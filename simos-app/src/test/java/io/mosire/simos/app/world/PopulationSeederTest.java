package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R1 的 T4**：创世批次列表（{@link PopulationSeeder}）与"同一份喂两条命令"的构造性一致。
 *
 * <p>夹具刻意取**能被 1000‰ 整除**的人数（1000 / 400），好让逐格、逐性别、逐档的期望值都是**精确**的整数 —— 残差 分派本身由 {@code
 * ProportionalSplitTest} 单独守。
 */
class PopulationSeederTest {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final HexCoord PURE_RURAL = new HexCoord(0, 0);
  private static final HexCoord MIXED = new HexCoord(1, 0);

  /** 两格：0,0 纯农村 1000 人；1,0 农村 400 人 + 城市 200 人。 */
  private static SettlementPlan plan() {
    Map<HexCoord, Long> rural = new LinkedHashMap<>();
    rural.put(PURE_RURAL, 1000L);
    rural.put(MIXED, 400L);
    return new SettlementPlan(
        rural,
        List.of(
            new PlannedCity(
                "c-1_0", "c-1_0", MIXED, PlannedCity.TIER_TOWN, 200L, 1, 0.0, 1.0, 1.0, "test")),
        Map.of(),
        250L,
        0L);
  }

  /**
   * ★★ **跨表一致性**：{@link PopulationSeeder#AGE_REPRESENTATIVE_DAYS} 的每个代表性年龄都必须落在 {@link
   * EconomySeeder#ageBracketOf} 认为的**同一档**里 —— 否则"批次按 D4 分布造、劳动按 D4 档折算"就是各说各话
   * （本仓最忌的"注释声称一致、其实不一致"）。
   *
   * <p>判别力：把代表性年龄改成 16 岁（掉进第二档）⇒ 本用例逐档红。
   */
  @Test
  void representativeAgesLandInTheirOwnBrackets() {
    for (int bracket = 0; bracket < PopulationSeeder.AGE_REPRESENTATIVE_DAYS.length; bracket++) {
      assertThat(EconomySeeder.ageBracketOf(PopulationSeeder.AGE_REPRESENTATIVE_DAYS[bracket]))
          .as("第 %d 档的代表性年龄", bracket)
          .isEqualTo(bracket);
    }
  }

  /**
   * 档边界口径：14 岁在 0-14、15 岁进 15-59、59 岁仍在 15-59、60 岁进 60+；且 15/60 是**整历法年** （14×365 这类按 365
   * 天近似写的夹具在闰日累计下会错开，必须用日历反推）。
   */
  @Test
  void bracketBoundariesFollowTheDocumentedZeroFourteenFifteenFiftyNineSixty() {
    CalendarClock clock = CalendarClock.julianDefault();
    JulianCalendar julian = JulianCalendar.INSTANCE;
    long genesisDay = clock.dayNumberOfTick(0L); // 创世 = 儒略 1445-01-01
    // 精确 N 个历法年前的出生日 → 创世时的逐日年龄；这才是 EconomySeeder.ageBracketOf 的入参口径。
    assertThat(EconomySeeder.ageBracketOf(ageDaysAtGenesis(genesisDay, julian, 14)))
        .as("14 岁整：仍 0-14")
        .isZero();
    assertThat(EconomySeeder.ageBracketOf(ageDaysAtGenesis(genesisDay, julian, 15)))
        .as("15 岁整：进 15-59")
        .isEqualTo(1);
    assertThat(EconomySeeder.ageBracketOf(ageDaysAtGenesis(genesisDay, julian, 59)))
        .as("59 岁整：仍在 15-59")
        .isEqualTo(1);
    assertThat(EconomySeeder.ageBracketOf(ageDaysAtGenesis(genesisDay, julian, 60)))
        .as("60 岁整：进 60+")
        .isEqualTo(2);
    // 生日差一天必须改变归档：少一天 = 14 / 59 岁。
    assertThat(EconomySeeder.ageBracketOf(ageDaysAtGenesis(genesisDay, julian, 15) - 1L))
        .as("离 15 岁生日还差一天：仍在 0-14")
        .isZero();
    assertThat(EconomySeeder.ageBracketOf(ageDaysAtGenesis(genesisDay, julian, 60) - 1L))
        .as("离 60 岁生日还差一天：仍在 15-59")
        .isEqualTo(1);
  }

  /** 创世日时已满 {@code years} 个整历法年者的逐日年龄（生日 = 创世日往回 years 个历法年）。 */
  private static long ageDaysAtGenesis(long genesisDay, JulianCalendar julian, int years) {
    CalendarDate birthday = julian.dateOf(genesisDay);
    long birthdayDay =
        julian.dayNumberOf(
            new CalendarDate(birthday.year() - years, birthday.month(), birthday.day()));
    return genesisDay - birthdayDay;
  }

