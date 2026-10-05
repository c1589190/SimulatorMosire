package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code unit.AdjustComposition} 的处理器（D-009 补裁的 GM 调试直改原语）： 有符号增量——正增量可新建 type（追加表尾）、负增量要求 type
 * 已存在且不越界、零增量是合法 no-op；GmOnly 标记不得丢。
 *
 * <p>★ S3b（2026-10-09）：{@code Unit.manpower} 已退役，本命令只作用于**装备表**；非空 `manpower` 载荷具名拒。
 */
class AdjustCompositionHandlerTest {

  private static final AdjustCompositionHandler HANDLER = new AdjustCompositionHandler();

  private static UnitState base() {
    return SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));
  }

  /** 两键装备基线（步枪:50 + 炮:4）——重载出"未提及 type"的观察面。 */
  private static UnitState twoKeyEquipment() {
    return UnitOperations.setComposition(
        base(),
        SpiFixture.U1,
        List.of(new CompositionEntry("步枪", 50), new CompositionEntry("炮", 4)));
  }

  private static UnitState applied(UnitState base, String payload) {
    HandlerOutcome outcome = HANDLER.handle(SpiFixture.state(SpiFixture.map(), base), payload);
    assertThat(outcome).as("期望 Applied 而不是 %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitChangeSet changeSet = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return UnitChangeSet.apply(changeSet, base);
  }

  private static String reason(UnitState base, String payload) {
    HandlerOutcome outcome = HANDLER.handle(SpiFixture.state(SpiFixture.map(), base), payload);
    assertThat(outcome)
        .as("期望 Rejected 而不是 %s", outcome)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  @Test
  void typeAndGmOnlyMarkerMatchTheSpec() {
    assertThat(HANDLER.type()).isEqualTo("unit.AdjustComposition");
    assertThat(HANDLER)
        .as("D-009 补裁收紧：调试直改原语不得嵌入决策令（只有 GM 直提 / GM 窄工具能用）")
        .isInstanceOf(GmOnlyCommand.class);
  }

  @Test
  void positiveDeltaOnAnExistingTypeAdds() {
    UnitState next =
        applied(base(), "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步枪\",\"amount\":25}]}");

    assertThat(next.units().get(SpiFixture.U1).equipment())
        .as("50 + 25 = 75（增量，不是覆写）")
        .containsExactly(new CompositionEntry("步枪", 75));
  }

  @Test
  void positiveDeltaOnANewTypeAppendsAtTheTail() {
    UnitState next =
        applied(
            twoKeyEquipment(),
            "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"坦克\",\"amount\":2}]}");

    assertThat(next.units().get(SpiFixture.U1).equipment())
        .as("新建条目追加在表尾，既有条目顺序不变")
        .containsExactly(
            new CompositionEntry("步枪", 50),
            new CompositionEntry("炮", 4),
            new CompositionEntry("坦克", 2));
  }

  @Test
  void negativeDeltaSubtractsAndLeavesUnmentionedTypesUntouched() {
    UnitState next =
        applied(
            twoKeyEquipment(),
            "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步枪\",\"amount\":-10}]}");

    assertThat(next.units().get(SpiFixture.U1).equipment())
        .as("只动提及 type；未提及的 炮 逐条逐位不变")
        .containsExactly(new CompositionEntry("步枪", 40), new CompositionEntry("炮", 4));
  }

  @Test
  void negativeDeltaDownToZeroKeepsTheEntry() {
    UnitState next =
        applied(base(), "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步枪\",\"amount\":-50}]}");

    assertThat(next.units().get(SpiFixture.U1).equipment())
        .as("减到 0 的条目保留（值 0，顺序不变）——不是整条消失")
        .containsExactly(new CompositionEntry("步枪", 0));
  }

  @Test
  void negativeDeltaRejectsUnknownTypes() {
    assertThat(reason(base(), "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"坦克\",\"amount\":-1}]}"))
        .contains("未知装备类型")
        .contains("坦克");
  }

  /** 正增量指向未知 type 是合法的新建（与 ApplyCasualties 的"未知 type 一律拒"刻意分叉）。 */
  @Test
  void positiveDeltaOnAnUnknownTypeCreatesItInsteadOfRejecting() {
    UnitState next =
        applied(base(), "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"坦克\",\"amount\":1}]}");

    assertThat(next.units().get(SpiFixture.U1).equipment())
        .containsExactly(new CompositionEntry("步枪", 50), new CompositionEntry("坦克", 1));
  }

  @Test
  void negativeDeltaRejectsOverdraw() {
    assertThat(
            reason(
                twoKeyEquipment(),
                "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步枪\",\"amount\":-51}]}"))
        .contains("装备减少超出当前值");
    assertThat(
            reason(
                twoKeyEquipment(), "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"炮\",\"amount\":-5}]}"))
        .contains("装备减少超出当前值");
  }

  @Test
  void zeroDeltaIsALegalNoOp() {
    HandlerOutcome outcome =
        HANDLER.handle(
            SpiFixture.state(SpiFixture.map(), base()),
            "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步枪\",\"amount\":0}]}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(((UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet()).isEmpty())
        .as("零增量不产 diff（与既有 attach/updateChain 的'无变化命令'同口径）")
        .isTrue();
  }

  @Test
  void duplicateTypesAndUnknownIdsAreRejected() {
    assertThat(
            reason(
                base(),
                "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步枪\",\"amount\":1},"
                    + "{\"type\":\"步枪\",\"amount\":2}]}"))
        .contains("不得有重复 type");
    assertThat(reason(base(), "{\"id\":\"u-404\",\"equipment\":[{\"type\":\"步枪\",\"amount\":1}]}"))
        .contains("单位不存在");
  }

  /** ★ S3b：非空 manpower 载荷必须具名拒——不能静默忽略后继续改装备（那会隐藏调用方的心智模型错误）。 */
  @Test
  void retiredManpowerPayloadIsRejected() {
    assertThat(
            reason(
                base(),
                "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":1}],"
                    + "\"equipment\":[]}"))
        .contains("已退役");
  }
}
