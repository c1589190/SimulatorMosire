package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.CreateArmy} 命令的处理器（阶段 12：Army 去 {@code NationId}、改认 {@code masterGovUnitId}）。
 *
 * <pre>{@code
 * {"armyId":"a1","masterGovUnitId":"g1","rootUnitId":"u-1","name":"第一军"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code masterGovUnitId} 可缺省/null（未认主子）；给了必须存在、且带 {@link GovFormation}（认主子只认
 * GOV，与 {@code unit.SetArmyFormation} 同口径）。{@code rootUnitId} 是否存在仍经 {@link SdSnapshots#unitExists}
 * 只读 unit 切片（铁律 3）。
 *
 * <p>★ <b>旧 {@code nationId} 键具名拒并指路</b>（本阶段二选一，选拒绝不选静默忽略）：字段已从 {@link Army}
 * 删除，继续兼容读会让人以为"军队还记着国家"。拒因直接指向 {@code masterGovUnitId} 与 {@code unit.SetArmyFormation}——既
 * fail-closed，又不把 {@code nationId} 硬映射成 GOV。
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
      if (payload.has("nationId")) {
        return new HandlerOutcome.Rejected(
            "sd.CreateArmy 不再接受 nationId：Army 已去 NationId，改认 masterGovUnitId"
                + "（可选，缺省 = 未认主子）。国家归属请用 sd.CreateNation/Affiliation.Nation；"
                + "认领 GOV 请用 unit.SetArmyFormation。");
      }
      ArmyId id = ArmyId.parse(SdPayloads.requireText(payload, "armyId"));
      Optional<UnitId> masterGovUnitId =
          SdPayloads.optionalText(payload, "masterGovUnitId").map(UnitId::parse);
      UnitId rootUnit = UnitId.parse(SdPayloads.requireText(payload, "rootUnitId"));
      String name = SdPayloads.requireText(payload, "name");
      if (base.armies().containsKey(id)) {
        return new HandlerOutcome.Rejected("军队已存在: " + id);
      }
      if (masterGovUnitId.isPresent()) {
        Unit masterGov = SdSnapshots.units(state).units().get(masterGovUnitId.get());
        if (masterGov == null) {
          return new HandlerOutcome.Rejected("masterGovUnitId 不存在: " + masterGovUnitId.get());
        }
        if (!(masterGov.module().orElse(null) instanceof GovFormation)) {
          return new HandlerOutcome.Rejected("masterGovUnitId 不是 GOV 单位: " + masterGovUnitId.get());
        }
      }
      if (!SdSnapshots.unitExists(state, rootUnit)) {
        return new HandlerOutcome.Rejected("rootUnitId 不存在: " + rootUnit);
      }
      Map<ArmyId, Army> next = new LinkedHashMap<>(base.armies());
      next.put(id, new Army(id, masterGovUnitId, rootUnit, name));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withArmies(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
