package io.mosire.simos.app.tools.gov;

import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.actor.spi.RemitGovTreasuryHandler;
import io.mosire.simos.economy.spi.EconomyDefineCurrencyHandler;
import io.mosire.simos.economy.spi.EconomyRecordMoneyIssuanceHandler;
import io.mosire.simos.economy.spi.EconomyRegisterGovernmentHandler;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.economy.spi.EconomyRenameCurrencyHandler;
import io.mosire.simos.economy.spi.EconomySetGovServiceCommitmentHandler;
import io.mosire.simos.economy.spi.EconomySetGovernmentPolicyHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdLaborHandler;
import io.mosire.simos.economy.spi.EconomySetOfficialRateHandler;
import io.mosire.simos.gov.tools.GovToolCommandTypes;
import io.mosire.simos.sd.spi.PutInfoHandler;

/**
 * {@link GovToolCommandTypes} 的宿主实现（组合根）：**逐条转调各域 handler 的 {@code TYPE} 常量** —— 命令类型串的唯一拼写点
 * 仍在各域自己的 handler 里，本类只是把它们交到 gov 工具面（gov 看不见 {@code economy} 实现 / {@code actor} / {@code sd}）。
 */
public final class AppGovToolCommandTypes implements GovToolCommandTypes {

  @Override
  public String setGovernmentPolicy() {
    return EconomySetGovernmentPolicyHandler.TYPE;
  }

  @Override
  public String registerGovernment() {
    return EconomyRegisterGovernmentHandler.TYPE;
  }

  @Override
  public String registerHousehold() {
    return EconomyRegisterHouseholdHandler.TYPE;
  }

  @Override
  public String setHouseholdLabor() {
    return EconomySetHouseholdLaborHandler.TYPE;
  }

  @Override
  public String setGovServiceCommitment() {
    return EconomySetGovServiceCommitmentHandler.TYPE;
  }

  @Override
  public String defineCurrency() {
    return EconomyDefineCurrencyHandler.TYPE;
  }

  @Override
  public String renameCurrency() {
    return EconomyRenameCurrencyHandler.TYPE;
  }

  @Override
  public String recordMoneyIssuance() {
    return EconomyRecordMoneyIssuanceHandler.TYPE;
  }

  @Override
  public String setOfficialRate() {
    return EconomySetOfficialRateHandler.TYPE;
  }

  @Override
  public String ensureHouseholdAccount() {
    return EnsureHouseholdAccountHandler.TYPE;
  }

  @Override
  public String remitGovTreasury() {
    return RemitGovTreasuryHandler.TYPE;
  }

  @Override
  public String putInfo() {
    return PutInfoHandler.TYPE;
  }
}
