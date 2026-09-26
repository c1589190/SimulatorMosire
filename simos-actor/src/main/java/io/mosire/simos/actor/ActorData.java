package io.mosire.simos.actor;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.AssetHolding;
import io.mosire.simos.actor.model.AssetHoldingKey;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * actor 切片的完整状态树（S1 spec §三）：激活元信息 + 主体表 + 产权表 + 库存表。
 *
 * <p>★★ <b>{@code meta} 为空 {@code Optional} = 本世界尚未落 actor 切片</b>（同 {@code EconomyData} 的口径）。空快照 ≠
 * 已激活。
 *
 * <p>★★ <b>本任务的状态树有四件</b>（控制方按计划"增量表"的裁定）：{@code meta} / {@code actors} / {@code holdings} / {@code
 * accounts}（{@code accounts} 由 Task 6 加）—— ★ 加一张表的同一个提交里<b>同步</b>改本 record 的组件、 {@link
 * io.mosire.simos.actor.change.ActorChangeSet} 的字段 delta 与 {@code ActorRoundTripTest} 的往返断言。
 *
 * <p>★★ <b>为什么资产与库存都不在这里</b>（spec §2.3 L109、§三 L283）："资产是 Actor <b>拥有的关系</b>，不是 Actor
 * <b>本体的一部分</b>" —— 故它们**不进 {@link Actor}**，而是各自独立成表（{@code holdings} 与 {@code
 * accounts}），键<b>从值派生</b>。若哪天有人把库存同时写进 {@code Actor} 本体，spec §三 L283 的禁令就被绕过 —— 那条禁令由 {@code
 * ActorRoundTripTest} 的反射断言把守。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code EconomyData} 的"classes 的每个键必须等于其 {@code ClassRow.key()}"） ：{@code
 * actors} 的每个键必须等于其 {@link Actor#ref()}；{@code holdings} 的每个键必须等于其 {@link
 * AssetHolding#key()}；{@code accounts} 的每个键必须等于其 {@link GoodsAccount#key()}。否则同一份身份 / 同一份产权 /
 * 同一本账就有两处可能不一致的记录。
 *
 * <p>★★ <b>缺键 = 空</b>（照 {@code EconomyData} 的旧档兼容口径）：四个组件在本切片都是新引入的，故 Jackson 绑成 null 时一律收成空表 /
 * 未激活，<b>此处不抛</b> —— 抛了等于"旧档全部读不回来"。方向是 fail-closed：缺键 ⇒ 未激活、没有主体、没有产权、没有库存。
 *
 * <p>★ <b>三张表都保序不可变</b>：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b>——它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步<b>写在字段赋值处</b>（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * <p>★★ <b>表与表之间没有引用完整性约束</b>（本任务实测确认的边界）：{@code holdings} / {@code accounts} 的 {@code owner} 可以
 * <b>不在</b> {@code actors} 里。这不是漏写守卫，而是"缺键 = 空"的同一条口径：状态树是<b>逐组件增量落盘</b>的 （铁律 5 的变更集按组件走
 * diff），某组件先到、另一组件后到是常态；在此判"引用的主体必须存在"会让"先落产权再落主体" 的合法写序当场炸。存在性由装配期与命令面校验（同 {@code ActorRef}
 * 的口径："存在性与权限由 app 组合根校验"）。
 */
public record ActorData(
    Optional<ActorMeta> meta,
    Map<ActorRef, Actor> actors,
    Map<AssetHoldingKey, AssetHolding> holdings,
    Map<GoodsAccountKey, GoodsAccount> accounts) {

  /** 往返用例的起点：未激活 + 三张空表。 */
  public static ActorData empty() {
    return new ActorData(Optional.empty(), Map.of(), Map.of(), Map.of());
  }

  public ActorData {
    // ★ 缺键（null）⇒ 未激活 / 空表，见类注释（旧档兼容，fail-closed 方向）。
    if (meta == null) {
      meta = Optional.empty();
    }
    if (actors == null) {
      actors = Map.of();
    }
    if (holdings == null) {
      holdings = Map.of();
    }
    if (accounts == null) {
      accounts = Map.of();
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
    Map<AssetHoldingKey, AssetHolding> holdingsCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetHoldingKey, AssetHolding> entry : holdings.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("holdings 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().key())) {
        throw new IllegalArgumentException(
            "holdings 的键必须与 AssetHolding.key 一致：键="
                + entry.getKey()
                + "，行内 key="
                + entry.getValue().key());
      }
      holdingsCopy.put(entry.getKey(), entry.getValue());
    }
    holdings = Collections.unmodifiableMap(holdingsCopy); // ★ 冻在赋值处
    Map<GoodsAccountKey, GoodsAccount> accountsCopy = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : accounts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("accounts 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().key())) {
        throw new IllegalArgumentException(
            "accounts 的键必须与 GoodsAccount.key 一致：键="
                + entry.getKey()
                + "，行内 key="
                + entry.getValue().key());
      }
      accountsCopy.put(entry.getKey(), entry.getValue());
    }
    accounts = Collections.unmodifiableMap(accountsCopy); // ★ 冻在赋值处
  }

  /** 一个组件一个 with（照 {@code LedgerData} / {@code EconomyData} 的形制）。 */
  public ActorData withMeta(Optional<ActorMeta> value) {
    return new ActorData(value, actors, holdings, accounts);
  }

  /** 一个组件一个 with（照 {@code LedgerData} / {@code EconomyData} 的形制）。 */
  public ActorData withActors(Map<ActorRef, Actor> value) {
    return new ActorData(meta, value, holdings, accounts);
  }

  /** 一个组件一个 with（照 {@code LedgerData} / {@code EconomyData} 的形制）。 */
  public ActorData withHoldings(Map<AssetHoldingKey, AssetHolding> value) {
    return new ActorData(meta, actors, value, accounts);
  }

  /** 一个组件一个 with（照 {@code LedgerData} / {@code EconomyData} 的形制）。 */
  public ActorData withAccounts(Map<GoodsAccountKey, GoodsAccount> value) {
    return new ActorData(meta, actors, holdings, value);
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
    return new ActorData(meta, next, holdings, accounts);
  }

  /**
   * ★★ <b>单份产权的写入口</b>：<b>键从值派生</b>（{@code holding.key()} 就是键）。
   *
   * <p>★★ <b>为什么必须有</b>（brief Step 1 的注，<b>写测试时发现的计划缺陷</b>）：若只把 {@code holdings} 暴露成一个 {@code
   * Map}，"聚合键是 {@code (owner, hex, assetClass)}"就退化成 {@code java.util.Map} <b>自己的</b>语义 ——
   * 测试会<b>恒真</b>、判别力为零。⇒ "键从值派生"这件事<b>必须只有一个拼写点</b>，测试才咬得住（同 {@link #withActor}）。
   *
   * <p>★ <b>同一个键写两次 = 后写覆盖前写</b>：调用方给的是"<b>该余额是多少</b>"，不是"加多少"（{@link AssetHolding} 的 {@code
   * quantity} 是余额）——故这里是 {@code put} 而不是"相加"。★ 若哪天要表达"转入 500"，那是**命令**（由一个 handler
   * 先读余额、再算出新余额），不是状态类型的方法。
   */
  public ActorData withHolding(AssetHolding holding) {
    Objects.requireNonNull(holding, "holding");
    Map<AssetHoldingKey, AssetHolding> next = new LinkedHashMap<>(holdings);
    next.put(holding.key(), holding);
    return new ActorData(meta, actors, next, accounts);
  }

  /**
   * ★★ <b>单本账的写入口</b>：<b>键从值派生</b>（{@code account.key()} 就是键）。
   *
   * <p>★★ <b>为什么必须有</b>（同 {@link #withHolding}，理由一字不差）：若只把 {@code accounts} 暴露成一个 {@code Map}，"聚合键是
   * {@code (owner, location)}"就退化成 {@code java.util.Map} <b>自己的</b>语义 —— 测试会<b>恒真</b>、 判别力为零。⇒
   * "键从值派生"这件事<b>必须只有一个拼写点</b>，测试才咬得住。
   *
   * <p>★ <b>同一个键写两次 = 后写覆盖前写</b>：调用方给的是"<b>这本账现在是多少</b>"，不是"加多少"（{@link GoodsAccount} 的语义是存量 ——
   * 余额是覆盖、且 0 也是一个值）。★ 若哪天要表达"转入 500"，那是**命令**（由一个 handler 先读余额、再算出新余额）， 不是状态类型的方法（同 {@link
   * #withHolding}）。
   */
  public ActorData withAccount(GoodsAccount account) {
    Objects.requireNonNull(account, "account");
    Map<GoodsAccountKey, GoodsAccount> next = new LinkedHashMap<>(accounts);
    next.put(account.key(), account);
    return new ActorData(meta, actors, holdings, next);
  }
}
