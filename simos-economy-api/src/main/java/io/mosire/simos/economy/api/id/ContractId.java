package io.mosire.simos.economy.api.id;

/**
 * 生产合同 ID（设计稿 §4.2/§5）：劳动合同、租佃合同的稳定身份，归 {@code production} 切片；合同改变下一周期的权利、 劳动承诺与索取队列。
 *
 * <p>裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record ContractId(String value) {

  public ContractId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ContractId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static ContractId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ContractId 不得为空白: " + text);
    }
    return new ContractId(text);
  }
}
