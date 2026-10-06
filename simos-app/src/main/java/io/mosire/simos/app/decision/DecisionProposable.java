package io.mosire.simos.app.decision;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **可被 propose 的工具**（D2 决策包计划 §3.3）：一个目标工具的**预览入口 + 目标提取配置**。
 *
 * <p>★ 这是"决策人填真实工具名 + JSON 参数"的落地形态：catalog 为初始清单每条注册一个本类型的适配器，持有**现场构造**的 {@link AgentTool}
 * 实例；{@code preview} 用它跑真（{@code preview=true}）路径，{@code targets} 从 args + preview 提取跨命名空间目标。未在
 * catalog 的工具 ⇒ propose 具名拒。
 *
 * <p>★ {@code skipKeys}/{@code createsUnit} 是**每条描述自己的配置**：创建型工具的新单位 id（{@code newUnitId}）不是既有
 * 资源、不能按"将要成为的目标"去判越界（那会把合法提议全部拒掉），故显式跳过、改用 {@code at}/{@code regionId} 目标。
 */
public interface DecisionProposable {

  /** 目标工具的全局工具名（{@code simos.*}）。 */
  String toolName();

  /** 现场构造的目标工具实例（只跑 preview，绝不 apply）。 */
  AgentTool tool();

  /** 目标提取时**整键跳过**的字段（如创建型工具的新 id 常量字段）。 */
  default Set<String> skipKeys() {
    return Set.of();
  }

  /** 是否是**创建型**工具：为真时，目标提取还会跳过与参数/预览里 {@code newUnitId} 逐字相等的单位 id （防止"新单位尚不存在 ⇒ 判不了"被误报越界）。 */
  default boolean createsUnit() {
    return false;
  }

  /** 跑目标工具的**真预览**（{@code preview=true}，不落盘）并返回预览视图。 */
  default Map<String, Object> preview(SimulationState state, Map<String, Object> args) {
    throw new UnsupportedOperationException("DecisionProposable 未实现 preview: " + toolName());
  }

  /** 从参数 + 预览提取跨命名空间目标（用于拟稿期 scope 校验）。 */
  default List<CommandTarget> targets(
      SimulationState state, Map<String, Object> args, Map<String, Object> preview) {
    throw new UnsupportedOperationException("DecisionProposable 未实现 targets: " + toolName());
  }
}
