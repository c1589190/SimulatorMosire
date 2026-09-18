package io.mosire.simos.core.command;

/**
 * {@code AdvanceTime} 那一支的**目标**（裁定 32）：Task 9 定义它、构造期收它，但**不认识实现者**。
 *
 * <p>★ **为什么要有这个接口**：计划的 Task 9 分派表写着 {@code AdvanceTime → Core 的推进管线（Task 12）}， 而 Task 12 又在 Task
 * 10/11 的下游（它的 Commit 步要用乐观并发检查、Post-commit 要用事件类型）⇒ {@code 9 → 12} 与 {@code 12 → 10/11 → 9}
 * 构成闭环。**裁决是"注入，不重排，不留桩"**： 目标由注入给出，Task 9 的用例传替身。
 *
 * <p>★ **留桩为什么不行**：{@code UnsupportedOperationException} 是运行时地雷，且 Task 9 的门禁会**不覆盖**
 * 自己分派表的一整支。**不影响 C16**：{@code switch} 仍在 Core 自己的**封闭**命令集上，只是其中一支的目标由注入给出。
 *
 * <p>★ 实现者归 **Task 12**（{@code TimeAdvance}），装配归 **Task 13**——join 落在计划本来放装配的那一处。
 */
@FunctionalInterface
public interface AdvanceRoute {

  /** 执行一次时间推进。★ **{@code cmd} 必须原样使用**——不得改写任何字段（形态 4：纯转发型 SPI）。 */
  CommandResult run(AdvanceTime cmd);
}
