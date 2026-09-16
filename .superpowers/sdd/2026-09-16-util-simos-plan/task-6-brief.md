### Task 6: 版本坐标、三个协议接口与 `SimulationState`

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/BranchId.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/RevisionId.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/StateRef.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/StateMeta.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/Snapshot.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/ChangeSet.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/Command.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/SimulationState.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/state/StateRefTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/state/SimulationStateTest.java`

**Interfaces:**
- Consumes: `SimosTimestamp`（Task 4）、`InfoSystem` / `InMemoryInfoSystem`（Task 5）
- Produces: `BranchId(String value)`；`RevisionId(long value)`（`Comparable`）；`StateRef(BranchId, RevisionId)`；`StateMeta(StateRef, SimosTimestamp)`；`Snapshot{ref(), timestamp(), namespace()}`；`ChangeSet{baseRevision()}`；`Command{expectedRevision()}`；`SimulationState(StateMeta, Map<String,Snapshot>, InfoSystem)` + `module(String) → Optional<Snapshot>`

- [ ] **Step 1: 写失败测试**

`StateRefTest.java`：

```java
package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §五：`RevisionId` 只在分支内有意义，跨分支的完整坐标是 `StateRef`。 */
class StateRefTest {

  @Test
  void branchAndRevisionFormTheCoordinate() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(7));
    assertThat(ref.branch().value()).isEqualTo("main");
    assertThat(ref.revision().value()).isEqualTo(7);
    assertThat(ref).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(7)));
  }

  @Test
  void revisionsAreOrdered() {
    assertThat(new RevisionId(7)).isGreaterThan(new RevisionId(6));
    assertThat(new RevisionId(7)).isEqualByComparingTo(new RevisionId(7));
  }

  @Test
  void blankBranchIsRejected() {
    assertThatThrownBy(() -> new BranchId(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stateMetaCarriesRefAndTimestamp() {
    StateMeta meta =
        new StateMeta(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(3, "第 3 日"));
    assertThat(meta.ref().revision()).isEqualTo(new RevisionId(1));
    assertThat(meta.timestamp().tick()).isEqualTo(3);
  }
}
```

`SnapshotProtocolTest.java`：

```java
package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §六：三个协议接口保持最小——不用万能父类，ChangeSet/Command 都是单方法接口。 */
class SnapshotProtocolTest {

  /** 玩具快照：证明模块快照只需实现三个方法即可接入协议。 */
  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha)
      implements Snapshot {}

  @Test
  void toySnapshotImplementsTheThreeProtocolMethods() {
    ToySnapshot snapshot =
        new ToySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(2)), SimosTimestamp.of(1), "toy", 42);
    assertThat(snapshot.ref().revision()).isEqualTo(new RevisionId(2));
    assertThat(snapshot.timestamp().tick()).isEqualTo(1);
    assertThat(snapshot.namespace()).isEqualTo("toy");
    assertThat(snapshot.alpha()).isEqualTo(42);
  }

  @Test
  void changeSetAndCommandExposeTheirStamps() {
    ChangeSet changeSet = () -> new RevisionId(2);
    Command command = () -> new RevisionId(2);
    assertThat(changeSet.baseRevision()).isEqualTo(new RevisionId(2));
    assertThat(command.expectedRevision()).isEqualTo(new RevisionId(2));
  }
}
```

`SimulationStateTest.java`：

```java
package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §六：`module(namespace)` 是唯一取用入口；无跨模块访问器。 */
class SimulationStateTest {

  @Test
  void moduleIsTheOnlyLookup() {
    SimulationState state = stateWith(toySnapshot("map", 1), toySnapshot("unit", 2));
    assertThat(state.module("map")).map(Snapshot::namespace).contains("map");
    assertThat(state.module("unit")).map(Snapshot::namespace).contains("unit");
    assertThat(state.module("social")).isEmpty();
  }

