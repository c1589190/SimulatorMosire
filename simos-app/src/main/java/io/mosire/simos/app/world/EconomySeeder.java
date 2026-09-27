package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.time.EconomySettlement;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ **R2a 生成器**（聚合式经济重设计 §十「验收目标 A」）：把一次世界生成已经算好的 {@link SettlementPlan}（逐格农村人口 + 城市表）翻成**一条**
 * {@code economy.Seed} 命令的载荷。
 *
 * <p>★★ **H1 起它同时产出家户的创世库存**（裁定 D3-C / K1；见 {@link Seed}）：商品库存的**唯一持久真源**是 actor 切片里 家户的 {@code
 * GoodsAccount}，故"每格两组四行的开缸余额"不再写进阶层行，而是由 {@link #openingStock} 一次算好后交回 —— {@link HouseholdSeeder}
 * 用**同一份**结果播 {@code actor.Seed}（"一次算出、同一份喂两条命令"，与人口批次那条接缝同款）。
 *
 * <p>★★ **R1（T4）起人口一律取自 {@link PopulationGroup} 批次列表**（{@link PopulationSeeder#groups} 造的那一份，
 * 同一份也喂给 {@code social.SeedGroups}）：逐格人数 = {@code Σ count}，农村/城镇两池由批次 id 的前缀分 （{@link
 * PopulationLots#isUrban}）。**不再读 {@link io.mosire.simos.social.gen.SettlementPlan}** —— 两条命令读同一份列表，
 * "Σ group == 经济侧总人口"因此是构造性成立的。本类只做"分配 / 换算"，不做人口估计。
 *
 * <p>★★ **R2 起"这个批次属于哪个产业"不再隐含在批次上，而由 {@code LaborAllocation} 表达**（第三阶段设计稿 §四）：本类 **同时**产出该格的
 * {@code laborSupply}（各批次的毛劳动与两项扣除）与 {@code allocations}（各批次把多少劳动给了哪个主体 —— 创世 = "农村批次 → 农业（庄园）/
 * 城镇批次 → 手工业（作坊）"的 1000‰ 配额）。★ 配额之和**逐值等于**改口径前的当日劳动 （见 {@link
 * #industryDailyLabor}），故本轮**改的是结构、不是取值**。
 *
 * <p>★★ **性别进入劳动折算**（R1 的 T4 判据）：每人的千分劳动按 {@code 批次.sex() × 年龄档} 取系数 （{@link
 * #AGE_LABOR_COEF_BY_SEX}，默认两性同表、可按性别覆盖）。人口按"性别 × 年龄"分组这件事由批次承载 （设计稿 §十.6）。
 *
 * <p>★★ **两个守恒在出口成立**（用例逐值断言）：
 *
 * <ul>
 *   <li>**人口守恒**：某格的 {@code Σ 阶层行人口 == 该格人口}（= 该格农村人口 + 落在该格的城市人口）。四舍五入的残差按**最大余数法**
 *       逐格分派（余数大者先得、同余数按下标序，每个槽位最多补 1 人），故不丢也不多。
 *   <li>**土地守恒**：某格农业产业的 {@code capacity[LAND] == 该格土地}（{@code muPerHex × 地形系数}，单位千分亩）。 ★ **H0.3
 *       起土地是"产业产能"而不是"行的生产资料"**（K3）⇒ 这条守恒从"Σ 四行 == 格土地"变成"一个数 == 格土地"， 旧版那条"农业人口为 0
 *       时整份土地记在第一个槽位"的特例**随之消失**（不再需要切分，也就没有除零与残差）。
 * </ul>
 *
 * <p>★★ **H0.2：阶层行的身份 = {@code (格, 居住类型, 阶层)}**（K2；{@code CohortKey}）：**每格两组四行** —— 农村四行（人口 =
 * 农村批次之和）与城镇四行（人口 = 城镇批次之和）。旧版的第三组（{@code weave} 那四行）**不再存在**：它人口恒 0、劳动恒 0，
 * 是同一批农村人的第二本账；它承载的织机与纤维按"产能 → 产业、商品 → 家户"两条规矩各自归位（见 {@link #householdWeaving}）。 ★ **每个产业都不再持有
 * {@code classes}**：产业只说"制度 + 配方 + 本格产能"，家户账只说"这批人有多少粮/多少活"。
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

  /** ★★ **农村家庭纺织**的产业种类标签（R3 的 T4；每个**农村**格一个）。 */
  public static final String WEAVE = "weave";

  /**
   * 农业制度：**领主自营庄园**（spec §六 的 {@code feudal} 档）。★ 本注原写"封建租佃"——**同词两义**：租佃（佃农家户）是另立的 {@code tenant}
   * 档（S1 阶段 3 D7）。
   */
  public static final String REGIME_FEUDAL = "feudal";

  /** 手工业制度（§十"制度"行）。 */
  public static final String REGIME_HANDICRAFT = "handicraft";

  /** ★★ **家户自给**制度（R3 的 T4）：农村家庭纺织的制度标签 —— 它**不是**庄园也不是作坊，是"同一批人农闲织布" （spec §四 的压力测试），故单列一档。 */
  public static final String REGIME_HOUSEHOLD = "household";

  /** 农业周期（天）：§十"单位"行"周期 = 120 天"（手工业同取 120：表里只有这一个周期值）。 */
  public static final int CYCLE_DAYS = 120;

  /**
   * ★★ **创世配额的发放周期**（R2，设计稿 §四）：{@code 1}。
   *
   * <p>★ **为什么是常量而不是"世界当前周期"**：{@code EconomyMeta} 的 {@code lastClosedCycle} 在创世为空 ⇒ 正在进行的周期恒为
   * {@code 0 + 1 = 1}；而且**配额与供给必须同期**（{@code EconomyData} 的构造期守卫按月判），两者由同一条载荷给出 ⇒
   * 无论哪一国先播、隔多久播，这个数都自洽。★ 本轮配额**常设**（跨周期不变），"按周期重发配额"是后续轮次的命令。
   */
  public static final long FIRST_PERIOD = 1L;

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
   * Industry.capacity} 的 {@code LAND} 是**千分亩**——播种步先 {@code / 1000} 换成亩再乘（差 1000 倍）。
   *
   * <p>★ 它让真档**第一次真的读到第三路瓶颈**（v2 spec §三 的 `seedCapMu`）：此前 {@code cycleInputPerUnit} 是空 map ⇒
   * 播种步一字不扣 ⇒ 真档行为与 V2 逐值一致，但三路瓶颈在 799 格真实世界里**看不见**。
   */
  public static final long SEED_MILLI_PER_MU = 8_000L;

  // ── R3（V7 通用生产）的配方参数：三张配方（农业 / 家庭纺织 / 城市作坊）──────────────────

  /**
   * ★★ **每亩需要的劳动**（千分劳动）：{@code 143} = {@code ⌈1000 ÷ 7⌉}（{@code
   * EconomySettlement.LAND_MU_PER_LABOR} = 7）。
   *
   * <p>★★ **为什么是倒数、为什么取整**：V2 的口径是"1 标准劳动（1000 千分劳动）经营 7 亩"（乘法），而 {@code
   * ProductionRecipe.laborPerUnit} 的口径是"每 1 单位规模需要多少劳动"（除法），两者互为倒数，而 {@code 1000 ÷ 7 = 142.857}
   * 不是整数 ⇒ 取**向上取整 143**（比旧口径**略紧**：每 7 亩要 1001 千分劳动而不是 1000）。 ★ **后果只落在"劳动是瓶颈"的格上**：真档的分母是土地（劳动可经营
   * 51,646 亩 ≫ 3,100 亩），故真档收获一分不动。
   */
  public static final long LABOR_MILLI_PER_MU = EconomySettlement.LABOR_MILLI_PER_MU;

  /**
   * ★★ **农田的纤维副产**（单位纤维/亩）：**6**。
   *
   * <p>★ **依据**：亚麻/秸秆是粮食作物的副产物，不占单独的地（spec §四 的 T4："**纤维内生于土地**（不凭空造）"）。 6 单位/亩 是与"每亩 67
   * 粮"同量级的**拍出来的假设**（与前现代亩产的秸秆/纤维量级相符），它的作用是让"田里**同时**出粮与纤维"在真档里看得见。
   *
   * <p>★ 改它 = 改产出结构 ⇒ 记入 {@link #RULES_VERSION}。
   */
  public static final long FIBER_OUTPUT_PER_MU = 6L;

  /**
   * ★★ **每多少农村人有一台织机**（创世场景参数）：**20**。
   *
   * <p>★ 依据：前现代农村"男耕女织"——织布是**家户副业**，一台织机服务一户（一户约 5 口，而参加纺织的是其中一部分人）； 取 20
   * 是为了让**织机、纺织劳动、纤维**三路瓶颈落在同一量级（见 {@code EconomyRealScaleClothTest} 的实测）， 从而"最紧约束"这条判据在真档里真的被走到。
   */
  public static final long RURAL_CAPITA_PER_LOOM = 20L;

  /** ★ 每台织机每周期需要的劳动（千分劳动）：**1000**（= 1 个标准劳动，全职织布）。 */
  public static final long LABOR_MILLI_PER_LOOM = 1_000L;

  /** ★ 每台织机每周期产布（匹）：**30**（= 每 4 天 1 匹，前现代手工织机的量级）。 */
  public static final long CLOTH_PER_LOOM_PER_CYCLE = 30L;

  /**
   * ★★ **每多少城市人有一座作坊**（创世场景参数）：**50**。
   *
   * <p>★ 依据：城市手工业是**专业化**生产（与农村副业不同），一座作坊（含工具、场地、匠人）服务约 50 人。
   */
  public static final long URBAN_CAPITA_PER_WORKSHOP = 50L;

  /** ★ 每座作坊每周期需要的劳动（千分劳动）：**1000**（= 1 个标准劳动）。 */
  public static final long LABOR_MILLI_PER_WORKSHOP = 1_000L;

  /** ★ 每座作坊每周期产布（匹）：**60**（专业化生产 ⇒ 约农村织机的两倍）。 */
  public static final long CLOTH_PER_WORKSHOP_PER_CYCLE = 60L;

  /** ★ 每座作坊每周期产工具（件）：**5**。 */
  public static final long TOOL_PER_WORKSHOP_PER_CYCLE = 5L;

  /** ★★ **每匹布需要多少纤维**（毫纤维/匹）：**1,000**（= 1 单位）。织机与作坊共用同一条口径（同一门手艺）。 */
  public static final long FIBER_MILLI_PER_CLOTH = 1_000L;

  /** ★★ **每件工具需要多少铁**（毫铁/件）：**2,000**（= 2 单位）。★ H5 起**没有配方读它**（见下）。 */
  public static final long IRON_MILLI_PER_TOOL = 2_000L;

  /**
   * ★★ <b>一座作坊每周期消耗的工具（毫工具 / 座·周期）：2,000（= 2 件）</b>—— H5 ④ 的出厂参数（GM 可调）。
   *
   * <pre>
   * 一座作坊一个周期的工具账（单位：毫工具；{@code 1 件 = 1000 毫}）：
   *   毛产   = TOOL_PER_WORKSHOP_PER_CYCLE(5 件) × 1000                    = 5,000
   *   损耗   = 毛产 × (FEED 0‰ + DEPRECIATION 30‰)                          =   150
   *   净产   = 5,000 − 150                                                  = 4,850
   *   投入   = TOOL_MILLI_PER_WORKSHOP_CYCLE(2 件) × 1000                   = 2,000
   *   ★ 净产出 = 4,850 − 2,000 = **+2,850 毫工具 / 座·周期 ≥ 0**            ⇒ 工具存量**可再生**
   * </pre>
   *
   * <p>★★ <b>改前是什么样</b>（H5 ④ 要消灭的那个外生断点）：作坊的投入是<b>铁</b>（{@code TOOL_PER_WORKSHOP_PER_CYCLE ×
   * IRON_MILLI_PER_TOOL = 10,000 毫铁/座·周期}），而**本仓没有任何冶炼流程** ⇒ 铁只能来自创世给的那一箱 ⇒ 用完（实测第 2
   * 个周期）作坊**永久停工**、工具产量归 0（{@code EconomyRealScaleClothTest} 实测：第 240 天作坊布产 =
   * 0）。改成"工具自产自用"之后，作坊吃的是**它自己产出的工具**（净产为正）⇒ 只要它开得起来，就一直开得下去。
   *
   * <p>★ <b>铁的去处</b>（用户裁定"铁留作留位"）：它仍是词表里的商品、仍由创世给城镇家户一份库存、仍在读口的商品清单里 （{@link
   * EconomyVocabulary#allCommodityIds()}）—— <b>但有话直说：本批没有任何配方读它</b>（留位，不是"在用"）。 ★ 取 0 ⇒
   * 作坊不再吃工具（工具只增不减）；取 ≥ 4,850 ⇒ 净产出为负（工具存量**净消耗** ⇒ 又变回"用完停工"， 只是把铁换成了工具）。
   */
  public static final long TOOL_MILLI_PER_WORKSHOP_CYCLE = 2_000L;

  /** 织造的产业活动标签（{@code LaborAllocation.activity}）。 */
  public static final String ACTIVITY_WEAVE = WEAVE;

  /**
   * ★★ **各劳动活动按性别的默认配置权重**（‰，创世 preset）——**"男耕女织"的落点**（spec §四 原文："**性别与年龄影响各类劳动活动的 默认配置权重**"）。
   *
   * <pre>
   * 农业（男耕）：男 600 / 女 400      // 偏向男性
   * 纺织（女织）：男 200 / 女 800      // 偏向女性
   * 作坊（城市手工业）：男 500 / 女 500 // 两性均等（专业化生产，没有那份乡土分工）
   * </pre>
   *
   * ★★ **它不是"女 = 纺织"的硬编码**：权重只是一张**可按活动覆盖的默认表**，配额仍是**按批次的毛劳动加权**切出来的 （{@code 权重 = 毛劳动 ×
   * 该性别在本活动的权重}）⇒ 男人照样进纺织、女人照样种地，只是**默认配置**偏一侧。 ★ 与 {@code AGE_LABOR_COEF_BY_SEX} 同为"创世 preset"：R4
   * 的季节性（农忙 ⇒ 农业劳动需求升 ⇒ 家庭纺织降）会重配它。
   */
  static final Map<String, Map<Sex, Integer>> ACTIVITY_SEX_WEIGHT_PER_MILLE =
      Map.of(
          FARM, Map.of(Sex.MALE, 600, Sex.FEMALE, 400),
          WEAVE, Map.of(Sex.MALE, 200, Sex.FEMALE, 800),
          CRAFT, Map.of(Sex.MALE, 500, Sex.FEMALE, 500));

  /**
   * ★★ **农村日劳动里分给家庭纺织的份额**（‰）：**100**。
   *
   * <p>★★ **这一条是本轮最容易踩的坑**（brief 点名，与 V3 的"播种器留白"同款）：若创世把农村批次 **1000‰ 全给农业**，
   * 家庭纺织就是"**有配额、没活干**"的惰性状态 ⇒ 报表里看不见布。故创世**必须**给农村批次一条非零的纺织配额 （判据：真档推一年后 {@code CLOTH} 库存 &gt; 0
   * 且该配额非零）。
   *
   * <p>★ 依据：农闲时家户投入织布的时间占全年劳动的一成左右；它是**判断结果**（spec §十一）⇒ 具名常量、可按活动调。 ★ **R4 的季节性会调它**（农忙 ⇒ 农业劳动需求升
   * ⇒ 家庭纺织降）。
   */
  public static final int WEAVE_SHARE_PER_MILLE = 100;

  /**
   * 初始阶层比例（‰）：§十"初始阶层比例"行 贫农 450 / 中农 350 / 富农 150 / 地主 50。
   *
   * <p>★ **包内可见**（不是 {@code public}）：数组是可变对象，公开分享等于对外开一个改参数的后门（SpotBugs MS_PKGPROTECT
   * 实测报过）；它只服务本类与同包用例。
   */
  static final int[] CLASS_SHARE_PER_MILLE = {450, 350, 150, 50};

  /** 阶层槽位 id（与 {@link #CLASS_SHARE_PER_MILLE} 同序；包内可见的理由见上）。 */
  /**
   * ★★ **阶层的规范值**（S1 阶段 1：改用**全局词表** {@link SocialClassId} 的值 —— 从 {@code peasant}/{@code
   * middle}/{@code rich} 换成 {@code poor_peasant}/{@code middle_peasant}/{@code rich_peasant}）。
   *
   * <p>★ **顺序必须与 {@link #CLASS_LABOR_PER_MILLE} 一一对齐**（贫农 → 中农 → 富农 → 地主）。
   *
   * <p>★ 保持 {@code String[]}（而非换成 `List&lt;SocialClassId&gt;`）：消费点（`.length`、`CLASS_IDS[i]`、 塞进
   * JSON 的 `slot.put("id", …)`）全部按字符串用 —— 换类型要动 15 处而**收益为零**， 词表校验已经在 {@code new
   * SocialClassId(...)} 那一处生效。
   */
  static final String[] CLASS_IDS = {"poor_peasant", "middle_peasant", "rich_peasant", "landlord"};

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
  public static final Map<SocialClassId, Integer> INITIAL_RATION_DAYS_BY_CLASS =
      Map.of(
          new SocialClassId(CLASS_IDS[0]), 30, // 贫农：最薄（先见底 ⇒ 缺口/借粮/饿死都从它起）
          new SocialClassId(CLASS_IDS[1]), 60, // 中农：与旧口径同（60 天）
          new SocialClassId(CLASS_IDS[2]), 120, // 富农：有余粮可贷
          new SocialClassId(CLASS_IDS[3]), 250); // 地主：最厚（同格主要债权人）

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

  // ── 商品 id：唯一拼写点都在 {@link EconomyVocabulary}（v2 spec §六；R3 起商品不止粮）──────────

  /** 粮。 */
  public static final String COMMODITY_GRAIN = EconomyVocabulary.GRAIN_COMMODITY_ID;

  /** 布（R3；家庭纺织与城市作坊的产出）。 */
  public static final String COMMODITY_CLOTH = EconomyVocabulary.CLOTH_COMMODITY_ID;

  /** 纤维（R3；农田副产，也是织机/作坊的原料）。 */
  public static final String COMMODITY_FIBER = EconomyVocabulary.FIBER_COMMODITY_ID;

  /** 工具（R3；作坊的产出）。 */
  public static final String COMMODITY_TOOL = EconomyVocabulary.TOOL_COMMODITY_ID;

  /** 铁（R3；作坊的原料）。 */
  public static final String COMMODITY_IRON = EconomyVocabulary.IRON_COMMODITY_ID;

  // ── H4：同格市场（固定价、每格单一计价货币）与创世货币禀赋 ──────────────────────────────

  /**
   * ★★ <b>出厂计价货币</b>（H4；裁定 M1-A"每格市场只有一个记账单位"）：{@code silver}。
   *
   * <p>★★ <b>唯一拼写点是 {@link RegimeRelations#DEFAULT_CURRENCY}</b>（H2 的币种位）—— 本类**不写第二份** {@code
   * "silver"} 字面量：同一件事两处拼写，改名那天必然漂开（而"钱是哪种"漂开不会有任何编译错误）。★ <b>M1.1 补记</b>：字面量本身已 搬进世界级货币词表 {@code
   * MoneyVocabulary}（{@code DEFAULT_CURRENCY} 引用它）⇒ 全仓 {@code src/main} 里 {@code "silver"} 恰一处，由
   * {@code EconomyVocabularyGuardTest} 的源扫描钉住。
   */
  public static final CurrencyId MARKET_NUMERAIRE = RegimeRelations.DEFAULT_CURRENCY;

  /**
   * ★★ <b>粮价 = 1</b>：整张表的**基准商品**（口粮口径的实物；M1-A 的计价实物）。
   *
   * <p>★ 依据：粮是本世界唯一"人人要吃、天天要吃"的商品 ⇒ 取它作价值基准，其余商品按"生产一个单位要多少劳动/投入" 与之相比（见下面逐条）。
   */
  public static final long MARKET_PRICE_GRAIN = 1L;

  /**
   * ★★ <b>布价 = 5</b>（1 匹布 = 5 粮的等价 = 5 毫银）。
   *
   * <p>★ 依据（按"生产一个单位要多少劳动/投入"）：一匹布 = <b>1 单位纤维</b>（{@link #FIBER_MILLI_PER_CLOTH}）+ 约 <b>33.3
   * 千分劳动</b>（{@link #LABOR_MILLI_PER_LOOM} 1000 ÷ {@link #CLOTH_PER_LOOM_PER_CYCLE} 30）。 取 5 = 原料 1
   * + 加工 4 —— "加工增值约为原料的四倍"，与"1 匹布换 5 单位粮"的前现代量级相符。 ★ 城市作坊的同一条配方每匹摊到的劳动减半、而多耗 1/6 单位铁（{@link
   * #CLOTH_PER_WORKSHOP_PER_CYCLE}） ⇒ 两条技术路线在同一挂牌价下的毛利率不同：**固定价不看成本**是本批的已知简化 （动态价格 P' =
   * P(1+k(D−S)/(D+S)) 留给价格那一轮）。
   */
  public static final long MARKET_PRICE_CLOTH = 5L;

  /**
   * ★★ <b>纤维价 = 1</b>（与粮同价）。
   *
   * <p>★ 依据：纤维是**农田的副产**——同一亩地、同一份劳动同时出 67 粮（{@link #GRAIN_OUTPUT_PER_MU}）与 6 单位纤维 （{@link
   * #FIBER_OUTPUT_PER_MU}）⇒ 它没有独立的"多花一份工"的成本，出厂按**同价**是保守取值（不给副产折价、 也不溢价）。★ 折价（例如 1/2）同样可辩护；这是 GM
   * 旋钮。
   */
  public static final long MARKET_PRICE_FIBER = 1L;

  /**
   * ★★ <b>铁价 = 10</b>（1 单位铁 = 10 粮的等价）：<b>依据最弱的一条，如实记</b>。
   *
   * <p>★★ 本轮**没有冶炼流程**（{@link #COMMODITY_IRON} 只来自创世给的初始库存，K6 的"留位"商品）⇒ 铁**没有生产成本可推**。
   * 按"它比农副产（纤维）贵一个量级、与工具同量级"给一个判断值；★ 采矿/冶炼接入那一轮必须重定（并且届时它才有 "按劳动推"的依据）。
   */
  public static final long MARKET_PRICE_IRON = 10L;

  /**
   * ★★ <b>工具价 = 20</b>：**由配方内生的比值**，不是另拍的数。
   *
   * <p>★ 依据：作坊每件工具耗 2 单位铁（{@link #IRON_MILLI_PER_TOOL} 2000 毫铁 = 2 单位）⇒ {@code 2 × 铁价 =
   * 20}。工具的加工劳动**已经计在布的售价里**（作坊是"布 + 工具"的联合生产，laborPerUnit 只有一份）⇒ 工具挂牌价恰等于它的铁含量价值，不重复计价。
   */
  public static final long MARKET_PRICE_TOOL = 20L;

  /**
   * ★★ <b>出厂价格表（保序：粮 → 布 → 纤维 → 工具 → 铁）</b>—— 逐格市场的 {@code prices}，键 = 商品 id。
   *
   * <p>★★ <b>量纲（与 {@code Market} 的契约逐字一致）：{@code 价格 = 毫计价货币 / 商品单位}</b>（1 商品单位 = 1000 最小计量单位，见
   * {@link EconomyVocabulary#MILLI_PER_COMMODITY_UNIT}）⇒ economy 侧的结算是 {@code 货款(毫银) = 数量(毫商品) × 价格
   * ÷ 1000}。★ 于是 {@link #MARKET_PRICE_GRAIN}（= 1）读作 <b>1 粮 = 1 毫银</b>（10,000 毫粮 ↔ 10 毫银 =
   * 一个周期的口粮）——"每单位商品的毫银数"是本表的唯一读法， 本类**不另立一套量纲**（价格量纲的唯一拼写点在 {@code Market} 的类注）。
   *
   * <p>★★ <b>整张表都是 GM 可调的数据，不是写死的行为</b>（信条十二）：本类只给**出厂值**，改价 = 改这一行 （或将来迁进 V7
   * 参数目录）；**没有任何代码分支依赖某个具体价格**——价格只被写进载荷、由 economy 侧的同格市场池读。
   *
   * <p>★★ <b>为什么没有木</b>（{@code EconomyVocabulary.WOOD_COMMODITY_ID}）：本轮**无配方、无播种、无库存** ⇒
   * 挂一个没有生产者、也没有持有者的牌价只是噪声（与 K6 对 {@code IRON} 的要求"留位必须有读者"同一条纪律的 反面：**不留没有读者的价**）。木材接入那一轮再加一行。
   *
   * <p>★ <b>键序 = 商品 id 的声明序</b>（{@link #COMMODITY_GRAIN} / {@link #COMMODITY_CLOTH} / {@link
   * #COMMODITY_FIBER} / {@link #COMMODITY_TOOL} / {@link #COMMODITY_IRON}）—— {@code Map.of} 的
   * 迭代序不是内容的纯函数 ⇒ 用 {@code LinkedHashMap} 包一层再冻结（载荷字节因此可复现）。
   */
  public static final Map<CommodityId, Long> MARKET_PRICES_FACTORY = factoryPrices();

  /**
   * ★★ <b>出厂市场</b>（每格同一个：单一计价货币 {@link #MARKET_NUMERAIRE} + 同一张出厂价表）。
   *
   * <p>★ 本批**逐格价格没有差异**（地力/距离进价格是后续轮次的事）⇒ 一个不可变实例被所有格共享（不逐格新建 799 份 逐字相同的对象）。
   */
  public static final Market MARKET_FACTORY = new Market(MARKET_NUMERAIRE, MARKET_PRICES_FACTORY);

  /**
   * ★★ <b>创世货币禀赋的缓冲系数（‰）</b>：{@code 1200} —— 每人 <b>1.2 个周期</b>的口粮等价。
   *
   * <p>★★ <b>为什么必须 ≥ 1000‰</b>（brief 的硬要求："必须够买一个周期的口粮，否则市场形同虚设"）：市场的有效需求 = 有购买力的缺口；若禀赋
   * <b>一个周期</b>的口粮等价，家户在第 1 天就买不起自己那份口粮 ⇒ 市场开张即死。
   *
   * <p>★ <b>为什么多那 200‰</b>：挂牌价是固定价、成交还受"供 &lt; 求 ⇒ 按需求比例配给"约束，且家户一年还要添一身 衣裳（每人每年 1 匹布 = 5 粮的等价）⇒
   * 留两成周转余量，别让禀赋卡在"恰好够粮"的边界上。★ 它是 GM 旋钮。
   */
  public static final int GENESIS_MONEY_BUFFER_PER_MILLE = 1_200;

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
    return plan(mapId, groups, map).economyPayload();
  }

  /**
   * ★★ <b>一次生成的<strong>内存形态</strong></b>（H1）：同一条循环的<strong>四个</strong>产物 —— {@code economy.Seed}
   * 的逐格 {@code entries} 与 {@code markets}，以及**家户的创世库存**与**创世货币禀赋**（键 = 家户身份）。
   *
   * <p>★★ <b>为什么把它们放在一起</b>：H1 起"商品库存"<b>不在阶层行里</b>（{@code ClassRow} 没有 {@code goods}，裁定 D3-C/K1）——
   * 它的持久真源是 actor 切片里该家户的 {@code GoodsAccount}。而"每格两组四行的开缸余额"（口粮按阶层天数、纤维按田亩副产、
   * 城镇行的纤维与铁按作坊数）<b>只能算一次</b>：两处各算一遍必然漂移（本仓最忌"同一事实两处拼写点"）。故本记录是那条接缝 —— {@link #payload(String,
   * List, GameMap)} 取 {@link #entries()} 与 {@link #markets()}，{@link HouseholdSeeder} 取 {@link
   * #householdStocks()} 与 {@link #householdMoney()}（**同一份**：命令播出来的世界与夹具手搭的世界逐字段同形）。
   *
   * <p>★★ <b>H4 的货币为什么不在这里"发行"</b>（裁定 K14）：{@link #householdMoney()} 是**初始条件**，与开缸口粮/纤维/铁 同类 —— 它由
   * {@link #genesisMoneyMilli(long)} 按人口一次算出、写进 {@code actor.Seed} 的账本， 此后世界上**没有任何铸造路径**（{@code
   * MoneyAuthority} 本批无实现者 ⇒ 发行/回笼 fail-closed），逐币种总量恒定， 只有交易在搬它。
   *
   * <p>★ <b>键序是内容的纯函数</b>：entry/市场按 {@code (q,r)} 字典序（{@code hexes.sort}）、每组按 {@code RURAL →
   * URBAN}、每个阶层按 {@link #CLASS_IDS} 序 —— 同一份输入两次调用逐字段相同。★ <b>账本为空的家户也在表里</b>（人口 0 的行：余额可能全 0）—— 见
   * {@link #cohortGroup}。
   */
  public record Seed(
      String mapId,
      List<Map<String, Object>> entries,
      Map<HexCoord, Market> markets,
      Map<CohortKey, Map<CommodityId, Long>> householdStocks,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      List<OperatorSeed> operators) {

    /**
     * ★★ <b>四张表在赋值处冻结</b>（照 {@code Industry.outputPerUnit} / {@code Facts} 的先例）： SpotBugs 的 {@code
     * EI_EXPOSE_REP} <b>不做跨过程分析</b>，看不出"构造器收了可变对象"之后有没有被改，
     * 故防御性拷贝与包装必须写在<b>它看得见的地方</b>（这里），而不是抽成一个助手再调。
     *
     * <p>★ <b>为什么必须冻</b>：本记录是"每格两组四行的开缸余额"与"创世货币禀赋"的<b>唯一拼写点</b>（economy 载荷与
     * 家户账本共用一份），一旦被外部改到，两处就会静默漂开 —— 那正是本仓最忌的"同一事实两处拼写点"。
     */
    public Seed {
      if (mapId == null || mapId.isBlank()) {
        throw new IllegalArgumentException("Seed.mapId 不得为空白");
      }
      List<Map<String, Object>> entriesCopy =
          new ArrayList<>(entries == null ? List.of() : entries);
      entries = Collections.unmodifiableList(entriesCopy);
      // ★ 市场表：外层的键序 = 逐格（(q,r) 字典序，由 plan 保证）；值是共享的不可变 {@link Market}。
      markets =
          Collections.unmodifiableMap(new LinkedHashMap<>(markets == null ? Map.of() : markets));
      Map<CohortKey, Map<CommodityId, Long>> stocksCopy = new LinkedHashMap<>();
      if (householdStocks != null) {
        for (Map.Entry<CohortKey, Map<CommodityId, Long>> entry : householdStocks.entrySet()) {
          stocksCopy.put(
              entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
      }
      householdStocks = Collections.unmodifiableMap(stocksCopy);
      Map<CohortKey, Map<CurrencyId, Long>> moneyCopy = new LinkedHashMap<>();
      if (householdMoney != null) {
        for (Map.Entry<CohortKey, Map<CurrencyId, Long>> entry : householdMoney.entrySet()) {
          moneyCopy.put(
              entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
      }
      householdMoney = Collections.unmodifiableMap(moneyCopy);
      // ★ H5：经营主体的开缸账（逐格逐产业，键序 = 产业生成序 ⇒ 内容的纯函数）。
      operators =
          Collections.unmodifiableList(new ArrayList<>(operators == null ? List.of() : operators));
    }

    /**
     * {@code economy.Seed} 的载荷文本（{@code mapId} / {@code rulesVersion} / {@code entries} / {@code
     * markets} 都在顶层）。
     */
    public String economyPayload() {
      return jsonOf(mapId, entries, markets);
    }
  }

  /**
   * ★★ <b>一个经营主体的开缸账</b>（H5 ⑤）：<b>经营者自己持账</b> —— 主体（{@code ESTATE: farm@0_0} / {@code WORKSHOP:
   * craft@0_0} / {@code HOUSEHOLD: weave@0_0}）、它那一格、它的开缸商品与开缸货币。
   *
   * <p>★★ <b>它为什么必须存在</b>（H5 的题目）：改前经营者**没有任何账**（创世只给家户播）⇒ ① 净产计提在 app 落账时 与家户账的绝对落回打架（{@code
   * tenant} 档的 operator 就是佃农家户 ⇒ 计提被抹掉）；② 关系实付/货币工资的付方是聚合主体 ⇒ economy 看不见它 ⇒ "可用 0 ⇒ 实付 0"（H4
   * 如实记的边界）。
   *
   * <p>★ <b>goods 与 money 都按 {@code GoodsAccount} 的两张余额表</b>（裁定 K15）：0 ⇒ 不落键（空表的纯形态）。 ★
   * <b>货币从哪来</b>：见 {@link #operatorWageReserveMilli}（它自己那条制度里货币档的每周期应付 × 缓冲）——
   * 它是**创世初始条件**（与家户的禀赋同一条口径，裁定 K14），不是发行。
   *
   * @param owner 经营主体（不得为 null；账户真源的键 = {@code (owner, location)}）
   * @param location 该产业所在的那一格（不得为 null）
   * @param label 显示名（**只为读**，不参与任何身份判定）
   * @param goods 开缸商品（逐商品；毫单位）
   * @param money 开缸货币（逐币种；最小币值）
   */
  public record OperatorSeed(
      ActorRef owner,
      HexCoord location,
      String label,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {

    public OperatorSeed {
      if (owner == null) {
        throw new IllegalArgumentException("OperatorSeed.owner 不得为 null");
      }
      if (location == null) {
        throw new IllegalArgumentException(
            "OperatorSeed.location 不得为 null（账户 = (actor, location)）");
      }
      if (label == null || label.isBlank()) {
        throw new IllegalArgumentException("OperatorSeed.label 不得为空白");
      }
      Map<CommodityId, Long> goodsCopy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry :
          (goods == null ? Map.<CommodityId, Long>of() : goods).entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "OperatorSeed.goods 的键值非 null 且不得为负: " + entry.getKey());
        }
        if (entry.getValue() > 0L) {
          goodsCopy.put(entry.getKey(), entry.getValue());
        }
      }
      goods = Collections.unmodifiableMap(goodsCopy);
      Map<CurrencyId, Long> moneyCopy = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry :
          (money == null ? Map.<CurrencyId, Long>of() : money).entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "OperatorSeed.money 的键值非 null 且不得为负: " + entry.getKey());
        }
        if (entry.getValue() > 0L) {
          moneyCopy.put(entry.getKey(), entry.getValue());
        }
      }
      money = Collections.unmodifiableMap(moneyCopy);
    }
  }

  /**
   * ★★ <b>经营者的创世<strong>工资周转金</strong></b>（毫计价货币；H5 ⑤）：<b>它自己那条制度里全部货币档规则的 每周期应付之和 × {@link
   * #GENESIS_MONEY_BUFFER_PER_MILLE}÷1000</b>。
   *
   * <pre>
   * handicraft：4 条 FIXED_MONEY_WAGE × 1,000 毫/周期 = 4,000 毫银/周期
   *             × 1200‰（与家户禀赋同一个缓冲）= **4,800 毫银**（= 1.2 个周期的工资）
   * 其余三档（feudal / household / tenant）：没有货币档规则 ⇒ 0 ⇒ 空钱包
   * </pre>
   *
   * <p>★★ <b>为什么口径取自"制度里的货币档"而不是另拍一个数</b>：{@code RegimeRelations} 是"每周期该付多少"的**唯一拼写点** ——
   * 播种器另写一份必然与它对不上（改一个数要改两处，而漏改不会报错）。
   *
   * <p>★★ <b>如实记的边界（本批没做的事）</b>：本批**没有任何钱回流到经营者的通道**（同格市场只在家户之间；经营者作为市场 参与者是后续批次）⇒
   * 这笔周转金是**一次性的**：它付完 N=1.2 个周期的工资就见底（此后货币工资回到"实付 0"， 读数里 {@code due > 0 && paid == 0} 看得见）。★
   * 这不是"静默付 0"：付不出是**账面上读得出来**的状态； 要让它长期成立，得让经营者**卖得掉它的布与工具**（市场参与者扩容）。
   */
  static long operatorWageReserveMilli(String regime, IndustryId id, ActorRef owner) {
    ProductionRelation relation =
        RegimeRelations.defaultRelation(
            new RegimeId(regime), id, owner, Set.of(ResidenceKind.URBAN));
    long perCycle = 0L;
    for (CompensationRule rule : relation.rules()) {
      if (rule.type().money()) {
        perCycle += rule.fixedAmount();
      }
    }
    return perCycle * GENESIS_MONEY_BUFFER_PER_MILLE / 1000L;
  }

  /** 一个经营者的开缸钱包（毫计价货币；0 ⇒ 空钱包）—— 逐字照 {@link #genesisMoney(long)} 的"只落正的量"。 */
  static Map<CurrencyId, Long> operatorWallet(long milli) {
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>();
    if (milli > 0L) {
      wallet.put(MARKET_NUMERAIRE, milli);
    }
    return wallet;
  }

  /**
   * 纯函数重载（**包内可见**：用例塞一个 {@code hex -> "plains"} 的替身即可，不必造 {@link GameMap}）—— 与真地图那条走**同一条** {@link
   * #plan}（唯一算一次的地方）。
   */
  static String payload(
      String mapId, List<PopulationGroup> groups, Function<HexCoord, String> terrainOf) {
    return plan(mapId, groups, terrainOf).economyPayload();
  }

  /** 真地图重载（{@code terrainIndex()} 一次物化后 O(1) 查）—— 见 {@link #payload(String, List, GameMap)}。 */
  public static Seed plan(String mapId, List<PopulationGroup> groups, GameMap map) {
    Map<HexCoord, String> terrain = map.terrainIndex();
    return plan(
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
   * 把 {@code entries} 与 {@code markets} 包成 {@code economy.Seed} 的载荷文本（{@code mapId} / {@code
   * rulesVersion} 在顶层）。
   *
   * <p>★★ <b>H4 的市场载荷（app 写、economy 读的形状）</b>：
   *
   * <pre>{@code
   * {"mapId":"Map1","rulesVersion":"aggregate-v1","entries":[…],
   *  "markets":{"0_0":{"numeraire":"silver","prices":{"grain":1,"cloth":5,"fiber":1,"tool":20,"iron":10}}, …}}
   * }</pre>
   *
   * ★ <b>键 = {@link HexCoord#toString()} 的规范串</b>（{@code "<q>_<r>"}）—— 本类**不自己拼**那个格式（
   * "格怎么写成一个串"的唯一拼写点在 {@code HexCoord}）；★ 逐格按 {@code (q,r)} 字典序（{@code LinkedHashMap} 保序） ⇒
   * 同一份输入两次调用**逐字节**相同。
   *
   * <p>★★ <b>缺格的格 = 没有市场</b>（合法状态）：市场只在"本格有经济 entry"时发出（格集 = 批次落点集合）——
   * "这一格没有市场"与"这一格不存在"是两件事，读口照此回答（{@code ApiViews.economyHex}）。
   */
  static String jsonOf(
      String mapId, List<Map<String, Object>> entries, Map<HexCoord, Market> markets) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mapId", mapId);
    payload.put("rulesVersion", RULES_VERSION);
    payload.put("entries", entries);
    Map<String, Object> marketNodes = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Market> atHex : markets.entrySet()) {
      marketNodes.put(atHex.getKey().toString(), marketNode(atHex.getValue()));
    }
    payload.put("markets", marketNodes);
    return ToolSupport.json(payload);
  }

  /** 一格市场的载荷节点：{@code {numeraire, prices}}（键序固定 ⇒ 载荷字节可复现）。 */
  static Map<String, Object> marketNode(Market market) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("numeraire", market.numeraire().value());
    Map<String, Object> prices = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> price : market.prices().entrySet()) {
      prices.put(price.getKey().value(), price.getValue());
    }
    node.put("prices", prices);
    return node;
  }

  /**
   * 纯函数主入口（**包内可见**：用例塞一个 {@code hex -> "plains"} 的替身即可，不必造 {@link GameMap}）。
   *
   * <p>★ **两池都来自批次**（不是两处各抄一份）：农村池 = {@code rural:} 前缀的批次、城镇池 = {@code urban:} 前缀的批次 （{@link
   * PopulationLots#isUrban}）。格集 = **批次的落点集合** —— 于是"经济侧该格有没有人口"也只有一处真相。
   *
   * @param terrainOf 逐格地形 key（真路径 = {@code map.terrainIndex()}）；未知地形 fail-closed
   */
  static Seed plan(
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
    Map<CohortKey, Map<CommodityId, Long>> householdStocks = new LinkedHashMap<>();
    // ★★ H4：创世货币禀赋（裁定 K14）走**同一处接缝** —— 与 householdStocks 同一次循环算出、同一份交给
    //   {@link HouseholdSeeder}（两处各算一遍必然漂开）。★ 它是**初始条件**，不是发行：见 {@link #householdMoney}。
    Map<CohortKey, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    // ★★ H4：逐格市场的载荷（M1-A 每格单一计价货币 + 固定价）。本批逐格价格无差异 ⇒ 共享同一个不可变 {@link Market}。
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    // ★★ H5：逐格逐产业的**经营主体开缸账**（键序 = 产业生成序 = farm → weave → craft ⇒ 内容的纯函数）。
    List<OperatorSeed> operators = new ArrayList<>();
    for (HexCoord hex : hexes) {
      List<PopulationGroup> ruralPool = ruralByHex.getOrDefault(hex, List.of());
      List<PopulationGroup> urbanPool = urbanByHex.getOrDefault(hex, List.of());
      IndustryId farmId = IndustryHexKeys.id(FARM, hex.q(), hex.r());
      IndustryId craftId = IndustryHexKeys.id(CRAFT, hex.q(), hex.r());
      IndustryId weaveId = IndustryHexKeys.id(WEAVE, hex.q(), hex.r());
      // ★★ **本格的产能与人口派生量**（H0.3：产能从"行"搬到"产业"，故它们在这里一次算好）：
      //   亩 = 地形系数决定（**与人口无关**：没人种的格，地还在）；织机/作坊 = 人口 ÷ 场景参数。
      long landMilliMu = landMilliMuOf(terrainOf.apply(hex));
      long looms = populationOf(ruralPool) / RURAL_CAPITA_PER_LOOM;
      long workshops = populationOf(urbanPool) / URBAN_CAPITA_PER_WORKSHOP;
      List<Map<String, Object>> industries = new ArrayList<>(3);
      industries.add(agriculture(hex, landMilliMu));
      boolean hasRural = populationOf(ruralPool) > 0L;
      if (hasRural) {
        // ★★ R3（T4）：农村家庭纺织 —— 配方 FIBER + LABOR + TOOL → CLOTH，由**同一批农村人**承担（见 appendAllocation）。
        //   ★ 它**没有自己的阶层行**（H0.2 起织机住在本产业的 {@code capacity}，纤维住在农村四行）⇒ 只有"有农村人口"
        //     的格才建它（没有农村人口的格既无织机也无农村劳动配额 ⇒ 建出来是一具空壳）。
        industries.add(householdWeaving(hex, looms));
      }
      boolean hasCraft = populationOf(urbanPool) > 0L;
      if (hasCraft) {
        industries.add(handicraft(hex, workshops));
      }
      // ★★ H5 ⑤：**经营者自己持账** —— 有产业才有经营主体，故这一份与上面三个产业**逐条对齐**：
      //   · farm（恒有，ESTATE）：开缸商品空（它的种子在**出料主体**的账上 —— feudal 档的 inputSupplier 就是它自己，
      //     而它的缸空 ⇒ H3 的家户代理那一层照旧供种，逐值不变）；无货币档 ⇒ 空钱包；
      //   · weave（有农村人口才有，HOUSEHOLD）：开缸商品空（纤维在**农村家户**的账上，H0.2 的既定分工）；无货币档；
      //   · craft（有城镇人口才有，WORKSHOP）：开缸商品 = **一个周期的工具用量 / 座**（H5 ④：工具是它自己的产品，
      //     故这份周转料交给它自己 —— 若仍留在城镇家户账上，"作坊吃自己产的工具"这条通道就断在别人的缸里）；
      //     钱包 = 工资周转金（见 {@link #operatorWageReserveMilli}）。
      operators.add(operatorSeed(farmId, REGIME_FEUDAL, hex, Map.of()));
      if (hasRural) {
        operators.add(operatorSeed(weaveId, REGIME_HOUSEHOLD, hex, Map.of()));
      }
      if (hasCraft) {
        operators.add(
            operatorSeed(
                craftId,
                REGIME_HANDICRAFT,
                hex,
                Map.of(new CommodityId(COMMODITY_TOOL), workshops * toolPerWorkshopMilli())));
      }
      // ★★ **R2：该格的劳动供给与配额**（第三阶段设计稿 §四）—— 创世按"农村批次 → 农业（庄园）/ 城镇批次 →
      //   手工业（作坊）"初始化配额；**R3 起农村那 1000‰ 拆成"农业 900‰ + 家庭纺织 100‰"**（同一批人两条配额，
      //   总和仍 ≤ 该批次的可用劳动 —— 由 {@code EconomyData} 的构造期守卫判死）。
      //   ★ **供给行每池只发一次**（{@link #appendSupply}）；配额按 (池, 产业) 各发一条（{@link #appendAllocation}）。
      List<Map<String, Object>> laborSupply = new ArrayList<>();
      List<Map<String, Object>> allocations = new ArrayList<>();
      appendSupply(laborSupply, ruralPool);
      appendSupply(laborSupply, urbanPool);
      if (hasRural) {
        long ruralDaily = industryDailyLabor(ruralPool);
        long weaveQuota = ruralDaily * WEAVE_SHARE_PER_MILLE / 1000L;
        // ★ 残差归农业（"农业 = ruralDaily − weaveQuota"⇒ **默认参数下**两条配额之和恒等于本池日劳动）。
        Map<PeopleLotId, Long> budget = laborBudget(ruralPool);
        appendAllocation(
            allocations,
            budget,
            ruralPool,
            farmId,
            ActorKind.ESTATE,
            FARM,
            ruralDaily - weaveQuota);
        appendAllocation(
            allocations,
            budget,
            ruralPool,
            weaveId,
            ActorKind.HOUSEHOLD,
            ACTIVITY_WEAVE,
            weaveQuota);
      }
      if (hasCraft) {
        appendAllocation(
            allocations,
            laborBudget(urbanPool),
            urbanPool,
            craftId,
            ActorKind.WORKSHOP,
            CRAFT,
            industryDailyLabor(urbanPool));
      }
      // ★★ **H0.2：本格的阶层行 = 两组四行（家户）** —— 行的身份是 {@code (格, 居住类型, 阶层)}，
      //   **不再挂在任何产业下**（产业只留"制度 + 配方 + 产能"）。这与"行 = 家户、产业 = 生产活动"的分工一一对应：
      //   一格的农村四行是**同一批农村人**，他们既供给农业（900‰）、也供给家庭纺织（100‰）；城镇四行同理只供给作坊。
      //   ★ **两组恒在**（即使某池人口为 0）：格集本身由批次落点决定，而"这一格有没有城镇家户"是一件事、
      //     "这一格此刻有没有城镇人口"是另一件事（H1 的家户 actor 按 格 × 居住 × 阶层 播，形状必须与行集一致）。
      //     人口为 0 的行是**合法的空账**（人口/劳动/需求全 0），不是噪声：它是"这一格有这个家户、只是没人"。
      List<Map<String, Object>> classes = new ArrayList<>(2 * CLASS_IDS.length);
      // ★★ **H1：开缸库存的去处 = 家户账**（actor 侧的 {@code GoodsAccount}）。本方法把它**一次算好**并交回
      //   （{@code Seed.householdStocks}），载荷里的阶层行**不再带 {@code goods}** —— 行里没有商品这件事
      //   在"一本账"的判据（守恒式无 ΔΣRowGoods）里是必须的。
      //   ★★ H4：**创世货币禀赋与它同源**（同一处循环、同一份人口口径）⇒ 商品与货币两本账在同一次 plan 里算定。
      classes.addAll(ruralCohort(hex, ruralPool, landMilliMu, householdStocks, householdMoney));
      classes.addAll(urbanCohort(hex, urbanPool, workshops, householdStocks, householdMoney));
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("q", hex.q());
      entry.put("r", hex.r());
      entry.put("industries", industries);
      entry.put("classes", classes);
      entry.put("laborSupply", laborSupply);
      entry.put("allocations", allocations);
      entries.add(entry);
      // ★★ H4：本格的市场（M1-A：每格一个计价货币 + 一张价表）。★ **有 entry 才有市场** ——
      //   "这一格没有市场"（格不在本表的键集里）是合法状态，不是缺数据。
      markets.put(hex, MARKET_FACTORY);
    }
    return new Seed(mapId, entries, markets, householdStocks, householdMoney, operators);
  }

  /**
   * 一个产业的**经营主体开缸账**（H5 ⑤）：主体由 {@code regime} 推导（{@link RegimeOperators#defaultOperator}，
   * 与载荷边缘**同一条**规则 ⇒ 命令播出来的主体与这里算的是同一个），钱包由 {@link #operatorWageReserveMilli} 给出。
   *
   * @param id 产业 id（主体的 id 就是它 —— 见 {@code RegimeOperators} 的裁定 R3）
   * @param regime 该产业的制度（决定主体的种类与货币档）
   * @param hex 该产业所在的那一格（= 主体账户的第二段）
   * @param goods 开缸商品（逐商品；0 项不落键）
   */
  static OperatorSeed operatorSeed(
      IndustryId id, String regime, HexCoord hex, Map<CommodityId, Long> goods) {
    ActorRef owner = RegimeOperators.defaultOperator(new RegimeId(regime), id);
    return new OperatorSeed(
        owner,
        hex,
        id.value() + " 经营者",
        goods,
        operatorWallet(operatorWageReserveMilli(regime, id, owner)));
  }

  // ── H4：出厂价表与创世货币禀赋（纯函数）──────────────────────────────────────────────

  /**
   * 出厂价表（{@link #MARKET_PRICES_FACTORY} 的构造）—— **保序**：粮 → 布 → 纤维 → 工具 → 铁。
   *
   * <p>★ 为什么不直接 {@code Map.of(...)}：它的迭代序不是内容的纯函数 ⇒ 载荷字节会抖（本仓对"可复现"的既定口径）。
   */
  static Map<CommodityId, Long> factoryPrices() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(new CommodityId(COMMODITY_GRAIN), MARKET_PRICE_GRAIN);
    prices.put(new CommodityId(COMMODITY_CLOTH), MARKET_PRICE_CLOTH);
    prices.put(new CommodityId(COMMODITY_FIBER), MARKET_PRICE_FIBER);
    prices.put(new CommodityId(COMMODITY_TOOL), MARKET_PRICE_TOOL);
    prices.put(new CommodityId(COMMODITY_IRON), MARKET_PRICE_IRON);
    return Collections.unmodifiableMap(prices);
  }

  /**
   * ★★ <b>创世货币禀赋的每人出厂值（毫银）</b>（裁定 K14）—— <b>按人口</b>的一次性初始条件：
   *
   * <pre>
   * 每人一个周期的口粮 = RATION_MILLI_PER_PERSON = 10,000 毫粮（= 10 粮，120 天）
   * 买下它的钱       = 10,000 毫粮 × 粮价 1 ÷ 1000 = 10 毫银          ← 价量表的口径：毫银 / 商品单位
   * 禀赋             = 10 毫银 × 缓冲 1200‰ = 12 毫银（毫银的最小币值）
   * </pre>
   *
   * ★ <b>它为什么"够买一个周期的口粮"</b>：家户一个周期（{@link #CYCLE_DAYS} 120 天）要买的口粮 = {@code
   * RATION_MILLI_PER_PERSON} = 10,000 毫粮/人 ⇒ 按挂牌粮价折 **10 毫银**；本值 **12 毫银 = 1.2 倍** （多出的两成是周转余量，见
   * {@link #GENESIS_MONEY_BUFFER_PER_MILLE}）。★ 口径与结算**同源**：口粮毫数取自 {@link
   * EconomyVocabulary#RATION_MILLI_PER_PERSON}（与 {@code EconomySettlement} 每日需求同一个常量）， 价格换算的 {@code
   * ÷ 1000} 与 {@code Market} 的货款公式**逐字同式**，不是本类另拍的数。
   *
   * <p>★★ <b>为什么是"初始条件"而不是"发行"</b>（裁定 K14）：发行要有发行人 —— {@code MoneyAuthority} 本批**无实现者**（M-J1：未注册发行人
   * ⇒ 任何发行尝试当场抛）⇒ 世界上根本没有"铸币"这条路径。这笔钱与开缸口粮、 纤维、铁**同类**：它们是这份创世载荷的一部分，只在创世出现一次。⇒ 逐币种总量 {@code Σ(人口 ×
   * 12)} 在创世之后 **恒定**，此后只有交易在搬它（守恒式可核：买卖两腿相消）。
   */
  public static long genesisMoneyMilliPerCapita() {
    return EconomyVocabulary.RATION_MILLI_PER_PERSON
        * MARKET_PRICE_GRAIN
        * GENESIS_MONEY_BUFFER_PER_MILLE
        / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
        / 1000L;
  }

  /**
   * 一个家户的创世钱包（毫银）：{@code 人口 × }{@link #genesisMoneyMilliPerCapita()}。
   *
   * <p>★ <b>口径与开缸库存逐字同形</b>（见 {@link #openingStock}）：<b>只落正的量</b> —— 人口 0 的家户拿到一本
   * **空的钱包**（键在、表空），与"一本空账"同义；"没有钱"与"没有这个家户"是两件事。
   *
   * <p>★ <b>可见性 = public</b>（H4 收口）：它是**跨包**的测试夹具（{@code app.gui.GuiApiTest} 与 {@code
   * app.world.EconomyTestWorld} 都要造出"与真播种器逐值相同的钱包"）—— 口径只有一个拼写点，夹具不许自己再拍一个价。
   */
  public static Map<CurrencyId, Long> genesisMoney(long population) {
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>();
    long amount = population * genesisMoneyMilliPerCapita();
    if (amount > 0L) {
      wallet.put(MARKET_NUMERAIRE, amount);
    }
    return wallet;
  }

  /** 一群批次的人数：{@code Σ count}（"每格人数"的唯一算法）。 */
  static long populationOf(List<PopulationGroup> pool) {
    long total = 0L;
    for (PopulationGroup group : pool) {
      total += group.count();
    }
    return total;
  }

  // ── 劳动供给与配额（R2；第三阶段设计稿 §四）──────────────────────────────────────────

  /**
   * ★★ **该池的"产业日劳动"**：{@code Σ_i (行劳动_i × 槽位投入率_i ÷ 1000)}，其中 {@code 行劳动_i = 该槽位人数_i × 池毛劳动 ÷
   * 池人数}（与 {@link #classRow} 写进载荷的那两个数**同一个算法**）。
   *
   * <pre>
   * 人数     people    = splitByShares(Σcount, CLASS_SHARE_PER_MILLE)      // 450/350/150/50
   * 行劳动   rowLabor_i = people[i] × poolLabor ÷ poolCount                 // 阶层间"年龄性别同分布"
   * 日劳动   Σ_i rowLabor_i × CLASS_LABOR_PER_MILLE[i] ÷ 1000              // 贫农 950‰ … 地主 100‰
   * </pre>
   *
   * ★★ **这条算式不是新口径，是"改口径前的结算**逐字**复刻**：R2 之前 {@code EconomySettlement} 每天的 {@code laborToday} 就是
   * {@code Σ(行 laborMilli × participationPerMille ÷ 1000)}，而两个乘数都由本类写进载荷 ⇒
   * 本方法算出的数**恰好**等于改口径前每一天累加进 {@code Industry.cycleLaborMilli()} 的那个数。R2 把它改从"劳动配额表"取 （见 {@code
   * EconomySettlement.laborByActor}），故**配额之和必须逐值等于这个数** —— 这就是真档数字一个都不变的原因。
   *
   * <p>★ 为什么**在这里**（生成器）算而不是在结算里算：结算看不见人口（economy 不认识 social 的 {@code PopulationGroup}），
   * 而"人有多少劳动"这件事只能从人口推；创世一次算好、落成配额，正是 R1 那条"app 一次算出、同一份喂两条命令"的接缝的延续。
   */
  static long industryDailyLabor(List<PopulationGroup> pool) {
    return industryDailyLabor(
        splitByShares(populationOf(pool), CLASS_SHARE_PER_MILLE),
        laborMilli(pool),
        populationOf(pool));
  }

  /**
   * 同上，但**按"已切好的阶层人数 + 池毛劳动 + 池人数"**给（不读批次）—— 服务**手搭的夹具** （{@code EconomyTestWorld} 那类没有 {@link
   * PopulationGroup} 的世界：它用 {@link #laborMilli(long)} 的窄口径造行）。
   *
   * <p>★ 与 {@link #industryDailyLabor(List)} **是同一个算式**（后者只是先切人、再转调本方法）⇒
   * "真档的配额之和"与"手搭世界的配额之和"不可能漂开。
   */
  static long industryDailyLabor(long[] people, long poolLabor, long poolCount) {
    long total = 0L;
    for (int i = 0; i < CLASS_IDS.length; i++) {
      total += rowLaborMilli(people[i], poolLabor, poolCount) * CLASS_LABOR_PER_MILLE[i] / 1000L;
    }
    return total;
  }

  /** 一个批次的**毛劳动**（千分劳动）：{@code 人数 × 年龄×性别系数} —— {@link #laborMilli(List)} 的单批次形态。 */
  static long grossLaborMilli(PopulationGroup group) {
    return group.count() * perCapitaLaborPerMille(group);
  }

  /**
   * ★★ **给一个池的每个批次发一条劳动供给**（R2）：{@code {group, period, grossLaborMilli, servedLaborMilli=0,
   * committedLaborMilli=0}} —— 每批次**至多一条**（{@code EconomyData.laborSupply} 以批次 id 为键）。
   *
   * <p>★★ **为什么它与配额分家**（R3）：R2 时"一池一产业"⇒ 发配额时顺手发供给是等价的；R3 起**同一个农村池供给两个产业** （农业 +
   * 家庭纺织），若仍由发配额的函数发供给，第二条配额会让同一批次出现**两份供给记录**（载荷解析当场拒： "同一份载荷里劳动供给重复"）。 ⇒ 供给按**池**发一次、配额按 **(池,
   * 产业)** 各发一条，两者各自幂等。
   *
   * <p>★ **零毛劳动的批次不发供给**（未成年批次：D4 preset 的系数为 0）：发一条 0 的供给只是噪声， 而"没有供给"与"毛额 0
   * 的供给"在结算与读口上**逐值同效**（两者都贡献 0，且都发不出配额）。
   *
   * @param laborSupply 出参：本格的供给行（每批次至多一条）
   */
  static void appendSupply(List<Map<String, Object>> laborSupply, List<PopulationGroup> pool) {
    for (PopulationGroup group : pool) {
      long gross = grossLaborMilli(group);
      if (gross <= 0L) {
        continue;
      }
      Map<String, Object> supply = new LinkedHashMap<>();
      supply.put("group", group.id().value());
      supply.put("period", FIRST_PERIOD);
      supply.put("grossLaborMilli", gross);
      // ★ 两项扣除本轮恒 0，但字段在（设计稿 §四 的公式是三项相减；LaborSupply 照减）。
      supply.put("servedLaborMilli", 0L);
      supply.put("committedLaborMilli", 0L);
      laborSupply.add(supply);
    }
  }

  /**
   * ★★ **给一个产业发配额**（R2；R3 起按活动加性别权重）：把 {@code total} 按各批次的**加权毛劳动**成比例切给它们 （最大余数法，{@code Σ 配额 ==
   * total}）。
   *
   * <pre>
   * 权重(batch) = 该批次毛劳动 × ACTIVITY_SEX_WEIGHT_PER_MILLE[活动][该批次性别] ÷ 1000
   * 配额(batch) = total × 权重 ÷ Σ权重                              // Σ == total（精确，残差按最大余数法）
   * </pre>
   *
   * <p>★★ **"同一批次可以供给多个产业"因此是结构上成立的**：本方法只写"某一 (批次, 产业) 对"的一条配额，同一批次被另一个活动 再调用一次就会拿到**第二条**配额。
   * 创世给农村批次发**两条** （农业 900‰ + 家庭纺织 100‰），**这正是 spec §四 那条压力测试**（"同一批人口能否同时参与多个生产过程，并保持劳动力守恒"）。
   *
   * <p>★★ **性别在这里进入配置**：权重按批次性别取 {@link #ACTIVITY_SEX_WEIGHT_PER_MILLE} ⇒ 纺织的配额默认偏向女性
   * （"男耕女织"），而**不是**"女 = 纺织"的硬编码（男人照样有一条非零的纺织配额，只是权重低）。
   *
   * <p>★★ **每一批次的配额受 {@code budget} 约束**（见 {@link #laborBudget}）：切出来的份额若超过该批次**剩下的**可支配劳动，
   * 就压到上限（**多出来的部分留在预算里，也就是"分不满"** —— R2 明说配额之和可以小于可用劳动）。 ⇒ "Σ 该批次在各产业的配额 ≤
   * 其可用劳动"**构造性成立**，不依赖"默认权重恰好不越界"。★ 这道预算是实测出来的： 把 {@link #WEAVE_SHARE_PER_MILLE} 改成 0（一个完全合理的 GM
   * 配置）时，性别权重的偏斜加上 86% 的参与率折扣，会让男性青壮批次 分到 4,202,381 &gt; 它的可用 4,072,000 ⇒ 载荷被 {@code EconomyData}
   * 的构造期守卫**当场拒**（世界播不出来）。
   *
   * <p>★ **{@code total ≤ 0} 时不发**（该池没活干，或该活动拿到的份额取整为 0）；**权重为 0 的批次也不发**（"没有配额"与"0 的配额"逐值同效）。
   *
   * @param allocations 出参：本格的配额行（每 (批次, 产业) 一条）
   * @param budget 该池各批次的**剩余可支配劳动**（{@link #laborBudget}；**就地扣减**）
   * @param total 该产业本次要切出去的劳动总量（千分劳动；= 该池日劳动 × 该活动的份额）
   * @param industry 收劳动的那个产业（{@code actor.id} 就是它 —— 见 {@code EconomyData} 的构造期守卫）
   * @param kind 该产业的制度身份（农业 = {@link ActorKind#ESTATE} 庄园、家庭纺织 = {@link ActorKind#HOUSEHOLD} 家户、
   *     手工业 = {@link ActorKind#WORKSHOP} 作坊）
   * @param activity 这笔劳动干什么（本仓当前用产业种类标签：{@code farm} / {@code weave} / {@code craft}）
   */
  static void appendAllocation(
      List<Map<String, Object>> allocations,
      Map<PeopleLotId, Long> budget,
      List<PopulationGroup> pool,
      IndustryId industry,
      ActorKind kind,
      String activity,
      long total) {
    if (total <= 0L) {
      return;
    }
    Map<Sex, Integer> sexWeights = ACTIVITY_SEX_WEIGHT_PER_MILLE.get(activity);
    if (sexWeights == null) {
      throw new IllegalStateException("活动 " + activity + " 不在性别权重表里（拒绝臆造）");
    }
    List<PopulationGroup> workers = new ArrayList<>(pool.size());
    List<Long> weights = new ArrayList<>(pool.size());
    for (PopulationGroup group : pool) {
      long gross = grossLaborMilli(group);
      if (gross > 0L) {
        workers.add(group);
        weights.add(gross * sexWeightOf(sexWeights, group.sex()) / 1000L);
      }
    }
    if (workers.isEmpty()) {
      return; // 池里没有能干活的人（毛劳动全 0）⇒ total 必为 0，上面已经挡掉；这里是防御性的第二道
    }
    long[] weightArray = new long[weights.size()];
    for (int i = 0; i < weightArray.length; i++) {
      weightArray[i] = weights.get(i);
    }
    long[] shares = splitProportional(total, weightArray); // Σ shares == total（最大余数法，一个人不丢）
    for (int i = 0; i < workers.size(); i++) {
      PopulationGroup group = workers.get(i);
      // ★★ 预算约束（见方法注释）：份额超过该批次**剩下的**可支配劳动 ⇒ 压到上限，多出来的留在预算里。
      long share = Math.min(shares[i], budget.getOrDefault(group.id(), 0L));
      budget.put(group.id(), budget.getOrDefault(group.id(), 0L) - share);
      if (share <= 0L) {
        continue; // 该批次在这一活动上的权重为 0 / 取整为 0 / 预算已用尽 ⇒ 不发 0 配额
      }
      Map<String, Object> allocation = new LinkedHashMap<>();
      allocation.put("id", allocationId(industry, group));
      allocation.put("group", group.id().value());
      Map<String, Object> actor = new LinkedHashMap<>();
      actor.put("kind", kind.name());
      actor.put("id", industry.value());
      allocation.put("actor", actor);
      allocation.put("activity", activity);
      allocation.put("laborMilli", share);
      allocation.put("period", FIRST_PERIOD);
      allocations.add(allocation);
    }
  }

  /**
   * ★★ **一个池的劳动预算**：每批次的可用劳动（= {@link #grossLaborMilli}，与劳动供给的毛额**同一个数**）。
   *
   * <p>★★ **为什么要有它**：{@link #appendAllocation} 按"性别权重 × 毛劳动"切活动总量，而**一个池的日劳动是该池各行的 参与率折扣后的数**（≈ 毛额的
   * 86%）—— 两个活动先后发配额时，偏重的那一性完全可能被分到超过自己那份毛额 （实测：{@link #WEAVE_SHARE_PER_MILLE} 设 0 时男性青壮批次
   * 4,202,381 &gt; 4,072,000）。 那时载荷会被 {@code EconomyData} 的构造期守卫拒 ⇒
   * **世界直接播不出来**。有了预算，那种配置退化成"分不满"（合法状态）。
   *
   * <p>★ 预算与供给的毛额同源（{@code grossLaborMilli}）⇒ "Σ 配额 ≤ availableLabor"这条不变量的两侧读的是同一个数。
   */
  static Map<PeopleLotId, Long> laborBudget(List<PopulationGroup> pool) {
    Map<PeopleLotId, Long> budget = new LinkedHashMap<>();
    for (PopulationGroup group : pool) {
      long gross = grossLaborMilli(group);
      if (gross > 0L) {
        budget.put(group.id(), gross);
      }
    }
    return budget;
  }

  /** 某性别在某活动上的默认权重（‰）；表缺 ⇒ fail-closed（拒绝臆造）。 */
  static int sexWeightOf(Map<Sex, Integer> weights, Sex sex) {
    Integer weight = weights.get(sex);
    if (weight == null) {
      throw new IllegalStateException("性别 " + sex + " 不在活动权重表里（拒绝臆造）");
    }
    return weight;
  }

  /**
   * 配额 id 的**唯一拼写点**：{@code alloc-<产业 id>-<批次 id>}。
   *
   * <p>★ **确定性**：{@code (产业, 批次)} 的纯函数 ⇒ 同一对必然给出同一个 id（重放/分支可比），且同一对不会重复。 ★ **不含 {@code "."}**：产业
   * id 形如 {@code farm@0_0}、批次 id 形如 {@code rural:0_0:MALE:1}，两者都不含点 ⇒ 地址 {@code
   * economy:<mapId>:allocation.<id>} 不会被 {@code AddressParser} 在第一个点处截断。
   */
  static String allocationId(IndustryId industry, PopulationGroup group) {
    // ★★ H5：格式的**唯一拼写点**已上移到契约层（{@link LaborAllocation#idOf}）—— 因为劳动再分配
    //   （{@code EconomySettlement.reallocateLabor}）也会新发配额，而它不是本模块的代码。本方法只做转调，
    //   **不再复述那个格式**（两处各拼一遍 ⇒ 改一处漏一处，而漏了不会报错）。
    return LaborAllocation.idOf(industry, group.id()).value();
  }

  // ── 三个产业（**只留制度 + 配方 + 产能**；行已搬到 {@link #ruralCohort} / {@link #urbanCohort}）──────

  /** 本格**可耕地**（千分亩）= {@code 每格基准亩 × 地形系数} —— ★ **与人口无关**（没人种的格，地还在）。 */
  static long landMilliMuOf(String terrain) {
    return MU_PER_HEX * MILLI_MU_PER_MU * arablePerMilleOf(foodOf(terrain)) / 1000L;
  }

  /**
   * 农业（**恒有**，§十）：制度 = **领主自营庄园**（{@link #REGIME_FEUDAL}）；★★ **H0.3 起产能 = 本格可耕地**， 不再按人口切进四行。
   *
   * <p>★ 本注原写"制度 = 封建租佃" —— 那是**同词两义**：租佃（佃农家户）是另立的 {@code tenant} 档，而本方法写进载荷的是 {@link
   * #REGIME_FEUDAL}（**领主自营庄园**）。它原与同文件里 {@code REGIME_FEUDAL} 常量注**直接矛盾**， 已在 S1 阶段 3 D8 就地改对（同 D7
   * 的口径）。
   *
   * <p>★★ **H0.2：它名下不再有四行** —— 那一格的四行农村家户搬到 entry 级（{@link #ruralCohort}），
   * 因为"这一格有多少地"是**产业**的事、"这一格的人有多少粮/多少活"是**家户**的事（K2/K3 的分工）。
   */
  private static Map<String, Object> agriculture(HexCoord hex, long landMilliMu) {
    return industry(
        IndustryHexKeys.id(FARM, hex.q(), hex.r()).value(),
        "农业",
        REGIME_FEUDAL,
        // ★★ **K3：产能从"行"搬到"产业"** —— 本格农业的产能总量 = 本格可耕地（千分亩）。
        //   旧版是"按各行人口切成四份、每行写一份 LAND"（Σ 才等于格土地）；现在总量只有一处真相，
        //   而"地归谁"由产权读口回答，不再与经济结算抢同一个字段。
        //   ★ 值**允许 0**（沙漠/山地/海洋格：可耕地 0）—— 0 是合法产能（"这格没有地"），不是缺键。
        Map.of("LAND", landMilliMu),
        Map.of("meansWeightPerMille", 700, "laborWeightPerMille", 300),
        // ★★ **V7 配方**：规模单位 = **亩**（每 1 亩要 1,000 千分亩，故 {@code 规模 == 产能的亩数}）；
        //   每一亩需要 {@link #LABOR_MILLI_PER_MU} 千分劳动；每亩产 67 粮 + {@link #FIBER_OUTPUT_PER_MU} 单位纤维
        //   （**田里同时出粮与纤维** —— 纤维是副产物，故不需要新的种植流程）；每亩下种 8 粮。
        Map.of("LAND", MILLI_MU_PER_MU),
        LABOR_MILLI_PER_MU,
        Map.of(COMMODITY_GRAIN, GRAIN_OUTPUT_PER_MU, COMMODITY_FIBER, FIBER_OUTPUT_PER_MU),
        Map.of("LAND", Map.of(COMMODITY_GRAIN, SEED_MILLI_PER_MU)));
  }

  /**
   * ★★ **农村家庭纺织**（R3 的 T4；每个有农村人口的格一个）：配方 {@code FIBER + LABOR + TOOL → CLOTH}（spec §四 的压力测试），制度 =
   * {@link #REGIME_HOUSEHOLD}，收劳动的主体是 {@link ActorKind#HOUSEHOLD} 家户。
   *
   * <p>★★ **H0.2：它不再有四行**（旧版那四行人口恒 0、劳动恒 0，是"同一批人的第二本账"）。旧版那四行上的东西**逐项去处**：
   *
   * <ul>
   *   <li>**织机**（旧：四行各持一份 {@code meansOfProduction.TOOL}）⇒ 并成**总数**写进本产业的 {@code capacity} （{@link
   *       #RURAL_CAPITA_PER_LOOM} 人一台）。理由：织机是**产能**（"单位规模 = 1 台织机"，见 {@code capacityPerUnit}），K3
   *       把它从家户账上收归产业；旧版"Σ 四行 = 本格织机数"这条守恒， 现在由**一个数**直接成立；
   *   <li>**纤维**（旧：四行各持一份 {@code goods.fiber}）⇒ 并入**农村四行**（见 {@link #ruralCohort}）。
   *       理由：那是**农村池的活**（同一批人农闲织布），家户账只能记在自家户名下 —— 记在"纺织"名下等于 给同一批人开第二本账；
   *   <li>**人口与劳动**（旧：恒 0）⇒ 本来就住在农村四行里，**一个数都不动**。农闲织布是同一批人的第二份活， 支撑它的是劳动配额那 100‰（{@link
   *       #WEAVE_SHARE_PER_MILLE}），不是第二份人口。
   * </ul>
   *
   * <p>★★ **纤维从哪来**（**留白，不是遗漏**）：创世给这四行各一份**纤维**（= 本格农业**一个周期**的纤维副产，见 {@link #fiberStockMilli}）。★
   * 把它从"田里"搬到"织机上"是**跨行的实物转移**，正是 spec §六 V8（统一转移）的活， 而 brief 明说"**不建议本轮做跨行实物转移**" ⇒
   * 本轮织机吃的是这份**明标为"估计来源"**的创世库存； 农业自己产的那份照常累积在农业行里（读口看得见）。★ **后果如实记**：一个周期之后织机没有原料 ⇒ 停工，等 V8
   * 把田里的纤维送过来。
   */
  private static Map<String, Object> householdWeaving(HexCoord hex, long looms) {
    return industry(
        IndustryHexKeys.id(WEAVE, hex.q(), hex.r()).value(),
        "家庭纺织",
        REGIME_HOUSEHOLD,
        // ★★ 产能 = 本格**织机总数**（旧版四行各一份、Σ 才是总数）。★ 值允许 0（农村人口 < 20 的格一台也没有）。
        Map.of("TOOL", looms),
        // 分配：家户自给 ⇒ 劳动权重为主（没有土地可摊；织机按户头摊）。
        Map.of("meansWeightPerMille", 300, "laborWeightPerMille", 700),
        Map.of("TOOL", 1L),
        LABOR_MILLI_PER_LOOM,
        Map.of(COMMODITY_CLOTH, CLOTH_PER_LOOM_PER_CYCLE),
        Map.of("TOOL", Map.of(COMMODITY_FIBER, CLOTH_PER_LOOM_PER_CYCLE * FIBER_MILLI_PER_CLOTH)));
  }

  /**
   * 本格农业**一个周期**的纤维副产（毫纤维）= 本格可耕地**亩数** × {@link #FIBER_OUTPUT_PER_MU} × 1000 毫/单位。
   *
   * <p>★ **与产出那一路同源**（{@code outputPerUnit[fiber]} 就是每亩 6 单位）⇒ 这份"初始库存"是从配方推出来的，不是第二个拍出来的数。
   * （旧版从农业四行的 {@code LAND} **逐行**换算成亩再向下取整后求和；现在直接由**产业产能**那一处算， 取整因此**逐格只发生一次** ——
   * 两者在"格土地不是千分亩整数倍"时可能差不到一亩的量级，见 {@code EconomyRealScaleClothTest} 的实测。）
   */
  static long fiberStockMilli(long landMilliMu) {
    return landMilliMu
        / MILLI_MU_PER_MU
        * FIBER_OUTPUT_PER_MU
        * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
  }

  /** 一座作坊**一个周期**的纤维用量（毫纤维）= 产布 × 每匹耗纤维。★ 与 {@code cycleInputPerUnit} **同一条口径**（唯一拼写点）。 */
  static long fiberPerWorkshopMilli() {
    return CLOTH_PER_WORKSHOP_PER_CYCLE * FIBER_MILLI_PER_CLOTH;
  }

  /**
   * 一座作坊**一个周期**的铁用量（毫铁）= 产工具 × 每件耗铁。★ 同上（唯一拼写点）。
   *
   * <p>★ <b>H5 起它只喂创世库存</b>（{@link #urbanCohort} 给城镇家户那一箱铁的用度估计），**没有配方读它** —— 铁按用户裁定留位（见 {@link
   * #TOOL_MILLI_PER_WORKSHOP_CYCLE} 的注释）。
   */
  static long ironPerWorkshopMilli() {
    return TOOL_PER_WORKSHOP_PER_CYCLE * IRON_MILLI_PER_TOOL;
  }

  /**
   * 一座作坊**一个周期**的工具用量（毫工具）= {@link #TOOL_MILLI_PER_WORKSHOP_CYCLE} —— ★ 与 {@code
   * handicraft().cycleInputPerUnit} **同一条口径**（唯一拼写点）。
   */
  static long toolPerWorkshopMilli() {
    return TOOL_MILLI_PER_WORKSHOP_CYCLE;
  }

  /**
   * ★★ **城市作坊**（T5；城市格追加，§十）：制度 = 手工业；★★ **H0.3 起产能 = 本格作坊总座数**。
   *
   * <p>★★ **配方 = {@code FIBER + IRON + LABOR + WORKSHOP → CLOTH + TOOL}**（两条变换合在一座作坊里；"消耗 IRON"
   * 这种话正是 R3 换型要表达的东西）—— 它同时证明两件事（spec §六 给 T5 定的目的）：**非 LAND 生产成立**、**城市能产出自己的产品**。 ★
   * 城乡交换（布换粮）不在本轮（那要 R4 的 V8）。
   *
   * <p>★★ **H0.2：它也不再有四行**。旧版那四行上的东西**逐项去处**：
   *
   * <ul>
   *   <li>**作坊**（旧：四行各持一份 {@code meansOfProduction.WORKSHOP}）⇒ 并成**总数**写进本产业的 {@code capacity}
   *       （{@link #URBAN_CAPITA_PER_WORKSHOP} 人一座）；
   *   <li>**原料库存：纤维 + 铁**（旧：四行各按"该行作坊数 × 一座作坊一个周期的用量"持有）⇒ 并入**城镇四行** （见 {@link
   *       #urbanCohort}）。理由同纺织：这是**城镇池的活**，只能记在城镇家户名下；
   *   <li>**人口与劳动** ⇒ 搬到城镇四行，**逐值不变**（旧版就在 craft 行上，不在 weave 行上）。
   * </ul>
   *
   * <p>★★ **原料从哪来**：创世给这四行各一份**初始库存**（纤维 + 铁，均明标"估计来源"）—— 与家庭纺织同一处置，理由见它的注释。
   */
  private static Map<String, Object> handicraft(HexCoord hex, long workshops) {
    return industry(
        IndustryHexKeys.id(CRAFT, hex.q(), hex.r()).value(),
        "手工业",
        REGIME_HANDICRAFT,
        // ★★ 产能 = 本格**作坊总座数**（旧版四行各一份、Σ 才是总数）。★ 值允许 0。
        Map.of("WORKSHOP", workshops),
        Map.of("meansWeightPerMille", 400, "laborWeightPerMille", 600),
        Map.of("WORKSHOP", 1L),
        LABOR_MILLI_PER_WORKSHOP,
        Map.of(
            COMMODITY_CLOTH,
            CLOTH_PER_WORKSHOP_PER_CYCLE,
            COMMODITY_TOOL,
            TOOL_PER_WORKSHOP_PER_CYCLE),
        // ★★ H5 ④：投入 = **纤维 + 工具**（改前是纤维 + 铁）—— 工具是作坊**自己的产品**（净产为正）
        //   ⇒ 存量可再生，"创世一箱铁 → 用完永久停工"这个外生断点消失。逐条算式见
        //   {@link #TOOL_MILLI_PER_WORKSHOP_CYCLE}。
        Map.of(
            "WORKSHOP",
            Map.of(
                COMMODITY_FIBER, fiberPerWorkshopMilli(),
                COMMODITY_TOOL, toolPerWorkshopMilli())));
  }

  /**
   * 一个产业对象（与 §3.1 {@code Industry} 逐字段对应；**R3 起含 V7 的四个配方分量**；**H0.3 起含产能总量**）。
   *
   * <p>{@code dailyInputPerUnit}/{@code dailyLaborPerUnit} 置 0：§十 没给这两项的依据（那是 R3a 的事），**不臆造**；
   * {@code progressDays} = 0（周期刚起）；{@code cycleDays} = {@link #CYCLE_DAYS}。
   *
   * <p>★★ **它名下没有 {@code classes}**（H0.2）：阶层行按 {@code (格, 居住类型, 阶层)} 挂在 entry 级 —— 一个产业的 {@code
   * slots} 只说"这个制度允许哪些角色"，不再说"这些行归它"。
   *
   * @param capacity 本格该产业的**产能总量**（§3.1 的 {@code capacity}；旧版住在 {@code ClassRow.meansOfProduction}）
   * @param capacityPerUnit 每 1 单位规模需要多少生产资料（农业 = 1 亩；织机/作坊 = 1 台/座）
   * @param laborPerUnit 每 1 单位规模需要多少劳动（千分劳动）
   * @param outputPerUnit 每 1 单位规模的产出（商品单位）
   * @param cycleInputPerUnit 每 1 单位规模每周期消耗的商品（毫单位；按生产资料种类归类）
   */
  private static Map<String, Object> industry(
      String id,
      String name,
      String regime,
      Map<String, Object> capacity,
      Map<String, Object> split,
      Map<String, Object> capacityPerUnit,
      long laborPerUnit,
      Map<String, Object> outputPerUnit,
      Map<String, Object> cycleInputPerUnit) {
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
    // ★★ H0.3（K3）：**本格该产业的产能总量**（承接原 ClassRow.meansOfProduction）——
    //   "单位规模"（capacityPerUnit）× 规模 ⇒ 本格最多开多少规模。
    industry.put("capacity", capacity);
    // ★★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"
    //   （"每单位什么"从此是**数据**；参数目录不在本轮 ⇒ 它们仍在实例里）。
    industry.put("capacityPerUnit", capacityPerUnit);
    industry.put("laborPerUnit", laborPerUnit);
    industry.put("dailyInputPerUnit", Map.of());
    industry.put("dailyLaborPerUnit", 0);
    industry.put("outputPerUnit", outputPerUnit);
    // ★★ 一次性投入（v2 spec §3.3：**周期第一天**现扣的原料）：值侧带商品维度（R3 换型）。
    //   量纲：键值是「毫单位 / 单位规模」，与 capacity 的「千分亩」差 1000 倍 —— 现扣步先 /1000 换成亩再乘。
    //   ★ V7 参数目录（spec §四.1 把它归**制度层**）落地后：本行改读参数（作用域 全局→国家→格/产业）。
    industry.put("cycleInputPerUnit", cycleInputPerUnit);
    // ★ R3a：周期累计实际劳动——创世 = 0（新周期尚未投入；日结算每天累加）。
    industry.put("cycleLaborMilli", 0);
    // ★ R3：本周期实际扣到的投入（**按商品**）——创世 = 空表（与 cycleLaborMilli 同形制；现扣日逐行累加、关账清零）。
    industry.put("cycleInputUsedMilli", Map.of());
    industry.put("allocation", allocation);
    industry.put("slots", slots);
    return industry;
  }

  // ── 家户行：{@code (格, 居住类型, 阶层)}（H0.2）────────────────────────────────────────

  /**
   * ★★ **农村四行**（一层 = 该格农村家户的四个阶层）：人口 = 该格**农村批次**之和按 {@link #CLASS_SHARE_PER_MILLE} 切。
   *
   * <p>★ **它们同时是"农业的行"与"家庭纺织的行"**：农业与纺织是**同一批人的两份活**（900‰ + 100‰ 两条配额）， 故只有一本账。
   *
   * @param landMilliMu 本格可耕地（千分亩）—— 只用来推"本格农田一个周期的纤维副产"这份初始库存
   */
  private static List<Map<String, Object>> ruralCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      long landMilliMu,
      Map<CohortKey, Map<CommodityId, Long>> stocks,
      Map<CohortKey, Map<CurrencyId, Long>> money) {
    // ★★ **纤维的去处**：旧版按阶层份额落在那四行**纺织行**上，H0.2 起并入**农村四行**（同一批人的同一本账）。
    Map<String, long[]> goods = new LinkedHashMap<>();
    goods.put(COMMODITY_FIBER, splitByShares(fiberStockMilli(landMilliMu), CLASS_SHARE_PER_MILLE));
    return cohortGroup(hex, ResidenceKind.RURAL, pool, goods, stocks, money);
  }

  /**
   * ★★ **城镇四行**：人口 = 该格**城镇批次**之和按 {@link #CLASS_SHARE_PER_MILLE} 切（**逐值 = 旧版 craft 四行**）。
   *
   * <p>★ **原料库存的去处**：旧版 craft 四行里的纤维与铁按"该行作坊数 × 一座作坊一个周期的用量"持有， H0.2 起并入**城镇四行**（同一批人的同一本账）、H1
   * 起落进那四个家户的 {@code GoodsAccount}（见 {@link #openingStock}） —— 逐值同式，只是行键不再带产业、且账本搬到了 actor 侧。
   *
   * @param workshops 本格作坊总数（= 城镇人口 ÷ {@link #URBAN_CAPITA_PER_WORKSHOP}）
   */
  private static List<Map<String, Object>> urbanCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      long workshops,
      Map<CohortKey, Map<CommodityId, Long>> stocks,
      Map<CohortKey, Map<CurrencyId, Long>> money) {
    long[] shopByClass = splitByShares(workshops, CLASS_SHARE_PER_MILLE);
    long[] fiber = new long[CLASS_IDS.length];
    long[] iron = new long[CLASS_IDS.length];
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★ 原料库存 = 该阶层分到的作坊数 × 一座作坊**一个周期**的用量（自洽：不是一个拍出来的总量）。
      fiber[i] = shopByClass[i] * fiberPerWorkshopMilli();
      iron[i] = shopByClass[i] * ironPerWorkshopMilli();
    }
    Map<String, long[]> goods = new LinkedHashMap<>();
    goods.put(COMMODITY_FIBER, fiber);
    goods.put(COMMODITY_IRON, iron);
    return cohortGroup(hex, ResidenceKind.URBAN, pool, goods, stocks, money);
  }

  /**
   * **一组四行**（同一格、同一居住类型、四个阶层）：人口按阶层比例切（Σ 恰为该池人口），有效劳动 = 该行人口 × **该池的人均劳动**（见 {@link #classRow}
   * 的旧注：阶层之间"年龄性别同分布"这条明说的假设）。
   *
   * <p>★ {@code goodsByClass} 是"逐商品的**逐阶层**存量表"（与 {@link #CLASS_IDS} 同序）：0 ⇒ 不落键（保持空商品表的纯形态）。
   *
   * <p>★★ <b>H4：货币与商品在**同一处**算定</b>（{@code money} 出参）：{@link #genesisMoney(long)} 按**同一份人口</b>
   * （{@code people[i]}）算出 ⇒ "这家户有多少人、于是有多少口粮、于是有多少钱"三件事不会各算一遍而漂开。
   */
  private static List<Map<String, Object>> cohortGroup(
      HexCoord hex,
      ResidenceKind residence,
      List<PopulationGroup> pool,
      Map<String, long[]> goodsByClass,
      Map<CohortKey, Map<CommodityId, Long>> stocks,
      Map<CohortKey, Map<CurrencyId, Long>> money) {
    long[] people = splitByShares(populationOf(pool), CLASS_SHARE_PER_MILLE);
    long poolLabor = laborMilli(pool);
    long poolCount = populationOf(pool);
    List<Map<String, Object>> rows = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★★ **H1：这个家户的开缸库存 → actor 侧的账本**（键 = {@link HouseholdActors#of} 的那个家户身份）。
      //   ★ **空账也落键**（人口 0 ⇒ 余额全 0）：读口因此读得到"这个家户在这一格有一本账"（既定口径），
      //     而"账本为空"与"这一格没有这个家户"是两件事。
      CohortKey key = new CohortKey(hex, residence, new SocialClassId(CLASS_IDS[i]));
      stocks.put(key, openingStock(people[i], CLASS_IDS[i], goodsByClass, i));
      // ★★ H4：创世货币禀赋走**同一本账**（actor 侧的同一个 {@code GoodsAccount}）—— 见 {@link
      // #genesisMoneyMilliPerCapita}。
      money.put(key, genesisMoney(people[i]));
      rows.add(
          cohortRow(
              residence, CLASS_IDS[i], people[i], CLASS_LABOR_PER_MILLE[i], poolLabor, poolCount));
    }
    return rows;
  }

  /**
   * ★★ <b>一个家户的创世库存</b>（H1；**行里不再有 {@code goods}** ⇒ 这是它的唯一拼写点）：口粮按阶层天数 （{@code 人口 × 该阶层天数} 天，见
   * {@link #rationMilli}）+ 逐行给出的其它商品（R3：农村行的纤维、城镇行的纤维与铁）。
   *
   * <p>★ <b>只落正的量</b>（0 ⇒ 不落键，保持"空账"的纯形态）—— 与旧版 {@code row.goods} 的口径逐字相同。
   */
  static Map<CommodityId, Long> openingStock(
      long population, String slot, Map<String, long[]> goodsByClass, int index) {
    Map<CommodityId, Long> stock = new LinkedHashMap<>();
    putStockIfPositive(stock, COMMODITY_GRAIN, rationMilli(population, slot));
    for (Map.Entry<String, long[]> entry : goodsByClass.entrySet()) {
      putStockIfPositive(stock, entry.getKey(), entry.getValue()[index]);
    }
    return stock;
  }

  /**
   * ★★ **一个家户行**（H0.2 起与 §3.2 {@code ClassRow} 逐字段对应，**除 {@code meansOfProduction} 外** —— 那一项已按 K3
   * 搬到 {@code Industry.capacity}）：身份 = {@code (residence, slot)}（格由它所在的 entry 给出）； 有效劳动 = 本行人口 ×
   * **该池的人均劳动**（= 池内 Σ(count × 年龄档 × 性别的每人系数) ÷ 池人口，见 {@link #laborMilli(List)}）、 自然需求 = **第 1
   * 天**的口粮（{@link #firstDayRationMilli}；结算每天会覆写它，§八.8）、 货币 0、无债务、无有效需求（§十 没有它们的依据 ⇒ 不臆造）。
   *
   * <p>★★ **H1：本行没有 {@code goods}** —— 开缸库存（口粮按阶层天数 + R3 的纤维/铁）由 {@link #openingStock} 算好后 落进 actor
   * 切片该家户的账本（{@link Seed#householdStocks()} ⇒ {@link HouseholdSeeder}）。
   *
   * <p>★★ **为什么"人均"而不是"逐行按自己的年龄构成"**：阶层比例把池子切成四份，而**没有任何数据**说清各阶层的年龄/性别构成 ⇒
   * 取池内人均（"阶层之间年龄性别同分布"这条**明说的**假设），不假装知道更多。默认系数下它与 R1 之前 `人口 × 580‰` **逐值相同**（池的年龄构成恰是 D4 preset
   * 时人均恒为 580），故真档的既有期望值不动。
   *
   * <p>★ **人口为 0 的行**（该池在这格没人）：人口/劳动/需求全 0 —— ★ 它的**开缸库存仍照算** （若那份存量由**土地**派生 ——
   * 如农村行的纤维，则它仍在：**地不因没人种而消失**），只是记在 actor 侧的账本上（H1）。
   */
  private static Map<String, Object> cohortRow(
      ResidenceKind residence,
      String slot,
      long population,
      int participationPerMille,
      long poolLaborMilli,
      long poolCount) {
    Map<String, Object> row = new LinkedHashMap<>();
    // ★★ **居住类型是身份的一维**（H0.1/R-N1-A）：少了它，同一格的农村贫农与城镇贫农会并成同一本账
    //   ⇒ "农村的余粮 + 城市的缺口"并到一起 ⇒ 城市不再饿死，但那不是因为修好了通道。
    //   ★ 字面量取自 {@link ResidenceKind#value()}（前缀/字面的唯一拼写点在契约里，本类不写第二份）。
    row.put("residence", residence.value());
    row.put("slot", slot);
    row.put("population", population);
    row.put("laborMilli", rowLaborMilli(population, poolLaborMilli, poolCount));
    row.put("participationPerMille", participationPerMille);
    // ★★ **H1：这里没有 {@code goods} 键**（裁定 D3-C/K1）—— 商品库存的唯一持久真源是 actor 切片里该家户的
    //   {@code GoodsAccount}，开缸余额由 {@link #openingStock} 一次算好、经 {@link HouseholdSeeder} 落成那本账。
    //   ★ 载荷里再写一份 = 同一事实的第二处拼写点，而且它会静默漂开（economy 侧已不再读它）。
    row.put("money", 0);
    row.put("debts", List.of());
    row.put("naturalNeeds", Map.of(COMMODITY_GRAIN, firstDayRationMilli(population)));
    row.put("effectiveDemand", Map.of());
    return row;
  }

  /** 同上的**家户账本形态**（键是 {@link CommodityId}；同一口径：0 ⇒ 不落键）。★ 与上面那个同名会撞擦除 ⇒ 另起名。 */
  private static void putStockIfPositive(
      Map<CommodityId, Long> stock, String commodity, long amount) {
    if (amount > 0L) {
      stock.put(new CommodityId(commodity), amount);
    }
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
    Integer days = INITIAL_RATION_DAYS_BY_CLASS.get(new SocialClassId(slot));
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
