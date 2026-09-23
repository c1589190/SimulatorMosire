package io.mosire.simos.sd.id;

/**
 * sd 侧 INFO 条目（= 决策结果）的 ID（第 3 波第 1 步）。
 *
 * <p>★ **唯一性键 = 同类型（{@code SdInfoEntry}）内唯一**：不带它，{@code SdInfoEntry} 就只是"某地址下第几个出现"，
 * 无法被界面/AAR/裁决稳定引用。合成与去重的落点见 {@link io.mosire.simos.sd.model.SdInfoIds#synthesize} 与 {@code
 * PutInfoHandler}。
 *
 * <p>★ **不自增、不用随机 UUID**（与既有 11 个 Id 类型同口径）：id 是 (canonical 地址, 追加序号) 或载荷给定的**纯函数**， 同一 base +
 * 同一载荷恒得同一 id ⇒ 可重放（铁律 2）。 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record SdInfoId(String value) {

  public SdInfoId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("SdInfoId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static SdInfoId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("SdInfoId 不得为空白: " + text);
    }
    return new SdInfoId(text);
  }
}
