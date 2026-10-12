package io.mosire.simos.map;

import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>可用地面积属性表的读时兜底 + 创世初值</b>（2026-10-12 批次 2）：<b>全仓唯一</b>的"没赋值时读什么"实现， 供 MCP 读工具（{@code
 * simos.map.hex}）与 GUI（{@code ApiViews.mapHex}）**共用同一个函数**（仓内硬纪律：GUI 与 MCP 不许各写一份同形视图）。
 *
 * <p>用户原话（逐字）：「这个可用地面积如果没有，那么读的时候就走默认，也就是单个地形的默认可用面积，<b>以乡村类型显示</b>」。 落地成三条：
 *
 * <ol>
 *   <li>某个键<b>有显式值</b> ⇒ 读显式值，来源标 {@link #SOURCE_EXPLICIT}；
 *   <li>某个键<b>没有显式值</b> ⇒ 读<b>该地形的默认可用面积</b>，来源标 {@link #SOURCE_TERRAIN_DEFAULT}；其中 {@link
 *       HexAreaKey.Builtin#RURAL} 的地形默认就是 {@link TerrainCatalog#defaultUsableAreaMilliMu(String)}
 *       （"以乡村类型显示"= 读面里 {@code areas.rural} 出现的那个数）；
 *   <li>{@link HexAreaKey.Builtin#URBAN} 的默认是 <b>0</b> —— 城市面积没有任何地形来源（创世也不给它初值）， "没赋值"对它就是"城市面积为
 *       0"。
 * </ol>
 *
 * <p>★ <b>输出形状</b>（两个字段，插入序固定 ⇒ 同一 revision 两次响应逐字节相同）：{@code areas} = 解析后的值（毫亩）， {@code
 * areaSources} = 逐键的来源标记。内置两项<b>恒在</b>（这就是"已有默认字段"），{@link HexAreaKey.Custom}（自定义字段）
 * <b>只在有显式值时才出现</b>（它没有地形默认可兜）。字段名取 {@link HexAreaKey#wireKey()}（{@code urban} / {@code rural} /
 * {@code custom:<名>}）⇒ 与载荷、落盘同一个拼写点。
 *
 * <p>★ <b>本类不做任何写</b>：改值走 {@code map.SetHexArea} 命令（{@code
 * ops.HexAreaOperations}）。这里只回答"读出来是多少、它从哪来"。
 */
public final class HexAreas {

  /** 来源标记：值来自该格**显式存的**编辑值。 */
  public static final String SOURCE_EXPLICIT = "explicit";

  /** 来源标记：该键没有显式值，值是该**地形的默认可用面积**。 */
  public static final String SOURCE_TERRAIN_DEFAULT = "terrain-default";

  private HexAreas() {}

  /**
   * 解析后的值（毫亩）：显式值优先，缺席 ⇒ {@link #defaultOf(HexAreaKey, String)}。
   *
   * <p>★ 写口（{@code add} 增量）也用这个函数取"改之前的现值" ⇒ <b>"读出来是多少"与"在多少的基础上加减"是同一个数</b> （否则在没赋过值的格上加 100 会从 0
   * 起跳，与读面显示的地形默认值自相矛盾）。
   */
  public static long resolved(HexCell cell, HexAreaKey key, String terrain) {
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(terrain, "terrain");
    Long explicit = cell.areas().get(key);
    return explicit != null ? explicit : defaultOf(key, terrain);
  }

  /**
   * 该键在该地形上的<b>默认值</b>（毫亩）：{@link HexAreaKey.Builtin#RURAL} ⇒ 该地形的默认可用面积； {@link
   * HexAreaKey.Builtin#URBAN} 与一切自定义字段 ⇒ {@code 0}（它们没有地形来源）。
   *
   * <p>★ 地形 key 的合法性由 {@link TerrainCatalog} 自己 fail-closed（词表外抛），本类不重复判。
   */
  public static long defaultOf(HexAreaKey key, String terrain) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(terrain, "terrain");
    if (key instanceof HexAreaKey.Builtin builtin) {
      return switch (builtin) {
        case URBAN -> 0L;
        case RURAL -> TerrainCatalog.defaultUsableAreaMilliMu(terrain);
      };
    }
    return 0L;
  }

  /**
   * 某个键的来源标记（{@link #SOURCE_EXPLICIT} / {@link #SOURCE_TERRAIN_DEFAULT}）。
   *
   * <p>★ 只对 {@link #putInto} 会输出的键有定义（内置两项恒有；自定义字段只在有显式值时被输出 ⇒ 恒为 {@code explicit}）。
   */
  public static String sourceOf(HexCell cell, HexAreaKey key) {
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(key, "key");
    return cell.areas().containsKey(key) ? SOURCE_EXPLICIT : SOURCE_TERRAIN_DEFAULT;
  }

  /**
   * 把 {@code areas} + {@code areaSources} 两个字段写进视图（<b>唯一实现</b>，GUI 与 MCP 共用）。
   *
   * <p>★ 内置两项按 {@link HexAreaKey.Builtin} 的声明序输出（{@code urban} → {@code rural}），自定义字段按该格自己的插入序 ——
   * 全是 {@code LinkedHashMap} 的插入序，<b>不依赖任何 {@code HashMap} 迭代序</b>（GUI 有一条"同一 revision
   * 两次响应逐字节相同"的既有断言）。
   *
   * <p>★ 视图的 map 由调用方给（工具面/接口面各造自己的那个），本类只往里放这两个字段 ⇒ 两处调用点的形状不可能漂移。
   *
   * @param view 目标视图（会被就地修改）
   * @param cell 该格
   * @param terrain 该格地形 key（调用方从状态里取，本类不查词表）
   */
  public static void putInto(Map<String, Object> view, HexCell cell, String terrain) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(terrain, "terrain");
    Map<String, Object> areas = new LinkedHashMap<>();
    Map<String, Object> sources = new LinkedHashMap<>();
    for (HexAreaKey.Builtin key : HexAreaKey.Builtin.values()) {
      areas.put(key.wireKey(), resolved(cell, key, terrain));
      sources.put(key.wireKey(), sourceOf(cell, key));
    }
    for (Map.Entry<HexAreaKey, Long> entry : cell.areas().entrySet()) {
      if (entry.getKey() instanceof HexAreaKey.Custom) {
        areas.put(entry.getKey().wireKey(), entry.getValue());
        sources.put(entry.getKey().wireKey(), SOURCE_EXPLICIT);
      }
    }
    view.put("areas", areas);
    view.put("areaSources", sources);
  }

  /**
   * ★ <b>创世初始化</b>：按地形给 {@link HexAreaKey.Builtin#RURAL} 一个固定初值（= 该地形的默认可用面积），
   * 城市面积不给初值（用户原话：「根据地形在初始化世界的时候先给乡村面积一个固定值，然后再根据需求自己调整城市化面积」）。
   *
   * <p>★ <b>默认值为 0 的地形不写显式项</b>（水体与不毛之地）：写 0 与不写，读出来都是 0（来源标不同而已）， 不写可以让落盘少一大堆无信息量的 {@code
   * "rural":0}（半径 80 的图里水体占多数）。 ⇒ <b>"初值 = 0" 的格仍然走读时兜底</b>， 这条兜底路径因此在真世界里也是活的。
   *
   * <p>★ 谁调用它（"写在哪一层"）：map 的 {@code MapGenerator}（生成图）与 app 的三个世界生成器 （{@code SmallWorld}/{@code
   * ThreePowersWorld}/{@code CorridorWorld}）—— 随仓 {@code worlds/*.json} 本批<b>不动</b>
   * （用户裁定"随仓世界暂不动"；它的旧形状每次加载都走 {@code MapCodec.migrate}，新字段因此恒缺省，读面照常给出地形默认值）。
   *
   * @param height 该格海拔
   * @param terrain 该格地形 key（必须在词表内；词表外由 {@link TerrainCatalog#of} 抛）
   * @return 带乡村面积初值的格；地形默认值为 0 时返回只有海拔的格
   */
  public static HexCell initialCell(double height, String terrain) {
    Objects.requireNonNull(terrain, "terrain");
    long rural = TerrainCatalog.defaultUsableAreaMilliMu(terrain);
    if (rural == 0L) {
      return new HexCell(height);
    }
    return new HexCell(height, Map.of(HexAreaKey.Builtin.RURAL, rural));
  }
}
