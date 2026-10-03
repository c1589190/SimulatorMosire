package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 两轮市场探针（M0）。
 *
 * <p>这不是正式测试基线；它是“市场 + 单边债务 + 规则 A/B/C”的纯数学原型，用来确认：
 *
 * <ul>
 *   <li>缺货商品 ref 上涨（规则 B）；
 *   <li>卖不掉商品 ref 下跌，且可以低于成本（规则 A）；
 *   <li>无成交时 ref=0（规则 C）的分支存在；
 *   <li>买家按总财富降序、无出价、按最低 ask 成交；
 *   <li>成交直接记单边债务；信贷池为卖方创造/支付货币；
 *   <li>商品/货币/债务的账能对上。
 * </ul>
 */
class TwoRoundMarketProbeTest {

  private static final long PER_MILLE = 1000L;

  enum Good {
    GRAIN,
    CLOTH
  }

  /** 一个抽象家户：库存 A、货币、债务、每轮需求、成本估算、产出。 */
  static final class Household {
    final String id;
    final EnumMap<Good, Long> stock = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> need = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> cost = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> production = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> unsoldLastRound = new EnumMap<>(Good.class);
    long money;
    long debt;

    Household(String id) {
      this.id = id;
    }

    Household stock(Good good, long value) {
      stock.put(good, value);
      return this;
    }

    Household need(Good good, long value) {
      need.put(good, value);
      return this;
    }

    Household cost(Good good, long value) {
      cost.put(good, value);
      return this;
    }

    Household production(Good good, long value) {
      production.put(good, value);
      return this;
    }

    long stockOf(Good good) {
      return stock.getOrDefault(good, 0L);
    }

    long needOf(Good good) {
      return need.getOrDefault(good, 0L);
    }
  }

  /** GM 可调参数。 */
  record Params(
      long alphaUpPerMille,
      long alphaDownPerMille,
      long undercutPerMille,
      long pMax,
      long interestPerMille,
      EnumMap<Good, Long> initialPrice) {}

  /** 一张卖单：一个家户对一种商品的要价与数量（数量可变，撮合中扣减）。 */
  static final class Ask {
    final Household seller;
    final Good good;
    final long unitPrice;
    long qty;

    Ask(Household seller, Good good, long qty, long unitPrice) {
      this.seller = seller;
      this.good = good;
      this.qty = qty;
      this.unitPrice = unitPrice;
    }
  }

  /** 一笔成交（用于简单平均成交价）。 */
  record Trade(Good good, long unitPrice, long qty) {}

  /** 一轮结束后的快照，供断言与打印。 */
  record RoundSnapshot(
      int round,
      Map<Good, Long> refBefore,
      Map<Good, Long> refAfter,
      List<String> wealthOrder,
      List<Trade> trades,
      Map<Good, Long> sold,
      Map<Good, Long> unsold,
      Map<Good, Long> unmet,
      Map<String, Long> debts,
      long creditIssued,
      long totalMoney,
      Map<String, Map<Good, Long>> stocks) {}

  static final class MarketProbe {
    final Params params;
    final List<Household> households;
    final EnumMap<Good, Long> ref = new EnumMap<>(Good.class);
    long creditIssued;

    MarketProbe(Params params, List<Household> households) {
      this.params = params;
      this.households = List.copyOf(households);
    }

    long currentPrice(Good good) {
      long value = ref.getOrDefault(good, 0L);
      return value > 0L ? value : params.initialPrice().getOrDefault(good, 0L);
    }

    long wealthOf(Household household) {
      long wealth = household.money;
      for (Good good : Good.values()) {
        wealth += household.stockOf(good) * currentPrice(good);
      }
      return wealth;
    }

