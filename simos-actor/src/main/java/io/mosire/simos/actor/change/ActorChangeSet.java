package io.mosire.simos.actor.change;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.AssetHolding;
import io.mosire.simos.actor.model.AssetHoldingKey;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * actor 状态的变更集。<b>组件与 {@link ActorData} 的 record 组件一一对应</b>（当前 4 个：{@code meta} / {@code actors} /
 * {@code holdings} / {@code accounts}）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code ActorRoundTripTest} 的<b>反射枚举</b>把守——新增状态组件若不进变更集， 那个测试自动红。
 *
 * <p>★ <b>差异与重建的语义不在这里</b>：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} / {@code UnitChangeSet} / {@code SdChangeSet} / {@code
 * LedgerChangeSet} / {@code EconomyChangeSet} 共用同一份机制，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★★ <b>{@code meta} 是单值组件，用"单键表"投影进同一份机制</b>（照 {@code EconomyChangeSet}）：{@code FieldDelta}
 * 是对<b>表</b>的差异（键 → 值），而 {@code meta} 是 {@code Optional<ActorMeta>}。若为它另写一份"单值差异" 机制，就有了与 {@code
 * FieldDelta} 分叉的第二份实现。故把 {@code Optional} 投影成至多一行的表（键 = {@link #META_KEY}）， 再投影回来。语义是纯的：{@code 空 →
 * 有值} = {@code Upsert}、{@code 有值 → 空} = {@code Remove}、{@code 有值 → 另一个值} = {@code Upsert}。
 *
 * <p>★ <b>实现 util 的 {@code ChangeSet} 标记接口</b>：该接口已收窄为<b>标记接口</b>，实现它不带来任何新义务。
 *
 * <p>★ Task 5 的 {@code holdings} 走同一份机制：它是普通的"键 → 值"表（键类型 {@link AssetHoldingKey} 自带 {@code
 * toString()} + {@code parse} 这一对，见裁定 R-48-f），故 diff/rebuild 一字不用改 —— rebuild 的键解析器就是 {@link
 * AssetHoldingKey#parse}，与 {@link ActorRef} 那一路同款。★ Task 6 的 {@code accounts}（键类型 {@link
 * GoodsAccountKey}） 照同一形制：它的 {@code parse} 就是第四路键解析器。
 */
public record ActorChangeSet(
    FieldDelta<ActorMeta> meta,
    FieldDelta<Actor> actors,
    FieldDelta<AssetHolding> holdings,
    FieldDelta<GoodsAccount> accounts)
    implements ChangeSet {

  /** {@code meta} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String META_KEY = "meta";

  public ActorChangeSet {
    // ★ **旧档兼容**（照 LedgerChangeSet / EconomyChangeSet 的口径）：升级前落盘的这条变更集没有这些键时，
    //   Jackson 绑成 null ⇒ 缺省 = Unchanged（"一字未动"），**此处不抛** —— 读成 null 的话 isEmpty()
    //   与 apply 都会 NPE。方向是 fail-closed：旧档没提该组件，就是没动它。
    if (meta == null) {
      meta = new FieldDelta.Unchanged<>();
    }
    if (actors == null) {
      actors = new FieldDelta.Unchanged<>();
    }
    if (holdings == null) {
      holdings = new FieldDelta.Unchanged<>();
    }
    if (accounts == null) {
      accounts = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static ActorChangeSet between(ActorData base, ActorData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new ActorChangeSet(
        FieldDelta.diff(metaTable(base.meta()), metaTable(target.meta())),
        FieldDelta.diff(base.actors(), target.actors()),
        FieldDelta.diff(base.holdings(), target.holdings()),
        FieldDelta.diff(base.accounts(), target.accounts()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static ActorData apply(ActorChangeSet cs, ActorData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new ActorData(
        metaOf(FieldDelta.rebuild(metaTable(base.meta()), cs.meta(), Function.identity())),
        FieldDelta.rebuild(base.actors(), cs.actors(), ActorChangeSet::parseActorKey),
        FieldDelta.rebuild(base.holdings(), cs.holdings(), AssetHoldingKey::parse),
        FieldDelta.rebuild(base.accounts(), cs.accounts(), GoodsAccountKey::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(meta.changed() || actors.changed() || holdings.changed() || accounts.changed());
  }

  /** {@code Optional<ActorMeta>} → 至多一行的表（键固定为 {@link #META_KEY}）。 */
  private static Map<String, ActorMeta> metaTable(Optional<ActorMeta> meta) {
    return meta.map(value -> Map.of(META_KEY, value)).orElseGet(Map::of);
  }

  /** 上一条的逆：单键表 → {@code Optional}。空表 ⇒ 未激活。 */
  private static Optional<ActorMeta> metaOf(Map<String, ActorMeta> table) {
    return Optional.ofNullable(table.get(META_KEY));
  }

  /**
   * 规范串 → {@link ActorRef}：{@link FieldDelta#rebuild} 对<b>新出现的</b>键要一个 {@code Function<String, K>}，
   * 而键是 {@code toString()} 的产物（{@code "<KIND>:<id>"}）。
   *
   * <p>★★ <b>逆住在上游，别处不再有第二个拼写点</b>（S1 阶段 2 修复轮）：{@link ActorRef#parseCanonical(String)} 就是 {@code
   * toString()} 的逆，且与它同住 {@code ActorRef} 一个文件、共用同一个分隔符常量 —— 本仓"裸值 {@code toString()} + {@code
   * static parse}"三件套的形制。本切片<b>只委托</b>：既不知道分隔符是什么， 也不判断"按第几个切"。★ 这条委托让往返**跟着上游契约走** ——
   * 上游把规范串改了，本切片的往返测试当场红。
   *
   * <p>★ <b>为什么不调 {@link ActorRef#parse(String, String)}</b>：那个两参形式<b>不是</b> {@code toString()}
   * 的逆（{@code "UNIT:u-1"} 喂不回去），而裁定 R4 明令这个不对称<b>原封不动</b>。故补的是新增 surface {@code
   * parseCanonical}，两参签名一字未动。
   */
  private static ActorRef parseActorKey(String text) {
    return ActorRef.parseCanonical(text);
  }
}
