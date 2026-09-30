package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code sd.CreateArmy} 的正常 / 拒绝路径（含经 unit 切片读根单位与 masterGov 存在性）。 */
class CreateArmyHandlerTest {

  private static final CreateArmyHandler HANDLER = new CreateArmyHandler();

  @Test
  void createsArmyWithoutMasterGovWhenRootUnitExists() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"rootUnitId\":\"u-1\",\"name\":\"第九军\"}");

    assertThat(next.armies()).containsKey(new ArmyId("a9"));
    assertThat(next.armies().get(new ArmyId("a9")).rootUnit()).isEqualTo(SdWorlds.ROOT_UNIT);
    assertThat(next.armies().get(new ArmyId("a9")).masterGovUnitId())
        .as("缺省 masterGovUnitId = 未认主子")
        .isEmpty();
  }

  /** ★ 显式 {@code null} 与缺省同义（可空载荷的两侧都钉住）。 */
  @Test
  void createsArmyWhenMasterGovIsExplicitNull() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"masterGovUnitId\":null,\"rootUnitId\":\"u-1\","
                + "\"name\":\"第九军\"}");

    assertThat(next.armies().get(new ArmyId("a9")).masterGovUnitId())
        .as("显式 null ⇒ 未认主子")
        .isEmpty();
  }

  @Test
  void createsArmyWhenMasterGovIsAnExistingGovUnit() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base, SdWorlds.map(), govUnits()),
            "{\"armyId\":\"a9\",\"masterGovUnitId\":\"u-1\",\"rootUnitId\":\"u-1\","
                + "\"name\":\"第九军\"}");

    assertThat(next.armies().get(new ArmyId("a9")).masterGovUnitId())
        .as("认领的 masterGov 原样落盘")
        .contains(SdWorlds.ROOT_UNIT);
  }

  @Test
  void rejectsDuplicateArmyId() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base), "{\"armyId\":\"a1\",\"rootUnitId\":\"u-1\",\"name\":\"重复\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("军队已存在: a1");
  }

  /** ★ 阶段 12：旧 {@code nationId} 键具名拒并指路（不是静默忽略，也不硬映射成 GOV）。 */
  @Test
  void rejectsLegacyNationIdWithNamedMessage() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"nationId\":\"n1\",\"rootUnitId\":\"u-1\",\"name\":\"第九军\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .isEqualTo(
            "sd.CreateArmy 不再接受 nationId：Army 已去 NationId，改认 masterGovUnitId"
                + "（可选，缺省 = 未认主子）。国家归属请用 sd.CreateNation/Affiliation.Nation；"
                + "认领 GOV 请用 unit.SetArmyFormation。");
  }

  @Test
  void rejectsMissingMasterGov() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"masterGovUnitId\":\"ghost\",\"rootUnitId\":\"u-1\","
                + "\"name\":\"第九军\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .isEqualTo("masterGovUnitId 不存在: ghost");
  }

  @Test
  void rejectsMasterGovThatIsNotGov() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"masterGovUnitId\":\"u-1\",\"rootUnitId\":\"u-1\","
                + "\"name\":\"第九军\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .isEqualTo("masterGovUnitId 不是 GOV 单位: u-1");
  }

  @Test
  void rejectsMissingRootUnit() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base), "{\"armyId\":\"a9\",\"rootUnitId\":\"ghost\",\"name\":\"第九军\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("rootUnitId 不存在: ghost");
  }

  /** 既有根单位 u-1 挂上 GovFormation，作为 masterGov 的合法目标。 */
  private static UnitState govUnits() {
    return UnitOperations.setGovFormation(
        SdWorlds.units(),
        SdWorlds.ROOT_UNIT,
        new GovFormation(Map.of(), OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL));
  }

  private static SdState applied(SdState base, SimulationState world, String payload) {
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}
