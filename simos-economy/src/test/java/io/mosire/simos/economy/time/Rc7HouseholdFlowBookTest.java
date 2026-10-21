package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.HouseholdFlowTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC7：S7-C 向下流动表（同 hex landed→landless）</b>。
 *
 * <p>判据：首轮只建表；债务扩大后只迁人口（10‰，source 至少保留 1 人）；钱/货/债/资产/经验不随人走； 每轮最多一次；0 人口目标不当作活目标；无目标时创建无
 * mode/position/standing 的 0 人口 PROLETARIAN 壳并 outbox `targetIsNew=true`。ownershipClass 由 LAND
 * 等生产资料派生，不由 class membership 推断。
 */
class Rc7HouseholdFlowBookTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HouseholdId SOURCE = HouseholdId.parse("hh-flow-source");
  private static final HouseholdId TARGET = HouseholdId.parse("hh-flow-target");
  private static final HouseholdId ZERO_SHELL = HouseholdId.parse("hh-flow-zero-shell");
  private static final HouseholdId CREDITOR = HouseholdId.parse("hh-flow-creditor");
  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final io.mosire.simos.economy.api.id.CommodityId GRAIN =
      new io.mosire.simos.economy.api.id.CommodityId("grain");
  private static final HouseholdCultureKey CULTURE =
      new HouseholdCultureKey("latium", "roman", List.of(), "本地农户");
  private static final HouseholdCultureKeyLookup CULTURE_LOOKUP = household -> Optional.of(CULTURE);

  @Test
  void firstMarketRoundOnlyBuildsTheTableAndDebtExpansionFlowsOnceWithAssets() {
    World world = World.withLiveTarget();
    HouseholdFlowBook.FlowRound first = HouseholdFlowBook.settle(world.ctx, 5L);
    assertThat(first.tablesUpdated()).as("首轮只建表").isEqualTo(1);
    assertThat(first.flows()).as("首轮不流动").isZero();
    HouseholdFlowTable table = world.ctx.session.sheet().householdFlowTables().get(SOURCE);
    assertThat(table).isNotNull();
    assertThat(table.targets()).extracting(t -> t.targetHousehold()).containsExactly(TARGET);
    assertThat(table.lastObservedDebtPrincipalMilli()).isEqualTo(100L);
    assertThat(table.lastFlowDay()).isEqualTo(-1L);

    world.increaseDebtTo(200L);
    HouseholdFlowBook.FlowRound second = HouseholdFlowBook.settle(world.ctx, 5L);
    assertThat(second.flows()).as("债务扩大 ⇒ 恰一次 flow").isEqualTo(1);
    assertThat(second.population()).as("10‰ of 100 = 1，且保留 source ≥ 1").isEqualTo(1L);
    assertThat(world.ctx.session.sheet().householdFlowTables().get(SOURCE).lastFlowDay())
        .as("lastFlowDay 更新")
        .isEqualTo(5L);
    assertThat(
            world
                .ctx
                .session
                .sheet()
                .householdFlowTables()
                .get(SOURCE)
                .lastObservedDebtPrincipalMilli())
        .as("lastObserved 记录本轮触发前债务")
        .isEqualTo(200L);
    assertThat(world.ctx.accounts.householdMoney().get(SOURCE).get(SILVER))
        .as("向下 flow 不搬货币：source 仍持有全部 1,000")
        .isEqualTo(1_000L);
    assertThat(world.ctx.accounts.householdMoney().get(TARGET))
        .as("target 不承接 source 货币")
        .isEmpty();
    assertThat(world.ctx.accounts.householdGoods().get(SOURCE).get(GRAIN))
        .as("向下 flow 不搬商品库存")
        .isEqualTo(1_000L);
    assertThat(world.ctx.accounts.householdGoods().get(TARGET))
        .as("target 不承接 source 商品库存")
        .isEmpty();
    assertThat(world.ctx.session.sheet().debtContracts().values())
        .as("向下 flow 不搬债务：债务合同仍在 source")
        .allSatisfy(debt -> assertThat(debt.debtor()).isEqualTo(SOURCE));

    HouseholdFlowBook.FlowRound third = HouseholdFlowBook.settle(world.ctx, 5L);
    assertThat(third.flows()).as("同 day 不得再次 flow").isZero();
  }

  @Test
  void belowHundredPopulationFloorsToOnePercentToZeroWithoutForcingAMigrant() {
    World world = World.withLiveTargetPopulation(90L);
    HouseholdFlowBook.settle(world.ctx, 5L);
    world.increaseDebtTo(200L);

    HouseholdFlowBook.FlowRound round = HouseholdFlowBook.settle(world.ctx, 5L);

    assertThat(HouseholdFlowBook.MAX_FLOW_PER_MILLE).as("RC8e：流量上限 = 10‰ = 1%").isEqualTo(10L);
    assertThat(round.flows()).as("触发但 floor(90 × 10 / 1000) = 0 ⇒ 不强行迁 1 人").isZero();
    assertThat(round.population()).isZero();
    assertThat(world.ctx.session.pendingPopulationTransfers()).isEmpty();
    HouseholdFlowTable table = world.ctx.session.sheet().householdFlowTables().get(SOURCE);
    assertThat(table.lastObservedDebtPrincipalMilli())
        .as("即使本轮迁 0 人，触发后的基线仍推进到当刻债务")
        .isEqualTo(200L);
  }

  @Test
  void debtHysteresisKeepsTheHighWaterBaselineAndIgnoresSmallRebounds() {
    World world = World.withInitialDebt(1_000L);
    HouseholdFlowBook.settle(world.ctx, 5L);

    world.increaseDebtTo(1_005L);
    HouseholdFlowBook.FlowRound belowThreshold = HouseholdFlowBook.settle(world.ctx, 5L);
    assertThat(belowThreshold.flows()).as("+5 小于基线 1,000 的 1% 阈值 10 ⇒ 不触发").isZero();
    assertThat(
            world
                .ctx
                .session
                .sheet()
                .householdFlowTables()
                .get(SOURCE)
                .lastObservedDebtPrincipalMilli())
        .as("未触发不重置高水位")
        .isEqualTo(1_000L);

    world.increaseDebtTo(900L);
    HouseholdFlowBook.settle(world.ctx, 5L);
    world.increaseDebtTo(1_009L);
    HouseholdFlowBook.FlowRound rebound = HouseholdFlowBook.settle(world.ctx, 5L);
    assertThat(rebound.flows()).as("债务回落后的小幅反弹不得因基线被刷新而误触发").isZero();
    assertThat(
            world
                .ctx
                .session
                .sheet()
                .householdFlowTables()
                .get(SOURCE)
                .lastObservedDebtPrincipalMilli())
        .as("高水位保持 1,000")
        .isEqualTo(1_000L);

    world.increaseDebtTo(1_010L);
    HouseholdFlowBook.FlowRound triggered = HouseholdFlowBook.settle(world.ctx, 5L);
    assertThat(triggered.flows()).as("达到基线 +1% ⇒ 触发").isEqualTo(1);
    assertThat(
            world
                .ctx
                .session
                .sheet()
                .householdFlowTables()
                .get(SOURCE)
                .lastObservedDebtPrincipalMilli())
        .as("触发后基线推进到当刻债务")
        .isEqualTo(1_010L);
  }

  @Test
  void noLiveTargetCreatesAZeroPopulationLandlessShellAndOutbox() {
    World world = World.withoutLiveTarget();
    HouseholdFlowBook.settle(world.ctx, 5L);
    world.increaseDebtTo(200L);
    HouseholdFlowBook.FlowRound round = HouseholdFlowBook.settle(world.ctx, 5L);

    assertThat(round.createdTargets()).as("无活目标 ⇒ 创建 0 人口 LANDLESS 壳").isEqualTo(1);
    assertThat(round.flows()).isEqualTo(1);
    HouseholdFlowTable table = world.ctx.session.sheet().householdFlowTables().get(SOURCE);
    HouseholdId created = table.targets().get(0).targetHousehold();
    assertThat(created).as("不得复用 0 人口壳作正人口目标").isNotEqualTo(ZERO_SHELL);
    HouseholdEconomy shell = world.ctx.session.sheet().householdEconomies().get(created);
    assertThat(shell).isNotNull();
    assertThat(shell.population()).as("新建壳人口 = 0（人口由 outbox 写 Social）").isZero();
    assertThat(world.ctx.session.sheet().classMemberships())
        .as("壳无 mode/position/class standing")
        .doesNotContainKey(created);
    assertThat(world.ctx.accounts.householdGoods().get(created)).isEmpty();
    assertThat(world.ctx.accounts.householdMoney().get(created)).isEmpty();
    assertThat(
            world
                .ctx
                .session
                .sheet()
                .householdFlowTables()
                .get(SOURCE)
                .targets()
                .get(0)
                .targetHousehold())
        .isEqualTo(created);
    assertThat(world.ctx.session.pendingPopulationTransfers())
        .as("newTarget outbox 必须为 true 且 targetMode 为空")
        .anyMatch(
            transfer ->
                transfer.newTarget()
                    && transfer.target().equals(created)
                    && transfer.targetMode().isEmpty());
  }

  @Test
  void sourceStaysOwnerAndLandIsNotMoved() {
    World world = World.withLiveTarget();
    HouseholdFlowBook.settle(world.ctx, 5L);
    world.increaseDebtTo(200L);
    HouseholdFlowBook.settle(world.ctx, 5L);

    assertThat(OwnershipClassBook.classOf(world.ctx, SOURCE))
        .as("source 仍由 LAND 派生 OWNER（不随 flow 改 class）")
        .isEqualTo(OwnershipClass.OWNER);
    assertThat(
            world.ctx.session.sheet().landHoldingsOrBase().values().stream()
                .filter(h -> h.owner().equals(SOURCE))
                .mapToLong(LandHolding::quantityMilliMu)
                .sum())
        .as("LAND 不随人口走")
        .isEqualTo(1_000L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static final class World {
    final SettlementDayContext ctx;

    World(SettlementDayContext ctx) {
      this.ctx = ctx;
    }

    static World withLiveTarget() {
      return build(true, false, 100L, 100L);
    }

    static World withLiveTargetPopulation(long sourcePopulation) {
      return build(true, false, sourcePopulation, 100L);
    }

    static World withInitialDebt(long initialDebt) {
      return build(true, false, 100L, initialDebt);
    }

    static World withoutLiveTarget() {
      return build(false, true, 100L, 100L);
    }

    void increaseDebtTo(long principal) {
      DebtContract existing = ctx.debts.values().iterator().next();
      DebtContract increased = existing.withPrincipal(principal);
      ctx.debts.put(increased.id(), increased);
      ctx.session.sheet().debtContracts().put(increased.id(), increased);
    }

    private static World build(
        boolean liveTarget, boolean zeroShell, long sourcePopulation, long initialDebt) {
      Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
      rows.put(SOURCE, row(SOURCE, HEX, sourcePopulation, SocialClassId.LANDED_PEASANT));
      if (liveTarget) {
        rows.put(TARGET, row(TARGET, HEX, 50L, SocialClassId.LANDLESS_LABORER));
      }
      if (zeroShell) {
        rows.put(ZERO_SHELL, row(ZERO_SHELL, HEX, 0L, SocialClassId.LANDLESS_LABORER));
      }
      // 债务人合同必须有一个已存在的债权人户；放在另一 hex 且为 LANDLESS ⇒ 不参与本 hex 目标选择/不作为 source。
      rows.put(CREDITOR, row(CREDITOR, OTHER_HEX, 10L, SocialClassId.LANDLESS_LABORER));

      DebtContract debt = debt(initialDebt);
      Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
      debts.put(debt.id(), debt);

      EconomyData base =
          EconomyData.empty()
              .withClassStructures(DefaultProductionModes.classStructures())
              .withProductionRoles(DefaultProductionModes.classPositions())
              .withHouseholdEconomies(rows)
              .withDebtContracts(debts);
      EconomySession session =
          new EconomySession(
              base,
              ProductionData.empty(),
              new ProductionRuntimeEngine(ProductionData.empty()),
              new UnavailableAssetOccupancyLookup(),
              new UnavailableParticipantAttributeLookup(),
              null,
              CULTURE_LOOKUP);
      AccountSession accounts = AccountSession.empty();
      for (HouseholdId household : rows.keySet()) {
        accounts.registerHousehold(
            household,
            HEX,
            household.equals(SOURCE) ? Map.of(GRAIN, 1_000L) : Map.of(),
            household.equals(SOURCE) ? Map.of(SILVER, 1_000L) : Map.of(),
            Map.of(),
            Map.of());
      }
      SettlementDayContext ctx =
          new SettlementDayContext(
              session,
              5L,
              accounts,
              null,
              false,
              new ProductionLedger.Accumulator(5L),
              EconomyParallelism.singleThreaded(),
              null,
              Map.of(),
              Set.of(),
              null,
              null,
              null,
              null);
      ctx.dayView = new WorkingDayView(session.sheet());
      ctx.householdEconomies = session.sheet().householdEconomies();
      ctx.debts = session.sheet().debtContracts();
      ctx.householdGoods = accounts.householdGoods();
      ctx.assetShares = new LinkedHashMap<>();
      // ownershipClass 的唯一派生输入：source 持有正 LAND ⇒ OWNER；target/creditor 无生产资料 ⇒ PROLETARIAN。
      session
          .sheet()
          .landHoldings()
          .put(new LandHoldingKey(HEX, SOURCE), new LandHolding(SOURCE, HEX, 1_000L));
      return new World(ctx);
    }
  }

  private static DebtContract debt(long principal) {
    return new DebtContract(
        DebtContractId.idOf(
            SOURCE, CREDITOR, new DebtUnit.Money(SILVER), DebtTerms.legacyDefault()),
        SOURCE,
        CREDITOR,
        new DebtUnit.Money(SILVER),
        DebtTerms.legacyDefault(),
        DebtSource.ORDINARY_LOAN,
        principal,
        1L,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  private static HouseholdEconomy row(
      HouseholdId id, HexCoord hex, long population, SocialClassId stratum) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, stratum),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }
}
