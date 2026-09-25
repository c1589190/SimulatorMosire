package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.RichWorld;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.EconomyTimeParticipant;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.gen.WorldgenConfig;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.worldgen.initialize} 的端到端验收（2026-09-23）：**真世界**（{@link RichWorld} 的 v17levant 真档）+
 * **真引擎**（{@code CoreSimos.bootstrapGenesis} / {@code submitBatch} / {@code replay}）跑一次奥斯特马克侯国的竖切，
 * 并让三国各跑一次一键初始化（含军队编制）。
 *
 * <p>★ 断言值都是**当场从冻结输入算过的字面量**（{@code total=3,070,000}、{@code urbanization=0.08} ⇒ 城市 245,600 / 农村
 * 2,824,400；真档 region 138 格且无 ocean ⇒ 逐格都有农村人口；编制合计 14,800 =
 * 800+500+500+1600+1300+9300+800），不是"再调一遍生成器对拍"。
 */
class WorldgenInitializeToolTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final RevisionId R1 = new RevisionId(1);
  private static final RevisionId R2 = new RevisionId(2);
  private static final String MAP_ID = "Map1";

  /** §十"单位"行：粮 = 1 公斤，商品 id 取 {@code grain}（与 {@code EconomySeeder.COMMODITY_GRAIN} 同源）。 */
  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final String OSTERMARK = "奥斯特马克侯国";
  private static final String OSTERMARK_TAG = "nation:" + OSTERMARK;
  private static final String INITIATOR = "agent:worldgen-test";

  private static final long OSTERMARK_TOTAL = 3_070_000L;
  private static final long OSTERMARK_RURAL = 2_824_400L;
  private static final long OSTERMARK_URBAN = 245_600L;
  private static final int OSTERMARK_HEXES = 138;

  /** 奥斯特马克编制合计：800+500+500+1600+1300+9300+800。 */
  private static final int OSTERMARK_ESTABLISHMENT = 14_800;

  /**
   * 军队编制块在**新库首跑**里追加的命令条数：1 条 {@code map.UpdateRegion}（真档 tag 是文档式 {@code "Nation"}， 不是 {@code
   * nation:…}）+ 1 {@code sd.CreateNation} + 1 根单位 + 7 兵种 + 1 指挥链 + 1 {@code sd.CreateArmy}。
   */
  private static final int ARMY_COMMANDS_OSTERMARK = 12;

  /** 三国配置的硬值（人口 / 城市化后城市人口 / 真档 region 格数 / 编制合计 / 兵种数 / 平时 / 动员）。 */
  private record NationCase(
      String regionId,
      long population,
      long urban,
      int hexes,
      int establishment,
      int armCount,
      int peacetime,
      int mobilization) {}

  private static final List<NationCase> NATIONS =
      List.of(
          new NationCase("德意志第二帝国", 6_230_000L, 747_600L, 430, 25_500, 9, 20_000, 25_000),
          new NationCase(
              OSTERMARK,
              OSTERMARK_TOTAL,
              OSTERMARK_URBAN,
              OSTERMARK_HEXES,
              14_800,
              7,
              6_500,
              14_800),
          new NationCase("霍赫兰伯国", 2_530_000L, 328_900L, 231, 16_200, 7, 4_300, 16_200));

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  @TempDir Path tempDir;

  // ── 1. dryRun 不写 ────────────────────────────────────────────────────────────────────

  @Test
  void dryRunComputesButWritesNothing() throws IOException {
    try (CoreSimos core = freshCore(dir("dry"))) {
      ToolResult result = execute(tool(core), Map.of("nation", OSTERMARK, "dryRun", true));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode body = JSON.readTree(result.message());
      assertThat(body.get("ruralPopulation").asLong()).isEqualTo(OSTERMARK_RURAL);
      assertThat(body.get("hexCount").asInt()).isEqualTo(OSTERMARK_HEXES);
      assertThat(body.has("revision")).as("dryRun 的摘要不带 revision").isFalse();

      assertThat(core.head(MAIN).orElseThrow().value()).as("dryRun 不得推进 head").isEqualTo(1L);
      SocialData social = socialAt(core, 1);
      assertThat(social.populations()).as("dryRun 不得写人口").isEmpty();
      assertThat(social.cities()).as("dryRun 不得建城").isEmpty();
    }
  }

  // ── 2. ★ 竖切真跑（核心用例）────────────────────────────────────────────────────────────

  @Test
  void ostermarkVerticalSliceLandsExactlyOneRevision() throws IOException {
    Path store = dir("vertical");
    SimulationState state;
    try (CoreSimos core = freshCore(store)) {
      ToolResult result = execute(tool(core), Map.of("nation", OSTERMARK, "dryRun", false));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode body = JSON.readTree(result.message());
      System.out.println("[WORLDGEN-VERTICAL] " + result.message());
      assertThat(body.get("revision").asLong()).as("恰好多出一条 revision").isEqualTo(2L);
      assertThat(body.get("branch").asText()).isEqualTo("main");
      assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(2L);

      state = core.replay(new StateRef(MAIN, new RevisionId(2)));
      SocialData social = socialSlice(state);
      SimosTimestamp at = state.meta().timestamp();

      long rural = social.populations().values().stream().mapToLong(s -> s.valueAt(at)).sum();
      // ★ R1（T5）：城的城镇人口**不再是 SocialCity 的字段** ⇒ 从该城的批次求和（派生量）。
      long urban =
          social.cities().values().stream()
              .mapToLong(city -> social.urbanPopulationAt(city.id()))
              .sum();
      assertThat(social.populations()).as("region 全部格都有农村人口（无 ocean 格）").hasSize(OSTERMARK_HEXES);
      assertThat(rural).as("农村合计").isEqualTo(OSTERMARK_RURAL);
      assertThat(urban).as("Σ city.population").isEqualTo(OSTERMARK_URBAN);
      assertThat(rural + urban).as("总人口").isEqualTo(OSTERMARK_TOTAL);
      assertThat(social.cities()).isNotEmpty();

      // ── ★★ R1 验收判据（设计稿 §九 / §十.7）：**新旧人口账逐格相等** ──────────────────────────
      //   新账 = `groups`（人口批次，人口的真值源）；旧账 = 农村序列 + 落在该格的各城人口（后者 R1 起
      //   由 `urbanPopulationAt` 从批次派生 —— 见 T5）。两笔账必须**逐格**对得上，否则"统一人口账"是空话。
      long groupsTotal = social.groups().values().stream().mapToLong(PopulationGroup::count).sum();
      assertThat(groupsTotal).as("Σ group（新账）= 全国总人口").isEqualTo(OSTERMARK_TOTAL);
      assertThat(social.groups())
          .as("138 格 × 6（农村：3 档 × 2 性）+ 每城 6（城镇）")
          .hasSize(OSTERMARK_HEXES * 6 + social.cities().size() * 6);
      long mismatchedHexes = 0L;
      for (Map.Entry<HexCoord, PopulationSeries> entry : social.populations().entrySet()) {
        HexCoord hex = entry.getKey();
        long oldRural = entry.getValue().valueAt(at);
        long oldUrban =
            social.cities().values().stream()
                .filter(city -> city.at().equals(hex))
                .mapToLong(city -> social.urbanPopulationAt(city.id()))
                .sum();
        if (social.populationAt(hex) != oldRural + oldUrban) {
          mismatchedHexes++;
        }
      }
      assertThat(mismatchedHexes).as("逐格：Σ group == 农村序列 + 该格各城人口（一格都不许差）").isZero();
      assertThat(
              social.groups().values().stream()
                  .filter(group -> !social.populations().containsKey(group.residence()))
                  .count())
          .as("批次必须全部落在有 populations 序列的格上（§十.7 的跨组件校验在真档上的现形）")
          .isZero();

      // ★★ R2a：同一批里落下的 economy.Seed —— 经济侧逐格有状态、人口/土地/日耗守恒。
      //   奥斯特马克真档只有 plains(119) + low_hills(19)（配置 terrainHistogram）⇒ 逐格都有农村人口。
      EconomyData economy = economySlice(state);
      assertThat(economy.meta()).as("创世即激活（meta 非空）").isPresent();
      assertThat(economy.meta().orElseThrow().mapId()).isEqualTo(MAP_ID);
      assertThat(economy.meta().orElseThrow().activatedDay())
          .as("激活日 = 世界当前 tick")
          .isEqualTo(at.tick());
      assertThat(economyHexCount(economy)).as("该国每一格各得一份经济状态").isEqualTo(OSTERMARK_HEXES);
      assertThat(economy.industries())
          .as("农业恒有，城市格再加手工业")
          .hasSize(OSTERMARK_HEXES + social.cities().size());
      assertThat(economy.classes().values().stream().mapToLong(ClassRow::population).sum())
          .as("经济侧人口 == 社会侧人口（农村 + 城市）")
          .isEqualTo(OSTERMARK_TOTAL);
      long plains = 119L;
      long lowHills = 19L;
      assertThat(
              economy.classes().values().stream()
                  .mapToLong(row -> row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L))
                  .sum())
          .as("Σ 土地 = 平原 119 格 + 低丘 19 格，各按标定的每格亩数与地形系数（千分亩）")
          .isEqualTo(
              plains * EconomySeeder.MU_PER_HEX * 1_000L
                  + lowHills
                      * EconomySeeder.MU_PER_HEX
                      * 1_000L
                      * EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf("low_hills"))
                      / 1000L);
      long seededNaturalNeeds =
          economy.classes().values().stream()
              .mapToLong(row -> row.naturalNeeds().getOrDefault(GRAIN, 0L))
              .sum();
      assertThat(seededNaturalNeeds)
          .as("Σ 日耗 == Σ 行第 1 天的口粮（逐行取整；播种器写的就是这个）")
          .isEqualTo(rationOverDays(economy, 1L));
      assertThat(seededNaturalNeeds)
          .as("量级锚：整格口径是 floor(总人口 × 10,000 ÷ 120)，逐行之和只可能更小（每行最多少 1）")
          .isBetween(
              EconomyVocabulary.dailyRationMilli(OSTERMARK_TOTAL, 1L) - economy.classes().size(),
              EconomyVocabulary.dailyRationMilli(OSTERMARK_TOTAL, 1L));
      System.out.println(
          "[WORLDGEN-ECONOMY] nation="
              + OSTERMARK
              + " hexes="
              + economyHexCount(economy)
              + " industries="
              + economy.industries().size()
              + " population="
              + economy.classes().values().stream().mapToLong(ClassRow::population).sum()
              + " landMilliMu="
              + economy.classes().values().stream()
                  .mapToLong(row -> row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L))
                  .sum()
              + " dailyGrainMilli="
              + economy.classes().values().stream()
                  .mapToLong(row -> row.naturalNeeds().getOrDefault(GRAIN, 0L))
                  .sum());

      SocialCity capital =
          social.cities().values().stream()
              .filter(city -> "马尔克堡".equals(city.name()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("没有叫 马尔克堡 的首都"));
      SocialCity largest =
          social.cities().values().stream()
              .max(Comparator.comparingLong(city -> social.urbanPopulationAt(city.id())))
              .orElseThrow();
      assertThat(capital.id()).as("首都必须是人口最大的那座").isEqualTo(largest.id());
      assertThat(capital.region()).contains(new RegionId(OSTERMARK));
      assertThat(capital.props())
          .as("城市节点挂了审计量（tier/catchmentHexes/localSurplus/…/seed）")
          .containsKeys(
              "tier",
              "catchmentHexes",
              "localSurplus",
              "tradeMultiplier",
              "politicalMultiplier",
              "justification",
              "seed");
      // ★ props 的值走 JSON 往返（payload → CreateCityHandler 的 convertValue）：整数落成 Integer（不是 Long）、
      //   浮点落成 Double —— 这正是"props 数值类型往返"要如实记录的一点。
      //   ★ 而 `seed` **故意走字符串**：它是 long，超过 int 的种子会被 Jackson 静默换类型甚至丢值
      //   （`SdInfoEntry.value` 的既定口径是"只保证标量往返"，long 不在那个保证里）。字符串逐字节往返。
      System.out.println(
          "[WORLDGEN-PROPS] seed="
              + capital.props().get("seed").getClass().getSimpleName()
              + " catchmentHexes="
              + capital.props().get("catchmentHexes").getClass().getSimpleName()
              + " localSurplus="
              + capital.props().get("localSurplus").getClass().getSimpleName()
              + " tier="
              + capital.props().get("tier").getClass().getSimpleName());
      assertThat(capital.props().get("seed")).as("seed 必须是字符串（见上）").isInstanceOf(String.class);
      assertThat(capital.props().get("seed")).isEqualTo("14441");
      assertThat(capital.props().get("catchmentHexes")).isInstanceOf(Integer.class);
      assertThat(capital.props().get("localSurplus")).isInstanceOf(Double.class);
      assertThat(capital.props().get("tier")).isInstanceOf(String.class);

      // ★★ 军队编制块：命令条数、region tag/颜色、单位/链/国家/军队。
      assertThat(body.get("commandCount").asInt())
          .as(
              "3 + N（N = 城市数；3 = SetPopulation + SeedGroups + economy.Seed）"
                  + "+ 军队块（UpdateRegion+CreateNation+根+7 兵种+链+Army）")
          .isEqualTo(3 + social.cities().size() + ARMY_COMMANDS_OSTERMARK);

      JsonNode armyView = body.get("army");
      assertThat(armyView).as("dryRun=false 的摘要也带 army 段").isNotNull();
      assertThat(armyView.get("nationId").asText()).isEqualTo(OSTERMARK);
      assertThat(armyView.get("peacetime").asInt()).isEqualTo(6_500);
      assertThat(armyView.get("mobilization").asInt()).isEqualTo(14_800);
      assertThat(armyView.get("establishmentTotal").asInt()).isEqualTo(OSTERMARK_ESTABLISHMENT);
      assertThat(armyView.get("armCount").asInt()).isEqualTo(7);
      assertThat(armyView.get("rootUnitId").asText()).isEqualTo("奥斯特马克侯国-army");
      assertThat(armyView.get("chainId").asText()).isEqualTo("奥斯特马克侯国-chain");
      assertThat(armyView.get("armyId").asText()).isEqualTo("奥斯特马克侯国-army-id");
      assertThat(armyView.get("units")).hasSize(1 + 7);
      assertThat(armyView.get("units").get(0).get("member").asInt())
          .isEqualTo(OSTERMARK_ESTABLISHMENT);
      assertThat(armyView.get("units").get(0).get("id").asText()).isEqualTo("奥斯特马克侯国-army");

      // ★★ region tag 变 nation:<nationId>，而 color/description/annexedBy **一字不变**（机制 2 的判据：
      //    "只发 {tag:…}" 的实现会把 color 抹成 null ⇒ 本断言必红）。
      Region before =
          mapSlice(core.replay(new StateRef(MAIN, R1))).regions().get(new RegionId(OSTERMARK));
      Region after = mapSlice(state).regions().get(new RegionId(OSTERMARK));
      assertThat(after.meta().tag()).isEqualTo(OSTERMARK_TAG);
      assertThat(after.meta().color()).as("区域颜色不得被抹掉").isEqualTo(before.meta().color());
      assertThat(after.meta().color()).isEqualTo("#9c9c86"); // 真档字面量
      assertThat(after.meta().description()).isEqualTo(before.meta().description());
      assertThat(after.meta().annexedBy()).isEqualTo(before.meta().annexedBy());

      // ★★ 编制守恒 + 单位/链/sd 一致性。
      UnitState units = unitSlice(state);
      Unit root = units.units().get(new UnitId("奥斯特马克侯国-army"));
      assertThat(root).as("根单位存在").isNotNull();
      assertThat(root.status()).as("驻防状态").isEqualTo(UnitStatus.RESTING);
      assertThat(root.parent().valueAt(at)).as("根单位无父").isEmpty();
      assertThat(root.equipment()).as("根单位装备为空表").isEmpty();
      assertThat(units.units()).hasSize(1 + 7);
      int armSum = 0;
      for (Map.Entry<UnitId, Unit> entry : units.units().entrySet()) {
        if (!entry.getKey().equals(root.id())) {
          assertThat(entry.getValue().parent().valueAt(at)).contains(root.id());
          armSum += entry.getValue().member();
        }
      }
      assertThat(armSum).as("Σ 兵种单位.member").isEqualTo(OSTERMARK_ESTABLISHMENT);
      assertThat(root.member()).as("根单位 member == Σ").isEqualTo(armSum);
      // 装备换算示例：弓弩手 1600 人 × armKits{弩:100, 箭矢:2000, 皮甲:80} / 100。
      assertThat(units.units().get(new UnitId("奥斯特马克侯国-弓弩手")).equipment())
          .containsExactlyInAnyOrderEntriesOf(Map.of("弩", 1600, "箭矢", 32_000, "皮甲", 1_280));

      CommandChain chain = units.commandChains().get(new CommandChainId("奥斯特马克侯国-chain"));
      assertThat(chain).as("指挥链存在").isNotNull();
      assertThat(chain.commander().value()).isEqualTo("奥斯特马克侯国-army");
      assertThat(chain.members()).as("members 含根 + 全部兵种").hasSize(1 + 7);
      assertThat(chain.members()).contains(new UnitId("奥斯特马克侯国-army"));

      SdState sd = sdSlice(state);
      assertThat(sd.nations()).hasSize(1);
      Nation nation = sd.nations().get(NationId.parse(OSTERMARK));
      assertThat(nation.homeRegion()).isEqualTo(new RegionId(OSTERMARK));
      assertThat(nation.name()).isEqualTo(OSTERMARK);
      assertThat(nation.adminBudgetPerTick()).as("行政预算置 0（本笔不臆造）").isZero();
      assertThat(sd.armies()).hasSize(1);
      Army army = sd.armies().get(ArmyId.parse("奥斯特马克侯国-army-id"));
      assertThat(army.nationId()).isEqualTo(NationId.parse(OSTERMARK));
      assertThat(army.rootUnit()).isEqualTo(root.id());
      assertThat(units.units().get(army.rootUnit()).member())
          .as("sd.army 的 rootUnitId 指向存在且 member == 编制合计的单位")
          .isEqualTo(OSTERMARK_ESTABLISHMENT);
    }

    // ★ 重放一致：换一个**新引擎**（只装 codec）从同一个库重放 (main,2)，逐字段等于首次重放——真往返，不是同一次
    //   调用的重复。
    try (CoreSimos reopened = codecOnly(store)) {
      assertThat(reopened.replay(new StateRef(MAIN, new RevisionId(2))))
          .as("从落盘重放 → 同样状态")
          .isEqualTo(state);
    }
  }

  // ── 2b. ★★ R1.5 的 T3：真档 138 格**逐格**读出来"两侧人口一致"──────────────────────────

  /**
   * ★★ **"两侧人口一致"读得出来（R1.5 的 T3 验收）**：真档奥斯特马克的**每一格**，经**读口**（{@link ApiViews#population} = GUI
   * {@code GET /api/social/population} 与 MCP {@code simos.social.population} 共用的那一份视图；{@link
   * ApiViews#economyHex} = {@code GET /api/economy/hex} 与 {@code simos.economy.hex}
   * 共用的那一份）各取一个数，断言**逐格相等**：
   *
   * <pre>
   * social 侧 = groups.total（Σ 该格各 PopulationGroup 的 count，R1.5 新发出来的那一项）
   * economy 侧 = population（Σ 该格各阶层行 ClassRow.population）
   * </pre>
   *
   * <p>★★ **它是 R1 那条"构造性相等"的读侧对照，两条都要有**：{@code PopulationSeederTest} 守的是"**创世算得对**"
   * （同一份批次列表喂两条命令），本条守的是"**读出来也对**"（装配、口径、单位一处没走偏）。★ 判别力因此在**读口**上：把 {@code ApiViews.population} 的
   * {@code total} 换成"只算农村"（R1.5 之前的读口就是只读农村序列）⇒ 城市格的 {@code total} 少掉整座城 ⇒ 这些格当场红。
   *
   * <p>★ **不许空过**（否则"0 == 0"会假绿）：两个总量各自都钉成真档硬值 {@code 3,070,000}，且逐格要求 economy 侧有产业 （{@code
   * industries} 非空）——"这一格没读出东西"不会伪装成"一致"。
   *
   * <p>★ **为什么走视图函数而不是 HTTP**：那两个视图函数**就是**两个端点的响应体（{@code GuiServer} 只做 {@code Reply.of(200,
   * ApiViews.…)} 与权限判定，不重排字段），HTTP 那一层（入参校验 / 404 / redaction）由 {@code GuiApiTest}
   * 用自己的小夹具覆盖（那边逐值钉了同一个块）。此处要的是**真档 138 格**，而真档跑一次 HTTP 服务器不带来额外判据。
   */
  @Test
  void readSidePopulationParityHoldsForEveryHexOfTheRealWorld() throws IOException {
    try (CoreSimos core = freshCore(dir("read-parity"))) {
      ToolResult result = execute(tool(core), Map.of("nation", OSTERMARK, "dryRun", false));
      assertThat(result.success()).as(result.message()).isTrue();

      SimulationState state = core.replay(new StateRef(MAIN, R2));
      SocialData social = socialSlice(state);
      EconomyData economy = economySlice(state);
      SimosTimestamp at = state.meta().timestamp();

      long hexes = 0L;
      long socialGrandTotal = 0L;
      long economyGrandTotal = 0L;
      String firstMismatch = "";
      for (Map.Entry<HexCoord, PopulationSeries> entry : social.populations().entrySet()) {
        HexCoord hex = entry.getKey();
        Map<String, Object> socialView = ApiViews.population(social, hex, at);
        Map<String, Object> economyView = ApiViews.economyHex(hex, economy);
        Object groupsRaw = socialView.get("groups");
        assertThat(groupsRaw)
            .as("%s：读口必须发出 groups 块（R1.5 的 T3 就靠它那一项对拍）", hex)
            .isInstanceOf(Map.class);
        long socialSide = ((Number) ((Map<?, ?>) groupsRaw).get("total")).longValue();
        long economySide = ((Number) economyView.get("population")).longValue();
        assertThat((List<?>) economyView.get("industries"))
            .as("%s：economy 侧这一格必须真有产业（否则 0 == 0 是假绿）", hex)
            .isNotEmpty();
        if (socialSide != economySide && firstMismatch.isEmpty()) {
          firstMismatch = hex + " social=" + socialSide + " economy=" + economySide;
        }
        socialGrandTotal += socialSide;
        economyGrandTotal += economySide;
        hexes++;
      }

      assertThat(hexes).as("真档奥斯特马克的格数（每格都有农村人口序列）").isEqualTo(OSTERMARK_HEXES);
      assertThat(firstMismatch).as("逐格：social 侧 Σ 批次 == economy 侧 Σ 各行").isEmpty();
      assertThat(socialGrandTotal)
          .as("social 侧合计 == 全国总人口（量级锚：不是 0 == 0 那种空过）")
          .isEqualTo(OSTERMARK_TOTAL);
      assertThat(economyGrandTotal).as("economy 侧合计 == 全国总人口").isEqualTo(OSTERMARK_TOTAL);
      // ★ 两个量级锚各自都非零，且**有一格是城市格**（城市人口在两边的两条池子里都算过）——138 格里必然有城。
      assertThat(social.cities()).as("真档有城 ⇒ 逐格里含城镇批次（否则本用例只验了农村）").isNotEmpty();
      System.out.println(
          "[R15-PARITY] hexes="
              + hexes
              + " social="
              + socialGrandTotal
              + " economy="
              + economyGrandTotal);
    }
  }

  // ── 3. ★ 同 seed 可复现：两个干净库的命令 payload 逐字节相同 ─────────────────────────────

  @Test
  void sameSeedProducesByteIdenticalCommandPayloadsAcrossCleanLibraries() throws IOException {
    try (CoreSimos coreA = freshCore(dir("repro-a"));
        CoreSimos coreB = freshCore(dir("repro-b"))) {
      List<CommandEnvelope> batchA = commandBatchFor(coreA);
      List<CommandEnvelope> batchB = commandBatchFor(coreB);

      assertThat(batchA).as("批大小相同").hasSameSizeAs(batchB);
      for (int i = 0; i < batchA.size(); i++) {
        assertThat(batchA.get(i).type()).as("第 %d 条类型相同", i).isEqualTo(batchB.get(i).type());
        assertThat(batchA.get(i).payloadJson())
            .as("第 %d 条 payload 必须逐字节相同（抓 Map 迭代序混进 payload）", i)
            .isEqualTo(batchB.get(i).payloadJson());
      }
    }
  }

  // ── 4. 随机化开关生效 ────────────────────────────────────────────────────────────────

  @Test
  void randomizeWithTwoSeedsChangesTheTotalWithinTheConfiguredBand() throws IOException {
    try (CoreSimos core = freshCore(dir("randomize"))) {
      JsonNode first =
          body(execute(tool(core), Map.of("nation", OSTERMARK, "randomize", true, "seed", 11L)));
      JsonNode second =
          body(
              execute(
                  tool(core), Map.of("nation", OSTERMARK, "randomize", true, "seed", 2_000_011L)));

      long totalFirst = first.get("totalPopulation").asLong();
      long totalSecond = second.get("totalPopulation").asLong();
      assertThat(first.get("randomize").asBoolean()).as("randomize 真的生效了").isTrue();
      assertThat(totalSecond).as("换 seed ⇒ 变体不同").isNotEqualTo(totalFirst);
      // population 的 jitterPct=0.03（配置）：3,070,000 × (1 ± 0.03)。
      assertThat(totalFirst).isBetween(2_977_900L, 3_162_100L);
      assertThat(totalSecond).isBetween(2_977_900L, 3_162_100L);
      assertThat(core.head(MAIN).orElseThrow().value()).as("dryRun 默认 ⇒ 不写").isEqualTo(1L);
    }
  }

  // ── 4b. ★ 三国都能一键初始化（人口/城市 + 军队编制）──────────────────────────────────────

  @Test
  void threeNationsEachOneClickInitializeWithArmy() throws IOException {
    // ★ 选择：三国各用一个**独立临时库**（每次都是"干净首启 ⇒ revision 1→2"），把 revision / tag 断言钉成常量。
    //   ★ 同库连播三国的回归护栏见 {@link #twoNationsSeedIntoTheSameEconomySlice()}（economy.Seed 已改"按格追加"）。
    long economyHexTotal = 0L;
    long rationGrandTotal = 0L;
    long sownGrandTotal = 0L;
    long decreaseGrandTotal = 0L;
    for (NationCase nation : NATIONS) {
      String id = nation.regionId();
      try (CoreSimos core = freshCore(dir("three-" + id))) {
        ToolResult result = execute(tool(core), Map.of("nation", id, "dryRun", false));
        assertThat(result.success()).as(id + ": " + result.message()).isTrue();
        JsonNode body = JSON.readTree(result.message());
        assertThat(body.get("revision").asLong()).as(id + " 只落一条 revision").isEqualTo(2L);

        SimulationState state = core.replay(new StateRef(MAIN, R2));
        SimosTimestamp at = state.meta().timestamp();
        SocialData social = socialSlice(state);
        long rural = social.populations().values().stream().mapToLong(s -> s.valueAt(at)).sum();
        // ★ R1（T5）：城的城镇人口**不再是 SocialCity 的字段** ⇒ 从该城的批次求和（派生量）。
        long urban =
            social.cities().values().stream()
                .mapToLong(city -> social.urbanPopulationAt(city.id()))
                .sum();
        assertThat(body.get("hexCount").asInt()).as(id + " 真档 region 格数").isEqualTo(nation.hexes());
        assertThat(rural).as(id + " 农村合计").isEqualTo(nation.population() - nation.urban());
        assertThat(urban).as(id + " 城市合计").isEqualTo(nation.urban());
        assertThat(rural + urban).as(id + " 总人口").isEqualTo(nation.population());
        assertThat(social.cities()).as(id + " 有城市").isNotEmpty();
        // 军队块条数 = UpdateRegion + CreateNation + 根单位 + 兵种数 + 指挥链 + CreateArmy = 兵种数 + 5。
        assertThat(body.get("commandCount").asInt())
            .as(id + " 3 + 城市数（SetPopulation + SeedGroups + economy.Seed）+ 军队块（兵种数 + 5）")
            .isEqualTo(3 + body.get("cityCount").asInt() + nation.armCount() + 5);

        // ★ region tag 变 nation:<nationId>，其余三个 meta 字段原样（机制 2 的判据）。
        Region before =
            mapSlice(core.replay(new StateRef(MAIN, R1))).regions().get(new RegionId(id));
        Region after = mapSlice(state).regions().get(new RegionId(id));
        assertThat(after.meta().tag()).isEqualTo("nation:" + id);
        assertThat(after.meta().color()).as(id + " 颜色不得被抹掉").isEqualTo(before.meta().color());
        assertThat(after.meta().description()).isEqualTo(before.meta().description());
        assertThat(after.meta().annexedBy()).isEqualTo(before.meta().annexedBy());

        // ★ 编制守恒。
        UnitState units = unitSlice(state);
        Unit root = units.units().get(new UnitId(id + "-army"));
        assertThat(root).as(id + " 根单位存在").isNotNull();
        assertThat(units.units()).as(id + " 1 根 + 兵种").hasSize(1 + nation.armCount());
        int armSum = 0;
        for (Unit unit : units.units().values()) {
          if (!unit.id().equals(root.id())) {
            armSum += unit.member();
          }
        }
        assertThat(armSum).as(id + " Σ 兵种.member").isEqualTo(nation.establishment());
        assertThat(root.member()).as(id + " 根 member == Σ").isEqualTo(nation.establishment());

        JsonNode armyView = body.get("army");
        assertThat(armyView).as(id + " 摘要带 army 段").isNotNull();
        assertThat(armyView.get("establishmentTotal").asInt()).isEqualTo(nation.establishment());
        assertThat(armyView.get("armCount").asInt()).isEqualTo(nation.armCount());
        assertThat(armyView.get("peacetime").asInt()).isEqualTo(nation.peacetime());
        assertThat(armyView.get("mobilization").asInt()).isEqualTo(nation.mobilization());

        SdState sd = sdSlice(state);
        Army army = sd.armies().get(ArmyId.parse(id + "-army-id"));
        assertThat(army).as(id + " 军队存在").isNotNull();
        assertThat(army.rootUnit()).isEqualTo(root.id());
        assertThat(units.units().get(army.rootUnit()).member())
            .as(id + " army.rootUnit 指向的根单位 member == 编制合计")
            .isEqualTo(nation.establishment());
        assertThat(sd.nations().get(NationId.parse(id)).adminBudgetPerTick()).isZero();

        // ★★ R2a：经济侧逐格有状态，且**经济人口 == 社会总人口**；三国累计格数在循环外断言。
        EconomyData economy = economySlice(state);
        assertThat(economy.meta()).as(id + " 创世即激活经济").isPresent();
        assertThat(economyHexCount(economy)).as(id + " 每格都有经济状态").isEqualTo(nation.hexes());
        assertThat(economy.classes().values().stream().mapToLong(ClassRow::population).sum())
            .as(id + " 经济侧人口 == 社会侧总人口")
            .isEqualTo(nation.population());
        economyHexTotal += economyHexCount(economy);
      }
    }
    assertThat(economyHexTotal).as("三国 799 格各得一份 economy 状态（430 + 138 + 231）").isEqualTo(799L);
  }

  // ── 4b″. ★★ 同库连播两国（本轮关键回归护栏：economy.Seed 改"按格追加"，不再"已激活即拒"）──────

  /**
   * ★★ **同库连播两国的回归护栏**：同一个库里先播奥斯特马克、再播霍赫兰（两国的格互不相同）⇒ 第二国的整批 （人口/城市/经济/军队）**不得**因"经济切片已激活"被拒回滚。
   *
   * <p>★ 判别力：把 {@code EconomySeedHandler} 的"按格判"改回"按库判"（{@code meta} 非空即拒），第二条 {@code execute} 会返回
   * {@code REJECTED} 且整批（含第二国人口/城市/军队）回滚 ⇒ 本条及其后的数值断言一起红。
   *
   * <p>★ 字面量（两国真档硬值）：格数 138 + 231 = 369；经济人口 3,070,000 + 2,530,000 = **5,600,000**；初始库存 = 按阶层天数 （贫
   * 30/中 60/富 120/地 250）逐行配 ⇒ {@code Σ 行人口 × 83 × 该行天数}（旧的"人人 60 天 = 人口 × 83 × 60"口径已被取代，
   * 两者**必须不同**；且 meta 的激活日仍是创世日 0，不被第二国覆盖）。
   */
  @Test
  void twoNationsSeedIntoTheSameEconomySlice() throws IOException {
    NationCase firstNation = NATIONS.get(1); // 奥斯特马克侯国
    NationCase secondNation = NATIONS.get(2); // 霍赫兰伯国
    try (CoreSimos core = freshCore(dir("same-slice"))) {
      ToolResult firstResult =
          execute(tool(core), Map.of("nation", firstNation.regionId(), "dryRun", false));
      assertThat(firstResult.success()).as(firstResult.message()).isTrue();

      ToolResult secondResult =
          execute(tool(core), Map.of("nation", secondNation.regionId(), "dryRun", false));
      assertThat(secondResult.success())
          .as("同库连播第二国的整批不得因经济切片已激活而拒回滚: " + secondResult.message())
          .isTrue();

      assertThat(core.head(MAIN).orElseThrow().value()).as("两国各一条 revision").isEqualTo(3L);
      EconomyData economy = economySlice(core.replay(new StateRef(MAIN, new RevisionId(3))));

      assertThat(economyHexCount(economy))
          .as("两国格数合计（138 + 231）")
          .isEqualTo(firstNation.hexes() + secondNation.hexes());
      assertThat(economy.classes().values().stream().mapToLong(ClassRow::population).sum())
          .as("两国经济人口合计（3,070,000 + 2,530,000）")
          .isEqualTo(firstNation.population() + secondNation.population());
      assertThat(grainTotal(economy))
          .as("两国初始库存合计 = Σ 行 cumulativeRationMilli(人口, 该阶层天数)")
          .isEqualTo(rationTotal(economy));
      assertThat(grainTotal(economy))
          .as("★ 判别力：与旧口径（人人 60 天 × 每人每日 83）必须不同，否则阶层天数表没被用到")
          .isNotEqualTo((firstNation.population() + secondNation.population()) * 83L * 60L);
      assertThat(economy.meta().orElseThrow().activatedDay()).as("meta 不覆盖：激活日仍是创世日 0").isZero();
      assertThat(economy.meta().orElseThrow().mapId()).isEqualTo(MAP_ID);
    }
  }

  // ── 4b′. ★ R3a + V3：真实三国各推进 10 天 ⇒ 粮库存减少 = Σ(人口 × 83 × 10) + 播种日扣的种子 ──────

  /**
   * ★★ **R3a + V3 + V6 的真实世界验收**：三国各自（独立库，因一次性播种）一键初始化 ⇒ 逐日推进 10 天 ⇒ 该国粮库存合计减少**恰为** {@code 口粮 − 缺口
   * + 播种日扣的种子}。
   *
   * <p>★★ **V6 §7.1①（放贷方留口粮）之后，"口粮"那一项必须减掉缺口**：真档里 poorest 那些行**把储备播成了种子** （30 天口粮 2,500 毫粮/人 vs
   * 每亩需种 8,000 × 人均约 0.21 亩 ≈ 1,676 毫粮/人），缸在第 10 天前后见底；V1 口径下它们 会从同格有余粮的行借到，**V6 起放贷方要留自己一整周期的口粮**
   * ⇒ 借不到了，缺口如实记进 {@code FlowRow.unmetNeed}。守恒式因此是 {@code Δ库存 == (逐行口粮 − 缺口) + 种子}（**不是** {@code 口粮
   * + 种子}）。
   *
   * <p>★★ **判别力分工（三条都是变异轮实测，别混）**：
   *
   * <ul>
   *   <li>守恒式挡的是"**缺口被如实记下来**"：删掉 {@code unmetNeed.merge(...)} 那一笔 ⇒ 读数变 0 而库存真的少吃了 ⇒ 红（实测，霍赫兰伯国
   *       {@code 2108332924 − 0 + 5000502000}）。
   *   <li>守恒式**挡不住** §7.1① 本身：保留额改回 0（V1 口径）它**照样成立**（缺口恒 0 那一侧也满足）⇒ 绿（实测）。
   *   <li>**"V6 的放贷规则真的生效"只由循环之后那条 {@code unmetGrandTotal > 0} 承担**（保留额改回 0 ⇒ 缺口恒 0 ⇒ 红，实测）。 ★
   *       只留守恒式，本条对 §7.1① 就是**装饰**。
   * </ul>
   *
   * <p>★ **顺带实测到一个事实**：把"借入计入缺口行 {@code consumed}"那一笔删掉，本条**仍绿** ⇒ 真档头 10 天**一笔借入都没发生**
   * （否则等号两边会差出借入额）⇒ 缺口来自"缸空**且本格没有有真余粮的放贷方**"的行，借入那一笔的守恒语义由 {@code
   * EconomyDebtTest.lendingIsAnInternalTransferSoTheHexLedgerStillBalances} 承担（那边删同一行 ⇒ 红）。
   *
   * <p>★★ **V3（Task 7）起，第 1 天是播种日**：{@code EconomySeeder} 给真档配了 {@code cycleInputPerUnit[LAND] =
   * 8,000 毫粮/亩} ⇒ 每格在第 1 天先扣种子（**在当天吃饭之前**，v2 spec §3.2）。故这条的字面量必须带上种子那一笔——
   * 它同时是"真档真的读到了第三路瓶颈"的证据（判别力：把播种器改回空 map ⇒ 本条的差值少掉种子那一大笔 ⇒ 红）。
   *
   * <p>★ 种子量**不从常量推**（满种 = 每格 {@code MU_PER_HEX × SEED_MILLI_PER_MU} 只在**付得起**的格上成立；真档里人口薄的格储备也薄 ⇒
   * 扣不满，第三路瓶颈正是在那些格上真的起作用）⇒ 用一条**独立可算**的规则从推进前的账面算出：逐行 {@code min(该行库存, 该行亩数 × 每亩需种)}。
   *
   * <p>★ 三国人口合计 6,230,000 + 3,070,000 + 2,530,000 = 11,830,000 ⇒ 口粮项 = Σ 行 {@code
   * cumulativeRationMilli(行人口, 10)} ≈ 9,858,333,333（**逐行**向下取整；"总人口 × 一天的量 × 10"表达不了它）。
   */
  @Test
  void advancingTenDaysConsumesPopulationTimesEightyThreeAcrossThreeNations() throws IOException {
    long economyHexTotal = 0L;
    long rationGrandTotal = 0L;
    long unmetGrandTotal = 0L;
    long sownGrandTotal = 0L;
    long decreaseGrandTotal = 0L;
    for (NationCase nation : NATIONS) {
      String id = nation.regionId();
      try (CoreSimos core = freshCoreWithEconomy(dir("advance-" + id))) {
        ToolResult result = execute(tool(core), Map.of("nation", id, "dryRun", false));
        assertThat(result.success()).as(id + ": " + result.message()).isTrue();

        SimulationState before = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
        EconomyData economy = economySlice(before);
        long population = economy.classes().values().stream().mapToLong(ClassRow::population).sum();
        assertThat(population).as(id + " 经济人口 == 社会总人口").isEqualTo(nation.population());
        long grainBefore = grainTotal(economy);
        long sown = expectedSownOnTheSowingDay(economy);
        // ★ 头 10 天的口粮 = Σ 行 cumulativeRationMilli(行人口, 10)（**逐行**向下取整 ⇒ 不能写成"总人口 × 一天的量"）。
        long ration10 = rationOverDays(economy, 10L);
        long landMu =
            economy.classes().values().stream()
                .mapToLong(row -> row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L) / 1000L)
                .sum();

        advanceDays(core, 10);

        SimulationState after = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
        long grainAfter = grainTotal(economySlice(after));
        // ★★ **V6 §7.1① 起必须减掉"没吃到的"那一项**：放贷方要留本周期自需 ⇒ 真档里那些"把储备播成种子、
        //   自己缸空"的行（见下）**借不到粮**了，缺口如实记进 {@code FlowRow.unmetNeed}（缺的粮不凭空生出来，
        //   它留在别人的缸里）。故守恒式是 **Δ库存 == Σ实吃 + Σ种子 == (逐行口粮 − 缺口) + 种子**。
        long unmet10 = unmetTotal(economySlice(after));
        assertThat(sown).as(id + "：真档真的扣了种（V3 的第三路瓶颈由此在 799 格里读得到）").isPositive();
        assertThat(sown)
            .as(id + "：扣到的种子 ≤ Σ地亩 × 每亩需种（第三路只**缩**面积，永不放大）")
            .isLessThanOrEqualTo(landMu * EconomySeeder.SEED_MILLI_PER_MU);
        assertThat(grainBefore - grainAfter)
            .as(
                "%s：推进 10 天 ⇒ 粮库存减少 = 头 10 天口粮（逐行累计）%d − 缺口 %d + 播种日扣的种子 %d",
                id, ration10, unmet10, sown)
            .isEqualTo(ration10 - unmet10 + sown);
        // ★ 逐国只钉"缺口远小于口粮"（量级）。★ **不能逐国断言 unmet10 > 0**：各国人均地力不同，
        //   实测德意志第二帝国 10 天内缺口恰为 **0**（缸没见底）⇒ 那条会假红。判别力放在三国合计上（见循环之后）。
        assertThat(unmet10)
            .as(id + "：缺口远小于口粮（V6 起缺口不再被借粮抹平，但也不许被算成大头）")
            .isLessThan(ration10 / 100L);
        rationGrandTotal += ration10;
        unmetGrandTotal += unmet10;
        sownGrandTotal += sown;
        decreaseGrandTotal += grainBefore - grainAfter;
        economyHexTotal += economyHexCount(economySlice(after));
      }
    }
    assertThat(economyHexTotal).as("三国 799 格都真的经结算推进过").isEqualTo(799L);
    // ★ 三国**合计**的账面（不再是"11,830,000 × 830"这种把常数乘一遍的算术）：实际库存减少 == 逐行累计口粮 + 扣到的种子。
    //   逐行的向下取整让"总人口 × 一天的量"这条路彻底不可用 —— 合计必须由行级数据累加而来。
    assertThat(decreaseGrandTotal)
        .as("Σ(三国 10 天库存减少) == Σ 逐行口粮累计 − Σ 缺口 + Σ 扣到的种子")
        .isEqualTo(rationGrandTotal - unmetGrandTotal + sownGrandTotal);
    // ★★ **V6 §7.1① 的真档可见性**：放贷方留口粮 ⇒ 缸空的行借不到粮 ⇒ 缺口**真的出现**（不再被借粮抹平）。
    //   ★ 为什么判在**三国合计**上：逐国可能恰为 0（实测德意志第二帝国 10 天内缸没见底）⇒ 逐国判正会假红。
    //   ★ 上面那条守恒式**挡不住**这件事（缺口为 0 那一侧它也成立）—— 对 §7.1① 的判别力**只**落在这一条上。
    //   实测：把 LENDER_SUBSISTENCE_RESERVE_PER_MILLE 改回 0（V1 口径）⇒ 这里恒为 0 ⇒ 本条红。
    assertThat(unmetGrandTotal).as("★ V6 起真档也真的缺粮：三国合计在第 10 天前就出现借不到的缺口（V1 口径下恒为 0）").isPositive();
    assertThat(rationGrandTotal)
        .as("口粮项的量级锚：11,830,000 人 × 10 天 ≈ 985,833,333 毫粮（逐行取整 ⇒ 略小于它，且差值 < 行数）")
        .isBetween(
            11_830_000L
                    * 10L
                    * EconomyVocabulary.RATION_MILLI_PER_PERSON
                    / EconomyVocabulary.RATION_CYCLE_DAYS
                - 4_000L,
            11_830_000L
                * 10L
                * EconomyVocabulary.RATION_MILLI_PER_PERSON
                / EconomyVocabulary.RATION_CYCLE_DAYS);
  }

  /**
   * ★ **播种日**（周期第一天）逐行扣的种子（毫粮）：v2 spec §3.2/§3.3 的规则从**推进前**的账面独立算出 —— 逐行 {@code min(该行库存, 该行亩数 ×
   * 每亩需种)}（亩 = 千分亩 {@code / 1000}，每亩需种 = {@link EconomySeeder#SEED_MILLI_PER_MU}
   * 毫粮/亩）。无地行（真档里每座城的手工业行）恒贡献 0。
   *
   * <p>★ 只对"周期尚未关账"的账成立：{@code cycleSeedUsedMilli} 在周期末清零，故推进 ≥ 1 个周期后这个式子要另算。
   */
  private static long expectedSownOnTheSowingDay(EconomyData economy) {
    long sown = 0L;
    for (ClassRow row : economy.classes().values()) {
      long landMu = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L) / 1000L;
      sown +=
          Math.min(row.goods().getOrDefault(GRAIN, 0L), landMu * EconomySeeder.SEED_MILLI_PER_MU);
    }
    return sown;
  }

  /** 真世界 + 真引擎 + **economy 参与者**（推进要用；必须在 worldgen 提交前注册，封存后 register 会抛）。 */
  private static CoreSimos freshCoreWithEconomy(Path storeDir) {
    CoreSimos core = freshCore(storeDir);
    core.register(new EconomyTimeParticipant(MAP_ID));
    return core;
  }

  /** 逐日推进 {@code days} 天（一次一天，用上一次返回的新 revision——日制裁定）。 */
  private static void advanceDays(CoreSimos core, int days) {
    long head = core.head(MAIN).orElseThrow().value();
    long from = core.replay(new StateRef(MAIN, new RevisionId(head))).meta().timestamp().tick();
    for (int i = 0; i < days; i++) {
      CommandResult result =
          core.submit(
              new AdvanceTime(
                  "cmd-advance-" + from,
                  "corr-advance-" + from,
                  INITIATOR,
                  MAIN,
                  new RevisionId(head),
                  new TimeRange(
                      SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(from + 1)))));
      assertThat(result)
          .as("推进第 %d 天（tick %d → %d）", i + 1, from, from + 1)
          .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));
      head++;
      from++;
    }
  }

  /** 某国全部阶层行**头 {@code days} 天**的口粮合计（毫粮）= Σ 行累计口粮（多日口粮的唯一写法）。 */
  private static long rationOverDays(EconomyData economy, long days) {
    return economy.classes().values().stream()
        .mapToLong(row -> EconomyVocabulary.cumulativeRationMilli(row.population(), days))
        .sum();
  }

  /** 某国全部阶层行的粮库存合计（毫粮）。 */
  private static long grainTotal(EconomyData economy) {
    return economy.classes().values().stream()
        .mapToLong(row -> row.goods().getOrDefault(GRAIN, 0L))
        .sum();
  }

  /**
   * 某国 Σ 行本周期未满足需求（毫粮）：{@code 需求 − 实得} 的逐日累加（{@code FlowRow.unmetNeed}）。
   *
   * <p>★ **守恒式里它是被减项**：借粮是同格内部划转、借不到的那部分**不凭空生出来**，故 {@code Δ库存 == Σ实吃 + Σ种子 == (逐行口粮 − 缺口) +
   * 种子}。窗口（10 天）落在**同一个周期**内 ⇒ 流水里的 {@code unmetNeed} 覆盖的就是这 10 天，不带别的周期的量。
   */
  private static long unmetTotal(EconomyData economy) {
    return economy.flows().values().stream().mapToLong(FlowRow::unmetNeed).sum();
  }

  /**
   * 按**阶层天数口径**（{@link EconomySeeder#INITIAL_RATION_DAYS_BY_CLASS}）算出的初始库存合计（毫粮）： 逐行 {@link
   * EconomyVocabulary#cumulativeRationMilli}(人口, 该阶层天数) —— **累计**函数，不是"人口 × 一天的量 × 天数"。
   */
  private static long rationTotal(EconomyData economy) {
    return economy.classes().values().stream()
        .mapToLong(
            row ->
                EconomyVocabulary.cumulativeRationMilli(
                    row.population(), EconomySeeder.initialRationDays(row.key().slot().value())))
        .sum();
  }

  /** 德意志的「仆从兵」不在 {@code armKits} 里 ⇒ 该兵种单位装备空表（验证空 map 被 CreateUnit 接受）。 */
  @Test
  void armWithoutKitGetsEmptyEquipment() throws IOException {
    try (CoreSimos core = freshCore(dir("no-kit"))) {
      ToolResult result = execute(tool(core), Map.of("nation", "德意志第二帝国", "dryRun", false));

      assertThat(result.success()).as(result.message()).isTrue();
      UnitState units = unitSlice(core.replay(new StateRef(MAIN, R2)));
      Unit retinue = units.units().get(new UnitId("德意志第二帝国-仆从兵"));
      assertThat(retinue).as("仆从兵单位存在").isNotNull();
      assertThat(retinue.member()).isEqualTo(3_000);
      assertThat(retinue.equipment()).as("armKits 无此兵种 ⇒ 空表").isEmpty();
    }
  }

  // ── 4c. army:false ⇒ 只做人口+城市 ────────────────────────────────────────────────────

  @Test
  void armyFalseDoesPopulationAndCitiesOnly() throws IOException {
    try (CoreSimos core = freshCore(dir("army-false"))) {
      ToolResult result =
          execute(tool(core), Map.of("nation", OSTERMARK, "dryRun", false, "army", false));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode body = JSON.readTree(result.message());
      assertThat(body.has("army")).as("army:false ⇒ 摘要无 army 段").isFalse();
      assertThat(body.get("revision").asLong()).isEqualTo(2L);

      SimulationState state = core.replay(new StateRef(MAIN, R2));
      SocialData social = socialSlice(state);
      assertThat(social.cities()).isNotEmpty();
      assertThat(body.get("commandCount").asInt())
          .as("只 3 + 城市数（SetPopulation + SeedGroups + economy.Seed；不产生任何军队块命令）")
          .isEqualTo(3 + social.cities().size());

      assertThat(sdSlice(state).nations()).as("army:false ⇒ 不建国").isEmpty();
      assertThat(sdSlice(state).armies()).as("army:false ⇒ 不建军").isEmpty();
      assertThat(unitSlice(state).units()).as("army:false ⇒ 不建单位").isEmpty();
      assertThat(unitSlice(state).commandChains()).isEmpty();

      Region before =
          mapSlice(core.replay(new StateRef(MAIN, R1))).regions().get(new RegionId(OSTERMARK));
      Region after = mapSlice(state).regions().get(new RegionId(OSTERMARK));
      assertThat(after.meta().tag()).as("army:false ⇒ 不写 nation tag").isEqualTo("Nation");
      assertThat(after.meta()).as("整个 meta 一字不动").isEqualTo(before.meta());
    }
  }

  // ── 5. fail-closed ──────────────────────────────────────────────────────────────────

  @Test
  void unknownNationFailsClosedAndLeavesNoRevision() throws IOException {
    try (CoreSimos core = freshCore(dir("unknown-nation"))) {
      ToolResult result = execute(tool(core), Map.of("nation", "不存在的国", "dryRun", false));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(1L);
    }
  }

  @Test
  void missingBranchFailsClosedAndLeavesNoRevision() throws IOException {
    try (CoreSimos core = freshCore(dir("missing-branch"))) {
      ToolResult result =
          execute(tool(core), Map.of("nation", OSTERMARK, "branch", "不存在的分支", "dryRun", false));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(core.head(MAIN).orElseThrow().value()).as("不产生 revision").isEqualTo(1L);
    }
  }

  @Test
  void missingConfigFileFailsClosed() throws IOException {
    try (CoreSimos core = freshCore(dir("missing-config"))) {
      AgentTool tool =
          new WorldgenInitializeTool(core, INITIATOR, MAP_ID, Path.of("没有这个文件", "nations.json"));
      ToolResult result = execute(tool, Map.of("nation", OSTERMARK, "dryRun", false));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).as("给可读原因（含路径）").contains("世界生成参数文件不存在");
      assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(1L);
    }
  }

  // ────────────────────────────────── 夹具 ──────────────────────────────────────────────

  /**
   * 真世界 + 真引擎：五 codec + 八条 handler（social 2 + 军队块 5 + economy 1）+ v17levant 创世（{@code (main,1)}）。
   */
  private static CoreSimos freshCore(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (var codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec())) {
      core.register(codec);
    }
    core.register(new SetPopulationHandler());
    core.register(new CreateCityHandler());
    // ★ R1（T3/T4）：人口批次的创世命令（worldgen 的命令批里有它，与 Shell 的装配同源）。
    core.register(new SeedGroupsHandler());
    // ★ R2a：经济播种（与 Shell 的装配同源）。
    core.register(new EconomySeedHandler());
    // ★ 军队编制块要用的五条 handler（与 Shell 的装配同源）。
    core.register(new UpdateRegionHandler());
    core.register(new CreateNationHandler());
    core.register(new CreateUnitHandler());
    core.register(new CreateCommandChainHandler());
    core.register(new CreateArmyHandler());
    core.bootstrapGenesis(RichWorld.state(MAP_ID));
    return core;
  }

  /** 只装 codec 的新引擎（重放不需要 handler）：用来验"从落盘重放 == 首次重放"。 */
  private static CoreSimos codecOnly(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (var codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec())) {
      core.register(codec);
    }
    return core;
  }

  private static AgentTool tool(CoreSimos core) {
    return new WorldgenInitializeTool(core, INITIATOR, MAP_ID, configFile());
  }

  private static ToolResult execute(AgentTool tool, Map<String, Object> args) {
    return tool.execute(context(tool, args));
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }

  private static JsonNode body(ToolResult result) throws IOException {
    assertThat(result.success()).as(result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  /**
   * 用**真生成路径**（配置 + 真档区域格集 + 生成器）产出一次计划，再问工具的 {@code buildBatch} 要命令批（用例逐字节对拍 payload）。★
   * 第二次调用是**另一个干净库**，故这条路本身也覆盖"跨库可复现"。
   */
  private static List<CommandEnvelope> commandBatchFor(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    GameMap map = mapSlice(state);
    Region region = map.regions().get(new RegionId(OSTERMARK));
    NationSetup setup =
        WorldgenConfig.load(configFile()).byRegionId(OSTERMARK).withHexes(region.hexes());
    ResolvedNation resolved = setup.resolve();
    SettlementPlan plan =
        SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
    HexCoord at = capitalHex(plan, resolved);
    return WorldgenInitializeTool.buildBatch(
        "batch-test",
        INITIATOR,
        MAP_ID,
        MAIN,
        R1,
        map,
        region,
        plan,
        resolved.request().seed(),
        setup.army(),
        setup.displayName(),
        at,
        state.meta().timestamp().tick()); // ★ R1：批次的锚点 = 世界当前日（与工具内同口径）
  }

  /** 首都格（与工具内 {@code requireCapitalHex} 同一判据：按配置首都名在城市表里找）。 */
  private static HexCoord capitalHex(SettlementPlan plan, ResolvedNation resolved) {
    String name = resolved.request().capital().map(anchor -> anchor.name()).orElseThrow();
    return plan.cities().stream()
        .filter(city -> name.equals(city.name()))
        .findFirst()
        .map(city -> city.at())
        .orElseThrow(() -> new AssertionError("生成的聚落表里没有首都 " + name));
  }

  private static SocialData socialAt(CoreSimos core, long revision) {
    return socialSlice(core.replay(new StateRef(MAIN, new RevisionId(revision))));
  }

  private static SocialData socialSlice(SimulationState state) {
    SocialSnapshot slice =
        (SocialSnapshot)
            state.module("social").orElseThrow(() -> new AssertionError("状态里没有 social 切片"));
    return slice.data();
  }

  private static GameMap mapSlice(SimulationState state) {
    return ((MapSnapshot) state.module("map").orElseThrow(() -> new AssertionError("状态里没有 map 切片")))
        .map();
  }

  private static UnitState unitSlice(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state();
  }

  private static SdState sdSlice(SimulationState state) {
    SdSnapshot slice =
        (SdSnapshot) state.module("sd").orElseThrow(() -> new AssertionError("状态里没有 sd 切片"));
    return slice.state();
  }

  /** economy 切片（R2a）。 */
  private static EconomyData economySlice(SimulationState state) {
    EconomySnapshot slice =
        (EconomySnapshot)
            state.module("economy").orElseThrow(() -> new AssertionError("状态里没有 economy 切片"));
    return slice.data();
  }

  /** 经济侧**有状态的格数**：从产业 id 里取出格键去重（"产业属于哪一格"走唯一拼写点 {@link IndustryHexKeys}）。 */
  private static long economyHexCount(EconomyData data) {
    Set<String> hexes = new LinkedHashSet<>();
    for (IndustryId id : data.industries().keySet()) {
      IndustryHexKeys.hexKeyOf(id).ifPresent(hexes::add);
    }
    return hexes.size();
  }

  /** 冻结输入的路径：surefire 工作目录是 {@code simos-app/} ⇒ 主树是 {@code ../config/...}。 */
  private static Path configFile() {
    for (Path candidate :
        List.of(
            Path.of("..", "config", "worldgen", "v17levant-nations.json"),
            Path.of("config", "worldgen", "v17levant-nations.json"))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到 config/worldgen/v17levant-nations.json（工作目录=" + Path.of(".").toAbsolutePath() + "）");
  }

  private Path dir(String name) throws IOException {
    return Files.createDirectories(tempDir.resolve(name));
  }
}
