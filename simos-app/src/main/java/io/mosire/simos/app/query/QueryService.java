package io.mosire.simos.app.query;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.address.NodeAddress;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.NodeRef;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * 查询门面（spec §5.1，M5 T3）：GUI（T8）与工具集（T5）**唯一**的只读入口。
 *
 * <p>四条语义：
 *
 * <ol>
 *   <li>{@link #stateAt(QueryTarget)}——按坐标重放。{@code revision} 缺省（{@code null}）取该分支 head；分支不存在 ⇒ 抛。
 *   <li>{@link #resolve(String, QueryTarget)}——{@code Address.parse} → {@link
 *       ResolverRegistry#resolve}；坏地址文本、未注册命名空间 都**明确失败**（util 契约本就是"抛，不兜底"）。
 *   <li>{@link #facets(String, QueryTarget)}——{@code Address.parse} → {@link
 *       FacetRegistry#queryAll}；解析出的 {@link Address} 与 {@link ResolveContext} **原样**交给注册的
 *       provider（R6：不改字段、不重排）。
 *   <li>{@link #facetNames()}——注册序的 facet 名（装配完整性的读面，R7）。
 * </ol>
 *
 * <p>★ **读路径按坐标记忆化（M9 T3，取代"不做缓存"）**：`(branch, revision) → SimulationState` 的上界为 {@value
 * #STATE_CACHE_CAPACITY} 的有界 LRU（访问序）。它消灭了"同一坐标的多个读端点各自重放一次"—— overview/units/state/hex/path
 * 全部落在同一坐标上时只重放一次，而每次重放都要从磁盘重读并解码整个 checkpoint。
 *
 * <p>★ **失效是键的函数，不是钩子**：head 在查缓存**之前**就解析成具体 {@link RevisionId}，键是**已解析的** {@link
 * StateRef}。提交（{@code CommandBus.submit} / {@code AdvanceTime} / {@code ForkBranch}）产生新 revision ⇒
 * head 解析出新值 ⇒ 新键 ⇒ 未命中 ⇒ 读到的必是新状态；新分支同理（它有自己的键空间）。**故意不装**"提交时清缓存" 的钩子：那是与时间线并存的第二份真相，漏掉任一写路径就会读到旧
 * revision；键即失效条件则无从漏。
 *
 * <p>★ **缓存值是深度不可变的** {@link SimulationState}（{@code modules = Map.copyOf}；{@code GameMap} 的 7 张表
 * 全是 {@code Collections.unmodifiableMap}；各切片是 record；{@code InMemoryInfoSystem} 构造期深拷贝），故
 * **取用时直接返回、不做防御性拷贝**——拷贝既无收益，又会让"同坐标两次读拿到逐字段相等的状态"多一层无意义的擦除。
 *
 * <p>★ **不静默编造**：本类对"没有候选"与"装配/输入故障"的分工完全交还给各 resolver/facet 与注册表——它自己只做"解析文本 → 取状态 → 转交"三件事，不
 * catch、不 fallback。
 */
public final class QueryService {

  /**
   * 读路径缓存容量（条）：见类注。取 8 的理由——启动三端点共享同一坐标只需 1 条，时间轴预览会在少量坐标间跳， 8 条覆盖一个分支的近期工作集，同时把内存上界钉在 {@code 8 ×
   * 单状态}（大图档下每条状态数十 MB 量级）。
   */
  private static final int STATE_CACHE_CAPACITY = 8;

  private final CoreSimos core;
  private final ResolverRegistry resolverRegistry;
  private final FacetRegistry facetRegistry;

  /**
   * 有界 LRU：{@code accessOrder = true} ⇒ {@code get} 也更新新鲜度，淘汰最久未用者。
   *
   * <p>{@code synchronizedMap} 对**单次** {@code get}/{@code put} 加锁；GUI 走虚拟线程，同一坐标的并发首读可能
   * 各重放一次（幂等、只是白干），但不会互相覆盖出错误值。
   */
  private final Map<StateRef, SimulationState> stateCache =
      Collections.synchronizedMap(new StateCache(STATE_CACHE_CAPACITY));

  private final LongAdder stateCacheHits = new LongAdder();
  private final LongAdder stateCacheMisses = new LongAdder();

  public QueryService(
      CoreSimos core, ResolverRegistry resolverRegistry, FacetRegistry facetRegistry) {
    this.core = Objects.requireNonNull(core, "core");
    this.resolverRegistry = Objects.requireNonNull(resolverRegistry, "resolverRegistry");
    this.facetRegistry = Objects.requireNonNull(facetRegistry, "facetRegistry");
  }

  /**
   * 重放目标状态（缓存命中 ⇒ 不碰磁盘、不重放）。{@code target.revision() == null} ⇒ 该分支 head；分支不存在 ⇒ {@link
   * IllegalArgumentException}。
   *
   * @throws IllegalArgumentException 分支不存在（没有 head 可查）
   */
  /**
   * ★★★ <b>W10（A1）：读口等创世落盘的上限（毫秒）</b>。创世要在磁盘上编码写完整份状态（小世界实测 ~1.7 s）， 取 30 s
   * 是"远大于任何一次创世、又不会把读线程挂死"的折中；超时 ⇒ 具名 {@link WorldNotReadyException}（503）。
   */
  public static final long GENESIS_READY_WAIT_MILLIS = 30_000L;

  public SimulationState stateAt(QueryTarget target) {
    Objects.requireNonNull(target, "target");
    RevisionId revision = target.revision();
    if (revision == null) {
      Optional<RevisionId> head = core.head(target.branch());
      if (head.isEmpty() && core.genesisPending()) {
        // ★★★ W10（A1 启动竞态）：空库启动时 GUI/MCP 的口先监听、创世后落盘 ⇒ 这个窗口里的读会撞上
        //   "父链回退到根仍无可用 checkpoint"（500 + 14 行栈）。这里**等创世真的落盘**（不是重试、不是报错），
        //   就绪后只再读一次 head；超时才走下面的具名"世界未就绪"。
        core.awaitGenesisReady(GENESIS_READY_WAIT_MILLIS);
        head = core.head(target.branch());
      }
      revision =
          head.orElseThrow(
              () ->
                  core.genesisPending()
                      ? new WorldNotReadyException(
                          "世界尚未就绪（创世还未落盘；等 "
                              + GENESIS_READY_WAIT_MILLIS
                              + " ms 后仍无 "
                              + target.branch().value()
                              + " 的 head）")
                      : new IllegalArgumentException(
                          "分支不存在，没有 head 可查：" + target.branch().value()));
    }
    StateRef ref = new StateRef(target.branch(), revision);
    SimulationState cached = stateCache.get(ref);
    if (cached != null) {
      stateCacheHits.increment();
      return cached;
    }
    SimulationState state = core.replay(ref);
    stateCache.put(ref, state);
    stateCacheMisses.increment();
    return state;
  }

  /** 缓存命中次数（M9 T3 的可观测计数：第二次读同一坐标 ⇒ 该值 +1 且不触发任何 checkpoint 读取）。 */
  public long stateCacheHits() {
    return stateCacheHits.sum();
  }

  /** 缓存未命中（= 真重放）次数。 */
  public long stateCacheMisses() {
    return stateCacheMisses.sum();
  }

  /** 有界 LRU（访问序）：超过容量即淘汰最久未用者。 */
  private static final class StateCache extends LinkedHashMap<StateRef, SimulationState> {

    private static final long serialVersionUID = 1L;

    private final int capacity;

    StateCache(int capacity) {
      super(16, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<StateRef, SimulationState> eldest) {
      return size() > capacity;
    }
  }

  /** 地址文本 → 候选（canonical 回显）。bad 文本 / 未注册命名空间 ⇒ 抛（util 契约）。 */
  public QueryResult resolve(String addressText, QueryTarget target) {
    Address address = Address.parse(addressText);
    return resolverRegistry.resolve(address, contextAt(target));
  }

  /** 地址文本 → 全 facet 视图（注册序拼接）。bad 文本 ⇒ 抛；外来主体由各 provider 判"空列表"。 */
  public List<FacetEntry> facets(String addressText, QueryTarget target) {
    Address address = Address.parse(addressText);
    return facetRegistry.queryAll(address, contextAt(target));
  }

  /**
   * D-036：解析“节点地址”{@code treeXXX-tickXXX/...}；不是节点形态时回退到旧 {@link #resolve(String, QueryTarget)}。
   *
   * <p>节点地址的节点段决定 {@code (branch,tick)}；实体路径映射到现有 namespace 地址：
   *
   * <pre>
   * hex/&lt;q,r&gt;        → map:&lt;mapId&gt;:hex.&lt;q_r&gt;
   * region/&lt;id&gt;      → map:&lt;mapId&gt;:region.&lt;id&gt;
   * city/&lt;id&gt;        → map:&lt;mapId&gt;:city.&lt;id&gt;
   * unit/[subtype]/&lt;id&gt; → unit:&lt;id&gt;（subtype = army/gov/…，当前只作语义提示）
   * battle/&lt;id&gt;      → army:combat.&lt;id&gt;
   * battle/&lt;id&gt;/stage/&lt;sid&gt; → army:combat.&lt;id&gt;:stage.&lt;sid&gt;
   * army/&lt;id&gt;        → army:army.&lt;id&gt;
   * gov/&lt;id&gt;         → unit:&lt;id&gt;
   * </pre>
   *
   * <p>空路径（只给节点）返回一个 {@code node:<branch>-<tick>} 候选，typeName = {@code Node}。
   *
   * @param addressText 地址文本
   * @param fallback 非节点地址时使用的查询坐标
   * @param mapId 本世界的 map 称谓（hex/region/city 的 map 命名空间根）
   */
  public QueryResult resolveWorld(String addressText, QueryTarget fallback, String mapId) {
    Objects.requireNonNull(addressText, "addressText");
    Objects.requireNonNull(fallback, "fallback");
    Objects.requireNonNull(mapId, "mapId");
    if (!NodeAddress.looksLikeNodeAddress(addressText)) {
      return resolve(addressText, fallback);
    }
    NodeAddress nodeAddress = NodeAddress.parse(addressText);
    QueryTarget target = nodeTarget(nodeAddress.node());
    if (nodeAddress.path().isEmpty()) {
      String label = nodeAddress.node().label();
      return new QueryResult(
          List.of(new ResolvedSubject(new SubjectId("node", label), "node:" + label, "Node")));
    }
    Address entity = entityAddress(nodeAddress, mapId);
    return resolverRegistry.resolve(entity, contextAt(target));
  }

  /** D-036：节点地址的 facet 视图；只给节点（无实体路径）⇒ 空表。 */
  public List<FacetEntry> facetsWorld(String addressText, QueryTarget fallback, String mapId) {
    Objects.requireNonNull(addressText, "addressText");
    Objects.requireNonNull(fallback, "fallback");
    Objects.requireNonNull(mapId, "mapId");
    if (!NodeAddress.looksLikeNodeAddress(addressText)) {
      return facets(addressText, fallback);
    }
    NodeAddress nodeAddress = NodeAddress.parse(addressText);
    if (nodeAddress.path().isEmpty()) {
      return List.of();
    }
    Address entity = entityAddress(nodeAddress, mapId);
    return facetRegistry.queryAll(entity, contextAt(nodeTarget(nodeAddress.node())));
  }

  private QueryTarget nodeTarget(NodeRef node) {
    RevisionId revision =
        core.revisionAtTick(node.branch(), node.tick())
            .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + node.label()));
    return QueryTarget.at(node.branch(), revision);
  }

  private static Address entityAddress(NodeAddress nodeAddress, String mapId) {
    List<String> path = nodeAddress.path();
    String type = path.get(0);
    List<String> rest = nodeAddress.rest();
    return switch (type) {
      case "hex" -> {
        requireSize(rest, 1, nodeAddress, "hex/<q,r>");
        String coordText = rest.get(0).replace(',', '_');
        HexCoord coord = HexCoord.parse(coordText);
        yield new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("hex", coord.toString())));
      }
      case "region" -> {
        requireSize(rest, 1, nodeAddress, "region/<id>");
        yield new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("region", rest.get(0))));
      }
      case "city" -> {
        requireSize(rest, 1, nodeAddress, "city/<id>");
        yield new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("city", rest.get(0))));
      }
      case "unit" -> {
        if (rest.size() == 1) {
          yield new Address(List.of(new Namespace("unit"), Entity.of(rest.get(0))));
        }
        if (rest.size() == 2) {
          yield new Address(List.of(new Namespace("unit"), Entity.of(rest.get(1))));
        }
        throw new IllegalArgumentException(
            "unit 地址应为 unit/<id> 或 unit/<subtype>/<id>: " + nodeAddress.canonical());
      }
      case "gov" -> {
        requireSize(rest, 1, nodeAddress, "gov/<id>");
        yield new Address(List.of(new Namespace("unit"), Entity.of(rest.get(0))));
      }
      case "army" -> {
        requireSize(rest, 1, nodeAddress, "army/<id>");
        yield new Address(List.of(new Namespace("army"), Entity.of("army", rest.get(0))));
      }
      case "battle" -> {
        if (rest.size() == 1) {
          yield new Address(List.of(new Namespace("army"), Entity.of("combat", rest.get(0))));
        }
        if (rest.size() == 3 && "stage".equals(rest.get(1))) {
          yield new Address(
              List.of(
                  new Namespace("army"),
                  Entity.of("combat", rest.get(0)),
                  Entity.of("stage", rest.get(2))));
        }
        throw new IllegalArgumentException(
            "battle 地址应为 battle/<id> 或 battle/<id>/stage/<sid>: " + nodeAddress.canonical());
      }
      default ->
          throw new IllegalArgumentException(
              "未知节点实体类型: " + type + "（地址: " + nodeAddress.canonical() + "）");
    };
  }

  private static void requireSize(
      List<String> rest, int expected, NodeAddress address, String expectedForm) {
    if (rest.size() != expected) {
      throw new IllegalArgumentException("地址形态应为 " + expectedForm + ": " + address.canonical());
    }
  }

  /** 已注册 facet 名（注册序）。 */
  public List<String> facetNames() {
    return facetRegistry.facetNames();
  }

  /** {@link ResolveContext} 的 {@code at} 取**被查状态自己的**时间戳（spec §5.1），不是调用方另外给的时间。 */
  private ResolveContext contextAt(QueryTarget target) {
    SimulationState state = stateAt(target);
    return new ResolveContext(state, state.meta().timestamp());
  }

  /**
   * 查询坐标：分支 + 可选版本（{@code revision == null} ⇒ head）。
   *
   * <p>用工厂而非裸构造，是为了让"缺省 head"这件事在调用点有声：{@link #head(BranchId)} / {@link #at(BranchId, RevisionId)}。
   */
  public record QueryTarget(BranchId branch, RevisionId revision) {

    public QueryTarget {
      Objects.requireNonNull(branch, "branch");
    }

    /** 该分支 head（{@code revision} 缺省）。 */
    public static QueryTarget head(BranchId branch) {
      return new QueryTarget(branch, null);
    }

    /** 指定版本（{@code revision} 非 null）。 */
    public static QueryTarget at(BranchId branch, RevisionId revision) {
      return new QueryTarget(branch, Objects.requireNonNull(revision, "revision"));
    }
  }
}
