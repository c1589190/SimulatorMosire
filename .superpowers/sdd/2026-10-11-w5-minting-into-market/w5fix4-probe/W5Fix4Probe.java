import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★★ W5-fix4 只读探针（不进 src、不进 Maven）：跑小世界 4 个铸币轮，读"本轮 ≤ 上一轮印量"这条闸门。
 *
 * <p>它**不改任何仓内文件**：只在内存里把政府的政策旋钮 {@code mintScalePerCycle} 改掉（每轮换一次），
 * 观察 ① 第 1 轮印量 X 与当时的 limit ② 第 2 轮旋钮抬到 &gt; X ⇒ 实印 ≤ X 且 MINT_LIMIT_REJECTED 的 limit == X
 * ③ 旋钮 ≤ X ⇒ 实印 = 旋钮 ④ 再抬回 &gt; X ⇒ 上限跟的是**上一轮**（100）而不是更早的 200。
 *
 * <p>读数全部来自真状态（{@code EconomyData.moneyIssuances} / 政府表 / actor 账户）与真日志（log4j2 console）。
 */
public class W5Fix4Probe {

  private static final CurrencyId SILVER = new CurrencyId("silver");

  public static void main(String[] args) {
    int rounds = args.length > 0 && !args[0].isBlank() ? Integer.parseInt(args[0]) : 4;
    boolean keepKnob = args.length > 1 && "keep-knob".equals(args[1]); // 出厂旋钮 2,000 全程不改
    String map = "W5Fix4Probe";
    SimulationState state = SmallWorld.state(map);
    System.out.println("PROBE genesis lastClosedCycle=" + lastClosedCycle(state));
    dumpIndustryCycles(state, true);
    dumpGovernments(state, "genesis");

    // ── 第 1 轮：政策旋钮 = 200（< 请求 500 ⇒ 第 1 轮印 200 = X，limit 应为政策旋钮 200） ──
    if (!keepKnob) {
      state = withKnob(state, 200L);
    }
    state = round(state, map, 1L, 121L, "ROUND1");
    long x = mintedTotal(state);
    System.out.println("PROBE ROUND1 mintedX=" + x + " lastClosedCycle=" + lastClosedCycle(state));

    // ── 第 2 轮：旋钮抬到 2,000（> X）⇒ 期望 实印 ≤ X=200 且 MINT_LIMIT_REJECTED limit == 200 ──
    if (!keepKnob) {
      state = withKnob(state, 2_000L);
    }
    state = round(state, map, 121L, 241L, "ROUND2");
    System.out.println(
        "PROBE ROUND2 mintedTotal="
            + mintedTotal(state)
            + " mintedThisRound="
            + mintedOfDay(state, 241L)
            + " lastClosedCycle="
            + lastClosedCycle(state));

    if (rounds < 3) {
      System.out.println("PROBE stop after " + rounds + " round(s)");
      return;
    }
    // ── 第 3 轮：旋钮 100（≤ X）⇒ 期望 实印 = 旋钮 100（不误伤） ──
    if (!keepKnob) {
      state = withKnob(state, 100L);
    }
    state = round(state, map, 241L, 361L, "ROUND3");
    System.out.println(
        "PROBE ROUND3 mintedTotal="
            + mintedTotal(state)
            + " mintedThisRound="
            + mintedOfDay(state, 361L)
            + " lastClosedCycle="
            + lastClosedCycle(state));

    if (rounds < 4) {
      System.out.println("PROBE stop after " + rounds + " round(s)");
      return;
    }
    // ── 第 4 轮：旋钮再抬回 2,000（> 上一轮 100）⇒ 期望 实印 ≤ 100（跟的是**上一轮**，不是更早的 200） ──
    if (!keepKnob) {
      state = withKnob(state, 2_000L);
    }
    state = round(state, map, 361L, 481L, "ROUND4");
    System.out.println(
        "PROBE ROUND4 mintedTotal="
            + mintedTotal(state)
            + " mintedThisRound="
            + mintedOfDay(state, 481L)
            + " lastClosedCycle="
            + lastClosedCycle(state));

    dumpGovernments(state, "after-round4");
    dumpTreasury(state, "after-round4");
    System.out.println("PROBE done");
  }

  /** 推一个 120 tick 的产业周期（(from, to] = 120 个 tick ⇒ 关账日 = to）。 */
  private static SimulationState round(
      SimulationState state, String map, long from, long to, String label) {
    System.out.println("PROBE " + label + " advancing (" + from + "," + to + "] knob=" + knobs(state));
    GovZ6WorldFixture.StateRunner runner = new GovZ6WorldFixture.StateRunner(map, state);
    runner.advance(from, to);
    SimulationState next = runner.state();
    dumpIssuances(next, label);
    dumpTreasury(next, label);
    return next;
  }

