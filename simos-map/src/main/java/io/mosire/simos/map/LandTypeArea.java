package io.mosire.simos.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单 hex 某土地类型的全部面积成分。组件按 component id 规范升序冻结，{@code nextComponentOrdinal} 随状态落盘。
 *
 * <p>不变量：component id 在同一个 type area 内唯一；每个 id 形如 {@code <type>@<hex>:<ordinal>}；ordinal 非负；{@code
 * nextComponentOrdinal > max(现有 ordinal)}。空 area 也保留 next 序号（删除 component 不回收序号）。
 *
 * @param type 土地类型
 * @param components 面积成分（保序不可变）
 * @param nextComponentOrdinal 下一个可分配的组件序号
 */
public record LandTypeArea(
    LandTypeId type, List<LandComponent> components, long nextComponentOrdinal) {

  public LandTypeArea {
    if (type == null) {
      throw new IllegalArgumentException("LandTypeArea.type 不得为 null");
    }
    if (components == null) {
      throw new IllegalArgumentException("LandTypeArea.components 不得为 null");
    }
    long maxOrdinal = -1L;
    Set<LandComponentId> ids = new HashSet<>();
    for (LandComponent component : components) {
      if (component == null) {
        throw new IllegalArgumentException("LandTypeArea.components 不得含 null");
      }
      if (!ids.add(component.id())) {
        throw new IllegalArgumentException("同一土地类型下 component id 不得重复: " + component.id());
      }
      maxOrdinal = Math.max(maxOrdinal, ordinalOf(type, component.id()));
    }
    if (nextComponentOrdinal <= maxOrdinal) {
      throw new IllegalArgumentException(
          "nextComponentOrdinal 必须大于现有最大 ordinal: next="
              + nextComponentOrdinal
              + " max="
              + maxOrdinal);
    }
    if (nextComponentOrdinal < 1L) {
      throw new IllegalArgumentException("nextComponentOrdinal 必须 >= 1: " + nextComponentOrdinal);
    }
    List<LandComponent> sorted = new ArrayList<>(components);
    sorted.sort(Comparator.comparing(component -> component.id().value()));
    components = Collections.unmodifiableList(sorted);
  }

  /** 该类型总量（毫亩）= 所有 component 数量之和。 */
  public long totalMilliMu() {
    long total = 0L;
    for (LandComponent component : components) {
      total = Math.addExact(total, component.quantityMilliMu());
    }
    return total;
  }

  /**
   * 解析 component id 的 ordinal，并校验它属于本 land type。
   *
   * <p>格式拼写点只在这里：{@code <type>@<hex>:<ordinal>}，最后一个冒号后是十进制 long。
   */
  public static long ordinalOf(LandTypeId type, LandComponentId id) {
    String text = id.value();
    String prefix = type.wireValue() + "@";
    if (!text.startsWith(prefix)) {
      throw new IllegalArgumentException("component id 的土地类型前缀不符: id=" + text + "，期望前缀=" + prefix);
    }
    int colon = text.lastIndexOf(':');
    if (colon <= prefix.length() || colon == text.length() - 1) {
      throw new IllegalArgumentException("component id 缺少规范 ordinal: " + text);
    }
    long ordinal;
    try {
      ordinal = Long.parseLong(text.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("component id 的 ordinal 不是 long: " + text, e);
    }
    if (ordinal < 0L) {
      throw new IllegalArgumentException("component id 的 ordinal 不得为负: " + text);
    }
    return ordinal;
  }
}
