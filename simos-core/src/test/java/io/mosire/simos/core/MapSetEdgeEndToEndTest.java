package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.spi.RegisterPathwayGroupHandler;
import io.mosire.simos.map.spi.SetEdgeHandler;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ M8 T5 的**端到端**：真 {@code map.SetEdge} 经 {@link CoreSimos}（真 store、真 checkpoint、真 replay）后
 * **merge 不丢既有 tag**、replace 整份覆盖、重放逐值一致、两次逐字节相同，且负例**不留 revision**。
 */
class MapSetEdgeEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H01 = new HexCoord(0, 1);

  private static final String EDGE_LEFT = "0_0|1_0";
  private static final String EDGE_UP = "0_0|0_1";

  @TempDir Path tempDir;

  /**
   * ★ merge 不丢既有 tag：genesis 上 E_LEFT 已有 <b>river（width=2）与 road（width=3）</b>，merge road 到 E_UP 后
   * E_LEFT **逐值不变**。
   *
   * <p>★ 夹具**必须**让 E_LEFT 带上本次 merge 的**同一 kind**（road）——{@code replace} 是**逐 kind** 摘标注的，若既有 tag
   * 只在别的 kind 上（如仅 river），"merge 当 replace 使"在这份输入上**两种语义结果相同**，用例就成了装饰： 实测 变异体 m2（{@code
   * REPLACE.equals(operation) || MERGE.equals(operation)}）下本方法**仍全绿**，是 {@code EdgeOperationsTest}
   * 的两条同 kind / 未目标边用例把它杀掉的。
   */
  @Test
  void mergeSurvivesTheRealCommandPathAndKeepsTheExistingTag() {
    GameMap genesisMap = genesisMapWithLeftRiver();

    GameMap afterMap;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));

      CommandResult result = core.submit(setEdgeEnvelope(1, "road", EDGE_UP, "merge"));
      assertThat(result).as("真命令应提交到 (main,2)").isEqualTo(new CommandResult.Committed(ref(2)));

      afterMap = mapAt(core, 2);
    }

    assertThat(afterMap.edges().get(EdgeRef.parse(EDGE_LEFT)).byPathway())
        .as("★ merge 后既有同 kind（road）与别的 kind（river）都逐值不变")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("river", Map.of("width", 2), "road", Map.of("width", 3)));
    assertThat(afterMap.edges().get(EdgeRef.parse(EDGE_UP)).byPathway()).containsOnlyKeys("road");
    assertThat(afterMap.edges()).containsOnlyKeys(EdgeRef.parse(EDGE_LEFT), EdgeRef.parse(EDGE_UP));
  }

  /** replace 整份覆盖该 kind：E_LEFT 的 river 被摘掉、road 原样（故 E_LEFT 仍在 edges 里），E_UP 拿到 river。 */
  @Test
  void replaceOverwritesTheKindThroughTheRealCommandPath() {
    GameMap genesisMap = genesisMapWithLeftRiver();

    GameMap afterMap;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));

      CommandResult result = core.submit(setEdgeEnvelope(1, "river", EDGE_UP, "replace"));
      assertThat(result).isEqualTo(new CommandResult.Committed(ref(2)));

      afterMap = mapAt(core, 2);
    }

    assertThat(afterMap.edges())
        .as("E_LEFT 只剩 road（river 被整份覆盖摘掉），E_UP 拿到 river")
        .containsOnlyKeys(EdgeRef.parse(EDGE_LEFT), EdgeRef.parse(EDGE_UP));
    assertThat(afterMap.edges().get(EdgeRef.parse(EDGE_LEFT)).byPathway())
        .as("别的 kind 一字不动")
        .containsOnlyKeys("road");
    assertThat(afterMap.edges().get(EdgeRef.parse(EDGE_UP)).byPathway()).containsOnlyKeys("river");
  }

  /** 两次独立运行、同一命令 ⇒ {@code edges} 组件逐字节相同。 */
  @Test
  void sameCommandIsByteIdenticalAcrossTwoRuns(@TempDir Path otherDir) {
    GameMap genesisMap = genesisMapWithLeftRiver();

    GameMap first;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(setEdgeEnvelope(1, "road", EDGE_UP, "merge"));
      first = mapAt(core, 2);
    }
    GameMap second;
    try (CoreSimos core = openCore(otherDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(setEdgeEnvelope(1, "road", EDGE_UP, "merge"));
      second = mapAt(core, 2);
    }

    assertThat(second.edges().toString())
        .as("两次独立运行的 edges toString 逐字节相同")
        .isEqualTo(first.edges().toString());
  }

  @Test
  void negativePayloadsAreRejectedAndLeaveNoRevision() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMapWithLeftRiver()));
      long revisionsBefore = core.revisions(MAIN).size();

      // ★ 缺 mode：整条信封的 type 已注册，但载荷缺字段 ⇒ Rejected。
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          new CommandEnvelope(
              "cmd-missing-mode",
              "corr-missing-mode",
              "player:test",
              MAIN,
              new RevisionId(1),
              "map.SetEdge",
              "{\"kind\":\"river\",\"edges\":[\"" + EDGE_UP + "\"]}"),
          "字段 mode 必须是字符串");
      assertRejectedNoRevision(
          core, revisionsBefore, setEdgeEnvelope(1, "sea", EDGE_UP, "merge"), "未知连通性类型: sea");
      assertRejectedNoRevision(
          core, revisionsBefore, setEdgeEnvelope(1, "river", "0_0|9_9", "merge"), "边的端点不在图上");
    }
  }

  /**
   * ★★ C34 的**端到端**（真 store / 真 replay）：未注册的 {@code canal} 被拒且**不留 revision**；经真 {@code
   * map.RegisterPathwayGroup} 注册后同一条 {@code map.SetEdge{kind:"canal"}} **成功落 revision**；默认两组不受影响。
   */
  @Test
  void registeredCustomGroupEnablesTheCommandWhileUnregisteredKindStaysRejected() {
    GameMap afterMap;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMapWithLeftRiver()));

      assertRejectedNoRevision(
          core, 1, setEdgeEnvelope(1, "canal", EDGE_UP, "merge"), "未知连通性类型: canal");

      assertThat(core.submit(registerEnvelope(1, "canal")))
          .as("注册 canal 应落 (main,2)")
          .isEqualTo(new CommandResult.Committed(ref(2)));
      assertThat(core.submit(setEdgeEnvelope(2, "canal", EDGE_UP, "merge")))
          .as("注册后 SetEdge{kind:canal} 应落 (main,3)")
          .isEqualTo(new CommandResult.Committed(ref(3)));

      afterMap = mapAt(core, 3);
    }

    assertThat(afterMap.edges().get(EdgeRef.parse(EDGE_UP)).byPathway()).containsOnlyKeys("canal");
    assertThat(afterMap.pathwayGroups()).containsKey("canal");
    assertThat(afterMap.pathwayGroups().keySet()).as("默认两组不受影响").contains("river", "road");
  }

  // ── 助手 ────────────────────────────────────────────────────────────────────

  private static void assertRejectedNoRevision(
      CoreSimos core, long revisionsBefore, CommandEnvelope envelope, String reasonFragment) {
    CommandResult result = core.submit(envelope);
    assertThat(result)
        .as("必须被拒: %s", envelope.payloadJson())
        .isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains(reasonFragment);
    assertThat(core.revisions(MAIN)).as("被拒的命令不得多留一行 revision").hasSize((int) revisionsBefore);
    assertThat(core.head(MAIN).orElseThrow()).as("被拒的命令不动 head").isEqualTo(new RevisionId(1));
  }

  private static CoreSimos openCore(Path dir) {
    return new CoreSimos(new CoreConfig(dir, 100, MAPPER))
        .register(new MapCodec())
        .register(new SetEdgeHandler())
        .register(new RegisterPathwayGroupHandler());
  }

  /** ★ C34 的端到端：注册 canal 的命令信封（真提交路径）。 */
  private static CommandEnvelope registerEnvelope(long expectedRevision, String id) {
    String payload = "{\"id\":\"" + id + "\",\"name\":\"运河\",\"color\":\"#3A7BD5\"}";
    return new CommandEnvelope(
        "cmd-register-" + id,
        "corr-register-" + id,
        "player:test",
        MAIN,
        new RevisionId(expectedRevision),
        "map.RegisterPathwayGroup",
        payload);
  }

  private static CommandEnvelope setEdgeEnvelope(
      long expectedRevision, String kind, String edge, String mode) {
    String payload =
        "{\"kind\":\"" + kind + "\",\"edges\":[\"" + edge + "\"],\"mode\":\"" + mode + "\"}";
    return new CommandEnvelope(
        "cmd-" + kind + "-" + mode,
        "corr-" + kind + "-" + mode,
        "player:test",
        MAIN,
        new RevisionId(expectedRevision),
        "map.SetEdge",
        payload);
  }

  private static GameMap mapAt(CoreSimos core, long revision) {
    return ((MapSnapshot) core.replay(ref(revision)).module("map").orElseThrow()).map();
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  private static SimulationState genesis(GameMap map) {
    return new SimulationState(
        new StateMeta(ref(1), T0),
        Map.of("map", new MapSnapshot(ref(1), T0, map)),
        InMemoryInfoSystem.empty());
  }

  private static GameMap genesisMapWithLeftRiver() {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H00, new HexCell(0.3));
    cells.put(H10, new HexCell(0.6));
    cells.put(H01, new HexCell(0.7));
    Map<EdgeRef, EdgeTags> edges =
        Map.of(
            EdgeRef.parse(EDGE_LEFT),
            new EdgeTags(Map.of("river", Map.of("width", 2), "road", Map.of("width", 3))));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        PathwayGroup.defaults(),
        edges,
        GenerationSpec.defaults(0L));
  }
}
