package io.mosire.simos.core.advance;

import io.mosire.simos.util.state.StateRef;

/**
 * ★ **急停路径的 drain 钩子**（2026-10-13 急停协议，口径 3 = (A)「只把 drain 接进急停路径」）。
 *
 * <p>★★ **为什么是一个 SPI 而不是 Core 自己去 drain**：{@code drain} 的实现在 **app**（{@code CommandDrain}： 把 {@code
 * Trigger} 命中后 {@code Action.EnqueueUnitCommand} 这类跨模块效果落地），而 **Core 不认识 app** （铁律 4 + 模块边界）。所以"谁来
 * drain"由调用方注入，**"什么时候 drain"由 Core 保证**——这正是顺序铁律 ②→③→⑤ 里 Core 唯一能负责的那一半。
 *
 * <p>★★ **它在次序中的位置不可挪动**（用户原话：「急停的时候是**先世界里做完**，然后再报给GM的」）：
 *
 * <pre>
 * ① 跑完当天全部参与者（不许短路）→ ② 落 revision（世界做完）→ ③ drain（本钩子）
 *   → ④ 聚合当天全部 Incident → ⑤ 非空 ⇒ 停并产出 StopReport（报给 GM）
 * </pre>
 *
 * <p>所以 {@code TimeAdvance} 在**那一天的 revision 已经落地之后、{@link AdvanceOutcome.Stopped} 返回之前**调它。 ★ 不
 * drain 的后果（§4.5）：停在 tick 5 时，第 5 天 FIRED 的跨模块效果**永远悬着** —— 每停一次多一条悬空。
 *
 * <p>★ **既有断链不在本批**（口径 3=A 的范围裁剪）：{@code /api/advance} 与 {@code simos.advance} 走的**非急停**路径 仍不
 * drain（它们仍直接 {@code core.submit}），这是**有意**的，不是遗漏。
 *
 * <p>★ **默认不装**（{@link #NONE}）：没装钩子 ⇒ 急停路径**无 drain**（后果如实记在账本的"未验证/已知未修"一节），
 * 而不是把急停整条路也一起废掉——停仍然要停，报仍然要报。
 */
@FunctionalInterface
public interface StopDrain {

  /** 空实现：急停时**什么也不做**（未装配 drain 的现场；它**不**让急停失效）。 */
  StopDrain NONE = (worldRef, atTick) -> {};

  /**
   * 把跨模块效果落地（口径 3=A：只在急停路径被调）。
   *
   * @param worldRef 急停那一天的坐标（branch + revision，**已经落盘**）；drain 若又提交了新 revision，调用方无须回报 ——{@code
   *     TimeAdvance} 会在 drain 之后重读 head 作为 {@link io.mosire.simos.util.spi.StopReport#worldRef()}
   * @param atTick 停在哪一天
   */
  void drainAfterStop(StateRef worldRef, long atTick);
}
