package io.mosire.simos.production.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.codec.ProductionCodec;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.CycleContribution;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.PerCycleCarry;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2a 判据 V10（扩展）：按 8 组件与<b>新类型</b>逐字段重建</b> （D-032 测试阶段派单 §14.11.5；铁律 5：{@code
 * apply(changeSet, base)} 逐字段重建 target）。
 *
 * <p>★★ <b>与 {@code ProductionRoundTripTest} 的分工</b>：那一份守住"组件集/豁免集/反射枚举/隔离"四条结构与 13 条既有读数（B6
 * 已跑绿）；本份专盯 <b>D-032 新增/迁移的取值面</b>—— 经验<b>余数</b>（主值之外的另一半）、<b>解锁</b>集合、效率<b>三段</b>余数、GM
 * <b>产出覆盖</b>（嵌套表）、 实例的<b>显式地点</b>与周期台账，并在 JSON 线格式上复跑一遍（B6 修的 {@code
 * ProductionLocation.isSpecified()} 属性泄漏就在这条路径上）。
 *
 * <p>★ <b>为什么每张表都必须非空</b>（照 {@code ProductionRoundTripTest} 的既有理由）：{@link FieldDelta#diff} 把键经
 * {@code toString()} 写成规范串、{@link FieldDelta#rebuild} 再用各自的 {@code parse} 读回 —— 空表
 * <b>根本不经过键的字面量往返</b>，键读侧缺失这类缺陷在空夹具下永远看不见。
 */
class ProductionRoundTripNewComponentsTest {

  private static final SimosTimestamp AT = new SimosTimestamp(120L, Optional.empty());

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final IndustryId MILL = IndustryHexKeys.id("mill", 3, 7);
  private static final ProductionModeId MODE = new ProductionModeId("peasant@0_0");
  private static final ProductionUnitId U1 = new ProductionUnitId("unit-farm-1");
  private static final ProductionUnitId U2 = new ProductionUnitId("unit-mill-1");
  private static final ExperienceTrackId FARMING = new ExperienceTrackId("farming");
  private static final ExperienceTrackId MILLING = new ExperienceTrackId("milling");
  private static final HouseholdId H1 = new HouseholdId("h-1");
  private static final HouseholdId H2 = new HouseholdId("h-2");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final ClassStructureId CS = new ClassStructureId("cs-peasant");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "h-1");

  private static final ProductionLocation LOC_0_0 = new ProductionLocation("0_0");
  private static final ProductionLocation LOC_3_7 = new ProductionLocation("3_7");

  // ── ① apply(changeSet, base) 逐字段重建（新类型的取值面）────────────────────────────────

  @Test
  void v10ApplyRebuildsEveryNewComponentFieldByField() {
    ProductionData base = baseWorld();
    ProductionData target = targetWorld();
    ProductionChangeSet changeSet = ProductionChangeSet.between(base, target);

    assertThat(changeSet.isEmpty()).isFalse();
    for (String component :
        List.of(
            "industries",
            "modes",
            "instances",
            "experienceTracks",
            "householdExperience",
            "unlocks",
            "efficiency",
            "outputQuantityOverrides")) {
      assertThat(changedOf(changeSet, component)).as("组件 %s 必须进变更集", component).isTrue();
    }
    // ★ 差异形状也是读数：本夹具的 instances/efficiency 只"改 + 加"（无键被删）⇒ Upsert；
    //   householdExperience 在 target 里删掉了一条 (H2, farming) ⇒ 又删又改又加 ⇒ Patch。
    assertThat(changeSet.instances()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.efficiency()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.householdExperience())
        .as("复合键类型走的是 Patch（含删行）")
        .isInstanceOf(FieldDelta.Patch.class);

    ProductionData rebuilt = ProductionChangeSet.apply(changeSet, base);

    // 经验余数（"完整特征"的另一半）——只搬主值就会在这里红。
    assertThat(rebuilt.experienceOf(H1, FARMING)).as("经验主值").isEqualTo(42L);
    assertThat(rebuilt.experienceFeatureOf(H1, FARMING).remainder())
        .as("★ 经验余数 999 逐值往返")
        .isEqualTo(999L);
    assertThat(rebuilt.experienceFeatureOf(H2, MILLING))
        .as("另一条 (家户 × 经验条) 复合键")
        .isEqualTo(new ProductionExperienceFeature(7L, 125L));
    assertThat(rebuilt.householdExperience())
        .as("被删掉的那条 (H2, farming) 不许留在重建结果里")
        .doesNotContainKey(new HouseholdExperienceKey(H2, FARMING));

    // 解锁集合（永久记忆；Set 值也要逐值回来）。
    assertThat(rebuilt.unlocksOf(H1)).containsExactlyInAnyOrder(FARM, MILL);
    assertThat(rebuilt.unlocksOf(H2)).containsExactly(MILL);
    assertThat(rebuilt.unlocks()).containsOnlyKeys(H1, H2);

    // 效率三段余数。
    assertThat(rebuilt.efficiency()).containsOnlyKeys(U1, U2);
    assertThat(rebuilt.efficiency().get(U1).carry())
        .as("★ 三段逐值（劳动周期段 3 · 劳动规模段 4 · 倍率段 999）")
        .isEqualTo(new PerCycleCarry(3L, 4L, 999L));
    assertThat(rebuilt.efficiencyOf(U2).orElseThrow().carry().efficiencyRemainderMilli())
        .isEqualTo(7L);

    // GM 产出覆盖（嵌套表：外层键 + 内层商品键都要过键解析）。
    assertThat(rebuilt.outputQuantityOverrides()).containsOnlyKeys(FARM, MILL);
    assertThat(rebuilt.outputQuantityOverrideOf(MILL))
        .containsOnlyKeys(CLOTH)
        .containsEntry(CLOTH, 22L);
    assertThat(rebuilt.outputQuantityOverrideOf(FARM))
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 11L);

    // 实例：显式地点 + 方案引用 + 进度 + 周期台账（逐家户贡献 + 实扣投入）。
    assertThat(rebuilt.instances()).containsOnlyKeys(U1, U2);
    assertThat(rebuilt.instances().get(U1).location()).as("★ 实例的显式地点往返（3_7）").isEqualTo(LOC_3_7);
    assertThat(rebuilt.instances().get(U1).location().isSpecified()).isTrue();
    assertThat(rebuilt.instances().get(U1).hexKey()).contains("3_7");
    assertThat(rebuilt.instances().get(U1).progressDays()).isEqualTo(2L);
    assertThat(rebuilt.instances().get(U1).cycleInputUsedMilli())
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 7L);
    assertThat(rebuilt.instances().get(U1).cycleContributions()).containsOnlyKeys(H1);
    assertThat(rebuilt.instances().get(U1).cycleContributions().get(H1).laborMilli())
        .isEqualTo(100L);
    assertThat(
            rebuilt.instances().get(U1).cycleContributions().get(H1).efficiencyWeightedLaborMilli())
        .as("加权劳动（未除 1000 的精确分子）也逐值往返")
        .isEqualTo(150_000L);
    assertThat(rebuilt.instances().get(U2).location()).isEqualTo(LOC_0_0);
    assertThat(rebuilt.instances().get(U2).plan()).isEqualTo(MILL);

    // 定义侧两表（新形状下依旧非空、依旧进变更集）。
    assertThat(rebuilt.industries()).containsOnlyKeys(FARM, MILL);
    assertThat(rebuilt.industries().get(MILL).cycleDays()).as("定义基数被覆盖（2 → 3）").isEqualTo(3L);
    assertThat(rebuilt.experienceTracks()).containsOnlyKeys(FARMING, MILLING);
    assertThat(rebuilt.experienceTracks().get(MILLING).decayPerTick()).isEqualTo(7L);
    assertThat(rebuilt.modes()).containsOnlyKeys(MODE);

    assertThat(rebuilt)
        .as("★ 铁律 5 原文：apply(between(base, target), base) 逐字段重建出 target")
        .isEqualTo(target);
  }

  // ── ② 同一份取值面过 JSON（线格式）：写得出、读得回、重建得出 ─────────────────────────────

  @Test
  void v10CodecRoundTripsTheNewComponentValuesThroughJson() {
    ProductionCodec codec = new ProductionCodec();
    ProductionData base = baseWorld();
    ProductionData target = targetWorld();
    StateRef baseRef = new StateRef(new BranchId("main"), new RevisionId(7L));
    StateRef targetRef = new StateRef(new BranchId("main"), new RevisionId(8L));

    ProductionSnapshot decoded =
        (ProductionSnapshot)
            codec.decodeSnapshot(codec.encodeSnapshot(new ProductionSnapshot(baseRef, AT, base)));
    ProductionSnapshot decodedTarget =
        (ProductionSnapshot)
            codec.decodeSnapshot(
                codec.encodeSnapshot(new ProductionSnapshot(targetRef, AT, target)));

    assertThat(decoded.state()).as("含实例（显式地点）的快照自己写出的字节必须自己读得回").isEqualTo(base);
    assertThat(decodedTarget.state()).as("★ 同一份取值面的 target 也逐字段读回").isEqualTo(target);
    assertThat(decodedTarget.state().instances().get(U1).location()).isEqualTo(LOC_3_7);
    assertThat(decodedTarget.state().experienceFeatureOf(H1, FARMING).remainder()).isEqualTo(999L);
    assertThat(decodedTarget.state().efficiency().get(U1).carry())
        .isEqualTo(new PerCycleCarry(3L, 4L, 999L));
    assertThat(decodedTarget.state().unlocksOf(H1)).containsExactlyInAnyOrder(FARM, MILL);

    ProductionChangeSet changeSet = ProductionChangeSet.between(base, target);
    ProductionChangeSet decodedChangeSet =
        (ProductionChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(changeSet));

    assertThat(ProductionChangeSet.apply(decodedChangeSet, decoded.state()))
        .as("变更集过 JSON 后仍能逐字段重建出 target")
        .isEqualTo(target);
    assertThat(codec.diff(decoded, decodedTarget)).isEqualTo(changeSet);
  }

  // ── 夹具（两张都非空；"空表"一次都不出现）────────────────────────────────────────────

  private static ProductionData baseWorld() {
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, 1L));
    industries.put(MILL, industry(MILL, 2L));
    Map<ProductionModeId, ProductionModeDefinitionView> modes = new LinkedHashMap<>();
    modes.put(MODE, new ProductionModeDefinitionView(MODE, "小农", 1, CS));
    Map<ProductionUnitId, ProductionProcess> instances = new LinkedHashMap<>();
    instances.put(U1, instance(U1, FARM, LOC_0_0, 0L, Map.of(), Map.of()));
    instances.put(
        U2,
        instance(U2, MILL, LOC_3_7, 1L, Map.of(H1, contribution(50L, 60_000L)), Map.of(GRAIN, 3L)));
    Map<ExperienceTrackId, ExperienceTrack> tracks = new LinkedHashMap<>();
    tracks.put(FARMING, track(FARMING, 3L));
    tracks.put(MILLING, track(MILLING, 5L));
    Map<HouseholdExperienceKey, ProductionExperienceFeature> experience = new LinkedHashMap<>();
    experience.put(
        new HouseholdExperienceKey(H1, FARMING), new ProductionExperienceFeature(10L, 250L));
    experience.put(
        new HouseholdExperienceKey(H2, FARMING), new ProductionExperienceFeature(5L, 5L));
    Map<HouseholdId, Set<IndustryId>> unlocks = new LinkedHashMap<>();
    unlocks.put(H1, new LinkedHashSet<>(Set.of(FARM)));
    Map<ProductionUnitId, ProductionEfficiencyState> efficiency = new LinkedHashMap<>();
    efficiency.put(U1, new ProductionEfficiencyState(new PerCycleCarry(1L, 2L, 3L)));
    Map<IndustryId, Map<CommodityId, Long>> overrides = new LinkedHashMap<>();
    overrides.put(FARM, new LinkedHashMap<>(Map.of(GRAIN, 5L)));
    return new ProductionData(
        industries, modes, instances, tracks, experience, unlocks, efficiency, overrides);
  }

  private static ProductionData targetWorld() {
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, 1L));
    industries.put(MILL, industry(MILL, 3L));
    Map<ProductionModeId, ProductionModeDefinitionView> modes = new LinkedHashMap<>();
    modes.put(MODE, new ProductionModeDefinitionView(MODE, "小农（改）", 2, CS));
    Map<ProductionUnitId, ProductionProcess> instances = new LinkedHashMap<>();
    instances.put(
        U1,
        instance(
            U1, FARM, LOC_3_7, 2L, Map.of(H1, contribution(100L, 150_000L)), Map.of(GRAIN, 7L)));
    instances.put(U2, instance(U2, MILL, LOC_0_0, 0L, Map.of(), Map.of()));
    Map<ExperienceTrackId, ExperienceTrack> tracks = new LinkedHashMap<>();
    tracks.put(FARMING, track(FARMING, 3L));
    tracks.put(MILLING, track(MILLING, 7L));
    Map<HouseholdExperienceKey, ProductionExperienceFeature> experience = new LinkedHashMap<>();
    experience.put(
        new HouseholdExperienceKey(H1, FARMING), new ProductionExperienceFeature(42L, 999L));
    experience.put(
        new HouseholdExperienceKey(H2, MILLING), new ProductionExperienceFeature(7L, 125L));
    Map<HouseholdId, Set<IndustryId>> unlocks = new LinkedHashMap<>();
    unlocks.put(H1, new LinkedHashSet<>(Set.of(FARM, MILL)));
    unlocks.put(H2, new LinkedHashSet<>(Set.of(MILL)));
    Map<ProductionUnitId, ProductionEfficiencyState> efficiency = new LinkedHashMap<>();
    efficiency.put(U1, new ProductionEfficiencyState(new PerCycleCarry(3L, 4L, 999L)));
    efficiency.put(U2, new ProductionEfficiencyState(new PerCycleCarry(0L, 0L, 7L)));
    Map<IndustryId, Map<CommodityId, Long>> overrides = new LinkedHashMap<>();
    overrides.put(FARM, new LinkedHashMap<>(Map.of(GRAIN, 11L)));
    overrides.put(MILL, new LinkedHashMap<>(Map.of(CLOTH, 22L)));
    return new ProductionData(
        industries, modes, instances, tracks, experience, unlocks, efficiency, overrides);
  }

  private static IndustryDefinitionView industry(IndustryId id, long cycleDays) {
    return new IndustryDefinitionView(
        id,
        "产业 " + id.value(),
        new RegimeId("tenant"),
        Optional.empty(),
        cycleDays,
        0L,
        Map.of(AssetKind.LAND, 1_000L),
        Map.of(),
        0L,
        1L,
        Map.of(GRAIN, 60L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 950)),
        new AllocationRule.Split(500, 500),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  private static ExperienceTrack track(ExperienceTrackId id, long decayPerTick) {
    return new ExperienceTrack(
        id, "经验条 " + id.value(), 10_000L, 1L, decayPerTick, ExpressionDefinition.NEUTRAL_PER_MILLE);
  }

  private static ProductionProcess instance(
      ProductionUnitId unit,
      IndustryId plan,
      ProductionLocation location,
      long progressDays,
      Map<HouseholdId, CycleContribution> contributions,
      Map<CommodityId, Long> cycleInputUsedMilli) {
    return new ProductionProcess(
        unit,
        plan,
        location,
        OPERATOR,
        plan.value(),
        progressDays,
        contributions,
        cycleInputUsedMilli);
  }

  private static CycleContribution contribution(long laborMilli, long weightedLaborMilli) {
    return new CycleContribution(H1, laborMilli, weightedLaborMilli);
  }

  private static boolean changedOf(ProductionChangeSet changeSet, String component) {
    return switch (component) {
      case "industries" -> changeSet.industries().changed();
      case "modes" -> changeSet.modes().changed();
      case "instances" -> changeSet.instances().changed();
      case "experienceTracks" -> changeSet.experienceTracks().changed();
      case "householdExperience" -> changeSet.householdExperience().changed();
      case "unlocks" -> changeSet.unlocks().changed();
      case "efficiency" -> changeSet.efficiency().changed();
      case "outputQuantityOverrides" -> changeSet.outputQuantityOverrides().changed();
      default -> throw new IllegalStateException("未登记的组件: " + component);
    };
  }
}
