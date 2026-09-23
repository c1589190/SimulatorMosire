package io.mosire.simos.core.command;

import io.mosire.simos.util.state.StateRef;
import java.util.List;
import java.util.Objects;

/**
 * {@code CommandBus.submitBatch} 的三种结局（"一批命令 = 一条 revision"，用户裁定：一个 tick 一条 revision，**原子**）。
 *
 * <p>★ **为什么不给 {@link CommandResult} 加一个"批"分支**：{@code CommandResult} 是**单条命令**的结局，它的三个 case 被
 * {@code CommandBus} 的日志 {@code switch}、{@code CoreSimos} 的 {@code instanceof} 等点消费（且那些 {@code
 * switch} 刻意不写 {@code default}，靠穷尽性守）。往封闭集里塞一个 {@code Batch} 会让"单条"这个词当场失真，并迫使每一处消费点都长出一个与它
 * 无关的分支。故批有自己的封闭形态（本接口）；per-command 的元素**仍复用** {@link CommandResult}（见 {@link CommandOutcome}）。
 *
 * <p>★ **不变式**：三种结局的 {@code outcomes} 与入参批次**逐位对应、条数相同**——每条命令都拿得到自己的结局，**哪怕批在跑 handler
 * 之前就被挡下**（分支不存在 / 冲突时，每条都是对应的拒 / 冲突）。
 */
public sealed interface BatchResult {

  /**
   * 整批生效：全部命令落进**同一个**新 revision。
   *
   * @param ref 批**新落**的坐标（{@code base.revision + 1}）
   * @param outcomes 每条命令的结局（全部 {@code Committed}，且 {@code ref} 相同）
   */
  record Committed(StateRef ref, List<CommandOutcome> outcomes) implements BatchResult {

    public Committed {
      Objects.requireNonNull(ref, "ref");
      outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }
  }

  /**
   * 整批未提交：至少一条被拒（handler 拒绝 / 类型未注册 / 守卫拒绝），**未落任何 revision**。
   *
   * <p>★ 哪条为什么被拒，逐条看 {@code outcomes}（真被拒的带真拒因；被接受却随整批复原的带"整批未提交"）。
   */
  record Rejected(List<CommandOutcome> outcomes) implements BatchResult {

    public Rejected {
      outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }
  }

  /**
   * 整批冲突：{@code head(branch) ≠ expectedRevision}（语义与单条 {@link CommandResult.Conflict} **同口径**）。
   *
   * @param current **真实 head**（调用方拿到它就能直接重试或分岔，不必再查一次库）
   * @param outcomes 每条命令的结局（全部 {@code Conflict(current)}）
   */
  record Conflict(StateRef current, List<CommandOutcome> outcomes) implements BatchResult {

    public Conflict {
      Objects.requireNonNull(current, "current");
      outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }
  }

  /** 批中每条命令的结局，**与入参顺序逐位对应**。 */
  List<CommandOutcome> outcomes();
}
