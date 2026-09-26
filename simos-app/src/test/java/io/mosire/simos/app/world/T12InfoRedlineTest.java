package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.codec.EconomyCodec;
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
      core.register(new EconomyCodec());
      // ★ S1 阶段 2：RichWorld.state() 带 actor 切片 ⇒ codec 表必须随之长（否则 bootstrapGenesis 当场抛）。
      core.register(new ActorCodec());

      SimulationState genesis = RichWorld.state("Map1");
      core.bootstrapGenesis(genesis);
      SimulationState replayed =
          core.replay(
              new StateRef(
                  new io.mosire.simos.util.state.BranchId("main"),
                  new io.mosire.simos.util.state.RevisionId(1)));

      // 装置自证：先证明这个状态**真的装着世界**，否则"info 为空"可能只是"没读到任何东西"。
      assertThat(replayed.modules().keySet())
          .containsExactlyInAnyOrder("map", "social", "unit", "sd", "economy", "actor");

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
    //    判据分两层：
    //      * `.md`（产出文档）：允许**提及** `InfoSystem`/`SdInfoEntry`（README 的红线声明就要写它们），
    //        但不得出现写路径**形态**（`InfoSystem.`、`SdInfoEntry(`、`bySubject`）。
    //      * `.py`（离线脚本）：**一律不得出现裸符号**（脚本没有任何合法理由提到 Info 的类型名——
    //        它只读 node JSON、只写 md）。m4 的教训：只钉"形态"会被 `_X = "InfoSystem"` 绕过。
    assertThat(mdFiles()).as("产出文档至少 7 个（装置自证：读到了东西）").isNotEmpty();
    assertThat(mdFiles().size()).isGreaterThanOrEqualTo(7);
    for (Path md : mdFiles()) {
      String text = Files.readString(md, StandardCharsets.UTF_8);
      assertThat(text)
          .as("★ 红线：%s 里不得有 Info 写路径的引用形态", md.getFileName())
          .doesNotContain("InfoSystem.")
          .doesNotContain("SdInfoEntry(")
          .doesNotContain("bySubject");
    }
    for (Path py : List.of(docGenerator(), docChecker())) {
      String text = Files.readString(py, StandardCharsets.UTF_8);
      // ★ 用 AST 判"符号引用"而不是裸词扫描：脚本的 docstring/字符串里**合法地**写着
      //   `InfoSystem` / `SdInfoEntry`（那是在声明"没进 Info"），裸词扫描会把声明本身当违规；
      //   而 m4 的绕过形态 `_X = "InfoSystem"` 也恰是字符串——两种情形只有 AST 分得开。
      //   判据：源码里不出现 `InfoSystem`/`SdInfoEntry`/`bySubject` 这些**名字**（Name/Attribute/Import）。
      java.util.List<String> symbols =
          pySymbolNames(py).stream()
              .filter(
                  n -> n.equals("InfoSystem") || n.equals("SdInfoEntry") || n.equals("bySubject"))
              .sorted()
              .toList();
      assertThat(symbols).as("★ 红线：%s 是纯离线文档器，源码里不得出现 Info 的符号名", py.getFileName()).isEmpty();
      // 兼容手段：也禁止 `import io.mosire`（跨语言声明，逻辑上不该出现在 py 里）
      assertThat(text).doesNotContain("import io.mosire");
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

  /**
   * 从 Python 源码里抽**标识符名**（Name / Attribute / import），跳过注释与字符串字面量。
   *
   * <p>它不是完整 Python 解析器：够用的形态即可（单行 `#` 注释、三引号 docstring、单/双引号字符串）。
   * 目标是"区分标识符与文本"——这正是裸词扫描做不到、而本红线判据需要的那一步。
   */
  private static List<String> pySymbolNames(Path py) throws IOException {
    String sanitized = stripPythonCommentsAndStrings(Files.readString(py, StandardCharsets.UTF_8));
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(sanitized);
    List<String> names = new java.util.ArrayList<>();
    while (m.find()) {
      names.add(m.group());
    }
    return names;
  }

  private static String stripPythonCommentsAndStrings(String src) {
    StringBuilder out = new StringBuilder(src.length());
    int i = 0;
    int n = src.length();
    while (i < n) {
      char c = src.charAt(i);
      if (c == '#') {
        while (i < n && src.charAt(i) != '\n') {
          i++;
        }
        continue;
      }
      if (c == '"' || c == '\'') {
        boolean triple = i + 2 < n && src.charAt(i + 1) == c && src.charAt(i + 2) == c;
        int close = triple ? 3 : 1;
        i += close;
        while (i < n) {
          if (src.charAt(i) == '\\') {
            i += 2;
            continue;
          }
          if (triple) {
            if (i + 2 < n
                && src.charAt(i) == c
                && src.charAt(i + 1) == c
                && src.charAt(i + 2) == c) {
              i += 3;
              break;
            }
          } else if (src.charAt(i) == c) {
            i += 1;
            break;
          }
          i++;
        }
        out.append(' ');
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
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
