package io.mosire.simos.gov.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.spi.AssignExternalGovPostHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.openPostsToMarket}（Z3d，2026-10-23 用户裁定 B 路 V1）：把外部家户挂到 GOV 外部岗位并写 {@code
 * GOV_SERVICE} 承诺</b>（GM + 该 GOV 决策人；决策人【直写】走审批链，【提议】{@code simos.ga.packet.propose} 零审批 —— 见下）。
 *
 * <pre>
 * V1 = 显式选户：外部家户（Social 家户表 + economy classes 行都存在、且<b>不在</b>该 GOV 的 Unit.households 里）
 *   → unit.AssignExternalGovPost（只写 GovernmentFormation.externalPosts）
 *   → economy.SetGovServiceCommitment（kind=GOV_SERVICE，不可缩、最高优先级）
 * 绝不改该户的 Unit.households / Social 位置（保留原归属）；工资沿用既有 ADMIN_SALARY 链
 *   （GovSalaryRuleBridge 按 GOV_SERVICE 承诺逐户发薪，不新增付款链）。
 * </pre>
 *
 * <p>★★ <b>命令组合（一批 = 一条 revision，固定顺序）</b>：
 *
 * <ol>
 *   <li>{@code economy.SetHouseholdLabor}（仅当经济行 laborMilli ≠ 当前 Social 权威劳动）：把经济物化视图对齐到 Social
 *       当日劳动，避免"承诺 ≤ 陈旧 laborMilli"误拒；下一轮推进仍由 Social 投影覆盖（不是第二权威）；
 *   <li>{@code unit.AssignExternalGovPost}：写 {@code externalPosts}（不改 Unit.households / 位置 /
 *       staff）；
 *   <li>{@code economy.SetGovServiceCommitment}：写 {@code kind=GOV_SERVICE} 的全职承诺。
 * </ol>
 *
 * <p>★★ <b>身份与权限</b>：GM 必须显式 {@code govUnitId}；决策人<b>直写</b>时身份派生只能自己所属 GOV（载荷指定别的 GOV ⇒ 越权 {@code
 * REJECTED}），且决策人<b>直写</b>是敏感工具 ⇒ 走 GM 审批（{@code AutoApproveGate → ConfirmGate →
 * PendingApprovals}）。 ★ <b>但「需 GM 审批」只对直写路径成立</b>：本工具在 {@code ProposalCatalog} 清单里，决策人改走 {@code
 * simos.ga.packet.propose} 是<b>零审批、不阻塞</b>（2026-10-15 批 3b §11.2 B3 实测：propose 成功且 asks 不增）。资源断言 锚点
 * = 目标 GOV 的 {@code unit:<govUnitId>}（{@code GovScope} 只授自己单位）。
 *
 * <p>★★ <b>但「决策人可省」只在直写路径成立</b>（2026-10-15 实测）：{@code simos.ga.packet.propose} 的真预览（{@code
 * ProposalCatalog.preview}）与 {@code gm.packet.execute} 的规划（{@code
 * DecisionEffectExecutor.planCall}）都用 {@code AgentIdentity.external()} ⇒ 走 GM 分支、{@code govUnitId}
 * 必填，省略 ⇒ {@code BAD_REQUEST}「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」（★ 逐字引用该报文的**首句**；正文其后还有「★
 * 键位按工具分叉」一段）。
 *
 * <p>★ <b>承诺小时口径</b>：缺省 = 该户当前 Social 权威劳动（全职）；显式 {@code laborMilli} 只能在 (0, 当前劳动] 内（超上限 ⇒ 具名
 * BAD_REQUEST）。
 *
 * <p>★ <b>V2/V3 不做</b>：队列/应募/竞价原语；本工具只做显式选户。
 */
