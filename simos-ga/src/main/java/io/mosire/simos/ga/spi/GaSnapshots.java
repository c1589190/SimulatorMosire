package io.mosire.simos.ga.spi;

import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * ★★ <b>从 {@link SimulationState} 取 ga / sd 切片的唯一入口</b>（本包 handler 共用，形制照 {@code SdSnapshots}）。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code SdSnapshots} / {@code UnitSnapshots} 同口径）：缺切片、或切片类型不对，都抛 {@link
 * IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 *
 * <p>★★ <b>为什么 handler 还要读 sd 切片</b>：{@code ga.UpsertDecisionPacket} 要在<b>新建包</b>时校验 {@code
 * proposerId} 存在；{@code ga.IssueDirective} 要校验 {@code decisionMakerId} 与 {@code effects[]} 存在（这三张表
 * ——{@code decisionMakers} / {@code effects} / {@code info}——仍住 sd，属批次 3b）。这条读取是既有的 {@code ga → sd}
 * 方向，不新增模块边。★ 反向的读（sd 侧要读 ga 的 {@code directives}）走注入的 {@code
 * io.mosire.simos.sd.spi.DirectiveLookup}（实现 {@link GaDirectiveLookup}）。
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

  /** ga 切片的完整状态树（四组件：决策包 / 合并效果集 / 决策记录 / 判决）。 */
  public static GaState state(SimulationState state) {
    return of(state).state();
  }

  /** sd 切片（只读）：{@code decisionMakers} 的存在性判据仍在那边（批次 3b 才搬）。 */
  public static SdState sd(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("state 里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException(
          "state 的 sd 切片不是 SdSnapshot: " + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
