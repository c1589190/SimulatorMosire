package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.money.MoneyAuthority;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>最小政府模型</b>（E3；设计稿 §2.6）：谁、以哪个国库账户、能发行哪些币种。
 *
 * <p>★★ <b>本批的取舍（如实记）</b>：当前市场的计价货币是单一的 {@code silver}（唯一拼写点 {@code
 * RegimeRelations.DEFAULT_CURRENCY}），而世界有三个国家。E3 采用<b>世界级最小政府</b>：一个 {@link GovernmentId} 指向一本
 * 国库，<b>只有一个发行主体声称 silver</b>。 每国一个政府、多币种/多发行主体留给后续阶段（见交付报告）。★★ 2026-10-07 起， 国库可以是 {@code
 * GOVERNMENT} actor，也可以是<b>非生产家户</b>的 {@code HOUSEHOLD} actor（GOV 家户试点）：后者让政府直接复用
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
    long debtIssuePerCycle,
    Map<String, OfficialRate> officialRates)
    implements MoneyAuthority {

  /**
   * ★ 旧形状兼容构造器：两个财政旋钮都取 0（逐值等于它们引入前的政府）。
   *
   * <p>旧夹具与旧载荷走它；带政府家户的世界（demo 的 world-silver / P2-C 的 GOV 单位政府）才显式给铸币/发债量。
   */
  public Government(
      GovernmentId id, String nationRef, ActorRef treasury, Set<CurrencyId> issuable) {
    this(id, nationRef, treasury, issuable, 0L, 0L, Map.of());
  }

  /**
   * ★★ <b>A2a 兼容构造器：没有官方汇率的形状</b>（官方汇率表 = 空）。
   *
   * <p>★ 它保住的正是"旧夹具/旧载荷/旧档一字不改仍编译、仍逐值可用"这一条：{@code officialRates} 是 A2a 新增的第 7 个组件， 旧的 6 参调用点（含
   * {@code src/test} 里的既有夹具）不必串改 —— 语义 = 这个政府<b>还没有定过任何官方汇率</b> （于是它的外汇窗口不存在，逐值退回 A2a 之前的行为）。
   */
  public Government(
      GovernmentId id,
      String nationRef,
      ActorRef treasury,
      Set<CurrencyId> issuable,
      long seignioragePerCycle,
      long debtIssuePerCycle) {
    this(id, nationRef, treasury, issuable, seignioragePerCycle, debtIssuePerCycle, Map.of());
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
      throw new IllegalArgumentException("Government.debtIssuePerCycle 不得为负: " + debtIssuePerCycle);
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
    // ★★ A2a：官方汇率表（键 == 值内币对）—— 跨表守卫与"政府声称发行词表里没有的钱"同族：
    //   键与值漂开 = 有一处代码在按另一个键查它，那种失败会在很远的读口才现形。
    if (officialRates == null) {
      officialRates = Map.of();
    }
    LinkedHashMap<String, OfficialRate> rates = new LinkedHashMap<>();
    for (Map.Entry<String, OfficialRate> entry : officialRates.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Government.officialRates 的键/值不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().key())) {
        throw new IllegalArgumentException(
            "Government.officialRates 的键必须等于值内币对（"
                + OfficialRate.keyOf(entry.getValue().base(), entry.getValue().quote())
                + "）: "
                + entry.getKey());
      }
      rates.put(entry.getKey(), entry.getValue());
    }
    officialRates = Collections.unmodifiableMap(rates);
  }

  /** ★★ <b>A2a：一个币对的官方汇率</b>（缺 ⇒ 空；"这个币对没有官方汇率"是合法状态，不是故障）。 */
  public Optional<OfficialRate> officialRate(CurrencyId base, CurrencyId quote) {
    return OfficialRate.find(officialRates, base, quote);
  }

  /**
   * ★★ <b>A2a：设置/覆盖一个币对的官方汇率</b>（只换这一条；发行权、财政旋钮、其它币对的报价逐值不变）。
   *
   * <p>★ 它是官方汇率的<b>唯一写入形态</b>：调用方拿不到"顺手把 issuable 也改了"的口子（同类改名的 {@code
   * CurrencyDef.withDisplayName}）。
   */
  public Government withOfficialRate(OfficialRate rate) {
    Objects.requireNonNull(rate, "rate");
    LinkedHashMap<String, OfficialRate> rates = new LinkedHashMap<>(officialRates);
    rates.put(rate.key(), rate);
    return new Government(
        id, nationRef, treasury, issuable, seignioragePerCycle, debtIssuePerCycle, rates);
  }

  /**
   * ★★ <b>只换发行权集合</b>（其它字段原样带过）：{@code DefineCurrency} 一类"给这个 GOV 加一种钱"的命令必须走它 —— 手写 {@code new
   * Government(...)} 会在下一次新增组件时静默丢掉那个组件（本仓最贵的那类 bug）。
   */
  public Government withIssuable(Set<CurrencyId> nextIssuable) {
    Objects.requireNonNull(nextIssuable, "nextIssuable");
    return new Government(
        id,
        nationRef,
        treasury,
        nextIssuable,
        seignioragePerCycle,
        debtIssuePerCycle,
        officialRates);
  }

  /** ★★ <b>只换国库 actor</b>（其它字段原样带过；官方汇率与发行权都不动）。 */
  public Government withTreasury(ActorRef nextTreasury) {
    Objects.requireNonNull(nextTreasury, "nextTreasury");
    return new Government(
        id,
        nationRef,
        nextTreasury,
        issuable,
        seignioragePerCycle,
        debtIssuePerCycle,
        officialRates);
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
