package io.mosire.simos.app.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.gen.ValueRange;
import io.mosire.simos.social.gen.WorldgenConfig;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.worldgen.initialize}（**只在 GM 桶**，2026-09-23）：把一个已就绪的聚落生成器（{@code
 * config/worldgen/v17levant-nations.json} + {@code simos-social} 的 {@code
 * SettlementGenerator}）接到一条**真实的写路径**上。
 *
 * <p>★★ **它不是某一条命令的窄封装**，故**不继承** {@code AbstractNarrowWriteTool}：一次初始化要落**一批**命令（1 条 {@code
 * social.SetPopulation} + N 条 {@code social.CreateCity}），且 {@code dryRun} 时**一个字节都不写**。它走 {@link
 * CoreSimos#submitBatch}（同批同 branch + 同 expectedRevision ⇒ **一批只落一条 revision**）。
 *
 * <p>★★ **两条载荷的形状是本工具与 social 域的契约**（{@code SetPopulationHandler} / {@code CreateCityHandler}）：
 *
 * <ul>
 *   <li>{@code social.SetPopulation}：{@code {"entries":[{"q","r","population"}…]}}，**entries 按
 *       {@link HexCoord} 排序**（键序是内容的纯函数——用 Map 的迭代序会让同一次生成的 payload 不可复现）；**不给 {@code
 *       anchorTick}**（用世界当前 tick）；
 *   <li>{@code social.CreateCity} 每座城一条：{@code id}=稳定身份 {@link PlannedCity#id()}、{@code at}/{@code
 *       population}/{@code region} 照抄，{@code props} 带**审计量**（{@code tier}/{@code
 *       catchmentHexes}/{@code localSurplus}/{@code tradeMultiplier}/{@code
 *       politicalMultiplier}/{@code justification}/{@code seed}）—— 这正兑现"城市节点可挂自定义信息"（{@code
 *       SocialCity.props}）。props 的值只放标量与字符串；★ {@code seed} 走**字符串**（它是 long，而 props 走 JSON
 *       往返时整数按幅值绑成 Integer/Long，超过 int 的种子会静默换类型甚至丢值）。
 * </ul>
 *
 * <p>★★ **randomize 怎么落地**：生成器的随机化开关是在 {@link WorldgenConfig#load(Path)} **解析期**定死的（{@code
 * enabled=false} ⇒ 各标量取文档值）。故本工具在要求"换一批变体"时，把配置**读进内存、改写 {@code randomization.enabled}
 * 后写一份临时副本**再交给 {@code WorldgenConfig.load}（**不改原档**）——与 social 侧 {@code WorldgenConfigTest}
 * 的既定做法同源。不指定 {@code randomize} 时**原样加载**（即配置里的开关说了算）。
 *
 * <p>★ **fail-closed**：未知 nation（配置 / 真档任一没有）⇒ {@code BAD_REQUEST}；配置路径不存在 / JSON 坏 ⇒ {@code
 * BAD_REQUEST}（带绝对路径与可读原因）；{@code dryRun=false} 但 branch 不存在 / 没有 head ⇒ {@code REJECTED}；提交冲突 ⇒
 * {@code CONFLICT}（带真实 head）。
 *
 * <p>★ **资源声明**：只写 social 命名空间（{@code social:*}）。它是 GM 工具，不在决策人桶里。
 */
public final class WorldgenInitializeTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.worldgen.initialize";

  /** 本工具唯二会提交的命令类型（**固定**，模型够不着 type）。 */
  public static final String SET_POPULATION_TYPE = "social.SetPopulation";

  /** 见 {@link #SET_POPULATION_TYPE}。 */
  public static final String CREATE_CITY_TYPE = "social.CreateCity";

  /** 城市表缺省最多列几行。 */
  public static final int DEFAULT_CITY_LIMIT = 20;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 本工具的资源声明：只写 social（逐格人口 + 城市节点）。 */
  private static final ResourceManifest WORLDGEN_WRITE =
      ResourceManifest.of(ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  /** 等级直方图的固定序（{@link PlannedCity#tierRank}）：MarketTown &lt; Town &lt; City &lt; MajorCity。 */
  private static final List<String> TIER_ORDER =
      List.of(
          PlannedCity.TIER_MARKET_TOWN,
          PlannedCity.TIER_TOWN,
          PlannedCity.TIER_CITY,
          PlannedCity.TIER_MAJOR_CITY);

  private final CoreSimos core;
  private final String initiator;
  private final Path worldgenConfigFile;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param worldgenConfigFile 冻结输入 JSON 的路径（**由 app 层拼**，见 {@code Shell}；本类不拼仓库相对路径）
   */
  public WorldgenInitializeTool(CoreSimos core, String initiator, Path worldgenConfigFile) {
    this.core = Objects.requireNonNull(core, "core");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.worldgenConfigFile = Objects.requireNonNull(worldgenConfigFile, "worldgenConfigFile");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 世界初始化：按冻结输入（config/worldgen/v17levant-nations.json）生成一国的聚落，"
        + "翻成 social 命令一次落**一条** revision（1 条 social.SetPopulation + 每座城一条 social.CreateCity）。"
        + "载荷 {nation(regionId，必填), seed?(缺省=配置), randomize?(缺省=配置 randomization.enabled),"
        + " dryRun?(缺省 true=只算不写), branch?(缺省 main), cityLimit?(缺省 "
        + DEFAULT_CITY_LIMIT
        + ")}。"
        + "返回摘要 {nation, seed, randomize, hexCount, ruralPopulation, urbanPopulation, totalPopulation,"
        + " cityCount, tierHistogram, capital, largestCity, shortfall, urbanCapacity, cities[]}；"
        + "dryRun=false 时另加 {revision, branch, commandCount}。"
        + "★ props 里的城市审计量（tier/catchmentHexes/localSurplus/tradeMultiplier/politicalMultiplier/"
        + "justification/seed）落进 SocialCity.props。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("nation", ToolSupport.prop("string", "regionId（如 奥斯特马克侯国）"));
    props.put("seed", ToolSupport.prop("integer", "随机种子；缺省 = 配置里该国的 seed"));
    props.put("randomize", ToolSupport.prop("boolean", "是否打开随机化；缺省 = 配置 randomization.enabled"));
    props.put("dryRun", ToolSupport.prop("boolean", "只算不写（缺省 true）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "cityLimit", ToolSupport.prop("integer", "返回的城市表最多列几行（缺省 " + DEFAULT_CITY_LIMIT + "）"));
    return ToolSupport.schema(props, List.of("nation"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发——与其余 GM 写工具同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return WORLDGEN_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "世界初始化 nation="
            + args.get("nation")
            + " dryRun="
            + args.getOrDefault("dryRun", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String nation = ToolSupport.requiredText(args, "nation");
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Boolean randomizeArg = optionalBoolean(args, "randomize");
      Boolean dryRunArg = optionalBoolean(args, "dryRun");
      boolean dryRun = dryRunArg == null || dryRunArg;
      Long seedArg = ToolSupport.optionalLong(args, "seed");
      Long limitArg = ToolSupport.optionalLong(args, "cityLimit");
      if (limitArg != null && limitArg < 0) {
        return ToolResult.error("BAD_REQUEST", "cityLimit 不得为负: " + limitArg);
      }
      int cityLimit = limitArg == null ? DEFAULT_CITY_LIMIT : (int) Math.min(limitArg, 1_000_000L);

      // 世界状态：**没 head 就没有世界**（dryRun 也一样要读地图拿区域格集）。
      Optional<RevisionId> head = core.head(branch);
      if (head.isEmpty()) {
        return ToolResult.error("REJECTED", "分支不存在: " + branch.value());
      }
      StateRef base = new StateRef(branch, head.get());
      SimulationState state = core.replay(base);
      GameMap map = ToolSupport.gameMap(state);

      WorldgenConfig config = loadConfig(randomizeArg);
      Region region = requireRegion(map, nation);
      NationSetup setup = config.byRegionId(nation).withHexes(region.hexes());
      ResolvedNation resolved = seedArg == null ? setup.resolve() : setup.resolve(seedArg);
      SettlementPlan plan =
          SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
      long seed = resolved.request().seed();
      // ★ 随机化是否**真的生效**（由解析出的区间是否退化成一点判断）：不指定参数时即配置的开关。
      boolean randomize = !ValueRange.isExact(setup.totalPopulation());

      Map<String, Object> summary = summary(nation, seed, randomize, plan, resolved, cityLimit);
      if (dryRun) {
        return ToolSupport.ok(summary);
      }

      String batchId = UUID.randomUUID().toString();
      List<CommandEnvelope> batch =
          buildBatch(
              batchId, initiator, branch, head.get(), resolved.request().region(), plan, seed);
      BatchResult result = core.submitBatch(batch);
      if (result instanceof BatchResult.Committed committed) {
        summary.put("revision", committed.ref().revision().value());
        summary.put("branch", committed.ref().branch().value());
        summary.put("commandCount", batch.size());
        return ToolSupport.ok(summary);
      }
      if (result instanceof BatchResult.Conflict conflict) {
        return ToolResult.error(
            "CONFLICT", "head 已变，未写入任何内容：真实 head = " + conflict.current().revision().value());
      }
      return ToolResult.error("REJECTED", rejectionReason((BatchResult.Rejected) result));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余写工具同一条：资源拒因必须原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而非"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "世界初始化失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 配置加载（含 randomize 的临时副本）─────────────────────────────────────────────────

  /**
   * 加载冻结输入。{@code randomize == null} ⇒ 原样加载（配置里的开关说了算）；否则写一份 {@code randomization.enabled=<值>}
   * 的**临时副本**再加载（不改原档，见类注）。
   */
  private WorldgenConfig loadConfig(Boolean randomize) {
    if (randomize == null) {
      return WorldgenConfig.load(worldgenConfigFile);
    }
    Path variant;
    try {
      variant = writeConfigVariant(worldgenConfigFile, randomize);
    } catch (IOException e) {
      throw new IllegalArgumentException("写随机化配置临时副本失败: " + worldgenConfigFile.toAbsolutePath(), e);
    }
    try {
      return WorldgenConfig.load(variant);
    } finally {
      try {
        Files.deleteIfExists(variant);
      } catch (IOException ignored) {
        // 临时文件删不掉不影响本次结果（deleteOnExit 兜底）。
      }
    }
  }

  /** 读原档、把 {@code randomization.enabled} 置为 {@code enabled}、写到临时文件（**不改原档**）。 */
  private static Path writeConfigVariant(Path file, boolean enabled) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("世界生成参数文件不存在或不是普通文件: " + file.toAbsolutePath());
    }
    JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    if (!(root instanceof ObjectNode object)
        || !(object.get("randomization") instanceof ObjectNode randomization)) {
      throw new IllegalArgumentException("世界生成参数文件缺少 randomization 对象: " + file.toAbsolutePath());
    }
    randomization.put("enabled", enabled);
    Path out = Files.createTempFile("simos-worldgen-", ".json");
    out.toFile().deleteOnExit();
    Files.writeString(out, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    return out;
  }

  /** 真档地图里的区域（**不是**配置里的 nation）：它的 {@code hexes()} 是生成器的格集来源。 */
  private static Region requireRegion(GameMap map, String nation) {
    Region region = map.regions().get(new RegionId(nation));
    if (region == null) {
      throw new IllegalArgumentException("真档地图里没有这个 region: " + nation);
    }
    return region;
  }

  // ── 命令批的构造（**包内可见**：用例要逐字节对拍 payload，见交付报告）────────────────────

  /**
   * 把一次生成的计划翻成命令批：**1 条** {@code social.SetPopulation}（全部农村人口，键序按 {@link HexCoord} 排序）+ **每座城一条**
   * {@code social.CreateCity}。命令顺序 = 先人口后城市（可读、可复现）。
   *
   * <p>★ 同批共享 {@code branch}/{@code expectedRevision}（{@link CoreSimos#submitBatch} 的硬约束）；{@code
   * correlationId} 用同一个 {@code batchId}（一条初始化链），{@code commandId} 各自新取。
   */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      BranchId branch,
      RevisionId expectedRevision,
      RegionId region,
      SettlementPlan plan,
      long seed) {
    List<CommandEnvelope> batch = new ArrayList<>(1 + plan.cities().size());
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            SET_POPULATION_TYPE,
            setPopulationPayload(plan)));
    for (PlannedCity city : plan.cities()) {
      batch.add(
          envelope(
              batchId,
              initiator,
              branch,
              expectedRevision,
              CREATE_CITY_TYPE,
              createCityPayload(region, city, seed)));
    }
    return List.copyOf(batch);
  }

  /** {@code {"entries":[{q,r,population}…]}}，**按 HexCoord 排序**（键序是内容的纯函数）。 */
  static String setPopulationPayload(SettlementPlan plan) {
    List<Map.Entry<HexCoord, Long>> entries = new ArrayList<>(plan.ruralPopulation().entrySet());
    entries.sort(Map.Entry.comparingByKey());
    List<Map<String, Object>> rows = new ArrayList<>(entries.size());
    for (Map.Entry<HexCoord, Long> entry : entries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("q", entry.getKey().q());
      row.put("r", entry.getKey().r());
      row.put("population", entry.getValue());
      rows.add(row);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", rows);
    return ToolSupport.json(payload);
  }

  /** 一座城的 {@code social.CreateCity} 载荷（props 带审计量，见类注）。 */
  static String createCityPayload(RegionId region, PlannedCity city, long seed) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tier", city.tier());
    props.put("catchmentHexes", city.catchmentHexes());
    props.put("localSurplus", city.localSurplus());
    props.put("tradeMultiplier", city.tradeMultiplier());
    props.put("politicalMultiplier", city.politicalMultiplier());
    props.put("justification", city.justification());
    // ★ seed 走**字符串**：props 经 JSON 往返，而 Jackson 把整数按**幅值**绑成 Integer/Long/…，
    //   一个超过 int 的 seed 会**静默换类型甚至丢值**（`SdInfoEntry.value` 的既定口径是"只保证标量往返"，
    //   而 long 不在那个保证里）。字符串是逐字节往返的，且 props 的读者只需拿它去复现，不必做算术。
    props.put("seed", Long.toString(seed));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", city.id());
    payload.put("name", city.name());
    payload.put("at", ToolSupport.hexCoord(city.at()));
    payload.put("region", region.value());
    payload.put("population", city.population());
    payload.put("props", props);
    return ToolSupport.json(payload);
  }

  private static CommandEnvelope envelope(
      String batchId,
      String initiator,
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

  // ── 汇总 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> summary(
      String nation,
      long seed,
      boolean randomize,
      SettlementPlan plan,
      ResolvedNation resolved,
      int cityLimit) {
    long ruralTotal = plan.ruralPopulation().values().stream().mapToLong(Long::longValue).sum();
    long urbanTotal = plan.cities().stream().mapToLong(PlannedCity::population).sum();
    Optional<PlannedCity> capital = capitalOf(plan, resolved);
    PlannedCity largest = largestCity(plan);

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("nation", nation);
    view.put("seed", seed);
    view.put("randomize", randomize);
    view.put("hexCount", plan.ruralPopulation().size());
    view.put("ruralPopulation", ruralTotal);
    view.put("urbanPopulation", urbanTotal);
    view.put("totalPopulation", ruralTotal + urbanTotal);
    view.put("cityCount", plan.cities().size());
    view.put("tierHistogram", tierHistogram(plan));
    view.put("capital", capital.map(WorldgenInitializeTool::cityView).orElse(null));
    view.put("largestCity", largest == null ? null : cityView(largest));
    view.put("shortfall", plan.shortfall());
    view.put("urbanCapacity", plan.urbanCapacity());
    List<Map<String, Object>> cities = new ArrayList<>();
    for (PlannedCity city : plan.cities()) {
      if (cities.size() >= cityLimit) {
        break;
      }
      cities.add(cityRow(city));
    }
    view.put("cities", cities);
    return view;
  }

  /** 首都 = 城市表里与配置首都同名的那一座（无首都 / 名字对不上 ⇒ 空）。 */
  private static Optional<PlannedCity> capitalOf(SettlementPlan plan, ResolvedNation resolved) {
    String capitalName = resolved.request().capital().map(anchor -> anchor.name()).orElse(null);
    if (capitalName == null) {
      return Optional.empty();
    }
    for (PlannedCity city : plan.cities()) {
      if (capitalName.equals(city.name())) {
        return Optional.of(city);
      }
    }
    return Optional.empty();
  }

  /** 最大城（城市表已按人口降序、id 升序 ⇒ 首项即最大；空表 ⇒ null）。 */
  private static PlannedCity largestCity(SettlementPlan plan) {
    return plan.cities().stream()
        .max(Comparator.comparingLong(PlannedCity::population))
        .orElse(null);
  }

  /** 等级直方图（按 {@link #TIER_ORDER} 固定序，只列出现过的档）。 */
  private static Map<String, Object> tierHistogram(SettlementPlan plan) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String tier : TIER_ORDER) {
      counts.put(tier, 0);
    }
    for (PlannedCity city : plan.cities()) {
      counts.merge(city.tier(), 1, Integer::sum);
    }
    Map<String, Object> histogram = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > 0) {
        histogram.put(entry.getKey(), entry.getValue());
      }
    }
    return histogram;
  }

  /** 城市行的**精简**视图（城市表逐行）。 */
  private static Map<String, Object> cityRow(PlannedCity city) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tier", city.tier());
    row.put("name", city.name());
    row.put("at", ToolSupport.hexCoord(city.at()));
    row.put("population", city.population());
    row.put("catchmentHexes", city.catchmentHexes());
    return row;
  }

  /** 首都 / 最大城的视图（名、人口、格）。 */
  private static Map<String, Object> cityView(PlannedCity city) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", city.name());
    row.put("population", city.population());
    row.put("at", ToolSupport.hexCoord(city.at()));
    row.put("tier", city.tier());
    return row;
  }

  /** 整批被拒时的人话（逐条结局里真正被拒的拒因）。 */
  private static String rejectionReason(BatchResult.Rejected rejected) {
    List<String> reasons = new ArrayList<>();
    for (CommandOutcome outcome : rejected.outcomes()) {
      if (outcome.result() instanceof CommandResult.Rejected r) {
        reasons.add(outcome.command().type() + ": " + r.reason());
      }
    }
    return reasons.isEmpty() ? "整批被拒（无逐条拒因）" : String.join("；", reasons);
  }

  /** 可选布尔：{@code null} 缺席；接受布尔或 {@code "true"}/{@code "false"} 字符串。 */
  private static Boolean optionalBoolean(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String text && !text.isBlank()) {
      if ("true".equalsIgnoreCase(text.trim())) {
        return true;
      }
      if ("false".equalsIgnoreCase(text.trim())) {
        return false;
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是布尔值");
  }
}
