package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.AccountPartitionKey;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.AccountSession.ActorAccount;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionLedger.ActorEntry;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li><b>{@link #apply} 批处理</b>（P1.4）：先把条目按 {@link HouseholdAccountKey} 聚合，再按**首次入账序**逐账户一次
 *       {@code withInventory}；每条条目的**前缀余额**仍逐条校验（与旧逐条实现同一处抛点），故入账序的中间态语义不变；
 *   <li><b>绝对值落回</b>：{@link #landAccountSession} 按会话的绝对值一次 {@code withInventories} 写回全部账
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
   * ★★ <b>批处理落账</b>（P1.4）：先按 {@link HouseholdAccountKey} 聚合 delta，再按**首次入账序**逐账户一次写。
   *
   * <p>★ <b>逐条前缀校验保留</b>：聚合不改变"某条条目落下时那本账不能为负"的判据 —— 每条仍按它的入账序推一次前缀余额， 负数当场抛（与旧实现逐字同因）。★
   * <b>入账序保留</b>：外层 {@code LinkedHashMap} 的键序 = 该账户第一次出现的次序； 同一账户内商品按首次出现序。
   *
   * <p>★ <b>溢出</b>：前缀与终值都用 {@code Math.addExact}；溢出是坏数据，抛具名 {@link IllegalStateException}，不静默回绕。
   */
  public static ActorData apply(ActorData base, List<ActorEntry> entries) {
    return apply(base, entries, Set.of());
  }

  /**
   * ★★ <b>带「会话已负责账户」过滤的批处理落账</b>（S3 缺陷修复）：日结算的账户会话是**绝对值**落回 actor 账本的， 凡是会话里登记过的 {@code (actor,
   * location)}，其当天终值都会被 {@link #landAccountSession} 整体覆盖； 因此这些账户上的 ledger 条目<b>不能再对 actor
   * 基准叠一遍</b>。
   *
   * <p>★★ <b>它修的是什么（实测）</b>：跨区在途到货（{@code deliverShipments}）当天只写会话副本、不产生 ledger 条目。
   * 买方当天在会话里拿到货后放贷，ledger 里出现一条 {@code LOAN_PRINCIPAL}；旧实现把这条也折到 actor 基准上， 而 actor 基准没有当天的到货（到货的
   * actor 侧落点就是会话绝对值）⇒ {@code OwnershipBooks.apply} 误报负余额 （实测 tick136：账户 grain 0 被 {@code -62333}
   * 扣成负数）。会话负责的账户跳过折叠后， 它们由绝对值落回统一收尾，而非会话账户（承运人、未播种的经营者等）仍按 ledger 条目逐笔落账。
   *
   * @param alreadyMaterialized 当天会由账户会话绝对值落回的账户键（{@link AccountSession#accounts()} 的键集）； {@code
   *     Set.of()} = 旧逐条落账口径（所有条目都折）
   */
  public static ActorData apply(
      ActorData base, List<ActorEntry> entries, Set<AccountPartitionKey> alreadyMaterialized) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(alreadyMaterialized, "alreadyMaterialized");
    Map<HouseholdAccountKey, Map<CommodityId, Long>> deltas = new LinkedHashMap<>();
    Map<HouseholdAccountKey, Map<CommodityId, Long>> prefix = new LinkedHashMap<>();
    for (ActorEntry entry : entries) {
      // ★★ P2-A §13.3：产权条目必须已经解析到家户（economy 的结算侧负责解析；这里不再有"非家户静默跳过"）。
      HouseholdAccountKey key = requireHouseholdKey(entry.actor());
      if (alreadyMaterialized.contains(new AccountPartitionKey(key.household()))) {
        continue; // ★ 会话负责：终值由 landAccountSession 的绝对值覆盖，这里不再叠一遍。
      }
      deltas
          .computeIfAbsent(key, ignored -> new LinkedHashMap<>())
          .merge(entry.commodity(), entry.delta(), Long::sum);
      long baseline = 0L;
      HouseholdInventory inventory = base.accounts().get(key);
      if (inventory != null) {
        baseline = inventory.balances().getOrDefault(entry.commodity(), 0L);
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
    List<Map.Entry<HouseholdAccountKey, Map<CommodityId, Long>>> canonicalOrder =
        new ArrayList<>(deltas.entrySet());
    canonicalOrder.sort(java.util.Comparator.comparing(entry -> entry.getKey().toString()));
    Map<HouseholdAccountKey, HouseholdInventory> inventories = new LinkedHashMap<>(base.accounts());
    for (Map.Entry<HouseholdAccountKey, Map<CommodityId, Long>> entry : canonicalOrder) {
      HouseholdAccountKey key = entry.getKey();
      HouseholdInventory inventory = base.accounts().get(key);
      Map<CommodityId, Long> balances =
          inventory == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inventory.balances());
      for (Map.Entry<CommodityId, Long> delta : entry.getValue().entrySet()) {
        long before = balances.getOrDefault(delta.getKey(), 0L);
        long after = Math.addExact(before, delta.getValue());
        if (after < 0L) {
          throw new IllegalStateException(
              "产权账余额不得为负（透支是信用，不是库存）：household="
                  + key.household()
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
      Map<CurrencyId, Long> money = inventory == null ? Map.of() : inventory.money();
      Map<CommodityId, Long> frozenBalances =
          inventory == null ? Map.of() : inventory.frozenBalances();
      Map<CurrencyId, Long> frozenMoney = inventory == null ? Map.of() : inventory.frozenMoney();
      // ★★ 整本覆盖必须把货币与两张冻结表带过（漏带 = 静默清零）。
      inventories.put(
          key, new HouseholdInventory(key, balances, money, frozenBalances, frozenMoney));
    }
    return base.withInventories(inventories);
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
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        economy.classes().entrySet()) {
      HouseholdId household = householdEconomyEntry.getKey();
      HouseholdEconomy householdEconomy = householdEconomyEntry.getValue();
      HexCoord location = householdEconomy.view().hex();
      // ★★ P2-A §13.3：一个家户一本账，键 = 家户身份（不再带格）。旧账户已报废、旧世界重建 ⇒ 无兼容回找。
      HouseholdInventory inventory = books.accounts().get(new HouseholdAccountKey(household));
      if (inventory == null) {
        missing.add(household + "（" + location + "）");
        continue;
      }
      session.registerHousehold(
          household,
          location,
          new LinkedHashMap<>(inventory.balances()),
          new LinkedHashMap<>(inventory.money()),
          new LinkedHashMap<>(inventory.frozenBalances()),
          new LinkedHashMap<>(inventory.frozenMoney()));
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
    // ★★ P2-A §13.3：庄园/作坊/商号/组织不再是账户主体 —— 它们的账户路径由 economy 侧解析到
    //   组织者/经营者家户；会话不再登记任何 operator 账（旧 operator 账户随旧世界报废）。
    return session;
  }

  /**
   * ★★ <b>按绝对值一次落回全部账户</b>（S1）：会话里每本账的四张表一起写回那一本 {@code HouseholdInventory} ——
   * 不再有"先商品后货币"的顺序约定（同一本账一次写全），也没有第二处落账路径。
   */
  public static ActorData landAccountSession(ActorData books, AccountSession session) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(session, "session");
    session.checkCoordinatorThread();
    Map<HouseholdAccountKey, HouseholdInventory> inventories =
        new LinkedHashMap<>(books.accounts());
    for (Map.Entry<AccountPartitionKey, ActorAccount> entry : session.accounts().entrySet()) {
      AccountPartitionKey sessionKey = entry.getKey();
      ActorAccount account = entry.getValue();
      validateNonNegative(sessionKey, account);
      HouseholdAccountKey key = new HouseholdAccountKey(sessionKey.household());
      inventories.put(
          key,
          new HouseholdInventory(
              key,
              new LinkedHashMap<>(account.goods()),
              new LinkedHashMap<>(account.money()),
              new LinkedHashMap<>(account.frozenGoods()),
              new LinkedHashMap<>(account.frozenMoney())));
    }
    return books.withInventories(inventories);
  }

  /** ★★ <b>一个家户的账本键 = 家户身份本身</b>（P2-A §13.3：一个家户一本账，键不再带 {@code HexCoord}）—— <b>本类里唯一的拼写点</b>。 */
  public static HouseholdAccountKey accountKeyOf(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    return new HouseholdAccountKey(household);
  }

  /**
   * 家户 actor 引用的家户身份；非 {@code HOUSEHOLD} actor ⇒ <b>具名拒绝</b>（P2-A §13.3：账户主体只有家户， 组织角色必须由 economy
   * 侧先解析到组织者/经营者家户；这里不再静默跳过、也不造 {@code retired-actor:} 占位键）。
   */
  private static HouseholdId requireHouseholdOf(ActorRef actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalStateException("产权账条目指名的不是家户主体（账户主体只有家户；庄园/作坊/商号必须解析到组织者/经营者家户）：" + actor);
    }
    return HouseholdActors.householdOf(actor);
  }

  /** ledger 条目落账用的键：家户 actor ⇒ 家户键；非家户 ⇒ 具名抛（见 {@link #apply}）。 */
  private static HouseholdAccountKey requireHouseholdKey(ActorRef actor) {
    return new HouseholdAccountKey(requireHouseholdOf(actor));
  }

  /** 落回前的负余额守卫（快照里不该有负数：透支是信用，不是库存）。 */
  private static void validateNonNegative(AccountPartitionKey key, ActorAccount account) {
    for (Map.Entry<CommodityId, Long> entry : account.goods().entrySet()) {
      if (entry.getValue() < 0L) {
        throw new IllegalStateException(
            "账户商品余额不得为负（透支是信用，不是库存）：household="
                + key.household()
                + " 商品="
                + entry.getKey()
                + " 余额="
                + entry.getValue());
      }
    }
    for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
      if (entry.getValue() < 0L) {
        throw new IllegalStateException(
            "账户货币余额不得为负（透支是信用，不是货币）：household="
                + key.household()
                + " 币种="
                + entry.getKey()
                + " 余额="
                + entry.getValue());
      }
    }
  }

  // ── 冻结 / 解冻（M1.2；语义与旧实现逐条相同）────────────────────────────────────────

  /** ★★ 冻结一笔商品：把 {@code key} 这本账上 {@code commodity} 的冻结额**置为** {@code amount}（幂等）。 */
  public static ActorData freeze(
      ActorData books, HouseholdAccountKey key, CommodityId commodity, long amount) {
    return withFrozenGoods(books, key, commodity, amount);
  }

  /** ★★ 解冻一笔商品 = 置 0（保留那条 0）。 */
  public static ActorData release(ActorData books, HouseholdAccountKey key, CommodityId commodity) {
    return withFrozenGoods(books, key, commodity, 0L);
  }

  /** ★★ 冻结一笔货币。 */
  public static ActorData freeze(
      ActorData books, HouseholdAccountKey key, CurrencyId currency, long amount) {
    return withFrozenMoney(books, key, currency, amount);
  }

  /** ★★ 解冻一笔货币 = 置 0。 */
  public static ActorData release(ActorData books, HouseholdAccountKey key, CurrencyId currency) {
    return withFrozenMoney(books, key, currency, 0L);
  }

  private static ActorData withFrozenGoods(
      ActorData books, HouseholdAccountKey key, CommodityId commodity, long amount) {
    HouseholdInventory inventory = requireInventory(books, key);
    Map<CommodityId, Long> frozen = new LinkedHashMap<>(inventory.frozenBalances());
    frozen.put(commodity, amount);
    return books.withInventory(
        new HouseholdInventory(
            key, inventory.balances(), inventory.money(), frozen, inventory.frozenMoney()));
  }

  private static ActorData withFrozenMoney(
      ActorData books, HouseholdAccountKey key, CurrencyId currency, long amount) {
    HouseholdInventory inventory = requireInventory(books, key);
    Map<CurrencyId, Long> frozen = new LinkedHashMap<>(inventory.frozenMoney());
    frozen.put(currency, amount);
    return books.withInventory(
        new HouseholdInventory(
            key, inventory.balances(), inventory.money(), inventory.frozenBalances(), frozen));
  }

  /** 目标账本（缺席 ⇒ 抛：冻结不是"对不存在的账下处置"）。 */
  private static HouseholdInventory requireInventory(ActorData books, HouseholdAccountKey key) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(key, "key");
    HouseholdInventory inventory = books.accounts().get(key);
    if (inventory == null) {
      throw new IllegalStateException("冻结/解冻要求该账本已在 actor 侧存在（对不存在的账冻结 = 凭空造账）：键=" + key);
    }
    return inventory;
  }
}
