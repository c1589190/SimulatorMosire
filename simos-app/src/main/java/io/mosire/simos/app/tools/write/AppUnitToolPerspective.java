package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.gov.tools.write.GovSelectExamineesPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.tools.UnitToolPerspective;
import io.mosire.simos.util.state.SimulationState;

/**
 * {@link UnitToolPerspective} 的宿主实现（组合根）：**逐条转调既有唯一实现**——判据与拒因文案一个字节都不在这里重写。
 *
 * <ul>
 *   <li>{@link #unitVisible} → {@code ToolSupport.unitVisible}（{@code unit:<unitId>}
 *       资源判定；**权限围栏不抄第二份**）；
 *   <li>{@link #armyExists} → {@code
 *       ToolSupport.armyData(state).armies().containsKey(ArmyId.parse(armyId))}（★ 2026-10-12 批次
 *       3d-1： {@code armies} 表随军队身份搬进 {@code simos-army}，此前是 {@code sdState(state).armies()}；
 *       {@code simos-army} 仍在 unit 的禁列里 ⇒ 判据照旧只有组合根能判）；
 *   <li>{@link #governmentHousehold} → {@code
 *       GovernmentHouseholdResolver.requireGovernmentHousehold}（GOV 国库家户的 既有唯一解析落点；{@code
 *       GovRemitTool}/{@code GovPayTool} 两条仍在 app 的财政路径与 levy 共用它）；
 *   <li>{@link #residenceAt} → {@code GovSelectExamineesPlan.residenceAt}（落点城乡判据的既有唯一实现）。
 * </ul>
 *
 * <p>★ 本适配器住在 {@code app.tools.write}：{@code GovSelectExamineesPlan} 是**包内可见**的（与 {@code
 * AppArmyToolViews}/{@code AppArmyManpowerPort} 同一条 D4 先例：不为一次下放放宽既有类的可见性）。
 */
public final class AppUnitToolPerspective implements UnitToolPerspective {

  @Override
  public boolean unitVisible(ToolContext context, UnitId unitId) {
    return ToolSupport.unitVisible(context, unitId);
  }

  /**
   * army 侧事实（2026-10-12 批次 3d-1 前是 sd 侧事实）：该 army id 是否已被占用。{@code ArmyId.parse} 的异常类与文案与迁入前
   * 逐字相同（非法 id ⇒ 具名 {@link IllegalArgumentException}，由工具折 {@code BAD_REQUEST}）。
   */
  @Override
  public boolean armyExists(SimulationState state, String armyId) {
    return ToolSupport.armyData(state).armies().containsKey(ArmyId.parse(armyId));
  }

  @Override
  public HouseholdId governmentHousehold(Unit unit, String unitId) {
    return GovernmentHouseholdResolver.requireGovernmentHousehold(unit, unitId);
  }

  @Override
  public ResidenceKind residenceAt(SocialData social, HexCoord at) {
    return GovSelectExamineesPlan.residenceAt(social, at);
  }
}
