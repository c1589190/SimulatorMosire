import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★★ W5-fix3 只读探针（不进 src）：小世界铸币路径的真实读数。
 *
 * <p>四问：① 铸币 unit 的日劳动配额（= min(池日劳动 × share, 剩余预算)）② 单位规模需求 ③ 关账处的 scale 与铸币记录数
 * ④ 国库货币/工具前后值（投入真实消耗）。
 */
public class W5Fix3Probe5 {

  private static final String MAP = "W5Fix3Probe";

  public static void main(String[] args) {
    SimulationState genesis = SmallWorld.state(MAP);
    EconomyData eco = ((EconomySnapshot) genesis.module("economy").orElseThrow()).data();
    ActorData actors0 = GovZ6WorldFixture.actorSlice(genesis);
    HouseholdId treasury = GovernmentHouseholds.of("world-silver");
    CommodityId tool = new CommodityId("tool");
    CurrencyId silver = new CurrencyId("silver");
    HouseholdAccountKey tk = new HouseholdAccountKey(treasury);

    // ── ① 铸币产业 / unit ────────────────────────────────────────────────────────────────
    IndustryId mintIndustryId = null;
    for (Map.Entry<IndustryId, Industry> e : eco.industries().entrySet()) {
      if (!e.getValue().recipe().moneyOutputPerUnit().isEmpty()) {
        mintIndustryId = e.getKey();
      }
    }
    System.out.println(
        "PROBE mintIndustry=" + (mintIndustryId == null ? "<none>" : mintIndustryId.value()));
    Industry mintIndustry = eco.industries().get(mintIndustryId);
    System.out.println(
        "PROBE recipe laborPerUnit="
            + mintIndustry.recipe().laborPerUnit()
            + " inputPerUnit="
            + mintIndustry.recipe().inputPerUnit()
            + " moneyOutputPerUnit="
            + mintIndustry.recipe().moneyOutputPerUnit().perUnit()
            + " capacityPerUnit="
            + mintIndustry.recipe().capacityPerUnit());
    ProductionProcess mintUnit = null;
    for (ProductionProcess u : eco.units().values()) {
      if (u.industry().equals(mintIndustryId)) {
        mintUnit = u;
      }
    }
    System.out.println("PROBE mintUnit=" + mintUnit.id().value());

    // ── ② 铸币 unit 的日劳动配额（配额表是唯一真相）──────────────────────────────────────
    Map<String, Long> byActivity = new LinkedHashMap<>();
    for (HouseholdLaborCommitment c : eco.allocations().values()) {
      byActivity.merge(c.activity(), c.laborMilli(), Long::sum);
    }
    System.out.println(
        "PROBE allocations.total="
            + eco.allocations().size()
            + " mintDailyQuotaMilli="
            + byActivity.getOrDefault(mintUnit.id().value(), -1L));
    long craftDaily = 0L;
    for (Map.Entry<String, Long> e : byActivity.entrySet()) {
      if (e.getKey().startsWith("unit-craft@0_0-")) {
        craftDaily += e.getValue();
      }
    }
    System.out.println("PROBE craftDailyQuotaMilli(0_0)=" + craftDaily);

    // ── ③ 产能份额（1 座铸炉 = 1 单位规模）────────────────────────────────────────────────
    long mintCapacity = 0L;
    for (Map.Entry<AssetShareId, OwnershipStake> e : eco.assetShares().entrySet()) {
      OwnershipStake s = e.getValue();
      if (!s.industry().equals(mintIndustryId)) {
        continue;
      }
      System.out.println(
          "PROBE mintStake asset=" + s.asset() + " quantity=" + s.quantity() + " kind=" + s.kind());
      mintCapacity += s.quantity();
    }
    System.out.println("PROBE mintCapacityScale=" + mintCapacity);

    // ── ④ 国库开缸（货币 + 工具）────────────────────────────────────────────────────────
    HouseholdInventory acc0 = actors0.accounts().get(tk);
    System.out.println(
        "PROBE treasury0 money="
            + (acc0 == null ? "<none>" : acc0.money())
            + " silver="
            + (acc0 == null ? -1L : acc0.money().getOrDefault(silver, 0L))
            + " tool="
            + (acc0 == null ? -1L : acc0.balances().getOrDefault(tool, 0L)));

    // ── ⑤ 一路推到第一个关账日（cycleDays=120 ⇒ 第 120 个 tick 关账；一次调用 120 tick）──
    GovZ6WorldFixture.StateRunner runner = new GovZ6WorldFixture.StateRunner(MAP, genesis);
    runner.advance(1L, 121L);
    SimulationState state = runner.state();
    EconomyData after = ((EconomySnapshot) state.module("economy").orElseThrow()).data();
    ActorData actors = GovZ6WorldFixture.actorSlice(state);

    long minted = 0L;
    int recs = 0;
    for (MoneyIssuanceRecord rec : after.moneyIssuances().values()) {
      if (rec.kind() != MoneyIssuanceKind.MINT_PRODUCTION) {
        continue;
      }
      recs++;
      minted += rec.amount();
      System.out.println(
          "PROBE MINT_ISSUANCE id="
              + rec.id().value()
              + " day="
              + rec.day()
              + " period="
              + rec.period()
              + " cur="
              + rec.currency().value()
              + " amount="
              + rec.amount()
              + " reason="
              + rec.reason());
    }
    System.out.println("PROBE MINT_PRODUCTION records=" + recs + " totalMilli=" + minted);
    ProductionProcess uAfter = after.units().get(mintUnit.id());
    System.out.println(
        "PROBE mintUnitAfter progressDays="
            + uAfter.progressDays()
            + " cycleLaborMilli="
            + uAfter.cycleLaborMilli()
            + " cycleInputUsed="
            + uAfter.cycleInputUsedMilli());
    HouseholdInventory acc1 = actors.accounts().get(tk);
    System.out.println(
        "PROBE treasury1 money="
            + (acc1 == null ? "<none>" : acc1.money())
            + " silver="
            + (acc1 == null ? -1L : acc1.money().getOrDefault(silver, 0L))
            + " tool="
            + (acc1 == null ? -1L : acc1.balances().getOrDefault(tool, 0L)));
    System.out.println(
        "PROBE mintDebtContracts="
            + after.debtContracts().values().stream()
                .filter(d -> d.debtor().equals(treasury))
                .count());
    System.out.println("PROBE done");
  }
}
