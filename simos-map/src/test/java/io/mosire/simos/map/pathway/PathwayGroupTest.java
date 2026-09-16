package io.mosire.simos.map.pathway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.pathway.PathwayGroup.PropertyDef;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 线的组定义（river / road …）。★ 本文件不在 Task 4 派单书的 Files 段里 —— R-48-c 要求把老仓的**静默填空改成抛**，
 * 而"护栏必须自证"要求每条守卫有一个**故意违规**的用例；没有归属的守卫等于装饰，故补此文件。
 */
class PathwayGroupTest {

  private static PathwayGroup river() {
    return new PathwayGroup(
        "river", "河流", "#3295D2", "天然水系", true, Map.of("width", new PropertyDef("int", 2, "河宽")));
  }

  /** 字段**照老仓**（实测 {@code MapData.java:451}），声明序即 Task 6 的 {@code FieldDelta} 序。 */
  @Test
  void fieldsAreTheOldRepoSet() {
    assertThat(PathwayGroup.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("id", "name", "color", "description", "visible", "properties");
    assertThat(river().visible()).isTrue();
  }

  /** ★ 老仓那五行 {@code if (x == null) x = …} 的静默填空，本项目一律改成抛。 */
  @Test
  void rejectsBlankIdNameAndMalformedColor() {
    for (String blank : new String[] {"", "   "}) {
      assertThatThrownBy(() -> new PathwayGroup(blank, "河流", "#3295D2", null, true, Map.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("id");
      assertThatThrownBy(() -> new PathwayGroup("river", blank, "#3295D2", null, true, Map.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name");
    }
    assertThatThrownBy(() -> new PathwayGroup(null, "河流", "#3295D2", null, true, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    // color：老仓静默填 "#808080"，这里要求合 #RRGGBB 形（与 TerrainType 同口径）
    for (String bad : new String[] {null, "red", "#FFF", "#80808O", "808080"}) {
      assertThatThrownBy(() -> new PathwayGroup("river", "河流", bad, null, true, Map.of()))
          .as("颜色 %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("color");
    }
    // description 有意不设校验（与 TerrainType 同口径）
    assertThat(new PathwayGroup("river", "河流", "#3295D2", null, true, Map.of()).description())
        .isNull();
  }

  @Test
  void propertiesIsOrderedAndImmutable() {
    Map<String, PropertyDef> source = new LinkedHashMap<>();
    source.put("width", new PropertyDef("int", 2, "河宽"));
    source.put("depth", new PropertyDef("float", 0.5, "水深"));
    source.put("bridge", new PropertyDef("bool", false, "有无桥"));
    PathwayGroup group = new PathwayGroup("river", "河流", "#3295D2", "天然水系", true, source);

    source.put("dam", new PropertyDef("bool", false, "有无坝"));

    assertThat(group.properties().keySet()).containsExactly("width", "depth", "bridge");
    assertThat(group.properties()).isUnmodifiable();
    assertThatThrownBy(() -> group.properties().put("x", new PropertyDef("int", 1, null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNullPropertiesAndNullEntries() {
    assertThatThrownBy(() -> new PathwayGroup("river", "河流", "#3295D2", null, true, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("properties");

    // 老仓那份 Map.copyOf 本就拒 null（键与值），此处只是把"顺序"这一项换掉
    Map<String, PropertyDef> nullValue = new LinkedHashMap<>();
    nullValue.put("width", null);
    assertThatThrownBy(() -> new PathwayGroup("river", "河流", "#3295D2", null, true, nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");

    Map<String, PropertyDef> nullKey = new LinkedHashMap<>();
    nullKey.put(null, new PropertyDef("int", 1, null));
    assertThatThrownBy(() -> new PathwayGroup("river", "河流", "#3295D2", null, true, nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
  }

  /** ★ 嵌套 record：Task 7 的反射枚举要能穿透它（R-48-c：不许为省事把 properties 删掉）。 */
  @Test
  void propertyDefIsNestedAndKeepsThreeComponents() {
    assertThat(PropertyDef.class.getEnclosingClass()).isEqualTo(PathwayGroup.class);
    assertThat(PropertyDef.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("type", "defaultValue", "description");

    PropertyDef def = new PropertyDef("int", 1, null);
    assertThat(def.defaultValue()).isEqualTo(1);
    assertThat(def.description()).isNull();

    for (String blank : new String[] {"", "   ", null}) {
      assertThatThrownBy(() -> new PropertyDef(blank, 1, "x"))
          .as("type %s", blank)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("type");
    }
  }
}
