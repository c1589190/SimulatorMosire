package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.household.HouseholdUnitConsistency;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.time.GovServiceFlowFeed;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetCategory;
import io.mosire.simos.gov.GovBudgetLine;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.gov.GovServiceFlow;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>{@code simos.gov.info}（Z3c-2）：GOV 行政运行只读读口</b>（GM + 该 GOV 决策人，决策人视野收窄到自己 GOV）。
 *
 * <p>★★ <b>内容</b>：编制计划（两维计划量 / 三档目录 / 静态修正 / k）vs 实际承诺供给；两维满足率 / 两维最终效率 / 总效率 （{@code
 * GovOfficeState} 当日读数）；承诺明细（家户 / 档位 / 劳动）；岗位 / 档位与 {@code projectedStaff}；国库 / 预算 （授权政策 + 行政俸禄结算读数
 * + 告警 evidence）；本 tick 服务流量（{@link GovServiceFlowFeed}，读不到具名 {@code unavailable}）；{@code
 * crisisSignals} 的 {@code ADMIN_*} 告警。
 *
 * <p>★★ <b>不另造第二套口径</b>：供给复用 {@link GovernmentServiceLaborBridge}（Z3b 唯一桥）；国库节点直接嵌 {@link
 * ApiViews#economyGovernment} 的既有投影；承诺 / 岗位直接读状态；流量读 {@link GovServiceFlowFeed}。读不到的项一律具名 {@code
 * unavailable}，绝不填 0。
 *
 * <p>★★ <b>视野收窄</b>：决策人身份（{@code decision-maker:<id>} → {@link Affiliation.Gov}）只能读自己的 GOV；载荷若请求别的
 * {@code govUnitId} ⇒ 具名 {@code REJECTED}。资源断言：{@code unit:<govId>} + 当刻座位的 {@code
 * map:<mapId>/hex/<q>_<r>} / {@code social:<q>_<r>} / {@code actor:<q>_<r>}（GM 面 unlimited；决策人
 * {@code GovScope} 只授自己单位与座位）。
 */
