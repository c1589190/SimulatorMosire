package io.mosire.simos.util.resolve;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;

/**
 * 命名空间解析器 SPI（总纲 §4.8）：各领域模块自己实现，Core 装配期注册。
 *
 * <p>返回**候选列表**（Human 形式可多解）；调用方选定后一律使用 canonical 地址。
 */
public interface Resolver {

  /** 本解析器负责的命名空间，与地址首段一致（`map` / `social` / `unit` / `agent`）。 */
  String namespace();

  QueryResult resolve(Address address, ResolveContext ctx);
}
