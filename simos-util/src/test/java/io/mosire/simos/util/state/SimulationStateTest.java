package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
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

  /**
   * 以下三条是控制器裁决第 1 条补的守卫自证用例。`modules` 与 `meta` 的消息判据尤其要紧：删掉紧凑构造器里的 `requireNonNull`
   * 后，`Map.copyOf(null)` 仍会抛 NPE（只是消息为 null），只断类型的话守卫是空转的。
   */
  @Test
  void nullMetaIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SimulationState(null, Map.of(), InMemoryInfoSystem.empty()))
        .withMessage("meta");
  }

  @Test
  void nullModulesIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SimulationState(meta(), null, InMemoryInfoSystem.empty()))
        .withMessage("modules");
  }

  @Test
  void nullInfoIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SimulationState(meta(), Map.of(), null))
        .withMessage("info");
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
    return new StateMeta(
        new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1));
  }

  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha)
      implements Snapshot {}
}
