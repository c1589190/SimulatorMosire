package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.TimeRange;

/**
 * 两阶段推进的 proposer（spec §5.2）。
 *
 * <p>★ {@link #simulate} 是**纯函数**——拿 base、吐提案，**不写状态**。这是"两阶段"的全部意义： 没有模块能在别人提案之前就把自己的改动落下去。
 *
 * <p>★ 每个参与者拿到的都是**同一份** base（C25）⇒ **调用顺序不影响结果**。
 */
public interface TimeParticipant {

  String namespace();

  TimeProposal simulate(SimulationState state, TimeRange range);
}
