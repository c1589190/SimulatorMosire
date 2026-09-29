package io.mosire.simos.economy.api.id;

/**
 * ★ <b>生产方式（{@code ProductionMode}）的稳定身份</b>（理想架构 §2.1/§3.3）：一个生产方式由 id、名称、版本与它绑定的阶层结构定义， 本类型只承载身份。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * <b>规范串不含 {@code "."}</b>：地址 {@code economy:<mapId>:productionMode.<id>} 由 {@code AddressParser}
 * 在第一个点处切段， 含点会把名字截断成另一个名字 ⇒ 本类型在构造期当场抛，不静默造一个解析不到的地址。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ProductionModeId(String value) {

  public ProductionModeId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionModeId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ProductionModeId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（新 id 的拼写点属命令层）。 */
  public static ProductionModeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionModeId 不得为空白: " + text);
    }
    return new ProductionModeId(text);
  }
}
