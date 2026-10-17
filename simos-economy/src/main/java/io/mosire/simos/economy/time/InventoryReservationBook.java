package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.InventoryReservationStatus;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>D-032 D4 商品库存预留内部写口</b>（D4 §4.4/§8.4）：只创建/释放 {@link InventoryReservation} 候选行并做
 * payer×commodity 全地点 overcommit 校验；<b>不是资产分配器</b>，也绝不直接写账户。
 *
 * <p>★★ <b>overcommit 的唯一判据</b>：同一 payer × commodity 的全部地点 ACTIVE 预留之和 + {@code
 * AccountSession.frozenGoods} ≤ 该户账户余额。这里只读 {@link AccountInventorySnapshotLookup} 的快照，不造第二本余额/账户
 * Map；违反 ⇒ ERROR {@code inventory-reservation-overcommitted}，不静默截断。
 *
 * <p>★ 创建/释放不直接写 {@code EconomyData}：返回的 {@link ReservationChange#writes()} 必须由调用方并入同一 revision 的
 * economy ChangeSet；实际转出投入时由账户变更集扣余额并把预留置 RELEASED，恰好只扣一次。
 */
public final class InventoryReservationBook {

  private static final Logger LOG = EconomyLog.assetOccupancy();

  private static final Comparator<InventoryReservation> RESERVATION_ORDER =
      Comparator.comparing((InventoryReservation row) -> row.payer().toString())
          .thenComparing(row -> row.commodity().value())
          .thenComparing(row -> row.location().value())
          .thenComparing(row -> row.unitId().value())
          .thenComparingLong(InventoryReservation::fromTick)
          .thenComparing(row -> row.id().value());

  private InventoryReservationBook() {}

  /**
   * 一条预留候选变更：只有 {@code writes} 需要由调用方提交（同一 revision 的 economy ChangeSet）。
   *
   * @param writes 要 Upsert 的行（创建=ACTIVE；释放=RELEASED）
   */
  public record ReservationChange(List<InventoryReservation> writes) {

    public ReservationChange {
      Objects.requireNonNull(writes, "ReservationChange.writes 不得为 null（无变更用空表）");
      List<InventoryReservation> copy = new ArrayList<>();
      for (InventoryReservation row : writes) {
        if (row == null) {
          throw new IllegalArgumentException("ReservationChange.writes 不得含 null");
        }
        copy.add(row);
      }
      copy.sort(RESERVATION_ORDER);
      writes = Collections.unmodifiableList(copy);
    }

    public boolean isEmpty() {
      return writes.isEmpty();
    }
  }

  /**
   * 创建/upsert 一条 ACTIVE 预留。
   *
   * @param existing 第 37 组件的权威表（只读）
   * @param candidate 待创建行；必须 ACTIVE
   * @param accountLookup 账户只读快照 provider
   * @return 恰好一条 Upsert；同 id 同值 ⇒ 幂等空变更
   * @throws IllegalStateException 账户不可用、payer 未登记、同 id 异值、或 overcommit
   */
  public static ReservationChange create(
      Map<InventoryReservationId, InventoryReservation> existing,
      InventoryReservation candidate,
      AccountInventorySnapshotLookup accountLookup) {
    Objects.requireNonNull(existing, "existing 不得为 null（无预留用空表）");
    Objects.requireNonNull(candidate, "candidate 不得为 null");
    Objects.requireNonNull(accountLookup, "accountLookup 不得为 null");
    if (candidate.status() != InventoryReservationStatus.ACTIVE) {
      contractFault(
          "inventory-reservation-create-not-active",
          "create 只接受 ACTIVE 预留: " + candidate.id().value(),
          candidate.payer());
    }
    InventoryReservation sameId = existing.get(candidate.id());
    if (sameId != null) {
      if (sameId.equals(candidate)) {
        return new ReservationChange(List.of());
      }
      contractFault(
          "inventory-reservation-id-collision",
          "同一 InventoryReservationId 已有不同值: " + candidate.id().value(),
          candidate.payer());
    }

    requireNoOvercommit(existing, candidate, accountLookup);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "INVENTORY_RESERVATION_CREATED",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "reservation",
                candidate.id().value(),
                "unit",
                candidate.unitId().value(),
                "payer",
                candidate.payer().toString(),
                "location",
                candidate.location().value(),
                "commodity",
                candidate.commodity().value(),
                "quantity",
                candidate.quantity(),
                "fromTick",
                candidate.fromTick(),
                "toTickExclusive",
                candidate.toTickExclusive()));
    return new ReservationChange(List.of(candidate));
  }

  /**
   * 释放一条预留：ACTIVE → RELEASED；余额未动，后续 W3/用途可重新使用。
   *
   * @return 恰好一条 Upsert；不存在 ⇒ 空变更（幂等）；已 RELEASED ⇒ 空变更
   */
  public static ReservationChange release(
      Map<InventoryReservationId, InventoryReservation> existing,
      InventoryReservationId id,
      long releasedAtTick) {
    Objects.requireNonNull(existing, "existing 不得为 null（无预留用空表）");
    Objects.requireNonNull(id, "id 不得为 null");
    if (releasedAtTick < 0L) {
      contractFault(
          "inventory-reservation-invalid-release-tick",
          "releasedAtTick 不得为负: " + releasedAtTick,
          null);
    }
    InventoryReservation current = existing.get(id);
    if (current == null || current.status() == InventoryReservationStatus.RELEASED) {
      return new ReservationChange(List.of());
    }
    InventoryReservation released =
        new InventoryReservation(
            current.id(),
            current.unitId(),
            current.payer(),
            current.location(),
            current.commodity(),
            current.quantity(),
            current.fromTick(),
            current.toTickExclusive(),
            InventoryReservationStatus.RELEASED,
            releasedAtTick);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "INVENTORY_RESERVATION_RELEASED",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "reservation",
                released.id().value(),
                "unit",
                released.unitId().value(),
                "payer",
                released.payer().toString(),
                "location",
                released.location().value(),
                "commodity",
                released.commodity().value(),
                "quantity",
                released.quantity(),
                "releasedAtTick",
                released.releasedAtTick()));
    return new ReservationChange(List.of(released));
  }

  /** 释放某 unit 的全部 ACTIVE 预留（生产取消/批次失败）；稳定序返回。 */
  public static ReservationChange releaseAllForUnit(
      Map<InventoryReservationId, InventoryReservation> existing,
      io.mosire.simos.economy.api.id.ProductionUnitId unit,
      long releasedAtTick) {
    Objects.requireNonNull(existing, "existing 不得为 null（无预留用空表）");
    Objects.requireNonNull(unit, "unit 不得为 null");
    if (releasedAtTick < 0L) {
      contractFault(
          "inventory-reservation-invalid-release-tick",
          "releasedAtTick 不得为负: " + releasedAtTick,
          null);
    }
    List<InventoryReservation> released = new ArrayList<>();
    for (InventoryReservation current : existing.values()) {
      if (current.unitId().equals(unit) && current.status() == InventoryReservationStatus.ACTIVE) {
        released.add(
            new InventoryReservation(
                current.id(),
                current.unitId(),
                current.payer(),
                current.location(),
                current.commodity(),
                current.quantity(),
                current.fromTick(),
                current.toTickExclusive(),
                InventoryReservationStatus.RELEASED,
                releasedAtTick));
      }
    }
    for (InventoryReservation row : released) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "INVENTORY_RESERVATION_RELEASED",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "reservation",
                  row.id().value(),
                  "unit",
                  row.unitId().value(),
                  "payer",
                  row.payer().toString(),
                  "location",
                  row.location().value(),
                  "commodity",
                  row.commodity().value(),
                  "quantity",
                  row.quantity(),
                  "releasedAtTick",
                  row.releasedAtTick(),
                  "why",
                  "release-all-for-unit"));
    }
    return new ReservationChange(released);
  }

  /**
   * 对一条候选 ACTIVE 预留做 payer×commodity 全地点 overcommit 校验。供写口与 {@code EconomyReferenceValidation}
   * 复用，禁止第二套减法。
   */
  public static void requireNoOvercommit(
      Map<InventoryReservationId, InventoryReservation> existing,
      InventoryReservation candidate,
      AccountInventorySnapshotLookup accountLookup) {
    Objects.requireNonNull(existing, "existing 不得为 null");
    Objects.requireNonNull(candidate, "candidate 不得为 null");
    Objects.requireNonNull(accountLookup, "accountLookup 不得为 null");
    if (candidate.status() != InventoryReservationStatus.ACTIVE) {
      return;
    }
    AccountSnapshot snapshot =
        accountLookup
            .accountSnapshot()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "inventory-reservation-account-unavailable: 账户只读快照不可用，不能校验 "
                            + candidate.id().value()));
    AccountPartitionKey key = snapshot.actorKeyOrNull(candidate.payer());
    if (key == null) {
      contractFault(
          "inventory-reservation-payer-unregistered",
          "payer 无法解析为已登记家户账户: " + candidate.payer(),
          candidate.payer());
    }
    AccountSnapshot.SnapshotAccount account = snapshot.requireAccount(key);
    long balance = account.goods().getOrDefault(candidate.commodity(), 0L);
    long frozen = account.frozenGoods().getOrDefault(candidate.commodity(), 0L);
    long reserved = reservedAllLocations(existing, candidate.payer(), candidate.commodity());
    reserved = addExact(reserved, candidate.quantity(), "reservationCandidate", candidate.payer());
    long committed = addExact(frozen, reserved, "reservationOvercommit", candidate.payer());
    if (committed > balance) {
      contractFault(
          "inventory-reservation-overcommitted",
          "payer="
              + candidate.payer()
              + " commodity="
              + candidate.commodity().value()
              + " frozen="
              + frozen
              + " reservedAllLocations="
              + reserved
              + " balance="
              + balance,
          candidate.payer());
    }
    EventLog.channel(LOG)
        .trace(
            LogEvent.of(
                "INVENTORY_RESERVATION_OVERCOMMIT_CHECK",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "payer",
                candidate.payer().toString(),
                "commodity",
                candidate.commodity().value(),
                "balance",
                balance,
                "frozen",
                frozen,
                "reservedAllLocations",
                reserved));
  }

  /** 全表校验：逐 payer×commodity 汇总全部 ACTIVE 预留 + frozen，与账户余额比较。账户/provider 不可用 ⇒ 契约 ERROR。 */
  public static void validateAllActive(
      Map<InventoryReservationId, InventoryReservation> reservations,
      AccountInventorySnapshotLookup accountLookup) {
    Objects.requireNonNull(reservations, "reservations 不得为 null");
    Objects.requireNonNull(accountLookup, "accountLookup 不得为 null");
    AccountSnapshot snapshot =
        accountLookup
            .accountSnapshot()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "inventory-reservation-account-unavailable: 账户只读快照不可用，不能校验预留"));
    Map<PayerCommodity, Long> totals = new LinkedHashMap<>();
    for (Map.Entry<InventoryReservationId, InventoryReservation> entry : reservations.entrySet()) {
      InventoryReservation row = entry.getValue();
      if (row == null || !entry.getKey().equals(row.id())) {
        contractFault(
            "inventory-reservation-key-mismatch",
            "reservations 的键必须等于行内 id: " + entry.getKey(),
            row == null ? null : row.payer());
      }
      if (row.status() == InventoryReservationStatus.ACTIVE) {
        totals.merge(
            new PayerCommodity(row.payer(), row.commodity()), row.quantity(), Math::addExact);
      }
    }
    List<Map.Entry<PayerCommodity, Long>> ordered = new ArrayList<>(totals.entrySet());
    ordered.sort(
        Comparator.comparing(
                (Map.Entry<PayerCommodity, Long> entry) -> entry.getKey().payer().toString())
            .thenComparing(entry -> entry.getKey().commodity().value()));
    for (Map.Entry<PayerCommodity, Long> entry : ordered) {
      PayerCommodity key = entry.getKey();
      AccountPartitionKey accountKey = snapshot.actorKeyOrNull(key.payer());
      if (accountKey == null) {
        contractFault(
            "inventory-reservation-payer-unregistered",
            "payer 无法解析为已登记家户账户: " + key.payer(),
            key.payer());
      }
      AccountSnapshot.SnapshotAccount account = snapshot.requireAccount(accountKey);
      long balance = account.goods().getOrDefault(key.commodity(), 0L);
      long frozen = account.frozenGoods().getOrDefault(key.commodity(), 0L);
      long committed = addExact(frozen, entry.getValue(), "reservationOvercommit", key.payer());
      if (committed > balance) {
        contractFault(
            "inventory-reservation-overcommitted",
            "payer="
                + key.payer()
                + " commodity="
                + key.commodity().value()
                + " frozen="
                + frozen
                + " reservedAllLocations="
                + entry.getValue()
                + " balance="
                + balance,
            key.payer());
      }
    }
  }

  private static long reservedAllLocations(
      Map<InventoryReservationId, InventoryReservation> existing,
      ActorRef payer,
      CommodityId commodity) {
    long reserved = 0L;
    for (InventoryReservation row : existing.values()) {
      if (row.status() == InventoryReservationStatus.ACTIVE
          && row.payer().equals(payer)
          && row.commodity().equals(commodity)) {
        reserved = addExact(reserved, row.quantity(), "reservationAllLocations", payer);
      }
    }
    return reserved;
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
                "INVENTORY_RESERVATION_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "failure",
                word,
                "payer",
                payer == null ? "-" : payer.toString(),
                "detail",
                detail));
    throw new IllegalStateException(word + ": " + detail);
  }

  private record PayerCommodity(ActorRef payer, CommodityId commodity) {}
}
