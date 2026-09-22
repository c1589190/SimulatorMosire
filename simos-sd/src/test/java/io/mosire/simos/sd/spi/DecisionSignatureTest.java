package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link DecisionSignature} 的用例（T11B 的"署名 B"洞）。
 *
 * <p>★ **两个方向都要有条**：冒名要拒（否则洞还在），**本人要放**（否则决策人连出令都出不了——那正是 T5-T8 曾经踩过的形态）； 另外 GM
 * 与"判不了"两种情形各有一条，免得把"判不了"实现成"冒名"。
 */
class DecisionSignatureTest {

  private static final String OWN = "dm-fra";

  /** 主干：署名是别人 ⇒ 拒，且理由里两个 id 都在（可读、可定位）。 */
  @Test
  void signingSomeoneElsesNameIsAViolation() {
    Optional<String> violation =
        DecisionSignature.violation(
            Optional.of(OWN), "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm-ger\"}");

    assertThat(violation).isPresent();
    assertThat(violation.get()).contains(OWN).contains("dm-ger").contains("decisionMakerId");
  }

  /** ★ 反方向：以**自己**名义 ⇒ 放行（这条不绿，决策人就出不了令了）。 */
  @Test
  void signingOnesOwnNameIsNotAViolation() {
    Optional<String> violation =
        DecisionSignature.violation(
            Optional.of(OWN), "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm-fra\"}");

    assertThat(violation).isEmpty();
  }

  /** GM（身份解不出决策人）⇒ 不受此限：它可以为任意决策人落决策（既有授权，不是漏洞）。 */
  @Test
  void anUnattributableCallerIsNotConstrained() {
    assertThat(
            DecisionSignature.violation(
                Optional.empty(), "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm-ger\"}"))
        .isEmpty();
  }

  /** ★ 载荷没有署名 / 不是合法 JSON ⇒ 本类**判不了**（既不放过也不冒名），交给 handler 按载荷形状拒。 */
  @Test
  void aPayloadWithoutASignatureIsNotJudgedHere() {
    assertThat(DecisionSignature.violation(Optional.of(OWN), "{\"directiveId\":\"d1\"}")).isEmpty();
    assertThat(DecisionSignature.violation(Optional.of(OWN), "不是 JSON")).isEmpty();
    assertThat(DecisionSignature.violation(Optional.of(OWN), "{\"decisionMakerId\":123}"))
        .isEmpty();
    assertThat(DecisionSignature.signatureOf(null)).isEmpty();
  }

  /**
   * ★ **逐字比较、不归一化**（取严的方向）：{@code "dm-fra "}（尾随空格）**不是** {@code "dm-fra"}。
   *
   * <p>归一化（trim / 大小写）会把这一格从"拒"变成"放"——那正是**放宽**的失效方向。
   */
  @Test
  void theComparisonIsLiteralSoNormalisationCannotWidenIt() {
    assertThat(
            DecisionSignature.violation(
                Optional.of(OWN), "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm-fra \"}"))
        .isPresent();
    assertThat(
            DecisionSignature.violation(
                Optional.of(OWN), "{\"directiveId\":\"d1\",\"decisionMakerId\":\"DM-FRA\"}"))
        .isPresent();
  }

  /** 署名本身能被单独读出（app 侧的强制点与 handler 读的是同一个字段、同一台 mapper）。 */
  @Test
  void theSignatureIsReadableOnItsOwn() {
    assertThat(DecisionSignature.signatureOf("{\"decisionMakerId\":\"dm-ger\"}"))
        .contains("dm-ger");
    assertThat(DecisionSignature.signatureOf("{}")).isEmpty();
  }
}
