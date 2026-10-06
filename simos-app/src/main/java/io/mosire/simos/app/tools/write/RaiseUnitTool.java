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
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.unit.raiseUnit}（辖区阶段 8 / 计划 §5）：<b>GM 组合工具</b>——从地方抽人力 + 抽粮/钱，
 * 组出一个新单位；命令按 P3 批序<b>同批落一条 revision</b>。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：新单位在 {@code unit} 切片、家户出账/账户与国库入账在 {@code actor} 切片、人在 {@code
 * social} 切片、新家户经济行在 {@code economy} 切片、行动记录在 {@code sd} 切片——单条命令只能落一个命名空间。本工具走 {@link CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link RaiseUnitPlan#plan}（不碰 {@link ToolContext} /
 * {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。不许出现第二份推导，也不许出现第二份分摊
 * （人力 = {@link HouseholdManpowerAllocator} 的 share-aware 家户份额瀑布；粮/钱 = {@link RegionAllocations} 的家户账瀑布）。
 *
 * <p>★★ <b>批的命令（按此 P3 固定顺序，按需缺席）</b>：
 *
 * <ol>
 *   <li>{@code social.SubmitHouseholdWorkOrder}（恒有）：人口腿的<b>唯一</b>命令；{@code
 *       orderId=raise-unit:<unitId>:<tick>:<manpower>} 确定性幂等键，target = 新人口家户 {@code hh-unit:<unitId>}；
 *       {@code plan} = {@code CREATE_HOUSEHOLD}（location = {@code UNIT(newUnitId)}）+ 逐来源 {@code
 *       TRANSFER_MEMBERS(from=来源家户, to=新家户, lotId, count=taken)}（<b>保持同一 lot id</b>，在 from/to 两侧按份额持有，
 *       不派生新 id）；
 *   <li>{@code unit.CreateUnit}（恒有）：{@code
 *       id/name/position=at/households=[新人口家户]/equipment=[{type,amount}]/speed/mobilityPerMille/parent?}；{@code
 *       jurisdiction} 不进载荷 ——{@code CreateUnitHandler} 对新建单位一律取 {@code Optional.empty()}；
 *   <li>{@code economy.RegisterHousehold}（恒有，P3）：给新人口家户补 {@code HouseholdEconomy} 行（0 人/0 劳动/0 钱；
 *       {@code view=(at,residence,landless_laborer)}，{@code at} 是某城 {@code at} ⇒ urban 否则 rural）；
 *   <li>{@code actor.EnsureHouseholdAccount}（恒有，P3）：给新人口家户补零余额账户（幂等；账户归 actor 切片）；
 *   <li>{@code actor.AdjustAccounts}（仅粮/钱任一 &gt; 0 时）：各来源家户账的<b>负增量</b>（粮与钱合并进同一 {@code (owner,格)}
 *       条目，避免同键重复）+ 新单位国库账户（{@code ActorRef(UNIT, newUnitId)} @ {@code at}）的 <b>正增量</b>；逐值相等（Σ 扣减
 *       == 入库）；
 *   <li>{@code sd.PutInfo}（恒有）：行动记录，地址 = 单位 canonical（{@link RaiseUnitPlan#unitAddress}），{@code
 *       key="raiseUnit"}，{@code value} = JSON <b>字符串</b>（unit/region/at/三项数量/来源计数/sources/reason），{@code
 *       note} = 人可读摘要，{@code tick} = 当前世界日。
 * </ol>
 *
 * <p>★★ <b>P1.3 / P3 口径</b>：{@code unit.CreateUnit} <b>不携带已退役的 {@code manpower}</b>；人员由
 * {@code households=[新人口家户]} 承载。人口腿不再逐条发 {@code social.CreateHousehold} /
 * {@code social.TransferHouseholdMembers}，收成上一条 {@code social.SubmitHouseholdWorkOrder}；旧
 * {@code social.SeedGroups} 批也已从本工具移除。P3 起同批补齐新家户的 economy 行与 actor 账户，<b>次日
 * advance</b> 不再在 {@code CLASSROW_POPULATION_PROJECTION_UNRESOLVED} 处 fail-closed。
 *
 * <p>★ <b>{@code tools} 本批不做</b>：actor 账只有商品 / 货币两维（工具形态不落 actor）⇒ 载荷出现 {@code tools} 键一律具名 {@link
 * IllegalArgumentException}（折 {@code BAD_REQUEST}），<b>不静默忽略</b>；装备只走 {@code equipment} 参数。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ {@code actor.AdjustAccounts} 与 {@code actor.EnsureHouseholdAccount}、
 * {@code economy.RegisterHousehold} 都标了 {@code GmOnlyCommand}，令 / {@code RegisterEffect} / 决策人 catalog
 * 三条路径到不了这些结构写口；本工具走 GM 授权上下文同批提交。
 *
 * <p>★ <b>资源声明</b>：只写 {@code economy}/{@code unit}/{@code actor}/{@code social}/{@code sd} 五个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}，GM 侧五面 unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM
 * 窄写同制。
 *
 * <p>★ <b>工具结果（preview 与 apply 同形）</b>：{@code
 * preview/submitted/tick/unitId/name/regionId/at/residence/parent/householdId/population/equipment[{type,amount}]/
 * speed/mobilityPerMille} + 逐维度 {@code grain}/{@code money} 各 {@code {requested, available,
 * sources[]}}（账来源带 owner + 格 + amount）+ {@code manpowerAllocation {requested, available,
 * sources[]}}（人力来源带 householdId + lotId + taken + hex）+ {@code commands}（将落的命令类型顺序）+ {@code
 * infoText}；apply 另加 {@code submission}（committed / conflict / rejected + 逐条拒因）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 负值 / manpower &lt; 1 / 非法速度或机动性 / 装备为负 / newUnitId 已存在 / region 不存在 /
 * {@code at} 不在 region / parent 不存在或不同格 / 三项来源总量不足 ⇒ {@link IllegalArgumentException} 折 {@code
 * BAD_REQUEST}；批被整条拒 ⇒ {@code REJECTED} 带逐条可读拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class RaiseUnitTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.raiseUnit";

  /** 本工具提交的命令类型（固定，模型够不着 type；唯一拼写点在 {@link RaiseUnitPlan}）。 */
  static final String CREATE_UNIT_TYPE = RaiseUnitPlan.CREATE_UNIT_TYPE;

  /** 见 {@link #CREATE_UNIT_TYPE}。 */
  static final String ADJUST_ACCOUNTS_TYPE = RaiseUnitPlan.ADJUST_ACCOUNTS_TYPE;

  /** P1.3：人口腿唯一命令（Social 家户工单；见 {@link #CREATE_UNIT_TYPE}）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE =
      RaiseUnitPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE;

  /** P3：新人口家户的 economy 经济行登记命令（见 {@link #CREATE_UNIT_TYPE}）。 */
  static final String REGISTER_HOUSEHOLD_TYPE = RaiseUnitPlan.REGISTER_HOUSEHOLD_TYPE;

  /** P3：新人口家户的 actor 零余额账户命令（见 {@link #CREATE_UNIT_TYPE}）。 */
  static final String ENSURE_HOUSEHOLD_ACCOUNT_TYPE = RaiseUnitPlan.ENSURE_HOUSEHOLD_ACCOUNT_TYPE;

  /** 见 {@link #CREATE_UNIT_TYPE}。 */
  static final String PUT_INFO_TYPE = RaiseUnitPlan.PUT_INFO_TYPE;

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 新单位 canonical，一次组军一条；同单位后续行动自然追加序号）。 */
  public static final String INFO_KEY = "raiseUnit";

  /** {@code tools} 键的具名拒因（计划 §5：用具来源本批不做，不静默忽略）。 */
  static final String TOOLS_REJECT_MESSAGE =
      "载荷不支持 tools 键：actor 账只有商品/货币两维；工具形态不落 actor；本批不做用具来源" + "（装备只走 equipment 参数，不从账本抽）";

  /** 本工具写 economy / unit / actor / social / sd 五个命名空间（GM 侧五面 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest RAISE_UNIT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #RAISE_UNIT_WRITE} 同源的逐命名空间粗断言（GM 五面 unlimited；顺序 = 批内命令的命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
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
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与 {@code LevyRegionTool} 同制；本工具资源 声明是命名空间的粗断言、单位地址按单位 id
   *     定位，不当路径用）
   */
  // ★ 测试/旧路径：全缺省时钟，不读 store；生产 Shell 必须用带 CalendarService 的重载（CalendarService.load）。
  public RaiseUnitTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this(core, query, initiator, mapId, CalendarService.defaults());
  }

  /** 生产构造器：历法时钟来自启动期 {@link CalendarService#load} 的同一实例。 */
  public RaiseUnitTool(
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
    return "GM 组军（组合工具，一批 = 一条 revision）：从 region 的 Social 家户份额抽 manpower（share-aware，MALE +"
        + " 成年档；同一 lot 可被多户持有）、从 region 各 hex 的 HOUSEHOLD 账抽 grain/money；同批先用一张 "
        + "social.SubmitHouseholdWorkOrder（CREATE_HOUSEHOLD + 逐来源 TRANSFER_MEMBERS）把成员转进"
        + " location=UNIT(newUnitId) 的新人口家户，再建出新单位（unit.households = [该家户]，人口 = 转移人数）、"
        + "补该家户的 economy 经济行与 actor 零余额账户、把粮/钱落进新单位国库，并落一条 sd.PutInfo 行动记录"
        + "（★ P1.3：不再写 unit.manpower 第二本 headcount，也不再逐条发 CreateHousehold/TransferHouseholdMembers；"
        + "★ P3：同批补齐 economy.RegisterHousehold + actor.EnsureHouseholdAccount，打通 raiseUnit 后的 advance）。"
        + "载荷 {newUnitId(必填), name(必填), regionId(必填), at{q,r}(必填, 必须在该 region 的 hex 集里), "
        + "manpower(必填 long, >=1；抽取人数), grain?(缺省 0), money?(缺省 0), speed(必填, >=1), "
        + "mobilityPerMille(必填, 1..1000), equipment?(缺省空表, 值 >=0；输入 map 按迭代序转成装备表), "
        + "parent?(可选, 必须存在且与 at 同格), reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "★ 不实现 tools：载荷出现 tools 键一律具名拒（actor 账只有商品/货币两维；本批不做用具来源）。"
        + "口径：newUnitId 不得与既有单位重复（不得复用）；人力来源 = residence 在 region、家户持有份额、MALE、"
        + "当前 tick 落在 AgeBracket.ADULT（同一 lot 多户持有各自成候选 ⇒ 拆分批次可用）；"
        + "粮/钱来源 = region 各 hex 上 HOUSEHOLD 账的可支配量（余额−冻结，AvailableStock 唯一算法）；"
        + "总量不足 ⇒ 整条拒（不部分、不截断）；分摊 = 瀑布（人力 = HouseholdManpowerAllocator 的家户份额全序；"
        + "粮/钱 = 账可用量降序）。"
        + "新家户 economy 行=0 人/0 劳动/0 钱、view=(at, residence, landless_laborer)、participationPerMille=0；"
        + "residence 默认：at 是某座 Social 城的 at ⇒ urban，否则 rural。"
        + "apply 批（固定顺序）：social.SubmitHouseholdWorkOrder（orderId=raise-unit:<unitId>:<tick>:<manpower>，"
        + "target=hh-unit:<unitId>，plan=CREATE_HOUSEHOLD+逐来源 TRANSFER_MEMBERS）→ unit.CreateUnit"
        + " → economy.RegisterHousehold（household=hh-unit:<unitId>）→ actor.EnsureHouseholdAccount"
        + "（household=hh-unit:<unitId>）→ actor.AdjustAccounts（家户负增量 + 新单位国库正增量；仅粮/钱>0 时）"
        + " → sd.PutInfo（单位 canonical 地址、key="
        + INFO_KEY
        + "、value=JSON 字符串的行动记录）。"
        + "返回 {preview, submitted, tick, unitId, name, regionId, at, residence, parent, householdId, population, "
        + "equipment[{type,amount}], speed, mobilityPerMille, grain/money 各 {requested, available, sources[]}, "
        + "manpowerAllocation {requested, available, sources[]}, commands, infoText}；"
        + "apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("newUnitId", ToolSupport.prop("string", "新单位 id（不得与既有单位重复；重名不拒，id 才是身份）"));
    props.put("name", ToolSupport.prop("string", "新单位名（非空白）"));
    props.put("regionId", ToolSupport.prop("string", "来源区域 id：必须存在于当前地图，且 at 在它的 hex 集里"));
    props.put(
        "at",
        ToolSupport.prop(
            "object", "新单位落点 {\"q\":整数,\"r\":整数} = 国库落点；必须在 region.hexes() 里（不默认、不猜中心）"));
    props.put(
        "manpower",
        ToolSupport.prop(
            "integer",
            "抽人口（人；>=1；只抽 MALE 且当前 tick 成年档的 Social 家户份额，同一 lot 多户持有各自成候选）；"
                + "P1.3 起落成新家户的成员，不写 unit.manpower"));
    props.put("grain", ToolSupport.prop("integer", "抽粮（最小计量单位；可选，缺省 0 = 本维度整段跳过；不得为负）"));
    props.put("money", ToolSupport.prop("integer", "抽钱（毫银；可选，缺省 0 = 本维度整段跳过；不得为负）"));
    props.put("speed", ToolSupport.prop("integer", "新单位速度（>=1；必填，不发明默认值）"));
    props.put("mobilityPerMille", ToolSupport.prop("integer", "新单位机动性（千分；1..1000；必填，不发明默认值）"));
    props.put(
        "equipment",
        ToolSupport.prop(
            "object", "装备 {字符串:整数}（可选，缺省空表；值 >=0，按输入 map 迭代序转成新 Unit 的 [{type,amount}] 表，不从账本抽）"));
    props.put("parent", ToolSupport.prop("string", "父单位 id（可选；必须存在且当刻有效位置与 at 同格）"));
    props.put("reason", ToolSupport.prop("string", "组军原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(
        props,
        List.of(
            "newUnitId",
            "name",
            "regionId",
            "at",
            "manpower",
            "speed",
            "mobilityPerMille",
            "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return RAISE_UNIT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "组军 newUnitId="
            + args.get("newUnitId")
            + " name="
            + args.get("name")
            + " regionId="
            + args.get("regionId")
            + " at="
            + args.get("at")
            + " manpower="
            + args.get("manpower")
            + " grain="
            + args.getOrDefault("grain", 0)
            + " money="
            + args.getOrDefault("money", 0)
            + " speed="
            + args.get("speed")
            + " mobilityPerMille="
            + args.get("mobilityPerMille")
            + " parent="
            + args.get("parent")
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
      // ★ tools 本批不做：出现键就具名拒，不静默忽略（计划 §5）。
      if (args.containsKey("tools")) {
        throw new IllegalArgumentException(TOOLS_REJECT_MESSAGE);
      }
      String newUnitId = ToolSupport.requiredText(args, "newUnitId");
      String name = ToolSupport.requiredText(args, "name");
      String regionId = ToolSupport.requiredText(args, "regionId");
      String reason = ToolSupport.requiredText(args, "reason");
      HexCoord at = requiredHex(args, "at");
      long manpower = ToolSupport.requiredLong(args, "manpower");
      long grain = optionalAmount(args, "grain");
      long money = optionalAmount(args, "money");
      int speed = requiredInt(args, "speed");
      int mobilityPerMille = requiredInt(args, "mobilityPerMille");
      Map<String, Integer> equipment = optionalEquipment(args);
      String parent = ToolSupport.optionalText(args, "parent", null);
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
      RaiseUnitPlan.Plan plan =
          RaiseUnitPlan.plan(
              state,
              newUnitId,
              name,
              regionId,
              at,
              manpower,
              grain,
              money,
              speed,
              mobilityPerMille,
              equipment,
              Optional.ofNullable(parent),
              calendarService.clock());
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
          "TOOL_ERROR", "组军失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数解析小件 ─────────────────────────────────────────────────────────────────────

  /** 可选数量的参数解析（缺省 0；类型错误由 {@link ToolSupport#optionalLong} 抛 ⇒ BAD_REQUEST）。 */
  private static long optionalAmount(Map<String, Object> args, String name) {
    Long value = ToolSupport.optionalLong(args, name);
    return value == null ? 0L : value;
  }

  /** 必填 int 参数（先按 long 解析，再显式判 int 范围——不静默截断）。 */
  private static int requiredInt(Map<String, Object> args, String name) {
    long value = ToolSupport.requiredLong(args, name);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + " 超出 int 范围: " + value);
    }
    return (int) value;
  }

  /** 必填的 {@code {"q":整数,"r":整数}} 对象（新单位落点）。 */
  private static HexCoord requiredHex(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (!(raw instanceof Map<?, ?> object)) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为 {\"q\":整数,\"r\":整数} 对象");
    }
    return new HexCoord(
        requiredIntComponent(object.get("q"), name, "q"),
        requiredIntComponent(object.get("r"), name, "r"));
  }

  /** 一个坐标分量：必须是整数（允许 Integer/Long 等整型 Number；浮点必须无小数部分），且落在 int 范围内。 */
  private static int requiredIntComponent(Object value, String field, String part) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("参数 " + field + " 的 " + part + " 必须是整数");
    }
    if (number instanceof Double || number instanceof Float) {
      if (hasFraction(number.doubleValue())) {
        throw new IllegalArgumentException("参数 " + field + " 的 " + part + " 必须是整数: " + value);
      }
    }
    long asLong = number.longValue();
    if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + field + " 的 " + part + " 超出 int 范围: " + value);
    }
    return (int) asLong;
  }

  /**
   * 浮点数是否有小数部分（NaN / 无穷 ⇒ 也算"不是整数"）。
   *
   * <p>★ 用 {@link BigDecimal} 精确判定，**不写浮点相等/取模比较**——那是 SpotBugs 的 {@code
   * FE_FLOATING_POINT_EQUALITY} 靶子；{@code BigDecimal.valueOf} 走 {@code Double.toString} 的十进制字面量，
   * {@code stripTrailingZeros().scale() > 0} 才是"确实有小数位"的判据（{@code 2.0} 归一成 {@code 2}，scale=0）。
   */
  private static boolean hasFraction(double value) {
    if (!Double.isFinite(value)) {
      return true;
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().scale() > 0;
  }

  /** 可选装备表：缺席/null ⇒ 空表；键必须是非空文本、值必须是 int 范围内的整数（负值由推导期具名拒）。 */
  private static Map<String, Integer> optionalEquipment(Map<String, Object> args) {
    Object raw = args.get("equipment");
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> object)) {
      throw new IllegalArgumentException("参数 equipment 若给出必须是 {字符串:整数} 对象");
    }
    Map<String, Integer> equipment = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : object.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 equipment 的键必须是非空文本");
      }
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalArgumentException("参数 equipment 的值必须是整数: " + key + "=" + entry.getValue());
      }
      if (number instanceof Double || number instanceof Float) {
        if (hasFraction(number.doubleValue())) {
          throw new IllegalArgumentException(
              "参数 equipment 的值必须是整数: " + key + "=" + entry.getValue());
        }
      }
      long asLong = number.longValue();
      if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
            "参数 equipment 的值超出 int 范围: " + key + "=" + entry.getValue());
      }
      equipment.put(key, (int) asLong);
    }
    return equipment;
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组按需多条命令（按需缺席）的同一批，走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      RaiseUnitPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
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
   * 组批：{@code social.SubmitHouseholdWorkOrder} → {@code unit.CreateUnit} →
   * {@code economy.RegisterHousehold} → {@code actor.EnsureHouseholdAccount} → {@code actor.AdjustAccounts}? →
   * {@code sd.PutInfo}（P3 固定顺序，可复现）。
   *
   * <p>★ 各条共享同一 {@code batchId}（correlationId）与同一 branch/expectedRevision ⇒ {@code submitBatch} 落一条
   * revision。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      RaiseUnitPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(6);
    // ★★ P1.3 批序：先落一张 Social 工单（建人口家户 + 把抽取份额转移进去），再建 unit 并带 households=[该家户]。
    // ★★ P3 批序：紧接同批给新家户补 economy 经济行与 actor 账户（后者恒有、幂等），再按需 actor 国库/行动记录。
    //   同批 = 一条 revision ⇒ Unit.households 与 Household.location 天然一致；次日 advance 不再缺经济行。
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(reason)));
    batch.add(
        envelope(
            batchId, branch, expectedRevision, CREATE_UNIT_TYPE, plan.createUnitPayloadJson()));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            REGISTER_HOUSEHOLD_TYPE,
            plan.registerHouseholdPayloadJson(reason)));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            ENSURE_HOUSEHOLD_ACCOUNT_TYPE,
            plan.ensureHouseholdAccountPayloadJson(reason)));
    if (plan.hasGrainOrMoney()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ADJUST_ACCOUNTS_TYPE,
              plan.adjustAccountsPayloadJson()));
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

  // ── 载荷：sd.PutInfo（行动记录）───────────────────────────────────────────────────────

  /**
   * {@code sd.PutInfo} 载荷：单位 canonical 地址 + {@code key="raiseUnit"} + {@code value} = JSON
   * <b>字符串</b> + {@code note} = 人可读摘要 + {@code tick} = 当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一单位的后续行动自然追加序号。
   */
  private static String infoPayload(RaiseUnitPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", RaiseUnitPlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      RaiseUnitPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("name", plan.name());
    view.put("regionId", plan.regionId());
    view.put("at", ToolSupport.hexCoord(plan.at()));
    view.put("residence", plan.residence().value());
    view.put("parent", plan.parent().orElse(null));
    view.put("householdId", plan.householdId());
    view.put("population", plan.manpowerCount());
    view.put("speed", plan.speed());
    view.put("mobilityPerMille", plan.mobilityPerMille());
    view.put("equipment", ToolSupport.compositionView(plan.equipment()));
    view.put("grain", accountDimensionView(plan.grain()));
    view.put("money", accountDimensionView(plan.money()));
    // ★ P1.3：新单位人口发在 population；抽取来源份额发在 manpowerAllocation，避免同名字段两个形状。
    view.put("manpowerAllocation", manpowerView(plan));
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }

  /** 粮 / 钱维度视图：{@code {requested, available, sources:[{owner{kind,id},q,r,amount}…]}}。 */
  private static Map<String, Object> accountDimensionView(
      RegionAllocations.AccountAllocation dimension) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", dimension.requested());
    view.put("available", dimension.available());
    List<Map<String, Object>> sources = new ArrayList<>(dimension.sources().size());
    for (RegionAllocations.AccountSource source : dimension.sources()) {
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

  /** 人力维度视图：{@code {requested, available, sources:[{householdId,lotId,taken,hex}…]}}（与工单来源同形）。 */
  private static Map<String, Object> manpowerView(RaiseUnitPlan.Plan plan) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", plan.manpowerCount());
    view.put("available", plan.available());
    view.put("sources", plan.sourcesView());
    return view;
  }

  /** 行内 owner 视图（{@code {kind,id}}；与 AdjustAccounts 载荷的 owner 同形）。 */
  private static Map<String, Object> actorRefView(ActorRef owner) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", owner.kind().name());
    view.put("id", owner.id());
    return view;
  }
}
