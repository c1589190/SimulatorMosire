package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.pathway.PathwayGroup;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * 连通性**组定义**的操作面（WebUI 阶段修复 T3，spec §三.6）：给词表**注册一个新组**，返回变更集。
 *
 * <p>★ `map.SetEdge` 的合法 {@code kind} = 已注册组集合（见 {@link EdgeOperations#setEdge}）⇒
 * 本操作是"加一种通路"的**唯一入口**： 注册 {@code canal} 之后 `map.SetEdge{kind:"canal"}` 才成立。默认两组（{@code
 * river}/{@code road}）由 {@link PathwayGroup#defaults()} 供给，不经本操作。
 *
 * <p>★ **重复注册 fail-closed**：组 id 已存在 ⇒ 拒绝（{@code "组已存在: …"}）。静默覆盖组定义会让"改色/改 schema"与"新增组"从同一入口混进来，
 * 且旧边上的标注会与组定义脱节。改名 / 改定义不在本阶段范围。
 *
 * <p>★ **纯函数**：只读 {@code base}，变更集走 {@link MapChangeSet#between}（只有 {@code pathwayGroups} 可能非
 * {@code Unchanged}）。
 */
public final class PathwayGroupOperations {

  private static final Logger LOG = MapLog.edit();

  private PathwayGroupOperations() {}

  /**
   * 把 {@code group} 注册进词表，返回变更集。
   *
   * @param base 现图（只读；既有组取自它）
   * @param group 组定义（id/name/color 的构造期校验由 {@link PathwayGroup} 自己抛）
   * @return 只有 {@code pathwayGroups} 可能非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException 组 id 已存在
   */
  public static MapChangeSet register(GameMap base, PathwayGroup group) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(group, "group");
    if (base.pathwayGroups().containsKey(group.id())) {
      throw new IllegalArgumentException("组已存在: " + group.id());
    }
    Map<String, PathwayGroup> next = new LinkedHashMap<>(base.pathwayGroups());
    next.put(group.id(), group);
    LOG.info("event=MAP_PATHWAY_GROUP_REGISTERED id={} name={}", group.id(), group.name());
    return MapChangeSet.between(base, base.withPathwayGroups(next));
  }
}
