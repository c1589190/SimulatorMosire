package io.mosire.simos.util.identity;

import io.mosire.simos.util.address.Address;

/**
 * 解析结果的候选项：稳定 ID + canonical 地址 + 展示/路由用类型名（spec §四）。
 *
 * <p>地址必须是 canonical 形式——机器协议只用 canonical，宽容写法在解析入口就被归一掉了。
 */
public record ResolvedSubject(SubjectId id, String canonicalAddress, String typeName) {

  public ResolvedSubject {
    if (id == null) {
      throw new IllegalArgumentException("ResolvedSubject.id 不得为 null");
    }
    if (canonicalAddress == null || canonicalAddress.isBlank()) {
      throw new IllegalArgumentException("ResolvedSubject.canonicalAddress 不得为空白");
    }
    if (!Address.parse(canonicalAddress).canonical().equals(canonicalAddress)) {
      throw new IllegalArgumentException("canonicalAddress 必须是 canonical 形式：" + canonicalAddress);
    }
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("ResolvedSubject.typeName 不得为空白");
    }
  }
}
