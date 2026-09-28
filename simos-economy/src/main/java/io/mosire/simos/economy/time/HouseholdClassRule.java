package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.id.UseRightId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.UseRight;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ <b>S3.4 阶层分化：{@code classify(household)} 的唯一拼写点</b>—— 判据全部来自可观察状态：{@code UseRight} + {@code
 * LaborAllocation} + 生产关系里的租规则 + 债务；<b>没有</b>"产量达到 X 就产生地主"这类硬编码。
 *
 * <pre>
 * ownLand       = Σ UseRight[holder=本户, asset=LAND].quantity
 *               + Σ 本户有租权（rentEntitled）的产业产能[LAND] ÷ 该产业租权受方家户数
 * laborSold     = Σ LaborAllocation[household=本户].laborMilli
 * laborHired    = Σ 其他家户供给"本户持有使用权/租权"的产业的劳动 + 直接以本户 actor 为雇主的劳动
 * netLaborSold  = laborSold − laborHired
 * netRentIncome = ledger 里本户作为租金受方的实付（读不到 ⇒ OptionalLong.empty，不填 0）
 * debtStress    = debtPrincipal × 1000 ÷ max(1, 使用权总量 + 租权推定地)
 * </pre>
 *
 * <p>★★ <b>允许聚合近似、禁止硬编码产量阈值</b>：{@code selfCultivationThreshold} 是**土地数量**阈值（千分亩），
 * 不是产量阈值；比例类聚合由可观察量的和算出。★ "租权推定地"是一条**具名的聚合近似**：本批 {@code UseRight.holder} 记的是**经营者 actor**（庄园 / 作坊
 * / 产业型家户），不是 {@code HouseholdId}，而 {@code RegimeRelations} 的租规则受方已经是稳定的 {@code ToHousehold} ⇒
 * "谁是这块地的租权受方"是可观察事实；拿它给"所有权"一个可复算的近似（有多个租权受方时按人数均分）。 这不是"凭空生成人口/权利"：没有改任何 {@code UseRight}、账户或
 * {@code ClassRow} 的其它字段。
 *
 * <p>★★ <b>旧四档不受影响</b>：本规则返回的 {@link SocialClassId} 可以是旧四档，也可以是 S3 追加的 {@code
 * landless_laborer}/{@code artisan}/{@code official}；旧档的 parse 行为不变（见 {@code SocialClassId}）。
 * <b>写回</b>只发生在结算关账日，且只改 {@code ClassRow.view}（见 {@code EconomySettlement}）。
 */
public final class HouseholdClassRule {

  /**
   * ★ "自耕规模"的土地数量阈值（千分亩）：{@code ownLand > 此值} 且净雇工 ⇒ 富农。
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

  private HouseholdClassRule() {}

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

  /** 按可观察量给一个家户分档（纯函数；不写状态）。 */
  public static SocialClassId classify(
      EconomyData data, HouseholdId household, Optional<ProductionLedger> ledger) {
    return classifyDetailed(data, household, ledger).stratum();
  }

