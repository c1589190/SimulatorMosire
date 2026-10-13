package io.mosire.simos.army.stop;

import io.mosire.simos.util.spi.StopReport;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.StateRef;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>"有急停的推进"端口</b>（2026-10-13 急停协议）：工具 {@code simos.emergency.advance} 用它在推进的同时拿到 {@link
 * StopReport}。
 *
 * <p>★★ <b>为什么要一个端口</b>：{@code AdvanceTime} / {@code AdvanceOutcome} 住 <b>core</b>，而 {@code
 * simos-army} 的允许依赖<b>不含 core</b>（{@code AGENTS.md} §三：util+map+unit+social+gov）⇒ 住 army
 * 的工具<b>编译不过</b> core 类型。故本接口只用 util 类型（{@code BranchId} / {@code StateRef} / {@link
 * StopReport}）描述"推进的四种结局"，实现由 <b>app</b>（组合根）注入—— {@code Shell} 里那一行 {@code
 * coreSimos.submitAdvance(...)} 就是它唯一的实现。
 *
 * <p>★★ <b>为什么本批需要它（如实记，这是对设计书 §7.2 的一处"加做"）</b>：既有两个推进入口 （{@code /api/advance} 与 {@code
 * simos.advance}）走的仍是 {@code core.submit} ⇒ 走那条路的急停在返回值里被折成 {@code Committed(atTick)}，{@code
 * StopReport} <b>无处安放</b>（设计书 §4.5 / A 的账本 §8.2 第 1 条，口径 3 = A 的<b>有意</b>范围裁剪）。本批要验
 * P5/P6（原因系统可查、急停路径 drain 生效），就必须有<b>至少一个入口</b>真的走 {@code CoreSimos.submitAdvance(cmd)}；控制方
 * 2026-10-13 明确要求 B 在自己负责的那一处新增这个入口，且<b>不许</b>顺手改既有两个入口的行为。 ⇒ 新增本端口 + 一条新工具，既有两个入口<b>一个字未动</b>。
 *
 * <p>★ <b>四种结局与 core 的 {@code AdvanceOutcome} 一一对应</b>（这里只是"去掉 core 类型"的值视图）。
 */
@FunctionalInterface
public interface AdvanceStopPort {

  /**
   * 推进 {@code [from, to]}（左闭右开，与 {@code TimeRange} 同口径）。
   *
   * @param branch 分支
   * @param expectedRevision 乐观并发 base revision
   * @param from 区间起点 tick（左闭）
   * @param to 区间终点 tick（右开）
   * @return 四种结局之一（急停 = {@link Outcome.Stopped}）
   */
  Outcome advance(BranchId branch, long expectedRevision, long from, long to);

  /** 一次推进的结局（core 的 {@code AdvanceOutcome} 在 army 侧的值视图）。 */
  sealed interface Outcome {

    /** 整段推进完：{@code ref} = 最后一天的新坐标。 */
    record Committed(StateRef ref) implements Outcome {

      public Committed {
        Objects.requireNonNull(ref, "ref");
      }
    }

    /** ★ 急停：世界<b>已推进到 {@code report.atTick()} 那天、并且已 drain</b> 才停（用户原话：「急停的时候是先世界里做完，然后再报给GM的」）。 */
    record Stopped(StopReport report) implements Outcome {

      public Stopped {
        Objects.requireNonNull(report, "report");
      }
    }

    /** 被拒（该日不留 revision）。 */
    record Rejected(String reason) implements Outcome {

      public Rejected {
        Objects.requireNonNull(reason, "reason");
      }
    }

    /** 冲突：{@code current} 是**真实 head**（不是调用方的期望值）。 */
    record Conflict(StateRef current) implements Outcome {

      public Conflict {
        Objects.requireNonNull(current, "current");
      }
    }
  }

  /** 本端口的急停报告（非急停 ⇒ 空）。 */
  static Optional<StopReport> reportOf(Outcome outcome) {
    return outcome instanceof Outcome.Stopped stopped
        ? Optional.of(stopped.report())
        : Optional.empty();
  }
}
