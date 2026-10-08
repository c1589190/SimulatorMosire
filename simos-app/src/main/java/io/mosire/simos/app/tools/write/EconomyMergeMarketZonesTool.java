package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.spi.EconomyMergeMarketZonesHandler;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.economy.mergeMarketZones}（阶段 2-B2，2026-10-08；约束设计书 §4.2 / 用户 §1.2「合并」）：<b>合并市场区</b>的
 * GM 窄写面 —— {@link EconomyMergeMarketZonesHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★ <b>薄工具而不是带参数的工具</b>：载荷形状的唯一权威是 handler 的类注与 catalog 的载荷提示。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令本身标 {@code GmOnlyCommand}
 * ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code DecisionCallerFactory.WHITELIST} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（区表是经济切片的组件）。
 */
public final class EconomyMergeMarketZonesTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.mergeMarketZones";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomyMergeMarketZonesTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return EconomyMergeMarketZonesHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "合并市场区 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "合并市场区（GM-only；固定命令 economy.MergeMarketZones，载荷 JSON）："
        + "{sourceZoneId, targetZoneId, reason?} —— 源区的成员格与区级官方汇率覆盖并入目标区，**源区随之撤销**"
        + "（\"撤区\"只有这一条路：改划命令明令拒\"把源区格全部划走\"）。"
        + "★ 具名拒：zone-not-found / same-zone / numeraire-mismatch（被并入的格计价币必须 = 目标区法定币；先显式改计价币）/"
        + " official-rate-conflict（同币对两个不同政策价不能同时成立）。"
        + "★ 合并后：锚格 / 法定币 / 发行者取目标区（目标区的钱成为合并区的法定币），声明半径取两区较大者，区级汇率取并集。"
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
