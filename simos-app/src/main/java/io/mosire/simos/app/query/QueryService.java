package io.mosire.simos.app.query;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.List;
import java.util.Objects;

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
 * <p>★ **每次查询重放**（spec §5.1）：小规模可接受，缓存留待有消费者——**不做**。
 *
 * <p>★ **不静默编造**：本类对"没有候选"与"装配/输入故障"的分工完全交还给各 resolver/facet 与注册表——它自己只做"解析文本 → 取状态 → 转交"三件事，不
 * catch、不 fallback。
 */
public final class QueryService {

  private final CoreSimos core;
  private final ResolverRegistry resolverRegistry;
  private final FacetRegistry facetRegistry;

  public QueryService(
      CoreSimos core, ResolverRegistry resolverRegistry, FacetRegistry facetRegistry) {
    this.core = Objects.requireNonNull(core, "core");
    this.resolverRegistry = Objects.requireNonNull(resolverRegistry, "resolverRegistry");
    this.facetRegistry = Objects.requireNonNull(facetRegistry, "facetRegistry");
  }

  /**
   * 重放目标状态。{@code target.revision() == null} ⇒ 该分支 head；分支不存在 ⇒ {@link IllegalArgumentException}。
   *
   * @throws IllegalArgumentException 分支不存在（没有 head 可查）
   */
  public SimulationState stateAt(QueryTarget target) {
    Objects.requireNonNull(target, "target");
    RevisionId revision = target.revision();
    if (revision == null) {
      revision =
          core.head(target.branch())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException("分支不存在，没有 head 可查：" + target.branch().value()));
    }
    return core.replay(new StateRef(target.branch(), revision));
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
