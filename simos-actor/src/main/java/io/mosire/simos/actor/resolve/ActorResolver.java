package io.mosire.simos.actor.resolve;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
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
 * {@code actor:} 命名空间的地址解析器（S1 spec §三；形制照 {@code EconomyResolver} / {@code SocialResolver}）。认两类主体
 * —— <b>与 {@link ActorData} 的两张带记录的表一一对应</b>（{@code meta} 是单值组件，不是表）：
 *
 * <ul>
 *   <li>{@code actor:<mapId>} —— 该地图的 actor 切片根主体（第 2 段是根主体 {@code Entity(∅,·)}）
 *   <li>{@code actor:<mapId>:actor.<KIND>.<id>} —— 主体（类型名 {@code "Actor"}）；无记录 ⇒ 空候选
 *   <li>{@code actor:<mapId>:goods.<key>} —— 商品库存（类型名 {@code "GoodsAccount"}）；无记录 ⇒ 空候选
 * </ul>
 *
 * <p>★★ <b>2026-09-27 裁定 S3</b>：产权（{@code holding.<key>} / 类型名 {@code "AssetHolding"}）整块退役，本解析器
 * <b>不再认领 {@code holding} 这个 kind</b> —— 它落进"其它 kind ⇒ 空候选"那一档，不再有地址解析。
 *
 * <p>★★ <b>库存那一类的 {@code <key>} 就是它聚合键的规范串</b>（{@code <owner>|<location>}）—— 它的逆住在那 个类型<b>自己的</b>
 * {@code parse} 里（裁定 R-48-f：格式的拼写与它的逆只许有一处）， 本类<b>只委托、不复述格式</b>，连"按第几个接缝切"都不判断。名字里含 {@code :} /
 * {@code [} / {@code ]} 时（{@code ActorRef} 的规范串就含 {@code :}）<b>需要在地址里加引</b>，canonical 形式由 {@link
 * Address} 的 AST 按 §3.4 的按需加引规则产出。
 *
 * <p>★ <b>主体的地址拆成 {@code <KIND>.<id>} 两半</b>（而不是把整条规范串塞进名字）：地址 AST 的实体段本来就长成 {@code kind.name}，而
 * {@code ActorRef} 的两参 {@code parse(kindText, id)} <b>正是</b>这个形状 —— 于是词表外的种类 （{@code
 * actor:Map1:actor.MANOR.x}）当场抛在 {@code ActorKind.parse} 里，消息自带合法值清单。
 *
 * <p><b>空候选与抛的分工</b>（与 {@code EconomyResolver}/{@code LedgerResolver} 同款）：合法但本模块不服务（其它 kind、属性段、 段数
 * &gt; 3、Index 段、没有记录的主体/库存）一律空候选；<b>抛只有两处</b>——装配故障（state 里没有 actor 切片 / 切片类型不对）与<b>认领了的 kind</b>
 * 里<b>名字解析失败</b>（{@link ActorRef#parse} 等抛它自己的 IAE，不包不吞）。
 *
 * <p>★ <b>canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出</b>（R13）：§3.4 的加引规则不在本类重实现。
 * {@code mapId} <b>只回显、不校验</b>（与 social/ledger/economy 同款：地图 ID 没有本切片内的判据）。
 */
public final class ActorResolver implements Resolver {

  private static final String NAMESPACE = "actor";

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
    // 装配故障在解析任何 actor: 地址时就炸，不留到某个查询路径上静默 miss（先于段形状判定）
    ActorData data = dataOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return empty(); // 第 2 段必须是根主体 Entity(∅,·)
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), rootAddress(mapId), "ActorData");
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（actor:m1:actor.ESTATE.farm@0_0:label）本切片不服务
    }
    AddressSegment third = segments.get(2);
    // Index 段（actor:m1:[0,0]）与缺 kind 的实体都不服务：本切片没有"位置型"主体。
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty();
    }
    return switch (entity.kind().get()) {
      case "actor" -> resolveActor(data, mapId, entity.name());
      case "goods" -> resolveGoods(data, mapId, entity.name());
      default -> empty(); // 其它 kind（含已退役的 holding）的合法地址，本模块不服务
    };
  }

  /**
   * 主体：名字是 {@code <KIND>.<id>}（{@link ActorRef#parse(String, String)} 的两参形状）。
   *
   * <p>★ 两半都交给<b>上游</b>判：{@code kind} 走 {@code ActorKind} 的词表（词表外即抛并列出合法值），{@code id} 非空白
   * ——本类<b>不复制</b>这些判据（{@code ActorRef} 的构造期守卫是那一处的唯一真相）。
   */
  private static QueryResult resolveActor(ActorData data, String mapId, String name) {
    int dot = name.indexOf('.');
    if (dot <= 0 || dot == name.length() - 1) {
      throw new IllegalArgumentException("非法主体地址名（应为 <KIND>.<id>）: " + name);
    }
    ActorRef ref = ActorRef.parse(name.substring(0, dot), name.substring(dot + 1));
    if (!data.actors().containsKey(ref)) {
      return empty(); // 合法但不存在的主体：空候选，不是错误
    }
    return single(
        new SubjectId("actor.actor", ref.toString()),
        entityAddress(mapId, "actor", ref.kind().name() + "." + ref.id()),
        "Actor");
  }

  private static QueryResult resolveGoods(ActorData data, String mapId, String name) {
    GoodsAccountKey key = GoodsAccountKey.parse(name);
    if (!data.accounts().containsKey(key)) {
      return empty();
    }
    return single(
        new SubjectId("actor.goods", key.toString()),
        entityAddress(mapId, "goods", key.toString()),
        "GoodsAccount");
  }

  /** 切片只能从 actor 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static ActorData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 actor 模块切片——ActorResolver 需要 ActorSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalArgumentException(
          "actor 模块切片不是 ActorSnapshot：" + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
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
