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
 * 单个国家/区域的人口生成请求。**全部输入都在这里** —— 生成器不再从别处读任何东西（参数在 {@link SettlementParams}，地形在 {@link
 * TerrainView}）。
 *
 * <p>字段逐条对应冻结输入 {@code config/worldgen/v17levant-nations.json} 的 {@code nations[]}：{@code regionId}
 * / {@code displayName} / {@code population} / {@code urbanizationRate} / {@code
 * agrarianSurplusRate} / {@code commercialIntegration} / {@code politicalCentralization} / {@code
 * seed} / {@code capital} / {@code documentedPlaceNames}；{@code hexes} 来自真档 region 的格集。
 *
 * <p>★★ **{@code hexes} 故意"保序不规范化"**（{@code Collections.unmodifiableSet(LinkedHashSet)}，**不是**
 * {@code Set.copyOf}）：迭代序无关必须由**生成器自己**保证，不能靠构造器把顺序悄悄抹平 —— 抹平了，"换一种插入顺序结果不变"那条用例就变成恒真 （假绿）。同理
 * {@code documentedNames} 用保序不可变 {@code List}（它是有优先级的序列，不是集合）。
 *
 * <p>★ **本类型不加任何 {@code isXxx()} 实例方法**（Jackson 内省会把它当 property 写进 JSON，多字段会炸严格读入）。
 *
 * @param region 区域身份（只做透传，生成器不解释它）
 * @param displayName 显示名；**空白即抛**
 * @param totalPopulation 总人口（农村 + 城市）；**不得为负**
 * @param urbanizationRate 城市化率，落在 [0,1]
 * @param agrarianSurplusRate 全国农业剩余率系数（三国 1.0 / 1.05 / 0.95）
 * @param commercialIntegration 商业整合度（叠进贸易乘数）
 * @param politicalCentralization 政治中央度（叠进政治乘数，**首都吃大头**）
 * @param seed 随机种子；所有随机数都是"种子 × 坐标 × 用途"的哈希，与迭代序无关
 * @param capital 首都锚点；{@code Optional.empty()} = 本区域不指定首都（则无城市被命名/锚定）
 * @param hexes 该国全部格；**不得为空、保序不可变、迭代序不被生成器依赖**
 * @param documentedNames 文档地名（按优先级，通常首都名已在 {@code capital} 里）；可空列表。**本字段是对给定签名的扩展** —— 冻结输入里的
 *     {@code documentedPlaceNames} 若没有落点就无从兑现"文档名优先给高等级城"这条要求。
 */
public record SettlementRequest(
    RegionId region,
    String displayName,
    long totalPopulation,
    double urbanizationRate,
    double agrarianSurplusRate,
    double commercialIntegration,
    double politicalCentralization,
    long seed,
    Optional<CapitalAnchor> capital,
    Set<HexCoord> hexes,
    List<String> documentedNames) {

  public SettlementRequest {
    if (region == null) {
      throw new IllegalArgumentException("region 不得为 null");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName 不得为空白");
    }
    if (totalPopulation < 0) {
      throw new IllegalArgumentException("totalPopulation 不得为负: " + totalPopulation);
    }
    requireRate(urbanizationRate, "urbanizationRate", 1.0);
    requireRate(agrarianSurplusRate, "agrarianSurplusRate", Double.MAX_VALUE);
    requireRate(commercialIntegration, "commercialIntegration", Double.MAX_VALUE);
    requireRate(politicalCentralization, "politicalCentralization", Double.MAX_VALUE);
    if (capital == null) {
      throw new IllegalArgumentException("capital 不得为 null（无首都用 Optional.empty()）");
    }
    if (hexes == null || hexes.isEmpty()) {
      throw new IllegalArgumentException("hexes 不得为空：没有格就没有人口可放");
    }
    if (documentedNames == null) {
      throw new IllegalArgumentException("documentedNames 不得为 null（无文档名用 List.of()）");
    }
    // ★ 保序不规范化：见类注释（Set.copyOf 会把那条"迭代序无关"用例变成假绿）。
    hexes = Collections.unmodifiableSet(new LinkedHashSet<>(hexes));
    List<String> names = new ArrayList<>(documentedNames.size());
    for (String name : documentedNames) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("documentedNames 不得含空白项: " + name);
      }
      names.add(name);
    }
    documentedNames = Collections.unmodifiableList(names);
  }

  private static void requireRate(double value, String field, double upperBound) {
    if (!Double.isFinite(value) || value < 0 || value > upperBound) {
      throw new IllegalArgumentException(field + " 必须落在 [0," + upperBound + "]: " + value);
    }
  }

  /** 换格集（其余不动）。 */
  public SettlementRequest withHexes(Set<HexCoord> value) {
    return new SettlementRequest(
        region,
        displayName,
        totalPopulation,
        urbanizationRate,
        agrarianSurplusRate,
        commercialIntegration,
        politicalCentralization,
        seed,
        capital,
        value,
        documentedNames);
  }

  /** 换首都锚点（其余不动）。 */
  public SettlementRequest withCapital(Optional<CapitalAnchor> value) {
    return new SettlementRequest(
        region,
        displayName,
        totalPopulation,
        urbanizationRate,
        agrarianSurplusRate,
        commercialIntegration,
        politicalCentralization,
        seed,
        value,
        hexes,
        documentedNames);
  }

  /** 换种子（其余不动）。 */
  public SettlementRequest withSeed(long value) {
    return new SettlementRequest(
        region,
        displayName,
        totalPopulation,
        urbanizationRate,
        agrarianSurplusRate,
        commercialIntegration,
        politicalCentralization,
        value,
        capital,
        hexes,
        documentedNames);
  }
}
