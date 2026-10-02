package io.mosire.simos.app.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.GovernmentActors;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.ClassFirstPilotEngine;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
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
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  /**
   * ★★ <b>经济地基 profile</b>（R3a 起只剩一个值）。
   *
   * <p>★ <b>R3a 删除</b>了旧的 {@code legacy} / {@code complete} 两个 profile 与全部旧生产 payload 分支：{@code
   * entries} 里不再有 {@code industries}（恒空数组，只是 payload 解析器的必填壳）、{@code laborSupply} / {@code
   * allocations} / {@code assetShares} / {@code units} / {@code memberships}；阶层池状态由顶层 {@code
   * classFirst}（{@link ClassFirstState}）承担，它是唯一的生产结算权威。旧档若带这些旧键，读入时按"只读迁移数据"处理， <b>绝不回到旧结算路径</b>。
   */
  public enum FoundationProfile {
    /** ★★ 唯一 profile：只种阶层池状态 + 最小人口/市场壳；不种旧生产结构。 */
    CLASS_FIRST("class-first");

    private final String wireName;

    FoundationProfile(String wireName) {
      this.wireName = wireName;
    }

    /** 线格式名（{@code "class-first"}）。 */
    public String wireName() {
      return wireName;
    }

    /** 按线格式名解析；R3a 起只认 {@code "class-first"}，其余/空白一律 fail-closed。 */
    public static FoundationProfile parse(String text) {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("economyProfile 不得为空白；合法值: class-first");
      }
      if (!"class-first".equals(text.trim())) {
        throw new IllegalArgumentException(
            "未知的 economyProfile: " + text + "；R3a 起合法值只有: class-first");
      }
      return CLASS_FIRST;
    }
  }

  // ── R2a：CLASS_FIRST 的种子参数（全部是显式出厂值；改它们 = 改新世界初态）────────────────────

  /**
   * ★★ <b>CLASS_FIRST：社会阶层槽位 → 阶层池位置</b>（与 {@link #CLASS_IDS} 同序：贫农/中农/富农/地主）。
   *
   * <p>★ <b>映射是一次显式种子判断</b>（不是旧生产结构）：地主 → {@code LANDLORD}、富农 → {@code MIDDLE_PEASANT}（有地自耕的中农）、 中农
   * → {@code TENANT}（佃耕）、贫农 → {@code LABORER}（无地雇农）。四个池因此都有真实人口；若将来要改档，改这一行即可。
   */
  private static final String[] CLASS_FIRST_POSITION_BY_SLOT = {
    PilotModel.LABORER_ID,
    PilotModel.TENANT_ID,
    PilotModel.MIDDLE_PEASANT_ID,
    PilotModel.LANDLORD_ID
  };

  /**
   * ★★ <b>CLASS_FIRST：初始土地所有权（‰，与 {@link #CLASS_IDS} 同序）</b>：{@code {贫农 0, 中农 0, 富农 300, 地主 700}}。
   *
   * <p>★ 与旧世界「主 unit 700‰ / 佃农副 unit 300‰」（{@link #SECONDARY_PER_MILLE}）同一量级：地主是主要出租方，富农自耕一份；
   * 中农/贫农无地（佃/雇）。土地总量**逐值来自**本 seed 的可耕地（{@link #landMilliMuOf}），不新增一亩。
   */
  private static final int[] CLASS_FIRST_LAND_OWNERSHIP_PER_MILLE = {0, 0, 300, 700};

  /**
   * ★★ <b>CLASS_FIRST：初始农具所有权（‰，与 {@link #CLASS_IDS} 同序）</b>：{@code {贫农 0, 中农 500, 富农 500, 地主 0}}。
   *
   * <p>★ 工具总量**逐值来自**本 seed 已有的作坊工具存量（{@code 作坊数 × }{@link #toolPerWorkshopMilli()}，÷1000 换成件），
   * 只把持有者从旧 craft 经营者改成两个**实际耕种**的池（{@link PilotModel#MIDDLE_PEASANT_ID} / {@link
   * PilotModel#TENANT_ID}）—— 引擎只让这两个池经营土地。
   */
  private static final int[] CLASS_FIRST_TOOLS_OWNERSHIP_PER_MILLE = {0, 500, 500, 0};

  /** ★ CLASS_FIRST：土地/工具按池内人口权重切分给家户子账户（总量由 {@link #splitProportional} 保真）。 */
  private static final long CLASS_FIRST_HOUSEHOLD_PARTICIPATION_PER_MILLE = 1000L;

  /**
   * ★★ <b>CLASS_FIRST：GOV 放贷窗口的初始资金（占创世家户货币总量的 ‰）</b>：{@code 10000} = <b>10 倍</b>。
   *
   * <p>★ <b>它是什么</b>：独立于任何阶层池的放贷主体（计划 §8「放贷先用独立 Lender/GOV 账户模拟」）—— 货币走一条显式 {@code
   * INITIAL_ENDOWMENT} 发行记录，商品/流动性为 0（只放钱、不放货）。★ 资金量按**本 seed 已算出的家户钱包**派生（不另造人口口径）， 10
   * 倍是"足够深的口袋"这一判断值（GM 可调：改这个常量 = 改新世界初态）。
   */
  private static final long CLASS_FIRST_LENDER_MONEY_PER_MILLE_OF_HOUSEHOLD = 10_000L;

  /** ★ CLASS_FIRST：放贷窗口的稳定 id（独立于任何阶层池；与 {@code ExternalLenderId} 的派生点一致）。 */
  public static final String CLASS_FIRST_LENDER_ID = "gov-class-first-lender";

  /** ★ CLASS_FIRST：放贷窗口利率（‰/tick；与 pilot 默认的 20‰ 同量级）。 */
  private static final long CLASS_FIRST_LENDER_INTEREST_PER_MILLE = 20L;

  /** ★ CLASS_FIRST：放贷窗口的首次到期 tick（与 pilot 夹具的 60 同值）。 */
  private static final long CLASS_FIRST_LENDER_NEXT_DUE_TICK = 60L;

  /** ★ CLASS_FIRST：放贷窗口的催收能力（pilot 的具名参数，保留原值）。 */
  private static final long CLASS_FIRST_LENDER_COLLECTION_POWER = 1000L;

  /** ★ CLASS_FIRST：地主催收政策的出厂值（与 pilot 夹具同值；GM 可调 policy 由 classFirst meta.config 显式带出）。 */
  private static final long CLASS_FIRST_COLLECTION_THRESHOLD = 900L;

  private static final long CLASS_FIRST_COLLECTION_TRIGGER_RATIO_PER_MILLE = 6000L;
  private static final long CLASS_FIRST_COLLECTION_RATIO_PER_MILLE = 250L;
  private static final long CLASS_FIRST_LAND_PRICE_PER_UNIT = 20L;

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
   * ★★ **初始粮食储备的按阶层天数表**（版本化参数，2026-09-25 用户点名）：键 = 阶层槽位，值 = **每人几天的口粮**。
   *
   * <p>★★ **为什么要按阶层差异化**：旧口径给全世界每一行都配同一份 60 天口粮 ⇒ 同格里**谁都没有余粮**（人人都恰好吃到自己那份），
   * 于是同格借粮链**空转**、缺粮**没有任何后果**。贫农最薄（30 天）、地主最厚（250 天）后，地主/富农手里天然有可贷的余粮， 贫农先见底 ⇒ 同格借贷与（{@code
   * 旧结算引擎（R3a 已删除）} 的）饿死惩罚才有落点。
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
   * {@link #CLASS_IDS} / {@link #CLASS_SHARE_PER_MILLE} 里**地主**的槽位序号（= 3）。
   *
   * <p>★ R4-B.3a 的佃耕 unit 要指名"该格农村 landlord 家户"（owner / 租金受方）—— 这个下标不在别处再写数字 3。
   */
  private static final int LANDLORD_SLOT_INDEX = 3;

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

  /**
   * ★★ E3：创世发行政府的稳定 id（世界级最小政府；唯一拼写点）。当前市场单一计价货币 {@code silver}， 因此全世界只有一个发行主体声称 silver ——
   * 不静默让三国各自主张同一币种。
   */
  public static final GovernmentId GENESIS_GOVERNMENT_ID = new GovernmentId("world-silver");

  /** 世界级政府在 {@code nationRef} 里的引用（当前不是任何真实国家 id，故用保留字面量）。 */
  public static final String GENESIS_GOVERNMENT_NATION_REF = "world";

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
      // ★★ R2a：CLASS_FIRST 的正式状态（其它 profile 恒为空态；顶层 classFirst 键只在它非空时发出）。
      ClassFirstState classFirst) {

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
            "Seed.profile 不得为 null（缺省用 FoundationProfile.CLASS_FIRST）");
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
      // ★★ R2a：CLASS_FIRST 必须真的种出池与账户 —— 这里 fail-closed，不把"classFirst 留空"的载荷发出去。
      classFirst = classFirst == null ? ClassFirstState.empty() : classFirst;
      if (profile == FoundationProfile.CLASS_FIRST && classFirst.isEmpty()) {
        throw new IllegalArgumentException(
            "CLASS_FIRST profile 的 Seed.classFirst 不得为空（必须真的种出阶层池与家户账户）");
      }
    }

    /**
     * {@code economy.Seed} 的载荷文本（{@code mapId} / {@code rulesVersion} / {@code entries} / {@code
     * markets} 都在顶层）。LEGACY profile 的键集与键序逐字节不变；COMPLETE 追加六个地基键；CLASS_FIRST 追加一个顶层 {@code
     * classFirst} 且不再发旧生产结构。 ★ P3：无 conditions 时 {@code debtContracts}/{@code pledges} 仍是空表、 也不出现
     * {@code testConditions} 键 ⇒ 与 P1 逐字节相同。
     */
    public String economyPayload() {
      return jsonOf(
          mapId,
          entries,
          markets,
          governments,
          genesisEndowment,
          genesisMoneyMilliPerCapita,
          profile,
          debtContracts,
          pledges,
          extraMoneyIssuances,
          conditionReport,
          classFirst);
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
   * 纯函数重载（**包内可见**：用例塞一个 {@code hex -> "plains"} 的替身即可，不必造 {@link GameMap}）—— 与真地图那条走**同一条** {@link
   * #plan}（唯一算一次的地方）。
   */
  static String payload(
      String mapId, List<PopulationGroup> groups, Function<HexCoord, String> terrainOf) {
    return plan(mapId, groups, terrainOf).economyPayload();
  }

  /**
   * 真地图重载（{@code terrainIndex()} 一次物化后 O(1) 查）—— 见 {@link #payload(String, List,
   * GameMap)}；初始禀赋取默认值，profile 缺省 {@link FoundationProfile#LEGACY}。
   */
  public static Seed plan(String mapId, List<PopulationGroup> groups, GameMap map) {
    return plan(mapId, groups, map, genesisMoneyMilliPerCapita(), FoundationProfile.CLASS_FIRST);
  }

  /**
   * ★★ E3：真地图重载 + **初始禀赋参数**（毫/人）。默认重载逐值等于旧行为；本重载只改 {@code INITIAL_ENDOWMENT}
   * 的每人金额，商品/人口/劳动/资产口径一字不动。profile 缺省 {@link FoundationProfile#LEGACY}。
   */
  public static Seed plan(
      String mapId, List<PopulationGroup> groups, GameMap map, long genesisMoneyMilliPerCapita) {
    return plan(mapId, groups, map, genesisMoneyMilliPerCapita, FoundationProfile.CLASS_FIRST);
  }

  /** ★ P1：真地图 + profile（初始禀赋取默认值）；{@link FoundationProfile#LEGACY} 逐值等于旧行为。 */
  public static Seed plan(
      String mapId, List<PopulationGroup> groups, GameMap map, FoundationProfile profile) {
    return plan(mapId, groups, map, genesisMoneyMilliPerCapita(), profile, TestConditions.EMPTY);
  }

  /**
   * ★★ <b>P3：真地图 + 初始禀赋 + profile + 测试条件</b>。要求条件的家户/份额都在本 seed 的格集内（跨 seed 引用没有对侧）；空条件（{@link
   * TestConditions#EMPTY}）逐值等于 P1。
   */
  public static Seed plan(
      String mapId,
      List<PopulationGroup> groups,
      GameMap map,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions) {
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
        },
        genesisMoneyMilliPerCapita,
        profile,
        conditions);
  }

  /** ★★ P1：真地图 + 初始禀赋 + profile 的主入口；P3 起条件缺省为空（逐值等于 P1）。 */
  public static Seed plan(
      String mapId,
      List<PopulationGroup> groups,
      GameMap map,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile) {
    return plan(mapId, groups, map, genesisMoneyMilliPerCapita, profile, TestConditions.EMPTY);
  }

  /** ★★ P3：真地图 + profile + 测试条件（初始禀赋取默认值）—— 载荷便捷入口。 */
  public static String payload(
      String mapId,
      List<PopulationGroup> groups,
      GameMap map,
      FoundationProfile profile,
      TestConditions conditions) {
    return plan(mapId, groups, map, genesisMoneyMilliPerCapita(), profile, conditions)
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
   * <p>★★ <b>R2a：{@code classFirst} 只在 {@link FoundationProfile#CLASS_FIRST} 下发出</b>（其余 profile
   * 即使传入非空状态也不改变旧字节）—— 且为空时 fail-closed（"CLASS_FIRST 必须真的有池和账户"）。
   */
  static String jsonOf(
      String mapId,
      List<Map<String, Object>> entries,
      Map<HexCoord, Market> markets,
      Map<GovernmentId, Government> governments,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      List<Map<String, Object>> debtContracts,
      List<Map<String, Object>> pledges,
      List<Map<String, Object>> extraMoneyIssuances,
      TestConditions.Report conditionReport,
      ClassFirstState classFirst) {
    if (profile == null) {
      throw new IllegalArgumentException("jsonOf 的 profile 不得为 null");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mapId", mapId);
    payload.put("rulesVersion", RULES_VERSION);
    payload.put("entries", entries);
    Map<String, Object> marketNodes = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Market> atHex : markets.entrySet()) {
      marketNodes.put(atHex.getKey().toString(), marketNode(atHex.getValue()));
    }
    payload.put("markets", marketNodes);
    if (profile == FoundationProfile.CLASS_FIRST) {
      if (classFirst == null || classFirst.isEmpty()) {
        throw new IllegalArgumentException("CLASS_FIRST 的 economy.Seed 必须带非空 classFirst（拒绝发出空壳载荷）");
      }
      // ★★ R2a：阶层池状态是 CLASS_FIRST 的生产权威；线格式的唯一拼写点是 economy 的持久 codec。
      payload.put("classFirst", classFirstNode(classFirst));
    }
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
    return ToolSupport.json(payload);
  }

  /**
   * ★★ <b>R2a：{@link ClassFirstState} → 顶层 {@code classFirst} 的线格式节点</b>。
   *
   * <p>★★ <b>为什么不在这里手抄键名</b>：{@code classFirst} 的线格式与快照/变更集**同一份**（{@code EconomyCodec} 的持久绑定点， 含
   * {@code ClassPool} 的显式序列化器与全部 ID 键反序列化器）。本类若自己拼一棵 {@code Map}， 就是同一事实的第二处拼写点 —— 状态 record
   * 加一个字段时不会有人记得改这里，而载荷仍能"看起来对"地发出去。 ⇒ 走一次"只改 classFirst 的最小变更集"编码， 再取出它的值节点：新增字段/改字段名会由 codec
   * 自动带上。
   */
  static JsonNode classFirstNode(ClassFirstState state) {
    Objects.requireNonNull(state, "state");
    if (state.isEmpty()) {
      throw new IllegalArgumentException("classFirstNode 需要非空 ClassFirstState（空态不发顶层键）");
    }
    EconomyChangeSet changes =
        EconomyChangeSet.between(EconomyData.empty(), EconomyData.empty().withClassFirst(state));
    String encoded = new EconomyCodec().encodeChangeSet(changes);
    try {
      JsonNode node =
          SimosObjectMapper.create()
              .readTree(encoded)
              .path("classFirst")
              .path("entries")
              .path("classFirst");
      if (node.isMissingNode() || node.isNull()) {
        throw new IllegalStateException("EconomyCodec 的 classFirst 编码形状与预期不符: " + encoded);
      }
      return node;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("classFirst 线格式解析失败", e);
    }
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

  /** ★★ R2a：同上，但 reason 由调用方给出（CLASS_FIRST 的家户钱包口径与旧"每人禀赋+经营者周转金"不同）。 */
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
      String mapId, List<PopulationGroup> groups, Function<HexCoord, String> terrainOf) {
    return plan(
        mapId, groups, terrainOf, genesisMoneyMilliPerCapita(), FoundationProfile.CLASS_FIRST);
  }

  /** ★ P1：纯函数主入口 + profile（初始禀赋取默认值）。 */
  static Seed plan(
      String mapId,
      List<PopulationGroup> groups,
      Function<HexCoord, String> terrainOf,
      FoundationProfile profile) {
    return plan(mapId, groups, terrainOf, genesisMoneyMilliPerCapita(), profile);
  }

  /**
   * ★★ E3：纯函数主入口 + 初始禀赋参数（{@code genesisMoneyMilliPerCapita}，毫/人；≥ 0）。 初始发行记录的总量按**家户钱包 +
   * 经营者钱包**逐币种汇总，与 actor.Seed 同一份表；profile 缺省 LEGACY。
   */
  static Seed plan(
      String mapId,
      List<PopulationGroup> groups,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita) {
    return plan(
        mapId, groups, terrainOf, genesisMoneyMilliPerCapita, FoundationProfile.CLASS_FIRST);
  }

  /**
   * ★★ P1：纯函数主入口 + 初始禀赋 + profile。{@link FoundationProfile#COMPLETE} 只在 {@link
   * Seed#economyPayload()} 里追加六个地基键；entries/库存/货币/发行记录逐值不变。
   */
  static Seed plan(
      String mapId,
      List<PopulationGroup> groups,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile) {
    return plan(
        mapId, groups, terrainOf, genesisMoneyMilliPerCapita, profile, TestConditions.EMPTY);
  }

  /** ★★ P3：纯函数主入口 + 初始禀赋 + profile + 测试条件（空条件逐值等于 P1）。 */
  static Seed plan(
      String mapId,
      List<PopulationGroup> groups,
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
    // ★★ R2a：CLASS_FIRST 走独立的播种路径 —— 只种阶层池状态 + 最小人口/市场壳，不生成旧产业/关系/unit/
    //   资产份额/劳动配额/成员份额（旧 profile 的这条路径因此逐字节不变）。
    if (profile != FoundationProfile.CLASS_FIRST) {
      throw new IllegalArgumentException("R3a 起只支持 CLASS_FIRST profile: " + profile);
    }
    return planClassFirst(mapId, groups, terrainOf, genesisMoneyMilliPerCapita, conditions);
  }

  // ── R2a：CLASS_FIRST 的播种路径（只种阶层池 + 最小人口/市场壳）──────────────────────────────

  /**
   * ★★ <b>R2a：CLASS_FIRST 的纯函数播种路径</b>。
   *
   * <p>它复用旧路径的人口聚合与开缸库存/货币算法（{@link #ruralCohort} / {@link #urbanCohort}），但出口完全不同：
   *
   * <ol>
   *   <li>{@code entries} 只留 {@code q/r + industries(恒空数组，解析器必填壳) + classes}（人口/账户视图）——
   *       旧生产结构的劳动配额/资产份额/生产单元/成员份额一律不发；{@code relations} 在条目形状里本就不存在（由 industries 派生）；
   *   <li>每个**人口 > 0** 的 {@code ClassRow} 映射到四个阶层池之一，库存/货币逐值喂进 {@link
   *       PilotModel.Household}（引擎构造期聚合到池）；
   *   <li>土地/农具按显式所有权表切给池、再按池内人口权重挂到各家家户：总量逐值来自本 seed 的可耕地（毫亩 → 亩）与作坊工具存量（毫工具 → 件）；
   *   <li>独立 GOV 放贷窗口：大量货币、零商品；与 actor.Seed 的 GOV 账户同额，并落一条 INITIAL_ENDOWMENT 发行记录；
   *   <li>用 {@link ClassFirstPilotEngine} 的播种构造器 + {@link ClassFirstPilotEngine#snapshot()} 得到权威
   *       {@link ClassFirstState}（含 schema / bounds / policy / meta.config）。
   * </ol>
   *
   * <p>★★ <b>量纲（与 actor 账本的关系）</b>：{@code GRAIN}/{@code CLOTH}/{@code MONEY} 在池与 actor 账本之间
   * <b>1:1</b>（同一份 {@code Seed.householdStocks}/{@code householdMoney} 的数字，不换标度、不复制计算）；{@code
   * OWNED_LAND} 由毫亩折亩、{@code TOOLS} 由毫工具折件（引擎的地租/农艺参数按"亩/件"读才有量级）。FIBER/IRON 不在 classfirst 资产维度里 ⇒
   * 它们只留在 actor 家户账本，不塞进池（见类注与报告）。
   *
   * <p>★★ <b>权威关系</b>：池是生产结算的唯一权威（{@code ClassPool} 的 {@code addStock}/{@code takeStock} 是唯一写口）；
   * {@link io.mosire.simos.economy.classfirst.HouseholdProductionAccount}
   * 只带人口/劳动/份额（<b>没有库存字段</b>，因此不存在池内第二本货账）； actor 家户 {@code GoodsAccount} 是与池同源、逐家户展开的账本（R2b 把
   * {@code settleOneDay} 的账户增量写回它）。
   */
  static Seed planClassFirst(
      String mapId,
      List<PopulationGroup> groups,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      TestConditions conditions) {
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
        hexes.add(hex);
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException("CLASS_FIRST 需要至少一个有人口的格（批次的 residence 集合为空，无法播种阶层池）");
    }

    List<Map<String, Object>> entries = new ArrayList<>(hexes.size());
    Map<HouseholdId, Map<CommodityId, Long>> householdStocks = new LinkedHashMap<>();
    Map<HouseholdId, HexCoord> householdLocations = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    List<ClassFirstHouseholdDraft> drafts = new ArrayList<>();
    List<OperatorSeed> operatorShells = new ArrayList<>();
    long totalLandMilliMu = 0L;
    long totalWorkshops = 0L;

    for (HexCoord hex : hexes) {
      List<PopulationGroup> ruralPool = ruralByHex.getOrDefault(hex, List.of());
      List<PopulationGroup> urbanPool = urbanByHex.getOrDefault(hex, List.of());
      IndustryId farmId = IndustryHexKeys.id(FARM, hex.q(), hex.r());
      IndustryId weaveId = IndustryHexKeys.id(WEAVE, hex.q(), hex.r());
      IndustryId craftId = IndustryHexKeys.id(CRAFT, hex.q(), hex.r());
      long landMilliMu = landMilliMuOf(terrainOf.apply(hex));
      long workshops = populationOf(urbanPool) / URBAN_CAPITA_PER_WORKSHOP;
      boolean hasRural = populationOf(ruralPool) > 0L;
      boolean hasCraft = populationOf(urbanPool) > 0L;

      // ★ 复用旧的 cohort 辅助方法：它顺带产出 actor 侧的位置/库存/货币三张表与 classes 行。成员份额 CLASS_FIRST
      //   不发出（旧结算引擎（R3a 已删除） 的耦合面），故给一个丢弃桶。
      List<Map<String, Object>> classes = new ArrayList<>(2 * CLASS_IDS.length);
      List<Map<String, Object>> ignoredMemberships = new ArrayList<>();
      classes.addAll(
          ruralCohort(
              hex,
              ruralPool,
              landMilliMu,
              householdLocations,
              householdStocks,
              householdMoney,
              ignoredMemberships,
              genesisMoneyMilliPerCapita));
      classes.addAll(
          urbanCohort(
              hex,
              urbanPool,
              workshops,
              householdLocations,
              householdStocks,
              householdMoney,
              ignoredMemberships,
              genesisMoneyMilliPerCapita));
      for (Map<String, Object> row : classes) {
        long population = ((Number) row.get("population")).longValue();
        if (population <= 0L) {
          continue; // 0 人口行没有可挂的生产账户（PilotModel.Household 的人口守卫是 >= 1）
        }
        long laborMilli = ((Number) row.get("laborMilli")).longValue();
        drafts.add(
            new ClassFirstHouseholdDraft(
                HouseholdId.parse((String) row.get("householdId")),
                classFirstPositionOf((String) row.get("slot")),
                population,
                laborMilli * 1000L / population));
      }

      totalLandMilliMu += landMilliMu;
      totalWorkshops += workshops;
      // ★★ 旧产业的经营者身份保留（actor.Seed 仍建"经营者账"），但开缸商品/货币**空**：CLASS_FIRST 的生产库存
      //    权威在阶层池。下面的土地/农具总量正是从它们原来的账/产能搬进池的（总量不变、持有者变）。
      operatorShells.add(classFirstOperatorShell(farmId, REGIME_FEUDAL, hex));
      if (hasRural) {
        operatorShells.add(classFirstOperatorShell(weaveId, REGIME_HOUSEHOLD, hex));
      }
      if (hasCraft) {
        operatorShells.add(classFirstOperatorShell(craftId, REGIME_HANDICRAFT, hex));
      }

      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("q", hex.q());
      entry.put("r", hex.r());
      // ★ 空壳：{@code EconomyPayloads} 把 industries 当**必填数组**（缺键即拒）。空数组 = 本格没有产业，
      //   不是"没给这个键"——CLASS_FIRST 的生产结构在顶层 classFirst 里，不在这里。
      entry.put("industries", List.of());
      entry.put("classes", classes);
      entries.add(entry);
      // ★ 有 entry 才有市场（与旧路径同口径；本批保留市场表供读口/后续市场阶段使用）。
      markets.put(hex, MARKET_FACTORY);
    }

    // ★★ E3 口径与旧路径一致：INITIAL_ENDOWMENT 取**条件注入之前**的家户+经营者钱包；条件注入的货币走独立
    //    FISCAL_ISSUE 审计（{@link #applyTestConditions}），绝不混进"每人禀赋"那条记录。
    Map<CurrencyId, Long> genesisEndowment = genesisEndowmentOf(householdMoney, operatorShells);

    // ★★ P3 条件：外部注入的家户库存/货币要先进**同一份表**，再据此建池（同一处接缝，不另算一遍）。
    AppliedConditions applied =
        applyTestConditions(
            mapId,
            entries,
            householdStocks,
            householdMoney,
            FoundationProfile.CLASS_FIRST,
            conditions);

    // 土地/农具：总和来自本 seed 的可耕地与作坊工具存量；按所有权表切到旧槽位，再映射到四个池位置。
    long totalLandMu = totalLandMilliMu / MILLI_MU_PER_MU;
    long totalTools =
        totalWorkshops * toolPerWorkshopMilli() / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
    long[] landBySlot = splitByShares(totalLandMu, CLASS_FIRST_LAND_OWNERSHIP_PER_MILLE);
    long[] toolsBySlot = splitByShares(totalTools, CLASS_FIRST_TOOLS_OWNERSHIP_PER_MILLE);
    int positions = PilotModel.classPositions().size();
    long[] landByPosition = new long[positions];
    long[] toolsByPosition = new long[positions];
    for (int slot = 0; slot < CLASS_IDS.length; slot++) {
      int index = classFirstPositionIndex(CLASS_FIRST_POSITION_BY_SLOT[slot]);
      landByPosition[index] += landBySlot[slot];
      toolsByPosition[index] += toolsBySlot[slot];
    }

    // 池内再按人口权重把土地/农具切给各家户（Σ 逐池保真；池总量因此逐值落在 Pool.stock(OWNED_LAND/TOOLS)）。
    List<PilotModel.Household> households = new ArrayList<>(drafts.size());
    for (int index = 0; index < positions; index++) {
      String positionId = PilotModel.classPositions().get(index).id();
      List<ClassFirstHouseholdDraft> members = new ArrayList<>();
      for (ClassFirstHouseholdDraft draft : drafts) {
        if (draft.positionId().equals(positionId)) {
          members.add(draft);
        }
      }
      if (members.isEmpty()) {
        if (landByPosition[index] != 0L || toolsByPosition[index] != 0L) {
          throw new IllegalStateException(
              "CLASS_FIRST：位置 "
                  + positionId
                  + " 没有人口，无法承载其土地/农具份额（land="
                  + landByPosition[index]
                  + "，tools="
                  + toolsByPosition[index]
                  + "）—— 请扩大 seed 人口或调整所有权表");
        }
        continue;
      }
      long[] weights = new long[members.size()];
      for (int i = 0; i < members.size(); i++) {
        weights[i] = members.get(i).population();
      }
      long[] landShares = splitProportional(landByPosition[index], weights);
      long[] toolShares = splitProportional(toolsByPosition[index], weights);
      for (int i = 0; i < members.size(); i++) {
        ClassFirstHouseholdDraft draft = members.get(i);
        households.add(
            classFirstHousehold(
                draft,
                householdStocks.get(draft.householdId()),
                householdMoney.get(draft.householdId()),
                landShares[i],
                toolShares[i]));
      }
    }

    // ★★ 独立 GOV 放贷窗口：货币量按**条件注入之前的**家户钱包派生（不另造人口口径；也不把条件注入算两遍）。
    long householdSilver = genesisEndowment.getOrDefault(MARKET_NUMERAIRE, 0L);
    long lenderMoney =
        Math.multiplyExact(householdSilver, CLASS_FIRST_LENDER_MONEY_PER_MILLE_OF_HOUSEHOLD)
            / 1000L;
    PilotModel.Lender lender = classFirstLender(lenderMoney);
    ClassFirstPilotEngine engine =
        new ClassFirstPilotEngine(classFirstConfig(lender), households, List.of(lender));
    ClassFirstState state = engine.snapshot();
    if (state.isEmpty()) {
      throw new IllegalStateException("CLASS_FIRST 播种没有产出非空 ClassFirstState");
    }

    List<OperatorSeed> operators = new ArrayList<>(operatorShells);
    operators.add(classFirstLenderOperator(entries, lenderMoney));
    List<Map<String, Object>> extraMoneyIssuances = new ArrayList<>(applied.extraMoneyIssuances());
    if (lenderMoney > 0L) {
      extraMoneyIssuances.add(classFirstLenderIssuance(mapId, entries, lenderMoney));
    }
    return new Seed(
        mapId,
        entries,
        markets,
        householdLocations,
        householdStocks,
        householdMoney,
        operators,
        Map.of(GENESIS_GOVERNMENT_ID, genesisGovernment()),
        genesisEndowment,
        genesisMoneyMilliPerCapita,
        FoundationProfile.CLASS_FIRST,
        applied.debtContracts(),
        applied.pledges(),
        extraMoneyIssuances,
        applied.report(),
        state);
  }

  /** R2a：CLASS_FIRST 的一个家户播种中间体（库存/货币在构造 {@link PilotModel.Household} 时从同一份表里读）。 */
  private record ClassFirstHouseholdDraft(
      HouseholdId householdId, String positionId, long population, long laborPerCapita) {}

  /** R2a：社会阶层槽位（{@link #CLASS_IDS} 序）→ 阶层池位置；未知槽位 fail-closed。 */
  private static String classFirstPositionOf(String slot) {
    for (int i = 0; i < CLASS_IDS.length; i++) {
      if (CLASS_IDS[i].equals(slot)) {
        return CLASS_FIRST_POSITION_BY_SLOT[i];
      }
    }
    throw new IllegalStateException("CLASS_FIRST 的词表外社会阶层槽位（拒绝臆造映射）: " + slot);
  }

  /**
   * ★★ <b>R2c：classfirst 阶层池位置 → 社会阶层槽位</b>（{@link #classFirstPositionOf} 的逆，唯一拼写点）。
   *
   * <p>只读 {@code ClassRow} 投影要用它把"家户当前所属的池"翻回 {@code view.stratum}；映射本身仍只写在本类 {@link
   * #CLASS_FIRST_POSITION_BY_SLOT} 一行，投影侧不另拍一份。
   */
  public static SocialClassId classFirstStratumOf(String positionId) {
    for (int i = 0; i < CLASS_IDS.length; i++) {
      if (CLASS_FIRST_POSITION_BY_SLOT[i].equals(positionId)) {
        return new SocialClassId(CLASS_IDS[i]);
      }
    }
    throw new IllegalStateException("CLASS_FIRST 的未知阶层池位置（无法投影为 ClassRow.view）: " + positionId);
  }

  /** R2a：阶层池位置 → {@link PilotModel#classPositions()} 的下标（所有权数组的下标换算）。 */
  private static int classFirstPositionIndex(String positionId) {
    for (int i = 0; i < PilotModel.classPositions().size(); i++) {
      if (PilotModel.classPositions().get(i).id().equals(positionId)) {
        return i;
      }
    }
    throw new IllegalStateException("CLASS_FIRST 的未知阶层池位置: " + positionId);
  }

  /** R2a：一行家户草稿 + **同一份**开缸库存/货币 → 引擎的 {@link PilotModel.Household}（只允许 grain/cloth 进池）。 */
  private static PilotModel.Household classFirstHousehold(
      ClassFirstHouseholdDraft draft,
      Map<CommodityId, Long> stock,
      Map<CurrencyId, Long> wallet,
      long land,
      long tools) {
    Map<String, Long> goods = new LinkedHashMap<>();
    long grain = stock == null ? 0L : stock.getOrDefault(new CommodityId(COMMODITY_GRAIN), 0L);
    long cloth = stock == null ? 0L : stock.getOrDefault(new CommodityId(COMMODITY_CLOTH), 0L);
    if (grain > 0L) {
      goods.put(PilotModel.GRAIN, grain);
    }
    if (cloth > 0L) {
      goods.put(PilotModel.CLOTH, cloth);
    }
    long money = 0L;
    if (wallet != null) {
      for (Map.Entry<CurrencyId, Long> entry : wallet.entrySet()) {
        if (entry.getValue() == 0L) {
          continue;
        }
        if (!entry.getKey().equals(MARKET_NUMERAIRE)) {
          throw new IllegalStateException(
              "CLASS_FIRST 只支持单一计价货币 "
                  + MARKET_NUMERAIRE.value()
                  + "；家户 "
                  + draft.householdId()
                  + " 另有 "
                  + entry.getKey().value());
        }
        money = Math.addExact(money, entry.getValue());
      }
    }
    return new PilotModel.Household(
        draft.householdId().value(),
        "CLASS_FIRST 家户 " + draft.householdId().value(),
        draft.positionId(),
        draft.population(),
        draft.laborPerCapita(),
        goods,
        money,
        land,
        tools,
        CLASS_FIRST_HOUSEHOLD_PARTICIPATION_PER_MILLE);
  }

  /** R2a：旧产业经营者身份的**零余额壳**（actor.Seed 仍建经营者账；库存权威已搬到阶层池）。 */
  private static OperatorSeed classFirstOperatorShell(IndustryId id, String regime, HexCoord hex) {
    return new OperatorSeed(
        RegimeOperators.defaultOperator(new RegimeId(regime), id),
        hex,
        id.value() + " 经营者",
        Map.of(),
        Map.of());
  }

  /** R2a：独立 GOV 放贷窗口的账户（actor.Seed 侧：货币 = 池外放贷资金，商品空 = 0 流动性）。 */
  private static OperatorSeed classFirstLenderOperator(
      List<Map<String, Object>> entries, long lenderMoney) {
    Map<String, Object> first = entries.get(0);
    HexCoord at =
        new HexCoord(((Number) first.get("q")).intValue(), ((Number) first.get("r")).intValue());
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>();
    if (lenderMoney > 0L) {
      wallet.put(MARKET_NUMERAIRE, lenderMoney);
    }
    return new OperatorSeed(
        GovernmentActors.of(GENESIS_GOVERNMENT_ID), at, "GOV 放贷窗口（CLASS_FIRST）", Map.of(), wallet);
  }

  /** R2a：GOV 放贷资金的 {@code INITIAL_ENDOWMENT} 审计节点（与 actor.Seed 的 GOV 账户同额）。 */
  private static Map<String, Object> classFirstLenderIssuance(
      String mapId, List<Map<String, Object>> entries, long lenderMoney) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put(
        "id",
        new MoneyIssuanceId(
                "endowment-classfirst-lender-"
                    + mapId
                    + "-"
                    + seedAnchor(entries)
                    + "-"
                    + MARKET_NUMERAIRE.value())
            .value());
    node.put("governmentId", GENESIS_GOVERNMENT_ID.value());
    node.put("currency", MARKET_NUMERAIRE.value());
    node.put("amount", lenderMoney);
    node.put("kind", "INITIAL_ENDOWMENT");
    node.put(
        "reason",
        "CLASS_FIRST：GOV 放贷窗口初始资金（独立放贷主体，不属任何阶层池；" + "同时记入 actor.Seed 的 GOV 账户；商品/流动性为 0）");
    return node;
  }

  /** R2a：独立 GOV 放贷主体（大量货币、零商品；不塞进任何阶层池）。 */
  private static PilotModel.Lender classFirstLender(long lenderMoney) {
    return new PilotModel.Lender(
        CLASS_FIRST_LENDER_ID,
        lenderMoney,
        Map.of(),
        CLASS_FIRST_LENDER_INTEREST_PER_MILLE,
        CLASS_FIRST_LENDER_NEXT_DUE_TICK,
        CLASS_FIRST_LENDER_COLLECTION_POWER);
  }

  /** R2a：佃农制出厂配置（classfirst 包的默认 schema/bounds/mobility；催收政策由本类的常量显式给出）。 */
  private static PilotConfig classFirstConfig(PilotModel.Lender lender) {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            CLASS_FIRST_COLLECTION_THRESHOLD,
            CLASS_FIRST_COLLECTION_TRIGGER_RATIO_PER_MILLE,
            CLASS_FIRST_COLLECTION_RATIO_PER_MILLE,
            CLASS_FIRST_LAND_PRICE_PER_UNIT,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    return PilotConfig.tenancyAgriculture(lender, policy);
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

  /** E3：世界级最小发行政府（唯一发行主体声称当前单一计价货币 silver）。 */
  static Government genesisGovernment() {
    return new Government(
        GENESIS_GOVERNMENT_ID,
        GENESIS_GOVERNMENT_NATION_REF,
        GovernmentActors.of(GENESIS_GOVERNMENT_ID),
        Set.of(MARKET_NUMERAIRE));
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
   * ★★ <b>S1：一组家户的成员份额（逐 lot 切）</b>：对池内每个批次，把它的人数在**同居住类型/同格的四个家户** 之间按行人口权重用最大余数法分配。
   *
   * <p>★★ <b>为什么按 lot 切而不是逐家户切</b>：要守的跨切片不变量是<b>逐 lot</b> 的 {@code Σcount ==
   * PopulationGroup.count(lot)}。逐家户各自按 lot 权重切会在"某一家户人很少、而 lot 权重很偏"时 把同一 lot 的余数都堆到一个方向，逐 lot
   * 之和就可能偏离（小样本下实测可差若干人）。按 lot 切让每个 lot 的分配 <b>构造性</b>地等于它的人数；代价是同一家户跨 lot 的份额之和可能与行人口差几个舍入人 —— 全局
   * Σ 仍逐值相等。
   *
   * <p>★ 权重 = 该家户的**行人口**（四个阶层份额切出来的 {@code people[i]}）；人口为 0 的空壳家户不落份额（它收不下人）。 池空 / 池人口为 0 ⇒ 不发。
   */
  private static void appendMembershipsByLot(
      List<Map<String, Object>> memberships,
      List<PopulationGroup> pool,
      List<HouseholdId> householdKeys,
      long[] householdPopulation) {
    if (pool.isEmpty() || householdKeys.size() != householdPopulation.length) {
      return;
    }
    long totalHousehold = 0L;
    for (long population : householdPopulation) {
      totalHousehold = Math.addExact(totalHousehold, population);
    }
    if (totalHousehold <= 0L) {
      return;
    }
    for (PopulationGroup group : pool) {
      if (group.count() <= 0L) {
        continue;
      }
      long[] parts =
          io.mosire.simos.util.economy.ProportionalSplit.byDenominator(
              group.count(), householdPopulation, totalHousehold);
      for (int i = 0; i < householdKeys.size(); i++) {
        if (parts[i] <= 0L) {
          continue;
        }
        Map<String, Object> membership = new LinkedHashMap<>();
        membership.put("lot", group.id().value());
        membership.put("household", householdKeys.get(i).value());
        membership.put("count", parts[i]);
        memberships.add(membership);
      }
    }
  }

  /**
   * 配额 id 的**唯一拼写点**：{@code alloc-<产业 id>-<批次 id>}。
   *
   * <p>★ **确定性**：{@code (产业, 批次)} 的纯函数 ⇒ 同一对必然给出同一个 id（重放/分支可比），且同一对不会重复。 ★ **不含 {@code "."}**：产业
   * id 形如 {@code farm@0_0}、批次 id 形如 {@code rural:0_0:MALE:1}，两者都不含点 ⇒ 地址 {@code
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
  private static List<Map<String, Object>> ruralCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      long landMilliMu,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      List<Map<String, Object>> memberships,
      long genesisMoneyMilliPerCapita) {
    // ★★ **纤维的去处**：旧版按阶层份额落在那四行**纺织行**上，H0.2 起并入**农村四行**（同一批人的同一本账）。
    Map<String, long[]> goods = new LinkedHashMap<>();
    goods.put(COMMODITY_FIBER, splitByShares(fiberStockMilli(landMilliMu), CLASS_SHARE_PER_MILLE));
    return cohortGroup(
        hex,
        ResidenceKind.RURAL,
        pool,
        goods,
        locations,
        stocks,
        money,
        memberships,
        genesisMoneyMilliPerCapita);
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
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      List<Map<String, Object>> memberships,
      long genesisMoneyMilliPerCapita) {
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
        goods,
        locations,
        stocks,
        money,
        memberships,
        genesisMoneyMilliPerCapita);
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
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      List<Map<String, Object>> memberships,
      long genesisMoneyMilliPerCapita) {
    long[] people = splitByShares(populationOf(pool), CLASS_SHARE_PER_MILLE);
    long poolLabor = laborMilli(pool);
    long poolCount = populationOf(pool);
    List<Map<String, Object>> rows = new ArrayList<>(CLASS_IDS.length);
    List<HouseholdId> householdKeys = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★★ **H1：这个家户的开缸库存 → actor 侧的账本**（键 = {@link HouseholdActors#of} 的那个家户身份）。
      //   ★ **空账也落键**（人口 0 ⇒ 余额全 0）：读口因此读得到"这个家户在这一格有一本账"（既定口径），
      //     而"账本为空"与"这一格没有这个家户"是两件事。
      HouseholdId key = HouseholdId.ofSeed(hex, residence, new SocialClassId(CLASS_IDS[i]));
      locations.put(key, hex);
      householdKeys.add(key);
      stocks.put(key, openingStock(people[i], CLASS_IDS[i], goodsByClass, i));
      // ★★ H4：创世货币禀赋走**同一本账**（actor 侧的同一个 {@code GoodsAccount}）—— 见 {@link
      // #genesisMoneyMilliPerCapita}。
      money.put(key, genesisMoney(people[i], genesisMoneyMilliPerCapita));
      rows.add(
          cohortRow(
              key,
              residence,
              CLASS_IDS[i],
              people[i],
              CLASS_LABOR_PER_MILLE[i],
              poolLabor,
              poolCount));
    }
    // ★★ S1.4：份额按**逐 lot** 切（不是逐家户各自切）：每个 lot 的 group.count 在本组四个家户之间按行人口权重
    //   用最大余数法分配 ⇒ **逐 lot 严格 Σcount == 该批次社会人数**（seed 出口自检的判据因此构造性成立）。
    //   ★ 代价如实记：同一家户跨 lot 的份额之和可能与它的行人口差几个人（最大余数法的舍入），
    //     而"Σ 全部份额 == Σ 全部行人口"仍是逐值相等（EconomyData 的全局守卫照过）。
    appendMembershipsByLot(memberships, pool, householdKeys, people);
    return rows;
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
      HouseholdId householdId,
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
    // ★★ S1：家户稳定身份随行发出（载荷把它带进 EconomyData；运行期不再从视图派生 actor id）。
    row.put("householdId", householdId.value());
    row.put("residence", residence.value());
    row.put("slot", slot);
    row.put("population", population);
    row.put("laborMilli", rowLaborMilli(population, poolLaborMilli, poolCount));
    row.put("participationPerMille", participationPerMille);
    // ★★ **H1：这里没有 {@code goods} 键**（裁定 D3-C/K1）—— 商品库存的唯一持久真源是 actor 切片里该家户的
    //   {@code GoodsAccount}，开缸余额由 {@link #openingStock} 一次算好、经 {@link HouseholdSeeder} 落成那本账。
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
   * 创世时钟（C5 过渡：C5 起换成 CalendarService 注入；缺省值相同）：创世的年龄档判定一律在 **tick 0** 折算，缺省 {@link
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
      FoundationProfile profile,
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
      if (profile == FoundationProfile.CLASS_FIRST) {
        throw new IllegalArgumentException(
            "test-condition: "
                + where
                + " 需要 complete profile 的旧资产规则；R3a 起只支持 CLASS_FIRST"
                + "（不种旧 mode/资产规则，质押没有落点）—— 该条件在 class-first 世界不适用");
      }
      ProductionModeId defaultMode = LegacyClassStructure.defaultModeId();
      if (!pledge.modeId().equals(defaultMode)) {
        throw new IllegalArgumentException(
            "test-condition: "
                + where
                + " 指名的 mode 不存在（本 profile 只种 "
                + defaultMode.value()
                + "）: "
                + pledge.modeId());
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
}
