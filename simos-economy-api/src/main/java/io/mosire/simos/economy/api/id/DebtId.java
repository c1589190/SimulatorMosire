package io.mosire.simos.economy.api.id;

/**
 * 债务 ID（新经济设计 §3.3）：阶层行之间"阶层 → 阶层"的**聚合债权**一条的稳定身份，归 {@code economy} 切片的债务表。
 *
 * <p>存量/流量分离（§3.3 末条）：债务只能由借入/赊购产生，**本金是存量**，不在 {@code FlowRow} 里冒充。 裸值 {@code toString()} + {@code
 * static parse} 三件套（铁律 1）。
 */
public record DebtId(String value) {

  public DebtId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DebtId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static DebtId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DebtId 不得为空白: " + text);
    }
    return new DebtId(text);
  }
}
