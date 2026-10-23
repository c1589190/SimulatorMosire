package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.model.MergedEffect;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 合并效果集命令载荷与窄写结局折叠（设计书 §6.2：旧的 upsert/submit/decide 决策包载荷已删除）。
 *
 * <p>决策包的写入只有一个内部命令 {@code ga.ApplyDecisionPackage}；其载荷编码/解码由 {@link
 * io.mosire.simos.ga.codec.GaCodec} 统一持有。本类不再保留任何旧决策包写路径。
 */
public final class DecisionPacketPayloads {

  private DecisionPacketPayloads() {}

  /** {@code ga.UpsertMergedEffectPlan} 的整包扁平载荷（GM 建计划 / 执行器 outcome 回写共用）。 */
  public static String mergedPlan(MergedEffectPlan plan) {
    Objects.requireNonNull(plan, "plan");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", plan.id().value());
    payload.put("tick", plan.tick());
    payload.put("participantIds", plan.participantIds().stream().map(id -> id.value()).toList());
    List<Map<String, Object>> effects = new ArrayList<>(plan.orderedEffects().size());
    for (MergedEffect effect : plan.orderedEffects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("toolName", effect.toolName());
      row.put("argsJson", effect.argsJson());
      row.put("sourceCallRefs", effect.sourceCallRefs());
      effects.add(row);
    }
    payload.put("orderedEffects", effects);
    payload.put("sources", plan.sources());
    payload.put("reasonInfoId", plan.reasonInfoId().orElse(null));
    payload.put("outcome", plan.outcome().orElse(null));
    return ToolResults.json(payload);
  }

  /** 命令三结局的统一折叠（只服务合并计划写工具；决策包写见 DecisionPackageWriteSupport）。 */
  public static ToolResult fold(
      ToolWritePort.Outcome outcome, Map<String, Object> view, String commandId) {
    return switch (outcome) {
      case ToolWritePort.Outcome.Committed committed -> {
        view.put("submission", ToolResults.committedView(committed.ref(), commandId, commandId));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.Outcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }
}
