package io.mosire.simos.economy.api.id;

/**
 * ★ <b>阶层结构（{@code ClassStructure}）的稳定身份</b>（理想架构 §2.2/§3.3）：一个生产方式下允许的阶层位置集合与默认人口份额由它承载， 本类型只承载身份。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * <b>规范串不含 {@code "."}</b>：地址切段理由同 {@link ProductionModeId}；含点即抛。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ClassStructureId(String value) {

  public ClassStructureId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClassStructureId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassStructureId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（新 id 的拼写点属命令层）。 */
  public static ClassStructureId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClassStructureId 不得为空白: " + text);
    }
    return new ClassStructureId(text);
  }
}
