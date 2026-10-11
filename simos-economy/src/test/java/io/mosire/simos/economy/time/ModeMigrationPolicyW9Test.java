package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W9（2026-10-11）判据测试：转业速度 = 收益差驱动 + 按库存定档 + 带得走的作业资本</b>。
 *
 * <p>★★ <b>判据出处（逐条）</b>：
 *
 * <ol>
 *   <li><b>用户原话（最高权威）</b>：① 「<b>没有生产工具不会买吗？</b>」② 「<b>没有库存就要么去当前生产方式的最低档…为啥要拦着？</b>」 ③
 *       「<b>只要其他生产方式比目前生产方式预期收益更高，就产生转业速度，差越大越快</b>」 ④
 *       「<b>债务规模决定本生产方式内阶层跌落速度，生产方式收益决定生产方式间转业速度</b>」 （设计书 {@code
 *       2026-10-10-unified-price-and-ordered-settlement-design.md} §7 v3.29/v3.30/v3.31）；
 *   <li><b>派单 ①</b>：删 {@code MIGRATION_PER_MILLE} 与 {@code max(10‰, f(债务/资产))}，唯一算式 {@code
 *       profitGapSpeedPerMille}；<b>速度逐目标</b>；
 *   <li><b>派单 ④</b>：{@code supportedTierIndex} / {@code carriedCapitalByKind} / {@code
 *       tierPosition}； 逐值表 0 / 2,000 / 7,000 / 200,000 毫工具 ⇒ 档 0 / 0 / 2 / 3；
 *   <li><b>派单 ②</b>：最低档需求 ≡ 0（"零库存也能转业"是结构必然，不是特例分支）。
 * </ol>
 *
 * <p>★ <b>本类只测"纯判据"（逐值表 + 边界 + 结构）</b>：计划级/执行级的用例分别在 {@link
 * ModeMigrationPolicyD024Test}（债务不驱动转业）、{@link MerchantOnDemandTradeW7Test}（缺工具可买/租）与 {@link
 * ModeMigrationInventoryCarryW9Test}（库存随迁守恒）里。
 *
 * <p>★ <b>独立复述算式</b>：下面每个期望值都由测试侧**独立复述一遍算式**再与 main 对照（不是抄 main 的输出），
 * 逐值表里的数则直接写死（它们是本批的验收判据本身）。关键项的变异自证记录见批账本。
 */
class ModeMigrationPolicyW9Test {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;

  // ── §A 转业速度 = 预期收益差驱动（逐值表）────────────────────────────────────────────────

  /**
   * ★★★ <b>判据①：速度映射的逐值表</b>（用户原话③「差越大越快」+ 控制方冻结形状）。
   *
   * <pre>
   * gap = 收益_其他 − 收益_当前
   * gap ≤ 0 ⇒ 0（**不转业**）
   * gap &gt; 0 ⇒ clamp(⌊SLOPE × gap ÷ 分母⌋, 1, 1000)，SLOPE = 100（政策常量）
   * 分母（★★ W9-fix，2026-10-11）= max(|收益_当前|, |收益_其他|, 收益规模基准)
   * </pre>
   *
   * <p>★★★ <b>为什么分母必须含 |收益_其他|（W9-fix 的判据面）</b>：旧口径 {@code max(1, |收益_当前|)} 在 {@code 收益_当前 ≈ 0}
   * 时退化成 1 ⇒ 打满 1000‰ ⇒ 一笔搬走整户（W9d 实测：three-powers 0→240 出现 21 个零成交轮、Σfills −44%）。 本文件调的是**两参**薄封装
   * ⇒ 收益规模基准 = 0（生产路径走三参形态，基准见 {@link
   * #theProfitScaleBasisKeepsTheZeroProfitDenominatorFromSaturating()}）。
   *
   * <p>★ 逐值表的每个期望值旁边写着**手算算式**（可自证）；分母口径一改，这里就按新算式逐条重算。
   */
  @Test
  void profitGapSpeedMatchesTheFrozenPerValueTable() {
    assertThat(ModeMigrationPolicy.PROFIT_GAP_SPEED_SLOPE_PER_MILLE)
        .as("斜率是政策常量（GM 可调预设的编译期形态）：100")
        .isEqualTo(100L);

    assertThat(actualSpeed(1_000_000L, 1_000_000L)).as("差 = 0 ⇒ 不产生转业速度").isZero();
    assertThat(actualSpeed(1_000_000L, 900_000L)).as("差 < 0 ⇒ 不产生转业速度").isZero();
    assertThat(actualSpeed(1_000_000L, 1_100_000L))
        .as("分母 = max(1,000,000, 1,100,000) = 1,100,000 ⇒ ⌊100 × 100,000 ÷ 1,100,000⌋ = 9")
        .isEqualTo(9L);
    assertThat(actualSpeed(1_000_000L, 2_000_000L))
        .as("分母 = max(1,000,000, 2,000,000) = 2,000,000 ⇒ ⌊100 × 1,000,000 ÷ 2,000,000⌋ = 50")
        .isEqualTo(50L);
    assertThat(actualSpeed(1_000_000L, 11_000_000L))
        .as("分母 = max(1,000,000, 11,000,000) = 11,000,000 ⇒ ⌊100 × 10,000,000 ÷ 11,000,000⌋ = 90")
        .isEqualTo(90L);
    assertThat(actualSpeed(0L, 1_000_000L))
        .as(
            "★ 分母口径（具名，W9-fix）：收益_当前 = 0 **不再退化成 1** —— 分母 = |收益_其他| = 1,000,000 ⇒ 100‰（W9 首版是 1000‰）")
        .isEqualTo(100L);
    assertThat(actualSpeed(-1_000_000L, -500_000L))
        .as(
            "现在亏、但少亏 ⇒ 分母 = max(|−1,000,000|, |−500,000|) = 1,000,000 ⇒ ⌊100 × 500,000 ÷ 1,000,000⌋ = 50")
        .isEqualTo(50L);
    assertThat(actualSpeed(-1_000_000L, 1_000_000L))
        .as(
            "★ 反号支（现在亏、别处赚）分母 = max(1,000,000, 1,000,000) = 1,000,000 ⇒ ⌊100 × 2,000,000 ÷ 1,000,000⌋ = 200 —— 两参形态的上界（结构性质：同号支 ≤ SLOPE，只有反号支能到 2×SLOPE）")
        .isEqualTo(200L);
    assertThat(actualSpeed(1_000_000L, 1_000_001L))
        .as("★ 地板（D5）：只要差为正就有速度，取整不许把它打成 0（⌊100 × 1 ÷ 1,000,001⌋ = 0 ⇒ 抬到 1）")
        .isEqualTo(1L);
  }