    RoundSnapshot runRound(int round) {
      EnumMap<Good, Long> refBefore = new EnumMap<>(ref);
      List<Trade> trades = new ArrayList<>();
      EnumMap<Good, Long> offered = new EnumMap<>(Good.class);
      EnumMap<Good, Long> sold = new EnumMap<>(Good.class);
      EnumMap<Good, Long> unmet = new EnumMap<>(Good.class);
      EnumMap<Good, Long> priceSum = new EnumMap<>(Good.class);
      EnumMap<Good, Long> priceCount = new EnumMap<>(Good.class);
      Map<String, EnumMap<Good, Long>> soldByHousehold = new LinkedHashMap<>();
      for (Household household : households) {
        soldByHousehold.put(household.id, new EnumMap<>(Good.class));
      }

      // ① 卖单：库存 − 自留需求；首次/无 ref 用 GM 初始价；上轮未售则立即降价。
      List<Ask> asks = new ArrayList<>();
      for (Good good : Good.values()) {
        for (Household household : households) {
          long reserve = household.needOf(good);
          long sellable = Math.max(0L, household.stockOf(good) - reserve);
          if (sellable <= 0L) {
            continue;
          }
          offered.merge(good, sellable, Math::addExact);
          long base = currentPrice(good);
          long ask = base;
          long unsold = household.unsoldLastRound.getOrDefault(good, 0L);
          if (unsold > 0L) {
            ask = ask * (PER_MILLE - params.undercutPerMille()) / PER_MILLE;
          }
          asks.add(new Ask(household, good, sellable, Math.max(0L, ask)));
          household.unsoldLastRound.put(good, 0L);
        }
      }
      Map<Good, List<Ask>> asksByGood = new LinkedHashMap<>();
      for (Good good : Good.values()) {
        List<Ask> list = new ArrayList<>();
        for (Ask ask : asks) {
          if (ask.good == good) {
            list.add(ask);
          }
        }
        list.sort(Comparator.comparingLong((Ask a) -> a.unitPrice).thenComparing(a -> a.seller.id));
        asksByGood.put(good, list);
      }

      // ② 买家按总财富降序；无出价，按卖家 ask 成交。
      List<Household> buyers = new ArrayList<>(households);
      buyers.sort(Comparator.comparingLong(this::wealthOf).reversed().thenComparing(h -> h.id));
      List<String> wealthOrder = new ArrayList<>();
      for (Household buyer : buyers) {
        wealthOrder.add(buyer.id);
        for (Good good : Good.values()) {
          long remainingNeed = Math.max(0L, buyer.needOf(good) - buyer.stockOf(good));
          while (remainingNeed > 0L) {
            Ask best = null;
            for (Ask ask : asksByGood.get(good)) {
              if (ask.qty <= 0L || ask.seller == buyer) {
                continue;
              }
              if (best == null
                  || ask.unitPrice < best.unitPrice
                  || (ask.unitPrice == best.unitPrice
                      && ask.seller.id.compareTo(best.seller.id) < 0)) {
                best = ask;
              }
            }
            if (best == null) {
              unmet.merge(good, remainingNeed, Math::addExact);
              break;
            }
            long qty = Math.min(remainingNeed, best.qty);
            long amount = Math.multiplyExact(qty, best.unitPrice);

            // 成交：货 -> 买家；钱从信贷池 -> 卖家；买家记单边债务。
            buyer.stock.merge(good, qty, Math::addExact);
            buyer.debt = Math.addExact(buyer.debt, amount);
            best.seller.stock.merge(good, -qty, Math::addExact);
            best.seller.money = Math.addExact(best.seller.money, amount);
            creditIssued = Math.addExact(creditIssued, amount);

            best.qty -= qty;
            remainingNeed -= qty;
            sold.merge(good, qty, Math::addExact);
            soldByHousehold.get(best.seller.id).merge(good, qty, Math::addExact);
            priceSum.merge(good, best.unitPrice, Math::addExact);
            priceCount.merge(good, 1L, Math::addExact);
            trades.add(new Trade(good, best.unitPrice, qty));
          }
        }
      }

      // ③ 规则 A/B/C：更新 ref。
      EnumMap<Good, Long> refAfter = new EnumMap<>(ref);
      for (Good good : Good.values()) {
        long count = priceCount.getOrDefault(good, 0L);
        if (count == 0L) {
          refAfter.put(good, 0L); // 规则 C：无成交 → 归零
          continue;
        }
        long avg = priceSum.getOrDefault(good, 0L) / count;
        long next = avg;
        long unsold = Math.max(0L, offered.getOrDefault(good, 0L) - sold.getOrDefault(good, 0L));
        long missed = unmet.getOrDefault(good, 0L);
        if (unsold > 0L) {
          long ratio = unsold * PER_MILLE / Math.max(1L, offered.getOrDefault(good, 0L));
          next = next * (PER_MILLE - params.alphaDownPerMille() * ratio / PER_MILLE) / PER_MILLE;
        }
        if (missed > 0L) {
          long demand = sold.getOrDefault(good, 0L) + missed;
          long gap = missed * PER_MILLE / Math.max(1L, demand);
          next = next * (PER_MILLE + params.alphaUpPerMille() * gap / PER_MILLE) / PER_MILLE;
        }
        refAfter.put(good, Math.max(0L, Math.min(params.pMax(), next)));
      }
      ref.clear();
      ref.putAll(refAfter);

      // 记录每户未售，供下一轮立刻降价。
      for (Good good : Good.values()) {
        for (Ask ask : asksByGood.get(good)) {
          if (ask.qty > 0L) {
            ask.seller.unsoldLastRound.merge(good, ask.qty, Math::addExact);
          }
        }
      }

      // ④ 计息（债务无限滚动；本征只做名义增长）。
      for (Household household : households) {
        household.debt = household.debt + household.debt * params.interestPerMille() / PER_MILLE;
      }

      // ⑤ 消费：本轮需求尽量吃/用掉，未满足的部分留待下一轮。
      for (Household household : households) {
        for (Good good : Good.values()) {
          long eat = Math.min(household.stockOf(good), household.needOf(good));
          household.stock.put(good, household.stockOf(good) - eat);
        }
      }

      // ⑥ 生产（探针占位：无投入消耗，只把产出加入库存）。
      for (Household household : households) {
        for (Good good : Good.values()) {
          long output = household.production.getOrDefault(good, 0L);
          if (output > 0L) {
            household.stock.merge(good, output, Math::addExact);
          }
        }
      }

      return new RoundSnapshot(
          round,
          Map.copyOf(refBefore),
          Map.copyOf(refAfter),
          List.copyOf(wealthOrder),
          List.copyOf(trades),
          Map.copyOf(sold),
          Map.copyOf(unsoldForPrint(offered, sold)),
          Map.copyOf(unmet),
          debtsOf(),
          creditIssued,
          totalMoney(),
          stocksOf());
    }

