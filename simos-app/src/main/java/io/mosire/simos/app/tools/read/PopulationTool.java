package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.social.population}（spec §7.1 读工具）：某格在 head 时刻的人口取值。
 *
 * <p>人口走 {@link PopulationSeries#valueAt}（与 facet / GUI 同口径，不造第二份真相）；该格无人口序列 ⇒ {@code NOT_FOUND}。
 */
public final class PopulationTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.social.population";

  private final QueryService query;

  public PopulationTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查某格在 head 时刻的人口取值（population）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("q", ToolSupport.prop("integer", "六角列坐标 q"));
    props.put("r", ToolSupport.prop("integer", "六角行坐标 r"));
    return ToolSupport.schema(props, List.of("q", "r"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.SOCIAL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      HexCoord coord =
          new HexCoord(
              (int) ToolSupport.requiredLong(args, "q"), (int) ToolSupport.requiredLong(args, "r"));
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      PopulationSeries series = ToolSupport.socialData(state).populations().get(coord);
      // ★ **人口按格判可见性**（T10；spec §3.3 的 social 路径 = `<q>_<r>`，见 ToolSupport#resourceSocial）；
      //   不可见与"该格没有人口序列"同款——拒因逐字相同，不泄露"有数但你看不到"。
      if (series == null || !ToolSupport.populationVisible(context, coord)) {
        return ToolResult.error("NOT_FOUND", "该格没有人口序列: " + coord.q() + "_" + coord.r());
      }
      return ToolSupport.ok(ToolSupport.population(coord, series, state.meta().timestamp()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
