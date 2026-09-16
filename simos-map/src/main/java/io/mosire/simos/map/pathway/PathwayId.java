package io.mosire.simos.map.pathway;

/**
 * 线的稳定身份。
 *
 * <p>★ **一旦分配即持久化，不由内容派生** —— 内容派生会让"改一个中间节点"变成"换了一条河"（铁律 1：ID 是身份，不随内容漂移）。 生成期由 {@code (seed, 序号)}
 * 确定性派生（保证同种子可复现，即 L7）；编辑期由 Command 分配并持久化；**分裂出的新段拿新 ID， 缩短不改 ID**。
 *
 * <p>★ GSimulator 的线段**只有 groupId**（所有河流共享 {@code "river"} 一个身份）、链的身份是**返回列表的下标** ⇒
 * "单条连通性线段可寻址"当前做不到。本类型就是那个身份。
 *
 * <p>★ {@link #toString()} 返回**裸值**（不是 record 默认的 {@code PathwayId[value=p1]}）：本类型是 pathways 组件的
 * key，Task 6 的 {@code MapChangeSet} 用 {@code keyOf = toString()} 把 key 变成 {@code FieldDelta} 的
 * String key、apply 侧再用 {@link #parse} 还原 —— 默认实现会让 key 变成 {@code PathwayId[value=p1]}，apply 侧认不出来。
 * 换言之这个串是**地址**，不是调试输出。
 */
public record PathwayId(String value) {

  public PathwayId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("PathwayId 不得为空白");
    }
  }

  /** 裸值。见类注释：它是**地址形式**，供变更集的 String key 使用，**不是**给人看的调试输出。 */
  @Override
  public String toString() {
    return value;
  }

  /**
   * 解析 {@link #toString()} 的产物。**非空白即收**；空白与 {@code null} 抛 {@link IllegalArgumentException} ——
   * 与构造器**同口径**：宁抛不静默（把空白静默收下，等于让一个地址在往返里换了身份）。
   */
  public static PathwayId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PathwayId 不得为空白: " + text);
    }
    return new PathwayId(text);
  }
}
