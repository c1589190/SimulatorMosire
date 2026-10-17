package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2a 判据 V1 / V2 / V9 / N1 / N2 / N12：试算面的行为读数</b> （D-032 测试阶段派单 §14.11.5；期望值来自 §6 的
 * V1/V2/V9 行与 §14.4 的 N1/N2/N12 行 + 账本 A3-§5 手算样例）。
 *
 * <p>★★ <b>世界（真实数据，裁定二十七）</b>：一个<b>1 天周期</b>的农场方案（每单位要 1 劳动 + 1 LAND + 1 谷）， 家户 {@code h-1} 在
 * {@code farming} 上有<b>经验 500 · 余数 125</b>（熟练度公式 = 恒等 ⇒ 熟练度 500）， 效率公式 {@code 1 + a} ⇒ {@code E =
 * 1500‰}；实例 {@code unit-farm-1} 已立（进度 0）。 ⇒ 试算的每一个数字都能手算（见各用例注释）。
 */
class ProductionRuntimeProbeTest {

  private static final HouseholdId H1 = ProductionTestFixtures.H1;
  private static final AssetKind LAND = AssetKind.LAND;
  private static final CommodityId GRAIN = ProductionTestFixtures.GRAIN;

  // ── V1：试算无副作用（三条读数）────────────────────────────────────────────────────────

  @Test
  void v1ProbeLeavesTheRevisionTheWholeSliceAndTheClockUntouched() {
    ProductionData base = world();
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(base);
    SimulationState before =
        new SimulationState(
            new StateMeta(ProductionTestFixtures.REF, ProductionTestFixtures.AT),
            Map.of(
                "production",
                new ProductionSnapshot(
                    ProductionTestFixtures.REF, ProductionTestFixtures.AT, base)),
            InMemoryInfoSystem.empty());

    ProductionProbeResult probed =
        engine.probe(probeRequest(ProductionTestFixtures.FARM, 100L, 200L, 120L, 500L));

    // 读数①「revisions」：试算派生出的本域变更集为空 ⇒ 没有任何可提交的 revision。
    assertThat(probed.producible()).as("先证明这次试算真的算出了东西（不是空转）").isTrue();
    assertThat(probed.scale()).isEqualTo(150L);
    assertThat(((ProductionChangeSet) engine.changeSet()).isEmpty())
        .as("★ 读数① 试算不产生变更集（零 revision）")
        .isTrue();

    // 读数②「全切片逐值不变」：同一 meta 下，production 切片（含 8 个组件）逐值相同。
    SimulationState after =
        new SimulationState(
            new StateMeta(ProductionTestFixtures.REF, ProductionTestFixtures.AT),
            Map.of(
                "production",
                new ProductionSnapshot(
                    ProductionTestFixtures.REF, ProductionTestFixtures.AT, engine.state())),
            InMemoryInfoSystem.empty());
    assertThat(after.modules())
        .as("★ 读数② SimulationState.modules() 全切片逐值不变（8 组件都在内）")
        .isEqualTo(before.modules());

    // 读数③「时钟/进度/经验」：tick 未开启、本 tick 无已推进实例、进度与经验特征一字不动。
    assertThat(engine.currentCondition()).as("试算不开启 tick").isEmpty();
    assertThat(engine.advancedUnitsThisTick()).as("试算不登记「本 tick 已推进」").isEmpty();
    ProductionProcess instance = engine.units().get(ProductionTestFixtures.U1);
    assertThat(instance.progressDays()).as("★ 读数③ 进度未推进").isZero();
    assertThat(instance.cycleContributions()).as("周期贡献台账未被试算写入").isEmpty();
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("经验主值未涨")
        .isEqualTo(500L);
    assertThat(engine.experienceStateOf(H1, ProductionTestFixtures.FARMING).remainder())
        .as("经验余数未动")
        .isEqualTo(125L);
  }

