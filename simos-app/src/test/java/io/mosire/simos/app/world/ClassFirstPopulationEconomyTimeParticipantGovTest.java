package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.actor.spi.AdjustAccountsHandler;
import io.mosire.simos.app.time.ClassFirstPopulationEconomyTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovRules;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.SetGovFormationHandler;
import io.mosire.simos.unit.spi.SetJurisdictionHandler;
import io.mosire.simos.unit.spi.SetTaxRateHandler;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>阶段 11b：GOV 行政结算链并入 {@link ClassFirstPopulationEconomyTimeParticipant} 的真三国日推进集成</b>。
 *
 * <p>沿用 {@link CompactThreeNationsWorld}（真 worldgen 三国 class-first）与 Tax 测试的对照分支法：同一创世、多个 store，
 * 各经真 {@code AdvanceTime} 推进，用“纯对照”的日末 actor 当税基，按生产同一对纯函数（{@link GovDemand} + {@link
 * GovEfficiency}）与 {@code JurisdictionDailyTax} 的算式现算期望值，不用碰巧成立的硬编码数字。
 *
 * <p>六个用例（每个一个 {@code @Test}）：
 *
 * <ol>
 *   <li><b>引导回归</b>：gov 片在场但无 office 读数；单位带 {@code GovFormation} ⇒ 推进 0→1 必须成功，且推进后出现 tick=1 的
 *       {@link GovOfficeState}（六表键集/覆盖率读数逐值）；
 *   <li><b>无 GOV ⇒ 不征</b>：设了管辖（含旧 {@code administrationPerMille=1000}）与税率、但无 {@code GovFormation} ⇒
 *       actor/social/classFirst 与纯对照逐字段相同，且不建国库账（旧字段不得被回退读）；
 *   <li><b>有 GOV ⇒ 税与俸禄三侧守恒</b>：逐户税按 {@code assessed/attainable/collected} 现算，家户减少 == 税收、国库 净增 ==
 *       税收 − 俸禄 paid、office 三表（审计读数）与两侧逐值一致；
 *   <li><b>缺料 ⇒ ADMIN_SUPPLY 同 (hex,kind) 覆盖</b>：库存远小于政策定额，两天逐日结算；第二天的同键信号覆盖第一天，不累积；
 *   <li><b>覆盖不足 ⇒ ADMIN_SECURITY/ADMIN_PAPERWORK</b>：staff 不足、policy 全 0（隔离 SUPPLY），两条信号 evidence 与
 *       office 读数、供给/需求现算值逐值一致；
 *   <li><b>多天推进（5 天）</b>：每天一次 advance + replay，office 读数逐日刷新不累积、全额支付、无 ADMIN_* 信号、国库逐日按 当日 paid
 *       精确递减。
 * </ol>
 */
