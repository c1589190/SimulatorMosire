package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.ProductionDayRequest;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionExperienceMoveOutcome;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionTickCondition;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2a 判据 V5 / N4 / N5 / N6 / N7：经验、衰减、门槛、归并与 tick 统一生效条件</b> （D-032 测试阶段派单 §14.11.5；期望值是实现账本
 * A3-§3/A3-§5 手算样例的逐值复算）。
 *
 * <p>★★ <b>量纲与换算（手算依据）</b>：一次实际贡献 {@code laborMilli} 在经验条 {@code g = growthPerLaborMilli} 上产生
 * {@code exact = laborMilli × g} 的<b>未截断分子</b>；{@code numerator = exact + 上期余数}； {@code 主值增量 =
 * ⌊numerator ÷ 1000⌋}、{@code 新余数 = numerator mod 1000}。 熟练度公式取恒等（熟练度 = 经验值），效率公式取 {@code 1 + a} ⇒
 * {@code E = 1000 + 熟练度}。
 *
 * <p>★★★ <b>tick 协议已按 B7 修复（DEF-T2a-3）</b>：{@code beginTick} 改走私有 {@code decayOnce()} ⇒ <b>首次
 * {@code beginTick(t)} 成功</b>、"每 tick 恰好一次衰减"仍由同一个布尔量保证。⇒ 本文件里三条原先被阻塞的用例 （V5 的逐 tick 衰减、N6 的顺序无关、N7
 * 的次序不变性）<b>已原样转绿</b>（判据与期望值一个字未改，只把名字里"CurrentlyBlocked"的旧说法去掉）。
 */
class ProductionRuntimeExperienceTest {

  private static final HouseholdId H1 = ProductionTestFixtures.H1;
  private static final AssetKind LAND = AssetKind.LAND;
  private static final CommodityId GRAIN = ProductionTestFixtures.GRAIN;

  // ── V5：增长 / 门槛开放（未开启 tick 的调用面上可量）──────────────────────────────────────

  @Test
  void v5ExperienceGrowsAndUnlocksAtTheProductionDayTheThresholdIsReached() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(unlockWorld());

