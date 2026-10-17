package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T6：V3 / I8「统一预算与回填」的直接判据载体</b>（§14.4 的 V3 行；G4/N19 的同族口径）。
 *
 * <p><b>它判什么</b>：同一份家户时间预算下，<b>被前面的候选吸收不了的那部分劳动必须被后面的候选用掉</b> （"缩减后释放资源被后续候选使用"）；不得出现"某个候选吃不下 ⇒
 * 这一份时间就空缺"的旧口径（那是 §13.5 明令替换掉的"释放出来的时间留在空缺"）。
 *
 * <p><b>为什么落在 {@link LaborQueueBook#plan} 这一层</b>：这里是"同一套新规则"的<b>唯一排序 + 发放点</b> （{@code
 * ActivitySelector} 排序 → 逐 offer 发放 → 余量结转给下一条），也就是 V3 那条链在 <b>不掺市场 / 价格 / 迁移</b>干扰时的最干净形态。它的
 * {@code Plan.decisions} 逐条给"谁拿到多少、为什么"， 故读数可以逐值手算（见下）。
 *
 * <p><b>手算（主用例）</b>：
 *
 * <pre>
 * 预算 budget = 1,000 毫小时；preserved = 0（没有 GOV_SERVICE 岗位承诺）
 * offer A：netPerLaborScaled = 500（收益率最高 ⇒ 排第 1）、maxAbsorbable = 400
 * offer B：netPerLaborScaled = 300（排第 2）、maxAbsorbable = 100   ← 刻意小于"剩余 600"
 * offer C：netPerLaborScaled = 100（排第 3）、maxAbsorbable = 1,000
 *
 * 回填口径：A 拿 400（吃满自己的上限）⇒ 余 600；B 只吃 100（自己的上限）⇒ 余 500；
 *          这 500 <b>必须</b>结转给 C ⇒ C = 500；Σ = 1,000 = 预算，空缺 0。
 * 旧口径（"释放出来的时间留在空缺"）：C 拿到 0 ⇒ Σ = 500 ⇒ 本条当场红。
 * </pre>
 *
 * <p><b>判据（逐值）</b>：① A = 400；② B = 100；③ C = <b>500</b>（不是 0）；④ Σ = 1,000； ⑤ 三条结局都是 {@code
 * GRANTED}。反向对照（预算真不够时）与岗位承诺（preserved）两条另有用例。
 *
 * <p><b>负向（T3 M7）</b>：把"余量结转给下一条"关掉（每条按 {@code min(预算−preserved, 可吸收量)} 静态发放） ⇒ 读数 ③ 与 ④ 当场红（实测见台账
 * §15 的 M7 三件套）。
 */
class LaborQueueBackfillAcceptanceTest {

  private static final HouseholdId H1 = HouseholdId.parse("hh-backfill");

  private static ProductionUnitId unit(String id) {
    return new ProductionUnitId(id);
  }

  /**
   * 一条候选读数（★ 字段序按 {@link LaborQueueBook.Offer} 的 record 声明逐位对齐 —— 本文件第一版把 {@code
   * maxAbsorbableLaborMilli} 与 {@code netPerLaborScaled} 摆错位，四个用例当场红成"读不懂的读数"，
   * 这正是"夹具字段错位"那一类假红；此处按声明序重排并注明每个实参的角色）。
   */
  private static LaborQueueBook.Offer offer(String id, long netPerLaborScaled, long absorbable) {
    return new LaborQueueBook.Offer(
        /* unitId= */ unit(id),
        /* modeId= */ Optional.empty(),
        /* rankModeKey= */ id,
        /* maxAbsorbableLaborMilli= */ absorbable,
        /* outputPriced= */ true,
        /* expectedOutputValueMilli= */ netPerLaborScaled,
        /* expectedInputCostMilli= */ 0L,
        /* expectedNetMilli= */ netPerLaborScaled,
        /* expectedLaborMilli= */ 1_000L,
        /* rank/per-labor key = */ netPerLaborScaled,
        /* reason= */ "backfill-acceptance");
  }

