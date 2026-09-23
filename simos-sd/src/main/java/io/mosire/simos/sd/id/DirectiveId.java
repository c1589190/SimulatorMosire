package io.mosire.simos.sd.id;

/**
 * 决策 ID（调用方给的短名，spec §二.1）。★ **本 ID 全局唯一**；而 R4 的键是 ({@code DecisionMakerId}, tick)——自 2026-09-23
 * 起是 **末位生效**：同一个键下**可以有多条**（重写 = 新的一版），但**至多一条生效**（旧的转 {@code SUPERSEDED}）。 裸值 {@code toString()}
 * + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record DirectiveId(String value) {

  public DirectiveId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DirectiveId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static DirectiveId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DirectiveId 不得为空白: " + text);
    }
    return new DirectiveId(text);
  }
}
