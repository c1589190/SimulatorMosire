package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.LaborQueueWritebackBook.laborCommitmentContractFault;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 劳动承诺与生产名册的对账原语；包内实现，不构成跨模块 API（DEF-8）。 */
final class LaborCommitmentReconcileBook {
  private LaborCommitmentReconcileBook() {}

  /**
   * ★★★ <b>DEF-8：删除"角色已不在名册"的陈旧 PRODUCTION 劳动承诺</b>。
   *
   * <p>{@link ProductionRoster} 是 {@code (household, role)} 参与关系的唯一权威；历史队列/迁移/组织重建可能在 {@code
   * laborCommitments} 里留下角色已不在名册的 PRODUCTION 行。M7 的角色重查会对这种行 fail-closed（{@code role-unknown}），故必须在
   * M6 建按户承诺索引之前、以及 M7 聚合贡献之前各清一次（两处幂等）。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>只处理 {@code kind == PRODUCTION} 且 {@code productionRole} present 的行；GOV_SERVICE / 无角色行不动；
   *   <li>只处理当前仍存在的生产实例（{@code units}）；没有实例 ⇒ 旧路径不动；
   *   <li>{@code rosters} 缺失该 unit ⇒ 旧 unit/无 roster 的既有路径不动；
   *   <li>roster 存在且 {@code participant(household, role).isEmpty()} ⇒ 删除该行，INFO {@code
   *       LABOR_COMMITMENT_STALE_ROLE_REMOVED}（reason=role-not-in-roster）；
   *   <li>遍历按 {@link LaborAllocationId#value()} 稳定升序；
   *   <li>清理后重扫一次；仍有 stale 行 ⇒ 具名 ERROR + fail-closed。
   * </ul>
   *
   * <p>★ 不新增承诺、不修改 roster、不动合法角色/多 lot 行。
   *
   * @return 删除的承诺行数
   */
  static int pruneStaleRoleCommitments(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRoster> rosters,
      long day) {
    return pruneStaleRoleCommitments(laborCommitments, units, rosters, Map.of(), Map.of(), day);
  }

  /**
   * ★★★ <b>RC8b：带"当前有效工资参与户"判据的清理入口</b>。roster 仍是历史参与权威，但同 hex 的 MONEY_WAGE 本地响应者经 {@link
   * ProductionPlanBridge#isCurrentEffectiveParticipant} 判为当前有效参与户 ⇒ 不按 stale 删除。
   */
  static int pruneStaleRoleCommitments(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      long day) {
    Objects.requireNonNull(laborCommitments, "laborCommitments");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(rosters, "rosters");
    Map<IndustryId, IndustryDefinitionView> safeIndustries =
        industries == null ? Map.of() : industries;
    Map<HouseholdId, HouseholdEconomy> safeHouseholdEconomies =
        householdEconomies == null ? Map.of() : householdEconomies;
    List<LaborAllocationId> orderedIds = new ArrayList<>(laborCommitments.keySet());
    orderedIds.sort(Comparator.comparing(LaborAllocationId::value));
    int removed = 0;
    for (LaborAllocationId id : orderedIds) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(id);
      if (laborCommitment == null
          || !isStaleRosterRole(
              laborCommitment, units, rosters, safeIndustries, safeHouseholdEconomies)) {
        continue;
      }
      ProductionUnitId unit = new ProductionUnitId(laborCommitment.activity());
      ProductionRoleId role = laborCommitment.productionRole().orElseThrow();
      laborCommitments.remove(id);
      removed++;
      if (EconomyLog.population().isInfoEnabled()) {
        EventLog.channel(EconomyLog.population())
            .info(
                LogEvent.of(
                    "LABOR_COMMITMENT_STALE_ROLE_REMOVED",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "household",
                    laborCommitment.household().value(),
                    "unit",
                    unit.value(),
                    "role",
                    role.value(),
                    "id",
                    id.value(),
                    "reason",
                    "role-not-in-roster"));
      }
    }

    // ★ 契约后置条件：清理后重扫一次；仍有 stale 行 = 状态或清理逻辑自相矛盾，具名 ERROR + fail-closed。
    List<LaborAllocationId> remainingIds = new ArrayList<>(laborCommitments.keySet());
    remainingIds.sort(Comparator.comparing(LaborAllocationId::value));
    for (LaborAllocationId id : remainingIds) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(id);
      if (laborCommitment == null
          || !isStaleRosterRole(
              laborCommitment, units, rosters, safeIndustries, safeHouseholdEconomies)) {
        continue;
      }
      ProductionRoleId role = laborCommitment.productionRole().orElseThrow();
      throw laborCommitmentContractFault(
          laborCommitment.household(),
          day,
          "stale-role-commitments-remain-after-prune: id="
              + id.value()
              + " unit="
              + laborCommitment.activity()
              + " role="
              + role.value());
    }
    return removed;
  }

  /** DEF-8 + RC8b：该行是否是"当前名册没有、也不是同 hex MONEY_WAGE 当前有效参与户"的 PRODUCTION 承诺。 */
  private static boolean isStaleRosterRole(
      HouseholdLaborCommitment laborCommitment,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION
        || laborCommitment.productionRole().isEmpty()) {
      return false;
    }
    ProductionUnitId unit = new ProductionUnitId(laborCommitment.activity());
    ProductionProcess unitProcess = units.get(unit);
    if (unitProcess == null) {
      return false; // 当前没有该生产实例 ⇒ 旧路径，不动
    }
    ProductionRoster roster = rosters.get(unit);
    if (roster == null) {
      return false; // 无 roster 的旧路径，不动
    }
    IndustryDefinitionView industry =
        industries == null ? null : industries.get(unitProcess.industry());
    if (industry != null
        && ProductionPlanBridge.isCurrentEffectiveParticipant(
            unitProcess,
            industry,
            roster,
            laborCommitment.household(),
            laborCommitment.productionRole().get(),
            householdEconomies)) {
      return false;
    }
    return roster
        .participant(laborCommitment.household(), laborCommitment.productionRole().get())
        .isEmpty();
  }
}
