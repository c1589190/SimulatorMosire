package io.mosire.simos.economy.api.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z3c-1 契约护栏：{@link DeductionReason#ADMIN_SALARY} 的登记与旧值不变</b>（设计书 §9 / z3c1 台账 §9.8）。
 *
 * <p>判据：新增官吏工资档必须能用规范字面量 {@code admin_salary} {@code parse} 回来；旧四档（军俸/辖区税/行政俸禄/ 徭役）的 {@code
 * value()} 与 {@code parse} 一字不动。词表是闭枚举（{@code parse} 大小写敏感、不归一）—— 写错一个档必须 当场炸并列出全部合法值。
 */
class DeductionReasonTest {

  private static final List<DeductionReason> LEGACY_REASONS =
      List.of(
          DeductionReason.MILITARY_SALARY,
          DeductionReason.JURISDICTION_TAX,
          DeductionReason.ADMIN_UPKEEP,
          DeductionReason.CORVEE);

  /** 新增档：规范字面量 = {@code admin_salary}（不是 {@code ADMIN_SALARY}，不做归一）。 */
  @Test
  void adminSalaryIsRegisteredWithCanonicalValue() {
    assertThat(DeductionReason.ADMIN_SALARY.value()).isEqualTo("admin_salary");
    assertThat(DeductionReason.parse("admin_salary")).isEqualTo(DeductionReason.ADMIN_SALARY);
    assertThat(DeductionReason.ADMIN_SALARY.name()).isEqualTo("ADMIN_SALARY");
  }

  /** ★ Z7c 新增档：{@code gov_remittance} —— 省政府上缴中央的转账腿；旧档四档一个不丢。 */
  @Test
  void govRemittanceIsRegisteredWithCanonicalValue() {
    assertThat(DeductionReason.GOV_REMITTANCE.value()).isEqualTo("gov_remittance");
    assertThat(DeductionReason.parse("gov_remittance")).isEqualTo(DeductionReason.GOV_REMITTANCE);
    assertThat(DeductionReason.GOV_REMITTANCE.name()).isEqualTo("GOV_REMITTANCE");
    assertThat(DeductionReason.all()).contains(DeductionReason.GOV_REMITTANCE);
    assertThat(DeductionReason.all()).containsAll(LEGACY_REASONS);
  }

  /** 旧四档读回不变：{@code value()} 仍是原字面量，{@code parse(value())} 仍是原常量（新增档不得改写旧档）。 */
  @Test
  void legacyReasonsParseBackToTheSameConstants() {
    assertThat(LEGACY_REASONS)
        .extracting(DeductionReason::value)
        .containsExactly("military_salary", "jurisdiction_tax", "admin_upkeep", "corvee");
    for (DeductionReason reason : LEGACY_REASONS) {
      assertThat(DeductionReason.parse(reason.value()))
          .as("旧档 %s 读回不变", reason.value())
          .isEqualTo(reason);
    }
  }

  /** 词表按声明序可遍历，且新增档在其中（供遍历/断言用）。 */
  @Test
  void allContainsEveryDeclaredReasonInDeclarationOrder() {
    assertThat(DeductionReason.all()).containsExactly(DeductionReason.values());
    assertThat(DeductionReason.all()).contains(DeductionReason.ADMIN_SALARY);
  }

  /** 坏档当场炸：大小写敏感（不归一）、未知档列出全部合法值。 */
  @Test
  void parseIsCaseSensitiveAndRejectsUnknownValuesWithTheLegalList() {
    assertThatThrownBy(() -> DeductionReason.parse("ADMIN_SALARY"))
        .as("枚举名不是规范字面量；归一是猜，必须炸")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ADMIN_SALARY")
        .hasMessageContaining("admin_salary");
    assertThatThrownBy(() -> DeductionReason.parse("admin-salary"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("admin_salary");
    assertThatThrownBy(() -> DeductionReason.parse(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    assertThatThrownBy(() -> DeductionReason.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
  }

  /** ★ JSON 往返（Jackson 默认枚举名）：新档与旧档都能写回同一个常量（线格式不因新增档漂移）。 */
  @Test
  void everyReasonRoundTripsThroughItsJsonName() throws Exception {
    ObjectMapper mapper = SimosObjectMapper.create();
    for (DeductionReason reason : DeductionReason.values()) {
      String json = mapper.writeValueAsString(reason);
      assertThat(json).isEqualTo("\"" + reason.name() + "\"");
      assertThat(mapper.readValue(json, DeductionReason.class))
          .as("%s 的 JSON 往返", reason)
          .isEqualTo(reason);
    }
  }
}
