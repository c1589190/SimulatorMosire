package io.mosire.simos.app;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.ConfirmGate;
import io.mosire.agentlib.approval.HttpApprovalChannel;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.gm.RecordingToolSource;
import io.mosire.simos.app.gui.GuiServer;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.llm.LlmProviderResolver;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.sd.DecisionAdjudicationService;
import io.mosire.simos.app.sd.SdCommandDrain;
import io.mosire.simos.app.sd.channel.CliDecisionChannel;
import io.mosire.simos.app.sd.channel.GuiDecisionChannel;
import io.mosire.simos.app.sd.channel.HttpDecisionChannel;
import io.mosire.simos.app.skill.SkillLibrary;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.spi.CreateRegionHandler;
import io.mosire.simos.map.spi.DeleteRegionHandler;
import io.mosire.simos.map.spi.RandomizeRegionHandler;
import io.mosire.simos.map.spi.RegisterPathwayGroupHandler;
import io.mosire.simos.map.spi.SetEdgeHandler;
import io.mosire.simos.map.spi.SetTerrainHandler;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.channel.ActorId;
import io.mosire.simos.sd.channel.DecisionChannel;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.guard.RegionDeleteGuard;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.resolve.SdResolver;
import io.mosire.simos.sd.spi.AddStageHandler;
import io.mosire.simos.sd.spi.CancelEffectHandler;
import io.mosire.simos.sd.spi.CommitOutcomeHandler;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateCombatHandler;
import io.mosire.simos.sd.spi.CreateDecisionMakerHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import io.mosire.simos.sd.spi.IssueDirectiveHandler;
import io.mosire.simos.sd.spi.PutInfoHandler;
import io.mosire.simos.sd.spi.RecordCasualtiesHandler;
import io.mosire.simos.sd.spi.RegisterEffectHandler;
import io.mosire.simos.sd.spi.ResetDecisionMakerConversationHandler;
import io.mosire.simos.sd.spi.RunDecisionHandler;
import io.mosire.simos.sd.spi.SetDecisionMakerAccessHandler;
import io.mosire.simos.sd.spi.SetDecisionMakerProviderHandler;
import io.mosire.simos.sd.spi.SetDirectiveStatusHandler;
import io.mosire.simos.sd.spi.SetOutcomeTableHandler;
import io.mosire.simos.sd.spi.StartDecisionHandler;
import io.mosire.simos.sd.spi.SubmitVerdictHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.facet.PopulationFacet;
import io.mosire.simos.social.resolve.SocialResolver;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.facet.UnitsHereFacet;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.resolve.UnitResolver;
import io.mosire.simos.unit.spi.ApplyCasualtiesHandler;
import io.mosire.simos.unit.spi.AttachUnitHandler;
import io.mosire.simos.unit.spi.CancelRouteHandler;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.DetachUnitHandler;
import io.mosire.simos.unit.spi.DisbandUnitHandler;
import io.mosire.simos.unit.spi.MergeFormationHandler;
import io.mosire.simos.unit.spi.PlaceAtHandler;
import io.mosire.simos.unit.spi.PlanRouteHandler;
import io.mosire.simos.unit.spi.PlanSparseRouteHandler;
import io.mosire.simos.unit.spi.RenameUnitHandler;
import io.mosire.simos.unit.spi.ReparentSubtreeHandler;
import io.mosire.simos.unit.spi.ReparentUnitHandler;
import io.mosire.simos.unit.spi.SetFormationOffsetHandler;
import io.mosire.simos.unit.spi.SetRejoinTargetHandler;
import io.mosire.simos.unit.spi.SetStatusHandler;
import io.mosire.simos.unit.spi.SetStrengthHandler;
import io.mosire.simos.unit.spi.SplitFormationHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.unit.spi.UpdateCommandChainHandler;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外壳：**唯一的装配点**（spec §3.2 的 1~7 步）。
 *
 * <p>★ **它是全仓唯一组装 CoreSimos 与领域模块的地方**：Core 的 main scope 看不见任何领域类型（ADR-1），四 codec / 全部 handler / 一
 * participant 必须由组合根注入。审批链（T6）、MCP 服务（T7）与 GUI（T8）都已接上——{@link #start} 走到"世界能提交命令、能重放、 能推进、能查询、能经
 * {@code /api} 与 MCP 工具面读写、写命令要过人审批"为止。
 *
 * <p>★ **装配清单**（spec §3.2 第 1~7 步）：四 codec（map/social/unit/sd）+ 全部 handler + 一 participant（{@link
 * CoreSimos} 侧，另有一个写前守卫 {@code RegionDeleteGuard}）+ 四 {@code Resolver} （map/social/unit/sd）+ 两
 * {@code FacetProvider}（unitsHere/population）→ {@link QueryService}（查询层，T3）；审批链（T6，S5：{@code
 * PendingApprovals → HttpApprovalChannel → ApprovalCoordinator → ApprovalHttpEndpoint}，无 Superior
 * 判定）→ {@link SimosToolSource}（spec §2.1：**唯一的 MCP 口 = {@link SimosToolSource.Role#GM}** = 读工具 +
 * 通用写 + 全部窄写；**条数以工具面为准**，不在此钉死）经 {@code McpSourceBridge.bind} 同步进 {@link ToolRegistry}（T5）→ {@link
 * AgentToMcpServer#startHttp}（**一次**，第 6 步，T7）→ GUI（第 7 步，T8）。
 *
 * <p>★ **本条刻意不钉 handler 条数**：写「四十二」的时候**实际已经是 44**（漏改过两次），而条数由下面那个注册块唯一决定、 看一眼就知道 ⇒
 * 钉死只会制造一处没人维护的谎（与工具面注释同一条纪律）。
 *
 * <p>★ **本类不持有任何存储写路径**：{@code SqliteStore} / {@code Timeline.appendRevision} / {@code
 * CheckpointStore} 一个都不在 app 源码里（铁律 2 的结构化，R1 的扫描对象）。唯一的写入口是 {@link
 * CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 */
public final class Shell implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  /** MCP server 自报名称（spec §7.2）。 */
  private static final String MCP_SERVER_NAME = "simos-shell";

  /** MCP server 自报版本（spec §7.2 的 {@code "0.1.0-SNAPSHOT"}）。 */
  private static final String MCP_SERVER_VERSION = "0.1.0-SNAPSHOT";

  /** 审批等待上限（spec 未定值，M5 取 5 分钟）：到点没人答 ⇒ fail-closed 拒（AgentLib 契约）。 */
  private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellConfig config;
  private final CoreSimos coreSimos;

  /** 查询层（T3）：GUI 与工具集唯一的只读入口（spec §5.1）。 */
  private final QueryService queryService;

  /**
   * 工具注册表（T5）：GM 组（{@link SimosToolSource.Role#GM}，= 唯一的 MCP 口）的全部工具经桥同步进此表，交给 {@code
   * AgentToMcpServer}。
   */
  private final ToolRegistry toolRegistry;

  /** 工具源 ↔ 注册表的同步桥（T5）：{@link #close()} 时整组下架本桥带入的工具。 */
  private final McpSourceBridge toolBridge;

  /** MCP 服务（T7，spec §7.2）：5715 的流式 HTTP 面；关闭次序里排第二（spec §3.3）。★ 全仓**唯一**一个 MCP 口。 */
  private final AgentToMcpServer mcpServer;

  /** GUI 服务器（T8）：5711 的静态页 + {@code /api}；关闭次序里排第一（spec §3.3）。 */
  private final GuiServer guiServer;

  /** 审批登记表（T6，spec §3.2 第 3 步）：进程内唯一的那一份，端点与通道共享同一 id。 */
  private final PendingApprovals pendingApprovals;

  /** HTTP 审批通道（T6）：端点真的绑定成功后才 {@code markUp()}（可用性认"端口在监听"）。 */
  private final HttpApprovalChannel approvalChannel;

  /** 审批编排器（T6，S5）：gates = {@code AutoApproveGate → ConfirmGate}，M5 无 LLM 上级判定。 */
  private final ApprovalCoordinator approvalCoordinator;

  /** 审批 HTTP 端点（T6，spec §3.2 第 4 步）：恒绑回环；关闭次序在 GUI 之后（spec §3.3）。 */
  private final ApprovalHttpEndpoint approvalEndpoint;

  /** 工具调用唯一入口（T6）：**带审批**（非 {@code standard()}），T7 交给 {@code startHttp}。 */
  private final ToolCallAuthorizer toolAuthorizer;

  /** 已注册模块 codec 的个数（map/social/unit/sd）；由实际注册动作数出来，不是写死的常量。 */
  private final int registeredModuleCount;

  /**
   * 跨模块效果落点（C5，spec §五.3）：{@code AdvanceTime} 提交成功后把 sd 的 pending 指令经 {@code submit} 落成真 revision。
   */
  private final SdCommandDrain sdCommandDrain;

  /** 决策提交渠道（D5，spec §十三）：GUI / MCP / CLI / 外部 HTTP 各一实现——**新增渠道不改领域代码**。 */
  private final List<DecisionChannel> decisionChannels;

  /** 已注册命令类型（D6）：按角色重建工具面时供 catalog 读。 */
  private final Set<String> commandTypes;

  /**
   * Skill 库（2026-09-23）：决策人的**外部方法论与常识**（仓库种子 {@code config/skills} + store 覆盖
   * {@code <storeDir>/skills}）。
   *
   * <p>★ **由 {@code storeDir} 现推、不进装配参数**：它不在世界 revision 内（与 {@code conversations.db}、
   * {@code agentlib/} 同族），没有"该配给哪个世界"这一维；多一个装配参数只会多一处可能传错的地方。
   */
  private final SkillLibrary skillLibrary;

  /**
   * {@code 命令类型 → 目标声明}（第 3 波第 2 步）：从**已注册的 handler 清单**派生——实现了 {@link CommandTargets}
   * 的那些把自己的目标交出来。{@code sd.AdjudicateTick} 拿它判"GM 代执行的这条命令动的 是谁"；**未实现者不在表里** ⇒ 工具侧 fail-closed 拒。
   */
  private final Map<String, CommandTargets> commandTargets;

  /**
   * LLM provider 配置（M11′ 对接版）：由 AgentLib 的 {@code ConfigStore} 承载 {@code llm.routes.*} / {@code
   * keys.*} （用户裁定「Provider 配置归 AgentLib」）。
   */
  private final AgentLibLlmConfig llmConfig;

  /** 决策编排（T3）：把 {@code AdjudicatorRunner} 接进壳——「开始决策」真的会跑 LLM 判决。 */
  private final DecisionAdjudicationService decisionAdjudicationService;

  /** 决策人 agent 运行流（T11C）：{@code sd.RunDecision} 触发的那一轮（真 LLM + 真工具）。 */
  private final DecisionAgentService decisionAgentService;

  /** 决策人的会话存储（T11C）：落 {@code <store>/conversations.db} ⇒ 跨 tick / 跨重启沿用同一段会话。 */
  private final SqliteConversationStore decisionConversations;

  private volatile boolean closed;

  private Shell(
      ShellConfig config,
      CoreSimos coreSimos,
      QueryService queryService,
      ToolRegistry toolRegistry,
      McpSourceBridge toolBridge,
      AgentToMcpServer mcpServer,
      GuiServer guiServer,
      PendingApprovals pendingApprovals,
      HttpApprovalChannel approvalChannel,
      ApprovalCoordinator approvalCoordinator,
      ApprovalHttpEndpoint approvalEndpoint,
      ToolCallAuthorizer toolAuthorizer,
      int registeredModuleCount,
      SdCommandDrain sdCommandDrain,
      List<DecisionChannel> decisionChannels,
      Set<String> commandTypes,
      Map<String, CommandTargets> commandTargets,
      SkillLibrary skillLibrary,
      AgentLibLlmConfig llmConfig,
      DecisionAdjudicationService decisionAdjudicationService,
      DecisionAgentService decisionAgentService,
      SqliteConversationStore decisionConversations) {
    this.config = config;
    this.coreSimos = coreSimos;
    this.queryService = queryService;
    this.toolRegistry = toolRegistry;
    this.toolBridge = toolBridge;
    this.mcpServer = mcpServer;
    this.guiServer = guiServer;
    this.pendingApprovals = pendingApprovals;
    this.approvalChannel = approvalChannel;
    this.approvalCoordinator = approvalCoordinator;
    this.approvalEndpoint = approvalEndpoint;
    this.toolAuthorizer = toolAuthorizer;
    this.registeredModuleCount = registeredModuleCount;
    this.sdCommandDrain = sdCommandDrain;
    this.decisionChannels = List.copyOf(decisionChannels);
    this.commandTypes = Set.copyOf(commandTypes);
    this.skillLibrary = Objects.requireNonNull(skillLibrary, "skillLibrary");
    this.commandTargets = Map.copyOf(commandTargets);
    this.llmConfig = llmConfig;
    this.decisionAdjudicationService = decisionAdjudicationService;
    this.decisionAgentService = decisionAgentService;
    this.decisionConversations = decisionConversations;
  }

  /**
   * 起壳：建 CoreSimos 并按其装配顺序注册**四 codec + 全部 handler + 一 participant**，再装**查询层**（三个 resolver + 两个真
   * facet + {@link QueryService}）。
   *
   * <p>★ {@code MovementCost} 由 app 注入（M3 口径）：{@link TerrainMovementCost} 是当前唯一实现，取它的单例 {@link
   * TerrainMovementCost#INSTANCE}（构造器私有，不能 {@code new}——这是对派单文字 {@code new TerrainMovementCost()}
   * 的一处就地校正）。
   *
   * <p>★ **GUI 在此启动**（T8，spec §3.2 第 7 步）：{@code guiPort=0} 时由 OS 分配随机端口，实际端口经 {@link
   * #boundGuiPort()} 读回；关闭由 {@link #close()} 按 spec §3.3 的次序（GUI 第一）负责。
   *
   * @param config 装配配置
   * @return 已装配、尚未封存的壳（封存发生在第一次 {@code submit}/{@code replay}）
   * @throws NullPointerException {@code config} 为 null
   * @throws IllegalStateException GUI 绑定失败（端口被占等）
   */
  public static Shell start(ShellConfig config) {
    return start(config, null);
  }

  /**
   * 同 {@link #start(ShellConfig)}，但可**显式注入决策人 agent 的 LLM 客户端来源**（T11C）。
   *
   * <p>★ **为什么有这条缝**：{@code sd.RunDecision} 触发的那一轮要**真调 LLM**，而本仓的用例纪律是"测试不打真网络" ⇒ 用例要能把 {@code
   * FakeLlmClient} 注入**真壳**（真 MCP 口、真审批链、真工具面、真 store），而不是另搭一套 装配——那样测到的就不是"壳接到哪里"了。
   *
   * <p>★ **它只替得掉"怎么造客户端"**：未绑定 provider 的 fail-closed 判定在 {@link DecisionAgentService} 里（世界事实那一半），
   * 注入什么实现都绕不过去；生产路径（{@code null}）用 {@link LlmProviderResolver}（配置那一半也 fail-closed）。
   *
   * @param decisionLlmClients {@code null} ⇒ 生产路径（按决策人 {@code providerId} 解析真 provider）
   */
  public static Shell start(
      ShellConfig config, DecisionAgentService.LlmClients decisionLlmClients) {
    Objects.requireNonNull(config, "config");
    CoreSimos coreSimos =
        new CoreSimos(
            new CoreConfig(
                config.storeDir(), config.checkpointInterval(), SimosObjectMapper.create()));

    List<ModuleCodec> codecs =
        List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec());
    for (ModuleCodec codec : codecs) {
      coreSimos.register(codec);
    }

    List<CommandHandler> handlers =
        new ArrayList<>(
            List.of(
                new SetTerrainHandler(),
                new CreateRegionHandler(),
                new UpdateRegionHandler(),
                new DeleteRegionHandler(),
                new SetEdgeHandler(),
                new RegisterPathwayGroupHandler(),
                new RandomizeRegionHandler(),
                new RenameUnitHandler(),
                new CreateUnitHandler(),
                new ReparentUnitHandler(),
                new SetStrengthHandler(),
                new PlaceAtHandler(),
                new PlanRouteHandler(),
                new CancelRouteHandler(),
                new DisbandUnitHandler(),
                new SetStatusHandler(),
                new AttachUnitHandler(),
                new DetachUnitHandler(),
                new ReparentSubtreeHandler(),
                new SetFormationOffsetHandler(),
                new SplitFormationHandler(),
                new MergeFormationHandler(),
                new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE),
                new SetRejoinTargetHandler(),
                new CreateCommandChainHandler(),
                new UpdateCommandChainHandler(),
                new ApplyCasualtiesHandler(),
                new CreateNationHandler(),
                new CreateArmyHandler(),
                new CreateDecisionMakerHandler(),
                new PutInfoHandler(),
                new CreateCombatHandler(),
                new AddStageHandler(),
                new SetOutcomeTableHandler(),
                new CommitOutcomeHandler(),
                new RecordCasualtiesHandler(),
                new CancelEffectHandler(),
                new StartDecisionHandler(),
                new RunDecisionHandler(),
                // ★ 第 3 波最后一块：裁决用的状态翻转命令（sd.SetDirectiveStatus）。它不是对外窄工具——
                //   由 sd.AdjudicateTick 内部编排产生；注册在此是为了它在批里能被 CommandBus 路由到。
                new SetDirectiveStatusHandler()));
    Set<String> drainableCommandTypes = new LinkedHashSet<>();
    for (CommandHandler handler : handlers) {
      if (!handler.type().startsWith("sd.")) {
        drainableCommandTypes.add(handler.type());
      }
    }
    handlers.add(new RegisterEffectHandler(drainableCommandTypes));
    Set<String> commandTypes = new LinkedHashSet<>();
    for (CommandHandler handler : handlers) {
      coreSimos.register(handler);
      commandTypes.add(handler.type());
    }

    // ★ 第 3 波第 2 步：命令的**目标声明**从同一份 handler 清单派生（实现了 CommandTargets 的那些）——
    //   与执行面同源 ⇒ 不需要另立一张会漂移的表；未实现者不在表里 ⇒ 工具侧 fail-closed 拒。
    Map<String, CommandTargets> commandTargets = new LinkedHashMap<>();
    for (CommandHandler handler : handlers) {
      if (handler instanceof CommandTargets targets) {
        commandTargets.put(handler.type(), targets);
      }
    }

    // ★ D1：决策命令白名单从**注册面**推导（禁 sd 自指/通用写）⇒ 必须在上面那个循环之后、用完整的 commandTypes 构造。
    IssueDirectiveHandler issueDirectiveHandler =
        new IssueDirectiveHandler(new DirectiveWhitelist(commandTypes));
    SubmitVerdictHandler submitVerdictHandler = new SubmitVerdictHandler();
    SetDecisionMakerAccessHandler setDecisionMakerAccessHandler =
        new SetDecisionMakerAccessHandler();
    SetDecisionMakerProviderHandler setDecisionMakerProviderHandler =
        new SetDecisionMakerProviderHandler();
    ResetDecisionMakerConversationHandler resetDecisionMakerConversationHandler =
        new ResetDecisionMakerConversationHandler();
    for (CommandHandler late :
        List.of(
            issueDirectiveHandler,
            submitVerdictHandler,
            setDecisionMakerAccessHandler,
            setDecisionMakerProviderHandler,
            resetDecisionMakerConversationHandler)) {
      handlers.add(late);
      coreSimos.register(late);
      commandTypes.add(late.type());
    }

    // ★ T10-h：participant 由**清单**注册、条数由清单长度数出来（曾把 `participant=1` 写死在日志里 ⇒ 将来加第二个会静默说谎）。
    List<TimeParticipant> participants =
        List.of(
            new UnitTimeParticipant(TerrainMovementCost.INSTANCE, config.mapId()),
            new SdTimeParticipant(config.mapId()));
    for (TimeParticipant participant : participants) {
      coreSimos.register(participant);
    }

    // 写前跨模块守卫（A6，spec §九）：带国家 tag 的区域不可删。
    coreSimos.register(new RegionDeleteGuard());

    ResolverRegistry resolverRegistry = new ResolverRegistry();
    resolverRegistry.register(new MapResolver());
    resolverRegistry.register(new SocialResolver());
    resolverRegistry.register(new UnitResolver());
    resolverRegistry.register(new SdResolver());

    FacetRegistry facetRegistry = new FacetRegistry();
    facetRegistry.register(new UnitsHereFacet());
    facetRegistry.register(new PopulationFacet());

    QueryService queryService = new QueryService(coreSimos, resolverRegistry, facetRegistry);

    // ★ M11′：LLM provider 配置落在 AgentLib 的 ConfigStore（<store>/agentlib/config.json），
    //   旧格式（<store>/llm-providers.json）只作一次性迁移来源；决策编排把 AdjudicatorRunner 接进壳。
    AgentLibLlmConfig llmConfig = AgentLibLlmConfig.open(config.storeDir());
    LlmProviderResolver llmProviderResolver = new LlmProviderResolver(llmConfig, config.storeDir());
    DecisionAdjudicationService decisionAdjudicationService =
        new DecisionAdjudicationService(coreSimos, llmProviderResolver);

    // ★ D5：三条决策渠道（spec §十三.3 / §四.3 撤销项）——声明各自可代表的 actor（当前世界里的决策人），最终写同一落点。
    //   ★ 决策人**不经 MCP**（用户裁定③）：旧 McpDecisionChannel 已删除，渠道回到 GUI / CLI / Http 三条。
    Supplier<Set<ActorId>> representableActors = () -> currentActorIds(coreSimos);
    List<DecisionChannel> decisionChannels =
        List.of(
            new GuiDecisionChannel(coreSimos, representableActors),
            new CliDecisionChannel(coreSimos, representableActors),
            new HttpDecisionChannel(coreSimos, representableActors));

    // 审批链（T6，spec §3.2 第 3 步；S5：无 Superior 判定，M5 无 LLM）。
    PendingApprovals pendingApprovals = new PendingApprovals();
    HttpApprovalChannel approvalChannel = new HttpApprovalChannel(pendingApprovals);
    ApprovalCoordinator approvalCoordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pendingApprovals), new ConfirmGate()),
            List.of(approvalChannel),
            pendingApprovals,
            APPROVAL_TIMEOUT,
            null);
    ToolCallAuthorizer toolAuthorizer =
        ToolCallAuthorizer.of(new ToolExecutionGuard(), approvalCoordinator);

    // 端点先真的绑上端口，再 markUp 通道（可用性认"端口在监听"，spec §3.2 第 4 步）。
    // ★ 恒回环，**不**随 config.bindAddress() 变（AgentLib 无 host 形参；对外面是 GUI 的 /api/approvals 代理）。
    ApprovalHttpEndpoint approvalEndpoint =
        ApprovalHttpEndpoint.start(config.approvalPort(), pendingApprovals, approvalCoordinator);
    approvalChannel.markUp();

    // ★ T11C：决策人 agent 运行流的装配（三样在它之前就绪：带审批的 authorizer、provider 解析链、查询层）。
    //   ① 权限组走**同一个** authorizer（决策人的两条窄写是敏感工具 ⇒ 没有审批编排器就永远进不了工具体）；
    //   ② 工具面取**决策人桶**（与白名单同源：DecisionToolDefs.requireAll 对不上就当场炸）；
    //   ③ 会话落 <store> 下（与 simos.db 同层 ⇒ 跨进程重启沿用同一段会话）。
    // ★ Skill 库（2026-09-23）：**装配期建一次**，两个工具面与 Shell 字段共用同一个实例
    //   （外部 Markdown：仓库种子 config/skills + store 覆盖 <storeDir>/skills；读时按 mtime 热更）。
    SkillLibrary skillLibrary = SkillLibrary.open(config.storeDir());
    DecisionCallerFactory decisionCallerFactory = DecisionCallerFactory.defaults(toolAuthorizer);
    ToolRegistry decisionTools = new ToolRegistry();
    decisionTools.registerAll(
        new SimosToolSource(
                coreSimos,
                queryService,
                config.mcpInitiator(),
                config.mapId(),
                commandTypes,
                skillLibrary,
                SimosToolSource.Role.DECISION_AGENT)
            .listTools());
    // ★ 决策人桶**不带**触发工具（决策人不触发自己，那是自环）⇒ 这里用不带运行流的那条构造器。
    SqliteConversationStore decisionConversations =
        SqliteConversationStore.open(
            config.storeDir().resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME));
    DecisionAgentService decisionAgentService =
        new DecisionAgentService(
            coreSimos,
            decisionLlmClients == null
                ? llmProviderResolver::agentLibClientFor
                : decisionLlmClients,
            decisionCallerFactory,
            decisionTools,
            decisionConversations,
            config.mapId());

    // 工具集（spec §2.1）：MCP 口 = **GM 组** = 读工具 + 通用写 + 全部窄写（条数以工具面为准）；
    //   GM 组的权限集是显式构造的那一份（gmCaller 的 resourceScopes 逐命名空间表态），不再是 unrestricted 的空资源图。
    SimosToolSource toolSource =
        new SimosToolSource(
            coreSimos,
            queryService,
            config.mcpInitiator(),
            config.mapId(),
            commandTypes,
            skillLibrary,
            commandTargets,
            SimosToolSource.Role.GM,
            decisionAgentService);
    // ★ T8：GM 交互界面的数据源——GM 口每次工具执行的留痕（工具名 + 结果），经 /api/gm/tool-usage 只读导出。
    //   只包 GM 组的源 ⇒ 记录的就是"GM MCP 的工具使用"（spec §七.4 C22）。
    GmToolUsage gmToolUsage = new GmToolUsage();
    ToolRegistry toolRegistry = new ToolRegistry();
    McpSourceBridge toolBridge =
        McpSourceBridge.bind(RecordingToolSource.record(toolSource, gmToolUsage), toolRegistry);

    // MCP 服务（T7，spec §3.2 第 6 步）：**唯一的**口 = GM 组；authorizer 是带审批的那个。
    // ★ 决策人**不开 MCP**（spec §2.2 的 C3：caller 在 startHttp 时定死，一个端口一份权限集 ⇒ 表达不了"每个决策人一份权限"；
    //   而范围随世界状态变 ⇒ 进程内现算是唯一可行路径）。
    AgentToMcpServer mcpServer = null;
    boolean mcpUp = false;
    try {
      mcpServer =
          AgentToMcpServer.startHttp(
              config.bindAddress(),
              config.mcpPort(),
              config.mcpPath(),
              toolRegistry,
              MCP_SERVER_NAME,
              MCP_SERVER_VERSION,
              gmCaller(),
              toolAuthorizer);
      mcpUp = true;
    } finally {
      if (!mcpUp) {
        // 口绑定失败：桥与审批端点/通道都不能留着占端口（GUI 尚未起，spec §3.3 里它排第一）。
        toolBridge.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    // 走到这里口已监听（绑定失败在上面的 finally 里已收尾并抛出）；显式断言以钉死该后置条件。
    Objects.requireNonNull(mcpServer, "mcpServer");

    // GUI（T8，spec §3.2 第 7 步）：审批面 base URL 指向刚绑定的端点，5711 的 /api/approvals 是它的透传代理。
    GuiServer guiServer =
        new GuiServer(
            queryService,
            coreSimos,
            config.mapId(),
            "http://127.0.0.1:" + approvalEndpoint.boundPort(),
            gmToolUsage,
            llmConfig,
            decisionAdjudicationService,
            // ★ T11C 的运行流（不是判决定编）：/api/sd/run-decision 落地时就是拿它跑那一轮——与 GM 侧的
            //   sd.RunDecision 窄工具**同一个实例**（同一个会话库、同一条 provider 解析链）。
            decisionAgentService);
    boolean guiUp = false;
    try {
      guiServer.start(config.bindAddress(), config.guiPort());
      guiUp = true;
    } finally {
      if (!guiUp) {
        // GUI 绑定失败：MCP 口与审批端点/通道、工具桥不能留着占端口（spec §3.3 里 GUI 排第一，此处它还没起来）。
        mcpServer.close();
        toolBridge.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    LOG.info(
        "Shell 装配完成: store={} checkpointInterval={} codec={} handler={} participant={}"
            + " resolver={} facet={} tool={} mapId={} bindAddress={} mcpPort={} guiPort={}"
            + " approvalPort={}",
        config.storeDir(),
        config.checkpointInterval(),
        codecs.size(),
        handlers.size(),
        participants.size(),
        resolverRegistry.namespaces().size(),
        facetRegistry.facetNames().size(),
        toolRegistry.size(),
        config.mapId(),
        config.bindAddress(),
        mcpServer.boundPort(),
        guiServer.boundPort(),
        approvalEndpoint.boundPort());
    return new Shell(
        config,
        coreSimos,
        queryService,
        toolRegistry,
        toolBridge,
        mcpServer,
        guiServer,
        pendingApprovals,
        approvalChannel,
        approvalCoordinator,
        approvalEndpoint,
        toolAuthorizer,
        codecs.size(),
        new SdCommandDrain(coreSimos),
        decisionChannels,
        commandTypes,
        commandTargets,
        skillLibrary,
        llmConfig,
        decisionAdjudicationService,
        decisionAgentService,
        decisionConversations);
  }

  /** 当前世界里的决策人（作为渠道可代表的 actor；空库 ⇒ 空集）。 */
  private static Set<ActorId> currentActorIds(CoreSimos core) {
    BranchId main = new BranchId("main");
    Optional<RevisionId> head = core.head(main);
    if (head.isEmpty()) {
      return Set.of();
    }
    SimulationState state = core.replay(new StateRef(main, head.get()));
    Snapshot slice = state.module("sd").orElse(null);
    if (!(slice instanceof SdSnapshot sd)) {
      return Set.of();
    }
    Set<ActorId> actors = new LinkedHashSet<>();
    for (DecisionMakerId id : sd.state().decisionMakers().keySet()) {
      actors.add(new ActorId(id.value()));
    }
    return actors;
  }

  /** 决策提交渠道（D5）：测试与运维读回装配的四条渠道。 */
  public List<DecisionChannel> decisionChannels() {
    return decisionChannels;
  }

  /**
   * 按角色重建工具面（D6/§2.1/§四.3）：{@link SimosToolSource.Role#GM} = 运行时 MCP 口（读 + 通用写 + 全部窄写）； {@link
   * SimosToolSource.Role#DECISION_AGENT} = 决策人组（读 + 两条决策窄写）——决策人**不走 MCP**，它那条路的权限由 {@code
   * DecisionCallerFactory} 现算的权限组承担。
   */
  public List<AgentTool> toolsFor(SimosToolSource.Role role) {
    return new SimosToolSource(
            coreSimos,
            queryService,
            config.mcpInitiator(),
            config.mapId(),
            commandTypes,
            skillLibrary,
            commandTargets,
            role,
            decisionAgentService)
        .listTools();
  }

  /** GUI 服务器实际绑定端口（{@code guiPort=0} 时由 OS 分配；spec §3.1 的读回口径，测试用）。 */
  public int boundGuiPort() {
    return guiServer.boundPort();
  }

  /**
   * GUI 服务器实际绑定主机（M10）：回显 {@link ShellConfig#bindAddress()} 的**生效值**（{@code 0.0.0.0} 时 JDK 读回通配形态，
   * 见 {@link java.net.InetAddress#isAnyLocalAddress()}）。运维与测试据此确认绑定面。
   */
  public String boundGuiHost() {
    return guiServer.boundHost();
  }

  /** MCP 服务实际绑定端口（{@code mcpPort=0} 时由 OS 分配；spec §3.1 的读回口径，经 AgentLib {@code boundPort()}）。 */
  public int boundMcpPort() {
    return mcpServer.boundPort();
  }

  /**
   * **GM 组的调用上下文**（J1，spec §2.1）：MCP 口上每次工具调用的调用者（装配期定死；spec §3.2 的 caller，S/P2-F 无 per-session
   * 身份：caller 在 {@code startHttp} 时定死，一个端口一份权限集）。
   *
   * <p>★ **权限边界在"组"、不在"端口"**（本阶段的口径取代）：端口只决定"谁连得上"，能不能碰某个资源由 {@link #gmPermissionSet()}
   * 那份**显式权限集**说了算——决策人组不再靠"另一个端口"表达，而是靠进程内现算的权限集（spec §二）。
   *
   * <p>★ **桶取 {@link AccessToken#DEFAULT} 而非 spec §3.2 字面的 {@code GUEST}**——这是装配期实测的**取代说明**：三条写工具
   * （{@code command.submit}/{@code advance}/{@code fork}）的 {@code ToolSpec} 是 {@code
   * ToolSpec.level(DEFAULT, sensitive=true, destructive=false)}，而 {@code PermissionChecker}
   * 在身份级别不足时**硬拒** （{@code PERMISSION_DENIED}），根本进不了审批闸。{@code GUEST} 桶下三条通用写工具 （{@code
   * command.submit}/{@code advance}/{@code fork}）全部不可达，MCP 只能读、不能写 ⇒ 与 S3/S4 的工具面设计矛盾。{@code
   * DEFAULT} 是**满足该工具面全部工具的最小桶**（读工具只要求 {@code GUEST}）。
   *
   * <p>★ **放行 ≠ 免审批**：敏感工具仍走 {@code ToolGate.Ask}，本壳注入的 authorizer 是带 {@code ApprovalCoordinator}
   * 的那个（非 {@code standard()}）。
   *
   * <p>★ 身份取 {@link AgentIdentity#external()}（{@code external-mcp} 实例 + {@code FULL}
   * 档）：外部客户端不是本进程派生的 Agent，审批面据它认得出"这不是我派的下级"（AgentLib 契约）。注意 AgentLib 的审批 {@code callerKey}
   * 取的是**桶名**（{@code DEFAULT}），不是本 identity 的实例 id（T6 裁定 63 已实测）。
   *
   * <p>★ **包内可见**：这是装配自检与用例的读回口径，**不是**对外 API——对外面只有端口。
   */
  static ToolContext gmCaller() {
    return ToolContext.of(AccessToken.DEFAULT, gmPermissionSet(), AgentIdentity.external());
  }

  /**
   * GM 权限组（J1，spec §2.1）：**显式构造**的权限集 —— 全工具白名单 + {@code sensitiveAllowed} + {@code
   * destructiveAllowed} + **四个命名空间各自 {@code unlimited()}**。
   *
   * <p>★ **它取代 {@code AgentPermissionSet.unrestricted(DEFAULT)}**（spec §1.1 记的根因形态）：那条路走**六参兼容构造**
   * ⇒ {@code resourceScopes} 为 null ⇒ 规范化成**空图** ⇒ 资源判定走"调用者未表态"分支 ⇒ **每一次 {@code require} 都恒真**
   * （断言在跑，但永远通过）。空图与 {@code unlimited()} 在**当前工具面**上行为恰好相同（工具声明的三命名空间缺省策略本就放行），
   * 差别是**表过态**：可达面从此是**显式数据**，而不是"缺省恰好放行"。
   *
   * <p>★ **{@code null} ≠ deny-all**（spec §5.2 第 3 条）：本层是限制层，"没表态"回落下一层（工具自己的 {@code
   * ResourceManifest} 缺省）；想表达"够不着"必须显式写 {@code none()}——两条方向相反，配错即静默放宽。
   *
   * <p>★ **身份仍是 {@link AgentIdentity#external()}**（用户裁定①）：它是**审批面语义**（"这不是本进程派生的下级"），
   * 与权限**级别**正交。GM 若是经 MCP 连入的外部 agent，这个身份是对的。
   */
  private static AgentPermissionSet gmPermissionSet() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .sensitiveAllowed(true)
        .destructiveAllowed(true)
        .resourceScopes(
            ResourceScopeMap.of(
                Map.of(
                    ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.SOCIAL_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.UNIT_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.SD_NAMESPACE, ResourceScope.unlimited())))
        .build();
  }

  /** 审批端点实际绑定端口（{@code approvalPort=0} 时由 OS 分配；spec §3.1 的读回口径）。 */
  public int boundApprovalPort() {
    return approvalEndpoint.boundPort();
  }

  /**
   * 审批登记表（T6）：T7 的 MCP 调用触发审批时，测试/调试面经此读待裁决项并作答。
   *
   * <p>★ SpotBugs 未判它 {@code EI_EXPOSE_REP}（实测：加了抑制反被 {@code US_USELESS_SUPPRESSION_ON_METHOD}
   * 判红）——它本就是给人面 与测试的进程内共享件。
   */
  public PendingApprovals pendingApprovals() {
    return pendingApprovals;
  }

  /**
   * 工具调用唯一入口（T6，spec §7.1/§7.2）：**带审批**（非 {@code standard()}）。T7 把它交给 {@code
   * AgentToMcpServer.startHttp}；测试用它执行写工具以验证 R3（未审批的写调用必须被拒且不留 revision）。
   */
  public ToolCallAuthorizer toolAuthorizer() {
    return toolAuthorizer;
  }

  /** 查询门面（spec §5.1）：GUI（T8）与工具集（T5）经此读状态、解析地址、取 facet。**只读**——写面仍只有 {@link CoreSimos#submit}。 */
  public QueryService queryService() {
    return queryService;
  }

  /** 工具注册表（spec §7.1；T5/T4）：现有口全部工具（通用写 + GM 窄写 + 读）的活清单，T7 交给 {@code AgentToMcpServer}。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "spec §3.2/§7.2 要求把注册表交给 MCP 服务（T7）；它不是内部表示而是本壳的产物本身，与 coreSimos() 同法")
  public ToolRegistry toolRegistry() {
    return toolRegistry;
  }

  /**
   * 底层门面（提交 / 重放 / 只读分支面）。★ 唯一写入口仍是 {@link CoreSimos#submit}。
   *
   * <p>★ **{@code EI_EXPOSE_REP} 是有意豁免（M5 T1 门禁实测）**：spec §3.2 要求组合根对外交出 {@code
   * CoreSimos}；它不是"内部表示"而是本壳的产物本身。SpotBugs 判它可变故报 {@code EI_EXPOSE_REP}，用
   * {@code @SuppressFBWarnings} 精确豁免在**这一个方法**上（注解依赖 provided、不进产物；与 AgentLibMosire/BrainMosire
   * 同法）。护栏不因此松：铁律 2 仍由"app 源码无 store/timeline 写面"的扫描 + 行为面守（R1，T8 落地）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "spec §3.2 要求对外交出 CoreSimos 引用；唯一写入口仍是 CoreSimos.submit，豁免只作用于本方法")
  public CoreSimos coreSimos() {
    return coreSimos;
  }

  /** 生效配置（原样回显，不重算）。 */
  public ShellConfig config() {
    return config;
  }

  /** 已注册的模块 codec 个数（map/social/unit/sd = 4）。{@code ShellMain} 用它打印装配实况。 */
  public int registeredModuleCount() {
    return registeredModuleCount;
  }

  /**
   * 跨模块效果落点（C5，spec §五.3）：{@code AdvanceTime} 提交成功后 drain（本方法就是"提交后调 drain"的兑现）。
   *
   * <p>★ **只对 {@code AdvanceTime} + 提交成功**触发；被拒/冲突不 drain（没有新 head 可读）。
   */
  public List<CommandResult> advanceAndDrain(AdvanceTime command) {
    Objects.requireNonNull(command, "command");
    CommandResult result = coreSimos.submit(command);
    if (result instanceof CommandResult.Committed) {
      return sdCommandDrain.drainAfterAdvance(command.branch());
    }
    return List.of();
  }

  /** 跨模块效果落点（C5）：测试/调试面可直接调它（不经 {@code advanceAndDrain}）。 */
  public SdCommandDrain sdCommandDrain() {
    return sdCommandDrain;
  }

  /** LLM provider 配置（M11′ 对接版）：AgentLib 的 {@code ConfigStore} 门面（测试/运维读回装配实况）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "本壳要把配置门面交给测试/运维读回装配实况；它不是内部表示而是本壳的产物，与 toolRegistry() 同法")
  public AgentLibLlmConfig llmConfig() {
    return llmConfig;
  }

  /** 决策编排（T3）：测试/调试面可直接调它（不经 GUI 端点）。 */
  public DecisionAdjudicationService decisionAdjudicationService() {
    return decisionAdjudicationService;
  }

  /**
   * 决策人 agent 运行流（T11C）：测试/调试面读回装配实况（{@code sd.RunDecision} 那条路内部就是它）。
   *
   * <p>★★ **本方法原先刻意不加抑制**（理由写在"它是本壳的产物、不是内部表示"上），但 2026-09-23 的门禁把它报了 （{@code
   * EI_EXPOSE_REP}，Medium）——而**同类的** {@link #llmConfig()} 一直带着同一条抑制。按"门禁是判据"处理： 在这里补上与 {@code
   * llmConfig()} 逐字同源的抑制，而不是改 API 形状（把运行流包一层只读壳会改变测试/调试的用法， 换不到任何真实保护——**它本来就是给测试与调试直接调的那个对象**）。
   *
   * <p>★ 诚实记一笔：**为什么会"同一份代码这次才报"没有查清**（本仓纪律形态 6 有先例：分析器的判定不是被分析文件的
   * 纯函数——它随**类集**变）。本改动确实新增了同类引用（{@code DecisionRunRegistry}），但**没有证据**证明因果。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "本壳要把决策人运行流交给测试/调试直接调；它不是内部表示而是本壳的产物，与 llmConfig()/toolRegistry() 同法")
  public DecisionAgentService decisionAgentService() {
    return decisionAgentService;
  }

  /**
   * 关闭：GUI → MCP → 工具桥 → 审批端点 → 审批通道 → {@link CoreSimos}（spec §3.3 的次序）。幂等。
   *
   * <p>★ spec §3.3 的完整次序是 GUI → MCP → 审批端点 → 审批通道 → CoreSimos；工具桥是 app 内的注册表卸载，插在 MCP 与审批之间不改变
   * 端口/线程的释放次序（MCP 先 {@code closeGracefully} 再停 server 由 AgentLib 契约实现）。★ **决策人会话库**（T11C）是 app 自己的
   * 存储（不占端口、不参与 spec §3.3 的次序），与 CoreSimos 同批关（都在最后）。
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    guiServer.close();
    mcpServer.close();
    toolBridge.close();
    approvalEndpoint.close();
    approvalChannel.close();
    decisionConversations.close();
    coreSimos.close();
  }
}
