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
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.tools.ArmyToolReads;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.SetArmyPayPolicyHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ {@code simos.gm.armyPayPolicy}（D4，2026-10-22）：<b>P4b 军俸政策</b>的 GM 窄工具 —— {@code
 * unit.SetArmyPayPolicy} 的整体替换封装（view / apply）。
 *
 * <p>★ <b>动作</b>：
 *
 * <ul>
 *   <li>{@code view}（缺省）：{@code unitId} 必须存在且带 {@link ArmyFormation}；返回当前 {@link
 *       MilitaryPayPolicy}（排期 + 三张逐户表 + enabled）；只读，不写；
 *   <li>{@code apply}：载荷字段与 {@code unit.SetArmyPayPolicy} <b>逐字一致</b>（{@code periodDays/phaseDay/
 *       startsOnDay/expiresOnDay?/grainPerHouseholdPerCycle?/clothPerHouseholdPerCycle?/
 *       moneyPerHouseholdPerCycle?/enabled?}）；{@code preview}（缺省 true）用 {@link MilitaryPayPolicy}
 *       构造器做 全部排期/逐值不变量校验 + 家户键 ⊆ {@code Unit.households}，返回前后对比；{@code preview=false} 直接组 JSON
 *       提交该命令（一条命令 = 一条 revision）。
 * </ul>
 *
 * <p>★★ <b>停发语义与命令层同源</b>：三表全空 ⇒ {@link MilitaryPayPolicy#disabled()}（允许，表示停发；此时排期字段可整组省略）； {@code
 * enabled=true} + 三表全空 / {@code enabled=false} + 非空腿 ⇒ 具名拒。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；只写 unit 命名空间（声明 {@code unit}
 * UNRESTRICTED）。工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。
 *
 * <p>★★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-army}。它原来继承 app 的
 * {@code AbstractHouseholdGmTool}（那是 social 侧 GM 窄写的共同基类，服务四条仍在 app 的家户/人口工具）——本类**只用到那条骨架里
 * "preview / apply / reason / 资源围栏 / 结局折叠"这一段**，故随迁时把它**内联**在这里，而不是在 army 里造一个只有一个子类的抽象基类
 * （那会是一条"看着能复用、其实没人复用"的假接缝）。骨架的逐条口径与原文案（含 {@code planOnly} 内部参数、错误折叠文案）一字未改， 对宿主的直连换成构造期注入的 {@link
 * ToolWritePort} / {@link ToolReadPort}。
 */
