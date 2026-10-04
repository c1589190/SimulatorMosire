package io.mosire.simos.app.tools.write;

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
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
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
 * ★★ <b>S3a 家户/人口 GM 窄写的共同基类</b>（2026-10-09）：四件事在一条工具里——<b>preview / apply、reason、资源围栏、
 * 结局折叠</b>——形态照 {@code EconomyAdjustTool} / {@code GovRemitTool} 的 GM 窄写口径。
 *
 * <p>★★ <b>preview=true（缺省）一个字节都不写</b>：只用 {@link QueryService} 读 base 状态并跑与落盘同源的纯推导
 * （Social 侧走 {@code HouseholdBook} 的纯函数、Unit 侧走 {@code UnitOperations} 的纯函数），把目标状态/前后差异/逐条命令
 * 预览放进结果；{@code preview=false} 才组命令走 {@link CoreSimos#submitBatch}（一批 = 一条 revision，整批原子）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有这些工具；工具名不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}（它们提交的命令类型由各自的 handler 注册进 catalog）。
 *
 * <p>★ <b>资源围栏</b>：子类声明自己的命名空间（只写 Social / 同时写 Social + Unit），{@code requireAll(WRITE, …)} 与其余 GM
 * 窄写同制；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错/{@code preview=false} 缺 {@code expectedRevision}/域校验不过 ⇒ {@code BAD_REQUEST}
 * （零 revision）；批被拒 ⇒ {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head。
 */
abstract class AbstractHouseholdGmTool implements AgentTool {

  /** 本包唯一的一台 mapper：共享基座出厂配置（只用于把 view/参数树折成 JSON 文本，不解析领域类型）。 */
  protected static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 家户类工具只写 social 命名空间（GM 侧 social unlimited ⇒ 逐条判通过）。 */
  protected static final ResourceManifest SOCIAL_WRITE =
      ResourceManifest.of(ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 组合工具写 social + unit 两个命名空间（GM 侧两者都 unlimited）。 */
  protected static final ResourceManifest SOCIAL_AND_UNIT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  protected final CoreSimos core;
  protected final QueryService query;
  protected final String initiator;

  protected AbstractHouseholdGmTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  /** 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。 */
  @Override
  public final ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public final ResourceManifest resources() {
    return resourceManifest();
  }

  @Override
  public final ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);
  }

  /** 审批摘要：子类可覆写加自己的关键参数；缺省给 preview/branch/reason 三件套。 */
  protected String summary(Map<String, Object> args) {
    return name()
        + " preview="
        + args.getOrDefault("preview", true)
        + " branch="
        + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
        + " reason="
        + args.get("reason");
  }

  @Override
  public final ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, writeResources());
      Map<String, Object> args = context.arguments();
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedArg);
      }
      if (!preview && expectedArg == null) {
        throw new IllegalArgumentException(
            "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedArg == null ? -1L : expectedArg;
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      return run(new Request(state, branch, expectedRevision, preview, reason, args));
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
  protected record Request(
      SimulationState state,
      BranchId branch,
      long expectedRevision,
      boolean preview,
      String reason,
      Map<String, Object> args) {}

  /** 子类实现：读 base、跑纯推导、preview 出视图或提交命令批。 */
  protected abstract ToolResult run(Request request);

  protected abstract ResourceManifest resourceManifest();

  protected abstract List<ResourceId> writeResources();

  // ── 提交与折叠 ───────────────────────────────────────────────────────────────────────

  /**
   * 组一条信封（批内所有命令共享同一个 {@code batchId} = commandId = correlationId；全工具唯一生成点）。
   *
   * <p>★ 一批 = 一条 revision（{@link io.mosire.simos.core.command.CommandBus#submitBatch}）；共享 id 让审计行、
   * 各命令事件与工具返回的 {@code submission} 能对齐到同一次调用。
   */
  protected final CommandEnvelope envelope(
      Request request, String batchId, String type, Map<String, Object> payload) {
    return new CommandEnvelope(
        batchId,
        batchId,
        initiator,
        request.branch(),
        new RevisionId(request.expectedRevision()),
        type,
        ToolSupport.json(payload));
  }

  /**
   * 批提交与三结局折叠（preview/apply 的结果视图由调用方先搭好）：
   * {@code Committed → ok（带新坐标）}；{@code Conflict → CONFLICT（真实 head）}；{@code Rejected → REJECTED（逐条真拒因）}。
   */
  protected final ToolResult submitBatch(
      Request request, List<CommandEnvelope> batch, Map<String, Object> view) {
    return submitBatch(request, batch, view, () -> {});
  }

  /**
   * 同 {@link #submitBatch(Request, List, Map)}，但允许在**批真的提交成功**之后跑一个钩子（例如 assign/detach 的
   * {@code UnitLog} 生命周期事件）——被拒/冲突不跑，日志不会说谎。
   */
  protected final ToolResult submitBatch(
      Request request,
      List<CommandEnvelope> batch,
      Map<String, Object> view,
      Runnable onCommitted) {
    BatchResult result = core.submitBatch(batch);
    view.put("preview", false);
    view.put("submitted", true);
    if (result instanceof BatchResult.Committed committed) {
      onCommitted.run();
      view.put("submission", ToolSupport.committedView(committed.ref(), batchId(batch), batchId(batch)));
      return ToolSupport.ok(view);
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolSupport.json(view));
    }
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    List<Map<String, Object>> rows = new ArrayList<>();
    List<CommandOutcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      CommandResult outcome = outcomes.get(i).result();
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }

  /** 批内命令共享同一个 batchId（= 首条命令的命令 id）。 */
  protected static String batchId(List<CommandEnvelope> batch) {
    return batch.get(0).commandId();
  }

  /**
   * 单条命令提交与三结局折叠（preview/apply 的结果视图由调用方先搭好）：只有一条命令的工具走这里，落盘的
   * {@code command_type} 就是命令类型本身（与 {@code core.submitBatch} 的批行区分开）。preview 一律不调本方法。
   */
  protected final ToolResult submitCommand(
      Request request, String type, Map<String, Object> payload, Map<String, Object> view) {
    String commandId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            commandId,
            commandId,
            initiator,
            request.branch(),
            new RevisionId(request.expectedRevision()),
            type,
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

  /** preview 结果（一个字节都没写）。 */
  protected static ToolResult preview(Map<String, Object> view) {
    view.put("preview", true);
    view.put("submitted", false);
    return ToolSupport.ok(view);
  }

  /** 无操作结果（幂等调用：不组命令、不落 revision）。 */
  protected static ToolResult noop(Map<String, Object> view, String reason) {
    view.put("preview", false);
    view.put("submitted", false);
    view.put("noop", true);
    view.put("reason", reason);
    return ToolSupport.ok(view);
  }

  // ── 参数/视图小件 ───────────────────────────────────────────────────────────────────

  /** 必填的 {@code location} 参数：{@code {type:HEX|UNIT, hex|unitId}}（大小写不敏感，缺 type 时按字段推断）。 */
  protected static HouseholdLocation locationArg(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为 {type:HEX|UNIT, hex|unitId} 对象");
    }
    String type = locationTypeArg(map, name);
    if ("hex".equalsIgnoreCase(type)) {
      Object hexRaw = map.get("hex");
      return new HouseholdLocation.Hex(hexArg(hexRaw, name + ".hex"));
    }
    if ("unit".equalsIgnoreCase(type)) {
      return new HouseholdLocation.Unit(requiredTextArg(map, "unitId", name));
    }
    throw new IllegalArgumentException("参数 " + name + " 的 type 只认 HEX|UNIT（大小写不敏感）: " + type);
  }

  private static String locationTypeArg(Map<?, ?> map, String name) {
    Object typeRaw = map.get("type");
    if (typeRaw == null) {
      typeRaw = map.get("@type");
    }
    if (typeRaw != null) {
      if (!(typeRaw instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + ".type 必须是非空文本");
      }
      return text;
    }
    boolean hasHex = map.get("hex") != null;
    boolean hasUnit = map.get("unitId") != null;
    if (hasHex && !hasUnit) {
      return "hex";
    }
    if (hasUnit && !hasHex) {
      return "unit";
    }
    throw new IllegalArgumentException(
        "参数 " + name + " 必须给 type（HEX|UNIT）或二选一的 hex/unitId");
  }

  /** {@code {q,r}} 参数 ⇒ {@link HexCoord}。 */
  protected static HexCoord hexArg(Object raw, String name) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是 {q,r} 对象");
    }
    return new HexCoord(integralArg(map.get("q"), name + ".q"), integralArg(map.get("r"), name + ".r"));
  }

  private static int integralArg(Object value, String name) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是整数");
    }
    long longValue = number.longValue();
    if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + " 超出 int 范围: " + longValue);
    }
    return (int) longValue;
  }

  private static String requiredTextArg(Map<?, ?> map, String key, String name) {
    Object value = map.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + "." + key + " 必填且为非空文本");
    }
    return text;
  }

  /** 可选的字符串参数（缺席 ⇒ null；出现但非字符串/空白 ⇒ 抛）。 */
  protected static String optionalTextArg(Map<?, ?> map, String key, String name) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + "." + key + " 若给出必须是非空文本");
    }
    return text;
  }

  /** {@code {键:字符串}} 参数 ⇒ 冻结表（缺席 ⇒ 空表）；值非字符串 ⇒ 抛。 */
  protected static Map<String, String> stringMapArg(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是 {键:字符串} 对象");
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空文本");
      }
      if (!(entry.getValue() instanceof String value)) {
        throw new IllegalArgumentException("参数 " + name + " 的值必须是字符串: " + key);
      }
      out.put(key, value);
    }
    return java.util.Collections.unmodifiableMap(out);
  }

  /** 可选的率数组成员（S3a 的 {@code [{bracketId,sex,birthRatePerMillePerTick?,deathRatePerMillePerTick?}…]}）。 */
  protected static List<HouseholdVitalRate> vitalRatesArg(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是率对象数组");
    }
    List<HouseholdVitalRate> rates = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("参数 " + name + " 的元素必须是率对象");
      }
      String bracketId = requiredTextArg(map, "bracketId", name);
      String sexText = requiredTextArg(map, "sex", name);
      Sex sex;
      try {
        sex = Sex.valueOf(sexText);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("参数 " + name + ".sex 必须是 MALE|FEMALE: " + sexText, e);
      }
      long birth = optionalLongArg(map, "birthRatePerMillePerTick", name);
      long death = optionalLongArg(map, "deathRatePerMillePerTick", name);
      rates.add(new HouseholdVitalRate(bracketId, sex, birth, death));
    }
    return List.copyOf(rates);
  }

  /** 可选的整数字段（缺席/JSON null ⇒ 0；非整数 ⇒ 抛）。 */
  private static long optionalLongArg(Map<?, ?> map, String key, String name) {
    Object value = map.get(key);
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + "." + key + " 必须是整数: " + text, e);
      }
    }
    throw new IllegalArgumentException("参数 " + name + "." + key + " 必须是整数");
  }

  /** {@link HouseholdLocation} ⇒ 命令载荷形状（与 {@code SocialPayloads.requireLocation} 同源）。 */
  protected static Map<String, Object> locationPayload(HouseholdLocation location) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (location instanceof HouseholdLocation.Hex hex) {
      payload.put("type", "HEX");
      Map<String, Object> at = new LinkedHashMap<>();
      at.put("q", hex.hex().q());
      at.put("r", hex.hex().r());
      payload.put("hex", at);
      return payload;
    }
    if (location instanceof HouseholdLocation.Unit unit) {
      payload.put("type", "UNIT");
      payload.put("unitId", unit.unitId());
      return payload;
    }
    throw new IllegalStateException("未知 HouseholdLocation 实现: " + location.getClass().getName());
  }

  /** {@link HouseholdLocation} ⇒ 只读视图形状。 */
  protected static Map<String, Object> locationView(HouseholdLocation location) {
    Map<String, Object> view = new LinkedHashMap<>();
    if (location instanceof HouseholdLocation.Hex hex) {
      view.put("type", "HEX");
      view.put("q", hex.hex().q());
      view.put("r", hex.hex().r());
      return view;
    }
    if (location instanceof HouseholdLocation.Unit unit) {
      view.put("type", "UNIT");
      view.put("unitId", unit.unitId());
      return view;
    }
    throw new IllegalStateException("未知 HouseholdLocation 实现: " + location.getClass().getName());
  }

  /** 家户 id 列表 ⇒ 裸字符串列表（载荷/视图共用）。 */
  protected static List<String> householdIds(List<HouseholdId> households) {
    List<String> out = new ArrayList<>(households.size());
    for (HouseholdId household : households) {
      out.add(household.value());
    }
    return List.copyOf(out);
  }

  /** 单位必须存在（app 侧跨切片校验；social 域看不到 unit）。 */
  protected static Unit requireUnit(UnitState units, String rawUnitId, String field) {
    Unit unit = units.units().get(new UnitId(rawUnitId));
    if (unit == null) {
      throw new IllegalArgumentException("参数 " + field + " 指定的单位不存在: " + rawUnitId);
    }
    return unit;
  }

  /** 家户必须存在（app 侧跨切片校验；social 域自己的 handler 也会再判一遍）。 */
  protected static void requireHousehold(SocialData base, HouseholdId id) {
    if (!base.households().containsKey(id)) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
  }

  /** HEX 必须在本世界地图上（detach/移动的落点校验；与 map 读口同口径）。 */
  protected static void requireHexOnMap(SimulationState state, HexCoord hex) {
    if (!ToolSupport.gameMap(state).hexes().containsKey(hex)) {
      throw new IllegalArgumentException("目标格不在本世界地图上: (" + hex.q() + "," + hex.r() + ")");
    }
  }

  /** 一条命令预览（type + 裸载荷 + payloadJson；与 {@link #envelope} 的组包同源）。 */
  protected static Map<String, Object> commandPreview(String type, Map<String, Object> payload) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", type);
    row.put("payload", payload);
    row.put("payloadJson", ToolSupport.json(payload));
    return row;
  }

  /** 率表 ⇒ 载荷数组（保序；create / rates 两个工具共用）。 */
  protected static List<Map<String, Object>> ratesPayload(List<HouseholdVitalRate> rates) {
    List<Map<String, Object>> out = new ArrayList<>(rates.size());
    for (HouseholdVitalRate rate : rates) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("bracketId", rate.bracketId());
      row.put("sex", rate.sex().name());
      row.put("birthRatePerMillePerTick", rate.birthRatePerMillePerTick());
      row.put("deathRatePerMillePerTick", rate.deathRatePerMillePerTick());
      out.add(row);
    }
    return out;
  }
}
