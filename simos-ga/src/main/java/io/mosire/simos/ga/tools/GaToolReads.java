package io.mosire.simos.ga.tools;

import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Map;

/**
 * ★★ <b>ga 工具面的取数助手</b>（2026-10-12 B3 第 7 域）：把 app 侧 {@code ToolSupport} 里"只依赖 util/sd"的那几段 （坐标解析 +
 * sd 切片提取）搬进域内。
 *
 * <p>★ <b>两套坐标解析口径都留</b>（逐字照抄，不合并）：
 *
 * <ul>
 *   <li>{@link #state} = app {@code ToolSupport.target(args, DEFAULT_BRANCH)} 的口径：读 {@code
 *       branch}（缺省 {@value ToolResources#DEFAULT_BRANCH}）+ {@code revision}（缺省 = head）；
 *   <li>{@link #stateOfExpectedRevision} = 决策包族/触发族工具自己的口径：读同两个键，但版本键是 {@code
 *       expectedRevision}。两套键名都是既有对外载荷面的一部分（改一个键名 = 改工具入参，破 I-G1）。
 * </ul>
 *
 * <p>★ <b>坐标为什么用值参</b>：宿主的 {@code QueryTarget} 是 app 类型（进不了本模块，I-G2）⇒ 端口收 {@code (branch,
 * revision)} 值参，{@code revision == null} 与"载荷里没给 revision"同义（取 head）。
 *
 * <p>★ <b>sd 切片缺失 = 装配故障，当场炸</b>（与 app 侧 {@code ToolSupport.sdState} 的文案逐字相同）：静默返回空会把装配
 * 错误伪装成"这个决策人没有归属"。
 */
public final class GaToolReads {

  private GaToolReads() {}

  /**
   * 取目标坐标的完整状态：{@code branch}（缺省 {@value ToolResources#DEFAULT_BRANCH}）+ {@code revision}（缺省 head）。
   */
  public static SimulationState state(ToolReadPort reads, Map<String, Object> args) {
    BranchId branch =
        new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
    Long revision = ToolSchema.optionalLong(args, "revision");
    return reads.stateAt(branch, revision == null ? null : new RevisionId(revision));
  }

  /**
   * 同 {@link #state}，但版本键是 {@code expectedRevision}（决策包族/触发族工具的既有载荷口径）。
   *
   * <p>★ 负值由调用方自己判（各家工具的拒因文案不一致，不在这里统一——改文案就是改对外载荷，破 I-G1）。
   */
  public static SimulationState stateOfExpectedRevision(
      ToolReadPort reads, Map<String, Object> args) {
    BranchId branch =
        new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
    Long expected = ToolSchema.optionalLong(args, "expectedRevision");
    return reads.stateAt(branch, expected == null ? null : new RevisionId(expected));
  }

  /** 载荷里的分支（缺省 {@value ToolResources#DEFAULT_BRANCH}）——给"要坐标、不要状态"的查询口用。 */
  public static BranchId branch(Map<String, Object> args) {
    return new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
  }

  /** 载荷里的版本（键 {@code revision}；{@code null} = head）——给"要坐标、不要状态"的查询口用。 */
  public static RevisionId revision(Map<String, Object> args) {
    Long revision = ToolSchema.optionalLong(args, "revision");
    return revision == null ? null : new RevisionId(revision);
  }

  /** 同 {@link #revision}，但键是 {@code expectedRevision}（决策包族/触发族的既有载荷口径）。 */
  public static RevisionId revisionOfExpectedRevision(Map<String, Object> args) {
    Long expected = ToolSchema.optionalLong(args, "expectedRevision");
    return expected == null ? null : new RevisionId(expected);
  }

  /** sd 切片（{@code state.module("sd")}）：缺切片 / 类型不对 = 装配故障，当场炸。 */
  public static SdState sd(SimulationState state) {
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalArgumentException("状态里没有 sd 模块切片——装配故障"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalArgumentException("sd 模块切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
