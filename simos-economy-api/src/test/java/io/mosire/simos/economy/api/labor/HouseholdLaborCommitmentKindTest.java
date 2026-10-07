package io.mosire.simos.economy.api.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.json.SimosObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z1b/Z3a 契约护栏：{@link HouseholdLaborCommitment#kind()}（{@link LaborCommitmentKind}）</b>。
 *
 * <p>判据（按 z1b 台账 §10.4 / z3a 台账 §7.2 的"旧 7 参构造点编译/语义兼容"）：
 *
 * <ul>
 *   <li>7 参便利构造器 = 旧调用点，缺省 {@link LaborCommitmentKind#PRODUCTION}；
 *   <li>8 参 canonical 显式给 {@code GOV_SERVICE} 必须原样保留（"缺省"不得覆盖显式值）；
 *   <li>{@code kind == null} 在契约层具名拒（不能把 null 静默当成 PRODUCTION；JSON 缺键的 {@code PRODUCTION} 缺省在
 *       {@code EconomyCodec} 的兼容层，不在契约层）；
 *   <li>显式 {@code GOV_SERVICE} 的 JSON 往返逐字节稳定（本仓类型自身过线的判据）。
 * </ul>
 */
class HouseholdLaborCommitmentKindTest {

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-kind-test");
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-kind-test");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, HOUSEHOLD.value());
  private static final ProductionUnitId UNIT =
      ProductionUnitId.idOf(new IndustryId("farm@0_0"), OPERATOR);

  private static HouseholdLaborCommitment commitment(LaborCommitmentKind kind) {
    return new HouseholdLaborCommitment(
        ALLOCATION, LOT, HOUSEHOLD, OPERATOR, UNIT.value(), 16_000L, 1L, kind);
  }

  /** 旧 7 参调用点（z1b §5 尾注列的测试构造点同形）不得编译不过，且语义 = PRODUCTION。 */
  @Test
  void sevenArgumentLegacyConstructorDefaultsToProduction() {
    HouseholdLaborCommitment legacy =
        new HouseholdLaborCommitment(
            ALLOCATION, LOT, HOUSEHOLD, OPERATOR, UNIT.value(), 16_000L, 1L);

    assertThat(legacy.kind())
        .as("旧 7 参构造点缺省 = PRODUCTION（漏带 kind 的旧调用点不得静默变成 GOV_SERVICE）")
        .isEqualTo(LaborCommitmentKind.PRODUCTION);
    assertThat(legacy.id()).isEqualTo(ALLOCATION);
    assertThat(legacy.group()).isEqualTo(LOT);
    assertThat(legacy.household()).isEqualTo(HOUSEHOLD);
    assertThat(legacy.actor()).isEqualTo(OPERATOR);
    assertThat(legacy.activity()).isEqualTo(UNIT.value());
    assertThat(legacy.laborMilli()).isEqualTo(16_000L);
    assertThat(legacy.period()).isEqualTo(1L);
  }

  /** 显式 GOV_SERVICE 必须被 canonical 构造器原样保留（"缺省"不得覆盖显式值）。 */
  @Test
  void explicitGovServiceIsKeptByCanonicalConstructor() {
    HouseholdLaborCommitment govService = commitment(LaborCommitmentKind.GOV_SERVICE);

    assertThat(govService.kind()).isEqualTo(LaborCommitmentKind.GOV_SERVICE);
    assertThat(commitment(LaborCommitmentKind.PRODUCTION).kind())
        .isEqualTo(LaborCommitmentKind.PRODUCTION);
  }

  /** null kind 在契约层具名拒（JSON 缺键的 PRODUCTION 缺省由 EconomyCodec 兼容层负责，见类注）。 */
  @Test
  void nullKindIsRejectedByName() {
    assertThatThrownBy(() -> commitment(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind")
        .hasMessageContaining("PRODUCTION");
  }

  /**
   * ★ 显式 {@code GOV_SERVICE} 的 JSON 往返稳定：两次编码逐字节相等（若某个绑定把枚举写成别的形状 / 读侧丢了 kind， 解码后的 record
   * 会在值断言处红）。
   */
  @Test
  void explicitGovServiceRoundTripsThroughJsonByteStably() throws Exception {
    ObjectMapper mapper = SimosObjectMapper.create();
    HouseholdLaborCommitment govService = commitment(LaborCommitmentKind.GOV_SERVICE);

    String once = mapper.writeValueAsString(govService);
    HouseholdLaborCommitment back = mapper.readValue(once, HouseholdLaborCommitment.class);
    String twice = mapper.writeValueAsString(back);

    assertThat(back).isEqualTo(govService);
    assertThat(back.kind()).isEqualTo(LaborCommitmentKind.GOV_SERVICE);
    assertThat(twice).as("显式 GOV_SERVICE 的 JSON 字节级往返").isEqualTo(once);
  }

  /**
   * ★ 契约层的边界（防误读）：JSON **缺 kind 键**不是契约层的缺省点 —— 契约层收 null 具名拒。 "缺键 ⇒ PRODUCTION"是 {@code
   * EconomyCodec} 的读侧兼容（旧档方向），其用例在 economy 模块 {@code EconomyCodecTest} 里（本用例只把界线钉住，避免两处各造一份缺省语义）。
   */
  @Test
  void jsonWithoutKindIsRejectedAtContractLayerWhereTheCodecCompatibilityRunsElsewhere()
      throws Exception {
    ObjectMapper mapper = SimosObjectMapper.create();
    String json = mapper.writeValueAsString(commitment(LaborCommitmentKind.PRODUCTION));
    assertThat(json).contains("\"kind\":\"PRODUCTION\"");
    String withoutKind = json.replace(",\"kind\":\"PRODUCTION\"", "");
    assertThat(withoutKind).doesNotContain("\"kind\":\"PRODUCTION\"");

    assertThatThrownBy(() -> mapper.readValue(withoutKind, HouseholdLaborCommitment.class))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasStackTraceContaining("kind");
  }
}
