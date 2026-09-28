package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.UseRight;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>S3.4 阶层分化：{@code classify(household)} 的唯一拼写点</b>—— 全部判据都是可观察量的和 （{@code UseRight} + {@code
 * LaborAllocation} + 地租规则 + 债务），<b>没有</b>"产量达到 X 就产生地主"这类硬编码。
 *
 * <pre>
 * ownLand       = Σ UseRight[holder=本户, asset=LAND].quantity
 * laborSold     = Σ LaborAllocation[household=本户].laborMilli           // 本户卖出的劳动
 * laborHired    = Σ LaborAllocation[actor=本户 actor].laborMilli         // 本户雇入的劳动
 * netLaborSold  = laborSold − laborHired                                  // >0 = 净卖劳动、<0 = 净雇工
 * netRentIncome = Σ 本户作为受方的租规则实付（ledger 瞬态；读不到 ⇒ 0 并如实标注）
 * </pre>
 *
 * <p>★★ <b>允许聚合近似、禁止硬编码产量阈值</b>：{@code selfCultivationThreshold} 是**土地数量**阈值（千分亩），
 * 不是产量阈值；比例类聚合由调用方用可观察量的和做分子/分母（见 {@code ApiViews} 的逐户分类结果）。
 *
 * <p>★ <b>旧四档不受影响</b>：本规则返回的 {@link SocialClassId} 可以是旧四档，也可以是 S3 追加的 {@code
 * landless_laborer}/{@code artisan}/{@code official}；旧档的 parse 行为不变（见 {@code SocialClassId}）。
 */
public final class HouseholdClassRule {

  /**
   * ★ "自耕规模"的土地数量阈值（千分亩）：{@code ownLand > 此值} 且净雇工 ⇒ 富农。
   *
   * <p>★ 这是**土地数量**的配置阈值（计划 §S3.4 明文：不是产量阈值）；真档每户农业产能约 3,100 千分亩（3.1 亩）， 故 1,000 千分亩（1
   * 亩）是"明显高于自耕口粮地"的保守档位。V 阶段迁入 GM 参数目录。
   */
  public static final long SELF_CULTIVATION_THRESHOLD_MILLI_MU = 1_000L;

  private HouseholdClassRule() {}

  /** 按可观察量给一个家户分档（纯函数；不写状态）。 */
  public static SocialClassId classify(
      EconomyData data, HouseholdId household, Optional<ProductionLedger> ledger) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(ledger, "ledger");
    ActorRef selfActor = HouseholdActors.of(household);
    long ownLand = 0L;
    long rightQuantity = 0L;
    boolean communalRight = false;
    for (UseRight right : data.useRights().values()) {
      if (!right.holder().equals(selfActor)) {
        continue;
      }
      rightQuantity += right.quantity();
      if (right.asset() == AssetKind.LAND) {
        ownLand += right.quantity();
      }
      if (right.kind() == UseRight.RightKind.COMMUNAL && right.quantity() > 0L) {
        communalRight = true;
      }
    }
    long laborSold = 0L;
    long laborHired = 0L;
    boolean handicraft = false;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (allocation.household().equals(household)) {
        laborSold += allocation.laborMilli();
      } else if (allocation.actor().equals(selfActor)) {
        laborHired += allocation.laborMilli();
      }
      if (allocation.actor().equals(selfActor) || allocation.household().equals(household)) {
        Industry industry = data.industries().get(new IndustryId(allocation.actor().id()));
        if (industry != null && RegimeOperators.HANDICRAFT.equals(industry.regime().value())) {
          handicraft = true;
        }
      }
    }
    long netLaborSold = laborSold - laborHired;
    long netRentIncome = 0L;
    if (ledger.isPresent()) {
      for (ProductionSettlement.RuleSettlement reading : ledger.orElseThrow().ruleSettlements()) {
        if (isRent(reading.rule().type())
            && recipientIs(reading.rule(), household)
            && reading.paidNow() > 0L) {
          netRentIncome += reading.paidNow();
        }
      }
    }
    boolean hasRight = rightQuantity > 0L;
    if (!hasRight && netLaborSold > 0L && netRentIncome == 0L) {
      return SocialClassId.LANDLESS_LABORER;
    }
    if (netRentIncome > 0L && netLaborSold <= 0L) {
      return SocialClassId.LANDLORD;
    }
    if (ownLand > SELF_CULTIVATION_THRESHOLD_MILLI_MU && netLaborSold < 0L) {
      return SocialClassId.RICH_PEASANT;
    }
    if (ownLand > 0L && netLaborSold == 0L && netRentIncome == 0L) {
      return SocialClassId.MIDDLE_PEASANT;
    }
    if (ownLand > 0L && (netLaborSold > 0L || netRentIncome < 0L)) {
      return SocialClassId.POOR_PEASANT;
    }
    if (handicraft) {
      return SocialClassId.ARTISAN;
    }
    if (communalRight) {
      return SocialClassId.OFFICIAL;
    }
    // 制度 fallback：有劳动来源（TENANT/SERF/WAGE）但没有任何可占有资产 ⇒ 落到无地劳动者档；
    // 完全没有可观察劳动/资产的家户 ⇒ 也归此档（DESTITUTE 的**生计状态**在 HouseholdCondition，不在阶层词表）。
    return SocialClassId.LANDLESS_LABORER;
  }

  /** 规则受方是否本户（与 {@code HouseholdCondition} 同口径；分类只读实付额）。 */
  private static boolean recipientIs(CompensationRule rule, HouseholdId household) {
    return switch (rule.recipient()) {
      case Recipient.ToHousehold toHousehold -> toHousehold.household().equals(household);
      case Recipient.ToActor toActor -> toActor.actor().equals(HouseholdActors.of(household));
      case Recipient.ToCohort toCohort ->
          HouseholdActors.of(toCohort.cohort()).equals(HouseholdActors.of(household));
    };
  }

  private static boolean isRent(RuleType type) {
    return type == RuleType.FIXED_IN_KIND_RENT || type == RuleType.FIXED_MONEY_RENT;
  }

  /** 该家户本户供给的产业的劳动来源（分类 fallback 可读；多档时取"最强制度"：TENANT/SERF > WAGE > 其它）。 */
  public static LaborSource laborSourceOf(EconomyData data, HouseholdId household) {
    LaborSource result = null;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (!allocation.household().equals(household)) {
        continue;
      }
      ProductionRelation relation = data.relations().get(new IndustryId(allocation.actor().id()));
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
}
