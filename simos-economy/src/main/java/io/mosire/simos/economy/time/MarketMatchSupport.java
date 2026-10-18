package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketMatcher.copyUnmet;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketMatchSupport {

  private MarketMatchSupport() {}

  /**
   * ★★ <b>2026-10-10 G3-fix-1：这个 unit 的经营者是不是"按构造不是家户行"的合成聚合经营者</b>。
   *
   * <p>★★ <b>为什么必须与"真坏数据"分开</b>：{@code participantsFor} 的第 ⑤ 分支（有正资产、却连劳动家户都解析不到）
   * 是**具名抛**的守卫，它防的是"经营者指着不存在的主体"这类坏数据（{@code
   * docs/superpowers/specs/2026-10-23-weave-not-a-market-subject.md} §3.4 明文要求保留）。但**聚合经营**（如家户纺织
   * {@code weave@<hex>}）的经营者**按构造就不是家户行**： {@code EconomySeeder} 不显式给 operator ⇒ 走 {@link
   * RegimeOperators#defaultOperator} 的合成 actor （{@code
   * HOUSEHOLD:weave@<hex>}），而它的账户主体是**名下劳动家户的集合**（{@link HouseholdRouting} ③）。
   * 成员户一旦全部消亡/被迁移删行（{@link ModeMigrationSettlement} 的 {@code retireSource} 会删人口归零的成员户行）， 这个 unit
   * 就落到第 ⑤ 分支 ⇒ 真实 world 复测里以"整条 AdvanceTime 不落 revision"的形式硬崩（day38）。
   *
   * <p>判据（两件同时成立，纯查表、无副作用）：
   *
   * <pre>
   * ① unit.operator **恰是**该 unit 产业的合成默认经营者
   *    {@link RegimeOperators#defaultOperator}(industry.regime(), industry.id())
   *    —— regime 未登记（该方法抛）⇒ 判据不成立（真坏数据仍走具名抛）
   * ② 该 operator **不是现存家户行**（{@link HouseholdRouting#householdOfActorOrNull} 为空）
   *    —— 显式换上组织者家户的 farm/craft/trade **不满足 ①**；换成真家户的 unit **不满足 ②**
   * </pre>
   *
   * <p>⇒ 只有"聚合 unit + 组织已无主"这一种合法终态被认出；绕过它就得伪造一个不存在的家户（铁律 1/3 禁止）。
   *
   * @param unitId 该 unit（必须在 {@code round.units} 里，调用方已保证）
   * @param operator 该 unit 的经营者 actor
   * @param round 本轮只读视图（取产业模板与现存家户行）
   */
  static boolean isUnresolvedAggregateOperator(
      ProductionUnitId unitId, ActorRef operator, MarketRound round) {
    if (operator.kind() != ActorKind.HOUSEHOLD) {
      return false; // 合成默认经营者的制度（household / tenant）都是 HOUSEHOLD；其余一律不算
    }
    ProductionProcess unit = round.units.get(unitId);
    IndustryDefinitionView industry = unit == null ? null : round.industries.get(unit.industry());
    if (unit == null || industry == null || industry.regime() == null) {
      return false;
    }
    ActorRef derived;
    try {
      derived = RegimeOperators.defaultOperator(industry.regime(), industry.id());
    } catch (RuntimeException unregisteredRegime) {
      // 制度未登记 ⇒ 推不出合成经营者 ⇒ 判据不成立（该 unit 仍走具名抛，fail-closed 不放宽）。
      return false;
    }
    if (!operator.equals(derived)) {
      return false; // 显式换上的组织者/经营者家户（或别的写法）⇒ 不属于本条
    }
    return HouseholdRouting.householdOfActorOrNull(operator, round.householdEconomies).isEmpty();
  }

  /**
   * ★★ <b>2026-10-09 D3：市场参与者解析失败（有正资产却既无关联家户、也无劳动家户）的具名诊断</b>。
   *
   * <p>先记 {@code MARKET_SUBJECT_UNRESOLVED_UNIT} ERROR（契约/跨切片一致性故障不降级），再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。旧实现只抛一句话（日志里只剩 {@code
   * error=IllegalStateException}），长跑现场完全看不出是哪个 unit / 哪个 operator / 它挂在哪一格 —— 本方法把"谁、在哪、为什么"
   * 一次性写进日志与异常正文。字段一律是身份与计数，不含载荷明文。
   */
  static IllegalStateException unresolvedMarketSubject(
      MarketRound round,
      ProductionUnitId unitId,
      ActorRef operator,
      Map<AssetKind, Long> assetAllocation) {
    ProductionProcess unit = round.units.get(unitId);
    ProductionRules relation = round.relations.get(unitId);
    List<String> shareOwners = new ArrayList<>();
    List<String> shareOperators = new ArrayList<>();
    for (AssetShareId shareId : round.index.ownershipStakeIdsOfProcess(unitId)) {
      OwnershipStake share = round.assetShares.get(shareId);
      if (share == null) {
        continue;
      }
      if (shareOwners.size() < 8) {
        shareOwners.add(share.owner().toString());
      }
      if (shareOperators.size() < 8) {
        shareOperators.add(share.operator().toString());
      }
    }
    // operator 若本身就是家户 actor，则把它的家户身份与"经济行是否还在"一并记出来 —— 这两者一起才能回答
    // "是家户行被删了（坏数据）"还是"operator 从来就不是家户（聚合主体，需靠 relation/份额解析）"。
    HouseholdId operatorHousehold = null;
    boolean operatorIsHousehold = operator.kind() == ActorKind.HOUSEHOLD;
    if (operatorIsHousehold) {
      operatorHousehold = HouseholdActors.householdOf(operator);
    }
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MARKET_SUBJECT_UNRESOLVED_UNIT",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "unit",
                unitId.value(),
                "operator",
                operator,
                "operatorKind",
                operator.kind(),
                "operatorHousehold",
                operatorHousehold == null ? "none" : operatorHousehold.value(),
                "operatorHouseholdRow",
                operatorHousehold != null
                    && round.householdEconomies.containsKey(operatorHousehold),
                "hex",
                round.index.hexOf(unitId) == null ? "none" : round.index.hexOf(unitId),
                "industry",
                unit == null ? "none" : unit.industry(),
                "modeKey",
                unit == null ? "none" : unit.modeKey(),
                "assetAllocation",
                assetAllocation,
                "shareOwners",
                shareOwners,
                "shareOperators",
                shareOperators,
                "relationResidualOwner",
                relation == null ? "none" : relation.residualOwner().toString(),
                "relationInputSupplier",
                relation == null ? "none" : relation.inputSupplier().toString(),
                "laborAllocations",
                round.index.allocationsOfUnit(unitId).size(),
                "reason",
                "positive-asset-but-no-economic-household-and-no-labor-household"));
    return new IllegalStateException(
        "市场参与者无法解析到任何家户（账户主体只有家户；聚合主体必须能解析到组织者/经营者家户）："
            + "day="
            + round.day
            + " unit="
            + unitId.value()
            + " operator="
            + operator
            + " operatorKind="
            + operator.kind()
            + " operatorHousehold="
            + (operatorHousehold == null ? "none" : operatorHousehold.value())
            + " operatorHouseholdRow="
            + (operatorHousehold != null && round.householdEconomies.containsKey(operatorHousehold))
            + " hex="
            + round.index.hexOf(unitId)
            + " industry="
            + (unit == null ? "none" : unit.industry())
            + " modeKey="
            + (unit == null ? "none" : unit.modeKey())
            + " assetAllocation="
            + assetAllocation
            + " shareOwners="
            + shareOwners
            + " shareOperators="
            + shareOperators
            + " relationResidualOwner="
            + (relation == null ? "none" : relation.residualOwner())
            + " relationInputSupplier="
            + (relation == null ? "none" : relation.inputSupplier())
            + " laborAllocations="
            + round.index.allocationsOfUnit(unitId).size());
  }

  /**
   * ★★ <b>2026-10-08：克隆丢字段的具名契约故障</b>（防复发守卫的唯一发射点）。
   *
   * <p>先记 {@code MARKET_ARBITRAGE_PLAN_LOST} ERROR（契约/跨切片一致性故障不降级），再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。{@code reason} 只含数量，不含载荷明文。
   */
  static IllegalStateException arbitragePlanLostByClone(int motherHouseholds, int cloneHouseholds) {
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MARKET_ARBITRAGE_PLAN_LOST",
                EconomyLogSource.ECONOMY_ARBITRAGE,
                "motherHouseholds",
                motherHouseholds,
                "cloneHouseholds",
                cloneHouseholds,
                "reason",
                "planning-round-clone-dropped-arbitrage-plan"));
    return new IllegalStateException(
        "市场轮的克隆丢了套利计划（母轮 "
            + motherHouseholds
            + " 户 / 克隆轮 "
            + cloneHouseholds
            + " 户）：订单生成用的是克隆轮，丢字段会让整个套利静默不生效。"
            + "克隆必须走 MarketRound.copyForWorker 这个唯一拼写点。");
  }

  /** ★ 区内一笔成交的不可变意向：worker 产出，协调器按区序/成交序回放（索引 = ctx.buys/ctx.sells 的全局下标）。 */
  record FillIntent(
      int buyIndex,
      int sellIndex,
      long quantity,
      long unitPrice,
      String regionId,
      CommodityId commodity,
      String sellerKey,
      String buyerKey) {}

  /** worker 交回的买槽终态（noMoney/blocked 只可能由本区撮合改变；remaining/frozen/spent 会在回放后校验）。 */
  record BuySlotState(
      int orderIndex,
      long remaining,
      long frozenRemaining,
      long spentMilli,
      boolean noMoney,
      MarketUnfilledReason blocked,
      // ★★ M-A1：被运力截断量必须随状态带回协调器（否则并行路径下 V-20 静默失效：协调器侧恒 0）。
      long capacityTruncatedMilli) {}

  /** worker 交回的卖槽终态。 */
  record SellSlotState(
      int orderIndex,
      long remaining,
      long frozenRemaining,
      MarketUnfilledReason blocked,
      boolean capacityBlocked,
      long capacityTruncatedMilli) {}

  /** 一个区的 worker 产物（回放序 = 协调器遍历 topology.regions() 的区序；区与区之间的账户不重叠）。 */
  record RegionOutcome(
      String regionId,
      List<FillIntent> fills,
      List<BuySlotState> buyStates,
      List<SellSlotState> sellStates,
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday) {

    RegionOutcome {
      Objects.requireNonNull(regionId, "regionId");
      fills = List.copyOf(fills);
      buyStates = List.copyOf(buyStates);
      sellStates = List.copyOf(sellStates);
      unmetToday = copyUnmet(unmetToday);
    }
  }
}
