package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.simos.sd.channel.DecisionChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **端口拓扑验收（spec §2.1/§四.3，2026-09-22 重写）**：**只有一个 MCP 口**，它就是 **GM 组**（读 + 通用写 + 全部窄写）； 决策人**不暴露
 * MCP**（它的权限靠进程内现算的权限组表达，见 {@code DecisionCallerFactory}）。用**真 MCP SDK 客户端**经真 socket 核 {@code
 * tools/list}，并逐条核"决策人口不再监听"与"渠道回到三条"。
 *
 * <p>★ **判别力来源**：工具面的 expected 集合是**逐条冻结**的（不是"含某些"）——口上丢掉任何一条窄工具都在 {@code
 * containsExactlyInAnyOrderElementsOf} 上当场红。
 *
 * <p>★ **口径变更**（取代 T4 的"两口拆分"）：旧设计拿**端口**当权限边界（{@code EXTERNAL_WITH_GM} 一口、{@code DECISION_AGENT}
 * 一口），用户 2026-09-22 裁定「MCP 和 GM Agent 处于同一权限级」+「决策人没有暴露 MCP」⇒ 端口拓扑回到**一口一模式**， 决策人改由 {@code
 * DecisionCallerFactory} 的权限组约束。
 *
 * <p>夹具最简：空 store 起壳即可（{@code Shell.start} 不要求创世），端口全 0 由 OS 分配。
 */
class McpPortTopologyTest {

  private static final String SERVER_NAME = "simos-shell";

  /**
   * T4 时代的决策人端口缺省（5717）。★ **这里有意写字面量**：那条配置项（{@code ShellConfig.decisionAgentMcpPort}）已随 T7
   * 拔掉，故"那个口没人监听"这件事只能对着**历史缺省值**证 —— 它正是旧实现会绑的那个口。
   */
  private static final int T4_DECISION_PORT = 5717;

  private static final List<String> READ_TOOLS =
      List.of(
          "simos.command.catalog",
          "simos.state.resolve",
          "simos.state.facets",
          "simos.timeline.branches",
          "simos.map.overview",
          "simos.map.hex",
          "simos.unit.list",
          "simos.unit.get",
          "simos.social.population");

  /**
   * **非窄写**的写工具（4 条）：3 条通用写（自选命令类型）+ 第 3 波第 2 步的批裁决 {@code sd.AdjudicateTick}
   * （命令类型由它要裁决的令决定，不是固定一条）——它们都不继承 {@code AbstractNarrowWriteTool}。
   */
  private static final List<String> NON_NARROW_WRITES =
      List.of("simos.command.submit", "simos.advance", "simos.fork", "sd.AdjudicateTick");

