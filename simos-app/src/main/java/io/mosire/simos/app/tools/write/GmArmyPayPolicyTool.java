package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.SetArmyPayPolicyHandler;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
public final class GmArmyPayPolicyTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.armyPayPolicy";

  /** 本工具只写 unit 命名空间（GM 侧 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest UNIT_WRITE =
      ResourceManifest.of(ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"));

  public GmArmyPayPolicyTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  /** 军俸政策载荷没有工具级 reason（停发/发放在 payload 里，没有统一审计理由字段）。 */
  @Override
  protected boolean requiresReason() {
    return false;
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
    props.put("action", ToolSupport.prop("string", "view（缺省）| apply"));
    props.put("unitId", ToolSupport.prop("string", "单位 id（必填；必须存在且带 ArmyFormation）"));
    props.put(
        "periodDays", ToolSupport.prop("integer", "apply：周期天数 > 0（与 phaseDay/startsOnDay 同给同省）"));
    props.put("phaseDay", ToolSupport.prop("integer", "apply：相位日 ∈ [0, periodDays)"));
    props.put("startsOnDay", ToolSupport.prop("integer", "apply：起始绝对世界日 ≥ 0"));
    props.put("expiresOnDay", ToolSupport.prop("integer", "apply：到期绝对世界日（缺省=永久；必须 ≥ startsOnDay）"));
    props.put(
        "grainPerHouseholdPerCycle",
        ToolSupport.prop("object", "apply：{家户 id: 每周期粮额（整数 > 0）}；缺省空表"));
    props.put(
        "clothPerHouseholdPerCycle",
        ToolSupport.prop("object", "apply：{家户 id: 每周期布额（整数 > 0）}；缺省空表"));
    props.put(
        "moneyPerHouseholdPerCycle",
        ToolSupport.prop("object", "apply：{家户 id: 每周期钱额（整数 > 0）}；缺省空表"));
    props.put(
        "enabled", ToolSupport.prop("boolean", "apply 可选边界：true=要发（三表不得全空）；false=停发（三表必须全空）"));
    props.put(
        "preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交一条命令（= 一条 revision）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId"));
  }

  @Override
  protected ResourceManifest resourceManifest() {
    return UNIT_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources() {
    return WRITE_RESOURCES;
  }

  @Override
  protected ToolResult run(Request request) {
    Map<String, Object> args = request.args();
    String action = ToolSupport.optionalText(args, "action", "view").toLowerCase(Locale.ROOT);
    Unit unit = requireUnit(ToolSupport.unitState(request.state()), args);
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
      return planOnly(request, SetArmyPayPolicyHandler.TYPE, payload);
    }
    view.put("commandsPreview", List.of(commandPreview(SetArmyPayPolicyHandler.TYPE, payload)));
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, SetArmyPayPolicyHandler.TYPE, payload, view);
  }

  /** 必须存在（工具侧跨切片校验；unit handler 也会再判一遍）。 */
  private static Unit requireUnit(UnitState units, Map<String, Object> args) {
    String text = ToolSupport.requiredText(args, "unitId");
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
    Optional<Boolean> enabled = ToolSupport.optionalBoolean(args, "enabled");
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
    long periodDays = ToolSupport.requiredLong(args, "periodDays");
    long phaseDay = ToolSupport.requiredLong(args, "phaseDay");
    long startsOnDay = ToolSupport.requiredLong(args, "startsOnDay");
    Long expiresArg = ToolSupport.optionalLong(args, "expiresOnDay");
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
}
