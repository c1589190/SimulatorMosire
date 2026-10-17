package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>economy 写入口对 production 定义权威的跨域引用校验</b>（D-031 P1 spec §3.3）。
 *
 * <p>★★ <b>为什么需要它</b>：引用完整性（unit→industry、assetShare→industry、{@code progressDays ≤ cycleDays}、
 * classStructure/classPosition/组织/规则/质押/变迁→mode、输出覆盖→产业配方产出键）原先住在 {@code EconomyData} 的构造期守卫里；定义迁出后
 * economy 状态构造器<b>无参照可查</b>。这些守卫必须在<b>业务写入口</b>显式重建，且不能等到下一次 advance 才发现悬空引用。纯 codec
 * 只验证本域；本类在命令候选态上逐条校验。
 *
 * <p>★ <b>只读</b>：只回答候选态是否自洽，不改任何状态；失败以 {@link IllegalArgumentException} 出面（命令层折 {@code Rejected}，不留
 * revision）。
 */
public final class EconomyReferenceValidation {

  private EconomyReferenceValidation() {}

  /**
   * 全量候选态校验；{@code label} 进拒绝理由（命令/播种点）。
   *
   * <p>★★ <b>B2b（§13.11.2 裁定六）</b>：{@code units} / {@code outputQuantityOverrides} 两维已从 {@code
   * EconomyData} 删除 ⇒ 本方法改从 production 切片的只读投影取它们 （{@link
   * io.mosire.simos.production.contract.ProductionStateLookup}）。判据<b>一条没删</b>， 只是"读哪一份"从 economy
   * 本地表换成 production 的权威候选态（I1/I13）。
   */
  public static void validate(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    Objects.requireNonNull(definitions, "definitions");
    Objects.requireNonNull(production, "production");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");
    validateUnitsAndShares(definitions, production, candidate, label);
    validateOutputQuantityOverrides(definitions, production, label);
    validateUnitCrossReferences(production, candidate, label);
    validateModeAssociations(definitions, candidate, label);
    validateModeStructures(definitions, candidate, label);
    validateLaborActorIndustryCollision(definitions, candidate, label);
    validateFlowsOnIndustryHex(definitions, candidate, label);
    validateDemandHexes(definitions, candidate, label);
    validateProductionRosters(definitions, production, candidate, label);
  }

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
   *   <li>逐户名册计划劳动之和 ≤ 该户当刻 {@code HouseholdEconomy.laborMilli}（D2-I13）；
   *   <li>{@code BY_RIGHTS}：份额存在、industry == plan、owner == 该户 actor，且不得跨 participant 重复引用；
   *   <li>{@code CONFIGURED_WEIGHTS}：权重只在该规则下允许非零且角色内至少一项 &gt; 0；其余规则必须 rights 空/weight 0；
   *   <li>名册方案 {@code ProductionRules.rules} 必须为空（D2-I17；否则具名 {@code
   *       legacy-compensation-rules-present}）；
   *   <li>有组织时 {@code ProductionEnterprise.laborSources} 必须等于名册全户派生集（否则 {@code
   *       enterprise-roster-mismatch}）。
   * </ul>
   */
  public static void validateProductionRosters(
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
    // 全局预算（D2-I13）：逐户跨全部名册合计 ≤ 当日 laborMilli。
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
      if (entry.getValue() > row.laborMilli()) {
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
   * ★★★ <b>生产实例引用/构造守卫（B2 批：改经 {@code production.contract} 的只读投影）</b>。
   *
   * <p>★★ <b>为什么它必须存在</b>：{@code EconomyData} 曾用"第 14 个组件 {@code units}"的构造期守卫判死
   * "实例指名的方案不存在"一类悬空引用。B2 把那份组件（连同它的守卫）从 economy 删掉后，<b>守卫本身不许跟着消失</b> —— 本方法就是它的新落点：判据逐条保留，读的却是
   * {@link ProductionRuntime#units()} 这份 <b>同一候选态的只读投影</b>（I13：不得跨 revision 陈旧）。
   *
   * <p>★★ <b>判据逐条（与旧 {@code EconomyData} 的同名守卫逐字同义）</b>：
   *
   * <ul>
   *   <li>实例的键必须与行内 id 一致（键≠值 = 状态自相矛盾）；
   *   <li>实例指名的方案必须在 production 的当刻权威里存在；
   *   <li>{@code progressDays} 必须落在 {@code [0, cycleDays]} 闭区间；
   *   <li>实例的经营者（operator）不得为空、地点不得是 {@code UNSPECIFIED} 占位 （§13.8 裁定二：地点有语义之处读到占位 ⇒
   *       <b>具名拒绝</b>，不静默当合法地址用）。
   * </ul>
   *
   * <p>★ <b>只读</b>：只回答投影是否自洽，不改任何状态；失败以 {@link IllegalArgumentException} 出面。
   *
   * @param runtime 生产运行时（其 {@link ProductionRuntime#units()} 是唯一权威的只读投影）；不得为 null
   * @param label 进拒绝理由（命令/播种点）
   */
  public static void validateProductionInstances(ProductionRuntime runtime, String label) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(label, "label");
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> entry :
        runtime.units().entrySet()) {
      ProductionProcess unit = entry.getValue();
      if (!entry.getKey().equals(unit.id())) {
        throw new IllegalArgumentException(
            label
                + " 的生产实例键与行内 id 不一致（状态自相矛盾）: 键="
                + entry.getKey().value()
                + " 行内 id="
                + unit.id().value());
      }
      IndustryDefinitionView industry =
          runtime
              .definitions()
              .industry(unit.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的生产实例指名的方案定义不存在（production 当刻权威）: unit="
                              + entry.getKey().value()
                              + " plan="
                              + unit.industry().value()));
      if (unit.progressDays() < 0L || unit.progressDays() > industry.cycleDays()) {
        throw new IllegalArgumentException(
            label
                + " 的生产进度越界（闭区间 [0, cycleDays]）: unit="
                + entry.getKey().value()
                + " progressDays="
                + unit.progressDays()
                + " cycleDays="
                + industry.cycleDays());
      }
      if (unit.operator() == null) {
        throw new IllegalArgumentException(
            label + " 的生产实例没有经营者（状态自相矛盾）: unit=" + entry.getKey().value());
      }
      if (!unit.location().isSpecified()) {
        // ★ §13.8 裁定二第 1 条：地点有语义之处读到 UNSPECIFIED ⇒ 具名拒绝（不是静默跳过）。
        throw new IllegalArgumentException(
            label
                + " 的生产实例地点是 UNSPECIFIED 占位（不是合法格）: unit="
                + entry.getKey().value()
                + " plan="
                + unit.industry().value());
      }
    }
  }

  /**
   * unit→industry 存在 + {@code progressDays ≤ cycleDays}；assetShare→industry 存在。
   *
   * <p>★ B2b：实例侧改读 {@code production.instances()}（权威投影），份额侧仍读 economy 的 {@code assetShares}。
   */
  public static void validateUnitsAndShares(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> entry :
        production.instances().entrySet()) {
      ProductionProcess unit = entry.getValue();
      IndustryDefinitionView industry =
          definitions
              .industry(unit.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的生产单元指名的产业定义不存在（production 当刻权威）: unit="
                              + entry.getKey().value()
                              + " industry="
                              + unit.industry().value()));
      if (unit.progressDays() > industry.cycleDays()) {
        throw new IllegalArgumentException(
            label
                + " 的生产进度超过产业周期：unit="
                + entry.getKey().value()
                + " progressDays="
                + unit.progressDays()
                + " cycleDays="
                + industry.cycleDays());
      }
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> entry :
        candidate.assetShares().entrySet()) {
      if (definitions.industry(entry.getValue().industry()).isEmpty()) {
        throw new IllegalArgumentException(
            label
                + " 的资产份额指名的产业定义不存在（production 当刻权威）: share="
                + entry.getKey().value()
                + " industry="
                + entry.getValue().industry().value());
      }
    }
  }

  /**
   * 输出覆盖：产业存在，且每个商品都在该产业 {@code recipe().outputPerUnit()} 键里。
   *
   * <p>★ B2b：改读 {@code production.outputQuantityOverrides()}（GM 覆盖的唯一权威）。
   */
  public static void validateOutputQuantityOverrides(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      String label) {
    for (Map.Entry<IndustryId, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> entry :
        production.outputQuantityOverrides().entrySet()) {
      IndustryDefinitionView industry =
          definitions
              .industry(entry.getKey())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的输出覆盖指名产业不存在（production 当刻权威）: industry="
                              + entry.getKey().value()));
      for (io.mosire.simos.economy.api.id.CommodityId commodity : entry.getValue().keySet()) {
        if (!industry.recipe().outputPerUnit().containsKey(commodity)) {
          throw new IllegalArgumentException(
              label
                  + " 的输出覆盖商品不在该产业配方产出键里: industry="
                  + entry.getKey().value()
                  + " commodity="
                  + commodity.value());
        }
      }
    }
  }

  /** mode/结构关联：classStructure/classPosition/组织/资产规则/质押/模式变迁指名的 mode 必须存在。 */
  public static void validateModeAssociations(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (ClassStructure structure : candidate.classStructures().values()) {
      requireMode(definitions, label, "classStructure", structure.id().value(), structure.modeId());
    }
    for (ProductionRole position : candidate.classPositions().values()) {
      requireMode(definitions, label, "classPosition", position.id().value(), position.modeId());
    }
    for (ProductionEnterprise enterprise : candidate.productionOrganizations().values()) {
      requireMode(
          definitions,
          label,
          "productionOrganization",
          enterprise.id().value(),
          enterprise.modeId());
    }
    for (AssetRule rule : candidate.assetRules().values()) {
      requireMode(definitions, label, "assetRule", rule.id().value(), rule.modeId());
    }
    for (Pledge pledge : candidate.pledges().values()) {
      requireMode(definitions, label, "pledge", pledge.id().value(), pledge.modeId());
    }
    for (ModeTransition transition : candidate.modeTransitions().values()) {
      requireMode(
          definitions,
          label,
          "modeTransition.from",
          transition.id().value(),
          transition.fromModeId());
      requireMode(
          definitions, label, "modeTransition.to", transition.id().value(), transition.toModeId());
    }
  }

  /** mode→classStructure 反向：production 的每个 mode 指名的结构必须在 economy 候选态里存在（与上面的结构→mode 合成双向闭环）。 */
  public static void validateModeStructures(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (io.mosire.simos.production.contract.ProductionModeDefinitionView mode :
        definitions.modes().values()) {
      if (!candidate.classStructures().containsKey(mode.classStructureId())) {
        throw new IllegalArgumentException(
            label
                + " 的生产方式指名的阶层结构不存在: mode="
                + mode.id().value()
                + " classStructureId="
                + mode.classStructureId().value()
                + "（同一 submitBatch 原子批里与 economy.GmAdjust.upsertClassStructure 一起创建即可）");
      }
    }
  }

  /**
   * ★★★ <b>B2b：实例 ↔ economy 表的交叉引用守卫</b>（原 {@code EconomyData} 构造期守卫的<b>新落点</b>）。
   *
   * <p>★★ <b>为什么必须重落而不是删</b>：{@code units} 从 economy 删除后，"economy 的表指着一条不存在的实例" 这类悬空引用就再也没有本域可查的参照物
   * —— 判据本身仍是 <b>fail-closed</b> 的一致性要求，故改读 production 的 当刻实例投影（{@link
   * io.mosire.simos.production.contract.ProductionStateLookup#instances()}）， 由候选提交前的跨片校验面执行。
   *
   * <p>★★ <b>判据逐条（与旧构造期守卫逐字同义）</b>：
   *
   * <ul>
   *   <li>{@code relations}：关系指名的实例必须存在，且 {@code relation.operator() == unit.operator()}
   *       （"谁经营"不许两处拼写）；
   *   <li>{@code operatorConditions}：键的实例必须存在，且值内 {@code industry == unit.industry()}；
   *   <li>{@code allocations}：{@code activity} 若形如 unit id（{@code unit-} 前缀）必须指向存在的实例 （悬空 ⇒
   *       具名拒；自由家户劳动用非 unit 活动词，合法）；命中实例时 {@code actor == unit.operator()}；
   *   <li>{@code productionOrganizations}：{@code unitId} 存在时必须指向存在的实例，且 {@code organizer ==
   *       unit.operator()}。
   * </ul>
   *
   * <p>★ <b>只读</b>：失败以 {@link IllegalArgumentException} 出面（命令层折 {@code Rejected}，不留 revision）。
   */
  public static void validateUnitCrossReferences(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
        production.instances();
    Map<String, ProductionProcess> byValue = new java.util.LinkedHashMap<>();
    for (ProductionProcess unit : instances.values()) {
      byValue.put(unit.id().value(), unit);
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionRules> entry :
        candidate.relations().entrySet()) {
      ProductionRules relation = entry.getValue();
      ProductionProcess unit = byValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的关系指名的生产实例不存在（production 当刻权威）: " + entry.getKey().value());
      }
      if (!relation.operator().equals(unit.operator())) {
        throw new IllegalArgumentException(
            label
                + " 的关系 operator 必须与实例 operator 一致（同一件事不许两处拼写）："
                + entry.getKey().value()
                + " 关系="
                + relation.operator()
                + "，实例="
                + unit.operator());
      }
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, OperatorCondition> entry :
        candidate.operatorConditions().entrySet()) {
      ProductionProcess unit = byValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的经营者条件指名的生产实例不存在（production 当刻权威）: " + entry.getKey().value());
      }
      if (!unit.industry().equals(entry.getValue().industry())) {
        throw new IllegalArgumentException(
            label
                + " 的经营者条件值内 industry 必须与实例 industry 一致：键="
                + entry.getKey().value()
                + "，实例 industry="
                + unit.industry().value()
                + "，值内="
                + entry.getValue().industry().value());
      }
    }
    for (HouseholdLaborCommitment commitment : candidate.allocations().values()) {
      ProductionProcess unit = byValue.get(commitment.activity());
      if (unit == null) {
        if (commitment.activity().startsWith("unit-")) {
          throw new IllegalArgumentException(
              label
                  + " 的劳动配额 activity 看起来是 unit id 但该实例不存在（悬空引用；自由家户劳动请用非 unit 的活动词）: "
                  + commitment.id().value()
                  + " activity="
                  + commitment.activity());
        }
        continue;
      }
      if (!unit.operator().equals(commitment.actor())) {
        throw new IllegalArgumentException(
            label
                + " 的劳动配额 activity 指名的实例与 actor 不一致（同一件事不许两处拼写）：activity="
                + commitment.activity()
                + " 实例 operator="
                + unit.operator()
                + "，actor="
                + commitment.actor());
      }
    }
    for (ProductionEnterprise enterprise : candidate.productionOrganizations().values()) {
      if (enterprise.unitId().isEmpty()) {
        continue;
      }
      String unitId = enterprise.unitId().get().value();
      ProductionProcess unit = byValue.get(unitId);
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的生产组织指名的实例必须已存在：组织=" + enterprise.id().value() + "，unitId=" + unitId);
      }
      if (!unit.operator().equals(enterprise.organizer())) {
        throw new IllegalArgumentException(
            label
                + " 的生产组织 organizer 必须与它指名实例的 operator 一致（同一件事不许两处拼写）：组织="
                + enterprise.id().value()
                + " organizer="
                + enterprise.organizer()
                + "，实例 operator="
                + unit.operator());
      }
    }
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

  /** 原 {@code EconomyData} 的 flows 守卫：flow 行指出的格必须登记过至少一个产业（无格键产业 ⇒ 对任何格都算"可能"）。 */
  public static void validateFlowsOnIndustryHex(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    if (definitions.industries().isEmpty()) {
      return; // 定义尚未提供（创世中间态）：最终候选态由统一校验闭环，不在本方法制造假拒绝
    }
    boolean hasHexlessIndustry =
        definitions.industries().keySet().stream()
            .anyMatch(id -> IndustryHexKeys.hexKeyOf(id).isEmpty());
    if (hasHexlessIndustry) {
      return; // 无格键产业 ⇒ 任何格都算已登记（与旧守卫同口径）
    }
    for (Map.Entry<io.mosire.simos.social.api.id.HouseholdId, io.mosire.simos.economy.model.FlowRow>
        entry : candidate.flows().entrySet()) {
      io.mosire.simos.economy.model.HouseholdEconomy household =
          candidate.classes().get(entry.getKey());
      if (household == null) {
        continue; // 对侧（classes）尚未提供 ⇒ 交给其它守卫/最终态
      }
      String hexKey =
          IndustryHexKeys.hexKey(household.view().hex().q(), household.view().hex().r());
      boolean registered =
          definitions.industries().keySet().stream()
              .anyMatch(id -> IndustryHexKeys.hexKeyOf(id).filter(hexKey::equals).isPresent());
      if (!registered) {
        throw new IllegalArgumentException(
            label
                + " 的 flow 行所在格没有登记任何产业（flows 引用了不存在的产业落点）: 家户="
                + entry.getKey().value()
                + " hex="
                + hexKey);
      }
    }
  }

  /**
   * 原 {@code EconomyData} 的 demands 守卫（产业维）：HEX 范围需求指名的格必须在产业/家户行/市场任一登记。
   *
   * <p>★★ <b>B5（§13.15.4 裁定二十）：{@code hexRegistered} 少的"产业"这一维的落点就是本方法</b> —— 本行 {@code
   * definitions.industries()} 那一支读 <b>production 当刻定义权威</b>，与下面两支（家户行 / 市场，读 {@code candidate}
   * 本域）合成同一个三元判据。⇒ 构造期 {@code EconomyData.hexRegistered(...)} 只判本域两维， 产业维在这里补齐（判据没丢、也没放宽）。
   *
   * <p>★ <b>边界（如实记）</b>：本方法跑在<b>候选态校验面</b>（注册点 {@code Shell.java:1199} 的 {@code
   * EconomyProductionReferenceValidator}；派发面见 P1 设计书 §9.3：单条命令 / {@code submitBatch} 最终候选态 /
   * restore / Advance 最终候选态落库前，以及 {@code CoreSimos.bootstrapGenesisInternal:399-402} 对创世态）。 ⇒ <b>单跑
   * codec（不经候选态校验面）时，"HEX 需求指名的格只有产业、没有家户/市场"这种状态不再被拒</b> —— 这是"定义副本从 economy
   * 删除"不可避免的边界，不是放宽：落库前的候选态校验面仍然 fail-closed。
   */
  public static void validateDemandHexes(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (io.mosire.simos.economy.model.HouseholdDemand demand : candidate.demands().values()) {
      if (demand.scope() != io.mosire.simos.economy.model.HouseholdDemand.DemandScope.HEX) {
        continue;
      }
      io.mosire.simos.map.hex.HexCoord hex = demand.hex().orElseThrow();
      String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
      boolean registered =
          definitions.industries().keySet().stream()
              .anyMatch(id -> IndustryHexKeys.hexKeyOf(id).filter(hexKey::equals).isPresent());
      if (!registered) {
        for (io.mosire.simos.economy.model.HouseholdEconomy household :
            candidate.classes().values()) {
          if (household.view().hex().equals(hex)) {
            registered = true;
            break;
          }
        }
      }
      if (!registered) {
        for (io.mosire.simos.economy.model.MarketTable table : candidate.marketTables().values()) {
          if (table.hexes().contains(hex)) {
            registered = true;
            break;
          }
        }
      }
      if (!registered) {
        throw new IllegalArgumentException(label + " 的 HEX 范围需求指名的格没有经济状态（产业/家户行/市场都没有）: " + hex);
      }
    }
  }

  private static void requireMode(
      ProductionDefinitionLookup definitions,
      String label,
      String where,
      String owner,
      io.mosire.simos.economy.api.id.ProductionModeId modeId) {
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label
              + " 的 "
              + where
              + " 指名生产方式不存在（production 当刻权威）: "
              + owner
              + " mode="
              + modeId.value());
    }
  }
}
