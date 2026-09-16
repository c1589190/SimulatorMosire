package io.mosire.simos.util.address;

/** 命名空间段（`map` / `social` / `unit` / `agent`）：地址的第 1 段，必须是裸词（spec §3.2）。 */
public record Namespace(String ident) implements AddressSegment {

  public Namespace {
    if (!AddressText.isBareWord(ident)) {
      throw new IllegalArgumentException("命名空间必须是裸词（非空、无空白、不含 : . [ ] \"）：" + ident);
    }
  }

  @Override
  public String canonical() {
    return ident;
  }
}
