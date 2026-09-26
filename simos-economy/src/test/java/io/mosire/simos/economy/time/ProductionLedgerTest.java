package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>过渡入账（R5）+ 多日入口 fail-closed（R4/E7）</b>—— S1 阶段 4+5 Task 4 的两条守门用例。
 *
 * <p>★★ <b>本文件的头号判据：不许让 cohort 断粮</b>。产出从 {@code ClassRow} 搬走之后，行里唯一还能收到实物的通道就是 {@code
 * ProductionSettlement} 的 cohort 入账（R5 ③）—— 它一旦缺失，行里那一份实物就<b>静默消失</b>（`ActorEntry` 照旧生成、账面上看不出
 * 少了谁）。故本夹具的断言逐值钉住"<b>行里真的多出了那一笔实付</b>"。
 *
 * <p>★ 夹具（一格、一个农业产业、周期 3 天、贫农 + 地主两行，形制照 {@code EconomySettlementTest}，**只多一条显式 relation**）：
 *
 * <pre>
 * 土地 700 + 300 = 1,000 千分亩；capacityPerUnit = {LAND: 1000} ⇒ 规模 = 1,000 ÷ 1,000 = 1
 * 毛产 = 规模 1 × 7 粮/亩 × 1000 毫/粮 = 7,000；损耗 = 7,000 × (饲料 0‰ + 折旧 30‰) = 210；净产 = 6,790
 * 关系 = 一条 FIXED_IN_KIND_PER_LABOR × LABOR_AMOUNT（100 毫粮 / 1000 千分劳动）→ (0,0)|poor_peasant
 * 贫农本期劳动（**本周期口径**：行 labor 是每日口径，× cycleDays 3）= 58,000 × 1,000‰ ÷ 1000 × 3 = 174,000
 *   ⇒ 应付 = ⌊174,000 ÷ 1000⌋ × 100 = 17,400
 * 付款上限（R6）= 净产 6,790 ⇒ 实付 = min(17,400, 6,790) = **6,790**（上限咬合 —— 这正是 R6 在起作用）
 * 贫农终态粮 = 期初 83,000 − 头 3 天口粮 25,000（= ⌊100 × 10,000 × 3 ÷ 120⌋）+ 实付 6,790 = **64,790**
 * 地主终态粮 = 期初 8,300 − 头 3 天口粮 2,500（= ⌊10 × 10,000 × 3 ÷ 120⌋）= **5,800**（没有规则付给它）
 * </pre>
 *
 * <p>★ <b>量的口径</b>（{@link EconomyVocabulary} 是唯一拼写点）：粮库存 = 毫粮；口粮 = 累计口粮的<b>逐日差分</b>（乘不出来）。
 */
class ProductionLedgerTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CohortKey PEASANT_KEY = new CohortKey(HEX, ResidenceKind.RURAL, PEASANT);
  private static final CohortKey LANDLORD_KEY = new CohortKey(HEX, ResidenceKind.RURAL, LANDLORD);
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-0-farm@0_0");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ESTATE, FARM.value());

  /** 周期天数（3 天 ⇒ 夹具跑满一个周期只要 3 步，字面量全都能手算）。 */
  private static final long CYCLE_DAYS = 3L;

  /** 贫农本期劳动（千分劳动）：58,000 = 100 人 × 580‰ × 投入率 1000‰。 */
  private static final long PEASANT_LABOR_MILLI = 58_000L;

  /** 给养标准（毫粮 / 1000 千分劳动）：100 —— 与真档出厂值（`RegimeRelations` 的 144）无关，**取 100 让字面量好算**。 */
  private static final long SUBSISTENCE_MILLI_PER_LABOR = 100L;

  // ── ① 过渡入账（R5）：cohort 不断粮 ────────────────────────────────────────────────

  /**
   * ★★ <b>"改前有饭吃、改后仍有饭吃"</b>：推满一个周期（3 天）后，贫农行里的粮 = 期初 − 周期口粮 + <b>关系实付</b>。
   *
   * <p>★ 判别力（T4 Step 7 的变异体①）：把 {@code harvest} 里"cohort 入账落消费行"那一步删掉（产出只进 ledger）⇒ 贫农行终态 =
   * 58,000（少了 5,800）⇒ 本条红。<b>这就是"断粮"的可执行证据</b>：产出离开行之后，行里唯一还有实物的通道就是它。
   */
  @Test
  void theCohortGetsItsPaidShareIntoTheConsumptionRow() {
    EconomyFixtures.World world = fixture();

    EconomyData after = advance(world, CYCLE_DAYS);

    // 贫农：83,000 − ⌊100 × 10,000 × 3 ÷ 120⌋ + 6,790 = 64,790
    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("★ I4.3/R5：家户账上的实物 = 期初 − 周期口粮 + 关系实付（算式见类注）")
        .isEqualTo(83_000L - 25_000L + 6_790L);
    // ★★ 不只相等：必须**严格多于**"一点没收到"的那个数 —— 否则"入账缺失"会被一个恰好相等的期望值掩盖。
    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("★ 不许让家户断粮：终态必须严格高于「产出离开行、且一点都没入账」的 58,000")
        .isGreaterThan(83_000L - 25_000L);
    // 地主：8,300 − ⌊10 × 10,000 × 3 ÷ 120⌋ = 5,800（本夹具没有规则付给它）
    assertThat(grainOf(world.goods(), LANDLORD_KEY)).as("地主：期初 − 周期口粮").isEqualTo(8_300L - 2_500L);
    assertThat(after.classes().get(PEASANT_KEY).population()).as("人口不变（这只是账的搬移）").isEqualTo(100L);
  }

  /**
   * ★★ <b>行侧的 {@code FlowRow.income} 口径已改</b>（R1：从"毛产分成"改成"<b>实物入账</b>"）：贫农本期所得 = 实付 5,800，
   * <b>不是</b>毛产/净产的任何一份分成；地主一分没有。
   *
   * <p>★ 判别力：把 {@code FlowRow.income} 改回"毛产分成"（那时代码里已经没有那条链）或把入账写成 {@code put} 而非 {@code merge} ⇒
   * 本条红。
   */
  @Test
  void theRowIncomeIsTheInKindIntakeNotAGrossShare() {
    EconomyData after = advance(fixture(), CYCLE_DAYS);

    assertThat(after.flows().get(PEASANT_KEY).income())
        .as("★ 行侧所得 = 关系实付（实物入账）= 净产全额（上限 6,790 咬合）")
        .containsEntry(GRAIN, 6_790L);
    assertThat(after.flows().get(LANDLORD_KEY).income()).as("没有规则付给地主 ⇒ 所得为空").isEmpty();
    assertThat(after.flows().get(PEASANT_KEY).income().get(GRAIN))
        .as("★ 6,790 ≠ 毛产 7,000 ⇒ 上面那条不可能是「毛产分成」的旧口径（损耗不再进 income）")
        .isNotEqualTo(7_000L);
  }

  // ── ①′ ledger 本身：产出离开 ClassRow 之后的**逐条**落点 ────────────────────────────

  /**
   * ★★ <b>产出的新路径逐条可读</b>：关账那一天（第 3 天）的 {@link ProductionLedger} 里 —— 毛产 7,000 / 损耗 210；
   * 产权条目<b>恰好两条</b>（{@code +净产 6,790 → operator} 与 {@code −实付 5,800 → operator}，E17 的转出条目）； cohort
   * 入账 5,800 落在 {@code (0,0)|poor_peasant}。
   *
   * <p>★★ <b>operator 的净增 = 净产 − 实付 = 990</b> —— 这正是 T5 的 {@code OwnershipBooks} 会落到 {@code
   * ActorData.accounts} 上的那个数（I4.1 的逐 actor 形态）。★ 前两天的账必须是空的（没关账 ⇒ 什么都没发生）。
   */
  @Test
  void theClosingDayLedgerCarriesGrossLossTheTwoEntriesAndTheIntake() {
    EconomyFixtures.World world = fixture();
    EconomyDayStepper stepper = new EconomyDayStepper(world.data(), world.goods());
    List<ProductionLedger> ledgers = new ArrayList<>();
    for (long day = 1L; day <= CYCLE_DAYS; day++) {
      ledgers.add(stepper.step(day));
    }
    stepper.finish();

    // ★ 给养（100 毫粮 / 1000 千分劳动·周期）算出的应付 17,400 被付款上限（净产 6,790）截断 ⇒ 全部付出去。
    assertThat(ledgers.get(0).hasOutput()).as("第 1 天没关账 ⇒ 没有产出").isFalse();
    assertThat(ledgers.get(1).hasOutput()).as("第 2 天没关账 ⇒ 没有产出").isFalse();

    ProductionLedger closing = ledgers.get(2);
    assertThat(closing.grossOf(FARM, GRAIN)).as("毛产 = 规模 1 × 7 × 1000").isEqualTo(7_000L);
    assertThat(closing.lossOf(FARM, GRAIN)).as("损耗 = 毛产 × 30‰").isEqualTo(210L);
    assertThat(closing.inputOf(FARM, GRAIN)).as("本夹具没有配投入 ⇒ 现扣投入为空").isZero();
    // ★★ H2：产出计提**只剩一条**（+净产 → operator）—— 关系实付那一对腿已改道成一条转移。
    assertThat(closing.outputAccruals())
        .as("★ 产出计提恰一条：+净产 6,790 → operator（产出是造出来的，没有对端 ⇒ 不是转移）")
        .containsExactly(new ProductionSettlement.ActorEntry(OPERATOR, HEX, GRAIN, 6_790L));
    // ★★ H2：实付 = 一条 `from=operator → to=家户 actor` 的转移（★ id 的 day 段 = 关账那一天 3、seq 自 1 起）。
    assertThat(closing.transfers())
        .as("★ 实付恰一条转移：from=operator、to=该格贫农家户、原因 = 关系实付、金额 = 实付 6,790")
        .containsExactly(
            new Transfer(
                new TransferId("tr-3-1"),
                3L,
                OPERATOR,
                HouseholdActors.of(new CohortKey(HEX, ResidenceKind.RURAL, PEASANT)),
                HEX,
                Map.of(GRAIN, 6_790L),
                Map.of(),
                TransferReason.RELATION_PAYMENT,
                Optional.empty()));
    assertThat(closing.deferredMoney()).as("本夹具没有货币规则 ⇒ 待办为空").isEmpty();
    // ★★ S4：读数的三个数逐值报得出（应付 ⌊174,000 ÷ 1000⌋ × 100 = 17,400；实付被 R6 截到净产 6,790；
    //   欠 = 17,400 − 6,790 = 10,610）。
    assertThat(closing.ruleSettlements()).as("★ S4：逐规则的应付/实付/欠（只读，不落债权）").hasSize(1);
    assertThat(closing.ruleSettlements().get(0).dueAmount()).as("应付 17,400").isEqualTo(17_400L);
    assertThat(closing.ruleSettlements().get(0).paidNow()).as("实付 6,790").isEqualTo(6_790L);
    assertThat(closing.ruleSettlements().get(0).owed())
        .as("欠 = 应付 − 实付 = 10,610")
        .isEqualTo(10_610L);
    // ★★ I4.1（operator 那一侧）：净增 = 产出计提 6,790 − 转出（转移的 from 那一端）6,790 = 0。
    assertThat(
            closing.outputAccruals().stream()
                    .filter(entry -> entry.actor().equals(OPERATOR))
                    .mapToLong(ProductionSettlement.ActorEntry::delta)
                    .sum()
                - closing.transfers().stream()
                    .filter(transfer -> transfer.from().equals(OPERATOR))
                    .flatMap(transfer -> transfer.goods().values().stream())
                    .mapToLong(Long::longValue)
                    .sum())
        .as("★★ I4.1（operator 那一侧）：净增 = 净产 6,790 − 实付 6,790 = 0（投入不是它出的 ⇒ Input(actor) ≡ 0）")
        .isEqualTo(0L);
    assertThat(
            closing.transfers().stream()
                .filter(transfer -> transfer.to().kind() == ActorKind.HOUSEHOLD)
                .flatMap(transfer -> transfer.goods().values().stream())
                .mapToLong(Long::longValue)
                .sum())
        .as("★★ I4.1（家户那一侧）：实收 = 实付 6,790（这一笔同时计进会话工作副本与 FlowRow.income）")
        .isEqualTo(6_790L);
  }

  // ── ② 多日入口 fail-closed（R4/E7）──────────────────────────────────────────────────

  /**
   * ★★ <b>E7「路径唯一化」的落点</b>：{@link EconomySettlement#settle(EconomyData, long, long)}
   * 这个<b>多日静态入口</b>一旦跨过周期末（有产出）就 <b>fail-closed</b> —— 它没有产权落账口，产出会<b>在账上静默消失</b> （本仓最反对的形态）。
   *
   * <p>★★ <b>H1 起这条 fail-closed 提前到"第一天之前"</b>（裁定 K1）：本入口连**家户账**都没有（它是会话状态） ⇒ 只要世界里有一个 {@code
   * population > 0} 的家户就当场抛，消息改成"**请走 EconomyDayStepper（家户账是会话状态）**"。 于是本夹具（贫农 100 人 + 地主 10
   * 人）撞到的是**第一层**：断言从"产权落账口"改成"家户账"（同一条判据的**新口径**， 不是放宽 —— 它照样是 {@link
   * IllegalStateException}、照样要求消息点名正确的入口）。
   *
   * <p>★ 第二层（"有产出就抛"）仍在，守的是"全零人口的世界照样不许静默丢产出"：{@code settle} 里那两处判断都在。
   *
   * <p>★ 正确的路径有两条：① {@link EconomyDayStepper}（economy 模块内的会话入口，交回当天的 {@code ProductionLedger}）； ②
   * app 协调器（同时看得见 economy + actor 的那个参与者）。消息里两条都点出来。
   */
  @Test
  void theMultiDayEntryIsFailClosedWhenACycleProducesOutput() {
    assertThatThrownBy(() -> EconomySettlement.settle(fixture().data(), 0L, CYCLE_DAYS))
        .as("★ H1/K1：多日入口没有家户账 ⇒ 第一天之前就当场抛（消息点名 EconomyDayStepper 这条正确路径）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("家户账")
        .hasMessageContaining("EconomyDayStepper");
  }

  /**
   * ★★ <b>对照组（H1 已删 —— 主语消失，如实记）</b>：改前这里断言"不跨周期末的区间照旧可用"。
   *
   * <p>★★ <b>为什么整条删掉而不是重算期望值</b>：H1 起多日静态入口 {@code settle} <b>连家户账都没有</b> （裁定 K1：家户账是会话状态）——
   * 只要世界里有一个 {@code population > 0} 的家户，它就在**第一天之前**当场抛 （见 {@code EconomySettlement.settle} 的第一层
   * fail-closed）。⇒ "静态入口对某些区间仍可用"这件事**已经不存在**， 那条对照失去了被对照的另一半（同一入口的"关账要产出就抛"仍在，由上面那条用例守着）。 ★
   * 本条的**判别力没有丢**：它守的"不跨周期末的推进能正常记账"由 {@code EconomySettlementTest} 与 {@code EconomyFlowCycleTest}
   * 的会话形态用例逐值覆盖（那里每天都真的结算了）。
   */

  // ── 推进（R4 的会话形态）────────────────────────────────────────────────────────────

  /**
   * 推满 {@code days} 天：**走 {@link EconomyDayStepper}**（多日静态入口已 fail-closed，见上一条）。
   *
   * <p>★ H1：家户账是**会话状态**（裁定 K1）⇒ 入参是成对的 {@link EconomyFixtures.World}（状态 + 工作副本）。
   */
  static EconomyData advance(EconomyFixtures.World world, long days) {
    EconomyDayStepper stepper = new EconomyDayStepper(world.data(), world.goods());
    for (long day = 1L; day <= days; day++) {
      stepper.step(day);
    }
    return stepper.finish();
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  /**
   * 一格、一个农业产业（3 天周期）、贫农 + 地主两行、**一条显式 relation**（给养给 {@code (0,0)|poor_peasant}）。
   *
   * <p>★ 与 {@code EconomySettlementTest.fixture()} 的差别只有"关系表非空"这一处 —— 于是本文件量到的东西<b>只可能来自关系</b>。
   */
  private static EconomyFixtures.World fixture() {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 0));
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            CYCLE_DAYS,
            0L,
            Map.of(AssetKind.LAND, 1_000L),
            // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            Map.of(),
            slots,
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    Map<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, row(PEASANT_KEY, 100L, PEASANT_LABOR_MILLI, 1000));
    classes.put(LANDLORD_KEY, row(LANDLORD_KEY, 10L, 5_800L, 0));
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★ 显式关系：一条给养规则（`FIXED_IN_KIND_PER_LABOR × LABOR_AMOUNT`，粮），受方 = 该格的贫农 cohort。
    CompensationRule subsistence =
        new CompensationRule(
            RuleType.FIXED_IN_KIND_PER_LABOR,
            new Recipient.ToCohort(new CohortKey(HEX, ResidenceKind.RURAL, PEASANT)),
            Pool.NET_AFTER_INPUTS,
            Weight.LABOR_AMOUNT,
            0,
            SUBSISTENCE_MILLI_PER_LABOR,
            Optional.of(GRAIN),
            Optional.empty(),
            10);
    Map<IndustryId, ProductionRelation> relations = new LinkedHashMap<>();
    relations.put(FARM, new ProductionRelation(FARM, OPERATOR, List.of(subsistence), OPERATOR));
    // ★★ H1（K1）：期初库存进**会话工作副本** —— 与状态成对交出（唯一拼写点在这里）。
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, GRAIN, 83_000L);
    EconomyFixtures.hold(goods, LANDLORD_KEY, GRAIN, 8_300L);
    return new EconomyFixtures.World(
        new EconomyData(
            Optional.of(meta),
            industries,
            classes,
            Map.of(),
            Map.of(),
            Map.of(LOT, new LaborSupply(LOT, 1L, PEASANT_LABOR_MILLI, 0L, 0L)),
            Map.of(
                ALLOCATION,
                new LaborAllocation(ALLOCATION, LOT, OPERATOR, "farm", PEASANT_LABOR_MILLI, 1L)),
            relations),
        goods);
  }

  private static ClassRow row(CohortKey key, long population, long laborMilli, int participation) {
    // ★★ H1：行里**没有** goods 了（裁定 K1）—— 期初库存见上面那份会话工作副本。
    return new ClassRow(
        key,
        population,
        laborMilli,
        participation,
        0L,
        List.of(),
        Map.of(GRAIN, EconomyVocabulary.dailyRationMilli(population, 1L)),
        Map.of());
  }

  /** 某家户的粮余额（★ H1：从**会话工作副本**读 —— 行里没有 {@code goods} 了）。 */
  private static long grainOf(Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key) {
    return EconomyFixtures.grainOf(goods, key);
  }
}
