package io.mosire.simos.army.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.army.tools.ArmyToolViews;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code simos.army.startCombat}（阶段 D4 / 用户设计 D-009 补裁 + D-010 + D-012，2026-10-02）：GM-only
 * 组合工具——**让单位进入交战**： 一批（一条 revision）= 一条 {@code army.RecordCombat}（建记录 + 初始阶段）+ 每个参与单位一条 {@code
 * unit.SetStateDescription}（状态链接）。
 *
 * <p>★★ <b>为什么是一次批写多切片的组合工具</b>：记录在 {@code army} 命名空间、状态链接在 {@code unit} 命名空间，单条命令只能落一个；本工具走注入的
 * {@link ToolWritePort#submitBatchWithReceipt}（同一批 + 同 branch/expectedRevision ⇒ 一批 = 一条
 * revision，原子）。 preview 一个字节都不写。
 *
 * <p>★★ <b>口径（写进报告给测试代理）</b>：{@code state="combat"}、{@code address="army:combat.<id>"}、初始阶段由
 * handler 合成为 {@code id="start"}；"自定义交战状态"的自由文本落在记录的 {@code kind}（"轰城" = {@code
 * kind="轰城"}，不另开攻城命令）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：它建的是战果记录；工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}（那两处认的是三条 army.* 命令）。
 *
 * <p>★ <b>资源声明</b>：只写 {@code army}/{@code unit} 两个命名空间（{@link ResourcePolicy#UNRESTRICTED}；GM 侧两片
 * unlimited），与批内两条命令 逐条对齐。
 *
 * <p>★ <b>参数</b>：{@code combatId(必填), q(必填), r(必填), kind(必填), participants[unitId...](必填，至少一个),
 * text(必填), preview?(缺省 true), branch?, expectedRevision?(preview=false 必填)}。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错 / id 已存在 / 参与单位不存在 / 记录 id 已存在 ⇒ {@code BAD_REQUEST}（零 revision）；批内域拒 ⇒
 * {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-army}，对 {@code
 * CoreSimos} / {@code QueryService} 的直连换成构造期注入（写口 / 读口 / 视图口）；工具名、载荷、拒绝码、资源声明、批内顺序逐字不变。 报出去的 {@code
 * submission.commandId}/{@code correlationId} 仍**就是**落盘信封里的那一对（批级 id 由写口适配器生成并回报， 见 {@link
 * ToolWritePort.BatchReceipt}）。
 */
public final class StartCombatTool implements AgentTool {

  /** 工具名（全局唯一；用户给定）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.army.startCombat";

  /** 本工具只写 army / unit 两个命名空间（GM 侧两片 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest START_COMBAT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.ARMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #START_COMBAT_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.ARMY_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final ArmyToolViews views;

  public StartCombatTool(ToolWritePort writes, ToolReadPort reads, ArmyToolViews views) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "让单位进入交战（GM 组合工具，一批 = 一条 revision）：先 army.RecordCombat 建记录（kind=自定义交战状态自由文本，"
        + "如\"野战\"/\"轰城\"；初始阶段缺省 id=start），再对每个参与单位写 unit.SetStateDescription"
        + "（state=\""
        + StartCombatPlan.STATE_KEY
        + "\"，address=\"army:combat.<id>\"）。参数 {combatId, q, r, kind, participants[unitId...]（至少一个、不重复、必须存在）,"
        + " text, preview?（缺省 true）, branch?, expectedRevision?（preview=false 必填）}。"
        + "返回 {preview, submitted, combatId, kind, hex, participants, text, tick, stateKey, address, initialStageId, commands}；"
        + "apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("combatId", ToolSchema.prop("string", "交战记录 id（如 c-1；不得与既有记录重复）"));
    props.put("kind", ToolSchema.prop("string", "自定义交战状态（自由文本，如 野战 / 轰城 / 围城）"));
    props.put("q", ToolSchema.prop("integer", "交战格：六角列坐标 q"));
    props.put("r", ToolSchema.prop("integer", "交战格：六角行坐标 r"));
    props.put("participants", ToolSchema.prop("array", "参与单位 id [unitId...]（至少一个、不重复、必须已存在）"));
    props.put("text", ToolSchema.prop("string", "自然语言过程（记录级概述，同时作为初始阶段文本）"));
    props.put(
        "preview",
        ToolSchema.prop(
            "boolean", "true（缺省）= 只算不写；false = 提交 RecordCombat + SetStateDescription 同一批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=head）"));
    return ToolSchema.schema(props, List.of("combatId", "kind", "q", "r", "participants", "text"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return START_COMBAT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "让单位进入交战 combatId="
            + args.get("combatId")
            + " kind="
            + args.get("kind")
            + " participants="
            + args.get("participants")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + " expected="
            + args.get("expectedRevision"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String combatId = ToolSchema.requiredText(args, "combatId");
      String kind = ToolSchema.requiredText(args, "kind");
      HexCoord hex =
          new HexCoord(
              (int) ToolSchema.requiredLong(args, "q"), (int) ToolSchema.requiredLong(args, "r"));
      List<String> participants = requireParticipantIds(args);
      String text = ToolSchema.requiredText(args, "text");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ 读数坐标 = 提交坐标：缺省（<0）= 该分支 head（与 QueryTarget.head 同义，见 ToolReadPort 的约定）。
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      StartCombatPlan.Plan plan =
          StartCombatPlan.derive(state, combatId, hex, kind, participants, text);
      if (preview) {
        return ToolResults.ok(view(plan, true, false, null));
      }
      return apply(plan, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "让单位进入交战失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** {@code participants} 参数：必填字符串数组（元素非空白；域层再判重复/存在性）。 */
  private static List<String> requireParticipantIds(Map<String, Object> args) {
    Object raw = args.get("participants");
    if (raw == null) {
      throw new IllegalArgumentException("参数 participants 必填且为 [unitId...] 数组");
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 participants 必须是 [unitId...] 数组");
    }
    List<String> ids = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 participants 的元素必须是非空白 unitId: " + item);
      }
      ids.add(text);
    }
    return List.copyOf(ids);
  }

  // ── preview / apply 共用视图与组批 ─────────────────────────────────────────────────

  private ToolResult apply(StartCombatPlan.Plan plan, BranchId branch, long expectedRevision) {
    List<ToolWritePort.Request> batch = new ArrayList<>(1 + plan.participants().size());
    batch.add(
        new ToolWritePort.Request(
            StartCombatPlan.RECORD_COMBAT_TYPE, plan.recordCombatPayloadJson(views)));
    for (String payload : plan.stateDescriptionPayloadsJson()) {
      batch.add(new ToolWritePort.Request(StartCombatPlan.SET_STATE_DESCRIPTION_TYPE, payload));
    }
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch);
    // ★ 批级 id 由写口适配器生成并回报 ⇒ 结果里报出去的就是落盘信封里的那一个（不再是"工具另生成一个"）。
    String batchId = receipt.correlationId();
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      return ToolResults.ok(view(plan, false, true, committedView(committed, batchId)));
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      return ToolResult.error("CONFLICT", ToolResults.json(view(plan, false, true, submission)));
    }
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效）。
    ToolWritePort.BatchOutcome.Rejected rejected =
        (ToolWritePort.BatchOutcome.Rejected) receipt.outcome();
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<ToolWritePort.Outcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      ToolWritePort.Outcome outcome = outcomes.get(i);
      row.put(
          "reason",
          outcome instanceof ToolWritePort.Outcome.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    return ToolResult.error("REJECTED", ToolResults.json(view(plan, false, true, submission)));
  }

  private static Map<String, Object> committedView(
      ToolWritePort.BatchOutcome.Committed committed, String batchId) {
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "committed");
    submission.put("ref", ToolResults.stateRef(committed.ref()));
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    return submission;
  }

  private Map<String, Object> view(
      StartCombatPlan.Plan plan,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("combatId", plan.combatId());
    view.put("kind", plan.kind());
    view.put("hex", views.hexCoord(plan.hex()));
    view.put("participants", plan.participantValues());
    view.put("text", plan.text());
    view.put("tick", plan.tick());
    view.put("stateKey", StartCombatPlan.STATE_KEY);
    view.put("address", plan.combatAddress());
    view.put("initialStageId", RecordCombatHandler.INITIAL_STAGE_ID);
    view.put("commands", plan.commandCounts());
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }
}
