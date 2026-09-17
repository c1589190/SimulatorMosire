package io.mosire.simos.core.state;

import io.mosire.simos.util.state.ChangeSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次推进/一条命令产生的**全模块**变更集（spec §5.5）：{@code namespace → 模块自己的变更集}。
 *
 * <p>它是 {@link ChangeSet} 的**首个 main 源码实现者**（此前 main 侧 0 个、test 侧 3 个，spec §十）——这条正是 "util
 * 的契约不空转"的证据。**全仓 main 源码里 ChangeSet 的实现者恰 4 个**（World/Map/Social/Unit），由 {@code
 * ArchitectureGuardsTest} 的 R1 钉住。
 *
 * <p>★ **Core 不知道 value 的具体类型**：{@code ChangeSet} 是标记接口，连 namespace 访问器都没有——Core 只按**键**（约定为模块的
 * namespace）把 value 交回对应的 codec（C26）。因此本类**不能**像 {@code SimulationState} 那样在构造期校验"键 = value 的
 * namespace"（无从校验，value 是不透明的）；键的 一致性由组装方（推进的 Commit、分岔的空集）保证。
 *
 * <p>★ **保序不可变拷贝**：迭代序 = 传入序。{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序是散列槽位序，不是内容的纯函数（M2 Task 5 实测 30 次）。
 */
public record WorldChangeSet(Map<String, ChangeSet> modules) implements ChangeSet {

  public WorldChangeSet {
    Objects.requireNonNull(modules, "modules");
    LinkedHashMap<String, ChangeSet> copy = new LinkedHashMap<>();
    for (Map.Entry<String, ChangeSet> entry : modules.entrySet()) {
      // ★ null 的键/值在**构造期**就炸（消息恰是守卫文案），不留到消费者（codec 路由、重放的 applyWorld）才炸
      //   ——LinkedHashMap 本身允许 null 键值，不拦的话 null 会静默活进落盘路径。
      copy.put(
          Objects.requireNonNull(entry.getKey(), "modules 的键"),
          Objects.requireNonNull(entry.getValue(), "modules 的值"));
    }
    modules = Collections.unmodifiableMap(copy);
  }

  /** 空的全模块变更集：分岔 revision 的变更集就是它（C13——分岔不改变世界，只增加一条边）。 */
  public static WorldChangeSet empty() {
    return new WorldChangeSet(Map.of());
  }
}
