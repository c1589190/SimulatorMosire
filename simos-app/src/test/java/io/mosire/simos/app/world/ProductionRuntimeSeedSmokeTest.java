package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.spi.ProductionSeedHandler;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P1/P2 production-runtime 播种冒烟</b>（统一测试阶段的入口判据）：{@link
 * EconomySeeder.FoundationProfile#PRODUCTION_RUNTIME} 的小地图必须
 *
 * <ol>
 *   <li>不写 class-first 顶层键（P0.1 后 {@code classFirst} 组件已删除，载荷不得再出该键）；
 *   <li>载荷顶层带 P1/P2 目录五表：{@code modes/classStructures/classPositions/classStandings/assetRules}；
 *   <li>每条 entry 都是完整生产结构（{@code plans} + {@code units/assetShares/allocations}），
 *       且至少一条真的有方案节点/生产单元/资产份额/劳动配额（P2-A 后 {@code laborSupply}/{@code memberships} 已退役）；
 *   <li>载荷被真 {@link EconomySeedHandler} 接受（{@code Applied}），落出的 {@link EconomyData} 里 mode = 4 （P5a
 *       单一 grain_farm 收口）、classStructure = 4、生产结构非空、classFirst 空。
 * </ol>
 *
 * <p>★★ <b>D-032 T6（a 桶锚点，B5 裁定十九的后果）</b>：entry 里的产业声明键由 {@code industries} 改名 {@code plans} ——
 * 旧键自带"一整套产业定义 + 旧形状实例字段"，而那些定义**从未进过 economy 状态**（定义唯一权威在 production，随 {@code production.Seed}
 * 落账）⇒ 键名改成 {@code plans}：它现在只声明"这一格有哪些方案节点" （每个节点仍是 unit 节点的宿主 + trade 的显式 relation 来源）。现场： {@code
 * EconomySeeder.java:3863 entry.put("plans", industries)}（B5 注释原文）。
 *
 * <p>★ 夹具照 {@code ClassFirstEconomySeedTest}/{@code CompactThreeNationsWorld}：只取紧凑三国的一国（48 格），
 * 人口批次与 worldgen 走同一条 {@link PopulationSeeder#groups} 路径，不另造第二套世界。
 */
class ProductionRuntimeSeedSmokeTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final List<String> PRODUCTION_STRUCTURE_KEYS =
      List.of("plans", "units", "assetShares", "allocations");

  @Test
  void productionRuntimeSeedCarriesCatalogAndFullProductionEntriesThenHandlerAppliesThem()
      throws Exception {
    GameMap map = CompactThreeNationsWorld.map();
    Region region = map.regions().get(CompactThreeNationsWorld.GRANARY);
    NationSetup setup =
        CompactThreeNationsWorld.config().byRegionId(region.id().value()).withHexes(region.hexes());
    ResolvedNation resolved = setup.resolve();
    SettlementPlan plan =
        SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
    PopulationSeeder.Seeding populations = PopulationSeeder.seed(plan, 0L);
    assertThat(populations.groups()).as("小地图必须有真人口批次").isNotEmpty();

    EconomySeeder.Seed seed =
        EconomySeeder.plan(
            CompactThreeNationsWorld.MAP_ID,
            populations,
            map,
            EconomySeeder.genesisMoneyMilliPerCapita(),
            EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME,
            TestConditions.EMPTY);

    assertThat(seed.profile())
        .as("profile 必须逐值保留")
        .isEqualTo(EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME);

    JsonNode payload = JSON.readTree(seed.economyPayload());
    assertThat(payload.path("entries").isArray()).as("entries 必须是数组").isTrue();
    assertThat(payload.path("entries")).as("播种必须有格子").isNotEmpty();

    for (String catalogKey :
        List.of("modes", "classStructures", "classPositions", "classStandings", "assetRules")) {
      assertThat(payload.hasNonNull(catalogKey)).as("P1/P2 顶层目录键必须存在: %s", catalogKey).isTrue();
      assertThat(payload.path(catalogKey).isArray()).as("%s 必须是数组", catalogKey).isTrue();
      assertThat(payload.path(catalogKey)).as("%s 不得为空", catalogKey).isNotEmpty();
    }
    // ★★ D-032 P5a：农村目录收口为单一 grain_farm；目录整体 = grain_farm / handicraft_workshop /
    //   merchant / displaced 四档（旧 tenancy_*/wage_farm/family_farm 已删除）。
    assertThat(payload.path("modes").size()).as("默认生产方式目录 = 4（P5a 单一 grain_farm 收口）").isEqualTo(4);
    assertThat(payload.path("modes").toString())
        .as("目录里必须有 grain_farm（农村唯一生产模式）")
        .contains("grain_farm");
    // ★★ D-032 P5a：阶层结构目录与生产方式目录同源（4 档）；旧"≥7"钉的是 tenancy_*/wage_farm/
    //   family_farm 时代的目录，P5a 后已删除。
    assertThat(payload.path("classStructures").size()).as("默认阶层结构 = 4（与生产方式目录同源）").isEqualTo(4);
    assertThat(payload.path("liquidationPolicies").isMissingNode())
        .as("P2d 已删除抵押/清算配置：载荷不得再出现 liquidationPolicies 字段")
        .isTrue();
    assertThat(payload.path("classFirst").isMissingNode())
        .as("production-runtime 不发顶层 classFirst")
        .isTrue();

    boolean anyPlans = false;
    boolean anyUnits = false;
    boolean anyAssetShares = false;
    boolean anyAllocations = false;
    for (JsonNode entry : payload.path("entries")) {
      for (String key : PRODUCTION_STRUCTURE_KEYS) {
        assertThat(entry.hasNonNull(key)).as("每条 entry 都必须带 %s（缺键 = 结构不完整）", key).isTrue();
        assertThat(entry.path(key).isArray()).as("entry.%s 必须是数组", key).isTrue();
      }
      anyPlans |= entry.path("plans").size() > 0;
      anyUnits |= entry.path("units").size() > 0;
      anyAssetShares |= entry.path("assetShares").size() > 0;
      anyAllocations |= entry.path("allocations").size() > 0;
    }
    assertThat(anyPlans).as("至少一条 entry 有非空 plans（方案节点声明）").isTrue();
    assertThat(anyUnits).as("至少一条 entry 有非空 units").isTrue();
    assertThat(anyAssetShares).as("至少一条 entry 有非空 assetShares").isTrue();
    assertThat(anyAllocations).as("至少一条 entry 有非空 allocations").isTrue();
    // ★ P2-A：laborSupply 已删除、memberships 已迁 Social ⇒ 载荷结构键与断言同步收敛。

    // ★★★ D-032 T6（a 桶夹具欠账）：**production.Seed 先落账**（N20 的次序：定义 + 实例的唯一权威在
    //   production），economy.Seed 再引用它。旧稿两段各起一个只含**本切片**的 SimulationState，先把
    //   economy 载荷交给 EconomySeedHandler ⇒ 它的跨域校验读到"没有 production 切片"⇒ 具名拒
    //   `Rejected[reason=state 里没有 production 切片（装配故障：实例/覆盖权威必须存在）]`（1 红即此形态）。
    //   本方法照真世界的次序重排**并让两段共用同一份 state 链**。
    SimulationState productionState =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("production", new ProductionSnapshot(REF, T0, ProductionData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome productionOutcome =
        new ProductionSeedHandler().handle(productionState, seed.productionPayload());
    assertThat(productionOutcome)
        .as("真播种器产出的 production 载荷必须被真 handler 接受：%s", productionOutcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData production =
        ProductionChangeSet.apply(
            (ProductionChangeSet) ((HandlerOutcome.Applied) productionOutcome).changeSet(),
            ProductionData.empty());

    SimulationState bothSlices =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of(
                "production", new ProductionSnapshot(REF, T0, production),
                "economy", new EconomySnapshot(REF, T0, EconomyData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(bothSlices, seed.economyPayload());
    assertThat(outcome)
        .as("真播种器产出的 economy 载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);

    EconomyData economy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
    // ★ P1：生产定义权威在 production ⇒ modes/industries/instances 逐条读 production 落账后的切片。
    assertThat(production.modes()).as("落盘 modes = 4（P5a）").hasSize(4);
    assertThat(economy.classStructures()).as("落盘 classStructures = 4（与 modes 同源）").hasSize(4);
    assertThat(economy.classPositions()).as("落盘 classPositions 不得为空").isNotEmpty();
    assertThat(economy.classStandings()).as("落盘 classStandings 不得为空").isNotEmpty();
    assertThat(economy.assetRules()).as("落盘 assetRules 不得为空").isNotEmpty();
    assertThat(production.industries()).as("industries 非空").isNotEmpty();
    // ★★ D-032 T1：实例读数改读 production 切片（`EconomyData.units()` 已随迁移删除）。
    assertThat(production.instances()).as("production 的生产实例非空").isNotEmpty();
    assertThat(economy.assetShares()).as("assetShares 非空").isNotEmpty();
    assertThat(economy.allocations()).as("allocations 非空").isNotEmpty();
    // ★ P2-A A4 / §13.2：laborSupply 已删除、memberships 已迁 Social ⇒ 改为钉家户时间预算的载体。
    assertThat(economy.classes()).as("classes 非空").isNotEmpty();
    assertThat(economy.classes().values().stream().allMatch(row -> row.laborMilli() >= 0L))
        .as("每行的每 tick 时间预算不得为负")
        .isTrue();
  }
}
