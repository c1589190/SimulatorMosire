package io.mosire.simos.unit.tools.write;

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
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.tools.UnitToolAllocations;
import io.mosire.simos.unit.tools.UnitToolPerspective;
import io.mosire.simos.unit.tools.UnitToolReads;
import io.mosire.simos.unit.tools.UnitToolViews;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ★★ {@code simos.unit.levyRegion}（辖区阶段 6 / 计划 §6.1；阶段 11b 补 cloth；2026-10-17 manpower 接线）： <b>GM
 * 组合工具</b>——一次性从单位辖区的家户 actor 账抽粮 / 抽钱 / 抽布，并把 region 各 hex 的 Social 家户份额 （MALE +
 * 成年档，share-aware）转进单位的<b>目标家户</b>（{@code unit.households()} 恰一个且已在 Social 里）；三类改动同批落一条 revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：粮 / 钱 / 布在 {@code actor} 切片、人在 {@code social} 切片、行动记录在 {@code
 * sd} 切片，单条命令只能落一个命名空间。本工具走注入的 {@link ToolWritePort#submitBatchWithReceipt}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link LevyRegionPlan#plan}（不碰 {@link ToolContext}
 * / {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。不许出现第二份推导。
 *
 * <p>★★ <b>批的三条命令（按此顺序，按需缺席）</b>：
 *
 * <ol>
 *   <li>{@code social.SubmitHouseholdWorkOrder}（人力 &gt; 0 时）：{@code
 *       orderId=levy-manpower:<batchId>:<unitId>}， {@code target} = Plan 的目标家户，{@code plan} = 逐
 *       share {@code TRANSFER_MEMBERS(from=来源家户, to=目标家户, lotId, count=taken)}，{@code
 *       source.module="gov"}；不再发 {@code social.SeedGroups}（删人腿已退役）；
 *   <li>{@code actor.AdjustAccounts}（粮 / 钱 / 布任一 &gt; 0 时）：各来源家户账的<b>负增量</b>（粮与布合并进同一 {@code
 *       (owner,格)} 条目的 {@code goods} 表，钱进同条目的 {@code money} 表，避免同键重复）+ 一条单位国库账户（{@code
 *       ActorRef(UNIT, unitId)}，格 = 单位当刻有效位置）的 <b>正增量</b>；逐值相等（Σ 扣减 == 入库）；
 *   <li>{@code sd.PutInfo}（恒有）：行动记录，地址 = 单位 canonical（{@link Address#parse} → {@link
 *       Address#canonical()}，与 {@code RejectDirectiveTool} 同款），{@code key="levyRegion"}，{@code
 *       value} = JSON <b>字符串</b>（unitId/regionId/tick/四项数量/目标家户/人力 shares 的
 *       household/lot/taken/hex/reason）， {@code tick} = 当前世界日。
 * </ol>
 *
 * <p>★★ <b>cloth 的上限口径（具名）</b>：<b>cloth 本批只受可用量约束；上限字段留后续</b>——{@code Jurisdiction} 只有粮/钱/人三条
 * {@code levy*CapPerCommand}，本工具不为布发明第四条上限；布的总量不足 ⇒ 仍按统一口径<b>整条拒</b>（不是部分抽、不是截断）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ {@code actor.AdjustAccounts} 不打 {@code DmUsableCommand}，令 / {@code RegisterEffect}
 * / 决策人 catalog 三条路径到不了那本裸账。
 *
 * <p>★ <b>资源声明</b>：只写 {@code actor}/{@code social}/{@code sd} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}， GM 侧三者 unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM
 * 窄写同制。
 *
 * <p>★ <b>工具结果（preview 与 apply 同形）</b>：{@code
 * preview/submitted/tick/unitId/regionId/treasuryLocation/manpowerTargetHousehold} + 逐维度 {@code
 * grain}/{@code money}/{@code cloth}/{@code manpower} 各 {@code {requested, available,
 * sources[]}}（账来源带 owner + 格 + amount；人力来源带 household/lot/taken/hex）+ {@code infoText}；apply 另加
 * {@code submission} （committed / conflict / rejected + 逐条拒因）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 负值 / 四项全 0 / 单位不存在 / 无管辖 / 区域不在管辖或地图 / 超上限（粮/钱/人） / 无位置 / 来源总量不足 /
 * 人力目标家户为空、多个、不在 Social ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}；批被整条拒 ⇒ {@code
 * REJECTED} 带逐条可读拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class LevyRegionTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.levyRegion";

  /** 本工具提交的三条命令类型（固定，模型够不着 type）。 */
  public static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** 见 {@link #ADJUST_ACCOUNTS_TYPE}。★ 常量引用 social handler，本类不另抄字面量。 */
  public static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE =
      SubmitHouseholdWorkOrderHandler.TYPE;

  /** 见 {@link #ADJUST_ACCOUNTS_TYPE}。 */
  public static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 人口工单的 {@code source.module}：本工具是 GOV 辖区抽取（征发主体与国库都在 GOV 侧）。 */
  private static final String WORK_ORDER_SOURCE_MODULE = "gov";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 单位 canonical，一次抽取一条；同单位后续抽取自然追加 #1、#2…）。 */
  public static final String INFO_KEY = "levyRegion";

  /** 本工具只写 actor / social / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest LEVY_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #LEVY_WRITE} 同源的逐命名空间粗断言（GM 三面 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.SD_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final UnitToolViews views;
  private final UnitToolPerspective perspective;
  private final UnitToolAllocations allocations;

  /**
   * @param writes 窄写口（本工具走 {@code submitBatchWithReceipt}；preview=true 时一个字节都不写）
   * @param reads 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param views 视图口（{@code {q,r}} 坐标形状的唯一实现）
   * @param perspective 视角口（GOV 国库家户解析的唯一实现）
   * @param allocations 两座瀑布的入口（家户账三维 + 选人层；历法时钟由它的 app 实现持有——生产路径 = {@code CalendarService.load}
   *     的同一实例，旧测试路径 = 全缺省）
   */
  public LevyRegionTool(
      ToolWritePort writes,
      ToolReadPort reads,
      UnitToolViews views,
      UnitToolPerspective perspective,
      UnitToolAllocations allocations) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
    this.perspective = Objects.requireNonNull(perspective, "perspective");
    this.allocations = Objects.requireNonNull(allocations, "allocations");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 辖区一次性抽取（组合工具，一批 = 一条 revision）：从单位辖区的家户 actor 账抽粮/钱/布，"
        + "并把 region 各 hex 的 Social 家户 MALE+成年份额（share-aware）转进单位的目标家户。"
        + "★ manpower 已于 2026-10-17 接线：目标家户 = unit.households() 恰一个且该家户已在 Social 里；"
        + "为空/多个/不在 Social ⇒ plan 级具名拒（preview/apply 都 BAD_REQUEST、本次调用零 revision；多户需显式 target 政策，不按列表顺序猜）；"
        + "来源 = HouseholdManpowerAllocator.allocateMalesOfAdult（与征兵/组军同一份选人层），逐 share (household,lot,taken)；"
        + "来源不足 ⇒ 整条拒（不部分、不截断）。粮/钱/人各自受 unit.SetJurisdiction 的 levy*CapPerCommand 约束（0 = 无额度）；"
        + "★ cloth 本批只受可用量约束，上限字段留后续（Jurisdiction 没有第四条上限）。"
        + "载荷 {unitId(必填), regionId(必填), grain?, money?, cloth?, manpower?(四项可选 long，缺省 0；"
        + "负数拒、四项全 0 拒), reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "口径：单位必须存在且管辖含该 region、region 必须在地图里、单位必须有当刻有效位置（国库落点）；"
        + "粮/钱/布来源 = region 各 hex 上 HOUSEHOLD 账的可支配量（余额−冻结，AvailableStock 唯一算法；布 = CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID)）；"
        + "总量不足 ⇒ 整条拒（不部分、不截断）；分摊 = 瀑布（可用量降序，同量按账键升序）；requested=0 的维度整段跳过（manpower=0 不扫描 Social 家户份额）。"
        + "apply 批（固定顺序）：social.SubmitHouseholdWorkOrder（manpower>0；orderId=levy-manpower:<batchId>:<unitId>，"
        + "target=目标家户，plan=逐 share TRANSFER_MEMBERS，source.module=gov）→ actor.AdjustAccounts（粮/钱/布任一>0；"
        + "各来源负增量 + 单位国库正增量；粮与布合并进 goods、钱进 money）→ sd.PutInfo（单位 canonical 地址、key="
        + INFO_KEY
        + "、value=JSON 字符串的行动记录，人力段逐 share household/lot/taken/hex）。"
        + "返回 {preview, submitted, tick, unitId, regionId, treasuryLocation, manpowerTargetHousehold,"
        + " grain/money/cloth/manpower 各 {requested, available, sources[]}, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSchema.prop("string", "抽取主体：军事单位 id（国库 = ActorRef(UNIT, unitId)）"));
    props.put(
        "regionId", ToolSchema.prop("string", "抽取区域 id：必须在该单位的 jurisdiction key 集里、且存在于当前地图"));
    props.put(
        "grain",
        ToolSchema.prop(
            "integer", "抽粮数量（最小计量单位；可选，缺省 0 = 本维度整段跳过；不得为负，不得超 levyGrainCapPerCommand）"));
    props.put(
        "money",
        ToolSchema.prop("integer", "抽钱金额（毫银；可选，缺省 0 = 本维度整段跳过；不得为负，不得超 levyMoneyCapPerCommand）"));
    props.put(
        "cloth",
        ToolSchema.prop(
            "integer", "抽布数量（毫布；可选，缺省 0 = 本维度整段跳过；不得为负；★ 本批无单命令上限，只受可用量约束，" + "上限字段留后续）"));
    props.put(
        "manpower",
        ToolSchema.prop(
            "integer",
            "抽人力（人；可选，缺省 0 = 本维度整段跳过；不得为负，不得超 levyManpowerCapPerCommand）："
                + "从 region 各 hex 的 Social 家户 MALE+成年份额（share-aware）里抽，转入单位 unit.households() 恰一个目标家户"
                + "（必须已在 Social 里；为空/多个/不在 Social ⇒ BAD_REQUEST；多户需显式 target 政策）；不足 ⇒ 整条拒（不部分、不截断）"));
    props.put("reason", ToolSchema.prop("string", "抽取原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put(
        "preview",
        ToolSchema.prop(
            "boolean",
            "true（缺省）= 只算不写；false = 提交同一批（social.SubmitHouseholdWorkOrder? + actor.AdjustAccounts? + sd.PutInfo）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("unitId", "regionId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return LEVY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "辖区抽取 unitId="
            + args.get("unitId")
            + " regionId="
            + args.get("regionId")
            + " grain="
            + args.getOrDefault("grain", 0)
            + " money="
            + args.getOrDefault("money", 0)
            + " cloth="
            + args.getOrDefault("cloth", 0)
            + " manpower="
            + args.getOrDefault("manpower", 0)
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
      String unitId = ToolSchema.requiredText(args, "unitId");
      String regionId = ToolSchema.requiredText(args, "regionId");
      String reason = ToolSchema.requiredText(args, "reason");
      long grain = optionalAmount(args, "grain");
      long money = optionalAmount(args, "money");
      long cloth = optionalAmount(args, "cloth");
      long manpower = optionalAmount(args, "manpower");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      LevyRegionPlan.Plan plan =
          LevyRegionPlan.plan(
              state, unitId, regionId, grain, money, cloth, manpower, allocations, perspective);
      if (preview) {
        return ToolResults.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision, planOnly);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "辖区抽取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选数量的参数解析（缺省 0；类型错误由 {@link ToolSchema#optionalLong} 抛 ⇒ BAD_REQUEST）。 */
  private static long optionalAmount(Map<String, Object> args, String name) {
    Long value = ToolSchema.optionalLong(args, name);
    return value == null ? 0L : value;
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组三条命令（按需缺席）的同一批，走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      LevyRegionPlan.Plan plan,
      String reason,
      BranchId branch,
      long expectedRevision,
      boolean planOnly) {
    // ★ 本工具**预先指定**批级 correlationId：社会工单的确定性幂等键 orderId 要把它写进载荷
    //   （`levy-manpower:<batchId>:<unitId>`），而载荷必须在组批之前定稿 ⇒ 见 ToolWritePort 的 4 参重载。
    //   id 仍只由装配面写进信封（适配器逐条放 correlationId），回执回报的就是同一个值。
    String batchId = UUID.randomUUID().toString();
    List<ToolWritePort.Request> batch = buildBatch(batchId, plan, reason);
    if (planOnly) {
      return ToolResults.ok(UnitBatchViews.plannedCommandsView(batch));
    }
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch, batchId);
    Map<String, Object> view = planView(plan, reason, false, true);
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
    // ★ 整批拒：逐条把**真拒因**摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
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
   * 组批：{@code social.SubmitHouseholdWorkOrder}? → {@code actor.AdjustAccounts}? → {@code
   * sd.PutInfo}（固定顺序，可复现）。
   *
   * <p>★ 三条共享同一 {@code batchId}（correlationId）与同一 branch/expectedRevision ⇒ {@code submitBatch} 落一条
   * revision；任一腿失败整批拒、零 revision。
   */
  private List<ToolWritePort.Request> buildBatch(
      String batchId, LevyRegionPlan.Plan plan, String reason) {
    List<ToolWritePort.Request> batch = new ArrayList<>(3);
    if (plan.hasManpower()) {
      batch.add(
          new ToolWritePort.Request(
              SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
              submitHouseholdWorkOrderPayload(batchId, plan, reason)));
    }
    if (plan.hasAccountMovements()) {
      batch.add(new ToolWritePort.Request(ADJUST_ACCOUNTS_TYPE, adjustAccountsPayload(plan)));
    }
    batch.add(new ToolWritePort.Request(PUT_INFO_TYPE, infoPayload(plan, reason)));
    return List.copyOf(batch);
  }

  // ── 载荷：actor.AdjustAccounts ───────────────────────────────────────────────────────

  /**
   * {@code actor.AdjustAccounts} 载荷：各来源家户账的负增量 + 一条国库正增量。
   *
   * <p>★★ 粮 / 布 / 钱<b>按 {@code (owner,格)} 合并</b>：同一本账同时供多个维度时只能出现一条（该命令明令拒同键重复）；粮与布进同一个 {@code
   * goods} 表，钱进 {@code money} 表。条目顺序 = 粮来源瀑布序 → 仅钱的来源瀑布序 → 仅布的来源瀑布序（首次出现的位置保留）→ 国库（恒在最后）， 是内容的纯函数。
   */
  private static String adjustAccountsPayload(LevyRegionPlan.Plan plan) {
    // ★ 合并键 = 家户 id 本身（迁移前的 `HouseholdAccountKey` 是它的 1:1 包装，`toString()` 就是 `value()`
    //   ⇒ 去重与「首次出现的位置保留」的次序逐字不变；`simos-actor` 在 unit 的禁列里，且该键只用于本表）。
    LinkedHashMap<HouseholdId, Long> grainByKey = new LinkedHashMap<>();
    for (LevyRegionPlan.AccountSource source : plan.grain().sources()) {
      grainByKey.put(HouseholdActors.householdOf(source.owner()), -source.amount());
    }
    LinkedHashMap<HouseholdId, Long> moneyByKey = new LinkedHashMap<>();
    for (LevyRegionPlan.AccountSource source : plan.money().sources()) {
      moneyByKey.put(HouseholdActors.householdOf(source.owner()), -source.amount());
    }
    LinkedHashMap<HouseholdId, Long> clothByKey = new LinkedHashMap<>();
    for (LevyRegionPlan.AccountSource source : plan.cloth().sources()) {
      clothByKey.put(HouseholdActors.householdOf(source.owner()), -source.amount());
    }
    LinkedHashSet<HouseholdId> order = new LinkedHashSet<>(grainByKey.keySet());
    order.addAll(moneyByKey.keySet());
    order.addAll(clothByKey.keySet());
    List<Map<String, Object>> entries = new ArrayList<>(order.size() + 1);
    for (HouseholdId key : order) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("household", key.value());
      // ★ 粮与布同在一张 goods 表里：同键同命令只出现一条，扣减逐值对应。
      Map<String, Object> goods = new LinkedHashMap<>();
      if (grainByKey.containsKey(key)) {
        goods.put(EconomyVocabulary.GRAIN_COMMODITY_ID, grainByKey.get(key));
      }
      if (clothByKey.containsKey(key)) {
        goods.put(EconomyVocabulary.CLOTH_COMMODITY_ID, clothByKey.get(key));
      }
      if (!goods.isEmpty()) {
        entry.put("goods", goods);
      }
      if (moneyByKey.containsKey(key)) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put(MoneyVocabulary.SILVER_CURRENCY.toString(), moneyByKey.get(key));
        entry.put("money", money);
      }
      entries.add(entry);
    }
    // ★ 国库账户一条正增量：粮 / 布 / 钱各自 +requested（分配不变量保证 Σ扣减 == requested，逐值相等）。
    Map<String, Object> treasury = new LinkedHashMap<>();
    treasury.put("household", plan.treasuryHousehold());
    Map<String, Object> treasuryGoods = new LinkedHashMap<>();
    if (plan.grain().requested() > 0L) {
      treasuryGoods.put(EconomyVocabulary.GRAIN_COMMODITY_ID, plan.grain().requested());
    }
    if (plan.cloth().requested() > 0L) {
      treasuryGoods.put(EconomyVocabulary.CLOTH_COMMODITY_ID, plan.cloth().requested());
    }
    if (!treasuryGoods.isEmpty()) {
      treasury.put("goods", treasuryGoods);
    }
    if (plan.money().requested() > 0L) {
      Map<String, Object> money = new LinkedHashMap<>();
      money.put(MoneyVocabulary.SILVER_CURRENCY.toString(), plan.money().requested());
      treasury.put("money", money);
    }
    entries.add(treasury);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    return ToolResults.json(payload);
  }

  // ── 载荷：social.SubmitHouseholdWorkOrder（人力腿）──────────────────────────────────

  /**
   * {@code social.SubmitHouseholdWorkOrder} 载荷（2026-10-17 接线）：{@code target} = Plan 的目标家户；{@code
   * plan} = 逐 share {@code TRANSFER_MEMBERS(from=来源家户, to=目标家户, lotId, count=taken)}；{@code
   * orderId} = {@code levy-manpower:<batchId>:<unitId>}；{@code source.module="gov"}；{@code reason}
   * = 工具 reason。
   *
   * <p>★ 目标家户的 {@code economy}/{@code actor} 行不在此批创建：GOV / raiseUnit 路径已由 createOffice / P3
   * 同批建齐；缺行会在 工单目标不存在或后续 advance 具名拒，不静默造户。
   */
  private static String submitHouseholdWorkOrderPayload(
      String batchId, LevyRegionPlan.Plan plan, String reason) {
    List<UnitToolAllocations.ManpowerShare> shares = plan.manpower().shares();
    List<Map<String, Object>> steps = new ArrayList<>(shares.size());
    for (UnitToolAllocations.ManpowerShare share : shares) {
      Map<String, Object> step = new LinkedHashMap<>();
      step.put("op", "TRANSFER_MEMBERS");
      step.put("from", share.householdId().value());
      step.put("to", plan.manpowerTargetHousehold().value());
      step.put("lotId", share.lotId().value());
      step.put("count", share.taken());
      steps.add(step);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", "levy-manpower:" + batchId + ":" + plan.unitId());
    payload.put("target", plan.manpowerTargetHousehold().value());
    payload.put("reason", reason);
    payload.put("source", Map.of("module", WORK_ORDER_SOURCE_MODULE));
    payload.put("plan", steps);
    return ToolResults.json(payload);
  }

  // ── 载荷：sd.PutInfo（行动记录）───────────────────────────────────────────────────────

  /**
   * {@code sd.PutInfo} 载荷：单位 canonical 地址 + {@code key="levyRegion"} + {@code value} = JSON
   * <b>字符串</b>（含 unitId/regionId/tick/四项数量/目标家户/reason + 人力 shares 的 household/lot/taken/hex）+
   * {@code note} = 人可读摘要 + {@code tick} = 当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一单位的后续抽取自然追加 #1、#2…
   */
  private String infoPayload(LevyRegionPlan.Plan plan, String reason) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("unitId", plan.unitId());
    value.put("regionId", plan.regionId());
    value.put("tick", plan.tick());
    value.put("grain", plan.grain().requested());
    value.put("money", plan.money().requested());
    value.put("cloth", plan.cloth().requested());
    value.put("manpower", plan.manpower().requested());
    value.put(
        "manpowerTargetHousehold",
        plan.manpowerTargetHousehold() == null ? null : plan.manpowerTargetHousehold().value());
    Map<String, Object> sourceCounts = new LinkedHashMap<>();
    sourceCounts.put("grain", plan.grain().sources().size());
    sourceCounts.put("money", plan.money().sources().size());
    sourceCounts.put("cloth", plan.cloth().sources().size());
    sourceCounts.put("manpower", plan.manpower().shares().size());
    value.put("sourceCounts", sourceCounts);
    value.put("sources", manpowerSourcesView(plan.manpower()));
    value.put("reason", reason);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", ToolResults.json(value));
    payload.put("note", infoText(plan, reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  /**
   * 单位地址（canonical）：转发 {@link UnitToolReads#unitAddress}（域内唯一拼写点，与 {@code raiseUnit}/{@code
   * spawnArmy} 的行动记录同源）——不写歪、不手拼第二套格式。
   */
  private static String unitAddress(String unitId) {
    return UnitToolReads.unitAddress(unitId);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private Map<String, Object> planView(
      LevyRegionPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("regionId", plan.regionId());
    view.put("treasuryLocation", views.hexCoord(plan.treasuryLocation()));
    view.put(
        "manpowerTargetHousehold",
        plan.manpowerTargetHousehold() == null ? null : plan.manpowerTargetHousehold().value());
    view.put("grain", accountDimensionView(plan.grain()));
    view.put("money", accountDimensionView(plan.money()));
    view.put("cloth", accountDimensionView(plan.cloth()));
    view.put("manpower", manpowerView(plan.manpower()));
    view.put("infoText", infoText(plan, reason));
    return view;
  }

  /** 粮 / 钱 / 布维度视图：{@code {requested, available, sources:[{owner{kind,id},q,r,amount}…]}}。 */
  private Map<String, Object> accountDimensionView(LevyRegionPlan.Dimension dimension) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", dimension.requested());
    view.put("available", dimension.available());
    List<Map<String, Object>> sources = new ArrayList<>(dimension.sources().size());
    for (LevyRegionPlan.AccountSource source : dimension.sources()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("owner", actorRefView(source.owner()));
      row.put("q", source.at().q());
      row.put("r", source.at().r());
      row.put("amount", source.amount());
      sources.add(row);
    }
    view.put("sources", sources);
    return view;
  }

  /** 人力维度视图：{@code {requested, available, sources:[{household,lot,taken,hex}…]}}。 */
  private Map<String, Object> manpowerView(LevyRegionPlan.Manpower manpower) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", manpower.requested());
    view.put("available", manpower.available());
    view.put("sources", manpowerSourcesView(manpower));
    return view;
  }

  /**
   * 人力逐 share 视图（工具结果与 {@code sd.PutInfo.value.sources} 共用；保序）：{@code
   * {household,lot,taken,hex}}；{@code hex} 只有 {@code HEX} 来源家户才有，{@code UNIT} 家户没有格 ⇒ {@code null}
   * （不伪造位置）。
   */
  private List<Map<String, Object>> manpowerSourcesView(LevyRegionPlan.Manpower manpower) {
    List<Map<String, Object>> sources = new ArrayList<>(manpower.shares().size());
    for (UnitToolAllocations.ManpowerShare share : manpower.shares()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("household", share.householdId().value());
      row.put("lot", share.lotId().value());
      row.put("taken", share.taken());
      row.put("hex", share.hex() == null ? null : views.hexCoord(share.hex()));
      sources.add(row);
    }
    return sources;
  }

  /** 行内 owner 视图（{@code {kind,id}}；与 AdjustAccounts 载荷的 owner 同形）。 */
  private static Map<String, Object> actorRefView(ActorRef owner) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", owner.kind().name());
    view.put("id", owner.id());
    return view;
  }

  /** 人可读行动摘要（结果视图的 {@code infoText}，也进 {@code sd.PutInfo} 的 {@code note}）。 */
  private static String infoText(LevyRegionPlan.Plan plan, String reason) {
    return "单位 "
        + plan.unitId()
        + " 从区域 "
        + plan.regionId()
        + " 抽取（tick "
        + plan.tick()
        + "）：粮 "
        + plan.grain().requested()
        + "（来源 "
        + plan.grain().sources().size()
        + "）、钱 "
        + plan.money().requested()
        + "（来源 "
        + plan.money().sources().size()
        + "）、布 "
        + plan.cloth().requested()
        + "（来源 "
        + plan.cloth().sources().size()
        + "）、人力 "
        + plan.manpower().requested()
        + "（家户份额 "
        + plan.manpower().shares().size()
        + (plan.manpowerTargetHousehold() == null
            ? ""
            : " → " + plan.manpowerTargetHousehold().value())
        + "）；reason="
        + reason;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
