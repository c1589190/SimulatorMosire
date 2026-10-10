package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** W1g 只读探针：用真 handler 跑夹具改过的 4 条载荷（不写仓库、不跑 JUnit / Maven 门禁）。 */
public class W1gProbe {

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Class<?> testClass = Class.forName("io.mosire.simos.app.McpCoverageTest");
    Field minimal = testClass.getDeclaredField("MINIMAL_PAYLOADS");
    minimal.setAccessible(true);
    Map<String, String> payloads = (Map<String, String>) minimal.get(null);
    Field rejects = testClass.getDeclaredField("PRECONDITION_REJECT_PAYLOADS");
    rejects.setAccessible(true);
    Map<String, String> rejectPayloads = (Map<String, String>) rejects.get(null);

    SimosTimestamp t7 = SimosTimestamp.of(7);
    JsonNode seed = EconomyPayloads.parse(payloads.get("economy.Seed"));
    EconomyData seeded = EconomyPayloads.toData(seed, t7);
    MarketZoneId marketId = new MarketZoneId("single-region");
    MarketTable seededTable = seeded.marketTables().get(marketId);
    System.out.println(
        "[1] economy.Seed 被 EconomyPayloads 接受：marketTables="
            + seeded.marketTables().keySet()
            + " 成员格="
            + seededTable.hexes()
            + " 计价币="
            + seededTable.numeraire().value()
            + " 出厂粮价="
            + seededTable.priceOf(new CommodityId("grain")));

    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimulationState state =
        new SimulationState(
            new StateMeta(ref, t7),
            Map.of("economy", new EconomySnapshot(ref, t7, seeded)),
            InMemoryInfoSystem.empty());

    HandlerOutcome priceOutcome =
        new EconomySetMarketPriceHandler().handle(state, payloads.get("economy.SetMarketPrice"));
    Map<MarketZoneId, MarketTable> expectedTables = new LinkedHashMap<>(seeded.marketTables());
    expectedTables.put(marketId, seededTable.withPrice(new CommodityId("grain"), 2L));
    EconomyData expectedPriced = seeded.withMarketTables(expectedTables);
    System.out.println(
        "[2] SetMarketPrice outcome="
            + priceOutcome.getClass().getSimpleName()
            + " / 变更集 == between(播种态, 'single-region 粮价=2' 态) ? "
            + priceOutcome.equals(
                new HandlerOutcome.Applied(EconomyChangeSet.between(seeded, expectedPriced)))
            + " / 该态粮价="
            + expectedPriced
                .marketTables()
                .get(marketId)
                .priceOf(new CommodityId("grain")));

    SimulationState priced =
        new SimulationState(
            new StateMeta(ref, t7),
            Map.of("economy", new EconomySnapshot(ref, t7, expectedPriced)),
            InMemoryInfoSystem.empty());
    HandlerOutcome demandOutcome =
        new EconomyAddDemandHandler().handle(priced, payloads.get("economy.AddDemand"));
    System.out.println(
        "[3] AddDemand(hex=1_1) outcome="
            + demandOutcome.getClass().getSimpleName()
            + (demandOutcome instanceof HandlerOutcome.Rejected r ? " reason=" + r.reason() : ""));

    // 反向：改前的 (1,2) 在老地方是否真的不再成立（判别力自证：新载荷不是"换个写法照样过"）
    HandlerOutcome oldHexDemand =
        new EconomyAddDemandHandler()
            .handle(
                priced,
                "{\"id\":\"demand-old-hex\",\"scope\":\"HEX\",\"hex\":{\"q\":1,\"r\":2},"
                    + "\"commodity\":\"grain\",\"kind\":\"RECURRING\",\"unit\":\"TOTAL\","
                    + "\"quantityPerCycle\":1}");
    System.out.println(
        "[3b] 旧载荷 AddDemand(hex=1_2) outcome="
            + oldHexDemand.getClass().getSimpleName()
            + (oldHexDemand instanceof HandlerOutcome.Rejected r2 ? " reason=" + r2.reason() : ""));

    HandlerOutcome oldPriceOutcome =
        new EconomySetMarketPriceHandler()
            .handle(state, "{\"q\":1,\"r\":2,\"commodity\":\"grain\",\"price\":2}");
    System.out.println(
        "[2b] 旧载荷 SetMarketPrice outcome="
            + oldPriceOutcome.getClass().getSimpleName()
            + (oldPriceOutcome instanceof HandlerOutcome.Rejected r3 ? " reason=" + r3.reason() : ""));

    HandlerOutcome numOutcome =
        new EconomySetMarketNumeraireHandler()
            .handle(state, rejectPayloads.get("economy.SetMarketNumeraire"));
    System.out.println(
        "[4] SetMarketNumeraire(拒例) outcome="
            + numOutcome.getClass().getSimpleName()
            + (numOutcome instanceof HandlerOutcome.Rejected r4 ? " reason=" + r4.reason() : ""));
  }
}
