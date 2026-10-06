package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticEventIds;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sd.RecordDiplomaticEvent} 命令的处理器（D-005 / R6）：**追加一条外交事件记录**（多国谈判逐 tick 记录参与国与内容）。
 *
 * <pre>{@code
 * {"eventId":"e-1","tick":120,"participants":["大蜀","西陵"],"text":"……自然语言……"}
 * }</pre>
 *
 * <ul>
 *   <li>{@code participants} 必填数组，**至少 2 个、不得重复**（每个元素解析成 {@link NationId}，保载荷顺序）。★ 本层只判
 *       **形状与去重**，不查这些 Nation 是否存在——存在性校验是 {@code sd.SetDiplomaticRelation} 的边端点语义；事件是"当时谁参与了"
 *       的记录（D-005 原文只要求参与国与内容），本阶段不发明额外约束（见报告"待裁定"）；
 *   <li>{@code text} 必填非空白：具体内容，自然语言；
 *   <li>{@code tick} 可选，缺省 = 世界当前 tick；**不得记在未来**（同 {@code sd.PutInfo}/{@code
 *       sd.IssueDirective}）；过去合法；
 *   <li>{@code eventId} 可选：给了就用它，与既有事件**撞 id 即具名拒**（不覆盖）；缺省走 {@link
 *       DiplomaticEventIds#synthesize}（{@code diplomatic-event:<tick>#<该 tick 下已有事件数>}，纯函数、可重放）。 合成
 *       id 仍会查一次撞车（显式 id 可能恰好长成合成串）⇒ 撞车响亮拒，不静默改写 id。
 * </ul>
 *
 * <p>★ **非 GmOnly**：与 {@code sd.SetDiplomaticRelation} 同待遇——身份约束在决策人窄工具层；{@code sd.*} 不进指令白名单。
 */
public final class RecordDiplomaticEventHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.RecordDiplomaticEvent";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String eventForLog = null;
    Long tickForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      String text = SdPayloads.requireText(payload, "text");
      List<NationId> participants = parseParticipants(payload);
      long worldTick = state.meta().timestamp().tick();
      long tick = SdPayloads.optionalLong(payload, "tick", worldTick);
      tickForLog = tick;
      if (tick < 0L) {
        return rejected("外交事件 tick 不得为负: " + tick, "event", "-", "tick", tickForLog);
      }
      if (tick > worldTick) {
        return rejected(
            "外交事件不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick,
            "event",
            "-",
            "tick",
            tickForLog);
      }
      Map<DiplomaticEventId, DiplomaticEvent> next = new LinkedHashMap<>(base.diplomaticEvents());
      Optional<String> explicitId = SdPayloads.optionalText(payload, "eventId");
      DiplomaticEventId id;
      if (explicitId.isPresent()) {
        id = DiplomaticEventId.parse(explicitId.get());
        eventForLog = id.value();
        if (next.containsKey(id)) {
          return rejected("外交事件 id 已存在: " + id.value(), "event", eventForLog, "tick", tickForLog);
        }
      } else {
        id = DiplomaticEventIds.synthesize(tick, countEventsAt(next, tick));
        eventForLog = id.value();
        if (next.containsKey(id)) {
          // 显式 id 恰好长成合成串时可能命中；响亮拒（不静默换 id——换了重放结果就不再是载荷的纯函数）。
          return rejected(
              "合成外交事件 id 已存在（请给显式 eventId 避开该串）: " + id.value(),
              "event",
              eventForLog,
              "tick",
              tickForLog);
        }
      }
      next.put(id, new DiplomaticEvent(id, tick, participants, text));
      EventLog.channel(SdLog.diplomacy())
          .info(
              LogEvent.of(
                  "SD_DIPLOMATIC_EVENT_RECORDED",
                  SdLogSource.SD_DIPLOMACY,
                  "id",
                  id.value(),
                  "tick",
                  tick,
                  "participants",
                  participants.size(),
                  "events",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDiplomaticEvents(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "event", eventForLog, "tick", tickForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.diplomacy(),
        SdLogSource.SD_DIPLOMACY,
        "SD_RECORD_DIPLOMATIC_EVENT_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /**
   * {@code participants}：必须是数组、元素非空白字符串、解析成 {@link NationId} 后**不重复**，且**至少 2 个**。
   *
   * <p>★ 不用 {@link SdPayloads#requireTextSet}（那个会把重复**静默吞掉**）：D-005 要的是"哪些国家参与"，重复参与国是一条坏输入， 应当响亮拒。
   */
  private static List<NationId> parseParticipants(JsonNode payload) {
    JsonNode value = payload.get("participants");
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 participants 必须是 [字符串…] 数组: " + payload);
    }
    List<NationId> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("participants 的元素必须是非空白字符串: " + element);
      }
      NationId nation = NationId.parse(element.asText());
      if (!seen.add(nation.value())) {
        throw new IllegalArgumentException("participants 不得重复: " + nation.value());
      }
      out.add(nation);
    }
    if (out.size() < 2) {
      throw new IllegalArgumentException("participants 至少 2 个（多国谈判）: " + out.size());
    }
    return List.copyOf(out);
  }

  /** 该 tick 下已有事件数（合成 id 的序号；纯函数，不依赖 map 迭代序）。 */
  private static int countEventsAt(Map<DiplomaticEventId, DiplomaticEvent> events, long tick) {
    int count = 0;
    for (DiplomaticEvent event : events.values()) {
      if (event.tick() == tick) {
        count++;
      }
    }
    return count;
  }
}
