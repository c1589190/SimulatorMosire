package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;

/**
 * ★★ <b>B2（2026-10-10）当日视图：一次 {@code advance} 的逐日循环里"当刻状态"的唯一只读读口</b>。
 *
 * <p>★★ <b>它解决的是什么</b>：日结算每一天都要回答"<b>现在</b>世界长什么样"（谁住在哪、谁是什么阶层、 哪个产业有模板、哪个格有运力、政府有哪些）。 一次 {@code
 * advance} 会把 N 天放进<b>同一个</b> {@link EconomySession}（整段只落一条 revision）， 而 {@code
 * EconomySession.base()} 是<b>本次 advance 起点</b>的那份不可变快照 —— 段内第 2 天以后再读它，读到的就是"上一天以前的旧世界"。 旧实现（E5b
 * 的连续压力计数器、迁移/排序路径整条）正是这么读的：它把"世界历史"偷换成"调用方从哪天开始"， 于是 {@code 0→360} 一次与 {@code 360 × 1 天}
 * 推进出来的<b>不是同一个世界</b>（实测差 18,538~25,673 叶）。
 *
 * <p>★★ <b>它是什么形态（性能红线 §3.4）</b>：内存里的<b>只读门面</b> —— <b>零拷贝、零序列化、零 ChangeSet、零落盘、零
 * Revision</b>；构造只持一个工作表引用（<b>引用级成本，与状态规模无关</b>），每次读取现场解析"工作副本优先、 未物化则复用 base 的不可变表"。★ 它<b>不进</b>
 * {@link EconomyData}、<b>不进</b>变更集、<b>不进</b> {@code Codec}：它没有自己的状态，只是"当刻值"的语法糖。
 *
 * <p>★★ <b>逐日读者唯一来源</b>：日循环里的"当刻"读者（迁移/择业/清算/前瞻/运力/市场装配/日志）一律取本视图； {@code advance} 段首的 {@link
 * EconomyData}（{@code session.base()}）<b>只允许</b>段级语义（审计/差分/回放起点）， 每一处仍读它的地方都在代码注释里写明理由（清单见 {@code
 * .superpowers/sdd/2026-10-10-b2-per-day-view/impl-ledger.md}）。
 *
 * <p>★★ <b>为什么不能"只换一半"</b>：同一个函数里若"当刻归属"读工作副本、"当刻人口/位置"读段首 base， 就会拼出<b>混合日视图</b>（"当刻位置 + 段首人口"）——
 * 那既不是任何一天的真实状态，也不可复算。 故本接口把这两类组件<b>放在同一个读口上</b>：{@link #classStandings()} 与 {@link #classes()}
 * 只能同源。
 *
 * <p>★ <b>与 {@link EconomyData} 的关系</b>：{@code EconomyData} 自己实现本接口（"我就是我自己的当刻值"）⇒ 段级/读口/GM
 * 工具那些<b>本来就在 revision 边界</b>的调用方传 {@code EconomyData} 时逐字不变， 只有日循环内部改传工作副本视图。
 *
 * <p>★ <b>实现者</b>：{@code io.mosire.simos.economy.time.WorkingDayView}（挂在 {@code
 * EconomyStateBuilder} 上）。 本接口只声明<b >读</b>口，不声明任何写口 —— 拿到它的人无法写状态。
 */
public interface EconomyDayView {

  /** 产业表（模板 + 存量）。★ <b>工作副本优先</b>：生产/收获/组织各阶段会在段内改它（产出、投入、闲置份额）， 故逐日读者必须看当刻值。 */
  /**
   * ★★★ <b>W10（用户裁定「凡"行为"一律做成 GM 可调的预设」）：政策读口</b> —— 缺键 ⇒ 该键的具名缺省（= 改造前的常量值）。
   *
   * <p>★ 它是结算类读政策值的**唯一拼写点**（不得各处自己 {@code meta().economyPolicies().get(...)} 再兜底）。
   */
  long economyPolicyOf(EconomyPolicyKey key);

  /**
   * ★★ <b>W10：该政策键是否被 GM 显式配置过</b>（缺键 ⇒ false）。
   *
   * <p>★ 只给"缺省值不在政策表里、而在别处"的那一类读取点用（铸币产出率的缺省住在产业载荷 {@code
   * IndustryDefinitionView.moneyOutputPerUnit} 里）：这类点必须在**未配置时逐值等于改动前**。其余键一律用 {@link
   * #economyPolicyOf}（缺省 = 该键的 {@code defaultValue()}）。
   */
  boolean economyPolicyConfigured(EconomyPolicyKey key);

  default Map<IndustryId, IndustryDefinitionView> industries() {
    return definitions().industries();
  }

  /**
   * ★★ <b>当刻生产定义的只读来源</b>（production 契约；P1 起定义权威在 production）。
   *
   * <p>★ {@link EconomyData} <b>不持有</b>定义，故它继承本方法的缺省实现（抛出）；真实执行视图（{@code WorkingDayView} / revision
   * 边界的装配视图）覆盖它并返回当刻 production 定义。这是"缺定义不能当空表降级"的机读形态：误用当场 fail-closed。
   */
  default ProductionDefinitionLookup definitions() {
    throw new IllegalStateException(
        "该 EconomyDayView 不持有生产定义：请经装配好的执行视图（definitions()）取 production.contract 定义");
  }

  /**
   * 家户行表（人口/劳动/居住格/参与度）。★ <b>工作副本优先</b>：段内每天的生死、迁移、劳动重算、模式切换都在改它 —— 这就是"当刻人口与当刻位置"的唯一来源（不得与 {@link
   * #classStandings()} 分成两个来源）。
   */
  Map<HouseholdId, HouseholdEconomy> classes();

