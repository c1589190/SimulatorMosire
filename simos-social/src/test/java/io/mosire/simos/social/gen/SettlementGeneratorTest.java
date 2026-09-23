package io.mosire.simos.social.gen;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link SettlementGenerator} 的判别力优先护栏。真档用例（1/2/3/6/8）用 {@link RealNations}
 * 的三国真参数与真地形；启发式用例（4/5/7/9）用 {@link SyntheticTerrain} 把单条规则照亮。
 *
 * <p>★ 每条都**验行为**，不是"跑完就算"：守恒断言的是三条等式、河流/沿海断言的是比值严格变大、稀有惩罚断言的是分布真的被压平、迭代序无关断言的是 **逐字段相等**。
 */
class SettlementGeneratorTest {

  private static final long FIXTURE_SEED = 20260923L;

  // ── 1. 同 seed 同输出 ──

  @Test
  void sameSeedProducesFieldByFieldIdenticalPlan() {
    SettlementRequest request = RealNations.request(RealNations.DEUTSCHES_REICH);
    SettlementPlan first = SettlementGenerator.generate(request, RealNations.terrainView());
    SettlementPlan second = SettlementGenerator.generate(request, RealNations.terrainView());

    assertThat(second).as("同 request 两次生成必须逐字段相等").isEqualTo(first);
    assertThat(first.cities()).as("真档三国必能产出城市").isNotEmpty();
  }

  // ── 2. ★ 迭代序无关（能抓住"顺着 Map 顺序抽随机数"）──

  @Test
  void planIsIndependentOfHexIterationOrder() {
    SettlementRequest request = RealNations.request(RealNations.DEUTSCHES_REICH);
    SettlementPlan baseline = SettlementGenerator.generate(request, RealNations.terrainView());

    List<HexCoord> original = new ArrayList<>(request.hexes());

    // 反序：与任何"排序后"的实现序都不同 ⇒ 若生成器顺着输入序抽随机数，这里必炸。
    Set<HexCoord> reversed = new LinkedHashSet<>();
    for (int i = original.size() - 1; i >= 0; i--) {
      reversed.add(original.get(i));
    }
    // 循环右移 7：第三种互不相同的迭代序。
    Set<HexCoord> rotated = new LinkedHashSet<>();
    for (int i = 0; i < original.size(); i++) {
      rotated.add(original.get((i + 7) % original.size()));
    }
    // 故意**不**用 TreeSet 当变异体：它的迭代序恰好等于生成器内部的排序序，证明力为零（只会假绿）。
    assertThat(new TreeSet<>(original)).as("TreeSet 只是占位对照，不作判别用").hasSize(original.size());

    assertThat(SettlementGenerator.generate(request.withHexes(reversed), RealNations.terrainView()))
        .as("反序插入必须与基准逐字段相等")
        .isEqualTo(baseline);
    assertThat(SettlementGenerator.generate(request.withHexes(rotated), RealNations.terrainView()))
        .as("循环右移必须与基准逐字段相等")
        .isEqualTo(baseline);
  }

  // ── 3. 守恒：三国真参数各跑一次 ──

  @Test
  void conservationHoldsForAllThreeRealNations() {
    for (String name : RealNations.ALL) {
      SettlementRequest request = RealNations.request(name);
      SettlementPlan plan = SettlementGenerator.generate(request, RealNations.terrainView());

      long urbanTotal = Math.round(request.totalPopulation() * request.urbanizationRate());
      long ruralTotal = request.totalPopulation() - urbanTotal;
      long sumRural = plan.ruralPopulation().values().stream().mapToLong(Long::longValue).sum();
      long sumCity = plan.cities().stream().mapToLong(PlannedCity::population).sum();

      assertThat(sumRural).as("%s：Σ rural", name).isEqualTo(ruralTotal);
      assertThat(sumCity).as("%s：Σ city.population", name).isEqualTo(urbanTotal);
      assertThat(sumRural + sumCity).as("%s：总人口守恒", name).isEqualTo(request.totalPopulation());

      printSummary(name, request, plan, urbanTotal, ruralTotal);
    }
    // 钉住冻结输入里唯一有城市人口锚点的那国：623 万 × 12% = 747,600。
    assertThat(Math.round(6_230_000L * 0.12)).isEqualTo(747_600L);
  }

