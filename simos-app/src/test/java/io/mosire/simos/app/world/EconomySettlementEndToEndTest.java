package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.EconomyTimeParticipant;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **R3a 日结算 + R4a 周期收获与分配**的端到端验收：真 {@link EconomyCodec} + 真 {@link EconomyTimeParticipant} + 真
 * store + {@link EconomyTestWorld}（5 格），全部经 {@code CoreSimos.submit(AdvanceTime)} 逐日推进。断言**逐值**。
 *
 * <p>★ 判据（用户点名 a~g）：日耗 / 缺口借粮 / 周期收获 / 守恒 / 多格独立 / 等价性 / **饿死惩罚**。
 *
 * <p>★ 夹具账面（{@link EconomyTestWorld}）：(0,0) 平原 1000 人 3,100 亩 / (1,0) 低丘 500 人 2,064 亩 / (2,0) 只有城市
 * 300 人 / (3,0) 平原 1000 人（有粮可借）/ (4,0) 平原 1000 人（无粮可借）。
 */
class EconomySettlementEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 消费口径（毫粮/人·日）：取结算侧的公开常量（§十，83）。 */
  private static final long DAILY_MILLI = EconomySettlement.DAILY_GRAIN_MILLI_PER_PERSON;

  private static final IndustryId FARM_0 = IndustryHexKeys.id(EconomySeeder.FARM, 0, 0);
  private static final IndustryId FARM_1 = IndustryHexKeys.id(EconomySeeder.FARM, 1, 0);
  private static final IndustryId FARM_3 = IndustryHexKeys.id(EconomySeeder.FARM, 3, 0);
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
  private static final ClassSlotId RICH = new ClassSlotId("rich");
  private static final CommodityId GRAIN = EconomyTestWorld.GRAIN;

  /**
   * (0,0) 平原格收获净产（毫粮）：由 {@link EconomySeeder} 的**标定常量**推出，故标定值一改它自动跟随 （v2 spec §10.3 定案 A：3,100 亩 ×
   * 67 粮/亩 × 1000 × 0.85 = 176,545,000）。
   *
   * <p>★ 绝对值的判别力由 `EconomySeederTest.realScaleHexIsSelfSufficientWithinTheCalibratedBand` 承担
   * （那里的自给率是硬编码区间）；这里只要"收获确实按公式发生"。
   */
  private static final long PLAINS_HARVEST_NET =
      EconomySeeder.MU_PER_HEX * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L * 850L / 1000L;

  /**
   * (0,0) 平原格的毛产（毫粮）：**独立定义**（= 亩数 × 亩产 × 1000 毫粮/粮），不由净产反推 —— 反推会把 0.85 抄两遍，
   * 生产消耗率一改就有两个地方要同步，而"毛产"本该只有一个定义。
   */
  private static final long PLAINS_HARVEST_GROSS =
      EconomySeeder.MU_PER_HEX * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L;

  @TempDir Path tempDir;

  // ── (a) 日耗：推进 1 天 ⇒ 每格粮库存减少 人口 × 83 毫粮；推进 N 天 ⇒ N× ──────────────────

  @Test
  void oneDayConsumesPopulationTimesEightyThreePerHex() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      advance(core, 1);
      EconomyData after = economy(core);

      assertThat(hexDecrease(before, after, 0, 0)).as("(0,0) 1000 × 83").isEqualTo(83_000L);
      assertThat(hexDecrease(before, after, 1, 0)).as("(1,0) 500 × 83").isEqualTo(41_500L);
      assertThat(hexDecrease(before, after, 2, 0)).as("(2,0) 300 × 83").isEqualTo(24_900L);
      assertThat(hexDecrease(before, after, 3, 0))
          .as("(3,0) 1000 × 83（地主缺口由富农借粮补上 ⇒ 全格仍恰好吃掉 1000 × 83）")
          .isEqualTo(83_000L);
      assertThat(hexDecrease(before, after, 4, 0))
          .as("(4,0) 无粮可借 ⇒ 只吃掉 83000 − 50×83 = 78,850（未满足的部分不凭空造粮）")
          .isEqualTo(78_850L);

      assertThat(totalPopulation(after)).as("推进不改人口").isEqualTo(totalPopulation(before));
    }
  }

  @Test
  void nDaysConsumeNTimesTheDailyAmountPerHex() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      advance(core, 5);
      EconomyData after = economy(core);

      assertThat(hexDecrease(before, after, 0, 0)).as("5 × 1000 × 83").isEqualTo(5L * 83_000L);
      assertThat(hexDecrease(before, after, 1, 0)).as("5 × 500 × 83").isEqualTo(5L * 41_500L);
    }
  }

  // ── (b) 缺口：有粮可借 ⇒ 出现 Debt；无粮可借 ⇒ 不产生债务 ─────────────────────────────

  @Test
  void deficitWithALenderCreatesExactlyOneDebtAndWithoutALenderCreatesNone() {
    try (CoreSimos core = freshCore()) {
      advance(core, 1);
      EconomyData after = economy(core);

      // (3,0)：地主（50 人）缺 50 × 83 = 4150；富农当日有余粮 ⇒ 恰好一条实物债。
      ClassKey landlordKey = new ClassKey(FARM_3, LANDLORD);
      ClassRow landlord = after.classes().get(landlordKey);
      assertThat(landlord.debts()).as("地主背上一条债务").hasSize(1);
      Debt debt = after.debts().get(landlord.debts().get(0));
      assertThat(debt.principal()).as("本金 = 缺口 = 50 × 83").isEqualTo(4_150L);
      assertThat(debt.debtor()).isEqualTo(landlordKey);
      assertThat(debt.creditor()).as("债权人 = 同格有粮的富农").isEqualTo(new ClassKey(FARM_3, RICH));
      assertThat(debt.commodity()).as("实物债（粮）").contains(GRAIN);
      assertThat(debt.ratePerMillePerCycle()).as("每周期 20‰").isEqualTo(20);
      assertThat(debt.dueCycle()).as("到期周期 = 当前周期 + 1 = 2").isEqualTo(2L);
      assertThat(after.debts()).as("整场只此一条债").hasSize(1);

      // (4,0)：地主同样缺，但其余各行恰好吃干 ⇒ 无人可借 ⇒ 一条债都没有。
      assertThat(
              after
                  .classes()
                  .get(new ClassKey(IndustryHexKeys.id(EconomySeeder.FARM, 4, 0), LANDLORD))
                  .debts())
          .as("无粮可借 ⇒ 不产生债务（也不凭空造粮）")
          .isEmpty();
    }
  }

  // ── (c) 周期收获：第 120 天一次性产粮，progressDays 归零、lastClosedCycle +1 ─────────────

  @Test
  void dayOneHundredTwentyHarvestsOnceAndResetsTheCycle() {
    try (CoreSimos core = freshCore()) {
      advance(core, 119);
      EconomyData beforeHarvest = economy(core);
      assertThat(hexGrain(beforeHarvest, 0, 0))
          .as("第 119 天 (0,0) 已吃完（按阶层配的储备 = 该格 65 天口粮，第 66 天见底）")
          .isZero();
      assertThat(farm(beforeHarvest, FARM_0).progressDays()).isEqualTo(119L);
      assertThat(farm(beforeHarvest, FARM_0).cycleLaborMilli())
          .as("周期劳动累计 = 119 天 × 498,800 千分劳动")
          .isEqualTo(119L * 498_800L);

      advance(core, 1);
      EconomyData afterHarvest = economy(core);

      // 实际投入亩 = min(可用 3,100 亩, 平均劳动 498,800 × LAND_MU_PER_LABOR(7) / 1000 = 3,491 亩)
      //   = 3,100 亩（**土地**是瓶颈）。★ 这里的 7 是 EconomySettlement.LAND_MU_PER_LABOR（亩/千分劳动），
      //   与亩产 67 无关 —— 两者在 v1 都是 7，改标定后只剩前者是 7，别混。
      // 毛产 = 3,100 × 67 粮/亩 × 1000 毫粮/粮；生产消耗 15% ⇒ 净 85%。
      assertThat(hexGrain(afterHarvest, 0, 0))
          .as("一次性产粮：库存跃升为净产出（3,100 亩 × 67 × 1000 × 0.85）")
          .isEqualTo(PLAINS_HARVEST_NET);
      assertThat(
              sumHarvestIncome(afterHarvest, FARM_0) - harvestProductionLoss(afterHarvest, FARM_0))
          .as("Σ行得（净）= 剩余产出（分配残差按槽位 id 序补足，不丢总量）")
          .isEqualTo(PLAINS_HARVEST_NET);
      assertThat(harvestProductionLoss(afterHarvest, FARM_0))
          .as("15% 生产消耗明文记入本期流水（不静默丢弃）")
          .isEqualTo(PLAINS_HARVEST_GROSS - PLAINS_HARVEST_NET);
      assertThat(sumHarvestIncome(afterHarvest, FARM_0))
          .as("流水所得记毛产出 = 净 + 生产消耗")
          .isEqualTo(PLAINS_HARVEST_GROSS);

      assertThat(farm(afterHarvest, FARM_0).progressDays()).as("progressDays 归零").isZero();
      assertThat(farm(afterHarvest, FARM_0).cycleLaborMilli()).as("周期累计清零").isZero();
      assertThat(afterHarvest.meta().orElseThrow().lastClosedCycle())
          .as("lastClosedCycle +1")
          .hasValue(1L);
    }
  }

  // ── (d) 守恒：粮（记账后）与人口在推进前后守恒 ─────────────────────────────────────────

  @Test
  void grainAndPopulationAreConservedAcrossADailyStepAndAHarvest() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      advance(core, 1);
      assertConserved(before, economy(core), "第 1 天（纯日耗）");

      advance(core, 118);
      EconomyData beforeHarvest = economy(core);
      advance(core, 1);
      assertConserved(beforeHarvest, economy(core), "第 120 天（日耗 + 收获 + 饿死）");
    }
  }

  // ── (e) 多格独立：人口不同 ⇒ 日耗不同、收获也不同 ─────────────────────────────────────

  @Test
  void adjacentHexesDifferByPopulationAndEachMatchesItsOwnFormula() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      advance(core, 1);
      EconomyData afterOneDay = economy(core);

      long decrease0 = hexDecrease(before, afterOneDay, 0, 0);
      long decrease1 = hexDecrease(before, afterOneDay, 1, 0);
      assertThat(decrease0).as("(0,0) = 1000 × 83").isEqualTo(1_000L * DAILY_MILLI);
      assertThat(decrease1).as("(1,0) = 500 × 83").isEqualTo(500L * DAILY_MILLI);
      assertThat(decrease0).as("两格日耗必须不相等（否则 isEqualTo 可能恒真）").isNotEqualTo(decrease1);

      advance(core, 119);
      EconomyData afterHarvest = economy(core);
      assertThat(hexGrain(afterHarvest, 0, 0))
          .as("(0,0) 平原格：净产按标定公式")
          .isEqualTo(PLAINS_HARVEST_NET);
      // ★ 低丘格的绝对值不写死：标定后它的实际投入亩由**劳动瓶颈**（而非土地）决定 ——
      //   可用 2,064 亩，但劳动可经营亩数 = avgLabor(249,400) × LAND_MU_PER_LABOR(7) / 1000 = 1,745 亩 ⇒ 取小得
      // 1,745。
      //   闭式 = 1,745 亩 × 67 × 1000 × 0.85 = 99,377,750 毫粮，但它把 EconomySettlement 的劳动模型内部
      //   （LAND_MU_PER_LABOR、580‰ 人均劳动、阶层参与率）搬进了本用例 ⇒ 标定一改就跟着碎。
      // ★ 诚实标注这条的**实际**判别力：它只挡"低丘格颗粒无收/两格用了同一条公式"这类粗错
      //   —— 注意它**挡不住**地形系数失效（低丘土地即便按平原算，劳动瓶颈仍给出 1,745 亩 ≠ 平原的 3,100 亩，两格照样不等）。
      //   地形系数本身由**精确值**钉在别处：EconomySeederTest.everyHexKeepsItsLandAndLandFollowsTerrainCoefficient
      //   与 WorldgenInitializeToolTest 的 Σ土地 == 408,127,400 千分亩。
      // ★ (1,0) 低丘格：**劳动是瓶颈，不是土地** ——
      //   可用亩 = 3,100,000 千分亩 × 666‰ ÷ 1000 = 2,064 亩；
      //   但 500 人 × 580‰ = 290,000 千分劳动，投入率按阶层加权 860‰ ⇒ 日劳动 249,400 千分劳动；
      //   可经营 = 249,400 × LAND_MU_PER_LABOR(7) ÷ 1000 = 1,745 亩 < 2,064 ⇒ 实际投入 1,745 亩。
      //   毛产 = 1,745 × 67 × 1000 = 116,915,000；扣 15% ⇒ 净 99,377,750。
      assertThat(hexGrain(afterHarvest, 1, 0))
          .as("(1,0) 低丘格：净产按**劳动瓶颈**算（不是按地）")
          .isEqualTo(99_377_750L);
      assertThat(hexGrain(afterHarvest, 0, 0))
          .as("两格收获必须不相等（地形系数与劳动瓶颈都在起作用）")
          .isNotEqualTo(hexGrain(afterHarvest, 1, 0));
    }
  }

  // ── (f) §十一 等价性（本轮核心护栏）：一次 N 天 == N 次单日，**硬断言终态逐值相等** ─────────────

  /**
   * ★★ **§十一 等价性护栏**：同一份创世，一份世界 {@code advance(0 → 150)} **一次**，另一份走 **150 次单日** advance ⇒
   * **两份终态逐值相同**（{@link EconomyData} 整个 record 相等：产业进度/周期劳动、队伍库存/货币/债务、 债务表、流水、meta 全在内）。
   *
   * <p>★ 150 天刻意跨过第 **120** 天那个周期末：逐日循环必须**照样在那一天收获 + 分配**（天数是绝对日，不是"推进次数"）。 判别力：若 {@code
   * EconomySettlement} 把一次 N 天压成"只按 to 结算一天"（或逐日循环只跑第一天）⇒ 第 120 天不收获、 逐日消费与债务都不对 ⇒ 本条红。
   */
  @Test
  void oneHundredFiftyDaysInOneCommandEqualsOneHundredFiftyDailySteps() throws Exception {
    Path onceDir = tempDir.resolve("once");
    Path dailyDir = tempDir.resolve("daily");
    java.nio.file.Files.createDirectories(onceDir);
    java.nio.file.Files.createDirectories(dailyDir);

    try (CoreSimos once = freshCoreAt(onceDir);
        CoreSimos daily = freshCoreAt(dailyDir)) {
      long head = once.head(MAIN).orElseThrow().value();
      CommandResult oneShot =
          once.submit(
              new AdvanceTime(
                  "cmd-once",
                  "corr-once",
                  "player:test",
                  MAIN,
                  new RevisionId(head),
                  new TimeRange(SimosTimestamp.of(0), Optional.of(SimosTimestamp.of(150)))));
      assertThat(oneShot)
          .as("一次 150 天 ⇒ 恰落一条 revision")
          .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));

      advance(daily, 150); // 另一份：150 次单日（每次用上一条的新 revision）

      EconomyData onceData = economy(once);
      EconomyData dailyData = economy(daily);

      // ★★ 唯一证据：两份终态**逐值相同**（不是"都没报错"）。
      assertThat(onceData)
          .as("§十一：advance(0→150) 的终态 == 150 次单日 advance 的终态（覆盖第 120 天收获/逐日消费/债务）")
          .isEqualTo(dailyData);
      // ★ 流水**也纳入**这份终态比较，且非平凡：150 天的发生额逐日累加（不是"两边都只留最后一天"的平凡相等）。
      assertThat(onceData.flows()).as("一次 150 天与 150 次单日的流水逐值相同").isEqualTo(dailyData.flows());
      assertThat(flowConsumed(onceData)).as("流水已跨日累加（远大于一天的口粮）").isGreaterThan(10L * DAILY_MILLI);

      // ★ 再钉一条可读的绝对日数字：150 天只跨过第 120 天一次 ⇒ 关账周期 1、周期进度 30。
      assertThat(onceData.meta().orElseThrow().lastClosedCycle())
          .as("150 天跨过第 120 天那个周期末 ⇒ 关账周期序号 1")
          .hasValue(1L);
      assertThat(farm(onceData, FARM_0).progressDays())
          .as("第 120 天收获后进入第 2 周期，又走 30 天")
          .isEqualTo(30L);
      assertThat(farm(dailyData, FARM_0).progressDays()).isEqualTo(30L);
    }
  }

  // ── (g) 饿死：第一周期末出现死亡 ⇒ 第二周期需求随之下降 ───────────────────────────────

  /**
   * ★★ **饿死惩罚的端到端验收（用户 2026-09-25 点名）**：(0,0) 1000 人按阶层配储备 = 该格 **65 天**口粮 ⇒ 第 66~120 天全缺 ⇒
   * **第一周期末（第 120 天）出现死亡**；第二周期人口少了 ⇒ {@code 需求 = 人口 × 83 × 120} 随之下降。多格（本夹具 5 格）各自独立结算。
   *
   * <p>★ 第一周期字面量（(0,0) 四行 450/350/150/50）：缺 55 天 ⇒ 每行 {@code faminePerMille = 55/120 = 458‰}，死亡
   * 41/32/13/4 = **90**；人口 1000 → 910。
   *
   * <p>★ **量纲标定之后第二周期不再饿死**：第 120 天的收获净产 176,545 粮/格 **远超**第二周期需求 9,063.6 粮/格（910 人 × 83 × 120）⇒
   * 青黄不接到此结束。标定前（1,000 亩 × 7 粮/亩）收获只够约 4% 的需求 ⇒ 那里 `deaths2` 必 > 0、本条必红。
   *
   * <p>★ 判别力：把 {@code FAMINE_MORTALITY_PER_MILLE} 当 0 用 ⇒ 第 1 周期死亡 0、人口不变 ⇒ 本条红（配套纯函数算例在 {@code
   * EconomySettlementTest}）。
   */
  @Test
  void theFirstLeanSeasonStarvesButTheCalibratedHarvestEndsIt() {
    try (CoreSimos core = freshCore()) {
      EconomyData initial = economy(core);
      long need1 = hexNeed(initial, 0, 0);

      advanceRange(core, 0L, 120L); // 一次推进到第 1 个周期末（§十一 等价性由 (f) 单独守）
      EconomyData cycle1 = economy(core);

      long deaths1 = hexDeaths(cycle1, 0, 0);
      long population1 = hexPopulation(cycle1, 0, 0);
      assertThat(deaths1).as("(0,0) 第一周期末饿死 90 人").isEqualTo(90L);
      assertThat(population1).as("人口 1000 → 910").isEqualTo(910L);
      assertThat(hexNeed(cycle1, 0, 0))
          .as("第二周期需求 = 减少后的人口 × 83 × 120")
          .isEqualTo(910L * 83L * 120L);
      assertThat(hexNeed(cycle1, 0, 0)).as("第二周期需求 < 第一周期需求（人口少了）").isLessThan(need1);

      advanceRange(core, 120L, 240L); // 到第 2 个周期末
      EconomyData cycle2 = economy(core);
      long deaths2 = hexDeaths(cycle2, 0, 0) - deaths1;
      // ★ 量纲标定（v2 spec §10.3 定案 A）之后：收获净产 176,545 粮/格 **远超**需求 9,960 粮/格 ⇒
      //   **青黄不接已过**，第二周期不再饿死 —— 这正是 v1 spec §十 验收判据 3 想要的
      //   "库存曲线：青黄不接 → 收获"。标定前（1,000 亩 × 7 粮/亩）这里必红：收获只够 4% 的需求。
      assertThat(deaths2).as("第二周期不死人：标定后的收获远超需求 ⇒ 青黄不接已过").isZero();
      assertThat(hexPopulation(cycle2, 0, 0)).as("人口不再下降（已恢复）").isEqualTo(population1);
    }
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  private CoreSimos freshCore() {
    return freshCoreAt(tempDir);
  }

  /** 在 {@code dir} 下起一个只装 economy 的真 core（两份世界对比时各用各的目录，避免共用同一个 sqlite 文件）。 */
  private static CoreSimos freshCoreAt(Path dir) {
    CoreSimos core =
        new CoreSimos(new CoreConfig(dir, CHECKPOINT_INTERVAL, SimosObjectMapper.create()));
    core.register(new EconomyCodec());
    core.register(new EconomyTimeParticipant(EconomyTestWorld.MAP_ID));
    core.bootstrapGenesis(EconomyTestWorld.genesis());
    return core;
  }

  /** 逐日推进 {@code days} 天（每次一天、用上一次返回的新 revision——日制裁定）。 */
  private static void advance(CoreSimos core, int days) {
    long head = core.head(MAIN).orElseThrow().value();
    long from = core.replay(new StateRef(MAIN, new RevisionId(head))).meta().timestamp().tick();
    for (int i = 0; i < days; i++) {
      CommandResult result =
          core.submit(
              new AdvanceTime(
                  "cmd-advance-" + from,
                  "corr-advance-" + from,
                  "player:test",
                  MAIN,
                  new RevisionId(head),
                  new TimeRange(
                      SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(from + 1)))));
      assertThat(result)
          .as("推进第 %d 天（tick %d → %d）", i + 1, from, from + 1)
          .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));
      head++;
      from++;
    }
  }

  /** **一次**推进 {@code (from, to]}（§十一：一条 revision；用于跨整个周期的大步，等价性由 (f) 守）。 */
  private static void advanceRange(CoreSimos core, long from, long to) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-advance-" + from,
                "corr-advance-" + from,
                "player:test",
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进 %d 天（tick %d → %d）", to - from, from, to)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));
  }

  private static EconomyData economy(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    EconomySnapshot slice =
        (EconomySnapshot)
            state.module("economy").orElseThrow(() -> new AssertionError("状态里没有 economy 切片"));
    return slice.data();
  }

  private static Industry farm(EconomyData data, IndustryId id) {
    Industry industry = data.industries().get(id);
    if (industry == null) {
      throw new AssertionError("没有产业 " + id);
    }
    return industry;
  }

  // ── 逐格汇总（都经 IndustryHexKeys 认"产业属于哪一格"，不自己拼 id）────────────────────

  private static List<ClassRow> rowsAt(EconomyData data, int q, int r) {
    return IndustryHexKeys.at(data.industries(), q, r).stream()
        .flatMap(id -> classKeysOf(data, id).stream().map(key -> data.classes().get(key)))
        .toList();
  }

  private static List<ClassKey> classKeysOf(EconomyData data, IndustryId industry) {
    return data.classes().keySet().stream()
        .filter(key -> key.industry().equals(industry))
        .sorted(Comparator.comparing(key -> key.slot().value()))
        .toList();
  }

  private static long hexGrain(EconomyData data, int q, int r) {
    return rowsAt(data, q, r).stream().mapToLong(row -> row.goods().getOrDefault(GRAIN, 0L)).sum();
  }

  private static long hexDecrease(EconomyData before, EconomyData after, int q, int r) {
    return hexGrain(before, q, r) - hexGrain(after, q, r);
  }

  /** 某格 Σ 人口。 */
  private static long hexPopulation(EconomyData data, int q, int r) {
    return rowsAt(data, q, r).stream().mapToLong(ClassRow::population).sum();
  }

  /** 某格本周期总需求（毫粮）= Σ 行（人口 × 83 × 该行产业周期天数）。 */
  private static long hexNeed(EconomyData data, int q, int r) {
    long total = 0L;
    for (IndustryId id : IndustryHexKeys.at(data.industries(), q, r)) {
      long cycleDays = data.industries().get(id).cycleDays();
      for (ClassKey key : classKeysOf(data, id)) {
        total += data.classes().get(key).population() * DAILY_MILLI * cycleDays;
      }
    }
    return total;
  }

  /** 某格 Σ 饿死人口（取各行流水的累计 {@code deaths}）。 */
  private static long hexDeaths(EconomyData data, int q, int r) {
    long total = 0L;
    for (IndustryId id : IndustryHexKeys.at(data.industries(), q, r)) {
      for (ClassKey key : classKeysOf(data, id)) {
        FlowRow flow = data.flows().get(key);
        if (flow != null) {
          total += flow.deaths();
        }
      }
    }
    return total;
  }

  private static long totalPopulation(EconomyData data) {
    return data.classes().values().stream().mapToLong(ClassRow::population).sum();
  }

  private static long totalGrain(EconomyData data) {
    return data.classes().values().stream()
        .mapToLong(row -> row.goods().getOrDefault(GRAIN, 0L))
        .sum();
  }

  /** 一整个产业的当日所得合计（毛产出份额）。 */
  private static long sumHarvestIncome(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (ClassKey key : classKeysOf(data, industry)) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        sum += flow.income();
      }
    }
    return sum;
  }

  /**
   * 一整个产业当日记入流水的生产消耗合计 = Σ流水所得 − 该产业各行当日的库存增量。
   *
   * <p>★ 只对"(0,0) 这类收获前库存恰为 0、当日无借贷"的产业成立（该夹具正是如此）——用一个**独立可算**的差来反推消耗， 而不是把 15% 再抄一遍。
   */
  private static long harvestProductionLoss(EconomyData data, IndustryId industry) {
    long stock = 0L;
    for (ClassKey key : classKeysOf(data, industry)) {
      stock += data.classes().get(key).goods().getOrDefault(GRAIN, 0L);
    }
    return sumHarvestIncome(data, industry) - stock;
  }

  /**
   * 守恒（§6.1 的账要平）：粮库存的减少 == **本次推进**的流水消费 − 本次推进的流水所得；**人口**则满足 §六 的逐格守恒 ——{@code Σ人口变化 ==
   * −Σ死亡}（2026-09-25 起：周期末的饿死会让总人口下降，这条取代旧的"人口逐值不变"）。
   *
   * <p>★ 流水**跨日/跨推进累加**（§十一，{@code FlowRow} 是"本期累计发生额"）⇒ 本次推进的发生额取 {@code after.flows() −
   * before.flows()} 的差，而不是 {@code after.flows()} 本身（否则 119 天的累计会把第 120 天的一天账淹没）。
   */
  private static void assertConserved(EconomyData before, EconomyData after, String what) {
    long consumed = flowConsumed(after) - flowConsumed(before);
    long income = flowIncome(after) - flowIncome(before);
    assertThat(totalGrain(before) - totalGrain(after))
        .as("%s：库存减少 == 本期流水消费 − 本期流水所得（损耗/产出都显式落账）", what)
        .isEqualTo(consumed - income);
    long deaths = flowDeaths(after) - flowDeaths(before);
    assertThat(totalPopulation(after))
        .as("%s：Σ人口变化 == −Σ死亡（逐格守恒；未死则人口不变）", what)
        .isEqualTo(totalPopulation(before) - deaths);
  }

  /** Σ 各行的粮消费（毫粮）。 */
  private static long flowConsumed(EconomyData data) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.consumed().getOrDefault(GRAIN, 0L))
        .sum();
  }

  /** Σ 各行的饿死人口（人）。 */
  private static long flowDeaths(EconomyData data) {
    return data.flows().values().stream().mapToLong(FlowRow::deaths).sum();
  }

  /** Σ 各行的流水所得。 */
  private static long flowIncome(EconomyData data) {
    return data.flows().values().stream().mapToLong(FlowRow::income).sum();
  }
}
