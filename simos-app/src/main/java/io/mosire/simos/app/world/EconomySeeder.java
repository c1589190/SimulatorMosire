package io.mosire.simos.app.world;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ★★ **R2a 生成器**（聚合式经济重设计 §十「验收目标 A」）：把一次世界生成已经算好的 {@link SettlementPlan}（逐格农村人口 + 城市表）翻成**一条**
 * {@code economy.Seed} 命令的载荷。
 *
 * <p>★★ **人口一律取自 {@link SettlementPlan}，一个数都不重新派生**（§十 口径表首行）：逐格农村人口来自 {@code
 * plan.ruralPopulation()}、城市人口来自 {@code plan.cities()}。本类只做"分配 / 换算"，不做人口估计。
 *
 * <p>★★ **两个守恒在出口成立**（用例逐值断言）：
 *
 * <ul>
 *   <li>**人口守恒**：某格的 {@code Σ 阶层行人口 == 该格人口}（= 该格农村人口 + 落在该格的城市人口）。四舍五入的残差按**槽位 id 序** 逐格分派（每个槽位最多补
 *       1 人），故不丢也不多。
 *   <li>**土地守恒**：某格农业各行的 {@code Σ LAND == 该格土地}（{@code muPerHex × 地形系数}，单位千分亩）。土地按各行**人口**
 *       成比例切分；该格农业人口为 0 时整份土地记在第一个槽位（不丢总量、也不做除零）。
 * </ul>
 *
 * <p>★ **参数默认值全部取自 §十 的口径表**（{@code 一切数字来自场景参数}）：它们在此以**具名常量**出现并各自注明来源—— 冻结输入 {@code
 * config/worldgen/v17levant-nations.json} 里没有这些字段，故"场景参数"就是本节钉住的默认值。
 *
 * <p>★ **不做的**（§十"明确不做"）：日结算（消费/进度/产出/分配）属 R3a/R4a ⇒ 本类只种**静态**初值（{@code progressDays=0}、 {@code
 * money=0}、无债务）；{@code dailyInputPerUnit}/{@code dailyLaborPerUnit} 没有依据 ⇒ 0（不臆造）。
 */
public final class EconomySeeder {

  /** 农业产业种类标签（{@link IndustryHexKeys} 的前缀）。 */
  public static final String FARM = "farm";

  /** 手工业产业种类标签（城市格追加，§十）。 */
  public static final String CRAFT = "craft";

  /** 农业制度：封建租佃（§十"制度"行）。 */
  public static final String REGIME_FEUDAL = "feudal";

  /** 手工业制度（§十"制度"行）。 */
  public static final String REGIME_HANDICRAFT = "handicraft";

  /** 农业周期（天）：§十"单位"行"周期 = 120 天"（手工业同取 120：表里只有这一个周期值）。 */
  public static final int CYCLE_DAYS = 120;

  /** 每格土地基准（亩）：§十"土地"行 {@code muPerHex 默认 1000 亩/格}。 */
  public static final long MU_PER_HEX = 1000L;

  /** 千分亩/亩（土地的量纲是千分亩，§7）。 */
  private static final long MILLI_MU_PER_MU = 1000L;

  /** 地形系数：平原 1.0（§十"土地"行）。 */
  public static final int COEF_PLAINS_PER_MILLE = 1000;

  /** 地形系数：低丘 0.6（§十"土地"行）。 */
  public static final int COEF_LOW_HILLS_PER_MILLE = 600;

  /** 农业每亩毛产（粮）：§十"土地"行 / §3.1 的 {@code outputPerUnit}（农业 = 每亩 7 粮）。 */
  public static final long GRAIN_OUTPUT_PER_MU = 7L;

  /**
   * 初始阶层比例（‰）：§十"初始阶层比例"行 贫农 450 / 中农 350 / 富农 150 / 地主 50。
   *
   * <p>★ **包内可见**（不是 {@code public}）：数组是可变对象，公开分享等于对外开一个改参数的后门（SpotBugs MS_PKGPROTECT
   * 实测报过）；它只服务本类与同包用例。
   */
  static final int[] CLASS_SHARE_PER_MILLE = {450, 350, 150, 50};

