package io.mosire.simos.app.tools.ga;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>{@link GaToolIdentity} 的 app 实现</b>（2026-10-12 B3 第七域）：身份派生、自指域 kind、越权围栏三件事
 * **逐条转调既有唯一实现**。
 *
 * <p>★ <b>围栏只有一份</b>：{@link #scopeViolation} 的判据（含"hex 走 hex 资源 ∪ 所属 region 资源两条通道"与 fail-closed
 * 的"未表态即拒"）**整体迁自** app 侧 {@code ProposeCallTool} 的同一段私有助手（那段代码原本也只有这一份， 工具下放时随之搬回组合根，而不是在 ga
 * 里重写一份）——拒因文案逐字不变（I-G1）。
 */
public final class AppGaToolIdentity implements GaToolIdentity {

  @Override
  public Optional<String> decisionMakerIdOf(AgentIdentity identity) {
    return DecisionCallerFactory.decisionMakerIdOf(identity);
  }

  @Override
  public String decisionMakerKind() {
    return DecisionCallerFactory.DECISION_MAKER_KIND;
  }

  @Override
  public String decisionPacketKind() {
    return DecisionCallerFactory.DECISION_PACKET_KIND;
  }

  @Override
  public Optional<String> scopeViolation(
      DecisionMakerId actor, SimulationState state, List<CommandTarget> targets, String mapId) {
    DecisionMaker maker = ToolSupport.gaState(state).decisionMakers().get(actor);
    if (maker == null) {
      return Optional.of("决策人不存在: " + actor.value());
    }
    ResourceScopeMap scopes =
        DecisionCallerFactory.resourceScopesFor(
            DecisionScopeFunctions.defaults(), maker, state, mapId);
    return firstScopeViolation(state, scopes, targets);
  }

  // ── 以下四段与迁入前 ProposeCallTool 的私有助手逐字相同（唯一一份，住组合根）──────────────────

  /** 逐条目标按调用者现算 scope 判越界；返回第一条越界的具名拒因。 */
  private static Optional<String> firstScopeViolation(
      SimulationState state, ResourceScopeMap scopes, List<CommandTarget> targets) {
    GameMap gameMap = null;
    for (CommandTarget target : targets) {
      ResourceScope scope = scopes.declaredScope(target.namespace());
      String problem;
      if (ToolSupport.MAP_NAMESPACE.equals(target.namespace())) {
        if (gameMap == null) {
          gameMap = ToolSupport.gameMap(state);
        }
        problem = mapViolation(gameMap, scope, target);
      } else {
        problem = simpleViolation(scope, target);
      }
      if (problem != null) {
        return Optional.of(problem);
      }
    }
    return Optional.empty();
  }

  /** map 目标：hex 走"hex 资源 ∪ 所属 region 资源"两条通道（与 {@code ToolSupport.hexVisible} 同款）。 */
  private static String mapViolation(GameMap gameMap, ResourceScope scope, CommandTarget target) {
    String hexPrefix = mapIdOf(target) + "/hex/";
    if (target.path().contains("/hex/")) {
      if (scope == null) {
        return "目标命名空间 map 调用者未表态，fail-closed 拒: " + target;
      }
      if (scope.allows(target.path())) {
        return null;
      }
      int slash = target.path().indexOf(hexPrefix);
      if (slash < 0) {
        return "地图 hex 目标路径非法: " + target;
      }
      HexCoord coord = parseHex(target.path().substring(slash + hexPrefix.length()));
      if (coord == null) {
        return "地图 hex 目标坐标解析失败: " + target;
      }
      for (RegionId owner : gameMap.regionIndex().regionOf(coord)) {
        if (scope.allows(ResourcePaths.region(mapIdOf(target), owner.value()))) {
          return null;
        }
      }
      return "地图 hex 目标超出可见范围: " + target;
    }
    if (scope == null || !scope.allows(target.path())) {
      return "地图目标超出可见范围: " + target;
    }
    return null;
  }

  private static String simpleViolation(ResourceScope scope, CommandTarget target) {
    if (scope == null) {
      return "目标命名空间 " + target.namespace() + " 调用者未表态，fail-closed 拒: " + target;
    }
    return scope.allows(target.path()) ? null : "目标超出可见范围: " + target;
  }

  /** 目标路径的首段就是 mapId（{@code <mapId>/hex/<q>_<r>}）；从路径取，避免成员变量与参数两处真相。 */
  private static String mapIdOf(CommandTarget target) {
    int slash = target.path().indexOf('/');
    return slash < 0 ? target.path() : target.path().substring(0, slash);
  }

  /** {@code q_r}（负号原样带）；解析失败 ⇒ null。 */
  private static HexCoord parseHex(String text) {
    int underscore = text.lastIndexOf('_');
    if (underscore <= 0 || underscore == text.length() - 1) {
      return null;
    }
    try {
      return new HexCoord(
          Integer.parseInt(text.substring(0, underscore)),
          Integer.parseInt(text.substring(underscore + 1)));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
