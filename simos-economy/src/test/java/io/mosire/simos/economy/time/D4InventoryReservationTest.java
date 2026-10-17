package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.InventoryReservationStatus;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** D4 §4.4 / §7.2 / P18d：预留全地点 overcommit 与工资可支付库存读口。 */
class D4InventoryReservationTest {

  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-d4-inventory");
  private static final ActorRef PAYER = HouseholdActors.of(HOUSEHOLD);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final ProductionLocation L0 = new ProductionLocation("0_0");
  private static final ProductionLocation L3 = new ProductionLocation("3_4");
  private static final ProductionUnitId UNIT_A = new ProductionUnitId("d4-inventory-unit-a");
  private static final ProductionUnitId UNIT_B = new ProductionUnitId("d4-inventory-unit-b");

  @Test
  void overcommitIsCheckedAcrossAllLocationsAndNeverTruncatedOrWritten() {
    AccountInventorySnapshotLookup accounts = accounts(1_000L, 0L);
    InventoryReservation first =
        reservation(UNIT_A, L0, GRAIN, 600L, InventoryReservationStatus.ACTIVE, -1L);
    InventoryReservation second =
        reservation(UNIT_B, L3, GRAIN, 600L, InventoryReservationStatus.ACTIVE, -1L);
    Map<InventoryReservationId, InventoryReservation> existing = new LinkedHashMap<>();

    InventoryReservationBook.ReservationChange created =
        InventoryReservationBook.create(existing, first, accounts);
    assertThat(created.writes()).containsExactly(first);
    existing.put(first.id(), first);

    assertThatThrownBy(() -> InventoryReservationBook.create(existing, second, accounts))
        .as("P18d/例 J：A 地点 600 + B 地点 600 > 余额 1000 ⇒ 必须 ERROR")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inventory-reservation-overcommitted");
    assertThat(existing)
        .as("零新增/零截断：失败后仍是 A 的 600，B 不落表")
        .containsOnlyKeys(first.id())
        .containsEntry(first.id(), first);
  }

  @Test
  void wageLookupDeductsFrozenAndActiveReservationsFromAllLocationsExactlyOnce() {
    Map<InventoryReservationId, InventoryReservation> reservations = new LinkedHashMap<>();
    InventoryReservation atA =
        reservation(UNIT_A, L0, GRAIN, 300L, InventoryReservationStatus.ACTIVE, -1L);
    InventoryReservation atB =
        reservation(UNIT_B, L3, GRAIN, 100L, InventoryReservationStatus.ACTIVE, -1L);
    reservations.put(atA.id(), atA);
    reservations.put(atB.id(), atB);
    Map<InventoryReservationId, InventoryReservation> before = Map.copyOf(reservations);

    WagePayableInventoryLookupImpl lookup =
        new WagePayableInventoryLookupImpl(reservations, 0L, accounts(1_000L, 200L));

    assertThat(lookup.payableInventory(PAYER, L0))
        .as("P13：1000 − frozen 200 − 全地点 ACTIVE 400 = 400；不是只减 L0 的 300")
        .contains(Map.of(GRAIN, 400L));
    assertThat(lookup.payableInventory(PAYER, L0).orElseThrow())
        .as("D4-I10：资产权益 LAND 不进入工资可支付商品表")
        .doesNotContainKey(CLOTH);
    assertThat(lookup.payableInventory(PAYER, L0))
        .as("读口纯读/不二次扣减：连续读取同值")
        .contains(Map.of(GRAIN, 400L));
    assertThat(reservations).as("lookup 不写预留").isEqualTo(before);
  }

  @Test
  void wageLookupDistinguishesEmptyMapFromUnavailableAccount() {
    WagePayableInventoryLookupImpl noGoods =
        new WagePayableInventoryLookupImpl(
            Map.of(), 0L, snapshot(Map.of(), Map.of(), Map.of(), Map.of()));
    assertThat(noGoods.payableInventory(PAYER, L0))
        .as("读口可用但无商品 ⇒ 空 Map（不是 Optional.empty）")
        .contains(Map.of());

    WagePayableInventoryLookupImpl unavailable =
        new WagePayableInventoryLookupImpl(Map.of(), 0L, Optional::<AccountSnapshot>empty);
    assertThat(unavailable.payableInventory(PAYER, L0)).as("账户权威不可用 ⇒ Optional.empty").isEmpty();

    WagePayableInventoryLookupImpl frozenAway =
        new WagePayableInventoryLookupImpl(
            Map.of(),
            0L,
            snapshot(Map.of(GRAIN, 1_000L), Map.of(), Map.of(GRAIN, 1_000L), Map.of()));
    assertThat(frozenAway.payableInventory(PAYER, L0))
        .as("余额被冻结全部扣完 ⇒ 空 Map，不是不可用")
        .contains(Map.of());
  }

  @Test
  void wageLookupFailsClosedForMissingOrIllegalSettlementLocation() {
    WagePayableInventoryLookupImpl lookup =
        new WagePayableInventoryLookupImpl(Map.of(), 0L, accounts(1_000L, 0L));
    assertThatThrownBy(() -> lookup.payableInventory(PAYER, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asset-location-missing");
    assertThatThrownBy(() -> lookup.payableInventory(PAYER, new ProductionLocation("foo")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asset-location-missing");
  }

  private static InventoryReservation reservation(
      ProductionUnitId unit,
      ProductionLocation location,
      CommodityId commodity,
      long quantity,
      InventoryReservationStatus status,
      long releasedAt) {
    return new InventoryReservation(
        InventoryReservationId.idOf(unit, PAYER, location, commodity, 0L),
        unit,
        PAYER,
        location,
        commodity,
        quantity,
        0L,
        3L,
        status,
        releasedAt);
  }

  private static AccountInventorySnapshotLookup accounts(long grain, long frozenGrain) {
    return snapshot(Map.of(GRAIN, grain), Map.of(), Map.of(GRAIN, frozenGrain), Map.of());
  }

  private static AccountInventorySnapshotLookup snapshot(
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      Map<CommodityId, Long> frozenGoods,
      Map<CurrencyId, Long> frozenMoney) {
    AccountSnapshot snapshot =
        AccountSnapshot.of(
            Map.of(
                new AccountPartitionKey(HOUSEHOLD),
                new AccountSnapshot.SnapshotAccount(goods, money, frozenGoods, frozenMoney)),
            Map.of());
    return () -> Optional.of(snapshot);
  }
}
