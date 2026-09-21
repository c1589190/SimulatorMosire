package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GM MCP 工具使用只读面端到端验收（T8，spec §七.4 C22）：起真 {@link Shell}（四端口全 0），用 JDK {@link HttpClient} 打真 HTTP。
 *
 * <p>★ **数据源是真的**：{@code /api/gm/tool-usage} 读的是 {@code Shell} 给 GM 口（{@code
 * EXTERNAL_WITH_GM}）工具源套上的 记录装饰器（{@link io.mosire.simos.app.gm.RecordingToolSource}）留下的痕迹。夹具经**真
 * authorizer + 真工具注册表**执行工具 （与 MCP {@code tools/call} 同一条调用路径），故断言的是"工具真的被用过 ⇒ 端点真的看得到"。
 *
 * <p>★ **口径边界**（记入报告）：被权限硬拒 / 审批未放行 / **工具不存在**的调用**不记**——记录发生在 {@code AgentTool.execute}，
 * 那些在执行之前就被 {@code ToolCallAuthorizer} 挡下了（本类 {@link #doesNotRecordCallsThatNeverReachATool} 钉住这条）。
 *
 * <p>★ **空态不编造**：空库、未用过任何工具 ⇒ {@code 200 {"entries":[]}}（不是 404/500，也不是假数据）。
 */
class GmToolUsageApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void emptyBeforeAnyToolCall() throws Exception {
    HttpResponse<String> response = get("/api/gm/tool-usage");

    assertThat(response.statusCode()).as("空记录不是错误").isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("entries")).as("字段在、是数组、为空").isNotNull().isEmpty();
  }

  @Test
  void recordsSuccessfulToolCallWithToolNameAndResult() throws Exception {
    ToolResult result = call(CatalogTool.NAME, Map.of());
    assertThat(result.success()).as(result.message()).isTrue();

    JsonNode entries = getJson("/api/gm/tool-usage").get("entries");
    assertThat(entries).hasSize(1);
    JsonNode entry = entries.get(0);
    assertThat(entry.get("tool").asText()).isEqualTo(CatalogTool.NAME);
    assertThat(entry.get("ok").asBoolean()).isTrue();
    assertThat(entry.get("code").isNull()).as("成功 ⇒ code 为 null").isTrue();
    assertThat(entry.get("atEpochMs").asLong()).isPositive();
  }

  @Test
  void recordsFailedToolCallWithItsCode() throws Exception {
    ToolResult result = call(MapHexTool.NAME, Map.of("q", 1L));
    assertThat(result.success()).as("缺 r ⇒ 失败").isFalse();

    JsonNode entry = getJson("/api/gm/tool-usage").get("entries").get(0);
    assertThat(entry.get("ok").asBoolean()).isFalse();
    assertThat(entry.get("code").asText()).isEqualTo(result.code());
  }

  @Test
  void recordsNewestFirst() throws Exception {
    call(CatalogTool.NAME, Map.of());
    call(MapHexTool.NAME, Map.of("q", 1L));

    JsonNode entries = getJson("/api/gm/tool-usage").get("entries");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).get("tool").asText()).as("最新在前").isEqualTo(MapHexTool.NAME);
    assertThat(entries.get(1).get("tool").asText()).isEqualTo(CatalogTool.NAME);
  }

  @Test
  void doesNotRecordCallsThatNeverReachATool() throws Exception {
    ToolResult result =
        shell.toolAuthorizer().execute(shell.toolRegistry(), "no.such.tool", context(Map.of()));

    assertThat(result.success()).isFalse();
    assertThat(getJson("/api/gm/tool-usage").get("entries"))
        .as("工具不存在的调用在执行之前就被挡下，不得伪装成一次工具使用")
        .isEmpty();
  }

  @Test
  void endpointRejectsTheAsViewParameterFailClosed() throws Exception {
    HttpResponse<String> response = get("/api/gm/tool-usage?as=dm-1");

    assertThat(response.statusCode()).as("未接 redaction 的端点带 as= ⇒ 400").isEqualTo(400);
  }

  @Test
  void endpointIsGetOnly() throws Exception {
    HttpResponse<String> response = post("/api/gm/tool-usage", "{}");

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).contains("GET");
  }

  private ToolResult call(String toolName, Map<String, Object> args) {
    return shell.toolAuthorizer().execute(shell.toolRegistry(), toolName, context(args));
  }

  /** 与 {@code Shell.mcpCaller()} 同形（DEFAULT 桶 + 全放行 + 外部身份）：代表真 MCP 调用的上下文。 */
  private static ToolContext context(Map<String, Object> args) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
        Map.of(),
        args,
        AgentIdentity.external());
  }

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
