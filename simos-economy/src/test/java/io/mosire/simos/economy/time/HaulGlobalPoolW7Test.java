package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.MerchantCapacityPool.CarrierAllocation;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W7（2026-10-11）判据：运力池「全市场区共享」+ 两把尺统一 + {@code NO_SERVICE_*} 改名</b>（测试批按判据写，不按代码反推）。
 *
 * <p>★★ <b>判据出处</b>：
 *
 * <ol>
 *   <li><b>用户原话（最高依据，逐字）</b>：「还有，<b>运力提供不应该也是全市场区共享的吗</b>」，并在选项里选定「<b>全国/跨区也能被雇</b>」—— 设计书 {@code
 *       2026-10-10-unified-price-and-ordered-settlement-design.md} <b>v3.26</b> 记为「取代 2026-10-10
 *       的『逐 hex』口径」（AGENTS §四.1：冲突时以最新落盘文档为准）；
 *   <li><b>设计书 §6.3 判据 3</b>「负向用例：…旧拒绝路径确实被挡住」；
 *   <li><b>W7 派单 ①④⑥</b>（本批任务书）：池全局共享 + 两把尺统一（{@code providerCapacityMilli}）+ {@code NO_SERVICE_*}
 *       两条理由<b>互不混淆</b>。
 * </ol>
 *
 * <p>★★★ <b>为什么它测得到 W7（而不是在测一件本来就成立的事）</b>：改前 {@code select} 的候选集 = {@code byHex.get(发货格)} ⇒
 * <b>发货格没有承运方时这条 lane 一个候选都看不到</b>（哪怕隔壁格手上有卖不掉的运输服务）。本类的 {@link
 * #carrierInAnotherHexServesTheLaneWhoseShippingHexHasNoCarrier()} 里发货格<b>真的</b>一个承运方都没有（有断言）。
 * 物理可达性由 W7 新增的<b>总行程</b>判据兜底（自家格 → 发货格 → 收货格 ≤ 派生半径）—— 它的负向由 {@link
 * #laneWhoseTripExceedsTheDerivedRadiusIsRefusedByName()} 守，并<b>同时</b>断言"只看收货格那一端的旧口径会放行"，
 * 以证明红/绿来自那条新判据、而不是别的原因。
 *
 * <p>★ <b>本类只测单测面</b>（池 / 派生量 / 门槛理由 / 字面量）；真世界的跨区读数与"非城市格能不能运出去"在批账本里。
 */
class HaulGlobalPoolW7Test {

  /** 发货格：本格**故意不放**承运方（W7 要修的就是"本格没有承运方"）。 */
  private static final HexCoord SHIPPING = new HexCoord(0, 0);

  /** 另一个格：W7 起这里的承运方可以接 {@link #SHIPPING} 发出的 lane。 */
  private static final HexCoord CARRIER_HEX = new HexCoord(1, 0);

  /** 收货格：lane = {@link #SHIPPING} → 这里（长度 2）。 */
  private static final HexCoord DESTINATION = new HexCoord(2, 0);

  /** 太远的承运方格：行程 = d(FAR,SHIPPING) + d(SHIPPING,DESTINATION) = 6 + 2 = 8 > 半径 4。 */
  private static final HexCoord FAR = new HexCoord(6, 0);

  private static final CommodityId HAUL = HaulService.HAUL_COMMODITY;

  /**
   * ★ 一批"可以真交付"的运输服务货：150,000 毫 ⇒ 派生 tier = SELF_EMPLOYED（&ge; 100,000）⇒ 半径 = 4 hex ⇒ 上面那条 长度 3
   * 的行程（1 + 2）够得着。★ 数值不是随手写的：PORTER（&lt; 100,000）半径只有 2，会让"够得着"这条判据落在边界上 （夹具规模决定判别力，AGENTS §三）。
   */
  private static final long DELIVERABLE_SERVICE_MILLI = 150_000L;

  private static HouseholdId hh(String id) {
    return HouseholdId.parse(id);
  }

  /** 规范的跑商位置（{@code merchant-principal}）—— 让夹具与 {@link DefaultProductionModes} 的目录一致。 */
  private static ClassPositionId merchantPosition() {
    return DefaultProductionModes.positionId(
            DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_MERCHANT_PRINCIPAL)
        .orElseThrow();
  }

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex, long laborMilli) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, new SocialClassId("artisan")),
        0L,
        laborMilli,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership merchantStanding(HouseholdId id) {
    ClassPositionId position = merchantPosition();
    return new HouseholdClassMembership(
        id, position, position, Set.of(), Map.of(), 0L, 0L, "w7-pool-test");
  }

  /**
   * 装配一个"服务成市"的池：{@code haulServiceHexes} = 服务已定价的格（{@link HaulService#pricedAt} 的入参口径）。
   *
   * @param households 家户 → 所在格；@param serviceGoods 家户 → 手上的运输服务货（毫）
   */
  private static MerchantCapacityPool pool(
      Map<HouseholdId, HexCoord> households,
      Map<HouseholdId, Long> serviceGoods,
      Set<HexCoord> haulServiceHexes) {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    Map<HouseholdId, HouseholdClassMembership> standings = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HexCoord> entry : households.entrySet()) {
      HouseholdId id = entry.getKey();
      // ★ 劳动 = 0：这一户**只能**靠手上的服务货交付（正是运力调查报告 G4/A3 实测的 hh-0_2-urban-landlord 那一类）。
      rows.put(id, row(id, entry.getValue(), 0L));
      standings.put(id, merchantStanding(id));
      goods.put(id, Map.of(HAUL, serviceGoods.getOrDefault(id, 0L)));
    }
    return MerchantCapacityPool.of(
        standings,
        DefaultProductionModes.classPositions(),
        rows,
        goods,
        Map.of(),
        true,
        haulServiceHexes);
  }

  /** 一个"该格 {@code haul} 有牌价"的世界（服务成市）—— 价表用既有夹具，不第二次手写价格表。 */
  private static EconomyData servicePricedBase(Map<HouseholdId, HexCoord> households) {
    return base(households, MarketSettlementFixtures.grainAndHaulMarket(10L, 4L));
  }

  private static EconomyData base(
      Map<HouseholdId, HexCoord> households, io.mosire.simos.economy.model.MarketTable table) {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    Map<HouseholdId, HouseholdClassMembership> standings = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HexCoord> entry : households.entrySet()) {
      rows.put(entry.getKey(), row(entry.getKey(), entry.getValue(), 0L));
      standings.put(entry.getKey(), merchantStanding(entry.getKey()));
    }
    return EconomyData.empty()
        .withModes(DefaultProductionModes.modes())
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(rows)
        .withClassMemberships(standings)
        .withMarketTables(EconomyFixtures.hexMarkets(Map.of(SHIPPING, table)));
  }

  // ── ① 全局共享：跨格分配 ────────────────────────────────────────────────────────────────

  /**
   * ★★★ <b>W7 判据① 正向：承运方在**别的格**，照样能被分配到"发货格没有承运方"的那条 lane</b>。
   *
   * <p>改前的候选集 = {@code byHex.get(发货格)} ⇒ 本用例里发货格的承运方计数是 <b>0</b>（下面有断言），改前必然零分配。
   */
  @Test
  void carrierInAnotherHexServesTheLaneWhoseShippingHexHasNoCarrier() {
    HouseholdId carrier = hh("hh-w7-far-carrier");
    MerchantCapacityPool pool =
        pool(
            Map.of(carrier, CARRIER_HEX),
            Map.of(carrier, DELIVERABLE_SERVICE_MILLI),
            Set.of(SHIPPING, CARRIER_HEX));

    assertThat(pool.householdCountAt(SHIPPING))
        .as("夹具自证：发货格**一个跑商家户都没有** —— 改前正是这一条让整个 lane 一个候选都看不到")
        .isZero();
    assertThat(pool.totalCapacityAt(CARRIER_HEX))
        .as("承运方的可交付服务货在**另一个格**上")
        .isEqualTo(DELIVERABLE_SERVICE_MILLI);

    CarrierAllocation allocation = pool.select(SHIPPING, DESTINATION, 1_000L, 1_000L);

    assertThat(allocation.choices())
        .as("★ 全局共享：发货格没有承运方，但全池有 ⇒ 这一笔必须被分到（用户原话「运力提供不应该也是全市场区共享的吗」）")
        .hasSize(1);
    assertThat(allocation.choices().get(0).household()).isEqualTo(carrier);
    assertThat(allocation.choices().get(0).hex())
        .as("承运方**不在发货格**（它从自己那一格跑过来接活）")
        .isEqualTo(CARRIER_HEX);
    assertThat(allocation.allocatedMilli()).as("服务货充足 ⇒ 整笔被运走").isEqualTo(1_000L);
    assertThat(allocation.businessRejection()).as("分到了就不是业务拒绝").isFalse();
  }

  /**
   * ★★★ <b>W7 判据① 负向：总行程超出派生半径 ⇒ 不分配，且具名</b>。
   *
   * <p>★★ <b>这个负向必须能分辨"是哪条判据拦下的"</b>：夹具同时断言
   *
   * <ol>
   *   <li>{@code pool.totalCapacityMilli() > 0} ⇒ 拒绝<b>不是</b>"全池没有服务货"；
   *   <li>{@code capacity.servesLane(DESTINATION)} 为 <b>真</b>（只看"承运方 → 收货格"那一端的旧口径会放行）；
   *   <li>{@code capacity.servesTrip(SHIPPING, DESTINATION)} 为 <b>假</b>（W7 的总行程口径才是拦住它的那条）；
   *   <li>归因档 = {@code OUT_OF_RADIUS}（不是"没有承运方"、也不是"没有服务货"）。
   * </ol>
   */
  @Test
  void laneWhoseTripExceedsTheDerivedRadiusIsRefusedByName() {
    HouseholdId carrier = hh("hh-w7-too-far-carrier");
    // ★ 发货格里放一个"选了跑商、但手上没有服务货"的户（F-1：预算 0 的条目仍留在池里）—— 它的作用是让
    //   "本格没有任何跑商家户"这条**逐格**归因（{@code NO_MERCHANT_HOUSEHOLD}）不抢先命中，从而把本用例的
    //   归因逼到"够不着"那一档上（否则测到的是另一条理由，红的理由就不对了）。
    //   ★ 顺带记录一处本批发现的**读数面残留**（如实报，不修 main）：发货格一个跑商家户都没有、而全池有货但都够不着时，
    //     {@code select} 的归因仍报 {@code no-merchant-household-in-shipping-hex}（逐格事实），而不是
    //     {@code out-of-derived-radius}（全池事实）—— 判据不受影响（choices/unallocated 逐值不变），只是理由的**主语**还是逐格的。
    HouseholdId emptyLocalMerchant = hh("hh-w7-local-without-goods");
    MerchantCapacityPool pool =
        pool(
            Map.of(carrier, FAR, emptyLocalMerchant, SHIPPING),
            Map.of(carrier, DELIVERABLE_SERVICE_MILLI, emptyLocalMerchant, 0L),
            Set.of(SHIPPING, FAR));

    assertThat(pool.householdCountAt(SHIPPING)).as("夹具自证：发货格**有**跑商家户（避免逐格归因抢先）").isEqualTo(1L);
    assertThat(pool.totalCapacityMilli())
        .as("夹具自证：全池**有**可交付服务货 ⇒ 下面的空分配不可能是「全世界没有服务」造成的")
        .isEqualTo(DELIVERABLE_SERVICE_MILLI);

    MerchantCapacity capacity =
        MerchantCapacity.ofService(carrier, FAR, DELIVERABLE_SERVICE_MILLI, 0L, 0L);
    assertThat(capacity.serviceRadiusHex()).as("150,000 毫 ⇒ SELF_EMPLOYED ⇒ 半径 4").isEqualTo(4L);
    assertThat(capacity.servesLane(DESTINATION))
        .as("判别力自证：只看「承运方 → 收货格」（改前口径 d(FAR,DESTINATION)=4）⇒ 4 ≤ 4 **会放行**")
        .isTrue();
    assertThat(capacity.servesTrip(SHIPPING, DESTINATION))
        .as("W7 口径：总行程 = d(FAR,SHIPPING)=6 + d(SHIPPING,DESTINATION)=2 = 8 > 4 ⇒ 够不着")
        .isFalse();

    CarrierAllocation allocation = pool.select(SHIPPING, DESTINATION, 1_000L, 1_000L);

    assertThat(allocation.choices()).as("★ 够不着的承运方不得被分配（fail-closed）").isEmpty();
    assertThat(allocation.unallocatedMilli()).as("全部转成未获服务（不静默丢）").isEqualTo(1_000L);
    assertThat(allocation.logisticsBlock())
        .as("具名归因必须落在「够不着」这一档，而不是「没有承运方」/「没有服务货」")
        .isEqualTo(MerchantCapacityPool.LogisticsBlock.OUT_OF_RADIUS);
    assertThat(allocation.businessRejection())
        .as("「够不着」是运行期读数（DEBUG 档），不是业务拒绝 —— 与「全池没货」分开")
        .isFalse();
  }

  /**
   * ★★ <b>W7 判据① 的第二条边界：两类承运方（服务货口径 / 劳动+工具口径）不许互相承接</b>（账本 §2.2 的口径闸）。
   *
   * <p>为什么它是判据而不是实现细节：全局共享把两类承运方放进<b>同一个候选集</b>，若混接，{@code deliverHaulService} 会在"劳动 + 工具"口径的户上找不到
   * {@code haul} 货 ⇒ 具名 ERROR 且整笔废单。缺省中性（{@code haulServiceHexes} 为空）时两个判据恒等 ⇒ 既有世界逐值不变。
   */
  @Test
  void carriersOfTheOtherCaliberAreNotHandedAServicePricedLane() {
    HouseholdId serviceCarrier = hh("hh-w7-service-caliber");
    // 承运方在服务成市格（CARRIER_HEX ∈ haulServiceHexes），而这条 lane 的发货格 SHIPPING **没有**定价
    //   ⇒ 口径不一致 ⇒ 它不得被派去接这条 lane（否则交付腿会找不到服务货）。
    MerchantCapacityPool pool =
        pool(
            Map.of(serviceCarrier, CARRIER_HEX),
            Map.of(serviceCarrier, DELIVERABLE_SERVICE_MILLI),
            Set.of(CARRIER_HEX));

    CarrierAllocation allocation = pool.select(SHIPPING, DESTINATION, 1_000L, 1_000L);

    assertThat(pool.totalCapacityMilli()).as("夹具自证：全池有可交付服务货").isPositive();
    assertThat(allocation.choices()).as("★ 口径不一致 ⇒ 不分配（绝不把服务货口径的承运方塞给未定价 lane）").isEmpty();
  }

  // ── ④ 两把尺统一（决策层与结算层同一份派生量）────────────────────────────────────────────

  /**
   * ★★★ <b>W7 判据④：一个"手上有服务货、但劳动/工具为 0"的户，决策层与结算层读**同一把尺**</b>。
   *
   * <p>★★ <b>改前是什么样（这正是派单 ④ 要修的那处误判）</b>：{@code PrimaryModeRanking.merchantGateReason} 读 {@code
   * MerchantCapacity.of(labor, tool).capacityMilli() <= 0} ⇒ 上面那一类户被判 {@code no-bargaining-power}
   * （调查报告实测：{@code hh-0_2-urban-landlord}），而它<b>随时能卖服务</b>。
   */
  @Test
  void laborlessHouseholdWithServiceGoodsReadsTheSameRulerOnBothSides() {
    HouseholdId laborless = hh("hh-w7-laborless-landlord");
    Map<HouseholdId, Map<CommodityId, Long>> goods =
        Map.of(laborless, Map.of(HAUL, DELIVERABLE_SERVICE_MILLI));
    EconomyData base = servicePricedBase(Map.of(laborless, SHIPPING));

    assertThat(MerchantCapacity.laborCapacityMilli(0L))
        .as("改前那把尺（劳动 + 工具）在这个户上读出 **0** —— 误判的来源")
        .isZero();

    long ruler = MerchantCapacityPool.providerCapacityMilli(base, laborless, SHIPPING, goods);
    assertThat(ruler)
        .as("★ 统一后的唯一派生量：服务成市格取 max(手上服务货, 劳动+工具) = 手上那 150,000")
        .isEqualTo(DELIVERABLE_SERVICE_MILLI);
    assertThat(MerchantCapacityPool.sharePerMilleAsProviderAt(base, laborless, SHIPPING, goods))
        .as("可交付 ⇒ 议价权占比不为 0（全池只有它一个 ⇒ 1000‰）")
        .isEqualTo(1_000L);
    assertThat(PrimaryModeRanking.merchantGateReason(base, laborless, SHIPPING, goods))
        .as("★ 门槛理由必须是 null（改前这里会返回 no-bargaining-power ⇒ 永远当不上主业跑商）")
        .isNull();

    // 同一个户、同样的 0 劳动，但**手上没有服务货** ⇒ 给的是"没有可交付运力"这条**事实**，不是"没有议价权"这条**推论**。
    Map<HouseholdId, Map<CommodityId, Long>> empty = Map.of(laborless, Map.of());
    assertThat(MerchantCapacityPool.providerCapacityMilli(base, laborless, SHIPPING, empty))
        .isZero();
    assertThat(PrimaryModeRanking.merchantGateReason(base, laborless, SHIPPING, empty))
        .as("★ W7 新增的具名事实（与结算层的空预算判据同源）")
        .isEqualTo("no-deliverable-service-capacity");
  }

  /** ★★ 判据④ 的另一半：{@code no-bargaining-power} 这条**既有**理由必须原样保留（只在占比真为 0 时给）。 */
  @Test
  void noBargainingPowerSurvivesForAHouseholdWhoseShareFloorsToZero() {
    HouseholdId big = hh("hh-w7-big-carrier");
    HouseholdId tiny = hh("hh-w7-tiny-carrier");
    // ★ 非服务成市格（只有 grain 有价）⇒ 运力回到"劳动 + 工具"算式（缺省中性），本用例只管占比取整。
    EconomyData base =
        EconomyData.empty()
            .withModes(DefaultProductionModes.modes())
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(
                Map.of(big, row(big, SHIPPING, 1_000_000L), tiny, row(tiny, SHIPPING, 1L)))
            .withClassMemberships(Map.of(big, merchantStanding(big), tiny, merchantStanding(tiny)))
            .withMarketTables(
                EconomyFixtures.hexMarkets(
                    Map.of(SHIPPING, MarketSettlementFixtures.grainMarket(10L))));

    assertThat(MerchantCapacityPool.providerCapacityMilli(base, big, SHIPPING, Map.of()))
        .as("夹具自证：大户运力 = 1,000,000")
        .isEqualTo(1_000_000L);
    assertThat(MerchantCapacityPool.providerCapacityMilli(base, tiny, SHIPPING, Map.of()))
        .as("夹具自证：小户运力 = 1（仍在池里，只是占比取整为 0）")
        .isEqualTo(1L);
    assertThat(PrimaryModeRanking.merchantGateReason(base, big, SHIPPING, Map.of()))
        .as("正对照：大户占比 999‰ > 0 ⇒ 门槛通过")
        .isNull();
    assertThat(PrimaryModeRanking.merchantGateReason(base, tiny, SHIPPING, Map.of()))
        .as("★ 1 × 1000 ÷ 1,000,001 向下取整 = 0 ⇒ 既有理由 no-bargaining-power 原样保留（不是被 W7 删掉）")
        .isEqualTo("no-bargaining-power");
  }

  // ── ⑥ 改名：两条理由互不混淆 ────────────────────────────────────────────────────────────

  /**
   * ★★★ <b>W7 判据⑥：改名后两条理由**互不混淆**</b>（AGENTS §四 的结构性歧义：改前两者同值 {@code
   * no-haul-service-in-shipping-hex}，一个词被读成三件互不相同的事，运力调查因此把归因读反过一次）。
   *
   * <pre>
   * 货侧 HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON     = no-deliverable-haul-service-goods     承运方手上没有可交付的服务货
   * 池侧 MerchantCapacityPool.NO_CARRIER_WITH_SERVICE_REASON = no-carrier-with-deliverable-service   全池没有一个能覆盖本 lane 且有货的承运方
   * </pre>
   */
  @Test
  void theTwoServiceReasonLiteralsAreDistinctAndSelfExplaining() {
    assertThat(HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON)
        .isEqualTo("no-deliverable-haul-service-goods");
    assertThat(MerchantCapacityPool.NO_CARRIER_WITH_SERVICE_REASON)
        .isEqualTo("no-carrier-with-deliverable-service");
    assertThat(HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON)
        .as("★ 两条理由必须**不同**（同值 = 结构性歧义复发，运力调查报告 §2.5 A2 已栽过一次）")
        .isNotEqualTo(MerchantCapacityPool.NO_CARRIER_WITH_SERVICE_REASON);
    assertThat(HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON)
        .as("★ 退役的旧字面量不得留在任何一条上")
        .isNotEqualTo("no-haul-service-in-shipping-hex");
    assertThat(MerchantCapacityPool.NO_CARRIER_WITH_SERVICE_REASON)
        .isNotEqualTo("no-haul-service-in-shipping-hex");
    assertThat(MerchantCapacityPool.LogisticsBlock.NO_CARRIER_WITH_SERVICE.detail())
        .as("枚举档的 detail() 与常量同源（一处拼写）")
        .isEqualTo(MerchantCapacityPool.NO_CARRIER_WITH_SERVICE_REASON);
    assertThat(MerchantCapacityPool.LogisticsBlock.NO_MERCHANT_HOUSEHOLD.detail())
        .as("第三支（连跑商家户都没有）仍然独立")
        .isEqualTo(MerchantCapacityPool.NO_MERCHANT_HOUSEHOLD_REASON);
  }
}
