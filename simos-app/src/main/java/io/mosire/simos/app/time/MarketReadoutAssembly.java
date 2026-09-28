package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.time.MarketReadout;
import io.mosire.simos.economy.time.MarketReadoutAccounts;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>{@link MarketReadout} 的组合根装配（M2.7）</b>：把"经济切片 + actor 账 + 城市拓扑 + 进程内报告"现算成一份逐区读数， 交给 {@code
 * ApiViews.economyHex}（GUI 与 MCP 共用同一份视图，AGENTS §8.3）。
 *
 * <p>★★ <b>为什么住 {@code simos-app}</b>（铁律 3/4）：经济侧不认识 actor 切片、也不认识 social/map 城市；只有组合根同时看得见
 * 三者。economy 侧只收纯 map 与只读拓扑。
 *
 * <p>★★ <b>如实标注</b>：{@code readout.match} 来自 {@link MarketReportFeed}（进程内、重启即失，只在同一 tick 内可信）；
 * 账户缺席不抛（手搭状态是合法形态），由 {@link MarketReadout#unavailable()} 计数点名。
 */
public final class MarketReadoutAssembly {

  private MarketReadoutAssembly() {}

  /**
   * ★★ <b>一个逐格读口的市场上下文</b>。
   *
   * @param readout 焦点区的读数；经济未激活 / 该格不在任何市场区 ⇒ {@link Optional#empty()}
   * @param report 进程内最近一轮市场报告（可为空；见 {@link MarketReportFeed} 的三条边界）
   * @param tick 当前 state 的世界日（读到多少就报多少，不猜）
   * @param readoutUnavailable {@code readout} 为空时的具名原因；非空时为空串
   */
  public record MarketReadoutContext(
      Optional<MarketReadout> readout,
      Optional<MarketReport> report,
      long tick,
      String readoutUnavailable) {

    public MarketReadoutContext {
      Objects.requireNonNull(readout, "readout");
      Objects.requireNonNull(report, "report");
      Objects.requireNonNull(readoutUnavailable, "readoutUnavailable");
    }
  }

  /** 从状态现算焦点区读数（GUI / MCP 共用的唯一入口）。 */
  public static MarketReadoutContext contextFor(SimulationState state, HexCoord focus) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(focus, "focus");
    long tick = state.meta().timestamp().tick();
    EconomyData economy = economyOrNull(state);
    if (economy == null || economy.meta().isEmpty()) {
      return new MarketReadoutContext(
          Optional.empty(), Optional.empty(), tick, "经济切片未激活（meta 为空）⇒ 没有市场读数");
    }
    String mapId = economy.meta().orElseThrow().mapId();
    Optional<MarketReport> report = MarketReportFeed.last(mapId, tick);
    ActorData actor = actorOrEmpty(state);
    MarketTopology topology = MarketTopologyBook.from(state);
    MarketReadoutAccounts accounts = accountsOf(economy, actor);
    Optional<MarketReadout> readout =
        MarketReadout.deriveFor(focus, economy, topology, tick, accounts, report);
    if (readout.isEmpty()) {
      return new MarketReadoutContext(
          Optional.empty(), report, tick, "该格不在任何市场区（没有市场表条目 = 这一格没有市场，合法状态）⇒ 没有区级市场读数");
    }
    return new MarketReadoutContext(readout, report, tick, "");
  }

  /** 把 actor 侧的余额/冻结表载入成逐家户与逐经营者的纯 map（缺席不抛；见类注）。 */
  static MarketReadoutAccounts accountsOf(EconomyData economy, ActorData actor) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(actor, "actor");
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : economy.classes().entrySet()) {
      HouseholdId key = entry.getKey();
      GoodsAccount account =
          actor.accounts().get(OwnershipBooks.accountKeyOf(key, entry.getValue().view().hex()));
      if (account == null) {
        continue; // 读口覆盖不足：由 MarketReadout 的 unavailable.householdAccounts 计数点名
      }
      householdGoods.put(key, account.balances());
      householdMoney.put(key, account.money());
      householdFrozenGoods.put(key, account.frozenBalances());
      householdFrozenMoney.put(key, account.frozenMoney());
    }
    Map<ActorRef, Map<CommodityId, Long>> operatorGoods = new LinkedHashMap<>();
    Map<ActorRef, Map<CurrencyId, Long>> operatorMoney = new LinkedHashMap<>();
    Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods = new LinkedHashMap<>();
    Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, HexCoord> entry :
        OwnershipBooks.operatorLocations(economy).entrySet()) {
      GoodsAccount account =
          actor.accounts().get(new GoodsAccountKey(entry.getKey(), entry.getValue()));
      if (account == null) {
        continue; // 经营者账缺席是合法状态（H4/H5 的既有口径）
      }
      operatorGoods.put(entry.getKey(), account.balances());
      operatorMoney.put(entry.getKey(), account.money());
      operatorFrozenGoods.put(entry.getKey(), account.frozenBalances());
      operatorFrozenMoney.put(entry.getKey(), account.frozenMoney());
    }
    return new MarketReadoutAccounts(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        operatorGoods,
        operatorMoney,
        operatorFrozenGoods,
        operatorFrozenMoney);
  }

  private static EconomyData economyOrNull(SimulationState state) {
    Snapshot snapshot = state.module("economy").orElse(null);
    if (snapshot instanceof EconomySnapshot economySnapshot) {
      return economySnapshot.data();
    }
    return null;
  }

  private static ActorData actorOrEmpty(SimulationState state) {
    Snapshot snapshot = state.module("actor").orElse(null);
    if (snapshot instanceof ActorSnapshot actorSnapshot) {
      return actorSnapshot.data();
    }
    return ActorData.empty();
  }
}
