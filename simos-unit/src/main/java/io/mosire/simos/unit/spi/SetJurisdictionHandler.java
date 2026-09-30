package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetJurisdiction} 命令的处理器（辖区阶段 5 / 计划 §2.2）：{@code unitId, regions[regionId…],
 * levyGrainCapPerCycle?, levyMoneyCapPerCycle?, levyManpowerCapPerCycle?, administrationPerMille?}。
 *
 * <p>★ <b>语义</b>：{@code regions} 必填（**可空数组 = 撤销全部管辖**）；区域集合整体替换、保留区域旧税率、新区域从 0 起；四个可选政策字段 未给 ⇒
 * 保持原值（单位原本无 {@code jurisdiction} ⇒ 用 0）。完整规则在 {@link UnitOperations#setJurisdiction}。
 *
 * <p>★ <b>区域存在性由领域层具名拒</b>：{@code GameMap} 从 {@code state.module("map")} 读（照 {@code
 * PlanSparseRouteHandler} 的形制），缺切片/类型不符 ⇒ 装配故障当场炸、不走拒绝路径；{@code regions} 里任一 id 不在 {@code
 * map.regions()} ⇒ {@link IllegalArgumentException} 折成 {@code Rejected}，**不静默丢**。
 *
 * <p>★ **目标声明**（{@link CommandTargets}）：按载荷点名的 unitId 判（同既有 unit 命令）。本命令是 unit 域命令，region 是 map
 * 命名空间的数据，故目标只含该 unit 的资源路径。
 */
public final class SetJurisdictionHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetJurisdiction";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      List<RegionId> regions = new ArrayList<>();
      for (String region : UnitPayloads.requireTextArray(payload, "regions")) {
        regions.add(RegionId.parse(region));
      }
      Optional<Long> grainCap = UnitPayloads.optionalLong(payload, "levyGrainCapPerCycle");
      Optional<Long> moneyCap = UnitPayloads.optionalLong(payload, "levyMoneyCapPerCycle");
      Optional<Long> manpowerCap = UnitPayloads.optionalLong(payload, "levyManpowerCapPerCycle");
      Optional<Integer> administration =
          UnitPayloads.optionalInt(payload, "administrationPerMille");
      UnitState next =
          UnitOperations.setJurisdiction(
              snapshot.state(),
              id,
              mapOf(state),
              regions,
              grainCap,
              moneyCap,
              manpowerCap,
              administration);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 与 {@code PlanSparseRouteHandler.mapOf} 同制：缺 `map` 切片或类型不符 ⇒ **装配故障当场炸**。 */
  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }
}
