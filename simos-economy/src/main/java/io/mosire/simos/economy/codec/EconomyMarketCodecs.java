package io.mosire.simos.economy.codec;

import io.mosire.simos.economy.api.market.MarketOrderKind;

/** 市场枚举键的线格式 helper（P0.5-C2；注册顺序仍由 {@link EconomyCoreCodecs#keyModule()} 唯一决定）。 */
final class EconomyMarketCodecs {

  /**
   * ★★ 写侧：枚举键按规范字面量（{@code lending}）而不是 {@code name()}（{@code LENDING}）落线格式。
   *
   * <p>★ 与 {@code GovCodec} 的旧同款实现逐字同制（政策搬进 economy 时一并搬来，不留第二份拼写点）。
   */
  static com.fasterxml.jackson.databind.JsonSerializer<MarketOrderKind> orderKindKeySerializer() {
    return new com.fasterxml.jackson.databind.JsonSerializer<>() {
      @Override
      public void serialize(
          MarketOrderKind value,
          com.fasterxml.jackson.core.JsonGenerator generator,
          com.fasterxml.jackson.databind.SerializerProvider serializers)
          throws java.io.IOException {
        generator.writeFieldName(value.value());
      }
    };
  }
}
