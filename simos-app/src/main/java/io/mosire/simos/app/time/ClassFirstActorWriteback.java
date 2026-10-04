package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstSettlement;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.model.ClassRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>R2b：把 {@link ClassFirstSettlement.Result#actorAccountDeltas()} 落到 actor 家户账本</b>。
 *
 * <p>它住在 {@code simos-app}：只有组合根同时看得见 classfirst 池（economy）与家户账本（actor）。{@code 旧日推进器（R3a 已删除）} 的
 * ledger 折账（旧 OwnershipBooks，R3a 已删除）与 classfirst 的账户增量<b>不是同一套口径</b>：后者是<b>池级</b>资产差分，而 actor 账本按
 * {@code (owner, hex)} 展开 ⇒ 本类负责"池级增量 → 家户级落账"的<b>唯一一次</b>展开。
 *
 * <p>★★ <b>落账口径（如实写在类注）</b>：
 *
 * <ul>
 *   <li><b>逐值守恒</b>：每个池的每个维度，Σ 落进该池家户账的增量恒等于 {@link ClassFirstSettlement.AccountDelta#delta()} ——
 *       正增量按 家户人口权重最大余数法分摊，负增量按可用余额（余额 − 冻结）从大到小瀑布扣减；两者都<b>逐值</b>分配完，不丢一个单位；
 *   <li><b>近似在"怎么分摊"</b>：classfirst 引擎只维护池级库存（家户生产账户不带库存），没有"逐家户商品份额"这维状态 ⇒
 *       日增量的家户间分摊是本类的<b>显式近似</b>（人口权重/可用余额），不冒充引擎内部账；
 *   <li><b>维度缺口具名</b>：{@code ownedLand} / {@code tools} 在 actor 账本里<b>没有对应维度</b>（{@code
 *       AssetHolding} 已退役，{@link GoodsAccount} 只有商品与货币）⇒ 这两维只落在 classfirst 池、不折 actor，名字经 {@link
 *       Applied#unmappedDimensions()} 交回调用方并写日志（不静默）；
 *   <li><b>fail-closed</b>：池成员缺 {@code ClassRow}/actor 账、负增量超出可用余额（含冻结）、币种不唯一、放贷账户组为空 —— 一律当场抛，
 *       绝不静默跳过（跳过 = 直接把货币/商品从守恒式里删掉）。
 * </ul>
 */
final class ClassFirstActorWriteback {

  private ClassFirstActorWriteback() {}

  /** 落账结果：新的 actor 状态 + 本批未映射的资产维度名（具名 gap）。 */
  record Applied(ActorData data, Set<String> unmappedDimensions) {

    Applied {
      Objects.requireNonNull(data, "data");
      unmappedDimensions =
          Collections.unmodifiableSet(
              new LinkedHashSet<>(Objects.requireNonNull(unmappedDimensions)));
    }
  }

  /**
   * 把 {@code result} 的账户增量逐条折进 {@code actor}。
   *
   * @param actor 当日结算<b>之前</b>的 actor 状态（只读，不改）
   * @param economy 与 {@code before} 同代的经济切片（提供 {@code class} 视图 → actor 账键所需的格）
   * @param before 当日结算<b>之前</b>的 classfirst 状态（池成员索引/权重）
   */
  static Applied apply(
      ActorData actor,
      EconomyData economy,
      ClassFirstState before,
      ClassFirstSettlement.Result result) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(result, "result");

    Set<CurrencyId> currencies = currenciesOf(actor);
    LinkedHashSet<String> gaps = new LinkedHashSet<>();
    ActorData current = actor;
    for (ClassFirstSettlement.AccountDelta delta : result.actorAccountDeltas()) {
      if (delta.accountId().startsWith(ClassPoolId.PREFIX)) {
        current =
            applyPoolDelta(
                current,
                economy,
                before,
                ClassPoolId.parse(delta.accountId()),
                delta,
                currencies,
                gaps);
      } else {
        current = applyLenderDelta(current, delta, currencies);
      }
    }
    return new Applied(current, gaps);
  }

  // ── 池级增量 → 家户级落账 ────────────────────────────────────────────────────────────

  private static ActorData applyPoolDelta(
      ActorData actor,
      EconomyData economy,
      ClassFirstState before,
      ClassPoolId poolId,
      ClassFirstSettlement.AccountDelta delta,
      Set<CurrencyId> currencies,
      Set<String> gaps) {
    Channel channel = poolChannel(delta.commodity(), currencies, delta);
    if (channel == null) {
      // 具名缺口：actor 账本没有土地/农具维度（不是"忘了落"，是结构上落不进去）。
      gaps.add(delta.commodity());
      return actor;
    }
    List<Member> members = poolMembers(actor, economy, before, poolId, delta);
    long[] shares = allocate(delta.delta(), members, channel, delta);
    ActorData next = actor;
    for (int i = 0; i < members.size(); i++) {
      if (shares[i] == 0L) {
        continue;
      }
      next = next.withAccount(adjust(members.get(i).account(), channel, shares[i], delta));
    }
    return next;
  }

  /** 一个池成员：actor 账键 + 值 + 人口权重（池内分摊的权）。 */
  private record Member(GoodsAccountKey key, GoodsAccount account, long population) {}

  private static List<Member> poolMembers(
      ActorData actor,
      EconomyData economy,
      ClassFirstState before,
      ClassPoolId poolId,
      ClassFirstSettlement.AccountDelta delta) {
    List<Member> members = new ArrayList<>();
    for (HouseholdProductionAccount account : before.householdAccounts().values()) {
      if (!poolId.equals(account.poolId())) {
        continue;
      }
      HouseholdId householdId = HouseholdId.parse(account.householdId());
      ClassRow row = economy.classes().get(householdId);
      if (row == null) {
        throw new IllegalStateException(
            "classFirst 池成员不在 economy.classes 里（无法定位它的 actor 账格）"
                + "：pool="
                + poolId
                + " household="
                + householdId
                + " delta="
                + delta);
      }
      GoodsAccountKey key = new GoodsAccountKey(HouseholdActors.of(householdId), row.view().hex());
      GoodsAccount book = actor.accounts().get(key);
      if (book == null) {
        throw new IllegalStateException(
            "classFirst 池成员缺 actor 家户账（不能静默丢这笔增量）：key=" + key + " delta=" + delta);
      }
      members.add(new Member(key, book, account.population()));
    }
    if (members.isEmpty()) {
      throw new IllegalStateException(
          "classFirst 池没有任何家户成员可落 actor 账：pool=" + poolId + " delta=" + delta);
    }
    return members;
  }

  // ── 放贷账户增量 → GOV 账本（世界级合并后可有多本）──────────────────────────────────────

  /**
   * ★★ R2c：放贷账户增量落进 actor 里的 <b>{@code GOVERNMENT} 账户组</b>。
   *
   * <p>多国 class-first seed 各在<b>本国首格</b>建一本 GOV 账户（同一 owner、不同 location ⇒ 不同 {@link
   * GoodsAccountKey}），而 classfirst 的放贷主体是世界级单账户 ⇒ 逐池合并后 Σactor GOV 余额 == lender
   * 资金。本方法把每条增量分到这些账户上：正增量按可用余额权重最大余数法，负增量按可用余额瀑布 —— 逐值分完，不静默丢。
   */
  private static ActorData applyLenderDelta(
      ActorData actor, ClassFirstSettlement.AccountDelta delta, Set<CurrencyId> currencies) {
    Channel channel;
    if ("money".equals(delta.commodity())) {
      channel = Channel.ofMoney(requireSingleCurrency(currencies, delta));
    } else {
      channel = Channel.ofCommodity(new CommodityId(delta.commodity()));
    }
    List<GoodsAccountKey> governments = governmentAccounts(actor, delta);
    List<GoodsAccount> books = new ArrayList<>(governments.size());
    for (GoodsAccountKey key : governments) {
      GoodsAccount book = actor.accounts().get(key);
      if (book == null) {
        throw new IllegalStateException("放贷账户的 actor 账本不在场：" + key + " delta=" + delta);
      }
      books.add(book);
    }
    long[] shares = allocateAcrossBooks(delta, books, channel);
    ActorData next = actor;
    for (int i = 0; i < books.size(); i++) {
      if (shares[i] != 0L) {
        next = next.withAccount(adjust(books.get(i), channel, shares[i], delta));
      }
    }
    return next;
  }

  /** classfirst 的放贷主体（本项目 = 创世 GOV 账户）：世界级合并后允许 ≥1 本；键按规范串排序保证确定性。 */
  private static List<GoodsAccountKey> governmentAccounts(
      ActorData actor, ClassFirstSettlement.AccountDelta delta) {
    List<GoodsAccountKey> governments = new ArrayList<>();
    for (GoodsAccountKey key : actor.accounts().keySet()) {
      if (key.owner().kind() == ActorKind.GOVERNMENT) {
        governments.add(key);
      }
    }
    if (governments.isEmpty()) {
      throw new IllegalStateException("放贷 AccountDelta 需要至少一个 actor GOVERNMENT 账户：" + delta);
    }
    governments.sort(Comparator.comparing(GoodsAccountKey::toString));
    return governments;
  }

  /**
   * 把一条放贷账户增量逐值分到 {@code books}：正增量按可用余额权重最大余数法（余额全 0 ⇒ 落第一本，确定性）； 负增量按可用余额（余额 −
   * 冻结）从大到小瀑布扣减，分不完当场抛。
   */
  private static long[] allocateAcrossBooks(
      ClassFirstSettlement.AccountDelta delta, List<GoodsAccount> books, Channel channel) {
    long[] shares = new long[books.size()];
    if (delta.delta() > 0L) {
      long[] weights = new long[books.size()];
      for (int i = 0; i < books.size(); i++) {
        weights[i] = Math.max(0L, availableOf(books.get(i), channel));
      }
      return ClassFirstDistribution.largestRemainder(delta.delta(), weights);
    }
    long remaining = -delta.delta();
    long[] available = new long[books.size()];
    for (int i = 0; i < books.size(); i++) {
      available[i] = availableOf(books.get(i), channel);
    }
    for (int index : ClassFirstDistribution.orderByDescending(available)) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(remaining, available[index]);
      shares[index] = -take;
      remaining -= take;
    }
    if (remaining > 0L) {
      throw new IllegalStateException(
          "放贷 AccountDelta 超出 actor GOV 账户组的可用余额（余额 − 冻结）：剩余 " + remaining + " 条记录=" + delta);
    }
    return shares;
  }

  // ── 维度映射 ────────────────────────────────────────────────────────────────────────

  /** 落账通道：商品（键 = {@link CommodityId}）或货币（键 = {@link CurrencyId}，{@code commodity == null}）。 */
  private record Channel(CommodityId commodity, CurrencyId currency) {

    static Channel ofCommodity(CommodityId commodity) {
      return new Channel(Objects.requireNonNull(commodity, "commodity"), null);
    }

    static Channel ofMoney(CurrencyId currency) {
      return new Channel(null, Objects.requireNonNull(currency, "currency"));
    }

    boolean isMoney() {
      return commodity == null;
    }
  }

  /**
   * classfirst 池维度 → actor 通道。{@code ownedLand}/{@code tools} 返回 {@code null}（具名缺口）。
   *
   * <p>★ 维度名的拼写在 {@link AssetKind#schemaName()}（唯一一处）；商品侧字符串的拼写在 {@link PilotModel#GRAIN} / {@link
   * PilotModel#CLOTH}（actor 账的 {@link CommodityId} 就按它们建）。
   */
  private static Channel poolChannel(
      String schemaName, Set<CurrencyId> currencies, ClassFirstSettlement.AccountDelta delta) {
    AssetKind kind = null;
    for (AssetKind candidate : AssetKind.ordered()) {
      if (candidate.schemaName().equals(schemaName)) {
        kind = candidate;
        break;
      }
    }
    if (kind == null) {
      throw new IllegalStateException("未知的 classFirst 池资产维度: " + schemaName + " delta=" + delta);
    }
    return switch (kind) {
      case GRAIN -> Channel.ofCommodity(new CommodityId(PilotModel.GRAIN));
      case CLOTH -> Channel.ofCommodity(new CommodityId(PilotModel.CLOTH));
      case MONEY -> Channel.ofMoney(requireSingleCurrency(currencies, delta));
      // actor 账本没有土地/农具维度（见类注：具名缺口，不静默、也不硬造一本假账）。
      case OWNED_LAND, TOOLS -> null;
      // 派生量（经营地/租约/债务）不进 stock() 差分 ⇒ AccountDelta 不会出现；出现即状态损坏。
      default ->
          throw new IllegalStateException(
              "classFirst 池维度 " + kind + " 不应出现在 AccountDelta 里: " + delta);
    };
  }

  /** actor 账本里出现过的币种集合（用于 money 增量的唯一映射；不猜、不默认）。 */
  private static Set<CurrencyId> currenciesOf(ActorData actor) {
    LinkedHashSet<CurrencyId> currencies = new LinkedHashSet<>();
    for (GoodsAccount account : actor.accounts().values()) {
      currencies.addAll(account.money().keySet());
    }
    return currencies;
  }

  private static CurrencyId requireSingleCurrency(
      Set<CurrencyId> currencies, ClassFirstSettlement.AccountDelta delta) {
    if (currencies.size() != 1) {
      throw new IllegalStateException(
          "含 money 的 AccountDelta 需要 actor 账本恰有一种货币，实得 " + currencies + "： " + delta);
    }
    return currencies.iterator().next();
  }

  // ── 分配（正：人口权重最大余数法；负：可用余额瀑布）──────────────────────────────────

  /**
   * 把 {@code delta} 逐值分给 {@code members}（不四舍五入丢单位）。
   *
   * <p>正增量按家户人口权重最大余数法；全池人口为 0（理论上只可能是空池）⇒ 全部落第一个成员，保证不丢。负增量按"可用余额"（余额 −
   * 冻结）从大到小瀑布扣减，仍然逐值分完；分不完（可用余额不足）当场抛 —— 那说明 actor 账与池库存已经不同步，不能靠夹取掩盖。
   */
  private static long[] allocate(
      long delta, List<Member> members, Channel channel, ClassFirstSettlement.AccountDelta record) {
    if (delta > 0L) {
      long[] weights = new long[members.size()];
      for (int i = 0; i < members.size(); i++) {
        weights[i] = members.get(i).population();
      }
      return ClassFirstDistribution.largestRemainder(delta, weights);
    }

    long removal = -delta;
    long[] shares = new long[members.size()];
    long[] available = new long[members.size()];
    for (int i = 0; i < members.size(); i++) {
      available[i] = availableOf(members.get(i).account(), channel);
    }
    long remaining = removal;
    for (int index : ClassFirstDistribution.orderByDescending(available)) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(remaining, available[index]);
      shares[index] = -take; // 负增量：份额带符号，落账时才是"扣减"
      remaining -= take;
    }
    if (remaining > 0L) {
      throw new IllegalStateException(
          "AccountDelta 超出池内 actor 家户账的可用余额（余额 − 冻结）：剩余 " + remaining + " 条记录=" + record);
    }
    return shares;
  }

  // ── 单账户改写（整本覆盖 → 新 {@link GoodsAccount}，不改运行对象）────────────────────

  private static GoodsAccount adjust(
      GoodsAccount account, Channel channel, long delta, ClassFirstSettlement.AccountDelta record) {
    if (delta == 0L) {
      throw new IllegalStateException("内部错误：0 增量不该走到落账（" + record + "）");
    }
    if (channel.isMoney()) {
      CurrencyId currency = channel.currency();
      Map<CurrencyId, Long> money = new LinkedHashMap<>(account.money());
      long next = Math.addExact(money.getOrDefault(currency, 0L), delta);
      requireNotNegative(next, "货币", currency.value(), account.key(), record);
      long frozen = account.frozenMoney().getOrDefault(currency, 0L);
      if (next < frozen) {
        throw new IllegalStateException(
            "落账会把货币余额压到冻结额之下：key="
                + account.key()
                + " currency="
                + currency
                + " next="
                + next
                + " frozen="
                + frozen
                + " record="
                + record);
      }
      money.put(currency, next);
      return new GoodsAccount(
          account.key(),
          account.balances(),
          money,
          account.frozenBalances(),
          account.frozenMoney());
    }
    CommodityId commodity = channel.commodity();
    Map<CommodityId, Long> balances = new LinkedHashMap<>(account.balances());
    long next = Math.addExact(balances.getOrDefault(commodity, 0L), delta);
    requireNotNegative(next, "商品", commodity.value(), account.key(), record);
    long frozen = account.frozenBalances().getOrDefault(commodity, 0L);
    if (next < frozen) {
      throw new IllegalStateException(
          "落账会把商品余额压到冻结额之下：key="
              + account.key()
              + " commodity="
              + commodity
              + " next="
              + next
              + " frozen="
              + frozen
              + " record="
              + record);
    }
    balances.put(commodity, next);
    return new GoodsAccount(
        account.key(), balances, account.money(), account.frozenBalances(), account.frozenMoney());
  }

  private static long availableOf(GoodsAccount account, Channel channel) {
    if (channel.isMoney()) {
      CurrencyId currency = channel.currency();
      return account.money().getOrDefault(currency, 0L)
          - account.frozenMoney().getOrDefault(currency, 0L);
    }
    CommodityId commodity = channel.commodity();
    return account.balances().getOrDefault(commodity, 0L)
        - account.frozenBalances().getOrDefault(commodity, 0L);
  }

  private static void requireNotNegative(
      long value,
      String dimension,
      String dimensionId,
      GoodsAccountKey key,
      ClassFirstSettlement.AccountDelta record) {
    if (value < 0L) {
      throw new IllegalStateException(
          "落账产生负余额（"
              + dimension
              + " "
              + dimensionId
              + "）：key="
              + key
              + " value="
              + value
              + " record="
              + record);
    }
  }
}
