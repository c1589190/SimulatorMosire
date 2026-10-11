package io.mosire.simos.app.tools;

import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@link ToolReadPort} 的宿主实现（组合根）：把"值参坐标"还原成查询门面的 {@code QueryTarget} 并**逐条转调** ——head
 * 解析、创世等待、读缓存、异常语义全部留在 {@link QueryService} 一处（下放到各域的工具因此拿到与本仓其它 调用点**同一份**读路径）。
 *
 * <p>★ 这是**通用**适配器（不是 map 专用）：任一域的工具搬下来都可以注它。
 */
public final class QueryServiceToolReadPort implements ToolReadPort {

  private final QueryService query;

  public QueryServiceToolReadPort(QueryService query) {
    this.query = Objects.requireNonNull(query, "query");
  }

  @Override
  public SimulationState stateAt(BranchId branch, RevisionId revision) {
    return query.stateAt(targetOf(branch, revision));
  }

  @Override
  public List<FacetEntry> facets(String address, BranchId branch, RevisionId revision) {
    return query.facets(address, targetOf(branch, revision));
  }

  /** 与工具载荷同义：{@code revision == null} ⇒ 该分支 head。 */
  private static QueryTarget targetOf(BranchId branch, RevisionId revision) {
    Objects.requireNonNull(branch, "branch");
    return revision == null ? QueryTarget.head(branch) : QueryTarget.at(branch, revision);
  }
}
