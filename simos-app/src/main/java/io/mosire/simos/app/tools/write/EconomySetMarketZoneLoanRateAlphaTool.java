package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.spi.EconomySetMarketZoneLoanRateAlphaHandler;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.economy.setMarketZoneLoanRateAlpha}（W5.5，2026-10-11，S-12⑤）：<b>设置市场区货币市场利率的自适应步长
 * α</b> 的 GM 窄写面 —— {@link EconomySetMarketZoneLoanRateAlphaHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★★ <b>它为什么必须存在</b>：S-12⑤ 的利率与 §2.1 的商品价走同一套自适应算式（缺钱涨、钱多跌、下限 0），步长 α 就是"每 市场轮利率最多动多少"；口径溯源 §4.1
 * 记着用户 2026-09-25 的裁定「凡是行为一律做成 GM 可调的预设」⇒ α <b>必须可调</b>。 本工具就是那个写口的 app 侧形态（与 {@code
 * EconomySetMarketZonePriceAlphaTool} 同款：只透传 {@code payloadJson}， 载荷形状的唯一权威 = handler 的类注与 catalog
 * 的载荷提示）。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令本身标 {@code GmOnlyCommand} ⇒
 * 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code DecisionCallerFactory.WHITELIST} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（区表是经济切片的组件）。
 */
public final class EconomySetMarketZoneLoanRateAlphaTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.setMarketZoneLoanRateAlpha";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomySetMarketZoneLoanRateAlphaTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return EconomySetMarketZoneLoanRateAlphaHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设置市场区货币市场利率步长 α branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设置某个市场区的货币市场利率自适应步长 α（GM-only；固定命令 economy.SetMarketZoneLoanRateAlpha，载荷 JSON）："
        + "{marketId, alphaPerMille}。利率 = **钱的价**（S-12⑤：与商品价同一套算式 —— 缺钱涨、钱多跌、下限 0 = 白借合法），"
        + "α = **每市场轮利率最多变动的千分比**（缺省 50‰/轮，由 MarketZone.loanRateAlphaPerMille 承载）。"
        + "★ 不设上下限（除 α ≥ 0）：可以远大于 50（利率一步到位），也可以取 0（该区利率从此不随供需变动）。"
        + "★ 具名拒：zone-missing（派生市场没有区可挂 α）/ alpha-unchanged（没变化就不落 revision）/ bad-payload（α < 0）。"
        + "★ 生效时点 = 下一市场轮（轮内已算出的利率与已签的合同一个都不改）。"
        + "参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。"
        + "★ preview 不在这里：要预览请先用 simos.state.resolve / simos.economy.hex 读现状。";
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
