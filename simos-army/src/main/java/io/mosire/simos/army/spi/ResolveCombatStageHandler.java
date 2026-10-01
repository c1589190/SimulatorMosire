package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcomeId;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatResolution;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code army.ResolveCombatStage} 命令的处理器（阶段 D4 / 用户设计 D-009 补裁 +
 * D-010，2026-10-02）：**给一个阶段投骰判定并写进记录**。
 *
 * <pre>{@code
 * {"combatId":"c-1","stageId":"s1"}                    // 确定性派生 seed 投骰
 * {"combatId":"c-1","stageId":"s1","seed":12345}       // 显式 seed 投骰
 * {"combatId":"c-1","stageId":"s1","outcomeId":"o1"}   // 显式结局（不投骰）
 * }</pre>
 *
 * <p>★★ <b>唯一判定算法在 {@link CombatResolution}</b>：本 handler 只做"查记录/查阶段/防重复判定/落新阶段"，三条骰子语义（显式结局 / 显式
 * seed / 两者同时给的复核）全在那份纯函数里——app 工具组批前也调它（要先知道选中结局的损失），不可能两处掷出不同结果。
 *
 * <p>★ <b>不可重复判定</b>：已判定的阶段再提交 ⇒ 具名拒（"同一阶段的判定只做一次"）；要改判请追加一个新阶段/新记录，本阶段不提供重骰。判定落库是**新 revision**，旧
 * revision 里该阶段仍是未判定。
 *
 * <p>★ <b>本命令只写记录，不动单位</b>：命中结局的损失以 {@code CombatUnitLoss}（{@code unit} + 有符号 {@code
 * manpower/equipment} 增量）原样存在记录里； **把损失写进单位**由 GM 组合工具 {@code simos.army.resolveCombat} 用同批 {@code
 * unit.AdjustComposition} 完成（D-010：Army 只编排，写单位走 unit 命令）。单独提交本命令 = 只记判定、不改单位（低层命令口径）。
 *
 * <p>★ <b>标 {@link GmOnlyCommand}</b>：与 {@link RecordCombatHandler} 同一条边界。
 */
public final class ResolveCombatStageHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "army.ResolveCombatStage";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ArmySnapshot snapshot = ArmySnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      CombatRecordId combatId = CombatRecordId.parse(ArmyPayloads.requireText(payload, "combatId"));
      CombatRecord record = snapshot.data().combats().get(combatId);
      if (record == null) {
        return new HandlerOutcome.Rejected("交战记录不存在: " + combatId.value());
      }
      CombatStageId stageId = CombatStageId.parse(ArmyPayloads.requireText(payload, "stageId"));
      CombatStage stage = null;
      for (CombatStage candidate : record.stages()) {
        if (candidate.id().equals(stageId)) {
          stage = candidate;
          break;
        }
      }
      if (stage == null) {
        return new HandlerOutcome.Rejected(
            "阶段不存在: " + stageId.value() + "（交战记录 " + combatId.value() + "）");
      }
      if (stage.resolved()) {
        return new HandlerOutcome.Rejected("阶段已判定过，不可重复投骰: " + stageId.value() + "（要改判请追加新阶段）");
      }
      Optional<CombatOutcomeId> outcomeId =
          ArmyPayloads.optionalText(payload, "outcomeId").map(CombatOutcomeId::parse);
      Optional<Long> seed = ArmyPayloads.optionalLong(payload, "seed");
      CombatResolution.Selection selection =
          CombatResolution.select(
              record.id(), stage.id(), record.tick(), stage.outcomes(), outcomeId, seed);
      CombatStage resolved = stage.resolvedAs(selection.outcome().id(), selection.seed());
      CombatRecord next = record.withReplacedStage(resolved);
      ArmyData nextData = snapshot.data().withCombat(next);
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), nextData));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
