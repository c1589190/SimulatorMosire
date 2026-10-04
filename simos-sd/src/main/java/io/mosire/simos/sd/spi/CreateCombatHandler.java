package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * {@code sd.CreateCombat} 命令的处理器（spec §四，C1）。
 *
 * <pre>{@code
 * {"combatId":"c1","name":"交战一","participants":["u-1"]}
 * }</pre>
 *
 * <p>★ 只建 {@link Combat}（阶段表为空）；首个 {@code sd.AddCombatStage} 才会建对应的 {@code CombatState}—— 因为 {@code
 * CombatState.currentStage} 必须落在阶段链里，没有阶段就没有合法的初始值（执行期取代说明 C1-a）。
 *
 * <p>★ 拒绝：id 已存在；参与单位不存在（只读 unit 切片，铁律 3）。
 */
public final class CreateCombatHandler implements CommandHandler {

  private static final Logger LOG = SdLog.combat();

  @Override
  public String type() {
    return "sd.CreateCombat";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId id = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      String name = SdPayloads.requireText(payload, "name");
      Set<UnitId> participants = SdPayloads.optionalUnitIdSet(payload, "participants");
      if (base.combats().containsKey(id)) {
        return new HandlerOutcome.Rejected("交战已存在: " + id);
      }
      for (UnitId unit : participants) {
        if (!SdSnapshots.unitExists(state, unit)) {
          return new HandlerOutcome.Rejected("参与单位不存在: " + unit);
        }
      }
      Map<CombatId, Combat> next = new LinkedHashMap<>(base.combats());
      next.put(id, new Combat(id, name, List.of(), participants, Optional.empty()));
      LOG.info(
          "event=SD_COMBAT_CREATED id={} name={} participants={}",
          id.value(),
          name,
          participants.size());
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withCombats(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
