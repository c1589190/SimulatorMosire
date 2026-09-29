package io.mosire.simos.economy.api.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R2 的劳动关系层**（{@link LaborSupply} / {@link LaborAllocation}）的类型护栏：构造期不变量逐条 + 那条公式 {@code
 * availableLabor = 毛额 − 已服役 − 已承诺} 的**逐值**算例。
 *
 * <p>★ 判据的意义：这两个类型是"劳动可分配但不能凭空重复"的**唯一载体** —— 它们的字段就是守恒式的两端，故不变量必须在这里判死
 * （负劳动、负周期、扣除项超过毛额都不是"状态"，是坏数据）。
 */
class LaborTypesTest {

  private static final PeopleLotId GROUP = new PeopleLotId("rural:0_0:MALE:1");
  private static final HouseholdId HOUSEHOLD = new HouseholdId("hh-0_0-rural-poor_peasant");
  private static final ActorRef ACTOR = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ProductionUnitId UNIT =
      ProductionUnitId.idOf(new IndustryId("farm@0_0"), ACTOR);
  private static final String ACTIVITY = UNIT.value();

  // ── LaborSupply：那条公式 ────────────────────────────────────────────────────────────

  /**
   * ★★ **逐值算例**（本阶段最重要的不变量的右端）：{@code availableLabor = 毛额 − 已服役 − 已承诺}。
   *
   * <p>★ 判别力：把"减去服役/承诺"写成"只减其中一项"或"直接返回毛额" ⇒ 本条的 850 会变成 900 / 950 / 1000，三条一起红。
   */
  @Test
  void availableLaborIsGrossMinusServedMinusCommitted() {
    LaborSupply supply = new LaborSupply(GROUP, 1L, 1_000L, 100L, 50L);

    assertThat(supply.availableLabor()).as("1,000 − 100 − 50").isEqualTo(850L);
  }

  /** ★ 两项扣除**本轮恒 0**，但类型照减（0 是值，不是"这一项不存在"）——非 0 那条路必须真的走得到。 */
  @Test
  void availableLaborEqualsGrossWhenNothingIsServedOrCommitted() {
    LaborSupply supply = new LaborSupply(GROUP, 1L, 580_000L, 0L, 0L);

    assertThat(supply.availableLabor()).as("本轮创世的实际形态：两份扣除都是 0").isEqualTo(580_000L);
  }

  @Test
  void laborSupplyRejectsNegativeOrOverDeductedNumbers() {
    assertThatThrownBy(() -> new LaborSupply(null, 1L, 0L, 0L, 0L))
        .as("group 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborSupply(GROUP, -1L, 0L, 0L, 0L))
        .as("period 不得为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborSupply(GROUP, 1L, -1L, 0L, 0L))
        .as("毛额不得为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborSupply(GROUP, 1L, 0L, -1L, 0L))
        .as("已服役不得为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborSupply(GROUP, 1L, 0L, 0L, -1L))
        .as("已承诺不得为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborSupply(GROUP, 1L, 100L, 100L, 1L))
        .as("已服役 + 已承诺 > 毛额 ⇒ 可支配劳动为负 ⇒ 拒（不是一种状态，是坏数据）")
        .isInstanceOf(IllegalArgumentException.class);
    // ★ 边界：扣到 0 是**合法**的（毛额恰好全被占住 ⇒ 可支配 0）
    assertThat(new LaborSupply(GROUP, 1L, 100L, 100L, 0L).availableLabor()).isZero();
  }

  // ── LaborAllocation：一次分配 ────────────────────────────────────────────────────────

  /** 一条配额的各方都在：谁出的（group + household）、谁收的（actor）、干什么（activity）、多少（laborMilli）+ 发放周期。 */
  @Test
  void allocationCarriesBothEndsOfTheRelationAndTheAmount() {
    LaborAllocation allocation =
        new LaborAllocation(
            LaborAllocation.idOf(UNIT, GROUP, HOUSEHOLD),
            GROUP,
            HOUSEHOLD,
            ACTOR,
            ACTIVITY,
            464_000L,
            1L);

    assertThat(allocation.id())
        .as("★ S1 起 id = alloc-<unit>-<group>-<household>（家户是分配身份的一部分）")
        .isEqualTo(LaborAllocation.idOf(UNIT, GROUP, HOUSEHOLD));
    assertThat(allocation.group()).as("出劳动的那批人").isEqualTo(GROUP);
    assertThat(allocation.household()).as("★ S1 起这份劳动有明确的家户归属").isEqualTo(HOUSEHOLD);
    assertThat(allocation.actor()).as("收劳动的主体（产业 = 庄园）").isEqualTo(ACTOR);
    assertThat(allocation.activity())
        .as("★ R3B.2 起 activity = ProductionUnitId.value()，不是旧产业标签")
        .isEqualTo(UNIT.value());
    assertThat(allocation.laborMilli()).isEqualTo(464_000L);
    assertThat(allocation.period()).isEqualTo(1L);
  }

  @Test
  void allocationRejectsMissingOrientationOrNegativeAmounts() {
    assertThatThrownBy(() -> new LaborAllocation(null, GROUP, HOUSEHOLD, ACTOR, ACTIVITY, 1L, 1L))
        .as("id 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborAllocation(id(), null, HOUSEHOLD, ACTOR, ACTIVITY, 1L, 1L))
        .as("group 不得为 null（这笔劳动必须有人出）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborAllocation(id(), GROUP, null, ACTOR, ACTIVITY, 1L, 1L))
        .as("★ S1：household 不得为 null（这份劳动必须属于一个家户）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborAllocation(id(), GROUP, HOUSEHOLD, null, ACTIVITY, 1L, 1L))
        .as("actor 不得为 null（这笔劳动必须有人收）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborAllocation(id(), GROUP, HOUSEHOLD, ACTOR, "", 1L, 1L))
        .as("activity 不得为空白")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborAllocation(id(), GROUP, HOUSEHOLD, ACTOR, ACTIVITY, -1L, 1L))
        .as("劳动量不得为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LaborAllocation(id(), GROUP, HOUSEHOLD, ACTOR, ACTIVITY, 1L, -1L))
        .as("周期不得为负")
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static LaborAllocationId id() {
    return LaborAllocation.idOf(UNIT, GROUP, HOUSEHOLD);
  }
}