  /**
   * 分类 + 证据读数（与 {@link #classify} 同一条算法；只多返回可复核字段）。
   *
   * @param ledger 当日（关账日 = 本周期分配）账本；{@link Optional#empty()} ⇒ 租金实付读不到（不填 0，具名在返回值的 {@code
   *     rentPaidComplete=false} 与读口说明里）
   */
  public static Classification classifyDetailed(
      EconomyData data, HouseholdId household, Optional<ProductionLedger> ledger) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(ledger, "ledger");
    return Index.of(data).classify(household, ledger);
  }

  /** 结算侧入口：直接拿日结算的工作表分量，避免为一次分类构造整棵 {@code EconomyData}。 */
  static Classification classifyDetailed(
      Map<UseRightId, UseRight> useRights,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<IndustryId, Industry> industries,
      Map<IndustryId, ProductionRelation> relations,
      Map<HouseholdId, ClassRow> classes,
      Map<DebtId, Debt> debts,
      HouseholdId household,
      Optional<ProductionLedger> ledger) {
    return Index.of(useRights, allocations, industries, relations, classes, debts)
        .classify(household, ledger);
  }

  /**
   * 该家户本户供给的产业的劳动来源（分类 fallback 可读；多档时取"最强制度"：TENANT/SERF &gt; WAGE &gt; 其它）。
   *
   * <p>★ 保留为公开读口：V 阶段与上层状态机需要"制度身份"这一可观察量，而不只是最终阶层。
   */
  public static LaborSource laborSourceOf(EconomyData data, HouseholdId household) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
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

  /** 规则受方是不是租金形状：固定实物/货币租，或毛产分成（feudal 的"地租 300‰"就是后者）。 */
  private static boolean isRentShaped(CompensationRule rule) {
    return rule.type() == RuleType.FIXED_IN_KIND_RENT
        || rule.type() == RuleType.FIXED_MONEY_RENT
        || (rule.type() == RuleType.OUTPUT_SHARE && rule.pool() == Pool.GROSS_OUTPUT);
  }

  /**
   * ★★ <b>一次性索引</b>：把 {@code UseRight} / {@code LaborAllocation} / 租规则 / 债务折成逐家户可 O(1) 分类的判据。
   *
   * <p>★★ <b>为什么必须一次建索引</b>：关账日要对全部家户写回（真档 4,000 行），若每户都重扫全部关系与配额， 就是 O(行 × 关系 × 规则)
   * 的重复劳动；而且"同一事实只算一次"也要求这些派生量在一处生成。
   */
  public static final class Index {

    private final Map<HouseholdId, ClassRow> classes;
    private final Map<HouseholdId, Long> ownLandMilliMu = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> rightQuantity = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> communalRight = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> laborSold = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> laborHired = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> handicraft = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> tenantLabor = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> rentEntitled = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> inferredLand = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> debtPrincipal = new LinkedHashMap<>();
    private final Map<HouseholdId, Set<IndustryId>> ownedActivitiesByHousehold =
        new LinkedHashMap<>();
    private final Map<HouseholdId, Set<CompensationRule>> rentRulesByHousehold =
        new LinkedHashMap<>();
    private final Map<CompensationRule, HouseholdId> rentRuleRecipient = new LinkedHashMap<>();
    private final Set<CompensationRule> ambiguousRentRules = new LinkedHashSet<>();

    private Index(
        Map<UseRightId, UseRight> useRights,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<IndustryId, Industry> industries,
        Map<IndustryId, ProductionRelation> relations,
        Map<HouseholdId, ClassRow> classes,
        Map<DebtId, Debt> debts) {
      this.classes = new LinkedHashMap<>(classes);
      Map<ActorRef, HouseholdId> householdByActor = new LinkedHashMap<>();
      for (ClassRow row : classes.values()) {
        householdByActor.put(HouseholdActors.of(row.id()), row.id());
      }
      Map<CohortKey, HouseholdId> householdByView = new LinkedHashMap<>();
      Set<CohortKey> ambiguousViews = new LinkedHashSet<>();
      for (ClassRow row : classes.values()) {
        if (householdByView.putIfAbsent(row.view(), row.id()) != null) {
          ambiguousViews.add(row.view());
        }
      }
      for (CohortKey view : ambiguousViews) {
        householdByView.remove(view);
      }
      // ★ S3 审计：这张 view 索引每次 Index 构造都从**当前** ClassRow 行集合重建，只服务旧档 ToCohort
      //   的兼容解析（默认关系已在 EconomyData 构造期归一为 ToHousehold 稳定身份）；S3 阶层写回后它不会
      //   被当成身份缓存，也不会让新阶层"漏行"。

      // ① 使用权：直接持有（holder 就是本户 actor / 可反解为本户）的那一份。
      for (UseRight right : useRights.values()) {
        HouseholdId holder = householdOf(right.holder(), classes, householdByActor);
        if (holder == null) {
          continue;
        }
        rightQuantity.merge(holder, right.quantity(), Math::addExact);
        if (right.asset() == AssetKind.LAND) {
          ownLandMilliMu.merge(holder, right.quantity(), Math::addExact);
        }
        if (right.kind() == UseRight.RightKind.COMMUNAL && right.quantity() > 0L) {
          communalRight.put(holder, true);
        }
        ownedActivities(holder).add(right.activity());
      }

      // ② 租权：受方是稳定的 HouseholdId（EconomyData 归一化后的 ToHousehold）；用它推出"这块地的所有权近似"。
      Map<IndustryId, Set<HouseholdId>> rentRecipientsByActivity = new LinkedHashMap<>();
      for (ProductionRelation relation : relations.values()) {
        for (CompensationRule rule : relation.rules()) {
          if (!isRentShaped(rule)) {
            continue;
          }
          HouseholdId recipient =
              recipientHousehold(rule.recipient(), classes, householdByActor, householdByView);
          if (recipient == null) {
            continue;
          }
          rentEntitled.put(recipient, true);
          rentRulesByHousehold
              .computeIfAbsent(recipient, ignored -> new LinkedHashSet<>())
              .add(rule);
          rentRecipientsByActivity
              .computeIfAbsent(relation.activity(), ignored -> new LinkedHashSet<>())
              .add(recipient);
          HouseholdId previous = rentRuleRecipient.putIfAbsent(rule, recipient);
          if (previous != null && !previous.equals(recipient)) {
            ambiguousRentRules.add(rule);
          }
        }
      }
      for (Map.Entry<IndustryId, Set<HouseholdId>> entry : rentRecipientsByActivity.entrySet()) {
        Industry industry = industries.get(entry.getKey());
        if (industry == null || entry.getValue().isEmpty()) {
          continue;
        }
        long land = industry.capacity().getOrDefault(AssetKind.LAND, 0L);
        if (land <= 0L) {
          continue;
        }
        long share = land / entry.getValue().size();
        for (HouseholdId household : entry.getValue()) {
          inferredLand.merge(household, share, Math::addExact);
          ownedActivities(household).add(entry.getKey());
        }
      }
      for (Map.Entry<HouseholdId, Long> entry : inferredLand.entrySet()) {
        ownLandMilliMu.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }

      // ③ 劳动：本户卖出的劳动 + 本户经营/持有租权的产业雇入的劳动（含直接以本户为雇主的配额）。
      Map<IndustryId, Long> activityLaborTotal = new LinkedHashMap<>();
      Map<IndustryId, Map<HouseholdId, Long>> activityLaborByHousehold = new LinkedHashMap<>();
      Map<HouseholdId, Map<IndustryId, Long>> directHiredByActivity = new LinkedHashMap<>();
      for (LaborAllocation allocation : allocations.values()) {
        HouseholdId household = allocation.household();
        IndustryId activity = new IndustryId(allocation.actor().id());
        laborSold.merge(household, allocation.laborMilli(), Math::addExact);
        activityLaborTotal.merge(activity, allocation.laborMilli(), Math::addExact);
        activityLaborByHousehold
            .computeIfAbsent(activity, ignored -> new LinkedHashMap<>())
            .merge(household, allocation.laborMilli(), Math::addExact);
        HouseholdId employer = householdOf(allocation.actor(), classes, householdByActor);
        if (employer != null && !employer.equals(household)) {
          directHiredByActivity
              .computeIfAbsent(employer, ignored -> new LinkedHashMap<>())
              .merge(activity, allocation.laborMilli(), Math::addExact);
        }
        Industry industry = industries.get(activity);
        if (industry != null && RegimeOperators.HANDICRAFT.equals(industry.regime().value())) {
          handicraft.put(household, true);
        }
        ProductionRelation relation = relations.get(activity);
        if (relation != null && relation.laborSource() == LaborSource.TENANT) {
          tenantLabor.put(household, true);
        }
      }
      for (HouseholdId household : classes.keySet()) {
        long hired = 0L;
        Set<IndustryId> owned = ownedActivitiesOrEmpty(household);
        for (IndustryId activity : owned) {
          long total = activityLaborTotal.getOrDefault(activity, 0L);
          long self =
              activityLaborByHousehold.getOrDefault(activity, Map.of()).getOrDefault(household, 0L);
          hired += total - self;
        }
        Map<IndustryId, Long> direct = directHiredByActivity.getOrDefault(household, Map.of());
        for (Map.Entry<IndustryId, Long> entry : direct.entrySet()) {
          if (!owned.contains(entry.getKey())) {
            hired += entry.getValue();
          }
        }
        laborHired.put(household, hired);
      }

      // ④ 债务：本金合计（分类只用"本金 ÷ 资产数量"这一个比值）。
      for (Debt debt : debts.values()) {
        debtPrincipal.merge(debt.debtor(), debt.principal(), Math::addExact);
      }
    }

    public static Index of(EconomyData data) {
      Objects.requireNonNull(data, "data");
      return of(
          data.useRights(),
          data.allocations(),
          data.industries(),
          data.relations(),
          data.classes(),
          data.debts());
    }

    public static Index of(
        Map<UseRightId, UseRight> useRights,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<IndustryId, Industry> industries,
        Map<IndustryId, ProductionRelation> relations,
        Map<HouseholdId, ClassRow> classes,
        Map<DebtId, Debt> debts) {
      Objects.requireNonNull(useRights, "useRights");
      Objects.requireNonNull(allocations, "allocations");
      Objects.requireNonNull(industries, "industries");
      Objects.requireNonNull(relations, "relations");
      Objects.requireNonNull(classes, "classes");
      Objects.requireNonNull(debts, "debts");
      return new Index(useRights, allocations, industries, relations, classes, debts);
    }

    /** 按上面的可观察量给一家户分档；{@code ledger} 只用于租金实付读数（读不到 ⇒ empty，不填 0）。 */
    public Classification classify(HouseholdId household, Optional<ProductionLedger> ledger) {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(ledger, "ledger");
      ClassRow row = classes.get(household);
      if (row == null) {
        throw new IllegalArgumentException("分类的家户没有 ClassRow 行: " + household);
      }
      long ownLand = ownLandMilliMu.getOrDefault(household, 0L);
      long rights = rightQuantity.getOrDefault(household, 0L);
      long sold = laborSold.getOrDefault(household, 0L);
      long hired = laborHired.getOrDefault(household, 0L);
      long netLaborSold = sold - hired;
      boolean rent = rentEntitled.getOrDefault(household, false);
      long debt = debtPrincipal.getOrDefault(household, 0L);
      long assetBase = rights + inferredLand.getOrDefault(household, 0L);
      long debtStress = debtStressPerMille(debt, assetBase);
      boolean debtStressed = debtStress >= DEBT_STRESS_THRESHOLD_PER_MILLE;

      OptionalLong rentPaid = OptionalLong.empty();
      boolean rentPaidComplete = false;
      if (ledger.isPresent()) {
        Set<CompensationRule> entitled = rentRulesByHousehold.getOrDefault(household, Set.of());
        Set<CompensationRule> settled = new LinkedHashSet<>();
        long paid = 0L;
        boolean unmappedLedgerRule = false;
        for (ProductionSettlement.RuleSettlement reading : ledger.orElseThrow().ruleSettlements()) {
          if (!isRentShaped(reading.rule())) {
            continue;
          }
          if (ambiguousRentRules.contains(reading.rule())) {
            unmappedLedgerRule = true;
            continue;
          }
          HouseholdId recipient = rentRuleRecipient.get(reading.rule());
          if (recipient == null) {
            unmappedLedgerRule = true;
            continue;
          }
          if (recipient.equals(household)) {
            settled.add(reading.rule());
            paid = Math.addExact(paid, reading.paidNow());
          }
        }
        // ★ 只有"本户全部租权规则都在本账本里被结算过"才算完整；否则宁可不给数（Optional.empty），
        //   不拿"部分规则的和"或 0 冒充整周期实付。
        rentPaidComplete = !unmappedLedgerRule && settled.containsAll(entitled);
        rentPaid = rentPaidComplete ? OptionalLong.of(paid) : OptionalLong.empty();
      }

      boolean tenant = tenantLabor.getOrDefault(household, false);
      boolean handi = handicraft.getOrDefault(household, false);
      boolean communal = communalRight.getOrDefault(household, false);
      SocialClassId stratum;
      String reason;
      if (rent && netLaborSold <= 0L) {
        stratum = SocialClassId.LANDLORD;
        reason =
            "landlord:rentEntitled,netLaborSoldMilli="
                + netLaborSold
                + ",ownLandMilliMu="
                + ownLand;
      } else if (ownLand > SELF_CULTIVATION_THRESHOLD_MILLI_MU && netLaborSold < 0L) {
        stratum = SocialClassId.RICH_PEASANT;
        reason = "rich_peasant:netLaborSoldMilli=" + netLaborSold + ",ownLandMilliMu=" + ownLand;
      } else if (ownLand > 0L && netLaborSold == 0L) {
        if (debtStressed) {
          stratum = SocialClassId.POOR_PEASANT;
          reason = "poor_peasant:debtStressPerMille=" + debtStress + ",ownLandMilliMu=" + ownLand;
        } else {
          stratum = SocialClassId.MIDDLE_PEASANT;
          reason = "middle_peasant:ownLandMilliMu=" + ownLand;
        }
      } else if (ownLand > 0L && (netLaborSold > 0L || debtStressed)) {
        stratum = SocialClassId.POOR_PEASANT;
        reason =
            "poor_peasant:netLaborSoldMilli="
                + netLaborSold
                + ",debtStressPerMille="
                + debtStress
                + ",ownLandMilliMu="
                + ownLand;
      } else if (ownLand == 0L && netLaborSold > 0L && tenant) {
        stratum = SocialClassId.POOR_PEASANT;
        reason = "poor_peasant:tenantLabor,netLaborSoldMilli=" + netLaborSold;
      } else if (ownLand == 0L && netLaborSold > 0L && handi) {
        stratum = SocialClassId.ARTISAN;
        reason = "artisan:handicraftLabor,netLaborSoldMilli=" + netLaborSold;
      } else if (ownLand == 0L && netLaborSold > 0L) {
        stratum = SocialClassId.LANDLESS_LABORER;
        reason = "landless_laborer:netLaborSoldMilli=" + netLaborSold;
      } else if (communal) {
        stratum = SocialClassId.OFFICIAL;
        reason = "official:communalRight";
      } else {
        // 制度 fallback：没有任何可占有资产、也没有可观察的净卖出劳动 ⇒ 无地劳动者（DESTITUTE 的生计状态在
        // HouseholdCondition，不在阶层词表）。
        stratum = SocialClassId.LANDLESS_LABORER;
        reason = "landless_laborer:noAssetNoNetLabor";
      }
      return new Classification(
          stratum,
          reason,
          ownLand,
          rights,
          sold,
          hired,
          netLaborSold,
          rent,
          rentPaid,
          rentPaidComplete,
          debt,
          debtStress);
    }

    private Set<IndustryId> ownedActivities(HouseholdId household) {
      return ownedActivitiesByHousehold.computeIfAbsent(
          household, ignored -> new LinkedHashSet<>());
    }

    private Set<IndustryId> ownedActivitiesOrEmpty(HouseholdId household) {
      return ownedActivitiesByHousehold.getOrDefault(household, Set.of());
    }

    /** 债务压力（千分）：{@code debt × 1000 ÷ max(1, 资产数量)}；无资产但欠债 ⇒ {@link Long#MAX_VALUE}。 */
    private static long debtStressPerMille(long debt, long assetBase) {
      if (debt <= 0L) {
        return 0L;
      }
      if (assetBase <= 0L) {
        return Long.MAX_VALUE;
      }
      BigInteger ratio =
          BigInteger.valueOf(debt)
              .multiply(BigInteger.valueOf(1_000L))
              .divide(BigInteger.valueOf(assetBase));
      return ratio.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
          ? Long.MAX_VALUE
          : ratio.longValue();
    }
  }

  /** actor 引用 → 本状态里真实存在的家户（经营者 actor 与产业型 HOUSEHOLD actor 都不算）。 */
  private static HouseholdId householdOf(
      ActorRef actor, Map<HouseholdId, ClassRow> classes, Map<ActorRef, HouseholdId> byActor) {
    HouseholdId exact = byActor.get(actor);
    if (exact != null) {
      return exact;
    }
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      return null;
    }
    HouseholdId parsed = HouseholdActors.householdOf(actor);
    return classes.containsKey(parsed) ? parsed : null;
  }

  /** 规则受方 → 本状态里真实存在的家户（{@code ToCohort} 走视图索引；歧义视图 ⇒ null，不猜）。 */
  private static HouseholdId recipientHousehold(
      Recipient recipient,
      Map<HouseholdId, ClassRow> classes,
      Map<ActorRef, HouseholdId> byActor,
      Map<CohortKey, HouseholdId> byView) {
    return switch (recipient) {
      case Recipient.ToHousehold toHousehold ->
          classes.containsKey(toHousehold.household()) ? toHousehold.household() : null;
      case Recipient.ToActor toActor -> householdOf(toActor.actor(), classes, byActor);
      case Recipient.ToCohort toCohort -> byView.get(toCohort.cohort());
    };
  }
}
