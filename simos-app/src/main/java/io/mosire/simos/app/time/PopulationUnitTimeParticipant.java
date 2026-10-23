package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.app.world.LandProductionCycle;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.TimeRange;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>Z7d-2：人口—经济参与者与 unit 参与者的组合形态</b>（app 组合根）。
 *
 * <p>★★ <b>为什么必须组合</b>：逃亡执行要求"Social 成员/位置 + Economy 人口/劳动/承诺 + Unit 岗位/容纳列表"落在 <b>同一条
 * revision</b>；但 {@code TimeProposalResolver} 的写-写判定对"两个参与者都交 unit 变更集"是<B>整日拒绝</B>
 * （模块键相同即冲突，与地址无关）——⇒ 只要 unit 侧还有一个独立参与者，任何写 unit 的逃亡都会与它冲突。组合参与者把 {@link UnitTimeParticipant} 与
 * {@link PopulationEconomyTimeParticipant} 合成<b>一份提案</b>：
 *
 * <ol>
 *   <li>先跑人口—经济参与者（namespace 字典序不变：{@code population}），它把逃亡摘除请求记进瞬态 outbox；
 *   <li>再跑 unit 参与者（移动/回归），并把它的 {@code unit} 变更集施加到 base 上得到"移动后"的 {@link UnitState}；
 *   <li>把逃亡摘除请求逐条施加到"移动后"的 unit 状态（{@link UnitOperations#evictGovernmentHousehold}）；
 *   <li>用 {@code UnitChangeSet.between(base, final)} 交回一份 unit 变更集（移动 + 逃亡摘除已合并、不会互相覆盖）。
 * </ol>
 *
 * <p>★ 两份提案的 reads/writes 取并集；参与者身份仍为 {@code population}。既有 unit 移动逻辑一字不改 （直接复用 {@link
 * UnitTimeParticipant#simulateWorld}）。
 */
public final class PopulationUnitTimeParticipant implements TimeParticipant {

  private static final String POPULATION = "population";
  private static final String UNIT = "unit";
  private static final String ACTOR = "actor";
  private static final String MAP = "map";
  private static final String ECONOMY = "economy";

  private final String mapId;
  private final PopulationEconomyTimeParticipant population;
  private final UnitTimeParticipant unit;

  /** 旧调用点（Shell 缺省）兼容：经济 worker 数取缺省单线程退化路径。 */
  public PopulationUnitTimeParticipant(String mapId) {
    this(mapId, 1);
  }

  /** 完整入口：{@code economyWorkerCount} 转交人口—经济参与者（≥ 1）。 */
  public PopulationUnitTimeParticipant(String mapId, int economyWorkerCount) {
    String id = Objects.requireNonNull(mapId, "mapId");
    this.mapId = id;
    this.population = new PopulationEconomyTimeParticipant(id, economyWorkerCount);
    this.unit = new UnitTimeParticipant(TerrainMovementCost.INSTANCE, id);
  }

  @Override
  public String namespace() {
    return POPULATION;
  }

  /** 读口/测试用：内部人口—经济参与者（单位移动由本类组合调用）。 */
  public PopulationEconomyTimeParticipant populationParticipant() {
    return population;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    // ★★ D-032 L2b2b：population 之前先跑一次 LAND continuation day。
    //   一次 Advance 只跑一个 land day；多 tick 逐日留给后续（本入口按 1 tick Advance）。
    SimulationState landState = LandProductionCycle.run(state, range.from().tick());
    // ① 人口—经济（含 Z7d-2 逃亡驱动/执行；摘除请求留在 outbox）。
    //   ★ population 看到 land 扣料/产出后的 actor、写回后的 map 与运输能力批次后的 economy。
    WorldTimeProposal populationProposal = population.simulateWorld(landState, range);
    // ② unit 移动/回归（既有逻辑，原样调用；仍基于原 state）。
    WorldTimeProposal unitProposal = unit.simulateWorld(state, range);

    UnitState baseUnits =
        ((UnitSnapshot)
                state
                    .module(UNIT)
                    .orElseThrow(() -> new IllegalStateException("组合参与者要求 state 里有 unit 切片")))
            .state();
    UnitState finalUnits = baseUnits;
    ChangeSet unitChangeSet = unitProposal.moduleChanges().get(UNIT);
    if (unitChangeSet instanceof UnitChangeSet unitChanges) {
      finalUnits = UnitChangeSet.apply(unitChanges, baseUnits);
    }
    // ③ 逃亡摘除请求（户空 ⇒ 摘岗位 + 摘 Unit.households）。
    List<GovernmentServiceDesertionBridge.Eviction> evictions = population.drainFlightEvictions();
    for (GovernmentServiceDesertionBridge.Eviction eviction : evictions) {
      finalUnits =
          UnitOperations.evictGovernmentHousehold(finalUnits, eviction.gov(), eviction.household());
    }
    UnitChangeSet mergedUnitChanges = UnitChangeSet.between(baseUnits, finalUnits);

    Map<String, ChangeSet> mergedChanges = new LinkedHashMap<>(populationProposal.moduleChanges());
    mergedChanges.put(UNIT, mergedUnitChanges);
    Set<String> reads = new LinkedHashSet<>(populationProposal.reads());
    reads.addAll(unitProposal.reads());
    Set<String> writes = new LinkedHashSet<>(populationProposal.writes());
    writes.addAll(unitProposal.writes());
    if (landState != state) {
      // ★★ land cycle 已改过 actor/map/economy：population 的对应变更集只是相对 land 的增量，
      //   必须重新从 base -> final 派生，才能在同一个 revision 里正确落账。
      mergeLandChangedSlices(state, landState, populationProposal, mergedChanges, writes);
    }
    for (GovernmentServiceDesertionBridge.Eviction eviction : evictions) {
      String address = unitAddress(eviction.gov());
      reads.add(address);
      writes.add(address);
    }
    return new WorldTimeProposal(POPULATION, mergedChanges, reads, writes);
  }

  /**
   * land cycle 改变 actor/map/economy 时，把 population 的对应增量应用到 land 切片，再从 base 到 final 重新派生变更集。
   *
   * <p>population 变更集若类型不是 {@link ActorChangeSet} / {@link MapChangeSet} / {@link EconomyChangeSet}
   * 或缺席，则保持 land 值（不伪造）。
   */
  private void mergeLandChangedSlices(
      SimulationState baseState,
      SimulationState landState,
      WorldTimeProposal populationProposal,
      Map<String, ChangeSet> mergedChanges,
      Set<String> writes) {
    ActorData baseActor = actorOf(baseState);
    ActorData landActor = actorOf(landState);
    ActorData finalActor = landActor;
    ChangeSet populationActorChanges = populationProposal.moduleChanges().get(ACTOR);
    if (populationActorChanges instanceof ActorChangeSet actorChanges) {
      finalActor = ActorChangeSet.apply(actorChanges, landActor);
    }

    GameMap baseMap = mapOf(baseState);
    GameMap landMap = mapOf(landState);
    GameMap finalMap = landMap;
    ChangeSet populationMapChanges = populationProposal.moduleChanges().get(MAP);
    if (populationMapChanges instanceof MapChangeSet mapChanges) {
      finalMap = MapChangeSet.apply(mapChanges, landMap);
    }

    EconomyData baseEconomy = economyOf(baseState);
    EconomyData landEconomy = economyOf(landState);
    EconomyData finalEconomy = landEconomy;
    ChangeSet populationEconomyChanges = populationProposal.moduleChanges().get(ECONOMY);
    if (populationEconomyChanges instanceof EconomyChangeSet economyChanges) {
      finalEconomy = EconomyChangeSet.apply(economyChanges, landEconomy);
    }

    mergedChanges.put(ACTOR, ActorChangeSet.between(baseActor, finalActor));
    mergedChanges.put(MAP, MapChangeSet.between(baseMap, finalMap));
    mergedChanges.put(ECONOMY, EconomyChangeSet.between(baseEconomy, finalEconomy));
    writes.add(actorAddressRoot());
    writes.add(mapAddressRoot());
    writes.add(economyAddressRoot());
  }

  /** {@code unit:<id>} 的 canonical 地址（与 {@code UnitTimeParticipant} 的同一 AST 构造法）。 */
  private static String unitAddress(UnitId id) {
    return new Address(List.of(new Namespace("unit"), Entity.of(id.value()))).canonical();
  }

  /** {@code actor:<mapId>} 根地址（形制照人口—经济参与者的 {@code actorAddressRoot}）。 */
  private String actorAddressRoot() {
    return new Address(List.of(new Namespace(ACTOR), Entity.of(mapId))).canonical();
  }

  /** {@code map:<mapId>} 根地址（形制照人口—经济参与者的 {@code mapAddressRoot}）。 */
  private String mapAddressRoot() {
    return new Address(List.of(new Namespace(MAP), Entity.of(mapId))).canonical();
  }

  /** {@code economy:<mapId>} 根地址（形制照人口—经济参与者的 {@code economyAddressRoot}）。 */
  private String economyAddressRoot() {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId))).canonical();
  }

  /** actor 切片只能从 actor 模块拿（铁律 3/4）；缺席或类型不对都是装配故障。 */
  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ACTOR)
            .orElseThrow(() -> new IllegalStateException("state 里没有 actor 切片（land cycle 装配故障）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
  }

  /** economy 切片只能从 economy 模块拿；缺席或类型不对都是 land 合并的装配故障。 */
  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ECONOMY)
            .orElseThrow(
                () -> new IllegalStateException("state 里没有 economy 切片（land cycle 合并装配故障）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  /** map 切片只能从 map 模块拿；缺席或类型不对都是装配故障。 */
  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(MAP)
            .orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（land cycle 装配故障）"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }
}
