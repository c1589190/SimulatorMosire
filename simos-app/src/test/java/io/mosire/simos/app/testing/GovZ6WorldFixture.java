package io.mosire.simos.app.testing;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.GovGenesisSeedAccess;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.gov.GovEfficiencyModifier;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>Z6a 创世/长跑共享夹具</b>：真 {@link Shell} + {@code worldId=small-world} + 真 {@link
 * ShellMain#seedGenesisIfEmpty} + 真 {@code bootstrapGenesis}；推进走真 {@link
 * Shell#advanceAndDrain}（Core 的 真 {@code AdvanceTime} 管线，不是绕过 core 直接调参与者）。
 *
 * <p>形态照 Z5 探针（{@code /tmp/z5-probe}，85 checks 全绿）——它验证了"空库 → 19 hex 小世界 → 逐日推进"这条链在真 core
 * 上可跑；本夹具只把它变成可复用的测试入口，不复制任何业务断言。
 *
 * <p>★ 创世走真 {@code ShellMain.seedGenesisIfEmpty}（经测试包内唯一转调 {@link
 * io.mosire.simos.app.GovGenesisSeedAccess}）；推进走真 {@code Shell#advanceAndDrain}。
 *
 * <p>★ 每个 {@link World} 必须 close（关掉 shell 的端口/审批线程）；同一测试类里可以起多座独立空库（确定性用例需要）。
 */
public final class GovZ6WorldFixture {

  public static final BranchId MAIN = new BranchId("main");

  private GovZ6WorldFixture() {}

  /** 一座真壳小世界：创世已落在 {@code (main, 1)}，可直接读/推。 */
  public static final class World implements AutoCloseable {

    private final Shell shell;
    private final String label;
    private long advanceSeq;

    private World(Shell shell, String label) {
      this.shell = shell;
      this.label = label;
    }

    public Shell shell() {
      return shell;
    }

    public CoreSimos core() {
      return shell.coreSimos();
    }

    public long head() {
      return core().head(MAIN).orElseThrow().value();
    }

    /** 当前 head 的完整状态（真 replay，不是内存引用）。 */
    public SimulationState state() {
      return stateAt(head());
    }

    public SimulationState stateAt(long revision) {
      return core().replay(ref(revision));
    }

    /** 从 {@code fromTick} 推进到 {@code toTick}（含），返回推进后的 head。 */
    public long advance(long fromTick, long toTick) {
      long head = head();
      shell.advanceAndDrain(
          new AdvanceTime(
              "cmd-" + label + "-" + (advanceSeq++),
              "corr-" + label + "-" + advanceSeq,
              label,
              MAIN,
              new RevisionId(head),
              new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)))));
      return head();
    }

    /** 逐日推进 {@code days} 天（tick 0 → tick days），返回最后一天的 tick。 */
    public long advanceDays(long days) {
      long tick = state().meta().timestamp().tick();
      for (long day = 0L; day < days; day++) {
        advance(tick, tick + 1L);
        tick++;
      }
      return tick;
    }

    @Override
    public void close() {
      shell.close();
    }
  }

  /**
   * 空 store → 真创世链（{@link ShellConfig#withWorldId} = {@code small-world} + {@link
   * ShellMain#seedGenesisIfEmpty}）。
   *
   * @param storeDir 该世界的独立存储目录（调用方保证不同 label 不同目录）
   * @param label 用例标签（只进 initiator/命令 id）
   */
  public static World smallWorldGenesis(Path storeDir, String label) {
    ShellConfig config =
        ShellConfig.defaults(storeDir).withPorts(0, 0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
    Shell shell = Shell.start(config);
    try {
      boolean seeded = GovGenesisSeedAccess.seed(shell);
      if (!seeded) {
        shell.close();
        throw new AssertionError("空库创世失败：seedGenesisIfEmpty 返回 false（label=" + label + "）");
      }
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
    return new World(shell, label);
  }

  /** 小世界创世状态（不经落盘）：确定性用例对照真落盘世界用。 */
  public static SimulationState smallWorldState(String mapId) {
    return SmallWorld.state(mapId);
  }

  public static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  // ── 状态级推进器（不落盘；测试要注入动态修正/扭曲切片时用）────────────────────────────

  /**
   * 直接对一份 {@link SimulationState} 跑真 {@link PopulationEconomyTimeParticipant} 并把四片变更集应用回状态。
   *
   * <p>★ 与 {@link World#advance} 的差别只有一个：这里不落 core revision，便于"改一片状态再推进"的判别力用例；推进本身仍是同一条 参与者/变更集路径。
   */
  public static final class StateRunner {

    private final PopulationEconomyTimeParticipant participant;
    private final StateRef ref;
    private SimulationState state;

    public StateRunner(String mapId, SimulationState initial) {
      this.participant = new PopulationEconomyTimeParticipant(mapId);
      this.ref = ref(initial.meta().ref().revision().value());
      this.state = initial;
    }

    public SimulationState state() {
      return state;
    }

    public PopulationEconomyTimeParticipant participant() {
      return participant;
    }

    public void inject(List<GovEfficiencyModifier> modifiers) {
      participant.updateGovEfficiencyModifiers(modifiers);
    }

    /** 从 {@code fromTick} 推进到 {@code toTick}（含）并把四片写回。 */
    public void advance(long fromTick, long toTick) {
      WorldTimeProposal proposal =
          participant.simulateWorld(
              state,
              new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick))));
      io.mosire.simos.util.time.SimosTimestamp at = SimosTimestamp.of(toTick);
      Map<String, Snapshot> modules = new java.util.LinkedHashMap<>(state.modules());
      for (Map.Entry<String, io.mosire.simos.util.state.ChangeSet> entry :
          proposal.moduleChanges().entrySet()) {
        Snapshot base = modules.get(entry.getKey());
        Snapshot next = applyChangeSet(entry.getKey(), entry.getValue(), base, at);
        if (next != null) {
          modules.put(entry.getKey(), next);
        }
      }
      state = new SimulationState(new StateMeta(ref, at), modules, state.info());
    }
  }

  /** 按模块名施加变更集（类型不匹配 = 装配故障，当场抛）。 */
  private static Snapshot applyChangeSet(
      String namespace,
      io.mosire.simos.util.state.ChangeSet changeSet,
      Snapshot base,
      SimosTimestamp at) {
    return switch (namespace) {
      case "economy" ->
          new EconomySnapshot(
              ref(base),
              at,
              io.mosire.simos.economy.change.EconomyChangeSet.apply(
                  (io.mosire.simos.economy.change.EconomyChangeSet) changeSet,
                  ((EconomySnapshot) base).data()));
      case "social" ->
          new SocialSnapshot(
              ref(base),
              at,
              io.mosire.simos.social.change.SocialChangeSet.apply(
                  (io.mosire.simos.social.change.SocialChangeSet) changeSet,
                  ((SocialSnapshot) base).data()));
      case "actor" ->
          new ActorSnapshot(
              ref(base),
              at,
              io.mosire.simos.actor.change.ActorChangeSet.apply(
                  (io.mosire.simos.actor.change.ActorChangeSet) changeSet,
                  ((ActorSnapshot) base).data()));
      case "gov" ->
          new GovSnapshot(
              ref(base),
              at,
              io.mosire.simos.gov.change.GovChangeSet.apply(
                  (io.mosire.simos.gov.change.GovChangeSet) changeSet,
                  ((GovSnapshot) base).state()));
      default -> null;
    };
  }

  private static StateRef ref(Snapshot snapshot) {
    return snapshot.ref();
  }

  /** 把某个命名空间换成新快照（其余切片逐字保留）。 */
  public static SimulationState withModule(
      SimulationState state, String namespace, Snapshot snapshot) {
    Map<String, Snapshot> modules = new java.util.LinkedHashMap<>(state.modules());
    modules.put(namespace, snapshot);
    return new SimulationState(state.meta(), modules, state.info());
  }

  // ── 切片读口（真 snapshot 取数）──────────────────────────────────────────────────────

  public static UnitState unitSlice(SimulationState state) {
    return ((UnitSnapshot) state.module("unit").orElseThrow()).state();
  }

  public static SocialData socialSlice(SimulationState state) {
    return ((SocialSnapshot) state.module("social").orElseThrow()).data();
  }

  public static EconomyData economySlice(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  public static GovState govSlice(SimulationState state) {
    return ((GovSnapshot) state.module("gov").orElseThrow()).state();
  }

  public static ActorData actorSlice(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }
}
