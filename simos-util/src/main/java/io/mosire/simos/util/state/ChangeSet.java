package io.mosire.simos.util.state;

/**
 * 变更集（总纲 §4.5）：字段清单由**各模块从自己的 Snapshot 类型派生**（铁律 5）， Util 只给接口与往返断言工具（{@code
 * io.mosire.simos.util.verify.RoundTripAssertions}）。
 */
public interface ChangeSet {

  RevisionId baseRevision();
}
