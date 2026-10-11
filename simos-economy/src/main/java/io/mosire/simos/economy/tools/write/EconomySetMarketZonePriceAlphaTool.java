package io.mosire.simos.economy.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.spi.EconomySetMarketZonePriceAlphaHandler;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.economy.setMarketZonePriceAlpha}（W2，2026-10-11）：<b>设置市场区动态价步长 α</b> 的 GM 窄写面 ——
 * {@link EconomySetMarketZonePriceAlphaHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★★ <b>它为什么必须存在</b>：约束设计书 §2.1 的 α 是逐区、每市场轮"价格最多动多少"的步长； 口径溯源 §4.1 记着用户 2026-09-25 的裁定「凡是行为一律做成
 * GM 可调的预设」⇒ α <b>必须可调</b>。 本工具就是那个写口的 app 侧形态（与 {@code EconomySetCommodityFreightTool} 同款： 只透传
 * {@code payloadJson}，载荷形状的唯一权威 = handler 的类注与 catalog 的载荷提示）。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令本身标 {@code GmOnlyCommand} ⇒
 * 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录； {@code DecisionCallerFactory.WHITELIST} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（区表是经济切片的组件）。
 */
public final class EconomySetMarketZonePriceAlphaTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.setMarketZonePriceAlpha";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomySetMarketZonePriceAlphaTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return EconomySetMarketZonePriceAlphaHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设置市场区动态价步长 α branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设置某个市场区的动态价步长 α（GM-only；固定命令 economy.SetMarketZonePriceAlpha，载荷 JSON）："
        + "{marketId, alphaPerMille}。α = **每市场轮价格最多变动的千分比**（缺货涨、滞销跌都以它封顶；"
        + "缺省 50‰/轮，由 MarketZone.priceAlphaPerMille 承载）。★ 不设上下限（除 α ≥ 0）：可以远大于 50"
        + "（价格一步到位），也可以取 0（该区价格从此不随供需变动）。★ 具名拒：zone-missing（派生市场没有区可挂 α）/"
        + "alpha-unchanged（没变化就不落 revision）/ bad-payload（α < 0）。★ 生效时点 = 下一市场轮"
        + "（轮内已算出的价一个都不改）。参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。"
        + "★ preview 不在这里：要预览请先用 simos.state.resolve / simos.economy.hex 读现状。";
  }

  @Override
  public ResourceManifest resources() {
    return ECONOMY_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return List.of(ResourceId.of(ToolResources.ECONOMY_NAMESPACE, "*"));
  }
}
