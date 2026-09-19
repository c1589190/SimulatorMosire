package io.mosire.simos.map.resolve;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
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
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;

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
      return empty(); // 认领与否由返回值表达；未知命名空间抛是注册表的职责（R-13-b）
    }
    // 装配故障在解析任何 map: 地址时就炸，不留到某个查询路径上静默 miss
    GameMap map = mapOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return empty(); // 第 2 段必须是根主体 Entity(∅,·)；map:[4,3] / map:hex.4_3 在此列
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), mapAddress(mapId), "Map");
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（map:m1:hex.0_0:height）等更长地址 M2 不服务
    }
    return resolveEntity(map, mapId, segments.get(2));
  }

  /** 第 3 段按类型分派：Index 是 hex 的 Human 形式，Entity 按 kind 分派，其余（Property 等）不服务。 */
  private static QueryResult resolveEntity(GameMap map, String mapId, AddressSegment third) {
    if (third instanceof Index index) {
      if (index.coords().size() != 2) {
        return empty();
      }
      return resolveHex(map, mapId, new HexCoord(index.coords().get(0), index.coords().get(1)));
    }
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty(); // Property 段（冒号形式落成的 hex/0_0 两个 Property 在此列）与缺 kind 的实体
    }
    String name = entity.name();
    return switch (entity.kind().get()) {
      case "hex" -> resolveHex(map, mapId, HexCoord.parse(name)); // 名字非法抛它自己的 IAE，不包不吞
      case "region" -> resolveRegion(map, mapId, name);
      case "city" -> resolveCity(map, mapId, name);
      default -> empty(); // terra.Grass / conn.river.* 等合法地址，M2 不服务
    };
  }

  private static QueryResult resolveHex(GameMap map, String mapId, HexCoord hex) {
    if (!map.hexes().containsKey(hex)) {
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
      return empty();
    }
    return single(
        new SubjectId("map.city", id.value()), entityAddress(mapId, "city", id.value()), "City");
  }

  /**
   * 查某格所属的**全部**区域（解 L5）。**O(1)**——委托 {@link GameMap#regionIndex()}，不做线性扫描； 从属是多对多（M8-U1），
   * 重叠区域**全部保留**、按 {@link RegionId} 字典序。无归属返回**空列表**。
   */
  public static List<RegionId> regionOfHex(GameMap map, HexCoord hex) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(hex, "hex");
    return map.regionIndex().regionOf(hex);
  }

  /** 图只能从 map 模块切片拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static GameMap mapOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 map 模块切片——MapResolver 需要 MapSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
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
