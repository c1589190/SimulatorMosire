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
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.tools.ArmyManpowerPort;
import io.mosire.simos.army.tools.ArmyToolViews;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.army.resolveCombat}（阶段 D4 / 用户设计 D-009 补裁 + D-010 + D-012，2026-10-02；P2 人员伤亡 回写
 * Social，2026-10-13）：GM-only 组合工具——**结算一个交战阶段**：一批（一条 revision）= 逐单位人员伤亡 {@code
 * social.SubmitHouseholdWorkOrder}（{@code REMOVE_MEMBERS}，从该单位的 Social 家户份额里真减人）+ 逐单位装备 {@code
 * unit.AdjustComposition}（只带 equipment 维度）+ 一条 {@code army.ResolveCombatStage}（选定结局/seed 落记录）
 * +（记录内所有阶段都已判定时）逐单位 {@code unit.SetStateDescription} 清除 {@code combat} 状态链接。
 *
 * <p>★★ <b>判定语义（三条，与 {@code CombatResolution} 同源）</b>：
 *
 * <ol>
 *   <li>只给 {@code outcomeId} ⇒ 不投骰，选中它（记录里 {@code rollSeed} 空）；
 *   <li>只给 {@code seed} ⇒ 用该 seed 投骰；
 *   <li>两个都给 ⇒ 按该 seed 复核（投出的必须就是给的 outcome，否则拒）；
 *   <li>都不给 ⇒ 由 {@code combatId+stageId+tick+概率表} 确定性派生 seed 后投骰（可复现；seed 落记录）。
 * </ol>
 *
 * <p>★★ <b>P2 人员损失口径</b>：选中结局的 {@code CombatUnitLoss.manpower} 只接受负增量；工具按 {@code MALE + ADULT} 从
 * {@code Unit.households()} 的 Social 份额里抽人（唯一选人层 = 注入的 {@link ArmyManpowerPort}，实现在 app），不足 ⇒
 * {@code BAD_REQUEST}（零 revision，不部分抽）。人员只从 Social 走，<b>Unit 侧不写第二本 headcount</b>；装备损失仍走 {@code
 * unit.AdjustComposition} 且载荷不含 manpower。<b>批内顺序</b>（P2 文档 §3）：每个 unit 先人员工单（如有）再装备命令 （如有）→ {@code
 * army.ResolveCombatStage} → 清链接。
 *
 * <p>★★ <b>状态链接的"结束"口径（R3）</b>：本次判定后若记录里所有阶段都已判定，工具在**同一批**里显式清除所有"状态键 combat 且地址恰为 {@code
 * army:combat.<id>}"的链接（只清恰链到本记录的；本来没有链接的不发命令）。还有未判定阶段 ⇒ 链接保留到下一次显式清除。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：它既改记录又改单位/家户，是战果结算；工具名不是命令类型 ⇒ 不进
 * catalog / {@code PAYLOAD_HINTS}。它提交的 {@code unit.AdjustComposition} 本身标了 {@code GmOnlyCommand}（GM
 * 直改原语），与此工具同一条权限边界。
 *
 * <p>★ <b>资源声明</b>：写 {@code social}/{@code unit}/{@code army} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}），与批内命令逐条对齐；**不新开写口**——人员只经 Social 工单写，装备只经 {@code
 * unit.AdjustComposition} 写。
 *
 * <p>★ <b>参数</b>：{@code combatId(必填), stageId(必填), outcomeId?, seed?, preview?(缺省 true), branch?,
 * expectedRevision?(preview=false 必填)}。
 *
 * <p>★ <b>失败具名</b>：记录/阶段不存在、阶段已判定、显式 outcome 不在表里、seed 与 outcome 不一致、损失指向不存在单位、manpower
 * 非负增量、单位无家户或合格人口不足 ⇒ {@code BAD_REQUEST}（零 revision）；批内域拒 ⇒ {@code REJECTED} 带逐条真拒因；冲突 ⇒ {@code
 * CONFLICT} 带真实 head。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-army}，对 {@code
 * CoreSimos} / {@code QueryService} / {@code CalendarService} 的直连换成构造期注入（写口 / 读口 / 视图口 /
 * 选人口——历法时钟由选人口的实现持有， 因为 {@code simos-calendar} 在 army 禁列里）；工具名、载荷、拒绝码、资源声明、批内顺序逐字不变。
 */
public final class ResolveCombatTool implements AgentTool {

  /** 工具名（全局唯一；用户给定）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.army.resolveCombat";

  /** 本工具写 social / unit / army 三个命名空间（GM 侧三片 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest RESOLVE_COMBAT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.ARMY_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #RESOLVE_COMBAT_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.ARMY_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final ArmyToolViews views;

  /** 选人口：人员伤亡的 {@code MALE + ADULT} 家户份额瀑布（实现持有生产历法时钟，见端口类注）。 */
  private final ArmyManpowerPort manpower;

