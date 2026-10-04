package io.mosire.simos.app;

import io.mosire.simos.app.world.WorldRegistry;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 外壳的装配配置（spec §3.1；M5 T1）。
 *
 * <p>★ **相对 spec §3.1 的一处取代说明候选**：spec 的 record 头里没有 {@code mapId}，而 {@link
 * io.mosire.simos.unit.spi.UnitTimeParticipant#UnitTimeParticipant(io.mosire.simos.unit.move.MovementCost,
 * String)} 需要它（{@code GameMap} 没有 id，见 M2/M3 挂起项）。缺了它 T1 的装配根本写不出来 ⇒ 本类补上，缺省 {@code
 * "Map1"}。这一处是**真缺口不是笔误**，记在 {@code t1-report.md} 的取代说明里。
 *
 * <p>★ **{@code port = 0} 一律支持**（spec §3.1 的测试口径）：OS 分配随机端口，读回由 T6/T7/T8 的 {@code boundXxxPort()}
 * 负责（T1 尚未起任何监听，故这里只做"非负"校验，不假装能读回）。
 *
 * @param storeDir 存储根目录（{@code CoreConfig.storeDir}）；不必预先存在
 * @param checkpointInterval C19 的周期 N，必须 ≥ 1（缺省 {@value #DEFAULT_CHECKPOINT_INTERVAL}）
 * @param guiPort GUI 服务器端口（缺省 {@value #DEFAULT_GUI_PORT}；T8 消费）
 * @param mcpPort MCP 服务端口（缺省 {@value #DEFAULT_MCP_PORT}；T7 消费）
 * @param mcpPath MCP 挂载路径（缺省 {@value #DEFAULT_MCP_PATH}；T7 消费）
 * @param approvalPort 审批端点端口（缺省 {@value #DEFAULT_APPROVAL_PORT}；T6 消费）
 * @param mcpInitiator MCP 写命令的 initiator（缺省 {@value #DEFAULT_MCP_INITIATOR}；C21 的 {@code
 *     <kind>:<id>} 形态）
 * @param mapId 本世界的 map 称谓（缺省 {@value #DEFAULT_MAP_ID}）：{@code UnitTimeParticipant} 构造需要
 * @param bindAddress GUI 与 MCP 两个对外面的绑定地址（缺省 {@value #DEFAULT_BIND_ADDRESS}；M10）。显式传 {@code
 *     0.0.0.0} 供反代场景（此前两个面硬编码回环、无法配）。★ **审批端点不在此列**——AgentLib 的 {@code ApprovalHttpEndpoint}
 *     把回环写成了编译期常量（其类注写明"不提供改绑地址的入口"），故审批端口恒回环；对外面是 GUI 的 {@code /api/approvals} 透传代理，见 {@link
 *     io.mosire.simos.app.Shell}
 * @param openingSnapshot **开场快照开关**（P4，缺省 {@value #DEFAULT_OPENING_SNAPSHOT}）：开 ⇒ 决策人的会话首次为空时，
 *     先给它发一张本国所在区域的渲染图（只在有视觉能力的路由上生效）。见 {@link #withOpeningSnapshot}
 * @param worldId **创世世界选择**（P1.1，缺省 {@value #DEFAULT_WORLD_ID}）：**只在空库首启时**决定用哪个 {@code worldId}
 *     对应的创世生成器做 bootstrap（注册表见 {@link io.mosire.simos.app.world.WorldRegistry}）。**它不是运行时切换**：非空库绝不
 *     覆盖已有世界，该值对已有世界无任何作用。来源优先级：命令行 {@code --world} &gt; 配置文件 {@code world} &gt; 内置缺省。
 */
public record ShellConfig(
    Path storeDir,
    int checkpointInterval,
    int guiPort,
    int mcpPort,
    String mcpPath,
    int approvalPort,
    String mcpInitiator,
    String mapId,
    String bindAddress,
    boolean openingSnapshot,
    String worldId) {

  public static final int DEFAULT_CHECKPOINT_INTERVAL = 100;
  public static final int DEFAULT_GUI_PORT = 5711;
  public static final int DEFAULT_MCP_PORT = 5715;
  public static final String DEFAULT_MCP_PATH = "/mcp";
  public static final int DEFAULT_APPROVAL_PORT = 5713;
  public static final String DEFAULT_MCP_INITIATOR = "agent:external-mcp";
  public static final String DEFAULT_MAP_ID = "Map1";

  /**
   * 创世世界的**内置缺省** id（P1.1）：指向 {@link io.mosire.simos.app.world.WorldRegistry} 里登记的唯一经济世界 {@code
   * v17levant}（字面量只在注册表里定义一次，避免两处漂移）。命令行 {@code --world} 与配置文件 {@code world} 都能覆盖它；三者都没有时就是它。
   */
  public static final String DEFAULT_WORLD_ID = WorldRegistry.V17LEVANT;

  /** GUI / MCP 的缺省绑定地址：回环（M10；不裸暴露，反代场景显式传 {@code 0.0.0.0}）。 */
  public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

  /**
   * **开场快照**的缺省（P4）：**关**。
   *
   * <p>★ 为什么缺省是关：它给决策人的会话**永久**加一条图片消息（每轮请求都会重发它），是一条会持续花钱的行为； 而"决策人自己调 {@code simos.map.render}
   * 看图"这条路本来就是通的。要开就显式开（{@code --opening-snapshot}）。
   */
  public static final boolean DEFAULT_OPENING_SNAPSHOT = false;

  /**
   * **10 参兼容构造**（P1.1）：{@code worldId} 取 {@value #DEFAULT_WORLD_ID}。
   *
   * <p>★ 它存在的理由与下一条同族：{@code worldId} 是**新加的第 11 个分量**，而库内已有 13 处按 9 参、若干处按 10 参装配（多数在测试里）。
   * 让它们继续钉在"新特性缺省"上，比各改一行更有价值。新代码请显式传 {@code worldId}。
   */
  public ShellConfig(
      Path storeDir,
      int checkpointInterval,
      int guiPort,
      int mcpPort,
      String mcpPath,
      int approvalPort,
      String mcpInitiator,
      String mapId,
      String bindAddress,
      boolean openingSnapshot) {
    this(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        approvalPort,
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        DEFAULT_WORLD_ID);
  }

  /**
   * **9 参兼容构造**（P4）：{@code openingSnapshot} 取 {@value #DEFAULT_OPENING_SNAPSHOT}、{@code worldId} 取
   * {@value #DEFAULT_WORLD_ID}。
   *
   * <p>★ 它存在的理由很实在：那个开关是**新加的第 10 个分量**，而库内已有 13 处按 9 参装配（多数在测试里）。加一个形参就让 13
   * 个与本次改动无关的地方各改一行，换不到任何东西；把它们钉在"新特性缺省关"上，正是我们要的语义。
   */
  public ShellConfig(
      Path storeDir,
      int checkpointInterval,
      int guiPort,
      int mcpPort,
      String mcpPath,
      int approvalPort,
      String mcpInitiator,
      String mapId,
      String bindAddress) {
    this(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        approvalPort,
        mcpInitiator,
        mapId,
        bindAddress,
        DEFAULT_OPENING_SNAPSHOT);
  }

  public ShellConfig {
    Objects.requireNonNull(storeDir, "storeDir");
    if (checkpointInterval < 1) {
      throw new IllegalArgumentException(
          "checkpointInterval 必须 ≥ 1（C19 第①项的取模周期）: " + checkpointInterval);
    }
    if (guiPort < 0 || mcpPort < 0 || approvalPort < 0) {
      throw new IllegalArgumentException(
          "端口不得为负（0 = 随机端口，见 spec §3.1）: gui="
              + guiPort
              + " mcp="
              + mcpPort
              + " approval="
              + approvalPort);
    }
    mcpPath = requireText(mcpPath, "mcpPath");
    mcpInitiator = requireText(mcpInitiator, "mcpInitiator");
    mapId = requireText(mapId, "mapId");
    bindAddress = requireText(bindAddress, "bindAddress");
    worldId = requireText(worldId, "worldId");
  }

  /**
   * 全部取缺省值的配置（端口 = spec 的三个默认值），只指定存储目录与 map 称谓留待覆盖。
   *
   * <p>★ 缺省 {@code mapId} 用 {@value #DEFAULT_MAP_ID}：{@code ShellMain} 无 {@code --map-id} 参数（T1
   * 范围只四个 开关），故命令行路径拿到的就是它。
   */
  public static ShellConfig defaults(Path storeDir) {
    return new ShellConfig(
        storeDir,
        DEFAULT_CHECKPOINT_INTERVAL,
        DEFAULT_GUI_PORT,
        DEFAULT_MCP_PORT,
        DEFAULT_MCP_PATH,
        DEFAULT_APPROVAL_PORT,
        DEFAULT_MCP_INITIATOR,
        DEFAULT_MAP_ID,
        DEFAULT_BIND_ADDRESS,
        DEFAULT_OPENING_SNAPSHOT,
        DEFAULT_WORLD_ID);
  }

  /** 仅替换三个端口，其余原样（测试用 {@code 0} 取随机端口时最常用）。 */
  public ShellConfig withPorts(int guiPort, int mcpPort, int approvalPort) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        approvalPort,
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        worldId);
  }

  /** 仅替换 GUI / MCP 的绑定地址，其余原样（M10；测试绑非回环地址时最常用）。 */
  public ShellConfig withBindAddress(String bindAddress) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        approvalPort,
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        worldId);
  }

  /**
   * 仅替换**开场快照开关**，其余原样（P4）。
   *
   * <p>★ 开它只对**有视觉能力**的路由有效：路由没声明 {@code capabilities.vision=true} 时运行流会跳过并留一行 warn （见 {@code
   * DecisionAgentRunner.openingSnapshotMessage}）——即"开了却没出图"是**看得见**的，不是静默。
   */
  public ShellConfig withOpeningSnapshot(boolean enabled) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        approvalPort,
        mcpInitiator,
        mapId,
        bindAddress,
        enabled,
        worldId);
  }

  /**
   * 仅替换**创世世界 id**，其余原样（P1.1）。
   *
   * <p>★ 它只影响**空库首启**的 bootstrap 生成器选择：非空库永不覆盖，见 {@link ShellConfig#worldId()} 与 {@link
   * io.mosire.simos.app.world.WorldRegistry#require(String)}（未知 id 在那里具名拒绝）。
   */
  public ShellConfig withWorldId(String worldId) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        approvalPort,
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        worldId);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白: " + value);
    }
    return value;
  }
}
