package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>D-032 RF4b 验收：organizer source 的迁移必须整源跳过（无半迁移、无 roster 合并），非 organizer source 照常</b>。
 *
 * <p>判据出处：{@code docs/superpowers/plans/2026-10-20-d032-rf4b-migration-organizer-guard-taskbook.md}
 * §5。
 *
 * <p>★ <b>判别力</b>：若守卫缺失，organizer 整户迁空会在 {@code MigrationPopulationMove.applySource} 抛 {@code
 * organizer-merge-requires-rebind}，整次 advance 被拒；若守卫只做半过滤，则会留下部分 move 已落账的中间态。 本类同时钉住"源户 0 move
 * 落账"与"非 organizer 的 move 照常"。
 */
class Rf4bMigrationOrganizerGuardTest {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final long DAY = 30L;
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-rf4b-organizer");
  private static final HouseholdId ORGANIZER = HouseholdId.parse("hh-rf4b-organizer");
  private static final HouseholdId PLAIN = HouseholdId.parse("hh-rf4b-plain");
  private static final HouseholdId HOST = HouseholdId.parse("hh-rf4b-host");

  @Test
  void organizerSourceIsSkippedAsAWholeWhileNonOrganizerStillMigrates() {
    EconomyData base = base();
    EconomySession session = new EconomySession(base, defs(), runtimeOf(defs()));
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(ORGANIZER, H1, Map.of(GRAIN, 1_000L), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(PLAIN, H1, Map.of(GRAIN, 1_000L), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(HOST, H2, Map.of(), Map.of(), Map.of(), Map.of());

    List<ModeMigrationPolicy.MigrationMove> moves = new ArrayList<>();
    moves.add(move(ORGANIZER, HOST, 40L, "organizer")); // ★ 必须整源跳过
    moves.add(move(PLAIN, HOST, 30L, "plain")); // ★ 必须照常执行

    List<String> logLines;
    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MIGRATION_LOGGER_NAME, Level.INFO)) {
      ModeMigrationSettlement.apply(
          session,
          accounts,
          new ModeMigrationPolicy.MigrationPlan(moves),
          EconomyFixtures.dayView(base, defs()),
          DAY);
      logLines = List.copyOf(log.lines());
    }

    List<EconomyPopulationTransfer> transfers = session.drainPendingPopulationTransfers();
    assertThat(transfers).as("★ organizer source 的 move 一条都不得落账；只允许非 organizer 的一条").hasSize(1);
    assertThat(transfers.get(0).source()).isEqualTo(PLAIN);
    assertThat(transfers.get(0).target()).isEqualTo(HOST);
    assertThat(transfers.get(0).population()).isEqualTo(30L);

    assertThat(accounts.householdGoods().get(ORGANIZER).get(GRAIN))
        .as("★ 无半迁移：organizer 源户库存逐毫不动")
        .isEqualTo(1_000L);
    assertThat(accounts.householdGoods().get(PLAIN).get(GRAIN))
        .as("非 organizer source 的随迁库存按比例 floor：⌊1,000 × 30 ÷ 100⌋ = 300")
        .isEqualTo(700L);
    assertThat(accounts.householdGoods().get(HOST).get(GRAIN))
        .as("目标户只收到 non-organizer 的那一份")
        .isEqualTo(300L);

    assertThat(logLines)
        .as("★ 必须具名审计：source/target/unit/reason 都在")
        .anyMatch(
            line ->
                line.contains("MIGRATION_ORGANIZER_REBIND_UNSUPPORTED")
                    && line.contains("source=" + ORGANIZER.value())
                    && line.contains("targets=[" + HOST.value() + "]")
                    && line.contains("units=[" + UNIT.value() + "]")
                    && line.contains("reason=production-rebind-not-implemented"));
  }

  private static ModeMigrationPolicy.MigrationMove move(
      HouseholdId source, HouseholdId target, long population, String reason) {
    return new ModeMigrationPolicy.MigrationMove(
        source,
        target,
        H2,
        DefaultProductionModes.GRAIN_FARM,
        10L,
        population,
        0L,
        0L,
        "fixture:rf4b-" + reason,
        Map.of());
  }

  private static EconomyData base() {
    ClassPositionId position = grainPosition();
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(ORGANIZER, row(ORGANIZER, H1, 100L));
    rows.put(PLAIN, row(PLAIN, H1, 100L));
    rows.put(HOST, row(HOST, H2, 100L));
    Map<HouseholdId, HouseholdClassMembership> memberships = new LinkedHashMap<>();
    for (HouseholdId household : List.of(ORGANIZER, PLAIN, HOST)) {
      memberships.put(
          household,
          new HouseholdClassMembership(
              household, position, position, Set.of(), Map.of(), 0L, 0L, "rf4b-test"));
    }
    ProductionRoster roster =
        new ProductionRoster(
            UNIT,
            FARM,
            List.of(
                ProductionParticipant.of(
                    ORGANIZER,
                    io.mosire.simos.economy.api.production.ProductionRoleId.parse("operator"),
                    true,
                    0L)));
    return EconomyData.empty()
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(rows)
        .withClassMemberships(memberships)
        .withProductionRosters(Map.of(UNIT, roster));
  }

  private static ClassPositionId grainPosition() {
    return DefaultProductionModes.positionId(
            DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
        .orElseThrow();
  }

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex, long population) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, POOR),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static ProductionData defs() {
    return ProductionData.empty().withModes(DefaultProductionModes.modes());
  }

  private static ProductionRuntime runtimeOf(ProductionData definitions) {
    return new ProductionRuntimeEngine(definitions);
  }
}
