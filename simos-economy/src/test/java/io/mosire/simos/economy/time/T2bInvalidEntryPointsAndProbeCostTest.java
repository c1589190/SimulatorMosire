package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.social.api.id.HouseholdId;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2b 判据 N24（旧无效入口已删）+ N25（{@code probe} 在 O(家户×候选) 的耗时观察）</b> （D-032 测试阶段派单 §14.11.5 的 T2b
 * 行；口径 = §14.4 的 N24 / N25 两行）。
 *
 * <p>★★ <b>N24 为什么用反射而不是"读代码"</b>：判据原文是"<b>调用即编译红</b>"——源码里"看不见"不等于 classpath
 * 上没有；反射读的是<b>当刻真实字节码</b>的形状（旧构造器 / 旧状态组件 / 旧类型是否还在）， 与真正跑起来的是同一份。
 *
 * <p>★★ <b>N25 的口径</b>：原文"记录 {@code probe} 在 O(家户×候选) 的耗时；<b>不许</b>为性能加缓存 （除非同源且不跨 revision
 * 陈旧）——<b>先报数</b>"。⇒ 本用例：① 先断一条**结构性**判据 （{@code probe} 逐值稳定 + 零副作用，否则测的不是"只读试算"）；② 把 <b>全量扫（家户 ×
 * 候选）</b>的耗时读数打进断言消息（随 surefire 报告留档 = "报数"）； ③ 只加一条**极宽松**的线性 sanity 上界（防平方级爆炸，不防常数/JIT/GC 抖动 ⇒ 不在
 * CI 上抖）。
 */