  // ── 4. 地形单调：plains-only 的农村承载力 > low_hills-only ──

  @Test
  void plainsRegionCarriesMoreThanLowHillsRegion() {
    Set<HexCoord> hexes = grid(4, 3);
    SyntheticTerrain plains = new SyntheticTerrain();
    SyntheticTerrain hills = new SyntheticTerrain();
    for (HexCoord c : hexes) {
      plains.put(c, "plains");
      hills.put(c, "low_hills");
    }
    SettlementRequest request = fixture("平原国", 120_000L, 0.12, hexes);

    SettlementPlan plainsPlan = SettlementGenerator.generate(request, plains);
    SettlementPlan hillsPlan = SettlementGenerator.generate(request, hills);

    double plainsSurplus = totalSurplus(plainsPlan);
    double hillsSurplus = totalSurplus(hillsPlan);
    assertThat(plainsSurplus)
        .as("同格数同人口：plains(1.0) 的农业剩余必须严格高于 low_hills(0.6)")
        .isGreaterThan(hillsSurplus);
    // 农村分配是按权重归一到 ruralTotal 的 ⇒ 两侧 Σ rural 必然相等；"承载力"的差异体现在剩余上。
    assertThat(totalRural(plainsPlan)).isEqualTo(totalRural(hillsPlan));
  }

  // ── 5. 河流/沿海乘数生效（用比值隔离噪声）──

  @Test
  void riverAndCoastalMultipliersRaiseSurplus() {
    HexCoord riverHex = new HexCoord(0, 0);
    HexCoord plainHex = new HexCoord(1, 0);
    SyntheticTerrain terrain =
        new SyntheticTerrain().put(riverHex, "plains").put(plainHex, "plains").river(riverHex, 1);
    SettlementPlan plan =
        SettlementGenerator.generate(
            fixture("沿河国", 1_000L, 0.12, Set.of(riverHex, plainHex)), terrain);

    // surplus/ruralCapacity == 地形剩余率 × 河流乘数 × 沿海乘数 —— 比值把坐标噪声完全消掉，故是严格不等而非"大概"。
    double riverRatio = ratio(plan, riverHex);
    double plainRatio = ratio(plan, plainHex);
    assertThat(riverRatio).as("riverEdgesAt=1 的格剩余比必须严格大于不沿河的格").isGreaterThan(plainRatio);
    assertThat(riverRatio / plainRatio)
        .as("比值应等于 riverMultiplier.ordinary=1.15")
        .isCloseTo(1.15, org.assertj.core.data.Offset.offset(1e-9));

    HexCoord coastHex = new HexCoord(0, 0);
    HexCoord inlandHex = new HexCoord(1, 0);
    SyntheticTerrain coastal =
        new SyntheticTerrain().put(coastHex, "plains").put(inlandHex, "plains").coast(coastHex);
    SettlementPlan coastalPlan =
        SettlementGenerator.generate(
            fixture("沿海国", 1_000L, 0.12, Set.of(coastHex, inlandHex)), coastal);

    double coastRatio = ratio(coastalPlan, coastHex);
    double inlandRatio = ratio(coastalPlan, inlandHex);
    assertThat(coastRatio).as("沿海格剩余比必须严格大于内陆格").isGreaterThan(inlandRatio);
    assertThat(coastRatio / inlandRatio)
        .as("比值应等于 coastalMultiplier=1.2")
        .isCloseTo(1.2, org.assertj.core.data.Offset.offset(1e-9));
  }

  // ── 6. 稀有惩罚：把阈值调到很小 ⇒ 超阈值的城被压下去（比较分布）──

