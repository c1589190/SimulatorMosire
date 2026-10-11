package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>W5.5 测试批（2026-10-11）：货币市场（取缔借粮 + 钱是市场里的一个商品）的判据 ①–⑦</b>。
 *
 * <p>★★ <b>判据出处 = 约束设计书 {@code
 * docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md} 的 <b>§2
 * S-12</b>（七条口径）+ <b>§6.3 判据清单</b>（第 1 条四类关键项、第 3 条负向用例、第 4 条逐值表）+ §7 v3.21/v3.22（R2 裁定与根因更正）</b>。
 *
 * <pre>
 * S-12① 取缔借粮腿（本类只测"钱腿只搬钱"，货仍走正规撮合）
 * S-12② 次序 = 挂商品 → 挂钱 → 借钱 → 买货（本类的端到端用例从"借到的钱在同一轮真被花掉"证它）
 * S-12③ 出借额 = 可用现金 − 生活储备 − 本轮自己的生产花费所需现金（≤0 ⇒ 不出借）
 * S-12④ 借款人无额度上限，唯一上限 = 放贷池（池空 ⇒ 借不到）
 * S-12⑤ 利率由供需（与 §2.1 同一套自适应：缺钱升、钱多降、D=S 不动、α=0 冻结、下限 0）
 * S-12⑥ 国库默认不挂钱（两侧都不参与；政府要出借走显式授权 —— 本批未建写口）
 * S-12⑦ 赊购保留（借钱借不到时的最后一道 ⇒ 未付价款记对卖方的债）
 * </pre>
 *
 * <p>★★★ <b>为什么分两层测（这是本类的结构，不是风格）</b>：
 *
 * <ol>
 *   <li><b>纯货币腿层</b>（{@link MoneyMarketSettlement#settle} 直调，六个用例）：两簿算式、上限、利率五条分支、国库排除、 守恒与"债务人 ≠
 *       债权人"这些判据的<b>输入是逐值的</b>（需求/供给/α 全是本类给的字面量）⇒ 断言可以钉在确切的数上， 不必从"世界的形状"反推需求；
 *   <li><b>端到端层</b>（真 {@code MarketSettlement.clearOncePerCycle}，两个用例）：判据 ②/①（借钱真能买到货）与 ⑦（池空 ⇒
 *       退赊购）只有在<b>完整市场轮</b>里才成立 —— 货币腿的输入（{@code need}）由买槽按本区牌价折出，借到的钱还要被补进订单预算 （{@code
 *       applyMoneyMarketBudgetTopUps}）才可能付得出去。这一层用资金池<b>有无</b>做判别对照：世界其余部分逐值相同。
 * </ol>
 *
 * <p>★★ <b>装置与诚实边界（如实写）</b>：
 *
 * <ul>
 *   <li>● 本类<b>不复制任何算式</b>：夹具（{@code MarketSettlementFixtures}）搭真 {@code MarketRound}，撮合走真 {@code
 *       clearOncePerCycle}，钱腿走真 {@code applyTransfer}（唯一写口），债务走真 {@code DebtContractBook.upsert}；
 *   <li>● <b>跨 logger 的绝对次序不可比</b>（模块测试的 {@code TestLogCapture} 按 logger 名精确挂槽：{@code
 *       MONEY_MARKET_LOAN} 在 {@code .debt}、{@code MONEY_MARKET_WHY} 在 {@code .market}）⇒ <b>判据 ⑦
 *       的"次序"证据由两条独立事实合成</b>： ① 同一 logger（{@code .market}）内的行序 —— 钱腿的 {@code MONEY_MARKET_WHY}
 *       早于买货那一步的 {@code MARKET_DEFERRED_PAYMENT}；② <b>行为等价性</b> —— 借到的钱在同一轮真被花掉（现金 0
 *       的家户付出现金），而没有放贷池时 同一户退回赊购。若把货币腿挪到撮合之后，①与②会同时红（②是判别性的：见"池空"对照用例）；
 *   <li>● 断言里的"应付货款 = ⌈数量 × 单价 ÷ 1000⌉"是<b>本类自己按口径复算</b>的（不是读被测实现），与 {@code
 *       DebtBasedClearingAcceptanceTest} 的既有先例同形。
 *   <li>● 断言里的"应付货款 = ⌈数量 × 单价 ÷ 1000⌉"是<b>本类自己按口径复算</b>的（不是读被测实现），与 {@code
 *       DebtBasedClearingAcceptanceTest} 的既有先例同形。
 * </ul>
 *
 * <p>★ <b>本类不落盘、不 replay</b>：只用会话内的工作副本 ⇒ 与存档 codec 那条链无关。
 */
class MoneyMarketSettlementTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;

  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;

  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  /** 派生单区形态的规范市场 id（与 {@code EconomySeeder.SINGLE_REGION_MARKET_ID} 逐字相同）。 */
  private static final MarketZoneId ZONE =
      new MarketZoneId(MarketSettlementFixtures.SINGLE_REGION_MARKET_ID);

  private static final long DAY = MarketSettlementFixtures.DAY;

  /** 借款合同的到期周期（只进合同的 {@code dueCycle} 字段）。 */
  private static final long DUE_CYCLE = 9L;

  /** 本区牌价（毫银 / 商品单位）：货款 = ⌈数量 × 10 ÷ 1000⌉ ⇒ 数量 2905 的货款恰 30 毫。 */
  private static final long GRAIN_PRICE = 10L;

  /** 出厂利率 / α（与 {@code MarketTable} 的两个缺省同值；对照用例显式给别的值）。 */
  private static final long DEFAULT_RATE = MarketTable.DEFAULT_LOAN_RATE_PER_MILLE;

  private static final long DEFAULT_ALPHA = MarketTable.DEFAULT_LOAN_RATE_ALPHA_PER_MILLE;

  /** S-12③ 的"生活储备"单价：{@code MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI = 12} 毫/人。 */
  private static final long RESERVE_PER_CAPITA = 12L;

  // ── ① 放贷池算式（S-12③）：可贷 = 现金 − 生活储备 − 本轮自己的生产花费；≤ 0 ⇒ 不出借，不许为负 ──────────

  /**
   * ★★★ <b>判据出处：S-12③</b>（出借额 = 余额 − 生活储备 − 本轮自己的生产花费所需现金；用户原话④「**如果实际对不上也不要紧， 该怎么算怎么算**」⇒ 只做
   * {@code max(0, ·)}，不加任何额外机制）。
   *
   * <p>★ <b>逐值表（放贷人 1 人、自己的生产花费 40 毫）</b>：{@code 可贷 = 现金 − 12 × 1 − 40} ⇒
   *
   * <pre>
   * 现金 51 ⇒ 可贷 −1 ⇒ 夹到 0（★ 不许为负）
   * 现金 52 ⇒ 可贷  0 ⇒ 恰好不出借（边界）
   * 现金 53 ⇒ 可贷  1 ⇒ 出借 1 毫（借不到 100 的缺口，剩下的退赊购）
   * 现金 500 ⇒ 可贷 448 ⇒ 出借 100（= 借款人缺口，仍受需求约束）
   * </pre>
   */
  @Test
  void loanPoolIsCashMinusLifeReserveMinusOwnProductionCostAndNeverNegative() {
    long borrowerNeed = 100L;
    long lenderOwnCost = 40L;

    // ① 现金 51 / 52：可贷 ≤ 0 ⇒ 供给 0、一笔都不借（负值被夹到 0，不许为负）。
    for (long lenderCash : List.of(51L, 52L)) {
      MarketSettlementFixtures.World world =
          poolWorld(Map.of(BORROWER, 0L, LENDER, lenderCash), 1L);
      Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
      MoneyMarketSettlement.Round round =
          MoneyMarketSettlement.settle(
              marketRound(world, debts),
              input(
                  table(DEFAULT_RATE, DEFAULT_ALPHA),
                  List.of(BORROWER, LENDER),
                  need(borrowerNeed)));

      assertThat(round.supplyMilli())
          .as(
              "★ 现金 %d ⇒ 可贷 = %d − %d×1 − %d ≤ 0 ⇒ 供给 0（不许为负）",
              lenderCash, lenderCash, RESERVE_PER_CAPITA, lenderOwnCost)
          .isZero();
      assertThat(round.lentMilli()).as("池空 ⇒ 一笔都不借").isZero();
      assertThat(round.loans()).isZero();
      assertThat(debts).as("没有借款 ⇒ 一条合同都没有").isEmpty();
      assertThat(world.silverOf(LENDER)).as("出借人的钱一分没动").isEqualTo(lenderCash);
      assertThat(world.silverOf(BORROWER)).as("借款人也没拿到钱").isZero();
    }

    // ② 现金 53 ⇒ 可贷 1 ⇒ 恰好借出 1 毫（"实际对不上也不要紧"：不补足、不四舍五入到需求）。
    MarketSettlementFixtures.World oneMilli = poolWorld(Map.of(BORROWER, 0L, LENDER, 53L), 1L);
    Map<DebtContractId, DebtContract> oneMilliDebts = new LinkedHashMap<>();
    MoneyMarketSettlement.Round oneMilliRound =
        MoneyMarketSettlement.settle(
            marketRound(oneMilli, oneMilliDebts),
            input(
                table(DEFAULT_RATE, DEFAULT_ALPHA), List.of(BORROWER, LENDER), need(borrowerNeed)));
    assertThat(oneMilliRound.supplyMilli()).as("★ 现金 53 ⇒ 可贷 = 53 − 12 − 40 = 1").isEqualTo(1L);
    assertThat(oneMilliRound.lentMilli()).as("★ 只借得出 1 毫（上限 = 池，不是需求）").isEqualTo(1L);
    assertThat(oneMilliRound.loans()).as("一笔借款").isEqualTo(1);
    assertThat(oneMilli.silverOf(LENDER)).as("贷方 53 → 52").isEqualTo(52L);
    assertThat(oneMilli.silverOf(BORROWER)).as("借方 0 → 1").isEqualTo(1L);

    // ③ 现金 500 ⇒ 可贷 448；需求 100 ⇒ 借出 100（受需求约束，不超借）。
    MarketSettlementFixtures.World rich = poolWorld(Map.of(BORROWER, 0L, LENDER, 500L), 1L);
    Map<DebtContractId, DebtContract> richDebts = new LinkedHashMap<>();
    MoneyMarketSettlement.Round richRound =
        MoneyMarketSettlement.settle(
            marketRound(rich, richDebts),
            input(
                table(DEFAULT_RATE, DEFAULT_ALPHA), List.of(BORROWER, LENDER), need(borrowerNeed)));
    assertThat(richRound.demandMilli()).as("需求 = 缺口 = 100 − 0").isEqualTo(100L);
    assertThat(richRound.supplyMilli()).as("★ 可贷 = 500 − 12×1 − 40 = 448").isEqualTo(448L);
    assertThat(richRound.lentMilli()).as("借出 = min(需求, 池) = 100").isEqualTo(100L);
    assertThat(rich.silverOf(LENDER)).as("贷方 500 → 400").isEqualTo(400L);
    assertThat(rich.silverOf(BORROWER)).as("借方 0 → 100").isEqualTo(100L);

    // ④ 生活储备与"自己的生产花费"是两个独立的减项（人口 2 的边界用例）。
    MarketSettlementFixtures.World twoPeople = poolWorld(Map.of(BORROWER, 0L, LENDER, 64L), 2L);
    MoneyMarketSettlement.Round twoPeopleRound =
        MoneyMarketSettlement.settle(
            marketRound(twoPeople, new LinkedHashMap<>()),
            input(
                table(DEFAULT_RATE, DEFAULT_ALPHA), List.of(BORROWER, LENDER), need(borrowerNeed)));
    assertThat(twoPeopleRound.supplyMilli())
        .as("★ 人口 2 ⇒ 储备 24：64 − 24 − 40 = 0 ⇒ 仍不出借（储备按人口缩放）")
        .isZero();
  }

  // ── ② 借款人无上限、唯一上限 = 放贷池（S-12④） ───────────────────────────────────────────────

  /** ★★★ <b>判据出处：S-12④</b>（「家户有多少借多少」（用户原话④）⇒ 借款人<b>无额度上限</b>，唯一上限 = 放贷池； 池空即止，剩下的缺口留给买货那一步的赊购）。 */
  @Test
  void borrowerHasNoOwnLimitAndTheOnlyCapIsThePool() {
    MarketSettlementFixtures.World world = poolWorld(Map.of(BORROWER, 0L, LENDER, 500L), 1L);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    try (TestLogCapture market =
        TestLogCapture.attach(EconomyLog.MARKET_LOGGER_NAME, Level.DEBUG)) {
      MoneyMarketSettlement.Round round =
          MoneyMarketSettlement.settle(
              marketRound(world, debts),
              input(table(DEFAULT_RATE, DEFAULT_ALPHA), List.of(BORROWER, LENDER), need(10_000L)));

      assertThat(round.demandMilli()).as("借款人要 10000（远超任何'额度'）").isEqualTo(10_000L);
      assertThat(round.lentMilli())
          .as("★ 借出 = 池的全部 448（上限 = 放贷池，不是借款人额度；也不因'还得起吗'打折）")
          .isEqualTo(448L);
      assertThat(round.lentMilli()).as("★ 池被借空（lent == supply）").isEqualTo(round.supplyMilli());
      assertThat(world.silverOf(BORROWER)).as("借方拿到 448").isEqualTo(448L);
      assertThat(world.silverOf(LENDER))
          .as("贷方被借空：500 − 448 = 52（= 12 储备 + 40 自己的生产花费）")
          .isEqualTo(52L);
      assertThat(debts).as("一笔合同").hasSize(1);
      assertThat(debts.values().iterator().next().principal()).as("本金 = 448").isEqualTo(448L);

      // ★ 池空之后的缺口有具名读数（DEBUG）：MONEY_MARKET_UNFILLED.unmetMilli = 10000 − 448。
      String unfilled = lineOf(market, "MONEY_MARKET_UNFILLED");
      assertThat(longFieldOf(unfilled, "borrowedMilli")).as("借到的部分").isEqualTo(448L);
      assertThat(longFieldOf(unfilled, "unmetMilli"))
          .as("★ 池空之后的缺口 = 9552（退回买货那一步的赊购）")
          .isEqualTo(9_552L);
      assertThat(textFieldOf(unfilled, "reason")).as("拒因具名").contains("pool-exhausted");
    }
  }

  // ── ③ 利率随供需（S-12⑤，与 §2.1 同一套算式） ─────────────────────────────────────────────

  /**
   * ★★★ <b>判据出处：S-12⑤ + §2.1 的算式逐值表</b>（缺钱升、钱多降、D=S 不动、α=0 冻结、下限 0 = 白借合法）。
   *
   * <p>★ 算式（{@code MarketSettlement.adaptiveNextPrice}，与商品价同一个唯一拼写点）：
   *
   * <pre>
   * D > S: step = min(α, ⌊α × (D−S) / max(1,S)⌋)；利率' = max(利率 + 1, ⌊利率 × (1000+step) / 1000⌋)   （step > 0 时）
   * D < S: step = min(α, ⌊α × (S−D) / max(1,D)⌋)；利率' = max(0, min(利率 − 1, ⌈利率 × (1000−step) / 1000⌉))
   * D = S: 利率' = 利率（缺省中性：不失衡的市场不动）
   * α = 0: 不动（合法状态：该市场利率被钉死）
   * </pre>
   *
   * <p>★ 本用例的每个读数都当场算得出来（放贷人 1 人、自己的生产花费 40 ⇒ 可贷 448）。
   */
  @Test
  void loanRateFollowsSupplyAndDemandWithAlphaZeroFrozenAndZeroFloor() {
    Map<HouseholdId, Long> lender = Map.of(BORROWER, 0L, LENDER, 500L);

    // ① 缺钱（D=1000 ≫ S=448）⇒ 升：step 打满 α=50 ⇒ max(20+1, ⌊20×1050/1000⌋) = 21。
    assertThat(rateNext(lender, 1_000L, 40L, table(20L, 50L)))
        .as("★ 缺钱 ⇒ 利率升 20 → 21（步长打满 α）")
        .isEqualTo(21L);

    // ② 钱多（D=100 < S=448）⇒ 降：step = min(50, ⌊50×348/100⌋=174) = 50 ⇒ min(20−1, ⌈20×950/1000⌉=19) =
    // 19。
    assertThat(rateNext(lender, 100L, 40L, table(20L, 50L)))
        .as("★ 钱多 ⇒ 利率降 20 → 19")
        .isEqualTo(19L);

    // ③ D == S（需求恰好 448 = 池）⇒ 不动（关键：不是"没变就不记"的实现细节，而是算式本身的一支）。
    MarketSettlementFixtures.World balanced = poolWorld(lender, 1L);
    Map<DebtContractId, DebtContract> balancedDebts = new LinkedHashMap<>();
    MoneyMarketSettlement.Round balancedRound =
        MoneyMarketSettlement.settle(
            marketRound(balanced, balancedDebts),
            input(table(20L, 50L), List.of(BORROWER, LENDER), need(448L)));
    assertThat(balancedRound.demandMilli()).as("D").isEqualTo(448L);
    assertThat(balancedRound.supplyMilli()).as("S（D == S 的前提）").isEqualTo(448L);
    assertThat(balancedRound.loanRateNext()).as("★ D = S ⇒ 利率不动（缺省中性；不下发任何写回）").isEmpty();

    // ④ α = 0 ⇒ 冻结（合法状态：GM 把该市场利率钉死）—— 即便 D ≫ S 也一个毫都不动。
    assertThat(rateNext(lender, 1_000L, 40L, table(20L, 0L)))
        .as("★ α=0 ⇒ 利率冻结在 20（不因缺钱而升）")
        .isNull();

    // ⑤ 下限 0：利率 1‰ 且钱多 ⇒ 一步到 0（白借合法，不许为负）。
    assertThat(rateNext(lender, 100L, 40L, table(1L, 50L)))
        .as("★ 下限 0：1 → 0（0 = 白借，是合法状态）")
        .isZero();
  }

  // ── ④ 国库默认不挂钱（S-12⑥） ────────────────────────────────────────────────────────────

  /**
   * ★★★ <b>判据出处：S-12⑥</b>（用户原话④「**国家挂不挂单看国家呗**」⇒ 国库<b>默认</b>不挂钱：两侧都不参与；要出借走显式授权， 本批未建写口）。
   *
   * <p>★ 本用例是<b>判别对照</b>：同一个国库户（手里 10000 毫银）在①"按国库身份"下供给 0、一分不借；②"按普通家户"下立刻成为 放贷人并借出 100 ⇒
   * 差异只能来自"国库身份被排除"，不是"它没钱"。
   */
  @Test
  void treasuryDoesNotLendOrBorrowByDefault() {
    MarketSettlementFixtures.World world = poolWorld(Map.of(BORROWER, 0L, TREASURY, 10_000L), 1L);
    List<HouseholdId> households = List.of(BORROWER, TREASURY);

    // ① 国库身份（GovernmentMarketMandatePlan.isAuthorizationOnly ⇒ AUTHORIZATION_ONLY）。
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    MoneyMarketSettlement.Round asTreasury =
        MoneyMarketSettlement.settle(
            marketRound(world, debts, treasuryPlan()),
            input(table(DEFAULT_RATE, DEFAULT_ALPHA), households, need(100L)));

    assertThat(asTreasury.supplyMilli()).as("★ S-12⑥：国库的 10000 毫银不进货币供给（默认不挂钱）").isZero();
    assertThat(asTreasury.lentMilli()).as("国库不借出 ⇒ 一笔都没有").isZero();
    assertThat(asTreasury.govSuppressed()).as("★ 具名读数：被'国库默认不挂钱'排除的国库户数 = 1").isEqualTo(1);
    assertThat(world.silverOf(TREASURY)).as("国库余额一分不动").isEqualTo(10_000L);
    assertThat(world.silverOf(BORROWER)).as("借款人借不到（缺口退赊购，S-12⑦）").isZero();
    assertThat(debts).as("没有合同").isEmpty();

    // ② 同一个户、同一笔钱，去掉国库身份 ⇒ 立刻是放贷人（判别对照：差异只来自身份）。
    MarketSettlementFixtures.World control = poolWorld(Map.of(BORROWER, 0L, TREASURY, 10_000L), 1L);
    Map<DebtContractId, DebtContract> controlDebts = new LinkedHashMap<>();
    MoneyMarketSettlement.Round asHousehold =
        MoneyMarketSettlement.settle(
            marketRound(control, controlDebts),
            input(table(DEFAULT_RATE, DEFAULT_ALPHA), households, need(100L)));

    assertThat(asHousehold.supplyMilli())
        .as("★ 判别对照：同一户按普通家户 ⇒ 供给 = 10000 − 12×1 = 9988")
        .isEqualTo(9_988L);
    assertThat(asHousehold.lentMilli()).as("借出 100（受需求约束）").isEqualTo(100L);
    assertThat(asHousehold.govSuppressed()).as("没有国库户被排除").isZero();
    assertThat(control.silverOf(BORROWER)).as("借款人拿到 100").isEqualTo(100L);
    assertThat(control.silverOf(TREASURY)).as("放贷人 10000 → 9900").isEqualTo(9_900L);
  }

  // ── ⑤ 守恒 + 债务人 ≠ 债权人（§6.3 判据 1 的守恒式 / 负向面） ────────────────────────────────

  /**
   * ★★★ <b>判据出处：§6.3 判据 1（守恒式）+ S-12 的"钱腿只搬钱" + {@code DebtContract} 的两端语义</b>。
   *
   * <p>★ <b>逐值设计（两个借款人 × 两个放贷人）</b>：
   *
   * <pre>
   * 借款人（按 id 升序）：hh-b1 缺口 100、hh-b2 缺口 300
   * 放贷人（按可贷额降序）：hh-l2 可贷 400、hh-l1 可贷 200
   * ⇒ 逐笔：l2→b1 100（l2 余 300）、l2→b2 300（l2 余 0）⇒ 2 笔、Σ 400、贷方只用了 l2
   * ⇒ 利率：D=400 < S=600 ⇒ 20 → 19（本轮合同仍写**本轮**利率 20 —— 轮内价格不变）
   * </pre>
   */
  @Test
  void lendingOnlyMovesMoneyAndEveryContractHasDistinctDebtorAndCreditor() {
    MarketSettlementFixtures.World world =
        poolWorld(Map.of(BORROWER, 0L, BORROWER_2, 0L, LENDER, 212L, LENDER_2, 412L), 1L);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    Map<HouseholdId, Long> need = new LinkedHashMap<>();
    need.put(BORROWER, 100L);
    need.put(BORROWER_2, 300L);

    long moneyBefore = totalSilver(world);
    MarketSettlement.MarketRound session = marketRound(world, debts);
    MoneyMarketSettlement.Round round =
        MoneyMarketSettlement.settle(
            session,
            input(
                table(DEFAULT_RATE, DEFAULT_ALPHA),
                List.of(BORROWER, BORROWER_2, LENDER, LENDER_2),
                need));

    assertThat(round.demandMilli()).as("需求 = 100 + 300").isEqualTo(400L);
    assertThat(round.supplyMilli()).as("供给 = (212−12) + (412−12) = 200 + 400").isEqualTo(600L);
    assertThat(round.loans()).as("2 笔（b1 从 l2 借 100；b2 从 l2 借 300）").isEqualTo(2);
    assertThat(round.borrowers()).as("2 个借款人").isEqualTo(2);
    assertThat(round.lenders()).as("1 个放贷人（l2 的可贷额够两笔）").isEqualTo(1);
    assertThat(round.lentMilli()).as("Σ 本金 = 400").isEqualTo(400L);
    assertThat(round.loanRateNext()).as("D=400 < S=600 ⇒ 下一轮利率 20 → 19").containsEntry(ZONE, 19L);

    // ★ 守恒式：借贷只搬货币（Σ 不变、不铸钱）。
    assertThat(totalSilver(world)).as("★ Σ货币不变（只搬不铸）").isEqualTo(moneyBefore);
    assertThat(moneyBefore).as("★ 非空转守卫：本世界的货币总量 > 0（否则守恒断言什么都没验）").isEqualTo(624L);

    // ★ 逐户逐值：借方 +、贷方 −，且"钱腿只搬钱"（goods 空）。
    assertThat(world.silverOf(BORROWER)).as("b1 0 → 100").isEqualTo(100L);
    assertThat(world.silverOf(BORROWER_2)).as("b2 0 → 300").isEqualTo(300L);
    assertThat(world.silverOf(LENDER)).as("l1 未被用到：212 不动").isEqualTo(212L);
    assertThat(world.silverOf(LENDER_2)).as("l2 412 − 400 = 12").isEqualTo(12L);

    List<Transfer> transfers = session.ledger().toLedger().transfers();
    assertThat(transfers).as("2 笔借款 = 2 条转移（钱腿）").hasSize(2);
    long sum = 0L;
    for (Transfer transfer : transfers) {
      assertThat(transfer.reason())
          .as("★ 唯一标的 = 借款本金（LOAN_PRINCIPAL）")
          .isEqualTo(TransferReason.LOAN_PRINCIPAL);
      assertThat(transfer.goods())
          .as("★ 钱腿只搬钱：商品腿恒空（货一律走正规撮合，S-12 与旧 creditRound 的关键区别）")
          .isEmpty();
      assertThat(transfer.money()).as("只搬本区计价币（不允许凭空多一种钱）").containsOnlyKeys(SILVER);
      assertThat(transfer.from()).as("★ 债务人 ≠ 债权人（不是自转移）").isNotEqualTo(transfer.to());
      sum += transfer.money().getOrDefault(SILVER, 0L);
    }
    assertThat(sum).as("Σ 逐笔 = 交出物里的 lentMilli").isEqualTo(round.lentMilli());

    assertThat(debts).as("2 笔借款 ⇒ 2 条合同（债务人不同 ⇒ 不同合同）").hasSize(2);
    for (DebtContract contract : debts.values()) {
      assertThat(contract.debtor()).as("★ 债务人 ≠ 债权人（每条合同都查）").isNotEqualTo(contract.creditor());
      assertThat(contract.principal()).as("本金只可能是 100 / 300").isIn(100L, 300L);
      assertThat(contract.terms().interestRatePerMillePerCycle())
          .as("★ 轮内价格不变：本轮合同写**本轮**清算利率 20（不是写回的 19）")
          .isEqualTo((int) DEFAULT_RATE);
      assertThat(contract.dueCycle()).as("到期 = 下一个周期（S-12 与赊购同一条）").hasValue(DUE_CYCLE);
    }
    assertThat(
            debts.values().stream()
                .filter(contract -> contract.debtor().equals(BORROWER))
                .map(DebtContract::creditor)
                .toList())
        .as("b1 的债权人是放贷人 l2（★ 不是'空气'，也不是卖方）")
        .containsExactly(LENDER_2);
  }

  // ── ⑥ 端到端：借钱真能买到货（S-12①②③ + §6.3 判据 1/3） ─────────────────────────────────────

  /**
   * ★★★ <b>判据出处：S-12②（次序 = 挂商品 → 挂钱 → 借钱 → 买货）+ S-12①（钱腿只搬钱、货走正规撮合）+ §6.3 判据 3 的负向面（W2
   * 的"钱清零仍拿货"在**有放贷池**时的正向形态）</b>。
   *
   * <p>★ <b>装置</b>：一格、一个市场（牌价 10 毫/单位）、三个家户 —— 卖方（有粮无钱）、买方（<b>现金 0</b>、当日有口粮需求）、 放贷人（0 人口、只有 200
   * 毫银）。买方的那张买单的目标量 = 生活保留（35 天口粮 = {@code lifeReserveGrain(1)}）。
   *
   * <p>★ <b>逐值预期</b>：需求 = 应付货款 = ⌈2905 × 10 ÷ 1000⌉ = 30；供给 = 200 − 12×0 − 0 = 200 ⇒ 借 30 ⇒ 买方 账户
   * <b>0 → +30 → −30</b>（先借后付，结算后归 0），贷方 200 → 170，卖方 0 → 30，成交价<b>仍是牌价 10</b>。
   */
  @Test
  void borrowedMoneyBuysGoodsInTheSameRoundBeforeMatching() {
    MarketSettlementFixtures.World world = buyerSellerLenderWorld(200L);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    MarketSettlementFixtures.Round base = MarketSettlementFixtures.round(world, world.defaultsOf());
    MarketSettlement.MarketRound round = base.round().withDeferredPayments(DUE_CYCLE, debts);
    long moneyBefore = totalSilver(world);

    // ★ 非空转守卫：买方真的现金 0（否则"借钱才买得到"这句话什么都没验）。
    assertThat(world.silverOf(BUYER)).as("装置前提：买方现金 0").isZero();
    assertThat(world.grainOf(BUYER)).as("装置前提：买方无粮").isZero();
    long expectedQuantity = MarketSettlementFixtures.lifeReserveGrain(1L);
    long expectedPayment = payment(expectedQuantity, GRAIN_PRICE);
    assertThat(expectedPayment)
        .as("复算：应付货款 = ⌈%d × %d ÷ 1000⌉ = %d", expectedQuantity, GRAIN_PRICE, expectedPayment)
        .isEqualTo(30L);

    try (TestLogCapture market = TestLogCapture.attach(EconomyLog.MARKET_LOGGER_NAME, Level.DEBUG);
        TestLogCapture debt = TestLogCapture.attach(EconomyLog.DEBT_LOGGER_NAME, Level.TRACE)) {
      MarketSettlement.MarketOutcome outcome =
          MarketSettlementFixtures.settle(
              world, new MarketSettlementFixtures.Round(world, round, base.ledger()));

      // ── ① 借钱（S-12② 的"挂钱 → 借钱"）：一笔，本金 = 买方的应付货款，债权人是放贷人 ──
      String loan = lineOf(debt, "MONEY_MARKET_LOAN");
      assertThat(textFieldOf(loan, "lender")).as("贷方").isEqualTo(LENDER.value());
      assertThat(textFieldOf(loan, "borrower")).as("借方 = 现金 0 的买方").isEqualTo(BUYER.value());
      assertThat(longFieldOf(loan, "amountMilli"))
          .as("★ 借入额 = 需求 − 现金 = %d − 0", expectedPayment)
          .isEqualTo(expectedPayment);
      assertThat(longFieldOf(loan, "ratePerMillePerCycle"))
          .as("本轮利率 = 出厂 20‰")
          .isEqualTo(DEFAULT_RATE);

      // ── ② 同一轮用它成交（S-12② 的"买货"）：成交价不变、货款 = 借入额、现金腿真的走了 ──
      //   ★ 次序说明（变异自证的需要）：这一组放在"合同条数"之前 —— 若"借到的钱没被花掉"（M3 变异体：
      //     订单预算不补），这里必须**第一个**红在"实付现金 = 借入额"上，而不是让下游的"多出一条赊购合同"先报。
      assertThat(outcome.report().fills()).as("恰一笔成交").hasSize(1);
      MarketReport.Fill fill = outcome.report().fills().get(0);
      assertThat(fill.buyer()).as("买方").isEqualTo(HouseholdActors.of(BUYER));
      assertThat(fill.seller()).as("卖方").isEqualTo(HouseholdActors.of(SELLER));
      assertThat(fill.quantity()).as("数量只受需求约束（35 天口粮）").isEqualTo(expectedQuantity);
      assertThat(fill.unitPriceMilli()).as("★ 成交价 = 本节牌价，借钱不改价").isEqualTo(GRAIN_PRICE);
      assertThat(fill.goodsPaymentMilli())
          .as("★ 实付现金 = 借入额（借到的钱在同一轮真被花掉）")
          .isEqualTo(expectedPayment);
      assertThat(fill.paymentCurrency()).as("货款铸在买方支付币上").isEqualTo(SILVER);

      // ── ①′ 合同：恰一条**借款**合同（本金 = 应付货款、债权人是放贷人）—— 借到钱并花掉之后才可能只有这一条 ──
      assertThat(debts).as("恰一条借款合同（借到的钱付清了 ⇒ 没有第二条赊购合同）").hasSize(1);
      DebtContract contract = debts.values().iterator().next();
      assertThat(contract.debtor()).as("★ 债务人是买方").isEqualTo(BUYER);
      assertThat(contract.creditor()).as("★ 债权人是**放贷人**（不是卖方 —— 这一笔是货币市场借的钱）").isEqualTo(LENDER);
      assertThat(contract.principal()).as("本金 = 借入额").isEqualTo(expectedPayment);

      // ── ③ 逐值账目：借方先 + 后 −（结算后归 0）、贷方 −、卖方 + ──
      assertThat(world.silverOf(BUYER))
          .as("★ 买方 0 → +%d（借）→ −%d（付）⇒ 结算后 0", expectedPayment, expectedPayment)
          .isZero();
      assertThat(world.silverOf(LENDER))
          .as("贷方 200 → %d", 200L - expectedPayment)
          .isEqualTo(200L - expectedPayment);
      assertThat(world.silverOf(SELLER))
          .as("卖方 0 → +%d（真收到钱，不是收债权）", expectedPayment)
          .isEqualTo(expectedPayment);
      assertThat(world.grainOf(BUYER)).as("买方拿到货").isEqualTo(expectedQuantity);
      assertThat(world.grainOf(SELLER)).as("卖方按毛量出货").isEqualTo(200_000L - expectedQuantity);
      assertThat(totalSilver(world)).as("★ Σ货币不变（借贷只搬钱；本用例无铸币）").isEqualTo(moneyBefore);

      // ── ④ 钱腿与货腿分得开（S-12①：钱腿只搬钱） ──
      List<Transfer> ledgerTransfers = base.ledger().toLedger().transfers();
      Transfer loanLeg =
          ledgerTransfers.stream()
              .filter(transfer -> transfer.reason() == TransferReason.LOAN_PRINCIPAL)
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("生产账本里没有 LOAN_PRINCIPAL 钱腿: " + ledgerTransfers));
      assertThat(loanLeg.money())
          .as("★ 钱腿：放贷人 → 买方 %d 毫", expectedPayment)
          .isEqualTo(Map.of(SILVER, expectedPayment));
      assertThat(loanLeg.goods()).as("★ 钱腿不碰任何商品").isEmpty();
      long loanLegs =
          ledgerTransfers.stream().filter(t -> t.reason() == TransferReason.LOAN_PRINCIPAL).count();
      assertThat(loanLegs).as("恰一条借款钱腿").isEqualTo(1L);
      assertThat(
              ledgerTransfers.stream()
                  .filter(transfer -> !transfer.goods().isEmpty())
                  .map(Transfer::goods)
                  .toList())
          .as("★ 货一律仍走正规撮合（唯一一条货腿 = 买方那张成交）")
          .containsExactly(Map.of(GRAIN, expectedQuantity));
      assertThat(market.lines())
          .as("★ 借到的钱只经'补进订单预算'这一条路进撮合（预算在下单时是快照）")
          .anyMatch(line -> line.contains("event=MONEY_MARKET_BUDGET_TOPUP"));

      // ── ⑤ 利率写回：本轮 D=30 < S=200 ⇒ 下一轮 20 → 19（持久在价表上） ──
      assertThat(outcome.marketTables().get(ZONE).loanRatePerMille())
          .as("★ 利率随供需写回价表（下一轮生效）：30 < 200 ⇒ 20 → 19")
          .isEqualTo(19L);
      // ★ 同一张价表上的"商品价"那一栏由**既有的自适应改价**负责（本轮 S ≫ D ⇒ 10 → 9）——
      //   本组断言只钉"两个字段互不干扰"：自适应改价不得把货币市场两项打回出厂值（W5.5 账本 ③-5 记的 withPrices
      //   漏带字段那一类坑），而货币腿也不得改商品价那一栏。
      assertThat(outcome.marketTables().get(ZONE).prices())
          .as("★ 商品价那一栏仍走既有的自适应改价（本轮供过于求 ⇒ 10 → 9；与货币腿无关）")
          .containsEntry(GRAIN, 9L);
      assertThat(outcome.marketTables().get(ZONE).loanRateAlphaPerMille())
          .as("★ α 逐值带过（withPrices 不得把货币市场两项打回出厂值）")
          .isEqualTo(DEFAULT_ALPHA);
      assertThat(fill.unitPriceMilli())
          .as("★ 轮内价格不变：本轮成交价仍是本轮开盘价 10（改价只管下一轮）")
          .isEqualTo(GRAIN_PRICE);
      assertThat(outcome.report().unfilled())
          .as("★ 负向：有放贷池时不得再出现 NO_BUDGET（旧拒绝路径确实被挡住）")
          .noneMatch(
              unfilled ->
                  unfilled.reason()
                      == io.mosire.simos.economy.api.market.MarketUnfilledReason.NO_BUDGET);
    }
  }

  // ── ⑦ 端到端：池空 ⇒ 借不到 ⇒ 退赊购（S-12④⑦ + §6.3 判据 3） ─────────────────────────────

  /**
   * ★★★ <b>判据出处：S-12④（池空 ⇒ 借不到）+ S-12⑦（赊购保留：「**肯定保留啊，算是债务的一种**」）</b>。
   *
   * <p>★ 它是上一个用例的<b>判别对照</b>：世界其余部分逐值相同，只把放贷人的 200 毫银清零 ⇒ 供给 0 ⇒ 买方借不到 ⇒ 但
   * <b>货照拿</b>（未付价款记成对<b>卖方</b>的债），且贷方一分钱没动。两条用例合起来证明"能不能买到货"取决于放贷池， 而不是"买方有没有钱"。
   */
  @Test
  void emptyPoolFallsBackToDeferredPaymentToTheSeller() {
    MarketSettlementFixtures.World world = buyerSellerLenderWorld(0L);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    MarketSettlementFixtures.Round base = MarketSettlementFixtures.round(world, world.defaultsOf());
    MarketSettlement.MarketRound round = base.round().withDeferredPayments(DUE_CYCLE, debts);
    long expectedQuantity = MarketSettlementFixtures.lifeReserveGrain(1L);
    long expectedPayment = payment(expectedQuantity, GRAIN_PRICE);

    try (TestLogCapture market =
        TestLogCapture.attach(EconomyLog.MARKET_LOGGER_NAME, Level.DEBUG)) {
      MarketSettlement.MarketOutcome outcome =
          MarketSettlementFixtures.settle(
              world, new MarketSettlementFixtures.Round(world, round, base.ledger()));

      // ① 借不到：★ 读数是 MONEY_MARKET_WHY（逐区两簿：需求 30、供给 0、借出 0）。
      //   ★★ W5.5-fix3 的发现 2（池空读数不对称，已修）：**旧**行为是 {@code MONEY_MARKET_UNFILLED}（逐借款人缺口读数）
      //   只在"池非空但不够"时发 —— 配对循环条件是 {@code demandTotal > 0 && supplyTotal > 0}；池空（supply == 0）时
      //   {@code ZoneRound.unmetMilli} 读 0、该行也不发（"需求 30 全落空"在日志里读成"没有缺口"= 读数说谎）。
      //   **新**行为两条路共用同一把尺（同一定序、同一发射点）：池空 ⇒ {@code unmetMilli} = Σ 缺口（= demand − lent）、
      //   逐借款人落 {@code MONEY_MARKET_UNFILLED reason=pool-empty}。⇒ 本用例按**新**口径钉正向读数（见 ①′/①″）；
      //   "池非空但不够"那条路（reason=pool-exhausted）由
      //   {@link #borrowerHasNoOwnLimitAndTheOnlyCapIsThePool} 覆盖。
      String why = lineOf(market, "MONEY_MARKET_WHY");
      long whyDemand = longFieldOf(why, "demandMilli");
      long whyLent = longFieldOf(why, "lentMilli");
      assertThat(whyDemand).as("需求 = 应付货款 %d", expectedPayment).isEqualTo(expectedPayment);
      assertThat(longFieldOf(why, "supplyMilli")).as("★ 池空（放贷人现金 0 ⇒ 可贷 0）").isZero();
      assertThat(whyLent).as("借到 0").isZero();
      assertThat(longFieldOf(why, "loans")).as("一笔都没有").isZero();
      assertThat(longFieldOf(why, "borrowers")).as("没有借款人拿到钱").isZero();

      // ①′ ★★ 正向读数（W5.5-fix3 的发现 2；旧 → 新 + 依据逐条具名）：
      //   旧：池空 ⇒ {@code unmetMilli} 读 0，且 MONEY_MARKET_UNFILLED 一行都不发；
      //   新：{@code unmetMilli} = demandMilli − lentMilli = 30（> 0），并逐借款人落 reason=pool-empty 的缺口行。
      //   依据：恒等式 unmetMilli == demandMilli − lentMilli 就是 S-12④ 的读数面（缺口 = 需求 − 真借到），
      //   池空与池不够只有"归因"不同、缺口口径必须是同一个。
      long whyUnmet = longFieldOf(why, "unmetMilli");
      assertThat(whyUnmet)
          .as(
              "★ 恒等式 unmetMilli == demandMilli − lentMilli（实得 %d == %d − %d；W5.5-fix3 的发现 2）",
              whyUnmet, whyDemand, whyLent)
          .isEqualTo(whyDemand - whyLent);
      assertThat(whyUnmet)
          .as("★ 池空 ⇒ 缺口必须 > 0（旧口径读 0 = 读数说谎：需求 %d 全落空却读成没有缺口）", whyDemand)
          .isPositive();

      // ①″ 逐借款人缺口行（W5.5-fix3 的两条具名归因互斥）：池空 ⇒ reason=pool-empty。
      String unfilled = lineOf(market, "MONEY_MARKET_UNFILLED");
      assertThat(longFieldOf(unfilled, "borrowedMilli")).as("池空 ⇒ 借到 0").isZero();
      assertThat(longFieldOf(unfilled, "unmetMilli"))
          .as("★ 逐借款人缺口 = 应付货款 %d（与 WHY 的 unmetMilli 同一个数）", expectedPayment)
          .isEqualTo(expectedPayment);
      assertThat(textFieldOf(unfilled, "reason"))
          .as("★ 归因具名：pool-empty（与'池非空但不够'的 pool-exhausted 互斥）")
          .contains("pool-empty");

      // ② 货照拿（赊购保留）+ 现金腿一分没走。
      assertThat(outcome.report().fills()).as("★ 池空不影响拿货（赊购是最后一道）").hasSize(1);
      MarketReport.Fill fill = outcome.report().fills().get(0);
      assertThat(fill.quantity()).as("数量仍只受需求约束").isEqualTo(expectedQuantity);
      assertThat(fill.unitPriceMilli()).as("★ 成交价不变").isEqualTo(GRAIN_PRICE);
      assertThat(fill.goodsPaymentMilli()).as("★ 现金付 0（钱腿没走）").isZero();
      assertThat(world.grainOf(BUYER)).as("买方拿到货").isEqualTo(expectedQuantity);
      assertThat(world.silverOf(SELLER)).as("卖方没收到钱（收的是债权）").isZero();
      assertThat(world.silverOf(LENDER)).as("★ 放贷人（现金 0）一分钱没动、也没被借成负数").isZero();

      // ③ 记对卖方的债（S-12⑦）。
      assertThat(debts).as("恰一条债：未付价款").hasSize(1);
      DebtContract contract = debts.values().iterator().next();
      assertThat(contract.debtor()).as("债务人是买方").isEqualTo(BUYER);
      assertThat(contract.creditor()).as("★ 债权人 = 卖方（借不到钱时的最后一道）").isEqualTo(SELLER);
      assertThat(contract.principal())
          .as("本金 = 全部未付价款 %d", expectedPayment)
          .isEqualTo(expectedPayment);

      // ④ 次序（同一 logger 内的行序）：钱腿的判断发生在买货那一步之前。
      assertThat(indexOf(market, "MONEY_MARKET_WHY"))
          .as("★ 次序：MONEY_MARKET_WHY（挂钱/借钱段）必须早于 MARKET_DEFERRED_PAYMENT（买货段的未付价款记债）")
          .isLessThan(indexOf(market, "MARKET_DEFERRED_PAYMENT"));

      // ⑤ 利率：池空 = 极端"缺钱"（D=30 > S=0）⇒ 下一轮 20 → 21（与商品价同一套算式）。
      assertThat(outcome.marketTables().get(ZONE).loanRatePerMille())
          .as("★ 缺钱 ⇒ 利率升 20 → 21")
          .isEqualTo(21L);
    }
  }

  // ── 夹具与纯函数助手 ─────────────────────────────────────────────────────────────────────

  private static final HouseholdId BORROWER = HouseholdId.parse("hh-b1");

  private static final HouseholdId BORROWER_2 = HouseholdId.parse("hh-b2");

  private static final HouseholdId LENDER = HouseholdId.parse("hh-l1");

  private static final HouseholdId LENDER_2 = HouseholdId.parse("hh-l2");

  private static final HouseholdId TREASURY = HouseholdId.parse("hh-gov-mm");

  private static final HouseholdId SELLER = HouseholdId.parse("hh-seller");

  private static final HouseholdId BUYER = HouseholdId.parse("hh-buyer");

  /**
   * ★ 一个"纯货币腿"的世界：{@code cashByHousehold} 给每户的银余额，两个放贷人家户的人口 = {@code lenderPopulation} （生活储备 = 12
   * × 人口）；其余家户按 1 人计。
   *
   * <p>★ <b>为什么借款人不给商品需求</b>：纯层的需求由 {@link #need} 显式给定（= 调用方按"买盘目标 × 本区牌价"折出的 "本期生产花费所需现金"）——
   * 夹具不复制那条折算；端到端层才让真买槽去折（见 {@link #buyerSellerLenderWorld}）。
   */
  private static MarketSettlementFixtures.World poolWorld(
      Map<HouseholdId, Long> cashByHousehold, long lenderPopulation) {
    MarketSettlementFixtures.Builder builder =
        MarketSettlementFixtures.builder().market(H1, GRAIN_PRICE);
    for (Map.Entry<HouseholdId, Long> entry : cashByHousehold.entrySet()) {
      boolean isLender = entry.getKey().equals(LENDER) || entry.getKey().equals(LENDER_2);
      builder.household(
          entry.getKey(),
          H1,
          isLender ? lenderPopulation : 1L,
          Map.of(),
          Map.of(SILVER, entry.getValue()),
          Map.of());
    }
    return builder.build();
  }

  /** 纯层的需求表：借款人缺口 = {@code borrowerNeed}；放贷人自己的生产花费 = {@code lenderOwnCost}。 */
  private static Map<HouseholdId, Long> need(long borrowerNeed) {
    return need(borrowerNeed, 40L);
  }

  private static Map<HouseholdId, Long> need(long borrowerNeed, long lenderOwnCost) {
    Map<HouseholdId, Long> need = new LinkedHashMap<>();
    need.put(BORROWER, borrowerNeed);
    need.put(LENDER, lenderOwnCost);
    return need;
  }

  /** 一张"只给 grain 定价"的价表（利率/α 显式给，避免依赖缺省）。 */
  private static MarketTable table(long loanRatePerMille, long loanRateAlphaPerMille) {
    return new MarketTable(
        SILVER, Set.of(), Map.of(GRAIN, GRAIN_PRICE), loanRatePerMille, loanRateAlphaPerMille);
  }

  /** 货币腿的输入：一区、给定参与户与逐户"本期生产花费所需现金"；排序面 = 空快照（名次未知 ⇒ 按 id 稳定序）。 */
  private static MoneyMarketSettlement.Input input(
      MarketTable table, List<HouseholdId> households, Map<HouseholdId, Long> needByHousehold) {
    Map<HouseholdId, HexCoord> hexes = new LinkedHashMap<>();
    for (HouseholdId household : households) {
      hexes.put(household, H1);
    }
    return new MoneyMarketSettlement.Input(
        Map.of(ZONE, table),
        List.of(ZONE),
        SettlementOrderBook.empty(DAY),
        Map.of(ZONE, SILVER),
        Map.of(ZONE, households),
        Map.of(ZONE, needByHousehold),
        hexes,
        DUE_CYCLE);
  }

  /** 真 {@code MarketRound}（走夹具的唯一生产构造器），并打开"未付价款记债"能力（货币腿的前置）。 */
  private static MarketSettlement.MarketRound marketRound(
      MarketSettlementFixtures.World world, Map<DebtContractId, DebtContract> debts) {
    return marketRound(world, debts, null);
  }

  private static MarketSettlement.MarketRound marketRound(
      MarketSettlementFixtures.World world,
      Map<DebtContractId, DebtContract> debts,
      GovernmentMarketMandatePlan plan) {
    MarketSettlement.MarketRound round =
        MarketSettlementFixtures.round(world, world.defaultsOf())
            .round()
            .withDeferredPayments(DUE_CYCLE, debts);
    return plan == null ? round : round.withGovMandates(plan);
  }

  /** S-12⑥ 的判据面：这份计划把 {@link #TREASURY} 认成"只按授权下单"（= 国库户）。 */
  private static GovernmentMarketMandatePlan treasuryPlan() {
    Government government =
        new Government(
            new GovernmentId("gov-mm"), "world", HouseholdActors.of(TREASURY), Set.of(SILVER), 0L);
    return GovernmentMarketMandatePlan.of(
        Map.<MarketMandateId, GovernmentMarketMandate>of(),
        Map.of(government.id(), government),
        DAY);
  }

  /** 一个区的下一轮利率读数（{@code null} = 不改 ⇒ α=0 冻结的那一支）。 */
  private static Long rateNext(
      Map<HouseholdId, Long> cashByHousehold,
      long borrowerNeed,
      long lenderOwnCost,
      MarketTable table) {
    MarketSettlementFixtures.World world = poolWorld(cashByHousehold, 1L);
    MoneyMarketSettlement.Round round =
        MoneyMarketSettlement.settle(
            marketRound(world, new LinkedHashMap<>()),
            input(table, List.of(BORROWER, LENDER), need(borrowerNeed, lenderOwnCost)));
    return round.loanRateNext().get(ZONE);
  }

  /**
   * ★ 端到端世界（一格 / 一个市场 / 三个家户）：卖方（有粮无钱、当日口粮需求）、买方（<b>现金 0</b>、无粮、当日口粮需求）、 放贷人（0 人口、{@code
   * lenderCash} 毫银、无需求）—— 与 {@code DebtBasedClearingAcceptanceTest#zeroCashWorld} 同形，只多一个"只有钱"的放贷人。
   */
  private static MarketSettlementFixtures.World buyerSellerLenderWorld(long lenderCash) {
    return MarketSettlementFixtures.builder()
        .market(H1, GRAIN_PRICE)
        .household(
            SELLER,
            H1,
            1L,
            Map.of(GRAIN, 200_000L),
            Map.of(),
            Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
        .household(
            BUYER,
            H1,
            1L,
            Map.of(),
            Map.of(),
            Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
        .household(LENDER, H1, 0L, Map.of(), Map.of(SILVER, lenderCash), Map.of())
        .build();
  }

  /** 买方支付币口径的 Σ（守恒式左/右两项）。 */
  private static long totalSilver(MarketSettlementFixtures.World world) {
    long total = 0L;
    for (HouseholdId household : world.money().keySet()) {
      total += world.silverOf(household);
    }
    return total;
  }

  /** ★ 本类自己按口径复算的应付货款：{@code ⌈数量 × 单价 ÷ 1000⌉}（毫银；不读被测实现）。 */
  private static long payment(long quantity, long unitPrice) {
    return (quantity * unitPrice + 999L) / 1000L;
  }

  /** 某 logger 里第一条含该事件名的行（找不到 ⇒ 当场抛，不静默）。 */
  private static String lineOf(TestLogCapture log, String event) {
    return log.lines().stream()
        .filter(line -> line.contains("event=" + event + " "))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + event + " 事件行: " + log.lines()));
  }

  /** 某 logger 里第一条含该事件名的行的下标（次序断言用）。 */
  private static int indexOf(TestLogCapture log, String event) {
    List<String> lines = log.lines();
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).contains("event=" + event + " ")) {
        return index;
      }
    }
    throw new AssertionError("没有 " + event + " 事件行: " + lines);
  }

  /** 从事件行里取一个整数（{@code k=v}；缺失/非整数 ⇒ 当场抛 —— ★ 不静默当 0）。 */
  private static long longFieldOf(String line, String key) {
    String marker = key + "=";
    int at = line.indexOf(marker);
    if (at < 0) {
      throw new AssertionError("事件行缺少字段 " + marker + " : " + line);
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length()
        && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
      end++;
    }
    if (end == start) {
      throw new AssertionError("事件行的字段不是整数 " + marker + " : " + line);
    }
    return Long.parseLong(line.substring(start, end));
  }

  /** 从事件行里取一个文本字段（读到空白为止）；缺失 ⇒ 当场抛。 */
  private static String textFieldOf(String line, String key) {
    String marker = key + "=";
    int at = line.indexOf(marker);
    if (at < 0) {
      throw new AssertionError("事件行缺少字段 " + marker + " : " + line);
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
      end++;
    }
    return line.substring(start, end);
  }
}
