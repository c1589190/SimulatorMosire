package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.time.AccountPartitionKey;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.AccountSession.ActorAccount;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionSettlement.ActorEntry;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>产权落账</b>（S1 阶段 4+5 Task 5；S1 起账户会话统一）：把一天/{@code ProductionLedger} 交出来的 {@link ActorEntry}
 * 落到 {@link ActorData#accounts()} 上 —— <b>纯函数、无 IO、不写状态</b>。
 *
 * <p>★★ <b>它为什么住在 {@code simos-app}</b>（铁律 3/4）：产权条目由 <b>economy</b> 产出，而账本住在 <b>{@code actor}</b>
 * 切片 —— 两个切片互不认识 ⇒ 会合点只能是组合根。
 *
 * <p>★★ <b>S1 的三条新口径</b>：
 *
 * <ol>
 *   <li><b>账户会话唯一</b>：{@link #loadAccountSession(EconomyData, ActorData)} / {@link
 *       #landAccountSession(ActorData, AccountSession)} 一次装载/落回<b>全部主体</b> （家户 + 经营者 + 将来的
 *       GOV/UNIT），键恒为 {@code (ActorRef, HexCoord)}。旧的四张会话地图与四张 frozen 表的平行装载/落回已删除（它们只是同一份账的不同切面）；
 *   <li><b>{@link #apply} 批处理</b>（P1.4）：先把条目按 {@link GoodsAccountKey} 聚合，再按**首次入账序**逐账户一次 {@code
 *       withAccount}；每条条目的**前缀余额**仍逐条校验（与旧逐条实现同一处抛点），故入账序的中间态语义不变；
 *   <li><b>绝对值落回</b>：{@link #landAccountSession} 按会话的绝对值一次 {@code withAccounts} 写回全部账
 *       （家户的商品/货币/冻结与经营者的四张表一起），不再有"先商品后货币"的顺序约定 —— 同一本账一次写全。
 * </ol>
 */
public final class OwnershipBooks {

  private OwnershipBooks() {}

  /**
   * ★★ <b>折进 actor 账时要排除的转移原因</b>：{@link TransferReason#MARKET_TRADE}（机制不变：市场成交落在账户上的
   * 那一份由会话绝对值落回覆盖；在途那一份由 {@code ShipmentBatch} 承载；再叠会在异地键上造幽灵账）。
   */
  public static final Set<TransferReason> REASONS_NOT_FOLDED = Set.of(TransferReason.MARKET_TRADE);

  /** 把当天的账折成产权条目（唯一拼写点）。 */
  public static List<ActorEntry> fold(ProductionLedger ledger) {
    return fold(ledger, REASONS_NOT_FOLDED);
  }

  /** 折账的带过滤版本。 */
  public static List<ActorEntry> fold(
      ProductionLedger ledger, Set<TransferReason> excludedReasons) {
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(excludedReasons, "excludedReasons");
    List<ActorEntry> entries = new ArrayList<>(ledger.outputAccruals());
    for (Transfer transfer : ledger.transfers()) {
      if (excludedReasons.contains(transfer.reason())) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        long amount = leg.getValue();
        if (amount <= 0L) {
          continue;
        }
        entries.add(new ActorEntry(transfer.from(), transfer.location(), leg.getKey(), -amount));
        entries.add(new ActorEntry(transfer.to(), transfer.location(), leg.getKey(), amount));
      }
    }
    return entries;
  }

  /**
   * ★★ <b>批处理落账</b>（P1.4）：先按 {@link GoodsAccountKey} 聚合 delta，再按**首次入账序**逐账户一次写。
   *
   * <p>★ <b>逐条前缀校验保留</b>：聚合不改变"某条条目落下时那本账不能为负"的判据 —— 每条仍按它的入账序推一次前缀余额， 负数当场抛（与旧实现逐字同因）。★
   * <b>入账序保留</b>：外层 {@code LinkedHashMap} 的键序 = 该账户第一次出现的次序； 同一账户内商品按首次出现序。
   *
   * <p>★ <b>溢出</b>：前缀与终值都用 {@code Math.addExact}；溢出是坏数据，抛具名 {@link IllegalStateException}，不静默回绕。
   */
  public static ActorData apply(ActorData base, List<ActorEntry> entries) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(entries, "entries");
    Map<GoodsAccountKey, Map<CommodityId, Long>> deltas = new LinkedHashMap<>();
    Map<GoodsAccountKey, Map<CommodityId, Long>> prefix = new LinkedHashMap<>();
    for (ActorEntry entry : entries) {
      GoodsAccountKey key = new GoodsAccountKey(entry.actor(), entry.location());
      deltas
          .computeIfAbsent(key, ignored -> new LinkedHashMap<>())
          .merge(entry.commodity(), entry.delta(), Long::sum);
      long baseline = 0L;
      GoodsAccount account = base.accounts().get(key);
      if (account != null) {
        baseline = account.balances().getOrDefault(entry.commodity(), 0L);
      }
      long running =
          prefix
              .computeIfAbsent(key, ignored -> new LinkedHashMap<>())
              .merge(entry.commodity(), entry.delta(), Math::addExact);
      long after;
      try {
        after = Math.addExact(baseline, running);
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException(
            "产权账前缀余额溢出（坏数据，拒绝回绕）：actor="
                + entry.actor()
                + " 格="
                + entry.location()
                + " 商品="
                + entry.commodity(),
            overflow);
      }
      if (after < 0L) {
        throw new IllegalStateException(
            "产权账余额不得为负（透支是信用，不是库存）：actor="
                + entry.actor()
                + " 格="
                + entry.location()
                + " 商品="
                + entry.commodity()
                + " 余额 "
                + baseline
                + " + 本轮累计 "
                + running
                + " = "
                + after);
      }
    }
    if (deltas.isEmpty()) {
      return base;
    }
    // ★★ R1 / P1.4 的"最终按 canonical 顺序合并"：逐条入账序（前缀校验）保持上面的顺序，但**写回 actor 的批次**
    //   按账户 canonical 串升序执行 —— 新增账户的插入序因此是内容的纯函数（1/4/8 线程/重放同序），
    //   而既有账户在 LinkedHashMap 里保持原位置（put 不改既有键序）。★ 同一账户内的商品序仍是首次入账序（见上）。
    List<Map.Entry<GoodsAccountKey, Map<CommodityId, Long>>> canonicalOrder =
        new ArrayList<>(deltas.entrySet());
    canonicalOrder.sort(java.util.Comparator.comparing(entry -> entry.getKey().toString()));
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(base.accounts());
    for (Map.Entry<GoodsAccountKey, Map<CommodityId, Long>> entry : canonicalOrder) {
      GoodsAccountKey key = entry.getKey();
      GoodsAccount account = base.accounts().get(key);
      Map<CommodityId, Long> balances =
          account == null ? new LinkedHashMap<>() : new LinkedHashMap<>(account.balances());
      for (Map.Entry<CommodityId, Long> delta : entry.getValue().entrySet()) {
        long before = balances.getOrDefault(delta.getKey(), 0L);
        long after = Math.addExact(before, delta.getValue());
        if (after < 0L) {
          throw new IllegalStateException(
              "产权账余额不得为负（透支是信用，不是库存）：actor="
                  + key.owner()
                  + " 格="
                  + key.location()
                  + " 商品="
                  + delta.getKey()
                  + " 余额 "
                  + before
                  + " + "
                  + delta.getValue()
                  + " = "
                  + after);
        }
        balances.put(delta.getKey(), after);
      }
      Map<CurrencyId, Long> money = account == null ? Map.of() : account.money();
      Map<CommodityId, Long> frozenBalances = account == null ? Map.of() : account.frozenBalances();
      Map<CurrencyId, Long> frozenMoney = account == null ? Map.of() : account.frozenMoney();
      // ★★ 整本覆盖必须把货币与两张冻结表带过（漏带 = 静默清零）。
      accounts.put(key, new GoodsAccount(key, balances, money, frozenBalances, frozenMoney));
    }
    return base.withAccounts(accounts);
  }

  /**
   * ★★ <b>装载唯一账户会话</b>（S1）：家户（以 economy 的行集为驱动，缺席 ⇒ 抛） + 经营者 （以产业 operator 的格为驱动，缺席合法 ——
   * 这个世界还没给它播种）。
   *
   * <p>★ 家户 actor 的 id 由 {@link HouseholdActors#of(HouseholdId)} 给出（唯一拼写点）；账的 location =
   * 行视图的格（搬迁不改账 location，S1.3）。
   */
  @SuppressWarnings(
      "deprecation") // ★ S1.5：旧三段 actor id 的兼容读只在这里（HouseholdActors.of(CohortKey) 已标退役）
  public static AccountSession loadAccountSession(EconomyData economy, ActorData books) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(books, "books");
    AccountSession session = AccountSession.empty();
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : economy.classes().entrySet()) {
      HouseholdId household = entry.getKey();
      ClassRow row = entry.getValue();
      HexCoord location = row.view().hex();
      ActorRef actor = HouseholdActors.of(household);
      GoodsAccount account = books.accounts().get(new GoodsAccountKey(actor, location));
      if (account == null) {
        // ★★ S1.5 旧档：actor 侧的三段 id 路径（{@code 0_0:rural:poor_peasant}）没有 {@code legacy-} 前缀，
        //   而 economy 侧迁移后的身份是 {@code legacy-0_0|rural|poor_peasant}（actor id 为
        //   {@code legacy-0_0:rural:...}）。⇒ 新键取不到时按旧视图再找一次；这是**迁移期的唯一兼容读**，
        //   运行期新档 id（{@code hh-…}）没有 legacyView ⇒ 这一步恒不触发。
        var legacyView = household.legacyView();
        if (legacyView.isPresent()) {
          ActorRef legacyActor = HouseholdActors.of(legacyView.get());
          account = books.accounts().get(new GoodsAccountKey(legacyActor, location));
        }
      }
      if (account == null) {
        missing.add(household + "（" + location + "）");
        continue;
      }
      session.registerHousehold(
          household,
          actor,
          location,
          new LinkedHashMap<>(account.balances()),
          new LinkedHashMap<>(account.money()),
          new LinkedHashMap<>(account.frozenBalances()),
          new LinkedHashMap<>(account.frozenMoney()));
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "家户 actor / 账本缺失 "
              + missing.size()
              + " 个（家户账是日结算的唯一读口，缺了不能当库存 0 —— S1 的播种应为"
              + "「每格 × 两组四行」一个不少，含人口 0 的空账）："
              + missing.subList(0, Math.min(5, missing.size()))
              + (missing.size() > 5 ? " …" : ""));
    }
    for (Map.Entry<ActorRef, HexCoord> entry : operatorLocations(economy).entrySet()) {
      GoodsAccount account =
          books.accounts().get(new GoodsAccountKey(entry.getKey(), entry.getValue()));
      if (account == null) {
        continue; // 缺席合法：手搭夹具 / 这个世界还没给经营者播种
      }
      session.registerOperator(
          entry.getKey(),
          entry.getValue(),
          new LinkedHashMap<>(account.balances()),
          new LinkedHashMap<>(account.money()),
          new LinkedHashMap<>(account.frozenBalances()),
          new LinkedHashMap<>(account.frozenMoney()));
    }
    return session;
  }

  /**
   * ★★ <b>按绝对值一次落回全部账户</b>（S1）：会话里每本账的四张表一起写回那一本 {@code GoodsAccount} ——
   * 不再有"先商品后货币"的顺序约定（同一本账一次写全），也没有第二处落账路径。
   */
  public static ActorData landAccountSession(ActorData books, AccountSession session) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(session, "session");
    session.checkCoordinatorThread();
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(books.accounts());
    for (Map.Entry<AccountPartitionKey, ActorAccount> entry : session.accounts().entrySet()) {
      AccountPartitionKey sessionKey = entry.getKey();
      ActorAccount account = entry.getValue();
      validateNonNegative(sessionKey, account);
      GoodsAccountKey key = new GoodsAccountKey(sessionKey.actor(), sessionKey.location());
      accounts.put(
          key,
          new GoodsAccount(
              key,
              new LinkedHashMap<>(account.goods()),
              new LinkedHashMap<>(account.money()),
              new LinkedHashMap<>(account.frozenGoods()),
              new LinkedHashMap<>(account.frozenMoney())));
    }
    return books.withAccounts(accounts);
  }

  /**
   * ★★ <b>S1.5 旧档 actor 账户搬家</b>（旧三段 actor id → 新 {@link HouseholdId} 派生的 actor id）：把旧键上的 {@link
   * GoodsAccount} 整体搬到新键（<b>余额 / 货币 / 冻结逐值带过，location 不变</b>），并删掉旧键。
   *
   * <p>★★ <b>为什么必须"搬"而不是"再找一次"</b>：旧档里同一笔余额若只读不搬，新键会在 {@code landAccountSession} 写出一本新账、旧键那本还留着 ⇒
   * 同一笔粮变成两本账（守恒式当场失真）。故这里是 <b>移动</b>：新键已存在 ⇒ 抛（说不清哪本是权威）；旧键不存在 ⇒ 幂等跳过（新档与新档重放都不受影响）。
   *
   * <p>★ 只认"economy 侧仍是 legacy 身份、且旧 actor id 能由旧视图拼出"的家户；非 legacy 家户 / 无旧账 ⇒ 不动。 ★ 旧 actor
   * 行（{@code actors} 表）不在这里删 —— 主体行的退役由旧档迁移的后续阶段处理；本方法只保证 <b>账户不被复制成两本</b>。
   */
  @SuppressWarnings("deprecation") // ★ S1.5：旧 actor id 的搬家是迁移期唯一允许触碰退役 API 的地方
  public static ActorData migrateLegacyHouseholdAccounts(ActorData books, EconomyData economy) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(economy, "economy");
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(books.accounts());
    boolean changed = false;
    for (Map.Entry<io.mosire.simos.economy.api.id.HouseholdId, ClassRow> entry :
        economy.classes().entrySet()) {
      io.mosire.simos.economy.api.id.HouseholdId household = entry.getKey();
      var legacyView = household.legacyView();
      if (legacyView.isEmpty()) {
        continue;
      }
      HexCoord location = entry.getValue().view().hex();
      GoodsAccountKey oldKey = new GoodsAccountKey(HouseholdActors.of(legacyView.get()), location);
      GoodsAccount oldAccount = accounts.get(oldKey);
      if (oldAccount == null) {
        continue; // 幂等：旧账已经搬过 / 这本就是新档
      }
      GoodsAccountKey newKey = new GoodsAccountKey(HouseholdActors.of(household), location);
      if (accounts.containsKey(newKey)) {
        throw new IllegalStateException(
            "旧档账户搬家失败：新旧两把键都有账，无法判断哪本是权威（拒绝静默合并）：旧=" + oldKey + " 新=" + newKey);
      }
      accounts.remove(oldKey);
      accounts.put(
          newKey,
          new GoodsAccount(
              newKey,
              new LinkedHashMap<>(oldAccount.balances()),
              new LinkedHashMap<>(oldAccount.money()),
              new LinkedHashMap<>(oldAccount.frozenBalances()),
              new LinkedHashMap<>(oldAccount.frozenMoney())));
      changed = true;
    }
    return changed ? books.withAccounts(accounts) : books;
  }

  /** 落回前的负余额守卫（快照里不该有负数：透支是信用，不是库存）。 */
  private static void validateNonNegative(AccountPartitionKey key, ActorAccount account) {
    for (Map.Entry<CommodityId, Long> entry : account.goods().entrySet()) {
      if (entry.getValue() < 0L) {
        throw new IllegalStateException(
            "账户商品余额不得为负（透支是信用，不是库存）：actor="
                + key.actor()
                + " 格="
                + key.location()
                + " 商品="
                + entry.getKey()
                + " 余额="
                + entry.getValue());
      }
    }
    for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
      if (entry.getValue() < 0L) {
        throw new IllegalStateException(
            "账户货币余额不得为负（透支是信用，不是货币）：actor="
                + key.actor()
                + " 格="
                + key.location()
                + " 币种="
                + entry.getKey()
                + " 余额="
                + entry.getValue());
      }
    }
  }

  /**
   * ★★ <b>经营者 actor → 它那一格</b>（H5）：逐产业取 {@code (operator, 产业 id 的格)}； 一个主体只可能有一格（{@code
   * RegimeOperators} 用产业 id 当主体 id）。
   */
  public static Map<ActorRef, HexCoord> operatorLocations(EconomyData economy) {
    Objects.requireNonNull(economy, "economy");
    Map<ActorRef, HexCoord> locations = new LinkedHashMap<>();
    for (IndustryId id : economy.industries().keySet()) {
      Optional<HexCoord> hex = IndustryHexKeys.hexKeyOf(id).map(HexCoord::parse);
      if (hex.isEmpty()) {
        continue; // 产业 id 里没有格键（手搭状态）：说不出账户在哪一格 ⇒ 不进表
      }
      locations.put(economy.industries().get(id).operator(), hex.get());
    }
    return locations;
  }

  /**
   * 一个家户的账本键：{@code (HouseholdActors.of(household), location)} —— <b>本类里唯一的拼写点</b>。 location 由调用方从
   * {@code ClassRow.view().hex()} 取（搬迁不改账 location，S1.3）。
   */
  public static GoodsAccountKey accountKeyOf(HouseholdId household, HexCoord location) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(location, "location");
    return new GoodsAccountKey(HouseholdActors.of(household), location);
  }

  // ── 冻结 / 解冻（M1.2；语义与旧实现逐条相同）────────────────────────────────────────

  /** ★★ 冻结一笔商品：把 {@code key} 这本账上 {@code commodity} 的冻结额**置为** {@code amount}（幂等）。 */
  public static ActorData freeze(
      ActorData books, GoodsAccountKey key, CommodityId commodity, long amount) {
    return withFrozenGoods(books, key, commodity, amount);
  }

  /** ★★ 解冻一笔商品 = 置 0（保留那条 0）。 */
  public static ActorData release(ActorData books, GoodsAccountKey key, CommodityId commodity) {
    return withFrozenGoods(books, key, commodity, 0L);
  }

  /** ★★ 冻结一笔货币。 */
  public static ActorData freeze(
      ActorData books, GoodsAccountKey key, CurrencyId currency, long amount) {
    return withFrozenMoney(books, key, currency, amount);
  }

  /** ★★ 解冻一笔货币 = 置 0。 */
  public static ActorData release(ActorData books, GoodsAccountKey key, CurrencyId currency) {
    return withFrozenMoney(books, key, currency, 0L);
  }

  private static ActorData withFrozenGoods(
      ActorData books, GoodsAccountKey key, CommodityId commodity, long amount) {
    GoodsAccount account = requireAccount(books, key);
    Map<CommodityId, Long> frozen = new LinkedHashMap<>(account.frozenBalances());
    frozen.put(commodity, amount);
    return books.withAccount(
        new GoodsAccount(key, account.balances(), account.money(), frozen, account.frozenMoney()));
  }

  private static ActorData withFrozenMoney(
      ActorData books, GoodsAccountKey key, CurrencyId currency, long amount) {
    GoodsAccount account = requireAccount(books, key);
    Map<CurrencyId, Long> frozen = new LinkedHashMap<>(account.frozenMoney());
    frozen.put(currency, amount);
    return books.withAccount(
        new GoodsAccount(
            key, account.balances(), account.money(), account.frozenBalances(), frozen));
  }

  /** 目标账本（缺席 ⇒ 抛：冻结不是"对不存在的账下处置"）。 */
  private static GoodsAccount requireAccount(ActorData books, GoodsAccountKey key) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(key, "key");
    GoodsAccount account = books.accounts().get(key);
    if (account == null) {
      throw new IllegalStateException("冻结/解冻要求该账本已在 actor 侧存在（对不存在的账冻结 = 凭空造账）：键=" + key);
    }
    return account;
  }
}
