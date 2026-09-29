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
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.HouseholdSeeder;
import io.mosire.simos.app.world.PopulationSeeder;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.social.gen.ArmyPlan;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.gen.ValueRange;
import io.mosire.simos.social.gen.WorldgenConfig;
import io.mosire.simos.social.population.PopulationGroup;
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
 * <p>★★ **军队编制块（2026-09-23，{@code army} 缺省真）**：在**同一个批**里按固定顺序追加（{@link #appendArmyCommands}）——
 *
 * <ol>
 *   <li>{@code map.UpdateRegion}（**仅当**当前 region 的 tag 不是 {@code nation:<nationId>}）：{@code
 *       sd.CreateNation} 要求 homeRegion 有国家 tag（{@code NationTag.PREFIX}），而真档三国 region 的 tag 是文档式
 *       {@code "Nation"} （不带前缀）⇒ 必须先改 tag。★★ 该命令的 {@code meta} 是**整体替换**（{@code
 *       MapPayloads.optionalMeta} 只读 color/tag/description/annexedBy 四个字段，未写的清成 null）⇒ 本工具**从当前
 *       region 原样回填 color / description / annexedBy**、只换 tag（只发 {@code {tag:…}} 会把区域颜色抹掉）；tag 已是目标值
 *       ⇒ **跳过**（幂等）。
 *   <li>{@code sd.CreateNation}（{@code nationId = regionId 字面值}）。★ {@code adminBudgetPerTick} **置
 *       0**：行政预算属后续 政治-经济模型，冻结输入**没有**它的依据，本笔不臆造（{@code Nation} 允许 0）。
 *   <li>**根单位**一条 + **每个兵种一条** {@code unit.CreateUnit}：{@code member} 取编制人数、{@code equipment} 按
 *       {@code armKits} 的"每百人件数"换算（{@code ceil(人数 × 件数 / 100)}，键名原样）、{@code speed}/{@code
 *       mobilityPerMille} 取 {@link #ARMY_SPEED}/{@link #ARMY_MOBILITY_PER_MILLE}、{@code status =
 *       RESTING}、兵种单位的 {@code parent} 指向根单位。
 *   <li>{@code unit.CreateCommandChain}：{@code commander = 根单位}，{@code members}
 *       **含根单位与全部兵种单位**（{@code CommandChain} 构造期硬要求 {@code commander ∈ members}）。
 *   <li>{@code sd.CreateArmy}：{@code rootUnitId = 根单位}。
 * </ol>
 *
 * <p>★ 同批内 handler 看到的是**累计候选态**（{@code CommandBus.runBatch} 的 candidate 逐条演进）⇒ {@code
 * UpdateRegion} 在前、{@code CreateNation} 在后即可通过校验，且**整批仍只落一条 revision**（不拆两条）。
 *
 * <p>★ **资源声明**：写四个命名空间（{@code map}/{@code social}/{@code unit}/{@code sd}，见 {@link
 * #WORLDGEN_WRITE}）。 它是 GM 工具，不在决策人桶里。
 */
public final class WorldgenInitializeTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.worldgen.initialize";

  /** 本工具唯二会提交的命令类型（**固定**，模型够不着 type）。 */
  public static final String SET_POPULATION_TYPE = "social.SetPopulation";

  /** 见 {@link #SET_POPULATION_TYPE}。 */
  public static final String CREATE_CITY_TYPE = "social.CreateCity";

  /**
   * ★ R1（T4）：人口批次的创世命令 —— **一条命令落该国的全部批次**（农村 + 城镇），载荷由 {@link PopulationSeeder} 从计划算出；它与 {@code
   * economy.Seed} 读**同一份**批次列表（见 {@link #buildBatch}）。
   */
  public static final String SEED_GROUPS_TYPE = "social.SeedGroups";

  /** ★ 军队编制块的五条命令类型（{@code army} 为真时按 {@link #appendArmyCommands} 的顺序追加）。 */
  public static final String UPDATE_REGION_TYPE = "map.UpdateRegion";

  /** 见 {@link #UPDATE_REGION_TYPE}。 */
  public static final String CREATE_NATION_TYPE = "sd.CreateNation";

  /** 见 {@link #UPDATE_REGION_TYPE}。 */
  public static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** 见 {@link #UPDATE_REGION_TYPE}。 */
  public static final String CREATE_COMMAND_CHAIN_TYPE = "unit.CreateCommandChain";

  /** 见 {@link #UPDATE_REGION_TYPE}。 */
  public static final String CREATE_ARMY_TYPE = "sd.CreateArmy";

  /**
   * ★ R2a（2026-09-25）：经济切片播种命令 —— 一次把该国的**全部格**种进 economy 切片（§十"验收目标 A"）。
   *
   * <p>它与人口/城市**同批**（同一 branch + 同一 expectedRevision ⇒ 一条 revision），载荷由 {@link
   * io.mosire.simos.app.world.EconomySeeder} 从**已经算好的** {@code SettlementPlan} + 真地图地形算出。
   */
  public static final String SEED_ECONOMY_TYPE = "economy.Seed";

  /**
   * ★★ <b>H1：家户 actor 的播种命令</b>（{@code actor.Seed}）—— 与人口/经济**同批**（同一 branch + 同一 expectedRevision
   * ⇒ 一条 revision），载荷由 {@link HouseholdSeeder} 从 {@link EconomySeeder#plan} 交回的 **同一份**家户开缸库存算出。
   *
   * <p>★★ <b>为什么它必须与经济同批</b>：H1 起"商品库存"的唯一持久真源是 actor 切片的 {@code GoodsAccount} （裁定 D3-C/K1）—— 若只播
   * economy 而不播 actor，世界起来的当天就<b>没有一本家户账</b>， 而日结算的消费与投入都要读它（{@code EconomyDayStepper} 当场抛，不静默当 0）。
   */
  public static final String SEED_ACTOR_TYPE = "actor.Seed";

  /**
   * ★ **中立移动量**（{@code speed} / {@code mobilityPerMille}）：冻结输入 {@code
   * config/worldgen/v17levant-nations.json} **只给编制人数、不给行军速度**，本笔**不臆造**组织级速度。
   *
   * <p>{@code speed = 3}、{@code mobilityPerMille = 1000}：后者是地形成本的**恒等倍率**（{@code
   * scale(moveCost×1000, 1000) == moveCost×1000}，移动模型下"不放大也不缩小"）；前者是无依据的中立整数，只保证 {@code
   * effectiveSpeed ≥ 1}（三档状态都成立： RESTING 下 {@code max(1, (3×500+500)/1000) = 2}）。两者都进 {@code
   * unit.CreateUnit} 的必填字段（{@code Unit} 构造期要求 {@code speed ≥ 1}、{@code mobilityPerMille ≥
   * 1}），但在本笔"单位 RESTING、无路线"时**不影响编制**；真正的速度依据应由后续 移动/经济模型给出。
   */
  public static final int ARMY_SPEED = 3;

  /** 见 {@link #ARMY_SPEED}。 */
  public static final int ARMY_MOBILITY_PER_MILLE = 1000;

  /** 初建单位的作战状态：{@code RESTING}（{@link io.mosire.simos.unit.UnitStatus} 三态之一；驻防、不移动不交战）。 */
  public static final String ARMY_STATUS = "RESTING";

  /**
   * 军队编制单位的 id 后缀：根单位 {@code <nationId>-army}、兵种单位 {@code <nationId>-<兵种>}、链 {@code …-chain}、 军队
   * {@code …-army-id}。
   */
  static final String ROOT_UNIT_SUFFIX = "-army";

  /** 见 {@link #ROOT_UNIT_SUFFIX}。 */
  static final String CHAIN_SUFFIX = "-chain";

  /** 见 {@link #ROOT_UNIT_SUFFIX}。 */
  static final String ARMY_ID_SUFFIX = "-army-id";

  /** 根单位 / 军队的显示名后缀（{@code <displayName>全军}）。 */
  static final String ARMY_NAME_SUFFIX = "全军";

  /** 指挥链显示名后缀（{@code <displayName>指挥链}）。 */
  static final String CHAIN_NAME_SUFFIX = "指挥链";

  /** 城市表缺省最多列几行。 */
  public static final int DEFAULT_CITY_LIMIT = 20;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /**
   * 本工具的资源声明（2026-09-23 军队编制起，R2a 起五域）：它一次写的命名空间有五个 —— {@code social}（逐格人口 + 城市节点）、 {@code map}（
   * {@code map.UpdateRegion} 给国家区域打 {@code nation:} tag）、{@code unit}（单位与指挥链）、{@code sd}（国家与军队）、
   * {@code economy}（R2a 起：逐格经济状态）。
   *
   * <p>★ 五个都声明为 {@link ResourcePolicy#UNRESTRICTED}（不是 sd 窄写那族惯用的 {@code READ_ONLY}）：本工具**真的写** sd
   * 域，声明成只读会让"调用者未表态 sd 时"回落成只读、把本工具在系统身份下整调拒掉（{@code ResourceAuthorizer} 的回退分支）。
   */
  private static final ResourceManifest WORLDGEN_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              // ★ H1：家户 actor（{@code actor.Seed}）—— 同 economy 的待遇（真写它，故 UNRESTRICTED）。
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #WORLDGEN_WRITE} 同源的逐命名空间粗断言（本工具是 GM 工具，调用者五个命名空间都 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.MAP_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"));

  /** 等级直方图的固定序（{@link PlannedCity#tierRank}）：MarketTown &lt; Town &lt; City &lt; MajorCity。 */
  private static final List<String> TIER_ORDER =
      List.of(
          PlannedCity.TIER_MARKET_TOWN,
          PlannedCity.TIER_TOWN,
          PlannedCity.TIER_CITY,
          PlannedCity.TIER_MAJOR_CITY);

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;
  private final Path worldgenConfigFile;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（R2a 起要进 {@code economy.Seed} 载荷的 {@code mapId}，落进 {@code EconomyMeta}）
   * @param worldgenConfigFile 冻结输入 JSON 的路径（**由 app 层拼**，见 {@code Shell}；本类不拼仓库相对路径）
   */
  public WorldgenInitializeTool(
      CoreSimos core, String initiator, String mapId, Path worldgenConfigFile) {
    this.core = Objects.requireNonNull(core, "core");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.worldgenConfigFile = Objects.requireNonNull(worldgenConfigFile, "worldgenConfigFile");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 世界初始化：按冻结输入（config/worldgen/v17levant-nations.json）生成一国的聚落，"
        + "翻成命令一次落**一条** revision（1 条 social.SetPopulation + 每座城一条 social.CreateCity"
        + " + 1 条 economy.Seed（R2a：逐格经济状态）；"
        + "army 为真时再追加 map.UpdateRegion? + sd.CreateNation + 根/兵种 unit.CreateUnit + unit.CreateCommandChain"
        + " + sd.CreateArmy）。"
        + "载荷 {nation(regionId，必填), seed?(缺省=配置), randomize?(缺省=配置 randomization.enabled),"
        + " dryRun?(缺省 true=只算不写), army?(缺省 true=连军队编制一起建；false=只做人口+城市), branch?(缺省 main),"
        + " economyProfile?(legacy|complete，缺省 legacy),"
        + " cityLimit?(缺省 "
        + DEFAULT_CITY_LIMIT
        + ")}。"
        + "返回摘要 {nation, seed, randomize, hexCount, ruralPopulation, urbanPopulation, totalPopulation,"
        + " cityCount, tierHistogram, capital, largestCity, shortfall, urbanCapacity, cities[], army?}；"
        + "dryRun=false 时另加 {revision, branch, commandCount}。"
        + "★ props 里的城市审计量（tier/catchmentHexes/localSurplus/tradeMultiplier/politicalMultiplier/"
        + "justification/seed）落进 SocialCity.props。"
        + "★ army 段含 peacetime/mobilization/establishmentTotal/armCount/rootUnitId/chainId/armyId/nationId/"
        + "各单位表；sd.CreateNation 的 adminBudgetPerTick 置 0（行政预算属后续政治-经济模型，本笔不臆造）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("nation", ToolSupport.prop("string", "regionId（如 奥斯特马克侯国）"));
    props.put("seed", ToolSupport.prop("integer", "随机种子；缺省 = 配置里该国的 seed"));
    props.put("randomize", ToolSupport.prop("boolean", "是否打开随机化；缺省 = 配置 randomization.enabled"));
    props.put("dryRun", ToolSupport.prop("boolean", "只算不写（缺省 true）"));
    props.put(
        "army",
        ToolSupport.prop(
            "boolean",
            "是否连军队编制一起建（缺省 true：同一批追加 sd.CreateNation + 单位 + 指挥链 + sd.CreateArmy）；"
                + "false = 只做人口+城市（不写国家 tag、不建国）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "economyProfile",
        ToolSupport.prop(
            "string", "经济地基 profile：legacy（缺省，旧 payload 逐字节不变）或 complete（种完整 E1/E2 地基）"));
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
            + " army="
            + args.getOrDefault("army", true)
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
      Optional<Boolean> randomizeArg = optionalBoolean(args, "randomize");
      boolean dryRun = optionalBoolean(args, "dryRun").orElse(true);
      boolean withArmy = optionalBoolean(args, "army").orElse(true);
      Long seedArg = ToolSupport.optionalLong(args, "seed");
      Long limitArg = ToolSupport.optionalLong(args, "cityLimit");
      if (limitArg != null && limitArg < 0) {
        return ToolResult.error("BAD_REQUEST", "cityLimit 不得为负: " + limitArg);
      }
      // ★★ E3：初始禀赋是 GM 可传参数（毫/人；缺省 = EconomySeeder 的出厂值）。不用 System property。
      Long genesisMoneyArg = ToolSupport.optionalLong(args, "genesisMoneyMilliPerCapita");
      if (genesisMoneyArg != null && genesisMoneyArg < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "genesisMoneyMilliPerCapita 不得为负: " + genesisMoneyArg);
      }
      long genesisMoneyMilliPerCapita =
          genesisMoneyArg == null ? EconomySeeder.genesisMoneyMilliPerCapita() : genesisMoneyArg;
      // ★★ P1：经济地基 profile（GM 可传参数；缺省 legacy，保证旧载荷逐值不变）。不用 System property。
      EconomySeeder.FoundationProfile economyProfile =
          EconomySeeder.FoundationProfile.parse(
              ToolSupport.optionalText(args, "economyProfile", "legacy"));
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
      // ★ 驻地 = 首都格；配置里的首都不在生成的聚落表里 ⇒ fail-closed（BAD_REQUEST），不臆造坐标。
      HexCoord armyAt = withArmy ? requireCapitalHex(plan, resolved) : null;
      if (withArmy) {
        summary.put(
            "army", armySummary(region.id().value(), setup.displayName(), setup.army(), armyAt));
      }
      if (dryRun) {
        return ToolSupport.ok(summary);
      }

      String batchId = UUID.randomUUID().toString();
      // ★ R1：批次的锚点 = 提交时的**世界当前日**（由调用方一次定死，见 buildBatch 的 @param）。
      long anchorTick = state.meta().timestamp().tick();
      List<CommandEnvelope> batch =
          withArmy
              ? buildBatch(
                  batchId,
                  initiator,
                  mapId,
                  branch,
                  head.get(),
                  map,
                  region,
                  plan,
                  seed,
                  setup.army(),
                  setup.displayName(),
                  armyAt,
                  anchorTick,
                  genesisMoneyMilliPerCapita,
                  economyProfile)
              : buildBatch(
                  batchId,
                  initiator,
                  mapId,
                  branch,
                  head.get(),
                  map,
                  region,
                  plan,
                  seed,
                  anchorTick,
                  genesisMoneyMilliPerCapita,
                  economyProfile);
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
  private WorldgenConfig loadConfig(Optional<Boolean> randomize) {
    if (randomize.isEmpty()) {
      return WorldgenConfig.load(worldgenConfigFile);
    }
    Path variant;
    try {
      variant = writeConfigVariant(worldgenConfigFile, randomize.get());
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
   * 把一次生成的计划翻成命令批：**1 条** {@code social.SetPopulation}（全部农村人口序列，键序按 {@link HexCoord} 排序）+ **每座城一条**
   * {@code social.CreateCity} + **1 条** {@code social.SeedGroups}（R1/T4：人口批次）+ **1 条** {@code
   * economy.Seed}（R2a：该国全部格的初始经济状态）+ **1 条** {@code actor.Seed}（H1：家户 actor 与它们的账本）。 命令顺序 =
   * 先人口序列、后城市、再批次、再经济、最后家户 actor（可读、可复现）。
   *
   * <p>★★ **R1 的接缝（T4）**：批次列表在这里**一次算出**（{@link PopulationSeeder#groups}），**同一份**喂给 {@code
   * social.SeedGroups}（{@link PopulationSeeder#payload}）与 {@code economy.Seed} （{@link
   * EconomySeeder#payload(String, java.util.List, GameMap)}）—— "Σ group == 经济侧总人口"因此是构造性的，
   * **不需要**跨切片协调器（那是后续轮次的事）。
   *
   * <p>★★ <b>H1 的接缝（家户 actor）</b>：{@code economy.Seed} 与 {@code actor.Seed} 读的是<b>同一份</b> {@link
   * EconomySeeder#plan}（前者要 entries、后者要 {@code householdStocks}）—— 家户的 id 由 {@code HouseholdActors}
   * 拼（唯一拼写点），开缸库存由 {@code EconomySeeder.openingStock} 算（唯一拼写点）。
   *
   * <p>★ 同批共享 {@code branch}/{@code expectedRevision}（{@link CoreSimos#submitBatch} 的硬约束）；{@code
   * correlationId} 用同一个 {@code batchId}（一条初始化链），{@code commandId} 各自新取。
   *
   * @param mapId 本世界的 map 称谓（进 {@code EconomyMeta}）
   * @param map 真地图（{@link EconomySeeder} 取逐格地形系数用）
   * @param anchorTick 批次的锚点（世界日）—— 见 {@link #buildBatch(String, String, String, BranchId,
   *     RevisionId, GameMap, Region, SettlementPlan, long, long)}
   */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      String mapId,
      BranchId branch,
      RevisionId expectedRevision,
      GameMap map,
      Region region,
      SettlementPlan plan,
      long seed,
      long anchorTick) {
    return buildBatch(
        batchId,
        initiator,
        mapId,
        branch,
        expectedRevision,
        map,
        region,
        plan,
        seed,
        anchorTick,
        EconomySeeder.genesisMoneyMilliPerCapita(),
        EconomySeeder.FoundationProfile.LEGACY);
  }

  /**
   * ★★ E3：同上一支 + **初始禀赋参数**（毫/人）。缺省重载逐值等于 E3 之前；本重载把参数透传给 {@link EconomySeeder#plan(String, List,
   * GameMap, long)}，只改 INITIAL_ENDOWMENT 的每人金额。profile 缺省 LEGACY。
   */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      String mapId,
      BranchId branch,
      RevisionId expectedRevision,
      GameMap map,
      Region region,
      SettlementPlan plan,
      long seed,
      long anchorTick,
      long genesisMoneyMilliPerCapita) {
    return buildBatch(
        batchId,
        initiator,
        mapId,
        branch,
        expectedRevision,
        map,
        region,
        plan,
        seed,
        anchorTick,
        genesisMoneyMilliPerCapita,
        EconomySeeder.FoundationProfile.LEGACY);
  }

  /**
   * ★★ P1：人口+城市+经济+家户 actor 批的完整入口 —— 额外把经济地基 profile 透传给 {@link EconomySeeder#plan(String, List,
   * GameMap, long, EconomySeeder.FoundationProfile)}。 {@code economy.Seed} 与 {@code actor.Seed}
   * 仍读同一次 plan（同源接缝不走样）。
   */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      String mapId,
      BranchId branch,
      RevisionId expectedRevision,
      GameMap map,
      Region region,
      SettlementPlan plan,
      long seed,
      long anchorTick,
      long genesisMoneyMilliPerCapita,
      EconomySeeder.FoundationProfile economyProfile) {
    List<CommandEnvelope> batch = new ArrayList<>(4 + plan.cities().size());
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
              createCityPayload(region.id(), city, seed)));
    }
    List<PopulationGroup> groups = PopulationSeeder.groups(plan, anchorTick);
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            SEED_GROUPS_TYPE,
            PopulationSeeder.payload(groups)));
    // ★★ H1：**经济与家户 actor 同源**（裁定 D3-C/K1）—— 一条 {@link EconomySeeder#plan} 同时交出
    //   {@code economy.Seed} 的 entries + markets 与家户的开缸库存 + **创世货币禀赋**（后两者进
    //   {@code actor.Seed} 的同一本账）："一次算出、同一份喂两条命令"，两处各算一遍必然漂开
    //   （本仓明令禁止的"同一事实两处拼写点"）。
    EconomySeeder.Seed seeding =
        EconomySeeder.plan(mapId, groups, map, genesisMoneyMilliPerCapita, economyProfile);
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            SEED_ECONOMY_TYPE,
            seeding.economyPayload()));
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            SEED_ACTOR_TYPE,
            // ★★ H4（裁定 K14）：货币是和开缸库存**同一次**算出来的（同源），故它们一起进这条命令。
            // ★★ H5（⑤）：**经营主体的开缸账**也来自同一次 plan（`Seed.operators()`）⇒ 家户与经营者两族主体
            //   在同一份载荷里播下（命令数不变：仍是同一批里的一条 actor.Seed）。
            HouseholdSeeder.payload(
                mapId,
                seeding.householdLocations(),
                seeding.householdStocks(),
                seeding.householdMoney(),
                seeding.operators())));
    return List.copyOf(batch);
  }

  /**
   * ★ **军队编制批**：先来 {@link #buildBatch} 的人口+城市+经济，再按 {@link #appendArmyCommands} 的固定序追加军队块 ——
   * 整批仍是**同一 branch + 同一 expectedRevision**（⇒ 一条 revision）。
   *
   * @param region 真档区域（读它当前的 {@code meta.tag} 决定要不要发 {@code map.UpdateRegion}）
   * @param army 编制（人数 + 装备配比）
   * @param displayName 配置里的显示名（根/军队/链的名字取它）
   * @param at 军队驻地（首都格）
   * @param anchorTick 人口批次的锚点（世界日，= 提交时的世界当前日）：批次记的是"锚点时刻的年龄"， 故必须由调用方**一次定死**，不能留给两条命令各自取
   */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      String mapId,
      BranchId branch,
      RevisionId expectedRevision,
      GameMap map,
      Region region,
      SettlementPlan plan,
      long seed,
      ArmyPlan army,
      String displayName,
      HexCoord at,
      long anchorTick) {
    return buildBatch(
        batchId,
        initiator,
        mapId,
        branch,
        expectedRevision,
        map,
        region,
        plan,
        seed,
        army,
        displayName,
        at,
        anchorTick,
        EconomySeeder.genesisMoneyMilliPerCapita(),
        EconomySeeder.FoundationProfile.LEGACY);
  }

  /** ★★ E3：军队批 + 初始禀赋参数（毫/人）—— 与无军队那支共用同一个透传点。profile 缺省 LEGACY。 */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      String mapId,
      BranchId branch,
      RevisionId expectedRevision,
      GameMap map,
      Region region,
      SettlementPlan plan,
      long seed,
      ArmyPlan army,
      String displayName,
      HexCoord at,
      long anchorTick,
      long genesisMoneyMilliPerCapita) {
    return buildBatch(
        batchId,
        initiator,
        mapId,
        branch,
        expectedRevision,
        map,
        region,
        plan,
        seed,
        army,
        displayName,
        at,
        anchorTick,
        genesisMoneyMilliPerCapita,
        EconomySeeder.FoundationProfile.LEGACY);
  }

  /** ★★ P1：军队批 + 初始禀赋 + 经济地基 profile（与无军队那支共用同一个透传点）。 */
  static List<CommandEnvelope> buildBatch(
      String batchId,
      String initiator,
      String mapId,
      BranchId branch,
      RevisionId expectedRevision,
      GameMap map,
      Region region,
      SettlementPlan plan,
      long seed,
      ArmyPlan army,
      String displayName,
      HexCoord at,
      long anchorTick,
      long genesisMoneyMilliPerCapita,
      EconomySeeder.FoundationProfile economyProfile) {
    List<CommandEnvelope> batch =
        new ArrayList<>(
            buildBatch(
                batchId,
                initiator,
                mapId,
                branch,
                expectedRevision,
                map,
                region,
                plan,
                seed,
                anchorTick,
                genesisMoneyMilliPerCapita,
                economyProfile));
    appendArmyCommands(
        batch, batchId, initiator, branch, expectedRevision, region, army, displayName, at);
    return List.copyOf(batch);
  }

  /**
   * 把军队编制块追加进批（顺序见类注）：{@code UpdateRegion?} → {@code CreateNation} → {@code CreateUnit}(根) → {@code
   * CreateUnit}(每兵种) → {@code CreateCommandChain} → {@code CreateArmy}。
   *
   * <p>★ {@code map.UpdateRegion} **仅当**当前 tag 不等于 {@code nation:<nationId>} 时追加（幂等：已是目标 tag 就不动
   * meta， 免得白白覆盖颜色）；其余六类命令**无条件**追加（{@code sd.CreateNation} 在已建国的区域上会被 {@code CreateNationHandler}
   * 拒「国家已存在」，这是调用方要处理的重放错误，不由本处静默跳过）。
   */
  private static void appendArmyCommands(
      List<CommandEnvelope> batch,
      String batchId,
      String initiator,
      BranchId branch,
      RevisionId expectedRevision,
      Region region,
      ArmyPlan army,
      String displayName,
      HexCoord at) {
    String nationId = region.id().value();
    String targetTag = NationTag.tagFor(NationId.parse(nationId));
    if (!targetTag.equals(region.meta().tag())) {
      batch.add(
          envelope(
              batchId,
              initiator,
              branch,
              expectedRevision,
              UPDATE_REGION_TYPE,
              updateRegionPayload(region, targetTag)));
    }
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            CREATE_NATION_TYPE,
            createNationPayload(nationId, displayName, nationId)));
    String rootId = rootUnitId(nationId);
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            CREATE_UNIT_TYPE,
            createUnitPayload(
                rootId,
                displayName + ARMY_NAME_SUFFIX,
                at,
                establishmentTotal(army),
                Map.of(),
                null)));
    List<String> armUnitIds = new ArrayList<>(army.establishment().size());
    for (Map.Entry<String, Integer> entry : army.establishment().entrySet()) {
      String armId = armUnitId(nationId, entry.getKey());
      armUnitIds.add(armId);
      batch.add(
          envelope(
              batchId,
              initiator,
              branch,
              expectedRevision,
              CREATE_UNIT_TYPE,
              createUnitPayload(
                  armId,
                  entry.getKey(),
                  at,
                  entry.getValue(),
                  equipmentFor(army, entry.getKey()),
                  rootId)));
    }
    // ★ commander（根单位）必须 ∈ members（CommandChain 构造期硬约束）⇒ members 含根 + 全部兵种。
    List<String> members = new ArrayList<>(1 + armUnitIds.size());
    members.add(rootId);
    members.addAll(armUnitIds);
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            CREATE_COMMAND_CHAIN_TYPE,
            createCommandChainPayload(
                chainId(nationId), displayName + CHAIN_NAME_SUFFIX, rootId, members)));
    batch.add(
        envelope(
            batchId,
            initiator,
            branch,
            expectedRevision,
            CREATE_ARMY_TYPE,
            createArmyPayload(armyId(nationId), nationId, rootId, displayName + ARMY_NAME_SUFFIX)));
  }

  // ── 军队编制：id / 载荷 ──────────────────────────────────────────────────────────────

  /** 根单位 id：{@code <nationId>-army}。 */
  static String rootUnitId(String nationId) {
    return nationId + ROOT_UNIT_SUFFIX;
  }

  /** 兵种单位 id：{@code <nationId>-<兵种>}。 */
  static String armUnitId(String nationId, String arm) {
    return nationId + "-" + arm;
  }

  /** 指挥链 id：{@code <nationId>-chain}。 */
  static String chainId(String nationId) {
    return nationId + CHAIN_SUFFIX;
  }

  /** 军队 id：{@code <nationId>-army-id}（与根单位 id 同前缀、不同尾，避免字符串层面的混淆）。 */
  static String armyId(String nationId) {
    return nationId + ARMY_ID_SUFFIX;
  }

  /** 编制合计（{@code Σ establishment}）。 */
  static int establishmentTotal(ArmyPlan army) {
    int total = 0;
    for (int member : army.establishment().values()) {
      total += member;
    }
    return total;
  }

  /**
   * {@code map.UpdateRegion} 载荷：★★ **meta 是整体替换**，故把当前 color/description/annexedBy **原样回填**，只换 tag。
   * 只发 {@code {tag:…}} 会让 {@code MapPayloads.optionalMeta} 把未写字段读成 null，**抹掉区域颜色**。
   */
  private static String updateRegionPayload(Region region, String tag) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("color", region.meta().color());
    meta.put("tag", tag);
    meta.put("description", region.meta().description());
    meta.put("annexedBy", region.meta().annexedBy());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("regionId", region.id().value());
    payload.put("meta", meta);
    return ToolSupport.json(payload);
  }

  /**
   * {@code sd.CreateNation} 载荷。★ {@code adminBudgetPerTick = 0}：行政预算属后续政治-经济模型，本笔**不臆造**（{@code
   * Nation} 允许 0）。
   */
  private static String createNationPayload(String nationId, String name, String homeRegionId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nationId", nationId);
    payload.put("name", name);
    payload.put("homeRegionId", homeRegionId);
    payload.put("adminBudgetPerTick", 0);
    return ToolSupport.json(payload);
  }

  /** {@code unit.CreateUnit} 载荷（{@code parent} 为 null ⇒ 不发该键，即根单位）。 */
  private static String createUnitPayload(
      String id,
      String name,
      HexCoord at,
      int member,
      Map<String, Integer> equipment,
      String parent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id);
    payload.put("name", name);
    payload.put("position", ToolSupport.hexCoord(at));
    payload.put("member", member);
    payload.put("equipment", equipment);
    payload.put("speed", ARMY_SPEED);
    payload.put("mobilityPerMille", ARMY_MOBILITY_PER_MILLE);
    payload.put("status", ARMY_STATUS);
    if (parent != null) {
      payload.put("parent", parent);
    }
    return ToolSupport.json(payload);
  }

  /**
   * {@code unit.CreateCommandChain} 载荷（{@code members} 含 commander，见 {@link #appendArmyCommands}）。
   */
  private static String createCommandChainPayload(
      String chainId, String name, String commander, List<String> members) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chainId", chainId);
    payload.put("name", name);
    payload.put("commander", commander);
    payload.put("members", members);
    return ToolSupport.json(payload);
  }

  /** {@code sd.CreateArmy} 载荷（键名以 {@code CreateArmyHandler} 为准：armyId/nationId/rootUnitId/name）。 */
  private static String createArmyPayload(
      String armyId, String nationId, String rootUnitId, String name) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("armyId", armyId);
    payload.put("nationId", nationId);
    payload.put("rootUnitId", rootUnitId);
    payload.put("name", name);
    return ToolSupport.json(payload);
  }

  /**
   * 某兵种的装备表：对 {@code armKits} 的每件装备取 {@code ceil(人数 × 每百人件数 / 100)}。兵种不在 {@code armKits} 里
   * （如德意志的「仆从兵」）⇒ 空表（{@code unit.CreateUnit} 的 {@code equipment} 允许空对象）。
   */
  private static Map<String, Integer> equipmentFor(ArmyPlan army, String arm) {
    Map<String, Integer> equipment = new LinkedHashMap<>();
    Map<String, Integer> kit = army.armKits().get(arm);
    if (kit == null) {
      return equipment;
    }
    int member = army.establishment().get(arm);
    for (Map.Entry<String, Integer> item : kit.entrySet()) {
      equipment.put(item.getKey(), ceilPerHundred(member, item.getValue()));
    }
    return equipment;
  }

  /** {@code ceil(member × perHundred / 100)}（"每 100 人的件数，不足 100 人向上取整"）。 */
  private static int ceilPerHundred(int member, int perHundred) {
    return Math.toIntExact((member * (long) perHundred + 99L) / 100L);
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
    // ★ R1（T5）：**不再发 population** —— 城的城镇人口是**派生量**（该城的批次之和），
    //   载荷里的 population 由 CreateCityHandler 明令拒收（见其类注），故这里也不该再写它。
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

  /**
   * 军队驻地 = 配置首都所在的那一格。配置里有首都、生成器却没产出同名城 ⇒ **fail-closed**（{@link IllegalArgumentException} ⇒ {@code
   * BAD_REQUEST}），不臆造坐标。
   */
  private static HexCoord requireCapitalHex(SettlementPlan plan, ResolvedNation resolved) {
    return capitalOf(plan, resolved)
        .map(PlannedCity::at)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "配置里的首都不在生成的聚落表里（无法为军队选驻地）: "
                        + resolved
                            .request()
                            .capital()
                            .map(anchor -> anchor.name())
                            .orElse("(无首都)")));
  }

  /**
   * {@code army} 摘要段（{@code dryRun} 时只是不落盘）：由编制与驻地算出，各 id 与 {@link #appendArmyCommands} 逐字一致。
   * {@code units} 表首行是根单位（{@code member = establishmentTotal}），随后每个兵种一行。
   */
  private static Map<String, Object> armySummary(
      String nationId, String displayName, ArmyPlan army, HexCoord at) {
    String rootId = rootUnitId(nationId);
    int total = establishmentTotal(army);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("nationId", nationId);
    view.put("peacetime", army.peacetime());
    view.put("mobilization", army.mobilization());
    view.put("establishmentTotal", total);
    view.put("armCount", army.establishment().size());
    view.put("rootUnitId", rootId);
    view.put("chainId", chainId(nationId));
    view.put("armyId", armyId(nationId));
    view.put("position", ToolSupport.hexCoord(at));
    List<Map<String, Object>> units = new ArrayList<>(1 + army.establishment().size());
    units.add(unitRow(rootId, displayName + ARMY_NAME_SUFFIX, total));
    for (Map.Entry<String, Integer> entry : army.establishment().entrySet()) {
      units.add(unitRow(armUnitId(nationId, entry.getKey()), entry.getKey(), entry.getValue()));
    }
    view.put("units", units);
    return view;
  }

  /** 编制单位行：{@code {id,name,member}}。 */
  private static Map<String, Object> unitRow(String id, String name, int member) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("name", name);
    row.put("member", member);
    return row;
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

  /**
   * 可选布尔：{@link Optional#empty()} = 参数**缺席**（与显式 {@code false} 明确区分——三个调用点都靠这个三态决定
   * "用配置缺省"还是"按给定值覆盖"）；接受布尔或 {@code "true"}/{@code "false"} 字符串。
   *
   * <p>★ 返回 {@code Optional} 而不是可空的 {@code Boolean}（SpotBugs NP_BOOLEAN_RETURN_NULL 的修法）：三态语义
   * 留在类型里，调用方漏处理"缺席"会编译不过，而不是在运行时把 {@code null} 当 {@code false} 用。
   */
  private static Optional<Boolean> optionalBoolean(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return Optional.empty();
    }
    if (value instanceof Boolean bool) {
      return Optional.of(bool);
    }
    if (value instanceof String text && !text.isBlank()) {
      if ("true".equalsIgnoreCase(text.trim())) {
        return Optional.of(true);
      }
      if ("false".equalsIgnoreCase(text.trim())) {
        return Optional.of(false);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是布尔值");
  }
}