  /** 阶层槽位 id（与 {@link #CLASS_SHARE_PER_MILLE} 同序；包内可见的理由见上）。 */
  static final String[] CLASS_IDS = {"peasant", "middle", "rich", "landlord"};

  /** 阶层槽位展示名（与 {@link #CLASS_SHARE_PER_MILLE} 同序；包内可见的理由见上）。 */
  static final String[] CLASS_NAMES = {"贫农", "中农", "富农", "地主"};

  /**
   * ★★ **初始粮食储备的按阶层天数表**（版本化参数，2026-09-25 用户点名）：键 = 阶层槽位，值 = **每人几天的口粮**。
   *
   * <p>★★ **为什么要按阶层差异化**：旧口径给全世界每一行都配同一份 60 天口粮 ⇒ 同格里**谁都没有余粮**（人人都恰好吃到自己那份），
   * 于是同格借粮链**空转**、缺粮**没有任何后果**。贫农最薄（30 天）、地主最厚（250 天）后，地主/富农手里天然有可贷的余粮， 贫农先见底 ⇒ 同格借贷与（{@code
   * EconomySettlement} 的）饿死惩罚才有落点。
   *
   * <p>★ 改这张表 = 改初始资源分布 ⇒ 记入 {@link #RULES_VERSION} 的口径（版本化，不写死在公式里）。
   */
  public static final Map<ClassSlotId, Integer> INITIAL_RATION_DAYS_BY_CLASS =
      Map.of(
          new ClassSlotId(CLASS_IDS[0]), 30, // 贫农：最薄（先见底 ⇒ 缺口/借粮/饿死都从它起）
          new ClassSlotId(CLASS_IDS[1]), 60, // 中农：与旧口径同（60 天）
          new ClassSlotId(CLASS_IDS[2]), 120, // 富农：有余粮可贷
          new ClassSlotId(CLASS_IDS[3]), 250); // 地主：最厚（同格主要债权人）

  /** 槽位劳动投入率上限（‰）：{@code ClassSlot} 的既定口径（贫农 950 / 中农 900 / 富农 750 / 地主 100）。 */
  static final int[] CLASS_LABOR_PER_MILLE = {950, 900, 750, 100};

  /** 有效劳动的年龄档（D4 默认，§十"有效劳动"行）：0-14 / 15-59 / 60+ 的人数占比（‰）。 */
  static final int[] AGE_SHARE_PER_MILLE = {350, 550, 100};

  /** 各年龄档的劳动系数（‰，与 {@link #AGE_SHARE_PER_MILLE} 同序）：0 / 1000 / 300。 */
  static final int[] AGE_LABOR_COEF_PER_MILLE = {0, 1000, 300};

  /** 每人每日口粮（毫粮）：唯一拼写点在 {@link EconomyVocabulary}（v2 spec §六）。 */
  public static final long DAILY_GRAIN_MILLI_PER_PERSON =
      EconomyVocabulary.DAILY_GRAIN_MILLI_PER_PERSON;

  /** 粮食商品的 id：唯一拼写点在 {@link EconomyVocabulary}（v2 spec §六）。 */
  public static final String COMMODITY_GRAIN = EconomyVocabulary.GRAIN_COMMODITY_ID;

  /** 规则版本标签（§5 末条"改参数 = 改 rulesVersion"）：写入 {@code EconomyMeta}。 */
  public static final String RULES_VERSION = "aggregate-v1";

  private EconomySeeder() {}

  /** 走真地图：地形 key 由 {@link GameMap#terrainIndex()} 一次物化后 O(1) 查。 */
  public static String payload(String mapId, SettlementPlan plan, GameMap map) {
    Map<HexCoord, String> terrain = map.terrainIndex();
    return payload(
        mapId,
        plan,
        at -> {
          String key = terrain.get(at);
          if (key == null) {
            throw new IllegalStateException("格 " + at + " 不在 terrainIndex 里（地图分割不变式被破坏）");
          }
          return key;
        });
  }

