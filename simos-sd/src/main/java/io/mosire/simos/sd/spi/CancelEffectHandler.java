package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.CancelEffect} 命令的处理器（spec §四，C4）。
 *
 * <pre>{@code
 * {"effectId":"e1"}
 * }</pre>
 *
 * <p>★ 拒绝：不存在；状态非 {@code PLANNED/COMMITTED}（已 FIRED / CANCELLED / EXPIRED 不可取消）。
 */
public final class CancelEffectHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.CancelEffect";
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
      Effect effect = base.effects().get(id);
      if (effect == null) {
        return rejected("效果不存在: " + id, "effect", effectForLog);
      }
      if (effect.status() != EffectStatus.PLANNED && effect.status() != EffectStatus.COMMITTED) {
        return rejected(
            "效果状态不可取消: " + effect.status(), "effect", effectForLog, "status", effect.status());
      }
      Map<EffectId, Effect> next = new LinkedHashMap<>(base.effects());
      next.put(
          id,
          new Effect(
              effect.id(),
              effect.kind(),
              effect.trigger(),
              effect.action(),
              EffectStatus.CANCELLED,
              effect.createdTick()));
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_CANCEL_EFFECT_APPLIED",
                  SdLogSource.SD_DECISION,
                  "effect",
                  id.value(),
                  "kind",
                  effect.kind(),
                  "fromStatus",
                  effect.status(),
                  "toStatus",
                  EffectStatus.CANCELLED,
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
        "SD_CANCEL_EFFECT_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
