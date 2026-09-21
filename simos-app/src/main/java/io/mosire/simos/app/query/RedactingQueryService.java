package io.mosire.simos.app.query;

import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据脱敏层（spec §七.1/§七.3，D4，R10/N6）：**按 actor 的 {@link ViewScope} 裁剪** {@link QueryService} 的结果。
 *
 * <p>★ **插桩点在 app 层**（spec §七.1 实测 {@code QueryService} 四个读方法都没有 caller 参数）：GUI / 读工具调用本类而非直接读
 * {@code QueryService}。
 *
 * <p>★ **视图由模块侧的数据构造、渠道拿不到全量**（N17）：本类先按 {@link DecisionMakerId} 查出其 {@code ViewScope}（存在 sd 状态里、随
 * revision 落盘），再逐项裁剪；**actor 不存在 ⇒ 空范围**（fail-closed，不是"放行"）。
 *
 * <p>★ **只报可观察项**（spec §七.3）：被裁掉的项**整条消失**，不生成"未探测到 X"的否定式条目——故两 scope 的输出是**子集关系**而非互斥补集。
 */
public final class RedactingQueryService {

  private final QueryService query;

  public RedactingQueryService(QueryService query) {
    this.query = Objects.requireNonNull(query, "query");
  }

  /** actor 的可见范围；actor 不存在 ⇒ {@link ViewScope#empty()}（fail-closed）。 */
  public ViewScope scopeOf(DecisionMakerId actor, QueryTarget target) {
    Objects.requireNonNull(actor, "actor");
    DecisionMaker maker = sdState(target).decisionMakers().get(actor);
    return maker == null ? ViewScope.empty() : maker.viewScope();
  }

  /** 裁剪后的地图总览：只保留可见 hex / region / city。 */
  public Map<String, Object> mapOverview(DecisionMakerId actor, QueryTarget target, String mapId) {
    ViewScope scope = scopeOf(actor, target);
    SimulationState state = query.stateAt(target);
    Map<String, Object> full = ToolSupport.mapOverview(mapId, ToolSupport.gameMap(state));
    full.put("hexes", filterHexes(full.get("hexes"), scope));
    full.put("regions", filterById(full.get("regions"), idSet(scope.visibleRegions())));
    full.put("cities", filterCities(full.get("cities"), scope));
    return full;
  }

  /** 裁剪后的单位列表：可见单位 ∪ （{@code seeOwnUnits} 时的己方整棵子树）。 */
  public List<Map<String, Object>> units(DecisionMakerId actor, QueryTarget target) {
    ViewScope scope = scopeOf(actor, target);
    SimulationState state = query.stateAt(target);
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp at = state.meta().timestamp();
    GameMap map = ToolSupport.gameMap(state);
    Set<UnitId> visible = new LinkedHashSet<>(scope.visibleUnits());
    if (scope.seeOwnUnits()) {
      visible.addAll(ownUnitIds(actor, target, units, at));
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> unit : ToolSupport.units(units, at, map)) {
      if (visible.contains(new UnitId(String.valueOf(unit.get("id"))))) {
        out.add(unit);
      }
    }
    return List.copyOf(out);
  }

  private Set<UnitId> ownUnitIds(
      DecisionMakerId actor, QueryTarget target, UnitState units, SimosTimestamp at) {
    SdState sd = sdState(target);
    DecisionMaker maker = sd.decisionMakers().get(actor);
    if (maker == null) {
      return Set.of();
    }
    Set<UnitId> roots = new LinkedHashSet<>();
    switch (maker.affiliation()) {
      case Affiliation.Army army -> {
        Army value = sd.armies().get(army.armyId());
        if (value != null) {
          roots.add(value.rootUnit());
        }
      }
      case Affiliation.Nation nation -> {
        for (Army army : sd.armies().values()) {
          if (army.nationId().equals(nation.nationId())) {
            roots.add(army.rootUnit());
          }
        }
      }
    }
    Set<UnitId> out = new LinkedHashSet<>();
    for (UnitId root : roots) {
      collectSubtree(units, root, at, out);
    }
    return out;
  }

  private static void collectSubtree(
      UnitState units, UnitId root, SimosTimestamp at, Set<UnitId> out) {
    if (!units.units().containsKey(root) || !out.add(root)) {
      return;
    }
    for (Unit unit : units.units().values()) {
      if (unit.parent().valueAt(at).filter(root::equals).isPresent()) {
        collectSubtree(units, unit.id(), at, out);
      }
    }
  }

  private SdState sdState(QueryTarget target) {
    SimulationState state = query.stateAt(target);
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("状态里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException("状态里 sd 切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }

  private static List<Map<String, Object>> filterHexes(Object raw, ViewScope scope) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> item : asList(raw)) {
      HexCoord coord = new HexCoord(number(item.get("q")), number(item.get("r")));
      if (scope.visibleHexes().contains(coord)) {
        out.add(item);
      }
    }
    return List.copyOf(out);
  }

  private static List<Map<String, Object>> filterCities(Object raw, ViewScope scope) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> item : asList(raw)) {
      Object at = item.get("at");
      if (at instanceof Map<?, ?> atMap
          && scope
              .visibleHexes()
              .contains(new HexCoord(number(atMap.get("q")), number(atMap.get("r"))))) {
        out.add(item);
      }
    }
    return List.copyOf(out);
  }

  private static List<Map<String, Object>> filterById(Object raw, Set<String> ids) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> item : asList(raw)) {
      if (ids.contains(String.valueOf(item.get("id")))) {
        out.add(item);
      }
    }
    return List.copyOf(out);
  }

  private static Set<String> idSet(Set<?> values) {
    Set<String> out = new LinkedHashSet<>();
    for (Object value : values) {
      out.add(String.valueOf(value));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asList(Object raw) {
    if (raw instanceof List<?> list) {
      return (List<Map<String, Object>>) list;
    }
    return List.of();
  }

  private static int number(Object value) {
    return ((Number) value).intValue();
  }
}