  /**
   * ★★ <b>判据①的边界（按 W9-fix 的新分母结构重算）</b>：同号支上界 = SLOPE(100)、反号支上界 = 2 × SLOPE(200)，两侧各一毫 + 地板那一毫。
   *
   * <p>旧口径的"相对差 ≥ 10 打满 1000‰"这条边界**已经不存在**了（分母含 {@code |收益_其他|} ⇒ 同号支的速度恒 ≤ 100），
   * 故这里换成新结构的两条真边界，而不是把一个到不了的 999 留着当装饰。
   */
  @Test
  void profitGapSpeedBoundariesAreExact() {
    // ── 同号支（收益_当前 ≥ 0）：分母 ≥ 收益_其他 = 当前 + gap ⇒ 速度 ≤ 100，且只在 当前 = 0 处取到 100。 ──
    assertThat(actualSpeed(0L, 1_000_000L))
        .as("当前 = 0 ⇒ 分母 = |其他| ⇒ 相对差恒 1.0 ⇒ 100‰（打满同号支的上界）")
        .isEqualTo(100L);
    assertThat(actualSpeed(0L, 1L))
        .as("当前 = 0 时速度与差的绝对大小无关：分母 = 1 ⇒ ⌊100 ÷ 1⌋ = 100")
        .isEqualTo(100L);
    assertThat(actualSpeed(1L, 1_000_001L))
        .as(
            "当前 = 1（很小正）⇒ 分母 = 1,000,001 ⇒ ⌊100 × 1,000,000 ÷ 1,000,001⌋ = 99 —— **打不到 100**（这正是 W9-fix 按死的'整户迁空'）")
        .isEqualTo(99L);
    // ── 反号支（当前 < 0 < 其他）：分母 = max(|当前|, 其他) ⇒ 速度 ∈ [100, 200]，上界在两值相等处取到。 ──
    assertThat(actualSpeed(-1_000_000L, 999_999L))
        .as("差一毫不满：分母 = 1,000,000 ⇒ ⌊100 × 1,999,999 ÷ 1,000,000⌋ = 199")
        .isEqualTo(199L);
    assertThat(actualSpeed(-1_000_000L, 1_000_000L))
        .as("恰好 |当前| == 其他 ⇒ 200‰（两参形态的上界）")
        .isEqualTo(200L);
    // ── 当前为负、别处也负：分母 = max(|当前|, |其他|) = |当前| ⇒ 与旧口径逐值相同。 ──
    assertThat(actualSpeed(-2_000_000L, -1_000_000L)).isEqualTo(50L);
    assertThat(actualSpeed(-2_000_000L, 0L))
        .as("现在亏 2,000,000、别处不亏不赚 ⇒ 相对差 = 1.0 ⇒ 100‰")
        .isEqualTo(100L);
    // ── 地板：分母远大于 SLOPE × gap ⇒ 取整会得 0，必须被抬到 1。 ──
    assertThat(actualSpeed(1_000_000L, 1_000_001L)).as("差 = 1 毫 ⇒ 1‰（不是 0）").isEqualTo(1L);
  }

