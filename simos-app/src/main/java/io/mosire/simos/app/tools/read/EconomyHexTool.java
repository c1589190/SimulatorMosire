package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.economy.hex}（R2a 的 G1 读口）：某格的经济读数——人口 / 有效劳动 / 土地 / 库存 / 货币 / 负债 / 制度 / 周期进度。
 *
 * <p>★★ **四桶共享**（不标 {@code GmOnlyRead}）：经济状态是**世界状态**（与 {@code simos.sd.combats} 同款判据）——决策人本来就该看得见
 * 自己辖地的产出与库存。★ 它的视野由 {@link ToolSupport#hexVisible} 收窄（**真判据**：走 map 的逐格/区域资源，不是"声明了却永远放行"
 * 的装饰）——不可见与"格不存在"**拒因逐字相同**（不给存在性侧信道，与 {@code simos.map.hex} / {@code simos.social.population}
 * 同口径）。
 *
 * <p>★ **视图只有一份**：体由 {@link ToolSupport#economyHex}（= {@code ApiViews.economyHex}）装配，与 GUI 的 {@code
 * GET /api/economy/hex} **同一个函数**（AGENT.md §8.3）。
 */
public final class EconomyHexTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.economy.hex";

  /**
   * 本工具声明的两个读命名空间：{@code economy}（数据本身）与 {@code map}（**视野判据的资源**）。
   *
   * <p>★ **为什么必须显式声明 {@code map}**：{@link ToolSupport#hexVisible} 判的是 {@code
   * map:<mapId>/hex/<q>_<r>} 或该格所属区域，而调用者的资源授权是拿**本工具声明的 manifest 缺省策略**与调用者权限求交的——未声明的命名空间走缺省 ⇒
   * 要么在系统身份下"看得到每一格"、要么在决策人身份下整调被拒，两种都不是"按视野收窄"。声明成 {@code READ_ONLY} 才是本工具想表达的语义（本用例实测踩过：不声明 map
   * 时每一格都判 NOT_FOUND）。
   */
  private static final ResourceManifest RESOURCES =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.READ_ONLY));

  private final QueryService query;
  private final String mapId;

  public EconomyHexTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查某格的经济读数：人口 / 有效劳动 / 土地（千分亩）/ 粮库存 / 货币 / 负债 / 各产业制度与周期进度"
        + "（按产业 id、槽位 id 字典序发；未激活或该格无产业 ⇒ activated=false、industries 为空）";
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
    return RESOURCES;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      HexCoord coord =
          new HexCoord(
              (int) ToolSupport.requiredLong(args, "q"), (int) ToolSupport.requiredLong(args, "r"));
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      GameMap map = ToolSupport.gameMap(state);
      if (!map.hexes().containsKey(coord) || !ToolSupport.hexVisible(context, mapId, map, coord)) {
        return ToolResult.error("NOT_FOUND", "六角格不存在: " + coord.q() + "_" + coord.r());
      }
      return ToolSupport.ok(ToolSupport.economyHex(coord, ToolSupport.economyData(state)));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
