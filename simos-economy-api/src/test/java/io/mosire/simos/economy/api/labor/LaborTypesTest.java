package io.mosire.simos.economy.api.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import org.junit.jupiter.api.Test;

/**
 * ★★ **P2 劳动口径的类型护栏**（{@link LaborTimeTable} / {@link LaborAllocation}）：家户每 tick 时间预算的逐值算例 +
 * 一次劳动分配的构造期不变量。
 *
 * <p>★ 判据的意义：P2-A §13.4 起劳动单位 = 毫小时，家户时间预算是**每 tick 重算的有限量**（{@link LaborTimeTable}），
 * 第二权威 {@code LaborSupply} 已删除；{@link LaborAllocation} 是与该预算对齐的分配载体， 其字段就是守恒式 {@code Σ allocated ≤
 * available} 的两端，故不变量必须在这里判死（负劳动、负周期、缺主体都不是"状态"，是坏数据）。
 */
class LaborTypesTest {

  private static final PeopleLotId GROUP = new PeopleLotId("rural:0_0:MALE:1");
  private static final HouseholdId HOUSEHOLD = new HouseholdId("hh-0_0-rural-poor_peasant");
  private static final ActorRef ACTOR = new ActorRef(ActorKind.ORGANIZATION, "farm@0_0");
  private static final ProductionUnitId UNIT =
      ProductionUnitId.idOf(new IndustryId("farm@0_0"), ACTOR);
  private static final String ACTIVITY = UNIT.value();

  // ── LaborTimeTable：家户每 tick 时间预算的逐值系数 ─────────────────────────────────────

  /**
   * ★★ **逐值算例**（P2-A §13.4 的默认口径）：未成年 4h、成年男 16h、成年女 8h、老年 0（毫小时）。
   *
   * <p>★ 判别力：把性别档写成同一值、把老年档猜成 4h/8h、或把单位从毫小时降成小时 ⇒ 本条的 4000/16000/8000/0
   * 至少有一处当场红（"家户每 tick 有限时间"这条判据全在这四个数上）。
   */
  @Test
  void defaultTableGivesTheDocumentedPerPersonBudgets() {
    assertThat(LaborTimeTable.DEFAULT.perPersonMilliHours(LaborTimeTable.BRACKET_CHILD, Sex.MALE))
        .as("未成年：4h = 4000 毫小时")
        .isEqualTo(4_000L);
    assertThat(LaborTimeTable.DEFAULT.perPersonMilliHours(LaborTimeTable.BRACKET_ADULT, Sex.MALE))
        .as("成年男：16h = 16000 毫小时")
        .isEqualTo(16_000L);
    assertThat(LaborTimeTable.DEFAULT.perPersonMilliHours(LaborTimeTable.BRACKET_ADULT, Sex.FEMALE))
        .as("成年女：8h = 8000 毫小时（与成年男不同的档必须真的不同）")
        .isEqualTo(8_000L);
    assertThat(LaborTimeTable.DEFAULT.perPersonMilliHours(LaborTimeTable.BRACKET_ELDER, Sex.MALE))
        .as("老年：0（口径是『先定死为 0』，不是『档位不存在』）")
        .isZero();
    assertThat(LaborTimeTable.DEFAULT.perPersonMilliHours(LaborTimeTable.BRACKET_ELDER, Sex.FEMALE))
        .isZero();
  }

  /** ★ 系数表可注入：非默认实例必须按自己的值算，不能回落到 {@link LaborTimeTable#DEFAULT}。 */
  @Test
  void injectedTableUsesItsOwnCoefficients() {
    LaborTimeTable custom = new LaborTimeTable(1_000L, 2_000L, 3_000L, 5_000L);

    assertThat(custom.perPersonMilliHours(LaborTimeTable.BRACKET_CHILD, Sex.FEMALE)).isEqualTo(1_000L);
    assertThat(custom.perPersonMilliHours(LaborTimeTable.BRACKET_ADULT, Sex.MALE)).isEqualTo(2_000L);
    assertThat(custom.perPersonMilliHours(LaborTimeTable.BRACKET_ADULT, Sex.FEMALE)).isEqualTo(3_000L);
    assertThat(custom.perPersonMilliHours(LaborTimeTable.BRACKET_ELDER, Sex.MALE)).isEqualTo(5_000L);
  }

  /** ★ 未知档位 / null 性别 ⇒ 具名抛（不猜、不给默默认值）。 */
  @Test
  void perPersonRejectsUnknownBracketAndNullSex() {
    assertThatThrownBy(() -> LaborTimeTable.DEFAULT.perPersonMilliHours(3, Sex.MALE))
        .as("档位只有 0/1/2；第 4 档即抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知年龄档");
    assertThatThrownBy(() -> LaborTimeTable.DEFAULT.perPersonMilliHours(-1, Sex.MALE))
        .isInstanceOf(IllegalArgumentException.class);
    // ★ 生产实现用 Objects.requireNonNull(sex)：null 性别 ⇒ NPE（Java 的 null 契约），不是"猜一个默认档"。
    assertThatThrownBy(
            () -> LaborTimeTable.DEFAULT.perPersonMilliHours(LaborTimeTable.BRACKET_ADULT, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void timeTableRejectsNegativeCoefficientsAtConstruction() {
    for (long[] bad :
        new long[][] {
          {-1L, 16_000L, 8_000L, 0L},
          {4_000L, -1L, 8_000L, 0L},
          {4_000L, 16_000L, -1L, 0L},
          {4_000L, 16_000L, 8_000L, -1L}
        }) {
      assertThatThrownBy(() -> new LaborTimeTable(bad[0], bad[1], bad[2], bad[3]))
          .as("负系数不是一种时间预算：%s", java.util.Arrays.toString(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new LaborTimeTable(0L, 0L, 0L, 0L)).as("零预算是合法值（值 0，不是缺档）").isNotNull();
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
    assertThat(allocation.actor()).as("收劳动的主体").isEqualTo(ACTOR);
    assertThat(allocation.activity())
        .as("★ R3B.2 起 activity = ProductionUnitId.value()，不是旧产业标签")
        .isEqualTo(UNIT.value());
    assertThat(allocation.laborMilli()).as("P2-A 起单位 = 毫小时").isEqualTo(464_000L);
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
