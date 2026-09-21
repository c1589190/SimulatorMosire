package io.mosire.simos.app.query;

import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.Verdict;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 *
 * <p>★★ **T6 收口**（spec `C28`/`C29`/`C30`）：此前 {@code adjudicationDisclosure} 与 {@code
 * redactedFields} **被解析、被存、却从未被应用**（只经 {@code ChannelAdmission.redactedBrief} 回显）。本类补齐两件事：
 *
 * <ol>
 *   <li>{@link #verdicts(DecisionMakerId, QueryTarget)}：按 {@code adjudicationDisclosure} 三档裁剪**判决**
 *       的可见内容——{@code FULL} 全字段 / {@code PERCEPTION_ONLY} 只留可观察项（去掉模型输出 {@code payload} 与 {@code
 *       meta}）/ {@code WITHHELD} **整条不出现**（空列表，不是空串）。
 *   <li>{@link #applyRedactedFields(Object, ViewScope)}：按 {@code redactedFields}
 *       **递归按字段名剔除**（单位读数里的 {@code position} 即以此消失）；{@link #seesHex} / {@link #seesRegion} / {@link
 *       #seesUnit} 给"按地址取单个实体" 的读端点提供 **fail-closed 可见性**（不可见 ⇒ 调用方折成 404，与"不存在"同形）。
 * </ol>
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

  /** 裁剪后的地图总览：只保留可见 hex / region / city，并按 {@code redactedFields} 剔除命名字段。 */
  public Map<String, Object> mapOverview(DecisionMakerId actor, QueryTarget target, String mapId) {
    ViewScope scope = scopeOf(actor, target);
    SimulationState state = query.stateAt(target);
    Map<String, Object> full = ToolSupport.mapOverview(mapId, ToolSupport.gameMap(state));
    full.put("hexes", filterHexes(full.get("hexes"), scope));
    full.put("regions", filterById(full.get("regions"), idSet(scope.visibleRegions())));
    full.put("cities", filterCities(full.get("cities"), scope));
    return asMap(applyRedactedFields(full, scope));
  }

  /** 裁剪后的单位列表：可见单位 ∪ （{@code seeOwnUnits} 时的己方整棵子树），并按 {@code redactedFields} 剔除命名字段。 */
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
        out.add(asMap(applyRedactedFields(unit, scope)));
      }
    }
    return List.copyOf(out);
  }

  // ── T6：可见性谓词 + redactedFields + adjudicationDisclosure ─────────────────────────

  /** 该 actor 是否可见此 hex（"按地址取单个实体"的读端点的 fail-closed 判据）。 */
  public boolean seesHex(DecisionMakerId actor, QueryTarget target, HexCoord coord) {
    Objects.requireNonNull(coord, "coord");
    return scopeOf(actor, target).visibleHexes().contains(coord);
  }

  /** 该 actor 是否可见此区域。 */
  public boolean seesRegion(DecisionMakerId actor, QueryTarget target, RegionId region) {
    Objects.requireNonNull(region, "region");
    return scopeOf(actor, target).visibleRegions().contains(region);
  }

  /** 该 actor 是否可见此单位（{@code visibleUnits} ∪ {@code seeOwnUnits} 的己方整棵子树）。 */
  public boolean seesUnit(DecisionMakerId actor, QueryTarget target, UnitId unit) {
    Objects.requireNonNull(unit, "unit");
    ViewScope scope = scopeOf(actor, target);
    if (scope.visibleUnits().contains(unit)) {
      return true;
    }
    if (!scope.seeOwnUnits()) {
      return false;
    }
    SimulationState state = query.stateAt(target);
    return ownUnitIds(actor, target, ToolSupport.unitState(state), state.meta().timestamp())
        .contains(unit);
  }

  /**
   * 按 {@code redactedFields} **递归**剔除命名字段（T6，C29）：{@code Map} 的键命中即整条去掉、{@code List} 逐项下钻、标量原样返回。
   *
   * <p>★ **深拷贝只在有剔除时发生**（空 {@code redactedFields} ⇒ 原对象原样返回）——避免无谓复制，同时保证调用方拿到的是新树（不原地改响应）。
   */
  public Object applyRedactedFields(Object body, ViewScope scope) {
    Objects.requireNonNull(scope, "scope");
    if (scope.redactedFields().isEmpty()) {
      return body;
    }
    return strip(body, scope.redactedFields());
  }

  private static Object strip(Object node, Set<String> fields) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (fields.contains(key)) {
          continue;
        }
        out.put(key, strip(entry.getValue(), fields));
      }
      return out;
    }
    if (node instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(strip(item, fields));
      }
      return out;
    }
    return node;
  }

  /** 无 actor（GM / 调试）的判决视图：{@code FULL} 口径，全量字段。 */
  public List<Map<String, Object>> verdicts(QueryTarget target) {
    return verdictViews(sdState(target), DisclosurePolicy.FULL);
  }

  /**
   * 按 actor 的 {@code adjudicationDisclosure} 裁剪的判决视图（T6，C28）。
   *
   * <p>★ **三档语义**（{@link DisclosurePolicy}）：{@code FULL} = 全字段（含模型输出 {@code payload} 与 {@code
   * meta}）； {@code PERCEPTION_ONLY} = 只留**可观察项**（{@code id}/{@code breakpoint}/{@code
   * subject}/{@code atRevision}，去掉 不可感知的模型内部量）；{@code WITHHELD} = **整条不出现**（空列表，不是空串）。
   */
  public List<Map<String, Object>> verdicts(DecisionMakerId actor, QueryTarget target) {
    ViewScope scope = scopeOf(actor, target);
    List<Map<String, Object>> views = verdictViews(sdState(target), scope.adjudicationDisclosure());
    List<Map<String, Object>> out = new ArrayList<>(views.size());
    for (Map<String, Object> view : views) {
      out.add(asMap(applyRedactedFields(view, scope)));
    }
    return List.copyOf(out);
  }

  /** 判决视图的公共构造：按 id 字典序（响应字节可复现），再按口径裁字段。 */
  private static List<Map<String, Object>> verdictViews(SdState sd, DisclosurePolicy policy) {
    if (policy == DisclosurePolicy.WITHHELD) {
      return List.of();
    }
    List<VerdictId> ids = new ArrayList<>(sd.verdicts().keySet());
    ids.sort(Comparator.comparing(VerdictId::value));
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (VerdictId id : ids) {
      Verdict verdict = sd.verdicts().get(id);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", verdict.id().value());
      view.put("breakpoint", verdict.breakpoint().value());
      view.put("subject", verdict.subject().canonical());
      view.put("atRevision", verdict.atRevision().value());
      if (policy == DisclosurePolicy.FULL) {
        view.put("payload", verdict.payloadJson());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", verdict.meta().model());
        meta.put("promptVersion", verdict.meta().promptVersion());
        meta.put("inputBriefDigest", verdict.meta().inputBriefDigest());
        view.put("meta", meta);
      }
      out.add(view);
    }
    return List.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
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
