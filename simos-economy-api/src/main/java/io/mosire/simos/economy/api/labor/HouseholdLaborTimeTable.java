package io.mosire.simos.economy.api.labor;

import io.mosire.simos.social.api.population.Sex;
import java.util.Objects;

/**
 * ★★ <b>家户劳动时间预算的可调系数表</b>（P2-A §13.4，2026-10-09 用户裁定）：每个家户每 tick 有一段有限时间，
 * 按成员人数与（年龄档 × 性别）折算；单位 = <b>毫小时</b>（{@code 1 小时 = 1000 毫小时}，定点整数、无浮点）。
 *
 * <pre>
 * 默认（本批控制方给定值，可调；后续调试校准）：
 *   未成年（0-14）  4000 毫小时 = 4h
 *   成年男（15-59）16000 毫小时 = 16h
 *   成年女（15-59） 8000 毫小时 = 8h
 *   老年（60+）        0（待补；先确定口径，不等同于"永远不会配置"）
 * </pre>
 *
 * <p>★★ <b>口径</b>：{@link #perPersonMilliHours(int, Sex)} 是**每人每 tick** 的预算；家户预算 = Σ 成员
 * {@code count × perPersonMilliHours}。它是**重算量**（每 tick 从 Social 的家户成员与年龄现算），
 * 不是经济状态里的第二权威 —— {@code HouseholdEconomy.laborMilli} 只是它在本 tick 的投影。
 *
 * <p>★ <b>可编辑性</b>：默认值只有 {@link #DEFAULT} 一个拼写点；调用方（创世播种 / 协调器）可注入别的实例。
 * 年龄档序号与 {@code PopulationSeeder} 的 D4 三档同序：{@link #BRACKET_CHILD} / {@link #BRACKET_ADULT} /
 * {@link #BRACKET_ELDER}。
 *
 * @param childMilliHoursPerTick 未成年每人每 tick 毫小时；不得为负
 * @param adultMaleMilliHoursPerTick 成年男每人每 tick 毫小时；不得为负
 * @param adultFemaleMilliHoursPerTick 成年女每人每 tick 毫小时；不得为负
 * @param elderMilliHoursPerTick 老年每人每 tick 毫小时；不得为负
 */
public record HouseholdLaborTimeTable(
    long childMilliHoursPerTick,
    long adultMaleMilliHoursPerTick,
    long adultFemaleMilliHoursPerTick,
    long elderMilliHoursPerTick) {

  /** 年龄档序号：未成年（0-14）。 */
  public static final int BRACKET_CHILD = 0;

  /** 年龄档序号：成年（15-59）。 */
  public static final int BRACKET_ADULT = 1;

  /** 年龄档序号：老年（60+）。 */
  public static final int BRACKET_ELDER = 2;

  /** 本批默认表（可调；见类注）。 */
  public static final HouseholdLaborTimeTable DEFAULT =
      new HouseholdLaborTimeTable(4_000L, 16_000L, 8_000L, 0L);

  public HouseholdLaborTimeTable {
    if (childMilliHoursPerTick < 0L) {
      throw new IllegalArgumentException(
          "LaborTimeTable.childMilliHoursPerTick 不得为负: " + childMilliHoursPerTick);
    }
    if (adultMaleMilliHoursPerTick < 0L) {
      throw new IllegalArgumentException(
          "LaborTimeTable.adultMaleMilliHoursPerTick 不得为负: " + adultMaleMilliHoursPerTick);
    }
    if (adultFemaleMilliHoursPerTick < 0L) {
      throw new IllegalArgumentException(
          "LaborTimeTable.adultFemaleMilliHoursPerTick 不得为负: " + adultFemaleMilliHoursPerTick);
    }
    if (elderMilliHoursPerTick < 0L) {
      throw new IllegalArgumentException(
          "LaborTimeTable.elderMilliHoursPerTick 不得为负: " + elderMilliHoursPerTick);
    }
  }

  /** 一人一 tick 的时间预算（毫小时）；未知档位 ⇒ 具名抛（不猜、不给默认值）。 */
  public long perPersonMilliHours(int ageBracket, Sex sex) {
    Objects.requireNonNull(sex, "sex");
    return switch (ageBracket) {
      case BRACKET_CHILD -> childMilliHoursPerTick;
      case BRACKET_ADULT ->
          sex == Sex.MALE ? adultMaleMilliHoursPerTick : adultFemaleMilliHoursPerTick;
      case BRACKET_ELDER -> elderMilliHoursPerTick;
      default ->
          throw new IllegalArgumentException(
              "未知年龄档 " + ageBracket + "（合法值: 0=未成年 / 1=成年 / 2=老年）");
    };
  }
}
