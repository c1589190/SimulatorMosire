package io.mosire.simos.app;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可执行入口（spec §3.4；计划 T1 Step 4）：解析命令行 → 起壳 → 打印生效配置 → 阻塞到 SIGINT → 关闭。
 *
 * <p>★ **只解析四个开关**（计划 T1 Step 4 的原文）：{@code --store <dir>}（必填）、{@code --gui-port N}、{@code
 * --mcp-port N}、{@code --approval-port N}（三者缺省取自 {@link ShellConfig}）。{@code mcpPath} / {@code
 * mcpInitiator} / {@code mapId} / {@code checkpointInterval} 暂无开关，取缺省——启用它们的口子在 T6/T7/T8
 * 接审批/MCP/GUI 时再开。
 *
 * <p>★ **不做 fat jar**（spec §3.4 的开口项）：{@code java -cp} 或 {@code ./mvnw -pl simos-app exec:java} 起。
 */
public final class ShellMain {

  private static final Logger LOG = LoggerFactory.getLogger(ShellMain.class);

  private ShellMain() {}

  /**
   * 入口。
   *
   * @param args 见类注的四个开关
   * @throws InterruptedException 等待停止信号时被中断（正常路径不会）
   */
  public static void main(String[] args) throws InterruptedException {
    ShellConfig config;
    try {
      config = parse(args);
    } catch (IllegalArgumentException e) {
      LOG.error("参数错误：{}", e.getMessage());
      LOG.error("用法：--store <dir> [--gui-port N] [--mcp-port N] [--approval-port N]");
      return;
    }
    run(config);
  }

  /**
   * 解析命令行。包级可见便于（将来）直接用例覆盖；T1 的冒烟测试不测它。
   *
   * @throws IllegalArgumentException 缺 {@code --store}、开关缺取值、端口非整数、或出现未知参数
   */
  static ShellConfig parse(String[] args) {
    Objects.requireNonNull(args, "args");
    Path store = null;
    int guiPort = ShellConfig.DEFAULT_GUI_PORT;
    int mcpPort = ShellConfig.DEFAULT_MCP_PORT;
    int approvalPort = ShellConfig.DEFAULT_APPROVAL_PORT;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--store" -> store = Path.of(value(args, ++i, "--store"));
        case "--gui-port" -> guiPort = port(value(args, ++i, "--gui-port"), "--gui-port");
        case "--mcp-port" -> mcpPort = port(value(args, ++i, "--mcp-port"), "--mcp-port");
        case "--approval-port" ->
            approvalPort = port(value(args, ++i, "--approval-port"), "--approval-port");
        default -> throw new IllegalArgumentException("未知参数: " + args[i]);
      }
    }
    if (store == null) {
      throw new IllegalArgumentException("必须提供 --store <dir>");
    }
    return new ShellConfig(
        store,
        ShellConfig.DEFAULT_CHECKPOINT_INTERVAL,
        guiPort,
        mcpPort,
        ShellConfig.DEFAULT_MCP_PATH,
        approvalPort,
        ShellConfig.DEFAULT_MCP_INITIATOR,
        ShellConfig.DEFAULT_MAP_ID);
  }

  /** 起壳并阻塞到停止信号。关闭走 try-with-resources；shutdown hook 先关一次（幂等，见 {@link Shell#close()}）。 */
  static void run(ShellConfig config) throws InterruptedException {
    try (Shell shell = Shell.start(config)) {
      LOG.info(
          "Simos Shell 已启动: store={} checkpointInterval={} 模块数={} guiPort={} mcpPort={} mcpPath={}"
              + " approvalPort={} mapId={}",
          config.storeDir(),
          config.checkpointInterval(),
          shell.registeredModuleCount(),
          shell.boundGuiPort(),
          config.mcpPort(),
          config.mcpPath(),
          config.approvalPort(),
          config.mapId());
      CountDownLatch stop = new CountDownLatch(1);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    LOG.info("收到停止信号，关闭 Shell");
                    shell.close();
                    stop.countDown();
                  },
                  "simos-shutdown"));
      stop.await();
    }
  }

  private static String value(String[] args, int index, String flag) {
    if (index >= args.length) {
      throw new IllegalArgumentException(flag + " 缺少取值");
    }
    return args[index];
  }

  private static int port(String text, String flag) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(flag + " 必须是整数: " + text);
    }
  }
}
