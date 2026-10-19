package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>W4 真实 tick（D-032 D3 §7.1 步 4、§8.4 P30/P31/P36）</b>：把 Social 下一 tick 生存事实经 {@link
 * EconomyDayStepper#updateSurvivalSocialFacts} 注入真实日循环，证明：
 *
 * <ul>
 *   <li>{@code idleEligible}（生存资格）与 {@code idleChosen}（策略选择）是两件事：同一生存不足输入，默认 {@code
 *       PREFER_IDLE_WHEN_SUBSISTENCE_UNMET} 选择闲置，{@code BEST_EFFORT_WORK} 仍授予正收益工作；
 *   <li>{@code SURVIVAL_OUTLOOK} 日志同一条同时给出两个读数（不把资格冒充选择）。
 * </ul>
 */
class W4SurvivalTickIntegrationTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId FARM = IndustryHexKeys.id("w4-tick-farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-w4-tick");
  private static final HouseholdId PAYER = HouseholdId.parse("hh-w4-tick-payer");
  private static final HouseholdId WORKER = HouseholdId.parse("hh-w4-tick-worker");
  private static final ActorRef PAYER_ACTOR = HouseholdActors.of(PAYER);
  private static final ActorRef WORKER_ACTOR = HouseholdActors.of(WORKER);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ProductionRoleId ROLE_ORGANIZER = ProductionRoleId.parse("operator");
  private static final ProductionRoleId ROLE_WORKER = ProductionRoleId.parse("hired-hand");
  private static final MarketZoneId ZONE = new MarketZoneId("w4-tick-zone");
  private static final PeopleLotId PAYER_LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final PeopleLotId WORKER_LOT = new PeopleLotId("rural:0_0:FEMALE:1");
  private static final long NEXT_TICK = 2L;
  private static final long NEED_MILLI = 10_000L;
  private static final long LABOR_BUDGET = 1_000L;

  @Test
  void defaultPolicyChoosesIdleWhileEligibilityAndChoiceStaySeparate() {
    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.POPULATION_LOGGER_NAME, Level.DEBUG)) {
      EconomyDayStepper stepper = stepper(Map.of());
      try {
        stepper.updateSurvivalSocialFacts(socialFacts());
        stepper.step(1L);
        String outlook =
            log.lines().stream()
                .filter(line -> line.contains("SURVIVAL_OUTLOOK"))
                .filter(line -> line.contains(WORKER.value()))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("没有 WORKER 的 SURVIVAL_OUTLOOK 事件: " + log.lines()));
        assertThat(outlook)
            .as("U23-b：资格读数与选择读数必须同条可读")
            .contains("idleEligible=true")
            .contains("idleChosen=true");
        assertThat(productionCommitments(stepper, WORKER))
            .as("PREFER_IDLE_WHEN_SUBSISTENCE_UNMET：资格成立即可自由分配劳动 ⇒ 不写生产劳动配额")
            .isEmpty();
      } finally {
        stepper.close();
      }
    }
  }

  @Test
  void bestEffortPolicyWorksDespiteSameEligibility() {
    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.POPULATION_LOGGER_NAME, Level.DEBUG)) {
      EconomyDayStepper stepper =
          stepper(Map.of(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, 0L)); // BEST_EFFORT_WORK
      try {
        stepper.updateSurvivalSocialFacts(socialFacts());
        stepper.step(1L);

        String outlook =
            log.lines().stream()
                .filter(line -> line.contains("SURVIVAL_OUTLOOK"))
                .filter(line -> line.contains(WORKER.value()))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("没有 WORKER 的 SURVIVAL_OUTLOOK 事件: " + log.lines()));
        assertThat(outlook)
            .as("同一生存不足输入：资格仍成立，但 BEST_EFFORT 不强制闲置")
            .contains("idleEligible=true")
            .contains("idleChosen=false");
        assertThat(productionCommitments(stepper, WORKER))
            .as("BEST_EFFORT_WORK：正收益工作被选中并写入劳动配额（idleChosen=false 的行为证据）")
            .isNotEmpty();
      } finally {
        stepper.close();
      }
    }
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static SurvivalSocialFacts socialFacts() {
    NextTickSubsistenceNeed need =
        new NextTickSubsistenceNeed(
            NEXT_TICK,
            List.of(new SubsistenceNeedLine(new SubsistenceNeedKey("food"), GRAIN, NEED_MILLI)));
    return SurvivalSocialFacts.of(
        NEXT_TICK,
        Map.of(PAYER, need, WORKER, need),
        Map.of(PAYER, LABOR_BUDGET, WORKER, LABOR_BUDGET));
  }

  private static List<HouseholdLaborCommitment> productionCommitments(
      EconomyDayStepper stepper, HouseholdId household) {
    return stepper.data().allocations().values().stream()
        .filter(commitment -> commitment.household().equals(household))
        .filter(commitment -> commitment.kind() == LaborCommitmentKind.PRODUCTION)
        .filter(commitment -> commitment.laborMilli() > 0L)
        .toList();
  }

  private static EconomyDayStepper stepper(Map<EconomyPolicyKey, Long> policies) {
    IndustryDefinitionView industry = industry();
    // ★★ D-032 T1 重写：被观察的家户是**自营 grain_farm 经营者**（不是被 M3 雇佣锁占满预算的雇工）。
    //   这样「idleEligible（资格）」与「idleChosen（策略选择）」才是两个可分开观察的量：
    //   PREFER_IDLE 下不写劳动配额，BEST_EFFORT 下才写正收益生产配额。
    ProductionProcess unit =
        new ProductionProcess(
            UNIT,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            WORKER_ACTOR,
            DefaultProductionModes.GRAIN_FARM.value(),
            0L,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());
    ProductionData definitions =
        ProductionData.empty()
            .withModes(DefaultProductionModes.modes())
            .withIndustry(industry)
            .withInstance(unit);

    OwnershipStake land =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM,
                AssetKind.LAND,
                WORKER_ACTOR,
                WORKER_ACTOR,
                AssetRightKind.OWNED,
                ProductionLocation.parse(HEX_KEY),
                0L),
            FARM,
            AssetKind.LAND,
            WORKER_ACTOR,
            WORKER_ACTOR,
            1_000L,
            AssetRightKind.OWNED,
            ProductionLocation.parse(HEX_KEY));
    // ★★★ P4c3：工资报价只读 U20 锚（键 = 市场区 × 产业 × 角色）；缺锚具名拒绝、不按 0 兜底。
    WageAnchorKey anchorKey = new WageAnchorKey(ZONE, FARM, ROLE_WORKER);
    ProductionWageAnchor anchor = new ProductionWageAnchor(SILVER, 1L, 0L, 0L, -1L, 0L, 0L, 0L);
    EconomyData base =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "w4-tick-survival",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                        Optional.empty(),
                        policies,
                        Map.of(),
                        Map.of())))
            .withHouseholdEconomies(Map.of(PAYER, row(PAYER), WORKER, row(WORKER)))
            .withProductionRosters(Map.of(UNIT, roster()))
            .withProductionWageAnchors(Map.of(anchorKey, anchor))
            .withOwnershipStakes(Map.of(land.id(), land))
            .withRelations(
                Map.of(
                    UNIT,
                    new ProductionRules(
                        UNIT,
                        WORKER_ACTOR,
                        new Payee.ToActor(WORKER_ACTOR),
                        List.of(),
                        WORKER_ACTOR)))
            .withMarketZones(Map.of(ZONE, zone()))
            .withMarketTables(Map.of(ZONE, EconomyFixtures.quote(SILVER, Map.of(GRAIN, 1L))));

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(PAYER, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(WORKER, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    // ★★ D-032 T1：产品份额的下一 tick 生存投影需要**权威 revision**（LaborSurvivalBook fail-closed）；
    //   旧夹具是雇工所以从未走到这条，自营 organizer 夹具必须显式给 expectedRevision。
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            base,
            definitions,
            new ProductionRuntimeEngine(definitions),
            accounts,
            MarketTopology.singleHex(base.marketTables(), base.marketZones()));
    stepper.updateComposition(
        Map.of(
            PAYER, Map.of(PAYER_LOT, 100L),
            WORKER, Map.of(WORKER_LOT, 100L)));
    return stepper;
  }

  private static IndustryDefinitionView industry() {
    ProductionRoleDefinition organizer =
        new ProductionRoleDefinition(
            ROLE_ORGANIZER,
            "组织者",
            Optional.empty(),
            1_000L,
            IntraRoleSplitRule.EQUAL,
            0L,
            Optional.empty(),
            List.of(),
            new RolePayment.ProductShare());
    return new IndustryDefinitionView(
        FARM,
        "W4 tick 夹具",
        new RegimeId("household"),
        Optional.empty(),
        // ★★ cycleDays=10 ⇒ 产品份额在下一 tick 还不可交付：生存投影按目标口径跳过该替代
        //   （不伪造未来收入），但劳动队列里的正收益 offer 仍在 ⇒ 两种 idle 策略可分开观察。
        10L,
        0L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 5L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED),
        List.of(organizer),
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.empty());
  }

  private static ProductionRoster roster() {
    // ★ D-032 T1：自营名册只有 organizer（product share）一个参与项；没有 MONEY_WAGE 雇工，
    //   因此 M3 不会产生雇佣锁，M6 的 idle 选择才是可观察的。
    return new ProductionRoster(
        UNIT,
        FARM,
        List.of(
            io.mosire.simos.economy.model.ProductionParticipant.of(
                WORKER, ROLE_ORGANIZER, true, LABOR_BUDGET)));
  }

  private static HouseholdEconomy row(HouseholdId household) {
    return new HouseholdEconomy(
        household,
        new io.mosire.simos.economy.api.cohort.CohortKey(
            HEX,
            io.mosire.simos.economy.api.cohort.ResidenceKind.RURAL,
            SocialClassId.POOR_PEASANT),
        100L,
        LABOR_BUDGET,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static MarketZone zone() {
    return new MarketZone(
        ZONE, HEX, 1, Set.of(HEX), SILVER, Map.of(), MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE);
  }
}
