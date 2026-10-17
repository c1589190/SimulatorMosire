package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.spi.ProductionSeedHandler;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W7e 测试批（2026-10-11）：跨模块耦合守卫 —— 城市格 {@code trade} 规模锚必须保持 100 单位</b>。
 *
 * <p>★★ <b>为什么这条守卫只能写在 {@code simos-app} 的测试里（不能在 main 里互相引用）</b>：耦合的两端分属两个模块 ——
 *
 * <pre>
 * 分子 = {@link EconomySeeder#MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}（simos-app：创世作业资本 12,000 毫工具）
 * 分母 = {@link MerchantTradeTemplate#TOOL_PER_UNIT}（simos-economy：每 1 单位规模的作业资本 120 毫工具）
 * 不变量 = 分子 ÷ 分母 == 100（城市格 trade unit 的规模 = ⌊作业资本 ÷ 每单位作业资本⌋）
 * </pre>
 *
 * <p>{@code simos-app} 在 {@code simos-economy} 的 enforcer 禁列里（{@code simos-economy/pom.xml} 的
 * {@code bannedDependencies}）⇒ economy 侧**不能** import app 常量，只能以字面量复述分子（见 {@code
 * MerchantTradeTemplate} 的 {@code TOOL_PER_UNIT} 注：「这是本常量与创世禀赋之间**唯一**的耦合点，改任一侧都必须回来改这里」）。
 * 那份字面量复述没有编译期护栏 ⇒ <b>只能靠本测试钉住</b>（W7-fix2 账本 §5 未完成 ③ 点名的就是这一条）。
 *
 * <p>★★★ <b>判别力（本类每条断言都能被"改坏常量"当场打红）</b>：
 *
 * <ul>
 *   <li><b>比值 | 绝对值</b>：只钉比值 ⇒ "两侧同时翻倍"（12,000/120 → 24,000/240）会静默存活；故比值与两侧绝对值都钉。
 *   <li><b>真播种世界</b>：{@link #everySeededTradeUnitStillStartsWithCapacityScaleOneHundred()}
 *       不用算术推导，而是真跑 {@code EconomySeeder.plan} → 真 {@code EconomySeedHandler} → 真 {@code
 *       EconomyData}，读**落盘后的**产业配方与作业资本份额 ⇒ 它证明的是"世界里真的长成 100 单位规模"，而不是"两个常量除得尽"。
 * </ul>
 *
 * <p>★ <b>为什么锚必须保 100（W7-fix2 的裁定 E2）</b>：W7-fix 曾把锚取成"一个周期的工具投入 100" ⇒ 城市格规模 120、产出 120,000 毫
 * 服务、周期工具投入 12,000 毫（恰好烧完禀赋）—— 那会污染"拆牲口门槛前 / 后"的可比性。W7-fix2 改判：<b>锚保 100</b>（{@code capacityPerUnit
 * = {TOOL: 120} = 12,000 ÷ 100}），逐值回到 W7 前的 CATTLE 口径（{@code {CATTLE: 1} × 100 头}）。
 */
class MerchantTradeScaleAnchorCouplingTest {

  private static final long EXPECTED_CITY_TRADE_SCALE = 100L;

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  // ── ① 常量级：分子 ÷ 分母 == 100（唯一耦合点）──────────────────────────────────────────

  /** ★★★ <b>不变量本体：创世作业资本 ÷ 模板锚 = 100 单位规模</b>（整除；不留余数歧义）。 */
  @Test
  void genesisToolEndowmentOverTemplateAnchorIsExactlyOneHundredScaleUnits() {
    long endowmentMilli = EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI;
    long perUnitMilli = MerchantTradeTemplate.TOOL_PER_UNIT;

    assertThat(endowmentMilli).as("分子（创世作业资本，毫工具）必须为正").isPositive();
    assertThat(perUnitMilli).as("分母（每 1 单位规模的作业资本，毫工具）必须为正").isPositive();
    assertThat(endowmentMilli % perUnitMilli)
        .as("★ 必须整除（%d %% %d）—— 有余数就意味着规模锚不是一个整数", endowmentMilli, perUnitMilli)
        .isZero();
    assertThat(endowmentMilli / perUnitMilli)
        .as("★★★ 城市格 trade 规模锚必须保持 100 单位（W7 前的 CATTLE 口径同样是 100）；" + "改坏任一侧常量 ⇒ 本条当场红（变异自证见批账本）")
        .isEqualTo(EXPECTED_CITY_TRADE_SCALE);
  }

  /** ★★ <b>两侧的绝对值也钉住</b>：只钉比值会让"两侧同时按同一比例变"静默活下来（比值仍 100，但世界的规模与禀赋都变了）。 */
  @Test
  void bothSidesOfTheCouplingAreStillAtTheirCalibratedValues() {
    assertThat(EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI)
        .as("创世作业资本（毫工具）= 12 商品单位工具（A3 派生式；见 HaulServiceCommoditySeedTest）")
        .isEqualTo(12_000L);
    assertThat(MerchantTradeTemplate.TOOL_PER_UNIT)
        .as("每 1 单位规模的作业资本 = 12,000 ÷ 100 = 120 毫工具")
        .isEqualTo(120L);
    assertThat(MerchantTradeTemplate.TOOL_PER_UNIT)
        .as(
            "★ W7-fix2：作业资本锚（存量 120）与每周期工具投入（流量 100）**不是同一个量** —— "
                + "退回「锚 = 周期投入」就是退回 W7-fix 的规模 120（本批明确不许）")
        .isNotEqualTo(MerchantTradeTemplate.TOOL_MILLI_PER_UNIT_CYCLE);
  }

  // ── ② 真播种世界：落盘后的规模 = 100（不是算术推导）────────────────────────────────────

  /**
   * ★★★ <b>真世界读数：真播种出来的每一个城市格 {@code trade} unit，其作业资本 ÷ 配方锚 == 100</b>。
   *
   * <p>夹具照 {@code HaulServiceCommoditySeedTest}（紧凑三国的一国 + 真 {@code EconomySeedHandler}）：
   * 本类读**落盘后**的 {@link EconomyData}（产业配方 + {@code assetShares} 作业资本），因此"模板锚改了但创世没跟上"（或反过来）都会当场红。
   */
  @Test
  void everySeededTradeUnitStillStartsWithCapacityScaleOneHundred() {
    Seeded seeded = seeded();
    EconomyData economy = seeded.economy();
    ProductionData definitions = seeded.production();

    List<IndustryDefinitionView> tradeIndustries = new ArrayList<>();
    for (IndustryDefinitionView industry : definitions.industries().values()) {
      if (industry.id().value().startsWith(EconomySeeder.TRADE + "@")) {
        tradeIndustries.add(industry);
      }
    }
    assertThat(tradeIndustries).as("★ 先证明「播种真的产出了 trade 产业」（否则下面的逐条断言会恒真）").isNotEmpty();

    for (IndustryDefinitionView trade : tradeIndustries) {
      assertThat(trade.recipe().capacityPerUnit())
          .as("★ 作业资本那一维 = TOOL（用户裁定：牲口要求取消）——%s", trade.id().value())
          .containsOnlyKeys(AssetKind.SMALL_TOOL);
      long perUnitMilli = trade.recipe().capacityPerUnit().get(AssetKind.SMALL_TOOL);
      assertThat(perUnitMilli)
          .as("★ 播种出来的配方锚 = 模板锚（同一拼写点）")
          .isEqualTo(MerchantTradeTemplate.TOOL_PER_UNIT);

      long capitalMilli = toolCapacityUnder(economy, trade.id().value());
      assertThat(capitalMilli)
          .as("★ 该格 trade 的作业资本（TOOL 份额）= 创世禀赋（%s）", trade.id().value())
          .isEqualTo(EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI);
      assertThat(capitalMilli / perUnitMilli)
          .as("★★★ 真世界里该商号的规模锚 = ⌊作业资本 ÷ 配方锚⌋ = 100 单位（W7 前也是 100）——%s", trade.id().value())
          .isEqualTo(EXPECTED_CITY_TRADE_SCALE);
    }
  }

  /** 某个 trade 产业下登记的 {@code TOOL} 作业资本总量（毫工具）。 */
  private static long toolCapacityUnder(EconomyData economy, String industryId) {
    long total = 0L;
    for (OwnershipStake share : economy.assetShares().values()) {
      if (share.asset() == AssetKind.SMALL_TOOL && share.industry().value().equals(industryId)) {
        total += share.quantity();
      }
    }
    return total;
  }

  /**
   * ★★ D-032 T6（a 桶夹具欠账）：真播种的**两个切片一次造齐**。
   *
   * <p>旧稿有两条各自独立播种的私有方法（{@code seededEconomy()} 与 {@code seededDefinitions()}），而 {@code
   * seededEconomy()} 交给 {@link EconomySeedHandler} 的 {@code SimulationState} **只有 economy 切片** ⇒
   * {@code economy.Seed} 的跨域校验（instance 指名的产业必须在 production 当刻定义权威里）读到"没有 production 切片" ⇒ 具名拒
   * {@code Rejected[reason=state 里没有 production 切片（装配故障…）]} ⇒ 本文件 1 红。
   *
   * <p>真世界的次序是 production 先落账（定义 + 实例），economy 后引用（N20）⇒ 本方法照那个次序：先在 production 切片上跑真 {@link
   * ProductionSeedHandler} 拿到定义权威，再把**同一个** {@link ProductionData} 放进 {@code SimulationState} 交给
   * {@link EconomySeedHandler}。两个切片来自**同一份** {@code seed}（同一个 世界、同一次 {@code
   * EconomySeeder.plan}），不另造第二套夹具。
   */
  private static Seeded seeded() {
    GameMap map = CompactThreeNationsWorld.map();
    Region region = map.regions().get(CompactThreeNationsWorld.GRANARY);
    NationSetup setup =
        CompactThreeNationsWorld.config().byRegionId(region.id().value()).withHexes(region.hexes());
    ResolvedNation resolved = setup.resolve();
    SettlementPlan plan =
        SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
    PopulationSeeder.Seeding populations = PopulationSeeder.seed(plan, 0L);
    EconomySeeder.Seed seed =
        EconomySeeder.plan(
            CompactThreeNationsWorld.MAP_ID,
            populations,
            map,
            EconomySeeder.genesisMoneyMilliPerCapita(),
            EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME,
            TestConditions.EMPTY);

    // ── 第 1 段：production.Seed 先落账（定义 + 实例的唯一权威，N20 的次序）──
    SimulationState productionOnly =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("production", new ProductionSnapshot(REF, T0, ProductionData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome productionOutcome =
        new ProductionSeedHandler().handle(productionOnly, seed.productionPayload());
    assertThat(productionOutcome)
        .as("真播种器产出的 production 载荷必须被真 handler 接受：%s", productionOutcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData production =
        ProductionChangeSet.apply(
            (ProductionChangeSet) ((HandlerOutcome.Applied) productionOutcome).changeSet(),
            ProductionData.empty());

    // ── 第 2 段：economy.Seed 引用第 1 段落定的定义权威（同一份 state，两个切片都在）──
    SimulationState bothSlices =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of(
                "production", new ProductionSnapshot(REF, T0, production),
                "economy", new EconomySnapshot(REF, T0, EconomyData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome economyOutcome =
        new EconomySeedHandler().handle(bothSlices, seed.economyPayload());
    assertThat(economyOutcome)
        .as("真播种器产出的经济载荷必须被真 handler 接受：%s", economyOutcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyData economy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) economyOutcome).changeSet(),
            EconomyData.empty());
    return new Seeded(economy, production);
  }

  /** 同一次真播种落出的两个切片（economy 的作业资本 + production 的定义权威）。 */
  private record Seeded(EconomyData economy, ProductionData production) {}
}
