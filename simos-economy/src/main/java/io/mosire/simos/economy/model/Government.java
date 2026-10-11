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
 * GOVERNMENT} actor，也可以是<b>非生产家户</b>的 {@code HOUSEHOLD} actor（GOV 家户试点）：后者让政府直接复用 家户的市场、账户与债务路径。
 *
 * <p>★★★ <b>W5（2026-10-11）：双轨合并 + 货币 = 政府债务</b>。改前本记录有<b>两个财政旋钮</b> （{@code seignioragePerCycle} =
 * 周期铸币，直接把钱加进国库；{@code debtIssuePerCycle} = 周期向家户借债）， 两条轨道各有写口。按用户裁定（约束设计书 §1 第一批第 1 条 + 第二批第 3
 * 条、§一.11）：
 *
 * <ul>
 *   <li><b>合并成一条</b>：{@code seignioragePerCycle} 与 {@code debtIssuePerCycle} <b>直接删除</b>（旧设计不留兼容位）；
 *       取而代之的唯一旋钮是 {@link #mintScalePerCycle()}；
 *   <li><b>铸币不再"直接加钱"</b>：钱是<b>生产出来的</b>（{@link Industry#moneyOutputPerUnit()}，组织者 = 政府家户）， 由
 *       {@code MintSettlement} 在周期关账时收获、写 {@code MoneyIssuanceRecord} 审计、并<b>记入同一本 {@code
 *       debtContracts}（发行政府的债务）</b>；
 *   <li><b>铸币规模</b>就是政策旋钮：它同时是<b>产出上限</b>（本周期最多铸多少毫）。
 * </ul>
 *
 * <p>★★ <b>{@code mintScalePerCycle} 的语义（三件事，别混）</b>：
 *
 * <ol>
 *   <li><b>0 = 不铸币</b>（缺省；判据：规模 0 ⇒ 新钱<b>恰好 0 毫</b>，货币总量守恒）；
 *   <li>它是<b>政策可调</b>的量，GM 写口有两条（各服务一种政府形态）：<b>GOV 单位政府</b> = {@code economy.RegisterGovernment} 的
 *       {@code mintScalePerCycle} 字段（窄工具 {@code simos.economy.registerGovernment} / {@code
 *       simos.economy.upsertGovUnit} 一族）；<b>世界级政府</b>（{@code world-silver} 这类不是任何 GOV 单位的发行主体）=
 *       {@code economy.SetGovernmentMintScale}（窄工具 {@code simos.economy.setGovernmentMintScale}）——
 *       ★ W5-fix6 补：改前世界级政府<b>没有任何写口</b>（前者的 {@code govUnitId} 会派生 {@code gov-unit-<id>}，够不着它；W5
 *       验收报告 §3-D1 的 HTTP 422 原文）；
 *   <li>★ 它<b>就是</b>"本轮能新投入多少"的闸门本身（W5-fix5：**决策人预期即权威**，用户原话「铸币能进政府的家户， 按照决策人预期被发行就行」）—— 不再有第二条"≤
 *       上一市场轮印量"的 clamp。本字段回答"这个政府本周期最多铸多少"， 实铸 = {@code min(产出请求, 本字段)}。
 * </ol>
 *
 * <p>★ <b>{@code issuable} 为空 = 这个政府不是任何币种的发行人</b>（它可以只是财政主体）；{@code authorityOf} 对集合外的币种 当场抛 ——
 * "谁能造钱"这条判据不能由一个宽松的集合悄悄抹掉。
 *
 * @param id 政府稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param nationRef 国家/辖区引用（当前为世界级最小政府，取 {@code "world"}；不得为空白）
 * @param treasury 国库 actor（{@code GOVERNMENT} 或非生产家户模型下的 {@code HOUSEHOLD}；不得为 null）
 * @param issuable 该政府可发行/回笼的币种集合；不得为 null、键不得为 null；空集 = 非发行人；保序不可变
 * @param mintScalePerCycle ★★ <b>铸币政策旋钮</b>：本政府每个产业大周期最多铸多少<b>毫</b>（产出的上限；不按币种细分， 逐币种取用）。{@code 0}
 *     = 政策不许铸币（缺省）；不得为负。★★ <b>W5-fix5 起它就是唯一的闸门</b>：实铸 = {@code min(产出请求, 本字段)} ——
 *     不再因"超过上一轮印量"拒发（用户 2026-10-11 原话「按照决策人预期被发行就行」）。
 * @param mintedLastCyclePerCycle ★★★ <b>上一铸币轮逐币种实际印量（毫）</b> —— <b>可持久读数</b>（决策人/GUI 看的"上一轮印了多少"）。 ★
 *     <b>它不是闸门</b>（W5-fix5 起不参与任何 clamp）：本字段不限制本轮的实铸额，只回答"上一轮印了多少"。 ★ 它<b>不是第二本发行账</b>：权威账仍是 {@code
 *     EconomyData.moneyIssuances} （{@code MoneyIssuanceRecord} 现算），本表只是"最近一轮的汇总"，由 {@code
 *     MintSettlement} 在每轮关账时覆盖写、 固定大小 ⇒ 状态不随推进天数增长（性能红线）。★ 空表 = 还没有过任何铸币轮（合法状态：这一轮没有上一轮印量可比）。 键不得为
 *     null、逐值必须 {@code > 0}（0 不是一条记录，删键即可）；保序不可变
 * @param officialRates 官方汇率表（键 == 值内币对）；不得为 null；保序不可变
 */
public record Government(
    GovernmentId id,
    String nationRef,
    ActorRef treasury,
    Set<CurrencyId> issuable,
    long mintScalePerCycle,
    Map<CurrencyId, Long> mintedLastCyclePerCycle,
    Map<String, OfficialRate> officialRates)
    implements MoneyAuthority {

  /**
   * ★ 旧形状兼容构造器：铸币旋钮 0、上一轮印量为空（逐值等于"这个政府还不铸币"）。
   *
   * <p>★ 它服务于"旧夹具/旧载荷走它"这一条（与 A2a 的官方汇率构造器同制）：非发行人/纯财政主体走它。
   */
  public Government(
      GovernmentId id, String nationRef, ActorRef treasury, Set<CurrencyId> issuable) {
    this(id, nationRef, treasury, issuable, 0L, Map.of(), Map.of());
  }

  /**
   * ★★ <b>只给铸币旋钮、没有上一轮印量的形状</b>（GOV 单位路径 {@code economy.RegisterGovernment} 与 GOV 家户 播种路径的常规入口）。
   *
   * <p>★ <b>为什么这个重载是安全的（两个 long/Map 尾参不会打架）</b>：本记录只有两个"尾参重载"—— 本构造器 （{@code long}）与下一个（{@code
   * Map}）。{@code long} 与 {@code Map} <b>没有任何共同的可转换形态</b> （Java 不会把 {@code long} 隐式转成 {@code
   * Map}，也不会把 {@code Map} 转成 {@code long}），故重载解析 在编译期就是确定的 —— 实测：{@code new Government(id, ref,
   * treasury, issuable, 2_000L)} 选中本构造器。
   */
  public Government(
      GovernmentId id,
      String nationRef,
      ActorRef treasury,
      Set<CurrencyId> issuable,
      long mintScalePerCycle) {
    this(id, nationRef, treasury, issuable, mintScalePerCycle, Map.of(), Map.of());
  }

  /**
   * ★★ <b>A2a：没有官方汇率的形状</b>（官方汇率表 = 空）。
   *
   * <p>★ 它保住的正是"旧夹具/旧载荷/旧档一字不改仍编译、仍逐值可用"这一条：{@code officialRates} 是 A2a 新增的组件， 旧的调用点（含 {@code
   * src/test} 里的既有夹具）不必串改 —— 语义 = 这个政府<b>还没有定过任何官方汇率</b> （于是它的外汇窗口不存在，逐值退回 A2a 之前的行为）。
   */
  public Government(
      GovernmentId id,
      String nationRef,
      ActorRef treasury,
      Set<CurrencyId> issuable,
      long mintScalePerCycle,
      Map<CurrencyId, Long> mintedLastCyclePerCycle) {
    this(id, nationRef, treasury, issuable, mintScalePerCycle, mintedLastCyclePerCycle, Map.of());
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
    if (mintScalePerCycle < 0L) {
      throw new IllegalArgumentException(
          "Government.mintScalePerCycle 不得为负（0 = 政策不许铸币）: " + mintScalePerCycle);
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
    // ★★ W5：上一轮印量表（逐币种；0 不是一条记录 ⇒ 构造期判死，不静默删键 —— 静默归一会让"写 0"看起来成功）。
    if (mintedLastCyclePerCycle == null) {
      mintedLastCyclePerCycle = Map.of();
    }
    LinkedHashMap<CurrencyId, Long> minted = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : mintedLastCyclePerCycle.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Government.mintedLastCyclePerCycle 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "Government.mintedLastCyclePerCycle 的数量必须为正（0 不是一条印量，删键即可）："
                + entry.getKey().value()
                + " = "
                + entry.getValue());
      }
      minted.put(entry.getKey(), entry.getValue());
    }
    mintedLastCyclePerCycle = Collections.unmodifiableMap(minted); // ★ 冻在赋值处
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
        id, nationRef, treasury, issuable, mintScalePerCycle, mintedLastCyclePerCycle, rates);
  }

  /**
   * ★★ <b>只换铸币政策旋钮</b>（其它字段原样带过）：{@code economy.RegisterGovernment} 一类"改这个 GOV 的铸币政策" 的命令必须走它 —— 手写
   * {@code new Government(...)} 会在下一次新增组件时静默丢掉那个组件（本仓最贵的那类 bug）。
   */
  public Government withMintScalePerCycle(long nextMintScalePerCycle) {
    return new Government(
        id,
        nationRef,
        treasury,
        issuable,
        nextMintScalePerCycle,
        mintedLastCyclePerCycle,
        officialRates);
  }

  /**
   * ★★★ <b>覆盖"上一铸币轮逐币种印量"</b>（{@code MintSettlement} 在每轮关账时调一次；其它字段原样带过）。
   *
   * <p>★ 它是本字段的<b>唯一写入形态</b>：写它的人必须拿得出"最近一个<b>严格早于本轮</b>的铸币日、这个政府逐币种的 {@code MINT_PRODUCTION} 合计"（从
   * {@code MoneyIssuanceRecord} 现算，空表 = 还没有过铸币轮）—— 别的路径拿不到"顺手把旋钮也改了"的口子。
   */
  public Government withMintedLastCyclePerCycle(Map<CurrencyId, Long> nextMintedLastCycle) {
    Objects.requireNonNull(nextMintedLastCycle, "nextMintedLastCycle");
    return new Government(
        id, nationRef, treasury, issuable, mintScalePerCycle, nextMintedLastCycle, officialRates);
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
        mintScalePerCycle,
        mintedLastCyclePerCycle,
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
        mintScalePerCycle,
        mintedLastCyclePerCycle,
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
