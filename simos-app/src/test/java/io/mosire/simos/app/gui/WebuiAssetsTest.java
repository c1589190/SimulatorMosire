package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * WebUI 静态资源结构护栏（M5 T9，spec §8.1/§8.3；M7 T2 扩条；M8 T7 校正）：三页 + 共享资产 + 工作台骨架**在册且非空**、**无 绝对 URL / 无
 * CDN**、三页**按相对路径**引用共享资产、{@code /} 是工作台主应用（模式栏**六个真控件**，M8 T7 起不再有"归 M8 的禁用按钮"、T7 起含「决策」）。
 *
 * <p>★ **为什么读源码树而不是 classpath**：本护栏要能对**故意违规**的源码变异响铃（计划 T9 变异 m1：往某资产里塞 {@code
 * https://cdn.example.com/x.js}）。读 {@code target/classes} 会读到上一次 {@code process-resources}
 * 的副本，变异轮是否 真的看到新字节取决于构建的拷贝时序——本项目吃过"陈旧 {@code .class}/报告被当本轮结论"的亏（CLAUDE.md 纪律形态 1）。⇒ 判定字节读 {@code
 * src/main/resources/webui/}（surefire 工作目录＝模块根，主树与 worktree 均成立，与 {@code AppWritePathGuardTest}
 * 同法）；同时**另立一条**用例断言这些资源确实进了 classpath（打包口径不被忽略）。
 *
 * <p>★ **非空自证**：扫到的 webui 资产数 ≥ 10、且三页与全部共享/骨架资产逐一在册——"扫描器静默扫 0 个文件"在本项目是已知陷阱（形态 1），不钉住护栏会变装饰。
 *
 * <p>★ **无绝对 URL 的判定**：扫描 {@code http://} / {@code https://} 与**协议相对**的 {@code //host} 形态。后者以行内
 * {@code //} 出现（JS 注释、正则、字符串里也可能有），故**只认"// 后紧跟一个看起来像主机名/路径的字符"**：{@code //} 后是空白、{@code /}、或者属于 JS
 * 注释 {@code // } 的不计。{@link #absoluteUrlScannerHasTeeth} 用冻结字面量钉住这条边界。
 */
class WebuiAssetsTest {

  private static final Path WEBUI_SOURCE = Paths.get("src/main/resources/webui");
  private static final String CLASSPATH_ROOT = "/webui/";

  /** 三页（spec §8.2 静态路由的落点）。 */
  private static final List<String> PAGES =
      List.of("index.html", "map.html", "unit.html", "social.html");

  /** 共享资产（spec §8.3：三页共享同一套）。 */
  private static final List<String> SHARED_ASSETS = List.of("api.js", "app.js", "styles.css");

  /** 三页各自额外引用的页内脚本（非共享，但同属 webui 资产）。 */
  private static final List<String> PAGE_SCRIPTS = List.of("map.js", "unit.js", "social.js");

  /** 工作台骨架脚本（M7 T2）：主应用 `/` 引用的四个新资产（T1 起含右下角通知栏）。 */
  private static final List<String> WORKBENCH_SCRIPTS =
      List.of("panels.js", "unitTree.js", "timeline.js", "notifications.js");

  /** 块几何（M9 T13）：index/map 两页共用的纯函数模块（`window.SimosBlocks`）。 */
  private static final List<String> BLOCK_SCRIPTS = List.of("blocks.js");

  /** 模式栏六按钮的可见标签（M8 spec §三；T7 起五个都是可点击的真控件；T7 加「决策」）。 */
  private static final List<String> MODE_LABELS =
      List.of("常规", "区域查看", "地图编辑", "区域编辑", "单位移动编辑", "决策");

  private static final List<String> ALL_ASSETS =
      concat(PAGES, SHARED_ASSETS, PAGE_SCRIPTS, WORKBENCH_SCRIPTS, BLOCK_SCRIPTS);

  @Test
  void allWebuiAssetsExistAndAreNonEmpty() throws IOException {
    List<Path> assets = webuiSources();

    assertThat(assets)
        .as("扫描必须非空（surefire 工作目录＝模块根）：扫到 0 个文件是『扫描器静默扫 0』陷阱，不是通过")
        .hasSizeGreaterThanOrEqualTo(10)
        .anyMatch(path -> path.getFileName().toString().equals("map.html"))
        .anyMatch(path -> path.getFileName().toString().equals("api.js"))
        .anyMatch(path -> path.getFileName().toString().equals("timeline.js"));

    for (String name : ALL_ASSETS) {
      Path asset = WEBUI_SOURCE.resolve(name);
      assertThat(Files.isRegularFile(asset)).as("%s 必须存在", name).isTrue();
      assertThat(read(asset)).as("%s 必须非空", name).isNotBlank();
    }
  }

  @Test
  void webuiAssetsArePackagedOnTheClasspath() {
    for (String name : ALL_ASSETS) {
      try (InputStream in = getClass().getResourceAsStream(CLASSPATH_ROOT + name)) {
        assertThat(in).as("classpath %s%s 必须可读（打包口径）", CLASSPATH_ROOT, name).isNotNull();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @Test
  void noWebuiAssetContainsAnAbsoluteUrl() throws IOException {
    List<Path> assets = webuiSources();

    assertThat(assets).as("扫描必须非空").isNotEmpty();

    for (Path asset : assets) {
      String content = read(asset);
      assertThat(content)
          .as("%s 不得含 http:// 绝对 URL（spec §8.1 无 CDN）", asset.getFileName())
          .doesNotContain("http://");
      assertThat(content)
          .as("%s 不得含 https:// 绝对 URL（spec §8.1 无 CDN）", asset.getFileName())
          .doesNotContain("https://");
      List<String> protocolRelative = protocolRelativeUrls(content);
      assertThat(protocolRelative)
          .as("%s 不得含协议相对 URL（//host…；spec §8.1 同源）", asset.getFileName())
          .isEmpty();
    }
  }

  @Test
  void eachPageReferencesSharedAssetsByRelativePath() throws IOException {
    for (String page : PAGES) {
      String html = read(WEBUI_SOURCE.resolve(page));
      for (String shared : SHARED_ASSETS) {
        assertThat(html)
            .as("%s 必须以相对路径引用共享资产 %s（不得 /webui/%s 或绝对 URL）", page, shared, shared)
            .contains("\"" + shared + "\"");
      }
    }
  }

  // ── 工作台主应用（M7 T2，判据①）────────────────────────────────────

  /** `/` 回来的必须是工作台骨架：模式栏五按钮、三栏、底栏、三个骨架脚本。 */
  @Test
  void rootServesTheWorkbenchSkeleton() throws IOException {
    String html = read(WEBUI_SOURCE.resolve("index.html"));

    assertThat(html).as("/ 必须是工作台主应用（模式栏标识）").contains("id=\"mode-bar\"");
    for (String label : MODE_LABELS) {
      assertThat(html).as("模式栏必须含按钮「%s」", label).contains(label);
    }
    for (String script : WORKBENCH_SCRIPTS) {
      assertThat(html).as("index.html 必须以相对路径引用骨架脚本 %s", script).contains("\"" + script + "\"");
    }
    assertThat(html).as("中部三栏容器").contains("col-left").contains("col-center").contains("col-right");
    assertThat(html).as("底部时间轴容器").contains("timeline-bar").contains("timeline-mount");
  }

  /**
   * M8 T7：六个模式**都是可点击的真控件**（不再有"归 M8 的禁用按钮"；T7 起含「决策」）。
   *
   * <p>★ 判别力：{@link #buttonScannerHasTeeth} 用冻结字面量证明本扫描器能区分 disabled / enabled 两种形态——若哪天有人把某个模式改回
   * {@code disabled} 或塞回 {@code data-milestone}，本用例必红。★ 新增模式只改 {@code modes.js} 而漏了 {@code
   * index.html} 的按钮 ⇒ 本用例红（T7 的 m3 变异靶子）。
   */
  @Test
  void allSixModesAreEnabledRealControls() throws IOException {
    String html = read(WEBUI_SOURCE.resolve("index.html"));

    for (String label : MODE_LABELS) {
      List<String> tags = buttonTagsContaining(html, label);
      assertThat(tags).as("必须恰有一个「%s」按钮", label).hasSize(1);
      assertThat(tags.get(0)).as("「%s」不得 disabled（T7 起是真控件）", label).doesNotContain("disabled");
      assertThat(tags.get(0)).as("「%s」不得再带 M8 标记", label).doesNotContain("data-milestone");
    }
  }

  /** 取含指定 label 的完整 {@code <button …>…</button>} 标签。 */
  static List<String> buttonTagsContaining(String html, String label) {
    List<String> found = new ArrayList<>();
    Matcher matcher = Pattern.compile("(?s)<button\\b[^>]*>.*?</button>").matcher(html);
    while (matcher.find()) {
      String tag = matcher.group();
      if (tag.contains(label)) {
        found.add(tag);
      }
    }
    return found;
  }

  /** 按钮判定器自证（形态 1）：禁用/启用两种形态各自可判，且不误伤。 */
  @Test
  void buttonScannerHasTeeth() {
    String html =
        "<button type=\"button\" disabled data-milestone=\"M8\">地图编辑</button>"
            + "<button type=\"button\">区域查看</button>";
    List<String> disabled = buttonTagsContaining(html, "地图编辑");
    assertThat(disabled).hasSize(1);
    assertThat(disabled.get(0)).contains("disabled");
    List<String> enabled = buttonTagsContaining(html, "区域查看");
    assertThat(enabled).hasSize(1);
    assertThat(enabled.get(0)).doesNotContain("disabled");
    assertThat(buttonTagsContaining(html, "不存在")).isEmpty();
  }

  // ── 判定器自证（护栏的判别力；形态 1/5）──────────────────────────────

  @Test
  void absoluteUrlScannerHasTeeth() {
    // 冻结字面量：正面（应被抓）与反面（不应被抓）。
    assertThat(protocolRelativeUrls("<script src=\"//cdn.example.com/x.js\"></script>"))
        .as("协议相对 URL 必须被抓")
        .isNotEmpty();
    assertThat(protocolRelativeUrls("fetch('https://api.example.com')")).isEmpty();
    assertThat(protocolRelativeUrls("// 这是 JS 注释，不是 URL\nvar a = 1;")).isEmpty();
    assertThat(protocolRelativeUrls("a // b  两数相除的注释")).isEmpty();
    assertThat(protocolRelativeUrls("var s = 'a//b';")).isEmpty();
    assertThat(protocolRelativeUrls("<!-- 注释里的 // 也不该被抓 -->")).isEmpty();
    assertThat(protocolRelativeUrls("url: //example.com/a")).isNotEmpty();
  }

  // ── 工具 ────────────────────────────────────────────────────────────

  /**
   * 找协议相对 URL 形态：{@code //} 后紧跟一个"像主机名/路径"的字符——字母、数字或 {@code /}。
   *
   * <p>排除 JS/HTML 注释与普通 {@code //}：{@code //} 后若是空白、{@code >}、{@code *}、{@code /} 开头且整体不是 URL 的（如
   * {@code ///}）都不算。字符串里 {@code a//b} 的后一个 {@code /} 后是 {@code
   * b}——但前一个字符是标识符字符，故以"前一个非空白字符不是标识符/引号"排除之。
   */
  static List<String> protocolRelativeUrls(String content) {
    List<String> found = new ArrayList<>();
    Matcher matcher = Pattern.compile("//[A-Za-z0-9/]").matcher(content);
    while (matcher.find()) {
      int start = matcher.start();
      char prev = start > 0 ? content.charAt(start - 1) : '\0';
      // 前面的字符若是标识符字符或点/斜杠/冒号，则这多半是除法、字符串或已有路径的一部分，不是协议相对 URL。
      if (isIdentifierChar(prev) || prev == '.' || prev == ':' || prev == '/') {
        continue;
      }
      found.add(content.substring(start, Math.min(content.length(), start + 32)));
    }
    return found;
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  private static List<Path> webuiSources() throws IOException {
    Path root = WEBUI_SOURCE.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException(
          "不是目录（surefire 的工作目录应是模块根，相对 src/main/resources/webui 必在此）: " + root);
    }
    List<Path> assets = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile).sorted().forEach(assets::add);
    }
    return assets;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SafeVarargs
  private static List<String> concat(List<String>... lists) {
    List<String> out = new ArrayList<>();
    for (List<String> list : lists) {
      out.addAll(list);
    }
    return List.copyOf(out);
  }
}
