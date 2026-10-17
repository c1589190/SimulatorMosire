package io.mosire.simos.economy.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.spi.EconomySetEconomyPolicyHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★★ {@code simos.economy.setEconomyPolicy}（W10，2026-10-11）：<b>把一个经济侧行为值写成 GM 政策预设</b> 的窄写面 ——
 * {@link EconomySetEconomyPolicyHandler} 的薄封装（固定命令类型 + 载荷 JSON + 乐观并发）。
 *
 * <p>★★ <b>它为什么必须存在</b>（用户 2026-09-25 裁定第 18 条：「凡"行为"一律做成 GM 可调的预设」）：这些值改前是编译期常量 （{@code
 * FamineMortality.FAMINE_MAX_PPM_PER_TICK} / {@code ClassSlideSettlement} 三常量 / 借贷基准利率 / 转业上限与斜率 /
 * 铸币产出率），要改就得重编译；而它们正是 W6 标定批交给用户拍板的旋钮。本工具是那条缺的写口。
 *
 * <p>★ <b>只在 GM 桶</b>：工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（命令类型本身进）；命令不打 {@code
 * DmUsableCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人命令目录；{@code 决策人白名单} 也不含本工具。
 *
 * <p>★ <b>写面只声明 economy 命名空间</b>：handler 只产 {@code EconomyChangeSet}（政策表住在 {@code economy.meta} 里）。
 */
public final class EconomySetEconomyPolicyTool extends AbstractNarrowWriteTool
    implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.economy.setEconomyPolicy";

  /** 本工具只写 economy 命名空间（handler 只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  public EconomySetEconomyPolicyTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return EconomySetEconomyPolicyHandler.TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设置经济政策预设 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "把某个经济侧**行为值**写成 GM 政策预设（GM-only；固定命令 economy.SetEconomyPolicy，载荷 JSON）："
        + "{key, valueMilli}。★ key = 政策键的稳定枚举名（大小写敏感），现行九个键："
        + "FAMINE_MAX_PPM_PER_TICK(饥荒死亡率上限 ppm/tick，缺省 1000) / "
        + "SLIDE_SPEED_PER_MILLE_PER_RATIO(阶层滑落斜率，缺省 100) / SLIDE_COST_PER_LEVEL(每级成本，缺省 1000) / "
        + "SLIDE_ROUND_DAYS(市场轮天数，缺省 5) / MINT_MILLI_PER_FURNACE_CYCLE(铸币产出率 毫/规模·周期，缺省 500) / "
        + "MINT_LABOR_SHARE_PER_MILLE(铸币劳动份额 ‰，缺省 30) / LOAN_BASE_RATE_PER_MILLE(借贷基准利率 ‰/周期，缺省 20) / "
        + "MIGRATION_SPEED_CAP_PER_MILLE(转业速度上限 ‰，缺省 100) / PROFIT_GAP_SPEED_SLOPE_PER_MILLE(转业斜率 ‰，缺省 100)。"
        + "★ valueMilli = 新值（整数）；**写成该键的缺省值 ⇒ 这一行从政策表里删掉**（缺键 = 缺省，不留冗余行）。"
        + "★ 具名拒：unknown-key（未知键）/ bad-range（越域）/ unchanged（与现值相同）/ not-activated（经济切片未激活）。"
        + "★ 只写 economy.meta 这一张表里的一个键：产业/家户/债务/市场/政府/发行审计一个数都不动。"
        + "★ 生效即本 revision（读取点每 tick/每轮从政策表读，不再读编译期常量）。"
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
