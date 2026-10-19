package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>S3.4 阶层分化：{@code classify(household)} 的唯一拼写点</b>—— 判据全部来自可观察状态：{@code OwnershipStake} +
 * {@code ProductionRules}（{@code laborSource}/{@code operator}/{@code inputSupplier}/{@code
 * residualOwner} + 规则受方） + {@code HouseholdLaborCommitment} + 地租/工资 + 债务；<b>没有</b>"产量达到 X
 * 就产生地主"这类硬编码产量阈值。
 *
 * <p>★★ <b>R3B.1 的字段口径</b>：{@code OwnershipStake} 有 {@code owner}（所有权主体）与 {@code
 * operator}（实际经营/使用主体） 两栏，本规则两栏都读（同一条份额 owner == operator 时只计一次）；旧 {@code UseRight.holder} 的读取对应
 * {@code operator}，判自耕/所有权的那几步结合 {@code owner}。真档里 {@code owner/operator} 常是<strong>产业的经营者
 * actor</strong>（{@code ESTATE:farm@…} / {@code WORKSHOP:craft@…} / {@code HOUSEHOLD:weave@…}）
 * ——它们不是家户行 ⇒ 绝大多数家户 {@code ownLand=0}、直接落到 {@code LANDLESS_LABORER}。本版按"本户在生产关系里 出什么/以什么身份参与"分类：
 *
 * <pre>
 * ① 直接 {@code OwnershipStake}（owner/operator 能反解成本状态里的家户）：
 *    有 LAND：
 *      · 净收地租且不是净卖劳动（{@code netLaborSold ≤ 0}）⇒ LANDLORD；
 *      · 净雇工（{@code netLaborSold < 0}）⇒ RICH_PEASANT；
 *      · 自耕/净劳动平衡（{@code netLaborSold == 0}）⇒ 债务压力高 ⇒ POOR_PEASANT，否则 MIDDLE_PEASANT；
 *      · 净卖劳动 ⇒ 地少（≤ 自耕阈值）或债务压力高 ⇒ POOR_PEASANT，否则 MIDDLE_PEASANT；
 *    无 LAND 但有 SMALL_TOOL/WORKSHOP（直接持有或本户是该关系的 operator/inputSupplier/residualOwner）⇒ ARTISAN；
 *    其它直接权利/经营身份（CATTLE 等尚未启用的资产）⇒ 无债 MIDDLE_PEASANT、有债 POOR_PEASANT。
 * ② 无直接权利时看 {@link ProductionRules} 与 {@code HouseholdLaborCommitment}：
 *    · 地租受方只作 LANDLORD 的辅助证据（结构上有租规则 + 净不卖劳动），不再被折成"家户自有土地"；
 *    · 本户是某产业的 operator/inputSupplier/residualOwner 时，即使份额登记在经营者 actor 名下，也按该身份给
 *      LAND/SMALL_TOOL 那一档的 rich/middle/poor（不得因"权利不在本户名下"直接判 landless）；
 *    · 纯劳动供给按 laborSource 判：
 *        TENANT  ⇒ 有投入/产出占有且债务不高 ⇒ MIDDLE_PEASANT，否则 POOR_PEASANT；
 *        SERF    ⇒ POOR_PEASANT（依附农）；
 *        FAMILY/SELF ⇒ MIDDLE_PEASANT（家庭自营）；债务压力高 ⇒ POOR_PEASANT；
 *        WAGE    ⇒ LANDLESS_LABORER。
 *    同一家户可以同时有多个关系（真档：既给庄园出 SERF 劳、也给家庭纺织出 FAMILY 劳）。本规则**优先自营/半自营**：
 *    只要本户在 FAMILY/SELF 关系里确有劳动且债务压力不高，就按"家庭自营"给 MIDDLE_PEASANT；债务压力高或只有依附劳动
 *    才落到 POOR_PEASANT。SERF 的"依附"仍是它的兜底身份，也是没有自营关系时的落点。
 * ③ 没有任何可观察证据（无权利、无关系、无劳动、无租）⇒ <b>保留当前 {@code HouseholdEconomy.view}</b>，不凭空发明
 *    {@code LANDLESS_LABORER}；{@code reason} 写明 {@code retainedCurrentView:noObservableEvidence}。
 * </pre>
 *
 * <p>★★ <b>地租受方的边界（计划 §S3.4 的"辅助证据"）</b>：地租规则受方仍可推出"本户是这块地的租权人/所有者近似"， 但它只用于两件事：① LANDLORD 的辅助判据；②
 * 把该产业算作租权受方的"控制活动"以便把雇入劳动计进净劳动（否则地主 的小额自劳会把"净不卖劳动"读成净卖劳动）。<b>不再</b>把推定的土地数量加进 {@code ownLand} 去伪造
 * rich/middle/poor。
 *
 * <p>★★ <b>允许聚合近似、禁止硬编码产量阈值</b>：{@code selfCultivationThreshold} 是**土地数量**阈值（千分亩），
 * 不是产量阈值；比例类聚合由可观察量的和算出（地租受方多于一个时按人数均分推定控制关系，<b>不</b>均分给 rich/middle）。
 *
 * <p>★★ <b>旧四档不受影响</b>：本规则返回的 {@link SocialClassId} 可以是旧四档，也可以是 S3 追加的 {@code
 * landless_laborer}/{@code artisan}/{@code official}；旧档的 parse 行为不变（见 {@code SocialClassId}）。
 * <b>写回</b>只发生在结算关账日，且只改 {@code HouseholdEconomy.view}（见 {@code 旧结算引擎（R3a 已删除）}）。
 *
 * <p>★★ <b>R4-B.4：纯派生</b>：分类不再读 {@code IndustryDefinitionView.slots}，也没有任何"槽位上限回退"分支；{@code
 * IndustryDefinitionView.slots} / {@code ClassSlot} 只作为生产方式内部的角色/劳动配置，不再是 {@code
 * HouseholdEconomy.view} 或参与率的上限来源。
 */
