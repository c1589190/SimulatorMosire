package io.mosire.simos.map.pathway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 边上的标注：这条边属于**哪条线**（{@link Pathway} 的实例）、在那条线上带哪些属性。
 *
 * <p>★ 这就是 spec §5.3 里取代第二份存储的那张表（{@code Map<EdgeRef, EdgeTags>} 是**唯一主存储**）。老仓的两份是 {@code
 * HexCell.edgeTags} 与遗留的 {@code riverMask}，而 Java 侧**只读不写**、只有前端写，两份之间又没有转换代码 ⇒ 前端一存就把所有边的 props
 * 抹平。本类型只此一份，抹平无从发生。
 *
 * <p>★ 外层 key 是 **pathwayId 的裸值**（{@link PathwayId#toString()}），内层是"属性名 → 值"。
 *
 * <p>★ **为什么不是组名**：老仓 {@code HexCell.edgeTags} 存的是"方向 → 组名表"（如 {@code ['river']}），把三个概念挤进了一个词 ——
 * 组定义、线的实例、边上的标注。spec §5.4 把三层拆开（{@code PathwayGroup} / {@code Pathway} / 本类型），本表属**线的实例**那一层。
 * 组名还做不到一件事：同一条边上可以同时有"某条河"与"某条路"的标注，而 {@code ['river']} 只能表达"有条河"。故这里取实例身份， **不取** {@code
 * Pathway#groupId}。
 *
 * <p>★ **保序**（{@link LinkedHashMap}）**且不可变**，外层与内层都是：浅拷贝只冻结外层，调用方照样能顺着内层 map 改掉 props ——
 * 那正是上面那个病的形态（第二条可写路径）。顺序本身也不是装饰：props 要落盘、要进变更集，迭代序漂移会让 同一份数据产生不同字节。
 */
public record EdgeTags(Map<String, Map<String, Object>> byPathway) {

  public EdgeTags {
    if (byPathway == null) {
      throw new IllegalArgumentException("byPathway 不得为 null");
    }
    Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : byPathway.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "byPathway 的 pathwayId 与内层 props 都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    byPathway = Collections.unmodifiableMap(copy);
  }
}
