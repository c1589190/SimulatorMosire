package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionDayRequest;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-031 P1 §5 V4：独立手算</b>（本段唯一写者，2026-10-16）。
 *
 * <p>判据原文（设计书 §5 V4）：周期未完成、完成、无劳动/缺投入、分配守恒、不同分配规则、实物与铸币、多商品毫单位换算、日劳动与周期劳动量纲；
 * 选择小而可逐笔核算的数据，<b>不从被测代码反算期望</b>。
 *
 * <p>★★ <b>口径（每个期望值都是纸面算式，不是从实现抄回来的）</b>：
 *
 * <pre>
 * 规模 scale = min( ⌊产能_k ÷ capacityPerUnit[k]⌋ , ⌊本周期累计劳动 ÷ cycleDays ÷ laborPerUnit⌋ ,
 *                  ⌊本周期实扣投入_j ÷ cycleInputPerUnit[j]⌋ )      ← 取小后向下取整
 *                  ↑ 劳动那一路的分母是【平均每日】劳动（= 累计 ÷ cycleDays）
 * 毛产 gross_j = scale × outputPerUnit[j] × 1000 毫/单位            ← 商品侧：×1000
 * 损耗 losses_j = gross_j × (FEED_PER_MILLE + DEPRECIATION_PER_MILLE) ÷ 1000 = gross_j × 30‰
 * 净产 net_j   = gross_j − losses_j                                 ← 计提给 operator
 * 铸币 minted  = scale × moneyOutputPerUnit[currency]              ← 货币侧：**不 ×1000**（最小单位就是毫）
 * 实物档实付   = min(应付 fixedAmount, 可用 = net − 已付)
 * 货币档实付   = min(应付 fixedAmount, 可用 = availableMoney[currency] − 已付)
 * </pre>
 *
 * <p>夹具：一格（{@code 0_0}）、一产业（{@code craft}）、一 unit、一户；{@code capacityPerUnit={WORKSHOP:1}}、10 台作坊、
 * 30,000 千分劳动 ⇒ 产能路 {@code 10}、劳动路 {@code 30,000 ÷ laborPerUnit}。
 */
