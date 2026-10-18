package io.mosire.simos.economy;

import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionRuntime;
import java.util.Objects;

/**
 * ★★ <b>economy 写入口对 production 定义权威的跨域引用校验</b>（D-031 P1 spec §3.3）。
 *
 * <p>★★ <b>为什么需要它</b>：引用完整性（unit→industry、assetShare→industry、{@code progressDays ≤ cycleDays}、
 * classStructure/classPosition/组织/规则/变迁→mode、输出覆盖→产业配方产出键）原先住在 {@code EconomyData} 的构造期守卫里；定义迁出后
 * economy 状态构造器<b>无参照可查</b>。这些守卫必须在<b>业务写入口</b>显式重建，且不能等到下一次 advance 才发现悬空引用。纯 codec
 * 只验证本域；本类在命令候选态上逐条校验。
 *
 * <p>★ <b>只读</b>：只回答候选态是否自洽，不改任何状态；失败以 {@link IllegalArgumentException} 出面（命令层折 {@code Rejected}，不留
 * revision）。
 */
public final class EconomyReferenceValidation {

  private EconomyReferenceValidation() {}

  public static void validate(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    validate(definitions, production, candidate, label, false);
  }

  public static void validate(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label,
      boolean enforcePlannedLaborBudget) {
    Objects.requireNonNull(definitions, "definitions");
    Objects.requireNonNull(production, "production");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");
    ProductionReferenceValidation.validateUnitsAndShares(definitions, production, candidate, label);
    ProductionReferenceValidation.validateOutputQuantityOverrides(definitions, production, label);
    ProductionReferenceValidation.validateUnitCrossReferences(production, candidate, label);
    ProductionReferenceValidation.validateModeAssociations(definitions, candidate, label);
    ProductionReferenceValidation.validateModeStructures(definitions, candidate, label);
    HouseholdReferenceValidation.validateLaborActorIndustryCollision(definitions, candidate, label);
    MarketReferenceValidation.validateFlowsOnIndustryHex(definitions, candidate, label);
    MarketReferenceValidation.validateDemandHexes(definitions, candidate, label);
    HouseholdReferenceValidation.validateProductionRosters(
        definitions, production, candidate, label, enforcePlannedLaborBudget);
    HouseholdReferenceValidation.validateProductionWages(definitions, production, candidate, label);
    ProductionReferenceValidation.validateAssetOccupancies(production, candidate, label);
    MarketReferenceValidation.validateInventoryReservations(production, candidate, label);
  }

  public static void validate(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label,
      io.mosire.simos.economy.time.AccountInventorySnapshotLookup accountLookup) {
    validate(definitions, production, candidate, label);
    MarketReferenceValidation.validateInventoryReservations(candidate, accountLookup, label);
  }

  public static void validateAssetOccupancies(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    ProductionReferenceValidation.validateAssetOccupancies(production, candidate, label);
  }

  public static void validateInventoryReservations(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    MarketReferenceValidation.validateInventoryReservations(production, candidate, label);
  }

  public static void validateInventoryReservations(EconomyData candidate, String label) {
    MarketReferenceValidation.validateInventoryReservations(candidate, label);
  }

  public static void validateInventoryReservations(
      EconomyData candidate,
      io.mosire.simos.economy.time.AccountInventorySnapshotLookup accountLookup,
      String label) {
    MarketReferenceValidation.validateInventoryReservations(candidate, accountLookup, label);
  }

  public static void validateProductionRosters(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    HouseholdReferenceValidation.validateProductionRosters(
        definitions, production, candidate, label);
  }

  public static void validateProductionRosters(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label,
      boolean enforcePlannedLaborBudget) {
    HouseholdReferenceValidation.validateProductionRosters(
        definitions, production, candidate, label, enforcePlannedLaborBudget);
  }

  public static void validateProductionWages(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    HouseholdReferenceValidation.validateProductionWages(definitions, production, candidate, label);
  }

  public static void validateProductionInstances(ProductionRuntime runtime, String label) {
    ProductionReferenceValidation.validateProductionInstances(runtime, label);
  }

  public static void validateUnitsAndShares(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    ProductionReferenceValidation.validateUnitsAndShares(definitions, production, candidate, label);
  }

  public static void validateOutputQuantityOverrides(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      String label) {
    ProductionReferenceValidation.validateOutputQuantityOverrides(definitions, production, label);
  }

  public static void validateModeAssociations(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    ProductionReferenceValidation.validateModeAssociations(definitions, candidate, label);
  }

  public static void validateModeStructures(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    ProductionReferenceValidation.validateModeStructures(definitions, candidate, label);
  }

  public static void validateUnitCrossReferences(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    ProductionReferenceValidation.validateUnitCrossReferences(production, candidate, label);
  }

  public static void validateLaborActorIndustryCollision(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    HouseholdReferenceValidation.validateLaborActorIndustryCollision(definitions, candidate, label);
  }

  public static void validateFlowsOnIndustryHex(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    MarketReferenceValidation.validateFlowsOnIndustryHex(definitions, candidate, label);
  }

  public static void validateDemandHexes(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    MarketReferenceValidation.validateDemandHexes(definitions, candidate, label);
  }
}
