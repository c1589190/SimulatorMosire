package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.sd.combats}（工具面补齐，2026-09-25）：**交战记录只读面**。
 *
 * <p>补的是今日新加的 GUI 读口 {@code GET /api/sd/combats}（{@code GuiServer:249} 常量、{@code GuiServer:637}
 * 分支）在 MCP 侧的对应工具——没有它，Agent 看不见"记录在案的交战"（交战 id、名称、交战格、当前阶段、已选结局、参与单位）， 只能从"同格 ≥2 个 rootId /
 * ENGAGED"去**推断**，而真实记录里的交战（尤其"交战双方不同格"的合法语义）推不出来。
 *
 * <p>★ **形状与 GUI 同源**：直接走 {@link ApiViews#combats(io.mosire.simos.sd.state.SdState,
 * io.mosire.simos.unit.UnitState, io.mosire.simos.util.time.SimosTimestamp)}（GUI {@code
 * combatsReply} 用的就是它）——本类只装配 {@code {"combats":[…"}}，不另拼一份 payload。
 *
 * <p>★ **四桶共享**（不标 {@code GmOnlyRead}）：交战记录是世界状态（{@code SdState.combatStates}，可回放）， 不是别人的底牌、也不是地形探测
 * ⇒ 决策人与外部 Agent 都该能看（与 {@code simos.map.hex}/{@code simos.unit.list} 同档）。
 */
public final class SdCombatsTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.combats";

  private final QueryService query;

  public SdCombatsTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "交战记录清单：{combats:[{combatId,combatStateId,name,hex,currentStage,currentStageName,selectedOutcome,"
        + "participants,participantsAtHex,participantCount,participantsAtHexCount}]}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(new LinkedHashMap<>(ToolSupport.targetProps()), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      var target = ToolSupport.target(context.arguments(), ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put(
          "combats",
          ApiViews.combats(
              ApiViews.sdState(state), ApiViews.unitState(state), state.meta().timestamp()));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
