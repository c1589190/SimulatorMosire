package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.adjudication.VerdictFreezer;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.sd.spi.SdPayloads;
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
 * {@code ga.SubmitVerdict} 命令的处理器（spec §四 / §八.5，D3；<b>★ 2026-10-12 批次 3b′ 自 {@code
 * simos-sd/sd.spi} 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）：把裁决结果**冻结**成数据、落进 revision。
 *
 * <pre>{@code
 * {"verdictId":"v1","breakpoint":"D1","subject":"army:combat.c1",
 *  "payload":"{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],\"rationaleText\":\"…\"}",
 *  "meta":{"model":"…","promptVersion":"…","inputBriefDigest":"…"}}
 * }</pre>
 *
 * <p>★ **N8**：{@code meta} 三字段非空（{@link VerdictMeta} 构造期强制）。
 *
 * <p>★ **N7**：判决落成**数据**（{@link Verdict}，含 {@code atRevision}）；回放不重跑 LLM——见 {@link VerdictFreezer}。
 *
 * <p>★ <b>本命令只写 {@code verdicts} 一张表</b>（{@code GaChangeSet}）：判决没有配套的 INFO 条目，故不存在 {@code
 * ga.IssueDirective} 那种"跨命名空间第二条命令"的形态。
 *
 * <p>拒绝：verdictId 已存在；payload 不过该断点 schema；subject 不在断点裁决面；meta 任一字段空白。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：示例里的战斗 subject 由 {@code sd:combat.c1} 改为 {@code army:combat.c1}</b>
 * （判决面重挂，零逻辑改注释）——旧战斗族整族退役、交战记录归 army；D1/D3/D6 的 subject 现在必须落在 {@code army:combat.*}（判据在 {@code
 * Breakpoints.acceptsVerdictSubject}）。★ 留着旧串会让照抄示例的人<b>必然</b>撞 "subject 不在裁决面"的具名拒。
 */
public final class SubmitVerdictHandler implements CommandHandler {

  @Override
  public String type() {
    return "ga.SubmitVerdict";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
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
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_VERDICT_SUBMITTED",
                  GaLogSource.GA_DECISION,
                  "id",
                  id.value(),
                  "breakpoint",
                  breakpoint.value(),
                  "subject",
                  subject.canonical(),
                  "verdicts",
                  next.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withVerdicts(next)));
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
        GaLog.decision(),
        GaLogSource.GA_DECISION,
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
