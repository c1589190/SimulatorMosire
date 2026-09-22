package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code map.SetEdge} 窄工具（M1，spec §八.3）：**GM 改连通性**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **{@code mode} 没有默认值**（域层 {@code EdgeOperations} 只收 {@code replace}/{@code merge}）：缺字段 ⇒
 * {@code Rejected}，不兜一个"看着合理"的模式。{@code kind} 必须在状态的通路组词表内（fail-closed）。
 */
public final class MapSetEdgeTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.SetEdge";

  public MapSetEdgeTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改连通性 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 改连通性：固定 map.SetEdge，载荷 {kind, edges[\"q_r|q_r\"…], mode（replace|merge，无默认）}";
  }
}
