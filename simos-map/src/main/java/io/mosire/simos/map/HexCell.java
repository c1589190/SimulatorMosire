package io.mosire.simos.map;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一个六边形格<b>此时此地的海拔</b> + <b>可用地面积属性表</b>（2026-10-12 批次 2 新增，用户裁定「形状按 A」= 塞进 {@code HexCell}）。
 *
 * <p>★ <b>本类型只存"逐格才有权威"的东西</b>（原类注「只剩 {@code height}」的本意）：地形早已升为 {@link
 * io.mosire.simos.map.block.TerrainBlock} 权威块（粗粒度、hex 间通用、仅 map 模块维护），取某格地形走 {@link
 * GameMap#terrainAt(io.mosire.simos.map.hex.HexCoord)}，<b>不要</b>再往本类型上加回 terrain；连通性的主存储是 {@code
 * GameMap.edges}（老仓的 {@code edgeTags}/{@code riverMask} 是第二份存储，Java 侧只读不写、前端一存就把所有边的 props 抹平）。⇒
 * 判据是<b>"别重复存别处已有权威的东西"</b>，而不是"字段越少越好"：海拔不是地形的函数（{@code RegionRandomizer} 改地形保留
 * height），可用地面积是逐格的编辑值、别处没有权威，故两者都逐格存。
 *
 * <p>★ {@code areas} 是<b>显式值表</b>：某个键不在表里 = "这一格没有显式赋值"，<b>不是</b> 0 的同义词 —— 读的时候按地形兜底（用户原话
 * 「这个可用地面积如果没有，那么读的时候就走默认，也就是单个地形的默认可用面积，以乡村类型显示」），兜底规则与读面输出在 {@link HexAreas}（唯一实现）。键 {@link
 * HexAreaKey}：内置两项（城市面积 / 乡村面积，enum 标签）本轮恒在，{@link HexAreaKey.Custom}（String）为后续自定义字段留口。<b>量纲 =
 * 毫亩</b>（{@code long}），与 economy 同源；值不得为负。
 *
 * <p>★ <b>保序不可变、禁 {@code Map.copyOf} 族</b>（与 {@link GameMap} 的 9 个 Map 同形制）：迭代序 = 插入序是落盘序的前提，
 * {@code Map.copyOf} 会按散列打乱它、同一份数据因此产出不同字节。冻结那一步写在构造器体里（<b>不抽私有辅助方法</b>：抽走之后 SpotBugs
 * 只认它看得见的包装调用，会给一条 {@code EI_EXPOSE_REP}——批次 1.8 的教训）。
 *
 * @param height 海拔，<b>归一到 {@code [0,1]}</b>
 * @param areas 可用地面积<b>显式值</b>表（毫亩，保序不可变）；缺席的键由读侧按地形兜底，见 {@link HexAreas}
 */
public record HexCell(double height, Map<HexAreaKey, Long> areas) {

  public HexCell {
    // ★ **必须排在范围校验之前**：NaN 与 [0,1] 的比较全是 false，调换顺序会让 NaN 漏过去。
    if (!Double.isFinite(height)) {
      throw new IllegalArgumentException("height 必须是有限数: " + height);
    }
    if (height < 0.0 || height > 1.0) {
      throw new IllegalArgumentException("height 必须在 [0,1]: " + height);
    }
    // ★ 缺席 = 没有显式值（老 JSON 只有 {"height":…} ⇒ Jackson 传 null；Java 侧 new HexCell(h, null) 同待遇）。
    //   这一条不是"旧档兼容位"：它是本类型的**缺省语义**（空表 ⇒ 读侧按地形兜底），与 §一.11 的"不写兼容层"不冲突。
    if (areas == null) {
      areas = Map.of();
    }
    // ★ 逐键值查 null 必须在拷贝之前：LinkedHashMap 的拷贝会先 NPE，报不出是哪个键。
    for (Map.Entry<HexAreaKey, Long> entry : areas.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("areas 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "可用地面积不得为负（毫亩）: " + entry.getKey().wireKey() + "=" + entry.getValue());
      }
    }
    // ★ 冻结写在赋值处，不藏进 helper：SpotBugs 只认它看得见的 Collections.unmodifiableMap。
    areas = Collections.unmodifiableMap(new LinkedHashMap<>(areas));
  }

  /**
   * <b>便捷构造器</b>：只给海拔 ⇒ 属性表为空（= 全部字段都走地形兜底）。
   *
   * <p>★ 它的存在让仓内 <b>240 处</b> {@code new HexCell(...)}（main 7 / test 233）<b>零编译改动</b>——与 {@code
   * EconomyData}/{@code CurrencyDef} 等 20+ 条既有形制同款；同时也是"这一格还没有任何编辑值"的规范写法。
   */
  public HexCell(double height) {
    this(height, Map.of());
  }

  /**
   * 新增/覆盖一个字段（返回<b>新格</b>；本类型不可变）。值越界（负）由构造期守卫拒。
   *
   * <p>★ 写口只用这一个方法改属性表 ⇒ "怎么改一格的一个字段"在 map 模块里只有一个拼写点。
   */
  public HexCell withArea(HexAreaKey key, long milliMu) {
    Objects.requireNonNull(key, "key");
    Map<HexAreaKey, Long> next = new LinkedHashMap<>(areas);
    next.put(key, milliMu);
    return new HexCell(height, next);
  }

  /**
   * 只换海拔，<b>属性表原样保留</b>。
   *
   * <p>★★ 这一条是 2026-10-12 批次 2 的<b>必修语义点</b>（派单书 §③.4）：{@code map.SetTerrain} 涂地形会重写该格的高度，
   * 而重写高度<b>不得</b>顺手把用户编辑过的面积抹平。改前那里写的是 {@code new HexCell(paintHeight)}（单字段时代的写法）， 换成 {@code
   * existing.withHeight(...)} 之后属性表逐条带过去 —— 见 {@code TerrainOperations} 的调用点。
   */
  public HexCell withHeight(double newHeight) {
    return new HexCell(newHeight, areas);
  }
}
