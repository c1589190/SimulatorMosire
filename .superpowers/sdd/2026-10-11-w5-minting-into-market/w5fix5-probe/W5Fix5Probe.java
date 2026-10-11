import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★★ W5-fix5 只读探针（不进 src、不进 Maven 门禁）：验证"上限不夹 = 政策旋钮即权威"，且"上一轮印量"仍是真读数。
 *
 * <p>三格（任务书 §3）：
 *
 * <ol>
 *   <li>第 1 轮旋钮 200 ⇒ 实印 ≤ 200（= 200）；
 *   <li>第 2 轮旋钮抬到 2,000（上一轮只印了 200）⇒ <b>实印可到 500</b>（不再被 200 夹住），
 *       且 {@code previousCycleMinted} 读数为 <b>200</b>（真值）；关账后 {@code mintedLastCyclePerCycle} == 200；
 *   <li>第 3 轮旋钮 0 ⇒ 0 毫（且读数仍真：上一轮 = 500）。
 * </ol>
 *
 * <p>★ 附：铁律 5 三件套（Codec 往返 / ChangeSet diff+apply 往返）在**真状态**（非空 mintedLastCyclePerCycle）上跑一次。
 * ★ 它**不改任何仓内文件**：只在内存里改政府旋钮（每轮换一次）。classpath 口径见 run-probe.sh。
 */
public class W5Fix5Probe {

  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId TOOL = new CommodityId("tool");

  public static void main(String[] args) {
    String map = "W5Fix5Probe";
    SimulationState state = SmallWorld.state(map);
    System.out.println("PROBE genesis lastClosedCycle=" + lastClosedCycle(state));
    dumpGovernments(state, "genesis");

    // ── 第 1 轮：旋钮 200（< 请求 500）⇒ 实印 200 ─────────────────────────────────────────
    state = withKnob(state, 200L);
    SimulationState before1 = state;
    state = round(state, map, 0L, 121L, "ROUND1");
    System.out.println("PROBE ROUND1 mintedThisRound=" + mintedLatestRound(state));
    System.out.println("PROBE ROUND1 mintedLastCyclePerCycle=" + lastCycleField(state));
    dumpGovernments(state, "after-round1-treasury");

    // ── 第 2 轮：旋钮抬到 2,000（**上一轮只印了 200**）⇒ 实印可到 500，读数 = 200 ──────────
    state = withKnob(state, 2_000L);
    SimulationState before2 = state;
    state = round(state, map, 121L, 241L, "ROUND2");
    System.out.println("PROBE ROUND2 mintedThisRound=" + mintedLatestRound(state));
    System.out.println("PROBE ROUND2 mintedLastCyclePerCycle=" + lastCycleField(state));
    dumpGuiView(state, "after-round2");

    // ── 第 3 轮：旋钮 0 ⇒ 0 毫；读数仍真（上一轮 = 500） ────────────────────────────────
    state = withKnob(state, 0L);
    SimulationState before3 = state;
    state = round(state, map, 241L, 361L, "ROUND3");
    System.out.println("PROBE ROUND3 mintedThisRound=" + mintedLatestRound(state));
    System.out.println("PROBE ROUND3 mintedLastCyclePerCycle=" + lastCycleField(state));

    // ── 附：铁律 5 三件套（真状态上的 Codec / ChangeSet 往返） ────────────────────────────
    iron5RoundTrips(before2, state, before1, before3);
    System.out.println("PROBE done");
  }

  /** 铁律 5：① 快照 Codec 往返逐值 ② ChangeSet diff 派生 ③ ChangeSet Codec 往返 + apply 重建 target。 */
  private static void iron5RoundTrips(
      SimulationState before2, SimulationState after, SimulationState before1, SimulationState before3) {
    EconomyCodec codec = new EconomyCodec();
    EconomySnapshot base = economySnap(before2);
    EconomySnapshot target = economySnap(after);

    // ① 快照 Codec 往返（重点：非空 mintedLastCyclePerCycle 必须逐值回来）
    EconomySnapshot back = (EconomySnapshot) codec.decodeSnapshot(codec.encodeSnapshot(target));
    System.out.println(
        "PROBE IRON5 snapshotRoundTrip dataEqual="
            + back.data().equals(target.data())
            + " fieldBefore="
            + fieldOf(target.data())
            + " fieldAfter="
            + fieldOf(back.data()));

    // ② 变更集从完整状态派生（铁律 5 的第一半）
    ChangeSet changeSet = codec.diff(base, target);
    String json = codec.encodeChangeSet(changeSet);
    System.out.println(
        "PROBE IRON5 changeSetBytes=" + json.length() + " mentionsMintedLastCycle=" + json.contains("mintedLastCyclePerCycle"));

    // ③ 变更集 Codec 往返 + apply(base) 逐字段重建 target
    ChangeSet decoded = codec.decodeChangeSet(json);
    Snapshot applied =
        codec.apply(
            decoded,
            base,
            new StateMeta(
                new StateRef(target.ref().branch(), target.ref().revision()),
                SimosTimestamp.of(target.timestamp().tick())));
    boolean rebuilt = ((EconomySnapshot) applied).data().equals(target.data());
    System.out.println(
        "PROBE IRON5 changeSetRoundTrip rebuiltTarget="
            + rebuilt
            + " appliedField="
            + fieldOf(((EconomySnapshot) applied).data()));

    // ④ 负向：把 base（第 2 轮前，字段 = 200）与 target（第 3 轮后，字段 = 500）也走一遍 —— 证明增量不是恒空
    System.out.println(
        "PROBE IRON5 fieldValues genesis="
            + fieldOf(economySnap(before1).data())
            + " afterRound1="
            + fieldOf(base.data())
            + " afterRound2="
            + fieldOf(economySnap(before3).data())
            + " afterRound3="
            + fieldOf(target.data()));
  }

