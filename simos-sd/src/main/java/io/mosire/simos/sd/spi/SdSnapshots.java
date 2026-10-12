package io.mosire.simos.sd.spi;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 <b>map / unit</b> 切片的唯一入口（本包 handler 共用）。
 *
 * <p>★ **装配故障当场炸**（与 {@code UnitSnapshots} 同口径）：缺切片、或切片类型不对，都抛 {@link
 * IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 *
 * <p>★ sd **只读** map / unit 切片（铁律 3）：既不 cache、也不改写它们。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类删掉 {@code of(SimulationState)} 与 {@code unitExists(...)}（如实记）</b>——
 * 那两个方法的<b>唯一调用者</b>是 {@code RegisterEffectHandler} 与 {@code CancelEffectHandler}（读 sd 树的 effects 表
 * + 校验 {@code Trigger.UnitAtHex} 的单位存在），它们本批随 {@code effects} 整族搬进 {@code simos-ga} （取数改走 {@code
 * GaSnapshots.state(...)} / {@code GaSnapshots.units(...)}）⇒ 这里的两支<b>失去全部调用者</b>， 同批删除（与 3d-2 删
 * {@code social(...)} 同款：唯一调用者一消失就删，不留"看着能用、其实无人用"的接缝）。 ★ <b>今天本类的读者只剩一支</b>：{@link #units}
 * ——{@code AffiliationTargets} 校验 {@code Affiliation.Gov(UnitId)} 指向的单位存在（那条例外随"归属归模块"的乙收尾另议）。★
 * {@link #map} 自 3c 起<b>已无调用者</b>（{@code gov.CreateNation} 搬走后），按 3c §D9 的既定处置<b>保留到 3e</b>（随 sd
 * 空壳一并删）。
 */
final class SdSnapshots {

  private SdSnapshots() {}

  /** map 切片（3c 起已无调用者；按 3c §D9 保留到 3e；只读）。 */
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

  /**
   * unit 切片（只读）——★ <b>本批起本类的唯一读者</b>：{@code AffiliationTargets} 校验 {@code Affiliation.Gov(UnitId)}
   * 指向的单位存在。
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
}
