package io.mosire.simos.util.facet;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;
import java.util.List;

/**
 * 跨模块可见性协议（spec §八）：模块自己实现并注册，`MapSimos` 对提供者完全不知情。
 *
 * <p>"某个 hex 上有哪些单位"因此**不能**写成 `MapManager.getUnitsAt(hex)`（铁律 3）。
 */
public interface FacetProvider {

  /** 本面的名字，全注册表唯一（`"unitsHere"` / `"population"`）。 */
  String facetName();

  /** 空列表 = "该主体上我这一面没有内容"，不是错误。 */
  List<FacetEntry> query(Address subject, ResolveContext ctx);
}
