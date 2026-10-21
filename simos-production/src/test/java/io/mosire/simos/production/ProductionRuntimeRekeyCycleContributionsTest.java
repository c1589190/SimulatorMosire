package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.contract.CycleContribution;
import io.mosire.simos.production.contract.ProductionInstanceOutcome;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC4-B：合户时 production 周期贡献随源户重键到 survivor</b>。
 *
 * <p>判据：同一 role 的贡献精确相加；不同 role 不串；无 source 项幂等；不存在 unit/from==to 具名拒绝；operator/进度/投入不变。
 */
class ProductionRuntimeRekeyCycleContributionsTest {

  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-rekey");
  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "hh-survivor");
  private static final HouseholdId SOURCE = HouseholdId.parse("hh-source");
  private static final HouseholdId SURVIVOR = HouseholdId.parse("hh-survivor");
  private static final ProductionRoleId ROLE_A = ProductionRoleId.parse("operator");
  private static final ProductionRoleId ROLE_B = ProductionRoleId.parse("hired_laborer");

  @Test
  void sameRoleContributionsAddAndDifferentRolesStaySeparate() {
    ProductionProcess instance =
        instance(
            Map.of(
                new ProductionParticipantKey(SOURCE, ROLE_A),
                    contribution(SOURCE, ROLE_A, 10L, 20L),
                new ProductionParticipantKey(SURVIVOR, ROLE_A),
                    contribution(SURVIVOR, ROLE_A, 3L, 4L),
                new ProductionParticipantKey(SOURCE, ROLE_B),
                    contribution(SOURCE, ROLE_B, 7L, 9L)));
    ProductionRuntime runtime =
        new ProductionRuntimeEngine(ProductionData.empty().withInstance(instance));

    ProductionInstanceOutcome outcome = runtime.rekeyCycleContributions(UNIT, SOURCE, SURVIVOR);
    assertThat(outcome)
        .as("rekey 必须成功: %s", outcome)
        .isInstanceOf(ProductionInstanceOutcome.Written.class);

    ProductionProcess rekeyed = runtime.units().get(UNIT);
    assertThat(rekeyed.progressDays()).as("进度不得变").isEqualTo(instance.progressDays());
    assertThat(rekeyed.cycleInputUsedMilli()).as("投入不得变").isEqualTo(instance.cycleInputUsedMilli());
    assertThat(rekeyed.operator()).as("operator 不归本方法改").isEqualTo(instance.operator());
    assertThat(rekeyed.cycleContributions())
        .containsOnlyKeys(
            new ProductionParticipantKey(SURVIVOR, ROLE_A),
            new ProductionParticipantKey(SURVIVOR, ROLE_B));
    assertThat(rekeyed.cycleContributions().get(new ProductionParticipantKey(SURVIVOR, ROLE_A)))
        .as("同 role 精确相加：10+3 / 20+4")
        .isEqualTo(contribution(SURVIVOR, ROLE_A, 13L, 24L));
    assertThat(rekeyed.cycleContributions().get(new ProductionParticipantKey(SURVIVOR, ROLE_B)))
        .as("不同 role 不相互合并")
        .isEqualTo(contribution(SURVIVOR, ROLE_B, 7L, 9L));
    assertThat(rekeyed.cycleContributions().keySet())
        .allSatisfy(key -> assertThat(key.household()).isEqualTo(SURVIVOR));
  }

  @Test
  void noSourceContributionIsAnIdempotentNoOp() {
    ProductionProcess instance = instance(Map.of());
    ProductionRuntime runtime =
        new ProductionRuntimeEngine(ProductionData.empty().withInstance(instance));
    ProductionInstanceOutcome outcome = runtime.rekeyCycleContributions(UNIT, SOURCE, SURVIVOR);
    assertThat(outcome).isInstanceOf(ProductionInstanceOutcome.Written.class);
    assertThat(runtime.units().get(UNIT)).isEqualTo(instance);
  }

  @Test
  void unknownUnitAndSameHouseholdAreRejectedByName() {
    ProductionRuntime runtime =
        new ProductionRuntimeEngine(ProductionData.empty().withInstance(instance(Map.of())));
    assertThat(
            runtime.rekeyCycleContributions(new ProductionUnitId("unit-missing"), SOURCE, SURVIVOR))
        .as("不存在 unit 必须具名拒绝")
        .isInstanceOf(ProductionInstanceOutcome.Rejected.class);
    assertThat(runtime.rekeyCycleContributions(UNIT, SOURCE, SOURCE))
        .as("from==to 必须具名拒绝")
        .isInstanceOf(ProductionInstanceOutcome.Rejected.class);
  }

  private static ProductionProcess instance(
      Map<ProductionParticipantKey, CycleContribution> contributions) {
    return new ProductionProcess(
        UNIT,
        FARM,
        ProductionLocation.parse("0_0"),
        OPERATOR,
        "mode-rekey",
        5L,
        new LinkedHashMap<>(contributions),
        Map.of());
  }

  private static CycleContribution contribution(
      HouseholdId household, ProductionRoleId role, long labor, long weighted) {
    return new CycleContribution(household, role, labor, weighted);
  }
}