  /**
   * ★★★ <b>W9-fix 的根因判据：三参形态（含"收益规模基准"）下，{@code 收益_当前 ≈ 0} 不再打满</b>。
   *
   * <p>这是那条硬牙（three-powers「零成交轮次 = 0」被 21 个零成交轮打破）在**单测面**的判据：生产路径走的是三参形态 （{@code
   * profitGapSpeedPerMille(其他, 当前, 收益规模基准)}），基准 = 两侧生产方式"1 单位规模劳动的毛收入 / 口粮成本"取大 ⇒ 分母**永远含一个正参考量**，与
   * {@code 收益_当前} 是否 ≈ 0 无关。
   *
   * <p>★ 逐值对照（W9 首版 ⇒ W9-fix）：基准 = 1,000,000 时，{@code (当前=0, 其他=1)} 由 **100‰** 降到 **1‰**、 {@code
   * (当前=0, 其他=1,000,000)} 由 **1000‰** 降到 **100‰** ⇒ 同一笔迁移的可迁人口从"整户"降到 ≤ 10%。
   */
  @Test
  void theProfitScaleBasisKeepsTheZeroProfitDenominatorFromSaturating() {
    long basis = 1_000_000L;
    assertThat(profitGapSpeedWithBasis(0L, 0L, basis)).as("差 = 0 ⇒ 不产生速度（基准不改变'没差就不转业'）").isZero();
    assertThat(profitGapSpeedWithBasis(0L, 1L, basis))
        .as(
            "★ 分母 = max(0, 1, 1,000,000) = 1,000,000 ⇒ ⌊100 × 1 ÷ 1,000,000⌋ = 0 ⇒ 地板抬到 1‰（W9 首版：分母 = 1 ⇒ 100‰）")
        .isEqualTo(1L);
    assertThat(profitGapSpeedWithBasis(0L, 1_000_000L, basis))
        .as(
            "★ 分母 = max(0, 1,000,000, 1,000,000) = 1,000,000 ⇒ 100‰（W9 首版：分母 = max(1, 0) = 1 ⇒ **1000‰** ⇒ 整户迁空）")
        .isEqualTo(100L);
    assertThat(profitGapSpeedWithBasis(0L, 10_000_000L, basis))
        .as("★ 大差也打不满：分母 = max(0, 10,000,000, 1,000,000) = 10,000,000 ⇒ 100‰（W9 首版：1000‰）")
        .isEqualTo(100L);
    assertThat(profitGapSpeedWithBasis(-1_000_000L, 1_000_000L, basis))
        .as("反号支不受基准影响：三项里 |当前| = |其他| = 1,000,000 ⇒ 200‰")
        .isEqualTo(200L);
    assertThat(profitGapSpeedWithBasis(1_000_000L, 11_000_000L, basis))
        .as("|当前| 已 ≥ 基准 ⇒ 与两参形态逐值相同（⌊100 × 10,000,000 ÷ 11,000,000⌋ = 90）")
        .isEqualTo(90L);
    // ★ 基准 = 0 的退化支（薄封装的调用点）：分母 = max(|当前|, |其他|) ⇒ 仍是三参算式本身，不是第二份实现。
    assertThat(profitGapSpeedWithBasis(0L, 1_000_000L, 0L))
        .as("基准 = 0 ⇒ 分母 = |其他| = 1,000,000 ⇒ 100‰（= 两参薄封装，逐值相同）")
        .isEqualTo(actualSpeed(0L, 1_000_000L));
  }

  // ── §A2 转业速率上限（W9-fix 第 2 件的**人口闸**）────────────────────────────────────────

