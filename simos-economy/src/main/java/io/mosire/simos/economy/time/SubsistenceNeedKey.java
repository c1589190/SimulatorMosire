package io.mosire.simos.economy.time;

import java.util.Objects;

/** D3 §2.5.1：Social 给出的稳定需求键（商品或营养组）。 */
public record SubsistenceNeedKey(String value) {
  public SubsistenceNeedKey {
    Objects.requireNonNull(value, "SubsistenceNeedKey.value 不得为 null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("SubsistenceNeedKey.value 不得为空白");
    }
  }
}
