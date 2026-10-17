package io.mosire.simos.app.time;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.time.NextTickSubsistenceNeed;
import io.mosire.simos.economy.time.SubsistenceNeedKey;
import io.mosire.simos.economy.time.SubsistenceNeedLine;
import io.mosire.simos.economy.time.SurvivalSocialFacts;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 W4/W5-C：Social 下一 tick 生存事实的唯一 app 侧投影</b>。
 *
 * <p>真实日循环（{@link PopulationEconomyTimeParticipant}）与备用产权接线（{@link
 * EconomyOwnershipTimeParticipant}）必须从同一份 Social 事实生成只读 {@link SurvivalSocialFacts}；本类只做形状翻译，
 * 数值全部来自 {@link SocialData}，不复制任何需求/营养/劳动/匹配/支付公式。
 */
public final class SocialSurvivalFacts {

  private SocialSurvivalFacts() {}

  /** 逐户生成 {@code nextTick} 的下一 tick 需求行与劳动预算（稳定按 household id 排序）。 */
  public static SurvivalSocialFacts forNextTick(SocialData social, long nextTick) {
    Objects.requireNonNull(social, "social");
    if (nextTick < 0L) {
      throw new IllegalArgumentException("survival 下一 tick 不得为负: " + nextTick);
    }
    CalendarClock clock = CalendarClock.julianDefault();
    List<HouseholdId> ordered = new ArrayList<>(social.households().keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, NextTickSubsistenceNeed> needs = new LinkedHashMap<>();
    Map<HouseholdId, Long> laborBudgets = new LinkedHashMap<>();
    for (HouseholdId household : ordered) {
      Map<CommodityId, Long> naturalNeeds =
          social.householdNaturalNeeds(household, nextTick, clock);
      List<SubsistenceNeedLine> lines = new ArrayList<>(naturalNeeds.size());
      for (Map.Entry<CommodityId, Long> entry : naturalNeeds.entrySet()) {
        lines.add(
            new SubsistenceNeedLine(
                new SubsistenceNeedKey(entry.getKey().value()), entry.getKey(), entry.getValue()));
      }
      lines.sort(Comparator.comparing(line -> line.key().value()));
      needs.put(household, new NextTickSubsistenceNeed(nextTick, lines));
      laborBudgets.put(household, social.householdLaborMilli(household, nextTick, clock));
    }
    return SurvivalSocialFacts.of(nextTick, needs, laborBudgets);
  }
}
