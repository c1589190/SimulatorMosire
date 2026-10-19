package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** economy 引用校验拆分 helper（由 EconomyReferenceValidation 抽取；行为不变）。 */
final class HouseholdReferenceValidation {

  private HouseholdReferenceValidation() {}

  /**
   * ★★★ <b>D-032 D2 §7.1：角色定义 ↔ 名册的交叉校验</b>（C2 新增的唯一拼写点）。
   *
   * <p>★ <b>只读</b>：在候选态上判"名册是否与 production 当刻定义/实例、economy 当刻资产份额与家户预算自洽"； 失败以 {@link
   * IllegalArgumentException} 具名出面（命令层折 {@code Rejected}，不留 revision）。
   *
   * <p>判据（逐条对应 D2 §7.1 / D2-I1–I18）：
   *
   * <ul>
   *   <li>unit 存在且 production 实例的 plan == roster.plan；
   *   <li>角色 id 全部存在于 plan.roles；每个 {@code PRODUCT_SHARE} 定义角色至少一个 participant（MONEY_WAGE 可无）；
   *   <li>恰好一个 organizer（{@link ProductionRoster} 构造期已判）；organizer 的角色必须是 {@code PRODUCT_SHARE}；
   *   <li>{@code unit.operator == HouseholdActors.of(organizerHousehold)}；
   *   <li>role 级 {@code standardLaborPerCycle>0} 的非工资角色至少一名 participant {@code plannedLabor>0}；
   *   <li>逐户名册计划劳动之和 ≤ 该户当刻 {@code HouseholdEconomy.laborMilli}（D2-I13；<b>仅写入口</b>强制， 运行时见 {@code
   *       enforcePlannedLaborBudget}）；
   *   <li>{@code BY_RIGHTS}：份额存在、industry == plan、owner == 该户 actor，且不得跨 participant 重复引用；
   *   <li>{@code CONFIGURED_WEIGHTS}：权重只在该规则下允许非零且角色内至少一项 &gt; 0；其余规则必须 rights 空/weight 0；
   *   <li>名册方案 {@code ProductionRules.rules} 必须为空（D2-I17；否则具名 {@code
   *       legacy-compensation-rules-present}）；
   *   <li>有组织时 {@code ProductionEnterprise.laborSources} 必须等于名册全户派生集（否则 {@code
   *       enterprise-roster-mismatch}）。
   * </ul>
   *
   * <p>★★ <b>D-032 W5-E：默认入口 = 写入口语义</b>（强制 D2-I13；{@code EconomySetProductionRosterHandler} /
   * {@code EconomySeedHandler} 用它，写坏预算的候选态仍具名拒 {@code household-labor-overcommitted}）。运行时全量校验请走
   * {@link #validateProductionRosters(ProductionDefinitionLookup,
   * io.mosire.simos.production.contract.ProductionStateLookup, EconomyData, String, boolean)} 并传
   * {@code false}。
   */
  public static void validateProductionRosters(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    validateProductionRosters(definitions, production, candidate, label, true);
  }

