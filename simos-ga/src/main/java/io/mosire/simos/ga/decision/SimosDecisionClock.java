package io.mosire.simos.ga.decision;

/**
 * Simos 决策包的时间哨兵（设计书 §6.4）。
 *
 * <p>世界状态中不使用真实墙钟；AgentLib {@code ProposeRequest.deadlineEpochMs} 统一写成 {@link
 * #NO_WALL_CLOCK_DEADLINE}，本批不按 tick 自动过期。{@link PendingDecisionQueue} 的构造期只需要一个正值， 用 {@link
 * #QUEUE_DEADLINE_MILLIS}，不会参与世界时钟判定。
 */
public final class SimosDecisionClock {

  /** 包 deadline 的“无墙钟超时”哨兵（Long.MAX_VALUE）。 */
  public static final long NO_WALL_CLOCK_DEADLINE = Long.MAX_VALUE;

  /** PendingDecisionQueue 构造期正值（Long.MAX_VALUE / 2），finalizer 会把它重写成上面的哨兵。 */
  public static final long QUEUE_DEADLINE_MILLIS = Long.MAX_VALUE / 2;

  private SimosDecisionClock() {}
}
