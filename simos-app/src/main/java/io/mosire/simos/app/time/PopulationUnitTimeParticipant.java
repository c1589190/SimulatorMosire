package io.mosire.simos.app.time;

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

  private final PopulationEconomyTimeParticipant population;
  private final UnitTimeParticipant unit;

  /** 旧调用点（Shell 缺省）兼容：经济 worker 数取缺省单线程退化路径。 */
  public PopulationUnitTimeParticipant(String mapId) {
    this(mapId, 1);
  }

  /** 完整入口：{@code economyWorkerCount} 转交人口—经济参与者（≥ 1）。 */
  public PopulationUnitTimeParticipant(String mapId, int economyWorkerCount) {
    String id = Objects.requireNonNull(mapId, "mapId");
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
    // ① 人口—经济（含 Z7d-2 逃亡驱动/执行；摘除请求留在 outbox）。
    WorldTimeProposal populationProposal = population.simulateWorld(state, range);
    // ② unit 移动/回归（既有逻辑，原样调用）。
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
    for (GovernmentServiceDesertionBridge.Eviction eviction : evictions) {
      String address = unitAddress(eviction.gov());
      reads.add(address);
      writes.add(address);
    }
    return new WorldTimeProposal(POPULATION, mergedChanges, reads, writes);
  }

  /** {@code unit:<id>} 的 canonical 地址（与 {@code UnitTimeParticipant} 的同一 AST 构造法）。 */
  private static String unitAddress(UnitId id) {
    return new Address(List.of(new Namespace("unit"), Entity.of(id.value()))).canonical();
  }
}
