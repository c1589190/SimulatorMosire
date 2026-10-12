package io.mosire.simos.gov.spi;

import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 gov / map 切片的唯一入口（本包 handler 共用）。形制照 {@code SdSnapshots} / {@code
 * UnitSnapshots}。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code UnitSnapshots} 同口径）：缺切片、或切片类型不对，都抛 {@link
 * IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 *
 * <p>★ gov <b>只读</b> map 切片的存在性（铁律 3）：既不 cache、也不改写它（{@code gov.CreateNation} 校验 homeRegion 用）。
 *
 * <p>★★ <b>2026-10-12 批次 3c</b>：原 {@code SdSnapshots} 里被迁入的四条命令用到的那两只（{@code of}/{@code map}）在本类
 * 逐字重建 —— sd 的 {@code SdSnapshots} 是<b>包私有</b>且绑在 {@code SdSnapshot} 上，gov 结构性拿不到（也不该拿：gov 禁 sd）。
 */
final class GovSnapshots {

  private GovSnapshots() {}

  static GovState of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state.module("gov").orElseThrow(() -> new IllegalStateException("state 里没有 gov 切片（装配故障）"));
    if (!(snapshot instanceof GovSnapshot govSnapshot)) {
      throw new IllegalStateException(
          "state 的 gov 切片不是 GovSnapshot: " + snapshot.getClass().getName());
    }
    return govSnapshot.state();
  }

  /**
   * map 切片（{@code gov.CreateNation} 校验 homeRegion 用；{@code gov.DeleteNation} 查 nation tag 用；只读）。
   */
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
   * unit 切片（{@code gov.AssignDecisionMakerGov} 判"这个单位存在吗、还是不是一个 GOV"用；只读，铁律 3）。
   *
   * <p>★ 2026-10-12 乙收尾新增：那条判据原本住 sd 的 {@code AffiliationTargets}（用 {@code
   * SdSnapshots.units}），随"归属归模块" 整条搬进本模块的命令 ⇒ 取数口也搬到这里（gov 本来就允许依赖 unit）。
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
