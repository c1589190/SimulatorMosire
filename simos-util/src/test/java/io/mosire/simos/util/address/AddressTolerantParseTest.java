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
        "map:\"a", // 引号未闭合
      })
  void malformedAddressesAreRejected(String text) {
    assertThatThrownBy(() -> Address.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }
}
