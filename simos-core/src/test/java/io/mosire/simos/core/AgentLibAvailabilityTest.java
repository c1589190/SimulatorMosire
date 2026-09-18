package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M0 验收：证明本地仓库里的 agentlib-mosire 是完整构建，而不是那个只有 109 个类的陈旧 JAR。
 *
 * <p>背景：2026-09-16 本机实测 {@code ~/.m2} 里的 0.1.0-SNAPSHOT 是 2026-09-13 的陈旧构建，只有 109 个类， 缺 {@code
 * permission} 包 8 个类与 {@code plugin.HostServices}；同一时刻 {@code AgentLibMosire/target/classes}（重编译）有
 * 118 个类。spec §10.5 依赖的能力（ToolCallAuthorizer / ResourceAuthorizer / Digest / ApprovalCoordinator /
 * AskKind）中 {@code ResourceAuthorizer} 在陈旧 JAR 里缺失。这个测试就是防止那种状态悄悄回来。
 */
class AgentLibAvailabilityTest {

  /** agentlib-mosire 源码构建的类文件数（118）；2026-09-13 的陈旧构建为 109。用 >= 以免新增类时误报。 */
  private static final int MIN_EXPECTED_CLASSES = 118;

  /**
   * 编译期证明：这两个类是 spec §10.5 点名的复用入口，只要它们不在依赖里，本文件根本编译不过。
   *
   * <p>（同时这也让两条 import 成为被代码引用的 import——否则 checkstyle 的 UnusedImports 会拦下它们。）
   */
  @Test
  void agentLibEntryPointsAreOnCompileClasspath() {
    assertThat(ToolCallAuthorizer.class.getName())
        .isEqualTo("io.mosire.agentlib.tool.ToolCallAuthorizer");
    assertThat(ResourceAuthorizer.class.getName())
        .isEqualTo("io.mosire.agentlib.permission.ResourceAuthorizer");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // 旧 JAR 缺失、但 spec §10.5 明确要复用的能力
        "io.mosire.agentlib.tool.ToolCallAuthorizer", // 唯一调用入口
        "io.mosire.agentlib.tool.Digest", // 参数摘要（spec §8.1）
        "io.mosire.agentlib.permission.ResourceAuthorizer", // 地址级权限
        "io.mosire.agentlib.permission.ResourceScope",
        "io.mosire.agentlib.permission.ResourceScopeMap",
        "io.mosire.agentlib.approval.ApprovalCoordinator", // 审批流
        "io.mosire.agentlib.approval.AskKind",
        "io.mosire.agentlib.plugin.HostServices", // 插件宿主服务
        "io.mosire.agentlib.plugin.PluginToolSource",
        "io.mosire.agentlib.llm.OpenAICompatibleLlmClient", // LLM 客户端
        "io.mosire.agentlib.llm.LlmRouteLoader",
        "io.mosire.agentlib.event.SqliteEventStore", // 事件持久化
        "io.mosire.agentlib.config.FileConfigStore",
        // M5 直接消费的三个类（spec §2.2 扩钉；T7 的 MCP 服务与 T6 的审批装配）
        "io.mosire.agentlib.mcp.AgentToMcpServer", // MCP HTTP 服务（startHttp）
        "io.mosire.agentlib.approval.ApprovalHttpEndpoint", // 审批 HTTP 端点
        "io.mosire.agentlib.approval.HttpApprovalChannel", // 审批 HTTP 通道
      })
  void agentLibApiIsLoadable(String fqn) throws Exception {
    assertThat(Class.forName(fqn)).as("agentlib-mosire 应提供 %s", fqn).isNotNull();
  }

  @Test
  void agentLibJarIsNotTheStaleBuild() throws Exception {
    URL location = ToolCallAuthorizer.class.getProtectionDomain().getCodeSource().getLocation();
    Path jarPath = Paths.get(location.toURI());

    assertThat(Files.isRegularFile(jarPath)).as("应从 JAR 运行（Maven 构建）；实际位置 = %s", location).isTrue();

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      long classCount = jar.stream().filter(e -> e.getName().endsWith(".class")).count();
      assertThat(classCount)
          .as(
              "agentlib-mosire JAR (%s) 只有 %d 个类，低于期望的 %d——"
                  + "极可能是 ~/.m2 里躺着一个过时构建。"
                  + "修复：cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install",
              jarPath, classCount, MIN_EXPECTED_CLASSES)
          .isGreaterThanOrEqualTo(MIN_EXPECTED_CLASSES);
    }
  }
}
