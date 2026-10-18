package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
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

  // ── 落笔：意向的执行（真写）与模拟（只写副本表） ──────────────────────────────────────

  /** ★★★ <b>意向落笔的显式写模式</b>（F5 / R6）：副作用面不靠"是哪个调用点"来约定 —— 调用点必须显式点名，意向段与执行段 因此不可能再共用一条会真写的路径。 */
  enum WriteMode {

    /** {@link #planEntries}：只写本方法的私有副本表 {@code context.tables}，production runtime 一字不动。 */
    SIMULATED_ONLY,

    /** {@link #execute}：真写 production（registerPlan/createInstance/实例守卫）并就地更新工作副本。 */
    REAL
  }

  /** 工作副本组（plan 用浅拷贝、只收本批模拟写且返回即丢；execute 用会话真副本）。 */
  record Tables(
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
  static final class EntryContext {

    /**
     * ★★ <b>production 计算契约（D-032 §4.2）</b>：试算与"按需登记方案定义"都经它 —— economy 不再自己算 可行性/规模/需求（那是第二份算式，与
     * production 的实现必然漂开），只做价格、需求与分配。
     */
    final io.mosire.simos.production.contract.ProductionRuntime runtime;

    final Tables tables;
    final Map<DemandId, HouseholdDemand> householdDemands;
    final Map<HouseholdId, HouseholdEconomy> householdEconomies;
    final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
    final MarketIndex markets;
    final long day;
    final Map<ActorRef, List<ProductionUnitId>> unitIdsByOperator = new LinkedHashMap<>();
    final Map<HouseholdId, List<PeopleLotId>> lotsByHousehold = new LinkedHashMap<>();
    final Map<HouseholdId, Long> allocationMilliByHousehold = new LinkedHashMap<>();
    final Map<PeopleLotId, Long> allocationMilliByGroup = new LinkedHashMap<>();
    final Map<ActorRef, List<AssetShareId>> shareIdsByOwner = new LinkedHashMap<>();
    final Map<ActorRef, List<AssetShareId>> shareIdsByOperator = new LinkedHashMap<>();

    /**
     * ★★ <b>B2c（裁定八）：闲置份额来源主体的 economy 声明面</b> —— 键 = 方案 id，值 = 该方案可取用闲置份额的主体。 缺键 = 只认自有份额（= 旧候选缺省
     * {@code assetSource} 的逐值行为）。
     */
    final Map<IndustryId, ActorRef> planAssetSources;

    /** ★★ B3（裁定十二）：按方案声明的**劳动来源**（缺键 ⇒ SELF；见 {@link EconomyMeta#laborSourceOf}）。 */
    final Map<IndustryId, LaborSource> planLaborSources;

    EntryContext(
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
    List<PeopleLotId> lotsFor(HouseholdId household) {
      return lotsByHousehold.getOrDefault(household, List.of());
    }
  }

  private EconomyEntrySettlement() {}

  static Map<IndustryId, IndustryDefinitionView> plans(
      Map<IndustryId, IndustryDefinitionView> industries) {
    return EntryCandidateBook.plans(industries);
  }

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
    return EntryEvaluationBook.planEntries(
        runtime,
        householdDemands,
        industries,
        planAssetSources,
        planLaborSources,
        index,
        markets,
        householdEconomies,
        householdGoods,
        householdMoney,
        composition,
        units,
        assetShares,
        laborCommitments,
        operatorConditions,
        relations,
        day);
  }

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
    return EntryExecutionBook.execute(
        runtime,
        intents,
        householdDemands,
        industries,
        planAssetSources,
        planLaborSources,
        units,
        assetShares,
        laborCommitments,
        operatorConditions,
        relations,
        householdEconomies,
        householdGoods,
        householdMoney,
        composition,
        markets,
        day,
        productionRosters);
  }
}