  // ── V2：预测 / 实际同源 ───────────────────────────────────────────────────────────────

  @Test
  void v2IdenticalInputsGiveIdenticalProbes() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(world());
    ProductionProbeRequest request =
        probeRequest(ProductionTestFixtures.FARM, 100L, 200L, 120L, 500L);

    ProductionProbeResult first = engine.probe(request);
    ProductionProbeResult second = engine.probe(request);

    // 手算：劳动 100（每单位 1）· 资产 200 · 原料 120 ⇒ baseScale=100、E=1500、scale=⌊100×1500÷1000⌋=150
    assertThat(first.scale()).isEqualTo(150L);
    assertThat(first.laborRequiredMilli()).isEqualTo(100L);
    assertThat(first.efficiencyScalePerMille()).isEqualTo(1_500L);
    assertThat(second).as("★ 同方案同输入 ⇒ 试算稳定（逐字段相等）").isEqualTo(first);
  }

  @Test
  void v2PredictionAndActualAgreeOnTheSameInputsAndDifferWhenTheActualInputsDiffer() {
    // 预测：劳动 100 ⇒ scale 150、预计产出 150000 毫谷。
    ProductionRuntimeEngine predicting = new ProductionRuntimeEngine(world());
    ProductionProbeResult predicted =
        predicting.probe(probeRequest(ProductionTestFixtures.FARM, 100L, 200L, 120L, 500L));
    assertThat(predicted.scale()).as("预测（产出规模）").isEqualTo(150L);
    assertThat(predicted.expectedOutput()).containsEntry(GRAIN, 150_000L);

    // 实际 = 同一投入：劳动 100 · 占用 LAND 100 · 实投谷 100 ⇒ baseScale=min(100,100,100)=100、E=1500 ⇒ 150。
    ProductionRuntimeEngine sameInputs = new ProductionRuntimeEngine(world());
    ProductionExecutionOutcome.Executed executed =
        (ProductionExecutionOutcome.Executed)
            sameInputs.execute(executeRequest(100L, Map.of(LAND, 100L), Map.of(GRAIN, 100L)));
    assertThat(executed.receipt().cycleClosed()).as("1 天周期 ⇒ 本次即关账").isTrue();
    assertThat(executed.receipt().scale())
        .as("★ 同源：同一入口（ProductionEfficiency）在相同投入下给出与预测相同的规模")
        .isEqualTo(predicted.scale());
    assertThat(executed.receipt().grossOutput())
        .as("实毛产与预计产出逐值相同")
        .isEqualTo(predicted.expectedOutput());
    assertThat(executed.receipt().consumedInputs())
        .as("实扣投入按基准规模那档")
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 100L);

    // 实际 ≠ 预测：实际只投了劳动 60 · LAND 60 · 谷 60 ⇒ baseScale=60、E=1500 ⇒ ⌊60×1500÷1000⌋=90。
    ProductionRuntimeEngine lessInputs = new ProductionRuntimeEngine(world());
    ProductionExecutionOutcome.Executed actual =
        (ProductionExecutionOutcome.Executed)
            lessInputs.execute(executeRequest(60L, Map.of(LAND, 60L), Map.of(GRAIN, 60L)));

    assertThat(actual.receipt().scale()).as("★ 实际依最终投入算（不得用预测值顶替）：60 ⇒ 90").isEqualTo(90L);
    assertThat(actual.receipt().grossOutput())
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 90_000L);
    assertThat(actual.receipt().consumedInputs()).containsOnlyKeys(GRAIN).containsEntry(GRAIN, 60L);
    assertThat(predicted.scale()).as("预测仍是 150（两者各自正确，不是互相顶替）").isEqualTo(150L);
  }

  // ── N1 / N2：试算读数上的"消耗按 baseScale、产出按 finalScale" ─────────────────────────

  @Test
  void n1ProbeReportsConsumptionAtTheBaseScaleAndOutputAtTheFinalScale() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(world());

    ProductionProbeResult total =
        engine.probe(probeRequest(ProductionTestFixtures.FARM, 100L, 200L, 120L, 500L));

    assertThat(total.scale()).as("产出规模 = finalScale = 150").isEqualTo(150L);
    assertThat(total.laborRequiredMilli()).as("TOTAL：劳动需求按 baseScale=100").isEqualTo(100L);
    assertThat(total.assetRequired())
        .as("TOTAL：资产需求按 baseScale")
        .containsOnlyKeys(LAND)
        .containsEntry(LAND, 100L);
    assertThat(total.inputRequired())
        .as("TOTAL：原料需求按 baseScale")
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 100L);
    assertThat(total.expectedOutput())
        .as("★ 同样的投入产出更多：150 单位（消耗仍是 100 档）")
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 150_000L);
    assertThat(total.expectedMint()).as("该方案不铸币 ⇒ 空候选（MoneyOutput.NONE）").isEmpty();
  }

  @Test
  void n2LaborScopeProbeFoldsTheLaborRequirementBackByEfficiency() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(world());

    ProductionProbeResult labor =
        engine.probe(probeRequest(ProductionTestFixtures.MILL, 100L, 200L, 120L, 500L));

    // 手算：LABOR 下劳动规模' = ⌊100×1500÷1000⌋ = 150，与资产 200、原料 120 取 min ⇒ baseScale=finalScale=120；
    //       劳动需求按 §13.9 裁定四折回：⌈120×1×1000÷1500⌉ = 80（欠投会让规模差一个单位 ⇒ 向上取整）。
    assertThat(labor.scale()).as("LABOR：产出规模 = 120（不是 150）").isEqualTo(120L);
    assertThat(labor.laborRequiredMilli())
        .as("★ ⌈baseScale×laborPerUnit×1000÷E⌉ = 80")
        .isEqualTo(80L);
    assertThat(labor.assetRequired()).containsOnlyKeys(LAND).containsEntry(LAND, 120L);
    assertThat(labor.inputRequired()).containsOnlyKeys(GRAIN).containsEntry(GRAIN, 120L);
    assertThat(labor.expectedOutput()).containsOnlyKeys(GRAIN).containsEntry(GRAIN, 120_000L);
    assertThat(labor.efficiencyScalePerMille()).isEqualTo(1_500L);
  }

  // ── N12：laborRequired ≤ 可用劳动 + slack（含"可用劳动恰好等于需求"的临界样例）──────────

  @Test
  void n12ProbeLaborRequirementNeverExceedsAvailableLaborBeyondTheRoundingSlack() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(world());
    long slack = ProductionProbeResult.LABOR_REQUIRED_ROUNDING_SLACK_MILLI;
    assertThat(slack).as("slack 的口径是契约常量里的 1 毫劳动").isEqualTo(1L);

    // 临界样例 A（TOTAL）：可用劳动 100 · 资产 200 · 原料 200 ⇒ baseScale=100 ⇒ 需求 = 100 × 1 = 100 = 可用。
    ProductionProbeResult totalCritical =
        engine.probe(probeRequest(ProductionTestFixtures.FARM, 100L, 200L, 200L, 500L));
    long totalAvailable = 100L;
    assertThat(totalCritical.laborRequiredMilli())
        .as("★ 临界：可用劳动恰好等于需求（TOTAL）")
        .isEqualTo(totalAvailable);
    assertThat(totalCritical.laborRequiredMilli())
        .as("不变式：required ≤ 可用 + slack")
        .isLessThanOrEqualTo(totalAvailable + slack);

    // 临界样例 B（LABOR + ⌈⌉ 折回）：熟练度 505 ⇒ E=1505；资产/原料都给 200 ⇒ baseScale=⌊100×1505÷1000⌋=150
    //   ⇒ 需求 = ⌈150×1×1000÷1505⌉ = ⌈99.67⌉ = 100 = 可用劳动（向下取整会给 99 ⇒ 欠投）。
    ProductionProbeResult laborCritical =
        engine.probe(probeRequest(ProductionTestFixtures.MILL, 100L, 200L, 200L, 505L));
    assertThat(laborCritical.efficiencyScalePerMille()).isEqualTo(1_505L);
    assertThat(laborCritical.scale()).as("LABOR：产出规模 150").isEqualTo(150L);
    assertThat(laborCritical.laborRequiredMilli())
        .as("★ 临界：⌈⌉ 折回后恰好等于可用劳动（⌊⌋ 会给 99）")
        .isEqualTo(100L);
    assertThat(laborCritical.laborRequiredMilli()).isLessThanOrEqualTo(totalAvailable + slack);

    // 非临界样例：资产只剩 60 ⇒ baseScale=60 ⇒ LABOR 需求 = ⌈60×1000÷1505⌉ = 40 ≪ 可用劳动 100。
    ProductionProbeResult assetBottleneck =
        engine.probe(probeRequest(ProductionTestFixtures.MILL, 100L, 60L, 200L, 505L));
    assertThat(assetBottleneck.scale()).isEqualTo(60L);
    assertThat(assetBottleneck.laborRequiredMilli()).as("资产瓶颈时需求更低（不变式的一侧）").isEqualTo(40L);
    assertThat(assetBottleneck.laborRequiredMilli()).isLessThanOrEqualTo(totalAvailable + slack);
  }

  // ── V9：无陈旧视图 / 多次试算不推进 / 实例引用完整 ─────────────────────────────────────

  @Test
  void v9RepeatedProbesNeverAdvanceTheTickOrGrowExperience() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(longCycleWorld());

    for (int i = 0; i < 3; i++) {
      ProductionProbeResult result =
          engine.probe(probeRequest(ProductionTestFixtures.FARM, 100L, 200L, 120L, 500L));
      assertThat(result.producible()).as("第 %s 次试算真的算出了东西", i + 1).isTrue();
    }

    assertThat(engine.units().get(ProductionTestFixtures.U1).progressDays())
        .as("★ 同 tick 多次试算不推进生产")
        .isZero();
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ 同 tick 多次试算不涨经验")
        .isEqualTo(500L);
    assertThat(((ProductionChangeSet) engine.changeSet()).isEmpty()).isTrue();
    assertThat(engine.currentCondition()).isEmpty();
  }

  @Test
  void v9EachEngineOnlyEverSeesItsOwnCandidateStateAndNotAStaleOne() {
    ProductionData base = longCycleWorld();
    ProductionRuntimeEngine first = new ProductionRuntimeEngine(base);

    ProductionExecutionOutcome.Executed advanced =
        (ProductionExecutionOutcome.Executed)
            first.execute(executeRequest(100L, Map.of(LAND, 200L), Map.of(GRAIN, 120L)));

    assertThat(advanced.receipt().cycleClosed()).as("2 天周期 ⇒ 第一天不关账").isFalse();
    assertThat(first.units().get(ProductionTestFixtures.U1).progressDays())
        .as("★ 试算/执行的读口给的是<当刻候选态>，不是装配起点的旧表")
        .isEqualTo(1L);
    assertThat(first.units().get(ProductionTestFixtures.U1).cycleContributions())
        .as("周期贡献台账按<每个生产日>累加（不是关账日才有）")
        .containsKey(
            new ProductionParticipantKey(H1, ProductionRoleDefinition.defaultOperator().id()));

    // 另一次装配（同一 revision 起点）看不到别人的推进：没有静态/共享可变权威。
    ProductionRuntimeEngine second = new ProductionRuntimeEngine(base);
    assertThat(second.units().get(ProductionTestFixtures.U1).progressDays())
        .as("★ 跨 revision 读不到陈旧视图（也没有跨装配的共享态）")
        .isZero();
    assertThat(((ProductionChangeSet) second.changeSet()).isEmpty()).isTrue();
    assertThat(base.instances().get(ProductionTestFixtures.U1).progressDays())
        .as("装配起点本身不可变（推进只落在候选态上）")
        .isZero();
  }

  @Test
  void v9InstanceReferenceGuardsAreLive() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(world());

    // ① 试算一个不存在的方案 ⇒ 具名业务拒绝（不抛、不猜）。
    assertThat(
            engine
                .probe(probeRequest(ProductionTestFixtures.CRAFT, 100L, 200L, 120L, 500L))
                .reason())
        .isEqualTo("plan-unknown");

    // ② 实例引用的方案在当刻权威里不存在 ⇒ 契约故障（本域状态自相矛盾，不是业务拒绝）。
    ProductionData dangling =
        ProductionData.empty()
            .withExperienceTrack(
                ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
            .withInstance(
                ProductionTestFixtures.instance(
                    ProductionTestFixtures.U1, ProductionTestFixtures.CRAFT, "0_0"));
    ProductionRuntimeEngine danglingEngine = new ProductionRuntimeEngine(dangling);
    assertThatThrownBy(
            () ->
                danglingEngine.execute(
                    executeRequest(100L, Map.of(LAND, 200L), Map.of(GRAIN, 120L))))
        .isInstanceOf(ProductionContractFault.class)
        .hasMessageContaining("instance-references-unknown-plan");

    // ③ 实例表的键必须等于行内 id（表键只是索引，id 才是身份）。
    assertThatThrownBy(
            () ->
                new ProductionData(
                    Map.of(),
                    Map.of(),
                    Map.of(
                        ProductionTestFixtures.U2,
                        ProductionTestFixtures.instance(
                            ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0")),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键必须与 ProductionProcess.id 一致");
  }

  // ── 夹具（真实数据；见类注）────────────────────────────────────────────────────────────

  /** 1 天周期的世界：TOTAL 农场（FARM）+ LABOR 磨坊（MILL），家户在 farming 上经验 500（余数 125）。 */
  private static ProductionData world() {
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
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.MILL,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(GRAIN, 1L),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.LABOR, 0L)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U2, ProductionTestFixtures.MILL, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(500L, 125L));
  }

  /** 2 天周期的世界（用于"推进但不关账"的读数；其余与 {@link #world()} 同）。 */
  private static ProductionData longCycleWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                2L,
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
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(500L, 125L));
  }

  /** 试算请求：劳动/资产/原料/熟练度/经验逐项显式给（缺一项就会把那条通道悄悄关掉）。 */
  private static ProductionProbeRequest probeRequest(
      IndustryId plan, long laborMilli, long assets, long inputs, long experience) {
    return new ProductionProbeRequest(
        plan,
        ProductionTestFixtures.conditions(
            H1,
            laborMilli,
            Map.of(LAND, assets),
            Map.of(GRAIN, inputs),
            Map.of(ProductionTestFixtures.FARMING, experience),
            Map.of(ProductionTestFixtures.FARMING, experience)),
        Map.of(LAND, assets),
        Optional.empty(),
        ExternalAttributeFacts.EMPTY);
  }

  /** 执行请求：既有实例 U1，按给定最终投入执行一个生产日。 */
  private static ProductionExecutionRequest executeRequest(
      long laborMilli, Map<AssetKind, Long> assetOccupancy, Map<CommodityId, Long> finalInputs) {
    return new ProductionExecutionRequest(
        Optional.of(ProductionTestFixtures.U1),
        Optional.empty(),
        Long.MAX_VALUE,
        List.of(
            new ParticipantContribution(
                H1, ProductionRoleDefinition.defaultOperator().id(), laborMilli)),
        assetOccupancy,
        finalInputs,
        ExternalAttributeFacts.EMPTY,
        Map.of(),
        Map.of(),
        Map.of());
  }
}
