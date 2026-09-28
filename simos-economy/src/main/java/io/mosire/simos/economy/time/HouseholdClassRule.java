package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ <b>S3.4 阶层分化：{@code classify(household)} 的唯一拼写点</b>—— 判据全部来自可观察状态：{@code AssetShare} + {@code
 * ProductionRelation}（{@code laborSource}/{@code operator}/{@code inputSupplier}/{@code
 * residualOwner} + 规则受方） + {@code LaborAllocation} + 地租/工资 + 债务；<b>没有</b>"产量达到 X 就产生地主"这类硬编码产量阈值。
 *
 * <p>★★ <b>R3B.1 的字段口径</b>：{@code AssetShare} 有 {@code owner}（所有权主体）与 {@code operator}（实际经营/使用主体）
 * 两栏，本规则两栏都读（同一条份额 owner == operator 时只计一次）；旧 {@code UseRight.holder} 的读取对应 {@code
 * operator}，判自耕/所有权的那几步结合 {@code owner}。真档里 {@code owner/operator} 常是<strong>产业的经营者
 * actor</strong>（{@code ESTATE:farm@…} / {@code WORKSHOP:craft@…} / {@code HOUSEHOLD:weave@…}）
 * ——它们不是家户行 ⇒ 绝大多数家户 {@code ownLand=0}、直接落到 {@code LANDLESS_LABORER}。本版按"本户在生产关系里 出什么/以什么身份参与"分类：
 *
 * <pre>
 * ① 直接 {@code AssetShare}（owner/operator 能反解成本状态里的家户）：
 *    有 LAND：
 *      · 净收地租且不是净卖劳动（{@code netLaborSold ≤ 0}）⇒ LANDLORD；
 *      · 净雇工（{@code netLaborSold < 0}）⇒ RICH_PEASANT；
 *      · 自耕/净劳动平衡（{@code netLaborSold == 0}）⇒ 债务压力高 ⇒ POOR_PEASANT，否则 MIDDLE_PEASANT；
 *      · 净卖劳动 ⇒ 地少（≤ 自耕阈值）或债务压力高 ⇒ POOR_PEASANT，否则 MIDDLE_PEASANT；
 *    无 LAND 但有 TOOL/WORKSHOP（直接持有或本户是该关系的 operator/inputSupplier/residualOwner）⇒ ARTISAN；
 *    其它直接权利/经营身份（CATTLE 等尚未启用的资产）⇒ 无债 MIDDLE_PEASANT、有债 POOR_PEASANT。
 * ② 无直接权利时看 {@link ProductionRelation} 与 {@code LaborAllocation}：
 *    · 地租受方只作 LANDLORD 的辅助证据（结构上有租规则 + 净不卖劳动），不再被折成"家户自有土地"；
 *    · 本户是某产业的 operator/inputSupplier/residualOwner 时，即使份额登记在经营者 actor 名下，也按该身份给
 *      LAND/TOOL 那一档的 rich/middle/poor（不得因"权利不在本户名下"直接判 landless）；
 *    · 纯劳动供给按 laborSource 判：
 *        TENANT  ⇒ 有投入/产出占有且债务不高 ⇒ MIDDLE_PEASANT，否则 POOR_PEASANT；
 *        SERF    ⇒ POOR_PEASANT（依附农）；
 *        FAMILY/SELF ⇒ MIDDLE_PEASANT（家庭自营）；债务压力高 ⇒ POOR_PEASANT；
 *        WAGE    ⇒ LANDLESS_LABORER。
 *    同一家户可以同时有多个关系（真档：既给庄园出 SERF 劳、也给家庭纺织出 FAMILY 劳）。本规则**优先自营/半自营**：
 *    只要本户在 FAMILY/SELF 关系里确有劳动且债务压力不高，就按"家庭自营"给 MIDDLE_PEASANT；债务压力高或只有依附劳动
 *    才落到 POOR_PEASANT。SERF 的"依附"仍是它的兜底身份，也是没有自营关系时的落点。
 * ③ 没有任何可观察证据（无权利、无关系、无劳动、无租）⇒ <b>保留当前 {@code ClassRow.view}</b>，不凭空发明
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
 * <b>写回</b>只发生在结算关账日，且只改 {@code ClassRow.view}（见 {@code EconomySettlement}）。
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

  /**
   * ★ 旧四档：有创世槽位与参与率上限（见 {@code EconomyData} 的槽位守卫）；S3 派生阶层没有创世槽位、也不设参与率上限。
   *
   * <p>它不是"第二份阶层表"：四档的常量值与判定顺序仍以 {@code SocialClassId} 为唯一来源；这里只回答"哪些档位受槽位上限约束"， 供 {@link Index}
   * 在分类时避免派生出一个参与率超过新档位上限的组合（构造期守卫会拒收那种状态）。
   */
  private static final Set<SocialClassId> LEGACY_TIER_STRATA =
      Set.of(
          SocialClassId.POOR_PEASANT,
          SocialClassId.MIDDLE_PEASANT,
          SocialClassId.RICH_PEASANT,
          SocialClassId.LANDLORD);

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
      Map<AssetShareId, AssetShare> assetShares,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<HouseholdId, ClassRow> classes,
      Map<DebtId, Debt> debts,
      HouseholdId household,
      Optional<ProductionLedger> ledger) {
    return Index.of(assetShares, allocations, units, industries, relations, classes, debts)
        .classify(household, ledger);
  }

  /**
   * 该家户本户供给的产业的劳动来源（分类 fallback 可读；多档时取"最强制度"：TENANT/SERF &gt; WAGE &gt; 其它）。
   *
   * <p>★ 保留为公开读口：V 阶段与上层状态机需要"制度身份"这一可观察量，而不只是最终阶层。★ <b>它不是 {@code classify}
   * 的缩写</b>：分类还要读同户的多条关系、债务与资产；这里只回答"最强制度身份是哪一档"。
   */
  public static LaborSource laborSourceOf(EconomyData data, HouseholdId household) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
    LaborSource result = null;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (!allocation.household().equals(household)) {
        continue;
      }
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      if (!data.units().containsKey(unitId)) {
        continue; // activity 不是现存 unit（自由家户劳动）：没有可读的关系来源
      }
      ProductionRelation relation = data.relations().get(unitId);
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
   * ★★ <b>一次性索引</b>：把 {@code AssetShare} / 生产关系 / {@code LaborAllocation} / 租规则 / 债务折成逐家户可 O(1)
   * 分类的判据。
   *
   * <p>★★ <b>为什么必须一次建索引</b>：关账日要对全部家户写回（真档 4,000+ 行），若每户都重扫全部关系与配额， 就是 O(行 × 关系 × 规则)
   * 的重复劳动；而且"同一事实只算一次"也要求这些派生量在一处生成。
   */
  public static final class Index {

    private final Map<HouseholdId, ClassRow> classes;

    /** 产业+经营者 → 该 (industry, operator) 的 unit 列表（份额事实折算到 unit 的桥；见 mergeDirectShare）。 */
    private final Map<String, List<ProductionUnitId>> unitsByIndustryOperator =
        new LinkedHashMap<>();

    /**
     * 逐格（以及无格键产业）的槽位参与率上限：{@code hexKey → (旧四档 → min 上限)}。
     *
     * <p>★ 只读构造期数据，用来避免分类派生出一个"参与率 > 新档位上限"的旧四档组合 —— 那种组合会被 {@code EconomyData} 的构造期守卫拒收（真档 tick120
     * 实测：poor_peasant 行 950‰ 派生 middle_peasant 上限 900‰ ⇒ 整批 advance 失败）。它不是新阈值，值全部取自 {@link
     * Industry#slots()}。
     */
    private final Map<String, Map<SocialClassId, Long>> slotCapsByHex = new LinkedHashMap<>();

    /** 无格键产业（对任意格都算"可能"）的最紧槽位上限；口径与 {@code EconomyData.requireStratumAllowed} 一致。 */
    private final Map<SocialClassId, Long> universalSlotCaps = new LinkedHashMap<>();

    // ── 直接资产份额（owner/operator 能反解为本状态里的家户）────────────────────────────
    private final Map<HouseholdId, Long> directRightQuantity = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> directLandMilliMu = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> directToolOrWorkshop = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> communalRight = new LinkedHashMap<>();
    private final Map<HouseholdId, Set<ProductionUnitId>> directActivities = new LinkedHashMap<>();
    // ── 本户是 operator / inputSupplier / residualOwner 的产业（AssetShare 可能不在本户名下）────
    private final Map<HouseholdId, Long> operatedLandMilliMu = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> operatedToolOrWorkshop = new LinkedHashMap<>();
    private final Map<HouseholdId, Set<ProductionUnitId>> operatedActivities =
        new LinkedHashMap<>();
    // ── 租规则受方（只作 LANDLORD 辅助证据 + 推定控制活动）────────────────────────────────
    private final Map<HouseholdId, Long> rentInferredLandMilliMu = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> rentEntitled = new LinkedHashMap<>();
    private final Map<HouseholdId, Set<CompensationRule>> rentRulesByHousehold =
        new LinkedHashMap<>();
    private final Map<CompensationRule, HouseholdId> rentRuleRecipient = new LinkedHashMap<>();
    private final Set<CompensationRule> ambiguousRentRules = new LinkedHashSet<>();
    // ── 产出/投入占有（TENANT 的"占有部分投入/产出"判据）──────────────────────────────────
    private final Map<HouseholdId, Set<ProductionUnitId>> productionStakeActivities =
        new LinkedHashMap<>();
    // ── 净劳动：控制的活动里"别人出的劳动"算雇入（真档的农场全部由庄园雇入）────────────────
    private final Map<HouseholdId, Set<ProductionUnitId>> controlledActivities =
        new LinkedHashMap<>();
    private final Map<HouseholdId, Long> laborSold = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> laborHired = new LinkedHashMap<>();
    private final Map<HouseholdId, Map<LaborSource, Long>> laborBySource = new LinkedHashMap<>();
    private final Map<HouseholdId, Boolean> tenantStake = new LinkedHashMap<>();
    // ── 原因字符串的证据（保序；只读口/审计使用，不参与分类算术）──────────────────────────
    private final Map<HouseholdId, Map<String, Long>> laborEvidence = new LinkedHashMap<>();
    private final Map<HouseholdId, Set<String>> roleEvidence = new LinkedHashMap<>();
    // ── 债务：本金合计（分类只用"本金 ÷ 资产数量"这一个比值）────────────────────────────
    private final Map<HouseholdId, Long> debtPrincipal = new LinkedHashMap<>();

    private Index(
        Map<AssetShareId, AssetShare> assetShares,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<ProductionUnitId, ProductionUnit> units,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionRelation> relations,
        Map<HouseholdId, ClassRow> classes,
        Map<DebtId, Debt> debts) {
      this.classes = new LinkedHashMap<>(classes);
      for (ProductionUnit unit : units.values()) {
        unitsByIndustryOperator
            .computeIfAbsent(
                industryOperatorKey(unit.industry(), unit.operator()), ignored -> new ArrayList<>())
            .add(unit.id());
      }
      for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
        Map<SocialClassId, Long> target =
            IndustryHexKeys.hexKeyOf(entry.getKey())
                .map(
                    hexKey ->
                        slotCapsByHex.computeIfAbsent(hexKey, ignored -> new LinkedHashMap<>()))
                .orElse(universalSlotCaps);
        for (ClassSlot slot : entry.getValue().slots()) {
          target.merge(slot.id(), (long) slot.laborParticipationPerMille(), Math::min);
        }
      }
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

      // ① 直接资产份额（R3B.1）：份额的 owner（所有权）与 operator（实际经营）任一能反解为本户，就计入本户。
      //    ★ 旧 holder 的读取按语义拆到这两栏：owner == operator（旧档一对一迁移/创世整额）时只计一次，不双计。
      for (AssetShare share : assetShares.values()) {
        HouseholdId owner = householdOf(share.owner(), classes, householdByActor);
        HouseholdId operator = householdOf(share.operator(), classes, householdByActor);
        if (owner != null) {
          mergeDirectShare(owner, share);
        }
        if (operator != null && !operator.equals(owner)) {
          mergeDirectShare(operator, share);
        }
      }

      // ② 生产关系：本户是 operator/inputSupplier/residualOwner 的产业（它的 AssetShare 可能在经营者 actor 名下），
      //    以及所有租规则受方。★ 这里**不**把森林式的"每个受方都是地主"折成所有权，只记事实。
      Map<ProductionUnitId, Set<HouseholdId>> rentRecipientsByActivity = new LinkedHashMap<>();
      for (ProductionRelation relation : relations.values()) {
        ProductionUnitId activity = relation.activity();
        HouseholdId operator = householdOf(relation.operator(), classes, householdByActor);
        if (operator != null) {
          operatedActivities(operator).add(activity);
          controlledActivities(operator).add(activity);
          roleEvidence(operator).add("operator@" + activity.value());
        }
        HouseholdId inputSupplier =
            recipientHousehold(
                relation.inputSupplier(), classes, householdByActor, householdByView);
        if (inputSupplier != null) {
          operatedActivities(inputSupplier).add(activity);
          controlledActivities(inputSupplier).add(activity);
          roleEvidence(inputSupplier).add("inputSupplier@" + activity.value());
        }
        HouseholdId residualOwner =
            householdOf(relation.residualOwner(), classes, householdByActor);
        if (residualOwner != null) {
          operatedActivities(residualOwner).add(activity);
          controlledActivities(residualOwner).add(activity);
          roleEvidence(residualOwner).add("residualOwner@" + activity.value());
        }
        for (CompensationRule rule : relation.rules()) {
          HouseholdId recipient =
              recipientHousehold(rule.recipient(), classes, householdByActor, householdByView);
          if (recipient == null) {
            continue;
          }
          if (isRentShaped(rule)) {
            rentEntitled.put(recipient, true);
            rentRulesByHousehold
                .computeIfAbsent(recipient, ignored -> new LinkedHashSet<>())
                .add(rule);
            rentRecipientsByActivity
                .computeIfAbsent(activity, ignored -> new LinkedHashSet<>())
                .add(recipient);
            roleEvidence(recipient).add("rentRecipient@" + activity.value());
            HouseholdId previous = rentRuleRecipient.putIfAbsent(rule, recipient);
            if (previous != null && !previous.equals(recipient)) {
              ambiguousRentRules.add(rule);
            }
          } else {
            // ★ TENANT 的"占有部分投入/产出"判据：本户是该关系里非租形状规则的受方（自留/分成/劳动报酬）。
            productionStakeActivities(recipient).add(activity);
            roleEvidence(recipient).add("compensated@" + activity.value());
          }
        }
      }

      // ②a 经营身份对应的技术产能：即使 AssetShare 的 owner/operator 是经营者
      // actor，operator/inputSupplier/residualOwner
      //    的 LAND / TOOL / WORKSHOP 也要算进该家户的分类资产（计划 §S3.4 "不得因权利不在本户名下直接判 landless"）。
      for (Map.Entry<HouseholdId, Set<ProductionUnitId>> entry : operatedActivities.entrySet()) {
        for (ProductionUnitId activity : entry.getValue()) {
          ProductionUnit unit = units.get(activity);
          if (unit == null) {
            continue;
          }
          // ★★ R3B.2：经营身份对应的产能从 **AssetShare 纯派生**（unit 的可用资产），不再读
          //   {@code Industry.capacity} —— 实物总账只有一个来源，分类与结算不可能漂开。
          for (Map.Entry<AssetKind, Long> usable :
              ProductionUnitBook.usableAssets(unit, assetShares).entrySet()) {
            if (usable.getValue() <= 0L) {
              continue;
            }
            if (usable.getKey() == AssetKind.LAND) {
              operatedLandMilliMu.merge(entry.getKey(), usable.getValue(), Math::addExact);
            } else if (usable.getKey() == AssetKind.TOOL || usable.getKey() == AssetKind.WORKSHOP) {
              operatedToolOrWorkshop.merge(entry.getKey(), usable.getValue(), Math::addExact);
            }
          }
        }
      }

      // ②b 租权推定控制：地租受方按"该产业有几个受方"均分该产业的 LAND，只用于把租权受方的雇入劳动算进净劳动
      //    （真档一格一个地主，故就是整块地）；不写进 ownLand，避免再用"租受方=土地所有者"的旧近似。
      for (Map.Entry<ProductionUnitId, Set<HouseholdId>> entry :
          rentRecipientsByActivity.entrySet()) {
        ProductionUnit unit = units.get(entry.getKey());
        if (unit == null || entry.getValue().isEmpty()) {
          continue;
        }
        long land =
            ProductionUnitBook.usableAssets(unit, assetShares).getOrDefault(AssetKind.LAND, 0L);
        if (land <= 0L) {
          continue;
        }
        long share = land / entry.getValue().size();
        for (HouseholdId household : entry.getValue()) {
          rentInferredLandMilliMu.merge(household, share, Math::addExact);
          controlledActivities(household).add(entry.getKey());
        }
      }

      // ③ 劳动：本户卖出的劳动 + 本户控制/经营产业里雇入的劳动（含直接以本户为雇主的配额）。
      Map<ProductionUnitId, Long> activityLaborTotal = new LinkedHashMap<>();
      Map<ProductionUnitId, Map<HouseholdId, Long>> activityLaborByHousehold =
          new LinkedHashMap<>();
      Map<HouseholdId, Map<ProductionUnitId, Long>> directHiredByActivity = new LinkedHashMap<>();
      for (LaborAllocation allocation : allocations.values()) {
        HouseholdId household = allocation.household();
        ProductionUnitId activity = new ProductionUnitId(allocation.activity());
        if (!units.containsKey(activity)) {
          // ★ activity 不是现存 unit（自由家户劳动/旧档未接线档）：只进 laborSold（守恒/读口），不进按 unit 的派生表。
          laborSold.merge(household, allocation.laborMilli(), Math::addExact);
          continue;
        }
        long laborMilli = allocation.laborMilli();
        laborSold.merge(household, laborMilli, Math::addExact);
        activityLaborTotal.merge(activity, laborMilli, Math::addExact);
        activityLaborByHousehold
            .computeIfAbsent(activity, ignored -> new LinkedHashMap<>())
            .merge(household, laborMilli, Math::addExact);
        HouseholdId employer = householdOf(allocation.actor(), classes, householdByActor);
        if (employer != null && !employer.equals(household)) {
          directHiredByActivity
              .computeIfAbsent(employer, ignored -> new LinkedHashMap<>())
              .merge(activity, laborMilli, Math::addExact);
        }
        ProductionRelation relation = relations.get(activity);
        if (relation == null) {
          continue;
        }
        LaborSource source = relation.laborSource();
        laborBySource
            .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
            .merge(source, laborMilli, Math::addExact);
        laborEvidence
            .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
            .merge(source.name() + "@" + activity.value(), laborMilli, Math::addExact);
        if (source == LaborSource.TENANT
            && (productionStakeActivitiesOrEmpty(household).contains(activity)
                || directActivitiesOrEmpty(household).contains(activity)
                || operatedActivitiesOrEmpty(household).contains(activity))) {
          tenantStake.put(household, true);
        }
      }
      for (HouseholdId household : classes.keySet()) {
        long hired = 0L;
        Set<ProductionUnitId> controlled = controlledActivitiesOrEmpty(household);
        for (ProductionUnitId activity : controlled) {
          long total = activityLaborTotal.getOrDefault(activity, 0L);
          long self =
              activityLaborByHousehold.getOrDefault(activity, Map.of()).getOrDefault(household, 0L);
          long other = total - self;
          if (other > 0L) {
            hired += other;
          }
        }
        Map<ProductionUnitId, Long> direct =
            directHiredByActivity.getOrDefault(household, Map.of());
        for (Map.Entry<ProductionUnitId, Long> entry : direct.entrySet()) {
          if (!controlled.contains(entry.getKey())) {
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
          data.assetShares(),
          data.allocations(),
          data.units(),
          data.industries(),
          data.relations(),
          data.classes(),
          data.debts());
    }

    public static Index of(
        Map<AssetShareId, AssetShare> assetShares,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<ProductionUnitId, ProductionUnit> units,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionRelation> relations,
        Map<HouseholdId, ClassRow> classes,
        Map<DebtId, Debt> debts) {
      Objects.requireNonNull(assetShares, "assetShares");
      Objects.requireNonNull(allocations, "allocations");
      Objects.requireNonNull(units, "units");
      Objects.requireNonNull(industries, "industries");
      Objects.requireNonNull(relations, "relations");
      Objects.requireNonNull(classes, "classes");
      Objects.requireNonNull(debts, "debts");
      return new Index(assetShares, allocations, units, industries, relations, classes, debts);
    }

    /** 按上面的可观察量给一家户分档；{@code ledger} 只用于租金实付读数（读不到 ⇒ empty，不填 0）。 */
    public Classification classify(HouseholdId household, Optional<ProductionLedger> ledger) {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(ledger, "ledger");
      ClassRow row = classes.get(household);
      if (row == null) {
        throw new IllegalArgumentException("分类的家户没有 ClassRow 行: " + household);
      }
      long directLand = directLandMilliMu.getOrDefault(household, 0L);
      long operatedLand = operatedLandMilliMu.getOrDefault(household, 0L);
      long ownLand = directLand + operatedLand;
      long directToolOrWorkshopQuantity = directToolOrWorkshop.getOrDefault(household, 0L);
      long operatedToolOrWorkshopQuantity = operatedToolOrWorkshop.getOrDefault(household, 0L);
      long toolOrWorkshop = directToolOrWorkshopQuantity + operatedToolOrWorkshopQuantity;
      long directRights = directRightQuantity.getOrDefault(household, 0L);
      long inferredLand = rentInferredLandMilliMu.getOrDefault(household, 0L);
      long assetBase = directRights + operatedLand + operatedToolOrWorkshopQuantity + inferredLand;
      long sold = laborSold.getOrDefault(household, 0L);
      long hired = laborHired.getOrDefault(household, 0L);
      long netLaborSold = sold - hired;
      boolean rent = rentEntitled.getOrDefault(household, false);
      long debt = debtPrincipal.getOrDefault(household, 0L);
      long debtStress = debtStressPerMille(debt, assetBase);
      boolean debtStressed = debtStress >= DEBT_STRESS_THRESHOLD_PER_MILLE;
      Map<LaborSource, Long> sources = laborBySource.getOrDefault(household, Map.of());
      boolean communal = communalRight.getOrDefault(household, false);
      boolean tenantStakeOfHousehold = tenantStake.getOrDefault(household, false);

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

      SocialClassId stratum;
      String reason;
      String evidence = compactEvidence(household);
      if (rent && netLaborSold <= 0L) {
        stratum = SocialClassId.LANDLORD;
        reason =
            "landlord:rentEntitled;netLaborSoldMilli="
                + netLaborSold
                + ";ownLandMilliMu="
                + ownLand
                + ";inferredRentLandMilliMu="
                + inferredLand
                + ";debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      } else if (ownLand > 0L) {
        // 有 LAND：净雇工 ⇒ 富农；自耕平衡 ⇒ 中农/贫农（看债务）；净卖劳动 ⇒ 地少或欠债才贫农。
        if (netLaborSold < 0L) {
          stratum = SocialClassId.RICH_PEASANT;
          reason =
              "rich_peasant:ownLandMilliMu="
                  + ownLand
                  + ";directLandMilliMu="
                  + directLand
                  + ";operatedLandMilliMu="
                  + operatedLand
                  + ";netLaborSoldMilli="
                  + netLaborSold
                  + ";debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else if (netLaborSold == 0L) {
          if (debtStressed) {
            stratum = SocialClassId.POOR_PEASANT;
            reason =
                "poor_peasant:ownLandMilliMu="
                    + ownLand
                    + ";selfCultivatingButDebtStressed;debtStressPerMille="
                    + debtStress
                    + ";"
                    + evidence;
          } else {
            stratum = SocialClassId.MIDDLE_PEASANT;
            reason =
                "middle_peasant:ownLandMilliMu="
                    + ownLand
                    + ";selfCultivating;debtStressPerMille="
                    + debtStress
                    + ";"
                    + evidence;
          }
        } else if (debtStressed || ownLand <= SELF_CULTIVATION_THRESHOLD_MILLI_MU) {
          stratum = SocialClassId.POOR_PEASANT;
          reason =
              "poor_peasant:ownLandMilliMu="
                  + ownLand
                  + ";netLaborSoldMilli="
                  + netLaborSold
                  + ";smallholdingOrDebtStressed;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else {
          stratum = SocialClassId.MIDDLE_PEASANT;
          reason =
              "middle_peasant:ownLandMilliMu="
                  + ownLand
                  + ";netLaborSoldMilli="
                  + netLaborSold
                  + ";sellsSomeLaborButLanded;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        }
      } else if (toolOrWorkshop > 0L) {
        stratum = SocialClassId.ARTISAN;
        reason =
            "artisan:directToolOrWorkshop="
                + directToolOrWorkshopQuantity
                + ";operatedToolOrWorkshop="
                + operatedToolOrWorkshopQuantity
                + ";netLaborSoldMilli="
                + netLaborSold
                + ";debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      } else if (communal) {
        stratum = SocialClassId.OFFICIAL;
        reason = "official:communalRight;netLaborSoldMilli=" + netLaborSold + ";" + evidence;
      } else if (directRights > 0L || !operatedActivitiesOrEmpty(household).isEmpty()) {
        // 其它尚未启用的资产（CATTLE/MACHINE/SHIP）或没有可计产能的经营身份：有资产事实，但无法套 LAND/TOOL 档。
        if (debtStressed || netLaborSold > 0L) {
          stratum = SocialClassId.POOR_PEASANT;
          reason =
              "poor_peasant:assetHolder;directRightQuantity="
                  + directRights
                  + ";netLaborSoldMilli="
                  + netLaborSold
                  + ";debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else {
          stratum = SocialClassId.MIDDLE_PEASANT;
          reason =
              "middle_peasant:assetHolder;directRightQuantity="
                  + directRights
                  + ";netLaborSoldMilli="
                  + netLaborSold
                  + ";"
                  + evidence;
        }
      } else if (!sources.isEmpty()) {
        if (sources.containsKey(LaborSource.TENANT)) {
          if (debtStressed || !tenantStakeOfHousehold) {
            stratum = SocialClassId.POOR_PEASANT;
            reason =
                "poor_peasant:tenantNoStakeOrDebtStressed;tenantStake="
                    + tenantStakeOfHousehold
                    + ";debtStressPerMille="
                    + debtStress
                    + ";"
                    + evidence;
          } else {
            stratum = SocialClassId.MIDDLE_PEASANT;
            reason =
                "middle_peasant:tenantWithInputOutputStake;debtStressPerMille="
                    + debtStress
                    + ";"
                    + evidence;
          }
        } else if (sources.containsKey(LaborSource.FAMILY)
            || sources.containsKey(LaborSource.SELF)) {
          // ★ 混合身份（真档：SERF 庄园劳动 + FAMILY 家庭纺织）：自营是"本户自己的生产"，不因同时给庄园出劳
          //   就消失；债务压力高时才退回贫农。依附身份（SERF）仍是它的兜底，reason 里两条关系都写出来。
          if (debtStressed) {
            stratum = SocialClassId.POOR_PEASANT;
            reason =
                "poor_peasant:selfEmploymentButDebtStressed;debtStressPerMille="
                    + debtStress
                    + ";"
                    + evidence;
          } else {
            stratum = SocialClassId.MIDDLE_PEASANT;
            reason =
                "middle_peasant:familySelfEmployment;debtStressPerMille="
                    + debtStress
                    + ";"
                    + evidence;
          }
        } else if (sources.containsKey(LaborSource.SERF)) {
          stratum = SocialClassId.POOR_PEASANT;
          reason = "poor_peasant:serfDependent;debtStressPerMille=" + debtStress + ";" + evidence;
        } else if (sources.containsKey(LaborSource.WAGE)) {
          stratum = SocialClassId.LANDLESS_LABORER;
          reason =
              "landless_laborer:wageLabor;netLaborSoldMilli="
                  + netLaborSold
                  + ";debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else {
          stratum = row.view().stratum();
          reason =
              "retainedCurrentView:unmappedLaborSource(current="
                  + stratum.value()
                  + ");"
                  + evidence;
        }
      } else {
        // ★★ 没有任何可观察证据（无权利、无租、无劳动、无关系）⇒ 保留当前视图；不凭空把空行判成 landless。
        stratum = row.view().stratum();
        reason =
            "retainedCurrentView:noObservableEvidence(current="
                + stratum.value()
                + ");ownLandMilliMu="
                + ownLand
                + ";rightQuantity="
                + directRights
                + ";laborSoldMilli="
                + sold
                + ";rentEntitled="
                + rent;
      }
      SocialClassId feasible = feasibleStratum(row, stratum);
      if (!feasible.equals(stratum)) {
        reason =
            reason
                + ";slotCapFallback(derived="
                + stratum.value()
                + ",slotCap="
                + slotCapText(row, stratum)
                + ",participationPerMille="
                + row.participationPerMille()
                + "->"
                + feasible.value()
                + ")";
        stratum = feasible;
      }
      return new Classification(
          stratum,
          reason,
          ownLand,
          directRights,
          sold,
          hired,
          netLaborSold,
          rent,
          rentPaid,
          rentPaidComplete,
          debt,
          debtStress);
    }

    /**
     * ★ 分类结果必须是**该行参与率在新档位槽位上限内**的旧四档，或不受上限约束的 S3 派生阶层。
     *
     * <p>★ 真档实测（本类头部注释的场景）：poor_peasant 行 950‰ 被本规则派生成 middle_peasant，而 middle 槽位上限 900‰ ⇒ {@code
     * EconomyData} 构造期守卫拒收整批 advance。修复不是放宽守卫，也不是改参与率（写回只改 view），而是
     * **在分类侧选一个参与率可行的档位**：能保持派生档就保持，否则按富→中→贫的顺序退化，退化理由写进 reason。
     */
    private SocialClassId feasibleStratum(ClassRow row, SocialClassId derived) {
      if (!LEGACY_TIER_STRATA.contains(derived)) {
        return derived; // S3 派生阶层没有创世槽位上限（EconomyData 的两分法）
      }
      OptionalLong cap = slotCapOf(row, derived);
      if (cap.isPresent() && row.participationPerMille() <= cap.getAsLong()) {
        return derived;
      }
      for (SocialClassId candidate : fallbackOrder(derived)) {
        OptionalLong candidateCap = slotCapOf(row, candidate);
        if (candidateCap.isPresent() && row.participationPerMille() <= candidateCap.getAsLong()) {
          return candidate;
        }
      }
      // 连一个可行的旧档都没有（只应出现在坏数据/手工状态）：保留当前视图 —— 它在构造期已经过同一守卫。
      return row.view().stratum();
    }

    /** 派生档位不可行时的退化顺序：富→中→贫；地主→中→贫（地租受方若参与率过高，至少可落到中/贫）。 */
    private static List<SocialClassId> fallbackOrder(SocialClassId derived) {
      if (derived.equals(SocialClassId.RICH_PEASANT) || derived.equals(SocialClassId.LANDLORD)) {
        return List.of(SocialClassId.MIDDLE_PEASANT, SocialClassId.POOR_PEASANT);
      }
      if (derived.equals(SocialClassId.MIDDLE_PEASANT)) {
        return List.of(SocialClassId.POOR_PEASANT);
      }
      return List.of();
    }

    /** 该行所在格（含无格键产业）对某档的最紧参与率上限；没有该档槽位 ⇒ empty（S3 派生阶层/坏数据）。 */
    private OptionalLong slotCapOf(ClassRow row, SocialClassId stratum) {
      String hexKey = IndustryHexKeys.hexKey(row.view().hex().q(), row.view().hex().r());
      Long cap = null;
      Map<SocialClassId, Long> local = slotCapsByHex.get(hexKey);
      if (local != null) {
        cap = local.get(stratum);
      }
      Long universal = universalSlotCaps.get(stratum);
      if (universal != null) {
        cap = cap == null ? universal : Math.min(cap, universal);
      }
      return cap == null ? OptionalLong.empty() : OptionalLong.of(cap);
    }

    private String slotCapText(ClassRow row, SocialClassId stratum) {
      OptionalLong cap = slotCapOf(row, stratum);
      return cap.isPresent() ? Long.toString(cap.getAsLong()) : "none";
    }

    /** 保序取一条家户的证据串（原因字符串用；不参与算术）。 */
    private String compactEvidence(HouseholdId household) {
      List<String> parts = new ArrayList<>();
      Map<String, Long> labor = laborEvidence.getOrDefault(household, Map.of());
      for (Map.Entry<String, Long> entry : labor.entrySet()) {
        if (parts.size() >= 4) {
          parts.add("…+" + (labor.size() - 4));
          break;
        }
        parts.add(entry.getKey() + "=" + entry.getValue());
      }
      Set<String> roles = roleEvidence.getOrDefault(household, Set.of());
      parts.addAll(roles);
      return parts.isEmpty() ? "noRelationEvidence" : "evidence[" + String.join(",", parts) + "]";
    }

    /** (industry, operator) 的字符串键（只作本类内部索引，不持久化、不参与排序语义）。 */
    private static String industryOperatorKey(IndustryId industry, ActorRef operator) {
      return industry.value() + "|" + operator.kind() + "|" + operator.id();
    }

    private Set<ProductionUnitId> directActivities(HouseholdId household) {
      return directActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
    }

    /**
     * 把一条 AssetShare 的资产事实记到某个家户名下（owner 与 operator 都调用它；同一家户同一份只调一次）。
     *
     * <p>★ 判据与旧实现逐字对应，只把 {@code holder/activity} 换成 {@code owner|operator/industry}：
     * 数量进资产基数、产业进直接/控制活动、LAND/TOOL/WORKSHOP 分类累计、COMMUNAL 记名。
     */
    private void mergeDirectShare(HouseholdId household, AssetShare share) {
      directRightQuantity.merge(household, share.quantity(), Math::addExact);
      // ★★ R3B.2：份额只带 (industry, operator)；折算到 unit = 该 (industry, operator) 的所有 unit
      //   （operator 一侧命中本户 actor 时才有；owner 一侧通常没有 unit，份额事实仍进 directRightQuantity）。
      List<ProductionUnitId> matching =
          unitsByIndustryOperator.getOrDefault(
              industryOperatorKey(share.industry(), share.operator()), List.of());
      directActivities(household).addAll(matching);
      controlledActivities(household).addAll(matching);
      if (share.asset() == AssetKind.LAND) {
        directLandMilliMu.merge(household, share.quantity(), Math::addExact);
      } else if (share.asset() == AssetKind.TOOL || share.asset() == AssetKind.WORKSHOP) {
        directToolOrWorkshop.merge(household, share.quantity(), Math::addExact);
      }
      if (share.kind() == AssetShare.RightKind.COMMUNAL && share.quantity() > 0L) {
        communalRight.put(household, true);
      }
    }

    private Set<ProductionUnitId> directActivitiesOrEmpty(HouseholdId household) {
      return directActivities.getOrDefault(household, Set.of());
    }

    private Set<ProductionUnitId> operatedActivities(HouseholdId household) {
      return operatedActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
    }

    private Set<ProductionUnitId> operatedActivitiesOrEmpty(HouseholdId household) {
      return operatedActivities.getOrDefault(household, Set.of());
    }

    private Set<ProductionUnitId> controlledActivities(HouseholdId household) {
      return controlledActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
    }

    private Set<ProductionUnitId> controlledActivitiesOrEmpty(HouseholdId household) {
      return controlledActivities.getOrDefault(household, Set.of());
    }

    private Set<ProductionUnitId> productionStakeActivities(HouseholdId household) {
      return productionStakeActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
    }

    private Set<ProductionUnitId> productionStakeActivitiesOrEmpty(HouseholdId household) {
      return productionStakeActivities.getOrDefault(household, Set.of());
    }

    private Set<String> roleEvidence(HouseholdId household) {
      return roleEvidence.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
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
