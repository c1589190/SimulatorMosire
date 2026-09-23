package io.mosire.simos.app.query;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 数据脱敏层（spec §3.4/§七.3）：按 actor 的**可见范围**裁剪 {@link QueryService} 的结果。
 *
 * <p>★ **插桩点在 app 层**（spec §七.1 实测 {@code QueryService} 四个读方法都没有 caller 参数）：GUI / 读工具调用本类而非直接读
 * {@code QueryService}。
 *
 * <p>★★ **T9 起本类换了可见性的来源（这是语义变更，不是改名）**：旧版本的 {@code scopeOf} 读的是决策人身上 GM **绝对指定**的 {@code
 * ViewScope}（一张写死的可见集合）。新语义下可见性由 **app 层的范围函数现算**（{@code NationScope} / {@code ArmyScope}），GM
 * 的配权（{@code AccessLimit}）只是**额外限制** ⇒ 两者求**交集**。
 *
 * <p>★★ **同源在这里是硬要求、不是风格**：本类**不再自己实现可见性规则**——它构造的上下文与 MCP / 决策人路径**同一份** （{@link
 * DecisionCallerFactory#readContextFor}，同一段 {@code narrowTo} 装配），判定也**复用同一批** 谓词（{@link
 * ToolSupport#hexVisible} / {@link ToolSupport#regionVisible} / {@link ToolSupport#unitVisible}）。
 * 各写一份的后果是"同一 actor 在两个端点上看到的东西不一样"，而**两边都不会报错**——GUI 与 MCP 会各自显得自洽。
 *
 * <p>★ **actor 不存在 ⇒ 什么都没有**（fail-closed，不是"放行"）：解不出决策人就拿不到上下文，所有谓词恒假、判决按 {@link
 * AccessLimit#empty()} 的 {@code WITHHELD} 口径（整条不出现）。
 *
 * <p>★ **只报可观察项**（spec §七.3）：被裁掉的项**整条消失**，不生成"未探测到 X"的否定式条目——故两个范围的输出是**子集关系**而非互斥补集。
 *
 * <p>★ **字段级与资源级分开**（spec §3.4）：资源级由权限组判（上面那套），**字段级**由 {@code redactedFields} 递归剔除（{@link
 * #applyRedactedFields}）——{@code ResourceScope} 管不到"能看这个资源的哪些字段"。
 */
public final class RedactingQueryService {

  private final QueryService query;
  private final DecisionScopeFunctions scopeFunctions;
  private final String mapId;

  public RedactingQueryService(
      QueryService query, DecisionScopeFunctions scopeFunctions, String mapId) {
    this.query = Objects.requireNonNull(query, "query");
    this.scopeFunctions = Objects.requireNonNull(scopeFunctions, "scopeFunctions");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  /**
   * actor 的**额外限制**（GM 用 {@code sd.SetDecisionMakerAccess} 配的那一层）；actor 不存在 ⇒ {@link
   * AccessLimit#empty()}（fail-closed）。
   *
   * <p>★ 注意它**不是**"该 actor 能看见什么"——那要范围函数参与，见 {@link #readContextOf}。
   */
  public AccessLimit accessLimitOf(DecisionMakerId actor, QueryTarget target) {
    Objects.requireNonNull(actor, "actor");
    DecisionMaker maker = sdState(target).decisionMakers().get(actor);
    return maker == null ? AccessLimit.empty() : maker.accessLimit();
  }

  /**
   * actor 的**读视角上下文**：范围函数现算 ∩ GM 额外限制（与 MCP / 决策人路径**同一份装配**）；actor 不存在 ⇒ 空。
   *
   * <p>★ 返回空是**唯一**的"这个 actor 什么都看不见"写法：调用方据此让所有谓词恒假，而不是拿一个 `AccessLimit.empty()`
   * 去凑上下文——空限制是"**不额外收紧**"（= 放行），与"够不着"方向相反（spec §5.2 第 3 条）。
   */
  public Optional<ToolContext> readContextOf(DecisionMakerId actor, QueryTarget target) {
    Objects.requireNonNull(actor, "actor");
    SimulationState state = query.stateAt(target);
    DecisionMaker maker = sdState(target).decisionMakers().get(actor);
    if (maker == null) {
      return Optional.empty();
    }
    return Optional.of(DecisionCallerFactory.readContextFor(scopeFunctions, maker, state, mapId));
  }

  /**
   * actor 的**现算可见范围**（未经解码的 {@code ResourceScopeMap}）：范围函数 ∩ GM 额外限制；actor 不存在 ⇒ 空。
   *
   * <p>★★ **这是"GM 能看见某人能看见什么"的唯一数据源**——GUI 的范围端点、决策人每次工具调用、{@code as=} 读路径 三者都收敛到 {@link
   * DecisionCallerFactory#resourceScopesFor} 这一个方法上。**不要**在调用方另算一份：两份范围 **都不会报错**，只会慢慢漂移，而漂移的后果是"GM
   * 看到的范围"与"决策人实际的权力"分叉。
   *
   * <p>★ 返回空（actor 不存在）与"空范围"是**不同**的东西：前者是"这个人不存在"（端点据此 404），后者是"这个人此刻什么都看不见"。
   */
  public Optional<ResourceScopeMap> computedScopeOf(DecisionMakerId actor, QueryTarget target) {
    Objects.requireNonNull(actor, "actor");
    SimulationState state = query.stateAt(target);
    DecisionMaker maker = sdState(target).decisionMakers().get(actor);
    if (maker == null) {
      return Optional.empty();
    }
    return Optional.of(
        DecisionCallerFactory.resourceScopesFor(scopeFunctions, maker, state, mapId));
  }

  /** 裁剪后的地图总览：只保留可见 hex / region / city，并按 {@code redactedFields} 剔除命名字段。 */
  public Map<String, Object> mapOverview(DecisionMakerId actor, QueryTarget target) {
    SimulationState state = query.stateAt(target);
    GameMap map = ToolSupport.gameMap(state);
    Optional<ToolContext> context = readContextOf(actor, target);
    // ★ 谓词口径与读工具**逐条同源**：格的可见性 = 格自身 ∪ 其所属区域（spec §3.3 硬要求 1；
    //   国家决策人的范围是区域级前缀，只看 ① 会让它连本国格都读不到）。
    Map<String, Object> full =
        ToolSupport.mapOverview(
            mapId,
            map,
            coord -> context.map(c -> ToolSupport.hexVisible(c, mapId, map, coord)).orElse(false),
            region -> context.map(c -> ToolSupport.regionVisible(c, mapId, region)).orElse(false));
    return asMap(applyRedactedFields(full, accessLimitOf(actor, target)));
  }

  /** 裁剪后的单位列表：只留范围函数 ∩ accessLimit 之下的单位，并按 {@code redactedFields} 剔除命名字段。 */
  public List<Map<String, Object>> units(DecisionMakerId actor, QueryTarget target) {
    SimulationState state = query.stateAt(target);
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp at = state.meta().timestamp();
    GameMap map = ToolSupport.gameMap(state);
    Optional<ToolContext> context = readContextOf(actor, target);
    AccessLimit limit = accessLimitOf(actor, target);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> unit : ToolSupport.units(units, at, map)) {
      UnitId id = new UnitId(String.valueOf(unit.get("id")));
      if (context.map(c -> ToolSupport.unitVisible(c, id)).orElse(false)) {
        out.add(asMap(applyRedactedFields(unit, limit)));
      }
    }
    return List.copyOf(out);
  }

  // ── 可见性谓词 + redactedFields + adjudicationDisclosure ─────────────────────────────

  /** 该 actor 是否可见此 hex（"按地址取单个实体"的读端点的 fail-closed 判据）。 */
  public boolean seesHex(DecisionMakerId actor, QueryTarget target, HexCoord coord) {
    Objects.requireNonNull(coord, "coord");
    GameMap map = ToolSupport.gameMap(query.stateAt(target));
    return readContextOf(actor, target)
        .map(c -> ToolSupport.hexVisible(c, mapId, map, coord))
        .orElse(false);
  }

  /** 该 actor 是否可见此区域。 */
  public boolean seesRegion(DecisionMakerId actor, QueryTarget target, RegionId region) {
    Objects.requireNonNull(region, "region");
    return readContextOf(actor, target)
        .map(c -> ToolSupport.regionVisible(c, mapId, region))
        .orElse(false);
  }

  /** 该 actor 是否可见此单位。 */
  public boolean seesUnit(DecisionMakerId actor, QueryTarget target, UnitId unit) {
    Objects.requireNonNull(unit, "unit");
    return readContextOf(actor, target).map(c -> ToolSupport.unitVisible(c, unit)).orElse(false);
  }

  /**
   * 按 {@code redactedFields} **递归**剔除命名字段（T6，C29）：{@code Map} 的键命中即整条去掉、{@code List} 逐项下钻、标量原样返回。
   *
   * <p>★ **深拷贝只在有剔除时发生**（空 {@code redactedFields} ⇒ 原对象原样返回）——避免无谓复制，同时保证调用方拿到的是新树（不原地改响应）。
   */
  public Object applyRedactedFields(Object body, AccessLimit limit) {
    Objects.requireNonNull(limit, "limit");
    if (limit.redactedFields().isEmpty()) {
      return body;
    }
    return strip(body, limit.redactedFields());
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
   * 按 actor 的 {@code adjudicationDisclosure} 裁剪的判决视图（T6，C28；T9 起该档读自 {@link AccessLimit}）。
   *
   * <p>★ **三档语义**（{@link DisclosurePolicy}）：{@code FULL} = 全字段（含模型输出 {@code payload} 与 {@code
   * meta}）； {@code PERCEPTION_ONLY} = 只留**可观察项**（{@code id}/{@code breakpoint}/{@code
   * subject}/{@code atRevision}，去掉 不可感知的模型内部量）；{@code WITHHELD} = **整条不出现**（空列表，不是空串）。
   */
  public List<Map<String, Object>> verdicts(DecisionMakerId actor, QueryTarget target) {
    AccessLimit limit = accessLimitOf(actor, target);
    List<Map<String, Object>> views = verdictViews(sdState(target), limit.adjudicationDisclosure());
    List<Map<String, Object>> out = new ArrayList<>(views.size());
    for (Map<String, Object> view : views) {
      out.add(asMap(applyRedactedFields(view, limit)));
    }
    return List.copyOf(out);
  }

  // ── 决策结果（第 3 波第 3 步）：决策人**只能看与自己有关**的那些 INFO 条目 ────────────────

  /**
   * 决策结果的**可见窗口**（第 3 波第 3 步）：{@code tick} 或 {@code fromTick}/{@code toTick} 闭区间二选一，{@code limit}
   * 恒有界。
   *
   * <p>★ **为什么必须给上限**：决策结果按 tick 各一条、无上限地回放整段历史会让载荷随世界年龄线性膨胀——本类型的构造期断言把 {@code limit}
   * 钉成正数，本类每次取用都**先排后截**，故任何查询都是有界的。
   *
   * <p>★ **互斥与方向在构造期就判**（{@code tick} 与区间同时给、或 {@code fromTick > toTick} ⇒ 抛）——调用方（工具） 把这三种折成
   * {@code BAD_REQUEST}，不留"两种解释都说得通"的输入。
   */
  public record DecisionResultWindow(Long tick, Long fromTick, Long toTick, int limit) {

    public DecisionResultWindow {
      if (limit <= 0) {
        throw new IllegalArgumentException("limit 必须为正: " + limit);
      }
      if (tick != null && (fromTick != null || toTick != null)) {
        throw new IllegalArgumentException("tick 与 fromTick/toTick 互斥（二选一）");
      }
      if (fromTick != null && toTick != null && fromTick > toTick) {
        throw new IllegalArgumentException("fromTick 不得大于 toTick: " + fromTick + " > " + toTick);
      }
    }
  }

  /**
   * ★★ **决策人自己能看的决策结果**（第 3 波第 3 步，用户的原始诉求）：在 sd 的 INFO 覆盖层里取**地址前缀 为 {@link
   * AdjudicateTickTool#RESULT_ADDRESS_PREFIX}** 的条目，逐条按两个判据筛——
   *
   * <ol>
   *   <li>★ **归属**：{@code entry.tags()} **含该 actor 自己**。标签是"这条结果与谁有关"的唯一表述（写路径由 {@code
   *       sd.AdjudicateTick} 按**本次涉及的决策人**落）。**无主（{@code tags} 为空）⇒ 不属于任何决策人 ⇒
   *       对决策人一律不可见**——空集不是"大家都能看"；
   *   <li>**窗口**：{@code tick} / {@code [fromTick, toTick]} 闭区间（见 {@link DecisionResultWindow}）。
   * </ol>
   *
   * <p>★★ **这条判据为什么住在这里、而不是资源谓词或调用方**：INFO 条目的地址（{@code sd:adjudication.<tick>}）**不在
   * 任何决策人的可达面**（决策人的 sd 域只是 {@code sd:decision-maker/<自己>}，见 {@code
   * DecisionCallerFactory#selfDecisionScope}）⇒ 用 {@link ToolSupport} 的资源谓词判会把**全部**结果判成不可见。故归属是
   * **一条独立的可见性轴**（"这条 INFO 记的是谁的事"，不是"这块资源你能不能碰"）。它**只此一处**：GUI 的决策结果子页与决策人读工具
   * 都走本方法，不各自再实现一遍（各写一份时两边都不报错、只会漂移，正是 {@code RedactingQueryService} 类注里那条口径）。
   *
   * <p>★ **排序 = tick 降序、同 tick 按 id 升序**（新的在前；与 {@code SdQueryService.order()} 的读序同口径，界面/模型
   * 拿到的第一条就是"最近那次裁决"）。同 tick 在**今天的写路径下不可能出现两条**（{@code sd.AdjudicateTick} 按地址派生 id 保证 一个 tick
   * 一条），id 只是把"将来万一出现"定死成可复现的次序。**先排后截** ⇒ {@code limit} 截到的是**最近的 N 条**。
   *
   * <p>★ **空结果返回空列表**（不是异常、也不是"什么都没有"的含混）：调用方据此给出**明确可读**的"没有可查看的决策结果"。
   *
   * @return 每条 {@code {tick, id, tags(升序), value(原样的 Object), at{branch, revision}}}；{@code value}
   *     **不做 解析/改写**——{@link SdInfoEntry#value()} 的契约是裸 {@code Object}（只有标量往返有保证），本层不假装能解析任意值。
   */
  public List<Map<String, Object>> decisionResults(
      DecisionMakerId actor, QueryTarget target, DecisionResultWindow window) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(window, "window");
    SdState sd = sdState(target);
    List<SdInfoEntry> matched = new ArrayList<>();
    for (Map.Entry<String, List<SdInfoEntry>> at : sd.info().entrySet()) {
      if (!at.getKey().startsWith(AdjudicateTickTool.RESULT_ADDRESS_PREFIX)) {
        continue;
      }
      for (SdInfoEntry entry : at.getValue()) {
        if (entry.tags().contains(actor) && inWindow(entry.tick(), window)) {
          matched.add(entry);
        }
      }
    }
    matched.sort(
        Comparator.comparingLong(SdInfoEntry::tick)
            .reversed()
            .thenComparing(entry -> entry.id().value()));
    List<Map<String, Object>> out = new ArrayList<>();
    for (SdInfoEntry entry : matched) {
      if (out.size() >= window.limit()) {
        break;
      }
      out.add(decisionResultView(entry, target));
    }
    return List.copyOf(out);
  }

  /** tick 是否落在窗口内（{@code tick} 优先，其次闭区间；两者都空 = 不按 tick 筛，仅受 {@code limit} 约束）。 */
  private static boolean inWindow(long tick, DecisionResultWindow window) {
    if (window.tick() != null) {
      return tick == window.tick();
    }
    if (window.fromTick() != null && tick < window.fromTick()) {
      return false;
    }
    return window.toTick() == null || tick <= window.toTick();
  }

  /** 单条决策结果的视图；{@code at} 取**被查分支** + 该条目落盘时的 revision（{@code SdInfoEntry.at}）。 */
  private static Map<String, Object> decisionResultView(SdInfoEntry entry, QueryTarget target) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", entry.tick());
    view.put("id", entry.id().value());
    view.put("tags", entry.tags().stream().map(DecisionMakerId::value).sorted().toList());
    view.put("value", entry.value());
    Map<String, Object> at = new LinkedHashMap<>();
    at.put("branch", target.branch().value());
    at.put("revision", entry.at().value());
    view.put("at", at);
    return view;
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

  private SdState sdState(QueryTarget target) {
    SimulationState state = query.stateAt(target);
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("状态里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException("状态里 sd 切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
