package io.mosire.simos.app.world;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
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
 * <p>★★ **R1（T4）起人口一律取自 {@link PopulationGroup} 批次列表**（{@link PopulationSeeder#groups} 造的那一份，
 * 同一份也喂给 {@code social.SeedGroups}）：逐格人数 = {@code Σ count}，农村/城镇两池由批次 id 的前缀分 （{@link
 * PopulationLots#isUrban}）。**不再读 {@link io.mosire.simos.social.gen.SettlementPlan}** —— 两条命令读同一份列表，
 * "Σ group == 经济侧总人口"因此是构造性成立的。本类只做"分配 / 换算"，不做人口估计。
 *
 * <p>★★ **性别进入劳动折算**（T4 的判据）：每人的千分劳动按 {@code 批次.sex() × 年龄档} 取系数 （{@link
 * #AGE_LABOR_COEF_BY_SEX}，默认两性同表、可按性别覆盖）。人口按"性别 × 年龄"分组这件事由批次承载 （设计稿 §十.6）。
 *
 * <p>★★ **两个守恒在出口成立**（用例逐值断言）：
 *
 * <ul>
 *   <li>**人口守恒**：某格的 {@code Σ 阶层行人口 == 该格人口}（= 该格农村人口 + 落在该格的城市人口）。四舍五入的残差按**最大余数法**
 *       逐格分派（余数大者先得、同余数按下标序，每个槽位最多补 1 人），故不丢也不多。
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

  /**
   * 每格土地基准（亩）：**量纲标定值**（v2 spec §10.3 定案 A，由 1,000 改来）。
   *
   * <p>★ 依据（★ V5 后的账：口粮口径改为"每人每 120 天 10 粮"的累计差分，残差不丢）：真档每格 14,806 人 × 10,000 毫粮/周期 = 148,060
   * 粮/格/周期需粮；毛产 3,100 亩 × 67 粮/亩 = 207,700，扣折旧 3% ⇒ 净 201,469，再减**播种日**扣的种子 24,800 ⇒ **可用 176,669**
   * ⇒ 自给率 **119.3%**（旧的"每人每日 83"口径需粮 147,468 ⇒ 自给率 119.8%：那 0.4% 的差正是被丢掉的残差）。 ★
   * 格面积是**纯经济假设**（{@code HexCell} 只存 height），与地图无关。
   */
  public static final long MU_PER_HEX = 3_100L;

  /** 千分亩/亩（土地的量纲是千分亩，§7）。 */
  private static final long MILLI_MU_PER_MU = 1000L;

  /**
   * 满可耕地的产能档（= {@code TerrainCatalog} 里平原的 {@code food}）：{@link #arablePerMilleOf} 的分母。
   *
   * <p>★ 有一条用例把它钉到 map（`fullArableFoodMatchesTheCatalogPlain`）：map 改了平原产能，这里就要红。
   */
  private static final int FOOD_AT_FULL_ARABLE = 3;

  /**
   * 农业每亩毛产（粮）：**量纲标定值**（v2 spec §10.3 定案 A，由 7 改来）。
   *
   * <p>67 粮/亩 = 134 斤/亩，是**前现代北方旱地小麦的量级**；v1 的 7（= 14 斤/亩）低约 10 倍。
   */
  public static final long GRAIN_OUTPUT_PER_MU = 67L;

  /**
   * ★★ **每亩需种**（**毫粮/亩** = 8 粮/亩）：真档播种器写进 {@code cycleInputPerUnit[LAND]} 的值（v2 spec §3.3； 用户
   * 2026-09-25 裁定「现定」，计划 2 的「修订与新增」）。
   *
   * <p>★ **依据**：前现代留种率约 **1:6 ~ 1:11**（收获 : 留种），取中。与标定值对照：满种 = 3,100 亩 × 8 粮/亩 = **24,800
   * 粮/格/周期**，对毛产 3,100 × 67 = **207,700 粮/格/周期** 之比 ≈ **1:8.4**，落在该区间内。
   *
   * <p>★★ **口径（别混量纲）**：这是**毫粮/亩**（与 {@code outputPerUnit} 的「粮/亩」、结算里按**亩**算的口径同侧）； {@code
   * ClassRow.meansOfProduction} 的 {@code LAND} 是**千分亩**——播种步先 {@code / 1000} 换成亩再乘（差 1000 倍）。
   *
   * <p>★ 它让真档**第一次真的读到第三路瓶颈**（v2 spec §三 的 `seedCapMu`）：此前 {@code cycleInputPerUnit} 是空 map ⇒
   * 播种步一字不扣 ⇒ 真档行为与 V2 逐值一致，但三路瓶颈在 799 格真实世界里**看不见**。
   */
  public static final long SEED_MILLI_PER_MU = 8_000L;

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

  /**
   * 有效劳动的年龄档（D4 默认，§十"有效劳动"行）：0-14 / 15-59 / 60+ 的人数占比（‰）。
   *
   * <p>★ **R1 起它是"创世输入"、不再是结算口径**（设计稿 §十.2 的裁定：D4 三档降为生成期 preset）：它决定 {@link PopulationSeeder}
   * **造出什么样的批次**（每格每性别按这三档分三批），而运行时只有逐日精度的年龄。
   */
  static final int[] AGE_SHARE_PER_MILLE = {350, 550, 100};

  /** 各年龄档的劳动系数（‰，与 {@link #AGE_SHARE_PER_MILLE} 同序）：0 / 1000 / 300。 */
  static final int[] AGE_LABOR_COEF_PER_MILLE = {0, 1000, 300};

  /**
   * ★★ **年龄档 × 性别的劳动系数表**（‰）：外层键 = 性别，值 = 与 {@link #AGE_SHARE_PER_MILLE} 同序的档内系数。
   *
   * <p>★ **默认两性同表**（本轮判据只是"性别**进入了**折算"，不是"男女系数不同"——"男耕女织"的具体数值属 R2，
   * 到那时才按观察调这张表）。表**可按性别覆盖**正是那个旋钮：`payload(…, 表)` 的包内可见重载收它，单测据此证明 "性别真的参与折算"（把女性系数改成 0 ⇒ 劳动逐值减半）。
   */
  static final Map<Sex, int[]> AGE_LABOR_COEF_BY_SEX =
      Map.of(Sex.MALE, AGE_LABOR_COEF_PER_MILLE, Sex.FEMALE, AGE_LABOR_COEF_PER_MILLE);

  /**
   * 年龄档的**上界**（天，不含；与 {@link #AGE_SHARE_PER_MILLE} 的 {0-14, 15-59, 60+} 同口径）：15 岁、60 岁。
   *
   * <p>★ 批次带的是**逐日精度的年龄**，具体落在哪一档由 {@link #ageBracketOf(long)} 现算（不存档位、不许出现"档间转移"）。 ★ 它与 {@link
   * PopulationSeeder#AGE_REPRESENTATIVE_DAYS} 必须互相自洽（代表性年龄要落在自己那一档里）， 由 {@code
   * PopulationSeederTest} 的跨表用例钉住。
   *
   * <p>★★ **R1.5 起本表只是 {@link AgeBracket} 的投影**（原来那两个 {@code 15L * 365L} / {@code 60L * 365L}
   * 的字面量已搬到 social 的 {@link AgeBracket#boundedMaxExclusiveDays()} 一处）：读口（GUI/MCP）也要按 同一套边算年龄结构，而
   * social 看不见本模块 ⇒ 边界的唯一定义处只能是 social；否则"批次按一套边造、劳动按另一套边折算、读口按第三套边显示"
   * 会被三张表悄悄漂开（本仓最忌"注释声称一致、其实不一致"）。
   */
  static final long[] AGE_BRACKET_MAX_EXCLUSIVE_DAYS = AgeBracket.boundedMaxExclusiveDays();

  /** 粮食商品的 id：唯一拼写点在 {@link EconomyVocabulary}（v2 spec §六）。 */
  public static final String COMMODITY_GRAIN = EconomyVocabulary.GRAIN_COMMODITY_ID;

  /** 规则版本标签（§5 末条"改参数 = 改 rulesVersion"）：写入 {@code EconomyMeta}。 */
  public static final String RULES_VERSION = "aggregate-v1";

  private EconomySeeder() {}

  /**
   * ★★ **R1 的人口来源**（T4）：人口**不再从 {@link SettlementPlan} 抄**，而是从**同一份** {@link PopulationGroup}
   * 列表按格聚合 —— 那份列表同时喂给 {@code social.SeedGroups} （{@link PopulationSeeder#payload}）。于是"**Σ group ==
   * 经济侧总人口**"是**构造性成立**的： 两侧读的是同一份列表，不需要运行期读 social 切片（那要跨切片协调器，属后续轮次）。
   *
   * <p>★ 走真地图：地形 key 由 {@link GameMap#terrainIndex()} 一次物化后 O(1) 查。
   */
  public static String payload(String mapId, List<PopulationGroup> groups, GameMap map) {
    Map<HexCoord, String> terrain = map.terrainIndex();
    return payload(
        mapId,
        groups,
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
   * <p>★ **两池都来自批次**（不是两处各抄一份）：农村池 = {@code rural:} 前缀的批次、城镇池 = {@code urban:} 前缀的批次 （{@link
   * PopulationLots#isUrban}）。格集 = **批次的落点集合** —— 于是"经济侧该格有没有人口"也只有一处真相。
   *
   * @param terrainOf 逐格地形 key（真路径 = {@code map.terrainIndex()}）；未知地形 fail-closed
   */
  static String payload(
      String mapId, List<PopulationGroup> groups, Function<HexCoord, String> terrainOf) {
    Map<HexCoord, List<PopulationGroup>> ruralByHex = new LinkedHashMap<>();
    Map<HexCoord, List<PopulationGroup>> urbanByHex = new LinkedHashMap<>();
    for (PopulationGroup group : groups) {
      Map<HexCoord, List<PopulationGroup>> target =
          PopulationLots.isUrban(group) ? urbanByHex : ruralByHex;
      target.computeIfAbsent(group.residence(), hex -> new ArrayList<>()).add(group);
    }
    List<HexCoord> hexes = new ArrayList<>(ruralByHex.keySet());
    for (HexCoord hex : urbanByHex.keySet()) {
      if (!ruralByHex.containsKey(hex)) {
        hexes.add(hex); // 有城市却无农村人口的格也要有经济状态（否则那座城的人口凭空消失）
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));

    List<Map<String, Object>> entries = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      List<PopulationGroup> ruralPool = ruralByHex.getOrDefault(hex, List.of());
      List<PopulationGroup> urbanPool = urbanByHex.getOrDefault(hex, List.of());
      List<Map<String, Object>> industries = new ArrayList<>(2);
      industries.add(agriculture(hex, ruralPool, terrainOf.apply(hex)));
      if (populationOf(urbanPool) > 0L) {
        industries.add(handicraft(hex, urbanPool));
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

  /** 一群批次的人数：{@code Σ count}（"每格人数"的唯一算法）。 */
  static long populationOf(List<PopulationGroup> pool) {
    long total = 0L;
    for (PopulationGroup group : pool) {
      total += group.count();
    }
    return total;
  }

  // ── 两个产业 ─────────────────────────────────────────────────────────────────────────

  /** 农业（**恒有**，§十）：人口 = 该格**农村批次**之和；土地 = {@code muPerHex × 地形系数}；制度 = 封建租佃。 */
  private static Map<String, Object> agriculture(
      HexCoord hex, List<PopulationGroup> pool, String terrain) {
    long landMilliMu = MU_PER_HEX * MILLI_MU_PER_MU * arablePerMilleOf(foodOf(terrain)) / 1000L;
    long[] people = splitByShares(populationOf(pool), CLASS_SHARE_PER_MILLE);
    long[] land = splitProportional(landMilliMu, people);
    long poolLabor = laborMilli(pool);
    long poolCount = populationOf(pool);
    List<Map<String, Object>> classes = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★ 土地按各行人口成比例切；人口为 0 的槽位据此得 0（唯一的例外见 splitProportional：整格人口为 0 时土地记在第一槽，
      //   保住"Σ土地 == 格土地"这条守恒，不静默丢地）。
      classes.add(
          classRow(
              CLASS_IDS[i],
              people[i],
              CLASS_LABOR_PER_MILLE[i],
              Map.of("LAND", land[i]),
              poolLabor,
              poolCount));
    }
    return industry(
        IndustryHexKeys.id(FARM, hex.q(), hex.r()).value(),
        "农业",
        REGIME_FEUDAL,
        classes,
        Map.of("meansWeightPerMille", 700, "laborWeightPerMille", 300));
  }

  /** 手工业（**城市格追加**，§十）：人口 = 该格**城镇批次**之和；不占地（土地全归农业）；制度 = 手工业。 */
  private static Map<String, Object> handicraft(HexCoord hex, List<PopulationGroup> pool) {
    long[] people = splitByShares(populationOf(pool), CLASS_SHARE_PER_MILLE);
    long poolLabor = laborMilli(pool);
    long poolCount = populationOf(pool);
    List<Map<String, Object>> classes = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      classes.add(
          classRow(
              CLASS_IDS[i], people[i], CLASS_LABOR_PER_MILLE[i], Map.of(), poolLabor, poolCount));
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
    // ★★ 一次性投入（v2 spec §3.3：**播种日**现扣的种子）：每亩 8 粮（毫粮/亩，见 {@link #SEED_MILLI_PER_MU}）。
    //   量纲：键值是「毫粮/亩」，与 meansOfProduction 的「千分亩」差 1000 倍 —— 播种步先 /1000 换成亩再乘。
    //   ★ 六种 AssetKind 都收（协议不设限），v1 只读 LAND（其余五种"声明但不启用"）。
    //   ★ V7 参数目录（spec §四.1 把它归**制度层**）落地后：本行改读参数（作用域 全局→国家→格/产业）。
    industry.put("cycleInputPerUnit", Map.of("LAND", SEED_MILLI_PER_MU));
    // ★ R3a：周期累计实际劳动——创世 = 0（新周期尚未投入；日结算每天累加）。
    industry.put("cycleLaborMilli", 0);
    // ★ V3：本周期实际扣到的种子（毫粮）——创世 = 0（与 cycleLaborMilli 同形制；播种日逐行累加、关账清零）。
    industry.put("cycleSeedUsedMilli", 0);
    industry.put("allocation", allocation);
    industry.put("slots", slots);
    industry.put("classes", classes);
    return industry;
  }

  /**
   * 一个阶层行（与 §3.2 {@code ClassRow} 逐字段对应）：有效劳动 = 本行人口 × **该池的人均劳动** （= 池内 Σ(count × 年龄档 × 性别的每人系数) ÷
   * 池人口，见 {@link #laborMilli(List)}）、自然需求 = **第 1 天**的口粮 （{@link
   * #firstDayRationMilli}；结算每天会覆写它，§八.8）、初始库存 = {@code 人口 × 该阶层天数} 天口粮（{@link #rationMilli} 经
   * {@link EconomyVocabulary#cumulativeRationMilli}，**按阶层差异化**）、货币 0、无债务、无有效需求（§十 没有它们的依据 ⇒ 不臆造）。
   *
   * <p>★★ **为什么"人均"而不是"逐行按自己的年龄构成"**：阶层比例把池子切成四份，而**没有任何数据**说清各阶层的年龄/性别构成 ⇒
   * 取池内人均（"阶层之间年龄性别同分布"这条**明说的**假设），不假装知道更多。默认系数下它与 R1 之前 `人口 × 580‰` **逐值相同**（池的年龄构成恰是 D4 preset
   * 时人均恒为 580），故真档的既有期望值不动。
   */
  private static Map<String, Object> classRow(
      String slot,
      long population,
      int participationPerMille,
      Map<String, Object> means,
      long poolLaborMilli,
      long poolCount) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("slot", slot);
    row.put("population", population);
    row.put("laborMilli", rowLaborMilli(population, poolLaborMilli, poolCount));
    row.put("participationPerMille", participationPerMille);
    row.put("meansOfProduction", means);
    row.put("goods", Map.of(COMMODITY_GRAIN, rationMilli(population, slot)));
    row.put("money", 0);
    row.put("debts", List.of());
    row.put("naturalNeeds", Map.of(COMMODITY_GRAIN, firstDayRationMilli(population)));
    row.put("effectiveDemand", Map.of());
    return row;
  }

  // ── 口径换算（纯函数，可单测）─────────────────────────────────────────────────────────

  /**
   * 每格可耕地系数（千分）：由 **map 的** {@link TerrainType#food()} 折算，**不自建地形表**。
   *
   * <p>★ 为什么不自建：v1 在 economy 侧另写了一份**两档**表（平原 1.0 / 低丘 0.6），而 map 的 {@code TerrainType} 早就有**六档**
   * {@code food}（平原 3 / 低丘 2 / 平缓高原 1 / 沙漠 0 / 山地 0 / 海洋 0）—— 同一个事实两处， 且 map 那份更细。真相只能有一个拼写点。
   *
   * <p>★ **为什么不抛**：{@code food == 0} 是**合法产能**（沙漠/山地/海洋），得 0 亩即可；抛会把"这格不产粮" 误报成"数据坏了"。（v1
   * 对非平原/低丘一律抛 ⇒ 地形直方图一变就整批 worldgen 回滚。）
   */
  public static int arablePerMilleOf(int food) {
    return food * 1000 / FOOD_AT_FULL_ARABLE;
  }

  /** 地形 key → map 的产能档；未知地形由 {@link TerrainCatalog#of} fail-closed 抛（不许当 0）。 */
  public static int foodOf(String terrainKey) {
    return TerrainCatalog.of(terrainKey).food();
  }

  /**
   * 年龄（天）落在哪一档（0-14 / 15-59 / 60+，与 {@link #AGE_SHARE_PER_MILLE} 同序）。
   *
   * <p>★ **档是现算的，不存档位**（设计稿 §三：年龄运行时只有逐日精度，"档间转移"因此不存在）。 ★ 超出末档上界一律归末档（年龄没有上界）。
   */
  static int ageBracketOf(long ageDays) {
    if (ageDays < 0L) {
      throw new IllegalArgumentException("ageDays 不得为负: " + ageDays);
    }
    for (int bracket = 0; bracket < AGE_BRACKET_MAX_EXCLUSIVE_DAYS.length; bracket++) {
      if (ageDays < AGE_BRACKET_MAX_EXCLUSIVE_DAYS[bracket]) {
        return bracket;
      }
    }
    return AGE_BRACKET_MAX_EXCLUSIVE_DAYS.length;
  }

  /**
   * **一个批次的每人千分劳动**：它的性别与年龄档 → 系数（默认表；★ 表可按性别覆盖，见 {@link #AGE_LABOR_COEF_BY_SEX}）。
   *
   * <p>★ 量纲：一个人满劳动 = 1000 千分劳动 ⇒ 青壮 1000、老年 300、未成年 0（D4 preset）。
   */
  static long perCapitaLaborPerMille(PopulationGroup group) {
    return perCapitaLaborPerMille(group, AGE_LABOR_COEF_BY_SEX);
  }

  /** 同上的**可注入表**重载（包内可见的旋钮：单测据此证明"性别真的进了折算"，见类注）。 */
  static long perCapitaLaborPerMille(PopulationGroup group, Map<Sex, int[]> coefficientsBySex) {
    int[] coefficients = coefficientsBySex.get(group.sex());
    if (coefficients == null) {
      throw new IllegalStateException("性别 " + group.sex() + " 不在劳动系数表里（拒绝臆造）");
    }
    return coefficients[ageBracketOf(group.ageAtAnchorDays())];
  }

  /**
   * ★★ **有效劳动（千分劳动）= Σ 批次 (count × 该批次的每人系数)**：人口按"性别 × 年龄"分组的**唯一**折算入口 （设计稿 §四：{@code
   * availableLabor = Σ(count × ageSexCoefficient)}）。
   *
   * <p>★ **性别在这里进入折算**：每人系数按 {@code group.sex()} 取表（默认两性同表 ⇒ 与 R1 之前逐值相同）。
   */
  static long laborMilli(List<PopulationGroup> groups) {
    return laborMilli(groups, AGE_LABOR_COEF_BY_SEX);
  }

  /** 同上的**可注入表**重载（包内可见的旋钮：R2 的"男耕女织"与它的判别力都落在这里）。 */
  static long laborMilli(List<PopulationGroup> groups, Map<Sex, int[]> coefficientsBySex) {
    long total = 0L;
    for (PopulationGroup group : groups) {
      total += group.count() * perCapitaLaborPerMille(group, coefficientsBySex);
    }
    return total;
  }

  /**
   * 某行分到的有效劳动 = 本行人口 × **池的人均劳动**（{@code poolLaborMilli ÷ poolCount}，逐行取整；池空 ⇒ 0）。
   *
   * <p>★ 与 R1 之前**逐值同形**：池的年龄构成恰是 D4 preset、且系数表两性同值时，{@code poolLaborMilli == poolCount × 580} ⇒
   * 本式退化为 {@code 人口 × 580}（那就是旧口径）。整数运算，不丢精度、不做除零。
   */
  private static long rowLaborMilli(long population, long poolLaborMilli, long poolCount) {
    if (poolCount == 0L) {
      return 0L;
    }
    return population * poolLaborMilli / poolCount;
  }

  /**
   * 有效劳动（千分劳动）的**窄入口**：人口 × Σ(年龄档占比 × 档内系数)（§十"D4 默认"，= 每人 580‰）。
   *
   * <p>★ **它不读批次**（没有批次可读时用它：{@code EconomyTestWorld} 那类手搭的夹具）。口径是"人口未按性别/年龄分组"， 故与 {@link
   * #laborMilli(List)} 在默认 preset 下**同值**（两性同系数 ⇒ 每个性别的人均系数都是 580‰）。
   */
  public static long laborMilli(long population) {
    long perCapitaPerMille = 0L;
    for (int i = 0; i < AGE_SHARE_PER_MILLE.length; i++) {
      perCapitaPerMille += (long) AGE_SHARE_PER_MILLE[i] * AGE_LABOR_COEF_PER_MILLE[i] / 1000L;
    }
    return population * perCapitaPerMille;
  }

  /**
   * **第 1 天**的口粮（毫粮）：{@link EconomyVocabulary#dailyRationMilli}(人口, 1) —— 创世写进 {@code naturalNeeds}
   * 的初值。
   *
   * <p>★★ **为什么带"第 1 天"**（V5）：日耗不是一个常量，而是**绝对日号的函数**（累计口粮的逐日差分：10,000 毫粮/人 ÷ 120 天 除不尽， 残差必须逐日补足，见
   * {@link EconomyVocabulary}）。★ 结算**每天**会把当天需求覆写进 {@code naturalNeeds}（spec §八.8 的"一条真相"） ⇒
   * 这一笔只在"尚未结算过"时可见（读口/GUI 首帧）。
   */
  public static long firstDayRationMilli(long population) {
    return EconomyVocabulary.dailyRationMilli(population, 1L);
  }

  /**
   * 初始库存（毫粮）：{@code 人口} 人 **{@code 该阶层天数} 天**的口粮 = {@link
   * EconomyVocabulary#cumulativeRationMilli}(人口, 天数)。
   *
   * <p>★ 口径校验：这份储备**恰好**够吃到第 {@code days} 天末（第 1..days 天的日耗之和 == 该累计值，逐日差分 telescopes）。 ★ 不许写成"人口 ×
   * 一天的量 × 天数"—— 日耗逐日不同，乘不出来。
   */
  public static long rationMilli(long population, String slot) {
    return EconomyVocabulary.cumulativeRationMilli(population, initialRationDays(slot));
  }

  /** 某阶层"每人几天的口粮"（版本化参数表 {@link #INITIAL_RATION_DAYS_BY_CLASS}）；查不到 ⇒ fail-closed（拒绝臆造）。 */
  public static int initialRationDays(String slot) {
    Integer days = INITIAL_RATION_DAYS_BY_CLASS.get(new ClassSlotId(slot));
    if (days == null) {
      throw new IllegalArgumentException("阶层槽位 " + slot + " 不在初始口粮天数表里（拒绝臆造）");
    }
    return days;
  }

  /**
   * 把 {@code total} 按 **千分比例表** 切成同长子表（§十 的"初始阶层比例"）。
   *
   * <p>★★ **分母恒为 1000‰，绝不用 Σ比例 归一化**：{@code 450/350/150/50} 之和恰为 1000，故正常情形下 {@code Σ 结果 ==
   * total}（余数 ∈ [0, n-1]，按**最大余数法**分派：余数 {@code total × 比例 mod 1000} 大者先得、同余数**按下标序**，见 {@link
   * ProportionalSplit}）。而**比例表被人改坏**（例如地主 50 → 100 而没重分）时 分母仍是 1000 ⇒ {@code Σ 结果 ≠ total} ——
   * 静默归一化会把这个错误**伪装成"一切正常"**（本仓最忌的那一族）， "Σ 行人口 == 格人口"那条守恒断言因此有判别力。
   *
   * <p>★ **不变式**：{@code Σ结果 == total} 当且仅当 {@code Σ比例 == 1000}（或残差为 0 的平凡情形）。
   */
  public static long[] splitByShares(long total, int[] sharesPerMille) {
    long[] weights = new long[sharesPerMille.length];
    for (int i = 0; i < sharesPerMille.length; i++) {
      if (sharesPerMille[i] < 0) {
        throw new IllegalArgumentException("splitByShares 的比例不得为负: " + sharesPerMille[i]);
      }
      weights[i] = sharesPerMille[i];
    }
    return split(total, weights, 1000L);
  }

  /**
   * 把 {@code total} 按**任意非负权重**成比例切成同长子表，**Σ 结果恰为 {@code total}**（残差按最大余数法分派）。
   *
   * <p>★ 与 {@link #splitByShares} 的分工：这里的分母是**权重之和**（权重不是千分数，如"按各行人口分土地"）； 权重全为 0（或 {@code total ==
   * 0}）时整份记在第一项（农业人口为 0 的格，土地不丢也不做除零）。
   *
   * <p>★★ **与 {@code EconomySettlement.allocate} 是同一个函数**（都走 {@link
   * ProportionalSplit#byDenominator} 且分母同为 Σ权重）⇒ "分配口径统一"是**可执行的事实**，由 {@code
   * EconomyAllocationConsistencyTest} 跨模块逐值对拍。
   */
  public static long[] splitProportional(long total, long[] weights) {
    return split(total, weights, -1L);
  }

  /**
   * 两个切分函数的共同实现（{@code denominator < 0} ⇒ 用 **Σ权重** 作分母，否则用给定分母）。
   *
   * <p>★ 校验留在两个公开入口（它们的异常消息是既有契约的一部分）；本函数只做"分母选择"。
   */
  private static long[] split(long total, long[] weights, long denominator) {
    if (total < 0) {
      throw new IllegalArgumentException("切分的 total 不得为负: " + total);
    }
    long weightSum = 0L;
    for (long weight : weights) {
      if (weight < 0) {
        throw new IllegalArgumentException("切分的权重不得为负: " + weight);
      }
      weightSum += weight;
    }
    if (weights.length == 0 && total != 0L) {
      throw new IllegalArgumentException("切分没有可承载的槽位，但 total = " + total);
    }
    return ProportionalSplit.byDenominator(
        total, weights, denominator < 0L ? weightSum : denominator);
  }
}
