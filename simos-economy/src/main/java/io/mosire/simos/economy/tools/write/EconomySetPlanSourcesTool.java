package io.mosire.simos.economy.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.spi.EconomySetPlanSourcesHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ {@code simos.economy.setPlanSources}（D-032 B3，裁定十一 + 裁定十二）：<b>声明/清除某个生产方案在 economy 侧的
 * 两条经济关系</b>（谁提供闲置份额 / 劳动由谁出）的窄写面 —— {@link EconomySetPlanSourcesHandler} 的薄封装 （固定命令类型 + 载荷 JSON +
 * 乐观并发）。
 *
 * <p>★ <b>只在 GM 桶</b>（与 {@code simos.economy.setEconomyPolicy} 同制）：工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}（命令类型本身进）；命令不打 {@code DmUsableCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} /
 * 决策人命令目录；决策人白名单也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（两张声明表住在 {@code economy.meta}）。
 */
public final class EconomySetPlanSourcesTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.setPlanSources";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomySetPlanSourcesTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return EconomySetPlanSourcesHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "声明方案经济关系 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "声明/清除某个**生产方案**在 economy 侧的经济关系（GM-only；固定命令 economy.SetPlanSources，载荷 JSON）："
        + "{plan, assetSource?, laborSource?}。"
        + "★ plan = 方案稳定 id（**不绑地点**的方案，即不带 `@q_r` 格段；带格段的 id ⇒ 具名拒 plan-not-a-plan）。"
        + "★ assetSource = {\"kind\":\"HOUSEHOLD|...\",\"id\":\"...\"} —— 该方案可借用其**空闲**份额的主体"
        + "（缺省/清除 ⇒ **只认自有份额**，= 旧候选缺省 assetSource 的逐值行为）。"
        + "★ laborSource = SELF|FAMILY|TENANT|SERF|WAGE —— 该方案被采用时写进生产关系的劳动来源"
        + "（缺省/清除 ⇒ **SELF**，= 旧候选缺省，逐值不变）。"
        + "★ 两条声明**至少要给一条**；**缺键 = 不动这一维**，**显式 null = 清除该维**（回缺省）。"
        + "写成缺省值 ⇒ 该行从声明表里删掉（缺键 = 缺省，不留冗余行）。"
        + "★ 具名拒：bad-payload（载荷非法/主体格式错/词表外）/ plan-not-a-plan / no-declaration（两条都没给）/ "
        + "unchanged（声明已经是这个样子）/ not-activated（经济切片未激活）。"
        + "★ 只写 economy.meta 的两张声明表：方案定义（production 权威）/ 实例 / 家户 / 债务 / 市场一个数都不动。"
        + "★ 生效即本 revision（进入算法每次评估现读）。**不做版本、不做窗口**。"
        + "参数 {payloadJson(必填), branch(必填), expectedRevision(必填；乐观并发)}。";
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
