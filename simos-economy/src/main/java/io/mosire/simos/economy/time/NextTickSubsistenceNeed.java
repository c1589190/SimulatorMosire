package io.mosire.simos.economy.time;

import java.util.List;
import java.util.Objects;

/** D3 §2.5.1：Social 权威投影出的下一 tick 生存需求。 */
public record NextTickSubsistenceNeed(long forTick, List<SubsistenceNeedLine> lines) {
  public NextTickSubsistenceNeed {
    if (forTick < 0L) {
      throw new IllegalArgumentException("NextTickSubsistenceNeed.forTick 不得为负: " + forTick);
    }
    Objects.requireNonNull(lines, "NextTickSubsistenceNeed.lines 不得为 null（无需求给空表）");
    lines = List.copyOf(lines);
  }
}
