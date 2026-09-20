package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
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
 * {@code unit.CreateUnit} 命令的处理器（spec §四）。自反序列化 payload、构造 {@link Unit}、调 {@link
 * UnitOperations#create}、返回 {@link HandlerOutcome.Applied}。
 *
 * <p>★ **初始段时刻 = base 状态时间戳**（{@code state.meta().timestamp()}）：信封不带时刻（C22/裁定 35）， {@code parent} 与
 * {@code position} 两条时态序列的 anchor 段都落在这一刻——与其余时间命令同一口径。
 *
 * <p>★ 字段缺失（含 {@code position}）⇒ {@code Rejected}；重 id、父不存在、负人数等域规则违反由 {@code Unit} 构造期 / {@code
 * UnitOperations} 抛出的 {@link IllegalArgumentException} 折成拒绝。
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
      UnitStatus status = UnitPayloads.optionalStatus(payload, "status").orElse(UnitStatus.MOVING);
      SimosTimestamp at = state.meta().timestamp();
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
              Optional.empty(),
              status,
              new SegmentedSeries<>(List.of(new Segment<>(at, true)), List.of(), null),
              new SegmentedSeries<>(
                  List.of(new Segment<>(at, Optional.<RelativeOffset>empty())), List.of(), null),
              Optional.empty());
      UnitState next = UnitOperations.create(snapshot.state(), unit);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
