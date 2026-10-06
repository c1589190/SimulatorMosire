package io.mosire.simos.map.resolve;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Index;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * {@code map:} 命名空间的地址解析器（M2 Task 13）。认四类地址（M1 spec §3.2/§3.6 的冻结样例形态）：
 *
 * <ul>
 *   <li>{@code map:<mapId>} —— 整张地图（第 2 段是该命名空间的**根主体** {@code Entity(∅,·)}，总纲 §4.4）
 *   <li>{@code map:<mapId>:hex.<q>_<r>} —— 单个格；{@code map:<mapId>:[q,r]} 是它的 Human 形式 （Index 段恰好 2
 *       元），canonical 一律输出 {@code hex.<q>_<r>}（总纲 §4.2：Human 进、canonical 出）
 *   <li>{@code map:<mapId>:region.<id>} —— 区域
 *   <li>{@code map:<mapId>:city.<id>} —— 城市
 * </ul>
 *
 * <p>★ **只认 {@code kind.name}（点号）形式**：段间只用 {@code :}、{@code .} 只在段内（总纲 §4.3）。 故 {@code
 * map:m1:hex:0_0} 里的 {@code hex} 与 {@code 0_0} 是两个 **Property** 段（第 ≥3 段的裸词一律判 Property，M1
 * §3.2），不是格地址——落进"合法但没人服务"的空候选。
 *
 * <p>**空候选与抛的分工**：合法地址但本模块不服务（其它 kind、Property 段、缺 kind 的实体、Index 元数 ≠ 2、 段数 &gt;
 * 3、图里不存在的格/区域/城市）一律**空候选、不抛**——空列表 = 没有候选，不是错误（{@link QueryResult}）。非 {@code map}
 * 命名空间也返回空候选："认领"与否由返回值表达，未知命名空间抛是 {@code ResolverRegistry} 的职责，本解析器不重复。**抛只有两处**：装配故障（state 里没有
 * map 切片 / 切片不是 {@link MapSnapshot}——与注册表"抛，不兜底"同口径）与认领了的 kind **名字解析失败**（{@link
 * HexCoord#parse(String)} 等抛它们自己的 IAE，不包不吞、不改消息）。
 *
 * <p>★ **canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出**：M1 §3.4 的四条按需加引规则
 * 不在本类重实现，mapId 含 {@code :} 时的引号因此自动正确。mapId **只回显、不校验**——{@link GameMap} 没有 id
 * 字段（挂起项：地图有身份字段后再收紧）。
 */
public final class MapResolver implements Resolver {

  private static final String NAMESPACE = "map";

  private static final Logger LOG = MapLog.resolve();

  /** 空候选的 DEBUG 诊断（判据/为什么）：只在 DEBUG 打开时构造字段；不逐次记成功的普通查询。 */
  private static void debugEmpty(String reason, Object... keyValues) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Object[] fields = new Object[keyValues.length + 2];
    fields[0] = "reason";
    fields[1] = reason;
    System.arraycopy(keyValues, 0, fields, 2, keyValues.length);
    EventLog.channel(LOG).debug(LogEvent.of("MAP_RESOLVE_EMPTY", MapLogSource.MAP_RESOLVE, fields));
  }

  /** 装配故障的 DEBUG 诊断（抛之前的判据说明；异常本身照旧抛，不吞不改）。 */
  private static void debugAssembly(String reason, Object... keyValues) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Object[] fields = new Object[keyValues.length + 2];
    fields[0] = "reason";
    fields[1] = reason;
    System.arraycopy(keyValues, 0, fields, 2, keyValues.length);
    EventLog.channel(LOG)
        .debug(LogEvent.of("MAP_RESOLVE_ASSEMBLY_FAILED", MapLogSource.MAP_RESOLVE, fields));
  }

  /** 本解析器负责的命名空间（注册表按它建键，与地址首段一致）。 */
  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      debugEmpty("命名空间不是 map", "namespace", address.namespace());
      return empty(); // 认领与否由返回值表达；未知命名空间抛是注册表的职责（R-13-b）
    }
    // 装配故障在解析任何 map: 地址时就炸，不留到某个查询路径上静默 miss
    GameMap map = mapOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      debugEmpty("第 2 段必须是根主体 Entity(∅,·)", "segments", segments.size());
      return empty(); // 第 2 段必须是根主体 Entity(∅,·)；map:[4,3] / map:hex.4_3 在此列
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), mapAddress(mapId), "Map");
    }
    if (segments.size() > 3) {
      debugEmpty("段数大于 3，M2 不服务", "mapId", mapId, "segments", segments.size());
      return empty(); // 属性访问（map:m1:hex.0_0:height）等更长地址 M2 不服务
    }
    return resolveEntity(map, mapId, segments.get(2));
  }

  /** 第 3 段按类型分派：Index 是 hex 的 Human 形式，Entity 按 kind 分派，其余（Property 等）不服务。 */
  private static QueryResult resolveEntity(GameMap map, String mapId, AddressSegment third) {
    if (third instanceof Index index) {
      if (index.coords().size() != 2) {
        debugEmpty("Index 元数不是 2", "mapId", mapId, "coords", index.coords().size());
        return empty();
      }
      return resolveHex(map, mapId, new HexCoord(index.coords().get(0), index.coords().get(1)));
    }
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      debugEmpty("第 3 段不是带 kind 的 Entity", "mapId", mapId, "segment", String.valueOf(third));
      return empty(); // Property 段（冒号形式落成的 hex/0_0 两个 Property 在此列）与缺 kind 的实体
    }
    String name = entity.name();
    return switch (entity.kind().get()) {
      case "hex" -> resolveHex(map, mapId, HexCoord.parse(name)); // 名字非法抛它自己的 IAE，不包不吞
      case "region" -> resolveRegion(map, mapId, name);
      case "city" -> resolveCity(map, mapId, name);
      default -> {
        debugEmpty("kind 不服务", "mapId", mapId, "kind", entity.kind().get());
        yield empty(); // terra.Grass / conn.river.* 等合法地址，M2 不服务
      }
    };
  }

  private static QueryResult resolveHex(GameMap map, String mapId, HexCoord hex) {
    if (!map.hexes().containsKey(hex)) {
      debugEmpty("hex 不存在", "mapId", mapId, "hex", hex);
      return empty(); // 合法但不存在的坐标：空候选，不是错误
    }
    return single(
        new SubjectId("map.hex", hex.toString()),
        entityAddress(mapId, "hex", hex.toString()),
        "Hex");
  }

  private static QueryResult resolveRegion(GameMap map, String mapId, String name) {
    RegionId id = RegionId.parse(name);
    if (!map.regions().containsKey(id)) {
      debugEmpty("region 不存在", "mapId", mapId, "region", id.value());
      return empty();
    }
    return single(
        new SubjectId("map.region", id.value()),
        entityAddress(mapId, "region", id.value()),
        "Region");
  }

  private static QueryResult resolveCity(GameMap map, String mapId, String name) {
    CityId id = CityId.parse(name);
    if (!map.cities().containsKey(id)) {
      debugEmpty("city 不存在", "mapId", mapId, "city", id.value());
      return empty();
    }
    return single(
        new SubjectId("map.city", id.value()), entityAddress(mapId, "city", id.value()), "City");
  }

  /**
   * 查某格所属的**全部**区域。成员由 {@link GameMap#regionIndex()} 给出（**一次 {@code Map.get}**，解 L5），但**次序不取字典序**：
   * 按 {@link GameMap#regions()} 的**定义序**（插入序）排列 —— **后定义者在后**，故列表的**末位**就是该格的**最顶层区域**。
   *
   * <p>★ **V3 取代 M8-Q6**：{@code /api/map/hex} 的 {@code regions} 曾是 {@link RegionId}
   * 字典序，现为定义序。从属关系仍是多对多 （M8-U1：重叠**全部保留**、不覆盖不报错），本方法只改**排列顺序**、不改集合（{@link RegionIndex}
   * 因此保持原样——它只管从属、 **不承载层次**）。
   *
   * <p>★ **判别力**：定义序与字典序在夹具里**故意分叉**（{@code MapResolverTest}：先插入 {@code r2} 再 {@code r1} ⇒ 定义序
   * {@code [r2, r1]}、字典序 {@code [r1, r2]}）⇒ "退回字典序"会让末位断言红。无归属返回**空列表**。
   */
  public static List<RegionId> regionOfHex(GameMap map, HexCoord hex) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(hex, "hex");
    List<RegionId> owners = map.regionIndex().regionOf(hex);
    if (owners.size() <= 1) {
      return owners;
    }
    // 按 GameMap.regions 的插入序过滤出本格的从属者 ⇒ 定义序；末位即最顶层。
    Set<RegionId> ownerSet = new HashSet<>(owners);
    List<RegionId> ordered = new ArrayList<>(owners.size());
    for (RegionId id : map.regions().keySet()) {
      if (ownerSet.contains(id)) {
        ordered.add(id);
      }
    }
    return List.copyOf(ordered);
  }

  /** 图只能从 map 模块切片拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static GameMap mapOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () -> {
                  debugAssembly("状态里没有 map 模块切片");
                  return new IllegalArgumentException(
                      "状态里没有 map 模块切片——MapResolver 需要 MapSnapshot（装配故障，不是\"没有候选\"）");
                });
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      debugAssembly("map 模块切片类型不是 MapSnapshot", "type", snapshot.getClass().getName());
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  // ★ canonical 一律由 Address AST 构造后调 canonical() 产出（R-13-g）：§3.4 的加引规则不许在这里手写重实现。

  private static Address mapAddress(String mapId) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(mapId)));
  }

  private static Address entityAddress(String mapId, String kind, String localId) {
    return new Address(
        List.of(new Namespace(NAMESPACE), Entity.of(mapId), Entity.of(kind, localId)));
  }

  private static QueryResult single(SubjectId id, Address canonicalAddress, String typeName) {
    return new QueryResult(
        List.of(new ResolvedSubject(id, canonicalAddress.canonical(), typeName)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }
}
