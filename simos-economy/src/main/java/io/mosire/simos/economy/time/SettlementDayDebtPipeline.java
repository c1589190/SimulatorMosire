package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.DebtSettlement.marketIndexByHousehold;
import static io.mosire.simos.economy.time.MarketLifecycleSettlement.applyGovMarketMandateLifecycle;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 日结算阶段执行器（包内瞬态；只由 EconomyDayPipeline 按固定顺序调用）。 */
final class SettlementDayDebtPipeline {

  private SettlementDayDebtPipeline() {}

  /**
   * ★★★ <b>P2e：市场日债务最先清算（M2）</b>。
   *
   * <p>位置 = 市场轮最前面（消费/生产/市场之前）：先冻结纯库存财富快照，按债权人纯库存财富降序受偿， 介质固定为「货币 → 商品库存 → 土地」，土地整块直接抵债。清算后立即重建
   * {@link SettlementIndex}， 让后续生产/市场读取新产权。M2 不生成任何市场订单、不写 Pledge、不新增商品债。
   */
  static void settleDebtsAtMarketOpen(SettlementDayContext ctx) {
    // ★★★ P2e-fix：家户 actor → HouseholdId 的反查表原本只在 prepareConsumption 里建；
    //   M2 现在跑在它之前，货币/商品还款腿的 applyTransfer 需要同一张有效映射。
    //   唯一来源 = 入口已建好的 SettlementIndex.householdByActor()（与 prepareConsumption 同一份派生），
    //   不传 null、不伪造空 Map（空 Map 会让转账静默找不到家户）。
    ctx.householdOfActor = requireHouseholdOfActor(ctx, "initial");
    CurrencyValuation currencyValuation = currencyValuationAtOpen(ctx);
    PureInventoryWealthBook.Snapshot wealthSnapshot =
        PureInventoryWealthBook.snapshot(
            ctx.day,
            ctx.householdEconomies,
            ctx.householdGoods,
            ctx.householdMoney,
            ctx.assetShares,
            ctx.markets,
            ctx.topology,
            currencyValuation);
    DebtFirstSettlement.settleAtMarketOpen(ctx, wealthSnapshot, currencyValuation);
    rebuildSettlementIndex(ctx);
    // ★ 重建后仍取同一派生源的家户反查表；不是空 Map，也不改变身份集合。
    ctx.householdOfActor = requireHouseholdOfActor(ctx, "rebuilt");
  }

  /** 从当刻 SettlementIndex 取有效的家户 actor 反查表；缺失即契约故障（不传 null / 空 Map）。 */
  private static Map<ActorRef, HouseholdId> requireHouseholdOfActor(
      SettlementDayContext ctx, String stage) {
    if (ctx.settlementIndex == null) {
      throw new IllegalStateException(
          "P2e-M2 householdOfActor-unavailable: " + stage + " SettlementIndex 缺失");
    }
    Map<ActorRef, HouseholdId> householdOfActor = ctx.settlementIndex.householdByActor();
    if (householdOfActor == null) {
      throw new IllegalStateException(
          "P2e-M2 householdOfActor-unavailable: "
              + stage
              + " SettlementIndex.householdByActor() 返回 null");
    }
    return householdOfActor;
  }

  /**
   * ★ 市场成交之后的非本金偿还事实：政府市场授权生命周期 + 债务估值 lookup 刷新。
   *
   * <p>★★ P2e 起这里<b>不再偿还本金</b>（本金唯一入口 = {@link #settleDebtsAtMarketOpen}）； {@code anyCycleClosed}
   * 只继续服务计息/关账/阶层等非本金事实。生命周期仍在市场成交之后，不随 M2 前移。
   */
  static void settleDebt(SettlementDayContext ctx) {
    applyGovMarketMandateLifecycle(ctx.session, ctx.day, ctx.govMandateFills);
    // ★★ D-030 §3.4/§3.5：本日起所有“债务折价/还款折算”共用同一份家户价目表索引。
    ctx.marketByHousehold =
        marketIndexByHousehold(ctx.householdEconomies, ctx.markets, ctx.topology);
    ctx.debtUnitValueLookup = DebtCapacityBook.marketPriceLookup(ctx.marketByHousehold);
  }

  /** M2 的行情来源与市场轮同一份装配口径：官方汇率 + 当地实际流通币种 + 口岸管制。 */
  static CurrencyValuation currencyValuationAtOpen(SettlementDayContext ctx) {
    FxRoundInput fx =
        ctx.dayView == null
            ? FxRoundInput.none()
            : FxRoundInput.of(
                ctx.dayView.governments(), ctx.dayView.moneyIssuances(), ctx.dayView.marketZones());
    Map<String, List<HouseholdId>> rowsByHex = EconomySettlement.rowsByHex(ctx.householdEconomies);
    Map<String, Set<CurrencyId>> circulation =
        ctx.topology == null
            ? Map.of()
            : CurrencyValuation.circulationByRegion(ctx.topology, rowsByHex, ctx.householdMoney);
    return CurrencyValuation.of(
        fx,
        circulation,
        ctx.portEnforcement == null ? PortEnforcementInput.none() : ctx.portEnforcement);
  }

  /** 土地抵债改变产权后重建只读索引；后续生产/市场必须读到新 owner/operator。 */
  private static void rebuildSettlementIndex(SettlementDayContext ctx) {
    ctx.settlementIndex =
        SettlementIndex.build(
            ctx.units,
            ctx.industries,
            ctx.assetShares,
            ctx.laborCommitments,
            ctx.householdEconomies,
            ctx.debts,
            ctx.relations,
            ctx.session.sheet().productionOrganizations(),
            ctx.session.sheet().assetOccupanciesOrBase(),
            ctx.session.sheet().productionRostersOrBase(),
            ctx.day,
            ctx.landAcquiredThisRound());
  }
}
