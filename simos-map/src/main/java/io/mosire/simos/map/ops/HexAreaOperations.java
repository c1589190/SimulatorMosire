package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreaKey;
import io.mosire.simos.map.HexAreas;
import io.mosire.simos.map.HexCell;
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
 * 可用地面积属性表的写操作面（2026-10-12 批次 2；命令 {@code map.SetHexArea}）：<b>一次改一格一个字段</b>。
 *
 * <p>★ <b>每个操作都是纯函数</b>（只读 {@code base}，产出走 {@link MapChangeSet}）；变更集<b>唯一</b>的生产路径是 {@link
 * MapChangeSet#between(GameMap, GameMap)} —— 不做"操作直接拼增量变更集"的第二条路径（两条路径必然分叉，正是本项目最贵的教训形态）。
 *
 * <p>★ <b>两条 op 都在这一个操作里</b>（用户原话「工具两者都要」= 增量与全量赋值都要；派单书 §③.7 允许"同一命令用显式 op 表达"）：
 *
 * <ul>
 *   <li>{@link Op#SET}（全量赋值）：{@code value} 是<b>新的绝对面积</b>（毫亩，≥ 0）；
 *   <li>{@link Op#ADD}（增量）：{@code value} 是<b>有符号增量</b>（±毫亩）。
 * </ul>
 *
 * <p>★ <b>为什么合成一条命令而不是两条</b>：两者改的是同一个状态位（某格某个字段的值）、走同一套载荷解析、同一套目标声明、同一条日志， 差别只在"新值怎么算"这一步；拆成两条命令会让
 * {@code catalog}/可嵌令面/工具面各多一个近乎重复的条目，而<b>领域语义一个字都不多</b>。 增量与全量都是显式 {@code op}，不存在"靠字段缺省猜意图"的形态。
 *
 * <p>★★ <b>增量的基数是"读出来的值"</b>（{@link HexAreas#resolved}）：没赋过值的格上 {@code add} 从<b>地形默认值</b>起算，
 * 与读面显示的数是同一个 —— 否则"读出来 3,100,000、加 100 变成 100"这种自相矛盾会当场出现。
 *
 * <p>★ <b>结果不得为负</b>（面积没有负数）：{@code set} 的负值、{@code add} 之后落到负数的，都在这里具名拒（命令边界折成 {@code Rejected}，零
 * revision）；{@link HexCell} 的构造期守卫是第二道（同一不变量的兜底，不是重复实现）。
 */
public final class HexAreaOperations {

  /**
   * 写操作（载荷 {@code op} 字段的取值，**唯一拼写点**）：
   *
   * <ul>
   *   <li>{@link #SET}（{@code set}）：全量赋值 —— {@code value} 是新的绝对面积（毫亩，≥ 0）；
   *   <li>{@link #ADD}（{@code add}）：增量 —— {@code value} 是有符号增量（±毫亩），基数是当前读出值。
   * </ul>
   */
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

    /** 线格式 → op；非法一律抛（不兜底、不忽略大小写）。 */
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

  /** 校验拒绝的 DEBUG 明细（判据/为什么）；事件名固定 {@code MAP_HEX_AREA_EDIT_BLOCKED}。日志只读，不参与任何分支或状态。 */
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
   * 改一格的一个字段，返回变更集。
   *
   * <p>校验次序（都在算出任何结果之前）：{@code base}/{@code hex}/{@code key}/{@code op} 判空 → 该格在图上 → {@code set}
   * 的值非负 → 算出结果 → 结果非负。任一不满足 ⇒ {@link IllegalArgumentException}，命令边界折成 {@code Rejected} （零
   * revision）。
   *
   * @param base 现图（只读；该格现值与它的地形取自它）
   * @param hex 要改的格；必须在 {@code base.hexes()} 里
   * @param key 要改的字段（内置 {@code urban}/{@code rural} 或自定义字段）
   * @param op 全量赋值 / 增量
   * @param value {@code set} ⇒ 新绝对面积（毫亩，≥ 0）；{@code add} ⇒ 有符号增量（±毫亩）
   * @return {@code hexes} 可能非 {@code Unchanged} 的变更集；新值与原值相同 ⇒ 8 个组件全 {@code Unchanged}
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
    Map<HexCoord, HexCell> nextHexes = new LinkedHashMap<>(base.hexes());
    nextHexes.put(hex, cell.withArea(key, after));
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
                  "explicit",
                  after));
    }
    // ★ withHexes 的键集不变（只换值）⇒ 不触发分割不变式；between 逐组件比较，未动的组件恒 Unchanged。
    return MapChangeSet.between(base, base.withHexes(nextHexes));
  }

  /** 增量加法：溢出 long 是**坏输入**（不可能的面积），折成具名拒绝而不是让 {@code ArithmeticException} 逃出命令边界。 */
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
