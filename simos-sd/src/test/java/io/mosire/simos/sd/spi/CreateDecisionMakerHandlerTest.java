package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code sd.CreateDecisionMaker} 的正常 / 拒绝路径，并直证 N9（白名单不得含通用写）。 */
class CreateDecisionMakerHandlerTest {

  private static final CreateDecisionMakerHandler HANDLER = new CreateDecisionMakerHandler();

  @Test
  void createsDecisionMakerWhenAffiliationExists() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                + "\"allowedTools\":[\"sd.SubmitVerdict\"],\"cadence\":3}");
    assertThat(next.decisionMakers()).containsKey(new DecisionMakerId("dm9"));
    assertThat(next.decisionMakers().get(new DecisionMakerId("dm9")).allowedTools())
        .containsExactly("sd.SubmitVerdict");
  }

  @Test
  void rejectsDuplicateId() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm1\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                + "\"allowedTools\":[],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("决策人已存在: dm1");
  }

  @Test
  void rejectsMissingAffiliationTarget() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"ghost\"},"
                + "\"allowedTools\":[],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("affiliation 目标不存在");
  }

  @Test
  void rejectsAffiliationToMissingArmy() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"army\",\"id\":\"ghost\"},"
                + "\"allowedTools\":[],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("affiliation 目标不存在");
  }

  @Test
  void rejectsGenericWriteInAllowedTools() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                + "\"allowedTools\":[\"simos.command.submit\"],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("N9：决策 Agent 不得握通用写")
        .contains("simos.command.submit")
        .contains("N9");
  }

  // ── 阶段 10a/12：Gov 归属必须绑"存在且带 GovernmentFormation"的单位 ──────────

  /** ★ {@code {"kind":"gov","id":…}} 解析 + 创建期强绑：单位存在且带 GovernmentFormation ⇒ Applied。 */
  @Test
  void createsGovAffiliationWhenTheUnitHasGovernmentFormation() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base, SdWorlds.map(), govUnits()),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"gov\",\"id\":\"u-1\"},"
                + "\"allowedTools\":[\"unit.GetUnit\"],\"cadence\":1}");

    assertThat(next.decisionMakers()).containsKey(new DecisionMakerId("dm9"));
    assertThat(next.decisionMakers().get(new DecisionMakerId("dm9")).affiliation())
        .as("读回来是 Gov(UnitId)，不是 Nation/Army")
        .isEqualTo(new Affiliation.Gov(SdWorlds.ROOT_UNIT));
  }

  /** ★ 单位**不存在**：具名理由必须说"目标不存在"，而不是"没有 GovernmentFormation"（纠正方向不同）。 */
  @Test
  void rejectsGovAffiliationWhenTheUnitDoesNotExist() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base, SdWorlds.map(), govUnits()),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"gov\",\"id\":\"g-ghost\"},"
                + "\"allowedTools\":[],\"cadence\":1}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("不存在 ⇒ 目标不存在")
        .contains("affiliation 目标不存在")
        .contains("g-ghost");
  }

  /**
   * ★ 单位存在但**不是 GOV**：另一条具名拒（先 {@code unit.SetGovFormation}），不得与"不存在"合成一句。
   *
   * <p>判别力：同一 payload 在 {@code govUnits()} 世界是 Applied、在本世界是 Rejected——证明拒因真的来自编制形态。
   */
  @Test
  void rejectsGovAffiliationWhenTheUnitHasNoGovernmentFormation() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"gov\",\"id\":\"u-1\"},"
                + "\"allowedTools\":[],\"cadence\":1}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("单位存在但无 GovernmentFormation ⇒ 指路 unit.SetGovFormation")
        .contains("u-1")
        .contains("没有 GovernmentFormation")
        .contains("unit.SetGovFormation")
        .doesNotContain("affiliation 目标不存在");
  }

  private static SdState applied(SdState base, SimulationState world, String payload) {
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }

  /** 既有根单位 u-1 挂上 GovernmentFormation，作为 Gov 归属的合法目标（与 CreateArmyHandlerTest 同形）。 */
  private static UnitState govUnits() {
    return UnitOperations.setGovernmentFormation(
        SdWorlds.units(),
        SdWorlds.ROOT_UNIT,
        new GovernmentFormation(
            Map.of(),
            Map.of(),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL));
  }
}
