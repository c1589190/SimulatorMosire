package io.mosire.simos.social.household;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdView;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.lookup.HouseholdLookup;
import io.mosire.simos.social.api.lookup.PopulationLookup;
import io.mosire.simos.social.api.population.AgeBracketView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>只读 SPI 的 social 侧实现</b>（2026-10-09 家户/人口架构 §5）：把一份 {@link SocialData} 包成
 * {@link HouseholdLookup} + {@link PopulationLookup}，供 Unit/Gov/Economy/Culture/Religion 只依赖契约层消费
 * （S3 的挂接点；本阶段先落在这里，避免 S3 再写第二份读取口径）。
 *
 * <p>★ <b>时间口径</b>：{@code ageBrackets} 的档位要"锚点 + 时间差"现算 ⇒ 本适配器在构造期绑定 {@code nowTick} 与
 * {@link CalendarClock}。它因此是<b>一次查询窗口</b>的只读视图（快照 + 时刻），不是长期持有的服务；推进后要重建。
 *
 * <p>★ 所有查询无副作用；家户不存在 ⇒ {@link Optional#empty()}，人口类 ⇒ {@code 0} / 空表（与 SPI 的 Javadoc 一致）。
 */
public final class SocialLookupAdapter implements HouseholdLookup, PopulationLookup {

  private final SocialData data;
  private final long nowTick;
  private final CalendarClock clock;

  public SocialLookupAdapter(SocialData data, long nowTick, CalendarClock clock) {
    this.data = Objects.requireNonNull(data, "data");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (nowTick < 0L) {
      throw new IllegalArgumentException("nowTick 不得为负: " + nowTick);
    }
    this.nowTick = nowTick;
  }

  @Override
  public Optional<HouseholdView> household(HouseholdId householdId) {
    Objects.requireNonNull(householdId, "householdId");
    Household household = data.households().get(householdId);
    if (household == null) {
      return Optional.empty();
    }
    return Optional.of(view(household));
  }

  @Override
  public List<HouseholdView> at(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    List<HouseholdView> out = new ArrayList<>();
    for (Household household : data.householdsAt(hex)) {
      out.add(view(household));
    }
    return List.copyOf(out);
  }

  @Override
  public List<HouseholdView> inUnit(String unitId) {
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 不得为空白");
    }
    List<HouseholdView> out = new ArrayList<>();
    for (Household household : data.householdsInUnit(unitId)) {
      out.add(view(household));
    }
    return List.copyOf(out);
  }

  @Override
  public long populationAt(HexCoord hex) {
    return data.populationAt(hex);
  }

  @Override
  public long unitPopulation(String unitId) {
    return data.unitPopulation(unitId);
  }

  @Override
  public long householdPopulation(HouseholdId householdId) {
    return data.householdPopulation(householdId);
  }

  @Override
  public List<AgeBracketView> ageBrackets(HouseholdId householdId) {
    Objects.requireNonNull(householdId, "householdId");
    return data.ageBrackets(householdId, nowTick, clock);
  }

  private HouseholdView view(Household household) {
    return new HouseholdView(
        household.id(),
        household.location(),
        household.profile(),
        household.memberLots(),
        data.ageBrackets(household.id(), nowTick, clock));
  }
}
