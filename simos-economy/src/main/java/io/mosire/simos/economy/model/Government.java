package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorKind;
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
 * 国库，<b>只有一个发行主体声称 silver</b>。 每国一个政府、多币种/多发行主体留给后续阶段（见交付报告）。★★ 2026-10-07 起，
 * 国库可以是 {@code GOVERNMENT} actor，也可以是<b>非生产家户</b>的 {@code HOUSEHOLD} actor（GOV 家户试点）：后者让政府直接复用
 * 家户的市场、账户与债务路径；{@code seignioragePerCycle} 是周期铸币旋钮（0 = 不自动铸币）。
 *
 * <p>★ <b>{@code issuable} 为空 = 这个政府不是任何币种的发行人</b>（它可以只是财政主体）；{@code authorityOf} 对集合外的币种 当场抛 ——
 * "谁能造钱"这条判据不能由一个宽松的集合悄悄抹掉。
 *
 * @param id 政府稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param nationRef 国家/辖区引用（当前为世界级最小政府，取 {@code "world"}；不得为空白）
 * @param treasury 国库 actor（{@code GOVERNMENT} 或非生产家户模型下的 {@code HOUSEHOLD}；不得为 null）
 * @param issuable 该政府可发行/回笼的币种集合；不得为 null、键不得为 null；空集 = 非发行人；保序不可变
 * @param seignioragePerCycle 每个产业大周期开始日向国库增发的货币量（最小币值）；{@code 0} = 不自动铸币；不得为负
 * @param debtIssuePerCycle 每个产业大周期开始日向家户借入的货币债务目标（最小币值）；{@code 0} = 不自动发债；不得为负
 */
public record Government(
    GovernmentId id,
    String nationRef,
    ActorRef treasury,
    Set<CurrencyId> issuable,
    long seignioragePerCycle,
    long debtIssuePerCycle)
    implements MoneyAuthority {

  /**
   * ★ 旧形状兼容构造器：两个财政旋钮都取 0（逐值等于它们引入前的政府）。
   *
   * <p>旧夹具与旧载荷走它；production-runtime 的内置 GOV 家户才显式给铸币/发债量。
   */
  public Government(
      GovernmentId id, String nationRef, ActorRef treasury, Set<CurrencyId> issuable) {
    this(id, nationRef, treasury, issuable, 0L, 0L);
  }

  /** ★ 只给铸币、不发债的形状（2026-10-07 首批 GOV 试点调用点兼容）。 */
  public Government(
      GovernmentId id,
      String nationRef,
      ActorRef treasury,
      Set<CurrencyId> issuable,
      long seignioragePerCycle) {
    this(id, nationRef, treasury, issuable, seignioragePerCycle, 0L);
  }

  public Government {
    Objects.requireNonNull(id, "Government.id 不得为 null");
    if (nationRef == null || nationRef.isBlank()) {
      throw new IllegalArgumentException("Government.nationRef 不得为空白");
    }
    Objects.requireNonNull(treasury, "Government.treasury 不得为 null");
    // ★★ 2026-10-07 GOV 非生产家户试点：国库可以是一本家户账（ActorKind.HOUSEHOLD）——
    //   那时政府与家户共用同一套市场/债务路径。旧档的世界级最小政府仍是 GOVERNMENT。
    if (treasury.kind() != ActorKind.GOVERNMENT && treasury.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalArgumentException(
          "Government.treasury 的 ActorKind 必须是 GOVERNMENT 或 HOUSEHOLD: " + treasury);
    }
    if (seignioragePerCycle < 0L) {
      throw new IllegalArgumentException(
          "Government.seignioragePerCycle 不得为负: " + seignioragePerCycle);
    }
    if (debtIssuePerCycle < 0L) {
      throw new IllegalArgumentException(
          "Government.debtIssuePerCycle 不得为负: " + debtIssuePerCycle);
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
