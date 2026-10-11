package io.mosire.simos.app.tools.economy;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.time.PeriodicHouseholdAdjustmentExecutor;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.tools.EconomyToolPerspective;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Optional;

/**
 * {@link EconomyToolPerspective} 的宿主实现（组合根）：三件事**逐条转调既有唯一实现**—— 可见性围栏走 {@link
 * ToolSupport#hexVisible}（围栏逻辑只有那一份）、到期判据走 {@link
 * PeriodicHouseholdAdjustmentExecutor#isDue}（执行语义只有那一份）、unit 编制预检读 unit 切片（economy 看不见它）。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@link ToolContext} 进来同一个布尔出去；同一个 {@code govUnitId} 进来同一句话出去 （"装配故障 /
 * 业务拒绝"两档的**异常类与文案逐字**保留，由工具折成 {@code TOOL_ERROR} / {@code REJECTED}——这条判别就是本端口 {@link
 * UnitRejection#assemblyFault()} 字段存在的唯一理由）。
 */
public final class AppEconomyToolPerspective implements EconomyToolPerspective {

  @Override
  public boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord) {
    return ToolSupport.hexVisible(context, mapId, map, coord);
  }

  /**
   * unit 切片预检（组合根专有；economy handler 因模块边界查不到）：unit 存在且 {@code module()} 是 {@link
   * GovernmentFormation}。
   *
   * <p>★ 逐字搬自原 {@code EconomyUpsertGovUnitTool.requireGovUnitFormation}：装配故障（没有 unit 切片 /
   * 类型不对）与业务拒绝 （单位不存在 / 不是 GOV 编制）分两档报，工具按档折成 {@code TOOL_ERROR} / {@code REJECTED}。
   */
  @Override
  public Optional<UnitRejection> govUnitFormation(SimulationState state, String govUnitId) {
    Optional<Snapshot> module = state.module("unit");
    if (module.isEmpty()) {
      return Optional.of(
          new UnitRejection("state 里没有 unit 切片（装配故障：GOV 生产 unit 要判 GovernmentFormation）", true));
    }
    if (!(module.get() instanceof UnitSnapshot unitSnapshot)) {
      return Optional.of(
          new UnitRejection(
              "state 的 unit 切片不是 UnitSnapshot: " + module.get().getClass().getName(), true));
    }
    Unit unit = unitSnapshot.state().units().get(UnitId.parse(govUnitId));
    if (unit == null) {
      return Optional.of(new UnitRejection("GOV 单位不存在: " + govUnitId, false));
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      return Optional.of(
          new UnitRejection("unit 不是 GOV 编制单位（缺 GovernmentFormation）: " + govUnitId, false));
    }
    return Optional.empty();
  }

  @Override
  public boolean isAdjustmentDue(HouseholdPeriodicAdjustment rule, long day) {
    return PeriodicHouseholdAdjustmentExecutor.isDue(rule, day);
  }
}
