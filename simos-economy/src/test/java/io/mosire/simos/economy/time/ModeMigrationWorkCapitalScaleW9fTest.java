package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W9f 测试批（2026-10-11）：W9-fix2 的两个<b>唯一拼写点</b>的逐值判据 + 两道闸的边界</b>。
 *
 * <p>★★ <b>为什么单开一类（它补的是 W9-fix2 的判据缺口，不是重复既有覆盖）</b>：W9-fix2 把"这一笔需要多少作业资本"收成一个拼写点 {@link
 * ModeMigrationPolicy#requiredWorkCapitalScale(IndustryDefinitionView,
 * long)}，计划期<b>预留</b>与执行期<b>索取</b>共用它， 并把"本笔随迁人口带来的劳动"收成第二个拼写点 {@link
 * ModeMigrationPolicy#laborCarriedByMoveUpperBound(long, long, long)}。W9-fix2
 * 批<b>一条测试都没跑</b>（派单明令）， 而本批实跑全仓 3,464 条 0 红 ⇒ 反过来说明：<b>这两个拼写点在既有测试里根本没有入口</b>（全仓只有 {@code
 * ModeMigrationPolicyD024Test} 一处调 {@code ModeMigrationPolicy.plan(...)}，而它的目标格走的是"既有产业"支、 不进按需
 * trade 支）。所以这一类的存在理由是"把 W9-fix2 修掉的那条具名不一致钉成可执行判据"。
 *
 * <p>★★★ <b>判据出处（逐条）</b>：
 *
 * <ol>
 *   <li><b>W9-fix2 账本 §3.2</b>（本仓工作树）：唯一拼写点 = {@code
 *       requiredWorkCapitalScale(IndustryDefinitionView, long)}，算式逐字 = {@code laborPerUnit <= 0 ? 1
 *       : max(1, laborMilli / laborPerUnit)}；
 *   <li><b>W9-fix2 账本 §3.3</b>（两种落点的尺度算式表）：并入既有户 ⇒ 两侧都不索取/不预留（都 = 0）；新建户 ⇒ 计划期预留 {@code
 *       requiredWorkCapitalScale(模板, laborCarriedByMoveUpperBound(源户))}、执行期索取 {@code
 *       requiredWorkCapitalScale(模板, 本笔迁入劳动)} ⇒ <b>同式、同量</b>；
 *   <li><b>W9-fix2 账本 §3.1</b>（真凶）：三种读法里唯一正确的是"<b>执行期真的会读到的那一行劳动</b>"（新建户 = {@code 0 +
 *       本笔迁入劳动}）；逐字读落盘行（恒 0）会得到 scale = 1 —— three-powers day=270 的 {@code labor=8857} ⇒ 执行期 scale
 *       = 8、计划期只预留 1 份 ⇒ 8 倍缺口 ⇒ HTTP 500；
 *   <li><b>W9-fix 账本 §3②</b> + <b>控制方裁定 v3.32 §7 第 2 条</b>：人口闸 {@code MIGRATION_SPEED_CAP_PER_MILLE
 *       = 100} ⇒ 每市场轮每户可转人口 ≤ {@code max(1, ⌊人口 × 100 ÷ 1000⌋)}，与收益差速度<b>分开</b>；
 *   <li><b>W9-fix 账本 §3④</b> + <b>v3.32 §7 第 4 条</b>（U5）：规模 = 0 ⇒ 最低档；最低档<b>不设门槛</b>（"没有库存也要能转业"）。
 * </ol>
 *
 * <p>★★ <b>本类的算式一律"两侧都算出来"（不写死被测值）</b>：期望值用与 main 同形但<b>独立重写</b>的算式现算，夹具一改期望跟着改。 唯一"写死"的是 <b>8857 ⇒
 * 8 ⇒ 960</b> 那一组 —— 那是 three-powers day=270 真世界现场的三个数（{@code labor=8857} / trade 模板 {@code
 * laborPerUnit=1000} / {@code capacityPerUnit={TOOL:120}}），它同时是回归锚。
 */
class ModeMigrationWorkCapitalScaleW9fTest {

  private static final HexCoord H1 = new HexCoord(0, 0);

  /** 目标格（非城市格）：按需 trade 模板的落点，与真世界 {@code trade@2_0} 同形（本格没有 trade 记录）。 */
  private static final HexCoord H2 = new HexCoord(1, 0);

  private static final IndustryId CUSTOM =
      IndustryHexKeys.id("custom-labour-per-unit", H1.q(), H1.r());

  // ── ① 拼写点一：requiredWorkCapitalScale ──────────────────────────────────────────────

  /**
   * ★★★ <b>判据 1：{@code requiredWorkCapitalScale} 的逐值表（含 {@code max(1, …)} 下限与向下取整）</b>。
   *
   * <pre>
   * scale(labor) = max(1, ⌊labor ÷ laborPerUnit⌋)        （laborPerUnit &gt; 0）
   * 需要量        = scale × capacityPerUnit[每一维]       （索取/预留那一侧各自按此乘）
   * </pre>
   *
   * <p>★ <b>为什么 {@code max(1, …)} 必须在这里（而不是"labor 不够就 0"）</b>：计划期的 labor 入参是 {@code
   * laborCarriedByMoveUpperBound(...)}（一个上界估计），执行期读到的是本笔迁入劳动；两者都可能 &lt; {@code laborPerUnit}。下限 1 =
   * "最低也要按 1 个单位规模预留" —— 与"新建户从 1 单位起步、随作业资本长大"同一条口径。 ★ 真实锚：{@code labor=8857} ⇒ {@code ⌊8857 ÷
   * 1000⌋ = 8} ⇒ 工具需要量 {@code 8 × 120 = 960} 毫（计划期少预留 1 份 ⇒ 执行期守卫抛的那笔）。
   */
  @Test
  void requiredWorkCapitalScaleIsTheFloorOfLaborOverPerUnitWithAFloorOfOne() {
    IndustryDefinitionView trade = MerchantTradeTemplate.industry(H2);
    long laborPerUnit = trade.recipe().laborPerUnit();
    long toolPerUnit = trade.recipe().capacityPerUnit().get(AssetKind.SMALL_TOOL);

    assertThat(laborPerUnit).as("★ 前置：trade 模板的劳动/规模是唯一拼写点给的（不写死）").isPositive();
    assertThat(toolPerUnit).as("★ 前置：作业资本那一维 = TOOL").isNotZero();

    long[] labors = {0L, 1L, 999L, 1_000L, 1_001L, 1_999L, 2_000L, 8_857L, 9_000L};
    for (long labor : labors) {
      long expected = Math.max(1L, labor / laborPerUnit);
      long actual = ModeMigrationPolicy.requiredWorkCapitalScale(trade, labor);
      assertThat(actual)
          .as(
              "scale(劳动 %d) 必须 == max(1, ⌊%d ÷ %d⌋) = max(1, %d) = %d",
              labor, labor, laborPerUnit, labor / laborPerUnit, expected)
          .isEqualTo(expected);
      assertThat(Math.multiplyExact(actual, toolPerUnit))
          .as("需要量 = scale × %d（这一乘在索取/预留两侧各拼一次，算式同源）", toolPerUnit)
          .isEqualTo(Math.multiplyExact(expected, toolPerUnit));
    }

    // ★ 真世界回归锚（three-powers day=270）：labor=8857、laborPerUnit=1000 ⇒ scale=8、need=8×120=960 毫工具。
    assertThat(ModeMigrationPolicy.requiredWorkCapitalScale(trade, 8_857L))
        .as(
            "★ 真世界 day=270 现场：⌊8857 ÷ %d⌋ = 8（改前计划期按落盘行 labor=0 只预留 1 份 ⇒ 8 倍缺口 ⇒ HTTP 500）",
            laborPerUnit)
        .isEqualTo(8L);
    assertThat(
            Math.multiplyExact(
                ModeMigrationPolicy.requiredWorkCapitalScale(trade, 8_857L), toolPerUnit))
        .as(
            "★ 现场需要量 = 8 × %d = 960 毫工具（与 ModeMigrationSettlement 的 tried=[…:scale=8,need=…] 同一算式）",
            toolPerUnit)
        .isEqualTo(960L);
  }

  /**
   * ★★★ <b>判据 2：{@code laborPerUnit ≤ 0} 的产业 ⇒ scale 恒 1（不除零、也不为负）</b>。
   *
   * <p>口径出处：W9-fix2 账本 §3.2 的算式第一支（{@code laborPerUnit <= 0L ? 1L : …}）。它在真世界的入口 = 旧档/未裁决产业 （{@code
   * laborPerUnit = 0} 的模板）—— 那种模板没有"每单位规模要多少劳动"的定义，唯一 fail-closed 的一侧是"按 1 个单位规模预留"，
   * 而不是"不预留"（后者会让执行期去索取一份计划期没留的资产 ⇒ 撞守卫）或者"崩"（除零）。
   *
   * <p>★ 反面对照（判别力）：同一个 {@code labor = Long.MAX_VALUE} 在 {@code laborPerUnit = 1} 时 scale 巨大 ⇒
   * 证明"这里返回 1"不是因为 labor 小，而是因为 {@code laborPerUnit ≤ 0} 那一支。
   */
  @Test
  void requiredWorkCapitalScaleTreatsNonPositiveLaborPerUnitAsASingleUnit() {
    // ★★ 实跑更正（本批，第一轮红点）：{@code laborPerUnit < 0} **根本构造不出来** —— {@code IndustryDefinitionView}
    // 的规范构造器
    //   自己 fail-closed（`IndustryDefinitionView.laborPerUnit 不得为负:
    // -1`，IndustryDefinitionView.java:280）⇒ 拼写点里 {@code ≤ 0} 那一支的
    //   "负"半边是**结构性不可达**的防御分支，唯一可达的入口 = {@code laborPerUnit == 0}（旧档/未裁决模板）。
    //   这里把这个事实**钉成断言**（而不是把负数从表里悄悄删掉）：① 0 那一支逐值；② 负数当场被构造器拒。
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> industry(-1L))
        .as(
            "★ IndustryDefinitionView 自己就拒绝负 laborPerUnit ⇒ requiredWorkCapitalScale 的 '≤ 0' 里只有 '== 0' 可达")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("laborPerUnit");

    for (long laborPerUnit : new long[] {0L}) {
      IndustryDefinitionView noLaborUnit = industry(laborPerUnit);
      for (long labor : new long[] {0L, 1L, 999L, 1_000_000L, Long.MAX_VALUE}) {
        assertThat(ModeMigrationPolicy.requiredWorkCapitalScale(noLaborUnit, labor))
            .as("laborPerUnit=%d（≤ 0）⇒ scale 恒 1，对 labor=%d 也一样（不除零、不回绕）", laborPerUnit, labor)
            .isEqualTo(1L);
      }
    }
    // ★ 反面对照：laborPerUnit = 1 ⇒ scale 随 labor 增长（证明上面那组不是"恒 1"的假绿）。
    IndustryDefinitionView perUnitOne = industry(1L);
    assertThat(ModeMigrationPolicy.requiredWorkCapitalScale(perUnitOne, 5L))
        .as("laborPerUnit=1、labor=5 ⇒ ⌊5 ÷ 1⌋ = 5")
        .isEqualTo(5L);
    assertThat(ModeMigrationPolicy.requiredWorkCapitalScale(perUnitOne, 0L))
        .as("laborPerUnit=1、labor=0 ⇒ max(1, 0) = 1（下限仍在）")
        .isEqualTo(1L);
  }

  // ── ② 拼写点二：laborCarriedByMoveUpperBound（计划期劳动估计）──────────────────────────

  /**
   * ★★★ <b>判据 3：{@code laborCarriedByMoveUpperBound} 的逐值表（向上取整 + 源户人口 ≤ 0 的兜底）</b>。
   *
   * <pre>
   * ⌈源户劳动 × 迁入人口 ÷ 源户人口⌉       （源户人口 ≤ 0 ⇒ take，与改前同：不按 1:1 猜劳动）
   * 商/余数形态，不用 (numerator + denominator - 1) / denominator —— 后者在 numerator 接近 Long.MAX_VALUE 时先溢出成负数
   * </pre>
   *
   * <p>口径出处：W9-fix2 账本 §3.2 第二半 + §9.3（"语义有歧义 ⇒ 按较大者预留"，控制方裁定）。★ 为什么向上取整： 执行期的 {@code laborTake}
   * 是在<b>运行中的剩余量</b>上逐笔向下取整的（余数会累积），⇒ 执行期读数可能比"按当日初值算的比例值"高至多几毫劳动； 计划期取上界 ⇒ 预留 ≥ 索取（fail-closed
   * 的那一侧）。
   */
  @Test
  void laborCarriedByMoveUpperBoundRoundsUpAndFallsBackToTakeWithoutPopulation() {
    record Row(long sourceLabor, long sourcePopulation, long take, long expected, String why) {}
    List<Row> rows =
        List.of(
            new Row(1_000L, 100L, 1L, 10L, "⌈1000×1÷100⌉ = 10（整除）"),
            new Row(1_000L, 100L, 3L, 30L, "⌈1000×3÷100⌉ = 30"),
            new Row(1L, 3L, 1L, 1L, "⌈1×1÷3⌉ = 1（不足 1 毫 ⇒ 进 1）"),
            new Row(10L, 4L, 1L, 3L, "⌈10÷4⌉ = ⌈2.5⌉ = 3（向下会得 2 ⇒ 这一格就是 floor/ceil 的分界）"),
            new Row(4L, 2L, 1L, 2L, "⌈4÷2⌉ = 2（整除）"),
            new Row(0L, 100L, 5L, 0L, "源户劳动 0 ⇒ 0（不凭空造劳动）"),
            new Row(1_000L, 100L, 0L, 0L, "迁入人口 0 ⇒ 0"),
            new Row(999L, 1L, 1L, 999L, "整户就 1 人 ⇒ ⌈999÷1⌉ = 999"),
            new Row(
                Long.MAX_VALUE,
                1L,
                1L,
                Long.MAX_VALUE,
                "★ numerator = Long.MAX_VALUE 也不溢出（商/余数形态）"),
            new Row(7L, 0L, 7L, 7L, "源户人口 0 ⇒ 兜底 = take（不按 1:1 猜，也不除零）"),
            new Row(7L, -3L, 4L, 4L, "源户人口 &lt; 0 ⇒ 兜底 = take"));

    for (Row row : rows) {
      assertThat(
              ModeMigrationPolicy.laborCarriedByMoveUpperBound(
                  row.sourceLabor(), row.sourcePopulation(), row.take()))
          .as(
              "labor=%d 人口=%d take=%d ⇒ %s",
              row.sourceLabor(), row.sourcePopulation(), row.take(), row.why())
          .isEqualTo(row.expected());
    }
  }

  /**
   * ★★★ <b>判据 4（对偶的一半，"计划期估计 ≥ 执行期实际"）：上界 ≥ 同式的向下取整，且最多多 1 毫劳动</b>。
   *
   * <p>执行期的 {@code laborTake} 用同一比例式的<b>向下取整</b>（{@code ⌊剩余劳动 × 本笔人口 ÷ 剩余人口⌋}）。⇒ 判据 = {@code ub ≥
   * floor} 且 {@code ub − floor ≤ 1}（"按较大者预留"的代价上界具名：至多多预留 1 毫劳动 ⇒ 只在恰好落在规模边界下 1 毫内时才多预留 1 个单位规模）。
   *
   * <p>★ <b>为什么这不是恒真的装饰</b>：把上界换成"向下取整"（W9-fix2 之前那一侧）会让 {@code ub − floor == 0} 的行仍然通过、 而下面 {@code
   * ub &gt; floor} 的行当场红 —— 后者在表里有 4 行（{@code 1/3}、{@code 10/4}、{@code 1000/3 取 2}、…）。
   * 本测试的第一条断言就要求"存在严格大于的行"，把"这一族是否还有判别力"钉住。
   */
  @Test
  void thePlanSideLaborEstimateIsNeverBelowTheExecutionSideFloorAndNeverAboveItByMoreThanOne() {
    long strictlyAbove = 0L;
    for (long sourceLabor : new long[] {0L, 1L, 7L, 999L, 1_000L, 8_857L, 100_000L}) {
      for (long population : new long[] {1L, 3L, 4L, 100L, 1_000L}) {
        for (long take : new long[] {0L, 1L, 2L, 3L, 17L, 100L}) {
          long numerator = Math.multiplyExact(sourceLabor, take);
          long floor = numerator / population;
          long ub = ModeMigrationPolicy.laborCarriedByMoveUpperBound(sourceLabor, population, take);
          assertThat(ub)
              .as(
                  "上界不得低于执行期同式的向下取整：labor=%d 人口=%d take=%d ⇒ floor=%d、ub=%d",
                  sourceLabor, population, take, floor, ub)
              .isGreaterThanOrEqualTo(floor);
          assertThat(ub - floor)
              .as("向上取整的代价 ≤ 1 毫劳动（labor=%d 人口=%d take=%d）", sourceLabor, population, take)
              .isLessThanOrEqualTo(1L);
          if (ub > floor) {
            strictlyAbove++;
          }
        }
      }
    }
    assertThat(strictlyAbove)
        .as("★ 判别力自检：这一族里必须有严格大于 floor 的行（否则 ① 这条判据在'向下取整'的实现下也会全绿）")
        .isPositive();
  }

  // ── ③ 人口闸 + U5 档位：两个拼写点与它们的边界 ─────────────────────────────────────────

  /**
   * ★★★ <b>判据 5：人口闸 {@code MIGRATION_SPEED_CAP_PER_MILLE = 100} 的逐值 + 与速度折算的取小（含边界与负向）</b>。
   *
   * <pre>
   * 本户本轮可转人口 = min( moveablePopulation(人口, 速度), moveablePopulation(人口, 上限) )
   * moveablePopulation(p, s) = p ≤ 0 ∨ s ≤ 0 ? 0 : min(p, max(1, ⌊p × s ÷ 1000⌋))
   * </pre>
   *
   * <p>口径出处：W9-fix 账本 §3② + v3.32 §7 第 2 条；{@code ModeMigrationPolicy.java:744-746}（两个调用点取小）。 ★ 与
   * {@code ModeMigrationPolicyW9Test#theMigrationSpeedCapClampsTheMoveablePopulationPerPlanRound}
   * 的分工：那一张表验"两道闸各自折算多少"， 这一张表验<b>取小的边界</b>（上限刚好等于速度、上限比速度大 1 毫、上限比速度小 1 毫）与<b>退化输入</b>（人口 0 / 速度 0
   * / 负人口）。
   */
  @Test
  void thePopulationGateIsTheSmallerOfTheSpeedFoldAndTheCapFold() {
    long cap = ModeMigrationPolicy.MIGRATION_SPEED_CAP_PER_MILLE;
    assertThat(cap).as("★ 前置：上限是 W9-fix 的政策预设 100‰（控制方裁定 v3.32 §7 第 2 条）").isEqualTo(100L);

    record Row(long population, long speedPerMille, long expectedEffective, String why) {}
    List<Row> rows =
        List.of(
            new Row(200L, 1_000L, 20L, "速度折算 200、上限折算 20 ⇒ 上限吃紧"),
            new Row(200L, 200L, 20L, "速度折算 40、上限折算 20 ⇒ 上限吃紧"),
            new Row(200L, 101L, 20L, "速度折算 20、上限折算 20 ⇒ 相等（边界）"),
            new Row(200L, 100L, 20L, "速度折算 20 = 上限折算 20（上限刚好等于速度）"),
            new Row(200L, 99L, 19L, "速度折算 19 &lt; 上限折算 20 ⇒ 速度吃紧（上限不生效）"),
            new Row(1_000L, 1_000L, 100L, "速度折算 1000、上限折算 100 ⇒ 上限把整户 10% 钉住"),
            new Row(9L, 100L, 1L, "⌊9×100÷1000⌋ = 0 ⇒ 地板 1 人"),
            new Row(10L, 100L, 1L, "⌊10×100÷1000⌋ = 1"),
            new Row(999L, 100L, 99L, "⌊999×100÷1000⌋ = 99"),
            new Row(1_000L, 0L, 0L, "速度 0 = 不转业 ⇒ 0 人（与'地板 1 人'不矛盾：地板只在速 &gt; 0 时生效）"),
            new Row(0L, 1_000L, 0L, "没有人口的壳户 ⇒ 0 人"),
            new Row(-5L, 1_000L, 0L, "负人口（脏数据）⇒ 0 人，不回绕"));

    for (Row row : rows) {
      long bySpeed = ModeMigrationPolicy.moveablePopulation(row.population(), row.speedPerMille());
      long byCap = ModeMigrationPolicy.moveablePopulation(row.population(), cap);
      long effective = Math.min(bySpeed, byCap);
      assertThat(effective)
          .as(
              "人口=%d 速度=%d‰ ⇒ min(速度折算 %d, 上限折算 %d) = %d（%s）",
              row.population(),
              row.speedPerMille(),
              bySpeed,
              byCap,
              row.expectedEffective(),
              row.why())
          .isEqualTo(row.expectedEffective());
    }

    // ★ 负向（"上限确实在夹"）：把它当成普通速度再折一次 ⇒ 逐值等于上面的取小结果（同一把尺）。
    assertThat(ModeMigrationPolicy.moveablePopulation(200L, 1_000L))
        .as("不加闸时整户 200 人都会走（W9 首版实测：单笔最多搬 199 人）")
        .isEqualTo(200L);
    assertThat(
            Math.min(
                ModeMigrationPolicy.moveablePopulation(200L, 1_000L),
                ModeMigrationPolicy.moveablePopulation(200L, cap)))
        .as("★ 加了上限之后同一户一轮最多 20 人 = 人口的 10%")
        .isEqualTo(20L);
  }

  /**
   * ★★★ <b>判据 6（U5 与拼写点一的接缝）：规模 = 0 ⇒ 最低档（最低档不设门槛），但"作业资本该留多少"仍按 {@code max(1, …)} 算</b>。
   *
   * <p>这两条<b>不是同一条口径</b>，本用例刻意把它们并排钉住（混起来就会读错真世界 day=270 那笔）：
   *
   * <ul>
   *   <li><b>档位</b>（{@link ModeMigrationPolicy#supportedTierIndex}）：分母是 {@code max(0, ⌊labor ÷
   *       laborPerUnit⌋)} —— 规模 = 0 ⇒ 直接返回 0（贫农档）。★ 这是 W9-fix 的 U5：改前"规模 0 ⇒ 每档额外规模都是 0 ⇒ 天然支撑"⇒
   *       循环把 {@code labor &lt; laborPerUnit} 的极小户抬到<b>最高档</b>（{@code tier(trade, 999, 0) == 3}）。
   *   <li><b>作业资本</b>（{@link ModeMigrationPolicy#requiredWorkCapitalScale}）：{@code max(1, ⌊labor ÷
   *       laborPerUnit⌋)} —— 同一个小户<b>仍要按 1 个单位规模预留</b>（它一旦要建企业，执行期索取的就是 1 × capacityPerUnit）。
   * </ul>
   *
   * <p>⇒ 判据：{@code tier == 0} 与 {@code scale == 1} 同时成立；载入任何数量的作业资本都不会把档位抬起来（劳动撑不起 1 个单位规模时，
   * "能当地主"与"能建起 1 个单位规模"是矛盾的）；而 {@code labor = laborPerUnit} 时档位开始可能 &gt; 0（反面对照，证明不是"恒 0"假绿）。
   */
  @Test
  void aHouseholdBelowOneLabourUnitLandsTheBottomTierButStillReservesOneWorkCapitalUnit() {
    IndustryDefinitionView trade = MerchantTradeTemplate.industry(H2);
    long laborPerUnit = trade.recipe().laborPerUnit();
    long belowOneUnit = laborPerUnit - 1L;

    for (long carried : new long[] {0L, 120L, 7_000L, 200_000L}) {
      assertThat(
              ModeMigrationPolicy.supportedTierIndex(
                  trade, belowOneUnit, Map.of(AssetKind.SMALL_TOOL, carried)))
          .as(
              "labor=%d（&lt; laborPerUnit=%d ⇒ 规模 0）× 携带工具 %d ⇒ 档位必须 == 0（U5：规模 0 ⇒ 最低档）",
              belowOneUnit, laborPerUnit, carried)
          .isZero();
    }
    assertThat(ModeMigrationPolicy.requiredWorkCapitalScale(trade, belowOneUnit))
        .as(
            "★ 同一户的作业资本尺度仍 == max(1, ⌊%d ÷ %d⌋) = 1（最低档不设门槛 ≠ 建企业不用作业资本）",
            belowOneUnit, laborPerUnit)
        .isEqualTo(1L);

    // ★ 反面对照（判别力）：labor = 1 个规模单位 且 带足作业资本 ⇒ 档位可以 &gt; 0（证明上面那组不是"恒 0"的假绿）。
    long highCarried =
        Math.multiplyExact(1_000L, trade.recipe().capacityPerUnit().get(AssetKind.SMALL_TOOL));
    assertThat(
            ModeMigrationPolicy.supportedTierIndex(
                trade, laborPerUnit, Map.of(AssetKind.SMALL_TOOL, highCarried)))
        .as("labor=1 单位规模 × 带足 %d 毫工具 ⇒ 档位允许 &gt; 0（否则 U5 那条判据是假的）", highCarried)
        .isGreaterThan(0);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  /**
   * 一个"每 1 单位规模需要 1 毫工具"的极小产业：只用来验 {@code laborPerUnit ≤ 0} 那一支与下限（★ 它的 {@code capacityPerUnit} 是 1
   * 而不是 trade 的 120，免得把两个数读混）。
   */
  private static IndustryDefinitionView industry(long laborPerUnit) {
    return IndustryViews.industry(
        CUSTOM,
        "custom-labor-per-unit",
        new RegimeId("household"),
        10L,
        Map.of(AssetKind.SMALL_TOOL, 1L),
        Map.of(),
        0L,
        laborPerUnit,
        Map.of(EconomySettlement.GRAIN, 1L),
        new LinkedHashMap<>(),
        List.of(new ClassSlot(new SocialClassId("poor_peasant"), "贫农", 950)),
        new AllocationRule.Split(500, 500));
  }
}
