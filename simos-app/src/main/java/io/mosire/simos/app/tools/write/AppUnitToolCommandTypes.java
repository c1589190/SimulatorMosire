package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.unit.tools.UnitToolCommandTypes;

/**
 * {@link UnitToolCommandTypes} 的宿主实现（组合根）：**引用两条命令类型的唯一拼写点**（各自 handler 的 {@code TYPE}）， 不在这里手抄字面量。
 *
 * <p>★★ <b>为什么要经端口绕一圈</b>：{@code EnsureHouseholdAccountHandler} 住在 {@code simos-actor}、{@code
 * EconomyRegisterHouseholdHandler} 住在 {@code simos-economy}——两者都在 {@code simos-unit}
 * 的**禁列**里（域看不见那两个实现）； 手抄一份类型串写歪一个字**不抛异常**，只在运行期变成一条"未知命令类型"的拒因 ⇒ 拼写点必须只有一处（与 B3 第三/四域 social 的
 * {@code SocialUnitPort.unitHouseholdsCommandType} 同一条先例）。
 */
public final class AppUnitToolCommandTypes implements UnitToolCommandTypes {

  @Override
  public String ensureHouseholdAccountType() {
    return EnsureHouseholdAccountHandler.TYPE;
  }

  @Override
  public String registerHouseholdType() {
    return EconomyRegisterHouseholdHandler.TYPE;
  }
}
