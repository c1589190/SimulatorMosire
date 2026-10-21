package io.mosire.simos.map;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HexLand} 组件树的读时派生 + 创世初值。
 *
 * <p>这里是“单 hex 土地读出来是什么”的唯一实现：{@code areas} 是兼容旧标量读口的派生 totals（urban + arable）， {@code
 * landTypes}/{@code components} 是新三档树视图。没有任何第二份 flat 面积持久状态。
 *
 * <p>{@code areas} 的默认语义：某 type 在 {@link HexLand#byType()} 里<b>存在</b> ⇒ 读它 components 的数量和（空 area 即
 * 0）； type 不存在 ⇒ urban 0、arable 按地形默认、自定义 0。这样旧 setter 把 arable 清到 0 时仍可读出 0，而创世前的 arable 仍显示地形默认。
 */
public final class HexAreas {

  /** 来源标记：旧 flat 面积表已不存在，所有 totals 都从 component 树派生。 */
  public static final String SOURCE_DERIVED = "derived";

  private HexAreas() {}

  /** 该 hex 的 arable 总量（毫亩）：有 area 读 component 和，无 area 按地形默认。 */
  public static long arableMilliMu(HexCell cell, String terrain) {
    return resolved(cell, HexAreaKey.Builtin.ARABLE, terrain);
  }

  /** 该 hex 的 urban 总量（毫亩）：有 area 读 component 和，无 area 为 0。 */
  public static long urbanMilliMu(HexCell cell, String terrain) {
    return resolved(cell, HexAreaKey.Builtin.URBAN, terrain);
  }

  /**
   * 该 hex 的全部土地类型总量（毫亩）。内置 arable/urban 始终在场（缺 area 时按上面的默认语义），自定义类型取自 {@link
   * HexLand#byType()}。返回不可变、顺序确定的视图。
   */
  public static Map<LandTypeId, Long> landTotals(HexCell cell, String terrain) {
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(terrain, "terrain");
    Map<LandTypeId, Long> totals = new LinkedHashMap<>();
    totals.put(LandTypeId.ARABLE, arableMilliMu(cell, terrain));
    totals.put(LandTypeId.URBAN, urbanMilliMu(cell, terrain));
    for (Map.Entry<LandTypeId, LandTypeArea> entry : cell.land().byType().entrySet()) {
      LandTypeId type = entry.getKey();
      if (type.equals(LandTypeId.ARABLE) || type.equals(LandTypeId.URBAN)) {
        continue;
      }
      totals.put(type, entry.getValue().totalMilliMu());
    }
    return Collections.unmodifiableMap(totals);
  }

  /** 解析后的标量值（毫亩）。 */
  public static long resolved(HexCell cell, HexAreaKey key, String terrain) {
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(terrain, "terrain");
    LandTypeId type = HexAreaKey.landTypeOf(key);
    LandTypeArea area = cell.land().byType().get(type);
    if (area != null) {
      return area.totalMilliMu();
    }
    return defaultOf(key, terrain);
  }

  /** 该标量键在“type 尚未出现在土地树里”时的默认值。 */
  public static long defaultOf(HexAreaKey key, String terrain) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(terrain, "terrain");
    if (key instanceof HexAreaKey.Builtin builtin) {
      return switch (builtin) {
        case URBAN -> 0L;
        case ARABLE -> TerrainCatalog.defaultUsableAreaMilliMu(terrain);
      };
    }
    return 0L;
  }

  /** 来源标记：当前所有标量 totals 都是组件树派生值。 */
  public static String sourceOf(HexCell cell, HexAreaKey key) {
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(key, "key");
    return SOURCE_DERIVED;
  }

  /**
   * 把 {@code landTypes} / {@code components} / 派生 {@code areas} / {@code areaSources} 写进视图。
   *
   * <p>所有迭代序来自 {@link HexLand} / {@link LandTypeArea} / {@link LandComponent} 的规范序；不依赖 HashMap 迭代序。
   */
  public static void putInto(Map<String, Object> view, HexCell cell, String terrain) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(cell, "cell");
    Objects.requireNonNull(terrain, "terrain");

    Map<String, Object> landTypes = new LinkedHashMap<>();
    List<Map<String, Object>> components = new ArrayList<>();
    for (Map.Entry<LandTypeId, LandTypeArea> entry : cell.land().byType().entrySet()) {
      LandTypeId type = entry.getKey();
      LandTypeArea area = entry.getValue();
      List<Map<String, Object>> areaComponents = new ArrayList<>(area.components().size());
      for (LandComponent component : area.components()) {
        Map<String, Object> componentView = componentView(type, component);
        areaComponents.add(componentView);
        components.add(componentView);
      }
      Map<String, Object> typeView = new LinkedHashMap<>();
      typeView.put("totalMilliMu", area.totalMilliMu());
      typeView.put("nextComponentOrdinal", area.nextComponentOrdinal());
      typeView.put("components", areaComponents);
      landTypes.put(type.wireValue(), typeView);
    }

    Map<String, Object> areas = new LinkedHashMap<>();
    Map<String, Object> sources = new LinkedHashMap<>();
    for (HexAreaKey.Builtin key : HexAreaKey.Builtin.values()) {
      areas.put(key.wireKey(), resolved(cell, key, terrain));
      sources.put(key.wireKey(), SOURCE_DERIVED);
    }
    for (LandTypeId type : cell.land().byType().keySet()) {
      if (type.equals(LandTypeId.ARABLE) || type.equals(LandTypeId.URBAN)) {
        continue;
      }
      areas.put(type.wireValue(), cell.land().totalMilliMu(type));
      sources.put(type.wireValue(), SOURCE_DERIVED);
    }
    view.put("landTypes", landTypes);
    view.put("components", components);
    view.put("areas", areas);
    view.put("areaSources", sources);
  }

  /**
   * 创世初始化：按地形给 arable 生成一个 component（地形默认面积为 0 时不生成）。
   *
   * <p>★ 旧签名没有 {@link HexCoord}，为保持 app main 的既有调用面不炸，使用占位的 {@code 0_0} 生成 component id。B2 的 app
   * seeder 应改用 {@link #initialCell(HexCoord, double, String)}，用真实格坐标生成全局可识别 id。
   */
  public static HexCell initialCell(double height, String terrain) {
    return initialCell(new HexCoord(0, 0), height, terrain);
  }

  /** 创世初始化：按地形给 arable 生成一个带稳定 component id 的 component。 */
  public static HexCell initialCell(HexCoord hex, double height, String terrain) {
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(terrain, "terrain");
    long arable = TerrainCatalog.defaultUsableAreaMilliMu(terrain);
    if (arable == 0L) {
      return new HexCell(height);
    }
    return new HexCell(
        height, LandComponentOperations.seedSingle(hex, LandTypeId.ARABLE, arable, Map.of()));
  }

  private static Map<String, Object> componentView(LandTypeId type, LandComponent component) {
    Map<String, Object> componentView = new LinkedHashMap<>();
    componentView.put("landType", type.wireValue());
    componentView.put("id", component.id().value());
    componentView.put("quantityMilliMu", component.quantityMilliMu());
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (Map.Entry<LandAttributeKey, LandAttributeValue> attribute :
        component.attributes().entrySet()) {
      Map<String, Object> valueView = new LinkedHashMap<>();
      valueView.put("kind", attribute.getValue().kind().name());
      valueView.put("canonicalValue", attribute.getValue().canonicalValue());
      attributes.put(attribute.getKey().wireValue(), valueView);
    }
    componentView.put("attributes", attributes);
    return componentView;
  }
}
