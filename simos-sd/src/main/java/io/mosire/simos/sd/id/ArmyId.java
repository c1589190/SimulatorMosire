package io.mosire.simos.sd.id;

/**
 * 军队归属 ID（调用方给的短名，spec §二.1）。关联 {@code NationId} 与单位根 {@code UnitId}（编制本身仍在 unit，spec §十）。 裸值
 * {@code toString()} + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record ArmyId(String value) {

  public ArmyId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ArmyId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static ArmyId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ArmyId 不得为空白: " + text);
    }
    return new ArmyId(text);
  }
}