  /**
   * ★★★ <b>判据①的人口闸（W9-fix 第 2 件）：每计划轮每户可转人口 ≤ max(1, ⌊人口 × 100 ÷ 1000⌋)</b>。
   *
   * <pre>
   * 速度折算 = moveablePopulation(人口, 速度‰) = min(人口, max(1, ⌊人口 × 速度 ÷ 1000⌋))
   * 上限折算 = moveablePopulation(人口, MIGRATION_SPEED_CAP_PER_MILLE)
   * 可迁人口 = min(速度折算, 上限折算)                       ← 与 main 的调用点逐字同形
   * </pre>
   *
   * <p>★★★ <b>为什么要有它</b>：分母修正把"零收益户"从 1000‰ 降到 ≤ 100‰，但**反号支仍可到 200‰**（见上面的边界用例）， 且 {@code 收益_当前 <
   * 0} 时分母修正**不减小**速度 ⇒ 单靠分母不足以保证"一轮不搬空整户"。上限是**独立的人口闸** （它不夹速度读数 ⇒ "差越大越快"仍逐值可读）：W9d 实测的"单笔搬 199
   * 人（整户迁空）"在这一条下上界变成 10% （人口 200 ⇒ 20 人）。
   *
   * <p>★ 逐值表两侧都**算出来**（不写死一个数）：每行同时给"速度折算 / 上限折算"两个中间量，再由它们的 min 得可迁人口。 ★ A 规则两支**不经过**这条闸（main 显式传
   * 1000‰ 且 moveable = 全户人口，按控制方裁定不受约束）—— 那一条由 {@link ModeMigrationPolicyD024Test} 的 A 规则用例覆盖。
   */
  @Test
  void theMigrationSpeedCapClampsTheMoveablePopulationPerPlanRound() {
    assertThat(ModeMigrationPolicy.MIGRATION_SPEED_CAP_PER_MILLE)
        .as("上限是政策预设（列 W6 标定首查项）：100‰ / 计划轮")
        .isEqualTo(100L);

    record Row(long population, long speedPerMille, long expected) {}
    List<Row> rows =
        List.of(
            new Row(200L, 200L, 20L), // 速度折算 40、上限折算 20 ⇒ 上限吃紧
            new Row(200L, 100L, 20L), // 两条闸同点
            new Row(100L, 200L, 10L), // 速度折算 20、上限折算 10
            new Row(20L, 200L, 2L), // 速度折算 4、上限折算 2
            new Row(5L, 200L, 1L), // 速度折算 1、上限折算 1（"至少 1 人"地板）
            new Row(3L, 1L, 1L), // 地板（人口 3 × 1‰ ⇒ 0 ⇒ 抬到 1）
            new Row(1L, 1000L, 1L), // 单户 1 人
            new Row(200L, 1000L, 20L), // ★ W9 对照：同一行 W9 首版 = 200（整户迁空，W9d 实测"单笔搬 199 人"）
            new Row(1000L, 1000L, 100L), // 千人大户一轮最多 100 人
            new Row(7L, 1000L, 1L)); // 上限折算 ⌊7×100÷1000⌋ = 0 ⇒ 地板 1
    for (Row row : rows) {
      long bySpeed = moveableBySpeed(row.population(), row.speedPerMille());
      long byCap =
          moveableBySpeed(row.population(), ModeMigrationPolicy.MIGRATION_SPEED_CAP_PER_MILLE);
      assertThat(Math.min(bySpeed, byCap))
          .as(
              "人口=%d 速度=%d‰ ⇒ 速度折算=%d、上限折算=%d ⇒ 可迁人口=%d",
              row.population(), row.speedPerMille(), bySpeed, byCap, row.expected())
          .isEqualTo(row.expected());
      assertThat(Math.min(bySpeed, byCap))
          .as("可迁人口恒 ∈ [0, 人口]（不许搬出比这户人更多）")
          .isBetween(0L, row.population());
    }
    // ★ 结构性质（两条闸的分工）：W9 的"整户"读数仍在（速度为 1000‰ 时速度折算 = 全户），但上限折算把它夹住。
    assertThat(moveableBySpeed(200L, 1000L))
        .as("W9 首版的'整户'读数（速度 1000‰ ⇒ 速度折算 = 全户 200）")
        .isEqualTo(200L);
    assertThat(moveableBySpeed(200L, ModeMigrationPolicy.MIGRATION_SPEED_CAP_PER_MILLE))
        .as("★ 同一户、同一轮的**上限折算** = ⌊200×100÷1000⌋ = 20 ⇒ 收益差支'整户迁空'不再可能")
        .isEqualTo(20L);
    assertThat(moveableBySpeed(0L, 1000L)).as("人口 0 ⇒ 0").isZero();
    assertThat(moveableBySpeed(100L, 0L)).as("速度 0（收益差 ≤ 0 = 不转业）⇒ 0").isZero();
  }

  /** {@code moveablePopulation} 的薄封装（main 的包内可见纯函数，与生产路径同一份实现）。 */
  private static long moveableBySpeed(long population, long speedPerMille) {
    return ModeMigrationPolicy.moveablePopulation(population, speedPerMille);
  }

  /** ★★ <b>判据①的单调性</b>：固定"收益_当前"，速度对"收益_其他"单调不减（"差越大越快"）。 */
  @Test
  void profitGapSpeedIsMonotoneInTheGap() {
    long current = 700_000L;
    long previous = -1L;
    for (long delta = -200_000L; delta <= 20_000_000L; delta += 137_000L) {
      long value = actualSpeed(current, current + delta);
      assertThat(value)
          .as("★ 单调不减：delta=%d ⇒ 速度=%d（上一次=%d）", delta, value, previous)
          .isGreaterThanOrEqualTo(previous == -1L ? 0L : previous)
          .isBetween(0L, 1000L);
      previous = value;
    }
  }

