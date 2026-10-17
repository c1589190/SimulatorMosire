package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionDayRequest;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionTickCondition;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2a 判据 N8 / N10：重复与乱序推进的具名契约故障 + 契约故障的整批拒绝语义</b> （D-032 测试阶段派单 §14.11.5；口径来自约束设计书 §13.2 第
 * 1 条②④ 与 §13.7 第 2 条）。
 *
 * <p>★★ <b>为什么断言的是 reason 串而不是异常类型</b>：{@link ProductionContractFault} 的消息形制是 {@code production
 * 契约故障[<reason>]: <detail>} ⇒ "具名"的可核对形态就是那段方括号里的稳定短 id （业务拒绝也各有具名理由，两者不得互相冒充）。
 *
 * <p>★★★ <b>本类的三段"缺陷现状"读数已按 §14.13.2 裁定三十<b>改判据</b>（不是删断言）</b>：B7 批把 DEF-T2a-3（{@code beginTick} 恒抛
 * {@code tick-not-started}）与 DEF-T2a-1（悬空方案被吞成逐项业务拒绝）修好之后，原先钉住<b>缺陷行为</b>的
 * 三段断言必须改成<b>修复后的判据</b>——判据的判别力（"tick 协议必须可用"/"跨域不一致必须整批终止"）原样保留，只把期望值翻到修复后的一侧。
 *
 * <ul>
 *   <li><b>裁定三十第 1 条</b>：首次 {@code beginTick(t)} <b>成功</b>（见 {@link
 *       #n8TheFirstBeginTickSucceedsWhileADirectPublicAdvanceTickStillCannotStartOnItsOwn()}）；★
 *       同时保留"<b>跳过</b> beginTick 直调公开 {@code advanceTick} 仍抛 {@code tick-not-started}</b>"那一条。
 *   <li><b>裁定三十第 2 条</b>：悬空方案 ⇒ {@code executeDay} <b>当场抛</b> {@code
 *       instance-references-unknown-plan}（见 {@link
 *       #n10ACrossSliceInconsistencyMidBatchMustRejectTheWholeBatchNotDegradeIntoAQuietRejection()}）。
 * </ul>
 *
 * <p>★ <b>具名故障的可核对形态</b>：五类 reason 串（① {@code instance-advanced-twice-in-tick} ② {@code
 * tick-already-decayed} ③ {@code tick-already-started} ④ {@code tick-regressed} ⑤ {@code
 * tick-not-started}）在 B7 修好后<b>全部可构造</b>（本类两条 N8 用例逐条断言）。
 */
class ProductionRuntimeContractFaultTest {

  private static final HouseholdId H1 = ProductionTestFixtures.H1;
  private static final AssetKind LAND = AssetKind.LAND;
  private static final CommodityId GRAIN = ProductionTestFixtures.GRAIN;

  // ── 裁定三十第 1 条：首次 beginTick 成功；⑤ 仍只拦"跳过 beginTick"的外部调用者 ──────────────

  @Test
  void n8TheFirstBeginTickSucceedsWhileADirectPublicAdvanceTickStillCannotStartOnItsOwn() {
    // ★★ 判据（§14.13.2 裁定三十第 1 条：原"现状读数"改为修复后判据）：新引擎上**第一次** beginTick(t) 必须**成功**。
    //   现场（B7 修复后）：ProductionRuntimeEngine.java:1009 改调私有 decayOnce() ⇒ 不再去撞 advanceTick 的
    //   "未开启即衰减"守卫；随后 :1010-1011 才登记 tick 身份与生效条件。
    //   ★ 本用例若改回旧断言（期望抛 tick-not-started）⇒ 当场红：这正是判别力所在。
    ProductionRuntimeEngine fresh = new ProductionRuntimeEngine(twoDayWorld());
    ProductionTickCondition opened = fresh.beginTick(5L);

    assertThat(opened.tick()).as("★ 首次 beginTick(5L) 成功，开的就是这个 tick").isEqualTo(5L);
    assertThat(opened.masteryPerMille())
        .as("★ 生效条件冻结的是 tick 起点的熟练度：家户经验 100（恒等熟练度公式）⇒ 1 行、值 100")
        .containsExactly(
            Map.entry(
                ProductionTestFixtures.experienceKey(H1, ProductionTestFixtures.FARMING), 100L));
    assertThat(opened.isNeutral()).as("有 1 行非零熟练度 ⇒ 条件不是中性的").isFalse();
    assertThat(fresh.currentCondition()).as("开启后本 tick 生效条件可读（就是刚返回的那一份）").contains(opened);
    assertThat(fresh.advancedUnitsThisTick()).as("刚开启、还没执行过任何实例").isEmpty();

    // ★ N8⑤ 保留：**跳过 beginTick** 直调公开 advanceTick ⇒ 仍抛 tick-not-started。
    //   口径（B7 后的注释即此意）：那道守卫只该管外部调用者 —— beginTick 自己走私有 decayOnce()。
    assertThatThrownBy(() -> new ProductionRuntimeEngine(twoDayWorld()).advanceTick())
        .isInstanceOf(ProductionContractFault.class)
        .as("★ N8⑤ 未开启即衰减 = tick-not-started")
        .hasMessageContaining("[tick-not-started]");
  }

  // ── N8：五类具名契约故障（①~④ 被 DEF-T2a-3 阻塞 ⇒ 本组两条用例当前为红）──────────────────

  @Test
  void n8DuplicateAdvanceAndDuplicateDecayAreNamedContractFaults() {
    // ① 同一实例同一 tick 重复 execute：第一次成功（进度 0→1），第二次必须当场抛。
    ProductionRuntimeEngine duplicateAdvance = new ProductionRuntimeEngine(twoDayWorld());
    duplicateAdvance.beginTick(5L);
    ProductionExecutionOutcome first =
        duplicateAdvance.execute(
            execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of()));
    assertThat(first)
        .as("第一次推进是合法的（先证明守卫不是恒红）")
        .isInstanceOf(ProductionExecutionOutcome.Executed.class);

    assertThatThrownBy(
            () ->
                duplicateAdvance.execute(
                    execute(ProductionTestFixtures.U1, 100L, Map.of(LAND, 200L), Map.of())))
        .isInstanceOf(ProductionContractFault.class)
        .as("★ ① 同 tick 重复推进 = instance-advanced-twice-in-tick")
        .hasMessageContaining("[instance-advanced-twice-in-tick]");

    // ② 同一 tick 重复衰减：beginTick 内部已衰减过一次 ⇒ 再调 advanceTick 必须抛。
    ProductionRuntimeEngine duplicateDecay = new ProductionRuntimeEngine(twoDayWorld());
    duplicateDecay.beginTick(5L);
    assertThatThrownBy(duplicateDecay::advanceTick)
        .isInstanceOf(ProductionContractFault.class)
        .as("★ ② 同 tick 重复衰减 = tick-already-decayed")
        .hasMessageContaining("[tick-already-decayed]");
  }

  @Test
  void n8TickIdentityViolationsAreNamedContractFaults() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(twoDayWorld());
    engine.beginTick(5L);

    // ③ 同一 tick 重复 beginTick。
    assertThatThrownBy(() -> engine.beginTick(5L))
        .isInstanceOf(ProductionContractFault.class)
        .as("★ ③ 同 tick 重复开启 = tick-already-started")
        .hasMessageContaining("[tick-already-started]");

    // ④ tick 回退（跨 tick 只能严格递增）。
    assertThatThrownBy(() -> engine.beginTick(4L))
        .isInstanceOf(ProductionContractFault.class)
        .as("★ ④ tick 回退 = tick-regressed")
        .hasMessageContaining("[tick-regressed]");
  }

  // ── N10：契约故障整批拒绝（零半提交）──────────────────────────────────────────────────

  @Test
  void n10AFormulaErrorMidBatchStopsTheWholeBatchAndNothingIsHalfCommitted() {
    // ★ B7 修复后按原注释要求把 beginTick 加回来（判据不变）：tick 协议可用 ⇒ 本条量的是**真实调用面**上的
    //   批量失败语义（不再是"未开启 tick 的退化窗口"）。
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(brokenFormulaWorld());
    engine.beginTick(1L);
    ProductionDayRequest batch =
        day(
            List.of(
                ProductionTestFixtures.U1, // 第 1 步：合法（会推进）
                ProductionTestFixtures.U2, // 第 2 步：方案公式除零 ⇒ 契约故障
                ProductionTestFixtures.U3)); // 第 3 步：绝不该被执行

    assertThatThrownBy(() -> engine.executeDay(batch))
        .isInstanceOf(ProductionContractFault.class)
        .as("★ 公式错误必须冒泡（不得降级成逐项业务拒绝、不得吞错继续）")
        .hasMessageContaining("[expression-divide-by-zero]");

    assertThat(engine.units().get(ProductionTestFixtures.U1).progressDays())
        .as("第 1 步确实跑过（证明这次批量不是空转）")
        .isEqualTo(1L);
    assertThat(engine.units().get(ProductionTestFixtures.U3).progressDays())
        .as("★★ 第 3 步一次都没被执行：故障终止了本次候选计算")
        .isZero();

    // ★ "零半提交"的机读形态：本域变更集永远是 **起点 → 当刻候选态** 的一份整体差（不是逐步的若干份），
    //   装配起点本身不可变 ⇒ 调用方在故障时丢掉这一份，就等于整批零残留（没有任何前缀被提交）。
    ProductionData start = brokenFormulaWorld();
    ProductionChangeSet candidate = (ProductionChangeSet) engine.changeSet();
    assertThat(candidate.isEmpty()).as("候选态里确实有第 1 步的改动（正因为如此才必须整份丢弃）").isFalse();
    assertThat(ProductionChangeSet.apply(candidate, start))
        .as("★ 变更集恰好是<起点 → 当刻候选态>的一份整体差（不是逐步的若干份）")
        .isEqualTo(engine.state());
    assertThat(start.instances().get(ProductionTestFixtures.U1).progressDays())
        .as("★ 装配起点一字未动（丢弃候选态 = 回到这里，零半提交）")
        .isZero();
  }

  @Test
  void n10ACrossSliceInconsistencyMidBatchMustRejectTheWholeBatchNotDegradeIntoAQuietRejection() {
    // ★★ 判据（§14.13.2 裁定三十第 2 条：原"现状读数"改为修复后判据）：实例引用的方案在当刻权威里不存在
    //   ⇒ executeDay **当场抛**具名契约故障 instance-references-unknown-plan，**不**返回"少做了一步"的逐项业务拒绝。
    //   现场（B7 修复后）：ProductionRuntimeEngine.java:771 的 contractFault(...)（旧写法在 :679-688 把这一情形
    //   判成"跳过"⇒ executeDay 把它吞成 not-advanced-this-day，与 :665-671 自己的 javadoc 第 ③ 条相反）。
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(danglingWorld());
    assertThatThrownBy(
            () ->
                engine.executeDay(
                    day(List.of(ProductionTestFixtures.U1, ProductionTestFixtures.U2))))
        .as("★★ 悬空方案必须冒泡为契约故障 instance-references-unknown-plan（不得降级成业务拒绝）")
        .isInstanceOf(ProductionContractFault.class)
        .hasMessageContaining("[instance-references-unknown-plan]");

    // ★ 零半提交的真读数（与 N10 的公式错误腿同形）：第 1 步 U1 确已跑过（进度 1），第 2 步 U2 一步都没跑
    //   （进度仍 0 —— 它不是"被业务拒绝"，是整批候选被丢弃）；装配起点一字未动。
    assertThat(engine.units().get(ProductionTestFixtures.U1).progressDays())
        .as("第 1 步确实跑过（证明这次批量不是空转）")
        .isEqualTo(1L);
    assertThat(engine.units().get(ProductionTestFixtures.U2).progressDays())
        .as("★ U2 一步都没跑（契约故障在它这一步终止了候选计算）")
        .isZero();

    // ★ 保留（§14.13.2 第 2 条的"保留 :175-182"）：同一条判据在**全新引擎**上独立复现 —— 证明上面那次读数
    //   不是"引擎被上一次调用污染"的产物。
    ProductionRuntimeEngine again = new ProductionRuntimeEngine(danglingWorld());
    assertThatThrownBy(
            () ->
                again.executeDay(
                    day(List.of(ProductionTestFixtures.U1, ProductionTestFixtures.U2))))
        .as("★★ 本条判据可重复：悬空方案 ⇒ 契约故障 instance-references-unknown-plan")
        .isInstanceOf(ProductionContractFault.class)
        .hasMessageContaining("[instance-references-unknown-plan]");
  }

  // ── ★ §14.13.4 第 2 条（新补判据）：实例不存在 ⇒ 具名业务拒绝 instance-unknown ─────────────

  @Test
  void n24AnUnknownInstanceIsRejectedByNameInTheDayResultWithoutStoppingTheOtherSteps() {
    // ★★ 判据（§14.13.4 第 2 条；补 DEF-T2a-4 的口径变化此前 63 条里没有载体的空白）：
    //   批量请求里给一个**状态里根本不存在**的实例 id ⇒ rejections() 必须给具名业务拒绝 `instance-unknown`。
    //   现场（B7 修复后）：ProductionRuntimeEngine.java:683-686（skipStep ② 分支给 instance-unknown）
    //   与 :754（execute 遇同一情形给同一条理由）—— 旧写法在 :626 给的是 `not-advanced-this-day`，
    //   经济侧因此读不出"这个实例根本不存在"（两者语义不同：一个是"本 tick 已推进过"，一个是"查无此实例"）。
    //   ★ 本用例**先 beginTick**：tick 协议按裁定三十第 1 条修好之后，这正是经济侧真实推进的那条调用面。
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(twoDayWorld());
    engine.beginTick(1L);
    ProductionUnitId ghost = new ProductionUnitId("unit-not-in-state");

    ProductionDayResult result = engine.executeDay(day(List.of(ghost, ProductionTestFixtures.U1)));

    assertThat(result.rejections())
        .as("★ 具名理由逐值 = instance-unknown（不是 not-advanced-this-day）")
        .containsExactly(Map.entry(ghost, "instance-unknown"));
    assertThat(result.receipts().stream().map(receipt -> receipt.unit()).toList())
        .as("回执表里只有真实存在的 U1：幽灵实例没有任何回执")
        .containsExactly(ProductionTestFixtures.U1);
    assertThat(engine.units()).as("★ 幽灵实例没有被凭空造出来").doesNotContainKey(ghost);
    assertThat(engine.units().get(ProductionTestFixtures.U1).progressDays())
        .as("★ 业务拒绝不短路：同批里合法的那一步照常推进（进度 1）")
        .isEqualTo(1L);
    assertThat(engine.advancedUnitsThisTick())
        .as("本 tick 只推进过真实存在的那一个实例")
        .containsExactly(ProductionTestFixtures.U1);
  }

  // ── 夹具（真实数据）────────────────────────────────────────────────────────────────

  /** 2 天周期的合法世界：FARM + 实例 U1（推进一天不关账 ⇒ 同 tick 重复推进可观察）。 */
  private static ProductionData twoDayWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                2L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 0L)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(100L, 0L));
  }

  /** 注入公式错误的世界：MILL 的效率公式是 {@code 1000 ÷ 0}（构造合法、求值必炸）。 */
  private static ProductionData brokenFormulaWorld() {
    ProductionExperienceUse broken =
        new ProductionExperienceUse(
            Optional.of(ProductionTestFixtures.FARMING),
            Optional.empty(),
            ProductionEffectScope.TOTAL,
            new ExpressionDefinition(
                ExpressionDefinition.CURRENT_VERSION,
                List.of(),
                new RestrictedExpression.Div(
                    new RestrictedExpression.Const(1_000L), new RestrictedExpression.Const(0L))),
            0L);
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                2L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 0L)))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.MILL,
                2L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(ProductionTestFixtures.CLOTH, 1L),
                broken))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U2, ProductionTestFixtures.MILL, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U3, ProductionTestFixtures.FARM, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(100L, 0L));
  }

  /** 跨域不一致的世界：U2 引用的方案 {@code craft@0_0} 不在 industries 里（状态自相矛盾）。 */
  private static ProductionData danglingWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                2L,
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
                ProductionTestFixtures.U2, ProductionTestFixtures.CRAFT, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(100L, 0L));
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
        List.of(
            new ParticipantContribution(
                H1, ProductionRoleDefinition.defaultOperator().id(), laborMilli)),
        occupancy,
        inputs,
        ExternalAttributeFacts.EMPTY,
        Map.of(),
        Map.of(),
        Map.of());
  }

  /** 一步一天的批量请求（按给定序）。 */
  private static ProductionDayRequest day(List<ProductionUnitId> unitsInOrder) {
    List<ProductionDayRequest.Step> steps =
        unitsInOrder.stream()
            .map(
                unit ->
                    new ProductionDayRequest.Step(
                        Optional.of(unit),
                        Optional.empty(),
                        Long.MAX_VALUE,
                        List.of(
                            new ParticipantContribution(
                                H1, ProductionRoleDefinition.defaultOperator().id(), 100L)),
                        Map.of(LAND, 200L),
                        Map.of(),
                        ExternalAttributeFacts.EMPTY,
                        Map.of(),
                        Map.of(),
                        Map.of()))
            .toList();
    return new ProductionDayRequest(steps);
  }
}
