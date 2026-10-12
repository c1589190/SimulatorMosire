package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code sd.StartDecision} 窄工具（T10，spec §四.5，D6 已裁）：**GM 专用**「开始决策」写面。
 *
 * <p>★ **只在 GM 桶**（{@code SimosToolSource.Role#GM}，装配面在 app）：决策人 Agent 口**没有**此工具（它不参与"
 * 开始决策"，只被异步发起后出令）。标 sensitive ⇒ GM Agent 经 MCP 发起时走**审批门链**（D6②）。
 *
 * <p>★ 用户经 GUI 点 ⇒ 走 {@code POST /api/sd/start-decision}（`initiator="player:gui"`，**直接生效**，不经审批）。
 */
public final class StartDecisionTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "sd.StartDecision";

  public StartDecisionTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
