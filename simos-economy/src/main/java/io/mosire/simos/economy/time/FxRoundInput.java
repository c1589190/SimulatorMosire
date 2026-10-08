package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketZoneBook;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>一轮市场的外汇入参</b>（阶段 2-A2a）：把"哪些 GOV 在这个世界开了外汇窗口、它们的储备上限是多少"从世界状态 带进市场轮。
 *
 * <p>★★ <b>它是逐轮瞬态</b>（不进 {@code EconomyData}、不进变更集、不落盘）：官方汇率本身是<b>状态</b> （{@code
 * Government.officialRates}，随 ChangeSet/Codec），而"这一轮窗口怎么摆"是每轮由状态现算的视图 —— 与 {@code
 * MarketRegulation.defaultsFor(markets)} 同一形制。
 *
 * <p>★★ <b>激活条件（fail-closed）：某个币对<b>没有</b>官方汇率 ⇒ 该币对没有窗口 ⇒ 没有 FX 市场</b>。这不是省事，而是
 * "政策价从哪来"这件事唯一说得通的形态：没有政府报价就没有参照，家户的限价也就无从锚定。副作用（如实记、且是刻意的）： <b>既有的单币世界逐值不变</b>——A1
 * 的创世没有定任何官方汇率，于是 A2a 落地后老世界的行为一个数都不动，直到有 DM/GM 真的定了一条汇率。
 *
 * <p>★★ <b>B4（2026-10-08）扩展：报价来源 = 市场区</b>（{@link #of(Map, Map, Map)}）。B2 把官方汇率扩到了 区级覆盖（{@code
 * MarketZone.officialRates}），但窗口组装一直只读 GOV 级 ⇒ 区级汇率<b>不产生窗口</b>（three-powers 的 实测缺陷）。B4 起：逐 GOV
 * 取<b>生效报价</b>（区级优先、按币对回落本 GOV 的 GOV 级报价，唯一口径在 {@link
 * MarketZoneBook#effectiveRatesOf}），窗口的属主/国库/储备上限口径一字不动。★ <b>本批的边界</b>：窗口仍是<b>世界级</b>的（谁的窗口跟谁成交
 * 不做区隔离）—— 把撮合域按区收窄是待裁定的开放点，不在本批。
 *
 * @param windows 本轮的窗口（保序；空 = 本轮没有外汇市场）
 */
public record FxRoundInput(List<Window> windows) {

  public FxRoundInput {
    windows = windows == null ? List.of() : List.copyOf(windows);
  }

  /** 本轮没有外汇市场（旧路径/未定政策 ⇒ 逐值退回 A2a 之前）。 */
  public static FxRoundInput none() {
    return new FxRoundInput(List.of());
  }

  /** 有没有窗口（空 ⇒ {@code MarketSettlement} 的 FX 段整段跳过）。 */
  public boolean isActive() {
    return !windows.isEmpty();
  }

  /**
   * <b>一个窗口</b>：属主 GOV + 国库 actor + 官方汇率 + 该币种的储备上限 {@code R_max}。
   *
   * @param governmentId 窗口属主（= 这条报价所属的 GOV：GOV 级报价就是它自己；★ B4 起<b>区级覆盖</b>的属主 = 该区发行 GOV）
   * @param treasury 国库 actor（两侧都要能在 {@code householdOfActor} 里解析到家户，才可能真的动账）
   * @param rate 官方汇率（报价的唯一来源）
   * @param reserveCapBaseMilli 储备上限 {@code R_max} 的读数（base 最小单位；≥ 0）。★ 2026-10-09 起生产装配恒传 {@link
   *     GovFxWindow#UNBOUNDED_RESERVE_CAP_BASE_MILLI}（该政策线已取消，<b>不封顶买入</b>）；旧构造点传有限值 ⇒
   *     只作读数留痕，不再封顶（退化语义见该常量的注）
   */
  public record Window(
      GovernmentId governmentId, ActorRef treasury, OfficialRate rate, long reserveCapBaseMilli) {

    public Window {
      Objects.requireNonNull(governmentId, "Window.governmentId 不得为 null");
      Objects.requireNonNull(treasury, "Window.treasury 不得为 null");
      Objects.requireNonNull(rate, "Window.rate 不得为 null");
      if (reserveCapBaseMilli < 0L) {
        throw new IllegalArgumentException(
            "Window.reserveCapBaseMilli 不得为负: " + reserveCapBaseMilli);
      }
    }
  }

  /**
   * ★★ <b>从世界状态装配本轮窗口（A2a 老路径：只读 GOV 级报价）</b>。
   *
   * <pre>
   * 逐 GOV：对它的每一条官方汇率 (base, quote)
   *   R_max(base) = UNBOUNDED（★ 2026-10-09：该政策线已取消，买入侧不再有储备上限）
   * </pre>
   *
   * <p>★★ <b>为什么这里不再有算式</b>：旧口径是"累计发行量 × 500‰"，实测（three-powers 0→360，见 {@code
   * docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md} 的 §11）该上限
   * （50,000）小于国库实有（100,000 copper）⇒ 窗口自第一轮起买入侧就被封顶，家户侧的外币只有单向流出。 用户 2026-10-08 裁定取消这条政策线 ⇒ 本方法只填
   * {@link GovFxWindow#UNBOUNDED_RESERVE_CAP_BASE_MILLI}； 发行量索引与算式一并退役（不留"算了却被忽略"的死算式）。
   *
   * <p>★ 本签名<b>逐字保留</b>（旧调用方/旧夹具仍在用）：区表非空的装配走 {@link #of(Map, Map, Map)}， 后者在"区表为空"时就退回本方法。
   *
   * @param governments 政府表（{@code EconomyData.governments()}）
   * @param moneyIssuances 发行审计表（{@code EconomyData.moneyIssuances()}）。★ <b>保留形参</b>：上限取消后它
   *     <b>不再参与</b>窗口容量（发行量不再有任何读者；签名冻结以免旧载荷/旧夹具失配）
   */
  public static FxRoundInput of(
      Map<GovernmentId, Government> governments, Map<?, MoneyIssuanceRecord> moneyIssuances) {
    if (governments == null || governments.isEmpty()) {
      return none();
    }
    List<Window> windows = new ArrayList<>();
    for (GovernmentId governmentId : sortedGovernmentIds(governments)) {
      Government government = governments.get(governmentId);
      if (government == null || government.officialRates().isEmpty()) {
        continue;
      }
      addWindows(windows, governmentId, government, government.officialRates());
    }
    return new FxRoundInput(windows);
  }

  /**
   * ★★ <b>B4（2026-10-08）：带市场区信息的装配 —— 生产路径自本批起走它</b>（区级汇率终于能产生窗口）。
   *
   * <pre>
   * 区表为空（旧世界 / 旧调用方） ⇒ 逐字走 {@link #of(Map, Map)}（只读 GOV 级的老路径）
   * 区表非空                     ⇒ 逐 GOV × 每一条<b>生效报价</b>：
   *                                生效报价 = 区级覆盖优先、按币对回落本 GOV 的 GOV 级报价
   *                                           （唯一口径：{@link MarketZoneBook#effectiveRatesOf}）
   *                                两者都没有 ⇒ 这个 GOV 没有窗口（fail-closed：没报价就没有政策价可锚）
   * </pre>
   *
   * <p>★★ <b>为什么逐 GOV 而不是逐区</b>：窗口的<b>身份</b>是"哪个政府开的"——属主 GOV / 国库 actor / 储备上限三项都挂在 GOV 上（{@link
   * Window}），而区级覆盖说的是"这个区的报价"。于是把区级报价<b>投到该区发行 GOV 的窗口</b>上：一个 GOV 一个币对<b>恰一个</b>窗口。逐区各发一条会让同一个 GOV
   * 的同一个币对重复投放容量（撮合簿里就是两张同价同量的窗口单 = 静默的数值放大），所以不那样做。
   *
   * <p>★ <b>一个 GOV 下辖多个区、且同币对报价冲突时</b>：取<b>规范序第一个区</b>（与 {@link MarketZoneBook#zoneCovering}
   * 同一口径）——"哪个区的人跟哪个窗口成交"是<b>待裁定的开放点</b>（把撮合域按区收窄不在本批）， 装订点（{@code
   * EconomySettlement#logFxWindows}）会把被覆盖的那条报价按 DEBUG 具名记下来，不由本方法静默吞掉。
   *
   * <p>★ <b>本批的边界（如实记）</b>：窗口仍是<b>世界级</b>的（`FxSettlement` 的币对簿不对区隔离），本批只做"让区级汇率产生窗口"这一步。
   *
   * @param governments 政府表（{@code EconomyData.governments()}）
   * @param moneyIssuances 发行审计表（{@code EconomyData.moneyIssuances()}；累计发行量的唯一来源）
   * @param marketZones 市场区表（{@code EconomyData.marketZones()}；null / 空 ⇒ 逐字走旧路径）
   */
  public static FxRoundInput of(
      Map<GovernmentId, Government> governments,
      Map<?, MoneyIssuanceRecord> moneyIssuances,
      Map<MarketZoneId, MarketZone> marketZones) {
    if (governments == null || governments.isEmpty()) {
      return none();
    }
    if (marketZones == null || marketZones.isEmpty()) {
      // ★★ 旧世界（区表为空）⇒ 逐字走 GOV 级老路径：A2 的 F2/F3/F4 与 small-world 一个数都不动。
      return of(governments, moneyIssuances);
    }
    List<Window> windows = new ArrayList<>();
    for (GovernmentId governmentId : sortedGovernmentIds(governments)) {
      Government government = governments.get(governmentId);
      if (government == null) {
        continue;
      }
      Map<String, OfficialRate> effectiveRates =
          MarketZoneBook.effectiveRatesOf(government, marketZones);
      if (effectiveRates.isEmpty()) {
        continue;
      }
      addWindows(windows, governmentId, government, effectiveRates);
    }
    return new FxRoundInput(windows);
  }

  /** 报价的确定序：(base, quote) 升序 —— 窗口序不依赖任何 map 的迭代序（与 A2a 逐值相同）。 */
  private static final Comparator<OfficialRate> RATE_ORDER =
      Comparator.comparing((OfficialRate r) -> r.base().value())
          .thenComparing(r -> r.quote().value());

  /** GOV id 的确定序（升序）：窗口的属主次序不依赖政府表的迭代序。 */
  private static List<GovernmentId> sortedGovernmentIds(Map<GovernmentId, Government> governments) {
    List<GovernmentId> govIds = new ArrayList<>(governments.keySet());
    govIds.sort(Comparator.comparing(GovernmentId::value));
    return govIds;
  }

  /** 逐条报价追加窗口（币对升序；{@code R_max} 恒为 {@link GovFxWindow#UNBOUNDED_RESERVE_CAP_BASE_MILLI}）。 */
  private static void addWindows(
      List<Window> windows,
      GovernmentId governmentId,
      Government government,
      Map<String, OfficialRate> rates) {
    List<OfficialRate> sorted = new ArrayList<>(rates.values());
    sorted.sort(RATE_ORDER);
    for (OfficialRate rate : sorted) {
      // ★ 2026-10-09：R_max 恒为"无上限"（该政策线已取消）—— 不再有发行量索引、不再有千分比算式。
      windows.add(
          new Window(
              governmentId,
              government.treasury(),
              rate,
              GovFxWindow.UNBOUNDED_RESERVE_CAP_BASE_MILLI));
    }
  }
}
