package io.mosire.simos.production.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotOrigin;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionOpportunity;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 L1b：本 tick 机会簿的独立测试</b>（架构 v1 §1.2 / §3 / §9；plan §3）。
 *
 * <p>判据：
 *
 * <ul>
 *   <li>10/3/1 三块地派生三条机会；A/B 的 expectedInputs/Outputs 按各自 stage/progress 不同，C 用完整周期；
 *   <li>pricePerUnit 有价 ⇒ marketValue/expectedNet/score 有值；缺价 ⇒ 三者 empty，但物理投入/产出仍保留；
 *   <li>unknown plan / unknown stage / progress 越界具名拒；IDLE 无 LAND capacity 与 legacy stages 空不生成机会；
 *   <li>机会顺序稳定（与输入 industries 插入序无关）、重复 opportunityId 由构造器具名拒。
 * </ul>
 *
 * <p>★ 主方案走真实 JSON（{@link StagedProductionTestFixtures#parseIndustry(String)}），阶段定义不手写 new，避免绕过解析面。
 */
class ProductionOpportunityBookTest {

  private static final HouseholdId H1 = new HouseholdId("h1");
  private static final HexCoord ORIGIN = new HexCoord(0, 0);
  private static final HexCoord EAST = new HexCoord(1, 0);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final IndustryId FARM = new IndustryId("grain_farm@0_0");

  // capacityPerUnit LAND=1000 ⇒ A(10000)=scale10、B(3000)=scale3、C(1000)=scale1。
  // stage1(sow): 10d, start grain100/unit, tick grain1/unit/day, NEXT_STAGE。
  // stage2(grow): 90d, tick grain2/unit/day, HARVEST 产 grain1000 + fiber5 /unit。
  private static final String STAGED_JSON =
      """
      {
        "id": "grain_farm@0_0",
        "name": "D-032 L1b 三块地分阶段谷物农场",
        "regime": "tenant",
        "cycleDays": 100,
        "capacityPerUnit": {"LAND": 1000},
        "laborPerUnit": 10,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "stage1",
            "durationDays": 10,
            "startInputPerUnit": {"grain": 100},
            "tickInputPerUnit": {"grain": 1},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          },
          {
            "stageId": "stage2",
            "durationDays": 90,
            "startInputPerUnit": {},
            "tickInputPerUnit": {"grain": 2},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 1000, "fiber": 5}
          }
        ]
      }
      """;

  // 只有 SMALL_TOOL capacity —— 不适用于 LAND lot。
  private static final String NON_LAND_JSON =
      """
      {
        "id": "toolshop@0_0",
        "name": "无 LAND 容量方案",
        "regime": "tenant",
        "cycleDays": 1,
        "capacityPerUnit": {"SMALL_TOOL": 1},
        "laborPerUnit": 0,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "only",
            "durationDays": 1,
            "startInputPerUnit": {},
            "tickInputPerUnit": {},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 1}
          }
        ]
      }
      """;

  // stages 为空 = legacy 单周期；L1b 暂不生成机会。
  private static final String LEGACY_JSON =
      """
      {
        "id": "legacy_farm@0_0",
        "name": "legacy 单周期方案",
        "regime": "tenant",
        "cycleDays": 1,
        "capacityPerUnit": {"LAND": 1000},
        "laborPerUnit": 1,
        "outputPerUnit": {"grain": 1},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0}
      }
      """;

  @Test
  void tenThreeOneLotsDeriveThreeOpportunitiesWithStageSpecificExpectedInputsAndOutputs() {
    IndustryDefinitionView plan = StagedProductionTestFixtures.parseIndustry(STAGED_JSON);
    InventoryLotView view =
        view(
            landLot("0", ORIGIN, 10_000L, inProduction(FARM.value(), "stage1", 8L, 8L, true)),
            landLot("1", ORIGIN, 3_000L, inProduction(FARM.value(), "stage2", 27L, 37L, false)),
            landLot("2", ORIGIN, 1_000L, LotState.IDLE));

    ProductionOpportunityBook book =
        ProductionOpportunityBook.from(view, Map.of(plan.id(), plan), prices(3L, 5L));

    assertThat(book.opportunities()).hasSize(3);
    ProductionOpportunity a = book.require(oppId("land:arable@0_0:0", plan.id()));
    ProductionOpportunity b = book.require(oppId("land:arable@0_0:1", plan.id()));
    ProductionOpportunity c = book.require(oppId("land:arable@0_0:2", plan.id()));

    // A：stage1 80% ⇒ 只剩 2 天；stage2 完整。
    assertThat(a.planId()).isEqualTo(plan.id());
    assertThat(a.ownerHousehold()).isEqualTo(H1);
    assertThat(a.location()).isEqualTo(ORIGIN);
    assertThat(a.carrierLotIds())
        .containsExactly(InventoryLotId.forLand(new LandComponentId("arable@0_0:0")));
    assertThat(a.quantityMilli()).isEqualTo(10_000L);
    assertThat(a.productionScale()).isEqualTo(10L);
    assertThat(a.currentStageId()).isEqualTo("stage1");
    assertThat(a.stageProgressDays()).isEqualTo(8L);
    assertThat(a.cycleProgressDays()).isEqualTo(8L);
    // 手算：stage1 剩 2 天 × grain1 × scale10 = 20；stage2 tick grain2 × 90 × scale10 = 1800。
    assertThat(a.expectedInputs()).containsExactly(Map.entry(GRAIN, 1_820L));
    // 手算：HARVEST grain1000×10×1000=10,000,000 毫；fiber5×10×1000=50,000 毫。
    assertThat(a.expectedOutputs())
        .containsExactly(Map.entry(GRAIN, 10_000_000L), Map.entry(FIBER, 50_000L));
    // 手算估值：产出 10,000,000×3/1000 + 50,000×5/1000 = 30,250；投入 1,820×3/1000 = 5；
    // net=30,245；score=30,245/max(1,5)=6,049。
    assertThat(a.marketValue()).hasValue(30_250L);
    assertThat(a.expectedNet()).hasValue(30_245L);
    assertThat(a.score()).hasValue(6_049L);
    assertThat(a.laborRequirementMilli()).isEqualTo(100L);

    // B：stage2 30% ⇒ 剩余 63 天；没有后续阶段。
    assertThat(b.productionScale()).isEqualTo(3L);
    assertThat(b.currentStageId()).isEqualTo("stage2");
    assertThat(b.stageProgressDays()).isEqualTo(27L);
    assertThat(b.cycleProgressDays()).isEqualTo(37L);
    // 手算：stage2 tick grain2 × 63 × scale3 = 378。
    assertThat(b.expectedInputs()).containsExactly(Map.entry(GRAIN, 378L));
    // 手算：grain1000×3×1000=3,000,000；fiber5×3×1000=15,000。
    assertThat(b.expectedOutputs())
        .containsExactly(Map.entry(GRAIN, 3_000_000L), Map.entry(FIBER, 15_000L));
    // 手算估值：9,000+75=9,075；投入 378×3/1000=1；net=9,074；score=9,074。
    assertThat(b.marketValue()).hasValue(9_075L);
    assertThat(b.expectedNet()).hasValue(9_074L);
    assertThat(b.score()).hasValue(9_074L);
    assertThat(b.laborRequirementMilli()).isEqualTo(30L);

    // C：IDLE ⇒ 完整周期从 stage1 起。
    assertThat(c.productionScale()).isEqualTo(1L);
    assertThat(c.currentStageId()).isEqualTo("stage1");
    assertThat(c.stageProgressDays()).isZero();
    assertThat(c.cycleProgressDays()).isZero();
    // 手算：start grain100 + stage1 tick grain1×10 + stage2 tick grain2×90 = 100+10+180 = 290。
    assertThat(c.expectedInputs()).containsExactly(Map.entry(GRAIN, 290L));
    // 手算：grain1000×1×1000=1,000,000；fiber5×1×1000=5,000。
    assertThat(c.expectedOutputs())
        .containsExactly(Map.entry(GRAIN, 1_000_000L), Map.entry(FIBER, 5_000L));
    // 手算估值：3,000+25=3,025；投入 290×3/1000=0；net=3,025；score=3,025。
    assertThat(c.marketValue()).hasValue(3_025L);
    assertThat(c.expectedNet()).hasValue(3_025L);
    assertThat(c.score()).hasValue(3_025L);

    // A/B 不相等；C 完整周期与 A/B 的剩余周期都不同。
    assertThat(a.expectedInputs()).isNotEqualTo(b.expectedInputs());
    assertThat(a.expectedOutputs()).isNotEqualTo(b.expectedOutputs());
    assertThat(a.currentStageId()).isNotEqualTo(b.currentStageId());
    assertThat(c.expectedInputs()).isNotEqualTo(a.expectedInputs());
    assertThat(c.expectedInputs()).isNotEqualTo(b.expectedInputs());
  }

  @Test
  void missingPriceLeavesValuationEmptyWithoutErasingPhysicalInputsAndOutputs() {
    IndustryDefinitionView plan = StagedProductionTestFixtures.parseIndustry(STAGED_JSON);
    InventoryLotView view =
        view(landLot("0", ORIGIN, 10_000L, inProduction(FARM.value(), "stage1", 8L, 8L, true)));

    ProductionOpportunityBook book =
        ProductionOpportunityBook.from(
            view, Map.of(plan.id(), plan), commodity -> OptionalLong.empty());

    assertThat(book.opportunities()).hasSize(1);
    ProductionOpportunity a = book.opportunities().get(0);
    assertThat(a.marketValue()).isEmpty();
    assertThat(a.expectedNet()).isEmpty();
    assertThat(a.score()).isEmpty();
    // 缺价不伪造估值，但物理量照旧派生。
    assertThat(a.expectedInputs()).containsExactly(Map.entry(GRAIN, 1_820L));
    assertThat(a.expectedOutputs())
        .containsExactly(Map.entry(GRAIN, 10_000_000L), Map.entry(FIBER, 50_000L));
  }

  @Test
  void inProductionLotReferencingUnknownPlanIsRejectedNamed() {
    IndustryDefinitionView plan = StagedProductionTestFixtures.parseIndustry(STAGED_JSON);
    InventoryLotView view =
        view(landLot("0", ORIGIN, 10_000L, inProduction("unknown_plan", "stage1", 0L, 0L, true)));

    assertThatThrownBy(
            () -> ProductionOpportunityBook.from(view, Map.of(plan.id(), plan), prices(3L, 5L)))
        .isInstanceOfSatisfying(
            ProductionContractFault.class,
            fault -> assertThat(fault.reason()).isEqualTo("production-opportunity-plan-missing"));
  }

  @Test
  void inProductionLotWithUnknownStageIsRejectedNamed() {
    IndustryDefinitionView plan = StagedProductionTestFixtures.parseIndustry(STAGED_JSON);
    InventoryLotView view =
        view(landLot("0", ORIGIN, 10_000L, inProduction(FARM.value(), "stage9", 0L, 0L, true)));

    assertThatThrownBy(
            () -> ProductionOpportunityBook.from(view, Map.of(plan.id(), plan), prices(3L, 5L)))
        .isInstanceOfSatisfying(
            ProductionContractFault.class,
            fault -> assertThat(fault.reason()).isEqualTo("production-opportunity-stage-unknown"));
  }

  @Test
  void inProductionLotWithStageProgressBeyondDurationIsRejectedNamed() {
    IndustryDefinitionView plan = StagedProductionTestFixtures.parseIndustry(STAGED_JSON);
    InventoryLotView view =
        view(landLot("0", ORIGIN, 10_000L, inProduction(FARM.value(), "stage1", 11L, 11L, true)));

    assertThatThrownBy(
            () -> ProductionOpportunityBook.from(view, Map.of(plan.id(), plan), prices(3L, 5L)))
        .isInstanceOfSatisfying(
            ProductionContractFault.class,
            fault ->
                assertThat(fault.reason())
                    .isEqualTo("production-opportunity-stage-progress-out-of-range"));
  }

  @Test
  void idleLandLotWithPlanThatHasNoLandCapacityYieldsNoOpportunity() {
    IndustryDefinitionView nonLandPlan = StagedProductionTestFixtures.parseIndustry(NON_LAND_JSON);
    InventoryLotView view = view(landLot("0", ORIGIN, 1_000L, LotState.IDLE));

    ProductionOpportunityBook book =
        ProductionOpportunityBook.from(view, Map.of(nonLandPlan.id(), nonLandPlan), prices(3L, 5L));

    assertThat(book.opportunities()).isEmpty();
  }

  @Test
  void legacyPlanWithEmptyStagesGeneratesNoOpportunityForIdleOrInProductionLot() {
    IndustryDefinitionView legacy = StagedProductionTestFixtures.parseIndustry(LEGACY_JSON);

    ProductionOpportunityBook idleBook =
        ProductionOpportunityBook.from(
            view(landLot("0", ORIGIN, 1_000L, LotState.IDLE)),
            Map.of(legacy.id(), legacy),
            prices(3L, 5L));
    assertThat(idleBook.opportunities()).as("IDLE + legacy(stages 空) 不生成机会").isEmpty();

    ProductionOpportunityBook inProductionBook =
        ProductionOpportunityBook.from(
            view(
                landLot(
                    "0",
                    ORIGIN,
                    1_000L,
                    inProduction(legacy.id().value(), "anyStage", 0L, 0L, true))),
            Map.of(legacy.id(), legacy),
            prices(3L, 5L));
    assertThat(inProductionBook.opportunities())
        .as("InProduction + legacy(stages 空) 不生成机会")
        .isEmpty();
  }

  @Test
  void opportunitiesAreSortedStablyIndependentOfIndustryInsertionOrder() {
    IndustryDefinitionView planB = simplePlan("order_farm_b");
    IndustryDefinitionView planA = simplePlan("order_farm_a");
    InventoryLotView view =
        view(
            landLot("2", EAST, 1_000L, LotState.IDLE), landLot("1", ORIGIN, 1_000L, LotState.IDLE));

    // 故意按 b→a 插入；机会簿仍按 location.q/r → lotId → planId 稳定排序。
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();
    industries.put(planB.id(), planB);
    industries.put(planA.id(), planA);

    ProductionOpportunityBook first =
        ProductionOpportunityBook.from(view, industries, prices(3L, 5L));
    ProductionOpportunityBook second =
        ProductionOpportunityBook.from(view, industries, prices(3L, 5L));

    assertThat(first.opportunities())
        .extracting(ProductionOpportunity::opportunityId)
        .containsExactly(
            "land:arable@0_0:1|order_farm_a",
            "land:arable@0_0:1|order_farm_b",
            "land:arable@1_0:2|order_farm_a",
            "land:arable@1_0:2|order_farm_b");
    assertThat(second.opportunities()).isEqualTo(first.opportunities());
  }

  @Test
  void duplicateOpportunityIdIsRejectedByBookConstructor() {
    ProductionOpportunity duplicate = exampleOpportunity("duplicate-opportunity");
    ProductionOpportunity sameIdDifferentObject = exampleOpportunity("duplicate-opportunity");

    assertThatThrownBy(
            () -> new ProductionOpportunityBook(List.of(duplicate, sameIdDifferentObject)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("production-opportunity-duplicate-id")
        .hasMessageContaining("duplicate-opportunity");
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private static Function<CommodityId, OptionalLong> prices(long grainPrice, long fiberPrice) {
    return commodity -> {
      if (commodity.equals(GRAIN)) {
        return OptionalLong.of(grainPrice);
      }
      if (commodity.equals(FIBER)) {
        return OptionalLong.of(fiberPrice);
      }
      return OptionalLong.empty();
    };
  }

  private static InventoryLotView view(InventoryLot... lots) {
    return new InventoryLotView(List.of(lots));
  }

  private static InventoryLot landLot(
      String ordinal, HexCoord location, long quantityMilli, LotState state) {
    String componentId = "arable@" + location.q() + "_" + location.r() + ":" + ordinal;
    return new InventoryLot(
        InventoryLotId.forLand(new LandComponentId(componentId)),
        InventoryLotKind.LAND,
        "arable",
        H1,
        Optional.of(location),
        quantityMilli,
        state,
        LotOrigin.currentStock(0L),
        false);
  }

  private static LotState inProduction(
      String planId,
      String stageId,
      long stageProgressDays,
      long cycleProgressDays,
      boolean startInputPaid) {
    return new LotState.InProduction(
        planId, stageId, stageProgressDays, cycleProgressDays, Map.of(), startInputPaid, Map.of());
  }

  private static String oppId(String lotId, IndustryId planId) {
    return lotId + "|" + planId.value();
  }

  /** 单阶段、无地点段（适用任意格）的最小方案；仅用于顺序稳定用例。 */
  private static IndustryDefinitionView simplePlan(String id) {
    String json =
        """
        {
          "id": "%s",
          "name": "顺序方案 %s",
          "regime": "tenant",
          "cycleDays": 10,
          "capacityPerUnit": {"LAND": 1000},
          "laborPerUnit": 1,
          "outputPerUnit": {},
          "cycleInputPerUnit": {},
          "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
          "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
          "stages": [
            {
              "stageId": "only",
              "durationDays": 10,
              "startInputPerUnit": {},
              "tickInputPerUnit": {},
              "endAction": "HARVEST",
              "outputPerUnit": {"grain": 1000}
            }
          ]
        }
        """
            .formatted(id, id);
    return StagedProductionTestFixtures.parseIndustry(json);
  }

  private static ProductionOpportunity exampleOpportunity(String opportunityId) {
    return new ProductionOpportunity(
        opportunityId,
        FARM,
        ORIGIN,
        H1,
        List.of(InventoryLotId.forLand(new LandComponentId("arable@0_0:0"))),
        1_000L,
        1L,
        "stage1",
        0L,
        0L,
        Map.of(GRAIN, 1L),
        Map.of(GRAIN, 1L),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        0L);
  }
}
