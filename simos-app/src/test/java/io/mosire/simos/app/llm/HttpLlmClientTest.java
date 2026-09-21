package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.mosire.simos.sd.adjudication.Breakpoints;
import io.mosire.simos.sd.adjudication.LlmRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M11 判据 C5/C6：OpenAI 兼容请求形状 + 错误路径不泄露密钥 / 不回显响应体。本地 stub，无外网。 */
class HttpLlmClientTest {

  private static final String KEY_SENTINEL = "sk-sentinel-0123456789";

  private HttpServer server;
  private volatile String lastMethod;
  private volatile String lastPath;
  private volatile String lastAuth;
  private volatile String lastBody;
  private volatile int requestCount;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopStub() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsOpenAiCompatibleRequestAndParsesContent() {
    respond(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}");
    HttpLlmClient client = clientWithKey(KEY_SENTINEL);

    String content = client.complete(request());

    assertThat(content).isEqualTo("{\"ok\":true}");
    assertThat(lastMethod).isEqualTo("POST");
    assertThat(lastPath).isEqualTo("/v1/chat/completions");
    assertThat(lastAuth).isEqualTo("Bearer " + KEY_SENTINEL);
    assertThat(lastBody)
        .contains("\"model\":\"gpt-test\"")
        .contains("\"role\":\"system\"")
        .contains("\"role\":\"user\"")
        .contains("断点 D1");
  }

  @Test
  void nonTwoHundredThrowsWithoutLeakingTheKeyOrTheBody() {
    respond(500, "{\"error\":\"server said LEAK-ME\"}");
    HttpLlmClient client = clientWithKey(KEY_SENTINEL);

    assertThatThrownBy(() -> client.complete(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("500")
        .satisfies(
            e -> {
              assertThat(e.getMessage()).doesNotContain(KEY_SENTINEL);
              assertThat(e.getMessage()).doesNotContain("LEAK-ME");
            });
  }

  @Test
  void badJsonThrows() {
    respond(200, "<html>not json</html>");
    assertThatThrownBy(() -> clientWithKey(KEY_SENTINEL).complete(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON");
  }

  @Test
  void missingContentThrows() {
    respond(200, "{\"choices\":[]}");
    assertThatThrownBy(() -> clientWithKey(KEY_SENTINEL).complete(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("choices");
  }

  @Test
  void blankKeyThrowsBeforeSending() {
    respond(200, "{}");
    HttpLlmClient client =
        new HttpLlmClient(
            baseUrl(), "gpt-test", () -> "  ", Duration.ofSeconds(5), HttpClient.newHttpClient());
    assertThatThrownBy(() -> client.complete(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("密钥");
    assertThat(requestCount).isZero();
  }

  private HttpLlmClient clientWithKey(String key) {
    return new HttpLlmClient(
        baseUrl(), "gpt-test", () -> key, Duration.ofSeconds(5), HttpClient.newHttpClient());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static LlmRequest request() {
    return new LlmRequest(Breakpoints.D1, "你是 simos 的裁决器，断点 D1。", "{\"brief\":\"推进\"}");
  }

  private void respond(int status, String body) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try (var in = exchange.getRequestBody()) {
            lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          lastMethod = exchange.getRequestMethod();
          lastPath = exchange.getRequestURI().getPath();
          lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
          requestCount++;
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
  }
}
