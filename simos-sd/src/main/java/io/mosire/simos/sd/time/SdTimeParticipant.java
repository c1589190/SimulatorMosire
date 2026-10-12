package io.mosire.simos.sd.time;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.spi.DecisionTablesLookup;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
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
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.ChangeSet;
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
 * sd 侧的时间推进参与者（spec §五.2，C4）：**每个 sd 命名空间只有一个**（{@code putIfAbsent} 重复即抛）。
 *
 * <p>★ {@code simulate} 是**纯函数**：**从 {@code range.from} 逐日推到 {@code range.to}**，逐日求值 {@code
 * Effect.trigger}（R6 延期效果）与 {@code CombatStage.exit}（N1 阶段推进），产出**本模块** {@code
 * SdChangeSet}（§十一：一次推进 N 天，内部逐日，避免一次跳日把中间该发生的阶段转换整段跳过）。**绝不放进 ③Resolve**。
 *
 * <p>★ **时间量纲（2026-09-24 日制裁定）**：{@code range.to.tick()} 是**世界日**（全局 1 tick = 1 天）⇒ 求值里 {@code
 * at.tick()} 与参照点的差值即**日数**。
 *
 * <p>★ **能力边界**：只写 sd（跨模块效果落成 {@code Action.EnqueueUnitCommand}，由 app 层 {@code SdCommandDrain} 落真
 * revision，spec §五.3）；只读 unit 切片。
 *
 * <p>★★ <b>2026-10-12 批次 3b-2：本参与者成了<b>多切片参与者</b>（{@link WorldTimeProposal}）</b>——{@code
 * Action.PutInfo} 写的 INFO 覆盖层搬进了 {@code GaState.info}，而 sd 不得依赖 ga（成环）⇒ 那一半经注入的 {@link
 * DecisionTablesLookup#replaceInfo} 产出 <b>ga 命名空间的变更集</b>（不透明 {@link ChangeSet}），与 sd 的
 * effects/combatStates 变更集装在**同一次推进的同一个提案**里（{@code TimeProposalResolver} 的读写集仍按参与者整体声明，
 * 语义与搬迁前逐字相同：INFO 仍是本参与者在本次推进里"写"的地址）。★ <b>没有 INFO 写入时不带 ga 分量</b>——不让每次推进都给 ga
 * 切片换一次坐标（与"不写就不动"同口径）。
 *
 * <p>★ {@code range.to} 缺省（无上界推进）⇒ **零变更提案、不抛**（照 {@code UnitTimeParticipant} 的边界）。
 */
public final class SdTimeParticipant implements TimeParticipant {

  private static final LogChannel TIME = EventLog.channel(SdLog.time());

  private static final LogChannel TRACE = EventLog.channel(SdLog.trace());

  private static final String NAMESPACE = "sd";

  private final String mapId;

  private final DecisionTablesLookup decisionTables;

  /**
   * @param mapId 地图 id（触发器的 {@code unit:*} / hex 地址需要它）
   * @param decisionTables ga 侧 {@code info} 覆盖层的读写口（装配期注入；非 null——缺了它 {@code Action.PutInfo} 无从落地）
   */
  public SdTimeParticipant(String mapId, DecisionTablesLookup decisionTables) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.decisionTables = Objects.requireNonNull(decisionTables, "decisionTables");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  /**
   * ★★ <b>多切片提案</b>（2026-10-12 批次 3b-2）：sd 分量（effects / combatStates）+ ga 分量（{@code Action.PutInfo}
   * 写的 INFO 覆盖层）。{@link TimeParticipant} 的契约里，改多个模块的参与者实现本方法、**不**实现 {@code simulate}（后者的默认实现会抛，
   * 指路到这里）——与 {@code PopulationUnitTimeParticipant} / {@code PopulationEconomyTimeParticipant} 同款。
   */
  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    SdState base = sdOf(state);
    Optional<SimosTimestamp> to = range.to();
    if (to.isEmpty()) {
      if (TIME.isDebugEnabled()) {
        TIME.debug(
            LogEvent.of(
                "SD_ADVANCE_SKIPPED",
                SdLogSource.SD_TICK,
                "day",
                range.from().tick(),
                "from",
                range.from().tick(),
                "reason",
                "range.to 缺省（无上界推进）"));
      }
      return WorldTimeProposal.single(
          new TimeProposal(NAMESPACE, SdChangeSet.between(base, base), Set.of(), Set.of()));
    }
    SimosTimestamp end = to.get();
    long startDay = range.from().tick();
    long endDay = end.tick();
    long days = endDay - startDay;
    EventLog.channel(SdLog.time())
        .info(
            LogEvent.of(
                "SD_ADVANCE_START",
                SdLogSource.SD_TICK,
                "day",
                startDay,
                "from",
                startDay,
                "to",
                endDay,
                "days",
                days,
                "effects",
                base.effects().size(),
                "combatStates",
                base.combatStates().size()));
    long firedEffects = 0L;
    long advancedStages = 0L;
    long effectsEvaluated = 0L;
    long effectsStatusSkipped = 0L;
    long triggersFalse = 0L;
    long stagesChecked = 0L;
    long stageExitsFalse = 0L;
    RevisionId atRevision = state.meta().ref().revision();
    UnitState units = unitsOf(state);
    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();

    Map<EffectId, Effect> effects = new LinkedHashMap<>(base.effects());
    Map<CombatStateId, CombatState> combatStates = new LinkedHashMap<>(base.combatStates());
    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>(decisionTables.info(state));

    // ★★ §十一（2026-09-25）：一次推进 N 天 ⇒ **从 from 逐日推到 to**——阶段链、AfterTicks/AtOrAfterTick、effect 触发
    //   都按**日**推进。一次跳 100 天只按 to 求值，会把中间该发生的阶段转换/触发整段跳过（等价性当场破）。
    //   ★ 每天用**该日开始时的状态快照**（effects/combatStates/info 已含前几天的累计结果）求值，与"N 次单日推进"
    //     的逐步语义逐字一致——这就是等价性的根。
    for (long day = startDay + 1L; day <= endDay; day++) {
      SimosTimestamp at = SimosTimestamp.of(day);
      // ★ 本批起 {@code info} 不在 sd 树里 ⇒ todayBegin 只带 sd 自己的两个组件；info 的累计仍在本方法内（第 3 个可变局部）。
      SdState todayBegin = base.withEffects(effects).withCombatStates(combatStates);
      long firedToday = 0L;
      long evaluatedToday = 0L;
      long statusSkippedToday = 0L;
      long triggersFalseToday = 0L;
      long stagesCheckedToday = 0L;
      long stageExitsFalseToday = 0L;

      for (Effect effect : sortedEffects(todayBegin)) {
        if (effect.status() != EffectStatus.PLANNED && effect.status() != EffectStatus.COMMITTED) {
          statusSkippedToday++;
          continue;
        }
        evaluatedToday++;
        reads.add(effectAddress(effect.id()));
        collectTriggerReads(effect.trigger(), reads);
        if (!TriggerEvaluator.evaluate(
            effect.trigger(), at, todayBegin, units, effect.createdTick())) {
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
                  SdLogSource.SD_TICK,
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
        applyAction(
            effect.action(), atRevision, at.tick(), todayBegin, combatStates, info, reads, writes);
      }

      long advancedToday = 0L;
      for (CombatState combatState : sortedStates(todayBegin)) {
        Combat combat = base.combats().get(combatState.combatId());
        CombatStage current = stageOf(combat, combatState.currentStage());
        if (current == null || current.exit().isEmpty()) {
          continue;
        }
        stagesCheckedToday++;
        reads.add(stageAddress(combat.id(), current.id()));
        for (Trigger condition : current.exit()) {
          collectTriggerReads(condition, reads);
        }
        boolean satisfied = true;
        for (Trigger condition : current.exit()) {
          if (!TriggerEvaluator.evaluate(condition, at, todayBegin, units, 0L)) {
            satisfied = false;
            break;
          }
        }
        if (!satisfied) {
          stageExitsFalseToday++;
          continue;
        }
        CombatStage next = nextStage(combat, current);
        if (next == null) {
          continue;
        }
        combatStates.put(
            combatState.id(),
            new CombatState(
                combatState.id(),
                combatState.combatId(),
                next.id(),
                combatState.hex(),
                combatState.participants(),
                combatState.selectedOutcome(),
                combatState.losses()));
        advancedStages++;
        advancedToday++;
        if (TRACE.isTraceEnabled()) {
          TRACE.trace(
              LogEvent.of(
                  "SD_COMBAT_STAGE_ADVANCED",
                  SdLogSource.SD_TICK,
                  "day",
                  day,
                  "combat",
                  combat.id().value(),
                  "from",
                  current.id().value(),
                  "to",
                  next.id().value()));
        }
        writes.add(stageAddress(combat.id(), next.id()));
      }

      effectsEvaluated += evaluatedToday;
      effectsStatusSkipped += statusSkippedToday;
      triggersFalse += triggersFalseToday;
      stagesChecked += stagesCheckedToday;
      stageExitsFalse += stageExitsFalseToday;
      if (TIME.isDebugEnabled()) {
        TIME.debug(
            LogEvent.of(
                "SD_ADVANCE_DAY",
                SdLogSource.SD_TICK,
                "day",
                day,
                "effectsEvaluated",
                evaluatedToday,
                "effectsFired",
                firedToday,
                "effectsStatusSkipped",
                statusSkippedToday,
                "triggersFalse",
                triggersFalseToday,
                "stagesChecked",
                stagesCheckedToday,
                "stagesAdvanced",
                advancedToday,
                "stageExitsFalse",
                stageExitsFalseToday));
      }
    }

    SdState target = base.withEffects(effects).withCombatStates(combatStates);
    EventLog.channel(SdLog.time())
        .info(
            LogEvent.of(
                "SD_ADVANCE_END",
                SdLogSource.SD_TICK,
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
                "advancedStages",
                advancedStages,
                "effectsEvaluated",
                effectsEvaluated,
                "effectsStatusSkipped",
                effectsStatusSkipped,
                "triggersFalse",
                triggersFalse,
                "stagesChecked",
                stagesChecked,
                "stageExitsFalse",
                stageExitsFalse,
                "effects",
                effects.size(),
                "combatStates",
                combatStates.size()));
    SdChangeSet sdChangeSet = SdChangeSet.between(base, target);
    if (info.equals(decisionTables.info(state))) {
      // ★ 本次一天都没写 INFO ⇒ 单切片提案（形态与搬迁前逐字相同）：不让每次推进都给 ga 切片换一次坐标。
      return WorldTimeProposal.single(new TimeProposal(NAMESPACE, sdChangeSet, reads, writes));
    }
    // ★★ 多切片提案：sd 分量 + ga 分量（INFO 覆盖层）。ga 分量由注入的口子算（sd 不认识 GaChangeSet）。
    ChangeSet gaChangeSet = decisionTables.replaceInfo(state, info);
    Map<String, ChangeSet> moduleChanges = new LinkedHashMap<>();
    moduleChanges.put(NAMESPACE, sdChangeSet);
    moduleChanges.put("ga", gaChangeSet);
    return new WorldTimeProposal(NAMESPACE, moduleChanges, reads, writes);
  }

  private void applyAction(
      Action action,
      RevisionId atRevision,
      long tick,
      SdState base,
      Map<CombatStateId, CombatState> combatStates,
      Map<String, List<SdInfoEntry>> info,
      Set<String> reads,
      Set<String> writes) {
    switch (action) {
      case Action.PutInfo putInfo -> {
        String address = putInfo.address().canonical();
        List<SdInfoEntry> entries = new ArrayList<>(info.getOrDefault(address, List.of()));
        // ★ 决策结果三件套（第 3 波第 1 步）：效果写的 INFO 没有决策人上下文 ⇒ tags 空集（无主）。
        entries.add(
            new SdInfoEntry(
                SdInfoIds.synthesize(address, entries.size()),
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
      case Action.SetStage setStage -> {
        CombatState target = combatStates.get(setStage.combatState());
        if (target != null && base.combats().containsKey(target.combatId())) {
          reads.add(stageAddress(target.combatId(), target.currentStage()));
          writes.add(stageAddress(target.combatId(), setStage.stage()));
          combatStates.put(
              target.id(),
              new CombatState(
                  target.id(),
                  target.combatId(),
                  setStage.stage(),
                  target.hex(),
                  target.participants(),
                  target.selectedOutcome(),
                  target.losses()));
        }
      }
      default -> {}
    }
  }

  private List<Effect> sortedEffects(SdState base) {
    List<Effect> out = new ArrayList<>(base.effects().values());
    out.sort((left, right) -> left.id().value().compareTo(right.id().value()));
    return out;
  }

  private static List<CombatState> sortedStates(SdState base) {
    List<CombatState> out = new ArrayList<>(base.combatStates().values());
    out.sort((left, right) -> left.id().value().compareTo(right.id().value()));
    return out;
  }

  private static CombatStage stageOf(Combat combat, CombatStageId stageId) {
    if (combat == null) {
      return null;
    }
    for (CombatStage stage : combat.stages()) {
      if (stage.id().equals(stageId)) {
        return stage;
      }
    }
    return null;
  }

  private static CombatStage nextStage(Combat combat, CombatStage current) {
    List<CombatStage> stages = combat.stages();
    for (int i = 0; i + 1 < stages.size(); i++) {
      if (stages.get(i).id().equals(current.id())) {
        return stages.get(i + 1);
      }
    }
    return null;
  }

  private void collectTriggerReads(Trigger trigger, Set<String> reads) {
    switch (trigger) {
      case Trigger.UnitAtHex unitAtHex -> {
        reads.add("unit:" + unitAtHex.unit().value());
        reads.add(hexAddress(mapId, unitAtHex.hex()));
      }
      case Trigger.OutcomeSelected outcomeSelected ->
          reads.add(combatAddress(outcomeSelected.combat()));
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
        // 时间/击杀阈值不引用实体
      }
    }
  }

  private static String effectAddress(EffectId id) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of("effect", id.value())))
        .canonical();
  }

  private static String combatAddress(CombatId id) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of("combat", id.value())))
        .canonical();
  }

  private static String stageAddress(CombatId combatId, CombatStageId stageId) {
    return new Address(
            List.of(
                new Namespace(NAMESPACE),
                Entity.of("combat", combatId.value()),
                Entity.of("stage", stageId.value())))
        .canonical();
  }

  private static String infoAddress(String key) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of("info", key))).canonical();
  }

  private static String hexAddress(String mapId, HexCoord hex) {
    return new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("hex", hex.toString())))
        .canonical();
  }

  private static SdState sdOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(NAMESPACE)
            .orElseThrow(() -> new IllegalStateException("state 里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException(
          "state 的 sd 切片不是 SdSnapshot: " + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }

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
