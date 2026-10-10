package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  /**
   * 全部模块的 {@code src/main}——漏一个模块 = 那道口子没人守。
   *
   * <p>★★ **本清单不许手抄**（Task 10）：它此前停在"R2a 那一刻的十个模块"，S1 阶段 2 新增的 {@code simos-actor-api} / {@code
   * simos-actor} **静默漏掉**（清单没变、用例全绿、词表缺口无人守）——这正是本清单下面那条 {@link
   * #theScannedModuleListMatchesTheOneTheBuildDeclares()} 要拦住的事。新增模块时**两处一起加**（本清单 + 根 {@code
   * pom.xml}），只加一处当场红。
   */
  static final List<String> MODULES =
      List.of(
          "simos-util",
          "simos-map",
          "simos-calendar",
          "simos-social-api",
          "simos-social",
          "simos-unit",
          "simos-core",
          "simos-sd",
          "simos-actor-api",
          "simos-actor",
          "simos-economy-api",
          "simos-economy",
          "simos-gov",
          "simos-army",
          "simos-app");

  /** 根 {@code pom.xml} 里的模块声明（构建面的权威清单）。 */
  private static final Pattern MODULE_TAG = Pattern.compile("<module>([^<]+)</module>");

  /** 词表的唯一落点（口径的两个数都得在这里）。 */
  private static final String VOCABULARY =
      "simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java";

  /**
   * ★★ **扫描面自己不许漂移**（Task 10 补）：{@link #MODULES} 必须**恰恰等于**根 {@code pom.xml} 声明的 {@code <module>}
   * 集合。
   *
   * <p><b>病灶形态</b>（真发生过，不是推演）：本类的 {@code MODULES} 是**手抄**的，而"全仓恰一份"的判据只能守在**被扫到的** 模块上 —— 于是 S1 阶段
   * 2 加了两个模块之后，清单没变、用例全绿，那两片的词表口子**没人守**。手抄清单与"清单该覆盖什么"之间 没有任何东西钉住，本用例就是那根钉子。
   *
   * <p>★ <b>为什么钉"等于"而不是"包含"</b>：多一个（清单里有、构建面没有 ⇒ 拼错模块名，扫描其实是空的）与少一个同样是静默 失效方向；{@code
   * containsExactlyInAnyOrderElementsOf} 两个方向一起钉，顺带把清单里的**重复项**也判红。
   */
  @Test
  void theScannedModuleListMatchesTheOneTheBuildDeclares() throws IOException {
    Set<String> declared = modulesDeclaredInRootPom();

    assertThat(declared)
        .as("★ 先证明解析器不是静默返回空（'命中 0 先怀疑自己的读取'：正则/读取坏掉时下面那条会变成恒真）")
        .contains("simos-util", "simos-actor-api", "simos-actor", "simos-app")
        .hasSizeGreaterThanOrEqualTo(
            15); // ★ 2026-10-09：simos-social-api 新增 ⇒ 15（simos-ledger 已退役；清单与根 pom 必须同步）
    assertThat(MODULES)
        .as("★★ 扫描面必须恰恰等于根 pom 的 <module> 集合——否则下个新模块还会静默漏掉")
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  /**
   * 根 {@code pom.xml} 里声明的模块名（{@code <module>…</module>} 一行一个）。
   *
   * <p>★ <b>为什么正则够用、且失败模式已被上面那条非空断言兜住</b>：这份文件是本仓自己的、形态极简（一行一模块，注释里不含该标签）， 而正则解析 XML 的经典失效是**静默 0
   * 命中**（那会让断言恒真）——故非空 + 具名模块的断言**先**跑。
   */
  private static Set<String> modulesDeclaredInRootPom() throws IOException {
    String pom = RepoSourceScan.rawContent(RepoSourceScan.repoFile("pom.xml"));
    Set<String> modules = new TreeSet<>();
    Matcher matcher = MODULE_TAG.matcher(pom);
    while (matcher.find()) {
      modules.add(matcher.group(1).trim());
    }
    return modules;
  }

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

  /**
   * ★★ **口粮口径的两个数各恰一份**（V5 换了载体：从"每人每日 83"换成"每人每 120 天 10,000 毫粮 + 120 天"）。
   *
   * <p>★ 钉的是「名字 + 字面量同处一行」= **直接赋值**的那一处；引用（{@code EconomyVocabulary.RATION_…}）不含字面量， 故不会命中 ——
   * 它们不可能漂移，不该被这条判为违规。
   */
  @Test
  void rationBasisLiteralsAreWrittenExactlyOnce() {
    assertThat(occurrencesByFile("RATION_MILLI_PER_PERSON = 10_000"))
        .as("口径分子的字面量（10,000 毫粮/人·120 天）在全仓 src/main 里必须只被直接赋值一次")
        .containsExactly(entry(VOCABULARY, 1L));
    assertThat(occurrencesByFile("RATION_CYCLE_DAYS = 120"))
        .as("口径分母（120 天）同理；★ 它与 EconomySeeder.CYCLE_DAYS 数值相同、语义无关，两者都必须是具名常量")
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

  /**
   * ★★ **R3：新商品 id 也各恰一份**（T1）—— 布 / 纤维 / 工具 / 铁 / 木。
   *
   * <p>★ **病灶形态与粮同款**：`"cloth"` 这种字面量一旦在 economy 侧与 app 侧各写一遍，改一处即静默分叉 （"田里产的纤维"与"织机吃的纤维"会变成两种商品 ——
   * 账面看不出来，只有守恒式会莫名其妙不平）。 ★ 判据与粮那条**逐字同款**：钉「名字 + 字面量同处一行」=
   * **直接赋值**那一处；引用（`EconomyVocabulary.CLOTH_COMMODITY_ID`） 不含字面量，故不会命中 —— 它们不可能漂移。
   */
  @Test
  void everyNewCommodityIdLiteralIsWrittenExactlyOnce() {
    assertThat(occurrencesByFile("CLOTH_COMMODITY_ID = \"cloth\""))
        .as("布的商品 id 字面量在全仓 src/main 里必须只被直接赋值一次")
        .containsExactly(entry(VOCABULARY, 1L));
    assertThat(occurrencesByFile("FIBER_COMMODITY_ID = \"fiber\""))
        .as("纤维")
        .containsExactly(entry(VOCABULARY, 1L));
    assertThat(occurrencesByFile("TOOL_COMMODITY_ID = \"tool\""))
        .as("工具")
        .containsExactly(entry(VOCABULARY, 1L));
    assertThat(occurrencesByFile("IRON_COMMODITY_ID = \"iron\""))
        .as("铁")
        .containsExactly(entry(VOCABULARY, 1L));
    assertThat(occurrencesByFile("WOOD_COMMODITY_ID = \"wood\""))
        .as("木")
        .containsExactly(entry(VOCABULARY, 1L));
  }

  /** ★★ **五个新商品都不许就地写 `CommodityId("…")`**（与粮那条同款：v1 的 ApiViews 私藏第三份正是这样来的）。 */
  @Test
  void noModuleSpellsTheNewCommodityIdsInline() {
    for (String id : List.of("cloth", "fiber", "tool", "iron", "wood")) {
      assertThat(occurrencesByFile("CommodityId(\"" + id + "\")"))
          .as("不许有模块就地写 new CommodityId(\"%s\")", id)
          .isEmpty();
    }
  }

  /**
   * ★★ <b>M1.1：币种 id 的字面量也恰一份</b>（世界级货币词表 = {@code MoneyVocabulary}，独立于商品词表）。
   *
   * <p>★ <b>为什么护栏要跟着加</b>：M1.1 把 {@code "silver"} 从 {@code RegimeRelations.DEFAULT_CURRENCY}（一个就地
   * {@code new CurrencyId("silver")}）搬进 {@code MoneyVocabulary}，并新增了 {@code CurrencyDef} / {@code
   * MoneyInstrument} 两个类型 —— 若没人钉住"字面量只写一次"，下一处 {@code new CurrencyId("silver")}
   * 会<b>静默</b>地把"哪种钱"分叉成两种 （币种名漂开不会有任何编译错误，这正是 M1.1 词表存在的理由）。
   *
   * <p>★ <b>为什么落点是 {@code economy-api} 而不是 util 的 {@code EconomyVocabulary}</b>：货币词表<b>带类型</b>
   * （{@code CurrencyDef} / {@code MoneyInstrument} 要用 {@code ActorRef} 与 {@code CurrencyId}），util
   * 看不见它们 ⇒ 只能住契约层。★ 本护栏只读文件、不依赖 economy，故照旧住在 util。
   */
  @Test
  void silverCurrencyIdLiteralIsWrittenExactlyOnce() {
    assertThat(occurrencesByFile("SILVER_CURRENCY_ID = \"silver\""))
        .as("★ 银的币种 id 字面量在全仓 src/main 里必须只被直接赋值一次（M1.1 的世界级货币词表）")
        .containsExactly(
            entry(
                "simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/MoneyVocabulary.java",
                1L));
  }

  /** ★★ 与商品那条同款：**不许**有模块就地把币种身份拼出来（`new CurrencyId("silver")`）。 */
  @Test
  void noModuleSpellsTheSilverCurrencyInline() {
    assertThat(occurrencesByFile("CurrencyId(\"silver\")"))
        .as("不许有模块就地写 new CurrencyId(\"silver\")（M1.1 之前 RegimeRelations 正是这样写的）")
        .isEmpty();
  }

  // ── A1（2026-10-10）：运输服务（haul）成为商品 ⇒ 同款护栏 ─────────────────────────────

  /**
   * ★★ <b>A1：运输服务的商品 id 字面量也恰一份</b>（设计书 `2026-10-10-haul-service-commodity-design.md` §3.1 / T-H1）。
   *
   * <p>★ <b>为什么它必须进护栏</b>：`haul` 是**跨模块**的一个键 —— 词表（util）、牌价/配方（app）、服务成交与运力
   * 口径（economy）都要用它。若哪一处就地写 `new CommodityId("haul")`，"运输服务"就会静默分叉成两种商品 （账面看不出来，只有守恒式会莫名其妙不平）—— 与
   * `grain`/`cloth` 那两条同款病灶。
   */
  @Test
  void haulCommodityIdLiteralIsWrittenExactlyOnce() {
    assertThat(occurrencesByFile("HAUL_COMMODITY_ID = \"haul\""))
        .as("运输服务的商品 id 字面量在全仓 src/main 里必须只被直接赋值一次（唯一权威 = EconomyVocabulary）")
        .containsExactly(entry(VOCABULARY, 1L));
  }

  /** ★★ 与粮/五个新商品同款：**不许**有模块就地把 `haul` 拼出来（`new CommodityId("haul")`）。 */
  @Test
  void noModuleSpellsTheHaulIdInline() {
    assertThat(occurrencesByFile("CommodityId(\"haul\")"))
        .as("不许有模块就地写 new CommodityId(\"haul\")（照 grain/cloth/… 的先例）")
        .isEmpty();
  }

  /**
   * ★★ <b>A1：{@code haul} 追加在词表**末尾**，既有六项的相对序逐字不动</b>（设计书 §5 I-H3 缺省中性的硬要求）。
   *
   * <p>★ <b>为什么钉"序"而不只钉"包含"</b>：{@code allCommodityIds()} 是各读口/报告列序的**唯一来源**（{@code ApiViews} 的
   * {@code commodityIds}、口岸政策校验、运费表校验都按它）；把新项插在中间会让既有六项的相对序发生位移， 而"按词表序读"的既有读口会静默换列 —— 这类位移不会报任何错。★
   * 判别力：把 {@code HAUL_COMMODITY_ID} 插到 {@code WOOD_COMMODITY_ID} 之前 ⇒ 本条当场红。
   *
   * <p>★ <b>只读 {@code return} 那一条语句</b>（不含方法注释），且**先证明读取不是静默落空**再断言（§三："命中 0 先怀疑自己的读取"）。
   */
  @Test
  void haulIsAppendedAfterTheSixLegacyIdsWhichKeepTheirRelativeOrder() throws IOException {
    String body = allCommodityIdsReturnStatement();
    int previous = -1;
    for (String constant :
        List.of(
            "GRAIN_COMMODITY_ID",
            "CLOTH_COMMODITY_ID",
            "FIBER_COMMODITY_ID",
            "TOOL_COMMODITY_ID",
            "IRON_COMMODITY_ID",
            "WOOD_COMMODITY_ID")) {
      int at = body.indexOf(constant);
      assertThat(at).as("★ 词表序里的 %s 必须出现在返回清单中", constant).isGreaterThanOrEqualTo(0);
      assertThat(at).as("★ 前六项的相对序逐字不变（%s 不得被挪到更前面）", constant).isGreaterThan(previous);
      previous = at;
    }
    int haul = body.indexOf("HAUL_COMMODITY_ID");
    assertThat(haul).as("★ A1：haul 必须**追加在末尾**（前六项相对序不动）").isGreaterThan(previous);
    assertThat(body.indexOf("HAUL_COMMODITY_ID", haul + 1))
        .as("★ 清单里 haul 恰一项（不许写两遍）")
        .isEqualTo(-1);
  }

  /** {@code EconomyVocabulary.allCommodityIds()} 的 {@code return …;} 语句（不含注释；读取失败当场红）。 */
  private static String allCommodityIdsReturnStatement() throws IOException {
    String source = RepoSourceScan.rawContent(RepoSourceScan.repoFile(VOCABULARY));
    int method = source.indexOf("allCommodityIds()");
    assertThat(method).as("★ 先证明读取不是静默落空（否则下面的序断言会恒真）").isGreaterThanOrEqualTo(0);
    int start = source.indexOf("return", method);
    int end = start < 0 ? -1 : source.indexOf(';', start);
    assertThat(start).as("返回语句必须存在").isGreaterThan(method);
    assertThat(end).as("返回语句必须以分号收尾").isGreaterThan(start);
    return source.substring(start, end);
  }
}
