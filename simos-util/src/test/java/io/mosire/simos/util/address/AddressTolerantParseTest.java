package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** spec §3.5：宽容只限"引号可省/可冗余"与 `.[` 一处兼容写法；其余一律明确报错。 */
class AddressTolerantParseTest {

  @Test
  void strayDotBeforeIndexIsTolerated() {
    Address a = Address.parse("social:Map1.[4,3]:population");
    assertThat(a.segments())
        .containsExactly(
            new Namespace("social"),
            Entity.of("Map1"),
            new Index(List.of(4, 3)),
            new Property("population"));
    assertThat(a.canonical()).isEqualTo("social:Map1:[4,3]:population");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "map", // 段数 < 2
        "map:", // 空段
        ":Map1", // 空段（首段为空）
        "map::Map1", // 空段
        "map:M[a", // 名字未加引却含 `[`
        "map:a b", // 含空格却不是裸词、也未加引
        "map:[]", // 空 Index
        "map:[4,]", // 坐标不是整数
        "map:[4,x]", // 坐标不是整数
        "map:[4,3", // Index 未闭合
      })
  void malformedAddressesAreRejected(String text) {
    assertThatThrownBy(() -> Address.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }

  /** spec §3.5：引号未闭合必须由本层明确报错——不能靠下游兜住。本条从上面的列表移出，以便断言消息， 让"引号未闭合"这条护栏具备判别性。 */
  @Test
  void unclosedQuoteIsRejected() {
    assertThatThrownBy(() -> Address.parse("map:\"a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("引号未闭合");
  }

  /** spec §3.5：Index 坐标两侧空白可容忍，canonical 一律紧形式（`.strip()` 在此，不在渲染侧）。 */
  @Test
  void indexCoordinatesTolerateSurroundingWhitespace() {
    assertThat(Address.parse("map:Map1:[4, 3]").segments().get(2))
        .isEqualTo(new Index(List.of(4, 3)));
    assertThat(Address.parse("map:Map1:[4, 3]").canonical()).isEqualTo("map:Map1:[4,3]");
  }

  /** spec §3.5：非数字坐标抛 IAE（带段序号），**不得漏出** `NumberFormatException`。 */
  @Test
  void nonNumericIndexCoordinateIsRejected() {
    assertThatThrownBy(() -> Address.parse("map:Map1:[a]"))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(NumberFormatException.class)
        .hasMessageContaining("第 3 段")
        .hasMessageContaining("不是整数");
  }

  /** spec §3.5：兼容写法的 `.` 左侧为空（`.[4,3]`）必须抛 IAE，而不是漏出越界异常。 */
  @Test
  void tolerantDotWithEmptyLeftSideIsRejected() {
    assertThatThrownBy(() -> Address.parse("map:Map1:.[4,3]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
  }
}
