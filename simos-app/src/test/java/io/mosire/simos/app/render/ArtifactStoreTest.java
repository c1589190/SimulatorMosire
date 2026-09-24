package io.mosire.simos.app.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ToolAsset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 工件库：内容寻址（同字节同 id、只存一份）、可解析、**拒绝畸形 id**（assetId 会从模型输出/会话历史回灌，是不可信输入）。 */
class ArtifactStoreTest {

  @TempDir Path tempDir;

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

  @Test
  void storesByContentHashSoTheSameBytesShareOneId() {
    ArtifactStore store = new ArtifactStore(tempDir.resolve("artifacts"));

    String first = store.putPng(PNG);
    String second = store.putPng(PNG.clone());

    assertThat(first).hasSize(64).isEqualTo(second);
    Optional<Path> file = store.pathOf(first);
    assertThat(file).isPresent();
    assertThat(file.get().getFileName().toString()).isEqualTo(first + ".png");
    // 只存一份：目录里恰好一个文件
    assertThat(store.directory().toFile().listFiles()).hasSize(1);
  }

  @Test
  void resolvesTheExactBytesAndMediaType() {
    ArtifactStore store = new ArtifactStore(tempDir);

    String id = store.putPng(PNG);
    ToolAsset asset = store.resolve(id).orElseThrow();

    assertThat(asset.mediaType()).isEqualTo(ArtifactStore.PNG_MEDIA_TYPE);
    assertThat(asset.bytes()).isEqualTo(PNG);
  }

  /**
   * 畸形 id（路径穿越、大小写、短串、非十六进制）一律当作"不存在"。
   *
   * <p>判别性：去掉 {@code ID} 形态校验后 {@code ../} 之类会拼出目录外的路径——本用例先把这一族挡在门外。
   */
  @Test
  void rejectsMalformedIdsInsteadOfTouchingTheFilesystem() throws IOException {
    ArtifactStore store = new ArtifactStore(tempDir.resolve("artifacts"));
    Files.createDirectories(store.directory());
    Files.writeString(tempDir.resolve("secret.png"), "不该被读到");

    assertThat(store.resolve("../secret")).isEmpty();
    assertThat(store.resolve("A".repeat(64))).isEmpty();
    assertThat(store.resolve("abc")).isEmpty();
    assertThat(store.resolve("")).isEmpty();
    assertThat(store.resolve(null)).isEmpty();
    assertThat(store.pathOf("../../etc/passwd")).isEmpty();
  }

  @Test
  void emptyBytesAreRefusedAtWriteTime() {
    ArtifactStore store = new ArtifactStore(tempDir);
    assertThatThrownBy(() -> store.putPng(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
  }
}
