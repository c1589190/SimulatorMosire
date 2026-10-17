package io.mosire.simos.production.contract;

/**
 * ★★ <b>角色产品份额合计不足 1000‰ 时的余数规则</b>（D-032 D1 §2.6）。
 *
 * <p>{@link #UNALLOCATED} 不是“留在无主池”：按 D2 §8.3，未进入角色池的部分最终全部归 organizer residual，并给具名未分配读数。{@link
 * #TO_LAST_ROLE} 则把余数并入最后一个角色。
 */
public enum RoleShareRemainder {
  UNALLOCATED("unallocated"),
  TO_LAST_ROLE("to_last_role");

  private final String key;

  RoleShareRemainder(String key) {
    this.key = key;
  }

  /** 线格式/载荷里的稳定键。 */
  public String key() {
    return key;
  }

  /** 解析稳定键（也接受枚举名，便于同版本 codec 读回）；未知 ⇒ {@code null}。 */
  public static RoleShareRemainder fromKey(String key) {
    if (key == null) {
      return null;
    }
    for (RoleShareRemainder value : values()) {
      if (value.key.equals(key) || value.name().equals(key)) {
        return value;
      }
    }
    return null;
  }
}
