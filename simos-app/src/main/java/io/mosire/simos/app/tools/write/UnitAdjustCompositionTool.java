package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.unit.spi.AdjustCompositionHandler;
import java.util.Map;

/**
 * {@code simos.unit.adjust-composition} 窄工具（阶段 D3a，2026-10-02 / D-009 补裁）：**有符号直改单单位人力/装备**的 GM
 * 调试直通口。
 *
 * <p>★ <b>工具名不是命令类型</b>：覆写 {@link #toolName()}；它**不进** catalog / {@code PAYLOAD_HINTS}（那两处认的是命令类型
 * {@code unit.AdjustComposition}）。
 *
 * <p>★ 载荷 {@code manpower/equipment = [{type,amount}]}，{@code amount} 有符号：正增量可**新建
 * type**（追加表尾）、负增量要求 type 已存在且 {@code |Δ| ≤ 当前值}；同表重复 type、long 溢出、越界都由域层具名拒。命令本身**非 GmOnly**（与既有
 * unit 命令同待遇）， 但本工具只在 GM 桶注册（与其余窄写同制）。
 *
 * <p>★ 标 sensitive ⇒ 走审批门链（GM 侧 GmAutoApproveGate 无脑过）。
 */
public final class UnitAdjustCompositionTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一；用户给定）。 */
  public static final String NAME = "simos.unit.adjust-composition";

  public UnitAdjustCompositionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
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
    return "GM 调试直改单位人力/装备：固定 unit.AdjustComposition，载荷 {id, manpower[{type,amount(有符号)}],"
        + " equipment[{type,amount(有符号)}]}——正增量可新建 type（追加表尾）、负增量要求 type 已存在且 |Δ| ≤ 当前值；"
        + "同表 type 不重复、零增量合法 no-op；一条命令原子改两张表。";
  }
}
