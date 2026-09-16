package io.mosire.simos.util.address;

/** 属性段：`population` / `height` / `member`——地址第 3 段起的裸词（spec §3.2）。 */
public record Property(String ident) implements AddressSegment {

  public Property {
    if (!AddressText.isBareWord(ident)) {
      throw new IllegalArgumentException("属性名必须是裸词（非空、无空白、不含 : . [ ] \"）：" + ident);
    }
  }

  @Override
  public String canonical() {
    return ident;
  }
}
