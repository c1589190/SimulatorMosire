package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.StartDecision} 窄工具（T10，spec §四.5，D6 已裁）：**GM 专用**「开始决策」写面。
 *
 * <p>★ **只在 GM 桶**（{@link io.mosire.simos.app.tools.SimosToolSource.Role#GM}）：决策人 Agent
 * 口**没有**此工具（它不参与" 开始决策"，只被异步发起后出令）。标 sensitive ⇒ GM Agent 经 MCP 发起时走**审批门链**（D6②）。
 *
 * <p>★ 用户经 GUI 点 ⇒ 走 {@code POST /api/sd/start-decision}（`initiator="player:gui"`，**直接生效**，不经审批）。
 */
public final class StartDecisionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.StartDecision";

  public StartDecisionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "开始决策 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 开始决策：固定 sd.StartDecision，载荷 {decisionMakerId, note?}";
  }

  @Override
  public ResourceManifest resources() {
    return SD_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐：本工具钉死的 {@code sd.*} 命令只产 {@code SdChangeSet}（实际只写 sd 命名空间），故写断言取 {@code sd:*}（GM 侧
   * unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }
}
