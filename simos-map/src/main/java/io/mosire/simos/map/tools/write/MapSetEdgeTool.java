package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.SetEdge} 窄工具（M1，spec §八.3）：**GM 改连通性**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **{@code mode} 没有默认值**（域层 {@code EdgeOperations} 只收 {@code replace}/{@code merge}）：缺字段 ⇒
 * {@code Rejected}，不兜一个"看着合理"的模式。{@code kind} 必须在状态的通路组词表内（fail-closed）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapSetEdgeTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "map.SetEdge";

  public MapSetEdgeTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
