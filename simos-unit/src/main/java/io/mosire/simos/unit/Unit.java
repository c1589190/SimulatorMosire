package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TemporalSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单位（M3 spec §4.1）：严格树的一个节点。{@code parent} 与 {@code position} 是**时态序列**（军队会改编、会调动）， 其余是普通值（C3；历史由
 * M4 的 revision 日志承载）。
 *
 * <p>★ {@code parent} 指向**自身 id** 在构造期就抛（便宜）；**跨单位的环**由 {@link UnitState} 构造期查 ——两者分工见 spec §4.2。
 *
 * <p>★ {@code position} 允许为空（"不知道在哪"），无则向父取 / 叠加偏移（{@link UnitState#effectivePosition}）。
 *
 * <p>★ **Unit 扩容 T1 的四个新字段**（spec §一.3 / §三.2）：{@code status}（三态，普通字段）、{@code attached}/{@code
 * offset} （{@code Formation}：是否跟随父 + 相对父的站位，与 {@code parent} 同形的时态序列）、{@code
 * rejoinTarget}（回归意图，普通字段）。 四者的默认值必须让**旧档行为一字不变**：{@code MOVING} / {@code true} / {@code empty} /
 * {@code empty}。旧 9 参签名由下面的**兼容构造器**保留（生产拷贝点一律走 canonical 13 参形态，避免丢字段）。
 */
public record Unit(
    UnitId id,
    String name,
    SegmentedSeries<Optional<UnitId>> parent,
    SegmentedSeries<Optional<HexCoord>> position,
    int member,
    Map<String, Integer> equipment,
    int speed,
    int mobilityPerMille,
    Optional<Movement> movement,
    UnitStatus status,
    SegmentedSeries<Boolean> attached,
    SegmentedSeries<Optional<RelativeOffset>> offset,
    Optional<UnitId> rejoinTarget) {

  public Unit {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    requireNoEvents(parent, "parent");
    requireNoEvents(position, "position");
    for (Segment<Optional<UnitId>> segment : parent.segments()) {
      if (segment.value().filter(id::equals).isPresent()) {
        throw new IllegalArgumentException("parent 不得指向自身: " + id);
      }
    }
    if (member < 0) {
      throw new IllegalArgumentException("member 必须 ≥ 0: " + member);
    }
    equipment =
        Collections.unmodifiableMap(
            copyEquipment(equipment)); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
    if (speed < 1) {
      throw new IllegalArgumentException("speed 必须 ≥ 1: " + speed);
    }
    if (mobilityPerMille < 1) {
      throw new IllegalArgumentException("mobilityPerMille 必须 ≥ 1: " + mobilityPerMille);
    }
    if (movement == null) {
      throw new IllegalArgumentException("movement 不得为 null（无在途路线用 Optional.empty()）");
    }
    if (status == null) {
      throw new IllegalArgumentException("status 不得为 null");
    }
    requireNoEvents(attached, "attached");
    requireNoEvents(offset, "offset");
    if (rejoinTarget == null) {
      throw new IllegalArgumentException("rejoinTarget 不得为 null（无回归目标用 Optional.empty()）");
    }
  }

  /**
   * ★ **兼容构造器**（T1，R1 的对策）：旧 9 参签名 ⇒ 以 {@code parent} 的锚段时刻造 {@code attached}/{@code offset}
   * 的锚段，{@code status = MOVING}、{@code rejoinTarget = empty}。
   *
   * <p>它让全仓约 40 处既有 {@code new Unit(…)} 调用点零改动编过；**生产拷贝点不要用它**（那会丢新字段），一律走 canonical 13 参形态——{@code
   * UnitOperations.copy} / {@code UnitMoves.evaluate} 的 frozen 视图 / {@code
   * UnitTimeParticipant.withPositionAndMovement} 都已改直。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement) {
    this(
        id,
        name,
        parent,
        position,
        member,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(anchorOf(parent), true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(anchorOf(parent), Optional.<RelativeOffset>empty())),
            List.of(),
            null),
        Optional.empty());
  }

  /** 兼容构造器的锚时刻取 {@code parent} 的首段（{@code parent} 不得为 null、构造期保证至少一段）。 */
  private static SimosTimestamp anchorOf(SegmentedSeries<?> series) {
    if (series == null) {
      throw new IllegalArgumentException("parent 不得为 null");
    }
    return series.segments().get(0).from();
  }

  /** ★ 两条时态序列的变化一律用"追加段"表达：`ADD` 对 `Optional` 无定义，`SET` 与段重复（spec §4.1 第 3 条）。 */
  private static void requireNoEvents(TemporalSeries<?> series, String field) {
    if (series == null) {
      throw new IllegalArgumentException(field + " 不得为 null");
    }
    if (!series.events().isEmpty()) {
      throw new IllegalArgumentException(field + " 不得带事件：变化一律用追加段表达（spec §4.1）");
    }
  }

  /**
   * 拷贝 + 逐键值查 null（不做冻结）。★ 取代说明（R-6-a，有 M2 Task 5 实测先例）：计划原稿的 {@code frozenEquipment} 在 helper
   * **里** {@code unmodifiableMap} 并返回——SpotBugs 只认赋值处 看得见的 {@code
   * Collections.unmodifiable*}，藏在私有方法里就报 {@code EI_EXPOSE_REP}（实测 verify 报 1 条）。照 GameMap
   * 先例改为"helper 只拷贝校验、赋值处冻结"，行为一字不变。
   */
  private static Map<String, Integer> copyEquipment(Map<String, Integer> equipment) {
    if (equipment == null) {
      throw new IllegalArgumentException("equipment 不得为 null");
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException("equipment 的值必须 ≥ 0: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