  /** M1 的 7 条 map 窄写（进 GM 桶）：决策人口**不得**含其中任何一条。 */
  private static final List<String> MAP_WRITES =
      List.of(
          "map.SetTerrain",
          "map.SetEdge",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup");

  /**
   * M2 的 20 条 unit 窄写：**只在 GM 组**（= 唯一的 MCP 口）。★ 旧 D-1 裁定曾让它们也挂决策人桶，**2026-09-22 已撤销**（用户：
   * 「决策人不能直接改地图等数据」）。★ 名单与 {@code SimosToolSource.addGmWrites} 的登记逐条同源。
   */
  private static final List<String> UNIT_WRITES =
      List.of(
          "unit.RenameUnit",
          "unit.CreateUnit",
          "unit.ReparentUnit",
          "unit.SetStrength",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.DisbandUnit",
          "unit.SetStatus",
          "unit.AttachUnit",
          "unit.DetachUnit",
          "unit.ReparentSubtree",
          "unit.SetFormationOffset",
          "unit.SplitFormation",
          "unit.MergeFormation",
          "unit.PlanSparseRoute",
          "unit.SetRejoinTarget",
          "unit.CreateCommandChain",
          "unit.UpdateCommandChain",
          "unit.ApplyCasualties");

  /**
   * GM 窄写（M1 起 11 条、M2 起 31 条、M3 起 43 条、T11C 起 44 条、**会话重置起 45 条**）：18 条 sd 窄写 + 7 条 map 窄写 + 20 条
   * unit 窄写；**都不是**通用写。
   */
  private static final List<String> GM_NARROW_WRITES =
      concat(
          List.of(
              "sd.IssueDirective",
              "sd.SubmitVerdict",
              "sd.SetDecisionMakerAccess",
              "sd.ResetDecisionMakerConversation",
              "sd.StartDecision",
              "sd.RunDecision",
              "sd.CreateNation",
              "sd.CreateArmy",
              "sd.CreateDecisionMaker",
              "sd.PutInfo",
              "sd.CreateCombat",
              "sd.AddCombatStage",
              "sd.SetStageOutcomeTable",
              "sd.CommitCombatOutcome",
              "sd.RecordCasualties",
              "sd.RegisterEffect",
              "sd.CancelEffect",
              "sd.SetDecisionMakerProvider"),
          MAP_WRITES,
          UNIT_WRITES);

  private static final Duration PORT_RELEASE_WAIT = Duration.ofSeconds(5);

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  /** J1：**唯一的 MCP 口 = GM 组**（读 9 + 非窄写 4 + 窄写 45 = 58）。 */
  @Test
  void theSingleMcpPortExposesTheGmToolFace() {
    try (McpSyncClient client = newClient(shell.boundMcpPort())) {
      McpSchema.InitializeResult init = client.initialize();
      assertThat(init.serverInfo().name()).isEqualTo(SERVER_NAME);

      assertThat(toolNames(client))
          .as("J1：唯一口 = GM 组（9 读 + 4 非窄写 + 45 窄写 = 58）")
          .containsExactlyInAnyOrderElementsOf(
              concat(READ_TOOLS, NON_NARROW_WRITES, GM_NARROW_WRITES));
    }
  }

  /** ★ J2：**决策人不再暴露 MCP** —— T4 时代的决策人端口（5717）在本壳起来时**无人监听**。 */
  @Test
  void theDecisionAgentPortIsNotBoundAnyMore() {
    assertThat(shell.boundMcpPort()).as("唯一的 MCP 口已绑定").isPositive();
    assertThat(canBind(T4_DECISION_PORT))
        .as(
            "J2：决策人端口（%d，T4 的缺省值）必须**可绑定** —— 旧实现会在这里起第二个 AgentToMcpServer。"
                + "若红了先分清两种情形：① 本壳又把它绑上了（T7 回退）；② 机器上有别的进程占着这个口（环境干扰，看 lsof）",
            T4_DECISION_PORT)
        .isTrue();
  }

  /** ★ J2：决策提交渠道**回到三条**（GUI / CLI / Http）——{@code McpDecisionChannel} 已随 T7 删除。 */
  @Test
  void decisionChannelsAreBackToTheGuiCliAndHttpThree() {
    assertThat(shell.decisionChannels().stream().map(DecisionChannel::channelId))
        .as("渠道清单：mcp 渠道不得再出现（决策人不经 MCP）")
        .containsExactlyInAnyOrder("gui", "cli", "http");
  }

  /** C8 的替代形态：{@code close()} 后唯一的 MCP 口可重新绑定（漏关即 {@code EADDRINUSE}）。 */
  @Test
  void closeReleasesTheSingleMcpPort() {
    int mcpPort = shell.boundMcpPort();
    assertThat(mcpPort).as("唯一的 MCP 口已绑定").isPositive();

    try (McpSyncClient client = newClient(mcpPort)) {
      client.initialize();
      assertThat(toolNames(client)).contains("sd.SetDecisionMakerAccess");
    }

    shell.close();

    assertEventuallyRebindable("唯一的 MCP 口", mcpPort);
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private McpSyncClient newClient(int port) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).build();
  }

  private static List<String> toolNames(McpSyncClient client) {
    return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
  }

  @SafeVarargs
  private static List<String> concat(List<String>... groups) {
    return java.util.Arrays.stream(groups).flatMap(List::stream).toList();
  }

  /** 轮询到端口可重新绑定（{@code close()} 漏关 MCP server 即在此红）。 */
  private static void assertEventuallyRebindable(String label, int port) {
    long deadline = System.nanoTime() + PORT_RELEASE_WAIT.toNanos();
    boolean bound = false;
    while (System.nanoTime() < deadline) {
      if (canBind(port)) {
        bound = true;
        break;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(bound).as("%s 端口 %d 在 close() 后必须可重新绑定", label, port).isTrue();
  }

  private static boolean canBind(int port) {
    try (ServerSocket probe = new ServerSocket()) {
      probe.setReuseAddress(true);
      probe.bind(new InetSocketAddress("127.0.0.1", port));
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
