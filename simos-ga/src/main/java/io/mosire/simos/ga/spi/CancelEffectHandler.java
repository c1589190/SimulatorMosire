package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.effect.Effect;
import io.mosire.simos.ga.effect.EffectStatus;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.spi.SdPayloads;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code ga.CancelEffect} 命令的处理器（spec §四，C4）。
 *
 * <pre>{@code
 * {"effectId":"e1"}
 * }</pre>
 *
 * <p>★ 拒绝：不存在；状态非 {@code PLANNED/COMMITTED}（已 FIRED / CANCELLED / EXPIRED 不可取消）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本 handler 自 {@code io.mosire.simos.sd.spi} 迁入本包，命令类型 {@code
 * sd.CancelEffect → ga.CancelEffect}</b>（用户裁定「<b>sd 整个弃用！</b>」）——理由与 {@link RegisterEffectHandler}
 * 的类注同款（"一条命令只写一个命名空间"要求类型前缀与切片同名）。★ 载荷形状（{@code effectId}）、 两条具名拒因文案、可取消状态判据、日志事件名（{@code
 * SD_CANCEL_EFFECT_APPLIED} / {@code SD_CANCEL_EFFECT_REJECTED}）与线格式<b>一字未改</b>；变的只有类型前缀、取数切片（sd →
 * ga）与日志门面 （{@code SdLog → GaLog}、来源表 {@code SD_DECISION → GA_DECISION}）。
 */
public final class CancelEffectHandler implements CommandHandler {

  @Override
  public String type() {
    return "ga.CancelEffect";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
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
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_CANCEL_EFFECT_APPLIED",
                  GaLogSource.GA_DECISION,
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
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withEffects(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "effect", effectForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logRejected}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "SD_CANCEL_EFFECT_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
