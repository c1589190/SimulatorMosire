package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.ClassFirstPopulationEconomyTimeParticipant;
import io.mosire.simos.app.time.EconomyDayFeed;
import io.mosire.simos.app.time.MarketReportFeed;
import io.mosire.simos.app.time.MarketTopologyBookTestAccess;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.app.tools.write.WorldgenInitializeTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.ExpectedProfitBook;
import io.mosire.simos.economy.time.MarketDemandBook;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.economy.time.ModeMigrationPolicy;
import io.mosire.simos.economy.time.OrganizationProfitBook;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionUnitBook;
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
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ P11.6：真实 12 格小地图 + 真实 CoreSimos 程序路径 3650 tick 验收。
 *
 * <p>地图由 {@link RealTwelveHexWorld} 程序化构造；人口/城市/经济/actor 经真实 {@link WorldgenInitializeTool}
 * （production-runtime）播种；推进只走 {@code core.submit(AdvanceTime)} 与已注册的真实时间参与者；读数全部来自
 * {@code core.replay} 后的真实状态与进程内真实账本/市场报告，不手搭 EconomyData、不直接调 EconomyDayStepper。
 *
 * <p>本类不做“只要跑通”的弱断言：迁移/合并/新建/壳户/流民、真实生产、跨格成交、在途、债务压力、断粮、D-022/D-023
 * 都按真实读数判断；若自然参数下没有发生，保留失败并输出需要调的参数方向。
 */
class RealTwelveHexProductionRuntime3650Test {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:real-12-test";
  private static final long TICKS = 3650L;
  private static final long SEGMENT_DAYS = 120L;

  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final CommodityId TOOL = new CommodityId("tool");

  /** 每个 120 天边界采一份“关账日快照”。 */
  private record Boundary(
      long tick,
      long population,
      long ruralPopulation,
      long urbanPopulation,
      long economyRuralPopulation,
      long economyUrbanPopulation,
      long households,
      long cities,
      long markets,
      Map<HouseholdId, Long> populationByHousehold,
      Map<HouseholdId, ResidenceKind> residenceByHousehold,
      Map<HouseholdId, ProductionModeId> modeByHousehold,
      Map<HouseholdId, String> positionByHousehold,
      Map<HouseholdId, Long> birthsByHousehold,
      Map<HouseholdId, Long> deathsByHousehold,
      Map<String, ProductionModeId> organizationModeById,
      Map<String, String> unitModeKeyById,
      long displacedPopulation,
      long displacedHouseholds,
      long shellHouseholds,
      long debtTotal,
      long debtContracts,
      long unmetNeed,
      long births,
      long deaths,
      /** 逐家户 {@code FlowRow.consumed} 的商品总量（毫商品；真实日耗 + 投入消费的读数）。 */
      long consumed,
      long newBorrowing,
      long repaid,
      long capitalizedArrears,
      Map<CurrencyId, Long> moneyByCurrency,
      Map<AssetKind, Long> assetByKind,
      long grainInTransit,
      long clothInTransit,
      long shipments,
      long producedGrainLastDay,
      long producedClothLastDay,
      long producedFiberLastDay,
      long producedToolLastDay,
      /** 当日 {@code ProductionLedger.inputs} 的商品总量（毫商品；投入被真实征调的读数）。 */
      long inputsLastDay,
      long marketFills,
      long marketImmediateFills,
      long marketCrossRegionFills,
      long marketCrossHexFills,
      /** ★ D-027：{@code report.immediateCrossHexFills()}（区内跨 hex 即时成交笔数）。 */
      long immediateCrossHexFills,
      /** ★ D-027：{@code report.immediateCrossHexLossMilli()}（单 hex 实物损耗合计，毫商品）。 */
      long immediateCrossHexLossMilli,
      /** ★ D-027：{@code report.regulatedTariffMilli()}（区级税费只读读数；production 默认恒 0）。 */
      long regulatedTariffMilli,
      long freightPaid,
      long freightUncollected,
      long operatorLastCycleRevenue,
      long operatorLastCycleCost,
      long operatorLastCycleNet,
      long operatorFilledQty,
      long displacedLaborAllocations,
      long displacedOrganizations,
      long merchantFirms,
      long merchantCapacityTotal) {}

  @TempDir Path tempDir;

