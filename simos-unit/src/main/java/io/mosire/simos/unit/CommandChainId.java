package io.mosire.simos.unit;

/** 命令链 ID：与 {@link UnitId} 同形（裸值 {@code toString} + {@code static parse} + 空白即抛）。 */
public record CommandChainId(String value) {

  public CommandChainId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("value 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层，M3 spec §8.6）。 */
  public static CommandChainId parse(String text) {
    return new CommandChainId(text);
  }
}
