package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.FxQuoteBook.rejectOnce;
import static io.mosire.simos.economy.time.FxSettlement.FX;
import static io.mosire.simos.economy.time.FxSettlement.FX_MIN_LOT_BASE_MILLI;
import static io.mosire.simos.economy.time.FxSettlement.HouseholdPlan;
import static io.mosire.simos.economy.time.FxSettlement.Order;
import static io.mosire.simos.economy.time.FxSettlement.WindowState;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.fx.FxFill;
import io.mosire.simos.economy.api.fx.FxRejectReason;
import io.mosire.simos.economy.api.fx.FxVenue;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** FX 拆分 helper（由 FxSettlement 抽取；行为不变）。 */
final class FxMatchBook {

  private FxMatchBook() {}

  // ── 撮合一本（一个币对）────────────────────────────────────────────────────────

  static void matchBook(
      MarketSettlement.MarketRound round,
      List<Order> book,
      List<FxFill> fills,
      List<FxRoundResult.NamedRejection> rejections) {
    List<Order> bids = new ArrayList<>();
    List<Order> asks = new ArrayList<>();
    for (Order order : book) {
      if (order.buy) {
        bids.add(order);
      } else {
        asks.add(order);
      }
    }
    bids.sort(
        Comparator.comparingLong((Order o) -> -o.limitPerMille).thenComparing(Order::ownerRank));
    asks.sort(
        Comparator.comparingLong((Order o) -> o.limitPerMille).thenComparing(Order::ownerRank));
    int i = 0;
    int j = 0;
    while (i < bids.size() && j < asks.size()) {
      Order bid = bids.get(i);
      Order ask = asks.get(j);
      if (bid.remaining < FX_MIN_LOT_BASE_MILLI) {
        i++;
        continue;
      }
      if (ask.remaining < FX_MIN_LOT_BASE_MILLI) {
        j++;
        continue;
      }
      if (bid.limitPerMille < ask.limitPerMille) {
        break; // 最好的买价都不再交叉 ⇒ 后面的更不会
      }
      if (bid.owner.equals(ask.owner)) {
        // 契约/一致性故障（生产路径上被相反报价判据与家户单互斥挡住 ⇒ 不可达）：fail-closed 作废两条并记
        // ERROR，绝不"自己跟自己成交"（§一.9：契约故障不降级）。
        bid.remaining = 0L;
        ask.remaining = 0L;
        EventLog.channel(FX)
            .error(
                LogEvent.of(
                    "FX_SELF_TRADE_BLOCKED",
                    EconomyLogSource.ECONOMY_FX,
                    "day",
                    round.day(),
                    "owner",
                    bid.owner,
                    "base",
                    bid.base.value(),
                    "quote",
                    bid.quote.value()));
        i++;
        j++;
        continue;
      }
      long price;
      try {
        price = FxPricing.fillPricePerMille(bid.limitPerMille, ask.limitPerMille);
      } catch (IllegalArgumentException e) {
        break;
      }
      long buyerMoney = MarketSettlement.spendableMoneyOf(round, bid.household, bid.quote);
      long sellerBase = MarketSettlement.spendableMoneyOf(round, ask.household, ask.base);
      long maxByMoney = GovFxWindow.mulDivFloor(buyerMoney, 1000L, price);
      long quantity =
          Math.min(Math.min(bid.remaining, ask.remaining), Math.min(maxByMoney, sellerBase));
      if (quantity < FX_MIN_LOT_BASE_MILLI) {
        // 谁把它卡住的，就把谁移出簿（具名一次），绝不留一个"看起来还能成交"的槽位。
        Order blocked = maxByMoney <= sellerBase ? bid : ask;
        rejectOnce(
            round.day(),
            rejections,
            new FxRoundResult.NamedRejection(
                FxRejectReason.INSUFFICIENT_FUNDS,
                blocked.owner,
                blocked.base,
                blocked.quote,
                blocked.remaining,
                0L,
                blocked.buy
                    ? "买方本地货币可花额不足（" + buyerMoney + " 毫 " + blocked.quote.value() + "）"
                    : "卖方外币可花额不足（" + sellerBase + " 毫 " + blocked.base.value() + "）"),
            blocked.window ? "FX_REJECTED" : "FX_HOUSEHOLD_UNFILLED",
            blocked.window);
        blocked.remaining = 0L;
        if (blocked == bid) {
          i++;
        } else {
          j++;
        }
        continue;
      }
      long quoteLeg = GovFxWindow.quotePerMille(quantity, price);
      if (quoteLeg > buyerMoney) {
        quantity -= 1L; // ceil 的边界：绝不越过买方实际可花额
        if (quantity < FX_MIN_LOT_BASE_MILLI) {
          bid.remaining = 0L;
          i++;
          continue;
        }
        quoteLeg = GovFxWindow.quotePerMille(quantity, price);
      }
      boolean windowInvolved = bid.window || ask.window;
      TransferReason legReason =
          windowInvolved ? TransferReason.GOV_FX_WINDOW : TransferReason.FX_TRADE;
      // ★ 有家户参与时逐字保持旧口径（家户那一侧的格优先）⇒ 既有世界的 location 一个数都不动；
      //   ★ 同币对多窗口（G3）下**两条窗口单之间**也可能交叉（GOV-A 的买价 ≥ GOV-B 的卖价）——
      //     此时两侧没有家户格 ⇒ 取卖方（窗口）的格 = 其国库户所在格（见 Order.window 的 hex）。
      //     （改前这里恒取 `ask.hex != null ? ask.hex : bid.hex` ⇒ 两侧都是窗口时 location = null，
      //      applyTransfer 当场抛「Transfer.location 不得为 null」，整轮日结算被炸掉。）
      HexCoord location = ask.window ? bid.hex : ask.hex;
      // base 腿：卖方 → 买方；quote 腿：买方 → 卖方。两条腿都走唯一写口（铁律 2）。
      applyLeg(
          round,
          round
              .ledger()
              .mint(
                  ask.owner, bid.owner, location, Map.of(), Map.of(ask.base, quantity), legReason));
      applyLeg(
          round,
          round
              .ledger()
              .mint(
                  bid.owner,
                  ask.owner,
                  location,
                  Map.of(),
                  Map.of(ask.quote, quoteLeg),
                  legReason));
      bid.remaining -= quantity;
      ask.remaining -= quantity;
      FxFill fill =
          new FxFill(
              round.day(),
              location,
              ask.regionId,
              ask.base,
              ask.quote,
              quantity,
              quoteLeg,
              price,
              bid.owner,
              ask.owner,
              windowInvolved ? FxVenue.GOV_WINDOW : FxVenue.HOUSEHOLD);
      fills.add(fill);
      if (bid.window) {
        bid.windowState.buyFilledBase += quantity;
        bid.windowState.buyQuotePaid += quoteLeg;
      }
      if (ask.window) {
        ask.windowState.sellFilledBase += quantity;
        ask.windowState.sellQuoteReceived += quoteLeg;
      }
      EventLog.channel(FX)
          .trace(
              LogEvent.of(
                  "FX_FILL",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "base",
                  fill.base().value(),
                  "quote",
                  fill.quote().value(),
                  "baseMilli",
                  quantity,
                  "quoteMilli",
                  quoteLeg,
                  "pricePerMille",
                  price,
                  "venue",
                  fill.venue().wire(),
                  "buyer",
                  fill.buyer(),
                  "seller",
                  fill.seller()));
    }
  }