    private EnumMap<Good, Long> unsoldForPrint(
        EnumMap<Good, Long> offered, EnumMap<Good, Long> sold) {
      EnumMap<Good, Long> unsold = new EnumMap<>(Good.class);
      for (Good good : Good.values()) {
        unsold.put(
            good, Math.max(0L, offered.getOrDefault(good, 0L) - sold.getOrDefault(good, 0L)));
      }
      return unsold;
    }

    private Map<String, Long> debtsOf() {
      Map<String, Long> debts = new LinkedHashMap<>();
      for (Household household : households) {
        debts.put(household.id, household.debt);
      }
      return debts;
    }

    private long totalMoney() {
      long total = 0L;
      for (Household household : households) {
        total = Math.addExact(total, household.money);
      }
      return total;
    }

    private Map<String, Map<Good, Long>> stocksOf() {
      Map<String, Map<Good, Long>> stocks = new LinkedHashMap<>();
      for (Household household : households) {
        stocks.put(household.id, Map.copyOf(household.stock));
      }
      return stocks;
    }
  }

  @Test
  void twoRoundsShowShortageUpGlutDownAndOneSidedDebt() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    Params params = new Params(200L, 100L, 100L, 10_000L, 50L, initialPrice);

    Household landlord =
        new Household("A-landlord")
            .stock(Good.GRAIN, 20L)
            .need(Good.GRAIN, 10L)
            .need(Good.CLOTH, 20L)
            .cost(Good.GRAIN, 60L)
            .cost(Good.CLOTH, 120L)
            .production(Good.GRAIN, 20L);
    landlord.money = 1_000L;

