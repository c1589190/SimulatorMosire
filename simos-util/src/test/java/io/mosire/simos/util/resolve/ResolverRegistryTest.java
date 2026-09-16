package io.mosire.simos.util.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §〇 裁决 3：namespace 唯一映射、无顺序、无兜底、重复注册立即抛异常。 */
class ResolverRegistryTest {

  @Test
  void dispatchesToTheResolverOfTheAddressNamespace() {
    ResolverRegistry registry = new ResolverRegistry();
    // 注册序**故意不取字母序**：若按字母序注册，"注册序"这条断言换成 TreeMap 实现也照样全绿，
    // 等于空转护栏。unit → map 之后，LinkedHashMap 绿、任何排序实现红。
    registry.register(resolver("unit", "u-1"));
    registry.register(resolver("map", "m-1"));
    assertThat(registry.namespaces()).containsExactly("unit", "map"); // 注册序，非排序
    assertThat(
            registry.resolve(Address.parse("unit:U"), context()).candidates().get(0).id().localId())
        .isEqualTo("u-1");
    assertThat(
            registry
                .resolve(Address.parse("map:Map1"), context())
                .candidates()
                .get(0)
                .id()
                .localId())
        .isEqualTo("m-1");
  }

  @Test
  void duplicateRegistrationIsRejected() {
    ResolverRegistry registry = new ResolverRegistry();
    registry.register(resolver("map", "m-1"));
    assertThatThrownBy(() -> registry.register(resolver("map", "m-2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }

  @Test
  void unregisteredNamespaceIsRejectedWithNoFallback() {
    // 与 GSimulator 的"静默遮蔽"相反：没有注册就是错，不给兜底解析器。
    ResolverRegistry registry = new ResolverRegistry();
    assertThatThrownBy(() -> registry.resolve(Address.parse("map:Map1"), context()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }

  @Test
  void blankNamespaceIsRejected() {
    ResolverRegistry registry = new ResolverRegistry();
    assertThatThrownBy(() -> registry.register(resolver(" ", "m-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    // null 走同一条守卫：只写 isBlank() 的实现会在此抛 NPE，本断言转红。
    assertThatThrownBy(() -> registry.register(resolver(null, "m-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
  }

  @Test
  void nullArgumentsAreRejectedWithFieldLevelMessages() {
    // 必须用 **`hasMessage` 精确匹配**，不能用 `hasMessageContaining`：`requireNonNull(x, "x")` 的消息
    // 恰是字段名本身，而删掉守卫后紧接着的那次解引用（`resolver.namespace()` / `address.namespace()`）
    // 会抛 JDK 21 的热心 NPE，其消息形如 `Cannot invoke "..." because "resolver" is null`
    // ——**同样含该字段名**，于是 `hasMessageContaining("resolver")` 对守卫的存废毫无判别力。
    // 这是 T7 `hasMessageContaining("t")` 的同一形态，只是 needle 从 1 个字符变成了 8 个。
    ResolverRegistry registry = new ResolverRegistry();
    assertThatThrownBy(() -> registry.register(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("resolver");
    assertThatThrownBy(() -> registry.resolve(null, context()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("address");
    assertThatThrownBy(() -> registry.resolve(Address.parse("map:Map1"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("ctx");
  }

  @Test
  void namespacesIsDefensivelyCopiedAndImmutable() {
    ResolverRegistry registry = new ResolverRegistry();
    registry.register(resolver("unit", "u-1"));
    List<String> names = registry.namespaces();
    assertThatThrownBy(() -> names.add("map")).isInstanceOf(UnsupportedOperationException.class);
    // 取到的是**快照**而非视图：后续注册不得改变已取出的列表
    // （返回 Collections.unmodifiableList(resolvers.keySet()) 这类"不可改但仍是视图"的实现会在此转红）。
    registry.register(resolver("map", "m-1"));
    assertThat(names).containsExactly("unit");
    assertThat(registry.namespaces()).containsExactly("unit", "map");
  }

  private static Resolver resolver(String namespace, String localId) {
    return new Resolver() {

      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public QueryResult resolve(Address address, ResolveContext ctx) {
        return new QueryResult(
            List.of(
                new ResolvedSubject(
                    new SubjectId(namespace, localId), address.canonical(), "Toy")));
      }
    };
  }

  @Test
  void resolveContextRejectsNullParts() {
    // `ResolveContext` 是本任务新建的状态类型，它的两条守卫同样要自证（G13，同 T7 对 Segment/Event 的处置）。
    // 两条都**有判别力**：删掉任一条，record 只会照存 null、什么都不抛，`assertThatThrownBy` 直接红。
    assertThatThrownBy(() -> new ResolveContext(null, SimosTimestamp.of(1)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("state");
    assertThatThrownBy(() -> new ResolveContext(state(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("at");
  }

  private static SimulationState state() {
    return new SimulationState(
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1)),
        Map.of(),
        InMemoryInfoSystem.empty());
  }

  private static ResolveContext context() {
    return new ResolveContext(state(), SimosTimestamp.of(1));
  }
}
