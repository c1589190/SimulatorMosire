package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W3（D-032 D3 §2.9/§2.9.1、§8.4 P22/P29/P29b）债务来源与工资债零息判据</b>。
 *
 * <ul>
 *   <li>工资债 = {@link DebtSource#WAGE_ARREARS} + {@link DebtTerms#wageArrearsDefault()}（0‰）；
 *   <li>普通贷款 = {@link DebtSource#ORDINARY_LOAN} + 各自原 terms（20‰ 例仍 20‰）；
 *   <li>同 id 不同 source ⇒ 具名 {@code debt-source-mismatch}，不得静默合并；
 *   <li>source 经 codec/snapshot 往返逐值保留；换本金不丢 source。
 * </ul>
 */
class W3DebtSourceContractTest {

  private static final HouseholdId DEBTOR = HouseholdId.parse("hh-w3-debt-debtor");
  private static final HouseholdId CREDITOR = HouseholdId.parse("hh-w3-debt-creditor");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final long DAY = 3L;
  private static final OptionalLong DUE_CYCLE = OptionalLong.of(4L);
  private static final EconomyCodec CODEC = new EconomyCodec();

  @Test
  void ordinaryLoanKeepsItsOwnTermsAndOrdinarySource() {
    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    DebtTerms loanTerms = DebtTerms.legacyDefault(20);

    DebtContract first =
        DebtContractBook.upsert(
            debts,
            DEBTOR,
            CREDITOR,
            new DebtUnit.Money(SILVER),
            loanTerms,
            DebtSource.ORDINARY_LOAN,
            100L,
            DAY,
            DUE_CYCLE);
    assertThat(first.source()).isEqualTo(DebtSource.ORDINARY_LOAN);
    assertThat(first.terms()).isEqualTo(loanTerms);
    assertThat(first.terms().interestRatePerMillePerCycle())
        .as("U23-a：普通贷款不得因工资债零息被一并改成 0‰")
        .isEqualTo(20);

    DebtContract second =
        DebtContractBook.upsert(
            debts,
            DEBTOR,
            CREDITOR,
            new DebtUnit.Money(SILVER),
            loanTerms,
            DebtSource.ORDINARY_LOAN,
            50L,
            DAY + 1L,
            OptionalLong.empty());
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.source()).as("累计本金不丢来源").isEqualTo(DebtSource.ORDINARY_LOAN);
    assertThat(second.terms()).as("累计本金不改条款").isEqualTo(loanTerms);
    assertThat(second.principal()).isEqualTo(150L);
  }

  @Test
  void wageArrearsUsesZeroInterestFactoryAndSurvivesPrincipalChanges() {
    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    DebtContract wage =
        DebtContractBook.upsert(
            debts,
            DEBTOR,
            CREDITOR,
            new DebtUnit.Money(SILVER),
            DebtTerms.wageArrearsDefault(),
            DebtSource.WAGE_ARREARS,
            91L,
            DAY,
            DUE_CYCLE);

    assertThat(DebtTerms.wageArrearsDefault().interestRatePerMillePerCycle())
        .as("U23-a 用户已定：工资债 0‰")
        .isZero();
    assertThat(wage.source()).isEqualTo(DebtSource.WAGE_ARREARS);
    assertThat(wage.terms()).isEqualTo(DebtTerms.wageArrearsDefault());
    assertThat(wage.withPrincipal(123L).source())
        .as("换本金/后续写口必须逐值保留 source")
        .isEqualTo(DebtSource.WAGE_ARREARS);
    assertThat(wage.withStatus(DebtStatus.DELINQUENT).source())
        .as("换状态必须逐值保留 source")
        .isEqualTo(DebtSource.WAGE_ARREARS);
  }

  @Test
  void sameContractIdWithDifferentSourceFailsClosed() {
    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    // 普通贷款也可以合法使用 0‰ 条款；因此“只看 terms”无法区分工资债 ⇒ source 必须参与判定。
    DebtTerms zeroRateTerms = DebtTerms.wageArrearsDefault();
    DebtContractBook.upsert(
        debts,
        DEBTOR,
        CREDITOR,
        new DebtUnit.Money(SILVER),
        zeroRateTerms,
        DebtSource.ORDINARY_LOAN,
        100L,
        DAY,
        DUE_CYCLE);

    assertThatThrownBy(
            () ->
                DebtContractBook.upsert(
                    debts,
                    DEBTOR,
                    CREDITOR,
                    new DebtUnit.Money(SILVER),
                    zeroRateTerms,
                    DebtSource.WAGE_ARREARS,
                    10L,
                    DAY,
                    DUE_CYCLE))
        .as("同四元组 id 不同 source ⇒ 不得静默并成一条")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("debt-source-mismatch");
    assertThat(debts).hasSize(1);
    assertThat(debts.values().iterator().next().source()).isEqualTo(DebtSource.ORDINARY_LOAN);
  }

  @Test
  void wageSourceWithLegacyTermsFailsClosed() {
    assertThatThrownBy(
            () ->
                new DebtContract(
                    DebtContractId.idOf(
                        DEBTOR, CREDITOR, new DebtUnit.Money(SILVER), DebtTerms.legacyDefault()),
                    DEBTOR,
                    CREDITOR,
                    new DebtUnit.Money(SILVER),
                    DebtTerms.legacyDefault(),
                    DebtSource.WAGE_ARREARS,
                    10L,
                    0L,
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    DebtStatus.NORMAL))
        .as("P22：工资债套 20‰ legacy terms 必须具名拒绝")
        .hasMessageContaining("wage-debt-terms-unsupported");
  }

  private static HouseholdEconomy row(HouseholdId household) {
    return new HouseholdEconomy(
        household,
        new CohortKey(
            HEX, ResidenceKind.RURAL, io.mosire.simos.economy.api.id.SocialClassId.POOR_PEASANT),
        1L,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  @Test
  void debtSourceSurvivesSnapshotCodecRoundTrip() {
    DebtContract wage =
        new DebtContract(
            DebtContractId.idOf(
                DEBTOR, CREDITOR, new DebtUnit.Money(SILVER), DebtTerms.wageArrearsDefault()),
            DEBTOR,
            CREDITOR,
            new DebtUnit.Money(SILVER),
            DebtTerms.wageArrearsDefault(),
            DebtSource.WAGE_ARREARS,
            91L,
            DAY,
            OptionalLong.empty(),
            DUE_CYCLE,
            DebtStatus.NORMAL);
    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    debts.put(wage.id(), wage);
    EconomyData data =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "w3-debt-source-codec",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                        Optional.empty())))
            .withHouseholdEconomies(Map.of(DEBTOR, row(DEBTOR), CREDITOR, row(CREDITOR)))
            .withDebtContracts(debts);
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    EconomySnapshot snapshot = new EconomySnapshot(ref, SimosTimestamp.of(7L), data);

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).as("往返逐字段相等").isEqualTo(snapshot);
    DebtContract decoded = back.data().debtContracts().get(wage.id());
    assertThat(decoded).as("工资债合同必须读回").isNotNull();
    assertThat(decoded.source())
        .as("P29：source 进 codec/snapshot 往返并逐值保留")
        .isEqualTo(DebtSource.WAGE_ARREARS);
    assertThat(decoded.terms()).isEqualTo(DebtTerms.wageArrearsDefault());
  }
}