public final class HouseholdClassRule {

  /**
   * ★ "自耕规模"的土地数量阈值（千分亩）：{@code ownLand > 此值} 且净卖劳动时，才可能是中农而不是贫农。
   *
   * <p>★ 这是**土地数量**的配置阈值（计划 §S3.4 明文：不是产量阈值）；真档每户农业产能约 3,100 千分亩（3.1 亩）， 故 1,000 千分亩（1
   * 亩）是"明显高于自耕口粮地"的保守档位。V 阶段迁入 GM 参数目录。
   */
  public static final long SELF_CULTIVATION_THRESHOLD_MILLI_MU = 1_000L;

  /**
   * ★ 债务压力阈值（千分）：{@code debtPrincipal × 1000 ÷ 资产数量 ≥ 此值} 时，本可判中农的自耕户降为贫农。
   *
   * <p>★ 这是"债务维度"在分类里的唯一使用点（计划 §S3.4 的 {@code debtRatio}）；阈值是政策值，V 阶段迁入参数目录。
   */
  public static final long DEBT_STRESS_THRESHOLD_PER_MILLE = 1_000L;

  /** 分类结果（含可复核证据；读口与审计写回共用，避免两处各算一套）。 */
  public record Classification(
      SocialClassId stratum,
      String reason,
      long ownLandMilliMu,
      long rightQuantity,
      long laborSoldMilli,
      long laborHiredMilli,
      long netLaborSoldMilli,
      boolean rentEntitled,
      OptionalLong rentPaidMilli,
      boolean rentPaidComplete,
      long debtPrincipalMilli,
      long debtStressPerMille) {

    public Classification {
      Objects.requireNonNull(stratum, "stratum");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(rentPaidMilli, "rentPaidMilli");
    }
  }

  public static SocialClassId classify(
      EconomyData data,
      Map<ProductionUnitId, ProductionProcess> units,
      HouseholdId household,
      Optional<ProductionLedger> ledger) {
    return classifyDetailed(data, units, household, ledger).stratum();
  }

  public static Classification classifyDetailed(
      EconomyData data,
      Map<ProductionUnitId, ProductionProcess> units,
      HouseholdId household,
      Optional<ProductionLedger> ledger) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(units, "units（production 实例的只读投影，B2b 起必填）");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(ledger, "ledger");
    return Index.of(data, units).classify(household, ledger);
  }

  static Classification classifyDetailed(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      HouseholdId household,
      Optional<ProductionLedger> ledger) {
    return Index.of(
            assetShares, laborCommitments, units, industries, relations, householdEconomies, debts)
        .classify(household, ledger);
  }

  public static LaborSource laborSourceOf(
      EconomyData data, Map<ProductionUnitId, ProductionProcess> units, HouseholdId household) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(units, "units（production 实例的只读投影，B2b 起必填）");
    Objects.requireNonNull(household, "household");
    LaborSource result = null;
    for (HouseholdLaborCommitment laborCommitment : data.allocations().values()) {
      if (!laborCommitment.household().equals(household)) {
        continue;
      }
      ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
      if (!units.containsKey(unitId)) {
        continue; // activity 不是现存实例（自由家户劳动）：没有可读的关系来源
      }
      ProductionRules relation = data.relations().get(unitId);
      if (relation == null) {
        continue;
      }
      LaborSource source = relation.laborSource();
      if (result == null || rank(source) > rank(result)) {
        result = source;
      }
    }
    return result;
  }

  private static int rank(LaborSource source) {
    return switch (source) {
      case TENANT, SERF -> 2;
      case WAGE -> 1;
      case SELF, FAMILY -> 0;
    };
  }

  private HouseholdClassRule() {}

  /** 公开的派生索引壳：实现见 {@link ClassProjectionBook}，保留原嵌套类型名与 public API。 */
  public static final class Index extends ClassProjectionBook {

    Index(
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<DebtContractId, DebtContract> debts,
        SettlementIndex settlementIndex,
        AssetOccupancyBudget assetOccupancyBudget) {
      super(
          assetShares,
          laborCommitments,
          units,
          relations,
          householdEconomies,
          debts,
          settlementIndex,
          assetOccupancyBudget);
    }

    public static Index of(EconomyData data, Map<ProductionUnitId, ProductionProcess> units) {
      return ClassProjectionBook.build(data, units);
    }

    public static Index of(
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<DebtContractId, DebtContract> debts) {
      return ClassProjectionBook.build(
          assetShares, laborCommitments, units, industries, relations, householdEconomies, debts);
    }

    public static Index of(
        EconomyData data,
        Map<ProductionUnitId, ProductionProcess> units,
        AssetOccupancyBudget assetOccupancyBudget) {
      return ClassProjectionBook.build(data, units, assetOccupancyBudget);
    }

    public static Index of(
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<DebtContractId, DebtContract> debts,
        AssetOccupancyBudget assetOccupancyBudget) {
      return ClassProjectionBook.build(
          assetShares,
          laborCommitments,
          units,
          industries,
          relations,
          householdEconomies,
          debts,
          assetOccupancyBudget);
    }

    public static Index of(
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<DebtContractId, DebtContract> debts,
        SettlementIndex settlementIndex) {
      return ClassProjectionBook.build(
          assetShares,
          laborCommitments,
          units,
          industries,
          relations,
          householdEconomies,
          debts,
          settlementIndex);
    }

    public Classification classify(HouseholdId household, Optional<ProductionLedger> ledger) {
      return super.classify(household, ledger);
    }
  }
}