  /**
   * 纯函数主入口（**包内可见**：用例塞一个 {@code hex -> "plains"} 的替身即可，不必造 {@link GameMap}）。
   *
   * @param terrainOf 逐格地形 key（真路径 = {@code map.terrainIndex()}）；未知地形 fail-closed
   */
  static String payload(String mapId, SettlementPlan plan, Function<HexCoord, String> terrainOf) {
    Map<HexCoord, Long> urban = urbanPopulationByHex(plan);
    List<HexCoord> hexes = new ArrayList<>(plan.ruralPopulation().keySet());
    for (HexCoord hex : urban.keySet()) {
      if (!plan.ruralPopulation().containsKey(hex)) {
        hexes.add(hex); // 有城市却无农村人口的格也要有经济状态（否则那座城的人口凭空消失）
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));

    List<Map<String, Object>> entries = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      long rural = plan.ruralPopulation().getOrDefault(hex, 0L);
      long city = urban.getOrDefault(hex, 0L);
      List<Map<String, Object>> industries = new ArrayList<>(2);
      industries.add(agriculture(hex, rural, terrainOf.apply(hex)));
      if (city > 0) {
        industries.add(handicraft(hex, city));
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("q", hex.q());
      entry.put("r", hex.r());
      entry.put("industries", industries);
      entries.add(entry);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mapId", mapId);
    payload.put("rulesVersion", RULES_VERSION);
    payload.put("entries", entries);
    return ToolSupport.json(payload);
  }

  /** 逐格城市人口（同一格多座城 ⇒ 相加；§十"每格人口 = 农村 + 城市"）。 */
  private static Map<HexCoord, Long> urbanPopulationByHex(SettlementPlan plan) {
    Map<HexCoord, Long> urban = new LinkedHashMap<>();
    for (PlannedCity city : plan.cities()) {
      urban.merge(city.at(), city.population(), Long::sum);
    }
    return urban;
  }

  // ── 两个产业 ─────────────────────────────────────────────────────────────────────────

  /** 农业（**恒有**，§十）：人口 = 该格农村人口；土地 = {@code muPerHex × 地形系数}；制度 = 封建租佃。 */
  private static Map<String, Object> agriculture(HexCoord hex, long population, String terrain) {
    long landMilliMu = MU_PER_HEX * MILLI_MU_PER_MU * terrainCoefPerMille(terrain) / 1000L;
    long[] people = splitByShares(population, CLASS_SHARE_PER_MILLE);
    long[] land = splitProportional(landMilliMu, people);
    List<Map<String, Object>> classes = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★ 土地按各行人口成比例切；人口为 0 的槽位据此得 0（唯一的例外见 splitProportional：整格人口为 0 时土地记在第一槽，
      //   保住"Σ土地 == 格土地"这条守恒，不静默丢地）。
      classes.add(
          classRow(CLASS_IDS[i], people[i], CLASS_LABOR_PER_MILLE[i], Map.of("LAND", land[i])));
    }
    return industry(
        IndustryHexKeys.id(FARM, hex.q(), hex.r()).value(),
        "农业",
        REGIME_FEUDAL,
        classes,
        Map.of("meansWeightPerMille", 700, "laborWeightPerMille", 300));
  }