  public ResolveCombatTool(
      ToolWritePort writes, ToolReadPort reads, ArmyToolViews views, ArmyManpowerPort manpower) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
    this.manpower = Objects.requireNonNull(manpower, "manpower");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "结算一个交战阶段（GM 组合工具，一批 = 一条 revision）：投骰或显式结局——逐单位"
        + " social.SubmitHouseholdWorkOrder（manpower 损失只接受负增量，从 unit.households 的 Social 家户份额里按"
        + " MALE+ADULT 真减人；不足整条拒）→ 逐单位 unit.AdjustComposition（只带 equipment 维度，空装备不发）→"
        + " army.ResolveCombatStage（选定结局/seed 写进记录）→ 若记录内所有阶段都已判定，逐单位 unit.SetStateDescription"
        + " 清除 state=\""
        + ResolveCombatPlan.STATE_KEY
        + "\" 且地址恰为 army:combat.<id> 的状态链接。参数 {combatId, stageId, outcomeId?, seed?, preview?（缺省 true）,"
        + " branch?, expectedRevision?（preview=false 必填）}。四种取法：outcomeId（不投骰）/ seed（显式投骰）/ 都不给（由"
        + " combatId+stageId+tick+概率表 确定性派生 seed）；两给则按 seed 复核 outcome。返回 {preview, submitted, combatId,"
        + " stageId, outcome, seed, seedProvided, outcomeProvided, tick, allStagesResolvedAfter, losses,"
        + " casualtyWorkOrders, clearedLinks, commandTypes, commands}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("combatId", ToolSchema.prop("string", "交战记录 id（如 c-1）"));
    props.put("stageId", ToolSchema.prop("string", "要判定的阶段 id（如 start / s2）"));
    props.put("outcomeId", ToolSchema.prop("string", "显式结局 id（可选；给了就不投骰，必须在该阶段概率表里）"));
    props.put(
        "seed",
        ToolSchema.prop("integer", "显式随机种子（可选；只给 seed = 显式投骰；与 outcomeId 同给 = 按 seed 复核 outcome）"));
    props.put(
        "preview",
        ToolSchema.prop(
            "boolean",
            "true（缺省）= 只算不写；false = 提交 Social 人员工单 + unit.AdjustComposition + army.ResolveCombatStage"
                + " 同一批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=head）"));
    return ToolSchema.schema(props, List.of("combatId", "stageId"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return RESOLVE_COMBAT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "结算交战阶段 combatId="
            + args.get("combatId")
            + " stageId="
            + args.get("stageId")
            + " outcomeId="
            + args.get("outcomeId")
            + " seed="
            + args.get("seed")
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
      String stageId = ToolSchema.requiredText(args, "stageId");
      Optional<String> outcomeId = optionalNonBlankText(args, "outcomeId");
      Optional<Long> seed = Optional.ofNullable(ToolSchema.optionalLong(args, "seed"));
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
      ResolveCombatPlan.Plan plan =
          ResolveCombatPlan.derive(state, combatId, stageId, outcomeId, seed, manpower);
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
          "TOOL_ERROR", "结算交战阶段失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── preview / apply 共用视图与组批 ─────────────────────────────────────────────────

  private ToolResult apply(ResolveCombatPlan.Plan plan, BranchId branch, long expectedRevision) {
    // ★ P2 §3：批内顺序由 Plan.unitCommands() 唯一给出——逐 unit 先人员工单（如有）再装备命令（如有），再 ResolveStage、清链接。
    List<ResolveCombatPlan.PlannedCommand> unitCommands = plan.unitCommands(views);
    List<ToolWritePort.Request> batch =
        new ArrayList<>(unitCommands.size() + 1 + plan.clearLinkUnits().size());
    for (ResolveCombatPlan.PlannedCommand command : unitCommands) {
      batch.add(new ToolWritePort.Request(command.type(), command.payloadJson()));
    }
    batch.add(
        new ToolWritePort.Request(ResolveCombatPlan.RESOLVE_STAGE_TYPE, plan.resolvePayloadJson()));
    for (String payload : plan.clearPayloadsJson()) {
      batch.add(new ToolWritePort.Request(ResolveCombatPlan.SET_STATE_DESCRIPTION_TYPE, payload));
    }
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch);
    // ★ 批级 id 由写口适配器生成并回报 ⇒ 结果里报出去的就是落盘信封里的那一个。
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
      ResolveCombatPlan.Plan plan,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("combatId", plan.combatId().value());
    view.put("stageId", plan.stageId().value());
    view.put("outcome", outcomeView(plan.outcome()));
    view.put("seed", plan.seed().orElse(null));
    view.put("seedProvided", plan.seedProvided());
    view.put("outcomeProvided", plan.outcomeProvided());
    view.put("tick", plan.tick());
    view.put("allStagesResolvedAfter", plan.allStagesResolvedAfter());
    view.put("losses", plan.lossesView(views));
    // ★ P2：人员伤亡工单视图（orderId/target/shares/完整载荷字节）+ 实际批内命令类型（含逐单位顺序）。
    view.put("casualtyWorkOrders", plan.casualtyOrdersView(views));
    view.put("clearedLinks", unitIdValues(plan.clearLinkUnits()));
    view.put("commandTypes", plan.commandTypes(views));
    view.put("commands", plan.commandCounts());
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  private static Map<String, Object> outcomeView(CombatOutcome outcome) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", outcome.id().value());
    view.put("label", outcome.label());
    view.put("weight", outcome.weight());
    return view;
  }

  private static List<String> unitIdValues(List<UnitId> units) {
    List<String> values = new ArrayList<>(units.size());
    for (UnitId unit : units) {
      values.add(unit.value());
    }
    return List.copyOf(values);
  }

  /** 可选非空白文本参数：缺省/显式 null ⇒ 空；给出但非文本或空白 ⇒ 具名 {@link IllegalArgumentException}。 */
  private static Optional<String> optionalNonBlankText(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是非空白文本");
    }
    return Optional.of(text);
  }
}
