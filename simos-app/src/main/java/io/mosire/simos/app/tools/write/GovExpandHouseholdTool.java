package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.economy.spi.EconomySetGovServiceCommitmentHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdLaborHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.social.workorder.HouseholdWorkOrder;
import io.mosire.simos.social.workorder.HouseholdWorkOrderBook;
import io.mosire.simos.social.workorder.HouseholdWorkOrderPlan;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.AssignGovPostHandler;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.expandHousehold}（Z3c-2）：招募路径 A —— 新建/扩充 GOV 官吏户（{@code
 * hh-unit:<govUnitId>}）， 再挂岗位 + 写全职 {@code GOV_SERVICE} 承诺</b>（GM + 该 GOV 决策人）。
 *
 * <p>★★ <b>固定批序（一批 = 一条 revision）</b>：
 *
 * <ol>
 *   <li>{@code social.SubmitHouseholdWorkOrder}：目标户缺失时 {@code CREATE_HOUSEHOLD}（location = {@code
 *       UNIT(govUnitId)}）+ 逐来源 {@code TRANSFER_MEMBERS}；目标户已存在时只转移成员。人口变更唯一受理口在 Social；
 *   <li>{@code unit.SetUnitHouseholds}：把目标户编入 GOV 单位的 {@code Unit.households}（与 Social 位置同批一致，S3b
 *       不变量）；
 *   <li>{@code economy.RegisterHousehold}（仅缺经济行时）：补 {@code HouseholdEconomy} 行（0 人口/0
 *       劳动，participation=1000）；
 *   <li>{@code actor.EnsureHouseholdAccount}（幂等）：补零余额账户；
 *   <li>{@code unit.AssignGovPost}：把目标户挂到 role/tierId（只写 posts、不写 staff）；
 *   <li>{@code economy.SetHouseholdLabor}（仅当经济行 laborMilli ≠ 投影劳动）：把经济物化视图对齐到 Social 投影劳动；
 *   <li>{@code economy.SetGovServiceCommitment}：写 {@code kind=GOV_SERVICE} 的全职承诺（= 投影劳动）。
 * </ol>
 *
 * <p>★★ <b>身份/权限</b>：GM 必须显式 {@code govUnitId}；决策人身份派生只能自己所属 GOV（载荷指定别的 GOV ⇒ 越权 {@code
 * REJECTED}），决策人链路是敏感工具 ⇒ 需 GM 审批（{@code AutoApproveGate → ConfirmGate → PendingApprovals}）。 资源断言锚点
 * = 目标 GOV 的 {@code unit:<govUnitId>}。
 *
 * <p>★ <b>不自动</b>：本工具不选人以外的任何自动动作——招多少人/招不招由调用方显式给 {@code count}；缺员只告警（Z3c-1）。
 */
