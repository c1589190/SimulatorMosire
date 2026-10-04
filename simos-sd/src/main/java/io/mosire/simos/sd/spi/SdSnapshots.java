package io.mosire.simos.sd.spi;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 sd / map / unit 切片的唯一入口（本包 handler 共用）。
 *
 * <p>★ **装配故障当场炸**（与 {@code UnitSnapshots} 同口径）：缺切片、或切片类型不对，都抛 {@link
 * IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 *
 * <p>★ sd **只读** map / unit 切片的存在性（铁律 3）：既不 cache、也不改写它们。
 */
final class SdSnapshots {

  private SdSnapshots() {}

  static SdSnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("state 里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException(
          "state 的 sd 切片不是 SdSnapshot: " + snapshot.getClass().getName());
    }
    return sdSnapshot;
  }

  /** map 切片（{@code sd.CreateNation} 校验 homeRegion 用；只读）。 */
  static GameMap map(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** unit 切片（{@code sd.CreateArmy} 校验 rootUnit 用；只读）。 */
  static UnitState units(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalStateException("state 里没有 unit 切片（装配故障）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /**
   * ★ <b>S3b（2026-10-09）：social 切片（只读）</b>——{@code sd.RecordCasualties} 的人员上界现在从 {@link
   * SocialData#unitPopulation(String)} 现算（Unit.manpower 已退役；人数唯一来源是 Social 家户）。
   */
  static SocialData social(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(() -> new IllegalStateException("state 里没有 social 切片（装配故障）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /** 根单位是否存在（只读 unit 切片）。 */
  static boolean unitExists(SimulationState state, UnitId unitId) {
    return units(state).units().containsKey(unitId);
  }
}
