package io.mosire.simos.sd.adjudication;

/**
 * 一次裁决的结果（spec §八.5，N10/N13）：**三态**——接受 / 弃权 / 失败。
 *
 * <p>★ **异常绝不逃逸**：LLM 超时、返回非法 JSON、schema 不过，都由 {@link LlmDecisionAdjudicator} 折成 {@link
 * Failed}（或模型显式弃权 ⇒ {@link Abstained}）；调用方据此**继续本 tick**，不得卡死（N13）。
 */
public sealed interface Judgement {

  /** 接受：{@code payloadJson} 已通过该断点的 schema 校验，可冻结进 {@code Verdict}（N7）。 */
  record Accepted(String payloadJson) implements Judgement {

    public Accepted {
      if (payloadJson == null) {
        throw new IllegalArgumentException("payloadJson 不得为 null（空载荷用 \"{}\"）");
      }
    }
  }

  /** 弃权：模型明确选择不裁决（合法输出，非失败）。 */
  record Abstained(String reason) implements Judgement {

    public Abstained {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 不得为空白");
      }
    }
  }

  /** 失败：超时 / 非法 JSON / schema 不过（N13 的降级落点）。 */
  record Failed(String reason) implements Judgement {

    public Failed {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 不得为空白");
      }
    }
  }
}
