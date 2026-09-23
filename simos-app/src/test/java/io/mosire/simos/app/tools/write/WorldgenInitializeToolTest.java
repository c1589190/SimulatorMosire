package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.world.RichWorld;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.gen.WorldgenConfig;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.worldgen.initialize} 的端到端验收（2026-09-23）：**真世界**（{@link RichWorld} 的 v17levant 真档）+
 * **真引擎**（{@code CoreSimos.bootstrapGenesis} / {@code submitBatch} / {@code replay}）跑一次奥斯特马克侯国的竖切。
 *
 * <p>★ 断言值都是**当场从冻结输入算过的字面量**（{@code total=3,070,000}、{@code urbanization=0.08} ⇒ 城市 245,600 / 农村
 * 2,824,400；真档 region 138 格且无 ocean ⇒ 逐格都有农村人口），不是"再调一遍生成器对拍"。
 */
class WorldgenInitializeToolTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final RevisionId R1 = new RevisionId(1);
  private static final String MAP_ID = "Map1";
  private static final String OSTERMARK = "奥斯特马克侯国";
  private static final String INITIATOR = "agent:worldgen-test";

  private static final long OSTERMARK_TOTAL = 3_070_000L;
  private static final long OSTERMARK_RURAL = 2_824_400L;
  private static final long OSTERMARK_URBAN = 245_600L;
  private static final int OSTERMARK_HEXES = 138;

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  @TempDir Path tempDir;

  // ── 1. dryRun 不写 ────────────────────────────────────────────────────────────────────

  @Test
  void dryRunComputesButWritesNothing() throws IOException {
    try (CoreSimos core = freshCore(dir("dry"))) {
      ToolResult result = execute(tool(core), Map.of("nation", OSTERMARK, "dryRun", true));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode body = JSON.readTree(result.message());
      assertThat(body.get("ruralPopulation").asLong()).isEqualTo(OSTERMARK_RURAL);
      assertThat(body.get("hexCount").asInt()).isEqualTo(OSTERMARK_HEXES);
      assertThat(body.has("revision")).as("dryRun 的摘要不带 revision").isFalse();

      assertThat(core.head(MAIN).orElseThrow().value()).as("dryRun 不得推进 head").isEqualTo(1L);
      SocialData social = socialAt(core, 1);
      assertThat(social.populations()).as("dryRun 不得写人口").isEmpty();
      assertThat(social.cities()).as("dryRun 不得建城").isEmpty();
    }
  }

  // ── 2. ★ 竖切真跑（核心用例）────────────────────────────────────────────────────────────

  @Test
  void ostermarkVerticalSliceLandsExactlyOneRevision() throws IOException {
    Path store = dir("vertical");
    SimulationState state;
    try (CoreSimos core = freshCore(store)) {
      ToolResult result = execute(tool(core), Map.of("nation", OSTERMARK, "dryRun", false));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode body = JSON.readTree(result.message());
      System.out.println("[WORLDGEN-VERTICAL] " + result.message());
      assertThat(body.get("revision").asLong()).as("恰好多出一条 revision").isEqualTo(2L);
      assertThat(body.get("branch").asText()).isEqualTo("main");
      assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(2L);

      state = core.replay(new StateRef(MAIN, new RevisionId(2)));
      SocialData social = socialSlice(state);
      SimosTimestamp at = state.meta().timestamp();

      long rural = social.populations().values().stream().mapToLong(s -> s.valueAt(at)).sum();
      long urban = social.cities().values().stream().mapToLong(SocialCity::population).sum();
      assertThat(social.populations()).as("region 全部格都有农村人口（无 ocean 格）").hasSize(OSTERMARK_HEXES);
      assertThat(rural).as("农村合计").isEqualTo(OSTERMARK_RURAL);
      assertThat(urban).as("Σ city.population").isEqualTo(OSTERMARK_URBAN);
      assertThat(rural + urban).as("总人口").isEqualTo(OSTERMARK_TOTAL);
      assertThat(social.cities()).isNotEmpty();

      SocialCity capital =
          social.cities().values().stream()
              .filter(city -> "马尔克堡".equals(city.name()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("没有叫 马尔克堡 的首都"));
      SocialCity largest =
          social.cities().values().stream()
              .max(Comparator.comparingLong(SocialCity::population))
              .orElseThrow();
      assertThat(capital.id()).as("首都必须是人口最大的那座").isEqualTo(largest.id());
      assertThat(capital.region()).contains(new RegionId(OSTERMARK));
      assertThat(capital.props())
          .as("城市节点挂了审计量（tier/catchmentHexes/localSurplus/…/seed）")
          .containsKeys(
              "tier",
              "catchmentHexes",
              "localSurplus",
              "tradeMultiplier",
              "politicalMultiplier",
              "justification",
              "seed");
      // ★ props 的值走 JSON 往返（payload → CreateCityHandler 的 convertValue）：整数落成 Integer（不是 Long）、
      //   浮点落成 Double —— 这正是"props 数值类型往返"要如实记录的一点。
      //   ★ 而 `seed` **故意走字符串**：它是 long，超过 int 的种子会被 Jackson 静默换类型甚至丢值
      //   （`SdInfoEntry.value` 的既定口径是"只保证标量往返"，long 不在那个保证里）。字符串逐字节往返。
      System.out.println(
          "[WORLDGEN-PROPS] seed="
              + capital.props().get("seed").getClass().getSimpleName()
              + " catchmentHexes="
              + capital.props().get("catchmentHexes").getClass().getSimpleName()
              + " localSurplus="
              + capital.props().get("localSurplus").getClass().getSimpleName()
              + " tier="
              + capital.props().get("tier").getClass().getSimpleName());
      assertThat(capital.props().get("seed")).as("seed 必须是字符串（见上）").isInstanceOf(String.class);
      assertThat(capital.props().get("seed")).isEqualTo("14441");
      assertThat(capital.props().get("catchmentHexes")).isInstanceOf(Integer.class);
      assertThat(capital.props().get("localSurplus")).isInstanceOf(Double.class);
      assertThat(capital.props().get("tier")).isInstanceOf(String.class);

      // 命令条数：1 条 SetPopulation + N 条 CreateCity。
      assertThat(body.get("commandCount").asInt())
          .as("1 + N（N = 城市数）")
          .isEqualTo(1 + social.cities().size());
    }

    // ★ 重放一致：换一个**新引擎**（只装 codec）从同一个库重放 (main,2)，逐字段等于首次重放——真往返，不是同一次
    //   调用的重复。
    try (CoreSimos reopened = codecOnly(store)) {
      assertThat(reopened.replay(new StateRef(MAIN, new RevisionId(2))))
          .as("从落盘重放 → 同样状态")
          .isEqualTo(state);
    }
  }

  // ── 3. ★ 同 seed 可复现：两个干净库的命令 payload 逐字节相同 ─────────────────────────────

  @Test
  void sameSeedProducesByteIdenticalCommandPayloadsAcrossCleanLibraries() throws IOException {
    try (CoreSimos coreA = freshCore(dir("repro-a"));
        CoreSimos coreB = freshCore(dir("repro-b"))) {
      List<CommandEnvelope> batchA = commandBatchFor(coreA);
      List<CommandEnvelope> batchB = commandBatchFor(coreB);

      assertThat(batchA).as("批大小相同").hasSameSizeAs(batchB);
      for (int i = 0; i < batchA.size(); i++) {
        assertThat(batchA.get(i).type()).as("第 %d 条类型相同", i).isEqualTo(batchB.get(i).type());
        assertThat(batchA.get(i).payloadJson())
            .as("第 %d 条 payload 必须逐字节相同（抓 Map 迭代序混进 payload）", i)
            .isEqualTo(batchB.get(i).payloadJson());
      }
    }
  }

  // ── 4. 随机化开关生效 ────────────────────────────────────────────────────────────────

  @Test
  void randomizeWithTwoSeedsChangesTheTotalWithinTheConfiguredBand() throws IOException {
    try (CoreSimos core = freshCore(dir("randomize"))) {
      JsonNode first =
          body(execute(tool(core), Map.of("nation", OSTERMARK, "randomize", true, "seed", 11L)));
      JsonNode second =
          body(
              execute(
                  tool(core), Map.of("nation", OSTERMARK, "randomize", true, "seed", 2_000_011L)));

      long totalFirst = first.get("totalPopulation").asLong();
      long totalSecond = second.get("totalPopulation").asLong();
      assertThat(first.get("randomize").asBoolean()).as("randomize 真的生效了").isTrue();
      assertThat(totalSecond).as("换 seed ⇒ 变体不同").isNotEqualTo(totalFirst);
      // population 的 jitterPct=0.03（配置）：3,070,000 × (1 ± 0.03)。
      assertThat(totalFirst).isBetween(2_977_900L, 3_162_100L);
      assertThat(totalSecond).isBetween(2_977_900L, 3_162_100L);
      assertThat(core.head(MAIN).orElseThrow().value()).as("dryRun 默认 ⇒ 不写").isEqualTo(1L);
    }
  }

  // ── 5. fail-closed ──────────────────────────────────────────────────────────────────

  @Test
  void unknownNationFailsClosedAndLeavesNoRevision() throws IOException {
    try (CoreSimos core = freshCore(dir("unknown-nation"))) {
      ToolResult result = execute(tool(core), Map.of("nation", "不存在的国", "dryRun", false));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(1L);
    }
  }

  @Test
  void missingBranchFailsClosedAndLeavesNoRevision() throws IOException {
    try (CoreSimos core = freshCore(dir("missing-branch"))) {
      ToolResult result =
          execute(tool(core), Map.of("nation", OSTERMARK, "branch", "不存在的分支", "dryRun", false));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(core.head(MAIN).orElseThrow().value()).as("不产生 revision").isEqualTo(1L);
    }
  }

  @Test
  void missingConfigFileFailsClosed() throws IOException {
    try (CoreSimos core = freshCore(dir("missing-config"))) {
      AgentTool tool =
          new WorldgenInitializeTool(core, INITIATOR, Path.of("没有这个文件", "nations.json"));
      ToolResult result = execute(tool, Map.of("nation", OSTERMARK, "dryRun", false));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).as("给可读原因（含路径）").contains("世界生成参数文件不存在");
      assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(1L);
    }
  }

  // ────────────────────────────────── 夹具 ──────────────────────────────────────────────

  /** 真世界 + 真引擎：四 codec + social 两条 handler + v17levant 创世（{@code (main,1)}）。 */
  private static CoreSimos freshCore(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (var codec : List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())) {
      core.register(codec);
    }
    core.register(new SetPopulationHandler());
    core.register(new CreateCityHandler());
    core.bootstrapGenesis(RichWorld.state(MAP_ID));
    return core;
  }

  /** 只装 codec 的新引擎（重放不需要 handler）：用来验"从落盘重放 == 首次重放"。 */
  private static CoreSimos codecOnly(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (var codec : List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())) {
      core.register(codec);
    }
    return core;
  }

  private static AgentTool tool(CoreSimos core) {
    return new WorldgenInitializeTool(core, INITIATOR, configFile());
  }

  private static ToolResult execute(AgentTool tool, Map<String, Object> args) {
    return tool.execute(context(tool, args));
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }

  private static JsonNode body(ToolResult result) throws IOException {
    assertThat(result.success()).as(result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  /**
   * 用**真生成路径**（配置 + 真档区域格集 + 生成器）产出一次计划，再问工具的 {@code buildBatch} 要命令批（用例逐字节对拍 payload）。★
   * 第二次调用是**另一个干净库**，故这条路本身也覆盖"跨库可复现"。
   */
  private static List<CommandEnvelope> commandBatchFor(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    GameMap map = ((MapSnapshot) state.module("map").orElseThrow()).map();
    Region region = map.regions().get(new RegionId(OSTERMARK));
    ResolvedNation resolved =
        WorldgenConfig.load(configFile()).byRegionId(OSTERMARK).withHexes(region.hexes()).resolve();
    SettlementPlan plan =
        SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
    return WorldgenInitializeTool.buildBatch(
        "batch-test", INITIATOR, MAIN, R1, region.id(), plan, resolved.request().seed());
  }

  private static SocialData socialAt(CoreSimos core, long revision) {
    return socialSlice(core.replay(new StateRef(MAIN, new RevisionId(revision))));
  }

  private static SocialData socialSlice(SimulationState state) {
    SocialSnapshot slice =
        (SocialSnapshot)
            state.module("social").orElseThrow(() -> new AssertionError("状态里没有 social 切片"));
    return slice.data();
  }

  /** 冻结输入的路径：surefire 工作目录是 {@code simos-app/} ⇒ 主树是 {@code ../config/...}。 */
  private static Path configFile() {
    for (Path candidate :
        List.of(
            Path.of("..", "config", "worldgen", "v17levant-nations.json"),
            Path.of("config", "worldgen", "v17levant-nations.json"))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到 config/worldgen/v17levant-nations.json（工作目录=" + Path.of(".").toAbsolutePath() + "）");
  }

  private Path dir(String name) throws IOException {
    return Files.createDirectories(tempDir.resolve(name));
  }
}
