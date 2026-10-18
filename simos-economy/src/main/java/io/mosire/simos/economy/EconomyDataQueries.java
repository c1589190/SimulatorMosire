package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.model.CommodityFreightBase;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** EconomyData 的纯读口 helper（机械拆分；行为不变）。 */
final class EconomyDataQueries {
  private EconomyDataQueries() {}

  /** W10：政策读口；meta 空 ⇒ 键的具名缺省。 */
  static long economyPolicyOf(Optional<EconomyMeta> meta, EconomyPolicyKey key) {
    Objects.requireNonNull(key, "key");
    return meta.map(value -> value.policyOf(key)).orElseGet(key::defaultValue);
  }

  /** W10：该政策键是否被显式配置过。 */
  static boolean economyPolicyConfigured(Optional<EconomyMeta> meta, EconomyPolicyKey key) {
    Objects.requireNonNull(key, "key");
    return meta.map(value -> value.economyPolicies().containsKey(key)).orElse(false);
  }

  /** 商品基础运费读口（缺键 ⇒ CommodityFreightBase 的具名缺省）。 */
  static long commodityFreightBaseMilliOf(
      Map<CommodityId, Long> commodityFreightBaseMilli, CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    Long configured = commodityFreightBaseMilli.get(commodity);
    return configured == null ? CommodityFreightBase.legacyMilli(commodity) : configured;
  }

  /** GOV 单位对应的政府记录；没有登记 ⇒ fail-closed。 */
  static Government governmentOf(Map<GovernmentId, Government> governments, String govUnitId) {
    Objects.requireNonNull(govUnitId, "govUnitId");
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(govUnitId);
    } catch (IllegalArgumentException bad) {
      throw new IllegalArgumentException(
          "GOV 单位 id 不能派生政府身份（GovernmentIds.ofUnit）: " + govUnitId, bad);
    }
    Government government = governments.get(governmentId);
    if (government == null) {
      throw new IllegalArgumentException(
          "该 GOV 单位没有对应的政府记录（本世界没登记过它，或它不是 GOV 单位）: " + govUnitId + " ⇒ " + governmentId.value());
    }
    return government;
  }

  /** GOV 单位对应的政府记录；没有登记 ⇒ 中性缺省政府壳。 */
  static Government governmentOrDefault(
      Map<GovernmentId, Government> governments, String govUnitId) {
    Objects.requireNonNull(govUnitId, "govUnitId");
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(govUnitId);
    } catch (IllegalArgumentException bad) {
      return neutralGovernment(govUnitId);
    }
    Government government = governments.get(governmentId);
    return government == null ? neutralGovernment(govUnitId) : government;
  }

  /**
   * 中性缺省政府壳（{@link #governmentOrDefault(String)} 专用）：三类政策都取各自的中性值，其余字段取"无主体"的合法占位。
   *
   * <p>★ 国库用 {@code GOVERNMENT/<govUnitId>} 的占位 actor（{@code Government.treasury} 的 ActorKind 判据只认
   * {@code GOVERNMENT}/{@code HOUSEHOLD}）；{@code issuable} 空集 = 非发行人；铸币旋钮 0 =
   * 不许铸币。它的<b>唯一用途</b>是承载中性政策值， 调用方只应读它的三个政策字段。
   */
  private static Government neutralGovernment(String govUnitId) {
    return new Government(
        new GovernmentId(GovernmentIds.UNIT_PREFIX + govUnitId),
        "neutral",
        new ActorRef(ActorKind.GOVERNMENT, govUnitId),
        Set.of());
  }

  /** 某家户作为债务人的全部合同 id（保序不可变清单）。 */
  static List<DebtContractId> debtsOf(
      Map<HouseholdDebtReference, Boolean> householdDebtRefs, HouseholdId household) {
    Objects.requireNonNull(household, "household 不得为 null");
    List<DebtContractId> ids = new ArrayList<>();
    for (HouseholdDebtReference reference : householdDebtRefs.keySet()) {
      if (reference.household().equals(household)) {
        ids.add(reference.contract());
      }
    }
    return Collections.unmodifiableList(ids);
  }

  /** economy 已占用的格键（规范 q_r；只认家户行）。 */
  static Set<String> occupiedHexKeys(Map<HouseholdId, HouseholdEconomy> classes) {
    Set<String> hexes = new LinkedHashSet<>();
    for (HouseholdEconomy householdEconomy : classes.values()) {
      hexes.add(
          IndustryHexKeys.hexKey(
              householdEconomy.view().hex().q(), householdEconomy.view().hex().r()));
    }
    return hexes;
  }
}
