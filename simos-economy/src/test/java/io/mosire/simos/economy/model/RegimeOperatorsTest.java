package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import org.junit.jupiter.api.Test;

/**
 * ★★ **制度 → 默认经营主体**的推导表（S1 阶段 3 Task 1；spec §六 + 裁定 R1/R3）。
 *
 * <p>★ 判别力：表里任一档的 {@code ActorKind} 改错（如 {@code handicraft → ESTATE}）⇒ 第一条用例**红**； 把"未登记即抛"改回
 * fail-open（拿某个档顶替）⇒ 第二条用例**红**；id 改成裸 hex ⇒ 第一条的末句**红**。
 */
class RegimeOperatorsTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");

  /** ★★ spec §六 四档**逐值** + 裁定 R3 的 id 拼法。 */
  @Test
  void theFourDocumentedRegimesMapToTheirDocumentedKinds() {
    assertThat(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM))
        .isEqualTo(new ActorRef(ActorKind.ESTATE, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("household"), FARM))
        .isEqualTo(new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("handicraft"), FARM))
        .isEqualTo(new ActorRef(ActorKind.WORKSHOP, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("tenant"), FARM))
        .as("★★ 本轮新加的租佃档：佃农家户经营（不是地主）")
        .isEqualTo(new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM).toString())
        .as("★ R3：id 是产业 id、不是裸 hex（否则同一个庄园会有两个 ActorRef）")
        .isEqualTo("ESTATE:farm@0_0");
  }

  /** ★★ 裁定 R1：未登记的制度**不许猜**（fail-closed）；★ 字面量大小写敏感（本表不做归一）。 */
  @Test
  void anUnregisteredRegimeIsRefusedRatherThanGuessed() {
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(new RegimeId("capitalist"), FARM))
        .as("★ 现存反例：EconomyCodecTest 的 WageFirst 夹具正用 capitalist")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capitalist")
        .hasMessageContaining("tenant");
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(new RegimeId("Feudal"), FARM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 登记面就是这四个（保序 = spec §六 表序）；null 不猜（同 `Industry` 一族构造期守卫）。 */
  @Test
  void theRegisteredTableIsExactlyThoseFourAndNullIsRejected() {
    assertThat(RegimeOperators.registered().keySet())
        .containsExactly("feudal", "household", "handicraft", "tenant");
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(null, FARM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(new RegimeId("feudal"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