  /**
   * 家户阶层归属表（当前位置 / 连续债务压力周期数 / 上次迁移日）。★ <b>工作副本优先</b>：E5b 的压力计数、E6a 的变迁、 迁移新建户都在段内写它
   * ⇒「累计计数」与「当刻归属」都必须读这里（读段首 base = 把"关账周期数"偷换成"调用次数"）。
   */
  Map<HouseholdId, HouseholdClassMembership> classStandings();

  /** 生产组织表（ACTIVE/EXITING/SHORTAGE…）。★ <b>工作副本优先</b>：自动组织阶段与 E6a 变迁都在当日 upsert。 */
  Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations();

  /** 在途批次表。★ <b>工作副本优先</b>：当日发运/到货会增删行（迁移择目标时要看当刻在途量）。 */
  Map<ShipmentId, ShipmentBatch> shipments();

  /** 市场表（价格/库存）。★ <b>工作副本优先</b>：自适应价格与市场轮当日写回（运力判据按价格是数据）。 */
  Map<MarketZoneId, MarketTable> marketTables();

  /** 政府表（政策/报价/国库户）。★ <b>工作副本优先</b>：日结算的发行/铸币腿与授权计划都按当刻政府表判。 */
  Map<GovernmentId, Government> governments();

  /** 货币发行审计表。★ <b>工作副本优先</b>：当日发行腿逐笔追加（FX 储备上限 = 累计发行量）。 */
  Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances();

  /**
   * 模式变迁表（PENDING/APPLIED/FAILED）。★ <b>工作副本优先</b>：E6a 在段内把到期 PENDING 写成 APPLIED ⇒
   * "今天有没有到期变迁"必须按<b>当刻</b>状态判 —— 读段首 base 会把已执行的变迁在段内每一天重新当成到期（空转， 且与"逐日推进"两条路径的行为不再同源）。
   */
  Map<ModeTransitionId, ModeTransition> modeTransitions();

  /** 阶层位置模板（静态：写入口只有创世/命令）⇒ 段内不变，视图中即 base 的那份表。 */
  Map<ClassPositionId, ProductionRole> classPositions();

  /** 阶层结构模板（静态）⇒ 段内不变。 */
  Map<ClassStructureId, ClassStructure> classStructures();

  /** 生产方式模板（静态）⇒ 段内不变；空表 = 未接线 E1–E6（旧世界形态）。 */
  default Map<ProductionModeId, ProductionModeDefinitionView> modes() {
    return definitions().modes();
  }

  /** 家户需求表（静态）⇒ 段内不变。 */
  Map<DemandId, HouseholdDemand> demands();

  /** 生产资料规则表（静态）⇒ 段内不变。 */
  Map<AssetRuleId, AssetRule> assetRules();

  /** 市场区表（静态）⇒ 段内不变（写入口只有三条区命令）。 */
  Map<MarketZoneId, MarketZone> marketZones();

  /**
   * ★★★ <b>D-032 D2 §5.3：生产参与名册（键 = unit）</b>。
   *
   * <p>★ <b>缺省 = 空表</b>：没有名册不是坏数据，是"这个世界还没有合作生产名册"（与其余可选组件同款）。 {@code EconomyData}
   * 直接实现（它就是自己的当刻值）；{@code WorkingDayView} 覆盖为**工作副本优先** （自动组织/名册命令会在段内写它）。
   */
  default Map<ProductionUnitId, ProductionRoster> productionRosters() {
    return Map.of();
  }

  /**
   * ★★★ <b>D-032 W2b：逐 {@code (unit, role, worker)} 工资进位余数（0..999）</b>。空表 = 全部为 0； {@code
   * EconomyData} 直接实现，{@code WorkingDayView} 覆盖为工作副本优先。
   */
  default Map<WageCarryKey, Long> wageCarryRemainders() {
    return Map.of();
  }

  /**
   * ★★★ <b>D-032 P4b1：U20 工资锚（键 = marketZone/industry/role）</b>。
   *
   * <p>★ <b>缺省 = 空表</b>：没有锚不是坏数据，也不是 0；缺锚与 0 工资必须由读口（{@code WageAnchorBook}）区分。
   * 锚是 M3/M6/M8 的唯一工资报价权威。
   */
  default Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors() {
    return Map.of();
  }

  /**
   * ★★★ <b>D-032 D4-Ib1：资产占用授予（第 36 组件）</b>。空表合法（= 没有占用）；当刻值 reader 必须与同一 revision 的 份额/实例同源。{@code
   * EconomyData} 直接实现，{@code WorkingDayView} 覆盖为工作副本优先。
   */
  default Map<AssetOccupancyGrantId, AssetOccupancyGrant> assetOccupancies() {
    return Map.of();
  }

  /** ★★★ <b>D-032 D4-Ib1：商品库存预留（第 37 组件）</b>。空表合法（= 没有预留）；它不是第二本余额账。 */
  default Map<InventoryReservationId, InventoryReservation> inventoryReservations() {
    return Map.of();
  }

  /**
   * ★★ <b>W1：市场轮统计簿</b>（键 = 市场 id；纯读数）。★ <b>工作副本优先</b>：每个市场轮结束时当日结算会写它 ⇒
   * 日内读者必须看当刻窗口（"本轮"就是刚过去的那一刻）。
   */
  Map<MarketZoneId, MarketRoundStats> marketRoundStats();
}
