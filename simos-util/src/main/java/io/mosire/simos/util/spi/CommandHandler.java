package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;

/**
 * 领域命令接上总线（spec §4.3 / ADR-1 C9）。
 *
 * <p>★ **{@code payloadJson} 必须原样使用**：Core 保证把它逐字节转交（R11），实现者不得假定它被规范化过。
 *
 * <p>★ **实现者自己反序列化**自己的载荷——Core 从不理解它的结构（C26）。载荷的 JSON 形态是**模块自己的事**。
 */
public interface CommandHandler {

  /** 信封上的 {@code type}，形如 {@code "unit.RenameUnit"}。 */
  String type();

  /** 纯函数：读 {@code state}、吐结果，**不写状态**。 */
  HandlerOutcome handle(SimulationState state, String payloadJson);
}
