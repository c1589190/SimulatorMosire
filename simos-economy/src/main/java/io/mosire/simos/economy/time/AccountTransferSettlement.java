package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.SettlementMath.addMoney;
import static io.mosire.simos.economy.time.SettlementMath.addStock;
import static io.mosire.simos.economy.time.SettlementMath.moneyOf;
import static io.mosire.simos.economy.time.SettlementMath.setMoney;
import static io.mosire.simos.economy.time.SettlementMath.setStock;
import static io.mosire.simos.economy.time.SettlementMath.stockOf;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.money.MoneyIssuance;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class AccountTransferSettlement {

  // ── 转移的落账（H2：唯一写会话副本的地方）────────────────────────────────────────────

  /**
   * ★★ <b>把一条转移的两端落到家户账户工作副本上</b>（P2-A §13.3：账户主体只有家户）—— <b>全模块唯一</b>写这些副本库存的 "换手"路径（另一类是消费/投入的扣减，见
   * {@link #consumeFromHousehold} 与 {@code drawCycleInputs}）。
   *
   * <p>★★ <b>两端必须解析到已登记家户</b>：{@code householdOfActor}（= 现存家户 actor 反查表）命中 ⇒ 家户账； 命不中 ⇒
   * <b>具名抛</b>（不再有"经营者账"旁路、也不再有"跳过这一端交给 app 落账"的静默口径 —— 结算侧必须在 铸转移之前就解析到家户）。
   *
   * <p>★★ <b>M1.4：本方法是两遍式</b>：第一遍 {@link #validateApplyTransfer} <b>只读</b>地判"每一条付方腿是否扣得动" （余额不足 /
   * 货币不足 ⇒ 走发行闸门），任一条不合法都在<b>会话活表一字未动</b>时抛出；第二遍才统一落账。
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    applyTransfer(
        householdGoods, householdMoney, Map.of(), Map.of(), householdOfActor, transfer, null);
  }

  /** ★★ E3：带发行审计落点的入口（无冻结）。 */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    applyTransfer(
        householdGoods, householdMoney, Map.of(), Map.of(), householdOfActor, transfer, journal);
  }

  /** ★★ <b>M2：带冻结表的 {@code applyTransfer}</b>（无发行落点）。 */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    applyTransfer(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        householdOfActor,
        transfer,
        null);
  }

  /**
   * ★★ <b>E3：唯一转移写口（带冻结 + 发行审计落点）</b>：
   *
   * <pre>
   * 第一遍（只读校验）：付方余额不足时先 MoneyIssuance.requireIssuerOf(currency)；
   *   · 付方 ≠ 发行源 ⇒ 保留旧 fail-closed 抛；
   *   · 付方 = 发行源 ⇒ 允许单边发行，但必须带 journal（否则当场抛，不静默造钱）。
   * 第二遍（统一落账）：普通腿逐值不变；发行腿只扣发行源实际持有的部分（不越过冻结），
   *   收方仍足额到账，差额写一条 FISCAL_ISSUE 审计记录。
   * </pre>
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    Objects.requireNonNull(transfer, "transfer");
    HouseholdId from = requireHouseholdOf(transfer.from(), householdOfActor);
    HouseholdId to = requireHouseholdOf(transfer.to(), householdOfActor);
    // ★★ M1.4 第一遍：**全量校验**（只读）—— 任一条腿不合法都在会话活表一字未动时抛出。
    validateApplyTransfer(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        from,
        transfer,
        journal);
    // ★★ M1.4 第二遍：**统一落账** —— 此刻所有付方腿的可扣性都已验证过 ⇒ 下面只写、不再判。
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      setStock(
          householdGoods,
          from,
          leg.getKey(),
          stockOf(householdGoods, from, leg.getKey()) - leg.getValue());
    }
    debitHouseholdMoney(householdMoney, householdFrozenMoney, from, transfer, journal);
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      addStock(householdGoods, to, leg.getKey(), leg.getValue());
    }
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      addMoney(householdMoney, to, leg.getKey(), leg.getValue());
    }
  }

  /** 家户 actor → 家户身份；不在现存家户表里 ⇒ 具名抛（账户主体只有家户）。 */
  static HouseholdId requireHouseholdOf(
      ActorRef actor, Map<ActorRef, HouseholdId> householdOfActor) {
    HouseholdId household = householdOfActor.get(Objects.requireNonNull(actor, "actor"));
    if (household == null) {
      throw new IllegalStateException(
          "转移的一端不是已登记家户（账户主体只有家户；庄园/作坊/商号必须解析到组织者/经营者家户）："
              + actor
              + "；家户表键数="
              + householdOfActor.size());
    }
    return household;
  }

  /** 家户货币付端：普通腿照旧扣；发行腿只扣实际持有（不越过冻结），差额交 journal 记发行。 */
  static void debitHouseholdMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      HouseholdId from,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      CurrencyId currency = leg.getKey();
      long amount = leg.getValue();
      long balance = moneyOf(householdMoney, from, currency);
      long frozen = householdFrozenMoney.getOrDefault(from, Map.of()).getOrDefault(currency, 0L);
      long debit = amount;
      if (balance < amount) {
        ActorRef issuer = MoneyIssuance.requireIssuerOf(currency);
        if (!transfer.from().equals(issuer)) {
          throw new IllegalStateException(
              "转移把家户的货币扣成了负数，而付方不是发行源（透支 = 发行）：家户="
                  + from
                  + " 币种="
                  + currency
                  + " 余额="
                  + balance
                  + " 扣减="
                  + amount
                  + " 发行源="
                  + issuer
                  + "；转移="
                  + transfer);
        }
        debit = Math.min(amount, Math.max(0L, balance - frozen));
        if (debit < amount) {
          if (journal == null) {
            throw new IllegalStateException("这条入口没有发行审计落点，拒绝单边发行（不许造钱而没有审计记录）：转移=" + transfer);
          }
          journal.recordIssuance(transfer, currency, amount - debit);
        }
      }
      setMoney(householdMoney, from, currency, balance - debit);
    }
  }

  /**
   * ★★ <b>M1.4 第一遍：把"这一次转移扣得动吗"一次判完</b>（只读）。
   *
   * <p>★ E3 起货币不足的判据收成两条：付方不是发行源 ⇒ 保留旧 fail-closed 文案；付方是发行源 ⇒ 必须带 {@link
   * MoneyIssuanceJournal}（否则不静默发行），且只扣实际持有的部分（冻结仍然受保护）。
   */
  static void validateApplyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      HouseholdId from,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      long stock = stockOf(householdGoods, from, leg.getKey());
      if (stock < leg.getValue()) {
        throw new IllegalStateException(
            "转移把家户账扣成了负数（不许凭空吞）：家户="
                + from
                + " 商品="
                + leg.getKey()
                + " 余额="
                + stock
                + " 扣减="
                + leg.getValue()
                + "；转移="
                + transfer);
      }
      long frozen =
          householdFrozenGoods.getOrDefault(from, Map.of()).getOrDefault(leg.getKey(), 0L);
      if (stock - leg.getValue() < frozen) {
        throw new IllegalStateException(
            "转移会花掉家户账上已冻结的商品（冻结只表达已明确的占用）：家户="
                + from
                + " 商品="
                + leg.getKey()
                + " 余额="
                + stock
                + " 冻结="
                + frozen
                + " 扣减="
                + leg.getValue()
                + "；转移="
                + transfer);
      }
    }
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      long balance = moneyOf(householdMoney, from, leg.getKey());
      long frozen =
          householdFrozenMoney.getOrDefault(from, Map.of()).getOrDefault(leg.getKey(), 0L);
      if (balance < leg.getValue()) {
        ActorRef issuer = MoneyIssuance.requireIssuerOf(leg.getKey());
        if (!transfer.from().equals(issuer)) {
          throw new IllegalStateException(
              "转移把家户的货币扣成了负数，而付方不是发行源（透支 = 发行）：家户="
                  + from
                  + " 币种="
                  + leg.getKey()
                  + " 余额="
                  + balance
                  + " 扣减="
                  + leg.getValue()
                  + " 发行源="
                  + issuer
                  + "；转移="
                  + transfer);
        }
        if (journal == null) {
          throw new IllegalStateException(
              "家户付方是发行源，但这条入口没有发行审计落点，拒绝单边发行（不许造钱而没有审计记录）：转移=" + transfer);
        }
        continue; // 发行腿：只扣实际持有的部分，冻结仍受 debitHouseholdMoney 保护
      }
      if (balance - leg.getValue() < frozen) {
        throw new IllegalStateException(
            "转移会花掉家户账上已冻结的货币（冻结只表达已明确的占用）：家户="
                + from
                + " 币种="
                + leg.getKey()
                + " 余额="
                + balance
                + " 冻结="
                + frozen
                + " 扣减="
                + leg.getValue()
                + "；转移="
                + transfer);
      }
    }
  }

  /**
   * ★ <b>为什么还活着（W2-fix6 裁定保留，不是孤儿）</b>：计划内待接线入口 —— 消费者 = **P7 的 GM 编辑工具**（回笼写口） 与日结算/命令层的同包代码（批次：P5
   * 立口、P7/GM 工具批接线；接上之前零自动调用方，属预期）。
   *
   * <p>★★ <b>P5：回笼（WITHDRAWAL）写口的包内入口</b> —— 薄转发到唯一实现 {@link TreasuryWithdrawal#withdraw}；
   * 发行授权、余额/冻结守卫、审计 id 与重放幂等全部在那一处，本方法不再写第二份算式。
   *
   * <p>★ <b>为什么保留这个入口</b>：日结算/命令层的同包代码与 P7 的 GM 编辑工具可以只依赖 {@code EconomySettlement} 这一个门面；跨包调用方直接走
   * {@link TreasuryWithdrawal}（public）。两条路落到同一实现、 同一张发行审计表。★ P7/GM 接上之前，本批没有自动调用方。
   */
  static MoneyIssuanceRecord withdrawFromTreasury(
      EconomySession session,
      AccountSession accounts,
      GovernmentId governmentId,
      CurrencyId currency,
      long amount,
      long day,
      String operationRef) {
    return TreasuryWithdrawal.withdraw(
        session, accounts, governmentId, currency, amount, day, operationRef);
  }

  /**
   * ★★ <b>H4 的第一条守卫：货币账必须覆盖每一个"有人口"的家户</b>（与 {@link #requireHouseholdAccounts} 逐字同款的口径与理由）。
   *
   * <p>★★ <b>为什么必须抛而不是"当成 0"</b>：没有账的一家人可花余额被读成 0 ⇒ 它的有效需求是 0 ⇒ 市场买不到粮、
   * 货币工资也付不出去，而账面（缺口、读数）看起来完全正常。★ 0 人口的家户**可以缺席**（它们不消费、不出工）。
   */
  static void requireHouseholdMoney(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (householdEconomyEntry.getValue().population() <= 0L) {
        continue; // 0 人口：不消费、不出工 ⇒ 允许没有钱包
      }
      if (!householdMoney.containsKey(householdEconomyEntry.getKey())) {
        missing.add(
            householdEconomyEntry.getKey()
                + "（人口 "
                + householdEconomyEntry.getValue().population()
                + "）");
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "家户**货币**账缺失（H4 fail-closed，裁定 K14）：有 "
            + missing.size()
            + " 个「有人口」的家户在会话货币副本里没有键 —— 不许把'没有账'静默当成'余额 0'"
            + "（那会让这一家人的有效需求恒为 0：市场买不到粮、货币工资收不到，而账面上看不出少了谁）。"
            + "app 协调器必须在推进前从 actor 侧载入货币账（创世禀赋在播种时给它）；"
            + "单模块用例请用 EconomyDayStepper 的 householdMoney 参数显式给账。缺失的家户（最多列 8 个）："
            + missing.subList(0, Math.min(8, missing.size())));
  }

  /**
   * ★★ **H1 的第一条守卫：家户账必须覆盖每一个"要吃粮的家户"**（fail-closed；裁定 K1 / D3-C）。
   *
   * <pre>
   * 逐行：population > 0 且 householdGoods 里没有该键 ⇒ 抛
   *       population == 0            ⇒ 放行（它们不吃饭、不出工 ⇒ 可以没有账）
   * </pre>
   *
   * <p>★★ **为什么必须抛而不是"当成 0"**：库里没有这一家的账时，若继续跑，它当天的消费是 0、投入也是 0 —— 而账面（流水 / 缺口 /
   * 收获）看起来完全正常，只有那一家人**静默地**没吃饭。那正是本仓最反对的"静默付 0"形态 （同 E14 的守卫、{@code EconomyData} 的构造期守卫）。★
   * 例外只有一个：{@code population == 0} 的行本来就不消费（见 {@code settleHexes} 与 {@code drawCycleInputs} 的显式跳过）。
   *
   * <p>★ **消息里给出前几个缺账的家户**（不是全部 —— 真档可能有几百个）：足够定位是哪一批人没被播种。
   */
  static void requireHouseholdAccounts(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (householdEconomyEntry.getValue().population() <= 0L) {
        continue; // 0 人口：不吃饭、不出工 ⇒ 允许没有账
      }
      if (!householdGoods.containsKey(householdEconomyEntry.getKey())) {
        missing.add(
            householdEconomyEntry.getKey()
                + "（人口 "
                + householdEconomyEntry.getValue().population()
                + "）");
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "家户账缺失（H1 fail-closed，裁定 K1）：有 "
            + missing.size()
            + " 个「有人口」的家户在会话工作副本里没有键 —— 不许把'没有账'静默当成'库存 0'"
            + "（那会让这一家人当天静默地不吃饭）。app 协调器必须在推进前从 actor 侧的 HouseholdInventory 载入家户账；"
            + "单模块用例请用 EconomyDayStepper 的 householdGoods 参数显式给账。缺失的家户（最多列 8 个）："
            + missing.subList(0, Math.min(8, missing.size())));
  }
}
