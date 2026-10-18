package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomyEntrySettlement.AssetGrant;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.EntryContext;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.EntryIntent;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.MAX_TRIAL_SCALE_PER_ENTRY;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.Plan;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.Tables;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.WriteMode;
import static io.mosire.simos.economy.time.EntryCandidateBook.demandSignal;
import static io.mosire.simos.economy.time.EntryCandidateBook.outputCommodities;
import static io.mosire.simos.economy.time.EntryCandidateBook.planDefinitionFor;
import static io.mosire.simos.economy.time.EntryCandidateBook.plans;
import static io.mosire.simos.economy.time.EntryCandidateBook.probeEntry;
import static io.mosire.simos.economy.time.EntryCandidateBook.scaleLinearly;
import static io.mosire.simos.economy.time.EntryCandidateBook.stockOf;
import static io.mosire.simos.economy.time.EntryExecutionBook.applyIntent;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Entry 拆分 helper（由 EconomyEntrySettlement 抽取；行为不变）。 */
final class EntryEvaluationBook {

  private EntryEvaluationBook() {}

  /**
   * ★ <b>纯意向段（零残留）</b>：只读传入的状态/索引与 production 的只读投影，按 (家户升序, 方案 id 升序) 评估；可行 ⇒ 意向 （并按 {@link
   * WriteMode#SIMULATED_ONLY} 在**私有副本表**上模拟登记：新实例只写进副本的 {@code units}，relation / condition / 份额 /
   * 劳动配额也只写副本，使同批次后续 {@code assess}/{@code hasActiveDuplicate} 看见它占用的资产/劳动）， 不可行 ⇒ 具名拒绝。
   *
   * <p>★★ <b>真实状态一字不动</b>：本方法<b>不</b>调用 {@code ProductionRuntime#registerPlan} / {@code
   * ProductionRuntime#createInstance} / {@code
   * EconomyReferenceValidation#validateProductionInstances}，也不改任何传入的 map ⇒ production 的定义、实例与
   * {@code changeSet()} 在返回前后逐值相同（连跑多次结果一致、无需回滚）；副本表在方法返回后即丢。
   *
   * <p>★★ B2c：输入从 {@code Map<CandidateId, ProductionCandidate>} 换成 {@code Map<IndustryId,
   * IndustryDefinitionView> industries}（production 的<b>当刻定义权威</b>）； 池由 {@link #plans(Map)}
   * 现算。{@code planAssetSources} = {@code EconomyMeta} 里"谁提供闲置份额"的声明 （缺省空表 ⇒ 只认自有份额）。
   */
  static Plan planEntries(
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      Map<DemandId, HouseholdDemand> householdDemands,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<IndustryId, ActorRef> planAssetSources,
      Map<IndustryId, LaborSource> planLaborSources,
      SettlementIndex index,
      MarketIndex markets,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRules> relations,
      long day) {
    Objects.requireNonNull(runtime, "runtime（D-032：试算走 production 契约，不接受 null）");
    Objects.requireNonNull(householdDemands, "demands");
    Objects.requireNonNull(industries, "industries（方案池的来源：production 的当刻定义权威）");
    Objects.requireNonNull(planAssetSources, "planAssetSources（缺省给空表 = 只认自有份额）");
    Objects.requireNonNull(planLaborSources, "planLaborSources（缺省给空表 = 全部 SELF）");
    Objects.requireNonNull(index, "index（planEntries 目前只用它做只读一致性锚点，见方法注释）");
    Map<IndustryId, IndustryDefinitionView> candidatePlans = plans(industries);
    if (householdDemands.isEmpty() || candidatePlans.isEmpty()) {
      return new Plan(List.of(), List.of());
    }
    // ★ 副本表：意向段需要"看见本批已占用的资产/劳动"，但真实状态一字不动。
    Tables shadow =
        new Tables(
            new LinkedHashMap<>(industries),
            new LinkedHashMap<>(units),
            new LinkedHashMap<>(assetShares),
            new LinkedHashMap<>(laborCommitments),
            new LinkedHashMap<>(operatorConditions),
            new LinkedHashMap<>(relations),
            new LinkedHashMap<>());
    EntryContext context =
        new EntryContext(
            runtime,
            shadow,
            householdDemands,
            householdEconomies,
            householdGoods,
            composition,
            markets,
            planAssetSources,
            planLaborSources,
            day);
    List<HouseholdId> orderedHouseholds = new ArrayList<>(householdEconomies.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    List<IndustryDefinitionView> orderedPlans = new ArrayList<>(candidatePlans.values());
    orderedPlans.sort(Comparator.comparing(plan -> plan.id().value()));
    List<EntryIntent> intents = new ArrayList<>();
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (HouseholdId household : orderedHouseholds) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null || householdEconomy.population() <= 0L) {
        continue;
      }
      for (IndustryDefinitionView plan : orderedPlans) {
        Attempt attempt = assess(household, plan, context);
        if (attempt.intent() == null) {
          if (attempt.rejection() != null) {
            outcomes.add(attempt.rejection());
          }
          continue;
        }
        // ★★ F5（R6）：意向段只模拟 —— 只写副本表，绝不触发 production 的写口。
        Optional<String> failure = applyIntent(attempt.intent(), context, WriteMode.SIMULATED_ONLY);
        if (failure.isPresent()) {
          outcomes.add(reject(context, household, plan, failure.get()));
        } else {
          intents.add(attempt.intent());
        }
      }
    }
    return new Plan(intents, outcomes);
  }

  // ── 评估 ─────────────────────────────────────────────────────────────────────────────

  /** 需求信号三档：没有命中（跳过该对）、命中但窗口太短（具名拒绝）、可用。 */
  enum DemandSignal {
    NONE,
    TOO_SHORT,
    OK
  }

  /** 评估结果：恰有一个非空 —— {@code intent}（可行）或 {@code rejection}（具名拒绝）；两者都空 = 不评估。 */
  record Attempt(EntryIntent intent, EntryOutcome rejection) {

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
   * 对一对 (家户, 方案) 做完整可行性评估（只读；不改任何表）。返回 {@link Attempt#skip()} = 该户没有命中方案任一产出商品的 有效需求 （不产出读口行， 避免
   * 6000×方案条无意义的 NO_DEMAND）。
   */
  static Attempt assess(HouseholdId household, IndustryDefinitionView plan, EntryContext context) {
    try {
      HouseholdEconomy householdEconomy = context.householdEconomies.get(household);
      if (householdEconomy == null || householdEconomy.population() <= 0L) {
        return Attempt.skip();
      }
      HexCoord hex = householdEconomy.view().hex();
      if (plan.id().value().indexOf('.') >= 0) {
        // ★ unit id / OwnershipStake id 都不允许 '.'（地址解析约定）；登记期未拦，进入期 fail-closed，不半建。
        return Attempt.rejection(reject(context, household, plan, "PLAN_ID_INVALID:dot"));
      }
      IndustryId industryId = IndustryHexKeys.id(plan.id().value(), hex.q(), hex.r());
      // ★★ B2c：本格**已物化**的定义（若有）就是当刻有效定义 —— 读它一次（只读、便宜），窗口与产出商品集都取它；
      //   ★ 未物化时"有效定义"逐值等于方案自身（物化就是按方案复制 + 换 id），故这里直接用方案的两个读数，
      //     既与物化结果逐值相同，又不必为"没有需求的多数对"白构造一份定义。
      Optional<IndustryDefinitionView> registered =
          context.runtime.definitions().industry(industryId);
      long horizon;
      try {
        horizon =
            Math.addExact(
                registered.map(IndustryDefinitionView::buildDays).orElse(plan.buildDays()),
                registered.map(IndustryDefinitionView::cycleDays).orElse(plan.cycleDays()));
      } catch (ArithmeticException overflow) {
        return Attempt.rejection(reject(context, household, plan, "DEMAND_HORIZON_OVERFLOW"));
      }
      List<CommodityId> outputs = outputCommodities(registered.orElse(plan));
      DemandSignal signal = demandSignal(household, outputs, hex, horizon, context);
      if (signal == DemandSignal.NONE) {
        return Attempt.skip();
      }
      if (signal == DemandSignal.TOO_SHORT) {
        return Attempt.rejection(reject(context, household, plan, "DEMAND_TOO_SHORT"));
      }
      ActorRef actor = HouseholdActors.of(household);
      if (hasActiveDuplicate(actor, outputs, plan.id().value(), context)) {
        return Attempt.rejection(reject(context, household, plan, "ALREADY_ENTERED"));
      }
      // ★★ R2：同一 (industry, operator) 已有 unit ⇒ 不自动创建第二个同 scope unit（旧档 opaque id 也要拦住）。
      //   显式种子若已形成共享 scope，预计/执行仍按同一具名理由 fail-closed。
      ProductionUnitId unitId = ProductionUnitId.idOf(industryId, actor);
      if (hasUnitInScope(context.tables.units(), industryId, actor)) {
        // 同一 revision 的 D4 预算只含既有 unit；候选没有显式占用请求 ⇒ 具名拒绝，不回退整池份额。
        return Attempt.rejection(
            reject(context, household, plan, "asset-occupancy-request-missing:" + unitId.value()));
      }
      if (context.tables.units().containsKey(unitId)) {
        return Attempt.rejection(reject(context, household, plan, "UNIT_ID_EXISTS"));
      }
      // ★★ 本格有效定义：已物化则原样沿用，否则按方案逐字段复制成 `<planId>@<q>_<r>`。
      IndustryDefinitionView planDefinition = planDefinitionFor(context, plan, industryId);
      if (planDefinition.acceptedRightKinds().isEmpty()) {
        return Attempt.rejection(reject(context, household, plan, "NO_RIGHT_KIND"));
      }

      // ★★★ ② ★★ B2（§13.10.4 第 1 条）：**可行性 / 规模 / 劳动与投入需求 / 预计产出一律来自 `probe`**。
      //   旧形态在本方法里自己算"资产/劳动/投入 三条 cap 取 min"——那是与 production 平行的**第二份算式**，
      //   它必然与 `execute` 的真实公式漂开（同一份配置、两套算术 ⇒ 预计与实际不同源）。
      //   现在 economy 只做三件它还该做的事：需求信号（上面）、价格与利润打分（下面）、资产拆分的腿（再下面）。
      //   ★ `probe` 无副作用（I5）：不改任何切片、不推进时间、不涨经验。
      String entryLocationKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
      Map<AssetKind, Long> own =
          ownAssets(
              actor, industryId, planDefinition.acceptedRightKinds(), entryLocationKey, context);
      // ★★ B2c（裁定八）：闲置份额来源主体留 economy —— 按**方案 id** 声明的外部来源（缺省 = 只认自有份额）。
      Optional<ActorRef> assetSource = Optional.ofNullable(context.planAssetSources.get(plan.id()));
      // ★★★ D-032 §15.6（R6）：`probe` 的 `assetOccupancy` = 候选**将要实占**的资产 = 自有份额 ∪ 外部允许来源。
      //   · 资产种类集取方案定义的 `capacityPerUnit` 键集（= probe 资产通道的约束面）⇒ `source` 必须提前到 probe 之前算；
      //   · `probe.capacityScaleOf` 对**空占用显式返回 0**（"没占资产就没法生产"）⇒ 旧"占用给空表"使一切候选
      //     在 `NO_ASSET_REQUIREMENT`/`PROBE_SCALE_ZERO` 处必拒（生产入口死路）。这里按将实占给，规模才与
      //     `execute` 同源（I6）。
      Map<AssetKind, Long> source =
          assetSource.isPresent()
              ? sourceAssets(
                  assetSource.get(), hex, planDefinition.capacityPerUnit().keySet(), context)
              : Map.of();
      Map<AssetKind, Long> assetOccupancy = occupiedAssets(own, source);
      // ★★ D-032 §15.11（E1-A2）：`probe` 按 `request.plan()` 查 production 的**当刻已登记定义**
      //   （`current.industries()`）；而本格物化 id `<planId>@<q>_<r>` 在 `planDefinitionFor` 里**只合成、不登记**
      //   ——登记只发生在 execute→applyIntent（REAL 模式）的 `registerPlan`，在 assess 之后（planEntries/
      //   assess 必须只读，不得为此调 `registerPlan` 制造写副作用）⇒ 某格**首次**采用某方案时，
      //   probe 拿物化 id 会查不到定义、被 `PROBE_INFEASIBLE:plan-unknown` 拒。
      //   ★ 未物化 ⇒ 改用方案池里的 **unbound 方案 id** 试算：物化是纯复制（两定义逐值相同），且 unbound 方案
      //     必然已在 `current.industries()` 里（否则它进不了 `plans()` 池）⇒ probe 读到的就是同一份数值定义。
      IndustryId probePlanId = registered.isPresent() ? industryId : plan.id();
      io.mosire.simos.production.contract.ProductionProbeResult probe =
          probeEntry(context, household, probePlanId, own, assetOccupancy, planDefinition);
      if (!probe.feasible()) {
        // ★ 业务拒绝 = INFO 具名理由（I15）；理由直接取 production 的具名 reason（不在这里改写措辞）。
        return Attempt.rejection(
            reject(context, household, plan, "PROBE_INFEASIBLE:" + probe.reason()));
      }

      Map<AssetKind, Long> required = probe.assetRequired();
      // ★ 防御（E1-A2 复核）：`assetRequired` 非空 ⟺ probe 的 `baseScale > 0`（`requiredAssets` 逐键 =
      //   `baseScale × capacityPerUnit`，且只收正值）⇒ 空表只可能是"可行但 baseScale = 0"（占用为空/退化组合）
      //   的零规模候选。保留本具名拒绝（先于下面的 `PROBE_SCALE_ZERO` 给出"资产需求为空"的读数），它不会
      //   命中可生产的候选 —— 判据方向不变，只留一个更具体的拒绝理由。
      boolean anyPositiveAsset = false;
      for (long quantity : required.values()) {
        if (quantity > 0L) {
          anyPositiveAsset = true;
          break;
        }
      }
      if (!anyPositiveAsset) {
        return Attempt.rejection(reject(context, household, plan, "NO_ASSET_REQUIREMENT"));
      }

      // ★★ 分配阶段的规模上限（economy 的事）：配置上限与 probe 的产出规模。
      //   ★ D-032 §15.11（E1-A2）：删掉旧 `assetCap = min_k(available_k ÷ required_k)` 的**二次夹** ——
      //     `required = probe.assetRequired()` 是 `baseScale` 处的**绝对量**（不是每单位量），
      //     `available = own ∪ source` 是同一份占用 ⇒ `available ÷ required` 只是"资产规模 ÷ baseScale"；
      //     资产是约束面（真实世界常见）时它恒为 1，把 `trialScale` 压成 1、`MAX_TRIAL_SCALE_PER_ENTRY` 失效。
      //     probe 已按**同一份占用**夹过资产通道，资产不足由 `PROBE_INFEASIBLE:asset-short` 具名拒
      //     ⇒ 这里不再对同一约束夹第二次。
      long cap = MAX_TRIAL_SCALE_PER_ENTRY;
      long probeScale = probe.scale();
      cap = Math.min(cap, probeScale);
      if (probeScale <= 0L) {
        // ★ "可行但一个规模单位都做不了"⇒ 排不进分配（与旧 `cap < 1` 同一条具名拒绝）。
        return Attempt.rejection(
            reject(context, household, plan, "PROBE_SCALE_ZERO:plan=" + industryId.value()));
      }

      // ★★ ④ 生计下限（economy 的口径，不是 physical 可行性）：口粮必须留足 —— 本户当前注入需求 × 窗口天数。
      long grainStock = stockOf(context.householdGoods, household, EconomySettlement.GRAIN);
      long grainReserve =
          householdEconomy.expectedNeedMilli(EconomySettlement.GRAIN, Math.addExact(horizon, 1L));
      if (grainStock < grainReserve) {
        return Attempt.rejection(
            reject(
                context,
                household,
                plan,
                "SUBSISTENCE_SHORT:grainStock=" + grainStock + ",need=" + grainReserve));
      }

      // ★★ ③ 劳动：批次选择与"本户时间预算余量"仍归 economy（那两件是分配，不是物理）。
      //   ★ 物理可行性（劳动规模 ≥ 1）已由 `probe` 判过；这里把"本户剩余时间"夹进规模，
      //     并**不变式**保持成立：`probe.laborRequiredMilli` 在可行时 ≤ 该户可用劳动（§13.9 裁定四）。
      List<PeopleLotId> lots = List.of();
      long laborPeriod = 0L;
      long laborRequiredClamped = 0L;
      long householdRoom =
          householdEconomy.laborMilli()
              - context.allocationMilliByHousehold.getOrDefault(household, 0L);
      if (probe.laborRequiredMilli() > 0L) {
        // ★★★ D-032 §13.11.3 裁定七第 2 条（取整 slack）：`laborRequiredMilli` 向上取整、`baseScale` 向下取整
        //   ⇒ 临界点上允许**最多 1 个劳动单位（千分劳动·日）**的取整余量：
        //     · required ≤ 可用 + slack ⇒ 视为满足，把需求**钳到可用劳动**（那 1 个单位不是真实欠投）；
        //     · required > 可用 + slack ⇒ 契约故障（LABOR 折回口径写错），终止整个候选提交（ERROR 不降级）。
        long laborSlack =
            io.mosire.simos.production.contract.ProductionProbeResult
                .LABOR_REQUIRED_ROUNDING_SLACK_MILLI;
        if (probe.laborRequiredMilli() > Math.addExact(householdRoom, laborSlack)) {
          throw new io.mosire.simos.production.contract.ProductionContractFault(
              "probe-labor-required-exceeds-available",
              "probe 判可行但 laborRequiredMilli("
                  + probe.laborRequiredMilli()
                  + ") > 本户可用劳动("
                  + householdRoom
                  + ") + 取整 slack("
                  + laborSlack
                  + ")：§13.9 裁定四的契约不变式被破坏（laborRequiredMilli 的 LABOR 折回口径）。"
                  + " unit="
                  + unitId.value()
                  + " household="
                  + household.value());
        }
        // ★ 钳到可用劳动：命中 slack 时按可用劳动投（取整余量不占真实劳动）。
        laborRequiredClamped = Math.min(probe.laborRequiredMilli(), householdRoom);
        List<PeopleLotId> lotChoices = context.lotsFor(household);
        if (lotChoices.isEmpty()) {
          return Attempt.rejection(reject(context, household, plan, "LABOR_NO_LOT"));
        }
        lots = List.of(lotChoices.get(0));
        laborPeriod = 1L; // ★ 只是审计标签（供给表删除后 period 不再有供给权威）
      }
      // ★ E1-A2：`cap = min(MAX_TRIAL_SCALE_PER_ENTRY, probe.scale())`，而上面的 `probeScale <= 0` 已具名拒
      //   ⇒ 到这里 cap ≥ 1。旧 `SCALE_CAP_BELOW_ONE` 分支因此不可达（且若被触发就是误杀），按"不留不可达
      //   误杀"删除；`trialScale` 的构造期守卫（必须 ≥ 1）仍保留。
      long trialScale = cap;

      // ★★ 投入需求（本周期一次性）与窗口覆盖一律按 `probe.inputRequired`（基准规模口径）线性换算，
      //   不再读方案自己的 `cycleInputPerUnit`——economy 只乘价、不做第二套物理算式（§13.10.3）。
      Map<CommodityId, Long> inputPlan = new LinkedHashMap<>();
      Map<CommodityId, Long> horizonNeed = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : probe.inputRequired().entrySet()) {
        long perCycle = scaleLinearly(entry.getValue(), trialScale, probeScale);
        if (perCycle <= 0L) {
          continue;
        }
        inputPlan.put(entry.getKey(), perCycle);
        horizonNeed.put(entry.getKey(), Math.multiplyExact(perCycle, horizon));
      }

      // ⑥ 可观察价 score（三项同一量纲：毫商品 × 毫银/商品；只用来判符号）：
      //   收益 = Σ(每个产出商品的单位量 × 该商品产出价)；成本 = Σ 正投入单位量 × 各自价；自留口粮 = 劳动 × 给养 × 粮价。
      //   ★ 任务书口径"缺价 ⇒ 不可行"：**每个产出商品**、任一正投入、给养要用的粮缺价，都具名拒绝 ——
      //   不按 0 假装便宜（那不是保守，是把无价成本当成免费）。
      //   ★★ B2c（裁定八）：产出**按商品逐项**处理（"主产出"概念已删）；单产出方案退化到旧口径逐值一致。
      MarketTable market = context.markets.at(hex);
      if (market == null) {
        return Attempt.rejection(reject(context, household, plan, "NO_MARKET"));
      }
      List<String> missingPrices = new ArrayList<>();
      // ★★ 成本与收益的**物理量侧**一律取 `probe`（同一入口、同一批公式、同一覆盖参数）——
      //   economy 只负责"乘上可观察价"这一步（价格/需求不进 probe，§4.2）。
      long revenue = 0L;
      for (CommodityId commodity : outputs) {
        long price = market.priceOf(commodity);
        if (price <= 0L) {
          missingPrices.add(commodity.value());
          continue;
        }
        revenue =
            Math.addExact(
                revenue,
                Math.multiplyExact(
                    scaleLinearly(
                        probe.expectedOutput().getOrDefault(commodity, 0L), trialScale, probeScale),
                    price));
      }
      long inputCost = 0L;
      for (Map.Entry<CommodityId, Long> entry : probe.inputRequired().entrySet()) {
        if (entry.getValue() <= 0L) {
          continue;
        }
        long price = market.priceOf(entry.getKey());
        if (price <= 0L) {
          missingPrices.add(entry.getKey().value());
          continue;
        }
        inputCost =
            Math.addExact(
                inputCost,
                Math.multiplyExact(scaleLinearly(entry.getValue(), trialScale, probeScale), price));
      }
      long subsistenceCost = 0L;
      if (probe.laborRequiredMilli() > 0L) {
        long grainPrice = market.priceOf(EconomySettlement.GRAIN);
        if (grainPrice <= 0L) {
          missingPrices.add(EconomySettlement.GRAIN.value());
        } else {
          subsistenceCost =
              Math.multiplyExact(
                  Math.multiplyExact(
                      scaleLinearly(laborRequiredClamped, trialScale, probeScale),
                      RegimeRelations.subsistenceMilliPerLabor()),
                  grainPrice);
        }
      }
      if (!missingPrices.isEmpty()) {
        return Attempt.rejection(
            reject(context, household, plan, "PRICE_MISSING:" + missingPrices));
      }
      long score = revenue - inputCost - subsistenceCost;
      if (score < 0L) {
        return Attempt.rejection(
            reject(
                context,
                household,
                plan,
                "SCORE_NEGATIVE:revenue="
                    + revenue
                    + ",inputCost="
                    + inputCost
                    + ",subsistence="
                    + subsistenceCost));
      }

      // ⑤ 资产拆分的具体腿（自有份额已够的部分不动；只拆缺的部分）。
      //   ★ E1-A2：`required` 是 probeScale（基准规模）处的**绝对量**，而 `trialScale` 是产出规模 ⇒ 与
      //     `inputPlan`/劳动同一口径线性换算。旧式 `required × trialScale` 在 probeScale ≠ 1 时把资产需求
      //     放大 probeScale 倍；删掉 `assetCap` 后 `trialScale` 可到 10，不换算会在这里新造一个假 `ASSET_SHORT`
      //     （把 T2 刚放开的候选又按错误量纲拒掉）。
      AssetRightKind grantKind = chooseGrantKind(planDefinition.acceptedRightKinds());
      List<AssetGrant> grants = new ArrayList<>();
      for (Map.Entry<AssetKind, Long> entry : required.entrySet()) {
        if (entry.getValue() <= 0L) {
          continue;
        }
        long need = scaleLinearly(entry.getValue(), trialScale, probeScale);
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
                  plan,
                  "ASSET_SHORT:asset=" + entry.getKey() + ",need=" + need + ",own=" + ownQuantity));
        }
        for (AssetShareId shareId :
            sourceShareIds(assetSource.get(), hex, entry.getKey(), context)) {
          OwnershipStake share = context.tables.assetShares().get(shareId);
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
                  plan,
                  "ASSET_SHORT:asset="
                      + entry.getKey()
                      + ",need="
                      + need
                      + ",sourceShort="
                      + remaining));
        }
      }
      // ★ 劳动投入 = `probe.laborRequiredMilli` 按规模线性换算（与上面 inputPlan 同一口径：probe 值 × trialScale ÷
      // probe.scale）。
      long laborMilli = scaleLinearly(laborRequiredClamped, trialScale, probeScale);
      // ★ 进入当天 progress 记 1，第 cycleDays 天关账产出的那个日结算日 = 进入日 + cycleDays − 1（D=1/cycleDays=30 ⇒ 第 30
      // 天）。
      //   ★ 周期长度取**方案定义**（production 的权威）。
      long expectedDay = Math.addExact(context.day, planDefinition.cycleDays() - 1L);
      EntryIntent intent =
          new EntryIntent(
              household,
              actor,
              plan,
              industryId,
              planDefinition,
              // ★ B2c：地点由**实例**显式携带（方案 id 的格段只是生成约定）；物化定义的 id 带格键 ⇒ 二者一致。
              new io.mosire.simos.production.contract.ProductionLocation(
                  IndustryHexKeys.hexKey(hex.q(), hex.r())),
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
          reject(context, household, plan, "ARITHMETIC_OVERFLOW:" + overflow.getMessage()));
    } catch (IllegalStateException | IllegalArgumentException invalid) {
      return Attempt.rejection(
          reject(context, household, plan, "INVALID_STATE:" + invalid.getMessage()));
    }
  }

  /**
   * 幂等：本户已有同 modeKey 或<b>产出商品集相交</b>的 ACTIVE/TRIALING unit（condition 缺失按 ACTIVE 口径， 见
   * StressPolicy）。
   *
   * <p>★★ B2c（裁定八）：{@code modeKey} 现在是<b>方案 id 字面</b>（版本键已删）；"同主产出"的逐商品推广 = 产出商品集<b>相交</b>（单产出方案退化成
   * {@code containsKey} 的旧判据，逐值相同）。
   */
  static boolean hasActiveDuplicate(
      ActorRef actor, List<CommodityId> outputs, String modeKey, EntryContext context) {
    for (ProductionUnitId unitId : context.unitIdsByOperator.getOrDefault(actor, List.of())) {
      ProductionProcess unit = context.tables.units().get(unitId);
      if (unit == null) {
        continue;
      }
      OperatorCondition condition = context.tables.operatorConditions().get(unitId);
      IndustryStatus status = condition == null ? IndustryStatus.ACTIVE : condition.status();
      if (status != IndustryStatus.ACTIVE && status != IndustryStatus.TRIALING) {
        continue;
      }
      if (unit.modeKey().equals(modeKey)) {
        return true;
      }
      // ★★ B2（§13.10.4 第 4 条 / U7）：实例引用的方案定义读 **production.contract 的只读投影**
      //   （`ProductionRuntime#definitions()` = 同一候选态的唯一权威），不再读 economy 的本地表 ——
      //   后者可能落后于 production 的当刻候选态（判据本身会因此漂移）。★ 守卫一条没删，只换了读哪一份。
      IndustryDefinitionView industry =
          context.runtime.definitions().industry(unit.industry()).orElse(null);
      if (industry == null) {
        continue;
      }
      for (CommodityId commodity : outputs) {
        if (industry.outputPerUnit().containsKey(commodity)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 本户自有可用资产：该格物化定义 id 下、accepted kind、owner 或 operator = 本户 actor（按归属索引查，不扫全表）。 */
  /** 同一 {@code (industry, operator)} 是否已有生产实例；只做 scope 守卫，不读资产数量。 */
  static boolean hasUnitInScope(
      Map<ProductionUnitId, ProductionProcess> units, IndustryId industry, ActorRef operator) {
    for (ProductionProcess unit : units.values()) {
      if (unit.industry().equals(industry) && unit.operator().equals(operator)) {
        return true;
      }
    }
    return false;
  }

  static Map<AssetKind, Long> ownAssets(
      ActorRef actor,
      IndustryId industryId,
      Set<AssetRightKind> acceptedKinds,
      String locationKey,
      EntryContext context) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    Set<AssetShareId> seen = new LinkedHashSet<>();
    for (AssetShareId shareId : context.shareIdsByOperator.getOrDefault(actor, List.of())) {
      if (seen.add(shareId)) {
        mergeOwn(
            sums,
            context.tables.assetShares().get(shareId),
            industryId,
            acceptedKinds,
            locationKey,
            actor);
      }
    }
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(actor, List.of())) {
      if (seen.add(shareId)) {
        mergeOwn(
            sums,
            context.tables.assetShares().get(shareId),
            industryId,
            acceptedKinds,
            locationKey,
            actor);
      }
    }
    return sums;
  }

  static void mergeOwn(
      Map<AssetKind, Long> sums,
      OwnershipStake share,
      IndustryId industryId,
      Set<AssetRightKind> acceptedKinds,
      String locationKey,
      ActorRef actor) {
    if (share == null
        || !share.industry().equals(industryId)
        || !share.location().value().equals(locationKey)
        || !acceptedKinds.contains(share.kind())
        || !(share.owner().equals(actor) || share.operator().equals(actor))) {
      return;
    }
    sums.merge(share.asset(), share.quantity(), Long::sum);
  }

  /**
   * ★★ <b>D-032 §15.6（R6）：候选将要实占的资产 = 自有份额 ∪ 外部允许来源</b>（逐 {@link AssetKind} 相加，只留正值）。
   *
   * <p>★ 为什么在 probe <b>之前</b>算：{@code probe} 的资产通道按<b>占用</b>夹规模（{@code capacityScaleOf}；空占用 ⇒
   * 0），故"本户能凑到的资产"必须作为占用入参给进去，而不是在 probe 之后再夹一次。★ 只留正值是 {@link
   * io.mosire.simos.production.contract.ProductionProbeRequest#assetOccupancy()} 的契约（"0 不进表"）；两项相加用
   * {@code Math.addExact} ⇒ 溢出由调用方的 {@code ARITHMETIC_OVERFLOW} 具名拒绝兜住（fail-closed）。
   */
  static Map<AssetKind, Long> occupiedAssets(
      Map<AssetKind, Long> own, Map<AssetKind, Long> source) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (Map<AssetKind, Long> part : List.of(own, source)) {
      for (Map.Entry<AssetKind, Long> entry : part.entrySet()) {
        long quantity = Math.addExact(sums.getOrDefault(entry.getKey(), 0L), entry.getValue());
        if (quantity > 0L) {
          sums.put(entry.getKey(), quantity);
        }
      }
    }
    return sums;
  }

  /** assetSource 名下、同格、空闲（operator==owner）的份额按资产汇总（只收方案 `capacityPerUnit` 点名的种类）。 */
  static Map<AssetKind, Long> sourceAssets(
      ActorRef assetSource, HexCoord hex, Set<AssetKind> capacityKinds, EntryContext context) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(assetSource, List.of())) {
      OwnershipStake share = context.tables.assetShares().get(shareId);
      if (share == null
          || !share.operator().equals(share.owner())
          || !capacityKinds.contains(share.asset())
          || !share.location().value().equals(IndustryHexKeys.hexKey(hex.q(), hex.r()))) {
        continue;
      }
      sums.merge(share.asset(), share.quantity(), Long::sum);
    }
    return sums;
  }

  /** assetSource 名下、同格、空闲、指定资产的份额 id（按 id 值升序 —— 拆分顺序可复现）。 */
  static List<AssetShareId> sourceShareIds(
      ActorRef assetSource, HexCoord hex, AssetKind asset, EntryContext context) {
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<AssetShareId> ids = new ArrayList<>();
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(assetSource, List.of())) {
      OwnershipStake share = context.tables.assetShares().get(shareId);
      if (share == null
          || !share.operator().equals(share.owner())
          || !share.asset().equals(asset)
          || !share.location().value().equals(hexKey)
          || share.quantity() <= 0L) {
        continue;
      }
      ids.add(shareId);
    }
    ids.sort(Comparator.comparing(AssetShareId::value));
    return ids;
  }

  /** 拆分后新份额的权利性质：按 acceptedRightKinds 里选 TENANCY → COMMUNAL → OWNED（登记期已保证非空）。 */
  static AssetRightKind chooseGrantKind(Set<AssetRightKind> acceptedKinds) {
    for (AssetRightKind kind :
        List.of(AssetRightKind.TENANCY, AssetRightKind.COMMUNAL, AssetRightKind.OWNED)) {
      if (acceptedKinds.contains(kind)) {
        return kind;
      }
    }
    throw new IllegalStateException(
        "acceptedRightKinds 非空但没有可用的 TENANCY/COMMUNAL/OWNED: " + acceptedKinds);
  }

  // ── 读数与工具 ───────────────────────────────────────────────────────────────────────

  /** 计划期/执行期的具名拒绝行（trialScale/expectedDay/laborMilli 全 0；没有可行规模可报）。 */
  static EntryOutcome reject(
      EntryContext context, HouseholdId household, IndustryDefinitionView plan, String reason) {
    HouseholdEconomy householdEconomy = context.householdEconomies.get(household);
    HexCoord hex = householdEconomy == null ? new HexCoord(0, 0) : householdEconomy.view().hex();
    return new EntryOutcome(
        context.day,
        IndustryHexKeys.hexKey(hex.q(), hex.r()),
        household,
        plan.id(),
        IndustryHexKeys.id(plan.id().value(), hex.q(), hex.r()),
        false,
        reason,
        0L,
        0L,
        Map.of(),
        0L);
  }

  /** 成功执行行（读口归因用；reason 只含小数字，不含大对象）。 */
  static EntryOutcome accepted(EntryContext context, EntryIntent intent) {
    HouseholdEconomy householdEconomy = context.householdEconomies.get(intent.household());
    HexCoord hex = householdEconomy == null ? new HexCoord(0, 0) : householdEconomy.view().hex();
    Map<String, Long> inputPlan = new TreeMap<>();
    for (Map.Entry<CommodityId, Long> entry : intent.inputPlan().entrySet()) {
      inputPlan.put(entry.getKey().value(), entry.getValue());
    }
    return new EntryOutcome(
        context.day,
        IndustryHexKeys.hexKey(hex.q(), hex.r()),
        intent.household(),
        intent.plan().id(),
        intent.industryId(),
        true,
        "entered:trialScale=" + intent.trialScale() + ",expectedDay=" + intent.expectedDay(),
        intent.trialScale(),
        intent.expectedDay(),
        inputPlan,
        intent.laborMilli());
  }
}
