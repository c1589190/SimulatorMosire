package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link UrbanRural}：两个计数都不得为负；{@code total} 就是两者的和（读口按它发"该格的批次求和"）。 */
class UrbanRuralTest {

  @Test
  void totalIsTheSumOfBothHalves() {
    assertThat(new UrbanRural(1_000L, 350L).total()).isEqualTo(1_350L);
    assertThat(new UrbanRural(0L, 0L).total()).as("零人口的格（不是缺键：构造器照样收）").isZero();
  }

  @Test
  void rejectsNegativeCounts() {
    assertThatThrownBy(() -> new UrbanRural(-1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("urban 必须 ≥ 0");
    assertThatThrownBy(() -> new UrbanRural(0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rural 必须 ≥ 0");
  }
}
