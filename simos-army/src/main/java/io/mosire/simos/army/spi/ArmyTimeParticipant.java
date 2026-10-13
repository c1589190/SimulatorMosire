package io.mosire.simos.army.spi;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.Incident;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>Army 的时间参与者</b>（2026-10-13 急停协议 G3 + §4.4）：把"两军相遇"这条判据接进推进，命中即上报 {@link Incident}（{@link
 * ArmyContacts#KIND} = {@code army.contact}）。
 *
 * <p>★★ <b>为什么 Army 自己算位置，而不是消费 Unit 的提案</b>（约束设计书 §4.4，不是选型而是既有架构约束）：两阶段推进里 <b>每个参与者拿到的都是同一份
 * base</b>（C25，{@code TimeAdvance} 类注）——参与者之间<b>看不到彼此的提案</b>。故"当天的单位位置"只能自己算。 这也顺带回答了不变量
 * I6：<b>不许</b>依赖"population 先跑"（参与者按 {@code namespace()} 字典序排，实际序是 {@code army} → {@code ga} →
 * {@code population}，Army 排在最后看到的东西一样是 base）。
 *
 * <p>★★ <b>必须逐日算</b>：只在 {@code range.to()} 算一次会<b>漏掉两军擦肩而过</b>（第 5 天相遇、第 10 天分开 ⇒ 终点看过去"从没相遇"）。第 5
 * 天命中 ⇒ 报的就是第 5 天（{@link Incident#tick()} 必须是<b>判据命中的那一天</b>，core 侧机械核对，不符即 {@code Rejected}）。
 *
 * <p>★ <b>位置怎么来</b>：军的位置 = 该军 {@link Army#rootUnit()} 当天的位置（{@code ArmyData} 三张表里<b>没有位置字段，也不加</b>
 * ——位置属于 unit 的时态序列，铁律 3）。取法是<b>纯读</b>：
 *
 * <ul>
 *   <li>该 rootUnit 在 {@code at} 时刻的<b>编制顶层</b>若在途（{@code movement()} 存在且 {@code at ≥ departedAt}）⇒
 *       {@code UnitMoves.evaluate(顶层, at, map, cost)}：那是<b>纯函数、第二参就是任意时刻</b>（{@code
 *       UnitMoves.java:49-50}）， 所以"任意一天的军在哪"可以现问，不用给 Unit 加日循环、也不用任何跨参与者通道；
 *   <li>否则（静止 / 查询时刻早于出发时刻）⇒ {@code UnitState.effectivePosition(rootUnit, at)}（位置时态序列的现读值，无则向父取 +
 *       叠偏移，{@code UnitState.java:98}）。
 * </ul>
 *
 * <p>★ <b>提案形状</b>：本参与者<b>不写</b> army 切片（判据是观测，不是世界事实）⇒ 交一份<b>不变变更集</b> （{@code moduleChanges}
 * 不得为空，见 {@code WorldTimeProposal} 的构造判据），把当天的突发事件挂在 {@code incidents} 上。 走的是单切片形态 + {@code
 * TimeParticipant.simulateWorld} 的默认桥（{@code WorldTimeProposal.single} <b>已带过 incidents</b>） ——★
 * 不许自己写四参包装，那会静默丢掉突发事件（急停静默失效）。
 *
 * <p>★ <b>纯函数</b>（不变量 I1）：只读 base、只吐提案；命中只记日志，不改任何状态。逐日自足（不变量 I2）：没有跨日/跨 advance
 * 的记忆，把一段推进切成十次单日，结果逐字相同。
 *
 * <p>★★ <b>代价（账本已记，不得当成免费）</b>：每个军每天一次 {@code UnitMoves.evaluate} —— 这是与 Unit 侧的<b>重复计算</b> （{@code
 * UnitTimeParticipant} 也会算），换来的是"判据住对模块"（用户裁定的归属）。19 格世界上可忽略，<b>大图上未评估</b>。 另：一天 N 个军 ⇒ O(N²)
 * 次六角距离比较（{@link ArmyContacts#scan}），本批不做空间索引。
 */
public final class ArmyTimeParticipant implements TimeParticipant {

  /**
   * 参与者身份（★ 也是 {@link Incident#namespace()} 的取值）。
   *
   * <p>★ 字典序位置：{@code army} &lt; {@code ga} &lt; {@code population} ⇒
   * 它<b>最先</b>跑。这正是"必须自己算"的原因（见类注）。
   */
  public static final String NAMESPACE = "army";

  /**
   * 移动成本函数（★ 由 app 注入，不在这里 {@code new}）：与 {@code UnitTimeParticipant} 走的是<b>同一个</b>实现 （{@code
   * TerrainMovementCost.INSTANCE}），否则"Army 算出来的位置"与"Unit 真正搬到的位置"会各说各话。
   */
  private final MovementCost cost;

  public ArmyTimeParticipant(MovementCost cost) {
    this.cost = Objects.requireNonNull(cost, "cost");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public TimeProposal simulate(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    ArmyData data = ArmySnapshots.of(state).data(); // 缺切片 = 装配故障 ⇒ 当场抛（既有口径）
    Optional<SimosTimestamp> to = range.to();
    if (to.isEmpty()) {
      // 开区间推进（无上界）⇒ 没有"逐日"可枚举。与 UnitTimeParticipant 同一条处置：交不变提案，不猜终点。
      EventLog.channel(ArmyLog.contact())
          .info(
              LogEvent.of(
                  "ARMY_CONTACT_SCAN_SKIPPED",
                  ArmyLogSource.ARMY_CONTACT,
                  "day",
                  range.from().tick(),
                  "reason",
                  "noUpperBound",
                  "armies",
                  data.armies().size()));
      return unchanged(data);
    }
    UnitState units = ArmySnapshots.units(state);
    GameMap map = mapOf(state);
    long fromTick = range.from().tick();
    long toTick = to.get().tick();
    List<Incident> incidents = new ArrayList<>();
    for (long day = fromTick + 1; day <= toTick; day++) {
      // ★★ 逐日：只在 toTick 算一次会漏掉"擦肩而过"（必做，不是优化）。
      SimosTimestamp at = SimosTimestamp.of(day);
      incidents.addAll(ArmyContacts.scan(day, data.armies(), positionsAt(data, units, map, at)));
    }
    if (ArmyLog.contact().isDebugEnabled()) {
      EventLog.channel(ArmyLog.contact())
          .debug(
              LogEvent.of(
                  "ARMY_CONTACT_SCAN_END",
                  ArmyLogSource.ARMY_CONTACT,
                  "from",
                  fromTick,
                  "to",
                  toTick,
                  "armies",
                  data.armies().size(),
                  "days",
                  toTick - fromTick,
                  "contacts",
                  incidents.size()));
    }
    return new TimeProposal(
        NAMESPACE, ArmyChangeSet.between(data, data), Set.of(), Set.of(), incidents);
  }

  /** 不变提案（"这一参与者没改 army 切片的任何东西"）——{@code moduleChanges} 不得为空，故交不变变更集。 */
  private TimeProposal unchanged(ArmyData data) {
    return new TimeProposal(NAMESPACE, ArmyChangeSet.between(data, data), Set.of(), Set.of());
  }

  /**
   * 当天各军的位置（缺键 = 当天算不出该军的位置 ⇒ 它不参与判据，与"位置未知"同一条 fail-closed 方向）。
   *
   * <p>★ 遍历序 = {@code armies} 表的插入序；判据内部按 id 字典序重排（{@link ArmyContacts#scan}）⇒ 结果与遍历序无关。
   */
  private Map<ArmyId, HexCoord> positionsAt(
      ArmyData data, UnitState units, GameMap map, SimosTimestamp at) {
    Map<ArmyId, HexCoord> positions = new LinkedHashMap<>();
    for (Army army : data.armies().values()) {
      // ★ 只有编制顶层会自己走（UnitTimeParticipant 的同一条判定）：rootUnit 若挂在别人下面，动的是顶层。
      UnitId moverId = units.formationRoot(army.rootUnit(), at).orElse(army.rootUnit());
      Unit mover = units.units().get(moverId);
      if (mover == null) {
        if (ArmyLog.contact().isDebugEnabled()) {
          EventLog.channel(ArmyLog.contact())
              .debug(
                  LogEvent.of(
                      "ARMY_CONTACT_ARMY_SKIPPED",
                      ArmyLogSource.ARMY_CONTACT,
                      "day",
                      at.tick(),
                      "army",
                      army.id().value(),
                      "rootUnit",
                      army.rootUnit().value(),
                      "reason",
                      "rootUnitMissing"));
        }
        continue;
      }
      Optional<HexCoord> hex = positionOf(mover, at, map);
      if (hex.isEmpty()) {
        hex = units.effectivePosition(army.rootUnit(), at);
      }
      if (hex.isEmpty()) {
        if (ArmyLog.contact().isDebugEnabled()) {
          EventLog.channel(ArmyLog.contact())
              .debug(
                  LogEvent.of(
                      "ARMY_CONTACT_ARMY_SKIPPED",
                      ArmyLogSource.ARMY_CONTACT,
                      "day",
                      at.tick(),
                      "army",
                      army.id().value(),
                      "rootUnit",
                      army.rootUnit().value(),
                      "reason",
                      "positionUnknown"));
        }
        continue;
      }
      positions.put(army.id(), hex.get());
    }
    return positions;
  }

  /**
   * 单个单位在 {@code at} 的位置：在途 ⇒ 纯函数物化；否则 ⇒ 位置时态序列的现读值。
   *
   * <p>★ {@code UnitMoves.evaluate} 的两个前提在 {@code UnitTimeParticipant} 与本类同源：① 单位有在途路线；②
   * 查询时刻不早于出发时刻 —— 前提不成立时它<b>抛</b>（那是"调用方 bug"的语义），故这里先判后调，不拿异常当控制流。
   */
  private Optional<HexCoord> positionOf(Unit unit, SimosTimestamp at, GameMap map) {
    Optional<Movement> inFlight = unit.movement();
    if (inFlight.isPresent() && at.compareTo(inFlight.get().departedAt()) >= 0) {
      return Optional.of(UnitMoves.evaluate(unit, at, map, cost).currentHex());
    }
    return unit.position().valueAt(at);
  }

  /**
   * 状态里的 map 切片（与 {@code ArmyToolReads.gameMap} 逐字同口径：缺席或类型不对是<b>装配故障</b>，当场抛）。
   *
   * <p>★ 为什么不直接调 {@code ArmyToolReads}：那个类住 {@code army.tools}（工具面），本类住 {@code army.spi}（推进面） —— 让
   * spi 反向依赖 tools 只会造出一条"看着能用、方向反了"的接缝。
   */
  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }
}
