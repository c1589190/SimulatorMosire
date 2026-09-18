package io.mosire.simos.app.binding;

/**
 * 绑定的生效方式（spec §6）：只出建议，还是自动施加。
 *
 * <p>★ **M5 只记录，不执行**：绑定后的决策人产出 → Command 由 Brain/MainMosire 消费。这个枚举是**给消费者的契约**， 本仓不解释它的行为。
 */
public enum BindingMode {
  SUGGEST_ONLY,
  AUTO_APPLY
}
