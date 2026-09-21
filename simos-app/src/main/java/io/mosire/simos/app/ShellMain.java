package io.mosire.simos.app;

import io.mosire.simos.app.demo.RichWorld;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.util.state.BranchId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可执行入口（spec §3.4；计划 T1 Step 4）：解析命令行 → 起壳 → 打印生效配置 → 阻塞到 SIGINT → 关闭。
 *
 * <p>★ **解析七个开关**（计划 T1 Step 4 的四个 + T9b 新增 {@code --demo} + M10 新增 {@code --bind-address} + T4 新增
 * {@code --decision-agent-mcp-port}）：{@code --store <dir>}（必填）、 {@code --demo}（空库时种演示世界）、{@code
 * --gui-port N}、{@code --mcp-port N}、{@code --approval-port N}、{@code --decision-agent-mcp-port
 * N}（后四者缺省取自 {@link ShellConfig}）、{@code --bind-address <host>}（GUI / MCP / 决策人 MCP 的绑定地址，缺省 {@code
 * 127.0.0.1}； ★ 审批端点恒回环，见 {@link ShellConfig#bindAddress()}）。{@code mcpPath} / {@code mcpInitiator}
 * / {@code mapId} / {@code checkpointInterval} 暂无开关，取缺省——启用它们的口子在 T6/T7/T8 接审批/MCP/GUI 时再开。
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
              + " [--decision-agent-mcp-port N] [--bind-address <host>]");
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
    int decisionAgentMcpPort = ShellConfig.DEFAULT_DECISION_AGENT_MCP_PORT;
    String bindAddress = ShellConfig.DEFAULT_BIND_ADDRESS;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--store" -> store = Path.of(value(args, ++i, "--store"));
        case "--demo" -> demo = true;
        case "--gui-port" -> guiPort = port(value(args, ++i, "--gui-port"), "--gui-port");
        case "--mcp-port" -> mcpPort = port(value(args, ++i, "--mcp-port"), "--mcp-port");
        case "--approval-port" ->
            approvalPort = port(value(args, ++i, "--approval-port"), "--approval-port");
        case "--decision-agent-mcp-port" ->
            decisionAgentMcpPort =
                port(value(args, ++i, "--decision-agent-mcp-port"), "--decision-agent-mcp-port");
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
            bindAddress,
            decisionAgentMcpPort),
        demo);
  }

  /**
   * 起壳并阻塞到停止信号。关闭走 try-with-resources；shutdown hook 先关一次（幂等，见 {@link Shell#close()}）。
   *
   * <p>★ **{@code --demo} 的空库首启**（T9b / T11）：壳起好后、阻塞前，若库为空则经 {@link CoreSimos#bootstrapGenesis} 种入
   * {@link RichWorld}（{@code v17levant} 复刻的富世界）。非空库**不碰**（绝不覆盖已有世界）。 因此 bootstrap 发生在 GUI
   * 监听之后、任何用户交互之前——{@link Shell} 把"装配"与"起 GUI"绑在 一个 {@link Shell#start} 里，没有更早的缝（记为 T9b 的一处已知时序）。
   */
  static void run(ShellConfig config, boolean demo) throws InterruptedException {
    try (Shell shell = Shell.start(config)) {
      if (shouldSeedGenesis(demo, shell.coreSimos().branches())) {
        shell.coreSimos().bootstrapGenesis(RichWorld.state(config.mapId()));
        LOG.info("已种入富世界（--demo）：v17levant 复刻（59223 hex / 252 区域 / 240 条河流边）");
      }
      LOG.info(
          "Simos Shell 已启动: store={} checkpointInterval={} 模块数={} bindAddress={} guiPort={}"
              + " mcpPort={} mcpPath={} approvalPort={} decisionAgentMcpPort={} mapId={}",
          config.storeDir(),
          config.checkpointInterval(),
          shell.registeredModuleCount(),
          config.bindAddress(),
          shell.boundGuiPort(),
          shell.boundMcpPort(),
          config.mcpPath(),
          config.approvalPort(),
          shell.boundDecisionAgentMcpPort(),
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

  /**
   * 是否种入创世世界（C25）：**只有 {@code --demo} 且库为空**才种——非空库绝不覆盖已有世界。
   *
   * <p>★ 抽成纯函数是为了可测：{@code bootstrapGenesis} 本身也拒非空库（第二道闸），但"要不要走到那一步"的判定 在 {@code run} 里，而 {@code
   * run} 会阻塞到停止信号、无法直接用例覆盖。
   *
   * @param demo 命令行给了 {@code --demo}
   * @param branches 库里已有的分支（空 = 空库）
   */
  static boolean shouldSeedGenesis(boolean demo, Set<BranchId> branches) {
    return demo && branches.isEmpty();
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
