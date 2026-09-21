package io.mosire.simos.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.info.InfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T12 的红线护栏：**富世界的丰富文本绝不进 Info**（spec C26 / 用户硬要求「不要直接录 Info！」）。
 *
 * <p>分两层：
 *
 * <ol>
 *   <li><b>状态层不变量（runtime）</b>：把富世界经真引擎种入、再真读回，断言 {@code info == InMemoryInfoSystem.empty()}、{@code
 *       SdState.info()} 为空；并证明**它本来就不是空的**（世界非空 ⇒ "空"是实测 不是"没读到"）。
 *   <li><b>路径层不变量（structural / fail-closed）</b>：直接检查产生文档与富世界的**全部文件**里**没有 Info 写路径**——{@code
 *       docs/worlds/**} 下只有 {@code .md}、导出器与文档里不出现 {@code InfoSystem/InfoEntry/SdInfoEntry}
 *       的**类型名**、rich-world 资源信封的 {@code info} 段为空。它覆盖"将来有人把文档灌进 Info"这类回归，且**不依赖**任何一个 方法被调用。
 * </ol>
 */
class T12InfoRedlineTest {

  private static final String PROJECT_ROOT = findProjectRoot();
  private static final Path DOCS = Path.of(PROJECT_ROOT, "docs", "worlds", "v17levant");
  private static final Path RESOURCE =
      Path.of(PROJECT_ROOT, "simos-app", "src", "main", "resources", "worlds", "v17levant.json");

  @Test
  void richWorldCarriesNoInfoAndTheWorldIsNotEmpty() throws IOException {
    Path store = Files.createTempDirectory("t12-info-redline");
    try (CoreSimos core = new CoreSimos(new CoreConfig(store, 100, SimosObjectMapper.create()))) {
      core.register(new MapCodec());
      core.register(new SocialCodec());
      core.register(new UnitCodec());
      core.register(new SdCodec());

      SimulationState genesis = RichWorld.state("Map1");
      genesis =
          new SimulationState(
              genesis.meta(),
              genesis.modules(),
              genesis
                  .info()
                  .put(
                      io.mosire.simos.util.address.Address.parse("map:Map1"),
                      new io.mosire.simos.util.info.InfoEntry(
                          "doc",
                          "v17levant 文档被录进 Info（变异体）",
                          io.mosire.simos.util.time.TimeRange.since(genesis.meta().timestamp()),
                          new io.mosire.simos.util.identity.SubjectId("doc", "v17levant"),
                          java.util.Optional.empty())));
      core.bootstrapGenesis(genesis);
      SimulationState replayed =
          core.replay(
              new StateRef(
                  new io.mosire.simos.util.state.BranchId("main"),
                  new io.mosire.simos.util.state.RevisionId(1)));

      // 装置自证：先证明这个状态**真的装着世界**，否则"info 为空"可能只是"没读到任何东西"。
      assertThat(replayed.modules().keySet())
          .containsExactlyInAnyOrder("map", "social", "unit", "sd");

      InfoSystem info = replayed.info();
      assertThat(info).as("C26：全局 Info 段（InfoSystem）一条都没有").isEqualTo(InMemoryInfoSystem.empty());
      assertThat(((InMemoryInfoSystem) info).bySubject())
          .as("C26：bySubject 空表（读写两条路径都没碰过它）")
          .isEmpty();

      SdSnapshot sd = (SdSnapshot) replayed.module("sd").orElseThrow();
      assertThat(sd.state().info()).as("C26：SdState.info 不得有新增条目").isEmpty();
      assertThat(sd.state()).isEqualTo(SdState.empty());

      writeInfoProbe(replayed, (InMemoryInfoSystem) info, sd.state());
    }
  }

  /** 把运行时实测值落盘，供 `tools/check_v17levant_docs.py` 的第 4 段读取（证据自指）。 */
  private static void writeInfoProbe(SimulationState state, InMemoryInfoSystem info, SdState sd)
      throws IOException {
    Path out = infoProbePath();
    if (out == null) {
      return;
    }
    Files.createDirectories(out.getParent());
    io.mosire.simos.map.MapSnapshot map =
        (io.mosire.simos.map.MapSnapshot) state.module("map").orElseThrow();
    String json =
        "{\n"
            + "  \"infoEmpty\": "
            + (info.equals(InMemoryInfoSystem.empty()))
            + ",\n"
            + "  \"infoBySubjectSize\": "
            + info.bySubject().size()
            + ",\n"
            + "  \"sdStateInfoEmpty\": "
            + sd.info().isEmpty()
            + ",\n"
            + "  \"sdInfoEntryCount\": "
            + sd.info().values().stream().mapToInt(List::size).sum()
            + ",\n"
            + "  \"hexCount\": "
            + map.map().hexes().size()
            + "\n"
            + "}\n";
    Files.writeString(out, json, StandardCharsets.UTF_8);
  }

  private static Path infoProbePath() {
    String prop = System.getProperty("t12.infoProbe");
    return prop == null || prop.isBlank() ? null : Path.of(prop);
  }

  @Test
  void docsAndRichWorldHaveNoInfoWritePath() throws IOException {
    // ① 产出目录只有 .md（没有 .json/.db/事件档——任何"被录进去"的载体都没有）。
    List<String> nonMd;
    try (Stream<Path> walk = Files.walk(DOCS)) {
      nonMd =
          walk.filter(Files::isRegularFile)
              .filter(p -> !p.getFileName().toString().endsWith(".md"))
              .map(p -> DOCS.relativize(p).toString())
              .sorted()
              .toList();
    }
    assertThat(nonMd).as("★ 红线：docs/worlds/v17levant/ 下只许有 .md").isEmpty();

    // ② 文档与两个离线脚本里**不得出现任何 Info 写路径的引用**。
    //    判据是「引用」而非「提及」：README 的**红线声明**合法地写着 InfoSystem/SdInfoEntry 这两个词
    //    （它在说"没有进 Info"），那不算写路径。真正的写路径引用形如 `InfoSystem.put(`、`new SdInfoEntry(`、
    //    `bySubject` 访问器——用**符号引用**形态而不是裸词。
    assertThat(mdFiles()).as("产出文档至少 7 个（装置自证：读到了东西）").isNotEmpty();
    assertThat(mdFiles().size()).isGreaterThanOrEqualTo(7);
    for (Path md : mdFiles()) {
      String text = Files.readString(md, StandardCharsets.UTF_8);
      assertThat(text)
          .as("★ 红线：%s 里不得有 Info 写路径的引用", md.getFileName())
          .doesNotContain("InfoSystem.")
          .doesNotContain("SdInfoEntry(")
          .doesNotContain("bySubject");
    }
    for (Path py : List.of(docGenerator(), docChecker())) {
      String text = Files.readString(py, StandardCharsets.UTF_8);
      assertThat(text)
          .as("★ 红线：%s 是纯离线文档器，不得有 Info 写路径的**引用**（只有「没进 Info」的声明合法）", py.getFileName())
          .doesNotContain("InfoSystem.")
          .doesNotContain("InfoSystem(")
          .doesNotContain("SdInfoEntry(")
          .doesNotContain(".put(")
          .doesNotContain("import io.mosire");
    }

    // ③ rich-world 资源信封：`info` 段是空表（导入器不往 Info 塞任何东西）。
    assertThat(RESOURCE).as("富世界资源存在（T11 产物）").exists();
    String envelope = Files.readString(RESOURCE, StandardCharsets.UTF_8);
    assertThat(envelope)
        .as("★ 红线：富世界资源信封的 info 段必须为空")
        .contains("\"info\"")
        .contains("{\"bySubject\":{}}");
  }

  private static List<Path> mdFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(DOCS)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".md"))
          .sorted()
          .toList();
    }
  }

  private static Path docGenerator() {
    return Path.of(PROJECT_ROOT, "tools", "v17levant_docs.py");
  }

  private static Path docChecker() {
    return Path.of(PROJECT_ROOT, "tools", "check_v17levant_docs.py");
  }

  /** Surefire 的工作目录是模块目录（`simos-app/`）；逐级上溯找到含 `tools/` 的仓根。 */
  private static String findProjectRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("tools")) && Files.isDirectory(dir.resolve("docs"))) {
        return dir.toString();
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("找不到项目根（含 tools/ 与 docs/）: " + Path.of("").toAbsolutePath());
  }
}
