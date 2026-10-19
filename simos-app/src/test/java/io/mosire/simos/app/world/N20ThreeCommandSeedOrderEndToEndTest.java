package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.gen.NationSetup;
import io.mosire.simos.social.gen.ResolvedNation;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★★ <b>D-032 T8：N20「三命令端到端播种序」的独立载体</b>（设计书 §14.4 的 N20 行）。
 *
 * <p>★★ <b>判据</b>：{@code production.Seed} 必须先于 {@code economy.Seed} 落账 —— 生产定义/实例的唯一权威在
 * production，economy 载荷只是<b>引用</b>它。三条命令（{@code production.Seed} → {@code economy.Seed} → {@code
 * actor.Seed}）在<b>同一次 submitBatch</b> 里按正常序提交 ⇒ 世界建得起来（一条 revision、三片都非空）； <b>颠倒序</b>（{@code
 * economy.Seed} 打头）⇒ 具名拒（economy 的跨域校验读到 production 里不存在的实例），且**零 revision**。
 *
 * <p>★★ <b>为什么必须端到端</b>：单模块 handler 的单测看不见"同批前一条命令的候选态会不会被后一条读到"—— 旧稿 {@code
 * ProductionRuntimeSeedSmokeTest} 已证明单次 handler 调用是对的，但"批里的次序"只在真 {@code CoreSimos.submitBatch}
 * 的候选态链上成立。本用例是那条链的专用载体。
 *
 * <p>★ <b>载荷来源</b>：真 {@link EconomySeeder#plan} + 真 {@link HouseholdSeeder#payload}（与 {@code
 * WorldgenInitializeTool.buildBatch} 同一个 plan），不手写第二套载荷。
 */
class N20ThreeCommandSeedOrderEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final ObjectMapper JSON = SimosObjectMapper.create();

  @TempDir Path tempDir;

  @Test
  void productionSeedMustLandBeforeEconomySeedAndTheReversedOrderIsRejectedByName()
      throws Exception {
    EconomySeeder.Seed seed = seed();

    // ★ N20 第三句：economy 载荷不依赖 industries[]/modes[]（定义唯一权威在 production）。
    JsonNode economyPayload = JSON.readTree(seed.economyPayload());
    assertThat(economyPayload.hasNonNull("industries"))
        .as("economy 载荷不得再带顶层 industries[]（B5 已退役）")
        .isFalse();
    // ★ 逐 entry 判：economy 载荷不得再需要 entry 级的 industries[]/modes[]（定义唯一权威在 production）。
    //   ★ 顶层的 modes[] 是 economy 侧的 P1/P2 目录（classStructures 的同批目录），不是"定义副本"；
    //     本判据针对的是 B5 退役掉的 entry 级定义键。
    assertThat(economyPayload.path("entries"))
        .as("economy 载荷必须有 entries（扫描面非空，否则下一条是空转）")
        .isNotEmpty();
    for (JsonNode entry : economyPayload.path("entries")) {
      assertThat(entry.hasNonNull("industries"))
          .as("economy 载荷的 entry 不得再带 industries[]（定义权威在 production.Seed）: %s", entry)
          .isFalse();
      assertThat(entry.hasNonNull("modes"))
          .as("economy 载荷的 entry 不得再带 modes[]（定义权威在 production.Seed）: %s", entry)
          .isFalse();
    }

    List<String> normalOrder =
        List.of(
            seed.productionPayload(),
            seed.economyPayload(),
            HouseholdSeeder.payload(
                CompactThreeNationsWorld.MAP_ID,
                seed.householdLocations(),
                seed.householdStocks(),
                seed.householdMoney(),
                seed.operators()));
    List<String> reversedOrder =
        List.of(normalOrder.get(1), normalOrder.get(0), normalOrder.get(2));

    ShellConfig config = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
    try (Shell shell = Shell.start(config)) {
      shell.coreSimos().bootstrapGenesis(genesisState());
      // ── ① 颠倒序（economy.Seed 打头）：具名拒 + 零 revision ──────────────────────────
      BatchResult rejected =
          shell
              .coreSimos()
              .submitBatch(
                  List.of(
                      envelope(1L, "economy.Seed", reversedOrder.get(0)),
                      envelope(1L, "production.Seed", reversedOrder.get(1)),
                      envelope(1L, "actor.Seed", reversedOrder.get(2))));

      assertThat(rejected)
          .as("★ economy.Seed 先跑 ⇒ 整批 Rejected（它引用的 production 实例还不存在）")
          .isInstanceOf(BatchResult.Rejected.class);
      List<CommandOutcome> outcomes = ((BatchResult.Rejected) rejected).outcomes();
      assertThat(outcomes).as("逐条结局齐全（一批三条）").hasSize(3);
      assertThat(outcomes.get(0).rejected()).as("★ 第 1 条（economy.Seed）就是被拒的那条").isTrue();
      String reason = ((CommandResult.Rejected) outcomes.get(0).result()).reason();
      // ★★ D-032 T1：economy.Seed 的拒绝点已随"名册/实例唯一权威在 production"上移 —— 现在是名册指名的
      //   生产实例在 production 片里不存在（`roster-instance-unknown`），仍具名到 production 权威，
      //   不是一句通用话。旧断言钉的是更早一版的 `production 当刻定义权威` 文案。
      assertThat(reason)
          .as("★★ 拒因具名到 production 当刻权威（不是一句通用话）：economy.Seed 引用的生产实例/方案在生产片里不存在")
          .contains("roster-instance-unknown")
          .contains("production")
          .contains("unit-farm@");
      assertThat(outcomes.get(1).committed())
          .as("★ 第 2 条（production.Seed）合法也不落 —— 整批零部分提交")
          .isFalse();
      assertThat(outcomes.get(2).committed()).as("第 3 条（actor.Seed）合法也不落").isFalse();
      assertThat(shell.coreSimos().head(MAIN))
          .as("★★ 颠倒序 ⇒ 零 revision（创世那一行仍是 head）")
          .contains(new RevisionId(1));

      // ── ② 正常序：三命令同批 ⇒ 一条 revision，三片都真的落 ──────────────────────────
      BatchResult committed =
          shell
              .coreSimos()
              .submitBatch(
                  List.of(
                      envelope(1L, "production.Seed", normalOrder.get(0)),
                      envelope(1L, "economy.Seed", normalOrder.get(1)),
                      envelope(1L, "actor.Seed", normalOrder.get(2))));

      assertThat(committed)
          .as("正常序必须提交成功: %s", committed)
          .isInstanceOf(BatchResult.Committed.class);
      assertThat(shell.coreSimos().head(MAIN))
          .as("三命令同批 = 恰一条 revision")
          .contains(new RevisionId(2));
      SimulationState world =
          shell
              .coreSimos()
              .replay(new io.mosire.simos.util.state.StateRef(MAIN, new RevisionId(2)));

      Snapshot productionSlice = world.module("production").orElseThrow();
      Snapshot economySlice = world.module("economy").orElseThrow();
      Snapshot actorSlice = world.module("actor").orElseThrow();
      assertThat(((ProductionSnapshot) productionSlice).state().industries())
          .as("production.Seed 真的落了定义")
          .isNotEmpty();
      assertThat(((ProductionSnapshot) productionSlice).state().instances())
          .as("production.Seed 真的落了实例（economy.Seed 的引用对象）")
          .isNotEmpty();
      assertThat(((EconomySnapshot) economySlice).data().classes())
          .as("economy.Seed 真的落了阶层行")
          .isNotEmpty();
      assertThat(((ActorSnapshot) actorSlice).data().accounts())
          .as("actor.Seed 真的落了家户账")
          .isNotEmpty();
    }
  }

  /**
   * 紧凑三国地图 + **空的经济/生产/actor 切片**的创世状态（{@code (main,1)}）：本用例要在它上面亲手提交三条播种命令， 故不能先跑 {@code
   * worldgen}（那会把命令序固定死、失去"颠倒序"的判别力）。
   */
  private static SimulationState genesisState() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0L);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(ref, at, CompactThreeNationsWorld.map()));
    modules.put("social", new SocialSnapshot(ref, at, SocialData.empty()));
    modules.put("unit", new UnitSnapshot(ref, at, UnitState.empty()));
    modules.put("ga", new GaSnapshot(ref, at, GaState.empty()));
    modules.put("economy", new EconomySnapshot(ref, at, EconomyData.empty()));
    modules.put("actor", new ActorSnapshot(ref, at, ActorData.empty()));
    modules.put("gov", new GovSnapshot(ref, at, GovState.empty()));
    GenesisModuleSlices.ensureEmptyProductionAndCulture(modules, ref, at);
    return new SimulationState(new StateMeta(ref, at), modules, InMemoryInfoSystem.empty());
  }

  /** 与 {@code WorldgenInitializeTool.buildBatch} 同一个 plan（紧凑三国的粮仓国）。 */
  private static EconomySeeder.Seed seed() {
    GameMap map = CompactThreeNationsWorld.map();
    Region region = map.regions().get(CompactThreeNationsWorld.GRANARY);
    NationSetup setup =
        CompactThreeNationsWorld.config().byRegionId(region.id().value()).withHexes(region.hexes());
    ResolvedNation resolved = setup.resolve();
    SettlementPlan plan =
        SettlementGenerator.generate(resolved.request(), TerrainView.of(map), resolved.params());
    PopulationSeeder.Seeding populations = PopulationSeeder.seed(plan, 0L);
    return EconomySeeder.plan(
        CompactThreeNationsWorld.MAP_ID,
        populations,
        map,
        EconomySeeder.genesisMoneyMilliPerCapita(),
        EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME,
        TestConditions.EMPTY);
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    return new CommandEnvelope(
        "cmd-n20-" + type,
        "corr-n20-" + expectedRevision,
        "gm:n20-test",
        MAIN,
        new RevisionId(expectedRevision),
        type,
        payload);
  }
}
