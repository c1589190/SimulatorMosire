package io.mosire.simos.economy.tools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;

/**
 * 经济工具的**宿主视角口**：三件"域内结构上够不着、而实现只有一份"的事——**格可见性围栏**（唯一实现在 app 的 {@code
 * ToolSupport.hexVisible}）、**unit 编制预检**（economy 看不见 unit 切片）、**周期扣增规则的到期判据**（唯一实现在 app 的 {@code
 * PeriodicHouseholdAdjustmentExecutor.isDue}）。
 *
 * <p>★★ <b>为什么是注入而不是在域内写第二份</b>：
 *
 * <ol>
 *   <li>权限围栏**不许抄第二份**（B3 两批的硬纪律）：可见性谓词只在 app 的 {@code ToolSupport} 一处，域侧只声明端口、宿主转调；
 *   <li>unit 切片**不在 economy 的允许面**（enforcer 禁 {@code simos-unit}，且 unit → social → economy
 *       会让反向声明成环）⇒ 结构上够不着；
 *   <li>到期判据是**执行语义**（日循环与工具必须同一条判据），抄一份就会漂 ⇒ 端口转调同一个静态方法。
 * </ol>
 *
 * <p>★ <b>失败语义不许折平</b>：{@link #govUnitFormation} 把"装配故障"与"业务拒绝"分开报（原实现前者抛 {@code
 * IllegalStateException}、后者抛 {@code EconomyGovUnitUpserts.Rejection}，两者的工具结局不同：{@code TOOL_ERROR}
 * vs {@code REJECTED}）——折成一种会改掉既有拒绝码。
 */
public interface EconomyToolPerspective {

  /** 某格是否可见（宿主转调 {@code ToolSupport.hexVisible}：格自身资源 ∪ 该格所属区域，两条通道取并集）。 */
  boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord);

  /**
   * GOV 生产 unit 的切片预检：{@code unit} 切片存在且该单位带 {@code GovernmentFormation}。
   *
   * @return 空 = 通过；否则给拒因与"这是装配故障还是业务拒绝"的判别
   */
  Optional<UnitRejection> govUnitFormation(SimulationState state, String govUnitId);

  /** 周期家户扣增规则的到期判据（宿主转调 {@code PeriodicHouseholdAdjustmentExecutor.isDue}，不另写第二份公式）。 */
  boolean isAdjustmentDue(HouseholdPeriodicAdjustment rule, long day);

  /**
   * unit 预检的结局。
   *
   * @param reason 拒因文本（原实现的 {@code IllegalStateException}/{@code Rejection} 消息逐字）
   * @param assemblyFault 真 = 装配故障（原实现抛 {@code IllegalStateException} ⇒ 工具折 {@code TOOL_ERROR}）； 假 =
   *     业务拒绝（原实现抛 {@code EconomyGovUnitUpserts.Rejection} ⇒ 工具折 {@code REJECTED}）
   */
  record UnitRejection(String reason, boolean assemblyFault) {

    public UnitRejection {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
