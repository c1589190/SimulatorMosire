package io.mosire.simos.app.crisis;

import static io.mosire.simos.app.world.PopulationEconomyFixture.DESERT;
import static io.mosire.simos.app.world.PopulationEconomyFixture.advanced;
import static io.mosire.simos.app.world.PopulationEconomyFixture.seeded;
import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.world.PopulationEconomyFixture;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ★★ **B2 regression：实时危机指标必须与"你在周期的第几天读"无关** （S1 spec §十 的第二条不变量）。
 *
 * <p>★★ **修之前的病**：{@code CrisisMonitor.lightsAt} 的**分子**是"本周期**至今**累计的 {@code unmetNeed}"（{@code
 * FlowRow} 在新周期第一天归零、此后逐日累加），而**分母**是 {@code cumulativeRationMilli(pop, cycleDays)} —— **整个周期**的需求。
 * ⇒ 周期第 1 天时分子只累计了 1 天、分母却已按 120 天算 ⇒ **满足率被严重高估** ⇒ **一场持续危机在周期切换后会暂时读成"没有危机"**。
 *
 * <p>★ **为什么这是 bug 而不是"读法问题"**：该指标的设计语义就是**实时**红灯 （v3 spec §七 原文："按**当前**需求满足水平……向 GM 出红灯"），且它挂在实时的
 * {@code /api/social/population} 读口上，javadoc 也写"当场量到的数字"。
 *
 * <p>★ **夹具**：(1,0) 沙漠格**可耕地系数为 0** ⇒ 从第 1 天起就吃不饱、且永远吃不饱 ⇒ "缺粮"是**持续**的而不是脉冲式的 —— 这正是不变量该成立的情形。
 *
 * <p>★ **判别力**：把分母改回 {@code cycleDays} ⇒ 第 1 天读到的类别集合变空 ⇒ 本条红。
 */
class CrisisMonitorPhaseTest {

  /**
   * ★★ **本类只比"由满足率判定"的类别**（{@code FOOD} / {@code CLOTH} / {@code DEBT}）。
   *
   * <p>★★ {@code MORTALITY} 与 {@code LABOR_BURDEN} **不在此列**，这不是疏忽：前者的分子是
   * "本周期**至今累计**死亡"、分母是**当前人口** ⇒ 它**本来就该随相位增长**（第 119 天当然比第 1 天死得多） ——
   * 把它卷进"相位不变"的断言会把一个**正确行为**判成红。B2 的病只出在 "分子是**至今**累计、分母却是**整周期**"的那几类上。
   */
  private static final Set<String> SATISFACTION_BASED = Set.of("FOOD", "CLOTH", "DEBT");

  /**
   * 同一场持续缺粮，在**同一个周期的三个相位**读，**红灯类别集合必须相同**。
   *
   * <p>★★ **窗口为什么取第 2 个周期**：第 1 个周期里沙漠格**靠创世库存过活**（实测：第 1 天只报 {@code CLOTH}、到第 119 天才报 {@code
   * FOOD}）—— 那时"缺粮"还没开始，三个相位**本来就不该一致**。 到第 2 周期创世库存已耗尽，才是真正的"**从周期第 1 天起就缺**"，正是本条不变量该成立的情形。
   *
   * <p>★ 三个相位各自从创世推进到该天数（121 / 180 / 239）⇒ 它们是第 2 个周期的第 1、60、119 天 （{@code cycleDays = 120}）。
   */
  @Test
  void crisisKindsAreInvariantAcrossCyclePhase() {
    Set<String> day1 = kindsAt(advanced(seeded(), 121L));
    Set<String> day60 = kindsAt(advanced(seeded(), 180L));
    Set<String> day119 = kindsAt(advanced(seeded(), 239L));

    assertThat(day119).as("前提：周期末确实报着红灯（否则本条测不出东西）").isNotEmpty();
    assertThat(day1).as("★★ 同一场持续危机，周期第 1 天读到的类别必须和周期末一致").isEqualTo(day119);
    assertThat(day60).as("★ 第 60 天同理").isEqualTo(day119);
  }

  /**
   * ★★ **关账日不是"周期第一天"** —— 分母必须取**整周期**，不能取 1 天。
   *
   * <p>★★ **为什么这条必须单独钉**：{@code progressDays == 0} 有**两种**截然不同的状态， 只看这个字段分不开：
   *
   * <table>
   *   <caption>两种 progressDays == 0</caption>
   *   <tr><th>状态</th><th>progressDays</th><th>FlowRow 流水</th><th>正确的分母</th></tr>
   *   <tr><td>创世（tick 0）</td><td>0</td><td>全 0</td><td>无所谓（unmet == 0 ⇒ 满足率恒 1000‰）</td></tr>
   *   <tr><td><b>关账那一天的 revision</b></td><td><b>0</b>（收获那一支已归零）</td>
   *       <td><b>刚关账那一整个周期</b>（清零发生在<b>次日</b>）</td><td><b>cycleDays</b></td></tr>
   * </table>
   *
   * <p>★ 第二行有两个既有用例钉着：{@code EconomyFlowCycleTest.theClosingDayCarriesTheWholeCyclesIncome} （关账后
   * {@code progressDays} 归零、而 {@code income} 是一整个周期的毛产）与 {@code
   * theFirstDayOfANewCycleStartsEveryFieldFromZero}（清零在**次日**）。
   *
   * <p>★★ **判别力**：若把分母写成"周期第 0 天按 1 天算"，本条的 {@code elapsedDays} 会是 **1** ⇒ 红。 那一版会让"整周期缺口 ÷ 1
   * 天需求"算出 **0‰** 的满足率 ⇒ **全境假阳性红灯**。
   */
  @Test
  void closingDayReadsTheWholeCycleNotOneDay() {
    PopulationEconomyFixture.Fixture closing = advanced(seeded(), 240L); // 第 2 周期关账日

    assertThat(closing.economy().industries().values().iterator().next().progressDays())
        .as("夹具前提：关账日的 progressDays 确实已归零")
        .isZero();

    Long elapsed =
        CrisisMonitor.lightsAt(DESERT, closing.economy(), closing.social(), closing.tick()).stream()
            .map(light -> light.evidence().get("elapsedDays"))
            .filter(java.util.Objects::nonNull)
            .map(v -> ((Number) v).longValue())
            .findFirst()
            .orElse(null);
    assertThat(elapsed).as("★★ 关账日：流水是整周期的 ⇒ 分母必须是 cycleDays(120)，不是 1").isEqualTo(120L);
  }

  /** 沙漠格在 {@code f.tick()} 这一刻的、**由满足率判定**的红灯类别集合（见 {@link #SATISFACTION_BASED}）。 */
  private static Set<String> kindsAt(PopulationEconomyFixture.Fixture f) {
    return CrisisMonitor.lightsAt(DESERT, f.economy(), f.social(), f.tick()).stream()
        .map(light -> light.kind().name())
        .filter(SATISFACTION_BASED::contains)
        .collect(Collectors.toSet());
  }
}
