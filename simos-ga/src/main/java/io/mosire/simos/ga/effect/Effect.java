package io.mosire.simos.ga.effect;

import io.mosire.simos.ga.id.EffectId;

/**
 * 效果（spec §三.6，R6）：**此时决定、未来发生**——trigger + action + 状态机。
 *
 * <p>★ **效果与执行原文无关**（spec §三.6）：{@code Directive.intentInfoKey} 与 {@code action} 分开存，不互相推导。 ★
 * **效果引用合法性（地址 + 命令白名单）由代码校验，绝不交给 AI**（§八.4），校验在命令期（C4）。
 *
 * <p>★ {@code createdTick} = 效果创建时的**世界日**（单位：日，2026-09-24 日制裁定；结构不变）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类型自 {@code io.mosire.simos.sd.model} <b>整族迁入本包</b></b>（用户裁定「<b>sd
 * 整个弃用！</b>」＋「<b>那让它依赖unit呗</b>」）：{@code effects} 表随之成为 {@code GaState} 的第 7 个组件，登记/取消/推进
 * 三条生产路径（{@code ga.RegisterEffect} / {@code ga.CancelEffect} / {@link GaTimeParticipant}）也一并搬进
 * {@code simos-ga}。<b>形状与语义一字未改</b>（record 组件、构造期判据、线格式逐字不变），改的只是包名与家。
 *
 * <p>★ <b>{@code EffectId} 仍住 {@code simos-sd}</b>（{@code io.mosire.simos.sd.id.EffectId}）：{@code
 * Directive.effects()}（值类型仍住 sd）以它为字段类型，搬它就会造出 {@code sd → ga} 的环（enforcer 当场拒）⇒ 由本模块
 * <b>反向引用</b>它（既有的 {@code ga → sd} 方向，不新增边）。★ 值类型与状态组件的去留口径见 {@code GaState} 的类注。
 */
public record Effect(
    EffectId id,
    EffectKind kind,
    Trigger trigger,
    Action action,
    EffectStatus status,
    long createdTick) {

  public Effect {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind 不得为 null");
    }
    if (trigger == null) {
      throw new IllegalArgumentException("trigger 不得为 null");
    }
    if (action == null) {
      throw new IllegalArgumentException("action 不得为 null");
    }
    if (status == null) {
      throw new IllegalArgumentException("status 不得为 null");
    }
    if (createdTick < 0) {
      throw new IllegalArgumentException("createdTick 必须 ≥ 0: " + createdTick);
    }
  }
}
