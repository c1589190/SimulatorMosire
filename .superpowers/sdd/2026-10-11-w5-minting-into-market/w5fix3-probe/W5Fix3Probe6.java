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

/** ★ W5-fix3 只读探针 6：连推 240 tick（两个关账日）—— 验证"每轮都铸 + 工具吃光后停工"。 */
public class W5Fix3Probe6 {
  public static void main(String[] args) {
    String map = "W5Fix3Probe6";
    SimulationState genesis = SmallWorld.state(map);
    HouseholdId treasury = GovernmentHouseholds.of("world-silver");
    CommodityId tool = new CommodityId("tool");
    CurrencyId silver = new CurrencyId("silver");
    HouseholdAccountKey tk = new HouseholdAccountKey(treasury);
    GovZ6WorldFixture.StateRunner runner = new GovZ6WorldFixture.StateRunner(map, genesis);
    // 关账日 = 第 120 / 240 个 tick；一次调用 240 tick 走到第二个关账日。
    runner.advance(1L, 241L);
    SimulationState state = runner.state();
    EconomyData eco = ((EconomySnapshot) state.module("economy").orElseThrow()).data();
    ActorData actors = GovZ6WorldFixture.actorSlice(state);
    long minted = 0L;
    int recs = 0;
    for (MoneyIssuanceRecord rec : eco.moneyIssuances().values()) {
      if (rec.kind() != MoneyIssuanceKind.MINT_PRODUCTION) continue;
      recs++; minted += rec.amount();
      System.out.println("PROBE6 MINT_ISSUANCE day=" + rec.day() + " period=" + rec.period()
          + " amount=" + rec.amount() + " reason=" + rec.reason());
    }
    System.out.println("PROBE6 records=" + recs + " totalMilli=" + minted);
    ProductionProcess u = null;
    for (ProductionProcess p : eco.units().values()) {
      var ind = eco.industries().get(p.industry());
      if (ind != null && !ind.recipe().moneyOutputPerUnit().isEmpty()) u = p;
    }
    System.out.println("PROBE6 mintUnit progressDays=" + u.progressDays()
        + " cycleLaborMilli=" + u.cycleLaborMilli() + " cycleInputUsed=" + u.cycleInputUsedMilli());
    HouseholdInventory acc = actors.accounts().get(tk);
    System.out.println("PROBE6 treasury silver=" + (acc == null ? -1L : acc.money().getOrDefault(silver, 0L))
        + " tool=" + (acc == null ? -1L : acc.balances().getOrDefault(tool, 0L)));
    System.out.println("PROBE6 done");
  }
}
