package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DiplomaticEventId;

/**
 * {@link DiplomaticEvent} 的 ID 合成（缺省 id 时的**唯一拼写点**）：形制 {@code diplomatic-event:<tick>#<该 tick
 * 下已有事件数>}。
 *
 * <p>★★ <b>为什么是"该 tick 下已有事件数"而不是全局自增/随机</b>：id 必须是 <b>(base state, 载荷) 的纯函数</b>——同一份 基态 + 同一份无 id
 * 载荷重放/重试必须得到同一 id（铁律 2 的可重放）。序号按"同一 tick 下已有多少条"数出来，同一基态下恒同； 而 tick 进了 id，跨 tick 也不会撞。
 *
 * <p>★ <b>合成撞车仍然 fail-closed</b>：载荷可以显式给任意 id，若它恰好等于本函数会合成的串，命令期在落盘前查出并**响亮拒绝** （见 {@code
 * RecordDiplomaticEventHandler}）——不静默覆盖、也不换一个 id（换 id 会让重放结果不再确定）。
 */
public final class DiplomaticEventIds {

  /** 合成 id 的固定前缀（人可读、且与载荷显式给的自定义 id 可区分）。 */
  public static final String PREFIX = "diplomatic-event:";

  private DiplomaticEventIds() {}

  /**
   * 按 (tick, 该 tick 下追加前的已有事件数) 合成 id。
   *
   * @param tick 事件所属世界日（≥ 0）
   * @param ordinal 同一 tick 下**追加前**的事件数（= 新事件在该 tick 内的下标）
   */
  public static DiplomaticEventId synthesize(long tick, int ordinal) {
    if (tick < 0) {
      throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal 必须 ≥ 0: " + ordinal);
    }
    return DiplomaticEventId.parse(PREFIX + tick + "#" + ordinal);
  }
}
