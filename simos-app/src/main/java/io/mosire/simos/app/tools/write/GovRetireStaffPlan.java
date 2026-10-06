package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
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
 * ★★ {@code simos.gov.retireStaff} 的<b>纯推导</b>（阶段 13A 人员流转；P1.2 改走 Social 家户工单）：离编 + 按政策从国库一次性
 * 支付退休待遇（支付口径<b>复用 {@link GovDismissPlan}</b>，本批不改）+ 从政府编制家户 {@code hh-gov-<unitId>}
 * <b>转出真实成员</b>到明确目标家户 + 留行动记录。一批落一条 revision，<b>不碰</b> {@link
 * io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>待遇口径复用 {@link GovDismissPlan}</b>：本类不写第二份支付推导——离编/roster 校验、{@code
 * policy.retirementPerStaff × count}、国库位置与可支配不足整条拒（带 requested/available/缺口）都由 {@link
 * GovDismissPlan#plan} 一处给出；本类只在其上加“从政府家户转出真实成员到目标家户”那一段。
 *
 * <p>★★ <b>目标家户必须明确（缺一 ⇒ plan 级具名拒，人不能凭空消失）</b>：
 *
 * <ol>
 *   <li>{@code toHouseholdId} 精确指定：必须存在于 {@code SocialData.households()}，且 ≠ 本次退休源政府家户 {@code
 *       hh-gov-<unitId>}；
 *   <li>未给 {@code toHouseholdId} 但给了 {@code reinsertQ}/{@code reinsertR}（必须成对）：在该 hex 的 {@code
 *       social.householdsAt(hex)} 里按 {@code HouseholdId.value()} 升序取<b>第一个有人口</b>的家户；没有 ⇒ 具名拒；
 *   <li>两者都没给 ⇒ <b>plan 级具名拒</b>（本工具不再允许"人不回写社会"；也不再用"并入最小 id 批次"的旧近似）。
 * </ol>
 *
 * <p>★★ <b>政府家户前置（缺一 ⇒ plan 级具名拒，不猜、不新建第二户）</b>：退休源恒为 {@link GovernmentHouseholds#of(String)} =
 * {@code hh-gov-<unitId>}；必须<b>同时</b>出现在 {@code Unit.households()}（否则该 GOV 单位的家户关系数据坏）与 {@code
 * social.households()}（否则 Social 里没有可转人的源家户）；且政府家户人口必须 ≥ {@code count}（不足 ⇒ 具名拒，不发批、零 revision）。
 *
 * <p>★★ <b>选人唯一拼写点</b>：只调 {@link HouseholdManpowerAllocator#allocateFromHousehold}（家户份额瀑布；本类不另写
 * 排序/过滤/扣减），退休调用<b>不传过滤</b>（{@code Optional.empty()} / {@code Optional.empty()}）——政府编制家户里可能含
 * 各年龄/性别的家属，过滤过窄会把真实人口误判为“不足”。守恒：{@code Σ take == count} 在 Plan 构造期逐值互校。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户>} 确定性幂等键；target = 目标家户；逐来源 {@code
 * TRANSFER_MEMBERS(from=hh-gov-<unitId>, to=目标家户, lotId, count=taken)}）→ {@code
 * unit.DismissStaff}（形状不变） →（待遇 &gt; 0）{@code actor.AdjustAccounts} → {@code sd.PutInfo}（地址 = 单位
 * canonical，key={@code retireStaff}， value=JSON <b>字符串</b>，含目标家户/来源 shares，note=人可读摘要）。四条共享同一
 * batchId 与同一 branch/expectedRevision ⇒ 一条 revision。
 *
 * <p>★★ <b>守恒</b>：GOV roster 前 − count == roster 后；待遇支付额 == {@code retirementPerStaff ×
 * count}；政府家户人口 前 − count == 后；目标家户人口 前 + count == 后；世界 Social 总人口不变（转移只改份额归属，不改批次人数）。Plan 构造期逐值互校。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表按 {@code
 * HouseholdManpowerAllocator} 的全序瀑布序，用 {@link List#copyOf} 冻结。
 */
final class GovRetireStaffPlan {

  /** {@code social.SubmitHouseholdWorkOrder} 的命令类型（与 handler 的 {@code TYPE} 同源）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** {@code unit.DismissStaff} 的命令类型（与 {@code DismissStaffHandler.type()} 同字面）。 */
  static final String DISMISS_STAFF_TYPE = "unit.DismissStaff";

  /** {@code actor.AdjustAccounts} 的命令类型（仅待遇 &gt; 0 才落）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 工单来源模块名（进 {@code social.SubmitHouseholdWorkOrder} 载荷与日志）。 */
  private static final String SOURCE_MODULE = "gov";

  private GovRetireStaffPlan() {}

  /**
   * 纯推导入口（见类注的目标家户/政府家户前置、待遇口径与选人守恒）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 离编主体（带 GovernmentFormation 的 GOV）
   * @param roleText 行政角色词表（SCRIBE|YAMEN|POST）
   * @param count 离编人数（≥ 1，且不得超过现有在编）
   * @param toHouseholdId 精确目标家户 id（可选；给了必须存在于 Social、且 ≠ 政府家户；与 reinsert 二选一）
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
    GovDismissPlan.Plan dismissal = GovDismissPlan.plan(state, unitId, roleText, count);
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
    Unit unit = units.units().get(UnitId.parse(dismissal.unitId()));
    if (unit == null) {
      throw new IllegalArgumentException("GOV 单位不存在: " + dismissal.unitId());
    }
    SocialData social = ToolSupport.socialData(state);
    HouseholdId governmentHousehold = GovernmentHouseholds.of(dismissal.unitId());
    if (!unit.households().contains(governmentHousehold)) {
      throw new IllegalArgumentException(
          "GOV 单位 "
              + dismissal.unitId()
              + " 的 Unit.households 不含政府家户 "
              + governmentHousehold.value()
              + "：单位家户关系数据坏，退休源不明确；先 unit.SetGovFormation / 修数"
              + "（不猜、不新建第二户）");
    }
    if (!social.households().containsKey(governmentHousehold)) {
      throw new IllegalArgumentException(
          "Social 里不存在政府家户 "
              + governmentHousehold.value()
              + "（GOV 单位 "
              + dismissal.unitId()
              + " 的退休源）：先补该政府家户（如 simos.gov.createOffice 的 social.CreateHousehold）再退休"
              + "（不猜、不新建第二户）");
    }
    long governmentPopulationBefore = social.householdPopulation(governmentHousehold);
    if (governmentPopulationBefore < dismissal.count()) {
      throw new IllegalArgumentException(
          "政府家户 "
              + governmentHousehold.value()
              + " 人口不足：现有 "
              + governmentPopulationBefore
              + " < 退休请求 "
              + dismissal.count()
              + "（退休必须从政府家户转出真实成员；先 simos.gov.recruit 补人，或减少 count）");
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
      if (requested.equals(governmentHousehold)) {
        throw new IllegalArgumentException(
            "toHouseholdId 不得是退休源政府家户 " + governmentHousehold.value() + "（源与目标相同会被域层拒；人必须转到别的家户）");
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
      if (targetHousehold.equals(governmentHousehold)) {
        throw new IllegalArgumentException(
            "回写格 "
                + hexText(hex)
                + " 解析出的目标家户是退休源政府家户 "
                + governmentHousehold.value()
                + "：拒绝自我转移（请改正该格家户数据或改用 toHouseholdId）");
      }
      targetHex = Optional.of(hex);
    } else {
      throw new IllegalArgumentException(
          "退休必须给目标家户：给 toHouseholdId（精确指定）或 reinsertQ/reinsertR"
              + "（在该 hex 按 household id 升序取第一个有人口的家户）；两者都没给 ⇒ 拒绝"
              + "（人不能凭空消失，也不会留在政府编制家户）");
    }

    long targetPopulationBefore = social.householdPopulation(targetHousehold);
    long targetPopulationAfter;
    try {
      targetPopulationAfter = Math.addExact(targetPopulationBefore, dismissal.count());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "目标家户 "
              + targetHousehold.value()
              + " 接收 "
              + dismissal.count()
              + " 人后人口溢出 long: 现有 "
              + targetPopulationBefore,
          e);
    }

    // ★ 选人唯一拼写点：指定家户入口（退休源是 UNIT 位置，不适用辖区 HEX 扫描），不传过滤。
    HouseholdManpowerAllocator.Allocation allocation;
    try {
      allocation =
          HouseholdManpowerAllocator.allocateFromHousehold(
              social,
              governmentHousehold,
              dismissal.count(),
              CalendarClock.julianDefault(), // 无过滤 ⇒ 时钟不参与选人；保留参数只为未来显式过滤
              dismissal.tick(),
              Optional.empty(),
              Optional.empty());
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException(
            "政府家户 " + governmentHousehold.value() + " 可选人口不足：" + e.getMessage(), e);
      }
      throw e;
    }
    return new Plan(
        dismissal,
        governmentHousehold,
        governmentPopulationBefore,
        targetHousehold,
        targetHex,
        targetPopulationBefore,
        targetPopulationAfter,
        allocation.available(),
        allocation.shares());
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
   * 一份退休计划（全部字段是状态的纯函数；{@code dismissal} 是共享支付推导的产物，来源 shares 在构造期冻结并互校）。
   *
   * @param dismissal 离编 + 待遇支付那一半（见 {@link GovDismissPlan.Plan}）
   * @param governmentHouseholdId 退休源政府家户（{@code hh-gov-<unitId>}；构造期已由 plan 前置校验存在）
   * @param governmentPopulationBefore 退休前政府家户人口（≥ count）
   * @param targetHouseholdId 目标家户（已存在于 Social；≠ 政府家户）
   * @param targetHex 目标家户的格（HEX 位置）；{@code UNIT} 位置或无 reinsert 解析时为 empty
   * @param targetPopulationBefore 退休前目标家户人口
   * @param targetPopulationAfter 退休后目标家户人口（= before + count）
   * @param available 政府家户全部合格份额合计（不足拒因用；成功时 = 政府家户人口）
   * @param sources 逐 lot 转出份额（Σtaken == count；每条来源都是政府家户）
   */
  record Plan(
      GovDismissPlan.Plan dismissal,
      HouseholdId governmentHouseholdId,
      long governmentPopulationBefore,
      HouseholdId targetHouseholdId,
      Optional<HexCoord> targetHex,
      long targetPopulationBefore,
      long targetPopulationAfter,
      long available,
      List<HouseholdManpowerAllocator.ManpowerShare> sources) {

    Plan {
      Objects.requireNonNull(dismissal, "dismissal");
      Objects.requireNonNull(governmentHouseholdId, "governmentHouseholdId");
      Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
      Objects.requireNonNull(targetHex, "targetHex");
      if (governmentHouseholdId.equals(targetHouseholdId)) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：退休源政府家户与目标家户相同 " + governmentHouseholdId.value());
      }
      if (governmentPopulationBefore < dismissal.count()) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：政府家户人口 " + governmentPopulationBefore + " < count=" + dismissal.count());
      }
      if (targetPopulationBefore < 0L || targetPopulationAfter < targetPopulationBefore) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：目标家户人口 " + targetPopulationBefore + "→" + targetPopulationAfter);
      }
      long expectedTargetAfter;
      try {
        expectedTargetAfter = Math.addExact(targetPopulationBefore, dismissal.count());
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(
            "目标家户人口溢出 long: before=" + targetPopulationBefore + " + count=" + dismissal.count(), e);
      }
      if (targetPopulationAfter != expectedTargetAfter) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：目标家户人口后 " + targetPopulationAfter + " != before+count=" + expectedTargetAfter);
      }
      if (available < dismissal.count()) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：available=" + available + " < count=" + dismissal.count());
      }
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        if (!share.householdId().equals(governmentHouseholdId)) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：来源家户必须是政府家户 "
                  + governmentHouseholdId.value()
                  + "，实际 "
                  + share.householdId().value());
        }
        total = saturatedAdd(total, share.taken());
      }
      if (total != dismissal.count()) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源 share.taken=" + total + " != count=" + dismissal.count() + "（批载荷必须逐值对应）");
      }
    }

    /** 政府家户退休后人口（= before − count；构造期已保证非负）。 */
    long governmentPopulationAfter() {
      return governmentPopulationBefore - dismissal.count();
    }

    /** 是否要落 {@code actor.AdjustAccounts}（待遇 &gt; 0 才落）。 */
    boolean hasPayment() {
      return dismissal.hasPayment();
    }

    /**
     * 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）： {@code social.SubmitHouseholdWorkOrder} →
     * {@code unit.DismissStaff} → （待遇 &gt; 0）{@code actor.AdjustAccounts} → {@code sd.PutInfo}。
     */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      types.add(DISMISS_STAFF_TYPE);
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
          + dismissal.unitId()
          + ":"
          + dismissal.role().name()
          + ":"
          + dismissal.tick()
          + ":"
          + dismissal.count()
          + ":"
          + targetHouseholdId.value();
    }

    /**
     * {@code social.SubmitHouseholdWorkOrder} 载荷（Map 形态；preview 视图直接可用）： {@code orderId}/{@code
     * target}/{@code reason}/{@code source.module="gov"} + 逐来源一条 {@code TRANSFER_MEMBERS(from=政府家户,
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

    /** {@code unit.DismissStaff} 载荷（复用共享推导的载荷；形状不变）。 */
    String dismissStaffPayloadJson() {
      return dismissal.dismissStaffPayloadJson();
    }

    /** {@code actor.AdjustAccounts} 载荷（复用共享推导的载荷；仅 {@link #hasPayment()} 时合法）。 */
    String adjustAccountsPayloadJson() {
      return dismissal.adjustAccountsPayloadJson();
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

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含目标家户/来源 shares/待遇，不再有旧近似文案）。 */
    String infoValueJson(String reason) {
      requireReason(reason);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", dismissal.unitId());
      value.put("role", dismissal.role().name());
      value.put("count", dismissal.count());
      value.put("tick", dismissal.tick());
      value.put("staffBefore", dismissal.staffBefore());
      value.put("staffAfter", dismissal.staffAfter());
      value.put("retirementPerStaff", dismissal.retirementPerStaff());
      value.put("payment", dismissal.payment());
      value.put(
          "treasury", dismissal.treasuryLocation().map(GovDismissPlan::treasuryView).orElse(null));
      value.put("availableSilver", dismissal.availableSilver());
      value.put("governmentHouseholdId", governmentHouseholdId.value());
      value.put("governmentPopulationBefore", governmentPopulationBefore);
      value.put("governmentPopulationAfter", governmentPopulationAfter());
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
          + dismissal.unitId()
          + " 的 "
          + dismissal.role()
          + " "
          + dismissal.count()
          + " 人（tick "
          + dismissal.tick()
          + "）：在编 "
          + dismissal.staffBefore()
          + "→"
          + dismissal.staffAfter()
          + "，待遇 "
          + dismissal.payment()
          + " 银"
          + (hasPayment()
              ? "（国库 @ " + hexText(dismissal.treasuryLocation().get()) + "）"
              : "（政策为 0，无支付命令）")
          + "；社会转移：政府家户 "
          + governmentHouseholdId.value()
          + " "
          + governmentPopulationBefore
          + "→"
          + governmentPopulationAfter()
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
