package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link EconomySeeder} 的纯函数用例（R2a；R1 起人口来源改为 {@link PopulationSeeder} 造的批次列表）：**3 个格**（平原纯农村 /
 * 低丘农村+城市 / 平原只有城市）——逐值断言 人口守恒、土地守恒、日耗 = 人口 × 83 毫粮。
 *
 * <p>★ R1（T4）：播种器的输入是 {@code List<PopulationGroup>}，故夹具先经 {@code PopulationSeeder.groups(plan, 0)}
 * 把计划翻成 批次 —— **这正是生产路径的走法**（同一个方法在 {@code WorldgenInitializeTool} 里同时喂给 social.SeedGroups 与
 * economy.Seed）。
 *
 * <p>★ 断言值都是**手算的字面量**，不是"再调一遍生成器对拍"（本仓用例纪律）。地形由 {@code hex -> key} 替身给出，不必造 {@link
 * io.mosire.simos.map.GameMap}。
 */
class EconomySeederTest {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final HexCoord PLAINS_RURAL = new HexCoord(0, 0);
  private static final HexCoord HILLS_MIXED = new HexCoord(1, 0);
  private static final HexCoord PLAINS_CITY_ONLY = new HexCoord(2, 0);

  /** 每格地形：0,0 平原 / 1,0 低丘 / 2,0 平原。 */
  private static final Map<HexCoord, String> TERRAIN =
      Map.of(PLAINS_RURAL, "plains", HILLS_MIXED, "low_hills", PLAINS_CITY_ONLY, "plains");

  /** 3 个格的计划：农村 1000 / 333 / 0；城市落在 1,0（200 人）与 2,0（50 人）。 */
  private static SettlementPlan plan() {
    Map<HexCoord, Long> rural = new LinkedHashMap<>();
    rural.put(PLAINS_RURAL, 1000L);
    rural.put(HILLS_MIXED, 333L);
    rural.put(PLAINS_CITY_ONLY, 0L);
    return new SettlementPlan(
        rural,
        List.of(city("c-1", HILLS_MIXED, 200L), city("c-2", PLAINS_CITY_ONLY, 50L)),
        Map.of(),
        250L,
        0L);
  }

  private static PlannedCity city(String id, HexCoord at, long population) {
    return new PlannedCity(id, id, at, PlannedCity.TIER_TOWN, population, 1, 0.0, 1.0, 1.0, "test");
  }

  private static JsonNode payload() throws Exception {
    return JSON.readTree(
        EconomySeeder.payload("Map1", PopulationSeeder.groups(plan(), 0L), TERRAIN::get));
  }

  private static JsonNode entry(JsonNode payload, int q, int r) {
    for (JsonNode node : payload.get("entries")) {
      if (node.get("q").asInt() == q && node.get("r").asInt() == r) {
        return node;
      }
    }
    throw new AssertionError("载荷里没有格 " + q + "_" + r);
  }

  /** 某格里某个产业（按 id 前缀）的全部阶层行。 */
  private static List<JsonNode> classes(JsonNode entry, String industryId) {
    for (JsonNode industry : entry.get("industries")) {
      if (industry.get("id").asText().equals(industryId)) {
        return List.copyOf(toList(industry.get("classes")));
      }
    }
    throw new AssertionError("格里的产业没有 " + industryId);
  }

  private static List<JsonNode> toList(JsonNode array) {
    List<JsonNode> out = new ArrayList<>();
    array.forEach(out::add);
    return out;
  }

  private static long sumOf(List<JsonNode> nodes, String field) {
    long total = 0L;
    for (JsonNode node : nodes) {
      total += node.get(field).asLong();
    }
    return total;
  }

  private static long sumOfNested(List<JsonNode> nodes, String group, String key) {
    long total = 0L;
    for (JsonNode node : nodes) {
      JsonNode map = node.get(group);
      if (map != null && map.has(key)) {
        total += map.get(key).asLong();
      }
    }
    return total;
  }

  // ── 人口守恒 ─────────────────────────────────────────────────────────────────────────

