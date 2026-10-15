package io.mosire.simos.ga.tools.write;

import com.fasterxml.jackson.core.type.TypeReference;
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
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.MergedEffectPlanId;
import io.mosire.simos.ga.model.MergedEffect;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.spi.UpsertMergedEffectPlanHandler;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.gm.mergedPlan.upsert}（D3 契约 §8）：GM 建/改一个 {@link MergedEffectPlan}——冲突决策包的**有序效果计划**。
 *
 * <p>★ 参数 {planId?, tick?, participantIds?, sources?, reason?,
 * orderedEffects[{toolName,argsJson,sourceCallRefs?}...]}。 {@code planId} 缺省 {@code
 * merge-<tick>-<n>}（n = 该 tick 已有计划数 + 1，稳定确定性）；每条 effect 的工具必须在 {@link ProposalCatalog} 初始清单里（D3
 * 不允许任意工具，避免执行面失控）。
 *
 * <p>★ 提交一条 {@code ga.UpsertMergedEffectPlan}（整包 upsert，一条 revision）；返回计划视图。
 *
 * <p>★ 顺序约束：先 upsert，再 {@code simos.gm.packet.decide(MERGE, mergedPlanId)}，最后 {@code
 * simos.gm.mergedPlan.apply}。
 *
 * <p>★ 只在 GM 桶；工具名不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS；资源声明 sd UNRESTRICTED；敏感写。
 */
public final class GmMergedPlanUpsertTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.gm.mergedPlan.upsert";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaProposalHost catalog;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param catalog 可执行工具清单口（效果工具必须在初始清单里）
   */
  public GmMergedPlanUpsertTool(ToolWritePort writes, ToolReadPort reads, GaProposalHost catalog) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 建/改合并效果计划（写）：参数 {planId?(缺省 merge-<tick>-<n>), tick?(缺省当前 tick), "
        + "participantIds?[决策人 id], sources?[来源引用], reason?(审批摘要用), "
        + "orderedEffects[{toolName(必须在 ProposalCatalog 清单), argsJson(JSON 对象), sourceCallRefs?[]}...](必填非空)}。"
        + "一条 ga.UpsertMergedEffectPlan（整包 upsert）。返回 {planId, tick, participantIds, orderedEffects, "
        + "sources, outcome?, submission?}。先本工具建计划，再 gm.packet.decide(MERGE, planId)，最后 "
        + "gm.mergedPlan.apply。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("planId", ToolSchema.prop("string", "计划 id（缺省 merge-<tick>-<该 tick 已有计划数+1>）"));
    props.put("tick", ToolSchema.prop("integer", "所属 tick（缺省当前世界 tick）"));
    Map<String, Object> participants = ToolSchema.prop("array", "参与者决策人 id（允许历史已删除）");
    participants.put("items", ToolSchema.prop("string", "决策人 id"));
    props.put("participantIds", participants);
    Map<String, Object> sources = ToolSchema.prop("array", "来源 call 引用（形如 pkt-…:0）");
    sources.put("items", ToolSchema.prop("string", "来源引用"));
    props.put("sources", sources);
    props.put("reason", ToolSchema.prop("string", "可选：审批摘要用的原因（本批不落盘）"));
    Map<String, Object> effects = ToolSchema.prop("array", "有序效果（必填非空；顺序即执行顺序）");
    Map<String, Object> effectItem = new LinkedHashMap<>();
    effectItem.put("type", "object");
    Map<String, Object> effectProps = new LinkedHashMap<>();
    effectProps.put("toolName", ToolSchema.prop("string", "真实工具名（必须在 ProposalCatalog 清单）"));
    effectProps.put("argsJson", ToolSchema.prop("string", "目标工具参数 JSON 对象文本"));
    Map<String, Object> refs = ToolSchema.prop("array", "来源 call 引用（形如 pkt-…:0）");
    refs.put("items", ToolSchema.prop("string", "来源引用"));
    effectProps.put("sourceCallRefs", refs);
    effectItem.put("properties", effectProps);
    effectItem.put("required", List.of("toolName", "argsJson"));
    effects.put("items", effectItem);
    props.put("orderedEffects", effects);
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("orderedEffects"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return GA_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "建/改合并计划 planId="
            + args.get("planId")
            + " tick="
            + args.get("tick")
            + " effects="
            + (args.get("orderedEffects") instanceof List<?> list ? list.size() : "?")
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.WRITE, List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      // reason 本批只进审批摘要（MergedEffectPlan 没有 reason 字段；reasonInfoId 留 D4）：读出来防误当未知参数静默忽略。
      ToolSchema.optionalText(args, "reason", null);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
      if (tick < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tick);
      }
      List<MergedEffect> effects = requiredEffects(args);
      List<DecisionMakerId> participantIds = optionalDecisionMakerIds(args, "participantIds");
      List<String> sources = optionalStringList(args, "sources", "sources");
      String planIdArg = ToolSchema.optionalText(args, "planId", null);
      String planId =
          planIdArg == null || planIdArg.isBlank() ? defaultPlanId(ga, tick) : planIdArg.trim();
      MergedEffectPlan plan =
          new MergedEffectPlan(
              MergedEffectPlanId.parse(planId),
              tick,
              participantIds,
              effects,
              sources,
              Optional.empty(),
              Optional.empty());
      // ★ 命令 id 由窄写口的装配面生成并**回报**（见 ToolWritePort.Receipt）。
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              UpsertMergedEffectPlanHandler.TYPE,
              DecisionPacketPayloads.mergedPlan(plan));
      Map<String, Object> view = planView(plan, branch, state.meta().ref().revision().value());
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "建合并计划失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数解析 ────────────────────────────────────────────────────────────────────────

  private List<MergedEffect> requiredEffects(Map<String, Object> args) {
    Object raw = args.get("orderedEffects");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("参数 orderedEffects 必填且为非空数组");
    }
    List<MergedEffect> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> row)) {
        throw new IllegalArgumentException("orderedEffects 的元素必须是对象: " + item);
      }
      Object toolNameRaw = row.get("toolName");
      if (!(toolNameRaw instanceof String toolName) || toolName.isBlank()) {
        throw new IllegalArgumentException("orderedEffect.toolName 必填且为非空文本: " + item);
      }
      if (!catalog.contains(toolName)) {
        throw new IllegalArgumentException("效果工具不在 ProposalCatalog 初始清单，不可执行: " + toolName);
      }
      Object argsJsonRaw = row.get("argsJson");
      if (argsJsonRaw != null && !(argsJsonRaw instanceof String)) {
        throw new IllegalArgumentException("orderedEffect.argsJson 必须是 JSON 对象文本: " + item);
      }
      String argsJson = argsJsonRaw == null ? "{}" : (String) argsJsonRaw;
      requireJsonObject(argsJson, "orderedEffect.argsJson");
      List<String> refs =
          optionalStringListFrom(row, "sourceCallRefs", "orderedEffect.sourceCallRefs");
      out.add(new MergedEffect(toolName, argsJson, refs));
    }
    return List.copyOf(out);
  }

  private static List<DecisionMakerId> optionalDecisionMakerIds(
      Map<String, Object> args, String field) {
    List<String> raw = optionalStringList(args, field, field);
    List<DecisionMakerId> out = new ArrayList<>(raw.size());
    for (String id : raw) {
      out.add(DecisionMakerId.parse(id));
    }
    return List.copyOf(out);
  }

  private static List<String> optionalStringList(
      Map<String, Object> args, String field, String label) {
    return optionalStringListFrom(args, field, label);
  }

  private static List<String> optionalStringListFrom(Map<?, ?> args, String field, String label) {
    Object raw = args.get(field);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + label + " 若给出必须是 [字符串…] 数组");
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 " + label + " 的元素必须是非空文本: " + item);
      }
      out.add(text);
    }
    return List.copyOf(out);
  }

  private static void requireJsonObject(String json, String label) {
    if (json.isBlank()) {
      throw new IllegalArgumentException(label + " 不得为空白（无参数用 {}）");
    }
    try {
      Map<String, Object> parsed =
          MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
      if (parsed == null) {
        throw new IllegalArgumentException(label + " 必须是 JSON 对象: " + json);
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(label + " 必须是 JSON 对象: " + e.getMessage(), e);
    }
  }

  /** 缺省 planId：{@code merge-<tick>-<n>}，n = 该 tick 已有计划数 + 1；撞既有 id（历史异常数据）就继续 +1。 */
  private static String defaultPlanId(GaState ga, long tick) {
    long count = 0L;
    for (MergedEffectPlan plan : ga.mergedEffectPlans().values()) {
      if (plan.tick() == tick) {
        count++;
      }
    }
    long n = count + 1;
    String candidate = "merge-" + tick + "-" + n;
    while (ga.mergedEffectPlans().containsKey(MergedEffectPlanId.parse(candidate))) {
      n++;
      candidate = "merge-" + tick + "-" + n;
    }
    return candidate;
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> planView(
      MergedEffectPlan plan, BranchId branch, long baseRevision) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("planId", plan.id().value());
    view.put("tick", plan.tick());
    view.put("participantIds", plan.participantIds().stream().map(DecisionMakerId::value).toList());
    List<Map<String, Object>> effects = new ArrayList<>(plan.orderedEffects().size());
    for (MergedEffect effect : plan.orderedEffects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("toolName", effect.toolName());
      row.put("argsJson", effect.argsJson());
      row.put("args", parseObject(effect.argsJson()));
      row.put("sourceCallRefs", effect.sourceCallRefs());
      effects.add(row);
    }
    view.put("orderedEffects", effects);
    view.put("sources", plan.sources());
    plan.outcome().ifPresent(outcome -> view.put("outcome", outcome));
    view.put("branch", branch.value());
    view.put("baseRevision", baseRevision);
    return view;
  }

  private static Map<String, Object> parseObject(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("合并计划 argsJson 不是对象: " + e.getMessage(), e);
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
