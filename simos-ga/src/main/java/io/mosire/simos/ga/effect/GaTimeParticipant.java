package io.mosire.simos.ga.effect;

import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.id.EffectId;
import io.mosire.simos.ga.model.DecisionInfoEntry;
import io.mosire.simos.ga.model.DecisionInfoIds;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ga 侧的时间推进参与者（spec §五.2，C4）：**每个世界日求值一次 {@code Effect.trigger}，满足则执行动作并标 {@code FIRED}**（R6 延期效果）。
 *
 * <p>★ {@code simulate} 是**纯函数**：**从 {@code range.from} 逐日推到 {@code range.to}**，产出**本模块** {@code
 * GaChangeSet}（§十一：一次推进 N 天，内部逐日，避免一次跳日把中间该发生的触发整段跳过）。 **绝不放进 ③Resolve**。
 *
 * <p>★ **时间量纲（2026-09-24 日制裁定）**：{@code range.to.tick()} 是**世界日**（全局 1 tick = 1 天）⇒ 求值里 {@code
 * at.tick()} 与参照点的差值即**日数**。
 *
 * <p>★ **能力边界**：<b>只写 ga</b>——{@code effects} 与 {@code info} 两张表<b>同住 {@code GaState}</b>（本批起）；
 * 跨模块效果仍落成 {@code Action.EnqueueUnitCommand}，由 app 层 {@code CommandDrain} 落真 revision（spec §五.3）；
 * 只读 unit 切片（{@code Trigger.UnitAtHex}）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类自 {@code io.mosire.simos.sd.time.SdTimeParticipant} 整类迁入本包（{@code
 * SdTimeParticipant → GaTimeParticipant}）</b>（用户裁定「<b>sd 整个弃用！</b>」＋「<b>那让它依赖unit呗</b>」）。
 * 迁入时<b>由多切片参与者收成单切片参与者</b>：{@code Action.PutInfo} 写的 INFO 覆盖层（批次 3b-2 起住 {@code GaState.info}）与
 * {@code effects} 表<b>现在同在一棵树里</b> ⇒ 3b-2 为跨切片写而引入的注入口（{@code
 * io.mosire.simos.sd.spi.DecisionTablesLookup} 的 {@code info()}/{@code replaceInfo()}）与 {@code
 * WorldTimeProposal} 的 {@code "sd"}／{@code "ga"} 双分量<b>整条删除</b>——本类回到"只改自己一个模块"的窄形态 （{@code
 * TimeProposal}），与 {@code UnitTimeParticipant} 等单切片参与者同形。
 *
 * <p>★★ <b>"每世界日推进 effect"的行为如何逐字保住（逐条对照搬迁前）</b>：
 *
 * <ol>
 *   <li><b>逐日循环</b>：{@code for (day = range.from.tick() + 1; day <= range.to.tick();
 *       day++)}，每天用**该日开始时的状态** （{@code effects}/{@code info} 已含前几天累计结果）求值 ⇒ 与"N
 *       次单日推进"逐步等价（这条是等价性的根，一字未改）；
 *   <li><b>参与求值的档</b>：状态为 {@code PLANNED}/{@code COMMITTED} 的效果才求值（其余计入 {@code
 *       effectsStatusSkipped}），<b>按 {@code effectId} 字典序</b>遍历（{@code sortedEffects}）；
 *   <li><b>求值本体</b>：{@link TriggerEvaluator#evaluate} 逐字照用（五档存活语义未动）；
 *   <li><b>触发</b>：达标即整条替换成 {@code status = FIRED}（其余五个字段原样带过）、记 TRACE {@code SD_EFFECT_FIRED}，
 *       并执行动作；
 *   <li><b>动作落地</b>：{@code Action.PutInfo} → 往 {@code info} 的同地址条目表<b>追加</b>一条 {@code
 *       DecisionInfoEntry} （序号 = 追加前条目数、tick = 当日的 {@code at.tick()}、tags/affiliations
 *       空集、note/sourceDirective/ adjudicationStatus 皆空、{@code atRevision} = 本次推进的 base
 *       revision）；{@code Action.EnqueueUnitCommand} → <b>有意不处理</b>（跨模块写，交 app 层 drain）；
 *   <li><b>读写声明</b>：{@code reads} 收 {@code ga:effect.<id>} 与触发条件引用的 {@code unit:<id>}／{@code
 *       map:<mapId>:hex.<q>_<r>}；{@code writes} 收 {@code ga:effect.<id>} 与被写 INFO 的 {@code
 *       ga:info.<key>} ——★ <b>唯一有意的字符串变化</b>：命名空间由 {@code sd} 换成 {@code ga}（表换了家，地址族随之；见 §"如实记"）；
 *   <li><b>空范围</b>：{@code range.to} 缺省 ⇒ **零变更提案、不抛**（照 {@code UnitTimeParticipant} 的边界），只记一条 DEBUG
 *       {@code SD_ADVANCE_SKIPPED}。
 * </ol>
 *
 * <p>★ <b>如实记：本批的一处可观测面变化</b>——搬迁前，本次推进若一天都没写 INFO，提案里<b>只有 sd 分量</b>（ga 切片不在 {@code moduleChanges}
 * 里）；按 {@code TimeAdvance} 的"变更集里没提到的模块保持原样、不推进坐标"口径，ga 切片的 {@code ref}/{@code timestamp}
 * 那时<b>不前进</b>。本类成为 ga 自己的参与者后，每次世界推进 ga 分量<b>总在</b>提案里 ⇒ ga
 * 切片每次推进都换到新坐标——这与"有参与者的模块每次推进都换坐标"的既有口径一致（unit/social/economy 等逐字如此）， 也与 {@code Replay.applyWorld}
 * 的语义不冲突（现在每次都"提到"了它）。★ <b>effect 的求值/触发/落地语义不受此影响</b> （逐字未变）。
 *
 * <p>★ <b>日志面（如实记）</b>：事件名（{@code SD_ADVANCE_START} / {@code SD_ADVANCE_END} / {@code
 * SD_ADVANCE_DAY} / {@code SD_ADVANCE_SKIPPED} / {@code SD_EFFECT_FIRED}）与全部字段<b>逐字保留</b>；变的只有
 * logger 名（{@code io.mosire.simos.sd.time} → {@code io.mosire.simos.ga.time}，开关随之从 {@code
 * -Dsimos.sd.logLevel} 换成 {@code -Dsimos.ga.logLevel}）与来源表（{@code sd-tick} → {@code ga-tick}）， 与批次
 * 3a/3b′ 迁移的其余 handler 同款口径。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2 的那次收缩仍然成立（历史留痕）</b>：本类原先还有第二段"逐日求值 {@code CombatStage.exit} 并推进
 * {@code CombatState.currentStage}"，随旧战斗族退役整段删除——那是<b>退役</b>不是搬迁（army 的阶段推进走它自己的命令 {@code
 * army.AppendCombatStage} / {@code army.ResolveCombatStage}，不走触发器状态机）。
 */
