package io.mosire.simos.sd.adjudication;

/**
 * LLM 客户端接口（spec §八.5，N10）：**app 实现，测试注入 Fake**。
 *
 * <p>★ key / 网络 / 重试 / 超时**都在实现里**（本接口只有一次同步调用）——实现抛出的任何异常都会被 {@link LlmDecisionAdjudicator} 折成
 * {@link Judgement.Failed}，从而满足 N13（不卡死本 tick）。
 *
 * <p>★ 这是**函数式接口**：测试用 lambda / 匿名类注入确定性返回值即可，判据完全离线。
 */
@FunctionalInterface
public interface LlmClient {

  /** 返回模型输出的原始文本（应为 JSON；是否合法由调用方校验）。 */
  String complete(LlmRequest request);
}