  @Test
  void rarityPenaltyFlattensTheDistribution() {
    // 用**无硬目标**的奥斯特马克：全体城市共用一个缩放因子 ⇒ 原始权重可由
    // raw_i ≈ pop_i × urbanCapacity / urbanTotal 反推（有硬目标时首都脱离公共缩放，这条反推不成立）。
    SettlementRequest request = RealNations.request(RealNations.OSTERMARK);
    long urbanTotal = Math.round(request.totalPopulation() * request.urbanizationRate());
    SettlementPlan baseline =
        SettlementGenerator.generate(
            request,
            RealNations.terrainView(),
            SettlementParams.defaults()
                .withRarityPenalty(new SettlementParams.RarityPenalty(Long.MAX_VALUE, 0.1)));

    PlannedCity top = topNonCapital(baseline, "马尔克堡");
    long rawTop = Math.round(top.population() * (double) baseline.urbanCapacity() / urbanTotal);
    // 阈值取反推原始权重的一半：必然惩罚到这座城，且必然留下大量原始权重更小的城不被惩罚
    // ⇒ 惩罚集是**真子集**，"同一座城的占比"才会下降（全体同乘 0.1 在归一化后是恒等变换）。
    long threshold = Math.max(1L, rawTop / 2);
    SettlementPlan penalized =
        SettlementGenerator.generate(
            request,
            RealNations.terrainView(),
            SettlementParams.defaults()
                .withRarityPenalty(new SettlementParams.RarityPenalty(threshold, 0.1)));

    assertThat(penalized.urbanCapacity())
        .as("惩罚确实生效：承载合计（Σ 原始权重）被压下去")
        .isLessThan(baseline.urbanCapacity());
    assertThat(share(penalized, top.name()))
        .as("同一座城的占比下降 ⇒ 分布被压平（身份只由坐标哈希定，两次是同一座城）")
        .isLessThan(share(baseline, top.name()));
    assertThat(populationOf(penalized, top.name())).as("该城的绝对人口也随之变小").isLessThan(top.population());
  }

  // ── 7. 首都硬目标 ──

  @Test
  void capitalHardTargetIsExactAndTierIsHighest() {
    Set<HexCoord> hexes = grid(5, 4);
    SettlementRequest anchored =
        new SettlementRequest(
            new RegionId("锚定国"),
            "锚定国",
            100_000L,
            0.2,
            1.0,
            1.1,
            1.4,
            FIXTURE_SEED,
            Optional.of(CapitalAnchor.of("Testhauptstadt", 5_000L)),
            hexes,
            List.of());
    SettlementPlan plan = SettlementGenerator.generate(anchored, allPlains(hexes));

    PlannedCity capital = cityNamed(plan, "Testhauptstadt");
    assertThat(capital.population()).as("硬目标必须精确命中").isEqualTo(5_000L);
    assertThat(capital.tier()).isEqualTo(PlannedCity.TIER_MAJOR_CITY);
    assertThat(plan.cities()).as("其余城市分剩下的城市人口（故必须不止一座城）").hasSizeGreaterThan(1);
    assertThat(plan.cities().stream().mapToLong(PlannedCity::population).sum())
        .as("硬目标不破坏守恒")
        .isEqualTo(20_000L);
  }

  @Test
  void capitalWithoutTargetGetsFormulaPopulationAndTierIsHighest() {
    Set<HexCoord> hexes = grid(5, 4);
    SettlementRequest request =
        new SettlementRequest(
            new RegionId("无锚国"),
            "无锚国",
            100_000L,
            0.2,
            1.0,
            1.1,
            1.4,
            FIXTURE_SEED,
            Optional.of(CapitalAnchor.withoutTarget("Kleinstadt")),
            hexes,
            List.of());
    SettlementPlan plan = SettlementGenerator.generate(request, allPlains(hexes));

    PlannedCity capital = cityNamed(plan, "Kleinstadt");
    assertThat(capital.tier()).as("无硬目标时首都仍取最高等级").isEqualTo(PlannedCity.TIER_MAJOR_CITY);
    assertThat(capital.population()).as("无硬目标时规模由公式给出（> 0）").isPositive();
    // 本夹具是**同质平原**（无河流/沿海/地形落差），故"政治乘数吃大头"必然让首都成为最大城。
    // ★ 真档的异质地形下这条**不保证**：内陆首都可能输给一块河口/沿海的好地（见交付报告"与预期不符之处"）。
    assertThat(plan.cities().stream().mapToLong(PlannedCity::population).max().orElse(-1))
        .as("同质夹具下首都吃政治乘数大头 ⇒ 应为最大城")
        .isEqualTo(capital.population());
  }

