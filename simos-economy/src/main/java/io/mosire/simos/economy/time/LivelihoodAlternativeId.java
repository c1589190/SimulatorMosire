package io.mosire.simos.economy.time;

import java.util.Objects;

/** D3 §2.5.1：同一 SurvivalOutlook 请求内稳定、非空白且唯一的替代 id。 */
public record LivelihoodAlternativeId(String value) implements Comparable<LivelihoodAlternativeId> {
  public LivelihoodAlternativeId {
    Objects.requireNonNull(value, "LivelihoodAlternativeId.value 不得为 null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("LivelihoodAlternativeId.value 不得为空白");
    }
  }

  @Override
  public int compareTo(LivelihoodAlternativeId other) {
    return value.compareTo(other.value);
  }
}
