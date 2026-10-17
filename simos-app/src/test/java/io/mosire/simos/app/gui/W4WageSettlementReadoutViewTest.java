package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.time.WageSettlementReadout;
import io.mosire.simos.economy.time.WageSettlementReadoutFeed;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W4（D-032 D3 §6.7 / W4 账本 2.3）：{@code economyHex} 工资结算只读读数</b>。
 *
 * <ul>
 *   <li>新增三键 {@code wageSettlementReadouts}/{@code wageSettlementReadoutStatus}/ {@code
 *       wageSettlementReadoutUnavailableReason}，且 readout 行是<b>精确键集</b>；
 *   <li>app 只读：原样投递 {@link WageSettlementReadout}、只按本格 unit 过滤并稳定排序，<b>不重算</b>支付/抵物/债；
 *   <li>读不到（未结算 / tick 不一致）⇒ 具名 unavailable，不拿空数组冒充“已结算且无义务”。
 * </ul>
 */
class W4WageSettlementReadoutViewTest {

  private static final String MAP_ID = "w4-readout";
  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final long TICK = 7L;
  private static final SimosTimestamp T0 = SimosTimestamp.of(TICK);
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("w4-readout-farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "org-w4-readout");
  private static final ProductionUnitId UNIT_A = new ProductionUnitId("unit-w4-a");
  private static final ProductionUnitId UNIT_B = new ProductionUnitId("unit-w4-b");
  private static final ProductionUnitId UNIT_OTHER = new ProductionUnitId("unit-w4-other");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ProductionRoleId ROLE = ProductionRoleId.parse("hired-hand");
  private static final HouseholdId WORKER_A = HouseholdId.parse("hh-w4-worker-a");
  private static final HouseholdId WORKER_B = HouseholdId.parse("hh-w4-worker-b");
  private static final HouseholdId PAYER = HouseholdId.parse("hh-w4-payer");

  @AfterEach
  void clearFeed() {
    WageSettlementReadoutFeed.clear(MAP_ID);
  }

  @Test
  void economyHexEmitsFeedReadoutsInStableOrderWithoutRecomputing() {
    WageSettlementReadout later = readout(UNIT_B, WORKER_B, 40L, 10L, 30L, 0L);
    WageSettlementReadout earlier = readout(UNIT_A, WORKER_A, 100L, 4L, 5L, 91L);
    WageSettlementReadout otherHex = readout(UNIT_OTHER, WORKER_A, 999L, 0L, 0L, 999L);
    // 故意乱序投递：读口必须自行按 (unit, role, worker, payer) 稳定排序。
    WageSettlementReadoutFeed.publish(MAP_ID, TICK, List.of(later, otherHex, earlier));

    Map<String, Object> body = ApiViews.economyHex(HEX, state());

    assertThat(body)
        .as("W4：三个新增键必须都在（缺键 = 读口没接线）")
        .containsKeys(
            "wageSettlementReadouts",
            "wageSettlementReadoutStatus",
            "wageSettlementReadoutUnavailableReason");
    assertThat(body.get("wageSettlementReadoutStatus")).isEqualTo("ok");
    assertThat(body.get("wageSettlementReadoutUnavailableReason"))
        .as("可得时 reason 必须是 null（不是空串/占位）")
        .isNull();

    List<Map<String, Object>> rows = rows(body, "wageSettlementReadouts");
    assertThat(rows).as("只发本格 unit 的读数；邻格 unit 不得混入").hasSize(2);
    assertThat(rows)
        .extracting(row -> row.get("unit"))
        .as("稳定序 = (unit, role, worker, payer)")
        .containsExactly(UNIT_A.value(), UNIT_B.value());

    Map<String, Object> first = rows.get(0);
    assertThat(fieldNames(first))
        .as("readout 行精确键集（app 只做形状化，不新增/不改写金额键）")
        .containsExactly(
            "unit",
            "role",
            "worker",
            "payer",
            "currency",
            "tickLaborMilli",
            "quote",
            "carryBefore",
            "carryAfter",
            "dueMilli",
            "paidMoneyMilli",
            "barterQuantity",
            "barterPaidValueMilli",
            "barter",
            "arrearsMilli",
            "debtContractId",
            "reasons");
    assertThat(first)
        .as("原值透传，不重算：due=100 = money 4 + barter 5 + arrears 91")
        .containsEntry("unit", UNIT_A.value())
        .containsEntry("role", ROLE.value())
        .containsEntry("worker", WORKER_A.value())
        .containsEntry("payer", PAYER.value())
        .containsEntry("currency", SILVER.value())
        .containsEntry("tickLaborMilli", 100_000L)
        .containsEntry("quote", 1L)
        .containsEntry("carryBefore", 0L)
        .containsEntry("carryAfter", 0L)
        .containsEntry("dueMilli", 100L)
        .containsEntry("paidMoneyMilli", 4L)
        .containsEntry("arrearsMilli", 91L)
        .containsEntry("barterQuantity", Map.of(GRAIN, 1_000L))
        .containsEntry("barterPaidValueMilli", Map.of(GRAIN, 5L))
        .containsEntry("reasons", List.of("wage-inventory-scope-unavailable"));
    @SuppressWarnings("unchecked")
    Map<String, Object> barter = (Map<String, Object>) first.get("barter");
    assertThat(fieldNames(barter))
        .as("D3 §6.7 形状的嵌套 barter 键集")
        .containsExactly("qPay", "paidValueMilli");
    assertThat(barter.get("qPay")).isEqualTo(Map.of(GRAIN, 1_000L));
    assertThat(barter.get("paidValueMilli")).isEqualTo(Map.of(GRAIN, 5L));

    assertThat(rows.get(1)).containsEntry("unit", UNIT_B.value()).containsEntry("dueMilli", 40L);
  }

  @Test
  void missingOrStaleFeedIsNamedUnavailableNotAnEmptySuccess() {
    WageSettlementReadoutFeed.publish(
        MAP_ID, TICK - 1L, List.of(readout(UNIT_A, WORKER_A, 100L, 0L, 0L, 100L)));

    Map<String, Object> body = ApiViews.economyHex(HEX, state());

    assertThat(body.get("wageSettlementReadoutStatus"))
        .as("tick 不一致 ⇒ 不得把上一 tick 读数当本 tick")
        .isEqualTo("unavailable");
    assertThat(body.get("wageSettlementReadoutUnavailableReason"))
        .isEqualTo("wage-settlement-readout-unavailable");
    assertThat(rows(body, "wageSettlementReadouts")).as("不可得时发空表（带具名 status），不是缺键/ null").isEmpty();
  }

  @Test
  void feedOnlyServesThePublishingTickAndPreservesPublishedObjects() {
    List<WageSettlementReadout> published = List.of(readout(UNIT_A, WORKER_A, 100L, 4L, 5L, 91L));
    WageSettlementReadoutFeed.publish(MAP_ID, TICK, published);

    Optional<List<WageSettlementReadout>> sameTick = WageSettlementReadoutFeed.last(MAP_ID, TICK);
    assertThat(sameTick).contains(published);
    assertThat(sameTick.orElseThrow().get(0)).isSameAs(published.get(0));
    assertThat(WageSettlementReadoutFeed.last(MAP_ID, TICK + 1L)).isEmpty();
    assertThat(WageSettlementReadoutFeed.last(MAP_ID, TICK - 1L)).isEmpty();

    WageSettlementReadoutFeed.publish(MAP_ID, TICK, List.of());
    assertThat(WageSettlementReadoutFeed.last(MAP_ID, TICK))
        .as("空列表 = 该日没有工资义务，是合法可读读数")
        .contains(List.of());
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static WageSettlementReadout readout(
      ProductionUnitId unit,
      HouseholdId worker,
      long due,
      long paidMoney,
      long barterValue,
      long arrears) {
    return new WageSettlementReadout(
        unit,
        ROLE,
        worker,
        PAYER,
        SILVER,
        100_000L,
        1L,
        0L,
        0L,
        due,
        paidMoney,
        barterValue > 0L ? Map.of(GRAIN, barterValue * 200L) : Map.of(),
        barterValue > 0L ? Map.of(GRAIN, barterValue) : Map.of(),
        arrears,
        Optional.empty(),
        arrears > 0L ? List.of("wage-inventory-scope-unavailable") : List.of());
  }

  private static SimulationState state() {
    IndustryDefinitionView plan =
        new IndustryDefinitionView(
            FARM,
            "W4 读口夹具",
            new RegimeId("tenant"),
            Optional.empty(),
            1L,
            0L,
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            1_000L,
            Map.of(GRAIN, 5L),
            Map.of(),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            ProductionExperienceUse.NONE,
            List.of(),
            Set.of(AssetRightKind.OWNED));
    ProductionData production =
        ProductionData.empty()
            .withIndustry(plan)
            .withInstance(unit(UNIT_A, HEX))
            .withInstance(unit(UNIT_B, HEX))
            .withInstance(unit(UNIT_OTHER, OTHER_HEX));
    EconomyData economy =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        MAP_ID,
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2,
                        Optional.empty())));
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(REF, T0, economy));
    modules.put("production", new ProductionSnapshot(REF, T0, production));
    modules.put("actor", new ActorSnapshot(REF, T0, ActorData.empty()));
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  private static ProductionProcess unit(ProductionUnitId id, HexCoord hex) {
    return new ProductionProcess(
        id,
        FARM,
        ProductionLocation.parse(IndustryHexKeys.hexKey(hex.q(), hex.r())),
        OPERATOR,
        FARM.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
    return (List<Map<String, Object>>) body.get(key);
  }

  private static List<String> fieldNames(Map<String, Object> row) {
    return new ArrayList<>(row.keySet());
  }
}