  // ── 8. Zipf 更碎：海量小城、极少大城 ──

  @Test
  void zipfShapeYieldsManySmallTownsAndAtMostOneMajorCity() {
    for (String name : RealNations.ALL) {
      SettlementRequest request = RealNations.request(name);
      SettlementPlan plan = SettlementGenerator.generate(request, RealNations.terrainView());

      long major =
          plan.cities().stream().filter(c -> PlannedCity.TIER_MAJOR_CITY.equals(c.tier())).count();
      long big = plan.cities().stream().filter(c -> c.population() > 100_000L).count();
      long small = plan.cities().stream().filter(c -> c.population() < 10_000L).count();

      assertThat(major).as("%s：MajorCity 至多一座", name).isLessThanOrEqualTo(1L);
      assertThat(big).as("%s：十万人级城市至多一座", name).isLessThanOrEqualTo(1L);
      assertThat(small * 2)
          .as("%s：多数城市是万人以下小城", name)
          .isGreaterThanOrEqualTo((long) plan.cities().size());
    }
  }

  // ── 9. 海洋格缺席（不是 0）──

  @Test
  void oceanHexIsAbsentFromRuralPopulation() {
    HexCoord land1 = new HexCoord(0, 0);
    HexCoord land2 = new HexCoord(1, 0);
    HexCoord sea = new HexCoord(0, 1);
    SyntheticTerrain terrain =
        new SyntheticTerrain().put(land1, "plains").put(land2, "plains").put(sea, "ocean");
    SettlementPlan plan =
        SettlementGenerator.generate(
            fixture("海国", 1_000L, 0.12, Set.of(land1, land2, sea)), terrain);

    assertThat(plan.ruralPopulation()).as("海洋格必须缺席，而不是 0").doesNotContainKey(sea);
    assertThat(plan.audit()).as("审计表同样不含海洋格").doesNotContainKey(sea);
    assertThat(plan.ruralPopulation()).containsKeys(land1, land2).hasSize(2);
  }

  // ── 9b. 承载缺口：承载 < 需求时必须显式报出，绝不静默调低 total ──

  @Test
  void carryingShortfallIsReportedExplicitly() {
    Set<HexCoord> hexes = grid(4, 3);
    // 极低的农业剩余率（0.0001）把"城市承载"压到需求之下 —— 三国真参数下这个分支**从不发生**（承载是需求的 5~7 倍），
    // 所以只能这样造。缺口的纪律是"报出来"，不是"把 total 调低"。
    SettlementRequest request =
        new SettlementRequest(
            new RegionId("贫瘠国"),
            "贫瘠国",
            100_000L,
            0.5,
            0.0001,
            1.0,
            1.0,
            FIXTURE_SEED,
            Optional.empty(),
            hexes,
            List.of());
    SettlementPlan plan = SettlementGenerator.generate(request, allPlains(hexes));

    long urbanTotal = 50_000L;
    assertThat(plan.shortfall()).as("承载小于需求 ⇒ 缺口 > 0").isPositive();
    assertThat(plan.urbanCapacity()).as("承载合计确实远低于需求").isLessThan(urbanTotal);
    assertThat(plan.shortfall()).isEqualTo(urbanTotal - plan.urbanCapacity());
    assertThat(plan.cities().stream().mapToLong(PlannedCity::population).sum())
        .as("★ total 没有被静默调低：Σ city 仍严格等于 urbanTotal")
        .isEqualTo(urbanTotal);
    assertThat(plan.cities().get(0).justification()).as("缺口写进 justification，可读").contains("城市承载缺口");
  }

  // ── 工具 ──

