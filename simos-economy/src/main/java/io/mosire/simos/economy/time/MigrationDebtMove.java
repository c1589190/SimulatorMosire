package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** P0.5-E1 机械拆分自 {@code ModeMigrationSettlement}；职责见类名与各方法注释。 */
final class MigrationDebtMove {

  private MigrationDebtMove() {}

  /** 迁移执行日志（与门面同一 migration 分类；logger 名仍唯一由 EconomyLog 拼写）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  static void moveMoney(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      CurrencyId currency,
      long amount) {
    Map<CurrencyId, Long> sourceMoney = accounts.householdMoney().get(source);
    Map<CurrencyId, Long> targetMoney = accounts.householdMoney().get(target);
    if (sourceMoney == null || targetMoney == null) {
      throw new IllegalStateException("迁移货币时账户不存在（拒绝静默丢钱）: " + source + " → " + target);
    }
    long balance = sourceMoney.getOrDefault(currency, 0L);
    if (amount > balance) {
      throw new IllegalStateException(
          "迁移货币超过源户余额（拒绝透支）: source=" + source + " need=" + amount + " balance=" + balance);
    }
    LinkedHashMap<CurrencyId, Long> nextSource = new LinkedHashMap<>(sourceMoney);
    putOrRemove(nextSource, currency, balance - amount);
    accounts.householdMoney().put(source, nextSource);
    LinkedHashMap<CurrencyId, Long> nextTarget = new LinkedHashMap<>(targetMoney);
    nextTarget.merge(currency, amount, Math::addExact);
    accounts.householdMoney().put(target, nextTarget);
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.29 §7 第 1 条）：商品库存随迁 —— 「默认带一部分库存过去」</b>。
   *
   * <pre>
   * 可移动商品 = 家户商品账里的**实物库存**（粮 / 纤维 / 布 / 工具 / 铁…），**不含** {@link HaulService#HAUL_COMMODITY}
   *              （运输服务不是可搬运的实物：它是"在这个格能交付的运力"的现货，跨格搬它等于凭空承诺别处的服务）
   * 逐商品取整  = 整户迁空（empties）⇒ 冻结之外的**剩余量全部**
   *              否则            ⇒ ⌊可用量 × 本笔人口 ÷ 迁出前人口⌋          （与货币/资产同式，余数留源户）
   * 可用量      = 商品账余额 − 该商品的冻结量（★ 冻结不越：付款承诺的那一份不得随人走）
   * 写路径      = {@code EconomySettlement.applyTransfer}（唯一写口，两遍式校验）；一条 move **一条转移**（多商品腿）
   * </pre>
   *
   * <p>★ <b>守恒</b>：两端都是已登记家户 ⇒ 该商品在**家户账总和**上一字不变（源减 = 目标增）；没有任何"凭空造/销毁"的腿。
   *
   * <p>★ <b>顺序</b>：本方法在同一源户的多笔 move 之间**逐笔读活表**（前面的笔已经把库存带走了）⇒ 与 D-023 §4 的"⌊剩余量 ×
   * 比例⌋、余数留源户"逐字一致（不是对同一份初值各算一遍）。
   */
  static void carryGoodsForMove(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      long popTake,
      long populationBefore,
      boolean empties,
      HexCoord targetHex,
      long day,
      ProductionLedger.Accumulator auditLedger,
      int moveOrdinal) {
    Map<CommodityId, Long> sourceGoods = accounts.householdGoods().getOrDefault(source, Map.of());
    if (sourceGoods.isEmpty()) {
      return;
    }
    Map<CommodityId, Long> frozenGoods =
        accounts.householdFrozenGoods().getOrDefault(source, Map.of());
    LinkedHashMap<CommodityId, Long> carried = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : sourceGoods.entrySet()) {
      CommodityId commodity = entry.getKey();
      if (!isMobileCommodity(commodity)) {
        continue;
      }
      long available = entry.getValue() - frozenGoods.getOrDefault(commodity, 0L);
      if (available <= 0L) {
        continue;
      }
      long take =
          empties
              ? available
              : Math.multiplyExact(available, popTake) / Math.max(1L, populationBefore);
      take = Math.min(take, available);
      if (take > 0L) {
        carried.put(commodity, take);
      }
    }
    if (carried.isEmpty()) {
      return;
    }
    ActorRef from = HouseholdActors.of(source);
    ActorRef to = HouseholdActors.of(target);
    Transfer transfer =
        auditLedger == null
            ? new Transfer(
                new TransferId("tr-" + day + "-migration-" + moveOrdinal),
                day,
                from,
                to,
                targetHex,
                carried,
                Map.of(),
                TransferReason.MIGRATION_CARRY,
                Optional.empty())
            : auditLedger.mint(
                from, to, targetHex, carried, Map.of(), TransferReason.MIGRATION_CARRY);
    EconomySettlement.applyTransfer(
        accounts.householdGoods(),
        accounts.householdMoney(),
        accounts.householdFrozenGoods(),
        accounts.householdFrozenMoney(),
        accounts.householdOfActor(),
        transfer);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MIGRATION_INVENTORY_CARRIED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "target",
                target.value(),
                "targetHex",
                targetHex,
                "population",
                popTake,
                "goods",
                carried,
                "reason",
                "mobile-commodity-inventory-carried-by-population-ratio"));
  }

  /**
   * ★★ <b>W9：哪些商品算"可移动"（唯一拼写点）</b> —— 家户商品账里的**实物库存**都可以随人走（粮、纤维、布、工具、铁…）； <b>运输服务</b>（{@link
   * HaulService#HAUL_COMMODITY}）**不算**：它是"在本格能交付的运力"现货（服务不入库随迁）， 把它搬到另一个格等于凭空承诺那边的服务。
   */
  static boolean isMobileCommodity(CommodityId commodity) {
    return commodity != null && !HaulService.HAUL_COMMODITY.equals(commodity);
  }

  /**
   * ★★ <b>P11.1 / D-023：旧 / 手工计划（{@code moneyByCurrency} 为空）的全币种兜底</b> —— 按当前余额逐币种 独立切：{@code share
   * = ⌊余额 × popTake ÷ 迁出前人口⌋}（源户迁空 ⇒ 该币种余额全走），余数留源户。<b>不做 FX</b>， 逐币种并列存在。
   */
  static void moveAllCurrenciesByPopulation(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      long populationBefore,
      long popTake,
      boolean empties) {
    Map<CurrencyId, Long> initialBalances =
        accounts.householdMoney().getOrDefault(source, Map.of());
    List<CurrencyId> currencies = new ArrayList<>(initialBalances.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long balance =
          accounts.householdMoney().getOrDefault(source, Map.of()).getOrDefault(currency, 0L);
      if (balance <= 0L) {
        continue;
      }
      long share =
          empties ? balance : Math.multiplyExact(balance, popTake) / Math.max(1L, populationBefore);
      if (share <= 0L) {
        continue;
      }
      if (share > balance) {
        throw new IllegalStateException(
            "迁移货币超过源户余额（拒绝透支）: source="
                + source
                + " currency="
                + currency
                + " need="
                + share
                + " balance="
                + balance);
      }
      moveMoney(accounts, source, target, currency, share);
    }
  }

  /**
   * ★★ <b>把一笔 move 的债务份额从源户搬到目标户</b>（逐合同，金额按计划分摊）。
   *
   * <p>★★ <b>D3（2026-10-09）：目标恰是债权人的那部分 ⇒ 自债净额，不落合同</b>。旧实现把它 upsert 回 <b>源户</b>
   * （注释直言"人口清零时会因残留债具名抛"）—— 于是"源户人口归零"与"这笔债的债权人正是迁入目标"同时成立时， {@code retireSource} 的"拒绝消亡丢账"守卫当场
   * ERROR 500（三区世界 day=270 实测：source={@code hh--3_3-urban-middle_peasant}，target=creditor={@code
   * hh--3_3-rural-poor_peasant}，grain=323）。
   *
   * <p>净额口径取自全仓唯一实现 {@link DebtPartyResolver}：「自债（debtor == creditor）显式净额、不落合同 ——
   * 同户对自己的债权无经济意义，且还款会铸出'两端相等'的非法转移」。债务本金按人口比例随迁，这笔份额对应的人已经并入 债权人户 ⇒
   * 债权与负债落在同一个家户里互相抵消，两边同时减少、净值不变。**不静默**：INFO 事件 + 当天 ledger 的具名读数。
   */
  static void moveDebt(
      ModeMigrationPolicy.MigrationMove move,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    List<DebtContract> contracts = new ArrayList<>();
    long totalPrincipal = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(move.source()) && contract.principal() > 0L) {
        contracts.add(contract);
        totalPrincipal = Math.addExact(totalPrincipal, contract.principal());
      }
    }
    contracts.sort(Comparator.comparing(contract -> contract.id().value()));
    long[] takes = distributeDebt(move, contracts, totalPrincipal);
    long selfNetted = 0L;
    long moved = 0L;
    for (int i = 0; i < contracts.size(); i++) {
      DebtContract contract = contracts.get(i);
      long take = takes[i];
      if (take <= 0L) {
        continue;
      }
      moved = Math.addExact(moved, take);
      DebtContractBook.reduce(debts, contract.id(), take);
      if (!move.target().equals(contract.creditor())) {
        DebtContractBook.upsert(
            debts,
            move.target(),
            contract.creditor(),
            contract.unit(),
            contract.terms(),
            contract.source(),
            take,
            day,
            contract.dueCycle());
      } else {
        // 目标恰是债权人 ⇒ 自债净额（见方法注）：本笔份额不落任何合同，两边同时减少。
        selfNetted = Math.addExact(selfNetted, take);
        EventLog.channel(EconomyLog.migration())
            .info(
                LogEvent.of(
                    "MIGRATION_DEBT_SELF_NETTED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "source",
                    move.source().value(),
                    "target",
                    move.target().value(),
                    "contract",
                    contract.id().value(),
                    "principalBefore",
                    contract.principal(),
                    "netted",
                    take,
                    "reason",
                    "creditor-is-migration-target-self-debt-netted"));
        if (auditLedger != null) {
          auditLedger.addLiquidationAudit(
              new ProductionLedger.LiquidationAudit(
                  day,
                  "migration-netted-self-debt",
                  Optional.of(move.source()),
                  Optional.of(contract.id()),
                  Optional.empty(),
                  Optional.empty(),
                  take,
                  0L,
                  take,
                  contract.principal() - take,
                  "creditor-is-migration-target",
                  Map.of("nettedPrincipal", take)));
        }
      }
    }
    if (moved != move.debtMilli()) {
      // distributeDebt 已保证分摊总额 == 计划额；这条例行守卫挡住"未来有人改分摊口径"。
      throw new IllegalStateException(
          "债务迁移金额没有全部分摊（拒绝静默丢债）: move="
              + move
              + " planDebtMilli="
              + move.debtMilli()
              + " moved="
              + moved);
    }
    long sourceResidualAfter = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(move.source()) && contract.principal() > 0L) {
        sourceResidualAfter = Math.addExact(sourceResidualAfter, contract.principal());
      }
    }
    if (EconomyLog.migration().isDebugEnabled()) {
      EventLog.channel(EconomyLog.migration())
          .debug(
              LogEvent.of(
                  "MIGRATION_DEBT_MOVED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "source",
                  move.source().value(),
                  "target",
                  move.target().value(),
                  "planDebtMilli",
                  move.debtMilli(),
                  "principalBefore",
                  totalPrincipal,
                  "contracts",
                  contracts.size(),
                  "selfNetted",
                  selfNetted,
                  "sourceResidualAfter",
                  sourceResidualAfter));
    }
  }

  /**
   * ★★ <b>D3：把一笔 move 的计划债务额分摊到源户的各笔合同上（总额恒等于计划额，逐笔确定性）</b>。
   *
   * <p>口径：逐合同按**剩余本金比例**取份额（{@code ⌊计划额 × 本合同剩余本金 ÷ 源户剩余本金总额⌋}）；逐笔 floor 会产生不到"合同数"的余数，**按合同 id 的
   * canonical 序补给仍有本金余额的合同**（绝不丢、绝不静默）。
   *
   * <p>★★ <b>旧实现为什么会在长跑里炸</b>：它只让**最后一笔**吃余数（{@code min(remaining, 本金)}）—— 最后一笔本金不够时 {@code
   * remaining != 0}，当场具名抛"债务迁移金额没有全部分摊"。三区世界 300 天一次推进实测：day=33x {@code
   * source=hh-0_-3-urban-middle_peasant} 计划 19,454,004、最后一笔吃不下余下的 5 ⇒ 整次 advance 500。 余数存在性只取决于
   * floor 与合同数，与金额大小无关 ⇒ 只要长跑够久就会撞上。
   *
   * @param move 本笔迁移（{@code debtMilli()} = 计划分摊额）
   * @param contracts 源户作为债务人的合同（须已按 id 升序；本金 > 0）
   * @param totalPrincipal 上述合同的本金合计（> 0）
   * @return 逐合同取额（与 {@code contracts} 同序；Σ == {@code move.debtMilli()}）
   */
  static long[] distributeDebt(
      ModeMigrationPolicy.MigrationMove move, List<DebtContract> contracts, long totalPrincipal) {
    long[] takes = new long[contracts.size()];
    long remaining = move.debtMilli();
    for (int i = 0; i < contracts.size(); i++) {
      long principal = contracts.get(i).principal();
      long share = Math.multiplyExact(move.debtMilli(), principal) / totalPrincipal;
      takes[i] = Math.min(principal, Math.min(share, remaining));
      remaining = Math.subtractExact(remaining, takes[i]);
    }
    // 第二趟：逐笔 floor 的余数（严格小于合同数）按 canonical 序补给仍有本金余额的合同。
    //   余数 ≤ Σ(本金 − 已取) 由"计划额 ≤ 剩余本金总额"保证（计划额超出本金总额 ⇒ 走下面的具名抛）。
    for (int i = 0; i < contracts.size() && remaining > 0L; i++) {
      long headroom = contracts.get(i).principal() - takes[i];
      long add = Math.min(headroom, remaining);
      takes[i] = Math.addExact(takes[i], add);
      remaining = Math.subtractExact(remaining, add);
    }
    if (remaining != 0L) {
      throw new IllegalStateException(
          "债务迁移计划额超过源户剩余本金（拒绝静默丢债）: move="
              + move
              + " totalPrincipal="
              + totalPrincipal
              + " remaining="
              + remaining);
    }
    return takes;
  }

  // ── D-023 资产随迁（migrateAssetsForMove 及配套）─────────────────────────────────────────────

  /** 一次 move 的资产随迁结果：新建的目标份额 id + 它们在各自产业下的 (AssetKind → quantity) 覆盖。 */
  static void putOrRemove(Map<CurrencyId, Long> table, CurrencyId key, long amount) {
    if (amount <= 0L) {
      table.remove(key);
    } else {
      table.put(key, amount);
    }
  }
}
