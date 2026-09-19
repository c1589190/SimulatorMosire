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
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.gui.GuiServer;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.spi.SetTerrainHandler;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.facet.PopulationFacet;
import io.mosire.simos.social.resolve.SocialResolver;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.facet.UnitsHereFacet;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.resolve.UnitResolver;
import io.mosire.simos.unit.spi.CancelRouteHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.DisbandUnitHandler;
import io.mosire.simos.unit.spi.PlaceAtHandler;
import io.mosire.simos.unit.spi.PlanRouteHandler;
import io.mosire.simos.unit.spi.RenameUnitHandler;
import io.mosire.simos.unit.spi.ReparentUnitHandler;
import io.mosire.simos.unit.spi.SetStrengthHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外壳：**唯一的装配点**（spec §3.2 的 1~7 步）。
 *
 * <p>★ **它是全仓唯一组装 CoreSimos 与领域模块的地方**：Core 的 main scope 看不见任何领域类型（ADR-1），三 codec / 八 handler / 一
 * participant 必须由组合根注入。审批链（T6）、MCP 服务（T7）与 GUI（T8）都已接上——{@link #start} 走到"世界能提交命令、能重放、 能推进、能查询、能经
 * {@code /api} 与 MCP 工具面读写、写命令要过人审批"为止。
 *
 * <p>★ **装配清单**（spec §3.2 第 1~7 步）：三 codec + 八 handler + 一 participant（{@link CoreSimos} 侧） + 三
 * {@code Resolver}（map/social/unit）+ 两 {@code FacetProvider}（unitsHere/population）→ {@link
 * QueryService}（查询层，T3）；审批链（T6，S5：{@code PendingApprovals → HttpApprovalChannel →
 * ApprovalCoordinator → ApprovalHttpEndpoint}，无 Superior 判定）→ {@link SimosToolSource}（12 工具 = 3 写 +
 * 9 读）经 {@code McpSourceBridge.bind} 同步进 {@link ToolRegistry}（T5）→ {@link
 * AgentToMcpServer#startHttp} （第 6 步，T7）→ GUI（第 7 步，T8）。
 *
 * <p>★ **本类不持有任何存储写路径**：{@code SqliteStore} / {@code Timeline.appendRevision} / {@code
 * CheckpointStore} 一个都不在 app 源码里（铁律 2 的结构化，R1 的扫描对象）。唯一的写入口是 {@link
 * CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 */
public final class Shell implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  /** GUI 监听地址：回环（spec §〇.4 不做鉴权/TLS 的回环基线）。 */
  private static final String GUI_HOST = "127.0.0.1";

  /** MCP 监听地址：回环（spec §〇.4；AgentLib 对非回环会响亮告警）。 */
  private static final String MCP_HOST = "127.0.0.1";

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

  /** 工具注册表（T5）：{@link SimosToolSource} 的 12 条工具经桥同步进此表；T7 交给 {@code AgentToMcpServer}。 */
  private final ToolRegistry toolRegistry;

  /** 工具源 ↔ 注册表的同步桥（T5）：{@link #close()} 时整组下架本桥带入的工具。 */
  private final McpSourceBridge toolBridge;

  /** MCP 服务（T7，spec §7.2）：5715 的流式 HTTP 面；关闭次序里排第二（spec §3.3）。 */
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

  /** 已注册模块 codec 的个数（map/social/unit）；由实际注册动作数出来，不是写死的常量。 */
  private final int registeredModuleCount;

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
      int registeredModuleCount) {
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
  }

  /**
   * 起壳：建 CoreSimos 并按其装配顺序注册**三 codec + 八 handler + 一 participant**，再装**查询层**（三个 resolver + 两个真
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

    List<ModuleCodec> codecs = List.of(new MapCodec(), new SocialCodec(), new UnitCodec());
    for (ModuleCodec codec : codecs) {
      coreSimos.register(codec);
    }

    List<CommandHandler> handlers =
        List.of(
            new SetTerrainHandler(),
            new RenameUnitHandler(),
            new CreateUnitHandler(),
            new ReparentUnitHandler(),
            new SetStrengthHandler(),
            new PlaceAtHandler(),
            new PlanRouteHandler(),
            new CancelRouteHandler(),
            new DisbandUnitHandler());
    Set<String> commandTypes = new LinkedHashSet<>();
    for (CommandHandler handler : handlers) {
      coreSimos.register(handler);
      commandTypes.add(handler.type());
    }

    coreSimos.register(new UnitTimeParticipant(TerrainMovementCost.INSTANCE, config.mapId()));

    ResolverRegistry resolverRegistry = new ResolverRegistry();
    resolverRegistry.register(new MapResolver());
    resolverRegistry.register(new SocialResolver());
    resolverRegistry.register(new UnitResolver());

    FacetRegistry facetRegistry = new FacetRegistry();
    facetRegistry.register(new UnitsHereFacet());
    facetRegistry.register(new PopulationFacet());

    QueryService queryService = new QueryService(coreSimos, resolverRegistry, facetRegistry);

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
    ApprovalHttpEndpoint approvalEndpoint =
        ApprovalHttpEndpoint.start(config.approvalPort(), pendingApprovals, approvalCoordinator);
    approvalChannel.markUp();

    // 工具集（T5）：12 条工具（3 写 + 9 读）经桥同步进注册表；T7 把注册表交给 MCP 服务。
    SimosToolSource toolSource =
        new SimosToolSource(
            coreSimos, queryService, config.mcpInitiator(), config.mapId(), commandTypes);
    ToolRegistry toolRegistry = new ToolRegistry();
    McpSourceBridge toolBridge = McpSourceBridge.bind(toolSource, toolRegistry);

    // MCP 服务（T7，spec §3.2 第 6 步）：注册表交给 AgentLib 的流式 HTTP 面；authorizer 是带审批的那个。
    AgentToMcpServer mcpServer = null;
    boolean mcpUp = false;
    try {
      mcpServer =
          AgentToMcpServer.startHttp(
              MCP_HOST,
              config.mcpPort(),
              config.mcpPath(),
              toolRegistry,
              MCP_SERVER_NAME,
              MCP_SERVER_VERSION,
              mcpCaller(),
              toolAuthorizer);
      mcpUp = true;
    } finally {
      if (!mcpUp) {
        // MCP 绑定失败：已起的审批端点/通道不能留着占端口（GUI 尚未起，spec §3.3 里它排第一）。
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    // GUI（T8，spec §3.2 第 7 步）：审批面 base URL 指向刚绑定的端点，5711 的 /api/approvals 是它的透传代理。
    GuiServer guiServer =
        new GuiServer(
            queryService,
            coreSimos,
            config.mapId(),
            "http://127.0.0.1:" + approvalEndpoint.boundPort());
    boolean guiUp = false;
    try {
      guiServer.start(GUI_HOST, config.guiPort());
      guiUp = true;
    } finally {
      if (!guiUp) {
        // GUI 绑定失败：MCP 与审批端点/通道不能留着占端口（spec §3.3 里 GUI 排第一，此处它还没起来）。
        mcpServer.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    LOG.info(
        "Shell 装配完成: store={} checkpointInterval={} codec={} handler={} participant=1"
            + " resolver={} facet={} tool={} mapId={} mcpPort={} guiPort={} approvalPort={}",
        config.storeDir(),
        config.checkpointInterval(),
        codecs.size(),
        handlers.size(),
        resolverRegistry.namespaces().size(),
        facetRegistry.facetNames().size(),
        toolRegistry.size(),
        config.mapId(),
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
        codecs.size());
  }

  /** GUI 服务器实际绑定端口（{@code guiPort=0} 时由 OS 分配；spec §3.1 的读回口径，测试用）。 */
  public int boundGuiPort() {
    return guiServer.boundPort();
  }

  /** MCP 服务实际绑定端口（{@code mcpPort=0} 时由 OS 分配；spec §3.1 的读回口径，经 AgentLib {@code boundPort()}）。 */
  public int boundMcpPort() {
    return mcpServer.boundPort();
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

  /** 工具注册表（spec §7.1；T5）：12 条工具（3 写 + 9 读）的活清单，T7 交给 {@code AgentToMcpServer}。 */
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

  /** 已注册的模块 codec 个数（map/social/unit = 3）。{@code ShellMain} 用它打印装配实况。 */
  public int registeredModuleCount() {
    return registeredModuleCount;
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
    toolBridge.close();
    approvalEndpoint.close();
    approvalChannel.close();
    coreSimos.close();
  }
}
