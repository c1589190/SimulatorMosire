package io.mosire.simos.economy.api.id;

/**
 * 生产制度 ID（新经济设计 §3.1/§5）：小农 / 封建租佃 / 手工业 / 资本主义工业四档生产制度的稳定身份。
 *
 * <p>制度决定"允许哪些阶层槽位"（§3.1 {@code Industry.regime}）与默认的分配函数参数（§5，参数版本化）。 它只是身份标签，**不含任何规则/公式**。裸值
 * {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record RegimeId(String value) {

  public RegimeId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("RegimeId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static RegimeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("RegimeId 不得为空白: " + text);
    }
    return new RegimeId(text);
  }
}
