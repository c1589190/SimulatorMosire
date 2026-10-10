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
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
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
  Map<IndustryId, Industry> industries();

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
  Map<HexCoord, Market> markets();

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
  Map<ProductionModeId, ProductionMode> modes();

  /** 家户需求表（静态）⇒ 段内不变。 */
  Map<DemandId, HouseholdDemand> demands();

  /** 生产资料规则表（静态）⇒ 段内不变。 */
  Map<AssetRuleId, AssetRule> assetRules();

  /** 市场区表（静态）⇒ 段内不变（写入口只有三条区命令）。 */
  Map<MarketZoneId, MarketZone> marketZones();
}