public final class GaTimeParticipant implements TimeParticipant {

  private static final LogChannel TIME = EventLog.channel(GaLog.time());

  private static final LogChannel TRACE = EventLog.channel(GaLog.trace());

  /** 参与者身份 == 本参与者所写模块的命名空间（单切片参与者，与 {@code UnitTimeParticipant} 同形）。 */
  private static final String NAMESPACE = "ga";

  private final String mapId;

  /**
   * @param mapId 地图 id（触发器的 {@code unit:*} / hex 地址需要它——{@code reads} 声明里的 {@code map:<id>:hex.*}）
   */
  public GaTimeParticipant(String mapId) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public TimeProposal simulate(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    GaState base = gaOf(state);
    Optional<SimosTimestamp> to = range.to();
    if (to.isEmpty()) {
      if (TIME.isDebugEnabled()) {
        TIME.debug(
            LogEvent.of(
                "SD_ADVANCE_SKIPPED",
                GaLogSource.GA_TICK,
                "day",
                range.from().tick(),
                "from",
                range.from().tick(),
                "reason",
                "range.to 缺省（无上界推进）"));
      }
      return new TimeProposal(NAMESPACE, GaChangeSet.between(base, base), Set.of(), Set.of());
    }
    SimosTimestamp end = to.get();
    long startDay = range.from().tick();
    long endDay = end.tick();
    long days = endDay - startDay;
    EventLog.channel(GaLog.time())
        .info(
            LogEvent.of(
                "SD_ADVANCE_START",
                GaLogSource.GA_TICK,
                "day",
                startDay,
                "from",
                startDay,
                "to",
                endDay,
                "days",
                days,
                "effects",
                base.effects().size()));
    long firedEffects = 0L;
    long effectsEvaluated = 0L;
    long effectsStatusSkipped = 0L;
    long triggersFalse = 0L;
    RevisionId atRevision = state.meta().ref().revision();
    UnitState units = unitsOf(state);
    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();

    Map<EffectId, Effect> effects = new LinkedHashMap<>(base.effects());
    Map<String, List<DecisionInfoEntry>> info = new LinkedHashMap<>(base.info());

    // ★★ §十一（2026-09-25）：一次推进 N 天 ⇒ **从 from 逐日推到 to**——AfterTicks/AtOrAfterTick、effect 触发
    //   都按**日**推进。一次跳 100 天只按 to 求值，会把中间该发生的触发整段跳过（等价性当场破）。
    //   ★ 每天用**该日开始时的状态快照**（effects/info 已含前几天的累计结果）求值，与"N 次单日推进"
    //     的逐步语义逐字一致——这就是等价性的根。
    for (long day = startDay + 1L; day <= endDay; day++) {
      SimosTimestamp at = SimosTimestamp.of(day);
      // ★ effects 与 info 现在同住 GaState ⇒ todayBegin 两件都带（搬迁前 info 在另一个模块，只能靠第二个可变局部累计）。
      GaState todayBegin = base.withEffects(effects).withInfo(info);
      long firedToday = 0L;
      long evaluatedToday = 0L;
      long statusSkippedToday = 0L;
      long triggersFalseToday = 0L;

      for (Effect effect : sortedEffects(todayBegin)) {
        if (effect.status() != EffectStatus.PLANNED && effect.status() != EffectStatus.COMMITTED) {
          statusSkippedToday++;
          continue;
        }
        evaluatedToday++;
        reads.add(effectAddress(effect.id()));
        collectTriggerReads(effect.trigger(), reads);
        if (!TriggerEvaluator.evaluate(effect.trigger(), at, units, effect.createdTick())) {
          triggersFalseToday++;
          continue;
        }
        effects.put(
            effect.id(),
            new Effect(
                effect.id(),
                effect.kind(),
                effect.trigger(),
                effect.action(),
                EffectStatus.FIRED,
                effect.createdTick()));
        firedEffects++;
        firedToday++;
        if (TRACE.isTraceEnabled()) {
          TRACE.trace(
              LogEvent.of(
                  "SD_EFFECT_FIRED",
                  GaLogSource.GA_TICK,
                  "day",
                  day,
                  "id",
                  effect.id().value(),
                  "kind",
                  effect.kind(),
                  "action",
                  effect.action().getClass().getSimpleName()));
        }
        writes.add(effectAddress(effect.id()));
        applyAction(effect.action(), atRevision, at.tick(), info, writes);
      }

      effectsEvaluated += evaluatedToday;
      effectsStatusSkipped += statusSkippedToday;
      triggersFalse += triggersFalseToday;
      if (TIME.isDebugEnabled()) {
        TIME.debug(
            LogEvent.of(
                "SD_ADVANCE_DAY",
                GaLogSource.GA_TICK,
                "day",
                day,
                "effectsEvaluated",
                evaluatedToday,
                "effectsFired",
                firedToday,
                "effectsStatusSkipped",
                statusSkippedToday,
                "triggersFalse",
                triggersFalseToday));
      }
    }

    GaState target = base.withEffects(effects).withInfo(info);
    EventLog.channel(GaLog.time())
        .info(
            LogEvent.of(
                "SD_ADVANCE_END",
                GaLogSource.GA_TICK,
                "day",
                endDay,
                "from",
                startDay,
                "to",
                endDay,
                "days",
                days,
                "firedEffects",
                firedEffects,
                "effectsEvaluated",
                effectsEvaluated,
                "effectsStatusSkipped",
                effectsStatusSkipped,
                "triggersFalse",
                triggersFalse,
                "effects",
                effects.size()));
    return new TimeProposal(NAMESPACE, GaChangeSet.between(base, target), reads, writes);
  }

