package io.mosire.simos.army.tools.write;

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
import io.mosire.simos.army.tools.ArmyToolReads;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.map.hex.HexCoord;
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
import java.util.UUID;

/**
 * {@code simos.army.editFormation}（GM-only）：一次 JSON 调用创建/更新单支军队的完整编制树。
 *
 * <p>★★ <b>一个原子批</b>：命令只落 {@code social}/{@code unit}/{@code army}；新节点整树一次 batch，任一失败全回滚。人数是 Social
 * 家户权威，装备只落 unit，Army 仍只认 GOV，不新增 NationId。
 *
 * <p>★★ <b>update 语义</b>：只改给定字段；未提及节点保留、不自动删除；{@code children} = 把这些节点编到该父之下（不是整表删除）； 更改 Army.root
 * 具名拒。
 */
public final class ArmyEditFormationTool implements SimosTool {

  public static final String NAME = "simos.army.editFormation";

  private static final ResourceManifest FORMATION_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.ARMY_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.ARMY_NAMESPACE, "*"));

  private final io.mosire.simos.ga.spi.ToolReadPort reads;
  private final ToolWritePort writes;

  public ArmyEditFormationTool(io.mosire.simos.ga.spi.ToolReadPort reads, ToolWritePort writes) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 一次编辑单支军队完整编制（原子批，仅 social/unit/army）：mode=create|update，armyId，name?，masterGov?，"
        + "root:{id,name?,role?,position?{q,r},personnel?,equipment?{type:count},speed?,mobilityPerMille?,attached?,children?:[...]}，"
        + "dryRun?（缺省 false），reason，branch?/revision?/expectedRevision(非 dryRun 必填)。"
        + "create：root 明坐标；新下级默认 attached=true、坐标缺省写实为父位置；每节点带 ArmyFormation（masterGov 与 Army 一致）；"
        + "HQ 可 0 人；personnel 是本级直接人数（不含下属），新成员默认成年男性锚点年龄 "
        + ArmyEditFormationPlan.DEFAULT_ADULT_MALE_AGE_DAYS
        + " 天，不是真实人口结构。update：只改给字段，未提及节点保留不删除；children 表示编到该父之下；"
        + "改 Army.root 具名拒。上限 512 节点/深度 16；重复/循环/跨其他 Army/attached 跨格/负数小数溢出具名拒。"
        + "不发 economy/actor/ga 命令。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("mode", ToolSchema.prop("string", "create|update"));
    props.put("armyId", ToolSchema.prop("string", "军队稳定 id"));
    props.put("name", ToolSchema.prop("string", "军队名（create 省略时取 root.name；update 省略=保持）"));
    props.put(
        "masterGov", ToolSchema.prop("string", "认领的 GOV 单位 id（可选；必须存在且带 GovernmentFormation）"));
    props.put(
        "root",
        ToolSchema.prop(
            "object",
            "递归编制树根：{id(必填),name?,role?,position?{q,r},personnel?,equipment?{type:count},speed?,"
                + "mobilityPerMille?,attached?,children?:[...]}；create 时 root.position 必填"));
    props.put("dryRun", ToolSchema.prop("boolean", "true=只算不写；缺省 false"));
    props.put("reason", ToolSchema.prop("string", "编辑原因（必填非空白）"));
    props.put("expectedRevision", ToolSchema.prop("integer", "非 dryRun 必填：提交的乐观并发 base revision"));
    return ToolSchema.schema(props, List.of("mode", "armyId", "root", "reason"));
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }

  @Override
  public ResourceManifest resources() {
    return FORMATION_WRITE;
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
        "编辑军队编制 armyId="
            + args.get("armyId")
            + " mode="
            + args.get("mode")
            + " name="
            + args.getOrDefault("name", "(保持)")
            + " masterGov="
            + args.getOrDefault("masterGov", "(保持)")
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
      String armyId = ToolSchema.requiredText(args, "armyId");
      String name = ToolSchema.optionalText(args, "name", null);
      String masterGov = ToolSchema.optionalText(args, "masterGov", null);
      Object rootRaw = args.get("root");
      if (rootRaw == null) {
        throw new IllegalArgumentException("root 必填");
      }
      ArmyEditFormationPlan.NodeSpec root = parseNode(rootRaw, "root", 1, new int[] {0});
      String reason = ToolSchema.requiredText(args, "reason");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason 不得为空白");
      }
      boolean dryRun = ToolSchema.optionalBoolean(args, "dryRun").orElse(false);
      BranchId branch = ArmyToolReads.branch(args);
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedArg == null ? -1L : expectedArg;
      SimulationState state;
      if (dryRun) {
        Long revisionArg = ToolSchema.optionalLong(args, "revision");
        state = reads.stateAt(branch, revisionArg == null ? null : new RevisionId(revisionArg));
      } else {
        if (expectedRevision < 0L) {
          throw new IllegalArgumentException("dryRun=false 时必须给 expectedRevision");
        }
        var head = writes.head(branch);
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
        state = reads.stateAt(branch, new RevisionId(expectedRevision));
      }
      ArmyEditFormationPlan.Plan plan =
          ArmyEditFormationPlan.plan(state, mode, armyId, name, masterGov, root, reason);
      if (dryRun) {
        return ToolResults.ok(ArmyEditFormationPlan.view(plan, true, reason));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR",
          "editFormation 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private ToolResult apply(
      ArmyEditFormationPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<ToolWritePort.Request> batch = new ArrayList<>(plan.commands().size());
    for (ArmyEditFormationPlan.Command command : plan.commands()) {
      batch.add(new ToolWritePort.Request(command.type(), command.payloadJson()));
    }
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(
            branch, new RevisionId(expectedRevision), List.copyOf(batch), batchId);
    Map<String, Object> view = ArmyEditFormationPlan.view(plan, false, reason);
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      view.put("submission", ToolResults.committedView(committed.ref(), batchId, batchId));
      return ToolResults.ok(view);
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    ToolWritePort.BatchOutcome.Rejected rejected =
        (ToolWritePort.BatchOutcome.Rejected) receipt.outcome();
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<ToolWritePort.Outcome> outcomes = rejected.outcomes();
    for (int index = 0; index < outcomes.size(); index++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(index).type());
      ToolWritePort.Outcome outcome = outcomes.get(index);
      row.put(
          "reason",
          outcome instanceof ToolWritePort.Outcome.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", List.copyOf(rows));
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolResults.json(view));
  }

  // ── 递归树解析 ──────────────────────────────────────────────────────

  private static ArmyEditFormationPlan.NodeSpec parseNode(
      Object raw, String path, int depth, int[] nodeCount) {
    if (depth > ArmyEditFormationPlan.MAX_DEPTH) {
      throw new IllegalArgumentException(
          path + " 深度超过上限 " + ArmyEditFormationPlan.MAX_DEPTH + "（递归入口先拒，不先建超深树）");
    }
    int current = ++nodeCount[0];
    if (current > ArmyEditFormationPlan.MAX_NODES) {
      throw new IllegalArgumentException(
          path + " 节点数超过上限 " + ArmyEditFormationPlan.MAX_NODES + "（递归入口先拒）");
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(path + " 必须是对象");
    }
    String id = requiredText(map.get("id"), path + ".id");
    String name = optionalText(map.get("name"), path + ".name");
    String role = optionalText(map.get("role"), path + ".role");
    HexCoord position = parsePosition(map.get("position"), path + ".position");
    Long personnel =
        map.get("personnel") == null ? null : integral(map.get("personnel"), path + ".personnel");
    Map<String, Long> equipment = parseEquipment(map.get("equipment"), path + ".equipment");
    Integer speed = map.get("speed") == null ? null : intValue(map.get("speed"), path + ".speed");
    Integer mobility =
        map.get("mobilityPerMille") == null
            ? null
            : intValue(map.get("mobilityPerMille"), path + ".mobilityPerMille");
    Boolean attached = optionalBoolean(map.get("attached"), path + ".attached").orElse(null);
    List<ArmyEditFormationPlan.NodeSpec> children = new ArrayList<>();
    Object childrenRaw = map.get("children");
    if (childrenRaw != null) {
      if (!(childrenRaw instanceof List<?> list)) {
        throw new IllegalArgumentException(path + ".children 必须是数组");
      }
      for (int index = 0; index < list.size(); index++) {
        children.add(
            parseNode(list.get(index), path + ".children[" + index + "]", depth + 1, nodeCount));
      }
    }
    return new ArmyEditFormationPlan.NodeSpec(
        id,
        name,
        role,
        position,
        personnel,
        equipment,
        speed,
        mobility,
        attached,
        List.copyOf(children));
  }

  private static HexCoord parsePosition(Object raw, String path) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(path + " 必须是 {q,r}");
    }
    long q = integral(map.get("q"), path + ".q");
    long r = integral(map.get("r"), path + ".r");
    if (q != (int) q || r != (int) r) {
      throw new IllegalArgumentException(path + " 的 q/r 超出 int: " + raw);
    }
    return new HexCoord((int) q, (int) r);
  }

  private static Map<String, Long> parseEquipment(Object raw, String path) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(path + " 必须是 {type:count} 对象");
    }
    Map<String, Long> equipment = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String type) || type.isBlank()) {
        throw new IllegalArgumentException(path + " 的键必须是非空白装备类型: " + entry.getKey());
      }
      long amount = integral(entry.getValue(), path + "[" + type + "]");
      if (amount < 0L) {
        throw new IllegalArgumentException(path + "[" + type + "] 必须 ≥ 0: " + amount);
      }
      equipment.put(type, amount);
    }
    return equipment;
  }

  private static String requiredText(Object raw, String path) {
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(path + " 必填且为非空白字符串");
    }
    return text;
  }

  private static String optionalText(Object raw, String path) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(path + " 给了就必须是非空白字符串");
    }
    return text;
  }

  private static Optional<Boolean> optionalBoolean(Object raw, String path) {
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof Boolean value)) {
      throw new IllegalArgumentException(path + " 必须是布尔");
    }
    return Optional.of(value);
  }

  private static Integer intValue(Object raw, String path) {
    long value = integral(raw, path);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(path + " 超出 int: " + value);
    }
    return (int) value;
  }

  private static long integral(Object raw, String path) {
    if (!(raw instanceof Number number)) {
      throw new IllegalArgumentException(path + " 必须是整数");
    }
    if (number instanceof Double || number instanceof Float) {
      double value = number.doubleValue();
      if (Double.compare(value, Math.rint(value)) != 0) {
        throw new IllegalArgumentException(path + " 必须是整数（不接受小数）: " + raw);
      }
      if (value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
        throw new IllegalArgumentException(path + " 超出 long: " + raw);
      }
      return (long) value;
    }
    return number.longValue();
  }
}
