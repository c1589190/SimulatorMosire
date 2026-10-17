package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>名册的纯函数集合</b>（D-032 D2 §5.4 / §9.3）：单户默认名册、全局计划劳动合计、迁移/合户合并。
 *
 * <p>★ <b>为什么单列一个类</b>：默认名册的 "remaining = laborMilli − Σ现有名册.plannedLabor" 与合户的 "同 role 相加、rights
 * 并集、organizer OR" 必须只有一处拼写点；散在 {@code EconomyEnterpriseSettlement} / {@code
 * EconomyEntrySettlement} / 迁移器各写一遍必然漂开（本仓最贵的那类 bug）。
 *
 * <p>★ <b>纯函数</b>：不读状态、不写账户、不使用时钟/随机；输入输出都是不可变值。
 */
public final class ProductionRosters {

  private ProductionRosters() {}

  /** 某户在全部名册里的计划劳动之和（毫小时/日）—— 全局预算（D2-I13）的输入。 */
  public static long plannedLaborOf(
      Map<ProductionUnitId, ProductionRoster> rosters, HouseholdId household) {
    Objects.requireNonNull(rosters, "rosters");
    Objects.requireNonNull(household, "household");
    long total = 0L;
    for (ProductionRoster roster : rosters.values()) {
      for (ProductionParticipant participant : roster.participantsOf(household)) {
        total = Math.addExact(total, participant.plannedLaborMilliPerDay());
      }
    }
    return total;
  }

