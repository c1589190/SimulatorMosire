package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M11 判据 C1~C4：注册表读写 / 密钥值不入库、不入视图 / ENV 与 FILE 引用解析。 */
class LlmProviderRegistryTest {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  /** 密钥值哨兵：只在内存/env/文件里出现，绝不被序列化。 */
  private static final String SENTINEL = "SENTINEL-super-secret-plaintext-42";

  @TempDir Path tempDir;

  @Test
  void upsertWritesConfigAndReloadsByIdListInLexicographicOrder() throws Exception {
    LlmProviderRegistry registry = LlmProviderRegistry.load(tempDir, Map.of());
    registry.upsert(provider("p-z", SecretRef.env("NOPE")));
    registry.upsert(provider("p-a", SecretRef.env("NOPE")));

    assertThat(registry.list()).extracting(LlmProvider::id).containsExactly("p-a", "p-z");
    assertThat(registry.file()).isEqualTo(tempDir.resolve(LlmProviderRegistry.FILE_NAME));
    assertThat(Files.exists(registry.file())).isTrue();

    LlmProviderRegistry reloaded = LlmProviderRegistry.load(tempDir, Map.of());
    assertThat(reloaded.list()).extracting(LlmProvider::id).containsExactly("p-a", "p-z");
    assertThat(reloaded.find("p-a"))
        .get()
        .extracting(LlmProvider::baseUrl)
        .isEqualTo("http://localhost:9999");
  }

  @Test
  void deleteRemovesTheRowAndPersists() {
    LlmProviderRegistry registry = LlmProviderRegistry.load(tempDir, Map.of());
    registry.upsert(provider("p-a", SecretRef.env("NOPE")));
    assertThat(registry.delete("p-a")).isTrue();
    assertThat(registry.delete("p-a")).isFalse();
    assertThat(LlmProviderRegistry.load(tempDir, Map.of()).list()).isEmpty();
  }

  @Test
  void secretValueNeverLandsInTheConfigFileOrInTheViews() throws Exception {
    // ★ 判据 C2（自证）：env 引用下，哨兵值只在解析时出现，文件字节 / 掩码视图都不含它。
    LlmProviderRegistry registry =
        LlmProviderRegistry.load(tempDir, Map.of("SIMOS_TEST_KEY", SENTINEL));
    registry.upsert(provider("p-1", SecretRef.env("SIMOS_TEST_KEY")));

    assertThat(Files.readString(registry.file())).doesNotContain(SENTINEL);
    String viewsJson = JSON.writeValueAsString(registry.views());
    assertThat(viewsJson).doesNotContain(SENTINEL);
    assertThat(viewsJson).contains("SIMOS_TEST_KEY");
    assertThat(registry.view(registry.find("p-1").orElseThrow()).get("secretResolvable"))
        .isEqualTo(true);
  }

  @Test
  void resolveSecretReadsEnvAndFileRefs() throws Exception {
    Path secretFile = tempDir.resolve("key.txt");
    Files.writeString(secretFile, "  file-secret-value\n");
    LlmProviderRegistry registry =
        LlmProviderRegistry.load(
            tempDir, Map.of("SIMOS_ENV_KEY", "env-secret-value", "SIMOS_BLANK", "   "));

    assertThat(registry.resolveSecret(SecretRef.env("SIMOS_ENV_KEY"))).contains("env-secret-value");
    assertThat(registry.resolveSecret(SecretRef.file(secretFile.toString())))
        .contains("file-secret-value");
    assertThat(registry.resolveSecret(SecretRef.env("SIMOS_MISSING"))).isEmpty();
    assertThat(registry.resolveSecret(SecretRef.env("SIMOS_BLANK"))).isEmpty();
    assertThat(registry.resolveSecret(SecretRef.file(tempDir.resolve("nope.txt").toString())))
        .isEmpty();
  }

  @Test
  void secretResolvableIsFalseWhenTheReferenceIsDangling() {
    LlmProviderRegistry registry = LlmProviderRegistry.load(tempDir, Map.of());
    LlmProvider dangling = provider("p-1", SecretRef.env("SIMOS_NOT_SET"));
    registry.upsert(dangling);
    Map<String, Object> view = registry.view(dangling);
    assertThat(view.get("secretResolvable")).isEqualTo(false);
    assertThat(view).containsKey("apiKeyRef");
  }

  private static LlmProvider provider(String id, SecretRef ref) {
    return new LlmProvider(id, "http://localhost:9999", "gpt-test", ref, Duration.ofSeconds(5));
  }
}
