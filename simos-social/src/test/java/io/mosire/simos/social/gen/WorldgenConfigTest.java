package io.mosire.simos.social.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorldgenConfig}（生产版加载器）与"范围内随机化"的判别力优先护栏。
 *
 * <p>★ 真档 JSON 从 {@link RealNations#nationsFile()} 取；"开随机化"的档由**读真档 + 翻开 enabled**写成临时文件得到 —— 这样出厂
 * JSON 保持 {@code enabled=false}，而随机化路径仍然被端到端走到。
 *
 * <p>★ **解析界不从 {@link ValueRange#min()}/{@link ValueRange#max()} 取**，而是测试自己按 JSON 的 {@code
 * jitterPct × clamp} 独立算一遍：拿被测实现自己算出来的界去对被测实现，是恒真（假绿）。
 */
class WorldgenConfigTest {

  private static final int SEED_COUNT = 200;

  /** 抖动/夹紧涉及的标量键（与配置的 {@code randomization.jitterPct} 同名）。 */
  private static final List<String> SCALAR_KEYS =
      List.of(
          "population",
          "urbanizationRate",
          "agrarianSurplusRate",
          "commercialIntegration",
          "politicalCentralization",
          "capitalTargetPopulation");

  // ── 1. enabled=false ⇒ 逐字段等于真档文档值（回归保护）──

  @Test
  void disabledRandomizationResolvesToDocumentedValues() {
    WorldgenConfig config = WorldgenConfig.load(RealNations.nationsFile());
    assertThat(config.nations()).as("三国").hasSize(3);

    for (String name : RealNations.ALL) {
      ResolvedNation resolved = config.byRegionId(name).withHexes(hexesOf(name)).resolve();
      assertThat(resolved.request())
          .as("%s：关随机化时，解析结果必须逐字段等于真档文档值（即 RealNations 读同一份档的结果）", name)
          .isEqualTo(RealNations.request(name));
      assertThat(resolved.params())
          .as("%s：defaults 解析必须与 SettlementParams.defaults() 逐字段一致（解析器保真）", name)
          .isEqualTo(SettlementParams.defaults());
    }
  }

  @Test
  void armyAndArmKitsAreExposedForP4() {
    WorldgenConfig config = WorldgenConfig.load(RealNations.nationsFile());
    NationSetup german = config.byRegionId(RealNations.DEUTSCHES_REICH);

    assertThat(german.army().peacetime()).isEqualTo(20_000);
    assertThat(german.army().mobilization()).isEqualTo(25_000);
    assertThat(german.army().establishment())
        .as("Turn6 编制九项（含文档里没有 armKit 的『仆从兵』）")
        .containsEntry("重骑兵", 3000)
        .containsEntry("轻步兵", 6500)
        .containsEntry("仆从兵", 3000)
        .hasSize(9);
    assertThat(german.army().establishment().values().stream().mapToInt(Integer::intValue).sum())
        .as("编制实数 25500（文档标题 2.5 万，差 500 如实保留）")
        .isEqualTo(25_500);
    assertThat(german.army().armKits())
        .as("顶层 armKits 表被每个国家共享暴露")
        .containsKey("重骑兵")
        .containsEntry("攻城兵", Map.of("重炮", 3, "火炮", 8, "攻城器械", 20, "火药磅", 3000));
    assertThat(config.byRegionId(RealNations.HOCHLAND).army().establishment())
        .as("霍赫兰编制含水军 1500")
        .containsEntry("水军", 1500);
  }

  // ── 2. ★ 区间断言：200 个 seed 都落在 jitterPct × clamp 推出的解析界内 ──

  @Test
  void randomizedScalarsStayWithinIndependentAnalyticBounds(@TempDir Path tempDir)
      throws IOException {
    JsonNode root = readRealConfig();
    Path enabled = writeWithRandomizationEnabled(tempDir, root);
    WorldgenConfig config = WorldgenConfig.load(enabled);

    for (String name : RealNations.ALL) {
      Map<String, Bounds> bounds = analyticBounds(root, name);
      NationSetup setup = config.byRegionId(name).withHexes(hexesOf(name));
      Map<String, double[]> observed = new TreeMap<>();

      for (int i = 0; i < SEED_COUNT; i++) {
        SettlementRequest request = setup.resolve(1_000L + 7L * i).request();
        for (Map.Entry<String, Bounds> entry : bounds.entrySet()) {
          double value = scalarOf(request, entry.getKey());
          assertThat(value)
              .as("%s：seed#%d 的 %s 必须落在解析界内", name, i, entry.getKey())
              .isBetween(entry.getValue().lo() - 1.0, entry.getValue().hi() + 1.0);
          double[] minMax =
              observed.computeIfAbsent(entry.getKey(), key -> new double[] {value, value});
          minMax[0] = Math.min(minMax[0], value);
          minMax[1] = Math.max(minMax[1], value);
        }
      }
      for (Map.Entry<String, double[]> entry : observed.entrySet()) {
        Bounds bound = bounds.get(entry.getKey());
        System.out.printf(
            Locale.ROOT,
            "RANDOMIZED %s %s: observed=[%s, %s] analytic=[%s, %s]%n",
            name,
            entry.getKey(),
            fmt(entry.getValue()[0]),
            fmt(entry.getValue()[1]),
            fmt(bound.lo()),
            fmt(bound.hi()));
      }
    }
  }

  // ── 3. ★ 守恒仍成立（用的是抖动后的 total）──

  @Test
  void conservationHoldsOnRandomizedVariants(@TempDir Path tempDir) throws IOException {
    WorldgenConfig config =
        WorldgenConfig.load(writeWithRandomizationEnabled(tempDir, readRealConfig()));

    for (String name : RealNations.ALL) {
      NationSetup setup = config.byRegionId(name).withHexes(hexesOf(name));
      for (long seed : List.of(7L, 1_234L, 99_999L)) {
        ResolvedNation resolved = setup.resolve(seed);
        SettlementRequest request = resolved.request();
        SettlementPlan plan =
            SettlementGenerator.generate(request, RealNations.terrainView(), resolved.params());

        long urbanTotal = Math.round(request.totalPopulation() * request.urbanizationRate());
        long ruralTotal = request.totalPopulation() - urbanTotal;
        long sumRural = plan.ruralPopulation().values().stream().mapToLong(Long::longValue).sum();
        long sumCity = plan.cities().stream().mapToLong(PlannedCity::population).sum();

        assertThat(sumRural).as("%s seed=%d：Σ rural", name, seed).isEqualTo(ruralTotal);
        assertThat(sumCity).as("%s seed=%d：Σ city.population", name, seed).isEqualTo(urbanTotal);
        assertThat(sumRural + sumCity)
            .as("%s seed=%d：总人口守恒（且用抖动后的 total）", name, seed)
            .isEqualTo(request.totalPopulation());
        System.out.printf(
            Locale.ROOT,
            "RANDOMIZED_CONSERVATION %s seed=%d: total=%d urbanTotal=%d urbanCapacity=%d shortfall=%d%n",
            name,
            seed,
            request.totalPopulation(),
            urbanTotal,
            plan.urbanCapacity(),
            plan.shortfall());
      }
    }
  }

  // ── 4. ★ 可复现：同 seed 两次 ⇒ request 与 plan 逐字段相等 ──

  @Test
  void sameSeedResolvesAndGeneratesIdentically(@TempDir Path tempDir) throws IOException {
    WorldgenConfig config =
        WorldgenConfig.load(writeWithRandomizationEnabled(tempDir, readRealConfig()));

    for (String name : RealNations.ALL) {
      NationSetup setup = config.byRegionId(name).withHexes(hexesOf(name));
      ResolvedNation first = setup.resolve(424_242L);
      ResolvedNation second = setup.resolve(424_242L);

      assertThat(second.request())
          .as("%s：同 seed 的 request 必须逐字段相等", name)
          .isEqualTo(first.request());
      SettlementPlan firstPlan =
          SettlementGenerator.generate(first.request(), RealNations.terrainView(), first.params());
      SettlementPlan secondPlan =
          SettlementGenerator.generate(
              second.request(), RealNations.terrainView(), second.params());
      assertThat(secondPlan).as("%s：同 seed 的 plan 必须逐字段相等", name).isEqualTo(firstPlan);
    }
  }

  // ── 5. ★ 真的变了：两个不同 seed ⇒ 至少某些标量不同 ──

  @Test
  void differentSeedsProduceDifferentVariants(@TempDir Path tempDir) throws IOException {
    WorldgenConfig config =
        WorldgenConfig.load(writeWithRandomizationEnabled(tempDir, readRealConfig()));

    for (String name : RealNations.ALL) {
      NationSetup setup = config.byRegionId(name).withHexes(hexesOf(name));
      List<Double> first = scalars(setup.resolve(11L).request());
      List<Double> second = scalars(setup.resolve(2_000_011L).request());
      assertThat(second).as("%s：换 seed 必须产生不同的变体（至少某些标量不同）", name).isNotEqualTo(first);
    }
  }

  // ── 6. load / byRegionId 的 fail-closed ──

  @Test
  void loadFailsClosedOnMissingAndBrokenInput(@TempDir Path tempDir) throws IOException {
    assertThatThrownBy(() -> WorldgenConfig.load(tempDir.resolve("不存在.json")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在");

    Path broken = tempDir.resolve("broken.json");
    Files.writeString(broken, "{ this is not json }", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> WorldgenConfig.load(broken))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON 非法");

    // 真档去掉 nations ⇒ 报"缺少 nations"（而不是继续往下走）
    ObjectMapper mapper = SimosObjectMapper.create();
    JsonNode root = readRealConfig();
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("nations");
    Path noNations = tempDir.resolve("no-nations.json");
    Files.writeString(noNations, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
    assertThatThrownBy(() -> WorldgenConfig.load(noNations))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nations");
  }

  @Test
  void unknownRegionIdFailsClosed() {
    WorldgenConfig config = WorldgenConfig.load(RealNations.nationsFile());
    assertThatThrownBy(() -> config.byRegionId("不存在的国"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在的国");
  }

  @Test
  void resolveBeforeHexesAreAttachedFailsClosed() {
    WorldgenConfig config = WorldgenConfig.load(RealNations.nationsFile());
    assertThatThrownBy(() -> config.byRegionId(RealNations.OSTERMARK).resolve())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("格集");
  }

  // ── 工具 ──

  private record Bounds(double lo, double hi) {}

  private static Set<HexCoord> hexesOf(String regionId) {
    return RealNations.map().regions().get(new RegionId(regionId)).hexes();
  }

  private static JsonNode readRealConfig() throws IOException {
    return SimosObjectMapper.create()
        .readTree(Files.readString(RealNations.nationsFile(), StandardCharsets.UTF_8));
  }

  private static Path writeWithRandomizationEnabled(Path tempDir, JsonNode root)
      throws IOException {
    ((ObjectNode) root.path("randomization")).put("enabled", true);
    Path out = tempDir.resolve("v17levant-nations-randomized.json");
    Files.writeString(
        out, SimosObjectMapper.create().writeValueAsString(root), StandardCharsets.UTF_8);
    return out;
  }

  /** 按 JSON 自己算解析界：{@code doc × (1 ± jitterPct)} 再与 {@code clamp} 取交。 */
  private static Map<String, Bounds> analyticBounds(JsonNode root, String regionId) {
    JsonNode nation = nationOf(root, regionId);
    JsonNode randomization =
        nation.has("randomization") ? nation.get("randomization") : root.get("randomization");
    JsonNode jitter = randomization.path("jitterPct");
    JsonNode clamp = randomization.path("clamp");

    Map<String, Bounds> bounds = new LinkedHashMap<>();
    for (String key : SCALAR_KEYS) {
      JsonNode documented = documentedNode(nation, key);
      if (documented == null || documented.isMissingNode() || documented.isNull()) {
        continue;
      }
      double value = documented.asDouble();
      double jitterPct = jitter.path(key).asDouble(0.0);
      double lo = value * (1.0 - jitterPct);
      double hi = value * (1.0 + jitterPct);
      JsonNode pair = clamp.path(key);
      if (pair.isArray() && pair.size() == 2) {
        lo = Math.max(lo, pair.get(0).asDouble());
        hi = Math.min(hi, pair.get(1).asDouble());
      }
      bounds.put(key, new Bounds(lo, hi));
    }
    return bounds;
  }

  private static JsonNode documentedNode(JsonNode nation, String key) {
    if ("capitalTargetPopulation".equals(key)) {
      return nation.path("capital").path("targetPopulation");
    }
    return nation.path(key);
  }

  private static JsonNode nationOf(JsonNode root, String regionId) {
    for (JsonNode nation : root.path("nations")) {
      if (regionId.equals(nation.path("regionId").asText())) {
        return nation;
      }
    }
    throw new AssertionError("真档里没有 " + regionId);
  }

  private static double scalarOf(SettlementRequest request, String key) {
    return switch (key) {
      case "population" -> request.totalPopulation();
      case "urbanizationRate" -> request.urbanizationRate();
      case "agrarianSurplusRate" -> request.agrarianSurplusRate();
      case "commercialIntegration" -> request.commercialIntegration();
      case "politicalCentralization" -> request.politicalCentralization();
      case "capitalTargetPopulation" -> capitalTargetOf(request);
      default -> throw new AssertionError("未知标量键 " + key);
    };
  }

  /** 首都硬目标（无首都或无目标 ⇒ {@code NaN}；{@link Double#equals} 认为 NaN 等于 NaN，故可用于列表比较）。 */
  private static double capitalTargetOf(SettlementRequest request) {
    Optional<CapitalAnchor> anchor = request.capital();
    if (anchor.isEmpty() || anchor.get().targetPopulation().isEmpty()) {
      return Double.NaN;
    }
    return anchor.get().targetPopulation().getAsLong();
  }

  private static List<Double> scalars(SettlementRequest request) {
    List<Double> values = new ArrayList<>(SCALAR_KEYS.size());
    for (String key : SCALAR_KEYS) {
      values.add(scalarOf(request, key));
    }
    return values;
  }

  private static String fmt(double value) {
    return String.format(Locale.ROOT, "%.4f", value);
  }
}
