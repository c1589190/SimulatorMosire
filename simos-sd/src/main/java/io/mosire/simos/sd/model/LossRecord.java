package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;

/**
 * 损失记录（spec §三.4，N3）：供回放 / AAR。记录 {@code atRevision} ⇒ 可回放且**逐值**相等。
 *
 * <p>★ {@code deltas} 保序不可变（{@code List.copyOf} 保序，且拒绝 null 元素）。
 */
public record LossRecord(
    LossRecordId id,
    CombatId combat,
    CombatStageId stage,
    RevisionId atRevision,
    List<CasualtyDelta> deltas) {

  public LossRecord {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (combat == null) {
      throw new IllegalArgumentException("combat 不得为 null");
    }
    if (stage == null) {
      throw new IllegalArgumentException("stage 不得为 null");
    }
    if (atRevision == null) {
      throw new IllegalArgumentException("atRevision 不得为 null");
    }
    if (deltas == null) {
      throw new IllegalArgumentException("deltas 不得为 null");
    }
    deltas = List.copyOf(deltas);
  }
}
