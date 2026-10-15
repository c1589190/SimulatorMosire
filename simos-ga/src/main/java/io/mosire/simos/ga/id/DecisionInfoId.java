package io.mosire.simos.ga.id;

/**
 * sd 侧 INFO 条目（= 决策结果）的 ID（第 3 波第 1 步）。
 *
 * <p>★ **唯一性键 = 同类型（{@code DecisionInfoEntry}）内唯一**：不带它，{@code DecisionInfoEntry} 就只是"某地址下第几个出现"，
 * 无法被界面/AAR/裁决稳定引用。合成与去重的落点见 {@link io.mosire.simos.ga.model.DecisionInfoIds#synthesize} 与 {@code
 * io.mosire.simos.ga.spi.PutInfoHandler}（该 handler 自 2026-10-12 批次 3b-2 起住 {@code simos-ga}）。
 *
 * <p>★ **不自增、不用随机 UUID**（与既有 11 个 Id 类型同口径）：id 是 (canonical 地址, 追加序号) 或载荷给定的**纯函数**， 同一 base +
 * 同一载荷恒得同一 id ⇒ 可重放（铁律 2）。 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record DecisionInfoId(String value) {

  public DecisionInfoId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DecisionInfoId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static DecisionInfoId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DecisionInfoId 不得为空白: " + text);
    }
    return new DecisionInfoId(text);
  }
}
