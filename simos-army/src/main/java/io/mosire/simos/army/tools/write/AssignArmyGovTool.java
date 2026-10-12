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
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.tools.ArmyToolPerspective;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.army.assignGov}（阶段 12 后续赋值缺口，2026-10-01）：<b>GM 组合工具</b>——已存在 Army 的主子 GOV 改派 /
 * 解除，并按需把 root unit 的 {@code ArmyFormation.masterGov} 同批同步；一批落一条 revision。
 *
 * <p>★★ <b>它为什么是一次批写多切片的组合工具而不是一条命令</b>：Army 主子在 {@code sd} 切片、{@code ArmyFormation.masterGov} 在
 * {@code unit} 切片；单条命令只能落一个命名空间。本工具走注入的 {@link ToolWritePort#submitBatchWithReceipt}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）， 不给"sd 改了、unit 没改"的漂移留窗口。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link AssignArmyGovPlan#plan}（不碰 {@link
 * ToolContext} / 写口）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。参数形状解析（bool / 可选文本 / 空串语义）在本类； 前置状态校验与批载荷组装在
 * Plan，不出现第二份推导。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：需要同步 unit 侧时 {@code unit.SetArmyFormation} → {@code
 * army.SetArmyMasterGov} → {@code ga.PutInfo}；不需要同步时第一条缺席。全部共享同一批级 id 与同一 branch/expectedRevision。
 *
 * <p>★★ <b>{@code masterGovUnitId} 三态</b>：缺席 / {@code null} / 空串（含空白串）= 解除认领；给了非空白值必须存在且带 {@code
 * GovernmentFormation}（preview 的前置校验与域层同口径）。
 *
 * <p>★★ <b>{@code syncUnitSide} / {@code role}</b>：缺省 {@code syncUnitSide=true}。root unit 已有 {@code
 * ArmyFormation} ⇒ 用它的原 role，只同步 masterGov（解除时 {@code unit.SetArmyFormation} 载荷不带 {@code masterGov}
 * 键）；root unit 没有 {@code ArmyFormation} 且给了 role ⇒ 用给定 role 落同批命令；没有且未给 role ⇒ {@code
 * BAD_REQUEST}（要么给 role，要么 {@code syncUnitSide=false}）。{@code syncUnitSide=false} 时本工具不碰 unit
 * 侧，role 不参与。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ <b>资源声明</b>：只写 {@code unit}/{@code sd} 两个命名空间（{@link ResourcePolicy#UNRESTRICTED}，GM 侧两面
 * unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM 组合工具同制。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / armyId 不存在 / root unit 不存在 / 新主子不存在或非 GOV / 需要同步 unit 侧却没有 role ⇒
 * {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒（如 root 已带 {@code
 * GovernmentFormation}、同 tick 二次改编）⇒ {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配
 * ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-army}，对 {@code
 * CoreSimos} / {@code QueryService} 的直连换成构造期注入（写口 / 读口 / 视角口——sd 侧的 Army 事实由 app 取出）；工具名、载荷、拒绝码、
 * 资源声明、批内顺序与冲突预检的形状逐字不变。
 */
public final class AssignArmyGovTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.army.assignGov";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = Army canonical，一次改派一条）。 */
  public static final String INFO_KEY = "assignArmyGov";

  /** 本工具只写 unit / sd 两个命名空间（GM 侧两者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest ASSIGN_ARMY_GOV_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #ASSIGN_ARMY_GOV_WRITE} 同源的逐命名空间粗断言（GM 两侧 unlimited，顺序 = 批内命令命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.SD_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final ArmyToolPerspective perspective;

  /**
   * @param writes 唯一写入口（本工具走 {@code submitBatchWithReceipt}；preview=true 时一个字节都不写）
   * @param reads 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与 apply 共用它）
   * @param perspective 视角口（sd 切片里 Army 的 root 单位与已认领 GOV——army 不认识 sd，铁律 3）
   */
  public AssignArmyGovTool(
      ToolWritePort writes, ToolReadPort reads, ArmyToolPerspective perspective) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.perspective = Objects.requireNonNull(perspective, "perspective");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 改派/解除 Army 主子 GOV（组合工具，一批 = 一条 revision）："
        + "参数 {armyId(必填, 必须已存在), masterGovUnitId?(可空/缺省/空串 = 解除认领; "
        + "给了必须存在且带 GovernmentFormation), syncUnitSide?(缺省 true), role?(可选; root 还没有 ArmyFormation "
        + "且 syncUnitSide=true 时必填；root 已有 ArmyFormation 时用它的原 role), reason(必填非空白), "
        + "preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填，>=0)}。"
        + "前置：Army 与 root unit 必须已存在；新主子必须存在且带 GovernmentFormation；syncUnitSide=true 且 root 无 "
        + "ArmyFormation 时必须有 role，否则 BAD_REQUEST（零 revision）。"
        + "批顺序："
        + "[syncUnitSide: unit.SetArmyFormation] → army.SetArmyMasterGov → ga.PutInfo"
        + "(address=army:army.<armyId>, key="
        + INFO_KEY
        + ", value=JSON 字符串, tick=当前世界日)。"
        + "root 已有 ArmyFormation 时只同步 masterGov（解除时 unit 侧载荷不带 masterGov 键）。"
        + "失败具名：坏参数/前置不满足 ⇒ BAD_REQUEST（零 revision）；批内域拒 ⇒ REJECTED（逐条真拒因）；"
        + "提交冲突 ⇒ CONFLICT（真实 head）；资源不匹配 ⇒ 原样抛资源拒因。"
        + "返回 {preview, submitted, tick, armyId, rootUnit, oldMasterGov, newMasterGov, syncUnitSide, "
        + "unitSetArmyFormation, role, commands, infoText, conflictPreflight}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("armyId", ToolSchema.prop("string", "Army id（必填，必须已存在）"));
    props.put(
        "masterGovUnitId",
        ToolSchema.prop("string", "新主子 GOV 单位 id（可空/缺省/空串 = 解除认领；给了必须存在且带 GovernmentFormation）"));
    props.put(
        "syncUnitSide",
        ToolSchema.prop("boolean", "是否同批同步 root unit 的 ArmyFormation.masterGov（缺省 true）"));
    props.put(
        "role",
        ToolSchema.prop(
            "string",
            "unit 侧 role（root 还没有 ArmyFormation 且 syncUnitSide=true 时必填；已有 ArmyFormation 时用原 role）"));
    props.put("reason", ToolSchema.prop("string", "改派原因（必填非空白；进 ga.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "preview=false 必填：提交的乐观并发 base revision（>=0）"));
    return ToolSchema.schema(props, List.of("armyId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ASSIGN_ARMY_GOV_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "改派 Army 主子 armyId="
            + args.get("armyId")
            + " masterGovUnitId="
            + args.getOrDefault("masterGovUnitId", "(解除认领)")
            + " syncUnitSide="
            + args.getOrDefault("syncUnitSide", true)
            + " role="
            + args.getOrDefault("role", "(未给)")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String armyId = ToolSchema.requiredText(args, "armyId");
      Optional<String> masterGovUnitId = optionalMasterGovUnitId(args);
      boolean syncUnitSide = ToolSchema.optionalBoolean(args, "syncUnitSide").orElse(true);
      Optional<String> role = optionalText(args, "role");
      String reason = ToolSchema.requiredText(args, "reason");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      AssignArmyGovPlan.Plan plan =
          AssignArmyGovPlan.plan(state, armyId, masterGovUnitId, syncUnitSide, role, perspective);
      Map<String, Object> conflictPreflight = conflictPreflight(branch, expectedRevisionArg);
      if (preview) {
        return ToolResults.ok(planView(plan, reason, true, false, conflictPreflight));
      }
      return apply(plan, reason, branch, expectedRevision, conflictPreflight);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "改派 Army 主子失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选文本：缺省 ⇒ 空 Optional；给了空白/非文本 ⇒ BAD_REQUEST（类型口径由 {@link ToolSchema#optionalText} 守）。 */
  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    String text = ToolSchema.optionalText(args, name, null);
    return Optional.ofNullable(text);
  }

  /**
   * {@code masterGovUnitId} 三态：键缺席 / {@code null} / 空串（含空白串）= 解除认领。其余类型 ⇒ 具名 {@code
   * BAD_REQUEST}（不静默按缺省办）。
   */
  private static Optional<String> optionalMasterGovUnitId(Map<String, Object> args) {
    Object raw = args.get("masterGovUnitId");
    if (raw == null) {
      return Optional.empty();
    }
    if (raw instanceof String text) {
      return text.isBlank() ? Optional.empty() : Optional.of(text);
    }
    throw new IllegalArgumentException("参数 masterGovUnitId 若给出必须是非空白文本或 null（空串 = 解除认领）: " + raw);
  }

  // ── 冲突预检（只读 head，不写） ───────────────────────────────────────────────────────

  /**
   * 乐观并发的 preview 预检：报出 {@code expectedRevision} 与该分支真实 head 是否一致。apply 的冲突判定仍由写口的批提交 在提交锁内做，并回报真实
   * head——本预检只是让 preview 先把风险说清楚。
   */
  private Map<String, Object> conflictPreflight(BranchId branch, Long expectedRevision) {
    Optional<RevisionId> head = writes.head(branch);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", branch.value());
    view.put("expectedRevision", expectedRevision);
    view.put("headRevision", head.map(RevisionId::value).orElse(null));
    boolean headMatchesExpected;
    if (expectedRevision == null) {
      // preview 没给 expectedRevision ⇒ 读数就是 head，不存在"提交基准已过期"的预检风险。
      headMatchesExpected = true;
    } else {
      long expected = expectedRevision;
      headMatchesExpected = head.map(rev -> rev.value() == expected).orElse(false);
    }
    view.put("headMatchesExpected", headMatchesExpected);
    view.put("wouldConflict", !headMatchesExpected);
    return view;
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序、按需缺席），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      AssignArmyGovPlan.Plan plan,
      String reason,
      BranchId branch,
      long expectedRevision,
      Map<String, Object> conflictPreflight) {
    List<ToolWritePort.Request> batch = buildBatch(plan, reason);
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch);
    // ★ 批级 id 由写口适配器生成并回报 ⇒ 结果里报出去的就是落盘信封里的那一个。
    String batchId = receipt.correlationId();
    Map<String, Object> view = planView(plan, reason, false, true, conflictPreflight);
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      view.put("submission", ToolResults.committedView(committed.ref(), batchId, batchId));
      return ToolResults.ok(view);
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
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
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolResults.json(view));
  }

  /**
   * 组批：{@code [syncUnitSide: unit.SetArmyFormation]} → {@code army.SetArmyMasterGov} → {@code
   * ga.PutInfo} （固定顺序，可复现）。批级 id（correlationId）与 branch/expectedRevision 由写口适配器统一补 ⇒ 整批落一条
   * revision。
   */
  private static List<ToolWritePort.Request> buildBatch(
      AssignArmyGovPlan.Plan plan, String reason) {
    List<ToolWritePort.Request> batch = new ArrayList<>(3);
    if (plan.hasUnitCommand()) {
      batch.add(
          new ToolWritePort.Request(
              AssignArmyGovPlan.SET_ARMY_FORMATION_TYPE, plan.setArmyFormationPayloadJson()));
    }
    batch.add(
        new ToolWritePort.Request(
            AssignArmyGovPlan.SET_ARMY_MASTER_GOV_TYPE, plan.setArmyMasterGovPayloadJson()));
    batch.add(
        new ToolWritePort.Request(AssignArmyGovPlan.PUT_INFO_TYPE, infoPayload(plan, reason)));
    return List.copyOf(batch);
  }

  // ── 载荷：ga.PutInfo（行动记录） ─────────────────────────────────────────────────────

  /**
   * {@code ga.PutInfo} 载荷：Army canonical 地址 + {@code key="assignArmyGov"} + {@code value}=JSON 字符串
   * + {@code note}=人可读摘要 + {@code tick}=当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code ga.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一 Army 的后续改派自然追加序号。
   */
  private static String infoPayload(AssignArmyGovPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", AssignArmyGovPlan.armyAddress(plan.armyId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      AssignArmyGovPlan.Plan plan,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> conflictPreflight) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("armyId", plan.armyId());
    view.put("rootUnit", plan.rootUnit());
    view.put("oldMasterGov", plan.oldMasterGov().orElse(null));
    view.put("newMasterGov", plan.newMasterGov().orElse(null));
    view.put("syncUnitSide", plan.syncUnitSide());
    view.put("unitSetArmyFormation", plan.hasUnitCommand());
    view.put("role", plan.role().orElse(null));
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    view.put("conflictPreflight", conflictPreflight);
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
