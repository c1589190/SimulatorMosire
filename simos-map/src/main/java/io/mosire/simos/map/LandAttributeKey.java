package io.mosire.simos.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 土地 component 的抽象属性键。内置 owner/progress/crop/fertility；其余键开放。
 *
 * <p>{@code owner} 只是不透明字符串（Map 不 import Social/Economy 领域类型）。
 *
 * @param wireValue 线格式（非空白）
 */
public record LandAttributeKey(String wireValue) {

  /** 所有权（不透明的家户/主体引用）。 */
  public static final LandAttributeKey OWNER = new LandAttributeKey("owner");

  /** 独立耕地进度（量纲由生产域后续定义）。 */
  public static final LandAttributeKey PROGRESS = new LandAttributeKey("progress");

  /** 作物/生产方案引用。 */
  public static final LandAttributeKey CROP = new LandAttributeKey("crop");

  /** 肥力等生产属性。 */
  public static final LandAttributeKey FERTILITY = new LandAttributeKey("fertility");

  public LandAttributeKey {
    if (wireValue == null || wireValue.isBlank()) {
      throw new IllegalArgumentException("LandAttributeKey.wireValue 不得为空白");
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return wireValue;
  }

  /** 线格式 → 属性键（开放词表；只拒绝空白）。 */
  @JsonCreator
  public static LandAttributeKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LandAttributeKey 不得为空白: " + text);
    }
    return new LandAttributeKey(text);
  }
}
