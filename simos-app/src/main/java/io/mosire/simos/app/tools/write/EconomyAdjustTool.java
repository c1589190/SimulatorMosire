package io.mosire.simos.app.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.spi.EconomyGmAdjustHandler;
import io.mosire.simos.economy.spi.EconomyGmAdjustments;
import io.mosire.simos.util.json.SimosObjectMapper;
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
 * ★★ <b>{@code simos.economy.adjust}（E6b）：GM 经济调整窄写工具</b> —— {@code economy.GmAdjust} 命令的封装， <b>预览
 * / 原因 / 前后差异 / 审计</b>四件事都在一条工具里。
 *
 * <p>★★ <b>preview / apply 共用同一份纯函数</b>：{@link EconomyGmAdjustments#project}（economy 侧）是唯一语义落点。
 *
 * <ul>
 *   <li>{@code preview=true}（缺省）：从 {@link QueryService} 读当前 {@link SimulationState} 的 {@link
 *       EconomyData}， 调同一个 {@code project} 算 projected 状态 / {@code EconomyChangeSet} / 前后差异 ——
 *       <b>不提交、不写状态</b>；
 *   <li>{@code preview=false}：先用同一个 {@code project} 在<b>同一个坐标</b>（branch + expectedRevision）算预览差异，
 *       再组一条 {@code economy.GmAdjust} 信封（payload 逐字含 adjustment/parameters/reason）走 {@code
 *       CoreSimos.submit}； 返回"预览差异 + 提交结局/revision"。校验不过 ⇒ {@code BAD_REQUEST} <b>不提交半笔</b>。
 * </ul>
 *
 * <p>★★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶（{@code
 * addDecisionAgentWrites}）里没有本工具； {@code DecisionCallerFactory.WHITELIST} 里也没有 {@link #NAME}；命令本身标了
 * {@code GmOnlyCommand} ⇒ 令 / {@code RegisterEffect} / 决策人 catalog 三条路径同样到不了它。
 *
 * <p>★ <b>审计</b>：GM 工具使用留痕复用既有的 {@code GmToolUsage}（{@code RecordingToolSource} 装饰 GM 源，不另造第二份）；
 * 命令载荷里带 {@code reason}（handler 落 {@code Applied}/事件链）；工具结果也带 {@code reason} 与差异摘要。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / {@code preview} 非布尔 / {@code parameters} 非对象 / 白名单外 adjustment（派生读数直写）/
 * 引用不存在 ⇒ {@code BAD_REQUEST}；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class EconomyAdjustTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.adjust";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 本工具只写 economy 命名空间（GM 侧 {@code economy} 未受限 ⇒ 逐条判通过）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(Map.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（本工具走 {@code submit}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；预览与工具面同源）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（★ 本工具的资源声明是 {@code economy:*} 命名空间粗断言，与其余 GM 窄写同制；
   *     此参数保留在装配签名里，发音"这是哪个世界的工具"，不当路径用）
   */
  public EconomyAdjustTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 经济调整（economy.GmAdjust 的窄封装）：先用 economy 侧纯函数算好 projected 状态与前后差异，"
        + "preview=true（缺省）只算不写；preview=false 时提交同一条 payload（reason 必填，进载荷/事件与工具结果）。"
        + "adjustment 白名单（10）—— 旧表两 kind —— forgiveDebt（debtContractId + amount?，只减/清本金）、"
        + "setLiquidationPolicy（assetRuleId + maxLiquidatePerMille/protectedReserve/priceSource/"
        + "policyValuePerUnitMilli/recipientRule）；P7 生产方式编辑八 kind —— "
        + "upsertProductionMode（id + name + version? + classStructureId；version 必须推进，classStructureId 须已存在）、"
        + "deactivateProductionMode（id；被结构/位置/组织/资产规则/变迁/质押引用 ⇒ 具名拒绝）、"
        + "upsertClassStructure（id + modeId + positions? + defaultSharesPerMille?；位置 upsert 并同步全局表与所有结构副本）、"
        + "upsertClassPosition（id + modeId + name?/relationToMeans?/laborRole?/surplusRole?/ruleExtensions? + classStructureId?）、"
        + "upsertProductionRelation（activity + operator? + inputSupplier? + rules? + residualOwner? + laborSource?；"
        + "operator 必须与 unit.operator 一致）、"
        + "upsertAssetRule（modeId + assetKind + isCoreMeans?/pledgeable?/liquidationPriority?/rentRule?/transferRule?；"
        + "id 由 AssetRuleId.idOf 派生）、"
        + "upsertProductionOrganization（id? + modeId + classPositionId + unitId? + organizer + laborSources?/assetSources?/"
        + "inputSources? + outputOwnership + relationTemplateRef? + status + statusReason?；ACTIVE/EXITING 必须有 unit，"
        + "SHORTAGE 必须有具名 reason）、"
        + "upsertCandidate（按 ProductionCandidate 现有字段；新建需 output/outputPerUnit/cycleDays/regime，"
        + "修订必须推进 version；★ ProductionCandidate 没有 modeId，本 kind 按现有模型走 regime，显式 modeId 具名拒绝）。"
        + "白名单外 adjustment（flows/demandBook/crisisSignals/classStandings 等派生读数）一律拒："
        + "派生读数不可由 GM 调整工具直写。★ 只在 GM 桶，决策人不可调用。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "adjustment",
        ToolSupport.prop(
            "string",
            "调整名（源状态白名单）：forgiveDebt | setLiquidationPolicy"
                + " | upsertProductionMode | deactivateProductionMode | upsertClassStructure | upsertClassPosition"
                + " | upsertProductionRelation | upsertAssetRule | upsertProductionOrganization | upsertCandidate；"
                + "白名单外一律拒（派生读数不可直写）"));
    props.put(
        "parameters",
        ToolSupport.prop(
            "object",
            "调整参数："
                + "forgiveDebt={debtContractId, amount?(缺省=全额本金，须 ≤ 本金)}；"
                + "setLiquidationPolicy={assetRuleId, maxLiquidatePerMille(0..1000), protectedReserve(≥0),"
                + " priceSource(MARKET|AGREED|POLICY), policyValuePerUnitMilli(≥0；非 POLICY 必须 0),"
                + " recipientRule(CREDITOR_FIRST|MARKET_FIRST)}；"
                + "upsertProductionMode={id, name, version?(须 ≥ 现有+1；缺省仅幂等重放), classStructureId(须已存在；"
                + "upsertClassStructure 必须先建结构)}；"
                + "deactivateProductionMode={id(被 classStructures/classPositions/productionOrganizations/assetRules/"
                + "modeTransitions/pledges 任一引用 ⇒ 具名拒绝)}；"
                + "upsertClassStructure={id, modeId, positions?[{id, modeId?, name, relationToMeans, laborRole, surplusRole,"
                + " ruleExtensions?}], defaultSharesPerMille?{位置:≥0}；至少给一项；位置 upsert-合并并同步全局表与所有结构副本，"
                + "份额给到即整体替换；新建必须给非空 positions}；"
                + "upsertClassPosition={id, modeId, name?/relationToMeans?/laborRole?/surplusRole?/ruleExtensions?,"
                + " classStructureId?(位置不属于任何结构时必填)}；"
                + "upsertProductionRelation={activity(=unit id), operator?(须与 unit.operator 一致), inputSupplier?{actor|household|cohort},"
                + " rules?[CompensationRule], residualOwner?, laborSource?(SELF|FAMILY|TENANT|SERF|WAGE)}；"
                + "upsertAssetRule={modeId, assetKind(LAND|CATTLE|TOOL|WORKSHOP|MACHINE|SHIP), id?(须=派生值),"
                + " isCoreMeans?, pledgeable?, liquidationPriority?(≥0), rentRule?, transferRule?；新建后四字段必填 (rentRule 可空)}；"
                + "upsertProductionOrganization={id?, modeId, classPositionId, unitId?, organizer{kind,id}, laborSources?[household],"
                + " assetSources?[shareId], inputSources?[recipient], outputOwnership{actor|household|cohort}, relationTemplateRef?,"
                + " status(ACTIVE|SHORTAGE|SUSPENDED|EXITING), statusReason?(ACTIVE 必须空，SHORTAGE 必须非空)；"
                + "id 缺省需 organizer 是 HOUSEHOLD 且有 unitId}；"
                + "upsertCandidate={id, version?, output?, outputPerUnit?{商品:>0}, inputPerUnit?{商品:≥0},"
                + " requiredAssets?{资产:≥0}, laborPerUnit?, buildDays?, cycleDays?, regime?, laborSource?,"
                + " acceptedRightKinds?[OWNED|TENANCY|COMMUNAL], assetSource?, name?；新建 output/outputPerUnit/cycleDays/regime 必填；"
                + "修订须推进 version；★ 无 modeId（模型无此字段，显式给 ⇒ 拒）}"));
    props.put("reason", ToolSupport.prop("string", "调整原因（必填非空白；进命令载荷与工具结果/审计）"));
    props.put(
        "preview", ToolSupport.prop("boolean", "true（缺省）= 只算前后差异、不写；false = 提交 economy.GmAdjust"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("adjustment", "parameters", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ECONOMY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "经济调整 adjustment="
            + args.get("adjustment")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String adjustment = ToolSupport.requiredText(args, "adjustment");
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      Object rawParameters = args.get("parameters");
      if (!(rawParameters instanceof Map<?, ?>)) {
        return ToolResult.error("BAD_REQUEST", "参数 parameters 必填且为 JSON 对象");
      }
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一个 parameters 树 + 同一个坐标上的 base 状态。
      JsonNode parameters = MAPPER.valueToTree(rawParameters);
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryService.QueryTarget.head(branch)
                  : QueryService.QueryTarget.at(branch, new RevisionId(expectedRevision)));
      EconomyData base = ToolSupport.economyData(state);
      long day = state.meta().timestamp().tick();
      EconomyGmAdjustments.Projection projection =
          EconomyGmAdjustments.project(base, adjustment, parameters, reason, day);
      Map<String, Object> view = diffView(projection);
      if (preview) {
        view.put("preview", true);
        view.put("submitted", false);
        return ToolSupport.ok(view);
      }
      return commit(view, adjustment, rawParameters, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与 AbstractNarrowWriteTool / CommandSubmitTool 同一条：资源拒因原样逃到 ToolCallAuthorizer 边界，
      //   折成 TOOL_ERROR 会让模型看到"参数问题"而看不到"换个资源"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "经济调整工具失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 提交阶段：组同一条 payload、走唯一写入口，把三结局并与预览差异拼在一份结果里。 */
  private ToolResult commit(
      Map<String, Object> view,
      String adjustment,
      Object rawParameters,
      String reason,
      BranchId branch,
      long expectedRevision) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("adjustment", adjustment);
    payload.put("parameters", rawParameters);
    payload.put("reason", reason);
    String commandId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            commandId,
            commandId,
            initiator,
            branch,
            new RevisionId(expectedRevision),
            EconomyGmAdjustHandler.TYPE,
            ToolSupport.json(payload));
    CommandResult result = core.submit(envelope);
    view.put("preview", false);
    view.put("submitted", true);
    view.put("commandId", commandId);
    return switch (result) {
      case CommandResult.Committed committed -> {
        view.put("submission", ToolSupport.committedView(committed.ref(), commandId, commandId));
        yield ToolSupport.ok(view);
      }
      case CommandResult.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolSupport.stateRef(conflict.current()));
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolSupport.json(view));
      }
      case CommandResult.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolSupport.json(view));
      }
    };
  }

  /**
   * 前后差异摘要（工具结果的共同部分）：逐组件 changed 计数、关键 id、逐键 before/after 与 reason。
   *
   * <p>★ {@code changeSetEmpty} 取自 {@link EconomyChangeSet#isEmpty()}（权威差异），{@code changes} 是本次调整实际
   * 碰过的稳定键（同一次 {@code project} 产出）——两者不一致就意味着纯函数之外还有变化，读结果时能一眼看出。
   */
  private static Map<String, Object> diffView(EconomyGmAdjustments.Projection projection) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("reason", projection.reason());
    view.put("adjustment", projection.adjustment());
    view.put("day", projection.day());
    Map<String, Integer> changedComponents = new LinkedHashMap<>();
    List<String> changedIds = new ArrayList<>();
    List<Map<String, Object>> changes = new ArrayList<>();
    for (EconomyGmAdjustments.Change change : projection.changes()) {
      changedComponents.merge(change.component(), 1, Integer::sum);
      changedIds.add(change.keyId());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("component", change.component());
      row.put("keyId", change.keyId());
      row.put("before", change.before());
      row.put("after", change.after());
      changes.add(row);
    }
    view.put("changedComponents", changedComponents);
    view.put("changedIds", changedIds);
    view.put("changes", changes);
    view.put("changeSetEmpty", projection.changeSet().isEmpty());
    return view;
  }
}
