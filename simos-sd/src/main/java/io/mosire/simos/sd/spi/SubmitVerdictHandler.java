package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.adjudication.VerdictFreezer;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.SubmitVerdict} 命令的处理器（spec §四 / §八.5，D3）：把裁决结果**冻结**成数据、落进 revision。
 *
 * <pre>{@code
 * {"verdictId":"v1","breakpoint":"D1","subject":"sd:combat.c1",
 *  "payload":"{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],\"rationaleText\":\"…\"}",
 *  "meta":{"model":"…","promptVersion":"…","inputBriefDigest":"…"}}
 * }</pre>
 *
 * <p>★ **N8**：{@code meta} 三字段非空（{@link VerdictMeta} 构造期强制）。
 *
 * <p>★ **N7**：判决落成**数据**（{@link Verdict}，含 {@code atRevision}）；回放不重跑 LLM——见 {@link VerdictFreezer}。
 *
 * <p>拒绝：verdictId 已存在；payload 不过该断点 schema；subject 不在断点裁决面；meta 任一字段空白。
 */
public final class SubmitVerdictHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.SubmitVerdict";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String verdictForLog = null;
    String breakpointForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      VerdictId id = VerdictId.parse(SdPayloads.requireText(payload, "verdictId"));
      verdictForLog = id.value();
      AdjudicationBreakpoint breakpoint =
          AdjudicationBreakpoint.parse(SdPayloads.requireText(payload, "breakpoint"));
      breakpointForLog = breakpoint.value();
      Address subject = SdPayloads.requireAddress(payload, "subject");
      String verdictPayload = SdPayloads.requireText(payload, "payload");
      VerdictMeta meta = requireMeta(payload);

      if (base.verdicts().containsKey(id)) {
        return rejected(
            "判决已存在: " + id.value(), "verdict", verdictForLog, "breakpoint", breakpointForLog);
      }

      RevisionId at = state.meta().ref().revision();
      Verdict verdict = VerdictFreezer.freeze(id, breakpoint, subject, verdictPayload, meta, at);
      Map<VerdictId, Verdict> next = new LinkedHashMap<>(base.verdicts());
      next.put(id, verdict);
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_VERDICT_SUBMITTED",
                  SdLogSource.SD_DECISION,
                  "id",
                  id.value(),
                  "breakpoint",
                  breakpoint.value(),
                  "subject",
                  subject.canonical(),
                  "verdicts",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withVerdicts(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "verdict", verdictForLog, "breakpoint", breakpointForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.decision(),
        SdLogSource.SD_DECISION,
        "SD_SUBMIT_VERDICT_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  private static VerdictMeta requireMeta(JsonNode payload) {
    JsonNode meta = payload.get("meta");
    if (meta == null || meta.isNull() || !meta.isObject()) {
      throw new IllegalArgumentException("字段 meta 必须是 {model,promptVersion,inputBriefDigest} 对象");
    }
    return new VerdictMeta(
        SdPayloads.requireText(meta, "model"),
        SdPayloads.requireText(meta, "promptVersion"),
        SdPayloads.requireText(meta, "inputBriefDigest"));
  }
}
