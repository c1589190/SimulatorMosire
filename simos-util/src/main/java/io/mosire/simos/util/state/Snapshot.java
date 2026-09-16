package io.mosire.simos.util.state;

import io.mosire.simos.util.time.SimosTimestamp;

/**
 * 模块状态切片（总纲 §4.5）：**不用万能父类**，各模块的快照是各自独立的 record 树。
 *
 * <p>`namespace()` 是 spec §十-D1 的增补——Util 不认识领域类型，只能靠它把切片与模块对上号。
 */
public interface Snapshot {

  StateRef ref();

  SimosTimestamp timestamp();

  /** 本快照所属模块（`"map"` / `"social"` / `"unit"`）。 */
  String namespace();
}
