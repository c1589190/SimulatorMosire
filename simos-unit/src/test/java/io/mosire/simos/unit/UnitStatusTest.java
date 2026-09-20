package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link UnitStatus} 的三态定义（spec §三.2，E3）。T2 在此文件补速度因子逐值断言。 */
class UnitStatusTest {

  @Test
  void theThreeStatesAreExactlyMovingRestingEngaged() {
    assertThat(UnitStatus.values())
        .containsExactly(UnitStatus.MOVING, UnitStatus.RESTING, UnitStatus.ENGAGED);
  }

  @Test
  void factorsAreTheExactPerMilleValues() {
    assertThat(UnitStatus.MOVING.factorPerMille()).isEqualTo(1000);
    assertThat(UnitStatus.RESTING.factorPerMille()).isEqualTo(500);
    assertThat(UnitStatus.ENGAGED.factorPerMille()).isEqualTo(250);
  }

  @Test
  void theThreeFactorsAreDistinctAndRestingEngagedAreSlowerThanMoving() {
    assertThat(UnitStatus.RESTING.factorPerMille()).isLessThan(UnitStatus.MOVING.factorPerMille());
    assertThat(UnitStatus.ENGAGED.factorPerMille()).isLessThan(UnitStatus.MOVING.factorPerMille());
    assertThat(UnitStatus.RESTING.factorPerMille())
        .isNotEqualTo(UnitStatus.ENGAGED.factorPerMille());
  }
}