  // ── 未成交归因 ──────────────────────────────────────────────────────────────────

  static void attributeResidue(
      long day, Map<String, List<Order>> books, List<FxRoundResult.NamedRejection> rejections) {
    for (List<Order> book : books.values()) {
      long bids = 0L;
      long asks = 0L;
      for (Order candidate : book) {
        if (candidate.buy) {
          bids++;
        } else {
          asks++;
        }
      }
      for (Order order : book) {
        if (order.remaining <= 0L) {
          continue;
        }
        if (order.window) {
          if (order.buy) {
            order.windowState.unfilledBuyBase += order.remaining;
            // 政府买入没有对手方（M3）：窗口摆了买盘，但这一轮没有可成交的家户卖单。
            rejectOnce(
                day,
                rejections,
                new FxRoundResult.NamedRejection(
                    FxRejectReason.NO_COUNTERPARTY,
                    order.owner,
                    order.base,
                    order.quote,
                    order.originalQuantity,
                    order.originalQuantity - order.remaining,
                    "政府买入无对手方（本轮没有可成交的家户卖单 / 家户卖盘已吃尽）"),
                "FX_REJECTED",
                true);
          } else {
            order.windowState.unfilledSellBase += order.remaining;
          }
          continue;
        }
        // 家户残量：说清"还剩多少、为什么"（结算序的最后一层归因；不准静默）。
        //   ★ 两个具名档的区别是**事实层面**的：对面一侧压根没有挂单 ⇒ NO_COUNTERPARTY（"没人接"）；
        //     对面有挂单但限价不交叉 ⇒ NO_CROSS（"价不拢"）；部分成交后把对面吃尽 ⇒ NO_COUNTERPARTY。
        //   ★★ P-T5 的日志级别：家户残量是**逐笔**读数（本批 §一.9 口径：INFO = 本轮汇总、DEBUG = 判据、
        //     TRACE = 逐笔）⇒ 走 TRACE；它仍逐条进 rejections（读口与"复核未成交"不受影响），绝不静默。
        //     ★ 窗口单的拒保持原有 INFO（那是"政府窗口停做/无对手方"这类政策面事实，级别不降）。
        boolean oppositePresent = order.buy ? asks > 0L : bids > 0L;
        FxRejectReason residueReason =
            order.originalQuantity != order.remaining || !oppositePresent
                ? FxRejectReason.NO_COUNTERPARTY
                : FxRejectReason.NO_CROSS;
        rejectOnce(
            day,
            rejections,
            new FxRoundResult.NamedRejection(
                residueReason,
                order.owner,
                order.base,
                order.quote,
                order.originalQuantity,
                order.originalQuantity - order.remaining,
                "本轮对手盘不足（剩余 " + order.remaining + " 毫 " + order.base.value() + "）"),
            "FX_HOUSEHOLD_UNFILLED",
            false);
      }
    }
  }

