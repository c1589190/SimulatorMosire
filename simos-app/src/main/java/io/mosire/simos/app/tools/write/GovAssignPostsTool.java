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
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.spi.EconomySetGovServiceCommitmentHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdLaborHandler;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.spi.AssignGovPostHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.assignPosts}（Z3c-2）：把 GOV 自家户挂到档位/角色并写全职承诺</b>（GM + 该 GOV 决策人）。
 *
 * <p>★★ <b>命令组合（一批 = 一条 revision，固定顺序）</b>：
 *
 * <ol>
 *   <li>{@code economy.SetHouseholdLabor}（仅当经济行 laborMilli ≠ 当前 Social 权威劳动）：把经济物化视图对齐到 Social
 *       当日劳动，避免"承诺 ≤ 陈旧 laborMilli"误拒；下一轮推进仍由 Social 投影覆盖（不是第二权威）；
 *   <li>{@code unit.AssignGovPost}：写 {@code governmentPostsOfHousehold}（只写 posts、永不写 staff；C4
 *       一处真相）；
 *   <li>{@code economy.SetGovServiceCommitment}：写 {@code kind=GOV_SERVICE} 的全职承诺（不可缩、最高优先级）。
 * </ol>
 *
 * <p>★★ <b>身份与权限</b>：GM 必须显式 {@code govUnitId}；决策人身份派生只能自己所属 GOV（载荷指定别的 GOV ⇒ 越权 {@code
 * REJECTED}），且决策人链路是敏感工具 ⇒ 走 GM 审批（{@code AutoApproveGate → ConfirmGate → PendingApprovals}）。
 * 资源断言锚点 = 目标 GOV 的 {@code unit:<govUnitId>}（{@code GovScope} 只授自己单位）。
 *
 * <p>★ <b>承诺小时口径</b>：缺省 = 该户当前 Social 权威劳动（{@code SocialData.householdLaborMilli}，全职）；显式 {@code
 * laborMilli} 只能在 (0, 当前劳动] 内（超上限 ⇒ 具名 BAD_REQUEST）。官吏户必须已在 {@code Unit.households} 里（先走 {@code
 * simos.gov.expandHousehold} / {@code simos.unit.assignHousehold}），本工具不擅自编户、不改位置。
 */
