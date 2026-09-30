package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M11′ 对接判据：AgentLib {@code ConfigStore} 读写 / 坏条目可见 / 密钥不落路由 / 旧格式迁移 / 删除幂等。 */
class AgentLibLlmConfigTest {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @TempDir Path tempDir;

  @Test
  void routeIsWrittenIntoAgentLibsConfigStoreAndReadBackByItsLoader() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute(
        "deepseek", "https://api.deepseek.com/v1", "deepseek-flash", "keys.deepseek", 120_000L);

    assertThat(config.availableNames()).containsExactly("deepseek");
    assertThat(config.configRoot().resolve("config.json")).exists();
    Map<String, Object> view = config.view("deepseek");
    assertThat(view.get("valid")).isEqualTo(true);
    assertThat(view.get("baseUrl")).isEqualTo("https://api.deepseek.com/v1");
    assertThat(view.get("model")).isEqualTo("deepseek-flash");
    assertThat(view.get("credentialsRef")).isEqualTo("keys.deepseek");
  }

  @Test
  void apiKeyGoesToKeysAndNeverIntoTheRouteEntry() throws Exception {
    // ★ 判据：密钥值只落 keys.<name>，llm.routes.<name> 里不得出现它（m6 的杀点）。
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    String sentinel = "sk-SENTINEL-DO-NOT-PERSIST-IN-ROUTES";
    config.upsertRoute("p1", "https://x/v1", "m", "keys.p1", 30_000L);
    config.putKey("p1", sentinel);

    String onDisk =
        Files.readString(config.configRoot().resolve("config.json"), StandardCharsets.UTF_8);
    assertThat(onDisk).contains("keys.p1");
    assertThat(onDisk).as("密钥值落在 keys 段里").contains(sentinel);
    assertThat(onDisk)
        .as("路由条目的 credentialsRef 是引用，不是值")
        .contains("\"credentialsRef\" : \"keys.p1\"");

    ConfigStore store = config.configStore();
    String routeJson = MAPPER.writeValueAsString(store.get("llm", "routes.p1").orElseThrow());
    assertThat(routeJson).as("路由条目里绝不得出现密钥值").doesNotContain(sentinel);
    assertThat(config.view("p1").toString()).as("视图里绝不得出现密钥值").doesNotContain(sentinel);
  }

  @Test
  void brokenRouteIsStillVisibleWithItsErrorCode() throws Exception {
    // ★ 判据：枚举给全（只列名字、不校验内容）；坏条目带 errorCode 原样上报（m7 的杀点）。
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("good", "https://x/v1", "m", "", 30_000L);
    // 手写一条缺 model 的坏路由（put 的 schema 会拒 ⇒ 直接落盘绕过写侧，模拟"用户手改坏了"）。
    Files.writeString(
        config.configRoot().resolve("config.json"),
        "{\"llm\":{\"routes\":{\"broken\":{\"baseUrl\":\"https://y/v1\"}}}}",
        StandardCharsets.UTF_8);

    AgentLibLlmConfig reopened = AgentLibLlmConfig.open(tempDir);
    assertThat(reopened.availableNames()).containsExactly("broken");
    Map<String, Object> view = reopened.view("broken");
    assertThat(view.get("valid")).isEqualTo(false);
    assertThat(view.get("errorCode")).isEqualTo("E_LLM_CONFIG_MISSING");
  }

  @Test
  void removeRouteIsIdempotentAndRemovesTheEntry() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("p1", "https://x/v1", "m", "", 30_000L);
    config.removeRoute("p1");
    assertThat(config.availableNames()).isEmpty();
    config.removeRoute("p1"); // 幂等：不抛
    assertThat(config.availableNames()).isEmpty();
  }

  @Test
  void upsertRejectsBlankRequiredFieldsAndNonPositiveTimeout() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    assertThatThrownBy(() -> config.upsertRoute("p1", " ", "m", "", 30_000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
    assertThatThrownBy(() -> config.upsertRoute("p1", "https://x/v1", "", "", 30_000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
    assertThatThrownBy(() -> config.upsertRoute("p1", "https://x/v1", "m", "", 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readTimeoutMs");
  }

  @Test
  void legacyLiteralKeyMigratesIntoKeysAndRoute() {
    // 旧格式：{providers:[{id,baseUrl,model,apiKey}]} ⇒ llm.routes.<id> + keys.<id>，源文件保留。
    Path legacy = tempDir.resolve(AgentLibLlmConfig.LEGACY_FILE_NAME);
    write(
        legacy,
        "{\"version\":1,\"providers\":[{\"id\":\"old\",\"baseUrl\":\"https://old/v1\",\"model\":\"m\",\"apiKey\":\"sk-OLD\"}]}");

    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    assertThat(config.availableNames()).containsExactly("old");
    assertThat(config.configStore().get("keys", "old").orElseThrow().asText()).isEqualTo("sk-OLD");
    assertThat(config.view("old").get("credentialsRef")).isEqualTo("keys.old");
    assertThat(legacy).as("迁移来源文件必须保留").exists();
  }

  @Test
  void legacyEchoReasoningContentMigratesIntoAgentLibCapability() {
    // 旧格式顶层位（config/llm-providers.json 的形态）⇒ AgentLib capabilities.echoReasoningContent。
    Path legacy = tempDir.resolve(AgentLibLlmConfig.LEGACY_FILE_NAME);
    write(
        legacy,
        "{\"providers\":[{\"id\":\"ds\",\"baseUrl\":\"https://ds/v1\",\"model\":\"deepseek-flash\","
            + "\"echoReasoningContent\":true}]}");

    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);

    assertThat(LlmRouteLoader.load(config.configStore(), "ds").transport().echoReasoningContent())
        .as("旧配置位必须进入发送侧权威的 LlmTransport")
        .isTrue();
    assertThat(LlmRouteLoader.capabilities(config.configStore(), "ds").echoReasoningContent())
        .as("掩码视图读到的能力描述也必须为 true")
        .isTrue();
    assertThat(config.view("ds").get("capabilities").toString())
        .as("simos.llm.providers 掩码视图必须能显示该能力位")
        .contains("echoReasoningContent=true");
  }

  @Test
  void upsertRouteWithoutEchoArgPreservesExistingEchoBit() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("ds", "https://ds/v1", "deepseek-flash", "keys.ds", 30_000L, true);

    // 配置页的常规保存路径（五参重载）不管理能力位，但绝不能把它顺手抹掉。
    config.upsertRoute("ds", "https://ds/v1", "deepseek-flash", "keys.ds", 30_000L);

    assertThat(LlmRouteLoader.load(config.configStore(), "ds").transport().echoReasoningContent())
        .as("保存路由不得把思考模式回传位重置为 false")
        .isTrue();
  }

  @Test
  void repoDefaultConfigSeedsAnEmptyStoreButLosesToTheStoreOverride() {
    // ★ 判据：仓库默认配置（config/llm-providers.json 的形态）只作**兜底种子**——store 空才迁；
    //   store 有自己的 llm-providers.json 时，仓库那份不得覆盖它（第一个能迁出条目的源即止）。
    Path repoDefault = tempDir.resolve("repo-default.json");
    write(
        repoDefault,
        "{\"providers\":[{\"id\":\"repo\",\"baseUrl\":\"https://repo/v1\",\"model\":\"m\",\"apiKey\":\"sk-REPO\"}]}");

    AgentLibLlmConfig seeded = AgentLibLlmConfig.open(tempDir, repoDefault);
    assertThat(seeded.availableNames()).containsExactly("repo");
    assertThat(seeded.configStore().get("keys", "repo").orElseThrow().asText())
        .isEqualTo("sk-REPO");

    Path other = tempDir.resolve("store-seed");
    createDir(other);
    Path storeLegacy = other.resolve(AgentLibLlmConfig.LEGACY_FILE_NAME);
    write(
        storeLegacy,
        "{\"providers\":[{\"id\":\"store\",\"baseUrl\":\"https://store/v1\",\"model\":\"m\"}]}");
    AgentLibLlmConfig storeWins = AgentLibLlmConfig.open(other, repoDefault);
    assertThat(storeWins.availableNames()).as("store 覆盖优先于仓库默认").containsExactly("store");
  }

  @Test
  void migrationDoesNotOverwriteExistingAgentLibConfig() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("current", "https://current/v1", "m", "", 30_000L);
    Path legacy = tempDir.resolve(AgentLibLlmConfig.LEGACY_FILE_NAME);
    write(
        legacy,
        "{\"providers\":[{\"id\":\"old\",\"baseUrl\":\"https://old/v1\",\"model\":\"m\"}]}");

    AgentLibLlmConfig reopened = AgentLibLlmConfig.open(tempDir);
    assertThat(reopened.availableNames()).as("目标非空 ⇒ 不迁移（不覆盖用户已改的配置）").containsExactly("current");
  }

  @Test
  void clientAssemblesAnAgentLibLlmClientForAConfiguredRoute() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("p1", "http://127.0.0.1:1/v1", "m", "", 1_000L);
    LlmClient client = config.client("p1");
    assertThat(client.model()).isEqualTo("m");
  }

  @Test
  void keyNameOfOnlyAcceptsKeysPrefix() {
    assertThat(AgentLibLlmConfig.keyNameOf("keys.deepseek")).contains("deepseek");
    assertThat(AgentLibLlmConfig.keyNameOf("MY_ENV_VAR")).isEmpty();
    assertThat(AgentLibLlmConfig.keyNameOf("keys.")).isEmpty();
    assertThat(AgentLibLlmConfig.keyNameOf("")).isEmpty();
    assertThat(AgentLibLlmConfig.keyNameOf(null)).isEmpty();
  }

  @Test
  void viewsAreSortedByNameSoTwoCallsAreByteIdentical() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("zebra", "https://z/v1", "m", "", 30_000L);
    config.upsertRoute("alpha", "https://a/v1", "m", "", 30_000L);
    assertThat(config.availableNames()).containsExactly("alpha", "zebra");
    assertThat(toJson(config.views())).isEqualTo(toJson(config.views()));
  }

  private static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(Path path, String content) {
    try {
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void createDir(Path path) {
    try {
      Files.createDirectories(path);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
