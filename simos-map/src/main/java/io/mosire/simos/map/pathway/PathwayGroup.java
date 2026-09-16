package io.mosire.simos.map.pathway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线的**组定义**（river / road …）。字段照老仓 {@code MapData.PathwayGroup}（实测 {@code :451}）。
 *
 * <p>★ **两处口径与老仓相反**（老仓是静默兜底，本项目禁）：老仓对 {@code id}/{@code name}/{@code color}/{@code
 * description}/{@code properties} 逐项做 {@code if (x == null) x = …} 的**静默填空**（{@code id} 填 {@code
 * ""}、 {@code color} 填 {@code "#808080"}、{@code properties} 填 {@code Map.of()}）——
 * 一个"没名字的组"因此会变成"名字为空串的组"， 事后谁也分不出来。此处**空白即抛**（与 {@link PathwayId}、{@code TerrainType}、{@code
 * RegionId} 同族）。
 *
 * <p>★ {@code color} 按 {@code #RRGGBB} 校验，与 {@code TerrainType} 同口径（老仓的两个默认色 {@code #3295D2}/{@code
 * #8B7355} 都合此形）。{@code description} 与 {@code TerrainType} 一样**有意不设校验**：它只作文档用途，既不参与判据也不被解引用。
 *
 * <p>★ {@code properties} 保序不可变，且键值都不得为 null —— 老仓那份用的 {@code Map.copyOf} 本就拒 null，此处只把
 * **顺序**这一项换掉（copyOf 不保序）。是否要一份 {@code defaults()}（river {@code #3295D2} / road {@code #8B7355}） 由
 * Task 6 的 {@code GenerationSpec} 决定，本任务只交付类型。
 */
public record PathwayGroup(
    String id,
    String name,
    String color,
    String description,
    boolean visible,
    Map<String, PropertyDef> properties) {

  public PathwayGroup {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
      throw new IllegalArgumentException("color 必须是 #RRGGBB 形式: " + color);
    }
    if (properties == null) {
      throw new IllegalArgumentException("properties 不得为 null");
    }
    Map<String, PropertyDef> copy = new LinkedHashMap<>();
    for (Map.Entry<String, PropertyDef> entry : properties.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("properties 的键与定义都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    properties = Collections.unmodifiableMap(copy);
  }

  /**
   * 组属性的一条定义。**嵌套 record** —— Task 7 的反射枚举要能穿透它，不许为省事把 {@code properties} 删掉。
   *
   * <p>★ {@code type} 是判别值类型的那个字段（老仓注释：{@code "int"}/{@code "float"}/{@code "bool"}/{@code
   * "string"}），老仓把 {@code null} 静默填成 {@code "string"} ⇒ 此处**空白即抛**。**不**按那四个词做白名单校验：
   * 老仓只在注释里列过它们，没有一处代码是判据，照抄成校验等于**替将来的词表做决定**。
   *
   * <p>★ {@code defaultValue} 与 {@code description} **不设校验**：前者的合法性完全由 {@code type} 定义（本 record 不解释
   * {@code type}，也就无从判它），后者只作文档用途、null 不破坏往返。
   */
  public record PropertyDef(String type, Object defaultValue, String description) {

    public PropertyDef {
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("type 不得为空白");
      }
    }
  }
}
