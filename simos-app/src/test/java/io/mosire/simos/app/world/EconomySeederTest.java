package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link EconomySeeder} 的纯函数用例（R2a；R1 起人口来源改为 {@link PopulationSeeder} 造的批次列表）：**3 个格**（平原纯农村 /
 * 低丘农村+城市 / 平原只有城市）——逐值断言 人口守恒、土地守恒（H0.3 起 = 农业产业的产能）、日耗 = 人口 × 83 毫粮。
 *
 * <p>★★ <b>H0.2/H0.3（2026-09-27）后的载荷形状</b>：行 = <b>家户</b>，键 = {@code (格, 居住类型, 阶层)} ⇒ 每格
 * <b>两组四行</b>（农村 4 + 城镇 4）挂在 <b>entry 级</b>的 {@code classes} 上（产业节点里没有 {@code classes} 了）；
 * 亩/织机/作坊这些<b>产能</b>搬到该格对应产业的 {@code capacity}（旧版散在各行的 {@code meansOfProduction}）。
 * 旧版的第三组行（{@code weave} 那四行，人口恒 0）<b>不再存在</b>：它的纤维并入农村四行、织机成为纺织产业的产能。
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

  /** 某格里某个产业的节点。 */
  private static JsonNode industry(JsonNode entry, String industryId) {
    for (JsonNode industry : entry.get("industries")) {
      if (industry.get("id").asText().equals(industryId)) {
        return industry;
      }
    }
    throw new AssertionError("格里的产业没有 " + industryId);
  }

  /** 某格**一组家户行**（H0.2：每格两组四行；{@code residence} = {@code "rural"} / {@code "urban"}）。 */
  private static List<JsonNode> cohortRows(JsonNode entry, String residence) {
    List<JsonNode> rows = new ArrayList<>();
    for (JsonNode row : toList(entry.get("classes"))) {
      if (row.get("residence").asText().equals(residence)) {
        rows.add(row);
      }
    }
    return rows;
  }

  /** JSON 数组 → {@code List}（载荷里的行/配额都是数组）。 */
  private static List<JsonNode> toList(JsonNode array) {
    List<JsonNode> out = new ArrayList<>();
    array.forEach(out::add);
    return out;
  }

  /** Σ 各行某字段（长整数字段）。 */
  private static long sumOf(List<JsonNode> nodes, String field) {
    long total = 0L;
    for (JsonNode node : nodes) {
      total += node.get(field).asLong();
    }
    return total;
  }

  /** Σ 各行某个**嵌套表**里的某键（如 {@code goods.fiber}）。 */
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

  /**
   * 某产业在本格的**产能总量**（H0.3/K3：旧版散在各行的 {@code meansOfProduction} 里、靠 Σ 还原；现在只有一个数）。
   * 缺键 ⇒ 0（该生产资料本格没有）。
   */
  private static long capacity(JsonNode entry, String industryId, String asset) {
    JsonNode capacity = industry(entry, industryId).get("capacity");
    return capacity != null && capacity.has(asset) ? capacity.get(asset).asLong() : 0L;
  }

  /** Σ 收劳动的主体等于 {@code actorId} 的那些配额的 {@code laborMilli}（R3：一个池的配额现在分给多个产业）。 */
  private static long sumForActor(List<JsonNode> allocations, String actorId) {
    long total = 0L;
    for (JsonNode allocation : allocations) {
      if (allocation.get("actor").get("id").asText().equals(actorId)) {
        total += allocation.get("laborMilli").asLong();
      }
    }
    return total;
  }

  // ── 人口守恒 ─────────────────────────────────────────────────────────────────────────

  @Test
  void everyHexKeepsItsPopulationAcrossItsTwoRowGroups() throws Exception {
    JsonNode payload = payload();

    // 平原纯农村：农业 + 家庭纺织（R3）两个产业；**两组四行**（农村 4 + 城镇 4 —— 该格没有城镇批次 ⇒ 后者全 0）。
    JsonNode a = entry(payload, 0, 0);
    assertThat(a.get("industries"))
        .as("纯农村格 = 农业 + 家庭纺织（R3；两者都**不再有名下的行** —— H0.2 起行按 (格,居住,阶层) 挂在格上）")
        .hasSize(2);
    assertThat(a.get("classes")).as("每格两组四行（农村 4 + 城镇 4），人口为 0 的那组也是**合法的空账**").hasSize(8);
    List<JsonNode> ruralA = cohortRows(a, "rural");
    assertThat(ruralA)
        .extracting(node -> node.get("slot").asText())
        .containsExactly("poor_peasant", "middle_peasant", "rich_peasant", "landlord");
    assertThat(ruralA)
        .extracting(node -> node.get("population").asLong())
        .containsExactly(450L, 350L, 150L, 50L);
    assertThat(sumOf(ruralA, "population")).as("Σ 农村行人口 == 该格农村人口").isEqualTo(1000L);
    assertThat(sumOf(cohortRows(a, "urban"), "population"))
        .as("该格没有城镇批次 ⇒ 城镇四行是**空账**（人口 0），不是「少了四行」")
        .isZero();

    // 低丘农村+城市：农村 333 + 城镇 200，两组合起来 == 533（= 农村 + 城市）。
    JsonNode b = entry(payload, 1, 0);
    List<JsonNode> ruralB = cohortRows(b, "rural");
    List<JsonNode> urbanB = cohortRows(b, "urban");
    assertThat(sumOf(ruralB, "population")).isEqualTo(333L);
    assertThat(sumOf(urbanB, "population")).isEqualTo(200L);
    assertThat(sumOf(ruralB, "population") + sumOf(urbanB, "population"))
        .as("格人口 = 该格农村 + 城市（★ 两组行不得并账：`(格,居住,阶层)` 那一维就是为此而加）")
        .isEqualTo(533L);

    // 平原只有城市：农村四行 0 人、城镇四行 50 人 —— 城市人口不丢。
    JsonNode c = entry(payload, 2, 0);
    assertThat(sumOf(cohortRows(c, "rural"), "population")).isZero();
    assertThat(sumOf(cohortRows(c, "urban"), "population")).isEqualTo(50L);

    // ★ 三国一次播种的总人口 = 农村合计 + 城市合计（两组行**逐格**相加，不再有三组）。
    long total = 0L;
    for (JsonNode entry : payload.get("entries")) {
      total += sumOf(cohortRows(entry, "rural"), "population");
      total += sumOf(cohortRows(entry, "urban"), "population");
    }
    assertThat(total).as("三国一次播种的总人口 = 农村合计 + 城市合计").isEqualTo(1583L);

    // ★★ **结构判据（brief 点名）**：行的身份只有 (格, 居住类型, 阶层) 三段 —— 行里**不带产业**、
    //   也**不带生产资料**（K3 把它们搬到了产业的 capacity 上）。
    for (JsonNode entry : payload.get("entries")) {
      for (JsonNode row : toList(entry.get("classes"))) {
        assertThat(row.get("residence").asText())
            .as("居住类型是身份的一维（农村贫农与城镇贫农是两本账）")
            .isIn("rural", "urban");
        assertThat(row.get("slot").asText()).isNotBlank();
        assertThat(row.has("meansOfProduction")).as("★ H0.3：行上没有生产资料").isFalse();
        assertThat(row.toString()).as("★ 行里没有任何产业 id（没有 `farm@` / `weave@` / `craft@`）").doesNotContain("@");
      }
    }
  }

  /**
   * ★★ **H0.2/H0.3：织机住"产业产能"、纤维住"农村家户账"**（旧版两者都住在 {@code weave} 那四行上）。
   *
   * <p>★ 旧用例钉的三件事各归其位（**判别力一条不减**）：
   *
   * <ul>
   *   <li>"织机总数 = 农村人口 ÷ {@link EconomySeeder#RURAL_CAPITA_PER_LOOM}（残差按最大余数法分派 ⇒ Σ 一分不丢）"
   *       ⇒ 现在由**一个数**直接成立（{@code weave@hex} 的 {@code capacity[TOOL]}），不再需要"四行 Σ"那一步；
   *   <li>"初始纤维 = 本格农田**一个周期**的纤维副产" ⇒ 同一份量、现在落在**农村四行**（同一批人的同一本账）；
   *   <li>"那四行不带人口" ⇒ **不再是四行**：农村人口只有一本账（{@code (格,RURAL,阶层)}），
   *       而织布仍靠**劳动配额**（900‰ 农业 + 100‰ 纺织）支撑，不是第二份人口。
   * </ul>
   *
   * <p>★ 判别力：把 {@code capacity[TOOL]} 去掉 ⇒ 纺织规模恒 0 ⇒ 真档里织不出布（{@code EconomyRealScaleClothTest} 红）；
   * 把纤维从农村行删掉 ⇒ 织机没有原料。
   */
  @Test
  void weavingCapacityAndFiberLiveOnTheIndustryAndTheRuralRows() throws Exception {
    JsonNode entry = entry(payload(), 0, 0);
    List<JsonNode> rural = cohortRows(entry, "rural");

    assertThat(capacity(entry, "weave@0_0", "TOOL"))
        .as("★ 织机总数 = 1000 ÷ 20（旧版是四行各持一份、Σ 才是总数）")
        .isEqualTo(1000L / EconomySeeder.RURAL_CAPITA_PER_LOOM);
    assertThat(rural)
        .as("★ 农村四行是**唯一**的农村家户账（旧版那四行「织机行」人口恒 0 ⇒ 已删）")
        .allSatisfy(
            node ->
                assertThat(node.get("population").asLong())
                    .as("农闲织布是同一批人的第二份活，不是第二份人口")
                    .isPositive());
    assertThat(sumOfNested(rural, "goods", "fiber"))
        .as("★★ 初始纤维 = 本格农田**一个周期**的纤维副产（明标「估计来源」；把田里的纤维搬到织机上是 V8 的活）")
        .isEqualTo(
            3_100L
                * EconomySeeder.FIBER_OUTPUT_PER_MU
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
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
    List<JsonNode> farm = cohortRows(entry(payload(), 1, 0), "rural");

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
    assertThat(capacity(entry(payload, 0, 0), "farm@0_0", "LAND"))
        .as("平原：农业产业产能 == 格土地（H0.3：旧版是「Σ 四行 LAND == 格土地」）")
        .isEqualTo(plainsLand);
    assertThat(capacity(entry(payload, 1, 0), "farm@1_0", "LAND"))
        .as("低丘：产能 == 格土地")
        .isEqualTo(hillsLand);
    assertThat(capacity(entry(payload, 2, 0), "farm@2_0", "LAND"))
        .as("农业人口为 0 的格：土地整份照旧在（地不因没人种而消失）——旧版「整份记在第一槽」那条特例随切分一起消失")
        .isEqualTo(plainsLand);
    assertThat(industry(entry(payload, 1, 0), "craft@1_0").get("capacity").has("LAND"))
        .as("手工业**不占地**（土地全归农业）；R3 起它持有的是**作坊**而不是土地")
        .isFalse();
    assertThat(capacity(entry(payload, 1, 0), "craft@1_0", "WORKSHOP"))
        .as("作坊数 = 城市人口 ÷ URBAN_CAPITA_PER_WORKSHOP（200 ÷ 50）")
        .isEqualTo(200L / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP);
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

    List<JsonNode> farm = cohortRows(entry(payload, 0, 0), "rural");
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

    List<JsonNode> craft = cohortRows(entry(payload, 1, 0), "urban");
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
    assertThat(EconomySeeder.initialRationDays("poor_peasant")).as("贫农 30 天").isEqualTo(30);
    assertThat(EconomySeeder.initialRationDays("middle_peasant")).as("中农 60 天").isEqualTo(60);
    assertThat(EconomySeeder.initialRationDays("rich_peasant")).as("富农 120 天").isEqualTo(120);
    assertThat(EconomySeeder.initialRationDays("landlord")).as("地主 250 天").isEqualTo(250);

    assertThat(EconomySeeder.rationMilli(1000L, "poor_peasant"))
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
    List<JsonNode> farm = cohortRows(entry(payload(), 0, 0), "rural");

    assertThat(farm)
        .extracting(node -> node.get("laborMilli").asLong())
        .containsExactly(450L * 580L, 350L * 580L, 150L * 580L, 50L * 580L);
    assertThat(sumOf(farm, "laborMilli")).isEqualTo(1000L * 580L);
  }

  // ── R2：劳动供给与配额（第三阶段设计稿 §四）────────────────────────────────────────

  /**
   * ★★ **R2（T3 的播种侧）+ R3（T4）：一个池的配额之和 == 该池"改口径前的当日劳动"** —— 后者正是 {@code EconomySettlement} 每天的
   * {@code Σ(行 laborMilli × participationPerMille ÷ 1000)}， 而 R2
   * 起它取自配额表。**两者逐值相等就是"真档数字一个都不变"的全部理由**。
   *
   * <pre>
   * 平原纯农村（1000 人）：行 laborMilli = 450/350/150/50 × 580，槽位投入率 = 950/900/750/100‰
   *   ⇒ 261,000×950‰ + 203,000×900‰ + 87,000×750‰ + 29,000×100‰
   *   = 247,950 + 182,700 + 65,250 + 2,900 = **498,800**（= 该池的当日劳动 ruralDaily）
   * R3：把它拆成两条 —— 农业 900‰ = **448,920**、家庭纺织 100‰ = **49,880**（残差归农业 ⇒ 和恒等于 498,800）
   * 批次的毛劳动：每性别 500 人 ⇒ 未成年 175（系数 0，不发）、青壮 275（×1000‰）、老年 50（×300‰）
   *   ⇒ 每性别 2 条配额；**权重按活动加性别**（农业 男 600/女 400、纺织 男 200/女 800）：
   *   农业 Σ权重 = (275,000 + 15,000) × (600+400)‰ = 290,000
   *       男青壮 = 448,920 × 165,000 ÷ 290,000 = 255,420；女青壮 = 448,920 × 110,000 ÷ 290,000 = 170,280
   *       男老年 = 448,920 ×   9,000 ÷ 290,000 =  13,932；女老年 = 448,920 ×   6,000 ÷ 290,000 =   9,288
   *   纺织 Σ权重 = (275,000 + 15,000) × (200+800)‰ = 290,000
   *       男青壮 =  49,880 ×  55,000 ÷ 290,000 =   9,460；女青壮 =  49,880 × 220,000 ÷ 290,000 =  37,840
   *       男老年 =  49,880 ×   3,000 ÷ 290,000 =     516；女老年 =  49,880 ×   2,000 ÷ 290,000 =   2,064
   *   ⇒ **女织**：女性在纺织上的配额（37,840）是男性（9,460）的四倍；**男耕**：男性在农业上是女性的 1.5 倍
   * </pre>
   */
  @Test
  void everyIndustryQuotaSumEqualsTheDailyLaborItReplaces() throws Exception {
    JsonNode entry = entry(payload(), 0, 0);
    List<JsonNode> allocations = toList(entry.get("allocations"));

    assertThat(allocations).as("纯农村格：**8** 条配额（农业 4 + 家庭纺织 4；未成年批次毛劳动 0 ⇒ 两条都不发）").hasSize(8);
    assertThat(allocations)
        .extracting(node -> node.get("laborMilli").asLong())
        .as("四个批次各两条（农业 + 纺织），逐值如上表")
        .containsExactly(
            255_420L, 13_932L, 170_280L, 9_288L, // 农业：男青壮 / 男老年 / 女青壮 / 女老年
            9_460L, 516L, 37_840L, 2_064L); // 纺织：同序
    assertThat(sumOf(allocations, "laborMilli"))
        .as("Σ 两条活动之和 == 该池改口径前的当日劳动（R2 判据在 R3 之后的形式）")
        .isEqualTo(498_800L);
    assertThat(sumForActor(allocations, "weave@0_0"))
        .as("★ 纺织拿到的总额 = 498,800 × WEAVE_SHARE_PER_MILLE ÷ 1000（非零 ⇒ 织机有活干）")
        .isEqualTo(49_880L);
    assertThat(sumForActor(allocations, "farm@0_0"))
        .as("农业那一条 = 498,800 − 49,880（残差归农业 ⇒ 两者之和恒等于该池的当日劳动）")
        .isEqualTo(448_920L);
  }

  /**
   * ★★ **同一份载荷里"行"与"配额"逐值对拍**（三个格）：**一个池**的配额之和 == 该池各行折算出的当日劳动。
   *
   * <p>★ 这是"改口径不等于改数"的**跨表示**判据：行给的是"产出在阶层之间怎么分"，配额给的是"这批人投了多少" ——
   * 两者在真档必须相等（同一份人口、同一条折算链），否则真档的收获瓶颈当场变（那就是"真档数字变了"）。
   *
   * <p>★★ **R3 起按"池"对拍，不再按"产业"**（T4）：农村那一池的当日劳动现在分给**两个产业**（农业 900‰ + 家庭纺织 100‰） ⇒
   * 逐产业只剩一个零头。判据改成"**池**的配额之和 == **池**各行折算出的当日劳动"， 而"每个产业各拿多少"由 {@code
   * everyIndustryQuotaSumEqualsTheDailyLaborItReplaces} 逐值钉住。
   */
  @Test
  void everyQuotaSumMatchesItsPoolRowsInEveryHex() throws Exception {
    JsonNode payload = payload();

    for (JsonNode entry : payload.get("entries")) {
      // ★ H0.2：**池 = 居住类型**（行的键里已经没有产业）—— 农村池供农业 + 家庭纺织、城镇池供手工业。
      for (String pool : List.of("rural", "urban")) {
        List<String> ids =
            toList(entry.get("industries")).stream()
                .map(node -> node.get("id").asText())
                .filter(
                    id ->
                        pool.equals("rural")
                            ? !id.startsWith(EconomySeeder.CRAFT + "@")
                            : id.startsWith(EconomySeeder.CRAFT + "@"))
                .toList();
        long quotaSum = 0L;
        for (String id : ids) {
          quotaSum += sumForActor(toList(entry.get("allocations")), id);
        }
        assertThat(quotaSum)
            .as("格 %s_%s：%s 池的配额之和必须等于该池各行折算出的当日劳动", entry.get("q"), entry.get("r"), pool)
            .isEqualTo(rowBasedDailyLabor(entry, pool));
      }
    }
  }

  /** 某池（{@code rural}/{@code urban}）"改口径前的当日劳动"（从**同一份载荷的行**算 ⇒ 跨表示对拍，不是"再调一遍生成器"）。 */
  private static long rowBasedDailyLabor(JsonNode entry, String residence) {
    Map<String, Integer> slotParticipation = slotParticipation(entry);
    long total = 0L;
    for (JsonNode row : cohortRows(entry, residence)) {
      total +=
          row.get("laborMilli").asLong() * slotParticipation.get(row.get("slot").asText()) / 1000L;
    }
    return total;
  }

  /**
   * 槽位 → 劳动投入率上限（‰）。★ 三个产业的槽位表**逐值相同**（同一套四阶层 + 950/900/750/100）⇒ 取该格任一产业即可；
   * 这也是 H0.2 之后"哪里能查到参与率"的唯一现成来源（行上不再有产业）。
   */
  private static Map<String, Integer> slotParticipation(JsonNode entry) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (JsonNode slot : entry.get("industries").get(0).get("slots")) {
      out.put(slot.get("id").asText(), slot.get("laborParticipationPerMille").asInt());
    }
    return out;
  }

  /**
   * ★★ **每条配额都有一份同期的供给，且配额之和不超过该批次的可用劳动**（本阶段最重要的不变量的播种侧对照）。
   *
   * <p>★ 判别力：播种器若把"毛额"写成配额的**同一个数**（不做 950/900/750/100‰ 的折算），{@code Σ 配额 = Σ 毛额} 仍会通过本条 ⇒
   * 但会被上一条（498,800）挡住；反过来，若配额算成了两倍（同一批次重复记账），本条的 {@code ≤} 当场红。
   */
  @Test
  void everyQuotaHasAMatchingSupplyAndStaysWithinIt() throws Exception {
    JsonNode payload = payload();

    for (JsonNode entry : payload.get("entries")) {
      Map<String, Long> available = new LinkedHashMap<>();
      Map<String, Long> periodOf = new LinkedHashMap<>();
      for (JsonNode supply : toList(entry.get("laborSupply"))) {
        String group = supply.get("group").asText();
        available.put(
            group,
            supply.get("grossLaborMilli").asLong()
                - supply.get("servedLaborMilli").asLong()
                - supply.get("committedLaborMilli").asLong());
        periodOf.put(group, supply.get("period").asLong());
      }
      Map<String, Long> allocated = new LinkedHashMap<>();
      for (JsonNode allocation : toList(entry.get("allocations"))) {
        String group = allocation.get("group").asText();
        assertThat(available).as("每条配额都必须有同期的供给：%s", group).containsKey(group);
        assertThat(allocation.get("period").asLong())
            .as("配额与供给必须同期：%s", group)
            .isEqualTo(periodOf.get(group));
        allocated.merge(group, allocation.get("laborMilli").asLong(), Long::sum);
      }
      for (Map.Entry<String, Long> entryAllocated : allocated.entrySet()) {
        assertThat(entryAllocated.getValue())
            .as("%s：Σ 配额 ≤ 可用劳动（可分配但不能凭空重复）", entryAllocated.getKey())
            .isLessThanOrEqualTo(available.get(entryAllocated.getKey()));
      }
      assertThat(allocated).as("有配额必有供给 ⇒ 两张表的键集相同").hasSameSizeAs(available);
    }
  }

  /**
   * ★★ **劳动预算把"分不满"变成合法状态，而不是让载荷被拒**（**实测出来的洞**，不是设想）：
   *
   * <pre>
   * 直接给农业发**整池的日劳动** 498,800（= "农村 1000‰ 全给农业"那种配置）：
   *   性别权重 600/400 把总量偏向男性，而该池的日劳动只有毛额的 ≈86%（参与率折扣）
   *   ⇒ 男青壮按权重该拿 498,800 × 165,000 ÷ 290,000 = **283,800**，而它的毛额只有 **275,000**
   * 有预算约束 ⇒ 它被压到 275,000（男老年同理压到 15,000），女青壮 189,200、女老年 10,320 照常
   *   ⇒ Σ = 489,520 &lt; 498,800（**分不满**），而"Σ 该批次 ≤ 其可用劳动"恒成立
   * </pre>
   *
   * <p>★★ **为什么这条必须存在**：没有预算时，把 {@link EconomySeeder#WEAVE_SHARE_PER_MILLE} 改成 0（一个完全合理的 GM 配置）会让
   * {@code economy.Seed} 的载荷被 {@code EconomyData} 的构造期守卫**当场拒** —— **世界直接播不出来** （实测：{@code
   * Rejected[批次 rural:0_0:MALE:1 的劳动配额之和 4202381 超过其可用劳动 4072000]}）。
   *
   * <p>★ 判别力：把 {@code appendAllocation} 里的 {@code Math.min(shares[i], budget…)} 去掉 ⇒ "Σ 逐批次 ≤
   * 毛额"这条当场红（男青壮 283,800 &gt; 275,000）。
   */
  @Test
  void theLaborBudgetKeepsEveryBatchWithinItsAvailableLabor() {
    List<PopulationGroup> pool = new ArrayList<>();
    for (PopulationGroup group : PopulationSeeder.groups(plan(), 0L)) {
      if (group.residence().equals(PLAINS_RURAL)) {
        pool.add(group);
      }
    }
    long poolDaily = EconomySeeder.industryDailyLabor(pool);
    assertThat(poolDaily).as("该池的当日劳动（改口径前那条算式）").isEqualTo(498_800L);

    List<Map<String, Object>> allocations = new ArrayList<>();
    EconomySeeder.appendAllocation(
        allocations,
        EconomySeeder.laborBudget(pool),
        pool,
        new IndustryId("farm@0_0"),
        ActorKind.ESTATE,
        EconomySeeder.FARM,
        poolDaily); // ★ 刻意**照满额**发：这正是"农业 1000‰"那种配置

    long total = 0L;
    for (Map<String, Object> allocation : allocations) {
      long share = ((Number) allocation.get("laborMilli")).longValue();
      total += share;
      String groupId = (String) allocation.get("group");
      long gross =
          pool.stream()
              .filter(g -> g.id().value().equals(groupId))
              .mapToLong(EconomySeeder::grossLaborMilli)
              .findFirst()
              .orElseThrow();
      assertThat(share)
          .as("★ 批次 %s 的配额不得超过它自己的毛额（构造性成立，不靠「默认权重恰好不越界」）", groupId)
          .isLessThanOrEqualTo(gross);
    }
    assertThat(total)
        .as("★ 被预算截断 ⇒ **分不满**（R2 明说配额之和可以小于可用劳动），而不是把载荷做到被拒")
        .isLessThan(poolDaily)
        .isEqualTo(489_520L);
  }

  /** ★ 创世的初始配额是"该批次 1000‰ 归它的乡土产业"：农村 → 农业（庄园）、城镇 → 手工业（作坊）。 */
  @Test
  void genesisQuotasGoToTheHomeIndustryOfEachPool() throws Exception {
    JsonNode payload = payload();

    for (JsonNode entry : payload.get("entries")) {
      for (JsonNode allocation : toList(entry.get("allocations"))) {
        String group = allocation.get("group").asText();
        String actor = allocation.get("actor").get("id").asText();
        String kind = allocation.get("actor").get("kind").asText();
        String activity = allocation.get("activity").asText();
        String hex = entry.get("q").asInt() + "_" + entry.get("r").asInt();
        boolean rural = group.startsWith("rural:");
        if (rural) {
          // ★★ R3（T4）：农村批次发**两条** —— 农业（庄园）900‰ + 家庭纺织（家户）100‰。
          assertThat(actor)
              .as("农村批次只供给农业与家庭纺织（同一批人农闲织布：spec §四 的压力测试）")
              .isIn("farm@" + hex, "weave@" + hex);
          assertThat(kind + "|" + activity)
              .as("农业 = 庄园/farm、家庭纺织 = 家户/weave（act 与 kind 一一对应）")
              .isIn("ESTATE|farm", "HOUSEHOLD|weave");
        } else {
          assertThat(actor).as("城镇批次 → 手工业（作坊）").isEqualTo("craft@" + hex);
          assertThat(kind).as("城镇批次 = 作坊").isEqualTo("WORKSHOP");
          assertThat(activity).as("城镇批次的活动").isEqualTo("craft");
        }
        assertThat(allocation.get("period").asLong()).isEqualTo(EconomySeeder.FIRST_PERIOD);
      }
    }
  }

  /**
   * ★ **没有人的产业不发配额**（0 的配额只是噪声）：(2,0) 的农村人口为 0 ⇒ 农业的日劳动为 0 ⇒ 农业名下一条配额都没有； 该格的城市批次照发（它们供给 {@code
   * craft@2_0}）。
   *
   * <p>★ 判别力：把"0 也发一条"写成无条件发（例如漏掉 {@code total <= 0} 的短路）⇒ 本条的 {@code isEmpty} 那条红。
   */
  @Test
  void hexesWithoutPeopleGetNoQuotasForThatIndustry() throws Exception {
    JsonNode entry = entry(payload(), 2, 0);

    assertThat(rowBasedDailyLabor(entry, "rural"))
        .as("该格农村四行人口为 0 ⇒ 日劳动 0（对照见 everyHexKeepsItsPopulationAcrossItsTwoRowGroups）")
        .isZero();
    assertThat(toList(entry.get("allocations")))
        .as("农业没有配额；只有城市批次供 craft")
        .isNotEmpty()
        .allSatisfy(
            node -> assertThat(node.get("actor").get("id").asText()).isEqualTo("craft@2_0"));
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
    // 产业与槽位都是既定口径：农业在前、家庭纺织居中、手工业在后（R3）；槽位 950/900/750/100。
    assertThat(payload.get("entries").get(1).get("industries"))
        .extracting(node -> node.get("id").asText())
        .containsExactly("farm@1_0", "weave@1_0", "craft@1_0");
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
    // ★ R3：投入表的值侧是**商品表**（"消耗 IRON"这种话原来表达不了）⇒ 每亩需种在 LAND→grain 那一格里。
    assertThat(farm.get("cycleInputPerUnit").get("LAND").get("grain").asLong())
        .as("真档播种器写进 cycleInputPerUnit[LAND][grain] 的每亩需种")
        .isEqualTo(EconomySeeder.SEED_MILLI_PER_MU);
    assertThat(farm.get("cycleInputUsedMilli")).as("创世时尚未投入任何原料").isEmpty();
    assertThat(EconomySeeder.MU_PER_HEX * EconomySeeder.SEED_MILLI_PER_MU)
        .as("满种种子量（毫粮/格/周期）= 3,100 亩 × 8,000")
        .isEqualTo(24_800_000L);
  }

  /**
   * ★★ **R3（T2）："每单位什么"是数据** —— 真档的**三张配方**逐值钉在这里；参数目录不在本轮 ⇒ 它们住在产业实例里。
   *
   * <pre>
   * 农业     容量 {LAND: 1000}  劳动/亩 143   产出 {grain: 67, fiber: 6}   投入 {LAND: {grain: 8000}}
   * 家庭纺织 容量 {TOOL: 1}     劳动/台 1000  产出 {cloth: 30}             投入 {TOOL: {fiber: 30000}}
   * 城市作坊 容量 {WORKSHOP: 1} 劳动/座 1000  产出 {cloth: 60, tool: 5}    投入 {WORKSHOP: {fiber: 60000, iron: 10000}}
   * </pre>
   *
   * <p>★ 判别力：把"每亩"退回隐式约定（删掉 {@code capacityPerUnit}）⇒ 真档的规模算不出来（构造期就拒）； 把纤维从农业产出里删掉 ⇒
   * "田里同时出粮与纤维"这条对不上，织机也失去"内生于土地"的来源。
   */
  @Test
  void theSeederWritesTheThreeDecidedRecipes() throws Exception {
    JsonNode entry = entry(payload(), 0, 0);
    JsonNode farm = entry.get("industries").get(0);
    JsonNode weave = entry.get("industries").get(1);

    // ★★ H0.3（K3）：**产能总量**（旧版散在各行的 meansOfProduction 里）—— "每单位什么" × 产能 ⇒ 本格规模上限。
    assertThat(farm.get("capacity").get("LAND").asLong())
        .as("农业：本格产能 = 3,100 亩（千分亩）")
        .isEqualTo(EconomySeeder.MU_PER_HEX * 1_000L);
    assertThat(weave.get("capacity").get("TOOL").asLong())
        .as("纺织：本格产能 = 1,000 ÷ 20 = 50 台织机")
        .isEqualTo(1000L / EconomySeeder.RURAL_CAPITA_PER_LOOM);
    assertThat(farm.get("capacityPerUnit").get("LAND").asLong())
        .as("农业：单位规模 = 1 亩")
        .isEqualTo(1_000L);
    assertThat(farm.get("laborPerUnit").asLong())
        .as("每亩需劳动 = ⌈1000 ÷ 7⌉ = 143（旧口径「1 标准劳动 7 亩」的倒数形式）")
        .isEqualTo(EconomySettlement.LABOR_MILLI_PER_MU)
        .isEqualTo(143L);
    assertThat(farm.get("outputPerUnit").get("grain").asLong()).isEqualTo(67L);
    assertThat(farm.get("outputPerUnit").get("fiber").asLong())
        .as("★★ 田里**同时**出粮与纤维（多商品产出的判据所在）")
        .isEqualTo(EconomySeeder.FIBER_OUTPUT_PER_MU);

    assertThat(weave.get("capacityPerUnit").get("TOOL").asLong())
        .as("纺织：单位规模 = 1 台织机")
        .isEqualTo(1L);
    assertThat(weave.get("laborPerUnit").asLong())
        .as("每台织机一个标准劳动")
        .isEqualTo(EconomySeeder.LABOR_MILLI_PER_LOOM);
    assertThat(weave.get("outputPerUnit").get("cloth").asLong())
        .isEqualTo(EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE);
    assertThat(weave.get("cycleInputPerUnit").get("TOOL").get("fiber").asLong())
        .as("每台织机一周期耗纤维 = 产布 × 每匹耗纤维（同一条口径，不拍第二个数）")
        .isEqualTo(EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH);
    assertThat(weave.get("regime").asText())
        .as("家户自给（不是庄园、也不是作坊）")
        .isEqualTo(EconomySeeder.REGIME_HOUSEHOLD);

    JsonNode craft = entry(payload(), 1, 0).get("industries").get(2);
    assertThat(craft.get("capacity").get("WORKSHOP").asLong())
        .as("作坊：本格产能 = 200 ÷ 50 = 4 座")
        .isEqualTo(200L / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP);
    assertThat(craft.get("capacityPerUnit").get("WORKSHOP").asLong())
        .as("★ 作坊的产能**不是土地**（非 LAND 生产成立）")
        .isEqualTo(1L);
    assertThat(craft.get("outputPerUnit").get("cloth").asLong())
        .isEqualTo(EconomySeeder.CLOTH_PER_WORKSHOP_PER_CYCLE);
    assertThat(craft.get("outputPerUnit").get("tool").asLong())
        .isEqualTo(EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE);
    assertThat(craft.get("cycleInputPerUnit").get("WORKSHOP").get("iron").asLong())
        .as("★「消耗 IRON」这句原来表达不了 —— R3 的换型就是为了它")
        .isEqualTo(EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE * EconomySeeder.IRON_MILLI_PER_TOOL);
  }

  /**
   * ★★ **R3（T4）"男耕女织"的默认配置权重**（spec §四：性别影响各类劳动活动的默认配置权重，**不是**"女 = 纺织"的硬编码）。
   *
   * <p>判据：同一个农村池的**同一性别**，在农业与纺织上的配额占比**相反**；且两者都不是 0（男人照样进纺织）。
   */
  @Test
  void sexWeightsTiltFarmToMenAndWeavingToWomen() throws Exception {
    List<JsonNode> allocations = toList(entry(payload(), 0, 0).get("allocations"));

    long maleFarm = 0L;
    long femaleFarm = 0L;
    long maleWeave = 0L;
    long femaleWeave = 0L;
    for (JsonNode node : allocations) {
      boolean male = node.get("group").asText().contains(":MALE:");
      boolean weaving = "weave".equals(node.get("activity").asText());
      long labor = node.get("laborMilli").asLong();
      if (weaving) {
        if (male) {
          maleWeave += labor;
        } else {
          femaleWeave += labor;
        }
      } else if (male) {
        maleFarm += labor;
      } else {
        femaleFarm += labor;
      }
    }
    assertThat(maleFarm).as("★ 男耕：男性在农业上的配额 > 女性").isGreaterThan(femaleFarm);
    assertThat(femaleWeave).as("★ 女织：女性在纺织上的配额 > 男性").isGreaterThan(maleWeave);
    assertThat(maleWeave).as("★ 不是「女 = 纺织」的硬编码：男人也有一条非零的纺织配额").isPositive();
    assertThat(maleFarm * 1000L / (maleFarm + femaleFarm))
        .as("农业的男性权重 = 600‰（{@code ACTIVITY_SEX_WEIGHT_PER_MILLE}）")
        .isEqualTo(600L);
    assertThat(femaleWeave * 1000L / (maleWeave + femaleWeave))
        .as("纺织的女性权重 = 800‰")
        .isEqualTo(800L);
  }

  @Test
  void laborAndRationAreTheDocumentedPerCapitaValues() {
    assertThat(EconomySeeder.laborMilli(1000L)).isEqualTo(580_000L);
    assertThat(EconomySeeder.laborMilli(0L)).isZero();
    assertThat(EconomySeeder.firstDayRationMilli(1000L))
        .as("创世写下的需求 = 第 1 天口粮 = floor(1000 × 10,000 ÷ 120)")
        .isEqualTo(83_333L);
    assertThat(EconomySeeder.rationMilli(1000L, "middle_peasant"))
        .as("中农 60 天 = cumulativeRationMilli(1000, 60) = 1000 × 10,000 × 60 ÷ 120")
        .isEqualTo(5_000_000L);
  }
}
