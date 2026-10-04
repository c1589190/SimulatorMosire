package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.money.GovernmentActors;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstPilotEngine;
import io.mosire.simos.economy.classfirst.ClassFirstSettlement;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>R2a 的入口判据</b>：{@code economyProfile=class-first} 的小世界必须
 *
 * <ol>
 *   <li>种出真正的 {@link ClassFirstState}：4 个阶层池、家户生产账户、独立 GOV 放贷账户；
 *   <li><b>不再种旧生产结构</b>：industries / relations / units / assetShares / laborSupply / allocations
 *       全空； entries 只留 {@code q/r + industries(空壳) + classes}；
 *   <li>池资产与 actor 家户账本逐值对账（同一份开缸库存/货币；土地/农具按显式所有权表从本 seed 的可耕地与作坊工具存量派生）；
 *   <li>用 {@link ClassFirstSettlement#settleOneDay} 直接推进 <b>24 tick</b>：人口/货币/账户净额/土地守恒；
 *   <li>真的经 {@code core.submitBatch} 落盘（不是只解析）并能重放；变更集还要能经 {@link Timeline#changeSetJson} +
 *       读回逐值往返（修 R1 的 {@code ClassPool} 裸 Jackson 绑定缺口）。
 * </ol>
 *
 * <p>★ <b>R2c：三国一起播</b>：本类现在依次播完三国 class-first seed，逐值核对世界级 4 池 = 三国之和（人口 / 家户账户 / 土地 / 农具 / 货币 /
 * lender 资金），并直接经 {@code ClassFirstSettlement} 推进 24 tick 验证守恒。
 */
class ClassFirstEconomySeedTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final long TICKS = 24L;

  @TempDir Path tempDir;

  @Test
  void classFirstWorldgenCommitsReplaysAndSettles24Ticks() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("class-first-store"));
    try (CoreSimos core = worldgenCore(store)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      List<JsonNode> summaries =
          CompactThreeNationsWorld.initializeNations(
              core, EconomySeeder.FoundationProfile.CLASS_FIRST);
      assertThat(summaries).as("R2c：三国 class-first seed 全部成功").hasSize(3);
      assertThat(summaries.get(2).get("revision").asLong()).as("创世 1 条 + 三国各 1 条").isEqualTo(4L);

      SimulationState seeded = core.replay(new StateRef(MAIN, new RevisionId(4L)));
      EconomyData economy = CompactThreeNationsWorld.economyOf(seeded);
      ActorData actor = CompactThreeNationsWorld.actorOf(seeded);
      ClassFirstState state = economy.classFirst();
      assertThat(state.isEmpty()).as("CLASS_FIRST 必须真的种出池与账户").isFalse();
      assertThat(state.modeParticipations()).hasSize(4);
      assertThat(state.classPools()).as("世界级聚合后仍只有 4 个池（同键加法，不按国家分池）").hasSize(4);
      assertThat(state.assetStateSchemas()).hasSize(1);
      assertThat(state.classBounds()).hasSize(4);
      assertThat(state.mobilityPolicies()).hasSize(1);
      long positiveRows =
          economy.classes().values().stream().filter(row -> row.population() > 0L).count();
      assertThat(state.householdAccounts())
          .as("家户账户数 = 三国合计（每个有人口的 ClassRow 一个生产账户）")
          .hasSize((int) positiveRows);
      assertThat(state.accounts()).as("创世零债务：双边账户表为空").isEmpty();
      assertThat(state.lenders()).as("三国同 id 放贷窗口合并成 1 个").hasSize(1);
      assertThat(state.meta().config()).as("schema/bounds/policy/config 全部显式落在状态里").isNotNull();
      Set<String> positions = new TreeSet<>();
      for (ClassPool pool : state.classPools().values()) {
        positions.add(pool.classPositionId());
        assertThat(pool.population()).as("四个池都要有真实人口: %s", pool.classPositionId()).isPositive();
        assertThat(pool.labor()).as("四个池都要有劳动: %s", pool.classPositionId()).isPositive();
      }
      assertThat(positions)
          .containsExactlyInAnyOrder(
              PilotModel.LANDLORD_ID,
              PilotModel.MIDDLE_PEASANT_ID,
              PilotModel.TENANT_ID,
              PilotModel.LABORER_ID);

      // ── 旧生产结构没有被种下（解析后的 EconomyData 逐表为空） ─────────────────────────
      assertThat(economy.industries()).isEmpty();
      assertThat(economy.relations()).isEmpty();
      assertThat(economy.units()).isEmpty();
      assertThat(economy.assetShares()).isEmpty();
      assertThat(economy.laborSupply()).isEmpty();
      assertThat(economy.allocations()).isEmpty();
      assertThat(economy.flows()).isEmpty();
      assertThat(economy.memberships()).as("R2c：class-first 入口丢弃旧档迁移器补出的合成成员份额（旧生产结构影子）").isEmpty();
      assertThat(economy.classes()).as("classes 只是人口/账户视图，保留").isNotEmpty();
      assertThat(economy.markets()).as("市场壳保留").isNotEmpty();

      // ── 三国求和：人口 / 池资产 = actor 家户账本的同一次开缸数字（不双计、不另算） ────────
      long poolPopulation =
          state.classPools().values().stream().mapToLong(ClassPool::population).sum();
      assertThat(poolPopulation)
          .as("Σ世界池人口 == 三国人口")
          .isEqualTo(CompactThreeNationsWorld.TOTAL_POPULATION);
      Map<String, long[]> householdTotals = aggregateActorHouseholds(economy, actor);
      for (ClassPool pool : state.classPools().values()) {
        long[] want = householdTotals.get(pool.classPositionId());
        assertThat(want).as("池位置 %s 必须来自已播家户", pool.classPositionId()).isNotNull();
        assertThat(pool.stock(AssetKind.GRAIN)).as("粮：池 == Σ家户账").isEqualTo(want[0]);
        assertThat(pool.stock(AssetKind.CLOTH)).as("布：池 == Σ家户账").isEqualTo(want[1]);
        assertThat(pool.stock(AssetKind.MONEY)).as("钱：池 == Σ家户账").isEqualTo(want[2]);
      }
      assertPoolLandAndToolsMatchSeed(economy, state, CompactThreeNationsWorld.socialOf(seeded));

      // ── HouseholdProductionAccount 的身份/归属与 ClassRow 一致 ────────────────────────
      for (var account : state.householdAccounts().values()) {
        ClassRow row = economy.classes().get(HouseholdId.parse(account.householdId()));
        assertThat(row).as("生产账户的家户必须在 classes 视图里: %s", account.householdId()).isNotNull();
        assertThat(account.poolId())
            .as("生产账户归属的池必须与阶层映射一致")
            .isEqualTo(
                ClassPoolId.idOf(
                    state.meta().config().mode().id(), positionOf(row.view().stratum().value())));
      }

      // ── 独立 GOV 放贷账户：三国各一本（同一 owner、不同格），合计 == 合并后的 lender 资金 ─────
      var lender = state.lenders().values().iterator().next();
      assertThat(lender.id()).isEqualTo(EconomySeeder.CLASS_FIRST_LENDER_ID);
      assertThat(lender.money()).as("GOV 放贷窗口有大量资金（三国之和）").isPositive();
      assertThat(lender.goods()).as("GOV 放贷窗口 0 流动性（不放货）").isEmpty();
      long govMoney = 0L;
      int govAccounts = 0;
      for (GoodsAccount candidate : actor.accounts().values()) {
        if (candidate
            .key()
            .owner()
            .equals(GovernmentActors.of(EconomySeeder.GENESIS_GOVERNMENT_ID))) {
          govAccounts++;
          assertThat(candidate.balances()).as("GOV 账户只放钱、不放货").isEmpty();
          govMoney =
              Math.addExact(
                  govMoney, candidate.money().getOrDefault(EconomySeeder.MARKET_NUMERAIRE, 0L));
        }
      }
      assertThat(govAccounts).as("三国 seed 各建一本 GOV 账户（同一 owner、不同格）").isEqualTo(3);
      assertThat(govMoney).as("Σactor GOV 货币 == 合并后的 lender 资金").isEqualTo(lender.money());

      // ── 用正式入口直接推进 24 tick：守恒断言 ─────────────────────────────────────────
      long initialPopulation =
          state.classPools().values().stream().mapToLong(ClassPool::population).sum();
      ClassFirstPilotEngine seededEngine = ClassFirstPilotEngine.restore(state);
      long initialMoney = seededEngine.totalMoney();
      long initialOwnedLand = seededEngine.initialOwnedLandTotal();
      System.out.println(
          "[CLASS-FIRST-SEED] pools="
              + state.classPools().size()
              + " households="
              + state.householdAccounts().size()
              + " population="
              + initialPopulation
              + " grain="
              + seededEngine.totalGrain()
              + " cloth="
              + seededEngine.totalCloth()
              + " householdMoney="
              + seededEngine.totalHouseholdMoney()
              + " lenderMoney="
              + seededEngine.totalLenderMoney()
              + " ownedLand="
              + seededEngine.totalOwnedLand()
              + " tools="
              + seededEngine.totalTools()
              + " landForSale="
              + seededEngine.landForSale());
      ClassFirstState current = state;
      for (long day = 1L; day <= TICKS; day++) {
        ClassFirstSettlement.Result result =
            ClassFirstSettlement.settleOneDay(
                current, new ClassFirstSettlement.Inputs(day, null, List.of(), List.of()));
        assertThat(result.audit().tick()).as("日审计 tick == 输入 day").isEqualTo(day);
        current = result.state();
      }
      assertThat(current.meta().tick()).isEqualTo(TICKS);
      assertThat(current.classPools()).hasSize(4);
      ClassFirstPilotEngine restored = ClassFirstPilotEngine.restore(current);
      assertThat(restored.totalPopulation()).as("人口守恒（迁移只换池）").isEqualTo(initialPopulation);
      assertThat(restored.accountNetSum()).as("双边账户净额之和恒为 0").isZero();
      assertThat(restored.totalMoney()).as("货币守恒（池 + 放贷窗口 + 托管）").isEqualTo(initialMoney);
      assertThat(restored.totalOwnedLand() + restored.landForSale())
          .as("土地守恒（所有权 + LandForSale）")
          .isEqualTo(initialOwnedLand);
      assertThat(restored.stockEnrichmentViolations()).as("迁移不得抬高原池人均库存").isZero();
      // ★ 24 tick 后的状态（账户/流动事件非空）也必须能经 Timeline 的裸 mapper 写读往返。
      EconomyData currentData = EconomyData.empty().withClassFirst(current);
      String currentJson =
          Timeline.changeSetJson(
              new WorldChangeSet(
                  Map.of("economy", EconomyChangeSet.between(EconomyData.empty(), currentData))));
      WorldChangeSet currentBack = Timeline.readChangeSet(currentJson);
      assertThat(
              EconomyChangeSet.apply(
                  (EconomyChangeSet) currentBack.modules().get("economy"), EconomyData.empty()))
          .as("24 tick 状态变更集经 Timeline 写读后逐值不变")
          .isEqualTo(currentData);
      System.out.println(
          "[CLASS-FIRST-24] pools="
              + current.classPools().size()
              + " households="
              + current.householdAccounts().size()
              + " population="
              + restored.totalPopulation()
              + " money="
              + restored.totalMoney()
              + " land="
              + restored.totalOwnedLand()
              + " producedGrain="
              + current.meta().totals().producedGrainTotal()
              + " seedUsed="
              + current.meta().totals().seedUsedTotal()
              + " rationConsumed="
              + current.meta().totals().rationConsumedTotal()
              + " clothConsumed="
              + current.meta().totals().clothConsumedTotal()
              + " borrowedGrain="
              + current.meta().totals().borrowedGrainTotal()
              + " borrowedMoney="
              + current.meta().totals().borrowedMoneyTotal()
              + " boughtGrain="
              + current.meta().totals().boughtGrainTotal()
              + " rentPaid="
              + current.meta().totals().rentPaidTotal()
              + " wagePaid="
              + current.meta().totals().wagePaidTotal()
              + " interestCharged="
              + current.meta().totals().interestChargedTotal()
              + " residualPaid="
              + current.meta().totals().residualPaidTotal()
              + " redLights="
              + current.meta().totals().redLightTotal()
              + " collections="
              + current.meta().totals().collectionEventCount()
              + " classFlowEvents="
              + current.classFlowEvents().size());
      for (ClassPool pool : current.classPools().values()) {
        System.out.println(
            "[CLASS-FIRST-POOL] "
                + pool.classPositionId()
                + " population="
                + pool.population()
                + " labor="
                + pool.labor()
                + " land="
                + pool.stock(AssetKind.OWNED_LAND)
                + " tools="
                + pool.stock(AssetKind.TOOLS)
                + " grain="
                + pool.stock(AssetKind.GRAIN)
                + " money="
                + pool.stock(AssetKind.MONEY)
                + " leaseHolding="
                + pool.leaseHolding());
      }
    }
  }

  /**
   * ★★ <b>R1 缺陷的回归（R2a 修）</b>：非空 {@code classFirst} 的变更集必须能经 <b>Timeline 的裸 mapper</b> 写出去、读回来，
   * 逐值重建。修前这条会抛 {@code No serializer found for ClassPool}；写侧还带出派生属性 {@code "empty"}。
   */
  @Test
  void classFirstChangeSetSurvivesTimelineRoundTrip() {
    ClassFirstState state = seededState();
    EconomyData target = EconomyData.empty().withClassFirst(state);
    EconomyChangeSet changes = EconomyChangeSet.between(EconomyData.empty(), target);

    String json = Timeline.changeSetJson(new WorldChangeSet(Map.of("economy", changes)));
    assertThat(json).as("派生判断 empty 不进线格式").doesNotContain("\"empty\"");
    WorldChangeSet decoded = Timeline.readChangeSet(json);
    assertThat(decoded.modules()).containsOnlyKeys("economy");
    EconomyChangeSet decodedChanges = (EconomyChangeSet) decoded.modules().get("economy");
    assertThat(EconomyChangeSet.apply(decodedChanges, EconomyData.empty()))
        .as("变更集经 Timeline 写读后逐值不变")
        .isEqualTo(target);
  }

  /** 紧凑三国的一个 region → 内存 CLASS_FIRST 小世界（与 worldgen 同一条 plan，但不落盘）。 */
  private static ClassFirstState seededState() {
    GameMap map = CompactThreeNationsWorld.map();
    Region region = map.regions().get(CompactThreeNationsWorld.GRANARY);
    SettlementPlan plan = settlementPlan(map, region);
    PopulationSeeder.Seeding populations = PopulationSeeder.seed(plan, 0L);
    EconomySeeder.Seed seeding =
        EconomySeeder.plan(
            CompactThreeNationsWorld.MAP_ID,
            populations,
            map,
            EconomySeeder.genesisMoneyMilliPerCapita(),
            EconomySeeder.FoundationProfile.CLASS_FIRST,
            TestConditions.EMPTY);
    assertThat(seeding.classFirst().isEmpty()).isFalse();
    SimulationState empty =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(0L)),
            Map.of("economy", new EconomySnapshot(REF, SimosTimestamp.of(0L), EconomyData.empty())),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(empty, seeding.economyPayload());
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyData economy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
    assertThat(economy.classFirst()).as("线格式往返后 classFirst 逐值不变").isEqualTo(seeding.classFirst());
    return economy.classFirst();
  }

  /** 紧凑三国的一个 region → 真 settlement plan（与 {@code WorldgenInitializeTool} 同一条生成路径）。 */
  private static SettlementPlan settlementPlan(GameMap map, Region region) {
    NationSetup setup =
        CompactThreeNationsWorld.config().byRegionId(region.id().value()).withHexes(region.hexes());
    ResolvedNation resolved = setup.resolve();
    return SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
  }

  /** 逐 ClassRow → 同格 actor 家户账 → 按阶层映射聚合（池的对账分母；零人口行也照读）。 */
  private static Map<String, long[]> aggregateActorHouseholds(
      EconomyData economy, ActorData actor) {
    Map<String, long[]> totals = new LinkedHashMap<>();
    CommodityId grain = new CommodityId(EconomySeeder.COMMODITY_GRAIN);
    CommodityId cloth = new CommodityId(EconomySeeder.COMMODITY_CLOTH);
    for (ClassRow row : economy.classes().values()) {
      GoodsAccount account =
          actor.accounts().get(new GoodsAccountKey(HouseholdActors.of(row.id()), row.view().hex()));
      assertThat(account).as("每个 ClassRow 必须有 actor 家户账: %s", row.id()).isNotNull();
      long[] atPool =
          totals.computeIfAbsent(positionOf(row.view().stratum().value()), k -> new long[3]);
      atPool[0] += account.balances().getOrDefault(grain, 0L);
      atPool[1] += account.balances().getOrDefault(cloth, 0L);
      atPool[2] += account.money().getOrDefault(EconomySeeder.MARKET_NUMERAIRE, 0L);
    }
    return totals;
  }

  /** 土地/农具：池总量必须逐值来自本 seed 的可耕地（毫亩→亩）与作坊工具存量（毫工具→件）。 */
  private static void assertPoolLandAndToolsMatchSeed(
      EconomyData economy, ClassFirstState state, SocialData social) {
    GameMap map = CompactThreeNationsWorld.map();
    Set<HexCoord> classHexes = new TreeSet<>();
    for (ClassRow row : economy.classes().values()) {
      classHexes.add(row.view().hex());
    }
    // ★★ R2c：CLASS_FIRST 逐国播种 ⇒ 土地/工具按国先各自取整再求和（不是拿三国毫亩总和一次取整）。
    long expectedLand = 0L;
    long expectedTools = 0L;
    for (Region region : map.regions().values()) {
      long landMilliMu = 0L;
      long workshops = 0L;
      for (HexCoord hex : classHexes) {
        if (!region.hexes().contains(hex)) {
          continue;
        }
        String terrain = map.terrainIndex().get(hex);
        assertThat(terrain).as("classes 的格必须在地图上: %s", hex).isNotNull();
        landMilliMu += EconomySeeder.landMilliMuOf(terrain);
        long urban = 0L;
        for (PopulationGroup group : social.groups().values()) {
          if (social.hexOfLot(group.id()).filter(hex::equals).isPresent()
              && PopulationLots.isUrban(group)) {
            urban += group.count();
          }
        }
        workshops += urban / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP;
      }
      expectedLand += landMilliMu / 1000L;
      expectedTools +=
          workshops
              * EconomySeeder.toolPerWorkshopMilli()
              / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
    }
    long poolLand =
        state.classPools().values().stream()
            .mapToLong(pool -> pool.stock(AssetKind.OWNED_LAND))
            .sum();
    long poolTools =
        state.classPools().values().stream().mapToLong(pool -> pool.stock(AssetKind.TOOLS)).sum();
    assertThat(poolLand).as("Σ池土地 == 三国可耕地之和（逐国毫亩→亩）").isEqualTo(expectedLand);
    assertThat(poolTools).as("Σ池农具 == 三国作坊工具存量之和（逐国）").isEqualTo(expectedTools);
  }

  /** 社会阶层槽位 → 阶层池位置（与 {@code EconomySeeder.CLASS_FIRST_POSITION_BY_SLOT} 同一映射的测试侧拼写）。 */
  private static String positionOf(String slot) {
    return switch (slot) {
      case "poor_peasant" -> PilotModel.LABORER_ID;
      case "middle_peasant" -> PilotModel.TENANT_ID;
      case "rich_peasant" -> PilotModel.MIDDLE_PEASANT_ID;
      case "landlord" -> PilotModel.LANDLORD_ID;
      default -> throw new IllegalStateException("未知阶层槽位: " + slot);
    };
  }

  /**
   * 与 {@code CompactThreeNationsEconomyTest.shellAlikeCore} 同源的装配（只需 worldgen 的 handler，不跑时间参与者）。
   */
  private static CoreSimos worldgenCore(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (ModuleCodec codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec(),
            new ActorCodec())) {
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
            new CreateArmyHandler())) {
      core.register(handler);
    }
    return core;
  }
}
