package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.gov.retireStaff} 的<b>纯推导</b>（阶段 13A 人员流转；P1.2 家户工单；Z4/C1 双模式改向）：离编 + 按政策从国库一次性
 * 支付退休待遇（支付口径复用 {@link GovDismissPlan#paymentFor}，一处推导）+ 从<b>官吏岗位户</b> <b>转出真实成员</b>到明确目标家户 +
 * 留行动记录。一批落一条 revision，<b>不碰</b> {@link io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>Z4/C1 双模式（过渡，非破坏性）</b>：
 *
 * <ol>
 *   <li><b>岗位户模式（新世界）</b>：GOV 的 {@code Unit.households} 含 {@code hh-unit:<unitId>} ⇒ 退休源 =
 *       该岗位户；人员从岗位户转出；<b>不写 unit.DismissStaff</b>（staff 是这些家户人口/承诺的投影，C4）；岗位条目保留 （空户 = 空缺岗位，承诺释放归
 *       Z3）；
 *   <li><b>财政户模式（旧档过渡）</b>：没有岗位户且 {@code householdPosts} 为空 ⇒ 退休源 = {@code hh-gov-<unitId>}
 *       （旧行为逐值保留：roster 校验 + {@code unit.DismissStaff} 腿 + 待遇支付）；
 *   <li>没有岗位户但 {@code householdPosts} 非空 ⇒ 数据坏，具名拒（不猜、不静默降级到财政户）。
 * </ol>
 *
 * <p>★★ <b>目标家户必须明确（缺一 ⇒ plan 级具名拒，人不能凭空消失）</b>：
 *
 * <ol>
 *   <li>{@code toHouseholdId} 精确指定：必须存在于 {@code SocialData.households()}，且 ≠ 本次退休源家户；
 *   <li>未给 {@code toHouseholdId} 但给了 {@code reinsertQ}/{@code reinsertR}（必须成对）：在该 hex 的 {@code
 *       social.householdsAt(hex)} 里按 {@code HouseholdId.value()} 升序取<b>第一个有人口</b>的家户；没有 ⇒ 具名拒；
 *   <li>两者都没给 ⇒ <b>plan 级具名拒</b>（本工具不再允许"人不回写社会"）。
 * </ol>
 *
 * <p>★★ <b>选人唯一拼写点</b>：只调 {@link HouseholdManpowerAllocator#allocateFromHousehold}（家户份额瀑布；本类不另写
 * 排序/过滤/扣减），退休调用<b>不传过滤</b>（{@code Optional.empty()} / {@code Optional.empty()}）——官吏户里可能含
 * 各年龄/性别的家属，过滤过窄会把真实人口误判为“不足”。守恒：{@code Σ take == count} 在 Plan 构造期逐值互校。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户>} 确定性幂等键；target = 目标家户；逐来源 {@code
 * TRANSFER_MEMBERS(from=退休源, to=目标家户, lotId, count=taken)}）→ [旧档：{@code unit.DismissStaff}] → （待遇
 * &gt; 0）{@code actor.AdjustAccounts} → {@code sd.PutInfo}（地址 = 单位 canonical，key={@code
 * retireStaff}， value=JSON <b>字符串</b>，含目标家户/来源 shares，note=人可读摘要）。全部共享同一 batchId 与同一
 * branch/expectedRevision ⇒ 一条 revision。
 *
 * <p>★★ <b>守恒</b>：退休源家户人口 前 − count == 后；待遇支付额 == {@code retirementPerStaff × count}；目标家户人口 前 +
 * count == 后；世界 Social 总人口不变；旧档模式另加 roster 前 − count == 后。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表按 {@code
 * HouseholdManpowerAllocator} 的全序瀑布序，用 {@link List#copyOf} 冻结。
 */
final class GovRetireStaffPlan {

  /** {@code social.SubmitHouseholdWorkOrder} 的命令类型（与 handler 的 {@code TYPE} 同源）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** {@code unit.DismissStaff} 的命令类型（旧档财政户模式才落；与 {@code DismissStaffHandler.TYPE} 同字面）。 */
  static final String DISMISS_STAFF_TYPE = "unit.DismissStaff";

  /** {@code actor.AdjustAccounts} 的命令类型（仅待遇 &gt; 0 才落）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 工单来源模块名（进 {@code social.SubmitHouseholdWorkOrder} 载荷与日志）。 */
  private static final String SOURCE_MODULE = "gov";

  private GovRetireStaffPlan() {}

  /**
   * 纯推导入口（见类注的双模式、目标家户前置、待遇口径与选人守恒）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 离编主体（带 GovernmentFormation 的 GOV）
   * @param roleText 行政角色词表（SCRIBE|YAMEN|POST）
   * @param count 离编人数（≥ 1；新世界 ≤ 岗位户人口，旧档另 ≤ 现有在编）
   * @param toHouseholdId 精确目标家户 id（可选；给了必须存在于 Social、且 ≠ 退休源；与 reinsert 二选一）
   * @param reinsertQ 回退目标格 q（可选；必须与 reinsertR 成对；按 hex 选第一个有人口的家户）
   * @param reinsertR 回退目标格 r（可选；必须与 reinsertQ 成对）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      String roleText,
      long count,
      Optional<String> toHouseholdId,
      Optional<Long> reinsertQ,
      Optional<Long> reinsertR) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(toHouseholdId, "toHouseholdId");
    Objects.requireNonNull(reinsertQ, "reinsertQ");
    Objects.requireNonNull(reinsertR, "reinsertR");
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 必须是非空文本");
    }
    StaffRole role = parseRole(roleText);
    if (count < 1L) {
      throw new IllegalArgumentException("离编人数 count 必须 ≥ 1: " + count);
    }
    if (reinsertQ.isPresent() != reinsertR.isPresent()) {
      throw new IllegalArgumentException(
          "reinsertQ 与 reinsertR 必须成对给出（要么都给、要么都不给）: reinsertQ="
              + reinsertQ.orElse(null)
              + "，reinsertR="
              + reinsertR.orElse(null));
    }
    if (toHouseholdId.isPresent() && (reinsertQ.isPresent() || reinsertR.isPresent())) {
      throw new IllegalArgumentException(
          "toHouseholdId 与 reinsertQ/reinsertR 是两种互斥的目标家户定义：一次只给一种"
              + "（精确指定优先，避免两处口径打架；要按格解析就先不要给 toHouseholdId）");
    }

    UnitState units = ToolSupport.unitState(state);
    Unit unit = units.units().get(UnitId.parse(unitId));
    if (unit == null) {
      throw new IllegalArgumentException("GOV 单位不存在: " + unitId);
    }
    GovernmentFormation governmentFormation = requireGovernmentFormation(unit, unitId);
    SocialData social = ToolSupport.socialData(state);

    // ── Z4/C1：退休源选择（岗位户优先；没有岗位户时按旧档口径回落到财政户）─────────────────────────
    HouseholdId postHousehold = HouseholdId.parse(RaiseUnitPlan.householdIdFor(unitId));
    boolean postHouseholdMode = unit.households().contains(postHousehold);
    HouseholdId sourceHousehold;
    long legacyStaffBefore = -1L;
    long legacyStaffAfter = -1L;
    if (postHouseholdMode) {
      if (!social.households().containsKey(postHousehold)) {
        throw new IllegalArgumentException(
            "GOV 单位 "
                + unitId
                + " 的 Unit.households 含岗位户 "
                + postHousehold.value()
                + "，但 Social 里没有它（家户位置账不一致）：先补 Social 家户或修 unit.SetUnitHouseholds"
                + "（不猜、不静默降级到财政户）");
      }
      sourceHousehold = postHousehold;
    } else {
      if (governmentFormation.staffIsHouseholdProjection()) {
        throw new IllegalArgumentException(
            "GOV 单位 "
                + unitId
                + " 的 householdPosts 非空但没有岗位户 "
                + postHousehold.value()
                + "：岗位数据坏（posts 的键不可能 ⊆ Unit.households）；先补齐岗位户再退休（不猜、不静默降级）");
      }
      HouseholdId treasuryHousehold = GovernmentHouseholds.of(unitId);
      if (!unit.households().contains(treasuryHousehold)) {
        throw new IllegalArgumentException(
            "GOV 单位 "
                + unitId
                + " 的 Unit.households 不含政府家户 "
                + treasuryHousehold.value()
                + "：单位家户关系数据坏，退休源不明确；先 unit.SetGovFormation / 修数"
                + "（不猜、不新建第二户）");
      }
      if (!social.households().containsKey(treasuryHousehold)) {
        throw new IllegalArgumentException(
            "Social 里不存在政府家户 "
                + treasuryHousehold.value()
                + "（GOV 单位 "
                + unitId
                + " 的旧档退休源）：先补该政府家户（如 simos.gov.createOffice 的 social.CreateHousehold）再退休"
                + "（不猜、不新建第二户）");
      }
      sourceHousehold = treasuryHousehold;
      legacyStaffBefore = governmentFormation.staff().getOrDefault(role, 0L);
      if (legacyStaffBefore < count) {
        throw new IllegalArgumentException(
            "离编 " + role + " " + count + " 人超过现有在编: 现有 " + legacyStaffBefore + " < 请求 " + count);
      }
      legacyStaffAfter = legacyStaffBefore - count;
    }

    long sourcePopulationBefore = social.householdPopulation(sourceHousehold);
    if (sourcePopulationBefore < count) {
      throw new IllegalArgumentException(
          (postHouseholdMode ? "岗位户 " : "政府家户 ")
              + sourceHousehold.value()
              + " 人口不足：现有 "
              + sourcePopulationBefore
              + " < 退休请求 "
              + count
              + "（退休必须从退休源转出真实成员；先 simos.gov.recruit 补人，或减少 count）");
    }

    // ── 目标家户解析（精确优先；未给则按 reinsert hex 取第一个有人口的家户）────────────────────────
    HouseholdId targetHousehold;
    Optional<HexCoord> targetHex = Optional.empty();
    if (toHouseholdId.isPresent()) {
      HouseholdId requested = HouseholdId.parse(toHouseholdId.get());
      Household target = social.households().get(requested);
      if (target == null) {
        throw new IllegalArgumentException(
            "toHouseholdId 指向的家户不存在: " + requested.value() + "（目标家户必须在 Social 里；本工具不猜、不新建第二户）");
      }
      if (requested.equals(sourceHousehold)) {
        throw new IllegalArgumentException(
            "toHouseholdId 不得是退休源家户 " + sourceHousehold.value() + "（源与目标相同会被域层拒；人必须转到别的家户）");
      }
      targetHousehold = requested;
      if (target.location() instanceof HouseholdLocation.Hex at) {
        targetHex = Optional.of(at.hex());
      }
    } else if (reinsertQ.isPresent()) {
      int q = toInt(reinsertQ.get(), "reinsertQ");
      int r = toInt(reinsertR.get(), "reinsertR");
      HexCoord hex = new HexCoord(q, r);
      targetHousehold = firstPopulatedHouseholdAt(social, hex);
      if (targetHousehold.equals(sourceHousehold)) {
        throw new IllegalArgumentException(
            "回写格 "
                + hexText(hex)
                + " 解析出的目标家户是退休源家户 "
                + sourceHousehold.value()
                + "：拒绝自我转移（请改正该格家户数据或改用 toHouseholdId）");
      }
      targetHex = Optional.of(hex);
    } else {
      throw new IllegalArgumentException(
          "退休必须给目标家户：给 toHouseholdId（精确指定）或 reinsertQ/reinsertR"
              + "（在该 hex 按 household id 升序取第一个有人口的家户）；两者都没给 ⇒ 拒绝"
              + "（人不能凭空消失，也不会留在退休源家户）");
    }

    long targetPopulationBefore = social.householdPopulation(targetHousehold);
    long targetPopulationAfter;
    try {
      targetPopulationAfter = Math.addExact(targetPopulationBefore, count);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "目标家户 "
              + targetHousehold.value()
              + " 接收 "
              + count
              + " 人后人口溢出 long: 现有 "
              + targetPopulationBefore,
          e);
    }

    long tick = state.meta().timestamp().tick();
    GovDismissPlan.Payment payment =
        GovDismissPlan.paymentFor(state, unit, governmentFormation, count);

    // ★ 选人唯一拼写点：指定家户入口（退休源是 UNIT 位置，不适用辖区 HEX 扫描），不传过滤。
    HouseholdManpowerAllocator.Allocation allocation;
    try {
      allocation =
          HouseholdManpowerAllocator.allocateFromHousehold(
              social,
              sourceHousehold,
              count,
              CalendarClock.julianDefault(), // 无过滤 ⇒ 时钟不参与选人；保留参数只为未来显式过滤
              tick,
              Optional.empty(),
              Optional.empty());
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException(
            (postHouseholdMode ? "岗位户 " : "政府家户 ")
                + sourceHousehold.value()
                + " 可选人口不足："
                + e.getMessage(),
            e);
      }
      throw e;
    }
    return new Plan(
        postHouseholdMode,
        unitId,
        role,
        count,
        tick,
        legacyStaffBefore,
        legacyStaffAfter,
        payment.retirementPerStaff(),
        payment.payment(),
        payment.treasuryLocation(),
        payment.availableSilver(),
        sourceHousehold,
        sourcePopulationBefore,
        targetHousehold,
        targetHex,
        targetPopulationBefore,
        targetPopulationAfter,
        allocation.available(),
        allocation.shares());
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

  /** 单位必须带 {@link GovernmentFormation}（退休前置；消息给出下一步）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：退休只对 GOV 单位；先 unit.SetGovFormation");
  }

  /** {@code reinsertQ}/{@code reinsertR} 解析出的 hex：按家户 id 升序取第一个有人口的家户；没有 ⇒ 具名拒。 */
  private static HouseholdId firstPopulatedHouseholdAt(SocialData social, HexCoord hex) {
    List<Household> households = new ArrayList<>(social.householdsAt(hex));
    households.sort(Comparator.comparing(household -> household.id().value()));
    for (Household household : households) {
      if (social.householdPopulation(household.id()) > 0L) {
        return household.id();
      }
    }
    throw new IllegalArgumentException(
        "回写格 "
            + hexText(hex)
            + " 上没有有人口的家户（按 household id 升序取第一个有人口的家户，找不到 ⇒ 拒绝；"
            + "不静默丢人、不并入最小批次、不新建家户）。先在该格建/迁入有人口的家户，或改用 toHouseholdId 精确指定");
  }

  /** long → int（回写格坐标；超 int ⇒ 具名拒，不静默截断）。 */
  private static int toInt(long value, String field) {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(field + " 超出 int 坐标范围: " + value);
    }
    return (int) value;
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /** 格的行内视图（{@code {q,r}}；工具结果与行动记录共用）。 */
  static Map<String, Object> hexView(HexCoord at) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", at.q());
    view.put("r", at.r());
    return view;
  }

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于守恒合计与拒因展示，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason 必须是非空文本");
    }
  }

  /**
   * 一份退休计划（全部字段是状态的纯函数；支付推导来自 {@link GovDismissPlan#paymentFor} 一处，来源 shares 在构造期冻结并互校）。
   *
   * @param postHouseholdMode true = 新世界岗位户模式（源 = {@code hh-unit:<unitId>}，无 DismissStaff 腿）；false =
   *     旧档财政户模式
   * @param unitId 离编主体
   * @param role 行政角色
   * @param count 离编人数
   * @param tick 推导时的世界日
   * @param legacyStaffBefore 旧档模式的该角色现有在编；岗位户模式 = −1
   * @param legacyStaffAfter 旧档模式的离编后在编；岗位户模式 = −1
   * @param retirementPerStaff 政策里的每人一次性退休待遇（银/人）
   * @param payment = retirementPerStaff × count
   * @param treasuryLocation 国库落点（payment=0 时空）
   * @param availableSilver 国库可支配银（payment=0 时 0）
   * @param sourceHouseholdId 退休源家户（岗位户或旧档财政户；≠ target）
   * @param sourcePopulationBefore 退休前退休源人口（≥ count）
   * @param targetHouseholdId 目标家户（已存在于 Social；≠ source）
   * @param targetHex 目标家户的格（HEX 位置）；{@code UNIT} 位置或无 reinsert 解析时为 empty
   * @param targetPopulationBefore 退休前目标家户人口
   * @param targetPopulationAfter 退休后目标家户人口（= before + count）
   * @param available 退休源全部合格份额合计（成功时 = 退休源人口）
   * @param sources 逐 lot 转出份额（Σtaken == count；每条来源都是退休源家户）
   */
  record Plan(
      boolean postHouseholdMode,
      String unitId,
      StaffRole role,
      long count,
      long tick,
      long legacyStaffBefore,
      long legacyStaffAfter,
      long retirementPerStaff,
      long payment,
      Optional<HexCoord> treasuryLocation,
      long availableSilver,
      HouseholdId sourceHouseholdId,
      long sourcePopulationBefore,
      HouseholdId targetHouseholdId,
      Optional<HexCoord> targetHex,
      long targetPopulationBefore,
      long targetPopulationAfter,
      long available,
      List<HouseholdManpowerAllocator.ManpowerShare> sources) {

    Plan {
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("unitId 不得为空白");
      }
      Objects.requireNonNull(role, "role");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (postHouseholdMode) {
        if (legacyStaffBefore != -1L || legacyStaffAfter != -1L) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：岗位户模式不得带 legacy staff 数字（"
                  + legacyStaffBefore
                  + "→"
                  + legacyStaffAfter
                  + "）");
        }
      } else if (legacyStaffBefore < count || legacyStaffAfter != legacyStaffBefore - count) {
        throw new IllegalArgumentException(
            "守恒破坏：legacy staffBefore="
                + legacyStaffBefore
                + " − count="
                + count
                + " != staffAfter="
                + legacyStaffAfter);
      }
      if (retirementPerStaff < 0L || payment < 0L) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：retirementPerStaff=" + retirementPerStaff + " payment=" + payment);
      }
      Objects.requireNonNull(treasuryLocation, "treasuryLocation");
      if (payment == 0L) {
        if (treasuryLocation.isPresent() || availableSilver != 0L) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：payment=0 却带国库落点/可支配银（" + treasuryLocation + " / " + availableSilver + "）");
        }
      } else {
        if (treasuryLocation.isEmpty() || availableSilver < payment) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：payment="
                  + payment
                  + " 需要国库落点与足额可支配银（"
                  + treasuryLocation
                  + " / "
                  + availableSilver
                  + "）");
        }
      }
      Objects.requireNonNull(sourceHouseholdId, "sourceHouseholdId");
      Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
      Objects.requireNonNull(targetHex, "targetHex");
      if (sourceHouseholdId.equals(targetHouseholdId)) {
        throw new IllegalArgumentException("内部分摊不自洽：退休源家户与目标家户相同 " + sourceHouseholdId.value());
      }
      if (sourcePopulationBefore < count) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：退休源人口 " + sourcePopulationBefore + " < count=" + count);
      }
      if (targetPopulationBefore < 0L || targetPopulationAfter < targetPopulationBefore) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：目标家户人口 " + targetPopulationBefore + "→" + targetPopulationAfter);
      }
      long expectedTargetAfter;
      try {
        expectedTargetAfter = Math.addExact(targetPopulationBefore, count);
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(
            "目标家户人口溢出 long: before=" + targetPopulationBefore + " + count=" + count, e);
      }
      if (targetPopulationAfter != expectedTargetAfter) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：目标家户人口后 " + targetPopulationAfter + " != before+count=" + expectedTargetAfter);
      }
      if (available < count) {
        throw new IllegalArgumentException("内部分摊不自洽：available=" + available + " < count=" + count);
      }
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        if (!share.householdId().equals(sourceHouseholdId)) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：来源家户必须是退休源 "
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

    /** 退休源家户退休后人口（= before − count；构造期已保证非负）。 */
    long sourcePopulationAfter() {
      return sourcePopulationBefore - count;
    }

    /** 是否要落 {@code actor.AdjustAccounts}（待遇 &gt; 0 才落）。 */
    boolean hasPayment() {
      return payment > 0L;
    }

    /**
     * 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）： {@code social.SubmitHouseholdWorkOrder} →
     * [旧档：{@code unit.DismissStaff}] → （待遇 &gt; 0）{@code actor.AdjustAccounts} → {@code
     * sd.PutInfo}。
     */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      if (!postHouseholdMode) {
        types.add(DISMISS_STAFF_TYPE);
      }
      if (hasPayment()) {
        types.add(ADJUST_ACCOUNTS_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * 工单确定性幂等键：{@code gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户>}。同一批参数在同一 tick 重放 ⇒
     * 命中幂等键、整单具名拒，不重复改人口。
     */
    String orderId() {
      return "gov-retire:"
          + unitId
          + ":"
          + role.name()
          + ":"
          + tick
          + ":"
          + count
          + ":"
          + targetHouseholdId.value();
    }

    /**
     * {@code social.SubmitHouseholdWorkOrder} 载荷（Map 形态；preview 视图直接可用）： {@code orderId}/{@code
     * target}/{@code reason}/{@code source.module="gov"} + 逐来源一条 {@code TRANSFER_MEMBERS(from=退休源,
     * to=目标家户, lotId, count=taken)}。
     */
    Map<String, Object> workOrderPayload(String reason) {
      requireReason(reason);
      List<Map<String, Object>> steps = new ArrayList<>(sources.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "TRANSFER_MEMBERS");
        step.put("from", share.householdId().value());
        step.put("to", targetHouseholdId.value());
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("module", SOURCE_MODULE);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId());
      payload.put("target", targetHouseholdId.value());
      payload.put("reason", reason);
      payload.put("source", source);
      payload.put("plan", List.copyOf(steps));
      return payload;
    }

    /** {@code social.SubmitHouseholdWorkOrder} 载荷 JSON（{@link #workOrderPayload(String)} 的统一出口）。 */
    String submitHouseholdWorkOrderPayloadJson(String reason) {
      return ToolSupport.json(workOrderPayload(reason));
    }

    /** {@code unit.DismissStaff} 载荷（只对旧档财政户模式合法）。 */
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

    /** {@code actor.AdjustAccounts} 载荷：国库银一条负增量（{@link #hasPayment()} 为真时才可调用）。 */
    String adjustAccountsPayloadJson() {
      if (!hasPayment()) {
        throw new IllegalStateException("批不自洽：无待遇却要组装 actor.AdjustAccounts 载荷");
      }
      HexCoord at = treasuryLocation.get();
      Map<String, Object> money = new LinkedHashMap<>();
      money.put(MoneyVocabulary.SILVER_CURRENCY.toString(), -payment);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("household", GovernmentHouseholds.of(unitId).value());
      entry.put("q", at.q());
      entry.put("r", at.r());
      entry.put("money", money);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", List.of(entry));
      return ToolSupport.json(payload);
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

    /** 来源份额的紧凑摘要（note 用；{@code lotId×taken}，瀑布序）。 */
    private String sourcesNote() {
      StringBuilder text = new StringBuilder();
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        if (text.length() > 0) {
          text.append(", ");
        }
        text.append(share.lotId().value()).append('×').append(share.taken());
      }
      return text.toString();
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含模式/退休源/目标家户/来源 shares/待遇）。 */
    String infoValueJson(String reason) {
      requireReason(reason);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("mode", postHouseholdMode ? "post-household" : "treasury-household-legacy");
      value.put("unitId", unitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("tick", tick);
      if (!postHouseholdMode) {
        value.put("staffBefore", legacyStaffBefore);
        value.put("staffAfter", legacyStaffAfter);
      }
      value.put("retirementPerStaff", retirementPerStaff);
      value.put("payment", payment);
      value.put("treasury", treasuryLocation.map(GovDismissPlan::treasuryView).orElse(null));
      value.put("availableSilver", availableSilver);
      value.put("sourceHouseholdId", sourceHouseholdId.value());
      value.put("sourcePopulationBefore", sourcePopulationBefore);
      value.put("sourcePopulationAfter", sourcePopulationAfter());
      if (!postHouseholdMode) {
        // ★ 旧档键名兼容：源 = hh-gov-<unitId>。
        value.put("governmentHouseholdId", sourceHouseholdId.value());
        value.put("governmentPopulationBefore", sourcePopulationBefore);
        value.put("governmentPopulationAfter", sourcePopulationAfter());
      }
      value.put("targetHouseholdId", targetHouseholdId.value());
      value.put("targetHex", targetHex.map(GovRetireStaffPlan::hexView).orElse(null));
      value.put("targetPopulationBefore", targetPopulationBefore);
      value.put("targetPopulationAfter", targetPopulationAfter);
      value.put("available", available);
      value.put("sourceCount", sources.size());
      value.put("sources", sourcesView());
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireReason(reason);
      return "退休离编 "
          + unitId
          + " 的 "
          + role
          + " "
          + count
          + " 人（tick "
          + tick
          + "，"
          + (postHouseholdMode ? "岗位户" : "旧档财政户")
          + "）："
          + (postHouseholdMode ? "" : "在编 " + legacyStaffBefore + "→" + legacyStaffAfter + "，")
          + "待遇 "
          + payment
          + " 银"
          + (hasPayment() ? "（国库 @ " + hexText(treasuryLocation.get()) + "）" : "（政策为 0，无支付命令）")
          + "；社会转移：退休源 "
          + sourceHouseholdId.value()
          + " "
          + sourcePopulationBefore
          + "→"
          + sourcePopulationAfter()
          + " → 目标家户 "
          + targetHouseholdId.value()
          + (targetHex.isPresent() ? " @ " + hexText(targetHex.get()) : "")
          + " "
          + targetPopulationBefore
          + "→"
          + targetPopulationAfter
          + "（逐 lot 来源 "
          + sources.size()
          + " 条："
          + sourcesNote()
          + "）；reason="
          + reason;
    }
  }
}
