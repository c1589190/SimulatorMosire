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
import io.mosire.simos.army.tools.ArmyToolViews;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
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
 * ★★ {@code simos.army.formatUnit}（阶段 D3a，2026-10-02 / D-010 + 补裁 R2 的<b>最小环</b>）：GM-only
 * 组合工具——按给定格式 （有序人力/装备条目 + 可选随机化幅度/上下限 + 可选 seed）算出目标表，经 {@code unit.SetComposition} **整表复写**，
 * 并同批写一条 {@code ga.PutInfo} 审计记录（含生效种子）。
 *
 * <p>★★ <b>为什么是一次批写多切片的组合工具而不是一条命令</b>：它同时写 {@code unit}（一条 {@code unit.SetComposition}）与 {@code
 * sd}（一条 {@code ga.PutInfo}）两个命名空间；单条命令只能落一个命名空间。本工具走注入的 {@link
 * ToolWritePort#submitBatchWithReceipt}（同一批 + 同一 branch/expectedRevision ⇒ **一批 = 一条
 * revision**，原子）； preview 一个字节都不写。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点在 {@link FormatUnitPlan#derive}（不碰 {@link
 * ToolContext} / 写口）；随机化与"同状态同参数 ⇒ 同结果"的口径写在那里的类注。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。它提交的 {@code unit.SetComposition} 本身打 {@code DmUsableCommand}（可嵌进决策令），但 {@code
 * formatUnit} 这条带随机化 的编排口只给 GM。
 *
 * <p>★ <b>资源声明</b>：只写 {@code unit}/{@code sd} 两个命名空间（{@link ResourcePolicy#UNRESTRICTED}；GM 侧两片
 * unlimited）， 与批内两条命令逐条对齐。
 *
 * <p>★ <b>参数</b>：{@code unitId(必填),
 * manpower[{type,baseAmount,jitterPerMille?,min?,max?}]（必填数组，空合法）, equipment[同形]（必填数组，空合法）, seed?,
 * reason?(缺省 {@value #DEFAULT_REASON}), preview?(缺省 true), branch?, expectedRevision?}。{@code seed}
 * 缺省时从"unitId + 基态 revision + tick + 格式表"确定性派生；{@code jitterPerMille} 是千分比（0 =
 * 不随机）。目标表**整体取代**旧表：未列出的 type 会消失，这是"整表复写"的语义。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错 / unitId 不存在 / 格式重复 type / 随机化溢出 ⇒ {@code BAD_REQUEST}（零 revision）；
 * 批内域拒 ⇒ {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-army}，对 {@code
 * CoreSimos} / {@code QueryService} 的直连换成构造期注入；工具名、载荷、拒绝码、资源声明、批内顺序逐字不变。
 */
public final class FormatUnitTool implements SimosTool {

  /** 工具名（全局唯一；用户给定）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.army.formatUnit";

  /** {@code reason} 缺省（任务书把 reason 列为可选；审计记录里仍留一条固定口径）。 */
  static final String DEFAULT_REASON = "army.formatUnit";

  /** 本工具只写 unit / sd 两个命名空间（GM 侧两片 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest FORMAT_UNIT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #FORMAT_UNIT_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.GA_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final ArmyToolViews views;

  /**
   * @param writes 唯一写入口（apply 走 {@code submitBatchWithReceipt}；preview 时一个字节都不写）
   * @param reads 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与 apply 共用同一份推导）
   * @param views 视图口（人力/装备表形状 + 单位 canonical 地址；实现逐条转调 app 的同一份函数）
   */
  public FormatUnitTool(ToolWritePort writes, ToolReadPort reads, ArmyToolViews views) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 按给定格式整表复写单位人力/装备（组合工具，一批 = 一条 revision）：格式 = 有序条目"
        + " {type, baseAmount, jitterPerMille?, min?, max?}；算出目标表后经 unit.SetComposition 整表复写，并同批写"
        + " ga.PutInfo（key="
        + FormatUnitPlan.INFO_KEY
        + "，value 含生效 seed/是否显式/前后两张表）。参数 {unitId(必填), manpower[](必填，空数组合法),"
        + " equipment[](必填，空数组合法), seed?, reason?(缺省 "
        + DEFAULT_REASON
        + "), preview?(缺省 true), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。seed 缺省 = 由 unitId + 基态 revision + tick + 格式表确定性派生；"
        + "jitterPerMille 千分比（0 = 不随机），每条在 [base−floor(base×jitter/1000), base+…] 均匀取整后按 [min,max] 截断；"
        + "未列出的 type 会消失（整表复写）。批顺序：unit.SetComposition → ga.PutInfo。"
        + "返回 {preview, submitted, unitId, seed, seedProvided, tick, manpower, equipment, beforeManpower,"
        + " beforeEquipment, commands, reason, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSchema.prop("string", "目标单位 id（必须存在）"));
    props.put(
        "manpower",
        ToolSchema.prop(
            "array", "人力目标格式 [{type, baseAmount, jitterPerMille?, min?, max?}…]（必填，空数组合法）"));
    props.put("equipment", ToolSchema.prop("array", "装备目标格式（同 manpower 形状；必填，空数组合法）"));
    props.put(
        "seed",
        ToolSchema.prop("integer", "显式随机种子（可选；缺省 = 由 unitId + 基态 revision + tick + 格式表确定性派生）"));
    props.put(
        "reason",
        ToolSchema.prop("string", "操作原因（可选；缺省 " + DEFAULT_REASON + "，进 ga.PutInfo 审计记录）"));
    props.put(
        "preview",
        ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交 SetComposition + PutInfo 同一批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=head）"));
    return ToolSchema.schema(props, List.of("unitId", "manpower", "equipment"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return FORMAT_UNIT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "按格式复写单位人力/装备 unitId="
            + args.get("unitId")
            + " seed="
            + args.get("seed")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + " expected="
            + args.get("expectedRevision")
            + " reason="
            + args.getOrDefault("reason", DEFAULT_REASON),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String unitId = ToolSchema.requiredText(args, "unitId");
      List<FormatUnitPlan.EntrySpec> manpower = requireSpecs(args, "manpower");
      List<FormatUnitPlan.EntrySpec> equipment = requireSpecs(args, "equipment");
      Long seed = ToolSchema.optionalLong(args, "seed");
      String reason = ToolSchema.optionalText(args, "reason", DEFAULT_REASON);
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ 读数坐标 = 提交坐标：缺省（<0）= 该分支 head（与 QueryTarget.head 同义，见 ToolReadPort 的约定）。
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      FormatUnitPlan.Plan plan =
          FormatUnitPlan.derive(state, unitId, manpower, equipment, Optional.ofNullable(seed));
      if (preview) {
        return ToolResults.ok(view(plan, reason, true, false, null));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "按格式复写单位失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数解析（嵌套数组里的对象；坏输入折 BAD_REQUEST）──────────────────────────────

  /** 必填的目标格式数组：字段缺失/不是数组/元素不是对象/条目字段类型错 ⇒ 具名 {@link IllegalArgumentException}。 */
  private static List<FormatUnitPlan.EntrySpec> requireSpecs(
      Map<String, Object> args, String field) {
    Object raw = args.get(field);
    if (raw == null) {
      throw new IllegalArgumentException("参数 " + field + " 必填且为 [{type,baseAmount,…}…] 数组");
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + field + " 必须是 [{type,baseAmount,…}…] 数组");
    }
    List<FormatUnitPlan.EntrySpec> specs = new ArrayList<>(list.size());
    for (Object item : list) {
      Map<String, Object> spec = stringKeyedObject(item, field);
      String type = ToolSchema.requiredText(spec, "type");
      long baseAmount = ToolSchema.requiredLong(spec, "baseAmount");
      Long jitterArg = ToolSchema.optionalLong(spec, "jitterPerMille");
      long jitterPerMille = jitterArg == null ? 0L : jitterArg;
      Long minArg = ToolSchema.optionalLong(spec, "min");
      Long maxArg = ToolSchema.optionalLong(spec, "max");
      long minAmount = minArg == null ? 0L : minArg;
      long maxAmount = maxArg == null ? Long.MAX_VALUE : maxArg;
      specs.add(
          new FormatUnitPlan.EntrySpec(type, baseAmount, jitterPerMille, minAmount, maxAmount));
    }
    return List.copyOf(specs);
  }

  /** 把嵌套参数对象折成 String 键的 Map（键不是字符串 ⇒ 具名拒；不静默丢）。 */
  private static Map<String, Object> stringKeyedObject(Object raw, String field) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + field + " 的元素必须是 {…} 对象");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("参数 " + field + " 的元素键必须是字符串: " + entry.getKey());
      }
      out.put(key, entry.getValue());
    }
    return out;
  }

  // ── preview / apply 共用视图与组批 ─────────────────────────────────────────────────

  private ToolResult apply(
      FormatUnitPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
    List<ToolWritePort.Request> batch =
        List.of(
            new ToolWritePort.Request(
                FormatUnitPlan.SET_COMPOSITION_TYPE, plan.setCompositionPayloadJson(views)),
            new ToolWritePort.Request(FormatUnitPlan.PUT_INFO_TYPE, infoPayload(plan, reason)));
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch);
    // ★ 批级 id 由写口适配器生成并回报 ⇒ 结果里报出去的就是落盘信封里的那一个。
    String batchId = receipt.correlationId();
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      return ToolResults.ok(view(plan, reason, false, true, committedView(committed, batchId)));
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      return ToolResult.error(
          "CONFLICT", ToolResults.json(view(plan, reason, false, true, submission)));
    }
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效）。
    ToolWritePort.BatchOutcome.Rejected rejected =
        (ToolWritePort.BatchOutcome.Rejected) receipt.outcome();
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<ToolWritePort.Outcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      ToolWritePort.Outcome outcome = outcomes.get(i);
      row.put(
          "reason",
          outcome instanceof ToolWritePort.Outcome.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    return ToolResult.error(
        "REJECTED", ToolResults.json(view(plan, reason, false, true, submission)));
  }

  private static Map<String, Object> committedView(
      ToolWritePort.BatchOutcome.Committed committed, String batchId) {
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "committed");
    submission.put("ref", ToolResults.stateRef(committed.ref()));
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    return submission;
  }

  private Map<String, Object> view(
      FormatUnitPlan.Plan plan,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("unitId", plan.unitId());
    view.put("seed", plan.seed());
    view.put("seedProvided", plan.seedProvided());
    view.put("tick", plan.tick());
    // ★ S3b：不再回 manpower 表（Unit.manpower 已退役，人员属于 Social 家户）；只回装备前后值。
    view.put("equipment", views.compositionView(plan.equipment()));
    view.put("beforeEquipment", views.compositionView(plan.beforeEquipment()));
    view.put("commands", plan.commandTypes());
    view.put("reason", reason);
    view.put("infoText", plan.infoNote(reason));
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  /** {@code ga.PutInfo} 载荷：单位 canonical 地址 + key + value(JSON 字符串) + note + tick。 */
  private String infoPayload(FormatUnitPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", views.unitAddress(plan.unitId()));
    payload.put("key", FormatUnitPlan.INFO_KEY);
    payload.put("value", plan.infoValueJson(views, reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
