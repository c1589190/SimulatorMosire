package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.money.MoneyAuthority;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>最小政府模型</b>（E3；设计稿 §2.6）：谁、以哪个国库账户、能发行哪些币种。
 *
 * <p>★★ <b>本批的取舍（如实记）</b>：当前市场的计价货币是单一的 {@code silver}（唯一拼写点 {@code
 * RegimeRelations.DEFAULT_CURRENCY}），而世界有三个国家。E3 采用<b>世界级最小政府</b>：一个 {@link GovernmentId} 指向一本
 * {@code ActorRef(GOVERNMENT, …)} 国库，<b>只有一个发行主体声称 silver</b>。 每国一个政府、多币种/多发行主体留给后续阶段（见交付报告）。
 *
 * <p>★ <b>{@code issuable} 为空 = 这个政府不是任何币种的发行人</b>（它可以只是财政主体）；{@code authorityOf} 对集合外的币种 当场抛 ——
 * "谁能造钱"这条判据不能由一个宽松的集合悄悄抹掉。
 *
 * @param id 政府稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param nationRef 国家/辖区引用（当前为世界级最小政府，取 {@code "world"}；不得为空白）
 * @param treasury 国库 actor（种类必须是 {@code GOVERNMENT}；不得为 null）
 * @param issuable 该政府可发行/回笼的币种集合；不得为 null、键不得为 null；空集 = 非发行人；保序不可变
 */
public record Government(
    GovernmentId id, String nationRef, ActorRef treasury, Set<CurrencyId> issuable)
    implements MoneyAuthority {

  public Government {
    Objects.requireNonNull(id, "Government.id 不得为 null");
    if (nationRef == null || nationRef.isBlank()) {
      throw new IllegalArgumentException("Government.nationRef 不得为空白");
    }
    Objects.requireNonNull(treasury, "Government.treasury 不得为 null");
    if (treasury.kind() != io.mosire.simos.actor.api.actor.ActorKind.GOVERNMENT) {
      throw new IllegalArgumentException(
          "Government.treasury 的 ActorKind 必须是 GOVERNMENT: " + treasury);
    }
    if (issuable == null) {
      throw new IllegalArgumentException("Government.issuable 不得为 null（不是发行人就给空集）");
    }
    LinkedHashSet<CurrencyId> copy = new LinkedHashSet<>();
    for (CurrencyId currency : issuable) {
      if (currency == null) {
        throw new IllegalArgumentException("Government.issuable 不得含 null");
      }
      copy.add(currency);
    }
    issuable = Collections.unmodifiableSet(copy); // ★ 冻在赋值处（SpotBugs 只认它看得见的包装）
  }

  /** ★★ 发行源：{@code issuable} 内含 {@code currency} ⇒ 返回国库 actor；否则当场抛（说不出"谁发的"）。 */
  @Override
  public ActorRef authorityOf(CurrencyId currency) {
    Objects.requireNonNull(currency, "Government.authorityOf 的 currency 不得为 null");
    if (!issuable.contains(currency)) {
      throw new IllegalArgumentException(
          "政府 " + id + " 不发行币种 " + currency + "（issuable=" + issuable + "）");
    }
    return treasury;
  }

  /** ★ 可发行币种（保序不可变；空集 = 非发行人）。 */
  @Override
  public Set<CurrencyId> issuable() {
    return issuable;
  }
}