public final class GovInfoTool implements AgentTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.info}）。 */
  public static final String NAME = "simos.gov.info";

  /** 声明读面：本视图组装要用到 map/social/unit/actor/economy 五片（逐格/座位断言见 {@link #requireVisible}）。 */
  private static final ResourceManifest RESOURCES =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.READ_ONLY));

  private final QueryService query;
  private final String mapId;

  public GovInfoTool(QueryService query, String mapId) {
    this.query = Objects.requireNonNull(query, "query");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "只读查看 GOV 行政运行：编制计划 vs 实际承诺供给、两维满足率/两维最终效率/总效率（GovOfficeState 当日读数）、"
        + "承诺明细（家户/档位/劳动）、岗位/档位与 projectedStaff、国库（复用 ApiViews.economyGovernment 投影）、"
        + "预算政策（类别 min/cap/顺序 + 官吏工资规则）与行政俸禄结算读数、ADMIN_* 告警、本 tick 服务流量"
        + "（GovServiceFlowFeed，读不到具名 unavailable）。参数 {govUnitId?(GM 省略=全部 GOV；决策人省略=自己的 GOV，"
        + "给出必须等于自己的 GOV), branch?, revision?}。决策人视野收窄到自己 GOV；越权请求具名拒。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSupport.prop("string", "GOV 单位 id（可选；GM 省略 = 全部 GOV；决策人省略 = 自己的 GOV，给出必须等于自己的 GOV）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put("revision", ToolSupport.prop("integer", "时间轴 revision（可选；缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return RESOURCES;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String requestedGovId = ToolSupport.optionalText(args, "govUnitId", null);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long revision = ToolSupport.optionalLong(args, "revision");
      SimulationState state =
          query.stateAt(
              revision == null
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(revision)));
      long tick = state.meta().timestamp().tick();
      GovState govState = govState(state);
      UnitState units = ToolSupport.unitState(state);
      SocialData social = ToolSupport.socialData(state);
      EconomyData economy = ToolSupport.economyData(state);
      GameMap map = ToolSupport.gameMap(state);

      List<UnitId> visible = visibleGovernments(context, state, units, requestedGovId);
      List<Map<String, Object>> governments = new ArrayList<>(visible.size());
      for (UnitId govId : visible) {
        Unit unit = units.units().get(govId);
        Optional<HexCoord> at = units.effectivePosition(govId, state.meta().timestamp());
        requireVisible(context, govId, at);
        governments.add(
            governmentInfo(state, map, govState, economy, social, units, unit, govId, at, tick));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("activated", true);
      view.put("tick", tick);
      view.put(
          "scope", "决策人 = 自己所属 GOV；GM = 指定 GOV 或全部 GOV。国库节点直接嵌入 ApiViews.economyGovernment 的既有投影");
      view.put("governmentCount", governments.size());
      view.put("governments", List.copyOf(governments));
      return ToolSupport.ok(view);
    } catch (InfoRejected e) {
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（不折成 TOOL_ERROR）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "gov.info 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** gov 切片提取（缺切片/类型不符 = 装配故障）。 */
  private static GovState govState(SimulationState state) {
    Snapshot snapshot =
        state.module("gov").orElseThrow(() -> new IllegalStateException("state 里没有 gov 切片（装配故障）"));
    if (snapshot instanceof GovSnapshot govSnapshot) {
      return govSnapshot.state();
    }
    throw new IllegalStateException(
        "state 的 gov 切片不是 GovSnapshot: " + snapshot.getClass().getName());
  }

  /** 可见 GOV 集合（决策人 = 自己；GM = 指定或全部 GOV，按 id 稳定序）。 */
  private static List<UnitId> visibleGovernments(
      ToolContext context, SimulationState state, UnitState units, String requestedGovId) {
    Optional<String> decisionMakerId = DecisionCallerFactory.decisionMakerIdOf(context.identity());
    if (decisionMakerId.isPresent()) {
      DecisionMaker maker;
      try {
        maker =
            ToolSupport.sdState(state)
                .decisionMakers()
                .get(new DecisionMakerId(decisionMakerId.get()));
      } catch (IllegalArgumentException e) {
        throw new InfoRejected("调用者身份里的决策人 id 非法: " + decisionMakerId.get());
      }
      if (maker == null) {
        throw new InfoRejected("调用者身份不是本世界已知的决策人: " + decisionMakerId.get());
      }
      if (!(maker.affiliation() instanceof Affiliation.Gov gov)) {
        throw new InfoRejected(
            "只有 GOV 归属的决策人可查看 gov.info（调用者 "
                + decisionMakerId.get()
                + " 归属: "
                + maker.affiliation()
                + "）");
      }
      if (requestedGovId != null && !requestedGovId.equals(gov.govUnit().value())) {
        throw new InfoRejected(
            "越权 GOV：请求 " + requestedGovId + "，但调用者只能查看自己所属 GOV " + gov.govUnit().value());
      }
      return List.of(gov.govUnit());
    }
    if (requestedGovId != null) {
      UnitId requested = UnitId.parse(requestedGovId);
      Unit unit = units.units().get(requested);
      if (unit == null || !(unit.module().orElse(null) instanceof GovernmentFormation)) {
        throw new IllegalArgumentException("GOV 单位不存在或不是 GOV: " + requestedGovId);
      }
      return List.of(requested);
    }
    List<UnitId> all = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation) {
        all.add(unit.id());
      }
    }
    all.sort(Comparator.comparing(UnitId::value));
    return List.copyOf(all);
  }

  /** 资源断言：单位 + 当刻座位的 map/social/actor 逐项（GM unlimited；决策人 GovScope 只授自己与座位）。 */
  private void requireVisible(ToolContext context, UnitId govId, Optional<HexCoord> at) {
    List<ResourceId> required = new ArrayList<>(4);
    required.add(ToolSupport.resourceUnit(govId.value()));
    at.ifPresent(
        seat -> {
          required.add(ToolSupport.resourceHex(mapId, seat.q(), seat.r()));
          required.add(ToolSupport.resourceSocial(seat.q(), seat.r()));
          required.add(
              ResourceId.of(ToolSupport.ACTOR_NAMESPACE, ResourcePaths.actor(seat.q(), seat.r())));
        });
    ToolSupport.requireAll(context, Operation.READ, required);
  }

  /** 单个 GOV 的完整读数（每个可选块自身 fail-soft：算不出/没数据 ⇒ 具名 unavailable）。 */
  private Map<String, Object> governmentInfo(
      SimulationState state,
      GameMap map,
      GovState govState,
      EconomyData economy,
      SocialData social,
      UnitState units,
      Unit unit,
      UnitId govId,
      Optional<HexCoord> at,
      long tick) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("unitId", govId.value());
    info.put("q", at.map(HexCoord::q).orElse(null));
    info.put("r", at.map(HexCoord::r).orElse(null));
    if (unit == null || !(unit.module().orElse(null) instanceof GovernmentFormation formation)) {
      info.put("unavailable", "not-a-gov-unit");
      return info;
    }
    info.put("level", formation.level().name());
    info.put("superiorGov", formation.superiorGov().map(UnitId::value).orElse(null));
    info.put(
        "governmentHousehold",
        GovernmentHouseholdResolver.requireGovernmentHousehold(unit, govId.value()).value());

    GovAdministrationPlan plan = govState.administrationPlanOrDefault(govId);
    info.put(
        "administrationPlanSource",
        govState.administrationPlans().containsKey(govId) ? "explicit" : "neutral-default");
    info.put("administrationPlan", planView(plan));

    GovBudgetPolicy policy = govState.budgetPolicyOrDefault(govId);
    info.put(
        "budgetPolicySource",
        govState.budgetPolicies().containsKey(govId) ? "explicit" : "neutral-default");
    info.put("budgetPolicy", policyView(policy));

    info.put("posts", postsView(formation.governmentPostsOfHousehold()));
    // ★ Z3d：外部岗位单列（键不要求 ∈ Unit.households；外部户保留原单位/位置，只承接行政任务）。
    info.put("externalPosts", postsView(formation.externalPosts()));
    info.put("projectedStaff", projectedStaffView(economy, social, units, govId, tick));
    info.put("efficiency", efficiencyView(govState.offices().get(govId)));
    info.put("supply", supplyView(economy, govId, formation, plan, tick));
    info.put("committedLabor", committedLaborView(economy, govId, formation, plan, tick));
    info.put("budgetSettlement", budgetSettlementView(govState.offices().get(govId), policy));
    info.put("treasury", treasuryView(state, govId));
    info.put("alerts", alertsView(economy, map, unit, at));
    info.put("serviceFlow", serviceFlowView(tick, govId));
    return info;
  }

  /** 编制计划视图（与 setEstablishment 的视图同形；档位保序）。 */
  private static Map<String, Object> planView(GovAdministrationPlan plan) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("securityPlannedLaborMilli", plan.securityPlannedLaborMilli());
    view.put("paperworkPlannedLaborMilli", plan.paperworkPlannedLaborMilli());
    List<Map<String, Object>> tiers = new ArrayList<>(plan.postTiers().size());
    for (GovPostTier tier : plan.postTiers()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tierId", tier.tierId());
      row.put("securityWeightPerMille", tier.securityWeightPerMille());
      row.put("paperworkWeightPerMille", tier.paperworkWeightPerMille());
      tiers.add(row);
    }
    view.put("postTiers", List.copyOf(tiers));
    view.put("securitySupplyStaticModifierPerMille", plan.securitySupplyStaticModifierPerMille());
    view.put("paperworkSupplyStaticModifierPerMille", plan.paperworkSupplyStaticModifierPerMille());
    view.put("securityDemandStaticModifierPerMille", plan.securityDemandStaticModifierPerMille());
    view.put("paperworkDemandStaticModifierPerMille", plan.paperworkDemandStaticModifierPerMille());
    view.put("supernumerarySqrtCoefficient", plan.supernumerarySqrtCoefficient());
    return view;
  }

  /** 预算政策视图（类别表保序 + 工资规则）。 */
  private static Map<String, Object> policyView(GovBudgetPolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    List<Map<String, Object>> categories = new ArrayList<>(policy.orderedCategories().size());
    for (GovBudgetLine line : policy.orderedCategories()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", line.category().name());
      row.put("minPerCycle", line.minPerCycle());
      row.put("capPerCycle", line.capPerCycle());
      categories.add(row);
    }
    view.put("orderedCategories", List.copyOf(categories));
    Map<String, Object> salary = new LinkedHashMap<>();
    salary.put(
        "grainMilliPerCommittedHour", policy.officialSalaryRule().grainMilliPerCommittedHour());
    salary.put(
        "silverMilliPerCommittedHour", policy.officialSalaryRule().silverMilliPerCommittedHour());
    view.put("officialSalaryRule", salary);
    return view;
  }

  /** 岗位视图（保序；tierId 空 = legacy/未指派；Z3d 起内部与外部两张表共用本形状）。 */
  private static List<Map<String, Object>> postsView(
      Map<HouseholdId, GovernmentPostOfHousehold> posts) {
    List<Map<String, Object>> rows = new ArrayList<>(posts.size());
    for (GovernmentPostOfHousehold post : posts.values()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("householdId", post.householdId().value());
      row.put("role", post.role().name());
      row.put("tierId", post.hasTier() ? post.tierId() : null);
      row.put("level", post.level().name());
      row.put("headOfGovernment", post.headOfGovernment());
      rows.add(row);
    }
    return List.copyOf(rows);
  }

  /** {@code projectedStaff}（复用 {@link HouseholdUnitConsistency} 的承诺→人数当量唯一投影）。 */
  private static Map<String, Object> projectedStaffView(
      EconomyData economy, SocialData social, UnitState units, UnitId govId, long tick) {
    Map<String, Object> view = new LinkedHashMap<>();
    try {
      Map<String, Long> projection =
          HouseholdUnitConsistency.staffHouseholdProjection(economy, social, units, tick);
      Map<String, Long> byRole = new LinkedHashMap<>();
      String prefix = govId.value() + ":";
      for (Map.Entry<String, Long> entry : projection.entrySet()) {
        if (entry.getKey().startsWith(prefix)) {
          byRole.put(entry.getKey().substring(prefix.length()), entry.getValue());
        }
      }
      view.put("available", true);
      view.put("byRole", byRole);
      view.put("source", "GOV_SERVICE 承诺 ÷ 标准劳动定额（全职人数当量）");
    } catch (RuntimeException e) {
      view.put("available", false);
      view.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    return view;
  }

  /** 两维满足率 / 两维最终效率 / 总效率（当日 GovOfficeState 读数；缺 ⇒ unavailable）。 */
  private static Map<String, Object> efficiencyView(GovOfficeState office) {
    Map<String, Object> view = new LinkedHashMap<>();
    if (office == null) {
      view.put("available", false);
      view.put("reason", "no-gov-office-state");
      return view;
    }
    view.put("available", true);
    view.put("officeStateTick", office.tick());
    view.put("securityCoveragePerMille", office.securityCoveragePerMille());
    view.put("paperworkCoveragePerMille", office.paperworkCoveragePerMille());
    view.put("securityEfficiencyPerMille", office.securityEfficiencyPerMille());
    view.put("paperworkEfficiencyPerMille", office.paperworkEfficiencyPerMille());
    view.put("efficiencyPerMille", office.efficiencyPerMille());
    view.put("bonusPerMilleLegacy", office.bonusPerMille());
    view.put("source", "GovOfficeState（结算日读数；不是次日输入）");
    return view;
  }

  /** 实际供给（复用 Z3b 唯一桥；契约故障 ⇒ 具名 unavailable，不填 0）。 */
  private static Map<String, Object> supplyView(
      EconomyData economy,
      UnitId govId,
      GovernmentFormation formation,
      GovAdministrationPlan plan,
      long tick) {
    Map<String, Object> view = new LinkedHashMap<>();
    try {
      GovernmentServiceLaborBridge.Supply supply =
          GovernmentServiceLaborBridge.supply(economy, govId, formation, plan, tick);
      view.put("available", true);
      view.put("securityLaborMilli", supply.securityLaborMilli());
      view.put("paperworkLaborMilli", supply.paperworkLaborMilli());
      view.put("source", "GovernmentServiceLaborBridge（承诺→两维供给唯一桥）");
    } catch (RuntimeException e) {
      view.put("available", false);
      view.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    return view;
  }

  /** 承诺明细：逐户劳动 + 岗位/档位（有承诺无岗位照实发 hasPost=false；bridge 不计入供给）。 */
  private static Map<String, Object> committedLaborView(
      EconomyData economy,
      UnitId govId,
      GovernmentFormation formation,
      GovAdministrationPlan plan,
      long tick) {
    Map<String, Object> view = new LinkedHashMap<>();
    Map<HouseholdId, Long> committed;
    try {
      committed = GovernmentServiceLaborBridge.committedLaborByHousehold(economy, govId, tick);
    } catch (RuntimeException e) {
      view.put("available", false);
      view.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
      return view;
    }
    List<Map<String, Object>> households = new ArrayList<>(committed.size());
    Map<HouseholdId, GovernmentPostOfHousehold> postsOfGov = formation.allPosts(); // ★ Z3d：内外同权
    for (Map.Entry<HouseholdId, Long> entry : committed.entrySet()) {
      HouseholdId household = entry.getKey();
      GovernmentPostOfHousehold post = postsOfGov.get(household);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("householdId", household.value());
      row.put("laborMilli", entry.getValue());
      row.put("hasPost", post != null);
      row.put(
          "postScope",
          post == null
              ? null
              : (formation.externalPosts().containsKey(household) ? "external" : "internal"));
      row.put("role", post == null ? null : post.role().name());
      row.put("tierId", post == null || !post.hasTier() ? null : post.tierId());
      row.put("tierKnown", post == null || !post.hasTier() ? null : tierKnown(plan, post.tierId()));
      households.add(row);
    }
    view.put("available", true);
    view.put("households", List.copyOf(households));
    return view;
  }

  private static boolean tierKnown(GovAdministrationPlan plan, String tierId) {
    for (GovPostTier tier : plan.postTiers()) {
      if (tier.tierId().equals(tierId)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 预算结算读数：唯一持久化的执行读数是 {@code GovOfficeState} 的行政俸禄六表（评估/实付/缺口）；其余类别的逐日授权/实付/缺口是 进程内派生件 ⇒ 具名
   * unavailable（告警 evidence 另在 {@code alerts} 里给事实）。
   */
  private static Map<String, Object> budgetSettlementView(
      GovOfficeState office, GovBudgetPolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put(
        "policyOrder", policy.orderedCategories().stream().map(l -> l.category().name()).toList());
    if (office == null) {
      view.put("available", false);
      view.put("reason", "no-gov-office-state");
      return view;
    }
    view.put("available", true);
    view.put("settledTick", office.tick());
    Map<String, Object> stipend = new LinkedHashMap<>();
    stipend.put("assessedGoods", goodsView(office.lastAssessedGoods()));
    stipend.put("paidGoods", goodsView(office.lastPaidGoods()));
    stipend.put("shortfallGoods", goodsView(office.lastShortfallGoods()));
    stipend.put("assessedMoney", moneyView(office.lastAssessedMoney()));
    stipend.put("paidMoney", moneyView(office.lastPaidMoney()));
    stipend.put("shortfallMoney", moneyView(office.lastShortfallMoney()));
    view.put("adminStipend", stipend);
    List<Map<String, Object>> unavailable = new ArrayList<>();
    for (GovBudgetLine line : policy.orderedCategories()) {
      if (line.category() == GovBudgetCategory.ADMIN_STIPEND) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", line.category().name());
      row.put("reason", "no-persistent-per-category-execution-read");
      row.put("detail", "逐日授权/实付/缺口是预算执行桥的进程内派生件；缺口事实见 alerts 的 ADMIN_BUDGET_SHORTFALL evidence");
      unavailable.add(row);
    }
    view.put("unavailableCategories", List.copyOf(unavailable));
    return view;
  }

  private static Map<String, Object> goodsView(Map<CommodityId, Long> goods) {
    Map<String, Object> view = new LinkedHashMap<>();
    goods.forEach((commodity, amount) -> view.put(commodity.value(), amount));
    return view;
  }

  private static Map<String, Object> moneyView(Map<CurrencyId, Long> money) {
    Map<String, Object> view = new LinkedHashMap<>();
    money.forEach((currency, amount) -> view.put(currency.value(), amount));
    return view;
  }

  /** 国库节点：直接嵌 {@link ApiViews#economyGovernment} 里该 GOV 的既有投影（不另造第二套口径）。 */
  private static Map<String, Object> treasuryView(SimulationState state, UnitId govId) {
    Map<String, Object> view = new LinkedHashMap<>();
    String expectedId = GovernmentIds.ofUnit(govId.value()).value();
    Object raw = ApiViews.economyGovernment(state).get("governments");
    if (raw instanceof List<?> governments) {
      for (Object candidate : governments) {
        if (candidate instanceof Map<?, ?> node && expectedId.equals(node.get("id"))) {
          view.put("available", true);
          view.put("projection", node);
          view.put("source", "ApiViews.economyGovernment");
          return view;
        }
      }
    }
    view.put("available", false);
    view.put("reason", "gov-not-registered-in-economy");
    return view;
  }

  /** ADMIN_* 告警：座位 + 辖区 hex 上的 crisisSignals（同格同 kind 最新由写侧覆盖）。 */
  private static List<Map<String, Object>> alertsView(
      EconomyData economy, GameMap map, Unit unit, Optional<HexCoord> at) {
    Set<HexCoord> visibleHexes = new LinkedHashSet<>();
    at.ifPresent(visibleHexes::add);
    unit.jurisdiction()
        .ifPresent(
            jurisdiction -> {
              for (RegionId regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
                Region region = map.regions().get(regionId);
                if (region != null) {
                  visibleHexes.addAll(region.hexes());
                }
              }
            });
    List<Map<String, Object>> alerts = new ArrayList<>();
    for (HexCrisisSignal signal : economy.crisisSignals().values()) {
      if (!signal.kind().name().startsWith("ADMIN_") || !visibleHexes.contains(signal.hex())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", signal.id().value());
      row.put("hex", ToolSupport.hexCoord(signal.hex()));
      row.put("kind", signal.kind().name());
      row.put("severity", signal.severity());
      row.put("day", signal.day());
      row.put("reason", signal.reason());
      row.put("evidence", new LinkedHashMap<>(signal.evidence()));
      alerts.add(row);
    }
    alerts.sort(
        Comparator.comparingLong((Map<String, Object> row) -> (Long) row.get("day"))
            .thenComparing(row -> (String) row.get("kind")));
    return List.copyOf(alerts);
  }

  /** 本 tick 服务流量（{@link GovServiceFlowFeed}；读不到 = 具名 unavailable，绝不填 0）。 */
  private Map<String, Object> serviceFlowView(long tick, UnitId govId) {
    Map<String, Object> view = new LinkedHashMap<>();
    Optional<Map<UnitId, GovServiceFlow>> feed = GovServiceFlowFeed.last(mapId, tick);
    if (feed.isEmpty() || !feed.get().containsKey(govId)) {
      view.put("available", false);
      view.put("reason", GovServiceFlowFeed.UNAVAILABLE_REASON);
      return view;
    }
    GovServiceFlow flow = feed.get().get(govId);
    view.put("available", true);
    view.put("tick", flow.tick());
    view.put("securityCommittedLaborMilli", flow.securityCommittedLaborMilli());
    view.put("paperworkCommittedLaborMilli", flow.paperworkCommittedLaborMilli());
    view.put("securityEffectiveLaborMilli", flow.securityEffectiveLaborMilli());
    view.put("paperworkEffectiveLaborMilli", flow.paperworkEffectiveLaborMilli());
    view.put("securityDemandLaborMilli", flow.securityDemandLaborMilli());
    view.put("paperworkDemandLaborMilli", flow.paperworkDemandLaborMilli());
    view.put("securityServiceOutputMilli", flow.securityServiceOutputMilli());
    view.put("paperworkServiceOutputMilli", flow.paperworkServiceOutputMilli());
    view.put("securityConsumedByEfficiencyMilli", flow.securityConsumedByEfficiencyMilli());
    view.put("paperworkConsumedByEfficiencyMilli", flow.paperworkConsumedByEfficiencyMilli());
    view.put("securityExpiredUnusedMilli", flow.securityExpiredUnusedMilli());
    view.put("paperworkExpiredUnusedMilli", flow.paperworkExpiredUnusedMilli());
    return view;
  }

  /** 决策人身份/越权的具名拒（改参数修不了 ⇒ REJECTED，与 BAD_REQUEST 分开）。 */
  private static final class InfoRejected extends RuntimeException {

    InfoRejected(String message) {
      super(message);
    }
  }
}
