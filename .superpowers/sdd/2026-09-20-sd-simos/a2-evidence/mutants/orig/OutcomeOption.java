package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.CombatOutcomeId;

/**
 * 结局表条目（spec §三.3，N2）：具名结局 + 权重 + 该结局的战损规模。
 *
 * <p>★ **categorical distribution**：权重 {@code weight > 0}（构造期强制）；"由 LLM 生成候选 + 选定"⇒ 生成候选（入表）与选定 （写
 * {@code CombatState.selectedOutcome}）是两次动作，本类型只承载候选。
 */
public record OutcomeOption(CombatOutcomeId id, String label, int weight, CasualtySpec casualties) {

  public OutcomeOption {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label 不得为空白");
    }
    if (weight <= 0) {
      throw new IllegalArgumentException("weight 必须 > 0: " + weight);
    }
    if (casualties == null) {
      throw new IllegalArgumentException("casualties 不得为 null");
    }
  }
}
