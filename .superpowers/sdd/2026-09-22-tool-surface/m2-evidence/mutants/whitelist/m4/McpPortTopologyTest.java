package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.store.SqliteStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **T4 端口拓扑验收（spec §二.2 / §七.2 的 C6~C9）**：现有 MCP 口 = {@code EXTERNAL ∪ GM}；新决策人口 = 仅 {@code
 * DECISION_AGENT}。用**真 MCP SDK 客户端**经真 socket 分别连两口，逐条核 {@code tools/list} 与"工具不存在"。
 *
 * <p>★ **判别力来源**：两口的 expected 集合是**逐条冻结**的（不是"含某些"）——现有口丢掉 GM 窄工具、或决策人口错挂通用写，都在 {@code
 * containsExactlyInAnyOrderElementsOf} 上当场红。C9 的"工具不存在"用 SDK 的 {@link McpError}（协议层 {@code Unknown
 * tool: invalid_tool_name}）证，**不是**"存在但被业务拒"。
 *
 * <p>★ **权限边界 = 端口级、不是认证级**（spec §二.3）：本用例只证"两口工具面不同"，**不**证两个身份安全隔离——全仓无多用户认证， 谁连得上端口就有该口的工具面。
 *
 * <p>夹具最简：空 store 起壳即可（{@code Shell.start} 不要求创世），四端口全 0 由 OS 分配。
 */
class McpPortTopologyTest {

  private static final String EXISTING_SERVER_NAME = "simos-shell";
  private static final String DECISION_SERVER_NAME = "simos-shell-decision";

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

  private static final List<String> GENERIC_WRITES =
      List.of("simos.command.submit", "simos.advance", "simos.fork");

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
   * M2 的 20 条 unit 窄写：**同一批同时进 GM 桶与决策人桶**（用户裁定 D-1；它们是窄工具、命令类型在工具里固定死， 与 N9「不给通用写」不冲突）。★ 名单与
   * {@code SimosToolSource} 两处登记逐条同源。
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

  /** GM 窄写（M1 起 11 条、M2 起 31 条）：4 条 sd 窄写 + 7 条 map 窄写 + 20 条 unit 窄写；**都不是**通用写。 */
  private static final List<String> GM_NARROW_WRITES =
      concat(
          List.of("sd.IssueDirective", "sd.SubmitVerdict", "sd.SetViewScope", "sd.StartDecision"),
          MAP_WRITES,
          UNIT_WRITES);

  /**
   * 决策人桶的窄写（M2 起 22 条）：2 条 sd 窄写 + 20 条 unit 窄写（用户裁定 D-1）；**无** {@code sd.SetViewScope}、
   * **无**通用写、**无** map 窄写。
   */
  private static final List<String> DECISION_AGENT_WRITES =
      concat(
          List.of("sd.IssueDirective", "sd.SubmitVerdict"), UNIT_WRITES, List.of("map.SetTerrain"));

  private static final Duration PORT_RELEASE_WAIT = Duration.ofSeconds(5);

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  /** C6：现有口同时含通用写与 GM 三十一条窄工具（D2="加"；T10 起 +sd.StartDecision，M1 起 +7 map，M2 起 +20 unit）。 */
  @Test
  void existingPortExposesExternalUnionGmToolFace() {
    try (McpSyncClient client = newClient(shell.boundMcpPort())) {
      McpSchema.InitializeResult init = client.initialize();
      assertThat(init.serverInfo().name()).isEqualTo(EXISTING_SERVER_NAME);

      assertThat(toolNames(client))
          .as("C6：现有口 = EXTERNAL ∪ GM（9 读 + 3 通用写 + 31 GM 窄写 = 43）")
          .containsExactlyInAnyOrderElementsOf(
              concat(READ_TOOLS, GENERIC_WRITES, GM_NARROW_WRITES));
    }
  }