public final class GmArmyPayPolicyTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.armyPayPolicy";

  /** 本工具只写 unit 命名空间（GM 侧 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest UNIT_WRITE =
      ResourceManifest.of(ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;

  public GmArmyPayPolicyTool(ToolWritePort writes, ToolReadPort reads) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 设置军队单位军俸政策（unit.SetArmyPayPolicy 的窄封装，整体替换；一条命令 = 一条 revision）："
        + "action=view(缺省)/apply。view 要求 unitId 存在且带 ArmyFormation，返回当前 militaryPayPolicy"
        + "（periodDays/phaseDay/startsOnDay/expiresOnDay/enabled + 三张逐户表）。apply 载荷 "
        + "{unitId, periodDays(>0), phaseDay([0,periodDays)), startsOnDay(≥0), expiresOnDay?(缺省=永久，必须 ≥ startsOnDay),"
        + " grainPerHouseholdPerCycle?{家户:整数>0}, clothPerHouseholdPerCycle?, moneyPerHouseholdPerCycle?, enabled?}；"
        + "三表全空 = disabled()（停发，排期可整组省略）；enabled=true + 三表全空 / enabled=false + 非空腿 ⇒ 具名拒；"
        + "列出的家户必须在 Unit.households 里。preview(缺省 true)=只算不写；preview=false 提交命令。"
        + "返回 {preview, submitted, action, unitId, policyBefore/policyAfter, households, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("action", ToolSchema.prop("string", "view（缺省）| apply"));
    props.put("unitId", ToolSchema.prop("string", "单位 id（必填；必须存在且带 ArmyFormation）"));
    props.put(
        "periodDays", ToolSchema.prop("integer", "apply：周期天数 > 0（与 phaseDay/startsOnDay 同给同省）"));
    props.put("phaseDay", ToolSchema.prop("integer", "apply：相位日 ∈ [0, periodDays)"));
    props.put("startsOnDay", ToolSchema.prop("integer", "apply：起始绝对世界日 ≥ 0"));
    props.put("expiresOnDay", ToolSchema.prop("integer", "apply：到期绝对世界日（缺省=永久；必须 ≥ startsOnDay）"));
    props.put(
        "grainPerHouseholdPerCycle",
        ToolSchema.prop("object", "apply：{家户 id: 每周期粮额（整数 > 0）}；缺省空表"));
    props.put(
        "clothPerHouseholdPerCycle",
        ToolSchema.prop("object", "apply：{家户 id: 每周期布额（整数 > 0）}；缺省空表"));
    props.put(
        "moneyPerHouseholdPerCycle",
        ToolSchema.prop("object", "apply：{家户 id: 每周期钱额（整数 > 0）}；缺省空表"));
    props.put("enabled", ToolSchema.prop("boolean", "apply 可选边界：true=要发（三表不得全空）；false=停发（三表必须全空）"));
    props.put(
        "preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交一条命令（= 一条 revision）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("unitId"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return UNIT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    // ★ app 侧基类的缺省摘要口径（本工具不覆写它）：preview / branch / reason 三件套。
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        name()
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      // ★ 军俸政策载荷没有工具级 reason（停发/发放在 payload 里，没有统一审计理由字段）⇒ 沿用 app 侧基类的
      //   requiresReason()=false 口径：可选、缺省 null。
      String reason = ToolSchema.optionalText(args, "reason", null);
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      // ★ D3 内部参数：不进 jsonSchema / 工具描述，只被系统执行器注入（见 ToolResults.plannedCommandsView 的同款视图）。
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedArg);
      }
      if (!preview && expectedArg == null) {
        throw new IllegalArgumentException(
            "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedArg == null ? -1L : expectedArg;
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      return run(new Request(state, branch, expectedRevision, preview, planOnly, reason, args));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（与其余写工具同一条）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", name() + " 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 一次工具调用的共同输入（preview 与 apply 共用同一份 base 状态与坐标）。 */
  private record Request(
      SimulationState state,
      BranchId branch,
      long expectedRevision,
      boolean preview,
      boolean planOnly,
      String reason,
      Map<String, Object> args) {

    Request {
      // ★ 参数树来自工具上下文，构造期做只读快照：run() 只读它（全仓 grep 无 args 写点），
      //   快照既不改值也不改迭代序（LinkedHashMap 保序），只是不再把外部可变引用透传出去。
      args = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(args, "args")));
    }
  }

  private ToolResult run(Request request) {
    Map<String, Object> args = request.args();
    String action = ToolSchema.optionalText(args, "action", "view").toLowerCase(Locale.ROOT);
    Unit unit = requireUnit(ArmyToolReads.unitState(request.state()), args);
    ArmyFormation formation = requireArmyFormation(unit);
    return switch (action) {
      case "view" -> runView(unit, formation);
      case "apply" -> runApply(request, unit, formation);
      default -> throw new IllegalArgumentException("参数 action 只认 view | apply: " + action);
    };
  }

  private static ToolResult runView(Unit unit, ArmyFormation formation) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "view");
    view.put("unitId", unit.id().value());
    view.put("militaryPayPolicy", policyView(formation.militaryPayPolicy()));
    view.put("households", householdIds(unit.households()));
    return preview(view);
  }

  private ToolResult runApply(Request request, Unit unit, ArmyFormation formation) {
    Map<String, Object> args = request.args();
    MilitaryPayPolicy policy = parsePolicy(args);
    // ★ 家户键 ⊆ Unit.households（域层 UnitState 也会判，工具侧先给同一份具名拒）。
    requireHouseholdsInUnit(unit, policy);
    Map<String, Object> payload = policyPayload(unit.id().value(), policy);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "apply");
    view.put("unitId", unit.id().value());
    Map<String, Object> beforeView = policyView(formation.militaryPayPolicy());
    Map<String, Object> afterView = policyView(policy);
    view.put("policyBefore", beforeView);
    view.put("policyAfter", afterView);
    // ★ 契约 §2.2 的"前后对比"：before/after 与 policyBefore/policyAfter 同值。
    view.put("before", beforeView);
    view.put("after", afterView);
    view.put("households", householdIds(unit.households()));
    if (request.planOnly()) {
      return planOnly(SetArmyPayPolicyHandler.TYPE, payload);
    }
    view.put("commandsPreview", List.of(commandPreview(SetArmyPayPolicyHandler.TYPE, payload)));
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, SetArmyPayPolicyHandler.TYPE, payload, view);
  }

  /** 必须存在（工具侧跨切片校验；unit handler 也会再判一遍）。 */
  private static Unit requireUnit(UnitState units, Map<String, Object> args) {
    String text = ToolSchema.requiredText(args, "unitId");
    Unit unit = units.units().get(UnitId.parse(text));
    if (unit == null) {
      throw new IllegalArgumentException("单位不存在: " + text);
    }
    return unit;
  }

  private static ArmyFormation requireArmyFormation(Unit unit) {
    if (!(unit.module().orElse(null) instanceof ArmyFormation formation)) {
      throw new IllegalArgumentException("单位不带 ArmyFormation: " + unit.id().value());
    }
    return formation;
  }

  /** 家户键 ⊆ 本单位 households；任一越界 ⇒ 具名拒（指名到键）。 */
  private static void requireHouseholdsInUnit(Unit unit, MilitaryPayPolicy policy) {
    Set<HouseholdId> households = new LinkedHashSet<>(unit.households());
    requireKeysIn(
        households, policy.grainPerHouseholdPerCycle().keySet(), "grainPerHouseholdPerCycle");
    requireKeysIn(
        households, policy.clothPerHouseholdPerCycle().keySet(), "clothPerHouseholdPerCycle");
    requireKeysIn(
        households, policy.moneyPerHouseholdPerCycle().keySet(), "moneyPerHouseholdPerCycle");
  }

  private static void requireKeysIn(
      Set<HouseholdId> households, Set<HouseholdId> keys, String field) {
    for (HouseholdId key : keys) {
      if (!households.contains(key)) {
        throw new IllegalArgumentException("字段 " + field + " 的家户不属于本单位: " + key.value());
      }
    }
  }

  /**
   * 与 {@code UnitPayloads.requireMilitaryPayPolicy} 同口径解析：排期三件同给同省、{@code expiresOnDay} 可缺省、
   * 三表可缺省、三表全空归一到 {@link MilitaryPayPolicy#disabled()}、可选 {@code enabled} 只作显式边界。
   */
  private static MilitaryPayPolicy parsePolicy(Map<String, Object> args) {
    Map<HouseholdId, Long> grain = amountsArg(args, "grainPerHouseholdPerCycle");
    Map<HouseholdId, Long> cloth = amountsArg(args, "clothPerHouseholdPerCycle");
    Map<HouseholdId, Long> money = amountsArg(args, "moneyPerHouseholdPerCycle");
    boolean allEmpty = grain.isEmpty() && cloth.isEmpty() && money.isEmpty();
    Optional<Boolean> enabled = ToolSchema.optionalBoolean(args, "enabled");
    if (enabled.isPresent()) {
      if (enabled.get() && allEmpty) {
        throw new IllegalArgumentException(
            "军俸政策声明 enabled=true 但 grain/cloth/money 三表全空（要发就至少给一条腿；"
                + "停发请用三表全空且不带 enabled=true 的载荷）");
      }
      if (!enabled.get() && !allEmpty) {
        throw new IllegalArgumentException("军俸政策声明 enabled=false 却带了非空腿（disabled 只能是三张空表）");
      }
    }
    boolean periodGiven = args.get("periodDays") != null;
    boolean phaseGiven = args.get("phaseDay") != null;
    boolean startsGiven = args.get("startsOnDay") != null;
    boolean expiresGiven = args.get("expiresOnDay") != null;
    boolean anySchedule = periodGiven || phaseGiven || startsGiven || expiresGiven;
    boolean fullSchedule = periodGiven && phaseGiven && startsGiven;
    if (anySchedule && !fullSchedule) {
      throw new IllegalArgumentException(
          "军俸政策的排期字段必须同时给或同时省略（periodDays/phaseDay/startsOnDay；expiresOnDay 只在给排期时可带）");
    }
    if (!anySchedule) {
      if (!allEmpty) {
        throw new IllegalArgumentException("军俸政策带非空腿时必须给 periodDays/phaseDay/startsOnDay");
      }
      return MilitaryPayPolicy.disabled();
    }
    long periodDays = ToolSchema.requiredLong(args, "periodDays");
    long phaseDay = ToolSchema.requiredLong(args, "phaseDay");
    long startsOnDay = ToolSchema.requiredLong(args, "startsOnDay");
    Long expiresArg = ToolSchema.optionalLong(args, "expiresOnDay");
    OptionalLong expiresOnDay =
        expiresArg == null ? OptionalLong.empty() : OptionalLong.of(expiresArg);
    if (allEmpty) {
      MilitaryPayPolicy.requireValidSchedule(periodDays, phaseDay, startsOnDay, expiresOnDay);
      return MilitaryPayPolicy.disabled();
    }
    return new MilitaryPayPolicy(
        periodDays, phaseDay, startsOnDay, expiresOnDay, grain, cloth, money);
  }

  /** {@code {家户 id:整数}} 参数 ⇒ 有序表；缺省/JSON null ⇒ 空表（值与键的合法性交构造器判）。 */
  private static Map<HouseholdId, Long> amountsArg(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是 {\"家户 id\":整数} 对象");
    }
    Map<HouseholdId, Long> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空家户 id");
      }
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalArgumentException("参数 " + name + " 的值必须是整数: " + key);
      }
      out.put(HouseholdId.parse(key), number.longValue());
    }
    return out;
  }

  /** 政策 ⇒ 命令载荷（disabled 用最短停发载荷 {unitId}，其余字段与 handler 解析逐字对齐）。 */
  private static Map<String, Object> policyPayload(String unitId, MilitaryPayPolicy policy) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unitId);
    if (!policy.enabled()) {
      return payload;
    }
    payload.put("periodDays", policy.periodDays());
    payload.put("phaseDay", policy.phaseDay());
    payload.put("startsOnDay", policy.startsOnDay());
    policy.expiresOnDay().ifPresent(value -> payload.put("expiresOnDay", value));
    payload.put("grainPerHouseholdPerCycle", amountsPayload(policy.grainPerHouseholdPerCycle()));
    payload.put("clothPerHouseholdPerCycle", amountsPayload(policy.clothPerHouseholdPerCycle()));
    payload.put("moneyPerHouseholdPerCycle", amountsPayload(policy.moneyPerHouseholdPerCycle()));
    return payload;
  }

  /** 政策 ⇒ 只读视图（排期 + 三表 + enabled；sink/永久显式 null）。 */
  private static Map<String, Object> policyView(MilitaryPayPolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("enabled", policy.enabled());
    view.put("periodDays", policy.periodDays());
    view.put("phaseDay", policy.phaseDay());
    view.put("startsOnDay", policy.startsOnDay());
    view.put(
        "expiresOnDay",
        policy.expiresOnDay().isPresent() ? policy.expiresOnDay().getAsLong() : null);
    view.put("grainPerHouseholdPerCycle", amountsPayload(policy.grainPerHouseholdPerCycle()));
    view.put("clothPerHouseholdPerCycle", amountsPayload(policy.clothPerHouseholdPerCycle()));
    view.put("moneyPerHouseholdPerCycle", amountsPayload(policy.moneyPerHouseholdPerCycle()));
    return view;
  }

  private static Map<String, Object> amountsPayload(Map<HouseholdId, Long> amounts) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : amounts.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }

  // ── 提交与折叠（app 侧 AbstractHouseholdGmTool 骨架的对应段，逐条同口径）────────────────────

  /**
   * 单条命令提交与三结局折叠（preview/apply 的结果视图由调用方先搭好）：只有一条命令的工具走这里，落盘的 {@code command_type}
   * 就是命令类型本身（与批提交的批行区分开）。preview 一律不调本方法。
   *
   * <p>★ 命令 id 由写口适配器生成并回报（{@link ToolWritePort.Receipt}）⇒ 视图里的 {@code commandId} 与落盘信封里的
   * 那一个逐字相同（不再是"工具另生成一个"）。
   */
  private ToolResult submitCommand(
      Request request, String type, Map<String, Object> payload, Map<String, Object> view) {
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(
            request.branch(),
            () -> new RevisionId(request.expectedRevision()),
            type,
            ToolResults.json(payload));
    String commandId = receipt.commandId();
    view.put("preview", false);
    view.put("submitted", true);
    view.put("commandId", commandId);
    return switch (receipt.outcome()) {
      case ToolWritePort.Outcome.Committed committed -> {
        view.put("submission", ToolResults.committedView(committed.ref(), commandId, commandId));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.Outcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }

  /** preview 结果（一个字节都没写）。 */
  private static ToolResult preview(Map<String, Object> view) {
    view.put("preview", true);
    view.put("submitted", false);
    return ToolResults.ok(view);
  }

  /**
   * D3 内部执行器专用：把这条工具本来要提交的命令组批返回，**一个字节都不写**（不调写口）。
   *
   * <p>★ 只在 {@code !preview && planOnly} 时被调用；{@code planOnly} 不进 jsonSchema / 工具描述。
   */
  private static ToolResult planOnly(String type, Map<String, Object> payload) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", false);
    view.put("submitted", false);
    view.put("planOnly", true);
    // ★ 逐条只给 {type, payloadJson}——与 app 侧 ToolSupport.plannedCommandsView 的字段集逐字相同
    //   （它**不发** commandPreview 里的裸 payload）。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", type);
    row.put("payloadJson", ToolResults.json(payload));
    view.put("plannedCommands", List.of(row));
    return ToolResults.ok(view);
  }

  /** 一条命令预览（type + 裸载荷 + payloadJson；与写口组包同源）。 */
  private static Map<String, Object> commandPreview(String type, Map<String, Object> payload) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", type);
    row.put("payload", payload);
    row.put("payloadJson", ToolResults.json(payload));
    return row;
  }

  /** 家户 id 列表 ⇒ 裸字符串列表（载荷/视图共用）。 */
  private static List<String> householdIds(List<HouseholdId> households) {
    List<String> out = new ArrayList<>(households.size());
    for (HouseholdId household : households) {
      out.add(household.value());
    }
    return List.copyOf(out);
  }
}
