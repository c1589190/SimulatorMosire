package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.ClassFirstPopulationEconomyTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassRow;
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
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.TerrainView;
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
 * ★★ <b>紧凑三国测试世界的验收测试</b>（2026-09-28；<b>R3a 迁 class-first</b>）。
 *
 * <p>★★ <b>R3a 迁移记录</b>：旧版本类注册 {@code PopulationEconomyTimeParticipant} + {@code COMPLETE}
 * profile（旧产业/生产单元/债务生命周期），随旧结算运行时一起删除。现版与 {@code Shell} 同项：注册 {@link
 * ClassFirstPopulationEconomyTimeParticipant}，用 {@code economyProfile=class-first} 初始化三国，一次推进 0 →
 * 120 tick，再读真实数据。旧的两条 720 tick 债务/清算用例（旧家户 DebtContract / AssetShare / LiquidationPolicy 主路径）
 * 随旧结构删除。
 *
 * <pre>
 * 判据 ① 世界规格：3 个 region / 地图 ≥100 格（本夹具 158 格，其中陆地 144）/ 地形 ≥3 种（实际 5 种）+ 河流 + 海岸；
 * 判据 ② 播种后：3 国 / ≥3 城 / 144 个市场 / 5 种商品 / 38 万人口 / 顶层 classFirst 非空，旧生产表（flows/industries/units）恒空；
 * 判据 ③ 0→120 一次推进不抛：classFirst.tick 到 120，Σ池人口 == Σsocial 人口，粮账为正；
 * 判据 ④ 账无负数：全部 actor 商品/货币余额 ≥0，classes 只读投影 == classFirst 家户账户。
 * </pre>
 *
 * <p>★ 打印的 {@code [COMPACT-CLASSFIRST-…]} 就是交付报告里的实测读数（不是"再调一遍生成器"）。
 */
class CompactThreeNationsEconomyTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:compact-three-nations-test";

  /** 一个产业周期 = 120 天。 */
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

  // ── 判据 ②③④：class-first 真播种 + 一次 0→120 + 真实读数 ──────────────────────────────

  @Test
  void threeNationsSeedAndCloseTheirFirstCycle() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("compact-classfirst-store"));
    try (CoreSimos core = shellAlikeCore(store)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));

      List<JsonNode> summaries =
          CompactThreeNationsWorld.initializeNations(
              core, EconomySeeder.FoundationProfile.CLASS_FIRST);
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
      System.out.println("[COMPACT-CLASSFIRST-SEED] " + seedReadings);
      assertThat((long) seedReadings.get("nations")).as("3 国").isEqualTo(3L);
      assertThat((long) seedReadings.get("mapHexes")).isEqualTo(CompactThreeNationsWorld.MAP_HEXES);
      assertThat((long) seedReadings.get("landHexes"))
          .isEqualTo(CompactThreeNationsWorld.LAND_HEXES);
      assertThat((long) seedReadings.get("cities")).isGreaterThanOrEqualTo(3);
      assertThat((long) seedReadings.get("markets"))
          .as("每个有经济状态的格一个市场（144 格）")
          .isEqualTo(CompactThreeNationsWorld.LAND_HEXES);
      assertThat((long) seedReadings.get("commodities")).as("粮/布/纤维/工具/铁 5 种").isEqualTo(5);
      assertThat((long) seedReadings.get("totalPopulation")).isEqualTo(380_000L);
      assertThat((long) seedReadings.get("ruralPopulation")).isEqualTo(282_000L);
      assertThat((long) seedReadings.get("urbanPopulation")).isEqualTo(98_000L);
      assertThat((long) seedReadings.get("lastClosedCycle")).as("还没关过账").isEqualTo(-1L);

      EconomyData seedEconomy = CompactThreeNationsWorld.economyOf(seeded);
      assertThat(seedEconomy.classFirst().isEmpty()).as("class-first 世界必须种出阶层池").isFalse();
      assertNoOldProductionTables(seedEconomy, "seed");

      // ── 0 → 120：一次 AdvanceTime，内部逐日（与 Shell 同一个 class-first 参与者）──
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
      EconomyData economy = CompactThreeNationsWorld.economyOf(after);
      SocialData social = CompactThreeNationsWorld.socialOf(after);
      ActorData books = CompactThreeNationsWorld.actorOf(after);
      Map<String, Object> day120 = CompactThreeNationsWorld.readings(after);
      System.out.println("[COMPACT-CLASSFIRST-120] " + day120);

      assertThat(economy.classFirst().meta().tick()).as("class-first tick 到 120").isEqualTo(120L);
      assertNoOldProductionTables(economy, "tick120");
      assertThat((long) day120.get("nations")).isEqualTo(3);
      assertThat((long) day120.get("cities")).isGreaterThanOrEqualTo(3);
      assertThat((long) day120.get("markets")).isEqualTo(144);
      assertThat((long) day120.get("commodities")).isEqualTo(5);
      assertThat((long) day120.get("totalPopulation")).isPositive();
      assertClassesProjectionMatchesClassfirst(economy, "tick120");
      assertThat(socialPopulation(social))
          .as("Σsocial == Σclassfirst（两侧同源）")
          .isEqualTo(poolPopulation(economy.classFirst()));
      assertThat(poolPopulation(economy.classFirst())).as("池人口 > 0").isPositive();
      assertThat(CompactThreeNationsWorld.grainAccountMilli(books))
          .as("120 天后 actor 账上粮为正")
          .isPositive();

      for (GoodsAccount account : books.accounts().values()) {
        assertThat(account.balances().values())
            .as("商品余额 ≥0：%s", account.key())
            .allSatisfy(value -> assertThat(value).isNotNegative());
        assertThat(account.money().values())
            .as("货币余额 ≥0：%s", account.key())
            .allSatisfy(value -> assertThat(value).isNotNegative());
      }
      for (ClassPool pool : economy.classFirst().classPools().values()) {
        assertThat(pool.population()).as("池人口 ≥0").isNotNegative();
        assertThat(pool.stock(AssetKind.GRAIN)).as("池粮库存 ≥0").isNotNegative();
      }
    }
  }

  // ── 判据辅助 ─────────────────────────────────────────────────────────────────────────

  /** R3a：class-first 生产路径不得写旧生产表（旧结算/旧 seeder 的痕迹）。 */
  private static void assertNoOldProductionTables(EconomyData economy, String tag) {
    assertThat(economy.flows()).as("%s: 不得有旧 FlowRow", tag).isEmpty();
    assertThat(economy.industries()).as("%s: 不得有旧 industries", tag).isEmpty();
    assertThat(economy.relations()).as("%s: 不得有旧 relations", tag).isEmpty();
    assertThat(economy.units()).as("%s: 不得有旧 units", tag).isEmpty();
    assertThat(economy.laborSupply()).as("%s: 不得有旧 laborSupply", tag).isEmpty();
    assertThat(economy.allocations()).as("%s: 不得有旧 allocations", tag).isEmpty();
  }

  /** classes 是 class-first 家户账户的只读投影：逐户 population/laborMilli 必须相等。 */
  private static void assertClassesProjectionMatchesClassfirst(EconomyData economy, String tag) {
    for (HouseholdProductionAccount account : economy.classFirst().householdAccounts().values()) {
      ClassRow row =
          economy
              .classes()
              .get(io.mosire.simos.economy.api.id.HouseholdId.parse(account.householdId()));
      assertThat(row).as("%s: 家户生产账户必须在 classes 投影里: %s", tag, account.householdId()).isNotNull();
      assertThat(row.population())
          .as("%s: classes.population == 家户账户人口: %s", tag, account.householdId())
          .isEqualTo(account.population());
      assertThat(row.laborMilli())
          .as("%s: classes.laborMilli == 家户账户 laborUnits: %s", tag, account.householdId())
          .isEqualTo(account.laborUnits());
    }
  }

  private static long poolPopulation(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.population();
    }
    return total;
  }

  private static long socialPopulation(SocialData social) {
    long total = 0L;
    for (PopulationGroup group : social.groups().values()) {
      total += group.count();
    }
    return total;
  }

  // ────────────────────────────────── 装配（与 Shell 同源）─────────────────────────────

  /**
   * Shell 同源的<b>最小完整装配</b>：6 codec + worldgen 10 handler + 3 时间参与者。★ 经济推进的日循环、出生/死亡、产权落账都在 {@link
   * ClassFirstPopulationEconomyTimeParticipant} 内部 —— 缺它就只能 replay、不能结算。
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
    core.register(new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID));
    return core;
  }
}
