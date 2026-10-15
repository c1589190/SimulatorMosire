package io.mosire.simos.app.sim;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.time.MarketReportFeed;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ★★ <b>批 M（2026-10-15）从测试类里切出来的"世界读数层"</b>（经济循环体检的读口 + 摘要写盘）—— 由两处共用：① 门禁里的 smoke {@code
 * EconomyCycleHealthTest}（1 段 30 天）； ② 独立跑法 {@link EconomyCycleHealthSim}（240 天 / 8 段 / 96 轮指纹，跑在
 * {@code test-world/} 那一侧）。
 *
 * <p>★ 这里**只有读数与写盘，没有判据**：断言留在两边的调用点上（smoke 的少、sim 的全）。 切出来的唯一改动是可见性（{@code private} → {@code
 * public}）与把摘要路径从常量改成形参 —— 读数口径逐字未动。
 */
public final class EconomyCycleReading {

  private EconomyCycleReading() {}

  /** 主分支（与 {@code GovZ6WorldFixture#MAIN} 同字面）。 */
  public static final BranchId MAIN = GovZ6WorldFixture.MAIN;

  /**
   * 每段推进的天数：★ 30 天一段（经济设计书 §12 的 10 倍效率口径），且 30 是关账日（GOV 官署 {@code OFFICE_CYCLE_DAYS = 30}、也是市场例行轮
   * {@code day % 5 == 0}）。
   */
  public static final long SEGMENT_DAYS = 30L;

  public static final CommodityId GRAIN =
      new CommodityId(io.mosire.simos.app.world.EconomySeeder.COMMODITY_GRAIN);
  public static final CommodityId CLOTH =
      new CommodityId(io.mosire.simos.app.world.EconomySeeder.COMMODITY_CLOTH);

  // ── 推进 ────────────────────────────────────────────────────────────────────────────

  /** 真推进：把 {@code from → to}（含）一次交给真 {@code AdvanceTime}（★ 多天一段，内部仍逐日结算）。 */
  public static void advance(Shell shell, long from, long to, int segment) {
    long head = head(shell);
    shell.advanceAndDrain(
        new AdvanceTime(
            "cmd-econ-cycle-" + segment,
            "corr-econ-cycle-" + segment,
            "econ-cycle",
            MAIN,
            new RevisionId(head),
            new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
  }

  public static long head(Shell shell) {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  /** 当前 head 的完整状态（真 replay，不是内存引用）。 */
  public static SimulationState replay(Shell shell) {
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(head(shell))));
  }

  public static long tickOf(SimulationState state) {
    return state.meta().timestamp().tick();
  }

  // ── 读数 ────────────────────────────────────────────────────────────────────────────

  /** 一个关账日的体检读数（全部来自真状态快照 + 进程内市场报告）。 */
  public record Reading(
      long tick,
      long revision,
      long population,
      long grainMilli,
      long clothMilli,
      long debtPrincipalMilli,
      Map<CurrencyId, Long> moneyByCurrency,
      Optional<MarketReport> market) {

    public String moneyTotalsText() {
      StringBuilder text = new StringBuilder();
      for (Map.Entry<CurrencyId, Long> entry : moneyByCurrency.entrySet()) {
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(entry.getKey().value())
            .append('=')
            .append(String.format("%,d", entry.getValue()));
      }
      return text.toString();
    }

    /** 未成交归因 top3（按笔数降序、笔数同取数量降序、再取原因字典序 —— 与输入序无关）。 */
    public String unfilledTop3Text() {
      if (market.isEmpty()) {
        return "读不到市场报告";
      }
      Map<MarketUnfilledReason, long[]> byReason = new LinkedHashMap<>();
      for (MarketReport.Unfilled unfilled : market.orElseThrow().unfilled()) {
        long[] cell = byReason.computeIfAbsent(unfilled.reason(), reason -> new long[2]);
        cell[0]++;
        cell[1] += unfilled.quantity();
      }
      if (byReason.isEmpty()) {
        return "无未成交";
      }
      List<Map.Entry<MarketUnfilledReason, long[]>> ranked = new ArrayList<>(byReason.entrySet());
      ranked.sort(
          Comparator.<Map.Entry<MarketUnfilledReason, long[]>>comparingLong(
                  entry -> entry.getValue()[0])
              .reversed()
              .thenComparing(
                  Comparator.<Map.Entry<MarketUnfilledReason, long[]>>comparingLong(
                          entry -> entry.getValue()[1])
                      .reversed())
              .thenComparing(entry -> entry.getKey().value()));
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < Math.min(3, ranked.size()); i++) {
        Map.Entry<MarketUnfilledReason, long[]> entry = ranked.get(i);
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(entry.getKey().value())
            .append('×')
            .append(entry.getValue()[0])
            .append("笔/")
            .append(String.format("%,d", entry.getValue()[1]))
            .append("毫");
      }
      return text.toString();
    }
  }

