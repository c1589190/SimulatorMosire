package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RF2/RF2e 验收：S4 周期投入只对实际入选 unit 扣、周期中途首次入选补扣一次、扣种保留一 tick 口粮、 同 hex 独立家户不共享 grain
 * 种子池</b>。
 *
 * <p>判据出处：{@code docs/superpowers/plans/2026-10-20-d032-rf2-s4-labor-seed-taskbook.md} §6、 {@code
 * docs/superpowers/plans/2026-10-20-d032-rf2e-midcycle-sow-taskbook.md} §6。
 *
 * <p>★ <b>不按实现反推</b>：本类只对 S4 的公开（包内）入口 {@link ProductionInputSettlement#surveyInputDemands} /
 * {@link ProductionInputSettlement#drawCycleInputs} 喂"真实世界形状"的输入，再用家户账/unit 周期状态/consumed 账核对。
 */
class Rf2S4InputSeedAcceptanceTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId FARM = IndustryHexKeys.id("rf2-input-farm", 0, 0);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final RegimeId REGIME = new RegimeId("tenant");

  private static final HouseholdId H1 = HouseholdId.parse("hh-rf2-input-a");
  private static final HouseholdId H2 = HouseholdId.parse("hh-rf2-input-b");

  /** 一 tick 口粮（保留口径 = naturalNeeds[grain] 当前值）。 */
  private static final long RATION = EconomyVocabulary.dailyRationMilli(1L, 1L);

  /** 每 1 规模整周期种子。 */
  private static final long SEED_PER_SCALE = 12_000L;

  @Test
  void midcycleFirstSelectionDrawsOnceAndLeavesAtLeastOneTickRation() {
    Fixture f = new Fixture(Map.of(H1, RATION + SEED_PER_SCALE), 4L);
    Set<ProductionUnitId> producing = Set.of(f.unitId);

    // 周期中途首次入选：progressDays>0 且 cycleInputUsed 为空 ⇒ 必须进入补扣调查。
    List<ProductionInputSettlement.InputPlan> plans =
        ProductionInputSettlement.surveyInputDemands(
            f.units,
            f.industries,
            f.households,
            Map.of(),
            Map.of(),
            f.index,
            f.goods,
            f.householdOfActor,
            producing);
    assertThat(plans).as("★ RF2e：周期中途首次入选也必须进入 S4 投入调查").hasSize(1);

    ProductionInputSettlement.drawCycleInputs(
        f.units,
        f.industries,
        f.households,
        Map.of(),
        Map.of(),
        f.index,
        f.goods,
        Map.of(),
        f.consumed,
        f.householdOfActor,
        f.ledger,
        4L,
        producing);

    assertThat(f.goods.get(H1).get(GRAIN)).as("★ 扣种后至少保留一 tick 口粮（不得把户粮清零）").isEqualTo(RATION);
    assertThat(f.consumed.get(H1)).as("★ 实际扣种 = 本周期所需种子").containsEntry(GRAIN, SEED_PER_SCALE);
    assertThat(f.units.get(f.unitId).cycleInputUsedMilli())
        .as("★ 补扣成功后 cycleInputUsedMilli 变非空（下一 tick 自然不再补扣）")
        .containsEntry(GRAIN, SEED_PER_SCALE);
    assertThat(f.units.get(f.unitId).progressDays()).as("补扣不得改写周期进度").isEqualTo(4L);

    // 同 unit 下一 tick：即使户粮再次充足，也不得重复扣（cycleInputUsed 非空 ⇒ 周期门关闭）。
    f.goods.get(H1).put(GRAIN, RATION + SEED_PER_SCALE);
    f.consumed.clear();
    ProductionInputSettlement.drawCycleInputs(
        f.units,
        f.industries,
        f.households,
        Map.of(),
        Map.of(),
        f.index,
        f.goods,
        Map.of(),
        f.consumed,
        f.householdOfActor,
        f.ledger,
        5L,
        producing);
    assertThat(f.goods.get(H1).get(GRAIN))
        .as("★ 同一 unit 下一 tick 不得重复扣种")
        .isEqualTo(RATION + SEED_PER_SCALE);
    assertThat(f.consumed).as("不再产生新的投入扣减").isEmpty();
  }

  @Test
  void unselectedUnitDrawsNothing() {
    Fixture f = new Fixture(Map.of(H1, RATION + SEED_PER_SCALE), 4L);

    ProductionInputSettlement.drawCycleInputs(
        f.units,
        f.industries,
        f.households,
        Map.of(),
        Map.of(),
        f.index,
        f.goods,
        Map.of(),
        f.consumed,
        f.householdOfActor,
        f.ledger,
        4L,
        Set.of()); // ★ 未入选/无实际劳动

    assertThat(f.goods.get(H1).get(GRAIN))
        .as("★ 未入选 unit 不得预扣整周期种子")
        .isEqualTo(RATION + SEED_PER_SCALE);
    assertThat(f.consumed).as("未入选 ⇒ 无投入扣减").isEmpty();
    assertThat(f.units.get(f.unitId).cycleInputUsedMilli()).isEmpty();
  }

  @Test
  void independentHouseholdsOnTheSameHexDoNotShareTheGrainSeedPool() {
    // H1 有足够种子，H2 只有口粮（没有可扣种子）。若同 hex 跨户合池，H2 会从 H1 的账里拿种。
    Fixture f =
        new Fixture(Map.of(H1, RATION + SEED_PER_SCALE, H2, RATION), 4L, Map.of(H1, H1, H2, H2));

    ProductionInputSettlement.drawCycleInputs(
        f.units,
        f.industries,
        f.households,
        Map.of(),
        Map.of(),
        f.index,
        f.goods,
        Map.of(),
        f.consumed,
        f.householdOfActor,
        f.ledger,
        4L,
        Set.copyOf(f.units.keySet()));

    assertThat(f.consumed.get(H1)).as("★ H1 从自己的账扣种").containsEntry(GRAIN, SEED_PER_SCALE);
    assertThat(f.goods.get(H1).get(GRAIN)).as("H1 保留一 tick 口粮").isEqualTo(RATION);
    assertThat(f.consumed.getOrDefault(H2, Map.of()))
        .as("★ 独立家户不得共享 grain 种子池：H2 不得从 H1 的账取料")
        .isEmpty();
    assertThat(f.goods.get(H2).get(GRAIN)).as("H2 的口粮一粒不动").isEqualTo(RATION);
    assertThat(f.units.get(f.unitIdOf(H2)).cycleInputUsedMilli())
        .as("H2 本周期没有可扣种子 ⇒ cycleInputUsed 保持为空")
        .isEmpty();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static final class Fixture {
    final IndustryDefinitionView industry;
    final Map<IndustryId, IndustryDefinitionView> industries;
    final Map<ProductionUnitId, ProductionProcess> unitById = new LinkedHashMap<>();
    final LinkedHashMap<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    final LinkedHashMap<HouseholdId, HouseholdEconomy> households = new LinkedHashMap<>();
    final LinkedHashMap<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    final LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
    final Map<ActorRef, HouseholdId> householdOfActor = new LinkedHashMap<>();
    final SettlementIndex index;
    final ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(4L);
    final ProductionUnitId unitId;

    /** 单户夹具（绝大多数用例）。 */
    Fixture(Map<HouseholdId, Long> grainByHousehold, long progressDays) {
      this(grainByHousehold, progressDays, Map.of(H1, H1));
    }

    /**
     * 多户夹具：{@code unitOwners} 的值 = 每户自己的 unit operator（本类里恒 = 家户本身），仅用于显式表达 "同一 hex 的多个独立
     * operator"。
     */
    Fixture(
        Map<HouseholdId, Long> grainByHousehold,
        long progressDays,
        Map<HouseholdId, HouseholdId> unitOwners) {
      industry = industry();
      industries = Map.of(FARM, industry);
      Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
      for (Map.Entry<HouseholdId, HouseholdId> entry : unitOwners.entrySet()) {
        HouseholdId household = entry.getKey();
        if (!grainByHousehold.containsKey(household)) {
          continue;
        }
        ActorRef operator = HouseholdActors.of(entry.getValue());
        ProductionUnitId id =
            new ProductionUnitId("unit-rf2-" + household.value() + "-" + progressDays);
        unitById.put(id, unit(id, operator, progressDays));
        units.put(id, unitById.get(id));
        households.put(
            household,
            new HouseholdEconomy(
                household,
                new CohortKey(HEX, ResidenceKind.RURAL, POOR),
                1L,
                1_000_000L,
                1_000,
                0L,
                Map.of(GRAIN, RATION),
                Map.of(),
                0L));
        goods.put(household, new LinkedHashMap<>(Map.of(GRAIN, grainByHousehold.get(household))));
        householdOfActor.put(operator, household);
        OwnershipStake share = workshopShare(household);
        shares.put(share.id(), share);
      }
      unitId = units.keySet().iterator().next();
      index =
          SettlementIndex.build(
              units,
              industries,
              shares,
              Map.of(),
              households,
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              progressDays);
    }

    ProductionUnitId unitIdOf(HouseholdId household) {
      return units.keySet().stream()
          .filter(id -> id.value().contains(household.value()))
          .findFirst()
          .orElseThrow();
    }
  }

  private static IndustryDefinitionView industry() {
    return new IndustryDefinitionView(
        FARM,
        "RF2 投入夹具",
        REGIME,
        Optional.empty(),
        120L,
        0L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 5_000L),
        Map.of(AssetKind.WORKSHOP, Map.of(GRAIN, SEED_PER_SCALE)),
        List.of(new ClassSlot(POOR, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  private static ProductionProcess unit(ProductionUnitId id, ActorRef operator, long progressDays) {
    return new ProductionProcess(
        id,
        FARM,
        ProductionLocation.parse(HEX_KEY),
        operator,
        "mode:rf2-input",
        progressDays,
        Map.of(),
        Map.of());
  }

  private static OwnershipStake workshopShare(HouseholdId household) {
    ActorRef operator = HouseholdActors.of(household);
    AssetShareId id =
        OwnershipStake.idOf(
            FARM,
            AssetKind.WORKSHOP,
            operator,
            operator,
            AssetRightKind.OWNED,
            ProductionLocation.parse(HEX_KEY),
            0L);
    return new OwnershipStake(
        id,
        FARM,
        AssetKind.WORKSHOP,
        operator,
        operator,
        1L,
        AssetRightKind.OWNED,
        ProductionLocation.parse(HEX_KEY));
  }
}
