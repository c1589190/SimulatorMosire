package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyDayView;
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
import java.util.Objects;

/**
 * ★★ <b>B2（2026-10-10）：挂在会话工作表上的"当日视图"</b> —— {@link EconomyDayView} 的唯一实现（见该接口的类注）。
 *
 * <p>★★ <b>形态与成本（性能红线 §3.4）</b>：本类<b>只持一个</b> {@link EconomyStateBuilder} 引用； 构造 = 一次对象分配 + 一次 null
 * 检查（<b>引用级，与状态规模无关</b>）。每个 accessor 都是"现场问工作表"：
 *
 * <ul>
 *   <li>有工作副本的组件（家庭行/产业/市场/组织/在途/政府/发行/阶层归属）⇒ 走工作表的 {@code xxxOrBase()} —— <b>已物化读工作副本、未物化直接复用 base
 *       的不可变表</b>，因此读一次当刻值<b>不会</b>顺带拷一份表（零拷贝）；
 *   <li>无工作副本的组件（阶层位置/阶层结构/生产方式/需求/资料规则/市场区）⇒ 读 {@code sheet.base()} 的那张表： 它的写入口只有创世与 GM
 *       命令，<b>段内不变</b> ⇒ "段首表"与"当刻表"是同一份对象，不存在混合视图。
 * </ul>
 *
 * <p>★★ <b>为什么每次读取都现场解析、而不是构造时把 15 张表拍进字段</b>：工作副本是<b>惰性物化</b>的 （例如 {@code classMemberships}
 * 可能在段内某一天的迁移步骤里才第一次被写）—— 构造时拍下的引用会在那一刻 <b>变成过期快照</b>，那正是本批要消灭的 bug 形态。⇒ 视图必须是"活的解析器"，不是"快照"。
 *
 * <p>★ <b>不落盘、不进状态</b>：本类不 import 任何 codec/changeSet/revision 类型，也不调用任何 save/checkpoint/persist ——
 * 它只是把"哪一份表算当刻"这件事收敛到一处。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "本类就是会话工作表的只读门面（类注：解析器而非快照）：持有并转交工作表/表的引用即设计语义，拷贝会破坏'当刻值'语义")
final class WorkingDayView implements EconomyDayView {

  private final EconomyStateBuilder sheet;

  WorkingDayView(EconomyStateBuilder sheet) {
    this.sheet = Objects.requireNonNull(sheet, "sheet（当日视图必须挂在会话工作表上）");
  }

  @Override
  public Map<IndustryId, Industry> industries() {
    return sheet.industriesOrBase();
  }

  @Override
  public Map<HouseholdId, HouseholdEconomy> classes() {
    return sheet.householdEconomiesOrBase();
  }

  @Override
  public Map<HouseholdId, HouseholdClassMembership> classStandings() {
    return sheet.classMembershipsOrBase();
  }

  @Override
  public Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations() {
    return sheet.productionOrganizationsOrBase();
  }

  @Override
  public Map<ShipmentId, ShipmentBatch> shipments() {
    return sheet.shipmentsOrBase();
  }

  @Override
  public Map<HexCoord, Market> markets() {
    return sheet.marketsOrBase();
  }

  @Override
  public Map<GovernmentId, Government> governments() {
    return sheet.governmentsOrBase();
  }

  @Override
  public Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances() {
    return sheet.moneyIssuancesOrBase();
  }

  @Override
  public Map<ModeTransitionId, ModeTransition> modeTransitions() {
    return sheet.modeTransitionsOrBase();
  }

  // ── 以下六张表**没有**工作副本（写入口只有创世/命令）⇒ 段内不变，视图即 base 的那一份 ──────────────
  //    ★ 它们仍走本视图（而不是让读者各自去摸 base）：一处拼写"当刻值"，读者只认这一个来源。

  @Override
  public Map<ClassPositionId, ProductionRole> classPositions() {
    return sheet.base().classPositions();
  }

  @Override
  public Map<ClassStructureId, ClassStructure> classStructures() {
    return sheet.base().classStructures();
  }

  @Override
  public Map<ProductionModeId, ProductionMode> modes() {
    return sheet.base().modes();
  }

  @Override
  public Map<DemandId, HouseholdDemand> demands() {
    return sheet.base().demands();
  }

  @Override
  public Map<AssetRuleId, AssetRule> assetRules() {
    return sheet.base().assetRules();
  }

  @Override
  public Map<MarketZoneId, MarketZone> marketZones() {
    return sheet.base().marketZones();
  }

  @Override
  public String toString() {
    // ★ 只报身份、不读任何状态：视图的 toString 不该成为"偷偷读一份表"的地方。
    return "WorkingDayView";
  }
}
