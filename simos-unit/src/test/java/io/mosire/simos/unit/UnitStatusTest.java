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
}
