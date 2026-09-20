package io.mosire.simos.app;

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
    String bindAddress) {

  public static final int DEFAULT_CHECKPOINT_INTERVAL = 100;
  public static final int DEFAULT_GUI_PORT = 5711;
  public static final int DEFAULT_MCP_PORT = 5715;
  public static final String DEFAULT_MCP_PATH = "/mcp";
  public static final int DEFAULT_APPROVAL_PORT = 5713;
  public static final String DEFAULT_MCP_INITIATOR = "agent:external-mcp";
  public static final String DEFAULT_MAP_ID = "Map1";

  /** GUI / MCP 的缺省绑定地址：回环（M10；不裸暴露，反代场景显式传 {@code 0.0.0.0}）。 */
  public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

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
  }

  /**
   * 全部取缺省值的配置（端口 = spec 的四个默认值），只指定存储目录与 map 称谓留待覆盖。
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
        DEFAULT_BIND_ADDRESS);
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
        bindAddress);
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
        bindAddress);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白: " + value);
    }
    return value;
  }
}
