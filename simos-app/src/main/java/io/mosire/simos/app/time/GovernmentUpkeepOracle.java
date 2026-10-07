package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code GovDaily.PaymentOracle} 的 P2-D 装配（政府家户账户版）</b>：{@link GovDaily#settle} 每次要付 grain /
 * cloth / silver 时，从该 GOV 单位的<b>政府家户账户</b>（{@code hh-gov-&lt;unitId&gt;}）扣款。
 *
 * <p>★★ <b>与旧装配的唯一区别</b>：旧路径的国库账键是 {@code (ActorRef(UNIT, unitId), 有效位置)}；P2-A §13.3 起非家户主体 不得持账，国库
 * = 政府家户账户。付款不再需要位置参数（{@code at} 只作 GovDaily 的读数/信号落点），账户身份由 {@link GovernmentHouseholdResolver} 从
 * {@code Unit.households} 按稳定 id 解析——不取列表第一个、不猜。
 *
 * <p>★★ <b>Z7b：俸禄 = 发给官吏户的口粮/布</b>（用户原话「俸禄不就是口粮吗」）——粮/布两腿不再 sink，而是 {@link
 * HouseholdStockDeduction#transfer} 给该 GOV 单位的官吏户；分摊权重 = 各户 {@code GOV_SERVICE} 承诺劳动（唯一权威 {@link
 * GovernmentServiceLaborBridge#committedLaborByHousehold}），承诺全 0 时退化为在官吏户间等分。★ 银腿仍走
 * sink：俸禄冻结为“实物粮/布”，银/额外粮归 {@code ADMIN_SALARY} 工资链（另一条已存在的具名转移），本类不产生第二份银需求。
 *
 * <p>★★ <b>失败语义（不静默付 0、不凭空造、不静默丢货）</b>：
 *
 * <ul>
 *   <li><b>缺账 / 解析不出政府家户</b> ⇒ 当场 {@link IllegalStateException}（这是装配/状态故障；推进入口已有 {@code
 *       GovernmentHouseholdWiring.requireConsistent} 先判，正常路径不可达）；
 *   <li><b>余额不足</b> ⇒ 付 {@code min(可用, 请求)}（可能是 0），由 {@link GovDaily} 记 {@code shortfall}并发 {@code
 *       ADMIN_SUPPLY} 信号——这是"记了缺口的 0"，不是静默；
 *   <li><b>官吏户不存在/只有国库户本身</b> ⇒ 具名 ERROR {@code GOV_UPKEEP_RECIPIENT_MISSING} + 付 0（缺口由 {@code
 *       GovDaily} 的 ADMIN_SUPPLY 承接），不 fallback 到 sink、不为谁造户；
 *   <li><b>某个官吏户账户缺失</b> ⇒ 具名 ERROR {@code GOV_UPKEEP_RECIPIENT_ACCOUNT_MISSING}，该户份额不落账（留在国库 =
 *       缺口）， 其余户照付；不静默把货丢掉；
 *   <li><b>可用量</b> = 余额 − 冻结，走 {@link AvailableStock} 的唯一算法；冻结表原样保留，付款不侵冻结。
 * </ul>
 *
 * <p>★ 付款走共享的 {@link StockDeductionService}（reason = {@link DeductionReason#ADMIN_UPKEEP}）：同一本账同一天按
 * {@link GovDaily} 的固定资源次序（grain → cloth → money）串行发生，后一次看得到前一次的扣减；银腿 sink 的账户提交阶段 = {@code
 * SettlementStage.TAX_AND_UPKEEP}（组合根在 {@code EconomyDayStepper.step(day)} 之后提交）。
 */
final class GovernmentUpkeepOracle implements GovDaily.PaymentOracle {

  /** 行政结算日志（app 日循环分类）。 */
  private static final Logger LOG = AppLog.time();

  /** 逐笔日志（trace 分类；默认关闭）。 */
  private static final Logger TRACE = AppLog.trace();

  private final AccountSession accounts;
  private final UnitState units;

  /** 承诺份额的权威（可为 null：旧夹具/无 economy 的组合 ⇒ 官吏户等分；生产由组合根传入）。 */
  private final EconomyData economy;

  /** 每个 GOV 的分摊目标缓存（GOV_SERVICE 承诺是 revision 内不变量；本 oracle 在生产里每日新建）。 */
  private final Map<UnitId, List<StipendTarget>> stipendTargetsByUnit = new LinkedHashMap<>();

  GovernmentUpkeepOracle(AccountSession accounts, UnitState units) {
    this(accounts, units, null);
  }

  GovernmentUpkeepOracle(AccountSession accounts, UnitState units, EconomyData economy) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.units = Objects.requireNonNull(units, "units");
    this.economy = economy;
  }

  @Override
  public long pay(
      UnitId unitId, HexCoord at, GovDaily.GovResource resource, long requested, long day) {
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(resource, "resource");
    if (requested <= 0L) {
      return 0L; // GovDaily 只对 > 0 的请求调本方法；防御性返回 0，不制造"负付款"。
    }
    Unit unit = units.units().get(unitId);
    if (unit == null) {
      throw new IllegalStateException(
          "GovDaily 付款 oracle 找不到 GOV 单位（状态损坏；GovDaily 本应先抛）: " + unitId);
    }
    HouseholdId treasury =
        GovernmentHouseholdResolver.requireGovernmentHousehold(unit, unitId.value());
    AccountSession.ActorAccount account = accounts.householdAccount(treasury);
    if (account == null) {
      throw new IllegalStateException(
          "政府家户账户不在本会话里（拒绝付 0/拒绝对看不见的账付款）：unit="
              + unitId.value()
              + " treasury="
              + treasury.value());
    }
    HouseholdInventory view =
        new HouseholdInventory(
            new HouseholdAccountKey(account.household()),
            account.goods(),
            account.money(),
            account.frozenGoods(),
            account.frozenMoney());
    long available = availableOf(view, resource);
    if (resource instanceof GovDaily.Money moneyResource) {
      // ★ Z7b：俸禄冻结为实物粮/布；银腿是“非家户的衙门开销”，保留既有 sink 口径（不发明第二个收款方）。
      return payMoneyToSink(unitId, treasury, moneyResource, requested, available, day);
    }
    if (!(resource instanceof GovDaily.Commodity commodity)) {
      throw new IllegalArgumentException("未知 GovResource: " + resource);
    }
    return payCommodityToOfficials(unit, unitId, treasury, commodity, requested, available, day);
  }

  /** 银腿：既有 sink 路径逐字保留（GOV_UPKEEP_NO_STOCK / GOV_UPKEEP_PAID 读数不变）。 */
  private long payMoneyToSink(
      UnitId unitId,
      HouseholdId treasury,
      GovDaily.Money resource,
      long requested,
      long available,
      long day) {
    long paid = Math.min(available, requested);
    if (paid <= 0L) {
      logNoStock(unitId, treasury, resource, requested, available, day);
      return 0L;
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(resource.currency(), paid);
    StockDeductionService.deduct(
        accounts,
        HouseholdStockDeduction.sink(
            treasury,
            Map.of(),
            money,
            DeductionReason.ADMIN_UPKEEP,
            "unit="
                + unitId.value()
                + " resource="
                + resource.name()
                + " requested="
                + requested
                + " paid="
                + paid
                + " availableBefore="
                + available),
        day);
    if (TRACE.isTraceEnabled()) {
      tracePaid(unitId, treasury, "<sink>", resource, paid, paid, available, day);
    }
    return paid;
  }

  /**
   * 粮/布腿：从国库转给官吏户。总实付 = {@code min(可用, 请求)}，按承诺份额用 {@link ProportionalSplit} 精确切分（Σ = 总实付，整数、无
   * double）；某户账户缺失 ⇒ 该户份额具名不落账（留国库 = GovDaily 记录的缺口），其余户照付。
   */
  private long payCommodityToOfficials(
      Unit unit,
      UnitId unitId,
      HouseholdId treasury,
      GovDaily.Commodity commodity,
      long requested,
      long available,
      long day) {
    List<StipendTarget> targets = stipendTargets(unit, treasury, unitId, day);
    if (targets.isEmpty()) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "GOV_UPKEEP_RECIPIENT_MISSING",
                  AppLogSource.DAILY_LOOP,
                  "day",
                  day,
                  "unit",
                  unitId.value(),
                  "treasury",
                  treasury.value(),
                  "resource",
                  commodity.name(),
                  "requested",
                  requested,
                  "reason",
                  "no-official-household"));
      return 0L;
    }
    long paidTotal = Math.min(available, requested);
    if (paidTotal <= 0L) {
      logNoStock(unitId, treasury, commodity, requested, available, day);
      return 0L;
    }
    long[] weights = new long[targets.size()];
    long denominator = 0L;
    for (int index = 0; index < targets.size(); index++) {
      weights[index] = targets.get(index).weight();
      denominator = Math.addExact(denominator, weights[index]);
    }
    if (denominator == 0L) {
      // 承诺全 0（例：有编制但无 GOV_SERVICE 承诺行的旧世界）⇒ 在官吏户间等分，仍不发明份额。
      for (int index = 0; index < weights.length; index++) {
        weights[index] = 1L;
      }
      denominator = weights.length;
    }
    long[] shares = ProportionalSplit.byDenominator(paidTotal, weights, denominator);
    long paidSum = 0L;
    for (int index = 0; index < targets.size(); index++) {
      StipendTarget target = targets.get(index);
      long share = shares[index];
      if (share <= 0L) {
        continue; // 权重 0 的户分不到；不是缺口（它不在本腿的承诺里）。
      }
      if (accounts.householdAccount(target.household()) == null) {
        EventLog.channel(LOG)
            .error(
                LogEvent.of(
                    "GOV_UPKEEP_RECIPIENT_ACCOUNT_MISSING",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "unit",
                    unitId.value(),
                    "treasury",
                    treasury.value(),
                    "household",
                    target.household().value(),
                    "resource",
                    commodity.name(),
                    "share",
                    share));
        continue;
      }
      Map<CommodityId, Long> goods = new LinkedHashMap<>();
      goods.put(commodity.commodity(), share);
      StockDeductionService.deduct(
          accounts,
          HouseholdStockDeduction.transfer(
              treasury,
              target.household(),
              goods,
              Map.of(),
              DeductionReason.ADMIN_UPKEEP,
              "unit="
                  + unitId.value()
                  + " resource="
                  + commodity.name()
                  + " requested="
                  + requested
                  + " paidTotal="
                  + paidTotal
                  + " payee="
                  + target.household().value()
                  + " share="
                  + share
                  + " availableBefore="
                  + available),
          day);
      paidSum = Math.addExact(paidSum, share);
      if (TRACE.isTraceEnabled()) {
        tracePaid(
            unitId, treasury, target.household().value(), commodity, share, share, available, day);
      }
    }
    return paidSum;
  }

  /**
   * 本 GOV 的俸禄分摊目标（确定序 = {@link HouseholdId#value()} 升序）：{@code Unit.households()} 去掉国库户本身；权重 = 各户
   * {@code GOV_SERVICE} 承诺劳动（{@code economy == null} 或全 0 ⇒ 等权 1）。★ 目标列表在本 oracle 内按 GOV 缓存： 承诺在
   * revision 内不变，生产里 oracle 每日新建。
   */
  private List<StipendTarget> stipendTargets(
      Unit unit, HouseholdId treasury, UnitId unitId, long day) {
    return stipendTargetsByUnit.computeIfAbsent(
        unitId, ignored -> computeStipendTargets(unit, treasury, unitId, day));
  }

  private List<StipendTarget> computeStipendTargets(
      Unit unit, HouseholdId treasury, UnitId unitId, long day) {
    List<HouseholdId> candidates = new ArrayList<>();
    for (HouseholdId household : unit.households()) {
      if (!household.equals(treasury)) {
        candidates.add(household);
      }
    }
    if (candidates.isEmpty()) {
      return List.of();
    }
    candidates.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, Long> committed =
        economy == null
            ? Map.of()
            : GovernmentServiceLaborBridge.committedLaborByHousehold(economy, unitId, day);
    List<StipendTarget> targets = new ArrayList<>(candidates.size());
    for (HouseholdId household : candidates) {
      long weight = committed.getOrDefault(household, 0L);
      if (weight < 0L) {
        throw new IllegalStateException(
            "GOV_SERVICE 承诺劳动不得为负（状态损坏）: household=" + household.value());
      }
      targets.add(new StipendTarget(household, weight));
    }
    return List.copyOf(targets);
  }

  private void logNoStock(
      UnitId unitId,
      HouseholdId treasury,
      GovDaily.GovResource resource,
      long requested,
      long available,
      long day) {
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "GOV_UPKEEP_NO_STOCK",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "treasury",
                treasury.value(),
                "resource",
                resource.name(),
                "requested",
                requested,
                "available",
                available));
  }

  private void tracePaid(
      UnitId unitId,
      HouseholdId treasury,
      String payee,
      GovDaily.GovResource resource,
      long paid,
      long recipientShare,
      long available,
      long day) {
    EventLog.channel(TRACE)
        .trace(
            LogEvent.of(
                "GOV_UPKEEP_PAID",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "treasury",
                treasury.value(),
                "payee",
                payee,
                "resource",
                resource.name(),
                "paid",
                paid,
                "recipientShare",
                recipientShare,
                "availableBefore",
                available));
  }

  /** 可用量 = {@link AvailableStock} 的唯一算法（商品/货币各走对应重载）。 */
  private static long availableOf(HouseholdInventory inventory, GovDaily.GovResource resource) {
    if (resource instanceof GovDaily.Commodity commodity) {
      return AvailableStock.available(inventory, commodity.commodity());
    }
    if (resource instanceof GovDaily.Money money) {
      return AvailableStock.available(inventory, money.currency());
    }
    throw new IllegalArgumentException("未知 GovResource: " + resource);
  }

  /** 一个俸禄分摊目标（官吏户 + 承诺权重）。 */
  private record StipendTarget(HouseholdId household, long weight) {
    private StipendTarget {
      Objects.requireNonNull(household, "household");
      if (weight < 0L) {
        throw new IllegalArgumentException("StipendTarget.weight 不得为负: " + weight);
      }
    }
  }
}
