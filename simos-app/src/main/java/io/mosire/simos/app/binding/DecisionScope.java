package io.mosire.simos.app.binding;

import java.util.Objects;

/**
 * 决策范围（spec §6）：**不透明标签**，M5 只校验非空白，语义留待消费者。
 *
 * <p>★ 有意不做枚举：范围名（战区内、某分支内…）是消费方（Brain/MainMosire）的事，收紧成枚举会让本仓替它做决定。
 */
public record DecisionScope(String value) {

  public DecisionScope {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("DecisionScope 不得为空白");
    }
  }
}