  /**
   * 触发器的动作落地（★ 2026-10-12 批次 3d-2：由 switch 收成单支；本批起写的是**本模块**的 {@code info} 表）。
   *
   * <p>今天 {@link Action} 只剩 {@code PutInfo} 与 {@code EnqueueUnitCommand} 两档，后者由 app 层 {@code
   * CommandDrain} 处理（本方法**有意**不处理它——那是跨模块写，不是本参与者的活），故这里是穷尽两支。
   */
  private void applyAction(
      Action action,
      RevisionId atRevision,
      long tick,
      Map<String, List<DecisionInfoEntry>> info,
      Set<String> writes) {
    switch (action) {
      case Action.PutInfo putInfo -> {
        String address = putInfo.address().canonical();
        List<DecisionInfoEntry> entries = new ArrayList<>(info.getOrDefault(address, List.of()));
        // ★ 决策结果三件套（第 3 波第 1 步）：效果写的 INFO 没有决策人上下文 ⇒ tags 空集（无主）。
        entries.add(
            new DecisionInfoEntry(
                DecisionInfoIds.synthesize(address, entries.size()),
                tick,
                Set.of(),
                Set.of(),
                putInfo.key(),
                putInfo.value(),
                Optional.empty(),
                atRevision,
                Optional.empty(),
                Optional.empty()));
        info.put(address, List.copyOf(entries));
        writes.add(infoAddress(putInfo.key()));
      }
      // ★ 跨模块效果（Action.EnqueueUnitCommand）不在此落地：它由 app 层 CommandDrain 经 CoreSimos.submit
      //   落成真 revision（spec §五.3，铁律 3）——这是既有分工，不是本批新加的分支。
      case Action.EnqueueUnitCommand ignored -> {}
    }
  }

