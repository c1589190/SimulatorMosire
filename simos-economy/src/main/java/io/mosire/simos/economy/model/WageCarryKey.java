package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>逐户工资进位余数的稳定复合键</b>（D-032 D3 §2.4；{@code EconomyData} 第 35 个组件）。
 *
 * <p>键 = {@code (unit, role, worker)}。同一 worker 在不同 unit/role 的 carry 分开；不同 worker 不共享 carry。 值域
 * {@code 0..999}，表示上次 {@code (quote × labor + carry)} 除 1000 后的分子余数。
 *
 * <p>★ 规范串采用<b>长度前缀</b>（{@code <unit 字符数>:<unit><role 字符数>:<role><worker>}），而不是用 {@code |}
 * 裸拼：{@link HouseholdId} 只校验非空白，合法值可以含 {@code |}（如 {@code legacy-0_0|rural|poor_peasant}），裸分隔符无法与
 * {@link #parse(String)} 严格互逆。长度前缀使三段边界只由长度决定， 与各段 id 允许出现的字符无关。
 *
 * @param unit 生产实例稳定身份；不得为 null
 * @param role 方案内工资角色稳定身份；不得为 null
 * @param worker 领取该笔工资的 worker 家户；不得为 null
 */
public record WageCarryKey(ProductionUnitId unit, ProductionRoleId role, HouseholdId worker) {

  public WageCarryKey {
    Objects.requireNonNull(unit, "WageCarryKey.unit 不得为 null");
    Objects.requireNonNull(role, "WageCarryKey.role 不得为 null");
    Objects.requireNonNull(worker, "WageCarryKey.worker 不得为 null");
  }

  /**
   * 规范串：{@code <unit.length()>:<unit><role.length()>:<role><worker>}（与 {@link #parse} 互为逆）。
   *
   * <p>最后一段 worker 吃掉剩余全部字符；"角色长度"严格标出 worker 的起点，故含 {@code :} 或 {@code |} 的合法 id 不会被切错。
   */
  @Override
  public String toString() {
    String unitValue = unit.value();
    String roleValue = role.value();
    return unitValue.length()
        + ":"
        + unitValue
        + roleValue.length()
        + ":"
        + roleValue
        + worker.value();
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * @throws IllegalArgumentException 文本为空、长度前缀缺失/非整数/非正、长度越界或任一段为空
   */
  public static WageCarryKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("WageCarryKey 不得为空白: " + text);
    }
    int unitColon = text.indexOf(':');
    if (unitColon <= 0) {
      throw new IllegalArgumentException(
          "WageCarryKey 的规范串必须以 <unit 长度>:<unit><role 长度>:<role><worker> 开头: " + text);
    }
    int unitLength = lengthOf(text, 0, unitColon, "unit");
    int unitStart = unitColon + 1;
    if (unitLength > text.length() - unitStart) {
      throw new IllegalArgumentException("WageCarryKey 的 unit 长度前缀越界: " + text);
    }
    int roleLengthColon = text.indexOf(':', unitStart + unitLength);
    if (roleLengthColon <= unitStart + unitLength) {
      throw new IllegalArgumentException(
          "WageCarryKey 缺少 <role 长度>: 前缀（unit 长度=" + unitLength + "）: " + text);
    }
    int roleLength = lengthOf(text, unitStart + unitLength, roleLengthColon, "role");
    int roleStart = roleLengthColon + 1;
    if (roleLength > text.length() - roleStart) {
      throw new IllegalArgumentException("WageCarryKey 的 role 长度前缀越界: " + text);
    }
    int workerStart = roleStart + roleLength;
    if (workerStart >= text.length()) {
      throw new IllegalArgumentException("WageCarryKey 的 worker 段不得为空: " + text);
    }
    return new WageCarryKey(
        ProductionUnitId.parse(text.substring(unitStart, unitStart + unitLength)),
        ProductionRoleId.parse(text.substring(roleStart, workerStart)),
        HouseholdId.parse(text.substring(workerStart)));
  }

  /** 读取 {@code [from, colon)} 的十进制长度前缀；非整数、非正或带符号/空白均具名拒绝。 */
  private static int lengthOf(String text, int from, int colon, String field) {
    String prefix = text.substring(from, colon);
    for (int i = 0; i < prefix.length(); i++) {
      char c = prefix.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException("WageCarryKey 的 " + field + " 长度前缀不是十进制非负整数: " + text);
      }
    }
    if (prefix.length() > 1 && prefix.charAt(0) == '0') {
      throw new IllegalArgumentException("WageCarryKey 的 " + field + " 长度前缀不得有前导零: " + text);
    }
    int length;
    try {
      length = Integer.parseInt(prefix);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("WageCarryKey 的 " + field + " 长度前缀不是整数: " + text, ex);
    }
    if (length <= 0) {
      throw new IllegalArgumentException("WageCarryKey 的 " + field + " 长度前缀必须为正: " + text);
    }
    return length;
  }
}
