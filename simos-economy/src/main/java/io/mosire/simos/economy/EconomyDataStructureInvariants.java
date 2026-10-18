package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** EconomyData 规范构造期的结构/跨表不变量 helper（机械拆分；行为不变）。 */
final class EconomyDataStructureInvariants {
  private EconomyDataStructureInvariants() {}

  /** classStructures：键/值非空、键 == 行内 id，并冻结。 */
  static Map<ClassStructureId, ClassStructure> validateClassStructures(
      Map<ClassStructureId, ClassStructure> classStructures) {
    Map<ClassStructureId, ClassStructure> structuresCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassStructureId, ClassStructure> entry : classStructures.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classStructures 的键与值都不得为 null: " + entry.getKey());
      }
      ClassStructure structure = entry.getValue();
      if (!entry.getKey().equals(structure.id())) {
        throw new IllegalArgumentException(
            "classStructures 的键必须与 ClassStructure.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + structure.id());
      }
      structuresCopy.put(entry.getKey(), structure);
    }
    classStructures = Collections.unmodifiableMap(structuresCopy); // ★ 冻在赋值处
    return classStructures;
  }

  /** classPositions：键/值非空、键 == 行内 id，并判其与结构内位置的逐值一致。 */
  static Map<ClassPositionId, ProductionRole> validateClassPositions(
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<ClassStructureId, ClassStructure> classStructures) {
    Map<ClassStructureId, ClassStructure> structuresCopy = classStructures;

    Map<ClassPositionId, ProductionRole> positionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, ProductionRole> entry : classPositions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classPositions 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionRole position = entry.getValue();
      if (!entry.getKey().equals(position.id())) {
        throw new IllegalArgumentException(
            "classPositions 的键必须与 ProductionRole.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + position.id());
      }
      positionsCopy.put(entry.getKey(), position);
    }
    // ★★ 结构内的位置必须与全局位置表逐值相等；全局表也不得残留不属于任何结构的孤儿位置。
    //   同一身份只有一处权威形状，避免"结构里写一套、全局表里另写一套"（对侧为空 = 该侧尚未提供，见段首口径）。
    if (!positionsCopy.isEmpty()) {
      for (ClassStructure structure : structuresCopy.values()) {
        for (Map.Entry<ClassPositionId, ProductionRole> entry : structure.positions().entrySet()) {
          ProductionRole flat = positionsCopy.get(entry.getKey());
          if (flat == null || !flat.equals(entry.getValue())) {
            throw new IllegalArgumentException(
                "classStructures.positions 的位置必须与 classPositions 逐值一致：结构="
                    + structure.id()
                    + "，位置="
                    + entry.getKey());
          }
        }
      }
    }
    if (!structuresCopy.isEmpty()) {
      for (ProductionRole position : positionsCopy.values()) {
        boolean registered = false;
        for (ClassStructure structure : structuresCopy.values()) {
          if (position.equals(structure.positions().get(position.id()))) {
            registered = true;
            break;
          }
        }
        if (!registered) {
          throw new IllegalArgumentException(
              "classPositions 中的位置必须至少属于一个 ClassStructure：位置=" + position.id());
        }
      }
    }
    classPositions = Collections.unmodifiableMap(positionsCopy); // ★ 冻在赋值处
    return classPositions;
  }

  /** classStandings：键/值非空、家户与阶层位置引用完整性，并冻结。 */
  static Map<HouseholdId, HouseholdClassMembership> validateClassStandings(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy,
      Map<ClassPositionId, ProductionRole> positionsCopy) {
    Map<HouseholdId, HouseholdClassMembership> classMembershipsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdClassMembership> classMembershipEntry :
        classStandings.entrySet()) {
      if (classMembershipEntry.getKey() == null || classMembershipEntry.getValue() == null) {
        throw new IllegalArgumentException(
            "classStandings 的键与值都不得为 null: " + classMembershipEntry.getKey());
      }
      HouseholdClassMembership classMembership = classMembershipEntry.getValue();
      if (!classMembershipEntry.getKey().equals(classMembership.householdId())) {
        throw new IllegalArgumentException(
            "classStandings 的键必须与 ClassStanding.householdId 一致：键="
                + classMembershipEntry.getKey()
                + "，行内 householdId="
                + classMembership.householdId());
      }
      if (!householdEconomiesCopy.isEmpty()
          && !householdEconomiesCopy.containsKey(classMembershipEntry.getKey())) {
        throw new IllegalArgumentException(
            "classStandings 的家户必须是已存在的家户（S1 起身份与视图分离）：" + classMembershipEntry.getKey());
      }
      if (!positionsCopy.isEmpty()) {
        if (!positionsCopy.containsKey(classMembership.currentPositionId())) {
          throw new IllegalArgumentException(
              "ClassStanding.currentPositionId 必须是已存在的阶层位置：家户="
                  + classMembershipEntry.getKey()
                  + "，当前位置="
                  + classMembership.currentPositionId());
        }
        if (!positionsCopy.containsKey(classMembership.originalPositionId())) {
          throw new IllegalArgumentException(
              "ClassStanding.originalPositionId 必须是已存在的阶层位置：家户="
                  + classMembershipEntry.getKey()
                  + "，原所属="
                  + classMembership.originalPositionId());
        }
        for (ClassPositionId retained : classMembership.retainedShares().keySet()) {
          if (!positionsCopy.containsKey(retained)) {
            throw new IllegalArgumentException(
                "ClassStanding.retainedShares 的键必须是已存在的阶层位置：家户="
                    + classMembershipEntry.getKey()
                    + "，保留位置="
                    + retained);
          }
        }
        // ★★ P2-B：追加参与的生产位置（多生产方式）必须逐个是已存在的位置 —— 组织阶段按它们建
        //   生产组织/unit，悬空引用会让"可参与"变成组织期的静默跳过。
        for (ClassPositionId participating : classMembership.participatingPositionIds()) {
          if (!positionsCopy.containsKey(participating)) {
            throw new IllegalArgumentException(
                "ClassStanding.participatingPositionIds 必须是已存在的阶层位置：家户="
                    + classMembershipEntry.getKey()
                    + "，参与位置="
                    + participating);
          }
        }
      }
      classMembershipsCopy.put(classMembershipEntry.getKey(), classMembership);
    }
    classStandings = Collections.unmodifiableMap(classMembershipsCopy); // ★ 冻在赋值处
    return classStandings;
  }

  /** productionOrganizations：键身份 + 位置/资产/劳动引用完整性，并冻结。 */
  static Map<ProductionOrganizationId, ProductionEnterprise> validateProductionOrganizations(
      Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations,
      Map<ClassPositionId, ProductionRole> positionsCopy,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy) {
    Map<ProductionOrganizationId, ProductionEnterprise> enterprisesCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionOrganizationId, ProductionEnterprise> entry :
        productionOrganizations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "productionOrganizations 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionEnterprise enterprise = entry.getValue();
      if (!entry.getKey().equals(enterprise.id())) {
        throw new IllegalArgumentException(
            "productionOrganizations 的键必须与 ProductionEnterprise.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + enterprise.id());
      }
      // ★ B5（裁定十八）：原"生产组织的 modeId 必须已存在"一维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
      if (!positionsCopy.isEmpty() && !positionsCopy.containsKey(enterprise.classPositionId())) {
        throw new IllegalArgumentException(
            "生产组织的 classPositionId 必须是已存在的阶层位置：组织="
                + entry.getKey()
                + "，位置="
                + enterprise.classPositionId());
      }
      // ★ B2b：原"组织指名的 unit 必须已存在、且 organizer == unit.operator"两条守卫已移到跨片校验面
      //   （{@code EconomyReferenceValidation.validateUnitCrossReferences}）——它们要读 production 的实例投影。
      for (AssetShareId assetSource : enterprise.assetSources()) {
        if (!assetShares.isEmpty() && !assetShares.containsKey(assetSource)) {
          throw new IllegalArgumentException(
              "生产组织使用的资产份额必须已存在：组织=" + entry.getKey() + "，份额=" + assetSource);
        }
        if (!assetShares.isEmpty()) {
          OwnershipStake organizedShare = assetShares.get(assetSource);
          if (organizedShare != null && !organizedShare.operator().equals(enterprise.organizer())) {
            throw new IllegalArgumentException(
                "生产组织使用的 OwnershipStake 必须由 organizer 经营（operator 一致）：组织="
                    + entry.getKey()
                    + "，份额="
                    + assetSource
                    + "，份额 operator="
                    + organizedShare.operator());
          }
        }
      }
      for (HouseholdId laborSource : enterprise.laborSources()) {
        if (!householdEconomiesCopy.isEmpty() && !householdEconomiesCopy.containsKey(laborSource)) {
          throw new IllegalArgumentException(
              "生产组织的劳动来源家户必须已存在：组织=" + entry.getKey() + "，家户=" + laborSource);
        }
      }
      enterprisesCopy.put(entry.getKey(), enterprise);
    }
    productionOrganizations = Collections.unmodifiableMap(enterprisesCopy); // ★ 冻在赋值处
    return productionOrganizations;
  }

  /** assetRules：键/值非空、键 == 行内 id、(modeId, assetKind) 唯一，并冻结。 */
  static Map<AssetRuleId, AssetRule> validateAssetRules(Map<AssetRuleId, AssetRule> assetRules) {
    Map<AssetRuleId, AssetRule> assetRulesCopy = new LinkedHashMap<>();
    Set<String> assetRuleModeKindKeys = new LinkedHashSet<>();
    for (Map.Entry<AssetRuleId, AssetRule> entry : assetRules.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetRules 的键与值都不得为 null: " + entry.getKey());
      }
      AssetRule rule = entry.getValue();
      if (!entry.getKey().equals(rule.id())) {
        throw new IllegalArgumentException(
            "assetRules 的键必须与 AssetRule.id 一致：键=" + entry.getKey() + "，行内 id=" + rule.id());
      }
      // ★ B5（裁定十八）：原"生产资料规则的 modeId 必须已存在"一维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
      // ★ 身份 = (modeId, assetKind) 的纯函数 ⇒ 同一组合只允许一条规则（重复 = 同一件事两个拼写点）。
      String modeKindKey = rule.modeId().value() + "|" + rule.assetKind().name();
      if (!assetRuleModeKindKeys.add(modeKindKey)) {
        throw new IllegalArgumentException(
            "同一 (modeId, assetKind) 只允许一条 AssetRule（重复 = 同一件事两处拼写）: "
                + rule.modeId()
                + " / "
                + rule.assetKind());
      }
      assetRulesCopy.put(entry.getKey(), rule);
    }
    assetRules = Collections.unmodifiableMap(assetRulesCopy); // ★ 冻在赋值处
    return assetRules;
  }

  /** governments：键身份、国库唯一、GOV 单位国库家户、发行主体唯一，并冻结。 */
  static Map<GovernmentId, Government> validateGovernments(
      Map<GovernmentId, Government> governments) {
    Map<GovernmentId, Government> governmentsCopy = new LinkedHashMap<>();
    Set<ActorRef> treasuries = new LinkedHashSet<>();
    Map<CurrencyId, ActorRef> issuersByCurrency = new LinkedHashMap<>();
    for (Map.Entry<GovernmentId, Government> entry : governments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("governments 的键与值都不得为 null: " + entry.getKey());
      }
      Government government = entry.getValue();
      if (!entry.getKey().equals(government.id())) {
        throw new IllegalArgumentException(
            "governments 的键必须与 Government.id 一致：键=" + entry.getKey() + "，行内 id=" + government.id());
      }
      if (!treasuries.add(government.treasury())) {
        throw new IllegalArgumentException(
            "同一个国库 actor 不能同时是两届政府（发行记录无法归属）：" + government.treasury());
      }
      // ★★ P2-C §13.7：按 GOV 单位派生的政府记录必须把国库钉在该单位的政府家户上
      //   （id = gov-unit-<unitId> ⇒ 家户 id = hh-gov-<unitId>）。这条不变量在状态构造期判死：
      //   任何"登记了单位政府却指向别的家户/别的 actor"的数据都进不了世界，也不靠"先到者胜"兜底。
      GovernmentIds.unitRefOf(government.id())
          .ifPresent(
              unitRef -> {
                if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
                  throw new IllegalArgumentException(
                      "单位政府 "
                          + government.id()
                          + " 的国库必须是该 GOV 单位的政府家户账户（HOUSEHOLD）："
                          + government.treasury());
                }
                HouseholdId expected = GovernmentHouseholds.of(unitRef);
                HouseholdId actual = HouseholdActors.householdOf(government.treasury());
                if (!expected.equals(actual)) {
                  throw new IllegalArgumentException(
                      "单位政府 "
                          + government.id()
                          + " 的国库家户必须是 "
                          + expected
                          + "（govUnitId="
                          + unitRef
                          + "），实际="
                          + actual);
                }
              });
      for (CurrencyId currency : government.issuable()) {
        ActorRef issuer = government.authorityOf(currency);
        if (issuer == null) {
          throw new IllegalArgumentException(
              "Government.authorityOf 不得为 null：政府=" + government.id() + "，币种=" + currency);
        }
        ActorRef existing = issuersByCurrency.putIfAbsent(currency, issuer);
        if (existing != null && !existing.equals(issuer)) {
          throw new IllegalArgumentException(
              "同一币种只能有一个发行主体：币种="
                  + currency
                  + "，已有发行主体="
                  + existing
                  + "，新发行主体="
                  + issuer
                  + "（政府="
                  + government.id()
                  + "）");
        }
      }
      governmentsCopy.put(entry.getKey(), government);
    }
    governments = Collections.unmodifiableMap(governmentsCopy); // ★ 冻在赋值处
    return governments;
  }

  /** moneyIssuances：键身份 + 政府引用完整性，并冻结。 */
  static Map<MoneyIssuanceId, MoneyIssuanceRecord> validateMoneyIssuances(
      Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances,
      Map<GovernmentId, Government> governmentsCopy) {
    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuancesCopy = new LinkedHashMap<>();
    for (Map.Entry<MoneyIssuanceId, MoneyIssuanceRecord> entry : moneyIssuances.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("moneyIssuances 的键与值都不得为 null: " + entry.getKey());
      }
      MoneyIssuanceRecord record = entry.getValue();
      if (!entry.getKey().equals(record.id())) {
        throw new IllegalArgumentException(
            "moneyIssuances 的键必须与 MoneyIssuanceRecord.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + record.id());
      }
      if (!governmentsCopy.containsKey(record.governmentId())) {
        throw new IllegalArgumentException(
            "发行记录的 governmentId 必须是已存在的政府：记录="
                + entry.getKey()
                + "，governmentId="
                + record.governmentId());
      }
      issuancesCopy.put(entry.getKey(), record);
    }
    moneyIssuances = Collections.unmodifiableMap(issuancesCopy); // ★ 冻在赋值处
    return moneyIssuances;
  }

  /** currencies：旧词表缺省 + 键身份 + 政府可发行币种完整性，并冻结。 */
  static Map<CurrencyId, CurrencyDef> validateCurrencies(
      Map<CurrencyId, CurrencyDef> currencies, Map<GovernmentId, Government> governmentsCopy) {
    boolean legacyVocabulary = currencies == null || currencies.isEmpty();

    Map<CurrencyId, CurrencyDef> currenciesCopy = new LinkedHashMap<>();
    if (legacyVocabulary) {
      for (CurrencyDef def : MoneyVocabulary.legacyCurrencyDefs()) {
        currenciesCopy.put(def.currencyId(), def);
      }
    } else {
      for (Map.Entry<CurrencyId, CurrencyDef> entry : currencies.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("currencies 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().currencyId())) {
          throw new IllegalArgumentException(
              "currencies 的键必须与 CurrencyDef.currencyId() 一致（键 = 身份，不许两处拼币种名）：键="
                  + entry.getKey()
                  + "，行内 id="
                  + entry.getValue().id());
        }
        currenciesCopy.put(entry.getKey(), entry.getValue());
      }
    }
    // ★★ 跨表守卫：政府声称能发行的币种必须在币种表里有定义（说不出"这是什么钱"就不许发行它）。
    //   与上面"发行记录的 governmentId 必须已存在"同一条口径（引用完整性 fail-closed）。
    for (Government government : governmentsCopy.values()) {
      for (CurrencyId currency : government.issuable()) {
        if (!currenciesCopy.containsKey(currency)) {
          throw new IllegalArgumentException(
              "政府 "
                  + government.id()
                  + " 声称发行的币种在币种表里没有定义："
                  + currency
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "；先 economy.DefineCurrency 或把该币种写进创世词表）");
        }
      }
    }
    currencies = Collections.unmodifiableMap(currenciesCopy); // ★ 冻在赋值处
    return currencies;
  }

  /** moneyInstruments：旧词表缺省 + 键身份 + 币种引用完整性，并冻结。 */
  static Map<InstrumentId, MoneyInstrument> validateMoneyInstruments(
      Map<InstrumentId, MoneyInstrument> moneyInstruments,
      Map<CurrencyId, CurrencyDef> currenciesCopy,
      boolean legacyVocabulary) {
    Map<InstrumentId, MoneyInstrument> instrumentsCopy = new LinkedHashMap<>();
    boolean legacyInstruments = moneyInstruments == null || moneyInstruments.isEmpty();
    if (legacyInstruments && legacyVocabulary) {
      for (MoneyInstrument instrument : MoneyVocabulary.legacyInstruments()) {
        instrumentsCopy.put(instrument.id(), instrument);
      }
    } else if (!legacyInstruments) {
      for (Map.Entry<InstrumentId, MoneyInstrument> entry : moneyInstruments.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("moneyInstruments 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().id())) {
          throw new IllegalArgumentException(
              "moneyInstruments 的键必须与 MoneyInstrument.id() 一致：键="
                  + entry.getKey()
                  + "，行内 id="
                  + entry.getValue().id());
        }
        if (!currenciesCopy.containsKey(entry.getValue().currency())) {
          throw new IllegalArgumentException(
              "货币工具 "
                  + entry.getKey().value()
                  + " 的币种在币种表里没有定义："
                  + entry.getValue().currency()
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "）");
        }
        instrumentsCopy.put(entry.getKey(), entry.getValue());
      }
    }
    moneyInstruments = Collections.unmodifiableMap(instrumentsCopy); // ★ 冻在赋值处
    return moneyInstruments;
  }

  /** marketZones：键身份、成员格不重叠、法定币与官方汇率币种完整性，并冻结。 */
  static Map<MarketZoneId, MarketZone> validateMarketZones(
      Map<MarketZoneId, MarketZone> marketZones, Map<CurrencyId, CurrencyDef> currenciesCopy) {
    if (marketZones == null) {
      marketZones = Map.of(); // 旧档缺该键 ⇒ 空表（见上面的归一方向）
    }
    Map<MarketZoneId, MarketZone> marketZonesCopy = new LinkedHashMap<>();
    Map<HexCoord, MarketZoneId> hexOwner = new LinkedHashMap<>();
    List<MarketZone> zonesInCanonicalOrder = new ArrayList<>(marketZones.size());
    zonesInCanonicalOrder.addAll(marketZones.values());
    zonesInCanonicalOrder.sort(Comparator.comparing(zone -> zone.zoneId().value()));
    for (MarketZone zone : zonesInCanonicalOrder) {
      if (zone == null) {
        throw new IllegalArgumentException("marketZones 不得含 null");
      }
      MarketZoneId zoneId = zone.zoneId();
      if (!marketZones.containsKey(zoneId)) {
        throw new IllegalArgumentException(
            "marketZones 的键必须与 MarketZone.zoneId 一致（键 = 身份，不许两处拼区名）：值内 zoneId="
                + zoneId.value()
                + "，表内键="
                + marketZones.keySet());
      }
      for (HexCoord hex : zone.hexes()) {
        MarketZoneId owner = hexOwner.putIfAbsent(hex, zoneId);
        if (owner != null) {
          throw new IllegalArgumentException(
              "一个 hex 至多属于一个市场区（I22 单一权威）：hex="
                  + hex
                  + " 同时在 zone="
                  + owner.value()
                  + " 与 zone="
                  + zoneId.value()
                  + "（先把该格从其中一个区划出去：economy.ReassignZoneHexes）");
        }
      }
      if (!currenciesCopy.containsKey(zone.legalTender())) {
        throw new IllegalArgumentException(
            "市场区 "
                + zoneId.value()
                + " 的法定币在币种表里没有定义："
                + zone.legalTender()
                + "（币种表="
                + currencyIdsOf(currenciesCopy)
                + "；先 economy.DefineCurrency 或把该币种写进创世词表）");
      }
      for (OfficialRate rate : zone.officialRates().values()) {
        if (!currenciesCopy.containsKey(rate.base())) {
          throw new IllegalArgumentException(
              "市场区 "
                  + zoneId.value()
                  + " 的官方汇率标的币在币种表里没有定义："
                  + rate.base()
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "）");
        }
        if (!currenciesCopy.containsKey(rate.quote())) {
          throw new IllegalArgumentException(
              "市场区 "
                  + zoneId.value()
                  + " 的官方汇率计价币在币种表里没有定义："
                  + rate.quote()
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "）");
        }
      }
      marketZonesCopy.put(zoneId, zone);
    }
    marketZones = Collections.unmodifiableMap(marketZonesCopy); // ★ 冻在赋值处
    return marketZones;
  }

  /** 市场区表与价表的跨表守卫（成员格唯一、区法定币 == 价表计价币）。 */
  static void validateMarketTablesAgainstZones(
      Map<MarketZoneId, MarketTable> marketTables, Map<MarketZoneId, MarketZone> marketZones) {
    Map<HexCoord, MarketZoneId> hexOwner = new LinkedHashMap<>();
    for (MarketZone zone : marketZones.values()) {
      for (HexCoord hex : zone.hexes()) {
        hexOwner.putIfAbsent(hex, zone.zoneId());
      }
    }

    for (Map.Entry<MarketZoneId, MarketTable> entry : marketTables.entrySet()) {
      MarketZoneId tableId = entry.getKey();
      MarketTable table = entry.getValue();
      MarketZone zone = marketZones.get(tableId);
      if (zone != null) {
        if (!table.numeraire().equals(zone.legalTender())) {
          throw new IllegalArgumentException(
              "市场 "
                  + tableId.value()
                  + " 的价表计价币与区法定币不一致（双权威未合并）：价表="
                  + table.numeraire().value()
                  + "，法定币="
                  + zone.legalTender().value());
        }
        if (!table.hexes().isEmpty()) {
          throw new IllegalArgumentException(
              "持久形态下价表的成员格必须为空（成员格权威 = MarketZone.hexes，I22）：市场="
                  + tableId.value()
                  + "，价表成员格数="
                  + table.hexes().size());
        }
      } else if (table.hexes().isEmpty()) {
        throw new IllegalArgumentException(
            "派生市场（键不在市场区表里）必须有成员格（它是\"有市场的格\"的唯一来源）：市场=" + tableId.value());
      }
      for (HexCoord hex : table.hexes()) {
        MarketZoneId owner = hexOwner.get(hex);
        if (owner != null) {
          throw new IllegalArgumentException(
              "价表的成员格已被市场区覆盖（该格的价只能由该区的条目给）：市场="
                  + tableId.value()
                  + "，hex="
                  + hex
                  + "，区="
                  + owner.value());
        }
      }
    }
  }

  /** govMarketMandates：键身份 + 已登记政府 + 家户国库，并冻结。 */
  static Map<MarketMandateId, GovernmentMarketMandate> validateGovMarketMandates(
      Map<MarketMandateId, GovernmentMarketMandate> govMarketMandates,
      Map<GovernmentId, Government> governmentsCopy) {
    Map<MarketMandateId, GovernmentMarketMandate> govMarketMandatesCopy = new LinkedHashMap<>();
    for (Map.Entry<MarketMandateId, GovernmentMarketMandate> entry : govMarketMandates.entrySet()) {
      MarketMandateId mandateId = entry.getKey();
      GovernmentMarketMandate mandate = entry.getValue();
      if (mandateId == null || mandate == null) {
        throw new IllegalArgumentException("govMarketMandates 的键与值都不得为 null: " + mandateId);
      }
      if (!mandateId.equals(mandate.id())) {
        throw new IllegalArgumentException(
            "govMarketMandates 的键必须与 GovernmentMarketMandate.id 一致：键="
                + mandateId
                + "，行内 id="
                + mandate.id());
      }
      Government government = governmentsCopy.get(mandate.government());
      if (government == null) {
        throw new IllegalArgumentException(
            "govMarketMandates 的授权主体政府未登记（授权没有可执行的主体）: "
                + mandateId
                + " government="
                + mandate.government().value());
      }
      if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
        throw new IllegalArgumentException(
            "govMarketMandates 的授权主体国库必须是家户（账户主体只有家户）: "
                + mandateId
                + " government="
                + mandate.government().value()
                + " treasury="
                + government.treasury());
      }
      govMarketMandatesCopy.put(mandateId, mandate);
    }
    govMarketMandates = Collections.unmodifiableMap(govMarketMandatesCopy); // ★ 冻在赋值处
    return govMarketMandates;
  }

  /** marketRoundStats：键/值非空、规范序冻结。 */
  static Map<MarketZoneId, MarketRoundStats> validateMarketRoundStats(
      Map<MarketZoneId, MarketRoundStats> marketRoundStats) {
    if (marketRoundStats == null) {
      marketRoundStats = Map.of();
    }
    for (Map.Entry<MarketZoneId, MarketRoundStats> entry : marketRoundStats.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("marketRoundStats 的键与值都不得为 null: " + entry.getKey());
      }
    }
    marketRoundStats = MarketRoundStats.frozen(marketRoundStats); // ★ 规范序 + 冻在赋值处
    return marketRoundStats;
  }

  /** 币种表里全部 id 的可读清单（拒因/诊断用；保序）。 */
  private static List<String> currencyIdsOf(Map<CurrencyId, CurrencyDef> table) {
    List<String> ids = new ArrayList<>(table.size());
    for (CurrencyId currency : table.keySet()) {
      ids.add(currency.value());
    }
    return ids;
  }
}