  private static String fieldOf(EconomyData data) {
    StringBuilder out = new StringBuilder();
    for (Government government : data.governments().values()) {
      if (out.length() > 0) {
        out.append(',');
      }
      out.append(government.id().value()).append('=').append(government.mintedLastCyclePerCycle());
    }
    return out.toString();
  }

  private static String lastCycleField(SimulationState state) {
    return fieldOf(economy(state));
  }

  private static EconomySnapshot economySnap(SimulationState state) {
    return (EconomySnapshot) state.module("economy").orElseThrow();
  }

  /** 推一个 120 tick 的产业周期（(from, to] = 120 个 tick ⇒ 关账日 = to）。 */
  private static SimulationState round(
      SimulationState state, String map, long from, long to, String label) {
    System.out.println("PROBE " + label + " advancing (" + from + "," + to + "] knob=" + knobs(state));
    long mintedBefore = mintedTotal(state);
    int recordsBefore = mintRecordCount(state);
    GovZ6WorldFixture.StateRunner runner = new GovZ6WorldFixture.StateRunner(map, state);
    runner.advance(from, to);
    SimulationState next = runner.state();
    dumpIssuances(next, label);
    dumpTreasury(next, label);
    System.out.println(
        "PROBE "
            + label
            + " mintedDelta="
            + (mintedTotal(next) - mintedBefore)
            + " newRecords="
            + (mintRecordCount(next) - recordsBefore)
            + " mintedTotalAfter="
            + mintedTotal(next));
    return next;
  }

  /** 把所有政府的铸币政策旋钮改成 {@code knob}（只在内存里；探针不改仓）。 */
  private static SimulationState withKnob(SimulationState state, long knob) {
    EconomySnapshot snap = economySnap(state);
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
    return economySnap(state).data();
  }

  /** 铸币记录的逐条读数：id / day / period / 币种 / 金额。 */
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

  /** ★ GUI 读口读数：{@code ApiViews.economyGovernment} 的 governments[].mintedLastCyclePerCycle。 */
  private static void dumpGuiView(SimulationState state, String label) {
    Object view = ApiViews.economyGovernment(state).get("governments");
    if (!(view instanceof java.util.List<?> rows)) {
      System.out.println("PROBE " + label + " GUIVIEW governments=" + view);
      return;
    }
    for (Object row : rows) {
      @SuppressWarnings("unchecked")
      Map<String, Object> node = (Map<String, Object>) row;
      System.out.println(
          "PROBE "
              + label
              + " GUIVIEW gov="
              + node.get("id")
              + " mintScalePerCycle="
              + node.get("mintScalePerCycle")
              + " mintedLastCyclePerCycle="
              + node.get("mintedLastCyclePerCycle"));
    }
  }

  /** 政府表读数：旋钮 + 上一轮印量读数（本批接通的那一栏）。 */
  private static void dumpGovernments(SimulationState state, String label) {
    for (Government government : economy(state).governments().values()) {
      System.out.println(
          "PROBE "
              + label
              + " GOV id="
              + government.id().value()
              + " mintScalePerCycle="
              + government.mintScalePerCycle()
              + " mintedLastCyclePerCycle="
              + government.mintedLastCyclePerCycle()
              + " issuable="
              + government.issuable()
              + " treasury="
              + government.treasury().kind()
              + "/"
              + government.treasury().id());
    }
  }

  /** 国库家户的账户读数（钱真的进了国库）。 */
  private static void dumpTreasury(SimulationState state, String label) {
    ActorData actors = GovZ6WorldFixture.actorSlice(state);
    for (Government government : economy(state).governments().values()) {
      HouseholdId treasury = HouseholdActors.householdOf(government.treasury());
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
              + (account == null ? -1L : account.balances().getOrDefault(TOOL, 0L)));
    }
  }

  /** MINT_PRODUCTION 记录条数。 */
  private static int mintRecordCount(SimulationState state) {
    int count = 0;
    for (MoneyIssuanceRecord record : economy(state).moneyIssuances().values()) {
      if (record.kind() == MoneyIssuanceKind.MINT_PRODUCTION) {
        count++;
      }
    }
    return count;
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

  /** 最近一个铸币日（关账日）的铸币额（= 本轮实印）—— 日号从记录里现读，不靠探针自己猜。 */
  private static long mintedLatestRound(SimulationState state) {
    long latestDay = -1L;
    for (MoneyIssuanceRecord record : economy(state).moneyIssuances().values()) {
      if (record.kind() == MoneyIssuanceKind.MINT_PRODUCTION && record.day() > latestDay) {
        latestDay = record.day();
      }
    }
    long total = 0L;
    for (MoneyIssuanceRecord record : economy(state).moneyIssuances().values()) {
      if (record.kind() == MoneyIssuanceKind.MINT_PRODUCTION && record.day() == latestDay) {
        total += record.amount();
      }
    }
    return total;
  }
}
