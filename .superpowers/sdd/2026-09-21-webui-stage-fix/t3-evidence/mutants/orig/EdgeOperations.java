package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 连通性操作面（M8 spec §二，S4 的"语义化命令"）：**规则放领域模块**，Core 只转发信封（ADR-1 / 铁律 4）。
 *
 * <p>★ **每个操作都是纯函数**（只读 {@code base}，产出走 {@link MapChangeSet}）；变更集**唯一**的生产路径是 {@link
 * MapChangeSet#between(GameMap, GameMap)}。
 *
 * <p>★★ **合并语义显式**（M8 spec §二 / Q2；M2 挂起项"重建河流会整份覆盖 {@code EdgeTags}"的账在这里结）：{@code mode}
 * **没有默认值**——调用方必须显式声明 {@code replace}（整份覆盖该 kind）还是 {@code merge}（只加不删）。 {@code merge} 对已有 tag
 * **只补不删**，`putIfAbsent` 语义使该边上**该 kind 的既有 props 一字不动**。
 *
 * <p>★ **kind 是 {@link EdgeTags} 的 pathway 键**（真档里就是 {@code "river"}/{@code "road"} 两个组 id）。 ★★
 * **词表不再是代码常量**（spec §三.6 的 WebUI 阶段修复，T3）：合法 {@code kind} = **已注册的 {@link
 * io.mosire.simos.map.pathway.PathwayGroup} 集合**（{@code base.pathwayGroups().keySet()}），大小写不敏感；未注册
 * ⇒ 仍 fail-closed 拒绝。默认的 {@code river}/{@code road} 由 {@link
 * io.mosire.simos.map.pathway.PathwayGroup#defaults()} 供给，自定义组经 {@code map.RegisterPathwayGroup}
 * 注册——加一种通路 **只需发一条注册命令、不再改代码**。{@code replace} 覆盖**整张图**上该 kind 的标注：payload 之外的边同样会被摘掉该
 * kind（那正是"整份覆盖"），该 kind 之外的标注（如 road 之于 river 的 replace）**一字不动**。
 *
 * <p>★ **逐组件独立性**：只换 {@code edges} 组件（{@link GameMap#withEdges}），故 {@code hexes}（高度）、{@code
 * terrainBlocks} 与其余组件恒 {@code Unchanged}——由 {@link MapChangeSet#between} 逐组件比较保证。
 */
public final class EdgeOperations {

  /** 显式模式：整份覆盖该 kind。 */
  private static final String REPLACE = "replace";

  /** 显式模式：只加不删。 */
  private static final String MERGE = "merge";

  private EdgeOperations() {}

  /**
   * 给 {@code edges} 这批边标注 {@code kind}，返回变更集。
   *
   * <p>校验次序（都在算出任何结果之前）：{@code kind} 已注册组 → {@code mode} 词表 → {@code edges} 非空 → 每条边的两端都在图上。
   *
   * @param base 现图（只读；原有标注与**合法 kind 集合**都取自它）
   * @param kind 连通性类型；须是 {@code base.pathwayGroups()} 里已注册的组 id，大小写不敏感
   * @param edges 要标注的边；**不得为空**，两端都必须在 {@code base.hexes()} 里
   * @param mode {@code replace} 或 {@code merge}（大小写不敏感）；**必须显式给**
   * @return 只有 {@code edges} 可能非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException kind 未注册 / mode 非法 / 空边集 / 端点不在图上
   */
  public static MapChangeSet setEdge(GameMap base, String kind, Set<EdgeRef> edges, String mode) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(edges, "edges");
    Objects.requireNonNull(mode, "mode");
    String tagKey = resolveKind(base, kind);
    String operation = normalizeMode(mode);
    if (edges.isEmpty()) {
      throw new IllegalArgumentException("edges 不得为空：一条 map.SetEdge 至少要标注一条边");
    }
    List<EdgeRef> ordered = new ArrayList<>(edges);
    ordered.sort(Comparator.naturalOrder());
    for (EdgeRef edge : ordered) {
      if (!base.hexes().containsKey(edge.a()) || !base.hexes().containsKey(edge.b())) {
        throw new IllegalArgumentException("边的端点不在图上: " + edge);
      }
    }
    Map<EdgeRef, EdgeTags> next = new LinkedHashMap<>(base.edges());
    if (REPLACE.equals(operation)) {
      // 整份覆盖：先摘掉全图该 kind 的标注（只剩空标注的边从 edges 组件里消失），再给 payload 的边补上。
      for (EdgeRef edge : new ArrayList<>(next.keySet())) {
        EdgeTags stripped = withoutTag(next.get(edge), tagKey);
        if (stripped == null) {
          next.remove(edge);
        } else {
          next.put(edge, stripped);
        }
      }
    }
    for (EdgeRef edge : ordered) {
      EdgeTags existing = next.get(edge);
      Map<String, Map<String, Object>> byPathway =
          existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.byPathway());
      byPathway.putIfAbsent(tagKey, Map.of()); // merge 保留该 kind 既有 props；replace 此刻已摘空
      next.put(edge, new EdgeTags(byPathway));
    }
    return MapChangeSet.between(base, base.withEdges(next));
  }

  /** 摘掉一条边上的某个 kind 标注；原本没有 ⇒ 原样返回；摘完别无标注 ⇒ {@code null}（该边不再进 edges 组件）。 */
  private static EdgeTags withoutTag(EdgeTags tags, String tagKey) {
    if (!tags.byPathway().containsKey(tagKey)) {
      return tags;
    }
    Map<String, Map<String, Object>> byPathway = new LinkedHashMap<>(tags.byPathway());
    byPathway.remove(tagKey);
    return byPathway.isEmpty() ? null : new EdgeTags(byPathway);
  }

  /**
   * ★ **词表 = 状态里已注册的组集合**（spec §三.6）：大小写不敏感地匹配 {@code base.pathwayGroups().keySet()}，返回 **组 id
   * 原文**（tag 键必须与注册的组 id 逐字一致，否则边上的标注与组定义脱节）。未注册 ⇒ fail-closed。
   *
   * <p>★ 不再有硬编码的 {@code Set.of("river","road")}：默认两组由 {@link
   * io.mosire.simos.map.pathway.PathwayGroup#defaults()} 注册，自定义组由 {@code map.RegisterPathwayGroup}
   * 注册。
   */
  private static String resolveKind(GameMap base, String kind) {
    String normalized = kind.toLowerCase(Locale.ROOT);
    for (String registered : base.pathwayGroups().keySet()) {
      if (registered.toLowerCase(Locale.ROOT).equals(normalized)) {
        return registered;
      }
    }
    throw new IllegalArgumentException("未知连通性类型: " + kind);
  }

  /** 模式**必须显式且合法**（缺字段由载荷层挡下；本层挡非法值），大小写不敏感。 */
  private static String normalizeMode(String mode) {
    String normalized = mode.toLowerCase(Locale.ROOT);
    if (!REPLACE.equals(normalized) && !MERGE.equals(normalized)) {
      throw new IllegalArgumentException("未知 mode: " + mode + "（只能是 replace 或 merge）");
    }
    return normalized;
  }
}
