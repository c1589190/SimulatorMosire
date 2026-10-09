package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.GovGenesisSeedAccess;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>真实世界经济循环体检</b>（本批新增）—— 用户原话：「有没有现有的实际经济循环测试给我看，以验证已有机制中正常？」。
 *
 * <p>★★ <b>这条测试在验什么</b>：在<b>真世界</b>（{@link WorldRegistry#THREE_POWERS}：37 格 / 3 个市场区 / 3 个 GOV / 3
 * 种货币 / 6,350 人）+ <b>真推进</b>（真 {@link Shell} + 真 {@code shell.advanceAndDrain(AdvanceTime)}）上跑 <b>2
 * 个完整产业周期</b>（{@code CYCLE_DAYS = 120} ⇒ 240 天，分 8 段 × 30 天推进），每个关账日输出一行<b>人可读摘要</b>（人口 / 粮 / 布 /
 * 债务本金 / 逐币种货币总量 / 本轮成交笔数 / 未成交归因 top3），并逐段断言下面这 <b>5 组不变量</b>。
 *
 * <p>★ <b>"关账日"在本测试里指什么</b>：段末 = 30 天的倍数 —— 它是 <b>GOV 官署的关账周期</b>（{@code
 * ThreePowersGovBootstrap.OFFICE_CYCLE_DAYS = 30}），同时必然命中市场例行轮（{@code day % 5 == 0}）；其中第 120 / 240
 * 天<b>同时</b>是 产业周期关账日（{@code CYCLE_DAYS = 120}）。⇒ 8 个段末都是"该有结算、该有开市"的日子，用它们当体检取样点。
 *
 * <p>★★ <b>为什么选 {@code three-powers} 而不是 {@code small-world}</b>：<b>①</b> 它有 <b>3 种货币</b>（silver /
 * copper / gold，每区法定币不同）⇒「逐币种货币总量守恒」这条判据在这里<b>非平凡</b>：跨区成交、外汇、跨币种工资任何一处"凭空造币/烧币"都会当场破；
 * 单币种世界里这条断言弱得多（少一个币种就少一类泄漏面）。<b>②</b> 它的 GOV 单位在 {@code economy.RegisterGovernment}
 * 里<b>不声明周期铸币</b> （{@code seignioragePerCycle} 缺省 0），而 {@code small-world} 走的是"demo 世界政府家户"路径、出厂就带
 * {@code GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI = 2,000} 毫/周期<b>真的印钱</b> ⇒
 * 在那个世界上"货币总量不变"<b>本来就不该成立</b>。 <b>③</b> 它是阶段 2 判据 G1 指定的验收世界（3 区 / 3 币 / 辖区两两不相交），不是为这条测试临时造的夹具。
 *
 * <p>★★ <b>推进口径（为什么 30 天一段，不是一天一步）</b>：{@code
 * docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md} §12–§13 实测：单步推进 485 ms/天 vs
 * 分段推进 48 ms/天（<b>10 倍</b>），因为每提交一次 revision 就要重建/落盘一次整份状态的派生差量。 故本测试一律 <b>30 天一段</b>（{@code
 * AdvanceTime} 原生支持 {@code from→to} 多天，内部仍逐日结算），只在段末读一次状态。
 *
 * <p>★★ <b>被断言的不变量清单（每条都是"机制正常"的判据，不是"跑出来的数字"）</b>：
 *
 * <ol>
 *   <li><b>①货币守恒（最硬）</b>：<b>逐币种</b>的货币总量 = Σ 全部账户（家户 / 组织 / 国库 / 单位）在该币种上的余额，<b>全程与创世基线逐值相同</b>。
 *       理由：本仓货币只有三个合法来源（创世 {@code INITIAL_ENDOWMENT} 发行记录、GM 的 {@code RecordMoneyIssuance}、政府的周期铸币
 *       {@code seignioragePerCycle}），而 {@code three-powers}
 *       创世后<b>没有任何一个</b>在跑；一切经济行为（成交、借还、税、工资、迁移、跨区结算）都必须是 <b>余额转移</b>。⇒ 任何一处"凭空造币 / 静默烧币"都会在这里当场红。★
 *       这条也顺带守住"货款腿与货腿原子"（只动钱不动物、或只动物不动钱，会先破别的判据）。
 *   <li><b>②非负</b>：人口 &gt; 0、粮存量 ≥ 0、布存量 ≥ 0、债务本金 ≥ 0。理由：库存与债务本金都是<b>存量</b>，负数是"透支"这种本仓明令不存在的语义
 *       （{@code HouseholdInventory} 构造期就拒负）；而人口为 0 意味着世界死了。
 *   <li><b>③人口不爆不灭</b>：每个关账日人口 &gt; 0，且相邻两段（30 天）人口的比值落在 <b>[1/2, 2]</b> 内。理由：30 天尺度上人口只由生死率
 *       （ppm/tick 量级）与迁移改变，一个 6,350 人的世界在 30 天内减半或翻倍<b>不是"机制正常"</b>，而是出生/死亡/迁移结算坏了；"数量级跳变"的字面判据是
 *       ×10，这里取 ±50% 是更保守的写法（真要跳变必先破这一条）。★ 不写死具体人数：那会把测试变成快照。
 *   <li><b>④市场在运转</b>：每个关账日必须读到<b>当日</b>的市场报告（{@code report.day() == 当日 tick}）且成交笔数 &gt; 0。理由：市场调度是
 *       "绝对日 {@code day % 5 == 0} 例行开市 + 关账日保底 + 低库存追加"（见 {@link
 *       io.mosire.simos.economy.time.MarketSettlement} 的 {@code triggerFor}）， 30 天的倍数必然命中例行轮 ⇒ "报告日
 *       ≠ 当日"或"成交 0 笔"都说明市场<b>冻结</b>了 —— 这正是本仓历史缺陷（市场冻结 / 借贷不触发）复发时的第一征兆， 这条专防它复发。★ 要求"报告日 ==
 *       当日"而不是"最近一轮"，是为了排除"读到 25 天前的旧报告"这种假绿。
 *   <li><b>⑤账目闭合</b>：每个段末 {@code state.meta().timestamp().tick()} 必须等于该段的目标 tick（无静默跳过）；head
 *       revision <b>严格递增</b>； 段数必须等于 {@code 240 / 30 = 8}（{@code AdvanceTime} 被拒/被吞会让段数或 tick
 *       对不上）。理由：时间推进是本仓唯一的"世界前进"入口， 它静默少走几天，后面所有读数都会错位而没人知道。
 * </ol>
 *
 * <p>★★ <b>读数的口径与边界（如实写）</b>：
 *
 * <ul>
 *   <li>货币总量 / 粮存量 / 布存量 读的是 actor 切片的账户余额（{@code ActorData.accounts()} 的 {@code money()} / {@code
 *       balances()}）—— 那是本仓商品与货币余额的<b>唯一真源</b>；<b>在途货物不算在内</b>（跨区发运中的 {@code ShipmentBatch}
 *       是与账户并列的另一个池子）， 故这里的"粮/布存量"读作<b>账上存量</b>；本测试不断言在途量（那是另一条判据）。
 *   <li>人口读的是 Social 切片（人口/生死的唯一权威：{@code SocialData.households()} 的成员份额之和），不读经济侧的物化视图。
 *   <li>债务本金 = Σ {@code EconomyData.debtContracts().values()} 的 {@code principal()}（连续本金余额，计息并入本金）。
 *   <li>成交笔数与未成交归因来自<b>进程内的</b> {@code MarketReport}（经 {@link MarketReportFeed}，不落盘、重启即失）——
 *       本测试在同一进程内推进后立即读，因此可读；这是它的合法用法。
 * </ul>
 *
 * <p>★★ <b>这条红了怎么办（fail-closed，不许靠放宽判据变绿）</b>：① 若红在<b>货币守恒</b>上 ⇒ 先查"这一批是不是新引入了货币的 发行/回笼"（周期铸币
 * {@code seignioragePerCycle}、GM 的 {@code RecordMoneyIssuance}、税费/拆迁里的销毁）—— 若确实引入，正确的修法是让本测试把
 * <b>发行记录</b>并入等式（{@code Σ账户余额 = Σ INITIAL_ENDOWMENT + Σ FISCAL_ISSUE − Σ
 * WITHDRAWAL}），<b>不是</b>把断言删掉或放宽成区间； 若没有引入发行，那它就是<b>真缺陷</b>（凭空造币/静默烧币），停下来如实报告。② 若红在<b>市场成交 0
 * 笔</b>上 ⇒ 那就是"市场冻结"复发， 同样按缺陷报，不许把这条判据降级成"允许 0 笔"。
 */
class EconomyCycleHealthTest {

  /** 主分支（与 {@link GovZ6WorldFixture#MAIN} 同字面）。 */
  private static final BranchId MAIN = GovZ6WorldFixture.MAIN;

  /** 产业周期（天）：与 {@link EconomySeeder#CYCLE_DAYS} 同源（120）。 */
  private static final long CYCLE_DAYS = EconomySeeder.CYCLE_DAYS;

  /**
   * 每段推进的天数：★ 30 天一段（§12 的 10 倍效率口径），且 30 是关账日（GOV 官署 {@code OFFICE_CYCLE_DAYS = 30}、 也是市场例行轮
   * {@code day % 5 == 0}）。
   */
  private static final long SEGMENT_DAYS = 30L;

  /** 总推进天数 = 2 个完整周期（≥ 用户要求的下限）。 */
  private static final long TOTAL_DAYS = 2L * CYCLE_DAYS;

  /** 段数（构造性等于 {@code TOTAL_DAYS / SEGMENT_DAYS} = 8；末段断言用它守"无静默跳过"）。 */
  private static final int SEGMENTS = (int) (TOTAL_DAYS / SEGMENT_DAYS);

  /** 摘要落盘位置（模块工作目录下的 target/；★ 只是一份给人看的证据，不参与断言）。 */
  private static final Path SUMMARY_FILE =
      Path.of("target", "economy-cycle-invariant", "summary.txt");

  private static final CommodityId GRAIN = new CommodityId(EconomySeeder.COMMODITY_GRAIN);
  private static final CommodityId CLOTH = new CommodityId(EconomySeeder.COMMODITY_CLOTH);

  /** 人口带：相邻两段人口的比值必须落在 [1/2, 2]（理由见类注不变量③；不写死人数）。 */
  private static final long POPULATION_BAND_NUMERATOR = 1L;

  private static final long POPULATION_BAND_DENOMINATOR = 2L;

  /**
   * 本测试世界的 mapId（★ 刻意<b>不用</b>缺省的 {@code Map1}）：进程内的市场报告投递点 {@link MarketReportFeed} 以 <b>mapId
   * 为键</b>，而同一个 surefire JVM 里还有别的世界（其它测试类）也叫 {@code Map1} —— 用自己的 mapId 才不会与它们互相串味（读侧"只在同一 tick
   * 内可信"那条边界）。
   */
  private static final String MAP_ID = "econ-cycle-health";

  @TempDir Path tempDir;

  @Test
  @DisplayName("经济循环体检：three-powers 真世界跑 240 天（2 个周期），逐币种货币守恒 + 市场不冻结")
  void twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive() throws IOException {
    ShellConfig config =
        new ShellConfig(
            tempDir.resolve("econ-cycle"),
            ShellConfig.DEFAULT_CHECKPOINT_INTERVAL,
            0,
            0,
            ShellConfig.DEFAULT_MCP_PATH,
            0,
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

      List<String> summary = new ArrayList<>();
      summary.add(
          "=== 经济循环体检：world="
              + WorldRegistry.THREE_POWERS
              + " mapId="
              + config.mapId()
              + " · "
              + SEGMENTS
              + " 段 × "
              + SEGMENT_DAYS
              + " 天 = "
              + TOTAL_DAYS
              + " 天 = 2 个产业周期（CYCLE_DAYS="
              + CYCLE_DAYS
              + "）===");
      summary.add("单位：粮/布/债/货币 = 毫（最小定点单位）；人口 = 人。不变量①：逐币种货币总量全程 == 创世基线（下方每行应与创世行逐值相同）。");
      appendRow(summary, "创世", genesisReading);

      long previousPopulation = initialPopulation;
      long previousRevision = genesisReading.revision();
      for (int segment = 1; segment <= SEGMENTS; segment++) {
        long to = segment * SEGMENT_DAYS;
        advance(shell, to - SEGMENT_DAYS, to, segment);
        SimulationState state = replay(shell);
        Reading reading = read(state, shell, config.mapId());

        // ── ⑤ 账目闭合：段末 tick 与 revision 单调递增（无静默跳过） ──
        assertThat(reading.tick())
            .as("第 %d 段：段末 tick 必须等于目标 tick（时间推进被吞会在这里现形）", segment)
            .isEqualTo(to);
        assertThat(reading.revision())
            .as("第 %d 段：head revision 必须严格递增（前值 %d）", segment, previousRevision)
            .isGreaterThan(previousRevision);
        previousRevision = reading.revision();

        appendRow(summary, "段" + segment, reading);
        writeSummary(summary);

        // ── ② 非负 ──
        assertThat(reading.population()).as("第 %d 段（tick=%d）：人口必须 > 0", segment, to).isPositive();
        assertThat(reading.grainMilli())
            .as("第 %d 段（tick=%d）：粮存量不得为负（账上存量）", segment, to)
            .isNotNegative();
        assertThat(reading.clothMilli())
            .as("第 %d 段（tick=%d）：布存量不得为负（账上存量）", segment, to)
            .isNotNegative();
        assertThat(reading.debtPrincipalMilli())
            .as("第 %d 段（tick=%d）：债务本金不得为负", segment, to)
            .isNotNegative();

        // ── ① 货币守恒（最硬一条）：逐币种与创世基线逐值相同 ──
        assertThat(reading.moneyByCurrency())
            .as(
                "第 %d 段（tick=%d）：逐币种货币总量必须与创世基线逐值相同（凭空造币/静默烧币都会在这里现形）。" + "创世=%s 本段=%s",
                segment, to, moneyText(genesisMoney), reading.moneyTotalsText())
            .isEqualTo(genesisMoney);

        // ── ③ 人口不爆不灭：>0（上面已断）且相邻段比值在 [1/2, 2] ──
        assertThat(reading.population() * POPULATION_BAND_DENOMINATOR)
            .as(
                "第 %d 段（tick=%d）：人口不得暴跌（下界 1/2）：上段=%d 本段=%d",
                segment, to, previousPopulation, reading.population())
            .isGreaterThanOrEqualTo(previousPopulation * POPULATION_BAND_NUMERATOR);
        assertThat(reading.population())
            .as(
                "第 %d 段（tick=%d）：人口不得暴涨（上界 2 倍）：上段=%d 本段=%d",
                segment, to, previousPopulation, reading.population())
            .isLessThanOrEqualTo(previousPopulation * 2L);
        assertThat(reading.population() * POPULATION_BAND_DENOMINATOR)
            .as(
                "第 %d 段（tick=%d）：人口不得相对创世暴跌（下界 1/2）：创世=%d 本段=%d",
                segment, to, initialPopulation, reading.population())
            .isGreaterThanOrEqualTo(initialPopulation * POPULATION_BAND_NUMERATOR);
        assertThat(reading.population())
            .as(
                "第 %d 段（tick=%d）：人口不得相对创世暴涨（上界 2 倍）：创世=%d 本段=%d",
                segment, to, initialPopulation, reading.population())
            .isLessThanOrEqualTo(initialPopulation * 2L);
        previousPopulation = reading.population();

        // ── ④ 市场在运转：当日有报告、且成交笔数 > 0（"市场冻结"防线） ──
        assertThat(reading.market())
            .as("第 %d 段（关账日 tick=%d）：必须读到当日的市场报告 —— 读不到就是市场没有开市（冻结）", segment, to)
            .isPresent();
        MarketReport report = reading.market().orElseThrow();
        assertThat(report.day())
            .as(
                "第 %d 段（关账日 tick=%d）：读到的市场报告必须是当日轮次（报告日=%d ⇒ 否则是旧报告，等于当天没开市）",
                segment, to, report.day())
            .isEqualTo(to);
        assertThat(report.fills())
            .as(
                "第 %d 段（关账日 day=%d，trigger=%s）：成交笔数必须 > 0 —— 0 笔就是历史缺陷「市场冻结」复发（未成交归因：%s）",
                segment, to, report.trigger(), reading.unfilledTop3Text())
            .isNotEmpty();
      }

      // ── ⑤ 账目闭合（收口）：段数 = 段数 ──
      assertThat(summary)
          .as("摘要行数 = 1 表头 + 1 说明 + 1 创世 + %d 段（少一行说明有段被静默跳过）", SEGMENTS)
          .hasSize(2 + 1 + SEGMENTS);

      summary.add(
          "=== 体检通过："
              + SEGMENTS
              + " 段 / "
              + TOTAL_DAYS
              + " 天，5 组不变量全部成立（货币守恒 · 非负 · 人口不爆不灭 · 市场在运转 · 账目闭合）===");
      writeSummary(summary);
      summary.forEach(System.out::println);
    }
  }

  // ── 推进 ────────────────────────────────────────────────────────────────────────────

  /** 真推进：把 {@code from → to}（含）一次交给真 {@code AdvanceTime}（★ 多天一段，内部仍逐日结算）。 */
  private static void advance(Shell shell, long from, long to, int segment) {
    long head = head(shell);
    shell.advanceAndDrain(
        new AdvanceTime(
            "cmd-econ-cycle-" + segment,
            "corr-econ-cycle-" + segment,
            "econ-cycle",
            MAIN,
            new RevisionId(head),
            new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  /** 当前 head 的完整状态（真 replay，不是内存引用）。 */
  private static SimulationState replay(Shell shell) {
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(head(shell))));
  }

  private static long tickOf(SimulationState state) {
    return state.meta().timestamp().tick();
  }

  // ── 读数 ────────────────────────────────────────────────────────────────────────────

  /** 一个关账日的体检读数（全部来自真状态快照 + 进程内市场报告）。 */
  private record Reading(
      long tick,
      long revision,
      long population,
      long grainMilli,
      long clothMilli,
      long debtPrincipalMilli,
      Map<CurrencyId, Long> moneyByCurrency,
      Optional<MarketReport> market) {

    String moneyTotalsText() {
      StringBuilder text = new StringBuilder();
      for (Map.Entry<CurrencyId, Long> entry : moneyByCurrency.entrySet()) {
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(entry.getKey().value())
            .append('=')
            .append(String.format("%,d", entry.getValue()));
      }
      return text.toString();
    }

    /** 未成交归因 top3（按笔数降序、笔数同取数量降序、再取原因字典序 —— 与输入序无关）。 */
    String unfilledTop3Text() {
      if (market.isEmpty()) {
        return "读不到市场报告";
      }
      Map<MarketUnfilledReason, long[]> byReason = new LinkedHashMap<>();
      for (MarketReport.Unfilled unfilled : market.orElseThrow().unfilled()) {
        long[] cell = byReason.computeIfAbsent(unfilled.reason(), reason -> new long[2]);
        cell[0]++;
        cell[1] += unfilled.quantity();
      }
      if (byReason.isEmpty()) {
        return "无未成交";
      }
      List<Map.Entry<MarketUnfilledReason, long[]>> ranked = new ArrayList<>(byReason.entrySet());
      ranked.sort(
          Comparator.<Map.Entry<MarketUnfilledReason, long[]>>comparingLong(
                  entry -> entry.getValue()[0])
              .reversed()
              .thenComparing(
                  Comparator.<Map.Entry<MarketUnfilledReason, long[]>>comparingLong(
                          entry -> entry.getValue()[1])
                      .reversed())
              .thenComparing(entry -> entry.getKey().value()));
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < Math.min(3, ranked.size()); i++) {
        Map.Entry<MarketUnfilledReason, long[]> entry = ranked.get(i);
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(entry.getKey().value())
            .append('×')
            .append(entry.getValue()[0])
            .append("笔/")
            .append(String.format("%,d", entry.getValue()[1]))
            .append("毫");
      }
      return text.toString();
    }
  }

  private static Reading read(SimulationState state, Shell shell, String mapId) {
    long tick = tickOf(state);
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    return new Reading(
        tick,
        head(shell),
        population(GovZ6WorldFixture.socialSlice(state)),
        commodityTotal(actor, GRAIN),
        commodityTotal(actor, CLOTH),
        debtPrincipal(economy),
        moneyByCurrency(actor),
        MarketReportFeed.last(mapId, tick));
  }

  /** 人口 = Social 切片全部家户的成员份额之和（Social 是人口/生死的唯一权威）。 */
  private static long population(SocialData social) {
    long total = 0L;
    for (Household household : social.households().values()) {
      for (long members : household.members().values()) {
        total = Math.addExact(total, members);
      }
    }
    return total;
  }

  /** 商品账上存量 = Σ 全部账户在该商品上的余额（actor 切片的 {@code balances} 是唯一真源；不含在途）。 */
  private static long commodityTotal(ActorData actor, CommodityId commodity) {
    long total = 0L;
    for (HouseholdInventory account : actor.accounts().values()) {
      total = Math.addExact(total, account.balances().getOrDefault(commodity, 0L));
    }
    return total;
  }

  /** 逐币种货币总量 = Σ 全部账户在该币种上的余额（键序按币种字面量排序 ⇒ 打印与比较都稳定）。 */
  private static Map<CurrencyId, Long> moneyByCurrency(ActorData actor) {
    Map<CurrencyId, Long> totals = new TreeMap<>(Comparator.comparing(CurrencyId::value));
    for (HouseholdInventory account : actor.accounts().values()) {
      for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return totals;
  }

  /** 债务本金 = Σ 全部债务契约的连续本金余额（计息并入本金，故它不是"原始借款额"）。 */
  private static long debtPrincipal(EconomyData economy) {
    long total = 0L;
    for (DebtContract contract : economy.debtContracts().values()) {
      total = Math.addExact(total, contract.principal());
    }
    return total;
  }

  // ── 摘要 ────────────────────────────────────────────────────────────────────────────

  /** 逐段一行（★ 按关账日打印，不是逐 tick）。 */
  private static void appendRow(List<String> summary, String label, Reading reading) {
    String marketText;
    if (reading.market().isEmpty()) {
      marketText = "市场=读不到报告";
    } else {
      MarketReport report = reading.market().orElseThrow();
      marketText =
          "市场 第"
              + report.day()
              + "日("
              + report.trigger()
              + ") 成交="
              + report.fills().size()
              + "笔 未成交top3=["
              + reading.unfilledTop3Text()
              + "]";
    }
    summary.add(
        String.format(
            "[%-4s] tick=%-3d rev=%-4d 人口=%,d 粮=%,d毫 布=%,d毫 债务本金=%,d毫 | 币: %s | %s",
            label,
            reading.tick(),
            reading.revision(),
            reading.population(),
            reading.grainMilli(),
            reading.clothMilli(),
            reading.debtPrincipalMilli(),
            reading.moneyTotalsText(),
            marketText));
  }

  private static String moneyText(Map<CurrencyId, Long> money) {
    StringBuilder text = new StringBuilder();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      if (text.length() > 0) {
        text.append(' ');
      }
      text.append(entry.getKey().value())
          .append('=')
          .append(String.format("%,d", entry.getValue()));
    }
    return text.toString();
  }

  /** 摘要落盘（给人看的证据；失败时也留得住 —— 每段都重写一次）。 */
  private static void writeSummary(List<String> summary) throws IOException {
    Files.createDirectories(SUMMARY_FILE.getParent());
    Files.writeString(
        SUMMARY_FILE,
        String.join(System.lineSeparator(), summary) + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }
}
