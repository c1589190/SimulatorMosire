package io.mosire.simos.sd.spi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 决策携带命令的**白名单**（spec §四 / §三.5，D1）：由**代码**维护、注册期收全量 {@code commandTypes}（{@code Shell} 已有该集合）。
 *
 * <p>★ **两条硬规则**：
 *
 * <ol>
 *   <li>**禁 {@code sd} 自指**：{@code sd.*} 命令不得出现在 {@code DirectiveCommand} 里递归生成指令（spec §四
 *       明确禁，防无限自指）；
 *   <li>**禁通用写**：{@link SdCommandNames#SIMOS_COMMAND_SUBMIT} 不是领域命令，不得作为决策命令。
 * </ol>
 *
 * <p>★ **不是恒真/恒假的装饰**：白名单是构造期从**注册面**推导的集合——既拒绝未注册的类型，也拒绝自指与通用写； 与 {@code DirectiveCommand} 的
 * shapes 校验分工是"白名单判合法性、record 判形状"。
 */
public final class DirectiveWhitelist {

  private final Set<String> allowed;

  /**
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源）
   * @throws NullPointerException {@code commandTypes} 为 null
   * @throws IllegalArgumentException 集合含空白项
   */
  public DirectiveWhitelist(Set<String> commandTypes) {
    Objects.requireNonNull(commandTypes, "commandTypes");
    Set<String> allowed = new LinkedHashSet<>();
    for (String type : commandTypes) {
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("commandTypes 不得含空白: " + type);
      }
      if (type.startsWith("sd.")) {
        continue; // 禁自指（spec §四）
      }
      if (type.equals(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {
        continue; // 禁通用写（N9 同族）
      }
      allowed.add(type);
    }
    this.allowed = Collections.unmodifiableSet(allowed); // ★ 冻在赋值处
  }

  /** 该命令类型是否允许出现在决策里。 */
  public boolean allows(String type) {
    return type != null && allowed.contains(type);
  }

  /** 合法类型集合（只读，注册序）。 */
  public Set<String> allowedTypes() {
    return allowed;
  }

  /** 该类型是否为 sd 自指（供拒绝理由区分"自指"与"白名单外"）。 */
  public static boolean isSdSelfReference(String type) {
    return type != null && type.startsWith("sd.");
  }
}
