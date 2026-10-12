package io.mosire.simos.ga.spi;

import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * ★★ <b>从 {@link SimulationState} 取 ga / unit 切片的唯一入口</b>（本包 handler 共用，形制照 {@code SdSnapshots}）。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code SdSnapshots} / {@code UnitSnapshots} 同口径）：缺切片、或切片类型不对，都抛 {@link
 * IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 *
 * <p>★★ <b>为什么 handler 还要读 unit 切片</b>：{@code ga.RegisterEffect} 的 {@code Trigger.UnitAtHex}
 * 要校验"这个单位存在吗" （§八.4：效果引用合法性由代码判）。★ 这是 {@code ga → unit} 那条边的<b>运行时</b>用途（该边由用户 2026-10-12 裁定
 * 「<b>那让它依赖unit呗</b>」打开，见规划 §15）。
 *
 * <p>★★ <b>本批 3b-4 删掉的 {@code sd(...)}（如实记）</b>：批次 3b-2 起本类还有第三个方法（读 sd 切片），它的<b>唯一调用者</b>是 {@code
 * IssueDirectiveHandler} 的"效果存在吗"判据——{@code effects} 表搬进本模块后那条判据改读 {@link #state}，
 * 这个方法就<b>没有读者</b>了 ⇒ 同批整条删除（与 3d-2 删 {@code SdSnapshots.social(...)} 同款：唯一调用者一消失就删，
 * 不留"看着能用、其实无人用"的接缝）。★ 留在 sd 的那两支（{@code SdResolver} 的存在性判据、{@code AffiliationTargets} 的 gov
 * 单位校验）各有自己的取数口，不经本类。
 *
 * <p>★ 本类<b>只读</b>，既不 cache、也不改写任何切片（铁律 3）。
 */
public final class GaSnapshots {

  private GaSnapshots() {}

  /** ga 切片（{@code state.module("ga")}）：缺切片 / 类型不对 = 装配故障，当场炸。 */
  public static GaSnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state.module("ga").orElseThrow(() -> new IllegalStateException("state 里没有 ga 切片（装配故障）"));
    if (!(snapshot instanceof GaSnapshot gaSnapshot)) {
      throw new IllegalStateException(
          "state 的 ga 切片不是 GaSnapshot: " + snapshot.getClass().getName());
    }
    return gaSnapshot;
  }

  /** ga 切片的完整状态树（七组件：决策包 / 合并效果集 / 决策记录 / 判决 / 决策人 / INFO 覆盖层 / 效果）。 */
  public static GaState state(SimulationState state) {
    return of(state).state();
  }

  /**
   * unit 切片（只读）：{@code Trigger.UnitAtHex} 的存在性判据用它（{@code ga.RegisterEffect}）， {@code
   * GaTimeParticipant} 的触发求值也用它。
   */
  public static UnitState units(SimulationState state) {
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