  /** C7：决策人口仅 DECISION_AGENT 桶——无通用写、无 {@code sd.SetViewScope}、**无 7 条 map 窄写**。 */
  @Test
  void decisionPortExposesOnlyDecisionAgentToolFace() {
    try (McpSyncClient client = newClient(shell.boundDecisionAgentMcpPort())) {
      McpSchema.InitializeResult init = client.initialize();
      assertThat(init.serverInfo().name())
          .as("决策人口自报名与现有口不同（spec §六.4）")
          .isEqualTo(DECISION_SERVER_NAME);

      List<String> names = toolNames(client);
      assertThat(names)
          .as("C7：决策人口 = 9 读 + 22 窄写（2 条 sd + 20 条 unit，用户裁定 D-1；无 SetViewScope、无通用写、无 map 窄写）")
          .containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, DECISION_AGENT_WRITES));
      // ★ 下面三条反向断言的判别力**只在与上面那份手抄常量脱钩时才显现**：
      //   若"某条工具进了决策桶"而 DECISION_AGENT_WRITES 没跟着同步，上面那条精确匹配先红（这三条此时是冗余的）；
      //   但若**两侧同改**——工具进桶 + 常量同步，这正是真实重构最可能的形态——精确匹配会绿，
      //   只剩这三条守得住桶边界（M2 的 m4 变异体就是按后者造的）。
      assertThat(names)
          .as("C7 反向①：通用写（submit/advance/fork）不在决策人口——N9 的原文")
          .doesNotContainAnyElementsOf(GENERIC_WRITES)
          .as("C7 反向②：sd.SetViewScope 不在决策人口（配权工具归 GM）")
          .doesNotContain("sd.SetViewScope")
          .as("C7 反向③：7 条 map 窄写不在决策人口（M1 的桶边界）——★ M2 的 unit 20 条**有意**两桶都有，故不在此列")
          .doesNotContainAnyElementsOf(MAP_WRITES);
    }
  }

  /** C8：两个 MCP server 同时监听、端口不同；{@code close()} 后两者都不可连。 */
  @Test
  void bothServersListenAndCloseReleasesBothPorts() {
    int existingPort = shell.boundMcpPort();
    int decisionPort = shell.boundDecisionAgentMcpPort();
    assertThat(existingPort).as("现有口已绑定").isPositive();
    assertThat(decisionPort).as("决策人口已绑定").isPositive();
    assertThat(decisionPort).as("C8：两口必须不同").isNotEqualTo(existingPort);

    try (McpSyncClient existing = newClient(existingPort);
        McpSyncClient decision = newClient(decisionPort)) {
      existing.initialize();
      decision.initialize();
      assertThat(toolNames(existing)).contains("sd.SetViewScope");
      assertThat(toolNames(decision)).doesNotContain("sd.SetViewScope");
    }

    shell.close();

    assertEventuallyRebindable("现有口", existingPort);
    assertEventuallyRebindable("决策人口", decisionPort);
  }

  /** C9：端口是唯一边界——决策人口调 {@code simos.command.submit} ⇒ 工具不存在（协议层 Unknown tool），且不留 revision。 */
  @Test
  void decisionPortHasNoGenericWriteSoSubmitIsUnknownTool() throws Exception {
    long revisionsBefore = revisionRowCount();

    try (McpSyncClient decision = newClient(shell.boundDecisionAgentMcpPort())) {
      decision.initialize();
      assertThatThrownBy(
              () ->
                  decision.callTool(
                      new McpSchema.CallToolRequest(
                          "simos.command.submit",
                          Map.of(
                              "type",
                              "unit.RenameUnit",
                              "payloadJson",
                              "{\"id\":\"u-1\",\"name\":\"x\"}",
                              "branch",
                              "main",
                              "expectedRevision",
                              1L))))
          .as("C9：决策人口没有通用写 ⇒ 协议层工具不存在（不是『存在但被拒』）")
          .isInstanceOf(McpError.class)
          .hasMessageContaining("Unknown tool");
    }

    assertThat(revisionRowCount()).as("工具不存在 ⇒ 不可能留下 revision").isEqualTo(revisionsBefore);
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

  /** 独立 store 读 {@code revisions} 行数（C9 的"不留 revision"按行断言）。 */
  private long revisionRowCount() {
    try (SqliteStore store = SqliteStore.open(tempDir.resolve(CoreSimos.DB_FILE_NAME))) {
      return store.inTransaction(
          connection -> {
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          });
    }
  }

  /** 轮询到端口可重新绑定（{@code close()} 漏关第二个 server 即在此红）。 */
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