public final class GovExpandHouseholdTool implements AgentTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.expandHousehold}）。 */
  public static final String NAME = "simos.gov.expandHousehold";

  /** 资源声明 = unit 命名空间；断言锚点 = 目标 GOV 单位。 */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final CalendarService calendarService;
  private final String initiator;

  public GovExpandHouseholdTool(
      CoreSimos core, QueryService query, CalendarService calendarService, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "招募路径 A：新建/扩充 GOV 官吏户 hh-unit:<govUnitId> 并挂岗位 + 写 GOV_SERVICE 全职承诺。"
        + "参数 {govUnitId?(GM 必填；决策人省略=自己的 GOV，给出必须等于自己的 GOV), role(SCRIBE|YAMEN|POST), "
        + "tierId?(可选，必须命中该 GOV 计划的 postTiers), count(必填 ≥1；从 GOV 辖区家户抽 MALE+成年档), "
        + "name?(新建户名，缺省 官吏户:<govUnitId>), activity?(行政服务 unit id，缺省按 GOV 唯一 office unit 解析), "
        + "reason(必填), preview?(缺省 true), branch?, expectedRevision?(preview=false 必填)}。"
        + "固定批序：SubmitHouseholdWorkOrder → SetUnitHouseholds → RegisterHousehold? → EnsureHouseholdAccount → "
        + "AssignGovPost → SetHouseholdLabor? → SetGovServiceCommitment；决策人只能自己的 GOV（越权具名拒）且必须过 GM 审批。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSupport.prop("string", "GOV 单位 id（GM 必填；决策人省略 = 自己的 GOV；给出必须等于自己的 GOV，否则越权拒）"));
    props.put("role", ToolSupport.prop("string", "行政角色（SCRIBE|YAMEN|POST）"));
    props.put("tierId", ToolSupport.prop("string", "档位 id（可选；给了必须命中该 GOV 计划的 postTiers）"));
    props.put("count", ToolSupport.prop("integer", "招募人数（≥1；从 GOV 辖区 Social 家户抽 MALE+成年档，不足整条拒）"));
    props.put("name", ToolSupport.prop("string", "新建官吏户的显示名（可选；缺省 官吏户:<govUnitId>）"));
    props.put("activity", ToolSupport.prop("string", "行政服务 unit id（可选；缺省按 GOV 唯一 office unit 解析）"));
    props.put("reason", ToolSupport.prop("string", "扩充原因（必填非空白；进命令载荷）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("role", "count", "reason"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return GOV_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "扩充官吏户 role="
            + args.get("role")
            + " count="
            + args.get("count")
            + " tier="
            + args.getOrDefault("tierId", "-")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + "（决策人只能自己的 GOV，需 GM 审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      SimulationState state = GovToolSupport.stateAt(query, preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      GovToolSupport.GovTarget target =
          GovToolSupport.resolveGov(
              context, state, ToolSupport.optionalText(args, "govUnitId", null));
      GovToolSupport.requireGovWrite(context, target);

      StaffRole role = GovAssignPostsTool.parseRole(ToolSupport.requiredText(args, "role"));
      String tierId = ToolSupport.optionalText(args, "tierId", null);
      GovAssignPostsTool.requireTierIfGiven(state, target, tierId);
      long count = ToolSupport.requiredLong(args, "count");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      String reason = ToolSupport.requiredText(args, "reason");
      String activity = ToolSupport.optionalText(args, "activity", null);
      String profileName = ToolSupport.optionalText(args, "name", "官吏户:" + target.govId().value());
      HouseholdId targetHouseholdId =
          HouseholdId.parse(RaiseUnitPlan.householdIdFor(target.govId().value()));

      SocialData social = ToolSupport.socialData(state);
      UnitState units = ToolSupport.unitState(state);
      EconomyData economy = ToolSupport.economyData(state);
      Household existing = social.households().get(targetHouseholdId);
      requireTargetHouseholdOwnership(units, target, targetHouseholdId, existing);
      long tick = state.meta().timestamp().tick();
      CalendarClock clock = calendarService.clock();

      List<Set<HexCoord>> jurisdictionHexesInOrder =
          jurisdictionHexes(target.unit(), ToolSupport.gameMap(state));
      Set<HouseholdId> excluded = Set.of(targetHouseholdId, target.governmentHousehold());
      HouseholdManpowerAllocator.Allocation allocation =
          allocate(jurisdictionHexesInOrder, social, count, clock, tick, excluded);
      List<HouseholdWorkOrderPlan.Step> steps = new ArrayList<>(allocation.shares().size() + 1);
      if (existing == null) {
        steps.add(
            new HouseholdWorkOrderPlan.CreateHousehold(
                targetHouseholdId,
                new HouseholdLocation.Unit(target.govId().value()),
                new HouseholdProfile(profileName, null, Map.of()),
                new HouseholdVitalRates(List.of())));
      }
      for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
        steps.add(
            new HouseholdWorkOrderPlan.TransferMembers(
                share.householdId(), targetHouseholdId, share.lotId(), share.taken()));
      }
      String orderId =
          "gov-expand:" + target.govId().value() + ":" + tick + ":" + role.name() + ":" + count;
      HouseholdWorkOrder order =
          new HouseholdWorkOrder(
              orderId, targetHouseholdId, reason, "module=gov", new HouseholdWorkOrderPlan(steps));
      SocialData projectedSocial = HouseholdWorkOrderBook.apply(social, order, tick);
      long projectedLabor = projectedSocial.householdLaborMilli(targetHouseholdId, tick, clock);
      if (projectedLabor <= 0L) {
        throw new IllegalArgumentException(
            "内部投影不自洽：转移 " + count + " 个成年男性后目标户劳动仍为 0（Social 劳动权威口径异常，拒绝静默）");
      }
      HouseholdEconomy economyRow = economy.classes().get(targetHouseholdId);
      boolean registerEconomyRow = economyRow == null;
      HexCoord seat = target.at().orElse(null);
      if (registerEconomyRow && seat == null) {
        throw new IllegalArgumentException(
            "GOV 单位 " + target.govId().value() + " 没有当刻有效位置：无法给新官吏户登记 economy 视图落点（先给 GOV 定位）");
      }

      String batchId = UUID.randomUUID().toString();
      RevisionId expected = new RevisionId(expectedRevision);
      List<CommandEnvelope> batch = new ArrayList<>(7);
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              expected,
              SubmitHouseholdWorkOrderHandler.TYPE,
              ToolSupport.json(
                  workOrderPayload(
                      orderId,
                      target.govId().value(),
                      targetHouseholdId,
                      reason,
                      existing == null,
                      profileName,
                      allocation))));
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              expected,
              SetUnitHouseholdsHandler.TYPE,
              ToolSupport.json(unitHouseholdsPayload(target.unit(), targetHouseholdId, reason))));
      if (registerEconomyRow) {
        batch.add(
            GovToolSupport.envelope(
                initiator,
                batchId,
                branch,
                expected,
                EconomyRegisterHouseholdHandler.TYPE,
                ToolSupport.json(
                    registerHouseholdPayload(targetHouseholdId.value(), seat, reason))));
      }
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              expected,
              EnsureHouseholdAccountHandler.TYPE,
              ToolSupport.json(ensureAccountPayload(targetHouseholdId.value(), reason))));
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              expected,
              AssignGovPostHandler.TYPE,
              ToolSupport.json(
                  assignPostPayload(
                      target.govId().value(), targetHouseholdId.value(), role, tierId))));
      if (economyRow == null || economyRow.laborMilli() != projectedLabor) {
        batch.add(
            GovToolSupport.envelope(
                initiator,
                batchId,
                branch,
                expected,
                EconomySetHouseholdLaborHandler.TYPE,
                ToolSupport.json(laborPayload(targetHouseholdId.value(), projectedLabor, reason))));
      }
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              expected,
              EconomySetGovServiceCommitmentHandler.TYPE,
              ToolSupport.json(
                  commitmentPayload(
                      target.govId().value(),
                      targetHouseholdId.value(),
                      projectedLabor,
                      activity,
                      reason))));

      Map<String, Object> view =
          expandView(
              target,
              targetHouseholdId.value(),
              existing == null,
              role,
              tierId,
              count,
              projectedLabor,
              allocation,
              activity,
              batch,
              preview);
      if (preview) {
        return ToolSupport.ok(view);
      }
      return GovToolSupport.submitBatch(core, view, batchId, batch);
    } catch (GovToolSupport.GovRejectedException e) {
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "扩充官吏户失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 目标户所有权/跨单位冲突：它是 GOV 自己的 {@code hh-unit:<govId>}，不得同时在别的 unit 或指向别的 UNIT。 */
  private static void requireTargetHouseholdOwnership(
      UnitState units,
      GovToolSupport.GovTarget target,
      HouseholdId targetHouseholdId,
      Household existing) {
    if (existing != null
        && existing.location() instanceof HouseholdLocation.Unit location
        && !location.unitId().equals(target.govId().value())) {
      throw new IllegalArgumentException(
          "目标官吏户 "
              + targetHouseholdId.value()
              + " 的 Social 位置是 UNIT("
              + location.unitId()
              + ")，不是 GOV "
              + target.govId().value()
              + "：本工具不擅自搬户/改单位归属，先解绑或另建户");
    }
    for (Unit unit : units.units().values()) {
      if (unit.id().equals(target.govId())) {
        continue;
      }
      if (unit.households().contains(targetHouseholdId)) {
        throw new IllegalArgumentException(
            "目标官吏户 "
                + targetHouseholdId.value()
                + " 同时在另一个 unit "
                + unit.id().value()
                + " 的 households 里（同一家户只能属于一个 unit；先解绑，本工具不擅自改归属）");
      }
    }
  }

  /** 辖区 hex（按 jurisdiction 表序）；无 jurisdiction / 空辖区 / 区域查无 ⇒ 具名拒。 */
  private static List<Set<HexCoord>> jurisdictionHexes(Unit unit, GameMap map) {
    var jurisdiction =
        unit.jurisdiction()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "GOV 单位 "
                            + unit.id().value()
                            + " 没有 jurisdiction：没有招募来源；先 unit.SetJurisdiction"));
    if (jurisdiction.taxRatePerMilleByRegion().isEmpty()) {
      throw new IllegalArgumentException(
          "GOV 单位 " + unit.id().value() + " 的 jurisdiction 为空：没有招募来源；先 unit.SetJurisdiction");
    }
    List<Set<HexCoord>> ordered = new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().size());
    for (RegionId regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
      Region region = map.regions().get(regionId);
      if (region == null) {
        throw new IllegalArgumentException(
            "辖区区域在地图里查无: "
                + regionId.value()
                + "（GOV "
                + unit.id().value()
                + " 的 jurisdiction 已坏）");
      }
      ordered.add(region.hexes());
    }
    return List.copyOf(ordered);
  }

  /** 选人：MALE + ADULT（与 GovRecruitPlan 同一 allocator）；不足整条拒并给下一步。 */
  private static HouseholdManpowerAllocator.Allocation allocate(
      List<Set<HexCoord>> jurisdictionHexesInOrder,
      SocialData social,
      long count,
      CalendarClock clock,
      long tick,
      Set<HouseholdId> excluded) {
    try {
      return HouseholdManpowerAllocator.allocateMalesOfAdult(
          social, jurisdictionHexesInOrder, count, clock, tick, excluded);
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException("招募来源不足：" + e.getMessage(), e);
      }
      throw e;
    }
  }

  /** {@code social.SubmitHouseholdWorkOrder} 载荷（与 {@link HouseholdWorkOrderPlan} 的纯投影同源）。 */
  private static Map<String, Object> workOrderPayload(
      String orderId,
      String govUnitId,
      HouseholdId targetHouseholdId,
      String reason,
      boolean create,
      String profileName,
      HouseholdManpowerAllocator.Allocation allocation) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", orderId);
    payload.put("target", targetHouseholdId.value());
    payload.put("reason", reason);
    payload.put("source", Map.of("module", "gov"));
    List<Map<String, Object>> steps = new ArrayList<>(allocation.shares().size() + 1);
    if (create) {
      Map<String, Object> createStep = new LinkedHashMap<>();
      createStep.put("op", "CREATE_HOUSEHOLD");
      createStep.put("household", targetHouseholdId.value());
      Map<String, Object> location = new LinkedHashMap<>();
      location.put("type", "UNIT");
      location.put("unitId", govUnitId);
      createStep.put("location", location);
      createStep.put("profile", Map.of("name", profileName));
      steps.add(createStep);
    }
    for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
      Map<String, Object> transfer = new LinkedHashMap<>();
      transfer.put("op", "TRANSFER_MEMBERS");
      transfer.put("from", share.householdId().value());
      transfer.put("to", targetHouseholdId.value());
      transfer.put("lotId", share.lotId().value());
      transfer.put("count", share.taken());
      steps.add(transfer);
    }
    payload.put("plan", List.copyOf(steps));
    return payload;
  }

  /** {@code unit.SetUnitHouseholds} 载荷：原列表保序 + 目标户（已含则逐值保留）。 */
  private static Map<String, Object> unitHouseholdsPayload(
      Unit unit, HouseholdId targetHouseholdId, String reason) {
    List<String> households = new ArrayList<>(unit.households().size() + 1);
    boolean present = false;
    for (HouseholdId household : unit.households()) {
      households.add(household.value());
      if (household.equals(targetHouseholdId)) {
        present = true;
      }
    }
    if (!present) {
      households.add(targetHouseholdId.value());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unit.id().value());
    payload.put("households", List.copyOf(households));
    payload.put("reason", reason);
    return payload;
  }

  /** {@code economy.RegisterHousehold} 载荷：官吏户按 full participation 登记（0 人口/0 劳动，后续工单填人）。 */
  private static Map<String, Object> registerHouseholdPayload(
      String householdId, HexCoord at, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", householdId);
    payload.put("q", at.q());
    payload.put("r", at.r());
    payload.put("participationPerMille", 1000);
    payload.put("reason", reason);
    return payload;
  }

  /** {@code actor.EnsureHouseholdAccount} 载荷（幂等）。 */
  private static Map<String, Object> ensureAccountPayload(String householdId, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", householdId);
    payload.put("reason", reason);
    return payload;
  }

  /** {@code unit.AssignGovPost} 载荷。 */
  private static Map<String, Object> assignPostPayload(
      String govUnitId, String householdId, StaffRole role, String tierId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", govUnitId);
    payload.put("household", householdId);
    payload.put("role", role.name());
    if (tierId != null) {
      payload.put("tierId", tierId);
    }
    return payload;
  }

  /** {@code economy.SetHouseholdLabor} 载荷（只改 laborMilli）。 */
  private static Map<String, Object> laborPayload(
      String householdId, long laborMilli, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", householdId);
    payload.put("laborMilli", laborMilli);
    payload.put("reason", reason);
    return payload;
  }

  /** {@code economy.SetGovServiceCommitment} 载荷。 */
  private static Map<String, Object> commitmentPayload(
      String govUnitId, String householdId, long laborMilli, String activity, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", govUnitId);
    payload.put("householdId", householdId);
    payload.put("laborMilli", laborMilli);
    if (activity != null) {
      payload.put("activity", activity);
    }
    payload.put("reason", reason);
    return payload;
  }

  private static Map<String, Object> expandView(
      GovToolSupport.GovTarget target,
      String householdId,
      boolean created,
      StaffRole role,
      String tierId,
      long count,
      long projectedLaborMilli,
      HouseholdManpowerAllocator.Allocation allocation,
      String activity,
      List<CommandEnvelope> batch,
      boolean preview) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("householdId", householdId);
    view.put("created", created);
    view.put("role", role.name());
    view.put("tierId", tierId);
    view.put("count", count);
    view.put("projectedLaborMilli", projectedLaborMilli);
    view.put("activity", activity);
    view.put("available", allocation.available());
    List<Map<String, Object>> sources = new ArrayList<>(allocation.shares().size());
    for (HouseholdManpowerAllocator.ManpowerShare share : allocation.shares()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("householdId", share.householdId().value());
      row.put("lotId", share.lotId().value());
      row.put("taken", share.taken());
      row.put("hex", share.hex() == null ? null : ToolSupport.hexCoord(share.hex()));
      sources.add(row);
    }
    view.put("sources", List.copyOf(sources));
    view.put("commandsPreview", GovToolSupport.commandsPreview(batch));
    return view;
  }
}
