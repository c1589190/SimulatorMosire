package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.EffectId;

/**
 * 效果（spec §三.6，R6）：**此时决定、未来发生**——trigger + action + 状态机。
 *
 * <p>★ **效果与执行原文无关**（spec §三.6）：{@code Directive.intentInfoKey} 与 {@code action} 分开存，不互相推导。 ★
 * **效果引用合法性（地址 + 命令白名单）由代码校验，绝不交给 AI**（§八.4），校验在命令期（C4）。
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
