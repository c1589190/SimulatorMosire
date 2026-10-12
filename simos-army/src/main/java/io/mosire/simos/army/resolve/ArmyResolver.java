package io.mosire.simos.army.resolve;

import io.mosire.simos.army.ArmyAddresses;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * {@code army:} 命名空间的地址解析器（阶段 D1 / 用户设计 D-012，2026-10-02；形制照 {@code SdResolver} / {@code
 * ActorResolver}）。
 *
 * <p><b>canonical 形态（两支）</b>：
 *
 * <ul>
 *   <li>{@code army:army.<id>} —— <b>军队身份</b>（2026-10-12 批次 3d-1 新增：{@code armies} 表随军队身份搬进 army，
 *       sd 侧的 {@code sd:army.<id>} 地址族随之整支删除 ⇒ 身份的读面落在这里，见 {@link ArmyAddresses#ARMY_KIND}）；
 *   <li>{@code army:combat.<id>} —— 交战记录（与 {@code sd:combat.<id>} 同款的两段形态）。
 * </ul>
 *
 * 两者都**不带 mapId**（army 切片不是逐地图的：它是"这个世界的军队身份与交战记录表"）。canonical 一律由 {@link Address} AST 构造后 {@link
 * Address#canonical()} 产出，**不手拼**。
 *
 * <p>★ <b>认领范围</b>：只有这两支（段数恰 2、根主体 kind ∈ {@code army|combat}）。其它段数 / 其它 kind 一律空候选。★
 * <b>空候选与抛的分工</b>同 {@code SdResolver}：合法但不服务/不存在的 ⇒ 空候选；抛只有一处——装配故障（缺 army 切片、或切片类型不对）。
 *
 * <p>★ <b>为什么读侧还有一层可见性</b>：本解析器只回答"这个地址指向哪条记录/哪个军队"；交战记录**在地图上的可见性**由 app 的 {@code
 * ToolSupport.subjectVisible} 按记录所在格判（与 {@code map.city} 的"没有自己的资源路径 ⇒ 按格判"同款）。★ <b>军队身份同理</b>：
 * 它也没有自己的资源路径 ⇒ 可见性按<b>它的 root 单位所在格</b>判（{@code ToolSupport} 侧的口径）。
 */
public final class ArmyResolver implements Resolver {

  private static final Logger LOG = ArmyLog.resolve();

  private static final String NAMESPACE = ArmyAddresses.NAMESPACE;

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty();
    }
    ArmyData data = dataOf(ctx); // 装配故障在解析任何 army: 地址时就炸，不留到某个查询路径上静默 miss
    List<AddressSegment> segments = address.segments();
    if (segments.size() != 2 || !(segments.get(1) instanceof Entity root)) {
      return empty();
    }
    if (root.kind().isEmpty()) {
      return empty();
    }
    String kind = root.kind().get();
    if (ArmyAddresses.ARMY_KIND.equals(kind)) {
      return resolveArmy(data, root.name());
    }
    if (ArmyAddresses.COMBAT_KIND.equals(kind)) {
      return resolveCombat(data, root.name());
    }
    return empty();
  }

  /** {@code army:army.<id>}：军队身份的根主体（存在性 = {@code armies} 表里有这个键）。 */
  private static QueryResult resolveArmy(ArmyData data, String name) {
    Optional<ArmyId> id = parseArmy(name);
    if (id.isEmpty() || !data.armies().containsKey(id.get())) {
      logEmpty(ArmyAddresses.ARMY_KIND, name);
      return empty(); // 合法但不存在的身份：空候选，不是错误
    }
    return single(
        new SubjectId(NAMESPACE, ArmyAddresses.ARMY_KIND + "." + name),
        ArmyAddresses.army(id.get()),
        "Army");
  }

  /** {@code army:combat.<id>}：交战记录的根主体（存在性 = {@code combats} 表里有这个键）。 */
  private static QueryResult resolveCombat(ArmyData data, String name) {
    // ★ `safe(...)` 不是装饰：Entity.name 只保证非 null（可为空串，见 Entity 的构造期），而 id 的 parse 对空白**抛**；
    //   本解析器的契约是"合法但不服务/不存在的 ⇒ 空候选"，故解析失败必须折成空候选（迁入前的 SdResolver 同款口径）。
    Optional<CombatRecordId> id = parseCombat(name);
    if (id.isEmpty() || !data.combats().containsKey(id.get())) {
      logEmpty(ArmyAddresses.COMBAT_KIND, name);
      return empty(); // 合法但不存在的记录：空候选，不是错误
    }
    return single(
        new SubjectId(NAMESPACE, ArmyAddresses.COMBAT_KIND + "." + name),
        ArmyAddresses.combat(id.get()),
        "CombatRecord");
  }

  /** 空候选的 DEBUG 行（只记 kind 与实体名——都是领域 id，不是载荷原文）。 */
  private static void logEmpty(String entityKind, String entity) {
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "ARMY_RESOLVE_EMPTY",
                  ArmyLogSource.ARMY_RESOLVE,
                  "entityKind",
                  entityKind,
                  "entity",
                  entity));
    }
  }

  private static Optional<ArmyId> parseArmy(String text) {
    try {
      return Optional.of(ArmyId.parse(text));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<CombatRecordId> parseCombat(String text) {
    try {
      return Optional.of(CombatRecordId.parse(text));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** 切片只能从 army 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static ArmyData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 army 模块切片——ArmyResolver 需要 ArmySnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalArgumentException(
          "army 模块切片不是 ArmySnapshot：" + snapshot.getClass().getName());
    }
    return armySnapshot.data();
  }

  private static QueryResult single(SubjectId id, Address canonical, String typeName) {
    return new QueryResult(List.of(new ResolvedSubject(id, canonical.canonical(), typeName)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }
}
