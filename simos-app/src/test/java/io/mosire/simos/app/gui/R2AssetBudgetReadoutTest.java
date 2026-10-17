package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.time.AssetBudgetBook;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.info.InMemoryInfoSystem;
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
 * ★★★ <b>R2 读口判据 P6（app/readout）</b>：{@code ApiViews.economyHex} 必须从同一份 {@link
 * AssetBudgetBook.AssetBudget} 读 allocations / unallocated / rejections，<b>不得把整池资产复制成每 unit 一份</b>。
 *
 * <p>★★ <b>判据原文（约束设计书 §4.1 P6）</b>：显示 allocations/unallocated/rejections，不把整池复制成每 unit 一份。
 *
 * <p>★ <b>判别力</b>：若读口沿用旧的"按 scope 聚合后复制给每个 unit"，共享 scope 的两条 unit 行会各显示 {@code
 * assets.LAND=100}、{@code assetBudget.allocations[unit].LAND=100}，而 {@code unallocated} 为空 ⇒ 本条红。
 */
class R2AssetBudgetReadoutTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("r2-readout-farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "org-r2-readout");
  private static final long LAND = 100L;
  private static final String SHARED = AssetBudgetBook.SHARED_SCOPE_REJECTION;

  @Test
  void sharedScopeReadoutShowsEmptyAllocationsUnallocatedAndNamedRejections() {
    ProductionProcess first = unit("unit-r2-readout-first");
    ProductionProcess second = unit("unit-r2-readout-second");
    SimulationState state = state(first, second);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = ApiViews.economyHex(HEX, state);

    @SuppressWarnings("unchecked")
    Map<String, Object> budget = (Map<String, Object>) body.get("assetBudget");
    assertThat(budget).as("P6：读口必须发出 assetBudget（allocations/unallocated/rejections）").isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> allocations = (Map<String, Object>) budget.get("allocations");
    assertThat(allocations)
        .as("P6：共享 scope 的两个 unit 在 allocations 里都是空表")
        .containsEntry(first.id().value(), Map.of())
        .containsEntry(second.id().value(), Map.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> unallocated = (Map<String, Object>) budget.get("unallocated");
    String scopeKey = FARM.value() + "|" + OPERATOR;
    assertThat(unallocated)
        .as("P6：整池 100 保留在 unallocated（按 scope 可见，不复制给 unit）")
        .containsEntry(scopeKey, Map.of("LAND", LAND));

    @SuppressWarnings("unchecked")
    Map<String, String> rejections = (Map<String, String>) budget.get("rejections");
    assertThat(rejections)
        .as("P6：两个 unit 都有具名拒绝")
        .containsEntry(first.id().value(), SHARED)
        .containsEntry(second.id().value(), SHARED);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> industries = (List<Map<String, Object>>) body.get("industries");
    Map<String, Object> industry =
        industries.stream()
            .filter(row -> FARM.value().equals(row.get("id")))
            .findFirst()
            .orElseThrow();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> units = (List<Map<String, Object>>) industry.get("units");
    assertThat(units).as("P6：本格两条 unit 都可见（不是被拒绝就隐藏）").hasSize(2);
    for (Map<String, Object> unit : units) {
      @SuppressWarnings("unchecked")
      Map<String, Object> assets = (Map<String, Object>) unit.get("assets");
      assertThat(assets).as("P6：逐 unit 的 assets 为空（不再把整池复制成每 unit 一份）").isEmpty();
      assertThat(unit.get("assetRejection")).as("P6：逐 unit 发出具名拒绝").isEqualTo(SHARED);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> capacity = (Map<String, Object>) industry.get("capacity");
    assertThat(capacity)
        .as("P6：产业兼容 capacity 不再从整池资产汇出 LAND（两条 unit 的分配都为空）")
        .doesNotContainKey("LAND");
    assertThat(body.get("landMilliMu")).as("P6：格级土地读数同样为 0").isEqualTo(0L);
  }

  @Test
  void singleUnitReadoutStillShowsTheWholePool() {
    ProductionProcess only = unit("unit-r2-readout-single");
    SimulationState state = state(only);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = ApiViews.economyHex(HEX, state);
    @SuppressWarnings("unchecked")
    Map<String, Object> budget = (Map<String, Object>) body.get("assetBudget");
    @SuppressWarnings("unchecked")
    Map<String, Object> allocations = (Map<String, Object>) budget.get("allocations");
    assertThat(allocations)
        .as("P6 单实例回归：唯一 unit 仍拿到整池 100（读口不得误清零）")
        .containsEntry(only.id().value(), Map.of("LAND", LAND));
    @SuppressWarnings("unchecked")
    Map<String, Object> rejections = (Map<String, Object>) budget.get("rejections");
    assertThat(rejections).as("P6 单实例回归：没有共享拒绝").isEmpty();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static SimulationState state(ProductionProcess... units) {
    IndustryDefinitionView plan = plan();
    ProductionData production = ProductionData.empty().withIndustry(plan);
    for (ProductionProcess unit : units) {
      production = production.withInstance(unit);
    }
    OwnershipStake land =
        new OwnershipStake(
            OwnershipStake.idOf(FARM, AssetKind.LAND, OPERATOR, OPERATOR, AssetRightKind.OWNED, 0L),
            FARM,
            AssetKind.LAND,
            OPERATOR,
            OPERATOR,
            LAND,
            AssetRightKind.OWNED);
    EconomyData economy =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "r2-readout",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2,
                        Optional.empty())))
            .withOwnershipStakes(Map.of(land.id(), land));
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(REF, T0, economy));
    modules.put("production", new ProductionSnapshot(REF, T0, production));
    modules.put("actor", new ActorSnapshot(REF, T0, ActorData.empty()));
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  private static ProductionProcess unit(String id) {
    return new ProductionProcess(
        new ProductionUnitId(id),
        FARM,
        ProductionLocation.parse("0_0"),
        OPERATOR,
        FARM.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  private static IndustryDefinitionView plan() {
    return new IndustryDefinitionView(
        FARM,
        "R2 读口夹具",
        new RegimeId("tenant"),
        Optional.empty(),
        1L,
        0L,
        Map.of(AssetKind.LAND, 1_000L),
        Map.of(),
        0L,
        1_000L,
        Map.of(new CommodityId("grain"), 5L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }
}
