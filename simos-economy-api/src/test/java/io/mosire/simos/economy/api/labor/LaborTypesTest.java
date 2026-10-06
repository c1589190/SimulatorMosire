package io.mosire.simos.economy.api.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.social.api.population.Sex;
import org.junit.jupiter.api.Test;

/**
 * ★★ **P2 劳动口径的类型护栏**（{@link HouseholdLaborTimeTable}）：家户每 tick 时间预算的逐值系数。
 *
 * <p>★ 判据的意义：P2-A §13.4 起劳动单位 = 毫小时，家户时间预算是**每 tick 重算的有限量**；第二权威 {@code LaborSupply} 与旧的分配载体
 * {@code LaborAllocation} 均已从主代码删除，经济侧只剩这张可注入的系数表。
 *
 * <p>★ 迁移留痕（D5）：原 {@code LaborTimeTable} → 当前 {@link HouseholdLaborTimeTable}（逐值字段与默认口径相同： child /
 * adultMale / adultFemale / elder）；原 {@code LaborAllocation} 段落整体删除 —— 该类已不存在于主代码， 其不变量（id
 * 非空、group/household/actor 非空、activity 非空白、劳动量与周期非负）没有可测的当前类型。
 */
class LaborTypesTest {

  // ── HouseholdLaborTimeTable：家户每 tick 时间预算的逐值系数 ─────────────────────────

  /**
   * ★★ **逐值算例**（P2-A §13.4 的默认口径）：未成年 4h、成年男 16h、成年女 8h、老年 0（毫小时）。
   *
   * <p>★ 判别力：把性别档写成同一值、把老年档猜成 4h/8h、或把单位从毫小时降成小时 ⇒ 本条的 4000/16000/8000/0 至少有一处当场红（"家户每 tick
   * 有限时间"这条判据全在这四个数上）。
   */
  @Test
  void defaultTableGivesTheDocumentedPerPersonBudgets() {
    assertThat(
            HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(
                HouseholdLaborTimeTable.BRACKET_CHILD, Sex.MALE))
        .as("未成年：4h = 4000 毫小时")
        .isEqualTo(4_000L);
    assertThat(
            HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(
                HouseholdLaborTimeTable.BRACKET_ADULT, Sex.MALE))
        .as("成年男：16h = 16000 毫小时")
        .isEqualTo(16_000L);
    assertThat(
            HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(
                HouseholdLaborTimeTable.BRACKET_ADULT, Sex.FEMALE))
        .as("成年女：8h = 8000 毫小时（与成年男不同的档必须真的不同）")
        .isEqualTo(8_000L);
    assertThat(
            HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(
                HouseholdLaborTimeTable.BRACKET_ELDER, Sex.MALE))
        .as("老年：0（口径是『先定死为 0』，不是『档位不存在』）")
        .isZero();
    assertThat(
            HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(
                HouseholdLaborTimeTable.BRACKET_ELDER, Sex.FEMALE))
        .isZero();
  }

  /** ★ 系数表可注入：非默认实例必须按自己的值算，不能回落到 {@link HouseholdLaborTimeTable#DEFAULT}。 */
  @Test
  void injectedTableUsesItsOwnCoefficients() {
    HouseholdLaborTimeTable custom = new HouseholdLaborTimeTable(1_000L, 2_000L, 3_000L, 5_000L);

    assertThat(custom.perPersonMilliHours(HouseholdLaborTimeTable.BRACKET_CHILD, Sex.FEMALE))
        .isEqualTo(1_000L);
    assertThat(custom.perPersonMilliHours(HouseholdLaborTimeTable.BRACKET_ADULT, Sex.MALE))
        .isEqualTo(2_000L);
    assertThat(custom.perPersonMilliHours(HouseholdLaborTimeTable.BRACKET_ADULT, Sex.FEMALE))
        .isEqualTo(3_000L);
    assertThat(custom.perPersonMilliHours(HouseholdLaborTimeTable.BRACKET_ELDER, Sex.MALE))
        .isEqualTo(5_000L);
  }

  /** ★ 未知档位 / null 性别 ⇒ 具名抛（不猜、不给默默认值）。 */
  @Test
  void perPersonRejectsUnknownBracketAndNullSex() {
    assertThatThrownBy(() -> HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(3, Sex.MALE))
        .as("档位只有 0/1/2；第 4 档即抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知年龄档");
    assertThatThrownBy(() -> HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(-1, Sex.MALE))
        .isInstanceOf(IllegalArgumentException.class);
    // ★ 生产实现用 Objects.requireNonNull(sex)：null 性别 ⇒ NPE（Java 的 null 契约），不是"猜一个默认档"。
    assertThatThrownBy(
            () ->
                HouseholdLaborTimeTable.DEFAULT.perPersonMilliHours(
                    HouseholdLaborTimeTable.BRACKET_ADULT, null))
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
      assertThatThrownBy(() -> new HouseholdLaborTimeTable(bad[0], bad[1], bad[2], bad[3]))
          .as("负系数不是一种时间预算：%s", java.util.Arrays.toString(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new HouseholdLaborTimeTable(0L, 0L, 0L, 0L)).as("零预算是合法值（值 0，不是缺档）").isNotNull();
  }
}
