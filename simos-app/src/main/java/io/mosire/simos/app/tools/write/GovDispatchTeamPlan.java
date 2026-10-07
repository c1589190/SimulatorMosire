package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.spi.PutInfoHandler;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.DismissStaffHandler;
import io.mosire.simos.unit.spi.SetArmyFormationHandler;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.gov.dispatchTeam} 的<b>纯推导</b>（阶段 13A 人员流转；P1.5 接完整 Social 工单路径）：从 GOV
 * 编制里出人，同批建<b>无标签纯人员单位</b>（可加 ArmyFormation 变武装调查组）+ 补家户经济行/账户 + 留行动记录，一批落一条 revision——<b>不碰</b>
 * {@link io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>调查组 = 纯人员单位 +（可选）小军队编制</b>（用户裁定 3/10 的口径）：人口腿 = 一张 {@code
 * social.SubmitHouseholdWorkOrder}（从政府家户 {@code hh-gov-<unitId>} 逐 share {@code TRANSFER_MEMBERS}
 * 到新人口家户 {@code hh-unit:<newUnitId>}）；单位腿 = {@code
 * unit.CreateUnit}（untagged，households=[新家户]，speed=6、 mobilityPerMille=900、position=来源 GOV
 * 当刻有效位置；<b>无 manpower</b>，已退役）；{@code armed=true} 时同批落 {@code unit.SetArmyFormation}（masterGov=来源
 * GOV、role="armed-team"）——不造任何“调查组”新类型。
 *
 * <p>★★ <b>来源口径 = 政府家户的 share-aware 份额瀑布</b>：只调 {@link
 * HouseholdManpowerAllocator#allocateFromHousehold}（{@code hh-gov-<unitId>} 的家户成员份额，MALE + {@link
 * AgeBracket#ADULT}；家户/lot 全序瀑布），本类不另写排序、过滤或扣减；不足 ⇒ 整条具名拒（不部分、不截断）。{@code staff[role] ≥ count}
 * 校验照旧（编制口径保持）。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-dispatch-team:<batchId>:<newUnitId>}；{@code plan =
 * CREATE_HOUSEHOLD(hh-unit:<newUnitId>, UNIT(newUnitId), profile=name+"·人口家户") + 逐 share
 * TRANSFER_MEMBERS(from=hh-gov:<unitId>, to=新家户, lotId, count=taken)}）→ {@code
 * unit.CreateUnit}（households=[新家户]、无 manpower）→（armed）{@code unit.SetArmyFormation} → {@code
 * economy.RegisterHousehold}（新家户经济行）→ {@code actor.EnsureHouseholdAccount}（新家户零余额账户，幂等）→ {@code
 * unit.DismissStaff}（{@code {unitId, role, count}}，出人不付待遇）→ {@code sd.PutInfo}（地址 = 来源 GOV
 * canonical， key={@code dispatchTeam}，value 含 armed 标记/新家户/来源 shares）。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：来源 GOV 存在且带 {@link
 * GovernmentFormation}；{@code count ≥ 1}；{@code roster[role] ≥ count}（缺省 role =
 * SCRIBE）否则具名拒（带现有/请求数字）； 来源 GOV 当刻必须有有效位置；新单位 id 未占用；政府家户必须同时在 {@code Unit.households()} 与 {@code
 * SocialData.households()} 里；新单位人口家户 id 未占用；政府家户的 MALE+ADULT 份额不足 ⇒ 整条拒。
 *
 * <p>★ <b>守恒</b>：{@code roster[role] − count == 出人后 roster}；{@code Σ share.taken == count ==
 * 新人口家户成员增量}； Plan 构造期逐值互校。
 */
final class GovDispatchTeamPlan {

  /** {@code social.SubmitHouseholdWorkOrder} 的命令类型（引用 social handler 常量）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** {@code unit.CreateUnit} 的命令类型（引用 unit handler 常量）。 */
  static final String CREATE_UNIT_TYPE = CreateUnitHandler.TYPE;

  /** {@code unit.SetArmyFormation} 的命令类型（仅 armed=true 才落；引用 unit handler 常量）。 */
  static final String SET_ARMY_FORMATION_TYPE = SetArmyFormationHandler.TYPE;

  /** {@code economy.RegisterHousehold} 的命令类型（引用 economy handler 常量）。 */
  static final String REGISTER_HOUSEHOLD_TYPE = EconomyRegisterHouseholdHandler.TYPE;

  /** {@code actor.EnsureHouseholdAccount} 的命令类型（引用 actor handler 常量）。 */
  static final String ENSURE_HOUSEHOLD_ACCOUNT_TYPE = EnsureHouseholdAccountHandler.TYPE;

  /** {@code unit.DismissStaff} 的命令类型（引用 unit handler 常量）。 */
  static final String DISMISS_STAFF_TYPE = DismissStaffHandler.TYPE;

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录；引用 sd handler 常量）。 */
  static final String PUT_INFO_TYPE = PutInfoHandler.TYPE;

  /** 新调查组单位的速度（控制方口径：纯人员单位、赶路快）。 */
  static final int NEW_UNIT_SPEED = 6;

  /** 新调查组单位的机动性（控制方口径 900‰）。 */
  static final int NEW_UNIT_MOBILITY_PER_MILLE = 900;

  /** 武装调查组的 ArmyFormation.role（控制方口径：通用接口，不造新类型）。 */
  static final String ARMED_TEAM_ROLE = "armed-team";

  /** {@code role} 缺省（缺省从 SCRIBE 出人）。 */
  static final StaffRole DEFAULT_ROLE = StaffRole.SCRIBE;

  /** 工单来源模块名（进 {@code social.SubmitHouseholdWorkOrder} 载荷与日志）。 */
  private static final String SOURCE_MODULE = "gov";

  private GovDispatchTeamPlan() {}

  /**
   * 纯推导入口（见类注的校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 来源 GOV 单位 id（必须带 {@link GovernmentFormation}）
   * @param count 出人数量（≥ 1，且不得超过该角色现有在编与政府家户的 MALE+ADULT 份额）
   * @param roleText 出人角色（可选；缺省 {@link #DEFAULT_ROLE}，只认 SCRIBE|YAMEN|POST）
   * @param armed 是否同批加 ArmyFormation（武装调查组 = 通用接口的小军队编制）
   * @param newUnitId 新单位 id（可选；缺省确定性生成）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  // ★ 测试/旧路径：全缺省儒略历时钟；生产路径由 CalendarService.clock() 传入。
  static Plan plan(
      SimulationState state,
      String unitId,
      long count,
      Optional<String> roleText,
      boolean armed,
      Optional<String> newUnitId) {
    return plan(state, unitId, count, roleText, armed, newUnitId, CalendarClock.julianDefault());
  }

  /**
   * 生产入口：历法时钟由调用方传入（MALE + ADULT 的年龄档判定只认这台钟）。
   *
   * @param clock 历法时钟（非空；生产路径 = CalendarService.clock()）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      long count,
      Optional<String> roleText,
      boolean armed,
      Optional<String> newUnitId,
      CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(roleText, "roleText");
    Objects.requireNonNull(newUnitId, "newUnitId");
    requireNonBlank(unitId, "unitId");
    if (count < 1L) {
      throw new IllegalArgumentException("出人数量 count 必须 ≥ 1: " + count);
    }
    StaffRole role = roleText.map(GovDispatchTeamPlan::parseRole).orElse(DEFAULT_ROLE);
    newUnitId.ifPresent(text -> requireNonBlank(text, "newUnitId"));
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp timestamp = state.meta().timestamp();
    long tick = timestamp.tick();
    Unit source = units.units().get(UnitId.parse(unitId));
    if (source == null) {
      throw new IllegalArgumentException("来源 GOV 单位不存在: " + unitId);
    }
    GovernmentFormation governmentFormation = requireGovernmentFormation(source, unitId);

    // ★★ Z4/C1：出人源选择（岗位户优先；没有岗位户且 posts 为空时维持旧档财政户口径）──────────────
    HouseholdId postHousehold = HouseholdId.parse(RaiseUnitPlan.householdIdFor(unitId));
    boolean postHouseholdMode = source.households().contains(postHousehold);
    if (!postHouseholdMode && governmentFormation.staffIsHouseholdProjection()) {
      throw new IllegalArgumentException(
          "GOV 单位 "
              + unitId
              + " 的 householdPosts 非空但没有岗位户 "
              + postHousehold.value()
              + "：岗位数据坏（posts 的键不可能 ⊆ Unit.households）；先补齐岗位户（Z3 simos.gov.expandHousehold / "
              + "unit.SetUnitHouseholds）再出人（不猜、不静默降级到财政户）");
    }
    long staffBefore = -1L;
    long staffAfter = -1L;
    SocialData social = ToolSupport.socialData(state);
    HouseholdId sourceHousehold;
    if (postHouseholdMode) {
      sourceHousehold = postHousehold;
      if (!social.households().containsKey(sourceHousehold)) {
        throw new IllegalArgumentException(
            "Social 里不存在岗位户 "
                + sourceHousehold.value()
                + "（GOV 单位 "
                + unitId
                + " 的出人源）：先由 Z3 simos.gov.expandHousehold / Z5 bootstrap 建户，或对齐 Unit.households"
                + "（不猜、不静默降级到财政户）");
      }
    } else {
      sourceHousehold = GovernmentHouseholds.of(unitId);
      if (!source.households().contains(sourceHousehold)) {
        throw new IllegalArgumentException(
            "GOV 单位 "
                + unitId
                + " 的 Unit.households 不含政府家户 "
                + sourceHousehold.value()
                + "：单位家户关系数据坏，出人源不明确；先 unit.SetGovFormation / 修数（不猜、不新建第二户）");
      }
      if (!social.households().containsKey(sourceHousehold)) {
        throw new IllegalArgumentException(
            "Social 里不存在政府家户 "
                + sourceHousehold.value()
                + "（GOV 单位 "
                + unitId
                + " 的旧档出人源）：先补该政府家户（如 simos.gov.createOffice 的 social.CreateHousehold）再派调查组"
                + "（不猜、不新建第二户）");
      }
      staffBefore = governmentFormation.staff().getOrDefault(role, 0L);
      if (staffBefore < count) {
        throw new IllegalArgumentException(
            "出人 " + role + " " + count + " 人超过现有在编: 现有 " + staffBefore + " < 请求 " + count);
      }
      staffAfter = staffBefore - count;
    }
    Optional<HexCoord> at = units.effectivePosition(source.id(), timestamp);
    if (at.isEmpty()) {
      throw new IllegalArgumentException("来源 GOV " + unitId + " 当刻没有有效位置：新单位落点无法确定；先 unit.PlaceAt");
    }
    String newId =
        GovSelectExamineesPlan.resolveNewUnitId(
            units, newUnitId, "team-" + unitId + "-" + tick + "-" + count, "newUnitId");
    String householdId = RaiseUnitPlan.householdIdFor(newId);
    if (social.households().containsKey(HouseholdId.parse(householdId))) {
      throw new IllegalArgumentException(
          "P1.5 新单位的人口家户 id 已被占用: " + householdId + "（先清掉同名家户，或换 newUnitId）");
    }
    HouseholdManpowerAllocator.Allocation allocation;
    try {
      allocation =
          HouseholdManpowerAllocator.allocateFromHousehold(
              social,
              sourceHousehold,
              count,
              clock,
              tick,
              Optional.of(Sex.MALE),
              Optional.of(AgeBracket.ADULT));
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException(
            (postHouseholdMode ? "岗位户 " : "政府家户 ")
                + sourceHousehold.value()
                + " 可选 MALE+ADULT 人口不足："
                + e.getMessage(),
            e);
      }
      throw e;
    }
    return new Plan(
        postHouseholdMode,
        unitId,
        newId,
        "调查组 " + unitId,
        at.get(),
        GovSelectExamineesPlan.residenceAt(social, at.get()),
        tick,
        count,
        role,
        staffBefore,
        staffAfter,
        armed,
        allocation.available(),
        householdId,
        sourceHousehold,
        allocation.shares());
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST，别的词给具名拒（不静默当缺省）。 */
  private static StaffRole parseRole(String roleText) {
    requireNonBlank(roleText, "role");
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** 单位必须带 {@link GovernmentFormation}（本工具只从 GOV 编制出人）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：本工具只从 GOV 编制出人；先 unit.SetGovFormation");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /** 饱和加法（非负 long；只用于守恒合计与拒因展示，不参与逐值扣减）。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /**
   * 一份调查组出人计划（全部字段是状态的纯函数；来源表在构造期冻结）。
   *
   * @param postHouseholdMode true = Z4 新世界岗位户模式（源 = {@code hh-unit:<unitId>}，无 DismissStaff
   *     腿）；false = 旧档财政户模式（源 = {@code hh-gov-<unitId>}，保留 DismissStaff 旧行为）
   * @param unitId 来源 GOV 单位 id
   * @param newUnitId 新调查组单位 id（untagged；armed 时才有 ArmyFormation）
   * @param unitName 新单位名
   * @param at 新单位落点 = 来源 GOV 当刻有效位置
   * @param residence P1.5：新人口家户 economy 视图的居住类型（at 是某城 at ⇒ URBAN，否则 RURAL）
   * @param tick 推导时的世界日
   * @param count 出人数量（= Σ来源 share.taken = 新人口家户成员增量；旧档另 = roster 减量）
   * @param role 出人角色
   * @param staffBefore 旧档该角色出人前在编；岗位户模式 = −1（staff 是投影，C4）
   * @param staffAfter 旧档该角色出人后在编（= staffBefore − count）；岗位户模式 = −1
   * @param armed 是否同批加 ArmyFormation
   * @param available 出人源家户全部合格 MALE+ADULT 份额合计（成功时 ≥ count）
   * @param householdId 新单位人口家户 id（{@code hh-unit:<newUnitId>}；location = UNIT(newUnitId)）
   * @param sourceHouseholdId 出人源家户（岗位户 {@code hh-unit:<unitId>} 或旧档政府家户 {@code
   *     hh-gov-<unitId>}；构造期已由 plan 前置校验存在）
   * @param sources 逐来源家户份额（家户/lot 全序瀑布序；Σtaken == count）
   */
  record Plan(
      boolean postHouseholdMode,
      String unitId,
      String newUnitId,
      String unitName,
      HexCoord at,
      ResidenceKind residence,
      long tick,
      long count,
      StaffRole role,
      long staffBefore,
      long staffAfter,
      boolean armed,
      long available,
      String householdId,
      HouseholdId sourceHouseholdId,
      List<HouseholdManpowerAllocator.ManpowerShare> sources) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(newUnitId, "newUnitId");
      requireNonBlank(unitName, "unitName");
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(residence, "residence");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      Objects.requireNonNull(role, "role");
      if (postHouseholdMode) {
        if (staffBefore != -1L || staffAfter != -1L) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：岗位户模式不得带 legacy staff 数字（" + staffBefore + "→" + staffAfter + "）");
        }
      } else if (staffBefore < count || staffAfter != staffBefore - count) {
        throw new IllegalArgumentException(
            "守恒破坏：staffBefore="
                + staffBefore
                + " − count="
                + count
                + " != staffAfter="
                + staffAfter);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      if (available < count) {
        throw new IllegalArgumentException("内部分摊不自洽：available=" + available + " < count=" + count);
      }
      requireNonBlank(householdId, "householdId");
      Objects.requireNonNull(sourceHouseholdId, "sourceHouseholdId");
      if (householdId.equals(sourceHouseholdId.value())) {
        throw new IllegalArgumentException("内部分摊不自洽：新单位人口家户与出人源家户相同 " + householdId);
      }
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        if (!share.householdId().equals(sourceHouseholdId)) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：来源家户必须是出人源 "
                  + sourceHouseholdId.value()
                  + "，实际 "
                  + share.householdId().value());
        }
        total = saturatedAdd(total, share.taken());
      }
      if (total != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源 share.taken=" + total + " != count=" + count + "（批载荷必须逐值对应）");
      }
    }

    /**
     * 工单确定性幂等键：{@code gov-dispatch-team:<batchId>:<newUnitId>}。{@code batchId} 是本工具 apply 生成的 batch
     * UUID（同一 apply 内稳定 ⇒ 同批可复现）。
     */
    String orderId(String batchId) {
      requireNonBlank(batchId, "batchId");
      return "gov-dispatch-team:" + batchId + ":" + newUnitId;
    }

    /**
     * P1.5 人口腿的<b>唯一</b>命令载荷（{@code social.SubmitHouseholdWorkOrder}）——第一步 {@code
     * CREATE_HOUSEHOLD}（location = {@code UNIT(newUnitId)}、画像 {@code name+"·人口家户"}、vitalRates
     * 空表），随后逐来源 {@code TRANSFER_MEMBERS(from=hh-gov:<unitId>, to=新家户, lotId, count=taken)}。
     */
    String submitHouseholdWorkOrderPayloadJson(String batchId, String reason) {
      requireNonBlank(reason, "reason");
      List<Map<String, Object>> steps = new ArrayList<>(sources.size() + 1);
      Map<String, Object> create = new LinkedHashMap<>();
      create.put("op", "CREATE_HOUSEHOLD");
      create.put("household", householdId);
      Map<String, Object> location = new LinkedHashMap<>();
      location.put("type", "UNIT");
      location.put("unitId", newUnitId);
      create.put("location", location);
      Map<String, Object> profile = new LinkedHashMap<>();
      profile.put("name", unitName + "·人口家户");
      create.put("profile", profile);
      create.put("vitalRates", List.of());
      steps.add(create);
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "TRANSFER_MEMBERS");
        step.put("from", share.householdId().value());
        step.put("to", householdId);
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId(batchId));
      payload.put("target", householdId);
      payload.put("reason", reason);
      payload.put("source", Map.of("module", SOURCE_MODULE));
      payload.put("plan", steps);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.CreateUnit} 载荷：untagged 纯人员单位（households=[新家户]、equipment=[]、speed=6、
     * mobilityPerMille=900）；<b>不再发已退役的 {@code manpower}</b>。
     */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", newUnitId);
      payload.put("name", unitName);
      payload.put("position", ToolSupport.hexCoord(at));
      payload.put("households", List.of(householdId));
      payload.put("equipment", List.of());
      payload.put("speed", NEW_UNIT_SPEED);
      payload.put("mobilityPerMille", NEW_UNIT_MOBILITY_PER_MILLE);
      return ToolSupport.json(payload);
    }

    /** {@code unit.SetArmyFormation} 载荷（{@link #armed} 为真时才可调用）：认来源 GOV 当主子。 */
    String setArmyFormationPayloadJson() {
      if (!armed) {
        throw new IllegalStateException("批不自洽：armed=false 却要组装 unit.SetArmyFormation 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", newUnitId);
      payload.put("masterGov", unitId);
      payload.put("role", ARMED_TEAM_ROLE);
      return ToolSupport.json(payload);
    }

    /**
     * {@code economy.RegisterHousehold} 载荷：落点 = {@code at}，居住类型 = {@link #residence()}，阶层 = {@code
     * landless_laborer}（无资产的中性档），参与率 = 0。
     */
    String registerHouseholdPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("q", at.q());
      payload.put("r", at.r());
      payload.put("residence", residence.value());
      payload.put("stratum", io.mosire.simos.economy.api.id.SocialClassId.LANDLESS_LABORER.value());
      payload.put("participationPerMille", 0);
      payload.put("reason", reason);
      return ToolSupport.json(payload);
    }

    /** {@code actor.EnsureHouseholdAccount} 载荷：给新人口家户补零余额账户（幂等）。 */
    String ensureHouseholdAccountPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("reason", reason);
      return ToolSupport.json(payload);
    }

    /** {@code unit.DismissStaff} 载荷（仅旧档财政户模式合法）：只减 roster、不支付、不回写（人在同批工单里转出）。 */
    String dismissStaffPayloadJson() {
      if (postHouseholdMode) {
        throw new IllegalStateException("批不自洽：岗位户模式（staff 是投影）不得组装 unit.DismissStaff 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.name());
      payload.put("count", count);
      return ToolSupport.json(payload);
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(7);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      types.add(CREATE_UNIT_TYPE);
      if (armed) {
        types.add(SET_ARMY_FORMATION_TYPE);
      }
      types.add(REGISTER_HOUSEHOLD_TYPE);
      types.add(ENSURE_HOUSEHOLD_ACCOUNT_TYPE);
      if (!postHouseholdMode) {
        types.add(DISMISS_STAFF_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含 mode/role/count/armed/新家户/来源 shares）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("mode", postHouseholdMode ? "post-household" : "treasury-household-legacy");
      value.put("govUnitId", unitId);
      value.put("newUnitId", newUnitId);
      value.put("householdId", householdId);
      value.put("sourceHouseholdId", sourceHouseholdId.value());
      value.put("role", role.name());
      value.put("count", count);
      value.put("armed", armed);
      value.put("tick", tick);
      value.put("at", ToolSupport.hexCoord(at));
      value.put("residence", residence.value());
      if (!postHouseholdMode) {
        value.put("staffBefore", staffBefore);
        value.put("staffAfter", staffAfter);
      }
      value.put("available", available);
      value.put("sourceCount", sources.size());
      value.put("sources", sourcesView());
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "派出"
          + (armed ? "武装" : "")
          + "调查组 "
          + newUnitId
          + "（"
          + count
          + " 人，role="
          + role
          + "，家户 "
          + householdId
          + "，tick "
          + tick
          + "）：来源 GOV "
          + unitId
          + (postHouseholdMode ? " 岗位户 " : " 编制 ")
          + (postHouseholdMode ? "" : staffBefore + "→" + staffAfter + "，")
          + "出人源家户 "
          + sourceHouseholdId.value()
          + " 转出 "
          + count
          + " 人（来源份额 "
          + sources.size()
          + " 条）；落点 "
          + hexText(at)
          + (armed ? "，ArmyFormation role=" + ARMED_TEAM_ROLE : "，无 module")
          + "；reason="
          + reason;
    }

    /** 逐来源视图（工具结果与 {@code sd.PutInfo.value.sources} 共用；{@code UNIT} 来源的 hex 为 null）。 */
    List<Map<String, Object>> sourcesView() {
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("householdId", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("taken", share.taken());
        row.put("hex", share.hex() == null ? null : ToolSupport.hexCoord(share.hex()));
        rows.add(row);
      }
      return rows;
    }
  }
}
