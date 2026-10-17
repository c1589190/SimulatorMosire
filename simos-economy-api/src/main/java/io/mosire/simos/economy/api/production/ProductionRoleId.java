package io.mosire.simos.economy.api.production;

/**
 * ★★★ <b>生产方案内的角色稳定身份</b>（D-032 D1 §2.1；D2 §5.1 将落点修正到 economy-api）。
 *
 * <p>★★ <b>为什么住在 economy-api</b>：economy 的 {@code HouseholdLaborCommitment} 与未来的 {@code
 * ProductionRoster} 都必须直接携带同一个角色 id；而 {@code simos-economy-api} 不能依赖 {@code simos-production}（反向会形成
 * Maven 环）。因此这里只放稳定身份，角色定义仍归 {@code production.contract.ProductionRoleDefinition}。
 *
 * <p>★ <b>不是社会阶层</b>：{@link io.mosire.simos.economy.api.id.SocialClassId} 是人的社会经济分类；本类型是
 * “某个生产方案里的一个位置”（地主、中农、佃农、经营者……），两者不得互换。
 *
 * <p>★ <b>地址/配额 id 安全</b>：值不得含 {@code '.'}。地址解析器在第一个 {@code '.'} 处切段，若角色 id 带点， 未来把角色纳入地址或配额 id
 * 时会产生二义。该约束与 {@code OwnershipStake.idOf} 同款。
 *
 * <p>★ 铁律 1 三件套：非空白守卫 + 裸值 {@code toString()} + {@code static parse}。
 *
 * @param value 非空白、不含 {@code '.'} 的稳定角色键（如 {@code landlord}、{@code operator}）
 */
public record ProductionRoleId(String value) {

  public ProductionRoleId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionRoleId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ProductionRoleId 不得含 '.'（地址/配额 id 安全）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白或含 {@code '.'} 即抛（与构造器同口径）。 */
  public static ProductionRoleId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionRoleId 不得为空白: " + text);
    }
    return new ProductionRoleId(text);
  }
}
