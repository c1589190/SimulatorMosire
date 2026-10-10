package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>A 计划（运输服务商品化）的验收判据</b>：{@code
 * docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md} <b>v1.3</b> §5 I-H1..I-H7 /
 * §6 T-H1..T-H4、N-H1、N-H3。
 *
 * <pre>
 * T-H2  跨格货单**派生**运输服务需求（数量与既有运费口径可核对）；买不到 ⇒ 该笔不成交（具名）
 * T-H3  跑商的收益走**标准企业利润**（EnterpriseProfitBook 读同一批 MARKET_TRADE 钱腿）；MerchantProfitBook 不再并存
 *       （后一半是**结构性**的：类已删除，源码面由 util 的退役护栏把守）
 * T-H4  预留自动成立：跑商家户的工具由**产业声明的投入**保留（不是"下夹一趟"的硬编码）
 * I-H5  运费不得既走商品成交又走 CARRIER_FEE 私有腿（本类逐笔核账本）
 * N-H1  无跑商/无跨格需求的世界 ⇒ 逐值不变（状态 dump 对照）
 * N-H3  两跑确定性：同参数两跑的状态 dump 逐字节相同
 * </pre>
 *
 * <p>★ <b>口径来源</b>：判据照设计书写，不按代码反推。数量口径的"可核对"落点是 {@link CapacityDemand}（既有运力算式）与 {@link
 * HaulService#unitFreightMilli}（既有运费算式骨架换第三因子）—— 本类的第一个用例把这两条与 {@code
 * MarketSettlement.freightUnitMilli} 的**逐值重合点**钉住。
 *
 * <p>★ 夹具：{@link MarketSettlementFixtures}（单区两格；跨格需求 = 买方 35 天生活保留缺口）。
 */
class HaulServiceCommodityAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;

  private static final HouseholdId SELLER = HouseholdId.parse("hh-haul-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-haul-buyer");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-haul-carrier");
  private static final HouseholdId LOCAL_BUYER = HouseholdId.parse("hh-haul-local-buyer");

  private static final long SELLER_GRAIN = 200_000L;
  private static final long BUYER_SILVER = 10_000_000L;
  private static final long GRAIN_PRICE = 10L;
  private static final long HAUL_PRICE = 4L;

  /** 服务成市格的承运户：手上有一大批服务货（够运整笔跨格需求）。 */
  private static final long CARRIER_HAUL_MILLI = 10_000_000L;

  // ── ① T-H2 口径：派生需求量与既有运费算式可核对（纯函数，不依赖任何夹具） ────────────────

  /**
   * ★★ <b>派生需求的"数量"就是既有运费算式的物理维</b>：{@code 服务需求(毫服务) = ⌈数量 × max(1, 基础费 × (1000 + 路线费率‰)) ÷
   * 1000⌉}（{@link CapacityDemand#workMilliOf}），且单位运费的骨架与既有 {@code
   * MarketSettlement.freightUnitMilli} 同形 —— 后者的第三因子 {@code (1000 + 承运成本‰)} 被换成 {@code 服务牌价 ×
   * 1000}。
   *
   * <p>★ <b>两条口径的逐值重合点</b>（本用例把它钉成等式，防"新造量纲"）：{@code 服务牌价 = (1000 + 承运成本‰) ÷ 1000} 时两式同值 ⇒ 在 {@code
   * 承运成本 = 0 ⇒ 牌价 1}、{@code 1000 ⇒ 2}、{@code 9000 ⇒ 10} 三点上逐值相等。
   */
  @Test
  void derivedServiceQuantityAndUnitFreightAreCheckableAgainstTheExistingFreightFormula() {
    for (long baseMilli : List.of(0L, 1L, 2L, 3L)) {
      for (long ratePerMille : List.of(0L, 30L, 60L, 1_000L)) {
        long work = CapacityDemand.workPerGoodPerMille(baseMilli, ratePerMille);
        assertThat(work)
            .as("每毫商品的运力耗用 = max(1, 基础费 × (1000 + 费率‰))；base=%s rate=%s", baseMilli, ratePerMille)
            .isEqualTo(Math.max(1L, baseMilli * (1_000L + ratePerMille)));
        for (long quantityMilli : List.of(1L, 999L, 2_905L, 100_000L)) {
          long expected = ceilDiv(quantityMilli * work, 1_000L);
          assertThat(CapacityDemand.workMilliOf(quantityMilli, work))
              .as("服务需求 = ⌈数量 × 耗用 ÷ 1000⌉（数量 %s）", quantityMilli)
              .isEqualTo(expected);
          // ★ 承接口径与需求口径配对（绝不超发）：maxGoodsFor 之后再算耗用 ≤ 原预算。
          long maxGoods = CapacityDemand.maxGoodsFor(expected, work);
          assertThat(CapacityDemand.workConsumedBy(maxGoods, work))
              .as("⌊w × 1000 ÷ f⌋ 再算耗用 ≤ w（向下/向上取整配对）")
              .isLessThanOrEqualTo(expected);
        }
      }
    }
    // ★★ 与既有"承运成本口径"的逐值重合点（服务牌价 = (1000 + 承运成本‰) ÷ 1000）。
    for (long carrierCostPerMille : List.of(0L, 1_000L, 9_000L)) {
      long servicePriceMilli = (1_000L + carrierCostPerMille) / 1_000L;
      for (long baseMilli : List.of(1L, 2L, 3L)) {
        for (long ratePerMille : List.of(0L, 30L, 60L)) {
          long work = CapacityDemand.workPerGoodPerMille(baseMilli, ratePerMille);
          assertThat(HaulService.unitFreightMilli(work, servicePriceMilli))
              .as(
                  "★ 牌价 = (1000+承运成本‰)/1000 ⇒ 与既有 freightUnitMilli 逐值相等（base=%s rate=%s cost=%s）",
                  baseMilli, ratePerMille, carrierCostPerMille)
              .isEqualTo(
                  MarketSettlement.freightUnitMilli(baseMilli, ratePerMille, carrierCostPerMille));
        }
      }
    }
  }

  // ── ② T-H2 + T-H3 + I-H5：跨格货单派生服务需求，运费走服务成交这一条腿 ────────────────────

  /**
   * ★★ <b>一笔跨格货单派生一份运输服务需求，并按服务商品的成交结算</b>：
   *
   * <pre>
   * 成交           跨格粮单被运走（qt 与"服务够运"一致）
   * 单位运费       = HaulService.unitFreightMilli(CapacityDemand.workPerGoodPerMille(粮基础费, 本 lane 费率), 牌价 4)
   * 服务货         承运户的 haul 账户**恰好减少** ⌈成交量 × 本 lane 耗用 ÷ 1000⌉（I-H2：卖出多少就得有多少货）
   * 钱腿           账本里**恰有一条** MARKET_TRADE 打到承运户、金额 = 运费；**零条** CARRIER_FEE（I-H5 不双记）
   * 标准企业利润   同一批钱腿喂 EnterpriseProfitBook ⇒ 该 (merchant, hex) 的 net == 运费（T-H3）
   * </pre>
   */
  @Test
  void crossHexOrderDerivesServiceDemandAndPaysThroughTheStandardTradeLeg() {
    MarketSettlementFixtures.World world = crossHexWorld(CARRIER_HAUL_MILLI);
    MerchantCapacityPool pool = world.serviceCarrierPool(Set.of(H1));
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));

    assertThat(pool.totalCapacityAt(H1))
        .as("服务成市格：运力预算 = 该户手上的服务货（I-H2）")
        .isEqualTo(CARRIER_HAUL_MILLI);
    long haulBefore = world.haulOf(CARRIER);

    MarketSettlement.MarketOutcome outcome =
        MarketSettlementFixtures.settleWithPool(world, round, pool);

    assertThat(outcome.report().fills()).as("跨格成交发生").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.to()).isEqualTo(H2);
    long quantity = fill.quantity();
    long demand = MarketSettlementFixtures.lifeReserveGrain(1L);
    assertThat(quantity).as("★ 服务充足 ⇒ 整笔跨格需求被运走（否则测不到「派生+成交」）").isEqualTo(demand);

    // ── 单位运费：与既有算式可核对（不写死费率：费率从拓扑现取，与本轮撮合读的是同一条函数）──────
    long ratePerMille = world.topology().freightPerMilleBetween(H1, H2, 0L, 0L);
    long baseMilli = world.topology().commodityFreightBaseMilliOf(MarketSettlementFixtures.GRAIN);
    long workPerGoodPerMille = CapacityDemand.workPerGoodPerMille(baseMilli, ratePerMille);
    long expectedUnitFreight = HaulService.unitFreightMilli(workPerGoodPerMille, HAUL_PRICE);
    assertThat(fill.freightPerUnitMilli())
        .as("★ 单位运费 = 服务耗用‰ × 服务牌价 的口径（与 HaulService 同一算式）")
        .isEqualTo(expectedUnitFreight);
    long expectedFreight = ceilDiv(quantity * expectedUnitFreight, 1_000L);
    assertThat(fill.freightMilli()).as("运费 = ⌈成交量 × 单位运费 ÷ 1000⌉").isEqualTo(expectedFreight);

    // ── I-H2：服务货恰好减去"这一笔消耗的服务量"（不凭空造、不静默销毁）────────────────────
    long serviceConsumed = CapacityDemand.workMilliOf(quantity, workPerGoodPerMille);
    assertThat(world.haulOf(CARRIER))
        .as("★ 承运户的服务货 = 卖出的服务量（1 毫服务 ≡ 1 毫商品·程，A1 单位锚）")
        .isEqualTo(haulBefore - serviceConsumed);
    assertThat(serviceConsumed).as("卖出的是正的服务量").isPositive();

    // ── I-H5 / T-H3：钱腿只有一条（MARKET_TRADE），且它就是标准企业利润读的收入腿 ────────────
    ProductionLedger ledger = round.ledger().toLedger();
    List<Transfer> carrierMoneyLegs =
        ledger.transfers().stream()
            .filter(t -> t.to().equals(HouseholdActors.of(CARRIER)))
            .filter(t -> !t.money().isEmpty())
            .toList();
    assertThat(carrierMoneyLegs).as("★ 承运户恰有一条钱腿（不双记）").hasSize(1);
    Transfer freightLeg = carrierMoneyLegs.get(0);
    assertThat(freightLeg.reason())
        .as("★ 服务成交的收入腿走既有 MARKET_TRADE（EnterpriseProfitBook 的收入腿，T-H3）")
        .isEqualTo(TransferReason.MARKET_TRADE);
    assertThat(freightLeg.money().getOrDefault(MarketSettlementFixtures.SILVER, 0L))
        .as("钱腿金额 = 本笔运费")
        .isEqualTo(expectedFreight);
    assertThat(ledger.transfers().stream().map(Transfer::reason))
        .as("★ I-H5：服务成市的 lane 上不得再有 CARRIER_FEE 私有腿")
        .doesNotContain(TransferReason.CARRIER_FEE);

    // ── T-H3：同一批钱腿喂标准企业利润 ⇒ 跑商收益在 EnterpriseProfitBook 一侧可见 ────────────
    ProductionOrganizationId orgId = new ProductionOrganizationId("org:" + CARRIER.value());
    ProductionEnterprise enterprise =
        new ProductionEnterprise(
            orgId,
            DefaultProductionModes.MERCHANT,
            ClassPositionId.parse("merchant:" + CARRIER.value()),
            Optional.of(
                ProductionUnitId.idOf(merchantIndustry().id(), HouseholdActors.of(CARRIER))),
            HouseholdActors.of(CARRIER),
            List.of(),
            List.of(),
            List.of(),
            new Payee.ToActor(HouseholdActors.of(CARRIER)),
            Optional.empty(),
            ProductionEnterprise.Status.ACTIVE,
            ""); // ACTIVE 不携带缺口原因（要原因请用 SHORTAGE）
    EnterpriseProfitBook.CycleAccumulator cycle = new EnterpriseProfitBook.CycleAccumulator();
    cycle.recordDay(MarketSettlementFixtures.DAY, ledger, outcome.report());
    EnterpriseProfitBook.Book book =
        EnterpriseProfitBook.collect(
            cycle,
            Map.of(orgId, enterprise),
            world.units(),
            world.rows(),
            world.industries(),
            world.markets(),
            AccountSession.empty());

    assertThat(book.hasReading(DefaultProductionModes.MERCHANT, H1))
        .as("★ 跑商家户在标准企业利润里有本期读数（T-H3）")
        .isTrue();
    assertThat(book.net(DefaultProductionModes.MERCHANT, H1))
        .as("★ 净收益 = 运费实收（那条 MARKET_TRADE 钱腿）—— 不再有平行的 MerchantProfitBook")
        .isEqualTo(expectedFreight);
  }

  // ── ③ T-H2 负向：买不到服务 ⇒ 该笔不成交（具名） ────────────────────────────────────────

  /**
   * ★★ <b>服务成市但一件服务货都没有 ⇒ 跨格货单不成交</b>（fail-closed，设计书 §3.3「买不到 ⇒ 该笔不成交」）。
   *
   * <p>判据面逐条（**市场侧在前**：判据是"不成交"，池侧读数是解释它为什么买不到）：
   *
   * <pre>
   * ① 市场侧：零成交、具名 LOGISTICS_CAPACITY（不是静默、不是钱的问题）
   * ② 账目侧：买方一分钱没动、卖方一粒粮没少、承运户服务货仍为 0（不成交 = 库房一个字节都没动）
   * ③ 池侧：该格有跑商家户（所以不是"没有承运人"），但服务运力预算 = 0 ⇒ 分配为空、全部转成未获服务
   * </pre>
   */
  @Test
  void noServiceGoodsMeansNoCrossHexFillAndTheReasonIsNamed() {
    MarketSettlementFixtures.World world = crossHexWorld(0L);
    MerchantCapacityPool pool = world.serviceCarrierPool(Set.of(H1));

    long sellerGrainBefore = world.grainOf(SELLER);
    long buyerSilverBefore = world.silverOf(BUYER);
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    MarketSettlement.MarketOutcome outcome =
        MarketSettlementFixtures.settleWithPool(world, round, pool);

    assertThat(outcome.report().fills()).as("★ 买不到服务 ⇒ 该笔不成交（不是「少运一点」）").isEmpty();
    assertThat(outcome.report().unfilledReasonCounts())
        .as("★ 具名 LOGISTICS_CAPACITY（不是口岸/币种/预算）")
        .containsKey(MarketUnfilledReason.LOGISTICS_CAPACITY);
    assertThat(world.silverOf(BUYER)).as("买方一分钱没动").isEqualTo(buyerSilverBefore);
    assertThat(world.grainOf(SELLER)).as("卖方一粒粮没少").isEqualTo(sellerGrainBefore);
    assertThat(world.haulOf(CARRIER)).as("承运户的服务货仍为 0（没凭空造）。").isZero();
    assertThat(round.ledger().toLedger().transfers().stream().map(Transfer::reason))
        .as("零运费腿（CARRIER_FEE 与 MARKET_TRADE 都不许出现）")
        .doesNotContain(TransferReason.CARRIER_FEE, TransferReason.MARKET_TRADE);

    // ── 池侧：为什么买不到（第一现场）────────────────────────────────────────────────
    assertThat(pool.householdCountAt(H1)).as("★ 这一格**有**跑商家户（把「没有承运人」排除掉）").isEqualTo(1L);
    assertThat(pool.totalCapacityAt(H1)).as("★ 但它手上没有服务货 ⇒ 本格服务运力 = 0").isZero();
    long workPerGoodPerMille =
        CapacityDemand.workPerGoodPerMille(
            world.topology().commodityFreightBaseMilliOf(MarketSettlementFixtures.GRAIN),
            world.topology().freightPerMilleBetween(H1, H2, 0L, 0L));
    long requested = MarketSettlementFixtures.lifeReserveGrain(1L);
    MerchantCapacityPool.CarrierAllocation allocation =
        pool.select(H1, H2, requested, workPerGoodPerMille);
    assertThat(allocation.choices()).as("买不到 ⇒ 零分配").isEmpty();
    assertThat(allocation.unallocatedMilli()).as("全部转成未获服务（不静默丢）").isEqualTo(requested);
  }

  // ── ④ T-H1/T-H4 反向：工具存量不再是市场轮准入判据 ─────────────────────────────────────

  /**
   * ★★ <b>工具存量 = 0 的跑商家户照样承运</b> —— A3 把"每趟 ≥ 1,000 毫工具 ⇒ 该次跑商不成立"的门槛整族退役 （工具消耗单套化到 {@code trade}
   * 产业的周期投入，设计书 §3.2 / I-H6）。
   *
   * <p>★ 判别力：旧架构下本用例**必红**（工具 0 &lt; 一趟门槛 ⇒ 零分配、零成交）；这正是"门槛已删"的负向证据。 工具现在只以"运力规模（tier / 议价权）"的身份进池
   * —— 本用例里的承运户因此仍有一份（劳动派生的）池成员身份。
   */
  @Test
  void aCarrierWithZeroToolStillHaulsBecauseThereIsNoMarketRoundThreshold() {
    HouseholdId broke = HouseholdId.parse("hh-haul-broke-tool");
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, MarketSettlementFixtures.grainAndHaulMarket(GRAIN_PRICE, HAUL_PRICE))
            .market(H2, GRAIN_PRICE)
            .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
            .household(BUYER, H2, 1L, 0L, BUYER_SILVER)
            .haulCarrier(broke, H1, 1_000L, CARRIER_HAUL_MILLI)
            .carrier(CARRIER, H1, 0L, 0L) // ★ 工具 0（旧门槛下"一趟都跑不了"）
            .build();
    // 服务成市格的运力预算只看服务货 ⇒ 工具 0 的户不因工具被拦（它也不持有服务货 ⇒ 不进分配，但"不被拦"是结构性的：
    //   select 里已不存在任何读工具的判据）。
    MerchantCapacityPool pool = world.serviceCarrierPool(Set.of(H1));
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    MarketSettlement.MarketOutcome outcome =
        MarketSettlementFixtures.settleWithPool(world, round, pool);

    assertThat(outcome.report().fills()).as("★ 工具 0 不再是「跑不了商」的理由（门槛已删）").hasSize(1);
    assertThat(outcome.report().fills().get(0).quantity())
        .as("整笔跨格需求被运走（服务货充足）")
        .isEqualTo(MarketSettlementFixtures.lifeReserveGrain(1L));
    assertThat(outcome.report().unfilledReasonCounts())
        .as("不许把「能运的」报成运力不足")
        .doesNotContainKey(MarketUnfilledReason.LOGISTICS_CAPACITY);
  }

  // ── ⑤ T-H4：预留由产业声明的投入自动覆盖（不再有"下夹一趟"的硬编码） ──────────────────────

  /**
   * ★★ <b>跑商家户的自家卖单必须给产业声明的周期投入让路</b>（I-H6 / T-H4）：
   *
   * <pre>
   * 夹具        一个跑商家户，挂一条产业 unit：capacityPerUnit={CATTLE:1}、cycleInputPerUnit={CATTLE:{tool:250}}
   *             ⇒ 持有 1 单位 CATTLE ⇒ 计划规模 scale = 1 ⇒ **声明的**工具投入 = 250 毫工具（不是旧特例的 1,000）
   *             它的工具账户 = 5,000 毫 ⇒ 卖单可卖 = 5,000 − 250 = 4,750
   * 判据        ① 卖单量**恰好**扣掉声明投入 ⇒ 预留自动成立（工具不会被自家卖单卖光）
   *             ② 它**不等于**「扣 1,000」（旧 §16 特例的硬编码）⇒ 本用例对「硬编码还在不在」有判别力
   * </pre>
   *
   * <p>★ 为什么预留量取 250 而不是世界里的 100：本夹具刻意取一个**与硬编码不同**的声明值 —— 判据是"预留 = 声明投入"， 不是"预留 = 某个历史常量"。世界
   * {@code trade} 产业那一份（{@code {CATTLE:{tool:100}}} ⇒ 10,000）由 app 侧的 {@code
   * HaulServiceCommoditySeedTest} 钉住配置，两侧合起来覆盖 T-H4。
   */
  @Test
  void ownListingsReserveTheDeclaredIndustryInputNotAHardcodedRunCost() {
    HouseholdId merchant = HouseholdId.parse("hh-haul-reserving-merchant");
    long toolStock = 5_000L;
    long declaredToolPerScale = 250L;
    // ★ 产业 id 必须带 hex 键（参与者的 unit 按"所在格"归集，见 MarketSettlement.participantsFor）⇒ 用既有
    //   唯一拼写点 IndustryHexKeys.id(...)，不手写第二套 id 形状。
    IndustryId industryId = IndustryHexKeys.id("fixture-haul-trade", H1.q(), H1.r());
    Industry trade =
        new Industry(
            industryId,
            "夹具跑商产业",
            new RegimeId("fixture"),
            120L,
            Map.of(AssetKind.CATTLE, 1L),
            Map.of(),
            0L,
            0L,
            Map.of(MarketSettlementFixtures.HAUL, 1L),
            Map.of(AssetKind.CATTLE, Map.of(MarketSettlementFixtures.TOOL, declaredToolPerScale)),
            List.of(new ClassSlot(new SocialClassId("artisan"), "跑商", 1_000)),
            new AllocationRule.Split(1_000, 0));

    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, pricedMarket(GRAIN_PRICE, Map.of(MarketSettlementFixtures.TOOL, 20L)))
            .carrier(merchant, H1, 1_000L, toolStock)
            .industryUnit(merchant, H1, trade, AssetKind.CATTLE, 1L)
            .build();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    MarketReport.SellerOutcome toolOffer =
        outcome.report().sellerOutcomes().stream()
            .filter(s -> s.commodity().equals(MarketSettlementFixtures.TOOL))
            .filter(s -> s.actor().equals(HouseholdActors.of(merchant)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("跑商家户必须挂出工具卖单（否则本用例测不到预留）"));

    assertThat(toolOffer.offeredQty())
        .as("★ 卖单可卖量 = 存量 − **产业声明的**周期投入（预留自动成立，T-H4）")
        .isEqualTo(toolStock - declaredToolPerScale);
    assertThat(toolOffer.offeredQty())
        .as("★ 判别力：它不得等于「存量 − 旧特例的 1,000」（§16 硬编码已撤回）")
        .isNotEqualTo(toolStock - 1_000L);
  }

  // ── ⑥ N-H1：无跑商/无跨格需求 ⇒ 逐值不变 ─────────────────────────────────────────────

  /**
   * ★★ <b>N-H1（I-H3 缺省中性）</b>：同一个世界额外放一个**跑商家户**（有位置、有工具货），但世界里既没有跨格需求、 也没有服务市场 ⇒
   * 既有家户的账目、市场价表、成交/归因读数**逐值不变**（状态 dump 对照）。
   *
   * <p>★ 对照面取"世界里原有的事实"（卖/买双方的账户 + 市场 + 本轮读数）；新增家户自己的账目当然会多出一行 —— 判据问的是"跑商的存在有没有扰动别的部分"。
   */
  @Test
  void aWorldWithoutMerchantHouseholdsIsValueIdenticalToTheSameWorldWithOne() {
    MarketSettlementFixtures.World without = localWorld(false);
    MarketSettlementFixtures.World with = localWorld(true);
    MarketSettlement.MarketOutcome a =
        MarketSettlementFixtures.settle(
            without,
            MarketSettlementFixtures.round(
                without, MarketRegulation.defaultsFor(without.markets())));
    MarketSettlement.MarketOutcome b =
        MarketSettlementFixtures.settle(
            with,
            MarketSettlementFixtures.round(with, MarketRegulation.defaultsFor(with.markets())));

    assertThat(a.report().fills()).as("对照世界必须有成交（否则 dump 相同是平凡的）").hasSize(1);
    assertThat(stateDump(with, b, List.of(SELLER, LOCAL_BUYER)))
        .as("★ 加一个跑商家户 ⇒ 既有世界逐值不变（无跨格需求 + 无服务市场）")
        .isEqualTo(stateDump(without, a, List.of(SELLER, LOCAL_BUYER)));
  }

  // ── ⑦ N-H3：两跑确定性（状态 dump 逐字节相同） ─────────────────────────────────────────

  /**
   * ★★ <b>N-H3</b>：同一份输入（同参数、同夹具）两跑 ⇒ 状态 dump 逐字节相同。
   *
   * <p>★ 选"有跑商家户 + 服务成市 + 跨格成交"的世界，是为了让**运力分配序**（限价升序 → 议价权 → 家户 id）与 逐户账户一起进 dump ——
   * 排序若依赖哈希/插入序（I7 禁 {@code Map.copyOf} 一族），两次 dump 就会漂开。
   *
   * <p>★ 如实记：本用例是**进程内两跑**（同一 JVM、各自新建世界）。"同 store 落盘两跑"（重启后读档再跑）不在本模块的 测试面里（economy 的夹具不落盘）——
   * 见账本"没做的"一节。
   */
  @Test
  void twoIdenticalRunsProduceByteIdenticalStateDumps() {
    List<String> dumps = new ArrayList<>();
    for (int run = 0; run < 2; run++) {
      HouseholdId carrier = HouseholdId.parse("hh-haul-determinism-carrier");
      MarketSettlementFixtures.World world =
          MarketSettlementFixtures.builder()
              .market(H1, MarketSettlementFixtures.grainAndHaulMarket(GRAIN_PRICE, HAUL_PRICE))
              .market(H2, GRAIN_PRICE)
              .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
              .household(BUYER, H2, 1L, 0L, BUYER_SILVER)
              .haulCarrier(carrier, H1, 1_000L, CARRIER_HAUL_MILLI)
              .build();
      MerchantCapacityPool pool = world.serviceCarrierPool(Set.of(H1));
      MarketSettlementFixtures.Round round =
          MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
      MarketSettlement.MarketOutcome outcome =
          MarketSettlementFixtures.settleWithPool(world, round, pool);
      assertThat(outcome.report().fills()).as("两跑都必须有跨格成交（否则 dump 相同是平凡的）").hasSize(1);
      dumps.add(stateDump(world, outcome, List.of(SELLER, BUYER, carrier)));
    }
    assertThat(dumps.get(1)).as("★ N-H3：两跑状态 dump 逐字节相同").isEqualTo(dumps.get(0));
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  /**
   * 跨格世界：H1 = 服务成市（粮 + haul 都有价）+ 卖方 + 承运户；H2 = 买方（只有粮价）。
   *
   * <p>★ 承运户带一条**跑商产业 unit**（{@code outputPerUnit={haul:1}}、无周期投入）：这是 {@code EnterpriseProfitBook}
   * 认得出"这户在经营 merchant 生产方式"的前提（T-H3 的 {@code ProductionEnterprise} 必须有 unit）， 也是真实播种的形状（世界 {@code
   * trade} 产业的产出全归经营者）。
   */
  private static MarketSettlementFixtures.World crossHexWorld(long carrierHaulMilli) {
    return MarketSettlementFixtures.builder()
        .market(H1, MarketSettlementFixtures.grainAndHaulMarket(GRAIN_PRICE, HAUL_PRICE))
        .market(H2, GRAIN_PRICE)
        .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
        .household(BUYER, H2, 1L, 0L, BUYER_SILVER)
        .haulCarrier(CARRIER, H1, 1_000L, carrierHaulMilli)
        .industryUnit(CARRIER, H1, merchantIndustry(), AssetKind.CATTLE, 1L)
        .build();
  }

  /** 跑商产业模板（夹具）：每 1 单位规模要 1 头 CATTLE、产 1 商品单位 haul、无周期投入、无劳动约束。 */
  private static Industry merchantIndustry() {
    return new Industry(
        IndustryHexKeys.id("fixture-merchant-haul", H1.q(), H1.r()),
        "夹具跑商产业",
        new RegimeId("fixture"),
        120L,
        Map.of(AssetKind.CATTLE, 1L),
        Map.of(),
        0L,
        0L,
        Map.of(MarketSettlementFixtures.HAUL, 1L),
        Map.of(),
        List.of(new ClassSlot(new SocialClassId("artisan"), "跑商", 1_000)),
        new AllocationRule.Split(1_000, 0));
  }

  /** 无跨格需求的单格世界：卖方 + 同格买方；{@code withMerchant} ⇒ 再放一个跑商家户（带工具货）。 */
  private static MarketSettlementFixtures.World localWorld(boolean withMerchant) {
    MarketSettlementFixtures.Builder builder =
        MarketSettlementFixtures.builder()
            .market(H1, GRAIN_PRICE)
            .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
            .household(LOCAL_BUYER, H1, 1L, 0L, BUYER_SILVER);
    if (withMerchant) {
      builder.carrier(HouseholdId.parse("hh-haul-neutral-merchant"), H1, 1_000L, 5_000L);
    }
    return builder.build();
  }

  /** 带自定义价表的市场（{@code grain} + 额外商品；本类的 T-H4 用例要一个 tool 价才能挂出 tool 卖单）。 */
  private static Market pricedMarket(long grainPrice, Map<CommodityId, Long> extraPrices) {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(MarketSettlementFixtures.GRAIN, grainPrice);
    prices.putAll(extraPrices);
    return new Market(MarketSettlementFixtures.SILVER, prices);
  }

  /**
   * ★ <b>状态 dump（判据的"逐字节相同"载体）</b>：指定家户的商品/货币/冻结/未满足账目 + 逐格价表（本轮价 + 下轮价） +
   * 本轮全部读数。键序一律按**字符串排**（不读哈希序）。
   */
  private static String stateDump(
      MarketSettlementFixtures.World world,
      MarketSettlement.MarketOutcome outcome,
      List<HouseholdId> households) {
    StringBuilder out = new StringBuilder();
    List<HouseholdId> ordered = new ArrayList<>(households);
    ordered.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : ordered) {
      out.append("goods ")
          .append(household.value())
          .append(' ')
          .append(sortedEntries(world.goods().getOrDefault(household, Map.of())))
          .append('\n');
      out.append("money ")
          .append(household.value())
          .append(' ')
          .append(sortedEntries(world.money().getOrDefault(household, Map.of())))
          .append('\n');
      out.append("frozenGoods ")
          .append(household.value())
          .append(' ')
          .append(sortedEntries(world.frozenGoods().getOrDefault(household, Map.of())))
          .append('\n');
      out.append("frozenMoney ")
          .append(household.value())
          .append(' ')
          .append(sortedEntries(world.frozenMoney().getOrDefault(household, Map.of())))
          .append('\n');
      out.append("unmet ")
          .append(household.value())
          .append(' ')
          .append(sortedEntries(world.unmetToday().getOrDefault(household, Map.of())))
          .append('\n');
    }
    List<HexCoord> hexes = new ArrayList<>(world.markets().keySet());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HexCoord hex : hexes) {
      out.append("prices ")
          .append(hex.q())
          .append(',')
          .append(hex.r())
          .append(' ')
          .append(sortedEntries(world.markets().get(hex).prices()))
          .append('\n');
      out.append("nextPrices ")
          .append(hex.q())
          .append(',')
          .append(hex.r())
          .append(' ')
          .append(sortedEntries(outcome.markets().get(hex).prices()))
          .append('\n');
    }
    out.append("fills ").append(outcome.report().fills()).append('\n');
    out.append("sellerOutcomes ").append(outcome.report().sellerOutcomes()).append('\n');
    out.append("buyerOutcomes ").append(outcome.report().buyerOutcomes()).append('\n');
    out.append("priceUpdates ").append(outcome.report().priceUpdates()).append('\n');
    out.append("routes ").append(outcome.report().routes()).append('\n');
    out.append("unfilled ")
        .append(
            outcome.report().unfilledReasonCounts().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .collect(Collectors.joining(",")))
        .append('\n');
    return out.toString();
  }

  /** 键值对按字符串排序后拼接（确定性 dump：不读哈希序）。 */
  private static String sortedEntries(Map<?, Long> map) {
    return map.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .sorted()
        .collect(Collectors.joining(","));
  }

  private static long ceilDiv(long numerator, long denominator) {
    return -Math.floorDiv(-numerator, denominator);
  }
}
