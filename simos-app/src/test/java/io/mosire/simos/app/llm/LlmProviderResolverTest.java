package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.ViewScope;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M11 判据 C8/C9：★ 未绑定 / 悬空 provider **明确报错，绝不静默兜底**。 */
class LlmProviderResolverTest {

  @TempDir java.nio.file.Path tempDir;

  @Test
  void unboundDecisionMakerIsAnExplicitErrorNotASilentDefault() {
    LlmProviderRegistry registry = LlmProviderRegistry.load(tempDir, Map.of());
    registry.upsert(
        new LlmProvider("p-default", "http://x", "m", SecretRef.env("K"), Duration.ofSeconds(1)));
    LlmProviderResolver resolver = new LlmProviderResolver(registry);

    DecisionMaker unbound = maker(Optional.empty());
    assertThatThrownBy(() -> resolver.adjudicatorFor(unbound))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未绑定");
  }

  @Test
  void danglingProviderIdNamesTheIdAndNeverFallsBackToAnExistingProvider() {
    LlmProviderRegistry registry = LlmProviderRegistry.load(tempDir, Map.of());
    registry.upsert(
        new LlmProvider("p-other", "http://x", "m", SecretRef.env("K"), Duration.ofSeconds(1)));
    LlmProviderResolver resolver = new LlmProviderResolver(registry);

    DecisionMaker dangling = maker(Optional.of("p-gone"));
    assertThatThrownBy(() -> resolver.llmClientFor("p-gone"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("p-gone")
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> resolver.adjudicatorFor(dangling))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("p-gone");
  }

  @Test
  void missingSecretIsAnExplicitErrorNamingOnlyTheReference() {
    LlmProviderRegistry registry = LlmProviderRegistry.load(tempDir, Map.of());
    registry.upsert(
        new LlmProvider(
            "p-1",
            "http://127.0.0.1:1",
            "m",
            SecretRef.env("SIMOS_ABSENT_KEY"),
            Duration.ofSeconds(1)));
    LlmProviderResolver resolver = new LlmProviderResolver(registry);

    assertThatThrownBy(() -> resolver.llmClientFor("p-1").complete(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SIMOS_ABSENT_KEY");
  }

  private static io.mosire.simos.sd.adjudication.LlmRequest request() {
    return new io.mosire.simos.sd.adjudication.LlmRequest(
        io.mosire.simos.sd.adjudication.Breakpoints.D1, "sys", "user");
  }

  private static DecisionMaker maker(Optional<String> providerId) {
    return new DecisionMaker(
        new DecisionMakerId("dm-1"),
        new Affiliation.Nation(new NationId("n1")),
        Set.of(),
        ViewScope.empty(),
        1,
        providerId);
  }
}