  public static Reading read(SimulationState state, Shell shell, String mapId) {
    long tick = tickOf(state);
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    return new Reading(
        tick,
        head(shell),
        population(GovZ6WorldFixture.socialSlice(state)),
        commodityTotal(actor, GRAIN),
        commodityTotal(actor, CLOTH),
        debtPrincipal(economy),
        moneyByCurrency(actor),
        MarketReportFeed.last(mapId, tick));
  }

  /** 人口 = Social 切片全部家户的成员份额之和（Social 是人口/生死的唯一权威）。 */
  public static long population(SocialData social) {
    long total = 0L;
    for (Household household : social.households().values()) {
      for (long members : household.members().values()) {
        total = Math.addExact(total, members);
      }
    }
    return total;
  }

  /** 商品账上存量 = Σ 全部账户在该商品上的余额（actor 切片的 {@code balances} 是唯一真源；不含在途）。 */
  public static long commodityTotal(ActorData actor, CommodityId commodity) {
    long total = 0L;
    for (HouseholdInventory account : actor.accounts().values()) {
      total = Math.addExact(total, account.balances().getOrDefault(commodity, 0L));
    }
    return total;
  }

  /** 逐币种货币总量 = Σ 全部账户在该币种上的余额（键序按币种字面量排序 ⇒ 打印与比较都稳定）。 */
  public static Map<CurrencyId, Long> moneyByCurrency(ActorData actor) {
    Map<CurrencyId, Long> totals = new TreeMap<>(Comparator.comparing(CurrencyId::value));
    for (HouseholdInventory account : actor.accounts().values()) {
      for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return totals;
  }

  /** 债务本金 = Σ 全部债务契约的连续本金余额（计息并入本金，故它不是"原始借款额"）。 */
  public static long debtPrincipal(EconomyData economy) {
    long total = 0L;
    for (DebtContract contract : economy.debtContracts().values()) {
      total = Math.addExact(total, contract.principal());
    }
    return total;
  }

  // ── 摘要 ────────────────────────────────────────────────────────────────────────────

  /** 逐段一行（★ 按关账日打印，不是逐 tick）。 */
  public static void appendRow(List<String> summary, String label, Reading reading) {
    String marketText;
    if (reading.market().isEmpty()) {
      marketText = "市场=读不到报告";
    } else {
      MarketReport report = reading.market().orElseThrow();
      marketText =
          "市场 第"
              + report.day()
              + "日("
              + report.trigger()
              + ") 成交="
              + report.fills().size()
              + "笔 未成交top3=["
              + reading.unfilledTop3Text()
              + "]";
    }
    summary.add(
        String.format(
            "[%-4s] tick=%-3d rev=%-4d 人口=%,d 粮=%,d毫 布=%,d毫 债务本金=%,d毫 | 币: %s | %s",
            label,
            reading.tick(),
            reading.revision(),
            reading.population(),
            reading.grainMilli(),
            reading.clothMilli(),
            reading.debtPrincipalMilli(),
            reading.moneyTotalsText(),
            marketText));
  }

  public static String moneyText(Map<CurrencyId, Long> money) {
    StringBuilder text = new StringBuilder();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      if (text.length() > 0) {
        text.append(' ');
      }
      text.append(entry.getKey().value())
          .append('=')
          .append(String.format("%,d", entry.getValue()));
    }
    return text.toString();
  }

  /** 摘要落盘（给人看的证据；失败时也留得住 —— 每段都重写一次）。 */
  public static void writeSummary(List<String> summary, Path summaryFile) throws IOException {
    Files.createDirectories(summaryFile.getParent());
    Files.writeString(
        summaryFile,
        String.join(System.lineSeparator(), summary) + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }
}
