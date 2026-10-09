package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.spi.EconomySetCommodityFreightHandler;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.economy.setCommodityFreight}（2026-10-09 用户裁定「甲」后的<b>纠正版</b>；约束设计书 §4.1 修正版）：
 * <b>设置某商品的全局基础运费</b>的 GM 窄写面 —— {@link EconomySetCommodityFreightHandler} 的薄封装（固定命令类型 + 载荷 JSON +
 * 乐观并发）。
 *
 * <p>★★ <b>为什么是薄工具而不是带参数的工具</b>：本命令的载荷契约（商品 id / 基础运费 / 可选 reason）与三条具名拒都写在 handler 类注与 catalog
 * 载荷提示里；逐个列成工具参数只会把契约复制一份到工具面（两处拼写、两处会漂）。本工具只透传 {@code payloadJson}。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（命令本体已注册并登记载荷提示）；命令本身标 {@code
 * GmOnlyCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code
 * DecisionCallerFactory.WHITELIST} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（基础运费表是经济切片的组件）。
 */
public final class EconomySetCommodityFreightTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.setCommodityFreight";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomySetCommodityFreightTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return EconomySetCommodityFreightHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设置商品基础运费 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设置某商品的全局基础运费（GM-only；固定命令 economy.SetCommodityFreight，载荷 JSON）："
        + "{commodityId(必填；grain/cloth/fiber/tool/iron/wood), baseMilli(必填；≥ 0 的毫/单位/程),"
        + " reason?(可选审计文本)}。"
        + "★ 语义（设计书 §4.1 修正版）：商品维 = **基础运费**（面值维，与商品价格无关）—— 单位运费 ="
        + " max(1, ⌈基础费 ×(1000+路线费率)×(1000+承运成本)÷1e6⌉) ⇒ 商品维管**整条运费**（短途长途都管）；"
        + "★ 缺键 ⇒ 现行硬编码分档（粮1/纤维1/布2/工具3；未登记 ⇒ 1）= 现状、未设表的世界逐值不变；"
        + "★ baseMilli = 0 合法 = 免基础费（仍被 max(1,…) 抬到 1 毫）；★ 只写 commodityFreightBaseMilli 一行，"
        + "不动价格/库存/账户/商号/区表；★ 三条具名拒（各自零 revision、head 不动）：unknown-commodity /"
        + " negative-freight（baseMilli < 0）/ freight-unchanged（与现值逐字相同，不做静默幂等）。"
        + "参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。"
        + "★ preview 不在这里：本工具一次提交一条命令（要预览请先用 simos.economy.hex / simos.state.resolve 读现状）。";
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
