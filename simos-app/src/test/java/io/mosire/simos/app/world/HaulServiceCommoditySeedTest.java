package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.util.economy.EconomyVocabulary;
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
 * ★★ <b>T-H1：{@code haul} 进词表/价格表/配方</b>（设计书 `2026-10-10-haul-service-commodity-design.md` v1.3 §6
 * T-H1）。
 *
 * <pre>
 * ① 词表      EconomyVocabulary.allCommodityIds() 含 haul，且**追加在末尾**（前六项相对序不动 —— I-H3 的硬要求）
 * ② 价格表    出厂价表（EconomySeeder.MARKET_PRICES_FACTORY / marketFor）含 haul = MARKET_PRICE_HAUL
 * ③ 配方      trade@hex：outputPerUnit 非空且产 haul；laborPerUnit &gt; 0（劳动成为真实约束）；
 *             cycleInputPerUnit → inputPerUnit() = {tool: TOOL_MILLI_PER_TRADE_UNIT_CYCLE}（**只剩一套工具消耗**）
 * </pre>
 *
 * <p>★ <b>为什么"只剩一套"要在这一层钉</b>：A3 把市场轮"每趟烧 1,000 毫工具"（旧 {@code MerchantHaul}）删除、把工具消耗
 * 单套化到本产业的**周期投入**（{@link EconomySeeder#HAUL_TOOL_PER_MILLE_OF_SERVICE} = 100‰ 是唯一强度常量）。
 * 若哪天有人把趟耗加回来，本用例的 `inputPerUnit` 恰一项 + 派生式断言同样会把它挡在"第二个工具消耗入口"之外； 被删的类/事件名残留另由 util 的退役护栏（{@code
 * HaulParallelMachineRetirementGuardTest}）把守。
 *
 * <p>★ 世界夹具照 {@link ProductionRuntimeSeedSmokeTest}（紧凑三国的一国 + 真 {@code EconomySeedHandler}），
 * 不另造第二套世界；本类只读落盘的 {@link EconomyData}。
 */
class HaulServiceCommoditySeedTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  /**
   * ★ 商品键的唯一拼写点 = 词表（{@code EconomySeeder.COMMODITY_HAUL} 是同一个字符串常量的别名）； 测试里不手写 {@code new
   * CommodityId("haul")}。
   */
  private static final CommodityId HAUL = new CommodityId(EconomyVocabulary.HAUL_COMMODITY_ID);

  private static final CommodityId TOOL = new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /** ① 词表：{@code haul} 在末尾，前六项的**值**与**相对序**都不动。 */
  @Test
  void haulIsAppendedAtTheEndOfTheCommodityVocabulary() {
    List<String> ids = EconomyVocabulary.allCommodityIds();
    assertThat(ids)
        .as("★ 前六项逐值、逐序不变（词表序是读口/报告列序的唯一来源）")
        .startsWith("grain", "cloth", "fiber", "tool", "iron", "wood");
    assertThat(ids)
        .as("★ A1：haul 恰一项且在末尾")
        .containsExactly("grain", "cloth", "fiber", "tool", "iron", "wood", "haul");
    assertThat(ids.get(ids.size() - 1)).isEqualTo(EconomyVocabulary.HAUL_COMMODITY_ID);
  }

  /** ② 价格表：出厂价表与逐币市场都含 haul 的牌价（"进价格表 ⇒ 可被买卖"）。 */
  @Test
  void haulIsInTheFactoryPriceTableAndEverySeededMarket() {
    assertThat(new ArrayList<>(EconomySeeder.MARKET_PRICES_FACTORY.keySet()))
        .as("★ 出厂价表：既有五项相对序不动，haul 追加在末位")
        .extracting(CommodityId::value)
        .containsExactly("grain", "cloth", "fiber", "tool", "iron", "haul");
    assertThat(EconomySeeder.MARKET_PRICES_FACTORY.get(HAUL))
        .as("★ 运输服务有牌价（缺价 ⇒ 不交易）")
        .isEqualTo(EconomySeeder.MARKET_PRICE_HAUL)
        .isPositive();
    assertThat(EconomySeeder.marketFor(EconomySeeder.MARKET_NUMERAIRE).prices())
        .as("★ 播种用的市场工具照样带上 haul 牌价")
        .containsEntry(HAUL, EconomySeeder.MARKET_PRICE_HAUL);
  }

  /** ③ 配方：真播种出来的每一个 {@code trade@hex} 产业都必须产出运输服务、有劳动约束、且工具消耗只有本产业周期投入一套。 */
  @Test
  void everySeededTradeIndustryProducesHaulWithASingleToolConsumption() {
    EconomyData economy = seededEconomy();

    List<Industry> tradeIndustries = new ArrayList<>();
    for (Industry industry : economy.industries().values()) {
      if (industry.id().value().startsWith(EconomySeeder.TRADE + "@")) {
        tradeIndustries.add(industry);
      }
    }
    assertThat(tradeIndustries).as("★ 先证明「播种真的产出了 trade 产业」（否则下面的逐条断言会恒真）").isNotEmpty();

    for (Industry trade : tradeIndustries) {
      // ── 产出：运输服务（A1 之前是空表 ⇒ 收获当场 return，跑商与标准管线结构性隔离）────────────
      assertThat(trade.outputPerUnit())
          .as("★ outputPerUnit 非空且产 haul（%s）", trade.id().value())
          .containsEntry(HAUL, EconomySeeder.HAUL_PER_TRADE_UNIT_CYCLE);
      // ── 劳动：laborPerUnit > 0（跑商是要人干的产业）；分配模板的劳动档也 > 0 ────────────────
      assertThat(trade.laborPerUnit())
          .as("★ 劳动成为真实约束（laborPerUnit > 0）")
          .isEqualTo(EconomySeeder.LABOR_MILLI_PER_TRADE_UNIT)
          .isPositive();
      assertThat(trade.allocation())
          .as("★ 分配模板 = 运力为主 + 劳动三成（TRADE_SPLIT_LABOR_PER_MILLE > 0）")
          .isInstanceOfSatisfying(
              AllocationRule.Split.class,
              split ->
                  assertThat(split.laborWeightPerMille())
                      .isEqualTo(EconomySeeder.TRADE_SPLIT_LABOR_PER_MILLE)
                      .isPositive());
      // ── 工具：**只有**产业周期投入这一套（A3 单套化；旧"每趟 1,000 毫工具"已删）──────────────
      assertThat(trade.inputPerUnit())
          .as("★ 工具消耗的唯一入口 = 本产业的周期投入（恰一项 tool）")
          .containsExactly(Map.entry(TOOL, EconomySeeder.TOOL_MILLI_PER_TRADE_UNIT_CYCLE));
      assertThat(trade.capacityPerUnit()).as("运力资产维：1 头 CATTLE / 1 单位规模").hasSize(1);
    }

    // ── 单套化的"唯一强度常量"派生式（T-H1「与趟耗同口径」的算式面）────────────────────────────
    assertThat(EconomySeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE).as("毫工具 / 毫服务").isEqualTo(100L);
    assertThat(EconomySeeder.TOOL_MILLI_PER_TRADE_UNIT_CYCLE)
        .as("★ 周期投入 = 产出服务量(毫) × 工具强度‰（改这一个常量即改整个跑商的工具强度）")
        .isEqualTo(
            EconomySeeder.HAUL_PER_TRADE_UNIT_CYCLE
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                * EconomySeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE
                / 1_000L)
        .isEqualTo(100L);
    assertThat(EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI)
        .as("★ 创世工具禀赋由**同一个**强度常量派生（不是第二个手写的数）")
        .isEqualTo(
            EconomySeeder.GOODS_UNITS_PER_HAUL
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                * EconomySeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE
                / 1_000L
                * 12L)
        .isEqualTo(12_000L);
  }

  /** 真播种（紧凑三国的一国）：载荷 → 真 {@code EconomySeedHandler} → {@link EconomyData}。 */
  private static EconomyData seededEconomy() {
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

    SimulationState empty =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("economy", new EconomySnapshot(REF, T0, EconomyData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(empty, seed.economyPayload());
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    return EconomyChangeSet.apply(
        (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
  }
}
