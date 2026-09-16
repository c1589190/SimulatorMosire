package io.mosire.simos.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 一座城：身份类型化、归属可为 null、props 保序不可变。 */
class CityTest {

  /**
   * 六个属性，**插入序既不是字典序、也不是哈希序**。
   *
   * <p>★ 键数是量出来的，不是随手定的：3 键时 {@code Map.copyOf} 有 **10/30（33%）** 的概率**恰好落回插入序**（同一条探针 另一次跑出 13/30 =
   * 43% —— 30 次采样的率是**估值**，不是常量），此时"改用 {@code Map.copyOf}"的变异体**打不响**这条钉子； 6 键实测 0/30。见 {@code
   * task-5-evidence/order-probe/}。
   */
  private static final String[] INSERTION_ORDER = {
    "population", "owner", "founded", "walls", "trade", "port"
  };

  private static Map<String, Object> props() {
    Map<String, Object> props = new LinkedHashMap<>();
    for (String key : INSERTION_ORDER) {
      props.put(key, key);
    }
    return props;
  }

  private static City city() {
    return new City(new CityId("c1"), "临江", new HexCoord(0, 0), new RegionId("r1"), props());
  }

  /** ★ 保序：与**冻结字面量**比，而不是"再调一次、比两次结果" —— {@code props()} 两次返回的是**同一个实例**，那种写法恒绿，钉不住它。 */
  @Test
  void propsPreservesInsertionOrder() {
    assertThat(city().props().keySet()).containsExactly(INSERTION_ORDER);
  }

  /** 不可变 + 防拷贝：源 map 事后被改**不回流**。 */
  @Test
  void propsIsImmutable() {
    Map<String, Object> source = props();
    City c = new City(new CityId("c1"), "临江", new HexCoord(0, 0), new RegionId("r1"), source);

    source.put("burned", true);

    assertThat(c.props()).containsOnlyKeys(INSERTION_ORDER);
    assertThat(c.props()).isUnmodifiable();
    assertThatThrownBy(() -> c.props().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void constructorRejectsNullIdBlankNameNullAtNullProps() {
    assertThatThrownBy(() -> new City(null, "临江", new HexCoord(0, 0), null, props()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
    for (String blank : new String[] {"", "  "}) {
      assertThatThrownBy(() -> new City(new CityId("c1"), blank, new HexCoord(0, 0), null, props()))
          .as("空白名字 %s", blank)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name");
    }
    assertThatThrownBy(() -> new City(new CityId("c1"), null, new HexCoord(0, 0), null, props()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> new City(new CityId("c1"), "临江", null, null, props()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at");
    assertThatThrownBy(() -> new City(new CityId("c1"), "临江", new HexCoord(0, 0), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("props");
  }

  /** 与 {@code EdgeTags}/{@code PathwayGroup} 同口径：键值都不得为 null（老仓的 {@code Map.copyOf} 本就拒 null）。 */
  @Test
  void propsRejectsNullKeyOrValue() {
    Map<String, Object> nullValue = new LinkedHashMap<>();
    nullValue.put("population", null);
    assertThatThrownBy(() -> new City(new CityId("c1"), "临江", new HexCoord(0, 0), null, nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("props");

    Map<String, Object> nullKey = new LinkedHashMap<>();
    nullKey.put(null, 1);
    assertThatThrownBy(() -> new City(new CityId("c1"), "临江", new HexCoord(0, 0), null, nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("props");
  }

  /** ★ `region` 为 null = "不在任何区域内"，是合法状态（区域是画出来的，格可以没有归属）。 */
  @Test
  void regionMayBeNull() {
    City unowned = new City(new CityId("c1"), "飞地", new HexCoord(0, 0), null, Map.of());

    assertThat(unowned.region()).isNull();
    assertThat(unowned)
        .isEqualTo(new City(new CityId("c1"), "飞地", new HexCoord(0, 0), null, Map.of()));
    assertThat(unowned).isNotEqualTo(city());
  }

  @Test
  void equalityIsComponentwise() {
    City base = city();

    assertThat(new City(new CityId("c1"), "临江", new HexCoord(0, 0), new RegionId("r1"), props()))
        .isEqualTo(base)
        .hasSameHashCodeAs(base);

    assertThat(new City(new CityId("c9"), "临江", new HexCoord(0, 0), new RegionId("r1"), props()))
        .isNotEqualTo(base);
    assertThat(new City(new CityId("c1"), "临海", new HexCoord(0, 0), new RegionId("r1"), props()))
        .isNotEqualTo(base);
    assertThat(new City(new CityId("c1"), "临江", new HexCoord(1, 0), new RegionId("r1"), props()))
        .isNotEqualTo(base);
    assertThat(new City(new CityId("c1"), "临江", new HexCoord(0, 0), new RegionId("r2"), props()))
        .isNotEqualTo(base);
    assertThat(
            new City(
                new CityId("c1"),
                "临江",
                new HexCoord(0, 0),
                new RegionId("r1"),
                Map.of("population", 1)))
        .isNotEqualTo(base);
  }
}
