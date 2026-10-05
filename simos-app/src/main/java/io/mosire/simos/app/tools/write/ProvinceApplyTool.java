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
import io.mosire.simos.app.gov.ProvinceDivider;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ★★ {@code simos.province.apply}（P3 一键落盘模式，2026-10-01）：<b>GM-only 组合写工具</b>——用 {@link
 * ProvinceDivider} 的同一份只读建议，把"建议省份"一次落成 `Region + GOV Unit + 决策人`，一批一条 revision。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link ProvinceApplyPlan#derive}（不碰 {@link
 * ToolContext} / {@code CoreSimos}）；本类只做五件事——资源断言、参数形状解析、读 base state、把 Plan 折成视图、组批与折叠结局。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：新建 Region 在 map 切片、GOV 单位在 unit 切片、决策人在 sd
 * 切片；单条命令只能落一个命名空间。本工具走 {@link CoreSimos#submitBatch}（同 batchId/branch/expectedRevision ⇒ 一批 = 一条
 * revision，原子）。
 *
 * <p>★★ <b>相关结构门</b>（目标 Region 内/指向目标 Region 的既有结构，只读 base state）：命中带 {@code GovFormation} 的单位 / 其
 * Gov 决策人 / 覆盖目标 Region（或完全落在目标 hex 集内的 Region）的 jurisdiction / id 形如 {@code
 * sanitize(regionId)+"__P"+数字} 或 {@code __CAP} 的既有 Region ⇒ <b>不 submit、零 revision</b>，返回 {@code
 * ToolResult.error("NEEDS_CLEAR", JSON)}，hint 指路 {@code simos.region.clearStructures}。
 *
 * <p>★★ <b>重叠门</b>：目标 Region 与既有 Region 相交时列出全部相交项（id/name/tag/相交格数）；未显式 {@code
 * overrideOverlaps=true} ⇒ <b>不 submit、零 revision</b>，返回 {@code
 * ToolResult.error("OVERLAP_OVERRIDE_REQUIRED", JSON)}。区域重叠 ≠ 省籍——override 后把 overlaps 放进 preview 的
 * warnings 继续。
 *
 * <p>★★ <b>首都区</b>：先按 {@link ProvinceDivider} 的建议落每条建议省（首都圈不再混在 {@code provinces} 里，它只有自己的独立 __CAP
 * Region），再由 {@code capitalDistrict} 落独立 Region {@code sanitize(regionId)+"__CAP"}（由中央 GOV 管辖）。中央
 * GOV 的 jurisdiction 只授 {@code __CAP}；没给 capitalHex（或首都圈为空）则中央不落 {@code unit.SetJurisdiction}（空管辖）。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code map.CreateRegion}（省 → 首都区）→ {@code unit.CreateUnit}（中央 → 省）→
 * {@code unit.SetGovFormation}（中央 → 省）→ {@code unit.SetJurisdiction}（中央在首都圈非空时才落；省逐个）→ {@code
 * sd.CreateDecisionMaker} → [{@code providerId}: {@code sd.SetDecisionMakerProvider}] → {@code
 * sd.PutInfo}（key={@value #INFO_KEY}，address=目标 Region canonical，value=JSON 字符串，tick=base 世界当前日）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；★ 工具名不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}。
 *
 * <p>★ <b>资源声明</b>：只写 {@code map}/{@code unit}/{@code sd} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}，GM 侧三者 unlimited）；{@link #spec()} 与其余 GM 组合写同制（DEFAULT / sensitive /
 * 不可嵌入）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / region 不存在 / min·max·radius 不合法 / 空建议 ⇒ {@link
 * IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；相关结构 / 相交未 override ⇒ {@code
 * NEEDS_CLEAR} / {@code OVERLAP_OVERRIDE_REQUIRED}（零 revision）；批内域拒 ⇒ {@code REJECTED} 带逐条真拒因；提交冲突
 * ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class ProvinceApplyTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.province.apply";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 目标 Region canonical，一次落盘一条）。 */
  public static final String INFO_KEY = ProvinceApplyPlan.INFO_KEY;

  /** 本工具只写 map / unit / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest PROVINCE_APPLY_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #PROVINCE_APPLY_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.MAP_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;
  private final String mapId;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（进 {@code sd.PutInfo} 的目标 Region canonical 地址）
   */
  public ProvinceApplyTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 省份一键落盘（组合工具，一批 = 一条 revision）：复用 simos.province.divide 的同一份只读建议，"
        + "按固定批序落 map.CreateRegion（省 → 首都区 __CAP）→ unit.CreateUnit（中央 -gov-central + 省 -gov-PNN）"
        + "→ social.CreateHousehold（每个 GOV 一个政府家户 hh-gov-<unitId>）× (N+1) → "
        + "actor.EnsureHouseholdAccount × (N+1) → unit.SetGovFormation（households 含自己的政府家户；省 superiorGov=中央）→ "
        + "economy.RegisterGovernment × (N+1)（gov-unit-<unitId>，要求 economy 已激活）→ "
        + "unit.SetJurisdiction（中央仅首都区；省本省）→ sd.CreateDecisionMaker × (N+1) → "
        + "[providerId: sd.SetDecisionMakerProvider] → sd.PutInfo。"
        + "参数 {regionId(必填，必须在当前 map.regions() 里), maxHexPerProvince?(缺省 "
        + ProvinceDivider.DEFAULT_MAX_HEX_PER_PROVINCE
        + "), minHexPerProvince?(缺省 "
        + ProvinceDivider.DEFAULT_MIN_HEX_PER_PROVINCE
        + "), capitalHex?({q,r}), capitalDistrictRadius?(缺省 "
        + ProvinceDivider.DEFAULT_CAPITAL_DISTRICT_RADIUS
        + "), namingPrefix?(缺省=Region.name()), staff?({SCRIBE|YAMEN|POST:整数}, 缺省空表), "
        + "policy?(可选；缺省 OfficePolicy.defaults()、可部分覆盖), allowedTools?(可用字符串数组，缺省空集 = 全局白名单), "
        + "cadence?(缺省 1), providerId?(可选；给了才逐 DM 落 provider), overrideOverlaps?(缺省 false), "
        + "reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "pre-scan：目标 Region 内已有 GovFormation 单位 / 其 Gov 决策人 / 覆盖它的 jurisdiction / "
        + "sanitize(regionId)+__P<数字> 或 __CAP 既有 Region ⇒ NEEDS_CLEAR（零 revision，指路 "
        + "simos.region.clearStructures）；与既有 Region 相交且 overrideOverlaps=false ⇒ "
        + "OVERLAP_OVERRIDE_REQUIRED（列出 id/name/tag/相交格数）。"
        + "返回 {preview, submitted, tick, regionId, regionName, namingPrefix, provinceCount, provinces, "
        + "capitalDistrict?, overlappingRegions, overrideOverlaps, staff, policy, allowedTools, cadence, "
        + "providerId, cleanGate, commands, warnings, conflictPreflight, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("regionId", ToolSupport.prop("string", "目标 Region id（必须在当前 map.regions() 里）"));
    props.put(
        "maxHexPerProvince",
        ToolSupport.prop(
            "integer", "每省 hex 上限（缺省 " + ProvinceDivider.DEFAULT_MAX_HEX_PER_PROVINCE + "）"));
    props.put(
        "minHexPerProvince",
        ToolSupport.prop(
            "integer",
            "每省 hex 下限（缺省 "
                + ProvinceDivider.DEFAULT_MIN_HEX_PER_PROVINCE
                + "；必须 ≥1 且 ≤ maxHexPerProvince）"));
    Map<String, Object> capitalHex = new LinkedHashMap<>();
    capitalHex.put("type", "object");
    capitalHex.put("description", "可选首都格 {q,r}；给出时必须落在目标 Region 的 hex 集内；首都圈单列 __CAP");
    Map<String, Object> capitalHexProps = new LinkedHashMap<>();
    capitalHexProps.put("q", ToolSupport.prop("integer", "六角列坐标 q"));
    capitalHexProps.put("r", ToolSupport.prop("integer", "六角行坐标 r"));
    capitalHex.put("properties", capitalHexProps);
    capitalHex.put("required", List.of("q", "r"));
    props.put("capitalHex", capitalHex);
    props.put(
        "capitalDistrictRadius",
        ToolSupport.prop(
            "integer",
            "首都圈半径（缺省 "
                + ProvinceDivider.DEFAULT_CAPITAL_DISTRICT_RADIUS
                + "；只在给出 capitalHex 时使用；半径内 ∩ Region 为空则忽略首都圈）"));
    props.put("namingPrefix", ToolSupport.prop("string", "命名前缀（缺省 = 目标 Region 名）"));
    props.put("staff", ToolSupport.prop("object", "每个 GOV 的初始编制 {SCRIBE|YAMEN|POST:整数}（缺省空表）"));
    props.put(
        "policy",
        ToolSupport.prop(
            "object",
            "每个 GOV 的编制政策 {grainPerStaffPerTick?, clothPerStaffPerCycle?, moneyPerStaffPerTick?, "
                + "retirementPerStaff?, staffCap?{角色:整数}}（缺省 OfficePolicy.defaults()，可部分覆盖）"));
    props.put("allowedTools", ToolSupport.prop("array", "每个决策人的窄工具白名单（字符串数组；缺省空集 = P6a 全局白名单）"));
    props.put("cadence", ToolSupport.prop("integer", "决策周期（天，≥1；缺省 1）"));
    props.put("providerId", ToolSupport.prop("string", "LLM provider 引用（可选；给了才逐 DM 落 provider）"));
    props.put("overrideOverlaps", ToolSupport.prop("boolean", "显式接受相交 Region（缺省 false ⇒ 命中相交即拒）"));
    props.put("reason", ToolSupport.prop("string", "落盘原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("regionId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return PROVINCE_APPLY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "省份一键落盘 regionId="
            + args.get("regionId")
            + " preview="
            + args.getOrDefault("preview", true)
            + " overrideOverlaps="
            + args.getOrDefault("overrideOverlaps", false)
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
      String reason = ToolSupport.requiredText(args, "reason");
      ProvinceApplyPlan.Params params = parseParams(args);
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = optionalLongStrict(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      Map<String, Object> conflictPreflight = conflictPreflight(branch, expectedRevisionArg);
      ProvinceApplyPlan.Derivation derived = ProvinceApplyPlan.derive(state, mapId, params);
      if (derived.gate() == ProvinceApplyPlan.Gate.NEEDS_CLEAR) {
        return needsClear(derived, params, conflictPreflight);
      }
      if (derived.gate() == ProvinceApplyPlan.Gate.OVERLAP_OVERRIDE_REQUIRED) {
        return overlapRequired(derived, params, conflictPreflight);
      }
      ProvinceApplyPlan.Plan plan =
          derived.plan().orElseThrow(() -> new IllegalStateException("推演不自洽：OPEN 却没有 plan"));
      if (preview) {
        return ToolSupport.ok(planView(plan, derived, reason, true, false, conflictPreflight));
      }
      return apply(plan, derived, reason, branch, expectedRevision, conflictPreflight);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "省份一键落盘失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数形状解析（词表 / 类型 / 嵌套对象；语义不在这里） ─────────────────────────────────

  /** 解析全部 GM 参数（reason/preview/branch/expectedRevision 在 {@link #execute} 里单独处理）。 */
  private static ProvinceApplyPlan.Params parseParams(Map<String, Object> args) {
    String regionId = ToolSupport.requiredText(args, "regionId");
    int max = intOrDefault(args, "maxHexPerProvince", ProvinceDivider.DEFAULT_MAX_HEX_PER_PROVINCE);
    int min = intOrDefault(args, "minHexPerProvince", ProvinceDivider.DEFAULT_MIN_HEX_PER_PROVINCE);
    if (min < 1) {
      throw new IllegalArgumentException("minHexPerProvince 必须 ≥1: " + min);
    }
    if (max < min) {
      throw new IllegalArgumentException(
          "maxHexPerProvince 必须 ≥ minHexPerProvince: max=" + max + ", min=" + min);
    }
    HexCoord capital = optionalHex(args, "capitalHex");
    int radius = ProvinceDivider.DEFAULT_CAPITAL_DISTRICT_RADIUS;
    if (capital != null) {
      radius = intOrDefault(args, "capitalDistrictRadius", radius);
      if (radius < 0) {
        throw new IllegalArgumentException("capitalDistrictRadius 不能为负: " + radius);
      }
    }
    String namingPrefix = ToolSupport.optionalText(args, "namingPrefix", null);
    Map<StaffRole, Long> staff = parseStaff(args.get("staff"));
    OfficePolicy policy = parsePolicy(args.get("policy"));
    Set<String> allowedTools = parseAllowedTools(args.get("allowedTools"));
    Long cadenceArg = optionalLongStrict(args, "cadence");
    long cadence = cadenceArg == null ? ProvinceApplyPlan.DEFAULT_CADENCE : cadenceArg;
    if (cadence < 1L) {
      throw new IllegalArgumentException("cadence 必须 ≥ 1（决策周期，单位：天）: " + cadence);
    }
    Optional<String> providerId = optionalText(args, "providerId");
    boolean overrideOverlaps = ToolSupport.optionalBoolean(args, "overrideOverlaps").orElse(false);
    return new ProvinceApplyPlan.Params(
        regionId,
        min,
        max,
        capital,
        radius,
        namingPrefix,
        staff,
        policy,
        allowedTools,
        cadence,
        providerId,
        overrideOverlaps);
  }

  /** 可选文本：缺省 ⇒ 空 Optional；给了空白/非文本 ⇒ BAD_REQUEST。 */
  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    String text = ToolSupport.optionalText(args, name, null);
    return Optional.ofNullable(text);
  }

  /** 必填/可选 int 参数：先按 long 严格解析，再显式判 int 范围（不静默截断）。 */
  private static int intOrDefault(Map<String, Object> args, String name, int fallback) {
    Long value = optionalLongStrict(args, name);
    if (value == null) {
      return fallback;
    }
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + " 超出 int 范围: " + value);
    }
    return value.intValue();
  }

  /** 可选 long 参数：整数 / 整数字符串；浮点带小数 ⇒ BAD_REQUEST（不静默截断）。 */
  private static Long optionalLongStrict(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    return raw == null ? null : parseLongValue(raw, name);
  }

  /** 把 MCP 参数里的整数（Number 或数字串）读成 long；浮点有小数 ⇒ 具名拒。 */
  private static long parseLongValue(Object value, String label) {
    if (value instanceof Number number) {
      if (number instanceof Double || number instanceof Float) {
        if (hasFraction(number.doubleValue())) {
          throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + value);
        }
      }
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + text, e);
      }
    }
    throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + value);
  }

  /**
   * 浮点数是否有小数部分（NaN / 无穷 ⇒ 也算"不是整数"）。用 {@link BigDecimal} 精确判定，不写浮点相等/取模比较 （SpotBugs 的 {@code
   * FE_FLOATING_POINT_EQUALITY} 靶子）。
   */
  private static boolean hasFraction(double value) {
    if (!Double.isFinite(value)) {
      return true;
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().scale() > 0;
  }

  /** 可选的 {@code {"q":整数,"r":整数}} 对象；缺席/null ⇒ null。 */
  private static HexCoord optionalHex(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> object)) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是 {\"q\":整数,\"r\":整数} 对象");
    }
    long q = parseLongValue(object.get("q"), name + ".q");
    long r = parseLongValue(object.get("r"), name + ".r");
    if (q < Integer.MIN_VALUE || q > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + ".q 超出 int 范围: " + q);
    }
    if (r < Integer.MIN_VALUE || r > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + ".r 超出 int 范围: " + r);
    }
    return new HexCoord((int) q, (int) r);
  }

  /** {@code staff?}：缺省空表；给了必须是 {角色:整数} 对象（角色词表在这里把关，负值也在解析期拒）。 */
  private static Map<StaffRole, Long> parseStaff(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 staff 必须是 {SCRIBE|YAMEN|POST:整数} 对象");
    }
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (!(key instanceof String roleName)) {
        throw new IllegalArgumentException("参数 staff 的角色键必须是字符串: " + key);
      }
      StaffRole role;
      try {
        role = StaffRole.valueOf(roleName.trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "参数 staff 的角色未知（只认 SCRIBE / YAMEN / POST）: " + roleName, e);
      }
      long count = parseLongValue(entry.getValue(), "staff[" + roleName + "]");
      if (count < 0L) {
        throw new IllegalArgumentException("参数 staff 的值必须 ≥ 0: " + roleName + "=" + count);
      }
      staff.put(role, count);
    }
    return staff;
  }

  /**
   * {@code policy?}：缺省 {@link OfficePolicy#defaults()}；给了必须是对象，四个数值与 {@code staffCap} 都可只覆盖字段
   * （缺省字段取 defaults）——与 {@code unit.SetGovFormation} 的 {@code UnitPayloads.optionalPolicy} 同语义。
   */
  private static OfficePolicy parsePolicy(Object raw) {
    if (raw == null) {
      return OfficePolicy.defaults();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 policy 必须是对象（缺省 = OfficePolicy.defaults()）");
    }
    OfficePolicy defaults = OfficePolicy.defaults();
    long grain = optionalLongFromMap(map, "grainPerStaffPerTick", defaults.grainPerStaffPerTick());
    long cloth =
        optionalLongFromMap(map, "clothPerStaffPerCycle", defaults.clothPerStaffPerCycle());
    long money = optionalLongFromMap(map, "moneyPerStaffPerTick", defaults.moneyPerStaffPerTick());
    long retirement = optionalLongFromMap(map, "retirementPerStaff", defaults.retirementPerStaff());
    Map<StaffRole, Long> staffCap =
        map.get("staffCap") == null ? defaults.staffCap() : parseStaff(map.get("staffCap"));
    return new OfficePolicy(grain, cloth, money, retirement, staffCap);
  }

  /** 嵌套对象里的可选整数值（缺省取 fallback；Number / 数字串都收，其余类型具名拒）。 */
  private static long optionalLongFromMap(Map<?, ?> map, String key, long fallback) {
    Object value = map.get(key);
    return value == null ? fallback : parseLongValue(value, "policy." + key);
  }

  /** {@code allowedTools?}：缺省空集；给了必须是字符串数组（保序去重，非空白）。 */
  private static Set<String> parseAllowedTools(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 allowedTools 必须是字符串数组");
    }
    Set<String> tools = new LinkedHashSet<>();
    for (Object element : list) {
      if (!(element instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 allowedTools 的元素必须是非空白字符串: " + element);
      }
      tools.add(text);
    }
    return tools;
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

  // ── 门禁命中：NEEDS_CLEAR / OVERLAP_OVERRIDE_REQUIRED（零 revision） ─────────────────────

  /** 相关结构命中的具名错误：命中类别 / 数量 / 示例 id + 清空指路。 */
  private static ToolResult needsClear(
      ProvinceApplyPlan.Derivation derived,
      ProvinceApplyPlan.Params params,
      Map<String, Object> conflictPreflight) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", "NEEDS_CLEAR");
    payload.put("submitted", false);
    payload.put("regionId", params.regionId());
    payload.put("hint", ProvinceApplyPlan.NEEDS_CLEAR_HINT);
    payload.put("cleanGate", derived.structureGate().view());
    payload.put("conflictPreflight", conflictPreflight);
    payload.put("warnings", derived.warnings());
    return ToolResult.error("NEEDS_CLEAR", ToolSupport.json(payload));
  }

  /** 相交 Region 未 override 的具名错误：列出每个相交 Region（id/name/tag/相交格数）。 */
  private static ToolResult overlapRequired(
      ProvinceApplyPlan.Derivation derived,
      ProvinceApplyPlan.Params params,
      Map<String, Object> conflictPreflight) {
    List<ProvinceDivider.Overlap> overlaps = derived.overlappingRegions();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", "OVERLAP_OVERRIDE_REQUIRED");
    payload.put("submitted", false);
    payload.put("regionId", params.regionId());
    payload.put(
        "hint",
        "目标 Region 与 "
            + overlaps.size()
            + " 个既有 Region 相交（区域重叠 ≠ 省籍）；确认要在相交状态下落盘请显式 overrideOverlaps=true 后重试");
    payload.put("overlappingRegions", ProvinceApplyPlan.overlapViews(overlaps));
    payload.put("conflictPreflight", conflictPreflight);
    payload.put("warnings", derived.warnings());
    return ToolResult.error("OVERLAP_OVERRIDE_REQUIRED", ToolSupport.json(payload));
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序、按需缺席），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      ProvinceApplyPlan.Plan plan,
      ProvinceApplyPlan.Derivation derived,
      String reason,
      BranchId branch,
      long expectedRevision,
      Map<String, Object> conflictPreflight) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch =
        buildBatch(batchId, plan, reason, branch, new RevisionId(expectedRevision));
    BatchResult result = core.submitBatch(batch);
    Map<String, Object> view = planView(plan, derived, reason, false, true, conflictPreflight);
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
    submission.put("commands", List.copyOf(rows));
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }

  /**
   * 组批（固定顺序，可复现）：{@code map.CreateRegion}（省 → 首都区）→ {@code unit.CreateUnit}（中央 → 省）→ {@code
   * unit.SetGovFormation}（中央 → 省）→ {@code unit.SetJurisdiction}（按需）→ {@code
   * sd.CreateDecisionMaker}（中央 → 省）→ [provider] → {@code sd.PutInfo}。全部共享同一 batchId 与同一
   * branch/expectedRevision ⇒ {@code submitBatch} 落一条 revision。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      ProvinceApplyPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<ProvinceApplyPlan.RegionUnitEntry> createdRegions = plan.createdRegions();
    List<ProvinceApplyPlan.GovEntry> govs = plan.govs();
    ProvinceApplyPlan.GovEntry central = govs.get(0);
    List<CommandEnvelope> batch = new ArrayList<>(createdRegions.size() + govs.size() * 7 + 2);

    for (ProvinceApplyPlan.RegionUnitEntry province : plan.provinces()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.CREATE_REGION_TYPE,
              plan.createRegionPayload(province, true)));
    }
    if (plan.capitalDistrict() != null) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.CREATE_REGION_TYPE,
              plan.createRegionPayload(plan.capitalDistrict(), false)));
    }

    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            ProvinceApplyPlan.CREATE_UNIT_TYPE,
            plan.createUnitPayload(central)));
    for (int i = 1; i < govs.size(); i++) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.CREATE_UNIT_TYPE,
              plan.createUnitPayload(govs.get(i))));
    }

    // ★★ P2-C §13.7：每个 GOV（中央 + 省）同批建政府家户 + 零余额账户 + 编制引用 + 政府记录。
    //   四件事与单位创建共享同一 batchId/branch/expectedRevision ⇒ 一条 revision 内三边同时成立。
    for (ProvinceApplyPlan.GovEntry gov : govs) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.CREATE_HOUSEHOLD_TYPE,
              plan.createGovernmentHouseholdPayload(gov, reason)));
    }
    for (ProvinceApplyPlan.GovEntry gov : govs) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.ENSURE_HOUSEHOLD_ACCOUNT_TYPE,
              plan.ensureHouseholdAccountPayload(gov, reason)));
    }

    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            ProvinceApplyPlan.SET_GOV_FORMATION_TYPE,
            plan.setGovFormationPayload(central)));
    for (int i = 1; i < govs.size(); i++) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.SET_GOV_FORMATION_TYPE,
              plan.setGovFormationPayload(govs.get(i))));
    }

    for (ProvinceApplyPlan.GovEntry gov : govs) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.REGISTER_GOVERNMENT_TYPE,
              plan.registerGovernmentPayload(gov, reason)));
    }

    for (ProvinceApplyPlan.GovEntry gov : govs) {
      if (gov.hasJurisdictionCommand()) {
        batch.add(
            envelope(
                batchId,
                branch,
                expectedRevision,
                ProvinceApplyPlan.SET_JURISDICTION_TYPE,
                plan.setJurisdictionPayload(gov)));
      }
    }

    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            ProvinceApplyPlan.CREATE_DECISION_MAKER_TYPE,
            plan.createDecisionMakerPayload(central)));
    for (int i = 1; i < govs.size(); i++) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.CREATE_DECISION_MAKER_TYPE,
              plan.createDecisionMakerPayload(govs.get(i))));
    }

    if (plan.params().providerId().isPresent()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ProvinceApplyPlan.SET_DECISION_MAKER_PROVIDER_TYPE,
              plan.setDecisionMakerProviderPayload(central)));
      for (int i = 1; i < govs.size(); i++) {
        batch.add(
            envelope(
                batchId,
                branch,
                expectedRevision,
                ProvinceApplyPlan.SET_DECISION_MAKER_PROVIDER_TYPE,
                plan.setDecisionMakerProviderPayload(govs.get(i))));
      }
    }

    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            ProvinceApplyPlan.PUT_INFO_TYPE,
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

  /** {@code sd.PutInfo} 载荷：目标 Region canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(ProvinceApplyPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", regionAddress(plan.mapId(), plan.regionId()));
    payload.put("key", ProvinceApplyPlan.INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  /** 目标 Region 的 canonical 地址：{@code map:<mapId>:region.<regionId>}（Address AST，特殊字符由它按需加引）。 */
  private static String regionAddress(String mapId, String regionId) {
    Address address =
        new Address(
            List.of(
                new Namespace(ToolSupport.MAP_NAMESPACE),
                Entity.of(mapId),
                Entity.of("region", regionId)));
    return address.canonical();
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      ProvinceApplyPlan.Plan plan,
      ProvinceApplyPlan.Derivation derived,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> conflictPreflight) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("regionId", plan.regionId());
    view.put("regionName", plan.regionName());
    view.put("namingPrefix", plan.namingPrefix());
    view.put("provinceCount", plan.provinces().size());
    view.put("provinces", plan.provincesView());
    if (plan.capitalDistrict() != null) {
      view.put("capitalDistrict", plan.capitalDistrictView());
    }
    view.put("overlappingRegions", plan.overlappingRegionsView());
    view.put("overrideOverlaps", plan.params().overrideOverlaps());
    view.put("staff", plan.params().staffView());
    view.put("policy", plan.params().policyView());
    view.put("allowedTools", plan.params().allowedToolsView());
    view.put("cadence", plan.params().cadence());
    view.put("providerId", plan.params().providerId().orElse(null));
    view.put("cleanGate", derived.structureGate().view());
    view.put("commands", plan.commandCounts());
    view.put("warnings", derived.warnings());
    view.put("conflictPreflight", conflictPreflight);
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
