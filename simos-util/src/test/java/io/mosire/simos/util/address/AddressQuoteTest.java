package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §3.4：三条"按需加引"条件、`""` 转义，与段类型的构造期校验（§3.2）。 */
class AddressQuoteTest {

  @Test
  void bareWordNamesAreNotQuoted() {
    assertThat(Entity.of("hex", "4_3").canonical()).isEqualTo("hex.4_3");
    assertThat(Entity.of("region", "Nation.区域A").canonical()).isEqualTo("region.Nation.区域A");
  }

  @Test
  void namesWithStructuralCharactersAreQuoted() {
    assertThat(Entity.of("region", "河口:渡口").canonical()).isEqualTo("region.\"河口:渡口\"");
    assertThat(Entity.of("region", "[前线]").canonical()).isEqualTo("region.\"[前线]\"");
    assertThat(Entity.of("region", " 区域A ").canonical()).isEqualTo("region.\" 区域A \"");
    assertThat(Entity.of("region", "").canonical()).isEqualTo("region.\"\"");
  }

  @Test
  void quoteCharacterIsDoubled() {
    assertThat(Entity.of("region", "A\"B").canonical()).isEqualTo("region.\"A\"\"B\"");
  }

  @Test
  void kindlessDottedNameIsQuoted() {
    assertThat(Entity.of("高地人旅指挥部.1营指挥部").canonical()).isEqualTo("\"高地人旅指挥部.1营指挥部\"");
    assertThat(Entity.of("Map1").canonical()).isEqualTo("Map1");
  }

  @Test
  void kindMustBeBareWord() {
    assertThat(Entity.of("hex", "4_3").kind()).contains("hex");
    assertThat(Entity.of("Map1").kind()).isEmpty();
    assertThatThrownBy(() -> Entity.of("a.b", "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Entity.of("a b", "x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceIndexAndPropertyRenderCanonically() {
    assertThat(new Namespace("map").canonical()).isEqualTo("map");
    assertThat(new Index(List.of(4, 3)).canonical()).isEqualTo("[4,3]");
    assertThat(new Index(List.of(7)).canonical()).isEqualTo("[7]");
    assertThat(new Property("population_growth").canonical()).isEqualTo("population_growth");
    assertThatThrownBy(() -> new Namespace("a:b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Property("a b")).isInstanceOf(IllegalArgumentException.class);
  }
}
