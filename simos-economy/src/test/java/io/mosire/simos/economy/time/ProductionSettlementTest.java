package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.time.ProductionSettlement.Facts;
import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>结算计算（公式表 + {@code priority} 序）的逐值用例</b>（S1 阶段 4+5 Task 3；spec §四 ①→⑤ 的计算那一步）。
 *
 * <p>★★ <b>判别力来自夹具，不来自断言</b>（本仓阶段 3 独立实测三次）：本文件的期望值**全是字面量**（{@code 100_000} / {@code 97_000} /
 * {@code 30_000} / {@code 29_100} / {@code 900} / {@code 52_500} / {@code 4_320} …），
 * 每一处都把**算式写在注释里**，**没有一处**从被测物再读一遍。 ★ 更要紧的是：凡要测「某值不是被推导出来的」，夹具就 <b>刻意让两条路分叉</b> ——
 *
 * <ul>
 *   <li><b>I5.2</b>：毛产 {@code 100_000}、净产 {@code 97_000}（损耗 {@code 3_000}）⇒ 两档的实得数
 *       <b>必然不同</b>（{@code 30_000} vs {@code 29_100}）；若夹具取 {@code gross == net}，两档走同一支也全绿；
 *   <li><b>I5.4</b>：两条规则在表里的先后与 {@code priority} 序<b>刻意相反</b> ⇒「按表序」与「按 priority
 *       序」两种实现<b>可观测地不同</b>；
 *   <li><b>R7</b>：{@code laborOfCohort} 里放进一个<b>不是受方</b>的地主（{@code 10_000}）⇒「分母取全体」与
 *       「分母只取受方」分叉（{@code 52_500} vs {@code 70_000}）；
 *   <li><b>R6</b>：要得比产出多（{@code 1000‰ × 毛产}）⇒ 截到可用量那一条<b>看得见</b>（{@code 97_000}）；
 *   <li><b>SELF_RETENTION</b>：{@code fixedAmount} 取 {@code 5_000}（≠ 0）⇒ 若它被接到固定额那一支上，当场红。
 * </ul>
 */
class ProductionSettlementTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef WORKSHOP = new ActorRef(ActorKind.WORKSHOP, "craft@0_0");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CohortKey POOR =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final CohortKey RICH =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.RICH_PEASANT);
  private static final CohortKey LANDLORD =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.LANDLORD);

  /** 该产业的产出表：**只产粮**（★ E14 的守卫只读它的键；值不参与任何公式 ⇒ 取 1）。 */
  private static final Map<CommodityId, Long> GRAIN_RECIPE = Map.of(GRAIN, 1L);

  // ── 夹具构造（全部参数都是字面量；不任何处从被测物推导期望值）──────────────────────────────

  private static Recipient toCohort(CohortKey cohort) {
    return new Recipient.ToCohort(cohort);
  }

  private static Recipient toActor(ActorRef actor) {
    return new Recipient.ToActor(actor);
  }

  /** 一条分成规则（{@code OUTPUT_SHARE}）。 */
  private static CompensationRule share(
      Basis basis, int rate, Recipient recipient, CommodityId commodity, int priority) {
    return new CompensationRule(
        RuleType.OUTPUT_SHARE, recipient, basis, rate, 0L, Optional.of(commodity), priority);
  }

  /** 一条固定额实物规则（{@code FIXED_IN_KIND_PER_LABOR} / {@code FIXED_IN_KIND_RENT}）。 */
  private static CompensationRule fixedInKind(
      RuleType type,
      Basis basis,
      long amount,
      Recipient recipient,
      CommodityId commodity,
      int priority) {
    return new CompensationRule(
        type, recipient, basis, 0, amount, Optional.of(commodity), priority);
  }

  /** 一条货币规则（{@code commodity} 必须为空 —— {@link CompensationRule} 的守卫判死）。 */
  private static CompensationRule money(
      RuleType type, long amount, Recipient recipient, int priority) {
    return new CompensationRule(
        type, recipient, Basis.FIXED_AMOUNT, 0, amount, Optional.empty(), priority);
  }

  /** 一份本周期的事实（劳动账/资产账由用例按需给 —— 本构造器给空表）。 */
  private static Facts grainFacts(
      Map<CommodityId, Long> gross, Map<CommodityId, Long> net, Map<CommodityId, Long> recipe) {
    return new Facts(HEX, gross, net, Map.of(), Map.of(), recipe);
  }

  private static ProductionRelation relation(CompensationRule... rules) {
    return new ProductionRelation(FARM, ESTATE, List.of(rules), ESTATE);
  }

  /** 某 cohort 在某商品上入账了多少（没有这个键 ⇒ 0）。 */
  private static long intake(
      ProductionSettlement.Outcome outcome, CohortKey cohort, CommodityId j) {
    return outcome.cohortIntake().getOrDefault(cohort, Map.of()).getOrDefault(j, 0L);
  }

  // ── 判据 I5.2 / I5.3 / I5.4 ──────────────────────────────────────────────────────

  /** ★★ <b>I5.2</b>：同一份产出、只换 {@code basis} ⇒ {@code GROSS 30%} 与 {@code NET 30%} 的实得数不同。 */
  @Test
  void i52_grossShareAndNetShareDifferByExactlyTheShareOfTheLoss() {
    // 夹具（字面量）：毛产 100,000、损耗 3,000 ⇒ 净产 97,000。
    //   GROSS 30% = 100,000 × 300 ÷ 1000 = 30,000
    //   NET   30% =  97,000 × 300 ÷ 1000 = 29,100
    //   两数之差 = 30,000 − 29,100 = 900 = 3,000（损耗）× 300 ÷ 1000
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 97_000L), GRAIN_RECIPE);
    ProductionSettlement.Outcome onGross =
        ProductionSettlement.settle(
            relation(share(Basis.GROSS_OUTPUT, 300, toCohort(POOR), GRAIN, 10)), facts);
    ProductionSettlement.Outcome onNet =
        ProductionSettlement.settle(
            relation(share(Basis.NET_AFTER_INPUTS, 300, toCohort(POOR), GRAIN, 10)), facts);

    assertThat(intake(onGross, POOR, GRAIN)).as("毛产的 30%").isEqualTo(30_000L);
    assertThat(intake(onNet, POOR, GRAIN)).as("净产的 30%").isEqualTo(29_100L);
    assertThat(intake(onGross, POOR, GRAIN) - intake(onNet, POOR, GRAIN))
        .as("★ I5.2：差 = 损耗 × 30%（3,000 × 300 ÷ 1000 = 900）—— 两档若走同一支，这个差恒为 0")
        .isEqualTo(900L);
    assertThat(onGross.cohortIntake())
        .as("★ 同一份产出、只有 basis 不同 ⇒ 实得数不同")
        .isNotEqualTo(onNet.cohortIntake());
  }

  /** ★★ <b>I5.3</b>：货币档<b>只定义、不结算</b> —— 一条转移都不产生，但要<b>明确报出来</b>（待 S2）。 */
  @Test
  void i53_moneyRulesProduceNoTransfersAndAreReportedAsWaitingForS2() {
    // ★ 夹具刻意让毛产/净产**非零**（100,000 / 97,000）：否则"不产生条目"会因为"本来就没东西可分"而假绿。
    //   ★ 表序 = [租(20), 工资(10)]，而 priority 序 = [工资(10), 租(20)] ⇒ 待办清单的**次序**也跟着数据走。
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 97_000L), GRAIN_RECIPE);
    CompensationRule rent = money(RuleType.FIXED_MONEY_RENT, 20_000_000L, toCohort(LANDLORD), 20);
    CompensationRule wage = money(RuleType.FIXED_MONEY_WAGE, 1_000L, toActor(WORKSHOP), 10);

    ProductionSettlement.Outcome outcome = ProductionSettlement.settle(relation(rent, wage), facts);

    assertThat(outcome.actorEntries()).as("★ 货币规则不产生任何产权条目（I5.3 的判别力就在这一条）").isEmpty();
    assertThat(outcome.cohortIntake()).as("★ cohort 也不入账").isEmpty();
    assertThat(outcome.deferredMoney()).as("★ 但必须**报出来**（不许静默），且按付款次序").containsExactly(wage, rent);
    assertThat(ProductionSettlement.deferredMoneyReason(wage))
        .as("★ 消息口径：读口读出「待 S2」+ 是哪一档")
        .contains("待 S2", "FIXED_MONEY_WAGE");
  }

  /** ★★ <b>I5.4</b>：{@code priority} 决定次序是<b>数据</b>，不是分支 —— 只对调两个整数，实得数就变。 */
  @Test
  void i54_priorityIsData_swappingTheTwoPrioritiesChangesWhatEachSideGets() {
    // ★★ 两张规则表**一字不动**（表序恒为 [剩余, 净产]），只把两个 priority 整数对调。
    //   剩余那条的 basis 是 OPERATOR_SURPLUS（**读"已付"**）；净产那条不读"已付"。
    //   net = 100,000（本用例不考损耗 ⇒ gross = net = 100,000）。
    //   序 ①[剩余(10), 净产(20)]：剩余 = (100,000 − 0) × 500 ÷ 1000 = 50,000
    //                           净产 = 100,000 × 500 ÷ 1000 = 50,000（可用 50,000，刚好够）
    //   序 ②[净产(10), 剩余(20)]：净产 = 50,000
    //                           剩余 = (100,000 − 50,000) × 500 ÷ 1000 = 25,000
    //   ⇒ 贫农那一份 50,000 vs 25,000（**只改了数据**）。★ 序 ② 里"priority 序"与"表序"**相反** ⇒
    //     "把排序换成表序"这种实现会在这一条上当场红。
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);
    ProductionSettlement.Outcome surplusFirst =
        ProductionSettlement.settle(
            relation(
                share(Basis.OPERATOR_SURPLUS, 500, toCohort(POOR), GRAIN, 10),
                share(Basis.NET_AFTER_INPUTS, 500, toCohort(LANDLORD), GRAIN, 20)),
            facts);
    ProductionSettlement.Outcome netFirst =
        ProductionSettlement.settle(
            relation(
                share(Basis.OPERATOR_SURPLUS, 500, toCohort(POOR), GRAIN, 20),
                share(Basis.NET_AFTER_INPUTS, 500, toCohort(LANDLORD), GRAIN, 10)),
            facts);

    assertThat(intake(surplusFirst, POOR, GRAIN)).isEqualTo(50_000L);
    assertThat(intake(surplusFirst, LANDLORD, GRAIN)).isEqualTo(50_000L);
    assertThat(intake(netFirst, POOR, GRAIN))
        .as("★★ I5.4：priority 从 10 改成 20（纯数据）⇒ 经营剩余那一份从 50,000 掉到 25,000")
        .isEqualTo(25_000L);
    assertThat(intake(netFirst, LANDLORD, GRAIN)).as("不读「已付」的那一条不受次序影响").isEqualTo(50_000L);
  }

  /** ★ 次序的第二半句：<b>同值按规则在 {@code rules} 里的先后</b>（保序、可复现）。 */
  @Test
  void rulesWithTheSamePriorityKeepTheTableOrder() {
    // 两条都是"经营剩余的五成"、priority 都 = 10；表序 = [贫农, 地主]。
    //   贫农 = (100,000 − 0) × 500 ÷ 1000 = 50,000（第一条吃得下）
    //   地主 = (100,000 − 50,000) × 500 ÷ 1000 = 25,000
    //   ⇒ 反表序（或不稳定的排序）会让地主拿 50,000、贫农拿 25,000 ⇒ 本用例红。
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation(
                share(Basis.OPERATOR_SURPLUS, 500, toCohort(POOR), GRAIN, 10),
                share(Basis.OPERATOR_SURPLUS, 500, toCohort(LANDLORD), GRAIN, 10)),
            facts);

    assertThat(intake(outcome, POOR, GRAIN)).as("同 priority ⇒ 表序在前的先付").isEqualTo(50_000L);
    assertThat(intake(outcome, LANDLORD, GRAIN)).isEqualTo(25_000L);
    assertThat(outcome.actorEntries())
        .as("★ 落账次序 = 付款次序（转出条目按 priority 升序、同值按表序）")
        .containsExactly(
            new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -50_000L),
            new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -25_000L));
  }

  // ── R6 / R7 ─────────────────────────────────────────────────────────────────────

  /** ★ <b>R6</b>：付款上限 = 本周期收到的产出 ⇒ 要得再多也只付到那里（<b>不抛、不造账</b>）。 */
  @Test
  void r6_demandBeyondThisCyclesOutputIsCappedAndNothingIsInvented() {
    // 净产 97,000：
    //   ① 毛产的 1000‰（要 100,000）⇒ 实付 = min(100,000, 97,000) = 97,000（可用被吃干）
    //   ② 净产的 1000‰（要  97,000）⇒ 可用 = 97,000 − 97,000 = 0 ⇒ 实付 = 0 ⇒ **不产生条目**
    //   ⇒ Σ付款 = 97,000 = 净产（恰好吃满上限；一分钱都不来自"历史上存下的粮"—— R6 的性质）
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 97_000L), GRAIN_RECIPE);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation(
                share(Basis.GROSS_OUTPUT, 1000, toCohort(POOR), GRAIN, 10),
                share(Basis.NET_AFTER_INPUTS, 1000, toCohort(LANDLORD), GRAIN, 20)),
            facts);

    assertThat(intake(outcome, POOR, GRAIN)).as("要 100,000、实付 97,000（截到可用量）").isEqualTo(97_000L);
    assertThat(outcome.cohortIntake()).as("★ 付不出 ⇒ 该条归零、不产生条目（不造账）").containsOnlyKeys(POOR);
    assertThat(outcome.actorEntries())
        .as("★ 转出只发生了一次，且 = 净产（Σ付款 ≤ 净产）")
        .containsExactly(new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -97_000L));
  }

  /** ★ <b>R7</b>：入账的键是 {@code CohortKey}；劳动账里没有的 cohort ⇒ 该条<b>归零</b>、不产生条目。 */
  @Test
  void r7_cohortIntakeIsKeyedByCohortKeyAndACohortWithoutLaborGetsNothing() {
    // 规则：净产的 700‰ 按**劳动量**分给贫农、富农两个 cohort（同 priority ⇒ 按表序）。
    //   laborOfCohort = {贫农 30,000, 地主 10,000}（★ 富农**不在**账里 ⇒ 归零；★ 地主在账里但**不是受方**）
    //   Σ劳动 = 30,000 + 10,000 = 40,000（★ 取 laborOfCohort **全体**，不是"全体受方"）
    //   贫农 = 100,000 × 700 ÷ 1000 = 70,000 ⇒ 70,000 × 30,000 ÷ 40,000 = 52,500
    //   富农 = 70,000 × 0 ÷ 40,000 = 0 ⇒ 归零、不产生条目
    Facts facts =
        new Facts(
            HEX,
            Map.of(GRAIN, 100_000L),
            Map.of(GRAIN, 100_000L),
            Map.of(),
            Map.of(POOR, 30_000L, LANDLORD, 10_000L),
            GRAIN_RECIPE);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation(
                share(Basis.LABOR_AMOUNT, 700, toCohort(POOR), GRAIN, 10),
                share(Basis.LABOR_AMOUNT, 700, toCohort(RICH), GRAIN, 10)),
            facts);

    assertThat(outcome.cohortIntake())
        .as("★ 键是 CohortKey（不是行键）；富农那条归零 ⇒ 只剩贫农一个键")
        .containsOnlyKeys(POOR);
    assertThat(intake(outcome, POOR, GRAIN))
        .as("★ 分母取 laborOfCohort 全体（含不是受方的地主）：70,000 × 3/4 = 52,500 —— 只按受方求和会给 70,000")
        .isEqualTo(52_500L);
  }

  // ── 公式表的其余档 ───────────────────────────────────────────────────────────────

  /** ★ 受方两族<b>共用同一套数量公式</b>，差别只在<b>落到哪里</b>。 */
  @Test
  void cohortAndActorRecipientsShareOneFormulaAndDifferOnlyInWhereItLands() {
    // 净产的 400‰ ⇒ 两族都是 100,000 × 400 ÷ 1000 = 40,000；
    //   cohort 受方 ⇒ 入账（+ 付方那一笔转出条目）；actor 受方 ⇒ 一对产权条目（− 付方 / + 收方）。
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);
    ProductionSettlement.Outcome toCohortOutcome =
        ProductionSettlement.settle(
            relation(share(Basis.NET_AFTER_INPUTS, 400, toCohort(POOR), GRAIN, 10)), facts);
    ProductionSettlement.Outcome toActorOutcome =
        ProductionSettlement.settle(
            relation(share(Basis.NET_AFTER_INPUTS, 400, toActor(WORKSHOP), GRAIN, 10)), facts);

    assertThat(intake(toCohortOutcome, POOR, GRAIN))
        .as("cohort 受方：实得 40,000 进 cohortIntake")
        .isEqualTo(40_000L);
    assertThat(toCohortOutcome.actorEntries())
        .as("★ 付方那一笔照样要落账（账户 = (actor, location)）—— 否则同一份产出在账上多出一份")
        .containsExactly(new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -40_000L));
    assertThat(toActorOutcome.cohortIntake()).isEmpty();
    assertThat(toActorOutcome.actorEntries())
        .as("★ actor 受方：按落账次序 − 付方在前、+ 收方在后（同一格、同一商品）")
        .containsExactly(
            new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -40_000L),
            new ProductionSettlement.ActorEntry(WORKSHOP, HEX, GRAIN, 40_000L));
  }

  /** ★ {@code SELF_RETENTION} 的数量恒为 <b>0</b>（不动；余额归 {@code residualOwner}）。 */
  @Test
  void selfRetentionMovesNothingAndLeavesTheWholeResidualWithTheOwner() {
    // ★ 判别力：这条规则的 fixedAmount 刻意取 5,000（≠ 0）—— 若有人把它接到"固定额"那一支上，这里当场红。
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);
    CompensationRule keep =
        fixedInKind(RuleType.SELF_RETENTION, Basis.FIXED_AMOUNT, 5_000L, toCohort(POOR), GRAIN, 10);

    ProductionSettlement.Outcome outcome = ProductionSettlement.settle(relation(keep), facts);

    assertThat(outcome.actorEntries()).isEmpty();
    assertThat(outcome.cohortIntake()).isEmpty();
    assertThat(outcome.deferredMoney()).as("自留不是货币档（不进「待 S2」的清单）").isEmpty();
  }

  /** ★ {@code FIXED_IN_KIND_PER_LABOR} = ⌊本受方劳动 ÷ 1000⌋ × fixedAmount（<b>不除以</b> Σ劳动）。 */
  @Test
  void fixedInKindPerLaborCountsWholeLaborUnitsOfTheRecipientOnly() {
    // 贫农劳动 30,500 千分 ⇒ ⌊30,500 ÷ 1000⌋ = 30（向下取整）⇒ 30 × 144 = 4,320
    // ★ 地主也记了 10,000 劳动（不是受方）：若实现按"分成 × 份额"算，会落到 30,500/40,500 那一族 ⇒ 红。
    Facts facts =
        new Facts(
            HEX,
            Map.of(GRAIN, 100_000L),
            Map.of(GRAIN, 100_000L),
            Map.of(),
            Map.of(POOR, 30_500L, LANDLORD, 10_000L),
            GRAIN_RECIPE);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation(
                fixedInKind(
                    RuleType.FIXED_IN_KIND_PER_LABOR,
                    Basis.LABOR_AMOUNT,
                    144L,
                    toCohort(POOR),
                    GRAIN,
                    10)),
            facts);

    assertThat(intake(outcome, POOR, GRAIN))
        .as("⌊30,500 ÷ 1000⌋ × 144 = 30 × 144")
        .isEqualTo(4_320L);
  }

  /** ★ {@code FIXED_IN_KIND_RENT} = {@code fixedAmount}<b>（每周期一笔，与产出无关）</b>。 */
  @Test
  void fixedInKindRentIsOnePaymentPerCycleIndependentOfTheOutput() {
    // ★ 两个夹具的净产**刻意不同**（100,000 与 100,000,000）⇒ 若它偷偷按比例算，两次会不一样 ⇒ 红。
    CompensationRule rent =
        fixedInKind(
            RuleType.FIXED_IN_KIND_RENT,
            Basis.FIXED_AMOUNT,
            20_000L,
            toCohort(LANDLORD),
            GRAIN,
            10);
    ProductionSettlement.Outcome small =
        ProductionSettlement.settle(
            relation(rent),
            grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE));
    ProductionSettlement.Outcome big =
        ProductionSettlement.settle(
            relation(rent),
            grainFacts(Map.of(GRAIN, 100_000_000L), Map.of(GRAIN, 100_000_000L), GRAIN_RECIPE));

    assertThat(intake(small, LANDLORD, GRAIN)).as("固定租 = 20,000 毫粮（一笔）").isEqualTo(20_000L);
    assertThat(intake(big, LANDLORD, GRAIN)).as("★ 产出翻一千倍，租一分不变").isEqualTo(20_000L);
  }

  /** ★ 同一个 cohort 收到多条规则 ⇒ <b>累加</b>（{@code merge} 而非 {@code put}）。 */
  @Test
  void twoRulesForTheSameCohortAccumulateInOneIntake() {
    // ① 净产的 300‰ ⇒ 100,000 × 300 ÷ 1000 = 30,000
    // ② 固定实物租 1,000
    // ⇒ 贫农这**一个**键上的粮 = 31,000（若第二条把第一条覆盖掉，就是 1,000 ⇒ 红）
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation(
                share(Basis.NET_AFTER_INPUTS, 300, toCohort(POOR), GRAIN, 10),
                fixedInKind(
                    RuleType.FIXED_IN_KIND_RENT,
                    Basis.FIXED_AMOUNT,
                    1_000L,
                    toCohort(POOR),
                    GRAIN,
                    20)),
            facts);

    assertThat(outcome.cohortIntake()).containsOnlyKeys(POOR);
    assertThat(intake(outcome, POOR, GRAIN))
        .as("30,000 + 1,000（两条规则累加到同一个 cohort 键上）")
        .isEqualTo(31_000L);
    assertThat(outcome.actorEntries())
        .containsExactly(
            new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -30_000L),
            new ProductionSettlement.ActorEntry(ESTATE, HEX, GRAIN, -1_000L));
  }

  // ── E14 的守卫 + 契约守卫 ────────────────────────────────────────────────────────

  /** ★★ <b>E14</b>：规则指名的商品<b>不在该产业的产出表里</b> ⇒ <b>当场抛</b>（绝不许静默付 0）。 */
  @Test
  void e14_aRuleNamingACommodityTheIndustryDoesNotProduceIsRejectedLoudly() {
    // ★ E14 的原场景：GM 把 household（同一条布规则）套在**农业**产业上 ⇒ 布不在 {grain} 里 ⇒ 抛。
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);

    assertThatThrownBy(
            () ->
                ProductionSettlement.settle(
                    relation(share(Basis.NET_AFTER_INPUTS, 700, toCohort(POOR), CLOTH, 10)), facts))
        .as("★ 消息里两种拼写都给出：规则指名的商品 + 该产业到底产什么")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cloth")
        .hasMessageContaining("grain")
        .hasMessageContaining("farm@0_0");

    // ★ 正对照：同一条规则换成**该产业真产**的商品 ⇒ 不抛、按公式付得出（否则上面那条红只是"什么关系都抛"）。
    ProductionSettlement.Outcome onGrain =
        ProductionSettlement.settle(
            relation(share(Basis.NET_AFTER_INPUTS, 700, toCohort(POOR), GRAIN, 10)), facts);
    assertThat(intake(onGrain, POOR, GRAIN)).as("100,000 × 700 ÷ 1000").isEqualTo(70_000L);
  }

  /** ★ E14 的守卫读的是「该产业<b>能产什么</b>」，不是「本周期产了多少」。 */
  @Test
  void e14_theGuardReadsTheRecipeNotThisCyclesNumbers() {
    // 本期规模 0 ⇒ gross/net 都是空表，但粮仍在产出表里 ⇒ **不抛**。
    //   ★ 若守卫改成读 gross 的键，本用例当场红（那会让"某周期没产出的商品"被当成配置错误）。
    Facts sterile = grainFacts(Map.of(), Map.of(), GRAIN_RECIPE);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation(share(Basis.NET_AFTER_INPUTS, 700, toCohort(POOR), GRAIN, 10)), sterile);

    assertThat(outcome.actorEntries()).isEmpty();
    assertThat(outcome.cohortIntake()).isEmpty();
  }

  /** ★ <b>E11</b>：哪些组合有公式由<b>这张公式表</b>说了算 ⇒ 表里没有的档位一律 fail-closed（不猜）。 */
  @Test
  void unregisteredRuleTypeAndBasisCombinationsAreRejected() {
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);

    assertThatThrownBy(
            () ->
                ProductionSettlement.settle(
                    relation(share(Basis.FIXED_AMOUNT, 700, toCohort(POOR), GRAIN, 10)), facts))
        .as("★ OUTPUT_SHARE 不读固定额（「固定额的分成」在表里没有落点）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OUTPUT_SHARE")
        .hasMessageContaining("FIXED_AMOUNT");

    assertThatThrownBy(
            () ->
                ProductionSettlement.settle(
                    relation(
                        fixedInKind(
                            RuleType.FIXED_IN_KIND_PER_LABOR,
                            Basis.GROSS_OUTPUT,
                            144L,
                            toCohort(POOR),
                            GRAIN,
                            10)),
                    facts))
        .as("★ FIXED_IN_KIND_PER_LABOR 只按劳动量（毛产那一层与它无关）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FIXED_IN_KIND_PER_LABOR")
        .hasMessageContaining("GROSS_OUTPUT");
  }

  /** ★ 契约守卫：没给的事实、负数数量、以及"把非货币档当货币档读"一律<b>当场抛</b>。 */
  @Test
  void missingOrNegativeFactsAndWrongDeferralReadsAreRejected() {
    Facts facts = grainFacts(Map.of(GRAIN, 100_000L), Map.of(GRAIN, 100_000L), GRAIN_RECIPE);
    ProductionRelation relation =
        relation(share(Basis.NET_AFTER_INPUTS, 300, toCohort(POOR), GRAIN, 10));

    assertThatThrownBy(() -> ProductionSettlement.settle(null, facts))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProductionSettlement.settle(relation, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Facts(HEX, null, Map.of(), Map.of(), Map.of(), GRAIN_RECIPE))
        .as("★ 事实没给 = 坏数据，不是状态（同本仓各 record 的口径）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> grainFacts(Map.of(GRAIN, -1L), Map.of(), GRAIN_RECIPE))
        .as("★ 负数不是一种数量（同 CompensationRule.fixedAmount 的口径）—— 否则实付会变成反向的收")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProductionSettlement.deferredMoneyReason(relation.rules().get(0)))
        .as("★ 「待 S2」那句话只对货币档成立")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
