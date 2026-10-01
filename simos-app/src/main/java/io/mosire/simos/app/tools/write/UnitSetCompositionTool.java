package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.unit.spi.SetCompositionHandler;
import java.util.Map;

/**
 * {@code simos.unit.set-composition} 窄工具（阶段 D3a，2026-10-02；原 {@code unit.SetStrength} 的 rename）：
 * **整表复写人力/装备**的 GM 窄写面。
 *
 * <p>★ <b>工具名不是命令类型</b>（D3a 起用户给定名为 {@code simos.unit.set-composition}）：覆写 {@link #toolName()}；它
 * **不进** catalog / {@code PAYLOAD_HINTS}（那两处认的是命令类型 {@code unit.SetComposition}）。
 *
 * <p>★ {@code manpower} 与 {@code equipment} 都是 `[{type,amount}]` **整表复写**（与 {@code
 * unit.ApplyCasualties} 的 "增量"、{@code unit.AdjustComposition} 的"有符号直改"是三种语义，别混用）；未知 type
 * 合法（给什么就是什么）， {@code amount ≥ 0}、同表 type 不重复由域层构造期拒。
 *
 * <p>★ 标 sensitive ⇒ 走审批门链（GM 侧 GmAutoApproveGate 无脑过，与其余窄写同制）。
 */
public final class UnitSetCompositionTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一；用户给定）。 */
  public static final String NAME = "simos.unit.set-composition";

  public UnitSetCompositionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String commandType() {
    return SetCompositionHandler.TYPE;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "整表复写单位人力/装备 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "整表复写单位人力/装备：固定 unit.SetComposition，载荷 {id, manpower[{type,amount}],"
        + " equipment[{type,amount}]}——两张表整体取代旧表（不是增量）；未知 type 合法；amount ≥ 0、同表 type 不重复。"
        + "旧 unit.SetStrength 已按 D-011 删除，不留兼容。";
  }
}
