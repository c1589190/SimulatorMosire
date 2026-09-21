package io.mosire.simos.sd.adjudication;

/**
 * 裁决器 SPI（spec §八.5，N10）：**边界内聚、执行上浮**。
 *
 * <p>★ 本接口定义在 {@code simos-sd}（模块内）；app 侧实现"调 LLM 或给人"——**key / 重试 / 超时**留在 app 的 {@link LlmClient}
 * 实现里。测试注入确定性实现即可**完全离线**断言（N14）。
 */
public interface DecisionAdjudicator {

  /** 裁决器名（留痕用；如 {@code "llm"} / {@code "human"}）。 */
  String name();

  /** 纯函数式调用：给请求、得三态结果；**不写状态**（落点由命令承担）。 */
  Judgement adjudicate(AdjudicationRequest request);
}
