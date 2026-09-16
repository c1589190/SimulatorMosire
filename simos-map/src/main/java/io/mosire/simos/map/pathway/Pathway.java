package io.mosire.simos.map.pathway;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 一条**极大简单链**：两端是端点或分支点。
 *
 * <p>★ **分支点即端点** —— 度数 &gt;= 3 的格是分支点，线在分支处断开 ⇒ "分支是独立的线"落地为"分支点把线切成一串"。
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
    if (edges.isEmpty()) {
      throw new IllegalStateException("空链没有端点: " + id);
    }
    if (edges.size() == 1) {
      return edges.get(0).a();
    }
    if (isClosed()) {
      return anchor();
    }
    return freeEnd(edges.get(0), edges.get(1));
  }

  /** 链尾的端点。闭环时与 {@link #start()} 同取锚 —— 于是"两端相等"就是"这条线是闭环"。 */
  public HexCoord end() {
    if (edges.isEmpty()) {
      throw new IllegalStateException("空链没有端点: " + id);
    }
    if (edges.size() == 1) {
      return edges.get(0).b();
    }
    if (isClosed()) {
      return anchor();
    }
    return freeEnd(edges.get(edges.size() - 1), edges.get(edges.size() - 2));
  }

  /** 边数。**不是格数**。 */
  public int length() {
    return edges.size();
  }

  /** 链上每个格的度数都恰为 2 ⇒ 没有端点 ⇒ 闭环（spec §5.2 的"无端点"情形）。 */
  private boolean isClosed() {
    Map<HexCoord, Integer> degree = new LinkedHashMap<>();
    for (EdgeRef e : edges) {
      degree.merge(e.a(), 1, Integer::sum);
      degree.merge(e.b(), 1, Integer::sum);
    }
    return degree.values().stream().allMatch(d -> d == 2);
  }

  /** 闭环的锚：**规范序最小的格**（spec §5.2）。 */
  private HexCoord anchor() {
    return edges.stream()
        .flatMap(e -> Stream.of(e.a(), e.b()))
        .min(HexCoord::compareTo)
        .orElseThrow(() -> new IllegalStateException("空链没有锚: " + id));
  }

  /**
   * {@code e} 相对邻边 {@code n} 的**自由端**：{@code e} 的两格中不被 {@code n} 占用的那一格。
   *
   * <p>两格**都被**占用只有一种来路：{@code e} 与 {@code n} 是完全相同的两条边。整条链都退化成闭环时，{@link #isClosed()}
   * 已在前面把它拦下；其余情形走到这里即抛。两格**都不**被占用说明链是断的（相邻两条边不相接），同样抛：本类型承诺的是"极大简单链"， 断链不是它。
   */
  private static HexCoord freeEnd(EdgeRef e, EdgeRef n) {
    boolean aShared = n.a().equals(e.a()) || n.b().equals(e.a());
    boolean bShared = n.a().equals(e.b()) || n.b().equals(e.b());
    if (aShared == bShared) {
      throw new IllegalStateException("链不合法：相邻两条边 " + e + " 与 " + n + (aShared ? " 完全重合" : " 不相接"));
    }
    return aShared ? e.b() : e.a();
  }
}