  // ── 组装读数 ────────────────────────────────────────────────────────────────────

  static FxRoundResult finish(
      MarketSettlement.MarketRound round,
      List<WindowState> windows,
      HouseholdPlan plan,
      List<FxFill> fills,
      List<FxRoundResult.NamedRejection> rejections) {
    List<FxRoundResult.WindowOutcome> outcomes = new ArrayList<>();
    for (WindowState window : windows) {
      outcomes.add(
          new FxRoundResult.WindowOutcome(
              window.spec.governmentId(),
              window.quote.base(),
              window.quote.quote(),
              window.quote.bidPerMille(),
              window.quote.askPerMille(),
              window.quote.reserveBaseMilli(),
              window.quote.reserveCapBaseMilli(),
              window.quote.buyCapacityBaseMilli(),
              window.quote.sellCapacityBaseMilli(),
              window.buyFilledBase,
              window.sellFilledBase,
              window.buyQuotePaid,
              window.sellQuoteReceived,
              window.unfilledBuyBase,
              window.unfilledSellBase,
              window.quote.buyBlocked(),
              window.quote.sellBlocked()));
    }
    FxRoundResult result = new FxRoundResult(fills, outcomes, rejections);
    // ★★ §一.9（本批口径）：INFO = <b>本轮换汇汇总</b>——参与户数 / 币对数 / 成交量（含家户侧），另有窗口数与逐笔拒数。
    //   ★ 触发条件加上 plan.orders() > 0：本轮"挂了单但一笔没成"也是换汇面发生过的事，不许静默。
    if (!fills.isEmpty() || !rejections.isEmpty() || plan.orders() > 0) {
      EventLog.channel(FX)
          .info(
              LogEvent.of(
                  "FX_ROUND",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "windows",
                  windows.size(),
                  "households",
                  plan.households(),
                  "orders",
                  plan.orders(),
                  "pairs",
                  plan.pairs(),
                  "fills",
                  fills.size(),
                  "baseVolumeMilli",
                  baseVolume(fills),
                  "windowFills",
                  windowFillCount(fills),
                  "householdFills",
                  householdFillCount(fills),
                  "householdBaseVolumeMilli",
                  householdBaseVolume(fills),
                  "rejections",
                  rejections.size()));
    }
    return result;
  }

  static long baseVolume(List<FxFill> fills) {
    long volume = 0L;
    for (FxFill fill : fills) {
      volume = Math.addExact(volume, fill.baseMilli());
    }
    return volume;
  }

  static long windowFillCount(List<FxFill> fills) {
    long count = 0L;
    for (FxFill fill : fills) {
      if (fill.windowInvolved()) {
        count++;
      }
    }
    return count;
  }

  /** ★ P-T5：本轮<b>不涉及窗口</b>（民间簿内部成交）的笔数。 */
  static long householdFillCount(List<FxFill> fills) {
    long count = 0L;
    for (FxFill fill : fills) {
      if (!fill.windowInvolved()) {
        count++;
      }
    }
    return count;
  }

  /** ★ P-T5：本轮<b>不涉及窗口</b>的成交量（base 最小单位；与 baseVolume 同一量纲、同一求和口径）。 */
  static long householdBaseVolume(List<FxFill> fills) {
    long volume = 0L;
    for (FxFill fill : fills) {
      if (!fill.windowInvolved()) {
        volume = Math.addExact(volume, fill.baseMilli());
      }
    }
    return volume;
  }

  /** 两腿都经唯一写口 {@code EconomySettlement.applyTransfer}（铁律 2）。 */
  static void applyLeg(MarketSettlement.MarketRound round, Transfer transfer) {
    EconomySettlement.applyTransfer(
        round.householdGoods(),
        round.householdMoney(),
        round.householdFrozenGoods(),
        round.householdFrozenMoney(),
        round.householdOfActor(),
        transfer);
  }
}