class ClassFirstPopulationEconomyTimeParticipantGovTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:t11b-gov-admin-test";

  /** 创世（revision 1）+ 三国 worldgen（revision 2..4）之后的 head。 */
  private static final long HEAD_AFTER_SEED = 4L;

  /** 本波选的低压税率：50‰，只推少量天数 ⇒ 不触发次日 ClassFirstActorWriteback fail-closed。 */
  private static final long TAX_RATE_PER_MILLE = 50L;

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final RegionId TAXED_REGION = CompactThreeNationsWorld.GRANARY;

  /** 与 Tax 测试一致：worldgen 根单位 = {@code <nationId>-army}。 */
  private static final UnitId ROOT_UNIT = new UnitId(TAXED_REGION.value() + "-army");

  /** 国库预置额：远超政策定额 ⇒ 俸禄必须全额支付。 */
  private static final long TREASURY_PREFUND = 10_000_000L;

  @TempDir Path tempDir;

  // ── 用例 1：引导回归（控制方刚修的生产缺陷）────────────────────────────────────────────

  @Test
  void govFormationBootstrapsOfficeAndAdvancesOnFirstDay() throws IOException {
    UnitId unitId = ROOT_UNIT;
    SimulationState configured;
    SimulationState advanced;
    GovEfficiency.Efficiency expectedEfficiency;
    Path store = Files.createDirectories(tempDir.resolve("t11b-bootstrap"));
    try (CoreSimos core = classFirstCore(store)) {
      seedClassFirstWorld(core);
      SimulationState seeded = core.replay(ref(HEAD_AFTER_SEED));
      assertChargeableUnit(seeded, unitId);
      assertThat(govOf(seeded).offices()).as("创世 gov 片存在但没有任何 office 读数（withEmptyGov）").isEmpty();

      configured = configureJurisdictionAndTax(core, unitId);
      assertThat(govOf(configured).offices()).as("只设管辖+税率不建 GOV 读数：gov 片仍空").isEmpty();

      Map<HexCoord, GovDemand.HexDemand> demand = demandOf(configured, unitId);
      long securityDemand = sumSecurity(demand);
      long paperworkDemand = sumPaperwork(demand);
      assertThat(securityDemand).as("辖区治安需求为正（否则 staff 给不给足没有判别力）").isPositive();
      assertThat(paperworkDemand).as("辖区文书需求为正（否则文书覆盖没有判别力）").isPositive();

      configured =
          setGovFormation(
              core,
              unitId,
              staff(
                  Math.addExact(Math.multiplyExact(securityDemand, 2L), 1L),
                  Math.addExact(Math.multiplyExact(paperworkDemand, 2L), 1L),
                  0L),
              0L,
              0L,
              0L,
              0L);
      assertThat(govOf(configured).offices())
          .as("SetGovFormation 只写 unit 片：推进前 gov 片仍无该单位读数（引导必须发生在 advance 内）")
          .isEmpty();

      expectedEfficiency = efficiencyOf(configured, unitId);
      assertThat(expectedEfficiency.securityCoveragePerMille())
          .as("staff 按 demand×2+1 给足 ⇒ 治安覆盖满")
          .isEqualTo(GovRules.COVERAGE_FULL_PER_MILLE);
      assertThat(expectedEfficiency.paperworkCoveragePerMille())
          .as("staff 按 demand×2+1 给足 ⇒ 文书覆盖满")
          .isEqualTo(GovRules.COVERAGE_FULL_PER_MILLE);
      assertThat(expectedEfficiency.efficiencyPerMille())
          .as("超编 ⇒ 效率 ≥1000‰（判据要求；具体值由生产 pure function 现算）")
          .isGreaterThanOrEqualTo(GovRules.COVERAGE_FULL_PER_MILLE);

      long revision = advance(core, 0L, 1L);
      advanced = core.replay(ref(revision));
    }

    GovOfficeState office = officeOf(advanced, unitId);
    assertThat(office.tick()).as("首建读数经首日结算：tick 必须推进到 1").isEqualTo(1L);
    assertThat(office.lastAssessedGoods())
        .as("评估商品表含 grain/cloth 两键")
        .containsOnlyKeys(GRAIN, CLOTH);
    assertThat(office.lastPaidGoods().keySet())
        .as("实付商品键集 == 评估商品键集")
        .isEqualTo(office.lastAssessedGoods().keySet());
    assertThat(office.lastShortfallGoods().keySet())
        .as("缺口商品键集 == 评估商品键集")
        .isEqualTo(office.lastAssessedGoods().keySet());
    assertThat(office.lastAssessedMoney()).as("评估货币表含 silver").containsOnlyKeys(SILVER);
    assertThat(office.lastPaidMoney().keySet())
        .as("实付货币键集 == 评估货币键集")
        .isEqualTo(office.lastAssessedMoney().keySet());
    assertThat(office.lastShortfallMoney().keySet())
        .as("缺口货币键集 == 评估货币键集")
        .isEqualTo(office.lastAssessedMoney().keySet());
    assertThat(office.efficiencyPerMille())
        .as("行政效率现算值 == GovDemand+GovEfficiency 的纯函数输出")
        .isEqualTo(expectedEfficiency.efficiencyPerMille());
    assertThat(office.efficiencyPerMille())
        .as("能建编制就能推进：效率 ≥1000‰")
        .isGreaterThanOrEqualTo(GovRules.COVERAGE_FULL_PER_MILLE);
    assertThat(office.lastAssessedGoods())
        .as("policy 全 0 ⇒ 粮/布评估逐值为 0（隔离俸禄）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 0L, CLOTH, 0L));
    assertThat(office.lastPaidGoods())
        .as("policy 全 0 且无国库 ⇒ 粮/布实付逐值为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 0L, CLOTH, 0L));
    assertThat(office.lastShortfallGoods())
        .as("policy 全 0 ⇒ 粮/布缺口逐值为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 0L, CLOTH, 0L));
    assertThat(office.lastAssessedMoney())
        .as("policy 全 0 ⇒ 银评估为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 0L));
    assertThat(office.lastPaidMoney())
        .as("policy 全 0 ⇒ 银实付为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 0L));
    assertThat(office.lastShortfallMoney())
        .as("policy 全 0 ⇒ 银缺口为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 0L));
  }

  // ── 用例 2：无 GOV ⇒ 不征（旧 administrationPerMille 不得回退）─────────────────────────

  @Test
  void noGovFormationMeansNoTaxEvenWithLegacyAdministrationPerMille() throws IOException {
    UnitId unitId = ROOT_UNIT;
    SimulationState control;
    SimulationState legacyConfigured;
    SimulationState advancedNoGov;
    long revisionControl;
    long revisionNoGov;
    try (CoreSimos core =
        classFirstCore(Files.createDirectories(tempDir.resolve("t11b-no-gov-control")))) {
      seedClassFirstWorld(core);
      assertChargeableUnit(core.replay(ref(HEAD_AFTER_SEED)), unitId);
      revisionControl = advance(core, 0L, 1L);
      control = core.replay(ref(revisionControl));
    }

    try (CoreSimos core =
        classFirstCore(Files.createDirectories(tempDir.resolve("t11b-no-gov-legacy")))) {
      seedClassFirstWorld(core);
      legacyConfigured = configureJurisdictionAndTax(core, unitId);
      Jurisdiction jurisdiction =
          CompactThreeNationsWorld.unitOf(legacyConfigured)
              .units()
              .get(unitId)
              .jurisdiction()
              .orElseThrow(() -> new AssertionError("配置后单位 " + unitId + " 必须有管辖"));
      assertThat(jurisdiction.administrationPerMille())
          .as("旧字段 administrationPerMille=1000 确实已设（若被回退读就会征税）")
          .isEqualTo(1000L);
      assertThat(jurisdiction.taxRatePerMilleByRegion())
          .as("税率为正且落在税区")
          .containsEntry(TAXED_REGION, TAX_RATE_PER_MILLE);
      assertThat(govOf(legacyConfigured).offices())
          .as("无 GovFormation ⇒ gov 片没有任何读数（引导集为空）")
          .isEmpty();

      revisionNoGov = advance(core, 0L, 1L);
      advancedNoGov = core.replay(ref(revisionNoGov));
    }

    assertThat(revisionNoGov - revisionControl).as("B 只比纯对照多两条辖区命令（推进次数一致）").isEqualTo(2L);
    assertThat(CompactThreeNationsWorld.actorOf(advancedNoGov))
        .as("无 GOV 读数 ⇒ 整单位不征：actor 与纯对照逐字段一致（旧 administrationPerMille=1000 不得回退）")
        .isEqualTo(CompactThreeNationsWorld.actorOf(control));
    assertThat(CompactThreeNationsWorld.socialOf(advancedNoGov))
        .as("无 GOV 读数 ⇒ social 与纯对照逐字段一致")
        .isEqualTo(CompactThreeNationsWorld.socialOf(control));
    assertThat(CompactThreeNationsWorld.economyOf(advancedNoGov).classFirst())
        .as("无 GOV 读数 ⇒ classFirst 结算轨迹与纯对照逐字段一致")
        .isEqualTo(CompactThreeNationsWorld.economyOf(control).classFirst());
    assertThat(govOf(advancedNoGov).offices()).as("无 GovFormation ⇒ 推进后 gov 片仍无读数").isEmpty();

    HexCoord at = positionAt(advancedNoGov, unitId, 1L);
    GoodsAccountKey treasuryKey =
        new GoodsAccountKey(new ActorRef(ActorKind.UNIT, unitId.value()), at);
    assertThat(CompactThreeNationsWorld.actorOf(advancedNoGov).accounts())
        .as("无 GOV ⇒ 不建该单位国库账：%s", treasuryKey)
        .doesNotContainKey(treasuryKey);
  }

  // ── 用例 3：有 GOV ⇒ 税与俸禄（家户/国库/审计三侧守恒）──────────────────────────────

  @Test
  void govFormationTaxesHouseholdsAndPaysUpkeepWithThreeSidedConservation() throws IOException {
    UnitId unitId = ROOT_UNIT;
    SimulationState baseline;
    long revisionControl;
    Path storeControl = Files.createDirectories(tempDir.resolve("t11b-tax-pay-control"));
    try (CoreSimos core = classFirstCore(storeControl)) {
      seedClassFirstWorld(core);
      assertChargeableUnit(core.replay(ref(HEAD_AFTER_SEED)), unitId);
      configureJurisdictionAndTax(core, unitId);
      revisionControl = advance(core, 0L, 1L);
      baseline = core.replay(ref(revisionControl));
    }

    SimulationState configured;
    SimulationState taxed;
    GovEfficiency.Efficiency efficiency;
    GovFormation formation;
    Path storeGov = Files.createDirectories(tempDir.resolve("t11b-tax-pay-gov"));
    try (CoreSimos core = classFirstCore(storeGov)) {
      seedClassFirstWorld(core);
      assertChargeableUnit(core.replay(ref(HEAD_AFTER_SEED)), unitId);

      configured = configureJurisdictionAndTax(core, unitId);
      HexCoord at0 = positionAt(configured, unitId, 0L);
      HexCoord at1 = positionAt(configured, unitId, 1L);
      assertThat(at1).as("单位位置在日 0/1 一致（预置国库格 == 征收格）").isEqualTo(at0);

      configured =
          fundUnitTreasury(core, unitId, at0, TREASURY_PREFUND, TREASURY_PREFUND, TREASURY_PREFUND);
      GoodsAccountKey treasuryKey =
          new GoodsAccountKey(new ActorRef(ActorKind.UNIT, unitId.value()), at0);
      GoodsAccount prefunded =
          CompactThreeNationsWorld.actorOf(configured).accounts().get(treasuryKey);
      assertThat(prefunded).as("AdjustAccounts 新建国库账：%s", treasuryKey).isNotNull();
      assertThat(prefunded.balances().getOrDefault(GRAIN, 0L))
          .as("预置国库粮")
          .isEqualTo(TREASURY_PREFUND);
      assertThat(prefunded.balances().getOrDefault(CLOTH, 0L))
          .as("预置国库布")
          .isEqualTo(TREASURY_PREFUND);
      assertThat(prefunded.money().getOrDefault(SILVER, 0L))
          .as("预置国库银")
          .isEqualTo(TREASURY_PREFUND);

      Map<HexCoord, GovDemand.HexDemand> demand = demandOf(configured, unitId);
      long securityDemand = sumSecurity(demand);
      long paperworkDemand = sumPaperwork(demand);
      assertThat(securityDemand).as("辖区治安需求为正（俸禄题外还要有正效率）").isPositive();
      assertThat(paperworkDemand).as("辖区文书需求为正").isPositive();

      configured =
          setGovFormation(
              core,
              unitId,
              staff(
                  Math.addExact(Math.multiplyExact(securityDemand, 2L), 1L),
                  Math.addExact(Math.multiplyExact(paperworkDemand, 2L), 1L),
                  0L),
              1L,
              365L,
              1L,
              0L);
      formation = govFormationOf(configured, unitId);
      efficiency = efficiencyOf(configured, unitId);
      assertThat(efficiency.securityCoveragePerMille())
          .as("staff 超编 ⇒ 治安覆盖满")
          .isEqualTo(GovRules.COVERAGE_FULL_PER_MILLE);
      assertThat(efficiency.paperworkCoveragePerMille())
          .as("staff 超编 ⇒ 文书覆盖满")
          .isEqualTo(GovRules.COVERAGE_FULL_PER_MILLE);
      assertThat(efficiency.efficiencyPerMille())
          .as("超编加成 ⇒ 效率 >1000‰（税收 attainable 会超过票面 assessed）")
          .isGreaterThan(GovRules.COVERAGE_FULL_PER_MILLE);

      long revisionGov = advance(core, 0L, 1L);
      taxed = core.replay(ref(revisionGov));
    }

    // ── 审计读数（GovOfficeState）：按生产政策公式现算当日定额 ─────────────────────────
    GovOfficeState office = officeOf(taxed, unitId);
    long totalStaff = totalStaff(formation);
    long expectedGrainNeed = totalStaff * formation.policy().grainPerStaffPerTick();
    long expectedClothNeed =
        totalStaff
            * Math.floorDiv(
                formation.policy().clothPerStaffPerCycle(), EconomyVocabulary.CLOTH_CYCLE_DAYS);
    long expectedMoneyNeed = totalStaff * formation.policy().moneyPerStaffPerTick();

    assertThat(formation.policy().grainPerStaffPerTick())
        .as("本用例 policy 粮定额 = 1/人/tick")
        .isEqualTo(1L);
    assertThat(formation.policy().clothPerStaffPerCycle())
        .as("本用例 policy 布周期额 == 周期天数（floor 折日 == 1/人）")
        .isEqualTo(EconomyVocabulary.CLOTH_CYCLE_DAYS);
    assertThat(formation.policy().moneyPerStaffPerTick())
        .as("本用例 policy 银定额 = 1/人/tick")
        .isEqualTo(1L);
    assertThat(expectedGrainNeed).as("policy 粮 1/人/tick ⇒ 评估粮 == 总编制").isEqualTo(totalStaff);
    assertThat(expectedClothNeed).as("floor(周期额/周期天数) == 1 ⇒ 评估布 == 总编制").isEqualTo(totalStaff);
    assertThat(expectedMoneyNeed).as("policy 银 1/人/tick ⇒ 评估银 == 总编制").isEqualTo(totalStaff);

    assertThat(office.lastAssessedGoods())
        .as("office 评估粮 == 总编制 × policy 每人均额")
        .containsEntry(GRAIN, expectedGrainNeed);
    assertThat(office.lastAssessedGoods())
        .as("office 评估布 == 总编制 × floor(policy 周期额 / 周期天数)")
        .containsEntry(CLOTH, expectedClothNeed);
    assertThat(office.lastAssessedMoney())
        .as("office 评估银 == 总编制 × policy 每人均额")
        .containsEntry(SILVER, expectedMoneyNeed);
    assertThat(office.lastPaidGoods())
        .as("国库预置充足 ⇒ 商品实付表逐值 == 评估表")
        .isEqualTo(office.lastAssessedGoods());
    assertThat(office.lastPaidMoney())
        .as("国库预置充足 ⇒ 货币实付表逐值 == 评估表")
        .isEqualTo(office.lastAssessedMoney());
    assertThat(office.lastShortfallGoods())
        .as("全额支付 ⇒ 商品缺口表逐值为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 0L, CLOTH, 0L));
    assertThat(office.lastShortfallMoney())
        .as("全额支付 ⇒ 货币缺口表逐值为 0")
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 0L));

    // ── 逐户税：以对照 store 的 day-1 actor 为税前基线，按生产算式现算 ────────────────
    ActorData baselineActor = CompactThreeNationsWorld.actorOf(baseline);
    ActorData taxedActor = CompactThreeNationsWorld.actorOf(taxed);
    GameMap map = CompactThreeNationsWorld.mapOf(taxed);
    Region taxedArea = map.regions().get(TAXED_REGION);
    assertThat(taxedArea).as("税区必须是真 region").isNotNull();
    HexCoord at = positionAt(taxed, unitId, 1L);
    GoodsAccountKey treasuryKey =
        new GoodsAccountKey(new ActorRef(ActorKind.UNIT, unitId.value()), at);

    Map<GoodsAccountKey, Expected> grainTax =
        expectedTax(baselineActor, taxedArea, GRAIN, efficiency.efficiencyPerMille());
    Map<GoodsAccountKey, Expected> silverTax =
        expectedTax(baselineActor, taxedArea, SILVER, efficiency.efficiencyPerMille());
    long assessedGrain = sumAssessed(grainTax);
    long assessedSilver = sumAssessed(silverTax);
    long taxGrain = sumCollected(grainTax);
    long taxSilver = sumCollected(silverTax);
    assertThat(grainTax).as("粮税基非空（否则用例什么都没验）").isNotEmpty();
    assertThat(silverTax).as("银税基非空").isNotEmpty();
    assertThat(assessedGrain).as("粮 assessed 为正").isPositive();
    assertThat(assessedSilver).as("银 assessed 为正").isPositive();
    assertThat(taxGrain).as("粮 collected 为正").isPositive();
    assertThat(taxSilver).as("银 collected 为正").isPositive();

    // 账键：基线全部账键 + 本日国库账（预置已新建，税收/俸禄只改它）。
    assertThat(baselineActor.accounts()).as("对照基线没有该国库账").doesNotContainKey(treasuryKey);
    List<GoodsAccountKey> expectedKeys = new ArrayList<>(baselineActor.accounts().keySet());
    expectedKeys.add(treasuryKey);
    assertThat(taxedActor.accounts().keySet())
        .as("GOV = 基线全部账键 + 国库账（不增不减别的账）")
        .containsExactlyInAnyOrderElementsOf(expectedKeys);

    // 逐户：after == 对照 after − 该户 collected（粮/银）；布/冻结表不动。
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : baselineActor.accounts().entrySet()) {
      GoodsAccountKey key = entry.getKey();
      GoodsAccount before = entry.getValue();
      GoodsAccount after = taxedActor.accounts().get(key);
      assertThat(after).as("税不删账键：%s", key).isNotNull();
      long grainCollected = grainTax.getOrDefault(key, Expected.ZERO).collected();
      long silverCollected = silverTax.getOrDefault(key, Expected.ZERO).collected();
      if (grainCollected == 0L && silverCollected == 0L) {
        assertThat(after).as("未征税的账逐字段原样（含其它区域/余额≤0）：%s", key).isEqualTo(before);
        continue;
      }
      assertThat(after.balances().getOrDefault(GRAIN, 0L))
          .as("逐户粮 after == 对照 − 手算 collected（key=%s）", key)
          .isEqualTo(before.balances().getOrDefault(GRAIN, 0L) - grainCollected);
      assertThat(after.money().getOrDefault(SILVER, 0L))
          .as("逐户银 after == 对照 − 手算 collected（key=%s）", key)
          .isEqualTo(before.money().getOrDefault(SILVER, 0L) - silverCollected);
      assertThat(after.balances().getOrDefault(CLOTH, 0L))
          .as("长期税不征布 ⇒ 逐户布不动（key=%s）", key)
          .isEqualTo(before.balances().getOrDefault(CLOTH, 0L));
      assertThat(after.frozenBalances())
          .as("税只从可支配扣、冻结表不动（key=%s）", key)
          .isEqualTo(before.frozenBalances());
      assertThat(after.frozenMoney())
          .as("税只从可支配扣、货币冻结表不动（key=%s）", key)
          .isEqualTo(before.frozenMoney());
    }

    // Σ家户减少 == 税收入（逐维）。
    long baselineHouseholdGrain = householdTotal(baselineActor, GRAIN);
    long baselineHouseholdSilver = householdTotal(baselineActor, SILVER);
    long taxedHouseholdGrain = householdTotal(taxedActor, GRAIN);
    long taxedHouseholdSilver = householdTotal(taxedActor, SILVER);
    assertThat(baselineHouseholdGrain - taxedHouseholdGrain)
        .as("Σ家户粮减少 == Σ逐户 collected（粮）")
        .isEqualTo(taxGrain);
    assertThat(baselineHouseholdSilver - taxedHouseholdSilver)
        .as("Σ家户银减少 == Σ逐户 collected（银）")
        .isEqualTo(taxSilver);

    // 国库净增 == 税收 − 俸禄 paid；office paid 与国库减量逐值一致。
    GoodsAccount treasury = taxedActor.accounts().get(treasuryKey);
    assertThat(treasury).as("国库账在场").isNotNull();
    long paidGrain = office.lastPaidGoods().getOrDefault(GRAIN, 0L);
    long paidCloth = office.lastPaidGoods().getOrDefault(CLOTH, 0L);
    long paidSilver = office.lastPaidMoney().getOrDefault(SILVER, 0L);
    assertThat(treasury.balances().getOrDefault(GRAIN, 0L) - TREASURY_PREFUND)
        .as("国库粮净增 == 税收粮 − 俸禄 paid 粮")
        .isEqualTo(taxGrain - paidGrain);
    assertThat(treasury.balances().getOrDefault(CLOTH, 0L) - TREASURY_PREFUND)
        .as("国库布净增 == −俸禄 paid 布（无布税）")
        .isEqualTo(-paidCloth);
    assertThat(treasury.money().getOrDefault(SILVER, 0L) - TREASURY_PREFUND)
        .as("国库银净增 == 税收银 − 俸禄 paid 银")
        .isEqualTo(taxSilver - paidSilver);
    assertThat(TREASURY_PREFUND + taxGrain - treasury.balances().getOrDefault(GRAIN, 0L))
        .as("国库粮减量逐值 == office paid 粮")
        .isEqualTo(paidGrain);
    assertThat(TREASURY_PREFUND - treasury.balances().getOrDefault(CLOTH, 0L))
        .as("国库布减量逐值 == office paid 布")
        .isEqualTo(paidCloth);
    assertThat(TREASURY_PREFUND + taxSilver - treasury.money().getOrDefault(SILVER, 0L))
        .as("国库银减量逐值 == office paid 银")
        .isEqualTo(paidSilver);

    // 世界口径守恒式的另一侧 + 全账非负。
    assertThat(taxedHouseholdGrain + taxGrain)
        .as("Σ对照家户粮 == ΣGOV家户粮 + 税粮")
        .isEqualTo(baselineHouseholdGrain);
    assertThat(taxedHouseholdSilver + taxSilver)
        .as("Σ对照家户银 == ΣGOV家户银 + 税银")
        .isEqualTo(baselineHouseholdSilver);
    for (GoodsAccount account : taxedActor.accounts().values()) {
      for (long value : account.balances().values()) {
        assertThat(value).as("余额不得为负：%s", account.key()).isNotNegative();
      }
      for (long value : account.money().values()) {
        assertThat(value).as("货币余额不得为负：%s", account.key()).isNotNegative();
      }
    }
  }

  // ── 用例 4：缺料 ⇒ ADMIN_SUPPLY + 同 (hex,kind) 覆盖 ───────────────────────────────

  @Test
  void shortageEmitsAdminSupplyAndSameHexKindOverwritesAcrossDays() throws IOException {
    UnitId unitId = ROOT_UNIT;
    long tiny = 7L;
    SimulationState configured;
    GovFormation formation;
    Path store = Files.createDirectories(tempDir.resolve("t11b-shortage"));
    try (CoreSimos core = classFirstCore(store)) {
      seedClassFirstWorld(core);
      assertChargeableUnit(core.replay(ref(HEAD_AFTER_SEED)), unitId);

      // 不设管辖/税率：本用例只考缺料，不把税混进来。
      configured =
          setGovFormation(
              core, unitId, staff(1L, 1L, 0L), 1_000_000L, 365_000_000L, 1_000_000L, 0L);
      formation = govFormationOf(configured, unitId);
      HexCoord at0 = positionAt(configured, unitId, 0L);
      configured = fundUnitTreasury(core, unitId, at0, tiny, tiny, tiny);
      assertThat(formation.policy().grainPerStaffPerTick()).as("政策粮定额远大于库存").isPositive();
      assertThat(formation.policy().clothPerStaffPerCycle()).as("政策布周期额远大于库存").isPositive();
      assertThat(formation.policy().moneyPerStaffPerTick()).as("政策银定额远大于库存").isPositive();

      long revisionDay1 = advance(core, 0L, 1L);
      SimulationState day1 = core.replay(ref(revisionDay1));
      long revisionDay2 = advance(core, 1L, 2L);
      SimulationState day2 = core.replay(ref(revisionDay2));

      verifyShortageDay(
          day1, unitId, formation, tiny, 1L, "第一天：库存只够付 %d ⇒ 评估−实付=缺口".formatted(tiny), true);
      verifyShortageDay(day2, unitId, formation, tiny, 2L, "第二天：国库已被掏空 ⇒ 实付 0、缺口 == 评估", false);
    }
  }

  /** 单日缺料断言：office 三资源 assessed/paid/shortfall + ADMIN_SUPPLY 信号（同键覆盖）逐值。 */
  private static void verifyShortageDay(
      SimulationState state,
      UnitId unitId,
      GovFormation formation,
      long day1Paid,
      long expectedDay,
      String scenario,
      boolean firstDay) {
    GovOfficeState office = officeOf(state, unitId);
    long totalStaff = totalStaff(formation);
    long expectedGrainNeed = totalStaff * formation.policy().grainPerStaffPerTick();
    long expectedClothNeed =
        totalStaff
            * Math.floorDiv(
                formation.policy().clothPerStaffPerCycle(), EconomyVocabulary.CLOTH_CYCLE_DAYS);
    long expectedMoneyNeed = totalStaff * formation.policy().moneyPerStaffPerTick();
    long paid = firstDay ? day1Paid : 0L;

    assertThat(office.tick()).as("%s：office.tick 必须等于当日", scenario).isEqualTo(expectedDay);
    assertThat(office.lastAssessedGoods())
        .as("%s：粮/布评估按政策逐值现算", scenario)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(GRAIN, expectedGrainNeed, CLOTH, expectedClothNeed));
    assertThat(office.lastAssessedMoney())
        .as("%s：银评估按政策逐值现算", scenario)
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, expectedMoneyNeed));
    assertThat(office.lastPaidGoods())
        .as("%s：粮/布实付 == min(库存, 定额)", scenario)
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, paid, CLOTH, paid));
    assertThat(office.lastPaidMoney())
        .as("%s：银实付 == min(库存, 定额)", scenario)
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, paid));
    assertThat(office.lastShortfallGoods())
        .as("%s：粮/布缺口 == 评估 − 实付", scenario)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(GRAIN, expectedGrainNeed - paid, CLOTH, expectedClothNeed - paid));
    assertThat(office.lastShortfallMoney())
        .as("%s：银缺口 == 评估 − 实付", scenario)
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, expectedMoneyNeed - paid));
    assertThat(office.lastShortfallGoods().getOrDefault(GRAIN, 0L))
        .as("%s：粮缺口必须 >0（否则缺料题不成立）", scenario)
        .isPositive();
    assertThat(paid + office.lastShortfallGoods().getOrDefault(GRAIN, 0L))
        .as("%s：paid + shortfall == assessed（粮）", scenario)
        .isEqualTo(office.lastAssessedGoods().getOrDefault(GRAIN, 0L));
    assertThat(paid + office.lastShortfallGoods().getOrDefault(CLOTH, 0L))
        .as("%s：paid + shortfall == assessed（布）", scenario)
        .isEqualTo(office.lastAssessedGoods().getOrDefault(CLOTH, 0L));
    assertThat(paid + office.lastShortfallMoney().getOrDefault(SILVER, 0L))
        .as("%s：paid + shortfall == assessed（银）", scenario)
        .isEqualTo(office.lastAssessedMoney().getOrDefault(SILVER, 0L));

    HexCoord at = positionAt(state, unitId, expectedDay);
    EconomyData economy = CompactThreeNationsWorld.economyOf(state);
    HexCrisisSignal signal = signalAt(economy, at, HexCrisisSignal.Kind.ADMIN_SUPPLY);
    assertThat(countSignals(economy, at, HexCrisisSignal.Kind.ADMIN_SUPPLY))
        .as("%s：同 (hex,kind) 恰好一条（覆盖即更新，不累积）", scenario)
        .isEqualTo(1L);
    assertThat(signal.day()).as("%s：信号 day 必须等于当日（第二天覆盖第一天）", scenario).isEqualTo(expectedDay);
    assertThat(signal.severity()).as("%s：本批 severity 一律 1", scenario).isEqualTo(1);
    assertThat(signal.hex()).as("%s：信号落在单位有效位置", scenario).isEqualTo(at);
    assertThat(signal.evidence())
        .as("%s：ADMIN_SUPPLY evidence 三键", scenario)
        .containsOnlyKeys("assessed", "paid", "shortfall");
    assertThat(signal.evidence().get("assessed"))
        .as("%s：evidence.assessed == office 三资源 assessed 合计", scenario)
        .isEqualTo(assessedTotal(office));
    assertThat(signal.evidence().get("paid"))
        .as("%s：evidence.paid == office 三资源 paid 合计", scenario)
        .isEqualTo(paidTotal(office));
    assertThat(signal.evidence().get("shortfall"))
        .as("%s：evidence.shortfall == office 三资源 shortfall 合计", scenario)
        .isEqualTo(shortfallTotal(office));
  }

  // ── 用例 5：覆盖不足 ⇒ ADMIN_SECURITY/ADMIN_PAPERWORK ──────────────────────────────

  @Test
  void insufficientCoverageEmitsAdminSecurityAndPaperworkSignals() throws IOException {
    UnitId unitId = ROOT_UNIT;
    SimulationState configured;
    SimulationState advanced;
    GovFormation formation;
    GovEfficiency.Efficiency expectedEfficiency;
    Map<HexCoord, GovDemand.HexDemand> demand;
    Path store = Files.createDirectories(tempDir.resolve("t11b-coverage"));
    try (CoreSimos core = classFirstCore(store)) {
      seedClassFirstWorld(core);
      assertChargeableUnit(core.replay(ref(HEAD_AFTER_SEED)), unitId);

      configured = configureJurisdictionAndTax(core, unitId);
      // staff 全 0（YAMEN/SCRIBE/POST 都 0）；policy 全 0 隔离 ADMIN_SUPPLY。
      configured = setGovFormation(core, unitId, staff(0L, 0L, 0L), 0L, 0L, 0L, 0L);
      formation = govFormationOf(configured, unitId);
      demand = demandOf(configured, unitId);
      expectedEfficiency = GovEfficiency.of(formation, demand);
      assertThat(sumSecurity(demand)).as("辖区治安需求为正（否则覆盖不足无从谈起）").isPositive();
      assertThat(sumPaperwork(demand)).as("辖区文书需求为正").isPositive();

      long revision = advance(core, 0L, 1L);
      advanced = core.replay(ref(revision));
    }

    GovOfficeState office = officeOf(advanced, unitId);
    long expectedSecuritySupply = formation.staff().getOrDefault(StaffRole.YAMEN, 0L);
    long expectedPaperworkSupply =
        formation.staff().getOrDefault(StaffRole.SCRIBE, 0L)
            + formation.staff().getOrDefault(StaffRole.POST, 0L);
    long expectedSecurityDemand = sumSecurity(demand);
    long expectedPaperworkDemand = sumPaperwork(demand);
    long expectedSecurityCoverage =
        Math.min(
            GovRules.COVERAGE_FULL_PER_MILLE,
            expectedSecuritySupply * GovRules.COVERAGE_FULL_PER_MILLE / expectedSecurityDemand);
    long expectedPaperworkCoverage =
        Math.min(
            GovRules.COVERAGE_FULL_PER_MILLE,
            expectedPaperworkSupply * GovRules.COVERAGE_FULL_PER_MILLE / expectedPaperworkDemand);

    assertThat(office.securityCoveragePerMille())
        .as("治安覆盖：office 读数 == GovEfficiency 纯函数输出")
        .isEqualTo(expectedEfficiency.securityCoveragePerMille());
    assertThat(office.paperworkCoveragePerMille())
        .as("文书覆盖：office 读数 == GovEfficiency 纯函数输出")
        .isEqualTo(expectedEfficiency.paperworkCoveragePerMille());
    assertThat(office.securityCoveragePerMille())
        .as("治安覆盖：office 读数 == min(1000, supply×1000/demand) 独立现算")
        .isEqualTo(expectedSecurityCoverage);
    assertThat(office.paperworkCoveragePerMille())
        .as("文书覆盖：office 读数 == min(1000, supply×1000/demand) 独立现算")
        .isEqualTo(expectedPaperworkCoverage);
    assertThat(office.securityCoveragePerMille())
        .as("staff 不足 ⇒ 治安覆盖率 <1000‰")
        .isLessThan(GovRules.COVERAGE_FULL_PER_MILLE);
    assertThat(office.paperworkCoveragePerMille())
        .as("staff 不足 ⇒ 文书覆盖率 <1000‰")
        .isLessThan(GovRules.COVERAGE_FULL_PER_MILLE);

    HexCoord at = positionAt(advanced, unitId, 1L);
    EconomyData economy = CompactThreeNationsWorld.economyOf(advanced);
    HexCrisisSignal securitySignal = signalAt(economy, at, HexCrisisSignal.Kind.ADMIN_SECURITY);
    HexCrisisSignal paperworkSignal = signalAt(economy, at, HexCrisisSignal.Kind.ADMIN_PAPERWORK);

    assertThat(countSignals(economy, at, HexCrisisSignal.Kind.ADMIN_SECURITY))
        .as("治安信号同 (hex,kind) 恰好一条")
        .isEqualTo(1L);
    assertThat(countSignals(economy, at, HexCrisisSignal.Kind.ADMIN_PAPERWORK))
        .as("文书信号同 (hex,kind) 恰好一条")
        .isEqualTo(1L);
    assertThat(securitySignal.day()).as("治安信号 day == 1").isEqualTo(1L);
    assertThat(paperworkSignal.day()).as("文书信号 day == 1").isEqualTo(1L);
    assertThat(securitySignal.severity()).as("治安信号 severity == 1").isEqualTo(1);
    assertThat(paperworkSignal.severity()).as("文书信号 severity == 1").isEqualTo(1);

    assertThat(securitySignal.evidence())
        .as("治安 evidence 三键（coveragePerMille/supply/demand）")
        .containsOnlyKeys("coveragePerMille", "supply", "demand");
    assertThat(securitySignal.evidence().get("coveragePerMille"))
        .as("治安 evidence.coveragePerMille == office 读数")
        .isEqualTo(office.securityCoveragePerMille());
    assertThat(securitySignal.evidence().get("supply"))
        .as("治安 evidence.supply == YAMEN 在编数（GovEfficiency.securitySupply 口径）")
        .isEqualTo(expectedSecuritySupply);
    assertThat(securitySignal.evidence().get("demand"))
        .as("治安 evidence.demand == Σ逐格治安需求（GovEfficiency.securityDemand 口径）")
        .isEqualTo(expectedSecurityDemand);

    assertThat(paperworkSignal.evidence())
        .as("文书 evidence 三键（coveragePerMille/supply/demand）")
        .containsOnlyKeys("coveragePerMille", "supply", "demand");
    assertThat(paperworkSignal.evidence().get("coveragePerMille"))
        .as("文书 evidence.coveragePerMille == office 读数")
        .isEqualTo(office.paperworkCoveragePerMille());
    assertThat(paperworkSignal.evidence().get("supply"))
        .as("文书 evidence.supply == SCRIBE+POST 在编数（GovEfficiency.paperworkSupply 口径）")
        .isEqualTo(expectedPaperworkSupply);
    assertThat(paperworkSignal.evidence().get("demand"))
        .as("文书 evidence.demand == Σ逐格文书需求（GovEfficiency.paperworkDemand 口径）")
        .isEqualTo(expectedPaperworkDemand);

    assertThat(economy.crisisSignals())
        .as("policy 全 0 隔离 ⇒ 不得出现 ADMIN_SUPPLY")
        .doesNotContainKey(CrisisSignalId.idOf(at, HexCrisisSignal.Kind.ADMIN_SUPPLY.name()));
  }

  // ── 用例 6：多天推进（5 天）────────────────────────────────────────────────────────

  @Test
  void multiDayAdvancesRefreshOfficeReadingsAndPayTreasuryEachDay() throws IOException {
    UnitId unitId = ROOT_UNIT;
    int days = 5;
    long budget = 1_000L;
    SimulationState configured;
    GovFormation formation;
    Path store = Files.createDirectories(tempDir.resolve("t11b-multi-day"));
    try (CoreSimos core = classFirstCore(store)) {
      seedClassFirstWorld(core);
      assertChargeableUnit(core.replay(ref(HEAD_AFTER_SEED)), unitId);

      // 不设管辖/税率：避免次日写回 fail-closed；policy 非零 + 国库充足 ⇒ 每天全额支付。
      configured = setGovFormation(core, unitId, staff(2L, 1L, 0L), 1L, 365L, 1L, 0L);
      formation = govFormationOf(configured, unitId);
      HexCoord at = positionAt(configured, unitId, 0L);
      configured = fundUnitTreasury(core, unitId, at, budget, budget, budget);
      GoodsAccountKey treasuryKey =
          new GoodsAccountKey(new ActorRef(ActorKind.UNIT, unitId.value()), at);
      assertThat(CompactThreeNationsWorld.actorOf(configured).accounts())
          .as("多天用例：国库账已预置")
          .containsKey(treasuryKey);

      long totalStaff = totalStaff(formation);
      long grainPerDay = totalStaff * formation.policy().grainPerStaffPerTick();
      long clothPerDay =
          totalStaff
              * Math.floorDiv(
                  formation.policy().clothPerStaffPerCycle(), EconomyVocabulary.CLOTH_CYCLE_DAYS);
      long moneyPerDay = totalStaff * formation.policy().moneyPerStaffPerTick();
      assertThat(grainPerDay).as("每日粮定额为正（否则多天递减断言空洞）").isPositive();
      assertThat(clothPerDay).as("每日布定额为正").isPositive();
      assertThat(moneyPerDay).as("每日银定额为正").isPositive();

      long treasuryGrain = budget;
      long treasuryCloth = budget;
      long treasurySilver = budget;
      for (long day = 0L; day < days; day++) {
        long revision = advance(core, day, day + 1L);
        SimulationState advanced = core.replay(ref(revision));
        GovOfficeState office = officeOf(advanced, unitId);
        assertThat(office.tick())
            .as("第 %d 天：office.tick 逐日刷新为当日（不累积）", day + 1L)
            .isEqualTo(day + 1L);
        assertThat(office.lastAssessedGoods())
            .as("第 %d 天：粮/布评估逐值恒定（不累积）", day + 1L)
            .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, grainPerDay, CLOTH, clothPerDay));
        assertThat(office.lastAssessedMoney())
            .as("第 %d 天：银评估逐值恒定（不累积）", day + 1L)
            .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, moneyPerDay));
        assertThat(office.lastPaidGoods())
            .as("第 %d 天：国库充足 ⇒ 商品实付 == 评估", day + 1L)
            .isEqualTo(office.lastAssessedGoods());
        assertThat(office.lastPaidMoney())
            .as("第 %d 天：国库充足 ⇒ 货币实付 == 评估", day + 1L)
            .isEqualTo(office.lastAssessedMoney());
        assertThat(office.lastShortfallGoods())
            .as("第 %d 天：商品缺口逐值为 0", day + 1L)
            .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 0L, CLOTH, 0L));
        assertThat(office.lastShortfallMoney())
            .as("第 %d 天：货币缺口逐值为 0", day + 1L)
            .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 0L));

        long paidGrain = office.lastPaidGoods().getOrDefault(GRAIN, 0L);
        long paidCloth = office.lastPaidGoods().getOrDefault(CLOTH, 0L);
        long paidSilver = office.lastPaidMoney().getOrDefault(SILVER, 0L);
        assertThat(paidGrain).as("第 %d 天：当日 paid 粮 == 政策日定额", day + 1L).isEqualTo(grainPerDay);
        assertThat(paidCloth).as("第 %d 天：当日 paid 布 == 政策日定额", day + 1L).isEqualTo(clothPerDay);
        assertThat(paidSilver).as("第 %d 天：当日 paid 银 == 政策日定额", day + 1L).isEqualTo(moneyPerDay);

        EconomyData economy = CompactThreeNationsWorld.economyOf(advanced);
        assertThat(economy.crisisSignals().values())
            .as("第 %d 天：无辖区 + 全额支付 ⇒ 不得有 ADMIN_* 信号", day + 1L)
            .allMatch(
                signal ->
                    signal.kind() != HexCrisisSignal.Kind.ADMIN_SUPPLY
                        && signal.kind() != HexCrisisSignal.Kind.ADMIN_SECURITY
                        && signal.kind() != HexCrisisSignal.Kind.ADMIN_PAPERWORK);

        treasuryGrain = Math.subtractExact(treasuryGrain, paidGrain);
        treasuryCloth = Math.subtractExact(treasuryCloth, paidCloth);
        treasurySilver = Math.subtractExact(treasurySilver, paidSilver);
        GoodsAccount treasury =
            CompactThreeNationsWorld.actorOf(advanced).accounts().get(treasuryKey);
        assertThat(treasury).as("第 %d 天：国库账仍在场", day + 1L).isNotNull();
        assertThat(treasury.balances().getOrDefault(GRAIN, 0L))
            .as("第 %d 天：国库粮 == 上一日 − 当日 paid 粮（无税收）", day + 1L)
            .isEqualTo(treasuryGrain);
        assertThat(treasury.balances().getOrDefault(CLOTH, 0L))
            .as("第 %d 天：国库布 == 上一日 − 当日 paid 布（无布收入）", day + 1L)
            .isEqualTo(treasuryCloth);
        assertThat(treasury.money().getOrDefault(SILVER, 0L))
            .as("第 %d 天：国库银 == 上一日 − 当日 paid 银（无税银收入）", day + 1L)
            .isEqualTo(treasurySilver);
      }
    }
  }

  // ── 判据小件 ───────────────────────────────────────────────────────────────

  /** 一个维度的逐户期望：{@code assessed=floor(balance×rate/1000)}、{@code collected=min(attainable, 可支配)}。 */
  private record Expected(long assessed, long collected) {

    static final Expected ZERO = new Expected(0L, 0L);
  }

  /** 商品维度：税区里每本余额 > 0 的 HOUSEHOLD 账的期望税（含 GOV 效率‰；与 {@code collect} 同算式）。 */
  private static Map<GoodsAccountKey, Expected> expectedTax(
      ActorData baseline, Region area, CommodityId commodity, long efficiencyPerMille) {
    Map<GoodsAccountKey, Expected> expected = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : baseline.accounts().entrySet()) {
      if (!isTaxableHousehold(entry.getKey(), area)) {
        continue;
      }
      GoodsAccount account = entry.getValue();
      long balance = account.balances().getOrDefault(commodity, 0L);
      if (balance <= 0L) {
        continue;
      }
      long assessed = Math.multiplyExact(balance, TAX_RATE_PER_MILLE) / 1000L;
      long attainable = Math.multiplyExact(assessed, efficiencyPerMille) / 1000L;
      long collected = Math.min(attainable, AvailableStock.available(account, commodity));
      expected.put(entry.getKey(), new Expected(assessed, collected));
    }
    return expected;
  }

  /** 货币维度：与商品同款（含 GOV 效率‰）。 */
  private static Map<GoodsAccountKey, Expected> expectedTax(
      ActorData baseline, Region area, CurrencyId currency, long efficiencyPerMille) {
    Map<GoodsAccountKey, Expected> expected = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : baseline.accounts().entrySet()) {
      if (!isTaxableHousehold(entry.getKey(), area)) {
        continue;
      }
      GoodsAccount account = entry.getValue();
      long balance = account.money().getOrDefault(currency, 0L);
      if (balance <= 0L) {
        continue;
      }
      long assessed = Math.multiplyExact(balance, TAX_RATE_PER_MILLE) / 1000L;
      long attainable = Math.multiplyExact(assessed, efficiencyPerMille) / 1000L;
      long collected = Math.min(attainable, AvailableStock.available(account, currency));
      expected.put(entry.getKey(), new Expected(assessed, collected));
    }
    return expected;
  }

  private static boolean isTaxableHousehold(GoodsAccountKey key, Region area) {
    return key.owner().kind() == ActorKind.HOUSEHOLD && area.hexes().contains(key.location());
  }

  private static long sumAssessed(Map<GoodsAccountKey, Expected> expected) {
    long total = 0L;
    for (Expected value : expected.values()) {
      total = Math.addExact(total, value.assessed());
    }
    return total;
  }

  private static long sumCollected(Map<GoodsAccountKey, Expected> expected) {
    long total = 0L;
    for (Expected value : expected.values()) {
      total = Math.addExact(total, value.collected());
    }
    return total;
  }

  private static long householdTotal(ActorData actor, CommodityId commodity) {
    long total = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : actor.accounts().entrySet()) {
      if (entry.getKey().owner().kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      total = Math.addExact(total, entry.getValue().balances().getOrDefault(commodity, 0L));
    }
    return total;
  }

  private static long householdTotal(ActorData actor, CurrencyId currency) {
    long total = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : actor.accounts().entrySet()) {
      if (entry.getKey().owner().kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      total = Math.addExact(total, entry.getValue().money().getOrDefault(currency, 0L));
    }
    return total;
  }

  private static long sumValues(Map<?, Long> values) {
    long total = 0L;
    for (long value : values.values()) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  /** ADMIN_SUPPLY evidence 的 assessed 合计：office 三资源（grain/cloth/silver）评估量之和。 */
  private static long assessedTotal(GovOfficeState office) {
    return sumValues(office.lastAssessedGoods()) + sumValues(office.lastAssessedMoney());
  }

  /** ADMIN_SUPPLY evidence 的 paid 合计。 */
  private static long paidTotal(GovOfficeState office) {
    return sumValues(office.lastPaidGoods()) + sumValues(office.lastPaidMoney());
  }

  /** ADMIN_SUPPLY evidence 的 shortfall 合计。 */
  private static long shortfallTotal(GovOfficeState office) {
    return sumValues(office.lastShortfallGoods()) + sumValues(office.lastShortfallMoney());
  }

  private static long sumSecurity(Map<HexCoord, GovDemand.HexDemand> demand) {
    long total = 0L;
    for (GovDemand.HexDemand value : demand.values()) {
      total = Math.addExact(total, value.security());
    }
    return total;
  }

  private static long sumPaperwork(Map<HexCoord, GovDemand.HexDemand> demand) {
    long total = 0L;
    for (GovDemand.HexDemand value : demand.values()) {
      total = Math.addExact(total, value.paperwork());
    }
    return total;
  }

  private static long countSignals(EconomyData economy, HexCoord hex, HexCrisisSignal.Kind kind) {
    long count = 0L;
    for (HexCrisisSignal signal : economy.crisisSignals().values()) {
      if (signal.hex().equals(hex) && signal.kind() == kind) {
        count++;
      }
    }
    return count;
  }

  private static HexCrisisSignal signalAt(
      EconomyData economy, HexCoord hex, HexCrisisSignal.Kind kind) {
    CrisisSignalId id = CrisisSignalId.idOf(hex, kind.name());
    HexCrisisSignal signal = economy.crisisSignals().get(id);
    assertThat(signal)
        .as("economy.crisisSignals 里必须有 (hex=%s, kind=%s) 的信号", hex, kind)
        .isNotNull();
    assertThat(signal.id()).as("信号键必须由 (hex,kind) 确定性派生").isEqualTo(id);
    return signal;
  }

  /** 既有根单位必须真有有效位置、且位置落在税区；税区里至少有一本家户账（否则税基为空 ⇒ 假绿）。 */
  private static void assertChargeableUnit(SimulationState seeded, UnitId unitId) {
    UnitState units = CompactThreeNationsWorld.unitOf(seeded);
    GameMap map = CompactThreeNationsWorld.mapOf(seeded);
    Unit unit = units.units().get(unitId);
    assertThat(unit)
        .as("三国 worldgen 的根单位 %s 存在（后缀 -army 与 WorldgenInitializeTool 一致）", unitId)
        .isNotNull();
    HexCoord at =
        units
            .effectivePosition(unitId, SimosTimestamp.of(0L))
            .orElseThrow(() -> new AssertionError("根单位 " + unitId + " 应有有效位置"));
    Region area = map.regions().get(TAXED_REGION);
    assertThat(area).as("地图里有税区 %s", TAXED_REGION).isNotNull();
    assertThat(area.hexes()).as("根单位驻地 %s 落在税区内", at).contains(at);
    long householdAccounts =
        CompactThreeNationsWorld.actorOf(seeded).accounts().keySet().stream()
            .filter(key -> isTaxableHousehold(key, area))
            .count();
    assertThat(householdAccounts).as("税区里至少一本 HOUSEHOLD 账").isPositive();
  }

  private static HexCoord positionAt(SimulationState state, UnitId unitId, long tick) {
    return CompactThreeNationsWorld.unitOf(state)
        .effectivePosition(unitId, SimosTimestamp.of(tick))
        .orElseThrow(() -> new AssertionError("单位 " + unitId + " 在 tick " + tick + " 必须有有效位置"));
  }

  private static GovState govOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module("gov")
            .orElseThrow(() -> new AssertionError("状态里必须有 gov 切片（本测试的 core 注册了 GovCodec）"));
    return ((GovSnapshot) snapshot).state();
  }

  private static GovOfficeState officeOf(SimulationState state, UnitId unitId) {
    GovOfficeState office = govOf(state).offices().get(unitId);
    assertThat(office).as("gov 片里必须有单位 %s 的行政读数", unitId.value()).isNotNull();
    return office;
  }

  private static GovFormation govFormationOf(SimulationState state, UnitId unitId) {
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(unitId);
    assertThat(unit).as("单位 %s 必须存在", unitId.value()).isNotNull();
    Object module =
        unit.module().orElseThrow(() -> new AssertionError("单位 " + unitId.value() + " 没有编制模块"));
    assertThat(module)
        .as("单位 %s 的编制必须是 GovFormation", unitId.value())
        .isInstanceOf(GovFormation.class);
    return (GovFormation) module;
  }

  private static long totalStaff(GovFormation formation) {
    long total = 0L;
    for (long count : formation.staff().values()) {
      total = Math.addExact(total, count);
    }
    return total;
  }

  private static Map<StaffRole, Long> staff(long yamen, long scribe, long post) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    staff.put(StaffRole.YAMEN, yamen);
    staff.put(StaffRole.SCRIBE, scribe);
    staff.put(StaffRole.POST, post);
    return staff;
  }

  // ── 装配与命令 ─────────────────────────────────────────────────────────────

  /**
   * 与 Tax 测试的 {@code classFirstCore} 同源，另注册 {@link AdjustAccountsHandler}（本测试给单位国库预置
   * grain/cloth/silver）。
   */
  private static CoreSimos classFirstCore(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (ModuleCodec codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec(),
            new ActorCodec(),
            // ★ 阶段 11b：GOV 切片；经真 AdvanceTime 走 GovFormation 首建读数引导。
            new GovCodec())) {
      core.register(codec);
    }
    for (CommandHandler handler :
        List.of(
            new SetPopulationHandler(),
            new CreateCityHandler(),
            new SeedGroupsHandler(),
            new EconomySeedHandler(),
            new ActorSeedHandler(),
            new UpdateRegionHandler(),
            new CreateNationHandler(),
            new CreateUnitHandler(),
            new CreateCommandChainHandler(),
            new CreateArmyHandler(),
            // ★ 本测试自己的 core 构建器里注册（生产注册不动）。
            new SetJurisdictionHandler(),
            new SetTaxRateHandler(),
            new SetGovFormationHandler(),
            new AdjustAccountsHandler())) {
      core.register(handler);
    }
    core.register(new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID));
    return core;
  }

  /**
   * 生产 {@code RichWorld.state()} 当前没有 gov 切片；本用例显式补空片，使 {@code unit.SetGovFormation} 之后的真 {@code
   * AdvanceTime} 的“base 必须有该 namespace”模块校验能落地（与 Tax 测试同法）。
   */
  private static SimulationState withEmptyGov(SimulationState base) {
    Map<String, Snapshot> modules = new LinkedHashMap<>(base.modules());
    modules.put(
        "gov", new GovSnapshot(base.meta().ref(), base.meta().timestamp(), GovState.empty()));
    return new SimulationState(base.meta(), modules, base.info());
  }

  private static void seedClassFirstWorld(CoreSimos core) throws IOException {
    core.bootstrapGenesis(
        withEmptyGov(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID)));
    CompactThreeNationsWorld.initializeNations(core, EconomySeeder.FoundationProfile.CLASS_FIRST);
  }

  /** 发辖区域 + 税率两条命令，返回配置后的状态（不硬编码 revision，head 由 core 现读）。 */
  private static SimulationState configureJurisdictionAndTax(CoreSimos core, UnitId unitId) {
    long revision =
        submit(
            core, "cmd-t11b-set-jurisdiction", "unit.SetJurisdiction", jurisdictionPayload(unitId));
    revision = submit(core, "cmd-t11b-set-tax-rate", "unit.SetTaxRate", taxRatePayload(unitId));
    return core.replay(ref(revision));
  }

  private static SimulationState setGovFormation(
      CoreSimos core,
      UnitId unitId,
      Map<StaffRole, Long> staff,
      long grainPerStaffPerTick,
      long clothPerStaffPerCycle,
      long moneyPerStaffPerTick,
      long retirementPerStaff) {
    long revision =
        submit(
            core,
            "cmd-t11b-set-gov-formation",
            "unit.SetGovFormation",
            govFormationPayload(
                unitId,
                staff,
                grainPerStaffPerTick,
                clothPerStaffPerCycle,
                moneyPerStaffPerTick,
                retirementPerStaff));
    return core.replay(ref(revision));
  }

  private static SimulationState fundUnitTreasury(
      CoreSimos core, UnitId unitId, HexCoord at, long grain, long cloth, long silver) {
    long revision =
        submit(
            core,
            "cmd-t11b-adjust-accounts",
            "actor.AdjustAccounts",
            adjustAccountsPayload(unitId, at, grain, cloth, silver));
    return core.replay(ref(revision));
  }

  /** 该单位当日生效的 GOV 效率（与生产 participant 同一对纯函数：以单位当前 jurisdiction 算 demand）。 */
  private static GovEfficiency.Efficiency efficiencyOf(SimulationState state, UnitId unitId) {
    return GovEfficiency.of(govFormationOf(state, unitId), demandOf(state, unitId));
  }

  /** 单位辖区（jurisdiction 的 key 集）的逐格行政需求（与生产 GovDemand 同一纯函数）。 */
  private static Map<HexCoord, GovDemand.HexDemand> demandOf(SimulationState state, UnitId unitId) {
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(unitId);
    assertThat(unit).as("算行政需求的单位 %s 必须存在", unitId).isNotNull();
    return GovDemand.of(
        CompactThreeNationsWorld.mapOf(state), CompactThreeNationsWorld.socialOf(state), unit);
  }

  private static String jurisdictionPayload(UnitId unitId) {
    return "{\"unitId\":\""
        + unitId.value()
        + "\",\"regions\":[\""
        + TAXED_REGION.value()
        + "\"],\"levyGrainCapPerCommand\":1000,\"levyMoneyCapPerCommand\":1000,"
        + "\"levyManpowerCapPerCommand\":1000,\"administrationPerMille\":1000}";
  }

  private static String taxRatePayload(UnitId unitId) {
    return "{\"unitId\":\""
        + unitId.value()
        + "\",\"regionId\":\""
        + TAXED_REGION.value()
        + "\",\"ratePerMille\":"
        + TAX_RATE_PER_MILLE
        + "}";
  }

  private static String govFormationPayload(
      UnitId unitId,
      Map<StaffRole, Long> staff,
      long grainPerStaffPerTick,
      long clothPerStaffPerCycle,
      long moneyPerStaffPerTick,
      long retirementPerStaff) {
    StringBuilder json = new StringBuilder();
    json.append("{\"unitId\":\"")
        .append(unitId.value())
        .append("\",\"level\":\"CENTRAL\",\"staff\":{");
    boolean first = true;
    for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(entry.getKey().name()).append("\":").append(entry.getValue());
    }
    json.append("},\"policy\":{\"grainPerStaffPerTick\":")
        .append(grainPerStaffPerTick)
        .append(",\"clothPerStaffPerCycle\":")
        .append(clothPerStaffPerCycle)
        .append(",\"moneyPerStaffPerTick\":")
        .append(moneyPerStaffPerTick)
        .append(",\"retirementPerStaff\":")
        .append(retirementPerStaff)
        .append("}}");
    return json.toString();
  }

  private static String adjustAccountsPayload(
      UnitId unitId, HexCoord at, long grain, long cloth, long silver) {
    return "{\"entries\":[{\"owner\":{\"kind\":\""
        + ActorKind.UNIT.name()
        + "\",\"id\":\""
        + unitId.value()
        + "\"},\"q\":"
        + at.q()
        + ",\"r\":"
        + at.r()
        + ",\"goods\":{\""
        + PilotModel.GRAIN
        + "\":"
        + grain
        + ",\""
        + PilotModel.CLOTH
        + "\":"
        + cloth
        + "},\"money\":{\""
        + SILVER.value()
        + "\":"
        + silver
        + "}}]}";
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  private static long submit(CoreSimos core, String commandId, String type, String payloadJson) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new CommandEnvelope(
                commandId, commandId, INITIATOR, MAIN, new RevisionId(head), type, payloadJson));
    assertThat(result)
        .as("%s 提交成功", type)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1L))));
    return head + 1L;
  }

  /** 一次真 {@code AdvanceTime}（一条 revision，内部由参与者逐日跑）；返回新 revision。 */
  private static long advance(CoreSimos core, long from, long to) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-t11b-advance-" + from + "-" + to,
                "corr-t11b-advance-" + from + "-" + to,
                INITIATOR,
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进 %d → %d 天成功（fail-closed 未触发）", from, to)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1L))));
    return head + 1L;
  }
}
