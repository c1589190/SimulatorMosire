package io.mosire.simos.map.pathway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 边上的标注：**唯一主存储**（spec §5.3 的 L2），保序且不可变。 */
class EdgeTagsTest {

  /** 六个 pathway，**按倒字典序**插入 —— 与任何"排过序"或"哈希序"的结果都不同。 */
  private static final String[] INSERTION_ORDER = {
    "width", "name", "locked", "flow", "depth", "color"
  };

  private static Map<String, Object> riverProps() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("depth", 3);
    props.put("width", 2);
    props.put("flow", "fast");
    return props;
  }

  private static EdgeTags tags() {
    Map<String, Map<String, Object>> byPathway = new LinkedHashMap<>();
    for (String key : INSERTION_ORDER) {
      byPathway.put(key, riverProps());
    }
    return new EdgeTags(byPathway);
  }

  /**
   * ★ 保序（前端曾因 props 被抹平而丢数据）：断言与**冻结字面量**逐项比，不是"再调一次、比两次结果" —— 后者在同一 JVM 内对 {@code Map.copyOf}
   * 也成立，钉不住它。
   */
  @Test
  void preservesInsertionOrder() {
    EdgeTags tags = tags();

    assertThat(tags.byPathway().keySet()).containsExactly(INSERTION_ORDER);
    assertThat(tags.byPathway().get("width").keySet()).containsExactly("depth", "width", "flow");
  }

  /** 外层与**内层**都不可改：浅拷贝只冻结外层，调用方照样能顺着内层 map 改光 props。 */
  @Test
  void isImmutable() {
    EdgeTags tags = tags();

    assertThat(tags.byPathway()).isUnmodifiable();
    assertThat(tags.byPathway().get("width")).isUnmodifiable();
    assertThatThrownBy(() -> tags.byPathway().put("x", Map.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> tags.byPathway().get("width").put("width", 99))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** 防拷贝：源 map 事后被改**不回流**（不然就是又一条可写路径，正是 L2 那个病的形态）。 */
  @Test
  void constructionCopiesTheSource() {
    Map<String, Map<String, Object>> source = new LinkedHashMap<>();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("width", 2);
    source.put("river-1", props);

    EdgeTags tags = new EdgeTags(source);
    props.put("depth", 3);
    source.put("river-2", Map.of("width", 1));

    assertThat(tags.byPathway().get("river-1")).containsOnlyKeys("width");
    assertThat(tags.byPathway()).containsOnlyKeys("river-1");
  }

  @Test
  void rejectsNullOuterKeyAndNullInnerProps() {
    assertThatThrownBy(() -> new EdgeTags(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byPathway");

    Map<String, Map<String, Object>> nullInner = new LinkedHashMap<>();
    nullInner.put("river-1", null);
    assertThatThrownBy(() -> new EdgeTags(nullInner))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");

    Map<String, Map<String, Object>> nullKey = new LinkedHashMap<>();
    nullKey.put(null, Map.of("width", 2));
    assertThatThrownBy(() -> new EdgeTags(nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
  }
}
