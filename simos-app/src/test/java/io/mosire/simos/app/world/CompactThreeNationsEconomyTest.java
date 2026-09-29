package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.gen.WorldgenConfig;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>紧凑三国测试世界的验收测试</b>（2026-09-28）：用真 {@code simos.worldgen.initialize} 路径初始化 3 国，再让真协调器 {@link
 * PopulationEconomyTimeParticipant} <b>一次推进 0 → 120 tick</b>（内部逐日），最后读真实数据。
 *
 * <pre>
 * 判据 ① 世界规格：3 个 region / 地图 ≥100 格（本夹具 158 格，其中陆地 144）/ 地形 ≥3 种（实际 5 种）+ 河流 + 海岸；
 * 判据 ② 播种后：3 国 / ≥3 城 / 144 个市场 / 5 种商品 / 600+ 阶层行 / 288+ 产业 —— 人口与需求不是"一格一组"；
 * 判据 ③ 0→120 一次推进不抛：lastClosedCycle 从空推进到 1；至少一个 FlowRow 的消费/所得为正；
 * 判据 ④ 账无负数：全部 actor 商品/货币余额 ≥0、全部 ClassRow.money ≥0（构造期守卫之外的读侧复核）。
 * </pre>
 *
 * <p>★ 打印的两行 {@code [COMPACT-…]} 就是交付报告里的实测读数（不是"再调一遍生成器"）。
 *
 * <p>★ <b>装配与 Shell 同源</b>：6 个 codec（Map/Social/Unit/Sd/Economy/Actor）+ worldgen 需要的 10 条
 * handler（social 3 + economy 1 + actor 1 + map 1 + unit 2 + sd 2）+ 3
 * 个时间参与者（Unit/Sd/PopulationEconomy，与 {@code Shell.start} 的清单同项）。
 */
class CompactThreeNationsEconomyTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:compact-three-nations-test";

  /** 一个产业周期 = 120 天（{@code EconomySeeder.CYCLE_DAYS}）。 */
  private static final long CYCLE_DAYS = 120L;

  @TempDir Path tempDir;

  // ── 判据 ①：世界规格（不跑引擎，先证明地图本身合格）────────────────────────────────────

  @Test
  void mapFixtureHasThreeDisjointRegionsAndRichTerrain() {
    GameMap map = CompactThreeNationsWorld.map();

    assertThat(map.hexes()).as("地图总格数").hasSize(CompactThreeNationsWorld.MAP_HEXES);
    assertThat(map.hexes().size()).as("地图 ≥100 格").isGreaterThanOrEqualTo(100);
    assertThat(map.regions()).as("3 个国家 region").hasSize(3);

    int land = 0;
    int ocean = 0;
    Set<String> terrains = new TreeSet<>();
    for (Map.Entry<HexCoord, String> entry : map.terrainIndex().entrySet()) {
      terrains.add(entry.getValue());
      if ("ocean".equals(entry.getValue())) {
        ocean++;
      } else {
        land++;
      }
    }
    assertThat(land).as("陆地格数 = 3 × 48").isEqualTo(CompactThreeNationsWorld.LAND_HEXES);
    assertThat(ocean).as("海洋格数（商业国海岸线）").isEqualTo(CompactThreeNationsWorld.OCEAN_HEXES);
    assertThat(terrains)
        .as("地形至少 5 种（要求 ≥3）")
        .contains("plains", "low_hills", "mountains", "desert", "ocean");

    Set<HexCoord> seen = new TreeSet<>();
    for (Region region : map.regions().values()) {
      assertThat(region.hexes()).as("每个 region 恰好 48 格").hasSize(48);
      for (HexCoord hex : region.hexes()) {
        assertThat(seen.add(hex)).as("region 不得重叠：%s", hex).isTrue();
      }
    }
    assertThat(seen).as("三个 region 合计 144 格且两两不交").hasSize(CompactThreeNationsWorld.LAND_HEXES);

    TerrainView view = TerrainView.of(map);
    long navigableRiverHexes = 0L;
    long coastalHexes = 0L;
    for (HexCoord hex : seen) {
      if (view.riverEdgesAt(hex) >= 2) {
        navigableRiverHexes++;
      }
      if (view.coastal(hex)) {
        coastalHexes++;
      }
    }
    assertThat(navigableRiverHexes).as("干流经过的格（river 边 ≥2）").isPositive();
    assertThat(coastalHexes).as("沿海格（邻居有 ocean）").isPositive();

    long riverEdges = 0L;
    for (Map.Entry<EdgeRef, EdgeTags> entry : map.edges().entrySet()) {
      if (entry.getValue().byPathway().containsKey("river")) {
        riverEdges++;
      }
    }
    assertThat(riverEdges).as("农业国干流 5 格 ⇒ 4 条河流边").isEqualTo(4L);

    System.out.println(
        "[COMPACT-MAP] hexes="
            + map.hexes().size()
            + " land="
            + land
            + " ocean="
            + ocean
            + " regions="
            + map.regions().size()
            + " terrains="
            + terrains
            + " riverEdges="
            + riverEdges
            + " navigableRiverHexes="
            + navigableRiverHexes
            + " coastalHexes="
            + coastalHexes);
  }

  // ── 判据 ②③④：真播种 + 一次 0→120 + 真实读数 ────────────────────────────────────────

  @Test
  void threeNationsSeedAndCloseTheirFirstCycle() throws IOException {
    WorldgenConfig config = CompactThreeNationsWorld.config();
    assertThat(config.nations()).as("冻结输入里有 3 国").hasSize(3);

    Path store = Files.createDirectories(tempDir.resolve("compact-store"));
    try (CoreSimos core = shellAlikeCore(store)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));

      List<JsonNode> summaries = CompactThreeNationsWorld.initializeNations(core);
      assertThat(summaries).as("三国各一条摘要").hasSize(3);
      long seededPopulation = 0L;
      long seededCities = 0L;
      for (JsonNode summary : summaries) {
        System.out.println("[COMPACT-NATION] " + summary);
        assertThat(summary.get("hexCount").asInt()).as("每国 48 个有农村人口的格").isEqualTo(48);
        assertThat(summary.get("cityCount").asInt()).as("每国至少 1 城").isPositive();
        seededPopulation += summary.get("totalPopulation").asLong();
        seededCities += summary.get("cityCount").asInt();
      }
      assertThat(seededPopulation).as("三国人口合计 = 380,000").isEqualTo(380_000L);
      assertThat(seededCities).as("城市总数 ≥ 3").isGreaterThanOrEqualTo(3);
      assertThat(core.head(MAIN).orElseThrow().value()).as("创世 1 条 + 三国各 1 条 = 4").isEqualTo(4L);

      SimulationState seeded = core.replay(new StateRef(MAIN, new RevisionId(4L)));
      Map<String, Object> seedReadings = CompactThreeNationsWorld.readings(seeded);
      System.out.println("[COMPACT-SEED] " + seedReadings);
      assertThat((long) seedReadings.get("nations")).as("3 国").isEqualTo(3L);
      assertThat((long) seedReadings.get("mapHexes")).isEqualTo(CompactThreeNationsWorld.MAP_HEXES);
      assertThat((long) seedReadings.get("landHexes"))
          .isEqualTo(CompactThreeNationsWorld.LAND_HEXES);
      assertThat((long) seedReadings.get("cities")).isGreaterThanOrEqualTo(3);
      assertThat((long) seedReadings.get("markets"))
          .as("每个有经济状态的格一个市场（144 格）")
          .isEqualTo(CompactThreeNationsWorld.LAND_HEXES);
      assertThat((long) seedReadings.get("commodities")).as("粮/布/纤维/工具/铁 5 种").isEqualTo(5);
      assertThat((long) seedReadings.get("classRows"))
          .as("阶层行数远多于 3（不止一格一组）")
          .isGreaterThanOrEqualTo(600L);
      assertThat((long) seedReadings.get("populationGroups")).isGreaterThanOrEqualTo(900L);
      assertThat((long) seedReadings.get("industries"))
          .as("每格农业 + 农村纺织 + 城市作坊")
          .isGreaterThanOrEqualTo(288L);
      assertThat((long) seedReadings.get("productionUnits"))
          .as("生产单元（H5：经营者/主 unit + 家户副 unit）非空")
          .isPositive();
      assertThat((long) seedReadings.get("totalPopulation")).isEqualTo(380_000L);
      assertThat((long) seedReadings.get("ruralPopulation")).isEqualTo(282_000L);
      assertThat((long) seedReadings.get("urbanPopulation")).isEqualTo(98_000L);
      assertThat((long) seedReadings.get("lastClosedCycle")).as("还没关过账").isEqualTo(-1L);

      // ── 0 → 120：一次 AdvanceTime，内部逐日（与 Shell 同一个 PopulationEconomyTimeParticipant）──
      long headBefore = core.head(MAIN).orElseThrow().value();
      CommandResult advanced =
          core.submit(
              new AdvanceTime(
                  "cmd-advance-0-120",
                  "corr-advance-0-120",
                  INITIATOR,
                  MAIN,
                  new RevisionId(headBefore),
                  new TimeRange(
                      SimosTimestamp.of(0L), Optional.of(SimosTimestamp.of(CYCLE_DAYS)))));
      assertThat(advanced)
          .as("一次推进 120 天只落一条 revision")
          .isEqualTo(
              new CommandResult.Committed(new StateRef(MAIN, new RevisionId(headBefore + 1L))));

      SimulationState after = core.replay(new StateRef(MAIN, new RevisionId(headBefore + 1L)));
      Map<String, Object> day120 = CompactThreeNationsWorld.readings(after);
      System.out.println("[COMPACT-120] " + day120);
      Map<String, Map<String, Object>> byNation = CompactThreeNationsWorld.nationReadings(after);
      long perNationPopulation = 0L;
      long nationsWithGap = 0L;
      for (Map.Entry<String, Map<String, Object>> entry : byNation.entrySet()) {
        System.out.println("[COMPACT-NATION-120] " + entry.getKey() + " -> " + entry.getValue());
        Map<String, Object> nation = entry.getValue();
        assertThat((long) nation.get("population")).as("%s 人口 > 0", entry.getKey()).isPositive();
        perNationPopulation += (long) nation.get("population");
        if ((long) nation.get("cycleUnmetNeed") > 0L) {
          nationsWithGap++;
        }
      }
      assertThat(perNationPopulation)
          .as("★ 逐国经济侧人口合计 == 社会侧全国总人口（两侧同源）")
          .isEqualTo((long) day120.get("totalPopulation"));
      assertThat(nationsWithGap).as("★ 至少一个国家的本期未满足需求 > 0（缺口读数可观测）").isPositive();

      EconomyData economy = CompactThreeNationsWorld.economyOf(after);
      assertThat(CompactThreeNationsWorld.lastClosedCycleOf(economy))
          .as("★ 周期末 lastClosedCycle 从空前进到 1")
          .isEqualTo(1L);
      assertThat((long) day120.get("cycleConsumed")).as("本周期有真实消费发生（毫商品）").isPositive();
      assertThat((long) day120.get("cycleIncome")).as("本周期有真实所得发生（毫商品）").isPositive();
      assertThat(economy.flows()).as("流水行与阶层行一一对应").hasSize(economy.classes().size());
      assertThat(
              economy.flows().values().stream()
                  .anyMatch(
                      flow ->
                          flow.consumed().values().stream().mapToLong(Long::longValue).sum() > 0L))
          .as("★ 至少一个 FlowRow 的消费为正")
          .isTrue();
      assertThat(
              economy.flows().values().stream()
                  .anyMatch(
                      flow ->
                          flow.income().values().stream().mapToLong(Long::longValue).sum() > 0L))
          .as("★ 至少一个 FlowRow 的所得为正")
          .isTrue();

      // ── 判据 ④：账无负数（读侧复核，不只依赖构造期守卫）──
      ActorData books = CompactThreeNationsWorld.actorOf(after);
      assertThat(books.accounts()).as("家户/经营者账本非空").isNotEmpty();
      for (GoodsAccount account : books.accounts().values()) {
        assertThat(account.balances().values())
            .as("商品余额 ≥0：%s", account.key())
            .allSatisfy(value -> assertThat(value).isNotNegative());
        assertThat(account.money().values())
            .as("货币余额 ≥0：%s", account.key())
            .allSatisfy(value -> assertThat(value).isNotNegative());
      }
      for (ClassRow row : economy.classes().values()) {
        assertThat(row.population()).as("行人口 ≥0").isNotNegative();
        assertThat(row.money()).as("行货币 ≥0：%s", row.id()).isNotNegative();
      }

      // ── 结构与人口在 120 天后仍在（不是被推进抹掉）──
      assertThat((long) day120.get("nations")).isEqualTo(3);
      assertThat((long) day120.get("cities")).isGreaterThanOrEqualTo(3);
      assertThat((long) day120.get("markets")).isEqualTo(144);
      assertThat((long) day120.get("commodities")).isEqualTo(5);
      assertThat((long) day120.get("totalPopulation")).isPositive();
      assertThat((long) day120.get("classRows")).isGreaterThanOrEqualTo(600L);
      assertThat((long) day120.get("industries")).isGreaterThanOrEqualTo(288L);
      assertThat((long) day120.get("productionUnits")).isPositive();
      assertThat((long) day120.get("grainAccountMilli") + (long) day120.get("grainInTransitMilli"))
          .as("120 天后粮的权威库存（账户 + 在途）非负且非空")
          .isPositive();
    }
  }

  // ── P4：720 tick 初始债务生命周期（典型条件：地主→贫农初始债 + LAND 拆分 + 质押）────────

  @Test
  void threeNations720TickInitialDebtLifecycle() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("compact-store-720"));
    try (CoreSimos core = shellAlikeCore(store)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      List<JsonNode> summaries =
          CompactThreeNationsWorld.initializeNations(
              core,
              CompactThreeNationsWorld.typicalConditions(),
              CompactThreeNationsWorld.TYPICAL_CONDITIONS_NATION);
      assertThat(summaries).as("三国各一条摘要").hasSize(3);

      long headBefore = core.head(MAIN).orElseThrow().value();
      CommandResult advanced =
          core.submit(
              new AdvanceTime(
                  "cmd-advance-0-720",
                  "corr-advance-0-720",
                  INITIATOR,
                  MAIN,
                  new RevisionId(headBefore),
                  new TimeRange(SimosTimestamp.of(0L), Optional.of(SimosTimestamp.of(720L)))));
      assertThat(advanced)
          .as("一次推进 720 天仍只落一条 revision")
          .isEqualTo(
              new CommandResult.Committed(new StateRef(MAIN, new RevisionId(headBefore + 1L))));

      SimulationState after = core.replay(new StateRef(MAIN, new RevisionId(headBefore + 1L)));
      Map<String, Object> day720 = CompactThreeNationsWorld.readings(after);
      System.out.println("[COMPACT-720] " + day720);

      EconomyData economy = CompactThreeNationsWorld.economyOf(after);
      DebtContractId initialDebtId =
          DebtContractId.idOf(
              CompactThreeNationsWorld.TYPICAL_DEBTOR,
              CompactThreeNationsWorld.TYPICAL_CREDITOR,
              DebtUnit.commodity(new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID)),
              DebtTerms.legacyDefault());
      DebtContract initialDebt = economy.debtContracts().get(initialDebtId);
      System.out.println("[COMPACT-720-DEBT] " + initialDebt);
      System.out.println("[COMPACT-720-PLEDGE] " + economy.pledges().values());

      assertThat(CompactThreeNationsWorld.lastClosedCycleOf(economy))
          .as("720 tick = 6 个完整周期")
          .isEqualTo(6L);
      assertThat(economy.debtContracts()).as("720 后全市场债务非空").isNotEmpty();
      assertThat(initialDebt).as("典型初始债仍在合同表里").isNotNull();
      assertThat(initialDebt.principal()).as("典型初始债跨 720 tick 后应被真实偿还而下降").isLessThan(1_000_000L);
      assertThat(initialDebt.lastInterestDay()).as("跨周期后应至少计过一次息").isPresent();
      assertThat(economy.pledges()).as("典型初始质押仍在").hasSize(1);

      ActorData books = CompactThreeNationsWorld.actorOf(after);
      for (GoodsAccount account : books.accounts().values()) {
        assertThat(account.balances().values())
            .as("商品余额 ≥0：%s", account.key())
            .allSatisfy(value -> assertThat(value).isNotNegative());
        assertThat(account.money().values())
            .as("货币余额 ≥0：%s", account.key())
            .allSatisfy(value -> assertThat(value).isNotNegative());
      }
    }
  }

  // ── 压力 720：到期自动 DEFAULTED → 自动挂质押 → 处置 → 阶层下滑 → hex 信号 ────────────────

  @Test
  void threeNations720TickDefaultLiquidation() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("compact-store-stress-720"));
    try (CoreSimos core = shellAlikeCore(store)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      assertThat(
              CompactThreeNationsWorld.initializeNations(
                  core,
                  CompactThreeNationsWorld.stressConditions(),
                  CompactThreeNationsWorld.GRANARY))
          .hasSize(3);

      CommodityId grain = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
      DebtContractId debtId =
          DebtContractId.idOf(
              CompactThreeNationsWorld.TYPICAL_DEBTOR,
              CompactThreeNationsWorld.STRESS_CREDITOR,
              DebtUnit.commodity(grain),
              DebtTerms.legacyDefault(
                  CompactThreeNationsWorld.STRESS_INTEREST_RATE_PER_MILLE_PER_CYCLE));

      long headBefore = core.head(MAIN).orElseThrow().value();
      CommandResult advanced =
          core.submit(
              new AdvanceTime(
                  "cmd-advance-0-720-stress",
                  "corr-advance-0-720-stress",
                  INITIATOR,
                  MAIN,
                  new RevisionId(headBefore),
                  new TimeRange(SimosTimestamp.of(0L), Optional.of(SimosTimestamp.of(720L)))));
      assertThat(advanced)
          .isEqualTo(
              new CommandResult.Committed(new StateRef(MAIN, new RevisionId(headBefore + 1L))));

      SimulationState after = core.replay(new StateRef(MAIN, new RevisionId(headBefore + 1L)));
      EconomyData economy = CompactThreeNationsWorld.economyOf(after);
      DebtContract debt = economy.debtContracts().get(debtId);
      Pledge autoPledge =
          economy.pledges().values().stream()
              .filter(pledge -> pledge.debtContractId().equals(debtId))
              .findFirst()
              .orElse(null);
      ClassStanding afterStanding =
          economy.classStandings().get(CompactThreeNationsWorld.TYPICAL_DEBTOR);
      boolean creditorOwnsLand =
          economy.assetShares().values().stream()
              .anyMatch(
                  share ->
                      share.asset() == AssetKind.LAND
                          && share.kind() == AssetShare.RightKind.OWNED
                          && share.quantity() > 0L
                          && share
                              .owner()
                              .equals(
                                  HouseholdActors.of(CompactThreeNationsWorld.STRESS_CREDITOR)));
      Set<HexCrisisSignal.Kind> signalKinds = new TreeSet<>();
      economy.crisisSignals().values().forEach(signal -> signalKinds.add(signal.kind()));

      System.out.println(
          "[STRESS-720] debt=" + debt + " pledge=" + autoPledge + " standing=" + afterStanding);
      System.out.println(
          "[STRESS-720] creditorOwnsLand="
              + creditorOwnsLand
              + " signals="
              + economy.crisisSignals().values());

      assertThat(debt).as("压力初始债仍在合同表里").isNotNull();
      assertThat(debt.status()).as("到期未清 ⇒ DEFAULTED").isEqualTo(DebtStatus.DEFAULTED);
      assertThat(debt.principal())
          .as("大额债在 720 后仍未清，但已真实减额")
          .isPositive()
          .isLessThan(CompactThreeNationsWorld.STRESS_DEBT_PRINCIPAL_MILLI);
      assertThat(autoPledge).as("无 InitialPledge ⇒ 自动挂质押").isNotNull();
      assertThat(autoPledge.id().value()).startsWith("autopledge-");
      assertThat(autoPledge.quantity())
          .as("自动质押已执行（quantity 下降）")
          .isPositive()
          .isLessThan(CompactThreeNationsWorld.STRESS_LAND_SPLIT_MILLI_MU);
      assertThat(creditorOwnsLand).as("对应 LAND 份额已转给债权人").isTrue();
      assertThat(afterStanding.currentPositionId())
          .as("核心生产资料被处置 ⇒ 阶层下滑")
          .isNotEqualTo(afterStanding.originalPositionId());
      assertThat(afterStanding.reason()).startsWith("class-decline");
      assertThat(signalKinds)
          .as("hex 同时出现 CLASS_DECLINE 与 DEBT_EXPLOSION")
          .contains(HexCrisisSignal.Kind.CLASS_DECLINE, HexCrisisSignal.Kind.DEBT_EXPLOSION);
    }
  }

  // ────────────────────────────────── 装配（与 Shell 同源）─────────────────────────────

  /**
   * Shell 同源的<b>最小完整装配</b>：6 codec + worldgen 10 handler + 3 时间参与者。★ 经济推进的日循环、出生/死亡、产权落账都在 {@link
   * PopulationEconomyTimeParticipant} 内部 —— 缺它就只能 replay、不能结算（本测试明确不走那条路）。
   */
  private static CoreSimos shellAlikeCore(Path storeDir) {
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
            // social：逐格农村人口 + 城市节点 + 人口批次
            new SetPopulationHandler(),
            new CreateCityHandler(),
            new SeedGroupsHandler(),
            // economy / actor：与人口同批的经济切片与家户账本
            new EconomySeedHandler(),
            new ActorSeedHandler(),
            // worldgen 军队块：tag + 建国 + 单位 + 指挥链 + 军队
            new UpdateRegionHandler(),
            new CreateNationHandler(),
            new CreateUnitHandler(),
            new CreateCommandChainHandler(),
            new CreateArmyHandler())) {
      core.register(handler);
    }
    core.register(
        new UnitTimeParticipant(TerrainMovementCost.INSTANCE, CompactThreeNationsWorld.MAP_ID));
    core.register(new SdTimeParticipant(CompactThreeNationsWorld.MAP_ID));
    core.register(new PopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID));
    return core;
  }
}
