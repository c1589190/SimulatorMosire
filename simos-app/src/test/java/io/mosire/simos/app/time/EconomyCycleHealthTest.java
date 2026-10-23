package io.mosire.simos.app.time;

import static io.mosire.simos.app.sim.EconomyCycleReading.SEGMENT_DAYS;
import static io.mosire.simos.app.sim.EconomyCycleReading.advance;
import static io.mosire.simos.app.sim.EconomyCycleReading.appendRow;
import static io.mosire.simos.app.sim.EconomyCycleReading.read;
import static io.mosire.simos.app.sim.EconomyCycleReading.replay;
import static io.mosire.simos.app.sim.EconomyCycleReading.tickOf;
import static io.mosire.simos.app.sim.EconomyCycleReading.writeSummary;
import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.GovGenesisSeedAccess;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.sim.EconomyCycleHealthSim;
import io.mosire.simos.app.sim.EconomyCycleReading.Reading;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.util.state.SimulationState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>真实世界经济循环体检 —— 门禁里的 smoke（批 M 缩编，2026-10-15）</b>。
 *
 * <p>★★ <b>为什么缩</b>：用户原话（逐字，2026-10-15）：「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑，
 * 别塞进测试里！」——原版本在**一个 `@Test` 里推进 240 天（8 段 × 30 天）**，判据 M1 明说「≥2 段 或 ≥60 tick = 违规」； 实测它一个用例吃门禁
 * **85.7s（25.1%）**。整段（连同 96 轮市场分布指纹、8 段逐段断言、W4b 加牙的逐轮扫描） **逐字搬到独立跑法** {@link
 * EconomyCycleHealthSim}（跑 {@code tools/multitick-sim.sh economy-cycle-health}， 独立进程 + 独立 store 落在
 * {@code test-world/multitick/}，不进 {@code clean verify}）。
 *
 * <p>★★ <b>这里留什么</b>：**1 段 = 30 天**（30 tick < 60，且只有 1 个多日段 ⇒ 判据 M1 合规），跑的还是 <b>同一座真世界</b>（{@link
 * WorldRegistry#THREE_POWERS}：3 个市场区 / 3 种货币 / 6,350 人）与真 {@code
 * Shell#advanceAndDrain}。判据只留最硬的三条（都能抓"推进被吞/被拒"与"世界坏了"）：
 *
 * <ol>
 *   <li><b>账目闭合</b>：段末 tick 必须逐值等于 30、head revision 必须严格递增 —— {@code AdvanceTime} 被拒/被吞当场红；
 *   <li><b>货币守恒</b>：<b>逐币种</b>总量与创世基线逐值相同（three-powers 创世后没有任何发行在跑 ⇒ 三币种守恒在这里非平凡）；
 *   <li><b>关口市场开市</b>：day 30 必须读到<b>当日</b>市场报告、且市场确实有订单流（买方订单或卖方报价至少一侧 &gt; 0）； 若**同 hex
 *       同时存在**买单与卖单（{@code sameHexBuySellOverlaps > 0}），则必须成交 &gt; 0 —— 同 hex 有货/有钱/同价却不成交才是"市场冻结"；
 * </ol>
 *
 * <p>★★ <b>2026-10-19 D-032 T1 语义收口（控制方工程裁定，依据目标运行设计 M4/tick contract； 不是用户新裁定）</b>：目标运行设计 M4/tick
 * contract 允许“无可成交供给/无重叠需求时零成交合法”， 这是合法结果，不是市场冻结。旧断言把“所有 0 成交”一律判红，已不符合新语义。现在的精确防线 = ①
 * 当日报告存在（市场开了）+ ② 订单流非空（撮合有输入）+ ③ **仅当同 hex 买卖重叠 &gt; 0** 时才要求 fills 非空。 跨格运力不足导致的未成交由 {@code
 * CapacityTruncationPricingAcceptanceTest} / 市场日志覆盖，不在这里重复当冻结。
 *
 * <p>★ 另有非负 / 人口带两条随段末一起断（与 sim 版同源，读数层共用 {@code EconomyCycleReading}）。 ★ <b>96 轮分布指纹、8
 * 段推进、逐轮到场扫描不在门禁里</b> —— 见 {@link EconomyCycleHealthSim} 的类注与 {@code docs/agents/08-七-门禁与关账命令.md}
 * 的「多 tick 全真模拟的独立跑法」。
 */
class EconomyCycleHealthTest {

  /** 本 smoke 自己的 mapId（与 sim 同字面：进程内市场报告投递点以 mapId 为键，别与别的测试串味）。 */
  private static final String MAP_ID = "econ-cycle-health";

  /** 人口带：段末人口相对创世必须落在 [1/2, 2]（不写死人数，理由同 sim 版类注不变量③）。 */
  private static final long POPULATION_BAND_NUMERATOR = 1L;

  private static final long POPULATION_BAND_DENOMINATOR = 2L;

  /** 摘要落盘位置（模块工作目录下的 target/；★ 只是一份给人看的证据，不参与断言）。 */
  private static final Path SUMMARY_FILE =
      Path.of("target", "economy-cycle-invariant", "smoke-summary.txt");

  @TempDir Path tempDir;

  @Test
  @DisplayName("经济循环体检 smoke：three-powers 真世界 1 段 30 天 —— 账目闭合 + 逐币种货币守恒 + 关口市场开市")
  void oneSegmentInRealThreePowersWorldKeepsMoneyConservedAndMarketOpen() throws IOException {
    ShellConfig config =
        new ShellConfig(
            tempDir.resolve("econ-cycle-smoke"),
            ShellConfig.DEFAULT_CHECKPOINT_INTERVAL,
            0,
            0,
            ShellConfig.DEFAULT_MCP_PATH,
            ShellConfig.DEFAULT_MCP_INITIATOR,
            MAP_ID,
            ShellConfig.DEFAULT_BIND_ADDRESS,
            ShellConfig.DEFAULT_OPENING_SNAPSHOT,
            WorldRegistry.THREE_POWERS);
    try (Shell shell = Shell.start(config)) {
      assertThat(GovGenesisSeedAccess.seed(shell))
          .as("空库创世必须成功（worldId=%s）", WorldRegistry.THREE_POWERS)
          .isTrue();
      SimulationState genesis = replay(shell);
      assertThat(tickOf(genesis)).as("创世 tick").isZero();

      Reading genesisReading = read(genesis, shell, config.mapId());
      // ★ 非空转守卫：货币守恒若在"总量 = 0"的世界里断言，等于什么都没验。
      assertThat(genesisReading.moneyByCurrency().values())
          .as("创世每个币种的货币总量都必须 > 0（否则守恒断言是空转）：实得 %s", genesisReading.moneyTotalsText())
          .isNotEmpty()
          .allMatch(total -> total > 0L);
      assertThat(genesisReading.population()).as("创世人口必须 > 0").isPositive();
      assertThat(genesisReading.moneyByCurrency().keySet())
          .as(
              "three-powers 创世必须有 3 种货币（少一种 ⇒ 守恒覆盖面缩水，须当场可见）：实得 %s",
              genesisReading.moneyTotalsText())
          .hasSize(3);

      long initialPopulation = genesisReading.population();
      Map<CurrencyId, Long> genesisMoney = genesisReading.moneyByCurrency();
      long previousRevision = genesisReading.revision();

      List<String> summary = new ArrayList<>();
      summary.add(
          "=== 经济循环体检 smoke：world="
              + WorldRegistry.THREE_POWERS
              + " mapId="
              + config.mapId()
              + " · 1 段 × "
              + SEGMENT_DAYS
              + " 天（★ 多 tick 重跑 = "
              + EconomyCycleHealthSim.SEGMENTS
              + " 段 / 240 天 / 96 轮指纹走 tools/multitick-sim.sh economy-cycle-health）===");
      summary.add("单位：粮/布/债/货币 = 毫（最小定点单位）；人口 = 人。不变量①：逐币种货币总量全程 == 创世基线。");
      appendRow(summary, "创世", genesisReading);

      // ── 1 段（30 天）真推进 ──
      advance(shell, 0L, SEGMENT_DAYS, 1);
      SimulationState state = replay(shell);
      Reading reading = read(state, shell, config.mapId());

      // ── ⑤ 账目闭合：段末 tick 与 revision 单调递增（无静默跳过） ──
      assertThat(reading.tick())
          .as("段末 tick 必须等于目标 tick %d（时间推进被吞会在这里现形）", SEGMENT_DAYS)
          .isEqualTo(SEGMENT_DAYS);
      assertThat(reading.revision())
          .as("head revision 必须严格递增（前值 %d）", previousRevision)
          .isGreaterThan(previousRevision);

      appendRow(summary, "段1", reading);
      writeSummary(summary, SUMMARY_FILE);

      // ── ② 非负 ──
      assertThat(reading.population()).as("段末（tick=%d）：人口必须 > 0", SEGMENT_DAYS).isPositive();
      assertThat(reading.grainMilli()).as("段末：粮存量不得为负（账上存量）").isNotNegative();
      assertThat(reading.clothMilli()).as("段末：布存量不得为负（账上存量）").isNotNegative();
      assertThat(reading.debtPrincipalMilli()).as("段末：债务本金不得为负").isNotNegative();

      // ── ① 货币守恒（最硬一条）：逐币种与创世基线逐值相同 ──
      assertThat(reading.moneyByCurrency())
          .as(
              "段末（tick=%d）：逐币种货币总量必须与创世基线逐值相同（凭空造币/静默烧币都会在这里现形）。创世=%s 本段=%s",
              SEGMENT_DAYS, genesisMoney, reading.moneyTotalsText())
          .isEqualTo(genesisMoney);

      // ── ③ 人口不爆不灭：段末相对创世落在 [1/2, 2]（30 天尺度上生死/迁移不该让 6,350 人的世界跳变） ──
      assertThat(reading.population() * POPULATION_BAND_DENOMINATOR)
          .as("段末人口不得相对创世暴跌（下界 1/2）：创世=%d 本段=%d", initialPopulation, reading.population())
          .isGreaterThanOrEqualTo(initialPopulation * POPULATION_BAND_NUMERATOR);
      assertThat(reading.population())
          .as("段末人口不得相对创世暴涨（上界 2 倍）：创世=%d 本段=%d", initialPopulation, reading.population())
          .isLessThanOrEqualTo(initialPopulation * 2L);

      // ── ④ 市场在运转：当日有报告 + 订单流非空；同 hex 有重叠时必须成交 ──
      //   ★ 2026-10-19 控制方工程裁定（依据目标运行设计 M4/tick contract；不是用户新裁定）：
      //     零成交在“无可成交供给 / 无重叠需求”时是合法结果；
      //     只有「同 hex 有货、有钱、同价却不成交」才是真正的冻结。
      assertThat(reading.market())
          .as("段末（关账日 tick=%d）：必须读到当日的市场报告 —— 读不到就是市场没有开市（冻结）", SEGMENT_DAYS)
          .isPresent();
      MarketReport report = reading.market().orElseThrow();
      assertThat(report.day())
          .as("段末（关账日 tick=%d）：读到的市场报告必须是当日轮次（报告日=%d ⇒ 否则是旧报告，等于当天没开市）", SEGMENT_DAYS, report.day())
          .isEqualTo(SEGMENT_DAYS);
      // ★ 失败诊断（不改判据）：把两侧订单槽与"同 hex 买卖重叠"一起打进断言消息 ——
      //   "0 笔成交"必须能区分「没有卖方」与「有同 hex 交叉订单却没撮合」。
      long buyerOrders =
          report.buyerOutcomes().stream().filter(outcome -> outcome.orderedQty() > 0L).count();
      long sellerOffers =
          report.sellerOutcomes().stream().filter(outcome -> outcome.offeredQty() > 0L).count();
      java.util.Set<String> buyKeys = new java.util.LinkedHashSet<>();
      for (MarketReport.BuyerOutcome outcome : report.buyerOutcomes()) {
        if (outcome.orderedQty() > 0L) {
          buyKeys.add(outcome.hex() + "|" + outcome.commodity().value());
        }
      }
      long sameHexBuySellOverlaps =
          report.sellerOutcomes().stream()
              .filter(outcome -> outcome.offeredQty() > 0L)
              .map(outcome -> outcome.hex() + "|" + outcome.commodity().value())
              .filter(buyKeys::contains)
              .distinct()
              .count();
      assertThat(buyerOrders + sellerOffers)
          .as(
              "段末（关账日 day=%d，trigger=%s）：市场必须有订单流（买方订单或卖方报价至少一侧 > 0）"
                  + "—— 0 才是“市场没开/撮合没跑”；诊断：buyerOrders=%d sellerOffers=%d unfilled=%d",
              SEGMENT_DAYS, report.trigger(), buyerOrders, sellerOffers, report.unfilled().size())
          .isPositive();
      // ★ 精确冻结防线：**仅当同 hex 同时有可买需求与可卖供给**时，才要求有成交。
      //   同 hex 无重叠（或只有单边订单）时允许 fills=0 —— 目标契约：无供给/无重叠需求是合法结果。
      if (sameHexBuySellOverlaps > 0L) {
        assertThat(report.fills())
            .as(
                "段末（关账日 day=%d，trigger=%s）：同 hex 有 %d 对 (hex,commodity) 买卖重叠却 0 成交"
                    + " —— 同 hex 有货/有钱/同价必须成交（未成交归因：%s；诊断：buyerOrders=%d sellerOffers=%d unfilled=%d）",
                SEGMENT_DAYS,
                report.trigger(),
                sameHexBuySellOverlaps,
                reading.unfilledTop3Text(),
                buyerOrders,
                sellerOffers,
                report.unfilled().size())
            .isNotEmpty();
      }

      summary.add(
          "=== smoke 通过：1 段 / "
              + SEGMENT_DAYS
              + " 天，账目闭合 · 逐币种货币守恒 · 非负 · 人口不爆不灭 · 关口市场开市 五条成立"
              + "（★ 240 天 / 8 段 / 96 轮分布指纹在独立跑法里，见类注） ===");
      writeSummary(summary, SUMMARY_FILE);
      summary.forEach(System.out::println);
    }
  }
}
