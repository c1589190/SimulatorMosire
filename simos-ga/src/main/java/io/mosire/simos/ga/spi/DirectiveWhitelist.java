package io.mosire.simos.ga.spi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 决策携带命令的**白名单**（spec §四 / §三.5，D1）：由**代码**维护、注册期收全量 {@code commandTypes}（{@code Shell} 已有该集合）。
 *
 * <p>★ **一条硬规则（2026-10-12 批次 1「权限统一」之后）**：
 *
 * <ol>
 *   <li>**禁通用写**：{@link GaCommandNames#SIMOS_COMMAND_SUBMIT} 不是领域命令，不得作为决策命令。
 * </ol>
 *
 * <p>★★ **{@code sd.} 前缀规则已删**（用户 2026-10-12 裁定「一条不留」）：自指保护不再靠前缀，改由 <b>"决策族命令不打 {@code
 * io.mosire.simos.util.spi.DmUsableCommand}"</b> 承担 —— 本类的输入集现在就是 ga 规则层 ({@code
 * io.mosire.simos.ga.command.CommandPermissionRules}) 派生的**可嵌令面**，里面本就没有一条 {@code sd.*}（它们全是
 * GM-only 或干脆未声明）。★ 于是本类退化为"对**已派生面**的一道保序收口"：不再有第二处"哪些命令能被 DM 嵌进令"的拼写。 ★ 同名规则的删除必须与 {@code Shell}
 * 里那 5 条**无条件 add**（`sd.IssueDirective` 等）同时做 —— 不删那 5 条，前缀规则一撤， 它们就复活成"自指可嵌令"的后门。
 *
 * <p>★ {@link #isSdSelfReference} **保留**：它是**拒因措辞**用的纯谓词（{@code IssueDirectiveHandler} 与 {@code
 * simos.ga.adjudicateTick} 借它把"自指"与"白名单外"两种拒绝理由分开报），不是判据本身 —— 删了它，拒绝理由会退化成含糊的一句 "不在白名单里"。
 *
 * <p>★★ <b>2026-10-15 批 N ②：{@link #isSdSelfReference} 今天**不是**恒假的死码</b>（回代码逐处核过，如实记）—— 它判的输入
 * **不是**"已注册的命令类型集合"，而是**调用方给的载荷文本**：{@code commands[].type} 由 {@code
 * GaPayloads.optionalDirectiveCommands} 从 {@code ga.IssueDirective} 的载荷里读出、**只校验非空白**（{@link
 * io.mosire.simos.ga.model.DirectiveCommand} 的紧凑构造器），不要求已注册。⇒ 全仓**已注册类型**的前缀直方图 {@code sd = 0}
 * （economy31 / social15 / unit13 / ga7 / army7 / map5 / actor5 / gov3）**推不出**它恒假。★ 活路两段：(a) {@code
 * ga.IssueDirective} 里这条判在**白名单判之前** ⇒ 模型送 {@code sd.} 前缀的任意类型得到的是"自指"、不是"不在白名单"——现场真发生过（决策人 {@code
 * dm-osman} 第一次出令就用了 {@code sd.RegisterEffect}）；(b) 每条身份消息都在**教**这条规则（{@code
 * DecisionAgentRunner}：{@code commands} 里不得放 {@code sd.} 前缀的命令类型）。★ 且 <b>{@code
 * DecisionAgentRunnerTest} 有用例逐字断言那次拒绝的正文含"自指"</b>。⇒ **保留**：删了它，{@code sd.X} 的拒绝理由会退化成"不在白名单" ——
 * 那是**可观测的行为改动**（拒绝文案 = 业务拒绝的 INFO 留痕），且与身份消息的教法自相矛盾。
 *
 * <p>★ **不是恒真/恒假的装饰**：白名单是构造期从**调用方给的集合**推导的 —— 未注册的类型进不来（调用方只可能给它自己派生的面）、 通用写被拒；与 {@code
 * DirectiveCommand} 的 shapes 校验分工是"白名单判合法性、record 判形状"。
 */
public final class DirectiveWhitelist {

  private final Set<String> allowed;

  /**
   * @param commandTypes 允许出现在决策里的命令类型（与 {@code Shell} 注册的 handler 同源；2026-10-12 批次 1 起它就是 ga
   *     规则层派生的**可嵌令面**）
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
      if (type.equals(GaCommandNames.SIMOS_COMMAND_SUBMIT)) {
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
