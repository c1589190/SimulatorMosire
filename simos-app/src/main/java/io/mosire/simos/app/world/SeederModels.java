package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** EconomySeeder 拆分后的包内中间态记录；只承载原有字段/方法，不改语义。 */
final class SeederModels {
  private SeederModels() {}
}

record PoolCohorts(
    ResidenceKind residence,
    List<HouseholdId> classHouseholds,
    long[] classPopulations,
    HouseholdId displacedHousehold,
    long displacedPopulation) {

  /**
   * 该批次归哪个家户：按 Social 侧同一居住类型的四阶层 + 流民户里找**与该批次前缀/阶层标签一致**的那一个。
   *
   * <p>★ 播种器的命名把阶层短名编进了 cohort 段（{@code s0..s3} / {@code d}）——这里只按"批次的 cohort 短名"在 {@link
   * PoolCohorts} 的候选户里选，不用再解析 id 的其它段。
   */
  HouseholdId householdOfGroup(PopulationGroup group) {
    String tag = PopulationLots.cohortOf(group.id());
    int dash = tag.indexOf('-');
    String stratumTag = dash < 0 ? tag : tag.substring(0, dash);
    if (PopulationSeeder.DISPLACED_TAG.equals(stratumTag)) {
      if (displacedHousehold == null) {
        throw new IllegalStateException("批次 " + group.id() + " 是流民批次，但该池没有流民家户");
      }
      return displacedHousehold;
    }
    int slot;
    try {
      slot = Integer.parseInt(stratumTag.substring(1));
    } catch (RuntimeException e) {
      throw new IllegalStateException("批次 " + group.id() + " 的阶层短名非法: " + stratumTag, e);
    }
    if (slot < 0 || slot >= classHouseholds.size()) {
      throw new IllegalStateException("批次 " + group.id() + " 的阶层短名越界: " + stratumTag);
    }
    return classHouseholds.get(slot);
  }
}

/** 条件应用产物（不可变；无 conditions 时 = {@link #empty()}）。 */
record AppliedConditions(
    List<Map<String, Object>> debtContracts,
    List<Map<String, Object>> extraMoneyIssuances,
    TestConditions.Report report) {

  static AppliedConditions empty() {
    return new AppliedConditions(List.of(), List.of(), TestConditions.Report.EMPTY);
  }
}

/** 一条既有资产份额节点的内存视图（node 可变：拆分只改它的 quantity）。 */
record ShareHandle(
    int entryIndex,
    Map<String, Object> node,
    IndustryId industry,
    AssetKind asset,
    ActorRef owner,
    ActorRef operator,
    AssetRightKind kind,
    ProductionLocation location) {

  long quantity() {
    return ((Number) node.get("quantity")).longValue();
  }
}

/** D-032 C3 的映射结果：{@code blockedReason == null} ⇔ 可整体映射。 */
record LegacyRoleMapping(List<Map<String, Object>> roles, String blockedReason) {
  LegacyRoleMapping {
    Objects.requireNonNull(roles, "roles");
    roles = Collections.unmodifiableList(new ArrayList<>(roles));
  }

  static LegacyRoleMapping mapped(List<Map<String, Object>> roles) {
    return new LegacyRoleMapping(roles, null);
  }

  static LegacyRoleMapping blocked(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("LegacyRoleMapping.blocked 的 reason 不得为空白");
    }
    return new LegacyRoleMapping(List.of(), reason);
  }

  boolean mapped() {
    return blockedReason == null;
  }
}

/** ★★ R3B.2 的产业播种中间体：模板载荷 + 经营者 + 产能总量（unit 与资产份额都从它派生）。 */
record IndustryPlan(
    String kind,
    String id,
    String regime,
    ActorRef operator,
    Map<String, Long> capacity,
    Map<String, Long> capacityPerUnit,
    Map<String, Object> payload) {

  /** 新 id 的唯一拼写点（契约层工厂）。 */
  ProductionUnitId unitId() {
    return ProductionUnitId.idOf(new IndustryId(id), operator);
  }

  /** ★★ P2-A §13.3：把主 unit 的经营者换成一个家户 actor（庄园/作坊的组织者家户）。 */
  IndustryPlan withOperator(ActorRef next) {
    if (next == null) {
      throw new IllegalArgumentException("IndustryPlan.withOperator 的 next 不得为 null");
    }
    return new IndustryPlan(kind, id, regime, next, capacity, capacityPerUnit, payload);
  }
}

/** 一个产业的拆分结果：主 unit 剩余容量 + 家户副 unit（保 canonical 序）+ 移到副 unit 的劳动行。 */
record IndustrySplit(
    IndustryPlan plan,
    Map<String, Long> mainCapacity,
    List<HouseholdUnit> secondaries,
    List<LaborMove> laborMoves) {}

/** 一个家户副 unit：身份、产权、份额与显式关系（逐产业不同，见 {@code LandOwnershipSeeder.householdUnit}）。 */
record HouseholdUnit(
    HouseholdId household,
    ActorRef operator,
    ActorRef owner,
    AssetRightKind kind,
    Map<String, Long> quantities,
    ProductionRules relation) {}

/**
 * ★★ <b>P5b1：一个农村家户在所在 hex 的 {@code grain_farm} unit 创世形态</b>。
 *
 * <p>{@code owner = operator = 家户 actor}；{@code unitNode} 带显式 {@code relation}（空 {@code rules} + {@code
 * SELF}）；{@code shareNode} 是该户自己的 {@code LAND / OWNED} 份额（无地户数量 0，仍要登记）。
 */
record GrainFarmUnit(
    HouseholdId household,
    ActorRef operator,
    ProductionUnitId unitId,
    Map<String, Object> unitNode,
    Map<String, Object> shareNode) {}

/** 拆分**中间态**：一条候选配额行（household/moved 已算好，副 unit 是否建尚未定）。 */
record MovedRow(Map<String, Object> row, HouseholdId household, long moved) {}

/** 一条**已决定**要移到副 unit 的配额行（unit 已绑定，{@code moved ≤ 原行 laborMilli}）。 */
record LaborMove(Map<String, Object> row, HouseholdUnit unit, long moved) {}
