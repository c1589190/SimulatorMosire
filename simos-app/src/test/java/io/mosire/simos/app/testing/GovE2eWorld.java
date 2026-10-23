package io.mosire.simos.app.testing;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.world.CompactThreeNationsWorld;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>P0 沉淀夹具（2026-10-15 测试面）：一座"8 片齐全（含 army）+ 已跑真 worldgen"的三国世界</b>。
 *
 * <p>它把四条仓库外探针（{@code OfficeProbe} / {@code CreateOfficeAcceptProbe} / {@code ProvinceProbe} /
 * {@code PlanOnlyProbe}）的创世段沉淀成仓内可复跑的夹具——<b>逐字照探针的 8 片创世</b>（{@code map/social/unit/ga/
 * economy/actor/gov/army}），这是探针能走到 {@code gov.createOffice} 而 {@code RealLlmGovScenarioTest} 走不到的
 * <b>唯一</b>差别：那条门控用例的 {@code seedGenesis()} 缺 {@code army} 片 ⇒ {@code ArmySnapshots} 抛「state 里没有
 * army 切片」⇒ 整条 e2e 在创世自检就断了。
 *
 * <p>★ <b>为什么必须带 army</b>：{@link CompactThreeNationsWorld#initializeNations} 的创世命令批会触到 army 切片；
 * 命令总线在切片缺席时<b>响亮失败</b>（不降级）。缺片 = 夹具坏，不是被测对象坏。
 *
 * <p>★ <b>本类不伪造任何生产语义</b>：创世走 {@code bootstrapGenesis} 之外的同一条"命令 → ChangeSet → Revision" 语义（与探针同法：先
 * append genesis revision、再写 checkpoint、起真 Shell），worldgen 走真 {@code initializeNations}（真命令批、真
 * revision）。
 */
public final class GovE2eWorld implements AutoCloseable {

  /** 本世界的分支（与 {@code bootstrapGenesis} 的创世分支同字面）。 */
  public static final BranchId MAIN = new BranchId("main");

  /** 创世 revision（探针同值：{@code (main, 1)}）。 */
  public static final long GENESIS_REVISION = 1L;

  /** 壳的 initiator（进 revision 行，便于定位是哪次测试写的）。 */
  public static final String INITIATOR = "test:gov-e2e";

  private final Shell shell;
  private final Path storeDir;

  private GovE2eWorld(Shell shell, Path storeDir) {
    this.shell = shell;
    this.storeDir = storeDir;
  }

  /**
   * 起一座全新世界：8 片创世 → 真 Shell → 真 worldgen（三国）。
   *
   * @param storeDir 该世界的独立存储目录（调用方保证不同用例不同目录）
   */
  public static GovE2eWorld start(Path storeDir) throws IOException {
    Files.createDirectories(storeDir);
    Path dbFile = storeDir.resolve(CoreSimos.DB_FILE_NAME);
    StateRef ref = new StateRef(MAIN, new RevisionId(GENESIS_REVISION));
    SimosTimestamp t0 = SimosTimestamp.of(0L);
    // ★★ 8 片全在场（含 army）—— 缺一片就是探针当年的坏夹具（命令总线响亮失败）。
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref, t0),
            Map.of(
                "map", new MapSnapshot(ref, t0, CompactThreeNationsWorld.map()),
                "social", new SocialSnapshot(ref, t0, new SocialData(Map.of(), Map.of(), Map.of())),
                "unit", new UnitSnapshot(ref, t0, UnitState.empty()),
                "ga", new GaSnapshot(ref, t0, GaState.empty()),
                "economy", new EconomySnapshot(ref, t0, EconomyData.empty()),
                "production", new ProductionSnapshot(ref, t0, ProductionData.empty()),
                "actor", new ActorSnapshot(ref, t0, ActorData.empty()),
                "gov", new GovSnapshot(ref, t0, GovState.empty()),
                "army", new ArmySnapshot(ref, t0, ArmyData.empty())),
            InMemoryInfoSystem.empty());
    try (SqliteStore seedStore = SqliteStore.open(dbFile)) {
      new Timeline(seedStore, 100)
          .appendRevision(
              new RevisionRow(
                  MAIN,
                  new RevisionId(GENESIS_REVISION),
                  Optional.empty(),
                  t0,
                  "cmd-genesis",
                  "corr-genesis",
                  INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    new CheckpointStore(storeDir)
        .write(
            ref,
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new GaCodec(),
                    new EconomyCodec(),
                    new io.mosire.simos.production.codec.ProductionCodec(),
                    new ActorCodec(),
                    new GovCodec(),
                    new ArmyCodec())));
    Shell shell =
        Shell.start(
            new ShellConfig(
                storeDir,
                100,
                0,
                0,
                "/mcp",
                INITIATOR,
                CompactThreeNationsWorld.MAP_ID,
                "127.0.0.1",
                false));
    try {
      CompactThreeNationsWorld.initializeNations(
          shell.coreSimos(), EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME);
      return new GovE2eWorld(shell, storeDir);
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
  }

  public Shell shell() {
    return shell;
  }

  public CoreSimos core() {
    return shell.coreSimos();
  }

  public Path storeDir() {
    return storeDir;
  }

  /** 当前 head revision。 */
  public long head() {
    return core().head(MAIN).orElseThrow().value();
  }

  /**
   * {@code revisions} 表的行数（★ 与 {@link #head()} 是<b>两个独立口径</b>：一个读 head 指针，一个数行）；一批 = 一条 revision
   * 的判据必须两个都成立。
   */
  public long revisionRows() {
    return core().revisions(MAIN).size();
  }

  /**
   * ★ <b>直接查库</b>数 {@code revisions} 表的行数（不看工具自报、也不经 CoreSimos 的读 API）—— 形制逐字照 {@code
   * CreateOfficeAcceptProbe.revisionRows}。
   */
  public long revisionRowsViaSql() throws java.sql.SQLException {
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + storeDir.resolve(CoreSimos.DB_FILE_NAME));
        java.sql.Statement statement = connection.createStatement();
        java.sql.ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** 当前 head 上的状态。 */
  public SimulationState state() {
    return stateAt(head());
  }

  public SimulationState stateAt(long revision) {
    return core().replay(new StateRef(MAIN, new RevisionId(revision)));
  }

  /** GM 面上的某一条工具（真装配的桶；找不到 ⇒ 抛，绝不静默跳过）。 */
  public AgentTool gmTool(String name) {
    return shell.toolsFor(SimosToolSource.Role.GM).stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("GM 面上必须有 " + name + "（装配面漂移？）"));
  }

  /** 系统上下文真调 GM 面一条工具（形制照四条仓库外探针）。 */
  public ToolResult callGm(String toolName, Map<String, Object> args) {
    return exec(gmTool(toolName), args);
  }

  /** 系统上下文（{@code AccessToken.SYSTEM} + 目标工具自己的 resources()）真调一条工具。 */
  public static ToolResult exec(AgentTool tool, Map<String, Object> args) {
    AgentPermissionSet permissions = AgentPermissionSet.system();
    ToolContext context =
        new ToolContext(AccessToken.SYSTEM, permissions, Map.of(), args, AgentIdentity.external())
            .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
    return tool.execute(context);
  }

  @Override
  public void close() {
    shell.close();
  }
}
