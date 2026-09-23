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
import io.mosire.simos.util.spi.CommandTargets;
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
 * <p>★★ **{@code position} 可选（本次改动）**：省略（或 {@code null}）⇒ 该单位**无自身位置**（{@code position} 段写 {@code
 * Optional.empty()}）⇒ 它按 {@code attached + parent} 向父取位，即**"移动时跟随"**。这是让"跟随"在命令面上可 达的唯一入口：此前 {@code
 * position} 必填、且没有任何命令能清掉自身位置，故单位永远命中"自身有位置"那一支、父动子不动。
 *
 * <p>★★ **省略 {@code position} 时 {@code parent} 必填**：无父又无自身位置 = 单位不在图上，是坏输入 ⇒ 拒绝（理由带 "position"
 * 字样，便于命令边界读）。给了 {@code position} ⇒ 行为与今天**逐字相同**（向后兼容）。
 *
 * <p>★ 其余字段缺失 ⇒ {@code Rejected}；重 id、父不存在、负人数等域规则违反由 {@code Unit} 构造期 / {@code UnitOperations} 抛出的
 * {@link IllegalArgumentException} 折成拒绝。
 */
public final class CreateUnitHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：按载荷里点名的**新**单位 id 判（建之后它才存在，见契约的创建型口径）。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

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
      // ★ position 可选：省略/null ⇒ 无自身位置（跟随父）；但那时必须给 parent（否则单位不在图上，是坏输入）。
      Optional<HexCoord> position = UnitPayloads.optionalHex(payload, "position");
      int member = UnitPayloads.requireInt(payload, "member");
      Map<String, Integer> equipment = UnitPayloads.requireEquipment(payload, "equipment");
      int speed = UnitPayloads.requireInt(payload, "speed");
      int mobilityPerMille = UnitPayloads.requireInt(payload, "mobilityPerMille");
      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");
      if (position.isEmpty() && parent.isEmpty()) {
        throw new IllegalArgumentException(
            "字段 position 缺失且未给 parent：无父又无自身位置的单位不在图上（要么给 position，要么给 parent）");
      }
      UnitStatus status = UnitPayloads.optionalStatus(payload, "status").orElse(UnitStatus.MOVING);
      SimosTimestamp at = state.meta().timestamp();
      Unit unit =
          new Unit(
              id,
              name,
              new SegmentedSeries<>(List.of(new Segment<>(at, parent)), List.of(), null),
              new SegmentedSeries<>(List.of(new Segment<>(at, position)), List.of(), null),
              member,
              equipment,
              speed,
              mobilityPerMille,
              Optional.empty(),
              status,
              new SegmentedSeries<>(List.of(new Segment<>(at, true)), List.of(), null),
              new SegmentedSeries<>(
                  List.of(new Segment<>(at, Optional.<RelativeOffset>empty())), List.of(), null),
              Optional.empty(),
              // ★ 创建（不是拷贝）：视野半径取缺省 1 圈——载荷里没有该字段，不凭空发明输入（spec §4.1 只要求"缺省 1"）。
              Unit.DEFAULT_VISION_RADIUS);
      UnitState next = UnitOperations.create(snapshot.state(), unit);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
