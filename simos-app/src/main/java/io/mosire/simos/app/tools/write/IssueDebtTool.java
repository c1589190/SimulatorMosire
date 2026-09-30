package io.mosire.simos.app.tools.write;

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
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.spi.UnitBorrowHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ {@code simos.unit.issueDebt}（辖区 · 税/地方债阶段 7 第二段 / 计划 §4）：<b>GM 组合工具</b>——单位向 class-first
 * 放贷方借入地方债，三条命令<b>同批落一条 revision</b>。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：钱 / 粮在 {@code classFirst.lenders}（economy 切片）、国库账在 {@code
 * actor} 切片、行动记录在 {@code sd} 切片，单条命令只能落一个命名空间。本工具走 {@link CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link UnitDebtPlan#planIssue}（不碰 {@link
 * ToolContext} / {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。不许出现第二份推导。
 *
 * <p>★★ <b>批的三条命令（固定顺序）</b>：
 *
 * <ol>
 *   <li>{@code economy.UnitBorrow}（7a 原语，标 {@code GmOnlyCommand}）：放贷方余额 −principal + 借款腿 /
 *       镜像腿两条同批落；
 *   <li>{@code actor.AdjustAccounts}：国库一条<b>正增量</b>（{@code ActorRef(UNIT, unitId)} +
 *       单位当刻有效位置；缺账可新建）；
 *   <li>{@code sd.PutInfo}（恒有）：行动记录，地址 = 单位 canonical（{@code unit:<unitId>}），{@code
 *       key="issueDebt"}， {@code value} = JSON <b>字符串</b>，{@code note} = 人可读摘要，{@code tick} =
 *       当前世界日。
 * </ol>
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 单提 {@code economy.UnitBorrow} 会造成"放贷方已扣、国库未收"的悬空 ⇒ 本工具批是唯一受支持的完整调用面。
 *
 * <p>★ <b>资源声明</b>：只写 {@code economy}/{@code actor}/{@code sd} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}，GM 侧三者 unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM
 * 窄写同制。
 *
 * <p>★ <b>工具结果（preview 与 apply 同形）</b>：{@code
 * preview/submitted/tick/unitId/lenderId/unit/principal/
 * interestRatePerMille/nextDueTick/terms/treasuryLocation/debtBefore/debtAfter/lenderAvailableBefore/
 * lenderAvailableAfter/commands/infoText}；apply 另加 {@code submission}（committed / conflict /
 * rejected + 逐条拒因）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 非法 unit / 本金与利率不合法 / nextDueTick 不在未来 / 单位不存在 / 无位置 / classFirst 为空 /
 * 放贷方不存在 / 可贷量不足 / 已有未结清腿 ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}；镜像腿非 0 ⇒ {@link
 * IllegalStateException} 折 {@code TOOL_ERROR}（状态损坏不静默）；批被整条拒 ⇒ {@code REJECTED} 带逐条可读拒因； 提交冲突 ⇒
 * {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class IssueDebtTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.issueDebt";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 单位 canonical，一次借入一条；同单位后续借入自然追加序号）。 */
  public static final String INFO_KEY = "issueDebt";

  /** 本工具只写 economy / actor / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest DEBT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #DEBT_WRITE} 同源的逐命名空间粗断言（GM 三面 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与 {@code LevyRegionTool} 等同制；本工具资源声明是三个命名空间的粗断言、 单位地址按单位 id
   *     定位，不当路径用）
   */
  public IssueDebtTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 地方债借入（组合工具，一批 = 一条 revision）：单位向 class-first 放贷方借入 money/grain，"
        + "国库同批入账 principal，并落一条 sd.PutInfo 行动记录。"
        + "载荷 {unitId(必填), lenderId(必填, 必须在 classFirst.lenders 里), unit(必填, 只认 money|grain), "
        + "principal(必填 long, >=1, 不得超过放贷方可贷量), interestRatePerMille(必填 long, >=0), "
        + "nextDueTick(必填 long, > 当前 tick), terms?(可选, 缺省 unit-debt), reason(必填), "
        + "preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "口径：单位必须存在且当刻有有效位置（国库落点）；同一 (unitId,lenderId,unit) 已有未结清借款腿 ⇒ 拒"
        + "（先 simos.unit.repayDebt 清账；已结清身份可重开）；镜像腿净额非 0 ⇒ 状态损坏（响亮拒，不静默覆盖）。"
        + "apply 批：economy.UnitBorrow（放贷方扣本金 + 双边两条腿）→ actor.AdjustAccounts（国库入账）→ "
        + "sd.PutInfo（单位 canonical 地址、key="
        + INFO_KEY
        + "、value=JSON 字符串）。"
        + "返回 {preview, submitted, tick, unitId, lenderId, unit, principal, interestRatePerMille, nextDueTick, "
        + "terms, treasuryLocation, debtBefore, debtAfter, lenderAvailableBefore, lenderAvailableAfter, commands, "
        + "infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "借入主体：军事单位 id（国库 = ActorRef(UNIT, unitId)）"));
    props.put(
        "lenderId",
        ToolSupport.prop("string", "放贷方 id：必须存在于 economy.classFirst().lenders()（阶层池债权人分支本批具名拒）"));
    props.put("unit", ToolSupport.prop("string", "计价单位：只认 \"money\"/\"grain\"（不做别名、不做大小写归一）"));
    props.put("principal", ToolSupport.prop("integer", "本金（最小计量单位；>=1；不得超过放贷方可贷量，不截断）"));
    props.put("interestRatePerMille", ToolSupport.prop("integer", "利率（千分；>=0，0 = 无息；必填）"));
    props.put("nextDueTick", ToolSupport.prop("integer", "下次到期 tick（必须 > 当前 tick）"));
    props.put(
        "terms",
        ToolSupport.prop("string", "条款词（可选，缺省 " + UnitBorrowHandler.TERMS_UNIT_DEBT + "）"));
    props.put("reason", ToolSupport.prop("string", "借入原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交三条命令的同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(
        props,
        List.of(
            "unitId",
            "lenderId",
            "unit",
            "principal",
            "interestRatePerMille",
            "nextDueTick",
            "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return DEBT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "地方债借入 unitId="
            + args.get("unitId")
            + " lenderId="
            + args.get("lenderId")
            + " unit="
            + args.get("unit")
            + " principal="
            + args.get("principal")
            + " interestRatePerMille="
            + args.get("interestRatePerMille")
            + " nextDueTick="
            + args.get("nextDueTick")
            + " terms="
            + args.getOrDefault("terms", UnitBorrowHandler.TERMS_UNIT_DEBT)
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String unitId = ToolSupport.requiredText(args, "unitId");
      String lenderId = ToolSupport.requiredText(args, "lenderId");
      String unit = ToolSupport.requiredText(args, "unit");
      long principal = ToolSupport.requiredLong(args, "principal");
      long interestRatePerMille = ToolSupport.requiredLong(args, "interestRatePerMille");
      long nextDueTick = ToolSupport.requiredLong(args, "nextDueTick");
      String terms = ToolSupport.optionalText(args, "terms", UnitBorrowHandler.TERMS_UNIT_DEBT);
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryService.QueryTarget.head(branch)
                  : QueryService.QueryTarget.at(branch, new RevisionId(expectedRevision)));
      UnitDebtPlan.IssuePlan plan =
          UnitDebtPlan.planIssue(
              state, unitId, lenderId, unit, principal, interestRatePerMille, nextDueTick, terms);
      if (preview) {
        return ToolSupport.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "地方债借入失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组三条命令（固定顺序）的同一批，走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      UnitDebtPlan.IssuePlan plan, String reason, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    RevisionId base = new RevisionId(expectedRevision);
    List<CommandEnvelope> batch =
        List.of(
            envelope(batchId, branch, base, UnitDebtPlan.BORROW_TYPE, plan.borrowPayloadJson()),
            envelope(
                batchId, branch, base, UnitDebtPlan.ADJUST_ACCOUNTS_TYPE, plan.adjustPayloadJson()),
            envelope(batchId, branch, base, UnitDebtPlan.PUT_INFO_TYPE, infoPayload(plan, reason)));
    BatchResult result = core.submitBatch(batch);
    Map<String, Object> view = planView(plan, reason, false, true);
    if (result instanceof BatchResult.Committed committed) {
      view.put("submission", ToolSupport.committedView(committed.ref(), batchId, batchId));
      return ToolSupport.ok(view);
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolSupport.json(view));
    }
    // ★ 整批拒：逐条把**真拒因**摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<CommandOutcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      CommandResult outcome = outcomes.get(i).result();
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }

  private CommandEnvelope envelope(
      String batchId,
      BranchId branch,
      RevisionId expectedRevision,
      String type,
      String payloadJson) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        batchId,
        initiator,
        branch,
        expectedRevision,
        type,
        payloadJson);
  }

  // ── 载荷：sd.PutInfo（行动记录）───────────────────────────────────────────────────────

  /**
   * {@code sd.PutInfo} 载荷：单位 canonical 地址 + {@code key="issueDebt"} + {@code value} = JSON
   * <b>字符串</b> + {@code note} = 人可读摘要 + {@code tick} = 当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一单位的后续借入自然追加序号。
   */
  private static String infoPayload(UnitDebtPlan.IssuePlan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", UnitDebtPlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      UnitDebtPlan.IssuePlan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("lenderId", plan.lenderId());
    view.put("unit", plan.unit());
    view.put("principal", plan.principal());
    view.put("interestRatePerMille", plan.interestRatePerMille());
    view.put("nextDueTick", plan.nextDueTick());
    view.put("terms", plan.terms());
    view.put("treasuryLocation", ToolSupport.hexCoord(plan.treasuryLocation()));
    view.put("debtBefore", plan.debtBefore());
    view.put("debtAfter", plan.debtAfter());
    view.put("lenderAvailableBefore", plan.lenderAvailableBefore());
    view.put("lenderAvailableAfter", plan.lenderAvailableAfter());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