public final class GovOpenPostsToMarketTool implements SimosTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.openPostsToMarket}）。 */
  public static final String NAME = "simos.gov.openPostsToMarket";

  /** 资源声明 = unit 命名空间；断言锚点 = 目标 GOV 单位。 */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final GovToolSupport support;

  public GovOpenPostsToMarketTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "把外部家户挂到 GOV 外部岗位并写 GOV_SERVICE 承诺（一批 = 一条 revision："
        + "economy.SetHouseholdLabor? → unit.AssignExternalGovPost → economy.SetGovServiceCommitment）。"
        + "参数 {govUnitId?(★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有【决策人直写】可省 = 自己的 GOV，"
        + "给出必须等于自己的 GOV), householdId(必填，Social/economy "
        + "都有行且不在该 GOV 的 Unit.households 里；保留原单位/位置), role(SCRIBE|YAMEN|POST), tierId?(可选，必须命中该 GOV "
        + "计划的 postTiers), level?(可选，缺省=该 GOV 编制层级), headOfGovernment?(缺省 false), laborMilli?(可选，缺省=该户"
        + "当前 Social 权威劳动；必须 ∈(0,当前劳动]), activity?(可选，缺省按 GOV 唯一 office unit 解析), reason(必填), "
        + "preview?(缺省 true), branch?, expectedRevision?(preview=false 必填)}。"
        + "外部户不改 Unit.households / Social 位置；工资沿用 ADMIN_SALARY 链；决策人只能自己的 GOV（越权具名拒）；★ 审批只对"
        + "【决策人直写】是必须的（该路径停在待批、阻塞等 GM，超时 APPROVAL_DENIED）；【决策人提议 "
        + "simos.ga.packet.propose】零审批、不阻塞（本工具在可提议清单里）；"
        + "V1 只做显式选户（队列/应募/竞价留 V2/V3）。"
        + "★ 身份与路径（2026-10-15 实测）：只有【决策人直写】可省 govUnitId（省略 = 自己的 GOV，给出别人的 GOV ⇒ 具名 "
        + "REJECTED）；【GM 面调用】与【simos.ga.packet.propose 提议】都必须显式给 —— propose 的真预览（ProposalCatalog.preview）"
        + "与 gm.packet.execute 的规划（DecisionEffectExecutor.planCall）都走 AgentIdentity.external()（GM 分支），"
        + "省略 ⇒ BAD_REQUEST「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSchema.prop(
            "string",
            "GOV 单位 id（★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有决策人直写可省 = 自己的 GOV，"
                + "给出别的 GOV ⇒ 越权拒）"));
    props.put(
        "householdId",
        ToolSchema.prop(
            "string",
            "外部家户 id（必填；Social 家户表 + economy classes 行都必须存在，且不在该 GOV 的 Unit.households 里；不改其归属/位置）"));
    props.put("role", ToolSchema.prop("string", "行政角色（SCRIBE|YAMEN|POST）"));
    props.put(
        "tierId",
        ToolSchema.prop(
            "string", "档位 id（可选；给了必须命中该 GOV GovAdministrationPlan.postTiers；省略 = legacy 未指派档位）"));
    props.put("level", ToolSchema.prop("string", "层级（可选：CENTRAL|PROVINCE；省略 = 该 GOV 编制自身层级）"));
    props.put("headOfGovernment", ToolSchema.prop("boolean", "是否政府首长（可选；省略 = false）"));
    props.put(
        "laborMilli",
        ToolSchema.prop("integer", "承诺劳动（毫小时/tick；可选，缺省 = 该户当前 Social 劳动；必须 >0 且 ≤ 当前 Social 劳动）"));
    props.put(
        "activity",
        ToolSchema.prop(
            "string", "行政服务 unit id（可选；缺省按 operator=hh-gov-<govUnitId> 的唯一 office unit 解析）"));
    props.put("reason", ToolSchema.prop("string", "开放岗位原因（必填非空白；进命令载荷）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("householdId", "role", "reason"));
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
        "开放行政岗位给外部家户 household="
            + args.get("householdId")
            + " role="
            + args.get("role")
            + " tier="
            + args.getOrDefault("tierId", "-")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + "（决策人直写：外部户保留原单位/位置，只能自己的 GOV，需 GM 审批；"
            + "本工具在可提议清单里，改走 simos.ga.packet.propose 零审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    return runOpenPosts(context);
  }

  /** 开放岗位实现（本类唯一入口；外部户必须存在且不在 GOV Unit.households 里，本工具不改归属/位置）。 */
  ToolResult runOpenPosts(ToolContext context) {
    String tool = NAME;
    try {
      Map<String, Object> args = context.arguments();
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      // ★ D3 内部参数：不进 jsonSchema / 工具描述，只被系统执行器注入（见 GovBatchViews#planOnly）。
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      SimulationState state = support.stateAt(preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      GovToolSupport.GovTarget target =
          support.resolveGov(context, state, ToolSchema.optionalText(args, "govUnitId", null));
      support.requireGovWrite(context, target);

      HouseholdId householdId = HouseholdId.parse(ToolSchema.requiredText(args, "householdId"));
      StaffRole role = GovAssignPostsTool.parseRole(ToolSchema.requiredText(args, "role"));
      String tierId = ToolSchema.optionalText(args, "tierId", null);
      GovAssignPostsTool.requireTierIfGiven(support.economy(), state, target, tierId);
      String levelText = ToolSchema.optionalText(args, "level", null);
      GovernmentLevel level = levelText == null ? null : parseLevel(levelText);
      Boolean headOfGovernment = ToolSchema.optionalBoolean(args, "headOfGovernment").orElse(null);
      String activity = ToolSchema.optionalText(args, "activity", null);
      String reason = ToolSchema.requiredText(args, "reason");

      SocialData social = GovToolReads.socialData(state);

      if (!social.households().containsKey(householdId)) {
        throw new IllegalArgumentException(
            "外部家户不存在: " + householdId.value() + "（Social 家户表没有该行；先建户/对齐 Social，不猜、不新建）");
      }
      Long economyLaborMilli = support.economy().classLaborMilli(state, householdId);
      if (economyLaborMilli == null) {
        throw new IllegalArgumentException(
            "economy 缺家户行: "
                + householdId.value()
                + "（先 economy.RegisterHousehold / simos.unit.assignHousehold 的组合批补齐）");
      }
      if (target.unit().households().contains(householdId)) {
        throw new IllegalArgumentException(
            "家户 "
                + householdId.value()
                + " 已在 GOV 单位 "
                + target.govId().value()
                + " 的 Unit.households 里：这是内部官吏户/岗位语义，请用 simos.gov.assignPosts（内部岗位）；"
                + "simos.gov.openPostsToMarket 只挂保留原单位/位置的外部家户");
      }
      if (target.formation().governmentPostsOfHousehold().containsKey(householdId)) {
        throw new IllegalArgumentException(
            "家户 "
                + householdId.value()
                + " 已在 GOV 单位 "
                + target.govId().value()
                + " 的内部 householdPosts 里：同一家户不得同时在内部与外部岗位；请用 simos.gov.assignPosts（内部岗位）");
      }

      long tick = state.meta().timestamp().tick();
      long socialLabor = support.manpower().householdLaborMilli(state, householdId, tick);
      if (socialLabor <= 0L) {
        throw new IllegalArgumentException(
            "家户 " + householdId.value() + " 当前 Social 劳动为 0：无劳动可承诺（先补人口/成员）");
      }
      Long laborArg = ToolSchema.optionalLong(args, "laborMilli");
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
                + "（外部岗位同样受承诺 ≤ 劳动预算约束；先补人/调 laborMilli）");
      }

      String batchId = UUID.randomUUID().toString();
      List<ToolWritePort.Request> batch = new ArrayList<>(3);
      if (economyLaborMilli != socialLabor) {
        batch.add(
            GovToolSupport.request(
                support.commandTypes().setHouseholdLabor(),
                ToolResults.json(laborPayload(householdId.value(), socialLabor, reason))));
      }
      batch.add(
          GovToolSupport.request(
              AssignExternalGovPostHandler.TYPE,
              ToolResults.json(
                  externalPostPayload(
                      target.govId().value(),
                      householdId.value(),
                      role,
                      tierId,
                      level,
                      headOfGovernment,
                      reason))));
      batch.add(
          GovToolSupport.request(
              support.commandTypes().setGovServiceCommitment(),
              ToolResults.json(
                  commitmentPayload(
                      target.govId().value(),
                      householdId.value(),
                      committedLaborMilli,
                      activity,
                      reason))));

      Map<String, Object> view =
          openPostsView(
              tool,
              target,
              householdId.value(),
              role,
              tierId,
              level,
              headOfGovernment,
              committedLaborMilli,
              socialLabor,
              economyLaborMilli,
              activity,
              batch,
              preview);
      if (preview) {
        return ToolResults.ok(view);
      }
      if (planOnly) {
        // ★★ 只规划不提交：把执行器要的那批命令原样交回（组批与 preview=false 同源，一个字节都不写）。
        return ToolResults.ok(GovBatchViews.planOnly(batch));
      }
      return support.submitBatch(view, batchId, branch, expectedRevision, batch);
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

  /** 显式层级文本 → 词表；未知串具名拒（不静默回落默认）。 */
  static GovernmentLevel parseLevel(String text) {
    try {
      return GovernmentLevel.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("level 不是合法层级（CENTRAL|PROVINCE）: " + text, e);
    }
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

  /**
   * {@code unit.AssignExternalGovPost} 载荷：只写外部岗位表；{@code tierId}/{@code level}/{@code
   * headOfGovernment} 缺省 字段不带（由 handler 取既有值/缺省），但本工具只在显式给了才带，保持载荷最小。
   */
  private static Map<String, Object> externalPostPayload(
      String govUnitId,
      String householdId,
      StaffRole role,
      String tierId,
      GovernmentLevel level,
      Boolean headOfGovernment,
      String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", govUnitId);
    payload.put("householdId", householdId);
    payload.put("role", role.name());
    if (tierId != null) {
      payload.put("tierId", tierId);
    }
    if (level != null) {
      payload.put("level", level.name());
    }
    if (headOfGovernment != null) {
      payload.put("headOfGovernment", headOfGovernment);
    }
    payload.put("reason", reason);
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

  private static Map<String, Object> openPostsView(
      String tool,
      GovToolSupport.GovTarget target,
      String householdId,
      StaffRole role,
      String tierId,
      GovernmentLevel level,
      Boolean headOfGovernment,
      long committedLaborMilli,
      long socialLaborMilli,
      long economyLaborBefore,
      String activity,
      List<ToolWritePort.Request> batch,
      boolean preview) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("tool", tool);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("householdId", householdId);
    view.put("external", true);
    view.put("householdAffiliationUnchanged", true);
    view.put("householdLocationUnchanged", true);
    view.put("role", role.name());
    view.put("tierId", tierId);
    view.put("level", level == null ? null : level.name());
    view.put("headOfGovernment", headOfGovernment);
    view.put("committedLaborMilli", committedLaborMilli);
    view.put("socialLaborMilli", socialLaborMilli);
    view.put("economyLaborBefore", economyLaborBefore);
    view.put(
        "economyLaborAfter",
        economyLaborBefore == socialLaborMilli ? economyLaborBefore : socialLaborMilli);
    view.put("activity", activity);
    view.put("salaryChain", "ADMIN_SALARY（GovSalaryRuleBridge；不新增付款链）");
    view.put("commandsPreview", GovToolSupport.commandsPreview(batch));
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
