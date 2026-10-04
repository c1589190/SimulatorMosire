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

/**
 * ★★ {@code simos.army.resolveCombat} 的<b>纯推导</b>（阶段 D4 / 用户设计 D-009 补裁 + D-010 +
 * D-012，2026-10-02）：从一份 {@link SimulationState} 与参数算出结算 {@link Plan}——<b>不碰 {@code ToolContext} /
 * {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义 （工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>职责边界（D-010/R2）</b>：Army 只做编排/随机化——判定算法委托 {@link CombatResolution}（全仓唯一）；命中结局的损失以 {@code
 * unit.AdjustComposition}（有符号增量）**逐单位**提交，写单位仍走 unit 命令；记录更新走 {@code army.ResolveCombatStage}，三条同批
 * = 一条 revision。
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
 * 必须在概率表里；投骰必须有概率表；命中结局里**有实际增量**的单位必须存在于 unit 切片（否则同批的 AdjustComposition 必被拒，这里折成前置具名拒）。
 */
final class ResolveCombatPlan {

  /** 第一条起的命令类型（逐单位应用有符号损失）。 */
  static final String ADJUST_COMPOSITION_TYPE = AdjustCompositionHandler.TYPE;

  /** 更新交战记录的判定命令类型。 */
  static final String RESOLVE_STAGE_TYPE = ResolveCombatStageHandler.TYPE;

  /** 结束（全部阶段判定完）时逐单位清除状态链接的命令类型。 */
  static final String SET_STATE_DESCRIPTION_TYPE = StartCombatPlan.SET_STATE_DESCRIPTION_TYPE;

  /** Unit 侧"进入交战"的状态键（与 {@link StartCombatPlan#STATE_KEY} 同源）。 */
  static final String STATE_KEY = StartCombatPlan.STATE_KEY;

  private ResolveCombatPlan() {}

  /**
   * 推导入口（校验清单见类注）。
   *
   * @param state 读数所在状态（preview/apply 共用同一坐标）
   * @param combatId 交战记录 id
   * @param stageId 要判定的阶段 id
   * @param outcomeId 显式结局（空 = 由 seed/派生种子投骰）
   * @param seed 显式 seed（空 = 由 {@code combatId+stageId+tick+概率表} 确定性派生）
   */
  static Plan derive(
      SimulationState state,
      String combatId,
      String stageId,
      Optional<String> outcomeId,
      Optional<Long> seed) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(outcomeId, "outcomeId");
    Objects.requireNonNull(seed, "seed");
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
    for (CombatUnitLoss loss : selection.outcome().losses()) {
      if (!loss.empty() && !units.units().containsKey(loss.unit())) {
        throw new IllegalArgumentException(
            "结局" + selection.outcome().id().value() + " 的损失指向不存在的单位: " + loss.unit().value());
      }
      // ★★ S3b（2026-10-09）：Unit.manpower 已退役 ⇒ 人力战损必须落到 Social 家户（成员批次），本工具尚未接线。
      //   这里 fail-closed、具名拒，绝不把人力损失静默丢给 unit.AdjustComposition（那会变成第二本 headcount）。
      if (!loss.manpower().isEmpty()) {
        throw new IllegalArgumentException(
            "结局 "
                + selection.outcome().id().value()
                + " 含 "
                + loss.manpower().size()
                + " 条人力损失（unit "
                + loss.unit().value()
                + "）：S3b 起 Unit.manpower 已退役，人力战损必须落到 Social 家户成员批次；"
                + "本工具尚未接线该写口（具名缺口），拒绝把人员损失写回 unit 状态");
      }
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
        state.meta().timestamp().tick(),
        allResolvedAfter,
        clearLinkUnits);
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
      List<UnitId> clearLinkUnits) {

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
    }

    /** 会生成 {@code unit.AdjustComposition} 的损失条数（两条表都为空的条目不生成命令）。 */
    int adjustCommandCount() {
      int count = 0;
      for (CombatUnitLoss loss : outcome.losses()) {
        if (!loss.empty()) {
          count++;
        }
      }
      return count;
    }

    /** 逐单位的 {@code unit.AdjustComposition} 载荷（顺序 = 结局 losses 表序；跳过无变动的条目）。 */
    List<String> adjustPayloadsJson() {
      List<String> payloads = new ArrayList<>();
      for (CombatUnitLoss loss : outcome.losses()) {
        if (loss.empty()) {
          continue;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", loss.unit().value());
        // ★ S3b：不再发 manpower（该结局若含人力损失已在 derive 处具名拒）；装备战损照常。
        payload.put("equipment", ToolSupport.compositionDeltaView(loss.equipment()));
        payloads.add(ToolSupport.json(payload));
      }
      return List.copyOf(payloads);
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
      List<Map<String, Object>> counts = new ArrayList<>(3);
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

    private static Map<String, Object> commandCount(String type, int count) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", type);
      row.put("count", count);
      return row;
    }
  }
}
