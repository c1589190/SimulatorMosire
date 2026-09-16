package io.mosire.simos.map.pathway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 线（`Pathway`）与它的身份（`PathwayId`）：ID 是**分配并持久化**的，不由内容派生（待决项 3）。 */
class PathwayTest {

  private static HexCoord c(int q, int r) {
    return new HexCoord(q, r);
  }

  /** 三格一条直线：(0,0) — (1,0) — (2,0)。 */
  private static final EdgeRef AB = new EdgeRef(c(0, 0), c(1, 0));

  private static final EdgeRef BC = new EdgeRef(c(1, 0), c(2, 0));

  private static final List<EdgeRef> CHAIN = List.of(AB, BC);

  private static Pathway pathway() {
    return new Pathway(new PathwayId("p1"), "长河", "river", CHAIN, Map.of("width", 2));
  }

  @Test
  void constructorRejectsBlankGroupIdAndNullId() {
    assertThatThrownBy(() -> new Pathway(null, "长河", "river", CHAIN, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
    for (String blank : new String[] {"", "   "}) {
      assertThatThrownBy(() -> new Pathway(new PathwayId("p1"), "长河", blank, CHAIN, Map.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("groupId");
    }
    assertThatThrownBy(() -> new Pathway(new PathwayId("p1"), "长河", null, CHAIN, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("groupId");
    assertThatThrownBy(() -> new Pathway(new PathwayId("p1"), "长河", "river", null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("edges");
    assertThatThrownBy(() -> new Pathway(new PathwayId("p1"), "长河", "river", CHAIN, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("props");
  }

  @Test
  void edgesIsImmutable() {
    List<EdgeRef> source = new ArrayList<>(CHAIN);
    Pathway p = new Pathway(new PathwayId("p1"), "长河", "river", source, Map.of());

    source.add(new EdgeRef(c(2, 0), c(3, 0)));
    source.remove(0);

    assertThat(p.edges()).containsExactly(AB, BC);
    assertThat(p.edges()).isUnmodifiable();
  }

  /** `props` 与 `edges` 同口径：**保序 + 不可变 + 防拷贝**（源 map 事后被改不回流）。 */
  @Test
  void propsIsImmutable() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("flow", "fast");
    source.put("depth", 3);
    Pathway p = new Pathway(new PathwayId("p1"), "长河", "river", CHAIN, source);

    source.put("dam", true);

    assertThat(p.props()).containsOnlyKeys("flow", "depth");
    assertThat(p.props()).isUnmodifiable();
    assertThatThrownBy(() -> p.props().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void lengthIsEdgeCount() {
    assertThat(pathway().length()).isEqualTo(2);
    assertThat(new Pathway(new PathwayId("p1"), null, "river", List.of(AB), Map.of()).length())
        .isEqualTo(1);
  }

  /** ★ 三格链：`start`/`end` 必须是**两头**。(1,0) 是中间那格，两个都不是它。 */
  @Test
  void startAndEndAreTheChainEnds() {
    Pathway p = pathway();

    assertThat(p.start()).isEqualTo(c(0, 0));
    assertThat(p.end()).isEqualTo(c(2, 0));
    assertThat(p.start()).isNotEqualTo(c(1, 0));
    assertThat(p.end()).isNotEqualTo(c(1, 0));

    // 单边链：两端就是那两格（a() 恒为规范序较小的那格）
    Pathway single = new Pathway(new PathwayId("p1"), null, "river", List.of(BC), Map.of());
    assertThat(single.start()).isEqualTo(c(1, 0));
    assertThat(single.end()).isEqualTo(c(2, 0));
  }

  /** 闭环没有端点 ⇒ 头尾同取**规范序最小的那格**作锚（spec §5.2）。 */
  @Test
  void closedLoopAnchorsAtSmallestHex() {
    // 三角形 (0,0)-(1,0)-(1,-1)-(0,0)：三格度数都恰为 2
    Pathway loop =
        new Pathway(
            new PathwayId("p1"),
            "环",
            "river",
            List.of(
                new EdgeRef(c(1, 0), c(1, -1)),
                new EdgeRef(c(0, 0), c(1, 0)),
                new EdgeRef(c(0, 0), c(1, -1))),
            Map.of());

    assertThat(loop.start()).isEqualTo(c(0, 0));
    assertThat(loop.end()).isEqualTo(c(0, 0));
  }

  @Test
  void emptyChainHasNoEndpoint() {
    Pathway empty = new Pathway(new PathwayId("p1"), null, "river", List.of(), Map.of());

    assertThat(empty.length()).isZero();
    assertThatThrownBy(empty::start).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(empty::end).isInstanceOf(IllegalStateException.class);
  }

  /** 断链（相邻两条边不相接）不是"极大简单链" ⇒ 取端点即抛（构造期不拦：空/断的 `edges` 是原料状态）。 */
  @Test
  void brokenChainIsRejectedAtEndpointAccess() {
    Pathway broken =
        new Pathway(
            new PathwayId("p1"),
            null,
            "river",
            List.of(AB, new EdgeRef(c(7, 7), c(8, 7))),
            Map.of());

    assertThatThrownBy(broken::start).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(broken::end).isInstanceOf(IllegalStateException.class);
  }

  /**
   * ★ **id 是持久身份，不随内容漂移**（待决项 3 的钉子，两条合起来才算数）：
   *
   * <p>① 内容全同、只有 id 不同 ⇒ **不相等**（id 不是冗余字段）；② 换掉一个中间节点、id 不变 ⇒ **id 仍相同**
   * （内容派生会让"改一条河的一个中间节点"变成"换了一条河"）。
   */
  @Test
  void idsArePersistedNotDerived() {
    Pathway a = pathway();
    Pathway b = new Pathway(new PathwayId("p2"), "长河", "river", CHAIN, Map.of("width", 2));
    assertThat(a).isNotEqualTo(b);

    List<EdgeRef> edited = new ArrayList<>(CHAIN);
    edited.set(1, new EdgeRef(c(1, 0), c(5, 5)));
    Pathway moved = new Pathway(new PathwayId("p1"), "长河", "river", edited, Map.of("width", 2));
    assertThat(moved.id()).isEqualTo(a.id());
    assertThat(moved.edges()).isNotEqualTo(a.edges());
  }

  @Test
  void equalityIsComponentwise() {
    Pathway base = pathway();

    assertThat(new Pathway(new PathwayId("p1"), "长河", "river", CHAIN, Map.of("width", 2)))
        .isEqualTo(base)
        .hasSameHashCodeAs(base);

    assertThat(new Pathway(new PathwayId("p9"), "长河", "river", CHAIN, Map.of("width", 2)))
        .isNotEqualTo(base);
    assertThat(new Pathway(new PathwayId("p1"), "短河", "river", CHAIN, Map.of("width", 2)))
        .isNotEqualTo(base);
    assertThat(new Pathway(new PathwayId("p1"), "长河", "road", CHAIN, Map.of("width", 2)))
        .isNotEqualTo(base);
    assertThat(new Pathway(new PathwayId("p1"), "长河", "river", List.of(AB), Map.of("width", 2)))
        .isNotEqualTo(base);
    assertThat(new Pathway(new PathwayId("p1"), "长河", "river", CHAIN, Map.of("width", 3)))
        .isNotEqualTo(base);
  }

  // ── ★ R-48-f：PathwayId 的三件套（缺一，Task 6 的往返就断） ─────────────────────

  /** ★ 冻结字面量。写成"两个实例的 toString 相等"是自证循环，钉不住 record 的默认实现。 */
  @Test
  void pathwayIdToStringIsBareValue() {
    assertThat(new PathwayId("p1").toString()).isEqualTo("p1");
    assertThat(new PathwayId("river-第 3 段").toString()).isEqualTo("river-第 3 段");
  }

  @Test
  void pathwayIdParseRoundTripsFrozenLiteral() {
    assertThat(PathwayId.parse("p1")).isEqualTo(new PathwayId("p1"));
    assertThat(PathwayId.parse(new PathwayId("p1").toString())).isEqualTo(new PathwayId("p1"));
  }

  @Test
  void pathwayIdParseRejectsBlank() {
    for (String blank : new String[] {"", "  "}) {
      assertThatThrownBy(() -> PathwayId.parse(blank))
          .as("空白串 %s", blank)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("不得为空白");
    }
    assertThatThrownBy(() -> PathwayId.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pathwayIdRejectsBlankValue() {
    for (String blank : new String[] {"", "  "}) {
      assertThatThrownBy(() -> new PathwayId(blank)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new PathwayId(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
