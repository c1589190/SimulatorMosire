package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * P10.3 正式运行时 7 hex 全链路 3650 tick 验收（控制方派单）。
 *
 * <p>与本包 {@code EconomyFixtures} 同包，直接用它的旧 17 参兼容位：{@code EconomyData} 构造期把
 * {@code Industry.capacity/operator} 归一化成 {@code ProductionUnit} + {@code AssetShare}（与
 * {@code EconomyTestWorld} 同路）。
 */
class SevenHexFullChain3650Test {

  private static final String MAP_ID = "seven-hex-full-chain";
  private static final long CYCLE_DAYS = 120L;
  private static final long LABOR_MILLI_PER_PERSON = 580L;
  private static final long START_PERIOD = 1L;

  private static final CommodityId GRAIN =
      new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId FIBER =
      new CommodityId(EconomyVocabulary.FIBER_COMMODITY_ID);
  private static final CommodityId CLOTH =
      new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);
  private static final CommodityId TOOL = new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final HexCoord C = new HexCoord(0, 0);
  private static final HexCoord R0 = new HexCoord(1, 0);
  private static final HexCoord R1 = new HexCoord(1, -1);
  private static final HexCoord R2 = new HexCoord(0, -1);
  private static final HexCoord R3 = new HexCoord(-1, 0);
  private static final HexCoord R4 = new HexCoord(-1, 1);
  private static final HexCoord R5 = new HexCoord(0, 1);

  private static final List<HexCoord> HEXES = List.of(C, R0, R1, R2, R3, R4, R5);

  private static final String POOR = "poor_peasant";
  private static final String MIDDLE = "middle_peasant";
  private static final String RICH = "rich_peasant";
  private static final String LANDLORD = "landlord";
  private static final String MERCHANT = "official";
  private static final String PORTER = "landless_laborer";
  private static final String ARTISAN = "artisan";
  private static final String OWNER = "official";
  private static final String DISPLACED = "landless_laborer";

  @Test
  void sevenHexFullChain3650() {
    // ── 阶段一：真实生产/市场/商号的 3650 tick 全链路读数（无迁移层，先证明主链路真跑）。──────
    World productionOnly = buildWorld(false, false, false);
    assertThat(productionOnly.initial().markets()).as("7 格市场").hasSize(7);
    assertThat(productionOnly.initial().industries().keySet())
        .as("每农村格都有 farm@hex")
        .contains(
            IndustryHexKeys.id("farm", R0.q(), R0.r()),
            IndustryHexKeys.id("farm", R1.q(), R1.r()),
            IndustryHexKeys.id("farm", R2.q(), R2.r()),
            IndustryHexKeys.id("farm", R3.q(), R3.r()),
            IndustryHexKeys.id("farm", R4.q(), R4.r()),
            IndustryHexKeys.id("farm", R5.q(), R5.r()));
    assertThat(productionOnly.initial().industries().keySet())
        .as("至少两格有 weave@hex、城市有 craft/trade")
        .contains(
            IndustryHexKeys.id("weave", R1.q(), R1.r()),
            IndustryHexKeys.id("weave", R3.q(), R3.r()),
            IndustryHexKeys.id("craft", C.q(), C.r()),
            IndustryHexKeys.id("trade", C.q(), C.r()));
    assertThat(productionOnly.initial().modes().keySet())
        .as("DefaultProductionModes 全目录")
        .containsExactlyInAnyOrderElementsOf(DefaultProductionModes.modes().keySet());
    assertThat(productionOnly.initialModes().values())
        .as("初始有 DISPLACED 户")
        .contains(DefaultProductionModes.DISPLACED);
    RunResult production = run(productionOnly, 3650, true);
    System.out.println("[7HEX-FULL] production-only milestones:");
    for (String line : production.milestones()) {
      System.out.println(line);
    }
    System.out.println("[7HEX-FULL] d022=" + production.d022Violations());
    RunResult repeated = run(productionOnly, 3650, false);
    assertThat(stateFingerprint(repeated.data()))
        .as("确定性：同输入两次 3650 tick 终态逐字段相同")
        .isEqualTo(stateFingerprint(production.data()));
    assertThat(accountFingerprint(repeated.accounts()))
        .as("确定性：同输入两次 3650 tick 账户余额逐字段相同")
        .isEqualTo(accountFingerprint(production.accounts()));

    assertThat(productionOnly.topology().regional())
        .as("MarketTopology.regional() 必须是 true（7 格显式节点 + 半径）")
        .isTrue();
    assertThat(production.outputHexes())
        .as("7 格都有产业关账（含 C 的 craft 与六个农村格）")
        .contains(
            IndustryHexKeys.hexKey(C.q(), C.r()),
            IndustryHexKeys.hexKey(R0.q(), R0.r()),
            IndustryHexKeys.hexKey(R1.q(), R1.r()),
            IndustryHexKeys.hexKey(R2.q(), R2.r()),
            IndustryHexKeys.hexKey(R3.q(), R3.r()),
            IndustryHexKeys.hexKey(R4.q(), R4.r()),
            IndustryHexKeys.hexKey(R5.q(), R5.r()));
    assertThat(production.milestones())
        .as("必须给出 tick=0/120/1200/3650 四个里程碑")
        .anyMatch(line -> line.startsWith("[7HEX-FULL] tick=0 "))
        .anyMatch(line -> line.startsWith("[7HEX-FULL] tick=120 "))
        .anyMatch(line -> line.startsWith("[7HEX-FULL] tick=1200 "))
        .anyMatch(line -> line.startsWith("[7HEX-FULL] tick=3650 "));
    assertThat(production.grainProducedMilli()).as("GRAIN 真实产出").isPositive();
    assertThat(production.fiberProducedMilli()).as("FIBER 真实产出").isPositive();
    assertThat(production.clothProducedMilli()).as("CLOTH 真实产出").isPositive();
    assertThat(production.toolProducedMilli()).as("TOOL 真实产出").isPositive();
    assertThat(production.marketFills()).as("市场成交笔数").isPositive();
    assertThat(production.immediateFills()).as("区内成交笔数").isPositive();
    assertThat(production.crossRegionFills()).as("跨格在途成交笔数").isPositive();
    assertThat(production.freightPaid()).as("承运商实收运费").isPositive();
    assertThat(production.freightUncollected())
        .as("运费未收读数必须非负；=0 表示全部承运，>0 表示商号容量不足（读数不消失）")
        .isNotNegative();
    assertThat(production.merchantFee()).as("商号上一周期运费实收").isPositive();
    assertThat(production.merchantUpkeep()).as("商号 upkeep 进入真实成本").isPositive();
    assertThat(production.merchantProfit()).as("商号 net（真实 ledger）非零").isNotZero();
    assertThat(production.d022Violations()).as("无迁移阶段不应出现 D-022 违例").isEmpty();
    assertThat(totalPopulation(production.data())).isEqualTo(productionOnly.initialPopulation());
    assertThat(totalAccountMoney(production.accounts())).isEqualTo(productionOnly.initialMoney());
    assertThat(totalDebt(production.data())).isGreaterThan(totalDebt(productionOnly.initial()));
    assertNoNegativeBalances(production.data(), production.accounts());

    // ── 阶段二：迁移验收（正式 7hex + ClassStanding/Organization/merchantFirms）。────────────
    // 先跑包含“新建目标 mode 家户”的配置：预期新建/合并/消亡同时发生。
    // 实测缺陷①：ModeMigrationSettlement.applySource 在新建目标时先 createNewHousehold，
    // 但随后的 targetRow 仍是迁移前捕获的 null，line 244 直接 NPE。让异常冒出来作为本测试的失败证据。
    World withCreation = buildWorld(true, true, false);
    RunResult migration = run(withCreation, 3650, true);
    System.out.println("[7HEX-FULL] migration milestones:");
    for (String line : migration.milestones()) {
      System.out.println(line);
    }
    assertThat(migration.merges()).as("正式迁移：至少一次合并已有目标户").isPositive();
    assertThat(migration.creations()).as("正式迁移：至少一次新建目标 mode 家户").isPositive();
    assertThat(migration.extinctions()).as("正式迁移：至少一次源户人口归零消亡").isPositive();
    assertThat(migration.maxSpeedTransfers())
        .as("A 规则源户以 1000‰ 速度迁出并消亡")
        .isPositive();
    assertThat(migration.displacedLastClose())
        .as("流民终局低于峰值")
        .isLessThan(migration.displacedPeak());
    assertThat(migration.d022Violations())
        .as("D-022：所有存活家户迁移前后 mode/standing/org/unit.modeKey 不变")
        .isEmpty();
    assertThat(totalPopulation(migration.data())).isEqualTo(withCreation.initialPopulation());
    assertThat(totalAccountMoney(migration.accounts())).isEqualTo(withCreation.initialMoney());
    assertNoNegativeBalances(migration.data(), migration.accounts());
  }

  @Test
  void migrationFlowRowDefectDiagnostic() {
    World world = buildWorld(false, true, false);
    EconomyData base = world.initial();
    AccountSession accounts = loadAccounts(base, world.goods(), world.money());
    EconomySession session = new EconomySession(base);
    HouseholdId source = world.aRuleSourceCandidate();
    HouseholdId target = hid("hh-r4-profit");
    session
        .flows()
        .put(
            source,
            new FlowRow(
                source,
                Map.of(),
                Map.of(),
                0L,
                0L,
                0L,
                0L,
                0L,
                Map.of(),
                0L,
                0L,
                Map.of(),
                Map.of()));
    ModeMigrationPolicy.MigrationMove move =
        new ModeMigrationPolicy.MigrationMove(
            source,
            target,
            R4,
            DefaultProductionModes.TENANCY_FIXED_KIND,
            ModeMigrationPolicy.A_RULE_TRANSFER_SPEED_PER_MILLE,
            150L,
            0L,
            0L,
            ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED);
    ModeMigrationSettlement.apply(
        session, accounts, new ModeMigrationPolicy.MigrationPlan(List.of(move)), base, 120L);
    System.out.println("[7HEX-FULL][DEFECT-2] source removed="
        + !session.sheet().rows().containsKey(source)
        + " flowStillExists="
        + session.flows().containsKey(source));
    // 期望：源户消亡后 FlowRow 随 classes 一起移除，preview 成功。
    // 实测：这里抛 EconomyData 守卫“flows 的键必须是已存在的家户”，复现缺陷②。
    session.preview();
  }

  @Test
  void organizationProfitBookDiagnostic() {
    World world = buildWorld(false, false, false);
    OrganizationProfitBook.Book book = collectFirstCycleBook(world);
    assertThat(book.byOrganization()).isNotEmpty();
    assertThat(book.byOrganization().values())
        .as("OrganizationProfitBook 至少一个组织 net 非零")
        .anyMatch(profit -> profit.netMilli() != 0L);
    assertThat(book.byOrganization().values())
        .as("利润金额来自真实 ledger（revenue/cost 至少一项非零）")
        .anyMatch(
            profit -> profit.revenueMilli() != 0L || profit.costPaidMilli() != 0L);
    System.out.println("[7HEX-FULL][PROFIT-BOOK] " + book.byOrganization());
  }

  private static OrganizationProfitBook.Book collectFirstCycleBook(World world) {
    EconomyData base = world.initial();
    AccountSession accounts = loadAccounts(base, world.goods(), world.money());
    EconomySession session = new EconomySession(base);
    OrganizationProfitBook.CycleAccumulator cycle = new OrganizationProfitBook.CycleAccumulator();
    for (long day = 1L; day <= 119L; day++) {
      ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
      EconomySettlement.settleOneDayInto(
          session,
          day,
          accounts,
          world.topology(),
          true,
          0,
          ledger,
          EconomyParallelism.singleThreaded(),
          null);
      cycle.recordDay(day, ledger.toLedger(), null);
    }
    List<OrganizationProfitBook.CloseFact> facts = new ArrayList<>();
    for (ProductionUnit unit : session.sheet().units().values()) {
      Industry unitIndustry = session.sheet().industries().get(unit.industry());
      if (unitIndustry != null && unit.progressDays() + 1L >= unitIndustry.cycleDays()) {
        HexCoord hex = EconomySettlement.hexOfIndustry(unit.industry());
        long labor = unit.cycleLaborMilli();
        for (LaborAllocation allocation : session.sheet().allocations().values()) {
          if (allocation.activity().equals(unit.id().value())) {
            labor += allocation.laborMilli();
          }
        }
        facts.add(
            new OrganizationProfitBook.CloseFact(
                unit.id(),
                unit.industry(),
                unit.operator(),
                hex,
                unit.modeKey(),
                labor,
                unit.cycleInputUsedMilli()));
      }
    }
    cycle.recordCloseFacts(120L, facts);
    ProductionLedger.Accumulator closing = new ProductionLedger.Accumulator(120L);
    EconomySettlement.settleOneDayInto(
        session,
        120L,
        accounts,
        world.topology(),
        true,
        0,
        closing,
        EconomyParallelism.singleThreaded(),
        null);
    cycle.recordDay(120L, closing.toLedger(), null);
    return OrganizationProfitBook.collect(
        cycle,
        session.sheet().productionOrganizations(),
        session.sheet().units(),
        session.sheet().rows(),
        session.sheet().industries(),
        session.sheet().markets(),
        accounts);
  }

  @Test
  void baselineMigrationDiagnostic() {
    World world = buildWorld(true, true, true);
    RunResult result = run(world, 120, true);
    System.out.println(
        "[7HEX-FULL][BASELINE-MIGRATION] merges="
            + result.merges()
            + " creations="
            + result.creations()
            + " extinctions="
            + result.extinctions()
            + " sourcePop="
            + result.data().classes().get(world.aRuleSourceCandidate()).population()
            + " targetPop="
            + result.data().classes().get(hid("hh-r4-profit")).population()
            + " displaced="
            + result.displacedLastClose()
            + " displacedPeak="
            + result.displacedPeak()
            + " targetModePop="
            + modePopulation(world.initial(), DefaultProductionModes.TENANCY_FIXED_KIND, world)
            + "->"
            + modePopulation(result.data(), DefaultProductionModes.TENANCY_FIXED_KIND, world)
            + " anchorDebt="
            + world.initial().debtContracts().get(world.anchorDebtId()).principal()
            + "->"
            + result.data().debtContracts().get(world.anchorDebtId()).principal()
            + " d022="
            + result.d022Violations());

    Map<HouseholdId, Long> beforePopulation = populationMap(world.initial());
    Map<HouseholdId, Long> afterPopulation = populationMap(result.data());
    for (HouseholdId id : beforePopulation.keySet()) {
      long before = beforePopulation.get(id);
      long after = afterPopulation.getOrDefault(id, 0L);
      if (before != after) {
        System.out.println(
            "[7HEX-FULL][BASELINE-MIGRATION][POP] " + id + " " + before + " -> " + after);
      }
    }
    assertThat(result.d022Violations())
        .as("D-022：基线迁移前后，存活家户 mode/standing/org/unit.modeKey 逐值不变")
        .isEmpty();
    assertThat(result.merges()).as("至少一次合并到已有目标户").isPositive();
    assertThat(result.creations()).as("本诊断不应新建目标户").isZero();
    assertThat(result.extinctions()).as("本诊断源户人口未清零").isZero();
    assertThat(result.displacedLastClose())
        .as("流民被迁出：终局低于峰值")
        .isLessThan(result.displacedPeak());
    assertThat(result.data().classes()).containsKey(world.aRuleSourceCandidate());
    assertThat(result.data().classes().get(world.aRuleSourceCandidate()).population())
        .as("A 规则被抑制后只走 10‰ 基线迁出")
        .isLessThan(150L)
        .isGreaterThan(0L);
    ClassStanding sourceStanding = result.data().classStandings().get(world.aRuleSourceCandidate());
    ClassPosition sourcePosition =
        result.data().classPositions().get(sourceStanding.currentPositionId());
    assertThat(sourcePosition.modeId()).isEqualTo(DefaultProductionModes.WAGE_FARM);
    assertThat(result.data().classes().get(hid("hh-r4-profit")).population()).isGreaterThan(1L);
    assertThat(modePopulation(result.data(), DefaultProductionModes.TENANCY_FIXED_KIND, world))
        .as("高真实利润 mode（tenancy_fixed_kind）人口上升")
        .isGreaterThan(
            modePopulation(world.initial(), DefaultProductionModes.TENANCY_FIXED_KIND, world));
    assertThat(totalPopulation(result.data())).isEqualTo(world.initialPopulation());
    assertThat(totalAccountMoney(result.accounts())).isEqualTo(world.initialMoney());
    assertThat(
            result
                .data()
                .debtContracts()
                .get(world.anchorDebtId())
                .principal())
        .as("下一周期利息并入本金 ⇒ 债务增加（不因迁移而消失）")
        .isGreaterThan(
            world.initial().debtContracts().get(world.anchorDebtId()).principal());
    assertNoNegativeBalances(result.data(), result.accounts());
  }

  @Test
  void aRulePolicyDiagnostic() {
    World world = buildWorld(false, true, false);
    EconomyData base = world.initial();
    AccountSession accounts = loadAccounts(base, world.goods(), world.money());
    OrganizationProfitBook.Book book = syntheticBook(base);
    ModeMigrationPolicy.MigrationPlan first =
        ModeMigrationPolicy.plan(
            base,
            base.productionOrganizations(),
            base.units(),
            base.classes(),
            base.classStandings(),
            base.assetShares(),
            base.relations(),
            base.allocations(),
            base.markets(),
            base.debtContracts(),
            accounts,
            book,
            120L);
    ModeMigrationPolicy.MigrationPlan second =
        ModeMigrationPolicy.plan(
            base,
            base.productionOrganizations(),
            base.units(),
            base.classes(),
            base.classStandings(),
            base.assetShares(),
            base.relations(),
            base.allocations(),
            base.markets(),
            base.debtContracts(),
            accounts,
            book,
            120L);
    assertThat(second.moves()).as("计划器确定性：同输入同计划").isEqualTo(first.moves());
    System.out.println(
        "[7HEX-FULL][A-RULE] planMoves="
            + first.moves().size()
            + " sourceARuleMoves="
            + first.moves().stream()
                .filter(move -> move.source().equals(world.aRuleSourceCandidate()))
                .toList()
            + " noTransferCandidateMoves="
            + first.moves().stream()
                .filter(move -> move.source().equals(world.aNoTransferCandidate()))
                .count());

    List<ModeMigrationPolicy.MigrationMove> sourceMoves =
        first.moves().stream()
            .filter(move -> move.source().equals(world.aRuleSourceCandidate()))
            .toList();
    assertThat(sourceMoves).as("A 规则源户必须出现 1000‰ 转移").isNotEmpty();
    assertThat(sourceMoves.stream().mapToLong(ModeMigrationPolicy.MigrationMove::population).sum())
        .as("A 规则源户人口 150 全部转出")
        .isEqualTo(150L);
    assertThat(sourceMoves)
        .allSatisfy(
            move -> {
              assertThat(move.transferSpeedPerMille()).isEqualTo(1000L);
              assertThat(move.reason())
                  .isEqualTo(ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED);
              assertThat(move.target()).isNotEqualTo(move.source());
              assertThat(move.targetMode()).isEqualTo(DefaultProductionModes.TENANCY_FIXED_KIND);
            });
    assertThat(first.moves())
        .as("R0 已是最优候选 ⇒ 不转移")
        .noneMatch(move -> move.source().equals(world.aNoTransferCandidate()));

    ClassStanding sourceStanding = base.classStandings().get(world.aRuleSourceCandidate());
    ClassPosition sourcePosition = base.classPositions().get(sourceStanding.currentPositionId());
    assertThat(sourcePosition.modeId())
        .as("D-022：计划器不改写源户 mode")
        .isEqualTo(DefaultProductionModes.WAGE_FARM);
    assertThat(base.classStandings().get(world.aRuleSourceCandidate())).isEqualTo(sourceStanding);
  }

  private static OrganizationProfitBook.Book syntheticBook(EconomyData data) {
    List<ProductionOrganizationId> ids =
        new ArrayList<>(data.productionOrganizations().keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    Map<ProductionOrganizationId, OrganizationProfitBook.OrganizationProfit> byOrganization =
        new LinkedHashMap<>();
    Map<OrganizationProfitBook.ModeHex, long[]> aggregate = new LinkedHashMap<>();
    for (ProductionOrganizationId id : ids) {
      ProductionOrganization organization = data.productionOrganizations().get(id);
      if (organization == null) {
        continue;
      }
      HouseholdId household = householdOfActor(organization.organizer(), data);
      if (household == null) {
        continue;
      }
      ProductionUnitId unitId =
          organization.unitId().isPresent() ? organization.unitId().get() : null;
      HexCoord hex;
      if (unitId != null) {
        ProductionUnit unit = data.units().get(unitId);
        hex = unit == null ? data.classes().get(household).view().hex()
            : EconomySettlement.hexOfIndustry(unit.industry());
      } else {
        hex = data.classes().get(household).view().hex();
      }
      long net;
      long labor;
      if (DefaultProductionModes.WAGE_FARM.equals(organization.modeId())
          && (hex.equals(R5) || hex.equals(R0))) {
        net = -5_000L;
        labor = 120L;
      } else if (DefaultProductionModes.TENANCY_FIXED_KIND.equals(organization.modeId())
          && hex.equals(R4)) {
        net = 0L;
        labor = 20_000L;
      } else if (DefaultProductionModes.MERCHANT.equals(organization.modeId())
          && hex.equals(C)) {
        net = -120_000_000L;
        labor = 120L;
      } else {
        net = 0L;
        labor = 1_000L;
      }
      long revenue = net >= 0L ? net : 0L;
      long cost = net < 0L ? -net : 0L;
      long perLabor = net / Math.max(1L, labor);
      OrganizationProfitBook.OrganizationProfit profit =
          new OrganizationProfitBook.OrganizationProfit(
              id,
              organization.modeId(),
              organization.unitId(),
              household,
              hex,
              revenue,
              cost,
              0L,
              net,
              labor,
              perLabor);
      byOrganization.put(id, profit);
      OrganizationProfitBook.ModeHex key =
          new OrganizationProfitBook.ModeHex(organization.modeId(), hex);
      long[] total = aggregate.computeIfAbsent(key, ignored -> new long[2]);
      total[0] += net;
      total[1] += labor;
    }
    Map<OrganizationProfitBook.ModeHex, Long> perLaborByModeHex = new LinkedHashMap<>();
    Map<OrganizationProfitBook.ModeHex, Long> laborByModeHex = new LinkedHashMap<>();
    for (Map.Entry<OrganizationProfitBook.ModeHex, long[]> entry : aggregate.entrySet()) {
      perLaborByModeHex.put(entry.getKey(), entry.getValue()[0] / Math.max(1L, entry.getValue()[1]));
      laborByModeHex.put(entry.getKey(), entry.getValue()[1]);
    }
    return new OrganizationProfitBook.Book(byOrganization, perLaborByModeHex, laborByModeHex);
  }

  private static String stateFingerprint(EconomyData data) {
    StringBuilder builder = new StringBuilder();
    appendMap(builder, "classes", data.classes());
    appendMap(builder, "flows", data.flows());
    appendMap(builder, "classStandings", data.classStandings());
    appendMap(builder, "productionOrganizations", data.productionOrganizations());
    appendMap(builder, "units", data.units());
    appendMap(builder, "assetShares", data.assetShares());
    appendMap(builder, "debtContracts", data.debtContracts());
    appendMap(builder, "allocations", data.allocations());
    appendMap(builder, "memberships", data.memberships());
    appendMap(builder, "merchantFirms", data.merchantFirms());
    appendMap(builder, "markets", data.markets());
    return builder.toString();
  }

  private static String accountFingerprint(AccountSession accounts) {
    StringBuilder builder = new StringBuilder();
    List<HouseholdId> households = new ArrayList<>(accounts.householdGoods().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      builder.append(household.value()).append('|');
      Map<CommodityId, Long> goods =
          new java.util.TreeMap<>(
              Comparator.comparing(CommodityId::value));
      goods.putAll(accounts.householdGoods().getOrDefault(household, Map.of()));
      builder.append(goods).append('|');
      Map<CurrencyId, Long> money =
          new java.util.TreeMap<>(Comparator.comparing(CurrencyId::value));
      money.putAll(accounts.householdMoney().getOrDefault(household, Map.of()));
      builder.append(money).append('\n');
    }
    return builder.toString();
  }

  private static <K, V> void appendMap(StringBuilder builder, String name, Map<K, V> values) {
    builder.append(name).append('=');
    List<Map.Entry<K, V>> entries = new ArrayList<>(values.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
    for (Map.Entry<K, V> entry : entries) {
      builder.append(entry.getKey()).append("->").append(entry.getValue()).append(';');
    }
    builder.append('\n');
  }

  private static long totalAccountMoney(AccountSession accounts) {
    long total = 0L;
    for (Map<CurrencyId, Long> wallet : accounts.householdMoney().values()) {
      for (long amount : wallet.values()) {
        total += amount;
      }
    }
    return total;
  }

  private static long totalDebt(EconomyData data) {
    long total = 0L;
    for (DebtContract contract : data.debtContracts().values()) {
      total += contract.principal();
    }
    return total;
  }

  private static void assertNoNegativeBalances(EconomyData data, AccountSession accounts) {
    for (ClassRow row : data.classes().values()) {
      assertThat(row.population()).as("人口不得为负: %s", row.id()).isNotNegative();
      assertThat(row.laborMilli()).as("劳动不得为负: %s", row.id()).isNotNegative();
      assertThat(row.money()).as("行货币不得为负: %s", row.id()).isNotNegative();
    }
    for (Map<CommodityId, Long> stock : accounts.householdGoods().values()) {
      for (long quantity : stock.values()) {
        assertThat(quantity).as("商品库存不得为负").isNotNegative();
      }
    }
    for (Map<CurrencyId, Long> wallet : accounts.householdMoney().values()) {
      for (long amount : wallet.values()) {
        assertThat(amount).as("货币余额不得为负").isNotNegative();
      }
    }
    for (var share : data.assetShares().values()) {
      assertThat(share.quantity()).as("资产份额不得为负: %s", share.id()).isNotNegative();
    }
    for (DebtContract contract : data.debtContracts().values()) {
      assertThat(contract.principal()).as("债务本金不得为负: %s", contract.id()).isNotNegative();
    }
  }

  // ── 推进 ─────────────────────────────────────────────────────────────────────────────

  private static RunResult run(World world, int ticks, boolean printMilestones) {
    AccountSession accounts = loadAccounts(world.initial(), world.goods(), world.money());
    EconomyDayStepper stepper =
        new EconomyDayStepper(world.initial(), accounts, world.topology());
    Stats stats = new Stats();
    List<String> milestones = new ArrayList<>();
    List<String> d022Violations = new ArrayList<>();
    Set<Long> seenMarketDays = new LinkedHashSet<>();
    stats.displacedAtLastClose = displacedPopulation(world.initial(), world);
    stats.displacedPeak = stats.displacedAtLastClose;
    try {
      if (printMilestones) {
        milestones.add(milestoneLine(0L, world.initial(), accounts, world, stats));
      }
      for (long day = 1L; day <= ticks; day++) {
        boolean closeDay = day % CYCLE_DAYS == 0L;
        Map<HouseholdId, HouseholdFingerprint> before =
            closeDay ? fingerprints(stepper.data()) : Map.of();
        ProductionLedger ledger = stepper.step(day);
        accumulate(stats, ledger);
        Optional<MarketReport> report = stepper.lastMarketReport();
        if (report.isPresent() && seenMarketDays.add(report.get().day())) {
          MarketReport value = report.get();
          stats.freightPaid += value.freightPaidMilli();
          stats.freightUncollected += value.freightUncollectedMilli();
          stats.crossRegionFills += value.crossRegionFills();
          stats.immediateFills += value.immediateFills();
          stats.marketFills += value.fills().size();
        }
        if (closeDay) {
          EconomyData after = stepper.data();
          detectEvents(world, before, fingerprints(after), stats, d022Violations);
          stats.displacedAtLastClose = displacedPopulation(after, world);
          stats.displacedPeak = Math.max(stats.displacedPeak, stats.displacedAtLastClose);
          stats.merchantFee = 0L;
          stats.merchantUpkeep = 0L;
          stats.merchantProfit = 0L;
          for (var firm : after.merchantFirms().values()) {
            stats.merchantFee += firm.lastFeeEarnedMilli();
            stats.merchantUpkeep += firm.lastUpkeepMilli();
            stats.merchantProfit += firm.lastProfitMilli();
          }
          if (printMilestones && (day == 120L || day == 1200L || day == ticks)) {
            milestones.add(milestoneLine(day, after, accounts, world, stats));
          }
        }
      }
      EconomyData finalData = stepper.data();
      if (printMilestones && ticks % CYCLE_DAYS != 0L) {
        milestones.add(milestoneLine(ticks, finalData, accounts, world, stats));
      }
      EconomyData data = stepper.finish();
      return new RunResult(
          data,
          accounts,
          List.copyOf(milestones),
          List.copyOf(d022Violations),
          stats.freightPaid,
          stats.freightUncollected,
          stats.crossRegionFills,
          stats.immediateFills,
          stats.carrierFees,
          stats.marketFills,
          stats.grainProduced,
          stats.fiberProduced,
          stats.clothProduced,
          stats.toolProduced,
          stats.merges,
          stats.creations,
          stats.extinctions,
          stats.maxSpeedTransfers,
          stats.displacedPeak,
          stats.displacedAtLastClose,
          stats.merchantFee,
          stats.merchantUpkeep,
          stats.merchantProfit,
          java.util.Collections.unmodifiableSet(new LinkedHashSet<>(stats.outputHexes)));
    } finally {
      stepper.close();
    }
  }

  private static void accumulate(Stats stats, ProductionLedger ledger) {
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      if (!entry.getValue().isEmpty()) {
        IndustryHexKeys.hexKeyOf(entry.getKey()).ifPresent(stats.outputHexes::add);
      }
    }
    for (Map<CommodityId, Long> line : ledger.gross().values()) {
      stats.grainProduced += line.getOrDefault(GRAIN, 0L);
      stats.fiberProduced += line.getOrDefault(FIBER, 0L);
      stats.clothProduced += line.getOrDefault(CLOTH, 0L);
      stats.toolProduced += line.getOrDefault(TOOL, 0L);
    }
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() == TransferReason.CARRIER_FEE) {
        for (long amount : transfer.money().values()) {
          stats.carrierFees += amount;
        }
      }
    }
  }

  private static void detectEvents(
      World world,
      Map<HouseholdId, HouseholdFingerprint> before,
      Map<HouseholdId, HouseholdFingerprint> after,
      Stats stats,
      List<String> d022Violations) {
    for (Map.Entry<HouseholdId, HouseholdFingerprint> entry : after.entrySet()) {
      HouseholdFingerprint previous = before.get(entry.getKey());
      if (previous == null) {
        stats.creations++;
      } else if (entry.getValue().population() > previous.population()) {
        stats.merges++;
      }
    }
    for (Map.Entry<HouseholdId, HouseholdFingerprint> entry : before.entrySet()) {
      HouseholdFingerprint next = after.get(entry.getKey());
      if (next == null) {
        stats.extinctions++;
        if (entry.getKey().equals(world.aRuleSourceCandidate()) && entry.getValue().population() > 0L) {
          stats.maxSpeedTransfers++;
        }
        continue;
      }
      if (!entry.getValue().sameModeIdentity(next)) {
        d022Violations.add(
            entry.getKey()
                + " BEFORE="
                + entry.getValue().modeIdentity()
                + " AFTER="
                + next.modeIdentity());
      }
    }
  }

  private static Map<HouseholdId, HouseholdFingerprint> fingerprints(EconomyData data) {
    Map<HouseholdId, ProductionOrganizationId> organizationOfHousehold = new LinkedHashMap<>();
    List<ProductionOrganizationId> organizationIds = new ArrayList<>(data.productionOrganizations().keySet());
    organizationIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId id : organizationIds) {
      ProductionOrganization organization = data.productionOrganizations().get(id);
      if (organization == null) {
        continue;
      }
      HouseholdId household = householdOfActor(organization.organizer(), data);
      if (household != null) {
        organizationOfHousehold.putIfAbsent(household, id);
      }
    }
    Map<HouseholdId, HouseholdFingerprint> result = new LinkedHashMap<>();
    List<HouseholdId> households = new ArrayList<>(data.classes().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      ClassRow row = data.classes().get(household);
      ProductionModeId modeId = null;
      String currentPosition = "";
      String originalPosition = "";
      ClassStanding standing = data.classStandings().get(household);
      if (standing != null) {
        currentPosition = standing.currentPositionId().value();
        originalPosition = standing.originalPositionId().value();
        ClassPosition position = data.classPositions().get(standing.currentPositionId());
        if (position != null) {
          modeId = position.modeId();
        }
      }
      ProductionOrganizationId organizationId = organizationOfHousehold.get(household);
      ProductionOrganization organization =
          organizationId == null ? null : data.productionOrganizations().get(organizationId);
      ProductionModeId organizationMode =
          organization == null ? null : organization.modeId();
      String unitModeKey = "";
      if (organization != null && organization.unitId().isPresent()) {
        ProductionUnit unit = data.units().get(organization.unitId().get());
        if (unit != null) {
          unitModeKey = unit.modeKey();
        }
      }
      result.put(
          household,
          new HouseholdFingerprint(
              row.population(),
              modeId,
              currentPosition,
              originalPosition,
              organizationMode,
              unitModeKey,
              debtPrincipalOf(data, household)));
    }
    return result;
  }

  private static HouseholdId householdOfActor(ActorRef actor, EconomyData data) {
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      return null;
    }
    try {
      HouseholdId household = HouseholdActors.householdOf(actor);
      return data.classes().containsKey(household) ? household : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static long debtPrincipalOf(EconomyData data, HouseholdId household) {
    long total = 0L;
    for (DebtContract contract : data.debtContracts().values()) {
      if (contract.debtor().equals(household)) {
        total += contract.principal();
      }
    }
    return total;
  }

  private static long displacedPopulation(EconomyData data, World world) {
    long total = 0L;
    Map<HouseholdId, ProductionModeId> modeOf = modeOf(data, world);
    for (ClassRow row : data.classes().values()) {
      if (DefaultProductionModes.DISPLACED.equals(modeOf.get(row.id()))) {
        total += row.population();
      }
    }
    return total;
  }

  private static AccountSession loadAccounts(
      EconomyData base,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CurrencyId, Long>> money) {
    AccountSession accounts = AccountSession.empty();
    for (ClassRow row : base.classes().values()) {
      accounts.registerHousehold(
          row.id(),
          HouseholdActors.of(row.id()),
          row.view().hex(),
          goods.getOrDefault(row.id(), Map.of()),
          money.getOrDefault(row.id(), Map.of()),
          Map.of(),
          Map.of());
    }
    for (ProductionUnit unit : base.units().values()) {
      ActorRef operator = unit.operator();
      if (accounts.actorKeyOrNull(operator) != null) {
        continue;
      }
      accounts.registerOperator(
          operator,
          EconomySettlement.hexOfIndustry(unit.industry()),
          Map.of(),
          Map.of(),
          Map.of(),
          Map.of());
    }
    return accounts;
  }

  // ── 报告行 ───────────────────────────────────────────────────────────────────────────

  private static String milestoneLine(
      long tick,
      EconomyData data,
      AccountSession accounts,
      World world,
      Stats stats) {
    Map<ProductionModeId, Long> popByMode =
        new TreeMap<>(Comparator.comparing(ProductionModeId::value));
    Map<ProductionModeId, Long> debtByMode =
        new TreeMap<>(Comparator.comparing(ProductionModeId::value));
    Map<ProductionModeId, Long> householdsByMode =
        new TreeMap<>(Comparator.comparing(ProductionModeId::value));
    Map<HouseholdId, ProductionModeId> modeOf = modeOf(data, world);
    for (ClassRow row : data.classes().values()) {
      ProductionModeId mode = modeOf.getOrDefault(row.id(), new ProductionModeId("<none>"));
      popByMode.merge(mode, row.population(), Long::sum);
      householdsByMode.merge(mode, 1L, Long::sum);
    }
    for (DebtContract debt : data.debtContracts().values()) {
      ProductionModeId mode = modeOf.getOrDefault(debt.debtor(), new ProductionModeId("<none>"));
      debtByMode.merge(mode, debt.principal(), Long::sum);
    }
    long cityPop = 0L;
    for (ClassRow row : data.classes().values()) {
      if (row.view().hex().equals(C)) {
        cityPop += row.population();
      }
    }
    long totalDebt = 0L;
    for (DebtContract debt : data.debtContracts().values()) {
      totalDebt += debt.principal();
    }
    long totalMoney = 0L;
    for (Map<CurrencyId, Long> wallet : accounts.householdMoney().values()) {
      for (long amount : wallet.values()) {
        totalMoney += amount;
      }
    }
    long displaced = displacedPopulation(data, world);
    return "[7HEX-FULL] tick="
        + tick
        + " popModes="
        + renderModeMap(popByMode)
        + " households="
        + renderModeMap(householdsByMode)
        + " debtModes="
        + renderModeMap(debtByMode)
        + " cityPop="
        + cityPop
        + " totalPop="
        + totalPopulation(data)
        + " totalMoney="
        + totalMoney
        + " totalDebt="
        + totalDebt
        + " producedGrainMilli="
        + stats.grainProduced
        + " producedFiberMilli="
        + stats.fiberProduced
        + " producedClothMilli="
        + stats.clothProduced
        + " producedToolMilli="
        + stats.toolProduced
        + " marketFills="
        + stats.marketFills
        + " immediateFills="
        + stats.immediateFills
        + " crossRegionFills="
        + stats.crossRegionFills
        + " freightPaid="
        + stats.freightPaid
        + " freightUncollected="
        + stats.freightUncollected
        + " carrierFees="
        + stats.carrierFees
        + " merchantLastFee="
        + stats.merchantFee
        + " merchantLastUpkeep="
        + stats.merchantUpkeep
        + " merchantLastProfit="
        + stats.merchantProfit
        + " merges="
        + stats.merges
        + " creations="
        + stats.creations
        + " extinctions="
        + stats.extinctions
        + " maxSpeedTransfers="
        + stats.maxSpeedTransfers
        + " displacedPop="
        + displaced
        + " displacedPeak="
        + stats.displacedPeak;
  }

  private static String renderModeMap(Map<ProductionModeId, Long> values) {
    if (values.isEmpty()) {
      return "{}";
    }
    return values.entrySet().stream()
        .filter(entry -> entry.getValue() != 0L)
        .map(entry -> entry.getKey().value() + ":" + entry.getValue())
        .collect(Collectors.joining(",", "{", "}"));
  }

  private static Map<HouseholdId, ProductionModeId> modeOf(EconomyData data, World world) {
    Map<HouseholdId, ProductionModeId> result = new LinkedHashMap<>();
    if (!data.classStandings().isEmpty()) {
      for (ClassStanding standing : data.classStandings().values()) {
        var position = data.classPositions().get(standing.currentPositionId());
        if (position != null) {
          result.put(standing.householdId(), position.modeId());
        }
      }
      return result;
    }
    for (Map.Entry<HouseholdId, ProductionModeId> entry : world.initialModes().entrySet()) {
      if (data.classes().containsKey(entry.getKey())) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  private static long modePopulation(
      EconomyData data, ProductionModeId modeId, World world) {
    Map<HouseholdId, ProductionModeId> modeOf = modeOf(data, world);
    long total = 0L;
    for (ClassRow row : data.classes().values()) {
      if (modeId.equals(modeOf.get(row.id()))) {
        total += row.population();
      }
    }
    return total;
  }

  private static Map<HouseholdId, Long> populationMap(EconomyData data) {
    Map<HouseholdId, Long> result = new LinkedHashMap<>();
    for (ClassRow row : data.classes().values()) {
      result.put(row.id(), row.population());
    }
    return result;
  }

  private static long totalPopulation(EconomyData data) {
    long total = 0L;
    for (ClassRow row : data.classes().values()) {
      total += row.population();
    }
    return total;
  }

  // ── 世界构造 ─────────────────────────────────────────────────────────────────────────

  private static World buildWorld(boolean creationMode, boolean withStandings, boolean suppressARule) {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    Map<HouseholdId, ProductionModeId> modeByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, ClassPositionId> positionByHousehold = new LinkedHashMap<>();
    Map<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    Map<IndustryId, ActorRef> unitOperator = new LinkedHashMap<>();
    List<UnitSpec> unitSpecs = new ArrayList<>();
    Map<HouseholdId, Long> populationByHousehold = new LinkedHashMap<>();

    // ── 人口/家户 ─────────────────────────────────────────────────────────────────────
    // R0：A 规则“自身已最优、不转移”的候选户 + 大量存粮的放贷户。
    HouseholdId r0Anchor = hid("hh-r0-anchor");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r0Anchor, R0, ResidenceKind.RURAL, RICH, 200L, DefaultProductionModes.WAGE_FARM,
        DefaultProductionModes.ROLE_WAGE_LABORER);
    goods.get(r0Anchor).put(GRAIN, 100L); // 仅够“还能开工”，远低于配方下次投入
    HouseholdId r0Supplier = hid("hh-r0-supplier");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r0Supplier, R0, ResidenceKind.RURAL, LANDLORD, 20L, DefaultProductionModes.TENANCY_FIXED_KIND,
        DefaultProductionModes.ROLE_LANDLORD);
    goods.get(r0Supplier).put(GRAIN, 2_000_000_000L); // 给两个“预期为负”的候选户做外部投入供应方
    money.get(r0Supplier).put(SILVER, 200_000L);

    // R1：正常农业 + 家庭纺织 + 地主。
    HouseholdId r1Farm = hid("hh-r1-farm");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r1Farm, R1, ResidenceKind.RURAL, POOR, 120L, DefaultProductionModes.TENANCY_FIXED_KIND,
        DefaultProductionModes.ROLE_TENANT_OPERATOR);
    HouseholdId r1Weaver = hid("hh-r1-weaver");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r1Weaver, R1, ResidenceKind.RURAL, MIDDLE, 80L, DefaultProductionModes.FAMILY_FARM,
        DefaultProductionModes.ROLE_FAMILY_FARMER);
    HouseholdId r1Landlord = hid("hh-r1-landlord");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r1Landlord, R1, ResidenceKind.RURAL, LANDLORD, 20L, DefaultProductionModes.TENANCY_FIXED_KIND,
        DefaultProductionModes.ROLE_LANDLORD);
    money.get(r1Landlord).put(SILVER, 100_000L);

    // R2：分成佃农 + 自耕农 + 地主。
    HouseholdId r2Farm = hid("hh-r2-farm");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r2Farm, R2, ResidenceKind.RURAL, POOR, 100L, DefaultProductionModes.TENANCY_SHARE,
        DefaultProductionModes.ROLE_TENANT_OPERATOR);
    HouseholdId r2Other = hid("hh-r2-other");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r2Other, R2, ResidenceKind.RURAL, MIDDLE, 80L, DefaultProductionModes.FAMILY_FARM,
        DefaultProductionModes.ROLE_FAMILY_FARMER);
    HouseholdId r2Landlord = hid("hh-r2-landlord");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r2Landlord, R2, ResidenceKind.RURAL, LANDLORD, 20L, DefaultProductionModes.TENANCY_SHARE,
        DefaultProductionModes.ROLE_LANDLORD);
    money.get(r2Landlord).put(SILVER, 100_000L);

    // R3：雇农 + 纺织 + 初始流民（放在 R4 邻格，验证流民会被吸走）。
    HouseholdId r3Wage = hid("hh-r3-wage");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r3Wage, R3, ResidenceKind.RURAL, POOR, 120L, DefaultProductionModes.WAGE_FARM,
        DefaultProductionModes.ROLE_WAGE_LABORER);
    HouseholdId r3Weaver = hid("hh-r3-weaver");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r3Weaver, R3, ResidenceKind.RURAL, MIDDLE, 80L, DefaultProductionModes.FAMILY_FARM,
        DefaultProductionModes.ROLE_FAMILY_FARMER);
    HouseholdId r3Displaced = hid("hh-r3-displaced");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r3Displaced, R3, ResidenceKind.RURAL, DISPLACED, 30L, DefaultProductionModes.DISPLACED,
        DefaultProductionModes.ROLE_DISPLACED_LABORER);
    money.get(r3Displaced).put(SILVER, 1_000L);

    // R4：迁移目标户（容量只剩 20 人 ⇒ 触发“合并 + 新建”）。
    HouseholdId r4Target = hid("hh-r4-target");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r4Target, R4, ResidenceKind.RURAL, POOR, creationMode ? 180L : 1L,
        DefaultProductionModes.FAMILY_FARM,
        DefaultProductionModes.ROLE_FAMILY_FARMER);
    goods.get(r4Target).put(GRAIN, creationMode ? 300_000L : 0L);
    HouseholdId r4Profit = hid("hh-r4-profit");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r4Profit, R4, ResidenceKind.RURAL, POOR,
        suppressARule ? 1L : (creationMode ? 199L : 1L),
        DefaultProductionModes.TENANCY_FIXED_KIND,
        DefaultProductionModes.ROLE_TENANT_OPERATOR);
    money.get(r4Profit).put(SILVER, 100_000L);
    HouseholdId r4Other = hid("hh-r4-other");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r4Other, R4, ResidenceKind.RURAL, MIDDLE, 40L, DefaultProductionModes.FAMILY_FARM,
        DefaultProductionModes.ROLE_FAMILY_FARMER);
    HouseholdId r4Landlord = hid("hh-r4-landlord");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r4Landlord, R4, ResidenceKind.RURAL, LANDLORD, 20L, DefaultProductionModes.TENANCY_FIXED_KIND,
        DefaultProductionModes.ROLE_LANDLORD);
    money.get(r4Landlord).put(SILVER, 100_000L);

    // R5：A 规则触发源（150 人、流动性耗尽）+ 普通农场 + 地主。
    HouseholdId r5Wage = hid("hh-r5-wage");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r5Wage, R5, ResidenceKind.RURAL, POOR, 150L, DefaultProductionModes.WAGE_FARM,
        DefaultProductionModes.ROLE_WAGE_LABORER);
    goods.get(r5Wage).put(GRAIN, 100L);
    HouseholdId r5Farm = hid("hh-r5-farm");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r5Farm, R5, ResidenceKind.RURAL, POOR, 80L, DefaultProductionModes.TENANCY_SHARE,
        DefaultProductionModes.ROLE_TENANT_OPERATOR);
    goods.get(r5Farm).put(GRAIN, 300_000L);
    HouseholdId r5Supplier = hid("hh-r5-supplier");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r5Supplier, R5, ResidenceKind.RURAL, LANDLORD, 20L, DefaultProductionModes.TENANCY_SHARE,
        DefaultProductionModes.ROLE_LANDLORD);
    goods.get(r5Supplier).put(GRAIN, 2_000_000_000L);
    money.get(r5Supplier).put(SILVER, 200_000L);
    HouseholdId r5Landlord = hid("hh-r5-landlord");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        r5Landlord, R5, ResidenceKind.RURAL, LANDLORD, 20L, DefaultProductionModes.TENANCY_SHARE,
        DefaultProductionModes.ROLE_LANDLORD);
    money.get(r5Landlord).put(SILVER, 100_000L);

    // C：商人 principal / porter / 初始流民（同时给商号出脚夫劳动）/ 工匠 / 作坊主。
    HouseholdId cMerchant = hid("hh-c-merchant");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        cMerchant, C, ResidenceKind.URBAN, MERCHANT, 40L, DefaultProductionModes.MERCHANT,
        DefaultProductionModes.ROLE_MERCHANT_PRINCIPAL);
    money.get(cMerchant).put(SILVER, 20_000_000L);
    goods.get(cMerchant).put(GRAIN, 100_000_000L); // 保证 principal 不作为买方（否则会给自己付运费）
    // 商号 principal 不在本夹具里买粮：否则它会成为自己的承运人，撞上“转移两端不得相等”的守卫。
    clearNaturalNeeds(classes, cMerchant);
    HouseholdId cPorter = hid("hh-c-porter");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        cPorter, C, ResidenceKind.URBAN, PORTER, 60L, DefaultProductionModes.MERCHANT,
        DefaultProductionModes.ROLE_PORTER);
    goods.get(cPorter).put(GRAIN, 300_000L);
    money.get(cPorter).put(SILVER, 50_000L);
    HouseholdId cDisplaced = hid("hh-c-displaced");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        cDisplaced, C, ResidenceKind.URBAN, DISPLACED, 40L, DefaultProductionModes.DISPLACED,
        DefaultProductionModes.ROLE_DISPLACED_LABORER);
    goods.get(cDisplaced).put(GRAIN, 20_000L);
    money.get(cDisplaced).put(SILVER, 1_000L);
    HouseholdId cArtisan = hid("hh-c-artisan");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        cArtisan, C, ResidenceKind.URBAN, ARTISAN, 100L, DefaultProductionModes.HANDICRAFT_WORKSHOP,
        DefaultProductionModes.ROLE_ARTISAN);
    goods.get(cArtisan).put(GRAIN, 300_000L);
    money.get(cArtisan).put(SILVER, 50_000L);
    HouseholdId cOwner = hid("hh-c-owner");
    addHousehold(classes, goods, money, modeByHousehold, positionByHousehold, populationByHousehold,
        cOwner, C, ResidenceKind.URBAN, OWNER, 60L, DefaultProductionModes.HANDICRAFT_WORKSHOP,
        DefaultProductionModes.ROLE_WORKSHOP_OWNER);
    goods.get(cOwner).put(GRAIN, 300_000L);
    goods.get(cOwner).put(FIBER, 60_000_000L);
    goods.get(cOwner).put(TOOL, 5_000_000L);
    money.get(cOwner).put(SILVER, 500_000L);

    if (suppressARule) {
      // A 规则触发条件是“预期净收益 < 0 且 现金 + 可卖库存 < 下一周期投入”。
      // 给两个损失户足够现金，使其不触发 1000‰ A 规则，只走基线 10‰ 迁移（用于跑通 D-022/合并/流民迁出诊断）。
      money.get(r0Anchor).merge(SILVER, 1_000_000L, Long::sum);
      money.get(r5Wage).merge(SILVER, 1_000_000L, Long::sum);
    }

    // ── 产业与 unit（旧 17 参兼容位 → EconomyData 归一化成 unit + AssetShare）──────────
    // 农村农场：LAND 产能、GRAIN+FIBER、劳动。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("farm", R0.q(), R0.r()),
        "租佃农场R0", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 200_000L), Map.of(GRAIN, 67L, FIBER, 6L), Map.of(),
        actor(HouseholdActors.of(r0Anchor)));
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("farm", R1.q(), R1.r()),
        "租佃农场R1", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 200_000L), Map.of(GRAIN, 67L, FIBER, 6L), Map.of(),
        actor(HouseholdActors.of(r1Farm)));
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("farm", R2.q(), R2.r()),
        "分成农场R2", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 200_000L), Map.of(GRAIN, 67L, FIBER, 6L), Map.of(),
        actor(HouseholdActors.of(r2Farm)));
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("farm", R3.q(), R3.r()),
        "雇农农场R3", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 200_000L), Map.of(GRAIN, 67L, FIBER, 6L), Map.of(),
        actor(HouseholdActors.of(r3Wage)));
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("farm", R4.q(), R4.r()),
        "租佃农场R4", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 200_000L), Map.of(GRAIN, 67L, FIBER, 6L), Map.of(),
        actor(HouseholdActors.of(r4Target)));
    // R4 的“真实正利润目标户”：极小劳动 + 高值布产出 ⇒ netPerLabor 严格为正，流民才有可迁目标。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("profit", R4.q(), R4.r()),
        "利润目标R4", regimeId("household"), Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(AssetKind.WORKSHOP, 1L), Map.of(CLOTH, 1_000L), Map.of(),
        actor(HouseholdActors.of(r4Profit)), 1L);
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("farm", R5.q(), R5.r()),
        "分成农场R5", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 160_000L), Map.of(GRAIN, 67L, FIBER, 6L), Map.of(),
        actor(HouseholdActors.of(r5Farm)));

    // 家庭纺织（R1/R3）：FIBER + 劳动 → CLOTH。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("weave", R1.q(), R1.r()),
        "家庭纺织R1", regimeId("household"), Map.of(AssetKind.TOOL, 1L),
        Map.of(AssetKind.TOOL, 40L), Map.of(CLOTH, 5L),
        Map.of(AssetKind.TOOL, Map.of(FIBER, 5_000L)), actor(HouseholdActors.of(r1Weaver)));
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("weave", R3.q(), R3.r()),
        "家庭纺织R3", regimeId("household"), Map.of(AssetKind.TOOL, 1L),
        Map.of(AssetKind.TOOL, 40L), Map.of(CLOTH, 5L),
        Map.of(AssetKind.TOOL, Map.of(FIBER, 5_000L)), actor(HouseholdActors.of(r3Weaver)));

    // 城市手工业：FIBER + TOOL + 劳动 + WORKSHOP → CLOTH + TOOL。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("craft", C.q(), C.r()),
        "城市手工业C", regimeId("handicraft"), Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(AssetKind.WORKSHOP, 30L), Map.of(CLOTH, 30L, TOOL, 20L),
        Map.of(
            AssetKind.WORKSHOP,
            Map.of(FIBER, 8_000L, TOOL, 2_000L)),
        actor(HouseholdActors.of(cOwner)));

    // R0 的“预期为负但自身读数不差”候选：外部供料 + 极短劳动配额 ⇒ 实际净收益可为负但可维持开工判定。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("anchor-loss", R0.q(), R0.r()),
        "迁移动锚点损失农场R0", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 1_000L), Map.of(GRAIN, 1L),
        Map.of(AssetKind.LAND, Map.of(GRAIN, 5_000_000L)), actor(HouseholdActors.of(r0Anchor)),
        1L);

    // R5 的 A 规则触发源：产出 1 谷、投入 10,000 毫谷/规模，劳动 1 千分/规模 ⇒ 实际与预期都是负。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("loss-farm", R5.q(), R5.r()),
        "亏损农场R5", regimeId("tenant"), Map.of(AssetKind.LAND, 1_000L),
        Map.of(AssetKind.LAND, 1_000L), Map.of(GRAIN, 1L),
        Map.of(AssetKind.LAND, Map.of(GRAIN, 5_000_000L)), actor(HouseholdActors.of(r5Wage)),
        1L);

    // 商号贸易单元：有运力资产、人工，但没有商品产出（具体产出由运费腿表达）。
    addIndustry(industries, unitOperator, unitSpecs, IndustryHexKeys.id("trade", C.q(), C.r()),
        "商号贸易C", regimeId("handicraft"), Map.of(AssetKind.CATTLE, 1L),
        Map.of(AssetKind.CATTLE, 1L), Map.of(), Map.of(),
        actor(HouseholdActors.of(cMerchant)), CYCLE_DAYS * 100L);

    // ── 劳动供给 / 配额（批次 id 用 ResidenceKind 的前缀约定）────────────────────────────
    Map<PeopleLotId, Long> grossByLot = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      for (ResidenceKind residence : ResidenceKind.all()) {
        long population = 0L;
        for (ClassRow row : classes.values()) {
          if (row.view().hex().equals(hex) && row.view().residence() == residence) {
            population += row.population();
          }
        }
        if (population <= 0L) {
          continue;
        }
        PeopleLotId lot = lot(hex, residence);
        long gross = population * LABOR_MILLI_PER_PERSON;
        grossByLot.put(lot, gross);
        laborSupply.put(lot, new LaborSupply(lot, START_PERIOD, gross, 0L, 0L));
      }
    }

    // 农村/城市：显式配额（actor = unit.operator；activity = unit id）——不再走旧档 pending 迁移。
    Map<PeopleLotId, Long> allocatedByLot = new LinkedHashMap<>();
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("farm", R0.q(), R0.r()), HouseholdActors.of(r0Anchor), r0Anchor,
        laborQuota(industries, IndustryHexKeys.id("farm", R0.q(), R0.r()), 100L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("anchor-loss", R0.q(), R0.r()), HouseholdActors.of(r0Anchor), r0Anchor,
        1L);
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("farm", R1.q(), R1.r()), HouseholdActors.of(r1Farm), r1Farm,
        laborQuota(industries, IndustryHexKeys.id("farm", R1.q(), R1.r()), 100L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("weave", R1.q(), R1.r()), HouseholdActors.of(r1Weaver), r1Weaver,
        laborQuota(industries, IndustryHexKeys.id("weave", R1.q(), R1.r()), 30L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("farm", R2.q(), R2.r()), HouseholdActors.of(r2Farm), r2Farm,
        laborQuota(industries, IndustryHexKeys.id("farm", R2.q(), R2.r()), 80L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("farm", R3.q(), R3.r()), HouseholdActors.of(r3Wage), r3Wage,
        laborQuota(industries, IndustryHexKeys.id("farm", R3.q(), R3.r()), 100L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("weave", R3.q(), R3.r()), HouseholdActors.of(r3Weaver), r3Weaver,
        laborQuota(industries, IndustryHexKeys.id("weave", R3.q(), R3.r()), 30L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("farm", R4.q(), R4.r()), HouseholdActors.of(r4Target), r4Target,
        laborQuota(industries, IndustryHexKeys.id("farm", R4.q(), R4.r()), 150L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("profit", R4.q(), R4.r()), HouseholdActors.of(r4Profit), r4Profit, 1L);
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("farm", R5.q(), R5.r()), HouseholdActors.of(r5Farm), r5Farm,
        laborQuota(industries, IndustryHexKeys.id("farm", R5.q(), R5.r()), 80L));
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("loss-farm", R5.q(), R5.r()), HouseholdActors.of(r5Wage), r5Wage,
        1L);
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("craft", C.q(), C.r()), HouseholdActors.of(cOwner), cOwner, 10_000L);
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("craft", C.q(), C.r()), HouseholdActors.of(cOwner), cArtisan, 8_000L);
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("trade", C.q(), C.r()), HouseholdActors.of(cMerchant), cPorter, 1L);
    addAllocation(allocations, laborSupply, allocatedByLot,
        IndustryHexKeys.id("trade", C.q(), C.r()), HouseholdActors.of(cMerchant), cDisplaced, 1L);

    // ── 关系（空规则 = 全部自留；商人/外部供料两条特殊）────────────────────────────────
    Map<ProductionUnitId, ProductionRelation> relations = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, ActorRef> entry : unitOperator.entrySet()) {
      ProductionUnitId unitId = ProductionUnitId.idOf(entry.getKey(), entry.getValue());
      IndustryId industryId = entry.getKey();
      ActorRef operator = entry.getValue();
      if (industryId.equals(IndustryHexKeys.id("trade", C.q(), C.r()))) {
        CompensationRule wage =
            new CompensationRule(
                RuleType.FIXED_MONEY_WAGE,
                new Recipient.ToHousehold(cPorter),
                Pool.FIXED_AMOUNT,
                Weight.NONE,
                0,
                10L,
                Optional.empty(),
                Optional.of(SILVER),
                10);
        relations.put(
            unitId,
            new ProductionRelation(
                unitId,
                operator,
                new Recipient.ToActor(operator),
                List.of(wage),
                operator,
                LaborSource.WAGE));
      } else if (industryId.equals(IndustryHexKeys.id("anchor-loss", R0.q(), R0.r()))
          || industryId.equals(IndustryHexKeys.id("loss-farm", R5.q(), R5.r()))) {
        HouseholdId supplier =
            industryId.equals(IndustryHexKeys.id("anchor-loss", R0.q(), R0.r()))
                ? r0Supplier
                : r5Supplier;
        relations.put(
            unitId,
            new ProductionRelation(
                unitId,
                operator,
                new Recipient.ToHousehold(supplier),
                List.of(),
                operator,
                LaborSource.SELF));
      } else {
        relations.put(
            unitId,
            new ProductionRelation(
                unitId,
                operator,
                new Recipient.ToActor(operator),
                List.of(),
                operator,
                LaborSource.SELF));
      }
    }

    // ── 市场：7 格同币（银），城乡价差制造跨格套利。────────────────────────────────────
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(C, market(3L, 8L, 20L, 30L));
    markets.put(R0, market(1L, 1L, 40L, 50L));
    markets.put(R1, market(1L, 1L, 36L, 48L));
    markets.put(R2, market(2L, 1L, 38L, 46L));
    markets.put(R3, market(1L, 1L, 42L, 52L));
    if (creationMode) {
      markets.put(R4, market(2L, 2L, 39L, 47L));
    } else {
      // 目标户本格没有 GRAIN/FIBER 价 ⇒ 它本期真实净收益 0；而 A 规则源仍然是负收益。
      Map<CommodityId, Long> noCropPrices = new LinkedHashMap<>();
      noCropPrices.put(CLOTH, 39L);
      noCropPrices.put(TOOL, 47L);
      markets.put(R4, new Market(SILVER, noCropPrices));
    }
    markets.put(R5, market(1L, 1L, 41L, 49L));

    // ── 债务：给 A 规则“自身最优、不转移”候选户一笔货币债（用它不生产的工具计价 ⇒ 不会被市
    //   场收入顺手还掉），下一周期利息并入本金 ⇒ 债务增加。────────────────────────────────
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    DebtUnit anchorDebtUnit = DebtUnit.commodity(TOOL);
    DebtTerms anchorTerms = DebtTerms.legacyDefault(20);
    DebtContractId anchorDebtId =
        DebtContractId.idOf(r0Anchor, r0Supplier, anchorDebtUnit, anchorTerms);
    debts.put(
        anchorDebtId,
        new DebtContract(
            anchorDebtId,
            r0Anchor,
            r0Supplier,
            anchorDebtUnit,
            anchorTerms,
            1_000_000L,
            0L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL));

    Map<MembershipId, Membership> memberships = new LinkedHashMap<>();
    for (ClassRow row : classes.values()) {
      PeopleLotId membershipLot = new PeopleLotId("fixture-lot-" + row.id().value());
      MembershipId membershipId = Membership.idOf(membershipLot, row.id());
      memberships.put(
          membershipId,
          new Membership(membershipId, membershipLot, row.id(), row.population()));
    }

    EconomyMeta legacyMeta =
        new EconomyMeta(
            MAP_ID,
            0L,
            OptionalLong.empty(),
            EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
            Optional.empty());
    EconomyData legacy =
        EconomyData.empty()
            .withMeta(Optional.of(legacyMeta))
            .withIndustries(industries)
            .withClasses(classes)
            .withLaborSupply(laborSupply)
            .withRelations(relations)
            .withAllocations(allocations)
            .withMemberships(memberships)
            .withDebtContracts(debts)
            .withMarkets(markets);

    // ── 模式 / 阶层归属 / 组织 / 商号（迁移元数据层）────────────────────────────────────
    EconomyData full = attachRuntimeLayers(legacy, modeByHousehold, positionByHousehold, unitOperator,
        r4Target, r5Wage, r0Anchor, r4Profit, cMerchant, cPorter, cDisplaced, r0Supplier,
        withStandings);

    long initialPopulation = totalPopulation(full);
    long initialMoney = totalMoney(full, goods, money);
    MarketTopology topology = topology(markets);
    Set<HouseholdId> initialDisplaced = new LinkedHashSet<>();
    for (Map.Entry<HouseholdId, ProductionModeId> entry : modeByHousehold.entrySet()) {
      if (DefaultProductionModes.DISPLACED.equals(entry.getValue())) {
        initialDisplaced.add(entry.getKey());
      }
    }
    return new World(
        full,
        deepCopyGoods(goods),
        deepCopyMoney(money),
        topology,
        initialPopulation,
        initialMoney,
        anchorDebtId,
        r0Anchor,
        r5Wage,
        initialDisplaced,
        Map.copyOf(modeByHousehold));
  }

  private static EconomyData attachRuntimeLayers(
      EconomyData legacy,
      Map<HouseholdId, ProductionModeId> modeByHousehold,
      Map<HouseholdId, ClassPositionId> positionByHousehold,
      Map<IndustryId, ActorRef> unitOperator,
      HouseholdId r4Target,
      HouseholdId r5Wage,
      HouseholdId r0Anchor,
      HouseholdId r4Profit,
      HouseholdId cMerchant,
      HouseholdId cPorter,
      HouseholdId cDisplaced,
      HouseholdId r0Supplier,
      boolean withStandings) {
    Map<HouseholdId, ClassStanding> standings = new LinkedHashMap<>();
    if (withStandings) {
      for (ClassRow row : legacy.classes().values()) {
        ProductionModeId mode = modeByHousehold.get(row.id());
        ClassPositionId position = positionByHousehold.get(row.id());
        if (mode == null || position == null) {
          throw new IllegalStateException("夹具缺 mode/position: " + row.id());
        }
        standings.put(
            row.id(),
            new ClassStanding(
                row.id(), position, position, Map.of(), 0L, 0L, "fixture-initial"));
      }
    }
    Map<ProductionOrganizationId, ProductionOrganization> organizations = new LinkedHashMap<>();
    Map<ProductionOrganizationId, MerchantFirm> merchantFirms = new LinkedHashMap<>();
    // r4Target 由自动组织阶段接管；迁移目标读数由 hh-r4-profit 的正利润组织承担。
    attachOrganization(legacy, organizations, modeByHousehold.get(r5Wage),
        positionByHousehold.get(r5Wage), r5Wage,
        IndustryHexKeys.id("loss-farm", R5.q(), R5.r()), unitOperator, "source-r5");
    attachOrganization(legacy, organizations, modeByHousehold.get(r4Profit),
        positionByHousehold.get(r4Profit), r4Profit,
        IndustryHexKeys.id("profit", R4.q(), R4.r()), unitOperator, "target-profit-r4");
    attachOrganization(legacy, organizations, modeByHousehold.get(r0Anchor),
        positionByHousehold.get(r0Anchor), r0Anchor,
        IndustryHexKeys.id("anchor-loss", R0.q(), R0.r()), unitOperator, "anchor-r0");
    ProductionOrganizationId merchantOrg =
        attachOrganization(legacy, organizations, modeByHousehold.get(cMerchant),
            positionByHousehold.get(cMerchant), cMerchant,
            IndustryHexKeys.id("trade", C.q(), C.r()), unitOperator, "merchant-c");
    merchantFirms.put(
        merchantOrg,
        new MerchantFirm(
            merchantOrg,
            MerchantPolicy.MerchantTier.PORTER,
            C,
            true,
            100_000L,
            0L,
            MerchantFirm.PORTER_SERVICE_RADIUS_HEX,
            0L,
            0L,
            0L,
            0L));
    // 让 compiler 知道这些参数被使用（脚夫/流民由显式配额承载；放贷户由 debt contract 承载）。
    assert cPorter != null && cDisplaced != null && r0Supplier != null;
    EconomyMeta runtimeMeta =
        new EconomyMeta(
            MAP_ID, 0L, OptionalLong.empty(), EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V1, Optional.empty());
    return legacy
        .withModes(DefaultProductionModes.modes())
        .withClassStructures(DefaultProductionModes.classStructures())
        .withClassPositions(DefaultProductionModes.classPositions())
        .withClassStandings(standings)
        .withProductionOrganizations(organizations)
        .withMerchantFirms(merchantFirms)
        .withMeta(Optional.of(runtimeMeta));
  }

  private static ProductionOrganizationId attachOrganization(
      EconomyData data,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      ProductionModeId mode,
      ClassPositionId position,
      HouseholdId household,
      IndustryId industry,
      Map<IndustryId, ActorRef> unitOperator,
      String ignoredTag) {
    ActorRef operator = unitOperator.get(industry);
    if (operator == null) {
      throw new IllegalStateException("产业没有 operator: " + industry);
    }
    ProductionUnitId unitId = ProductionUnitId.idOf(industry, operator);
    List<AssetShareId> assetSources = new ArrayList<>();
    Industry template = data.industries().get(industry);
    for (AssetKind asset : template.capacityPerUnit().keySet()) {
      AssetShareId shareId =
          AssetShare.idOf(industry, asset, operator, operator, AssetShare.RightKind.OWNED, 0L);
      if (data.assetShares().containsKey(shareId)) {
        assetSources.add(shareId);
      }
    }
    if (assetSources.isEmpty()) {
      throw new IllegalStateException("组织没有资产份额: " + industry);
    }
    String hexKey = IndustryHexKeys.hexKeyOf(industry).orElseThrow();
    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(mode, position, household, hexKey);
    organizations.put(
        organizationId,
        new ProductionOrganization(
            organizationId,
            mode,
            position,
            Optional.of(unitId),
            operator,
            List.of(household),
            assetSources,
            List.of(new Recipient.ToActor(operator)),
            new Recipient.ToActor(operator),
            Optional.of("fixture:" + ignoredTag),
            ProductionOrganization.Status.ACTIVE,
            ""));
    return organizationId;
  }

  // ── 小工具：家户/产业/配额/市场 ─────────────────────────────────────────────────────

  private static HouseholdId hid(String value) {
    return HouseholdId.parse(value);
  }

  private static ActorRef actor(ActorRef ref) {
    return ref;
  }

  private static RegimeId regimeId(String value) {
    return new RegimeId(value);
  }

  private static Map<CommodityId, Long> naturalNeeds(long population) {
    Map<CommodityId, Long> needs = new LinkedHashMap<>();
    needs.put(GRAIN, EconomyVocabulary.dailyRationMilli(population, 1L));
    long cloth = population * 1_000L / 365L;
    if (cloth > 0L) {
      needs.put(CLOTH, cloth);
    }
    return needs;
  }

  private static void addHousehold(
      Map<HouseholdId, ClassRow> classes,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      Map<HouseholdId, ProductionModeId> modeByHousehold,
      Map<HouseholdId, ClassPositionId> positionByHousehold,
      Map<HouseholdId, Long> populationByHousehold,
      HouseholdId id,
      HexCoord hex,
      ResidenceKind residence,
      String classId,
      long population,
      ProductionModeId mode,
      String role) {
    CohortKey view = new CohortKey(hex, residence, new SocialClassId(classId));
    long labor = population * LABOR_MILLI_PER_PERSON;
    classes.put(
        id,
        new ClassRow(
            id,
            view,
            population,
            labor,
            800,
            0L,
            List.of(),
            naturalNeeds(population),
            Map.of(),
            0L));
    goods.put(id, new LinkedHashMap<>());
    money.put(id, new LinkedHashMap<>());
    modeByHousehold.put(id, mode);
    positionByHousehold.put(
        id, DefaultProductionModes.positionId(mode, role).orElseThrow());
    populationByHousehold.put(id, population);
  }

  private static void clearNaturalNeeds(Map<HouseholdId, ClassRow> classes, HouseholdId id) {
    ClassRow row = classes.get(id);
    if (row == null) {
      throw new IllegalStateException("清空自然需求的家户不存在: " + id);
    }
    classes.put(
        id,
        new ClassRow(
            row.id(),
            row.view(),
            row.population(),
            row.laborMilli(),
            row.participationPerMille(),
            row.money(),
            row.debts(),
            Map.of(),
            Map.of(),
            row.cycleNaturalNeedMilli()));
  }

  private static Market market(long grain, long fiber, long cloth, long tool) {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, grain);
    prices.put(FIBER, fiber);
    prices.put(CLOTH, cloth);
    prices.put(TOOL, tool);
    return new Market(SILVER, prices);
  }

  private static MarketTopology topology(Map<HexCoord, Market> markets) {
    List<MarketNode> nodes = new ArrayList<>();
    for (HexCoord hex : HEXES) {
      long radius = hex.equals(C) ? 3L : 1L;
      nodes.add(
          new MarketNode(
              "node-" + hex.q() + "_" + hex.r(),
              hex,
              (int) radius,
              SILVER,
              MoneyVocabulary.SILVER_SPECIE.id()));
    }
    // 探针默认费率是 5‰ + 5‰/hex；P6 城市 BOSS 折扣对 1 hex lane 就能把它压到 0，
    // 故本世界显式采用 P4 T1 运输队场景的 50‰ + 50‰/hex（仍在 MarketTopology.of 显式拓扑入口内）。
    return MarketTopology.of(
        nodes,
        markets,
        markets.keySet(),
        hex -> 1,
        (from, to) -> 0,
        hex -> 0,
        new TransportTariff(50L, 50L));
  }

  private static long totalMoney(
      EconomyData data,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CurrencyId, Long>> money) {
    long total = 0L;
    for (Map<CurrencyId, Long> wallet : money.values()) {
      for (long amount : wallet.values()) {
        total += amount;
      }
    }
    return total;
  }

  private static Map<HouseholdId, Map<CommodityId, Long>> deepCopyGoods(
      Map<HouseholdId, Map<CommodityId, Long>> source) {
    Map<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : source.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return copy;
  }

  private static Map<HouseholdId, Map<CurrencyId, Long>> deepCopyMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> source) {
    Map<HouseholdId, Map<CurrencyId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> entry : source.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return copy;
  }

  private static void addIndustry(
      Map<IndustryId, Industry> industries,
      Map<IndustryId, ActorRef> unitOperator,
      List<UnitSpec> unitSpecs,
      IndustryId id,
      String name,
      RegimeId regime,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Long> capacity,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      ActorRef operator) {
    addIndustry(
        industries, unitOperator, unitSpecs, id, name, regime, capacityPerUnit, capacity,
        outputPerUnit, cycleInputPerUnit, operator, 143L);
  }

  private static void addIndustry(
      Map<IndustryId, Industry> industries,
      Map<IndustryId, ActorRef> unitOperator,
      List<UnitSpec> unitSpecs,
      IndustryId id,
      String name,
      RegimeId regime,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Long> capacity,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      ActorRef operator,
      long laborPerUnit) {
    industries.put(
        id,
        EconomyFixtures.industry(
            id,
            name,
            regime,
            CYCLE_DAYS,
            0L,
            capacityPerUnit,
            capacity,
            Map.of(),
            0L,
            laborPerUnit,
            outputPerUnit,
            cycleInputPerUnit,
            slots(),
            new AllocationRule.Split(500, 500),
            0L,
            Map.of(),
            operator));
    unitOperator.put(id, operator);
    unitSpecs.add(new UnitSpec(id, operator));
  }

  private static List<ClassSlot> slots() {
    return List.of(
        new ClassSlot(new SocialClassId(POOR), "贫农", 800),
        new ClassSlot(new SocialClassId(MIDDLE), "中农", 700),
        new ClassSlot(new SocialClassId(RICH), "富农", 600),
        new ClassSlot(new SocialClassId(LANDLORD), "地主", 100));
  }

  private static long laborQuota(
      Map<IndustryId, Industry> industries, IndustryId industry, long desiredScale) {
    Industry template = industries.get(industry);
    return template.recipe().laborPerUnit() * desiredScale;
  }

  private static void addAllocation(
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<PeopleLotId, LaborSupply> supply,
      Map<PeopleLotId, Long> allocatedByLot,
      IndustryId industry,
      ActorRef actor,
      HouseholdId household,
      long laborMilli) {
    HexCoord hex = IndustryHexKeys.hexKeyOf(industry).map(HexCoord::parse).orElseThrow();
    ResidenceKind residence = hex.equals(C) ? ResidenceKind.URBAN : ResidenceKind.RURAL;
    PeopleLotId lot = lot(hex, residence);
    LaborSupply labor = supply.get(lot);
    if (labor == null) {
      throw new IllegalStateException("没有劳动供给批次: " + lot);
    }
    long already = allocatedByLot.getOrDefault(lot, 0L);
    if (already + laborMilli > labor.availableLabor()) {
      throw new IllegalStateException(
          "夹具配额超过供给: " + lot + " used=" + already + " add=" + laborMilli);
    }
    allocatedByLot.put(lot, already + laborMilli);
    ProductionUnitId unitId = ProductionUnitId.idOf(industry, actor);
    LaborAllocationId id = LaborAllocation.idOf(industry, lot, household);
    allocations.put(
        id,
        new LaborAllocation(
            id, lot, household, actor, unitId.value(), laborMilli, START_PERIOD));
  }

  private static PeopleLotId lot(HexCoord hex, ResidenceKind residence) {
    return new PeopleLotId(
        residence.lotPrefix()
            + (residence == ResidenceKind.URBAN ? "c-" : "")
            + hex.q()
            + "_"
            + hex.r()
            + ":MALE:1");
  }

  // ── 结果载体 ─────────────────────────────────────────────────────────────────────────

  record World(
      EconomyData initial,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      MarketTopology topology,
      long initialPopulation,
      long initialMoney,
      DebtContractId anchorDebtId,
      HouseholdId aNoTransferCandidate,
      HouseholdId aRuleSourceCandidate,
      Set<HouseholdId> initialDisplaced,
      Map<HouseholdId, ProductionModeId> initialModes) {}

  record RunResult(
      EconomyData data,
      AccountSession accounts,
      List<String> milestones,
      List<String> d022Violations,
      long freightPaid,
      long freightUncollected,
      long crossRegionFills,
      long immediateFills,
      long carrierFees,
      long marketFills,
      long grainProducedMilli,
      long fiberProducedMilli,
      long clothProducedMilli,
      long toolProducedMilli,
      long merges,
      long creations,
      long extinctions,
      long maxSpeedTransfers,
      long displacedPeak,
      long displacedLastClose,
      long merchantFee,
      long merchantUpkeep,
      long merchantProfit,
      Set<String> outputHexes) {}

  private static final class Stats {
    long freightPaid;
    long freightUncollected;
    long crossRegionFills;
    long immediateFills;
    long carrierFees;
    long marketFills;
    long grainProduced;
    long fiberProduced;
    long clothProduced;
    long toolProduced;
    long merges;
    long creations;
    long extinctions;
    long maxSpeedTransfers;
    long displacedAtLastClose;
    long displacedPeak;
    long merchantFee;
    long merchantUpkeep;
    long merchantProfit;
    final Set<String> outputHexes = new LinkedHashSet<>();
  }

  private record HouseholdFingerprint(
      long population,
      ProductionModeId modeId,
      String currentPosition,
      String originalPosition,
      ProductionModeId organizationMode,
      String unitModeKey,
      long debtPrincipal) {

    String modeIdentity() {
      return "modeId="
          + modeId
          + ",standing="
          + currentPosition
          + ",original="
          + originalPosition
          + ",orgMode="
          + organizationMode
          + ",unitModeKey="
          + unitModeKey;
    }

    boolean sameModeIdentity(HouseholdFingerprint other) {
      return java.util.Objects.equals(modeId, other.modeId)
          && java.util.Objects.equals(currentPosition, other.currentPosition)
          && java.util.Objects.equals(originalPosition, other.originalPosition)
          && java.util.Objects.equals(organizationMode, other.organizationMode)
          && java.util.Objects.equals(unitModeKey, other.unitModeKey);
    }
  }

  private record UnitSpec(IndustryId industry, ActorRef operator) {}
}
