package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.AssignGovPostHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.absorbUnit} 的<b>纯推导</b>（阶段 13A 人员流转；2026-10-19 家户口径接线）：把纯人员单位 {@code
 * Unit.households()} 里的<b>真实家户成员</b>转移进 GOV 的政府家户 {@code hh-gov-<govUnitId>}（可顺带解散已空的源单位）， 一批落一条
 * revision——<b>不碰</b> {@link io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>S3b 口径</b>：{@code Unit.manpower} 已退役，吸收<b>不再写 Unit 侧第二本 headcount</b>；人只从 Social 家户里出、 进
 * Social 家户，人口权威始终在 Social。批里的 {@code unit.RecruitStaff} 只把编制 {@code staff[role] += count}，
 * 人员本身由同批第一条 {@code social.SubmitHouseholdWorkOrder} 的 {@code TRANSFER_MEMBERS} 真转移。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SubmitHouseholdWorkOrder}（逐来源 {@code
 * TRANSFER_MEMBERS(from=源家户, to=政府家户, lotId, count)}；{@code disbandDispatched} 时再逐源家户 {@code
 * SET_LOCATION(HEX=GOV 单位当刻 effectivePosition)}）→ {@code unit.RecruitStaff}（{@code role += count}）→
 * （{@code disbandDispatched} 才落）{@code unit.DisbandUnit} → {@code sd.PutInfo}（行动记录）。 ★ {@code
 * SET_LOCATION} 必须在 {@code unit.DisbandUnit} 之前：否则源家户的 {@code UNIT(sourceUnitId)} 会变成孤儿位置， 下轮
 * {@code HouseholdUnitConsistency} 会 unresolved（且无法单侧修复）。
 *
 * <p>★★ <b>来源口径（用户 2026-10-19 裁定 2）</b>：来源 = 源单位 {@code Unit.households()} 的成员份额；本类只调 {@link
 * HouseholdManpowerAllocator#allocateFromHouseholds}（显式过滤 {@code MALE} + {@link AgeBracket#ADULT}，
 * 排除集 {@code Set.of()}）抽恰好 {@code count} 人，不另写份额读取/排序/过滤/瀑布。合格份额不足 ⇒ 整条具名拒，不部分抽取。
 *
 * <p>★★ <b>disbandSource 的条件语义</b>：{@code disbandDispatched = disbandSource && 所有源家户迁移后剩余人口 == 0}
 * （{@code 剩余人口 = householdPopulation − Σ该户 taken}）。请求了但源仍有剩余人口 ⇒ <b>不解散</b>，在计划里给具名 {@code
 * disbandSkippedReason}（不丢剩下的人）。{@code disbandDispatched} 时若 GOV 单位当刻没有可确定的有效位置 ⇒ <b>plan 级拒</b>（不落
 * SET_LOCATION/DisbandUnit；不留下孤儿 {@code UNIT} 位置）。
 *
 * <p>★★ <b>守恒（Plan 构造期逐值互校）</b>：{@code Σ share.taken == count}；{@code Σ源家户迁移后剩余 == 源人口前 − count}；
 * {@code staffBefore + count == staffAfter}；每条 {@code share} 的家户都在源单位 {@code Unit.households()}
 * 里且逐户 taken 与 剩余人口互校。批载荷全部从同一份 {@code shares}/{@code remainders} 派生，没有第二份数字。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表按 {@link
 * HouseholdManpowerAllocator} 的全序瀑布序，家户剩余表按 household id 字符串升序，全部用 {@link List#copyOf} 冻结。
 */
final class GovAbsorbUnitPlan {

  /** 人口腿统一走 Social 家户工单（引用 social handler 常量，本类不另抄字面量）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** {@code unit.RecruitStaff} 的命令类型（旧档财政户模式才落；与 {@code RecruitStaffHandler.type()} 同字面）。 */
  static final String RECRUIT_STAFF_TYPE = "unit.RecruitStaff";

  /** {@code unit.AssignGovPost} 的命令类型（Z4 岗位户模式落点；只写 posts，绝不写 staff）。 */
  static final String ASSIGN_GOV_POST_TYPE = AssignGovPostHandler.TYPE;

  /** {@code unit.DisbandUnit} 的命令类型（仅 disbandDispatched 时才落）。 */
  static final String DISBAND_UNIT_TYPE = "unit.DisbandUnit";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 工单来源模块名（进 {@code social.SubmitHouseholdWorkOrder} 载荷与日志）。 */
  private static final String SOURCE_MODULE = "gov";

  private GovAbsorbUnitPlan() {}

  /**
   * 测试/旧路径入口：全缺省儒略历时钟。
   *
   * @see #plan(SimulationState, String, String, String, long, boolean, CalendarClock)
   */
  static Plan plan(
      SimulationState state,
      String govUnitId,
      String roleText,
      String sourceUnitId,
      long count,
      boolean disbandSource) {
    return plan(
        state,
        govUnitId,
        roleText,
        sourceUnitId,
        count,
        disbandSource,
        CalendarClock.julianDefault());
  }

  /**
   * 纯推导入口（见类注的校验、来源口径与守恒）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param govUnitId 吸收方 GOV 单位 id（必须存在、带 {@link GovernmentFormation}，且政府家户同时在其 {@code
   *     Unit.households()} 与 Social 里）
   * @param roleText 入编角色词表（SCRIBE|YAMEN|POST）
   * @param sourceUnitId 源人口单位 id（必须存在、无 module、{@code Unit.households()} 非空、且 ≠ 吸收方）
   * @param count 吸收人数（≥ 1；必须能从源家户份额里抽出 MALE+ADULT 恰好 count 人）
   * @param disbandSource 源被吸收后若所有源家户人口已为 0，是否同批 {@code SET_LOCATION + unit.DisbandUnit}
   * @param clock 历法时钟（年龄档现算唯一拼写点；生产路径 = {@code CalendarService.clock()}）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}、零 revision）
   */
  static Plan plan(
      SimulationState state,
      String govUnitId,
      String roleText,
      String sourceUnitId,
      long count,
      boolean disbandSource,
      CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(clock, "clock");
    requireNonBlank(govUnitId, "unitId");
    StaffRole role = parseRole(roleText);
    requireNonBlank(sourceUnitId, "sourceUnitId");
    if (count < 1L) {
      throw new IllegalArgumentException("吸收人数 count 必须 ≥ 1: " + count);
    }

    UnitState units = ToolSupport.unitState(state);
    UnitId govKey = UnitId.parse(govUnitId);
    Unit govUnit = units.units().get(govKey);
    if (govUnit == null) {
      throw new IllegalArgumentException("吸收方 GOV 单位不存在: " + govUnitId);
    }
    GovernmentFormation governmentFormation = requireGovernmentFormation(govUnit, govUnitId);

    UnitId sourceKey = UnitId.parse(sourceUnitId);
    Unit sourceUnit = units.units().get(sourceKey);
    if (sourceUnit == null) {
      throw new IllegalArgumentException("源单位不存在: " + sourceUnitId);
    }
    if (govKey.equals(sourceKey)) {
      throw new IllegalArgumentException(
          "源单位不得是吸收方本身（unitId == sourceUnitId == " + govUnitId + "）：不能自己吸收自己");
    }
    requirePurePersonnelUnit(sourceUnit, sourceUnitId);
    if (sourceUnit.households().isEmpty()) {
      throw new IllegalArgumentException(
          "源单位 "
              + sourceUnitId
              + " 的 Unit.households 为空：没有可吸收的家户人口（纯人员单位至少要有一个家户；先 "
              + "unit.SetUnitHouseholds / 合法组军路径补家户）");
    }

    // ★★ Z4/C1：吸收目标家户选择（岗位户优先；没有岗位户且 posts 为空时维持旧档财政户口径）──────────────
    HouseholdId postHousehold = HouseholdId.parse(RaiseUnitPlan.householdIdFor(govUnitId));
    boolean postHouseholdMode = govUnit.households().contains(postHousehold);
    if (!postHouseholdMode && governmentFormation.staffIsHouseholdProjection()) {
      throw new IllegalArgumentException(
          "GOV 单位 "
              + govUnitId
              + " 的 householdPosts 非空但没有岗位户 "
              + postHousehold.value()
              + "：岗位数据坏（posts 的键不可能 ⊆ Unit.households）；先补齐岗位户（Z3 simos.gov.expandHousehold / "
              + "unit.SetUnitHouseholds）再吸收（不猜、不静默降级到财政户）");
    }
    SocialData social = ToolSupport.socialData(state);
    HouseholdId targetHousehold;
    if (postHouseholdMode) {
      targetHousehold = postHousehold;
      if (!social.households().containsKey(targetHousehold)) {
        throw new IllegalArgumentException(
            "Social 里不存在岗位户 "
                + targetHousehold.value()
                + "（GOV 单位 "
                + govUnitId
                + " 的吸收目标）：先由 Z3 simos.gov.expandHousehold / Z5 bootstrap 建户，或对齐 Unit.households"
                + "（不猜、不静默降级到财政户）");
      }
    } else {
      // ★ 旧档：政府家户必须同时在 Unit.households（GovernmentHouseholdResolver 解析）与 Social 里；缺一 ⇒ plan 级拒。
      targetHousehold = GovernmentHouseholdResolver.requireGovernmentHousehold(govUnit, govUnitId);
      if (!social.households().containsKey(targetHousehold)) {
        throw new IllegalArgumentException(
            "Social 里不存在政府家户 "
                + targetHousehold.value()
                + "（GOV 单位 "
                + govUnitId
                + " 的旧档吸收目标）：先补该家户（如 simos.gov.createOffice）再吸收（不猜、不新建第二户）");
      }
    }

    // ★ staff 只在旧档模式参与：新世界的 staff 是岗位户人口/承诺投影（C4），由 app 现算，不由本工具直写。
    long staffBefore = -1L;
    long staffAfter = -1L;
    if (!postHouseholdMode) {
      staffBefore = governmentFormation.staff().getOrDefault(role, 0L);
      if (staffBefore > Long.MAX_VALUE - count) {
        throw new IllegalArgumentException(
            "吸收后 " + role + " 在编人数溢出 long: 现有 " + staffBefore + " + 请求 " + count);
      }
      staffAfter = staffBefore + count;
      // ★ staffCap 校验照旧（GovRecruitPlan 的口径）：现有 + count > cap ⇒ 具名拒（不截断）。
      Optional<Long> staffCap =
          Optional.ofNullable(governmentFormation.policy().staffCap().get(role));
      if (staffCap.isPresent() && staffBefore > staffCap.get() - count) {
        throw new IllegalArgumentException(
            "吸收 "
                + role
                + " "
                + count
                + " 人会超编制上限: 现有 "
                + staffBefore
                + " + 请求 "
                + count
                + " > staffCap "
                + staffCap.get()
                + "（不截断；先 unit.SetGovPolicy 提上限或减少 count）");
      }
    }

    LinkedHashSet<HouseholdId> sourceHouseholds = new LinkedHashSet<>(sourceUnit.households());
    if (sourceHouseholds.contains(targetHousehold)) {
      throw new IllegalArgumentException(
          "源单位 "
              + sourceUnitId
              + " 的 Unit.households 含吸收目标家户 "
              + targetHousehold.value()
              + "：把家户成员转移到它自己会被 HouseholdBook 拒（from == to）；先修正源家户列表或换源单位");
    }

    long tick = state.meta().timestamp().tick();
    // ★ 选人唯一拼写点 = 指定家户集合入口（源单位是 UNIT 位置，不适用辖区 HEX 扫描）+ 显式 MALE/ADULT 过滤。
    HouseholdManpowerAllocator.Allocation allocation;
    try {
      allocation =
          HouseholdManpowerAllocator.allocateFromHouseholds(
              social,
              sourceHouseholds,
              count,
              clock,
              tick,
              Optional.of(Sex.MALE),
              Optional.of(AgeBracket.ADULT),
              Set.of());
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException(
            "吸收来源不足：源单位 "
                + sourceUnitId
                + " 的家户合格人口（MALE+ADULT）抽不出 "
                + count
                + " 人："
                + e.getMessage(),
            e);
      }
      throw e;
    }

    // ★ 逐户汇总 taken（同一家户可有多条 lot 份额），再算每个源家户迁移后剩余人口 = populationBefore − Σtaken。
    Map<HouseholdId, Long> takenByHousehold = new LinkedHashMap<>();
    for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
      takenByHousehold.merge(share.householdId(), share.taken(), Long::sum);
    }
    List<HouseholdId> orderedHouseholds = new ArrayList<>(sourceHouseholds);
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    List<HouseholdRemainder> remainders = new ArrayList<>(orderedHouseholds.size());
    long sourcePopulationBefore = 0L;
    try {
      for (HouseholdId householdId : orderedHouseholds) {
        long populationBefore = social.householdPopulation(householdId);
        long taken = takenByHousehold.getOrDefault(householdId, 0L);
        if (taken > populationBefore) {
          throw new IllegalStateException(
              "选人层给出的份额超过家户人口：household="
                  + householdId
                  + " taken="
                  + taken
                  + " population="
                  + populationBefore
                  + "（SocialData 不变量已坏）");
        }
        sourcePopulationBefore = Math.addExact(sourcePopulationBefore, populationBefore);
        remainders.add(
            new HouseholdRemainder(householdId, populationBefore, taken, populationBefore - taken));
      }
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("源家户人口合计溢出 long：源单位 " + sourceUnitId + "（状态数据异常）", e);
    }
    if (sourcePopulationBefore < count) {
      throw new IllegalStateException(
          "内部分摊不自洽：源家户人口合计 " + sourcePopulationBefore + " < count=" + count + "（选人层本应整条拒）");
    }
    long sourcePopulationAfter = sourcePopulationBefore - count;
    boolean allEmptied = remainders.stream().allMatch(HouseholdRemainder::emptied);
    boolean disbandDispatched = disbandSource && allEmptied;

    Optional<HexCoord> govHex = units.effectivePosition(govKey, state.meta().timestamp());
    if (disbandDispatched && govHex.isEmpty()) {
      // ★ 用户二选一：没有位置 ⇒ plan 级拒（本文选定的那一种）。原因：SET_LOCATION 需要明确的 HEX；
      //   没有它就无法在 DisbandUnit 前把源家户从 UNIT(source) 摘出来 ⇒ 会留下孤儿 UNIT 位置。
      throw new IllegalArgumentException(
          "解散源单位需要先把源家户位置从 UNIT("
              + sourceUnitId
              + ") 摘到 GOV 单位当刻有效位置的 HEX，但 GOV 单位 "
              + govUnitId
              + " 当刻没有可确定的有效位置：本批整条拒（不落 SET_LOCATION/unit.DisbandUnit，避免留下孤儿 UNIT 位置）；"
              + "先给 GOV 单位 unit.PlaceAt 落格，或改用 disbandSource=false");
    }
    Optional<String> disbandSkippedReason =
        disbandSource && !disbandDispatched
            ? Optional.of(
                "disbandSource=true 但迁移后源家户仍有剩余人口："
                    + describeRemaining(remainders)
                    + "；本批不落 SET_LOCATION/unit.DisbandUnit，源单位保留（不丢剩余人口）")
            : Optional.empty();

    return new Plan(
        postHouseholdMode,
        govUnitId,
        targetHousehold,
        sourceUnitId,
        role,
        count,
        tick,
        staffBefore,
        staffAfter,
        sourcePopulationBefore,
        sourcePopulationAfter,
        allocation.shares(),
        remainders,
        govHex,
        disbandSource,
        disbandDispatched,
        disbandSkippedReason);
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST，别的词给具名拒（不静默当缺省）。 */
  private static StaffRole parseRole(String roleText) {
    if (roleText == null || roleText.isBlank()) {
      throw new IllegalArgumentException("role 必须是非空文本（SCRIBE|YAMEN|POST）");
    }
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** 吸收方必须带 {@link GovernmentFormation}（入编命令的领域前置）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：吸收只对 GOV 编制单位；先 unit.SetGovFormation");
  }

  /** 源单位必须无 module（纯人员单位）；带 Army/GOV 编制 ⇒ 各自的具名拒因。 */
  private static void requirePurePersonnelUnit(Unit unit, String unitId) {
    UnitModule module = unit.module().orElse(null);
    if (module == null) {
      return;
    }
    if (module instanceof ArmyFormation armyFormation) {
      throw new IllegalArgumentException(
          "源单位 "
              + unitId
              + " 带 ArmyFormation（role="
              + armyFormation.role()
              + "）：军队单位不是人口容器，不能被吸收；只有无 module 的纯人员单位才能被吸收"
              + "（先 simos.army.formatUnit 去掉 ArmyFormation，或改用别的源）");
    }
    if (module instanceof GovernmentFormation) {
      throw new IllegalArgumentException(
          "源单位 " + unitId + " 带 GovernmentFormation：GOV 单位不是人口容器，不能被吸收；只有无 module 的纯人员单位才能被吸收");
    }
    throw new IllegalArgumentException(
        "源单位 "
            + unitId
            + " 带 module "
            + module.getClass().getSimpleName()
            + "：只有无 module 的纯人员单位才能被吸收");
  }

  /** 剩余人口的可读文本（只列非零项；disbandSkippedReason 用）。 */
  private static String describeRemaining(List<HouseholdRemainder> remainders) {
    StringBuilder text = new StringBuilder();
    long total = 0L;
    for (HouseholdRemainder remainder : remainders) {
      if (remainder.populationAfter() <= 0L) {
        continue;
      }
      if (text.length() > 0) {
        text.append(", ");
      }
      text.append(remainder.householdId().value()).append('=').append(remainder.populationAfter());
      total = Math.addExact(total, remainder.populationAfter());
    }
    return text + "（共 " + total + " 人）";
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /**
   * 一个源家户在迁移后的人口账（全部字段是状态的纯函数）。
   *
   * @param householdId 源家户 id（∈ 源单位 {@code Unit.households()}）
   * @param populationBefore 迁移前家户人口（= Social 的 household population）
   * @param taken 本批从该户抽走的人数（Σ该户各 lot 的 share.taken；可为 0）
   * @param populationAfter 迁移后剩余人口（= before − taken；≥ 0）
   */
  record HouseholdRemainder(
      HouseholdId householdId, long populationBefore, long taken, long populationAfter) {

    HouseholdRemainder {
      Objects.requireNonNull(householdId, "householdId");
      if (populationBefore < 0L) {
        throw new IllegalArgumentException("populationBefore 不得为负: " + populationBefore);
      }
      if (taken < 0L || taken > populationBefore) {
        throw new IllegalArgumentException(
            "taken 必须在 [0, populationBefore]：taken="
                + taken
                + " populationBefore="
                + populationBefore);
      }
      if (populationAfter != populationBefore - taken) {
        throw new IllegalArgumentException(
            "守恒破坏：populationAfter="
                + populationAfter
                + " != populationBefore−taken="
                + (populationBefore - taken));
      }
    }

    /** 迁移后是否已清空（{@link Plan#disbandDispatched} 的判据）。 */
    boolean emptied() {
      return populationAfter == 0L;
    }
  }

  /**
   * 一份吸收计划（全部字段是状态的纯函数；来源 shares 与逐户剩余人口在构造期冻结并互校）。
   *
   * @param postHouseholdMode true = Z4 新世界岗位户模式（目标 = {@code hh-unit:<govUnitId>}，落 {@code
   *     unit.AssignGovPost}）；false = 旧档财政户模式（保留 {@code unit.RecruitStaff} 旧行为）
   * @param govUnitId 吸收方 GOV
   * @param targetHouseholdId 吸收目标家户（岗位户 {@code hh-unit:<govUnitId>} 或旧档政府家户 {@code
   *     hh-gov-<govUnitId>}；构造期已由 plan 前置校验同时在 Unit.households 与 Social）
   * @param sourceUnitId 源纯人员单位
   * @param role 入编角色
   * @param count 吸收人数（= Σ share.taken；旧档另 = roster 增量）
   * @param tick 推导时的世界日
   * @param staffBefore 旧档该角色吸收前在编；岗位户模式 = −1（staff 是投影，C4）
   * @param staffAfter 旧档该角色吸收后在编（= staffBefore + count）；岗位户模式 = −1
   * @param sourcePopulationBefore 源单位全部家户迁移前人口合计（= Σ remainders.populationBefore）
   * @param sourcePopulationAfter 源单位全部家户迁移后人口合计（= before − count；世界 Social 总人口不变）
   * @param shares 逐来源份额（Σtaken == count；每条来源家户都在源单位 households 里）
   * @param remainders 逐源家户迁移后剩余人口（与 shares 逐户互校；Σremaining == sourcePopulationAfter）
   * @param govHex GOV 单位当刻有效位置（disbandDispatched 时必在；SET_LOCATION 的目标 HEX）
   * @param disbandSource 调用方是否请求“源清空则解散”
   * @param disbandDispatched 批里是否真的落 {@code SET_LOCATION + unit.DisbandUnit}（disbandSource 且所有源家户剩余
   *     == 0）
   * @param disbandSkippedReason 请求了但没落解散时的具名原因（其余 = empty）
   */
  record Plan(
      boolean postHouseholdMode,
      String govUnitId,
      HouseholdId targetHouseholdId,
      String sourceUnitId,
      StaffRole role,
      long count,
      long tick,
      long staffBefore,
      long staffAfter,
      long sourcePopulationBefore,
      long sourcePopulationAfter,
      List<HouseholdManpowerAllocator.ManpowerShare> shares,
      List<HouseholdRemainder> remainders,
      Optional<HexCoord> govHex,
      boolean disbandSource,
      boolean disbandDispatched,
      Optional<String> disbandSkippedReason) {

    Plan {
      requireNonBlank(govUnitId, "govUnitId");
      Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
      requireNonBlank(sourceUnitId, "sourceUnitId");
      Objects.requireNonNull(role, "role");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (govUnitId.equals(sourceUnitId)) {
        throw new IllegalArgumentException("内部分摊不自洽：源单位与吸收方相同 " + govUnitId);
      }
      if (postHouseholdMode) {
        if (staffBefore != -1L || staffAfter != -1L) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：岗位户模式不得带 legacy staff 数字（" + staffBefore + "→" + staffAfter + "）");
        }
      } else {
        if (staffBefore < 0L) {
          throw new IllegalArgumentException("staffBefore 不得为负: " + staffBefore);
        }
        if (staffAfter != staffBefore + count) {
          throw new IllegalArgumentException(
              "守恒破坏：roster " + staffBefore + " + count " + count + " != " + staffAfter);
        }
      }
      if (sourcePopulationBefore < count
          || sourcePopulationAfter != sourcePopulationBefore - count) {
        throw new IllegalArgumentException(
            "守恒破坏：源人口 "
                + sourcePopulationBefore
                + " − count "
                + count
                + " != "
                + sourcePopulationAfter);
      }
      shares = List.copyOf(Objects.requireNonNull(shares, "shares"));
      remainders = List.copyOf(Objects.requireNonNull(remainders, "remainders"));
      Objects.requireNonNull(govHex, "govHex");
      Objects.requireNonNull(disbandSkippedReason, "disbandSkippedReason");

      Map<HouseholdId, HouseholdRemainder> remainderByHousehold = new LinkedHashMap<>();
      long beforeTotal = 0L;
      long afterTotal = 0L;
      for (HouseholdRemainder remainder : remainders) {
        if (remainderByHousehold.put(remainder.householdId(), remainder) != null) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：源家户剩余表有重复条目 " + remainder.householdId().value());
        }
        beforeTotal += remainder.populationBefore();
        afterTotal += remainder.populationAfter();
      }
      if (beforeTotal != sourcePopulationBefore) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ remainders.populationBefore="
                + beforeTotal
                + " != sourcePopulationBefore="
                + sourcePopulationBefore);
      }
      if (afterTotal != sourcePopulationAfter) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ remainders.populationAfter="
                + afterTotal
                + " != sourcePopulationAfter="
                + sourcePopulationAfter);
      }

      Map<HouseholdId, Long> takenByHousehold = new LinkedHashMap<>();
      long shareTotal = 0L;
      for (HouseholdManpowerAllocator.ManpowerShare share : shares) {
        if (!remainderByHousehold.containsKey(share.householdId())) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：share 来源家户不在源单位 households 表里: " + share.householdId().value());
        }
        if (share.householdId().equals(targetHouseholdId)) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：share 来源不得是吸收目标家户 " + targetHouseholdId.value() + "（自我转移会被域层拒）");
        }
        takenByHousehold.merge(share.householdId(), share.taken(), Long::sum);
        shareTotal += share.taken();
      }
      if (shareTotal != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ share.taken=" + shareTotal + " != count=" + count + "（批载荷必须逐值对应）");
      }
      for (HouseholdRemainder remainder : remainders) {
        long taken = takenByHousehold.getOrDefault(remainder.householdId(), 0L);
        if (taken != remainder.taken()) {
          throw new IllegalArgumentException(
              "守恒破坏：家户 "
                  + remainder.householdId().value()
                  + " 的 share.taken 合计="
                  + taken
                  + " != remainders.taken="
                  + remainder.taken());
        }
      }
      boolean allEmptied = remainders.stream().allMatch(HouseholdRemainder::emptied);
      if (disbandDispatched != (disbandSource && allEmptied)) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：disbandDispatched="
                + disbandDispatched
                + " 与 disbandSource="
                + disbandSource
                + "/所有源家户剩余为 0="
                + allEmptied
                + " 不一致");
      }
      if (disbandDispatched && govHex.isEmpty()) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：disbandDispatched 却没有 GOV 单位有效位置（SET_LOCATION 目标缺失）");
      }
      boolean reasonExpected = disbandSource && !disbandDispatched;
      if (disbandSkippedReason.isPresent() != reasonExpected) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：disbandSkippedReason 存在="
                + disbandSkippedReason.isPresent()
                + " 与 disbandSource="
                + disbandSource
                + "/disbandDispatched="
                + disbandDispatched
                + " 不一致");
      }
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      types.add(postHouseholdMode ? ASSIGN_GOV_POST_TYPE : RECRUIT_STAFF_TYPE);
      if (disbandDispatched) {
        types.add(DISBAND_UNIT_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * 工单确定性幂等键：{@code gov-absorb-unit:<batchId>:<govUnitId>:<sourceUnitId>} （{@code batchId} 由 Tool
     * 每次 apply 生成，本类不造随机数）。
     */
    String orderId(String batchId) {
      requireNonBlank(batchId, "batchId");
      return "gov-absorb-unit:" + batchId + ":" + govUnitId + ":" + sourceUnitId;
    }

    /**
     * {@code social.SubmitHouseholdWorkOrder} 载荷：{@code target = 吸收目标家户（岗位户或旧档财政户）}；{@code plan}
     * 先逐来源 {@code TRANSFER_MEMBERS(from=源家户, to=目标家户, lotId, count=taken)}；{@code
     * disbandDispatched} 时再逐源家户 {@code SET_LOCATION(location = {type:"HEX", hex {q,r}} = GOV
     * 单位当刻有效位置)}。 {@code SET_LOCATION} 在同一工单里排在 {@code TRANSFER_MEMBERS} 之后、{@code
     * unit.DisbandUnit} 之前。
     */
    String submitHouseholdWorkOrderPayloadJson(String batchId, String reason) {
      requireReason(reason);
      List<Map<String, Object>> steps =
          new ArrayList<>(shares.size() + (disbandDispatched ? remainders.size() : 0));
      for (HouseholdManpowerAllocator.ManpowerShare share : shares) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "TRANSFER_MEMBERS");
        step.put("from", share.householdId().value());
        step.put("to", targetHouseholdId.value());
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      if (disbandDispatched) {
        HexCoord hex =
            govHex.orElseThrow(
                () -> new IllegalStateException("批不自洽：disbandDispatched 却缺 SET_LOCATION 目标位置"));
        for (HouseholdRemainder remainder : remainders) {
          Map<String, Object> location = new LinkedHashMap<>();
          location.put("type", "HEX");
          location.put("hex", ToolSupport.hexCoord(hex));
          Map<String, Object> step = new LinkedHashMap<>();
          step.put("op", "SET_LOCATION");
          step.put("household", remainder.householdId().value());
          step.put("location", location);
          steps.add(step);
        }
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId(batchId));
      payload.put("target", targetHouseholdId.value());
      payload.put("reason", reason);
      payload.put("source", Map.of("module", SOURCE_MODULE));
      payload.put("plan", steps);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.AssignGovPost} 载荷（仅岗位户模式合法）：{@code {unitId, household, role}}；只写 {@code
     * householdPosts}，<b>绝不写 staff</b>（C4）。
     */
    String assignGovPostPayloadJson() {
      if (!postHouseholdMode) {
        throw new IllegalStateException("批不自洽：旧档财政户模式不得组装 unit.AssignGovPost 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", govUnitId);
      payload.put("household", targetHouseholdId.value());
      payload.put("role", role.name());
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.RecruitStaff} 载荷（仅旧档财政户模式合法）：{@code {unitId, role, count, sources}}；{@code
     * sources} = 逐来源 {@code {kind:"household", id, lotId, count}}，与 {@link #shares} 逐值对应（命令本身只入编、
     * 不扣人；扣人在同批工单）。
     */
    String recruitStaffPayloadJson() {
      if (postHouseholdMode) {
        throw new IllegalStateException("批不自洽：岗位户模式（staff 是投影）不得组装 unit.RecruitStaff 载荷");
      }
      List<Map<String, Object>> rows = new ArrayList<>(shares.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : shares) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "household");
        row.put("id", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("count", share.taken());
        rows.add(row);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", govUnitId);
      payload.put("role", role.name());
      payload.put("count", count);
      payload.put("sources", rows);
      return ToolSupport.json(payload);
    }

    /** {@code unit.DisbandUnit} 载荷（{@link #disbandDispatched} 为真时才可调用）。 */
    String disbandUnitPayloadJson() {
      if (!disbandDispatched) {
        throw new IllegalStateException("批不自洽：不落解散却要组装 unit.DisbandUnit 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", sourceUnitId);
      return ToolSupport.json(payload);
    }

    /** 逐来源份额视图（工具结果与 {@code sd.PutInfo.value.shares} 共用；保序）。 */
    List<Map<String, Object>> sharesView() {
      List<Map<String, Object>> rows = new ArrayList<>(shares.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : shares) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("householdId", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("taken", share.taken());
        // ★ 源家户是 UNIT 位置（unit.households 不变量）⇒ 这里通常是 null；不用 ToolSupport.hexCoord(null) 以免 NPE。
        row.put("hex", share.hexOptional().map(ToolSupport::hexCoord).orElse(null));
        rows.add(row);
      }
      return List.copyOf(rows);
    }

    /** 逐源家户迁移后剩余人口视图（工具结果与 {@code sd.PutInfo.value.householdRemainders} 共用；保序）。 */
    List<Map<String, Object>> remaindersView() {
      List<Map<String, Object>> rows = new ArrayList<>(remainders.size());
      for (HouseholdRemainder remainder : remainders) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("householdId", remainder.householdId().value());
        row.put("populationBefore", remainder.populationBefore());
        row.put("taken", remainder.taken());
        row.put("populationAfter", remainder.populationAfter());
        row.put("emptied", remainder.emptied());
        rows.add(row);
      }
      return List.copyOf(rows);
    }

    /** SET_LOCATION 目标 HEX 的行内视图；只有真的落 SET_LOCATION（disbandDispatched）时才给，其余为 null。 */
    Map<String, Object> setLocationHexView() {
      return disbandDispatched ? govHex.map(ToolSupport::hexCoord).orElse(null) : null;
    }

    /**
     * {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含
     * govUnitId/sourceUnitId/role/count/shares/disbanded/disbandSkippedReason）。
     */
    String infoValueJson(String reason) {
      requireReason(reason);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("mode", postHouseholdMode ? "post-household" : "treasury-household-legacy");
      value.put("govUnitId", govUnitId);
      value.put("targetHouseholdId", targetHouseholdId.value());
      if (!postHouseholdMode) {
        // ★ 旧档键名兼容：目标 = hh-gov-<govUnitId>。
        value.put("governmentHouseholdId", targetHouseholdId.value());
      }
      value.put("sourceUnitId", sourceUnitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("tick", tick);
      if (!postHouseholdMode) {
        value.put("staffBefore", staffBefore);
        value.put("staffAfter", staffAfter);
      }
      value.put("sourcePopulationBefore", sourcePopulationBefore);
      value.put("sourcePopulationAfter", sourcePopulationAfter);
      value.put("shares", sharesView());
      value.put("householdRemainders", remaindersView());
      value.put("disbandSource", disbandSource);
      value.put("disbanded", disbandDispatched);
      value.put("disbandSkippedReason", disbandSkippedReason.orElse(null));
      value.put("setLocationHex", setLocationHexView());
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireReason(reason);
      return "吸收 "
          + sourceUnitId
          + " 的家户人口 → "
          + govUnitId
          + " 的 "
          + role
          + " "
          + count
          + " 人（tick "
          + tick
          + "，"
          + (postHouseholdMode ? "岗位户" : "旧档财政户")
          + "）：源家户人口 "
          + sourcePopulationBefore
          + "→"
          + sourcePopulationAfter
          + (postHouseholdMode
              ? "，落岗位指派（unit.AssignGovPost，不写 staff）"
              : "，在编 " + staffBefore + "→" + staffAfter)
          + (disbandDispatched
              ? "；源已清空，同批 SET_LOCATION("
                  + (setLocationHexView() == null ? "(缺)" : setLocationHexView())
                  + ") + unit.DisbandUnit"
              : disbandSkippedReason.map(text -> "；未解散（" + text + "）").orElse("；未请求解散源"))
          + "；reason="
          + reason;
    }

    private static void requireReason(String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空文本");
      }
    }
  }
}
