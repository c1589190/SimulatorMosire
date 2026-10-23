package io.mosire.simos.economy.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.economy.tools.EconomyToolPerspective;
import io.mosire.simos.economy.tools.EconomyToolReads;
import io.mosire.simos.economy.tools.EconomyToolViews;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.economy.ownership}（H0.6 的产权读口）：某格**各家户持有什么** —— actor 侧的库存账与行侧的商品栏**并排**给。
 *
 * <p>★★ <b>它为什么必须存在</b>（{@code AGENT.md} §9.4 同族第五次那类口径错的结构性堵法）：两本账被单独读成 "全系统有多少"都是一次口径错。本工具把
 * {@code actorGoodsTotal} 与 {@code rowGoodsTotal} 放进**同一个响应**（见 {@code
 * ApiViews.economyOwnership}——唯一实现在 app 组合根）⇒ 读的人当场看得见两者差多少。
 *
 * <p>★★ <b>H1 之后它同时是"一本账"的判据</b>：家户 actor 已按"格 × 两组四行"播全（真档 799 × 8 = 6392 个） ⇒ {@code accounts} /
 * {@code actorGoodsTotal} **有数**，而 {@code rowGoodsTotal} **恒空** （{@code HouseholdEconomy} 里已经没有
 * {@code goods} 这一栏）—— 行侧还是全 0/非空，一眼就能看出账搬完没有。
 *
 * <p>★★ <b>只在 GM 桶</b>（{@link GmOnlyRead}）：响应里含 **actor 切片的商品余额**（{@code HouseholdInventory}）——
 * 那条面在本仓的资源表态里是 **缺省拒**（{@code ToolSupport.ALL_READ} 的 {@code actor} 一档就是 {@code DENY}），故本工具与
 * {@code sd.verdicts} 同款： 不向决策人桶开。
 *
 * <p>★ <b>视图只有一份</b>：体由 {@code ToolSupport.economyOwnership}（= {@code
 * ApiViews.economyOwnership}）装配，与 GUI 的 {@code GET /api/economy/ownership} **同一个函数**（{@code
 * AGENT.md} §8.3：不许在工具里另拼一遍）。
 */
public final class EconomyOwnershipTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.economy.ownership";

  /**
   * 本工具声明的读命名空间：{@code economy}（行侧账与产业）与 {@code map}（**视野判据的资源**，见 {@code EconomyHexTool} 的同款声明）。
   *
   * <p>★ <b>为什么不声明 {@code actor}</b>：它与 {@code ALL_READ} 的第 4 档同口径 —— actor
   * 面**缺省拒**，只有显式表过态的调用者看得见； 而本工具本身只进 GM 桶（GM 的范围是 unlimited）⇒ 不给未表态的调用者留一条"声明了却放行"的口子。
   */
  private static final ResourceManifest RESOURCES =
      ResourceManifest.of(
          Map.of(
              ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolResources.MAP_NAMESPACE, ResourcePolicy.READ_ONLY));

  private final ToolReadPort reads;
  private final EconomyToolViews views;
  private final EconomyToolPerspective perspective;
  private final String mapId;

  /**
   * @param reads 窄读口（宿主实现：head 解析 / 创世等待 / 读缓存都在 app 的组合根里）
   * @param views 视图口（唯一实现 = 宿主的 {@code ApiViews}，与 GUI 同一份函数）
   * @param perspective 宿主视角口（可见性围栏唯一实现 = app 的 {@code ToolSupport.hexVisible}）
   * @param mapId 地图 id（资源路径与可见性判据用）
   */
  public EconomyOwnershipTool(
      ToolReadPort reads,
      EconomyToolViews views,
      EconomyToolPerspective perspective,
      String mapId) {
    this.reads = reads;
    this.views = views;
    this.perspective = perspective;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查某格的产权读数：actor 侧每本账（谁在这格持有什么：goods + money）+ actor 侧商品合计 +"
        + " **actor 侧货币合计（actorMoneyTotal，逐币种）** + **行侧家户库存合计**"
        + "（两个合计并排 ⇒ 不会把其中一本账读成「全系统」；钱与货同住一本 HouseholdInventory，裁定 M2）。"
        + "响应另含统一 dashboard 块（stocks/flows/derived/crisis/windows），与 simos.economy.hex 共用同一份装配；"
        + "本入口没有 SimulationState ⇒ tick 与进程内 MarketReport 等参考口径在 windows.unavailable 具名缺失";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("q", ToolSchema.prop("integer", "六角列坐标 q"));
    props.put("r", ToolSchema.prop("integer", "六角行坐标 r"));
    Map<String, Object> schema = ToolSchema.schema(props, List.of("q", "r"));
    schema.put(
        "description",
        "响应体含统一 dashboard 块（stocks/flows/derived/crisis/windows，与 simos.economy.hex 同装配）；"
            + "本入口无 SimulationState ⇒ tick/进程内 MarketReport 参考口径在 windows.unavailable 具名缺失");
    return schema;
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
              (int) ToolSchema.requiredLong(args, "q"), (int) ToolSchema.requiredLong(args, "r"));
      SimulationState state = EconomyToolReads.state(reads, args);
      GameMap map = EconomyToolReads.gameMap(state);
      if (!map.hexes().containsKey(coord) || !perspective.hexVisible(context, mapId, map, coord)) {
        return ToolResult.error("NOT_FOUND", "六角格不存在: " + coord.q() + "_" + coord.r());
      }
      return ToolResults.ok(views.economyOwnership(coord, state));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
