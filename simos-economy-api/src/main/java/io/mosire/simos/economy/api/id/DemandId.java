package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>需求条目 ID</b>（R4-E2）：一条 GM/内生需求（{@code HouseholdDemand}）的稳定身份，归 {@code economy} 切片。
 *
 * <p>★ <b>opaque 值对象</b>：裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。{@code parse}
 * 不做格式约束 —— 旧档/外部工具写下的 id 原样读回，不因本类新增而重算。新 id 的拼写点在 {@code EconomyAddDemandHandler} （{@code
 * demand-<scope>-<target>-<commodity>-<kind>-<unit>-<sequence>}）。
 *
 * <p>★ <b>为什么 id 是显式组件而不是 "住址"</b>：需求会随优先级/有效期变化，位置与内容都不是身份（铁律 1）。
 */
public record DemandId(String value) {

  public DemandId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DemandId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（格式的权威在命令层的新 id 拼写点）。 */
  public static DemandId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DemandId 不得为空白: " + text);
    }
    return new DemandId(text);
  }
}
