package io.mosire.simos.army.spi;

import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 army / unit 切片的唯一入口（本包命令 handler 共用）。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code ActorSnapshots} / {@code UnitSnapshots} 同口径）：缺 {@code army} 切片、或切片不是
 * {@link ArmySnapshot}，都抛 {@link IllegalStateException}——它们是<b>装配</b>的错，不是命令/载荷的错，不走 {@code
 * Rejected} 路径（那条路是给"世界数据说了什么"用的）。
 *
 * <p>★ <b>本批（3d-1）新增 {@link #units}</b>：军队身份的两条命令（{@code army.CreateArmy} / {@code
 * army.SetArmyMasterGov}）要只读 unit 切片判"root 单位 / 认领的 GOV 单位存不存在、是不是 GOV"——这是迁入前 {@code
 * SdSnapshots.units} 的同一条判据（铁律 3：army <b>只读</b> unit，既不 cache 也不改写）。
 */
final class ArmySnapshots {

  private ArmySnapshots() {}

  static ArmySnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("army")
            .orElseThrow(() -> new IllegalStateException("state 里没有 army 切片（装配故障）"));
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalStateException(
          "state 的 army 切片不是 ArmySnapshot: " + snapshot.getClass().getName());
    }
    return armySnapshot;
  }

  /**
   * unit 切片（{@code army.CreateArmy} 校验 rootUnit / masterGovUnitId 用；只读）。
   *
   * <p>★ 异常类与文案逐字照抄迁入前的 {@code SdSnapshots.units}（装配故障当场炸，不当成"没有单位"）。
   */
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

  /** 根单位是否存在（只读 unit 切片）。 */
  static boolean unitExists(SimulationState state, UnitId unitId) {
    return units(state).units().containsKey(unitId);
  }
}
