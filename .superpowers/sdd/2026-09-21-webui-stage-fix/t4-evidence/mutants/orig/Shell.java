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
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.gui.GuiServer;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.sd.SdCommandDrain;
import io.mosire.simos.app.sd.channel.CliDecisionChannel;
import io.mosire.simos.app.sd.channel.GuiDecisionChannel;
import io.mosire.simos.app.sd.channel.HttpDecisionChannel;
import io.mosire.simos.app.sd.channel.McpDecisionChannel;
import io.mosire.simos.app.tools.SimosToolSource;
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
import io.mosire.simos.sd.spi.SetOutcomeTableHandler;
import io.mosire.simos.sd.spi.SetViewScopeHandler;
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
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外壳：**唯一的装配点**（spec §3.2 的 1~7 步）。
 *
 * <p>★ **它是全仓唯一组装 CoreSimos 与领域模块的地方**：Core 的 main scope 看不见任何领域类型（ADR-1），四 codec / 三十 handler / 一
 * participant 必须由组合根注入。审批链（T6）、MCP 服务（T7）与 GUI（T8）都已接上——{@link #start} 走到"世界能提交命令、能重放、 能推进、能查询、能经
 * {@code /api} 与 MCP 工具面读写、写命令要过人审批"为止。
 *
 * <p>★ **装配清单**（spec §3.2 第 1~7 步）：四 codec（map/social/unit/sd）+ 三十 handler + 一 participant（{@link
 * CoreSimos} 侧，另有一个写前守卫 {@code RegionDeleteGuard}）+ 四 {@code Resolver} （map/social/unit/sd）+ 两
 * {@code FacetProvider}（unitsHere/population）→ {@link QueryService}（查询层，T3）；审批链（T6，S5：{@code
 * PendingApprovals → HttpApprovalChannel → ApprovalCoordinator → ApprovalHttpEndpoint}，无 Superior
 * 判定）→ {@link SimosToolSource}（T4：现有口 {@code EXTERNAL_WITH_GM} 15 工具 = 3 通用写 + 3 GM 窄写 + 9 读；决策人口
 * {@code DECISION_AGENT} 11 工具 = 2 窄写 + 9 读）经 {@code McpSourceBridge.bind} 同步进各自 {@link
 * ToolRegistry}（T5）→ {@link AgentToMcpServer#startHttp} **两次**（现有口 + 决策人口，第 6 步，T7/T4）→ GUI（第 7
 * 步，T8）。
 *
 * <p>★ **本类不持有任何存储写路径**：{@code SqliteStore} / {@code Timeline.appendRevision} / {@code
 * CheckpointStore} 一个都不在 app 源码里（铁律 2 的结构化，R1 的扫描对象）。唯一的写入口是 {@link
 * CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 */
public final class Shell implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  /** MCP server 自报名称（spec §7.2）。 */
  private static final String MCP_SERVER_NAME = "simos-shell";

  /** 决策人 MCP server 的自报名称（T4，spec §六.4 建议两口用不同名以便区分）。 */
  private static final String DECISION_MCP_SERVER_NAME = "simos-shell-decision";

  /** MCP server 自报版本（spec §7.2 的 {@code "0.1.0-SNAPSHOT"}）。 */
  private static final String MCP_SERVER_VERSION = "0.1.0-SNAPSHOT";

  /** 审批等待上限（spec 未定值，M5 取 5 分钟）：到点没人答 ⇒ fail-closed 拒（AgentLib 契约）。 */
  private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellConfig config;
  private final CoreSimos coreSimos;

  /** 查询层（T3）：GUI 与工具集唯一的只读入口（spec §5.1）。 */
  private final QueryService queryService;

  /**
   * 工具注册表（T5/T4）：现有口 {@link SimosToolSource.Role#EXTERNAL_WITH_GM} 的 15 条工具经桥同步进此表；T7 交给 {@code
   * AgentToMcpServer}。
   */
  private final ToolRegistry toolRegistry;

  /** 工具源 ↔ 注册表的同步桥（T5）：{@link #close()} 时整组下架本桥带入的工具。 */
  private final McpSourceBridge toolBridge;

  /**
   * 决策人口工具注册表（T4）：{@link SimosToolSource.Role#DECISION_AGENT} 的 11 条工具经桥同步；与 {@link #toolRegistry}
   * 相互独立。
   */
  private final ToolRegistry decisionToolRegistry;

  /** 决策人口的工具源 ↔ 注册表同步桥（T4）：{@link #close()} 时整组下架。 */
  private final McpSourceBridge decisionToolBridge;

  /** MCP 服务（T7，spec §7.2）：5715 的流式 HTTP 面；关闭次序里排第二（spec §3.3）。 */
  private final AgentToMcpServer mcpServer;

  /** 决策人 MCP 服务（T4，spec §二.2）：5717 的流式 HTTP 面；关闭次序里紧随 {@link #mcpServer}（spec §3.3）。 */
  private final AgentToMcpServer decisionMcpServer;

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

  private volatile boolean closed;

  private Shell(
      ShellConfig config,
      CoreSimos coreSimos,
      QueryService queryService,
      ToolRegistry toolRegistry,
      McpSourceBridge toolBridge,
      ToolRegistry decisionToolRegistry,
      McpSourceBridge decisionToolBridge,
      AgentToMcpServer mcpServer,
      AgentToMcpServer decisionMcpServer,
      GuiServer guiServer,
      PendingApprovals pendingApprovals,
      HttpApprovalChannel approvalChannel,
      ApprovalCoordinator approvalCoordinator,
      ApprovalHttpEndpoint approvalEndpoint,
      ToolCallAuthorizer toolAuthorizer,
      int registeredModuleCount,
      SdCommandDrain sdCommandDrain,
      List<DecisionChannel> decisionChannels,
      Set<String> commandTypes) {
    this.config = config;
    this.coreSimos = coreSimos;
    this.queryService = queryService;
    this.toolRegistry = toolRegistry;
    this.toolBridge = toolBridge;
    this.decisionToolRegistry = decisionToolRegistry;
    this.decisionToolBridge = decisionToolBridge;
    this.mcpServer = mcpServer;
    this.decisionMcpServer = decisionMcpServer;
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
  }

  /**
   * 起壳：建 CoreSimos 并按其装配顺序注册**四 codec + 三十 handler + 一 participant**，再装**查询层**（三个 resolver + 两个真
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
                new CancelEffectHandler()));
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

    // ★ D1：决策命令白名单从**注册面**推导（禁 sd 自指/通用写）⇒ 必须在上面那个循环之后、用完整的 commandTypes 构造。
    IssueDirectiveHandler issueDirectiveHandler =
        new IssueDirectiveHandler(new DirectiveWhitelist(commandTypes));
    SubmitVerdictHandler submitVerdictHandler = new SubmitVerdictHandler();
    SetViewScopeHandler setViewScopeHandler = new SetViewScopeHandler();
    for (CommandHandler late :
        List.of(issueDirectiveHandler, submitVerdictHandler, setViewScopeHandler)) {
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

    // ★ D5：四条决策渠道（spec §十三.3）——声明各自可代表的 actor（当前世界里的决策人），最终写同一落点。
    Supplier<Set<ActorId>> representableActors = () -> currentActorIds(coreSimos);
    List<DecisionChannel> decisionChannels =
        List.of(
            new GuiDecisionChannel(coreSimos, representableActors),
            new McpDecisionChannel(coreSimos, representableActors),
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

    // 工具集（T5/T4）：现有口 = EXTERNAL ∪ GM（**D2="加"**，spec §二.5）= 9 读 + 3 通用写 + 3 GM 窄写。
    // ★ 与 SDSimos 裁定 N9 的冲突在此端口显式记账：保留通用写是**用户裁定 D2 的取舍、不是缺陷**；
    //   N9 在决策人口（下面的 DECISION_AGENT 桶）与 DecisionMaker.allowedTools 白名单上照旧有效。
    SimosToolSource toolSource =
        new SimosToolSource(
            coreSimos,
            queryService,
            config.mcpInitiator(),
            config.mapId(),
            commandTypes,
            SimosToolSource.Role.EXTERNAL_WITH_GM);
    ToolRegistry toolRegistry = new ToolRegistry();
    McpSourceBridge toolBridge = McpSourceBridge.bind(toolSource, toolRegistry);

    // 决策人口（T4，spec §二.2/§六.4）：仅 DECISION_AGENT 桶（9 读 + 2 窄写），**无**通用写、**无** sd.SetViewScope。
    SimosToolSource decisionToolSource =
        new SimosToolSource(
            coreSimos,
            queryService,
            config.mcpInitiator(),
            config.mapId(),
            commandTypes,
            SimosToolSource.Role.DECISION_AGENT);
    ToolRegistry decisionToolRegistry = new ToolRegistry();
    McpSourceBridge decisionToolBridge =
        McpSourceBridge.bind(decisionToolSource, decisionToolRegistry);

    // MCP 服务（T7/T4，spec §3.2 第 6 步）：两个注册表各交给一个 AgentLib 流式 HTTP 面；authorizer 是带审批的那个。
    // ★ 两口都绑 config.bindAddress()、都用 mcpCaller()——全仓无多用户认证 ⇒ 权限边界在**端口**、不在身份（spec §二.3）。
    AgentToMcpServer mcpServer = null;
    AgentToMcpServer decisionMcpServer = null;
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
              mcpCaller(),
              toolAuthorizer);
      decisionMcpServer =
          AgentToMcpServer.startHttp(
              config.bindAddress(),
              config.decisionAgentMcpPort(),
              config.mcpPath(),
              decisionToolRegistry,
              DECISION_MCP_SERVER_NAME,
              MCP_SERVER_VERSION,
              mcpCaller(),
              toolAuthorizer);
      mcpUp = true;
    } finally {
      if (!mcpUp) {
        // 任一口绑定失败：已起的服务、桥与审批端点/通道都不能留着占端口（GUI 尚未起，spec §3.3 里它排第一）。
        if (decisionMcpServer != null) {
          decisionMcpServer.close();
        }
        if (mcpServer != null) {
          mcpServer.close();
        }
        decisionToolBridge.close();
        toolBridge.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    // 走到这里两个口都已监听（任一绑定失败在上面的 finally 里已收尾并抛出）；显式断言以钉死该后置条件。
    Objects.requireNonNull(mcpServer, "mcpServer");
    Objects.requireNonNull(decisionMcpServer, "decisionMcpServer");

    // GUI（T8，spec §3.2 第 7 步）：审批面 base URL 指向刚绑定的端点，5711 的 /api/approvals 是它的透传代理。
    GuiServer guiServer =
        new GuiServer(
            queryService,
            coreSimos,
            config.mapId(),
            "http://127.0.0.1:" + approvalEndpoint.boundPort());
    boolean guiUp = false;
    try {
      guiServer.start(config.bindAddress(), config.guiPort());
      guiUp = true;
    } finally {
      if (!guiUp) {
        // GUI 绑定失败：两个 MCP 与审批端点/通道、工具桥不能留着占端口（spec §3.3 里 GUI 排第一，此处它还没起来）。
        decisionMcpServer.close();
        mcpServer.close();
        decisionToolBridge.close();
        toolBridge.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    LOG.info(
        "Shell 装配完成: store={} checkpointInterval={} codec={} handler={} participant={}"
            + " resolver={} facet={} tool={} decisionTool={} mapId={} bindAddress={} mcpPort={}"
            + " decisionAgentMcpPort={} guiPort={} approvalPort={}",
        config.storeDir(),
        config.checkpointInterval(),
        codecs.size(),
        handlers.size(),
        participants.size(),
        resolverRegistry.namespaces().size(),
        facetRegistry.facetNames().size(),
        toolRegistry.size(),
        decisionToolRegistry.size(),
        config.mapId(),
        config.bindAddress(),
        mcpServer.boundPort(),
        decisionMcpServer.boundPort(),
        guiServer.boundPort(),
        approvalEndpoint.boundPort());
    return new Shell(
        config,
        coreSimos,
        queryService,
        toolRegistry,
        toolBridge,
        decisionToolRegistry,
        decisionToolBridge,
        mcpServer,
        decisionMcpServer,
        guiServer,
        pendingApprovals,
        approvalChannel,
        approvalCoordinator,
        approvalEndpoint,
        toolAuthorizer,
        codecs.size(),
        new SdCommandDrain(coreSimos),
        decisionChannels,
        commandTypes);
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
   * 按角色重建工具面（D6，N9/N11；T4）：GM / 决策 Agent 桶**无**通用写；运行时两口分别是 EXTERNAL_WITH_GM 与 DECISION_AGENT 桶。
   */
  public List<AgentTool> toolsFor(SimosToolSource.Role role) {
    return new SimosToolSource(
            coreSimos, queryService, config.mcpInitiator(), config.mapId(), commandTypes, role)
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

  /** 决策人 MCP 服务实际绑定端口（T4；{@code decisionAgentMcpPort=0} 时由 OS 分配，读回口径同 {@link #boundMcpPort()}）。 */
  public int boundDecisionAgentMcpPort() {
    return decisionMcpServer.boundPort();
  }

  /**
   * MCP 调用者身份（装配期定死；spec §3.2 的 caller，S/P2-F 无 per-session 身份）。
   *
   * <p>★ **桶取 {@link AccessToken#DEFAULT} 而非 spec §3.2 字面的 {@code GUEST}**——这是装配期实测的**取代说明**：三条写工具
   * （{@code command.submit}/{@code advance}/{@code fork}）的 {@code ToolSpec} 是 {@code
   * ToolSpec.level(DEFAULT, sensitive=true, destructive=false)}，而 {@code PermissionChecker}
   * 在身份级别不足时**硬拒** （{@code PERMISSION_DENIED}），根本进不了审批闸。{@code GUEST} 桶下 12 条工具里的 3 条写工具全部不可达，MCP
   * 只能读、不能写 ⇒ 与 S3/S4 的工具面设计矛盾。{@code DEFAULT} 是**满足全部 12 条工具的最小桶**（读工具只要求 {@code GUEST}）。
   *
   * <p>★ **权限集须显式开 {@code sensitiveAllowed}**（写工具的敏感位在硬拒规则里"默认拒绝"）：故用 {@link
   * AgentPermissionSet#unrestricted(AccessToken)}（含 destructive/sensitive 放行）。**放行 ≠ 免审批**：敏感工具仍走
   * {@code ToolGate.Ask}，本壳注入的 authorizer 是带 {@code ApprovalCoordinator} 的那个（非 {@code standard()}）。
   *
   * <p>★ 身份取 {@link AgentIdentity#external()}（{@code external-mcp} 实例 + {@code FULL}
   * 档）：外部客户端不是本进程派生的 Agent，审批面据它认得出"这不是我派的下级"（AgentLib 契约）。注意 AgentLib 的审批 {@code callerKey}
   * 取的是**桶名**（{@code DEFAULT}），不是本 identity 的实例 id（T6 裁定 63 已实测）。
   */
  private static ToolContext mcpCaller() {
    return ToolContext.of(
        AccessToken.DEFAULT,
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
        AgentIdentity.external());
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

  /**
   * 工具注册表（spec §7.1；T5/T4）：现有口 15 条工具（3 通用写 + 3 GM 窄写 + 9 读）的活清单，T7 交给 {@code AgentToMcpServer}。
   */
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

  /**
   * 关闭：GUI → MCP → 工具桥 → 审批端点 → 审批通道 → {@link CoreSimos}（spec §3.3 的次序）。幂等。
   *
   * <p>★ spec §3.3 的完整次序是 GUI → MCP → 审批端点 → 审批通道 → CoreSimos；工具桥是 app 内的注册表卸载，插在 MCP 与审批之间不改变
   * 端口/线程的释放次序（MCP 先 {@code closeGracefully} 再停 server 由 AgentLib 契约实现）。
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    guiServer.close();
    mcpServer.close();
    decisionMcpServer.close();
    toolBridge.close();
    decisionToolBridge.close();
    approvalEndpoint.close();
    approvalChannel.close();
    coreSimos.close();
  }
}
