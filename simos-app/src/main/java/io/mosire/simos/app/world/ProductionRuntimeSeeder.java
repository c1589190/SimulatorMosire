package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.EconomySeederConditions.applyTestConditions;
import static io.mosire.simos.app.world.GovernmentSeeder.genesisAuditGovernment;
import static io.mosire.simos.app.world.GovernmentSeeder.governmentHouseholdGovernment;
import static io.mosire.simos.app.world.GovernmentSeeder.seedGovernmentHousehold;
import static io.mosire.simos.app.world.GovernmentSeeder.seedGovernmentMint;
import static io.mosire.simos.app.world.IndustrySeeder.agriculture;
import static io.mosire.simos.app.world.IndustrySeeder.handicraft;
import static io.mosire.simos.app.world.IndustrySeeder.householdWeaving;
import static io.mosire.simos.app.world.IndustrySeeder.planOf;
import static io.mosire.simos.app.world.IndustrySeeder.trade;
import static io.mosire.simos.app.world.LandOwnershipSeeder.collectiveRelation;
import static io.mosire.simos.app.world.LandOwnershipSeeder.grainFarmRosterNode;
import static io.mosire.simos.app.world.LandOwnershipSeeder.grainFarmUnits;
import static io.mosire.simos.app.world.LandOwnershipSeeder.organizerRelation;
import static io.mosire.simos.app.world.LandOwnershipSeeder.ownershipStakeNode;
import static io.mosire.simos.app.world.LandOwnershipSeeder.ownershipStakesOf;
import static io.mosire.simos.app.world.LandOwnershipSeeder.plannedLaborOf;
import static io.mosire.simos.app.world.LandOwnershipSeeder.retargetFarmAllocation;
import static io.mosire.simos.app.world.LandOwnershipSeeder.secondaryAllocation;
import static io.mosire.simos.app.world.LandOwnershipSeeder.splitIndustry;
import static io.mosire.simos.app.world.MarketSeeder.derivedMarketTables;
import static io.mosire.simos.app.world.MarketSeeder.genesisEndowmentOf;
import static io.mosire.simos.app.world.MarketSeeder.operatorWageReserveMilli;
import static io.mosire.simos.app.world.ProductionRoleSeeder.applyLegacyRoleMapping;
import static io.mosire.simos.app.world.RuralHouseholdSeeder.poolCohorts;
import static io.mosire.simos.app.world.RuralHouseholdSeeder.ruralCohort;
import static io.mosire.simos.app.world.RuralHouseholdSeeder.urbanCohort;
import static io.mosire.simos.app.world.RuralHouseholdSeeder.withoutDisplaced;
import static io.mosire.simos.app.world.RuralLaborSeeder.appendAllocation;
import static io.mosire.simos.app.world.RuralLaborSeeder.industryDailyLabor;
import static io.mosire.simos.app.world.RuralLaborSeeder.laborBudget;
import static io.mosire.simos.app.world.RuralLaborSeeder.laborMilli;
import static io.mosire.simos.app.world.RuralLaborSeeder.landMilliMuOf;
import static io.mosire.simos.app.world.RuralLaborSeeder.populationOf;
import static io.mosire.simos.app.world.RuralLaborSeeder.toolPerWorkshopMilli;
import static io.mosire.simos.app.world.SeederPayloadSupport.mintAllocationsOf;
import static io.mosire.simos.app.world.SeederPayloadSupport.unitOf;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.world.EconomySeeder.FoundationProfile;
import io.mosire.simos.app.world.EconomySeeder.OperatorSeed;
import io.mosire.simos.app.world.EconomySeeder.Seed;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** P0.5-D：production-runtime 逐格创世编排（产业/劳动/家户/市场/政府）。 */
class ProductionRuntimeSeeder extends WorldBalanceSeeder {

