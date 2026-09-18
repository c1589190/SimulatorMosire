package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 变异体 m2（T4 变异自证）：CreateUnit 初始段时刻写死 {@code SimosTimestamp.of(0)}，而非 base 状态时间戳。
 *
 * <p>与原件唯一差异在第 54 行：{@code at} 的来源。字段解析 / 拒绝路径全不变；只有"初始段时刻 = base 时间戳"这条被保护行为被破坏。
 */
public final class CreateUnitHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.CreateUnit";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      String name = UnitPayloads.requireText(payload, "name");
      HexCoord position = UnitPayloads.requireHex(payload, "position");
      int member = UnitPayloads.requireInt(payload, "member");
      Map<String, Integer> equipment = UnitPayloads.requireEquipment(payload, "equipment");
      int speed = UnitPayloads.requireInt(payload, "speed");
      int mobilityPerMille = UnitPayloads.requireInt(payload, "mobilityPerMille");
      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");
      SimosTimestamp at = SimosTimestamp.of(0);
      Unit unit =
          new Unit(
              id,
              name,
              new SegmentedSeries<>(List.of(new Segment<>(at, parent)), List.of(), null),
              new SegmentedSeries<>(
                  List.of(new Segment<>(at, Optional.of(position))), List.of(), null),
              member,
              equipment,
              speed,
              mobilityPerMille,
              Optional.empty());
      UnitState next = UnitOperations.create(snapshot.state(), unit);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
