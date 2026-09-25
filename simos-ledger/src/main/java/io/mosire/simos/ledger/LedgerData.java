package io.mosire.simos.ledger;

import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.ledger.model.Account;
import io.mosire.simos.ledger.model.Claim;
import io.mosire.simos.ledger.model.EconomyMeta;
import io.mosire.simos.ledger.model.Transfer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 账本切片的完整状态树（增量 2 spec §3 逐字）：激活元信息 + 账户表 + 索取权表 + 转移流水。
 *
 * <p>★★ **{@code economyMeta} 为空 {@code Optional} = 经济未激活**（设计稿 §2 的"未激活经济"语义）：日制世界
 * 可先沿用简化人口查询（{@code populations}/{@code cities}），但**日期与参数一律按天解释**。空快照 ≠ 已激活。
 *
 * <p>★★ **本切片只写自己的数据**：库存与货币的**守恒**（{@code 买方扣款 = 卖方入账 + 税入账 + 运输方收入}、 {@code 卖方出货 = 买方/在途入货 +
 * 明示损耗}）由**命令层/协调器**校验，**不落成第二份真相**——这里只有账， 没有"校验结论"。同样地，任何经济公式（产量/价格/税/撮合）都不在本切片。
 *
 * <p>★ **四个组件与 {@link io.mosire.simos.ledger.change.LedgerChangeSet} 的四个组件一一对应**（铁律 5）：
 * 新增状态组件必须同时进变更集，由 {@code LedgerRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ **缺键 = 空**（spec §11 的旧档兼容口径，照 {@code SocialData.cities} 的先例）：四个组件在本切片
 * **都是新引入的**（没有哪个是"老键"），故 Jackson 绑成 null 时一律收成空表 / 未激活，**此处不抛** —— 抛了等于"旧档全部读不回来"。方向是
 * fail-closed：缺键 ⇒ 没有账户/没有债权/没有流水/未激活。 （与 {@code SocialData} 的不对称形态不同：那边 {@code populations}
 * 是随切片一起诞生的老键。）
 *
 * <p>★ **三张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（M2 实测），字节级往返因此不成立。冻结那一步**写在字段赋值处** （SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 */
public record LedgerData(
    Optional<EconomyMeta> economyMeta,
    Map<AccountId, Account> accounts,
    Map<ClaimId, Claim> claims,
    Map<TransferId, Transfer> transfers) {

  /** 往返用例的起点：未激活 + 三张空表。 */
  public static LedgerData empty() {
    return new LedgerData(Optional.empty(), Map.of(), Map.of(), Map.of());
  }

  public LedgerData {
    // ★ 缺键（null）⇒ 未激活 / 空表，见类注释（旧档兼容，fail-closed 方向）。
    if (economyMeta == null) {
      economyMeta = Optional.empty();
    }
    if (accounts == null) {
      accounts = Map.of();
    }
    if (claims == null) {
      claims = Map.of();
    }
    if (transfers == null) {
      transfers = Map.of();
    }
    Map<AccountId, Account> accountsCopy = new LinkedHashMap<>();
    for (Map.Entry<AccountId, Account> entry : accounts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("accounts 的键与值都不得为 null: " + entry.getKey());
      }
      accountsCopy.put(entry.getKey(), entry.getValue());
    }
    accounts = Collections.unmodifiableMap(accountsCopy); // ★ 冻在赋值处（EI_EXPOSE_REP 只认它看得见的）
    Map<ClaimId, Claim> claimsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClaimId, Claim> entry : claims.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("claims 的键与值都不得为 null: " + entry.getKey());
      }
      claimsCopy.put(entry.getKey(), entry.getValue());
    }
    claims = Collections.unmodifiableMap(claimsCopy); // ★ 冻在赋值处，同上
    Map<TransferId, Transfer> transfersCopy = new LinkedHashMap<>();
    for (Map.Entry<TransferId, Transfer> entry : transfers.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("transfers 的键与值都不得为 null: " + entry.getKey());
      }
      transfersCopy.put(entry.getKey(), entry.getValue());
    }
    transfers = Collections.unmodifiableMap(transfersCopy); // ★ 冻在赋值处，同上
  }

  /** 一个组件一个 with（照 {@code SocialData} 的形制）。 */
  public LedgerData withEconomyMeta(Optional<EconomyMeta> value) {
    return new LedgerData(value, accounts, claims, transfers);
  }

  /** 一个组件一个 with（照 {@code SocialData} 的形制）。 */
  public LedgerData withAccounts(Map<AccountId, Account> value) {
    return new LedgerData(economyMeta, value, claims, transfers);
  }

  /** 一个组件一个 with（照 {@code SocialData} 的形制）。 */
  public LedgerData withClaims(Map<ClaimId, Claim> value) {
    return new LedgerData(economyMeta, accounts, value, transfers);
  }

  /** 一个组件一个 with（照 {@code SocialData} 的形制）。 */
  public LedgerData withTransfers(Map<TransferId, Transfer> value) {
    return new LedgerData(economyMeta, accounts, claims, value);
  }
}
