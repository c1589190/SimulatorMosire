package io.mosire.simos.economy.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.spi.EconomySetGovernmentMintScaleHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ {@code simos.economy.setGovernmentMintScale}（W5-fix6，2026-10-11）：<b>设置某个政府的铸币政策旋钮</b> 的 GM 窄写面
 * —— {@link EconomySetGovernmentMintScaleHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★★ <b>它为什么必须存在</b>：旋钮 {@code Government.mintScalePerCycle} 是"这个政府本周期最多铸多少毫"的<b>唯一闸门</b>，
 * 而改前唯一能写它的命令 {@code economy.RegisterGovernment} 只认 <b>GOV 单位</b>形态（{@code gov-unit-<id>}）⇒ 小世界里
 * <b>唯一会铸币的政府</b>是世界级 {@code world-silver}，用那条命令去调它会被"同一家户不能同时是两届政府的国库"当场拒（W5 验收报告 §3-D1 的 HTTP 422
 * 原文）—— 政策旋钮在唯一会铸币的政府上<b>够不着</b>。本工具就是那条缺的写口（与 {@code EconomySetMarketZonePriceAlphaTool} 同款：只透传
 * {@code payloadJson}，载荷形状的唯一权威 = handler 的类注与 catalog 的载荷提示）。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（命令类型本身进）；命令不打 {@code
 * DmUsableCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code 决策人白名单} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（政府表是经济切片的组件）。
 */
public final class EconomySetGovernmentMintScaleTool extends AbstractNarrowWriteTool
    implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.setGovernmentMintScale";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomySetGovernmentMintScaleTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return EconomySetGovernmentMintScaleHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设置政府铸币规模 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设置某个政府的铸币政策旋钮（GM-only；固定命令 economy.SetGovernmentMintScale，载荷 JSON）："
        + "{governmentId | govUnitId, mintScalePerCycle}。★ governmentId = **精确政府 id**（世界级政府如 world-silver 只能走这一路，"
        + "它不是任何 GOV 单位）；govUnitId = GOV 单位 id（派生 gov-unit-<id>）—— 两者**恰给一个**。"
        + "mintScalePerCycle = 本政府每个产业大周期最多铸多少**毫**（实铸 = min(产出请求, 它)；0 = 政策不许铸币，是合法状态；"
        + "不得为负）。★ 具名拒：government-missing（未登记的政府）/ bad-payload（负数 / 非整数 / 缺字段 / 两个地址都给）/"
        + "mint-scale-unchanged（与现值相同 ⇒ 不落 revision）。★ 只写政府表的那一个字段：国库、发行权、上一轮印量读数、"
        + "官方汇率、账户/债务/发行审计一个数都不动。★ 生效时点 = 下一个关账日（MintSettlement 逐轮现读）。"
        + "★ 旧形态不受影响：economy.RegisterGovernment 的 govUnitId 语义与守卫一字未改。"
        + "参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。"
        + "★ preview 不在这里：要预览请先用 simos.state.resolve / simos.economy.gov 读现状。";
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
