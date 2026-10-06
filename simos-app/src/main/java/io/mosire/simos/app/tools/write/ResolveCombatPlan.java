package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyAddresses;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatOutcomeId;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatResolution;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.army.spi.ResolveCombatStageHandler;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.AdjustCompositionHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.army.resolveCombat} 的<b>纯推导</b>（阶段 D4 / 用户设计 D-009 补裁 + D-010 +
 * D-012，2026-10-02；P2 人员伤亡回写 Social，2026-10-13）：从一份 {@link SimulationState} 与参数算出结算 {@link
 * Plan}——<b>不碰 {@code ToolContext} / {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义
 * （工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>职责边界（D-010/R2 + P2）</b>：Army 只做编排/随机化——判定算法委托 {@link
 * CombatResolution}（全仓唯一）；命中结局的损失拆成两条腿：
 *
 * <ol>
 *   <li><b>人员损失（P2）</b>：{@code CombatUnitLoss.manpower} 只接受<b>负增量</b>；{@code lossCount = Σ|amount|}
 *       从 {@code Unit.households()} 的 Social 家户份额里抽 {@code MALE + ADULT} 人（{@link
 *       HouseholdManpowerAllocator#allocateFromHouseholds}），逐单位落一张 {@code
 *       social.SubmitHouseholdWorkOrder} （{@code REMOVE_MEMBERS} 步骤）；<b>Unit 侧不写第二本 headcount</b>；
 *   <li><b>装备损失</b>：{@code unit.AdjustComposition} 只带 {@code equipment} 维度（{@code {id,equipment}}
 *       形状； 空装备不发命令），语义与本批之前一致。
 * </ol>
 *
 * <p>★★ <b>命中结局无损/只有装备损失时行为不变</b>：没有人员损失 ⇒ 不读 Social、不发 Social 工单，批内只有原来的逐单位 {@code
 * unit.AdjustComposition}；装备为空的条目本来就不生成命令。
 *
 * <p>★★ <b>批顺序（P2 文档 §3，固定可复现）</b>：对每个涉事 unit（顺序 = 结局 {@code losses} 表序）——
 * <b>先</b>人员工单（如有）、<b>再</b>装备命令（如有）；全部单位之后是 {@code army.ResolveCombatStage}；若记录内所有阶段都已判定， 再逐单位
 * {@code unit.SetStateDescription} 清链接；本工具无 {@code sd.PutInfo} 腿。
 *
 * <p>★★ <b>状态链接的"结束"口径（R3：不自动清，由本工具显式处理）</b>：本次判定之后若记录里**所有阶段都已判定**，则清除所有"状态键 {@code combat} 且地址恰为
 * {@code army:combat.<id>}"的单位链接（{@code unit.SetStateDescription} 省略 address = 删除）。只清**恰好链到本记录**的那些
 * ——链到别的记录/被显式改写过的链接不动；本来就没有链接的单位也不发命令（单位层对"清除不存在的链接"是具名拒，发了会让整批拒）。 若还有未判定阶段 ⇒ 链接保留（这是 R3
 * 的"保留至显式覆盖/清除"）。
 *
 * <p>★★ <b>可复现</b>：投骰路径的生效 seed 由 {@link CombatResolution} 显式落进阶段记录；显式结局路径不投骰。工具把推导结果作为 {@code
 * outcomeId(+seed)} 传给命令，命令再按同一种子复核一遍（见 {@link CombatResolution} 的三条语义）。
 *
 * <p>★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：记录/阶段必须存在；阶段不得已判定；显式 outcome
 * 必须在概率表里；投骰必须有概率表；命中结局里**有实际增量**的单位必须存在于 unit 切片；人员损失必须全为负增量且 {@code Σ|amount|} 不溢出；人员损失单位的 {@code
 * Unit.households()} 必须非空、其中 {@code MALE + ADULT} 份额必须够 {@code lossCount} （不足 ⇒ 带
 * unit/requested/available/缺口具名拒，不部分抽、不换年龄档）。
 */
final class ResolveCombatPlan {

  /** 人员伤亡回写 Social 的命令类型（逐单位一张工单，P2）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** 逐单位应用装备损失（有符号增量）的命令类型。 */
  static final String ADJUST_COMPOSITION_TYPE = AdjustCompositionHandler.TYPE;

  /** 更新交战记录的判定命令类型。 */
  static final String RESOLVE_STAGE_TYPE = ResolveCombatStageHandler.TYPE;

  /** 结束（全部阶段判定完）时逐单位清除状态链接的命令类型。 */
  static final String SET_STATE_DESCRIPTION_TYPE = StartCombatPlan.SET_STATE_DESCRIPTION_TYPE;

  /** Unit 侧"进入交战"的状态键（与 {@link StartCombatPlan#STATE_KEY} 同源）。 */
  static final String STATE_KEY = StartCombatPlan.STATE_KEY;

  private ResolveCombatPlan() {}

  /**
   * 测试/旧路径入口：全缺省儒略历时钟（与 {@code HouseholdManpowerAllocator} 的年龄档现算口径一致）。
   *
   * <p>★ 生产路径一律用带 {@link CalendarClock} 的重载（{@code CalendarService.clock()}），本重载只为既有测试/旧调用点保留。
   */
  static Plan derive(
      SimulationState state,
      String combatId,
      String stageId,
      Optional<String> outcomeId,
      Optional<Long> seed) {
    return derive(state, combatId, stageId, outcomeId, seed, CalendarClock.julianDefault());
  }

  /**
   * 生产推导入口（校验清单见类注；P2 起还负责人员伤亡的 Social 家户分摊与工单载荷）。
   *
   * @param state 读数所在状态（preview/apply 共用同一坐标）
   * @param combatId 交战记录 id
   * @param stageId 要判定的阶段 id
   * @param outcomeId 显式结局（空 = 由 seed/派生种子投骰）
   * @param seed 显式 seed（空 = 由 {@code combatId+stageId+tick+概率表} 确定性派生）
   * @param clock 历法时钟（年龄档现算的唯一拼写点；非空；生产路径 = {@code CalendarService.clock()}）
   */
  static Plan derive(
      SimulationState state,
      String combatId,
      String stageId,
      Optional<String> outcomeId,
      Optional<Long> seed,
      CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(outcomeId, "outcomeId");
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(clock, "clock");
    CombatRecordId recordId = CombatRecordId.parse(combatId);
    ArmyData army = ApiViews.armyData(state);
    CombatRecord record = army.combats().get(recordId);
    if (record == null) {
      throw new IllegalArgumentException("交战记录不存在: " + combatId);
    }
    CombatStageId wantedStageId = CombatStageId.parse(stageId);
    CombatStage stage = null;
    for (CombatStage candidate : record.stages()) {
      if (candidate.id().equals(wantedStageId)) {
        stage = candidate;
        break;
      }
    }
    if (stage == null) {
      throw new IllegalArgumentException("阶段不存在: " + stageId + "（交战记录 " + combatId + "）");
    }
    if (stage.resolved()) {
      throw new IllegalArgumentException("阶段已判定过，不可重复投骰: " + stageId + "（要改判请追加新阶段）");
    }
    Optional<CombatOutcomeId> explicitOutcomeId = outcomeId.map(CombatOutcomeId::parse);
    CombatResolution.Selection selection =
        CombatResolution.select(
            record.id(), stage.id(), record.tick(), stage.outcomes(), explicitOutcomeId, seed);

    UnitState units = ToolSupport.unitState(state);
    long tick = state.meta().timestamp().tick();
    // ★ P2：没有人员损失时保持原行为——不读 Social（惰性取），批内只有装备命令。
    SocialData social = null;
    List<CasualtyOrder> casualtyOrders = new ArrayList<>();
    for (CombatUnitLoss loss : selection.outcome().losses()) {
      if (loss.empty()) {
        continue; // 两条表都空：不生成任何命令。
      }
      Unit unit = units.units().get(loss.unit());
      if (unit == null) {
        throw new IllegalArgumentException(
            "结局" + selection.outcome().id().value() + " 的损失指向不存在的单位: " + loss.unit().value());
      }
      if (loss.manpower().isEmpty()) {
        continue; // 纯装备损失：不走 Social。
      }
      // ★ manpower 只接受负增量：正/0 是"增援/补员/无变化"，本批不猜、不静默丢。
      long lossCount = casualtyCount(selection.outcome().id(), loss);
      if (unit.households().isEmpty()) {
        throw new IllegalArgumentException(
            "单位 "
                + unit.id().value()
                + " 没有家户（Unit.households 为空），无法承载 "
                + lossCount
                + " 人战斗人员损失：先 unit.AssignHousehold 把人口家户挂到该单位（不猜、不新建第二本 headcount）");
      }
      if (social == null) {
        social = ToolSupport.socialData(state);
      }
      HouseholdManpowerAllocator.Allocation allocation;
      try {
        allocation =
            HouseholdManpowerAllocator.allocateFromHouseholds(
                social,
                Set.copyOf(unit.households()),
                lossCount,
                clock,
                tick,
                Optional.of(Sex.MALE),
                Optional.of(AgeBracket.ADULT),
                Set.of());
      } catch (IllegalArgumentException e) {
        // ★ 不足拒因由选人层给出 requested/available/缺口；这里补 unit 与"这是战斗人员损失"的上下文，整条 plan 拒。
        throw new IllegalArgumentException(
            "单位 "
                + unit.id().value()
                + " 的战斗人员损失分摊失败（unit="
                + unit.id().value()
                + "，requested="
                + lossCount
                + "）："
                + e.getMessage(),
            e);
      }
      String orderId =
          "combat-casualty:"
              + record.id().value()
              + ":"
              + stage.id().value()
              + ":"
              + unit.id().value();
      String reason =
          "战斗人员伤亡（combat="
              + record.id().value()
              + " stage="
              + stage.id().value()
              + " unit="
              + unit.id().value()
              + "）";
      // ★ target = 第一个被抽家户（工单要求 target 被 plan 引用；plan 的 REMOVE_MEMBERS 逐步点名全部被抽家户）。
      casualtyOrders.add(
          new CasualtyOrder(
              unit.id(),
              orderId,
              allocation.shares().get(0).householdId(),
              allocation.shares(),
              reason));
    }

    boolean allResolvedAfter = true;
    for (CombatStage candidate : record.stages()) {
      if (!candidate.id().equals(stage.id()) && !candidate.resolved()) {
        allResolvedAfter = false;
        break;
      }
    }
    List<UnitId> clearLinkUnits =
        allResolvedAfter
            ? linkedUnits(units, ArmyAddresses.combatCanonical(record.id()))
            : List.of();
    return new Plan(
        record.id(),
        stage.id(),
        selection.outcome(),
        selection.seed(),
        explicitOutcomeId.isPresent(),
        seed.isPresent(),
        tick,
        allResolvedAfter,
        clearLinkUnits,
        casualtyOrders);
  }

  /**
   * 人力损失量 = {@code Σ|amount|}：逐条要求 {@code amount < 0}（正/0 ⇒ 具名拒，本批不做增援/补员），并防 long 溢出。
   *
   * @throws IllegalArgumentException 任一 amount ≥ 0，或 {@code Σ|amount|} 溢出 long
   */
  private static long casualtyCount(CombatOutcomeId outcomeId, CombatUnitLoss loss) {
    long total = 0L;
    for (CompositionDelta delta : loss.manpower()) {
      if (delta.amount() >= 0L) {
        throw new IllegalArgumentException(
            "结局 "
                + outcomeId.value()
                + " 的单位 "
                + loss.unit().value()
                + " manpower 损失只接受负增量（amount < 0；增援/补员另开批次，本批不猜、不静默忽略）: type="
                + delta.type()
                + "，amount="
                + delta.amount());
      }
      try {
        total = Math.addExact(total, Math.negateExact(delta.amount()));
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(
            "结局 "
                + outcomeId.value()
                + " 的单位 "
                + loss.unit().value()
                + " 人力损失量 Σ|amount| 溢出 long：type="
                + delta.type()
                + "，amount="
                + delta.amount(),
            e);
      }
    }
    return total;
  }

  /** 找出所有"状态键 combat 且地址恰为本记录 canonical 地址"的单位（按单位 id 字典序，响应/批字节可复现）。 */
  private static List<UnitId> linkedUnits(UnitState units, String combatAddress) {
    List<Unit> sorted = new ArrayList<>(units.units().values());
    sorted.sort(Comparator.comparing(unit -> unit.id().value()));
    List<UnitId> linked = new ArrayList<>();
    for (Unit unit : sorted) {
      if (combatAddress.equals(unit.stateDescriptions().get(STATE_KEY))) {
        linked.add(unit.id());
      }
    }
    return List.copyOf(linked);
  }

  /** {@code unit.AdjustComposition} 的装备维度载荷（P2：只带 equipment，绝不带 manpower）。 */
  private static String equipmentAdjustPayloadJson(CombatUnitLoss loss) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", loss.unit().value());
    payload.put("equipment", ToolSupport.compositionDeltaView(loss.equipment()));
    return ToolSupport.json(payload);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不得为空白");
    }
  }

  /** 批内一条命令（类型 + 载荷）；preview 视图与 apply 组批共用同一份推导结果。 */
  record PlannedCommand(String type, String payloadJson) {
    PlannedCommand {
      requireNonBlank(type, "type");
      requireNonBlank(payloadJson, "payloadJson");
    }
  }

  /**
   * 一个单位的人员伤亡工单（P2）：逐 share {@code REMOVE_MEMBERS(household,lot,count)}；{@code target} = 第一个被抽家户。
   *
   * @param unit 涉事单位
   * @param orderId 确定性幂等键 {@code combat-casualty:<combatId>:<stageId>:<unitId>}
   * @param target 工单 target（必须被 plan 引用 ⇒ 恒为第一个 share 的家户）
   * @param shares 本次抽取的家户份额（瀑布顺序；Σtaken = 该 unit 的人员损失数）
   * @param reason 理由（含 combat/stage/unit）
   */
  record CasualtyOrder(
      UnitId unit,
      String orderId,
      HouseholdId target,
      List<HouseholdManpowerAllocator.ManpowerShare> shares,
      String reason) {

    CasualtyOrder {
      Objects.requireNonNull(unit, "unit");
      requireNonBlank(orderId, "orderId");
      Objects.requireNonNull(target, "target");
      shares = List.copyOf(Objects.requireNonNull(shares, "shares"));
      if (shares.isEmpty()) {
        throw new IllegalArgumentException(
            "CasualtyOrder.shares 不得为空（有人员损失就必须至少一条 REMOVE_MEMBERS）");
      }
      if (!shares.get(0).householdId().equals(target)) {
        throw new IllegalArgumentException(
            "CasualtyOrder.target 必须是第一条 share 的家户（工单 target 必须被 plan 引用）: target="
                + target.value()
                + "，first="
                + shares.get(0).householdId().value());
      }
      requireNonBlank(reason, "reason");
    }

    /**
     * {@code social.SubmitHouseholdWorkOrder} 载荷：source.module=army；plan = 逐 share REMOVE_MEMBERS。
     */
    String payloadJson() {
      List<Map<String, Object>> steps = new ArrayList<>(shares.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : shares) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "REMOVE_MEMBERS");
        step.put("household", share.householdId().value());
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId);
      payload.put("target", target.value());
      payload.put("reason", reason);
      payload.put("source", Map.of("module", "army"));
      payload.put("plan", steps);
      return ToolSupport.json(payload);
    }

    /** 逐 share 的来源视图（preview 用；HEX 家户带 hex，UNIT 家户为 null）。 */
    List<Map<String, Object>> sharesView() {
      List<Map<String, Object>> rows = new ArrayList<>(shares.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : shares) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("household", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("count", share.taken());
        row.put("hex", share.hexOptional().map(ToolSupport::hexCoord).orElse(null));
        rows.add(row);
      }
      return List.copyOf(rows);
    }

    /** 一条人员工单的完整预览视图（含可直接比对的载荷字节）。 */
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("unit", unit.value());
      view.put("orderId", orderId);
      view.put("target", target.value());
      view.put("reason", reason);
      view.put("shares", sharesView());
      view.put("payload", payloadJson());
      return view;
    }
  }

  /**
   * 一份结算计划（全部字段是状态与参数的纯函数）。
   *
   * @param combatId 交战记录 id
   * @param stageId 本次判定的阶段 id
   * @param outcome 选中的结局（已含损失表）
   * @param seed 要写进记录 {@code rollSeed} 的种子（投骰路径在场；显式结局且未给 seed ⇒ 空）
   * @param outcomeProvided 调用方显式给了 outcome（{@code true} = 没投骰）
   * @param seedProvided 调用方显式给了 seed
   * @param tick 推导时的世界日
   * @param allStagesResolvedAfter 本次判定后记录是否所有阶段都已判定（据此决定清不清状态链接）
   * @param clearLinkUnits 要显式清除状态链接的单位（保序；只在 {@code allStagesResolvedAfter} 时非空）
   * @param casualtyOrders 逐单位的人员伤亡工单（顺序 = 结局 losses 表序中 manpower 非空的单位；与 outcome 逐单位一一对应）
   */
  record Plan(
      CombatRecordId combatId,
      CombatStageId stageId,
      CombatOutcome outcome,
      Optional<Long> seed,
      boolean outcomeProvided,
      boolean seedProvided,
      long tick,
      boolean allStagesResolvedAfter,
      List<UnitId> clearLinkUnits,
      List<CasualtyOrder> casualtyOrders) {

    Plan {
      Objects.requireNonNull(combatId, "combatId");
      Objects.requireNonNull(stageId, "stageId");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(seed, "seed");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      Objects.requireNonNull(clearLinkUnits, "clearLinkUnits");
      if (!allStagesResolvedAfter && !clearLinkUnits.isEmpty()) {
        throw new IllegalStateException("推演不自洽：还有未判定阶段却要清状态链接");
      }
      clearLinkUnits = List.copyOf(clearLinkUnits);
      casualtyOrders = List.copyOf(Objects.requireNonNull(casualtyOrders, "casualtyOrders"));
      // ★ 一一对应校验：outcome 里"manpower 非空"的单位序列必须与 casualtyOrders 逐位同 unit（顺序 = losses 表序）。
      int expected = 0;
      for (CombatUnitLoss loss : outcome.losses()) {
        if (!loss.manpower().isEmpty()) {
          expected++;
        }
      }
      if (casualtyOrders.size() != expected) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：manpower 非空的单位有 "
                + expected
                + " 个，但 casualtyOrders 有 "
                + casualtyOrders.size()
                + " 条");
      }
      int index = 0;
      for (CombatUnitLoss loss : outcome.losses()) {
        if (loss.manpower().isEmpty()) {
          continue;
        }
        CasualtyOrder order = casualtyOrders.get(index++);
        if (!order.unit().equals(loss.unit())) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：casualtyOrders 第 "
                  + index
                  + " 条指向 "
                  + order.unit().value()
                  + "，但结局 losses 表同位的单位是 "
                  + loss.unit().value());
        }
      }
    }

    /** 会生成 {@code unit.AdjustComposition} 的损失条数（equipment 非空的条数）。 */
    int adjustCommandCount() {
      int count = 0;
      for (CombatUnitLoss loss : outcome.losses()) {
        if (!loss.equipment().isEmpty()) {
          count++;
        }
      }
      return count;
    }

    /** 会生成 {@code social.SubmitHouseholdWorkOrder} 的单位数（= 人员伤亡工单数）。 */
    int casualtyWorkOrderCount() {
      return casualtyOrders.size();
    }

    /** 逐单位的 {@code unit.AdjustComposition} 载荷（顺序 = 结局 losses 表序；只装备维度，空装备跳过）。 */
    List<String> adjustPayloadsJson() {
      List<String> payloads = new ArrayList<>();
      for (CombatUnitLoss loss : outcome.losses()) {
        if (loss.equipment().isEmpty()) {
          continue;
        }
        payloads.add(equipmentAdjustPayloadJson(loss));
      }
      return List.copyOf(payloads);
    }

    /**
     * 逐单位人员工单的 {@code social.SubmitHouseholdWorkOrder} 载荷（顺序 = 结局 losses 表序）。
     *
     * <p>★ 单条工单 = 该单位本次全部 {@code REMOVE_MEMBERS} 步骤；taken 来自同一个选人结果，工具只组批、不二次选人。
     */
    List<String> casualtyPayloadsJson() {
      List<String> payloads = new ArrayList<>(casualtyOrders.size());
      for (CasualtyOrder order : casualtyOrders) {
        payloads.add(order.payloadJson());
      }
      return List.copyOf(payloads);
    }

    /**
     * ★ 批内前段命令（ResolveCombatStage 之前）的精确顺序：逐 unit 先人员工单（如有）再装备命令（如有）。 apply 组批与 preview
     * 命令视图都只认这一处，避免视图与真实批序漂移。
     */
    List<PlannedCommand> unitCommands() {
      List<PlannedCommand> commands = new ArrayList<>();
      int casualtyIndex = 0;
      for (CombatUnitLoss loss : outcome.losses()) {
        if (!loss.manpower().isEmpty()) {
          CasualtyOrder order = casualtyOrders.get(casualtyIndex++);
          commands.add(new PlannedCommand(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE, order.payloadJson()));
        }
        if (!loss.equipment().isEmpty()) {
          commands.add(
              new PlannedCommand(ADJUST_COMPOSITION_TYPE, equipmentAdjustPayloadJson(loss)));
        }
      }
      return List.copyOf(commands);
    }

    /** 实际批内命令类型（含逐单位顺序 + ResolveCombatStage + 清链接；preview 与 apply 共用）。 */
    List<String> commandTypes() {
      List<PlannedCommand> unitCommands = unitCommands();
      List<String> types = new ArrayList<>(unitCommands.size() + 1 + clearLinkUnits.size());
      for (PlannedCommand command : unitCommands) {
        types.add(command.type());
      }
      types.add(RESOLVE_STAGE_TYPE);
      for (int i = 0; i < clearLinkUnits.size(); i++) {
        types.add(SET_STATE_DESCRIPTION_TYPE);
      }
      return List.copyOf(types);
    }

    /** {@code army.ResolveCombatStage} 载荷：显式 outcome（工具已推导）+ 投骰路径的 seed（供命令复核/落库）。 */
    String resolvePayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("combatId", combatId.value());
      payload.put("stageId", stageId.value());
      payload.put("outcomeId", outcome.id().value());
      seed.ifPresent(value -> payload.put("seed", value));
      return ToolSupport.json(payload);
    }

    /** 逐单位的 {@code unit.SetStateDescription} 清除载荷（省略 address = 删除 combat 链接）。 */
    List<String> clearPayloadsJson() {
      List<String> payloads = new ArrayList<>(clearLinkUnits.size());
      for (UnitId unit : clearLinkUnits) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", unit.value());
        payload.put("state", STATE_KEY);
        payloads.add(ToolSupport.json(payload));
      }
      return List.copyOf(payloads);
    }

    /** 命令类型 + 条数（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<Map<String, Object>> commandCounts() {
      List<Map<String, Object>> counts = new ArrayList<>(4);
      counts.add(commandCount(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE, casualtyWorkOrderCount()));
      counts.add(commandCount(ADJUST_COMPOSITION_TYPE, adjustCommandCount()));
      counts.add(commandCount(RESOLVE_STAGE_TYPE, 1));
      counts.add(commandCount(SET_STATE_DESCRIPTION_TYPE, clearLinkUnits.size()));
      return List.copyOf(counts);
    }

    /** 选中结局的损失视图（preview 用；形状与命令载荷同源）。 */
    List<Map<String, Object>> lossesView() {
      List<Map<String, Object>> rows = new ArrayList<>(outcome.losses().size());
      for (CombatUnitLoss loss : outcome.losses()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("unit", loss.unit().value());
        row.put("manpower", ToolSupport.compositionDeltaView(loss.manpower()));
        row.put("equipment", ToolSupport.compositionDeltaView(loss.equipment()));
        row.put("empty", loss.empty());
        rows.add(row);
      }
      return List.copyOf(rows);
    }

    /** 逐单位人员伤亡工单视图（preview 用；含 orderId/target/shares/可直接比对的载荷字节）。 */
    List<Map<String, Object>> casualtyOrdersView() {
      List<Map<String, Object>> rows = new ArrayList<>(casualtyOrders.size());
      for (CasualtyOrder order : casualtyOrders) {
        rows.add(order.view());
      }
      return List.copyOf(rows);
    }

    private static Map<String, Object> commandCount(String type, int count) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", type);
      row.put("count", count);
      return row;
    }
  }
}