  /**
   * ★★★ <b>判据④的结构面：速度算式**只有两个输入**（收益_其他, 收益_当前）</b> —— 债务/资产不再是输入。
   *
   * <p>★ 为什么用反射：用户原话④ 把两层拆开（债务 → **同生产方式内**的阶层滑落；收益 → **生产方式间**的转业）。 W9 前那条口径（{@code max(10‰,
   * f(债务/资产))}）读的是 {@code SettlementOrderBook}（债务/资产快照）。 若哪天有人把债务再塞回这个算式，签名会先变 —— 这条断言就是那个"先变"的哨兵。
   */
  @Test
  void profitGapSpeedTakesExactlyTheTwoProfitReadings() throws Exception {
    Method method =
        ModeMigrationPolicy.class.getDeclaredMethod(
            "profitGapSpeedPerMille", long.class, long.class);
    assertThat(Modifier.isStatic(method.getModifiers())).as("纯函数（无状态）").isTrue();
    assertThat(method.getParameterTypes())
        .as("★ 唯一算式只有两个输入：收益_其他、收益_当前 —— 债务/资产不得回来")
        .containsExactly(long.class, long.class);
    assertThat(ModeMigrationPolicy.class.getDeclaredMethods())
        .as("★ §一.11：旧口径的 slideSpeedPerMille（读债务/资产快照）已删，不得复活")
        .noneMatch(candidate -> candidate.getName().equals("slideSpeedPerMille"));
  }

  /** ★ 被测对象（main 的唯一算式）—— 参数顺序按 (收益_当前, 收益_其他) 读，与逐值表同序。 */
  private static long actualSpeed(long currentNetPerLaborScaled, long otherNetPerLaborScaled) {
    return ModeMigrationPolicy.profitGapSpeedPerMille(
        otherNetPerLaborScaled, currentNetPerLaborScaled);
  }

  /**
   * ★ 被测对象（**生产路径**的唯一入口 = 三参形态）—— 同样按 (收益_当前, 收益_其他, 收益规模基准) 读。
   *
   * <p>★ 为什么单独包一层：两参形态只是"没有规模基准的调用点"的薄封装（基准 = 0）；判"零收益户会不会打满"必须走三参形态 —— 那才是真世界里的那一条路径。
   */
  private static long profitGapSpeedWithBasis(
      long currentNetPerLaborScaled, long otherNetPerLaborScaled, long profitScaleBasisScaled) {
    return ModeMigrationPolicy.profitGapSpeedPerMille(
        otherNetPerLaborScaled, currentNetPerLaborScaled, profitScaleBasisScaled);
  }

  /** ★★ <b>判据①的交叉验证</b>：整段区间内 main 的算式与测试侧**独立复述**逐值一致（防"实现与判据漂开"）。 */
  @Test
  void profitGapSpeedMatchesAnIndependentRestatementAcrossTheWholeRange() {
    for (long current : new long[] {-5_000_000L, -1L, 0L, 1L, 700_000L, 1_000_000L, 123_456_789L}) {
      for (long other :
          new long[] {
            -9_000_000L, -1L, 0L, 1L, current, current + 1L, 2L * current + 7L, 50_000_000L
          }) {
        assertThat(actualSpeed(current, other))
            .as("current=%d other=%d", current, other)
            .isEqualTo(expectedSpeed(current, other))
            .isBetween(0L, 1000L);
      }
    }
  }

  /**
   * ★ 测试侧**独立复述**算式（与 main 逐字同形，但**不是**同一个方法）—— 用来做"整段区间内两者逐值一致"的交叉验证；
   * 逐值表本身的期望值仍写死在断言里（那是验收判据），不靠这个复述。
   */
  private static long expectedSpeed(long currentNetPerLaborScaled, long otherNetPerLaborScaled) {
    long gap = otherNetPerLaborScaled - currentNetPerLaborScaled;
    if (gap <= 0L) {
      return 0L;
    }
    long slope = 100L;
    // ★★ W9-fix 的分母（本条 = 两参薄封装 ⇒ 收益规模基准 = 0）：max(|当前|, |其他|) —— gap > 0 ⇒ 分母恒 > 0，
    //    不需要 `max(1, ·)` 那种"不许除零"的补丁（那个补丁正是 W9 首版"零收益 ⇒ 打满"的病根）。
    long denominator =
        Math.max(Math.abs(currentNetPerLaborScaled), Math.abs(otherNetPerLaborScaled));
    long quotient = Math.floorDiv(Math.multiplyExact(slope, gap), denominator);
    return Math.min(1000L, Math.max(1L, quotient));
  }

  // ── §B 按库存定档（逐值表 0 / 2,000 / 7,000 / 200,000 ⇒ 0 / 0 / 2 / 3）────────────────────