  /** 把所有政府的铸币政策旋钮改成 {@code knob}（只在内存里；探针不改仓）。 */
  private static SimulationState withKnob(SimulationState state, long knob) {
    EconomySnapshot snap = (EconomySnapshot) state.module("economy").orElseThrow();
    EconomyData data = snap.data();
    Map<GovernmentId, Government> governments = new LinkedHashMap<>();
    for (Government government : data.governments().values()) {
      governments.put(government.id(), government.withMintScalePerCycle(knob));
    }
    EconomySnapshot next =
        new EconomySnapshot(snap.ref(), snap.timestamp(), data.withGovernments(governments));
    return GovZ6WorldFixture.withModule(state, "economy", next);
  }

  private static String knobs(SimulationState state) {
    StringBuilder out = new StringBuilder();
    for (Government government : economy(state).governments().values()) {
      if (out.length() > 0) {
        out.append(',');
      }
      out.append(government.id().value()).append('=').append(government.mintScalePerCycle());
    }
    return out.toString();
  }

  private static long lastClosedCycle(SimulationState state) {
    return economy(state).meta().orElseThrow().lastClosedCycle().orElse(-1L);
  }

  private static EconomyData economy(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  /** 铸币记录的逐条读数：id / day / **period** / 币种 / 金额（本批判据①的原始证据）。 */
  private static void dumpIssuances(SimulationState state, String label) {
    int count = 0;
    for (MoneyIssuanceRecord record : economy(state).moneyIssuances().values()) {
      if (record.kind() != MoneyIssuanceKind.MINT_PRODUCTION) {
        continue;
      }
      count++;
      System.out.println(
          "PROBE "
              + label
              + " MINT day="
              + record.day()
              + " period="
              + record.period()
              + " cur="
              + record.currency().value()
              + " amount="
              + record.amount()
              + " gov="
              + record.governmentId().value()
              + " id="
              + record.id().value());
    }
    System.out.println(
        "PROBE " + label + " MINT records=" + count + " totalMinted=" + mintedTotal(state));
  }

  /** 政府表读数：旋钮 + issuable + 国库 actor。 */
  private static void dumpGovernments(SimulationState state, String label) {
    for (Government government : economy(state).governments().values()) {
      System.out.println(
          "PROBE "
              + label
              + " GOV id="
              + government.id().value()
              + " mintScalePerCycle="
              + government.mintScalePerCycle()
              + " issuable="
              + government.issuable()
              + " treasury="
              + government.treasury().kind()
              + "/"
              + government.treasury().id()
              + " mintedLastCyclePerCycle="
              + government.mintedLastCyclePerCycle());
    }
  }

  /** 国库家户的账户读数（钱真的进了国库）。 */
  private static void dumpTreasury(SimulationState state, String label) {
    ActorData actors = GovZ6WorldFixture.actorSlice(state);
    for (Government government : economy(state).governments().values()) {
      HouseholdId treasury = GovernmentHouseholds.of("world-silver");
      HouseholdInventory account = actors.accounts().get(new HouseholdAccountKey(treasury));
      System.out.println(
          "PROBE "
              + label
              + " TREASURY gov="
              + government.id().value()
              + " household="
              + treasury.value()
              + " silver="
              + (account == null ? -1L : account.money().getOrDefault(SILVER, 0L))
              + " tool="
              + (account == null ? -1L : account.balances().getOrDefault(new CommodityId("tool"), 0L)));
    }
  }

  /** 创世证据：各产业的 cycleDays（铸币 120 vs 官署 30 ⇒ period 在两次铸币之间步进 4）。 */
  private static void dumpIndustryCycles(SimulationState state, boolean byCycleDays) {
    Map<Long, Integer> histogram = new LinkedHashMap<>();
    for (Industry industry : economy(state).industries().values()) {
      histogram.merge(industry.cycleDays(), 1, Integer::sum);
    }
    System.out.println("PROBE genesis industryCycleDaysHistogram=" + histogram);
  }

  /** 全部 MINT_PRODUCTION 之和（毫）。 */
  private static long mintedTotal(SimulationState state) {
    long total = 0L;
    for (MoneyIssuanceRecord record : economy(state).moneyIssuances().values()) {
      if (record.kind() == MoneyIssuanceKind.MINT_PRODUCTION) {
        total += record.amount();
      }
    }
    return total;
  }

  /** 指定关账日的铸币额（= 本轮实印）。 */
  private static long mintedOfDay(SimulationState state, long day) {
    long total = 0L;
    for (MoneyIssuanceRecord record : economy(state).moneyIssuances().values()) {
      if (record.kind() == MoneyIssuanceKind.MINT_PRODUCTION && record.day() == day) {
        total += record.amount();
      }
    }
    return total;
  }
}
