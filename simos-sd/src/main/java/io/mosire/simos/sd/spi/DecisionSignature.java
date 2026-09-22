package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;

/**
 * **决策的署名规则**（T11B，控制器裁定要修的洞）：<b>决策人只能以自己名义落决策</b>。
 *
 * <p>★ **洞的形态**（T10 报告 §六 第 4 条）：{@code sd.IssueDirective} 的载荷里有一个模型可自由填的 {@code
 * decisionMakerId}，而命令路径上**没有任何地方**拿它与"这次调用到底是谁在做"对一遍 ⇒ 决策人 A 可以落一条**署名 B** 的 directive。 这与 spec
 * **N16**「渠道**不得冒称**任意 actor」是同一族的安全边界。
 *
 * <p>★★ **为什么"规则在 sd、强制点在 app"**（本轮唯一一处**偏离派单文字**的地方，如实记账）：派单要求"在 {@code simos-sd} 的相应 handler
 * 里校验"，但 {@link io.mosire.simos.util.spi.CommandHandler#handle} 的签名是 {@code (state,
 * payloadJson)}——**handler 结构性看不见调用者**（Core 只按 {@code type} 找 handler，信封上的 {@code initiator} 也不进
 * handler）。要让它看得见，得动 SPI 与 Core 的命令路径（均已关账、且 CLAUDE.md 明写"既有契约原地不动"）。 故实际形态是：**规则（本类，住在 sd 域、随 sd
 * 一起演进与测试）+ 强制点（app 层 {@code IssueDirectiveTool}，那里才有身份）**。
 *
 * <p>★ **口径**：
 *
 * <ul>
 *   <li>调用者**是**决策人（身份解析得出 id）：载荷署名**必须逐字等于**它，否则拒；
 *   <li>调用者**不是**决策人（GM / 认不出身份）：**不受此限**——"GM 可以为任意决策人落决策"是既有的、有意的授权（{@code
 *       AbstractNarrowWriteTool#decisionWriteResources} 的 javadoc 与 {@code
 *       DecisionMakerScopeEndToEndTest} 的 GM 用例都钉着它）；
 *   <li>载荷里**没有**署名：本类**不判**（那是"载荷形状"问题，归 handler 的 {@code requireText}）——两处各判各的，不互相抢判据。
 * </ul>
 *
 * <p>★ **逐字比较，不做归一化**：归一化（trim / 大小写 / 解析后比 value）会**放宽**这条判据——例如 {@code "dm-fra "} 若被 trim 成
 * {@code "dm-fra"} 就等于本人。比较取严，失效方向是"多拒一次"，不是"放过一条冒名"。
 */
public final class DecisionSignature {

  /** 载荷里署名所在的字段名（与 {@link IssueDirectiveHandler} 读的是同一个）。 */
  public static final String FIELD = "decisionMakerId";

  private DecisionSignature() {}

  /**
   * 载荷里的署名（{@code decisionMakerId}）。
   *
   * <p>★ **容错读**：载荷不是合法 JSON 对象 / 没有该字段 / 该字段不是字符串 ⇒ **空**。这三种情形都**不是**"署名合法"， 而是"本类判不了"——它们会在
   * handler 的 {@code SdPayloads.requireText} 那一层被拒（不写任何状态）。
   */
  public static Optional<String> signatureOf(String payloadJson) {
    if (payloadJson == null) {
      return Optional.empty();
    }
    JsonNode payload;
    try {
      payload = SdPayloads.parse(payloadJson);
    } catch (IllegalArgumentException e) {
      return Optional.empty(); // 坏载荷：不在这里抢 handler 的判据
    }
    JsonNode value = payload.get(FIELD);
    return value != null && value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
  }

  /**
   * 这次调用是否**冒名**。
   *
   * @param callerDecisionMakerId 调用者身份解出的决策人 id（{@code decision-maker:<id>} 里的 {@code <id>}）；不是决策人
   *     ⇒ 空
   * @param payloadJson 命令载荷原文（模型写的，**不受信**）
   * @return 拒绝理由；没有冒名（或本类判不了）⇒ {@link Optional#empty()}
   */
  public static Optional<String> violation(
      Optional<String> callerDecisionMakerId, String payloadJson) {
    Objects.requireNonNull(callerDecisionMakerId, "callerDecisionMakerId");
    if (callerDecisionMakerId.isEmpty()) {
      return Optional.empty(); // GM / 非决策人身份：不受此限（既有授权，见类注）
    }
    String caller = callerDecisionMakerId.get();
    return signatureOf(payloadJson)
        .filter(signature -> !signature.equals(caller))
        .map(
            signature ->
                "决策人 " + caller + " 不得以 " + signature + " 的名义落决策（载荷 " + FIELD + " 与调用者身份不一致）");
  }
}
