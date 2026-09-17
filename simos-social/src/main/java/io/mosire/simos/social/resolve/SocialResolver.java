package io.mosire.simos.social.resolve;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
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
 * {@code social:} 命名空间的地址解析器（M3 spec §3.5）。认三类地址：
 *
 * <ul>
 *   <li>{@code social:<mapId>} —— 该地图的社会切片根主体（第 2 段是根主体 {@code Entity(∅,·)}）
 *   <li>{@code social:<mapId>:hex.<q>_<r>} —— 该格的人口序列；无记录 ⇒ 空候选
 *   <li>{@code social:<mapId>:[q,r]} —— 上一条的 Human 形式（Index 段恰 2 元），canonical 一律输出 {@code hex.q_r}
 * </ul>
 *
 * <p>**空候选与抛的分工**（与 {@code MapResolver} 同款）：合法但本模块不服务（其它 kind、属性段、段数 &gt; 3、
 * 没有记录的格）一律空候选；**抛只有两处**——装配故障（state 里没有 social 切片 / 切片类型不对）与认领了的 kind 里**名字解析失败**（{@link
 * HexCoord#parse} 抛它自己的 IAE，不包不吞）。
 *
 * <p>★ **canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出**：M1 §3.4 的按需加引规则
 * 不在本类重实现。{@code mapId} **只回显、不校验**（{@code GameMap} 没有 id 字段，M2 遗留挂起项）。
 */
public final class SocialResolver implements Resolver {

  private static final String NAMESPACE = "social";

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
      return empty(); // 认领与否由返回值表达；未知命名空间抛是注册表的职责
    }
    // 装配故障在解析任何 social: 地址时就炸，不留到某个查询路径上静默 miss（先于段形状判定）
    SocialData data = dataOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return empty(); // 第 2 段必须是根主体 Entity(∅,·)；social:[4,3] / social:hex.4_3 在此列
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), rootAddress(mapId), "Social");
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（social:m1:hex.0_0:population）M3 不服务
    }
    AddressSegment third = segments.get(2);
    if (third instanceof Index index) {
      if (index.coords().size() != 2) {
        return empty();
      }
      return resolveHex(data, mapId, new HexCoord(index.coords().get(0), index.coords().get(1)));
    }
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty(); // Property 段与缺 kind 的实体不服务
    }
    return switch (entity.kind().get()) {
      case "hex" -> resolveHex(data, mapId, HexCoord.parse(entity.name())); // 名字非法抛它自己的 IAE
      default -> empty(); // city.c1 等合法地址，M3 不服务
    };
  }

  private static QueryResult resolveHex(SocialData data, String mapId, HexCoord hex) {
    if (!data.populations().containsKey(hex)) {
      return empty(); // 合法但不存在的格：空候选，不是错误
    }
    return single(
        new SubjectId("social.hex", hex.toString()),
        entityAddress(mapId, "hex", hex.toString()),
        "HexPopulation");
  }

  /** 切片只能从 social 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static SocialData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 social 模块切片——SocialResolver 需要 SocialSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  // ★ canonical 一律由 Address AST 构造后调 canonical() 产出（R13）：§3.4 的加引规则不许在这里手写重实现。

  private static Address rootAddress(String mapId) {
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