  /**
   * ★★ <b>PRODUCTION_RUNTIME：P2 恢复的旧完整生产播种路径</b>（旧 {@code b243e3d9^} 的 LEGACY/COMPLETE 共同生产段）。
   *
   * <p>它是唯一生产路径：本方法逐格生成 {@code industries}（farm/weave/craft）、 {@code units} / {@code assetShares} /
   * {@code laborSupply} / {@code allocations} / {@code memberships} / 经营者开缸账；生产权威 = entries 的完整生产结构
   * + 默认生产方式目录。
   *
   * <p>★ 载荷出口的 {@code modes} / {@code classStructures} / {@code classPositions} / {@code
   * classStandings} / {@code assetRules} 由 {@link #jsonOf} 追加（mode
   * 目录来自 {@code DefaultProductionModes}）；有政府引用（{@code seeding.governmentHousehold()}）时内置 GOV
   * 家户与政府国库， 否则只落一条不持户的创世审计主体（P2-C §13.7）。
   */
  static EconomySeeder.Seed planProductionRuntime(
      String mapId,
      PopulationSeeder.Seeding seeding,
      Function<HexCoord, String> terrainOf,
      long genesisMoneyMilliPerCapita,
      FoundationProfile profile,
      TestConditions conditions,
      Function<HexCoord, CurrencyId> numeraireOf) {
    Objects.requireNonNull(numeraireOf, "numeraireOf");
    Map<HexCoord, List<PopulationGroup>> ruralByHex = new LinkedHashMap<>();
    Map<HexCoord, List<PopulationGroup>> urbanByHex = new LinkedHashMap<>();
    for (PopulationGroup group : seeding.groups()) {
      Map<HexCoord, List<PopulationGroup>> target =
          PopulationLots.isUrban(group) ? urbanByHex : ruralByHex;
      // ★ S2：落点从家户取（批次身上没有 residence）。
      target.computeIfAbsent(seeding.locationOf(group.id()), hex -> new ArrayList<>()).add(group);
    }
    List<HexCoord> hexes = new ArrayList<>(ruralByHex.keySet());
    for (HexCoord hex : urbanByHex.keySet()) {
      if (!ruralByHex.containsKey(hex)) {
        hexes.add(hex); // 有城市却无农村人口的格也要有经济状态（否则那座城的人口凭空消失）
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));

    List<Map<String, Object>> entries = new ArrayList<>(hexes.size());
    // ★★★ W5（2026-10-11）：逐格的"作坊配额发完之后仍未被用掉的劳动预算"（键 = 格）。
    //   铸币产业的劳动**只能从这里拿**（见 seedGovernmentMint 的"劳动从哪来"）—— 它保证了
    //   "Σ 家户 PRODUCTION 配额 ≤ 时间预算"这条构造期不变量**逐值仍成立**（不是再造一份劳动）。
    Map<HexCoord, Map<PeopleLotId, Long>> urbanSlackLaborByHex = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> householdStocks = new LinkedHashMap<>();
    // ★ S1：家户 id → 账所在格（去重表：id 由 ofSeed 生成 ⇒ 同 (格,居住,阶层) 恒同一 id）。
    Map<HouseholdId, HexCoord> householdLocations = new LinkedHashMap<>();
    // ★★ H4：创世货币禀赋（裁定 K14）走**同一处接缝** —— 与 householdStocks 同一次循环算出、同一份交给
    //   {@link HouseholdSeeder}（两处各算一遍必然漂开）。★ 它是**初始条件**，不是发行：见 {@link #householdMoney}。
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    // ★★ H4：逐格市场的载荷（M1-A 每格单一计价货币 + 固定价）。本批逐格价格无差异 ⇒ 共享同一个不可变 {@link MarketTable}。
    // ★★ W1：价表**每区一张**。创世期的区由"计价币"决定：全格同币 ⇒ 一张 {@code single-region}（派生形态，
    //   与改动前 MarketTopology.singleRegion 的区 id 同源）；多币种 ⇒ 逐币一张（键 = {@code currency:<币>}）。
    //   持久市场区（三权世界）在创世之后的 economy.DefineMarketZone 命令里落地，届时把这几张按币分组的
    //   派生价表**改建**到区 id 上（见 EconomyDefineMarketZoneHandler 的改建段）。
    Map<CurrencyId, Set<HexCoord>> hexesByNumeraire = new LinkedHashMap<>();
    // ★★ H5：逐格逐产业的**经营主体开缸账**（键序 = 产业生成序 = farm → weave → craft ⇒ 内容的纯函数）。
    List<OperatorSeed> operators = new ArrayList<>();
    // ★★★ P5b1：grain_farm 多角色 unit 的显式名册（Seed 顶层 productionRosters[] 的唯一出口）。
    List<Map<String, Object>> productionRosters = new ArrayList<>();
    for (HexCoord hex : hexes) {
      // ★★ P2-A：家户身份/人口从 Social 侧读入；劳动池要排除流民批次（D-023：流民没有工作），
      //   但成员份额投影要用**全部**批次（流民户也是真实的成员归属）。
      List<PopulationGroup> ruralAll = ruralByHex.getOrDefault(hex, List.of());
      List<PopulationGroup> urbanAll = urbanByHex.getOrDefault(hex, List.of());
      PoolCohorts ruralCohorts = poolCohorts(seeding, hex, ResidenceKind.RURAL);
      PoolCohorts urbanCohorts = poolCohorts(seeding, hex, ResidenceKind.URBAN);
      List<PopulationGroup> ruralPool = withoutDisplaced(ruralAll, ruralCohorts);
      List<PopulationGroup> urbanPool = withoutDisplaced(urbanAll, urbanCohorts);
      IndustryId farmId = IndustryHexKeys.id(FARM, hex.q(), hex.r());
      IndustryId craftId = IndustryHexKeys.id(CRAFT, hex.q(), hex.r());
      IndustryId weaveId = IndustryHexKeys.id(WEAVE, hex.q(), hex.r());
      // ★★ R3B.2：新载荷显式产生 unit —— 先建 plan（模板 + 经营者 + 产能），再逐 unit/份额落载荷。
      // ★★ **本格的产能与人口派生量**（H0.3：产能从"行"搬到"产业"，故它们在这里一次算好）：
      //   亩 = 地形系数决定（**与人口无关**：没人种的格，地还在）；织机/作坊 = 人口 ÷ 场景参数。
      long landMilliMu = landMilliMuOf(terrainOf.apply(hex));
      long looms = populationOf(ruralAll) / RURAL_CAPITA_PER_LOOM;
      long urbanPopulation = populationOf(urbanAll);
      long workshops = urbanPopulation / URBAN_CAPITA_PER_WORKSHOP;
      boolean hasRural = populationOf(ruralAll) > 0L;
      boolean hasCraft = urbanPopulation > 0L;
      // ★★ B1：**本格的计价币**（多币种世界 = 本格所属市场区的法定币）。它在**同一处**同时喂给两件事：
      //   ① 本格市场的 numeraire（见下面 markets.put）② 本格家户/经营者钱包的币种（见 cohort 调用与作坊主工资周转金）。
      //   ★ 两件事必须同源：钱包币种 ≠ 本格市场币种 ⇒ 该格家户的每一次买盘都撞 I19 的异币具名拒（本地市场对它关门）。
      //   ★ 缺省函数（老入口）恒回银 ⇒ 老世界逐值不变。
      CurrencyId numeraire = numeraireOf.apply(hex);
      if (numeraire == null) {
        throw new IllegalStateException(
            "逐格计价币函数对格 " + hex + " 回了 null（说不出这格按什么钱报价 ⇒ 播种器 fail-closed，不静默落到银）");
      }
      // ★★ P11.7/D-024：城市格的商号本金主 = 该格城镇 landlord 家户（位置 = merchant.principal）。它的身份从
      //   Social 侧的 PoolCohorts 读入（P2-A：不再另拼 ofSeed），actor 拼写点走 HouseholdActors。
      HouseholdId merchantPrincipalHousehold =
          hasCraft ? urbanCohorts.classHouseholds().get(LANDLORD_SLOT_INDEX) : null;
      ActorRef merchantPrincipalActor =
          hasCraft ? HouseholdActors.of(merchantPrincipalHousehold) : null;
      List<IndustryPlan> plans = new ArrayList<>(hasCraft ? 4 : 3);
      // ★★ P2-A §13.3：作坊不是账户主体 —— 主 unit 的经营者改成**组织者家户**：
      //   · craft（handicraft 作坊）⇒ 该格城镇中农家户（{@code handicraft_workshop.workshop_owner}）。
      //   ★ 家户纺织（household）保持聚合主体：它是**多个家户共同经营**的 unit，账户主体 = unit 名下劳动家户的集合
      //     （见 HouseholdRouting）；trade 的经营者本来就是商号本金主家户。
      //   ★★ P5b1：farm 不再有"地主主 unit"—— grain_farm 由一个共享 plan + 逐家户 unit 组成（见下）。
      ActorRef craftOrganizer =
          hasCraft
              ? HouseholdActors.of(urbanCohorts.classHouseholds().get(WORKSHOP_OWNER_SLOT_INDEX))
              : null;
      IndustryPlan farmPlan = agriculture(hex, landMilliMu);
      plans.add(farmPlan);
      // ★★★ P5b1：本 hex 的 grain_farm 逐户 unit —— 四个阶层家户按确定性权重分 LAND（owner=operator=家户），
      //   流民/无地户保留 0 土地 unit。跨 hex 不伪造：份额 location 恒为本 hex。
      List<GrainFarmUnit> farmUnits =
          grainFarmUnits(
              farmPlan, hex, ruralCohorts.classHouseholds(), ruralCohorts.displacedHousehold());
      if (hasRural) {
        // ★★ R3（T4）：农村家庭纺织 —— 配方 FIBER + LABOR + SMALL_TOOL → CLOTH，由**同一批农村人**承担（见
        // appendAllocation）。
        //   ★ 它**没有自己的阶层行**（H0.2 起织机住在本产业的 {@code capacity}，纤维住在农村四行）⇒ 只有"有农村人口"
        //     的格才建它（没有农村人口的格既无织机也无农村劳动配额 ⇒ 建出来是一具空壳）。
        plans.add(householdWeaving(hex, looms));
      }
      if (hasCraft) {
        plans.add(handicraft(hex, workshops).withOperator(craftOrganizer));
        // ★★ P11.7/D-024：城市 trade 产业 —— 制度 merchant、产出运输服务、以**工具**为作业资本（W7-fix）；经营者 = 商号本金主。
        plans.add(trade(hex, merchantPrincipalActor));
      }
      // ★★ P2-A：作坊主 unit 的显式关系 —— operator/residualOwner = 组织者家户，inputSupplier 仍指**原产业主体 actor**
      //   （{@code ToActor(org)} 在 supplierAccountsOf 里代理到 unit 名下劳动家户 ⇒ 播种期"谁出种/出料"逐字不变）。
      //   ★ P5b1：farm 的 unit 关系已在 {@code grainFarmUnits} 里逐户显式给出（空 rules + SELF），不再走这里。
      ProductionRules craftMainRelation =
          hasCraft
              ? organizerRelation(
                  planOf(plans, craftId),
                  Set.of(ResidenceKind.URBAN),
                  new ActorRef(ActorKind.ORGANIZATION, craftId.value()))
              : null;
      // ★★ P2-E：家户纺织主 unit 是**集体经营** unit（operator 是聚合主体，名下有多个劳动家户）。
      //   它必须显式带一条"无规则"关系：净产出已由 harvest 的 creditOutput 按劳动权重分给各家家户账；
      //   若沿用制度默认的 HOUSEHOLD 模板，模板的四个受方 cohort 与集体成员是同一批家户 ⇒ 规则结算会在
      //   成员之间铸出"付给自己"的转移（Transfer 两端不得相等，首个收获日当场抛）。
      ProductionRules weaveMainRelation =
          hasRural ? collectiveRelation(planOf(plans, weaveId)) : null;
      List<Map<String, Object>> industries = new ArrayList<>(plans.size());
      for (IndustryPlan plan : plans) {
        industries.add(plan.payload());
      }
      // ★★ P2-A §13.3：庄园/作坊/商号**不再持账** ⇒ 这里不再播种任何经营者账户（{@code operators} 保持空表）。
      //   原先挂在经营者账上的东西逐项改记到组织者家户：
      //   · craft 的工具周转料 = **一个周期的工具用量 / 座** ⇒ 记进作坊主家户（下面与 classes 表同批合并）；
      //   · craft 的工资周转金（{@link #operatorWageReserveMilli}）⇒ 记进作坊主家户钱包；
      //   · farm 的种子/weave 的纤维本来就在家户账上（inputSupplier 走家户代理，逐值不变）。
      // ★★ **R2：该格的劳动供给与配额**（第三阶段设计稿 §四）—— 创世按"农村批次 → 农业（庄园）/ 城镇批次 →
      //   手工业（作坊）"初始化配额；**R3 起农村那 1000‰ 拆成"农业 900‰ + 家庭纺织 100‰"**（同一批人两条配额，
      //   总和仍 ≤ 该批次的可用劳动 —— 由 {@code EconomyData} 的构造期守卫判死）。
      List<Map<String, Object>> allocations = new ArrayList<>();
      // ★★ S1：成员份额（逐 lot → 家户）：由 cohortGroup 按 row population × pool 各批次人数权重拆出。
      if (hasRural) {
        IndustryPlan weavePlan = planOf(plans, weaveId);
        long ruralDaily =
            industryDailyLabor(
                ruralCohorts.classPopulations(), laborMilli(ruralPool), populationOf(ruralPool));
        long weaveQuota = ruralDaily * WEAVE_SHARE_PER_MILLE / 1000L;
        // ★ 残差归农业（"农业 = ruralDaily − weaveQuota"⇒ **要切的总量**之和恒等于本池日劳动（折扣后口径））。
        //   ★★ M1.8 起不等于"实际发出的配额之和"：预算按**逐批次折扣后的可用劳动**封顶，性别权重偏斜时某些批次会
        //      被压到上限 ⇒ 总配额可以**分不满**（合法状态；见 appendAllocation / laborBudget 的类注）。
        Map<PeopleLotId, Long> budget = laborBudget(ruralPool);
        // ★★★ P5b1：先按既有算法发农业配额（占位 unit = plan.unitId），随后逐行改挂到该家户自己的 grain_farm unit。
        //   只换 unit/actor；laborMilli / group / household 一个不动 ⇒ 家户劳动守恒与预算封顶逐值不变。
        appendAllocation(
            allocations,
            budget,
            ruralPool,
            hex,
            farmPlan.unitId(),
            farmPlan.operator(),
            FARM,
            ruralDaily - weaveQuota);
        appendAllocation(
            allocations,
            budget,
            ruralPool,
            hex,
            weavePlan.unitId(),
            weavePlan.operator(),
            ACTIVITY_WEAVE,
            weaveQuota);
        Map<HouseholdId, GrainFarmUnit> farmUnitByHousehold = new LinkedHashMap<>();
        for (GrainFarmUnit unit : farmUnits) {
          farmUnitByHousehold.put(unit.household(), unit);
        }
        String farmPlaceholder = farmPlan.unitId().value();
        for (Map<String, Object> allocation : allocations) {
          if (!farmPlaceholder.equals(allocation.get("activity"))) {
            continue;
          }
          HouseholdId household = HouseholdId.parse((String) allocation.get("household"));
          GrainFarmUnit unit = farmUnitByHousehold.get(household);
          if (unit == null) {
            throw new IllegalStateException(
                "农业配额找不到家户 grain_farm unit（seed 内部不一致）: household="
                    + household
                    + " hex="
                    + hex);
          }
          retargetFarmAllocation(allocation, unit);
        }
      }
      if (hasCraft) {
        IndustryPlan craftPlan = planOf(plans, craftId);
        // ★★★ W5（2026-10-11）：把**城镇池的剩余预算**留下来给铸币产业（见 seedGovernmentMint 的"劳动从哪来"）。
        //   ★ 顺序不变、口径不变（appendAllocation 的 total/权重/预算封顶一字未改）：这里只是把同一张预算表
        //     多做一次读取 —— 铸币拿的是**作坊配额封顶之后真的没被用掉的那部分**，不是"再造一份劳动"。
        Map<PeopleLotId, Long> urbanMintBudget = laborBudget(urbanPool);
        appendAllocation(
            allocations,
            urbanMintBudget,
            urbanPool,
            hex,
            craftPlan.unitId(),
            craftPlan.operator(),
            CRAFT,
            industryDailyLabor(
                urbanCohorts.classPopulations(), laborMilli(urbanPool), populationOf(urbanPool)));
        urbanSlackLaborByHex.put(hex, urbanMintBudget);
      }
      // ★★ **R4-B.3a：把上面的"整额配额 + 整份 capacity"确定性地拆成主 unit + 家户副 unit** ——
      //   劳动行已经按现有规则发完（逐值不动），这里只做"把已有的拆成两笔"：
      //   · 副 unit 合计拿 ⌊总量 × SECONDARY_PER_MILLE ÷ 1000⌋，主 unit 拿剩余；
      //   · 分不出 ≥ 一份 capacityPerUnit 的家户跳过（其劳动与份额都留在主 unit）；
      //   · Σ 份额逐 asset 不变、Σ 配额逐 (批次, 家户) 不变（见 splitIndustry 的守恒注）。
      //   ★ 必须在 allocations 发完之后做：候选家户 = "在本格配额里出现过的家户"，只有发完才知道。
      //   ★★ P5b1：farm 不走 splitIndustry（农村 grain_farm 已逐户直接物化，无主 unit/tenant 副 unit）。
      List<IndustrySplit> splits = new ArrayList<>(plans.size());
      for (IndustryPlan plan : plans) {
        if (FARM.equals(plan.kind())) {
          continue;
        }
        splits.add(splitIndustry(plan, allocations, hex));
      }
      List<Map<String, Object>> units = new ArrayList<>();
      List<Map<String, Object>> assetShares = new ArrayList<>();
      // ★★★ P5b1：farm 的逐户 unit + LAND 份额（owner=operator=家户；无地户份额 0 也登记）。
      for (GrainFarmUnit unit : farmUnits) {
        units.add(unit.unitNode());
        assetShares.add(unit.shareNode());
      }
      List<Map<String, Object>> splitAllocations = new ArrayList<>();
      // ★★★ D-032 C3（裁定选项 (a)）：逐 plan 收集**主 unit + 全部副 unit** 的旧规则；全部 unit 建好后由
      //   {@link #applyLegacyRoleMapping} 统一决定"整体映射并清空"或"原样保留走旧路径"。
      Map<String, List<CompensationRule>> legacyRulesByPlan = new LinkedHashMap<>();
      for (IndustrySplit split : splits) {
        IndustryPlan plan = split.plan();
        // 主 unit：载荷逐字段与 B.2 同形（operator 不变），capacity 变成"剩余"量（体现在 assetShares）。
        // ★★ P11.7/D-024：trade 的 unit 由解析器从 industry 节点的 legacy operator 合成（见 {@link #trade} 的
        //   兼容读口注释）—— 这里不重发，避免解析器判"同一 (产业, 经营者) 两处拼写"；作业资本（SMALL_TOOL）份额仍照发。
        if (!TRADE.equals(plan.kind())) {
          ProductionRules mainRelation =
              switch (plan.kind()) {
                case FARM -> null; // P5b1：farm 不走主 unit（逐户 unit 已在上面加入）。
                case CRAFT -> craftMainRelation;
                case WEAVE -> weaveMainRelation;
                default -> null;
              };
          if (mainRelation != null) {
            legacyRulesByPlan
                .computeIfAbsent(plan.id(), ignored -> new ArrayList<>())
                .addAll(mainRelation.rules());
          }
          units.add(
              mainRelation == null ? unitOf(plan) : unitOf(plan, plan.operator(), mainRelation));
        }
        assetShares.addAll(
            ownershipStakesOf(
                plan, split.mainCapacity(), IndustryHexKeys.hexKey(hex.q(), hex.r())));
        // 副 unit：owner/operator/kind 显式落 assetShares；relation 显式随 unit 发出。
        for (HouseholdUnit secondary : split.secondaries()) {
          units.add(unitOf(plan, secondary));
          legacyRulesByPlan
              .computeIfAbsent(plan.id(), ignored -> new ArrayList<>())
              .addAll(secondary.relation().rules());
          for (Map.Entry<String, Long> asset : secondary.quantities().entrySet()) {
            assetShares.add(
                ownershipStakeNode(
                    plan.id(),
                    asset.getKey(),
                    secondary.owner(),
                    secondary.operator(),
                    secondary.kind(),
                    asset.getValue(),
                    IndustryHexKeys.hexKey(hex.q(), hex.r())));
          }
        }
        // 劳动：主行减 moved、副 unit 新发一条；Σ 逐批次不变。
        for (LaborMove move : split.laborMoves()) {
          long laborMilli = ((Number) move.row().get("laborMilli")).longValue();
          move.row().put("laborMilli", laborMilli - move.moved());
          splitAllocations.add(secondaryAllocation(plan, move));
        }
      }
      allocations.addAll(splitAllocations);
      // ★★★ P5b1：farm 名册必须在配额落定后生成（plannedLabor = 本 unit 的配额合计）。
      //   多角色模板必须显式名册；没有真实 hired_laborer 参与项时不塞假 worker。
      for (GrainFarmUnit unit : farmUnits) {
        productionRosters.add(
            grainFarmRosterNode(unit, plannedLaborOf(allocations, unit.unitId())));
      }
      // ★★★ D-032 C3：plan 级映射/回退（可映射 ⇒ roles + 清空旧 rules；不可表示 ⇒ 原样保留旧 rules + INFO BLOCKED）。
      //   ★ P5b1：farm 的 roles 是显式两角色、且 unit 已有显式名册 ⇒ 不进入旧规则映射（否则会被单 operator 覆盖）。
      List<IndustryPlan> legacyRolePlans = new ArrayList<>(plans.size());
      for (IndustryPlan plan : plans) {
        if (!FARM.equals(plan.kind())) {
          legacyRolePlans.add(plan);
        }
      }
      applyLegacyRoleMapping(legacyRolePlans, units, legacyRulesByPlan);
      // ★★ M-A1：商号行（merchantFirms）已退役 —— 创世不再为城市格播商号状态。运力改由**派生量**给出
      //   （MerchantCapacityPool：该格"选了跑商"的家户的劳动投入 + 工具可投入量）。
      //   ★ 保留：本格的 trade 产业（{@link #TRADE}）与它的作业资本（SMALL_TOOL）份额 —— 那是 merchant 生产方式的产业落点、
      //     也是迁移前瞻的产业模板（M5/M-D 的"主业副业排序"要用它），与商号行是两件事。
      // ★★ **H0.2：本格的阶层行 = 两组四行（家户）** —— 行的身份是 {@code (格, 居住类型, 阶层)}，
      //   **不再挂在任何产业下**（产业只留"制度 + 配方 + 产能"）。这与"行 = 家户、产业 = 生产活动"的分工一一对应：
      //   一格的农村四行是**同一批农村人**，他们既供给农业（900‰）、也供给家庭纺织（100‰）；城镇四行同理只供给作坊。
      //   ★ **两组恒在**（即使某池人口为 0）：格集本身由批次落点决定，而"这一格有没有城镇家户"是一件事、
      //     "这一格此刻有没有城镇人口"是另一件事（H1 的家户 actor 按 格 × 居住 × 阶层 播，形状必须与行集一致）。
      //     人口为 0 的行是**合法的空账**（人口/劳动/需求全 0），不是噪声：它是"这一格有这个家户、只是没人"。
      List<Map<String, Object>> classes = new ArrayList<>(2 * CLASS_IDS.length);
      // ★★ **H1：开缸库存的去处 = 家户账**（actor 侧的 {@code HouseholdInventory}）。本方法把它**一次算好**并交回
      //   （{@code Seed.householdStocks}），载荷里的阶层行**不再带 {@code goods}** —— 行里没有商品这件事
      //   在"一本账"的判据（守恒式无 ΔΣRowGoods）里是必须的。
      //   ★★ H4：**创世货币禀赋与它同源**（同一处循环、同一份人口口径）⇒ 商品与货币两本账在同一次 plan 里算定。
      classes.addAll(
          ruralCohort(
              hex,
              ruralPool,
              ruralAll,
              landMilliMu,
              householdLocations,
              householdStocks,
              householdMoney,
              genesisMoneyMilliPerCapita,
              ruralCohorts,
              numeraire));
      classes.addAll(
          urbanCohort(
              hex,
              urbanPool,
              urbanAll,
              workshops,
              householdLocations,
              householdStocks,
              householdMoney,
              genesisMoneyMilliPerCapita,
              urbanCohorts,
              numeraire));
      if (hasCraft) {
        // ★★ P2-A §13.3：作坊主家户承接原"作坊经营者账"的周转料与工资周转金（同一格、同一户，账只有一本）。
        HouseholdId workshopOwner = urbanCohorts.classHouseholds().get(WORKSHOP_OWNER_SLOT_INDEX);
        Map<CommodityId, Long> tools =
            new LinkedHashMap<>(householdStocks.getOrDefault(workshopOwner, Map.of()));
        tools.merge(
            new CommodityId(COMMODITY_TOOL),
            Math.multiplyExact(workshops, toolPerWorkshopMilli()),
            Math::addExact);
        householdStocks.put(workshopOwner, tools);
        long wageReserve =
            operatorWageReserveMilli(
                REGIME_HANDICRAFT, craftId, new ActorRef(ActorKind.ORGANIZATION, craftId.value()));
        if (wageReserve > 0L) {
          Map<CurrencyId, Long> wallet =
              new LinkedHashMap<>(householdMoney.getOrDefault(workshopOwner, Map.of()));
          wallet.merge(numeraire, wageReserve, Math::addExact);
          householdMoney.put(workshopOwner, wallet);
        }
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("q", hex.q());
      entry.put("r", hex.r());
      // ★★★ B5（§13.15.3 裁定十九）：本键原名 `industries`（economy 载荷自带一整套产业定义 + 旧形状实例字段）
      //   —— 那些定义从未进过 economy 状态（唯一权威在 production），旧形状合成路径已删 ⇒ 键名改为 `plans`：
      //   它现在只声明"这一格有哪些方案节点"（每个节点仍是 unit 节点的宿主 + trade 的显式 relation 来源）。
      entry.put("plans", industries);
      entry.put("classes", classes);
      entry.put("allocations", allocations);
      // ★★ S1/R3B.2：本格各产业的资产份额由 plan 显式发出（容量整额 OWNED 给 unit.operator）。
      entry.put("assetShares", assetShares);
      entry.put("units", units);
      entries.add(entry);
      // ★★ H4：本格的市场（M1-A：每格一个计价货币 + 一张价表）。★ **有 entry 才有市场** ——
      //   "这一格没有市场"（格不在本表的键集里）是合法状态，不是缺数据。
      //   ★★ B1：计价币取**本格的** {@code numeraire}（多币种世界 = 本区法定币）；老入口缺省恒回银。
      //   ★★ W1：逐格价表退役 ⇒ 这里不再逐格建表，只登记"这一格属于哪个币"，由 {@link #derivedMarketTables}
      //   归组成每币一张派生价表（同一份信息、少 N-1 份逐字相同的表）。
      hexesByNumeraire.computeIfAbsent(numeraire, ignored -> new LinkedHashSet<>()).add(hex);
    }
    // ★★ P2-C §13.7：政府家户**按调用方给出的政府引用稳定建户**（Social 侧已建好，经济侧只读）。
    //   给了引用（世界级 demo / GOV 单位）⇒ 追加该家户的空行+空账；没给（多国/多省的普通 seed）⇒
    //   本次播种不产生政府家户，也不产生"先到者胜"的世界政府 —— 单位政府由
    //   economy.RegisterGovernment + actor.EnsureHouseholdAccount 按 GOV 单位稳定 id 建。
    Optional<HouseholdId> governmentHousehold = seeding.governmentHousehold();
    if (governmentHousehold.isPresent()) {
      HouseholdId govHousehold = governmentHousehold.get();
      seedGovernmentHousehold(
          entries,
          seeding.households(),
          govHousehold,
          householdLocations,
          householdStocks,
          householdMoney);
      // ★★★ W5（2026-10-11）：给政府家户播一个**铸币生产方式**（{@link IndustryDefinitionView#moneyOutputPerUnit()} 的
      //   {@code moneyOutputPerUnit}）+ 它的 unit，并把铸币要吃的投入（工具）记进政府家户库存。
      //   ★ 为什么必须由创世给出、而不是结算里凭空造：铸币是"生产出来的"（用户原话），生产必须有组织者、
      //     有产能锚（"单位规模"的定义）、有要被**真实消耗**的投入 —— 三者都只能在播种/命令面出现
      //     （GM 侧 {@code economy.UpsertIndustry} 也照同形追加/修改模板）。
      //   ★ 本周期最多铸多少毫 = {@code Government.mintScalePerCycle}（政策旋钮）；实际产出还取决于本 unit 的规模。
      HexCoord mintHex = householdLocations.get(govHousehold);
      seedGovernmentMint(
          entries,
          govHousehold,
          mintHex,
          householdStocks,
          householdMoney,
          numeraireOf.apply(mintHex),
          // ★★ 铸币的劳动：从**这一格作坊配额发完之后剩下的预算**里拿（见方法注"劳动从哪来"）。
          //   没有剩余（或这一格根本没有城镇池）⇒ 铸币拿不到劳动 ⇒ 本方法具名跳过（不造一份假劳动）。
          mintAllocationsOf(mintHex, entries),
          urbanByHex.getOrDefault(mintHex, List.of()),
          urbanSlackLaborByHex.get(mintHex));
    }
    // ★★ S1.4 出口自检：tick0 seed 是"人工造份额"的唯一入口 ⇒ 这里逐 lot 对账，不等就播不出去（fail-closed）。
    //   给了政府家户 ⇒ 国库 = 它的账户，铸币政策旋钮取具名 GM 默认值（demo 世界才铸币）；
    //   没给 ⇒ 只落一条**不持户的创世审计主体**（world-silver 的 legacy 形状）给 INITIAL_ENDOWMENT 归属，
    //   不声称任何家户/账户，也不参与铸币/发债（旋钮恒 0）。
    Map<GovernmentId, Government> governments =
        governmentHousehold
            .map(hh -> Map.of(GENESIS_GOVERNMENT_ID, governmentHouseholdGovernment(hh)))
            .orElseGet(() -> Map.of(GENESIS_GOVERNMENT_ID, genesisAuditGovernment()));
    // ★★ P3：INITIAL_ENDOWMENT 的总量取**条件注入之前**的家户+经营者钱包 —— 外部注入的货币走独立
    //   FISCAL_ISSUE 审计（见 applyTestConditions），绝不混进"每人禀赋"这条记录。
    Map<CurrencyId, Long> genesisEndowment = genesisEndowmentOf(householdMoney, operators);
    // ★★ W1：把"逐格计价币"归组成**每币一张派生价表**（键 = single-region / currency:<币>；成员格 = 该币的全部格）。
    Map<MarketZoneId, MarketTable> markets = derivedMarketTables(hexesByNumeraire);
    AppliedConditions applied =
        applyTestConditions(mapId, entries, householdStocks, householdMoney, conditions);
    // ★★★ D-032 C3（D2 §5.5）：创世名册节点。P5b1 的 grain_farm 是多角色（operator + hired_laborer）⇒
    //   每个 farm unit 都在上面显式给出名册；economyPayload() 的 requireRostersForMultiRoleUnits 会以
    //   seed-roster-missing:<unit> 拒绝任何多角色 unit 的缺名册。其他单角色 plan 仍由 C2 自动路径补默认名册。
    if (LOG.isDebugEnabled()) {
      io.mosire.simos.util.log.EventLog.channel(LOG)
          .debug(
              io.mosire.simos.util.log.LogEvent.of(
                  "ECONOMY_SEED",
                  io.mosire.simos.app.AppLogSource.SHELL_LIFECYCLE,
                  "profile",
                  profile,
                  "mapId",
                  mapId,
                  "entries",
                  entries.size(),
                  "markets",
                  markets.size(),
                  "householdStocks",
                  householdStocks.size(),
                  "householdMoney",
                  householdMoney.size(),
                  "operators",
                  operators.size(),
                  "debtContracts",
                  applied.debtContracts().size(),
                  "extraMoneyIssuances",
                  applied.extraMoneyIssuances().size(),
                  "genesisEndowment",
                  genesisEndowment));
    }
    return new Seed(
        mapId,
        entries,
        markets,
        householdLocations,
        householdStocks,
        householdMoney,
        operators,
        governments,
        genesisEndowment,
        genesisMoneyMilliPerCapita,
        profile,
        applied.debtContracts(),
        applied.extraMoneyIssuances(),
        productionRosters,
        applied.report());
  }
}
