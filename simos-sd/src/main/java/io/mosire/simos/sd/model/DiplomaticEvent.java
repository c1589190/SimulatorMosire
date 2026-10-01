package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一条**外交事件记录**（D-005 / R6）：多国谈判/联动裁决时，逐 tick 记下"哪些国家参与、具体是什么"（自然语言）。
 *
 * <p>字段：
 *
 * <ul>
 *   <li>{@link #id}——事件 id（唯一键；同时是 {@code SdState.diplomaticEvents} 的 map key）；
 *   <li>{@link #tick}——该事件所属世界日（单位：日），与 {@code SdInfoEntry.tick} 同口径；
 *   <li>{@link #participants}——参与国（{@link NationId}）列表，**至少 2 个、不得重复**（D-005 的"多国谈判"；双边状态走
 *       关系边，不走本类型）；顺序按载荷给定保留；
 *   <li>{@link #text}——具体内容，自然语言。
 * </ul>
 *
 * <p>★ 同一 tick 允许多条事件（逐条 append，不覆盖）：本类型没有"一 tick 一条"的不变量。
 */
public record DiplomaticEvent(
    DiplomaticEventId id, long tick, List<NationId> participants, String text) {

  public DiplomaticEvent {
    if (id == null) {
      throw new IllegalArgumentException("外交事件 id 不得为 null");
    }
    if (tick < 0) {
      throw new IllegalArgumentException("外交事件 tick 必须 ≥ 0: " + tick);
    }
    if (participants == null) {
      throw new IllegalArgumentException("外交事件 participants 不得为 null");
    }
    if (participants.size() < 2) {
      throw new IllegalArgumentException("外交事件 participants 至少 2 个（多国谈判）: " + participants.size());
    }
    LinkedHashSet<NationId> unique = new LinkedHashSet<>();
    for (NationId participant : participants) {
      if (participant == null) {
        throw new IllegalArgumentException("外交事件 participants 不得含 null");
      }
      if (!unique.add(participant)) {
        throw new IllegalArgumentException("外交事件 participants 不得重复: " + participant.value());
      }
    }
    participants = List.copyOf(unique); // ★ 冻在赋值处（保序、不可变）
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("外交事件的 text 不得为空白（自然语言内容是本记录的本体）");
    }
  }
}