public final class GovAssignPostsTool implements AgentTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.assignPosts}）。 */
  public static final String NAME = "simos.gov.assignPosts";

  /** 资源声明 = unit 命名空间；断言锚点 = 目标 GOV 单位。 */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final CalendarService calendarService;
  private final String initiator;

  public GovAssignPostsTool(
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
    return "把 GOV 自家户挂到行政岗位/档位并写 GOV_SERVICE 全职承诺（一批 = 一条 revision："
        + "economy.SetHouseholdLabor? → unit.AssignGovPost → economy.SetGovServiceCommitment）。"
        + "参数 {govUnitId?(GM 必填；决策人省略=自己的 GOV，给出必须等于自己的 GOV), householdId(必填，必须已在 GOV 的 "
        + "Unit.households 且 economy/Social 都有行), role(SCRIBE|YAMEN|POST), tierId?(可选，必须命中该 GOV 计划的 "
        + "postTiers), laborMilli?(可选，缺省=该户当前 Social 权威劳动；必须 ∈(0,当前劳动]), activity?(可选，缺省按 GOV 唯一 "
        + "office unit 解析), reason(必填), preview?(缺省 true), branch?, expectedRevision?(preview=false 必填)}。"
        + "决策人只能自己的 GOV（越权具名拒）且必须过 GM 审批；不擅自编户/改位置/改 staff。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSupport.prop("string", "GOV 单位 id（GM 必填；决策人省略 = 自己的 GOV；给出必须等于自己的 GOV，否则越权拒）"));
    props.put(
        "householdId",
        ToolSupport.prop(
            "string", "岗位家户 id（必填；必须已在 GOV 的 Unit.households 里，且 economy classes/Social 都存在）"));
    props.put("role", ToolSupport.prop("string", "行政角色（SCRIBE|YAMEN|POST）"));
    props.put(
        "tierId",
        ToolSupport.prop(
            "string", "档位 id（可选；给了必须命中该 GOV GovAdministrationPlan.postTiers；省略 = legacy 未指派档位）"));
    props.put(
        "laborMilli",
        ToolSupport.prop(
            "integer", "承诺劳动（毫小时/tick；可选，缺省 = 该户当前 Social 劳动；必须 >0 且 ≤ 当前 Social 劳动）"));
    props.put(
        "activity",
        ToolSupport.prop(
            "string", "行政服务 unit id（可选；缺省按 operator=hh-gov-<govUnitId> 的唯一 office unit 解析）"));
    props.put("reason", ToolSupport.prop("string", "指派原因（必填非空白；进命令载荷）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("householdId", "role", "reason"));
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
        "指派行政岗位 household="
            + args.get("householdId")
            + " role="
            + args.get("role")
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
    return runAssignment(core, query, calendarService, initiator, context);
  }

  /** 指派实现（本类唯一入口；岗位户必须已在 {@code Unit.households}，本工具不擅自改单位归属）。 */
  static ToolResult runAssignment(
      CoreSimos core,
      QueryService query,
      CalendarService calendarService,
      String initiator,
      ToolContext context) {
    String tool = NAME;
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

      HouseholdId householdId = HouseholdId.parse(ToolSupport.requiredText(args, "householdId"));
      StaffRole role = parseRole(ToolSupport.requiredText(args, "role"));
      String tierId = ToolSupport.optionalText(args, "tierId", null);
      requireTierIfGiven(state, target, tierId);
      String activity = ToolSupport.optionalText(args, "activity", null);
      String reason = ToolSupport.requiredText(args, "reason");

      SocialData social = ToolSupport.socialData(state);
      EconomyData economy = ToolSupport.economyData(state);
      if (!social.households().containsKey(householdId)) {
        throw new IllegalArgumentException(
            "家户不存在: " + householdId.value() + "（先建户/对齐 Social 家户表，不猜、不新建）");
      }
      if (!target.unit().households().contains(householdId)) {
        throw new IllegalArgumentException(
            "岗位家户 "
                + householdId.value()
                + " 不在 GOV 单位 "
                + target.govId().value()
                + " 的 Unit.households 里：先走 simos.gov.expandHousehold（路径 A 新建/扩充官吏户）"
                + "或 simos.unit.assignHousehold 编入（本工具不擅自改单位归属）");
      }
      HouseholdEconomy economyRow = economy.classes().get(householdId);
      if (economyRow == null) {
        throw new IllegalArgumentException(
            "economy 缺家户行: "
                + householdId.value()
                + "（先 economy.RegisterHousehold / simos.unit.assignHousehold 的组合批补齐）");
      }
      long tick = state.meta().timestamp().tick();
      long socialLabor = social.householdLaborMilli(householdId, tick, calendarService.clock());
      if (socialLabor <= 0L) {
        throw new IllegalArgumentException(
            "家户 " + householdId.value() + " 当前 Social 劳动为 0：无劳动可承诺（先补人口/成员）");
      }
      Long laborArg = ToolSupport.optionalLong(args, "laborMilli");
      long committedLaborMilli = laborArg == null ? socialLabor : laborArg;
      if (committedLaborMilli <= 0L) {
        throw new IllegalArgumentException("laborMilli 必须 > 0: " + committedLaborMilli);
      }
      if (committedLaborMilli > socialLabor) {
        throw new IllegalArgumentException(
            "承诺劳动 "
                + committedLaborMilli
                + " 超过该户当前 Social 权威劳动 "
                + socialLabor
                + "（官吏为全职，承诺 ≤ 劳动预算；先补人/调 laborMilli）");
      }
      String batchId = UUID.randomUUID().toString();
      List<CommandEnvelope> batch = new ArrayList<>(3);
      if (economyRow.laborMilli() != socialLabor) {
        batch.add(
            GovToolSupport.envelope(
                initiator,
                batchId,
                branch,
                new RevisionId(expectedRevision),
                EconomySetHouseholdLaborHandler.TYPE,
                ToolSupport.json(laborPayload(householdId.value(), socialLabor, reason))));
      }
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              new RevisionId(expectedRevision),
              AssignGovPostHandler.TYPE,
              ToolSupport.json(
                  assignPostPayload(target.govId().value(), householdId.value(), role, tierId))));
      batch.add(
          GovToolSupport.envelope(
              initiator,
              batchId,
              branch,
              new RevisionId(expectedRevision),
              EconomySetGovServiceCommitmentHandler.TYPE,
              ToolSupport.json(
                  commitmentPayload(
                      target.govId().value(),
                      householdId.value(),
                      committedLaborMilli,
                      activity,
                      reason))));

      Map<String, Object> view =
          assignmentView(
              tool,
              target,
              householdId.value(),
              role,
              tierId,
              committedLaborMilli,
              socialLabor,
              economyRow.laborMilli(),
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
          "TOOL_ERROR", tool + " 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST（未知 ⇒ 具名拒，不静默回落）。 */
  static StaffRole parseRole(String roleText) {
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** {@code tierId} 非空时必须命中该 GOV 计划的 postTiers（unit 看不见 gov 计划，跨切片校验在 app 侧）。 */
  static void requireTierIfGiven(
      SimulationState state, GovToolSupport.GovTarget target, String tierId) {
    if (tierId == null) {
      return;
    }
    for (GovPostTier tier :
        GovToolSupport.govState(state).administrationPlanOrDefault(target.govId()).postTiers()) {
      if (tier.tierId().equals(tierId)) {
        return;
      }
    }
    throw new IllegalArgumentException(
        "tierId 不在 GOV "
            + target.govId().value()
            + " 的编制计划 postTiers 里: "
            + tierId
            + "（先 simos.gov.setEstablishment 设档位目录，或改用合法档位）");
  }

  /** 经济物化视图对齐载荷（只改 laborMilli；participation 逐值保留）。 */
  private static Map<String, Object> laborPayload(
      String householdId, long laborMilli, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", householdId);
    payload.put("laborMilli", laborMilli);
    payload.put("reason", reason);
    return payload;
  }

  /** {@code unit.AssignGovPost} 载荷（tierId 非空才带；只写 posts，不写 staff）。 */
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

  /**
   * {@code economy.SetGovServiceCommitment} 载荷（activity 省略 = 由 handler 按 GOV 唯一 office unit 解析）。
   */
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

  private static Map<String, Object> assignmentView(
      String tool,
      GovToolSupport.GovTarget target,
      String householdId,
      StaffRole role,
      String tierId,
      long committedLaborMilli,
      long socialLaborMilli,
      long economyLaborBefore,
      String activity,
      List<CommandEnvelope> batch,
      boolean preview) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("tool", tool);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("householdId", householdId);
    view.put("role", role.name());
    view.put("tierId", tierId);
    view.put("activity", activity);
    view.put("committedLaborMilli", committedLaborMilli);
    view.put("socialLaborMilli", socialLaborMilli);
    view.put("economyLaborBefore", economyLaborBefore);
    view.put(
        "economyLaborAfter",
        economyLaborBefore == socialLaborMilli ? economyLaborBefore : socialLaborMilli);
    view.put("commandsPreview", GovToolSupport.commandsPreview(batch));
    return view;
  }
}
