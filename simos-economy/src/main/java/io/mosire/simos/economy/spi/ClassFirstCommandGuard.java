package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Objects;
import java.util.Optional;

/**
 * class-first 世界对"旧表命令"的共享具名拒绝口（class-first GM 工具阶段 3）。
 *
 * <p>★★ <b>规则</b>：{@link EconomyData#classFirst()} <b>非空</b>（class-first 世界）⇒ 接入本守卫的 7 条旧表命令
 * <b>具名拒绝</b> —— class-first 结算只读 {@code ClassFirstState} + 当日输入，这些命令写的旧表在 class-first 世界里没有结算路径；
 * {@code classFirst} <b>为空</b>（旧档 / 尚未播种）⇒ 返回 {@link Optional#empty()}，调用方行为<b>逐字不变</b>。
 *
 * <p>★★ <b>调用点</b>：handler 在 {@code EconomySnapshots.of(state).data()} 之后、本命令自己的校验之前调用。拒绝路径 只构造一条
 * {@link HandlerOutcome.Rejected}，不解析载荷、不产生任何状态变更。
 *
 * <p>★★ <b>固定句式（命令类型与理由的唯一拼写点）</b>：{@code <命令类型> 在 class-first 世界不可用：<guidance>}。 {@code guidance}
 * 由调用方给：点名 class-first 结算<b>不读</b>哪张表/哪份状态、真值在哪，以及可行指路；命令类型由 调用方传入本命令的同一常量（{@code type()}
 * 也返回它），避免拒绝文案与注册类型各写一遍。
 *
 * <p>★ <b>边界</b>：本类只做"非空即拒"这一件事，不实现任何 class-first 等价命令，也不改动旧表/旧结算；旧档行为保持原样。
 */
final class ClassFirstCommandGuard {

  private ClassFirstCommandGuard() {}

  /**
   * class-first 世界（{@code classFirst} 非空）⇒ 返回具名 {@link HandlerOutcome.Rejected}；旧档/未播种 ⇒ 返回空。
   *
   * @param commandType 命令类型（如 {@code economy.SetMarketPrice}），进拒绝文案前缀
   * @param base 当前经济状态；只读 {@link EconomyData#classFirst()}
   * @param guidance 理由主体：不读哪张表/哪份状态 + 真值在哪 + 指路
   * @return 非空 class-first 时是拒绝；否则 {@link Optional#empty()}（调用方照旧执行）
   */
  static Optional<HandlerOutcome> rejectIfClassFirst(
      String commandType, EconomyData base, String guidance) {
    Objects.requireNonNull(commandType, "commandType");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(guidance, "guidance");
    if (base.classFirst().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new HandlerOutcome.Rejected(commandType + " 在 class-first 世界不可用：" + guidance));
  }
}