  private static SettlementRequest fixture(
      String name, long population, double urbanization, Set<HexCoord> hexes) {
    return new SettlementRequest(
        new RegionId(name),
        name,
        population,
        urbanization,
        1.0,
        1.0,
        1.0,
        FIXTURE_SEED,
        Optional.empty(),
        hexes,
        List.of());
  }

  /** (2i, 2j) 网格：两两距离 ≥ 2 ⇒ 不被最小距离抑制吃掉（用来观察"有不止一座城"）。 */
  private static Set<HexCoord> grid(int columns, int rows) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (int i = 0; i < columns; i++) {
      for (int j = 0; j < rows; j++) {
        hexes.add(new HexCoord(2 * i, 2 * j));
      }
    }
    return hexes;
  }

  private static SyntheticTerrain allPlains(Set<HexCoord> hexes) {
    SyntheticTerrain terrain = new SyntheticTerrain();
    for (HexCoord c : hexes) {
      terrain.put(c, "plains");
    }
    return terrain;
  }

  private static double ratio(SettlementPlan plan, HexCoord hex) {
    SurplusEstimate estimate = plan.audit().get(hex);
    assertThat(estimate).as("审计表必须有这一格").isNotNull();
    assertThat(estimate.ruralCapacity()).as("农村人口必须 > 0，比值才有意义").isPositive();
    return estimate.surplusPotential() / estimate.ruralCapacity();
  }

  private static double totalSurplus(SettlementPlan plan) {
    return plan.audit().values().stream().mapToDouble(SurplusEstimate::surplusPotential).sum();
  }

  private static long totalRural(SettlementPlan plan) {
    return plan.ruralPopulation().values().stream().mapToLong(Long::longValue).sum();
  }

  private static PlannedCity cityNamed(SettlementPlan plan, String name) {
    return plan.cities().stream()
        .filter(city -> name.equals(city.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有名为 " + name + " 的城市"));
  }

  private static PlannedCity topNonCapital(SettlementPlan plan, String capitalName) {
    return plan.cities().stream()
        .filter(city -> !capitalName.equals(city.name()))
        .max(Comparator.comparingLong(PlannedCity::population))
        .orElseThrow(() -> new AssertionError("没有可比较的非首都城市"));
  }

  private static long populationOf(SettlementPlan plan, String name) {
    return cityNamed(plan, name).population();
  }

  private static double share(SettlementPlan plan, String name) {
    long total = plan.cities().stream().mapToLong(PlannedCity::population).sum();
    return total == 0L ? 0.0 : (double) populationOf(plan, name) / total;
  }

  private static void printSummary(
      String name,
      SettlementRequest request,
      SettlementPlan plan,
      long urbanTotal,
      long ruralTotal) {
    Map<String, Long> tiers = new java.util.TreeMap<>();
    for (PlannedCity city : plan.cities()) {
      tiers.merge(city.tier(), 1L, Long::sum);
    }
    PlannedCity largest =
        plan.cities().stream()
            .max((a, b) -> Long.compare(a.population(), b.population()))
            .orElseThrow();
    String capitalNote = "none";
    if (request.capital().isPresent()) {
      String capitalName = request.capital().get().name();
      PlannedCity capital =
          plan.cities().stream().filter(c -> capitalName.equals(c.name())).findFirst().orElse(null);
      capitalNote =
          capital == null
              ? capitalName + "(未成为城市)"
              : capital.name()
                  + "("
                  + capital.population()
                  + ","
                  + capital.tier()
                  + ")@"
                  + capital.at();
    }
    System.out.printf(
        Locale.ROOT,
        "SUMMARY %s: hexes=%d rural=%d city=%d total=%d cities=%d tiers=%s largest=%s(%d)@%s capital=%s urbanCapacity=%d shortfall=%d%n",
        name,
        request.hexes().size(),
        totalRural(plan),
        plan.cities().stream().mapToLong(PlannedCity::population).sum(),
        request.totalPopulation(),
        plan.cities().size(),
        tiers,
        largest.name(),
        largest.population(),
        largest.at(),
        capitalNote,
        plan.urbanCapacity(),
        plan.shortfall());
    assertThat(ruralTotal + urbanTotal).isEqualTo(request.totalPopulation());
  }
}
