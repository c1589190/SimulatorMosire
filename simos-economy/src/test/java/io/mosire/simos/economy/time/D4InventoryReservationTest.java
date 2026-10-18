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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * D4 §4.4 / §7.2 / P18d：预留全地点 overcommit，以及 P4a 后工资可支付库存读口的退役判据。
 *
 * <p>★ P4a：工资改为只付现金 ⇒ {@code WagePayableInventoryLookup} 系列整体删除；本类把原先专测该读口的
 * 三段改成 <b>负向/静态判据</b>：类型不存在、WageSettlement 不接收任何 Lookup 依赖，且库存预留基础设施仍保留给其他
 * 预算用途（不得误删）。
 */
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

  /**
   * P4a 负向判据（取代旧“读口扣除全地点 ACTIVE 预留”）：工资可支付库存读口的三个类型都已从类型系统删除。
   */
  @Test
  void wagePayableInventoryLookupTypesAreRetired() {
    for (String retired :
        List.of(
            "io.mosire.simos.economy.time.WagePayableInventoryLookup",
            "io.mosire.simos.economy.time.WagePayableInventoryLookupImpl",
            "io.mosire.simos.economy.time.UnavailableWagePayableInventoryLookup")) {
      assertThatThrownBy(() -> Class.forName(retired))
          .as("P4a §4.1：工资可支付库存读口必须整体删除: " + retired)
          .isInstanceOf(ClassNotFoundException.class);
    }
  }

  /**
   * P4a 负向判据（取代旧“空 Map vs 账户不可用”）：{@link WageSettlement} 的结算口径不再接收、也不再声明任何
   * inventory lookup 字段/方法 ⇒ 工资支付不读库存、不因 lookup 不可用而 skip。
   */
  @Test
  void wageSettlementApiHasNoInventoryLookupDependency() {
    Method settle =
        Arrays.stream(WageSettlement.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("settle"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("WageSettlement.settle 必须存在"));
    assertThat(Arrays.stream(settle.getParameterTypes()).map(Class::getSimpleName).toList())
        .as("P4a：WageSettlement.settle 不得接收任何 Lookup 依赖")
        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("lookup"));

    for (Field field : WageSettlement.class.getDeclaredFields()) {
      assertThat(field.getType().getSimpleName().toLowerCase(Locale.ROOT))
          .as("P4a：WageSettlement 不得持有 inventory lookup 字段: " + field.getName())
          .doesNotContain("lookup");
    }
    assertThat(
            Arrays.stream(WageSettlement.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("payableinventory"))
                .toList())
        .as("P4a：不得残留 payableInventory 读口方法")
        .isEmpty();
  }

  /**
   * P4a 不得误删库存基础设施（取代旧“缺/非法地点 fail-closed”）：{@link AccountInventorySnapshotLookup} 与
   * {@link InventoryReservationBook} 仍存在并继续服务其他预算用途；只有工资路径不再依赖它们。
   */
  @Test
  void inventoryInfrastructureSurvivesForNonWageBudgets() throws Exception {
    assertThat(Class.forName(AccountInventorySnapshotLookup.class.getName()))
        .as("P4a §4.1：不得误删 AccountInventorySnapshotLookup")
        .isEqualTo(AccountInventorySnapshotLookup.class);
    assertThat(Class.forName(InventoryReservationBook.class.getName()))
        .as("P4a §4.1：保留 InventoryReservationBook 等其他库存预算使用")
        .isEqualTo(InventoryReservationBook.class);
    Method settle =
        Arrays.stream(WageSettlement.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("settle"))
            .findFirst()
            .orElseThrow();
    assertThat(Arrays.stream(settle.getParameterTypes()).map(Class::getName).toList())
        .as("P4a：工资结算不得把库存预留/账户库存读口纳入签名")
        .doesNotContain(
            AccountInventorySnapshotLookup.class.getName(), InventoryReservationBook.class.getName());
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
