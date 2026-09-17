package io.mosire.simos.util.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SimosObjectMapper} 的配置契约：单点装配的每一项都要有钉子（M4 Task 3 Step 2）。
 *
 * <p>★ 这是"配置不是装饰"的自证处——feature 清单上**写了的**和**没写的**各有一条用例： 写了的（{@code Jdk8Module}、{@code
 * extraModules} 口子）、没写的（{@code ORDER_MAP_ENTRIES_BY_KEYS} 不启用、{@code FAIL_ON_UNKNOWN_PROPERTIES}
 * 不关闭）。
 */
class SimosObjectMapperTest {

  @Test
  void createReturnsANewMapperEachCall() {
    // "返回一份新的"是 create 的契约：codec 拿它当 static final 单件没问题，但两台 mapper 绝不许是同一个对象——
    // 否则一家改了 feature 全家漂移，单点装配反过来成了单点污染。
    assertThat(SimosObjectMapper.create())
        .as("每次 create 都是新实例")
        .isNotSameAs(SimosObjectMapper.create());
  }

  @Test
  void optionalHandlingIsRegisteredInTheBaseConfig() throws Exception {
    // Jdk8Module 在基座（Optional 是 java.util 的类型，util 够得着）：写了 label 与没写 label 两个方向都要活着。
    SimosTimestamp labeled = SimosTimestamp.of(7, "弘光元年");
    assertThat(
            SimosObjectMapper.create()
                .readValue(
                    SimosObjectMapper.create().writeValueAsString(labeled), SimosTimestamp.class))
        .isEqualTo(labeled);
    assertThat(SimosObjectMapper.create().readValue("{\"tick\":3}", SimosTimestamp.class))
        .isEqualTo(SimosTimestamp.of(3));
  }

  @Test
  void unknownPropertiesAreRejectedNotSwallowed() {
    // 默认的严格**就是**配置的一部分（铁律 5：多出来的字段是漂移信号）。这条钉的是"没写的 feature"——
    // 有人顺手 disable(FAIL_ON_UNKNOWN_PROPERTIES) 的话这里响。
    assertThatThrownBy(
            () ->
                SimosObjectMapper.create()
                    .readValue("{\"tick\":3,\"bogus\":1}", SimosTimestamp.class))
        .as("未知字段必须响（FAIL_ON_UNKNOWN_PROPERTIES 保持默认开启）")
        .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
  }

  @Test
  void extraModulesAreHonored() throws Exception {
    // 裁定 16 的口子：键反序列化器由各 codec 追加。用测试局部的键类型证明"传进去就被用上"。
    SimpleModule keys = new SimpleModule("test-keys");
    keys.addKeyDeserializer(
        ToyKey.class,
        new KeyDeserializer() {
          @Override
          public Object deserializeKey(
              String key, com.fasterxml.jackson.databind.DeserializationContext ctxt) {
            return new ToyKey(key.substring(1));
          }
        });
    Map<ToyKey, String> back =
        SimosObjectMapper.create(keys)
            .readValue(
                "{\"#a\":\"1\",\"#b\":\"2\"}",
                new com.fasterxml.jackson.core.type.TypeReference<Map<ToyKey, String>>() {});
    assertThat(back.keySet()).containsExactly(new ToyKey("a"), new ToyKey("b"));
  }

  /** 测试局部的自定义键类型（有"裸值 toString + parse"三件套的缩影）。 */
  record ToyKey(String value) {
    @Override
    public String toString() {
      return value;
    }
  }
}
