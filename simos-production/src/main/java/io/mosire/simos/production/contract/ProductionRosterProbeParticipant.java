package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>roster 试算里的一个参与项</b>（D-032 P3a §8；economics 的名册事实 → production 的不可变输入）。
 *
 * <p>★★ <b>它只表达"谁在哪个角色上计划出多少工"</b>：生产资料、原料、熟练度/经验仍在 {@link HouseholdProductionConditions}
 * 里按户给一次（同一户多角色不得重复计投入/资产，见 {@link ProductionRosterProbeRequest} 的聚合口径）。
 *
 * <p>★ <b>量纲 = 毫小时/周期</b>（本周期累计，与 {@link HouseholdProductionConditions#laborMilli()} 同口径； 生产周期由方案
 * {@code cycleDays} 决定）。★ 不要与 economy 名册的 {@code plannedLaborMilliPerDay}（毫小时/日）混淆： 后者 × {@code
 * cycleDays} 才是本字段。
 *
 * <p>★★ <b>{@code plannedLaborMilliPerCycle == 0} 合法</b>：零劳动 organizer（只组织/收剩余）是用户 U15 明确允许的形态；
 * 它不表示"这个参与项缺条件"，也不得被静默当成"整份 roster 没劳动"——是否 labor-short 由整份 roster 的劳动合计 与工资招工目标共同判（见 {@link
 * ProductionRosterProbeRequest}）。
 *
 * @param household 参与家户；不得为 null
 * @param role 方案内生产角色；不得为 null（角色是否存在由请求求值时 fail-closed 判）
 * @param plannedLaborMilliPerCycle 本周期计划劳动（毫小时/周期）；不得为负（0 = 该参与项本周期不出工）
 */
public record ProductionRosterProbeParticipant(
    HouseholdId household, ProductionRoleId role, long plannedLaborMilliPerCycle) {

  public ProductionRosterProbeParticipant {
    Objects.requireNonNull(household, "ProductionRosterProbeParticipant.household 不得为 null");
    Objects.requireNonNull(role, "ProductionRosterProbeParticipant.role 不得为 null");
    if (plannedLaborMilliPerCycle < 0L) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeParticipant.plannedLaborMilliPerCycle 不得为负（毫小时/周期）: "
              + plannedLaborMilliPerCycle);
    }
  }

  /** 本参与项的复合键（家户 × 角色）——与 execute 回执/效率表的键同一形状。 */
  public ProductionParticipantKey participantKey() {
    return new ProductionParticipantKey(household, role);
  }
}
