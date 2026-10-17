package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>执行期工资岗位的稳定复合键</b>（D-032 D3 §2.2）。
 *
 * <p>键 = {@code (unit, role)}。同一 {@code (unit, role)} 至多一条 {@link ProductionWagePosting}；
 * 它是执行期唯一实际报价权威，候选 default 不参与已存在实例的报价/结算。
 *
 * <p>★ 线格式采用长度前缀（{@code <unit 字符数>:<unit><role>}），不用 {@code |} 裸拼：{@link ProductionUnitId} 与 {@link
 * ProductionRoleId} 的合法值可以含 {@code |} / {@code :}，裸分隔符无法与 {@link #parse(String)} 严格互逆。 最后一段 role
 * 取剩余全部字符。
 *
 * @param unit 生产实例稳定身份；不得为 null
 * @param role 方案内工资角色稳定身份；不得为 null
 */
public record WagePostingKey(ProductionUnitId unit, ProductionRoleId role) {

  public WagePostingKey {
    Objects.requireNonNull(unit, "WagePostingKey.unit 不得为 null");
    Objects.requireNonNull(role, "WagePostingKey.role 不得为 null");
  }

  /** 规范串：{@code <unit.length()>:<unit><role>}（与 {@link #parse} 互为逆）。 */
  @Override
  public String toString() {
    return WageKeyWireFormat.encode(unit.value(), role.value());
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * @throws IllegalArgumentException 文本为空、长度前缀缺失/非整数/非正、长度越界或任一段为空
   */
  public static WagePostingKey parse(String text) {
    String[] parts = WageKeyWireFormat.decode("WagePostingKey", text, "unit", "role");
    return new WagePostingKey(ProductionUnitId.parse(parts[0]), ProductionRoleId.parse(parts[1]));
  }
}
