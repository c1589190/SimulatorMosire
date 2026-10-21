package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreaKey;
import io.mosire.simos.map.HexAreas;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * 旧 {@code map.SetHexArea} 的写操作面：alpha 起它只是<b>单 component 类型总量 setter</b>，不再写 flat {@code areas}。
 *
 * <p>每个操作都是纯函数；变更集仍唯一由 {@link MapChangeSet#between(GameMap, GameMap)} 派生。
 *
 * <p>该 type 无 component，或恰有 1 个 component ⇒ set/add 改该 component 数量；≥2 component ⇒ 具名拒绝 {@link
 * LandComponentOperations#MULTIPLE_COMPONENTS_REASON}，不静默合并/重建。增量基数是 {@link
 * HexAreas#resolved}（与读面同一个数）。
 */
public final class HexAreaOperations {

  /** 写操作（载荷 {@code op} 的取值）。 */
  public enum Op {
    SET("set"),
    ADD("add");

    private final String wire;

    Op(String wire) {
      this.wire = wire;
    }

    /** 线格式（= 载荷取值 = 日志里的 {@code op}）。 */
    public String wire() {
      return wire;
    }

    /** 线格式 → op；非法一律抛。 */
    public static Op parse(String text) {
      for (Op value : values()) {
        if (value.wire.equals(text)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知写操作: " + text + "（合法取值: set | add）");
    }
  }

  private static final Logger LOG = MapLog.edit();

  private static final Logger TRACE = MapLog.trace();

  private HexAreaOperations() {}

  private static void debugRejected(String reason, Object... keyValues) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Object[] fields = new Object[keyValues.length + 2];
    fields[0] = "reason";
    fields[1] = reason;
    System.arraycopy(keyValues, 0, fields, 2, keyValues.length);
    EventLog.channel(LOG)
        .debug(LogEvent.of("MAP_HEX_AREA_EDIT_BLOCKED", MapLogSource.MAP_EDIT, fields));
  }

  /**
   * 改一格一个标量土地类型总量，返回变更集。
   *
   * @param base 现图
   * @param hex 要改的格
   * @param key 标量键（内置 urban/arable 或自定义）
   * @param op 全量赋值 / 增量
   * @param value set ⇒ 新绝对面积（≥0）；add ⇒ 有符号增量（±毫亩）
   */
  public static MapChangeSet setArea(
      GameMap base, HexCoord hex, HexAreaKey key, Op op, long value) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(op, "op");
    HexCell cell = base.hexes().get(hex);
    if (cell == null) {
      debugRejected("hex 不在图上", "hex", hex, "field", key.wireKey(), "op", op.wire());
      throw new IllegalArgumentException("hex 不在图上: " + hex);
    }
    LandTypeId type = HexAreaKey.landTypeOf(key);
    LandTypeArea area = cell.land().byType().get(type);
    if (area != null && area.components().size() >= 2) {
      debugRejected(
          LandComponentOperations.MULTIPLE_COMPONENTS_REASON,
          "hex",
          hex,
          "field",
          key.wireKey(),
          "components",
          area.components().size());
      throw new IllegalArgumentException(LandComponentOperations.MULTIPLE_COMPONENTS_REASON);
    }
    if (op == Op.SET && value < 0L) {
      debugRejected("set 的值不得为负", "hex", hex, "field", key.wireKey(), "value", value);
      throw new IllegalArgumentException(
          "op=set 的 value 是绝对面积，不得为负（毫亩）: " + key.wireKey() + "=" + value);
    }
    String terrain = base.terrainAt(hex);
    long before = HexAreas.resolved(cell, key, terrain);
    long after = op == Op.SET ? value : addExact(before, value, hex, key);
    if (after < 0L) {
      debugRejected("结果不得为负", "hex", hex, "field", key.wireKey(), "before", before, "value", value);
      throw new IllegalArgumentException(
          "结果不得为负（面积，毫亩）: " + key.wireKey() + " 现值 " + before + "，增量 " + value + " ⇒ " + after);
    }
    var nextLand =
        LandComponentOperations.setSingleComponentQuantity(cell.land(), hex, type, after);
    Map<HexCoord, HexCell> nextHexes = new LinkedHashMap<>(base.hexes());
    nextHexes.put(hex, cell.withLand(nextLand));
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MAP_HEX_AREA_SET",
                MapLogSource.MAP_EDIT,
                "hex",
                hex,
                "field",
                key.wireKey(),
                "op",
                op.wire(),
                "before",
                before,
                "after",
                after));
    if (TRACE.isTraceEnabled()) {
      EventLog.channel(TRACE)
          .trace(
              LogEvent.of(
                  "MAP_HEX_AREA_HEX",
                  MapLogSource.MAP_EDIT,
                  "hex",
                  hex,
                  "field",
                  key.wireKey(),
                  "derived",
                  nextLand.totalMilliMu(type)));
    }
    return MapChangeSet.between(base, base.withHexes(nextHexes));
  }

  private static long addExact(long before, long delta, HexCoord hex, HexAreaKey key) {
    try {
      return Math.addExact(before, delta);
    } catch (ArithmeticException e) {
      debugRejected("增量溢出", "hex", hex, "field", key.wireKey(), "before", before, "delta", delta);
      throw new IllegalArgumentException(
          "增量结果超出 long: " + key.wireKey() + " 现值 " + before + "，增量 " + delta, e);
    }
  }
}