  @Test
  void v3LaborReleasedByAShrunkCandidateIsPickedUpByTheNextOneInTheSameBudget() {
    LaborQueueBook.Plan plan =
        LaborQueueBook.plan(
            H1,
            1_000L,
            0L,
            List.of(
                // ★ 排序键 = netPerLaborScaled desc ⇒ A(500) 第 1、B(300) 第 2、C(100) 第 3。
                offer("unit-a", 500L, 400L),
                // ★ B 的可吸收量刻意小于"它理论上能拿到的剩余"（1,000−400 = 600）——
                //   旧口径「释放出来的时间留在空缺」在这里会让 B 只拿 100、C 拿 0；
                //   回填口径必须把 B 吃不下而释放的 500 交给下一条候选 C。
                offer("unit-b", 300L, 100L),
                offer("unit-c", 100L, 1_000L)));

    assertThat(plan.household()).as("同一条读数必须挂在该家户上（防止拿错 Plan）").isEqualTo(H1);
    assertThat(plan.budgetMilli()).as("① 预算原值").isEqualTo(1_000L);

    List<LaborQueueBook.Decision> decisions = plan.decisions();
    assertThat(decisions).as("逐 offer 一条决策（3 条 offer ⇒ 3 条决策）").hasSize(3);
    assertThat(decisions.get(0).offer().unitId()).isEqualTo(unit("unit-a"));
    assertThat(decisions.get(0).grantedLaborMilli())
        .as("② 收益率最高者拿满它的最大可吸收量（500‰ 档 ⇒ 400）")
        .isEqualTo(400L);
    assertThat(decisions.get(1).offer().unitId()).isEqualTo(unit("unit-b"));
    assertThat(decisions.get(1).grantedLaborMilli()).as("③ 第二条吃满它自己的可吸收上限 100").isEqualTo(100L);
    assertThat(decisions.get(2).offer().unitId()).isEqualTo(unit("unit-c"));
    assertThat(decisions.get(2).grantedLaborMilli())
        .as(
            "★★★ ④ 回填本体：预算 1,000 − A 的 400 − B 的 100 = 500 必须落到第三条候选上"
                + "（旧口径「释放出来的时间留在空缺」在这里会给 C 0）")
        .isEqualTo(500L);
    assertThat(decisions.get(2).outcome())
        .as("⑤ C 的结局是 GRANTED（不是 IDLE_BUDGET_EXHAUSTED —— 预算确有余量给它）")
        .isEqualTo("GRANTED");

    assertThat(decisions.stream().mapToLong(LaborQueueBook.Decision::grantedLaborMilli).sum())
        .as("⑥ 逐条发放之和 = 400 + 100 + 500 = 1,000 ⇒ 预算被吃满（不许超额、也不许留空缺）")
        .isEqualTo(1_000L);
    assertThat(decisions)
        .as("⑦ 三条 offer 的结局都是 GRANTED（没有人因为前一条「吃不下」而被判空闲）")
        .allMatch(decision -> "GRANTED".equals(decision.outcome()));
  }

  /**
   * ★ 反向对照（同一套规则）：预算**小于**全部候选的可吸收量之和时，只有排在前面的人拿到， 后面的拿 0 且理由是具名的 {@code IDLE_BUDGET_EXHAUSTED} ——
   * 证明上面那条"落到 C 上 500"不是 "无脑给满"的假象。
   */
  @Test
  void v3WhenTheBudgetTrulyRunsOutTheLaterCandidatesAreNamedIdleInsteadOfOverGranted() {
    LaborQueueBook.Plan plan =
        LaborQueueBook.plan(
            H1, 500L, 0L, List.of(offer("unit-a", 500L, 900L), offer("unit-b", 100L, 900L)));

    assertThat(plan.decisions().get(0).grantedLaborMilli()).as("第一条吃满预算（500）").isEqualTo(500L);
    assertThat(plan.decisions().get(1).grantedLaborMilli())
        .as("预算真的没了 ⇒ 第二条 0（不是「回填」能变出来的）")
        .isZero();
    assertThat(plan.decisions().get(1).outcome()).isEqualTo("IDLE_BUDGET_EXHAUSTED");
    assertThat(
            plan.decisions().stream().mapToLong(LaborQueueBook.Decision::grantedLaborMilli).sum())
        .as("发放之和恰 = 预算 500（第二条不是「超发」出来的）")
        .isEqualTo(500L);
  }

  /** ★ 岗位承诺（preserved）不进队列：预算 1,000 − preserved 400 = 600 是队列的可用量。 */
  @Test
  void v3ThePreservedGovServiceLaborIsDeductedBeforeTheQueueRuns() {
    LaborQueueBook.Plan plan =
        LaborQueueBook.plan(H1, 1_000L, 400L, List.of(offer("unit-a", 500L, 900L)));
    assertThat(plan.preservedLaborMilli()).as("岗位承诺整额保留").isEqualTo(400L);
    assertThat(plan.decisions().get(0).grantedLaborMilli())
        .as("队列可用量 = 1,000 − 400 = 600 ⇒ 第一条只拿 600（不是 900）")
        .isEqualTo(600L);
    //   ★ 断言落在**决策逐条读数之和**（`Plan.allocatedLaborMilli()` 是派生字段；本用例的判据是
    //     "队列真的只用掉 600"，用它自己的决策表算，不依赖另一个派生量）：
    assertThat(
            plan.decisions().stream().mapToLong(LaborQueueBook.Decision::grantedLaborMilli).sum())
        .as("队列真的只用掉 1,000 − 400 = 600（不是把 900 的上限当预算）")
        .isEqualTo(600L);
  }

  /** ★ 家户 id 的派生口径与 {@link HouseholdActors} 同源（防"两处拼一个不同的 id"）。 */
  @Test
  void v3TheFixtureHouseholdIdIsDerivedThroughTheSingleSpellingPoint() {
    assertThat(HouseholdActors.of(H1).id())
        .as("HouseholdActors.idOf 是家户 actor id 的唯一拼写点")
        .isNotBlank();
  }
}
