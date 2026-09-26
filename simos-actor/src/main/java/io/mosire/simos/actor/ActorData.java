package io.mosire.simos.actor;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * actor 切片的完整状态树（S1 spec §三）：激活元信息 + 主体表。
 *
 * <p>★★ <b>{@code meta} 为空 {@code Optional} = 本世界尚未落 actor 切片</b>（同 {@code EconomyData} 的口径）。空快照 ≠
 * 已激活。
 *
 * <p>★★ <b>本任务的状态树只有两件</b>（控制方按计划"增量表"的裁定）：{@code meta} 与 {@code actors}；{@code holdings}（产权）由 Task
 * 5 加、{@code accounts}（商品余额）由 Task 6 加 —— 每加一张，同一个提交里同步改本 record 的组件、 {@link
 * io.mosire.simos.actor.change.ActorChangeSet} 的字段 delta 与 {@code ActorRoundTripTest} 的往返断言。★
 * 这样每个任务<b>自身可编译、可测、可评审</b>（不让本任务去引用还不存在的类型）。
 *
 * <p>★★ <b>为什么资产与库存不在这里</b>（spec §2.3 L109、§三 L283）："资产是 Actor <b>拥有的关系</b>，不是 Actor <b>本体的一部分</b>"
 * —— 故它们**不进 {@link Actor}**，而是各自独立成表（Task 5 / Task 6），键<b>从值派生</b>。若哪天有人把库存同时写进 {@code Actor}
 * 本体，spec §三 L283 的禁令就被绕过 —— 那条禁令由 {@code ActorRoundTripTest} 的反射断言把守。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code EconomyData} 的"classes 的每个键必须等于其 {@code ClassRow.key()}"） ：{@code
 * actors} 的每个键必须等于其 {@link Actor#ref()}。否则同一份身份就有两处可能不一致的记录。
 *
 * <p>★★ <b>缺键 = 空</b>（照 {@code EconomyData} 的旧档兼容口径）：两个组件在本切片都是新引入的，故 Jackson 绑成 null 时一律收成空表 /
 * 未激活，<b>此处不抛</b> —— 抛了等于"旧档全部读不回来"。方向是 fail-closed：缺键 ⇒ 没有主体、未激活。
 *
 * <p>★ <b>{@code actors} 表保序不可变</b>：{@code LinkedHashMap} + {@code
 * Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b>——它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步<b>写在字段赋值处</b>（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。★ Task 5 / Task 6 加进来的两张表照同一形制。
 */
public record ActorData(Optional<ActorMeta> meta, Map<ActorRef, Actor> actors) {

  /** 往返用例的起点：未激活 + 空表。 */
  public static ActorData empty() {
    return new ActorData(Optional.empty(), Map.of());
  }

  public ActorData {
    // ★ 缺键（null）⇒ 未激活 / 空表，见类注释（旧档兼容，fail-closed 方向）。
    if (meta == null) {
      meta = Optional.empty();
    }
    if (actors == null) {
      actors = Map.of();
    }
    Map<ActorRef, Actor> actorsCopy = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, Actor> entry : actors.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("actors 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().ref())) {
        throw new IllegalArgumentException(
            "actors 的键必须与 Actor.ref 一致：键=" + entry.getKey() + "，行内 ref=" + entry.getValue().ref());
      }
      actorsCopy.put(entry.getKey(), entry.getValue());
    }
    actors = Collections.unmodifiableMap(actorsCopy); // ★ 冻在赋值处
  }

  /** 一个组件一个 with（照 {@code LedgerData} / {@code EconomyData} 的形制）。 */
  public ActorData withMeta(Optional<ActorMeta> value) {
    return new ActorData(value, actors);
  }

  /** 一个组件一个 with（照 {@code LedgerData} / {@code EconomyData} 的形制）。 */
  public ActorData withActors(Map<ActorRef, Actor> value) {
    return new ActorData(meta, value);
  }

  /**
   * ★★ <b>单个主体的写入口</b>：<b>键从值派生</b>（{@code actor.ref()} 就是键）。
   *
   * <p>★★ <b>为什么必须有这个 wither，而不是只暴露 {@code actors} 那张 {@code Map}</b>（控制方 2026-09-26
   * 裁定，理由写于计划"预检"节）：{@code FieldDelta} 的 key 由 {@code toString()} 产出、重建时用 {@code parse}
   * 还原，而<b>键与值的一致性由构造器判</b>——若只把表暴露成 {@code Map}，调用方就得自己拼键， 而"键是 {@code (ref)}"
   * 这件事就有了第二个拼写点。若那个拼写点写歪，测试要么恒真、要么判别力为零。 ⇒ <b>"键从值派生"只许有一个拼写点</b>，就在这里（Task 5 的 {@code
   * withHolding}、Task 6 的 {@code withAccount} 是同一个道理）。
   *
   * <p>★ <b>同一个键写两次 = 后写覆盖前写</b>（调用方给的是"这个主体是谁"，不是"追加一条"）：{@code LinkedHashMap} 的 {@code put}
   * 保留首次插入的位置、只换值 ⇒ 迭代序仍等于首次插入序，且表里只剩一条。
   */
  public ActorData withActor(Actor actor) {
    Objects.requireNonNull(actor, "actor");
    Map<ActorRef, Actor> next = new LinkedHashMap<>(actors);
    next.put(actor.ref(), actor);
    return new ActorData(meta, next);
  }
}
