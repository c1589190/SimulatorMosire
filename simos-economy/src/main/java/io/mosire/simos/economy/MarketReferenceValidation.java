package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.Map;
import java.util.Objects;

/** economy 引用校验拆分 helper（由 EconomyReferenceValidation 抽取；行为不变）。 */
final class MarketReferenceValidation {

  private MarketReferenceValidation() {}

  /**
   * ★★★ <b>D-032 D4-Ib1：商品库存预留的结构校验</b>（D4 §4.4）：键==id、payer 家户、unit/location/commodity/数量。
   *
   * <p>★ overcommit 需要账户快照，见 {@link #validateInventoryReservations(EconomyData,
   * io.mosire.simos.economy.time.AccountInventorySnapshotLookup, String)}。
   */
  public static void validateInventoryReservations(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    Objects.requireNonNull(production, "production");
    validateInventoryReservations(candidate, label);
    Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
        production.instances();
    for (Map.Entry<InventoryReservationId, InventoryReservation> entry :
        candidate.inventoryReservations().entrySet()) {
      InventoryReservation reservation = entry.getValue();
      if (!instances.containsKey(reservation.unitId())) {
        throw new IllegalArgumentException(
            label
                + " 的 reservation 指名悬空 unit（inventory-reservation-unit-missing）: "
                + reservation.id().value()
                + " → "
                + reservation.unitId().value());
      }
    }
  }

  public static void validateInventoryReservations(EconomyData candidate, String label) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");
    for (Map.Entry<InventoryReservationId, InventoryReservation> entry :
        candidate.inventoryReservations().entrySet()) {
      InventoryReservationId key = entry.getKey();
      InventoryReservation reservation = entry.getValue();
      if (!key.equals(reservation.id())) {
        throw new IllegalArgumentException(
            label
                + " 的 inventoryReservations 键必须等于行内 id（inventory-reservation-key-mismatch）: 键="
                + key.value()
                + " id="
                + reservation.id().value());
      }
      if (reservation.payer().kind() != ActorKind.HOUSEHOLD) {
        throw new IllegalArgumentException(
            label
                + " 的 InventoryReservation.payer 必须是家户主体（inventory-reservation-payer-not-household）: "
                + reservation.payer());
      }
      OwnershipStake.requireSpecifiedLocation(reservation.location());
    }
  }

  /** ★★★ D4-Ib1：reservation 的 payer×commodity 全地点 overcommit（账户快照可用时）。 */
  public static void validateInventoryReservations(
      EconomyData candidate,
      io.mosire.simos.economy.time.AccountInventorySnapshotLookup accountLookup,
      String label) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");
    validateInventoryReservations(candidate, label);
    io.mosire.simos.economy.time.InventoryReservationBook.validateAllActive(
        candidate.inventoryReservations(), Objects.requireNonNull(accountLookup, "accountLookup"));
  }

  /** 原 {@code EconomyData} 的 flows 守卫：flow 行指出的格必须登记过至少一个产业（无格键产业 ⇒ 对任何格都算"可能"）。 */
  public static void validateFlowsOnIndustryHex(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    if (definitions.industries().isEmpty()) {
      return; // 定义尚未提供（创世中间态）：最终候选态由统一校验闭环，不在本方法制造假拒绝
    }
    boolean hasHexlessIndustry =
        definitions.industries().keySet().stream()
            .anyMatch(id -> IndustryHexKeys.hexKeyOf(id).isEmpty());
    if (hasHexlessIndustry) {
      return; // 无格键产业 ⇒ 任何格都算已登记（与旧守卫同口径）
    }
    for (Map.Entry<io.mosire.simos.social.api.id.HouseholdId, io.mosire.simos.economy.model.FlowRow>
        entry : candidate.flows().entrySet()) {
      io.mosire.simos.economy.model.HouseholdEconomy household =
          candidate.classes().get(entry.getKey());
      if (household == null) {
        continue; // 对侧（classes）尚未提供 ⇒ 交给其它守卫/最终态
      }
      String hexKey =
          IndustryHexKeys.hexKey(household.view().hex().q(), household.view().hex().r());
      boolean registered =
          definitions.industries().keySet().stream()
              .anyMatch(id -> IndustryHexKeys.hexKeyOf(id).filter(hexKey::equals).isPresent());
      if (!registered) {
        throw new IllegalArgumentException(
            label
                + " 的 flow 行所在格没有登记任何产业（flows 引用了不存在的产业落点）: 家户="
                + entry.getKey().value()
                + " hex="
                + hexKey);
      }
    }
  }

  /**
   * 原 {@code EconomyData} 的 demands 守卫（产业维）：HEX 范围需求指名的格必须在产业/家户行/市场任一登记。
   *
   * <p>★★ <b>B5（§13.15.4 裁定二十）：{@code hexRegistered} 少的"产业"这一维的落点就是本方法</b> —— 本行 {@code
   * definitions.industries()} 那一支读 <b>production 当刻定义权威</b>，与下面两支（家户行 / 市场，读 {@code candidate}
   * 本域）合成同一个三元判据。⇒ 构造期 {@code EconomyData.hexRegistered(...)} 只判本域两维， 产业维在这里补齐（判据没丢、也没放宽）。
   *
   * <p>★ <b>边界（如实记）</b>：本方法跑在<b>候选态校验面</b>（注册点 {@code Shell.java:1199} 的 {@code
   * EconomyProductionReferenceValidator}；派发面见 P1 设计书 §9.3：单条命令 / {@code submitBatch} 最终候选态 /
   * restore / Advance 最终候选态落库前，以及 {@code CoreSimos.bootstrapGenesisInternal:399-402} 对创世态）。 ⇒ <b>单跑
   * codec（不经候选态校验面）时，"HEX 需求指名的格只有产业、没有家户/市场"这种状态不再被拒</b> —— 这是"定义副本从 economy
   * 删除"不可避免的边界，不是放宽：落库前的候选态校验面仍然 fail-closed。
   */
  public static void validateDemandHexes(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (io.mosire.simos.economy.model.HouseholdDemand demand : candidate.demands().values()) {
      if (demand.scope() != io.mosire.simos.economy.model.HouseholdDemand.DemandScope.HEX) {
        continue;
      }
      io.mosire.simos.map.hex.HexCoord hex = demand.hex().orElseThrow();
      String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
      boolean registered =
          definitions.industries().keySet().stream()
              .anyMatch(id -> IndustryHexKeys.hexKeyOf(id).filter(hexKey::equals).isPresent());
      if (!registered) {
        for (io.mosire.simos.economy.model.HouseholdEconomy household :
            candidate.classes().values()) {
          if (household.view().hex().equals(hex)) {
            registered = true;
            break;
          }
        }
      }
      if (!registered) {
        for (io.mosire.simos.economy.model.MarketTable table : candidate.marketTables().values()) {
          if (table.hexes().contains(hex)) {
            registered = true;
            break;
          }
        }
      }
      if (!registered) {
        throw new IllegalArgumentException(label + " 的 HEX 范围需求指名的格没有经济状态（产业/家户行/市场都没有）: " + hex);
      }
    }
  }
}
