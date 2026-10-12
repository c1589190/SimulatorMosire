package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.ga.tools.GaToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public final class SdCombatsTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.combats";

  private final ToolReadPort reads;
  private final GaToolViews views;

  public SdCombatsTool(ToolReadPort reads, GaToolViews views) {
    this.reads = reads;
    this.views = views;
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
    return ToolSchema.schema(new LinkedHashMap<>(ToolSchema.targetProps()), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      SimulationState state = GaToolReads.state(reads, context.arguments());
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("combats", views.combats(state));
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
