package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **经济词表全仓恰一份**（v2 spec §六）。
 *
 * <p><b>病灶形态</b>：v1 里 {@code 83} 与 {@code "grain"} 各在 {@code EconomySeeder}（app）与 {@code
 * EconomySettlement}（economy）写了一份，{@code ApiViews} 还私藏第三份 —— **没有任何东西钉住它们相等** ⇒
 * 改一处即静默分叉（改口粮口径时必然踩）。军队接入后会有第四份。
 *
 * <p>★ <b>为什么这条护栏住在 {@code io.mosire.simos.util.verify}</b>：仓源扫描底座 {@link RepoSourceScan} 是**包私有**
 * 的（test scope 的类不跨模块可见，为此造 test-jar 依赖是真耦合 —— 见它的类注释）。护栏的模块归属由"底座在哪"决定。 本用例只读文件、不依赖
 * economy，故不需要任何模块依赖。
 *
 * <p>★ <b>只扫 {@code src/main}</b>：用例里的 {@code new CommodityId("grain")} 是测试夹具，不是第二份真相
 * （护栏扫它们只会把"改测试"也变成违规）。
 */
class EconomyVocabularyGuardTest {

  /** 全部模块的 {@code src/main}——漏一个模块 = 那道口子没人守。 */
  private static final List<String> MODULES =
      List.of(
          "simos-util",
          "simos-map",
          "simos-social",
          "simos-unit",
          "simos-core",
          "simos-sd",
          "simos-economy-api",
          "simos-ledger",
          "simos-economy",
          "simos-app");

  /** 词表的唯一落点（三处声明都得在这里）。 */
  private static final String VOCABULARY =
      "simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java";

  /** 逐文件数 ``token`` 出现在多少行上；只收 >0 的文件，键 = 仓库相对路径。 */
  private static Map<String, Long> occurrencesByFile(String token) {
    Map<String, Long> hits = new LinkedHashMap<>();
    try {
      for (String module : MODULES) {
        for (Path file : RepoSourceScan.javaFilesUnder(module + "/src/main")) {
          long lines =
              RepoSourceScan.rawContent(file).lines().filter(l -> l.contains(token)).count();
          if (lines > 0L) {
            hits.merge(RepoSourceScan.relative(file), lines, Long::sum);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return hits;
  }

  @Test
  void rationLiteralIsWrittenExactlyOnce() {
    // ★ 钉的是「名字 + 字面量同处一行」= **直接赋值**的那一处。转发常量（= EconomyVocabulary.X）不含字面量，
    //   故不会命中 —— 它们不可能漂移，不该被这条判为违规。
    assertThat(occurrencesByFile("DAILY_GRAIN_MILLI_PER_PERSON = 83"))
        .as("口粮的字面量 83 在全仓 src/main 里必须只被直接赋值一次（v1 有两处且无守卫 ⇒ 改一处即静默分叉）")
        .containsExactly(entry(VOCABULARY, 1L));
  }

  @Test
  void grainCommodityIdLiteralIsWrittenExactlyOnce() {
    assertThat(occurrencesByFile("GRAIN_COMMODITY_ID = \"grain\""))
        .as("粮商品 id 的字面量在全仓 src/main 里必须只被直接赋值一次")
        .containsExactly(entry(VOCABULARY, 1L));
  }

  @Test
  void noModuleSpellsTheGrainIdLiteralInline() {
    assertThat(occurrencesByFile("CommodityId(\"grain\")"))
        .as("不许有模块就地写 new CommodityId(\"grain\")（v1 的 ApiViews 与 EconomySettlement 各有一处）")
        .isEmpty();
  }
}
