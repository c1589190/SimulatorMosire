package io.mosire.simos.army.resolve;

import io.mosire.simos.army.ArmyAddresses;
import io.mosire.simos.army.ArmyData;
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
import org.slf4j.Logger;

/**
 * {@code army:} 命名空间的地址解析器（阶段 D1 / 用户设计 D-012，2026-10-02；形制照 {@code SdResolver} / {@code
 * ActorResolver}）。
 *
 * <p><b>canonical 形态</b>：{@code army:combat.<id>} —— 与 {@code sd:combat.<id>} 同款的两段形态（命名空间 + 根主体），
 * **不带 mapId**（army 切片不是逐地图的：它是"这个世界的交战记录表"）。canonical 一律由 {@link Address} AST 构造后 {@link
 * Address#canonical()} 产出，**不手拼**。
 *
 * <p>★ <b>认领范围</b>：只有 {@code army:combat.<id>}（段数恰 2、根主体 kind = {@code combat}）。其它段数 / 其它 kind
 * 一律空候选。★ <b>空候选与抛的分工</b>同 {@code SdResolver}：合法但不服务/不存在的 ⇒ 空候选；抛只有一处——装配故障（缺 army 切片、或切片类型不对）。
 *
 * <p>★ <b>为什么读侧还有一层可见性</b>：本解析器只回答"这个地址指向哪条记录"；交战记录**在地图上的可见性**由 app 的 {@code
 * ToolSupport.subjectVisible} 按记录所在格判（与 {@code map.city} 的"没有自己的资源路径 ⇒ 按格判"同款）。
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
    if (root.kind().isEmpty() || !ArmyAddresses.COMBAT_KIND.equals(root.kind().get())) {
      return empty();
    }
    CombatRecordId id = CombatRecordId.parse(root.name());
    if (!data.combats().containsKey(id)) {
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ARMY_RESOLVE_EMPTY",
                    ArmyLogSource.ARMY_RESOLVE,
                    "entityKind",
                    "combat",
                    "entity",
                    root.name()));
      }
      return empty(); // 合法但不存在的记录：空候选，不是错误
    }
    return single(
        new SubjectId(NAMESPACE, "combat." + root.name()),
        ArmyAddresses.combat(id),
        "CombatRecord");
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
