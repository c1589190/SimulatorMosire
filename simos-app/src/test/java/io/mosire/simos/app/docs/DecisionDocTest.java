package io.mosire.simos.app.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ **文档地址约定的唯一拼写点**（Docs，2026-09-23）：{@code sd:doc.<docId>} 的往返与**判不出来就不当文档**。
 *
 * <p>★ **为什么地址约定要单独测**：{@code sd.info()} 的键是**任何人写的任意 canonical 地址**（{@code sd.PutInfo} 是通用写口），
 * 而"这是不是一篇文档"的判据如果写成字符串前缀，就会把 {@code sd:docx.foo} 这类**假阳性**放进来、也会被 docId 里的 {@code '.'} 撕碎
 * （canonical 是段序列拼 {@code ':'}、段内是 {@code kind.name}）。故 {@link DecisionDoc#docIdOf} 用**解析出的
 * kind**判， 本用例把两个方向都钉住。
 */
class DecisionDocTest {

  @Test
  void aValidDocIdRoundTripsThroughItsCanonicalAddress() {
    String address = DecisionDoc.addressOf("shanghai-brief");
    assertThat(address).isEqualTo("sd:doc.shanghai-brief");
    assertThat(DecisionDoc.docIdOf(address)).contains("shanghai-brief");
  }

  @Test
  void docIdsThatWouldBreakTheAddressShapeAreRejectedAtWriteTime() {
    for (String bad : new String[] {"a.b", "a:b", "a b", "", " ", "\t"}) {
      assertThatThrownBy(() -> DecisionDoc.addressOf(bad))
          .as("★ docId %s 会撕碎 canonical 段形状 ⇒ 写入侧就拒（不允许它以『文档静默消失』的形式出现）", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> DecisionDoc.addressOf(null))
        .as("★ null 与空白同一条口径（拒绝理由一致，调用方不用分两种）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNonDocAddressIsNeverReadAsADoc() {
    for (String other :
        new String[] {
          "sd:adjudication.0",
          "sd:decision.dm1#0",
          "map:Map1",
          "map:Map1:region.r1",
          "unit:u-1",
          "sd:docx.foo",
        }) {
      assertThat(DecisionDoc.docIdOf(other))
          .as("★ %s 不是文档地址 ⇒ 判不出来就不当文档（fail-closed）", other)
          .isEmpty();
    }
  }

  @Test
  void aGarbageOrNullAddressIsNotADocRatherThanAnError() {
    assertThat(DecisionDoc.docIdOf("not an address")).isEmpty();
    assertThat(DecisionDoc.docIdOf("")).isEmpty();
    assertThat(DecisionDoc.docIdOf(null)).isEqualTo(Optional.empty());
  }
}
