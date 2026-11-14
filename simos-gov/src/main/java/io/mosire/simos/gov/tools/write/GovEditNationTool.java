package io.mosire.simos.gov.tools.write;

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
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.map.hex.HexCoord;
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
 * {@code simos.gov.editNation}（GM-only）：一次调用完成国家创建/更新、领土并集、颜色与可选中央 GOV。
 *
 * <p>★★ <b>一次原子批</b>：命令只落 {@code map} / {@code unit} / {@code gov} 三片；不写
 * social/economy/actor/ga，也不重建 既有 GOV。所有命令经同一个 {@link ToolWritePort#submitBatchWithReceipt} ⇒
 * 任一后续命令被拒时整批复原。
 *
 * <p>★★ <b>dryRun 与 apply 共用同一份 {@link GovEditNationPlan}</b>：预览不提交；apply 需要 {@code
 * expectedRevision}。
 *
 * <p>★★ <b>领土语义</b>：create 用确定 homeRegion（优先既有精确 {@code nation:<id>} 区域，否则 {@code
 * nation-home-<id>}）承载 非空目标并集；update 把目标并集放进既有 homeRegion，其他同标签区域只解除标签（保留 ID/几何/其他 meta）。transfer
 * 只从其他国家区域扣格， 扣后变空则整批具名拒。
 */
public final class GovEditNationTool implements SimosTool {

  public static final String NAME = "simos.gov.editNation";

  private static final ResourceManifest NATION_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.GOV_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.MAP_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.GOV_NAMESPACE, "*"),
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"));

  private final GovToolSupport support;

  public GovEditNationTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 一次编辑国家（原子批，map/unit/gov/social）：mode=create|update，nationId，name/color:#RRGGBB/hexes[{q,r}]，"
        + "territoryMode=replace|add|remove（缺省 replace），overlapPolicy=reject|transfer（缺省 reject），"
        + "government?{unitId,name,position:{q,r}}（中央 GOV：空编制 Unit + GovernmentFormation + jurisdiction + 0 人政府家户；不建经济/决策人），"
        + "dryRun?（true=只算不写，缺省 false），reason(必填)，branch?/revision?/expectedRevision(非 dryRun 必填)。"
        + "create 必须给 name/color/非空 hexes；update 未给字段保持原值，replace 的空 hexes 会被拒（国家不能清空）。"
        + "国家领土 = 精确 nation:<id> 标签区域并集；update 时其他同标签区域只解除标签、保留几何与元数据；"
        + "transfer 从其他国家区域扣目标格，若使对方区域变空则整批拒绝。返回命令清单与读回坐标。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("mode", ToolSchema.prop("string", "create|update"));
    props.put("nationId", ToolSchema.prop("string", "国家稳定 id"));
    props.put("name", ToolSchema.prop("string", "国家名（create 必填；update 省略=保持）"));
    props.put("color", ToolSchema.prop("string", "领土颜色 #RRGGBB（create 必填；update 省略=保持）"));
    props.put("hexes", ToolSchema.prop("array", "国家目标领土 [{q,r}]（create 必填非空；update 省略=不改领土）"));
    props.put(
        "territoryMode",
        ToolSchema.prop("string", "replace|add|remove（缺省 replace；replace 的空集合会被拒）"));
    props.put(
        "overlapPolicy",
        ToolSchema.prop("string", "reject|transfer（缺省 reject；transfer 只从其他国家同标签区域扣格）"));
    props.put(
        "government",
        ToolSchema.prop(
            "object",
            "可选中央 GOV {unitId,name,position:{q,r}}；新建时 position 必填，update 省略=保留既有 GOV 不重建"));
    props.put("dryRun", ToolSchema.prop("boolean", "true=只算不写；缺省 false"));
    props.put("reason", ToolSchema.prop("string", "编辑原因（必填非空白）"));
    props.put("expectedRevision", ToolSchema.prop("integer", "非 dryRun 必填：提交的乐观并发 base revision"));
    return ToolSchema.schema(props, List.of("mode", "nationId", "reason"));
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }

  @Override
  public ResourceManifest resources() {
    return NATION_WRITE;
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "编辑国家 "
            + args.get("nationId")
            + " mode="
            + args.get("mode")
            + " name="
            + args.getOrDefault("name", "(保持)")
            + " hexes="
            + (args.get("hexes") == null ? "(保持)" : "(给定)")
            + " territoryMode="
            + args.getOrDefault("territoryMode", "replace")
            + " overlapPolicy="
            + args.getOrDefault("overlapPolicy", "reject")
            + " government="
            + args.getOrDefault("government", "(保持)")
            + " dryRun="
            + args.getOrDefault("dryRun", false)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String mode = ToolSchema.requiredText(args, "mode");
      String nationId = ToolSchema.requiredText(args, "nationId");
      String name = ToolSchema.optionalText(args, "name", null);
      String color = ToolSchema.optionalText(args, "color", null);
      List<HexCoord> hexes = parseHexes(args);
      String territoryMode = ToolSchema.optionalText(args, "territoryMode", "replace");
      String overlapPolicy = ToolSchema.optionalText(args, "overlapPolicy", "reject");
      if ("create".equals(mode) && !"replace".equals(territoryMode)) {
        throw new IllegalArgumentException("create 只能使用 territoryMode=replace（完整领土一次给出）");
      }
      GovEditNationPlan.GovernmentSpec government = parseGovernment(args.get("government"));
      String reason = ToolSchema.requiredText(args, "reason");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason 不得为空白");
      }
      boolean dryRun = ToolSchema.optionalBoolean(args, "dryRun").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedArg == null ? -1L : expectedArg;
      SimulationState state;
      if (dryRun) {
        Long revisionArg = ToolSchema.optionalLong(args, "revision");
        state =
            support
                .reads()
                .stateAt(branch, revisionArg == null ? null : new RevisionId(revisionArg));
      } else {
        if (expectedRevision < 0L) {
          throw new IllegalArgumentException("dryRun=false 时必须给 expectedRevision");
        }
        var head = support.writes().head(branch);
        if (head.isEmpty()) {
          throw new IllegalArgumentException("分支不存在: " + branch.value());
        }
        if (head.orElseThrow().value() != expectedRevision) {
          Map<String, Object> conflict = new LinkedHashMap<>();
          conflict.put("result", "conflict");
          conflict.put(
              "current", Map.of("branch", branch.value(), "revision", head.orElseThrow().value()));
          conflict.put("message", "expectedRevision 不是该分支当前 head，未提交任何命令");
          return ToolResult.error("CONFLICT", ToolResults.json(conflict));
        }
        state = support.reads().stateAt(branch, new RevisionId(expectedRevision));
      }
      GovEditNationPlan.Plan plan =
          GovEditNationPlan.plan(
              state,
              mode,
              nationId,
              name,
              color,
              hexes,
              territoryMode,
              overlapPolicy,
              government,
              reason);
      if (dryRun) {
        return ToolResults.ok(GovEditNationPlan.view(plan, true, reason));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "editNation 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private ToolResult apply(
      GovEditNationPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<ToolWritePort.Request> batch =
        plan.commands().stream()
            .map(command -> GovToolSupport.request(command.type(), command.payloadJson()))
            .toList();
    ToolWritePort.BatchReceipt receipt =
        support
            .writes()
            .submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch, batchId);
    Map<String, Object> view = GovEditNationPlan.view(plan, false, reason);
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      view.put("submission", ToolResults.committedView(committed.ref(), batchId, batchId));
      return ToolResults.ok(view);
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      view.put("submission", GovBatchViews.conflictSubmission(batchId, conflict.current()));
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    view.put(
        "submission",
        GovBatchViews.rejectedSubmission(
            batchId, batch, ((ToolWritePort.BatchOutcome.Rejected) receipt.outcome()).outcomes()));
    return ToolResult.error("REJECTED", ToolResults.json(view));
  }

  // ── 参数解析 ────────────────────────────────────────────────────────

  private static List<HexCoord> parseHexes(Map<String, Object> args) {
    if (!args.containsKey("hexes")) {
      return null;
    }
    Object raw = args.get("hexes");
    if (raw == null) {
      throw new IllegalArgumentException("hexes 不得为 null（update 省略该键 = 保持领土）");
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("hexes 必须是 [{q,r}] 数组");
    }
    List<HexCoord> hexes = new ArrayList<>(list.size());
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("hexes 的元素必须是 {q,r} 对象: " + element);
      }
      Object q = map.get("q");
      Object r = map.get("r");
      if (!(q instanceof Number qn) || !(r instanceof Number rn)) {
        throw new IllegalArgumentException("hexes 元素的 q/r 必须是整数: " + element);
      }
      long ql = qn.longValue();
      long rl = rn.longValue();
      if (ql != (int) ql || rl != (int) rl) {
        throw new IllegalArgumentException("hexes 元素的 q/r 超出 int 或被截断: " + element);
      }
      if (qn instanceof Double || rn instanceof Double) {
        throw new IllegalArgumentException("hexes 元素的 q/r 必须是整数（不接受小数）: " + element);
      }
      hexes.add(new HexCoord((int) ql, (int) rl));
    }
    return List.copyOf(hexes);
  }

  private static GovEditNationPlan.GovernmentSpec parseGovernment(Object raw) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("government 必须是 {unitId,name,position} 对象");
    }
    Object unitIdRaw = map.get("unitId");
    if (!(unitIdRaw instanceof String unitId) || unitId.isBlank()) {
      throw new IllegalArgumentException("government.unitId 必填非空白");
    }
    String name = null;
    Object nameRaw = map.get("name");
    if (nameRaw != null) {
      if (!(nameRaw instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("government.name 给了就必须是非空白字符串");
      }
      name = text;
    }
    HexCoord position = null;
    Object positionRaw = map.get("position");
    if (positionRaw != null) {
      if (!(positionRaw instanceof Map<?, ?> pos)) {
        throw new IllegalArgumentException("government.position 必须是 {q,r}");
      }
      Object q = pos.get("q");
      Object r = pos.get("r");
      if (!(q instanceof Number qn) || !(r instanceof Number rn)) {
        throw new IllegalArgumentException("government.position 的 q/r 必须是整数");
      }
      if (qn instanceof Double || rn instanceof Double) {
        throw new IllegalArgumentException("government.position 的 q/r 必须是整数（不接受小数）");
      }
      long ql = qn.longValue();
      long rl = rn.longValue();
      if (ql != (int) ql || rl != (int) rl) {
        throw new IllegalArgumentException("government.position 的 q/r 超出 int");
      }
      position = new HexCoord((int) ql, (int) rl);
    }
    return new GovEditNationPlan.GovernmentSpec(unitId, name, position);
  }
}
