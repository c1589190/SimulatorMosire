package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborTimeTable;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreas;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * ★★ **R2a 生成器**（聚合式经济重设计 §十「验收目标 A」）：把一次世界生成已经算好的 {@link SettlementPlan}（逐格农村人口 + 城市表）翻成**一条**
 * {@code economy.Seed} 命令的载荷。
 *
 * <p>★★ **H1 起它同时产出家户的创世库存**（裁定 D3-C / K1；见 {@link Seed}）：商品库存的**唯一持久真源**是 actor 切片里 家户的 {@code
 * HouseholdInventory}，故"每格两组四行的开缸余额"不再写进阶层行，而是由 {@link #openingStock} 一次算好后交回 —— {@link
 * HouseholdSeeder} 用**同一份**结果播 {@code actor.Seed}（"一次算出、同一份喂两条命令"，与人口批次那条接缝同款）。
 *
 * <p>★★ **R1（T4）起人口一律取自 {@link PopulationGroup} 批次列表**（{@link PopulationSeeder#groups} 造的那一份，
 * 同一份也喂给 {@code social.SeedGroups}）：逐格人数 = {@code Σ count}，农村/城镇两池由批次 id 的前缀分 （{@link
 * PopulationLots#isUrban}）。**不再读 {@link io.mosire.simos.social.gen.SettlementPlan}** —— 两条命令读同一份列表，
 * "Σ group == 经济侧总人口"因此是构造性成立的。本类只做"分配 / 换算"，不做人口估计。
 *
 * <p>★★ **R2 起"这个批次属于哪个产业"不再隐含在批次上，而由 {@code HouseholdLaborCommitment} 表达**（第三阶段设计稿 §四）：本类
 * **同时**产出该格的 {@code laborSupply}（各批次的毛劳动与两项扣除）与 {@code allocations}（各批次把多少劳动给了哪个主体 —— 创世 = "农村批次 →
 * 农业（庄园）/ 城镇批次 → 手工业（作坊）"的 1000‰ 配额）。★ 配额之和**逐值等于**改口径前的当日劳动 （见 {@link
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
 * 是同一批农村人的第二本账；它承载的织机与纤维按"产能 → 产业、商品 → 家户"两条规矩各自归位（见 {@code IndustrySeeder.householdWeaving}）。 ★
 * **每个产业都不再持有 {@code classes}**：产业只说"制度 + 配方 + 本格产能"，家户账只说"这批人有多少粮/多少活"。
 *
 * <p>★ **参数默认值全部取自 §十 的口径表**（{@code 一切数字来自场景参数}）：它们在此以**具名常量**出现并各自注明来源—— 冻结输入 {@code
 * config/worldgen/v17levant-nations.json} 里没有这些字段，故"场景参数"就是本节钉住的默认值。
 *
 * <p>★ **不做的**（§十"明确不做"）：日结算（消费/进度/产出/分配）属 R3a/R4a ⇒ 本类只种**静态**初值（{@code progressDays=0}、 {@code
 * money=0}、无债务）；{@code dailyInputPerUnit}/{@code dailyLaborPerUnit} 没有依据 ⇒ 0（不臆造）。
 */
public final class EconomySeeder {
  /**
   * ★★ <b>经济地基 profile</b>（2026-10-09 起唯一值）：{@link #PRODUCTION_RUNTIME} —— 旧完整生产路径 +
   * 默认生产方式目录/资产规则。★★ P2-C §13.7 起：{@code governmentRef} 给了才建那个政府家户 （国库 = 它的家户账户；demo
   * 世界才带周期铸币/发债），没给则只落一条不持户的创世审计主体 （{@code GovernmentSeeder.genesisAuditGovernment}）给
   * INITIAL_ENDOWMENT 归属 —— 单位政府由 {@code economy.RegisterGovernment} 按 GOV 单位稳定 id 建立。
   *
   * <p>★ 旧 {@code class-first} / {@code legacy} / {@code complete} 与 {@code
   * production-runtime-government} 线格式名不再可解析（fail-closed，不把缺省猜成旧 profile）。
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

    /** 按线格式名解析：只接受 {@code "production-runtime"}；其余/空白一律 fail-closed。 */
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

  // ── 公开常量：声明迁到 WorldBalanceSeeder；门面保留同名别名，外部读写路径不变 ──

  public static final String PRODUCTION_RUNTIME_SEED_REASON =
      WorldBalanceSeeder.PRODUCTION_RUNTIME_SEED_REASON;
  public static final String FARM = WorldBalanceSeeder.FARM;
  public static final String CRAFT = WorldBalanceSeeder.CRAFT;
  public static final String WEAVE = WorldBalanceSeeder.WEAVE;
  public static final String TRADE = WorldBalanceSeeder.TRADE;
  public static final List<String> SEED_ACCEPTED_RIGHT_KINDS_OWNED =
      WorldBalanceSeeder.SEED_ACCEPTED_RIGHT_KINDS_OWNED;
  public static final List<String> SEED_ACCEPTED_RIGHT_KINDS_OWNED_OR_TENANCY =
      WorldBalanceSeeder.SEED_ACCEPTED_RIGHT_KINDS_OWNED_OR_TENANCY;
  public static final long LABOR_MILLI_PER_TRADE_UNIT =
      WorldBalanceSeeder.LABOR_MILLI_PER_TRADE_UNIT;
  public static final long HAUL_PER_TRADE_UNIT_CYCLE = WorldBalanceSeeder.HAUL_PER_TRADE_UNIT_CYCLE;
  public static final long HAUL_TOOL_PER_MILLE_OF_SERVICE =
      WorldBalanceSeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE;
  public static final long TOOL_MILLI_PER_TRADE_UNIT_CYCLE =
      WorldBalanceSeeder.TOOL_MILLI_PER_TRADE_UNIT_CYCLE;
  public static final int TRADE_SPLIT_MEANS_PER_MILLE =
      WorldBalanceSeeder.TRADE_SPLIT_MEANS_PER_MILLE;
  public static final int TRADE_SPLIT_LABOR_PER_MILLE =
      WorldBalanceSeeder.TRADE_SPLIT_LABOR_PER_MILLE;
  public static final MerchantPolicy.MerchantTier MERCHANT_TIER = WorldBalanceSeeder.MERCHANT_TIER;
  public static final String REGIME_FEUDAL = WorldBalanceSeeder.REGIME_FEUDAL;
  public static final String REGIME_HANDICRAFT = WorldBalanceSeeder.REGIME_HANDICRAFT;
  public static final String REGIME_HOUSEHOLD = WorldBalanceSeeder.REGIME_HOUSEHOLD;
  public static final int CYCLE_DAYS = WorldBalanceSeeder.CYCLE_DAYS;
  public static final long FIRST_PERIOD = WorldBalanceSeeder.FIRST_PERIOD;
  public static final long MU_PER_HEX = WorldBalanceSeeder.MU_PER_HEX;
  public static final long GRAIN_OUTPUT_PER_MU = WorldBalanceSeeder.GRAIN_OUTPUT_PER_MU;
  public static final long SEED_MILLI_PER_MU = WorldBalanceSeeder.SEED_MILLI_PER_MU;
  public static final long LAND_MU_PER_LABOR = WorldBalanceSeeder.LAND_MU_PER_LABOR;
  public static final long LABOR_MILLI_PER_MU = WorldBalanceSeeder.LABOR_MILLI_PER_MU;
  public static final long FIBER_OUTPUT_PER_MU = WorldBalanceSeeder.FIBER_OUTPUT_PER_MU;
  public static final long RURAL_CAPITA_PER_LOOM = WorldBalanceSeeder.RURAL_CAPITA_PER_LOOM;
  public static final long LABOR_MILLI_PER_LOOM = WorldBalanceSeeder.LABOR_MILLI_PER_LOOM;
  public static final long CLOTH_PER_LOOM_PER_CYCLE = WorldBalanceSeeder.CLOTH_PER_LOOM_PER_CYCLE;
  public static final long URBAN_CAPITA_PER_WORKSHOP = WorldBalanceSeeder.URBAN_CAPITA_PER_WORKSHOP;
  public static final long LABOR_MILLI_PER_WORKSHOP = WorldBalanceSeeder.LABOR_MILLI_PER_WORKSHOP;
  public static final long CLOTH_PER_WORKSHOP_PER_CYCLE =
      WorldBalanceSeeder.CLOTH_PER_WORKSHOP_PER_CYCLE;
  public static final long TOOL_PER_WORKSHOP_PER_CYCLE =
      WorldBalanceSeeder.TOOL_PER_WORKSHOP_PER_CYCLE;
  public static final long FIBER_MILLI_PER_CLOTH = WorldBalanceSeeder.FIBER_MILLI_PER_CLOTH;
  public static final long IRON_MILLI_PER_TOOL = WorldBalanceSeeder.IRON_MILLI_PER_TOOL;
  public static final long TOOL_MILLI_PER_WORKSHOP_CYCLE =
      WorldBalanceSeeder.TOOL_MILLI_PER_WORKSHOP_CYCLE;
  public static final long GOODS_UNITS_PER_HAUL = WorldBalanceSeeder.GOODS_UNITS_PER_HAUL;
  public static final long MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI =
      WorldBalanceSeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI;
  public static final String ACTIVITY_WEAVE = WorldBalanceSeeder.ACTIVITY_WEAVE;
  public static final int MINT_LABOR_SHARE_PER_MILLE =
      WorldBalanceSeeder.MINT_LABOR_SHARE_PER_MILLE;
  public static final int WEAVE_SHARE_PER_MILLE = WorldBalanceSeeder.WEAVE_SHARE_PER_MILLE;
  public static final int SECONDARY_PER_MILLE = WorldBalanceSeeder.SECONDARY_PER_MILLE;
  public static final int TENANT_RENT_PER_MILLE = WorldBalanceSeeder.TENANT_RENT_PER_MILLE;
  public static final int DISPLACED_SEED_PER_MILLE = WorldBalanceSeeder.DISPLACED_SEED_PER_MILLE;
  public static final Map<SocialClassId, Integer> INITIAL_RATION_DAYS_BY_CLASS =
      WorldBalanceSeeder.INITIAL_RATION_DAYS_BY_CLASS;
  public static final int GENESIS_GRAIN_RESERVE_MULTIPLIER =
      WorldBalanceSeeder.GENESIS_GRAIN_RESERVE_MULTIPLIER;
  public static final HouseholdLaborTimeTable HOUSEHOLD_LABOR_TIME_TABLE =
      WorldBalanceSeeder.HOUSEHOLD_LABOR_TIME_TABLE;
  public static final String COMMODITY_GRAIN = WorldBalanceSeeder.COMMODITY_GRAIN;
  public static final String COMMODITY_CLOTH = WorldBalanceSeeder.COMMODITY_CLOTH;
  public static final String COMMODITY_FIBER = WorldBalanceSeeder.COMMODITY_FIBER;
  public static final String COMMODITY_TOOL = WorldBalanceSeeder.COMMODITY_TOOL;
  public static final String COMMODITY_IRON = WorldBalanceSeeder.COMMODITY_IRON;
  public static final String COMMODITY_HAUL = WorldBalanceSeeder.COMMODITY_HAUL;
  public static final String COMMODITY_TRANSPORT_HORSE =
      WorldBalanceSeeder.COMMODITY_TRANSPORT_HORSE;
  public static final long TRANSPORT_HORSE_OWNER_STOCK_MILLI =
      WorldBalanceSeeder.TRANSPORT_HORSE_OWNER_STOCK_MILLI;
  public static final CurrencyId MARKET_NUMERAIRE = WorldBalanceSeeder.MARKET_NUMERAIRE;
  public static final long MARKET_PRICE_GRAIN = WorldBalanceSeeder.MARKET_PRICE_GRAIN;
  public static final long MARKET_PRICE_CLOTH = WorldBalanceSeeder.MARKET_PRICE_CLOTH;
  public static final long MARKET_PRICE_FIBER = WorldBalanceSeeder.MARKET_PRICE_FIBER;
  public static final long MARKET_PRICE_IRON = WorldBalanceSeeder.MARKET_PRICE_IRON;
  public static final long MARKET_PRICE_TOOL = WorldBalanceSeeder.MARKET_PRICE_TOOL;
  public static final long MARKET_PRICE_HAUL = WorldBalanceSeeder.MARKET_PRICE_HAUL;
  public static final long MARKET_PRICE_TRANSPORT_HORSE =
      WorldBalanceSeeder.MARKET_PRICE_TRANSPORT_HORSE;
  public static final Map<CommodityId, Long> MARKET_PRICES_FACTORY =
      WorldBalanceSeeder.MARKET_PRICES_FACTORY;
  public static final String SINGLE_REGION_MARKET_ID = WorldBalanceSeeder.SINGLE_REGION_MARKET_ID;
  public static final int GENESIS_MONEY_BUFFER_PER_MILLE =
      WorldBalanceSeeder.GENESIS_MONEY_BUFFER_PER_MILLE;
  public static final GovernmentId GENESIS_GOVERNMENT_ID = WorldBalanceSeeder.GENESIS_GOVERNMENT_ID;
  public static final String GENESIS_GOVERNMENT_NATION_REF =
      WorldBalanceSeeder.GENESIS_GOVERNMENT_NATION_REF;
  public static final long GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI =
      WorldBalanceSeeder.GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI;
  public static final String MINT = WorldBalanceSeeder.MINT;
  public static final String REGIME_MINT = WorldBalanceSeeder.REGIME_MINT;
  public static final long MINT_FURNACES_PER_CAPITA = WorldBalanceSeeder.MINT_FURNACES_PER_CAPITA;
  public static final long MINT_LABOR_MILLI_PER_FURNACE =
      WorldBalanceSeeder.MINT_LABOR_MILLI_PER_FURNACE;
  public static final long MINT_TOOL_MILLI_PER_FURNACE =
      WorldBalanceSeeder.MINT_TOOL_MILLI_PER_FURNACE;
  public static final long MINT_MILLI_PER_FURNACE_CYCLE =
      WorldBalanceSeeder.MINT_MILLI_PER_FURNACE_CYCLE;
  public static final int MINT_SEED_CYCLES = WorldBalanceSeeder.MINT_SEED_CYCLES;

  // ── 包内常量：声明迁到 WorldBalanceSeeder；门面保留同名别名，同包调用点不变 ──
  static final Map<String, Map<Sex, Integer>> ACTIVITY_SEX_WEIGHT_PER_MILLE =
      WorldBalanceSeeder.ACTIVITY_SEX_WEIGHT_PER_MILLE;
  static final int[] CLASS_SHARE_PER_MILLE = WorldBalanceSeeder.CLASS_SHARE_PER_MILLE;
  static final String[] CLASS_IDS = WorldBalanceSeeder.CLASS_IDS;
  static final String[] CLASS_NAMES = WorldBalanceSeeder.CLASS_NAMES;
  static final String DISPLACED_SLOT = WorldBalanceSeeder.DISPLACED_SLOT;
  static final int[] CLASS_LABOR_PER_MILLE = WorldBalanceSeeder.CLASS_LABOR_PER_MILLE;
  static final int CLASS_WEIGHTED_LABOR_PER_MILLE =
      WorldBalanceSeeder.CLASS_WEIGHTED_LABOR_PER_MILLE;
  static final String[] RURAL_CLASS_IDS = WorldBalanceSeeder.RURAL_CLASS_IDS;
  static final String[] RURAL_CLASS_NAMES = WorldBalanceSeeder.RURAL_CLASS_NAMES;
  static final int[] RURAL_CLASS_SHARE_PER_MILLE = WorldBalanceSeeder.RURAL_CLASS_SHARE_PER_MILLE;
  static final int[] RURAL_CLASS_LABOR_PER_MILLE = WorldBalanceSeeder.RURAL_CLASS_LABOR_PER_MILLE;
  static final int RURAL_CLASS_WEIGHTED_LABOR_PER_MILLE =
      WorldBalanceSeeder.RURAL_CLASS_WEIGHTED_LABOR_PER_MILLE;
  static final int[] AGE_SHARE_PER_MILLE = WorldBalanceSeeder.AGE_SHARE_PER_MILLE;
  static final int[] AGE_LABOR_COEF_PER_MILLE = WorldBalanceSeeder.AGE_LABOR_COEF_PER_MILLE;
  static final Map<Sex, int[]> AGE_LABOR_COEF_BY_SEX = WorldBalanceSeeder.AGE_LABOR_COEF_BY_SEX;

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
   * 的逐格 {@code entries} 与逐区价表 {@code markets}，以及**家户的创世库存**与**创世货币禀赋**（键 = 家户身份）。
   *
   * <p>★★ <b>为什么把它们放在一起</b>：H1 起"商品库存"<b>不在阶层行里</b>（{@code HouseholdEconomy} 没有 {@code goods}，裁定
   * D3-C/K1）—— 它的持久真源是 actor 切片里该家户的 {@code HouseholdInventory}。而"每格两组四行的开缸余额"（口粮按阶层天数、纤维按田亩副产、
   * 城镇行的纤维与铁按作坊数）<b>只能算一次</b>：两处各算一遍必然漂移（本仓最忌"同一事实两处拼写点"）。故本记录是那条接缝 —— {@link #payload(String,
   * List, GameMap)} 取 {@link #entries()} 与 {@link #marketTables()}，{@link HouseholdSeeder} 取 {@link
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
   * {@code RuralHouseholdSeeder.cohortGroup}。
   */
  public record Seed(
      String mapId,
      List<Map<String, Object>> entries,
      Map<MarketZoneId, MarketTable> marketTables,
      Map<HouseholdId, HexCoord> householdLocations,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      List<OperatorSeed> operators,
      Map<GovernmentId, Government> governments,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      List<Map<String, Object>> debtContracts,
      List<Map<String, Object>> extraMoneyIssuances,
      List<Map<String, Object>> productionRosters,
      TestConditions.Report conditionReport) {

    /**
     * ★★ <b>四张表在赋值处冻结</b>（照 {@code IndustryDefinitionView.outputPerUnit} / {@code Facts} 的先例）：
     * SpotBugs 的 {@code EI_EXPOSE_REP} <b>不做跨过程分析</b>，看不出"构造器收了可变对象"之后有没有被改，
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
      // ★ 市场表：外层的键序 = 逐格（(q,r) 字典序，由 plan 保证）；值是共享的不可变 {@link MarketTable}。
      marketTables =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(marketTables == null ? Map.of() : marketTables));
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
      // ★★ P3：条件产物（债务节点 + 外部注入发行记录 + 报告）也冻在赋值处 —— 它们与 actor.Seed 的账本
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
      List<Map<String, Object>> extraMoneyIssuancesCopy =
          new ArrayList<>(extraMoneyIssuances == null ? List.of() : extraMoneyIssuances);
      for (Map<String, Object> node : extraMoneyIssuancesCopy) {
        if (node == null) {
          throw new IllegalArgumentException("Seed.extraMoneyIssuances 的元素不得为 null");
        }
      }
      extraMoneyIssuances = Collections.unmodifiableList(extraMoneyIssuancesCopy);
      // ★★★ D-032 C3（D2 §5.5）：创世名册节点（§5.4 同形）。空表合法 = 单角色 unit 走 C2 自动名册；
      //   多角色 unit 必须在发出前由 {@code ProductionPayloadSeeder.requireRostersForMultiRoleUnits} 校验（缺 ⇒
      // seed-roster-missing）。
      List<Map<String, Object>> productionRostersCopy =
          new ArrayList<>(productionRosters == null ? List.of() : productionRosters);
      for (Map<String, Object> node : productionRostersCopy) {
        if (node == null) {
          throw new IllegalArgumentException("Seed.productionRosters 的元素不得为 null");
        }
      }
      productionRosters = Collections.unmodifiableList(productionRostersCopy);
      conditionReport = conditionReport == null ? TestConditions.Report.EMPTY : conditionReport;
    }

    /**
     * {@code economy.Seed} 的载荷文本（{@code mapId} / {@code rulesVersion} / {@code entries} / {@code
     * markets} 都在顶层）。production-runtime 的 entries 是完整生产结构，并在其后<b>追加</b> {@code modes} / {@code
     * classStructures} / {@code classPositions} / {@code classStandings} / {@code assetRules} /
     * 五个键（{@code merchantFirms} 已于 M-A1 退役 ⇒ 不再发）。★ P3：无 conditions 时 {@code debtContracts}
     * 仍是空表、也不出现 {@code testConditions} 键。
     */
    public String economyPayload() {
      return ProductionPayloadSeeder.jsonOf(
          mapId,
          economyEntries(),
          marketTables,
          governments,
          genesisEndowment,
          genesisMoneyMilliPerCapita,
          debtContracts,
          extraMoneyIssuances,
          productionRosters,
          conditionReport);
    }

    /**
     * ★★★ <b>D-032 L4：{@code map.SeedArableOwnership} 载荷</b>（来源 = 与 {@code economy.Seed} 同一份
     * entries 的 {@code landHoldings[]}）。
     *
     * <pre>{@code
     * {"entries":[{"hex":{"q":0,"r":0},
     *              "owners":[{"owner":"hh-...","quantityMilliMu":3100000}]}]}
     * }</pre>
     *
     * <p>★ 不在本方法另算土地：entry 的 owner 行就是 {@code ProductionRuntimeSeeder} 构造的那一份；命令边界再逐 hex 校验 {@code
     * Σ ownerQuantity == Map arable 总量}。
     */
    public String landOwnershipSeedPayload() {
      List<Map<String, Object>> nodes = new ArrayList<>();
      for (Map<String, Object> entry : entries) {
        Object qValue = entry.get("q");
        Object rValue = entry.get("r");
        if (!(qValue instanceof Number q) || !(rValue instanceof Number r)) {
          throw new IllegalStateException(
              "economy seed entry 缺 q/r，无法构造 map.SeedArableOwnership: " + entry);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("hex", Map.of("q", q.intValue(), "r", r.intValue()));
        List<Map<String, Object>> owners = new ArrayList<>();
        Object holdingsValue = entry.get("landHoldings");
        if (holdingsValue != null) {
          if (!(holdingsValue instanceof List<?> holdings)) {
            throw new IllegalStateException(
                "economy seed 的 landHoldings 必须是数组: hex="
                    + new HexCoord(q.intValue(), r.intValue()));
          }
          for (Object item : holdings) {
            if (!(item instanceof Map<?, ?> holding)
                || !(holding.get("owner") instanceof String owner)
                || !(holding.get("quantityMilliMu") instanceof Number quantity)) {
              throw new IllegalStateException(
                  "economy seed 的 landHoldings 元素必须是 {owner, quantityMilliMu}: " + item);
            }
            Map<String, Object> ownerNode = new LinkedHashMap<>();
            ownerNode.put("owner", owner);
            ownerNode.put("quantityMilliMu", quantity.longValue());
            owners.add(ownerNode);
          }
        }
        node.put("owners", owners);
        nodes.add(node);
      }
      return ToolSupport.json(Map.of("entries", nodes));
    }

    /** economy 载荷不携带任何 LAND 持久键（Map component.owner 才是唯一权威）。 */
    private List<Map<String, Object>> economyEntries() {
      List<Map<String, Object>> stripped = new ArrayList<>(entries.size());
      for (Map<String, Object> entry : entries) {
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        copy.remove("landHoldings");
        copy.remove("landArableMilliMu");
        stripped.add(copy);
      }
      return List.copyOf(stripped);
    }

    /**
     * ★★ <b>{@code production.Seed} 的载荷文本</b>（D-031 P1：定义先落 production，economy.Seed 据此校验
     * unit→industry 等跨域引用）。
     *
     * <p>★★ <b>机械修正（D-032 A 批续做）</b>：三个调用点（{@code WorldgenInitializeTool} / {@code SmallWorld} /
     * {@code ThreePowersWorld}）都已在同一 {@code submitBatch} 里发 {@code production.Seed}，而本方法在中断前
     * <b>没有落地</b>（全仓 0 个定义）⇒ 应用层编译不过。这里按 {@link
     * io.mosire.simos.production.spi.ProductionSeedHandler} 真正读的两段补齐：
     *
     * <pre>
     * industries[] —— 逐格产业定义：本 Seed 的 entries 本来就是 production 的产业节点形状
     *                 （id/name/regime/cycleDays/capacityPerUnit/laborPerUnit/dailyInputPerUnit/
     *                  dailyLaborPerUnit/outputPerUnit/cycleInputPerUnit/allocation/slots ——
     *                  parse 口径见 ProductionDefinitionPayloads.industry），故**同一份节点原样复用**
     *                 （不在这里造第二套字段拼写点）。
     * modes[]      —— 默认生产方式目录（{@code ProductionPayloadSeeder.productionModeNodes}；与 economyPayload 的 modes 同源同形）。
     * </pre>
     *
     * <p>★ <b>为什么 industries 直接复用 entries</b>：{@code ProductionSeedHandler} 只从节点里取上列字段 （多余键它不读），而
     * entries 里那几个多出来的键（{@code operator}/{@code capacity} 等实例事实）已在 R3B.2 从模板里摘净 ⇒
     * 逐个重建节点只会把同一份事实写两遍（本仓明令禁止的第二拼写点）。
     */
    public String productionPayload() {
      Map<String, Object> payload = new LinkedHashMap<>();
      // ★★★ B5（§13.15.3 裁定十九）：`entries` 里那一格原来就叫 `industries`（= 本命令要的定义节点），
      //   B5 把它改名成 `plans`（那是 economy 载荷的键）⇒ 本处改为**从 plans 节点逐格收集**，
      //   定义内容一字不改（仍是 `ProductionDefinitionPayloads.industry` 读的那套字段）。
      List<Map<String, Object>> industryNodes = new ArrayList<>();
      for (Map<String, Object> entry : entries) {
        Object raw = entry.get("plans");
        if (!(raw instanceof List<?> planList)) {
          continue;
        }
        for (Object element : planList) {
          if (element instanceof Map<?, ?> planNode) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) planNode;
            // ★★★ D-032 C3（C0 顶层 unknown-key 缺口由 seed 裁剪处理）：只把 production 认识的键送进
            //   production.Seed —— economy 的实例/关系键（operator / relation / capacity 等）不跨域塞进去，
            //   也不改 production parser 的"多余键不读"语义。
            industryNodes.add(ProductionPayloadSeeder.productionIndustryNode(node));
          }
        }
      }
      payload.put("industries", industryNodes);
      payload.put("modes", ProductionPayloadSeeder.productionModeNodes());
      // ★★★ B2b（§13.11.4 第 5 条）：**实例段** —— `EconomyData.units` 已删（生产实例的唯一权威在
      //   production）⇒ 逐格 `units[]` 不再进 economy 状态，改由 `production.Seed` 的 `instances[]` 承接。
      //   ★ 同一份 unit 节点**仍然**留在 economy payload 里，但它的用途只剩"逐 unit 的 relation 声明 +
      //     配额归一"（relations 是 economy 的表）；`EconomyPayloads` 现在会拿 production 的实例投影**逐个核对**
      //     它（缺一个就具名拒），故不存在"economy 单方面声明一个实例"的第二权威。
      //   ★ 节点字段映射：economy 侧叫 `industry`，production 侧叫 `plan`（同一个方案稳定 id）。
      List<Map<String, Object>> instances = new ArrayList<>();
      for (Map<String, Object> entry : entries) {
        Object raw = entry.get("units");
        if (!(raw instanceof List<?> unitList)) {
          continue;
        }
        for (Object element : unitList) {
          if (!(element instanceof Map<?, ?> unit)) {
            continue;
          }
          Map<String, Object> instance = new LinkedHashMap<>();
          instance.put("id", unit.get("id"));
          instance.put("plan", unit.get("industry"));
          instance.put("operator", unit.get("operator"));
          instance.put("modeKey", unit.get("modeKey"));
          instance.put(
              "progressDays", unit.get("progressDays") == null ? 0 : unit.get("progressDays"));
          instances.add(instance);
        }
      }
      payload.put("instances", instances);
      return ToolSupport.json(payload);
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
   * <b>货币从哪来</b>：见 {@code MarketSeeder.operatorWageReserveMilli}（它自己那条制度里货币档的每周期应付 × 缓冲）——
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
    return plan(
        mapId, seeding, map, genesisMoneyMilliPerCapita(), FoundationProfile.PRODUCTION_RUNTIME);
  }

  /**
   * ★★ E3：真地图重载 + **初始禀赋参数**（毫/人）。默认重载逐值等于旧行为；本重载只改 {@code INITIAL_ENDOWMENT}
   * 的每人金额，商品/人口/劳动/资产口径一字不动。profile 缺省 {@link FoundationProfile#PRODUCTION_RUNTIME}。
   */
  public static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      GameMap map,
      long genesisMoneyMilliPerCapita) {
    return plan(
        mapId, seeding, map, genesisMoneyMilliPerCapita, FoundationProfile.PRODUCTION_RUNTIME);
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
    Seed seed =
        plan(
            mapId,
            seeding,
            terrainOf(map, terrain),
            arableOf(map, terrain),
            genesisMoneyMilliPerCapita,
            profile,
            conditions,
            DEFAULT_NUMERAIRE_OF);
    validateHoldingArableTotals(seed, map, terrain);
    return seed;
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

  /**
   * ★★ <b>B1（阶段 2-B）：真地图 + <b>逐格计价币函数</b>（多币种世界的创世入口）</b>。
   *
   * <p>★★ <b>它改什么、不改什么</b>：{@code numeraireOf} 同时决定<b>两件事</b>，而这两件事必须同源（"这格按什么钱报价" 与"这格的家户手里是什么钱"漂开
   * = 本地市场对该格家户直接关门）：
   *
   * <ol>
   *   <li>该格 {@code MarketTable.numeraire}（{@link #marketFor(CurrencyId)}）；
   *   <li>该格家户（含流民户）与作坊主**工资周转金**的创世钱包币种（{@link #genesisMoney(long, long, CurrencyId)}）。
   * </ol>
   *
   * <p>★ <b>其余一切逐值不变</b>：价格表、人口、库存、劳动、配额、资产、关系、审计记录的口径一字不动；商品/劳动/资产与币种无关。
   *
   * <p>★ <b>缺省（老入口）</b>：{@code at -> MARKET_NUMERAIRE} ⇒ 与不传函数的入口<b>逐字节同载荷</b>。
   *
   * @param numeraireOf 逐格计价币函数；返回 {@code null} ⇒ 当场抛（不静默落到银：那会把"没给币种"变成"偷偷按银算"）
   */
  public static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      GameMap map,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions,
      Function<HexCoord, CurrencyId> numeraireOf) {
    Map<HexCoord, String> terrain = map.terrainIndex();
    Seed seed =
        plan(
            mapId,
            seeding,
            terrainOf(map, terrain),
            arableOf(map, terrain),
            genesisMoneyMilliPerCapita,
            profile,
            conditions,
            numeraireOf);
    validateHoldingArableTotals(seed, map, terrain);
    return seed;
  }

  /** ★★ <b>B1：逐格计价币 + 出厂禀赋/profile</b>（条件取空；逐值等于带 {@code TestConditions.EMPTY} 的重载）。 */
  public static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      GameMap map,
      Function<HexCoord, CurrencyId> numeraireOf) {
    return plan(
        mapId,
        seeding,
        map,
        genesisMoneyMilliPerCapita(),
        FoundationProfile.PRODUCTION_RUNTIME,
        TestConditions.EMPTY,
        numeraireOf);
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
        mapId,
        seeding,
        terrainOf,
        genesisMoneyMilliPerCapita(),
        FoundationProfile.PRODUCTION_RUNTIME);
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
        mapId,
        seeding,
        terrainOf,
        genesisMoneyMilliPerCapita,
        FoundationProfile.PRODUCTION_RUNTIME);
  }

  /**
   * ★★ <b>P2：纯函数主入口 + 初始禀赋 + profile</b>。{@link FoundationProfile#PRODUCTION_RUNTIME} 走完整生产
   * entries，并在 {@code Seed.economyPayload()} 追加生产方式目录/阶层/资产规则；政府家户/政府记录只在 {@link
   * PopulationSeeder.Seeding#governmentHousehold()} 非空（SmallWorld 的 world-silver）时内置。
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
    return plan(
        mapId,
        seeding,
        terrainOf,
        genesisMoneyMilliPerCapita,
        profile,
        conditions,
        DEFAULT_NUMERAIRE_OF);
  }

  /**
   * ★★ <b>B1：纯函数主入口 + 逐格计价币</b>（阶段 2-B 的多币种创世入口；口径见真地图重载的注）。
   *
   * <p>★ 老入口逐值不变：它们传 {@link #DEFAULT_NUMERAIRE_OF}（恒 {@link #MARKET_NUMERAIRE}）。
   */
  static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions,
      Function<HexCoord, CurrencyId> numeraireOf) {
    Objects.requireNonNull(terrainOf, "terrainOf");
    // ★ B2 过渡：纯函数入口没有 GameMap，arable 只能按 terrain fallback；真地图入口必须传 Map 组件总量。
    return plan(
        mapId,
        seeding,
        terrainOf,
        at -> RuralLaborSeeder.landMilliMuOf(terrainOf.apply(at)),
        genesisMoneyMilliPerCapita,
        profile,
        conditions,
        numeraireOf);
  }

  /** 显式 arable 来源入口：真地图传 Map 组件总和，纯函数/夹具才允许 terrain fallback。 */
  static Seed plan(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      Function<HexCoord, Long> arableOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions,
      Function<HexCoord, CurrencyId> numeraireOf) {
    Objects.requireNonNull(terrainOf, "terrainOf");
    Objects.requireNonNull(arableOf, "arableOf");
    if (genesisMoneyMilliPerCapita < 0L) {
      throw new IllegalArgumentException("初始禀赋（毫/人）不得为负: " + genesisMoneyMilliPerCapita);
    }
    if (profile == null) {
      throw new IllegalArgumentException("profile 不得为 null");
    }
    Objects.requireNonNull(numeraireOf, "numeraireOf");
    // ★ P3：缺省/空 conditions = P1 路径（不新增任何键、不碰任何账）。
    conditions = conditions == null ? TestConditions.EMPTY : conditions;
    // ★★ 2026-10-09：唯一路线 = production-runtime（完整生产 entries + 默认生产方式目录/资产规则；
    //   政府家户/政府记录只在 seeding.governmentHousehold() 非空时内置，见 planProductionRuntime/jsonOf）。
    return ProductionRuntimeSeeder.planProductionRuntime(
        mapId,
        seeding,
        terrainOf,
        arableOf,
        genesisMoneyMilliPerCapita,
        profile,
        conditions,
        numeraireOf);
  }

  /** 真地图地形函数：一次物化 terrainIndex，未知格 fail-closed。 */
  private static Function<HexCoord, String> terrainOf(GameMap map, Map<HexCoord, String> terrain) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(terrain, "terrain");
    return at -> {
      String key = terrain.get(at);
      if (key == null) {
        throw new IllegalStateException("格 " + at + " 不在 terrainIndex 里（地图分割不变式被破坏）");
      }
      return key;
    };
  }

  /** 真地图 arable 来源：唯一读口 = {@link HexAreas#arableMilliMu(HexCell, String)}。 */
  private static Function<HexCoord, Long> arableOf(GameMap map, Map<HexCoord, String> terrain) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(terrain, "terrain");
    return at -> {
      HexCell cell = map.hexes().get(at);
      if (cell == null) {
        throw new IllegalStateException("格 " + at + " 不在 map.hexes() 里（未知 hex，拒绝按 0 伪造可耕地）");
      }
      String key = terrain.get(at);
      if (key == null) {
        throw new IllegalStateException("格 " + at + " 不在 terrainIndex 里（地图分割不变式被破坏）");
      }
      return HexAreas.arableMilliMu(cell, key);
    };
  }

  /**
   * ★★★ <b>D-032 L3 创世守卫</b>：economy 不再持久 arable supply；真地图创世的每个 economy entry 必须逐 hex 满足 {@code Σ
   * landHoldings[hex].quantityMilliMu == Map.arableMilliMu(hex)}。之后 app 每 tick 会从同一 Map 构造瞬态 {@code
   * LandArableSupply} 注入 economy session；这里先把创世两本数钉死，任何缺失/负数/不等都拒绝创世。
   */
  private static void validateHoldingArableTotals(
      Seed seed, GameMap map, Map<HexCoord, String> terrain) {
    for (Map<String, Object> entry : seed.entries()) {
      Object qValue = entry.get("q");
      Object rValue = entry.get("r");
      if (!(qValue instanceof Number q) || !(rValue instanceof Number r)) {
        throw new IllegalStateException("economy seed entry 缺 q/r，无法对账 Map arable: " + entry);
      }
      HexCoord hex = new HexCoord(q.intValue(), r.intValue());
      HexCell cell = map.hexes().get(hex);
      if (cell == null) {
        throw new IllegalStateException(
            "economy seed 的格 " + hex + " 不在 map.hexes() 里（无法对账 Map arable）");
      }
      String key = terrain.get(hex);
      if (key == null) {
        throw new IllegalStateException(
            "economy seed 的格 " + hex + " 不在 terrainIndex 里（无法对账 Map arable）");
      }
      long expected = HexAreas.arableMilliMu(cell, key);
      Object holdingsValue = entry.get("landHoldings");
      List<?> holdings = List.of();
      if (holdingsValue != null) {
        if (!(holdingsValue instanceof List<?> parsedHoldings)) {
          throw new IllegalStateException(
              "economy seed 的 landHoldings 必须是数组: hex=" + hex + " value=" + holdingsValue);
        }
        holdings = parsedHoldings;
      }
      long actual = 0L;
      for (Object item : holdings) {
        if (!(item instanceof Map<?, ?> holding)
            || !(holding.get("quantityMilliMu") instanceof Number quantity)) {
          throw new IllegalStateException(
              "economy seed 的 landHoldings 元素必须是含 quantityMilliMu 的对象: hex="
                  + hex
                  + " item="
                  + item);
        }
        long value = quantity.longValue();
        if (value < 0L) {
          throw new IllegalStateException(
              "economy seed 的 landHoldings 数量不得为负: hex=" + hex + " value=" + value);
        }
        try {
          actual = Math.addExact(actual, value);
        } catch (ArithmeticException overflow) {
          throw new IllegalStateException("economy seed 的 landHoldings 合计溢出: hex=" + hex, overflow);
        }
      }
      if (actual != expected) {
        EventLog.channel(io.mosire.simos.app.AppLog.shell())
            .error(
                LogEvent.of(
                    "ECONOMY_SEED_ARABLE_SOURCE_MISMATCH",
                    AppLogSource.SHELL_LIFECYCLE,
                    "hex",
                    hex,
                    "landHoldingsQuantityMilliMu",
                    actual,
                    "mapArableMilliMu",
                    expected));
        throw new IllegalStateException(
            "创世土地合计与 Map arable 不一致: hex="
                + hex
                + " ΣlandHoldings="
                + actual
                + " map.arableMilliMu="
                + expected);
      }
    }
  }

  /**
   * ★★ <b>B1：老入口的逐格计价币缺省函数</b>（恒 {@link #MARKET_NUMERAIRE}）。
   *
   * <p>★ 它是"不传函数的入口逐值不变"这条承诺的落点：同一个函数实例被所有老入口共用，载荷因此逐字节相同。
   */
  private static final Function<HexCoord, CurrencyId> DEFAULT_NUMERAIRE_OF =
      hex -> MARKET_NUMERAIRE;

  /** {@code economy.Seed} 约定：计价币/价格入口（实现见 {@link MarketSeeder}）。 */
  public static MarketTable marketFor(CurrencyId numeraire) {
    return MarketSeeder.marketFor(numeraire);
  }

  /** 创世初始禀赋的每人金额（毫/人）。 */
  public static long genesisMoneyMilliPerCapita() {
    return MarketSeeder.genesisMoneyMilliPerCapita();
  }

  /** 一个家户的创世钱包。 */
  public static Map<CurrencyId, Long> genesisMoney(long population) {
    return MarketSeeder.genesisMoney(population);
  }

  /** 一个家户的创世钱包（每人金额可注入）。 */
  public static Map<CurrencyId, Long> genesisMoney(
      long population, long genesisMoneyMilliPerCapita) {
    return MarketSeeder.genesisMoney(population, genesisMoneyMilliPerCapita);
  }

  /** 一个家户的创世钱包（币种可注入）。 */
  public static Map<CurrencyId, Long> genesisMoney(
      long population, long genesisMoneyMilliPerCapita, CurrencyId numeraire) {
    return MarketSeeder.genesisMoney(population, genesisMoneyMilliPerCapita, numeraire);
  }

  /** 一群批次的人数。 */
  static long populationOf(List<PopulationGroup> pool) {
    return RuralLaborSeeder.populationOf(pool);
  }

  /** 每格可耕地系数（千分）。 */
  public static int arablePerMilleOf(int food) {
    return RuralLaborSeeder.arablePerMilleOf(food);
  }

  /** 地形 key → map 的产能档。 */
  public static int foodOf(String terrainKey) {
    return RuralLaborSeeder.foodOf(terrainKey);
  }

  /** 创世流民人数。 */
  static long displacedSeedPopulation(long poolPopulation) {
    return RuralHouseholdSeeder.displacedSeedPopulation(poolPopulation);
  }

  /** 年龄（天）落在哪一档。 */
  static int ageBracketOf(long ageDays) {
    return RuralLaborSeeder.ageBracketOf(ageDays);
  }

  /** 一个批次的每人千分劳动。 */
  static long perCapitaLaborPerMille(PopulationGroup group) {
    return RuralLaborSeeder.perCapitaLaborPerMille(group);
  }

  /** 一个批次的每人千分劳动（可注入系数表）。 */
  static long perCapitaLaborPerMille(PopulationGroup group, Map<Sex, int[]> coefficientsBySex) {
    return RuralLaborSeeder.perCapitaLaborPerMille(group, coefficientsBySex);
  }

  /** 有效劳动（批次口径）。 */
  static long laborMilli(List<PopulationGroup> groups) {
    return RuralLaborSeeder.laborMilli(groups);
  }

  /** 有效劳动（批次口径 + 可注入系数表）。 */
  static long laborMilli(List<PopulationGroup> groups, Map<Sex, int[]> coefficientsBySex) {
    return RuralLaborSeeder.laborMilli(groups, coefficientsBySex);
  }

  /** 有效劳动（人口窄口径）。 */
  public static long laborMilli(long population) {
    return RuralLaborSeeder.laborMilli(population);
  }

  /** 池的产业日劳动。 */
  static long industryDailyLabor(List<PopulationGroup> pool) {
    return RuralLaborSeeder.industryDailyLabor(pool);
  }

  /** 池的产业日劳动（已切好的阶层人数形态）。 */
  static long industryDailyLabor(long[] people, long poolLabor, long poolCount) {
    return RuralLaborSeeder.industryDailyLabor(people, poolLabor, poolCount);
  }

  /** 一座作坊每周期的工具用量。 */
  static long toolPerWorkshopMilli() {
    return RuralLaborSeeder.toolPerWorkshopMilli();
  }

  /** 第 1 天的口粮。 */
  public static long firstDayRationMilli(long population) {
    return RuralLaborSeeder.firstDayRationMilli(population);
  }

  /** 某阶层人口的多日口粮。 */
  public static long rationMilli(long population, String slot) {
    return RuralLaborSeeder.rationMilli(population, slot);
  }

  /** 某阶层每人几天的口粮。 */
  public static int initialRationDays(String slot) {
    return RuralLaborSeeder.initialRationDays(slot);
  }

  /** 单位户口粮槽位。 */
  public static String unitHouseholdRationSlot() {
    return RuralLaborSeeder.unitHouseholdRationSlot();
  }

  /** 单位户口粮天数。 */
  public static int unitHouseholdRationDays() {
    return RuralLaborSeeder.unitHouseholdRationDays();
  }

  /** 单位户创世粮。 */
  public static long unitHouseholdGenesisGrainMilli(long population) {
    return RuralLaborSeeder.unitHouseholdGenesisGrainMilli(population);
  }

  /** 家户开缸库存（阶层槽位口径）。 */
  static Map<CommodityId, Long> openingStock(
      long population, String slot, Map<String, long[]> goodsByClass, int index) {
    return RuralHouseholdSeeder.openingStock(population, slot, goodsByClass, index);
  }

  /** 家户开缸库存（显式口粮天数口径）。 */
  static Map<CommodityId, Long> openingStock(
      long population, int rationDays, Map<String, long[]> goodsByClass, int index) {
    return RuralHouseholdSeeder.openingStock(population, rationDays, goodsByClass, index);
  }

  /** 单户/单经营者方案的默认角色节点。 */
  public static List<Map<String, Object>> singleOperatorRoleNodes() {
    return IndustrySeeder.singleOperatorRoleNodes();
  }

  /** PRODUCTION_RUNTIME 的运行时版本标签。 */
  static String productionRuntimeRulesVersion() {
    return MarketSeeder.productionRuntimeRulesVersion();
  }

  /** 按千分比例表切分（实现见 {@link WorldBalanceSeeder}）。 */
  public static long[] splitByShares(long total, int[] sharesPerMille) {
    return WorldBalanceSeeder.splitByShares(total, sharesPerMille);
  }

  /** 按任意非负权重成比例切分（实现见 {@link WorldBalanceSeeder}）。 */
  public static long[] splitProportional(long total, long[] weights) {
    return WorldBalanceSeeder.splitProportional(total, weights);
  }
}
