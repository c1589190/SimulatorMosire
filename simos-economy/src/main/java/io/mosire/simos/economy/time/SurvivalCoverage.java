package io.mosire.simos.economy.time;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** D3 §2.5.1：逐需求行覆盖、来源与最差瓶颈覆盖率。 */
public record SurvivalCoverage(
    Map<SubsistenceNeedKey, Long> coveredQuantityMilli,
    Map<SubsistenceNeedKey, Long> coveragePerMille,
    Map<SubsistenceNeedKey, SurvivalCoverageLine> provenance,
    long bottleneckCoveragePerMille) {

  public SurvivalCoverage {
    coveredQuantityMilli =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeLong(coveredQuantityMilli, "coveredQuantityMilli")));
    coveragePerMille =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeLong(coveragePerMille, "coveragePerMille")));
    Objects.requireNonNull(provenance, "SurvivalCoverage.provenance 不得为 null");
    LinkedHashMap<SubsistenceNeedKey, SurvivalCoverageLine> lines = new LinkedHashMap<>();
    provenance.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "SurvivalCoverage.provenance 不得含 null 需求键");
          Objects.requireNonNull(value, "SurvivalCoverage.provenance 不得含 null 行");
          lines.put(key, value);
        });
    provenance = Collections.unmodifiableMap(new LinkedHashMap<>(lines));
    if (bottleneckCoveragePerMille < 0L || bottleneckCoveragePerMille > 1000L) {
      throw new IllegalArgumentException(
          "SurvivalCoverage.bottleneckCoveragePerMille 必须在 0..1000: " + bottleneckCoveragePerMille);
    }
  }

  private static Map<SubsistenceNeedKey, Long> freezeLong(
      Map<SubsistenceNeedKey, Long> source, String name) {
    Objects.requireNonNull(source, "SurvivalCoverage." + name + " 不得为 null");
    LinkedHashMap<SubsistenceNeedKey, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "SurvivalCoverage." + name + " 不得含 null 需求键");
          Objects.requireNonNull(value, "SurvivalCoverage." + name + " 不得含 null 数值");
          copy.put(key, value);
        });
    return Collections.unmodifiableMap(copy);
  }
}
