package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectKind;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
    String effectForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      EffectId id = EffectId.parse(SdPayloads.requireText(payload, "effectId"));
      effectForLog = id.value();
      EffectKind kind = SdPayloads.requireEffectKind(payload, "kind");
      Trigger trigger = SdPayloads.requireTrigger(payload, "trigger");
      Action action = SdPayloads.requireAction(payload, "action");
      long createdTick =
          SdPayloads.optionalLong(payload, "createdTick", state.meta().timestamp().tick());
      if (base.effects().containsKey(id)) {
        return rejected("效果已存在: " + id, "effect", effectForLog);
      }
      requireTriggerReferences(state, trigger);
      requireActionReferences(action);
      Map<EffectId, Effect> next = new LinkedHashMap<>(base.effects());
      next.put(id, new Effect(id, kind, trigger, action, EffectStatus.PLANNED, createdTick));
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_EFFECT_REGISTERED",
                  SdLogSource.SD_DECISION,
                  "id",
                  id.value(),
                  "kind",
                  kind,
                  "createdTick",
                  createdTick,
                  "effects",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withEffects(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "effect", effectForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.decision(),
        SdLogSource.SD_DECISION,
        "SD_REGISTER_EFFECT_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  private static void requireTriggerReferences(SimulationState state, Trigger trigger) {
    switch (trigger) {
      case Trigger.UnitAtHex unitAtHex -> {
        if (!SdSnapshots.unitExists(state, unitAtHex.unit())) {
          throw new IllegalArgumentException("trigger 引用的单位不存在: " + unitAtHex.unit());
        }
      }
      case Trigger.And and -> {
        for (Trigger child : and.all()) {
          requireTriggerReferences(state, child);
        }
      }
      case Trigger.Or or -> {
        for (Trigger child : or.any()) {
          requireTriggerReferences(state, child);
        }
      }
      default -> {
        // AtOrAfterTick / AfterTicks 不引用实体
      }
    }
  }

  /**
   * 动作引用的合法性（§八.4：效果引用合法性由代码判）。
   *
   * <p>★ <b>2026-10-12 批次 3d-2：本方法由两档收成一档（如实记）</b>——原来还判 {@code Action.SetStage}（引 {@code
   * CombatState} / 阶段）与 {@code Action.RecordCasualties}（引 {@code LossRecord}），两者随旧战斗族退役。 今天剩 {@code
   * EnqueueUnitCommand}（白名单）与 {@code PutInfo}（地址由 {@link io.mosire.simos.util.address.Address}
   * 解析期保证合法）。★ 同批：本方法的两个参数 {@code state} 与 {@code base}（sd
   * 状态树）随那两档一起取下——因为它们只被那两档读；方法本身仍<b>是实例方法</b>（白名单 {@code allowedCommandTypes} 是实例字段，只有 {@code
   * state} 参数被取掉）。
   */
  private void requireActionReferences(Action action) {
    switch (action) {
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
}
