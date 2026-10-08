package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.spi.EconomyReassignZoneHexesHandler;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.economy.reassignZoneHexes}（阶段 2-B2，2026-10-08；约束设计书 §4.2 / 用户 §1.2「退让市场区、覆盖范围」）：
 * <b>逐格改划</b>的 GM 窄写面 —— {@link EconomyReassignZoneHexesHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★ <b>薄工具而不是带参数的工具</b>：载荷形状的唯一权威是 handler 的类注与 catalog 的载荷提示；把字段逐个搬进工具参数等于把 契约复制一份（两处拼写、两处会漂）。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令本身标 {@code GmOnlyCommand} ⇒
 * 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code DecisionCallerFactory.WHITELIST} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（区表是经济切片的组件）。
 */
public final class EconomyReassignZoneHexesTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.reassignZoneHexes";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomyReassignZoneHexesTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return EconomyReassignZoneHexesHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "市场区改划（退让/覆盖）branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "市场区逐格改划（GM-only；固定命令 economy.ReassignZoneHexes，载荷 JSON）："
        + "{fromZoneId, toZoneId, hexes[{q,r}…], reason?} —— 把格从源区划到目标区（源区少格 = 退让、目标区收格 = 覆盖）。"
        + "★ 具名拒：zone-not-found / same-zone / hex-not-in-from-zone / from-zone-would-be-empty（要撤区走 merge）/"
        + " from-zone-anchor-would-move（锚格是取价点）/ numeraire-mismatch（被划格的计价币必须 = 目标区法定币）。"
        + "★ 半径不参与归属：本命令不改任何区的 radiusHex（成员格是唯一权威，I22）。"
        + "参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。";
  }

  @Override
  public ResourceManifest resources() {
    return ECONOMY_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));
  }
}
