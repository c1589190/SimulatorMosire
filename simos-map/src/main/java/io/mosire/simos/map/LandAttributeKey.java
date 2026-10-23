package io.mosire.simos.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 土地 component 的抽象属性键。内置 owner/progress/stage/cycle_progress/start_input_paid/crop/fertility；
 * 其余键开放。
 *
 * <p>{@code owner} 只是不透明字符串（Map 不 import Social/Economy 领域类型）。
 *
 * @param wireValue 线格式（非空白）
 */
public record LandAttributeKey(String wireValue) {

  /** 所有权（不透明的家户/主体引用；只能由土地转移口改写）。 */
  public static final LandAttributeKey OWNER = new LandAttributeKey("owner");

  /** 当前阶段已过天数（LONG，单位：天；只计当前阶段，不含此前阶段）。 */
  public static final LandAttributeKey PROGRESS = new LandAttributeKey("progress");

  /** 当前阶段 id（STRING，不透明；阶段词表由生产域解释）。 */
  public static final LandAttributeKey STAGE = new LandAttributeKey("stage");

  /** 整个生产周期已过天数（LONG，单位：天；与 {@link #PROGRESS} 分账，不得混用）。 */
  public static final LandAttributeKey CYCLE_PROGRESS = new LandAttributeKey("cycle_progress");

  /** 阶段开始投入是否已支付（BOOLEAN）。 */
  public static final LandAttributeKey START_INPUT_PAID = new LandAttributeKey("start_input_paid");

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
