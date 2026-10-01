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
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.HouseholdSeeder;
import io.mosire.simos.app.world.PopulationSeeder;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.social.gen.CapitalAnchor;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
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
 * ★★ {@code simos.region.seed}（P1a1，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 组合工具</b>——给一个空白 Region 按
 * GM 参数生成并落盘人口 / 城市 / 批次 / （可选）家户经济。
 *
 * <p>★★ <b>一批 = 一条 revision，固定批序与 {@link WorldgenInitializeTool} 同源</b>：{@code
 * social.SetPopulation} → {@code social.CreateCity × N} → {@code social.SeedGroups} → [{@code
 * includeEconomy}: {@code economy.Seed}] → [{@code includeActors}: {@code actor.Seed}] → {@code
 * sd.PutInfo}。全部共享同一 {@code batchId}（correlationId） 与同一 branch/expectedRevision ⇒ {@link
 * CoreSimos#submitBatch} 原子落一条 revision；preview 一个字节都不写。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点在 {@link RegionSeedPlan#derive}；本类只做四件事——参数形状解析、读
 * base state、把 Plan 折成视图、组批与折叠结局。
 *
 * <p>★★ <b>clean gate（必须做，只读 base state）</b>：{@link RegionSeedPlan} 检查目标 Region 格集内是否已有 {@code
 * social.populations} / {@code PopulationGroup} 落点 / {@code SocialCity.at} 或 city.region / actor 账户
 * location / economy 逐格 industries·markets；任一命中 ⇒ <b>不 submit、零 revision</b>，返回 {@code NEEDS_CLEAR}
 * 具名 JSON（类型 / 数量 / 示例 id），并提示用 {@code simos.region.clearData} / {@code
 * simos.region.clearStructures} 清空后重试（这两个清空工具 <b>待 P1b</b>，尚未实现）。世界级 {@code classFirst}
 * 池是共享世界状态、不作为 Region 命中，只在 {@code warnings} 里警告。
 *
 * <p>★★ <b>默认带经济与 actor</b>：{@code includeEconomy}/{@code includeActors} 缺省均为 true（用户 2026-10-01 裁定
 * 3）； 低人口 / 零人口（class-first 无法建池）⇒ {@code BAD_REQUEST} 具名拒绝，并提示可显式关掉两个开关只播种人口与城市。
 *
 * <p>★ <b>白名单</b>：只做生成模式（{@code totalPopulation + seed}）；explicit 明细模式留后续批次。参数里没有逐格 entries。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。
 *
 * <p>★ <b>资源声明</b>：五个命名空间 {@code map}/{@code social}/{@code economy}/{@code actor}/{@code sd} 全部
 * {@link ResourcePolicy#UNRESTRICTED}（GM 侧五面 unlimited），与写路径逐条对齐。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / region 不存在 / 全海洋或零承载力 / 首都硬目标 > 城市总人口 / 低人口且经济开关打开 ⇒ {@link
 * IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域拒 ⇒ {@code REJECTED} 带逐条真拒因；提交冲突 ⇒
 * {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。{@code
 * shortfall > 0} 原样返回、不阻止落盘。
 */
public final class RegionSeedTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.region.seed";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 目标 Region canonical，一次播种一条）。 */
  public static final String INFO_KEY = "regionSeed";

  /** clean gate 命中时的指路文案。★ 两个清空工具**尚未实现（待 P1b）**——文案里必须写清，否则调用方会去调不存在的工具。 */
  public static final String NEEDS_CLEAR_HINT =
      "调用 simos.region.clearData / simos.region.clearStructures 清空当前区域相关数据后重试"
          + "（这两个清空工具尚未实现，待 P1b）";

  /** 本工具写五个命名空间（GM 侧五面 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest REGION_SEED_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #REGION_SEED_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令命名空间序：social → economy → actor → sd）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.MAP_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;
  private final String mapId;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与 apply 共用同一份推导）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（进 {@code economy.Seed} 与 {@code sd.PutInfo} 地址）
   */
  public RegionSeedTool(CoreSimos core, QueryService query, String initiator, String mapId) {
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
    return "GM 任意 Region 数据播种（生成模式；组合工具，一批 = 一条 revision）：按 regionId + totalPopulation 用"
        + " SettlementGenerator 生成聚落，翻成固定批序 social.SetPopulation → social.CreateCity × N →"
        + " social.SeedGroups → [includeEconomy: economy.Seed] → [includeActors: actor.Seed] → sd.PutInfo"
        + "（key="
        + INFO_KEY
        + "，address=Region canonical，value=JSON 字符串）。"
        + "参数 {regionId(必填，必须在当前 map.regions() 里), totalPopulation(必填 >=0),"
        + " urbanizationRate?(缺省 "
        + RegionSeedPlan.DEFAULT_URBANIZATION_RATE
        + "，0..1), seed?(缺省 "
        + RegionSeedPlan.DEFAULT_SEED
        + "), capital?({name, targetPopulation?}；CapitalAnchor 无 hex，本工具不发明),"
        + " documentedNames?(字符串数组，缺省 []), agrarianSurplusRate?(缺省 1.0), commercialIntegration?(缺省 1.0),"
        + " politicalCentralization?(缺省 1.0), includeEconomy?(缺省 true), includeActors?(缺省 true),"
        + " preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填，>=0), reason(必填非空白)}。"
        + "clean gate：目标 Region 格集内已有 social.populations / PopulationGroup / SocialCity.at 或 city.region /"
        + " actor GoodsAccount location / economy 逐格 industries·markets 任一命中 ⇒ 不提交、零 revision，"
        + "返回 NEEDS_CLEAR（列出类型/数量/示例 id 与清空指路）；世界级 classFirst 池只警告、不作 Region 命中。"
        + "低人口/零人口且 includeEconomy/includeActors 打开 ⇒ BAD_REQUEST 具名拒绝，可关开关只播种人口与城市。"
        + "全海洋/零承载力 ⇒ BAD_REQUEST；shortfall>0 原样返回、不阻止落盘。"
        + "失败语义：参数/前置 ⇒ BAD_REQUEST（零 revision）；批内域拒 ⇒ REJECTED（逐条真拒因）；"
        + "冲突 ⇒ CONFLICT（真实 head）；资源不匹配 ⇒ 原样抛资源拒因。"
        + "返回 {preview, submitted, regionId, regionName, hexCount, regionHexCount, seed, totalPopulation,"
        + " ruralPopulation, urbanPopulation, cityCount, tierHistogram, capital, largestCity, shortfall,"
        + " urbanCapacity, includeEconomy, includeActors, cleanGate, commands, conflictPreflight, warnings, infoText}；"
        + "apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("regionId", ToolSupport.prop("string", "目标 Region id（必须在当前 map.regions() 里）"));
    props.put("totalPopulation", ToolSupport.prop("integer", "总人口（>= 0；生成模式）"));
    props.put(
        "urbanizationRate",
        ToolSupport.prop(
            "number", "城市化率（可选，缺省 " + RegionSeedPlan.DEFAULT_URBANIZATION_RATE + "，必须在 0..1）"));
    props.put(
        "seed",
        ToolSupport.prop("integer", "随机种子（可选，缺省 " + RegionSeedPlan.DEFAULT_SEED + "；确定默认、可复现）"));
    props.put(
        "capital",
        ToolSupport.prop("object", "首都锚点 {name, targetPopulation?}（可选；CapitalAnchor 无 hex）"));
    props.put("documentedNames", ToolSupport.prop("array", "文档地名数组（可选；缺省空列表）"));
    props.put("agrarianSurplusRate", ToolSupport.prop("number", "农业剩余率系数（可选，缺省 1.0，>= 0）"));
    props.put("commercialIntegration", ToolSupport.prop("number", "商业整合度（可选，缺省 1.0，>= 0）"));
    props.put("politicalCentralization", ToolSupport.prop("number", "政治中央度（可选，缺省 1.0，>= 0）"));
    props.put("includeEconomy", ToolSupport.prop("boolean", "是否落 economy.Seed（可选，缺省 true；低人口可关掉）"));
    props.put("includeActors", ToolSupport.prop("boolean", "是否落 actor.Seed（可选，缺省 true；低人口可关掉）"));
    props.put("reason", ToolSupport.prop("string", "播种原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision（>=0）；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("regionId", "totalPopulation", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发——与其余 GM 写工具同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return REGION_SEED_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "Region 数据播种 regionId="
            + args.get("regionId")
            + " totalPopulation="
            + args.get("totalPopulation")
            + " includeEconomy="
            + args.getOrDefault("includeEconomy", true)
            + " includeActors="
            + args.getOrDefault("includeActors", true)
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
      String reason = ToolSupport.requiredText(args, "reason");
      RegionSeedPlan.Params params = parseParams(args);
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ preview / apply 的共同输入：同一坐标上的 base state + 同一份纯推导。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      Map<String, Object> conflictPreflight = conflictPreflight(branch, expectedRevisionArg);
      RegionSeedPlan.Derivation derived = RegionSeedPlan.derive(state, mapId, params);
      if (!derived.clean()) {
        return needsClear(derived, params);
      }
      RegionSeedPlan.Plan plan = derived.plan().orElseThrow();
      if (preview) {
        return ToolSupport.ok(planView(plan, derived, reason, true, false, conflictPreflight));
      }
      return apply(plan, derived, reason, branch, expectedRevision, conflictPreflight);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余写工具同一条：资源拒因必须原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而非"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "Region 播种失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数形状解析（类型 / 整数 / 浮点 / 首都 / 名字表；语义不在这里） ───────────────────────

  /** 解析全部 GM 参数（不含 reason/preview/branch/expectedRevision——它们在 {@link #execute} 里单独处理）。 */
  private static RegionSeedPlan.Params parseParams(Map<String, Object> args) {
    String regionId = ToolSupport.requiredText(args, "regionId");
    Long totalPopulationArg = optionalLong(args, "totalPopulation");
    if (totalPopulationArg == null) {
      throw new IllegalArgumentException("参数 totalPopulation 必填且为整数");
    }
    if (totalPopulationArg < 0L) {
      throw new IllegalArgumentException("totalPopulation 不得为负: " + totalPopulationArg);
    }
    double urbanizationRate =
        optionalDouble(args, "urbanizationRate", RegionSeedPlan.DEFAULT_URBANIZATION_RATE);
    requireRate(urbanizationRate, "urbanizationRate");
    Long seedArg = optionalLong(args, "seed");
    long seed = seedArg == null ? RegionSeedPlan.DEFAULT_SEED : seedArg;
    Optional<CapitalAnchor> capital = optionalCapital(args);
    List<String> documentedNames = optionalDocumentedNames(args);
    double agrarianSurplusRate =
        optionalDouble(args, "agrarianSurplusRate", RegionSeedPlan.DEFAULT_COEFFICIENT);
    requireNonNegative(agrarianSurplusRate, "agrarianSurplusRate");
    double commercialIntegration =
        optionalDouble(args, "commercialIntegration", RegionSeedPlan.DEFAULT_COEFFICIENT);
    requireNonNegative(commercialIntegration, "commercialIntegration");
    double politicalCentralization =
        optionalDouble(args, "politicalCentralization", RegionSeedPlan.DEFAULT_COEFFICIENT);
    requireNonNegative(politicalCentralization, "politicalCentralization");
    boolean includeEconomy = ToolSupport.optionalBoolean(args, "includeEconomy").orElse(true);
    boolean includeActors = ToolSupport.optionalBoolean(args, "includeActors").orElse(true);
    return new RegionSeedPlan.Params(
        regionId,
        totalPopulationArg,
        urbanizationRate,
        seed,
        capital,
        documentedNames,
        agrarianSurplusRate,
        commercialIntegration,
        politicalCentralization,
        includeEconomy,
        includeActors);
  }

  /** 可选 capital：{@code {name, targetPopulation?}}；缺省 / null ⇒ 无首都（不发明 hex）。 */
  private static Optional<CapitalAnchor> optionalCapital(Map<String, Object> args) {
    Object raw = args.get("capital");
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 capital 若给出必须是对象 {name, targetPopulation?}");
    }
    Object nameRaw = map.get("name");
    if (!(nameRaw instanceof String name) || name.isBlank()) {
      throw new IllegalArgumentException("参数 capital.name 必填且为非空文本");
    }
    Object targetRaw = map.get("targetPopulation");
    if (targetRaw == null) {
      return Optional.of(CapitalAnchor.withoutTarget(name));
    }
    long target = toLong(targetRaw, "capital.targetPopulation");
    if (target < 0L) {
      throw new IllegalArgumentException("capital.targetPopulation 不得为负: " + target);
    }
    return Optional.of(CapitalAnchor.of(name, target));
  }

  /** 可选 documentedNames：缺省 ⇒ 空列表；给了必须是字符串数组（元素非空，顺序原样保留）。 */
  private static List<String> optionalDocumentedNames(Map<String, Object> args) {
    Object raw = args.get("documentedNames");
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 documentedNames 若给出必须是字符串数组");
    }
    List<String> names = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 documentedNames 的元素必须是非空文本");
      }
      names.add(text);
    }
    return List.copyOf(names);
  }

  /** 可选 long（缺席 ⇒ null；给了但非整数 / 浮点带小数 ⇒ BAD_REQUEST，不静默截断）。 */
  private static Long optionalLong(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    return raw == null ? null : toLong(raw, name);
  }

  /** 把 MCP 参数里的整数读成 long；浮点有小数 ⇒ 具名拒（不静默截断）。 */
  private static long toLong(Object value, String label) {
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

  /** 可选浮点：缺省 ⇒ fallback；给了但非有限数 / 非数 ⇒ BAD_REQUEST。 */
  private static double optionalDouble(Map<String, Object> args, String name, double fallback) {
    Object raw = args.get(name);
    if (raw == null) {
      return fallback;
    }
    double value;
    if (raw instanceof Number number) {
      value = number.doubleValue();
    } else if (raw instanceof String text && !text.isBlank()) {
      try {
        value = Double.parseDouble(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是数: " + text, e);
      }
    } else {
      throw new IllegalArgumentException("参数 " + name + " 必须是数: " + raw);
    }
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是有限数: " + raw);
    }
    return value;
  }

  private static void requireRate(double value, String name) {
    if (value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " 必须落在 [0,1]: " + value);
    }
  }

  private static void requireNonNegative(double value, String name) {
    if (value < 0.0) {
      throw new IllegalArgumentException(name + " 不得为负: " + value);
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

  // ── clean gate 命中：NEEDS_CLEAR（零 revision） ─────────────────────────────────────────

  /** clean gate 命中的具名错误：命中类型 / 数量 / 示例 id + 清空指路（两个清空工具待 P1b）。 */
  private static ToolResult needsClear(
      RegionSeedPlan.Derivation derived, RegionSeedPlan.Params params) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", "NEEDS_CLEAR");
    payload.put("submitted", false);
    payload.put("regionId", params.regionId());
    payload.put("hint", NEEDS_CLEAR_HINT);
    payload.put("cleanGate", derived.cleanGate().view());
    payload.put("warnings", derived.warnings());
    return ToolResult.error("NEEDS_CLEAR", ToolSupport.json(payload));
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序、按开关缺席），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      RegionSeedPlan.Plan plan,
      RegionSeedPlan.Derivation derived,
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
    submission.put("commands", rows);
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }

  /**
   * 组批（固定顺序，可复现）：{@code social.SetPopulation} → {@code social.CreateCity × N} → {@code
   * social.SeedGroups} → [{@code includeEconomy}: {@code economy.Seed}] → [{@code includeActors}:
   * {@code actor.Seed}] → {@code sd.PutInfo}。
   *
   * <p>★ 载荷**复用 {@code WorldgenInitializeTool} 的同一份包内助手**（逐格人口 / 城市 props 的拼法只有一处），经济与 actor 读
   * **同一次** {@link EconomySeeder#plan} 的 {@link EconomySeeder.Seed}。全部共享同一 {@code batchId} 与同一
   * branch/expectedRevision ⇒ {@code submitBatch} 落一条 revision。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      RegionSeedPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(5 + plan.settlement().cities().size());
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            RegionSeedPlan.SET_POPULATION_TYPE,
            WorldgenInitializeTool.setPopulationPayload(plan.settlement())));
    for (PlannedCity city : plan.settlement().cities()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              RegionSeedPlan.CREATE_CITY_TYPE,
              WorldgenInitializeTool.createCityPayload(
                  plan.region().id(), city, plan.params().seed())));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            RegionSeedPlan.SEED_GROUPS_TYPE,
            PopulationSeeder.payload(plan.groups())));
    EconomySeeder.Seed seeding = plan.economySeed().orElse(null);
    if (plan.params().includeEconomy()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              RegionSeedPlan.SEED_ECONOMY_TYPE,
              requireSeed(seeding).economyPayload()));
    }
    if (plan.params().includeActors()) {
      EconomySeeder.Seed required = requireSeed(seeding);
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              RegionSeedPlan.SEED_ACTOR_TYPE,
              // ★★ H1/H5：家户 + 经营者的开缸库存/货币/经营者读的是**同一次 plan** 的同一份事实。
              HouseholdSeeder.payload(
                  plan.mapId(),
                  required.householdLocations(),
                  required.householdStocks(),
                  required.householdMoney(),
                  required.operators())));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            RegionSeedPlan.PUT_INFO_TYPE,
            infoPayload(plan, reason)));
    return List.copyOf(batch);
  }

  private static EconomySeeder.Seed requireSeed(EconomySeeder.Seed seeding) {
    if (seeding == null) {
      throw new IllegalStateException("includeEconomy/includeActors 为真却没有经济推导产物（装配故障）");
    }
    return seeding;
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
   * {@code sd.PutInfo} 载荷：目标 Region canonical 地址（{@code map:<mapId>:region.<regionId>}，只用 Address
   * AST 造） + {@code key="regionSeed"} + {@code value}=JSON 字符串 + {@code note}=人可读摘要 + {@code
   * tick}=当前世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一 Region 的后续播种自然追加序号。
   */
  private static String infoPayload(RegionSeedPlan.Plan plan, String reason) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("regionId", plan.region().id().value());
    value.put("seed", plan.params().seed());
    value.put("totalPopulation", plan.params().totalPopulation());
    value.put("cityCount", plan.settlement().cities().size());
    value.put("shortfall", plan.settlement().shortfall());
    value.put("includeEconomy", plan.params().includeEconomy());
    value.put("includeActors", plan.params().includeActors());
    value.put("reason", reason);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", regionAddress(plan.mapId(), plan.region().id().value()));
    payload.put("key", INFO_KEY);
    payload.put("value", ToolSupport.json(value));
    payload.put("note", infoNote(plan, reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  /** 目标 Region 的 canonical 地址：{@code map:<mapId>:region.<regionId>}（Address AST，名字里的特殊字符由它按需加引）。 */
  private static String regionAddress(String mapId, String regionId) {
    Address address =
        new Address(
            List.of(
                new Namespace(ToolSupport.MAP_NAMESPACE),
                Entity.of(mapId),
                Entity.of("region", regionId)));
    return address.canonical();
  }

  /** 行动记录 / 工具结果共用的人可读摘要。 */
  private static String infoNote(RegionSeedPlan.Plan plan, String reason) {
    return "Region 播种 region="
        + plan.region().id().value()
        + " seed="
        + plan.params().seed()
        + " totalPopulation="
        + plan.params().totalPopulation()
        + " cities="
        + plan.settlement().cities().size()
        + " shortfall="
        + plan.settlement().shortfall()
        + " includeEconomy="
        + plan.params().includeEconomy()
        + " includeActors="
        + plan.params().includeActors()
        + " reason="
        + reason;
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      RegionSeedPlan.Plan plan,
      RegionSeedPlan.Derivation derived,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> conflictPreflight) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("regionId", plan.region().id().value());
    view.put("regionName", plan.region().name());
    // ★ hexCount = 生成器落了农村人口序列的格数（与 worldgen.initialize 同口径）；
    //   regionHexCount = 目标 Region 的总格数（clean gate 扫描范围）——含海洋/零产格时两者不等。
    view.put("hexCount", plan.settlement().ruralPopulation().size());
    view.put("regionHexCount", plan.regionHexCount());
    view.put("seed", plan.params().seed());
    // ★ 与 worldgen.initialize 同口径：总人口 = Σ 农村 + Σ 城市（生成器出口不变量保证 == 请求值）。
    view.put("totalPopulation", plan.ruralTotal() + plan.urbanTotal());
    view.put("ruralPopulation", plan.ruralTotal());
    view.put("urbanPopulation", plan.urbanTotal());
    view.put("cityCount", plan.settlement().cities().size());
    view.put("tierHistogram", plan.tierHistogram());
    view.put("capital", plan.capitalCity().map(RegionSeedTool::cityView).orElse(null));
    view.put("largestCity", plan.largestCity().map(RegionSeedTool::cityView).orElse(null));
    view.put("shortfall", plan.settlement().shortfall());
    view.put("urbanCapacity", plan.settlement().urbanCapacity());
    view.put("includeEconomy", plan.params().includeEconomy());
    view.put("includeActors", plan.params().includeActors());
    view.put("cleanGate", derived.cleanGate().view());
    view.put("commands", plan.commandTypes());
    view.put("conflictPreflight", conflictPreflight);
    view.put("warnings", derived.warnings());
    view.put("infoText", infoNote(plan, reason));
    return view;
  }

  /** 首都 / 最大城的视图（名、人口、格、等级）。 */
  private static Map<String, Object> cityView(PlannedCity city) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", city.name());
    row.put("population", city.population());
    row.put("at", ToolSupport.hexCoord(city.at()));
    row.put("tier", city.tier());
    return row;
  }
}