  @Test
  void realTwelveHexProductionRuntimeRuns3650Ticks() throws Exception {
    MarketReportFeed.clear(RealTwelveHexWorld.MAP_ID);
    EconomyDayFeed.clear(RealTwelveHexWorld.MAP_ID);

    // ── 判据 0：地图 12 格，且 MapCodec 往返逐字段保持 ───────────────────────────────
    GameMap map = RealTwelveHexWorld.map();
    assertThat(map.hexes()).as("真实小地图格数").hasSize(RealTwelveHexWorld.MAP_HEXES);
    assertThat(map.regions()).as("单 Region").hasSize(1);
    assertThat(map.regions().get(RealTwelveHexWorld.REGION).hexes()).hasSize(12);
    long ocean = map.terrainIndex().values().stream().filter("ocean"::equals).count();
    assertThat(ocean).as("保留 1 格海洋").isEqualTo(1L);
    assertCodecRoundTrip(map);

    Path store = Files.createDirectories(tempDir.resolve("real-twelve-store"));
    Path config = RealTwelveHexWorld.writeConfig(tempDir);

    ObjectMapper originalChangesetMapper = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core = shellAlikeCore(store)) {
      core.bootstrapGenesis(RealTwelveHexWorld.state(RealTwelveHexWorld.MAP_ID));

      // ── 判据 1：真实 worldgen 播种（production-runtime）────────────────────────────
      AgentTool tool =
          new WorldgenInitializeTool(
              core, INITIATOR, RealTwelveHexWorld.MAP_ID, config);
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("nation", RealTwelveHexWorld.REGION.value());
      args.put("dryRun", false);
      args.put("army", false);
      args.put("economyProfile", "production-runtime");
      ToolResult seedResult = tool.execute(context(tool, args));
      assertThat(seedResult.success())
          .as("真实 worldgen.initialize 必须成功: %s %s", seedResult.code(), seedResult.message())
          .isTrue();
      JsonNode summary = SimosObjectMapper.create().readTree(seedResult.message());
      System.out.println("[REAL-12][SEED-SUMMARY] " + summary);
      assertThat(summary.path("totalPopulation").asLong())
          .as("播种人口 = 配置的 3,500")
          .isEqualTo(RealTwelveHexWorld.TOTAL_POPULATION);
      assertThat(summary.path("cityCount").asInt()).as("至少 1 城").isPositive();
      assertThat(summary.path("hexCount").asInt())
          .as("生成器把人口放到 11 个陆地格")
          .isEqualTo(RealTwelveHexWorld.LAND_HEXES);

      long revision = summary.path("revision").asLong();
      SimulationState seeded = core.replay(new StateRef(MAIN, new RevisionId(revision)));
      assertSeededState(seeded);

      // ── 判据 1b（D-027）：生产路径的 MarketTopology 只有一个区 ─────────────────────
      EconomyData seededEconomy = CompactThreeNationsWorld.economyOf(seeded);
      MarketTopology seededTopology = MarketTopologyBookTestAccess.topologyOf(seeded);
      System.out.println(
          "[REAL-12][TOPOLOGY-SINGLE] tick0 regions="
              + seededTopology.regions().size()
              + " nodeId="
              + seededTopology.regions().get(0).node().nodeId()
              + " anchor="
              + seededTopology.regions().get(0).anchor()
              + " markets="
              + seededEconomy.markets().keySet());
      assertThat(seededTopology.regions())
          .as("★ D-027：12hex 生产世界只有 1 个 market region")
          .hasSize(1);
      assertThat(seededTopology.regions().get(0).node().nodeId())
          .as("★ 生产路径走 singleRegion 入口")
          .isEqualTo("single-region");
      assertThat(seededTopology.regions().get(0).members())
          .as("★ 单区成员 = 全部有市场的 hex")
          .containsExactlyInAnyOrderElementsOf(seededEconomy.markets().keySet());

      // ── 判据 2：0→3650，分段 AdvanceTime（真实 CoreSimos + 真实 participant）────────
      List<Boundary> boundaries = new ArrayList<>();
      Boundary atZero = readBoundary(0L, seeded, Optional.empty(), Optional.empty());
      boundaries.add(atZero);
      printMilestone(atZero);
      System.out.println("[REAL-12][INITIAL] " + compact(atZero));

      // ── D-024 §8.2：tick0 生产方式分布 + 商号 ─────────────────────────────────────────
      Map<ProductionModeId, Long> modeAtZero = modeTotals(atZero);
      System.out.println("[REAL-12][TICK0-MODES] " + modeAtZero);
      assertThat(modeAtZero.getOrDefault(DefaultProductionModes.HANDICRAFT_WORKSHOP, 0L))
          .as("★ tick0 handicraft_workshop 人口 >0（城镇手工业必须进入）")
          .isPositive();
      assertThat(modeAtZero.getOrDefault(DefaultProductionModes.MERCHANT, 0L))
          .as("★ tick0 merchant 人口 >0（城镇商户必须进入）")
          .isPositive();
      assertThat(modeAtZero.getOrDefault(DefaultProductionModes.DISPLACED, 0L))
          .as("★ tick0 displaced 人口 >0（按具名比例播种流民）")
          .isPositive();
      long farmAtZero =
          modeAtZero.getOrDefault(DefaultProductionModes.FAMILY_FARM, 0L)
              + modeAtZero.getOrDefault(DefaultProductionModes.TENANCY_FIXED_KIND, 0L)
              + modeAtZero.getOrDefault(DefaultProductionModes.TENANCY_SHARE, 0L)
              + modeAtZero.getOrDefault(DefaultProductionModes.TENANCY_CASH, 0L)
              + modeAtZero.getOrDefault(DefaultProductionModes.WAGE_FARM, 0L);
      assertThat(farmAtZero).as("★ 农村 farm mode 仍 >0").isPositive();
      assertThat(atZero.merchantFirms()).as("★ tick0 有 merchantFirms").isPositive();
      assertThat(atZero.merchantCapacityTotal()).as("★ tick0 商号运力 >0").isPositive();

      long head = revision;
      long tick = 0L;
      int segment = 0;
      while (tick < TICKS) {
        long to = Math.min(tick + SEGMENT_DAYS, TICKS);
        segment++;
        System.out.println("[REAL-12][ADVANCE] tick=" + tick + "->" + to + " segment=" + segment);
        CommandResult result;
        try {
          result =
              core.submit(
                  new AdvanceTime(
                      "real12-adv-" + segment,
                      "real12-adv-" + segment,
                      INITIATOR,
                      MAIN,
                      new RevisionId(head),
                      new TimeRange(SimosTimestamp.of(tick), Optional.of(SimosTimestamp.of(to)))));
        } catch (RuntimeException e) {
          System.out.println(
              "[REAL-12][BLOCKER] core.submit(core.AdvanceTime) 失败：tick=" + tick + "->" + to);
          System.out.println(
              "[REAL-12][BLOCKER] exception=" + e.getClass().getName() + ": " + e.getMessage());
          Throwable root = e;
          while (root.getCause() != null) {
            root = root.getCause();
          }
          System.out.println(
              "[REAL-12][BLOCKER] root=" + root.getClass().getName() + ": " + root.getMessage());
          for (StackTraceElement element : e.getStackTrace()) {
            if (element.getClassName().startsWith("io.mosire")) {
              System.out.println("[REAL-12][BLOCKER-AT] " + element);
            }
          }
          printPreBlockerDiagnostics(boundaries);
          throw e;
        }
        assertThat(result)
            .as("AdvanceTime %s→%s 必须提交", tick, to)
            .isInstanceOf(CommandResult.Committed.class);
        head++;
        SimulationState after = core.replay(new StateRef(MAIN, new RevisionId(head)));
        tick = after.meta().timestamp().tick();
        assertThat(tick).as("推进后的世界日").isEqualTo(to);
        Optional<ProductionLedger> ledger = EconomyDayFeed.last(RealTwelveHexWorld.MAP_ID, tick);
        Optional<MarketReport> report = MarketReportFeed.last(RealTwelveHexWorld.MAP_ID, tick);
        Boundary boundary = readBoundary(tick, after, ledger, report);
        Boundary previous = boundaries.get(boundaries.size() - 1);
        boundaries.add(boundary);
        printMilestone(boundary);
        // ★ 第三轮诊断（测试代理）：主判据仍红时，回答“城市人口是迁出还是饿死”——按 economy 侧 urban 家户
        //   聚合 cycle births/deaths，并把残差 = Δpop − births + deaths 单独打印（残差 <0 才说明发生了迁出）。
        printUrbanDecomposition(previous, boundary);
      }

      Boundary initial = boundaries.get(0);
      Boundary last = boundaries.get(boundaries.size() - 1);
      assertThat(last.tick()).isEqualTo(TICKS);

      // ── D-025：城市人口允许下降/归零；urbanization 是派生读数，不设城市维持阈值 ───────────
      System.out.println(
          "[REAL-12][URBAN-VERDICT] economyUrban "
              + initial.economyUrbanPopulation()
              + "->"
              + last.economyUrbanPopulation()
              + " urbanizationInitialPermille="
              + urbanizationPermille(initial)
              + " urbanizationFinalPermille="
              + urbanizationPermille(last)
              + "（派生量 = urban/(rural+urban)；本批不设 ≥50% 硬判据，D-025）");

      // ── 判据 3：真实读数与守恒 ─────────────────────────────────────────────────────
      MigrationStats migration = migrationStats(boundaries);
      System.out.println("[REAL-12][MIGRATION] " + migration);
      long actualMerges = migration.mergeSignals();
      long actualCreations = migration.creations();
      long actualPopulationZeroed = migration.populationZeroed();
      System.out.println(
          "[REAL-12][EVENTS] merges="
              + actualMerges
              + " creations="
              + actualCreations
              + " extinctions="
              + migration.extinctions()
              + " populationZeroed="
              + actualPopulationZeroed
              + " displacedPeak="
              + migration.displacedPeak()
              + " displacedLast="
              + migration.displacedLast()
              + " maxShell="
              + migration.maxShell()
              + " maxDebt="
              + migration.maxDebt()
              + " maxUnmetNeed="
              + migration.maxUnmetNeed());

      // 人口未丢失：social 与 economy 两侧同源。
      assertThat(last.population()).as("终局 total class population > 0").isPositive();
      assertThat(last.population()).as("终局人口数量级仍在真实世界量级").isLessThan(1_000_000L);

      // 货币按币种守恒（迁移不产生 FX）；允许 GOV 发行/回笼则为 0（本世界没有政府参与）。
      assertThat(last.moneyByCurrency())
          .as("D-023：全币种随迁不做 FX，终局逐币种货币 == 初始")
          .isEqualTo(initial.moneyByCurrency());

      // 资产按 kind 守恒（可迁移资产只换 owner/hex，不凭空增减）。
      assertThat(last.assetByKind())
          .as("D-023：资产随迁守恒，终局按 AssetKind == 初始")
          .isEqualTo(initial.assetByKind());

      // 债务无负值；允许因利息/资本化增长。
      assertThat(last.debtTotal()).as("债务本金不得为负").isNotNegative();
      assertThat(last.debtContracts()).as("终局债务合同数 >= 0").isNotNegative();

      // ★ D-022（设计 §8.2 口径修正）：只对 mode / ProductionOrganization.modeId / ProductionUnit.modeKey 的
      //   原地改写报违规；同 mode 的 ClassStanding.currentPositionId 变化（债务驱动的阶层下落等）单独计数、
      //   不作为违规。
      D022Scan d022 = d022Scan(boundaries);
      System.out.println(
          "[REAL-12][D022] violations="
              + d022.violations().size()
              + " sameModePositionChanges="
              + d022.sameModePositionChanges().size()
              + " first20PositionChanges="
              + d022.sameModePositionChanges().stream().limit(20L).toList());
      assertThat(d022.violations())
          .as("D-022：mode / organization.modeId / unit.modeKey 不得原地改写")
          .isEmpty();
      // 允许的单独读数：同一 mode 内 position 变化（例如负债驱动的阶层下落）。
      assertThat(d022.sameModePositionChanges())
          .as("同 mode 位置变化作为单独读数记录（允许非空）")
          .isNotNull();

      // D-023：DISPLACED 无自动劳动配额/组织。
      assertThat(last.displacedLaborAllocations())
          .as("D-023：DISPLACED 不得挂自动劳动配额")
          .isZero();
      assertThat(last.displacedOrganizations())
          .as("D-023：DISPLACED 不得有自动生产组织")
          .isZero();

      // 真实生产/消费/市场/损耗：至少在一个采样边界上出现。
      long maxProduced = 0L;
      long maxInputs = 0L;
      long maxConsumed = 0L;
      long maxBirths = 0L;
      long maxDeaths = 0L;
      long maxMarketFills = 0L;
      long maxCrossHex = 0L;
      long maxCrossRegion = 0L;
      long maxTransit = 0L;
      long maxFreight = 0L;
      long maxFreightPaid = 0L;
      long maxMerchantFirms = 0L;
      long maxImmediateCrossHexFills = 0L;
      long maxImmediateCrossHexLossMilli = 0L;
      long maxRegulatedTariffMilli = 0L;
      for (Boundary b : boundaries) {
        maxProduced =
            Math.max(maxProduced, b.producedGrainLastDay() + b.producedClothLastDay() + b.producedFiberLastDay() + b.producedToolLastDay());
        maxConsumed = Math.max(maxConsumed, b.consumed());
        maxInputs = Math.max(maxInputs, b.inputsLastDay());
        maxBirths = Math.max(maxBirths, b.births());
        maxDeaths = Math.max(maxDeaths, b.deaths());
        maxMarketFills = Math.max(maxMarketFills, b.marketFills());
        maxCrossHex = Math.max(maxCrossHex, b.marketCrossHexFills());
        maxCrossRegion = Math.max(maxCrossRegion, b.marketCrossRegionFills());
        maxTransit = Math.max(maxTransit, b.grainInTransit() + b.clothInTransit());
        maxFreight = Math.max(maxFreight, b.freightPaid() + b.freightUncollected());
        maxFreightPaid = Math.max(maxFreightPaid, b.freightPaid());
        maxMerchantFirms = Math.max(maxMerchantFirms, b.merchantFirms());
        maxImmediateCrossHexFills =
            Math.max(maxImmediateCrossHexFills, b.immediateCrossHexFills());
        maxImmediateCrossHexLossMilli =
            Math.max(maxImmediateCrossHexLossMilli, b.immediateCrossHexLossMilli());
        maxRegulatedTariffMilli = Math.max(maxRegulatedTariffMilli, b.regulatedTariffMilli());
      }
      System.out.println(
          "[REAL-12][REAL-ACTIVITY] maxProducedLastDay="
              + maxProduced
              + " maxInputsLastDay="
              + maxInputs
              + " maxConsumedCycle="
              + maxConsumed
              + " maxBirthsCycle="
              + maxBirths
              + " maxDeathsCycle="
              + maxDeaths
              + " maxMarketFills="
              + maxMarketFills
              + " maxCrossHexFills="
              + maxCrossHex
              + " maxCrossRegionFills="
              + maxCrossRegion
              + " maxImmediateCrossHexFills="
              + maxImmediateCrossHexFills
              + " maxImmediateCrossHexLossMilli="
              + maxImmediateCrossHexLossMilli
              + " maxTransit="
              + maxTransit
              + " maxFreight="
              + maxFreight
              + " maxFreightPaid="
              + maxFreightPaid
              + " maxRegulatedTariffMilli="
              + maxRegulatedTariffMilli
              + " maxMerchantFirms="
              + maxMerchantFirms);
      assertThat(maxProduced).as("真实生产（GRAIN/CLOTH/FIBER/TOOL 至少一类）").isPositive();
      assertThat(maxInputs)
          .as("投入读数只记录（本夹具的 120 天采样边界恰好是收获/关账日，ProductionLedger.inputs 可为 0；"
              + "投入消费已并入 FlowRow.consumed，后者 >0 硬判）")
          .isNotNegative();
      assertThat(maxConsumed).as("★ 真实消费读数非空（FlowRow.consumed 含日耗与生产投入）").isPositive();
      assertThat(maxBirths).as("★ 出生读数非空（周期 births >0 至少一次）").isPositive();
      assertThat(maxDeaths).as("★ 死亡读数非空（周期 deaths >0 至少一次）").isPositive();
      assertThat(maxMarketFills).as("真实市场成交").isPositive();
      assertThat(maxCrossHex).as("跨格市场成交（from != to）").isPositive();
      assertThat(maxImmediateCrossHexFills)
          .as("★ D-027：区内跨 hex 即时成交笔数 >0")
          .isPositive();
      assertThat(maxImmediateCrossHexLossMilli)
          .as("★ D-027：单 hex 实物损耗读数 >0（区内跨格成交套损耗）")
          .isPositive();
      assertThat(maxRegulatedTariffMilli)
          .as("本批 production 路径默认 regulation 无税费 ⇒ 税费读数恒 0")
          .isZero();
      assertThat(maxMerchantFirms)
          .as("★ 设计 §8.2：merchantFirms 至少在一个边界非空")
          .isPositive();
      assertThat(last.merchantFirms()).as("★ 终局 merchantFirms 仍存在").isPositive();

      // ★★ D-027：单区 + 跨区暂缓 ⇒ 没有跨区 lane/在途/承运费。这三个零值是**结构必然**，
      //   把它们钉死可防止“跨区成交了却没运费”这类真缺陷被静默；本批不要求 freightPaid>0 / shipments>0。
      System.out.println(
          "[REAL-12][TOPOLOGY] socialCities="
              + atZero.cities()
              + " markets="
              + atZero.markets()
              + " singleRegion=true"
              + " maxCrossRegion="
              + maxCrossRegion
              + " maxTransit="
              + maxTransit
              + " maxFreightPaid="
              + maxFreightPaid
              + " maxFreight="
              + maxFreight
              + " merchantFirms="
              + last.merchantFirms());
      assertThat(maxCrossRegion)
          .as("★ D-027：单区世界不得出现跨区在途成交（跨区暂缓）")
          .isZero();
      assertThat(maxTransit)
          .as("★ D-027：单区无跨区 lane ⇒ shipments 结构性为 0")
          .isZero();
      assertThat(maxFreight)
          .as("★ D-027：单区 freight 结构性为 0（钱不因无承运人而蒸发）")
          .isZero();
      assertThat(maxFreightPaid)
          .as("★ D-027：单区 freightPaid 结构性为 0（§8.2 freight 判据在本批不可达，具名）")
          .isZero();
      assertThat(last.shipments()).as("★ D-027：终局 shipments 也结构性为 0").isZero();

      // 自然迁移/合并/新建：D-025 后只要求“迁移发生过”（合并或新建至少一次）；
      // populationZeroed/maxShell 是读数（允许为 0），不强制归零/消亡。
      assertThat(actualMerges + actualCreations)
          .as("★ 自然迁移至少一次（合并或新建目标家户）")
          .isPositive();
      System.out.println(
          "[REAL-12][EVENTS-D025] populationZeroed="
              + actualPopulationZeroed
              + " maxShell="
              + migration.maxShell()
              + "（D-025：城市/家户可下降/归零；只记录，不强制 >0）");
      assertThat(actualPopulationZeroed).as("populationZeroed 只记录（D-025 不强制 >0）").isNotNegative();
      assertThat(migration.maxShell()).as("壳户读数只记录（D-025 不强制 >0）").isNotNegative();
      assertThat(migration.displacedPeak()).as("流民峰值 >0（D-023 播种流民）").isPositive();
      assertThat(migration.displacedLast()).as("流民峰值后仍存在（允许不增长失控）").isNotNegative();
      assertThat(migration.maxDebt()).as("债务压力读数（本金 >0）").isPositive();
      assertThat(migration.maxUnmetNeed()).as("断粮/未满足需求压力读数 >0").isPositive();

      // 无负值逐表扫一遍（终局），并在终局复读生产路径的单区拓扑。
      SimulationState finalState = core.replay(new StateRef(MAIN, new RevisionId(head)));
      assertNoNegativeReadings(finalState);
      EconomyData finalEconomy = CompactThreeNationsWorld.economyOf(finalState);
      MarketTopology finalTopology = MarketTopologyBookTestAccess.topologyOf(finalState);
      assertThat(finalTopology.regions()).as("★ D-027：3650 tick 后仍是单区").hasSize(1);
      assertThat(finalTopology.regions().get(0).node().nodeId())
          .as("★ 终局仍是 singleRegion 入口")
          .isEqualTo("single-region");
      assertThat(finalTopology.regions().get(0).members())
          .as("★ 终局单区成员 = 全部有市场的 hex")
          .containsExactlyInAnyOrderElementsOf(finalEconomy.markets().keySet());

      System.out.println("[REAL-12][FINAL] " + compact(last));
      System.out.println("[REAL-12][DONE] ticks=" + TICKS + " revisions=" + head);

      // ── D-025：城市可饥荒归零；urbanization 是派生印刷读数，不设维持阈值 ─────────────
      System.out.println(
          "[REAL-12][URBAN] economyInitial="
              + atZero.economyUrbanPopulation()
              + " economyFinal="
              + last.economyUrbanPopulation()
              + " urbanizationInitialPermille="
              + urbanizationPermille(atZero)
              + " urbanizationFinalPermille="
              + urbanizationPermille(last)
              + " socialInitial="
              + atZero.urbanPopulation()
              + " socialFinal="
              + last.urbanPopulation());
      assertThat(atZero.economyUrbanPopulation()).as("初始 economy 城市人口 >0（播种城市）").isPositive();
      assertThat(last.economyUrbanPopulation()).as("D-025：终局城市人口允许下降/归零，只要求非负").isNotNegative();
      assertThat(last.economyRuralPopulation()).as("D-025：终局农村人口非负").isNotNegative();
      // §9.1 已知缺口：social 侧批次不随经济迁移拆合，这里只记录偏差，不当作失败。
      System.out.println(
          "[REAL-12][SOCIAL-URBAN-DRIFT] socialUrban "
              + atZero.urbanPopulation()
              + "->"
              + last.urbanPopulation()
              + "（P8/P9 未接，设计 §9.1 具名缺口）");
    } finally {
      restoreChangeSetMapper(originalChangesetMapper);
    }
  }

  /**
   * ★★ <b>追加诊断（非验收；用于主判据仍红时的"新增证据"）</b>：真实 12hex 跑完第 1 个周期（tick=120）后，
   * 用重放出来的**当天工作副本** {@code economy.productionOrganizations()} 再跑一次
   * {@link ModeMigrationPolicy#plan}，打印：
   *
   * <ul>
   *   <li>工作副本里有多少组织、claimed 份额有多少（验证 A2 修复确实有输入）；
   *   <li>城市源户仍在被抽往哪些 (targetMode, targetHex)、各自人口；
   *   <li>抽水来自 A 规则（1000‰）还是 10‰ 基线（{@link ModeMigrationPolicy.MigrationMove#reason()}）。
   * </ul>
   *
   * <p>两种需求簿口径都跑：① 逐日推进 120 天累积的 {@link MarketReport} 列表（最接近执行期
   * {@code profitCycle.marketReports()} 的口径）；② 只带最后一天报告的退化口径（对照）。用途是回答
   * "谁在抽、哪个目标仍有正权重、谁占主导"，读数已在报告里逐条贴出。它仍是<b>边界重算</b>，不是执行期
   * 原计划的逐字重放。
   */
  @Test
  void migrationPlanProbeAfterFirstCycleClose() throws Exception {
    MarketReportFeed.clear(RealTwelveHexWorld.MAP_ID);
    EconomyDayFeed.clear(RealTwelveHexWorld.MAP_ID);
    Path store = Files.createDirectories(tempDir.resolve("real-twelve-probe-store"));
    Path config = RealTwelveHexWorld.writeConfig(tempDir);
    ObjectMapper originalChangesetMapper = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core = shellAlikeCore(store)) {
      core.bootstrapGenesis(RealTwelveHexWorld.state(RealTwelveHexWorld.MAP_ID));
      AgentTool tool =
          new WorldgenInitializeTool(core, INITIATOR, RealTwelveHexWorld.MAP_ID, config);
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("nation", RealTwelveHexWorld.REGION.value());
      args.put("dryRun", false);
      args.put("army", false);
      args.put("economyProfile", "production-runtime");
      ToolResult seedResult = tool.execute(context(tool, args));
      assertThat(seedResult.success()).as("probe 播种必须成功").isTrue();
      JsonNode summary = SimosObjectMapper.create().readTree(seedResult.message());
      long revision = summary.path("revision").asLong();

      long head = revision;
      // 逐日推进 120 天，把每个交易日的 MarketReport 收齐（接近执行期 profitCycle.marketReports()）。
      List<MarketReport> cycleReports = new ArrayList<>();
      for (long day = 1L; day <= SEGMENT_DAYS; day++) {
        CommandResult step =
            core.submit(
                new AdvanceTime(
                    "real12-probe-" + day,
                    "real12-probe-" + day,
                    INITIATOR,
                    MAIN,
                    new RevisionId(head),
                    new TimeRange(
                        SimosTimestamp.of(day - 1L), Optional.of(SimosTimestamp.of(day)))));
        assertThat(step).as("probe: 推进到 day=" + day).isInstanceOf(CommandResult.Committed.class);
        head++;
        MarketReportFeed.last(RealTwelveHexWorld.MAP_ID, day).ifPresent(cycleReports::add);
      }
      SimulationState after = core.replay(new StateRef(MAIN, new RevisionId(head)));

      EconomyData economy = CompactThreeNationsWorld.economyOf(after);
      ActorData actor = CompactThreeNationsWorld.actorOf(after);
      AccountSession accounts = OwnershipBooks.loadAccountSession(economy, actor);
      Map<ProductionOrganizationId, ProductionOrganization> organizations =
          new LinkedHashMap<>(economy.productionOrganizations());
      List<MarketReport> lastDayReports =
          cycleReports.isEmpty() ? List.of() : List.of(cycleReports.get(cycleReports.size() - 1));
      ModeMigrationPolicy.MigrationPlan planCycle =
          probePlan(economy, organizations, accounts, SEGMENT_DAYS, cycleReports);
      ModeMigrationPolicy.MigrationPlan planLastDay =
          probePlan(economy, organizations, accounts, SEGMENT_DAYS, lastDayReports);

      long urbanHouseholds = 0L;
      for (ClassRow row : economy.classes().values()) {
        if (row.view().residence() == ResidenceKind.URBAN) {
          urbanHouseholds++;
        }
      }
      // 注：claimedAssetShares(...) 是 economy 包内可见；app 侧测试只做同义的只读汇总，用于诊断读数。
      long claimedShareCount =
          organizations.values().stream()
              .flatMap(organization -> organization.assetSources().stream())
              .distinct()
              .count();
      long[] urbanCycle = urbanMoveTotal(economy, planCycle);
      long[] urbanLastDay = urbanMoveTotal(economy, planLastDay);
      System.out.println(
          "[REAL-12][PROBE] tick=120 orgs="
              + organizations.size()
              + " claimedShares="
              + claimedShareCount
              + " urbanHouseholds="
              + urbanHouseholds
              + " cycleReports="
              + cycleReports.size()
              + " planMoves(cycle)="
              + planCycle.moves().size()
              + " urbanMoves(cycle)="
              + urbanCycle[0]
              + " urbanPop(cycle)="
              + urbanCycle[1]
              + " planMoves(lastDay)="
              + planLastDay.moves().size()
              + " urbanMoves(lastDay)="
              + urbanLastDay[0]
              + " urbanPop(lastDay)="
              + urbanLastDay[1]);

      Map<String, Long> urbanSinkPopulation = new TreeMap<>();
      Map<String, Long> urbanReasonPopulation = new TreeMap<>();
      Map<String, Long> allSourceModePopulation = new TreeMap<>();
      Map<String, Long> allSinkPopulation = new TreeMap<>();
      long urbanMoves = 0L;
      long urbanMovePopulation = 0L;
      int printed = 0;
      for (ModeMigrationPolicy.MigrationMove move : planCycle.moves()) {
        ClassRow sourceRow = economy.classes().get(move.source());
        ClassStanding standing = economy.classStandings().get(move.source());
        String sourceMode = "<none>";
        if (standing != null) {
          ClassPosition position = economy.classPositions().get(standing.currentPositionId());
          if (position != null) {
            sourceMode = position.modeId().value();
          }
        }
        String sink = sourceMode + " -> " + move.targetMode().value() + "@" + move.targetHex();
        allSourceModePopulation.merge(sourceMode, move.population(), Long::sum);
        allSinkPopulation.merge(sink, move.population(), Long::sum);
        boolean urban = sourceRow != null && sourceRow.view().residence() == ResidenceKind.URBAN;
        if (!urban) {
          continue;
        }
        urbanSinkPopulation.merge(sink, move.population(), Long::sum);
        urbanReasonPopulation.merge(
            move.reason() + "(" + move.transferSpeedPerMille() + "‰)", move.population(), Long::sum);
        urbanMoves++;
        urbanMovePopulation += move.population();
        if (printed < 40) {
          printed++;
          System.out.println(
              "[REAL-12][PROBE][MOVE] "
                  + move.source()
                  + " ["
                  + sourceMode
                  + "] -> "
                  + move.target()
                  + " targetMode="
                  + move.targetMode().value()
                  + "@"
                  + move.targetHex()
                  + " reason="
                  + move.reason()
                  + " speed="
                  + move.transferSpeedPerMille()
                  + "‰ pop="
                  + move.population());
        }
      }
      System.out.println(
          "[REAL-12][PROBE][URBAN-TOTAL] moves="
              + urbanMoves
              + " movedPopulation="
              + urbanMovePopulation);
      System.out.println("[REAL-12][PROBE][URBAN-SINK] " + urbanSinkPopulation);
      System.out.println("[REAL-12][PROBE][URBAN-REASON] " + urbanReasonPopulation);
      System.out.println("[REAL-12][PROBE][ALL-SOURCE-MODE] " + allSourceModePopulation);
      System.out.println("[REAL-12][PROBE][ALL-SINK] " + allSinkPopulation);

      // ── ★ 第三轮诊断（测试代理）：迁移原因、目标雇主/闲置资产、urban 无迁移户的当前预期读数。────────
      Map<String, Long> allReasonPopulation = new TreeMap<>();
      for (ModeMigrationPolicy.MigrationMove move : planCycle.moves()) {
        allReasonPopulation.merge(
            move.reason() + "(" + move.transferSpeedPerMille() + "‰)", move.population(), Long::sum);
      }
      System.out.println("[REAL-12][PROBE][ALL-REASON] " + allReasonPopulation);

      // 说明：claimedAssetShares(...) 是 economy 包内可见；这里按同一谓词做只读汇总（org 引用 ∪ 在产 unit
      // 占用；不改变测试判据，只服务诊断）。
      Set<AssetShareId> claimedForProbe = claimedByWorkingCopy(economy);
      Set<String> targetKeys = new LinkedHashSet<>();
      for (ModeMigrationPolicy.MigrationMove move : planCycle.moves()) {
        targetKeys.add(move.targetMode().value() + "@" + move.targetHex());
      }
      for (String targetKey : targetKeys) {
        int at = targetKey.lastIndexOf('@');
        String hexKey = targetKey.substring(at + 1);
        long employerUnits = 0L;
        long employerCapacity = 0L;
        List<String> employerDetail = new ArrayList<>();
        for (ProductionUnit unit : economy.units().values()) {
          if (!IndustryHexKeys.hexKeyOf(unit.industry()).orElse("").equals(hexKey)) {
            continue;
          }
          var industry = economy.industries().get(unit.industry());
          long scale =
              industry == null
                  ? 0L
                  : ProductionUnitBook.capacityScaleOf(unit, industry, economy.assetShares());
          employerUnits++;
          employerCapacity += scale;
          if (employerDetail.size() < 6) {
            employerDetail.add(unit.id().value() + "(scale=" + scale + ",op=" + unit.operator().id() + ")");
          }
        }
        long idleShares = 0L;
        for (AssetShare share : economy.assetShares().values()) {
          if (share.quantity() <= 0L
              || !share.operator().equals(share.owner())
              || claimedForProbe.contains(share.id())) {
            continue;
          }
          if (IndustryHexKeys.hexKeyOf(share.industry()).orElse("").equals(hexKey)) {
            idleShares++;
          }
        }
        System.out.println(
            "[REAL-12][PROBE][TARGET] target="
                + targetKey
                + " employerUnits="
                + employerUnits
                + " employerCapacityScaleSum="
                + employerCapacity
                + " idleSharesAtHex="
                + idleShares
                + " units="
                + employerDetail);
      }

      MarketTopology probeTopology = MarketTopology.singleHex(economy.markets());
      MarketDemandBook.Book probeDemand =
          MarketDemandBook.build(
              cycleReports,
              probeTopology,
              economy.classes(),
              economy.units(),
              economy.industries(),
              economy.assetShares(),
              economy.shipments(),
              accounts,
              economy.markets(),
              SEGMENT_DAYS,
              SEGMENT_DAYS);
      long urbanNoMove = 0L;
      int urbanPrinted = 0;
      for (ClassRow row : economy.classes().values()) {
        if (row.view().residence() != ResidenceKind.URBAN) {
          continue;
        }
        if (planCycle.moves().stream().anyMatch(move -> move.source().equals(row.id()))) {
          continue;
        }
        urbanNoMove++;
        ClassStanding standing = economy.classStandings().get(row.id());
        ClassPosition position =
            standing == null ? null : economy.classPositions().get(standing.currentPositionId());
        ProductionModeId modeId = position == null ? null : position.modeId();
        ExpectedProfitBook.Prospect current =
            modeId == null
                ? null
                : ExpectedProfitBook.prospect(
                    economy,
                    row.id(),
                    modeId,
                    row.view().hex(),
                    null,
                    economy.markets().get(row.view().hex()),
                    probeDemand,
                    economy.assetShares(),
                    claimedForProbe,
                    accounts,
                    economy.units(),
                    economy.relations(),
                    probeTopology,
                    SEGMENT_DAYS);
        if (urbanPrinted < 20) {
          urbanPrinted++;
          System.out.println(
              "[REAL-12][PROBE][URBAN-NO-MOVE] "
                  + row.id()
                  + " mode="
                  + (modeId == null ? "?" : modeId.value())
                  + " hex="
                  + row.view().hex()
                  + " pop="
                  + row.population()
                  + " prospectFeasible="
                  + (current == null ? "?" : current.feasible())
                  + " reason="
                  + (current == null ? "?" : current.reason())
                  + " netPerLaborScaled="
                  + (current == null ? "?" : current.netPerLaborScaled())
                  + " inputCostMilli="
                  + (current == null ? "?" : current.inputCostMilli()));
        }
      }
      System.out.println("[REAL-12][PROBE][URBAN-NO-MOVE-TOTAL] count=" + urbanNoMove);

      assertThat(organizations).as("probe：tick=120 工作副本已有生产组织（A2 修复的 claimed 输入）").isNotEmpty();
      assertThat(claimedShareCount).as("probe：claimed 份额非空").isPositive();
    } finally {
      restoreChangeSetMapper(originalChangesetMapper);
    }
  }

  /** probe 专用：用当前重放状态 + 指定需求报告跑一次 {@link ModeMigrationPolicy#plan}。 */
  private static ModeMigrationPolicy.MigrationPlan probePlan(
      EconomyData economy,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      AccountSession accounts,
      long day,
      List<MarketReport> reports) {
    return ModeMigrationPolicy.plan(
        economy,
        organizations,
        economy.units(),
        economy.classes(),
        economy.classStandings(),
        economy.assetShares(),
        economy.relations(),
        economy.allocations(),
        economy.markets(),
        economy.debtContracts(),
        accounts,
        new OrganizationProfitBook.Book(Map.of(), Map.of(), Map.of()),
        day,
        MarketTopology.singleHex(economy.markets()),
        reports);
  }

  /** probe 专用：返回 {城市源户迁移条数, 迁移人口}。 */
  private static long[] urbanMoveTotal(
      EconomyData economy, ModeMigrationPolicy.MigrationPlan plan) {
    long moves = 0L;
    long population = 0L;
    for (ModeMigrationPolicy.MigrationMove move : plan.moves()) {
      ClassRow row = economy.classes().get(move.source());
      if (row != null && row.view().residence() == ResidenceKind.URBAN) {
        moves++;
        population += move.population();
      }
    }
    return new long[] {moves, population};
  }

  /**
   * ★ 第三轮诊断用：按 {@code ModeMigrationPolicy.claimedAssetShares(organizations, units, shares)} 的同一谓词
   * 做只读汇总（app 侧看不到 economy 包内方法）：org.assetSources ∪ 在产 unit 占用（同 industry、同 operator、
   * quantity&gt;0）。只服务诊断输出，不参与任何验收判据。
   */
  private static Set<AssetShareId> claimedByWorkingCopy(EconomyData economy) {
    Set<AssetShareId> claimed = new LinkedHashSet<>();
    for (ProductionOrganization organization : economy.productionOrganizations().values()) {
      claimed.addAll(organization.assetSources());
    }
    for (ProductionUnit unit : economy.units().values()) {
      for (AssetShare share : economy.assetShares().values()) {
        if (share.quantity() > 0L
            && share.industry().equals(unit.industry())
            && share.operator().equals(unit.operator())) {
          claimed.add(share.id());
        }
      }
    }
    return claimed;
  }

  // ── 播种断言 ─────────────────────────────────────────────────────────────────────────

  private static void assertSeededState(SimulationState seeded) {
    EconomyData economy = CompactThreeNationsWorld.economyOf(seeded);
    SocialData social = CompactThreeNationsWorld.socialOf(seeded);
    ActorData actor = CompactThreeNationsWorld.actorOf(seeded);
    assertThat(economy.meta()).as("economy.meta 已激活").isPresent();
    assertThat(economy.meta().orElseThrow().rulesVersion())
        .as("★ D-024 §3.5/§3.6：production-runtime 种子必须写 seven-hex-v2")
        .isEqualTo(EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2);
    assertThat(economy.meta().orElseThrow().isCurrentRuntimeVersion())
        .as("★ V2 版本门：新档必须被当前版本判据接受")
        .isTrue();
    assertThat(economy.classFirst().isEmpty())
        .as("production-runtime: classFirst 为空")
        .isTrue();
    assertThat(economy.modes()).as("modes 非空").isNotEmpty();
    assertThat(economy.modes().size()).as("默认 mode 目录 >= 7").isGreaterThanOrEqualTo(7);
    assertThat(economy.classStructures()).as("classStructures 非空").isNotEmpty();
    assertThat(economy.classPositions()).as("classPositions 非空").isNotEmpty();
    assertThat(economy.classStandings()).as("classStandings 非空").isNotEmpty();
    assertThat(economy.assetRules()).as("assetRules 非空").isNotEmpty();
    assertThat(economy.industries()).as("industries 非空").isNotEmpty();
    assertThat(economy.units()).as("units 非空").isNotEmpty();
    assertThat(economy.assetShares()).as("assetShares 非空").isNotEmpty();
    assertThat(economy.allocations()).as("allocations 非空").isNotEmpty();
    assertThat(economy.laborSupply()).as("laborSupply 非空").isNotEmpty();
    assertThat(economy.markets()).as("markets 非空").isNotEmpty();
    // productionOrganizations 由生产运行时在关账/结算中从 units 物化，不在创世载荷里；留待推进后读。
    // ★ D-024 §3.5：merchantFirms 现在必须由 seeder 播种非空（城市格商号），否则真实运费路径退化。
    assertThat(economy.merchantFirms())
        .as("★ D-024：production-runtime 播种必须带非空 merchantFirms")
        .isNotEmpty();
    assertThat(economy.merchantFirms().values())
        .as("商号运力必须 >0")
        .allMatch(firm -> firm.capacityPerRound() > 0L);
    assertThat(economy.assetShares().values())
        .as("★ D-024：商号必须有真实运力资产（CATTLE/SHIP）")
        .anyMatch(
            share ->
                (share.asset() == AssetKind.CATTLE || share.asset() == AssetKind.SHIP)
                    && share.quantity() > 0L);
    assertThat(social.groups()).as("social 人口批次非空").isNotEmpty();
    assertThat(actor.accounts()).as("actor 家户账非空").isNotEmpty();

    long socialPopulation = 0L;
    for (PopulationGroup group : social.groups().values()) {
      socialPopulation += group.count();
    }
    assertThat(socialPopulation)
        .as("social 人口总量")
        .isEqualTo(RealTwelveHexWorld.TOTAL_POPULATION);
    assertThat(CompactThreeNationsWorld.readings(seeded).get("totalPopulation"))
        .as("economy classes 人口 == 配置人口")
        .isEqualTo(RealTwelveHexWorld.TOTAL_POPULATION);
    System.out.println("[REAL-12][SEED-READINGS] " + CompactThreeNationsWorld.readings(seeded));
    assertNoNegativeReadings(seeded);
  }

  private static void assertCodecRoundTrip(GameMap map) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    io.mosire.simos.map.MapSnapshot snapshot =
        new io.mosire.simos.map.MapSnapshot(ref, SimosTimestamp.of(0L), map);
    MapCodec codec = new MapCodec();
    String json = codec.encodeSnapshot(snapshot);
    io.mosire.simos.map.MapSnapshot decoded =
        (io.mosire.simos.map.MapSnapshot) codec.decodeSnapshot(json);
    assertThat(decoded.map()).as("MapCodec 往返逐字段保持").isEqualTo(map);
  }

  // ── 读数（真实状态 + 真实进程内账本/市场报告）──────────────────────────────────────────

  private static Boundary readBoundary(
      long tick,
      SimulationState state,
      Optional<ProductionLedger> ledger,
      Optional<MarketReport> report) {
    EconomyData economy = CompactThreeNationsWorld.economyOf(state);
    SocialData social = CompactThreeNationsWorld.socialOf(state);
    ActorData actor = CompactThreeNationsWorld.actorOf(state);

    Map<HouseholdId, Long> populationByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, ResidenceKind> residenceByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, ProductionModeId> modeByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, String> positionByHousehold = new LinkedHashMap<>();
    for (ClassStanding standing : economy.classStandings().values()) {
      HouseholdId household = standing.householdId();
      ClassRow row = economy.classes().get(household);
      if (row != null) {
        populationByHousehold.put(household, row.population());
        residenceByHousehold.put(household, row.view().residence());
      }
      ClassPosition position = economy.classPositions().get(standing.currentPositionId());
      if (position != null) {
        modeByHousehold.put(household, position.modeId());
        positionByHousehold.put(household, position.id().value());
      }
    }
    for (ClassRow row : economy.classes().values()) {
      populationByHousehold.putIfAbsent(row.id(), row.population());
      residenceByHousehold.putIfAbsent(row.id(), row.view().residence());
    }

    long displacedHouseholds = 0L;
    long displacedPopulation = 0L;
    for (Map.Entry<HouseholdId, ProductionModeId> entry : modeByHousehold.entrySet()) {
      if (DefaultProductionModes.DISPLACED.equals(entry.getValue())) {
        displacedHouseholds++;
        displacedPopulation += populationByHousehold.getOrDefault(entry.getKey(), 0L);
      }
    }

    Map<HouseholdId, Long> birthsByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> deathsByHousehold = new LinkedHashMap<>();
    long unmetNeed = 0L;
    long births = 0L;
    long deaths = 0L;
    long consumed = 0L;
    long newBorrowing = 0L;
    long repaid = 0L;
    long capitalizedArrears = 0L;
    for (FlowRow flow : economy.flows().values()) {
      birthsByHousehold.put(flow.id(), flow.births());
      deathsByHousehold.put(flow.id(), flow.deaths());
      births += flow.births();
      deaths += flow.deaths();
      for (long value : flow.consumed().values()) {
        consumed += value;
      }
      newBorrowing += flow.newBorrowing();
      repaid += flow.repaid();
      for (long value : flow.unmetNeed().values()) {
        unmetNeed += value;
      }
      for (long value : flow.capitalizedArrears().values()) {
        capitalizedArrears += value;
      }
    }

    Map<String, ProductionModeId> organizationModeById = new LinkedHashMap<>();
    for (ProductionOrganization organization : economy.productionOrganizations().values()) {
      organizationModeById.put(organization.id().value(), organization.modeId());
    }
    Map<String, String> unitModeKeyById = new LinkedHashMap<>();
    for (ProductionUnit unit : economy.units().values()) {
      unitModeKeyById.put(unit.id().value(), unit.modeKey());
    }

    Map<CurrencyId, Long> moneyByCurrency = new LinkedHashMap<>();
    for (GoodsAccount account : actor.accounts().values()) {
      for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
        moneyByCurrency.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }

    Map<AssetKind, Long> assetByKind = new EnumMap<>(AssetKind.class);
    economy.assetShares().values().forEach(share -> assetByKind.merge(share.asset(), share.quantity(), Long::sum));

    long debtTotal = 0L;
    for (DebtContract contract : economy.debtContracts().values()) {
      debtTotal += contract.principal();
    }

    long grainInTransit = 0L;
    long clothInTransit = 0L;
    for (var batch : economy.shipments().values()) {
      if (GRAIN.equals(batch.commodity())) {
        grainInTransit += batch.quantity();
      } else if (CLOTH.equals(batch.commodity())) {
        clothInTransit += batch.quantity();
      }
    }

    long producedGrain = 0L;
    long producedCloth = 0L;
    long producedFiber = 0L;
    long producedTool = 0L;
    long inputsLastDay = 0L;
    long marketFills = 0L;
    long marketImmediate = 0L;
    long marketCrossRegion = 0L;
    long marketCrossHex = 0L;
    long immediateCrossHexFills = 0L;
    long immediateCrossHexLossMilli = 0L;
    long regulatedTariffMilli = 0L;
    long freightPaid = 0L;
    long freightUncollected = 0L;
    if (ledger.isPresent()) {
      ProductionLedger value = ledger.get();
      for (Map<CommodityId, Long> line : value.gross().values()) {
        producedGrain += line.getOrDefault(GRAIN, 0L);
        producedCloth += line.getOrDefault(CLOTH, 0L);
        producedFiber += line.getOrDefault(FIBER, 0L);
        producedTool += line.getOrDefault(TOOL, 0L);
      }
      for (Map<CommodityId, Long> line : value.inputs().values()) {
        for (long quantity : line.values()) {
          inputsLastDay += quantity;
        }
      }
    }
    if (report.isPresent()) {
      MarketReport value = report.get();
      marketFills = value.fills().size();
      marketImmediate = value.immediateFills();
      marketCrossRegion = value.crossRegionFills();
      immediateCrossHexFills = value.immediateCrossHexFills();
      immediateCrossHexLossMilli = value.immediateCrossHexLossMilli();
      regulatedTariffMilli = value.regulatedTariffMilli();
      freightPaid = value.freightPaidMilli();
      freightUncollected = value.freightUncollectedMilli();
      for (MarketReport.Fill fill : value.fills()) {
        if (!fill.from().equals(fill.to())) {
          marketCrossHex++;
        }
      }
    }

    long operatorRevenue = 0L;
    long operatorCost = 0L;
    long operatorNet = 0L;
    long operatorFilled = 0L;
    for (var condition : economy.operatorConditions().values()) {
      operatorRevenue += condition.lastCycleRevenueMilli();
      operatorCost += condition.lastCycleCostMilli();
      operatorNet += condition.lastCycleNetMilli();
      operatorFilled += condition.cycleFilledQty();
    }

    long displacedLaborAllocations = 0L;
    for (LaborAllocation allocation : economy.allocations().values()) {
      ProductionModeId mode = modeByHousehold.get(allocation.household());
      if (DefaultProductionModes.DISPLACED.equals(mode) && allocation.laborMilli() > 0L) {
        displacedLaborAllocations++;
      }
    }
    long displacedOrganizations =
        economy.productionOrganizations().values().stream()
            .filter(org -> DefaultProductionModes.DISPLACED.equals(org.modeId()))
            .count();

    long rural = 0L;
    long urban = 0L;
    for (PopulationGroup group : social.groups().values()) {
      if (io.mosire.simos.social.population.PopulationLots.isUrban(group)) {
        urban += group.count();
      } else {
        rural += group.count();
      }
    }
    // ★ 设计 §9.1：本批 P8/P9 社会侧城乡迁移未接；本轮以 **economy 侧 ClassRow.view().residence()** 为准，
    //   social 侧只作为单独读数记录（可能出现不一致）。
    long economyRural = 0L;
    long economyUrban = 0L;
    for (ClassRow row : economy.classes().values()) {
      if (row.view().residence() == io.mosire.simos.economy.api.cohort.ResidenceKind.URBAN) {
        economyUrban += row.population();
      } else {
        economyRural += row.population();
      }
    }

    long merchantCapacityTotal = 0L;
    for (var firm : economy.merchantFirms().values()) {
      merchantCapacityTotal += firm.capacityPerRound();
    }

    return new Boundary(
        tick,
        sumPopulation(economy),
        rural,
        urban,
        economyRural,
        economyUrban,
        economy.classes().size(),
        social.cities().size(),
        economy.markets().size(),
        Map.copyOf(populationByHousehold),
        Map.copyOf(residenceByHousehold),
        Map.copyOf(modeByHousehold),
        Map.copyOf(positionByHousehold),
        Map.copyOf(birthsByHousehold),
        Map.copyOf(deathsByHousehold),
        Map.copyOf(organizationModeById),
        Map.copyOf(unitModeKeyById),
        displacedPopulation,
        displacedHouseholds,
        shellHouseholds(economy),
        debtTotal,
        economy.debtContracts().size(),
        unmetNeed,
        births,
        deaths,
        consumed,
        newBorrowing,
        repaid,
        capitalizedArrears,
        Map.copyOf(moneyByCurrency),
        Map.copyOf(assetByKind),
        grainInTransit,
        clothInTransit,
        economy.shipments().size(),
        producedGrain,
        producedCloth,
        producedFiber,
        producedTool,
        inputsLastDay,
        marketFills,
        marketImmediate,
        marketCrossRegion,
        marketCrossHex,
        immediateCrossHexFills,
        immediateCrossHexLossMilli,
        regulatedTariffMilli,
        freightPaid,
        freightUncollected,
        operatorRevenue,
        operatorCost,
        operatorNet,
        operatorFilled,
        displacedLaborAllocations,
        displacedOrganizations,
        economy.merchantFirms().size(),
        merchantCapacityTotal);
  }

  // ── 事件/缺口统计 ─────────────────────────────────────────────────────────────────────

  private record MigrationStats(
      long mergeSignals,
      long creations,
      long extinctions,
      long populationZeroed,
      long displacedPeak,
      long displacedLast,
      long maxShell,
      long maxDebt,
      long maxUnmetNeed) {}

  /** 阻塞前已采到的真实读数：事件/守恒/D-022/D-023，全部来自 core.replay 后的状态。 */
  private static void printPreBlockerDiagnostics(List<Boundary> boundaries) {
    Boundary first = boundaries.get(0);
    Boundary last = boundaries.get(boundaries.size() - 1);
    System.out.println("[REAL-12][PRE-BLOCKER][MIGRATION] " + migrationStats(boundaries));
    System.out.println(
        "[REAL-12][PRE-BLOCKER][D022] violations="
            + d022Scan(boundaries).violations()
            + " sameModePositionChanges="
            + d022Scan(boundaries).sameModePositionChanges().size());
    System.out.println(
        "[REAL-12][PRE-BLOCKER][CONSERVATION] moneyInitial="
            + first.moneyByCurrency()
            + " moneyLast="
            + last.moneyByCurrency()
            + " assetsInitial="
            + first.assetByKind()
            + " assetsLast="
            + last.assetByKind()
            + " debtInitial="
            + first.debtTotal()
            + " debtLast="
            + last.debtTotal()
            + " popInitial="
            + first.population()
            + " popLast="
            + last.population());
    System.out.println(
        "[REAL-12][PRE-BLOCKER][D023] displacedLast="
            + last.displacedPopulation()
            + " displacedHouseholds="
            + last.displacedHouseholds()
            + " displacedLaborAllocations="
            + last.displacedLaborAllocations()
            + " displacedOrganizations="
            + last.displacedOrganizations());
  }

  private static MigrationStats migrationStats(List<Boundary> boundaries) {
    long merges = 0L;
    long creations = 0L;
    long extinctions = 0L;
    long zeroed = 0L;
    long displacedPeak = 0L;
    long maxShell = 0L;
    long maxDebt = 0L;
    long maxUnmet = 0L;
    Set<HouseholdId> everSeen = new LinkedHashSet<>();
    for (Boundary b : boundaries) {
      everSeen.addAll(b.populationByHousehold().keySet());
      displacedPeak = Math.max(displacedPeak, b.displacedPopulation());
      maxShell = Math.max(maxShell, b.shellHouseholds());
      maxDebt = Math.max(maxDebt, b.debtTotal());
      maxUnmet = Math.max(maxUnmet, b.unmetNeed());
    }
    for (int i = 1; i < boundaries.size(); i++) {
      Boundary prev = boundaries.get(i - 1);
      Boundary cur = boundaries.get(i);
      for (HouseholdId id : cur.populationByHousehold().keySet()) {
        if (!prev.populationByHousehold().containsKey(id)) {
          creations++;
        }
      }
      for (Map.Entry<HouseholdId, Long> entry : prev.populationByHousehold().entrySet()) {
        HouseholdId id = entry.getKey();
        long before = entry.getValue();
        Long afterValue = cur.populationByHousehold().get(id);
        long after = afterValue == null ? 0L : afterValue;
        if (afterValue == null) {
          extinctions++;
        }
        if (before > 0L && after == 0L) {
          zeroed++;
        }
        if (before > 0L && afterValue != null) {
          long births = cur.birthsByHousehold().getOrDefault(id, 0L);
          long deaths = cur.deathsByHousehold().getOrDefault(id, 0L);
          long netMigration = after - before - births + deaths;
          if (netMigration > 0L) {
            merges++;
          }
        }
      }
    }
    return new MigrationStats(
        merges,
        creations,
        extinctions,
        zeroed,
        displacedPeak,
        boundaries.get(boundaries.size() - 1).displacedPopulation(),
        maxShell,
        maxDebt,
        maxUnmet);
  }

  /**
   * ★ D-022 扫描（设计 §8.2 修正口径）：
   *
   * <ul>
   *   <li>{@code violations} = mode 原地改写 / {@code ProductionOrganization.modeId} 改写 /
   *       {@code ProductionUnit.modeKey} 改写；
   *   <li>{@code sameModePositionChanges} = 家户 mode 未变、但 {@code currentPositionId} 变了——债务驱动的
   *       阶层下落属于这一类，<b>允许</b>，只作单独读数。
   * </ul>
   */
  private record D022Scan(List<String> violations, List<String> sameModePositionChanges) {}

  private static D022Scan d022Scan(List<Boundary> boundaries) {
    List<String> violations = new ArrayList<>();
    List<String> positionChanges = new ArrayList<>();
    for (int i = 1; i < boundaries.size(); i++) {
      Boundary prev = boundaries.get(i - 1);
      Boundary cur = boundaries.get(i);
      for (Map.Entry<HouseholdId, ProductionModeId> entry : prev.modeByHousehold().entrySet()) {
        HouseholdId id = entry.getKey();
        ProductionModeId now = cur.modeByHousehold().get(id);
        if (now != null && !entry.getValue().equals(now)) {
          violations.add(
              "tick=" + cur.tick() + " household " + id.value() + " mode " + entry.getValue().value() + " -> " + now.value());
          continue; // mode 都改了，position 变化归因到 mode 改写，不重复计数
        }
        String beforePosition = prev.positionByHousehold().get(id);
        String afterPosition = cur.positionByHousehold().get(id);
        if (beforePosition != null && afterPosition != null && !beforePosition.equals(afterPosition)) {
          positionChanges.add(
              "tick=" + cur.tick() + " household " + id.value() + " sameModePosition " + beforePosition + " -> " + afterPosition);
        }
      }
      for (Map.Entry<String, ProductionModeId> entry : prev.organizationModeById().entrySet()) {
        ProductionModeId now = cur.organizationModeById().get(entry.getKey());
        if (now != null && !entry.getValue().equals(now)) {
          violations.add(
              "tick=" + cur.tick() + " organization " + entry.getKey() + " mode " + entry.getValue().value() + " -> " + now.value());
        }
      }
      for (Map.Entry<String, String> entry : prev.unitModeKeyById().entrySet()) {
        String now = cur.unitModeKeyById().get(entry.getKey());
        if (now != null && !entry.getValue().equals(now)) {
          violations.add(
              "tick=" + cur.tick() + " unit " + entry.getKey() + " modeKey " + entry.getValue() + " -> " + now);
        }
      }
    }
    return new D022Scan(violations, positionChanges);
  }

  /** 按 mode 汇总边界快照中全部家户人口（tick0 分布与报告读数用）。 */
  private static Map<ProductionModeId, Long> modeTotals(Boundary boundary) {
    Map<ProductionModeId, Long> totals = new TreeMap<>(java.util.Comparator.comparing(ProductionModeId::value));
    for (Map.Entry<HouseholdId, Long> entry : boundary.populationByHousehold().entrySet()) {
      ProductionModeId mode = boundary.modeByHousehold().get(entry.getKey());
      if (mode != null) {
        totals.merge(mode, entry.getValue(), Long::sum);
      }
    }
    return totals;
  }

  // ── 打印 ─────────────────────────────────────────────────────────────────────────────

  /**
   * ★ 第三轮诊断（测试代理）：economy 侧 urban 家户的 cycle 人口变动分解。
   *
   * <p>残差 = 期末 urban pop − 期初同户 urban pop − births + deaths。迁移只改变“哪一户持有这些人”，
   * 不改变总人口 ⇒ 若残差 ≈ 0 而 deaths ≫ births，城市衰退就是“饿死”而不是“迁出”。
   */
  private static void printUrbanDecomposition(Boundary previous, Boundary current) {
    long popBefore = 0L;
    long popAfter = 0L;
    long births = 0L;
    long deaths = 0L;
    long households = 0L;
    for (Map.Entry<HouseholdId, Long> entry : current.populationByHousehold().entrySet()) {
      if (current.residenceByHousehold().get(entry.getKey()) != ResidenceKind.URBAN) {
        continue;
      }
      households++;
      popAfter += entry.getValue();
      popBefore += previous.populationByHousehold().getOrDefault(entry.getKey(), 0L);
      births += current.birthsByHousehold().getOrDefault(entry.getKey(), 0L);
      deaths += current.deathsByHousehold().getOrDefault(entry.getKey(), 0L);
    }
    System.out.println(
        "[REAL-12][URBAN-DECOMP] tick="
            + current.tick()
            + " urbanHouseholds="
            + households
            + " pop="
            + popBefore
            + "->"
            + popAfter
            + " births="
            + births
            + " deaths="
            + deaths
            + " netResidual=Δpop-births+deaths="
            + (popAfter - popBefore - births + deaths)
            + "（残差<0 = 净迁出；deaths 是同期真实死亡）");
  }

  private static void printMilestone(Boundary b) {
    System.out.println(
        "[REAL-12] tick="
            + b.tick()
            + " pop="
            + b.population()
            + " rural="
            + b.ruralPopulation()
            + " urban="
            + b.urbanPopulation()
            + " economyRural="
            + b.economyRuralPopulation()
            + " economyUrban="
            + b.economyUrbanPopulation()
            + " urbanizationPermille="
            + urbanizationPermille(b)
            + " households="
            + b.households()
            + " modes="
            + renderModeTotals(b)
            + " cities="
            + b.cities()
            + " markets="
            + b.markets()
            + " producedGrainLastDay="
            + b.producedGrainLastDay()
            + " producedClothLastDay="
            + b.producedClothLastDay()
            + " consumedCycle="
            + b.consumed()
            + " marketFills="
            + b.marketFills()
            + " crossHexFills="
            + b.marketCrossHexFills()
            + " immediateCrossHexFills="
            + b.immediateCrossHexFills()
            + " immediateCrossHexLossMilli="
            + b.immediateCrossHexLossMilli()
            + " crossRegionFills="
            + b.marketCrossRegionFills()
            + " shipments="
            + b.shipments()
            + " merchantFirms="
            + b.merchantFirms()
            + " freightPaid="
            + b.freightPaid()
            + " freightUncollected="
            + b.freightUncollected()
            + " regulatedTariffMilli="
            + b.regulatedTariffMilli()
            + " debt="
            + b.debtTotal()
            + " displaced="
            + b.displacedPopulation()
            + " shells="
            + b.shellHouseholds()
            + " unmetNeedCycle="
            + b.unmetNeed()
            + " birthsCycle="
            + b.births()
            + " deathsCycle="
            + b.deaths());
  }

  /**
   * ★ D-025：城市化率是<b>派生量</b> {@code urban/(rural+urban)}，不是可存字段；人口为 0 ⇒ 0‰（不猜、不抛）。
   * 这里用 economy 侧 residence 口径（§9.1），与 {@code readBoundary} 的 economyUrban/economyRural 同源。
   */
  private static long urbanizationPermille(Boundary b) {
    long total = b.economyRuralPopulation() + b.economyUrbanPopulation();
    return total <= 0L ? 0L : b.economyUrbanPopulation() * 1000L / total;
  }

  private static String renderModeTotals(Boundary b) {
    Map<ProductionModeId, Long> popByMode =
        new TreeMap<>(java.util.Comparator.comparing(ProductionModeId::value));
    for (Map.Entry<HouseholdId, Long> entry : b.populationByHousehold().entrySet()) {
      ProductionModeId mode = b.modeByHousehold().get(entry.getKey());
      if (mode != null) {
        popByMode.merge(mode, entry.getValue(), Long::sum);
      }
    }
    return popByMode.toString();
  }

  private static Map<String, Object> compact(Boundary b) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("tick", b.tick());
    out.put("population", b.population());
    out.put("households", b.households());
    out.put("modes", renderModeTotals(b));
    out.put("urbanizationPermille", urbanizationPermille(b));
    out.put("debtTotal", b.debtTotal());
    out.put("displaced", b.displacedPopulation());
    out.put("shells", b.shellHouseholds());
    out.put("unmetNeed", b.unmetNeed());
    out.put("consumed", b.consumed());
    out.put("inputs", b.inputsLastDay());
    out.put("moneyByCurrency", b.moneyByCurrency());
    out.put("assetByKind", b.assetByKind());
    out.put("grainInTransit", b.grainInTransit());
    out.put("clothInTransit", b.clothInTransit());
    out.put("shipments", b.shipments());
    out.put("immediateCrossHexFills", b.immediateCrossHexFills());
    out.put("immediateCrossHexLossMilli", b.immediateCrossHexLossMilli());
    out.put("regulatedTariffMilli", b.regulatedTariffMilli());
    return out;
  }

  // ── 通用守恒扫描 ─────────────────────────────────────────────────────────────────────

  private static void assertNoNegativeReadings(SimulationState state) {
    EconomyData economy = CompactThreeNationsWorld.economyOf(state);
    ActorData actor = CompactThreeNationsWorld.actorOf(state);
    SocialData social = CompactThreeNationsWorld.socialOf(state);
    for (ClassRow row : economy.classes().values()) {
      assertThat(row.population()).as("人口不得为负: %s", row.id()).isNotNegative();
      assertThat(row.laborMilli()).as("劳动不得为负: %s", row.id()).isNotNegative();
      assertThat(row.money()).as("行货币不得为负: %s", row.id()).isNotNegative();
    }
    for (GoodsAccount account : actor.accounts().values()) {
      for (long value : account.balances().values()) {
        assertThat(value).as("商品余额不得为负: %s", account.key()).isNotNegative();
      }
      for (long value : account.money().values()) {
        assertThat(value).as("货币余额不得为负: %s", account.key()).isNotNegative();
      }
    }
    for (var share : economy.assetShares().values()) {
      assertThat(share.quantity()).as("资产份额不得为负: %s", share.id()).isNotNegative();
    }
    for (DebtContract debt : economy.debtContracts().values()) {
      assertThat(debt.principal()).as("债务本金不得为负: %s", debt.id()).isNotNegative();
    }
    for (PopulationGroup group : social.groups().values()) {
      assertThat(group.count()).as("批次人口不得为负: %s", group.id()).isNotNegative();
    }
  }

  private static long sumPopulation(EconomyData economy) {
    long total = 0L;
    for (ClassRow row : economy.classes().values()) {
      total += row.population();
    }
    return total;
  }

  private static long shellHouseholds(EconomyData economy) {
    long shells = 0L;
    for (ClassRow row : economy.classes().values()) {
      if (row.population() == 0L) {
        shells++;
      }
    }
    return shells;
  }

  // ── 装配（与 Compact/Shell 同源）────────────────────────────────────────────────────

  /** 只承载注解，方法体永不执行。 */
  abstract static class EconomyMetaDerivedPropertyMixin {
    @JsonIgnore
    abstract boolean isCurrentRuntimeVersion();
  }

  /**
   * ★★ 主程序缺口的**测试侧线格式补丁**（不改 main）：真实 Shell 的 Core 时间线 mapper 是
   * {@code SimosObjectMapper.create()}（{@code Timeline.CHANGESET_MAPPER}），它没摘掉
   * {@link EconomyMeta#isCurrentRuntimeVersion()} 这个派生判据；production-runtime 关账会改 {@code economy.meta}，
   * 于是 Core replay 会被 {@code currentRuntimeVersion} 未知键炸掉（未打补丁时实测 tick 0→120 后
   * {@code core.replay} 即抛 {@code UnrecognizedPropertyException}）。这里给时间线 mapper 补 mixin，
   * 让测试能越过这个缺口继续跑到真正的经济阻塞点；缺口本身在最终报告里如实列出。
   */
  private static ObjectMapper installChangeSetMapperEconomyMetaMixin()
      throws ReflectiveOperationException {
    Field field = Timeline.class.getDeclaredField("CHANGESET_MAPPER");
    field.setAccessible(true);
    ObjectMapper mapper = (ObjectMapper) field.get(null);
    // 静态 final 字段不能替换，只能给这台 mapper 补 mixin；这是测试 JVM 内的进程级线格式补丁。
    mapper.addMixIn(EconomyMeta.class, EconomyMetaDerivedPropertyMixin.class);
    return mapper;
  }

  private static void restoreChangeSetMapper(ObjectMapper original)
      throws ReflectiveOperationException {
    // Jackson 的 mixin 已进入反序列化缓存；这里只做 best-effort 摘除（不影响本次断言）。
    original.addMixIn(EconomyMeta.class, null);
  }

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
    core.register(
        new UnitTimeParticipant(TerrainMovementCost.INSTANCE, RealTwelveHexWorld.MAP_ID));
    core.register(new SdTimeParticipant(RealTwelveHexWorld.MAP_ID));
    core.register(new ClassFirstPopulationEconomyTimeParticipant(RealTwelveHexWorld.MAP_ID));
    return core;
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }
}
