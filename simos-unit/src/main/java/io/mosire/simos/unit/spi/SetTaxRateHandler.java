package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.SetTaxRate} 命令的处理器（辖区阶段 5 / 计划 §2.2）：{@code unitId, regionId, ratePerMille}。
 *
 * <p>★ <b>语义</b>：upsert 某管辖区域的长期税率，其余字段与税率表顺序原样带过。两条具名拒（领域层给出，命令边界折成 {@code Rejected}）：
 *
 * <ul>
 *   <li>单位不存在 / 没有 {@code jurisdiction} ⇒ 拒，指路 {@code unit.SetJurisdiction}；
 *   <li>{@code regionId} 不在该单位管辖的 key 集里 ⇒ 拒，指路先 {@code unit.SetJurisdiction} 把它纳入管辖；
 *   <li>{@code ratePerMille} 不在 {@code [0,1000]} ⇒ 拒（不钳制）。
 * </ul>
 *
 * <p>★ **目标声明**（{@link CommandTargets}）：按载荷点名的 unitId 判；region 是 map 命名空间的数据，本命令只写 unit 侧管辖。
 */
public final class SetTaxRateHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetTaxRate";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    String unitForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      unitForLog = id.value();
      RegionId regionId = RegionId.parse(UnitPayloads.requireText(payload, "regionId"));
      long ratePerMille = UnitPayloads.requireLong(payload, "ratePerMille");
      UnitState next = UnitOperations.setTaxRate(snapshot.state(), id, regionId, ratePerMille);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_TAX_RATE_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "region",
                  regionId.value(),
                  "ratePerMille",
                  ratePerMille));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_TAX_RATE_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