class D031P1HandCalcSettlementTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId CRAFT = IndustryHexKeys.id("craft", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, CRAFT.value());
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(CRAFT, OPERATOR);
  private static final CohortKey VIEW =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.ARTISAN);
  private static final HouseholdId HOUSEHOLD = HouseholdIds.ofLegacy(VIEW);
  private static final ActorRef HOUSEHOLD_ACTOR = HouseholdActors.of(HOUSEHOLD);
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-v4-handcalc");

  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  private static final long WORKSHOPS = 10L;
  private static final long LABOR_MILLI = 30_000L;
  private static final long AMPLE_GRAIN = 1_000_000L;

  // ── ① 周期未完成 / 完成 ─────────────────────────────────────────────────────────────

  @Test
  void aTwoDayCycleYieldsNothingOnDayOneAndTheHandComputedGrossOnDayTwo() {
    IndustryDefinitionView industry = craft(Map.of(GRAIN, 7L), Map.of(), 2L, 1L);
    EconomyDayStepper stepper =
        stepper(world(industry, 2L, 1L), defs(industry), stocks(GRAIN, AMPLE_GRAIN));
    try {
      ProductionLedger dayOne = stepper.step(1L);
      assertThat(gross(dayOne, GRAIN))
          .as("★ 周期未完成（progressDays=1 < cycleDays=2）⇒ 一分产出都不该有")
          .isZero();

      ProductionLedger dayTwo = stepper.step(1L);
      assertThat(gross(dayTwo, GRAIN))
          .as("★★ 周期完成那一天：scale = min(10, 30,000÷1) = 10 ⇒ 10 × 7 × 1000 = 70,000 毫")
          .isEqualTo(70_000L);
    } finally {
      stepper.close();
    }
  }

  // ── ② 无劳动 / 缺投入 ───────────────────────────────────────────────────────────────

  @Test
  void zeroLaborYieldsNothingEvenThoughCapacityAloneCouldProduce() {
    IndustryDefinitionView industry = craft(Map.of(GRAIN, 7L), Map.of(), 1L, 1L);
    EconomyDayStepper stepper =
        stepper(world(industry, 1L, 1L, 0L), defs(industry), stocks(GRAIN, AMPLE_GRAIN));
    try {
      assertThat(gross(stepper.step(1L), GRAIN))
          .as("★ 无劳动 ⇒ 劳动路 scale = ⌊0 ÷ 1⌋ = 0 ⇒ 毛产 0（产能路单独本来能出 10 × 7 × 1000）")
          .isZero();
    } finally {
      stepper.close();
    }
  }

  @Test
  void missingCycleInputYieldsNothingAndTheSameWorldWithInputProducesTheHandComputedGross() {
    // 每周期每规模要 1,000 毫粮种子；本周期实扣 = min(所需, 可用)。
    IndustryDefinitionView withInput =
        craft(Map.of(GRAIN, 7L), Map.of(AssetKind.WORKSHOP, Map.of(GRAIN, 1_000L)), 1L, 1L);

    EconomyDayStepper starved = stepper(world(withInput, 1L, 1L), defs(withInput), Map.of());
    try {
      ProductionLedger ledger = starved.step(1L);
      assertThat(gross(ledger, GRAIN)).as("★ 手头一粒粮都没有 ⇒ 投入路 scale = 0 ⇒ 毛产 0").isZero();
      assertThat(inputOf(ledger, GRAIN)).as("实扣投入也必须是 0（不是'扣了负数'）").isZero();
    } finally {
      starved.close();
    }

    EconomyDayStepper supplied =
        stepper(world(withInput, 1L, 1L), defs(withInput), stocks(GRAIN, AMPLE_GRAIN));
    try {
      ProductionLedger ledger = supplied.step(1L);
      assertThat(gross(ledger, GRAIN))
          .as("★ 粮充足 ⇒ 投入路不再卡：10 × 7 × 1000 = 70,000")
          .isEqualTo(70_000L);
      assertThat(gross(ledger, GRAIN))
          .as("★ 粮充足 ⇒ 投入路不再卡：10 × 7 × 1000 = 70,000")
          .isEqualTo(70_000L);
      assertThat(inputOf(ledger, GRAIN))
          .as("实扣投入 = 10 × 1,000 = 10,000 毫（逐笔可核）")
          .isEqualTo(10_000L);
    } finally {
      supplied.close();
    }
  }

  // ── ③ 多商品毫单位换算 ──────────────────────────────────────────────────────────────

  @Test
  void eachCommodityIsConvertedToMilliIndependentlyAtTimesOneThousand() {
    Map<CommodityId, Long> output = new LinkedHashMap<>();
    output.put(GRAIN, 7L);
    output.put(FIBER, 3L);
    IndustryDefinitionView industry = craft(output, Map.of(), 1L, 1L);

    EconomyDayStepper stepper =
        stepper(world(industry, 1L, 1L), defs(industry), stocks(GRAIN, AMPLE_GRAIN));
    try {
      ProductionLedger ledger = stepper.step(1L);
      assertThat(gross(ledger, GRAIN)).as("粮：10 × 7 × 1000 = 70,000 毫").isEqualTo(70_000L);
      assertThat(gross(ledger, FIBER))
          .as("纤维：10 × 3 × 1000 = 30,000 毫（逐商品各乘一次 1000）")
          .isEqualTo(30_000L);
      assertThat(ledger.gross().get(CRAFT)).as("同一产业的产出表恰两行").hasSize(2);
    } finally {
      stepper.close();
    }
  }

  // ── ④ 日劳动 vs 周期劳动量纲 ────────────────────────────────────────────────────────

  @Test
  void laborIsAccumulatedOverTheWholeCycleAndDividedByLaborPerUnit() {
    // 每 1 单位规模要 4,000 千分劳动。cycleDays=1 ⇒ 本周期累计劳动 = 30,000 ⇒ 劳动路 = ⌊30,000 ÷ 4,000⌋ = 7。
    IndustryDefinitionView oneDay = craft(Map.of(GRAIN, 7L), Map.of(), 1L, 4_000L);
    EconomyDayStepper shortCycle =
        stepper(world(oneDay, 1L, 4_000L), defs(oneDay), stocks(GRAIN, AMPLE_GRAIN));
    try {
      assertThat(gross(shortCycle.step(1L), GRAIN))
          .as("★ 1 天 30,000 劳动 ÷ 4,000 = 7 规模（产能路 10 未绑定）⇒ 7 × 7 × 1000 = 49,000")
          .isEqualTo(49_000L);
    } finally {
      shortCycle.close();
    }

    // cycleDays=2 ⇒ 两天累计 60,000 ⇒ ★ 量纲：劳动那一路读的是**平均每日劳动** 60,000 ÷ 2 = 30,000
    // ⇒ laborScale = ⌊30,000 ÷ 4,000⌋ = 7（与 cycleDays=1 同值 —— 这正是"日劳动量纲"的含义：
    //   周期拉长不会让同一批人凭空多出规模；用整周期总量 60,000 去算会被误当成 15）。
    IndustryDefinitionView twoDays = craft(Map.of(GRAIN, 7L), Map.of(), 2L, 4_000L);
    EconomyDayStepper longCycle =
        stepper(world(twoDays, 2L, 4_000L), defs(twoDays), stocks(GRAIN, AMPLE_GRAIN));
    try {
      assertThat(gross(longCycle.step(1L), GRAIN)).as("第 1 天未关账").isZero();
      assertThat(gross(longCycle.step(1L), GRAIN))
          .as("★★ 平均每日劳动 30,000 ÷ 4,000 = 7 规模 ⇒ 7 × 7 × 1000 = 49,000（不是 60,000÷4,000=15）")
          .isEqualTo(49_000L);
    } finally {
      longCycle.close();
    }

    // 同一条两日周期，把 laborPerUnit 减半 ⇒ 平均每日劳动那一路 = ⌊30,000 ÷ 2,000⌋ = 15 ⇒ 转由产能路 10 绑定。
    IndustryDefinitionView twoDaysCheapLabor = craft(Map.of(GRAIN, 7L), Map.of(), 2L, 2_000L);
    EconomyDayStepper capped =
        stepper(
            world(twoDaysCheapLabor, 2L, 2_000L),
            defs(twoDaysCheapLabor),
            stocks(GRAIN, AMPLE_GRAIN));
    try {
      assertThat(gross(capped.step(1L), GRAIN)).isZero();
      assertThat(gross(capped.step(1L), GRAIN))
          .as("★ 劳动路 15 > 产能路 10 ⇒ 取小 10 ⇒ 70,000（两路都手算过，不是'取到哪个算哪个'）")
          .isEqualTo(70_000L);
    } finally {
      capped.close();
    }
  }

  // ── ⑤ 分配守恒（毛产 − 损耗 = 净产，逐值落在产出计提上） ──────────────────────────────

  @Test
  void conservationHoldsBetweenGrossLossAndTheNetAccruedToTheOperator() {
    IndustryDefinitionView industry = craft(Map.of(GRAIN, 7L), Map.of(), 1L, 1L);
    EconomyDayStepper stepper =
        stepper(world(industry, 1L, 1L), defs(industry), stocks(GRAIN, AMPLE_GRAIN));
    try {
      ProductionLedger ledger = stepper.step(1L);

      long grossMilli = gross(ledger, GRAIN);
      long lossMilli = ledger.lossOf(CRAFT, GRAIN);
      assertThat(grossMilli).as("毛产（手算）").isEqualTo(70_000L);
      assertThat(lossMilli)
          .as("损耗 = 毛产 × (饲料 0‰ + 折旧 30‰) = 70,000 × 30 ÷ 1000 = 2,100")
          .isEqualTo(2_100L);

      long netAccrued = 0L;
      for (ProductionLedger.ActorEntry entry : ledger.outputAccruals()) {
        if (entry.commodity().equals(GRAIN)) {
          netAccrued += entry.delta();
        }
      }
      assertThat(netAccrued)
          .as("★ 守恒：毛产 − 损耗 = 70,000 − 2,100 = 67,900，逐值等于计提给 operator 的净产")
          .isEqualTo(grossMilli - lossMilli)
          .isEqualTo(67_900L);
    } finally {
      stepper.close();
    }
  }

  // ── ⑥ 实物 vs 铸币：两条不同公式，逐笔手算的实付/欠额 ────────────────────────────────

  @Test
  void inKindRentAndMoneyWageFollowTwoDifferentHandComputedFormulas() {
    // ── 实物租：应付 500 毫粮，本周期净产 1,000 毫粮、家户库存空 ⇒ 可用 = net − 已付 = 1,000 ⇒ 实付 500、欠 0。
    ProductionSettlement.Facts inKindFacts = facts(Map.of(GRAIN, 1_000L), Map.of(), Map.of());
    CompensationRule rent =
        new CompensationRule(
            RuleType.FIXED_IN_KIND_RENT,
            new Payee.ToActor(HOUSEHOLD_ACTOR),
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            500L,
            Optional.of(GRAIN),
            Optional.empty(),
            0);
    ProductionSettlement.Outcome rentOutcome =
        ProductionSettlement.settle(relation(List.of(rent)), inKindFacts);

    assertThat(rentOutcome.transfers()).as("实物租实付 500 > 0 ⇒ 恰好一条转移").hasSize(1);
    assertThat(rentOutcome.transfers().get(0).goods())
        .as("★ 实物腿：goods = {grain: min(500, 1,000) = 500}")
        .containsExactlyEntriesOf(Map.of(GRAIN, 500L));
    assertThat(rentOutcome.transfers().get(0).money()).as("实物档不带货币腿").isEmpty();
    assertThat(rentOutcome.ruleSettlements()).hasSize(1);
    assertThat(rentOutcome.ruleSettlements().get(0).dueAmount()).isEqualTo(500L);
    assertThat(rentOutcome.ruleSettlements().get(0).paidNow()).isEqualTo(500L);
    assertThat(rentOutcome.ruleSettlements().get(0).owed()).as("付得清 ⇒ 欠 0").isZero();

    // ── 铸币工资：应付 700 毫银，可用余额只有 400 毫 ⇒ 实付 min(700, 400) = 400、欠 300。
    ProductionSettlement.Facts moneyFacts = facts(Map.of(), Map.of(SILVER, 400L), Map.of());
    CompensationRule wage =
        new CompensationRule(
            RuleType.FIXED_MONEY_WAGE,
            new Payee.ToActor(HOUSEHOLD_ACTOR),
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            700L,
            Optional.empty(),
            Optional.of(SILVER),
            1);
    ProductionSettlement.Outcome wageOutcome =
        ProductionSettlement.settle(relation(List.of(wage)), moneyFacts);

    assertThat(wageOutcome.transfers()).as("实付 400 > 0 ⇒ 一条转移").hasSize(1);
    assertThat(wageOutcome.transfers().get(0).money())
        .as("★ 货币腿：money = {silver: min(700, 400) = 400}")
        .containsExactlyEntriesOf(Map.of(SILVER, 400L));
    assertThat(wageOutcome.transfers().get(0).goods()).as("货币档不带商品腿").isEmpty();
    assertThat(wageOutcome.ruleSettlements().get(0).dueAmount()).isEqualTo(700L);
    assertThat(wageOutcome.ruleSettlements().get(0).paidNow()).isEqualTo(400L);
    assertThat(wageOutcome.ruleSettlements().get(0).owed())
        .as("★ 付不出不是静默 0：欠 = 700 − 400 = 300 落在读数上")
        .isEqualTo(300L);
    assertThat(wageOutcome.wageArrears()).as("欠款按 FIXED_MONEY_WAGE 分档成 WageArrears").hasSize(1);
  }

  @Test
  void mintedMoneyIsNotScaledByOneThousandWhileCommodityOutputIs() {
    // ★ 两条产出率的量纲在同一处并排手算：商品 = ×1000 换毫；货币 = 已经是毫，绝不 ×1000。
    long scale = 2L;
    Map<CommodityId, Long> commodityPerUnit = Map.of(GRAIN, 7L);
    MoneyOutput moneyPerUnit = new MoneyOutput(Map.of(SILVER, 500L));

    assertThat(scale * commodityPerUnit.get(GRAIN) * EconomySettlement.MILLI_PER_GRAIN)
        .as("商品：2 规模 × 7 单位 × 1000 = 14,000 毫")
        .isEqualTo(14_000L);
    assertThat(moneyPerUnit.grossOf(scale))
        .as("★ 货币：2 规模 × 500 毫 = 1,000 毫（grossOf 不再 ×1000）")
        .containsExactlyEntriesOf(Map.of(SILVER, 1_000L));
    assertThat(moneyPerUnit.perUnitOf(SILVER)).as("每单位规模的铸币率原样可读").isEqualTo(500L);
    assertThat(new MoneyOutput(Map.of()).grossOf(scale)).as("空表 = 不铸币（不是铸 0）").isEmpty();
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  /** 一条 craft 定义：WORKSHOP 1/规模、产出/投入/周期/劳动率都由参数给。 */
  // ── ④ G3（B8-§10 第 1 条）：executeDay 请求里的 finalInputsMilli == 本日 ledger 实扣量 ─────────────

  /**
   * ★★ <b>判据 G3 / B8-§10 第 1 条</b>（派单正文点名："此前**只有间接覆盖**"）：{@code executeDay} 请求里带的那份 {@code
   * finalInputsMilli} 必须**逐值等于**本日 {@code ledger} 记下的实扣量。
   *
   * <p>为什么必须直接钉"请求"而不是只看毛产：毛产是**结果**——"请求带 0 但结果碰巧对"与"请求带本日实扣"在旧读数里 不可区分（DEF-T2b-2 修复前正是"经济侧扣了、请求里
   * 0"）。本用例用一个**只记录、不改行为**的 {@link RecordingRuntime} 包住真引擎，读到的就是经济侧真正递给 production 的那份数。
   *
   * <pre>
   * 手算（本用例取 cycleDays=2，好让"周期未完成时的进度"也可读）：
   *   capacity = 10 台作坊 ÷ 1 = 10；劳动 = 30,000 ÷ 2（cycleDays）÷ 1 = 15,000；
   *   投入 = 1,000,000 毫粮 ÷ 1,000 毫/规模 = 1,000 ⇒ scale = min(10, 15,000, 1,000) = 10
   *   本日实扣 = scale × cycleInputPerUnit[WORKSHOP][grain] = 10 × 1,000 = 10,000 毫
   *   （★ 取 2 天周期：1 天周期当天就关账、进度会回绕到 0，读不出"推进了一天"这个事实）
   * </pre>
   */
  @Test
  void executeDayRequestCarriesExactlyTheInputsTheLedgerDrewThisDay() {
    IndustryDefinitionView withInput =
        craft(Map.of(GRAIN, 7L), Map.of(AssetKind.WORKSHOP, Map.of(GRAIN, 1_000L)), 2L, 1L);
    ProductionData definitions = defs(withInput);
    DayRecorder recorder = new DayRecorder(definitions);
    EconomyDayStepper stepper =
        stepper(
            world(withInput, 2L, 1L),
            definitions,
            recorder.asRuntime(),
            stocks(GRAIN, AMPLE_GRAIN));
    try {
      ProductionLedger ledger = stepper.step(1L);

      assertThat(ledger.inputOf(CRAFT, GRAIN))
          .as("① 账上实扣 = 10 × 1,000 = 10,000 毫（手算）")
          .isEqualTo(10_000L);
      assertThat(recorder.daysExecuted()).as("本日恰好推进一次生产日（不多不少）").isEqualTo(1);
      assertThat(recorder.finalInputsOf(UNIT))
          .as("② ★ 判据：请求里的 finalInputsMilli 与本日 ledger 实扣量逐值相等（同一份，不是两份）")
          .containsExactlyEntriesOf(Map.of(GRAIN, ledger.inputOf(CRAFT, GRAIN)))
          .containsEntry(GRAIN, 10_000L);
      assertThat(recorder.units().get(UNIT).progressDays())
          .as("③ 推进一个生产日 ⇒ 权威实例进度 = 1（2 天周期未关账）")
          .isEqualTo(1L);
      assertThat(recorder.units().get(UNIT).cycleInputUsedMilli())
          .as("④ 权威实例记下的本周期实扣 = 请求里那一份（10,000 毫粮）")
          .containsEntry(GRAIN, 10_000L);
    } finally {
      stepper.close();
    }
  }

  /**
   * ★★ <b>只记录、不改行为</b>的 G3 观测器：用<b>动态代理</b>包住真 {@link ProductionRuntimeEngine}（其余方法一律原样 反射转发），只在
   * {@code executeDay} 上抄一份每一步的 {@code finalInputsMilli}。
   *
   * <p>★ 为什么用代理而不是手写一份实现类：{@code ProductionRuntime} 是**活跃演进**的契约口（本批期间就新增了 {@code
   * createInstance}/{@code reoperateInstance}/{@code mergeExperience}）——手写实现会在契约每加一个方法时
   * 编译红一次，而观测点的语义（"经济侧递给 production 的投入"）与那些方法无关。
   */
  private static final class DayRecorder implements java.lang.reflect.InvocationHandler {

    private final io.mosire.simos.production.ProductionRuntimeEngine delegate;
    private final Map<ProductionUnitId, Map<CommodityId, Long>> finalInputs = new LinkedHashMap<>();
    private int daysExecuted;

    DayRecorder(ProductionData definitions) {
      this.delegate = new io.mosire.simos.production.ProductionRuntimeEngine(definitions);
    }

    ProductionRuntime asRuntime() {
      return (ProductionRuntime)
          java.lang.reflect.Proxy.newProxyInstance(
              ProductionRuntime.class.getClassLoader(),
              new Class<?>[] {ProductionRuntime.class},
              this);
    }

    Map<CommodityId, Long> finalInputsOf(ProductionUnitId unit) {
      return finalInputs.getOrDefault(unit, Map.of());
    }

    int daysExecuted() {
      return daysExecuted;
    }

    Map<ProductionUnitId, ProductionProcess> units() {
      return delegate.units();
    }

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
        throws Throwable {
      if (method.getName().equals("executeDay") && args != null && args.length == 1) {
        daysExecuted++;
        for (ProductionDayRequest.Step step : ((ProductionDayRequest) args[0]).steps()) {
          step.instance().ifPresent(id -> finalInputs.put(id, Map.copyOf(step.finalInputsMilli())));
        }
      }
      try {
        return method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  private static IndustryDefinitionView craft(
      Map<CommodityId, Long> output,
      Map<AssetKind, Map<CommodityId, Long>> cycleInput,
      long cycleDays,
      long laborPerUnit) {
    return IndustryViews.industry(
        CRAFT,
        "作坊（V4 手算夹具）",
        new RegimeId("handicraft"),
        cycleDays,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        laborPerUnit,
        output,
        cycleInput,
        List.of(new ClassSlot(SocialClassId.ARTISAN, "工匠", 1000)),
        new AllocationRule.Split(500, 500));
  }

  /**
   * ★★ <b>D-032 T2b 更新（测试面欠账，不是放宽）</b>：实例的唯一权威在 production ⇒ 定义表必须**连实例一起**给 （`EconomyDayStepper` 的
   * runtime 只执行它自己权威表里的实例）。
   *
   * <p>旧写法只 {@code withIndustry(...)}（实例对象在 {@code world(...)} 里造了却从未落表）⇒ runtime 的实例表为空 ⇒ {@code
   * executeDay} 无步可执行 ⇒ 毛产恒 0（这正是本类 5 条红的同一个根因）。这里把实例补进权威表， 与 {@code world(...)} 里 economy
   * 侧的配额/份额**引用同一个 unit id**（`UNIT`）。
   */
  private static ProductionData defs(IndustryDefinitionView industry) {
    return ProductionData.empty().withIndustry(industry).withInstance(unitOf(industry));
  }

  /** 该产业的实例（键 == 行内 id；地点按 plan id 的格段派生）。 */
  private static ProductionProcess unitOf(IndustryDefinitionView industry) {
    return new ProductionProcess(
        UNIT,
        industry.id(),
        ProductionLocation.ofPlan(industry.id()).orElseThrow(),
        OPERATOR,
        CRAFT.value(),
        0L,
        Map.of(),
        Map.of());
  }

  private static EconomyData world(
      IndustryDefinitionView industry, long cycleDays, long laborPerUnit) {
    return world(industry, cycleDays, laborPerUnit, LABOR_MILLI);
  }

  /** 一格一 unit 一户的最小世界（10 台作坊 OWNED、一条劳动配额）。 */
  private static EconomyData world(
      IndustryDefinitionView industry, long cycleDays, long laborPerUnit, long laborMilli) {
    OwnershipStake workshops =
        new OwnershipStake(
            OwnershipStake.idOf(
                industry.id(), AssetKind.WORKSHOP, OPERATOR, OPERATOR, AssetRightKind.OWNED, 0L),
            industry.id(),
            AssetKind.WORKSHOP,
            OPERATOR,
            OPERATOR,
            WORKSHOPS,
            AssetRightKind.OWNED);
    HouseholdEconomy row =
        new HouseholdEconomy(HOUSEHOLD, VIEW, 100L, LABOR_MILLI, 1000, 0L, Map.of(), Map.of(), 0L);
    HouseholdLaborCommitment commitment =
        new HouseholdLaborCommitment(
            ALLOCATION, LOT, HOUSEHOLD, OPERATOR, UNIT.value(), laborMilli, 1L);
    // ★★ <b>D-032 T2b 更新（测试面欠账，不是放宽）</b>：现扣周期投入**从 relation 指名的供方账上出**
    //   （`EconomySettlement.drawCycleInputs` 的 H3 口径）⇒ 声明了 `cycleInputPerUnit` 的世界**必须**有这条
    //   relation，且供方要指向**真持有粮的那一户**（`HOUSEHOLD_ACTOR`）。旧夹具没有 relation ⇒ 可供量恒 0
    //   ⇒ 投入路 scale = 0 ⇒ 毛产 0（这正是本类最后一条红的根因）。
    ProductionRules relation =
        new ProductionRules(
            UNIT, OPERATOR, new Payee.ToActor(HOUSEHOLD_ACTOR), List.of(), OPERATOR);
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withHouseholdEconomies(Map.of(HOUSEHOLD, row))
        .withOwnershipStakes(Map.of(workshops.id(), workshops))
        .withLaborCommitments(Map.of(ALLOCATION, commitment))
        .withRelations(Map.of(UNIT, relation));
  }

  /** 真日循环；{@code stocks} 是家户结算前的库存（毫）。 */
  private static EconomyDayStepper stepper(
      EconomyData base, ProductionData definitions, Map<CommodityId, Long> stocks) {
    return stepper(base, definitions, runtimeOf(definitions), stocks);
  }

  /** ★ G3：同一日循环，但运行时是一个只记录的装饰器（观测 {@code executeDay} 请求里的投入）。 */
  private static EconomyDayStepper stepper(
      EconomyData base,
      ProductionData definitions,
      ProductionRuntime runtime,
      Map<CommodityId, Long> stocks) {
    AccountSession accounts = AccountSession.empty();
    for (HouseholdEconomy row : base.classes().values()) {
      accounts.registerHousehold(row.id(), row.view().hex(), stocks, Map.of(), Map.of(), Map.of());
    }
    return new EconomyDayStepper(
        base,
        definitions,
        runtime,
        accounts,
        MarketTopology.singleHex(base.marketTables(), base.marketZones()));
  }

  private static Map<CommodityId, Long> stocks(CommodityId commodity, long milli) {
    return Map.of(commodity, milli);
  }

  private static ProductionRules relation(List<CompensationRule> rules) {
    return new ProductionRules(UNIT, OPERATOR, new Payee.ToActor(OPERATOR), rules, OPERATOR);
  }

  private static ProductionSettlement.Facts facts(
      Map<CommodityId, Long> net, Map<CurrencyId, Long> money, Map<CommodityId, Long> payerStock) {
    return new ProductionSettlement.Facts(
        HEX, net, net, Map.of(), Map.of(), Map.of(GRAIN, 7L), money, payerStock);
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "d031-v4-hand-calc",
        0L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  private static long gross(ProductionLedger ledger, CommodityId commodity) {
    return ledger.gross().getOrDefault(CRAFT, Map.of()).getOrDefault(commodity, 0L);
  }

  private static long inputOf(ProductionLedger ledger, CommodityId commodity) {
    return ledger.inputOf(CRAFT, commodity);
  }

  /** ★★ D-032 B2b/B3：会话/日推进器必须显式拿到 production 运行时（{@code runtime=null} 的旧构造器已删）。 */
  private static io.mosire.simos.production.contract.ProductionRuntime runtimeOf(
      io.mosire.simos.production.ProductionData definitions) {
    return new io.mosire.simos.production.ProductionRuntimeEngine(definitions);
  }
}