  /**
   * ★★ <b>D2 §5.4 的单户默认名册</b>。
   *
   * <p>前置（调用方判）：plan 只有<b>一个</b>角色、relation 的 {@code rules} 为空、该户 {@code remaining > 0}。 角色 =
   * {@code plan.roles().get(0).id()}；计划劳动 = {@code min(remaining, standardLabor>0 ?
   * standardLabor/cycleDays : remaining)}。
   *
   * @return 默认名册；planned 不足 1 毫小时/日 ⇒ empty（调用方记 {@code roster-required}，不写半条）
   */
  public static Optional<ProductionRoster> defaultSingleHouseholdRoster(
      ProductionUnitId unit,
      IndustryDefinitionView plan,
      HouseholdId household,
      long laborBudgetMilli) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(household, "household");
    if (plan.roles().size() != 1) {
      return Optional.empty();
    }
    ProductionRoleDefinition role = plan.roles().get(0);
    long standardLaborPerCycle = Math.max(0L, role.standardLaborMilliPerCycle());
    long cycleDays = Math.max(1L, plan.cycleDays());
    long standardPerDay =
        standardLaborPerCycle > 0L ? standardLaborPerCycle / cycleDays : laborBudgetMilli;
    long planned = Math.min(Math.max(0L, laborBudgetMilli), Math.max(0L, standardPerDay));
    if (planned <= 0L) {
      return Optional.empty();
    }
    List<ProductionParticipant> participants =
        List.of(new ProductionParticipant(household, role.id(), true, planned, List.of(), 0L));
    return Optional.of(new ProductionRoster(unit, plan.id(), participants));
  }

  /** 该 unit 是否已有名册（`roster-required` / 自动路径的判定读口）。 */
  public static boolean hasRoster(
      Map<ProductionUnitId, ProductionRoster> rosters, ProductionUnitId unit) {
    return rosters.containsKey(unit);
  }

  /** 名册里该户的角色集合（稳定序：首次出现序）。 */
  public static Set<ProductionRoleId> rolesOf(ProductionRoster roster, HouseholdId household) {
    Objects.requireNonNull(roster, "roster");
    Objects.requireNonNull(household, "household");
    Set<ProductionRoleId> roles = new LinkedHashSet<>();
    for (ProductionParticipant participant : roster.participantsOf(household)) {
      roles.add(participant.role());
    }
    return roles;
  }

  /**
   * ★★ <b>D2 §9.3：迁移/合户的名册纯函数</b> —— 每个含 {@code from} 的名册把 participant.household 改为 {@code to}；若
   * {@code to} 已在该 roster 的同一 role：plannedLabor 相加、rightsStakes 并集去重、 configuredWeight 相加、organizer
   * 取 OR。
   *
   * <p>★ <b>不做的两件事</b>（D2 §9.3 明文）：不复制份额数量（只移动引用）；不改 {@code unit.operator}/{@code
   * ProductionEnterprise.organizer}/{@code ProductionRules.operator} —— organizer 合户必须走 Phase M 的
   * {@code production.RebindInstanceOperator}，本方法只负责名册这一半。
   *
   * @param rosters 现有名册表（保序）
   * @param from 迁出户
   * @param to 迁入户
   * @return 合并后的新表（不可变；无变化时返回同内容的新表）
   */
  public static Map<ProductionUnitId, ProductionRoster> mergeHousehold(
      Map<ProductionUnitId, ProductionRoster> rosters, HouseholdId from, HouseholdId to) {
    Objects.requireNonNull(rosters, "rosters");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (from.equals(to)) {
      return rosters;
    }
    Map<ProductionUnitId, ProductionRoster> merged = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRoster> entry : rosters.entrySet()) {
      ProductionRoster roster = entry.getValue();
      if (roster.participantsOf(from).isEmpty()) {
        merged.put(entry.getKey(), roster);
        continue;
      }
      // 逐 role 聚合：键 = role，值 = 合并后的 participant。
      Map<ProductionRoleId, ProductionParticipant> byRole = new LinkedHashMap<>();
      for (ProductionParticipant participant : roster.participants()) {
        HouseholdId household = participant.household().equals(from) ? to : participant.household();
        ProductionParticipant moved =
            participant.household().equals(from)
                ? new ProductionParticipant(
                    household,
                    participant.role(),
                    participant.organizer(),
                    participant.plannedLaborMilliPerDay(),
                    participant.rightsStakes(),
                    participant.configuredWeight())
                : participant;
        ProductionParticipant existing = byRole.get(moved.role());
        if (existing == null) {
          byRole.put(moved.role(), moved);
          continue;
        }
        // 同 role 合并：labor/weight 相加、rights 并集、organizer OR。
        List<io.mosire.simos.economy.api.id.AssetShareId> rights = new ArrayList<>();
        Set<io.mosire.simos.economy.api.id.AssetShareId> seen =
            new LinkedHashSet<>(existing.rightsStakes());
        rights.addAll(existing.rightsStakes());
        for (io.mosire.simos.economy.api.id.AssetShareId stake : moved.rightsStakes()) {
          if (seen.add(stake)) {
            rights.add(stake);
          }
        }
        byRole.put(
            moved.role(),
            new ProductionParticipant(
                to,
                moved.role(),
                existing.organizer() || moved.organizer(),
                Math.addExact(existing.plannedLaborMilliPerDay(), moved.plannedLaborMilliPerDay()),
                rights,
                Math.addExact(existing.configuredWeight(), moved.configuredWeight())));
      }
      try {
        merged.put(
            entry.getKey(),
            new ProductionRoster(roster.unit(), roster.plan(), new ArrayList<>(byRole.values())));
      } catch (IllegalArgumentException multipleOrganizers) {
        // 两个 organizer 合户 ⇒ 合并后仍是同一主体，保留一个 organizer（D2 §9.3 明文）。
        merged.put(entry.getKey(), collapseDuplicateOrganizers(roster, byRole));
      }
    }
    return merged;
  }

  private static ProductionRoster collapseDuplicateOrganizers(
      ProductionRoster original, Map<ProductionRoleId, ProductionParticipant> byRole) {
    // 只可能因两个 organizer 合户而出现多个 true；保留第一个（稳定序），其余清 false。
    boolean kept = false;
    List<ProductionParticipant> participants = new ArrayList<>();
    for (ProductionParticipant participant : byRole.values()) {
      boolean organizer = participant.organizer() && !kept;
      kept = kept || organizer;
      participants.add(
          new ProductionParticipant(
              participant.household(),
              participant.role(),
              organizer,
              participant.plannedLaborMilliPerDay(),
              participant.rightsStakes(),
              participant.configuredWeight()));
    }
    return new ProductionRoster(original.unit(), original.plan(), participants);
  }
}
