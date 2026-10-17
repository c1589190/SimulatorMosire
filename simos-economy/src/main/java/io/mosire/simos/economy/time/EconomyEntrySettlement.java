package io.mosire.simos.economy.time;

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
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionRosters;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
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
 * ★★ <b>R4-E2b / D-032 B2c：方案定义 → 合条件主体的实际采用（运行期进入/试产）</b>—— 本片只做"本格有有效需求 +
 * 生产模块里有一份<b>不绑地点</b>的方案定义"时，让可行家户形成一条 {@code OperatorCondition.IndustryStatus#TRIALING} 的新 {@code
 * ProductionProcess}；<b>不做</b>自动发明、全局 ROI 切换、E3 经验、E4 梯度消费。
 *
 * <p>★★ <b>B2c：候选面（{@code ProductionCandidate}）整体退役</b>（裁定八）。今天的"可以采用的东西"就是 production
 * 里的<b>方案定义</b>（{@link IndustryDefinitionView}）：
 *
 * <ul>
 *   <li><b>方案池</b> = {@link #plans(Map)}：{@code hexKeyOf(id)} 为空的定义（<b>不绑地点</b>的稳定 id）。
 *       带格键的定义（{@code farm@0_0} 一类，播种产物）<b>不是</b>方案 —— 它们是"某格已经物化好的定义"， 只能被 {@link
 *       #planDefinitionFor} 当作"已存在的定义原样沿用"读到；
 *   <li><b>版本键退役</b>：方案身份 = 稳定 {@code IndustryId}；{@code modeKey} 就是方案 id 字面 （不再有 {@code
 *       id@version}）。"修订"= 经 {@code ProductionRuntime#registerPlan} 写新 id 的定义；
 *   <li><b>主产出概念退役</b>：产出是 {@code Map<CommodityId, Long> outputPerUnit} —— economy <b>按商品逐项</b>
 *       处理（每个产出商品各自进需求匹配与取价，家户利润 = Σ产出×市价 − Σ投入×市价）。★ 单产出方案的算法 结果与旧"单一主产出"口径逐值相同（退化情形，见账本 B2c 节）；
 *   <li><b>建设期 / 资产接受面</b>：{@link IndustryDefinitionView#buildDays()} / {@link
 *       IndustryDefinitionView#acceptedRightKinds()}（B2c 新增组件，裁定八）；
 *   <li><b>闲置份额来源主体</b>（旧候选的 {@code assetSource}）：留 economy —— {@code
 *       EconomyMeta.planAssetSources}（按方案 id 声明的外部来源主体；缺省 = 只认自有份额）。
 * </ul>
 *
 * <p>★★ <b>两段式（纯意向 + 执行）</b>，理由与计划一致：
 *
 * <ol>
 *   <li>{@link #planEntries} <b>只读</b>地按"家户升序 × 方案(id)升序"评估每一对，产出 {@link EntryIntent} 与具名拒绝 {@link
 *       EntryOutcome}；评估所需的派生量（unit↔operator、配额和、份额归属）在入口建一次，<b>不</b>做 O(unit×allocation) 全表扫描； ★
 *       它<b>不</b>碰 production 的任何写口（不调 {@code registerPlan}/{@code createInstance}/实例守卫），
 *       production 的定义、实例与 {@code changeSet()} 一字不动 —— 连跑多次也只留下"意向"这一份纯读数；
 *   <li>{@link #execute} 在日结算的"现扣投入/劳动再分配"之前对每条意向<b>重新评估</b>（计划到执行之间可能已有别的意向改了资产/劳动），
 *       通过后才真写：production 的 {@code registerPlan}（本格物化定义）→ {@code createInstance}（实例）→ 实例引用/构造守卫，
 *       再写工作副本的 relation → condition → 份额拆分 → 劳动配额。任一步失败 ⇒ 当条拒绝，不半建。
 * </ol>
 *
 * <p>★★ <b>两条路径的"幂等/零残留"语义不同，不混用</b>：意向段（{@code planEntries}）是<b>零残留</b> —— 它只把"本批模拟 出来的占用"登记在私有副本表
 * {@code Tables} 里（新实例也只进副本表的 {@code units}，供同批次后续 {@code assess}/ {@code hasActiveDuplicate}
 * 看见），方法返回副本即丢、真实状态不需要回滚；执行段（{@code execute}）是<b>真写 + 逐条原子</b> ——
 * 每条意向先做全量前置校验，全过才写，失败只拒该条（已成功的条不回滚，调用方按 {@link Execution#changed()} 决定是否换 索引）。production
 * 侧两件写口自身幂等（同 id 同值 = no-op；同 id 实例已存在 = 具名 {@code unit-conflict}），故执行段重复 进入同一意向只会被具名拒绝，不会造出第二份权威。
 *
 * <p>★★ <b>可行性口径（逐条）</b>：
 *
 * <ul>
 *   <li><b>需求</b>：本户所在格有 {@link HouseholdDemand#effectiveOn(long)} 的有效需求命中<b>任一产出商品</b>，且剩余窗口 ≥
 *       {@code buildDays + cycleDays}（{@code expiresDay < 0} = 永久）；
 *   <li><b>幂等</b>：本户不得已有同 {@code modeKey} 或<b>产出商品集相交</b>的 ACTIVE/TRIALING unit；同一 {@code
 *       (household, plan)} 因此只建一次；
 *   <li><b>资产</b>：{@code capacityPerUnit} 为空或全 0 ⇒ 不可行（本片没有"无资产也能产"的路）；自有可用份额只认 <b>已登记在该格物化定义 id
 *       下</b>、权利性质 ∈ {@code acceptedRightKinds} 的份额；不足部分从 {@code EconomyMeta.planAssetSources}
 *       点名的来源主体名下<b>空闲</b>（{@code operator==owner}）、同格的份额拆分： 原份额只减数量（减到 0 则删行），新份额 owner
 *       不变、operator = 家户、kind 按 {@code acceptedRightKinds} 选， 并登记在该格物化定义 id 下（否则 {@code
 *       ProductionProcessBook} 的 {@code (industry,operator)} 作用域取不到它）。 <b>不新增总量、不删 owner</b>；
 *   <li><b>劳动</b>：家户折算后可用劳动（{@code participationAdjustedLaborMilli} − 已分配）与所选人口批次余量（{@code
 *       availableLabor} − 已分配）都 ≥ {@code laborPerUnit × trialScale}；批次按成员份额选（没有成员份额的旧夹具退回该户既有配额
 *       的批次），并列取余量大者、再按批次 id 升序；
 *   <li><b>投入/生计</b>：本户库存要覆盖 {@code inputPerUnit × trialScale × (buildDays+cycleDays)}，并额外留出同窗口 + 1
 *       天的 基本口粮（按 {@link HouseholdEconomy#expectedNeedMilli} 的当前注入需求 × 原窗口 {horizon+1} 天，
 *       不另写"每人每天"）；
 *   <li><b>收益/成本只用可观察价</b>：<b>每个产出商品</b>、任一正投入、以及给养要用的粮，<b>缺价一律具名拒绝</b>（{@code
 *       PRICE_MISSING:...}）—— "缺价按 0 计"会把无价成本当成免费，不是保守口径；自留口粮成本按 {@link
 *       RegimeRelations#subsistenceMilliPerLabor()}（与 {@code ProducerCostBook} 同源）；score &lt; 0 ⇒
 *       不可行。
 * </ul>
 *
 * <p>★★ <b>规模</b>：{@code trialScale = min(配置上限, probe.scale())} —— probe 已把"本户可用劳动（周期累计）/ 资产占用 /
 * 投入"三通道夹进 {@code probe.scale()}，economy 不再对同一约束夹第二次；{@code probe.scale() == 0} ⇒ 不可行。配置上限 = {@link
 * #MAX_TRIAL_SCALE_PER_ENTRY}（V 阶段接 GM 参数目录后迁出）。
 *
 * <p>★★ <b>投入表怎么表达进 IndustryDefinitionView</b>：物化定义沿用方案自己的 {@code cycleInputPerUnit} （现扣/收获读 {@code
 * IndustryDefinitionView.inputPerUnit()} = 各内层表之和 / {@code recipe()}，没有路径按某个 asset 分支直接读它）。
 *
 * <p>★ <b>进入触发日</b>：每个日结算日都评估（只要 demands 与方案池非空）；<b>已能维生的旧 unit 一律不动</b>，没有全局 ROI 切换。 {@code
 * buildDays} 本片只进"需求剩余窗口"与"投入/口粮覆盖窗口"两个门槛，<b>不推迟结算进度</b>（首个关账日 = 进入日 + {@code cycleDays} − 1）；这一差异在
 * E2b 报告里如实记。
 */
final class EconomyEntrySettlement {

  /**
   * ★ <b>单次进入的试产规模配置上限（单位规模）</b>：只与 {@code probe.scale()} 取小（劳动/资产/投入三通道 已由 probe 夹过）；{@code
   * probe.scale() == 0} ⇒ 不可行。
   *
   * <p>★ 它是本片新增的唯一"配置值"；V 阶段接 GM 参数目录后从这里迁出，评估逻辑不改。取 10 = 现有主 unit 规模（数百～数千）的一个小比例。
   */
  static final long MAX_TRIAL_SCALE_PER_ENTRY = 10L;

  private EconomyEntrySettlement() {}

  /** 一条资产拆分腿：从 {@code sourceShare} 拆出 {@code quantity} 给新 unit（新份额 owner 不变、operator=家户）。 */
  record AssetGrant(AssetShareId sourceShare, AssetKind asset, long quantity, AssetRightKind kind) {

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
   * 一条"可以采用"的意向（plan 产出；execute 重新评估后使用）。字段口径见类注；{@code grants} 只含<b>需要从来源主体拆</b>
   * 的腿（自有份额已够的部分不在其中），{@code inputPlan} = 一周期投入（已乘 trialScale），{@code horizonNeed} = 覆盖窗口投入。
   *
   * <p>★★ B2c：{@code plan} = 被采用的<b>方案定义</b>（不绑地点；{@code modeKey} 就是它的 id 字面）， {@code
   * planDefinition} = 本格<b>物化定义</b>（已存在则原样沿用，否则按方案逐字段复制成 {@code <planId>@<q>_<r>}）。
   */
  record EntryIntent(
      HouseholdId household,
      ActorRef householdActor,
      IndustryDefinitionView plan,
      IndustryId industryId,
      IndustryDefinitionView planDefinition,
      io.mosire.simos.production.contract.ProductionLocation location,
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
      Objects.requireNonNull(plan, "EntryIntent.plan 不得为 null");
      Objects.requireNonNull(industryId, "EntryIntent.industryId 不得为 null");
      Objects.requireNonNull(planDefinition, "EntryIntent.planDefinition 不得为 null");
      Objects.requireNonNull(location, "EntryIntent.location 不得为 null（实例必须有地点）");
      if (!location.isSpecified()) {
        throw new IllegalArgumentException(
            "EntryIntent.location 不得是 UNSPECIFIED 占位（进入动作必须拿到真实格）: " + location.value());
      }
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
   * ★★ <b>方案池</b>（裁定八）：{@code hexKeyOf(id)} 为空的<b>不绑地点</b>定义才是"可以被采用的方案"；带格键的定义 （{@code farm@0_0}
   * 一类，播种产物）是"某格已物化好的定义"，只由 {@link #planDefinitionFor} 沿用，不进本池。
   *
   * <p>★ 按 id 值升序返回（评估序是内容的纯函数）；★ 空池 = 今天没有任何可采用的方案 ⇒ 调用方连评估都不做。
   */
  static Map<IndustryId, IndustryDefinitionView> plans(
      Map<IndustryId, IndustryDefinitionView> industries) {
    Objects.requireNonNull(industries, "industries");
    List<IndustryId> ids = new ArrayList<>();
    for (IndustryId id : industries.keySet()) {
      if (io.mosire.simos.economy.api.id.IndustryHexKeys.hexKeyOf(id).isEmpty()) {
        ids.add(id);
      }
    }
    ids.sort(Comparator.comparing(IndustryId::value));
    Map<IndustryId, IndustryDefinitionView> out = new LinkedHashMap<>();
    for (IndustryId id : ids) {
      out.put(id, industries.get(id));
    }
    return out;
  }

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

  /**
   * ★ <b>执行段（真写）</b>：在日结算的"现扣投入/劳动再分配"之前调用；对每条意向以**当前工作副本**重新评估，通过后才按 {@link WriteMode#REAL}
   * 落笔：production 的 {@code registerPlan}（本格物化定义，已存在则幂等 no-op）→ {@code createInstance}（实例；同 id 已存在 =
   * 具名 {@code unit-conflict}，不覆盖）→ 实例引用/构造守卫，然后写工作副本的 relation → condition(TRIALING) → 份额拆分 →
   * 劳动配额。任一步失败 ⇒ <b>当条</b>拒绝，不半建（已成功的条不回滚）。
   *
   * <p>★ 传入的 map 都是工作副本（由调用方持有并最终交出）；本方法**就地更新**，不另建第二份状态。★ 定义与实例<b>不</b>写进 工作副本的 {@code
   * industries}/{@code units} —— 它们的唯一权威是 production（本方法经 {@code runtime} 真写）， economy
   * 本地表写它们只会造出"第二份可写权威"。
   */
  static Execution execute(
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      List<EntryIntent> intents,
      Map<DemandId, HouseholdDemand> householdDemands,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<IndustryId, ActorRef> planAssetSources,
      Map<IndustryId, LaborSource> planLaborSources,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      MarketIndex markets,
      long day,
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    Objects.requireNonNull(runtime, "runtime（D-032：试算走 production 契约，不接受 null）");
    Objects.requireNonNull(intents, "intents");
    Objects.requireNonNull(planAssetSources, "planAssetSources（缺省给空表 = 只认自有份额）");
    Objects.requireNonNull(planLaborSources, "planLaborSources（缺省给空表 = 全部 SELF）");
    if (intents.isEmpty()) {
      return new Execution(false, Set.of(), List.of());
    }
    Objects.requireNonNull(productionRosters, "productionRosters（C2：名册工作副本）");
    Tables tables =
        new Tables(
            industries,
            units,
            assetShares,
            laborCommitments,
            operatorConditions,
            relations,
            productionRosters);
    EntryContext context =
        new EntryContext(
            runtime,
            tables,
            householdDemands,
            householdEconomies,
            householdGoods,
            composition,
            markets,
            planAssetSources,
            planLaborSources,
            day);
    boolean changed = false;
    Set<ProductionUnitId> entered = new LinkedHashSet<>();
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (EntryIntent planned : intents) {
      Attempt attempt = assess(planned.household(), planned.plan(), context);
      if (attempt.intent() == null) {
        outcomes.add(
            attempt.rejection() != null
                ? attempt.rejection()
                : reject(context, planned.household(), planned.plan(), "REVALIDATION_NO_SIGNAL"));
        continue;
      }
      EntryIntent fresh = attempt.intent();
      // ★★ F5（R6）：执行段真写 —— registerPlan/createInstance/守卫 + 工作副本就地更新。
      Optional<String> failure = applyIntent(fresh, context, WriteMode.REAL);
      if (failure.isPresent()) {
        outcomes.add(reject(context, fresh.household(), fresh.plan(), failure.get()));
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
   * 对一对 (家户, 方案) 做完整可行性评估（只读；不改任何表）。返回 {@link Attempt#skip()} = 该户没有命中方案任一产出商品的 有效需求 （不产出读口行， 避免
   * 6000×方案条无意义的 NO_DEMAND）。
   */
  private static Attempt assess(
      HouseholdId household, IndustryDefinitionView plan, EntryContext context) {
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
      if (AssetBudgetBook.hasUnitInScope(context.tables.units, industryId, actor)) {
        return Attempt.rejection(
            reject(context, household, plan, AssetBudgetBook.SHARED_SCOPE_REJECTION));
      }
      ProductionUnitId unitId = ProductionUnitId.idOf(industryId, actor);
      if (context.tables.units.containsKey(unitId)) {
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
          OwnershipStake share = context.tables.assetShares.get(shareId);
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
   * ★★★ <b>方案定义 → 本格当刻有效定义</b>（§13.10.3 裁定五；B2c 按裁定八改成"按方案逐字段物化"）。
   *
   * <p>★★ <b>为什么要有"物化"这一步</b>：{@link #plans(Map)} 里的方案 id <b>不绑地点</b>，而实例引用的 {@link IndustryId}
   * 必须与资产份额的 {@code industry} 键（{@code <kind>@<q>_<r>}，读路径按格解析它）一致 —— 故本格定义 id 唯一拼写点是 {@link
   * IndustryHexKeys#id(String, int, int)}，并由 {@link
   * io.mosire.simos.production.contract.ProductionRuntime#registerPlan} 决定接不接受（合并/冲突规则在
   * production，见 {@code DefinitionRegistration}）。
   *
   * <p>★ <b>已存在的定义原样沿用</b>（不覆盖、不重写）；不存在则把方案的字段<b>逐值复制</b>过去、只换 id。 ★
   * 物化是**纯复制**：没有"第二份算式"、没有手写常量（旧候选口径的那套 slots/Split(500,500)/MoneyOutput.NONE 手工合成已随候选面删除）。
   */
  private static IndustryDefinitionView planDefinitionFor(
      EntryContext context, IndustryDefinitionView plan, IndustryId industryId) {
    Optional<IndustryDefinitionView> existing = context.runtime.definitions().industry(industryId);
    if (existing.isPresent()) {
      return existing.get();
    }
    return new IndustryDefinitionView(
        industryId,
        plan.name(),
        plan.regime(),
        plan.skill(),
        plan.cycleDays(),
        plan.buildDays(),
        plan.capacityPerUnit(),
        plan.dailyInputPerUnit(),
        plan.dailyLaborPerUnit(),
        plan.laborPerUnit(),
        plan.outputPerUnit(),
        plan.cycleInputPerUnit(),
        plan.slots(),
        plan.allocation(),
        plan.moneyOutputPerUnit(),
        plan.experience(),
        plan.availability(),
        plan.acceptedRightKinds());
  }

  /**
   * ★★ <b>B2c：方案的产出商品集</b>（裁定八：产出按商品逐项处理）——{@code outputPerUnit} 里<b>逐值 &gt; 0</b> 的那些键，按 id
   * 值升序（评估序是内容的纯函数）。
   *
   * <p>★ 为什么只认正数项：{@code outputPerUnit} 允许 0 值行（"这一项不产"），把它当成"产出商品"会让需求匹配与 取价凭空多出一个不产出的商品。★ 空集 =
   * 本方案不产任何东西 ⇒ 需求信号恒 NONE（该对被跳过，不具名拒绝）。
   */
  private static List<CommodityId> outputCommodities(IndustryDefinitionView plan) {
    List<CommodityId> out = new ArrayList<>();
    for (Map.Entry<CommodityId, Long> entry : plan.outputPerUnit().entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        out.add(entry.getKey());
      }
    }
    out.sort(Comparator.comparing(CommodityId::value));
    return out;
  }

  /**
   * ★★★ <b>对本条 (家户, 候选) 做一次无副作用试算</b>（§13.10.4 第 1 条；I5/V1/V2）。
   *
   * <p>★★ <b>入参只给不可变值</b>（§4.2）：家户条件（可用劳动 / 资产 / 原料 / 相关经验条的熟练度与经验）+ 外部属性事实。★ <b>价格与需求不进这里</b> ——
   * 那两件是 economy 的打分与排序（本方法一个价都不读）。
   *
   * <p>★★ <b>D-032 §15.6（R6）两个口径（本方法只在这里拼装）</b>：
   *
   * <ul>
   *   <li><b>劳动 = 本周期累计可用劳动</b> = {@link HouseholdEconomy#laborMilli()}（每 tick 的时间预算）× {@code
   *       plan.cycleDays()}。{@code probe} 内部按 {@code ProductionEfficiency} 的 ① <b>÷cycleDays</b>
   *       折回日均 ⇒ 传每日值会把规模低估 {@code cycleDays} 倍（R6 的 C/D 对照）。溢出走 {@code Math.multiplyExact} ⇒ 调用方
   *       {@code ARITHMETIC_OVERFLOW} 具名拒绝（fail-closed）。
   *   <li><b>资产占用 = 候选将要实占的资产</b> = 自有份额 ∪ 外部允许来源（{@code assetOccupancy} 由调用方按此口径 合并好传入；{@link
   *       io.mosire.simos.production.contract.ProductionProbeRequest} 的契约是"0 不进表"）。 旧口径给空表 ⇒ {@code
   *       probe.capacityScaleOf} 显式"空占用 ⇒ 0" ⇒ 一切候选必被拒（R6）。
   * </ul>
   *
   * <p>★ 熟练度与经验按方案点名的<b>两条经验条</b>（专门 a + 可共用 b）逐条读 {@link
   * io.mosire.simos.production.contract.ProductionRuntime#masteryOf} / {@code
   * experienceOf}（经验账的只读读口；缺 ⇒ 0 是真实世界状态，不是配置错误）。
   *
   * <p>★★ <b>D-032 §15.11（E1-A2）：{@code probePlanId} 与 {@code plan} 的分工</b> —— {@code probe} 按
   * {@code request.plan()} 查 production 的**当刻已登记定义**；本格物化 id 在 {@link #planDefinitionFor} 里
   * 只合成、不登记（登记只发生在 execute→applyIntent 的 {@link WriteMode#REAL}，assess/planEntries 必须只读）⇒
   * 未物化时调用方改传方案池里的 **unbound 方案 id**（物化是纯复制、两定义逐值相同），否则首次采用会被 {@code plan-unknown} 拒。{@code plan}
   * 仍用于读入参（经验条 / 投入键 / {@code cycleDays}）。
   */
  private static io.mosire.simos.production.contract.ProductionProbeResult probeEntry(
      EntryContext context,
      HouseholdId household,
      IndustryId probePlanId,
      Map<AssetKind, Long> ownAssets,
      Map<AssetKind, Long> assetOccupancy,
      IndustryDefinitionView plan) {
    Map<ExperienceTrackId, Long> mastery = new LinkedHashMap<>();
    Map<ExperienceTrackId, Long> experience = new LinkedHashMap<>();
    for (Optional<ExperienceTrackId> track :
        List.of(plan.experience().primaryTrack(), plan.experience().sharedTrack())) {
      if (track.isEmpty()) {
        continue;
      }
      mastery.put(track.get(), context.runtime.masteryOf(household, track.get()));
      experience.put(track.get(), context.runtime.experienceOf(household, track.get()));
    }
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    for (CommodityId commodity : plan.inputPerUnit().keySet()) {
      inputs.put(commodity, stockOf(context.householdGoods, household, commodity));
    }
    HouseholdEconomy householdEconomy = context.householdEconomies.get(household);
    // ★★ D-032 §15.6（R6）：`probe` 的 `laborMilli` 是**本周期累计**劳动（内部 ÷cycleDays），
    //   而 `HouseholdEconomy.laborMilli()` 是**每 tick（日）**时间预算 ⇒ 必须乘 cycleDays。
    long laborPeriodMilli = Math.multiplyExact(householdEconomy.laborMilli(), plan.cycleDays());
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            household, laborPeriodMilli, ownAssets, inputs, mastery, experience);
    return context.runtime.probe(
        new io.mosire.simos.production.contract.ProductionProbeRequest(
            probePlanId,
            conditions,
            assetOccupancy,
            Optional.empty(),
            io.mosire.simos.production.contract.ExternalAttributeFacts.EMPTY));
  }

  /**
   * ★ <b>把 `probe` 的读数按规模线性换算到实际采用的规模</b>（{@code probeValue × scale ÷ probeScale}）。
   *
   * <p>★ 为什么是线性：`probe` 的三件需求（劳动/资产/原料）在**基准规模**上是线性量（§13.8 裁定一： {@code baseScale × laborPerUnit}
   * 一类），而产出在 {@code finalScale} 上也是线性的 —— 效率倍率只把 整个基准规模乘一次，不是逐单位变化的函数。⇒ 缩小规模不改变"每单位要多少"，只改变总量。
   *
   * <p>★ 除零与 {@code probeScale == 0} 的退化：那表示"可行、但一个规模单位都做不了"（调用方在此之前已拒）， 这里给 0（不猜、不放大）。
   */
  private static long scaleLinearly(long probeValue, long scale, long probeScale) {
    if (probeValue <= 0L || scale <= 0L || probeScale <= 0L) {
      return 0L;
    }
    return Math.multiplyExact(probeValue, scale) / probeScale;
  }

  /**
   * 本户所在格的需求信号（HEX 命中本格 / HOUSEHOLD 命中本户；窗口 ≥ horizon 或永久）。
   *
   * <p>★★ B2c（裁定八）：命中面 = 方案<b>任一产出商品</b>（"主产出"概念已删）；{@code outputs} 为空则恒 {@link
   * DemandSignal#NONE}（该对被跳过）。单产出方案与旧口径逐值相同。
   */
  private static DemandSignal demandSignal(
      HouseholdId household,
      List<CommodityId> outputs,
      HexCoord hex,
      long horizon,
      EntryContext context) {
    if (outputs.isEmpty()) {
      return DemandSignal.NONE;
    }
    boolean sawAny = false;
    for (HouseholdDemand demand : context.householdDemands.values()) {
      if (!outputs.contains(demand.commodity()) || !demand.effectiveOn(context.day)) {
        continue;
      }
      if (demand.scope() == HouseholdDemand.DemandScope.HOUSEHOLD) {
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

  /**
   * 幂等：本户已有同 modeKey 或<b>产出商品集相交</b>的 ACTIVE/TRIALING unit（condition 缺失按 ACTIVE 口径， 见
   * StressPolicy）。
   *
   * <p>★★ B2c（裁定八）：{@code modeKey} 现在是<b>方案 id 字面</b>（版本键已删）；"同主产出"的逐商品推广 = 产出商品集<b>相交</b>（单产出方案退化成
   * {@code containsKey} 的旧判据，逐值相同）。
   */
  private static boolean hasActiveDuplicate(
      ActorRef actor, List<CommodityId> outputs, String modeKey, EntryContext context) {
    for (ProductionUnitId unitId : context.unitIdsByOperator.getOrDefault(actor, List.of())) {
      ProductionProcess unit = context.tables.units.get(unitId);
      if (unit == null) {
        continue;
      }
      OperatorCondition condition = context.tables.operatorConditions.get(unitId);
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
  private static Map<AssetKind, Long> ownAssets(
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
            context.tables.assetShares.get(shareId),
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
            context.tables.assetShares.get(shareId),
            industryId,
            acceptedKinds,
            locationKey,
            actor);
      }
    }
    return sums;
  }

  private static void mergeOwn(
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
  private static Map<AssetKind, Long> occupiedAssets(
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
  private static Map<AssetKind, Long> sourceAssets(
      ActorRef assetSource, HexCoord hex, Set<AssetKind> capacityKinds, EntryContext context) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(assetSource, List.of())) {
      OwnershipStake share = context.tables.assetShares.get(shareId);
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
  private static List<AssetShareId> sourceShareIds(
      ActorRef assetSource, HexCoord hex, AssetKind asset, EntryContext context) {
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<AssetShareId> ids = new ArrayList<>();
    for (AssetShareId shareId : context.shareIdsByOwner.getOrDefault(assetSource, List.of())) {
      OwnershipStake share = context.tables.assetShares.get(shareId);
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
  private static AssetRightKind chooseGrantKind(Set<AssetRightKind> acceptedKinds) {
    for (AssetRightKind kind :
        List.of(AssetRightKind.TENANCY, AssetRightKind.COMMUNAL, AssetRightKind.OWNED)) {
      if (acceptedKinds.contains(kind)) {
        return kind;
      }
    }
    throw new IllegalStateException(
        "acceptedRightKinds 非空但没有可用的 TENANCY/COMMUNAL/OWNED: " + acceptedKinds);
  }

  // ── 落笔：意向的执行（真写）与模拟（只写副本表） ──────────────────────────────────────

  /** ★★★ <b>意向落笔的显式写模式</b>（F5 / R6）：副作用面不靠"是哪个调用点"来约定 —— 调用点必须显式点名，意向段与执行段 因此不可能再共用一条会真写的路径。 */
  private enum WriteMode {

    /** {@link #planEntries}：只写本方法的私有副本表 {@code context.tables}，production runtime 一字不动。 */
    SIMULATED_ONLY,

    /** {@link #execute}：真写 production（registerPlan/createInstance/实例守卫）并就地更新工作副本。 */
    REAL
  }

  /**
   * ★★★ <b>把一条意向落笔 —— 两种显式写模式（见 {@link WriteMode}），前置校验段两模式共用</b>。
   *
   * <p>先做<b>全量前置校验</b>（GRANT_SOURCE_*、UNIT_ID_EXISTS、ALLOCATION_ID_EXISTS、
   * LABOR_HOUSEHOLD_ROOM_GONE），任一失败 ⇒ 返回具名原因且<b>一个字不改</b>（production 与副本表都不动）。 校验全过后按模式分叉：
   *
   * <ul>
   *   <li>{@link WriteMode#SIMULATED_ONLY}（{@link #planEntries} 用）：<b>不碰 production 的任何写口</b> —— 不调
   *       {@code registerPlan}/{@code createInstance}/{@code validateProductionInstances}；新实例
   *       <b>只</b>写进 {@code context.tables.units}（进度 0、无周期贡献/投入），使同批次后续 {@code assess} 的 {@code
   *       UNIT_ID_EXISTS} 与 {@code hasActiveDuplicate} 能在副本表看见它；relation/condition/份额/劳动配额 同样只写副本表；
   *   <li>{@link WriteMode#REAL}（{@link #execute} 用）：{@code registerPlan}（同 id 同值 = 幂等 no-op）→
   *       {@code createInstance}（同 id 实例已存在 = 具名 {@code unit-conflict}）→ 实例引用/构造守卫，然后写工作副本；
   *       <b>不</b>把定义/实例写进工作副本的 {@code industries}/{@code units}（唯一权威在 production）。
   * </ul>
   *
   * <p>★ 两模式在"写工作副本"这一段<b>逐值相同</b>：relation / condition(TRIALING) / 份额拆分 / 劳动配额 / 派生账本 （{@code
   * unitIdsByOperator}、{@code allocationMilliByHousehold}、{@code allocationMilliByGroup}）。 返回
   * {@link Optional#empty()} = 成功（真实写入或模拟登记完成）。
   *
   * <p>★★ <b>E5a 如实边界</b>：本方法里的份额写是<b>跨产业重新登记</b>（把来源主体名下的空闲份额改挂到方案的新 industryId，
   * 再交给本户经营），不是纯所有权/经营权转移 ⇒ 不委托 {@link OwnershipStakeBook}（Book 的守恒式是逐 {@code (industry, asset)}
   * 的，改产业不在它语义内）。这是记为遗留的显式例外，不是新增写路径；E5b 清算的转移/拆分只走 Book。
   */
  private static Optional<String> applyIntent(
      EntryIntent intent, EntryContext context, WriteMode mode) {
    Tables tables = context.tables;
    for (AssetGrant grant : intent.grants()) {
      OwnershipStake source = tables.assetShares.get(grant.sourceShare());
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
    // ★★★ D-032 D2 §5.4 / I16：真实执行时的名册前置判定（模拟期不落名册，只证明可以建）。
    Optional<ProductionRoster> defaultRoster = Optional.empty();
    if (mode == WriteMode.REAL) {
      List<io.mosire.simos.production.contract.ProductionRoleDefinition> roles =
          intent.plan().roles();
      if (roles.size() > 1 && !tables.productionRosters.containsKey(unitId)) {
        return Optional.of("roster-required:" + unitId.value());
      }
      if (roles.size() == 1) {
        HouseholdEconomy row = context.householdEconomies.get(intent.household());
        long remaining =
            row == null
                ? 0L
                : Math.max(
                    0L,
                    row.laborMilli()
                        - ProductionRosters.plannedLaborOf(
                            tables.productionRosters, intent.household()));
        defaultRoster =
            ProductionRosters.defaultSingleHouseholdRoster(
                unitId, intent.plan(), intent.household(), remaining);
        if (defaultRoster.isEmpty()) {
          return Optional.of("roster-required:" + unitId.value());
        }
      }
    }
    LaborAllocationId allocationId = null;
    io.mosire.simos.economy.api.production.ProductionRoleId allocationRole =
        intent.plan().roles().size() == 1 ? intent.plan().roles().get(0).id() : null;
    if (intent.laborMilli() > 0L && allocationRole != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      allocationId =
          HouseholdLaborCommitment.idOfProduction(unitId, allocationRole, lot, intent.household());
      if (tables.laborCommitments.containsKey(allocationId)) {
        return Optional.of("ALLOCATION_ID_EXISTS:" + allocationId.value());
      }
      HouseholdEconomy roomHouseholdEconomy = context.householdEconomies.get(intent.household());
      long room =
          roomHouseholdEconomy == null
              ? 0L
              : roomHouseholdEconomy.laborMilli()
                  - context.allocationMilliByHousehold.getOrDefault(intent.household(), 0L);
      if (room < intent.laborMilli()) {
        return Optional.of(
            "LABOR_HOUSEHOLD_ROOM_GONE:household="
                + intent.household().value()
                + ",room="
                + room
                + ",need="
                + intent.laborMilli());
      }
    }

    // ── 校验全过：开始落笔（同一批内不再有可失败的依赖）。
    if (mode == WriteMode.SIMULATED_ONLY) {
      // ★★ F5（R6）：意向段的**只读模拟** —— 实例只写私有副本表（同批次 assess 的 UNIT_ID_EXISTS /
      //   hasActiveDuplicate 因此看得见它）；production 的 registerPlan/createInstance/守卫一个都不调
      //   ⇒ 真实定义/实例/changeSet 零变化。副本表在 planEntries 返回后即丢。
      tables.units.put(
          unitId,
          new ProductionProcess(
              unitId,
              intent.industryId(),
              intent.location(),
              intent.householdActor(),
              intent.plan().id().value(),
              0L,
              Map.of(),
              Map.of()));
    } else {
      // ★★★ B2（§13.10.3 裁定五）：execute 的**真写** —— 方案定义与实例都经 production 的契约立起来：
      //   ① `registerPlan(定义)`：方案数值的唯一权威在 production（合并/冲突规则也在它那边）；
      //   ② `createInstance(请求)`：实例的唯一权威同样在 production。
      //   ★ 两件事都**幂等**（同 id 同值 = no-op；同 id 的实例已存在 = 具名拒 unit-conflict），
      //     故 revalidation 重复走到这里不会造出第二份权威。
      //   ★ economy 侧在**真写模式**下也**不**把定义/实例写进工作副本 —— `EconomyStateBuilder.industries()` /
      //     `units()` 直接读 production 的当刻候选态，写副本反而会造出"第二份可写权威"（本批明禁）。
      var planOutcome = context.runtime.registerPlan(intent.planDefinition());
      if (planOutcome
          instanceof io.mosire.simos.production.contract.ProductionPlanOutcome.Rejected rejected) {
        return Optional.of("PLAN_REGISTER_REJECTED:" + rejected.reason());
      }
      var instanceOutcome =
          context.runtime.createInstance(
              new io.mosire.simos.production.contract.ProductionCreationRequest(
                  intent.industryId(),
                  unitId,
                  intent.location(),
                  intent.householdActor(),
                  // ★★ B2c（裁定八）：modeKey = 方案 id 字面（版本键已删）—— 经验条/解锁因此围绕**方案稳定身份**
                  //   记账（换 hex 不重学同一门技术）。
                  intent.plan().id().value()));
      if (instanceOutcome
          instanceof
          io.mosire.simos.production.contract.ProductionInstanceOutcome.Rejected rejected) {
        return Optional.of("INSTANCE_CREATE_REJECTED:" + rejected.reason());
      }
      // ★★ B2（§13.10.4 第 4 条 / U7）：建档后**当场**用同一份只读投影复核"实例引用/构造守卫"
      //   （`EconomyReferenceValidation.validateProductionInstances`）—— 判据与旧 `EconomyData` 的 units
      //   构造期守卫同义（键==行内 id / 方案存在 / 进度闭区间 / 经营者非空 / 地点不是 UNSPECIFIED 占位），
      //   读的却是 production.contract 的**当刻候选态**（I13），不是我方本地表。★ 守卫一条没删，只换了读哪一份。
      io.mosire.simos.economy.EconomyReferenceValidation.validateProductionInstances(
          context.runtime, "EconomyEntrySettlement.applyIntent");
    }
    tables.relations.put(
        unitId,
        new ProductionRules(
            unitId,
            intent.householdActor(),
            new Payee.ToHousehold(intent.household()),
            List.of(),
            intent.householdActor(),
            // ★★ B3（§13.13.3 裁定十二）：**逐值不变** —— 劳动来源取 economy 的**方案声明**
            //   （`EconomyMeta.planLaborSources`，键 = 不绑地点的方案 id；缺键 ⇒ SELF）。
            //   ★ 不得改回 `RegimeRelations.laborSourceFor(regime)`：那是"按制度派生"的**行为变更**
            //     （非 SELF 制度下与旧候选缺省 SELF 不同），裁定要把那条另立项 + 数值验收，不在本批。
            EconomyMeta.laborSourceOf(context.planLaborSources, intent.plan().id())));
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
            Map.of(), // ★★ P-T4：上一周期收入按币分列（新入场 ⇒ 空表）
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            "entry:" + intent.plan().id().value(),
            0L,
            0L,
            0L,
            Map.of(), // ★★ P-T4：本周期累计货款按币分列（新入场 ⇒ 空表）
            0L,
            0L,
            0L,
            0L));
    for (AssetGrant grant : intent.grants()) {
      OwnershipStake source = tables.assetShares.get(grant.sourceShare());
      long left = source.quantity() - grant.quantity();
      if (left == 0L) {
        tables.assetShares.remove(grant.sourceShare());
      } else {
        tables.assetShares.put(
            source.id(),
            new OwnershipStake(
                source.id(),
                source.industry(),
                source.asset(),
                source.owner(),
                source.operator(),
                left,
                source.kind(),
                source.location()));
      }
      AssetShareId newId =
          nextShareId(
              tables,
              intent.industryId(),
              grant.asset(),
              source.owner(),
              intent.householdActor(),
              grant.kind(),
              intent.location());
      tables.assetShares.put(
          newId,
          new OwnershipStake(
              newId,
              intent.industryId(),
              grant.asset(),
              source.owner(),
              intent.householdActor(),
              grant.quantity(),
              grant.kind(),
              intent.location()));
    }
    if (allocationId != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      tables.laborCommitments.put(
          allocationId,
          new HouseholdLaborCommitment(
              allocationId,
              lot,
              intent.household(),
              intent.householdActor(),
              unitId.value(),
              intent.laborMilli(),
              intent.laborPeriod(),
              io.mosire.simos.economy.api.labor.LaborCommitmentKind.PRODUCTION,
              Optional.of(allocationRole)));
    }
    defaultRoster.ifPresent(roster -> tables.productionRosters.put(unitId, roster));
    // 派生账本同步（供同批次后续评估看见本条的占用）。
    context
        .unitIdsByOperator
        .computeIfAbsent(intent.householdActor(), ignored -> new ArrayList<>())
        .add(unitId);
    if (allocationId != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      context.allocationMilliByHousehold.merge(intent.household(), intent.laborMilli(), Long::sum);
      context.allocationMilliByGroup.merge(lot, intent.laborMilli(), Long::sum);
    }
    return Optional.empty();
  }

  /**
   * ★★ <b>E5a 如实边界：本方法仍是份额表的直接写入点，不委托 {@link OwnershipStakeBook}</b>。理由：进入动作要把来源主体
   * 名下的空闲份额<b>改登记到本格物化定义</b>（{@code intent.industryId()}）后交给本户经营，这样新 unit 才 通过 {@code
   * ProductionProcessBook.usableAssets} 的 {@code industry==unit.industry} 判据看见它；而 Book 的守恒式是 逐
   * {@code (industry, asset)} 的，跨产业的重新登记不在它的语义内。这是记为遗留的显式例外，不是新增写路径； E5b 清算新增的转移/拆分一律只走 {@link
   * OwnershipStakeBook}。
   */
  private static AssetShareId nextShareId(
      Tables tables,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      ProductionLocation location) {
    for (long sequence = 0L; ; sequence++) {
      AssetShareId candidate =
          OwnershipStake.idOf(industry, asset, owner, operator, kind, location, sequence);
      if (!tables.assetShares.containsKey(candidate)) {
        return candidate;
      }
    }
  }

  // ── 读数与工具 ───────────────────────────────────────────────────────────────────────

  /** 计划期/执行期的具名拒绝行（trialScale/expectedDay/laborMilli 全 0；没有可行规模可报）。 */
  private static EntryOutcome reject(
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
  private static EntryOutcome accepted(EntryContext context, EntryIntent intent) {
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

  /** 工作副本组（plan 用浅拷贝、只收本批模拟写且返回即丢；execute 用会话真副本）。 */
  private record Tables(
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRules> relations,
      // ★★★ D-032 C2：名册工作副本（真实执行时写默认名册；模拟期用空表，不落任何状态）。
      Map<ProductionUnitId, ProductionRoster> productionRosters) {

    Tables {
      Objects.requireNonNull(industries, "Tables.industries 不得为 null");
      Objects.requireNonNull(units, "Tables.units 不得为 null");
      Objects.requireNonNull(assetShares, "Tables.assetShares 不得为 null");
      Objects.requireNonNull(laborCommitments, "Tables.allocations 不得为 null");
      Objects.requireNonNull(operatorConditions, "Tables.operatorConditions 不得为 null");
      Objects.requireNonNull(relations, "Tables.relations 不得为 null");
      Objects.requireNonNull(productionRosters, "Tables.productionRosters 不得为 null");
    }
  }

  /**
   * 一次评估内的派生索引（入口建一次，避免每个 (户,方案) 都全表扫）：unit↔operator、成员批次、配额和（家户/批次）、 份额↔owner。评估阶段只读它们；落笔（{@link
   * WriteMode#SIMULATED_ONLY} 或 {@link WriteMode#REAL}）后只增量更新 unit↔operator 与两个配额和 ——
   * 模拟模式只动副本表、真写模式只动工作副本。
   */
  private static final class EntryContext {

    /**
     * ★★ <b>production 计算契约（D-032 §4.2）</b>：试算与"按需登记方案定义"都经它 —— economy 不再自己算 可行性/规模/需求（那是第二份算式，与
     * production 的实现必然漂开），只做价格、需求与分配。
     */
    private final io.mosire.simos.production.contract.ProductionRuntime runtime;

    private final Tables tables;
    private final Map<DemandId, HouseholdDemand> householdDemands;
    private final Map<HouseholdId, HouseholdEconomy> householdEconomies;
    private final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
    private final MarketIndex markets;
    private final long day;
    private final Map<ActorRef, List<ProductionUnitId>> unitIdsByOperator = new LinkedHashMap<>();
    private final Map<HouseholdId, List<PeopleLotId>> lotsByHousehold = new LinkedHashMap<>();
    private final Map<HouseholdId, Long> allocationMilliByHousehold = new LinkedHashMap<>();
    private final Map<PeopleLotId, Long> allocationMilliByGroup = new LinkedHashMap<>();
    private final Map<ActorRef, List<AssetShareId>> shareIdsByOwner = new LinkedHashMap<>();
    private final Map<ActorRef, List<AssetShareId>> shareIdsByOperator = new LinkedHashMap<>();

    /**
     * ★★ <b>B2c（裁定八）：闲置份额来源主体的 economy 声明面</b> —— 键 = 方案 id，值 = 该方案可取用闲置份额的主体。 缺键 = 只认自有份额（= 旧候选缺省
     * {@code assetSource} 的逐值行为）。
     */
    private final Map<IndustryId, ActorRef> planAssetSources;

    /** ★★ B3（裁定十二）：按方案声明的**劳动来源**（缺键 ⇒ SELF；见 {@link EconomyMeta#laborSourceOf}）。 */
    private final Map<IndustryId, LaborSource> planLaborSources;

    private EntryContext(
        io.mosire.simos.production.contract.ProductionRuntime runtime,
        Tables tables,
        Map<DemandId, HouseholdDemand> householdDemands,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<PeopleLotId, Long>> composition,
        MarketIndex markets,
        Map<IndustryId, ActorRef> planAssetSources,
        Map<IndustryId, LaborSource> planLaborSources,
        long day) {
      this.runtime = runtime;
      this.tables = tables;
      this.householdDemands = householdDemands;
      this.householdEconomies = householdEconomies;
      this.householdGoods = householdGoods;
      this.markets = markets;
      this.planAssetSources = planAssetSources;
      this.planLaborSources =
          Objects.requireNonNull(planLaborSources, "planLaborSources（缺省给空表 = 全部 SELF）");
      this.day = day;
      for (ProductionProcess unit : tables.units.values()) {
        unitIdsByOperator
            .computeIfAbsent(unit.operator(), ignored -> new ArrayList<>())
            .add(unit.id());
      }
      for (OwnershipStake share : tables.assetShares.values()) {
        shareIdsByOwner
            .computeIfAbsent(share.owner(), ignored -> new ArrayList<>())
            .add(share.id());
        shareIdsByOperator
            .computeIfAbsent(share.operator(), ignored -> new ArrayList<>())
            .add(share.id());
      }
      // ★ P2-A A3：家户人口组成来自 Social 的只读投影（不是 Economy 状态里的成员份额表）。
      for (Map.Entry<HouseholdId, Map<PeopleLotId, Long>> householdMembers :
          composition.entrySet()) {
        List<PeopleLotId> lots =
            lotsByHousehold.computeIfAbsent(
                householdMembers.getKey(), ignored -> new ArrayList<>());
        for (Map.Entry<PeopleLotId, Long> member : householdMembers.getValue().entrySet()) {
          if (member.getValue() != null
              && member.getValue() > 0L
              && !lots.contains(member.getKey())) {
            lots.add(member.getKey());
          }
        }
      }
      for (HouseholdLaborCommitment laborCommitment : tables.laborCommitments.values()) {
        allocationMilliByHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Long::sum);
        allocationMilliByGroup.merge(
            laborCommitment.group(), laborCommitment.laborMilli(), Long::sum);
        List<PeopleLotId> lots =
            lotsByHousehold.computeIfAbsent(
                laborCommitment.household(), ignored -> new ArrayList<>());
        if (!lots.contains(laborCommitment.group())) {
          lots.add(laborCommitment.group());
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
