package io.mosire.simos.app.production;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyBoundaryView;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyProductionDefinitions;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.ExpectedProfitBook;
import io.mosire.simos.economy.time.ExpectedProfitBook.RosterHouseholdIncome;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;

/**
 * ★★ <b>{@link ProductionConsumerPort} 的宿主实现（组合根）</b> —— D-032 B4 / §13.14.5 第 1 条、裁定十三。
 *
 * <p>★★ <b>为什么实现必须在 app</b>：本端口要同时回答两类事实，而它们各在一半世界里：
 *
 * <ul>
 *   <li><b>economy 半边</b>：家户行（"某格是否已激活"）、{@code assetShares} / {@code relations} / 生产方式六类引用者 /
 *       阶层结构；
 *   <li><b>production 半边</b>：<b>生产实例</b>（{@code IndustryReferenceCounts#units} 的唯一权威 —— B2b 已把
 *       economy 的 {@code units} 组件删除；"某格是否已激活"的实例那一维同理）。
 * </ul>
 *
 * <p>⇒ production 看不见 economy 实现（enforcer），economy 的切片快照也看不见 production；只有组合根同时认识两边 （这正是 §3.2 "由
 * app 装配，或 economy 快照实现只读 contract" 里被裁定十三选中的那一支）。
 *
 * <p>★★ <b>无状态、每次调用现取 state</b>：本类不持有任何切片引用 ⇒ 同一份实例可跨 revision 复用，读到的永远是调用那一刻的 当刻值（I13：不得跨 revision
 * 陈旧）。装配只有一处：组合根把它 {@code new} 出来，经构造器注入给用它的 production handler / 工具（{@code Shell} / {@code
 * GovWorldBootstrap} / {@code ThreePowersGovBootstrap} / {@code SimosToolSource}）。
 *
 * <p>★★ <b>判据逐条承接 P0（不新增、不放宽、也不丢）</b>：
 *
 * <ul>
 *   <li>{@code industryReferences.units}：旧 P0 数的是 {@code EconomyData.units} 里 {@code industry() ==
 *       id} 的实例 ⇒ <b>现值 = production {@code instances()} 里 {@code plan == id} 的实例</b>（同一语义，换了权威）；
 *   <li>{@code industryReferences.assetShares} / {@code relations}：仍读 economy（{@code relations}
 *       经其实例的 industry 间接命中 —— 实例不存在的那条关系不计，与 P0 的 {@code unit == null} 跳过逐字一致）；
 *   <li>{@code modeReferences}：六类与旧 {@code EconomyGmAdjustments:396} 的遍历逐类对齐（含 {@code
 *       modeTransitions} 的 {@code (from)} / {@code (to)} 后缀）；
 *   <li>{@code isHexEconomicallyActive}：{@code EconomyData.meta} 非空（未激活世界恒 false）∧（家户行所在的格 ∪
 *       <b>落在此格的生产实例</b>）。★ 旧 P0 判据还含"economy 侧的产业定义键"一维：那份副本已按裁定十五删除，本实现 <b>不把它换成</b>"production
 *       定义键" —— 那会把更多格判成已激活（= 放宽创建守卫）；实例那一维才是 B2b 后"这格上真有个生产单位"的 同一件事。
 * </ul>
 *
 * <p>★ <b>装配故障当场炸</b>：缺切片 / 切片类型不符 ⇒ {@link IllegalStateException}（ERROR 事件 + 抛），<b>不是</b> {@code
 * IllegalArgumentException} —— 后者会被 production 命令层折成"业务拒绝 INFO"，把装配错说成用户错（§一.9 日志纪律）。
 */
public final class AppProductionConsumerPort implements ProductionConsumerPort {

  /** 契约/装配故障的日志门面（组合根级事件 ⇒ 与 Shell 同源）。 */
  private static final Logger LOG = AppLog.shell();