  /**
   * ★★★ <b>判据②：档位 = 这户带来的库存能支撑的最高档 —— 逐值表</b>（用户原话②「够地主档就当地主，不够逐级降」）。
   *
   * <pre>
   * 夹具         = 跑商模板 {@code MerchantTradeTemplate.industry(H)}（4 档 950/900/750/100‰、laborPerUnit=1000、capacityPerUnit={TOOL:120}）
   * 该户劳动     = 160,000 毫小时 ⇒ 规模_劳动 = 160 单位
   * 每档需求     = (⌈160 × 1000 ÷ 参与率⌉ − 160) × 120 毫工具 = 2,160 / 6,480 / 172,800（档 1/2/3）
   * </pre>
   */
  @Test
  void carriedToolInventorySelectsTheTierByThePerValueTable() {
    Industry trade = MerchantTradeTemplate.industry(H);
    assertThat(trade.slots()).as("夹具：贸易模板四档（贫农/中农/富农/地主）").hasSize(4);
    long labor = 160_000L;

    assertThat(tier(trade, labor, 0L)).as("带 0 毫工具 ⇒ 兜底档 0").isZero();
    assertThat(tier(trade, labor, 2_000L)).as("带 2,000 毫工具（< 档 1 需求 2,160）⇒ 档 0").isZero();
    assertThat(tier(trade, labor, 7_000L)).as("带 7,000 毫工具（≥ 2,160、< 6,480）⇒ 档 2（富农）").isEqualTo(2);
    assertThat(tier(trade, labor, 200_000L)).as("带 200,000 毫工具（≥ 172,800）⇒ 档 3（地主）").isEqualTo(3);
  }

  /**
   * ★★★ <b>判据②的边界（逐档两侧各一毫）</b>：{@code 恰好满足第 t 档 ⇒ t}；{@code 差一毫 ⇒ t−1}。
   *
   * <p>★ 需求由测试侧**独立复述**算式算出来（{@code (⌈规模 × 1000 ÷ 参与率⌉ − 规模) × capacityPerUnit}）， 再与 main 的判定对照 ⇒
   * "支撑判据"本身有判别力，而不是被同一份实现自证。
   */
  @Test
  void tierBoundariesAreExactOnBothSides() {
    Industry trade = MerchantTradeTemplate.industry(H);
    long labor = 160_000L;
    for (int t = 1; t < trade.slots().size(); t++) {
      long need = tierNeed(trade, labor, t);
      assertThat(need).as("第 %d 档的需求必须为正（否则这条边界没有判别力）", t).isPositive();
      assertThat(tier(trade, labor, need)).as("恰好满足第 %d 档 ⇒ 落到第 %d 档", t, t).isEqualTo(t);
      assertThat(tier(trade, labor, need - 1L))
          .as("差一毫 ⇒ 落回第 %d 档（需求是真的在判，不是恒真）", t - 1)
          .isEqualTo(t - 1);
    }
    // 档位单调：带得越多，档位不降。
    long previous = -1L;
    for (long carried = 0L; carried <= 200_000L; carried += 3_137L) {
      long value = tier(trade, labor, carried);
      assertThat(value)
          .as("carried=%d ⇒ 档 %d", carried, value)
          .isGreaterThanOrEqualTo(previous == -1L ? 0L : previous);
      previous = value;
    }
  }

