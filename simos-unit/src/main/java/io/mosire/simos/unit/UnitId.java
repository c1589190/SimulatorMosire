package io.mosire.simos.unit;

/** 单位 ID：与 {@code RegionId}/{@code CityId} 同形（裸值 toString + static parse + 空白即抛）。 */
public record UnitId(String value) {

  public UnitId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("value 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层，M3 spec §8.6）。 */
  public static UnitId parse(String text) {
    return new UnitId(text);
  }
}
