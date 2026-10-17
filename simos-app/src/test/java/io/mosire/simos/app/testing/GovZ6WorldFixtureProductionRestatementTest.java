package io.mosire.simos.app.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.GovZ6WorldFixture.StateRunner;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.util.state.SimulationState;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>裁定四十八的守卫</b>（D-032 T7）：{@link GovZ6WorldFixture.StateRunner} 必须把 <b>production</b>
 * 切片的变更集也应用回状态。
 *
 * <p>缺陷现场（修前）：{@code GovZ6WorldFixture.applyChangeSet} 的 switch 只有 {@code
 * economy/social/actor/gov/unit}，{@code default -> null} ⇒ {@code StateRunner.advance} 里 {@code
 * next != null} 的守卫把 production ChangeSet <b>静默丢弃</b>。后果：任何"跨 advance 的 production 状态推进" （实例 {@code
 * progressDays}、经验、效率、覆盖）在 app 级夹具里都<b>永远看不到</b> —— N22（串行两次 advance vs 一次两天）的假阴性就是这么来的（见 spec
 * §14.26.1/§14.26.3 与 impl B12-§4.2-3）。
 *
 * <p>★ 这两条用例的判别力（**实测**，见台账 §16.1.4）：把 {@code case "production"} 拿掉、并把 {@code default} 改回 {@code
 * null}（= 修前形制）⇒ **第一条当场红**（推进一天后实例 {@code progressDays} 仍停在 0，期望 1）。 第二条比的是"一次两天 vs
 * 两次一天"：两侧都丢时**仍绿**（对称丢弃）⇒ 它钉的是**等价性本身**，缺口存在性由第一条钉。 ⇒ 不是"补丁顺手绿"，而是这条缺口本身的可复现判据。
 */
class GovZ6WorldFixtureProductionRestatementTest {

  @Test
  void stateRunnerAppliesTheProductionChangeSetAcrossAnAdvance() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    ProductionData baseProduction = productionOf(base);
    assertThat(baseProduction.instances()).as("小世界创世必须带 production 实例").isNotEmpty();
    assertThat(baseProduction.instances().values())
        .as("创世基线的实例进度全为 0（否则下面的 +1 读不通）")
        .allMatch(process -> process.progressDays() == 0L);

    StateRunner runner = new StateRunner("Map1", base);
    runner.advance(0L, 1L);

    ProductionData afterDay1 = productionOf(runner.state());
    assertThat(afterDay1.instances().keySet())
        .as("推进后实例集合不得变空（丢了 = production 片没被写回）")
        .containsAll(baseProduction.instances().keySet());
    assertThat(afterDay1.instances().values())
        .as("★ 裁定四十八：production 变更集必须被夹具应用（修前这里恒 0）")
        .allMatch(process -> process.progressDays() == 1L);
  }

  @Test
  void splitAdvancesAndOneShotAdvanceAgreeOnTheWholeProductionSlice() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");

    StateRunner oneShot = new StateRunner("Map1", base);
    oneShot.advance(0L, 2L);

    StateRunner split = new StateRunner("Map1", base);
    split.advance(0L, 1L);
    split.advance(1L, 2L);

    // ★ N22 的夹具面判据：同一套新规则下，"一次两天"与"两次各一天"在 production 切片上必须逐值一致
    //   （实例/进度/经验/效率/解锁/覆盖 —— 这里比整个 ProductionData，不挑字段）。
    //   修前：split 的 production 永远停在 base ⇒ progressDays 0 vs 2 ⇒ 红。
    assertThat(productionOf(split.state()))
        .as("N22：advance(0,2) 与 advance(0,1)+advance(1,2) 的 production 切片必须逐值相等")
        .isEqualTo(productionOf(oneShot.state()));
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }
}
