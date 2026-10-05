package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.economy.EconomyLog;
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
import java.util.LinkedHashMap;
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
 * <p>★★ <b>失败语义（不静默付 0）</b>：
 *
 * <ul>
 *   <li><b>缺账 / 解析不出政府家户</b> ⇒ 当场 {@link IllegalStateException}（这是装配/状态故障；推进入口已有 {@code
 *       GovernmentHouseholdWiring.requireConsistent} 先判，正常路径不可达）；
 *   <li><b>余额不足</b> ⇒ 付 {@code min(可用, 请求)}（可能是 0），由 {@link GovDaily} 记 {@code shortfall} 并发 {@code
 *       ADMIN_SUPPLY} 信号——这是"记了缺口的 0"，不是静默；
 *   <li><b>可用量</b> = 余额 − 冻结，走 {@link AvailableStock} 的唯一算法；冻结表原样保留，付款不侵冻结。
 * </ul>
 *
 * <p>★ 付款走共享的 {@link StockDeductionService}（reason = {@link
 * DeductionReason#ADMIN_UPKEEP}，sink）：同一本账同一天按 {@link GovDaily} 的固定资源次序（grain → cloth →
 * money）串行发生，后一次看得到前一次的扣减。 账户提交阶段 = {@code SettlementStage.TAX_AND_UPKEEP}（组合根在 {@code
 * EconomyDayStepper.step(day)} 之后提交）。
 */
final class GovernmentUpkeepOracle implements GovDaily.PaymentOracle {

  /** 行政结算日志（settlement 分类）。 */
  private static final Logger LOG = EconomyLog.settlement();

  /** 逐笔日志（trace 分类；默认关闭）。 */
  private static final Logger TRACE = EconomyLog.trace();

  private final AccountSession accounts;
  private final UnitState units;

  GovernmentUpkeepOracle(AccountSession accounts, UnitState units) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.units = Objects.requireNonNull(units, "units");
  }

  @Override
  public long pay(UnitId unitId, HexCoord at, GovDaily.GovResource resource, long requested) {
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
    GoodsAccount view =
        new GoodsAccount(
            new GoodsAccountKey(account.household()),
            account.goods(),
            account.money(),
            account.frozenGoods(),
            account.frozenMoney());
    long available = availableOf(view, resource);
    long paid = Math.min(available, requested);
    if (paid <= 0L) {
      LOG.debug(
          "event=GOV_UPKEEP_NO_STOCK unit={} treasury={} resource={} requested={} available={}",
          unitId.value(),
          treasury.value(),
          resource.name(),
          requested,
          available);
      return 0L;
    }
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    if (resource instanceof GovDaily.Commodity commodity) {
      goods.put(commodity.commodity(), paid);
    } else if (resource instanceof GovDaily.Money moneyResource) {
      money.put(moneyResource.currency(), paid);
    } else {
      throw new IllegalArgumentException("未知 GovResource: " + resource);
    }
    // ★★ 2026-10-09 用户裁定：行政俸禄不再自己拼负增量 + 直接 AccountSession.commit，而是构造通用扣除（reason =
    //   ADMIN_UPKEEP）+ 调共享服务。★ 本路径无可信收款对端（GovDaily 旧合约：编制人员的俸禄付给"整编"而非具名家户）⇒
    //   显式 sink（日志记 to=<sink>），不假装有一笔转移；缺额语义仍由 GovDaily 记 shortfall + 发 ADMIN_SUPPLY 信号。
    StockDeductionService.deduct(
        accounts,
        HouseholdStockDeduction.sink(
            treasury,
            goods,
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
                + available));
    if (TRACE.isTraceEnabled()) {
      TRACE.trace(
          "event=GOV_UPKEEP_PAID unit={} treasury={} resource={} requested={} paid={}"
              + " availableBefore={}",
          unitId.value(),
          treasury.value(),
          resource.name(),
          requested,
          paid,
          available);
    }
    return paid;
  }

  /** 可用量 = {@link AvailableStock} 的唯一算法（商品/货币各走对应重载）。 */
  private static long availableOf(GoodsAccount account, GovDaily.GovResource resource) {
    if (resource instanceof GovDaily.Commodity commodity) {
      return AvailableStock.available(account, commodity.commodity());
    }
    if (resource instanceof GovDaily.Money money) {
      return AvailableStock.available(account, money.currency());
    }
    throw new IllegalArgumentException("未知 GovResource: " + resource);
  }
}
