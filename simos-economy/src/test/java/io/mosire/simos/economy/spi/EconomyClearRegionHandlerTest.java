package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionEfficiencyState;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>§3.1/§3.2/§11.3：{@code economy.ClearRegion} 对两个新组件的按格清除</b>。
 *
 * <p>夹具：两格各一产业 + 各一 unit，两格都带 GM 产出数量覆盖与效率累计行；区域 {@code 701} 只含 {@code (0,0)}。清空后：
 *
 * <ul>
 *   <li>目标格的 {@code outputQuantityOverrides} / {@code productionEfficiency} 键被删；
 *   <li>未清格的同两表**逐值不变**（不是"空表即通过"）；
 *   <li>变更集确实把两个组件报成 changed（否则 Core 不会把删除落盘）。
 * </ul>
 *
 * <p>本类自足：只借 map 切片的 {@link Region} 类型做定位（ClearRegion 的既有前置），不搭真 map。
 */
class EconomyClearRegionHandlerTest {

  private static final HexCoord TARGET = new HexCoord(0, 0);
  private static final HexCoord KEPT = new HexCoord(1, 0);
  private static final IndustryId TARGET_FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final IndustryId KEPT_FARM = IndustryHexKeys.id("farm", 1, 0);
  private static final ActorRef TARGET_OPERATOR =
      new ActorRef(ActorKind.ORGANIZATION, TARGET_FARM.value());
  private static final ActorRef KEPT_OPERATOR =
      new ActorRef(ActorKind.ORGANIZATION, KEPT_FARM.value());
  private static final ProductionUnitId TARGET_UNIT =
      ProductionUnitId.idOf(TARGET_FARM, TARGET_OPERATOR);
  private static final ProductionUnitId KEPT_UNIT = ProductionUnitId.idOf(KEPT_FARM, KEPT_OPERATOR);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final RegionId REGION = new RegionId("701");

  @Test
  void clearRegionRemovesBothNewComponentsForTheTargetHexAndLeavesOtherHexesIntact() {
    EconomyData base = twoHexEconomy();
    EconomySnapshot economySnapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(10), base);
    MapSnapshot mapSnapshot =
        new MapSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(10),
            GameMap.empty().withRegions(Map.of(REGION, region(TARGET))));
    SimulationState state =
        new SimulationState(
            new StateMeta(
                new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(10)),
            Map.<String, Snapshot>of("economy", economySnapshot, "map", mapSnapshot),
            InMemoryInfoSystem.empty());

    HandlerOutcome outcome =
        new EconomyClearRegionHandler().handle(state, "{\"regionId\":\"" + REGION.value() + "\"}");

    assertThat(outcome).as("区域存在 ⇒ 不是拒绝").isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet changeSet = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.outputQuantityOverrides().changed())
        .as("覆盖表按格删除 ⇒ 必须进变更集（否则 Core 不落盘）")
        .isTrue();
    assertThat(changeSet.productionEfficiency().changed()).as("效率表按 unit 删除 ⇒ 必须进变更集").isTrue();

    EconomyData after = EconomyChangeSet.apply(changeSet, base);

    assertThat(after.outputQuantityOverrides()).as("目标格的覆盖整条删除").containsOnlyKeys(KEPT_FARM);
    assertThat(after.outputQuantityOverrides().get(KEPT_FARM))
        .as("未清格的覆盖逐值不变")
        .isEqualTo(base.outputQuantityOverrides().get(KEPT_FARM));

    assertThat(after.productionEfficiency()).as("目标格 unit 的效率行整条删除").containsOnlyKeys(KEPT_UNIT);
    assertThat(after.productionEfficiency().get(KEPT_UNIT))
        .as("未清格的效率行逐值不变")
        .isEqualTo(base.productionEfficiency().get(KEPT_UNIT));

    assertThat(after.industries()).as("目标格产业被清、未清格保留").containsOnlyKeys(KEPT_FARM);
    assertThat(after.units()).as("目标格 unit 被清、未清格保留").containsOnlyKeys(KEPT_UNIT);
  }

  @Test
  void clearRegionWithoutTheTargetHexKeepsBothNewComponentsIntact() {
    EconomyData base = twoHexEconomy();
    EconomySnapshot economySnapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(10), base);
    MapSnapshot mapSnapshot =
        new MapSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(10),
            // 同一张区域，但这次 payload 点的是不存在的区域 ⇒ 拒绝，状态一字不动（负向：拒绝路径不落 revision）。
            GameMap.empty().withRegions(Map.of(REGION, region(TARGET))));
    SimulationState state =
        new SimulationState(
            new StateMeta(
                new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(10)),
            Map.<String, Snapshot>of("economy", economySnapshot, "map", mapSnapshot),
            InMemoryInfoSystem.empty());

    HandlerOutcome outcome =
        new EconomyClearRegionHandler().handle(state, "{\"regionId\":\"999\"}");

    assertThat(outcome).as("区域不存在 ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(base.outputQuantityOverrides().keySet()).containsExactly(TARGET_FARM, KEPT_FARM);
    assertThat(base.productionEfficiency().keySet()).containsExactly(TARGET_UNIT, KEPT_UNIT);
  }

  private static Region region(HexCoord... hexes) {
    return Region.of(REGION, "区域 701", Set.of(hexes), RegionMeta.empty());
  }

  /** 两格各一产业 + unit；两格都带非空覆盖与效率行（值刻意不同 ⇒ "逐值不变"有判别力）。 */
  private static EconomyData twoHexEconomy() {
    Map<IndustryId, Map<CommodityId, Long>> overrides = new LinkedHashMap<>();
    overrides.put(TARGET_FARM, Map.of(GRAIN, 3L));
    overrides.put(KEPT_FARM, Map.of(GRAIN, 4L));
    Map<ProductionUnitId, ProductionEfficiencyState> efficiency = new LinkedHashMap<>();
    efficiency.put(TARGET_UNIT, new ProductionEfficiencyState(120_000L, 1L, 2L, 3L, 4L));
    efficiency.put(KEPT_UNIT, new ProductionEfficiencyState(110_000L, 5L, 6L, 7L, 8L));
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withIndustries(Map.of(TARGET_FARM, industry(TARGET_FARM), KEPT_FARM, industry(KEPT_FARM)))
        .withProcesses(
            Map.of(
                TARGET_UNIT, unit(TARGET_UNIT, TARGET_FARM, TARGET_OPERATOR),
                KEPT_UNIT, unit(KEPT_UNIT, KEPT_FARM, KEPT_OPERATOR)))
        .withOutputQuantityOverrides(overrides)
        .withProductionEfficiency(efficiency);
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "clear-region-test",
        0L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  /** 一个最小产业：产出键只有 grain（覆盖夹具的 commodity 必须在这个键里）。 */
  private static Industry industry(IndustryId id) {
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        Map.of(AssetKind.CATTLE, 1L),
        Map.of(),
        0L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1000)),
        new AllocationRule.Split(1000, 0));
  }

  private static ProductionProcess unit(
      ProductionUnitId id, IndustryId industryId, ActorRef operator) {
    return new ProductionProcess(id, industryId, operator, industryId.value(), 0L, 0L, Map.of());
  }
}
