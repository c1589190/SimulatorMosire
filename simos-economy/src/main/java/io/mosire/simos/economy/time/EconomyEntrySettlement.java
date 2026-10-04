package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * ★★ <b>R4-E2b：候选预设 → 合条件主体的实际采用（运行期进入/试产）</b>—— 本片只做"GM 登记了预设 + 本格有有效需求"时， 让可行家户形成一条 {@code
 * OperatorCondition.IndustryStatus#TRIALING} 的新 {@code ProductionUnit}；<b>不做</b>自动发明、全局 ROI 切换、E3
 * 经验、E4 梯度消费。
 *
 * <p>★★ <b>两段式（纯意向 + 执行）</b>，理由与计划一致：
 *
 * <ol>
 *   <li>{@link #planEntries} <b>只读</b>地按"家户升序 × 候选(id,version)升序"评估每一对，产出 {@link EntryIntent} 与具名拒绝
 *       {@link EntryOutcome}；评估所需的派生量（unit↔operator、配额和、份额归属）在入口建一次，<b>不</b>做 O(unit×allocation)
 *       全表扫描；
 *   <li>{@link #execute} 在日结算的"现扣投入/劳动再分配"之前对每条意向<b>重新评估</b>（计划到执行之间可能已有别的意向改了资产/劳动），
 *       通过后才写工作副本：产业模板 → unit → relation → condition → 份额拆分 → 劳动配额。任一步失败 ⇒ 当条拒绝，不半建。
 * </ol>
 *
 * <p>★★ <b>可行性口径（逐条）</b>：
 *
 * <ul>
 *   <li><b>需求</b>：本户所在格有 {@link DemandEntry#effectiveOn(long)} 的有效需求命中候选主产出，且剩余窗口 ≥ {@code
 *       buildDays + cycleDays}（{@code expiresDay < 0} = 永久）；
 *   <li><b>幂等</b>：本户不得已有同 {@code modeKey} 或同主产出的 ACTIVE/TRIALING unit；同一 {@code
 *       (household,candidate)} 因此只建一次；
 *   <li><b>资产</b>：{@code requiredAssets} 为空或全 0 ⇒ 不可行（本片没有"无资产也能产"的路）；自有可用份额只认 <b>已登记在候选产业 id
 *       下</b>、权利性质 ∈ {@code acceptedRightKinds} 的份额；不足部分从 {@code assetSource} 名下<b>空闲</b>（{@code
 *       operator==owner}）、同格的份额拆分：原份额只减数量（减到 0 则删行），新份额 owner 不变、operator = 家户、kind 按 {@code
 *       acceptedRightKinds} 选，并登记在候选产业 id 下（否则 {@code ProductionUnitBook} 的 {@code
 *       (industry,operator)} 作用域取不到它）。<b>不新增总量、不删 owner</b>；
 *   <li><b>劳动</b>：家户折算后可用劳动（{@code participationAdjustedLaborMilli} − 已分配）与所选人口批次余量（{@code
 *       availableLabor} − 已分配）都 ≥ {@code laborPerUnit × trialScale}；批次按成员份额选（没有成员份额的旧夹具退回该户既有配额
 *       的批次），并列取余量大者、再按批次 id 升序；
 *   <li><b>投入/生计</b>：本户库存要覆盖 {@code inputPerUnit × trialScale × (buildDays+cycleDays)}，并额外留出同窗口 + 1
 *       天的 基本口粮（按 {@link EconomyVocabulary#cumulativeRationMilli} 的区间差，不另写"每人每天"）；
 *   <li><b>收益/成本只用可观察价</b>：主产出、任一正投入、以及给养要用的粮，<b>缺价一律具名拒绝</b>（{@code PRICE_MISSING:...}）—— "缺价按 0
 *       计"会把无价成本当成免费，不是保守口径；自留口粮成本按 {@link RegimeRelations#subsistenceMilliPerLabor()}（与 {@code
 *       ProducerCostBook} 同源）；score &lt; 0 ⇒ 不可行。
 * </ul>
 *
 * <p>★★ <b>规模</b>：{@code trialScale = min(配置上限, 资产/requiredAssets, 劳动/laborPerUnit,
 * 投入horizon可用量)}，&lt;1 ⇒ 不可行。配置上限 = {@link #MAX_TRIAL_SCALE_PER_ENTRY}（V 阶段接 GM 参数目录后迁出）。
 *
 * <p>★★ <b>投入表怎么表达进 Industry</b>：候选的 {@code inputPerUnit} 必须进 {@code Industry.cycleInputPerUnit}
 * （否则现扣/收获的 {@code inputPerUnit()} 读不到）。本片把它整体挂在候选 {@code requiredAssets} 第一个正需求 key 下 （唯一一处、见
 * {@link #templateFor}），前提已用代码确认：现扣与收获都读 {@code Industry.inputPerUnit()}（= 各内层表之和）/ {@code
 * recipe()}，没有任何路径按某个 asset 分支直接读 {@code cycleInputPerUnit}（全仓 {@code main} 只有 {@code Industry} 自身与
 * {@code EconomyData} 的归一化读它，且归一化只是原样带过）。
 *
 * <p>★ <b>进入触发日</b>：每个日结算日都评估（只要 demands/candidates 非空）；<b>已能维生的旧 unit 一律不动</b>，没有全局 ROI 切换。 {@code
 * buildDays} 本片只进"需求剩余窗口"与"投入/口粮覆盖窗口"两个门槛，<b>不推迟结算进度</b>（首个关账日 = 进入日 + {@code cycleDays} − 1）；这一差异在
 * E2b 报告里如实记。
 */
final class EconomyEntrySettlement {

  /**
   * ★ <b>单次进入的试产规模配置上限（单位规模）</b>：取小后还要过资产/劳动/投入三道，&lt;1 ⇒ 不可行。
   *
   * <p>★ 它是本片新增的唯一"配置值"；V 阶段接 GM 参数目录后从这里迁出，评估逻辑不改。取 10 = 现有主 unit 规模（数百～数千）的一个小比例。
   */
  static final long MAX_TRIAL_SCALE_PER_ENTRY = 10L;

  private EconomyEntrySettlement() {}

  /** 一条资产拆分腿：从 {@code sourceShare} 拆出 {@code quantity} 给新 unit（新份额 owner 不变、operator=家户）。 */
  record AssetGrant(
      AssetShareId sourceShare, AssetKind asset, long quantity, AssetShare.RightKind kind) {

    AssetGrant {
      Objects.requireNonNull(sourceShare, "AssetGrant.sourceShare 不得为 null");
      Objects.requireNonNull(asset, "AssetGrant.asset 不得为 null");
      Objects.requireNonNull(kind, "AssetGrant.kind 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException("AssetGrant.quantity 必须 > 0: " + quantity);
      }
    }
  }

  /**
   * 一条"可以采用"的意向（plan 产出；execute 重新评估后使用）。字段口径见类注；{@code grants} 只含<b>需要从 assetSource 拆</b>
   * 的腿（自有份额已够的部分不在其中），{@code inputPlan} = 一周期投入（已乘 trialScale），{@code horizonNeed} = 覆盖窗口投入。
   */
  record EntryIntent(
      HouseholdId household,
      ActorRef householdActor,
      ProductionCandidate candidate,
      IndustryId industryId,
      long trialScale,
      long expectedDay,
      List<AssetGrant> grants,
      Map<CommodityId, Long> inputPlan,
      Map<CommodityId, Long> horizonNeed,
      Optional<PeopleLotId> laborLot,
      long laborMilli,
      long laborPeriod) {

    EntryIntent {
      Objects.requireNonNull(household, "EntryIntent.household 不得为 null");
      Objects.requireNonNull(householdActor, "EntryIntent.householdActor 不得为 null");
      Objects.requireNonNull(candidate, "EntryIntent.candidate 不得为 null");
      Objects.requireNonNull(industryId, "EntryIntent.industryId 不得为 null");
      Objects.requireNonNull(grants, "EntryIntent.grants 不得为 null");
      Objects.requireNonNull(inputPlan, "EntryIntent.inputPlan 不得为 null");
      Objects.requireNonNull(horizonNeed, "EntryIntent.horizonNeed 不得为 null");
      Objects.requireNonNull(laborLot, "EntryIntent.laborLot 不得为 null（没有请用 Optional.empty()）");
      if (trialScale < 1L) {
        throw new IllegalArgumentException("EntryIntent.trialScale 必须 ≥ 1: " + trialScale);
      }
      if (expectedDay < 1L) {
        throw new IllegalArgumentException("EntryIntent.expectedDay 必须 ≥ 1: " + expectedDay);
      }
      if (laborMilli < 0L || laborPeriod < 0L) {
        throw new IllegalArgumentException(
            "EntryIntent 的 laborMilli/laborPeriod 不得为负: " + laborMilli + "/" + laborPeriod);
      }
      grants = List.copyOf(grants);
      inputPlan = Collections.unmodifiableMap(new LinkedHashMap<>(inputPlan));
      horizonNeed = Collections.unmodifiableMap(new LinkedHashMap<>(horizonNeed));
    }
  }

  /** plan 的产物：可行的意向 + 计划期即被拒的具名结果（execute 的结果另出）。 */
  record Plan(List<EntryIntent> intents, List<EntryOutcome> outcomes) {

    Plan {
      Objects.requireNonNull(intents, "Plan.intents 不得为 null");
      Objects.requireNonNull(outcomes, "Plan.outcomes 不得为 null");
      intents = List.copyOf(intents);
      outcomes = List.copyOf(outcomes);
    }
  }

  /** execute 的产物：是否真的改变了状态、新 unit id（供再分配例外集）、逐条最终结果。 */
  record Execution(
      boolean changed, Set<ProductionUnitId> enteredUnitIds, List<EntryOutcome> outcomes) {

    Execution {
      Objects.requireNonNull(enteredUnitIds, "Execution.enteredUnitIds 不得为 null");
      Objects.requireNonNull(outcomes, "Execution.outcomes 不得为 null");
      enteredUnitIds = Collections.unmodifiableSet(new LinkedHashSet<>(enteredUnitIds));
      outcomes = List.copyOf(outcomes);
    }
  }

  /**
   * ★ <b>纯意向段</b>：只读传入的状态/索引，按 (家户升序, 候选 id/version 升序) 评估；可行 ⇒ 意向（并在**副本表**上模拟登记，
   * 使同批次后续评估看见它占用的资产/劳动），不可行 ⇒ 具名拒绝。真实状态一字不动。
   */
  static Plan planEntries(
      Map<DemandId, DemandEntry> demands,
      Map<CandidateId, ProductionCandidate> candidates,
      Map<IndustryId, Industry> industries,
      SettlementIndex index,
      Map<HexCoord, Market> markets,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<MembershipId, Membership> memberships,
      Map<PeopleLotId, LaborSupply> laborSupply,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<AssetShareId, AssetShare> assetShares,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRelation> relations,
      long day) {
    Objects.requireNonNull(demands, "demands");
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(index, "index（planEntries 目前只用它做只读一致性锚点，见方法注释）");
    if (demands.isEmpty() || candidates.isEmpty()) {
      return new Plan(List.of(), List.of());
    }
    // ★ 副本表：意向段需要"看见本批已占用的资产/劳动"，但真实状态一字不动。
    Tables shadow =
        new Tables(
            new LinkedHashMap<>(industries),
            new LinkedHashMap<>(units),
            new LinkedHashMap<>(assetShares),
            new LinkedHashMap<>(allocations),
            new LinkedHashMap<>(operatorConditions),
            new LinkedHashMap<>(relations));
    EntryContext context =
        new EntryContext(
            shadow,
            demands,
            rows,
            householdGoods,
            householdMoney,
            memberships,
            laborSupply,
            markets,
            day);
    List<HouseholdId> orderedHouseholds = new ArrayList<>(rows.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    List<ProductionCandidate> orderedCandidates = new ArrayList<>(candidates.values());
    orderedCandidates.sort(
        Comparator.comparing((ProductionCandidate candidate) -> candidate.id().value())
            .thenComparingInt(ProductionCandidate::version));
    List<EntryIntent> intents = new ArrayList<>();
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (HouseholdId household : orderedHouseholds) {
      ClassRow row = rows.get(household);
      if (row == null || row.population() <= 0L) {
        continue;
      }
      for (ProductionCandidate candidate : orderedCandidates) {
        Attempt attempt = assess(household, candidate, context);
        if (attempt.intent() == null) {
          if (attempt.rejection() != null) {
            outcomes.add(attempt.rejection());
          }
          continue;
        }
        Optional<String> failure = applyIntent(attempt.intent(), context);
        if (failure.isPresent()) {
          outcomes.add(reject(context, household, candidate, failure.get()));
        } else {
          intents.add(attempt.intent());
        }
      }
    }
    return new Plan(intents, outcomes);
  }

  /**
   * ★ <b>执行段</b>：在日结算的"现扣投入/劳动再分配"之前调用；对每条意向以**当前工作副本**重新评估，通过后写： 产业模板（若该 candidate/hex 还没有）→ unit
   * → relation → condition(TRIALING) → 份额拆分 → 劳动配额。
   *
   * <p>★ 传入的 map 都是工作副本（由调用方持有并最终交出）；本方法**就地更新**，不另建第二份状态。
   */
  static Execution execute(
      List<EntryIntent> intents,
      Map<DemandId, DemandEntry> demands,
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<AssetShareId, AssetShare> assetShares,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<MembershipId, Membership> memberships,
      Map<PeopleLotId, LaborSupply> laborSupply,
      Map<HexCoord, Market> markets,
      long day) {
    Objects.requireNonNull(intents, "intents");
    if (intents.isEmpty()) {
      return new Execution(false, Set.of(), List.of());
    }
    Tables tables =
        new Tables(industries, units, assetShares, allocations, operatorConditions, relations);
    EntryContext context =
        new EntryContext(
            tables,
            demands,
            rows,
            householdGoods,
            householdMoney,
            memberships,
            laborSupply,
            markets,
            day);
    boolean changed = false;
    Set<ProductionUnitId> entered = new LinkedHashSet<>();
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (EntryIntent planned : intents) {
      Attempt attempt = assess(planned.household(), planned.candidate(), context);
      if (attempt.intent() == null) {
        outcomes.add(
            attempt.rejection() != null
                ? attempt.rejection()
                : reject(
                    context, planned.household(), planned.candidate(), "REVALIDATION_NO_SIGNAL"));
        continue;
      }
      EntryIntent fresh = attempt.intent();
      Optional<String> failure = applyIntent(fresh, context);
      if (failure.isPresent()) {
        outcomes.add(reject(context, fresh.household(), fresh.candidate(), failure.get()));
        continue;
      }
      changed = true;
      entered.add(ProductionUnitId.idOf(fresh.industryId(), fresh.householdActor()));
      outcomes.add(accepted(context, fresh));
    }
    return new Execution(changed, entered, outcomes);
  }

  // ── 评估 ─────────────────────────────────────────────────────────────────────────────

  /** 需求信号三档：没有命中（跳过该对）、命中但窗口太短（具名拒绝）、可用。 */
  private enum DemandSignal {
    NONE,
    TOO_SHORT,
    OK
  }

  /** 评估结果：恰有一个非空 —— {@code intent}（可行）或 {@code rejection}（具名拒绝）；两者都空 = 不评估。 */
  private record Attempt(EntryIntent intent, EntryOutcome rejection) {

    static Attempt skip() {
      return new Attempt(null, null);
    }

    static Attempt intent(EntryIntent intent) {
      return new Attempt(intent, null);
    }

    static Attempt rejection(EntryOutcome rejection) {
      return new Attempt(null, rejection);
    }
  }

  /**
   * 对一对 (家户, 候选) 做完整可行性评估（只读；不改任何表）。返回 {@link Attempt#skip()} = 该户没有命中候选主产出的有效需求 （不产出读口行， 避免
   * 6000×候选条无意义的 NO_DEMAND）。
   */
  private static Attempt assess(
      HouseholdId household, ProductionCandidate candidate, EntryContext context) {
    try {
      ClassRow row = context.rows.get(household);
      if (row == null || row.population() <= 0L) {
        return Attempt.skip();
      }
      HexCoord hex = row.view().hex();
      long horizon;
      try {
        horizon = Math.addExact(candidate.buildDays(), candidate.cycleDays());
      } catch (ArithmeticException overflow) {
        return Attempt.rejection(reject(context, household, candidate, "DEMAND_HORIZON_OVERFLOW"));
      }
      DemandSignal signal = demandSignal(household, candidate, hex, horizon, context);
      if (signal == DemandSignal.NONE) {
        return Attempt.skip();
      }
      if (signal == DemandSignal.TOO_SHORT) {
        return Attempt.rejection(reject(context, household, candidate, "DEMAND_TOO_SHORT"));
      }
      if (candidate.id().value().indexOf('.') >= 0) {
        // ★ unit id / AssetShare id 都不允许 '.'（地址解析约定）；登记期未拦，进入期 fail-closed，不半建。
        return Attempt.rejection(reject(context, household, candidate, "CANDIDATE_ID_INVALID:dot"));
      }
      IndustryId industryId = IndustryHexKeys.id(candidate.id().value(), hex.q(), hex.r());
      ActorRef actor = HouseholdActors.of(household);
      if (hasActiveDuplicate(actor, candidate, context)) {
        return Attempt.rejection(reject(context, household, candidate, "ALREADY_ENTERED"));
      }
      ProductionUnitId unitId = ProductionUnitId.idOf(industryId, actor);
      if (context.tables.units.containsKey(unitId)) {
        return Attempt.rejection(reject(context, household, candidate, "UNIT_ID_EXISTS"));
      }
      Map<AssetKind, Long> required = candidate.requiredAssets();
      boolean anyPositiveAsset = false;
      for (long quantity : required.values()) {
        if (quantity > 0L) {
          anyPositiveAsset = true;
          break;
        }
      }
      if (!anyPositiveAsset) {
        return Attempt.rejection(reject(context, household, candidate, "NO_ASSET_REQUIREMENT"));
      }
      if (candidate.acceptedRightKinds().isEmpty()) {
        return Attempt.rejection(reject(context, household, candidate, "NO_RIGHT_KIND"));
      }
      long cap = MAX_TRIAL_SCALE_PER_ENTRY;

      // ② 资产可行性：自有（候选产业 id 下、accepted kind、owner/operator=本户）+ assetSource 空闲同格份额。
      Map<AssetKind, Long> own =
          ownAssets(actor, industryId, candidate.acceptedRightKinds(), context);
      Optional<ActorRef> assetSource = candidate.assetSource();
      Map<AssetKind, Long> source =
          assetSource.isPresent()
              ? sourceAssets(assetSource.get(), hex, required.keySet(), context)
              : Map.of();
      long assetCap = Long.MAX_VALUE;
      for (Map.Entry<AssetKind, Long> entry : required.entrySet()) {
        if (entry.getValue() <= 0L) {
          continue;
        }
        long available =
            own.getOrDefault(entry.getKey(), 0L) + source.getOrDefault(entry.getKey(), 0L);
        assetCap = Math.min(assetCap, available / entry.getValue());
      }
      if (assetCap < 1L) {
        return Attempt.rejection(
            reject(
                context,
                household,
                candidate,
                "ASSET_SHORT:required=" + required + ",own=" + own + ",source=" + source));
      }
      cap = Math.min(cap, assetCap);

      // ③ 劳动可行性：本户折算后余量 + 所选批次余量（两者都不得超）。
      List<PeopleLotId> lots = List.of();
      long laborCap = Long.MAX_VALUE;
      long laborPeriod = 0L;
      if (candidate.laborPerUnit() > 0L) {
        long householdRoom =
            row.participationAdjustedLaborMilli()
                - context.allocationMilliByHousehold.getOrDefault(household, 0L);
        if (householdRoom < candidate.laborPerUnit()) {
          return Attempt.rejection(
              reject(
                  context,
                  household,
                  candidate,
                  "LABOR_SHORT:householdRoom="
                      + householdRoom
                      + ",need="
                      + candidate.laborPerUnit()));
        }
        List<PeopleLotId> lotChoices = context.lotsFor(household);
        PeopleLotId bestLot = null;
        long bestRoom = Long.MIN_VALUE;
        for (PeopleLotId lot : lotChoices) {
          LaborSupply supply = context.laborSupply.get(lot);
          if (supply == null) {
            continue;
          }
          long room =
              supply.availableLabor() - context.allocationMilliByGroup.getOrDefault(lot, 0L);
          if (room > bestRoom
              || (room == bestRoom
                  && (bestLot == null || lot.value().compareTo(bestLot.value()) < 0))) {
            bestLot = lot;
            bestRoom = room;
          }
        }
        if (bestLot == null) {
          return Attempt.rejection(reject(context, household, candidate, "LABOR_NO_SUPPLY"));
        }
        if (bestRoom < candidate.laborPerUnit()) {
          return Attempt.rejection(
              reject(
                  context,
                  household,
                  candidate,
                  "LABOR_SHORT:groupRoom=" + bestRoom + ",need=" + candidate.laborPerUnit()));
        }
        long groupCap = bestRoom / candidate.laborPerUnit();
        long householdCap = householdRoom / candidate.laborPerUnit();
        laborCap = Math.min(groupCap, householdCap);
        if (laborCap < 1L) {
          return Attempt.rejection(
              reject(
                  context,
                  household,
                  candidate,
                  "LABOR_SHORT:householdRoom=" + householdRoom + ",groupRoom=" + bestRoom));
        }
        lots = List.of(bestLot);
        LaborSupply supply = context.laborSupply.get(bestLot);
        laborPeriod = supply.period();
      }
      cap = Math.min(cap, laborCap);

      // ④ 投入/生计：库存覆盖 inputPerUnit × trialScale × horizon + (horizon+1) 天基本口粮。
      long grainStock = stockOf(context.householdGoods, household, EconomySettlement.GRAIN);
      long grainReserve =
          EconomyVocabulary.cumulativeRationMilli(row.population(), context.day + horizon)
              - EconomyVocabulary.cumulativeRationMilli(row.population(), context.day - 1L);
      if (grainStock < grainReserve) {
        return Attempt.rejection(
            reject(
                context,
                household,
                candidate,
                "SUBSISTENCE_SHORT:grainStock=" + grainStock + ",need=" + grainReserve));
      }
      long inputCap = Long.MAX_VALUE;
      for (Map.Entry<CommodityId, Long> entry : candidate.inputPerUnit().entrySet()) {
        long perScale = entry.getValue();
        if (perScale <= 0L) {
          continue;
        }
        long stock = stockOf(context.householdGoods, household, entry.getKey());
        long available =
            entry.getKey().equals(EconomySettlement.GRAIN) ? grainStock - grainReserve : stock;
        long perWindow = Math.multiplyExact(perScale, horizon);
        long commodityCap = available / perWindow;
        if (commodityCap < 1L) {
          return Attempt.rejection(
              reject(
                  context,
                  household,
                  candidate,
                  "INPUT_SHORT:commodity="
                      + entry.getKey().value()
                      + ",stock="
                      + stock
                      + ",needPerScalePerWindow="
                      + perWindow));
        }
        inputCap = Math.min(inputCap, commodityCap);
      }
      cap = Math.min(cap, inputCap);
      if (cap < 1L) {
        return Attempt.rejection(
            reject(context, household, candidate, "SCALE_CAP_BELOW_ONE:cap=" + cap));
      }
      long trialScale = cap;

      // ⑥ 可观察价 score（三项同一量纲：毫商品 × 毫银/商品；只用来判符号）：
      //   收益 = 主产出单位量 × 产出价；成本 = Σ 正投入单位量 × 各自价；自留口粮 = 劳动 × 给养 × 粮价。
      //   ★ 任务书口径"缺价 ⇒ 不可行"：主产出、任一正投入、给养要用的粮缺价，都具名拒绝 ——
      //   不按 0 假装便宜（那不是保守，是把无价成本当成免费）。
      Market market = context.markets.get(hex);
      if (market == null) {
        return Attempt.rejection(reject(context, household, candidate, "NO_MARKET"));
      }
      List<String> missingPrices = new ArrayList<>();
      long outputPrice = market.priceOf(candidate.output());
      if (outputPrice <= 0L) {
        missingPrices.add(candidate.output().value());
      }
      long inputCost = 0L;
      for (Map.Entry<CommodityId, Long> entry : candidate.inputPerUnit().entrySet()) {
        long perScale = entry.getValue();
        if (perScale <= 0L) {
          continue;
        }
        long price = market.priceOf(entry.getKey());
        if (price <= 0L) {
          missingPrices.add(entry.getKey().value());
          continue;
        }
        inputCost = Math.addExact(inputCost, Math.multiplyExact(perScale, price));
      }
      long subsistenceCost = 0L;
      if (candidate.laborPerUnit() > 0L) {
        long grainPrice = market.priceOf(EconomySettlement.GRAIN);
        if (grainPrice <= 0L) {
          missingPrices.add(EconomySettlement.GRAIN.value());
        } else {
          subsistenceCost =
              Math.multiplyExact(
                  Math.multiplyExact(
                      candidate.laborPerUnit(), RegimeRelations.subsistenceMilliPerLabor()),
                  grainPrice);
        }
      }
      if (!missingPrices.isEmpty()) {
        return Attempt.rejection(
            reject(context, household, candidate, "PRICE_MISSING:" + missingPrices));
      }
      long revenue =
          Math.multiplyExact(candidate.outputPerUnit().get(candidate.output()), outputPrice);
      long score = revenue - inputCost - subsistenceCost;
      if (score < 0L) {
        return Attempt.rejection(
            reject(
                context,
                household,
                candidate,
                "SCORE_NEGATIVE:revenue="
                    + revenue
                    + ",inputCost="
                    + inputCost
                    + ",subsistence="
                    + subsistenceCost));
      }

      // ⑤ 资产拆分的具体腿（自有份额已够的部分不动；只拆缺的部分）。
      AssetShare.RightKind grantKind = chooseGrantKind(candidate.acceptedRightKinds());
      List<AssetGrant> grants = new ArrayList<>();
      for (Map.Entry<AssetKind, Long> entry : required.entrySet()) {
        if (entry.getValue() <= 0L) {
          continue;
        }
        long need = Math.multiplyExact(entry.getValue(), trialScale);
        long ownQuantity = Math.min(need, own.getOrDefault(entry.getKey(), 0L));
        long remaining = need - ownQuantity;
        if (remaining <= 0L) {
          continue;
        }
        if (assetSource.isEmpty()) {
          return Attempt.rejection(
              reject(
                  context,
                  household,
                  candidate,
                  "ASSET_SHORT:asset=" + entry.getKey() + ",need=" + need + ",own=" + ownQuantity));
        }
        for (AssetShareId shareId :
            sourceShareIds(assetSource.get(), hex, entry.getKey(), context)) {
          AssetShare share = context.tables.assetShares.get(shareId);
          if (share == null || share.quantity() <= 0L) {
            continue;
          }
          long take = Math.min(remaining, share.quantity());
          if (take > 0L) {
            grants.add(new AssetGrant(shareId, entry.getKey(), take, grantKind));
            remaining -= take;
          }
          if (remaining <= 0L) {
            break;
          }
        }
        if (remaining > 0L) {
          return Attempt.rejection(
              reject(
                  context,
                  household,
                  candidate,
                  "ASSET_SHORT:asset="
                      + entry.getKey()
                      + ",need="
                      + need
                      + ",sourceShort="
                      + remaining));
        }
      }
      Map<CommodityId, Long> inputPlan = new LinkedHashMap<>();
      Map<CommodityId, Long> horizonNeed = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : candidate.inputPerUnit().entrySet()) {
        long perScale = entry.getValue();
        if (perScale <= 0L) {
          continue;
        }
        long perCycle = Math.multiplyExact(perScale, trialScale);
        inputPlan.put(entry.getKey(), perCycle);
        horizonNeed.put(entry.getKey(), Math.multiplyExact(perCycle, horizon));
      }
      long laborMilli = Math.multiplyExact(candidate.laborPerUnit(), trialScale);
      // ★ 进入当天 progress 记 1，第 cycleDays 天关账产出的那个日结算日 = 进入日 + cycleDays − 1（D=1/cycleDays=30 ⇒ 第 30
      // 天）。
      long expectedDay = Math.addExact(context.day, candidate.cycleDays() - 1L);
      EntryIntent intent =
          new EntryIntent(
              household,
              actor,
              candidate,
              industryId,
              trialScale,
              expectedDay,
              List.copyOf(grants),
              inputPlan,
              horizonNeed,
              lots.isEmpty() ? Optional.empty() : Optional.of(lots.get(0)),
              laborMilli,
              laborPeriod);
      return Attempt.intent(intent);
    } catch (ArithmeticException overflow) {
      return Attempt.rejection(
          reject(context, household, candidate, "ARITHMETIC_OVERFLOW:" + overflow.getMessage()));
    } catch (IllegalStateException | IllegalArgumentException invalid) {
      return Attempt.rejection(
          reject(context, household, candidate, "INVALID_STATE:" + invalid.getMessage()));
    }
  }

  /** 本户所在格的候选主产出需求信号（HEX 命中本格 / HOUSEHOLD 命中本户；窗口 ≥ horizon 或永久）。 */
  private static DemandSignal demandSignal(
      HouseholdId household,
      ProductionCandidate candidate,
      HexCoord hex,
      long horizon,
      EntryContext context) {
    boolean sawAny = false;
    for (DemandEntry demand : context.demands.values()) {
      if (!demand.commodity().equals(candidate.output()) || !demand.effectiveOn(context.day)) {
        continue;
      }
      if (demand.scope() == DemandEntry.DemandScope.HOUSEHOLD) {
        if (!demand.household().orElseThrow().equals(household)) {
          continue;
        }
      } else if (!demand.hex().orElseThrow().equals(hex)) {
        continue;
      }
      sawAny = true;
      if (demand.expiresDay() < 0L || demand.expiresDay() - context.day >= horizon) {
        return DemandSignal.OK;
      }
    }
    return sawAny ? DemandSignal.TOO_SHORT : DemandSignal.NONE;
  }

  /** 幂等：本户已有同 modeKey 或同主产出的 ACTIVE/TRIALING unit（condition 缺失按 ACTIVE 口径，见 StressPolicy）。 */
  private static boolean hasActiveDuplicate(
      ActorRef actor, ProductionCandidate candidate, EntryContext context) {
    for (ProductionUnitId unitId : context.unitIdsByOperator.getOrDefault(actor, List.of())) {
      ProductionUnit unit = context.tables.units.get(unitId);
      if (unit == null) {
        continue;
      }
      OperatorCondition condition = context.tables.operatorConditions.get(unitId);
      IndustryStatus status = condition == null ? IndustryStatus.ACTIVE : condition.status();
      if (status != IndustryStatus.ACTIVE && status != IndustryStatus.TRIALING) {
        continue;
      }
      if (unit.modeKey().equals(candidate.modeKey())) {
        return true;
      }
      Industry industry = context.tables.industries.get(unit.industry());
      if (industry != null && industry.outputPerUnit().containsKey(candidate.output())) {
        return true;
      }
    }
    return false;
  }

  /** 本户自有可用资产：候选产业 id 下、accepted kind、owner 或 operator = 本户 actor（按归属索引查，不扫全表）。 */
  private static Map<AssetKind, Long> ownAssets(
      ActorRef actor,
      IndustryId industryId,
      Set<AssetShare.RightKind> acceptedKinds,
      EntryContext context) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    Set<AssetShareId> seen = new LinkedHashSet<>();
    for (AssetShareId shareId : context.shareIdsByOperator.getOrDefault(actor, List.of())) {
      if (seen.add(shareId)) {
        mergeOwn(sums, context.tables.assetShares.get(shareId), industryId, acceptedKinds, actor);
      }
    }
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(actor, List.of())) {
      if (seen.add(shareId)) {
        mergeOwn(sums, context.tables.assetShares.get(shareId), industryId, acceptedKinds, actor);
      }
    }
    return sums;
  }

  private static void mergeOwn(
      Map<AssetKind, Long> sums,
      AssetShare share,
      IndustryId industryId,
      Set<AssetShare.RightKind> acceptedKinds,
      ActorRef actor) {
    if (share == null
        || !share.industry().equals(industryId)
        || !acceptedKinds.contains(share.kind())
        || !(share.owner().equals(actor) || share.operator().equals(actor))) {
      return;
    }
    sums.merge(share.asset(), share.quantity(), Long::sum);
  }

  /** assetSource 名下、同格、空闲（operator==owner）的份额按资产汇总（只收 requiredAssets 里点名的种类）。 */
  private static Map<AssetKind, Long> sourceAssets(
      ActorRef assetSource, HexCoord hex, Set<AssetKind> requiredKinds, EntryContext context) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(assetSource, List.of())) {
      AssetShare share = context.tables.assetShares.get(shareId);
      if (share == null
          || !share.operator().equals(share.owner())
          || !requiredKinds.contains(share.asset())
          || !hexKeyOf(share.industry()).equals(IndustryHexKeys.hexKey(hex.q(), hex.r()))) {
        continue;
      }
      sums.merge(share.asset(), share.quantity(), Long::sum);
    }
    return sums;
  }

  /** assetSource 名下、同格、空闲、指定资产的份额 id（按 id 值升序 —— 拆分顺序可复现）。 */
  private static List<AssetShareId> sourceShareIds(
      ActorRef assetSource, HexCoord hex, AssetKind asset, EntryContext context) {
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<AssetShareId> ids = new ArrayList<>();
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(assetSource, List.of())) {
      AssetShare share = context.tables.assetShares.get(shareId);
      if (share == null
          || !share.operator().equals(share.owner())
          || !share.asset().equals(asset)
          || !hexKeyOf(share.industry()).equals(hexKey)
          || share.quantity() <= 0L) {
        continue;
      }
      ids.add(shareId);
    }
    ids.sort(Comparator.comparing(AssetShareId::value));
    return ids;
  }

  /** 拆分后新份额的权利性质：按 acceptedRightKinds 里选 TENANCY → COMMUNAL → OWNED（登记期已保证非空）。 */
  private static AssetShare.RightKind chooseGrantKind(Set<AssetShare.RightKind> acceptedKinds) {
    for (AssetShare.RightKind kind :
        List.of(
            AssetShare.RightKind.TENANCY,
            AssetShare.RightKind.COMMUNAL,
            AssetShare.RightKind.OWNED)) {
      if (acceptedKinds.contains(kind)) {
        return kind;
      }
    }
    throw new IllegalStateException(
        "acceptedRightKinds 非空但没有可用的 TENANCY/COMMUNAL/OWNED: " + acceptedKinds);
  }

  // ── 执行：把一条意向写进工作副本 ──────────────────────────────────────────────────────

  /**
   * 把一条意向落到工作副本：先做<b>全量前置校验</b>（份额仍在/够、unit id 空闲、批次余量仍够），任一失败 ⇒ 返回具名原因且一个字不改；
   * 全过之后才写产业模板/unit/relation/condition/份额/配额。返回 {@link Optional#empty()} = 成功。
   *
   * <p>★★ <b>E5a 如实边界</b>：本方法里的份额写是**跨产业重新登记**（把 assetSource 的空闲份额改挂到候选的新
   * industryId，再交给本户经营），不是纯所有权/经营权转移 ⇒ 不委托 {@link AssetShareBook}（Book 的守恒式是逐 {@code (industry,
   * asset)} 的，改产业不在它语义内）。这是记为遗留的显式例外，不是新增写路径；E5b 清算的转移/拆分只走 Book。
   */
  private static Optional<String> applyIntent(EntryIntent intent, EntryContext context) {
    Tables tables = context.tables;
    for (AssetGrant grant : intent.grants()) {
      AssetShare source = tables.assetShares.get(grant.sourceShare());
      if (source == null) {
        return Optional.of("GRANT_SOURCE_GONE:" + grant.sourceShare().value());
      }
      if (source.quantity() < grant.quantity()) {
        return Optional.of(
            "GRANT_SOURCE_SHORT:share="
                + grant.sourceShare().value()
                + ",have="
                + source.quantity()
                + ",need="
                + grant.quantity());
      }
    }
    ProductionUnitId unitId = ProductionUnitId.idOf(intent.industryId(), intent.householdActor());
    if (tables.units.containsKey(unitId)) {
      return Optional.of("UNIT_ID_EXISTS:" + unitId.value());
    }
    LaborAllocationId allocationId = null;
    if (intent.laborMilli() > 0L) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      allocationId = LaborAllocation.idOf(unitId, lot, intent.household());
      if (tables.allocations.containsKey(allocationId)) {
        return Optional.of("ALLOCATION_ID_EXISTS:" + allocationId.value());
      }
      LaborSupply supply = context.laborSupply.get(lot);
      if (supply == null || supply.period() != intent.laborPeriod()) {
        return Optional.of("LABOR_SUPPLY_CHANGED:" + lot.value());
      }
      long room = supply.availableLabor() - context.allocationMilliByGroup.getOrDefault(lot, 0L);
      if (room < intent.laborMilli()) {
        return Optional.of(
            "LABOR_GROUP_ROOM_GONE:lot="
                + lot.value()
                + ",room="
                + room
                + ",need="
                + intent.laborMilli());
      }
    }

    // ── 校验全过：开始写（同一批内不再有可失败的依赖）。
    tables.industries.putIfAbsent(
        intent.industryId(), templateFor(intent.candidate(), intent.industryId()));
    tables.units.put(
        unitId,
        new ProductionUnit(
            unitId,
            intent.industryId(),
            intent.householdActor(),
            intent.candidate().modeKey(),
            0L,
            0L,
            Map.of()));
    tables.relations.put(
        unitId,
        new ProductionRelation(
            unitId,
            intent.householdActor(),
            new Recipient.ToHousehold(intent.household()),
            List.of(),
            intent.householdActor(),
            intent.candidate().laborSource()));
    tables.operatorConditions.put(
        unitId,
        new OperatorCondition(
            intent.industryId(),
            IndustryStatus.TRIALING,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            "entry:" + intent.candidate().modeKey(),
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L));
    for (AssetGrant grant : intent.grants()) {
      AssetShare source = tables.assetShares.get(grant.sourceShare());
      long left = source.quantity() - grant.quantity();
      if (left == 0L) {
        tables.assetShares.remove(grant.sourceShare());
      } else {
        tables.assetShares.put(
            source.id(),
            new AssetShare(
                source.id(),
                source.industry(),
                source.asset(),
                source.owner(),
                source.operator(),
                left,
                source.kind()));
      }
      AssetShareId newId =
          nextShareId(
              tables,
              intent.industryId(),
              grant.asset(),
              source.owner(),
              intent.householdActor(),
              grant.kind());
      tables.assetShares.put(
          newId,
          new AssetShare(
              newId,
              intent.industryId(),
              grant.asset(),
              source.owner(),
              intent.householdActor(),
              grant.quantity(),
              grant.kind()));
    }
    if (allocationId != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      tables.allocations.put(
          allocationId,
          new LaborAllocation(
              allocationId,
              lot,
              intent.household(),
              intent.householdActor(),
              unitId.value(),
              intent.laborMilli(),
              intent.laborPeriod()));
    }
    // 派生账本同步（供同批次后续评估看见本条的占用）。
    context
        .unitIdsByOperator
        .computeIfAbsent(intent.householdActor(), ignored -> new ArrayList<>())
        .add(unitId);
    if (intent.laborMilli() > 0L) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      context.allocationMilliByHousehold.merge(intent.household(), intent.laborMilli(), Long::sum);
      context.allocationMilliByGroup.merge(lot, intent.laborMilli(), Long::sum);
    }
    return Optional.empty();
  }

  /**
   * ★★ <b>E5a 如实边界：本方法仍是份额表的直接写入点，不委托 {@link AssetShareBook}</b>。理由：进入动作要把 assetSource
   * 的空闲份额<b>改登记到候选的新产业</b>（{@code intent.industryId()}）后交给本户经营，这样新 unit 才 通过 {@code
   * ProductionUnitBook.usableAssets} 的 {@code industry==unit.industry} 判据看见它；而 Book 的守恒式是 逐 {@code
   * (industry, asset)} 的，跨产业的重新登记不在它的语义内。这是记为遗留的显式例外，不是新增写路径； E5b 清算新增的转移/拆分一律只走 {@link
   * AssetShareBook}。
   */
  private static AssetShareId nextShareId(
      Tables tables,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetShare.RightKind kind) {
    for (long sequence = 0L; ; sequence++) {
      AssetShareId candidate = AssetShare.idOf(industry, asset, owner, operator, kind, sequence);
      if (!tables.assetShares.containsKey(candidate)) {
        return candidate;
      }
    }
  }

  /**
   * ★★ <b>候选 → Industry 模板</b>：{@code capacityPerUnit = requiredAssets} 的正数项；{@code
   * cycleDays/outputPerUnit/laborPerUnit} 逐值取候选；{@code slots} 用现有四阶层模板（B.4 后槽位已不参与分类上限，但 {@code
   * Industry} 构造期要求非空）；{@code allocation} 取中性 {@code Split(500,500)}（本片 relation 的空规则表 ⇒ 全部归
   * operator，allocation 不参与结算）。
   *
   * <p>★★ <b>投入表挂法（唯一一处、注释写明）</b>：候选 {@code inputPerUnit} 整体挂在 {@code requiredAssets} 第一个正需求 key
   * 下。理由：{@code Industry.inputPerUnit()} 是各内层表之和，而全仓没有按 asset 分支读 {@code cycleInputPerUnit}
   * 的现扣/收获路径 —— 挂在哪一层的值侧都会逐值进 {@code inputPerUnit()}。若将来出现按分支读的路径，必须改成显式 direct 投入字段（并同步 Codec/迁移），
   * 不许硬塞。
   */
  private static Industry templateFor(ProductionCandidate candidate, IndustryId industryId) {
    Map<AssetKind, Long> capacityPerUnit = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : candidate.requiredAssets().entrySet()) {
      if (entry.getValue() > 0L) {
        capacityPerUnit.put(entry.getKey(), entry.getValue());
      }
    }
    if (capacityPerUnit.isEmpty()) {
      throw new IllegalStateException(
          "候选 " + candidate.id().value() + " 的 requiredAssets 没有正数项，不能建 Industry 模板");
    }
    AssetKind anchor = capacityPerUnit.keySet().iterator().next();
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit = new LinkedHashMap<>();
    if (!candidate.inputPerUnit().isEmpty()) {
      cycleInputPerUnit.put(anchor, new LinkedHashMap<>(candidate.inputPerUnit()));
    }
    List<ClassSlot> slots =
        List.of(
            new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 950),
            new ClassSlot(SocialClassId.MIDDLE_PEASANT, "中农", 900),
            new ClassSlot(SocialClassId.RICH_PEASANT, "富农", 750),
            new ClassSlot(SocialClassId.LANDLORD, "地主", 100));
    return new Industry(
        industryId,
        candidate.name(),
        candidate.regime(),
        candidate.cycleDays(),
        capacityPerUnit,
        Map.of(),
        0L,
        candidate.laborPerUnit(),
        candidate.outputPerUnit(),
        cycleInputPerUnit,
        slots,
        new AllocationRule.Split(500, 500));
  }

  // ── 读数与工具 ───────────────────────────────────────────────────────────────────────

  /** 计划期/执行期的具名拒绝行（trialScale/expectedDay/laborMilli 全 0；没有可行规模可报）。 */
  private static EntryOutcome reject(
      EntryContext context, HouseholdId household, ProductionCandidate candidate, String reason) {
    ClassRow row = context.rows.get(household);
    HexCoord hex = row == null ? new HexCoord(0, 0) : row.view().hex();
    return new EntryOutcome(
        context.day,
        IndustryHexKeys.hexKey(hex.q(), hex.r()),
        household,
        candidate.id(),
        candidate.version(),
        candidate.modeKey(),
        IndustryHexKeys.id(candidate.id().value(), hex.q(), hex.r()),
        false,
        reason,
        0L,
        0L,
        Map.of(),
        0L);
  }

  /** 成功执行行（读口归因用；reason 只含小数字，不含大对象）。 */
  private static EntryOutcome accepted(EntryContext context, EntryIntent intent) {
    ClassRow row = context.rows.get(intent.household());
    HexCoord hex = row == null ? new HexCoord(0, 0) : row.view().hex();
    Map<String, Long> inputPlan = new TreeMap<>();
    for (Map.Entry<CommodityId, Long> entry : intent.inputPlan().entrySet()) {
      inputPlan.put(entry.getKey().value(), entry.getValue());
    }
    return new EntryOutcome(
        context.day,
        IndustryHexKeys.hexKey(hex.q(), hex.r()),
        intent.household(),
        intent.candidate().id(),
        intent.candidate().version(),
        intent.candidate().modeKey(),
        intent.industryId(),
        true,
        "entered:trialScale=" + intent.trialScale() + ",expectedDay=" + intent.expectedDay(),
        intent.trialScale(),
        intent.expectedDay(),
        inputPlan,
        intent.laborMilli());
  }

  private static long stockOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId household,
      CommodityId commodity) {
    Map<CommodityId, Long> goods = householdGoods.get(household);
    return goods == null ? 0L : goods.getOrDefault(commodity, 0L);
  }

  private static String hexKeyOf(IndustryId industryId) {
    return IndustryHexKeys.hexKeyOf(industryId).orElse("");
  }

  /** 工作副本组（plan 用浅拷贝，execute 用会话真副本）。 */
  private record Tables(
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<AssetShareId, AssetShare> assetShares,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRelation> relations) {

    Tables {
      Objects.requireNonNull(industries, "Tables.industries 不得为 null");
      Objects.requireNonNull(units, "Tables.units 不得为 null");
      Objects.requireNonNull(assetShares, "Tables.assetShares 不得为 null");
      Objects.requireNonNull(allocations, "Tables.allocations 不得为 null");
      Objects.requireNonNull(operatorConditions, "Tables.operatorConditions 不得为 null");
      Objects.requireNonNull(relations, "Tables.relations 不得为 null");
    }
  }

  /**
   * 一次评估内的只读派生账本（入口建一次，避免每个 (户,候选) 都全表扫）： unit↔operator、成员批次、配额和（家户/批次）、份额↔owner。apply 后只增量更新
   * unit↔operator 与配额和。
   */
  private static final class EntryContext {

    private final Tables tables;
    private final Map<DemandId, DemandEntry> demands;
    private final Map<HouseholdId, ClassRow> rows;
    private final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
    private final Map<HouseholdId, Map<CurrencyId, Long>> householdMoney;
    private final Map<PeopleLotId, LaborSupply> laborSupply;
    private final Map<HexCoord, Market> markets;
    private final long day;
    private final Map<ActorRef, List<ProductionUnitId>> unitIdsByOperator = new LinkedHashMap<>();
    private final Map<HouseholdId, List<PeopleLotId>> lotsByHousehold = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> allocationMilliByHousehold = new LinkedHashMap<>();
    private final Map<PeopleLotId, Long> allocationMilliByGroup = new LinkedHashMap<>();
    private final Map<ActorRef, List<AssetShareId>> shareIdsByOwner = new LinkedHashMap<>();
    private final Map<ActorRef, List<AssetShareId>> shareIdsByOperator = new LinkedHashMap<>();

    private EntryContext(
        Tables tables,
        Map<DemandId, DemandEntry> demands,
        Map<HouseholdId, ClassRow> rows,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<MembershipId, Membership> memberships,
        Map<PeopleLotId, LaborSupply> laborSupply,
        Map<HexCoord, Market> markets,
        long day) {
      this.tables = tables;
      this.demands = demands;
      this.rows = rows;
      this.householdGoods = householdGoods;
      this.householdMoney = householdMoney;
      this.laborSupply = laborSupply;
      this.markets = markets;
      this.day = day;
      for (ProductionUnit unit : tables.units.values()) {
        unitIdsByOperator
            .computeIfAbsent(unit.operator(), ignored -> new ArrayList<>())
            .add(unit.id());
      }
      for (AssetShare share : tables.assetShares.values()) {
        shareIdsByOwner
            .computeIfAbsent(share.owner(), ignored -> new ArrayList<>())
            .add(share.id());
        shareIdsByOperator
            .computeIfAbsent(share.operator(), ignored -> new ArrayList<>())
            .add(share.id());
      }
      for (Membership membership : memberships.values()) {
        if (membership.count() <= 0L) {
          continue;
        }
        List<PeopleLotId> lots =
            lotsByHousehold.computeIfAbsent(membership.household(), ignored -> new ArrayList<>());
        if (!lots.contains(membership.lot())) {
          lots.add(membership.lot());
        }
      }
      for (LaborAllocation allocation : tables.allocations.values()) {
        allocationMilliByHousehold.merge(
            allocation.household(), allocation.laborMilli(), Long::sum);
        allocationMilliByGroup.merge(allocation.group(), allocation.laborMilli(), Long::sum);
        List<PeopleLotId> lots =
            lotsByHousehold.computeIfAbsent(allocation.household(), ignored -> new ArrayList<>());
        if (!lots.contains(allocation.group())) {
          lots.add(allocation.group());
        }
      }
      for (Map.Entry<HouseholdId, List<PeopleLotId>> entry : lotsByHousehold.entrySet()) {
        entry.getValue().sort(Comparator.comparing(PeopleLotId::value));
      }
    }

    /** 该户可用于劳动的批次：成员份额优先（旧夹具没有成员份额则退回既有配额批次）；排序后返回。 */
    private List<PeopleLotId> lotsFor(HouseholdId household) {
      return lotsByHousehold.getOrDefault(household, List.of());
    }
  }
}
