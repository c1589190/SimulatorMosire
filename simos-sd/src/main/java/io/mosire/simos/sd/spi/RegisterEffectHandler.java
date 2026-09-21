package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectKind;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code sd.RegisterEffect} 命令的处理器（spec §四/§三.6，C4）。
 *
 * <pre>{@code
 * {"effectId":"e1","kind":"SCHEDULED",
 *  "trigger":{"@class":"at_or_after_tick","tick":5},
 *  "action":{"@class":"enqueue_unit_command","type":"unit.ApplyCasualties",
 *            "payloadJson":"{\"id\":\"u-1\",\"personnel\":-5,\"equipment\":{}}"}}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；trigger 引用不存在的实体；action 引用不存在的地址/实体、或命令不在白名单（§八.4：效果引用合法性由代码判）。
 *
 * <p>★ **白名单由装配注入**（`Shell` 收全量已注册命令类型后传入，且**排除 `sd.*`**——防自指递归，spec §四）。
 */
public final class RegisterEffectHandler implements CommandHandler {

  private final Set<String> allowedCommandTypes;

  public RegisterEffectHandler(Set<String> allowedCommandTypes) {
    this.allowedCommandTypes =
        Set.copyOf(Objects.requireNonNull(allowedCommandTypes, "allowedCommandTypes"));
  }

  @Override
  public String type() {
    return "sd.RegisterEffect";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      EffectId id = EffectId.parse(SdPayloads.requireText(payload, "effectId"));
      EffectKind kind = SdPayloads.requireEffectKind(payload, "kind");
      Trigger trigger = SdPayloads.requireTrigger(payload, "trigger");
      Action action = SdPayloads.requireAction(payload, "action");
      long createdTick =
          SdPayloads.optionalLong(payload, "createdTick", state.meta().timestamp().tick());
      if (base.effects().containsKey(id)) {
        return new HandlerOutcome.Rejected("效果已存在: " + id);
      }
      requireTriggerReferences(state, base, trigger);
      requireActionReferences(state, base, action);
      Map<EffectId, Effect> next = new LinkedHashMap<>(base.effects());
      next.put(id, new Effect(id, kind, trigger, action, EffectStatus.PLANNED, createdTick));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withEffects(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static void requireTriggerReferences(
      SimulationState state, SdState base, Trigger trigger) {
    switch (trigger) {
      case Trigger.UnitAtHex unitAtHex -> {
        if (!SdSnapshots.unitExists(state, unitAtHex.unit())) {
          throw new IllegalArgumentException("trigger 引用的单位不存在: " + unitAtHex.unit());
        }
      }
      case Trigger.OutcomeSelected outcomeSelected -> {
        requireOutcomeInCombat(base, outcomeSelected.combat(), outcomeSelected.outcome());
      }
      case Trigger.And and -> {
        for (Trigger child : and.all()) {
          requireTriggerReferences(state, base, child);
        }
      }
      case Trigger.Or or -> {
        for (Trigger child : or.any()) {
          requireTriggerReferences(state, base, child);
        }
      }
      default -> {
        // AtOrAfterTick / AfterTicks / ThresholdKills 不引用实体
      }
    }
  }

  private void requireActionReferences(SimulationState state, SdState base, Action action) {
    switch (action) {
      case Action.SetStage setStage -> {
        CombatState combatState = base.combatStates().get(setStage.combatState());
        if (combatState == null) {
          throw new IllegalArgumentException(
              "action 引用的 CombatState 不存在: " + setStage.combatState());
        }
        Combat combat = base.combats().get(combatState.combatId());
        if (combat == null || SdCombats.stageOrNull(combat, setStage.stage()) == null) {
          throw new IllegalArgumentException("action 引用的阶段不存在: " + setStage.stage());
        }
      }
      case Action.RecordCasualties record -> {
        if (!base.lossRecords().containsKey(record.lossRecord())) {
          throw new IllegalArgumentException("action 引用的损失记录不存在: " + record.lossRecord());
        }
      }
      case Action.EnqueueUnitCommand enqueue -> {
        if (!allowedCommandTypes.contains(enqueue.type())) {
          throw new IllegalArgumentException("action 引用的命令不在白名单: " + enqueue.type());
        }
      }
      default -> {
        // PutInfo 的地址由 Address 解析期保证合法
      }
    }
  }

  private static void requireOutcomeInCombat(
      SdState base, CombatId combatId, CombatOutcomeId outcomeId) {
    Combat combat = base.combats().get(combatId);
    if (combat == null) {
      throw new IllegalArgumentException("trigger 引用的交战不存在: " + combatId);
    }
    for (CombatStage stage : combat.stages()) {
      for (OutcomeOption option : stage.outcomes().options()) {
        if (option.id().equals(outcomeId)) {
          return;
        }
      }
    }
    throw new IllegalArgumentException("trigger 引用的结局不在该交战里: " + outcomeId);
  }
}
