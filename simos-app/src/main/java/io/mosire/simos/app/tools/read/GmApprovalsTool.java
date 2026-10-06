package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.gm.approvals}（P7b，2026-10-01 后端 + MCP 稳定化计划）：**待裁决审批清单的 GM-only MCP 读口**。
 *
 * <p>补的是 GUI/外部 GM 已能用、MCP 口此前够不着的 {@code GET /api/approvals}（ {@code
 * ApprovalHttpEndpoint.handleList} 的等价面）。数据源是**进程内唯一**的 {@link PendingApprovals}，每次执行直接读 {@link
 * PendingApprovals#pending()}，**不缓存副本**：审批项会被人/超时摘除， 副本会与真实待裁决集合分叉。
 *
 * <p>★★ **输出与 HTTP 面逐字同形**：{@code
 * {"pending":[{id,tool,classKey,summary,digest,createdAtEpochMs,deadlineEpochMs}]}}。这里只做字段搬运，
 * 不额外拼参数、不加工 summary（summary 由工具自报，AgentLib 契约）。
 *
 * <p>★★ **只在 GM 桶**（{@link GmOnlyRead}）：审批队列是**控制面**（谁在等谁点头），不是世界状态；决策人桶不该看见别的主体 的待裁决项。资源声明取 {@link
 * ResourceManifest#NONE}：它**不读任何世界命名空间**，也不落世界 revision。
 *
 * <p>★ **未接入审批面（装配未传 {@link PendingApprovals}）⇒ 具名 {@code UNAVAILABLE}**，不静默回空列表当"没有待裁决项"。
 */
public final class GmApprovalsTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.approvals";

  /** 审批事件的发射通道（分类 = {@link AppLog#approval()}，来源 = {@link AppLogSource#APPROVAL}）。 */
  private static final LogChannel APPROVAL = EventLog.channel(AppLog.approval());

  private final PendingApprovals pending;

  /** {@code pending} 允许为 null（未接入审批面的装配）⇒ 执行期回可读的 {@code UNAVAILABLE}，不静默给空。 */
  public GmApprovalsTool(PendingApprovals pending) {
    this.pending = pending;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "待裁决审批清单（GM 专用，控制面，不落世界 revision）："
        + "{pending:[{id,tool,classKey,summary,digest,createdAtEpochMs,deadlineEpochMs}]}；"
        + "未接入审批面 ⇒ UNAVAILABLE。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(new LinkedHashMap<>(), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ResourceManifest.NONE;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    if (pending == null) {
      APPROVAL.debug(
          LogEvent.of(
              "APPROVAL_LIST_UNAVAILABLE",
              AppLogSource.APPROVAL,
              "tool",
              NAME,
              "reason",
              "UNAVAILABLE"));
      return ToolResult.error("UNAVAILABLE", "审批面未接入（PendingApprovals 缺席）");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ApprovalRequest request : pending.pending()) {
      // ★ 字段名与顺序照 ApprovalHttpEndpoint.handleList 的 LinkedHashMap 形态；不缓存、不加工。
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", request.id());
      row.put("tool", request.tool());
      row.put("classKey", request.classKey());
      row.put("summary", request.summary());
      row.put("digest", request.digest());
      row.put("createdAtEpochMs", request.createdAtEpochMs());
      row.put("deadlineEpochMs", request.deadlineEpochMs());
      rows.add(row);
    }
    if (rows.isEmpty()) {
      // ★ 控制方默认：读口只在空候选时记 DEBUG，不逐次记。
      APPROVAL.debug(
          LogEvent.of("APPROVAL_LIST_EMPTY", AppLogSource.APPROVAL, "tool", NAME, "pending", 0));
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("pending", rows);
    return ToolSupport.ok(view);
  }
}
