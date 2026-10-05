package io.mosire.simos.unit.spi;

import static io.mosire.simos.unit.spi.SpiFixture.T0;
import static io.mosire.simos.unit.spi.SpiFixture.U1;
import static io.mosire.simos.unit.spi.SpiFixture.map;
import static io.mosire.simos.unit.spi.SpiFixture.state;
import static io.mosire.simos.unit.spi.SpiFixture.unitState;
import static io.mosire.simos.unit.spi.SpiFixture.unitWithMovement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link RenameUnitHandler}（spec §9.2）：信封全链的最小被测对象。 */
class RenameUnitHandlerTest {

  private static final RenameUnitHandler HANDLER = new RenameUnitHandler();

  private static HandlerOutcome handle(UnitState base, String payloadJson) {
    return HANDLER.handle(state(map(), base), payloadJson);
  }

  /** 期望拒绝的用例走这里：拒绝路径的断言一律在 {@code Rejected} 形态上做。 */
  private static HandlerOutcome.Rejected rejected(UnitState base, String payloadJson) {
    return (HandlerOutcome.Rejected) handle(base, payloadJson);
  }

  private static UnitState baseState() {
    return unitState(unitWithMovement(Optional.empty()));
  }

  @Test
  void typeIsUnitRenameUnit() {
    assertThat(HANDLER.type()).isEqualTo("unit.RenameUnit");
  }

  @Test
  void renamesThroughTheEnvelopeChain() {
    UnitState base = baseState();
    HandlerOutcome outcome = handle(base, "{\"id\":\"u-1\",\"name\":\"第二连\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitChangeSet changeSet = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    UnitState next = UnitChangeSet.apply(changeSet, base);
    assertThat(next.units().get(U1).name()).isEqualTo("第二连");
    // 改名只动 name：其余 16 个组件（含 equipment / households / parent 时态段）逐值带过
    assertThat(next.units().get(U1))
        .usingRecursiveComparison()
        .ignoringFields("name")
        .isEqualTo(base.units().get(U1));
  }

  @Test
  void payloadNameIsUsedVerbatim() {
    // 空格与冒号都原样保留——handler 不做任何规范化（R11 的模块侧义务）
    String name = "第一 连: 突击队";
    UnitState base = baseState();
    HandlerOutcome outcome = handle(base, "{\"id\":\"u-1\",\"name\":\"" + name + "\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitChangeSet changeSet = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(UnitChangeSet.apply(changeSet, base).units().get(U1).name()).isEqualTo(name);
  }

  @Test
  void unknownUnitIsRejectedWithReason() {
    assertThat(rejected(baseState(), "{\"id\":\"u-404\",\"name\":\"幽灵部队\"}").reason())
        .contains("单位不存在");
  }

  @Test
  void malformedPayloadIsRejected() {
    assertThat(rejected(baseState(), "这不是 JSON").reason()).contains("不是合法 JSON");
  }

  @Test
  void missingOrNonTextualFieldsAreRejected() {
    String expected = "payload 必须是 {\"id\":字符串,\"name\":字符串}";
    assertThat(rejected(baseState(), "{}").reason()).contains(expected);
    assertThat(rejected(baseState(), "{\"id\":\"u-1\",\"name\":42}").reason()).contains(expected);
    assertThat(rejected(baseState(), "{\"id\":7,\"name\":\"第二连\"}").reason()).contains(expected);
  }

  @Test
  void blankNameIsRejected() {
    assertThat(rejected(baseState(), "{\"id\":\"u-1\",\"name\":\" \"}").reason())
        .contains("name 不得为空白");
  }

  @Test
  void sameNameRenameIsAppliedWithEmptyChangeSet() {
    UnitState base = baseState();
    HandlerOutcome outcome = handle(base, "{\"id\":\"u-1\",\"name\":\"第一连\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitChangeSet changeSet = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.isEmpty()).isTrue();
  }

  @Test
  void missingUnitSliceIsAssemblyFaultNotRejection() {
    MapSnapshot mapOnly = new MapSnapshot(SpiFixture.REF, T0, map());
    assertThatThrownBy(
            () ->
                HANDLER.handle(
                    SpiFixture.singleModuleState("map", mapOnly),
                    "{\"id\":\"u-1\",\"name\":\"第二连\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unit");
  }
}
