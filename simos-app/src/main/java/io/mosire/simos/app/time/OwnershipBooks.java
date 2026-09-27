package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionSettlement.ActorEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>产权落账</b>（S1 阶段 4+5 Task 5）：把一天/{@code ProductionLedger} 交出来的 {@link ActorEntry} 落到 {@link
 * ActorData#accounts()} 上 —— <b>纯函数、无 IO、不写状态</b>。
 *
 * <p>★★ <b>它为什么住在 {@code simos-app}</b>（铁律 3/4）：产权条目由 <b>economy</b> 产出（{@code
 * ProductionSettlement} 算、 {@code harvest} 收进 ledger），而账本住在 <b>{@code actor}</b> 切片 ——
 * 两个切片<b>互不认识</b> ⇒ 把两者接起来的地方 只能是<b>组合根</b>（设计稿 §8.2 的原文："跨切片的协调器必须住 {@code simos-app}"）。
 *
 * <p>★★ <b>三条口径</b>（每条都有一条用例守着）：
 *
 * <ol>
 *   <li><b>账户键 = (actor, location)，从值派生</b>：{@link GoodsAccountKey} 由条目自己的两段构造，写入走 {@link
 *       ActorData#withAccount}（<b>本类不自己拼键、也不碰那张 Map</b>）—— 否则"键是 (actor, location)"
 *       这件事就有了第二个拼写点，而它写歪<b>不会报错</b>（测试会恒真）；
 *   <li><b>余额只在被写的商品上覆盖</b>：{@code 余额' = 原余额 + delta}，其余商品<b>原样带过</b> ⇒ 同一 actor 在两地的
 *       两本账、以及一本账里的多种商品**互不抹除**（{@link GoodsAccount} 是<b>整本覆盖</b>的写入口，故副本必须先拷全）；
 *   <li>★★ <b>余额不得为负</b>：{@link GoodsAccount} 的构造期守卫已经兜了一层，本类<b>再判一层并抛</b>更早、更可读的错 （消息里带 <b>actor /
 *       商品 / 当前余额 / 本次增减</b>）—— 透支是<b>信用</b>（S2 的领域），不是库存。
 * </ol>
 *
 * <p>★★ <b>为什么"入账序"要保序</b>：条目按 {@code priority} 序产生（同一份产出可能被多条规则分）⇒ <b>先付后收</b>的次序 在账面上看得见。逐条
 * {@code withAccount} 天然保序（{@code put} 保留首次插入位置），故本条不需要额外代码 —— 但它是 <b>约定</b>，写在类注里免得后来者把它改掉。
 *
 * <p>★ <b>0 余额保留</b>（{@link GoodsAccount} 的口径）：收支相抵的账户**照样留在表里**，读口因此读得到"这个人在这一格有一本 （余额 0）的账" ——
 * 那与"这个人不在这格"是两件事。
 */
public final class OwnershipBooks {

  private OwnershipBooks() {}

  /**
   * 把一批产权条目落到账本上（**逐条累加**；{@code base} 一字不改）。
   *
   * @param base 落账前的 actor 状态；不得为 null
   * @param entries 产权条目（毫单位；{@code > 0} 收、{@code < 0} 付）；不得为 null（没有条目请给空表）
   * @return 落账后的新状态（**只有被写到的账户被替换**，其余原样带过）
   * @throws IllegalStateException 某本账的余额会变成负数（见类注第 ③ 条）
   */
  /**
   * ★★ <b>把当天的账折成"产权条目"——本折算的<b>唯一拼写点</b></b>（H2 / 裁定 D2-A 的 app 侧收口）。
   *
   * <p>H2 起，全系统的"东西从 A 到 B"只有一种事实：{@link Transfer}（`simos-economy-api`）。 而 actor 切片的账本写入口 {@link
   * #apply} 收的是**一腿一条**的 {@link ActorEntry}。 ⇒ 折算规则只写在这里，两个时间参与者与测试**都调它**（别处再折一遍就是同一个格式的第二处拼写点）。
   *
   * <p>★ <b>两样东西，两种折法</b>：
   *
   * <ul>
   *   <li>{@link ProductionLedger#outputAccruals()} —— <b>产出计提</b>（净产 → operator）**原样带过**：
   *       它**不是转移**（产出没有对端，而 {@link Transfer} 明令 `from ≠ to`）；
   *   <li>{@link ProductionLedger#transfers()} —— 每条转移的**每个商品腿**折成**两条**条目： 付方 {@code −amount}、收方
   *       {@code +amount}（两端同一格 = `transfer.location()`）。 ★★ <b>货币腿仍然不在这里折</b>（H4 的口径变了、结论没变）：
   *       家户的货币账**住同一本 {@code GoodsAccount}**，而它的权威值是**工作副本**（{@code EconomyDayStepper} 就地更新 ——
   *       市场成交与工钱的货币腿都写在副本上）⇒ 落盘走 {@link #landHouseholdMoney} 的**绝对值**那一条路。 在这里再折一遍货币腿 =
   *       同一笔钱记两遍（随后被绝对值覆盖，于是**只有付方那半**留下痕迹）。 ★ <b>如实记的边界</b>：本批的货币副本只覆盖**家户**（键 = {@code
   *       CohortKey}）⇒ 非家户主体（庄园/作坊经营者）的 货币余额**没有落点**；"经营者自己持账"是 H5 的事（与产出归属同一条账）。
   * </ul>
   *
   * <p>★★ <b>别在这一步把家户账"叠加"一遍</b>：家户那一端的余额由 {@link #landHouseholdGoods} 按**副本绝对值**写回（日耗 / 投入 /
   * 同格取材只写副本，它们不是条目） ⇒ 这里是"产权条目"的落点，那里是"家户账"的落点，两处不重叠。
   */
  public static List<ActorEntry> fold(ProductionLedger ledger) {
    Objects.requireNonNull(ledger, "ledger");
    List<ActorEntry> entries = new ArrayList<>(ledger.outputAccruals());
    for (Transfer transfer : ledger.transfers()) {
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        long amount = leg.getValue();
        if (amount <= 0L) {
          continue; // 0 腿不产生条目（同既有的"实付 0 ⇒ 不产生条目"口径）
        }
        entries.add(new ActorEntry(transfer.from(), transfer.location(), leg.getKey(), -amount));
        entries.add(new ActorEntry(transfer.to(), transfer.location(), leg.getKey(), amount));
      }
    }
    return entries;
  }

  public static ActorData apply(ActorData base, List<ActorEntry> entries) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(entries, "entries");
    ActorData books = base;
    for (ActorEntry entry : entries) {
      GoodsAccountKey key = new GoodsAccountKey(entry.actor(), entry.location());
      GoodsAccount account = books.accounts().get(key);
      // ★ 整本覆盖的写入口 ⇒ 必须先拷全原余额（其余商品原样带过；本账缺席就是一本空账）。
      Map<CommodityId, Long> balances =
          account == null ? new LinkedHashMap<>() : new LinkedHashMap<>(account.balances());
      long before = balances.getOrDefault(entry.commodity(), 0L);
      long after = before + entry.delta();
      if (after < 0L) {
        throw new IllegalStateException(
            "产权账余额不得为负（透支是信用，不是库存）：actor="
                + entry.actor()
                + " 格="
                + entry.location()
                + " 商品="
                + entry.commodity()
                + " 余额 "
                + before
                + " + "
                + entry.delta()
                + " = "
                + after);
      }
      balances.put(entry.commodity(), after);
      // ★★ H4/K15：**整本覆盖必须把货币带过** —— 两参构造器给的是"钱 = 空表"，
      //   用它写回会**静默把钱清零**（今天只靠"货币落回排在后面"兜住，任何只调 apply 的新路径都会中招）。
      Map<CurrencyId, Long> money = account == null ? Map.of() : account.money();
      books = books.withAccount(new GoodsAccount(key, balances, money));
    }
    return books;
  }

  /**
   * ★★ <b>把家户账载入成<strong>会话工作副本</strong></b>（H1；裁定 K1 / D3-C）—— 供 {@code EconomyDayStepper}
   * 的构造器用（它按 {@code population > 0} 的行读这本账）。
   *
   * <pre>
   * 副本键 = {@code CohortKey}（家户身份）；值 = 商品余额（**缺失键 = 该家户没有该商品**）
   * 取账   = {@code actor.accounts().get(new GoodsAccountKey(HouseholdActors.of(cohort), cohort.hex()))}
   * </pre>
   *
   * <p>★★ <b>为什么以"economy 的家户行集"为驱动、而不是扫全表</b>：{@code HOUSEHOLD} 这个种类**不只有家户** —— 家庭纺织产业的经营主体也是
   * {@code HOUSEHOLD}（id 形如 {@code weave@0_0}），它对 {@link HouseholdActors#cohortOf}
   * 是**非法输入**（那个契约宁抛不静默）。而那份副本的键集**本来就该等于行集** （日结算只按行查账）⇒ 按行集取账既不会误伤产业主体，也不会把"没有行的家户 actor"塞进副本。
   *
   * <p>★★ <b>载入不出来 ⇒ 抛</b>（fail-closed，冻结接口第 4 条）：某一行没有 actor / 没有账，说明播种漏了 （真档应为"每格 × 两组四行"一个不少）。★
   * <b>不许静默丢</b>：那本账会被当成"库存 0"—— 而"没有账"与"账是空的" 是两件完全不同的事（{@code GoodsAccount} 的"0 余额保留"口径），静默当 0
   * 正是本仓最反对的"静默付 0"。
   *
   * <p>★ 内层表是**可变的新表**（{@code LinkedHashMap}）：{@code EconomyDayStepper} 会就地更新这份副本 （它换值一律 {@code
   * put} 一张新表，不改旧表）。
   *
   * @param economy 家户行集（= 副本的键集）；不得为 null
   * @param books actor 切片当前状态（家户账的真源）；不得为 null
   * @throws IllegalStateException 行集里有家户在 actor 切片里没有账（消息点名前几个 + 总数）
   */
  public static Map<CohortKey, Map<CommodityId, Long>> loadHouseholdGoods(
      EconomyData economy, ActorData books) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(books, "books");
    Map<CohortKey, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    List<CohortKey> missing = new ArrayList<>();
    for (CohortKey cohort : economy.classes().keySet()) {
      GoodsAccount account = books.accounts().get(accountKeyOf(cohort));
      if (account == null) {
        missing.add(cohort);
        continue;
      }
      copy.put(cohort, new LinkedHashMap<>(account.balances()));
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "家户 actor / 账本缺失 "
              + missing.size()
              + " 个（家户账是日结算的唯一读口，缺了不能当库存 0 —— H1 的播种应为"
              + "「每格 × 两组四行」一个不少，含人口 0 的空账）："
              + missing.subList(0, Math.min(5, missing.size()))
              + (missing.size() > 5 ? " …" : ""));
    }
    return copy;
  }

  /**
   * ★★ <b>把家户账工作副本<strong>按绝对值</strong>落回 actor 切片</b>（H1；裁定 K1）。
   *
   * <p>★★ <b>为什么是"绝对值"而不是"再叠加一遍条目"</b>（economy 侧 H1 冻结的两条路，逐条对着写）：
   *
   * <ol>
   *   <li><b>日耗 / 投入 / 同格取材</b>只写进副本（它们<b>不是</b>产权条目）⇒ 不落回去就在账上消失；
   *   <li><b>关系实付给家户</b>既是 {@code ledger.actorEntries()} 里的一条，<b>也已经计进副本</b> ⇒ 落盘时按副本的
   *       绝对值写回即可，<b>不许</b>把条目再叠加到副本上（叠加 = 同一笔粮记两遍）。
   * </ol>
   *
   * <p>★ 于是本方法与 {@link #apply} 的分工是：{@link #apply} 管<b>条目</b>（operator 那一路的产出/实付，家户的条目也走它、
   * 随后被本方法的绝对值覆盖），本方法管<b>家户账的终值</b>。两条路都只写 {@code ActorData.accounts} 这一张表 —— 没有第二条"落账路径"（D1-A
   * 的好处）。
   *
   * <p>★ <b>0 余额保留</b>：副本里的 0 照写（读口因此读得到"这个家户在这一格有一本账"），不过滤、不归一。 ★ <b>批量写</b>（一次 {@code
   * withAccounts}）：真档 6392 本账 × 每天一次，逐本 {@code withAccount} 会是 O(n²)。
   *
   * @param books 落账前的 actor 状态；不得为 null
   * @param householdGoods 工作副本（键 = 家户身份；值 = 商品余额）；不得为 null
   * @return 落账后的新状态（家户账整本覆盖，其余账户原样带过）
   * @throws IllegalStateException 某本账的余额为负（副本不该出现负数：透支是信用，不是库存）
   */
  public static ActorData landHouseholdGoods(
      ActorData books, Map<CohortKey, Map<CommodityId, Long>> householdGoods) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(householdGoods, "householdGoods");
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(books.accounts());
    for (Map.Entry<CohortKey, Map<CommodityId, Long>> entry : householdGoods.entrySet()) {
      CohortKey cohort = entry.getKey();
      GoodsAccountKey key = accountKeyOf(cohort);
      for (Map.Entry<CommodityId, Long> balance : entry.getValue().entrySet()) {
        if (balance.getValue() < 0L) {
          throw new IllegalStateException(
              "家户账余额不得为负（透支是信用，不是库存）：家户="
                  + key.owner()
                  + " 格="
                  + key.location()
                  + " 商品="
                  + balance.getKey()
                  + " 余额="
                  + balance.getValue());
        }
      }
      // ★★ 同 ①：按绝对值写回**商品**，但钱要**原样带过**（别用两参构造器把它清零）。
      GoodsAccount existing = books.accounts().get(key);
      accounts.put(
          key,
          new GoodsAccount(key, entry.getValue(), existing == null ? Map.of() : existing.money()));
    }
    return books.withAccounts(accounts);
  }

  /**
   * ★★ <b>把家户的<strong>货币账</strong>载入成会话工作副本</b>（H4；裁定 K1 / K14）—— 供 {@code EconomyDayStepper}
   * 的第三个构造参数用（同格市场池按它算"谁买得起"）。
   *
   * <pre>
   * 副本键 = {@code CohortKey}（家户身份）；值 = 货币余额（**缺失键 = 该家户没有该币种**）
   * 取账   = {@code actor.accounts().get(new GoodsAccountKey(HouseholdActors.of(cohort), cohort.hex()))}
   * </pre>
   *
   * <p>★★ <b>与 {@link #loadHouseholdGoods} 逐字同形</b>（同一份 {@code OwnershipBooks}、同一个驱动集、同一条
   * fail-closed 口径）：货币不是"另一条落账路径"，它就是**同一本 {@code GoodsAccount} 的第二个余额表**（裁定 M2： {@code CurrencyId}
   * 与 {@code CommodityId} 各守各的守恒，但住同一本账）—— 故两条载入读的是**同一个**账户对象。
   *
   * <p>★ <b>为什么以"economy 的家户行集"为驱动</b>：理由与商品那条一字不差（{@code HOUSEHOLD} 这个种类不只有家户； 副本的键集本来就该等于行集）。★
   * <b>载入不出来 ⇒ 抛</b>：不许把"没有账"静默当成"没有钱"。
   *
   * @param economy 家户行集（= 副本的键集）；不得为 null
   * @param books actor 切片当前状态（家户账的真源）；不得为 null
   * @throws IllegalStateException 行集里有家户在 actor 切片里没有账（消息点名前几个 + 总数）
   */
  public static Map<CohortKey, Map<CurrencyId, Long>> loadHouseholdMoney(
      EconomyData economy, ActorData books) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(books, "books");
    Map<CohortKey, Map<CurrencyId, Long>> copy = new LinkedHashMap<>();
    List<CohortKey> missing = new ArrayList<>();
    for (CohortKey cohort : economy.classes().keySet()) {
      GoodsAccount account = books.accounts().get(accountKeyOf(cohort));
      if (account == null) {
        missing.add(cohort);
        continue;
      }
      copy.put(cohort, new LinkedHashMap<>(account.money()));
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "家户 actor / 账本缺失 "
              + missing.size()
              + " 个（货币账与商品账同住一本 GoodsAccount，缺了不能当余额 0 —— H1 的播种应为"
              + "「每格 × 两组四行」一个不少，含人口 0 的空账）："
              + missing.subList(0, Math.min(5, missing.size()))
              + (missing.size() > 5 ? " …" : ""));
    }
    return copy;
  }

  /**
   * ★★ <b>把家户的货币账工作副本<strong>按绝对值</strong>落回 actor 切片</b>（H4；裁定 K1 / K14）。
   *
   * <p>★★ <b>与 {@link #landHouseholdGoods} 逐字同形</b>：市场成交（商品反向、货币正向）与工钱/地租的**货币腿**只写进 副本（economy
   * 侧就地更新它），落盘时按副本的**绝对值**写回即可 —— **不许**把条目再叠加到副本上（叠加 = 同一笔钱 记两遍）。★
   * <b>顺序**不能**反</b>：本方法必须在该家户的**商品**落回之后调用（写的是同一本账的另一个余额表 ⇒ 它要把商品那一半原样带过，而那半必须是**已经落好的**）。
   *
   * <p>★ <b>0 余额保留</b>：副本里的 0 照写（读口因此读得到"这个家户有一本货币账"），不过滤、不归一。 ★ <b>批量写</b>（一次 {@code
   * withAccounts}）：真档 6392 本账，逐本 {@code withAccount} 会是 O(n²)。
   *
   * @param books 落账前的 actor 状态（**该家户的商品已经落好**）；不得为 null
   * @param householdMoney 货币工作副本（键 = 家户身份；值 = 币种余额）；不得为 null
   * @return 落账后的新状态（家户账的货币表整表覆盖，商品表与其余账户原样带过）
   * @throws IllegalStateException 某本账的余额为负（副本不该出现负数：透支是信用，不是货币）；或该家户的账本缺席 （说明商品那一半还没落 —— 顺序反了）
   */
  public static ActorData landHouseholdMoney(
      ActorData books, Map<CohortKey, Map<CurrencyId, Long>> householdMoney) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(householdMoney, "householdMoney");
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(books.accounts());
    for (Map.Entry<CohortKey, Map<CurrencyId, Long>> entry : householdMoney.entrySet()) {
      CohortKey cohort = entry.getKey();
      GoodsAccountKey key = accountKeyOf(cohort);
      for (Map.Entry<CurrencyId, Long> balance : entry.getValue().entrySet()) {
        if (balance.getValue() < 0L) {
          throw new IllegalStateException(
              "家户货币余额不得为负（透支是信用，不是货币）：家户="
                  + key.owner()
                  + " 格="
                  + key.location()
                  + " 币种="
                  + balance.getKey()
                  + " 余额="
                  + balance.getValue());
        }
      }
      GoodsAccount existing = accounts.get(key);
      if (existing == null) {
        // ★ fail-closed：货币与商品住**同一本账** ⇒ 账本缺席只可能是"商品那一步还没跑"（顺序反了），
        //   而不是"这本账不存在"。静默新建一本**没有商品**的账会把那一半悄悄抹掉。
        throw new IllegalStateException(
            "货币落回要求该家户的账本已在 actor 侧存在（商品落回在前，顺序不能反）：家户=" + key.owner() + " 格=" + key.location());
      }
      // ★ 商品那一半**原样带过**（`GoodsAccount` 是整本覆盖的写入口）。
      accounts.put(key, new GoodsAccount(key, existing.balances(), entry.getValue()));
    }
    return books.withAccounts(accounts);
  }

  /**
   * 一个家户的账本键：{@code (HouseholdActors.of(cohort), cohort.hex())} —— <b>本类里唯一的拼写点</b> （家户 actor 的 id
   * 拼法在 {@link HouseholdActors}，本类不复述）。
   */
  public static GoodsAccountKey accountKeyOf(CohortKey cohort) {
    return new GoodsAccountKey(HouseholdActors.of(cohort), cohort.hex());
  }
}
