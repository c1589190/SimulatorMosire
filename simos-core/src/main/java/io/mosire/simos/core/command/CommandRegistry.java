package io.mosire.simos.core.command;

import io.mosire.simos.util.spi.CommandHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code type → CommandHandler} 的表（spec §4.3）：**按 type 找 handler 的唯一一处**（C16 的分派落点）。
 *
 * <p>★ **构造期收全量、不可变**：本类**不提供可变的 {@code register()}**。计划 Produces 行写的是 {@code
 * register(CommandHandler)}，但 spec §4.3 要求"同 type 注册两次 ⇒ **构造期**抛"——一个可变注册表里
 * **不存在"构造期"这个时刻**，两条写法自相矛盾。取 spec 的语义（早响），故注册发生在构造：装配方（Task 13） 收集完全部模块的 handler 再建表。副作用是 {@code
 * CommandBus} 手里那张表**天生线程安全**，Task 10 加锁时不必管它。
 *
 * <p>★ **构造期校验两件事**（都是装配错误，早响比晚响好，且不许在每次提交里重复校验）：
 *
 * <ol>
 *   <li>{@code type()} 不得重复——**静默覆盖会让一个模块的 handler 永远不生效**（而且不报错）；
 *   <li>{@code type()} 必须形如 {@code <namespace>.<Command>}——Core 靠这个前缀把 handler 返回的变更集装进 {@code
 *       WorldChangeSet}（裁定 37）。不含 {@code '.'}、或以 {@code '.'} 开头/结尾都不合法。
 * </ol>
 */
public final class CommandRegistry {

  private final Map<String, CommandHandler> byType;

  /**
   * @param handlers 全部 handler；**迭代序即传入序**，重复 type 或 type 形状非法 ⇒ 当场抛
   * @throws NullPointerException {@code handlers} 或其中任一 handler 为 null
   * @throws IllegalArgumentException type 重复，或 type 不是 {@code <namespace>.<Command>} 形状
   */
  public CommandRegistry(Collection<CommandHandler> handlers) {
    Objects.requireNonNull(handlers, "handlers");
    // ★ 绝不用 Map.copyOf：它的迭代序是散列槽位序、不是内容的纯函数（M2 Task 5 实测 30 次）
    Map<String, CommandHandler> table = new LinkedHashMap<>();
    for (CommandHandler handler : handlers) {
      Objects.requireNonNull(handler, "handlers 里有 null");
      String type = Objects.requireNonNull(handler.type(), "handler.type()");
      requireNamespacedType(type, handler);
      CommandHandler previous = table.putIfAbsent(type, handler);
      if (previous != null) {
        throw new IllegalArgumentException(
            "同一 type 注册了两次（静默覆盖会让一个模块的 handler 永远不生效）: "
                + type
                + " —— 已在册: "
                + previous.getClass().getName()
                + "，新来的: "
                + handler.getClass().getName());
      }
    }
    this.byType = Collections.unmodifiableMap(table);
  }

  /** 按 type 找 handler（C16 的唯一一处）。未注册 ⇒ 空——**由调用方决定怎么拒绝**，本类不替它抛。 */
  public Optional<CommandHandler> byType(String type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(byType.get(type));
  }

  /** 在册的全部 type（装配自检与用例用）。 */
  public java.util.Set<String> types() {
    return byType.keySet();
  }

  /**
   * {@code <namespace>.<Command>} 的形状校验（裁定 37）。
   *
   * <p>★ **这不违反 C16/C26**：Core 没有 {@code instanceof} 任何模块类型、没有 parse 载荷， 只用了一个**由 Core
   * 自己规定的字符串形状**——{@code type} 是 Core 拥有的字段。
   */
  private static void requireNamespacedType(String type, CommandHandler handler) {
    int dot = type.indexOf('.');
    if (dot <= 0 || dot == type.length() - 1) {
      throw new IllegalArgumentException(
          "handler 的 type() 必须形如 <namespace>.<Command>（裁定 37：Core 靠前缀把变更集装进 WorldChangeSet）: "
              + type
              + " —— 来自 "
              + handler.getClass().getName());
    }
  }
}