  @Override
  public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(hexKey, "hexKey");
    EconomyData economy = economyOf(state);
    if (economy.meta().isEmpty()) {
      return false; // 未激活世界：没有家户行、也没有经济状态（与 P0 的 requireActivatedHex 同判据）
    }
    if (economy.occupiedHexKeys().contains(hexKey)) {
      return true;
    }
    for (ProductionProcess instance : productionStateOf(state).instances().values()) {
      if (hexKey.equals(requireInstanceHexKey(instance))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public IndustryReferenceCounts industryReferences(SimulationState state, IndustryId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    EconomyData economy = economyOf(state);
    Map<ProductionUnitId, ProductionProcess> instances = productionStateOf(state).instances();
    // ★★★ B4（§13.14.5 第 1 条②）：units 维按 B2b 后的新权威重算 —— 数 production 的实例，不数已删的 economy units。
    int units = 0;
    for (ProductionProcess instance : instances.values()) {
      if (instance.industry().equals(id)) {
        units++;
      }
    }
    int assetShares = 0;
    for (OwnershipStake share : economy.assetShares().values()) {
      if (share.industry().equals(id)) {
        assetShares++;
      }
    }
    // ★ relations 经它的实例的 industry 间接命中（实例不存在 ⇒ 不计；与 P0 的 `unit == null` 跳过逐字一致）。
    int relations = 0;
    for (ProductionUnitId activity : economy.relations().keySet()) {
      ProductionProcess instance = instances.get(activity);
      if (instance != null && instance.industry().equals(id)) {
        relations++;
      }
    }
    return new IndustryReferenceCounts(units, assetShares, relations);
  }

  @Override
  public ModeReferenceReport modeReferences(SimulationState state, ProductionModeId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    EconomyData economy = economyOf(state);
    List<String> classStructures = new ArrayList<>();
    for (ClassStructure structure : economy.classStructures().values()) {
      if (structure.modeId().equals(id)) {
        classStructures.add(structure.id().value());
      }
    }
    List<String> classPositions = new ArrayList<>();
    for (ProductionRole position : economy.classPositions().values()) {
      if (position.modeId().equals(id)) {
        classPositions.add(position.id().value());
      }
    }
    List<String> productionOrganizations = new ArrayList<>();
    for (ProductionEnterprise enterprise : economy.productionOrganizations().values()) {
      if (enterprise.modeId().equals(id)) {
        productionOrganizations.add(enterprise.id().value());
      }
    }
    List<String> assetRules = new ArrayList<>();
    for (AssetRule rule : economy.assetRules().values()) {
      if (rule.modeId().equals(id)) {
        assetRules.add(rule.id().value());
      }
    }
    List<String> modeTransitions = new ArrayList<>();
    for (ModeTransition transition : economy.modeTransitions().values()) {
      if (transition.fromModeId().equals(id)) {
        modeTransitions.add(transition.id().value() + "(from)");
      }
      if (transition.toModeId().equals(id)) {
        modeTransitions.add(transition.id().value() + "(to)");
      }
    }
    List<String> pledges = new ArrayList<>();
    for (Pledge pledge : economy.pledges().values()) {
      if (pledge.modeId().equals(id)) {
        pledges.add(pledge.id().value());
      }
    }
    return new ModeReferenceReport(
        classStructures,
        classPositions,
        productionOrganizations,
        assetRules,
        modeTransitions,
        pledges);
  }

  /** economy 切片（装配故障 ⇒ ERROR + 抛，不降级）。 */
  private static EconomyData economyOf(SimulationState state) {
    Snapshot slice = state.module("economy").orElse(null);
    if (!(slice instanceof EconomySnapshot snapshot)) {
      throw assemblyFault(
          "state 里没有 economy 切片（或类型不符）: " + (slice == null ? "null" : slice.getClass().getName()));
    }
    return snapshot.data();
  }

  /** production 切片的实例只读投影（装配故障 ⇒ ERROR + 抛，不降级）。 */
  private static ProductionStateLookup productionStateOf(SimulationState state) {
    Snapshot slice = state.module("production").orElse(null);
    if (!(slice instanceof ProductionStateLookup instances)) {
      throw assemblyFault(
          "state 里没有 production 切片（或它未实现 ProductionStateLookup）: "
              + (slice == null ? "null" : slice.getClass().getName()));
    }
    return instances;
  }

  /**
   * 实例属于哪一格（{@link ProductionProcess#hexKey()} 是唯一拼写点；与 {@code ClearRegionDefinitionsHandler}
   * 同一条判据）。
   *
   * <p>★ 判不出来 = 状态自相矛盾（B3 起四处构造点全部 fail-closed ⇒ 不可构造）⇒ 契约故障，不静默当作"不在这一格"。
   */
  private static String requireInstanceHexKey(ProductionProcess instance) {
    return instance
        .hexKey()
        .orElseThrow(
            () ->
                assemblyFault(
                    "生产实例地点不可判（状态自相矛盾）: unit="
                        + instance.id().value()
                        + ", plan="
                        + instance.plan().value()));
  }

  /** 契约/装配故障：ERROR 具名事件 + 抛 {@link IllegalStateException}（不许被折成业务拒绝）。 */
  private static IllegalStateException assemblyFault(String reason) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "PRODUCTION_CONSUMER_PORT_ASSEMBLY_FAULT",
                AppLogSource.SHELL_LIFECYCLE,
                "port",
                AppProductionConsumerPort.class.getSimpleName(),
                "reason",
                reason));
    return new IllegalStateException("ProductionConsumerPort: " + reason);
  }

  // ── ★★★ D-032 C3：名册只读消费（唯一调用 economy 的 ExpectedProfitBook.rosterIncomes 的 app 点）────────

  /**
   * ★★★ <b>按 unit 输出名册与逐户预计所得（只读）</b> —— {@code ExpectedProfitBook.rosterIncomes(...)} 的 <b>app
   * 侧唯一消费点</b>（D2 §8.1/§8.2）。
   *
   * <p>★★ <b>不复制任何公式</b>：角色份额 / 两层分配 / 产品估值 / 工资应收应付 / 投入负担全部由 economy 的 {@code rosterIncomes}
   * 一处算出；本类只负责**装配输入**（state → 定义/实例/名册/账户会话/市场表/只读 lookup） 与**形状化输出**。不写任何状态、不产生变更集。
   *
   * <p>★★ <b>D4-Ib2b 起</b>：资产占用与工资报价都经 {@link AppProductionLookups} 注入同一 revision 的真实只读实现；
   * 读口只装配输入，不复写工资/分配/估值公式。账户/占用不可用时仍由 economy 具名拒绝（{@code asset-occupancy-unavailable} / {@code
   * wage-quote-missing:<role>}），<b>不按 0 冒充</b>。
   *
   * @param state 当刻状态（只读）
   * @param units 要读的名册 unit（无名册的 unit 自动跳过；顺序按 unit id 稳定排序）
   * @return 每个有名册 unit 一行（空表 = 这些 unit 还没有名册）
   */
  public List<Map<String, Object>> rosterReadouts(
      SimulationState state, Collection<ProductionUnitId> units) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(units, "units");
    EconomyData economy = economyOf(state);
    if (economy.meta().isEmpty()) {
      return List.of(); // 未激活世界：没有名册，也没有可读的经济事实（不是错误）
    }
    ProductionDefinitionLookup definitions = EconomyProductionDefinitions.of(state);
    // ★ 只读视图装配：EconomyData + production 定义来源 ⇒ EconomyDayView（C2 的 BoundaryView 是唯一装配形态）。
    EconomyBoundaryView boundary = EconomyBoundaryView.of(economy, definitions);
    Map<ProductionUnitId, ProductionRoster> rosters = boundary.productionRosters();
    if (rosters.isEmpty()) {
      return List.of();
    }
    Map<ProductionUnitId, ProductionProcess> instances =
        EconomyProductionDefinitions.stateOf(state).instances();
    ActorData actors = actorOf(state);
    ProductionRuntimeEngine runtime = new ProductionRuntimeEngine(productionDataOf(state));
    long day = state.meta().timestamp().tick();
    RevisionId revision = AppProductionLookups.expectedRevision(state);
    List<ProductionUnitId> ordered = new ArrayList<>(units);
    ordered.sort((left, right) -> left.value().compareTo(right.value()));
    List<Map<String, Object>> readouts = new ArrayList<>();
    for (ProductionUnitId unitId : ordered) {
      ProductionRoster roster = rosters.get(unitId);
      if (roster == null) {
        continue; // 单角色 unit 在 C2 自动名册生成前没有行；"还没有名册"不是故障
      }
      ProductionProcess instance = instances.get(unitId);
      if (instance == null) {
        throw assemblyFault("名册指名了不存在的生产实例: unit=" + unitId.value());
      }
      IndustryDefinitionView plan =
          definitions
              .industry(instance.plan())
              .orElseThrow(
                  () ->
                      assemblyFault(
                          "名册实例的方案定义不存在: unit="
                              + unitId.value()
                              + " plan="
                              + instance.plan().value()));
      AccountSession accounts = readOnlyAccounts(economy, actors, roster);
      MarketTable market = marketTableOf(economy, instance);
      // ★★★ D4-Ib2b：真实资产占用 + W2a posting 报价；同一份 accounts 也是工资可支付库存读口的账户来源。
      AppProductionLookups.Lookups lookups =
          AppProductionLookups.forEconomy(economy, definitions, runtime, accounts, revision, day);
      Set<ProductionLocation> existingSettlementLocations =
          ExpectedProfitBook.existingSettlementLocationsOf(
              boundary, instances, HouseholdActors.of(roster.organizerHousehold()));
      List<RosterHouseholdIncome> incomes =
          ExpectedProfitBook.rosterIncomes(
              plan,
              instance,
              roster,
              economy.assetShares(),
              accounts,
              economy.relations(),
              market,
              lookups.wageQuotes(),
              Map.of(),
              lookups.assetOccupancy(),
              revision,
              economy.productionWagePostings(),
              existingSettlementLocations,
              day,
              runtime);
      readouts.add(rosterReadoutView(roster, incomes));
    }
    return Collections.unmodifiableList(readouts);
  }

  /**
   * 名册只读视图（唯一形状拼写点）：参与者 + 逐户预计所得。
   *
   * <p>★ 键序固定（参与者按名册保序；商品表按 id 升序）⇒ 同一份状态的输出是内容的纯函数。
   */
  private static Map<String, Object> rosterReadoutView(
      ProductionRoster roster, List<RosterHouseholdIncome> incomes) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unit", roster.unit().value());
    view.put("plan", roster.plan().value());
    List<Map<String, Object>> participants = new ArrayList<>();
    for (ProductionParticipant participant : roster.participants()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("household", participant.household().value());
      row.put("role", participant.role().value());
      row.put("organizer", participant.organizer());
      row.put("plannedLaborMilliPerDay", participant.plannedLaborMilliPerDay());
      row.put("rightsStakes", sortedStakeIds(participant.rightsStakes()));
      row.put("configuredWeight", participant.configuredWeight());
      participants.add(Collections.unmodifiableMap(row));
    }
    view.put("participants", Collections.unmodifiableList(participants));
    List<Map<String, Object>> incomeViews = new ArrayList<>(incomes.size());
    for (RosterHouseholdIncome income : incomes) {
      incomeViews.add(incomeView(income));
    }
    view.put("expectedIncomes", Collections.unmodifiableList(incomeViews));
    return Collections.unmodifiableMap(view);
  }

  private static Map<String, Object> incomeView(RosterHouseholdIncome income) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("unit", income.unit().value());
    row.put("plan", income.plan().value());
    row.put("household", income.household().value());
    row.put("role", income.role().value());
    row.put("organizer", income.organizer());
    row.put("productShareMilli", sortedCommodityAmounts(income.productShareMilli()));
    row.put("productShareValueMilli", income.productShareValueMilli());
    row.put("wageReceivableMilli", income.wageReceivableMilli());
    row.put("wagePayableMilli", income.wagePayableMilli());
    row.put("inputBurdenMilli", income.inputBurdenMilli());
    row.put("expectedNetMilli", income.expectedNetMilli());
    row.put("negative", income.negative());
    row.put("unpricedCommodities", sortedCommodityIds(income.unpricedCommodities()));
    row.put("rejection", income.rejection().orElse(null));
    return Collections.unmodifiableMap(row);
  }

  private static List<String> sortedStakeIds(
      List<io.mosire.simos.economy.api.id.AssetShareId> stakes) {
    List<String> ids = new ArrayList<>(stakes.size());
    for (io.mosire.simos.economy.api.id.AssetShareId stake : stakes) {
      ids.add(stake.value());
    }
    Collections.sort(ids);
    return Collections.unmodifiableList(ids);
  }

  private static Map<String, Long> sortedCommodityAmounts(Map<CommodityId, Long> amounts) {
    Map<String, Long> sorted = new TreeMap<>();
    for (Map.Entry<CommodityId, Long> entry : amounts.entrySet()) {
      sorted.put(entry.getKey().value(), entry.getValue());
    }
    return Collections.unmodifiableMap(sorted);
  }

  private static List<String> sortedCommodityIds(Set<CommodityId> commodities) {
    List<String> ids = new ArrayList<>(commodities.size());
    for (CommodityId commodity : commodities) {
      ids.add(commodity.value());
    }
    Collections.sort(ids);
    return Collections.unmodifiableList(ids);
  }

  /**
   * 只读账户会话：只为名册参与户登记当刻账（缺账的户跳过 —— 读口不因一个未播种账户而整页 500； 预计所得里该户的输入按 0 读，与 {@code
   * ProductionProbeInputs.inputsOf} 的 null 口径一致）。
   *
   * <p>★ 不调 {@code OwnershipBooks.loadAccountSession}（那会要求**全部**家户都有账，对读口过强）；
   * 本方法只注册名册相关户、只读、同一线程内即用即弃。
   */
  private static AccountSession readOnlyAccounts(
      EconomyData economy, ActorData actors, ProductionRoster roster) {
    AccountSession session = AccountSession.empty();
    for (HouseholdId household : roster.households()) {
      HouseholdEconomy row = economy.classes().get(household);
      HouseholdInventory inventory = actors.accounts().get(new HouseholdAccountKey(household));
      if (row == null || inventory == null) {
        continue;
      }
      session.registerHousehold(
          household,
          row.view().hex(),
          inventory.balances(),
          inventory.money(),
          inventory.frozenBalances(),
          inventory.frozenMoney());
    }
    return session;
  }

  /** unit 所在格的市场表（没有 ⇒ null = 读口不估值；不把"没有市场"读成 0 价）。 */
  private static MarketTable marketTableOf(EconomyData economy, ProductionProcess unit) {
    if (!unit.location().isSpecified()) {
      return null;
    }
    String hexKey = unit.location().value();
    for (MarketTable table : economy.marketTables().values()) {
      for (io.mosire.simos.map.hex.HexCoord hex : table.hexes()) {
        if (hexKey.equals(hex.q() + "_" + hex.r())) {
          return table;
        }
      }
    }
    return null;
  }

  /** actor 切片（装配故障 ⇒ ERROR + 抛，不降级）。 */
  private static ActorData actorOf(SimulationState state) {
    Snapshot slice = state.module("actor").orElse(null);
    if (!(slice instanceof ActorSnapshot snapshot)) {
      throw assemblyFault(
          "state 里没有 actor 切片（或类型不符）: " + (slice == null ? "null" : slice.getClass().getName()));
    }
    return snapshot.data();
  }

  /** production 切片内部状态（只读；D-032 B1 起由组合根本地读，不交给 economy）。 */
  private static ProductionData productionDataOf(SimulationState state) {
    Snapshot slice = state.module("production").orElse(null);
    if (!(slice instanceof ProductionSnapshot snapshot)) {
      throw assemblyFault(
          "state 里没有 production 切片（或类型不符）: "
              + (slice == null ? "null" : slice.getClass().getName()));
    }
    return snapshot.state();
  }
}
