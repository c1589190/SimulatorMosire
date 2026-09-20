package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.CreateArmy} 命令的处理器（spec §四）。
 *
 * <pre>{@code
 * {"armyId":"a1","nationId":"n1","rootUnitId":"u-1","name":"第一军"}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；{@code nationId} 不存在；{@code rootUnitId} 不存在（存在性经 {@link SdSnapshots#unitExists} 只读
 * unit 切片，铁律 3）。
 */
public final class CreateArmyHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.CreateArmy";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      ArmyId id = ArmyId.parse(SdPayloads.requireText(payload, "armyId"));
      NationId nationId = NationId.parse(SdPayloads.requireText(payload, "nationId"));
      UnitId rootUnit = UnitId.parse(SdPayloads.requireText(payload, "rootUnitId"));
      String name = SdPayloads.requireText(payload, "name");
      if (base.armies().containsKey(id)) {
        return new HandlerOutcome.Rejected("军队已存在: " + id);
      }
      if (!base.nations().containsKey(nationId)) {
        return new HandlerOutcome.Rejected("nationId 不存在: " + nationId);
      }
      if (!SdSnapshots.unitExists(state, rootUnit)) {
        return new HandlerOutcome.Rejected("rootUnitId 不存在: " + rootUnit);
      }
      Map<ArmyId, Army> next = new LinkedHashMap<>(base.armies());
      next.put(id, new Army(id, nationId, rootUnit, name));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withArmies(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
