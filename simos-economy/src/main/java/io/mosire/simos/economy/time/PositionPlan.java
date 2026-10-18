package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★★ <b>D-032 P3c1：一个生产位置在某一遍编排中的瞬态选择</b>。
 *
 * <p>位置是“家户在自己的劳动预算内选择的单位”，不是持久生产实例。{@link #unit()} 指向它落到的 {@link ProductionUnitId}；{@link #plan()}
 * 是生产方式稳定身份；{@link #role()}、{@link #location()} 共同把位置 定到具体方案/地点/角色。{@code scale}、{@code
 * selfLaborMilliPerDay}、{@code hiredLaborMilliPerDay}、 {@code landQuantityMilliMu}
 * 都只表达本遍编排的计划量，不写任何账。
 *
 * <p>★ {@link #positionNetMilli()} 与 {@link #positionYieldPerLaborScaled()} 由 {@link
 * PositionEconomics} 的同一公式 派生；{@link #fromEconomics} 是该语义的直接入口。M3/M6 的候选与选择结果都带这两个读数，不得在调用点另写一套公式。
 *
 * <p>本类型只活在一个 tick 内：不落 {@code EconomyData}、不进 ChangeSet/Codec，也不提供 {@code with}/{@code apply}。
 */
record PositionPlan(
    ProductionUnitId unit,
    IndustryId plan,
    ProductionRoleId role,
    ProductionLocation location,
    long scale,
    long selfLaborMilliPerDay,
    long hiredLaborMilliPerDay,
    long landQuantityMilliMu,
    long positionNetMilli,
    long positionYieldPerLaborScaled) {

  PositionPlan {
    Objects.requireNonNull(unit, "PositionPlan.unit 不得为 null");
    Objects.requireNonNull(plan, "PositionPlan.plan 不得为 null");
    Objects.requireNonNull(role, "PositionPlan.role 不得为 null");
    Objects.requireNonNull(location, "PositionPlan.location 不得为 null");
    if (scale < 0L) {
      throw new IllegalArgumentException("PositionPlan.scale 不得为负: " + scale);
    }
    if (selfLaborMilliPerDay < 0L) {
      throw new IllegalArgumentException(
          "PositionPlan.selfLaborMilliPerDay 不得为负: " + selfLaborMilliPerDay);
    }
    if (hiredLaborMilliPerDay < 0L) {
      throw new IllegalArgumentException(
          "PositionPlan.hiredLaborMilliPerDay 不得为负: " + hiredLaborMilliPerDay);
    }
    if (landQuantityMilliMu < 0L) {
      throw new IllegalArgumentException(
          "PositionPlan.landQuantityMilliMu 不得为负: " + landQuantityMilliMu);
    }
    // 入选计划只保留正收益候选；负值留在 PositionEconomics 的候选读数里，不伪装成一条已选位置。
    if (positionNetMilli < 0L) {
      throw new IllegalArgumentException(
          "PositionPlan.positionNetMilli 不得为负（负收益候选不得入选）: " + positionNetMilli);
    }
    if (positionYieldPerLaborScaled < 0L) {
      throw new IllegalArgumentException(
          "PositionPlan.positionYieldPerLaborScaled 不得为负（负收益候选不得入选）: "
              + positionYieldPerLaborScaled);
    }
  }

  /** 用 {@link PositionEconomics} 的同一公式构造计划位置；调用方只提供事实，不再手写净额/每劳动收益。 */
  static PositionPlan fromEconomics(
      ProductionUnitId unit,
      IndustryId plan,
      ProductionRoleId role,
      ProductionLocation location,
      long scale,
      long selfLaborMilliPerDay,
      long hiredLaborMilliPerDay,
      long landQuantityMilliMu,
      PositionEconomics economics) {
    Objects.requireNonNull(economics, "PositionPlan.fromEconomics 的 economics 不得为 null");
    return new PositionPlan(
        unit,
        plan,
        role,
        location,
        scale,
        selfLaborMilliPerDay,
        hiredLaborMilliPerDay,
        landQuantityMilliMu,
        economics.positionNetMilli(),
        economics.positionYieldPerLaborScaled());
  }

  /**
   * ★ <b>unit 级查询形态</b>：同一 {@link ProductionUnitId} 下，来自不同家户的多个位置归在一起。
   *
   * <p>map 的 key 必须等于 {@link #unit()}；{@code positions} 在本类型内保序不可变，调用方应传入自己的规范序（同一输入
   * 重复构造得到同一顺序）。所有位置的方案与地点必须和 unit 级字段一致，避免“同一个 unit 下混入别的方案/别的地点”。
   */
  record UnitPlan(
      ProductionUnitId unit,
      IndustryId plan,
      ProductionLocation location,
      List<PositionPlan> positions) {

    UnitPlan {
      Objects.requireNonNull(unit, "UnitPlan.unit 不得为 null");
      Objects.requireNonNull(plan, "UnitPlan.plan 不得为 null");
      Objects.requireNonNull(location, "UnitPlan.location 不得为 null");
      Objects.requireNonNull(positions, "UnitPlan.positions 不得为 null（没有位置就不要建 unit 计划）");
      if (positions.isEmpty()) {
        throw new IllegalArgumentException("UnitPlan.positions 不得为空: " + unit.value());
      }
      List<PositionPlan> copy = new ArrayList<>(positions.size());
      for (PositionPlan position : positions) {
        if (position == null) {
          throw new IllegalArgumentException("UnitPlan.positions 不得含 null: " + unit.value());
        }
        if (!unit.equals(position.unit())) {
          throw new IllegalArgumentException(
              "UnitPlan.positions 的位置 unit 必须与 key 一致：key="
                  + unit.value()
                  + "，position="
                  + position.unit().value());
        }
        if (!plan.equals(position.plan())) {
          throw new IllegalArgumentException(
              "UnitPlan.positions 的位置 plan 必须与 unit 计划一致：unit="
                  + unit.value()
                  + "，plan="
                  + position.plan().value());
        }
        if (!location.equals(position.location())) {
          throw new IllegalArgumentException(
              "UnitPlan.positions 的位置 location 必须与 unit 计划一致：unit="
                  + unit.value()
                  + "，location="
                  + position.location().value());
        }
        copy.add(position);
      }
      positions = List.copyOf(copy); // 保序不可变
    }
  }
}
