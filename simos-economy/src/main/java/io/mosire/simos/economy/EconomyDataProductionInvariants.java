package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** EconomyData 规范构造期的生产/债务尾段不变量 helper（机械拆分；行为不变）。 */
final class EconomyDataProductionInvariants {
  private EconomyDataProductionInvariants() {}

  /** crisisSignals：键身份/确定性派生 + 家户引用完整性，并冻结。 */
  static Map<CrisisSignalId, HexCrisisSignal> validateCrisisSignals(
      Map<CrisisSignalId, HexCrisisSignal> crisisSignals,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy) {
    Map<CrisisSignalId, HexCrisisSignal> crisisSignalsCopy = new LinkedHashMap<>();
    for (Map.Entry<CrisisSignalId, HexCrisisSignal> entry : crisisSignals.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("crisisSignals 的键与值都不得为 null: " + entry.getKey());
      }
      HexCrisisSignal signal = entry.getValue();
      if (!entry.getKey().equals(signal.id())) {
        throw new IllegalArgumentException(
            "crisisSignals 的键必须与 HexCrisisSignal.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + signal.id());
      }
      CrisisSignalId derived = CrisisSignalId.idOf(signal.hex(), signal.kind().name());
      if (!entry.getKey().equals(derived)) {
        throw new IllegalArgumentException(
            "crisisSignals 的键必须由 (hex, kind) 确定性派生（同 hex 同 kind 只保留最新一条）：键="
                + entry.getKey()
                + "，派生="
                + derived);
      }
      if (!householdEconomiesCopy.isEmpty()) {
        for (HouseholdId household : signal.households()) {
          if (!householdEconomiesCopy.containsKey(household)) {
            throw new IllegalArgumentException(
                "危机信号点名的家户不存在（家户表已提供 ⇒ fail-closed）：信号=" + entry.getKey() + "，家户=" + household);
          }
        }
      }
      crisisSignalsCopy.put(entry.getKey(), signal);
    }
    crisisSignals = Collections.unmodifiableMap(crisisSignalsCopy); // ★ 冻在赋值处
    return crisisSignals;
  }

  /** modeTransitions：键身份/确定性派生 + 组织/fromMode 一致性 + 单一 PENDING，并冻结。 */
  static Map<ModeTransitionId, ModeTransition> validateModeTransitions(
      Map<ModeTransitionId, ModeTransition> modeTransitions,
      Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations) {
    Map<ModeTransitionId, ModeTransition> modeTransitionsCopy = new LinkedHashMap<>();
    Set<ProductionOrganizationId> pendingEnterprises = new LinkedHashSet<>();
    for (Map.Entry<ModeTransitionId, ModeTransition> entry : modeTransitions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("modeTransitions 的键与值都不得为 null: " + entry.getKey());
      }
      ModeTransition transition = entry.getValue();
      if (!entry.getKey().equals(transition.id())) {
        throw new IllegalArgumentException(
            "modeTransitions 的键必须与 ModeTransition.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + transition.id());
      }
      ModeTransitionId derived =
          ModeTransitionId.idOf(
              transition.organizationId(), transition.toModeId(), transition.effectiveDay());
      if (!entry.getKey().equals(derived)) {
        throw new IllegalArgumentException(
            "modeTransitions 的键必须由 (organizationId, toModeId, effectiveDay) 确定性派生"
                + "（同一请求重复提交必须得到同一 id）：键="
                + entry.getKey()
                + "，派生="
                + derived);
      }
      if (!productionOrganizations.isEmpty()
          && !productionOrganizations.containsKey(transition.organizationId())) {
        throw new IllegalArgumentException(
            "模式变迁指名的生产组织不存在：变迁=" + entry.getKey() + "，组织=" + transition.organizationId());
      }
      if (!productionOrganizations.isEmpty()) {
        ProductionEnterprise enterprise = productionOrganizations.get(transition.organizationId());
        if (enterprise != null && !enterprise.modeId().equals(transition.fromModeId())) {
          throw new IllegalArgumentException(
              "模式变迁的 fromModeId 必须等于组织当前的 modeId（同一件事不许两处拼写）：变迁="
                  + entry.getKey()
                  + "，fromMode="
                  + transition.fromModeId()
                  + "，组织 mode="
                  + enterprise.modeId());
        }
      }
      // ★ B5（裁定十八）：原"变迁的 from/to mode 必须已存在"两维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
      if (transition.status() == ModeTransition.Status.PENDING
          && !pendingEnterprises.add(transition.organizationId())) {
        throw new IllegalArgumentException(
            "同一生产组织至多允许一条 PENDING 模式变迁（重复会让同一次切换被结算两次）：组织=" + transition.organizationId());
      }
      modeTransitionsCopy.put(entry.getKey(), transition);
    }
    modeTransitions = Collections.unmodifiableMap(modeTransitionsCopy); // ★ 冻在赋值处
    return modeTransitions;
  }

