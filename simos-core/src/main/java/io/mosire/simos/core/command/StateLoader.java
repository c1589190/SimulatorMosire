package io.mosire.simos.core.command;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;

/**
 * "给定坐标，给我那个坐标上的完整状态"（裁定 34）：{@code CommandBus} 据此在执行 handler 前装配 {@code state}。
 *
 * <p>★ **为什么是注入而不是直接依赖 {@code Replay}**：装配状态的唯一来源确实是 {@code Replay.replay(StateRef)} （Task 8 / spec
 * §6.4），但 ① 直接依赖会让 Task 9 的每条用例都被迫搭起 store + timeline + checkpoint 一整套才能验一次 转发；② {@code CommandBus}
 * 需要的只是"给定坐标给状态"这一件事，不是重放的全部能力。
 *
 * <p>★ **与 {@link AdvanceRoute} 的区别（别混为一谈）**：{@code AdvanceRoute} 是**解环**（{@code 9 ↔ 12}
 * 构成闭环，非注入不可）；本条**不是解环**（{@code 9 → 8} 本就无环），是**为可测性与职责分离选的注入**。 理由不同，结论同形。
 *
 * <p>★ 装配时传 {@code replay::replay}（Task 13）。
 */
@FunctionalInterface
public interface StateLoader {

  /**
   * @param ref 要取的坐标。★ {@code CommandBus} 传的是 {@code (branch, head(branch))}——**当前 tip**，不是 信封上的
   *     {@code expectedRevision}（两者在入口检查通过后相等，但语义是"现在"）
   */
  SimulationState load(StateRef ref);
}
