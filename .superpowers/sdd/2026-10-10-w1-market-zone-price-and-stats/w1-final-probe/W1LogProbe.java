import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.MarketRoundStats;
import java.util.LinkedHashMap;
import java.util.Map;

/** (A) 的口径对照：同一 tick 的「旧写法打印什么 / 新写法打印什么」，数字取验收报告 §5.1/§7.2 的 day=360 真值。 */
public final class W1LogProbe {
  public static void main(String[] args) {
    Map<CommodityId, CommodityRound> round = new LinkedHashMap<>();
    for (String c : new String[] {"grain", "cloth", "tool", "salt", "iron"}) {
      round.put(new CommodityId(c), new CommodityRound(1_000L, 900L, 800L, 4L, 100L));
    }
    // 旧写法 = 本轮入参：MarketSettlement.roundStatsOf 造的单轮形态（两段累计恒空）
    MarketRoundStats incoming = new MarketRoundStats(360L, round, 521L, Map.of(), 0L, Map.of(), 0L);
    // 新写法 = 落盘的那份：EconomySettlement 里 current.withRound(day, …, cycleClosed=true) 的返回值
    //   关账轮轮转 ⇒ 上周期 = 本周期累计(5080) + 本轮(521) = 5601（= 报告里落盘原文的 previousCycleTurnoverMilli）
    MarketRoundStats before = MarketRoundStats.EMPTY.withRound(359L, round, 5_080L, false);
    MarketRoundStats stored = before.withRound(360L, round, 521L, true);
    System.out.println("[旧] MARKET_ROUND_STATS_DETAIL stats=" + incoming.describe());
    System.out.println("[新] MARKET_ROUND_STATS_DETAIL stats=" + stored.describe());
    System.out.println(
        "[核对] 新打印的上周期成交额 == 落盘 previousCycleTurnoverMilli: 5601 == "
            + stored.previousCycleTurnoverMilli()
            + " ; 上周期商品数 == "
            + stored.previousCycle().size());
  }
}
