package io.mosire.simos.app.decision;

import io.mosire.agentlib.decision.DecisionReadAudit;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AgentLib {@link DecisionReadAudit} 的 Simos 实现：五工具 {@code full=true} 读取明文时落一条 {@code ga.PutInfo}
 * 审计（只记包 id / index / 读取者 / tick，绝不记参数明文）。
 *
 * <p>审计写失败由 AgentLib 工具层 warn 后放行读取（其契约允许审计降级）；本类不把审计失败伪装成读取失败。
 */
public final class CoreDecisionReadAudit implements DecisionReadAudit {

  private final CoreSimos core;
  private final BranchId branch;
  private final String initiator;

  public CoreDecisionReadAudit(CoreSimos core, BranchId branch, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.branch = Objects.requireNonNull(branch, "branch");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public void detailRead(String packageId, List<Integer> indexes, String by) {
    Objects.requireNonNull(packageId, "packageId");
    List<Integer> safeIndexes = indexes == null ? List.of() : List.copyOf(indexes);
    RevisionId head =
        core.head(branch)
            .orElseThrow(
                () -> new IllegalStateException("决策包明文读取审计失败：分支 " + branch.value() + " 没有 head"));
    long tick =
        core.replay(new io.mosire.simos.util.state.StateRef(branch, head))
            .meta()
            .timestamp()
            .tick();
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("packageId", packageId);
    audit.put("indexes", safeIndexes);
    audit.put("by", by == null ? "" : by);
    audit.put("tick", tick);
    String batchId = UUID.randomUUID().toString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", "ga:decision-read." + batchId);
    payload.put("key", "detailRead");
    payload.put("value", ToolSupport.json(audit));
    payload.put("note", "决策包明文读取审计");
    payload.put("tags", List.of("decision-package", packageId));
    payload.put("tick", tick);
    CommandEnvelope envelope =
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            initiator,
            branch,
            head,
            PutInfoHandler.TYPE,
            ToolSupport.json(payload));
    BatchResult result = core.submitBatch(List.of(envelope));
    if (!(result instanceof BatchResult.Committed)) {
      throw new IllegalStateException("决策包明文读取审计提交失败: " + result);
    }
  }
}
