package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.World;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetCategory;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.tools.read.GovInfoTool;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>Z6a 创世/长跑真档用例（Z5 §8.1，设计书 §12.9）</b>：空 store → 真 {@code WorldRegistry.small-world} 生成器 → 真
 * {@code ShellMain.seedGenesisIfEmpty} → 真 core {@code bootstrapGenesis}；逐项钉 tick0 结构、第 1
 * 天结算读数/告警/国库、 {@code simos.gov.info} 真读、确定性/幂等与 3 天长跑 smoke。
 *
 * <p>★★ <b>批 M（2026-10-15）</b>：原「120 天长跑」用例（一个 `@Test` 里逐日推 120 tick）已按用户裁定搬出 JUnit ⇒ {@code
 * io.mosire.simos.app.sim.GovGenesis120DaySim}（跑 {@code tools/multitick-sim.sh gov-genesis-120d}）；
 * 本类留 3 天 smoke（判据 M1 合规）。
 *
 * <p>★ <b>期望值来源</b>：Z5 探针（{@code /tmp/z5-probe}，85 checks 全绿）与设计书 §3/§6/§8/§9；数字是字面量，不拿被测实现当期望
 * （唯一例外是财政/公式的复算，用被测消费端同一份数据现算——见各断言注释）。
 *
 * <p>★ <b>效率非 0 的时点</b>：tick0 的 {@code gov} 切片只落"读数位"（{@code offices} 空；政策源状态自追加裁定 12 起住 {@code
 * EconomyData.governments}，见 §21.9 D-1），当日 {@code GovOfficeState} 读数是推进第 1 天时由日循环写入的（设计书
 * §8："当日读数、不作次日输入"）。故 tick0 断言"源状态齐 + 承诺/单位/国库齐"，效率逐值断言在 tick1。
 */
class GovGenesisZ6Test {

  private static final ObjectMapper JSON = MapperHolder.MAPPER;

  private static final UnitId CENTRAL = UnitId.parse(GovWorldBootstrap.CENTRAL_GOV_ID);
  private static final UnitId PROVINCE = UnitId.parse(GovWorldBootstrap.PROVINCE_GOV_ID);

  @TempDir Path tempDir;

  // ── tick0：创世结构逐项 ─────────────────────────────────────────────────────────────

