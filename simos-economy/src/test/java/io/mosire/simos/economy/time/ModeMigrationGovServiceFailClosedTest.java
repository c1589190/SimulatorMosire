package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ★★ <b>Z3b §7 / §10.1：{@code ModeMigrationSettlement} 对 {@code GOV_SERVICE} 承诺 fail-closed</b>（含
 * GOV_SERVICE 的 source ⇒ 具名 ERROR + ISE + 世界零变化；不含 GOV_SERVICE 的既有迁移路径逐值不变）。
 *
 * <p>"五路"覆盖 = 指向同一 source 的五种计划形态：全迁到既有户 / 部分迁到既有户 / 全迁到新建户 / 一源拆两笔 / 同户里 GOV 与 PRODUCTION 混存 ——
 * 五种都必须先命中 GOV 拒因，且在**任何写入之前**抛出（投影账/账户/资产/关系全部未动、outbox 空）。
 *
 * <p>★ <b>为什么世界零变化能成立</b>：拒绝发生在 {@code applySource} 建立 {@code sourceLaborCommitments} 之后、任何
 * put/remove/record 之前；{@code base} 本身不可变。故断言 = ① {@code session} 工作表逐值不变；② 无 {@code
 * EconomyPopulationTransfer}；③ 传入的 {@code base} 逐值不变。
 *
 * <p>★ <b>日志断言边界</b>：{@code MIGRATION_GOV_SERVICE_COMMITMENT_REFUSED} ERROR 走 slf4j；economy 测试
 * scope 无 可捕获 appender，故 ERROR 字段语义落在与它同源的具名 ISE 消息上（source/allocation/activity/laborMilli 逐项）。
 */
class ModeMigrationGovServiceFailClosedTest {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final HouseholdId SOURCE = HouseholdId.parse("hh-mig-gov-source");
  private static final HouseholdId TARGET = HouseholdId.parse("hh-mig-existing-target");
  private static final HouseholdId OTHER = HouseholdId.parse("hh-mig-other-target");
  private static final HouseholdId NEW_TARGET = HouseholdId.parse("hh-mig-new-target");
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOC = new LaborAllocationId("alloc-mig-gov");
  private static final ProductionModeId FAMILY = DefaultProductionModes.FAMILY_FARM;
  private static final ClassPositionId POSITION =
      DefaultProductionModes.positionId(FAMILY, DefaultProductionModes.ROLE_FAMILY_FARMER)
          .orElseThrow();

  /** 既有目标户的经营 unit/组织：让正对照真的能把 PRODUCTION 承诺搬到目标 unit（而不是只做扣源）。 */
  private static final IndustryId TARGET_INDUSTRY =
      IndustryHexKeys.id("farm-target", H2.q(), H2.r());

