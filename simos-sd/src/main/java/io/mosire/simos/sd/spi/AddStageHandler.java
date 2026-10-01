package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatStages;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sd.AddCombatStage} 命令的处理器（spec §四，C1）。
 *
 * <pre>{@code
 * {"combatId":"c1","combatStateId":"cs1","hex":{"q":0,"r":0},
 *  "stage":{"stageId":"s1","name":"阶段一","participants":["u-1"],
 *           "entry":[{"@class":"at_or_after_tick","tick":0}],
 *           "exit":[{"@class":"at_or_after_tick","tick":5}],
 *           "minDurationTicks":0,"maxDurationTicks":10,
 *           "outcomes":{"options":[{"id":"o1","label":"胜","weight":1}]}}}
 * }</pre>
 *
 * <p>★ 拒绝：交战不存在；**后续阶段**（已有 CombatState）的载荷**出现** {@code combatStateId} 或 {@code hex} 键（含显式 {@code
 * null}）——这两个参数只在首阶段生效；**链式条件断裂**（上一 exit ≠ 新 entry，N1）；outcomeTable 权重 ≤0 / 空表（N2）； 首阶段缺 {@code
 * combatStateId}/{@code hex}。
 */
public final class AddStageHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.AddCombatStage";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId combatId = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      CombatStage stage = SdPayloads.requireStage(payload, "stage");
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return new HandlerOutcome.Rejected("交战不存在: " + combatId);
      }
      if (SdCombats.stateOrNull(base, combatId) != null
          && (payload.has("combatStateId") || payload.has("hex"))) {
        return new HandlerOutcome.Rejected(
            "后续阶段不接受 combatStateId/hex；这两个参数只在首阶段生效"
                + "（交战 "
                + combatId
                + " 已有 CombatState，追加阶段不得再指定战斗状态与坐标）");
      }
      java.util.List<CombatStage> stages = CombatStages.append(combat.stages(), stage);
      Combat nextCombat =
          new Combat(
              combat.id(), combat.name(), stages, combat.participants(), combat.finalOutcome());
      Map<CombatId, Combat> nextCombats = new LinkedHashMap<>(base.combats());
      nextCombats.put(combatId, nextCombat);

      SdState withCombats = base.withCombats(nextCombats);
      if (SdCombats.stateOrNull(withCombats, combatId) != null) {
        return new HandlerOutcome.Applied(SdChangeSet.between(base, withCombats));
      }
      CombatStateId combatStateId =
          new CombatStateId(SdPayloads.requireText(payload, "combatStateId"));
      HexCoord hex = SdPayloads.requireHex(payload, "hex");
      if (withCombats.combatStates().containsKey(combatStateId)) {
        return new HandlerOutcome.Rejected("CombatState 已存在: " + combatStateId);
      }
      CombatState combatState =
          new CombatState(
              combatStateId,
              combatId,
              stage.id(),
              hex,
              stage.participants(),
              Optional.empty(),
              Set.of());
      Map<CombatStateId, CombatState> nextStates = new LinkedHashMap<>(withCombats.combatStates());
      nextStates.put(combatStateId, combatState);
      return new HandlerOutcome.Applied(
          SdChangeSet.between(base, withCombats.withCombatStates(nextStates)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
