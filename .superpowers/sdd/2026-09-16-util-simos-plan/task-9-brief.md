### Task 9: Facet 协议与注册表（`facet` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/facet/FacetProvider.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/facet/FacetEntry.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/facet/FacetRegistry.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/facet/FacetRegistryTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）、`ResolveContext`（Task 8）
- Produces: `FacetProvider{facetName(), query(Address, ResolveContext)}`；`FacetEntry(String namespace, String label, String typeName, Object value)`；`FacetRegistry{register(FacetProvider), facetNames(), queryAll(Address, ResolveContext)}`

**解决什么**：`inspect map:Map1:hex.4_3` 要能列出该 hex 上的单位，而 `MapSimos` 绝不能知道 `UnitSimos` 存在（铁律 3）。模块自注册提供者，`MapSimos` 对这些扩展完全不知情。

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.facet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §八：facetName 唯一；queryAll 按注册序；空列表不是错误。 */
class FacetRegistryTest {

  private static final Address HEX = Address.parse("map:Map1:hex.4_3");

  @Test
  void queryAllConcatenatesInRegistrationOrder() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of("U-1"))));
    registry.register(provider("social", "population", entry("social", "population", 10_000)));
    assertThat(registry.facetNames()).containsExactly("unit", "social"); // 注册序
    assertThat(registry.queryAll(HEX, context()).stream().map(FacetEntry::label).toList())
        .containsExactly("unitsHere", "population");
  }

  @Test
  void duplicateFacetNameIsRejected() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of())));
    assertThatThrownBy(
            () -> registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unitsHere");
  }

  @Test
  void aProviderWithNothingToSayIsNotAnError() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere")); // 返回空列表
    assertThat(registry.queryAll(HEX, context())).isEmpty();
  }

  @Test
  void valuesStayStructured() {
    // spec §八：value 是结构化 Object，不是预先格式化的字符串。
    FacetEntry entry = entry("social", "population", Map.of("total", 10_000, "growth", 0.03));
    assertThat(entry.value()).isInstanceOf(Map.class);
  }

  @Test
  void blankOrNullPartsAreRejected() {
    // 断言钉到**消息**：三条空白守卫的消息各不相同，只断异常类型的话把它们互换实现也照绿。
    assertThatThrownBy(() -> new FacetEntry(" ", "label", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    assertThatThrownBy(() -> new FacetEntry(null, "label", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    assertThatThrownBy(() -> new FacetEntry("unit", " ", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");
    assertThatThrownBy(() -> new FacetEntry("unit", "label", " ", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typeName");
    assertThatThrownBy(() -> new FacetEntry("unit", "label", "Type", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("value"); // 空值走 Objects.requireNonNull，不裹成 IAE
  }

  @Test
  void nullArgumentsAreRejectedWithFieldLevelMessages() {
    // 三条 `requireNonNull` 一律用 **`hasMessage` 精确匹配**：其消息恰是字段名本身，而删掉守卫后
    // 紧接着的解引用（`provider.facetName()`）会抛 JDK 21 的热心 NPE，消息形如
    // `Cannot invoke "..." because "provider" is null`——**同样含该字段名**，
    // 故 `hasMessageContaining("provider")` 对守卫的存废毫无判别力（T7 `"t"` 的同一形态）。
    FacetRegistry registry = new FacetRegistry();
    assertThatThrownBy(() -> registry.register(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("provider");
    assertThatThrownBy(() -> registry.register(provider("unit", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    // null 走同一条守卫：只写 isBlank() 的实现会在此抛 NPE，本断言转红。
    assertThatThrownBy(() -> registry.register(provider("unit", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    assertThatThrownBy(() -> registry.queryAll(null, context()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("subject");
    assertThatThrownBy(() -> registry.queryAll(HEX, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("ctx");
  }

  @Test
  void aProviderReturningNullIsRejected() {
    // 返回 null 与"返回空列表"是两回事：前者是提供者的 bug，后者是"我这一面没内容"。
    // 少了这条守卫，实现会以 NPE 的形态倒下——响亮，但指错了地方。
    FacetRegistry registry = new FacetRegistry();
    registry.register(
        new FacetProvider() {

          @Override
          public String facetName() {
            return "unitsHere";
          }

          @Override
          public List<FacetEntry> query(Address subject, ResolveContext ctx) {
            return null;
          }
        });
    assertThatThrownBy(() -> registry.queryAll(HEX, context()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unitsHere");
  }

  @Test
  void facetNamesIsDefensivelyCopiedAndImmutable() {
    FacetRegistry registry = new FacetRegistry();
    assertThat(registry.facetNames()).isEmpty(); // 空注册表不是错误
    registry.register(provider("unit", "unitsHere"));
    List<String> names = registry.facetNames();
    assertThatThrownBy(() -> names.add("population"))
        .isInstanceOf(UnsupportedOperationException.class);
    // 取到的是**快照**而非视图：后续注册不得改变已取出的列表
    // （返回 Collections.unmodifiableList(providers.keySet()) 这类"不可改但仍是视图"的实现会在此转红）。
    registry.register(provider("social", "population"));
    assertThat(names).containsExactly("unitsHere");
    assertThat(registry.facetNames()).containsExactly("unitsHere", "population");
  }

  private static FacetProvider provider(String namespace, String facetName, FacetEntry... entries) {
    return new FacetProvider() {

      @Override
      public String facetName() {
        return facetName;
      }

      @Override
      public List<FacetEntry> query(Address subject, ResolveContext ctx) {
        return List.of(entries);
      }
    };
  }

  private static FacetEntry entry(String namespace, String label, Object value) {
    return new FacetEntry(namespace, label, "Toy", value);
  }

  private static ResolveContext context() {
    return new ResolveContext(
        new SimulationState(
            new StateMeta(new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1)),
            Map.of(),
            InMemoryInfoSystem.empty()),
        SimosTimestamp.of(1));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=FacetRegistryTest test`
Expected: 编译失败——`cannot find symbol: class FacetProvider`

- [ ] **Step 3: 实现三个类型**

`FacetProvider.java`：

```java
package io.mosire.simos.util.facet;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;
import java.util.List;

/**
 * 跨模块可见性协议（spec §八）：模块自己实现并注册，`MapSimos` 对提供者完全不知情。
 *
 * <p>"某个 hex 上有哪些单位"因此**不能**写成 `MapManager.getUnitsAt(hex)`（铁律 3）。
 */
public interface FacetProvider {

  /** 本面的名字，全注册表唯一（`"unitsHere"` / `"population"`）。 */
  String facetName();

  /** 空列表 = "该主体上我这一面没有内容"，不是错误。 */
  List<FacetEntry> query(Address subject, ResolveContext ctx);
}
```

`FacetEntry.java`：

```java
package io.mosire.simos.util.facet;

import java.util.Objects;

/**
 * 一条跨模块视图（spec §八）。
 *
 * @param namespace 贡献者的模块名（`unit` / `social`），用于分组与显示排序
 * @param label 显示标签
 * @param typeName 供 UI 着色/图标
 * @param value **结构化** `Object`（Jackson 可序列化），不是预先格式化的字符串——GUI / MCP / LLM
 *     三种消费者对同一个值有不同呈现需求，字符串化会把结构在 Core 层丢掉
 */
public record FacetEntry(String namespace, String label, String typeName, Object value) {

  public FacetEntry {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.namespace 不得为空白");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.label 不得为空白");
    }
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.typeName 不得为空白");
    }
    Objects.requireNonNull(value, "value");
  }
}
```

`FacetRegistry.java`：

```java
package io.mosire.simos.util.facet;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Facet 注册表：注册语义与 {@code ResolverRegistry} 一致——名字唯一映射、重复注册立即抛异常、无兜底。
 *
 * <p>{@link #queryAll} **按注册顺序**拼接，显示顺序因此是确定性的（由 Core 的装配顺序决定），不引入排序规则。
 */
public final class FacetRegistry {

  private final Map<String, FacetProvider> providers = new LinkedHashMap<>();

  public void register(FacetProvider provider) {
    Objects.requireNonNull(provider, "provider");
    String facetName = provider.facetName();
    if (facetName == null || facetName.isBlank()) {
      throw new IllegalArgumentException("FacetProvider.facetName() 不得为空白");
    }
    if (providers.putIfAbsent(facetName, provider) != null) {
      throw new IllegalArgumentException(
          "facetName " + facetName + " 已有提供者，不允许重复注册");
    }
  }

  /** 注册序。 */
  public List<String> facetNames() {
    return List.copyOf(providers.keySet());
  }

  /** 按注册顺序拼接所有提供者的结果；提供者返回空列表不报错。 */
  public List<FacetEntry> queryAll(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    List<FacetEntry> entries = new ArrayList<>();
    for (FacetProvider provider : providers.values()) {
      List<FacetEntry> fromProvider = provider.query(subject, ctx);
      if (fromProvider == null) {
        throw new IllegalStateException(
            "FacetProvider " + provider.facetName() + " 返回了 null，应返回空列表");
      }
      entries.addAll(fromProvider);
    }
    return List.copyOf(entries);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=FacetRegistryTest test`
Expected: PASS（**8 个用例**：brief 原给 5 条 + 控制器补齐的 3 条——null 守卫字段级消息、提供者返回 null、`facetNames()` 快照语义；`blankOrNullPartsAreRejected` 同时扩到三条空白守卫并钉消息）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/facet/ simos-util/src/test/java/io/mosire/simos/util/facet/
git diff --cached --stat
git commit -m "feat(util): Facet 协议与注册表（M1 Task 9）"
```

---

