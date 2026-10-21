package io.mosire.simos.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 土地面积成分的稳定身份，形如 {@code arable@0_0:0}。它是身份，不是列表下标；删除/合并 component 时不回收序号。
 *
 * @param value 规范串（非空白）
 */
public record LandComponentId(String value) {

  public LandComponentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("LandComponentId.value 不得为空白");
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }

  /** 线格式 → component id（只校验非空白；完整形状由 {@link LandTypeArea} 按所属类型解析）。 */
  @JsonCreator
  public static LandComponentId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LandComponentId 不得为空白: " + text);
    }
    return new LandComponentId(text);
  }
}