  /** 手工业（**城市格追加**，§十）：人口 = 该格城市人口；不占地（土地全归农业）；制度 = 手工业。 */
  private static Map<String, Object> handicraft(HexCoord hex, long population) {
    long[] people = splitByShares(population, CLASS_SHARE_PER_MILLE);
    List<Map<String, Object>> classes = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      classes.add(classRow(CLASS_IDS[i], people[i], CLASS_LABOR_PER_MILLE[i], Map.of()));
    }
    return industry(
        IndustryHexKeys.id(CRAFT, hex.q(), hex.r()).value(),
        "手工业",
        REGIME_HANDICRAFT,
        classes,
        Map.of("meansWeightPerMille", 400, "laborWeightPerMille", 600));
  }

  /**
   * 一个产业对象（与 §3.1 {@code Industry} 逐字段对应）。
   *
   * <p>{@code dailyInputPerUnit}/{@code dailyLaborPerUnit} 置 0：§十 没给这两项的依据（那是 R3a 的事），**不臆造**；
   * {@code progressDays} = 0（周期刚起）；{@code cycleDays} = {@link #CYCLE_DAYS}。
   */
  private static Map<String, Object> industry(
      String id,
      String name,
      String regime,
      List<Map<String, Object>> classes,
      Map<String, Object> split) {
    List<Map<String, Object>> slots = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      Map<String, Object> slot = new LinkedHashMap<>();
      slot.put("id", CLASS_IDS[i]);
      slot.put("name", CLASS_NAMES[i]);
      slot.put("laborParticipationPerMille", CLASS_LABOR_PER_MILLE[i]);
      slots.add(slot);
    }
    Map<String, Object> allocation = new LinkedHashMap<>(split);
    allocation.put("@class", "split");
    Map<String, Object> industry = new LinkedHashMap<>();
    industry.put("id", id);
    industry.put("name", name);
    industry.put("regime", regime);
    industry.put("cycleDays", CYCLE_DAYS);
    industry.put("progressDays", 0);
    industry.put("dailyInputPerUnit", Map.of());
    industry.put("dailyLaborPerUnit", 0);
    industry.put("outputPerUnit", Map.of(COMMODITY_GRAIN, GRAIN_OUTPUT_PER_MU));
    // ★ R3a：周期累计实际劳动——创世 = 0（新周期尚未投入；日结算每天累加）。
    industry.put("cycleLaborMilli", 0);
    industry.put("allocation", allocation);
    industry.put("slots", slots);
    industry.put("classes", classes);
    return industry;
  }

  /**
   * 一个阶层行（与 §3.2 {@code ClassRow} 逐字段对应）：有效劳动 = 人口 × 年龄系数（§十"D4 默认"）、自然需求 = 人口 × 83 毫粮
   * （§十"消费"）、初始库存 = {@code 人口 × 83 毫粮 × 该阶层天数}（{@link #INITIAL_RATION_DAYS_BY_CLASS}，**按阶层差异化**）、
   * 货币 0、无债务、无有效需求（§十 没有它们的依据 ⇒ 不臆造）。
   */
  private static Map<String, Object> classRow(
      String slot, long population, int participationPerMille, Map<String, Object> means) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("slot", slot);
    row.put("population", population);
    row.put("laborMilli", laborMilli(population));
    row.put("participationPerMille", participationPerMille);
    row.put("meansOfProduction", means);
    row.put("goods", Map.of(COMMODITY_GRAIN, rationMilli(population, slot)));
    row.put("money", 0);
    row.put("debts", List.of());
    row.put("naturalNeeds", Map.of(COMMODITY_GRAIN, dailyGrainMilli(population)));
    row.put("effectiveDemand", Map.of());
    return row;
  }

  // ── 口径换算（纯函数，可单测）─────────────────────────────────────────────────────────

  /** 地形系数（‰）：平原 1.0 / 低丘 0.6（§十"土地"行）；其余地形 fail-closed（这三个国家的有产格只有这两种）。 */
  static int terrainCoefPerMille(String terrainKey) {
    return switch (terrainKey) {
      case "plains" -> COEF_PLAINS_PER_MILLE;
      case "low_hills" -> COEF_LOW_HILLS_PER_MILLE;
      default ->
          throw new IllegalArgumentException("地形 " + terrainKey + " 没有土地系数依据（§十 只给了 平原/低丘）⇒ 拒绝臆造");
    };
  }

  /**
   * 有效劳动（千分劳动）：人口 × Σ(年龄档占比 × 档内劳动系数)（§十"D4 默认"）。
   *
   * <p>量纲：一个人满劳动 = 1000 千分劳动 ⇒ 每人折算 580 千分劳动（= 550×1000‰ + 100×300‰）。**整数**运算，不丢精度。
   */
  public static long laborMilli(long population) {
    long perCapitaPerMille = 0L;
    for (int i = 0; i < AGE_SHARE_PER_MILLE.length; i++) {
      perCapitaPerMille += (long) AGE_SHARE_PER_MILLE[i] * AGE_LABOR_COEF_PER_MILLE[i] / 1000L;
    }
    return population * perCapitaPerMille;
  }

  /** 每人每日口粮（毫粮）：§十"消费"行。 */
  public static long dailyGrainMilli(long population) {
    return population * DAILY_GRAIN_MILLI_PER_PERSON;
  }

  /** 某阶层"每人几天的口粮"（版本化参数表 {@link #INITIAL_RATION_DAYS_BY_CLASS}）；查不到 ⇒ fail-closed（拒绝臆造）。 */
  public static int initialRationDays(String slot) {
    Integer days = INITIAL_RATION_DAYS_BY_CLASS.get(new ClassSlotId(slot));
    if (days == null) {
      throw new IllegalArgumentException("阶层槽位 " + slot + " 不在初始口粮天数表里（拒绝臆造）");
    }
    return days;
  }

  /** 初始库存（毫粮）：{@code 人口 × 83 毫粮 × 该阶层天数}（{@link #INITIAL_RATION_DAYS_BY_CLASS}）。 */
  public static long rationMilli(long population, String slot) {
    return dailyGrainMilli(population) * initialRationDays(slot);
  }

  /**
   * 把 {@code total} 按 **千分比例表** 切成同长子表（§十 的"初始阶层比例"）。
   *
   * <p>★★ **分母恒为 1000‰，绝不用 Σ比例 归一化**：{@code 450/350/150/50} 之和恰为 1000，故正常情形下 {@code Σ 结果 ==
   * total}（余数 ∈ [0, n-1]，按**槽位 id 序**逐个 +1 分派）。而**比例表被人改坏**（例如地主 50 → 100 而没重分）时 分母仍是 1000 ⇒ {@code
   * Σ 结果 ≠ total} —— 静默归一化会把这个错误**伪装成"一切正常"**（本仓最忌的那一族）， "Σ 行人口 == 格人口"那条守恒断言因此有判别力。
   */
  public static long[] splitByShares(long total, int[] sharesPerMille) {
    if (total < 0) {
      throw new IllegalArgumentException("splitByShares 的 total 不得为负: " + total);
    }
    long[] out = new long[sharesPerMille.length];
    long assigned = 0L;
    for (int i = 0; i < sharesPerMille.length; i++) {
      if (sharesPerMille[i] < 0) {
        throw new IllegalArgumentException("splitByShares 的比例不得为负: " + sharesPerMille[i]);
      }
      out[i] = total * sharesPerMille[i] / 1000L;
      assigned += out[i];
    }
    long remainder = total - assigned;
    for (int i = 0; i < out.length && remainder > 0; i++, remainder--) {
      out[i]++;
    }
    return out;
  }

  /**
   * 把 {@code total} 按**任意非负权重**成比例切成同长子表，**Σ 结果恰为 {@code total}**（余数按**下标序**逐个 +1）。
   *
   * <p>★ 与 {@link #splitByShares} 的分工：这里的分母是**权重之和**（权重不是千分数，如"按各行人口分土地"）； 权重全为 0（或 {@code total ==
   * 0}）时整份记在第一项（农业人口为 0 的格，土地不丢也不做除零）。
   */
  public static long[] splitProportional(long total, long[] weights) {
    if (total < 0) {
      throw new IllegalArgumentException("splitProportional 的 total 不得为负: " + total);
    }
    long[] out = new long[weights.length];
    if (weights.length == 0) {
      if (total != 0) {
        throw new IllegalArgumentException("splitProportional 没有可承载的槽位，但 total = " + total);
      }
      return out;
    }
    long weightSum = 0L;
    for (long weight : weights) {
      if (weight < 0) {
        throw new IllegalArgumentException("splitProportional 的权重不得为负: " + weight);
      }
      weightSum += weight;
    }
    if (weightSum == 0L || total == 0L) {
      out[0] = total;
      return out;
    }
    long assigned = 0L;
    for (int i = 0; i < weights.length; i++) {
      out[i] = total * weights[i] / weightSum;
      assigned += out[i];
    }
    long remainder = total - assigned;
    for (int i = 0; i < out.length && remainder > 0; i++, remainder--) {
      out[i]++;
    }
    return out;
  }
}
