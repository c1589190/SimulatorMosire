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
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.population.PopulationGroup;
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
 *   <li>不种 class-first 状态（{@code seed.classFirst().isEmpty()}）；
 *   <li>载荷顶层带 P1/P2 目录五表：{@code modes/classStructures/classPositions/classStandings/assetRules}；
 *   <li>每条 entry 都是完整生产结构（{@code industries} + {@code units/assetShares/allocations/laborSupply/
 *       memberships}），且至少一条真的有产业/生产单元/资产份额/劳动/成员；
 *   <li>载荷被真 {@link EconomySeedHandler} 接受（{@code Applied}），落出的 {@link EconomyData} 里 mode ≥ 7、
 *       classStructure ≥ 7、生产结构非空、classFirst 空。
 * </ol>
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
      List.of("industries", "units", "assetShares", "allocations", "laborSupply", "memberships");

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
    List<PopulationGroup> groups = PopulationSeeder.groups(plan, 0L);
    assertThat(groups).as("小地图必须有真人口批次").isNotEmpty();

    EconomySeeder.Seed seed =
        EconomySeeder.plan(
            CompactThreeNationsWorld.MAP_ID,
            groups,
            map,
            EconomySeeder.genesisMoneyMilliPerCapita(),
            EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME,
            TestConditions.EMPTY);

    assertThat(seed.profile())
        .as("profile 必须逐值保留")
        .isEqualTo(EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME);
    assertThat(seed.classFirst().isEmpty())
        .as("production-runtime 的生产权威是 entries，不种 class-first")
        .isTrue();

    JsonNode payload = JSON.readTree(seed.economyPayload());
    assertThat(payload.path("entries").isArray()).as("entries 必须是数组").isTrue();
    assertThat(payload.path("entries")).as("播种必须有格子").isNotEmpty();

    for (String catalogKey :
        List.of("modes", "classStructures", "classPositions", "classStandings", "assetRules")) {
      assertThat(payload.hasNonNull(catalogKey)).as("P1/P2 顶层目录键必须存在: %s", catalogKey).isTrue();
      assertThat(payload.path(catalogKey).isArray()).as("%s 必须是数组", catalogKey).isTrue();
      assertThat(payload.path(catalogKey)).as("%s 不得为空", catalogKey).isNotEmpty();
    }
    assertThat(payload.path("modes").size()).as("默认生产方式目录 ≥ 7").isGreaterThanOrEqualTo(7);
    assertThat(payload.path("classStructures").size()).as("默认阶层结构 ≥ 7").isGreaterThanOrEqualTo(7);
    assertThat(payload.hasNonNull("liquidationPolicies"))
        .as("P2 资产规则的清算政策与 assetRules 同源发出")
        .isTrue();
    assertThat(payload.path("classFirst").isMissingNode())
        .as("production-runtime 不发顶层 classFirst")
        .isTrue();

    boolean anyIndustries = false;
    boolean anyUnits = false;
    boolean anyAssetShares = false;
    boolean anyAllocations = false;
    boolean anyLaborSupply = false;
    boolean anyMemberships = false;
    for (JsonNode entry : payload.path("entries")) {
      for (String key : PRODUCTION_STRUCTURE_KEYS) {
        assertThat(entry.hasNonNull(key)).as("每条 entry 都必须带 %s（缺键 = 结构不完整）", key).isTrue();
        assertThat(entry.path(key).isArray()).as("entry.%s 必须是数组", key).isTrue();
      }
      anyIndustries |= entry.path("industries").size() > 0;
      anyUnits |= entry.path("units").size() > 0;
      anyAssetShares |= entry.path("assetShares").size() > 0;
      anyAllocations |= entry.path("allocations").size() > 0;
      anyLaborSupply |= entry.path("laborSupply").size() > 0;
      anyMemberships |= entry.path("memberships").size() > 0;
    }
    assertThat(anyIndustries).as("至少一条 entry 有非空 industries").isTrue();
    assertThat(anyUnits).as("至少一条 entry 有非空 units").isTrue();
    assertThat(anyAssetShares).as("至少一条 entry 有非空 assetShares").isTrue();
    assertThat(anyAllocations).as("至少一条 entry 有非空 allocations").isTrue();
    assertThat(anyLaborSupply).as("至少一条 entry 有非空 laborSupply").isTrue();
    assertThat(anyMemberships).as("至少一条 entry 有非空 memberships").isTrue();

    SimulationState empty =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("economy", new EconomySnapshot(REF, T0, EconomyData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(empty, seed.economyPayload());
    assertThat(outcome)
        .as("真播种器产出的 production-runtime 载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);

    EconomyData economy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
    assertThat(economy.classFirst().isEmpty())
        .as("handler 落盘后 classFirst 仍为空（production-runtime 不混播）")
        .isTrue();
    assertThat(economy.modes()).as("落盘 modes ≥ 7").hasSizeGreaterThanOrEqualTo(7);
    assertThat(economy.classStructures())
        .as("落盘 classStructures ≥ 7")
        .hasSizeGreaterThanOrEqualTo(7);
    assertThat(economy.classPositions()).as("落盘 classPositions 不得为空").isNotEmpty();
    assertThat(economy.classStandings()).as("落盘 classStandings 不得为空").isNotEmpty();
    assertThat(economy.assetRules()).as("落盘 assetRules 不得为空").isNotEmpty();
    assertThat(economy.industries()).as("industries 非空").isNotEmpty();
    assertThat(economy.units()).as("units 非空").isNotEmpty();
    assertThat(economy.assetShares()).as("assetShares 非空").isNotEmpty();
    assertThat(economy.allocations()).as("allocations 非空").isNotEmpty();
    assertThat(economy.laborSupply()).as("laborSupply 非空").isNotEmpty();
    assertThat(economy.memberships()).as("memberships 非空").isNotEmpty();
  }
}
