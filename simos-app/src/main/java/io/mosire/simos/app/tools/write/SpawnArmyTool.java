package io.mosire.simos.app.tools.write;

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
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.unit.spawnArmy}（P5，2026-10-01 后端 + MCP 稳定化计划）：<b>GM 组合工具</b>——按格直接建军，<b>不抽人口、
 * 不抽粮饷</b>；{@code unit.CreateUnit} → [role 非空: {@code unit.SetArmyFormation}] → {@code
 * sd.CreateArmy} → {@code sd.PutInfo} 同批落一条 revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：root 单位在 {@code unit} 切片、Army 归属在 {@code sd} 切片，单条命令只能落一个
 * 命名空间。本工具走 {@link CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link SpawnArmyPlan#plan}（不碰 {@link ToolContext} /
 * {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。参数形状解析（类型 / 整数 / 装备表 / 词表）在本类； 前置状态校验与批载荷组装在
 * Plan，不出现第二份推导。
 *
 * <p>★★ <b>GM 特权</b>：直接建军允许无人口、无国库；{@code member} 直接写进新单位（仍须 ≥ 1），不读写 social/actor。★ {@code
 * raiseUnit} 保持抽取语义，一个字不动。
 *
 * <p>★★ <b>{@code masterGov} 的双边语义</b>：给了就必须存在且带 GovernmentFormation；同批写入 {@code
 * sd.CreateArmy.masterGovUnitId}。{@code role} 非空时才同批落 {@code unit.SetArmyFormation}，也把同一个 {@code
 * masterGov} 写进它的 {@code masterGov}。{@code role} 为空但 {@code masterGov} 给了 ⇒ 只写 sd 侧，<b>unit 侧未设
 * ArmyFormation.masterGov</b>；preview 的 {@code armyFormationNote} 会明确说明。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。
 *
 * <p>★ <b>资源声明</b>：只写 {@code unit}/{@code sd} 两个命名空间（{@link ResourcePolicy#UNRESTRICTED}，GM 侧两面
 * unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM 窄写同制。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / 负值 / member &lt; 1 / speed &lt; 1 / mobility 不在 [1,1000] /
 * equipment 值 &lt; 0 / hex 不在当前 GameMap / unitId 或 armyId 已存在 / parent 不存在或当刻不同格 / masterGov 不存在或非
 * GOV ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域拒 ⇒ {@code REJECTED}
 * 带逐条真拒因； 提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class SpawnArmyTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.spawnArmy";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = root 单位 canonical，一次建军一条）。 */
  public static final String INFO_KEY = "spawnArmy";

  /** 本工具只写 unit / sd 两个命名空间（GM 侧两面 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest SPAWN_ARMY_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #SPAWN_ARMY_WRITE} 同源的逐命名空间粗断言（GM 两侧 unlimited，顺序 = 批内命令命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与其余 GM 组合工具同制；本工具资源声明是两个命名空间的粗断言， 落点存在性按当刻 {@code
   *     GameMap.hexes()} 判，不当路径用）
   */
  public SpawnArmyTool(CoreSimos core, QueryService query, String initiator, String mapId) {
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
    return "GM 按格直接建军（组合工具，一批 = 一条 revision；GM 特权：不抽人口、不抽粮饷）。★ S3b：Unit.manpower 已退役，本工具尚未接线到家户来源 ⇒ apply 会被 unit.CreateUnit 具名拒（新单位人口必须先有 Social 家户）；本工具暂只保留 preview 与失败路径："
        + "参数 {unitId(必填), name(必填), q(必填 int), r(必填 int), member(必填 int, >=1；新单位人力表落成单条 "
        + "{type=\""
        + SpawnArmyPlan.DEFAULT_MANPOWER_TYPE
        + "\", amount=member}), equipment?(可选 {字符串:整数}，缺省 {}，值 >=0；按输入序转成装备表), "
        + "speed?(可选，缺省 3，>=1), "
        + "mobilityPerMille?(可选，缺省 1000，必须在 1..1000), "
        + "parent?(可选; 给了必须存在且当刻有效位置与 (q,r) 同格), "
        + "status?(可选 MOVING|RESTING|ENGAGED，缺省 RESTING), "
        + "armyId?(可选; 缺省 unitId + \"-army\"；不得已存在), role?(可选; 给了非空白才同批落 unit.SetArmyFormation), "
        + "masterGov?(可选; 给了必须存在且带 GovernmentFormation；同批写入 sd.CreateArmy.masterGovUnitId，role 非空时也写入 "
        + "unit.SetArmyFormation.masterGov), reason(必填非空白), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 必填，>=0)}。"
        + "前置：member>=1、speed>=1、mobilityPerMille 在 1..1000、equipment 值 >=0、hex 必须存在于当前 GameMap、"
        + "unitId 与 armyId 不得已存在、parent 必须存在且当刻同格、masterGov 必须存在且带 GovernmentFormation、role 给了不得空白。"
        + "批顺序：unit.CreateUnit → [role 非空: unit.SetArmyFormation] → sd.CreateArmy → sd.PutInfo(key="
        + INFO_KEY
        + "，address=root 单位 canonical，value=JSON 字符串)。"
        + "★ 若 role 为空但 masterGov 给了：只写 sd 侧 masterGov，unit 侧未设 ArmyFormation.masterGov（preview 会明确说明）。"
        + "失败具名：坏参数/前置不满足 ⇒ BAD_REQUEST（零 revision）；批内域拒 ⇒ REJECTED（逐条真拒因）；"
        + "提交冲突 ⇒ CONFLICT（真实 head）；资源不匹配 ⇒ 原样抛资源拒因。"
        + "返回 {preview, submitted, tick, unitId, name, armyId, hex, manpower[{type,amount}], equipment[{type,amount}], "
        + "speed, mobility, parent, status, role, masterGov, unitSetArmyFormation, armyFormationNote, commands, "
        + "infoText, conflictPreflight}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "新 root 单位 id（不得与既有单位重复）"));
    props.put("name", ToolSupport.prop("string", "新单位名（非空白）"));
    props.put("q", ToolSupport.prop("integer", "新单位落点 q（int）"));
    props.put("r", ToolSupport.prop("integer", "新单位落点 r（int）"));
    props.put(
        "member",
        ToolSupport.prop(
            "integer",
            "新单位人数（>= 1；GM 直接建军不抽人口）；新单位人力表落成单条 {type=\""
                + SpawnArmyPlan.DEFAULT_MANPOWER_TYPE
                + "\", amount=member}"));
    props.put(
        "equipment",
        ToolSupport.prop("object", "装备 {字符串:整数}（可选，缺省空表；值 >= 0；按输入 map 迭代序转成 [{type,amount}] 表）"));
    props.put("speed", ToolSupport.prop("integer", "速度（可选，缺省 3；>= 1）"));
    props.put("mobilityPerMille", ToolSupport.prop("integer", "机动性（可选，缺省 1000；必须在 1..1000）"));
    props.put("parent", ToolSupport.prop("string", "父单位 id（可选；给了必须存在且当刻有效位置与 (q,r) 同格）"));
    props.put("status", ToolSupport.prop("string", "状态（可选 MOVING|RESTING|ENGAGED，缺省 RESTING）"));
    props.put("armyId", ToolSupport.prop("string", "Army id（可选；缺省 unitId + \"-army\"；不得已存在）"));
    props.put("role", ToolSupport.prop("string", "兵种/职责短名（可选；给了非空白才同批落 unit.SetArmyFormation）"));
    props.put(
        "masterGov",
        ToolSupport.prop("string", "认领的 GOV 单位 id（可选；给了必须存在且带 GovernmentFormation；同批写 sd.CreateArmy）"));
    props.put("reason", ToolSupport.prop("string", "建军原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision（>=0）；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId", "name", "q", "r", "member", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SPAWN_ARMY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "按格直接建军 unitId="
            + args.get("unitId")
            + " name="
            + args.get("name")
            + " q="
            + args.get("q")
            + " r="
            + args.get("r")
            + " member="
            + args.get("member")
            + " armyId="
            + args.getOrDefault("armyId", "(缺省 unitId-army)")
            + " role="
            + args.getOrDefault("role", "(未给)")
            + " masterGov="
            + args.getOrDefault("masterGov", "(未给)")
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
      String unitId = ToolSupport.requiredText(args, "unitId");
      String name = ToolSupport.requiredText(args, "name");
      int q = requiredInt(args, "q");
      int r = requiredInt(args, "r");
      int member = requiredInt(args, "member");
      Map<String, Integer> equipment = optionalEquipment(args);
      int speed = optionalInt(args, "speed", SpawnArmyPlan.DEFAULT_SPEED);
      int mobilityPerMille =
          optionalInt(args, "mobilityPerMille", SpawnArmyPlan.DEFAULT_MOBILITY_PER_MILLE);
      Optional<String> parent = optionalText(args, "parent");
      UnitStatus status =
          parseStatus(
              ToolSupport.optionalText(args, "status", SpawnArmyPlan.DEFAULT_STATUS.name()));
      String armyId = ToolSupport.optionalText(args, "armyId", unitId + "-army");
      Optional<String> role = optionalText(args, "role");
      Optional<String> masterGov = optionalText(args, "masterGov");
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryService.QueryTarget.head(branch)
                  : QueryService.QueryTarget.at(branch, new RevisionId(expectedRevision)));
      SpawnArmyPlan.Plan plan =
          SpawnArmyPlan.plan(
              state,
              unitId,
              name,
              new HexCoord(q, r),
              member,
              equipment,
              speed,
              mobilityPerMille,
              parent,
              status,
              armyId,
              role,
              masterGov);
      Map<String, Object> conflictPreflight = conflictPreflight(branch, expectedRevisionArg);
      if (preview) {
        return ToolSupport.ok(planView(plan, reason, true, false, conflictPreflight));
      }
      return apply(plan, reason, branch, expectedRevision, conflictPreflight);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "按格建军失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数形状解析（类型 / 整数 / 装备表 / 词表；语义不在这里） ───────────────────────────

  /** 必填 int 参数（整型 Number 或整数字符串；浮点有小数、超 int 范围 ⇒ BAD_REQUEST）。 */
  private static int requiredInt(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为整数");
    }
    return toInt(raw, name);
  }

  /** 可选 int 参数：键缺席 ⇒ {@code fallback}；给了但类型 / 范围不对 ⇒ BAD_REQUEST。 */
  private static int optionalInt(Map<String, Object> args, String name, int fallback) {
    Object raw = args.get(name);
    return raw == null ? fallback : toInt(raw, name);
  }

  /** 可选 long 参数：键缺席 ⇒ null；浮点有小数 / 非整数字符串 / 其它类型 ⇒ BAD_REQUEST。 */
  private static Long optionalLong(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      if (number instanceof Double || number instanceof Float) {
        if (hasFraction(number.doubleValue())) {
          throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + raw);
        }
      }
      return number.longValue();
    }
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + text, e);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + raw);
  }

  /** 把 MCP 参数里的整数（Number 或整数字符串）读成 int；浮点有小数 ⇒ 具名拒，不静默截断。 */
  private static int toInt(Object value, String label) {
    if (value instanceof Number number) {
      if (number instanceof Double || number instanceof Float) {
        if (hasFraction(number.doubleValue())) {
          throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + value);
        }
      }
      return requireIntRange(number.longValue(), label, value);
    }
    if (value instanceof String text && !text.isBlank()) {
      long parsed;
      try {
        parsed = Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + text, e);
      }
      return requireIntRange(parsed, label, value);
    }
    throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + value);
  }

  private static int requireIntRange(long value, String label, Object raw) {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + label + " 超出 int 范围: " + raw);
    }
    return (int) value;
  }

  /**
   * 浮点数是否有小数部分（NaN / 无穷 ⇒ 也算"不是整数"）。
   *
   * <p>★ 用 {@link BigDecimal} 精确判定，不写浮点相等/取模比较——那是 SpotBugs 的 {@code FE_FLOATING_POINT_EQUALITY}
   * 靶子。
   */
  private static boolean hasFraction(double value) {
    if (!Double.isFinite(value)) {
      return true;
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().scale() > 0;
  }

  /** 可选文本：缺省 ⇒ 空 Optional；给了空白 / 非文本 ⇒ BAD_REQUEST（类型口径由 {@link ToolSupport#optionalText} 守）。 */
  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    String text = ToolSupport.optionalText(args, name, null);
    return Optional.ofNullable(text);
  }

  /** 可选装备表：缺省/null ⇒ 空表；键必须是非空文本、值必须是 int 范围内的整数（负值由 Plan 具名拒）。 */
  private static Map<String, Integer> optionalEquipment(Map<String, Object> args) {
    Object raw = args.get("equipment");
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> object)) {
      throw new IllegalArgumentException("参数 equipment 若给出必须是 {字符串:整数} 对象");
    }
    Map<String, Integer> equipment = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : object.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 equipment 的键必须是非空文本");
      }
      equipment.put(key, toInt(entry.getValue(), "equipment[" + key + "]"));
    }
    return equipment;
  }

  /** 状态词表：只认 MOVING|RESTING|ENGAGED，别的词给具名拒（不静默当缺省）。 */
  private static UnitStatus parseStatus(String text) {
    try {
      return UnitStatus.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("参数 status 不是合法状态（MOVING|RESTING|ENGAGED）: " + text, e);
    }
  }

  // ── 冲突预检（只读 head，不写） ───────────────────────────────────────────────────────

  /**
   * 乐观并发的 preview 预检：报出 {@code expectedRevision} 与该分支真实 head 是否一致。apply 的冲突判定仍由 {@code submitBatch}
   * 在提交锁内做，并回报真实 head——本预检只是让 preview 先把风险说清楚。
   */
  private Map<String, Object> conflictPreflight(BranchId branch, Long expectedRevision) {
    Optional<RevisionId> head = core.head(branch);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", branch.value());
    view.put("expectedRevision", expectedRevision);
    view.put("headRevision", head.map(RevisionId::value).orElse(null));
    boolean headMatchesExpected;
    if (expectedRevision == null) {
      // preview 没给 expectedRevision ⇒ 读数就是 head，不存在"提交基准已过期"的预检风险。
      headMatchesExpected = true;
    } else {
      long expected = expectedRevision;
      headMatchesExpected = head.map(rev -> rev.value() == expected).orElse(false);
    }
    view.put("headMatchesExpected", headMatchesExpected);
    view.put("wouldConflict", !headMatchesExpected);
    return view;
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序、按需缺席），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      SpawnArmyPlan.Plan plan,
      String reason,
      BranchId branch,
      long expectedRevision,
      Map<String, Object> conflictPreflight) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch =
        buildBatch(batchId, plan, reason, branch, new RevisionId(expectedRevision));
    BatchResult result = core.submitBatch(batch);
    Map<String, Object> view = planView(plan, reason, false, true, conflictPreflight);
    if (result instanceof BatchResult.Committed committed) {
      view.put("submission", ToolSupport.committedView(committed.ref(), batchId, batchId));
      return ToolSupport.ok(view);
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolSupport.json(view));
    }
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
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

  /**
   * 组批：{@code unit.CreateUnit} → [role 非空: {@code unit.SetArmyFormation}] → {@code sd.CreateArmy} →
   * {@code sd.PutInfo}（固定顺序，可复现）。
   *
   * <p>★ 全部共享同一 {@code batchId}（correlationId）与同一 branch/expectedRevision ⇒ {@code submitBatch} 落一条
   * revision。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      SpawnArmyPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(4);
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            SpawnArmyPlan.CREATE_UNIT_TYPE,
            plan.createUnitPayloadJson()));
    if (plan.hasArmyFormationCommand()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              SpawnArmyPlan.SET_ARMY_FORMATION_TYPE,
              plan.setArmyFormationPayloadJson()));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            SpawnArmyPlan.CREATE_ARMY_TYPE,
            plan.createArmyPayloadJson()));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            SpawnArmyPlan.PUT_INFO_TYPE,
            infoPayload(plan, reason)));
    return List.copyOf(batch);
  }

  private CommandEnvelope envelope(
      String batchId,
      BranchId branch,
      RevisionId expectedRevision,
      String type,
      String payloadJson) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        batchId,
        initiator,
        branch,
        expectedRevision,
        type,
        payloadJson);
  }

  // ── 载荷：sd.PutInfo（行动记录） ─────────────────────────────────────────────────────

  /**
   * {@code sd.PutInfo} 载荷：root 单位 canonical 地址 + {@code key="spawnArmy"} + {@code value}=JSON 字符串 +
   * {@code note}=人可读摘要 + {@code tick}=当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一单位的后续行动自然追加序号。
   */
  private static String infoPayload(SpawnArmyPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", SpawnArmyPlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      SpawnArmyPlan.Plan plan,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> conflictPreflight) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("name", plan.name());
    view.put("armyId", plan.armyId());
    view.put("hex", ToolSupport.hexCoord(plan.at()));
    view.put("manpower", ToolSupport.compositionView(plan.manpowerEntries()));
    view.put("equipment", ToolSupport.compositionView(plan.equipment()));
    view.put("speed", plan.speed());
    view.put("mobility", plan.mobilityPerMille());
    view.put("parent", plan.parent().orElse(null));
    view.put("status", plan.status().name());
    view.put("role", plan.role().orElse(null));
    view.put("masterGov", plan.masterGov().orElse(null));
    view.put("unitSetArmyFormation", plan.hasArmyFormationCommand());
    view.put("armyFormationNote", plan.armyFormationNote());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    view.put("conflictPreflight", conflictPreflight);
    return view;
  }
}
