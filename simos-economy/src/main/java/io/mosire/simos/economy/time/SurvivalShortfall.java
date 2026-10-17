package io.mosire.simos.economy.time;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** D3 §2.5.1：逐需求行缺口与最差缺口。 */
public record SurvivalShortfall(
    Map<SubsistenceNeedKey, Long> quantityMilli, long worstShortfallPerMille) {
  public SurvivalShortfall {
    Objects.requireNonNull(quantityMilli, "SurvivalShortfall.quantityMilli 不得为 null");
    LinkedHashMap<SubsistenceNeedKey, Long> copy = new LinkedHashMap<>();
    quantityMilli.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "SurvivalShortfall.quantityMilli 不得含 null 需求键");
          Objects.requireNonNull(value, "SurvivalShortfall.quantityMilli 不得含 null 数值");
          if (value < 0L) {
            throw new IllegalArgumentException("SurvivalShortfall.quantityMilli 不得为负: " + value);
          }
          copy.put(key, value);
        });
    quantityMilli = Collections.unmodifiableMap(copy);
    if (worstShortfallPerMille < 0L || worstShortfallPerMille > 1000L) {
      throw new IllegalArgumentException(
          "SurvivalShortfall.worstShortfallPerMille 必须在 0..1000: " + worstShortfallPerMille);
    }
  }
}
