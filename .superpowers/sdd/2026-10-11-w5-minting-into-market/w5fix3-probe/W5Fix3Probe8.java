import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;

/** ★ W5-fix3 只读探针 8：铸币 unit 的**周期累计劳动**前后值（关账前 119 天 / 关账后）。 */
public class W5Fix3Probe8 {
  public static void main(String[] args) {
    String map = "W5Fix3Probe8";
    SimulationState genesis = SmallWorld.state(map);
    EconomyData eco0 = ((EconomySnapshot) genesis.module("economy").orElseThrow()).data();
    HouseholdId treasury = GovernmentHouseholds.of("world-silver");
    CommodityId tool = new CommodityId("tool");
    CurrencyId silver = new CurrencyId("silver");
    HouseholdAccountKey tk = new HouseholdAccountKey(treasury);
    ProductionProcess mu = null;
    for (ProductionProcess p : eco0.units().values()) {
      var ind = eco0.industries().get(p.industry());
      if (ind != null && !ind.recipe().moneyOutputPerUnit().isEmpty()) mu = p;
    }
    System.out.println("PROBE8 genesis mintUnit cycleLaborMilli=" + mu.cycleLaborMilli()
        + " progressDays=" + mu.progressDays());
    GovZ6WorldFixture.StateRunner runner = new GovZ6WorldFixture.StateRunner(map, genesis);
    runner.advance(1L, 120L); // (1,120] = 119 tick
    EconomyData eco1 = ((EconomySnapshot) runner.state().module("economy").orElseThrow()).data();
    ProductionProcess u1 = eco1.units().get(mu.id());
    System.out.println("PROBE8 day119 mintUnit progressDays=" + u1.progressDays()
        + " cycleLaborMilli=" + u1.cycleLaborMilli()
        + " cycleInputUsed=" + u1.cycleInputUsedMilli());
    runner.advance(120L, 121L); // 第 120 个 tick = 关账日
    SimulationState s2 = runner.state();
    EconomyData eco2 = ((EconomySnapshot) s2.module("economy").orElseThrow()).data();
    ActorData actors = GovZ6WorldFixture.actorSlice(s2);
    ProductionProcess u2 = eco2.units().get(mu.id());
    int recs = 0; long minted = 0;
    for (MoneyIssuanceRecord r : eco2.moneyIssuances().values()) {
      if (r.kind() == MoneyIssuanceKind.MINT_PRODUCTION) { recs++; minted += r.amount(); }
    }
    HouseholdInventory acc = actors.accounts().get(tk);
    System.out.println("PROBE8 afterClose mintUnit progressDays=" + u2.progressDays()
        + " cycleLaborMilli=" + u2.cycleLaborMilli()
        + " cycleInputUsed=" + u2.cycleInputUsedMilli());
    System.out.println("PROBE8 records=" + recs + " minted=" + minted
        + " treasurySilver=" + (acc == null ? -1L : acc.money().getOrDefault(silver, 0L))
        + " treasuryTool=" + (acc == null ? -1L : acc.balances().getOrDefault(tool, 0L)));
    System.out.println("PROBE8 done");
  }
}