  private static final ProductionUnitId TARGET_UNIT =
      ProductionUnitId.idOf(TARGET_INDUSTRY, HouseholdActors.of(TARGET));
  private static final ProductionOrganizationId TARGET_ORGANIZATION =
      new ProductionOrganizationId("org-mig-existing-target");
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** 五路：同一 GOV source 的五种计划形态（mixed=true 的家户里还有一条 PRODUCTION 行）。 */
  static List<Arguments> govServicePlans() {
    return List.of(
        Arguments.of("full-move-to-existing-target", plan(move(TARGET, H2, 100L)), false),
        Arguments.of("partial-move-to-existing-target", plan(move(TARGET, H2, 40L)), false),
        Arguments.of("full-move-to-new-target", plan(move(NEW_TARGET, H2, 100L)), false),
        Arguments.of(
            "split-one-source-into-two-moves",
            plan(move(TARGET, H2, 40L), move(OTHER, H2, 60L)),
            false),
        Arguments.of("mixed-gov-and-production-rows", plan(move(TARGET, H2, 40L)), true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("govServicePlans")
  void migrationRefusesGovServiceSourceOnEveryPlanShape(
      String label, ModeMigrationPolicy.MigrationPlan plan, boolean mixed) {
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(ALLOC, commitment(LaborCommitmentKind.GOV_SERVICE, 600L));
    if (mixed) {
      commitments.put(
          new LaborAllocationId("alloc-mig-production"),
          new HouseholdLaborCommitment(
              new LaborAllocationId("alloc-mig-production"),
              LOT,
              SOURCE,
              HouseholdActors.of(SOURCE),
              "other-free-work",
              200L,
              1L,
              LaborCommitmentKind.PRODUCTION));
    }
    EconomyData base = base(commitments);
    EconomySession session = new EconomySession(base);
    Map<LaborAllocationId, HouseholdLaborCommitment> allocationsBefore =
        new LinkedHashMap<>(session.sheet().laborCommitments());
    Map<HouseholdId, HouseholdEconomy> householdsBefore =
        new LinkedHashMap<>(session.sheet().householdEconomies());

    assertThatThrownBy(() -> ModeMigrationSettlement.apply(session, accounts(), plan, base, 10L))
        .as("%s：含 GOV_SERVICE 的 source 必须 fail-closed", label)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GOV_SERVICE")
        .hasMessageContaining("source=" + SOURCE.value())
        .hasMessageContaining("allocation=" + ALLOC.value())
        .hasMessageContaining("activity=free-work")
        .hasMessageContaining("laborMilli=600");

    assertThat(session.sheet().laborCommitments())
        .as("%s：拒绝发生在任何写入之前 ⇒ 承诺表逐值不变", label)
        .isEqualTo(allocationsBefore);
    assertThat(session.sheet().householdEconomies())
        .as("家户行（人口/劳动）也不得被投影账旁路写")
        .isEqualTo(householdsBefore);
    assertThat(session.pendingPopulationTransfers()).as("无成功 move ⇒ 无 outbox").isEmpty();
    assertThat(base.allocations().get(ALLOC).kind())
        .as("base 逐值不变（世界零变化）")
        .isEqualTo(LaborCommitmentKind.GOV_SERVICE);
  }

  /**
   * ★ 正对照：不带 GOV_SERVICE（7 参旧构造点 ⇒ PRODUCTION）的既有迁移照常发生 —— 源户留 300、目标户得 300，kind 一路带过
   * PRODUCTION（不得被拒因误伤、也不得降级/篡改）。
   */
  @Test
  void migrationStillRunsForSourceWithOnlyProductionCommitment() {
    HouseholdLaborCommitment legacyProduction =
        new HouseholdLaborCommitment(
            ALLOC, LOT, SOURCE, HouseholdActors.of(SOURCE), "free-work", 600L, 1L);
    assertThat(legacyProduction.kind()).isEqualTo(LaborCommitmentKind.PRODUCTION);
    EconomyData base = base(Map.of(ALLOC, legacyProduction));
    EconomySession session = new EconomySession(base);

    ModeMigrationSettlement.apply(session, accounts(), plan(move(TARGET, H2, 50L)), base, 10L);

    assertThat(session.pendingPopulationTransfers()).as("正路必须留 outbox").hasSize(1);
    HouseholdLaborCommitment sourceAfter = session.sheet().laborCommitments().get(ALLOC);
    assertThat(sourceAfter).as("源户保留 300").isNotNull();
    assertThat(sourceAfter.laborMilli()).isEqualTo(300L);
    assertThat(sourceAfter.kind()).as("kind 原样带过").isEqualTo(LaborCommitmentKind.PRODUCTION);
    List<HouseholdLaborCommitment> targetRows =
        session.sheet().laborCommitments().values().stream()
            .filter(commitment -> commitment.household().equals(TARGET))
            .toList();
    assertThat(targetRows).as("目标户拿到 300").hasSize(1);
    assertThat(targetRows.get(0).laborMilli()).isEqualTo(300L);
    assertThat(targetRows.get(0).kind()).isEqualTo(LaborCommitmentKind.PRODUCTION);
    assertThat(base.allocations().get(ALLOC).laborMilli()).as("base 不被就地改").isEqualTo(600L);
  }

  // ── 夹具 ──

  private static ModeMigrationPolicy.MigrationPlan plan(
      ModeMigrationPolicy.MigrationMove... moves) {
    return new ModeMigrationPolicy.MigrationPlan(List.of(moves));
  }

  private static ModeMigrationPolicy.MigrationMove move(
      HouseholdId target, HexCoord targetHex, long population) {
    return new ModeMigrationPolicy.MigrationMove(
        SOURCE,
        target,
        targetHex,
        FAMILY,
        ModeMigrationPolicy.MIGRATION_PER_MILLE,
        population,
        0L,
        0L,
        "fixture");
  }

  private static HouseholdLaborCommitment commitment(LaborCommitmentKind kind, long laborMilli) {
    return new HouseholdLaborCommitment(
        ALLOC, LOT, SOURCE, HouseholdActors.of(SOURCE), "free-work", laborMilli, 1L, kind);
  }

  private static EconomyData base(Map<LaborAllocationId, HouseholdLaborCommitment> commitments) {
    return EconomyData.empty()
        .withModes(DefaultProductionModes.modes())
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(
            Map.of(
                SOURCE, row(SOURCE, H1, 100L, 1_000L),
                TARGET, row(TARGET, H2, 0L, 0L)))
        .withClassMemberships(
            Map.of(
                SOURCE, membership(SOURCE),
                TARGET, membership(TARGET)))
        .withIndustries(Map.of(TARGET_INDUSTRY, targetIndustry()))
        .withProcesses(Map.of(TARGET_UNIT, targetProcess()))
        .withProductionEnterprises(Map.of(TARGET_ORGANIZATION, targetEnterprise()))
        .withLaborCommitments(commitments);
  }

  /** 目标户既有产业模板（只为让目标 unit 存在的合法支撑，不参与本用例的数值）。 */
  private static Industry targetIndustry() {
    return new Industry(
        TARGET_INDUSTRY,
        "target-farm",
        new RegimeId("tenant"),
        10L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 1L),
        Map.of(),
        List.of(new ClassSlot(POOR, "peasant", 1_000)),
        new AllocationRule.Split(500, 500));
  }

  private static ProductionProcess targetProcess() {
    return new ProductionProcess(
        TARGET_UNIT,
        TARGET_INDUSTRY,
        HouseholdActors.of(TARGET),
        TARGET_INDUSTRY.value(),
        0L,
        0L,
        Map.of());
  }

  private static ProductionEnterprise targetEnterprise() {
    return new ProductionEnterprise(
        TARGET_ORGANIZATION,
        FAMILY,
        POSITION,
        Optional.of(TARGET_UNIT),
        HouseholdActors.of(TARGET),
        List.of(TARGET),
        List.of(),
        List.of(),
        new Payee.ToActor(HouseholdActors.of(TARGET)),
        Optional.empty(),
        ProductionEnterprise.Status.ACTIVE,
        "");
  }

  private static HouseholdEconomy row(
      HouseholdId id, HexCoord hex, long population, long laborMilli) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, POOR),
        population,
        laborMilli,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership membership(HouseholdId id) {
    return new HouseholdClassMembership(id, POSITION, POSITION, Map.of(), 0L, 0L, "fixture");
  }

  private static AccountSession accounts() {
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(SOURCE, H1, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(TARGET, H2, Map.of(), Map.of(), Map.of(), Map.of());
    return accounts;
  }
}
