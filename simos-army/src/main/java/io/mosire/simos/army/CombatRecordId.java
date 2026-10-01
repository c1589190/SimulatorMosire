package io.mosire.simos.army;

/**
 * 交战记录 id（阶段 D1 / 用户设计 D-012，2026-10-02）：**调用方给的短名**，同一 army 切片内唯一。 裸值 {@code toString()} + {@code
 * static parse} 三件套（铁律 1：id 是身份、地址是定位方式）；不自增、不用随机 UUID——交战记录是不可变历史，id 由调用方冻结。
 *
 * <p>★ 只校验非空白：词表/前缀不是本类型的职责（与 {@code sd.CombatId}/{@code UnitId} 同口径）。地址形态是 {@code
 * army:combat.<id>}，其中 {@code <id>} 就是 {@link #value()}。
 */
public record CombatRecordId(String value) {

  public CombatRecordId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CombatRecordId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static CombatRecordId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CombatRecordId 不得为空白: " + text);
    }
    return new CombatRecordId(text);
  }
}
