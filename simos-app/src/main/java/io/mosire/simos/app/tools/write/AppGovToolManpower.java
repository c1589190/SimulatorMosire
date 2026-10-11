package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.gov.tools.GovToolManpower;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link GovToolManpower} 的宿主实现（组合根）：**逐条转调** {@code HouseholdManpowerAllocator}（选人唯一拼写点）+ 持有历法时钟。
 *
 * <p>★ <b>为什么这个适配器住 {@code app.tools.write} 而不是 {@code app.tools.gov}</b>： {@code
 * HouseholdManpowerAllocator} 是**包内可见**的（{@code static Allocation allocate…}），它的 {@code
 * Allocation}/{@code ManpowerShare} 也是包内 record ⇒ 不为一次下放放宽既有类的可见性（那是别的批次的领地）。 ★ 这是 army 批 D4
 * 已裁定、unit 批照抄的通用口径：**凡委托人包内可见 ⇒ 适配器住该包**。
 *
 * <p>★ {@code Allocation}/{@code ManpowerShare} 是**逐字段搬运**（含 {@code hex} 允许为 null 的既有语义）。
 */
public final class AppGovToolManpower implements GovToolManpower {

  private final CalendarService calendarService;

  public AppGovToolManpower(CalendarService calendarService) {
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  @Override
  public Allocation allocateMalesOfAdult(
      SimulationState state,
      List<Set<HexCoord>> jurisdictionHexesInOrder,
      long requested,
      long tick,
      Set<HouseholdId> excludedHouseholds) {
    return mirror(
        HouseholdManpowerAllocator.allocateMalesOfAdult(
            ToolSupport.socialData(state),
            jurisdictionHexesInOrder,
            requested,
            calendarService.clock(),
            tick,
            excludedHouseholds));
  }

  @Override
  public Allocation allocateFromHousehold(
      SimulationState state,
      HouseholdId householdId,
      long requested,
      long tick,
      Optional<Sex> sexFilter,
      Optional<AgeBracket> ageBracketFilter) {
    return mirror(
        HouseholdManpowerAllocator.allocateFromHousehold(
            ToolSupport.socialData(state),
            householdId,
            requested,
            calendarService.clock(),
            tick,
            sexFilter,
            ageBracketFilter));
  }

  @Override
  public Allocation allocateFromHouseholds(
      SimulationState state,
      Set<HouseholdId> householdIds,
      long requested,
      long tick,
      Optional<Sex> sexFilter,
      Optional<AgeBracket> ageBracketFilter,
      Set<HouseholdId> excludedHouseholds) {
    return mirror(
        HouseholdManpowerAllocator.allocateFromHouseholds(
            ToolSupport.socialData(state),
            householdIds,
            requested,
            calendarService.clock(),
            tick,
            sexFilter,
            ageBracketFilter,
            excludedHouseholds));
  }

  @Override
  public long householdLaborMilli(SocialData social, HouseholdId household, long tick) {
    return social.householdLaborMilli(household, tick, calendarService.clock());
  }

  private static Allocation mirror(HouseholdManpowerAllocator.Allocation allocation) {
    List<ManpowerShare> shares = new ArrayList<>(allocation.shares().size());
    for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
      shares.add(new ManpowerShare(share.householdId(), share.lotId(), share.hex(), share.taken()));
    }
    return new Allocation(
        allocation.requested(), allocation.available(), Collections.unmodifiableList(shares));
  }
}
