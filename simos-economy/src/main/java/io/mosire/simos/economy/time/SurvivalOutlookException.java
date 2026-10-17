package io.mosire.simos.economy.time;

import java.util.Objects;

/** SurvivalOutlook 的具名契约失败；{@link #reason()} 是 D3 §8.1 的稳定词。 */
public final class SurvivalOutlookException extends IllegalArgumentException {
  private final String reason;

  public SurvivalOutlookException(String reason, String detail) {
    super(reason + (detail == null || detail.isBlank() ? "" : ": " + detail));
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public String reason() {
    return reason;
  }
}
