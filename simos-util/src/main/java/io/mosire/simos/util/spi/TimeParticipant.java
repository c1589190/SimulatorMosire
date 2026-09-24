package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.TimeRange;

/**
 * 两阶段推进的 proposer（spec §5.2）。
 *
 * <p>★ {@link #simulateWorld} 是**纯函数**——拿 base、吐提案，**不写状态**。这是"两阶段"的全部意义： 没有模块能在别人提案之前就把自己的改动落下去。
 *
 * <p>★ 每个参与者拿到的都是**同一份** base（C25）⇒ **调用顺序不影响结果**。
 *
 * <p>★★ **单切片与多切片二选一实现**（2026-09-24 日制裁定的通用提案协议，POLITICAL_ECONOMY_DESIGN.md §9）：
 *
 * <ul>
 *   <li>只改自己一个模块的参与者：照旧实现 {@link #simulate}（窄签名没变，既有模块一行都不用改）；
 *   <li>一次推进要改多个模块的参与者（日结算协调器）：实现 {@link #simulateWorld} 交 {@link WorldTimeProposal}， 不必实现 {@code
 *       simulate}（默认实现会抛，指路到 {@code simulateWorld}）。
 * </ul>
 *
 * <p>Core 只调 {@link #simulateWorld}；两种实现形态在 Core 眼里没有区别（它不认识领域类型，只认识 namespace 与 codec）。{@code
 * namespace()} 仍是**参与者身份**（构造期排序键 + 事件里的参与者名）：多切片参与者给一个稳定的短名 （如 {@code "economy"}），它**不需要**有同名
 * codec——校验按 {@code moduleChanges} 的每个键走。
 */
public interface TimeParticipant {

  /** 参与者身份（构造期排序键；多切片参与者不必有同名 codec）。 */
  String namespace();

  /**
   * 单切片提案。★ 只改一个模块的参与者实现它；多切片参与者应改实现 {@link #simulateWorld}。
   *
   * @throws UnsupportedOperationException 两类都没实现（装配错误，早炸早发现）
   */
  default TimeProposal simulate(SimulationState state, TimeRange range) {
    throw new UnsupportedOperationException(
        "该参与者未实现 simulate（多切片参与者应覆写 simulateWorld）: " + namespace());
  }

  /** 多切片提案的入口。默认把 {@link #simulate} 的单切片结果包成单模块提案（既有模块零改动地兼容）。 */
  default WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    return WorldTimeProposal.single(simulate(state, range));
  }
}
