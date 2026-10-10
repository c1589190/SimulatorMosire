package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>N-H2：跑商"平行机器"退役后的**残留护栏**</b>（设计书
 * `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` v1.3 §3.4 / §6 N-H2）。
 *
 * <p>★★ <b>它守什么</b>：A3 把跑商从"市场轮私有的平行机器"并回标准生产管线后，三族结构必须**在代码面**清零（留痕注释不算）：
 *
 * <pre>
 * ① 平行门槛族      MerchantHaul（每趟 1,000 毫工具的门槛 + 烧工具 + tool-short/tool-frozen 归因）、
 *                  pool.toolBlockedRuns()（门槛的具名计数读口）、MERCHANT_HAUL_TOOL_* 三个事件名
 * ② 平行利润族      MerchantProfitBook（影子利润账；收益改走标准 EnterpriseProfitBook 的 MARKET_TRADE 收入腿）、
 *                  MERCHANT_PROFIT_* 两个事件名
 * ③ 双记运费族      同一笔运费既走商品成交又走 CARRIER_FEE 私有腿（I-H5）
 * ④ 走私族          smuggl*（历史遗留命名；设计书 §3.4 要求 grep 证明无残留）
 * </pre>
 *
 * <p>★★ <b>为什么这条护栏必须存在</b>：这些族**删掉之后没有任何编译期证据要求它们保持删除** —— 删类的提交本身不留守卫，
 * 后人"顺手加回一个门槛/影子账"不会红。本仓的纪律是"护栏要自证判别力"，故这里把三条都钉住；同时它也把 A3 账本里那句 "N-H2 自证 ① 门槛族标识符 main 全模块（排除注释行）0
 * 命中"从**一次性 grep** 变成**常驻测试**。
 *
 * <p>★ <b>扫描面</b> = {@link EconomyVocabularyGuardTest#MODULES}（与词表护栏同一份清单，已由那条用例钉住它 == 根 {@code
 * pom.xml} 的 {@code <module>} 集合 ⇒ 新增模块不会静默漏扫）的 {@code src/main}。 ★ <b>只扫 {@code
 * src/main}</b>：测试夹具里出现历史名字是留痕，不是残留（判据问的是"生产代码还在不在做那件事"）。 ★ <b>注释被剥掉后再数</b>（含块注释 {@code /*…*}{@code
 * /} 与行注释）：判据是"代码面"，而 A3 特意在各处留了 "某族已退役"的说明性注释 —— 那些是**留痕**（§五.4），不该被本护栏判红。
 */
class HaulParallelMachineRetirementGuardTest {

  /**
   * 已退役的"平行机器"族标识符（**代码面必须 0 命中**）。
   *
   * <p>★ 逐项对应设计书 §3.4 的退役清单；括号里是它曾经做的事。
   */
  private static final List<String> RETIRED_PARALLEL_FAMILIES =
      List.of(
          "MerchantHaul", // 趟耗 1,000 毫工具 + "工具不够一趟 ⇒ 该次跑商不成立" + tool-frozen/tool-short
          "toolBlockedRuns", // 门槛的具名计数读口（MerchantCapacityPool）
          "MERCHANT_HAUL_TOOL_", // 门槛族事件名：SHORT_AT_COMMIT / BLOCKED_AT_SELECT / UNPRICED
          "MerchantProfitBook", // 平行利润读数（影子账）
          "MERCHANT_PROFIT_", // 平行利润事件名：TOTAL / HOUSEHOLD
          "CapacityQuote", // 逐户自报价簿（CapacityQuote / CapacityQuoteBook）
          "MERCHANT_HOUSEHOLDS_LOST_BY_CLONE"); // §16 特例的"克隆丢了跑商家户集合"防复发 ERROR 行

  /** 走私族（设计书 §3.4 的历史遗留命名；**代码面必须 0 命中**，含任意大小写形态）。 */
  private static final List<String> SMUGGLING_FAMILIES = List.of("smuggl", "Smuggl", "SMUGGL");

  /** 运费腿的理由码（I-H5 的判据面）。 */
  private static final String CARRIER_FEE = "CARRIER_FEE";

  /** 标准企业利润读的收入腿（服务成交改走它）。 */
  private static final String MARKET_TRADE = "MARKET_TRADE";

  private static final String MARKET_SETTLEMENT =
      "simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java";

  private static final String ENTERPRISE_PROFIT_BOOK =
      "simos-economy/src/main/java/io/mosire/simos/economy/time/EnterpriseProfitBook.java";

  private static final String TRANSFER_REASON =
      "simos-economy-api/src/main/java/io/mosire/simos/economy/api/transfer/TransferReason.java";

  /** ★★ 平行门槛/利润/自报价三族：全仓 {@code src/main} 的**代码面**必须 0 命中。 */
  @Test
  void retiredParallelMachineFamiliesHaveNoCodeResidue() {
    assertThat(scannedFileCount()).as("★ 先证明扫描面不是空的（读取坏掉时下面的断言会恒真）").isGreaterThan(500);
    for (String token : RETIRED_PARALLEL_FAMILIES) {
      Map<String, List<String>> hits = codeLinesWith(token);
      assertThat(hits).as("★ A3 退役族 %s 在 src/main 的**代码面**必须 0 命中（留痕注释不算）", token).isEmpty();
    }
  }

  /** ★★ 走私族：同样 0 命中（设计书 §3.4 的第三族）。 */
  @Test
  void smugglingFamilyHasNoCodeResidue() {
    for (String token : SMUGGLING_FAMILIES) {
      assertThat(codeLinesWith(token)).as("★ 走私族 %s 在 src/main 的代码面必须 0 命中", token).isEmpty();
    }
  }

  /**
   * ★★ <b>I-H5：一笔运费只有一条铸腿路径</b> —— {@code MarketSettlement} 里 {@code CARRIER_FEE} 的代码面**恰一处**，
   * 且那一处是"服务成市 ⇒ {@code MARKET_TRADE}，否则 ⇒ {@code CARRIER_FEE}"的**同一个三元表达式**（结构上互斥 ⇒ 不可能双记）；{@code
   * EnterpriseProfitBook} 里只许出现**比较**（只读归集），不许出现新的铸腿写法。
   *
   * <p>★ 判别力：把服务 lane 的钱腿改回无条件 {@code CARRIER_FEE}（或再加一条 {@code CARRIER_FEE} 腿）⇒ {@code
   * MarketSettlement} 里 {@code TransferReason.CARRIER_FEE} 的命中数变 2 ⇒ 本条当场红。
   *
   * <p>★ <b>为什么按「身份」而不是只数行数</b>：同一份 {@code MarketSettlement} 里 {@code CARRIER_FEE} 还会以
   * <b>日志标签</b>的身份出现（{@code CARRIER_FEE_PAID}）—— 那是读数、不是腿。故本条把「理由码」与「字符串标签」
   * 分开判：理由码恰一处（三元互斥），其余出现必须是字符串字面量。
   */
  @Test
  void freightIsMintedThroughOneMutuallyExclusiveLeg() {
    Map<String, List<String>> hits = codeLinesWith(CARRIER_FEE);
    assertThat(hits.keySet())
        .as("★ 只许这三处提到 CARRIER_FEE：理由码声明 + 标准利润只读归集 + 市场结算")
        .containsExactlyInAnyOrder(TRANSFER_REASON, ENTERPRISE_PROFIT_BOOK, MARKET_SETTLEMENT);

    List<String> settlementLines = hits.get(MARKET_SETTLEMENT);
    List<String> minting =
        settlementLines.stream()
            .filter(line -> line.contains("TransferReason." + CARRIER_FEE))
            .toList();
    assertThat(minting).as("★ 铸腿点恰一处（两条路径/两条腿都不行）").hasSize(1);
    assertThat(minting.get(0))
        .as("★ 它是「服务成市 ⇒ MARKET_TRADE / 否则 ⇒ CARRIER_FEE」的同一个三元表达式（互斥 ⇒ 不可能双记）")
        .contains("TransferReason." + MARKET_TRADE)
        .contains("?");
    assertThat(settlementLines)
        .as("★ CARRIER_FEE 只许以两种身份出现：理由码（上面的三元）或日志标签字符串")
        .allSatisfy(
            line ->
                assertThat(line.contains("TransferReason." + CARRIER_FEE) || line.contains("\""))
                    .as("MarketSettlement 里出现第三种用法 ⇒ 可能是新的铸腿路径：%s", line)
                    .isTrue());

    assertThat(hits.get(ENTERPRISE_PROFIT_BOOK))
        .as("★ EnterpriseProfitBook 只读归集：它的每一处都必须是 reason 比较，不是新铸腿")
        .allSatisfy(line -> assertThat(line).contains("=="));
  }

  // ── 扫描装置 ──────────────────────────────────────────────────────────────────────────

  /** 命中 {@code token} 的文件（仓相对路径）→ 命中的**代码行**（注释已剥）。只收 >0 命中。 */
  private static Map<String, List<String>> codeLinesWith(String token) {
    Map<String, List<String>> hits = new LinkedHashMap<>();
    try {
      for (String module : EconomyVocabularyGuardTest.MODULES) {
        for (Path file : RepoSourceScan.javaFilesUnder(module + "/src/main")) {
          List<String> matched = new ArrayList<>();
          for (String line : codeOnly(RepoSourceScan.rawContent(file)).lines().toList()) {
            if (line.contains(token)) {
              matched.add(line.trim());
            }
          }
          if (!matched.isEmpty()) {
            hits.put(RepoSourceScan.relative(file), matched);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return hits;
  }

  /** 扫描面大小（非空自证：命中 0 之前先证明"确实扫了东西"）。 */
  private static long scannedFileCount() {
    try {
      long count = 0L;
      for (String module : EconomyVocabularyGuardTest.MODULES) {
        count += RepoSourceScan.javaFilesUnder(module + "/src/main").size();
      }
      return count;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * ★ <b>剥掉注释后的"代码面"</b>（块注释 + 行注释）。
   *
   * <p>★ <b>为什么需要它</b>：A3 在代码里留了大量"某族已退役"的说明（类注/javadoc/行尾注释），它们是**留痕**（§五.4），
   * 不该被判红；判据问的是"代码还在不在做那件事"。
   *
   * <p>★ <b>已知偏差（如实记）</b>：这是**行级**启发式 —— 字符串字面量里出现 {@code //}（例如 URL）会被误当行注释截断。
   * 对本护栏的三族标识符而言这只会**漏报**（不会误报），且本仓的生产代码里没有含这些标识符的字符串字面量。
   */
  private static String codeOnly(String source) {
    StringBuilder out = new StringBuilder(source.length());
    boolean inBlockComment = false;
    for (String rawLine : source.split("\n", -1)) {
      String line = rawLine;
      if (inBlockComment) {
        int end = line.indexOf("*/");
        if (end < 0) {
          continue;
        }
        line = line.substring(end + 2);
        inBlockComment = false;
      }
      while (true) {
        int start = line.indexOf("/*");
        if (start < 0) {
          break;
        }
        int end = line.indexOf("*/", start + 2);
        if (end < 0) {
          line = line.substring(0, start);
          inBlockComment = true;
          break;
        }
        line = line.substring(0, start) + line.substring(end + 2);
      }
      int lineComment = line.indexOf("//");
      if (lineComment >= 0) {
        line = line.substring(0, lineComment);
      }
      out.append(line).append('\n');
    }
    return out.toString();
  }
}
