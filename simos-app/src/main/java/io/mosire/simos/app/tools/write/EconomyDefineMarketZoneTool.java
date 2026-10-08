package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.spi.EconomyDefineMarketZoneHandler;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.economy.defineMarketZone}（阶段 2-B2，2026-10-08；约束设计书 §4.2）：<b>定义市场区</b>的 GM 窄写面 ——
 * {@link EconomyDefineMarketZoneHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★★ <b>为什么是薄工具而不是带参数的工具</b>：区定义一次要给的字段有七个（区 id / 锚格 / 成员格集 / 法定币 / 发行 GOV /
 * 半径 / 原因），逐个列成工具参数只会把命令载荷契约复制一份到工具面（两处拼写、两处会漂）；本工具只透传 {@code payloadJson}，
 * 载荷形状的唯一权威是 handler 的类注与 catalog 的载荷提示。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令本身标 {@code GmOnlyCommand}
 * ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code DecisionCallerFactory.WHITELIST} 也不含本工具。★ 决策人侧的
 * "管理本国市场区"（用户 §1.2 的口岸政策语境）需要身份派生的作用域，与口岸/管制一起做（阶段 3）——本批不给决策人桶开这条口子。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（区表是经济切片的组件）。
 */
public final class EconomyDefineMarketZoneTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.defineMarketZone";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomyDefineMarketZoneTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return EconomyDefineMarketZoneHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "定义市场区 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "定义市场区（GM-only；固定命令 economy.DefineMarketZone，载荷 JSON）："
        + "{zoneId, anchor{q,r}, hexes[{q,r}…], legalTender, govUnitId, radiusHex?, reason?}。"
        + "★ 成员格就此成为**持久状态**（I22：一个 hex 属于哪个区由它给定，不再每轮现算城市+半径）；"
        + "★ 具名拒：zone-already-defined / hex-in-other-zone（一个 hex 至多属于一个区）/ anchor-not-in-hexes /"
        + " anchor-missing-market / currency-not-defined / gov-not-registered / currency-not-issuable"
        + "（发行 GOV 的 issuable 必须含法定币）/ numeraire-mismatch（本区已有市场的格计价币必须 = 法定币）。"
        + "参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。"
        + "★ preview 不在这里：本工具一次提交一条命令（要预览请先用 simos.state.resolve / simos.economy.hex 读现状）。";
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