class T2bInvalidEntryPointsAndProbeCostTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, FARM.value());
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ExperienceTrackId FARMING = new ExperienceTrackId("farming");

  // ── N24：旧无效入口必须已从 classpath 消失（"调用即编译红"的机读形态）──────────────────────

  @Test
  void n24TheRuntimeNullEntryPointsAreGoneAndOnlyTheExplicitRuntimeAssembliesRemain() {
    // ① `EconomyDayStepper` 的合法装配都显式收 runtime：5 参（区域拓扑）、6 参（D-032 L4 显式
    //    LandComponentLedger / 旧 arable 快照两个工厂入口）、7 参（行为旋钮全参）、9 参（D4-C 全参）、
    //    10 参（再加权威 revision）、11 参（RC4-A 再加 culture key / 旧 arable 变体）、12 参
    //    （culture key + LandComponentLedger / 旧 arable 变体）。
    List<String> stepperCtors = new ArrayList<>();
    for (Constructor<?> constructor : EconomyDayStepper.class.getDeclaredConstructors()) {
      stepperCtors.add(constructor.getParameterCount() + " 参");
      assertThat(constructor.getParameterTypes())
          .as("★ 每个合法构造器都必须显式收 %s（runtime=null 的入口不得复活）", ProductionRuntime.class)
          .contains(ProductionRuntime.class);
    }
    assertThat(stepperCtors)
        .as("★★ runtime=null 的旧入口必须已删；合法构造器都显式收 runtime")
        .containsExactlyInAnyOrder(
            "5 参", "6 参", "6 参", "7 参", "9 参", "10 参", "11 参", "11 参", "11 参", "12 参", "12 参");

    // ② `EconomySession` 的每个合法构造器都必须显式收 runtime：3 参最小装配、4 参分别显式
    //    LandComponentLedger / 旧 arable 快照、5 参 D4-C 读口、6 参权威 revision、7 参 culture key、
    //    8 参 culture key + LandComponentLedger。runtime=null 的旧构造器已删。
    List<String> sessionCtors = new ArrayList<>();
    for (Constructor<?> constructor : EconomySession.class.getDeclaredConstructors()) {
      sessionCtors.add(constructor.getParameterCount() + " 参");
      assertThat(constructor.getParameterTypes())
          .as("★ 每个 EconomySession 构造器都必须显式收 %s（runtime=null 入口不得复活）", ProductionRuntime.class)
          .contains(ProductionRuntime.class);
    }
    assertThat(sessionCtors)
        .as("★★ runtime=null 的旧构造器已删；合法构造器都显式收 runtime")
        .containsExactlyInAnyOrder("3 参", "4 参", "4 参", "5 参", "6 参", "7 参", "8 参");

    // ③ `EconomyData` 不再持有实例表 / 定义表 / 候选面（P2d 后当前 35 组件）。
    List<String> components = new ArrayList<>();
    for (var component : EconomyData.class.getRecordComponents()) {
      components.add(component.getName());
    }
    assertThat(components)
        .as("★★ 实例表（units）与定义表（industries/modes）都不得是 economy 的状态组件")
        .doesNotContain("units", "industries", "modes", "unitsWorkCopy", "candidates");

    // ④ 旧写口 / 旧桥方法必须已删（调用即编译红）。
    List<String> economyDataMethods = new ArrayList<>();
    for (Method method : EconomyData.class.getMethods()) {
      economyDataMethods.add(method.getName());
    }
    assertThat(economyDataMethods)
        .as("★★ 旧定义/实例写口与候选面必须消失")
        .doesNotContain(
            "withUnits",
            "withIndustries",
            "withModes",
            "upsertCandidate",
            "templateFor",
            "candidates");
    List<String> builderMethods = new ArrayList<>();
    for (Method method : EconomyStateBuilder.class.getMethods()) {
      builderMethods.add(method.getName());
    }
    assertThat(builderMethods)
        .as("★★ 工作副本桥 `unitsWorkCopy` 已整体删除（B2b）")
        .doesNotContain("unitsWorkCopy");
    assertThat(builderMethods)
        .as("★ 正向对照：合法的只读投影口仍在（否则上面那条可能是类名写错后的恒真）")
        .contains("unitsReadOnly", "unitsOrBase", "units");

    // ⑤ 旧类型 / 候选类型必须已从 classpath 消失（不是"源码里搜不到"）。
    for (String gone :
        List.of(
            "io.mosire.simos.production.ProductionCandidate",
            "io.mosire.simos.production.contract.CandidateId",
            "io.mosire.simos.economy.EconomyBaseDefinitions",
            "io.mosire.simos.economy.model.EconomyProductionDefinitionLookup",
            "io.mosire.simos.economy.model.ProductionSkillCatalog")) {
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () ->
                      Class.forName(
                          gone,
                          false,
                          T2bInvalidEntryPointsAndProbeCostTest.class.getClassLoader())))
          .as("★ 旧类型 %s 必须已从 classpath 消失（Alpha：不留兼容位）", gone)
          .isInstanceOf(ClassNotFoundException.class);
    }
  }

  // ── N25：`probe` 在 O(家户 × 候选) 的耗时读数（先报数；不许加缓存）──────────────────────────

  @Test
  void n25ProbeCostIsRecordedAcrossGrowingHouseholdAndPlanCountsWithoutCaching() {
    // ★ 结构性判据（先证"这次测量有意义"）：同一份世界重复 probe 必须**逐值稳定**且**零副作用**
    //   （无变更集 ⇒ 零可提交 revision），否则下面测的不是"只读试算"。
    ProductionRuntime small = runtime(3, 2);
    ProductionProbeResult first = probe(small, planId(0), householdId(0));
    assertThat(probe(small, planId(0), householdId(0)))
        .as("★ probe 逐值稳定（同一世界、同一家户、同一方案）")
        .isEqualTo(first);
    // ★ "零副作用"的机读形态：本域变更集恒为 **起点 → 当刻候选态** 的一份整体差；试算不推进任何东西
    //   ⇒ 它必须是**空**的生产变更集（`ProductionChangeSet.isEmpty()`，与 T2a 的 V1 同一条口径）。
    assertThat(small.changeSet())
        .as("★★ probe 无副作用：试算不产任何可提交变更（I5）")
        .isInstanceOf(io.mosire.simos.production.change.ProductionChangeSet.class);
    assertThat(
            ((io.mosire.simos.production.change.ProductionChangeSet) small.changeSet()).isEmpty())
        .as("★ 而且那份变更集确实是空的（不是'有内容但类型对'）")
        .isTrue();
    assertThat(first.feasible()).as("这套世界是可行的（否则测的是拒绝路径的常数）").isTrue();
    assertThat(first.scale())
        .as("手算：劳动 100 × E(=1000+熟练度 500) ÷ 1000 = ⌊100×1500÷1000⌋ = 150")
        .isEqualTo(150L);

    // ★ 报数（口径 = §14.4 N25 的"先报数"）：家户与候选各翻档，记录**全量扫**
    //   （每一对 家户×方案 各 probe 一次）的纳秒读数 —— 那才是 O(家户×候选) 的那个量。
    Map<String, Long> nanosPerProbe = new LinkedHashMap<>();
    Map<String, Long> nanosPerSweep = new LinkedHashMap<>();
    for (int[] size : new int[][] {{3, 2}, {6, 4}, {12, 8}, {24, 16}}) {
      int households = size[0];
      int plans = size[1];
      ProductionRuntime runtime = runtime(households, plans);
      // 预热（类加载 / JIT 不计入读数）
      sweep(runtime, households, plans);
      sweep(runtime, households, plans);
      int rounds = 20;
      long start = System.nanoTime();
      for (int i = 0; i < rounds; i++) {
        sweep(runtime, households, plans);
      }
      long elapsed = System.nanoTime() - start;
      long pairs = (long) households * plans;
      nanosPerSweep.put(households + "家户×" + plans + "候选", elapsed / rounds);
      nanosPerProbe.put(households + "家户×" + plans + "候选", elapsed / rounds / pairs);
    }

    // ★ 报数（口径 = §14.4 N25 的"先报数"）：读数同时 ① 进断言消息 ② 打到 surefire 捕获的 stdout
    //   （后者才是"通过时也留档"的那一份 —— 断言消息只在失败时出现）。
    System.out.println(
        "[N25] probe 耗时读数（纳秒；单次 probe / 全量扫）：" + nanosPerProbe + " / " + nanosPerSweep);

    // ★ 断言形制：读数进消息（surefire 报告留档 = "报数"），并只加**极宽松**的 sanity 上界：
    //   全量扫的规模从 6 对涨到 384 对（64 倍）⇒ 给 64×8 = 512 倍的上界。
    //   平方级爆炸会是 4096 倍 ⇒ 仍能被抓到；常数/JIT/GC 抖动远到不了 512 倍 ⇒ CI 不抖。
    long firstSweep = nanosPerSweep.values().iterator().next();
    long lastSweep = new ArrayList<>(nanosPerSweep.values()).get(nanosPerSweep.size() - 1);
    assertThat(lastSweep)
        .as("★★ N25 耗时读数（纳秒；单次 probe / 全量扫）：%s / %s", nanosPerProbe, nanosPerSweep)
        .isLessThanOrEqualTo(Math.max(firstSweep, 1L) * 512L);
    assertThat(nanosPerSweep).as("★ 四档尺寸都必须真的测到（否则上一条是空转）").hasSize(4);
    // ★ 单次 probe 的量级 sanity（毫秒级就说明有东西爆炸了；正常在微秒以下）。
    assertThat(nanosPerProbe.values())
        .as("★ 单次 probe 仍在微秒量级（读数见上一条）")
        .allSatisfy(value -> assertThat(value).isLessThan(1_000_000L));
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  private static IndustryId planId(int index) {
    return IndustryHexKeys.id("farm" + index, 0, 0);
  }

  private static HouseholdId householdId(int index) {
    return new HouseholdId("h-" + index);
  }

  /**
   * 一个 {@code households × plans} 的世界：同一格上 {@code plans} 条方案、每条各一个实例； 每条方案都声明经验面（熟练度 500 ⇒
   * E=1500），家户经验给 {@code households} 条。
   */
  private static ProductionRuntime runtime(int households, int plans) {
    ProductionData data =
        ProductionData.empty()
            .withExperienceTrack(
                new ExperienceTrack(
                    FARMING,
                    "农耕",
                    10_000L,
                    0L,
                    0L,
                    // 熟练度（千分制）= 经验值（恒等）⇒ 经验 500 ⇒ 熟练度 500 ⇒ E=1500
                    ExpressionDefinition.ofSingleInput(
                        ExperienceTrack.EXPERIENCE_INPUT,
                        new RestrictedExpression.Input(ExperienceTrack.EXPERIENCE_INPUT))));
    for (int p = 0; p < plans; p++) {
      IndustryId id = planId(p);
      data =
          data.withIndustry(plan(id, onePlusPrimaryMastery()))
              .withInstance(
                  new ProductionProcess(
                      new ProductionUnitId("unit-" + id.value()),
                      id,
                      ProductionLocation.ofPlan(id).orElseThrow(),
                      OPERATOR,
                      id.value(),
                      0L,
                      Map.of(),
                      Map.of()));
    }
    for (int h = 0; h < households; h++) {
      data =
          data.withHouseholdExperience(
              householdId(h), FARMING, new ProductionExperienceFeature(500L, 0L));
    }
    return new ProductionRuntimeEngine(data);
  }

  /** 效率倍率公式 {@code 1 + a}（{@code a} = 专门熟练度，千分制）：熟练度 500 ⇒ 1500‰。 */
  private static ProductionExperienceUse onePlusPrimaryMastery() {
    return new ProductionExperienceUse(
        Optional.of(FARMING),
        Optional.empty(),
        ProductionEffectScope.TOTAL,
        new ExpressionDefinition(
            ExpressionDefinition.CURRENT_VERSION,
            List.of(ProductionExperienceUse.PRIMARY_MASTERY_INPUT),
            new RestrictedExpression.Add(
                new RestrictedExpression.Const(1_000L),
                new RestrictedExpression.Input(ProductionExperienceUse.PRIMARY_MASTERY_INPUT))),
        0L);
  }

  private static IndustryDefinitionView plan(IndustryId id, ProductionExperienceUse use) {
    return new IndustryDefinitionView(
        id,
        "农业 " + id.value(),
        new RegimeId("tenant"),
        Optional.empty(),
        1L,
        0L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1L,
        Map.of(GRAIN, 1L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        use,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  /** 全量扫：每一对（家户 × 方案）各试算一次 = 判据 N25 的 O(家户×候选)。 */
  private static void sweep(ProductionRuntime runtime, int households, int plans) {
    for (int h = 0; h < households; h++) {
      for (int p = 0; p < plans; p++) {
        probe(runtime, planId(p), householdId(h));
      }
    }
  }

  /** 一次试算：劳动 100 · 占用 LAND 200（每单位 1）· 无原料 ⇒ baseScale=100、E=1500 ⇒ scale=150。 */
  private static ProductionProbeResult probe(
      ProductionRuntime runtime, IndustryId plan, HouseholdId household) {
    return runtime.probe(
        new ProductionProbeRequest(
            plan,
            new HouseholdProductionConditions(
                household,
                100L,
                Map.of(AssetKind.LAND, 200L),
                Map.of(),
                Map.of(FARMING, 500L),
                Map.of(FARMING, 500L)),
            Map.of(AssetKind.LAND, 200L),
            Optional.empty(),
            ExternalAttributeFacts.EMPTY));
  }
}
