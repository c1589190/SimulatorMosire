package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** spec §四：稳定身份的值语义；命名层级由各模块自定，Util 不校验。 */
class SubjectIdTest {

  @Test
  void valueSemantics() {
    assertThat(new SubjectId("map.hex", "h-0001")).isEqualTo(new SubjectId("map.hex", "h-0001"));
    assertThat(new SubjectId("map.hex", "h-0001"))
        .hasSameHashCodeAs(new SubjectId("map.hex", "h-0001"));
    assertThat(new SubjectId("map.hex", "h-0001").toString()).contains("map.hex", "h-0001");
  }

  @Test
  void blankPartsAreRejected() {
    assertThatThrownBy(() -> new SubjectId(" ", "h-0001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SubjectId("map.hex", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceHierarchyIsFreeForm() {
    // §四：`<模块>` 或 `<模块>.<类型>` 都由模块自定，Util 不校验层级
    assertThat(new SubjectId("unit", "U-1").namespace()).isEqualTo("unit");
    assertThat(new SubjectId("map.region", "R-1").namespace()).isEqualTo("map.region");
  }
}