  /**
   * 名册跨片校验（显式选择是否强制名册计划劳动预算）。
   *
   * @param enforcePlannedLaborBudget {@code true} = 写入口语义：逐户计划劳动之和 &gt; 当刻预算 ⇒ 具名拒 {@code
   *     household-labor-overcommitted}；{@code false} = 运行时语义：仍校验参与户存在于家户行表，但把"历史计划 &gt; 当刻预算"
   *     交给实际分配处的预算封顶（不改 roster 计划值、不缩短 AdvanceTime）。
   */
  public static void validateProductionRosters(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label,
      boolean enforcePlannedLaborBudget) {
    Objects.requireNonNull(definitions, "definitions");
    Objects.requireNonNull(production, "production");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");
    Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
        production.instances();
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionRoster> entry :
        candidate.productionRosters().entrySet()) {
      io.mosire.simos.economy.api.id.ProductionUnitId unitId = entry.getKey();
      ProductionRoster roster = entry.getValue();
      ProductionProcess unit = instances.get(unitId);
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的名册指名了不存在的生产实例（roster-instance-unknown）: " + unitId.value());
      }
      IndustryDefinitionView plan =
          definitions
              .industry(unit.plan())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的名册实例方案定义不存在: unit="
                              + unitId.value()
                              + " plan="
                              + unit.plan().value()));
      if (!roster.plan().equals(plan.id())) {
        throw new IllegalArgumentException(
            label
                + " 的名册 plan 与 production 实例不一致（roster-plan-mismatch）: unit="
                + unitId.value()
                + " roster="
                + roster.plan().value()
                + " instance="
                + plan.id().value());
      }
      Map<io.mosire.simos.economy.api.production.ProductionRoleId, ProductionRoleDefinition>
          rolesById = new java.util.LinkedHashMap<>();
      for (ProductionRoleDefinition role : plan.roles()) {
        rolesById.put(role.id(), role);
      }
      // 角色存在性 + 每个 PRODUCT_SHARE 角色至少一个 participant。
      Map<io.mosire.simos.economy.api.production.ProductionRoleId, Integer> participantsByRole =
          new java.util.LinkedHashMap<>();
      for (io.mosire.simos.economy.model.ProductionParticipant participant :
          roster.participants()) {
        ProductionRoleDefinition role = rolesById.get(participant.role());
        if (role == null) {
          throw new IllegalArgumentException(
              label
                  + " 的名册含未知角色（role-unknown）: unit="
                  + unitId.value()
                  + " role="
                  + participant.role().value());
        }
        participantsByRole.merge(participant.role(), 1, Integer::sum);
      }
      for (ProductionRoleDefinition role : plan.roles()) {
        if (role.payment() instanceof RolePayment.ProductShare
            && participantsByRole.getOrDefault(role.id(), 0) == 0) {
          throw new IllegalArgumentException(
              label
                  + " 的名册缺少 PRODUCT_SHARE 角色参与项（role-unfilled）: unit="
                  + unitId.value()
                  + " role="
                  + role.id().value());
        }
      }
      // organizer 角色必须是 PRODUCT_SHARE；operator 必须解析到 organizer 家户 actor。
      ProductionRoleDefinition organizerRole = rolesById.get(roster.organizer().role());
      if (organizerRole == null || !(organizerRole.payment() instanceof RolePayment.ProductShare)) {
        throw new IllegalArgumentException(
            label
                + " 的名册 organizer 角色必须是 PRODUCT_SHARE（D2-I3）: unit="
                + unitId.value()
                + " role="
                + roster.organizer().role().value());
      }
      if (unit.operator().kind() != ActorKind.HOUSEHOLD
          || !unit.operator().equals(HouseholdActors.of(roster.organizerHousehold()))) {
        throw new IllegalArgumentException(
            label
                + " 的 unit.operator 与名册 organizer 不一致（organizer-actor-mismatch）: unit="
                + unitId.value()
                + " operator="
                + unit.operator()
                + " organizer="
                + roster.organizerHousehold().value());
      }
      // 角色内劳动/权重/权益规则。
      java.util.Set<io.mosire.simos.economy.api.id.AssetShareId> referencedShares =
          new java.util.LinkedHashSet<>();
      Map<io.mosire.simos.economy.api.production.ProductionRoleId, Long> positiveLaborByRole =
          new java.util.LinkedHashMap<>();
      Map<io.mosire.simos.economy.api.production.ProductionRoleId, Long> weightByRole =
          new java.util.LinkedHashMap<>();
      for (io.mosire.simos.economy.model.ProductionParticipant participant :
          roster.participants()) {
        ProductionRoleDefinition role = rolesById.get(participant.role());
        if (role == null) {
          continue; // 上面已拒
        }
        if (participant.plannedLaborMilliPerDay() > 0L) {
          positiveLaborByRole.merge(
              participant.role(), participant.plannedLaborMilliPerDay(), Math::addExact);
        }
        switch (role.intraRoleSplit()) {
          case BY_RIGHTS -> {
            if (!participant.rightsStakes().isEmpty()) {
              for (io.mosire.simos.economy.api.id.AssetShareId share : participant.rightsStakes()) {
                if (!referencedShares.add(share)) {
                  throw new IllegalArgumentException(
                      label
                          + " 的名册 rightsStakes 被跨 participant 重复引用（rights-duplicate）: unit="
                          + unitId.value()
                          + " share="
                          + share.value());
                }
                OwnershipStake stake = candidate.assetShares().get(share);
                if (stake == null) {
                  throw new IllegalArgumentException(
                      label
                          + " 的名册 rightsStakes 引用的份额不存在（rights-not-owned）: unit="
                          + unitId.value()
                          + " share="
                          + share.value());
                }
                if (!stake.industry().equals(plan.id())) {
                  throw new IllegalArgumentException(
                      label
                          + " 的名册 rightsStakes 的产业与 plan 不一致（rights-not-owned）: unit="
                          + unitId.value()
                          + " share="
                          + share.value());
                }
                if (!stake.owner().equals(HouseholdActors.of(participant.household()))) {
                  throw new IllegalArgumentException(
                      label
                          + " 的名册 rightsStakes 的 owner 不是该参与户（rights-not-owned）: unit="
                          + unitId.value()
                          + " share="
                          + share.value());
                }
              }
            }
            if (participant.configuredWeight() != 0L) {
              throw new IllegalArgumentException(
                  label
                      + " 的 BY_RIGHTS 角色不得给 configuredWeight（configured-weight-invalid）: unit="
                      + unitId.value()
                      + " role="
                      + role.id().value());
            }
          }
          case CONFIGURED_WEIGHTS -> {
            if (!participant.rightsStakes().isEmpty()) {
              throw new IllegalArgumentException(
                  label
                      + " 的 CONFIGURED_WEIGHTS 角色不得给 rightsStakes（rights-not-owned）: unit="
                      + unitId.value()
                      + " role="
                      + role.id().value());
            }
            weightByRole.merge(participant.role(), participant.configuredWeight(), Math::addExact);
          }
          case EQUAL, BY_RECOGNIZED_LABOR -> {
            if (!participant.rightsStakes().isEmpty() || participant.configuredWeight() != 0L) {
              throw new IllegalArgumentException(
                  label
                      + " 的 EQUAL/BY_RECOGNIZED_LABOR 角色不得给 rights/weight（configured-weight-invalid）: unit="
                      + unitId.value()
                      + " role="
                      + role.id().value());
            }
          }
        }
      }
      for (ProductionRoleDefinition role : plan.roles()) {
        if (role.intraRoleSplit() == IntraRoleSplitRule.CONFIGURED_WEIGHTS
            && participantsByRole.getOrDefault(role.id(), 0) > 0
            && weightByRole.getOrDefault(role.id(), 0L) <= 0L) {
          throw new IllegalArgumentException(
              label
                  + " 的 CONFIGURED_WEIGHTS 角色内权重必须至少一项 > 0（configured-weight-invalid）: unit="
                  + unitId.value()
                  + " role="
                  + role.id().value());
        }
        if (!(role.payment() instanceof RolePayment.MoneyWage)
            && role.standardLaborMilliPerCycle() > 0L
            && positiveLaborByRole.getOrDefault(role.id(), 0L) <= 0L) {
          throw new IllegalArgumentException(
              label
                  + " 的劳动者角色没有任何 plannedLabor>0 的参与项（role-unfilled）: unit="
                  + unitId.value()
                  + " role="
                  + role.id().value());
        }
      }
      // 旧补偿规则禁用（D2-I17）。
      ProductionRules relation = candidate.relations().get(unitId);
      if (relation != null && !relation.rules().isEmpty()) {
        throw new IllegalArgumentException(
            label
                + " 的名册方案不得同时保留旧补偿规则（legacy-compensation-rules-present）: unit="
                + unitId.value()
                + " rules="
                + relation.rules().size());
      }
      // enterprise.laborSources 派生一致性（D2 §4.3）。
      for (ProductionEnterprise enterprise : candidate.productionOrganizations().values()) {
        if (enterprise.unitId().isPresent() && enterprise.unitId().get().equals(unitId)) {
          List<HouseholdId> derived = roster.households();
          if (!enterprise.laborSources().equals(derived)) {
            throw new IllegalArgumentException(
                label
                    + " 的组织 laborSources 与名册全户派生集不一致（enterprise-roster-mismatch）: unit="
                    + unitId.value());
          }
        }
      }
    }
    // 全局预算（D2-I13）：逐户跨全部名册合计 ≤ 当日 laborMilli —— **只在写入口强制**。
    //   ★ D-032 W5-E：运行时（enforce=false）仍校验"参与户必须存在"，但不把历史计划快照当成硬拒条件：
    //     实际劳动分配由 LaborQueueSettlement / EconomySettlement.applyLaborBudgetsInto 按当刻预算封顶。
    Map<HouseholdId, Long> plannedByHousehold = new java.util.LinkedHashMap<>();
    for (ProductionRoster roster : candidate.productionRosters().values()) {
      for (Map.Entry<HouseholdId, Long> planned : roster.plannedLaborByHousehold().entrySet()) {
        plannedByHousehold.merge(planned.getKey(), planned.getValue(), Math::addExact);
      }
    }
    for (Map.Entry<HouseholdId, Long> entry : plannedByHousehold.entrySet()) {
      HouseholdEconomy row = candidate.classes().get(entry.getKey());
      if (row == null) {
        throw new IllegalArgumentException(label + " 的名册参与户不在家户行表里: " + entry.getKey().value());
      }
      if (enforcePlannedLaborBudget && entry.getValue() > row.laborMilli()) {
        throw new IllegalArgumentException(
            label
                + " 的名册计划劳动超过家户当日预算（household-labor-overcommitted）: household="
                + entry.getKey().value()
                + " planned="
                + entry.getValue()
                + " budget="
                + row.laborMilli());
      }
    }
  }

  /**
   * ★★★ <b>D-032 P4c3：U20 工资锚 / 工资进位的跨切片校验</b>。
   *
   * <p>★ 只读：在候选态上判锚是否与 production 当刻定义以及 economy 当刻市场区自洽；失败以 {@link IllegalArgumentException}
   * 具名出面（命令层折 {@code Rejected}，不留 revision）。
   *
   * <p>判据：
   *
   * <ul>
   *   <li>anchor：键 == 值内 {@code (marketZone, industry, role)}；industry/role 存在且 payment = {@code
   *       MONEY_WAGE}；marketZone 命中持久区表时 currency == legalTender、仅命中派生市场价表时 currency ==
   *       numeraire，两者都不命中 ⇒ {@code wage-anchor-market-zone-missing}；quote ≥ 0；
   *   <li>carry：unit 存在；role 存在且为 {@code MONEY_WAGE}；worker 家户存在；值域由构造期判。
   * </ul>
   */
  public static void validateProductionWages(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    Objects.requireNonNull(definitions, "definitions");
    Objects.requireNonNull(production, "production");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");

    Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
        production.instances();

    // ── ★★★ D-032 P4b1 §6.1/§6.4：U20 工资锚的跨切片身份校验 ─────────────────────────────────
    //   键 = (marketZone, industry, role)；industry/role 必须在 production 当刻定义里存在且 role 为 MONEY_WAGE。
    //   ★★★ D-032 P4fix BUG-1：锚的 marketZone 合法来源 = 持久市场区表 **或** 市场价表：
    //     ① candidate.marketZones() 命中 ⇒ currency == zone.legalTender；
    //     ② 仅 candidate.marketTables() 命中（派生市场形态：single-region / currency:<币>，它们本来就是
    //        economy 的市场表键）⇒ currency == table.numeraire；
    //     ③ 两者都不命中 ⇒ 原 wage-anchor-market-zone-missing:<id> fail-closed。
    //   ★ 不按 0 兜底、不改锚键形状、不在运行期重键。
    for (Map.Entry<WageAnchorKey, ProductionWageAnchor> entry :
        candidate.productionWageAnchors().entrySet()) {
      WageAnchorKey key = entry.getKey();
      ProductionWageAnchor value = entry.getValue();
      if (key == null || value == null) {
        throw new IllegalArgumentException("productionWageAnchors 的键与值都不得为 null: " + key);
      }
      IndustryDefinitionView plan =
          definitions
              .industry(key.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "wage-anchor-industry-unknown:" + key.industry().value()));
      ProductionRoleDefinition role = roleOf(plan, key.role());
      if (role == null) {
        throw new IllegalArgumentException("wage-anchor-role-unknown:" + key.role().value());
      }
      if (!(role.payment() instanceof RolePayment.MoneyWage)) {
        throw new IllegalArgumentException("wage-role-not-money:" + key.role().value());
      }
      io.mosire.simos.economy.api.market.MarketZone zone =
          candidate.marketZones().get(key.marketZone());
      if (zone != null) {
        if (!value.currency().equals(zone.legalTender())) {
          throw new IllegalArgumentException(
              "wage-anchor-currency-mismatch:" + key.marketZone().value());
        }
      } else {
        MarketTable table = candidate.marketTables().get(key.marketZone());
        if (table == null) {
          throw new IllegalArgumentException(
              "wage-anchor-market-zone-missing:" + key.marketZone().value());
        }
        if (!value.currency().equals(table.numeraire())) {
          throw new IllegalArgumentException(
              "wage-anchor-currency-mismatch:" + key.marketZone().value());
        }
      }
    }

    // ── ★★★ D-032 W2b §2.4：逐户工资进位余数 ───────────────────────────────────────────────
    //   值域已由 EconomyData 构造期判；此处补跨切片身份：unit 存在、role 存在且为 MONEY_WAGE。
    for (Map.Entry<WageCarryKey, Long> entry : candidate.wageCarryRemainders().entrySet()) {
      WageCarryKey key = entry.getKey();
      Long carry = entry.getValue();
      if (carry == null || carry < 0L || carry > 999L) {
        throw new IllegalArgumentException("wage-carry-out-of-range: " + key + "=" + carry);
      }
      ProductionProcess instance = instances.get(key.unit());
      if (instance == null) {
        throw new IllegalArgumentException("wage-carry-unit-unknown:" + key.unit().value());
      }
      IndustryDefinitionView plan =
          definitions
              .industry(instance.plan())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的工资进位实例方案定义不存在: unit="
                              + key.unit().value()
                              + " plan="
                              + instance.plan().value()));
      ProductionRoleDefinition role = roleOf(plan, key.role());
      if (role == null) {
        throw new IllegalArgumentException("wage-carry-role-unknown:" + key.role().value());
      }
      if (!(role.payment() instanceof RolePayment.MoneyWage)) {
        throw new IllegalArgumentException("wage-role-not-money:" + key.role().value());
      }
      if (!candidate.classes().isEmpty() && !candidate.classes().containsKey(key.worker())) {
        throw new IllegalArgumentException("wage-worker-account-missing:" + key.worker().value());
      }
    }
  }

  static ProductionRoleDefinition roleOf(
      IndustryDefinitionView plan, io.mosire.simos.economy.api.production.ProductionRoleId role) {
    for (ProductionRoleDefinition candidate : plan.roles()) {
      if (candidate.id().equals(role)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * 原 {@code EconomyData} 的"actor 撞名产业"守卫：非 ORGANIZATION/HOUSEHOLD 的劳动主体 id 不得等于任何产业 id（结算按 actor
   * id 归属劳动，撞名会把配额静默算进那个产业）。
   */
  public static void validateLaborActorIndustryCollision(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (HouseholdLaborCommitment commitment : candidate.allocations().values()) {
      ActorKind kind = commitment.actor().kind();
      boolean mayResolveToIndustry = kind == ActorKind.ORGANIZATION || kind == ActorKind.HOUSEHOLD;
      if (!mayResolveToIndustry) {
        String actorId = commitment.actor().id();
        for (IndustryId industryId : definitions.industries().keySet()) {
          if (industryId.value().equals(actorId)) {
            throw new IllegalArgumentException(
                label
                    + " 的劳动配额 actor 与产业 id 撞名（非 ORGANIZATION/HOUSEHOLD 不得与产业 id 相同）: actor="
                    + kind
                    + "|"
                    + actorId
                    + " industry="
                    + industryId.value());
          }
        }
      }
    }
  }
}
