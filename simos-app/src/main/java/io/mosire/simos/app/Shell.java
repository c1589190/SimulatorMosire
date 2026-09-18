package io.mosire.simos.app;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.gui.GuiServer;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.resolve.MapResolver;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外壳：**唯一的装配点**（spec §3.2 的 1~2 步与 3~4 步中不依赖审批/MCP 的部分）。
 *
 * <p>★ **它是全仓唯一组装 CoreSimos 与领域模块的地方**：Core 的 main scope 看不见任何领域类型（ADR-1），三 codec / 八 handler / 一
 * participant 必须由组合根注入。MCP / 审批的装配归 T6/T7，**本壳在 T8 已接上 GUI**——{@link #start} 走到"世界能提交命令、能重放、
 * 能推进、能查询、能经 {@code /api} 读写"为止。
 *
 * <p>★ **装配清单**（spec §3.2 第 1~2 步）：三 codec + 八 handler + 一 participant（{@link CoreSimos} 侧） + 三
 * {@code Resolver}（map/social/unit）+ 两 {@code FacetProvider}（unitsHere/population）→ {@link
 * QueryService}（查询层，T3）；再 {@link SimosToolSource}（12 工具 = 3 写 + 9 读）经 {@code McpSourceBridge.bind}
 * 同步进 {@link ToolRegistry}（T5，T7 消费）。后续任务在此继续接审批/MCP/GUI。
 *
 * <p>★ **本类不持有任何存储写路径**：{@code SqliteStore} / {@code Timeline.appendRevision} / {@code
 * CheckpointStore} 一个都不在 app 源码里（铁律 2 的结构化，R1 的扫描对象）。唯一的写入口是 {@link
 * CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 */
public final class Shell implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  /** GUI 监听地址：回环（spec §〇.4 不做鉴权/TLS 的回环基线）。 */
  private static final String GUI_HOST = "127.0.0.1";

  private final ShellConfig config;
  private final CoreSimos coreSimos;

  /** 查询层（T3）：GUI 与工具集唯一的只读入口（spec §5.1）。 */
  private final QueryService queryService;

  /** 工具注册表（T5）：{@link SimosToolSource} 的 12 条工具经桥同步进此表；T7 交给 {@code AgentToMcpServer}。 */
  private final ToolRegistry toolRegistry;

  /** 工具源 ↔ 注册表的同步桥（T5）：{@link #close()} 时整组下架本桥带入的工具。 */
  private final McpSourceBridge toolBridge;

  /** GUI 服务器（T8）：5711 的静态页 + {@code /api}；关闭次序里排第一（spec §3.3）。 */
  private final GuiServer guiServer;

  /** 已注册模块 codec 的个数（map/social/unit）；由实际注册动作数出来，不是写死的常量。 */
  private final int registeredModuleCount;

  private Shell(
      ShellConfig config,
      CoreSimos coreSimos,
      QueryService queryService,
      ToolRegistry toolRegistry,
      McpSourceBridge toolBridge,
      GuiServer guiServer,
      int registeredModuleCount) {
    this.config = config;
    this.coreSimos = coreSimos;
    this.queryService = queryService;
    this.toolRegistry = toolRegistry;
    this.toolBridge = toolBridge;
    this.guiServer = guiServer;
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

    // 工具集（T5）：12 条工具（3 写 + 9 读）经桥同步进注册表；T7 把注册表交给 MCP 服务。
    SimosToolSource toolSource =
        new SimosToolSource(
            coreSimos, queryService, config.mcpInitiator(), config.mapId(), commandTypes);
    ToolRegistry toolRegistry = new ToolRegistry();
    McpSourceBridge toolBridge = McpSourceBridge.bind(toolSource, toolRegistry);

    // GUI（T8）：审批 base URL 传 null——T6 未接入，approval 端点回 503（spec §8.2 的接缝）。
    GuiServer guiServer = new GuiServer(queryService, coreSimos, config.mapId(), null);
    guiServer.start(GUI_HOST, config.guiPort());

    LOG.info(
        "Shell 装配完成: store={} checkpointInterval={} codec={} handler={} participant=1"
            + " resolver={} facet={} tool={} mapId={} guiPort={}",
        config.storeDir(),
        config.checkpointInterval(),
        codecs.size(),
        handlers.size(),
        resolverRegistry.namespaces().size(),
        facetRegistry.facetNames().size(),
        toolRegistry.size(),
        config.mapId(),
        guiServer.boundPort());
    return new Shell(
        config, coreSimos, queryService, toolRegistry, toolBridge, guiServer, codecs.size());
  }

  /** GUI 服务器实际绑定端口（{@code guiPort=0} 时由 OS 分配；spec §3.1 的读回口径，测试用）。 */
  public int boundGuiPort() {
    return guiServer.boundPort();
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
   * 关闭：GUI → {@link CoreSimos}（spec §3.3 的次序，GUI 排第一先释放监听端口）。幂等。
   *
   * <p>★ spec §3.3 的完整次序是 GUI → MCP → 审批端点 → 审批通道 → CoreSimos；MCP / 审批要到 T6/T7 才存在，故当前实现就是
   * 完整次序去掉尚不存在的三项。
   */
  @Override
  public void close() {
    guiServer.close();
    toolBridge.close();
    coreSimos.close();
  }
}
