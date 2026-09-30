package io.mosire.simos.app;

import io.mosire.simos.app.world.RichWorld;
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
 * <p>★ **解析五个开关**（计划 T1 Step 4 的四个 + M10 新增 {@code --bind-address}；T9b 的 {@code --demo}
 * 已随"没有就就地初始化"的裁定拔掉，见 {@link #parse})：{@code --store <dir>}（必填）、 {@code --gui-port N}、 {@code
 * --mcp-port N}、{@code --approval-port N} （三者缺省取自 {@link ShellConfig}）、{@code --bind-address
 * <host>} （GUI / MCP 的绑定地址，缺省 {@code 127.0.0.1}； ★ 审批端点恒回环，见 {@link ShellConfig#bindAddress()})。 ★
 * R3a 起 {@code --economy-threads N} 已拔掉（class-first 引擎是单线程，旧并行结算随旧结算运行时退役）—— 传它按未知参数拒，
 * 不留在"接了线但其实没用"的半吊子形态里。 {@code mcpPath} / {@code mcpInitiator} / {@code mapId} / {@code
 * checkpointInterval} 暂无开关，取缺省。
 *
 * <p>★ **世界从哪来**：**有世界就是有，没有就就地初始化一个新的**。起壳后若库为空，经 {@link CoreSimos#bootstrapGenesis} 就地种入 {@link
 * RichWorld}（{@code v17levant} 复刻）；**非空库绝不覆盖**（数据安全线，见 {@link
 * #seedGenesisIfEmpty})。**没有"选世界"这一层**：命令行不含 {@code --world} / {@code --demo} 之类的世界开关。
 *
 * <p>★ **分发形态（M10）**：既是 {@code java -cp} / {@code ./mvnw -pl simos-app exec:java} 的入口，也是 {@code
 * maven-shade-plugin} 产出的 {@code simos-app-*-shaded.jar} 的 {@code Main-Class}： {@code java -jar
 * simos-app-0.1.0-SNAPSHOT-shaded.jar --store <dir> [--bind-address 0.0.0.0]}。
 */
public final class ShellMain {

  private static final Logger LOG = LoggerFactory.getLogger(ShellMain.class);

  private ShellMain() {}

  /**
   * 入口。
   *
   * @param args 见类注的五个开关
   * @throws InterruptedException 等待停止信号时被中断（正常路径不会）
   */
  public static void main(String[] args) throws InterruptedException {
    ShellConfig config;
    try {
      config = parse(args);
    } catch (IllegalArgumentException e) {
      LOG.error("参数错误：{}", e.getMessage());
      LOG.error(
          "用法：--store <dir> [--gui-port N] [--mcp-port N] [--approval-port N]"
              + " [--bind-address <host>] [--opening-snapshot]");
      return;
    }
    run(config);
  }

  /**
   * 解析命令行。包级可见便于直接用例覆盖。
   *
   * @throws IllegalArgumentException 缺 {@code --store}、开关缺取值、端口非整数、或出现未知参数
   */
  static ShellConfig parse(String[] args) {
    Objects.requireNonNull(args, "args");
    Path store = null;
    int guiPort = ShellConfig.DEFAULT_GUI_PORT;
    int mcpPort = ShellConfig.DEFAULT_MCP_PORT;
    int approvalPort = ShellConfig.DEFAULT_APPROVAL_PORT;
    String bindAddress = ShellConfig.DEFAULT_BIND_ADDRESS;
    boolean openingSnapshot = ShellConfig.DEFAULT_OPENING_SNAPSHOT;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--store" -> store = Path.of(value(args, ++i, "--store"));
        case "--gui-port" -> guiPort = port(value(args, ++i, "--gui-port"), "--gui-port");
        case "--mcp-port" -> mcpPort = port(value(args, ++i, "--mcp-port"), "--mcp-port");
        case "--approval-port" ->
            approvalPort = port(value(args, ++i, "--approval-port"), "--approval-port");
        case "--bind-address" -> bindAddress = value(args, ++i, "--bind-address");
        // ★ 无值开关（P4）：它不开取值，故不在 value(...) 那一族里。
        case "--opening-snapshot" -> openingSnapshot = true;
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
        ShellConfig.DEFAULT_MAP_ID,
        bindAddress,
        openingSnapshot);
  }

  /**
   * 起壳并阻塞到停止信号。关闭走 try-with-resources；shutdown hook 先关一次（幂等，见 {@link Shell#close()}）。
   *
   * <p>★ **空库就地初始化**（T9b / T11）：壳起好后、阻塞前，若库为空则经 {@link CoreSimos#bootstrapGenesis} 种入 {@link
   * RichWorld}（{@code v17levant} 复刻的富世界）。非空库**不碰**（绝不覆盖已有世界）。 因此 bootstrap 发生在 GUI
   * 监听之后、任何用户交互之前——{@link Shell} 把"装配"与"起 GUI"绑在 一个 {@link Shell#start} 里，没有更早的缝（记为 T9b 的一处已知时序）。
   */
  static void run(ShellConfig config) throws InterruptedException {
    try (Shell shell = Shell.start(config)) {
      if (seedGenesisIfEmpty(shell)) {
        LOG.info("空库 ⇒ 已就地初始化世界：v17levant 复刻（59223 hex / 252 区域 / 240 条河流边）");
      }
      LOG.info(
          "Simos Shell 已启动: store={} checkpointInterval={} 模块数={} bindAddress={} guiPort={}"
              + " mcpPort={} mcpPath={} approvalPort={} mapId={}",
          config.storeDir(),
          config.checkpointInterval(),
          shell.registeredModuleCount(),
          config.bindAddress(),
          shell.boundGuiPort(),
          shell.boundMcpPort(),
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

  /**
   * 空库就就地初始化一个新世界（{@link RichWorld}），非空库什么也不做（**数据安全线**：绝不覆盖已有世界）。
   *
   * <p>★ 抽出成方法是为了可测：{@code bootstrapGenesis} 本身也拒非空库（第二道闸），但"要不要走到那一步"的判定 原先在会阻塞到停止信号的 {@code run}
   * 里，无法直接用例覆盖。
   *
   * <p>★ **唯一安全口**：写入走 {@link CoreSimos#bootstrapGenesis}——全仓唯一绕过 {@code submit} 的写路径、且只在空库。
   * 本方法**不新开创世路径**，只是它的调用点与空库判定。
   *
   * @param shell 已起好的壳（其 codec 已注册，故 bootstrapGenesis 能编码 checkpoint）
   * @return 是否真的初始化了（空库 ⇒ {@code true}；非空库 ⇒ {@code false}）
   * @throws IllegalStateException 竞态：判定为空库后、写之前库变非空（此时由 {@code bootstrapGenesis} 拒绝，不覆盖）
   */
  static boolean seedGenesisIfEmpty(Shell shell) {
    Objects.requireNonNull(shell, "shell");
    if (!shouldSeedGenesis(shell.coreSimos().branches())) {
      return false;
    }
    shell.coreSimos().bootstrapGenesis(RichWorld.state(shell.config().mapId()));
    return true;
  }

  /**
   * 是否就地初始化世界：**只有库为空**才初始化——非空库绝不覆盖已有世界。
   *
   * <p>★ 抽成纯函数是为了可测（{@code run} 会阻塞到停止信号，无法直接用例覆盖）。
   *
   * @param branches 库里已有的分支（空 = 空库）
   */
  static boolean shouldSeedGenesis(Set<BranchId> branches) {
    return branches.isEmpty();
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
