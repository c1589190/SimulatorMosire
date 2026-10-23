package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.tools.SimosToolSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ 设计 §8.5 / §10.4 H1–H3：HTTP 审批面与旧 GM 审批工具已退役。
 *
 * <p>本类不测"旧代理怎么透传"——那个面已经不存在。它只守退役后的两条硬事实：
 *
 * <ol>
 *   <li>生产源码没有任何 {@code HttpApprovalChannel} / {@code ApprovalHttpEndpoint} / {@code
 *       DecisionPackagesHttpHandler} 引用，也不再有 GUI {@code /api/approvals} 路由；
 *   <li>GM 工具面不再注册旧单条审批工具，且外部 HTTP 请求拿不到审批代理（404，而不是代理到某个不存在的端点）。
 * </ol>
 */
class ShellApprovalTest {

  private static final Set<String> RETIRED_APPROVAL_CLASSES =
      Set.of(
          "io.mosire.agentlib.approval.HttpApprovalChannel",
          "io.mosire.agentlib.approval.ApprovalHttpEndpoint",
          "io.mosire.agentlib.approval.DecisionPackagesHttpHandler");

  private static final List<String> RETIRED_GM_APPROVAL_TOOLS =
      List.of(
          "simos.gm.approvals",
          "simos.gm.approve",
          "simos.gm.packet.decide",
          "simos.gm.packet.execute");

  @TempDir Path tempDir;

  @Test
  void productionSourceHasNoHttpApprovalWiring() throws Exception {
    List<Path> mainSources = productionSources();
    assertThat(mainSources).as("★ 扫描面自证：必须真的找到生产源码（0 个 = 下面的零命中是假的）").hasSizeGreaterThan(100);

    List<String> offenders = new java.util.ArrayList<>();
    for (Path file : mainSources) {
      String source = Files.readString(file);
      for (String retired : RETIRED_APPROVAL_CLASSES) {
        if (source.contains(retired)) {
          offenders.add(file + " -> " + retired);
        }
      }
    }
    assertThat(offenders).as("★★ H1/H2：生产代码不得再装配 HTTP 审批端点；命中 = 旧面回流").isEmpty();
    String guiServer =
        Files.readString(
            productionSources().stream()
                .filter(
                    path ->
                        path.endsWith(
                            Path.of(
                                "simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java")))
                .findFirst()
                .orElseThrow());
    assertThat(guiServer)
        .as("★★ H2：GUI 生产代码不得再有 /api/approvals 透传路由")
        .doesNotContain("/api/approvals");
    for (String retired : RETIRED_APPROVAL_CLASSES) {
      assertThatThrownByClassAbsent(retired);
    }
  }

  @Test
  void guiNoLongerServesApprovalProxyAndGmFaceHasNoLegacyApprovalTools() throws Exception {
    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0))) {
      assertThat(shell.boundGuiPort()).isPositive();
      assertThat(get(shell.boundGuiPort(), "/api/approvals").statusCode())
          .as("★★ H2：GUI /api/approvals 必须 404（不得代理到已退役端点）")
          .isEqualTo(404);
      assertThat(post(shell.boundGuiPort(), "/api/approvals/ap-x").statusCode())
          .as("★★ H2：POST 旧审批面同样 404（路由整体删除，不是只删 GET）")
          .isEqualTo(404);

      Set<String> gmFace =
          shell.toolsFor(SimosToolSource.Role.GM).stream()
              .map(AgentTool::name)
              .collect(Collectors.toSet());
      assertThat(gmFace).as("★ 扫描面自证：GM 工具面必须非空（否则 doesNotContain 恒真）").isNotEmpty();
      assertThat(gmFace)
          .as("★★ H2：旧单条审批工具不得再注册到 GM 面")
          .doesNotContainAnyElementsOf(RETIRED_GM_APPROVAL_TOOLS);
      assertThat(shell.pendingApprovals().pending()).as("新链路不靠旧的全局待批表；启动后它必须为空").isEmpty();
    }
  }

  private static void assertThatThrownByClassAbsent(String className) {
    try {
      Class.forName(className, false, ShellApprovalTest.class.getClassLoader());
      throw new AssertionError("★★ H1：已退役的审批类型仍在 classpath：" + className);
    } catch (ClassNotFoundException expected) {
      // expected
    }
  }

  private static List<Path> productionSources() throws Exception {
    Path root = Path.of("..").toAbsolutePath().normalize();
    try (var modules = Files.list(root)) {
      return modules
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith("simos-"))
          .flatMap(
              module -> {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                  return java.util.stream.Stream.empty();
                }
                try {
                  return Files.walk(main).filter(path -> path.toString().endsWith(".java"));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              })
          .toList();
    }
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> post(int port, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"decision\":\"deny\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
