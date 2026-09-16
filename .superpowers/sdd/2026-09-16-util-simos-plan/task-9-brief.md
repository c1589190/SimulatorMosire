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
    assertThat(new FacetRegistry().facetNames()).isEmpty();
  }

  @Test
  void blankOrNullPartsAreRejected() {
    assertThatThrownBy(() -> new FacetEntry(" ", "label", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FacetEntry("unit", "label", "Type", null))
        .isInstanceOf(NullPointerException.class); // 空值走 Objects.requireNonNull，不裹成 IAE
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
Expected: PASS（5 个用例）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/facet/ simos-util/src/test/java/io/mosire/simos/util/facet/
git diff --cached --stat
git commit -m "feat(util): Facet 协议与注册表（M1 Task 9）"
```

---

