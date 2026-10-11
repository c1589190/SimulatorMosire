package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.tools.UnitToolAllocations;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link UnitToolAllocations} 的宿主实现（组合根）：**逐条转调两座既有唯一瀑布**，只做结果类型的逐字段搬运。
 *
 * <ul>
 *   <li><b>家户账瀑布</b> → {@code RegionAllocations.allocateAccounts(ApiViews.actorData(state),
 *       ToolSupport.socialData(state), region, label, requested, availableOf)}——可用量的减法仍走 {@code
 *       AvailableStock} 的对应重载（本类不写减法）；调用点传的 {@code availableOf} 与迁入前逐字同源（商品维度 = {@code
 *       AvailableStock.available(inventory, commodity)}，货币维度同理）；
 *   <li><b>选人瀑布</b> → {@code HouseholdManpowerAllocator.allocateMalesOfAdult(social,
 *       List.of(region.hexes()), requested, calendarService.clock(), tick, excluded)}——{@code MALE
 *       + ADULT} 的属性过滤、全序、守恒都在那一份实现里； ★ 历法时钟取自构造期注入的 {@link CalendarService}（生产 = {@code
 *       CalendarService.load} 的同一实例，旧测试路径 = {@code CalendarService.defaults()}），与迁入前 {@code
 *       LevyRegionTool}/{@code RaiseUnitTool} 的取法**逐字同源**。
 * </ul>
 *
 * <p>★ 本类住在 {@code app.tools.write}：{@code RegionAllocations} 与 {@code HouseholdManpowerAllocator}
 * 都是**包内可见**的 （B3 第二域台账 D4 的既有先例——不为一次下放放宽既有类的可见性）。
 *
 * <p>★ 结果映射是**值搬运**、不是第二份算法：排序键、瀑布序、守恒校验都仍在 app 的唯一实现里；映射后逐字段相等（字段名与顺序逐字相同）。
 */
public final class AppUnitToolAllocations implements UnitToolAllocations {

  private final CalendarService calendarService;

  public AppUnitToolAllocations(CalendarService calendarService) {
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  @Override
  public AccountAllocation allocateCommodityAccounts(
      SimulationState state, Region region, String label, long requested, CommodityId commodity) {
    return adapt(
        RegionAllocations.allocateAccounts(
            ApiViews.actorData(state),
            ToolSupport.socialData(state),
            region,
            label,
            requested,
            inventory -> AvailableStock.available(inventory, commodity)));
  }

  @Override
  public AccountAllocation allocateMoneyAccounts(
      SimulationState state, Region region, String label, long requested, CurrencyId currency) {
    return adapt(
        RegionAllocations.allocateAccounts(
            ApiViews.actorData(state),
            ToolSupport.socialData(state),
            region,
            label,
            requested,
            inventory -> AvailableStock.available(inventory, currency)));
  }

  @Override
  public ManpowerAllocation allocateMalesOfAdult(
      SocialData social, Region region, long requested, long tick, Set<HouseholdId> excluded) {
    HouseholdManpowerAllocator.Allocation allocation =
        HouseholdManpowerAllocator.allocateMalesOfAdult(
            social, List.of(region.hexes()), requested, calendarService.clock(), tick, excluded);
    List<ManpowerShare> shares = new ArrayList<>(allocation.shares().size());
    for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
      shares.add(new ManpowerShare(share.householdId(), share.lotId(), share.hex(), share.taken()));
    }
    return new ManpowerAllocation(allocation.requested(), allocation.available(), shares);
  }

  /** app 侧账分摊结果 → 端口结果（逐字段同形：requested / available / sources{owner,at,amount}）。 */
  private static AccountAllocation adapt(RegionAllocations.AccountAllocation allocation) {
    List<AccountSource> sources = new ArrayList<>(allocation.sources().size());
    for (RegionAllocations.AccountSource source : allocation.sources()) {
      sources.add(new AccountSource(source.owner(), source.at(), source.amount()));
    }
    return new AccountAllocation(allocation.requested(), allocation.available(), sources);
  }
}
