package io.mosire.simos.economy.api.inventory;

import java.util.Optional;

/**
 * lot 的来源审计记录。
 *
 * @param kind 来源分类
 * @param sourceLotId SPLIT / TRANSFERRED 的来源 lot；无来源时为空
 * @param runId PRODUCED 的生产批次；无批次时为空
 * @param tick 发生日
 */
public record LotOrigin(
    LotOriginKind kind, Optional<InventoryLotId> sourceLotId, Optional<String> runId, long tick) {

  public LotOrigin {
    if (kind == null) {
      throw new IllegalArgumentException("LotOrigin.kind 不得为 null");
    }
    if (sourceLotId == null) {
      throw new IllegalArgumentException("LotOrigin.sourceLotId 不得为 null（无来源用 Optional.empty()）");
    }
    if (runId == null) {
      throw new IllegalArgumentException("LotOrigin.runId 不得为 null（无批次用 Optional.empty()）");
    }
  }

  /** 当前存量快照：没有来源 lot、没有生产批次。 */
  public static LotOrigin currentStock(long tick) {
    return new LotOrigin(LotOriginKind.CURRENT_STOCK, Optional.empty(), Optional.empty(), tick);
  }
}
