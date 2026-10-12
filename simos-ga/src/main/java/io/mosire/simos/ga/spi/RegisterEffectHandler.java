package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.effect.Action;
import io.mosire.simos.ga.effect.Effect;
import io.mosire.simos.ga.effect.EffectKind;
import io.mosire.simos.ga.effect.EffectPayloads;
import io.mosire.simos.ga.effect.EffectStatus;
import io.mosire.simos.ga.effect.Trigger;
import io.mosire.simos.ga.id.EffectId;
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
 * {@code ga.RegisterEffect} 命令的处理器（spec §四/§三.6，C4）。
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
 * <p>★ **白名单由装配注入**（`Shell` 收全量已注册命令类型后传入，且**排除 `ga.*`**——防自指递归，spec §四）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本 handler 自 {@code io.mosire.simos.sd.spi} 迁入本包，命令类型 {@code
 * sd.RegisterEffect → ga.RegisterEffect}</b>（用户裁定「<b>sd 整个弃用！</b>」）：{@code effects} 表随 {@link
 * Effect} 整族搬进本模块（{@code GaState} 第 7 组件）⇒ <b>一条命令只写一个命名空间</b> （{@code CommandBus.namespaceOf}
 * 取类型前缀）这条硬约束要求改名，否则 {@code GaChangeSet} 会被喂给 {@code SdCodec} 当场炸。
 *
 * <p>★ <b>改名不动语义（逐条对照）</b>：载荷形状（{@code effectId}/{@code kind}/{@code trigger}/{@code action}/
 * {@code createdTick?}）、五条具名拒因文案、日志事件名（{@code SD_EFFECT_REGISTERED} / {@code
 * SD_REGISTER_EFFECT_REJECTED}）、构造期不变量与线格式<b>一字未改</b>；变的只有：① 类型前缀；② 状态取数从 sd 切片改成 {@code
 * GaSnapshots.state(state)}（本模块自己的树）；③ 日志门面 {@code SdLog → GaLog} 与来源表 {@code SD_DECISION →
 * GA_DECISION}（与批次 3a/3b′ 迁移的其余 {@code ga.*} handler 逐字同款， "变的只有 logger 名与来源表"）。
 *
 * <p>★ <b>那条白名单仍是可入队面（66 条）</b>：装配点 {@code Shell} 注入的集合内容一字未动（= {@code
 * CommandFaces.drainableTypes()} 的同源声明面）⇒ 本批"可入队面仍恰 66"这条不变量不受改名影响（2 条改名不改数）。
 */
public final class RegisterEffectHandler implements CommandHandler {

  private final Set<String> allowedCommandTypes;

  public RegisterEffectHandler(Set<String> allowedCommandTypes) {
    this.allowedCommandTypes =
        Set.copyOf(Objects.requireNonNull(allowedCommandTypes, "allowedCommandTypes"));
  }

  @Override
  public String type() {
    return "ga.RegisterEffect";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String effectForLog = null;
    try {
      JsonNode payload = GaPayloads.parse(payloadJson);
      EffectId id = EffectId.parse(GaPayloads.requireText(payload, "effectId"));
      effectForLog = id.value();
      EffectKind kind = EffectPayloads.requireEffectKind(payload, "kind");
      Trigger trigger = EffectPayloads.requireTrigger(payload, "trigger");
      Action action = EffectPayloads.requireAction(payload, "action");
      long createdTick =
          GaPayloads.optionalLong(payload, "createdTick", state.meta().timestamp().tick());
      if (base.effects().containsKey(id)) {
        return rejected("效果已存在: " + id, "effect", effectForLog);
      }
      requireTriggerReferences(state, trigger);
      requireActionReferences(action);
      Map<EffectId, Effect> next = new LinkedHashMap<>(base.effects());
      next.put(id, new Effect(id, kind, trigger, action, EffectStatus.PLANNED, createdTick));
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_EFFECT_REGISTERED",
                  GaLogSource.GA_DECISION,
                  "id",
                  id.value(),
                  "kind",
                  kind,
                  "createdTick",
                  createdTick,
                  "effects",
                  next.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withEffects(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "effect", effectForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GaPayloads#logRejected}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GaPayloads.logRejected(
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "SD_REGISTER_EFFECT_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /** {@code Trigger.UnitAtHex} 引用的单位必须存在（只读 unit 切片；ga → unit 是用户已批的依赖边）。 */
  private static void requireTriggerReferences(SimulationState state, Trigger trigger) {
    switch (trigger) {
      case Trigger.UnitAtHex unitAtHex -> {
        if (!GaSnapshots.units(state).units().containsKey(unitAtHex.unit())) {
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
   * <p>★ <b>2026-10-12 批次 3d-2：本方法由两档收成一档（如实记）</b>——原来还判 {@code Action.SetStage} 与 {@code
   * Action.RecordCasualties}，两者随旧战斗族退役。今天剩 {@code EnqueueUnitCommand}（白名单）与 {@code PutInfo}（地址由
   * {@link io.mosire.simos.util.address.Address} 解析期保证合法）。
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