  private static List<Effect> sortedEffects(GaState base) {
    List<Effect> out = new ArrayList<>(base.effects().values());
    out.sort((left, right) -> left.id().value().compareTo(right.id().value()));
    return out;
  }

  private void collectTriggerReads(Trigger trigger, Set<String> reads) {
    switch (trigger) {
      case Trigger.UnitAtHex unitAtHex -> {
        reads.add("unit:" + unitAtHex.unit().value());
        reads.add(hexAddress(mapId, unitAtHex.hex()));
      }
      case Trigger.And and -> {
        for (Trigger child : and.all()) {
          collectTriggerReads(child, reads);
        }
      }
      case Trigger.Or or -> {
        for (Trigger child : or.any()) {
          collectTriggerReads(child, reads);
        }
      }
      default -> {
        // 时间阈值（AtOrAfterTick / AfterTicks）不引用实体
      }
    }
  }

  private static String effectAddress(EffectId id) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of("effect", id.value())))
        .canonical();
  }

  /** ★ {@code info} 表已住本模块 ⇒ 写声明也落 {@code ga} 命名空间（搬迁前是 {@code sd:info.<key>}）。 */
  private static String infoAddress(String key) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of("info", key))).canonical();
  }

  private static String hexAddress(String mapId, HexCoord hex) {
    return new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("hex", hex.toString())))
        .canonical();
  }

  /** ga 切片（本参与者所写的模块）：缺席 / 类型不对 = **装配故障**，当场炸（不折成"今天没有效果"）。 */
  private static GaState gaOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(NAMESPACE)
            .orElseThrow(() -> new IllegalStateException("state 里没有 ga 切片（装配故障）"));
    if (!(snapshot instanceof GaSnapshot gaSnapshot)) {
      throw new IllegalStateException(
          "state 的 ga 切片不是 GaSnapshot: " + snapshot.getClass().getName());
    }
    return gaSnapshot.state();
  }

  /** unit 切片（只读；{@code Trigger.UnitAtHex} 求值用）：缺席 / 类型不对 = 装配故障，当场炸。 */
  private static UnitState unitsOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalStateException("state 里没有 unit 切片（装配故障）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }
}
