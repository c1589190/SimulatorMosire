package io.mosire.simos.app;

import io.mosire.simos.app.world.WorldRegistry;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 外壳的装配配置（spec §3.1；M5 T1）。
 *
 * <p>HTTP 审批面已退役（设计书 §8.5）：本配置不再有 {@code approvalPort}，GUI 也不再代理 {@code /api/approvals}。非包 Ask
 * 由内部审批链 fail-closed。
 *
 * @param storeDir 存储根目录（{@code CoreConfig.storeDir}）；不必预先存在
 * @param checkpointInterval C19 的周期 N，必须 ≥ 1
 * @param guiPort GUI 服务器端口（0 = OS 分配）
 * @param mcpPort MCP 服务端口（0 = OS 分配）
 * @param mcpPath MCP 挂载路径
 * @param mcpInitiator MCP 写命令的 initiator
 * @param mapId 本世界的 map 称谓
 * @param bindAddress GUI 与 MCP 的绑定地址
 * @param openingSnapshot 开场快照开关
 * @param worldId 创世世界选择（只在空库首启时生效）
 */
public record ShellConfig(
    Path storeDir,
    int checkpointInterval,
    int guiPort,
    int mcpPort,
    String mcpPath,
    String mcpInitiator,
    String mapId,
    String bindAddress,
    boolean openingSnapshot,
    String worldId) {

  public static final int DEFAULT_CHECKPOINT_INTERVAL = 100;
  public static final int DEFAULT_GUI_PORT = 5711;
  public static final int DEFAULT_MCP_PORT = 5715;
  public static final String DEFAULT_MCP_PATH = "/mcp";
  public static final String DEFAULT_MCP_INITIATOR = "agent:external-mcp";
  public static final String DEFAULT_MAP_ID = "Map1";
  public static final String DEFAULT_WORLD_ID = WorldRegistry.V17LEVANT;
  public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
  public static final boolean DEFAULT_OPENING_SNAPSHOT = false;

  /** 兼容构造：{@code worldId} 取缺省。 */
  public ShellConfig(
      Path storeDir,
      int checkpointInterval,
      int guiPort,
      int mcpPort,
      String mcpPath,
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
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        DEFAULT_WORLD_ID);
  }

  /** 兼容构造：{@code openingSnapshot}/{@code worldId} 取缺省。 */
  public ShellConfig(
      Path storeDir,
      int checkpointInterval,
      int guiPort,
      int mcpPort,
      String mcpPath,
      String mcpInitiator,
      String mapId,
      String bindAddress) {
    this(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        mcpInitiator,
        mapId,
        bindAddress,
        DEFAULT_OPENING_SNAPSHOT,
        DEFAULT_WORLD_ID);
  }

  public ShellConfig {
    Objects.requireNonNull(storeDir, "storeDir");
    if (checkpointInterval < 1) {
      throw new IllegalArgumentException(
          "checkpointInterval 必须 ≥ 1（C19 第①项的取模周期）: " + checkpointInterval);
    }
    if (guiPort < 0 || mcpPort < 0) {
      throw new IllegalArgumentException(
          "端口不得为负（0 = 随机端口，见 spec §3.1）: gui=" + guiPort + " mcp=" + mcpPort);
    }
    mcpPath = requireText(mcpPath, "mcpPath");
    mcpInitiator = requireText(mcpInitiator, "mcpInitiator");
    mapId = requireText(mapId, "mapId");
    bindAddress = requireText(bindAddress, "bindAddress");
    worldId = requireText(worldId, "worldId");
  }

  public static ShellConfig defaults(Path storeDir) {
    return new ShellConfig(
        storeDir,
        DEFAULT_CHECKPOINT_INTERVAL,
        DEFAULT_GUI_PORT,
        DEFAULT_MCP_PORT,
        DEFAULT_MCP_PATH,
        DEFAULT_MCP_INITIATOR,
        DEFAULT_MAP_ID,
        DEFAULT_BIND_ADDRESS,
        DEFAULT_OPENING_SNAPSHOT,
        DEFAULT_WORLD_ID);
  }

  /** 仅替换 GUI / MCP 两个端口。 */
  public ShellConfig withPorts(int guiPort, int mcpPort) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        worldId);
  }

  public ShellConfig withBindAddress(String bindAddress) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        mcpInitiator,
        mapId,
        bindAddress,
        openingSnapshot,
        worldId);
  }

  public ShellConfig withOpeningSnapshot(boolean enabled) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
        mcpInitiator,
        mapId,
        bindAddress,
        enabled,
        worldId);
  }

  public ShellConfig withWorldId(String worldId) {
    return new ShellConfig(
        storeDir,
        checkpointInterval,
        guiPort,
        mcpPort,
        mcpPath,
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