    // 读数一（达门槛前）：MILL 的门槛是 farming 经验 100，家户现在是 0 ⇒ 试算具名拒 `locked`。
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING)).isZero();
    assertThat(engine.unlocksOf(H1)).as("还没有任何解锁").isEmpty();
    assertThat(engine.isUnlocked(H1, ProductionTestFixtures.MILL)).isFalse();
    assertThat(probe(engine, ProductionTestFixtures.MILL, 100L).reason())
        .as("★ 读数一：达门槛前 MILL 不可用（具名理由 locked）")
        .isEqualTo("locked");

    // 读数二（达到门槛的那一个生产日就授予）：出工 1000 毫劳动、g=100
    //   ⇒ exact = 1000 × 100 = 100000 ⇒ 主值 +100、余数 0 ⇒ 经验 100 ⇒ 恰达门槛 100。
    ProductionExecutionOutcome.Executed worked =
        (ProductionExecutionOutcome.Executed)
            engine.execute(
                execute(ProductionTestFixtures.U1, 1_000L, Map.of(LAND, 2_000L), Map.of()));

    assertThat(worked.receipt().experienceGrowth())
        .as("★ 读数二：增长按实际贡献换算（1000 毫劳动 × 100 ÷ 1000 = 100）")
        .containsEntry(
            ProductionTestFixtures.experienceKey(H1, ProductionTestFixtures.FARMING), 100L);
    assertThat(worked.receipt().newlyUnlocked())
        .as("★ 门槛在达到的那一个生产日记入永久解锁（不等关账日）")
        .containsExactly(ProductionTestFixtures.MILL);
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING)).isEqualTo(100L);
    assertThat(engine.masteryOf(H1, ProductionTestFixtures.FARMING))
        .as("熟练度 = 经验（恒等公式）")
        .isEqualTo(100L);
    assertThat(engine.unlocksOf(H1)).containsExactly(ProductionTestFixtures.MILL);
    assertThat(engine.isUnlocked(H1, ProductionTestFixtures.MILL)).isTrue();

    // 门槛开放后 MILL 可试算：E = 1000 + 100 = 1100 ⇒ scale = ⌊100×1100÷1000⌋ = 110。
    ProductionProbeResult unlocked = probe(engine, ProductionTestFixtures.MILL, 100L);
    assertThat(unlocked.feasible()).as("解锁后同一方案可用").isTrue();
    assertThat(unlocked.reason()).isNull();
    assertThat(unlocked.scale()).as("★ 读数二：效率吃 100 熟练度（110）").isEqualTo(110L);
  }

  // ── V5：逐 tick 衰减 / 衰减跌破门槛后已解锁仍可用（tick 协议 B7 修好后原样复跑 ⇒ 绿）────────

  @Test
  void v5PerTickDecayAndTheUnlockSurvivingItRunThroughTheTickEntryPoint() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(unlockWorld());
    engine.execute(execute(ProductionTestFixtures.U1, 1_000L, Map.of(LAND, 2_000L), Map.of()));
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("先取得<已达门槛>的读数（100 经验、MILL 已解锁）")
        .isEqualTo(100L);
    assertThat(engine.unlocksOf(H1)).containsExactly(ProductionTestFixtures.MILL);

    // ★ 读数三（逐 tick 衰减）：tick 2 衰减 30 ⇒ 经验 100 → 70（跌破门槛 100）。
    //   ★ B7 修复后本行成功（首次 beginTick 不再抛 tick-not-started）。
    engine.beginTick(2L);

    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ 读数三：逐 tick 衰减一次（100 - 30 = 70）")
        .isEqualTo(70L);
    assertThat(engine.masteryOf(H1, ProductionTestFixtures.FARMING)).isEqualTo(70L);
    assertThat(engine.unlocksOf(H1))
        .as("★★ I7：衰减跌破门槛后<已解锁仍可用>（解锁只增不减）")
        .containsExactly(ProductionTestFixtures.MILL);
    assertThat(engine.isUnlocked(H1, ProductionTestFixtures.MILL)).isTrue();
    ProductionProbeResult afterDecay = probe(engine, ProductionTestFixtures.MILL, 100L);
    assertThat(afterDecay.feasible()).as("衰减后仍可试算（不是 locked）").isTrue();
    assertThat(afterDecay.scale())
        .as("★ E = 1000 + 70 = 1070 ⇒ ⌊100×1070÷1000⌋ = 107")
        .isEqualTo(107L);

    // 再衰减 4 个 tick：70 → 40 → 10 → 0（衰减只作用于主值、夹到 0）。
    engine.beginTick(3L);
    engine.beginTick(4L);
    engine.beginTick(5L);
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING)).isZero();
    assertThat(engine.unlocksOf(H1))
        .as("★★ I7 的极值：经验归零也不撤销资格（本模块没有撤销解锁的方法）")
        .containsExactly(ProductionTestFixtures.MILL);
    assertThat(probe(engine, ProductionTestFixtures.MILL, 100L).scale())
        .as("可用性另一侧仍受可行性约束：E 退回 1000 ⇒ scale = 100（不是 0，也不是 locked）")
        .isEqualTo(100L);
  }

  // ── N5：小额贡献的余数必须结转（不得永远 0；未开启 tick 亦可量）────────────────────────────

  @Test
  void n5SmallPerTickContributionsAccumulateThroughTheRemainderInsteadOfStayingZeroForever() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(smallAmountWorld());

    // 手算：g = 1、每次出工 100 毫劳动 ⇒ exact = 100 ⇒ numerator = 100 + 上期余数。
    //   第 1..5 次 ⇒ 主值 0、余数 100/200/300/400/500；第 6..10 次 ⇒ 600/700/800/900/1000 ⇒ 第 10 次主值 +1、余数 0。
    for (int day = 1; day <= 5; day++) {
      engine.execute(execute(ProductionTestFixtures.U3, 100L, Map.of(LAND, 200L), Map.of()));
    }
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.MILLING))
        .as("前 5 次还未凑满一个整经验点")
        .isZero();
    assertThat(engine.experienceStateOf(H1, ProductionTestFixtures.MILLING).remainder())
        .as("★ 余数结转（旧式逐次截断在这里恒为 0）")
        .isEqualTo(500L);

    for (int day = 6; day <= 10; day++) {
      engine.execute(execute(ProductionTestFixtures.U3, 100L, Map.of(LAND, 200L), Map.of()));
    }

    assertThat(engine.experienceOf(H1, ProductionTestFixtures.MILLING))
        .as("★ 10 × 100 毫劳动 × g=1 ⇒ 恰 +1（不得永远 0）")
        .isEqualTo(1L);
    assertThat(engine.experienceStateOf(H1, ProductionTestFixtures.MILLING).remainder())
        .as("余数恰好吃净")
        .isZero();
    assertThat(
            engine.experienceOf(H1, ProductionTestFixtures.MILLING) * 1_000L
                + engine.experienceStateOf(H1, ProductionTestFixtures.MILLING).remainder())
        .as("★ 对账口径：主值×1000 + 余数 == 累计精确换算 10 × 100 × 1")
        .isEqualTo(1_000L);
  }

  // ── N6：同户同条不得重复计（未开启 tick 亦可量）──────────────────────────────────────────

  @Test
  void n6TheSameHouseholdOnTwoInstancesIsNotCountedTwiceEvenWithoutTheTickEntryPoint() {
    // 手算：H1 起点 300 经验、g = 500；两个实例各出工 100（两条 execute）
    //   ⇒ 两次各 +50 ⇒ 400（若把 200 毫劳动当 400 计，会得到 500）。
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(mergeWorld());
    engine.execute(execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of()));
    engine.execute(execute(ProductionTestFixtures.U2, 100L, Map.of(LAND, 200L), Map.of()));

    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ 同户同条不重复计：300 + 50 + 50 = 400（重复计会给 500）")
        .isEqualTo(400L);
    assertThat(engine.experienceStateOf(H1, ProductionTestFixtures.FARMING).remainder()).isZero();

    // 余数路径：同世界换 g=1 ⇒ 每次 +0、余数 100 → 200（零头不丢，且只按实际贡献累一次）。
    ProductionRuntimeEngine remainderEngine =
        new ProductionRuntimeEngine(mergeWorldWithGrowth(1L, 300L));
    remainderEngine.execute(execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of()));
    remainderEngine.execute(execute(ProductionTestFixtures.U2, 100L, Map.of(LAND, 200L), Map.of()));

    assertThat(remainderEngine.experienceOf(H1, ProductionTestFixtures.FARMING)).isEqualTo(300L);
    assertThat(remainderEngine.experienceStateOf(H1, ProductionTestFixtures.FARMING).remainder())
        .as("★ 两次实际贡献的零头合计 200（分两次各截断会只剩 100）")
        .isEqualTo(200L);
  }

  // ── N6：同 tick 归并 + 顺序无关（tick 协议 B7 修好后原样复跑 ⇒ 绿）────────────────────────

  @Test
  void n6SameTickMergeAndOrderIndependenceHoldThroughTheTickEntryPoint() {
    // 手算：H1 起点 300 经验、g = 500；同一 tick 两个实例各出工 100 ⇒ 归并 200
    //   ⇒ exact = 200 × 500 = 100000 ⇒ 主值 +100 ⇒ 400（若重复计成 400 劳动则会是 +200 ⇒ 500）。
    //   ★ 同一份请求的遍历序对调后，八组件必须逐值相同（归并键 = 家户 × 经验条，与实例顺序无关）。
    ProductionRuntimeEngine forward = new ProductionRuntimeEngine(mergeWorld());
    forward.beginTick(1L);
    ProductionDayResult forwardResult =
        forward.executeDay(day(List.of(ProductionTestFixtures.U1, ProductionTestFixtures.U2)));

    assertThat(forwardResult.rejections()).as("两步都该成功").isEmpty();
    assertThat(scalesByUnit(forwardResult))
        .as("两实例本 tick 的产出规模（E 冻结在 tick 起点 1300 ⇒ ⌊100×1300÷1000⌋ = 130）")
        .containsOnlyKeys(ProductionTestFixtures.U1, ProductionTestFixtures.U2)
        .containsValues(130L, 130L);
    assertThat(forward.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ 同户同条只换算一次：300 + 100 = 400（重复计会给 500）")
        .isEqualTo(400L);
    assertThat(forward.experienceStateOf(H1, ProductionTestFixtures.FARMING).remainder()).isZero();

    ProductionRuntimeEngine reversed = new ProductionRuntimeEngine(mergeWorld());
    reversed.beginTick(1L);
    ProductionDayResult reversedResult =
        reversed.executeDay(day(List.of(ProductionTestFixtures.U2, ProductionTestFixtures.U1)));

    assertThat(reversedResult.rejections()).isEmpty();
    assertThat(scalesByUnit(reversedResult)).isEqualTo(scalesByUnit(forwardResult));
    assertThat(reversed.state())
        .as("★★ 换遍历序 ⇒ 当刻候选态逐值不变（归并键是<家户 × 经验条>，与实例顺序无关）")
        .isEqualTo(forward.state());
  }

  // ── N7：同 tick 统一生效条件（★ 被 DEF-T2a-3 阻塞 ⇒ 当前为红）────────────────────────────

  @Test
  void n7SwappingTheInstanceOrderDoesNotChangeTheOtherInstanceThisTickConditionOrOutput() {
    ProductionRuntimeEngine reference = new ProductionRuntimeEngine(mergeWorld());
    reference.beginTick(1L);
    ProductionReceipt alone =
        ((ProductionExecutionOutcome.Executed)
                reference.execute(
                    execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of())))
            .receipt();
    assertThat(alone.scale()).as("隔离基线：H1 熟练度 300 ⇒ E=1300 ⇒ scale 130").isEqualTo(130L);

    // 次序 A：U1 先、U2 后。次序 B：U2 先、U1 后。两次的 U2 本 tick 产出必须与隔离基线一致。
    ProductionRuntimeEngine u1First = new ProductionRuntimeEngine(mergeWorld());
    u1First.beginTick(1L);
    ProductionDayResult first =
        u1First.executeDay(day(List.of(ProductionTestFixtures.U1, ProductionTestFixtures.U2)));

    ProductionRuntimeEngine u2First = new ProductionRuntimeEngine(mergeWorld());
    u2First.beginTick(1L);
    ProductionDayResult second =
        u2First.executeDay(day(List.of(ProductionTestFixtures.U2, ProductionTestFixtures.U1)));

    assertThat(scalesByUnit(first))
        .as("★★ 次序 A：两个实例都按 tick 起点的熟练度算（先跑的没有把熟练度喂给后跑的）")
        .containsEntry(ProductionTestFixtures.U1, 130L)
        .containsEntry(ProductionTestFixtures.U2, 130L);
    assertThat(scalesByUnit(second))
        .as("★★ 次序 B：逐值相同 ⇒ 另一实例本 tick 的产出不变")
        .isEqualTo(scalesByUnit(first));
    assertThat(scalesByUnit(first).get(ProductionTestFixtures.U2))
        .as("★ 后跑的 U2 拿到的规模 == 它单独跑时的规模（130，而不是按 400 熟练度算出的 140）")
        .isEqualTo(alone.scale());

    // ★ 生效条件与候选态两件事：条件仍是 tick 起点的 300，候选态已含本 tick 的 +100。
    assertThat(
            u2First.currentCondition().orElseThrow().masteryOf(H1, ProductionTestFixtures.FARMING))
        .as("本 tick 生效条件（冻结）")
        .isEqualTo(300L);
    assertThat(u2First.masteryOf(H1, ProductionTestFixtures.FARMING))
        .as("当刻候选态（可含本 tick 新经验，供迁移/后续读取）")
        .isEqualTo(400L);
    assertThat(u2First.experienceOf(H1, ProductionTestFixtures.FARMING)).isEqualTo(400L);
  }

  // ── ★ §14.13.4 第 1 条（新补判据）：缺经验条槽位不得 NPE，缺的那一路按 0 熟练度参与 ─────────

  @Test
  void theMissingExperienceSlotsCountAsZeroMasteryInsteadOfBlowingUpOnceTheTickIsOpen() {
    // ★★ 判据（§14.13.4 第 1 条；补 B7 报备的第 5 处修复此前没有载体的空白）：
    //   "只挂 a"（primary 有、shared 缺）与"都不挂"（ProductionExperienceUse.NONE，两个槽位都缺）
    //   两种**合法**方案，在**先 beginTick** 的调用面上必须不 NPE，且缺的那一路按 **0 熟练度** 参与公式。
    //   现场（B7 修复后）：ProductionRuntimeEngine.java:1311 masteryFromCondition(...) —— 旧写法在
    //   :1286-1287 直调 condition.masteryOf(household, track)，而 ProductionTickCondition.masteryOf
    //   对 track 走 Objects.requireNonNull ⇒ 缺槽位（null）当场 NPE。该分支在 DEF-T2a-3 修好前是**死代码**
    //   （condition 恒 null），修好后立刻成为活路径。
    //
    // 手算（本世界）：经验条 farming 上限 10000、g=0、不衰减、熟练度=经验（恒等）。
    //   家户 h-1 在 farming 上 500 经验 ⇒ a = 500；另有一行 milling = 700，但**本方案没有把 milling
    //   挂进任何槽位** ⇒ 它不得作为 b 混进来（这正是"缺槽位 = 0"而不是"随手找一条别的经验条"的判别点）。
    //   方案 A（只挂 a）：公式 1000 + a + b ⇒ E = 1000 + 500 + 0 = 1500。
    //   方案 B（都不挂）：NONE（中性 1000‰）⇒ E = 1000。
    //   两例各出工 100 毫劳动、每单位 1 劳动 · 占用 LAND 200（每单位 1）· 无原料通道：
    //     baseLaborScale = 100、assetScale = 200、inputScale = ∞ ⇒ baseScale = 100
    //     A: finalScale = ⌊100 × 1500 ÷ 1000⌋ = 150 ⇒ 毛产 grain = 150 × 1 = 150000 毫
    //     B: finalScale = ⌊100 × 1000 ÷ 1000⌋ = 100 ⇒ 毛产 grain = 100 × 1 = 100000 毫
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(missingSlotWorld());
    ProductionTickCondition opened = engine.beginTick(1L);

    assertThat(opened.masteryPerMille())
        .as("★ 开启 tick 成功（不是 NPE、也不是契约故障）；冻结的条件里 a=500、无关的 milling=700")
        .containsEntry(
            ProductionTestFixtures.experienceKey(H1, ProductionTestFixtures.FARMING), 500L)
        .containsEntry(
            ProductionTestFixtures.experienceKey(H1, ProductionTestFixtures.MILLING), 700L);

    // ★ 只挂 a：shared 槽位缺 ⇒ 按 0 参与（若 NPE 则本行直接炸；若误读 milling 则 E=2200 ⇒ 规模 220）。
    ProductionExecutionOutcome.Executed aOnly =
        (ProductionExecutionOutcome.Executed)
            engine.execute(execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of()));
    assertThat(aOnly.receipt().scale())
        .as("★ 只挂 a：E = 1000 + 500 + 0 = 1500 ⇒ ⌊100×1500÷1000⌋ = 150")
        .isEqualTo(150L);
    assertThat(aOnly.receipt().grossOutput())
        .as("毛产按产出规模逐值：150 × 每单位 1 = 150000 毫")
        .containsExactly(Map.entry(GRAIN, 150_000L));

    // ★ 都不挂：两个槽位都缺 ⇒ 两路都按 0，效率退回中性 1000‰（若 NPE 则本行直接炸）。
    ProductionExecutionOutcome.Executed noTrack =
        (ProductionExecutionOutcome.Executed)
            engine.execute(execute(ProductionTestFixtures.U2, 100L, Map.of(LAND, 200L), Map.of()));
    assertThat(noTrack.receipt().scale())
        .as("★ 都不挂：纯劳动方案 E = 1000 ⇒ ⌊100×1000÷1000⌋ = 100")
        .isEqualTo(100L);
    assertThat(noTrack.receipt().grossOutput())
        .as("毛产逐值：100 × 每单位 1 = 100000 毫")
        .containsExactly(Map.entry(GRAIN, 100_000L));
    assertThat(noTrack.receipt().experienceGrowth())
        .as("都不挂 ⇒ 没有任何经验条可涨（不是故障，是这个方案本就不读经验面）")
        .isEmpty();
  }

  // ── N4（引擎侧）：关账把效率余数写回状态，下一周期接着吃 ───────────────────────────────

  @Test
  void n4TheEfficiencyRemainderSurvivesTheCycleCloseAndIsConsumedNextCycle() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(carryWorld());

    // 手算：H1 经验 505 ⇒ 熟练度 505 ⇒ E=1505；劳动 100（每单位 1）· 资产 200 · 原料 120 ⇒ baseScale=100
    //   周期 1：⌊(100×1505 + 0) ÷ 1000⌋   = 150，余数 500
    //   周期 2：⌊(100×1505 + 500) ÷ 1000⌋ = 151，余数 0 ⇒ 两周期合计 301
    ProductionExecutionOutcome.Executed first =
        (ProductionExecutionOutcome.Executed)
            engine.execute(
                execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of(GRAIN, 120L)));

    assertThat(first.receipt().cycleClosed()).isTrue();
    assertThat(first.receipt().scale()).as("周期 1 产出规模").isEqualTo(150L);
    assertThat(
            engine
                .efficiencyOf(ProductionTestFixtures.U1)
                .orElseThrow()
                .carry()
                .efficiencyRemainderMilli())
        .as("★ 关账把余数 500 写进本域状态（不是留在过程里）")
        .isEqualTo(500L);

    ProductionExecutionOutcome.Executed second =
        (ProductionExecutionOutcome.Executed)
            engine.execute(
                execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of(GRAIN, 120L)));

    assertThat(second.receipt().scale()).as("周期 2 产出规模（吃上期 500）").isEqualTo(151L);
    assertThat(first.receipt().scale() + second.receipt().scale())
        .as("★ 两周期合计 301（逐次截断会永远 300）")
        .isEqualTo(301L);
    assertThat(engine.efficiencyOf(ProductionTestFixtures.U1)).as("余数吃净 ⇒ 缺行（与全零行逐值等价）").isEmpty();
  }

  // ── 夹具（真实数据）────────────────────────────────────────────────────────────────

  /** 解锁世界：FARM（无门槛、g=100、衰减 30）+ MILL（门槛 100）。 */
  private static ProductionData unlockWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 100L, 30L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 0L)))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.MILL,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(ProductionTestFixtures.CLOTH, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 100L)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U2, ProductionTestFixtures.MILL, "0_0"));
  }

  /** 小额聚合世界：CRAFT（中性效率 ⇒ 只观察经验账）、g=1、不衰减。 */
  private static ProductionData smallAmountWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.MILLING, 10_000L, 1L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.CRAFT,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(ProductionTestFixtures.CLOTH, 1L),
                ProductionTestFixtures.neutralUse(ProductionTestFixtures.MILLING)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U3, ProductionTestFixtures.CRAFT, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.MILLING, ProductionTestFixtures.feature(0L, 0L));
  }

  /** 归并世界：两个实例引用同一方案、同一经验条；H1 起点 300 经验、g=500。 */
  private static ProductionData mergeWorld() {
    return mergeWorldWithGrowth(500L, 300L);
  }

  /**
   * ★ §14.13.4 第 1 条的缺槽位世界：两条**合法**方案 —— A 只挂 a（shared 缺）、B 都不挂（{@code NONE}）。
   *
   * <p>家户 h-1 在 farming 上 500 经验（⇒ a = 500）、在 milling 上 700 经验；但两个方案都**没有**把 milling 挂进槽位 ⇒ 那 700
   * 不得作为 b 混进公式（缺槽位 = 0，不是"另找一条经验条"）。 经验条 growth/decay 都取 0 ⇒ 本用例只观察"缺槽位怎么参与公式"，不掺进增长/衰减。
   */
  private static ProductionData missingSlotWorld() {
    IndustryId aOnly = IndustryHexKeys.id("farm-a-only", 0, 0);
    IndustryId noTrack = IndustryHexKeys.id("farm-no-track", 0, 0);
    ProductionExperienceUse bothChannels =
        new ProductionExperienceUse(
            Optional.of(ProductionTestFixtures.FARMING),
            Optional.empty(), // ★ 只挂 a：b 槽位缺
            ProductionEffectScope.TOTAL,
            // 公式 1000 + a + b（两个输入都读 ⇒ "缺的那一路是否按 0"才有判别力）
            new ExpressionDefinition(
                ExpressionDefinition.CURRENT_VERSION,
                List.of(
                    ProductionExperienceUse.PRIMARY_MASTERY_INPUT,
                    ProductionExperienceUse.SHARED_MASTERY_INPUT),
                new RestrictedExpression.Add(
                    new RestrictedExpression.Add(
                        new RestrictedExpression.Const(1_000L),
                        new RestrictedExpression.Input(
                            ProductionExperienceUse.PRIMARY_MASTERY_INPUT)),
                    new RestrictedExpression.Input(ProductionExperienceUse.SHARED_MASTERY_INPUT))),
            0L);
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.MILLING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                aOnly, 1L, 1L, Map.of(LAND, 1L), Map.of(), Map.of(GRAIN, 1L), bothChannels))
        .withIndustry(
            ProductionTestFixtures.plan(
                noTrack,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionExperienceUse.NONE)) // ★ 都不挂：两个槽位都缺
        .withInstance(ProductionTestFixtures.instance(ProductionTestFixtures.U1, aOnly, "0_0"))
        .withInstance(ProductionTestFixtures.instance(ProductionTestFixtures.U2, noTrack, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(500L, 0L))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.MILLING, ProductionTestFixtures.feature(700L, 0L));
  }

  /** 归并世界（可调增长与起点经验）。 */
  private static ProductionData mergeWorldWithGrowth(long growth, long startExperience) {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, growth, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 0L)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U2, ProductionTestFixtures.FARM, "0_0"))
        .withHouseholdExperience(
            H1,
            ProductionTestFixtures.FARMING,
            ProductionTestFixtures.feature(startExperience, 0L));
  }

  /** 余数世界：H1 经验 505、g=0、不衰减；农场每单位要 1 劳动 + 1 LAND + 1 谷。 */
  private static ProductionData carryWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(GRAIN, 1L),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 0L)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(505L, 0L));
  }

  /** 一次试算（劳动 100 · 资产 200；熟练度/经验按当刻账本给）。 */
  private static ProductionProbeResult probe(
      ProductionRuntimeEngine engine, IndustryId plan, long laborMilli) {
    return engine.probe(
        new ProductionProbeRequest(
            plan,
            ProductionTestFixtures.conditions(
                H1,
                laborMilli,
                Map.of(LAND, 200L),
                Map.of(),
                Map.of(
                    ProductionTestFixtures.FARMING,
                    engine.masteryOf(H1, ProductionTestFixtures.FARMING)),
                Map.of(
                    ProductionTestFixtures.FARMING,
                    engine.experienceOf(H1, ProductionTestFixtures.FARMING))),
            Map.of(LAND, 200L),
            Optional.empty(),
            ExternalAttributeFacts.EMPTY));
  }

  /** 一次执行（既有实例、一个生产日）。 */
  private static ProductionExecutionRequest execute(
      ProductionUnitId unit,
      long laborMilli,
      Map<AssetKind, Long> occupancy,
      Map<CommodityId, Long> inputs) {
    return new ProductionExecutionRequest(
        Optional.of(unit),
        Optional.empty(),
        Long.MAX_VALUE,
        Map.of(H1, laborMilli),
        occupancy,
        inputs,
        ExternalAttributeFacts.EMPTY);
  }

  // ── V6 / N15（I10 拆合）：用户例式 ⇒ 人口加权平均，余数完整携带 ─────────────────────────

  /**
   * ★★ <b>判据 V6 / N15（I10 拆合；§14.4 的原话例式"60 人 × 10 + 20 × 30 ÷ 40"）</b>：人口迁出携带各经验条
   * <b>完整值（不乘比例）</b>；合户按<b>人口加权平均</b>；结果必须逐值等于用户给的例式。
   *
   * <pre>
   * 例式（value 量纲；exact = value × 1000 + remainder）：
   *   来源户：10 人、经验 60            ⇒ exact_from = 60 × 1000 + 0 = 60,000
   *   迁入户：原有 30 人、经验 20        ⇒ exact_to   = 20 × 1000 + 0 = 20,000
   *   mergedExact = ⌊(60,000 × 10 + 20,000 × 30) ÷ (10 + 30)⌋ = ⌊1,200,000 ÷ 40⌋ = 30,000
   *   ⇒ 主值 30、余数 0                    ★ 这就是"⇒ 30"
   * 余数腿（第二段）：来源 60 余 500、迁入 20 余 700
   *   mergedExact = ⌊(60,500 × 10 + 20,700 × 30) ÷ 40⌋ = ⌊1,226,000 ÷ 40⌋ = 30,650
   *   ⇒ 主值 30、余数 650（余数**不丢**：不乘比例、也不各自截断）
   * </pre>
   */
  @Test
  void v6MergeIsAPopulationWeightedAverageThatCarriesTheExactRemainder() {
    HouseholdId from = ProductionTestFixtures.H2;
    HouseholdId to = ProductionTestFixtures.H3;
    ExperienceTrackId track = ProductionTestFixtures.FARMING;

    ProductionData base =
        ProductionData.empty()
            .withExperienceTrack(ProductionTestFixtures.track(track, 1_000_000L, 1_000L, 0L))
            .withHouseholdExperience(from, track, ProductionTestFixtures.feature(60L, 0L))
            .withHouseholdExperience(to, track, ProductionTestFixtures.feature(20L, 0L))
            .withUnlock(from, ProductionTestFixtures.MILL);
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(base);

    ProductionExperienceMoveOutcome receipt = engine.mergeExperience(from, 10L, to, 30L);

    assertThat(engine.experienceOf(to, track))
        .as("★ 用户例式：⌊(60×1000×10 + 20×1000×30) ÷ 40⌋ ÷ 1000 = 30")
        .isEqualTo(30L);
    assertThat(engine.experienceStateOf(to, track).remainder()).as("余数 0").isZero();
    assertThat(engine.experienceOf(from, track)).as("★ 源户不减（迁移是'带过去'，不是'拿回来一半'）").isEqualTo(60L);
    assertThat(engine.unlocksOf(to))
        .as("★ 解锁取并集（I7 永久记忆：迁入户由此继承来源已解锁的方案）")
        .contains(ProductionTestFixtures.MILL);
    assertThat(receipt.tracksMerged()).as("搬了 1 条经验").isEqualTo(1);
    assertThat(receipt.unlocksAdded()).as("并入 1 条解锁").isEqualTo(1);
    assertThat(receipt.changed()).as("确实变了").isTrue();
    assertThat(receipt.from()).isEqualTo(from);
    assertThat(receipt.to()).isEqualTo(to);

    // ── 余数腿：两边的余数都进分子，结果余数 650（各自截断会得 30/0，丢掉 650）──────────────
    ProductionData withRemainders =
        ProductionData.empty()
            .withExperienceTrack(ProductionTestFixtures.track(track, 1_000_000L, 1_000L, 0L))
            .withHouseholdExperience(from, track, ProductionTestFixtures.feature(60L, 500L))
            .withHouseholdExperience(to, track, ProductionTestFixtures.feature(20L, 700L));
    ProductionRuntimeEngine second = new ProductionRuntimeEngine(withRemainders);

    second.mergeExperience(from, 10L, to, 30L);

    assertThat(second.experienceOf(to, track)).as("主值 30").isEqualTo(30L);
    assertThat(second.experienceStateOf(to, track).remainder())
        .as("★ 余数 650（⌊1,226,000 ÷ 40⌋ = 30,650 ⇒ 主值 30 + 余数 650）")
        .isEqualTo(650L);
  }

  /** 一步一天的批量请求（两步：给定序）。 */
  private static ProductionDayRequest day(List<ProductionUnitId> unitsInOrder) {
    List<ProductionDayRequest.Step> steps =
        unitsInOrder.stream()
            .map(
                unit ->
                    new ProductionDayRequest.Step(
                        Optional.of(unit),
                        Optional.empty(),
                        Long.MAX_VALUE,
                        Map.of(H1, 100L),
                        Map.of(LAND, 200L),
                        Map.of(),
                        ExternalAttributeFacts.EMPTY))
            .toList();
    return new ProductionDayRequest(steps);
  }

  /** 回执按实例 id 索引（顺序无关的读数形制）。 */
  private static Map<ProductionUnitId, Long> scalesByUnit(ProductionDayResult result) {
    Map<ProductionUnitId, Long> scales = new LinkedHashMap<>();
    for (ProductionReceipt receipt : result.receipts()) {
      scales.put(receipt.unit(), receipt.scale());
    }
    return scales;
  }
}
