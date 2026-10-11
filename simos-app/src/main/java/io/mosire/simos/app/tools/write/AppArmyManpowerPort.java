package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.army.tools.ArmyManpowerPort;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ArmyManpowerPort} 的宿主实现（组合根）：**逐条转调** {@link
 * HouseholdManpowerAllocator#allocateFromHouseholds} （选人层的唯一实现——它同时服务征兵 / 组军 / 科举 / 消化单位四条仍在 app
 * 的调用链）。
 *
 * <p>★★ <b>为什么这个适配器住在 {@code app.tools.write} 而不是 {@code app.tools.army}</b>： {@code
 * HouseholdManpowerAllocator} 是**包内可见**（{@code app.tools.write}），本类要调它就必须同包。★ 这是刻意的：
 * 选人层的**可见性一字未改**（不为一次下放把它放宽成 public），代价只是适配器与它同包（旁边还有一个同类先例——app 的窄写基类也在 这个包）。
 *
 * <p>★ <b>过滤口径逐字照抄迁入前的调用点</b>：{@code Optional.of(Sex.MALE)} + {@code Optional.of(AgeBracket.ADULT)}
 * + {@code Set.of()}（排除集为空）——战斗人员伤亡按 {@code MALE + ADULT} 抽人，不换年龄档、不部分抽。
 *
 * <p>★ <b>历法时钟由本类持有</b>：年龄档现算要用 {@code CalendarClock}，而 {@code simos-calendar} 在 {@code simos-army}
 * 的禁列里 ⇒ 时钟留在组合根（生产路径 = 启动期 {@code CalendarService.load} 的同一实例，每次调用取 {@code clock()}， 与迁入前 {@code
 * ResolveCombatTool} 的取法逐字相同）。
 */
public final class AppArmyManpowerPort implements ArmyManpowerPort {

  private final CalendarService calendarService;

  public AppArmyManpowerPort(CalendarService calendarService) {
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  @Override
  public Allocation allocateMalesOfAdult(
      SocialData social, Set<HouseholdId> householdIds, long requested, long tick) {
    HouseholdManpowerAllocator.Allocation allocation =
        HouseholdManpowerAllocator.allocateFromHouseholds(
            social,
            householdIds,
            requested,
            calendarService.clock(),
            tick,
            Optional.of(Sex.MALE),
            Optional.of(AgeBracket.ADULT),
            Set.of());
    List<Share> shares = new ArrayList<>(allocation.shares().size());
    for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
      shares.add(new Share(share.householdId(), share.lotId(), share.hex(), share.taken()));
    }
    return new Allocation(shares);
  }
}
