package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import org.junit.jupiter.api.Test;

/**
 * ★★ **阶层身份全局化**（S1 阶段 1，spec §2.6）：{@code ClassKey} 的第二段是**产业无关**的 {@link SocialClassId} —— 同一个
 * {@code poor_peasant} 出现在两个产业里，指的是**同一个社会阶层**。
 *
 * <p>★ 判别力：把 {@code ClassKey.slot} 的类型改回产业内的 {@code SocialClassId} ⇒ 本类**编译不过** ⇒ 红。
 */
class ClassKeyTest {

  /**
   * ★★ **本阶段要建立的不变量**：两个不同产业里的同名阶层，是**同一个** {@code SocialClassId}。
   *
   * <p>★ 判别力：若第二段仍是产业内的"槽位"，"同一个阶层"就只能靠**值相等**侥幸成立； 换成 {@code SocialClassId}
   * 后它变成**类型上成立**（且词表外的值根本构造不出来）。
   */
  @Test
  void theSameStratumInTwoIndustriesIsTheSameIdentity() {
    ClassKey farm = ClassKey.parse("farm@0_0|poor_peasant");
    ClassKey craft = ClassKey.parse("craft@0_0|poor_peasant");

    assertThat(farm.slot())
        .as("★★ 两个产业里的同一个阶层必须是同一个 SocialClassId（不是两个碰巧同值的槽位）")
        .isEqualTo(craft.slot())
        .isEqualTo(SocialClassId.POOR_PEASANT);
  }

  /** ★ **往返**：`parse(toString()) == 自身` —— `ClassKey` 是变更集的 key，这条挂了会静默丢状态。 */
  @Test
  void roundTripsThroughItsCanonicalString() {
    for (SocialClassId stratum : SocialClassId.all()) {
      ClassKey key = new ClassKey(new IndustryId("farm@0_0"), stratum);
      assertThat(ClassKey.parse(key.toString())).isEqualTo(key);
    }
  }

  /** ★ **词表外的阶层构造不出来**（fail-closed）—— 旧的 `SocialClassId` 允许任意文本，这正是换装要堵的口子。 */
  @Test
  void rejectsAStratumOutsideTheVocabulary() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ClassKey.parse("farm@0_0|noble"))
        .as("★★ 阶层词表外的值必须当场抛（静态接受会让写错阶层变成运行时幽灵）")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
