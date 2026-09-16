package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** spec §四：候选 = 稳定 ID + canonical 地址 + 展示用类型名。 */
class ResolvedSubjectTest {

  @Test
  void carriesIdentityAddressAndType() {
    ResolvedSubject s = hexSubject("map:Map1:hex.4_3");
    assertThat(s.id()).isEqualTo(new SubjectId("map.hex", "h-0001"));
    assertThat(s.canonicalAddress()).isEqualTo("map:Map1:hex.4_3");
    assertThat(s.typeName()).isEqualTo("Hex");
  }

  @Test
  void valueSemantics() {
    assertThat(hexSubject("map:Map1:hex.4_3")).isEqualTo(hexSubject("map:Map1:hex.4_3"));
    // 空白地址的错误消息必须点名 canonicalAddress：`Address.parse` 对空串自己也会抛 IAE，
    // 本层校验的可见差别只在这条消息（字段级定位），故判据钉消息而不是只钉异常类型。
    assertThatThrownBy(() -> new ResolvedSubject(new SubjectId("map.hex", "h-0001"), " ", "Hex"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonicalAddress");
    assertThatThrownBy(
            () -> new ResolvedSubject(new SubjectId("map.hex", "h-0001"), "map:Map1", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullIdIsRejected() {
    // 候选没有身份即无意义：ID 缺失必须在构造期就被拒绝
    assertThatThrownBy(() -> new ResolvedSubject(null, "map:Map1:hex.4_3", "Hex"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonCanonicalAddressIsRejected() {
    // §四：调用方选定后一律回传 canonical。宽容写法不得成为 Resolver 的输出。
    assertThatThrownBy(
            () ->
                new ResolvedSubject(
                    new SubjectId("social.population", "p-1"),
                    "social:Map1.[4,3]:population",
                    "Population"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical");
  }

  private static ResolvedSubject hexSubject(String address) {
    return new ResolvedSubject(new SubjectId("map.hex", "h-0001"), address, "Hex");
  }
}