  @Test
  void everyHexKeepsItsPopulationAcrossItsIndustries() throws Exception {
    JsonNode payload = payload();

    // 平原纯农村：农业 1 个产业、4 个槽位，Σ 行人口 == 1000，且比例恰为 450/350/150/50。
    JsonNode a = entry(payload, 0, 0);
    assertThat(a.get("industries")).as("纯农村格只有农业").hasSize(1);
    List<JsonNode> farmA = classes(a, "farm@0_0");
    assertThat(farmA)
        .extracting(node -> node.get("slot").asText())
        .containsExactly("peasant", "middle", "rich", "landlord");
    assertThat(farmA)
        .extracting(node -> node.get("population").asLong())
        .containsExactly(450L, 350L, 150L, 50L);
    assertThat(sumOf(farmA, "population")).as("Σ 行人口 == 格人口").isEqualTo(1000L);

    // 低丘农村+城市：农业 333 + 手工业 200，两产业合起来 == 533（= 农村 + 城市）。
    JsonNode b = entry(payload, 1, 0);
    List<JsonNode> farmB = classes(b, "farm@1_0");
    List<JsonNode> craftB = classes(b, "craft@1_0");
    assertThat(sumOf(farmB, "population")).isEqualTo(333L);
    assertThat(sumOf(craftB, "population")).isEqualTo(200L);
    assertThat(sumOf(farmB, "population") + sumOf(craftB, "population"))
        .as("格人口 = 该格农村 + 城市")
        .isEqualTo(533L);

    // 平原只有城市：农业 0 人、手工业 50 人 —— 城市人口不丢。
    JsonNode c = entry(payload, 2, 0);
    assertThat(sumOf(classes(c, "farm@2_0"), "population")).isZero();
    assertThat(sumOf(classes(c, "craft@2_0"), "population")).isEqualTo(50L);

    long total =
        sumOf(classes(a, "farm@0_0"), "population")
            + sumOf(classes(b, "farm@1_0"), "population")
            + sumOf(classes(b, "craft@1_0"), "population")
            + sumOf(classes(c, "farm@2_0"), "population")
            + sumOf(classes(c, "craft@2_0"), "population");
    assertThat(total).as("三国一次播种的总人口 = 农村合计 + 城市合计").isEqualTo(1583L);
  }

  /**
   * ★★ **取整残差按最大余数法分派**（v2 spec §八.7）：333 按 450/350/150/50 切。
   *
   * <pre>
   * 地板除：333×450 = 149,850 ⇒ 149（余 850）；×350 = 116,550 ⇒ 116（余 550）；
   *        ×150 = 49,950 ⇒ 49（余 950）；×50 = 16,650 ⇒ 16（余 650）⇒ Σ = 330，残差 3
   * 余数降序：富农 950 &gt; 贫农 850 &gt; 地主 650 &gt; 中农 550 ⇒ 前三名各 +1
   * ⇒ 150 / 116 / 50 / 17（Σ = 333）
   * </pre>
   *
   * <p>★ 判别力：改回 v1 的「按下标序逐个 +1」⇒ 150/117/50/16 ⇒ 本条红。
   */
  @Test
  void roundingResidualKeepsTheTotalAndGoesToTheLargestRemainders() throws Exception {
    List<JsonNode> farm = classes(entry(payload(), 1, 0), "farm@1_0");

    assertThat(farm)
        .extracting(node -> node.get("population").asLong())
        .containsExactly(150L, 116L, 50L, 17L);
    assertThat(sumOf(farm, "population")).isEqualTo(333L);
  }

  // ── 土地守恒 ─────────────────────────────────────────────────────────────────────────

