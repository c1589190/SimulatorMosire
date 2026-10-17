package io.mosire.simos.economy.time;

import java.util.Objects;

/** D3 §2.5.1：组合内互斥的资源/占用稳定键。 */
public record ResourceClaimKey(String value) {
  public ResourceClaimKey {
    Objects.requireNonNull(value, "ResourceClaimKey.value 不得为 null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("ResourceClaimKey.value 不得为空白");
    }
  }
}
