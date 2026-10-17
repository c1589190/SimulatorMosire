package io.mosire.simos.app.tools.social;

import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.tools.SocialToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Objects;

/**
 * {@link SocialToolViews} 的宿主实现（组合根）：**逐条转调** {@link ToolSupport#population}（它又转调 GUI 的 {@code
 * ApiViews.population}）——视图只有一份，本类对下放工具的行为是**恒等**的。
 *
 * <p>★ <b>为什么历法时钟由本类持有</b>：{@code ApiViews.population} 要世界 {@link CalendarService}（{@code at} 的日期与
 * tick→历法年换算同源），而历法服务的装配属于组合根（域工具不该自己造一台时钟）——与 B3 第二域 {@code AppArmyManpowerPort} 持有 {@code
 * CalendarClock} 同一条处置。
 */
public final class AppSocialToolViews implements SocialToolViews {

  private final CalendarService calendars;

  public AppSocialToolViews(CalendarService calendars) {
    this.calendars = Objects.requireNonNull(calendars, "calendars");
  }

  @Override
  public Map<String, Object> population(HexCoord coord, SimulationState state) {
    return ToolSupport.population(
        ToolSupport.socialData(state),
        ToolSupport.economyData(state),
        io.mosire.simos.economy.EconomyProductionDefinitions.stateOf(state).instances(),
        io.mosire.simos.economy.EconomyProductionDefinitions.of(state),
        coord,
        state.meta().timestamp(),
        calendars);
  }
}
