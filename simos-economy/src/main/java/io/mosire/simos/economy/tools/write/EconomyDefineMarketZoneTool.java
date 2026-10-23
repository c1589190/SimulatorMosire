package io.mosire.simos.economy.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.spi.EconomyDefineMarketZoneHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ {@code simos.economy.defineMarketZone}（阶段 2-B2，2026-10-08；约束设计书 §4.2）：<b>定义市场区</b>的 GM 窄写面 ——
 * {@link EconomyDefineMarketZoneHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★★ <b>为什么是薄工具而不是带参数的工具</b>：区定义一次要给的字段有七个（区 id / 锚格 / 成员格集 / 法定币 / 发行 GOV / 半径 /
 * 原因），逐个列成工具参数只会把命令载荷契约复制一份到工具面（两处拼写、两处会漂）；本工具只透传 {@code payloadJson}， 载荷形状的唯一权威是 handler 的类注与
 * catalog 的载荷提示。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令本身不打 {@code DmUsableCommand}
 * ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；决策人白名单（派生自决策人桶） 也不含本工具。★ 决策人侧的 "管理本国市场区"（用户 §1.2
 * 的口岸政策语境）需要身份派生的作用域，与口岸/管制一起做（阶段 3）——本批不给决策人桶开这条口子。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（区表是经济切片的组件）。
 */
public final class EconomyDefineMarketZoneTool extends AbstractNarrowWriteTool
    implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.defineMarketZone";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomyDefineMarketZoneTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
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
    return "定义市场区（GM-only；固定命令 economy.DefineMarketZone，载荷 JSON）：{zoneId, anchor{q,r},"
        + " hexes[{q,r}…], legalTender, radiusHex?, reason?}。★ 成员格就此成为**持久状态**（I22：一个 hex"
        + " 属于哪个区由它给定，不再每轮现算城市+半径）；★ 具名拒：zone-already-defined / hex-in-other-zone（一个 hex"
        + " 至多属于一个区）/ anchor-not-in-hexes / anchor-missing-market / currency-not-defined /"
        + " numeraire-mismatch（本区已有市场的格计价币必须 = 法定币）。★★ 2026-10-09 C 批：载荷**没有"
        + " govUnitId**（区不再记发行政府 —— 谁管这种钱由 Government.issuable 反查；仍带 govUnitId ⇒"
        + " bad-payload）；立区也不看\"谁发得出\"（gov-not-registered / currency-not-issuable 已退役）。参数"
        + " {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。★ preview"
        + " 不在这里：本工具一次提交一条命令（要预览请先用 simos.state.resolve / simos.economy.hex 读现状）。";
  }

  @Override
  public ResourceManifest resources() {
    return ECONOMY_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return List.of(ResourceId.of(ToolResources.ECONOMY_NAMESPACE, "*"));
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
