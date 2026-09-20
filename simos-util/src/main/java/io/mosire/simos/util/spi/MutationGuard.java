package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * 写前跨模块策略（spec §九，A6）：**只读、只回答"可不可以"**——形制照 {@link AgentAttachPolicy}。
 *
 * <p>★ **为什么是它而不是 Validator/interceptor**：全仓 main 源码**没有**任何写时语义校验 SPI（research §C②） ⇒ 必须新造。放在
 * {@code util.spi} 让 **Core 仍看不见领域类型**（铁律 4）：Core 只按 {@code commandType} 字符串与不透明的 {@code
 * payloadJson} 把问题转交给已注册的 guard，对"Region / Nation 是什么"一无所知。
 *
 * <p>★ **调用点**：{@code CommandBus} 的信封支、{@code handler.handle} **之前**。任一 guard 返回非空 ⇒ 照 {@code
 * HandlerOutcome.Rejected} 落 {@code received + rejected}、**不留 revision**。
 *
 * <p>★ **不得写状态**（纯函数）：它只读 {@code state}，返回拒绝理由或空。
 */
public interface MutationGuard {

  /** 本守卫的名（进日志/诊断；同一实例可复数注册时用于区分）。 */
  String name();

  /**
   * 这条命令是否应被拒。
   *
   * @param state 当前状态（只读；guard 可读任意模块切片）
   * @param commandType 信封上的 {@code type}（如 {@code "map.DeleteRegion"}）
   * @param payloadJson 不透明载荷文本（guard 自己解析自己关心的那部分）
   * @return 拒绝理由；放行为 {@link Optional#empty()}
   */
  Optional<String> rejection(SimulationState state, String commandType, String payloadJson);
}
