package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * {@code army.UpdateArmy}：只改既有 {@link Army} 的 {@code name} / {@code masterGovUnitId}，其余字段 （id /
 * rootUnit / contactWatch）原样保留。
 *
 * <pre>{@code
 * {"armyId":"a1","name":"新名","masterGovUnitId":"g1"}
 * }</pre>
 *
 * <p>★ <b>为什么需要 name 更新</b>：既有 {@code army.SetArmyMasterGov} 只改主子；{@code simos.army.editFormation}
 * 的 update 要能在同一原子批里改名并保持其他字段，不能靠"删了重建"（会丢 rootUnit/contactWatch 与引用）。
 *
 * <p>★ {@code masterGovUnitId} 缺席 = 保持；显式 {@code null}/空串 = 解除认领（与 SetArmyMasterGov
 * 同口径）；给了非空白必须存在且带 {@link GovernmentFormation}。至少给一个字段，否则具名拒。
 *
 * <p>★ <b>GM-only</b>（不打 {@code DmUsableCommand}）。
 */
public final class UpdateArmyHandler implements CommandHandler {

  public static final String TYPE = "army.UpdateArmy";

  private static final Logger LOG = ArmyLog.armies();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ArmySnapshot snapshot = ArmySnapshots.of(state);
    String armyForLog = null;
    String masterForLog = null;
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      ArmyId id = ArmyId.parse(ArmyPayloads.requireText(payload, "armyId"));
      armyForLog = id.value();
      Army existing = snapshot.data().armies().get(id);
      if (existing == null) {
        return rejected("军队不存在: " + id, "army", armyForLog);
      }
      boolean any = false;
      String name = existing.name();
      if (payload.has("name") && !payload.get("name").isNull()) {
        name = ArmyPayloads.requireText(payload, "name");
        any = true;
      }
      Optional<UnitId> masterGov = existing.masterGovUnitId();
      if (payload.has("masterGovUnitId")) {
        any = true;
        masterGov =
            ArmyPayloads.optionalTextAllowingBlank(payload, "masterGovUnitId")
                .filter(text -> !text.isBlank())
                .map(UnitId::parse);
      }
      if (!any) {
        throw new IllegalArgumentException("army.UpdateArmy 至少要给 name / masterGovUnitId 之一");
      }
      masterForLog = masterGov.map(UnitId::value).orElse(null);
      if (masterGov.isPresent()) {
        Unit gov = ArmySnapshots.units(state).units().get(masterGov.orElseThrow());
        if (gov == null) {
          return rejected(
              "masterGovUnitId 不存在: " + masterGov.orElseThrow(),
              "army",
              armyForLog,
              "masterGov",
              masterForLog);
        }
        if (!(gov.module().orElse(null) instanceof GovernmentFormation)) {
          return rejected(
              "masterGovUnitId 不是 GOV 单位: " + masterGov.orElseThrow(),
              "army",
              armyForLog,
              "masterGov",
              masterForLog);
        }
      }
      ArmyData next =
          snapshot
              .data()
              .withArmy(
                  new Army(id, masterGov, existing.rootUnit(), name, existing.contactWatch()));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ARMY_UPDATED",
                  ArmyLogSource.ARMY_REGISTRY,
                  "id",
                  id.value(),
                  "name",
                  name,
                  "masterGov",
                  masterGov.map(UnitId::value).orElse("-"),
                  "rootUnit",
                  existing.rootUnit().value()));
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), next));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "army", armyForLog, "masterGov", masterForLog);
    }
  }

  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    ArmyPayloads.logRejected(
        LOG, ArmyLogSource.ARMY_REGISTRY, "ARMY_UPDATE_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
