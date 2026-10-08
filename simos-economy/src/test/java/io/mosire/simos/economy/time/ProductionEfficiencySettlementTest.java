package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.production.ProductionEfficiencyModifier;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.spi.EconomyGmAdjustments;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>§1.1/§5.2/§6/§11.6 端到端：单 tick 单产业生产框架在真结算路径上的行为</b>。
 *
 * <p>夹具：一格、一产业（`cycleDays=1`、`lpu=1`、`capacityPerUnit={CATTLE:1}`）、10 头牛、30,000 千分劳动/日、 配方默认产出
 * `grain=7`/单位规模 ⇒ 首个周期末规模 = 10、毛产 = `10×7×1000 = 70,000` 毫粮。用真 {@link EconomyDayStepper} 推一天（日循环 =
 * 既有 harvest 全流程），只读 {@link ProductionLedger#gross()}。
 *
 * <p>覆盖：① 修正参数未注入 = 中性（默认数量）；② 注入 500‰ ⇒ 规模减半、毛产按规模变；③ GM `setOutputQuantity` 覆盖 ⇒ 毛产按新数量；④ GM 清除覆盖
 * ⇒ 回落配方默认；⑤ 未知 unit / 重复 unit / null 三类注入拒绝。
 */
class ProductionEfficiencySettlementTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, FARM.value());
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(FARM, OPERATOR);
  private static final CohortKey VIEW =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final HouseholdId HOUSEHOLD = HouseholdIds.ofLegacy(VIEW);
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-framework-test");
  private static final CommodityId GRAIN = new CommodityId("grain");

  @Test
  void noInjectionIsNeutralAndProducesTheRecipeDefault() {
    ProductionLedger ledger = runOneDay(baseEconomy(), List.of());
    assertThat(grossGrain(ledger)).as("未注入修正 = 1000‰ 中性 ⇒ 规模 10、毛产 = 10×7×1000").isEqualTo(70_000L);
  }

  @Test
  void injectedModifierChangesTheHarvestGrossOnTheSameTick() {
    ProductionLedger half = runOneDay(baseEconomy(), List.of(modifier(UNIT, 500L)));
    assertThat(grossGrain(half))
        .as("m=500‰ ⇒ 规模 floor(10×500/1000)=5 ⇒ 毛产 35,000")
        .isEqualTo(35_000L);

    ProductionLedger boosted = runOneDay(baseEconomy(), List.of(modifier(UNIT, 1500L)));
    assertThat(grossGrain(boosted)).as("m=1500‰ ⇒ 规模 15 ⇒ 毛产 105,000").isEqualTo(105_000L);
  }

  @Test
  void gmOutputQuantityOverrideDrivesTheHarvestAndClearFallsBackToTheRecipeDefault() {
    EconomyData overridden =
        EconomyGmAdjustments.project(
                baseEconomy(),
                EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                setParams("farm@0_0", "grain", 3L),
                "gm:test",
                1L)
            .projected();
    assertThat(
            EconomyGmAdjustments.project(
                    overridden,
                    EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY,
                    clearParams("farm@0_0", "grain"),
                    "gm:test",
                    1L)
                .projected()
                .outputQuantityOverrides())
        .as("清除后覆盖表为空 ⇒ 回落默认")
        .isEmpty();

    ProductionLedger withOverride = runOneDay(overridden, List.of());
    assertThat(grossGrain(withOverride))
        .as("GM 覆盖数量_j = 3 ⇒ 规模 10 × 3 = 30,000（默认 7 时是 70,000）")
        .isEqualTo(30_000L);

    EconomyData cleared =
        EconomyGmAdjustments.project(
                overridden,
                EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY,
                clearParams("farm@0_0", "grain"),
                "gm:test",
                1L)
            .projected();
    ProductionLedger afterClear = runOneDay(cleared, List.of());
    assertThat(grossGrain(afterClear)).as("清除覆盖 ⇒ 毛产回落到配方默认 70,000").isEqualTo(70_000L);
  }

  @Test
  void modifierInjectionRejectsUnknownDuplicateAndNull() {
    EconomyDayStepper unknown = new EconomyDayStepper(baseEconomy(), accountsFor(baseEconomy()));
    try {
      ProductionUnitId ghost = ProductionUnitId.idOf(new IndustryId("ghost"), OPERATOR);
      assertThatThrownBy(() -> unknown.updateProductionModifiers(List.of(modifier(ghost, 1000L))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("未知 unit");
    } finally {
      unknown.close();
    }

    EconomyDayStepper duplicate = new EconomyDayStepper(baseEconomy(), accountsFor(baseEconomy()));
    try {
      ProductionEfficiencyModifier one = modifier(UNIT, 500L);
      assertThatThrownBy(() -> duplicate.updateProductionModifiers(List.of(one, one)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("重复");
    } finally {
      duplicate.close();
    }

    EconomyDayStepper nulls = new EconomyDayStepper(baseEconomy(), accountsFor(baseEconomy()));
    try {
      assertThatThrownBy(() -> nulls.updateProductionModifiers(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("不得为 null");
      assertThatThrownBy(
              () ->
                  nulls.updateProductionModifiers(
                      java.util.Arrays.asList((ProductionEfficiencyModifier) null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null");
    } finally {
      nulls.close();
    }
  }

  // ── 夹具（最小自洽世界；只服务本条生产路径） ─────────────────────────────────────────

  private static ProductionLedger runOneDay(
      EconomyData base, List<ProductionEfficiencyModifier> modifiers) {
    EconomyDayStepper stepper = new EconomyDayStepper(base, accountsFor(base));
    try {
      stepper.updateProductionModifiers(modifiers);
      return stepper.step(1L);
    } finally {
      stepper.close();
    }
  }

  private static long grossGrain(ProductionLedger ledger) {
    return ledger.gross().getOrDefault(FARM, Map.of()).getOrDefault(GRAIN, 0L);
  }

  private static ProductionEfficiencyModifier modifier(ProductionUnitId unit, long perMille) {
    return new ProductionEfficiencyModifier(unit, perMille, "test-bridge", "PEF 端到端夹具");
  }

  private static AccountSession accountsFor(EconomyData base) {
    AccountSession accounts = AccountSession.empty();
    for (HouseholdEconomy row : base.classes().values()) {
      accounts.registerHousehold(
          row.id(), row.view().hex(), Map.of(GRAIN, 1_000_000L), Map.of(), Map.of(), Map.of());
    }
    return accounts;
  }

  private static EconomyData baseEconomy() {
    Industry industry =
        new Industry(
            FARM,
            "农业",
            new RegimeId("tenant"),
            1L,
            Map.of(AssetKind.CATTLE, 1L),
            Map.of(),
            0L,
            1L,
            Map.of(GRAIN, 7L),
            Map.of(),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1000)),
            new AllocationRule.Split(1000, 0));
    ProductionProcess unit =
        new ProductionProcess(UNIT, FARM, OPERATOR, FARM.value(), 0L, 0L, Map.of());
    HouseholdEconomy row =
        new HouseholdEconomy(HOUSEHOLD, VIEW, 100L, 100_000L, 1000, 0L, Map.of(), Map.of(), 0L);
    OwnershipStake cattle =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM, AssetKind.CATTLE, OPERATOR, OPERATOR, OwnershipStake.RightKind.OWNED, 0L),
            FARM,
            AssetKind.CATTLE,
            OPERATOR,
            OPERATOR,
            10L,
            OwnershipStake.RightKind.OWNED);
    HouseholdLaborCommitment commitment =
        new HouseholdLaborCommitment(
            ALLOCATION, LOT, HOUSEHOLD, OPERATOR, UNIT.value(), 30_000L, 1L);
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withIndustries(Map.of(FARM, industry))
        .withProcesses(Map.of(UNIT, unit))
        .withHouseholdEconomies(Map.of(HOUSEHOLD, row))
        .withOwnershipStakes(Map.of(cattle.id(), cattle))
        .withLaborCommitments(Map.of(ALLOCATION, commitment));
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "pef-settlement-test",
        0L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  private static ObjectNode setParams(String industryId, String commodityId, long quantity) {
    ObjectNode node = objectNode(industryId, commodityId);
    node.put("quantity", quantity);
    return node;
  }

  private static ObjectNode clearParams(String industryId, String commodityId) {
    return objectNode(industryId, commodityId);
  }

  private static ObjectNode objectNode(String industryId, String commodityId) {
    ObjectNode node = SimosObjectMapper.create().createObjectNode();
    node.put("industryId", industryId);
    node.put("commodityId", commodityId);
    return node;
  }
}
