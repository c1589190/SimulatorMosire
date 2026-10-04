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
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ {@code simos.unit.levyRegion}（辖区阶段 6 / 计划 §6.1；阶段 11b 补 cloth）：<b>GM 组合工具</b>——一次性从一个单位辖区的家户
 * actor 账抽粮 / 抽钱 / 抽布、并从该区域抽人力，三条命令<b>同批落一条 revision</b>。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：粮 / 钱 / 布在 {@code actor} 切片、人在 {@code social} 切片、行动记录在 {@code
 * sd} 切片，单条命令只能落一个命名空间。本工具走 {@link CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条
 * revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link LevyRegionPlan#plan}（不碰 {@link ToolContext}
 * / {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。不许出现第二份推导。
 *
 * <p>★★ <b>批的三条命令（按此顺序，按需缺席）</b>：
 *
 * <ol>
 *   <li>{@code actor.AdjustAccounts}（粮 / 钱 / 布任一 &gt; 0 时）：各来源家户账的<b>负增量</b>（粮与布合并进同一 {@code
 *       (owner,格)} 条目的 {@code goods} 表，钱进同条目的 {@code money} 表，避免同键重复）+ 一条单位国库账户（{@code
 *       ActorRef(UNIT, unitId)}，格 = 单位当刻有效位置）的 <b>正增量</b>；逐值相等（Σ 扣减 == 入库）；
 *   <li>{@code social.SeedGroups}（人力 &gt; 0 时）：每个被动批次一条<b>整组覆盖</b>条目（{@code id/q/r/sex/count=扣后} +
 *       {@code ageDays/anchorTick/stress} 保真），扣后 count 可为 0（合法空批）；
 *   <li>{@code sd.PutInfo}（恒有）：行动记录，地址 = 单位 canonical（{@link Address#parse} → {@link
 *       Address#canonical()}，与 {@code RejectDirectiveTool} 同款），{@code key="levyRegion"}，{@code
 *       value} = JSON <b>字符串</b>（unitId/regionId/tick/四项数量/来源计数/reason），{@code tick} = 当前世界日。
 * </ol>
 *
 * <p>★★ <b>cloth 的上限口径（具名）</b>：<b>cloth 本批只受可用量约束；上限字段留后续</b>——{@code Jurisdiction} 只有粮/钱/人三条
 * {@code levy*CapPerCommand}，本工具不为布发明第四条上限；布的总量不足 ⇒ 仍按统一口径<b>整条拒</b>（不是部分抽、不是截断）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ {@code actor.AdjustAccounts} 标了 {@code GmOnlyCommand}，令 / {@code RegisterEffect}
 * / 决策人 catalog 三条路径到不了那本裸账。
 *
 * <p>★ <b>资源声明</b>：只写 {@code actor}/{@code social}/{@code sd} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}， GM 侧三者 unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM
 * 窄写同制。
 *
 * <p>★ <b>工具结果（preview 与 apply 同形）</b>：{@code
 * preview/submitted/tick/unitId/regionId/treasuryLocation} + 逐维度 {@code grain}/{@code money}/{@code
 * cloth}/{@code manpower} 各 {@code {requested, available, sources[]}}（账来源带 owner + 格 +
 * amount；人力来源带批次 id + 格 + before/taken/after）+ {@code infoText}；apply 另加 {@code submission}
 * （committed / conflict / rejected + 逐条拒因）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 负值 / 四项全 0 / 单位不存在 / 无管辖 / 区域不在管辖或地图 / 超上限（粮/钱/人） / 无位置 / 来源总量不足 ⇒ {@link
 * IllegalArgumentException} 折 {@code BAD_REQUEST}；批被整条拒 ⇒ {@code REJECTED} 带逐条可读拒因；提交冲突 ⇒ {@code
 * CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class LevyRegionTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.levyRegion";

  /** 本工具提交的三条命令类型（固定，模型够不着 type）。 */
  public static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** 见 {@link #ADJUST_ACCOUNTS_TYPE}。 */
  public static final String SEED_GROUPS_TYPE = "social.SeedGroups";

  /** 见 {@link #ADJUST_ACCOUNTS_TYPE}。 */
  public static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 单位 canonical，一次抽取一条；同单位后续抽取自然追加 #1、#2…）。 */
  public static final String INFO_KEY = "levyRegion";

  /** 单位 address 的命名空间前缀（与 {@link ToolSupport#UNIT_NAMESPACE} 同源，不另写 "unit" 字面量）。 */
  private static final String UNIT_ADDRESS_PREFIX = ToolSupport.UNIT_NAMESPACE + ":";

  /** 本工具只写 actor / social / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest LEVY_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #LEVY_WRITE} 同源的逐命名空间粗断言（GM 三面 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /** 历法/气候服务：四个 gov 写工具按它取时钟（生产路径 = CalendarService.load；旧路径 = 全缺省）。 */
  private final CalendarService calendarService;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与 {@code EconomyAdjustTool} 等同制；本工具的资源声明是三个命名空间的 粗断言、单位
   *     address 也按单位 id 定位，不当路径用）
   */
  // ★ 测试/旧路径：全缺省时钟，不读 store；生产 Shell 必须用带 CalendarService 的重载（CalendarService.load）。
  public LevyRegionTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this(core, query, initiator, mapId, CalendarService.defaults());
  }

  /** 生产构造器：历法时钟来自启动期 {@link CalendarService#load} 的同一实例。 */
  public LevyRegionTool(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      CalendarService calendarService) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 辖区一次性抽取（组合工具，一批 = 一条 revision）：从单位辖区的家户 actor 账抽粮/钱/布、从该区域抽人力，"
        + "粮/钱/人各自受 unit.SetJurisdiction 的 levy*CapPerCommand 约束（0 = 无额度）；"
        + "★ cloth 本批只受可用量约束，上限字段留后续（Jurisdiction 没有第四条上限）。"
        + "载荷 {unitId(必填), regionId(必填), grain?, money?, cloth?, manpower?(四项可选 long，缺省 0；"
        + "负数拒、四项全 0 拒), reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "口径：单位必须存在且管辖含该 region、region 必须在地图里、单位必须有当刻有效位置（国库落点）；"
        + "粮/钱/布来源 = region 各 hex 上 HOUSEHOLD 账的可支配量（余额−冻结，AvailableStock 唯一算法；布 = CommodityId(PilotModel.CLOTH)），"
        + "人力来源 = residence 在 region、MALE、当前 tick 落在 AgeBracket.ADULT 的批次；"
        + "总量不足 ⇒ 整条拒（不部分、不截断）；分摊 = 瀑布（可用量/人数降序，同量按账键/批次 id 升序）。"
        + "apply 批：actor.AdjustAccounts（各来源负增量 + 单位国库正增量；粮与布合并进 goods、钱进 money）"
        + "+ social.SeedGroups（各被动批次整组覆盖，带 ageDays/anchorTick/stress 保真、扣后 count 可为 0）"
        + "+ sd.PutInfo（单位 canonical 地址、key="
        + INFO_KEY
        + "、value=JSON 字符串的行动记录，带 cloth 计数）。"
        + "返回 {preview, submitted, tick, unitId, regionId, treasuryLocation, grain/money/cloth/manpower 各"
        + " {requested, available, sources[]}, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "抽取主体：军事单位 id（国库 = ActorRef(UNIT, unitId)）"));
    props.put(
        "regionId", ToolSupport.prop("string", "抽取区域 id：必须在该单位的 jurisdiction key 集里、且存在于当前地图"));
    props.put(
        "grain",
        ToolSupport.prop(
            "integer", "抽粮数量（最小计量单位；可选，缺省 0 = 本维度整段跳过；不得为负，不得超 levyGrainCapPerCommand）"));
    props.put(
        "money",
        ToolSupport.prop("integer", "抽钱金额（毫银；可选，缺省 0 = 本维度整段跳过；不得为负，不得超 levyMoneyCapPerCommand）"));
    props.put(
        "cloth",
        ToolSupport.prop(
            "integer", "抽布数量（毫布；可选，缺省 0 = 本维度整段跳过；不得为负；★ 本批无单命令上限，只受可用量约束，" + "上限字段留后续）"));
    props.put(
        "manpower",
        ToolSupport.prop(
            "integer",
            "抽人力（人；可选，缺省 0 = 本维度整段跳过；不得为负，不得超 levyManpowerCapPerCommand；"
                + "只抽 MALE 且当前 tick 成年档的批次）"));
    props.put("reason", ToolSupport.prop("string", "抽取原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交三条命令的同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId", "regionId", "reason"));
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
      String regionId = ToolSupport.requiredText(args, "regionId");
      String reason = ToolSupport.requiredText(args, "reason");
      long grain = optionalAmount(args, "grain");
      long money = optionalAmount(args, "money");
      long cloth = optionalAmount(args, "cloth");
      long manpower = optionalAmount(args, "manpower");
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
      LevyRegionPlan.Plan plan =
          LevyRegionPlan.plan(
              state, unitId, regionId, grain, money, cloth, manpower, calendarService.clock());
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
          "TOOL_ERROR", "辖区抽取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选数量的参数解析（缺省 0；类型错误由 {@link ToolSupport#optionalLong} 抛 ⇒ BAD_REQUEST）。 */
  private static long optionalAmount(Map<String, Object> args, String name) {
    Long value = ToolSupport.optionalLong(args, name);
    return value == null ? 0L : value;
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组三条命令（按需缺席）的同一批，走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      LevyRegionPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch =
        buildBatch(batchId, plan, reason, branch, new RevisionId(expectedRevision));
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

  /**
   * 组批：{@code actor.AdjustAccounts}? → {@code social.SeedGroups}? → {@code sd.PutInfo}（固定顺序，可复现）。
   *
   * <p>★ 三条共享同一 {@code batchId}（correlationId）与同一 branch/expectedRevision ⇒ {@code submitBatch} 落一条
   * revision。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      LevyRegionPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(3);
    if (plan.hasAccountMovements()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ADJUST_ACCOUNTS_TYPE,
              adjustAccountsPayload(plan)));
    }
    if (plan.hasManpower()) {
      batch.add(
          envelope(batchId, branch, expectedRevision, SEED_GROUPS_TYPE, seedGroupsPayload(plan)));
    }
    batch.add(
        envelope(batchId, branch, expectedRevision, PUT_INFO_TYPE, infoPayload(plan, reason)));
    return List.copyOf(batch);
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

  // ── 载荷：actor.AdjustAccounts ───────────────────────────────────────────────────────

  /**
   * {@code actor.AdjustAccounts} 载荷：各来源家户账的负增量 + 一条国库正增量。
   *
   * <p>★★ 粮 / 布 / 钱<b>按 {@code (owner,格)} 合并</b>：同一本账同时供多个维度时只能出现一条（该命令明令拒同键重复）；粮与布进同一个 {@code
   * goods} 表，钱进 {@code money} 表。条目顺序 = 粮来源瀑布序 → 仅钱的来源瀑布序 → 仅布的来源瀑布序（首次出现的位置保留）→ 国库（恒在最后）， 是内容的纯函数。
   */
  private static String adjustAccountsPayload(LevyRegionPlan.Plan plan) {
    LinkedHashMap<GoodsAccountKey, Long> grainByKey = new LinkedHashMap<>();
    for (LevyRegionPlan.AccountSource source : plan.grain().sources()) {
      grainByKey.put(new GoodsAccountKey(source.owner(), source.at()), -source.amount());
    }
    LinkedHashMap<GoodsAccountKey, Long> moneyByKey = new LinkedHashMap<>();
    for (LevyRegionPlan.AccountSource source : plan.money().sources()) {
      moneyByKey.put(new GoodsAccountKey(source.owner(), source.at()), -source.amount());
    }
    LinkedHashMap<GoodsAccountKey, Long> clothByKey = new LinkedHashMap<>();
    for (LevyRegionPlan.AccountSource source : plan.cloth().sources()) {
      clothByKey.put(new GoodsAccountKey(source.owner(), source.at()), -source.amount());
    }
    LinkedHashSet<GoodsAccountKey> order = new LinkedHashSet<>(grainByKey.keySet());
    order.addAll(moneyByKey.keySet());
    order.addAll(clothByKey.keySet());
    List<Map<String, Object>> entries = new ArrayList<>(order.size() + 1);
    for (GoodsAccountKey key : order) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("owner", actorRefView(key.owner()));
      entry.put("q", key.location().q());
      entry.put("r", key.location().r());
      // ★ 粮与布同在一张 goods 表里：同键同命令只出现一条，扣减逐值对应。
      Map<String, Object> goods = new LinkedHashMap<>();
      if (grainByKey.containsKey(key)) {
        goods.put(PilotModel.GRAIN, grainByKey.get(key));
      }
      if (clothByKey.containsKey(key)) {
        goods.put(PilotModel.CLOTH, clothByKey.get(key));
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
    treasury.put("owner", actorRefView(new ActorRef(ActorKind.UNIT, plan.unitId())));
    treasury.put("q", plan.treasuryLocation().q());
    treasury.put("r", plan.treasuryLocation().r());
    Map<String, Object> treasuryGoods = new LinkedHashMap<>();
    if (plan.grain().requested() > 0L) {
      treasuryGoods.put(PilotModel.GRAIN, plan.grain().requested());
    }
    if (plan.cloth().requested() > 0L) {
      treasuryGoods.put(PilotModel.CLOTH, plan.cloth().requested());
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
    return ToolSupport.json(payload);
  }

  // ── 载荷：social.SeedGroups ─────────────────────────────────────────────────────────

  /**
   * {@code social.SeedGroups} 载荷：每个被动批次一条<b>整组覆盖</b>，必须带原 {@code ageDays}/{@code anchorTick} 与
   * {@code stress} 保真（否则重写会把压力静默清零）；{@code count} 取扣后、可为 0。
   */
  private static String seedGroupsPayload(LevyRegionPlan.Plan plan) {
    List<Map<String, Object>> entries = new ArrayList<>(plan.manpower().sources().size());
    for (LevyRegionPlan.GroupSource source : plan.manpower().sources()) {
      PopulationGroup group = source.group();
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", group.id().value());
      entry.put("q", source.at().q());
      entry.put("r", source.at().r());
      entry.put("sex", group.sex().name());
      entry.put("count", source.countAfter());
      // ★ 保真三件：锚点年龄 / 锚点 tick / 生理压力——整组覆盖不重新解释这批人。
      entry.put("ageDays", group.ageAtAnchorDays());
      entry.put("anchorTick", group.anchorTick());
      entry.put("stress", group.physiologicalStress());
      entries.add(entry);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    return ToolSupport.json(payload);
  }

  // ── 载荷：sd.PutInfo（行动记录）───────────────────────────────────────────────────────

  /**
   * {@code sd.PutInfo} 载荷：单位 canonical 地址 + {@code key="levyRegion"} + {@code value} = JSON
   * <b>字符串</b> （含 unitId/regionId/tick/四项数量/来源计数/reason）+ {@code note} = 人可读摘要 + {@code tick} =
   * 当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一单位的后续抽取自然追加 #1、#2…
   */
  private static String infoPayload(LevyRegionPlan.Plan plan, String reason) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("unitId", plan.unitId());
    value.put("regionId", plan.regionId());
    value.put("tick", plan.tick());
    value.put("grain", plan.grain().requested());
    value.put("money", plan.money().requested());
    value.put("cloth", plan.cloth().requested());
    value.put("manpower", plan.manpower().requested());
    Map<String, Object> sourceCounts = new LinkedHashMap<>();
    sourceCounts.put("grain", plan.grain().sources().size());
    sourceCounts.put("money", plan.money().sources().size());
    sourceCounts.put("cloth", plan.cloth().sources().size());
    sourceCounts.put("manpower", plan.manpower().sources().size());
    value.put("sourceCounts", sourceCounts);
    value.put("reason", reason);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", ToolSupport.json(value));
    payload.put("note", infoText(plan, reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  /**
   * 单位地址（canonical）：只经 {@link Address#parse} → {@link Address#canonical()}（与 {@code
   * RejectDirectiveTool} 同款）——单位进的是地址的<b>根主体</b>位，命名空间是 {@link
   * ToolSupport#UNIT_NAMESPACE}，不写歪、不手拼第二套格式。
   */
  private static String unitAddress(String unitId) {
    return Address.parse(UNIT_ADDRESS_PREFIX + unitId).canonical();
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      LevyRegionPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("regionId", plan.regionId());
    view.put("treasuryLocation", ToolSupport.hexCoord(plan.treasuryLocation()));
    view.put("grain", accountDimensionView(plan.grain()));
    view.put("money", accountDimensionView(plan.money()));
    view.put("cloth", accountDimensionView(plan.cloth()));
    view.put("manpower", manpowerView(plan.manpower()));
    view.put("infoText", infoText(plan, reason));
    return view;
  }

  /** 粮 / 钱 / 布维度视图：{@code {requested, available, sources:[{owner{kind,id},q,r,amount}…]}}。 */
  private static Map<String, Object> accountDimensionView(LevyRegionPlan.Dimension dimension) {
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

  /** 人力维度视图：{@code {requested, available, sources:[{id,q,r,before,taken,after}…]}}。 */
  private static Map<String, Object> manpowerView(LevyRegionPlan.Manpower manpower) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", manpower.requested());
    view.put("available", manpower.available());
    List<Map<String, Object>> sources = new ArrayList<>(manpower.sources().size());
    for (LevyRegionPlan.GroupSource source : manpower.sources()) {
      PopulationGroup group = source.group();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", group.id().value());
      row.put("q", source.at().q());
      row.put("r", source.at().r());
      row.put("before", group.count());
      row.put("taken", source.taken());
      row.put("after", source.countAfter());
      sources.add(row);
    }
    view.put("sources", sources);
    return view;
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
        + "（批次 "
        + plan.manpower().sources().size()
        + "）；reason="
        + reason;
  }
}
