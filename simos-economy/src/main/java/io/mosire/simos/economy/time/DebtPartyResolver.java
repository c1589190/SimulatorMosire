package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.migrate.ClassPositionResolver;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassPosition.RelationToMeans;
import io.mosire.simos.economy.model.ClassPosition.SurplusRole;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>P2：债务端点 → 责任家户的唯一解析点</b>。
 *
 * <p>E4c 的资本化原先只做 {@code householdOfActor.get(payer/payee)}：ESTATE / WORKSHOP 这类聚合 operator 不在家户
 * actor 表里 ⇒ 欠款永远落不成合同，真档 {@code debts == 0}。本类把"这个主体/受方由哪些家户承担责任"按固定次序解析一次， 产出合计严格 1000‰ 的 {@link
 * PartyShare}；解析不到返回具名 {@link Resolution#reason()}，绝不伪造端点。
 *
 * <p><b>actor 解析顺序</b>：
 *
 * <ol>
 *   <li><b>direct</b>：actor 本身就是家户 actor（{@link SettlementIndex#householdByActor()}）⇒ 该户 1000‰；
 *   <li><b>resolver</b>：用 E1 的唯一结果 {@link SettlementIndex#economicHouseholdOf(ProductionUnitId)}
 *       找"actor 出现的 unit"（operator / relation.operator / relation.residualOwner /
 *       relation.inputSupplier(ToActor) / 份额 owner / 份额 operator 六路命中均算）。命中且家户唯一 ⇒
 *       1000‰；命中多户时聚合主体继续走 ③；
 *   <li><b>organization</b>：聚合主体先用 E2 的 {@link ProductionOrganization}（{@code organizer == actor} 或
 *       对应 unit 的 {@code operator == actor}）的 {@code laborSources} / 家户归属解析；
 *   <li><b>population-fallback</b>：ESTATE ⇒ 该 hex 上处于"所有者/剩余索取者"位置的家户（优先 {@code relationToMeans ==
 *       OWNER} 或 {@code surplusRole == SURPLUS_RECEIVER}；旧档无位置信息时回落 {@code legacy-landlord} /
 *       {@link SocialClassId#LANDLORD}）；WORKSHOP ⇒ {@code legacy-artisan} / {@link
 *       SocialClassId#ARTISAN}；
 *   <li>仍解析不到 ⇒ 空 + 具名 reason（{@code no-population-composition:...} 等）。
 * </ol>
 *
 * <p><b>人口拆分的唯一口径</b>：用 {@link ClassRow#population()}（不用 {@code Membership.count} 二次聚合）。
 * 理由是行人口已经是经济切片的权威存量，且与口粮/劳动/需求同源；{@code Membership} 的批次归属可能滞后于死亡回写，
 * 两处口径一旦漂开，"谁人口多谁多担债"会静默改变。拆分走全仓唯一实现 {@link ProportionalSplit}（最大余数法）， 目标家户按 {@link
 * HouseholdId#value()} 升序，故可重放。
 *
 * <p><b>金额拆分</b>：{@link #splitDebtAmounts} 先按债务人份额切 owed、再逐债务人按债权人份额切；两级都用 {@link
 * ProportionalSplit}，并用 {@code Math.addExact} 核对 "合同金额 + 自债净额 == owed"，跨组合不丢不多。 组合数上界 {@link
 * #MAX_PARTY_COMBINATIONS}；超限返回具名 {@code too-many-party-combinations:...}， 由调用方落 {@code
 * UnresolvedDebtCapitalization}，不静默丢金额。
 *
 * <p>★★ <b>自债（debtor == creditor）显式净额、不落合同</b>：同户对自己的债权无经济意义，且还款会铸出 "两端相等"的非法转移。{@link DebtSplit}
 * 把它单独记为 {@link DebtSplit#selfNettedAmount()}，金额仍守恒、 由调用方写成具名 unresolved（"self-party-netted"），不静默丢。
 */
final class DebtPartyResolver {

  /** 组合数上界：超过就具名拒绝，不做无界展开（真档每端通常个位数家户）。 */
  static final long MAX_PARTY_COMBINATIONS = 4096L;

  static final String SOURCE_DIRECT = "direct";
  static final String SOURCE_RELATION = "resolver";
  static final String SOURCE_ORGANIZATION = "organization";
  static final String SOURCE_POPULATION_FALLBACK = "population-fallback";
  static final String SOURCE_COHORT = "cohort";

  private DebtPartyResolver() {}

  /** 一个责任家户的千分份额；{@code source}/{@code reason} 是读口可解释性维（来源 + 具名细节）。 */
  record PartyShare(HouseholdId household, long sharePerMille, String source, String reason) {

    PartyShare {
      Objects.requireNonNull(household, "PartyShare.household 不得为 null");
      if (sharePerMille <= 0L || sharePerMille > 1000L) {
        throw new IllegalArgumentException(
            "PartyShare.sharePerMille 必须在 (0, 1000]: " + sharePerMille);
      }
      Objects.requireNonNull(source, "PartyShare.source 不得为 null");
      Objects.requireNonNull(reason, "PartyShare.reason 不得为 null");
      if (source.isBlank()) {
        throw new IllegalArgumentException("PartyShare.source 不得为空白");
      }
    }
  }

  /** 一次端点解析的结果：{@code shares} 非空 ⇒ 解析成功且合计 = 1000；空 ⇒ {@code reason} 必须具名。 */
  record Resolution(List<PartyShare> shares, String reason) {

    Resolution {
      Objects.requireNonNull(shares, "Resolution.shares 不得为 null");
      Objects.requireNonNull(reason, "Resolution.reason 不得为 null");
      shares = List.copyOf(shares);
      for (PartyShare share : shares) {
        Objects.requireNonNull(share, "Resolution.shares 不得含 null");
      }
      if (shares.isEmpty()) {
        if (reason.isBlank()) {
          throw new IllegalArgumentException("未解析的 Resolution 必须带具名 reason");
        }
      } else if (!reason.isBlank()) {
        throw new IllegalArgumentException("已解析的 Resolution 不携带未解析 reason: " + reason);
      } else {
        long sum = 0L;
        for (PartyShare share : shares) {
          sum = Math.addExact(sum, share.sharePerMille());
        }
        if (sum != 1000L) {
          throw new IllegalArgumentException("Resolution.shares 的千分合计必须 = 1000: " + sum);
        }
      }
    }

    boolean isResolved() {
      return !shares.isEmpty();
    }

    static Resolution resolved(List<PartyShare> shares) {
      return new Resolution(shares, "");
    }

    static Resolution unresolved(String reason) {
      return new Resolution(List.of(), reason);
    }
  }

  /** 一条实际落合同的 debtor × creditor 金额（>{@code 0}；金额守恒由 {@link DebtSplit} 负责核）。 */
  record DebtAmount(HouseholdId debtor, HouseholdId creditor, long amount) {

    DebtAmount {
      Objects.requireNonNull(debtor, "DebtAmount.debtor 不得为 null");
      Objects.requireNonNull(creditor, "DebtAmount.creditor 不得为 null");
      if (amount <= 0L) {
        throw new IllegalArgumentException("DebtAmount.amount 必须 > 0: " + amount);
      }
    }
  }

  /**
   * 一次 owed 的 Cartesian 拆分结果。
   *
   * @param amounts 真正落合同的组合（仅 {@code debtor != creditor}，金额 > 0；序 = 债务人 id 升序 → 债权人 id 升序）
   * @param selfNettedAmount 同户自债的净额（不落合同，由调用方写具名 unresolved；金额守恒仍算在内）
   * @param selfHouseholds 自债涉及的家户（去重、稳定序）
   * @param rejectionReason 非空白 ⇒ 本事件不做合同（组合上界等具名兜底），原 owed 必须由调用方写成 unresolved
   */
  record DebtSplit(
      List<DebtAmount> amounts,
      long selfNettedAmount,
      List<HouseholdId> selfHouseholds,
      String rejectionReason) {

    DebtSplit {
      Objects.requireNonNull(amounts, "DebtSplit.amounts 不得为 null");
      Objects.requireNonNull(selfHouseholds, "DebtSplit.selfHouseholds 不得为 null");
      Objects.requireNonNull(rejectionReason, "DebtSplit.rejectionReason 不得为 null");
      amounts = List.copyOf(amounts);
      selfHouseholds = List.copyOf(selfHouseholds);
      if (selfNettedAmount < 0L) {
        throw new IllegalArgumentException("DebtSplit.selfNettedAmount 不得为负: " + selfNettedAmount);
      }
      if (!rejectionReason.isBlank() && (!amounts.isEmpty() || selfNettedAmount != 0L)) {
        throw new IllegalArgumentException("DebtSplit 拒绝时必须没有金额：" + rejectionReason);
      }
    }

    boolean rejected() {
      return !rejectionReason.isBlank();
    }
  }

  /** 解析一个 actor 端（payer 或 {@link Recipient.ToActor} 的受方）。 */
  static Resolution resolveActor(
      EconomyData data,
      SettlementIndex index,
      ActorRef actor,
      HexCoord hex,
      Optional<ProductionUnitId> activity) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(actor, "actor");
    Optional<ProductionUnitId> optionalActivity = activity == null ? Optional.empty() : activity;

    // ① actor 本身是家户 actor ⇒ 该户 1000‰。
    HouseholdId direct = index.householdByActor().get(actor);
    if (direct != null) {
      if (!data.classes().containsKey(direct)) {
        return Resolution.unresolved("household-row-missing:" + direct.value());
      }
      return Resolution.resolved(
          List.of(new PartyShare(direct, 1000L, SOURCE_DIRECT, "actor-is-household")));
    }

    // ② E1 唯一解析器：actor 出现在哪些 unit 里；命中且家户唯一 ⇒ 1000‰。
    LinkedHashSet<HouseholdId> relatedHouseholds = new LinkedHashSet<>();
    for (ProductionUnitId unitId : index.unitsRelatedTo(actor)) {
      index
          .economicHouseholdOf(unitId)
          .filter(data.classes()::containsKey)
          .ifPresent(relatedHouseholds::add);
    }
    if (relatedHouseholds.size() == 1L) {
      HouseholdId household = relatedHouseholds.iterator().next();
      return Resolution.resolved(
          List.of(
              new PartyShare(
                  household, 1000L, SOURCE_RELATION, "economic-household-of-related-unit")));
    }

    boolean aggregateKind = actor.kind() == ActorKind.ESTATE || actor.kind() == ActorKind.WORKSHOP;
    List<ProductionOrganization> organizations = index.organizationsOf(actor);

    // ③ 聚合主体优先用 E2 生产组织登记的家户归属（organizer 或 unit.operator == actor）。
    if (!organizations.isEmpty()) {
      Resolution organization = resolveViaOrganizations(data, index, actor, organizations);
      if (organization.isResolved()) {
        return organization;
      }
      // 组织路解析不到（缺 laborSources / 人口为 0）⇒ 记原因后继续人口回退（ESTATE/WORKSHOP）。
      if (!aggregateKind) {
        return Resolution.unresolved("organization-" + organization.reason());
      }
    }

    // ④ 聚合主体按人口成分回退。
    if (aggregateKind) {
      Resolution fallback = resolveViaPopulation(data, index, actor, hex, optionalActivity);
      if (fallback.isResolved()) {
        return fallback;
      }
      if (!relatedHouseholds.isEmpty()) {
        return Resolution.unresolved(
            "actor-relation-ambiguous:" + relatedHouseholds.size() + ";" + fallback.reason());
      }
      return fallback;
    }

    // ⑤ 非家户且无法归类 ⇒ 不伪造。
    if (!relatedHouseholds.isEmpty()) {
      return Resolution.unresolved("actor-relation-ambiguous:" + relatedHouseholds.size());
    }
    return Resolution.unresolved("actor-kind-not-supported:" + actor.kind());
  }

  /** 解析一个 {@link Recipient} 端（payer 恒为 actor；creditor 可能是家户 / actor / 旧 cohort）。 */
  static Resolution resolveRecipient(
      EconomyData data,
      SettlementIndex index,
      Recipient recipient,
      HexCoord hex,
      Optional<ProductionUnitId> activity) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(recipient, "recipient");
    return switch (recipient) {
      case Recipient.ToHousehold toHousehold -> {
        HouseholdId household = toHousehold.household();
        if (!data.classes().containsKey(household)) {
          yield Resolution.unresolved("household-row-missing:" + household.value());
        }
        yield Resolution.resolved(
            List.of(new PartyShare(household, 1000L, SOURCE_DIRECT, "recipient-to-household")));
      }
      case Recipient.ToActor toActor -> resolveActor(data, index, toActor.actor(), hex, activity);
      case Recipient.ToCohort toCohort -> resolveCohort(data, toCohort.cohort());
    };
  }

  /**
   * 两级最大余数拆金额：{@code owed} 先按债务人份额切，再逐债务人按债权人份额切；每个实际组合金额 > 0。
   *
   * <p>守恒核对：{@code Σ amounts + selfNettedAmount == owed}（{@code Math.addExact}，失败/不等 ⇒ 具名抛）。
   */
  static DebtSplit splitDebtAmounts(
      long owed, List<PartyShare> debtors, List<PartyShare> creditors) {
    Objects.requireNonNull(debtors, "debtors");
    Objects.requireNonNull(creditors, "creditors");
    if (owed <= 0L) {
      throw new IllegalArgumentException("splitDebtAmounts 的 owed 必须 > 0: " + owed);
    }
    if (debtors.isEmpty() || creditors.isEmpty()) {
      return new DebtSplit(List.of(), 0L, List.of(), "no-debtor-or-creditor-shares");
    }
    if ((long) debtors.size() * (long) creditors.size() > MAX_PARTY_COMBINATIONS) {
      return new DebtSplit(
          List.of(),
          0L,
          List.of(),
          "too-many-party-combinations:" + debtors.size() + "x" + creditors.size());
    }
    List<PartyShare> debtorOrder = sortedByHousehold(debtors);
    List<PartyShare> creditorOrder = sortedByHousehold(creditors);
    long[] debtorWeights = weightsOf(debtorOrder, "debtors");
    long[] creditorWeights = weightsOf(creditorOrder, "creditors");

    long[] debtorAmounts = ProportionalSplit.byDenominator(owed, debtorWeights, 1000L);
    long allocated = 0L;
    long selfNetted = 0L;
    LinkedHashSet<HouseholdId> selfHouseholds = new LinkedHashSet<>();
    List<DebtAmount> amounts = new ArrayList<>();
    for (int debtorIndex = 0; debtorIndex < debtorOrder.size(); debtorIndex++) {
      long debtorAmount = debtorAmounts[debtorIndex];
      if (debtorAmount <= 0L) {
        continue;
      }
      HouseholdId debtor = debtorOrder.get(debtorIndex).household();
      long[] creditorAmounts =
          ProportionalSplit.byDenominator(debtorAmount, creditorWeights, 1000L);
      for (int creditorIndex = 0; creditorIndex < creditorOrder.size(); creditorIndex++) {
        long amount = creditorAmounts[creditorIndex];
        if (amount <= 0L) {
          continue;
        }
        HouseholdId creditor = creditorOrder.get(creditorIndex).household();
        if (debtor.equals(creditor)) {
          selfNetted = Math.addExact(selfNetted, amount);
          selfHouseholds.add(debtor);
        } else {
          amounts.add(new DebtAmount(debtor, creditor, amount));
          allocated = Math.addExact(allocated, amount);
        }
      }
    }
    long conserved = Math.addExact(allocated, selfNetted);
    if (conserved != owed) {
      throw new IllegalStateException(
          "DebtPartyResolver 金额拆分不守恒：owed="
              + owed
              + " allocated="
              + allocated
              + " selfNetted="
              + selfNetted);
    }
    return new DebtSplit(List.copyOf(amounts), selfNetted, List.copyOf(selfHouseholds), "");
  }

  // ── 组织路 / 人口路 / cohort 路 ─────────────────────────────────────────────────────

  /** E2 组织路：{@code laborSources} + 组织的家户归属受方。 */
  private static Resolution resolveViaOrganizations(
      EconomyData data,
      SettlementIndex index,
      ActorRef actor,
      List<ProductionOrganization> organizations) {
    LinkedHashSet<HouseholdId> households = new LinkedHashSet<>();
    for (ProductionOrganization organization : organizations) {
      households.addAll(organization.laborSources());
      // 组织者如果是家户（例如 unit.operator == actor 而 organizer 是家户），也把组织者算进来。
      HouseholdId organizerHousehold = index.householdByActor().get(organization.organizer());
      if (organizerHousehold != null) {
        households.add(organizerHousehold);
      }
      addRecipientHousehold(organization.outputOwnership(), households);
      for (Recipient source : organization.inputSources()) {
        addRecipientHousehold(source, households);
      }
    }
    if (households.isEmpty()) {
      return Resolution.unresolved("no-household-sources:" + organizations.size());
    }
    Resolution resolution =
        splitByPopulation(
            data,
            households,
            SOURCE_ORGANIZATION,
            "organization-labor-or-ownership:" + organizations.size());
    if (resolution.isResolved()) {
      return resolution;
    }
    return Resolution.unresolved(resolution.reason());
  }

  /** 人口回退路：ESTATE = owner/surplus receiver；WORKSHOP = legacy artisan；旧档按 stratum 回落。 */
  private static Resolution resolveViaPopulation(
      EconomyData data,
      SettlementIndex index,
      ActorRef actor,
      HexCoord hex,
      Optional<ProductionUnitId> activity) {
    HexCoord targetHex = effectiveHex(index, actor, hex, activity);
    if (targetHex == null) {
      return Resolution.unresolved("no-population-composition:hex-not-resolvable");
    }
    LinkedHashSet<HouseholdId> positionMatches = new LinkedHashSet<>();
    LinkedHashSet<HouseholdId> legacyMatches = new LinkedHashSet<>();
    for (ClassRow row : data.classes().values()) {
      if (!row.view().hex().equals(targetHex)) {
        continue;
      }
      Optional<ClassPositionId> positionId = ClassPositionResolver.resolveCurrent(data, row.id());
      ClassPosition position = positionId.map(data.classPositions()::get).orElse(null);
      boolean positionMatch = false;
      boolean legacyMatch = false;
      if (actor.kind() == ActorKind.ESTATE) {
        positionMatch =
            position != null
                && (position.relationToMeans() == RelationToMeans.OWNER
                    || position.surplusRole() == SurplusRole.SURPLUS_RECEIVER);
        legacyMatch = row.view().stratum().equals(SocialClassId.LANDLORD);
      } else if (actor.kind() == ActorKind.WORKSHOP) {
        positionMatch =
            position != null
                && LegacyClassStructure.socialClassOf(position.id())
                    .filter(SocialClassId.ARTISAN::equals)
                    .isPresent();
        legacyMatch = row.view().stratum().equals(SocialClassId.ARTISAN);
      }
      if (positionMatch) {
        positionMatches.add(row.id());
      } else if (legacyMatch) {
        legacyMatches.add(row.id());
      }
    }

    String positionDetail =
        actor.kind() == ActorKind.ESTATE
            ? "estate-owner-or-surplus-receiver"
            : "workshop-legacy-artisan-position";
    Resolution positionResolution =
        splitByPopulation(data, positionMatches, SOURCE_POPULATION_FALLBACK, positionDetail);
    if (positionResolution.isResolved()) {
      return positionResolution;
    }

    String legacyDetail =
        actor.kind() == ActorKind.ESTATE
            ? "estate-legacy-landlord"
            : "workshop-legacy-artisan-stratum";
    Resolution legacyResolution =
        splitByPopulation(data, legacyMatches, SOURCE_POPULATION_FALLBACK, legacyDetail);
    if (legacyResolution.isResolved()) {
      return legacyResolution;
    }

    String detail = actor.kind() == ActorKind.ESTATE ? "estate" : "workshop";
    return Resolution.unresolved(
        "no-population-composition:"
            + detail
            + ":"
            + traceOf(positionResolution, legacyResolution));
  }

  /** 旧 cohort 受方：在该 hex 上按 {@link ClassRow#view()} 逐字段相等匹配。 */
  private static Resolution resolveCohort(EconomyData data, CohortKey cohort) {
    LinkedHashSet<HouseholdId> households = new LinkedHashSet<>();
    for (ClassRow row : data.classes().values()) {
      if (row.view().equals(cohort)) {
        households.add(row.id());
      }
    }
    if (households.isEmpty()) {
      return Resolution.unresolved("cohort-no-household:" + cohort);
    }
    Resolution resolution = splitByPopulation(data, households, SOURCE_COHORT, "cohort-view-match");
    if (resolution.isResolved()) {
      return resolution;
    }
    return Resolution.unresolved(resolution.reason());
  }

  /** 人口份额：按 {@link ClassRow#population()} 最大余数拆分；0 人口/缺行的家户不参与，目标按 id 升序。 */
  private static Resolution splitByPopulation(
      EconomyData data, Collection<HouseholdId> households, String source, String detail) {
    if (households == null || households.isEmpty()) {
      return Resolution.unresolved("no-population-composition:" + detail);
    }
    List<HouseholdId> ordered = new ArrayList<>(new LinkedHashSet<>(households));
    ordered.sort(Comparator.comparing(HouseholdId::value));
    List<HouseholdId> targets = new ArrayList<>();
    long[] weights = new long[ordered.size()];
    long totalPopulation = 0L;
    for (HouseholdId household : ordered) {
      ClassRow row = data.classes().get(household);
      if (row == null || row.population() <= 0L) {
        continue;
      }
      targets.add(household);
      weights[targets.size() - 1] = row.population();
      totalPopulation = Math.addExact(totalPopulation, row.population());
    }
    if (targets.isEmpty()) {
      return Resolution.unresolved("no-population-composition:all-zero-or-missing:" + detail);
    }
    if (targets.size() == 1) {
      return Resolution.resolved(
          List.of(new PartyShare(targets.get(0), 1000L, source, detail + ":single-household")));
    }
    long[] shares = ProportionalSplit.byDenominator(1000L, weights, totalPopulation);
    List<PartyShare> result = new ArrayList<>(targets.size());
    long sum = 0L;
    for (int i = 0; i < targets.size(); i++) {
      if (shares[i] <= 0L) {
        continue; // 0‰ 家户不落 PartyShare（它不承担责任）；总和仍是 1000。
      }
      result.add(new PartyShare(targets.get(i), shares[i], source, detail));
      sum = Math.addExact(sum, shares[i]);
    }
    if (sum != 1000L || result.isEmpty()) {
      throw new IllegalStateException(
          "DebtPartyResolver 人口份额合计不为 1000：detail="
              + detail
              + " totalPopulation="
              + totalPopulation
              + " shares="
              + sum);
    }
    return Resolution.resolved(List.copyOf(result));
  }

  /** 端点解析的 hex 兜底：显式 hex → activity 的格 → actor id 的产业格 → 组织的 id/unit 格 → 相关 unit 的格。 */
  private static HexCoord effectiveHex(
      SettlementIndex index, ActorRef actor, HexCoord hex, Optional<ProductionUnitId> activity) {
    if (hex != null) {
      return hex;
    }
    if (activity.isPresent()) {
      HexCoord parsed = parseHexKey(index.hexOf(activity.get()));
      if (parsed != null) {
        return parsed;
      }
    }
    if (actor.kind() == ActorKind.ESTATE || actor.kind() == ActorKind.WORKSHOP) {
      HexCoord parsed =
          parseHexKey(IndustryHexKeys.hexKeyOf(new IndustryId(actor.id())).orElse(null));
      if (parsed != null) {
        return parsed;
      }
    }
    for (ProductionOrganization organization : index.organizationsOf(actor)) {
      HexCoord fromId = parseHexKey(organization.id().hexKey().orElse(null));
      if (fromId != null) {
        return fromId;
      }
      if (organization.unitId().isPresent()) {
        HexCoord fromUnit = parseHexKey(index.hexOf(organization.unitId().get()));
        if (fromUnit != null) {
          return fromUnit;
        }
      }
    }
    for (ProductionUnitId unit : index.unitsRelatedTo(actor)) {
      HexCoord parsed = parseHexKey(index.hexOf(unit));
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private static HexCoord parseHexKey(String hexKey) {
    if (hexKey == null || hexKey.isBlank()) {
      return null;
    }
    try {
      return HexCoord.parse(hexKey);
    } catch (IllegalArgumentException notAHexKey) {
      return null;
    }
  }

  private static void addRecipientHousehold(
      Recipient recipient, LinkedHashSet<HouseholdId> households) {
    if (recipient instanceof Recipient.ToHousehold toHousehold) {
      households.add(toHousehold.household());
    }
  }

  private static List<PartyShare> sortedByHousehold(List<PartyShare> shares) {
    List<PartyShare> ordered = new ArrayList<>(shares);
    ordered.sort(Comparator.comparing(share -> share.household().value()));
    return List.copyOf(ordered);
  }

  private static long[] weightsOf(List<PartyShare> shares, String field) {
    long[] weights = new long[shares.size()];
    long sum = 0L;
    for (int i = 0; i < shares.size(); i++) {
      PartyShare share = shares.get(i);
      Objects.requireNonNull(share, field + " 不得含 null");
      weights[i] = share.sharePerMille();
      sum = Math.addExact(sum, share.sharePerMille());
    }
    if (sum != 1000L) {
      throw new IllegalArgumentException(field + " 的千分合计必须 = 1000: " + sum);
    }
    return weights;
  }

  private static String traceOf(Resolution first, Resolution second) {
    String firstReason = first.reason().isBlank() ? "resolved" : first.reason();
    String secondReason = second.reason().isBlank() ? "resolved" : second.reason();
    if (firstReason.equals(secondReason)) {
      return firstReason;
    }
    return firstReason + "|" + secondReason;
  }
}
