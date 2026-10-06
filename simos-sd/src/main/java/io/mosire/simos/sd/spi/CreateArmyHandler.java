package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
 * <p>★ <b>载荷语义</b>：{@code masterGovUnitId} 可缺省/null（未认主子）；给了必须存在、且带 {@link
 * GovernmentFormation}（认主子只认 GOV，与 {@code unit.SetArmyFormation} 同口径）。{@code rootUnitId} 是否存在仍经
 * {@link SdSnapshots#unitExists} 只读 unit 切片（铁律 3）。
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
    String armyForLog = null;
    String masterForLog = null;
    String rootForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      if (payload.has("nationId")) {
        return rejected(
            "sd.CreateArmy 不再接受 nationId：Army 已去 NationId，改认 masterGovUnitId"
                + "（可选，缺省 = 未认主子）。国家归属请用 sd.CreateNation/Affiliation.Nation；"
                + "认领 GOV 请用 unit.SetArmyFormation。",
            "army",
            "-");
      }
      ArmyId id = ArmyId.parse(SdPayloads.requireText(payload, "armyId"));
      armyForLog = id.value();
      Optional<UnitId> masterGovUnitId =
          SdPayloads.optionalText(payload, "masterGovUnitId").map(UnitId::parse);
      masterForLog = masterGovUnitId.map(UnitId::value).orElse(null);
      UnitId rootUnit = UnitId.parse(SdPayloads.requireText(payload, "rootUnitId"));
      rootForLog = rootUnit.value();
      String name = SdPayloads.requireText(payload, "name");
      if (base.armies().containsKey(id)) {
        return rejected("军队已存在: " + id, "army", armyForLog);
      }
      if (masterGovUnitId.isPresent()) {
        Unit masterGov = SdSnapshots.units(state).units().get(masterGovUnitId.get());
        if (masterGov == null) {
          return rejected(
              "masterGovUnitId 不存在: " + masterGovUnitId.get(),
              "army",
              armyForLog,
              "masterGov",
              masterForLog);
        }
        if (!(masterGov.module().orElse(null) instanceof GovernmentFormation)) {
          return rejected(
              "masterGovUnitId 不是 GOV 单位: " + masterGovUnitId.get(),
              "army",
              armyForLog,
              "masterGov",
              masterForLog);
        }
      }
      if (!SdSnapshots.unitExists(state, rootUnit)) {
        return rejected("rootUnitId 不存在: " + rootUnit, "army", armyForLog, "rootUnit", rootForLog);
      }
      Map<ArmyId, Army> next = new LinkedHashMap<>(base.armies());
      next.put(id, new Army(id, masterGovUnitId, rootUnit, name));
      EventLog.channel(SdLog.nation())
          .info(
              LogEvent.of(
                  "SD_ARMY_CREATED",
                  SdLogSource.SD_NATION,
                  "id",
                  id.value(),
                  "masterGov",
                  masterGovUnitId.map(UnitId::value).orElse("-"),
                  "rootUnit",
                  rootUnit.value(),
                  "name",
                  name,
                  "armies",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withArmies(next)));
    } catch (IllegalArgumentException e) {
      return rejected(
          e.getMessage(), "army", armyForLog, "masterGov", masterForLog, "rootUnit", rootForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.nation(), SdLogSource.SD_NATION, "SD_CREATE_ARMY_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
