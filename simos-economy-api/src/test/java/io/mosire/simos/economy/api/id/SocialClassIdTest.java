package io.mosire.simos.economy.api.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ★★ **社会阶层是全局身份**（S1 spec §2.6）：它不是"某个产业允许的槽位"，而是**产业无关**的人口身份 —— 同一个 {@code poor_peasant}
 * 出现在农业行与手工业行里，指的是**同一个社会阶层**。
 *
 * <p>★ 判别力：把 {@link SocialClassId#parse} 改成静默接受任意文本 ⇒ 第 2 条红。
 */
class SocialClassIdTest {

  @Test
  void knowsExactlyTheSevenStrata() {
    assertThat(SocialClassId.all())
        .as("★ 词表就是这七个（保序）—— 多一个或少一个都必须让本用例红")
        .containsExactly(
            SocialClassId.POOR_PEASANT,
            SocialClassId.MIDDLE_PEASANT,
            SocialClassId.RICH_PEASANT,
            SocialClassId.LANDLORD,
            SocialClassId.LANDLESS_LABORER,
            SocialClassId.ARTISAN,
            SocialClassId.OFFICIAL);
  }

  /** ★ **词表外即抛**（fail-closed）：静默接受会让"写错阶层"变成运行时幽灵。 */
  @Test
  void rejectsAnythingOutsideTheVocabulary() {
    assertThatThrownBy(() -> SocialClassId.parse("noble"))
        .as("★★ 词表外的阶层必须当场抛（且消息里列出合法值）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noble");
    assertThatThrownBy(() -> SocialClassId.parse(" "))
        .as("空白同样抛")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 往返：`parse(toString()) == 自身`（铁律 1 的"裸值 + parse"三件套）。 */
  @Test
  void roundTripsThroughText() {
    for (SocialClassId id : SocialClassId.all()) {
      assertThat(SocialClassId.parse(id.toString())).isEqualTo(id);
    }
  }
}
