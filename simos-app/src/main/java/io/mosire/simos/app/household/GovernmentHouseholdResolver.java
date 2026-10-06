package io.mosire.simos.app.household;

import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitModule;
import java.util.Objects;

/**
 * ★★ <b>GOV 单位 → 政府家户的唯一解析落点</b>（P2-C §13.7；2026-10-09 唯一列表裁定后改读 {@code Unit.households}）：
 * 从单位的唯一实质列表里按 <b>稳定 id</b>（{@code hh-gov-<unitId>}）取该政府单位的国库家户。
 *
 * <p>★★ <b>为什么必须收在一处</b>：{@code GovRemitTool} / {@code GovPayTool} / {@code LevyRegionPlan} 三条财政路径
 * 都要“这个 GOV 的国库是哪个家户”。P2-A 之前它们各自取 {@code unit.households().get(0)}——那是“先到者胜”的列表顺序口径，
 * 一旦政府家户不是列表第一个（或列表里混入下辖民户）就会把别家的账当国库。现在三处共用本类：找前缀、 要求恰好一个、且引用逐字等于单位 id；对不上就具名拒（不猜、不退回
 * unit.households 的第一个）。
 *
 * <p>★ {@code UnitState} 构造期已把“每个 GOV 恰一个政府家户、且就在 {@code Unit.households} 里”判死；本类是命令/工具边界的
 * <b>同一口径</b>再解析一次，让工具不依赖列表顺序。{@code GovernmentFormation} 只用来判“这确实是 GOV 单位”，家户列表一个字不读。
 */
public final class GovernmentHouseholdResolver {

  private GovernmentHouseholdResolver() {}

  /**
   * 单位必须带 {@link GovernmentFormation}；取它 {@code Unit.households} 里唯一的政府家户（形状/引用对不上 ⇒ 具名 {@link
   * IllegalArgumentException}）。
   */
  public static HouseholdId requireGovernmentHousehold(Unit unit, String unitId) {
    Objects.requireNonNull(unit, "unit");
    UnitModule module = unit.module().orElse(null);
    if (!(module instanceof GovernmentFormation)) {
      throw new IllegalArgumentException(
          "单位 " + unitId + " 没有 GovernmentFormation，不能作为 GOV（国库 = 政府家户账户只对 GOV 成立）");
    }
    Objects.requireNonNull(unitId, "unitId");
    HouseholdId expected = GovernmentHouseholds.of(unitId);
    HouseholdId found = null;
    for (HouseholdId household : unit.households()) {
      if (!GovernmentHouseholds.isGovernment(household)) {
        continue;
      }
      if (found != null) {
        throw new IllegalArgumentException(
            "GOV 单位 " + unitId + " 的 Unit.households 有多个政府家户: " + found + " / " + household);
      }
      found = household;
    }
    if (found == null) {
      throw new IllegalArgumentException(
          "GOV 单位 "
              + unitId
              + " 的 Unit.households 没有政府家户（P2-C §13.7：国库 = 政府家户账户 "
              + expected.value()
              + "；请用 unit.SetGovFormation 立编制或 unit.SetUnitHouseholds 补上）");
    }
    if (!expected.equals(found)) {
      throw new IllegalArgumentException(
          "GOV 单位 " + unitId + " 的政府家户应为 " + expected.value() + "，实际=" + found.value());
    }
    return found;
  }
}
