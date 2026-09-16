package io.mosire.simos.map;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一座城市。
 *
 * <p>★ GSimulator 的 {@code City} 是 **4 组件**（{@code q}/{@code r}/{@code name}/{@code description}，实测
 * {@code MapData:327}）—— 它**连区域归属都没有**：城市与省份之间不存在任何数据结构上的联系，落到哪一格纯靠坐标。此处补三样： {@link #id()}（类型化身份，铁律
 * 1）、{@link #region()}（归属）与 {@link #props()}（可扩展属性）。
 *
 * <p>★ {@code region} **允许为 {@code null}** = "这座城不在任何区域内"。这不是缺失，是合法状态：区域是画出来的，格可以没有归属 ——{@code
 * RegionIndex.regionOf} 对无归属的格正是返回 {@code null}，两处同口径。**其余四个字段都不许 null**（{@code name} 空白即抛，与 {@code
 * Region}/{@code PathwayGroup} 同族）：它们没有"空"的语义。
 *
 * <p>★ {@code props} **保序不可变**，且键值都不得为 null（老仓那份用的 {@code Map.copyOf} 本就拒 null，此处只把**顺序**这一项换掉
 * ——copyOf 不保序）。顺序不是装饰：props 要落盘、要进变更集，迭代序漂移会让同一份数据产生不同字节。
 *
 * @param id 稳定身份；不得为 null
 * @param name 显示名；**空白即抛**
 * @param at 所在格；不得为 null
 * @param region 所属区域；**可为 null**（无归属）
 * @param props 城市自己的属性表；保序不可变
 */
public record City(
    CityId id, String name, HexCoord at, RegionId region, Map<String, Object> props) {

  public City {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (props == null) {
      throw new IllegalArgumentException("props 不得为 null");
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : props.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("props 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    props = Collections.unmodifiableMap(copy);
  }
}
