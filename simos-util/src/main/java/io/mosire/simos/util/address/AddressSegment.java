package io.mosire.simos.util.address;

/** 地址的一段。段间用 `:` 分隔，段内用 `.` 分隔 kind 与 name（spec §3.2）。 */
public sealed interface AddressSegment permits Namespace, Entity, Index, Property {

  /** 本段的规范写法（canonical，按需加引，spec §3.4）。 */
  String canonical();
}
