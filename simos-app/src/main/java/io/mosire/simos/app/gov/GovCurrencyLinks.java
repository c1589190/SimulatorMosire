package io.mosire.simos.app.gov;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>"发行政府 ↔ GOV 单位"连线与市场区的只读查询面</b>（阶段 2-B2，2026-10-08；约束设计书 §4.3）。
 *
 * <p>★★ <b>它回答四个问题</b>（都是 B2 的交付面，此前全仓没有稳定的查询点）：
 *
 * <ol>
 *   <li><b>某个 GOV 单位发行哪些币</b>（{@link #currenciesIssuedBy}）：{@code gov-unit-<govUnitId>} 查政府表 ⇒
 *       {@code issuable}；
 *   <li><b>某种币由哪些政府发行</b>（{@link #possibleIssuersOf}）：扫政府表的 {@code issuable}（<b>可多值</b>、规范序； ★
 *       2026-10-09 C 批起这就是"谁管这种钱"的唯一答案，区里不再记发行政府）+ 它的 GOV 单位投影（{@link #govUnitIdsIssuing}）；
 *   <li><b>某个 hex 属于哪个区</b>（{@link #zoneOfHex}）；
 *   <li><b>某个 hex 上的官方汇率</b>（{@link #officialRateFor}）：区级覆盖优先、回落本区法定币发行者的 GOV 级报价。
 * </ol>
 *
 * <p>★ <b>为什么住 app</b>：区表与政府表住在 economy，而"GOV 单位"是 string 级引用（app 同时看得见 unit/economy 的接线）。
 * 本类是**组合根上的只读门面**，实现全部委托 {@code MarketZoneBook}（economy 侧的唯一拼写点）—— 不在 app 重算一遍区归属。
 *
 * <p>★ <b>不写状态</b>：全是纯函数；写入口只有 {@code economy.DefineMarketZone}/{@code ReassignZoneHexes}/{@code
 * MergeMarketZones}/{@code SetOfficialRate}（GM 桶，见各 handler）。★ 授权面（谁能改哪个区）留阶段 3：本批**不**给决策人桶
 * 开区管理（权限不得因为新增查询/命令而放大）。
 */
public final class GovCurrencyLinks {

  private GovCurrencyLinks() {}

  /** economy 切片（缺席/类型不符 ⇒ 抛；与 {@code MarketTopologyBook} 的既有口径同源）。 */
  private static EconomyData economyOf(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("状态里没有 economy 切片（装配故障）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  /**
   * ★★ <b>某个 GOV 单位发行哪些币</b>（§4.3 查询面）：政府未登记 ⇒ <b>空集</b>（"这个单位不是发行人"是合法答案，不是故障）。
   *
   * @throws IllegalArgumentException govUnitId 为空白 / 含分段符 / 已带 {@code gov-unit-} 前缀（{@code
   *     GovernmentIds} 的口径）
   */
  public static Set<CurrencyId> currenciesIssuedBy(SimulationState state, String govUnitId) {
    return MarketZoneBook.currenciesIssuedBy(economyOf(state), govUnitId);
  }

  /**
   * ★★ <b>某种币由哪些政府发行</b>（§4.3 查询面；★ 2026-10-09 C 批起这是"谁管这种钱"的唯一答案）：扫政府表的 {@code issuable} ⇒
   * <b>全部</b>声称能发行该币的政府身份（{@code GovernmentId}，<b>可多值</b>、规范序）。★ 不替调用方挑一个（挑一个 = 把"谁发的"藏进任意 choice）。
   */
  public static List<GovernmentId> possibleIssuersOf(SimulationState state, CurrencyId currency) {
    return MarketZoneBook.possibleIssuersOf(economyOf(state), currency);
  }

  /**
   * ★★ <b>某种币由哪些 GOV 单位发行</b>（§4.3 查询面）：只列**GOV 单位**政府（世界级主体如 {@code world-silver} 不在其中 ——
   * 它没有单位可指，那是"不猜"而不是"漏报"）。★ 结果按单位 id 升序（可复现）。
   */
  public static Set<String> govUnitIdsIssuing(SimulationState state, CurrencyId currency) {
    return MarketZoneBook.govUnitIdsIssuing(economyOf(state), currency);
  }

  /** 某个 hex 所属的持久市场区（不属于任何区 ⇒ 空）。 */
  public static Optional<MarketZone> zoneOfHex(SimulationState state, HexCoord hex) {
    return MarketZoneBook.zoneOfHex(economyOf(state), hex);
  }

  /** 某个 hex 上某币对的官方汇率（区级覆盖优先 ⇒ 本区法定币发行者的 GOV 级报价 ⇒ 空；不回落成"随便哪个 GOV 的价"）。 */
  public static Optional<OfficialRate> officialRateFor(
      SimulationState state, HexCoord hex, CurrencyId base, CurrencyId quote) {
    return MarketZoneBook.officialRateFor(economyOf(state), hex, base, quote);
  }

  /**
   * 逐区一行人类可读摘要（保序；供日志/工具/探针直读）：{@code <zoneId>@<anchor>[<n>格]=<法定币>}。 ★ 只输出稳定 id 与数量（§一.9 的日志纪律）。★
   * 2026-10-09 C 批：不再输出"发行 GOV 单位"那一栏（区里已无发行者；谁管这种钱见 {@link #possibleIssuersOf}）。
   */
  public static List<String> describe(SimulationState state) {
    EconomyData economy = economyOf(state);
    List<String> lines = new java.util.ArrayList<>();
    for (MarketZone zone : MarketZoneBook.zones(economy)) {
      lines.add(
          zone.zoneId().value()
              + "@"
              + zone.anchor()
              + "["
              + zone.hexCount()
              + "格]="
              + zone.legalTender().value()
              + (zone.officialRates().isEmpty() ? "" : " rates=" + zone.officialRates().size()));
    }
    return List.copyOf(lines);
  }
}
