package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P5 死亡按人口比例删债</b>：最小 {@link EconomyData}（一个家户行 + 一笔粮债 + 一条劳动配额）上调用真 {@link
 * EconomySettlement#applyPopulationChange} 的会话路径，逐值断言
 *
 * <pre>
 * newPrincipal = ⌊principal × survivors ÷ population⌋
 * forgiven     = principal − newPrincipal
 * </pre>
 *
 * <p>同时钉住：删债只动合同本金（不产生发行/还款记录、不搬库存）；无死亡时合同本金不动。
 *
 * <p>★ 夹具走与 {@code EconomyInvariantsTest} 同款的“已迁移 meta + 显式 unit/配额”最小形态，避免构造期旧档迁移 改写输入。
 */
class DeathDebtWriteOffTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ORGANIZATION, "farm@0_0");
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(FARM, ESTATE);
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final HouseholdId DEBTOR =
      HouseholdIds.ofSeed(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final HouseholdId CREDITOR =
      HouseholdIds.ofSeed(HEX, ResidenceKind.RURAL, SocialClassId.LANDLORD);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final DebtTerms TERMS = DebtTerms.legacyDefault();
  private static final DebtContractId DEBT =
      DebtContractId.idOf(DEBTOR, CREDITOR, DebtUnit.commodity(GRAIN), TERMS);
  private static final LaborAllocationId ALLOC = new LaborAllocationId("alloc-death");

  @Test
  void deathReducesPrincipalBySurvivorRatioAndRecordsTheWriteOff() {
    EconomyData base = minimalEconomy(100L, 1_000_000L);
    EconomySession session = new EconomySession(base);

    EconomySettlement.applyPopulationChangeInto(session, List.of(new LotChange(LOT, HEX, 0L, 1L)));

    assertThat(session.debtWriteOffsView())
        .as("删债累加器：1/100 人口死亡 ⇒ 本金减 1/100")
        .containsEntry(DEBTOR, 10_000L);

    EconomyData after = session.build();
    assertThat(after.classes().get(DEBTOR).population()).as("存活人口 99").isEqualTo(99L);
    assertThat(after.classes().get(DEBTOR).laborMilli())
        .as("劳动按存活比例缩：70,000 × 99/100 = 69,300")
        .isEqualTo(69_300L);
    assertThat(after.debtContracts().get(DEBT).principal())
        .as("⌊1,000,000 × 99 ÷ 100⌋ = 990,000")
        .isEqualTo(990_000L);
    assertThat(after.debtContracts().get(DEBT).status())
        .as("只减本金，未清空 ⇒ 状态仍是 NORMAL")
        .isEqualTo(DebtStatus.NORMAL);
    assertThat(DebtStock.totalPrincipal(after.debtContracts()))
        .as("DebtStock.totalPrincipal 与合同表逐值同步")
        .isEqualTo(990_000L);
    assertThat(after.moneyIssuances()).as("死亡删债不是发行/回笼 ⇒ 不产生货币发行审计记录").isEmpty();
  }

  @Test
  void deathFloorsPerContractAndFullDeathWritesOffTheWholePrincipal() {
    EconomyData base = minimalEconomy(100L, 999L);
    EconomyData afterOneDeath =
        EconomySettlement.applyPopulationChange(base, List.of(new LotChange(LOT, HEX, 0L, 1L)));

    assertThat(afterOneDeath.debtContracts().get(DEBT).principal())
        .as("⌊999 × 99 ÷ 100⌋ = 989（逐笔向下取整，不先合计再摊）")
        .isEqualTo(989L);

    EconomyData afterAllDeath =
        EconomySettlement.applyPopulationChange(base, List.of(new LotChange(LOT, HEX, 0L, 100L)));
    assertThat(afterAllDeath.classes().get(DEBTOR).population()).as("全户死亡").isZero();
    assertThat(afterAllDeath.classes().get(DEBTOR).laborMilli()).as("劳动同步归零").isZero();
    assertThat(afterAllDeath.debtContracts().get(DEBT).principal())
        .as("survivors=0 ⇒ 全额减免")
        .isZero();
    assertThat(afterAllDeath.debtContracts().get(DEBT).status())
        .as("本金归零 ⇒ 合同转 FORGIVEN")
        .isEqualTo(DebtStatus.FORGIVEN);
    assertThat(DebtStock.totalPrincipal(afterAllDeath.debtContracts())).isZero();
  }

  @Test
  void noDeathDoesNotTouchDebtPrincipal() {
    EconomyData base = minimalEconomy(100L, 1_000_000L);

    EconomyData withBirths =
        EconomySettlement.applyPopulationChange(base, List.of(new LotChange(LOT, HEX, 2L, 0L)));

    assertThat(withBirths.classes().get(DEBTOR).population()).as("出生不改债务").isEqualTo(102L);
    assertThat(withBirths.debtContracts().get(DEBT).principal())
        .as("无死亡 ⇒ 本金一字不动")
        .isEqualTo(1_000_000L);
    assertThat(DebtStock.totalPrincipal(withBirths.debtContracts())).isEqualTo(1_000_000L);

    EconomyData emptyPlan = EconomySettlement.applyPopulationChange(base, List.of());
    assertThat(emptyPlan).as("空计划直接返回同一实例（不制造等值新对象）").isSameAs(base);

    EconomyData zeroChange =
        EconomySettlement.applyPopulationChange(base, List.of(new LotChange(LOT, HEX, 0L, 0L)));
    assertThat(zeroChange).as("出生/死亡都为 0 的条目是 no-op，状态逐值不变").isEqualTo(base);
    assertThat(zeroChange.debtContracts().get(DEBT).principal()).isEqualTo(1_000_000L);
  }

  // ── 最小夹具 ─────────────────────────────────────────────────────────────────────────

  /** 一个 100 人家户（一笔粮债）+ 一个债主家户 + 一条喂给 ESTATE unit 的配额。 */
  private static EconomyData minimalEconomy(long population, long principal) {
    Industry industry =
        new Industry(
            FARM,
            "农业",
            new RegimeId("tenant"),
            120L,
            Map.of(AssetKind.LAND, 1000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            Map.of(),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 700)),
            new AllocationRule.Split(700, 300));
    ProductionProcess unit =
        new ProductionProcess(UNIT, FARM, ESTATE, FARM.value(), 0L, 0L, Map.of());
    HouseholdEconomy debtor =
        new HouseholdEconomy(
            DEBTOR,
            new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
            population,
            population * 700L,
            700,
            0L,
            Map.of(),
            Map.of(),
            0L);
    HouseholdEconomy creditor =
        new HouseholdEconomy(
            CREDITOR,
            new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.LANDLORD),
            10L,
            0L,
            0,
            0L,
            Map.of(),
            Map.of(),
            0L);
    DebtContract debt =
        new DebtContract(
            DEBT,
            DEBTOR,
            CREDITOR,
            DebtUnit.commodity(GRAIN),
            TERMS,
            principal,
            0L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL);
    LinkedHashMap<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(DEBTOR, debtor);
    rows.put(CREDITOR, creditor);

    return EconomyData.empty()
        .withMeta(
            Optional.of(
                new EconomyMeta(
                    "death-debt-write-off",
                    0L,
                    OptionalLong.empty(),
                    EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                    Optional.empty())))
        .withIndustries(Map.of(FARM, industry))
        .withProcesses(Map.of(UNIT, unit))
        .withHouseholdEconomies(rows)
        .withDebtContracts(Map.of(DEBT, debt))
        .withLaborCommitments(
            Map.of(
                ALLOC,
                new HouseholdLaborCommitment(
                    ALLOC, LOT, DEBTOR, ESTATE, UNIT.value(), 60_000L, 1L)));
  }
}
