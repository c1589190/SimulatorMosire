package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.InventoryReservationStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>D-032 D4 工资可支付库存的真实只读实现</b>（D4 §7.1/§7.2）。
 *
 * <p>数据流：户级 {@link AccountSnapshot}（商品余额/冻结）+ 第 37 组件全部地点 ACTIVE {@link InventoryReservation} ⇒ 逐商品
 * {@code payable = balance - frozen - reservedAllLocations}。
 *
 * <p>★★ <b>不二次扣减</b>：不读 {@code OwnershipStake}、不读 {@code cycleInputUsedMilli}、不读市场价；只保留 {@code > 0}
 * 的项。 {@code settlementLocation} 只作结算分组上下文，<b>不</b>过滤其他地点的 ACTIVE 预留。
 *
 * <p>★★ <b>empty 与空 Map 严格分开</b>：账户 provider 不可用或 payer 未登记 ⇒ {@link Optional#empty()}（调用方具名 {@code
 * wage-inventory-scope-unavailable}）；读口可用但无可支付商品 ⇒ 空 {@link Map}（合法 0）。
 */
public final class WagePayableInventoryLookupImpl implements WagePayableInventoryLookup {

  private static final Logger LOG = EconomyLog.assetOccupancy();

  private final Map<InventoryReservationId, InventoryReservation> reservations;
  private final long currentTick;
  private final AccountInventorySnapshotLookup accountLookup;

  /** 权威状态入口：从 {@link EconomyData#inventoryReservations()} 读取第 37 组件。 */
  public WagePayableInventoryLookupImpl(
      EconomyData data, long currentTick, AccountInventorySnapshotLookup accountLookup) {
    this(
        Objects.requireNonNull(data, "data 不得为 null").inventoryReservations(),
        currentTick,
        accountLookup);
  }

  /** 显式注入第 37 组件（测试/装配可给同一 revision 的只读表）。 */
  public WagePayableInventoryLookupImpl(
      Map<InventoryReservationId, InventoryReservation> reservations,
      long currentTick,
      AccountInventorySnapshotLookup accountLookup) {
    this.reservations =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(
                Objects.requireNonNull(reservations, "reservations 不得为 null（无预留用空表）")));
    if (currentTick < 0L) {
      throw new IllegalArgumentException(
          "WagePayableInventoryLookupImpl.currentTick 不得为负: " + currentTick);
    }
    this.currentTick = currentTick;
    this.accountLookup = Objects.requireNonNull(accountLookup, "accountLookup 不得为 null");
  }

  @Override
  public Optional<Map<CommodityId, Long>> payableInventory(
      ActorRef payer, ProductionLocation settlementLocation) {
    Objects.requireNonNull(payer, "payer 不得为 null");
    if (settlementLocation == null) {
      throw new IllegalArgumentException("asset-location-missing: settlementLocation 不得为 null");
    }
    OwnershipStake.requireSpecifiedLocation(settlementLocation);

    Optional<AccountSnapshot> snapshot = accountLookup.accountSnapshot();
    if (snapshot.isEmpty()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "WAGE_INVENTORY_SCOPE_UNAVAILABLE",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "payer",
                  payer.toString(),
                  "location",
                  settlementLocation.value(),
                  "why",
                  "account-snapshot-provider-unavailable"));
      return Optional.empty();
    }
    AccountPartitionKey accountKey = snapshot.get().actorKeyOrNull(payer);
    if (accountKey == null) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "WAGE_INVENTORY_SCOPE_UNAVAILABLE",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "payer",
                  payer.toString(),
                  "location",
                  settlementLocation.value(),
                  "why",
                  "payer-not-registered-household-account"));
      return Optional.empty();
    }
    AccountSnapshot.SnapshotAccount account = snapshot.get().requireAccount(accountKey);

    Map<CommodityId, Long> reserved = reservedByCommodity(payer);
    Map<CommodityId, Long> payable = new LinkedHashMap<>();
    List<CommodityId> commodities = new ArrayList<>();
    for (CommodityId commodity : account.goods().keySet()) {
      commodities.add(commodity);
    }
    for (CommodityId commodity : reserved.keySet()) {
      if (!account.goods().containsKey(commodity)) {
        commodities.add(commodity);
      }
    }
    commodities.sort(Comparator.comparing(CommodityId::value));
    for (CommodityId commodity : commodities) {
      long balance = account.goods().getOrDefault(commodity, 0L);
      long frozen = account.frozenGoods().getOrDefault(commodity, 0L);
      long reservedQuantity = reserved.getOrDefault(commodity, 0L);
      long committed = addExact(frozen, reservedQuantity, "payableCommitted", payer);
      if (committed > balance) {
        contractFault(
            "inventory-reservation-overcommitted",
            "payer="
                + payer
                + " commodity="
                + commodity.value()
                + " frozen="
                + frozen
                + " reservedAllLocations="
                + reservedQuantity
                + " balance="
                + balance,
            payer);
      }
      long quantity = balance - committed;
      if (quantity > 0L) {
        payable.put(commodity, quantity);
      }
      EventLog.channel(LOG)
          .trace(
              LogEvent.of(
                  "WAGE_PAYABLE_INVENTORY_ITEM",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "payer",
                  payer.toString(),
                  "location",
                  settlementLocation.value(),
                  "commodity",
                  commodity.value(),
                  "balance",
                  balance,
                  "frozen",
                  frozen,
                  "reservedAllLocations",
                  reservedQuantity,
                  "payable",
                  quantity));
    }
    return Optional.of(Collections.unmodifiableMap(payable));
  }

  /** 按 payer×commodity 汇总全部地点、当前 tick 活动的 ACTIVE 预留；地点不参与过滤。 */
  private Map<CommodityId, Long> reservedByCommodity(ActorRef payer) {
    List<InventoryReservation> ordered = new ArrayList<>();
    for (Map.Entry<InventoryReservationId, InventoryReservation> entry : reservations.entrySet()) {
      InventoryReservation row = entry.getValue();
      if (row == null || !entry.getKey().equals(row.id())) {
        contractFault(
            "inventory-reservation-key-mismatch",
            "reservations 的键必须等于行内 id: " + entry.getKey(),
            payer);
      }
      if (row.payer().equals(payer)
          && row.status() == InventoryReservationStatus.ACTIVE
          && row.activeAt(currentTick)) {
        ordered.add(row);
      }
    }
    ordered.sort(
        Comparator.comparing((InventoryReservation row) -> row.commodity().value())
            .thenComparing(row -> row.location().value())
            .thenComparing(row -> row.id().value()));
    Map<CommodityId, Long> totals = new LinkedHashMap<>();
    for (InventoryReservation row : ordered) {
      totals.merge(row.commodity(), row.quantity(), Math::addExact);
    }
    return totals;
  }

  private static long addExact(long left, long right, String operation, ActorRef payer) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "inventory-reservation-quantity-overflow",
          operation + " 溢出: " + left + " + " + right,
          payer);
      return 0L;
    }
  }

  private static void contractFault(String word, String detail, ActorRef payer) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "WAGE_INVENTORY_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "failure",
                word,
                "payer",
                payer == null ? "-" : payer.toString(),
                "detail",
                detail));
    throw new IllegalStateException(word + ": " + detail);
  }
}
