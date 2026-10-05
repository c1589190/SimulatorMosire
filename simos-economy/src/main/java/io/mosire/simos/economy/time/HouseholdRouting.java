package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>账户主体解析（P2-A §13.3 的唯一拼写点）</b>：把"经营者/组织者 actor"解析到家户账。
 *
 * <p>★★ <b>为什么必须有它</b>：账户主体只有家户，而生产单元上写的是 {@code ActorRef operator}（庄园/作坊/商号可能是
 * {@code ORGANIZATION}/{@code ESTATE}/{@code WORKSHOP} 的 id）。解析规则固定为：
 *
 * <pre>
 * ① unit 的既有经济家户解析（{@link EconomicHouseholdResolver}：operator / relation / 份额 owner）⇒ 单一家户
 * ② unit 对应的 {@link ProductionOrganization#organizer()}（家户 actor）⇒ 单一家户
 * ③ unit 名下劳动配额的家户集合（{@code SettlementIndex.householdsOf}）⇒ <b>集体主体</b>
 *    （家户纺织这类"多个家户共同经营一个聚合 unit"：产出/付款按劳动权重分给各家户）
 * ④ 否则 ⇒ 空（调用方必须具名拒绝/具名缺口，绝不静默造账、绝不跳过）
 * </pre>
 *
 * <p>★ <b>非家户 actor 一律不持账</b>：{@link #requireHouseholdOf(ActorRef)} 对非 {@code HOUSEHOLD} 具名抛；
 * {@link #householdOfActorOrNull} 只对"现存家户行"里的 HOUSEHOLD actor 命中，其余返回空。
 *
 * <p>★ <b>本类不进状态</b>：它只读 {@code rows}/{@code units}/{@code organizations}/{@code index} 并返回纯派生结果。
 */
final class HouseholdRouting {

  private HouseholdRouting() {}

  /** 非家户 actor ⇒ 具名抛（"账户主体只有家户"）。 */
  static HouseholdId requireHouseholdOf(ActorRef actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalStateException(
          "账户主体只有家户：非家户 actor 必须先由结算侧解析到组织者/经营者家户（不得静默跳过）：" + actor);
    }
    return HouseholdActors.householdOf(actor);
  }

  /** HOUSEHOLD actor 且是现存家户行 ⇒ 该家户；其余（非家户 / 行不存在 / id 不可解析）⇒ 空。 */
  static Optional<HouseholdId> householdOfActorOrNull(
      ActorRef actor, Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    if (actor == null || actor.kind() != ActorKind.HOUSEHOLD) {
      return Optional.empty();
    }
    HouseholdId household;
    try {
      household = HouseholdActors.householdOf(actor);
    } catch (RuntimeException notAHouseholdActor) {
      return Optional.empty();
    }
    return householdEconomies.containsKey(household) ? Optional.of(household) : Optional.empty();
  }

  /** 一个 unit 的账户主体：单一家户 或 集体家户（两者最多一个非空；空 = 解析不到）。 */
  record Subject(Optional<HouseholdId> single, List<HouseholdId> collective) {

    Subject {
      Objects.requireNonNull(single, "single");
      Objects.requireNonNull(collective, "collective");
      collective = List.copyOf(collective);
      if (single.isPresent() && !collective.isEmpty()) {
        throw new IllegalArgumentException("账户主体不能同时是单一家户与集体：single=" + single + " collective=" + collective);
      }
    }

    static Subject single(HouseholdId household) {
      return new Subject(Optional.of(household), List.of());
    }

    static Subject collective(List<HouseholdId> households) {
      return new Subject(Optional.empty(), households);
    }

    static Subject unresolved() {
      return new Subject(Optional.empty(), List.of());
    }

    boolean isEmpty() {
      return single.isEmpty() && collective.isEmpty();
    }

    /** 成员（单一主体 = 单元素表；空主体 = 空表）。保序。 */
    List<HouseholdId> all() {
      if (single.isPresent()) {
        return List.of(single.get());
      }
      return collective;
    }
  }

  /**
   * 解析一个 unit 的账户主体（见类注的 ①→④）。不抛（解析不到 ⇒ {@link Subject#unresolved()}，由调用方具名处理）。
   */
  static Subject subjectOf(
      ProductionUnit unit,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionOrganization> organizationByUnit,
      SettlementIndex index) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(index, "index");
    Optional<HouseholdId> economic = index.economicHouseholdOf(unit.id());
    if (economic.isPresent()) {
      return Subject.single(economic.get());
    }
    Optional<HouseholdId> direct = householdOfActorOrNull(unit.operator(), householdEconomies);
    if (direct.isPresent()) {
      return Subject.single(direct.get());
    }
    ProductionOrganization organization =
        organizationByUnit == null ? null : organizationByUnit.get(unit.id());
    if (organization != null) {
      Optional<HouseholdId> organizer = householdOfActorOrNull(organization.organizer(), householdEconomies);
      if (organizer.isPresent()) {
        return Subject.single(organizer.get());
      }
    }
    List<HouseholdId> collective = index.householdsOf(unit.id());
    if (!collective.isEmpty()) {
      return Subject.collective(collective);
    }
    return Subject.unresolved();
  }

  /**
   * 集体主体的分配权重：该 unit 名下逐家户的劳动配额之和（{@code laborMilli}）；全为 0 时退回人口；
   * 仍无权重 ⇒ 等权。返回表保序（首次出现序），只含主体成员。
   */
  static Map<HouseholdId, Long> weightsOf(
      ProductionUnitId unitId, SettlementIndex index, Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<HouseholdId, Long> weights = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : index.allocationsOfUnit(unitId)) {
      weights.merge(laborCommitment.household(), Math.max(0L, laborCommitment.laborMilli()), Math::addExact);
    }
    long positive = 0L;
    for (long weight : weights.values()) {
      positive += weight;
    }
    if (positive > 0L) {
      return weights;
    }
    weights.clear();
    for (HouseholdId household : index.householdsOf(unitId)) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      weights.put(household, Math.max(1L, householdEconomy == null ? 1L : householdEconomy.population()));
    }
    return weights;
  }

  /** 把 {@code amount} 按权重分给主体成员（最大余数法，Σ = amount）；amount ≤ 0 / 无成员 ⇒ 空表。 */
  static Map<HouseholdId, Long> apportion(
      Subject subject, long amount, Map<HouseholdId, Long> weights) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(weights, "weights");
    Map<HouseholdId, Long> result = new LinkedHashMap<>();
    if (amount <= 0L || subject.isEmpty()) {
      return result;
    }
    List<HouseholdId> members = subject.all();
    long[] values = new long[members.size()];
    long denominator = 0L;
    for (int i = 0; i < members.size(); i++) {
      long weight = Math.max(0L, weights.getOrDefault(members.get(i), 0L));
      values[i] = weight;
      denominator += weight;
    }
    if (denominator <= 0L) {
      for (int i = 0; i < values.length; i++) {
        values[i] = 1L;
        denominator += 1L;
      }
    }
    long[] parts = ProportionalSplit.byDenominator(amount, values, denominator);
    for (int i = 0; i < members.size(); i++) {
      if (parts[i] > 0L) {
        result.put(members.get(i), parts[i]);
      }
    }
    return result;
  }

  /** 把主体展开成家户 actor 列表（保序；用于日志/对账）。 */
  static List<ActorRef> actorsOf(Subject subject) {
    List<ActorRef> actors = new ArrayList<>();
    for (HouseholdId household : subject.all()) {
      actors.add(HouseholdActors.of(household));
    }
    return actors;
  }
}