  /** ★ 性别比例是**创世 preset**、两性各半；批次列表逐格按 (性别 × 档) 出 6 条，落点 = 该格。 */
  @Test
  void groupsCoverEveryHexWithSixLotsOfTheD4Distribution() {
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan(), 0L);
    List<PopulationGroup> groups = seeding.groups();
    assertThat(groups).as("2 格 × 6（农村）+ 1 城 × 6（城镇）").hasSize(18);
    assertThat(seeding.households()).as("2 个农村家户 + 1 个城镇家户").hasSize(3);
    assertThat(seeding.households())
        .allSatisfy(
            household ->
                assertThat(household.memberLots())
                    .as("家户成员 = 该位置的 6 条批次")
                    .hasSize(6));

    // 农村 1000 人：500 男 / 500 女；每性别再按 350/550/100‰ 分三档 ⇒ 175 / 275 / 50。
    Map<Sex, List<Long>> ruralBySex = new LinkedHashMap<>();
    for (PopulationGroup group : groups) {
      if (seeding.locationOf(group.id()).equals(PURE_RURAL)) {
        assertThat(group.id().value())
            .as("农村批次按 PopulationLots 的约定命名")
            .startsWith(PopulationLots.RURAL_PREFIX + "0_0:" + group.sex().name() + ":");
        ruralBySex
            .computeIfAbsent(group.sex(), key -> new java.util.ArrayList<>())
            .add(group.count());
      }
    }
    assertThat(ruralBySex).containsOnlyKeys(Sex.MALE, Sex.FEMALE);
    assertThat(ruralBySex.get(Sex.MALE)).containsExactly(175L, 275L, 50L);
    assertThat(ruralBySex.get(Sex.FEMALE)).containsExactly(175L, 275L, 50L);
    assertThat(
            groups.stream()
                .filter(group -> seeding.locationOf(group.id()).equals(PURE_RURAL))
                .mapToLong(PopulationGroup::count)
                .sum())
        .as("逐格 Σ count == 该格农村人口")
        .isEqualTo(1000L);
  }

  /** 城镇批次挂在**城的身份**上（落点 = 城所在的格），与农村批次同格共存、互不吞并。 */
  @Test
  void cityLotsAreNamedAfterTheCityAndSitOnItsHex() {
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan(), 0L);
    List<PopulationGroup> groups = seeding.groups();
    long urban =
        groups.stream().filter(PopulationLots::isUrban).mapToLong(PopulationGroup::count).sum();

    assertThat(urban).as("Σ 城镇批次 == 城市人口").isEqualTo(200L);
    assertThat(groups.stream().filter(PopulationLots::isUrban))
        .allSatisfy(group -> assertThat(seeding.locationOf(group.id())).isEqualTo(MIXED));
    assertThat(
            groups.stream()
                .filter(group -> seeding.locationOf(group.id()).equals(MIXED))
                .mapToLong(PopulationGroup::count)
                .sum())
        .as("该格 Σ count == 农村 400 + 城镇 200")
        .isEqualTo(600L);
  }

  /** ★ 锚点由调用方一次定死（这一份列表同时喂经济侧，两侧的年龄必须指向同一个锚点）。 */
  @Test
  void everyLotCarriesTheGivenAnchor() {
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan(), 7L);
    assertThat(seeding.groups())
        .allSatisfy(group -> assertThat(group.anchorTick()).isEqualTo(7L));
    assertThat(seeding.groups().get(0).ageDaysAt(9L))
        .as("年龄 = 锚点年龄 + (now − anchor)")
        .isEqualTo(PopulationSeeder.AGE_REPRESENTATIVE_DAYS[0] + 2L);
  }

  /** 载荷是 T3 的形状：{@code {entries:[{id,q,r,sex,count,ageDays,anchorTick}...]}}。 */
  @Test
  void payloadCarriesEveryLotFieldAndHouseholdNodes() throws Exception {
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan(), 3L);
    List<PopulationGroup> groups = seeding.groups();
    JsonNode payload = JSON.readTree(PopulationSeeder.payload(seeding));
    JsonNode first = payload.get("entries").get(0);
    PopulationGroup firstGroup = groups.get(0);
    HexCoord firstAt = seeding.locationOf(firstGroup.id());

    assertThat(payload.get("entries")).hasSize(groups.size());
    assertThat(first.get("id").asText()).isEqualTo(firstGroup.id().value());
    assertThat(first.get("q").asInt()).isEqualTo(firstAt.q());
    assertThat(first.get("r").asInt()).isEqualTo(firstAt.r());
    assertThat(first.get("sex").asText()).isEqualTo(firstGroup.sex().name());
    assertThat(first.get("count").asLong()).isEqualTo(firstGroup.count());
    assertThat(first.get("ageDays").asLong()).isEqualTo(firstGroup.ageAtAnchorDays());
    assertThat(first.get("anchorTick").asLong()).isEqualTo(3L);
    assertThat(first.get("household").asText())
        .as("S2：每条批次都声明所属家户")
        .isEqualTo(seeding.householdOf(firstGroup.id()).value());

    assertThat(payload.get("households")).as("S2 载荷新增 households[]").hasSize(3);
    for (JsonNode household : payload.get("households")) {
      String id = household.get("id").asText();
      HexCoord at = seeding.locationOf(seeding.households().stream()
          .filter(h -> h.id().value().equals(id))
          .findFirst()
          .orElseThrow()
          .memberLots()
          .get(0));
      assertThat(household.get("q").asInt()).isEqualTo(at.q());
      assertThat(household.get("r").asInt()).isEqualTo(at.r());
    }
  }

  /**
   * ★★ **本轮的接缝**：{@code Σ group == 经济侧总人口} 是**构造性成立**的（两侧读同一份列表）—— 这里把两份载荷并排比：{@code
   * social.SeedGroups} 的 Σ count 与 {@code economy.Seed} 的 Σ 阶层行人口必须**逐值相等**。
   *
   * <p>判别力：若有人把经济侧改回"从 SettlementPlan 抄人口"（或让两侧各造一份列表），只要两条路有一处取整/遗漏不同， 本用例当场红 —— 这正是 T4
   * 禁止"各抄一份"的机械化守门。
   */
  @Test
  void theSameGroupListFeedsBothCommandsSoTheTotalsMatchByConstruction() throws Exception {
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan(), 0L);
    List<PopulationGroup> groups = seeding.groups();
    long groupsTotal = groups.stream().mapToLong(PopulationGroup::count).sum();

    JsonNode economyPayload =
        JSON.readTree(EconomySeeder.payload("Map1", seeding, testMap()));
    long economyTotal = 0L;
    for (JsonNode entry : economyPayload.get("entries")) {
      // ★★ H0（K2/K3）：阶层行从**产业节点内**搬到**格 entry 级**（身份 = 格 + 居住类型 + 阶层）⇒ 遍历点跟着搬。
      //   两组四行的**合计**与旧三组合计**逐值相同**：`rural` 那四行 = 旧 farm 四行（旧 weave 四行人口/劳动恒 0，
      //   H0 起不再存在），`urban` 那四行 = 旧 craft 四行。★ 只取 `rural` 那一组会漏掉城镇那 200 人 ——
      //   下一条断言要的是"Σ 阶层行人口 == Σ group（1600）"，故两组都要算进来。
      for (JsonNode row : entry.get("classes")) {
        economyTotal += row.get("population").asLong();
      }
    }

    assertThat(groupsTotal).as("1000 + 400 + 200").isEqualTo(1_600L);
    assertThat(economyTotal).as("经济侧总人口 == Σ group（同一份列表 ⇒ 构造性相等，不是「再算一遍」）").isEqualTo(groupsTotal);
  }

  /**
   * ★★ **性别真的进了劳动折算**（T4 的判据）：同一个池，把**女性**的劳动系数改成 0 ⇒ 有效劳动**逐值减半** （池子两性各半、且两性的年龄构成相同 ⇒
   * 剩下的恰是男性那一半）。
   *
   * <p>★ 判别力：把 {@code laborMilli} 改回"只看人口 × 常量"（性别不参与）⇒ 本用例红（两次调用同值）。 ★ 默认表两性同系数 ⇒ 数值与 R1
   * 之前逐值相同（这条由 {@code EconomySeederTest} 的 580‰ 断言守着）。
   */
  @Test
  void sexEntersTheLaborConversion() {
    List<PopulationGroup> groups = PopulationSeeder.seed(plan(), 0L).groups();
    long withDefaultTable = EconomySeeder.laborMilli(groups);
    long maleOnly =
        EconomySeeder.laborMilli(
            groups,
            Map.of(
                Sex.MALE, EconomySeeder.AGE_LABOR_COEF_PER_MILLE, Sex.FEMALE, new int[] {0, 0, 0}));

    assertThat(withDefaultTable).as("1600 人 × 580‰（D4 默认：两性同表）").isEqualTo(1_600L * 580L);
    assertThat(maleOnly).as("女性系数归零 ⇒ 只剩男性那一半（800 人 × 580‰）").isEqualTo(withDefaultTable / 2L);
    assertThat(maleOnly).isNotEqualTo(withDefaultTable);
  }

  /** 两格最小地图（覆盖 {@link SettlementPlan} 里的两个 rural 格 + 城所在格）：只为喂 {@link EconomySeeder} 的地形索引。 */
  private static GameMap testMap() {
    TerrainType plains = TerrainCatalog.of("plains");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(PURE_RURAL, new HexCell(0.5));
    hexes.put(MIXED, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(plains.key(), plains);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), plains.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 每个池的人均劳动在默认表下恒为 580‰（池的年龄构成恰是 D4 preset）⇒ 与旧口径 `人口 × 580` 逐值相同。 */
  @Test
  void defaultPresetReproducesTheOldPerCapitaLabor() {
    List<PopulationGroup> groups = PopulationSeeder.seed(plan(), 0L).groups();
    assertThat(EconomySeeder.laborMilli(groups)).isEqualTo(EconomySeeder.laborMilli(1_600L));
  }
}
