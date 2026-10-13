package io.mosire.simos.core.advance;

import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.util.spi.StopReport;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次时间推进的**四种结局**（2026-10-13 急停协议，约束设计书 §4.3 的 Q-C）。
 *
 * <p>★★ **为什么不是 {@link CommandResult} 加一种**：{@code CommandResult} 的三种封闭是**铁律 2 的公共契约** （{@code
 * Committed}/{@code Rejected}/{@code Conflict}，不变量 I3）——往里加"急停"这一支，等于让**每一条命令**的消费者
 * 都去处理一种只属于时间推进的结局。急停走**本类型**，在 Shell/GUI/MCP 层暴露；{@code CommandResult} 一个字未动。
 *
 * <ul>
 *   <li>{@link Committed} —— 整段 range 全部推进完（逐日落 revision，每天一条）。
 *   <li>{@link Stopped} —— ★ **急停**：某一天有模块报了突发事件 ⇒ 世界**已推进到那天并且已 drain**，然后才停
 *       （用户原话：「急停的时候是**先世界里做完**，然后再报给GM的」）。带 {@link StopReport} 与那一天落下的坐标。
 *   <li>{@link Rejected} —— 被拒（语法/连续性/校验/参与者装配错误），**该日不留 revision**。
 *   <li>{@link Conflict} —— 冲突，{@code current} 是**真实 head**：① 乐观并发（期望坐标被别人推走）；② 单日写-写冲突且**已落过 ≥1
 *       天**（世界停在冲突前一天）。两者的区分看事件链：② 在冲突日前必有本命令的 {@code simos.timeline.conflict}。
 * </ul>
 *
 * <p>★ **三种非急停结局与 {@code CommandResult} 一一对应**：{@link #commandResult()} 把本结局折回铁律 2 的公共契约 ——{@code
 * Stopped} 折成它**落下的那一天**的 {@link CommandResult.Committed}（那确实是一段真实推进的终点，不变量 I4）。
 */
public sealed interface AdvanceOutcome {

  /** 整段 range 推进完：{@code committed} 给出**最后一天**的新坐标。 */
  record Committed(CommandResult.Committed committed) implements AdvanceOutcome {

    public Committed {
      Objects.requireNonNull(committed, "committed");
    }
  }

  /**
   * ★ 急停：世界**已推进到 {@code report.atTick()} 那天并且已 drain**，然后才停。
   *
   * @param report 原因系统（{@code atTick} + 真实坐标 + 聚合的全部原因）
   * @param committed 那一天落下的 revision（世界真的停在它上面，可直接续推）
   */
  record Stopped(StopReport report, CommandResult.Committed committed) implements AdvanceOutcome {

    public Stopped {
      Objects.requireNonNull(report, "report");
      Objects.requireNonNull(committed, "committed");
    }
  }

  /** 被拒（该日不留 revision）：{@code rejected.reason()} 是给人/给事件看的文本。 */
  record Rejected(CommandResult.Rejected rejected) implements AdvanceOutcome {

    public Rejected {
      Objects.requireNonNull(rejected, "rejected");
    }
  }

  /** 冲突：{@code conflict.current()} 是**真实 head**（不是调用方的期望值，也不是被拒的期望值）。 */
  record Conflict(CommandResult.Conflict conflict) implements AdvanceOutcome {

    public Conflict {
      Objects.requireNonNull(conflict, "conflict");
    }
  }

  /**
   * 折回铁律 2 的公共契约（既有调用方拿 {@code CommandResult} 的那条路：{@code TimeAdvance.run}）。
   *
   * <p>★ {@code Stopped} 折成它**落下的那一天**的 {@code Committed} —— 世界确实提交到了 {@code atTick}。 ★ **代价如实记**：
   * 这条折法**丢掉了 {@link StopReport}**（{@code CommandResult} 里没有它的位置）。要看急停原因，调用方必须走 {@link
   * TimeAdvance#advance}（{@code CoreSimos.submitAdvance}）那条口。
   */
  default CommandResult commandResult() {
    return switch (this) {
      case Committed committed -> committed.committed();
      case Stopped stopped -> stopped.committed();
      case Rejected rejected -> rejected.rejected();
      case Conflict conflict -> conflict.conflict();
    };
  }

  /** 是否急停。 */
  default boolean stopped() {
    return this instanceof Stopped;
  }

  /** 急停的原因系统（非急停 ⇒ 空）。 */
  default Optional<StopReport> stopReport() {
    return this instanceof Stopped stopped ? Optional.of(stopped.report()) : Optional.empty();
  }
}
