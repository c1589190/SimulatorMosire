package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★★ <b>W7（2026-10-11）：跑商产业（{@code trade@hex}）模板的**全仓唯一拼写点**</b>。
 *
 * <p>★★ <b>用户原话（本批依据，逐字）</b>：
 *
 * <blockquote>
 *
 * 「**跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？**」<br>
 * 「难道没有家户自动估算其他生产方式的利润，然后自动设置往其他所需要投入更低、利润更高的家户跑的机制吗？」
 *
 * </blockquote>
 *
 * <p>★★ <b>它解决什么</b>：W7 之前，{@code trade} 产业<b>只由创世播种器</b>在有城镇人口的格播下 （{@code
 * EconomySeeder.trade(...)}，判据 {@code hasCraft = urbanPopulation > 0}）⇒ 创世之后<b>没有任何自动路径</b>能在别的格
 * 长出一间商号（唯一入口是 GM 命令 {@code economy.UpsertIndustry}）。用户问的"家户自动估算其他生产方式的利润、自动往利润高的地方跑"
 * 因此被"目标格必须先有那种产业模板"锁死。W7 起：迁移时**允许按需创建目标格的 {@code trade} 记录** ——
 * 而它建出来的东西必须与创世播的那一份<b>逐字段同值</b>，故模板上移到这里，{@code EconomySeeder} 改为引用本类。
 *
 * <p>★★ <b>形状（逐字等于 {@code EconomySeeder.trade(...)} 播出来的那一份）</b>：
 *
 * <pre>
 * id                = trade@&lt;q&gt;_&lt;r&gt;            （{@link IndustryHexKeys#id(String, int, int)}）
 * name              = 贸易
 * regime            = merchant                  （{@link RegimeOperators#MERCHANT}；BY_REGIME 不登记它 ⇒ 经营主体必须显式给）
 * cycleDays         = 120                       （与农业/手工业同周期）
 * capacityPerUnit   = {TOOL: 120}               （★ W7-fix2：120 毫工具作业资本 / 1 单位规模 —— 牲口已退出配方；120 = 12,000 ÷ 100 ⇒ 城市格规模锚**保持 100 单位**）
 * dailyInputPerUnit = {}  dailyLaborPerUnit = 0
 * laborPerUnit      = 1000
 * outputPerUnit     = {haul: 1}                 （A1：跑商产出**运输服务**，不再是一具"收获当场 return"的空壳）
 * cycleInputPerUnit = {tool: {tool: 100}}       （A3：工具消耗的唯一一套口径 = 产业周期投入；每单位规模·周期）
 * slots             = {@link IndustryClassSlots#slots()}
 * allocation        = Split(700, 300)
 * moneyOutputPerUnit= NONE                      （跑商不铸币）
 * </pre>
 *
 * ★★★ <b>它<b>不</b>造资产</b>：模板只是"技术配方"（{@code capacityPerUnit} 是"每单位规模需要多少生产资料"，不是存量）。 真正让一间商号跑起来的是
 * {@code OwnershipStake} 里那一份作业资本份额（{@code capacityPerUnit} 的 {@code AssetKind}）—— D-023 §4 的可移动资产跨
 * hex 在目标产业的同 {@code AssetKind} 下重建份额、源份额同量减少。
 *
 * <p>★★★ <b>W7-fix（2026-10-11，用户裁定 v3.28 §7）：牲口（CATTLE）不再进配方</b>。用户原话「**能不能先把牲口要求取消掉啊？
 * 目前是在跑测试，算作工具需求得了**」⇒ 跑商（承运）的投入口径 = <b>劳动 + 工具</b>（= 商人阶梯设计 §12 J-B 的既有口径 「运力 = f(劳动投入, 工具消耗)」）：
 *
 * <pre>
 * 可行性判据（决策层）  劳动 &gt; 0 ∧ 工具 &gt; 0 ⇒ 可行（{@code ExpectedProfitBook.merchantProspect}；
 *                       缺哪一项就报哪个具名理由 no-labor / no-tool-input）
 * 运力派生量            逐值仍是既有算式：{@link MerchantCapacity#capacityMilliOf}(劳动投入, 工具)
 *                       （{@code MerchantCapacityPool.providerCapacityMilli}，本批未改）
 * 规模锚（生产层）      本模板的 {@code capacityPerUnit = {TOOL: TOOL_PER_UNIT}}（= 120 毫工具/单位规模）—— 作业资本那一维 =
 *                       **工具**，不再有任何 {@code CATTLE} 字段（不留"没人读的字段"，§一.11）；取值使城市格规模锚**保持 100 单位**（W7-fix2）
 * 周期投入              {@code tool: 100 毫/规模·周期}（数值一字未改，只是外层分路键由 CATTLE 改成 tool）
 *                       ★★ 与上一行**不是同一个量**：120 = 每 1 单位规模**压住**的作业资本（存量），100 = 每 1 单位规模每周期**烧掉**的（流量），
 *                       本批刻意**不要求相等**（见 {@link #TOOL_PER_UNIT}）
 * </pre>
 *
 * ★ 逐条后果已记进实现账本（`.superpowers/sdd/2026-10-11-w7-haul-and-merchants/impl-ledger.md` 的 W7-fix /
 * W7-fix2 节）： ① {@code CATTLE} 在本模板里**一个字段都不剩**；② 创世播种不再为 {@code trade} 播 CATTLE 份额（那会变成没人读的孤儿份额）；
 * ③ 本格 {@code trade} unit 的规模锚那一**维**从"牲口头数"变成"工具作业资本量" —— ★★ W7-fix2 起锚**值**仍 = 100 单位规模（120
 * 毫工具/单位 × 100 单位 = 12,000 毫工具禀赋）⇒ 相对 W7 前**数值行为不变**，变的只是"压住规模的那一维资产种类"（{@code CATTLE → TOOL}）。
 *
 * <p>★ <b>本类无状态、不写任何东西</b>：{@link #industry(HexCoord)} 与 {@link #industryId(HexCoord)} 都是纯函数 ——
 * 决策层（{@code ExpectedProfitBook} 的预估）可以直接拿它算"若我在这一格开一间商号会怎样"，而<b>不落任何状态</b>； 只有迁移执行（{@code
 * ModeMigrationSettlement}）才会把同一份值真的 put 进工作副本（随本 tick 的变更集落 revision）。
 */
public final class MerchantTradeTemplate {

  private MerchantTradeTemplate() {}

  /** 产业 kind（= {@code IndustryHexKeys} 的 kind 维；{@code trade@q_r} 的前缀）。 */
  public static final String KIND = "trade";

  /** 展示名（与创世播种逐字相同）。 */
  public static final String DISPLAY_NAME = "贸易";

  /** 制度（唯一拼写点仍是 {@link RegimeOperators#MERCHANT}）。 */
  public static final RegimeId REGIME = new RegimeId(RegimeOperators.MERCHANT);

  /** 周期（天）：与农业/手工业同值（既有创世口径）。 */
  public static final int CYCLE_DAYS = 120;

  /**
   * 每 1 单位贸易规模每周期消耗的工具（毫工具）：{@code 100}（★ 逐值等于 {@code
   * EconomySeeder.TOOL_MILLI_PER_TRADE_UNIT_CYCLE} = {@code HAUL_PER_TRADE_UNIT_CYCLE ×
   * MILLI_PER_COMMODITY_UNIT × HAUL_TOOL_PER_MILLE_OF_SERVICE ÷ 1000} = 1 × 1000 × 100 ÷ 1000）。
   *
   * <p>★★ <b>量纲（别与"每趟烧 1000 毫工具"的旧口径混）</b>：这是**每 1 单位规模 · 每周期**的投入； 旧 {@code
   * MerchantHaul.TOOL_MILLI_PER_HAUL}（每趟 1,000 毫）已随 A3 整体退役 —— 两者不是同一个量 （{@code
   * MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI} 的 12,000 是"12 趟 × 10 商品单位 × 1000 毫 × 100‰"
   * 的**创世禀赋**，不是本常量）。
   *
   * <p>★★ <b>W7-fix（2026-10-11）</b>：值 {@code 100} **一字未改**，改的只是它在 {@code cycleInputPerUnit}
   * 里挂的分路键（{@code CATTLE → tool}）。
   *
   * <p>★★ <b>W7-fix2（2026-10-11）更正：它<b>不再</b>兼任作业资本锚的取值</b>（W7-fix 的 J2 已废，见 {@link
   * #TOOL_PER_UNIT}）—— 本常量 = <b>每 1 单位规模每周期烧掉多少毫工具</b>（流量）；作业资本锚 = <b>每 1 单位规模压住多少毫工具</b>（存量，=
   * {@link #TOOL_PER_UNIT} = 120）。<b>两者不是同一个量，不要求相等</b>（本批刻意让锚去保规模 100）。
   */
  public static final long TOOL_MILLI_PER_UNIT_CYCLE = 100L;

  /**
   * ★★ <b>W7-fix（2026-10-11）：每 1 单位贸易规模需要的<b>作业资本</b>（{@code capacityPerUnit}，毫工具）</b>。
   *
   * <p>★★ <b>W7-fix2（2026-10-11，控制方裁定 E2 改判）：取值 = 120 毫工具 / 单位规模，使城市格规模锚保持既有的 100 单位</b>：
   *
   * <pre>
   * {@code capacityPerUnit   = {TOOL: TOOL_PER_UNIT}} = {TOOL: 120}   ← 每 1 单位规模**压住**的作业资本（存量 / 资本占用）
   * {@code cycleInputPerUnit = {tool: 100}}                           ← 每 1 单位规模每周期**烧掉**的工具（流量）
   * 创世作业资本（{@code EconomySeeder} 的 trade {@code capacity} = {TOOL: 12,000} 毫工具）
   * ⇒ 规模 = ⌊12,000 ÷ 120⌋ = **100 单位**（= W7 的 CATTLE 锚逐值：{@code {CATTLE: 1} × 100 头}）
   * </pre>
   *
   * <p>★★ <b>120 是怎么来的</b>：{@code 12_000L / 100L} —— 分子 = 商号本金主的**创世作业资本**（毫工具），逐值等于 {@code
   * EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}（= 12,000）；分母 = **要保住的规模锚**（100 单位，W7 的
   * CATTLE 口径同样是 100）。★ 分子只能在本类以**字面量复述**：{@code simos-app} 在 economy 的 enforcer 禁列里（跨模块不可引用）⇒
   * 这是本常量与创世禀赋之间**唯一**的耦合点，改任一侧都必须回来改这里。
   *
   * <p>★★ <b>它取代了什么</b>：W7 的 {@code CATTLE_PER_UNIT}（{@code {CATTLE: 1}}，"1 头畜力 / 1 单位运力"）。用户裁定
   * v3.28 §7 取消牲口要求（原话「**能不能先把牲口要求取消掉啊？目前是在跑测试，算作工具需求得了**」）⇒ 本模板的投入口径 = **劳动 + 工具**，作业资本那一维随之落到
   * {@code TOOL}（§一.11：模板里**一个 CATTLE 字段都不留**）。
   *
   * <p>★★ <b>量纲（★ 本批最容易读错的一处）</b>：本仓商品账一律以**毫**计（{@code EconomyVocabulary.MILLI_PER_COMMODITY_UNIT}
   * = 1000；{@code OwnershipStake.quantity()} 同制）⇒ 本常量读作 "<b>每 1 单位规模压 120 毫工具作业资本</b>"。
   *
   * <p>★★ <b>它进入哪些算式（W7-fix2 回代码核过）</b>：只进"<b>规模反解</b>"与"按规模补资产 / 迁移覆盖"，**不进**运力算式 （运力 = {@code
   * MerchantCapacity.capacityMilliOf}(劳动投入, 工具**可投入量**)，两个操作数都是流量）：
   *
   * <pre>
   * 规模   = min over k ∈ capacityPerUnit: ⌊可用[k] ÷ capacityPerUnit[k]⌋
   *          （{@code ProductionProcessBook.capacityScaleOf} / {@code SettlementIndex} / {@code ExpectedProfitBook}）
   * 补资产 = 目标规模 × capacityPerUnit[k]（{@code EconomyEnterpriseSettlement} / {@code MigrationSettlement}）
   * 产出   = outputPerUnit × 规模  ⇒ 本常量只经"规模"**间接**影响产出，不直接乘产出
   * </pre>
   *
   * ⇒ 口径：它是"<b>每单位规模的资本占用</b>"（规模上界 = 作业资本**存量** ÷ 本值），不是"每周期成本"。
   *
   * <p>★ <b>为什么不再取"一个周期的工具投入"（W7-fix 的 J2，本批作废）</b>：那样锚 = 100 ⇒ 规模 = 120 ⇒ 城市格的产出 / 工具消耗 /
   * 牌价随之漂移，**污染"拆门槛前 / 后"的可比性**。本批的唯一目的是拆掉牲口门槛，**不许顺带改标定** ⇒ 锚改成 120，逐值保住 W7 的 100 单位规模。
   * 代价（如实记）：W7-fix 那条"12,000 毫恰好是一个周期要烧掉的量"的自洽性**不再成立** —— 100 单位规模每周期烧 100 × 100 = **10,000** 毫工具，
   * 禀赋 12,000 毫留 **2,000 毫**余量。该自洽性让位于"规模锚不变"。
   */
  public static final long TOOL_PER_UNIT =
      12_000L / 100L; // = 120：分子 = 创世作业资本（毫工具），分母 = 要保住的规模锚（100 单位）

  /** 每 1 单位贸易规模需要的劳动（千分劳动 / 日·单位）。 */
  public static final long LABOR_MILLI_PER_UNIT = 1_000L;

  /** 每 1 单位贸易规模每周期产出的运输服务（商品单位）。 */
  public static final long HAUL_PER_UNIT_CYCLE = 1L;

  /** 分配模板的生产资料权重（‰）。 */
  public static final int SPLIT_MEANS_PER_MILLE = 700;

  /** 分配模板的劳动权重（‰）。 */
  public static final int SPLIT_LABOR_PER_MILLE = 300;

  /** 运输服务商品 id（唯一拼写点仍是 {@link EconomyVocabulary#HAUL_COMMODITY_ID}）。 */
  public static final CommodityId HAUL_COMMODITY =
      new CommodityId(EconomyVocabulary.HAUL_COMMODITY_ID);

  /** 工具商品 id（唯一拼写点仍是 {@link EconomyVocabulary#TOOL_COMMODITY_ID}）。 */
  public static final CommodityId TOOL_COMMODITY =
      new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /** 这一格的 {@code trade} 产业 id。 */
  public static IndustryId industryId(HexCoord hex) {
    if (hex == null) {
      throw new IllegalArgumentException("MerchantTradeTemplate.industryId 的 hex 不得为 null");
    }
    return IndustryHexKeys.id(KIND, hex.q(), hex.r());
  }

  /**
   * ★★ <b>W7-fix（2026-10-11）：作业资本那一维的**唯一拼写点**</b>（{@code capacityPerUnit} 的键集，保序）—— 需要"哪些 {@code
   * AssetKind} 是跑商的作业资本"的调用方（如 {@code ModeMigrationPolicy} 的随迁携带量算式）读这里，**不许**各自写死一个 {@code
   * AssetKind}。
   *
   * <p>★ 返回 {@code {TOOL}}（W7-fix 起）；返值是内容相同的纯函数（同一份数组 → 同一份不可变列表），可安全反复调用。
   */
  public static List<AssetKind> capacityKinds() {
    return List.of(AssetKind.TOOL);
  }

  /**
   * ★★ <b>标准跑商产业模板</b>（纯函数；逐字段等于创世播种出来的那一份）。
   *
   * <p>★★ <b>调用方须知</b>：本方法是<b>估算与按需创建共用的同一份值</b> —— 决策层拿它算预期利润（不落状态）， 迁移执行把它 put 进工作副本。两边同源 ⇒
   * "预估的那个商号"与"真的长出来的那个商号"不可能漂开。
   */
  public static Industry industry(HexCoord hex) {
    Map<AssetKind, Long> capacityPerUnit = new LinkedHashMap<>();
    // ★★ W7-fix：作业资本那一维 = 工具（牲口已退出配方，见类注）。★ 键集与每单位量都只有一处拼写：
    //   键集 = {@link #capacityKinds()}（需要"哪些 AssetKind 是作业资本"的调用方读它，不各自写死）。
    for (AssetKind kind : capacityKinds()) {
      capacityPerUnit.put(kind, TOOL_PER_UNIT);
    }
    Map<CommodityId, Long> outputPerUnit = new LinkedHashMap<>();
    outputPerUnit.put(HAUL_COMMODITY, HAUL_PER_UNIT_CYCLE);
    Map<CommodityId, Long> toolPerCycle = new LinkedHashMap<>();
    toolPerCycle.put(TOOL_COMMODITY, TOOL_MILLI_PER_UNIT_CYCLE);
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit = new LinkedHashMap<>();
    // ★★ W7-fix：外层分路键随作业资本维度一起改成 tool（{@code Industry.inputPerUnit()} 把各层摊平成
    //   {商品 → 毫}，故**内层**值侧口径一字未改：仍是 tool: 100 毫/规模·周期）。
    cycleInputPerUnit.put(AssetKind.TOOL, toolPerCycle);
    return new Industry(
        industryId(hex),
        DISPLAY_NAME,
        REGIME,
        CYCLE_DAYS,
        capacityPerUnit,
        Map.of(),
        0L,
        LABOR_MILLI_PER_UNIT,
        outputPerUnit,
        cycleInputPerUnit,
        IndustryClassSlots.slots(),
        new AllocationRule.Split(SPLIT_MEANS_PER_MILLE, SPLIT_LABOR_PER_MILLE),
        // ★ 末尾五个旧档兼容位逐值等于"新形状没有它们"（与 EconomyPayloads.industry(JsonNode) 的出口同值）：
        //   operator=null / progressDays=0 / capacity={} / cycleLaborMilli=0 /
        // cycleInputUsedMilli={}。
        null,
        0L,
        Map.of(),
        0L,
        Map.of(),
        MoneyOutput.NONE);
  }
}
