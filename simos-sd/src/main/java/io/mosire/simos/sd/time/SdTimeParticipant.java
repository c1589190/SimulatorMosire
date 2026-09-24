package io.mosire.simos.sd.time;

import io.mosire.simos.map.hex.HexCoord;
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
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
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
 * sd 侧的时间推进参与者（spec §五.2，C4）：**每个 sd 命名空间只有一个**（{@code putIfAbsent} 重复即抛）。
 *
 * <p>★ {@code simulate} 是**纯函数**：用 {@code range.to} 求值 {@code Effect.trigger}（R6 延期效果）与 {@code
 * CombatStage.exit}（N1 阶段推进），产出**本模块** {@code SdChangeSet}。**绝不放进 ③Resolve**。
 *
 * <p>★ **时间量纲（2026-09-24 日制裁定）**：{@code range.to.tick()} 是**世界日**（全局 1 tick = 1 天）⇒ 求值里 {@code
 * at.tick()} 与参照点的差值即**日数**。
 *
 * <p>★ **能力边界**：只写 sd（跨模块效果落成 {@code Action.EnqueueUnitCommand}，由 app 层 {@code SdCommandDrain} 落真
 * revision，spec §五.3）；只读 unit 切片。
 *
 * <p>★ {@code range.to} 缺省（无上界推进）⇒ **零变更提案、不抛**（照 {@code UnitTimeParticipant} 的边界）。
 */
public final class SdTimeParticipant implements TimeParticipant {

  private static final String NAMESPACE = "sd";

  private final String mapId;

  public SdTimeParticipant(String mapId) {
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
    SdState base = sdOf(state);
    Optional<SimosTimestamp> to = range.to();
    if (to.isEmpty()) {
      return new TimeProposal(NAMESPACE, SdChangeSet.between(base, base), Set.of(), Set.of());
    }
    SimosTimestamp at = to.get();
    RevisionId atRevision = state.meta().ref().revision();
    UnitState units = unitsOf(state);
    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();

    Map<EffectId, Effect> effects = new LinkedHashMap<>(base.effects());
    Map<CombatStateId, CombatState> combatStates = new LinkedHashMap<>(base.combatStates());
    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>(base.info());

    for (Effect effect : sortedEffects(base)) {
      if (effect.status() != EffectStatus.PLANNED && effect.status() != EffectStatus.COMMITTED) {
        continue;
      }
      reads.add(effectAddress(effect.id()));
      collectTriggerReads(effect.trigger(), reads);
      if (!TriggerEvaluator.evaluate(effect.trigger(), at, base, units, effect.createdTick())) {
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
      writes.add(effectAddress(effect.id()));
      applyAction(effect.action(), atRevision, at.tick(), base, combatStates, info, reads, writes);
    }

    for (CombatState combatState : sortedStates(base)) {
      Combat combat = base.combats().get(combatState.combatId());
      CombatStage current = stageOf(combat, combatState.currentStage());
      if (current == null || current.exit().isEmpty()) {
        continue;
      }
      reads.add(stageAddress(combat.id(), current.id()));
      for (Trigger condition : current.exit()) {
        collectTriggerReads(condition, reads);
      }
      boolean satisfied = true;
      for (Trigger condition : current.exit()) {
        if (!TriggerEvaluator.evaluate(condition, at, base, units, 0L)) {
          satisfied = false;
          break;
        }
      }
      if (!satisfied) {
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
      writes.add(stageAddress(combat.id(), next.id()));
    }

    SdState target = base.withEffects(effects).withCombatStates(combatStates).withInfo(info);
    return new TimeProposal(NAMESPACE, SdChangeSet.between(base, target), reads, writes);
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
