package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborTimeTable;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ **R2a 生成器**（聚合式经济重设计 §十「验收目标 A」）：把一次世界生成已经算好的 {@link SettlementPlan}（逐格农村人口 + 城市表）翻成**一条**
 * {@code economy.Seed} 命令的载荷。
 *
 * <p>★★ **H1 起它同时产出家户的创世库存**（裁定 D3-C / K1；见 {@link Seed}）：商品库存的**唯一持久真源**是 actor 切片里 家户的 {@code
 * HouseholdInventory}，故"每格两组四行的开缸余额"不再写进阶层行，而是由 {@link #openingStock} 一次算好后交回 —— {@link HouseholdSeeder}
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

  /** 经济播种日志（settlement 分类：播种是经济生命周期的第 0 天）。 */
  private static final org.slf4j.Logger LOG = io.mosire.simos.economy.EconomyLog.settlement();

  /**
   * ★★ <b>经济地基 profile</b>（2026-10-09 起唯一值）：{@link #PRODUCTION_RUNTIME} —— 旧完整生产路径 +
   * 默认生产方式目录/资产规则。★★ P2-C §13.7 起：{@code governmentRef} 给了才建那个政府家户
   * （国库 = 它的家户账户；demo 世界才带周期铸币/发债），没给则只落一条不持户的创世审计主体
   * （{@link #genesisAuditGovernment()}）给 INITIAL_ENDOWMENT 归属 —— 单位政府由
   * {@code economy.RegisterGovernment} 按 GOV 单位稳定 id 建立。
   *
   * <p>★ 旧 {@code class-first} / {@code legacy} / {@code complete} 与
   * {@code production-runtime-government} 线格式名不再可解析（fail-closed，不把缺省猜成旧 profile）。
   */
  public enum FoundationProfile {
    /** ★★ 唯一生产运行时：完整生产 entries + 生产方式目录 + 政府家户（政府内置）。 */
    PRODUCTION_RUNTIME("production-runtime");

    private final String wireName;

    FoundationProfile(String wireName) {
      this.wireName = wireName;
    }

    /** 线格式名（唯一值 {@code "production-runtime"}）。 */
    public String wireName() {
      return wireName;
    }

    /**
     * 按线格式名解析：只接受 {@code "production-runtime"}；其余/空白一律 fail-closed。
     */
    public static FoundationProfile parse(String text) {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("economyProfile 不得为空白；唯一合法值: production-runtime");
      }
      String trimmed = text.trim();
      if ("production-runtime".equals(trimmed)) {
        return PRODUCTION_RUNTIME;
      }
      throw new IllegalArgumentException(
          "未知的 economyProfile: " + text + "；唯一合法值: production-runtime");
    }
  }

  /**
   * ★★ <b>production-runtime 家户 → 默认阶层位置时写进 {@code ClassStanding.reason} 的具名值。</b>
   *
   * <p>命名风格与旧档迁移的 {@code legacyDefault:seedClassStanding} 一致：冒号前是来源、冒号后是动作。它是状态字段的
   * 可审计文本，不是显示给玩家的文本。
   */
  public static final String PRODUCTION_RUNTIME_SEED_REASON =
      "production-runtime:seedClassStanding";

  /** 农业产业种类标签（{@link IndustryHexKeys} 的前缀）。 */
  public static final String FARM = "farm";

  /** 手工业产业种类标签（城市格追加，§十）。 */
  public static final String CRAFT = "craft";

  /** ★★ **农村家庭纺织**的产业种类标签（R3 的 T4；每个**农村**格一个）。 */
  public static final String WEAVE = "weave";

  /** ★★ <b>城市贸易/承运产业标签</b>（P11.7/D-024；每个有城镇人口的格一个）：制度 = {@link RegimeOperators#MERCHANT}。 */
  public static final String TRADE = "trade";

  /**
   * ★★ <b>每个城市格播种的商号运力（毫单位 / 周期）</b>：{@code 100_000} = {@link
   * MerchantPolicy#CITY_CAPACITY_CEILING}（GM 默认；{@code MerchantFirm.capacityPerRound} 构造期要求 &gt; 0）。
   */
  public static final long MERCHANT_CAPACITY_PER_CITY = 100_000L;

  /**
   * ★★ <b>每个城市格给 {@code trade} 播种的 CATTLE 运力资产数量</b>：{@code 100}（GM 默认）。
   *
   * <p>它经现有 {@code assetShares} 出口落成 {@code owner = operator = 商号本金主} 的 {@code OWNED} 份额；
   * {@code trade} 的 {@code capacityPerUnit = {CATTLE: 1}} ⇒ 可支撑 100 单位规模。不凭空造资产：数量就是这个常量。
   */
  public static final long MERCHANT_CATTLE_PER_CITY = 100L;

  /** ★★ 每 1 单位贸易规模的劳动（千分劳动/日）：{@code 1000} = 1 个标准劳动（与作坊口径同量级）。 */
  public static final long LABOR_MILLI_PER_TRADE_UNIT = 1_000L;

  /** ★★ 创世商号的层级（GM 默认）：{@link MerchantPolicy.MerchantTier#PORTER}（三档里最小的一档）。 */
  public static final MerchantPolicy.MerchantTier MERCHANT_TIER =
      MerchantPolicy.MerchantTier.PORTER;

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
   * ★★ **每亩需要的劳动**（千分劳动）：{@code 143} = {@code ⌈1000 ÷ 7⌉}（{@code 旧结算引擎（R3a 已删除）.LAND_MU_PER_LABOR}
   * = 7）。
   *
   * <p>★★ **为什么是倒数、为什么取整**：V2 的口径是"1 标准劳动（1000 千分劳动）经营 7 亩"（乘法），而 {@code
   * ProductionRecipe.laborPerUnit} 的口径是"每 1 单位规模需要多少劳动"（除法），两者互为倒数，而 {@code 1000 ÷ 7 = 142.857}
   * 不是整数 ⇒ 取**向上取整 143**（比旧口径**略紧**：每 7 亩要 1001 千分劳动而不是 1000）。 ★ **后果只落在"劳动是瓶颈"的格上**：真档的分母是土地（劳动可经营
   * 51,646 亩 ≫ 3,100 亩），故真档收获一分不动。
   */
  /** ★ R3a：一名劳动的耕地当量（亩/人）；旧 {@code 旧结算引擎（R3a 已删除）.LAND_MU_PER_LABOR} 的同值搬移。 */
  public static final long LAND_MU_PER_LABOR = 7L;

  /**
   * ★★ <b>每亩需要的劳动（千分劳动）</b>：{@code ⌈1000 ÷ LAND_MU_PER_LABOR(=7)⌉ = 143}。
   *
   * <p>★ R3a：旧 {@code 旧结算引擎（R3a 已删除）.LABOR_MILLI_PER_MU} 常量已随旧结算引擎删除，这里按同一条算式就地 保留（唯一拼写点仍在 {@link
   * #LAND_MU_PER_LABOR}）；逐值 = 143，农业配方不变。
   */
  public static final long LABOR_MILLI_PER_MU =
      (1000L + LAND_MU_PER_LABOR - 1L) / LAND_MU_PER_LABOR;

  /**
   * ★★ **农田的纤维副产**（单位纤维/亩）：**6**。
   *
   * <p>★ **依据**：亚麻/秸秆是粮食作物的副产物，不占单独的地（spec §四 的 T4："**纤维内生于土地**（不凭空造）"）。 6 单位/亩 是与"每亩 67
   * 粮"同量级的**拍出来的假设**（与前现代亩产的秸秆/纤维量级相符），它的作用是让"田里**同时**出粮与纤维"在真档里看得见。
   *
   * <p>★ 改它 = 改产出结构 ⇒ 记入 productionRuntimeRulesVersion()。
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
   * ★★ <b>R4-B.3a：每格产业容量与劳动拆给家户副 unit 的份额（‰）：{@code 300}</b>。
   *
   * <p>★★ <b>拆法（唯一常量、唯一算法）</b>：对每个产业的每条 {@code (industry, asset)} 容量与每条指向主 unit 的劳动配额， 副 unit 合计拿
   * {@code ⌊总量 × 本值 ÷ 1000⌋}，主 unit 拿剩余 —— 于是 {@code Σ 份额 == 旧 capacity}、 {@code Σ 配额 ==
   * 旧配额}（只拆不加，见 {@link #splitIndustry}）。★ 家户自用/佃耕/家户纺织的份额都在这 300‰ 之内， <b>不额外造地、不额外造劳动</b>。
   *
   * <p>★ <b>为什么是 300</b>：出厂判断值（judgement，不是从数据推的）：庄园自营占七成、佃耕/匠户合计三成， 既让同一格出现"两个主体各记各的进度/产出账"，又不把主
   * unit 压到看不见规模。★ GM 旋钮：改它 = 改新世界初态 （不影响旧档迁移路径）。
   */
  public static final int SECONDARY_PER_MILLE = 300;

  /**
   * ★★ <b>R4-B.3a：佃租/匠户分成率（‰）：{@code 300}</b>。
   *
   * <p>佃农 unit 的规则 = {@code OUTPUT_SHARE × GROSS_OUTPUT × grain × 本值‰ × 受方=地主家户}；匠户 unit 的同型规则 以
   * {@code cloth} 付给作坊主。★ 它是<b>一条显式 {@link CompensationRule} 的出厂参数</b>，与 {@code RegimeRelations} 的
   * {@code FEUDAL_RENT_PER_MILLE}（庄园地租 300‰）同量级但各自独立 —— 本轮不改 {@code RegimeRelations}、不改结算。
   */
  public static final int TENANT_RENT_PER_MILLE = 300;

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
   * ★★ <b>创世初始流民比例（‰）：{@code 50} = 5%（GM 默认）。</b>
   *
   * <p>它只在 {@link FoundationProfile#PRODUCTION_RUNTIME} 路径生效：每个池（农村/城镇）按 {@code floor(池人口 ×
   * 本值 ÷ 1000)} 从**最贫一档**（{@link #CLASS_IDS}{@code [0]}）切出一条 {@code displaced.laborer} 家户行；切不出
   * ≥1 人则不发该行（不造 0 人以下家户）。★ 改它 = 改新世界初态，记入 productionRuntimeRulesVersion() 的口径。
   */
  public static final int DISPLACED_SEED_PER_MILLE = 50;

  /**
   * ★★ <b>流民家户的阶层槽位字面量</b>：{@link SocialClassId#LANDLESS_LABORER} 的规范值（{@code
   * "landless_laborer"}）—— 它是 {@link SocialClassId} 封闭词表里与"无地流民"唯一对齐的一档。
   *
   * <p>★★ <b>为什么不是字面量 {@code "displaced"}</b>（如实记偏差）：{@code ClassRow} 的 {@code
   * slot}/view 的阶层段由 {@code EconomyPayloads.classRow} 经 {@link SocialClassId#parse} 解析，而该词表**不含**
   * {@code displaced}（本批文件所有权也不含 {@code SocialClassId}）⇒ 线格式只能用一个词表内阶层。流民的**生产方式**仍是
   * {@link DefaultProductionModes#DISPLACED}，由 {@code productionRuntimeClassStandings} 按本槽位映射到 {@code
   * displaced.laborer} 位置；家户 id 的 {@code roleSuffix="displaced"} 保留"流民户"的显式痕迹。
   */
  static final String DISPLACED_SLOT = SocialClassId.LANDLESS_LABORER.value();

  /**
   * ★★ **初始粮食储备的按阶层天数表**（版本化参数，2026-09-25 用户点名）：键 = 阶层槽位，值 = **每人几天的口粮**。
   *
   * <p>★★ **为什么要按阶层差异化**：旧口径给全世界每一行都配同一份 60 天口粮 ⇒ 同格里**谁都没有余粮**（人人都恰好吃到自己那份），
   * 于是同格借粮链**空转**、缺粮**没有任何后果**。贫农最薄（30 天）、地主最厚（250 天）后，地主/富农手里天然有可贷的余粮， 贫农先见底 ⇒ 同格借贷与（{@code
   * 旧结算引擎（R3a 已删除）} 的）饿死惩罚才有落点。
   *
   * <p>★ 改这张表 = 改初始资源分布 ⇒ 记入 productionRuntimeRulesVersion() 的口径（版本化，不写死在公式里）。
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
   * {@link #CLASS_IDS} / {@link #CLASS_SHARE_PER_MILLE} 里**地主**的槽位序号（= 3）。
   *
   * <p>★ R4-B.3a 的佃耕 unit 要指名"该格农村 landlord 家户"（owner / 租金受方）—— 这个下标不在别处再写数字 3。
   */
  private static final int LANDLORD_SLOT_INDEX = 3;

  /**
   * {@link #CLASS_IDS} 里**作坊主**的槽位序号（= 1，{@code middle_peasant}）。
   *
   * <p>★ P2-A §13.3：城市作坊不再是账户主体 —— 主 unit 的经营者 = 该格城镇中农家户（
   * {@code productionRuntimePositionId} 的 {@code handicraft_workshop.workshop_owner} 档），它的工具周转料与工资周转金
   * 都记在这一户的账上。
   */
  private static final int WORKSHOP_OWNER_SLOT_INDEX = 1;

  /**
   * ★★ <b>M1.8：阶层加权的参与率（‰）</b> —— {@code Σ(阶层份额 × 该阶层参与率) ÷ 1000}。
   *
   * <pre>
   * (450×950 + 350×900 + 150×750 + 50×100) ÷ 1000 = 860 000 ÷ 1000 = 860‰
   * </pre>
   *
   * <p>★★ <b>它为什么存在、以及它为什么不是"第二次折扣"</b>：配额是**按批次**发的，而一个批次（性别 × 年龄）里混着四个阶层 ——
   * 批次的"可用劳动"只能按**池的阶层构成**取加权参与率。于是同一份毛劳动在两条投影上各折算<b>一次</b>： 逐行 = {@link
   * ClassRow#participationAdjustedLaborMilli()}（阶层自己的参与率），逐批次 = 本常量；两者在池上的总量相等（同一批人、同一套份额）。 ★
   * <b>严禁</b>任何调用点在乘过本常量之后又去乘一次逐阶层的参与率（或反过来）—— 那会把同一份劳动折算两遍（M1.8 的靶子）。
   *
   * <p>★ <b>它是派生量、不是第二个参数表</b>：公式只读 {@link #CLASS_SHARE_PER_MILLE} 与 {@link #CLASS_LABOR_PER_MILLE}
   * —— 改那两张表，本值跟着变，不可能漂开。
   */
  static final int CLASS_WEIGHTED_LABOR_PER_MILLE = classWeightedParticipationPerMille();

  /** {@link #CLASS_WEIGHTED_LABOR_PER_MILLE} 的算式（静态初始化用；两处不得各写一份）。 */
  private static int classWeightedParticipationPerMille() {
    long weighted = 0L;
    for (int i = 0; i < CLASS_SHARE_PER_MILLE.length; i++) {
      weighted += (long) CLASS_SHARE_PER_MILLE[i] * CLASS_LABOR_PER_MILLE[i];
    }
    return (int) (weighted / 1000L);
  }

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
   * ★★ <b>P2-A §13.4：家户每 tick 劳动时间预算的系数表（可调参数）</b>：默认 = 未成年 4h / 成年男 16h /
   * 成年女 8h / 老年 0。单位毫小时；{@code ClassRow.laborMilli} 是它在经济侧的投影（每 tick 重算）。
   */
  public static final LaborTimeTable LABOR_TIME_TABLE = LaborTimeTable.DEFAULT;

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

  /**
   * ★★ P10.1/P11.7：PRODUCTION_RUNTIME 新档写入的运行时版本（{@link EconomyMeta#RUNTIME_VERSION_SEVEN_HEX_V2}）—— 唯一拼写点在
   * {@link EconomyMeta}；写入前先过版本门自检（当前版本 ≠ 自检值 ⇒ 当场抛"旧档/版本不符，需要 GM 重置"，而不是把错版本写进新档）。
   */
  static String productionRuntimeRulesVersion() {
    EconomyMeta.requireCurrentRuntimeVersionTag(EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2);
    return EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2;
  }

  /**
   * ★★ E3：创世发行/审计主体的稳定 id（唯一拼写点）。当前市场单一计价货币 {@code silver}。
   *
   * <p>★ P2-C 后的两种形态：给了政府家户的 demo 世界里它是"国库 = 家户账户"的发行政府；普通 nation seed 里它是
   * {@link #genesisAuditGovernment()}（不持户、issuable 空集、旋钮 0），只为 INITIAL_ENDOWMENT 提供归属，不声称任何家户。
   * 单位政府（中央/地方）另用 {@code gov-unit-<unitId>} 的派生 id，不再共用这一把 key。
   */
  public static final GovernmentId GENESIS_GOVERNMENT_ID = new GovernmentId("world-silver");

  /** 世界级政府在 {@code nationRef} 里的引用（当前不是任何真实国家 id，故用保留字面量）。 */
  public static final String GENESIS_GOVERNMENT_NATION_REF = "world";

  /**
   * ★★ <b>2026-10-07 GOV 非生产家户试点：周期铸币的出厂量</b>（毫计价货币 / 产业周期；{@code 10_000} = 10 银）。
   *
   * <p>它是 production-runtime **带政府家户的 demo 世界**（小世界）的具名 GM 默认值：写进
   * {@link Government#seignioragePerCycle()}。当前还没有 GM 实时编辑命令；要改本批口径就改这个常量并重播
   * （与其余 seeder 初态参数同制）。
   */
  public static final long GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI = 2_000L;

  /**
   * ★★ <b>2026-10-07 GOV 非生产家户试点：周期发债目标</b>（毫计价货币 / 产业周期；{@code 5_000} = 5 银）。
   *
   * <p>周期开始日政府按此目标向家户借入货币（真实余额转移 + {@code DebtContract}，不是新钱）；家户可借额不足时
   * 只借到实际可借部分并记 shortfall。它与 {@link #GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI} 一起构成“政府缺钱：先印一部分、
   * 再向家户发一部分债”的试点口径。
   */
  public static final long GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI = 5_000L;

  private EconomySeeder() {}

  /**
   * ★★ **R1 的人口来源**（T4）：人口**不再从 {@link SettlementPlan} 抄**，而是从**同一份** {@link PopulationGroup}
   * 列表按格聚合 —— 那份列表同时喂给 {@code social.SeedGroups} （{@link PopulationSeeder#payload}）。于是"**Σ group ==
   * 经济侧总人口**"是**构造性成立**的： 两侧读的是同一份列表，不需要运行期读 social 切片（那要跨切片协调器，属后续轮次）。
   *
   * <p>★ 走真地图：地形 key 由 {@link GameMap#terrainIndex()} 一次物化后 O(1) 查。
   */
  public static String payload(String mapId, PopulationSeeder.Seeding seeding, GameMap map) {
    return plan(mapId, seeding, map).economyPayload();
  }

  /**
   * ★★ <b>一次生成的<strong>内存形态</strong></b>（H1）：同一条循环的<strong>四个</strong>产物 —— {@code economy.Seed}
   * 的逐格 {@code entries} 与 {@code markets}，以及**家户的创世库存**与**创世货币禀赋**（键 = 家户身份）。
   *
   * <p>★★ <b>为什么把它们放在一起</b>：H1 起"商品库存"<b>不在阶层行里</b>（{@code ClassRow} 没有 {@code goods}，裁定 D3-C/K1）——
   * 它的持久真源是 actor 切片里该家户的 {@code HouseholdInventory}。而"每格两组四行的开缸余额"（口粮按阶层天数、纤维按田亩副产、
   * 城镇行的纤维与铁按作坊数）<b>只能算一次</b>：两处各算一遍必然漂移（本仓最忌"同一事实两处拼写点"）。故本记录是那条接缝 —— {@link #payload(String,
   * List, GameMap)} 取 {@link #entries()} 与 {@link #markets()}，{@link HouseholdSeeder} 取 {@link
   * #householdStocks()} 与 {@link #householdMoney()}（**同一份**：命令播出来的世界与夹具手搭的世界逐字段同形）。
   *
   * <p>★★ <b>H4/E3 的货币口径</b>：{@link #householdMoney()} 由 {@link #genesisMoney(long, long)}
   * 按人口一次算出、写进 {@code actor.Seed} 的账本；E3 起它不是"天上掉的钱"，而是**显式 INITIAL_ENDOWMENT 发行记录**（ {@link
   * #genesisEndowment()} 与钱包同一份表，总量含经营者工资周转金；由 {@code economy.Seed} 的 {@code moneyIssuances} 载荷落进
   * {@code EconomyData}）。逐币种守恒式因此可核： {@code Σ账户余额 = Σ INITIAL_ENDOWMENT + Σ FISCAL_ISSUE − Σ
   * WITHDRAWAL}，普通账户不得为负。
   *
   * <p>★ <b>键序是内容的纯函数</b>：entry/市场按 {@code (q,r)} 字典序（{@code hexes.sort}）、每组按 {@code RURAL →
   * URBAN}、每个阶层按 {@link #CLASS_IDS} 序 —— 同一份输入两次调用逐字段相同。★ <b>账本为空的家户也在表里</b>（人口 0 的行：余额可能全 0）—— 见
   * {@link #cohortGroup}。
   */
  public record Seed(
      String mapId,
      List<Map<String, Object>> entries,
      Map<HexCoord, Market> markets,
      Map<HouseholdId, HexCoord> householdLocations,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      List<OperatorSeed> operators,
      Map<GovernmentId, Government> governments,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      List<Map<String, Object>> debtContracts,
      List<Map<String, Object>> pledges,
      List<Map<String, Object>> extraMoneyIssuances,
      TestConditions.Report conditionReport,
      // ★★ P11.7/D-024：PRODUCTION_RUNTIME 的商号表（键 = 生产组织 id；空表时不发顶层键）。
      Map<ProductionOrganizationId, MerchantFirm> merchantFirms) {

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
      if (profile == null) {
        throw new IllegalArgumentException(
            "Seed.profile 不得为 null（缺省用 FoundationProfile.PRODUCTION_RUNTIME）");
      }
      List<Map<String, Object>> entriesCopy =
          new ArrayList<>(entries == null ? List.of() : entries);
      entries = Collections.unmodifiableList(entriesCopy);
      // ★ 市场表：外层的键序 = 逐格（(q,r) 字典序，由 plan 保证）；值是共享的不可变 {@link Market}。
      markets =
          Collections.unmodifiableMap(new LinkedHashMap<>(markets == null ? Map.of() : markets));
      // ★ S1：家户 id → 它账所在的格（actor id 不进家户 id；账户 location 由这张表显式带过）。
      householdLocations =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(householdLocations == null ? Map.of() : householdLocations));
      Map<HouseholdId, Map<CommodityId, Long>> stocksCopy = new LinkedHashMap<>();
      if (householdStocks != null) {
        for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : householdStocks.entrySet()) {
          stocksCopy.put(
              entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
      }
      householdStocks = Collections.unmodifiableMap(stocksCopy);
      Map<HouseholdId, Map<CurrencyId, Long>> moneyCopy = new LinkedHashMap<>();
      if (householdMoney != null) {
        for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> entry : householdMoney.entrySet()) {
          moneyCopy.put(
              entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
      }
      householdMoney = Collections.unmodifiableMap(moneyCopy);
      // ★ H5：经营主体的开缸账（逐格逐产业，键序 = 产业生成序 ⇒ 内容的纯函数）。
      operators =
          Collections.unmodifiableList(new ArrayList<>(operators == null ? List.of() : operators));
      // ★★ E3：创世发行政府（当前 = 世界级最小政府）与初始发行总量（逐币种；家户钱包 + 经营者钱包）。
      //   两张表都冻结在赋值处；发行总量是"钱包口径"的纯和，绝不另算一遍人口。
      governments =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(governments == null ? Map.of() : governments));
      genesisEndowment =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(genesisEndowment == null ? Map.of() : genesisEndowment));
      if (genesisMoneyMilliPerCapita < 0L) {
        throw new IllegalArgumentException(
            "Seed.genesisMoneyMilliPerCapita 不得为负: " + genesisMoneyMilliPerCapita);
      }
      // ★★ P3：条件产物（债务/质押节点 + 外部注入发行记录 + 报告）也冻在赋值处 —— 它们与 actor.Seed 的账本
      //   是同一次 plan 的同一份事实，被外部改到就会"载荷里的债"与"账上的钱/货"静默漂开。
      //   ★ 拷贝/包装**写在这里而不是抽助手**：SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析（与上方 entries 同款）。
      List<Map<String, Object>> debtContractsCopy =
          new ArrayList<>(debtContracts == null ? List.of() : debtContracts);
      for (Map<String, Object> node : debtContractsCopy) {
        if (node == null) {
          throw new IllegalArgumentException("Seed.debtContracts 的元素不得为 null");
        }
      }
      debtContracts = Collections.unmodifiableList(debtContractsCopy);
      List<Map<String, Object>> pledgesCopy =
          new ArrayList<>(pledges == null ? List.of() : pledges);
      for (Map<String, Object> node : pledgesCopy) {
        if (node == null) {
          throw new IllegalArgumentException("Seed.pledges 的元素不得为 null");
        }
      }
      pledges = Collections.unmodifiableList(pledgesCopy);
      List<Map<String, Object>> extraMoneyIssuancesCopy =
          new ArrayList<>(extraMoneyIssuances == null ? List.of() : extraMoneyIssuances);
      for (Map<String, Object> node : extraMoneyIssuancesCopy) {
        if (node == null) {
          throw new IllegalArgumentException("Seed.extraMoneyIssuances 的元素不得为 null");
        }
      }
      extraMoneyIssuances = Collections.unmodifiableList(extraMoneyIssuancesCopy);
      conditionReport = conditionReport == null ? TestConditions.Report.EMPTY : conditionReport;
      // ★★ P11.7/D-024：商号表在赋值处冻结（同上方四张表）；null ⇒ 空表。
      Map<ProductionOrganizationId, MerchantFirm> merchantFirmsCopy = new LinkedHashMap<>();
      Map<ProductionOrganizationId, MerchantFirm> firms =
          merchantFirms == null ? Map.of() : merchantFirms;
      for (Map.Entry<ProductionOrganizationId, MerchantFirm> entry : firms.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "Seed.merchantFirms 的键与值都不得为 null: " + entry.getKey());
        }
        merchantFirmsCopy.put(entry.getKey(), entry.getValue());
      }
      merchantFirms = Collections.unmodifiableMap(merchantFirmsCopy);
    }

    /**
     * {@code economy.Seed} 的载荷文本（{@code mapId} / {@code rulesVersion} / {@code entries} / {@code
     * markets} 都在顶层）。production-runtime 的 entries 是完整生产结构，并在其后<b>追加</b> {@code modes} / {@code
     * classStructures} / {@code classPositions} / {@code classStandings} / {@code assetRules} / {@code
     * liquidationPolicies} 六个键 + 非空时一个可选 {@code merchantFirms} 数组（见 {@link #jsonOf}）。★ P3：无 conditions 时 {@code
     * debtContracts}/{@code pledges} 仍是空表、也不出现 {@code testConditions} 键。
     */
    public String economyPayload() {
      return jsonOf(
          mapId,
          entries,
          markets,
          governments,
          genesisEndowment,
          genesisMoneyMilliPerCapita,
          debtContracts,
          pledges,
          extraMoneyIssuances,
          conditionReport,
          merchantFirms);
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
   * <p>★ <b>goods 与 money 都按 {@code HouseholdInventory} 的两张余额表</b>（裁定 K15）：0 ⇒ 不落键（空表的纯形态）。 ★
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
   * 纯函数重载（**包内可见**：用例塞一个 {@code hex -> "plains"} 的替身即可，不必造 {@link GameMap}）—— 与真地图那条走**同一条** {@link
   * #plan}（唯一算一次的地方）。
   */
  static String payload(
      String mapId, PopulationSeeder.Seeding seeding, Function<HexCoord, String> terrainOf) {
    return plan(mapId, seeding, terrainOf).economyPayload();
  }

  /**
   * 真地图重载（{@code terrainIndex()} 一次物化后 O(1) 查）—— 见 {@link #payload(String, List,
   * GameMap)}；初始禀赋取默认值，profile 缺省 {@link FoundationProfile#PRODUCTION_RUNTIME}。
   */
  public static Seed plan(String mapId, PopulationSeeder.Seeding seeding, GameMap map) {
    return plan(mapId, seeding, map, genesisMoneyMilliPerCapita(), FoundationProfile.PRODUCTION_RUNTIME);
  }

  /**
   * ★★ E3：真地图重载 + **初始禀赋参数**（毫/人）。默认重载逐值等于旧行为；本重载只改 {@code INITIAL_ENDOWMENT}
   * 的每人金额，商品/人口/劳动/资产口径一字不动。profile 缺省 {@link FoundationProfile#PRODUCTION_RUNTIME}。
   */
  public static Seed plan(
      String mapId, PopulationSeeder.Seeding seeding, GameMap map, long genesisMoneyMilliPerCapita) {
    return plan(mapId, seeding, map, genesisMoneyMilliPerCapita, FoundationProfile.PRODUCTION_RUNTIME);
  }

  /** ★ P1/P2：真地图 + profile（初始禀赋取默认值）；当前唯一值是 {@link FoundationProfile#PRODUCTION_RUNTIME}。 */
  public static Seed plan(
      String mapId, PopulationSeeder.Seeding seeding, GameMap map, FoundationProfile profile) {
    return plan(mapId, seeding, map, genesisMoneyMilliPerCapita(), profile, TestConditions.EMPTY);
  }

  /**
   * ★★ <b>P3：真地图 + 初始禀赋 + profile + 测试条件</b>。要求条件的家户/份额都在本 seed 的格集内（跨 seed 引用没有对侧）；空条件（{@link
   * TestConditions#EMPTY}）逐值等于 P1。
   */
  public static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      GameMap map,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions) {
    Map<HexCoord, String> terrain = map.terrainIndex();
    return plan(
        mapId,
        seeding,
        at -> {
          String key = terrain.get(at);
          if (key == null) {
            throw new IllegalStateException("格 " + at + " 不在 terrainIndex 里（地图分割不变式被破坏）");
          }
          return key;
        },
        genesisMoneyMilliPerCapita,
        profile,
        conditions);
  }

  /** ★★ P1：真地图 + 初始禀赋 + profile 的主入口；P3 起条件缺省为空（逐值等于 P1）。 */
  public static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      GameMap map,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile) {
    return plan(mapId, seeding, map, genesisMoneyMilliPerCapita, profile, TestConditions.EMPTY);
  }

  /** ★★ P3：真地图 + profile + 测试条件（初始禀赋取默认值）—— 载荷便捷入口。 */
  public static String payload(
      String mapId,
      PopulationSeeder.Seeding seeding,
      GameMap map,
      FoundationProfile profile,
      TestConditions conditions) {
    return plan(mapId, seeding, map, genesisMoneyMilliPerCapita(), profile, conditions)
        .economyPayload();
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
  /**
   * ★★ <b>P3：带初始条件产物的载荷构造</b>。{@code debtContracts}/{@code pledges} 由 {@link #applyTestConditions}
   * 在真实转账/拆分成功后给出；{@code extraMoneyIssuances} 是外部注入货币的 {@code FISCAL_ISSUE} 审计节点（**不并入**
   * INITIAL_ENDOWMENT）；{@code conditionReport} 非空时额外落一个顶层 {@code testConditions} 报告键（可审计"这次 seed
   * 注入了什么"）。
   *
   * <p>★★ <b>无 conditions 的路径逐字节不变</b>：三张条件表为空 + 报告为空 ⇒ 不出现 {@code testConditions} 键， {@code
   * debtContracts}/{@code pledges} 仍是空数组，与本方法 P1 版本的输出逐字节相同。
   *
   * <p>★★ production-runtime 在<b>本方法末尾追加</b>六个顶层键：{@code modes} / {@code
   * classStructures} / {@code classPositions} / {@code classStandings} / {@code assetRules} /
   * {@code liquidationPolicies}（默认目录来自 {@code DefaultProductionModes}）。
   */
  static String jsonOf(
      String mapId,
      List<Map<String, Object>> entries,
      Map<HexCoord, Market> markets,
      Map<GovernmentId, Government> governments,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita,
      List<Map<String, Object>> debtContracts,
      List<Map<String, Object>> pledges,
      List<Map<String, Object>> extraMoneyIssuances,
      TestConditions.Report conditionReport,
      Map<ProductionOrganizationId, MerchantFirm> merchantFirms) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mapId", mapId);
    // ★★ P10.1：新档写当前 7 hex 运行时版本（唯一拼写点在 {@code EconomyMeta}）。
    payload.put("rulesVersion", productionRuntimeRulesVersion());
    payload.put("entries", entries);
    Map<String, Object> marketNodes = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Market> atHex : markets.entrySet()) {
      marketNodes.put(atHex.getKey().toString(), marketNode(atHex.getValue()));
    }
    payload.put("markets", marketNodes);
    // ★★ E3：政府与 INITIAL_ENDOWMENT 发行记录（缺省长（ENDOWMENT=零）时只有 governments；旧载荷缺这两个键 ⇒ 空表）。
    payload.put("governments", governmentNodes(governments));
    List<Map<String, Object>> issuances =
        new ArrayList<>(
            issuanceNodes(mapId, entries, genesisEndowment, genesisMoneyMilliPerCapita));
    // ★★ P3：外部注入的货币走独立的 FISCAL_ISSUE 记录 —— 绝不并进 INITIAL_ENDOWMENT 的总量（见 TestConditions）。
    if (extraMoneyIssuances != null) {
      issuances.addAll(extraMoneyIssuances);
    }
    payload.put("moneyIssuances", issuances);
    // ★★ E4c/P3：新键的空语义 —— 无条件时发空表（世界从零债开始，合法）；有条件时**只发真实对价已备好的**
    //   债务/质押（见 applyTestConditions：债权人库存/货币真扣、资产份额真拆、质押真 OWNED 份额）。
    payload.put("debtContracts", debtContracts == null ? List.of() : debtContracts);
    payload.put("pledges", pledges == null ? List.of() : pledges);
    if (conditionReport != null && !conditionReport.isEmpty()) {
      // ★ 条件应用的可读报告（条数 + 外部注入总量 + 具名 reason）：随载荷走，读口/审计可核。
      payload.put("testConditions", conditionReport.toWireMap());
    }
    // ★★ P2：在既有键集之后**追加**六个顶层键 —— 顺序固定为 modes / classStructures /
    //   classPositions / classStandings / assetRules / liquidationPolicies。
    // ★★ P2：基础六表（mode/结构/位置/standing）后追加资产规则；清算政策与规则 id 同源派生 ⇒ 引用必然对得上。
    payload.put("modes", productionModeNodes());
    payload.put("classStructures", classStructureNodes());
    payload.put("classPositions", classPositionNodes());
    payload.put("classStandings", productionRuntimeClassStandings(entries));
    payload.put("assetRules", productionRuntimeAssetRuleNodes());
    payload.put("liquidationPolicies", productionRuntimeLiquidationPolicyNodes());
    // ★★ P11.7/D-024：可选顶层 merchantFirms（仅非空时发）。
    //   节点字段与 EconomyPayloads.parseMerchantFirms 的读取名逐字一致（唯一拼写点仍是各自读取处，不在这里
    //   发明第二套字段名）。
    if (merchantFirms != null && !merchantFirms.isEmpty()) {
      payload.put("merchantFirms", merchantFirmNodes(merchantFirms));
    }
    return ToolSupport.json(payload);
  }

  /**
   * ★★ <b>商号表 → 顶层 {@code merchantFirms[]} 节点</b>（键序 = 值的稳定对象序 = {@code LinkedHashMap} 插入序；逐条字段与
   * {@code EconomyPayloads.parseMerchantFirms} 的读取名逐字一致）：
   *
   * <pre>
   * {organizationId, tier, homeHex, homeIsCity, capacityPerRound, capacityUsedThisRound,
   *  serviceRadiusHex, ruralTradeCostPenaltyPerMille, lastFeeEarnedMilli, lastUpkeepMilli, lastProfitMilli}
   * </pre>
   */
  private static List<Map<String, Object>> merchantFirmNodes(
      Map<ProductionOrganizationId, MerchantFirm> merchantFirms) {
    List<Map<String, Object>> nodes = new ArrayList<>(merchantFirms.size());
    for (MerchantFirm firm : merchantFirms.values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("organizationId", firm.organizationId().value());
      node.put("tier", firm.tier().name());
      node.put("homeHex", firm.homeHex().toString());
      node.put("homeIsCity", firm.homeIsCity());
      node.put("capacityPerRound", firm.capacityPerRound());
      node.put("capacityUsedThisRound", firm.capacityUsedThisRound());
      node.put("serviceRadiusHex", firm.serviceRadiusHex());
      node.put("ruralTradeCostPenaltyPerMille", firm.ruralTradeCostPenaltyPerMille());
      node.put("lastFeeEarnedMilli", firm.lastFeeEarnedMilli());
      node.put("lastUpkeepMilli", firm.lastUpkeepMilli());
      node.put("lastProfitMilli", firm.lastProfitMilli());
      nodes.add(node);
    }
    return nodes;
  }

  /**
   * ★★ <b>默认生产方式目录 → {@code modes[]} 节点</b>：{@code {id,name,version,classStructureId}}。
   *
   * <p>键序 = {@code DefaultProductionModes.modes()} 的稳定声明序；每个键名与 {@code EconomyPayloads.parseModes}
   * 的读取名逐字一致（不在这里发明第二套字段名）。
   */
  private static List<Map<String, Object>> productionModeNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ProductionMode mode : DefaultProductionModes.modes().values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", mode.id().value());
      node.put("name", mode.name());
      node.put("version", mode.version());
      node.put("classStructureId", mode.classStructureId().value());
      nodes.add(node);
    }
    return nodes;
  }

  /**
   * ★★ <b>默认阶层结构 → {@code classStructures[]} 节点</b>：{@code
   * {id,modeId,positions:[...],defaultSharesPerMille:{...}}}。
   *
   * <p>{@code positions} 与下面的扁平 {@code classPositions[]} 由<b>同一个</b> {@link #classPositionNode} 生成
   * ⇒ 结构内位置与全局表逐字段同形，{@code EconomyData} 的“结构内位置 == 全局位置表”守卫自然成立。
   */
  private static List<Map<String, Object>> classStructureNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ClassStructure structure : DefaultProductionModes.classStructures().values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", structure.id().value());
      node.put("modeId", structure.modeId().value());
      List<Map<String, Object>> positions = new ArrayList<>();
      for (ClassPosition position : structure.positions().values()) {
        positions.add(classPositionNode(position));
      }
      node.put("positions", positions);
      Map<String, Object> shares = new LinkedHashMap<>();
      for (Map.Entry<ClassPositionId, Long> share : structure.defaultSharesPerMille().entrySet()) {
        shares.put(share.getKey().value(), share.getValue());
      }
      node.put("defaultSharesPerMille", shares);
      nodes.add(node);
    }
    return nodes;
  }

  /** ★ 默认全局位置表 → 扁平的 {@code classPositions[]} 节点（与结构内 {@code positions} 逐字段相同）。 */
  private static List<Map<String, Object>> classPositionNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ClassPosition position : DefaultProductionModes.classPositions().values()) {
      nodes.add(classPositionNode(position));
    }
    return nodes;
  }

  /**
   * ★ 一个阶层位置的线格式节点：{@code {id,modeId,name,relationToMeans,laborRole,surplusRole,ruleExtensions?}}。
   *
   * <p>{@code ruleExtensions} 只在非空时发出（P1 全空）；缺键 ⇒ 解析器给空表，不在这里多写一个空对象。
   */
  private static Map<String, Object> classPositionNode(ClassPosition position) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", position.id().value());
    node.put("modeId", position.modeId().value());
    node.put("name", position.name());
    node.put("relationToMeans", position.relationToMeans().name());
    node.put("laborRole", position.laborRole().name());
    node.put("surplusRole", position.surplusRole().name());
    if (!position.ruleExtensions().isEmpty()) {
      node.put("ruleExtensions", new LinkedHashMap<>(position.ruleExtensions()));
    }
    return node;
  }

  /**
   * ★★ <b>PRODUCTION_RUNTIME：{@code entries[].classes[]} → {@code classStandings[]}</b>。
   *
   * <p>每一条 {@code classes} 行写一条归属（人口 0 的行也在内，与 {@code EconomyData.classes} 的表一一对应）； {@code
   * originalPositionId == currentPositionId}、{@code retainedShares} 空表、压力/变迁数值 0、{@code reason} 取
   * {@link #PRODUCTION_RUNTIME_SEED_REASON}。slot → 默认位置由 {@link #productionRuntimePositionId} 唯一裁决；
   * 词表外槽位或目录缺位置都 fail-closed，<b>不静默少一条</b>。
   */
  private static List<Map<String, Object>> productionRuntimeClassStandings(
      List<Map<String, Object>> entries) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    Set<HouseholdId> seenHouseholds = new LinkedHashSet<>();
    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
      Object classesValue = entries.get(entryIndex).get("classes");
      if (!(classesValue instanceof List<?> rows)) {
        throw new IllegalStateException(
            "PRODUCTION_RUNTIME 的 entry[" + entryIndex + "].classes 必须是数组: " + classesValue);
      }
      for (Object rowValue : rows) {
        if (!(rowValue instanceof Map<?, ?> row)) {
          throw new IllegalStateException(
              "PRODUCTION_RUNTIME 的 entry[" + entryIndex + "].classes[] 每项必须是对象: " + rowValue);
        }
        Object householdValue = row.get("householdId");
        Object slotValue = row.get("slot");
        Object residenceValue = row.get("residence");
        if (!(householdValue instanceof String householdText)
            || !(slotValue instanceof String slot)
            || !(residenceValue instanceof String residenceText)) {
          throw new IllegalStateException(
              "PRODUCTION_RUNTIME 的家户行必须带字符串 householdId/slot/residence：entry="
                  + entryIndex
                  + "，行="
                  + rowValue);
        }
        HouseholdId householdId = HouseholdId.parse(householdText);
        if (!seenHouseholds.add(householdId)) {
          throw new IllegalStateException(
              "PRODUCTION_RUNTIME 的家户 id 在 entries 里重复（classStandings 必须一户一条）: " + householdId);
        }
        // ★★ 2026-10-07 GOV 非生产家户试点：official 槽位的 GOV 家户**没有**阶层位置/ClassStanding ——
        //   它不生产、不持资产、不出劳动；关账日 HouseholdClassRule 在"无可观察证据"时保留当前 view（official）。
        //   这里只做 id 去重（上面已做），不发 standing。
        if (SocialClassId.OFFICIAL.value().equals(slotValue)) {
          continue;
        }
        // ★★ P11.7/D-024：位置由 (residence, slot) 共同裁决 —— 同一个 slot 在城乡映射到不同 mode。
        ClassPositionId positionId =
            productionRuntimePositionId(ResidenceKind.parse(residenceText), slot);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("householdId", householdId.value());
        node.put("originalPositionId", positionId.value());
        node.put("currentPositionId", positionId.value());
        node.put("retainedShares", new LinkedHashMap<String, Long>());
        node.put("consecutiveDebtStressCycles", 0L);
        node.put("lastTransitionDay", 0L);
        node.put("reason", PRODUCTION_RUNTIME_SEED_REASON);
        nodes.add(node);
      }
    }
    return nodes;
  }

  /**
   * ★★ <b>PRODUCTION_RUNTIME：(residence, slot) → 默认位置 id（本类唯一裁决点）</b>。
   *
   * <pre>
   * 农村：poor_peasant → wage_farm.wage_laborer；middle/rich_peasant → family_farm.family_farmer；
   *       landlord → tenancy_fixed_kind.landlord
   * 城镇：poor_peasant → handicraft_workshop.artisan；middle_peasant → handicraft_workshop.workshop_owner；
   *       rich_peasant → merchant.self_employed；landlord → merchant.principal
   * 任意居住类型：{@code landless_laborer}（流民槽位，{@link #DISPLACED_SLOT}）→ displaced.laborer
   * </pre>
   *
   * <p>位置 id 只能经 {@link DefaultProductionModes#positionId} 查得 ⇒ 本类不拼第二份位置串；目录与映射漂开时具名抛，
   * 不静默少一条。词表外槽位（本类没有裁决过的值）同样 fail-closed。</p>
   */
  private static ClassPositionId productionRuntimePositionId(
      ResidenceKind residence, String slot) {
    if (residence == null) {
      throw new IllegalStateException("PRODUCTION_RUNTIME 的 residence 不得为 null");
    }
    if (slot == null || slot.isBlank()) {
      throw new IllegalStateException("PRODUCTION_RUNTIME 的社会阶层槽位不得为空白");
    }
    if (DISPLACED_SLOT.equals(slot)) {
      // ★ 流民无工作：位置是 displaced.laborer（D-023），与居住类型无关（城乡各有一条流民行）。
      return requiredDefaultPosition(
          DefaultProductionModes.DISPLACED, DefaultProductionModes.ROLE_DISPLACED_LABORER);
    }
    boolean rural = residence == ResidenceKind.RURAL;
    if (CLASS_IDS[0].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.WAGE_FARM, DefaultProductionModes.ROLE_WAGE_LABORER)
          : requiredDefaultPosition(
              DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_ARTISAN);
    }
    if (CLASS_IDS[1].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.FAMILY_FARM, DefaultProductionModes.ROLE_FAMILY_FARMER)
          : requiredDefaultPosition(
              DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_WORKSHOP_OWNER);
    }
    if (CLASS_IDS[2].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.FAMILY_FARM, DefaultProductionModes.ROLE_FAMILY_FARMER)
          : requiredDefaultPosition(
              DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_SELF_EMPLOYED);
    }
    if (CLASS_IDS[3].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.TENANCY_FIXED_KIND, DefaultProductionModes.ROLE_LANDLORD)
          : requiredDefaultPosition(
              DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_MERCHANT_PRINCIPAL);
    }
    throw new IllegalStateException("PRODUCTION_RUNTIME 未裁决的社会阶层槽位（拒绝臆造映射）: " + slot);
  }

  /** ★ 默认目录必须给得出这个位置；给不出 = 目录与 seeder 漂开，具名抛（不猜另一个位置）。 */
  private static ClassPositionId requiredDefaultPosition(ProductionModeId modeId, String role) {
    return DefaultProductionModes.positionId(modeId, role)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "默认生产方式目录缺少位置：mode="
                        + modeId.value()
                        + " role="
                        + role
                        + "（catalog 与 seeder 漂开）"));
  }


  static List<Map<String, Object>> governmentNodes(Map<GovernmentId, Government> governments) {
    List<Map<String, Object>> nodes = new ArrayList<>(governments.size());
    for (Government government : governments.values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", government.id().value());
      node.put("nationRef", government.nationRef());
      Map<String, Object> treasury = new LinkedHashMap<>();
      treasury.put("kind", government.treasury().kind().name());
      treasury.put("id", government.treasury().id());
      node.put("treasury", treasury);
      List<String> issuable = new ArrayList<>(government.issuable().size());
      for (CurrencyId currency : government.issuable()) {
        issuable.add(currency.value());
      }
      node.put("issuable", issuable);
      // ★ 旧行为逐字节不变：两个财政旋钮为 0 时不写键（旧载荷/旧档继续读 0）。
      if (government.seignioragePerCycle() > 0L) {
        node.put("seignioragePerCycle", government.seignioragePerCycle());
      }
      if (government.debtIssuePerCycle() > 0L) {
        node.put("debtIssuePerCycle", government.debtIssuePerCycle());
      }
      nodes.add(node);
    }
    return nodes;
  }

  /**
   * E3：{@code INITIAL_ENDOWMENT} 的逐币种聚合记录（id 含本 seed 的格集锚点 ⇒ 多国 seed 各自一条、不互相覆盖）。 记录总量 = 家户钱包 +
   * 经营者钱包（同一次 {@link #plan} 的同一份表）。
   */
  static List<Map<String, Object>> issuanceNodes(
      String mapId,
      List<Map<String, Object>> entries,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita) {
    return issuanceNodes(
        mapId,
        entries,
        genesisEndowment,
        "GM 代 GOV 创世初始禀赋："
            + genesisMoneyMilliPerCapita
            + " 毫/人（含经营者工资周转金；"
            + "总量按 actor.Seed 的家户+经营者钱包逐值汇总）");
  }

  /** ★★ R2a：同上，但 reason 由调用方给出（调用方自行给出可审计原因）。 */
  static List<Map<String, Object>> issuanceNodes(
      String mapId,
      List<Map<String, Object>> entries,
      Map<CurrencyId, Long> genesisEndowment,
      String reason) {
    if (genesisEndowment.isEmpty()) {
      return List.of();
    }
    String anchor = seedAnchor(entries);
    List<Map<String, Object>> nodes = new ArrayList<>(genesisEndowment.size());
    for (Map.Entry<CurrencyId, Long> amount : genesisEndowment.entrySet()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put(
          "id",
          new MoneyIssuanceId("endowment-" + mapId + "-" + anchor + "-" + amount.getKey().value())
              .value());
      node.put("governmentId", GENESIS_GOVERNMENT_ID.value());
      node.put("currency", amount.getKey().value());
      node.put("amount", amount.getValue());
      node.put("kind", "INITIAL_ENDOWMENT");
      node.put("reason", reason);
      nodes.add(node);
    }
    return nodes;
  }

  /** 本 seed 格集的最小 {@code (q,r)} 规范串（ENDOWMENT id 的确定性锚点；空格集取 {@code none}）。 */
  private static String seedAnchor(List<Map<String, Object>> entries) {
    String min = null;
    for (Map<String, Object> entry : entries) {
      Object q = entry.get("q");
      Object r = entry.get("r");
      if (!(q instanceof Number qNumber) || !(r instanceof Number rNumber)) {
        continue;
      }
      String key = qNumber.intValue() + "_" + rNumber.intValue();
      if (min == null) {
        min = key;
        continue;
      }
      String[] minParts = min.split("_", -1);
      int minQ = Integer.parseInt(minParts[0]);
      int minR = Integer.parseInt(minParts[1]);
      int qInt = qNumber.intValue();
      int rInt = rNumber.intValue();
      if (qInt < minQ || (qInt == minQ && rInt < minR)) {
        min = key;
      }
    }
    return min == null ? "none" : min;
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
      String mapId, PopulationSeeder.Seeding seeding, Function<HexCoord, String> terrainOf) {
    return plan(
        mapId, seeding, terrainOf, genesisMoneyMilliPerCapita(), FoundationProfile.PRODUCTION_RUNTIME);
  }

  /** ★ P1：纯函数主入口 + profile（初始禀赋取默认值）。 */
  static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      FoundationProfile profile) {
    return plan(mapId, seeding, terrainOf, genesisMoneyMilliPerCapita(), profile);
  }

  /**
   * ★★ E3：纯函数主入口 + 初始禀赋参数（{@code genesisMoneyMilliPerCapita}，毫/人；≥ 0）。 初始发行记录的总量按**家户钱包 +
   * 经营者钱包**逐币种汇总，与 actor.Seed 同一份表；profile 缺省 LEGACY。
   */
  static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita) {
    return plan(
        mapId, seeding, terrainOf, genesisMoneyMilliPerCapita, FoundationProfile.PRODUCTION_RUNTIME);
  }

  /**
   * ★★ <b>P2：纯函数主入口 + 初始禀赋 + profile</b>。{@link FoundationProfile#PRODUCTION_RUNTIME} 走完整生产
   * entries，并在 {@code Seed.economyPayload()} 追加生产方式目录/阶层/资产规则；政府家户/政府记录只在
   * {@link PopulationSeeder.Seeding#governmentHousehold()} 非空（SmallWorld 的 world-silver）时内置。
   */
  static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile) {
    return plan(
        mapId, seeding, terrainOf, genesisMoneyMilliPerCapita, profile, TestConditions.EMPTY);
  }

  /** ★★ P3：纯函数主入口 + 初始禀赋 + profile + 测试条件（空条件逐值等于 P1）。 */
  static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions) {
    if (genesisMoneyMilliPerCapita < 0L) {
      throw new IllegalArgumentException("初始禀赋（毫/人）不得为负: " + genesisMoneyMilliPerCapita);
    }
    if (profile == null) {
      throw new IllegalArgumentException("profile 不得为 null");
    }
    // ★ P3：缺省/空 conditions = P1 路径（不新增任何键、不碰任何账）。
    conditions = conditions == null ? TestConditions.EMPTY : conditions;
    // ★★ 2026-10-09：唯一路线 = production-runtime（完整生产 entries + 默认生产方式目录/资产规则；
    //   政府家户/政府记录只在 seeding.governmentHousehold() 非空时内置，见 planProductionRuntime/jsonOf）。
    return planProductionRuntime(
        mapId, seeding, terrainOf, genesisMoneyMilliPerCapita, profile, conditions);
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
   * EconomyVocabulary#RATION_MILLI_PER_PERSON}（与 {@code 旧结算引擎（R3a 已删除）} 每日需求同一个常量）， 价格换算的 {@code ÷
   * 1000} 与 {@code Market} 的货款公式**逐字同式**，不是本类另拍的数。
   *
   * <p>★★ <b>E3：初始禀赋就是一次显式 INITIAL_ENDOWMENT 发行</b>（GM 代 GOV，发行主体 = 世界级最小政府）。本方法仍只算 <b>每人金额</b>；总量由
   * {@link #genesisEndowmentOf(Map, List)} 对同一份钱包表求和，绝不另算一遍人口。默认值不变， 参数化入口见 {@link
   * #genesisMoney(long, long)} 与 {@code plan(..., long)}。
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
    return genesisMoney(population, genesisMoneyMilliPerCapita());
  }

  /** ★★ E3：一个家户的创世钱包（毫计价货币），每人金额可注入。金额 &lt; 0 或乘法溢出 ⇒ 当场抛； 金额 0 ⇒ 空钱包（与旧"只落正的量"逐值相同）。 */
  public static Map<CurrencyId, Long> genesisMoney(
      long population, long genesisMoneyMilliPerCapita) {
    if (population < 0L) {
      throw new IllegalArgumentException("population 不得为负: " + population);
    }
    if (genesisMoneyMilliPerCapita < 0L) {
      throw new IllegalArgumentException(
          "genesisMoneyMilliPerCapita 不得为负: " + genesisMoneyMilliPerCapita);
    }
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>();
    long amount = Math.multiplyExact(population, genesisMoneyMilliPerCapita);
    if (amount > 0L) {
      wallet.put(MARKET_NUMERAIRE, amount);
    }
    return wallet;
  }


  /**
   * E3：创世初始发行总量 = 家户钱包 + 经营者钱包的逐币种纯和（与 {@code actor.Seed} 的同一份表）。 只保留正数键；0 不造记录（"没有发行"与"发行 0"是两件事）。
   */
  static Map<CurrencyId, Long> genesisEndowmentOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney, List<OperatorSeed> operators) {
    Map<CurrencyId, Long> totals = new LinkedHashMap<>();
    for (Map<CurrencyId, Long> wallet : householdMoney.values()) {
      mergeWallet(totals, wallet);
    }
    for (OperatorSeed operator : operators) {
      mergeWallet(totals, operator.money());
    }
    return Collections.unmodifiableMap(totals);
  }

  private static void mergeWallet(Map<CurrencyId, Long> totals, Map<CurrencyId, Long> wallet) {
    for (Map.Entry<CurrencyId, Long> entry : wallet.entrySet()) {
      if (entry.getValue() == 0L) {
        continue;
      }
      totals.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
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
   * ★★ **这条算式不是新口径，是"改口径前的结算**逐字**复刻**：R2 之前 {@code 旧结算引擎（R3a 已删除）} 每天的 {@code laborToday} 就是
   * {@code Σ(行 laborMilli × participationPerMille ÷ 1000)}，而两个乘数都由本类写进载荷 ⇒
   * 本方法算出的数**恰好**等于改口径前每一天累加进 {@code Industry.cycleLaborMilli()} 的那个数。R2 把它改从"劳动配额表"取 （见 {@code
   * 旧结算引擎（R3a 已删除）.laborByActor}），故**配额之和必须逐值等于这个数** —— 这就是真档数字一个都不变的原因。
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
      // ★ M1.8：按参与率折算的唯一算法在 ClassRow（本处与结算/读口调的是同一个函数）—— 这里不再自己写
      //   `rowLabor * CLASS_LABOR_PER_MILLE / 1000`（公式同值，但两处写法就是两份会漂开的真相）。
      total +=
          ClassRow.participationAdjustedLaborMilli(
              rowLaborMilli(people[i], poolLabor, poolCount), CLASS_LABOR_PER_MILLE[i]);
    }
    return total;
  }

  /** 一个批次的**毛劳动**（千分劳动）：{@code 人数 × 年龄×性别系数} —— {@link #laborMilli(List)} 的单批次形态。 */
  static long grossLaborMilli(PopulationGroup group) {
    return group.count() * perCapitaLaborPerMille(group);
  }

  /**
   * ★★ <b>M1.8：一个批次按阶层参与率折扣后的每日可用劳动</b>（千分劳动/日）= 毛额 × {@link #CLASS_WEIGHTED_LABOR_PER_MILLE} ÷
   * 1000。
   *
   * <p>★★ <b>它是批次侧的唯一"可用"口径</b>：{@link #laborBudget}（配额上限）与 {@link #appendAllocation}（权重）都读它 ——
   * 两处不许各乘一次（同一份劳动最多折算一次）。逐行的折算见 {@link ClassRow#participationAdjustedLaborMilli()}：
   * 两条投影在池上的总量相等（同一批人、同一套阶层份额与参与率）。
   *
   * <p>★ <b>为什么不是把折算写进 {@code LaborSupply.grossLaborMilli}</b>：那个字段的契约口径是"毛额 = Σ(人数 × 年龄×性别系数)"
   * （{@code LaborSupply} 的类注），改它的语义会牵动 codec/往返与"已服役 + 已承诺 ≤ 毛额"那条不变量 ⇒ 本批<b>零契约改动</b>：
   * 状态里仍是毛额，折扣发生在"预算/权重"这一使用点上。{@code EconomyData} 的构造期守卫因此仍以毛额为更宽的一道网（见 {@link #laborBudget}）。
   */
  static long participationAdjustedLaborMilli(PopulationGroup group) {
    return ClassRow.participationAdjustedLaborMilli(
        grossLaborMilli(group), CLASS_WEIGHTED_LABOR_PER_MILLE);
  }

  /**
   * 配额 id 的**唯一拼写点**：{@code alloc-<产业 id>-<批次 id>}。
   *
   * <p>★ **确定性**：{@code (产业, 批次)} 的纯函数 ⇒ 同一对必然给出同一个 id（重放/分支可比），且同一对不会重复。 ★ **不含 {@code "."}**：产业
   * id 形如 {@code farm@0_0}、批次 id 形如 {@code rural:0_0:MALE:s0-1}，两者都不含点 ⇒ 地址 {@code
   * economy:<mapId>:allocation.<id>} 不会被 {@code AddressParser} 在第一个点处截断。
   */

  // ── 三个产业（**只留制度 + 配方 + 产能**；行已搬到 {@link #ruralCohort} / {@link #urbanCohort}）──────

  /** 本格**可耕地**（千分亩）= {@code 每格基准亩 × 地形系数} —— ★ **与人口无关**（没人种的格，地还在）。 */
  static long landMilliMuOf(String terrain) {
    return MU_PER_HEX * MILLI_MU_PER_MU * arablePerMilleOf(foodOf(terrain)) / 1000L;
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

  /** 一个主体引用的载荷节点（{@code {kind,id}}）——与 {@code EconomyPayloads.actorRef} 同一形状。 */
  private static Map<String, Object> actorNode(ActorRef actor) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("kind", actor.kind().name());
    node.put("id", actor.id());
    return node;
  }

  // ── 家户行：{@code (格, 居住类型, 阶层)}（H0.2）────────────────────────────────────────

  /**
   * ★★ **农村四行**（一层 = 该格农村家户的四个阶层）：人口 = 该格**农村批次**之和按 {@link #CLASS_SHARE_PER_MILLE} 切。
   *
   * <p>★ **它们同时是"农业的行"与"家庭纺织的行"**：农业与纺织是**同一批人的两份活**（900‰ + 100‰ 两条配额）， 故只有一本账。
   *
   * @param landMilliMu 本格可耕地（千分亩）—— 只用来推"本格农田一个周期的纤维副产"这份初始库存
   */
  /**
   * ★★ **农村四行 + 可选流民行**（P2-A：家户身份与人口都从 {@link PopulationSeeder.Seeding} 的 Social 侧切分读入，
   * 经济侧不再自己按阶层比例二次切人、也不再另造家户 id）。
   *
   * <p>★ **纤维的去处**：旧版按阶层份额落在那四行**纺织行**上，H0.2 起并入**农村四行**（同一批人的同一本账）。
   */
  private static List<Map<String, Object>> ruralCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      List<PopulationGroup> allPool,
      long landMilliMu,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      long genesisMoneyMilliPerCapita,
      PoolCohorts cohorts) {
    Map<String, long[]> goods = new LinkedHashMap<>();
    goods.put(COMMODITY_FIBER, splitByShares(fiberStockMilli(landMilliMu), CLASS_SHARE_PER_MILLE));
    return cohortGroup(
        hex,
        ResidenceKind.RURAL,
        pool,
        allPool,
        goods,
        locations,
        stocks,
        money,
        genesisMoneyMilliPerCapita,
        cohorts);
  }

  /**
   * ★★ **城镇四行**：人口 = 该格**城镇批次**之和按 {@link #CLASS_SHARE_PER_MILLE} 切（**逐值 = 旧版 craft 四行**）。
   *
   * <p>★ **原料库存的去处**：旧版 craft 四行里的纤维与铁按"该行作坊数 × 一座作坊一个周期的用量"持有， H0.2 起并入**城镇四行**（同一批人的同一本账）、H1
   * 起落进那四个家户的 {@code HouseholdInventory}（见 {@link #openingStock}） —— 逐值同式，只是行键不再带产业、且账本搬到了 actor 侧。
   *
   * @param workshops 本格作坊总数（= 城镇人口 ÷ {@link #URBAN_CAPITA_PER_WORKSHOP}）
   */
  /**
   * ★★ **城镇四行 + 可选流民行**：人口 = Social 侧 {@link PopulationSeeder.Seeding} 里该 (格, 城镇, 阶层) 家户的人数
   * （**逐值 = 旧版 craft 四行**）。
   *
   * <p>★ **原料库存的去处**：旧版 craft 四行里的纤维与铁按"该行作坊数 × 一座作坊一个周期的用量"持有， H0.2 起并入**城镇四行**（同一批人的同一本账）、H1
   * 起落进那四个家户的 {@code HouseholdInventory}（见 {@link #openingStock}） —— 逐值同式，只是行键不再带产业、且账本搬到了 actor 侧。
   *
   * @param workshops 本格作坊总数（= 城镇人口 ÷ {@link #URBAN_CAPITA_PER_WORKSHOP}）
   */
  private static List<Map<String, Object>> urbanCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      List<PopulationGroup> allPool,
      long workshops,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      long genesisMoneyMilliPerCapita,
      PoolCohorts cohorts) {
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
    return cohortGroup(
        hex,
        ResidenceKind.URBAN,
        pool,
        allPool,
        goods,
        locations,
        stocks,
        money,
        genesisMoneyMilliPerCapita,
        cohorts);
  }

  /**
   * ★★ <b>一个池的 Social 侧家户切分（P2-A 的 A1 接缝）</b>：Social 是家户身份与人口的真值源，经济侧只读。
   *
   * <pre>
   * raw = { PeopleLotId → {m,i,z,t,s,i}}         // 可选留痕，不进状态
   * classHouseholds[i] = householdByCohort[(hex, residence, CLASS_IDS[i])]
   * classPopulations[i] = Σ members(lot)         // 该阶层的批次人数之和（= Social 侧切分结果，不再二次切）
   * displaced           = householdByCohort[(hex, residence, landless_laborer)]（可能缺）
   * </pre>
   *
   * <p>★ 缺常规家户 ⇒ fail-closed（Social 与 Economy 的格集/居住类型必须一一对应）。
   */
  private static PoolCohorts poolCohorts(
      PopulationSeeder.Seeding seeding, HexCoord hex, ResidenceKind residence) {
    List<HouseholdId> classHouseholds = new ArrayList<>(CLASS_IDS.length);
    long[] classPopulations = new long[CLASS_IDS.length];
    for (int i = 0; i < CLASS_IDS.length; i++) {
      CohortKey key = new CohortKey(hex, residence, new SocialClassId(CLASS_IDS[i]));
      HouseholdId household =
          seeding
              .householdOfCohort(key)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Social 播种缺家户（P2-A 起经济侧只读 Social 的家户集）：" + key));
      classHouseholds.add(household);
      classPopulations[i] = seeding.populationOf(household);
    }
    HouseholdId displaced =
        seeding
            .householdOfCohort(new CohortKey(hex, residence, SocialClassId.LANDLESS_LABORER))
            .orElse(null);
    long displacedPopulation = displaced == null ? 0L : seeding.populationOf(displaced);
    return new PoolCohorts(residence, classHouseholds, classPopulations, displaced, displacedPopulation);
  }

  /** 一个池的 Social 家户切分（P2-A；见 {@link #poolCohorts}）。 */
  private record PoolCohorts(
      ResidenceKind residence,
      List<HouseholdId> classHouseholds,
      long[] classPopulations,
      HouseholdId displacedHousehold,
      long displacedPopulation) {}

  /** 从池里剔除流民批次（D-023：流民不进 laborSupply/allocations/资产；但成员份额用全池）。 */
  private static List<PopulationGroup> withoutDisplaced(
      List<PopulationGroup> pool, PoolCohorts cohorts) {
    if (cohorts.displacedHousehold() == null) {
      return pool;
    }
    List<PopulationGroup> out = new ArrayList<>(pool.size());
    for (PopulationGroup group : pool) {
      if (!cohorts.displacedHousehold().equals(householdOfGroup(group, cohorts))) {
        out.add(group);
      }
    }
    return out;
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
  /**
   * ★★ <b>一组家户行</b>（P2-A：身份/人口/成员份额都从 Social 侧的 {@link PoolCohorts} 读入，经济侧不二次切分）。
   *
   * <p>★ {@code goodsByClass} 是"逐商品的**逐阶层**存量表"（与 {@link #CLASS_IDS} 同序）：0 ⇒ 不落键（保持空商品表的纯形态）。
   *
   * <p>★★ <b>memberships 的口径（P2-A 的 A3）</b>：逐 lot 直接从 Social 的成员关系投影（{@code (lot, household,
   * count)}），<b>包括流民户的批次</b>——流民行不参与劳动/资产，但它的成员份额是真实的社会构成。这样
   * {@code Σ Membership.count == Σ ClassRow.population} 与"逐 lot Σcount == PopulationGroup.count"两条守卫同时自然成立。
   */
  private static List<Map<String, Object>> cohortGroup(
      HexCoord hex,
      ResidenceKind residence,
      List<PopulationGroup> pool,
      List<PopulationGroup> allPool,
      Map<String, long[]> goodsByClass,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      long genesisMoneyMilliPerCapita,
      PoolCohorts cohorts) {
    long poolPopulation = populationOf(pool);
    long[] people = cohorts.classPopulations();
    long displacedPopulation = cohorts.displacedPopulation();
    long poolLabor = laborMilli(pool);
    long poolCount = poolPopulation;
    List<Map<String, Object>> rows =
        new ArrayList<>(CLASS_IDS.length + (displacedPopulation > 0L ? 1 : 0));
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★★ **H1：这个家户的开缸库存 → actor 侧的账本**（键 = {@link HouseholdActors#of} 的那个家户身份）。
      //   ★ **空账也落键**（人口 0 ⇒ 余额全 0）：读口因此读得到"这个家户在这一格有一本账"（既定口径），
      //     而"账本为空"与"这一格没有这个家户"是两件事。
      HouseholdId key = cohorts.classHouseholds().get(i);
      locations.put(key, hex);
      stocks.put(key, openingStock(people[i], CLASS_IDS[i], goodsByClass, i));
      // ★★ H4：创世货币禀赋走**同一本账**（actor 侧的同一个 {@code HouseholdInventory}）—— 见 {@link
      // #genesisMoneyMilliPerCapita}。
      money.put(key, genesisMoney(people[i], genesisMoneyMilliPerCapita));
      rows.add(
          cohortRow(
              key,
              residence,
              CLASS_IDS[i],
              people[i],
              CLASS_LABOR_PER_MILLE[i],
              householdBudgetMilli(allPool, cohorts, key)));
    }
    if (displacedPopulation > 0L) {
      // ★★ D-023：流民没有工作、没有自动组织 —— 只落一条人口/需求行与它的家户账（库存/货币），不进劳动与资产表。
      HouseholdId displacedKey = cohorts.displacedHousehold();
      locations.put(displacedKey, hex);
      stocks.put(
          displacedKey,
          openingStock(
              displacedPopulation,
              initialRationDays(CLASS_IDS[0]),
              Map.of(),
              0));
      money.put(displacedKey, genesisMoney(displacedPopulation, genesisMoneyMilliPerCapita));
      rows.add(
          cohortRow(
              displacedKey,
              residence,
              DISPLACED_SLOT,
              displacedPopulation,
              0,
              householdBudgetMilli(allPool, cohorts, displacedKey)));
    }
    return rows;
  }

  /**
   * 该批次归哪个家户：按 Social 侧同一居住类型的四阶层 + 流民户里找**与该批次前缀/阶层标签一致**的那一个。
   *
   * <p>★ 播种器的命名把阶层短名编进了 cohort 段（{@code s0..s3} / {@code d}）——这里只按"批次的 cohort 短名"在
   * {@link PoolCohorts} 的候选户里选，不用再解析 id 的其它段。
   */
  private static HouseholdId householdOfGroup(PopulationGroup group, PoolCohorts cohorts) {
    String tag = PopulationLots.cohortOf(group.id());
    int dash = tag.indexOf('-');
    String stratumTag = dash < 0 ? tag : tag.substring(0, dash);
    if (PopulationSeeder.DISPLACED_TAG.equals(stratumTag)) {
      if (cohorts.displacedHousehold() == null) {
        throw new IllegalStateException("批次 " + group.id() + " 是流民批次，但该池没有流民家户");
      }
      return cohorts.displacedHousehold();
    }
    int slot;
    try {
      slot = Integer.parseInt(stratumTag.substring(1));
    } catch (RuntimeException e) {
      throw new IllegalStateException("批次 " + group.id() + " 的阶层短名非法: " + stratumTag, e);
    }
    if (slot < 0 || slot >= cohorts.classHouseholds().size()) {
      throw new IllegalStateException("批次 " + group.id() + " 的阶层短名越界: " + stratumTag);
    }
    return cohorts.classHouseholds().get(slot);
  }

  /** ★ {人口：{@code floor(池人口 × }{@link #DISPLACED_SEED_PER_MILLE}{@code ÷ 1000)}}——切不出 ≥1 人时返回 0。 */
  static long displacedSeedPopulation(long poolPopulation) {
    if (poolPopulation <= 0L) {
      return 0L;
    }
    return poolPopulation * DISPLACED_SEED_PER_MILLE / 1000L;
  }

  /**
   * 一条 {@code AssetShare} 载荷节点：{@code {industry, owner, operator, asset, quantity, kind}} —— 新形状显式给
   * owner/operator（租佃时两者不等），id 由 {@code EconomyPayloads.addAssetShare} 的确定性序号生成 （同一 {@code
   * (industry, asset, owner, operator, kind)} 从 0 递增）。
   */
  private static Map<String, Object> assetShareNode(
      String industry,
      String asset,
      ActorRef owner,
      ActorRef operator,
      AssetShare.RightKind kind,
      long quantity) {
    Map<String, Object> share = new LinkedHashMap<>();
    share.put("industry", industry);
    share.put("owner", actorNode(owner));
    share.put("operator", actorNode(operator));
    share.put("asset", asset);
    share.put("quantity", quantity);
    share.put("kind", kind.name());
    return share;
  }

  // ── R4-B.3a：主 unit + 家户副 unit 的确定性拆分 ─────────────────────────────────────────

  /**
   * ★★ <b>一个家户的创世库存</b>（H1；**行里不再有 {@code goods}** ⇒ 这是它的唯一拼写点）：口粮按阶层天数 （{@code 人口 × 该阶层天数} 天，见
   * {@link #rationMilli}）+ 逐行给出的其它商品（R3：农村行的纤维、城镇行的纤维与铁）。
   *
   * <p>★ <b>只落正的量</b>（0 ⇒ 不落键，保持"空账"的纯形态）—— 与旧版 {@code row.goods} 的口径逐字相同。
   */
  static Map<CommodityId, Long> openingStock(
      long population, String slot, Map<String, long[]> goodsByClass, int index) {
    return openingStock(population, initialRationDays(slot), goodsByClass, index);
  }

  /**
   * 上一条的**显式口粮天数**形态（流民行没有阶层槽位可查：它按最贫一档的天数走，见 {@link #DISPLACED_SLOT}）—— 库存的
   * "只落正的量 + 同一处算定"口径与上一条逐字相同，不另写第二份算式。
   */
  static Map<CommodityId, Long> openingStock(
      long population, int rationDays, Map<String, long[]> goodsByClass, int index) {
    Map<CommodityId, Long> stock = new LinkedHashMap<>();
    putStockIfPositive(
        stock, COMMODITY_GRAIN, EconomyVocabulary.cumulativeRationMilli(population, rationDays));
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
      HouseholdId householdId,
      ResidenceKind residence,
      String slot,
      long population,
      int participationPerMille,
      long laborBudgetMilli) {
    Map<String, Object> row = new LinkedHashMap<>();
    // ★★ **居住类型是身份的一维**（H0.1/R-N1-A）：少了它，同一格的农村贫农与城镇贫农会并成同一本账
    //   ⇒ "农村的余粮 + 城市的缺口"并到一起 ⇒ 城市不再饿死，但那不是因为修好了通道。
    //   ★ 字面量取自 {@link ResidenceKind#value()}（前缀/字面的唯一拼写点在契约里，本类不写第二份）。
    // ★★ S1：家户稳定身份随行发出（载荷把它带进 EconomyData；运行期不再从视图派生 actor id）。
    row.put("householdId", householdId.value());
    row.put("residence", residence.value());
    row.put("slot", slot);
    row.put("population", population);
    row.put("laborMilli", laborBudgetMilli);
    row.put("participationPerMille", participationPerMille);
    // ★★ **H1：这里没有 {@code goods} 键**（裁定 D3-C/K1）—— 商品库存的唯一持久真源是 actor 切片里该家户的
    //   {@code HouseholdInventory}，开缸余额由 {@link #openingStock} 一次算好、经 {@link HouseholdSeeder} 落成那本账。
    //   ★ 载荷里再写一份 = 同一事实的第二处拼写点，而且它会静默漂开（economy 侧已不再读它）。
    row.put("money", 0);
    // ★★ E4a：行里的债务引用不再由 seed 写（欠债只能由 runtime 借粮路径产生；空引用 = 缺键）。
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
   * 创世时钟（★ <b>具名缺口：创世年龄档仍未随 GM 配置走</b>）：{@link EconomySeeder} 的静态入口（如 {@code plan(...)}）没有
   * CalendarService——{@code WorldgenInitializeTool} 与 {@code RegionSeedPlan} 都是静态调用，
   * 装配链是静态的；本批不硬塞，留给后续工具面接线。当前创世的年龄档判定一律在 <b>tick 0</b> 折算，缺省 {@link
   * CalendarClock#julianDefault()}（儒略 1445-01-01）。
   *
   * <p>★ **私有静态 final**：{@link #ageBracketOf(long)} 每次调用都走它，不必每次新造一台时钟；它是无状态不可变的，共享安全。
   */
  private static final CalendarClock GENESIS_CLOCK = CalendarClock.julianDefault();

  /**
   * 年龄（天，**创世 tick 0 时的年龄**）落在哪一档（0-14 / 15-59 / 60+，与 {@link #AGE_SHARE_PER_MILLE} 同序）。
   *
   * <p>★ **档是现算的，不存档位**（设计稿 §三：年龄运行时只有逐日精度，"档间转移"因此不存在）。 ★ 超出末档上界一律归末档（年龄没有上界）。
   *
   * <p>★★ **边界与换算的唯一定义处都在 social**：本方法把创世 tick 0 的 ageDays 交给 {@link AgeBracket#of}（15/60
   * 是**整历法年**， 2/29 的生日惯例见 {@code CalendarAge}），本类不再另写 365 天的档界 —— 否则"批次按一套边造、劳动按另一套边折算、读口按第三套边显示"
   * 会被三张表悄悄漂开（本仓最忌"注释声称一致、其实不一致"）。
   */
  static int ageBracketOf(long ageDays) {
    if (ageDays < 0L) {
      throw new IllegalArgumentException("ageDays 不得为负: " + ageDays);
    }
    return AgeBracket.of(GENESIS_CLOCK.system(), GENESIS_CLOCK.dayNumberOfTick(0L), ageDays)
        .ordinal();
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
   * ★★ <b>P2-A §13.4：一个家户每 tick 的时间预算（毫小时）</b> = Σ_{属于它的批次} count ×
   * {@link LaborTimeTable#perPersonMilliHours}(年龄档, 性别)。年龄档 = {@link #ageBracketOf(long)}（与 D4 三档同序）。
   *
   * <p>★ 这是 {@code ClassRow.laborMilli} 在创世时的唯一算法；运行时每 tick 由协调器按同一张表从 Social 重算。
   */
  static long householdBudgetMilli(
      List<PopulationGroup> allPool, PoolCohorts cohorts, HouseholdId household) {
    long total = 0L;
    for (PopulationGroup group : allPool) {
      if (!householdOfGroup(group, cohorts).equals(household)) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(
                  group.count(),
                  LABOR_TIME_TABLE.perPersonMilliHours(
                      ageBracketOf(group.ageAtAnchorDays()), group.sex())));
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
   * <p>★★ **与 {@code 旧结算引擎（R3a 已删除）.allocate} 是同一个函数**（都走 {@link ProportionalSplit#byDenominator}
   * 且分母同为 Σ权重）⇒ "分配口径统一"是**可执行的事实**，由 {@code EconomyAllocationConsistencyTest} 跨模块逐值对拍。
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

  // ── P3：测试条件的应用（真实对价 / 守恒 / 审计）─────────────────────────────────────────

  /** 条件应用产物（不可变；无 conditions 时 = {@link #empty()}）。 */
  private record AppliedConditions(
      List<Map<String, Object>> debtContracts,
      List<Map<String, Object>> pledges,
      List<Map<String, Object>> extraMoneyIssuances,
      TestConditions.Report report) {

    static AppliedConditions empty() {
      return new AppliedConditions(List.of(), List.of(), List.of(), TestConditions.Report.EMPTY);
    }
  }

  /**
   * ★★ <b>P3：把测试条件应用到已建好的家户账 / 资产份额 / 经营者表上</b> —— <b>每一条都有真实对价</b>：
   *
   * <ol>
   *   <li>{@code extraGoods}/{@code extraMoney}：直接加到家户开缸库存/钱包，但这是**具名的外部注入** （{@code
   *       test-condition:external-endowment}）⇒ 货币注入同时发一条 {@code FISCAL_ISSUE} {@code
   *       MoneyIssuanceRecord}，<b>不并进</b> INITIAL_ENDOWMENT；
   *   <li>{@code InitialDebt}：真实从债权人库存/货币扣、加给债务人；不足 ⇒ 具名拒绝（不改 {@code moveInventory=false}）； {@code
   *       moveInventory=false} 只能消耗本批外部注入的**未承诺余量**（同一份注入不许被两条债重复当对价）；
   *   <li>{@code AssetSplit}：源 {@code OWNED} 份额减、同 industry/asset 的新 {@code OWNED}
   *       份额（owner=operator=目标家户）加， 逐 {@code (industry, asset)} 总量守恒（出口复核，不等即抛）；
   *   <li>{@code InitialPledge}：只引用本批真实合同与真实 {@code OWNED} 份额，校验 owner=债务人、Σ活跃质押 ≤ 份额数量、 {@code
   *       modeId} 已被本 profile 种下。
   * </ol>
   *
   * <p>★ 条件里的每一个家户都必须在<b>本次 seed 的格集</b>里（跨 seed 引用没有对侧，具名拒绝）。
   */
  private static AppliedConditions applyTestConditions(
      String mapId,
      List<Map<String, Object>> entries,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      TestConditions conditions) {
    if (conditions == null || conditions.isEmpty()) {
      return AppliedConditions.empty();
    }
    // ── ① 外部注入（具名；货币另发 FISCAL_ISSUE 审计）──────────────────────────────────────
    Map<CommodityId, Long> injectedGoodsTotal = new LinkedHashMap<>();
    Map<CurrencyId, Long> injectedMoneyTotal = new LinkedHashMap<>();
    // ★ "未承诺的注入余量"：moveInventory=false 的债只能消耗这份余量（逐条扣减 ⇒ 不重复计价）。
    Map<HouseholdId, Map<String, Long>> uncommittedInjection = new LinkedHashMap<>();
    List<Map<String, Object>> extraIssuances = new ArrayList<>();
    int issuanceIndex = 0;
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> byHousehold :
        conditions.extraGoodsByHousehold().entrySet()) {
      HouseholdId household = byHousehold.getKey();
      requireSeededHousehold(household, householdStocks, householdMoney, "extraGoodsByHousehold");
      Map<CommodityId, Long> stock = householdStocks.get(household);
      for (Map.Entry<CommodityId, Long> amount : byHousehold.getValue().entrySet()) {
        stock.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectedGoodsTotal.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectionCoverage(uncommittedInjection, household)
            .merge(DebtUnit.commodity(amount.getKey()).key(), amount.getValue(), Math::addExact);
      }
    }
    for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> byHousehold :
        conditions.extraMoneyByHousehold().entrySet()) {
      HouseholdId household = byHousehold.getKey();
      requireSeededHousehold(household, householdStocks, householdMoney, "extraMoneyByHousehold");
      Map<CurrencyId, Long> wallet = householdMoney.get(household);
      for (Map.Entry<CurrencyId, Long> amount : byHousehold.getValue().entrySet()) {
        wallet.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectedMoneyTotal.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectionCoverage(uncommittedInjection, household)
            .merge(DebtUnit.money(amount.getKey()).key(), amount.getValue(), Math::addExact);
        extraIssuances.add(
            externalIssuanceNode(
                mapId, entries, household, amount.getKey(), amount.getValue(), issuanceIndex++));
      }
    }
    // ── ② 初始债务（真实转账 / 真实外部对价）──────────────────────────────────────────────
    List<Map<String, Object>> debtNodes = new ArrayList<>();
    Map<DebtContractId, TestConditions.InitialDebt> debtsById = new LinkedHashMap<>();
    Set<DebtContractId> seenContracts = new LinkedHashSet<>();
    for (int i = 0; i < conditions.initialDebts().size(); i++) {
      TestConditions.InitialDebt debt = conditions.initialDebts().get(i);
      String where = "initialDebts[" + i + "]";
      requireSeededHousehold(debt.debtor(), householdStocks, householdMoney, where + ".debtor");
      requireSeededHousehold(debt.creditor(), householdStocks, householdMoney, where + ".creditor");
      if (debt.debtor().equals(debt.creditor())) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的债务人 == 债权人（自己欠自己不是债）: " + debt.debtor());
      }
      DebtContractId id =
          DebtContractId.idOf(debt.debtor(), debt.creditor(), debt.unit(), debt.terms());
      if (!seenContracts.add(id)) {
        throw new IllegalArgumentException(
            "test-condition: 同 (debtor, creditor, unit, terms) 在条件里出现多次 ⇒ 合同 id 重复，"
                + "不许静默合并或覆盖: "
                + id);
      }
      debtsById.put(id, debt);
      if (debt.moveInventory()) {
        transferForInitialDebt(debt, householdStocks, householdMoney);
      } else {
        consumeInjectedConsideration(debt, uncommittedInjection, where);
      }
      debtNodes.add(debtContractNode(id, debt));
    }
    // ── ③ 资产份额：先按 EconomyPayloads 的确定性序列口径给每条既有份额算出 id，再应用拆分 ──────
    Map<AssetShareId, ShareHandle> shares = new LinkedHashMap<>();
    Map<String, Long> sequences = new LinkedHashMap<>();
    Map<String, Long> conservedBefore = new LinkedHashMap<>();
    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
      for (Map<String, Object> node : assetSharesList(entries.get(entryIndex), entryIndex)) {
        ShareHandle handle = parseShareHandle(node, entryIndex);
        String sequenceKey = sequenceKeyOf(handle);
        long sequence = sequences.getOrDefault(sequenceKey, 0L);
        sequences.put(sequenceKey, sequence + 1L);
        AssetShareId id =
            AssetShare.idOf(
                handle.industry(),
                handle.asset(),
                handle.owner(),
                handle.operator(),
                handle.kind(),
                sequence);
        if (shares.putIfAbsent(id, handle) != null) {
          throw new IllegalStateException("资产份额确定性 id 冲突（plan 内部构造错误）: " + id);
        }
        conservedBefore.merge(assetTotalsKey(handle), handle.quantity(), Math::addExact);
      }
    }
    int splitShareCount = 0;
    for (int i = 0; i < conditions.assetSplits().size(); i++) {
      TestConditions.AssetSplit split = conditions.assetSplits().get(i);
      String where = "assetSplits[" + i + "]";
      requireSeededHousehold(
          split.targetHousehold(), householdStocks, householdMoney, where + ".targetHousehold");
      ShareHandle source = resolveSourceShare(split, shares, where);
      if (source.kind() != AssetShare.RightKind.OWNED) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的源份额必须是 OWNED（拆分只拆自有份额）: id 对应 " + source.kind());
      }
      long available = source.quantity();
      if (available < split.quantity()) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的源份额数量不足：现有=" + available + "，需要=" + split.quantity());
      }
      source.node().put("quantity", available - split.quantity());
      ActorRef target = HouseholdActors.of(split.targetHousehold());
      String sequenceKey =
          source.industry()
              + "|"
              + source.asset()
              + "|"
              + target
              + "|"
              + target
              + "|"
              + AssetShare.RightKind.OWNED;
      long sequence = sequences.getOrDefault(sequenceKey, 0L);
      sequences.put(sequenceKey, sequence + 1L);
      AssetShareId newId =
          AssetShare.idOf(
              source.industry(),
              source.asset(),
              target,
              target,
              AssetShare.RightKind.OWNED,
              sequence);
      Map<String, Object> newNode =
          assetShareNode(
              source.industry().value(),
              source.asset().name(),
              target,
              target,
              AssetShare.RightKind.OWNED,
              split.quantity());
      assetSharesList(entries.get(source.entryIndex()), source.entryIndex()).add(newNode);
      if (shares.putIfAbsent(
              newId,
              new ShareHandle(
                  source.entryIndex(),
                  newNode,
                  source.industry(),
                  source.asset(),
                  target,
                  target,
                  AssetShare.RightKind.OWNED))
          != null) {
        throw new IllegalStateException("拆分新建份额 id 冲突（plan 内部构造错误）: " + newId);
      }
      splitShareCount++;
    }
    Map<String, Long> conservedAfter = new LinkedHashMap<>();
    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
      for (Map<String, Object> node : assetSharesList(entries.get(entryIndex), entryIndex)) {
        ShareHandle handle = parseShareHandle(node, entryIndex);
        conservedAfter.merge(assetTotalsKey(handle), handle.quantity(), Math::addExact);
      }
    }
    if (!conservedBefore.equals(conservedAfter)) {
      throw new IllegalStateException(
          "test-condition: 资产份额拆分违反逐 (industry, asset) 总量守恒：before="
              + conservedBefore
              + " after="
              + conservedAfter);
    }
    // ── ④ 初始质押（真实合同 + 真实 OWNED 份额 + 债务人所有权）────────────────────────────
    List<Map<String, Object>> pledgeNodes = new ArrayList<>();
    Map<AssetShareId, Long> activePledged = new LinkedHashMap<>();
    for (int i = 0; i < conditions.initialPledges().size(); i++) {
      TestConditions.InitialPledge pledge = conditions.initialPledges().get(i);
      String where = "initialPledges[" + i + "]";
      TestConditions.InitialDebt backed = debtsById.get(pledge.debtContractId());
      if (backed == null) {
        throw new IllegalArgumentException(
            "test-condition: "
                + where
                + " 指名的债务合同不在本批 conditions 内（跨 seed / 凭空引用被拒）: "
                + pledge.debtContractId());
      }
      ShareHandle share = shares.get(pledge.assetShareId());
      if (share == null) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 指名的资产份额不在本次 seed 的份额表里: " + pledge.assetShareId());
      }
      if (share.kind() != AssetShare.RightKind.OWNED) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的质押份额必须是真实 OWNED 份额（当前 " + share.kind() + "）");
      }
      ActorRef debtorActor = HouseholdActors.of(backed.debtor());
      if (!share.owner().equals(debtorActor)) {
        throw new IllegalArgumentException(
            "test-condition: "
                + where
                + " 的质押份额 owner 必须 = 债务人的 OWNED 份额：owner="
                + share.owner()
                + "，debtor="
                + debtorActor);
      }
      long pledged = activePledged.merge(pledge.assetShareId(), pledge.quantity(), Math::addExact);
      if (pledged > share.quantity()) {
        throw new IllegalArgumentException(
            "test-condition: "
                + where
                + " 违反 Σ活跃质押 ≤ 份额数量：份额="
                + pledge.assetShareId()
                + " 质押合计="
                + pledged
                + "，份额数量="
                + share.quantity());
      }
      // ★★ P2：旧路径的 LegacyClassStructure.defaultModeId() 单值硬编码改成 DefaultProductionModes
      //   目录查询 —— 目录外的 mode 具名拒绝，不静默通过（目录是唯一 mode 来源）。
      if (DefaultProductionModes.mode(pledge.modeId()).isEmpty()) {
        throw new IllegalArgumentException(
            "test-condition: "
                + where
                + " 指名的 mode 不在 production-runtime 的默认生产方式目录里（拒绝凭空引用）: "
                + pledge.modeId()
                + "；合法 mode: "
                + DefaultProductionModes.modes().keySet());
      }
      pledgeNodes.add(pledgeNode(pledgeIdFor(i, pledge), pledge));
    }
    TestConditions.Report report =
        new TestConditions.Report(
            debtNodes.size(),
            pledgeNodes.size(),
            splitShareCount,
            injectedGoodsTotal,
            injectedMoneyTotal);
    return new AppliedConditions(
        Collections.unmodifiableList(debtNodes),
        Collections.unmodifiableList(pledgeNodes),
        Collections.unmodifiableList(extraIssuances),
        report);
  }

  /** 条件里指名的家户必须在本 seed 的账表里（stocks 与 money 两表同键，由 {@code cohortGroup} 保证）。 */
  private static void requireSeededHousehold(
      HouseholdId household,
      Map<HouseholdId, ?> householdStocks,
      Map<HouseholdId, ?> householdMoney,
      String where) {
    if (household == null
        || !householdStocks.containsKey(household)
        || !householdMoney.containsKey(household)) {
      throw new IllegalArgumentException(
          "test-condition: " + where + " 指名的家户不在本次 seed 的格集里（跨 seed 引用没有对侧，拒绝）: " + household);
    }
  }

  /** 未承诺的注入余量表（按家户惰性建表）。 */
  private static Map<String, Long> injectionCoverage(
      Map<HouseholdId, Map<String, Long>> coverage, HouseholdId household) {
    return coverage.computeIfAbsent(household, ignored -> new LinkedHashMap<>());
  }

  /** {@code moveInventory=false} 的对价校验：只能消耗本批外部注入的未承诺余量（逐条扣减 ⇒ 同一份注入不被两条债重复计价）。 */
  private static void consumeInjectedConsideration(
      TestConditions.InitialDebt debt,
      Map<HouseholdId, Map<String, Long>> uncommittedInjection,
      String where) {
    Map<String, Long> byUnit = injectionCoverage(uncommittedInjection, debt.debtor());
    String key = debt.unit().key();
    long available = byUnit.getOrDefault(key, 0L);
    if (available < debt.principal()) {
      throw new IllegalArgumentException(
          "test-condition: "
              + where
              + " moveInventory=false，但债务人的外部注入对价不足（不许无对价建条）：家户="
              + debt.debtor()
              + "，标的="
              + key
              + "，可用注入="
              + available
              + "，需要="
              + debt.principal());
    }
    long remaining = available - debt.principal();
    if (remaining == 0L) {
      byUnit.remove(key);
    } else {
      byUnit.put(key, remaining);
    }
  }

  /** 初始债务的真实转账：债权人的对应库存/货币真扣、债务人真加；不足 ⇒ 具名拒绝（不改 {@code moveInventory=false}）。 */
  private static void transferForInitialDebt(
      TestConditions.InitialDebt debt,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    if (debt.unit() instanceof DebtUnit.Commodity commodity) {
      CommodityId id = commodity.commodity();
      Map<CommodityId, Long> creditorStock = householdStocks.get(debt.creditor());
      long have = creditorStock.getOrDefault(id, 0L);
      if (have < debt.principal()) {
        throw new IllegalArgumentException(
            "test-condition: 债权人库存不足，拒绝生成无对价的初始债务：creditor="
                + debt.creditor()
                + "，商品="
                + id.value()
                + "，现有="
                + have
                + "，需要="
                + debt.principal());
      }
      putOrRemove(creditorStock, id, have - debt.principal());
      householdStocks.get(debt.debtor()).merge(id, debt.principal(), Math::addExact);
      return;
    }
    if (debt.unit() instanceof DebtUnit.Money money) {
      CurrencyId currency = money.currency();
      Map<CurrencyId, Long> creditorWallet = householdMoney.get(debt.creditor());
      long have = creditorWallet.getOrDefault(currency, 0L);
      if (have < debt.principal()) {
        throw new IllegalArgumentException(
            "test-condition: 债权人货币不足，拒绝生成无对价的初始债务：creditor="
                + debt.creditor()
                + "，币种="
                + currency.value()
                + "，现有="
                + have
                + "，需要="
                + debt.principal());
      }
      putOrRemove(creditorWallet, currency, have - debt.principal());
      householdMoney.get(debt.debtor()).merge(currency, debt.principal(), Math::addExact);
      return;
    }
    throw new IllegalStateException("未知 DebtUnit 变体: " + debt.unit());
  }

  /** 余额 0 ⇒ 删键（与"只落正的量"的纯形态一致），否则写回。 */
  private static <K> void putOrRemove(Map<K, Long> map, K key, long amount) {
    if (amount == 0L) {
      map.remove(key);
    } else {
      map.put(key, amount);
    }
  }

  /** 一条初始债务的 {@code debtContracts[]} 载荷节点（id 由四元组派生、openedDay=0、从未计息、NORMAL）。 */
  private static Map<String, Object> debtContractNode(
      DebtContractId id, TestConditions.InitialDebt debt) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id.value());
    node.put("debtor", debt.debtor().value());
    node.put("creditor", debt.creditor().value());
    Map<String, Object> unit = new LinkedHashMap<>();
    if (debt.unit() instanceof DebtUnit.Commodity commodity) {
      unit.put("kind", "commodity");
      unit.put("commodity", commodity.commodity().value());
    } else if (debt.unit() instanceof DebtUnit.Money money) {
      unit.put("kind", "money");
      unit.put("currency", money.currency().value());
    } else {
      throw new IllegalStateException("未知 DebtUnit 变体: " + debt.unit());
    }
    node.put("unit", unit);
    DebtTerms terms = debt.terms();
    Map<String, Object> termsNode = new LinkedHashMap<>();
    termsNode.put("interestRatePerMillePerCycle", terms.interestRatePerMillePerCycle());
    termsNode.put("interestTiming", terms.interestTiming().name());
    termsNode.put("repaymentRule", terms.repaymentRule().name());
    termsNode.put("monetaryConversion", terms.monetaryConversion().name());
    termsNode.put("defaultRemedy", terms.defaultRemedy().name());
    if (terms.dueCycle().isPresent()) {
      termsNode.put("dueCycle", terms.dueCycle().getAsLong());
    }
    if (terms.dueDay().isPresent()) {
      termsNode.put("dueDay", terms.dueDay().getAsLong());
    }
    node.put("terms", termsNode);
    node.put("principal", debt.principal());
    node.put("openedDay", 0L);
    // 尚未计息/无约定到期周期：显式 null（载荷解析口径：缺席/null ⇒ 空）。
    node.put("lastInterestDay", null);
    node.put("dueCycle", debt.dueCycle().isPresent() ? debt.dueCycle().getAsLong() : null);
    node.put("status", DebtStatus.NORMAL.name());
    return node;
  }

  /**
   * 外部注入货币的审计节点：{@link MoneyIssuanceKind#FISCAL_ISSUE}，reason 含 {@link
   * TestConditions#EXTERNAL_ENDOWMENT_REASON}，<b>绝不并进</b> INITIAL_ENDOWMENT。
   */
  private static Map<String, Object> externalIssuanceNode(
      String mapId,
      List<Map<String, Object>> entries,
      HouseholdId household,
      CurrencyId currency,
      long amount,
      int index) {
    String id =
        "testcondition-" + mapId + "-" + seedAnchor(entries) + "-" + index + "-" + currency.value();
    if (id.indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "test-condition: 外部注入的 MoneyIssuanceId 含 '|'（请改 mapId/币种）: " + id);
    }
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id);
    node.put("governmentId", GENESIS_GOVERNMENT_ID.value());
    node.put("day", 0L);
    node.put("period", FIRST_PERIOD);
    node.put("currency", currency.value());
    node.put("amount", amount);
    node.put("kind", MoneyIssuanceKind.FISCAL_ISSUE.name());
    node.put(
        "reason",
        TestConditions.EXTERNAL_ENDOWMENT_REASON
            + "（测试外部注入，不计入 INITIAL_ENDOWMENT）：家户="
            + household.value()
            + "，金额="
            + amount
            + " 毫最小币值");
    return node;
  }

  /** 一条 {@code pledges[]} 载荷节点（状态恒 ACTIVE；id 由条件序号 + 合同 + 份额确定性派生）。 */
  private static Map<String, Object> pledgeNode(PledgeId id, TestConditions.InitialPledge pledge) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id.value());
    node.put("debtContractId", pledge.debtContractId().value());
    node.put("assetShareId", pledge.assetShareId().value());
    node.put("quantity", pledge.quantity());
    node.put("modeId", pledge.modeId().value());
    node.put("priority", pledge.priority());
    node.put("status", Pledge.Status.ACTIVE.name());
    return node;
  }

  /** 质押 id：确定性 + 不含 {@code '.'}（地址截断）/{@code '|'}（分段符）。 */
  private static PledgeId pledgeIdFor(int index, TestConditions.InitialPledge pledge) {
    String value =
        "testcondition-pledge-"
            + index
            + "-"
            + pledge.debtContractId().value()
            + "-"
            + pledge.assetShareId().value();
    if (value.indexOf('.') >= 0 || value.indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "test-condition: 生成的 PledgeId 含 '.' 或 '|'（地址/分段冲突），请改用其它家户或份额: " + value);
    }
    return new PledgeId(value);
  }

  /** 一条既有资产份额节点的内存视图（node 可变：拆分只改它的 quantity）。 */
  private record ShareHandle(
      int entryIndex,
      Map<String, Object> node,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetShare.RightKind kind) {

    long quantity() {
      return ((Number) node.get("quantity")).longValue();
    }
  }

  /**
   * ★★ <b>与经济载荷解析器逐字同式的确定性 id 口径</b>：序列键 = {@code industry|asset|owner|operator|kind} （{@code
   * EconomyPayloads.addAssetShare} 的 {@code sequenceKey}），序号 = 该键在<b>整份载荷</b>里出现的第几条（从 0 起）。
   * 本类先在拆分前对全部既有份额算一遍，拆分新建的份额再按同一张计数器往后发 —— 于是本类算出的 id 与 economy 侧解析出的 id <b>逐值相同</b>（拆分创建的目标份额因此可被
   * {@code InitialPledge.assetShareId} 直接引用）。
   */
  private static String sequenceKeyOf(ShareHandle handle) {
    return handle.industry()
        + "|"
        + handle.asset()
        + "|"
        + handle.owner()
        + "|"
        + handle.operator()
        + "|"
        + handle.kind();
  }

  /** 守恒复核对账键：逐 {@code (industry, asset)}。 */
  private static String assetTotalsKey(ShareHandle handle) {
    return handle.industry().value() + "|" + handle.asset().name();
  }

  /** entry 的资产份额列表（plan 内部构造保证存在）。 */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> assetSharesList(
      Map<String, Object> entry, int entryIndex) {
    Object value = entry.get("assetShares");
    if (!(value instanceof List<?>)) {
      throw new IllegalStateException("entry[" + entryIndex + "] 缺 assetShares 列表（plan 内部构造错误）");
    }
    return (List<Map<String, Object>>) value;
  }

  private static ShareHandle parseShareHandle(Map<String, Object> node, int entryIndex) {
    Object industryNode = node.get("industry");
    Object assetNode = node.get("asset");
    Object kindNode = node.get("kind");
    Object ownerNode = node.get("owner");
    Object operatorNode = node.get("operator");
    Object quantityNode = node.get("quantity");
    if (!(industryNode instanceof String industry)
        || !(assetNode instanceof String asset)
        || !(kindNode instanceof String kind)
        || !(ownerNode instanceof Map<?, ?> owner)
        || !(operatorNode instanceof Map<?, ?> operator)
        || !(quantityNode instanceof Number)) {
      throw new IllegalStateException("资产份额节点形状非法（plan 内部构造错误）: " + node);
    }
    return new ShareHandle(
        entryIndex,
        node,
        IndustryId.parse(industry),
        AssetKind.valueOf(asset),
        parseActorNode(owner),
        parseActorNode(operator),
        AssetShare.RightKind.valueOf(kind));
  }

  private static ActorRef parseActorNode(Map<?, ?> node) {
    Object kind = node.get("kind");
    Object id = node.get("id");
    if (!(kind instanceof String kindText) || !(id instanceof String idText)) {
      throw new IllegalStateException("actor 节点形状非法（plan 内部构造错误）: " + node);
    }
    return ActorRef.parse(kindText, idText);
  }

  /** 拆分源解析：具体 id ⇒ 直查；查询 ⇒ 必须唯一命中一条 OWNED 份额（有歧义则具名拒绝）。 */
  private static ShareHandle resolveSourceShare(
      TestConditions.AssetSplit split, Map<AssetShareId, ShareHandle> shares, String where) {
    if (split.sourceAssetShareId().isPresent()) {
      AssetShareId id = split.sourceAssetShareId().get();
      ShareHandle handle = shares.get(id);
      if (handle == null) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 找不到 sourceAssetShareId（不在本次 seed 的份额表里）: " + id);
      }
      return handle;
    }
    IndustryId industry = split.industry().orElseThrow();
    AssetKind asset = split.asset().orElseThrow();
    ActorRef sourceOwner = split.sourceOwner().orElseThrow();
    List<ShareHandle> matches = new ArrayList<>();
    for (ShareHandle handle : shares.values()) {
      if (handle.kind() == AssetShare.RightKind.OWNED
          && handle.industry().equals(industry)
          && handle.asset() == asset
          && handle.owner().equals(sourceOwner)) {
        matches.add(handle);
      }
    }
    if (matches.isEmpty()) {
      throw new IllegalArgumentException(
          "test-condition: "
              + where
              + " 查询不到匹配的 OWNED 源份额：industry="
              + industry
              + "，asset="
              + asset
              + "，sourceOwner="
              + sourceOwner);
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "test-condition: "
              + where
              + " 的查询命中 "
              + matches.size()
              + " 条 OWNED 源份额（有歧义）⇒ 请改用 sourceAssetShareId");
    }
    return matches.get(0);
  }

  /**
   * ★★ <b>PRODUCTION_RUNTIME：P2 恢复的旧完整生产播种路径</b>（旧 {@code b243e3d9^} 的 LEGACY/COMPLETE 共同生产段）。
   *
   * <p>它是唯一生产路径：本方法逐格生成 {@code industries}（farm/weave/craft）、 {@code
   * units} / {@code assetShares} / {@code laborSupply} / {@code allocations} / {@code memberships}
   * / 经营者开缸账；生产权威 = entries 的完整生产结构 + 默认生产方式目录。
   *
   * <p>★ 载荷出口的 {@code modes} / {@code classStructures} / {@code classPositions} / {@code
   * classStandings} / {@code assetRules} / {@code liquidationPolicies} 由 {@link #jsonOf} 追加（mode 目录来自
   * {@code DefaultProductionModes}）；有政府引用（{@code seeding.governmentHousehold()}）时内置 GOV 家户与政府国库，
   * 否则只落一条不持户的创世审计主体（P2-C §13.7）。
   */
  private static Seed planProductionRuntime(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions) {
    Map<HexCoord, List<PopulationGroup>> ruralByHex = new LinkedHashMap<>();
    Map<HexCoord, List<PopulationGroup>> urbanByHex = new LinkedHashMap<>();
    for (PopulationGroup group : seeding.groups()) {
      Map<HexCoord, List<PopulationGroup>> target =
          PopulationLots.isUrban(group) ? urbanByHex : ruralByHex;
      // ★ S2：落点从家户取（批次身上没有 residence）。
      target.computeIfAbsent(seeding.locationOf(group.id()), hex -> new ArrayList<>()).add(group);
    }
    List<HexCoord> hexes = new ArrayList<>(ruralByHex.keySet());
    for (HexCoord hex : urbanByHex.keySet()) {
      if (!ruralByHex.containsKey(hex)) {
        hexes.add(hex); // 有城市却无农村人口的格也要有经济状态（否则那座城的人口凭空消失）
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));

    List<Map<String, Object>> entries = new ArrayList<>(hexes.size());
    Map<HouseholdId, Map<CommodityId, Long>> householdStocks = new LinkedHashMap<>();
    // ★ S1：家户 id → 账所在格（去重表：id 由 ofSeed 生成 ⇒ 同 (格,居住,阶层) 恒同一 id）。
    Map<HouseholdId, HexCoord> householdLocations = new LinkedHashMap<>();
    // ★★ H4：创世货币禀赋（裁定 K14）走**同一处接缝** —— 与 householdStocks 同一次循环算出、同一份交给
    //   {@link HouseholdSeeder}（两处各算一遍必然漂开）。★ 它是**初始条件**，不是发行：见 {@link #householdMoney}。
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    // ★★ H4：逐格市场的载荷（M1-A 每格单一计价货币 + 固定价）。本批逐格价格无差异 ⇒ 共享同一个不可变 {@link Market}。
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    // ★★ H5：逐格逐产业的**经营主体开缸账**（键序 = 产业生成序 = farm → weave → craft ⇒ 内容的纯函数）。
    List<OperatorSeed> operators = new ArrayList<>();
    // ★★ P11.7/D-024：逐城市格的商号表（键 = 组织 id；插入序 = hex 序 ⇒ 载荷逐值确定）。
    Map<ProductionOrganizationId, MerchantFirm> merchantFirms = new LinkedHashMap<>();
    // 商号本金主的位置是 merchant.principal（(residence=urban, slot=landlord) 的唯一裁决），全局只需算一次。
    ClassPositionId merchantPrincipalPosition =
        productionRuntimePositionId(ResidenceKind.URBAN, CLASS_IDS[LANDLORD_SLOT_INDEX]);
    for (HexCoord hex : hexes) {
      // ★★ P2-A：家户身份/人口从 Social 侧读入；劳动池要排除流民批次（D-023：流民没有工作），
      //   但成员份额投影要用**全部**批次（流民户也是真实的成员归属）。
      List<PopulationGroup> ruralAll = ruralByHex.getOrDefault(hex, List.of());
      List<PopulationGroup> urbanAll = urbanByHex.getOrDefault(hex, List.of());
      PoolCohorts ruralCohorts = poolCohorts(seeding, hex, ResidenceKind.RURAL);
      PoolCohorts urbanCohorts = poolCohorts(seeding, hex, ResidenceKind.URBAN);
      List<PopulationGroup> ruralPool = withoutDisplaced(ruralAll, ruralCohorts);
      List<PopulationGroup> urbanPool = withoutDisplaced(urbanAll, urbanCohorts);
      IndustryId farmId = IndustryHexKeys.id(FARM, hex.q(), hex.r());
      IndustryId craftId = IndustryHexKeys.id(CRAFT, hex.q(), hex.r());
      IndustryId weaveId = IndustryHexKeys.id(WEAVE, hex.q(), hex.r());
      // ★★ R3B.2：新载荷显式产生 unit —— 先建 plan（模板 + 经营者 + 产能），再逐 unit/份额落载荷。
      // ★★ **本格的产能与人口派生量**（H0.3：产能从"行"搬到"产业"，故它们在这里一次算好）：
      //   亩 = 地形系数决定（**与人口无关**：没人种的格，地还在）；织机/作坊 = 人口 ÷ 场景参数。
      long landMilliMu = landMilliMuOf(terrainOf.apply(hex));
      long looms = populationOf(ruralAll) / RURAL_CAPITA_PER_LOOM;
      long urbanPopulation = populationOf(urbanAll);
      long workshops = urbanPopulation / URBAN_CAPITA_PER_WORKSHOP;
      boolean hasRural = populationOf(ruralAll) > 0L;
      boolean hasCraft = urbanPopulation > 0L;
      // ★★ P11.7/D-024：城市格的商号本金主 = 该格城镇 landlord 家户（位置 = merchant.principal）。它的身份从
      //   Social 侧的 PoolCohorts 读入（P2-A：不再另拼 ofSeed），actor 拼写点走 HouseholdActors。
      HouseholdId merchantPrincipalHousehold =
          hasCraft ? urbanCohorts.classHouseholds().get(LANDLORD_SLOT_INDEX) : null;
      ActorRef merchantPrincipalActor =
          hasCraft ? HouseholdActors.of(merchantPrincipalHousehold) : null;
      List<IndustryPlan> plans = new ArrayList<>(hasCraft ? 4 : 3);
      // ★★ P2-A §13.3：庄园/作坊不是账户主体 —— 主 unit 的经营者改成**组织者家户**：
      //   · farm（feudal 自营庄园）⇒ 该格农村地主家户（{@code tenancy_fixed_kind.landlord} 的庄园主人）；
      //   · craft（handicraft 作坊）⇒ 该格城镇中农家户（{@code handicraft_workshop.workshop_owner}）。
      //   ★ 家户纺织（household）保持聚合主体：它是**多个家户共同经营**的 unit，账户主体 = unit 名下劳动家户的集合
      //     （见 HouseholdRouting）；trade 的经营者本来就是商号本金主家户。
      ActorRef farmOrganizer = HouseholdActors.of(ruralCohorts.classHouseholds().get(LANDLORD_SLOT_INDEX));
      ActorRef craftOrganizer =
          hasCraft
              ? HouseholdActors.of(urbanCohorts.classHouseholds().get(WORKSHOP_OWNER_SLOT_INDEX))
              : null;
      IndustryPlan farmPlanMain = agriculture(hex, landMilliMu).withOperator(farmOrganizer);
      plans.add(farmPlanMain);
      if (hasRural) {
        // ★★ R3（T4）：农村家庭纺织 —— 配方 FIBER + LABOR + TOOL → CLOTH，由**同一批农村人**承担（见 appendAllocation）。
        //   ★ 它**没有自己的阶层行**（H0.2 起织机住在本产业的 {@code capacity}，纤维住在农村四行）⇒ 只有"有农村人口"
        //     的格才建它（没有农村人口的格既无织机也无农村劳动配额 ⇒ 建出来是一具空壳）。
        plans.add(householdWeaving(hex, looms));
      }
      if (hasCraft) {
        plans.add(handicraft(hex, workshops).withOperator(craftOrganizer));
        // ★★ P11.7/D-024：城市 trade 产业 —— 制度 merchant、无商品产出、以 CATTLE 为运力资产；经营者 = 商号本金主。
        plans.add(trade(hex, merchantPrincipalActor));
      }
      // ★★ P2-A：主 unit 的显式关系 —— operator/residualOwner = 组织者家户，inputSupplier 仍指**原产业主体 actor**
      //   （{@code ToActor(org)} 在 supplierAccountsOf 里代理到 unit 名下劳动家户 ⇒ 播种期"谁出种/出料"逐字不变）。
      ProductionRelation farmMainRelation =
          organizerRelation(
              farmPlanMain,
              Set.of(ResidenceKind.RURAL),
              new ActorRef(ActorKind.ORGANIZATION, farmId.value()));
      ProductionRelation craftMainRelation =
          hasCraft
              ? organizerRelation(
                  planOf(plans, craftId),
                  Set.of(ResidenceKind.URBAN),
                  new ActorRef(ActorKind.ORGANIZATION, craftId.value()))
              : null;
      // ★★ P2-E：家户纺织主 unit 是**集体经营** unit（operator 是聚合主体，名下有多个劳动家户）。
      //   它必须显式带一条"无规则"关系：净产出已由 harvest 的 creditOutput 按劳动权重分给各家家户账；
      //   若沿用制度默认的 HOUSEHOLD 模板，模板的四个受方 cohort 与集体成员是同一批家户 ⇒ 规则结算会在
      //   成员之间铸出"付给自己"的转移（Transfer 两端不得相等，首个收获日当场抛）。
      ProductionRelation weaveMainRelation =
          hasRural ? collectiveRelation(planOf(plans, weaveId)) : null;
      List<Map<String, Object>> industries = new ArrayList<>(plans.size());
      for (IndustryPlan plan : plans) {
        industries.add(plan.payload());
      }
      // ★★ P2-A §13.3：庄园/作坊/商号**不再持账** ⇒ 这里不再播种任何经营者账户（{@code operators} 保持空表）。
      //   原先挂在经营者账上的东西逐项改记到组织者家户：
      //   · craft 的工具周转料 = **一个周期的工具用量 / 座** ⇒ 记进作坊主家户（下面与 classes 表同批合并）；
      //   · craft 的工资周转金（{@link #operatorWageReserveMilli}）⇒ 记进作坊主家户钱包；
      //   · farm 的种子/weave 的纤维本来就在家户账上（inputSupplier 走家户代理，逐值不变）。
      // ★★ **R2：该格的劳动供给与配额**（第三阶段设计稿 §四）—— 创世按"农村批次 → 农业（庄园）/ 城镇批次 →
      //   手工业（作坊）"初始化配额；**R3 起农村那 1000‰ 拆成"农业 900‰ + 家庭纺织 100‰"**（同一批人两条配额，
      //   总和仍 ≤ 该批次的可用劳动 —— 由 {@code EconomyData} 的构造期守卫判死）。
      List<Map<String, Object>> allocations = new ArrayList<>();
      // ★★ S1：成员份额（逐 lot → 家户）：由 cohortGroup 按 row population × pool 各批次人数权重拆出。
      IndustryPlan farmPlan = planOf(plans, farmId);
      if (hasRural) {
        IndustryPlan weavePlan = planOf(plans, weaveId);
        long ruralDaily =
            industryDailyLabor(
                ruralCohorts.classPopulations(), laborMilli(ruralPool), populationOf(ruralPool));
        long weaveQuota = ruralDaily * WEAVE_SHARE_PER_MILLE / 1000L;
        // ★ 残差归农业（"农业 = ruralDaily − weaveQuota"⇒ **要切的总量**之和恒等于本池日劳动（折扣后口径））。
        //   ★★ M1.8 起不等于"实际发出的配额之和"：预算按**逐批次折扣后的可用劳动**封顶，性别权重偏斜时某些批次会
        //      被压到上限 ⇒ 总配额可以**分不满**（合法状态；见 appendAllocation / laborBudget 的类注）。
        Map<PeopleLotId, Long> budget = laborBudget(ruralPool);
        appendAllocation(
            allocations,
            budget,
            ruralPool,
            hex,
            farmPlan.unitId(),
            farmPlan.operator(),
            FARM,
            ruralDaily - weaveQuota);
        appendAllocation(
            allocations,
            budget,
            ruralPool,
            hex,
            weavePlan.unitId(),
            weavePlan.operator(),
            ACTIVITY_WEAVE,
            weaveQuota);
      }
      if (hasCraft) {
        IndustryPlan craftPlan = planOf(plans, craftId);
        appendAllocation(
            allocations,
            laborBudget(urbanPool),
            urbanPool,
            hex,
            craftPlan.unitId(),
            craftPlan.operator(),
            CRAFT,
            industryDailyLabor(
                urbanCohorts.classPopulations(), laborMilli(urbanPool), populationOf(urbanPool)));
      }
      // ★★ **R4-B.3a：把上面的"整额配额 + 整份 capacity"确定性地拆成主 unit + 家户副 unit** ——
      //   劳动行已经按现有规则发完（逐值不动），这里只做"把已有的拆成两笔"：
      //   · 副 unit 合计拿 ⌊总量 × SECONDARY_PER_MILLE ÷ 1000⌋，主 unit 拿剩余；
      //   · 分不出 ≥ 一份 capacityPerUnit 的家户跳过（其劳动与份额都留在主 unit）；
      //   · Σ 份额逐 asset 不变、Σ 配额逐 (批次, 家户) 不变（见 splitIndustry 的守恒注）。
      //   ★ 必须在 allocations 发完之后做：候选家户 = "在本格配额里出现过的家户"，只有发完才知道。
      long landlordPopulation =
          hasRural ? ruralCohorts.classPopulations()[LANDLORD_SLOT_INDEX] : 0L;
      List<IndustrySplit> splits = new ArrayList<>(plans.size());
      for (IndustryPlan plan : plans) {
        splits.add(splitIndustry(plan, allocations, hex, landlordPopulation));
      }
      List<Map<String, Object>> units = new ArrayList<>();
      List<Map<String, Object>> assetShares = new ArrayList<>();
      List<Map<String, Object>> splitAllocations = new ArrayList<>();
      for (IndustrySplit split : splits) {
        IndustryPlan plan = split.plan();
        // 主 unit：载荷逐字段与 B.2 同形（operator 不变），capacity 变成"剩余"量（体现在 assetShares）。
        // ★★ P11.7/D-024：trade 的 unit 由解析器从 industry 节点的 legacy operator 合成（见 {@link #trade} 的
        //   兼容读口注释）—— 这里不重发，避免解析器判"同一 (产业, 经营者) 两处拼写"；CATTLE 份额仍照发。
        if (!TRADE.equals(plan.kind())) {
          ProductionRelation mainRelation =
              switch (plan.kind()) {
                case FARM -> farmMainRelation;
                case CRAFT -> craftMainRelation;
                case WEAVE -> weaveMainRelation;
                default -> null;
              };
          units.add(
              mainRelation == null
                  ? unitOf(plan)
                  : unitOf(plan, plan.operator(), mainRelation));
        }
        assetShares.addAll(assetSharesOf(plan, split.mainCapacity()));
        // 副 unit：owner/operator/kind 显式落 assetShares；relation 显式随 unit 发出。
        for (HouseholdUnit secondary : split.secondaries()) {
          units.add(unitOf(plan, secondary));
          for (Map.Entry<String, Long> asset : secondary.quantities().entrySet()) {
            assetShares.add(
                assetShareNode(
                    plan.id(),
                    asset.getKey(),
                    secondary.owner(),
                    secondary.operator(),
                    secondary.kind(),
                    asset.getValue()));
          }
        }
        // 劳动：主行减 moved、副 unit 新发一条；Σ 逐批次不变。
        for (LaborMove move : split.laborMoves()) {
          long laborMilli = ((Number) move.row().get("laborMilli")).longValue();
          move.row().put("laborMilli", laborMilli - move.moved());
          splitAllocations.add(secondaryAllocation(plan, move));
        }
      }
      allocations.addAll(splitAllocations);
      // ★★ P11.7/D-024：城市格恰一条商号 —— 组织 id 必须与 EconomyOrganizationSettlement 自动组织将创建的
      //   ProductionOrganizationId 逐字一致（同走 ProductionOrganizationId.idOf(merchant, principalPosition,
      //   merchantPrincipalHousehold, hexKey)）；tier/capacity 都是具名 GM 默认。
      if (hasCraft) {
        ProductionOrganizationId merchantOrgId =
            ProductionOrganizationId.idOf(
                DefaultProductionModes.MERCHANT,
                merchantPrincipalPosition,
                merchantPrincipalHousehold,
                IndustryHexKeys.hexKey(hex.q(), hex.r()));
        merchantFirms.put(merchantOrgId, merchantFirm(hex, merchantOrgId));
      }
      // ★★ **H0.2：本格的阶层行 = 两组四行（家户）** —— 行的身份是 {@code (格, 居住类型, 阶层)}，
      //   **不再挂在任何产业下**（产业只留"制度 + 配方 + 产能"）。这与"行 = 家户、产业 = 生产活动"的分工一一对应：
      //   一格的农村四行是**同一批农村人**，他们既供给农业（900‰）、也供给家庭纺织（100‰）；城镇四行同理只供给作坊。
      //   ★ **两组恒在**（即使某池人口为 0）：格集本身由批次落点决定，而"这一格有没有城镇家户"是一件事、
      //     "这一格此刻有没有城镇人口"是另一件事（H1 的家户 actor 按 格 × 居住 × 阶层 播，形状必须与行集一致）。
      //     人口为 0 的行是**合法的空账**（人口/劳动/需求全 0），不是噪声：它是"这一格有这个家户、只是没人"。
      List<Map<String, Object>> classes = new ArrayList<>(2 * CLASS_IDS.length);
      // ★★ **H1：开缸库存的去处 = 家户账**（actor 侧的 {@code HouseholdInventory}）。本方法把它**一次算好**并交回
      //   （{@code Seed.householdStocks}），载荷里的阶层行**不再带 {@code goods}** —— 行里没有商品这件事
      //   在"一本账"的判据（守恒式无 ΔΣRowGoods）里是必须的。
      //   ★★ H4：**创世货币禀赋与它同源**（同一处循环、同一份人口口径）⇒ 商品与货币两本账在同一次 plan 里算定。
      classes.addAll(
          ruralCohort(
              hex,
              ruralPool,
              ruralAll,
              landMilliMu,
              householdLocations,
              householdStocks,
              householdMoney,
              genesisMoneyMilliPerCapita,
              ruralCohorts));
      classes.addAll(
          urbanCohort(
              hex,
              urbanPool,
              urbanAll,
              workshops,
              householdLocations,
              householdStocks,
              householdMoney,
              genesisMoneyMilliPerCapita,
              urbanCohorts));
      if (hasCraft) {
        // ★★ P2-A §13.3：作坊主家户承接原"作坊经营者账"的周转料与工资周转金（同一格、同一户，账只有一本）。
        HouseholdId workshopOwner = urbanCohorts.classHouseholds().get(WORKSHOP_OWNER_SLOT_INDEX);
        Map<CommodityId, Long> tools =
            new LinkedHashMap<>(householdStocks.getOrDefault(workshopOwner, Map.of()));
        tools.merge(
            new CommodityId(COMMODITY_TOOL),
            Math.multiplyExact(workshops, toolPerWorkshopMilli()),
            Math::addExact);
        householdStocks.put(workshopOwner, tools);
        long wageReserve =
            operatorWageReserveMilli(
                REGIME_HANDICRAFT, craftId, new ActorRef(ActorKind.ORGANIZATION, craftId.value()));
        if (wageReserve > 0L) {
          Map<CurrencyId, Long> wallet =
              new LinkedHashMap<>(householdMoney.getOrDefault(workshopOwner, Map.of()));
          wallet.merge(MARKET_NUMERAIRE, wageReserve, Math::addExact);
          householdMoney.put(workshopOwner, wallet);
        }
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("q", hex.q());
      entry.put("r", hex.r());
      entry.put("industries", industries);
      entry.put("classes", classes);
      entry.put("allocations", allocations);
      // ★★ S1/R3B.2：本格各产业的资产份额由 plan 显式发出（容量整额 OWNED 给 unit.operator）。
      entry.put("assetShares", assetShares);
      entry.put("units", units);
      entries.add(entry);
      // ★★ H4：本格的市场（M1-A：每格一个计价货币 + 一张价表）。★ **有 entry 才有市场** ——
      //   "这一格没有市场"（格不在本表的键集里）是合法状态，不是缺数据。
      markets.put(hex, MARKET_FACTORY);
    }
    // ★★ P2-C §13.7：政府家户**按调用方给出的政府引用稳定建户**（Social 侧已建好，经济侧只读）。
    //   给了引用（世界级 demo / GOV 单位）⇒ 追加该家户的空行+空账；没给（多国/多省的普通 seed）⇒
    //   本次播种不产生政府家户，也不产生"先到者胜"的世界政府 —— 单位政府由
    //   economy.RegisterGovernment + actor.EnsureHouseholdAccount 按 GOV 单位稳定 id 建。
    Optional<HouseholdId> governmentHousehold = seeding.governmentHousehold();
    if (governmentHousehold.isPresent()) {
      seedGovernmentHousehold(
          entries,
          seeding.households(),
          governmentHousehold.get(),
          householdLocations,
          householdStocks,
          householdMoney);
    }
    // ★★ S1.4 出口自检：tick0 seed 是"人工造份额"的唯一入口 ⇒ 这里逐 lot 对账，不等就播不出去（fail-closed）。
    //   给了政府家户 ⇒ 国库 = 它的账户，seigniorage/debtIssue 取具名 GM 默认值（demo 世界才有铸币/发债）；
    //   没给 ⇒ 只落一条**不持户的创世审计主体**（world-silver 的 legacy 形状）给 INITIAL_ENDOWMENT 归属，
    //   不声称任何家户/账户，也不参与铸币/发债（旋钮恒 0）。
    Map<GovernmentId, Government> governments =
        governmentHousehold
            .map(hh -> Map.of(GENESIS_GOVERNMENT_ID, governmentHouseholdGovernment(hh)))
            .orElseGet(() -> Map.of(GENESIS_GOVERNMENT_ID, genesisAuditGovernment()));
    // ★★ P3：INITIAL_ENDOWMENT 的总量取**条件注入之前**的家户+经营者钱包 —— 外部注入的货币走独立
    //   FISCAL_ISSUE 审计（见 applyTestConditions），绝不混进"每人禀赋"这条记录。
    Map<CurrencyId, Long> genesisEndowment = genesisEndowmentOf(householdMoney, operators);
    AppliedConditions applied =
        applyTestConditions(mapId, entries, householdStocks, householdMoney, conditions);
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "event=ECONOMY_SEED profile={} mapId={} entries={} markets={} householdStocks={} householdMoney={} operators={} debtContracts={} pledges={} extraMoneyIssuances={} merchantFirms={} genesisEndowment={}",
          profile,
          mapId,
          entries.size(),
          markets.size(),
          householdStocks.size(),
          householdMoney.size(),
          operators.size(),
          applied.debtContracts().size(),
          applied.pledges().size(),
          applied.extraMoneyIssuances().size(),
          merchantFirms.size(),
          genesisEndowment);
    }
    return new Seed(
        mapId,
        entries,
        markets,
        householdLocations,
        householdStocks,
        householdMoney,
        operators,
        governments,
        genesisEndowment,
        genesisMoneyMilliPerCapita,
        profile,
        applied.debtContracts(),
        applied.pledges(),
        applied.extraMoneyIssuances(),
        applied.report(),
        merchantFirms);
  }

  // ── 2026-10-07 GOV 非生产家户试点：种子形状 ───────────────────────────────────────────────

  /**
   * ★★ <b>把 GOV 家户追加进生产运行时 seed</b>：在 Social 播种已建好的 GOV 家户（人口 0、{@code slot=official}，
   * 见 {@link PopulationSeeder}）所在格加一条行，并在 {@code householdLocations}/{@code householdStocks}/{@code householdMoney}
   * 三张表里给它一本空账。
   *
   * <p>★★ <b>P2-A：身份/落点由 Social 的 {@link PopulationSeeder.Seeding#governmentHousehold()} 给出</b>（经济侧只读，
   * 不再自己挑格、另拼 id）。它<b>不</b>进 laborSupply/allocations/memberships/units/assetShares/merchantFirms —— 不生产、不出劳动、不持资产。
   * 没有 ClassStanding（{@code productionRuntimeClassStandings} 对 official 槽位显式跳过）：关账日
   * HouseholdClassRule 在无可观察证据时保留当前 view。
   */
  private static HouseholdId seedGovernmentHousehold(
      List<Map<String, Object>> entries,
      List<Household> socialHouseholds,
      HouseholdId householdId,
      Map<HouseholdId, HexCoord> householdLocations,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    HexCoord hex = null;
    for (Household household : socialHouseholds) {
      if (!household.id().equals(householdId)) {
        continue;
      }
      if (household.location() instanceof HouseholdLocation.Hex at) {
        hex = at.hex();
      }
      break;
    }
    if (hex == null) {
      throw new IllegalStateException(
          "GOV 家户不在 Social 播种的家户集里（P2-A 起经济侧只读 Social）：" + householdId);
    }
    householdLocations.put(householdId, hex);
    householdStocks.put(householdId, Map.of());
    householdMoney.put(householdId, Map.of());

    Map<String, Object> row = governmentClassRow(householdId);
    boolean added = false;
    for (Map<String, Object> entry : entries) {
      int q = ((Number) entry.get("q")).intValue();
      int r = ((Number) entry.get("r")).intValue();
      if (q != hex.q() || r != hex.r()) {
        continue;
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> classes = (List<Map<String, Object>>) entry.get("classes");
      classes.add(row);
      added = true;
      break;
    }
    if (!added) {
      throw new IllegalStateException(
          "GOV 家户找不到落点 entry（seed 内部不一致）：hex=" + hex + " entries=" + entries.size());
    }
    return householdId;
  }

  /** GOV 家户行：population/labor/participation 全 0、无需求；身份仍由 {@link HouseholdId} 稳定承载。 */
  private static Map<String, Object> governmentClassRow(HouseholdId householdId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("householdId", householdId.value());
    row.put("residence", ResidenceKind.URBAN.value());
    row.put("slot", SocialClassId.OFFICIAL.value());
    row.put("population", 0L);
    row.put("laborMilli", 0L);
    row.put("participationPerMille", 0);
    row.put("money", 0L);
    row.put("naturalNeeds", Map.of());
    row.put("effectiveDemand", Map.of());
    return row;
  }

  /** GOV 家户政府的国库指向它的家户账户；周期铸币/发债量取本类的两个具名 GM 默认值（只在 demo 世界有政府家户时调用）。 */
  private static Government governmentHouseholdGovernment(HouseholdId governmentHousehold) {
    Objects.requireNonNull(governmentHousehold, "governmentHousehold");
    return new Government(
        GENESIS_GOVERNMENT_ID,
        GENESIS_GOVERNMENT_NATION_REF,
        HouseholdActors.of(governmentHousehold),
        Set.of(MARKET_NUMERAIRE),
        GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI,
        GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI);
  }

  /**
   * ★★ <b>P2-C：没有政府家户的 seed 所用的"创世审计主体"</b>（多国/多省播种的普通路径）。
   *
   * <p>它<b>不持户、不持账、不发生额</b>：国库是 legacy 形状的 {@code GOVERNMENT} actor（不是家户），
   * {@code issuable} 为空、两个财政旋钮恒 0。它存在的唯一理由是 {@code INITIAL_ENDOWMENT} 发行记录必须有归属
   * （{@code MoneyIssuanceRecord.governmentId} 必须存在于 {@code governments}）；每次 seed 生成的这份记录逐值相同，
   * 因此多国重复播种是幂等叠加，而不是"第一份赢"。
   *
   * <p>真正的政府（有家户国库、可铸币/发债/入市/生产）由 GOV 单位路径
   * {@code economy.RegisterGovernment} 按 GOV 单位稳定 id 建立。
   */
  private static Government genesisAuditGovernment() {
    return new Government(
        GENESIS_GOVERNMENT_ID,
        GENESIS_GOVERNMENT_NATION_REF,
        new ActorRef(ActorKind.GOVERNMENT, GENESIS_GOVERNMENT_ID.value()),
        Set.of(),
        0L,
        0L);
  }

  // ── P2：PRODUCTION_RUNTIME 的资产规则 / 清算政策（DefaultProductionModes 是唯一 mode 来源）──────────

  /** PRODUCTION_RUNTIME 基线覆盖的生产资料种类（P11.7/D-024 起加入商号运力 SHIP/CATTLE）。 */
  private static final List<AssetKind> PRODUCTION_RUNTIME_ASSET_KINDS =
      List.of(
          AssetKind.LAND, AssetKind.TOOL, AssetKind.WORKSHOP, AssetKind.SHIP, AssetKind.CATTLE);

  /** P2 基线资产规则的默认租金分成率（‰；沿用旧 COMPLETE 地基的 300‰ 制度量级）。 */
  private static final int PRODUCTION_RUNTIME_RENT_SHARE_PER_MILLE = 300;

  /**
   * ★★ <b>PRODUCTION_RUNTIME：{@code assetRules[]} 节点</b>：{@code DefaultProductionModes} 的<b>每个
   * mode</b> × {@link #PRODUCTION_RUNTIME_ASSET_KINDS}（LAND/TOOL/WORKSHOP/SHIP/CATTLE）。
   *
   * <p>★★ <b>id 是派生值、不是手写第二份身份</b>：逐条经 {@link AssetRuleId#idOf(ProductionModeId, AssetKind)} 生成（与
   * {@code EconomyPayloads.parseAssetRules} 的判据同一处），并有 {@code idMatchesIdentity} 的同一条 {@code (mode,
   * assetKind)} 唯一性。{@code rentRule}/{@code transferRule} 的形状与旧 COMPLETE 地基逐字段同源： LAND
   * 分成粮、TOOL/WORKSHOP 分成布；转移需所有权人同意、不许转租。
   *
   * <p>★ 键序 = mode 目录序 × {@link #PRODUCTION_RUNTIME_ASSET_KINDS} 声明序；纯函数、可复现。
   */
  static List<Map<String, Object>> productionRuntimeAssetRuleNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ProductionMode mode : DefaultProductionModes.modes().values()) {
      for (AssetKind assetKind : PRODUCTION_RUNTIME_ASSET_KINDS) {
        AssetRuleId ruleId = AssetRuleId.idOf(mode.id(), assetKind);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", ruleId.value());
        node.put("modeId", mode.id().value());
        node.put("assetKind", assetKind.name());
        node.put("isCoreMeans", true);
        node.put("pledgeable", true);
        // LAND/WORKSHOP = 1、TOOL = 2：土地/作坊先于工具处置（旧 COMPLETE 地基的显式制度参数）。
        node.put("liquidationPriority", assetKind == AssetKind.TOOL ? 2 : 1);
        node.put("rentRule", productionRuntimeRentRuleNode(assetKind));
        node.put("transferRule", productionRuntimeTransferRuleNode());
        nodes.add(node);
      }
    }
    return nodes;
  }

  /** 一条资产规则的租金模板：产出分成 300‰；LAND 分成粮，其余（TOOL/WORKSHOP/SHIP/CATTLE）分成布。 */
  private static Map<String, Object> productionRuntimeRentRuleNode(AssetKind assetKind) {
    String commodity = assetKind == AssetKind.LAND ? COMMODITY_GRAIN : COMMODITY_CLOTH;
    Map<String, Object> leg = new LinkedHashMap<>();
    leg.put("kind", RentRule.RentType.SHARE.name());
    leg.put("ratePerMille", PRODUCTION_RUNTIME_RENT_SHARE_PER_MILLE);
    leg.put("fixedAmount", 0L);
    leg.put("commodity", commodity);
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("type", RentRule.RentType.SHARE.name());
    rule.put("priority", 10);
    rule.put("legs", List.of(leg));
    return rule;
  }

  /** 一条资产规则的转移模板：可转移、需所有权人同意、不许转租（旧 COMPLETE 地基的三个制度布尔）。 */
  private static Map<String, Object> productionRuntimeTransferRuleNode() {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("transferable", true);
    node.put("requiresOwnerConsent", true);
    node.put("allowSublease", false);
    return node;
  }

  /**
   * ★★ <b>PRODUCTION_RUNTIME：{@code liquidationPolicies[]} 节点</b>：与 {@link
   * #productionRuntimeAssetRuleNodes()} 同一次 {@code (mode, assetKind)} 循环派生 ⇒ 每条政策的 {@code ruleId}
   * 都指向本载荷真实发出的 {@code AssetRule}（引用完整性构造性成立，不伪造无规则的政策）。
   *
   * <p>★ 数值沿用旧 COMPLETE 地基：每次最多处置 500‰、保护量 1000 单位、POLICY 制度价 4 毫/单位、先给债权人。
   */
  static List<Map<String, Object>> productionRuntimeLiquidationPolicyNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ProductionMode mode : DefaultProductionModes.modes().values()) {
      for (AssetKind assetKind : PRODUCTION_RUNTIME_ASSET_KINDS) {
        AssetRuleId ruleId = AssetRuleId.idOf(mode.id(), assetKind);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("ruleId", ruleId.value());
        node.put("maxLiquidatePerMille", 500);
        node.put("protectedReserve", 1000L);
        node.put("priceSource", LiquidationPolicy.PriceSource.POLICY.name());
        node.put("policyValuePerUnitMilli", 4L);
        node.put("recipientRule", LiquidationPolicy.RecipientRule.CREDITOR_FIRST.name());
        nodes.add(node);
      }
    }
    return nodes;
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
            new RegimeId(regime),
            ProductionUnitId.idOf(id, owner),
            id,
            owner,
            Set.of(ResidenceKind.URBAN));
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
   * ★★ **给一个产业发配额**（R2；R3 起按活动加性别权重；M1.8 起按**折扣后的可用劳动**加权）：把 {@code total} 按各批次的 **加权可用劳动**成比例切给它们
   * （最大余数法，{@code Σ 配额 == total}）。
   *
   * <pre>
   * 权重(batch) = 该批次**按阶层参与率折扣后的可用劳动** × ACTIVITY_SEX_WEIGHT_PER_MILLE[活动][该批次性别] ÷ 1000
   * 配额(batch) = total × 权重 ÷ Σ权重                              // Σ == total（精确，残差按最大余数法）
   * </pre>
   *
   * <p>★★ <b>"同一批次可以供给多个产业"因此是结构上成立的</b>：本方法只写"某一 (批次, 产业) 对"的一条配额，同一批次被另一个活动 再调用一次就会拿到**第二条**配额。
   * 创世给农村批次发**两条** （农业 900‰ + 家庭纺织 100‰），**这正是 spec §四 那条压力测试**（"同一批人口能否同时参与多个生产过程，并保持劳动力守恒"）。
   *
   * <p>★★ **性别在这里进入配置**：权重按批次性别取 {@link #ACTIVITY_SEX_WEIGHT_PER_MILLE} ⇒ 纺织的配额默认偏向女性
   * （"男耕女织"），而**不是**"女 = 纺织"的硬编码（男人照样有一条非零的纺织配额，只是权重低）。
   *
   * <p>★★ **每一批次的配额受 {@code budget} 约束**（见 {@link #laborBudget}）：切出来的份额若超过该批次**剩下的** 可支配劳动，
   * 就压到上限（**多出来的部分留在预算里，也就是"分不满"** —— R2 明说配额之和可以小于可用劳动）。 ⇒ "Σ 该批次在各产业的配额 ≤
   * 其可用劳动"**构造性成立**，不依赖"默认权重恰好不越界"。★ 这道预算是实测出来的： 把 {@link #WEAVE_SHARE_PER_MILLE} 改成 0（一个完全合理的 GM
   * 配置）时，性别权重的偏斜会让男性青壮批次要 4,202,381 —— 改前（预算 = 毛额 4,072,000）它被压到毛额；★ M1.8 起预算 = 折扣后的可用
   * 3,501,920，压得更早、更紧（这正是"参与率进计算"的落点）。★ 没有预算时载荷会被 {@code EconomyData} 的构造期守卫**当场拒**（世界播不出来）。
   *
   * <p>★ **{@code total ≤ 0} 时不发**（该池没活干，或该活动拿到的份额取整为 0）；**权重为 0 的批次也不发**（"没有配额"与"0 的配额"逐值同效）。
   *
   * @param allocations 出参：本格的配额行（每 (批次, 产业) 一条）
   * @param budget 该池各批次的**剩余可支配劳动**（{@link #laborBudget}；**就地扣减**）
   * @param total 该 unit 本次要切出去的劳动总量（千分劳动；= 该池日劳动 × 该活动的份额）
   * @param unitId 收劳动的生产单元（R3B.2 起配额的 id 与 activity 都按它拼；见 {@code LaborAllocation.idOf}）
   * @param operator 该 unit 的经营者（收劳动的主体；actor 列按它落载荷）
   * @param activity 这笔劳动干什么的**标签**（本仓当前用产业种类标签：{@code farm} / {@code weave} / {@code craft}；
   *     只有性别权重表读它，载荷的 activity 列写的是 unit id）
   */
  static void appendAllocation(
      List<Map<String, Object>> allocations,
      Map<PeopleLotId, Long> budget,
      List<PopulationGroup> pool,
      HexCoord hex,
      ProductionUnitId unitId,
      ActorRef operator,
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
      // ★★ M1.8：权重 = **按阶层参与率折扣后的**可用劳动（不是毛额）—— 与 total（也是折扣后的口径）同侧。
      long available = participationAdjustedLaborMilli(group);
      if (available > 0L) {
        workers.add(group);
        weights.add(available * sexWeightOf(sexWeights, group.sex()) / 1000L);
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
    ResidenceKind residence = ResidenceKind.ofLot(pool.get(0).id());
    for (int i = 0; i < workers.size(); i++) {
      PopulationGroup group = workers.get(i);
      // ★★ 预算约束（见方法注释）：份额超过该批次**剩下的**可支配劳动 ⇒ 压到上限，多出来的留在预算里。
      long share = Math.min(shares[i], budget.getOrDefault(group.id(), 0L));
      budget.put(group.id(), budget.getOrDefault(group.id(), 0L) - share);
      if (share <= 0L) {
        continue; // 该批次在这一活动上的权重为 0 / 取整为 0 / 预算已用尽 ⇒ 不发 0 配额
      }
      int slot = stratumSlotOfLot(group);
      if (slot >= 0) {
        // ★★ P2-A：批次按 (居住 × 阶层) 分组 ⇒ 这份劳动整份归它自己的家户（不再按阶层比例拆给四个户）。
        HouseholdId household = HouseholdIds.ofSeed(hex, residence, new SocialClassId(CLASS_IDS[slot]));
        allocations.add(
            allocationNode(unitId, operator, group, household, share));
        continue;
      }
      // ★ 旧形状夹具（批次 cohort 段不是 s<slot>-<n>）：退回"按阶层比例拆到四个家户"的旧口径。
      long[] byHousehold = splitByShares(share, CLASS_SHARE_PER_MILLE);
      for (int stratum = 0; stratum < CLASS_IDS.length; stratum++) {
        if (byHousehold[stratum] <= 0L) {
          continue;
        }
        HouseholdId household =
            HouseholdIds.ofSeed(hex, residence, new SocialClassId(CLASS_IDS[stratum]));
        allocations.add(allocationNode(unitId, operator, group, household, byHousehold[stratum]));
      }
    }
  }

  /** 批次的阶层槽位（`s<slot>-<age>` 形状）；旧形状/流民批次 ⇒ -1。 */
  static int stratumSlotOfLot(PopulationGroup group) {
    String tag = PopulationLots.cohortOf(group.id());
    int dash = tag.indexOf('-');
    if (dash <= 1 || tag.charAt(0) != 's') {
      return -1;
    }
    try {
      int slot = Integer.parseInt(tag.substring(1, dash));
      return slot >= 0 && slot < CLASS_IDS.length ? slot : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** 一条 {@code LaborAllocation} 载荷（id/group/household/actor/activity/laborMilli/period）—— 唯一拼写点。 */
  private static Map<String, Object> allocationNode(
      ProductionUnitId unitId,
      ActorRef operator,
      PopulationGroup group,
      HouseholdId household,
      long laborMilli) {
    Map<String, Object> allocation = new LinkedHashMap<>();
    // ★★ R3B.2：id 与 activity 都按 **unit** 拼；actor = unit.operator（收劳动的主体）。
    allocation.put("id", LaborAllocation.idOf(unitId, group.id(), household).value());
    allocation.put("group", group.id().value());
    allocation.put("household", household.value());
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("kind", operator.kind().name());
    actor.put("id", operator.id());
    allocation.put("actor", actor);
    allocation.put("activity", unitId.value());
    allocation.put("laborMilli", laborMilli);
    allocation.put("period", FIRST_PERIOD);
    return allocation;
  }

  /**
   * ★★ <b>S1.4：tick0 份额守恒自检</b>：逐 lot 的 {@code Σ membership.count} 必须等于该 {@link PopulationGroup}
   * 的人数。
   *
   * <p>★ seed 是份额的**构造点**（拆分的最大余数法在 {@link #appendMemberships} 里）⇒ 出口这一道是"拆分没丢/没多" 的判别力所在：不等 ⇒
   * 当场抛，把坏载荷挡在命令面之前（播进去的状态再想对账就晚了）。
   */

  /**
   * ★★ **M1.8：一个池的劳动预算**：每批次**按阶层参与率折扣后的可用劳动**（{@link
   * #participationAdjustedLaborMilli(PopulationGroup)}） —— 不再是毛额。
   *
   * <p>★★ <b>为什么要有它</b>：{@link #appendAllocation} 按"性别权重 ×
   * 可用劳动"切活动总量，而**一个池的日劳动本身已经是各行的参与率折扣后的数**（≈ 毛额的 86%）—— 两个活动先后发配额时，偏重的那一性完全可能被分到超过自己那份**可用**劳动
   * （{@link #WEAVE_SHARE_PER_MILLE} 设 0 时男性青壮批次实测 4,202,381 &gt; 它的毛额 4,072,000，更远超它的折扣后可用
   * 3,501,920）。 那时载荷会被 {@code EconomyData} 的构造期守卫拒 ⇒ **世界直接播不出来**。有了预算，那种配置退化成"分不满"（合法状态）。
   *
   * <p>★★ <b>M1.8 修的是什么</b>：改前预算是**毛额** ⇒ "地主 100‰ / 贫农 950‰"的差别只在产业**总量**里出现过一次，逐批次上限里完全不存在
   * （真档实测四阶层 {@code labor/pop} 全 = 562.0‰）。现在预算与权重都走**同一个折扣后的可用劳动** ⇒ 参与率的差别在配额这一步就咬合。
   *
   * <p>★ <b>两条网各自的口径（如实记）</b>：本预算 = <b>紧</b>的那道（按参与率折扣，配额的实际上限）；{@code EconomyData} 的构造期守卫仍以 {@code
   * LaborSupply.availableLabor()} = <b>毛额</b>为上限（宽的那道，零契约改动的代价）。 两者不矛盾：折扣后的配额必然 ≤
   * 毛额，故守卫保持绿；"同一份劳动不得被两个产业各算一次满额"这条不变量由守卫守， 而"不得超过参与率折扣后的可用"由本预算守。
   */
  static Map<PeopleLotId, Long> laborBudget(List<PopulationGroup> pool) {
    // ★★ P2-A §13.4：配额上限改为**该 batch 每 tick 的时间预算**（毫小时）—— 配额 ≤ 所属家户的
    //   ClassRow.laborMilli 由此构造性成立（splitIndustry 只搬不加）。参与率不再当硬上限，
    //   只作为 appendAllocation 的分配权重（旧口径的"折扣后劳动"不再是权威）。
    Map<PeopleLotId, Long> budget = new LinkedHashMap<>();
    for (PopulationGroup group : pool) {
      long perPerson =
          LABOR_TIME_TABLE.perPersonMilliHours(ageBracketOf(group.ageAtAnchorDays()), group.sex());
      long available = Math.multiplyExact(group.count(), perPerson);
      if (available > 0L) {
        budget.put(group.id(), available);
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
   * 农业（**恒有**，§十）：制度 = **领主自营庄园**（{@link #REGIME_FEUDAL}）；★★ **H0.3 起产能 = 本格可耕地**， 不再按人口切进四行。
   *
   * <p>★ 本注原写"制度 = 封建租佃" —— 那是**同词两义**：租佃（佃农家户）是另立的 {@code tenant} 档，而本方法写进载荷的是 {@link
   * #REGIME_FEUDAL}（**领主自营庄园**）。它原与同文件里 {@code REGIME_FEUDAL} 常量注**直接矛盾**， 已在 S1 阶段 3 D8 就地改对（同 D7
   * 的口径）。
   *
   * <p>★★ **H0.2：它名下不再有四行** —— 那一格的四行农村家户搬到 entry 级（{@link #ruralCohort}），
   * 因为"这一格有多少地"是**产业**的事、"这一格的人有多少粮/多少活"是**家户**的事（K2/K3 的分工）。
   */
  private static IndustryPlan agriculture(HexCoord hex, long landMilliMu) {
    return industry(
        FARM,
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
   * {@link #REGIME_HOUSEHOLD}，收劳动的主体是 {@code ActorKind.HOUSEHOLD} 家户。
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
  private static IndustryPlan householdWeaving(HexCoord hex, long looms) {
    return industry(
        WEAVE,
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
  private static IndustryPlan handicraft(HexCoord hex, long workshops) {
    return industry(
        CRAFT,
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
   * ★★ <b>城市贸易/承运产业（P11.7/D-024）</b>：每个有城镇人口的格追加一个 {@code trade@hex} 模板。
   *
   * <pre>
   * regime          = merchant（{@link RegimeOperators#MERCHANT}；BY_REGIME 不登记它，故经营主体显式给）
   * capacity        = {CATTLE: MERCHANT_CATTLE_PER_CITY}      // 具名 GM 默认（100）
   * capacityPerUnit = {CATTLE: 1}                             // 1 头畜力 / 1 单位运力
   * outputPerUnit   = {}                                      // 贸易没有商品产出（收入走承运运费）
   * cycleInputPerUnit = {}                                    // 没有周期投入
   * laborPerUnit    = LABOR_MILLI_PER_TRADE_UNIT（1000）
   * cycleDays       = CYCLE_DAYS
   * 经营者           = 该格 merchant principal 家户 actor
   * </pre>
   *
   * <p>★ {@code capacity} 会在 {@code assetSharesOf(plan, mainCapacity)} 出口物化成 {@code owner = operator} 的
   * {@code OWNED} CATTLE 份额（trade 没有家户副 unit 的配额 ⇒ 不拆分、整额给商号本金主）。
   */
  private static IndustryPlan trade(HexCoord hex, ActorRef merchantPrincipal) {
    IndustryPlan plan =
        industry(
            TRADE,
            IndustryHexKeys.id(TRADE, hex.q(), hex.r()).value(),
            "贸易",
            RegimeOperators.MERCHANT,
            Map.of(AssetKind.CATTLE.name(), MERCHANT_CATTLE_PER_CITY),
            // 分配模板（旧形状的 split 规则）：承运以运力资产为主；trade 不发劳动配额，labor 权重只是模板占位。
            Map.of("meansWeightPerMille", 1000, "laborWeightPerMille", 0),
            Map.of(AssetKind.CATTLE.name(), 1L),
            LABOR_MILLI_PER_TRADE_UNIT,
            Map.of(),
            Map.of(),
            merchantPrincipal);
    // ★★ <b>兼容读口必须的两个键（如实记，见实现报告）</b>：{@code EconomyPayloads.industrySpec} 对**每个**
    //   industry 节点都会先算 {@code legacyOperator}（无 operator 键 ⇒ 调 {@code RegimeOperators.defaultOperator}），
    //   而 merchant 不在 {@code RegimeOperators.BY_REGIME} 的登记表里（Agent A 的既定形状）⇒ 不给 operator 键，
    //   整个 production-runtime 载荷在解析期就 fail-closed。故 trade 节点显式带：
    //   · {@code operator} = 商号本金主（把那条默认推导短路）；
    //   · {@code relation} = 最小自留关系（merchant 不在 {@code RegimeRelations} 的默认模板表里；这条关系同时供
    //     解析器为下面"由 legacy 合成"的 trade unit 建关系）。
    //   代价：trade 的 unit 由 {@code EconomyPayloads} 的 legacy 合成路径按 {@code (产业, operator)} 的同一公式建出
    //   （id/operator/modeKey 与本类 plan 逐值同式），因此 seeder **不**再为 trade 显式发 units[] 条目（否则解析器判
    //   "同一 (产业, 经营者) 两处拼写"）；CATTLE 份额仍走 assetShares 出口。
    plan.payload().put("operator", actorNode(merchantPrincipal));
    plan.payload().put("relation", relationNode(tradeRelation(plan)));
    return plan;
  }

  /**
   * ★★ <b>trade 的最小自留生产关系</b>：{@code merchant} 不在 {@code RegimeRelations} 的四档默认表里，载荷若无显式
   * {@code relation} 会在解析期 fail-closed（{@code EconomyPayloads} 的 defaultRelation 分支）⇒ seeder 必须显式给一条。
   *
   * <p>空 {@code rules} = 产出全归 {@link ProductionRelation#residualOwner()}（这与"缺 relation 走最小自留"逐值同效）；
   * {@code trade} 没有商品产出，劳动来源按其本金主/经营者自营记 {@link LaborSource#SELF}。投入供方就是 operator（无投入，
   * 这一栏只是形状）。
   */
  private static ProductionRelation tradeRelation(IndustryPlan plan) {
    ActorRef operator = plan.operator();
    return new ProductionRelation(
        plan.unitId(),
        operator,
        new Recipient.ToActor(operator),
        List.of(),
        operator,
        LaborSource.SELF);
  }

  /**
   * ★★ <b>一条城市商号</b>：id 由调用方按与 {@code EconomyOrganizationSettlement} 相同的四元组公式给出；tier/capacity
   * 都是具名 GM 默认，三个金额读数从 0 起（本批只落形状）。
   */
  private static MerchantFirm merchantFirm(
      HexCoord hex, ProductionOrganizationId organizationId) {
    return new MerchantFirm(
        organizationId,
        MERCHANT_TIER,
        hex,
        true,
        MERCHANT_CAPACITY_PER_CITY,
        0L,
        MerchantFirm.defaultServiceRadiusHex(MERCHANT_TIER),
        0L,
        0L,
        0L,
        0L);
  }

  /**
   * 一个产业对象（与 §3.1 {@code Industry} 逐字段对应；**R3 起含 V7 的四个配方分量**；**H0.3 起含产能总量**）。
   *
   * <p>{@code dailyInputPerUnit}/{@code dailyLaborPerUnit} 置 0：§十 没给这两项的依据（那是 R3a 的事），**不臆造**；
   * {@code cycleDays} = {@link #CYCLE_DAYS}。★★ R3B.2 起返回 {@link IndustryPlan}：模板载荷 + 经营者 + 产能， unit
   * 与资产份额从它派生（模板本身不再带 operator/capacity/progress/cycleState）。
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
  private static IndustryPlan industry(
      String kind,
      String id,
      String name,
      String regime,
      Map<String, Object> capacity,
      Map<String, Object> split,
      Map<String, Object> capacityPerUnit,
      long laborPerUnit,
      Map<String, Object> outputPerUnit,
      Map<String, Object> cycleInputPerUnit) {
    return industry(
        kind,
        id,
        name,
        regime,
        capacity,
        split,
        capacityPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        RegimeOperators.defaultOperator(new RegimeId(regime), new IndustryId(id)));
  }

  /**
   * 上一条的**显式经营主体**重载：服务 {@code RegimeOperators.BY_REGIME} 不登记的制度（当前 = merchant 的
   * {@code trade}，它的经营主体必须由载荷显式给出）。载荷/单位/份额的构造与上一条逐字相同。
   */
  private static IndustryPlan industry(
      String kind,
      String id,
      String name,
      String regime,
      Map<String, Object> capacity,
      Map<String, Object> split,
      Map<String, Object> capacityPerUnit,
      long laborPerUnit,
      Map<String, Object> outputPerUnit,
      Map<String, Object> cycleInputPerUnit,
      ActorRef operator) {
    if (operator == null) {
      throw new IllegalArgumentException(
          "industry 的 operator 不得为 null（缺省请用不带 operator 的重载）");
    }
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
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id);
    payload.put("name", name);
    payload.put("regime", regime);
    payload.put("cycleDays", CYCLE_DAYS);
    // ★★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"。
    payload.put("capacityPerUnit", capacityPerUnit);
    payload.put("laborPerUnit", laborPerUnit);
    payload.put("dailyInputPerUnit", Map.of());
    payload.put("dailyLaborPerUnit", 0);
    payload.put("outputPerUnit", outputPerUnit);
    // ★★ 一次性投入（v2 spec §3.3：**周期第一天**现扣的原料）：值侧带商品维度（R3 换型）。
    payload.put("cycleInputPerUnit", cycleInputPerUnit);
    payload.put("allocation", allocation);
    payload.put("slots", slots);
    // ★★ R3B.2：**模板不再带 operator/capacity/progress/cycleState** —— 这些是 unit/份额的事实：
    //   经营者 = RegimeOperators 的默认（与 operatorSeed 同源）；进度/劳动/投入由 unitOf(plan) 显式发出；
    //   产能总量由 assetSharesOf(plan) 整额 OWNED 物化。这样新载荷不会触发"旧形状"兼容路径。
    Map<String, Long> capacityLongs = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : capacity.entrySet()) {
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalStateException("capacity 的值必须是整数: " + entry);
      }
      capacityLongs.put(entry.getKey(), number.longValue());
    }
    // ★★ R4-B.3a：capacityPerUnit 也留一份**定点整数**形态进 plan —— 副 unit 的"至少一份"判据
    //   （每资产至少 capacityPerUnit）读它；载荷那一份仍原样发出（形状不变）。
    Map<String, Long> capacityPerUnitLongs = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : capacityPerUnit.entrySet()) {
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalStateException("capacityPerUnit 的值必须是整数: " + entry);
      }
      capacityPerUnitLongs.put(entry.getKey(), number.longValue());
    }
    return new IndustryPlan(
        kind, id, regime, operator, capacityLongs, capacityPerUnitLongs, payload);
  }

  /**
   * ★★ <b>R3B.2：一个产业 plan → 一条默认 unit 载荷</b>（一产业一 unit；多 unit 拆分留给 B.3）。
   *
   * <pre>
   * id            = ProductionUnitId.idOf(industry, operator)
   * modeKey       = industry.id().value()（旧档口径；候选预设留给 E2）
   * progressDays/cycleLaborMilli/cycleInputUsedMilli = 0/0/{}（周期刚起）
   * </pre>
   */
  private static Map<String, Object> unitOf(IndustryPlan plan) {
    return unitOf(plan, plan.operator(), null);
  }

  /**
   * ★★ <b>R4-B.3a：一个家户副 unit 的载荷</b>：与主 unit 逐字段同形（{@code id/industry/operator/modeKey/
   * progressDays/cycleLaborMilli/cycleInputUsedMilli}），额外显式发 {@code relation}（副 unit 的规则不靠制度默认）。
   */
  private static Map<String, Object> unitOf(IndustryPlan plan, HouseholdUnit secondary) {
    return unitOf(plan, secondary.operator(), secondary.relation());
  }

  /**
   * 一条 unit 载荷的共同构造（主 unit 与副 unit 的唯一拼写点）：{@code id} 由契约层工厂按 {@code (产业, 经营者)} 算； {@code modeKey =
   * 产业 id}（旧档口径；候选预设留给 E2）；周期状态全部从 0 起（创世）。
   *
   * @param relation 只在副 unit 上显式发出（主 unit 仍走制度默认关系）；主 unit 传 {@code null}
   */
  private static Map<String, Object> unitOf(
      IndustryPlan plan, ActorRef operator, ProductionRelation relation) {
    Map<String, Object> unit = new LinkedHashMap<>();
    unit.put("id", ProductionUnitId.idOf(new IndustryId(plan.id()), operator).value());
    unit.put("industry", plan.id());
    unit.put("operator", actorNode(operator));
    unit.put("modeKey", plan.id());
    unit.put("progressDays", 0);
    unit.put("cycleLaborMilli", 0);
    unit.put("cycleInputUsedMilli", Map.of());
    if (relation != null) {
      unit.put("relation", relationNode(relation));
    }
    return unit;
  }

  /** 一个受方的载荷节点（{@code actor|household|cohort} 恰其一）——与 {@code EconomyPayloads.recipient} 同一形状。 */
  private static Map<String, Object> recipientNode(Recipient recipient) {
    Map<String, Object> node = new LinkedHashMap<>();
    if (recipient instanceof Recipient.ToActor toActor) {
      node.put("actor", actorNode(toActor.actor()));
    } else if (recipient instanceof Recipient.ToHousehold toHousehold) {
      node.put("household", toHousehold.household().value());
    } else if (recipient instanceof Recipient.ToCohort toCohort) {
      node.put("cohort", toCohort.cohort().toString());
    } else {
      throw new IllegalStateException("未知 Recipient 变体：" + recipient);
    }
    return node;
  }

  /** 一条显式生产关系 → 载荷节点（{@code operator/inputSupplier/residualOwner/rules/laborSource}）。 */
  private static Map<String, Object> relationNode(ProductionRelation relation) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("operator", actorNode(relation.operator()));
    node.put("inputSupplier", recipientNode(relation.inputSupplier()));
    node.put("residualOwner", actorNode(relation.residualOwner()));
    List<Map<String, Object>> rules = new ArrayList<>(relation.rules().size());
    for (CompensationRule rule : relation.rules()) {
      rules.add(ruleNode(rule));
    }
    node.put("rules", rules);
    node.put("laborSource", relation.laborSource().name());
    return node;
  }

  /** 一条补偿规则 → 载荷节点（{@code EconomyPayloads.compensationRule} 的逆形状）。 */
  private static Map<String, Object> ruleNode(CompensationRule rule) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", rule.type().name());
    node.put("recipient", recipientNode(rule.recipient()));
    node.put("pool", rule.pool().name());
    node.put("weight", rule.weight().name());
    node.put("ratePerMille", rule.ratePerMille());
    node.put("fixedAmount", rule.fixedAmount());
    rule.commodity().ifPresent(commodity -> node.put("commodity", commodity.value()));
    rule.currency().ifPresent(currency -> node.put("currency", currency.value()));
    node.put("priority", rule.priority());
    return node;
  }

  /** 同一格内按产业 id 找 plan（三产业固定集合；找不到 = 该产业本格不存在）。 */
  private static IndustryPlan planOf(List<IndustryPlan> plans, IndustryId id) {
    for (IndustryPlan plan : plans) {
      if (plan.id().equals(id.value())) {
        return plan;
      }
    }
    throw new IllegalStateException("本格没有产业 plan: " + id);
  }

  /** ★★ R3B.2 的产业播种中间体：模板载荷 + 经营者 + 产能总量（unit 与资产份额都从它派生）。 */
  private record IndustryPlan(
      String kind,
      String id,
      String regime,
      ActorRef operator,
      Map<String, Long> capacity,
      Map<String, Long> capacityPerUnit,
      Map<String, Object> payload) {

    /** 新 id 的唯一拼写点（契约层工厂）。 */
    ProductionUnitId unitId() {
      return ProductionUnitId.idOf(new IndustryId(id), operator);
    }

    /** ★★ P2-A §13.3：把主 unit 的经营者换成一个家户 actor（庄园/作坊的组织者家户）。 */
    IndustryPlan withOperator(ActorRef next) {
      if (next == null) {
        throw new IllegalArgumentException("IndustryPlan.withOperator 的 next 不得为 null");
      }
      return new IndustryPlan(kind, id, regime, next, capacity, capacityPerUnit, payload);
    }
  }

  /**
   * ★★ <b>主 unit 的显式关系</b>（P2-A §13.3）：{@code operator}/{@code residualOwner} = 组织者家户，
   * {@code inputSupplier} 仍指原产业主体 actor（它在 {@code supplierAccountsOf} 里代理到 unit 名下劳动家户，
   * 播种期的"谁出种/出料"因此逐字不变）。
   */
  private static ProductionRelation organizerRelation(
      IndustryPlan plan, Set<ResidenceKind> residences, ActorRef inputSupplierOrg) {
    IndustryId industryId = new IndustryId(plan.id());
    ProductionUnitId activity = ProductionUnitId.idOf(industryId, plan.operator());
    ProductionRelation base =
        RegimeRelations.defaultRelation(
            new RegimeId(plan.regime()), activity, industryId, plan.operator(), residences);
    return new ProductionRelation(
        base.activity(),
        base.operator(),
        new Recipient.ToActor(inputSupplierOrg),
        withoutSelfPayments(base.rules(), plan.operator()),
        base.residualOwner(),
        base.laborSource());
  }

  /**
   * ★★ <b>剔除"受方 == 本条关系的 operator"的规则</b>（P2-E）：这类规则对余额是恒等变换，而 {@code Transfer}
   * 的两端不得相等（自转移是坏数据）。P2-A 起主 farm/craft unit 的 operator 就是组织者家户，而 FEUDAL/HANDICRAFT
   * 默认模板里"付给该家户所在 cohort"的给养/地租/工资就是自付 —— 不剔除会在首个收获日当场抛。
   *
   * <p>★ 口径与 {@code EconomyOrganizationSettlement.normalizeRecipients} 逐条相同（那里管自动组织新建的 unit，
   * 这里管 seeder 创世载荷）；本类不另立第二套判定。
   */
  private static List<CompensationRule> withoutSelfPayments(
      List<CompensationRule> rules, ActorRef operator) {
    List<CompensationRule> kept = new ArrayList<>(rules.size());
    for (CompensationRule rule : rules) {
      if (!paysOperator(rule.recipient(), operator)) {
        kept.add(rule);
      }
    }
    return List.copyOf(kept);
  }

  /** 一条规则的受方是否就是 operator（cohort 受方按创世家户 id 的同一拼写点还原）。 */
  private static boolean paysOperator(Recipient recipient, ActorRef operator) {
    return switch (recipient) {
      case Recipient.ToHousehold toHousehold ->
          HouseholdActors.of(toHousehold.household()).equals(operator);
      case Recipient.ToActor toActor -> toActor.actor().equals(operator);
      case Recipient.ToCohort toCohort ->
          HouseholdActors.of(
                  HouseholdIds.ofSeed(
                      toCohort.cohort().hex(),
                      toCohort.cohort().residence(),
                      toCohort.cohort().stratum()))
              .equals(operator);
    };
  }

  /**
   * ★★ <b>集体经营 unit 的最小关系</b>（P2-E；当前 = 家户纺织主 unit）：{@code operator}/{@code
   * residualOwner} = 聚合经营主体，{@code inputSupplier} 也指它（{@code supplierAccountsOf} 对聚合主体按
   * {@code index.householdsOf(unit)} 代理到各劳动家户账）；<b>规则为空</b> —— 净产出已由 harvest 的
   * {@code creditOutput} 按劳动权重分给各家家户账，不再叠一层制度模板。
   *
   * <p>★ <b>为什么必须显式空规则</b>：HOUSEHOLD 模板的四个受方 cohort 与这个集体 unit 的成员是同一批家户 ⇒
   * 模板规则会让成员之间互相转移（同一对家户既是付方又是受方 ⇒ 自转移，{@code Transfer} 的两端不得相等，
   * 实测首个收获日当场抛）。集体经营的产出归属由 {@code HouseholdRouting} 的劳动权重表达，模板在这里是第二本账。
   */
  private static ProductionRelation collectiveRelation(IndustryPlan plan) {
    return new ProductionRelation(
        plan.unitId(),
        plan.operator(),
        new Recipient.ToActor(plan.operator()),
        List.of(),
        plan.operator(),
        LaborSource.FAMILY);
  }

  /**
   * ★★ <b>R3B.2：一个产业 plan → 它的整额 OWNED 实物资产份额</b>（capacity 的逐项；{@code owner} 与 {@code operator} 都 =
   * plan.operator，即新世界播种的"自有自营"档）。
   *
   * <p>★ <b>逐项含 0 值</b>：0 产能是合法形态（沙漠格 LAND=0），但"非 EXITED/ABANDONED 的 unit 必须至少有一条同 industry 的
   * AssetShare"这条守卫要求 0 也登记；数量 0 不改变规模（capacityScale 对每键读到 0 ⇒ 规模 0，与旧档 capacity 0 等价）。 {@code kind
   * = OWNED} 是创世默认档。★ B.2 不拆多 unit：一块 capacity 只发一条整额份额。
   */
  private static List<Map<String, Object>> assetSharesOf(
      IndustryPlan plan, Map<String, Long> quantities) {
    List<Map<String, Object>> shares = new ArrayList<>();
    for (Map.Entry<String, Long> entry : quantities.entrySet()) {
      // ★★ 逐项**含 0 值**：0 产能是合法形态（沙漠格 LAND=0），但"非退出 unit 必须有至少一条同 industry 的
      //   AssetShare"这条守卫要求 0 也要登记（数量 0 不改变规模：capacityScale 对每键读到 0 ⇒ 规模 0）。
      shares.add(
          assetShareNode(
              plan.id(),
              entry.getKey(),
              plan.operator(),
              plan.operator(),
              AssetShare.RightKind.OWNED,
              entry.getValue()));
    }
    return shares;
  }

  /**
   * ★★ <b>R4-B.3a：把一个产业的现有配额行拆成"主 unit 余量 + 家户副 unit"</b>（只做静态初值，不改运行期结算）。
   *
   * <pre>
   * 劳动：moved = ⌊laborMilli × SECONDARY_PER_MILLE ÷ 1000⌋
   *       主行减 moved；副 unit 新发一条 (unit, group, household) 的配额（Σ 逐批次不变）
   * 资产：secondaryTotal = ⌊capacity × SECONDARY_PER_MILLE ÷ 1000⌋
   *       按各户 moved 劳动为权重用最大余数法切给候选户；每户每个 asset 都 ≥ capacityPerUnit 才建副 unit
   *       被跳过的户：份额与劳动都留在主 unit（只拆不加；主 unit 拿 total − 已建成副 unit 份额）
   * </pre>
   *
   * <p>★★ <b>守恒由构造保证</b>：{@code Σ 主+副 assetShares == plan.capacity}、{@code Σ 主行+副行 laborMilli ==
   * 原行}。 ★ <b>确定性</b>：候选户按 {@link HouseholdId#value()} 升序（{@code ProportionalSplit} 的"同余数按下标序"
   * 因此正好是 canonical id 升序）；不读 {@code HashMap} 迭代序，不用随机数/时间。
   *
   * @param landlordPopulation 该格农村地主阶层的人数（0 ⇒ 佃 unit 的 owner 退回主 unit 的 ESTATE actor； 见 {@link
   *     #farmTenantUnit}）
   */
  private static IndustrySplit splitIndustry(
      IndustryPlan plan,
      List<Map<String, Object>> allocations,
      HexCoord hex,
      long landlordPopulation) {
    String mainUnitId = plan.unitId().value();
    // ① 只拆"指向主 unit"的行；moved ≤ 0 的行原样留下（小配额不拆 = 合法，不是丢数据）。
    Map<HouseholdId, Long> movedLaborByHousehold = new LinkedHashMap<>();
    List<MovedRow> movedRows = new ArrayList<>();
    for (Map<String, Object> allocation : allocations) {
      if (!mainUnitId.equals(allocation.get("activity"))) {
        continue;
      }
      long laborMilli = ((Number) allocation.get("laborMilli")).longValue();
      long moved = laborMilli * SECONDARY_PER_MILLE / 1000L;
      if (moved <= 0L) {
        continue;
      }
      HouseholdId household = HouseholdId.parse((String) allocation.get("household"));
      if (HouseholdActors.of(household).equals(plan.operator())) {
        continue; // ★ P2-A：组织者家户（地主/作坊主）自己的劳动与产能留在主 unit，不另建同 id 的副 unit。
      }
      movedLaborByHousehold.merge(household, moved, Math::addExact);
      movedRows.add(new MovedRow(allocation, household, moved));
    }
    if (movedLaborByHousehold.isEmpty()) {
      // 没有可拆的劳动 ⇒ 一产业一 unit（与 B.2 逐值相同）；主 unit 仍拿整份 capacity。
      return new IndustrySplit(plan, plan.capacity(), List.of(), List.of());
    }
    List<HouseholdId> households = new ArrayList<>(movedLaborByHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    long[] weights = new long[households.size()];
    for (int i = 0; i < households.size(); i++) {
      weights[i] = movedLaborByHousehold.get(households.get(i));
    }
    // ② 逐 asset 切副 unit 总量；"至少一份"不满足的户整体不建（份额与劳动都退回主 unit）。
    Map<String, long[]> sharesByAsset = new LinkedHashMap<>();
    boolean[] kept = new boolean[households.size()];
    Arrays.fill(kept, true);
    for (Map.Entry<String, Long> capacity : plan.capacity().entrySet()) {
      long secondaryTotal = capacity.getValue() * SECONDARY_PER_MILLE / 1000L;
      long[] shares = splitProportional(secondaryTotal, weights);
      sharesByAsset.put(capacity.getKey(), shares);
      Long capacityPerUnit = plan.capacityPerUnit().get(capacity.getKey());
      if (capacityPerUnit == null) {
        throw new IllegalStateException(
            "产业 " + plan.id() + " 的资产 " + capacity.getKey() + " 没有 capacityPerUnit（无法判一份最小规模）");
      }
      for (int i = 0; i < households.size(); i++) {
        if (shares[i] < capacityPerUnit) {
          kept[i] = false;
        }
      }
    }
    // ③ 主 unit 剩余 = capacity − 已建成副 unit 的份额；被跳过户的份额不动（就在主 unit 里）。
    Map<String, Long> mainCapacity = new LinkedHashMap<>(plan.capacity());
    List<HouseholdUnit> secondaries = new ArrayList<>();
    for (int i = 0; i < households.size(); i++) {
      if (!kept[i]) {
        continue;
      }
      HouseholdId household = households.get(i);
      Map<String, Long> quantities = new LinkedHashMap<>();
      for (Map.Entry<String, Long> capacity : plan.capacity().entrySet()) {
        long quantity = sharesByAsset.get(capacity.getKey())[i];
        quantities.put(capacity.getKey(), quantity);
        mainCapacity.merge(capacity.getKey(), -quantity, Math::addExact);
      }
      secondaries.add(householdUnit(plan, hex, household, quantities, landlordPopulation));
    }
    // ④ 只把"建成了副 unit"的家户的劳动行移过去；其余行原样留在主 unit。
    Map<HouseholdId, HouseholdUnit> secondaryByHousehold = new LinkedHashMap<>();
    for (HouseholdUnit secondary : secondaries) {
      secondaryByHousehold.put(secondary.household(), secondary);
    }
    List<LaborMove> laborMoves = new ArrayList<>();
    for (MovedRow movedRow : movedRows) {
      HouseholdUnit secondary = secondaryByHousehold.get(movedRow.household());
      if (secondary != null) {
        laborMoves.add(new LaborMove(movedRow.row(), secondary, movedRow.moved()));
      }
    }
    return new IndustrySplit(plan, mainCapacity, secondaries, laborMoves);
  }

  /**
   * 一条副 unit 的劳动配额载荷：{@code id = LaborAllocation.idOf(副 unit, 批次, 家户)}、{@code actor = 家户 actor}、
   * {@code activity = 副 unit id}，其余字段（group/household/period）照原行。★ 调用方先把主行的 {@code laborMilli} 减掉
   * moved。
   */
  private static Map<String, Object> secondaryAllocation(IndustryPlan plan, LaborMove move) {
    HouseholdUnit unit = move.unit();
    ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(plan.id()), unit.operator());
    Map<String, Object> row = move.row();
    Map<String, Object> allocation = new LinkedHashMap<>();
    allocation.put(
        "id",
        LaborAllocation.idOf(unitId, PeopleLotId.parse((String) row.get("group")), unit.household())
            .value());
    allocation.put("group", row.get("group"));
    allocation.put("household", unit.household().value());
    allocation.put("actor", actorNode(unit.operator()));
    allocation.put("activity", unitId.value());
    allocation.put("laborMilli", move.moved());
    allocation.put("period", row.get("period"));
    return allocation;
  }

  /**
   * ★★ <b>一个家户副 unit 的完整形态</b>（owner/kind/relation 按产业档位显式给出，<b>不靠制度默认关系猜</b>）。
   *
   * <pre>
   * farm  ⇒ operator=家户 · owner=该格农村地主家户（0 人口 / 自租 ⇒ ESTATE 主 unit） · TENANCY · relation=TENANT + 300‰ 粮租
   * weave ⇒ operator=owner=家户 · OWNED · relation 空 rules（全自留） + FAMILY
   * craft ⇒ operator=家户 · owner=WORKSHOP 主 unit · TENANCY · relation=TENANT + 300‰ 布给作坊主
   * </pre>
   *
   * <p>★ <b>家户 actor 的身份拼写点</b> = {@link HouseholdActors#of(HouseholdId)}（本类不另拼 actor id）。
   */
  private static HouseholdUnit householdUnit(
      IndustryPlan plan,
      HexCoord hex,
      HouseholdId household,
      Map<String, Long> quantities,
      long landlordPopulation) {
    ActorRef operator = HouseholdActors.of(household);
    return switch (plan.kind()) {
      case FARM -> farmTenantUnit(plan, hex, household, operator, quantities, landlordPopulation);
      case WEAVE ->
          new HouseholdUnit(
              household,
              operator,
              operator,
              AssetShare.RightKind.OWNED,
              quantities,
              new ProductionRelation(
                  ProductionUnitId.idOf(new IndustryId(plan.id()), operator),
                  operator,
                  new Recipient.ToHousehold(household),
                  List.of(),
                  operator,
                  LaborSource.FAMILY));
      case CRAFT ->
          new HouseholdUnit(
              household,
              operator,
              plan.operator(),
              AssetShare.RightKind.TENANCY,
              quantities,
              new ProductionRelation(
                  ProductionUnitId.idOf(new IndustryId(plan.id()), operator),
                  operator,
                  new Recipient.ToActor(plan.operator()),
                  List.of(outputShareRule(new Recipient.ToActor(plan.operator()), COMMODITY_CLOTH)),
                  operator,
                  LaborSource.TENANT));
      default -> throw new IllegalStateException("未知产业 kind：" + plan.kind());
    };
  }

  /**
   * 佃耕 unit：{@code owner = 该格农村 landlord 家户 actor}，在两种退化情形下退回主 unit 的 ESTATE actor： ① 地主阶层人口为
   * 0（或该行不存在）；② **operator 就是 landlord 家户本人**（自己租给自己）—— 后者若仍把 owner/受方写成该家户，收获时那条 {@code
   * OUTPUT_SHARE} 会铸出"两端相等"的转移，被 {@code Transfer} 的 fail-closed 守卫当场拒（实测：799 个格各一条，见 B3a 报告）。显式
   * relation 把毛产的 {@link #TENANT_RENT_PER_MILLE}‰ 以粮付给 owner，其余归佃农家户（{@code residualOwner}）。
   *
   * <p>★★ <b>受方可解析</b>：owner 是家户时用 {@code ToHousehold(landlord)}（该家户行由 {@code ruralCohort} 恒建， 0
   * 人口行也合法）；退回 ESTATE 时用 {@code ToActor(ESTATE)}（经营者开缸账由 {@code operators} 建）。
   */
  private static HouseholdUnit farmTenantUnit(
      IndustryPlan plan,
      HexCoord hex,
      HouseholdId household,
      ActorRef operator,
      Map<String, Long> quantities,
      long landlordPopulation) {
    HouseholdId landlord =
        HouseholdIds.ofSeed(
            hex, ResidenceKind.RURAL, new SocialClassId(CLASS_IDS[LANDLORD_SLOT_INDEX]));
    // ★ 自租退化（operator == landlord）必须与"没有地主"同档：转移的两端不许相等（Transfer 构造期守卫）。
    boolean hasLandlord = landlordPopulation > 0L && !landlord.equals(household);
    ActorRef owner = hasLandlord ? HouseholdActors.of(landlord) : plan.operator();
    Recipient rentRecipient =
        hasLandlord ? new Recipient.ToHousehold(landlord) : new Recipient.ToActor(plan.operator());
    ProductionRelation relation =
        new ProductionRelation(
            ProductionUnitId.idOf(new IndustryId(plan.id()), operator),
            operator,
            new Recipient.ToHousehold(household),
            List.of(outputShareRule(rentRecipient, COMMODITY_GRAIN)),
            operator,
            LaborSource.TENANT);
    return new HouseholdUnit(
        household, operator, owner, AssetShare.RightKind.TENANCY, quantities, relation);
  }

  /** 一条 {@code OUTPUT_SHARE × GROSS_OUTPUT} 实物分成规则（佃租/匠户分成共用这一处拼写）。 */
  private static CompensationRule outputShareRule(Recipient recipient, String commodity) {
    return new CompensationRule(
        RuleType.OUTPUT_SHARE,
        recipient,
        Pool.GROSS_OUTPUT,
        Weight.NONE,
        TENANT_RENT_PER_MILLE,
        0L,
        Optional.of(new CommodityId(commodity)),
        Optional.empty(),
        10);
  }

  /** 一个产业的拆分结果：主 unit 剩余容量 + 家户副 unit（保 canonical 序）+ 移到副 unit 的劳动行。 */
  private record IndustrySplit(
      IndustryPlan plan,
      Map<String, Long> mainCapacity,
      List<HouseholdUnit> secondaries,
      List<LaborMove> laborMoves) {}

  /** 一个家户副 unit：身份、产权、份额与显式关系（逐产业不同，见 {@link #householdUnit}）。 */
  private record HouseholdUnit(
      HouseholdId household,
      ActorRef operator,
      ActorRef owner,
      AssetShare.RightKind kind,
      Map<String, Long> quantities,
      ProductionRelation relation) {}

  /** 拆分**中间态**：一条候选配额行（household/moved 已算好，副 unit 是否建尚未定）。 */
  private record MovedRow(Map<String, Object> row, HouseholdId household, long moved) {}

  /** 一条**已决定**要移到副 unit 的配额行（unit 已绑定，{@code moved ≤ 原行 laborMilli}）。 */
  private record LaborMove(Map<String, Object> row, HouseholdUnit unit, long moved) {}
}
