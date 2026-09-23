package io.mosire.simos.social.gen;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一个国家的**未解析**参数：全部标量以 {@link ValueRange} 形式持有，另带首都、编制表、文档地名与默认 seed。
 *
 * <p>★ {@link #resolve(long)} 把这些区间按 seed 各取一点，落成 {@link SettlementRequest} + {@link
 * SettlementParams} 的一对（见 {@link ResolvedNation}）。这是"同一条国家参数产出**一批**既落在声明区间内、又自洽的变体"的唯一入口 —— 换 seed
 * 即换变体。
 *
 * <p>★ {@code enabled=false} 时 {@link WorldgenConfig} 给的是 {@link ValueRange#exact(double)} ⇒ {@code
 * resolve} 恒返回文档值， 与开启随机化之前**逐字节相同**。
 *
 * <p>★ **格集不在冻结输入里**：hexes 是地图的事（{@code nations[].hexCount} 只是数字）。故 {@code WorldgenConfig.load} 产出
 * 的国家 setup **不含格**，接线方（P3）用 {@link #withHexes(Set)} 把真档 region 的格集附着上来；{@link #resolve(long)}
 * 对空格集**抛**（fail-closed，不产出一个"没有格的人口请求"）。
 *
 * <p>★ 本类型不加任何 {@code isXxx()} 实例方法（见 {@link PlannedCity} 类注释）。
 *
 * @param region 区域身份
 * @param displayName 显示名
 * @param totalPopulation 总人口区间（抖动后取整，必须仍是正整数）
 * @param urbanizationRate 城市化率区间（抖动后必须落在 (0,1)）
 * @param agrarianSurplusRate 全国农业剩余率系数区间
 * @param commercialIntegration 商业整合度区间
 * @param politicalCentralization 政治中央度区间
 * @param seed 文档默认 seed；{@link #resolve()} 用它，{@link #resolve(long)} 用传入值
 * @param capital 首都（名字 + 人口硬目标区间）；无首都用 {@code Optional.empty()}
 * @param documentedPlaceNames 文档地名（按优先级）
 * @param army 军事编制（P4 才用；本笔只暴露）
 * @param params 生成器系数（来自配置 {@code defaults}）
 * @param hexes 该国格集；{@code WorldgenConfig.load} 给空集，接线方 {@link #withHexes} 附着
 */
public record NationSetup(
    RegionId region,
    String displayName,
    ValueRange totalPopulation,
    ValueRange urbanizationRate,
    ValueRange agrarianSurplusRate,
    ValueRange commercialIntegration,
    ValueRange politicalCentralization,
    long seed,
    Optional<CapitalSeed> capital,
    List<String> documentedPlaceNames,
    ArmyPlan army,
    SettlementParams params,
    Set<HexCoord> hexes) {

  /** 未解析的首都：名字 + 人口硬目标区间（{@code empty()} = 文档没给人口数字，规模由公式给出）。 */
  public record CapitalSeed(String name, Optional<ValueRange> targetPopulation) {
    public CapitalSeed {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("首都名不得为空白");
      }
      if (targetPopulation == null) {
        throw new IllegalArgumentException("targetPopulation 不得为 null（无目标用 Optional.empty()）");
      }
    }
  }

  // ★ 用途盐：同一国家的不同标量必须给互不相同的盐，否则各标量会同步抖动（伪相关）。
  private static final long SALT_POPULATION = 0x71L;
  private static final long SALT_URBANIZATION = 0x72L;
  private static final long SALT_AGRARIAN = 0x73L;
  private static final long SALT_COMMERCIAL = 0x74L;
  private static final long SALT_CENTRALIZATION = 0x75L;
  private static final long SALT_CAPITAL_TARGET = 0x76L;

  public NationSetup {
    if (region == null) {
      throw new IllegalArgumentException("region 不得为 null");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName 不得为空白");
    }
    requireRange(totalPopulation, "totalPopulation");
    requireRange(urbanizationRate, "urbanizationRate");
    requireRange(agrarianSurplusRate, "agrarianSurplusRate");
    requireRange(commercialIntegration, "commercialIntegration");
    requireRange(politicalCentralization, "politicalCentralization");
    if (capital == null) {
      throw new IllegalArgumentException("capital 不得为 null（无首都用 Optional.empty()）");
    }
    if (documentedPlaceNames == null) {
      throw new IllegalArgumentException("documentedPlaceNames 不得为 null（无文档名用 List.of()）");
    }
    List<String> names = new ArrayList<>(documentedPlaceNames.size());
    for (String name : documentedPlaceNames) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("documentedPlaceNames 不得含空白项: " + name);
      }
      names.add(name);
    }
    documentedPlaceNames = Collections.unmodifiableList(names);
    if (army == null) {
      throw new IllegalArgumentException("army 不得为 null");
    }
    if (params == null) {
      throw new IllegalArgumentException("params 不得为 null");
    }
    if (hexes == null) {
      throw new IllegalArgumentException("hexes 不得为 null（未附着用 Set.of()）");
    }
    hexes = Collections.unmodifiableSet(new LinkedHashSet<>(hexes));
  }

  private static void requireRange(ValueRange range, String field) {
    if (range == null) {
      throw new IllegalArgumentException(field + " 不得为 null");
    }
  }

  /** 附着该国格集（其余不动）。{@code WorldgenConfig.load} 产出的 setup 不含格，接线方用它补上。 */
  public NationSetup withHexes(Set<HexCoord> value) {
    return new NationSetup(
        region,
        displayName,
        totalPopulation,
        urbanizationRate,
        agrarianSurplusRate,
        commercialIntegration,
        politicalCentralization,
        seed,
        capital,
        documentedPlaceNames,
        army,
        params,
        value);
  }

  /** 用文档默认 seed 解析（{@code enabled=false} 时即"与今天逐字节相同"的那一组）。 */
  public ResolvedNation resolve() {
    return resolve(seed);
  }

  /**
   * 按 {@code seedOverride} 把各区间各取一点，落成 {@link SettlementRequest} + {@link SettlementParams}。
   *
   * <p>★ 请求的 {@code seed} 也被设为 {@code seedOverride} ⇒ 同一 seed 解析出的变体可复现地喂给生成器。
   *
   * @throws IllegalArgumentException 格集未附着、人口抖动成非正数、城市化率抖动出 (0,1)
   */
  public ResolvedNation resolve(long seedOverride) {
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException(
          "国家 " + displayName + " 还没有附着格集：resolve 前先 withHexes(region.hexes())");
    }
    long population = Math.round(totalPopulation.resolve(seedOverride, SALT_POPULATION));
    if (population <= 0L) {
      throw new IllegalArgumentException("国家 " + displayName + " 的人口抖动后必须仍是正整数，得到 " + population);
    }
    double urbanization = urbanizationRate.resolve(seedOverride, SALT_URBANIZATION);
    if (!(urbanization > 0.0 && urbanization < 1.0)) {
      throw new IllegalArgumentException(
          "国家 " + displayName + " 的城市化率抖动后必须落在 (0,1)，得到 " + urbanization);
    }
    Optional<CapitalAnchor> anchor =
        capital.map(
            seedCapital ->
                seedCapital
                    .targetPopulation()
                    .map(
                        target ->
                            CapitalAnchor.of(
                                seedCapital.name(),
                                Math.round(target.resolve(seedOverride, SALT_CAPITAL_TARGET))))
                    .orElseGet(() -> CapitalAnchor.withoutTarget(seedCapital.name())));
    SettlementRequest request =
        new SettlementRequest(
            region,
            displayName,
            population,
            urbanization,
            agrarianSurplusRate.resolve(seedOverride, SALT_AGRARIAN),
            commercialIntegration.resolve(seedOverride, SALT_COMMERCIAL),
            politicalCentralization.resolve(seedOverride, SALT_CENTRALIZATION),
            seedOverride,
            anchor,
            hexes,
            documentedPlaceNames);
    return new ResolvedNation(request, params);
  }
}
