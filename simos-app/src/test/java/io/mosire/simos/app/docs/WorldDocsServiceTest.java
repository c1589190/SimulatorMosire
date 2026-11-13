package io.mosire.simos.app.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D-036 世界文档服务：增 / 改（备份）/ 删（回收站）/ 多版本 / 多名称 / 查询。 */
class WorldDocsServiceTest {

  @TempDir Path tempDir;

  @Test
  void createAndGetDocument() {
    WorldDocsService docs = new WorldDocsService(tempDir);

    WorldDocView created =
        docs.create(
            "tree1-360/unit/army/vg-berlin",
            "柏林国民掷弹兵师",
            "# 柏林\n\n驻防 hex/1,1。",
            List.of("柏林掷弹兵师", "vg-berlin"));

    assertThat(created.meta().version()).isEqualTo(1);
    assertThat(created.meta().deleted()).isFalse();
    assertThat(created.content()).contains("驻防");
    assertThat(created.versions()).extracting(WorldDocVersion::version).containsExactly(1);

    WorldDocView loaded = docs.get("tree1-360/unit/army/vg-berlin");
    assertThat(loaded.meta().title()).isEqualTo("柏林国民掷弹兵师");
    assertThat(loaded.meta().names()).containsExactly("柏林掷弹兵师", "vg-berlin");
    assertThat(Files.isDirectory(tempDir.resolve("world/entries"))).isTrue();
  }

  @Test
  void editBacksUpOldVersionAndBumpsVersion() {
    WorldDocsService docs = new WorldDocsService(tempDir);
    docs.create("tree1-0/region/柏林", "柏林", "v1", List.of());

    WorldDocView edited = docs.edit("tree1-0/region/柏林", "柏林", "v2", null);

    assertThat(edited.meta().version()).isEqualTo(2);
    assertThat(edited.content()).isEqualTo("v2");
    assertThat(edited.versions())
        .extracting(WorldDocVersion::version, WorldDocVersion::file)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "history/v1.md"),
            org.assertj.core.groups.Tuple.tuple(2, "current.md"));
    assertThat(docs.contentAt("tree1-0/region/柏林", 1)).isEqualTo("v1");
    assertThat(docs.contentAt("tree1-0/region/柏林", 2)).isEqualTo("v2");
  }

  @Test
  void deleteMovesCurrentToRecycleAndMarksDate() {
    WorldDocsService docs = new WorldDocsService(tempDir);
    docs.create("tree1-0/hex/1,1", "格 1,1", "old", List.of());

    WorldDocMeta deleted = docs.delete("tree1-0/hex/1,1", "2026-11-13");

    assertThat(deleted.deletedAt()).isEqualTo("2026-11-13");
    assertThat(docs.get("tree1-0/hex/1,1").content()).isEmpty();
    assertThat(docs.search("hex/1,1", false)).isEmpty();
    assertThat(docs.search("hex/1,1", true)).hasSize(1);
    try (var files = Files.walk(tempDir.resolve("world/.recycle"))) {
      assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void recreateAfterDeleteContinuesVersionNumber() {
    WorldDocsService docs = new WorldDocsService(tempDir);
    docs.create("tree1-0/unit/gov/gov-central", "中央", "v1", List.of());
    docs.edit("tree1-0/unit/gov/gov-central", null, "v2", null);
    docs.delete("tree1-0/unit/gov/gov-central", "2026-11-13");

    WorldDocView recreated = docs.create("tree1-0/unit/gov/gov-central", "中央", "v3", List.of());

    assertThat(recreated.meta().version()).isEqualTo(3);
    assertThat(recreated.meta().deleted()).isFalse();
    assertThat(recreated.content()).isEqualTo("v3");
  }

  @Test
  void searchMatchesPartialAddressTitleAndName() {
    WorldDocsService docs = new WorldDocsService(tempDir);
    docs.create("tree1-360/unit/army/vg-berlin", "柏林掷弹兵师", "a", List.of("柏林部队"));
    docs.create("tree2-600/unit/gov/gov-central", "中央政府", "b", List.of("中央"));
    docs.create("tree1-360/region/勃兰登堡", "勃兰登堡", "c", List.of());

    assertThat(docs.search("tree1", false)).hasSize(2);
    assertThat(docs.search("柏林", false)).hasSize(1);
    assertThat(docs.search("中央", false)).hasSize(1);
    assertThat(docs.search("柏林部队", false)).hasSize(1);
    assertThat(docs.search("", false)).hasSize(3);
  }

  @Test
  void duplicateCreateIsRejected() {
    WorldDocsService docs = new WorldDocsService(tempDir);
    docs.create("tree1-0/hex/1,1", "格", "", List.of());

    assertThatThrownBy(() -> docs.create("tree1-0/hex/1,1", "格", "", List.of()))
        .isInstanceOf(WorldDocException.class)
        .extracting(e -> ((WorldDocException) e).code())
        .isEqualTo(WorldDocException.Code.EXISTS);
  }

  @Test
  void pathTraversalAndEmptySegmentsAreRejected() {
    WorldDocsService docs = new WorldDocsService(tempDir);

    assertThatThrownBy(() -> docs.create("../evil", "x", "", List.of()))
        .isInstanceOf(WorldDocException.class);
    assertThatThrownBy(() -> docs.create("tree1-0//hex", "x", "", List.of()))
        .isInstanceOf(WorldDocException.class);
    assertThatThrownBy(() -> docs.create("tree1-0/hex/../x", "x", "", List.of()))
        .isInstanceOf(WorldDocException.class);
  }

  @Test
  void sanitizedAddressesDoNotCollide() throws Exception {
    WorldDocsService docs = new WorldDocsService(tempDir);
    docs.create("a:b", "colon", "1", List.of());
    docs.create("a_b", "underscore", "2", List.of());

    assertThat(docs.get("a:b").content()).isEqualTo("1");
    assertThat(docs.get("a_b").content()).isEqualTo("2");
    try (var files = Files.walk(tempDir.resolve("world/entries"))) {
      assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(4); // 2 × meta + current
    }
  }
}
