package io.mosire.simos.app;

import io.mosire.simos.app.demo.DemoWorld;
import io.mosire.simos.core.CoreSimos;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可执行入口（spec §3.4；计划 T1 Step 4）：解析命令行 → 起壳 → 打印生效配置 → 阻塞到 SIGINT → 关闭。
 *
 * <p>★ **解析六个开关**（计划 T1 Step 4 的四个 + T9b 新增 {@code --demo} + M10 新增 {@code --bind-address}）：{@code
 * --store <dir>}（必填）、 {@code --demo}（空库时种演示世界）、{@code --gui-port N}、{@code --mcp-port N}、{@code
 * --approval-port N} （后三者缺省取自 {@link ShellConfig}）、{@code --bind-address <host>}（GUI / MCP 的绑定地址，缺省
 * {@code 127.0.0.1}； ★ 审批端点恒回环，见 {@link ShellConfig#bindAddress()}）。{@code mcpPath} / {@code
 * mcpInitiator} / {@code mapId} / {@code checkpointInterval} 暂无开关，取缺省——启用它们的口子在 T6/T7/T8
 * 接审批/MCP/GUI 时再开。
 *
 * <p>★ **分发形态（M10）**：既是 {@code java -cp} / {@code ./mvnw -pl simos-app exec:java} 的入口，也是 {@code
 * maven-shade-plugin} 产出的 {@code simos-app-*-shaded.jar} 的 {@code Main-Class}： {@code java -jar
 * simos-app-0.1.0-SNAPSHOT-shaded.jar --store <dir> [--demo] [--bind-address 0.0.0.0]}。
 */
public final class ShellMain {

  private static final Logger LOG = LoggerFactory.getLogger(ShellMain.class);

  private ShellMain() {}

  /** 解析结果：配置 + 首启是否种演示世界（{@code --demo}）。 */
  record Parsed(ShellConfig config, boolean demo) {}

  /**
   * 入口。
   *
   * @param args 见类注的五个开关
   * @throws InterruptedException 等待停止信号时被中断（正常路径不会）
   */
  public static void main(String[] args) throws InterruptedException {
    Parsed parsed;
    try {
      parsed = parse(args);
    } catch (IllegalArgumentException e) {
      LOG.error("参数错误：{}", e.getMessage());
      LOG.error(
          "用法：--store <dir> [--demo] [--gui-port N] [--mcp-port N] [--approval-port N]"
              + " [--bind-address <host>]");
      return;
    }
    run(parsed.config(), parsed.demo());
  }

  /**
   * 解析命令行。包级可见便于直接用例覆盖。
   *
   * @throws IllegalArgumentException 缺 {@code --store}、开关缺取值、端口非整数、或出现未知参数
   */
  static Parsed parse(String[] args) {
    Objects.requireNonNull(args, "args");
    Path store = null;
    boolean demo = false;
    int guiPort = ShellConfig.DEFAULT_GUI_PORT;
    int mcpPort = ShellConfig.DEFAULT_MCP_PORT;
    int approvalPort = ShellConfig.DEFAULT_APPROVAL_PORT;
    String bindAddress = ShellConfig.DEFAULT_BIND_ADDRESS;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--store" -> store = Path.of(value(args, ++i, "--store"));
        case "--demo" -> demo = true;
        case "--gui-port" -> guiPort = port(value(args, ++i, "--gui-port"), "--gui-port");
        case "--mcp-port" -> mcpPort = port(value(args, ++i, "--mcp-port"), "--mcp-port");
        case "--approval-port" ->
            approvalPort = port(value(args, ++i, "--approval-port"), "--approval-port");
        case "--bind-address" -> bindAddress = value(args, ++i, "--bind-address");
        default -> throw new IllegalArgumentException("未知参数: " + args[i]);
      }
    }
    if (store == null) {
      throw new IllegalArgumentException("必须提供 --store <dir>");
    }
    return new Parsed(
        new ShellConfig(
            store,
            ShellConfig.DEFAULT_CHECKPOINT_INTERVAL,
            guiPort,
            mcpPort,
            ShellConfig.DEFAULT_MCP_PATH,
            approvalPort,
            ShellConfig.DEFAULT_MCP_INITIATOR,
            ShellConfig.DEFAULT_MAP_ID,
            bindAddress),
        demo);
  }

  /**
   * 起壳并阻塞到停止信号。关闭走 try-with-resources；shutdown hook 先关一次（幂等，见 {@link Shell#close()}）。
   *
   * <p>★ **{@code --demo} 的空库首启**（T9b）：壳起好后、阻塞前，若库为空则经 {@link CoreSimos#bootstrapGenesis} 种入 {@link
   * DemoWorld}。非空库**不碰**（绝不覆盖已有世界）。 因此 bootstrap 发生在 GUI 监听之后、任何用户交互之前——{@link Shell} 把"装配"与"起
   * GUI"绑在 一个 {@link Shell#start} 里，没有更早的缝（记为 T9b 的一处已知时序）。
   */
  static void run(ShellConfig config, boolean demo) throws InterruptedException {
    try (Shell shell = Shell.start(config)) {
      if (demo && shell.coreSimos().branches().isEmpty()) {
        shell.coreSimos().bootstrapGenesis(DemoWorld.state(config.mapId()));
        LOG.info("已种入演示世界（--demo）：单位 u-1 在 [1,1]，走廊 [1,1]→[1,2]→[1,3]，人口 [1,1]=15000");
      }
      LOG.info(
          "Simos Shell 已启动: store={} checkpointInterval={} 模块数={} bindAddress={} guiPort={}"
              + " mcpPort={} mcpPath={} approvalPort={} mapId={}",
          config.storeDir(),
          config.checkpointInterval(),
          shell.registeredModuleCount(),
          config.bindAddress(),
          shell.boundGuiPort(),
          config.mcpPort(),
          config.mcpPath(),
          config.approvalPort(),
          config.mapId());
      LOG.info("WebUI 就绪（点击打开）：http://127.0.0.1:{}/", shell.boundGuiPort());
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
