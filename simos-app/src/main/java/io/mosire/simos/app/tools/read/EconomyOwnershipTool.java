package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.economy.ownership}（H0.6 的产权读口）：某格**各家户持有什么** —— 行侧的家户库存与 actor 侧的库存账**并排**给。
 *
 * <p>★★ <b>它为什么必须存在</b>（{@code AGENT.md} §9.4 同族第五次那类口径错的结构性堵法）：行侧的 {@code ClassRow.goods} 与 actor
 * 侧的 {@code GoodsAccount} 是**两本不同性质的账**，任何一方被单独读成"全系统有多少"都是一次口径错。本工具把
 * {@code actorGoodsTotal} 与 {@code rowGoodsTotal} 放进**同一个响应**（见 {@link
 * io.mosire.simos.app.gui.ApiViews#economyOwnership}）⇒ 读的人当场看得见两者差多少。
 *
 * <p>★★ <b>只在 GM 桶</b>（{@link GmOnlyRead}）：响应里含 **actor 切片的商品余额**（{@code GoodsAccount}）—— 那条面在本仓的资源表态里是
 * **缺省拒**（{@link ToolSupport#ALL_READ} 的 {@code actor} 一档就是 {@code DENY}），故本工具与 {@code sd.verdicts} 同款：
 * 不向决策人桶开。★ 本轮（H0）家户 actor 还没播种（H1 的事）⇒ {@code accounts} 是空表、{@code actorGoodsTotal} 全 0，
 * 那是**合法且正确**的状态，不是"读口坏了"。
 *
 * <p>★ <b>视图只有一份</b>：体由 {@link ToolSupport#economyOwnership}（= {@code ApiViews.economyOwnership}）装配，与 GUI 的
 * {@code GET /api/economy/ownership} **同一个函数**（{@code AGENT.md} §8.3：不许在工具里另拼一遍）。
 */
public final class EconomyOwnershipTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.economy.ownership";

  /**
   * 本工具声明的读命名空间：{@code economy}（行侧账与产业）与 {@code map}（**视野判据的资源**，见 {@code EconomyHexTool} 的同款声明）。
   *
   * <p>★ <b>为什么不声明 {@code actor}</b>：它与 {@code ALL_READ} 的第 4 档同口径 —— actor 面**缺省拒**，只有显式表过态的调用者看得见；
   * 而本工具本身只进 GM 桶（GM 的范围是 unlimited）⇒ 不给未表态的调用者留一条"声明了却放行"的口子。
   */
  private static final ResourceManifest RESOURCES =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.READ_ONLY));

  private final QueryService query;
  private final String mapId;

  public EconomyOwnershipTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查某格的产权读数：actor 侧每本库存账（谁在这格持有什么）+ actor 侧合计 + **行侧家户库存合计**"
        + "（两个合计并排 ⇒ 不会把其中一本账读成「全系统」；本轮家户 actor 尚未播种 ⇒ accounts 为空表）";
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
      return ToolSupport.ok(ToolSupport.economyOwnership(coord, state));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
