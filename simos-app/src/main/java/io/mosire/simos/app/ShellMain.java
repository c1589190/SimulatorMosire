package io.mosire.simos.app;

import io.mosire.simos.app.world.WorldRegistry;
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
 * <p>★ **解析的开关**（T1 Step 4 的四个 + M10 的 {@code --bind-address} + P1.1 的 {@code --world} / {@code
 * --config}；T9b 的 {@code --demo} 已随"没有就就地初始化"的裁定拔掉，见 {@link #parse})：
 *
 * <ul>
 *   <li>{@code --store <dir>}（命令行缺省值可由配置文件 {@code store} 提供；两处都没有才拒）；
 *   <li>{@code --gui-port N} / {@code --mcp-port N} / {@code --approval-port N}（缺省取 {@link
 *       ShellConfig}，可被配置文件覆盖）；
 *   <li>{@code --bind-address <host>}（GUI / MCP 的绑定地址；★ 审批端点恒回环，见 {@link
 *       ShellConfig#bindAddress()})；
 *   <li>{@code --world=<worldId>}（同时兼容 {@code --world <worldId>}）：**只在空库首启时**选择哪个世界生成器做 bootstrap，见
 *       {@link ShellConfig#worldId()} 与 {@link WorldRegistry}；
 *   <li>{@code --config <file>}（同时兼容 {@code --config=<file>}）：改指配置文件；缺省 {@value
 *       ShellConfigFile#DEFAULT_PATH}。
 * </ul>
 *
 * <p>★ **配置来源优先级（P1.1，用户 2026-10-09 口径）**：**命令行参数 &gt; 配置文件 &gt; 内置缺省**。缺省配置文件**不存在 =
 * 正常**（直接用内置缺省）；存在但坏数据 = **fail-closed 具名报错**（见 {@link ShellConfigFile}）。★ {@code
 * --economy-threads} 已拔掉（R3a 起组合根按单线程退化路径装配生产运行时）——传它按未知参数拒，不留在"接了线但其实没用"的半吊子形态里。
 *
 * <p>★ **世界从哪来**：**有世界就是有，没有就按选中的 worldId 就地初始化一个新的**。起壳后若库为空，经 {@link CoreSimos#bootstrapGenesis}
 * 种入 {@link WorldRegistry#require(String)} 解析出的生成器产出的创世状态；**非空库绝不覆盖** （数据安全线，见 {@link
 * #seedGenesisIfEmpty})。{@code --world} **不是运行时切换**：库非空时它没有任何作用。
 *
 * <p>★ **分发形态（M10）**：既是 {@code java -cp} / {@code ./mvnw -pl simos-app exec:java} 的入口，也是 {@code
 * maven-shade-plugin} 产出的 {@code simos-app-*-shaded.jar} 的 {@code Main-Class}： {@code java -jar
 * simos-app-0.1.0-SNAPSHOT-shaded.jar --store <dir> [--world=<worldId>] [--bind-address 0.0.0.0]}。
 */
public final class ShellMain {

  private static final Logger LOG = LoggerFactory.getLogger(ShellMain.class);

  private ShellMain() {}

  /**
   * 入口。
   *
   * @param args 见类注的开关
   * @throws InterruptedException 等待停止信号时被中断（正常路径不会）
   */
  public static void main(String[] args) throws InterruptedException {
    ShellConfig config;
    try {
      config = parse(args);
    } catch (IllegalArgumentException e) {
      LOG.error("参数错误：{}", e.getMessage());
      LOG.error(
          "用法：--store <dir> [--world=<worldId>] [--config <file>] [--gui-port N] [--mcp-port N]"
              + " [--approval-port N] [--bind-address <host>] [--opening-snapshot]");
      return;
    }
    run(config);
  }

  /**
   * 解析命令行并合并配置文件缺省值。包级可见便于直接用例覆盖。
   *
   * <p>★ 合并次序固定为：**命令行显式值 &gt; 配置文件显式值 &gt; {@link ShellConfig} 内置缺省**。命令行里"没出现"的参数不会去覆盖配置文件。
   *
   * @throws IllegalArgumentException 缺 store（命令行与配置文件都没有）、开关缺取值、端口非整数、未知 worldId、配置文件坏数据、或出现未知参数
   */
  static ShellConfig parse(String[] args) {
    Objects.requireNonNull(args, "args");
    Path configFile = null;
    boolean configExplicit = false;
    Path store = null;
    String worldId = null;
    Integer guiPort = null;
    Integer mcpPort = null;
    Integer approvalPort = null;
    String bindAddress = null;
    Boolean openingSnapshot = null;

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      String inlineWorld = inlineValue(arg, "--world");
      if (inlineWorld != null) {
        worldId = inlineWorld;
        continue;
      }
      String inlineConfig = inlineValue(arg, "--config");
      if (inlineConfig != null) {
        configFile = Path.of(inlineConfig);
        configExplicit = true;
        continue;
      }
      switch (arg) {
        case "--store" -> store = Path.of(value(args, ++i, "--store"));
        case "--gui-port" -> guiPort = port(value(args, ++i, "--gui-port"), "--gui-port");
        case "--mcp-port" -> mcpPort = port(value(args, ++i, "--mcp-port"), "--mcp-port");
        case "--approval-port" ->
            approvalPort = port(value(args, ++i, "--approval-port"), "--approval-port");
        case "--bind-address" -> bindAddress = value(args, ++i, "--bind-address");
        // ★ 兼容 `--world XXX` 与 `--world=XXX` 两种写法（用户 2026-10-09 口径：等号形是主形态）。
        case "--world" -> worldId = value(args, ++i, "--world");
        case "--config" -> {
          configFile = Path.of(value(args, ++i, "--config"));
          configExplicit = true;
        }
        // ★ 无值开关（P4）：它不开取值，故不在 value(...) 那一族里。
        case "--opening-snapshot" -> openingSnapshot = true;
        default -> throw new IllegalArgumentException("未知参数: " + arg);
      }
    }

    ShellConfigFile file =
        configExplicit
            ? ShellConfigFile.loadRequired(configFile)
            : ShellConfigFile.loadDefaultIfPresent();

    Path storeDir = coalesce(store, file.storeDir().orElse(null));
    if (storeDir == null) {
      throw new IllegalArgumentException(
          "必须提供 --store <dir>（或在配置文件 " + file.source() + " 里提供 store）");
    }
    String selectedWorld =
        coalesce(worldId, file.worldId().orElse(null), ShellConfig.DEFAULT_WORLD_ID);
    WorldRegistry.require(selectedWorld);

    return new ShellConfig(
        storeDir,
        coalesce(file.checkpointInterval().orElse(null), ShellConfig.DEFAULT_CHECKPOINT_INTERVAL),
        coalesce(guiPort, file.guiPort().orElse(null), ShellConfig.DEFAULT_GUI_PORT),
        coalesce(mcpPort, file.mcpPort().orElse(null), ShellConfig.DEFAULT_MCP_PORT),
        coalesce(file.mcpPath().orElse(null), ShellConfig.DEFAULT_MCP_PATH),
        coalesce(approvalPort, file.approvalPort().orElse(null), ShellConfig.DEFAULT_APPROVAL_PORT),
        coalesce(file.mcpInitiator().orElse(null), ShellConfig.DEFAULT_MCP_INITIATOR),
        coalesce(file.mapId().orElse(null), ShellConfig.DEFAULT_MAP_ID),
        coalesce(bindAddress, file.bindAddress().orElse(null), ShellConfig.DEFAULT_BIND_ADDRESS),
        coalesce(
            openingSnapshot,
            file.openingSnapshot().orElse(null),
            ShellConfig.DEFAULT_OPENING_SNAPSHOT),
        selectedWorld);
  }

  /**
   * 起壳并阻塞到停止信号。关闭走 try-with-resources；shutdown hook 先关一次（幂等，见 {@link Shell#close()}）。
   *
   * <p>★ **空库就地初始化**（T9b / T11 / P1.1）：壳起好后、阻塞前，若库为空则经 {@link CoreSimos#bootstrapGenesis} 种入 {@code
   * config.worldId()} 在 {@link WorldRegistry} 里对应的生成器产出的世界。非空库**不碰**（绝不覆盖已有世界）。 因此 bootstrap 发生在
   * GUI 监听之后、任何用户交互之前——{@link Shell} 把"装配"与"起 GUI"绑在 一个 {@link Shell#start} 里，没有更早的缝（记为 T9b
   * 的一处已知时序）。
   */
  static void run(ShellConfig config) throws InterruptedException {
    try (Shell shell = Shell.start(config)) {
      WorldRegistry.Entry world = WorldRegistry.require(config.worldId());
      if (seedGenesisIfEmpty(shell)) {
        LOG.info("空库 ⇒ 已就地初始化世界：worldId={}（{}）", world.id(), world.description());
      }
      LOG.info(
          "Simos Shell 已启动: store={} checkpointInterval={} 模块数={} bindAddress={} guiPort={}"
              + " mcpPort={} mcpPath={} approvalPort={} mapId={} worldId={}",
          config.storeDir(),
          config.checkpointInterval(),
          shell.registeredModuleCount(),
          config.bindAddress(),
          shell.boundGuiPort(),
          shell.boundMcpPort(),
          config.mcpPath(),
          config.approvalPort(),
          config.mapId(),
          config.worldId());
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
   * 空库就按 {@code shell.config().worldId()} 就地初始化一个新世界，非空库什么也不做（**数据安全线**：绝不覆盖已有世界）。
   *
   * <p>★ 抽出成方法是为了可测：{@code bootstrapGenesis} 本身也拒非空库（第二道闸），但"要不要走到那一步"的判定 原先在会阻塞到停止信号的 {@code run}
   * 里，无法直接用例覆盖。
   *
   * <p>★ **唯一安全口**：写入走 {@link CoreSimos#bootstrapGenesis}——全仓唯一绕过 {@code submit} 的写路径、且只在空库。
   * 本方法**不新开创世路径**，只是它的调用点与空库判定。★ 世界生成器由 {@link WorldRegistry#require(String)} 解析：未知 id
   * 具名抛（不该发生——{@link #parse} 已先拒一次；这里是防"绕过 parse 直接起壳"的第二道闸）。
   *
   * @param shell 已起好的壳（其 codec 已注册，故 bootstrapGenesis 能编码 checkpoint）
   * @return 是否真的初始化了（空库 ⇒ {@code true}；非空库 ⇒ {@code false}）
   * @throws IllegalStateException 竞态：判定为空库后、写之前库变非空（此时由 {@code bootstrapGenesis} 拒绝，不覆盖）
   * @throws IllegalArgumentException 配置里的 worldId 未登记
   */
  static boolean seedGenesisIfEmpty(Shell shell) {
    Objects.requireNonNull(shell, "shell");
    if (!shouldSeedGenesis(shell.coreSimos().branches())) {
      return false;
    }
    WorldRegistry.Entry world = WorldRegistry.require(shell.config().worldId());
    shell.coreSimos().bootstrapGenesis(world.genesis(shell.config().mapId()));
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

  /**
   * 取 {@code --flag=value} 的内联取值；不匹配该 flag 时返回 {@code null}。
   *
   * <p>★ {@code --world=}（空值）按"缺少取值"当场拒——不能让它退化成"用缺省世界"。
   */
  private static String inlineValue(String arg, String flag) {
    String prefix = flag + "=";
    if (!arg.startsWith(prefix)) {
      return null;
    }
    String value = arg.substring(prefix.length());
    if (value.isBlank()) {
      throw new IllegalArgumentException(flag + " 缺少取值（--flag= 后必须给值）");
    }
    return value;
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

  /** 取第一个非 null 值；调用方保证最后一个实参非 null（否则返回 null，由调用方处理）。 */
  @SafeVarargs
  private static <T> T coalesce(T... values) {
    for (T value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
