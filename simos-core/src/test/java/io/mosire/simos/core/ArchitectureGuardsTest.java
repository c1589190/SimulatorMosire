package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M4 架构护栏（spec §十一 R1）：ChangeSet 的 main 源码实现者名单被钉住。 */
class ArchitectureGuardsTest {

  /**
   * R1：main 源码里 {@code ChangeSet} 的实现者**恰 4 个**（World/Map/Social/Unit）——多一个少一个都是信号： 多一个可能是有人绕过
   * codec 在 Core 里另造状态形态；少一个说明某个模块的变更集丢了 ChangeSet 身份 （Featherweight 的"契约不空转"断了一根）。
   *
   * <p>★ test 侧的 3 个玩具实现者（simos-util 的 {@code RoundTripAssertionsTest} / {@code DriftTest} 里）
   * **不计入**：它们不在 main 源码里，本扫描只枚举四个模块的 {@code src/main}——这条例外写在这里， 免得下一个人以为漏扫了。
   *
   * <p>★ 用 {@code containsExactly} 而不只是 {@code hasSize(4)}：个数对"一个文件重复含串、另一个实现者 恰好漏了"零判别力（M1
   * 的形态：数个数对张冠李戴零判别力）。扫描匹配的是**连续串**——声明被 google-java-format 折行时 {@code implements}
   * 与类型名仍落在同一行（实测：MapChangeSet 即折行形态，仍命中）。
   */
  @Test
  void changeSetHasExactlyFourMainSourceImplementors() throws IOException {
    // ★ rawContent 声明受检异常，而 lambda（Predicate）传不出去——计划草图此处编不过，
    //   故经下面的 content() 拆包成 UncheckedIOException（与 util 侧 R15 同款执行期校正）。
    List<String> hits =
        RepoSourceScan.javaFilesUnder(
                "simos-map/src/main",
                "simos-social/src/main",
                "simos-unit/src/main",
                "simos-core/src/main")
            .stream()
            .filter(p -> content(p).contains("implements ChangeSet"))
            .map(RepoSourceScan::relative)
            .sorted()
            .toList();

    assertThat(hits)
        .containsExactly(
            "simos-core/src/main/java/io/mosire/simos/core/state/WorldChangeSet.java",
            "simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java",
            "simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java",
            "simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java");
  }

  private static String content(java.nio.file.Path file) {
    try {
      return RepoSourceScan.rawContent(file);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
