package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionEnterprise.Status;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * ★★ <b>E6a：模式变迁执行阶段</b>（理想架构 §2.9/§4.4）：在日结算的自动组织阶段**之前**，把 {@link ModeTransition.Status#PENDING}
 * 且 {@code effectiveDay <= day} 的请求逐条应用。
 *
 * <p>★★ <b>它改哪些维、不改哪些维（逐条写清）</b>：
 *
 * <ul>
 *   <li><b>迁移（按模式身份）</b>：旧组织 → {@code EXITING}（保留 unitId/operator/laborSources/assetSources/
 *       inputSources/outputOwnership/relationTemplateRef）；新组织 {@code ACTIVE} 复用**同一条** {@code
 *       ProductionProcess}（不复制实物、不新 progress）；该 unit 的 {@code modeKey} 改为新 mode key；旧 {@code
 *       Pledge.modeId == fromMode} 的质押改成 toMode（暴露出的 mode 维）；对旧组织 laborSources ∪ organizer 家户逐户写
 *       {@link ClassShare}（旧位置 retain‰、新位置 (1000−retain)‰，0‰ 省略）并更新 {@link
 *       HouseholdClassMembership}
 *       （currentPosition/retainedShares/lastTransitionDay=day/reason；originalPosition 不动）。
 *   <li><b>按身份不动</b>：{@code OwnershipStake} 本身按 industry 存在，owner/operator/quantity/kind 一律不拆不复制；
 *       债务是家户间债权，不随 mode 复制；已有 {@code HouseholdLaborCommitment} 的 activity 就是复用中的 unit id，原样有效；
 *       relation 挂在 unit 上，原样有效。
 *   <li><b>失败不改状态</b>：规划阶段（找 mode/structure/position/hex/unit）任一具名失败 ⇒ 该 transition 落 {@code FAILED
 *       + 具名原因}，不修改组织/unit/质押/份额/standing；其余 transition 继续（逐条独立）。
 * </ul>
 *
 * <p>★★ <b>位置匹配</b>：由旧 position 的 {@code (relationToMeans, surplusRole)} 在 toMode 的 ClassStructure
 * 里找 同维位置；多个 ⇒ 取 id 字典序最小；一个都没有 ⇒ FAILED {@code NO_MATCHING_POSITION}。
 *
 * <p>★★ <b>格键</b>：从旧组织的 unit 的 industry id 与 assetSources 的 OwnershipStake.industry id 经 {@link
 * IndustryHexKeys#hexKeyOf} 推导；恰好一个不同格键才继续，0 个 ⇒ {@code NO_HEX_KEY}，多个 ⇒ {@code
 * AMBIGUOUS_HEX_KEY}。★ 家户 = organizer 是 HOUSEHOLD 时直接反查，否则取唯一的 laborSource；推不出 ⇒ {@code
 * NO_ORGANIZER_HOUSEHOLD}。
 *
 * <p>★ <b>确定性</b>：transition 按 id 升序；家户按 id 升序；份额/standing/Pledge 的写入序稳定；无随机、无时钟、无
 * UUID。本类是协调器单线程阶段：只写调用方交进来的工作副本；一次 revision 的原子性由 {@code EconomySession} 的一次 {@code build()} 承担。
 */
final class EconomyModeTransitionSettlement {

  /** 变迁逐条日志（settlement 分类；Outcome 计数在调用点另有一条 INFO）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  static final String REASON_ORG_NOT_FOUND = "ORG_NOT_FOUND";
  static final String REASON_ORG_EXITING = "ORG_EXITING";
  static final String REASON_ORG_MODE_MISMATCH = "ORG_MODE_MISMATCH";
  static final String REASON_ORG_HAS_NO_UNIT = "ORG_HAS_NO_UNIT";
  static final String REASON_UNIT_NOT_FOUND = "UNIT_NOT_FOUND";
  static final String REASON_UNIT_OPERATOR_MISMATCH = "UNIT_OPERATOR_MISMATCH";
  static final String REASON_TO_MODE_NOT_FOUND = "TO_MODE_NOT_FOUND";
  static final String REASON_SAME_MODE = "TO_MODE_SAME_AS_FROM";
  static final String REASON_TO_CLASS_STRUCTURE_NOT_FOUND = "TO_CLASS_STRUCTURE_NOT_FOUND";
  static final String REASON_FROM_POSITION_NOT_FOUND = "FROM_POSITION_NOT_FOUND";
  static final String REASON_NO_MATCHING_POSITION = "NO_MATCHING_POSITION";
  static final String REASON_NO_HEX_KEY = "NO_HEX_KEY";
  static final String REASON_AMBIGUOUS_HEX_KEY = "AMBIGUOUS_HEX_KEY";
  static final String REASON_NO_ORGANIZER_HOUSEHOLD = "NO_ORGANIZER_HOUSEHOLD";
  static final String REASON_HOUSEHOLD_NOT_FOUND = "HOUSEHOLD_NOT_FOUND";
  static final String REASON_TARGET_ORGANIZATION_EXISTS = "TARGET_ORGANIZATION_EXISTS";
  static final String REASON_CLASS_SHARE_CONFLICT = "CLASS_SHARE_CONFLICT";

  private EconomyModeTransitionSettlement() {}

  /** 执行结果：APPLIED/FAILED 条数与是否写了任何工作副本（供调用方决定后续是否重建索引）。 */
  record Outcome(int applied, int failed, boolean changed) {

    Outcome {
      if (applied < 0 || failed < 0) {
        throw new IllegalArgumentException(
            "Outcome 计数不得为负: applied=" + applied + "，failed=" + failed);
      }
    }

    static Outcome empty() {
      return new Outcome(0, 0, false);
    }
  }

  /**
   * ★★ <b>应用所有到期 PENDING 变迁</b>。写口全部是调用方的工作副本（{@code LinkedHashMap}），在 {@code EconomySession} 的同一个
   * revision 内；规划失败逐条具名落 FAILED，不抛出（除非状态本身违反构造期不变量 —— 那由后续 {@code build} 响亮失败）。
   *
   * @param base 结算前的不可变状态（读 modes/classStructures/classPositions 三张只读表）
   * @param day 当前世界日（{@code effectiveDay <= day} 才应用；也是 standing 的 lastTransitionDay）
   * @param rows 家户行工作副本（读 household 存在性）
   * @param enterprises 生产组织工作副本（读旧组织、写旧 EXITING + 新 ACTIVE）
   * @param units 生产单元工作副本（写复用 unit 的 modeKey）
   * @param assetShares 实物资产份额表（只读；用于推导 org 的格键）
   * @param pledges 质押表工作副本（写 modeId）
   * @param classStandings 家户阶层归属工作副本（写 current/retained/lastTransition/reason）
   * @param modeTransitions 模式变迁工作副本（PENDING → APPLIED/FAILED）
   * @param classShares 阶层保留份额工作副本（新增成功户的份额）
   */
  static Outcome apply(
      EconomyDayView base,
      long day,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<PledgeId, Pledge> pledges,
      LinkedHashMap<HouseholdId, HouseholdClassMembership> classMemberships,
      LinkedHashMap<ModeTransitionId, ModeTransition> modeTransitions,
      LinkedHashMap<ClassShareId, ClassShare> classShares) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(enterprises, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(pledges, "pledges");
    Objects.requireNonNull(classMemberships, "classStandings");
    Objects.requireNonNull(modeTransitions, "modeTransitions");
    Objects.requireNonNull(classShares, "classShares");
    if (modeTransitions.isEmpty()) {
      return Outcome.empty();
    }

    List<ModeTransition> due = new ArrayList<>();
    for (ModeTransition transition : modeTransitions.values()) {
      if (transition.status() == ModeTransition.Status.PENDING
          && transition.effectiveDay() <= day) {
        due.add(transition);
      }
    }
    due.sort(Comparator.comparing(transition -> transition.id().value()));

    boolean changed = false;
    int applied = 0;
    int failed = 0;
    for (ModeTransition transition : due) {
      Plan plan =
          plan(
              base,
              transition,
              day,
              householdEconomies,
              enterprises,
              units,
              assetShares,
              pledges,
              classMemberships,
              classShares);
      if (plan.failureReason != null) {
        modeTransitions.put(
            transition.id(),
            transition.withStatus(ModeTransition.Status.FAILED, plan.failureReason));
        changed = true;
        failed++;
        if (LOG.isTraceEnabled()) {
          EventLog.channel(LOG)
              .trace(
                  LogEvent.of(
                      "MODE_TRANSITION_FAILED",
                      EconomyLogSource.ECONOMY_MIGRATION,
                      "day",
                      day,
                      "transition",
                      transition.id().value(),
                      "organization",
                      transition.organizationId().value(),
                      "fromMode",
                      transition.fromModeId().value(),
                      "toMode",
                      transition.toModeId().value(),
                      "effectiveDay",
                      transition.effectiveDay(),
                      "reasonLength",
                      plan.failureReason.length()));
        }
        continue;
      }
      // ★ 规划已全部通过：下面只落工作副本，不再做可失败判断（构造期不变量异常照常抛出，不伪装成 FAILED）。
      enterprises.put(plan.exitingOrganization.id(), plan.exitingOrganization);
      enterprises.put(plan.newOrganization.id(), plan.newOrganization);
      units.put(plan.updatedUnit.id(), plan.updatedUnit);
      pledges.putAll(plan.updatedPledges);
      classMemberships.putAll(plan.updatedClassMemberships);
      classShares.putAll(plan.newClassShares);
      modeTransitions.put(transition.id(), plan.appliedTransition);
      changed = true;
      applied++;
      if (LOG.isTraceEnabled()) {
        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "MODE_TRANSITION_APPLIED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "transition",
                    transition.id().value(),
                    "organization",
                    transition.organizationId().value(),
                    "fromMode",
                    transition.fromModeId().value(),
                    "toMode",
                    transition.toModeId().value(),
                    "newOrganization",
                    plan.newOrganization.id().value(),
                    "unit",
                    plan.updatedUnit.id().value()));
      }
    }
    return new Outcome(applied, failed, changed);
  }

  /** 规划结果：全部可写对象在规划期算好；{@code failureReason != null} ⇒ 只写 transition 的 FAILED 终态。 */
  private record Plan(
      String failureReason,
      ProductionEnterprise exitingOrganization,
      ProductionEnterprise newOrganization,
      ProductionProcess updatedUnit,
      Map<PledgeId, Pledge> updatedPledges,
      Map<ClassShareId, ClassShare> newClassShares,
      Map<HouseholdId, HouseholdClassMembership> updatedClassMemberships,
      ModeTransition appliedTransition) {}

  private static Plan plan(
      EconomyDayView base,
      ModeTransition transition,
      long day,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<PledgeId, Pledge> pledges,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<ClassShareId, ClassShare> classShares) {
    ProductionEnterprise enterprise = enterprises.get(transition.organizationId());
    if (enterprise == null) {
      return failed(REASON_ORG_NOT_FOUND + ":" + transition.organizationId().value());
    }
    if (enterprise.status() == Status.EXITING) {
      return failed(REASON_ORG_EXITING + ":" + transition.organizationId().value());
    }
    if (!enterprise.modeId().equals(transition.fromModeId())) {
      return failed(
          REASON_ORG_MODE_MISMATCH
              + ":org="
              + enterprise.modeId().value()
              + ",transition.from="
              + transition.fromModeId().value());
    }
    if (transition.toModeId().equals(transition.fromModeId())) {
      return failed(REASON_SAME_MODE + ":" + transition.toModeId().value());
    }
    if (enterprise.unitId().isEmpty()) {
      return failed(REASON_ORG_HAS_NO_UNIT + ":" + transition.organizationId().value());
    }
    ProductionUnitId unitId = enterprise.unitId().get();
    ProductionProcess unit = units.get(unitId);
    if (unit == null) {
      return failed(REASON_UNIT_NOT_FOUND + ":" + unitId.value());
    }
    if (!unit.operator().equals(enterprise.organizer())) {
      return failed(
          REASON_UNIT_OPERATOR_MISMATCH
              + ":unit="
              + unitId.value()
              + ",unit.operator="
              + unit.operator()
              + ",org.organizer="
              + enterprise.organizer());
    }

    ProductionMode toMode = base.modes().get(transition.toModeId());
    if (toMode == null) {
      return failed(REASON_TO_MODE_NOT_FOUND + ":" + transition.toModeId().value());
    }
    ClassStructure structure = base.classStructures().get(toMode.classStructureId());
    if (structure == null) {
      return failed(REASON_TO_CLASS_STRUCTURE_NOT_FOUND + ":" + toMode.classStructureId().value());
    }
    ProductionRole oldPosition = base.classPositions().get(enterprise.classPositionId());
    if (oldPosition == null) {
      return failed(REASON_FROM_POSITION_NOT_FOUND + ":" + enterprise.classPositionId().value());
    }
    ClassPositionId newPosition = matchPosition(structure, oldPosition);
    if (newPosition == null) {
      return failed(
          REASON_NO_MATCHING_POSITION
              + ":relation="
              + oldPosition.relationToMeans().name()
              + ",surplus="
              + oldPosition.surplusRole().name());
    }

    Set<String> hexKeys = resolveHexKeys(enterprise, unit, assetShares);
    if (hexKeys.isEmpty()) {
      return failed(REASON_NO_HEX_KEY + ":unit=" + unitId.value());
    }
    if (hexKeys.size() > 1) {
      return failed(REASON_AMBIGUOUS_HEX_KEY + ":" + String.join(",", hexKeys));
    }
    String hexKey = hexKeys.iterator().next();

    HouseholdId organizerHousehold = resolveOrganizerHousehold(enterprise);
    if (organizerHousehold == null) {
      return failed(REASON_NO_ORGANIZER_HOUSEHOLD + ":organizer=" + enterprise.organizer());
    }
    if (!householdEconomies.containsKey(organizerHousehold)) {
      return failed(REASON_HOUSEHOLD_NOT_FOUND + ":" + organizerHousehold.value());
    }
    ProductionOrganizationId newEnterpriseId =
        ProductionOrganizationId.idOf(
            transition.toModeId(), newPosition, organizerHousehold, hexKey);
    if (newEnterpriseId.equals(enterprise.id()) || enterprises.containsKey(newEnterpriseId)) {
      return failed(REASON_TARGET_ORGANIZATION_EXISTS + ":" + newEnterpriseId.value());
    }

    // ★ 逐户（laborSources ∪ organizer 家户；去重、id 升序）：生成两条 ClassShare + 更新 HouseholdClassMembership。
    Set<HouseholdId> households = new LinkedHashSet<>(enterprise.laborSources());
    households.add(organizerHousehold);
    List<HouseholdId> orderedHouseholds = new ArrayList<>(households);
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));

    int retain = transition.retainOriginalPerMille();
    int migrated = 1000 - retain;
    Map<ClassShareId, ClassShare> newShares = new LinkedHashMap<>();
    Map<HouseholdId, HouseholdClassMembership> updatedClassMemberships = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      if (!householdEconomies.containsKey(household)) {
        return failed(REASON_HOUSEHOLD_NOT_FOUND + ":" + household.value());
      }
      if (retain > 0) {
        ClassShareId oldShareId =
            ClassShareId.idOf(transition.id(), household, enterprise.classPositionId());
        ClassShare oldShare =
            new ClassShare(
                oldShareId, transition.id(), household, enterprise.classPositionId(), retain);
        ClassShare conflict = classShares.get(oldShareId);
        if (conflict != null && !conflict.equals(oldShare)) {
          return failed(REASON_CLASS_SHARE_CONFLICT + ":" + oldShareId.value());
        }
        newShares.put(oldShareId, oldShare);
      }
      if (migrated > 0) {
        ClassShareId newShareId = ClassShareId.idOf(transition.id(), household, newPosition);
        ClassShare newShare =
            new ClassShare(newShareId, transition.id(), household, newPosition, migrated);
        ClassShare conflict = classShares.get(newShareId);
        if (conflict != null && !conflict.equals(newShare)) {
          return failed(REASON_CLASS_SHARE_CONFLICT + ":" + newShareId.value());
        }
        newShares.put(newShareId, newShare);
      }

      HouseholdClassMembership existingClassMembership = classMemberships.get(household);
      ClassPositionId currentPosition = retain == 1000 ? enterprise.classPositionId() : newPosition;
      Map<ClassPositionId, Long> retainedShares = new LinkedHashMap<>();
      if (retain > 0) {
        retainedShares.put(enterprise.classPositionId(), (long) retain);
      }
      if (migrated > 0) {
        retainedShares.put(newPosition, (long) migrated);
      }
      HouseholdClassMembership nextClassMembership =
          new HouseholdClassMembership(
              household,
              existingClassMembership == null
                  ? enterprise.classPositionId()
                  : existingClassMembership.originalPositionId(),
              currentPosition,
              // ★ P2-B：模式变迁后只参与新位置；旧的可参与集合属于旧 mode，不再沿用。
              Set.of(),
              retainedShares,
              existingClassMembership == null
                  ? 0L
                  : existingClassMembership.consecutiveDebtStressCycles(),
              day,
              transition.reason());
      updatedClassMemberships.put(household, nextClassMembership);
    }

    // ★ Pledge 的 mode 维：凡 modeId == fromMode 的质押（含非 ACTIVE）改挂 toMode；其余字段逐值不动。
    Map<PledgeId, Pledge> updatedPledges = new LinkedHashMap<>();
    for (Map.Entry<PledgeId, Pledge> entry : pledges.entrySet()) {
      Pledge pledge = entry.getValue();
      if (pledge.modeId().equals(transition.fromModeId())) {
        updatedPledges.put(
            entry.getKey(),
            new Pledge(
                pledge.id(),
                pledge.debtContractId(),
                pledge.assetShareId(),
                pledge.quantity(),
                transition.toModeId(),
                pledge.priority(),
                pledge.status()));
      }
    }

    ProductionProcess updatedUnit =
        new ProductionProcess(
            unit.id(),
            unit.industry(),
            unit.operator(),
            EconomyEnterpriseSettlement.MODE_KEY_PREFIX + transition.toModeId().value(),
            unit.progressDays(),
            unit.cycleLaborMilli(),
            unit.cycleInputUsedMilli());
    ProductionEnterprise exitingOrganization =
        new ProductionEnterprise(
            enterprise.id(),
            enterprise.modeId(),
            enterprise.classPositionId(),
            enterprise.unitId(),
            enterprise.organizer(),
            enterprise.laborSources(),
            enterprise.assetSources(),
            enterprise.inputSources(),
            enterprise.outputOwnership(),
            enterprise.relationTemplateRef(),
            Status.EXITING,
            enterprise.statusReason());
    ProductionEnterprise newOrganization =
        new ProductionEnterprise(
            newEnterpriseId,
            transition.toModeId(),
            newPosition,
            Optional.of(unitId),
            enterprise.organizer(),
            enterprise.laborSources(),
            enterprise.assetSources(),
            enterprise.inputSources(),
            enterprise.outputOwnership(),
            enterprise.relationTemplateRef(),
            Status.ACTIVE,
            "");
    return new Plan(
        null,
        exitingOrganization,
        newOrganization,
        updatedUnit,
        updatedPledges,
        newShares,
        updatedClassMemberships,
        transition.withStatus(ModeTransition.Status.APPLIED, transition.reason()));
  }

  private static Plan failed(String reason) {
    return new Plan(reason, null, null, null, Map.of(), Map.of(), Map.of(), null);
  }

  /** 由 (relationToMeans, surplusRole) 在结构内匹配新位置；多个取 id 字典序最小；没有 ⇒ null。 */
  private static ClassPositionId matchPosition(
      ClassStructure structure, ProductionRole oldPosition) {
    ClassPositionId best = null;
    for (Map.Entry<ClassPositionId, ProductionRole> entry : structure.positions().entrySet()) {
      ProductionRole candidate = entry.getValue();
      if (candidate.relationToMeans() != oldPosition.relationToMeans()
          || candidate.surplusRole() != oldPosition.surplusRole()) {
        continue;
      }
      if (best == null || entry.getKey().value().compareTo(best.value()) < 0) {
        best = entry.getKey();
      }
    }
    return best;
  }

  /** 从旧 org 的 unit.industry 与 assetSources 的行业 id 推导格键集合（去重、字典序；空/多值都由调用方具名落）。 */
  private static Set<String> resolveHexKeys(
      ProductionEnterprise enterprise,
      ProductionProcess unit,
      Map<AssetShareId, OwnershipStake> assetShares) {
    Set<String> keys = new TreeSet<>();
    IndustryHexKeys.hexKeyOf(unit.industry()).ifPresent(keys::add);
    for (AssetShareId shareId : enterprise.assetSources()) {
      OwnershipStake share = assetShares.get(shareId);
      if (share != null) {
        IndustryHexKeys.hexKeyOf(share.industry()).ifPresent(keys::add);
      }
    }
    return keys;
  }

  /** organizer 对应的家户：HOUSEHOLD actor 直接反查；否则取唯一的 laborSource；推不出 ⇒ null。 */
  private static HouseholdId resolveOrganizerHousehold(ProductionEnterprise enterprise) {
    if (enterprise.organizer().kind() == ActorKind.HOUSEHOLD) {
      try {
        return HouseholdActors.householdOf(enterprise.organizer());
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    List<HouseholdId> sources = new ArrayList<>(enterprise.laborSources());
    sources.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId candidate : sources) {
      if (HouseholdActors.of(candidate).equals(enterprise.organizer())) {
        return candidate;
      }
    }
    return sources.size() == 1 ? sources.get(0) : null;
  }
}
