package io.mosire.simos.app.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code simos.command.submitBatch}：{@link CommandSubmitTool} 的**批版兄弟**（spec §7.1 写工具）—— 把"提交单个命令"
 * 扩成"一次提交一批命令"，其余形制逐条照它：同一条 {@link CoreSimos} 写入口、同一份 {@link ToolSupport#ALL_WRITE}
 * 资源面、同一种敏感审批档、同一套结局折叠词汇。
 *
 * <p>★ <b>薄</b>（铁律 4）：本类只做三件事 —— <b>载荷解析 → {@link CoreSimos#submitBatch} → 结局折叠</b>；不写领域逻辑、不预检命令
 * handler 会拒什么（预检能被别的路径绕过 ⇒ 是装饰）、不重试、不吞结局。
 *
 * <p>★★ <b>批的语义以代码为准</b>（回代码核过，不照抄任务书摘要）：{@link
 * io.mosire.simos.core.command.CommandBus#submitBatch(java.util.List)} ——
 *
 * <ul>
 *   <li><b>一批 = 一条 revision</b>：整批在同一把 {@code commitLock} 内跑完（复查 head → 逐条 handler → 从**累积候选状态**
 *       派生**一个** {@code WorldChangeSet} → 落**一行** revision），<b>不叠加</b>各命令的变更集；
 *   <li><b>原子</b>：任一命令被拒 ⇒ {@link BatchResult.Rejected}，<b>一条 revision 都不落</b>；已被接受、随整批复原的命令也报
 *       {@code Rejected}（理由写明"整批未提交"）；
 *   <li><b>批级共享坐标</b>：同批必须共享 {@code branch} 与 {@code expectedRevision}（{@code *} 不一致 ⇒ {@code
 *       IllegalArgumentException}）⇒ 载荷把它们放在<b>批级</b>、不放进每条命令（与 {@code
 *       CoreSimosToolWritePort#submitBatchWithReceipt} 的端口入参同形）；空批也被 {@code submitBatch} 具名拒。
 * </ul>
 *
 * <p>★ <b>信封身份口径</b>照 {@link
 * io.mosire.simos.app.tools.CoreSimosToolWritePort#submitBatchWithReceipt}：<b>批级一个 {@code
 * batchId}（= 批内所有信封的 {@code correlationId}）+ 逐条命令一个 {@code commandId}</b>，{@code initiator}
 * 取注入值（发起者属于装配面，不吃工具载荷）。
 *
 * <p>★ <b>敏感写</b>：{@code spec().sensitive()=true} + {@link ToolGate.Ask}（classKey = 工具名，summary
 * 只含条数/分支/期望 revision）；{@code roles()={GM}} 与 {@code simos.command.submit} 同档（GM 面的通用写，走 GM
 * 的自动放行链）。
 */
public final class CommandSubmitBatchTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.command.submitBatch";

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CoreSimos 是本工具的唯一写入口（只调 submitBatch），非内部表示外泄；写面仍受权限/审批闸约束")
  public CommandSubmitBatchTool(CoreSimos core, String initiator, String mapId) {
    this.core = core;
    this.initiator = initiator;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "提交一批领域命令（通用信封批，一批 = 一条 revision、整批原子）：commands[{type,payloadJson}] + branch +"
        + " expectedRevision";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> itemProps = new LinkedHashMap<>();
    itemProps.put("type", ToolSupport.prop("string", "命令类型（见 simos.command.catalog）"));
    itemProps.put(
        "payloadJson", ToolSupport.prop("string", "载荷 JSON 文本（见 catalog 的 payloadHints；缺省 {}）"));
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", "object");
    item.put("properties", itemProps);
    item.put("required", List.of("type"));
    Map<String, Object> commandsProp =
        ToolSupport.prop("array", "按执行顺序排好的命令数组（至少一条；与 simos.command.submit 的单条参数同义）");
    commandsProp.put("items", item);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("commands", commandsProp);
    props.put("branch", ToolSupport.prop("string", "分支名（★ 全批共享一个坐标）"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发；★ 全批共享）"));
    return ToolSupport.schema(props, List.of("commands", "branch", "expectedRevision"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "提交领域命令批 n="
            + batchSize(args)
            + " branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String batchId = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAllWrite(context, mapId);
      Map<String, Object> args = context.arguments();
      BranchId branch = new BranchId(ToolSupport.requiredText(args, "branch"));
      RevisionId expectedRevision =
          new RevisionId(ToolSupport.requiredLong(args, "expectedRevision"));
      List<CommandEnvelope> batch = envelopes(args, batchId, branch, expectedRevision);
      return fold(core.submitBatch(batch), batch, batchId);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与 CommandSubmitTool / AbstractNarrowWriteTool 同一条（T10）：资源拒因必须原样逃到 ToolCallAuthorizer 的边界，
      //   折成 TOOL_ERROR 会让模型看到"命令提交失败"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令批提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）：与 {@code simos.command.submit} 同档。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }

  // ── 载荷解析（薄：只把 commands[] 拆成信封；合法性一律留给命令总线与各域 handler）──────────────────

  /** 批内一条命令的载荷（{@code {type, payloadJson}}，与 {@code simos.command.submit} 的两个同名参数同义）。 */
  private record Entry(String type, String payloadJson) {}

  /**
   * 组批：{@code commands[]} → 按序排好的信封。批级 {@code branch}/{@code expectedRevision} 由**工具入参**给（不是每条命令
   * 自己给）—— 因为 {@code submitBatch} 的硬约束就是"同批共享这两个坐标"。
   */
  private List<CommandEnvelope> envelopes(
      Map<String, Object> args, String batchId, BranchId branch, RevisionId expectedRevision) {
    Object raw = args.get("commands");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("参数 commands 必填且为非空数组（一批 = 一条 revision，至少要有一条命令）");
    }
    List<CommandEnvelope> batch = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      Entry entry = entry(list.get(i), i);
      // ★ 批级 correlationId + 逐条 commandId（口径照 CoreSimosToolWritePort.submitBatchWithReceipt）。
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              expectedRevision,
              entry.type(),
              entry.payloadJson()));
    }
    return batch;
  }

  /** 解析 {@code commands[i]}（形状不对 ⇒ 具名 BAD_REQUEST，带下标；不静默跳过、不猜缺省 type）。 */
  private static Entry entry(Object item, int index) {
    if (!(item instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 commands[" + index + "] 必须是对象 {type, payloadJson}");
    }
    Object rawType = map.get("type");
    if (!(rawType instanceof String type) || type.isBlank()) {
      throw new IllegalArgumentException("参数 commands[" + index + "].type 必填且为非空文本");
    }
    Object rawPayload = map.get("payloadJson");
    if (rawPayload == null) {
      return new Entry(type, "{}");
    }
    if (!(rawPayload instanceof String payload) || payload.isBlank()) {
      throw new IllegalArgumentException("参数 commands[" + index + "].payloadJson 若给出必须是非空文本");
    }
    return new Entry(type, payload);
  }

  /**
   * 审批摘要里的条数（★ 只读、不解析载荷）：{@link io.mosire.simos.ga.tools.ToolClassAudit} 的探针上下文给的是<b>空
   * arguments</b> ⇒ 本方法必须缺省安全、<b>绝不抛</b>（gate() 抛 = 装配期分区审计当场炸）。
   */
  private static String batchSize(Map<String, Object> args) {
    Object raw = args.get("commands");
    return raw instanceof List<?> list ? String.valueOf(list.size()) : "?";
  }

  // ── 结局折叠（三种结局的词汇照 CommandSubmitTool 的单条口径 + 既有批工具的逐条拒因）──────────────────

  /**
   * 批三结局 → {@link ToolResult}：{@code Committed → ok}（带新坐标 + 批内 id），{@code Conflict/Rejected →
   * error}。
   */
  private static ToolResult fold(BatchResult result, List<CommandEnvelope> batch, String batchId) {
    if (result instanceof BatchResult.Committed committed) {
      // ★ 复用公开构造块 committedView（result/ref/commandId/correlationId 四个字段只有一处拼装），再补批级信息。
      Map<String, Object> view = ToolSupport.committedView(committed.ref(), batchId, batchId);
      view.put("commandCount", batch.size());
      view.put("commandIds", commandIds(batch));
      return ToolSupport.ok(view);
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("result", "conflict");
      view.put("current", ToolSupport.stateRef(conflict.current()));
      view.put("commandId", batchId);
      view.put("correlationId", batchId);
      view.put("commandCount", batch.size());
      return ToolResult.error("CONFLICT", ToolSupport.json(view));
    }
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "rejected");
    view.put("commandId", batchId);
    view.put("correlationId", batchId);
    view.put("commandCount", batch.size());
    view.put("reason", rejectionReason(rejected));
    view.put("commands", rejectionRows(batch, rejected));
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }

  /** 逐条命令的 id（保序；与 {@code BatchResult.outcomes()} 逐位对应）。 */
  private static List<String> commandIds(List<CommandEnvelope> batch) {
    List<String> ids = new ArrayList<>(batch.size());
    for (CommandEnvelope envelope : batch) {
      ids.add(envelope.commandId());
    }
    return ids;
  }

  /** 整批被拒时的人话（逐条结局里真正被拒的拒因；口径照 {@code WorldgenInitializeTool#rejectionReason}）。 */
  private static String rejectionReason(BatchResult.Rejected rejected) {
    List<String> reasons = new ArrayList<>();
    for (CommandOutcome outcome : rejected.outcomes()) {
      if (outcome.result() instanceof CommandResult.Rejected rejection) {
        reasons.add(outcome.command().type() + ": " + rejection.reason());
      }
    }
    return reasons.isEmpty() ? "整批被拒（无逐条拒因）" : String.join("；", reasons);
  }

  /** 逐条拒因行（下标 + 类型 + 理由；形制照 {@code RegionSeedTool} 的 {@code submission.commands}）。 */
  private static List<Map<String, Object>> rejectionRows(
      List<CommandEnvelope> batch, BatchResult.Rejected rejected) {
    List<CommandOutcome> outcomes = rejected.outcomes();
    List<Map<String, Object>> rows = new ArrayList<>(outcomes.size());
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", i);
      row.put("type", batch.get(i).type());
      CommandResult outcome = outcomes.get(i).result();
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    return rows;
  }
}
