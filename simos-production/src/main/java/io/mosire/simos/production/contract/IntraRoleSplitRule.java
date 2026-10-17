package io.mosire.simos.production.contract;

/**
 * ★★ <b>同一角色内多户的拆分规则</b>（D-032 D1 §2.3）。
 *
 * <p>本枚举只声明“规则种类”，不携带任何实际 {@code HouseholdId} 或权重表：实际参与户与 权重由 economy 按当刻事实传入，production
 * 的定义面不写活人家户身份（D-I9）。
 *
 * <ul>
 *   <li>{@link #BY_RECOGNIZED_LABOR}：按角色内各户被认可的劳动比例；
 *   <li>{@link #BY_RIGHTS}：按角色内各户权益量比例（数量由 economy 计算）；
 *   <li>{@link #EQUAL}：参与户均分；
 *   <li>{@link #CONFIGURED_WEIGHTS}：由 economy 传入的显式权重表。
 * </ul>
 *
 * <p>★ 具体分配算法归 C1 的 {@code RoleDistribution}；本类型只保存每角色的配置。
 */
public enum IntraRoleSplitRule {
  BY_RECOGNIZED_LABOR("by_recognized_labor"),
  BY_RIGHTS("by_rights"),
  EQUAL("equal"),
  CONFIGURED_WEIGHTS("configured_weights");

  private final String key;

  IntraRoleSplitRule(String key) {
    this.key = key;
  }

  /** 线格式/载荷里的稳定键。 */
  public String key() {
    return key;
  }

  /** 解析稳定键（也接受枚举名，便于同版本 codec 读回）；未知 ⇒ {@code null}。 */
  public static IntraRoleSplitRule fromKey(String key) {
    if (key == null) {
      return null;
    }
    for (IntraRoleSplitRule rule : values()) {
      if (rule.key.equals(key) || rule.name().equals(key)) {
        return rule;
      }
    }
    return null;
  }
}
