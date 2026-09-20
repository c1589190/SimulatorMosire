package io.mosire.simos.sd.model;

/**
 * 判决元数据（spec §三.5，N8）：**模型 / 提示版本 / 输入简报摘要**——三字段**非空**（构造期强制）。
 *
 * <p>★ 留痕的另一半（渠道 id + actor，N18）落在 {@code VerdictMeta} 的扩展上，见 D5；本 record 只承载 N8 点名的三字段。
 */
public record VerdictMeta(String model, String promptVersion, String inputBriefDigest) {

  public VerdictMeta {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model 不得为空白");
    }
    if (promptVersion == null || promptVersion.isBlank()) {
      throw new IllegalArgumentException("promptVersion 不得为空白");
    }
    if (inputBriefDigest == null || inputBriefDigest.isBlank()) {
      throw new IllegalArgumentException("inputBriefDigest 不得为空白");
    }
  }
}
