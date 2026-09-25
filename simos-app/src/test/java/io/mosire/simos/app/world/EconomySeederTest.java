package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link EconomySeeder} 的纯函数用例（R2a）：**3 个格**（平原纯农村 / 低丘农村+城市 / 平原只有城市）——逐值断言 人口守恒、土地守恒、日耗 = 人口 × 83
 * 毫粮。
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
    return JSON.readTree(EconomySeeder.payload("Map1", plan(), TERRAIN::get));
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

  /** 取整残差按槽位 id 序分派：333 按 450/350/150/50 切（333×450% = 149.85 ⇒ 149…）⇒ Σ 仍恰为 333。 */
  @Test
  void roundingResidualKeepsTheTotalAndGoesToTheFirstSlotsInOrder() throws Exception {
    List<JsonNode> farm = classes(entry(payload(), 1, 0), "farm@1_0");

    assertThat(farm)
        .extracting(node -> node.get("population").asLong())
        .containsExactly(150L, 117L, 50L, 16L);
    assertThat(sumOf(farm, "population")).isEqualTo(333L);
  }

  // ── 土地守恒 ─────────────────────────────────────────────────────────────────────────

  @Test
  void everyHexKeepsItsLandAndLandFollowsTerrainCoefficient() throws Exception {
    JsonNode payload = payload();

    long plainsLand = 1000L * 1000L; // muPerHex 1000 亩 × 1000 千分亩/亩 × 系数 1.0
    long hillsLand = plainsLand * 600L / 1000L; // 低丘系数 0.6
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

  // ── 日耗与库存（§十"消费"83 毫粮/人·日、库存 60 天）────────────────────────────────────

  @Test
  void dailyNeedIsEightyThreeMilliGrainPerPersonAndStockIsSixtyDays() throws Exception {
    JsonNode payload = payload();

    List<JsonNode> farm = classes(entry(payload, 0, 0), "farm@0_0");
    assertThat(sumOfNested(farm, "naturalNeeds", "grain")).as("1000 人 × 83").isEqualTo(83_000L);
    assertThat(sumOfNested(farm, "goods", "grain")).as("83 × 60 天").isEqualTo(4_980_000L);
    for (JsonNode row : farm) {
      long population = row.get("population").asLong();
      assertThat(row.get("naturalNeeds").get("grain").asLong())
          .as("逐值：日耗 == 人口 × 83")
          .isEqualTo(population * 83L);
    }

    List<JsonNode> craft = classes(entry(payload, 1, 0), "craft@1_0");
    assertThat(sumOfNested(craft, "naturalNeeds", "grain")).as("200 人 × 83").isEqualTo(16_600L);
    assertThat(sumOfNested(craft, "goods", "grain")).isEqualTo(16_600L * 60L);
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
    String first = EconomySeeder.payload("Map1", plan(), TERRAIN::get);
    String second = EconomySeeder.payload("Map1", plan(), TERRAIN::get);

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
        .as("3 人按 450/350/150/50 切 ⇒ 地板除 1/1/0/0，残差 1 落在第一槽 ⇒ Σ 仍恰为 3")
        .containsExactly(2L, 1L, 0L, 0L);
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
  void terrainCoefficientOnlyKnowsPlainAndLowHills() {
    assertThat(EconomySeeder.terrainCoefPerMille("plains")).isEqualTo(1000);
    assertThat(EconomySeeder.terrainCoefPerMille("low_hills")).isEqualTo(600);
    assertThatThrownBy(() -> EconomySeeder.terrainCoefPerMille("desert"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("desert");
  }

  @Test
  void laborAndRationAreTheDocumentedPerCapitaValues() {
    assertThat(EconomySeeder.laborMilli(1000L)).isEqualTo(580_000L);
    assertThat(EconomySeeder.laborMilli(0L)).isZero();
    assertThat(EconomySeeder.dailyGrainMilli(1000L)).isEqualTo(83_000L);
    assertThat(EconomySeeder.rationMilli(1000L)).isEqualTo(4_980_000L);
  }
}