  /** ★★ <b>兜底档不设门槛（用户原话②"为啥要拦着"）：{@code 需求(0, ·) ≡ 0} ⇒ 零库存户恒落兜底档</b>。 */
  @Test
  void theBottomTierNeverRequiresAnyInventory() {
    Industry trade = MerchantTradeTemplate.industry(H);
    assertThat(tierNeed(trade, 160_000L, 0)).as("★ 兜底档（贫农）需求恒 0 —— 这是「零库存也能转业」的结构根").isZero();
    for (long labor : new long[] {1_000L, 160_000L, 1_600_000L}) {
      assertThat(tier(trade, labor, 0L)).as("labor=%d（≥ 一个规模单位）：零库存 ⇒ 恒兜底档", labor).isZero();
    }
    // ★★★ U5 修复（W9-fix，2026-10-11）：劳动投入**不足一个规模单位**（{@code laborMilli < laborPerUnit} ⇒
    //   规模_劳动 = 0）的极小户。改前每一档的"额外规模"都算成 0（{@code ⌈0×1000÷参与率⌉ − 0 = 0}）⇒ 判据给**最高档 3**
    //   （W9d 上报的 U5：极小户落点被抬到该 mode 的最高位置）；改后 {@code 规模 ≤ 0 ⇒ 档 0}（最低档）。
    //   ★ 下面这一组逐值断言**就是 U5 的判据**：规模 = 0 ⇒ 档 0，且**与带多少库存无关**（库存在"没有规模"时
    //     不该把户抬到任何档 —— 那正是改前 200,000 毫工具 ⇒ 档 3 的坏形状）。
    for (long labor : new long[] {0L, 1L, 998L, 999L}) {
      for (long carried : new long[] {0L, 2_000L, 7_000L, 200_000L}) {
        assertThat(tier(trade, labor, carried))
            .as(
                "★ U5：劳动 %d 毫 < laborPerUnit(1,000) ⇒ 规模_劳动 = 0 ⇒ 落兜底档 0（带 %d 毫工具也一样；改前是最高档 3）",
                labor, carried)
            .isZero();
      }
    }
    // ★ 反面对照：**恰好**一个规模单位 ⇒ 判档面立刻复活（证明上面那一片 0 不是"恒 0"的假绿）。
    assertThat(tier(trade, 1_000L, 200_000L))
        .as("规模 = 1 且带满工具（≥ 档 3 需求 1,080 毫）⇒ 最高档 3")
        .isEqualTo(3);
    assertThat(tier(trade, 1_000L, 0L)).as("规模 = 1、零库存 ⇒ 仍是兜底档 0（「零库存也能转业」不受 U5 修复影响）").isZero();
    // ★ 「没有档位表」那一支（`supportedTierIndex` 的 `slots().isEmpty() → 0`）**无法用 Industry 构造出来**：
    //   `Industry` 的构造期就 fail-closed（"Industry.slots 不得为空"）⇒ 该分支是**防御性**的（旧档/未裁决产业
    //   在到达判据之前就已经被类型挡住）。这里如实钉住"构造函数先挡"这一事实，不假装测到了那一支。
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    new Industry(
                        IndustryHexKeys.id("no-slots", H.q(), H.r()),
                        "no-slots",
                        new RegimeId("household"),
                        10L,
                        Map.of(AssetKind.TOOL, 120L),
                        Map.of(),
                        0L,
                        1_000L,
                        Map.of(GRAIN, 1L),
                        Map.of(),
                        List.of(),
                        new AllocationRule.Split(500, 500))))
        .as("★ 空槽位表在 Industry 构造期就被拒 ⇒ 判据里那一支等价存活（测不到，如实记）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Industry.slots 不得为空");
  }

  /**
   * 测试侧独立复述"第 t 档需求"（毫；只按 capacityPerUnit 逐 kind 取最大需求）。
   *
   * <p>★ <b>档 0 恒为 0</b>：判据的循环从 {@code tier = 1} 起（兜底档不查参与率、不设门槛）—— 这不是"算出来 0"， 是 **定义上就是
   * 0**（"零库存也能转业"的结构根，用户原话②）。
   */
  private static long tierNeed(Industry industry, long laborMilli, int tier) {
    if (tier == 0) {
      return 0L;
    }
    long laborPerUnit = Math.max(1L, industry.recipe().laborPerUnit());
    long scaleLabor = laborMilli / laborPerUnit;
    long participation = Math.max(1L, industry.slots().get(tier).laborParticipationPerMille());
    long extraScale = ceilDiv(Math.multiplyExact(scaleLabor, 1000L), participation) - scaleLabor;
    if (extraScale <= 0L) {
      return 0L;
    }
    long need = 0L;
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long perUnit = required.getValue() == null ? 0L : required.getValue();
      need = Math.max(need, Math.multiplyExact(extraScale, perUnit));
    }
    return need;
  }

  private static long ceilDiv(long numerator, long denominator) {
    return numerator <= 0L ? 0L : (numerator + denominator - 1L) / denominator;
  }

  /** {@code supportedTierIndex} 的薄封装（只为让上面的逐值表读起来像表）。 */
  private static int tier(Industry industry, long laborMilli, long carriedToolMilli) {
    Map<AssetKind, Long> carried =
        carriedToolMilli > 0L ? Map.of(AssetKind.TOOL, carriedToolMilli) : Map.of();
    return ModeMigrationPolicy.supportedTierIndex(industry, laborMilli, carried);
  }

  // ── §C "带得走"的作业资本（档位判据的输入）──────────────────────────────────────────────

  /**
   * ★★★ <b>判据②的输入面：什么算"这户带来的库存"</b> —— 自有 ∧ 可移动（{@code isMobileAsset}）∧ 未被占用，
   * <b>外加工具商品库存</b>（工具是唯一"既有商品形态、又有资产份额形态"的作业资本）。
   */
  @Test
  void carriedCapitalCountsOwnedMobileUnclaimedSharesPlusToolGoods() {
    HouseholdId household = HouseholdId.parse("hh-w9-carried");
    ActorRef me = HouseholdActors.of(household);
    ActorRef other = HouseholdActors.of(HouseholdId.parse("hh-w9-carried-other"));
    IndustryId industry = IndustryHexKeys.id("carried-w9", H.q(), H.r());

    OwnershipStake myTool = share(industry, AssetKind.TOOL, me, me, 5_000L, 0L);
    OwnershipStake myLand = share(industry, AssetKind.LAND, me, me, 7L, 1L);
    OwnershipStake myShip = share(industry, AssetKind.SHIP, me, me, 300L, 2L);
    OwnershipStake myMachine = share(industry, AssetKind.MACHINE, me, me, 400L, 3L);
    OwnershipStake notMine = share(industry, AssetKind.TOOL, other, other, 900L, 4L);
    OwnershipStake claimedTool = share(industry, AssetKind.TOOL, me, me, 8_000L, 5L);
    OwnershipStake emptyCattle = share(industry, AssetKind.CATTLE, me, me, 0L, 6L);

    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    for (OwnershipStake s :
        List.of(myTool, myLand, myShip, myMachine, notMine, claimedTool, emptyCattle)) {
      shares.put(s.id(), s);
    }

    Map<AssetKind, Long> carried =
        ModeMigrationPolicy.carriedCapitalByKind(
            household,
            shares,
            Set.of(claimedTool.id(), myMachine.id()),
            Map.of(MerchantTradeTemplate.TOOL_COMMODITY, 2_000L, GRAIN, 9_999_999L));

    assertThat(carried)
        .as("★ 只有工具与可移动自有份额算作业资本：5,000 工具份额 + 2,000 工具商品 = 7,000；粮食不是作业资本")
        .containsExactlyInAnyOrderEntriesOf(Map.of(AssetKind.TOOL, 7_000L, AssetKind.SHIP, 300L));
    assertThat(carried)
        .as("★ 不可移动的 LAND 不计（「带不走」的唯一拼写点 = isMobileAsset）")
        .doesNotContainKey(AssetKind.LAND);
    assertThat(carried).as("★ 被既有组织/在产 unit 占用的份额不计（claimed）").doesNotContainKey(AssetKind.MACHINE);
    assertThat(carried.get(AssetKind.TOOL))
        .as("★ 900 毫别人的工具 + 8,000 毫被占用的工具都不得混进来")
        .isEqualTo(7_000L);
    Map<AssetKind, Long> nothingClaimed =
        ModeMigrationPolicy.carriedCapitalByKind(household, shares, Set.of(), Map.of());
    assertThat(nothingClaimed)
        .as("★ 去掉 claimed 之后，那份 8,000 毫工具份额与 400 毫机器份额都计入（判据是 claimed，不是资产种类）")
        .containsEntry(AssetKind.TOOL, 13_000L)
        .containsEntry(AssetKind.MACHINE, 400L);
  }

  private static OwnershipStake share(
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      long quantity,
      long sequence) {
    AssetShareId id =
        OwnershipStake.idOf(
            industry, asset, owner, operator, OwnershipStake.RightKind.OWNED, sequence);
    return new OwnershipStake(
        id, industry, asset, owner, operator, quantity, OwnershipStake.RightKind.OWNED);
  }

  // ── §D 档位 → 落点位置（升序位阶）──────────────────────────────────────────────────────

  /**
   * ★★★ <b>判据②的落点面：档位按 {@link ClassLadderBook#POSITION_LADDER_ASCENDING} 升序取第 min(t, n−1) 个位置</b>。
   *
   * <p>★ <b>为什么这条必须有判别力</b>：位阶表是**降序**的（{@code POSITION_HEIGHT_ORDER}），若错用降序表的首项，
   * 方向就反了（"库存最少也落最高档"）—— 这种反向错误不抛不报、只让世界静默变样。
   */
  @Test
  void tierPositionTakesTheAscendingLadderAndCapsAtTheHighest() {
    List<ProductionRole> producing = producingPositionsOf(DefaultProductionModes.MERCHANT);
    assertThat(producing).as("商人 mode 有三个可生产位置（本金主/脚夫/个体户）").hasSize(3);

    List<ProductionRole> ascending = new ArrayList<>(producing);
    ascending.sort(ClassLadderBook.POSITION_LADDER_ASCENDING);
    assertThat(ascending.get(0).id())
        .as("★ 逐值：升序第 0 项 = 脚夫（DIRECT_LABORER/WAGE_EARNER）—— 方向反了会当场红")
        .isEqualTo(
            DefaultProductionModes.positionId(
                    DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_PORTER)
                .orElseThrow());
    assertThat(ascending.get(ascending.size() - 1).id())
        .as("★ 逐值：升序最后一项 = 本金主（OWNER 最高）")
        .isEqualTo(
            DefaultProductionModes.positionId(
                    DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_PRINCIPAL)
                .orElseThrow());

    for (int t = 0; t < producing.size(); t++) {
      assertThat(ModeMigrationPolicy.tierPosition(producing, t))
          .as("档 %d ⇒ 升序第 %d 个位置", t, t)
          .isEqualTo(ascending.get(t).id());
    }
    assertThat(ModeMigrationPolicy.tierPosition(producing, 99))
        .as("档位多于位置数 ⇒ 封顶到最高位置（不抛、不越界）")
        .isEqualTo(ascending.get(ascending.size() - 1).id());
    assertThat(ModeMigrationPolicy.tierPosition(List.of(), 2)).as("没有可生产位置 ⇒ null").isNull();
  }

  /** 某个 mode 的可生产位置（与 {@code ModeMigrationPolicy.producingPositionsByMode} 同一口径：可生产 ∧ id 升序）。 */
  private static List<ProductionRole> producingPositionsOf(
      io.mosire.simos.economy.api.id.ProductionModeId mode) {
    List<ProductionRole> positions = new ArrayList<>();
    for (ProductionRole position : DefaultProductionModes.classPositions().values()) {
      if (position.modeId().equals(mode)
          && position.laborRole() != ProductionRole.LaborRole.NONE
          && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT) {
        positions.add(position);
      }
    }
    positions.sort(java.util.Comparator.comparing(position -> position.id().value()));
    return positions;
  }
}
