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

  /**
   * 只校验非空白，不做格式约束（分配器属命令层，M3 spec §8.6）。
   *
   * <p>★ 接受仓内 canonical 地址形态 {@code unit:<裸值>}（读口/resolve 给决策人的常见形态）—— 去掉前缀后按裸值建 id；否则 {@code
   * unit:foo} 会被当成另一个单位而查无。
   */
  public static UnitId parse(String text) {
    if (text != null && text.startsWith("unit:")) {
      text = text.substring("unit:".length());
    }
    return new UnitId(text);
  }
}