  /** classShares：键身份 + 变迁/家户/位置引用 + 分组 Σ = 1000，并冻结。 */
  static Map<ClassShareId, ClassShare> validateClassShares(
      Map<ClassShareId, ClassShare> classShares,
      Map<ModeTransitionId, ModeTransition> modeTransitionsCopy,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy,
      Map<ClassPositionId, ProductionRole> positionsCopy) {
    Map<ClassShareId, ClassShare> classSharesCopy = new LinkedHashMap<>();
    Map<String, Long> sharePerGroup = new LinkedHashMap<>();
    for (Map.Entry<ClassShareId, ClassShare> entry : classShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classShares 的键与值都不得为 null: " + entry.getKey());
      }
      ClassShare share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            "classShares 的键必须与 ClassShare.id 一致：键=" + entry.getKey() + "，行内 id=" + share.id());
      }
      if (!modeTransitionsCopy.isEmpty()
          && !modeTransitionsCopy.containsKey(share.transitionId())) {
        throw new IllegalArgumentException(
            "阶层保留份额指名的模式变迁不存在：份额=" + entry.getKey() + "，变迁=" + share.transitionId());
      }
      if (!householdEconomiesCopy.isEmpty()
          && !householdEconomiesCopy.containsKey(share.householdId())) {
        throw new IllegalArgumentException(
            "阶层保留份额指名的家户不存在：份额=" + entry.getKey() + "，家户=" + share.householdId());
      }
      if (!positionsCopy.isEmpty() && !positionsCopy.containsKey(share.classPositionId())) {
        throw new IllegalArgumentException(
            "阶层保留份额指名的阶层位置不存在：份额=" + entry.getKey() + "，位置=" + share.classPositionId());
      }
      String groupKey = share.transitionId().value() + "|" + share.householdId().value();
      sharePerGroup.merge(groupKey, share.sharePerMille(), Math::addExact);
      classSharesCopy.put(entry.getKey(), share);
    }
    for (Map.Entry<String, Long> entry : sharePerGroup.entrySet()) {
      if (entry.getValue() != 1000L) {
        throw new IllegalArgumentException(
            "同一 (transitionId, householdId) 的 Σ ClassShare.sharePerMille 必须 = 1000：组="
                + entry.getKey()
                + "，合计="
                + entry.getValue());
      }
    }
    classShares = Collections.unmodifiableMap(classSharesCopy); // ★ 冻在赋值处
    return classShares;
  }

  /** periodicAdjustments：键/值非空、键 == 行内 id，并冻结。 */
  static Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment>
      validatePeriodicAdjustments(
          Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> periodicAdjustments) {
    Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> periodicAdjustmentsCopy =
        new LinkedHashMap<>();
    for (Map.Entry<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> entry :
        periodicAdjustments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("periodicAdjustments 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "periodicAdjustments 的键必须与 HouseholdPeriodicAdjustment.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      periodicAdjustmentsCopy.put(entry.getKey(), entry.getValue());
    }
    periodicAdjustments = Collections.unmodifiableMap(periodicAdjustmentsCopy); // ★ 冻在赋值处
    return periodicAdjustments;
  }

  /** productionRosters：键/值非空、键 == 行内 unit，并冻结。 */
  static Map<ProductionUnitId, ProductionRoster> validateProductionRosters(
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    if (productionRosters == null) {
      productionRosters = Map.of();
    }
    Map<ProductionUnitId, ProductionRoster> productionRostersCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRoster> entry : productionRosters.entrySet()) {
      ProductionUnitId rosterUnit = entry.getKey();
      ProductionRoster roster = entry.getValue();
      if (rosterUnit == null || roster == null) {
        throw new IllegalArgumentException("productionRosters 的键与值都不得为 null: " + rosterUnit);
      }
      if (!rosterUnit.equals(roster.unit())) {
        throw new IllegalArgumentException(
            "productionRosters 的键必须与 ProductionRoster.unit 一致（D2-I1）：键="
                + rosterUnit.value()
                + "，行内 unit="
                + roster.unit().value());
      }
      productionRostersCopy.put(rosterUnit, roster);
    }
    productionRosters = Collections.unmodifiableMap(productionRostersCopy); // ★ 冻在赋值处
    return productionRosters;
  }

  /** wageCarryRemainders：键/值非空、值域 0..999，并冻结。 */
  static Map<WageCarryKey, Long> validateWageCarryRemainders(
      Map<WageCarryKey, Long> wageCarryRemainders) {
    if (wageCarryRemainders == null) {
      wageCarryRemainders = Map.of();
    }
    Map<WageCarryKey, Long> wageCarryCopy = new LinkedHashMap<>();
    for (Map.Entry<WageCarryKey, Long> entry : wageCarryRemainders.entrySet()) {
      WageCarryKey key = entry.getKey();
      Long carry = entry.getValue();
      if (key == null || carry == null) {
        throw new IllegalArgumentException("wageCarryRemainders 的键与值都不得为 null: " + key);
      }
      if (carry < 0L || carry > 999L) {
        throw new IllegalArgumentException(
            "wage-carry-out-of-range: wageCarryRemainders 的值必须在 0..999: 键="
                + key
                + "，carry="
                + carry);
      }
      wageCarryCopy.put(key, carry);
    }
    wageCarryRemainders = Collections.unmodifiableMap(wageCarryCopy); // ★ 冻在赋值处
    return wageCarryRemainders;
  }

  /** assetOccupancies：键/值非空、键 == 行内 id，并冻结。 */
  static Map<AssetOccupancyGrantId, AssetOccupancyGrant> validateAssetOccupancies(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> assetOccupancies) {
    if (assetOccupancies == null) {
      assetOccupancies = Map.of();
    }
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> occupancyCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry :
        assetOccupancies.entrySet()) {
      AssetOccupancyGrantId key = entry.getKey();
      AssetOccupancyGrant grant = entry.getValue();
      if (key == null || grant == null) {
        throw new IllegalArgumentException("assetOccupancies 的键与值都不得为 null: " + key);
      }
      if (!key.equals(grant.id())) {
        throw new IllegalArgumentException(
            "assetOccupancies 的键必须与 AssetOccupancyGrant.id 一致：键="
                + key.value()
                + "，行内 id="
                + grant.id().value());
      }
      occupancyCopy.put(key, grant);
    }
    assetOccupancies = Collections.unmodifiableMap(occupancyCopy); // ★ 冻在赋值处
    return assetOccupancies;
  }

  /** inventoryReservations：键/值非空、键 == 行内 id，并冻结。 */
  static Map<InventoryReservationId, InventoryReservation> validateInventoryReservations(
      Map<InventoryReservationId, InventoryReservation> inventoryReservations) {
    if (inventoryReservations == null) {
      inventoryReservations = Map.of();
    }
    Map<InventoryReservationId, InventoryReservation> reservationCopy = new LinkedHashMap<>();
    for (Map.Entry<InventoryReservationId, InventoryReservation> entry :
        inventoryReservations.entrySet()) {
      InventoryReservationId key = entry.getKey();
      InventoryReservation reservation = entry.getValue();
      if (key == null || reservation == null) {
        throw new IllegalArgumentException("inventoryReservations 的键与值都不得为 null: " + key);
      }
      if (!key.equals(reservation.id())) {
        throw new IllegalArgumentException(
            "inventoryReservations 的键必须与 InventoryReservation.id 一致：键="
                + key.value()
                + "，行内 id="
                + reservation.id().value());
      }
      reservationCopy.put(key, reservation);
    }
    inventoryReservations = Collections.unmodifiableMap(reservationCopy); // ★ 冻在赋值处
    return inventoryReservations;
  }

  /**
   * productionWageAnchors：键/值非空、键身份合法、currency 与市场区 legalTender 一致（若区表提供该区），并冻结。
   *
   * <p>★ <b>冻结形状</b>：{@link ProductionWageAnchor} 按 P4 §6.1 不携带三元组，故键的唯一身份就是本表键。 这里校验键/值非
   * null，并在区表能定位该区时把 currency 与 legalTender 对齐（不一致 ⇒ {@code wage-anchor-currency-mismatch}）。★ 区表为空
   * / 缺该区时状态层不拒绝（本仓派生市场形态与分步 seed 都允许“先有锚、后有区”的写序）；显式命令 / seed 边界另有具名 zone 存在性校验。
   */
  static Map<WageAnchorKey, ProductionWageAnchor> validateProductionWageAnchors(
      Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors,
      Map<MarketZoneId, MarketZone> marketZones) {
    if (productionWageAnchors == null) {
      productionWageAnchors = Map.of();
    }
    Map<WageAnchorKey, ProductionWageAnchor> anchorsCopy = new LinkedHashMap<>();
    for (Map.Entry<WageAnchorKey, ProductionWageAnchor> entry : productionWageAnchors.entrySet()) {
      WageAnchorKey key = entry.getKey();
      ProductionWageAnchor value = entry.getValue();
      if (key == null || value == null) {
        throw new IllegalArgumentException("productionWageAnchors 的键与值都不得为 null: " + key);
      }
      MarketZone zone = marketZones == null ? null : marketZones.get(key.marketZone());
      if (zone != null && !value.currency().equals(zone.legalTender())) {
        throw new IllegalArgumentException(
            "wage-anchor-currency-mismatch: 锚币种必须等于市场区法定币：key="
                + key
                + "，currency="
                + value.currency().value()
                + "，legalTender="
                + zone.legalTender().value());
      }
      anchorsCopy.put(key, value);
    }
    productionWageAnchors = Collections.unmodifiableMap(anchorsCopy); // ★ 冻在赋值处
    return productionWageAnchors;
  }
}
