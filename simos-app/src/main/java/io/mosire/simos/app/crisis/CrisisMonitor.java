package io.mosire.simos.app.crisis;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
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
 *   <tr><td>债务增长</td><td>{@link Kind#DEBT}：本期新借入 ÷ 本周期**至今**需求（与分子同基准）</td></tr>
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
    /** 债务危机：本期新借入达到本周期**至今**需求的 {@link #DEBT_CRISIS_PER_MILLE} 以上（与分子同基准）。 */
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
    // ★★ H0.2：**格直接住在行键里**（{@code CohortKey.hex()}）—— 旧版要靠"行属于哪个产业、产业 id 里带哪一格"
    //   反解两次，现在一次都不必（"行在哪一格"与"产业在哪一格"从此是两件事，各读各的）。
    //   ★ 分组键仍是 {@code <q>_<r>} 字符串（{@link IndustryHexKeys#hexKey}），排序口径与旧版逐字相同（字典序）。
    Map<String, List<HouseholdId>> byHex = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : economy.classes().entrySet()) {
      HexCoord hex = entry.getValue().view().hex();
      String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
      byHex.computeIfAbsent(hexKey, ignored -> new ArrayList<>()).add(entry.getKey());
    }
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(String::compareTo);
    Map<HexCoord, List<Light>> out = new LinkedHashMap<>();
    for (String hexKey : hexKeys) {
      HexCoord coord = HexCoord.parse(hexKey);
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
    List<HouseholdId> keys = new ArrayList<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : economy.classes().entrySet()) {
      if (entry.getValue().view().hex().equals(coord)) {
        keys.add(entry.getKey());
      }
    }
    return lightsAt(coord, keys, economy, social, atTick);
  }

  private static List<Light> lightsAt(
      HexCoord coord, List<HouseholdId> keys, EconomyData economy, SocialData social, long atTick) {
    long grainNeed = 0L;
    long grainUnmet = 0L;
    long clothNeed = 0L;
    long clothUnmet = 0L;
    long population = 0L;
    long deaths = 0L;
    long borrowing = 0L;
    long elapsedDaysSeen = 0L;
    long cycleDaysSeen = 0L;
    for (HouseholdId key : keys) {
      ClassRow row = economy.classes().get(key);
      FlowRow flow = economy.flows().get(key);
      if (row == null) {
        continue;
      }
      population += row.population();
      // ★★ B2 修复：**分子与分母必须同基准**。
      //   分子是"本周期**至今**累计的 unmetNeed"（FlowRow 在新周期第一天归零、此后逐日累加），
      //   故分母必须是"本周期**至今**的需求"，而不是整周期的需求 —— 否则周期初分子只累计了几天、
      //   分母却已按 120 天算 ⇒ 满足率被严重高估 ⇒ **一场持续危机在周期切换后会暂时读成"没有危机"**。
      //   `cumulativeRationMilli` 的类注本就写明它是"**天的函数**（不是'周期内第几天'的函数）：
      //   调用方传**绝对天数/绝对日号**" —— 传 cycleDays 这个常量正是误用。
      long elapsedDays = elapsedDaysOf(economy, key);
      elapsedDaysSeen = Math.max(elapsedDaysSeen, elapsedDays);
      cycleDaysSeen = Math.max(cycleDaysSeen, cycleDaysOf(economy, key));
      grainNeed += EconomyVocabulary.cumulativeRationMilli(row.population(), elapsedDays);
      clothNeed += EconomyVocabulary.cumulativeClothMilli(row.population(), elapsedDays);
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
      // ★ 相位：让读的人知道这是**部分周期**的读数（elapsedDays ≤ cycleDays）。
      evidence.put("elapsedDays", elapsedDaysSeen);
      evidence.put("cycleDays", cycleDaysSeen);
      // ★ "儿童·青壮年·老年人分别受影响程度"：各档批次**生理压力**的最大值（批次身上只有逐日年龄与压力）。
      evidence.put("stressByAgeBracket", stressByAgeBracket(coord, social, atTick));
      lights.add(new Light(coord, Kind.FOOD, evidence));
    }
    long clothSatisfaction = satisfaction(clothNeed, clothUnmet);
    if (clothNeed > 0L && clothSatisfaction < CLOTH_CRISIS_PER_MILLE) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("clothSatisfactionPerMille", clothSatisfaction);
      evidence.put("clothUnmetMilli", clothUnmet);
      // ★ 相位：同 FOOD。
      evidence.put("elapsedDays", elapsedDaysSeen);
      evidence.put("cycleDays", cycleDaysSeen);
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
      // ★ 键名与语义同时更正：B2 之后这个分母是"本周期**至今**"的需求，不再是一整个周期 ——
      //   沿用旧名 `cycleNeedMilli` 会让读的人以为它是整周期需求。
      evidence.put("elapsedNeedMilli", grainNeed);
      evidence.put("elapsedDays", elapsedDaysSeen);
      evidence.put("cycleDays", cycleDaysSeen);
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

  /**
   * ★★ **该行所属产业在本周期"已过多少天"**（{@code Industry.progressDays}）—— B2 的分母基准。
   *
   * <p>★ **周期第 0 天按 1 天算**：既不除零，也不把"周期刚开始"读成"完全满足"。 不设产业（手工搭的状态）⇒ 同样返回 1。
   */
  private static long elapsedDaysOf(EconomyData economy, HouseholdId key) {
    long elapsed = 0L;
    HexCoord hex = economy.classes().get(key).view().hex();
    // ★★ R3B.2：相位住在 unit 上（同一格可能有多个 unit）；产业模板只回答 cycleDays。
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    for (ProductionUnit unit : economy.units().values()) {
      if (IndustryHexKeys.hexKeyOf(unit.industry()).filter(hexKey::equals).isEmpty()) {
        continue;
      }
      elapsed = Math.max(elapsed, phaseDaysOf(unit, economy.industries().get(unit.industry())));
    }
    // ★ 该格没有任何 unit（手工搭的状态）⇒ 按旧口径返回 1（既不除零，也不把"周期刚开始"读成"完全满足"）。
    return elapsed == 0L ? 1L : elapsed;
  }

  /** 该家户所属格的**整周期**天数（只用于 evidence 里标出相位）；该格没有产业 ⇒ 1。 */
  private static long cycleDaysOf(EconomyData economy, HouseholdId key) {
    long days = 0L;
    HexCoord hex = economy.classes().get(key).view().hex();
    for (IndustryId id : IndustryHexKeys.at(economy.industries(), hex.q(), hex.r())) {
      days = Math.max(days, economy.industries().get(id).cycleDays());
    }
    return days == 0L ? 1L : days;
  }

  /**
   * ★★ **一个产业的相位天数**（B2 的分母基准；{@link #elapsedDaysOf} 与 {@link #cycleDaysOf} 共用）。
   *
   * <p>★ **关账那一支必须取整周期**：`progressDays == 0` 有**两种**状态，只看它分不开 —— ① 创世（tick 0）：流水全 0 ⇒ 满足率恒
   * 1000‰，分母取哪个都一样； ② **关账那一天的 revision**：`progressDays` 已被收获那一支归零，而 FlowRow 的清零在**次日** ⇒ 此刻
   * `unmetNeed` 携带的是**刚关账那一整个周期**的量 ⇒ 分母必须是 cycleDays。 取 1 天会让"整周期缺口 ÷ 1 天需求"算出 0‰ 的满足率 ⇒
   * **全境假阳性红灯**。 两个既有用例钉着这件事：EconomyFlowCycleTest.theClosingDayCarriesTheWholeCyclesIncome 与
   * theFirstDayOfANewCycleStartsEveryFieldFromZero。
   *
   * <p>★ 一个家户的相位取自**它那一格的产业**（H0.2：行键里没有产业）：同一格的产业由同一条日推进同步走 ⇒ 取其中最大的那个与旧口径（逐行各取自己产业的相位、再取 max）同值。
   */
  private static long phaseDaysOf(ProductionUnit unit, Industry industry) {
    if (unit == null || industry == null) {
      return 1L;
    }
    return unit.progressDays() == 0L ? industry.cycleDays() : unit.progressDays();
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
