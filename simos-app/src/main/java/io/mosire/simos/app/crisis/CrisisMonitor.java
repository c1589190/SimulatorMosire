package io.mosire.simos.app.crisis;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ **生活资料 / 社会再生产危机的红灯**（R4 的 T3；spec §七 的 {@code CrisisMonitor}，**薄**）。
 *
 * <p>★★ **它输出的是一件事的**类别**，不是一个概率**（spec §七 原文："它输出的是'某地发生生活资料/社会再生产危机'， 不是'暴动概率 83%'"）：本类逐格给出
 * {@link Kind} 的**清单**，外加**当场量到的数字**（满足率、死亡数、占用率…）作为依据。 **没有**"发生暴动的可能性"这种字段 ——
 * 那需要一套本阶段**不存在**的政治模型，编出来就是把推测当数据。
 *
 * <p>★★ **本阶段绝不生成起义机制**（spec §七 原文）：本类**只读**，不产生任何命令、事件或状态改动。起义、逃亡、抢粮、 请愿、救济一律留给以后的政治系统。
 *
 * <p>★ **判据（spec §七 列的五项）与实现**：
 *
 * <table>
 *   <caption>判据落点</caption>
 *   <tr><th>spec 列的</th><th>本类的落点</th></tr>
 *   <tr><td>当前需求满足水平</td><td>{@link Kind#FOOD}/{@link Kind#CLOTH}：本周期**逐商品**的满足率（粮与布**各一条**）</td></tr>
 *   <tr><td>儿童·青壮年·老年人分别受影响程度</td><td>{@link Kind#FOOD} 的 {@code stressByAgeBracket}：各档批次的**生理压力**最大值/均值</td></tr>
 *   <tr><td>债务增长</td><td>{@link Kind#DEBT}：本期新借入 ÷ 本周期总需求</td></tr>
 *   <tr><td>劳动负担</td><td>{@link Kind#LABOR_BURDEN}：该格劳动占用率（{@code Σ配额 ÷ Σ可用劳动}）</td></tr>
 *   <tr><td>相比历史基线的突变</td><td>★ **如实记：本阶段用的是"满额基线（1000‰）"** ——真正的"与历史基线相比"要一份跨周期的留痕
 *       （第二个状态组件），那超出 R4 的最小范围；本类不假装有它。</td></tr>
 * </table>
 *
 * <p>★★ **两个阈值刻意不同**（{@link #FOOD_CRISIS_PER_MILLE} 与 {@link #CLOTH_CRISIS_PER_MILLE}）：这正是 spec §七
 * "粮食不足与衣物不足对死亡的时间尺度显然不能一样"在读口上的落点 —— 缺粮到九成就要报，缺布要缺到一半才报。
 *
 * <p>★ **判据与阈值是"判断结果"**（spec §十一）⇒ 具名常量、注明量级依据；**V7 参数目录落地后迁入并成为 GM 可调**。
 */
public final class CrisisMonitor {

  /** 危机类别（**是类别，不是概率**）。 */
  public enum Kind {
    /** 生活资料危机：本周期**粮**的满足率低于 {@link #FOOD_CRISIS_PER_MILLE}。 */
    FOOD,
    /** 生活资料危机：本周期**布**的满足率低于 {@link #CLOTH_CRISIS_PER_MILLE}。 */
    CLOTH,
    /** 社会再生产危机：本周期死亡率高于 {@link #MORTALITY_CRISIS_PER_MILLE}。 */
    MORTALITY,
    /** 债务危机：本期新借入达到本周期总需求的 {@link #DEBT_CRISIS_PER_MILLE} 以上。 */
    DEBT,
    /** 劳动负担：该格劳动占用率 ≥ {@link #LABOR_BURDEN_CRISIS_PER_MILLE}。 */
    LABOR_BURDEN
  }

  /** 粮满足率低于它 ⇒ 报 {@link Kind#FOOD}（‰）：**900**（缺一成）。 */
  public static final long FOOD_CRISIS_PER_MILLE = 900L;

  /** 布满足率低于它 ⇒ 报 {@link Kind#CLOTH}（‰）：**500**（缺一半）。★ **与粮那个阈值刻意不同**，见类注。 */
  public static final long CLOTH_CRISIS_PER_MILLE = 500L;

  /** 本周期死亡率高于它 ⇒ 报 {@link Kind#MORTALITY}（‰/周期）：**20**（≈ 人口的 2%/周期）。 */
  public static final long MORTALITY_CRISIS_PER_MILLE = 20L;

  /** 本期新借入 ≥ 本周期总需求的这个千分比 ⇒ 报 {@link Kind#DEBT}（‰）：**250**（借了四分之一的口粮）。 */
  public static final long DEBT_CRISIS_PER_MILLE = 250L;

  /** 劳动占用率 ≥ 它 ⇒ 报 {@link Kind#LABOR_BURDEN}（‰）：**950**（几乎没有余量）。 */
  public static final long LABOR_BURDEN_CRISIS_PER_MILLE = 950L;

  private CrisisMonitor() {}

  /**
   * ★★ **逐格的红灯**（保序：格按 {@code (q,r)}）。**只读**，不产生任何状态改动。
   *
   * @param economy 经济切片（需求、满足、借贷、劳动都在这里）
   * @param social 社会切片（批次：生理压力按年龄档分组读）
   * @param atTick 读的时刻（世界日）：批次落在哪一档由 {@code ageDaysAt(atTick)} 现算（年龄没有档，只有逐日精度）
   */
  public static Map<HexCoord, List<Light>> lights(
      EconomyData economy, SocialData social, long atTick) {
    Map<String, List<ClassKey>> byHex = new LinkedHashMap<>();
    for (ClassKey key : economy.classes().keySet()) {
      String hex = IndustryHexKeys.hexKeyOf(key.industry()).orElse(key.industry().value());
      byHex.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(key);
    }
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(String::compareTo);
    Map<HexCoord, List<Light>> out = new LinkedHashMap<>();
    for (String hexKey : hexKeys) {
      HexCoord coord = hexCoordOf(economy, hexKey);
      if (coord == null) {
        continue;
      }
      List<Light> lights = lightsAt(coord, byHex.get(hexKey), economy, social, atTick);
      if (!lights.isEmpty()) {
        out.put(coord, lights);
      }
    }
    return out;
  }

  /** 单格的红灯（空清单 = 没有红灯）。 */
  public static List<Light> lightsAt(
      HexCoord coord, EconomyData economy, SocialData social, long atTick) {
    List<ClassKey> keys = new ArrayList<>();
    for (ClassKey key : economy.classes().keySet()) {
      boolean here =
          IndustryHexKeys.hexKeyOf(key.industry())
              .filter(hex -> hex.equals(IndustryHexKeys.hexKey(coord.q(), coord.r())))
              .isPresent();
      if (here) {
        keys.add(key);
      }
    }
    return lightsAt(coord, keys, economy, social, atTick);
  }

  /** 从该格的任一产业 id 反解格坐标（{@code <kind>@<q>_<r>}）；无产业 ⇒ null。 */
  private static HexCoord hexCoordOf(EconomyData economy, String hexKey) {
    for (IndustryId id : economy.industries().keySet()) {
      if (IndustryHexKeys.hexKeyOf(id).filter(hexKey::equals).isPresent()) {
        String[] parts = hexKey.split("_");
        if (parts.length == 2) {
          return new HexCoord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
      }
    }
    return null;
  }

  private static List<Light> lightsAt(
      HexCoord coord, List<ClassKey> keys, EconomyData economy, SocialData social, long atTick) {
    long grainNeed = 0L;
    long grainUnmet = 0L;
    long clothNeed = 0L;
    long clothUnmet = 0L;
    long population = 0L;
    long deaths = 0L;
    long borrowing = 0L;
    for (ClassKey key : keys) {
      ClassRow row = economy.classes().get(key);
      FlowRow flow = economy.flows().get(key);
      if (row == null) {
        continue;
      }
      population += row.population();
      long cycleDays =
          economy.industries().containsKey(key.industry())
              ? economy.industries().get(key.industry()).cycleDays()
              : 1L;
      grainNeed += EconomyVocabulary.cumulativeRationMilli(row.population(), cycleDays);
      clothNeed += EconomyVocabulary.cumulativeClothMilli(row.population(), cycleDays);
      if (flow != null) {
        grainUnmet += flow.unmetNeed().getOrDefault(commodityGrain(), 0L);
        clothUnmet += flow.unmetNeed().getOrDefault(commodityCloth(), 0L);
        deaths += flow.deaths();
        borrowing += flow.newBorrowing();
      }
    }
    List<Light> lights = new ArrayList<>();
    long grainSatisfaction = satisfaction(grainNeed, grainUnmet);
    if (grainNeed > 0L && grainSatisfaction < FOOD_CRISIS_PER_MILLE) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("grainSatisfactionPerMille", grainSatisfaction);
      evidence.put("grainUnmetMilli", grainUnmet);
      // ★ "儿童·青壮年·老年人分别受影响程度"：各档批次**生理压力**的最大值（批次身上只有逐日年龄与压力）。
      evidence.put("stressByAgeBracket", stressByAgeBracket(coord, social, atTick));
      lights.add(new Light(coord, Kind.FOOD, evidence));
    }
    long clothSatisfaction = satisfaction(clothNeed, clothUnmet);
    if (clothNeed > 0L && clothSatisfaction < CLOTH_CRISIS_PER_MILLE) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("clothSatisfactionPerMille", clothSatisfaction);
      evidence.put("clothUnmetMilli", clothUnmet);
      lights.add(new Light(coord, Kind.CLOTH, evidence));
    }
    if (population > 0L && deaths * 1000L / population > MORTALITY_CRISIS_PER_MILLE) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("deathsThisCycle", deaths);
      evidence.put("population", population);
      evidence.put("mortalityPerMille", deaths * 1000L / population);
      lights.add(new Light(coord, Kind.MORTALITY, evidence));
    }
    if (grainNeed > 0L && borrowing * 1000L / grainNeed >= DEBT_CRISIS_PER_MILLE) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("newBorrowingMilli", borrowing);
      evidence.put("cycleNeedMilli", grainNeed);
      lights.add(new Light(coord, Kind.DEBT, evidence));
    }
    long available = 0L;
    long allocated = 0L;
    for (PopulationGroup group : social.groupsAt(coord)) {
      var supply = economy.laborSupply().get(group.id());
      if (supply != null) {
        available += supply.availableLabor();
      }
      for (var allocation : economy.allocations().values()) {
        if (allocation.group().equals(group.id())) {
          allocated += allocation.laborMilli();
        }
      }
    }
    if (available > 0L && allocated * 1000L / available >= LABOR_BURDEN_CRISIS_PER_MILLE) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("allocatedMilli", allocated);
      evidence.put("availableMilli", available);
      evidence.put("utilizationPerMille", allocated * 1000L / available);
      lights.add(new Light(coord, Kind.LABOR_BURDEN, evidence));
    }
    return List.copyOf(lights);
  }

  /** 满足率（‰）：{@code 需求 == 0 ⇒ 1000}；否则 {@code (需求 − 缺口) × 1000 ÷ 需求}。 */
  private static long satisfaction(long need, long unmet) {
    if (need <= 0L) {
      return 1000L;
    }
    return Math.max(0L, (need - Math.min(need, unmet)) * 1000L / need);
  }

  /** 该格各年龄档的**最大生理压力**（"儿童·青壮年·老年人分别受影响程度"的唯一现成口径）。 */
  private static Map<String, Long> stressByAgeBracket(
      HexCoord coord, SocialData social, long atTick) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (AgeBracket bracket : AgeBracket.values()) {
      out.put(bracket.key(), 0L);
    }
    for (PopulationGroup group : social.groupsAt(coord)) {
      String key = AgeBracket.of(group.ageDaysAt(atTick)).key();
      out.merge(key, group.physiologicalStress(), Math::max);
    }
    return out;
  }

  private static CommodityId commodityGrain() {
    return new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  }

  private static CommodityId commodityCloth() {
    return new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);
  }

  /**
   * 一盏红灯：**类别** + **当场量到的数字**（依据）。★ **没有概率字段**（见类注）。
   *
   * @param at 哪一格
   * @param kind 危机类别
   * @param evidence 判定它的那些实测数字（键序保序、可复现）
   */
  public record Light(HexCoord at, Kind kind, Map<String, Object> evidence) {

    public Light {
      if (at == null || kind == null) {
        throw new IllegalArgumentException("Light 的格与类别都不得为 null");
      }
      evidence = Map.copyOf(evidence == null ? Map.of() : evidence);
    }
  }
}
