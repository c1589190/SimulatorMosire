package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.state.SdState;
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
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      EffectId id = EffectId.parse(SdPayloads.requireText(payload, "effectId"));
      Effect effect = base.effects().get(id);
      if (effect == null) {
        return new HandlerOutcome.Rejected("效果不存在: " + id);
      }
      if (effect.status() != EffectStatus.PLANNED && effect.status() != EffectStatus.COMMITTED) {
        return new HandlerOutcome.Rejected("效果状态不可取消: " + effect.status());
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
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withEffects(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
