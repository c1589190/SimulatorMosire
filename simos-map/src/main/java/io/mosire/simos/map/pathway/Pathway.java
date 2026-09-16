package io.mosire.simos.map.pathway;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 一条**极大简单链**：两端是端点或分支点。
 *
 * <p>★ **分支点即端点** —— 度数 &gt;= 3 的格是分支点，线在分支处断开 ⇒ "分支是独立的线"落地为"分支点把线切成一串"。
 *
 * <p>★ 取端点时**整条验一遍**（{@link #start()}/{@link #end()}）：重复边、链**内部**的分支点、中段断开，一律抛 {@link
 * IllegalStateException}，不静默 —— 本类型**直接从存档反序列化**，畸形输入是正常到达路径。
 *
 * <p>★ GSimulator 的线段**只有 groupId**（所有河流共享 {@code "river"} 一个身份），链的身份是**返回列表的下标** （{@code
 * MapService.java:757} 的 Javadoc；{@code GSimapEdgeTraceTool:65-70} 按 {@code i+1} 编号打印）⇒ 总纲 §5.1
 * 要的"单条连通性线段可寻址"当前做不到。{@link PathwayId} 是本类型的身份，{@code edges} 是有序的链身。
 *
 * <p>★ {@code edges} 的**顺序即链的走向**，故 {@link #start()}/{@link #end()} 由它决定；反着存是同一条链但**不是同一个
 * Pathway**（record 的 {@code equals} 逐组件比，{@code List.equals} 有序）。{@code props} 是这条线自己的属性，与边上 的
 * {@link EdgeTags} 不是一回事（后者挂在边上，一条边可同时属于若干条线）。
 *
 * <p>★ {@code name} **不设校验**（与 {@code TerrainType.description} 同口径）：无名道路是合法的，老仓把 {@code null} 静默填成
 * {@code ""} 才是要禁的那件事 —— 这里既不填也不拦，null 就是 null。
 */
public record Pathway(
    PathwayId id, String name, String groupId, List<EdgeRef> edges, Map<String, Object> props) {

  public Pathway {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (groupId == null || groupId.isBlank()) {
      throw new IllegalArgumentException("groupId 不得为空白");
    }
    if (edges == null) {
      throw new IllegalArgumentException("edges 不得为 null");
    }
    if (props == null) {
      throw new IllegalArgumentException("props 不得为 null");
    }
    edges = List.copyOf(edges);
    props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
  }

  /**
   * 链头的端点。**闭环**（每个格在链上的度数都恰为 2 ⇒ 无端点）时取**规范序最小的格**作锚（spec §5.2）。
   *
   * <p>空链没有端点，抛 {@link IllegalStateException}（构造期不拦空链：{@code edges} 为空是本类型合法的**原料**状态， 端点只是那时还不存在）。
   */
  public HexCoord start() {
    return endpoint(true);
  }

  /** 链尾的端点。闭环时与 {@link #start()} 同取锚 —— 于是"两端相等"就是"这条线是闭环"。 */
  public HexCoord end() {
    return endpoint(false);
  }

  /** 边数。**不是格数**。 */
  public int length() {
    return edges.size();
  }

  /** 取链头或链尾。**取之前先把整条链验一遍** —— 见 {@link #verifySimpleChain()} 与 {@link #walkToTail}。 */
  private HexCoord endpoint(boolean head) {
    if (edges.isEmpty()) {
      throw new IllegalStateException("空链没有端点: " + id);
    }
    verifySimpleChain();
    HexCoord from = chainHead();
    HexCoord to = walkToTail(from);
    if (to.equals(from)) {
      return anchor(); // 闭环：无端点，两端同取锚
    }
    return head ? from : to;
  }

  /**
   * 校验 {@code edges} 够得上"极大简单链"：**无重复边**、**无分支点**（点数度 ≤ 2）。
   *
   * <p>★ 这两条原先没有 —— 原实现只在头尾各看一对相邻边，于是三种畸形输入都能溜过去、并**静默产出看起来合理的端点**： 中途分叉（{@code
   * [(A,B),(B,C),(B,D)]}，B 度为 3 ⇒ 端点 A 与 D）、中段断开（{@code [(A,B),(B,C),(X,Y),(Y,Z)]} ⇒ 端点 A 与
   * Z）、同一条边写两遍（{@code [AB, AB]} ⇒ 被当成"长度为 2 的闭环"、两端同取锚）。
   * 分支点把线切成一串，它只能是某条线的**端点**，不可能在**内部**；这三条都是"畸形输入静默通过"，正是本类型要禁的那件事。
   *
   * <p>相邻性是**逐条走**验的（{@link #walkToTail}），故这里只管度数分布。
   */
  private void verifySimpleChain() {
    if (new HashSet<>(edges).size() != edges.size()) {
      throw new IllegalStateException("链不合法：有重复边: " + id);
    }
    Map<HexCoord, Integer> degree = new LinkedHashMap<>();
    for (EdgeRef e : edges) {
      degree.merge(e.a(), 1, Integer::sum);
      degree.merge(e.b(), 1, Integer::sum);
    }
    for (Map.Entry<HexCoord, Integer> entry : degree.entrySet()) {
      if (entry.getValue() > 2) {
        throw new IllegalStateException(
            "链不合法：格 "
                + entry.getKey()
                + " 上挂了 "
                + entry.getValue()
                + " 条边（分支点应把链断开，不得出现在线内部）: "
                + id);
      }
    }
  }

  /**
   * 链头候选：{@code edges.get(0)} 上**不接第二条边**的那一格；单边链取 {@code a()}。
   *
   * <p>这里**不判**相邻性：头两条边若真不相接，{@link #walkToTail} 走第二步时就会抛，且抛出的位置（第几条边）比这里更准。
   * 本方法只负责"从哪一格起步"，起步方向错了并不影响判定 —— 闭环时两端同取锚，开链时方向由 {@code edges} 的顺序定。
   */
  private HexCoord chainHead() {
    if (edges.size() == 1) {
      return edges.get(0).a();
    }
    EdgeRef first = edges.get(0);
    EdgeRef second = edges.get(1);
    boolean aShared = second.a().equals(first.a()) || second.b().equals(first.a());
    return aShared ? first.b() : first.a();
  }

  /**
   * 从 {@code head} 起步、**按 {@code edges} 的顺序**逐条走到尾，返回落脚的那一格。
   *
   * <p>★ 走的正是"链"的定义：每条边必须接得上当前游标，否则抛 —— **中段断开**与**次序错乱**都在这里响（原来的实现只看头尾两对，中段无人过问）。 重复边已由 {@link
   * #verifySimpleChain()} 挡下，故游标每步都在前进，循环必然终止。
   */
  private HexCoord walkToTail(HexCoord head) {
    HexCoord cursor = head;
    for (int i = 0; i < edges.size(); i++) {
      EdgeRef e = edges.get(i);
      if (e.a().equals(cursor)) {
        cursor = e.b();
      } else if (e.b().equals(cursor)) {
        cursor = e.a();
      } else {
        throw new IllegalStateException(
            "链不合法：第 " + (i + 1) + " 条边 " + e + " 接不上（游标在 " + cursor + "）: " + id);
      }
    }
    return cursor;
  }

  /** 闭环的锚：**规范序最小的格**（spec §5.2）。 */
  private HexCoord anchor() {
    return edges.stream()
        .flatMap(e -> Stream.of(e.a(), e.b()))
        .min(HexCoord::compareTo)
        .orElseThrow(() -> new IllegalStateException("空链没有锚: " + id));
  }
}
