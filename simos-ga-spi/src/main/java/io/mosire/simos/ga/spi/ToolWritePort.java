package io.mosire.simos.ga.spi;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具面的**窄写口**：替代今天 162 个工具类直连 {@code CoreSimos}（设计书 §3 的 F5 / §4.2 的 "ToolWritePort（窄写口）"一行）。
 *
 * <p>★ <b>现状（F5，2026-10-12 回代码核实）</b>：162 个工具类直连 {@code CoreSimos}，实际只用到 5 个方法 —— {@code
 * submit(Command)} / {@code submitBatch(List<CommandEnvelope>)} / {@code head(BranchId)} / {@code
 * submitRestore(...)} / {@code replay(StateRef)}（另加读侧的 {@code revisions(BranchId)}）。
 * 本接口把它们收成"工具真正需要的那点面"：提交（单条 / 批）+ 写前读（head / replay）。
 *
 * <p>★★ <b>只用 util 类型，绝不引 core</b>（设计书 §4.2 末段 / 不变量 I-G2）：{@code core} 的 test scope 依赖 {@code
 * map}/{@code social}/{@code unit} ⇒ 一旦本接口出现 {@code CommandEnvelope} / {@code CommandResult} /
 * {@code BatchResult} / {@code WorldChangeSet} 这类 core 类型，要依赖本模块写工具的 域模块就会把 core 拖进依赖图，{@code map →
 * ga-spi → core → map(test)} **当场成环**（Maven 建图期直接拒）。 ⇒ 结局类型由本接口**自己定义**（{@link Outcome} / {@link
 * BatchOutcome}），语义与 core 的 {@code CommandResult} / {@code BatchResult}
 * **一一对应**（那是"镜像"，不是"改语义"：三种结局的字段与含义逐条同形）。
 *
 * <p>★ <b>本批（B1）只定义接口 + 适配规则，不写适配器</b>：{@code CoreSimos → ToolWritePort} 的适配器属 B2 的 {@code
 * simos-ga}（它才允许依赖 core）。本文件里**没有**任何实现。
 *
 * <h2>适配规则（B2 的实现方按此写适配器）</h2>
 *
 * <p>每次调用落成一个 core 的 {@code CommandEnvelope}，字段来源：
 *
 * <table>
 *   <caption>端口入参 → 命令信封</caption>
 *   <tr><th>信封字段</th><th>来源</th></tr>
 *   <tr><td>{@code branch}</td><td>端口入参（工具从自己的载荷取；缺省分支见
 *       {@link ToolResources#DEFAULT_BRANCH}）</td></tr>
 *   <tr><td>{@code expectedRevision}</td><td>{@code submit} = {@code command.expectedRevision()}；
 *       {@code submitBatch} = 批级入参（同批共享）</td></tr>
 *   <tr><td>{@code type} / {@code payloadJson}</td><td>端口入参；{@code payloadJson} **逐字节转交**
 *       （C26：不 parse、不 trim、不 re-serialize）</td></tr>
 *   <tr><td>{@code commandId}</td><td>**工具不给**：装配面生成（实现方）</td></tr>
 *   <tr><td>{@code correlationId}</td><td>**工具不给**：装配面按 C22 缺省（单命令链 = {@code commandId}）</td></tr>
 *   <tr><td>{@code initiator}</td><td>**工具不给**：装配面从调用者身份取</td></tr>
 * </table>
 *
 * <p>★ <b>为什么 {@code commandId} / {@code initiator} 不由工具给</b>：那是**装配面**的事（谁在调、 调用链是什么）；让 176
 * 个工具各生成一份 id/发起者 = 176 个拼写点，且与今天 {@code AbstractNarrowWriteTool} "信封与命令 id 的**唯一生成点**"这条既有口径直接冲突 ⇒
 * 适配器接手这一件事，工具只给"我这条命令是什么"。
 *
 * <p>★ <b>为什么 {@code branch} 是每次调用的入参</b>：写工具的 {@code branch} 来自**载荷** （{@code
 * ToolSchema#targetProps()} 的 {@code branch} 属性），不是端口实例的状态——同一个端口实例会被
 * 不同分支的调用复用，把它做进构造器就等于给工具开了一条"忘了传分支"的静默路径。
 *
 * <p>★ <b>端口没有的两件（如实记，B2/B3 需要时另裁）</b>：
 *
 * <ul>
 *   <li>{@code submitRestore(BranchId, RevisionId, String initiator, WorldChangeSet)}（1 处 = {@code
 *       VoidAdjudicationTool}）：它的载荷类型 {@code WorldChangeSet} 与返回的 {@code CommandResult} **都是 core
 *       类型** ⇒ 放进本接口 = 把 core 拖进 ga-spi（破 I-G2）；
 *   <li>{@code revisions(BranchId)}（返回 {@code List<RevisionRow>}，同为 core 类型；工具面只有 {@code
 *       TimelineRevisionsTool} 这条读工具在用）。
 * </ul>
 *
 * <p>★ <b>失败语义（不许放宽）</b>：{@link #submit} / {@link #submitBatch} 的三种结局**就是** core 的三种 结局（{@code
 * Committed} / {@code Rejected} / {@code Conflict}）——适配器**不得**把拒/冲突折成异常、
 * 也不得把异常折成拒（那会改掉工具面今天逐字不变的拒绝码与文案，破 I-G1）。{@link #replay} 保留异常抛出 （见该方法注）。
 */
public interface ToolWritePort {

  /**
   * 单条命令的三种结局（**镜像** core 的 {@code CommandResult}，但不引 core 类型）。
   *
   * <p>★ {@code Conflict} 带的是**真实 head**（含分支），调用方拿到它就能直接重试或分岔；分支不存在时 **没有 head 可报**，走 {@link
   * Rejected} 而不是编一个坐标出来（与 core 逐条同口径）。
   */
  sealed interface Outcome {

    /** 提交成功：给出**新 revision** 的坐标。 */
    record Committed(StateRef ref) implements Outcome {

      public Committed {
        Objects.requireNonNull(ref, "ref");
      }
    }

    /** 命令被拒（handler 拒绝、类型未注册、分支不存在）：理由是给人/给事件看的文本。 */
    record Rejected(String reason) implements Outcome {

      public Rejected {
        Objects.requireNonNull(reason, "reason");
      }
    }

    /** 乐观并发冲突：{@code current} 是**真实 head**。 */
    record Conflict(StateRef current) implements Outcome {

      public Conflict {
        Objects.requireNonNull(current, "current");
      }
    }
  }

  /**
   * 一批命令的三种结局（**镜像** core 的 {@code BatchResult}）；不变式逐条相同： {@link #outcomes()}
   * 与入参批次**逐位对应、条数相同**——每条命令都拿得到自己的结局， **哪怕批在跑 handler 之前就被挡下**（分支不存在 / 冲突时，每条都是对应的拒 / 冲突）。
   */
  sealed interface BatchOutcome {

    /** 批中每条命令的结局，**与入参顺序逐位对应**。 */
    List<Outcome> outcomes();

    /** 整批生效：全部命令落进**同一个**新 revision。 */
    record Committed(StateRef ref, List<Outcome> outcomes) implements BatchOutcome {

      public Committed {
        Objects.requireNonNull(ref, "ref");
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
      }
    }

    /** 整批未提交：至少一条被拒，**未落任何 revision**（逐条拒因看 {@link #outcomes()}）。 */
    record Rejected(List<Outcome> outcomes) implements BatchOutcome {

      public Rejected {
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
      }
    }

    /** 整批冲突：{@code head(branch) ≠ expectedRevision}（{@code current} = **真实 head**）。 */
    record Conflict(StateRef current, List<Outcome> outcomes) implements BatchOutcome {

      public Conflict {
        Objects.requireNonNull(current, "current");
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
      }
    }
  }

  /**
   * 批中的一条命令：**只有** {@code type} 与 {@code payloadJson}——分支与期望版本是**批级**的 （同一批必须共享坐标，core 的硬约束：批内坐标不一致
   * ⇒ {@code IllegalArgumentException}）。
   *
   * <p>★ {@code commandId} / {@code correlationId} / {@code initiator} 由适配器补（见接口类注）。
   */
  record Request(String type, String payloadJson) {

    public Request {
      Objects.requireNonNull(type, "type");
      if (type.isEmpty()) {
        throw new IllegalArgumentException("type 不得为空串");
      }
      Objects.requireNonNull(payloadJson, "payloadJson");
    }
  }

  /**
   * 一次提交的**回执**：装配面生成的命令 id + 三结局之一。
   *
   * <p>★★ <b>为什么必须有这个回执</b>（2026-10-12 B3 试点新增；偏离与理由记在台账 {@code
   * .superpowers/sdd/2026-10-12-b3-pilot-map/impl-ledger.md}）：工具结果的视图里带 {@code commandId} / {@code
   * correlationId} 两个字段（app 侧 {@code ToolSupport.committedView} 的既有对外载荷），而按本接口类注的适配规则 **命令 id
   * 由装配面生成**（工具不给）⇒ 只回 {@link Outcome} 的话，工具**拿不到**自己这条命令的真实 id： 要么报不出这两个字段（改载荷 = 破
   * I-G1），要么自己另生成一个（报出去的 id 与落盘信封的 id 不是同一个 = 静默地对不上）。回执把"适配器生成 id"与"工具要报 id"两件事同时钉住。
   *
   * @param commandId 本次提交的命令 id（= correlationId 的缺省来源，见接口类注的适配规则）
   * @param outcome 三结局之一
   */
  record Receipt(String commandId, Outcome outcome) {

    public Receipt {
      Objects.requireNonNull(commandId, "commandId");
      Objects.requireNonNull(outcome, "outcome");
    }
  }

  /**
   * 提交一条命令，并把装配面生成的**命令 id** 一并回报（见 {@link Receipt}）。
   *
   * <p>★ {@code command} 只提供 {@link Command#expectedRevision()}（乐观并发坐标）——{@code type} / {@code
   * payloadJson} 由工具单独给：它们今天也是工具拼进信封的，且工具**不该**为了调一次写口去实现一个 core 侧的命令类型。
   *
   * @param branch 写入目标分支（工具从载荷取）
   * @param command 只承载期望版本（{@code () -> new RevisionId(n)} 即可）
   * @param type 命令类型，形如 {@code <namespace>.<Command>}（如 {@code "unit.RenameUnit"}）
   * @param payloadJson 不透明载荷（逐字节转交）
   * @return 命令 id + 三种结局之一（**不抛**"拒 / 冲突"）
   */
  Receipt submitWithReceipt(BranchId branch, Command command, String type, String payloadJson);

  /**
   * 只要结局、不要 id 的形态（{@link #submitWithReceipt} 的投影）。
   *
   * <p>★ 要把 {@code commandId} 报进结果的工具调 {@link #submitWithReceipt}；不需要的那些照旧调本方法。
   */
  default Outcome submit(BranchId branch, Command command, String type, String payloadJson) {
    return submitWithReceipt(branch, command, type, payloadJson).outcome();
  }

  /**
   * **原子批提交**（"一批命令 = 一条 revision"）：要么全成、要么全不成。
   *
   * <p>★ 批内所有请求共享 {@code branch} 与 {@code expectedRevision}（core 的硬约束）；{@code batch} 为空、或批内坐标不一致 ⇒
   * 与 core 同口径当场抛（那是调用点的装配错误，不是世界的拒绝）。
   */
  BatchOutcome submitBatch(BranchId branch, RevisionId expectedRevision, List<Request> batch);

  /**
   * 分支 head（写前读现状：工具据此自己判"我给的期望版本是不是当前 head"）。
   *
   * <p>★ 分支不存在 ⇒ {@link Optional#empty()}，**不是**抛异常（与 core 同口径：GUI/查询层问
   * "这个分支现在到哪"时，"还没有这个分支"是正常答案）。
   */
  Optional<RevisionId> head(BranchId branch);

  /**
   * 重放任一坐标，返回该坐标的完整状态（写前读现状 / 创世读基线）。
   *
   * <p>★★ <b>这里**不给** {@code Optional}</b>：core 的 {@code replay} 在"坐标不在时间线上 / 信封变更集
   * 读不出"时**抛异常**（父链中断、无可用 checkpoint 亦然）——那是**失败**，不是"没有"。折成 {@code Optional.empty()}
   * 会把故障静默成"空世界"，属**放宽**；而现有工具点的行为是让异常逃到管线折成 错误（I-G1 的"拒绝语义逐字不变"）⇒ 端口**原样抛**，缺省值只能由调用点自己写（{@code
   * orElseThrow} 是调用点的决定，不是契约的静默兜底）。
   */
  SimulationState replay(StateRef ref);
}