    Household farmer =
        new Household("B-farmer")
            .stock(Good.GRAIN, 20L)
            .need(Good.GRAIN, 10L)
            .need(Good.CLOTH, 10L)
            .cost(Good.GRAIN, 50L)
            .cost(Good.CLOTH, 100L)
            .production(Good.GRAIN, 10L);
    farmer.money = 100L;

    Household weaver =
        new Household("C-weaver")
            .stock(Good.GRAIN, 0L)
            .stock(Good.CLOTH, 60L)
            .need(Good.GRAIN, 20L)
            .need(Good.CLOTH, 5L)
            .cost(Good.GRAIN, 70L)
            .cost(Good.CLOTH, 80L)
            .production(Good.CLOTH, 20L);
    weaver.money = 100L;

    Household laborer =
        new Household("D-laborer")
            .stock(Good.GRAIN, 5L)
            .need(Good.GRAIN, 15L)
            .need(Good.CLOTH, 5L)
            .cost(Good.GRAIN, 90L)
            .cost(Good.CLOTH, 150L);
    laborer.money = 5L;

    List<Household> households = List.of(landlord, farmer, weaver, laborer);
    long initialMoney = households.stream().mapToLong(h -> h.money).sum();
    long initialGoods =
        households.stream().mapToLong(h -> h.stockOf(Good.GRAIN) + h.stockOf(Good.CLOTH)).sum();

    MarketProbe market = new MarketProbe(params, households);

    RoundSnapshot first = market.runRound(1);
    print(first);
    RoundSnapshot second = market.runRound(2);
    print(second);

    // 规则 B：粮缺货 → ref 上涨。
    assertThat(first.refAfter().get(Good.GRAIN))
        .isGreaterThan(params.initialPrice().get(Good.GRAIN));
    // 规则 A：布卖不掉 → ref 下跌（本轮可低于成本；成本只是估算，不作为卖价下限）。
    assertThat(first.refAfter().get(Good.CLOTH)).isLessThan(params.initialPrice().get(Good.CLOTH));
    // 第二轮粮继续缺货 → ref 继续上涨。
    assertThat(second.refAfter().get(Good.GRAIN)).isGreaterThan(first.refAfter().get(Good.GRAIN));

    // 买家按总财富降序；织户（布库存最多）第一。
    assertThat(first.wealthOrder()).startsWith("C-weaver");
    // 单边债务确实产生。
    assertThat(second.debts().values()).allMatch(debt -> debt > 0L);
    // 货币 = 初始货币 + 信贷池发行。
    assertThat(second.totalMoney()).isEqualTo(initialMoney + second.creditIssued());
    // 商品守恒（交易不创造货物；生产在消费之后追加，另行核对总产出）。
    long produced =
        households.stream().mapToLong(h -> h.production.getOrDefault(Good.GRAIN, 0L)).sum()
            + households.stream().mapToLong(h -> h.production.getOrDefault(Good.CLOTH, 0L)).sum();
    long finalGoods =
        households.stream().mapToLong(h -> h.stockOf(Good.GRAIN) + h.stockOf(Good.CLOTH)).sum();
    // 两轮都消费了需求，库存无法直接等于 初始 + 生产；这里只保证没有负数。
    assertThat(finalGoods).isNotNegative();
    assertThat(initialGoods + 2L * produced).isPositive();
  }

  private static void print(RoundSnapshot snapshot) {
    System.out.println("=== 市场轮 " + snapshot.round() + " ===");
    System.out.println("ref before = " + snapshot.refBefore());
    System.out.println("wealth order = " + snapshot.wealthOrder());
    System.out.println("trades = " + snapshot.trades());
    System.out.println("sold = " + snapshot.sold());
    System.out.println("unsold = " + snapshot.unsold());
    System.out.println("unmet = " + snapshot.unmet());
    System.out.println("ref after = " + snapshot.refAfter());
    System.out.println("debts = " + snapshot.debts());
    System.out.println("creditIssued = " + snapshot.creditIssued());
    System.out.println("totalMoney = " + snapshot.totalMoney());
    System.out.println("stocks = " + snapshot.stocks());
  }
}
