package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.Government;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
   * @param governmentId 窗口属主（= 定这条官方汇率的 GOV）
   * @param treasury 国库 actor（两侧都要能在 {@code householdOfActor} 里解析到家户，才可能真的动账）
   * @param rate 官方汇率（报价的唯一来源）
   * @param reserveCapBaseMilli 储备上限 {@code R_max}（base 最小单位；≥ 0）
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
   * ★★ <b>从世界状态装配本轮窗口</b>（生产路径的唯一入口）。
   *
   * <pre>
   * 逐 GOV：对它的每一条官方汇率 (base, quote)
   *   R_max(base) = 该 GOV 对该币种的<b>累计发行量</b> × DEFAULT_RESERVE_CAP_PER_MILLE/1000
   * </pre>
   *
   * <p>★ <b>为什么上限挂在"累计发行量"上</b>：政府的外汇储备上限若与它发出去的钱毫无关系，就只是一个孤立的数字；挂在发行量上 ⇒
   * "我不打算把超过我发行量一半的外币囤回库"是可解释的政策线，且换世界/换精度都不用改一个字（见 {@link
   * GovFxWindow#DEFAULT_RESERVE_CAP_PER_MILLE}）。
   *
   * @param governments 政府表（{@code EconomyData.governments()}）
   * @param moneyIssuances 发行审计表（{@code EconomyData.moneyIssuances()}；累计发行量的唯一来源）
   */
  public static FxRoundInput of(
      Map<GovernmentId, Government> governments, Map<?, MoneyIssuanceRecord> moneyIssuances) {
    if (governments == null || governments.isEmpty()) {
      return none();
    }
    Map<String, Long> issuanceByGovCurrency = new LinkedHashMap<>();
    if (moneyIssuances != null) {
      List<MoneyIssuanceRecord> records = new ArrayList<>();
      for (MoneyIssuanceRecord record : moneyIssuances.values()) {
        if (record != null) {
          records.add(record);
        }
      }
      // 确定性顺序（不依赖 map 迭代序）：按 (gov, 币种, day, id) 升序求和。
      records.sort(
          Comparator.comparing((MoneyIssuanceRecord r) -> r.governmentId().value())
              .thenComparing(r -> r.currency().value())
              .thenComparingLong(MoneyIssuanceRecord::day)
              .thenComparing(r -> r.id().value()));
      for (MoneyIssuanceRecord record : records) {
        if (!record.kind().issuance()) {
          continue; // 回笼不抬上限（它把钱收回来，不是放出去）
        }
        issuanceByGovCurrency.merge(
            issuanceKey(record.governmentId(), record.currency()), record.amount(), Long::sum);
      }
    }
    List<Window> windows = new ArrayList<>();
    List<GovernmentId> govIds = new ArrayList<>(governments.keySet());
    govIds.sort(Comparator.comparing(GovernmentId::value));
    for (GovernmentId governmentId : govIds) {
      Government government = governments.get(governmentId);
      if (government == null || government.officialRates().isEmpty()) {
        continue;
      }
      List<OfficialRate> rates = new ArrayList<>(government.officialRates().values());
      rates.sort(
          Comparator.comparing((OfficialRate r) -> r.base().value())
              .thenComparing(r -> r.quote().value()));
      for (OfficialRate rate : rates) {
        long issuance =
            issuanceByGovCurrency.getOrDefault(issuanceKey(governmentId, rate.base()), 0L);
        long cap =
            GovFxWindow.mulDivFloor(issuance, GovFxWindow.DEFAULT_RESERVE_CAP_PER_MILLE, 1000L);
        windows.add(new Window(governmentId, government.treasury(), rate, cap));
      }
    }
    return new FxRoundInput(windows);
  }

  private static String issuanceKey(GovernmentId governmentId, CurrencyId currency) {
    return governmentId.value() + "|" + currency.value();
  }
}
