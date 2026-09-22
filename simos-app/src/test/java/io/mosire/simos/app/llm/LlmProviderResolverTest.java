package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 判据 C8/C9：解析 fail-closed——未绑定 / 悬空 / 未配置都**不静默兜底**（m1/m2 的杀点）。 */
class LlmProviderResolverTest {

  @TempDir Path tempDir;

  @Test
  void unboundDecisionMakerIsRejectedNotSilentlyDefaulted() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("p1", "http://127.0.0.1:1/v1", "m", "", 1_000L);
    LlmProviderResolver resolver = new LlmProviderResolver(config, tempDir);

    // ★ m2 的杀点：未绑定 ⇒ 抛「未绑定」，**绝不**落到 p1。
    assertThatThrownBy(() -> resolver.llmClientFor(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(LlmProviderResolver.E_UNBOUND);
    assertThatThrownBy(() -> resolver.llmClientFor("  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(LlmProviderResolver.E_UNBOUND);
  }

  @Test
  void danglingProviderIdIsRejectedWithTheIdNamed() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("p1", "http://127.0.0.1:1/v1", "m", "", 1_000L);
    LlmProviderResolver resolver = new LlmProviderResolver(config, tempDir);

    // ★ m1 的杀点：查无 ⇒ 抛**点名该 id** 的「不存在」，**绝不**回落 p1。
    assertThatThrownBy(() -> resolver.llmClientFor("ghost"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(LlmProviderResolver.E_NOT_FOUND)
        .hasMessageContaining("ghost")
        .hasMessageContaining("绝不回退");
  }

  @Test
  void configuredProviderResolvesToAnAgentLibClient() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("p1", "http://127.0.0.1:1/v1", "deepseek-flash", "", 1_000L);
    LlmProviderResolver resolver = new LlmProviderResolver(config, tempDir);

    assertThat(resolver.agentLibClientFor("p1").model()).isEqualTo("deepseek-flash");
    assertThat(resolver.modelNameOf("p1")).isEqualTo("deepseek-flash");
  }

  @Test
  void brokenRouteSurfacesAgentLibConfigExceptionInsteadOfFallingBack() {
    // 路由在场但坏掉 ⇒ AgentLib 的错误原样冒泡（不兜底、不换路由）。
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("bad", "http://127.0.0.1:1/v1", "m", "", 1_000L);
    try {
      java.nio.file.Files.writeString(
          config.configRoot().resolve("config.json"),
          "{\"llm\":{\"routes\":{\"bad\":{\"baseUrl\":\"https://y/v1\"}}}}",
          java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    LlmProviderResolver resolver = new LlmProviderResolver(config, tempDir);
    assertThatThrownBy(() -> resolver.agentLibClientFor("bad"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("E_LLM_CONFIG_MISSING");
  }

  @Test
  void adjudicatorForUsesTheMakersBoundProvider() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("p1", "http://127.0.0.1:1/v1", "m", "", 1_000L);
    LlmProviderResolver resolver = new LlmProviderResolver(config, tempDir);

    DecisionMaker bound = maker(Optional.of("p1"));
    assertThat(resolver.adjudicatorFor(bound).name()).isEqualTo("llm");

    DecisionMaker unbound = maker(Optional.empty());
    assertThatThrownBy(() -> resolver.adjudicatorFor(unbound))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(LlmProviderResolver.E_UNBOUND);
  }

  private static DecisionMaker maker(Optional<String> providerId) {
    return new DecisionMaker(
        new DecisionMakerId("dm1"),
        new Affiliation.Nation(new io.mosire.simos.sd.id.NationId("n1")),
        Set.of(),
        AccessLimit.empty(),
        1L,
        providerId,
        0L);
  }
}
