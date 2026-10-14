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
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
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
 * ★★ {@code simos.gov.createOffice}（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6）：<b>GM 组合工具</b>——建
 * GOV 单位 + 同批立编制 + 绑决策人（可选 provider / accessLimit），一批落一条 revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：新单位在 {@code unit} 切片、决策人在 {@code sd} 切片；单条命令只能落一个命名空间。 本工具走
 * {@link CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link GovCreateOfficePlan#plan}（不碰 {@link
 * ToolContext} / {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。参数形状解析（词表/类型/负值）
 * 在本类；前置状态校验与批载荷组装在 Plan，不出现第二份推导。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：
 *
 * <ol>
 *   <li>{@code unit.CreateUnit}（恒有）：{@code manpower=[], equipment=[], speed=1,
 *       mobilityPerMille=500, position=(q,r)}，无 parent、status 缺省 MOVING；
 *   <li>{@code unit.SetGovFormation}（恒有）：{@code level/superiorGov?/staff/policy}；
 *   <li>{@code unit.SetJurisdiction}（仅 {@code regions} 非空才落）：{@code {unitId, regions:[…]}}，不带 levy
 *       caps；
 *   <li>{@code ga.CreateDecisionMaker}（恒有）：{@code {id, allowedTools, cadence}} —— <b>三个</b>必填输入；
 *       <b>不带</b> {@code affiliation}（该键被具名拒：归属归模块）；
 *   <li>{@code gov.AssignDecisionMakerGov}（恒有）：{@code {decisionMakerId, govUnitId}} —— 决策人的 gov 归属；
 *       必须晚于上面的 {@code unit.SetGovFormation}（要求目标带 {@code GovernmentFormation}）与 {@code
 *       ga.CreateDecisionMaker}（要求决策人已存在）；
 *   <li>{@code ga.SetDecisionMakerProvider}（仅 providerId 给了才落）；
 *   <li>{@code ga.SetDecisionMakerAccess}（仅 accessLimit 给了非空对象才落；创建命令不接受 accessLimit）；
 *   <li>{@code ga.PutInfo}（恒有）：地址 = 单位 canonical，key = {@value #INFO_KEY}，value = JSON <b>字符串</b>，
 *       note = 人可读摘要，tick = 当前世界日。
 * </ol>
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。
 *
 * <p>★ <b>资源声明</b>：只写 {@code unit}/{@code sd} 两个命名空间（{@link ResourcePolicy#UNRESTRICTED}，GM 侧两者
 * unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM 窄写同制。
 *
 * <p>★ <b>缺省</b>：{@code regions?} 缺省空数组（CENTRAL 合法；PROVINCE 会被前置校验具名拒）；{@code cadence?} 缺省 1（域层下界）；
 * {@code allowedTools?} 缺省空集；{@code staff?} 缺省空表（工具层给 Plan）；{@code policy?} 缺省 {@link
 * OfficePolicy#defaults()}（给了可只覆盖部分字段，缺省字段取 defaults——与 {@code unit.SetGovFormation} 的载荷语义逐字一致）；
 * {@code accessLimit?} 缺省不写。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / level 不在词表 / regions 元素在地图里查无 / PROVINCE 的 regions 为空 / unitId 已存在
 * / decisionMakerId 已存在 / superiorGov 不存在或不是 GOV / cadence &lt; 1 ⇒ {@link
 * IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒（如一单位一标签、N9 白名单）⇒ {@code
 * REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class GovCreateOfficeTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.createOffice";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 新 GOV 单位 canonical，一次建置一条）。 */
  public static final String INFO_KEY = "createOffice";

  /** 本工具只写 unit / sd 两个命名空间（GM 侧两者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest OFFICE_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #OFFICE_WRITE} 同源的逐命名空间粗断言（GM 两面 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.GA_NAMESPACE, "*"));

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与 {@code LevyRegionTool} 等同制；本工具资源声明是两个命名空间的粗断言、单位 address
   *     也按单位 id 定位，不当路径用）
   */
  public GovCreateOfficeTool(GovToolSupport support, String mapId) {
    this.support = Objects.requireNonNull(support, "support");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 建 GOV 单位并同批绑决策人（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填), name(必填), q(必填 int), r(必填 int), level(必填 CENTRAL|PROVINCE), "
        + "regions?(可选 RegionId 字符串数组; 每个必须存在于当前地图 regions()，重复元素保序去重), "
        + "superiorGov?(可选; 非空必须存在且带 GovernmentFormation), staff?(可选 {SCRIBE|YAMEN|POST:整数}, 缺省空表), "
        + "policy?(可选 {grainPerStaffPerTick?, clothPerStaffPerCycle?, moneyPerStaffPerTick?, retirementPerStaff?, "
        + "staffCap?{角色:整数}}, 缺省 OfficePolicy.defaults()、可部分覆盖), decisionMakerId(必填, 不得已存在), "
        + "providerId?(可选; 给了同批 ga.SetDecisionMakerProvider), cadence?(可选, 缺省 1), "
        + "allowedTools?(可选字符串数组, 缺省空集), accessLimit?(可选 {命名空间:[前缀…]}; 给了非空对象才同批 "
        + "ga.SetDecisionMakerAccess), reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "纯推导前置：unitId/decisionMakerId 必须不存在；level 词表；regions 每个必须存在于当前地图（具名拒，不静默丢）；"
        + "level=PROVINCE 时 regions 必须非空，level=CENTRAL 时可为空（缺省空 = 不落 SetJurisdiction、无管辖）；"
        + "superiorGov 非空须存在且带 GovernmentFormation。"
        + "新单位固定 manpower=[]/equipment=[]/speed=1/mobilityPerMille=500/position=(q,r)/无 parent。"
        + "批顺序：unit.CreateUnit → social.CreateHousehold（政府家户 hh-gov-<unitId>，位置 UNIT(unitId)）→ "
        + "actor.EnsureHouseholdAccount（政府家户零余额账户）→ unit.SetGovFormation（域层把该政府家户编入 Unit.households）→ "
        + "economy.RegisterGovernment（gov-unit-<unitId>，要求 economy 已激活）→ [regions 非空: unit.SetJurisdiction] → "
        + "ga.CreateDecisionMaker（载荷 {id, allowedTools, cadence}，不带 affiliation）→ "
        + "gov.AssignDecisionMakerGov（{decisionMakerId, govUnitId}，落决策人的 GOV 归属）→ [provider] → [access] → ga.PutInfo(key="
        + INFO_KEY
        + "，value 回显 regions)。"
        + "返回 {preview, submitted, tick, unitId, name, position, level, regions, superiorGov, staff, policy, "
        + "decisionMakerId, providerId, cadence, allowedTools, accessLimit, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSchema.prop("string", "新 GOV 单位 id（不得与既有单位重复）"));
    props.put("name", ToolSchema.prop("string", "新 GOV 单位名（非空白）"));
    props.put("q", ToolSchema.prop("integer", "新单位落点 q（int）"));
    props.put("r", ToolSchema.prop("integer", "新单位落点 r（int）"));
    props.put("level", ToolSchema.prop("string", "GOV 层级：CENTRAL|PROVINCE"));
    props.put(
        "regions",
        ToolSchema.prop(
            "array",
            "初始管辖区域 RegionId 字符串数组（可选；每个必须存在于当前地图；PROVINCE 必须非空，CENTRAL 可空缺省；"
                + "仅非空时同批落 unit.SetJurisdiction，载荷不带 levy caps）"));
    props.put(
        "superiorGov", ToolSchema.prop("string", "上级 GOV 单位 id（可选；非空必须存在且带 GovernmentFormation）"));
    props.put("staff", ToolSchema.prop("object", "初始编制 {SCRIBE|YAMEN|POST:整数}（可选，缺省空表；值必须 ≥ 0）"));
    props.put(
        "policy",
        ToolSchema.prop(
            "object",
            "编制政策 {grainPerStaffPerTick?, clothPerStaffPerCycle?, moneyPerStaffPerTick?, "
                + "retirementPerStaff?, staffCap?{角色:整数}}（可选，缺省 OfficePolicy.defaults()，可只覆盖部分字段）"));
    props.put("decisionMakerId", ToolSchema.prop("string", "同批绑定的决策人 id（不得已存在）"));
    props.put(
        "providerId",
        ToolSchema.prop("string", "LLM provider 引用（可选；给了同批 ga.SetDecisionMakerProvider）"));
    props.put("cadence", ToolSchema.prop("integer", "决策周期（天，≥ 1；缺省 1）"));
    props.put("allowedTools", ToolSchema.prop("array", "决策人窄工具白名单（可选字符串数组，缺省空集）"));
    props.put(
        "accessLimit",
        ToolSchema.prop("object", "额外资源限制 {命名空间:[前缀…]}（可选；给了非空对象才同批 ga.SetDecisionMakerAccess）"));
    props.put("reason", ToolSchema.prop("string", "建置原因（必填非空白；进 ga.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(
        props, List.of("unitId", "name", "q", "r", "level", "decisionMakerId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return OFFICE_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "建 GOV unitId="
            + args.get("unitId")
            + " name="
            + args.get("name")
            + " level="
            + args.get("level")
            + " regions="
            + args.getOrDefault("regions", "(缺省空)")
            + " decisionMakerId="
            + args.get("decisionMakerId")
            + " providerId="
            + args.getOrDefault("providerId", "(未给)")
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
      String unitId = ToolSchema.requiredText(args, "unitId");
      String name = ToolSchema.requiredText(args, "name");
      int q = requiredInt(args, "q");
      int r = requiredInt(args, "r");
      GovernmentLevel level = parseLevel(ToolSchema.requiredText(args, "level"));
      List<RegionId> regions = parseRegions(args.get("regions"));
      Optional<String> superiorGov = optionalText(args, "superiorGov");
      Map<StaffRole, Long> staff = parseStaff(args.get("staff"));
      OfficePolicy policy = parsePolicy(args.get("policy"));
      String decisionMakerId = ToolSchema.requiredText(args, "decisionMakerId");
      Optional<String> providerId = optionalText(args, "providerId");
      Long cadenceArg = ToolSchema.optionalLong(args, "cadence");
      long cadence = cadenceArg == null ? GovCreateOfficePlan.DEFAULT_CADENCE : cadenceArg;
      Set<String> allowedTools = parseAllowedTools(args.get("allowedTools"));
      Optional<Map<String, Set<String>>> accessLimit = parseAccessLimit(args.get("accessLimit"));
      String reason = ToolSchema.requiredText(args, "reason");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          support
              .reads()
              .stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      GovCreateOfficePlan.Plan plan =
          GovCreateOfficePlan.plan(
              support,
              state,
              unitId,
              name,
              new HexCoord(q, r),
              level,
              regions,
              superiorGov,
              staff,
              policy,
              decisionMakerId,
              providerId,
              cadence,
              allowedTools,
              accessLimit);
      if (preview) {
        return ToolResults.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "建 GOV 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数形状解析（词表 / 类型 / 嵌套对象；语义不在这里） ───────────────────────────────

  /** 必填 int 参数（JSON 整数或数字串，经 {@link ToolSupport#requiredLong}；超 int 范围 ⇒ BAD_REQUEST）。 */
  private static int requiredInt(Map<String, Object> args, String name) {
    long value = ToolSchema.requiredLong(args, name);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + " 超出 int 范围: " + value);
    }
    return (int) value;
  }

  /** 可选文本：缺省 ⇒ 空 Optional；给了空白/非文本 ⇒ BAD_REQUEST（类型口径由 {@link ToolSupport#optionalText} 守）。 */
  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    String text = ToolSchema.optionalText(args, name, null);
    return Optional.ofNullable(text);
  }

  /** 层级词表：只认 CENTRAL|PROVINCE，别的词给具名拒（不静默当缺省）。 */
  private static GovernmentLevel parseLevel(String text) {
    try {
      return GovernmentLevel.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("参数 level 不是合法层级（CENTRAL|PROVINCE）: " + text, e);
    }
  }

  /**
   * {@code regions?}：缺省空数组；给了必须是字符串数组（元素 {@link RegionId#parse} 把关空白，保序去重）。
   *
   * <p>★ 去重与域层 {@code UnitOperations.setJurisdiction} 的 key 语义一致（{@code LinkedHashMap.put} 同键只留一个），
   * 让 Plan 的 info/视图回显与落盘后的 jurisdiction key 集逐值同形；<b>区域存在性</b>由 Plan 对着状态具名拒，不在这里做。
   */
  private static List<RegionId> parseRegions(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 regions 必须是 RegionId 字符串数组");
    }
    Set<RegionId> regions = new LinkedHashSet<>();
    for (Object element : list) {
      if (!(element instanceof String text)) {
        throw new IllegalArgumentException("参数 regions 的元素必须是字符串: " + element);
      }
      if (text.isBlank()) {
        throw new IllegalArgumentException("参数 regions 的元素不得为空白: " + element);
      }
      regions.add(RegionId.parse(text));
    }
    return List.copyOf(regions);
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

  /**
   * {@code accessLimit?}：键缺席 ⇒ 空 Optional；给了必须是 {命名空间:[非空前缀…]} 对象（含空对象；显式空对象 = 与创建缺省等价， Plan 不落
   * access 命令）。
   */
  private static Optional<Map<String, Set<String>>> parseAccessLimit(Object raw) {
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 accessLimit 必须是 {命名空间:[前缀…]} 对象");
    }
    Map<String, Set<String>> limit = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (!(key instanceof String namespace) || namespace.isBlank()) {
        throw new IllegalArgumentException("参数 accessLimit 的命名空间不得为空白: " + key);
      }
      Object prefixes = entry.getValue();
      if (!(prefixes instanceof List<?> list)) {
        throw new IllegalArgumentException("参数 accessLimit[" + namespace + "] 必须是 [前缀…] 数组");
      }
      Set<String> items = new LinkedHashSet<>();
      for (Object prefix : list) {
        if (!(prefix instanceof String text) || text.isBlank()) {
          throw new IllegalArgumentException(
              "参数 accessLimit[" + namespace + "] 的元素必须是非空白字符串: " + prefix);
        }
        items.add(text);
      }
      limit.put(namespace, items);
    }
    return Optional.of(limit);
  }

  /** 嵌套对象里的可选整数值（缺省取 {@code fallback}；Number / 数字串都收，其余类型具名拒）。 */
  private static long optionalLongFromMap(Map<?, ?> map, String key, long fallback) {
    Object value = map.get(key);
    if (value == null) {
      return fallback;
    }
    return parseLongValue(value, "policy." + key);
  }

  /** 把 MCP 参数里的整数（Number 或数字串）读成 long；其余类型具名拒。 */
  private static long parseLongValue(Object value, String label) {
    if (value instanceof Number number) {
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

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序、按需缺席），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      GovCreateOfficePlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<ToolWritePort.Request> batch = buildBatch(batchId, plan, reason);

    ToolWritePort.BatchReceipt receipt =
        support
            .writes()
            .submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch, batchId);
    // ★ submitted 只对真落盘的结局为 true：REJECTED（整批复原、零 revision）与 CONFLICT（一条没落）
    //   都必须报 false —— 否则"按 submitted 判成功"的断言在整批被拒时是假绿。
    boolean committed = receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed;
    Map<String, Object> view = planView(plan, reason, false, committed);
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committedOutcome) {
      view.put("submission", ToolResults.committedView(committedOutcome.ref(), batchId, batchId));
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

  /** 组批：固定顺序，命令类型与载荷都是 Plan 的纯函数；共享 batchId/branch/expectedRevision ⇒ 一条 revision。 */
  private List<ToolWritePort.Request> buildBatch(
      String batchId, GovCreateOfficePlan.Plan plan, String reason) {
    List<ToolWritePort.Request> batch = new ArrayList<>(11);
    batch.add(
        GovToolSupport.request(GovCreateOfficePlan.CREATE_UNIT_TYPE, plan.createUnitPayloadJson()));
    // ★★ P2-C §13.7：每个 GOV 单位同批创建它自己的政府家户、零余额账户、编制引用与政府记录 —— 四件事共享
    //   batchId/branch/expectedRevision ⇒ 一条 revision 内"单位 ↔ 家户 ↔ 国库"三边同时成立，不留半截态。
    batch.add(
        GovToolSupport.request(
            GovCreateOfficePlan.CREATE_HOUSEHOLD_TYPE,
            plan.createGovernmentHouseholdPayloadJson(reason)));
    batch.add(
        GovToolSupport.request(
            support.commandTypes().ensureHouseholdAccount(),
            plan.ensureHouseholdAccountPayloadJson(reason)));
    batch.add(
        GovToolSupport.request(
            GovCreateOfficePlan.SET_GOV_FORMATION_TYPE, plan.setGovFormationPayloadJson()));
    batch.add(
        GovToolSupport.request(
            support.commandTypes().registerGovernment(),
            plan.registerGovernmentPayloadJson(reason)));
    if (plan.hasJurisdictionCommand()) {
      batch.add(
          GovToolSupport.request(
              GovCreateOfficePlan.SET_JURISDICTION_TYPE, plan.setJurisdictionPayloadJson()));
    }
    batch.add(
        GovToolSupport.request(
            GovCreateOfficePlan.CREATE_DECISION_MAKER_TYPE, plan.createDecisionMakerPayloadJson()));
    // ★★ 同批补一条 gov.AssignDecisionMakerGov（归属归模块：决策人身份与归属是两条命令）。
    //   定序是 handler 的硬判据，不是偏好：它必须晚于 unit.SetGovFormation（要求目标单位带
    //   GovernmentFormation）且晚于上面的 ga.CreateDecisionMaker（要求决策人已存在）；同批后一条能看见
    //   前一条（CommandBus.runBatch 把每条变更集施加到累积候选态上）⇒ 夹在 create 与 provider 之间即可。
    batch.add(
        GovToolSupport.request(
            GovCreateOfficePlan.ASSIGN_DECISION_MAKER_GOV_TYPE,
            plan.assignDecisionMakerGovPayloadJson()));
    if (plan.hasProviderCommand()) {
      batch.add(
          GovToolSupport.request(
              GovCreateOfficePlan.SET_DECISION_MAKER_PROVIDER_TYPE,
              plan.setDecisionMakerProviderPayloadJson()));
    }
    if (plan.hasAccessCommand()) {
      batch.add(
          GovToolSupport.request(
              GovCreateOfficePlan.SET_DECISION_MAKER_ACCESS_TYPE,
              plan.setDecisionMakerAccessPayloadJson()));
    }
    batch.add(GovToolSupport.request(GovCreateOfficePlan.PUT_INFO_TYPE, infoPayload(plan, reason)));
    // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf）。
    return java.util.Collections.unmodifiableList(batch);
  }

  // ── 载荷：ga.PutInfo（行动记录） ─────────────────────────────────────────────────────

  /** {@code ga.PutInfo} 载荷：单位 canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovCreateOfficePlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private Map<String, Object> planView(
      GovCreateOfficePlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("name", plan.name());
    view.put("position", GovToolReads.hexCoord(plan.at()));
    view.put("level", plan.level().name());
    view.put("regions", plan.regionValues());
    view.put("superiorGov", plan.superiorGov().orElse(null));
    view.put("staff", plan.staffView());
    view.put("policy", plan.policyView());
    view.put("decisionMakerId", plan.decisionMakerId());
    view.put("providerId", plan.providerId().orElse(null));
    view.put("cadence", plan.cadence());
    view.put("allowedTools", new ArrayList<>(plan.allowedTools()));
    view.put("accessLimit", accessLimitView(plan));
    view.put("commands", plan.commandTypes(support.commandTypes()));
    view.put("infoText", plan.infoNote(reason));
    return view;
  }

  /** accessLimit 视图：键缺席 ⇒ null；有值（含空对象）⇒ 逐命名空间数组。 */
  private static Map<String, Object> accessLimitView(GovCreateOfficePlan.Plan plan) {
    if (plan.accessLimit().isEmpty()) {
      return null;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : plan.accessLimit().get().entrySet()) {
      view.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
