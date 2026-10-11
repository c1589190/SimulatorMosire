package io.mosire.simos.app.tools.social;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.tools.SocialUnitPort;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link SocialUnitPort} 的宿主实现（组合根）：**单位编制事实逐条转调**既有唯一实现——unit 切片的 {@code UnitState}、容纳列表
 * 写入的**唯一守卫** {@code UnitOperations.setUnitHouseholds}、以及命令类型常量的唯一拼写点 {@code
 * SetUnitHouseholdsHandler.TYPE}。
 *
 * <p>★★ <b>为什么实现必须在 app</b>：social 结构上看不见 unit（enforcer 禁 {@code simos-unit}，且 {@code simos-unit}
 * 反过来显式依赖 {@code simos-social} ⇒ 直连即建图期成环）；而 app 是唯一同时认识两边的模块。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@code unitId} 进来同一份 households（同一个迭代序）；守卫违例时抛出的就是 {@code
 * UnitOperations} 自己的 {@link IllegalArgumentException}（类与文案逐字，工具折 {@code BAD_REQUEST}）。
 */
public final class AppSocialUnitPort implements SocialUnitPort {

  @Override
  public Optional<UnitHouseholds> unit(SimulationState state, String unitId) {
    Unit unit = ToolSupport.unitState(state).units().get(new UnitId(unitId));
    if (unit == null) {
      return Optional.empty();
    }
    return Optional.of(new UnitHouseholds(unit.id().value(), rawHouseholds(unit)));
  }

  @Override
  public List<String> householdOwners(SimulationState state, String householdId) {
    HouseholdId id = HouseholdId.parse(householdId);
    List<String> owners = new ArrayList<>();
    // ★ 判据与迁入前逐字相同：只看 unit 侧 households 列表的真实成员关系，按单位切片的迭代序。
    for (Unit unit : ToolSupport.unitState(state).units().values()) {
      if (unit.households().contains(id)) {
        owners.add(unit.id().value());
      }
    }
    return owners;
  }

  @Override
  public void requireValidHouseholds(
      SimulationState state, String unitId, List<String> householdIds) {
    UnitState units = ToolSupport.unitState(state);
    List<HouseholdId> households = new ArrayList<>(householdIds.size());
    for (String householdId : householdIds) {
      households.add(HouseholdId.parse(householdId));
    }
    // ★ 唯一守卫：不撞名 / 不重复 / 跨单位不重属 —— 结果（投影状态）不在这里用，要的是它的**校验**。
    UnitOperations.setUnitHouseholds(units, new UnitId(unitId), households);
  }

  @Override
  public String unitHouseholdsCommandType() {
    return SetUnitHouseholdsHandler.TYPE;
  }

  /** 单位的 households（{@code HouseholdId} ⇒ 裸字符串，稳定序）。 */
  private static List<String> rawHouseholds(Unit unit) {
    List<String> out = new ArrayList<>(unit.households().size());
    for (HouseholdId household : unit.households()) {
      out.add(household.value());
    }
    return out;
  }
}