  @Test
  void genesisTick0HasGovOfficeUnitsCommitmentsTreasuryAndZeroTreasuryPopulation() {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("tick0"), "tick0")) {
      SimulationState state = world.state();
      UnitState units = GovZ6WorldFixture.unitSlice(state);
      SocialData social = GovZ6WorldFixture.socialSlice(state);
      EconomyData economy = GovZ6WorldFixture.economySlice(state);
      GovState gov = GovZ6WorldFixture.govSlice(state);
      var actor = GovZ6WorldFixture.actorSlice(state);

      assertThat(gov.offices()).as("tick0 只有读数位（offices 空；政策住 economy，当日读数是第 1 天日循环写的）").isEmpty();

      assertThat(social.households()).as("社会家户表非空").isNotEmpty();
      assertThat(totalSocialPopulation(social)).as("社会总人口守恒 = 4800").isEqualTo(4800L);
      assertThat(economy.governments()).as("world-silver + 2 个 GOV").hasSize(3);

      for (UnitId govId : List.of(CENTRAL, PROVINCE)) {
        String id = govId.value();
        Unit govUnit = units.units().get(govId);
        assertThat(govUnit).as(id + " GOV 单位存在").isNotNull();
        GovernmentFormation formation = (GovernmentFormation) govUnit.module().orElseThrow();

        HouseholdId govHousehold = GovernmentHouseholds.of(id);
        HouseholdId officialHousehold = HouseholdId.parse("hh-unit:" + id);
        assertThat(govUnit.households())
            .as(id + " Unit.households = [hh-gov, hh-unit]（保序）")
            .containsExactly(govHousehold, officialHousehold);

        Household official = social.households().get(officialHousehold);
        assertThat(official).as(id + " 官吏户在 Social 表里").isNotNull();
        assertThat(official.location())
            .as(id + " 官吏户位置 = UNIT(" + id + ")")
            .isInstanceOfSatisfying(
                HouseholdLocation.Unit.class,
                location -> assertThat(location.unitId()).isEqualTo(id));
        assertThat(official.members().values().stream().mapToLong(Long::longValue).sum())
            .as(id + " 官吏户人口 = 2")
            .isEqualTo(GovWorldBootstrap.OFFICIAL_MEMBERS_PER_GOV);

        GovernmentPostOfHousehold post =
            formation.governmentPostsOfHousehold().get(officialHousehold);
        assertThat(post).as(id + " 岗位存在").isNotNull();
        assertThat(post.role()).isEqualTo(StaffRole.POST);
        assertThat(post.tierId())
            .as(id + " 岗位档位 = 默认 tier-3（两维各 500‰）")
            .isEqualTo(GovAdministrationPlan.DEFAULT_TIER_3.tierId());
        assertThat(formation.staff().get(StaffRole.POST))
            .as(id + " stored staff = {POST:2}")
            .isEqualTo(2L);

        // gov service unit：operator = hh-gov-<id>（Z1c 身份）
        // ★★ D-032 T1：生产实例的读数改从 **production 切片**当刻现读（`EconomyData.units()` 已按 B2b/裁定六
        //   迁移删除；实例的唯一权威在 production）。判据一字未改：仍是"operator = hh-gov-<id> 的 office unit 恰一条"。
        var treasuryActor = HouseholdActors.of(govHousehold);
        List<ProductionProcess> serviceUnits =
            productionInstances(state).values().stream()
                .filter(process -> process.operator().equals(treasuryActor))
                .toList();
        assertThat(serviceUnits).as(id + " 恰一个 gov service unit").hasSize(1);
        ProductionProcess serviceUnit = serviceUnits.get(0);
        assertThat(serviceUnit.industry().value()).startsWith("office@");
        assertThat(serviceUnit.modeKey()).isEqualTo(GovWorldBootstrap.OFFICE_REGIME);

        HouseholdLaborCommitment commitment =
            economy.allocations().values().stream()
                .filter(c -> c.kind() == LaborCommitmentKind.GOV_SERVICE)
                .filter(c -> c.household().equals(officialHousehold))
                .findFirst()
                .orElseThrow();
        assertThat(commitment.laborMilli())
            .as(id + " Σ承诺 = 32000 毫小时（2 名成年男性 × 16000）")
            .isEqualTo(32_000L);
        assertThat(commitment.activity()).isEqualTo(serviceUnit.id().value());
        assertThat(commitment.actor()).isEqualTo(treasuryActor);

        HouseholdEconomy economyRow = economy.classes().get(officialHousehold);
        assertThat(economyRow).as(id + " economy 行存在").isNotNull();
        assertThat(commitment.laborMilli())
            .as(id + " 承诺 ≤ 家户劳动预算")
            .isLessThanOrEqualTo(economyRow.laborMilli());

        // ★★ 追加裁定 12（§21.9 D-1）：编制计划与预算政策住 economy 的政府记录（GovState 不再承载政策表）。
        Government registered = economy.governments().get(GovernmentIds.ofUnit(id));
        assertThat(registered).as(id + " economy 政府登记存在").isNotNull();
        GovAdministrationPlan plan = registered.administrationPlan();
        assertThat(plan).as(id + " 编制计划存在（explicit）").isNotNull();
        long expectedPlanned =
            govId.equals(CENTRAL) ? 16_000L : 32_000L; // 中央 1 人当量满覆盖 / 省 2 人当量半覆盖
        assertThat(plan.securityPlannedLaborMilli()).isEqualTo(expectedPlanned);
        assertThat(plan.paperworkPlannedLaborMilli()).isEqualTo(expectedPlanned);
        assertThat(plan.postTiers()).hasSize(3);
        assertThat(plan.securitySupplyStaticModifierPerMille()).isEqualTo(1000L);
        assertThat(plan.paperworkSupplyStaticModifierPerMille()).isEqualTo(1000L);
        assertThat(plan.securityDemandStaticModifierPerMille()).isEqualTo(1000L);
        assertThat(plan.paperworkDemandStaticModifierPerMille()).isEqualTo(1000L);
        assertThat(plan.supernumerarySqrtCoefficient()).isEqualTo(1L);

        var budget = registered.budgetPolicy();
        assertThat(budget).as(id + " 预算政策存在（explicit）").isNotNull();
        assertThat(budget.orderedCategories())
            .extracting(line -> line.category())
            .containsExactly(
                GovBudgetCategory.ADMIN_STIPEND,
                GovBudgetCategory.MILITARY_STIPEND,
                GovBudgetCategory.ADMIN_SALARY,
                GovBudgetCategory.DEBT_SERVICE,
                GovBudgetCategory.OTHER);
        assertThat(budget.officialSalaryRule().grainMilliPerCommittedHour()).isEqualTo(10L);
        assertThat(budget.officialSalaryRule().silverMilliPerCommittedHour()).isEqualTo(1L);

        assertThat(registered.treasury()).as(id + " 国库 actor").isEqualTo(treasuryActor);

        HouseholdInventory treasury = actor.accounts().get(new HouseholdAccountKey(govHousehold));
        assertThat(treasury).as(id + " 国库账户存在").isNotNull();
        assertThat(treasury.balances().getOrDefault(EconomyCommodities.GRAIN, 0L))
            .isEqualTo(GovWorldBootstrap.TREASURY_GRAIN_MILLI_PER_GOV);
        assertThat(treasury.balances().getOrDefault(EconomyCommodities.CLOTH, 0L))
            .isEqualTo(GovWorldBootstrap.TREASURY_CLOTH_MILLI_PER_GOV);
        assertThat(treasury.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L))
            .isEqualTo(GovWorldBootstrap.TREASURY_SILVER_MILLI_PER_GOV);

        // hh-gov 仍 0 人口纯财政（设计书 §0 冻结；不因创世被塞人）
        Household govSocial = social.households().get(govHousehold);
        assertThat(govSocial.members().values().stream().mapToLong(Long::longValue).sum())
            .as(id + " hh-gov Social 人口 = 0")
            .isZero();
        assertThat(economy.classes().get(govHousehold).population())
            .as(id + " hh-gov economy 行 population = 0")
            .isZero();
      }

      // 省：辖区 + 税率 100‰
      Unit province = units.units().get(PROVINCE);
      assertThat(province.jurisdiction()).isPresent();
      assertThat(
              province
                  .jurisdiction()
                  .orElseThrow()
                  .taxRatePerMilleByRegion()
                  .get(new RegionId(SmallWorld.REGION_ID)))
          .isEqualTo(100L);
    }
  }

  // ── 第 1 天：效率 / 告警 / 官俸 / 工资 逐值 ─────────────────────────────────────────

  @Test
  void firstDaySettlesEfficiencyStipendAndSalaryWithoutShortfall() {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("day1"), "day1");
        AppLogCapture log = AppLogCapture.appTime();
        // ★ W5.5 测试批：人口算式的两个读数（出生/死亡）只在 Social 的 POPULATION_SETTLE 里 ⇒ 同时采集它。
        AppLogCapture socialLog = AppLogCapture.logger(SocialLog.SETTLE_LOGGER_NAME)) {
      world.advance(0L, 1L);
      SimulationState before = world.stateAt(1L);
      SimulationState after = world.stateAt(2L);
      GovState gov = GovZ6WorldFixture.govSlice(after);
      EconomyData economy = GovZ6WorldFixture.economySlice(after);
      var actor = GovZ6WorldFixture.actorSlice(after);

      GovOfficeState central = gov.offices().get(CENTRAL);
      GovOfficeState province = gov.offices().get(PROVINCE);
      assertThat(central).as("第 1 天中央有当日读数").isNotNull();
      assertThat(province).as("第 1 天省有当日读数").isNotNull();
      assertThat(central.securityCoveragePerMille()).isEqualTo(1000L);
      assertThat(central.paperworkCoveragePerMille()).isEqualTo(1000L);
      assertThat(central.efficiencyPerMille()).as("中央总效率 = 1000‰（满覆盖）").isEqualTo(1000L);
      assertThat(central.securityEfficiencyPerMille()).isEqualTo(1000L);
      assertThat(central.paperworkEfficiencyPerMille()).isEqualTo(1000L);
      assertThat(province.securityCoveragePerMille()).as("省半覆盖 500‰").isEqualTo(500L);
      assertThat(province.paperworkCoveragePerMille()).isEqualTo(500L);
      assertThat(province.efficiencyPerMille())
          .as("省总效率 = 500 × 500 ÷ 1000 = 250‰")
          .isEqualTo(250L);
      assertThat(province.securityEfficiencyPerMille()).isEqualTo(500L);
      assertThat(province.paperworkEfficiencyPerMille()).isEqualTo(500L);

      // ADMIN_STIPEND：2 名编制 × 83 毫粮/tick = 166；布 = 2 × ⌊1000/365⌋ = 4；两腿零缺口。
      assertThat(central.lastAssessedGoods().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .as("ADMIN_STIPEND 粮评估 = 166")
          .isEqualTo(166L);
      assertThat(central.lastPaidGoods().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .isEqualTo(166L);
      assertThat(central.lastAssessedGoods().getOrDefault(EconomyCommodities.CLOTH, 0L))
          .as("ADMIN_STIPEND 布腿 = 2 × ⌊1000/365⌋ = 4")
          .isEqualTo(4L);
      assertThat(central.lastPaidGoods().getOrDefault(EconomyCommodities.CLOTH, 0L)).isEqualTo(4L);
      assertThat(central.lastShortfallGoods().getOrDefault(EconomyCommodities.GRAIN, 0L)).isZero();
      assertThat(central.lastShortfallGoods().getOrDefault(EconomyCommodities.CLOTH, 0L)).isZero();
      assertThat(province.lastShortfallGoods().getOrDefault(EconomyCommodities.GRAIN, 0L)).isZero();
      assertThat(province.lastShortfallGoods().getOrDefault(EconomyCommodities.CLOTH, 0L)).isZero();

      // GOV_ADMIN_SALARY_DAY：32000 × 10 / 1000 = 320 粮；32000 × 1 / 1000 = 32 银；零缺口（真日志事件逐值）。
      assertThat(
              log.hasInfo(
                  "GOV_ADMIN_SALARY_DAY",
                  "unit=gov-central",
                  "requestedGrain=320",
                  "requestedSilver=32",
                  "paidGrain=320",
                  "paidSilver=32",
                  "shortfallGrain=0",
                  "shortfallSilver=0"))
          .as(
              "中央工资日事件逐值 320/32 零缺口；实得 %s",
              log.messages().stream()
                  .filter(line -> line.contains("GOV_ADMIN_SALARY_DAY"))
                  .toList())
          .isTrue();
      assertThat(
              log.hasInfo(
                  "GOV_ADMIN_SALARY_DAY",
                  "unit=gov-province",
                  "requestedGrain=320",
                  "requestedSilver=32",
                  "paidGrain=320",
                  "paidSilver=32",
                  "shortfallGrain=0",
                  "shortfallSilver=0"))
          .as("省工资日事件逐值 320/32 零缺口")
          .isTrue();

      // 中央国库被官俸/工资扣减且仍为正。★ 不钉绝对余额：hh-gov-* 国库按既有经济规则参与信用/债务市场
      //   （day1 有 GOV_DEBT_ISSUE / DEFICIT_LENDING），绝对余额不是本用例的语义面；"官俸/工资零缺口"由上面的
      //   GOV_ADMIN_SALARY_DAY / GOV_ADMIN_ADVANCE_END 日志与 GovOfficeState 六表逐值钉死。
      HouseholdInventory centralTreasury =
          actor.accounts().get(new HouseholdAccountKey(GovernmentHouseholds.of(CENTRAL.value())));
      assertThat(centralTreasury.balances().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .as("中央国库粮被扣减后仍为正")
          .isBetween(1L, GovWorldBootstrap.TREASURY_GRAIN_MILLI_PER_GOV - 1L);
      assertThat(centralTreasury.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L))
          .as("中央国库银被扣减后仍为正")
          .isBetween(1L, GovWorldBootstrap.TREASURY_SILVER_MILLI_PER_GOV - 1L);

      HouseholdInventory provinceTreasury =
          actor.accounts().get(new HouseholdAccountKey(GovernmentHouseholds.of(PROVINCE.value())));
      assertThat(provinceTreasury.balances().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .as("省国库扣减后仍为正（含省税收入）")
          .isPositive();
      assertThat(provinceTreasury.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L))
          .as("省国库银扣减后仍为正")
          .isPositive();

      // 告警：省两维供给 < 计划 ⇒ ADMIN_SECURITY + ADMIN_PAPERWORK；中央无任何 ADMIN_*。
      List<HexCrisisSignal> signals = new ArrayList<>(economy.crisisSignals().values());
      assertThat(kindsAt(signals, GovWorldBootstrap.PROVINCE_AT))
          .as("省座位出现两维缺口告警")
          .contains(HexCrisisSignal.Kind.ADMIN_SECURITY, HexCrisisSignal.Kind.ADMIN_PAPERWORK);
      assertThat(kindsAt(signals, GovWorldBootstrap.CENTRAL_AT))
          .as("中央满覆盖 ⇒ 中央座位不得有任何 ADMIN_* 告警")
          .noneMatch(kind -> kind.name().startsWith("ADMIN_"));
      assertThat(signals)
          .as("无 ADMIN_PLAN_MISSING / ADMIN_SERVICE_FLOW_ZERO / ADMIN_CONTRACT（负向）")
          .noneMatch(
              signal ->
                  signal.kind() == HexCrisisSignal.Kind.ADMIN_PLAN_MISSING
                      || signal.kind() == HexCrisisSignal.Kind.ADMIN_SERVICE_FLOW_ZERO
                      || signal.kind() == HexCrisisSignal.Kind.ADMIN_CONTRACT);

      // 官俸/工资自检日志：两个 GOV 全额支付、零缺口（与逐账断言同源）。
      assertThat(
              log.hasInfo(
                  "GOV_ADMIN_ADVANCE_END",
                  "governments=2",
                  "upkeepPaidGrain=332",
                  "upkeepPaidCloth=8",
                  "upkeepShortfallTotal=0",
                  "adminSalaryPaidGrain=640",
                  "adminSalaryPaidSilver=64",
                  "adminSalaryShortfallGrain=0",
                  "adminSalaryShortfallSilver=0"))
          .as("两 GOV 官俸 332 粮/8 布 + 工资 640 粮/64 银，逐项零缺口")
          .isTrue();

      // ★★★ W5.5 测试批（2026-10-11，显式重新基线化 + 加牙）：这条断言原为裸数字 `4801`，而实测是 **4799**
      //   —— 且**改动前就已经是 4799**（判别实验见 W5.5-fix2 账本 ③-3：改前/改后 day1 的 POPULATION_SETTLE
      //   逐值同一行 `births=1 deaths=2 famineDeaths=2`；那一轮测试在 `:239` 就红了、没跑到这里，所以旧数字一直没被发现）。
      //   ⇒ 按"不许留僵死数字"的口径改成**能自证的算式**：人口' == 创世人口 + 当日出生 − 当日死亡。
      //   三个量全部是当场读到的真值（人口 = Social 家户表求和；出生/死亡 = Social 唯一结算口 POPULATION_SETTLE 的真日志）
      //   ⇒ 世界再变时这条断言会指出**是哪一项变了**，而不是只喊"数字不对"。
      SocialData socialAfter = GovZ6WorldFixture.socialSlice(after);
      long genesisPopulation = totalSocialPopulation(GovZ6WorldFixture.socialSlice(before));
      String populationSettle = theOnlyPopulationSettleLine(socialLog);
      assertThat(longFieldOf(populationSettle, "day")).as("当天那一行结算日志属于第 1 天").isEqualTo(1L);
      long day1Births = longFieldOf(populationSettle, "births");
      long day1Deaths = longFieldOf(populationSettle, "deaths");
      assertThat(genesisPopulation).as("创世人口（算式左项；与 tick0 用例同一口径）").isEqualTo(4800L);
      assertThat(day1Births).as("第 1 天出生 = 1（真日志读数）").isEqualTo(1L);
      assertThat(day1Deaths).as("第 1 天死亡 = 2（含 famineDeaths=2；真日志读数）").isEqualTo(2L);
      assertThat(totalSocialPopulation(socialAfter))
          .as(
              "人口 == 创世人口 %d + 出生 %d − 死亡 %d = %d（算式自证；原裸数字 4801 改动前即已失效）",
              genesisPopulation,
              day1Births,
              day1Deaths,
              genesisPopulation + day1Births - day1Deaths)
          .isEqualTo(genesisPopulation + day1Births - day1Deaths);
      assertThat(
              socialAfter
                  .households()
                  .get(GovernmentHouseholds.of(CENTRAL.value()))
                  .members()
                  .values()
                  .stream()
                  .mapToLong(Long::longValue)
                  .sum())
          .isZero();
      assertThat(before.meta().timestamp().tick()).isZero();
    }
  }

  // ── simos.gov.info：真读 + 视野 + 负向 ──────────────────────────────────────────────

  @Test
  void govInfoReadsVisibleGovernmentForGmAndUnavailableFieldsAreNamed() throws Exception {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("info"), "info")) {
      world.advance(0L, 1L);
      AgentTool info = world.shell().toolRegistry().find(GovInfoTool.NAME).orElseThrow();

      // GM 指定省：计划 vs 供给、效率、承诺、来源、国库、告警逐项。
      JsonNode province = call(info, Map.of("govUnitId", GovWorldBootstrap.PROVINCE_GOV_ID));
      assertThat(province.get("governmentCount").asInt()).isEqualTo(1);
      JsonNode node = province.get("governments").get(0);
      assertThat(node.get("unitId").asText()).isEqualTo(GovWorldBootstrap.PROVINCE_GOV_ID);
      assertThat(node.get("supply").get("available").asBoolean()).isTrue();
      assertThat(node.get("supply").get("securityLaborMilli").asLong()).isEqualTo(16_000L);
      assertThat(node.get("supply").get("paperworkLaborMilli").asLong()).isEqualTo(16_000L);
      assertThat(node.get("efficiency").get("available").asBoolean()).isTrue();
      assertThat(node.get("efficiency").get("efficiencyPerMille").asLong()).isEqualTo(250L);
      assertThat(node.get("efficiency").get("securityCoveragePerMille").asLong()).isEqualTo(500L);
      assertThat(node.get("efficiency").get("paperworkCoveragePerMille").asLong()).isEqualTo(500L);
      assertThat(node.get("committedLabor").get("available").asBoolean()).isTrue();
      assertThat(node.get("committedLabor").get("households").size()).isEqualTo(1);
      assertThat(node.get("administrationPlanSource").asText()).isEqualTo("explicit");
      assertThat(node.get("budgetPolicySource").asText()).isEqualTo("explicit");
      assertThat(node.get("treasury").get("available").asBoolean()).isTrue();
      List<String> alertKinds = new ArrayList<>();
      node.get("alerts").forEach(alert -> alertKinds.add(alert.get("kind").asText()));
      assertThat(alertKinds).contains("ADMIN_SECURITY", "ADMIN_PAPERWORK");
      assertThat(alertKinds)
          .as("负向：不得出现计划缺失/零流量/契约告警")
          .doesNotContain("ADMIN_PLAN_MISSING", "ADMIN_SERVICE_FLOW_ZERO", "ADMIN_CONTRACT");
      // 服务流量是同 tick 进程内投递 ⇒ 1 天推进后读得到（真值，不是 unavailable）。
      assertThat(node.get("serviceFlow").get("available").asBoolean()).isTrue();
      assertThat(node.get("serviceFlow").get("securityCommittedLaborMilli").asLong())
          .isEqualTo(16_000L);
      assertThat(node.get("serviceFlow").get("paperworkCommittedLaborMilli").asLong())
          .isEqualTo(16_000L);

      // GM 指定中央：满覆盖、无告警。
      JsonNode central =
          call(info, Map.of("govUnitId", GovWorldBootstrap.CENTRAL_GOV_ID))
              .get("governments")
              .get(0);
      assertThat(central.get("efficiency").get("efficiencyPerMille").asLong()).isEqualTo(1000L);
      assertThat(central.get("alerts").size()).isZero();

      // GM 省略 ⇒ 全部 GOV，按 id 升序（gov-central < gov-province）。
      JsonNode all = call(info, Map.of());
      assertThat(all.get("governmentCount").asInt()).isEqualTo(2);
      assertThat(all.get("governments").get(0).get("unitId").asText())
          .isEqualTo(GovWorldBootstrap.CENTRAL_GOV_ID);
      assertThat(all.get("governments").get(1).get("unitId").asText())
          .isEqualTo(GovWorldBootstrap.PROVINCE_GOV_ID);

      // GM 指定不存在 ⇒ BAD_REQUEST（不是空列表）。
      ToolResult missing = executeRaw(info, Map.of("govUnitId", "gov-missing"));
      assertThat(missing.success()).isFalse();
      assertThat(missing.code()).isEqualTo("BAD_REQUEST");
    }
  }

  // ── 确定性 / 幂等 / 空库守卫 ────────────────────────────────────────────────────────

  @Test
  void genesisIsDeterministicAndNonEmptyStoreIsNeverReinitialized() {
    // ① 同一 mapId 两次生成逐字段相等
    SimulationState first = GovZ6WorldFixture.smallWorldState("Map1");
    SimulationState second = GovZ6WorldFixture.smallWorldState("Map1");
    assertThat(first).as("SmallWorld.state 两次 equals（确定性）").isEqualTo(second);
    assertThat(WorldRegistry.require(WorldRegistry.SMALL_WORLD).genesis("Map1"))
        .as("WorldRegistry 生成器与 SmallWorld.state 同源")
        .isEqualTo(first);

    // ② 两座独立空库各自 bootstrap 后状态逐字段相等（落盘往返一起覆盖）
    try (World a = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("det-a"), "det-a");
        World b = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("det-b"), "det-b")) {
      assertThat(a.head()).isEqualTo(1L);
      assertThat(b.head()).isEqualTo(1L);
      assertThat(a.state()).as("两座独立空库状态 equals").isEqualTo(b.state());
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    // ③ 非空库第二次 bootstrapGenesis ⇒ ISE，head 不变
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("guard"), "guard")) {
      long headBefore = world.head();
      assertThatThrownBy(
              () -> world.core().bootstrapGenesis(GovZ6WorldFixture.smallWorldState("Map1")))
          .as("非空库不得被创世覆盖")
          .isInstanceOf(IllegalStateException.class);
      assertThat(world.head()).as("被拒 ⇒ head 不变").isEqualTo(headBefore);
    }
  }

  // ── 长跑 smoke（★ 120 天那条已搬出 JUnit，见独立跑法）────────────────────────────────

  /**
   * ★★ <b>批 M（2026-10-15）缩编：3 天逐日推进的 smoke</b>。
   *
   * <p>★★ <b>为什么缩</b>：用户原话（逐字，2026-10-15）：「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑， 别塞进测试里！」——
   * 原 {@code oneHundredTwentyDaysAdvanceWithoutContractFailure} 在**一个 `@Test` 里逐日推 120 天 = 120
   * tick**（判据 M1「≥60 tick = 违规」的"多次单日推进"形态；实测该类吃门禁 41.0s / 12.0%）。 整段连同**全部断言逐字**搬进 {@link
   * io.mosire.simos.app.sim.GovGenesis120DaySim} （跑 {@code tools/multitick-sim.sh
   * gov-genesis-120d}，独立进程 + 独立 store，不进 {@code clean verify}）。
   *
   * <p>★ <b>这里留什么</b>：3 天（3 tick &lt; 60；且全是**逐日**推进 ⇒ 多日段 0）跑同一批机制读数 —— 「每日恰一条 revision」（{@code
   * World#advanceDays} 的推进没被吞/被拒）、GOV_SERVICE 承诺不被静默缩、 无 ADMIN_CONTRACT 危机、三省 GOV 在位、人口与家户表仍在。 ★
   * 120 天那条真正的时间跨度靶子（跨 4 个 30 天官署关账周期）**只在独立跑法里** —— 3 天抓不到它，如实记。
   */
  @Test
  void threeDaysAdvanceWithoutContractFailureAndCommitmentsIntact() {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("short"), "short")) {
      long tick = world.advanceDays(3L);

      assertThat(tick).isEqualTo(3L);
      assertThat(world.head()).as("每日恰一条 revision ⇒ head = 4").isEqualTo(4L);

      SimulationState state = world.state();
      GovState gov = GovZ6WorldFixture.govSlice(state);
      EconomyData economy = GovZ6WorldFixture.economySlice(state);
      // C7 口径：GOV_SERVICE 承诺不因结算/死亡比例缩放被静默缩小（超预算 ⇒ fail-closed，测试会当场红）。
      for (UnitId govId : List.of(CENTRAL, PROVINCE)) {
        HouseholdId official = HouseholdId.parse("hh-unit:" + govId.value());
        long committed =
            economy.allocations().values().stream()
                .filter(c -> c.kind() == LaborCommitmentKind.GOV_SERVICE)
                .filter(c -> c.household().equals(official))
                .mapToLong(HouseholdLaborCommitment::laborMilli)
                .sum();
        assertThat(committed)
            .as(govId.value() + " 3 天后 GOV_SERVICE 承诺仍为 32000（未被静默缩）")
            .isEqualTo(32_000L);
        assertThat(gov.offices().get(govId)).isNotNull();
      }
      long socialPopulation = totalSocialPopulation(GovZ6WorldFixture.socialSlice(state));
      assertThat(socialPopulation).as("3 天后 Social 人口仍为正（每日生死正常结算）").isPositive();
      assertThat(GovZ6WorldFixture.socialSlice(state).households())
          .as("3 天后 Social 家户表仍在（未被清空）")
          .isNotEmpty();
      assertThat(economy.crisisSignals().values())
          .as("3 天推进无 ADMIN_CONTRACT 契约告警（★ 120 天那条在独立跑法里）")
          .noneMatch(signal -> signal.kind() == HexCrisisSignal.Kind.ADMIN_CONTRACT);
      assertThat(economy.governments()).hasSize(3);
    }
  }

  // ── 小件 ────────────────────────────────────────────────────────────────────────────

  /** 直接调 GM 工具并断言成功，返回 message JSON。 */
  private static JsonNode call(AgentTool tool, Map<String, Object> args) throws Exception {
    ToolResult result = executeRaw(tool, args);
    assertThat(result.success()).as("工具必须成功：%s / %s", result.code(), result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  /** 直调 GM 工具（system 权限 = GM 桶），返回未折叠的 ToolResult（负向断言 code/message 用）。 */
  private static ToolResult executeRaw(AgentTool tool, Map<String, Object> args) {
    return tool.execute(
        new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
            .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources())));
  }

  private static long totalSocialPopulation(SocialData social) {
    long total = 0L;
    for (Household household : social.households().values()) {
      for (long members : household.members().values()) {
        total += members;
      }
    }
    return total;
  }

  /**
   * ★★ D-032 T1：**生产实例**的当刻真值（跨模块测试只能经 production 切片的只读投影口读 —— 实例权威在 production）。 形态同 {@code
   * GovZ6WorldFixture} 的 {@code economySlice/unitSlice}，只是 production 侧没有共享助手。
   */
  private static Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess>
      productionInstances(SimulationState state) {
    return ((io.mosire.simos.production.ProductionSnapshot)
            state.module("production").orElseThrow())
        .state()
        .instances();
  }

  /**
   * ★ W5.5 测试批：取本轮唯一的 {@code POPULATION_SETTLE} 事件行（Social 的唯一人口结算口）。
   *
   * <p>★ 找不到 / 不止一条 ⇒ 当场抛（**不静默当 0**：读侧漏采会让"人口 == 创世 + 出生 − 死亡"退化成恒真的假绿）。
   */
  private static String theOnlyPopulationSettleLine(AppLogCapture socialLog) {
    List<String> lines =
        socialLog.messages().stream()
            // ★ 事件名后必须跟空白：同一 tick 还有 POPULATION_SETTLE_START（前缀相同）——只按子串过滤会采到 2 行。
            .filter(line -> line.contains("event=POPULATION_SETTLE "))
            .toList();
    if (lines.size() != 1) {
      throw new AssertionError(
          "第 1 天必须恰有一条 POPULATION_SETTLE 真日志（实得 " + lines.size() + " 条）：" + lines);
    }
    return lines.get(0);
  }

  /** 从事件行里取一个整数（{@code k=v} 形态，读到空白为止）；缺失/非整数 ⇒ 当场抛（★ 不静默当 0）。 */
  private static long longFieldOf(String line, String key) {
    String marker = key + "=";
    int at = line.indexOf(marker);
    if (at < 0) {
      throw new AssertionError("事件行缺少字段 " + marker + " : " + line);
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length()
        && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
      end++;
    }
    if (end == start) {
      throw new AssertionError("事件行的字段不是整数 " + marker + " : " + line);
    }
    return Long.parseLong(line.substring(start, end));
  }

  private static List<HexCrisisSignal.Kind> kindsAt(
      List<HexCrisisSignal> signals, io.mosire.simos.map.hex.HexCoord hex) {
    List<HexCrisisSignal.Kind> kinds = new ArrayList<>();
    for (HexCrisisSignal signal : signals) {
      if (signal.hex().equals(hex)) {
        kinds.add(signal.kind());
      }
    }
    return kinds;
  }

  /** 复用共享 ObjectMapper（app 的 SimosObjectMapper：JDK8 模块等配置与生产同源）。 */
  private static final class MapperHolder {
    private static final ObjectMapper MAPPER = SimosObjectMapper.create();
  }
}
