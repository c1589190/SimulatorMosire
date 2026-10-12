package io.mosire.simos.map.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.tools.MapPathPort;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.map.path}（工具面 M4）：**寻路试算**（A\*），起点由服务端取。
 *
 * <p>补的是 M4 侦察报告里的一条完全缺口：GUI 有 {@code GET /api/map/path}，MCP 侧只能靠"写一条 {@code unit.PlanRoute}
 * 试试看"——那**是写**（落 revision），试算本不该有副作用。
 *
 * <p>★ **起点由服务端取**（该单位在 head 时刻的 {@code effectivePosition}），调用方只给终点——与 GUI 同一口径
 * （前端不传起点，避免第二份真相）；寻路本身走注入的 {@link MapPathPort}（实现转调 unit 域的 {@code PathFinder}， app
 * 层不另写寻路——地图模块**不认识 unit**，铁律 3）。
 *
 * <p>★ **只在 GM 桶**（{@link GmOnlyRead}）：报告 §二-4 实测它是**对未见地形的推断**——反复试算可逐格还原 地形成本，等于一条**绕过 {@code
 * viewScope} 的测地形通道**。GM 本来就看得见全图，故对 GM 无风险；对决策人 则必须"必填 actor + 对未见格 fail-closed"，本批不做 ⇒ 先只给 GM。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-map}，对 app/core
 * 的直连换成构造期注入 （读口 + 视图口 + 寻路口）；四段行为（单位不存在 / 无有效位置 / 到不了 / 到达）与文案逐字不变。
 */
public final class MapPathTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.path";

  private final ToolReadPort reads;
  private final MapToolViews views;
  private final MapPathPort paths;

  public MapPathTool(ToolReadPort reads, MapToolViews views, MapPathPort paths) {
    this.reads = reads;
    this.views = views;
    this.paths = paths;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "寻路试算（只读）：给单位 id 与终点格，返回 {reachable, path}；起点由服务端按该单位的有效位置取";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("unit", ToolSchema.prop("string", "单位 id（起点取它的有效位置）"));
    props.put("q", ToolSchema.prop("integer", "终点格 q"));
    props.put("r", ToolSchema.prop("integer", "终点格 r"));
    return ToolSchema.schema(props, List.of("unit", "q", "r"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String unitText = ToolSchema.requiredText(args, "unit");
      int q = Math.toIntExact(ToolSchema.requiredLong(args, "q"));
      int r = Math.toIntExact(ToolSchema.requiredLong(args, "r"));
      SimulationState state = MapToolReads.state(reads, args);
      MapPathPort.Outcome outcome = paths.findPath(state, unitText, new HexCoord(q, r));
      if (outcome instanceof MapPathPort.Outcome.UnitMissing) {
        return ToolResult.error("NOT_FOUND", "单位不存在: " + unitText);
      }
      if (outcome instanceof MapPathPort.Outcome.Reachable reachable) {
        return ToolResults.ok(views.pathResult(true, reachable.path()));
      }
      return ToolResults.ok(views.pathResult(false, List.of()));
    } catch (ArithmeticException e) {
      return ToolResult.error("BAD_REQUEST", "q/r 超出 int 范围");
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
