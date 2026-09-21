package io.mosire.simos.app.sd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.adjudication.AdjudicationRequest;
import io.mosire.simos.sd.adjudication.AdjudicationSchemas;
import io.mosire.simos.sd.adjudication.Breakpoints;
import io.mosire.simos.sd.adjudication.DecisionAdjudicator;
import io.mosire.simos.sd.adjudication.Judgement;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 断点编排（spec §八.1/§八.2，D7，app 侧）：按 tick 把到点的断点**分组调用** {@link DecisionAdjudicator}，并把**接受的判决** 冻结进
 * revision。
 *
 * <p>★ **D1 + D3 合并为同一次调用**（spec §八.2）：编排按 {@link Breakpoints#callGroups()} 走——due 里含 D1 或 D3
 * 都只触发同一次调用。
 *
 * <p>★ **N13 降级**：某断点失败/弃权 ⇒ 该断点本 tick **无判决**，**tick 继续**（不抛、不中断后续断点）。
 *
 * <p>★ **N14**：本类**不断言 LLM 的选择**——它只做"请求 → 判决 → 落点"，选择由模型给。
 *
 * <p>★ **执行上浮**（N10）：真实 key / 超时 / 重试在注入的 {@code LlmClient} 实现里；测试注 Fake 即完全离线。
 *
 * <p>★ v1 落点：只有**产判决**的断点（D1/D3/D6）经 {@code sd.SubmitVerdict} 自动落盘；其余断点（D2/D4/D5/D7/D8）的接受草案返回给调用方，
 * 由决策 Agent / 人经 {@code sd.IssueDirective} 走同一路径发出（不在此自动落盘——那需要额外的草稿→指令装配）。
 */
public final class AdjudicatorRunner {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final String INITIATOR = "system:adjudicator";

  private final CoreSimos core;
  private final DecisionAdjudicator adjudicator;

  public AdjudicatorRunner(CoreSimos core, DecisionAdjudicator adjudicator) {
    this.core = Objects.requireNonNull(core, "core");
    this.adjudicator = Objects.requireNonNull(adjudicator, "adjudicator");
  }

  /**
   * 跑一遍到点的断点。
   *
   * @param branch 分支
   * @param expectedRevision 起始 base revision（乐观并发；每个落点成功后前移）
   * @param due 本 tick 到点的断点（含 D1 或 D3 都只触发合并调用）
   * @param subjects 各断点的裁决主体地址（仅产判决的断点用；缺省则不落盘）
   * @return 每个调用组的判决（保序）
   */
  public List<Judgement> run(
      BranchId branch,
      RevisionId expectedRevision,
      List<AdjudicationBreakpoint> due,
      Map<AdjudicationBreakpoint, String> subjects) {
    return run(branch, expectedRevision, due, subjects, Map.of());
  }

  /**
   * 同 {@link #run(BranchId, RevisionId, List, Map)}，但**逐断点给脱敏简报**（取代说明，2026-09-22）：D 阶段这里写死 {@code
   * "{}"}，真模型拿不到任何上下文（连 schema 要的 {@code stageId}/{@code selectedOutcomeId} 都没有）⇒ 只能弃权。
   * 本重载把"简报从哪来"交回调用方（app 组合根按世界事实构造事实性简报）；缺省仍为 {@code "{}"}，不替调用方编造。
   *
   * @param briefs 各断点的脱敏简报 JSON；缺项 ⇒ {@code "{}"}（不编造）
   */
  public List<Judgement> run(
      BranchId branch,
      RevisionId expectedRevision,
      List<AdjudicationBreakpoint> due,
      Map<AdjudicationBreakpoint, String> subjects,
      Map<AdjudicationBreakpoint, String> briefs) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(due, "due");
    Objects.requireNonNull(subjects, "subjects");
    Objects.requireNonNull(briefs, "briefs");

    long current = expectedRevision.value();
    List<Judgement> results = new ArrayList<>();
    for (List<AdjudicationBreakpoint> group : Breakpoints.callGroups()) {
      if (group.stream().noneMatch(due::contains)) {
        continue;
      }
      AdjudicationBreakpoint primary = group.get(0);
      AdjudicationRequest request =
          new AdjudicationRequest(
              primary.value(),
              briefs.getOrDefault(primary, "{}"),
              AdjudicationSchemas.schemaJson(primary),
              "[]");
      Judgement judgement = adjudicator.adjudicate(request);
      results.add(judgement);
      if (judgement instanceof Judgement.Accepted accepted
          && Breakpoints.producesVerdict(primary)
          && subjects.containsKey(primary)) {
        current =
            submitVerdict(branch, current, primary, subjects.get(primary), accepted.payloadJson());
      }
    }
    return List.copyOf(results);
  }

  private long submitVerdict(
      BranchId branch,
      long expectedRevision,
      AdjudicationBreakpoint breakpoint,
      String subject,
      String payloadJson) {
    String verdictId = "adjudicated:" + breakpoint.value() + ":" + expectedRevision;
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("model", adjudicator.name());
    meta.put("promptVersion", "v1");
    meta.put("inputBriefDigest", "digest:" + breakpoint.value());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("verdictId", verdictId);
    root.put("breakpoint", breakpoint.value());
    root.put("subject", subject);
    root.put("payload", payloadJson);
    root.put("meta", meta);
    CommandResult result =
        core.submit(
            new CommandEnvelope(
                verdictId,
                verdictId,
                INITIATOR,
                branch,
                new RevisionId(expectedRevision),
                "sd.SubmitVerdict",
                toJson(root)));
    if (result instanceof CommandResult.Committed committed) {
      return committed.ref().revision().value();
    }
    return expectedRevision;
  }

  private static String toJson(Object view) {
    try {
      return MAPPER.writeValueAsString(view);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("判决载荷序列化失败: " + e.getOriginalMessage(), e);
    }
  }
}
