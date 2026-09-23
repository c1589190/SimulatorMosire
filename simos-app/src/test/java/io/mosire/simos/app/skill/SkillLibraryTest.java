package io.mosire.simos.app.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **Skill 库的两条要害**（2026-09-23）：解析口径（头可选、坏了不生效）与**热更新**（改文件不重启即生效）。
 *
 * <p>★ 这两条都是"没有它用户就白改"的性质：写坏了不该让整库失效（保留上一版 + 日志），改了不该要重启（按 mtime 重读）。
 */
class SkillLibraryTest {

  @TempDir Path tempDir;

  // ── 解析口径（纯函数）────────────────────────────────────────────────────────

  @Test
  void aHeaderSuppliesIdTitleAndVersion() {
    Skill skill =
        SkillLibrary.parse(
            "file-name",
            Path.of("x.md"),
            "---\n{\"id\":\"decision-basics\",\"title\":\"决策总则\",\"version\":3}\n---\n# 正文标题\n\n内容\n",
            7L);

    assertThat(skill.id()).isEqualTo("decision-basics");
    assertThat(skill.title()).isEqualTo("决策总则");
    assertThat(skill.version()).isEqualTo(3);
    assertThat(skill.body()).as("正文含标题行（不剥——模型读到的与文件里写的一致）").contains("# 正文标题");
    assertThat(skill.body()).doesNotContain("{");
    assertThat(skill.modifiedMillis()).isEqualTo(7L);
  }

  @Test
  void withoutAHeaderTheFileNameAndFirstHeadingAreUsed() {
    Skill skill = SkillLibrary.parse("plain", Path.of("plain.md"), "# 我的一级标题\n\n正文\n", 1L);

    assertThat(skill.id()).isEqualTo("plain");
    assertThat(skill.title()).isEqualTo("我的一级标题");
    assertThat(skill.version()).as("缺省版本 = 1").isEqualTo(1);
  }

  @Test
  void aHeaderWithoutIdOrTitleFallsBackToTheFileNameAndHeading() {
    Skill skill = SkillLibrary.parse("fallback", Path.of("f.md"), "---\n{}\n---\n# 标题\n", 1L);
    assertThat(skill.id()).isEqualTo("fallback");
    assertThat(skill.title()).isEqualTo("标题");
  }

  @Test
  void aBrokenHeaderIsRejectedSoTheCallerCanKeepThePreviousVersion() {
    // ★ 围栏不闭合 / 头不是合法 JSON ⇒ 抛（调用方据此"保留上一版 + 记日志"，而不是让整库失效）。
    assertThatThrownBy(
            () -> SkillLibrary.parse("bad", Path.of("b.md"), "---\n{\"id\":\"x\"}\n# 没有闭合\n", 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("围栏");

    assertThatThrownBy(
            () -> SkillLibrary.parse("bad", Path.of("b.md"), "---\n{不是 JSON}\n---\n正文\n", 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON");
  }

  // ── 两个来源与覆盖优先级 ────────────────────────────────────────────────────

  @Test
  void theStoreVersionWinsOverTheSeedForTheSameId() throws Exception {
    Path seed = Files.createDirectories(tempDir.resolve("seed"));
    Path store = Files.createDirectories(tempDir.resolve("store"));
    write(seed.resolve("same.md"), "---\n{\"id\":\"same\"}\n---\n# 种子版\n", 1000L);
    write(store.resolve("same.md"), "---\n{\"id\":\"same\"}\n---\n# store 版\n", 1000L);
    write(seed.resolve("only-seed.md"), "# 只在种子里\n", 1000L);

    SkillLibrary library = SkillLibrary.open(seed, store);

    assertThat(library.find("same")).get().extracting(Skill::title).isEqualTo("store 版");
    assertThat(library.find("only-seed")).as("种子里独有的照样在").isPresent();
    assertThat(ids(library)).as("目录按 id 升序、不重复").containsExactly("only-seed", "same");
  }

  @Test
  void aMissingDirectoryYieldsAnEmptyLibraryRatherThanAFailure() {
    SkillLibrary library = SkillLibrary.open(tempDir.resolve("nope"), tempDir.resolve("also-nope"));

    assertThat(library.list()).isEmpty();
    assertThat(library.find("anything")).isEmpty();
  }

  // ── 热更新与"坏文件保留上一版" ────────────────────────────────────────────────

  @Test
  void editingAFileTakesEffectWithoutRestarting() throws Exception {
    Path store = Files.createDirectories(tempDir.resolve("store"));
    Path file = store.resolve("live.md");
    write(file, "# 第一版\n", 1000L);
    SkillLibrary library = SkillLibrary.open(tempDir.resolve("seed"), store);
    assertThat(library.find("live")).get().extracting(Skill::title).isEqualTo("第一版");

    write(file, "# 第二版\n", 2000L);

    assertThat(library.find("live"))
        .as("★ 改文件即生效（按 mtime 重读）——不必重启")
        .get()
        .extracting(Skill::title)
        .isEqualTo("第二版");
  }

  @Test
  void aFileWrittenBrokenKeepsThePreviousVersion() throws Exception {
    Path store = Files.createDirectories(tempDir.resolve("store"));
    Path file = store.resolve("live.md");
    write(file, "# 好的一版\n", 1000L);
    SkillLibrary library = SkillLibrary.open(tempDir.resolve("seed"), store);
    assertThat(library.find("live")).isPresent();

    // 并发写/写坏：头没有闭合围栏 ⇒ 那一条不生效，**保留上一版**（不是整库失效、也不是静默变空）。
    write(file, "---\n{\"id\":\"live\"}\n没有闭合\n", 2000L);

    assertThat(library.find("live"))
        .as("★ 坏文件保留上一版")
        .get()
        .extracting(Skill::title)
        .isEqualTo("好的一版");
    assertThat(ids(library)).as("目录里仍只有那一条").containsExactly("live");
  }

  @Test
  void aBrokenFileThatWasNeverGoodSimplyDoesNotAppear() throws Exception {
    Path store = Files.createDirectories(tempDir.resolve("store"));
    write(store.resolve("broken.md"), "---\n{坏了}\n---\n", 1000L);
    write(store.resolve("fine.md"), "# 好的\n", 1000L);

    SkillLibrary library = SkillLibrary.open(tempDir.resolve("seed"), store);

    assertThat(ids(library)).as("从来没读成功过的那一条不进目录（但不影响别的）").containsExactly("fine");
  }

  // ── 助手 ───────────────────────────────────────────────────────────────────

  private static void write(Path file, String text, long modifiedMillis) throws Exception {
    Files.writeString(file, text, StandardCharsets.UTF_8);
    Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
  }

  private static List<String> ids(SkillLibrary library) {
    return library.list().stream().map(SkillSummary::id).toList();
  }
}
