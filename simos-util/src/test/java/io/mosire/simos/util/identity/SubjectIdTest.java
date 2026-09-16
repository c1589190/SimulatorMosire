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
  void nullPartsAreRejected() {
    // 守卫把 null 与空白合并成同一条 IAE（"不得为空白"）：null 是"忘了写"，不是合法值。
    // 判据连字段名一起钉：哪一臂缺席，都能指名道姓地转红。
    assertThatThrownBy(() -> new SubjectId(null, "h-0001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    assertThatThrownBy(() -> new SubjectId("map.hex", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("localId");
  }

  @Test
  void namespaceHierarchyIsFreeForm() {
    // §四：`<模块>` 或 `<模块>.<类型>` 都由模块自定，Util 不校验层级
    assertThat(new SubjectId("unit", "U-1").namespace()).isEqualTo("unit");
    assertThat(new SubjectId("map.region", "R-1").namespace()).isEqualTo("map.region");
  }
}