  @Test
  void everyHexKeepsItsLandAndLandFollowsTerrainCoefficient() throws Exception {
    JsonNode payload = payload();

    // ★ 由标定常量推出，故标定值一改这里自动跟随（v2 spec §10.3 定案 A）
    long plainsLand = EconomySeeder.MU_PER_HEX * 1000L;
    long hillsLand =
        plainsLand * EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf("low_hills")) / 1000L;
    assertThat(sumOfNested(classes(entry(payload, 0, 0), "farm@0_0"), "meansOfProduction", "LAND"))
        .as("平原：Σ 土地 == 格土地")
        .isEqualTo(plainsLand);
    assertThat(sumOfNested(classes(entry(payload, 1, 0), "farm@1_0"), "meansOfProduction", "LAND"))
        .as("低丘：Σ 土地 == 格土地")
        .isEqualTo(hillsLand);
    assertThat(sumOfNested(classes(entry(payload, 2, 0), "farm@2_0"), "meansOfProduction", "LAND"))
        .as("农业人口为 0 的格：土地整份记在第一槽（不丢地、不做除零）")
        .isEqualTo(plainsLand);
    assertThat(classes(entry(payload, 1, 0), "craft@1_0"))
        .allSatisfy(node -> assertThat(node.get("meansOfProduction")).as("手工业不占地").isEmpty());
  }

  // ── 日耗与库存（口径 = 每人每 120 天 10 粮；初始库存 = 按阶层天数）─────────────────────────

  /**
   * ★★ **创世写下的自然需求 = 第 1 天的口粮**（{@link EconomySeeder#firstDayRationMilli}），**初始库存**按 **{@link
   * EconomySeeder#INITIAL_RATION_DAYS_BY_CLASS}** 的阶层天数分别配（贫 30/中 60/富 120/地 250）—— **取代旧的"全世界一律 60
   * 天"口径**（那一口径下同格没人有余粮）。
   *
   * <pre>
   * 平原纯农村 1000 人（450/350/150/50）：
   *   第 1 天需求合计 = Σ floor(人口 × 10,000 ÷ 120) = 37,500 + 29,166 + 12,500 + 4,166 = 83,332
   *   储备合计       = Σ cumulativeRationMilli(人口, 该阶层天数)
   *                  = 450×2,500 + 350×5,000 + 150×10,000 + 50×20,833 = 5,416,666
   *   ★ 旧的 83 口径：日耗 83,000、储备 5,395,000 —— 两者都必须不同（残差 0.4% 就在这里）
   * </pre>
   */
  @Test
  void theSeededDailyNeedIsTheFirstDaysRationAndStockFollowsTheClassDayTable() throws Exception {
    JsonNode payload = payload();

    List<JsonNode> farm = classes(entry(payload, 0, 0), "farm@0_0");
    assertThat(sumOfNested(farm, "naturalNeeds", "grain"))
        .as("1000 人第 1 天的需求合计（逐行向下取整：83,332，比整格的 83,333 少 1）")
        .isEqualTo(83_332L);
    assertThat(sumOfNested(farm, "goods", "grain")).as("按阶层天数配的储备合计").isEqualTo(5_416_666L);
    assertThat(sumOfNested(farm, "goods", "grain"))
        .as("★ 判别力：与旧口径（人人 60 天）必须不同，否则参数表没被用到")
        .isNotEqualTo(4_980_000L);
    // 逐行：需求 == 该行第 1 天的口粮；储备 == 该行人口 × 该槽位天数的**累计**口粮。
    for (JsonNode row : farm) {
      long population = row.get("population").asLong();
      long days = EconomySeeder.initialRationDays(row.get("slot").asText());
      assertThat(row.get("naturalNeeds").get("grain").asLong())
          .as("逐值：需求 == 该行第 1 天的口粮")
          .isEqualTo(EconomyVocabulary.dailyRationMilli(population, 1L));
      assertThat(row.get("goods").get("grain").asLong())
          .as("逐值：储备 == cumulativeRationMilli(人口, %d 天)（%s）", days, row.get("slot").asText())
          .isEqualTo(EconomyVocabulary.cumulativeRationMilli(population, days));
    }

    List<JsonNode> craft = classes(entry(payload, 1, 0), "craft@1_0");
    assertThat(sumOfNested(craft, "naturalNeeds", "grain"))
        .as("200 人（90/70/30/10）第 1 天的需求合计")
        .isEqualTo(7_500L + 5_833L + 2_500L + 833L);
    assertThat(sumOfNested(craft, "goods", "grain"))
        .as("储备合计 = Σ cumulativeRationMilli(人口, 该阶层天数)")
        .isEqualTo(1_083_333L);
  }

  /**
   * ★★ **初始储备按阶层差异化的字面量用例**（用户 2026-09-25 点名：「每个地块给每个阶层一定量粮食储备」）：给定 1000 人， 贫农 30 天、地主 250
   * 天各逐值。判别力：把参数表改成全世界一律 60 天 ⇒ 4,980,000 / 4,980,000 ⇒ 本条当场红。
   */
  @Test
  void initialReservesAreLiteralPerClassDayCounts() {
    assertThat(EconomySeeder.initialRationDays("peasant")).as("贫农 30 天").isEqualTo(30);
    assertThat(EconomySeeder.initialRationDays("middle")).as("中农 60 天").isEqualTo(60);
    assertThat(EconomySeeder.initialRationDays("rich")).as("富农 120 天").isEqualTo(120);
    assertThat(EconomySeeder.initialRationDays("landlord")).as("地主 250 天").isEqualTo(250);

    assertThat(EconomySeeder.rationMilli(1000L, "peasant"))
        .as("1000 贫农 30 天 = cumulativeRationMilli(1000, 30)")
        .isEqualTo(2_500_000L);
    assertThat(EconomySeeder.rationMilli(1000L, "landlord"))
        .as("1000 地主 250 天 = cumulativeRationMilli(1000, 250)")
        .isEqualTo(20_833_333L);
    assertThat(EconomySeeder.rationMilli(0L, "landlord")).as("0 人 ⇒ 0 储备").isZero();
  }

  /** 有效劳动：人口 × 580 千分劳动（D4 默认 0-14/15-59/60+ = 350/550/100‰ × 0/1000/300‰）。 */
  @Test
  void effectiveLaborIsPopulationTimesFiveEighty() throws Exception {
    List<JsonNode> farm = classes(entry(payload(), 0, 0), "farm@0_0");

    assertThat(farm)
        .extracting(node -> node.get("laborMilli").asLong())
        .containsExactly(450L * 580L, 350L * 580L, 150L * 580L, 50L * 580L);
    assertThat(sumOf(farm, "laborMilli")).isEqualTo(1000L * 580L);
  }

  // ── 形状与可复现 ─────────────────────────────────────────────────────────────────────

  @Test
  void payloadIsByteIdenticalAcrossCallsAndIsOrderedByCoordinates() throws Exception {
    String first = EconomySeeder.payload("Map1", PopulationSeeder.groups(plan(), 0L), TERRAIN::get);
    String second =
        EconomySeeder.payload("Map1", PopulationSeeder.groups(plan(), 0L), TERRAIN::get);

    assertThat(first).as("同输入 ⇒ 逐字节相同（抓 Map 迭代序混进载荷）").isEqualTo(second);
    JsonNode payload = JSON.readTree(first);
    assertThat(payload.get("mapId").asText()).isEqualTo("Map1");
    assertThat(payload.get("entries")).hasSize(3);
    assertThat(payload.get("entries").get(0).get("q").asInt()).isZero();
    assertThat(payload.get("entries").get(1).get("q").asInt()).isEqualTo(1);
    assertThat(payload.get("entries").get(2).get("q").asInt()).isEqualTo(2);
    // 产业与槽位都是既定口径：农业在前、手工业在后；槽位 950/900/750/100。
    assertThat(payload.get("entries").get(1).get("industries"))
        .extracting(node -> node.get("id").asText())
        .containsExactly("farm@1_0", "craft@1_0");
    assertThat(payload.get("entries").get(0).get("industries").get(0).get("slots"))
        .extracting(node -> node.get("laborParticipationPerMille").asInt())
        .containsExactly(950, 900, 750, 100);
    assertThat(payload.get("entries").get(0).get("industries").get(0).get("allocation"))
        .isEqualTo(
            JSON.readTree(
                "{\"meansWeightPerMille\":700,\"laborWeightPerMille\":300,\"@class\":\"split\"}"));
  }

  // ── 换算函数（纯函数直测）─────────────────────────────────────────────────────────────

  @Test
  void splitIsExactEvenWithRoundingResidual() {
    assertThat(EconomySeeder.splitByShares(1001L, EconomySeeder.CLASS_SHARE_PER_MILLE))
        .as("残差 1 落在第一个槽位 ⇒ Σ 仍恰为 1001")
        .containsExactly(451L, 350L, 150L, 50L);
    assertThat(EconomySeeder.splitByShares(3L, EconomySeeder.CLASS_SHARE_PER_MILLE))
        .as(
            "3 人按 450/350/150/50 切 ⇒ 地板除 1/1/0/0，余数 350/50/450/150"
                + " ⇒ **残差 1 归余数最大的富农**（v1 的「按下标序」会给第一槽 ⇒ 2/1/0/0）")
        .containsExactly(1L, 1L, 1L, 0L);
    assertThat(EconomySeeder.splitByShares(0L, EconomySeeder.CLASS_SHARE_PER_MILLE))
        .containsExactly(0L, 0L, 0L, 0L);
    assertThat(EconomySeeder.splitProportional(1000L, new long[] {0L, 0L, 0L, 0L}))
        .as("权重全 0：整份记在第一槽（不丢总量）")
        .containsExactly(1000L, 0L, 0L, 0L);
  }

  /** ★★ 判别力自证：比例表被改坏（地主 ×2 而没重分）⇒ **不静默归一化**、Σ ≠ total ⇒ 守恒断言当场红。 */
  @Test
  void brokenShareTableIsNotSilentlyRenormalised() {
    long[] broken = EconomySeeder.splitByShares(1000L, new int[] {450, 350, 150, 100});

    long sum = 0L;
    for (long part : broken) {
      sum += part;
    }
    assertThat(sum).as("Σ = 1050 ≠ 1000（归一会把这个错误伪装成一切正常）").isEqualTo(1050L);
  }

  @Test
  void arablePerMilleComesFromTheTerrainCatalogNotALocalTable() {
    // ★ 满可耕地 = 平原的产能档；这一条同时钉住"map 改了平原产能 ⇒ 经济侧立刻知道"
    assertThat(EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf("plains"))).isEqualTo(1000);
    assertThat(EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf("low_hills")))
        .as("低丘 = 2/3（v1 手挑的 600‰ 没有依据：真相在 map 的 food）")
        .isEqualTo(666);
    assertThat(EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf("plateau")))
        .as("平缓高原 = 1/3")
        .isEqualTo(333);
  }

  @Test
  void zeroFoodTerrainsGetZeroArableLandInsteadOfThrowing() {
    // ★ v1 的 fail-closed：这三种地形一律抛 ⇒ 沙漠/山地格一旦有人口，整批 worldgen 回滚（人口+城市+军队一起）
    for (String terrain : List.of("desert", "mountains", "ocean")) {
      assertThat(EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf(terrain)))
          .as("%s 必须得 0 而不是抛", terrain)
          .isZero();
    }
  }

  @Test
  void unknownTerrainStillFailsClosed() {
    // ★ 0 与"不认识"是两件事：前者是合法产能，后者是坏数据
    assertThatThrownBy(() -> EconomySeeder.foodOf("swamp"))
        .as("未知地形必须抛，不许当 0")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知地形类型");
  }

  @Test
  void fullArableFoodMatchesTheCatalogPlain() {
    assertThat(EconomySeeder.foodOf("plains"))
        .as("FOOD_AT_FULL_ARABLE 必须等于 map 里平原的产能档；map 改了这里就要红")
        .isEqualTo(3);
  }

  /**
   * ★★ **真档量级下的自给率**（v2 spec §1.1.1 / §九 V2 判据 ④；plan1 Task 7 Step 1 点名的用例）。
   *
   * <pre>
   * 需粮   = cumulativeRationMilli(14,806, 120) = 14,806 × 10,000 = 148,060,000 毫粮/格/周期
   *          （旧的 83 口径 = 14,806 × 83 × 120 = 147,467,760 ⇒ 相差 0.4% 正是被丢掉的残差）
   * 毛产   = 3,100 亩 × 67 粮/亩 × 1000 毫粮/粮 = 207,700,000 毫粮/格/周期
   * 收获净产 = 毛产 − 生产损耗（饲料 0‰ + 折旧 30‰） = 207,700,000 − 6,231,000 = 201,469,000
   * 播种日扣种 = 3,100 亩 × {@link EconomySeeder#SEED_MILLI_PER_MU}(8,000 毫粮/亩) = 24,800,000
   * 可用   = 201,469,000 − 24,800,000 = 176,669,000
   * 自给率 = 176,669,000 × 1000 ÷ 148,060,000 = 1193‰（区间 1100~1300‰）
   * </pre>
   *
   * <p>真档每格人口 11,830,000 ÷ 799 = 14,806 人；亩产与每格亩数是**拍出来的假设**，必须被这条钉住。 标定前（v1 的 1,000 亩 × 7
   * 粮/亩）这条是**红的**：自给率 = 5,950,000 × 1000 ÷ 148,060,000 = **40‰**。
   *
   * <p>★ V3/Task 7 起"留种"不再从 15% 里扣，而是**播种日按亩现扣**（{@code SEED_MILLI_PER_MU}）——故本算式里
   * 它是**独立的一项**，不再是损耗率的一部分。★ 折旧那一项用**具名常量**（不写 30）：改折旧率这条跟着走。
   *
   * <p>★ 取 120%（而非刚好 100%）是因为农业还要养城市人口与军队，而手工业现产 0。
   */
  @Test
  void realScaleHexIsSelfSufficientWithinTheCalibratedBand() {
    long populationPerHex = 11_830_000L / 799L; // = 14,806
    long needMilli =
        EconomyVocabulary.cumulativeRationMilli(populationPerHex, EconomySeeder.CYCLE_DAYS);
    long grossMilli =
        EconomySeeder.MU_PER_HEX
            * EconomySeeder.GRAIN_OUTPUT_PER_MU
            * EconomyVocabulary.MILLI_PER_GRAIN;
    long harvestNetMilli =
        grossMilli
            * (1000L - EconomySettlement.FEED_PER_MILLE - EconomySettlement.DEPRECIATION_PER_MILLE)
            / 1000L;
    long seedMilli = EconomySeeder.MU_PER_HEX * EconomySeeder.SEED_MILLI_PER_MU;
    long perMille = (harvestNetMilli - seedMilli) * 1000L / needMilli;

    assertThat(populationPerHex).as("真档每格人口").isEqualTo(14_806L);
    assertThat(needMilli)
        .as("一周期需粮 = 14,806 × 10,000（整周期处累计值恰为口径的整数倍 ⇒ 与「人口 × 83 × 120」不同）")
        .isEqualTo(148_060_000L);
    assertThat(harvestNetMilli).as("收获净产 = 207,700,000 × 97%").isEqualTo(201_469_000L);
    assertThat(seedMilli).as("播种日扣种 = 3,100 亩 × 8,000").isEqualTo(24_800_000L);
    assertThat(perMille).as("自给率必须落在 1100~1300‰（标定目标 1198‰）；标定前这里是 ~40‰").isBetween(1100L, 1300L);
  }

  /**
   * ★★ **播种器按定案数配了每亩需种**（v2 spec §3.3 的 {@code cycleInputPerUnit[LAND]}；用户 2026-09-25 裁定「现定」， 计划 2
   * 的「修订与新增」）。
   *
   * <p>★ 这条**不是**"顺手多写一条断言"：{@code EconomySettlementEndToEndTest} / {@code
   * WorldgenInitializeToolTest} / {@link EconomyRealScaleSeedBottleneckTest} 的字面量**都以这个数为前提**
   * （真档每格满种 = 3,100 亩 × 8 粮/亩 = 24,800 粮/周期，对毛产 207,700 粮 之比 ≈ 1:8.4，落在前现代留种率 1:6~1:11 内） ⇒
   * 谁要改它，必须**同时**重标定那些字面量；这条用例就是那个提醒。
   */
  @Test
  void theSeederConfiguresTheDecidedSeedRate() throws Exception {
    JsonNode farm = entry(payload(), 0, 0).get("industries").get(0);

    assertThat(EconomySeeder.SEED_MILLI_PER_MU).as("定案：8 粮/亩（单位毫粮/亩）").isEqualTo(8_000L);
    assertThat(farm.get("cycleInputPerUnit")).as("★ 只有 LAND 一档（其余五种 AssetKind「声明但不启用」）").hasSize(1);
    assertThat(farm.get("cycleInputPerUnit").get("LAND").asLong())
        .as("真档播种器写进 cycleInputPerUnit[LAND] 的每亩需种")
        .isEqualTo(EconomySeeder.SEED_MILLI_PER_MU);
    assertThat(farm.get("cycleSeedUsedMilli").asLong()).as("创世时尚未投入任何种子").isZero();
    assertThat(EconomySeeder.MU_PER_HEX * EconomySeeder.SEED_MILLI_PER_MU)
        .as("满种种子量（毫粮/格/周期）= 3,100 亩 × 8,000")
        .isEqualTo(24_800_000L);
  }

  @Test
  void laborAndRationAreTheDocumentedPerCapitaValues() {
    assertThat(EconomySeeder.laborMilli(1000L)).isEqualTo(580_000L);
    assertThat(EconomySeeder.laborMilli(0L)).isZero();
    assertThat(EconomySeeder.firstDayRationMilli(1000L))
        .as("创世写下的需求 = 第 1 天口粮 = floor(1000 × 10,000 ÷ 120)")
        .isEqualTo(83_333L);
    assertThat(EconomySeeder.rationMilli(1000L, "middle"))
        .as("中农 60 天 = cumulativeRationMilli(1000, 60) = 1000 × 10,000 × 60 ÷ 120")
        .isEqualTo(5_000_000L);
  }
}
