package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.unit.spi.AdjustCompositionHandler;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.unit.adjust-composition} 窄工具（阶段 D3a，2026-10-02 / D-009 补裁）：**有符号直改单单位人力/装备**的 GM
 * 调试直通口。
 *
 * <p>★ <b>工具名不是命令类型</b>：覆写 {@link #toolName()}；它**不进** catalog / {@code PAYLOAD_HINTS}（那两处认的是命令类型
 * {@code unit.AdjustComposition}）。
 *
 * <p>★ 载荷 {@code manpower/equipment = [{type,amount}]}，{@code amount} 有符号：正增量可**新建
 * type**（追加表尾）、负增量要求 type 已存在且 {@code |Δ| ≤ 当前值}；同表重复 type、long 溢出、越界都由域层具名拒。命令本身**仅 GM**（{@code
 * unit.AdjustComposition} 不打 {@code DmUsableCommand}，是 GM 调试直改原语， 与 {@code unit.SetComposition}
 * 的"可嵌令"不同待遇）， 但本工具只在 GM 桶注册（与其余窄写同制）。
 *
 * <p>★ 标 sensitive ⇒ 走审批门链（GM 侧 GmAutoApproveGate 无脑过）。
 */
public final class UnitAdjustCompositionTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（全局唯一；用户给定）。 */
  public static final String NAME = "simos.unit.adjust-composition";

  public UnitAdjustCompositionTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String commandType() {
    return AdjustCompositionHandler.TYPE;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "调试直改单位人力/装备 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 调试直改单位装备：固定 unit.AdjustComposition，载荷 {id, equipment[{type,amount(有符号)}]}"
        + "（★ S3b：manpower 已退役，非空 manpower 具名拒；人员属于 Social 家户）——正增量可新建 type（追加表尾）、"
        + "负增量要求 type 已存在且 |Δ| ≤ 当前值；同表 type 不重复、零增量合法 no-op。";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
