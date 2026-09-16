### Task 8: `Resolver` SPI 与注册表（`resolve` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/resolve/ResolveContext.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/resolve/Resolver.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/resolve/ResolverRegistry.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）、`QueryResult` / `ResolvedSubject` / `SubjectId`（Task 3）、`SimosTimestamp`（Task 4）、`SimulationState`（Task 6）
- Produces: `ResolveContext(SimulationState state, SimosTimestamp at)`；`Resolver{namespace(), resolve(Address, ResolveContext)}`；`ResolverRegistry{register(Resolver), namespaces(), resolve(Address, ResolveContext)}`

**裁决口径**（spec §〇 第 3 项）：**namespace 唯一映射**——无顺序、无兜底、重复注册立即抛异常。与 GSimulator 的"多解析器按优先级遮蔽、不匹配就静默兜底"相反。

- [ ] **Step 1: 写失败测试**

```java
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
    assertThat(registry.resolve(Address.parse("unit:U"), context()).candidates().get(0).id().localId())
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
            List.of(new ResolvedSubject(new SubjectId(namespace, localId), address.canonical(), "Toy")));
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=ResolverRegistryTest test`
Expected: 编译失败——`cannot find symbol: class Resolver`

- [ ] **Step 3: 实现三个类型**

`ResolveContext.java`：

```java
package io.mosire.simos.util.resolve;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 解析上下文（spec §〇 附表）：`revision` 管数据版本、`at` 管模拟时间，两者正交。
 *
 * <p>**不加通用扩展袋**——需要什么就在这里显式长出来。
 */
public record ResolveContext(SimulationState state, SimosTimestamp at) {

  public ResolveContext {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(at, "at");
  }
}
```

`Resolver.java`：

```java
package io.mosire.simos.util.resolve;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;

/**
 * 命名空间解析器 SPI（总纲 §4.8）：各领域模块自己实现，Core 装配期注册。
 *
 * <p>返回**候选列表**（Human 形式可多解）；调用方选定后一律使用 canonical 地址。
 */
public interface Resolver {

  /** 本解析器负责的命名空间，与地址首段一致（`map` / `social` / `unit` / `agent`）。 */
  String namespace();

  QueryResult resolve(Address address, ResolveContext ctx);
}
```

`ResolverRegistry.java`：

```java
package io.mosire.simos.util.resolve;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 解析器注册表：**namespace 唯一映射**（spec §〇 裁决 3）。
 *
 * <p>注册顺序只影响 {@link #namespaces()} 的展示顺序，**不构成优先级**；重复注册立即抛异常，
 * 未知命名空间不给兜底。
 */
public final class ResolverRegistry {

  private final Map<String, Resolver> resolvers = new LinkedHashMap<>();

  public void register(Resolver resolver) {
    Objects.requireNonNull(resolver, "resolver");
    String namespace = resolver.namespace();
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("Resolver.namespace() 不得为空白");
    }
    if (resolvers.putIfAbsent(namespace, resolver) != null) {
      throw new IllegalArgumentException(
          "命名空间 " + namespace + " 已有解析器，不允许重复注册（注册表不设优先级）");
    }
  }

  /** 注册序。 */
  public List<String> namespaces() {
    return List.copyOf(resolvers.keySet());
  }

  /** 分发到地址首段对应的解析器；未注册的命名空间**抛异常，不兜底**。 */
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    Resolver resolver = resolvers.get(address.namespace());
    if (resolver == null) {
      throw new IllegalArgumentException(
          "没有注册命名空间 " + address.namespace() + " 的解析器（已注册：" + resolvers.keySet() + "）");
    }
    return resolver.resolve(address, ctx);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=ResolverRegistryTest test`
Expected: PASS（**7 个用例**：brief 原给 4 条 + 控制器补齐的 3 条——null 守卫字段级消息、`namespaces()` 快照语义、`ResolveContext` 的两条 record 守卫；另 `blankNamespaceIsRejected` 内加了一例 null 命名空间）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/resolve/ simos-util/src/test/java/io/mosire/simos/util/resolve/
git diff --cached --stat
git commit -m "feat(util): Resolver SPI 与唯一映射注册表（M1 Task 8）"
```

---

