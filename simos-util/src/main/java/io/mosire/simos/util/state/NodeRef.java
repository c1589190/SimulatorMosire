package io.mosire.simos.util.state;

import java.util.Objects;

/**
 * 时间线节点坐标（D-036）：{@code (branch, tick)}。
 *
 * <p>★ 一个 {@code (branch, tick)} 对应**一个节点**；同一 tick 的另一个版本由**另一条分支**表达，
 * 不再用“同 tick 多 revision”的 rev 形态。
 *
 * <p>★ 本类型只表达坐标，不表达“该节点当前解析到哪条 revision”。revision 是内部存储序号；
 * 地址对外只用 {@code treeXXX-tickXXX}。
 *
 * @param branch 分支（如 {@code tree1}）
 * @param tick 模拟时刻（日；≥ 0）
 */
public record NodeRef(BranchId branch, long tick) {

  public NodeRef {
    Objects.requireNonNull(branch, "branch");
    if (tick < 0L) {
      throw new IllegalArgumentException("NodeRef.tick 必须 ≥ 0: " + tick);
    }
  }

  /** 地址文本：{@code treeXXX-tickXXX}。 */
  public String label() {
    return branch.value() + "-" + tick;
  }

  @Override
  public String toString() {
    return label();
  }
}