  @Test
  void moduleKeysMustMatchSnapshotNamespace() {
    assertThatThrownBy(
            () ->
                new SimulationState(
                    meta(), Map.of("map", toySnapshot("unit", 1)), InMemoryInfoSystem.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void modulesMapIsDefensivelyCopied() {
    Map<String, Snapshot> mutable = new HashMap<>();
    mutable.put("map", toySnapshot("map", 1));
    SimulationState state = new SimulationState(meta(), mutable, InMemoryInfoSystem.empty());
    mutable.clear();
    assertThat(state.modules()).hasSize(1);
    assertThatThrownBy(() -> state.modules().put("unit", toySnapshot("unit", 2)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void stateExposesNoCrossModuleAccessor() {
    // 铁律 3/4：跨模块可见性只走 Facet。公开方法只有下面这些——
    // 任何"返回领域类型的便捷访问器"（state.map() 之类）都会让本用例红。
    assertThat(publicMethodNames(SimulationState.class))
        .containsExactlyInAnyOrder(
            "meta", "modules", "info", "module", "equals", "hashCode", "toString");
  }

  @Test
  void theAccessorCheckFlagsAReplicaThatHasOne() {
    // G13 自证：同一个检查器对故意违规的夹具必须报出多余的方法。
    assertThat(publicMethodNames(NonCompliantState.class)).contains("map");
    assertThat(new NonCompliantState().map()).isEmpty();
  }

  private static List<String> publicMethodNames(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(m -> Modifier.isPublic(m.getModifiers()))
        .map(Method::getName)
        .sorted()
        .distinct()
        .toList();
  }

  /** 故意违规夹具（G13 的自证对象）：一个挂跨模块便捷访问器的假状态类型。 */
  private static final class NonCompliantState {

    public Map<String, Snapshot> map() {
      return Map.of();
    }
  }

  private static SimulationState stateWith(Snapshot... snapshots) {
    Map<String, Snapshot> modules = new HashMap<>();
    for (Snapshot snapshot : snapshots) {
      modules.put(snapshot.namespace(), snapshot);
    }
    return new SimulationState(meta(), modules, InMemoryInfoSystem.empty());
  }

  private static Snapshot toySnapshot(String namespace, int revision) {
    return new ToySnapshot(
        new StateRef(new BranchId("main"), new RevisionId(revision)),
        SimosTimestamp.of(revision),
        namespace,
        revision);
  }

  private static StateMeta meta() {
    return new StateMeta(new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1));
  }

  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha)
      implements Snapshot {}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' test`
Expected: 编译失败——`cannot find symbol: class StateRef`

- [ ] **Step 3: 实现四个坐标类型与三个协议接口**

`BranchId.java`：

```java
package io.mosire.simos.util.state;

/** 分支标识（总纲 §4.1）：`RevisionId` 只在分支内有意义。 */
public record BranchId(String value) {

  public BranchId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("BranchId.value 不得为空白");
    }
  }
}
```

`RevisionId.java`：

```java
package io.mosire.simos.util.state;

/** 数据版本号：只管数据版本，与模拟时间（`SimosTimestamp`）互不换算（总纲 §0.1）。 */
public record RevisionId(long value) implements Comparable<RevisionId> {

  @Override
  public int compareTo(RevisionId other) {
    return Long.compare(value, other.value);
  }
}
```

`StateRef.java`：

```java
package io.mosire.simos.util.state;

import java.util.Objects;

/** 状态的唯一坐标：分支 + 版本（总纲 §4.1）。 */
public record StateRef(BranchId branch, RevisionId revision) {

  public StateRef {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(revision, "revision");
  }
}
```

`StateMeta.java`：

```java
package io.mosire.simos.util.state;

import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/** 整个世界状态的坐标：版本坐标 + 模拟时刻（spec §五）。 */
public record StateMeta(StateRef ref, SimosTimestamp timestamp) {

  public StateMeta {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
```

`Snapshot.java`：

```java
package io.mosire.simos.util.state;

import io.mosire.simos.util.time.SimosTimestamp;

/**
 * 模块状态切片（总纲 §4.5）：**不用万能父类**，各模块的快照是各自独立的 record 树。
 *
 * <p>`namespace()` 是 spec §十-D1 的增补——Util 不认识领域类型，只能靠它把切片与模块对上号。
 */
public interface Snapshot {

  StateRef ref();

  SimosTimestamp timestamp();

  /** 本快照所属模块（`"map"` / `"social"` / `"unit"`）。 */
  String namespace();
}
```

`ChangeSet.java`：

```java
package io.mosire.simos.util.state;

/**
 * 变更集（总纲 §4.5）：字段清单由**各模块从自己的 Snapshot 类型派生**（铁律 5），
 * Util 只给接口与往返断言工具（{@code io.mosire.simos.util.verify.RoundTripAssertions}）。
 */
public interface ChangeSet {

  RevisionId baseRevision();
}
```

`Command.java`：

```java
package io.mosire.simos.util.state;

/**
 * 命令（总纲 §4.5）：保持最小——只有乐观并发所需的 `expectedRevision()`。
 *
 * <p>`commandId` / `correlationId` / 发起者属于**命令信封**（总纲 §8.1），由 Core 的 Command Bus 承担（spec §十-D6）。
 */
public interface Command {

  RevisionId expectedRevision();
}
```

- [ ] **Step 4: 实现 `SimulationState`**

```java
package io.mosire.simos.util.state;

import io.mosire.simos.util.info.InfoSystem;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 整个模拟状态：元信息 + 各模块切片 + 外挂信息（spec §六）。
 *
 * <p>**不提供跨模块访问器**（没有 `state.map().units()` 这类入口）：跨模块可见性只走 Facet
 * （铁律 3/4）。这条由 {@code SimulationStateTest} 的反射用例钉住。
 */
public record SimulationState(StateMeta meta, Map<String, Snapshot> modules, InfoSystem info) {

  public SimulationState {
    Objects.requireNonNull(meta, "meta");
    Objects.requireNonNull(modules, "modules");
    Objects.requireNonNull(info, "info");
    modules = Map.copyOf(modules);
    for (Map.Entry<String, Snapshot> entry : modules.entrySet()) {
      String namespace = entry.getValue().namespace();
      if (!entry.getKey().equals(namespace)) {
        throw new IllegalArgumentException(
            "modules 的键必须等于该快照的 namespace()：键=" + entry.getKey() + "，快照=" + namespace);
      }
    }
  }

  /** 唯一的取用入口。 */
  public Optional<Snapshot> module(String namespace) {
    return Optional.ofNullable(modules.get(namespace));
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' test`
Expected: PASS

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/state/ simos-util/src/test/java/io/mosire/simos/util/state/
git diff --cached --stat
git commit -m "feat(util): 版本坐标、三个协议接口与 SimulationState（M1 Task 6）"
```

---

