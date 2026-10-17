package io.mosire.simos.production.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
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
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.verify.RoundTripAssertions;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ <b>铁律 5 在 production 切片的机械化落地</b>（D-031 P1 spec §5 V1；照 {@code GovRoundTripTest} 的形制，不另创一套）。
 *
 * <p>★★ <b>判别力来自"反射枚举 + 四个 switch 的 default 抛"</b>：往 {@link ProductionData} 加一个组件而<b>不</b>同步 {@link
 * ProductionChangeSet} 时，{@link #mutate} 与 {@link #changedOf} 的 {@code default -> throw}
 * 当场响，且组件数断言与 {@link #changeSetCoversEveryStateComponentWithNoExemptions} 的"并集相等"同时红。
 *
 * <p>★★ <b>为什么夹具每张表都必须非空</b>：{@link FieldDelta#diff} 把表键经 {@code toString()} 写成规范串、 {@link
 * FieldDelta#rebuild} 再用各自的 {@code parse} 读回 —— 空表<b>根本不经过键的字面量往返</b>，于是"键读侧缺失"这一类缺陷在空夹具下
 * 永远看不见。故本类一律用<b>非空八表</b>（含 {@code HouseholdExperienceKey} 的 {@code <household>@<track>} 复合键与
 * {@code ProductionUnitId} 的实例键）。
 *
 * <p>★★ <b>D-032 迁移后的形状（8 个组件）</b>：{@code industries}（方案定义）/ {@code modes}（生产方式）/ {@code
 * instances}（★ 本批从 {@code EconomyData.units} 迁出）/ {@code experienceTracks} / {@code
 * householdExperience} （家户 × 经验条 → 整条特征 = 主值 + 余数）/ {@code unlocks}（永久记忆）/ {@code efficiency}（★ 从
 * {@code EconomyData.productionEfficiency} 迁出）/ {@code outputQuantityOverrides}（★ 从 {@code
 * EconomyData.outputQuantityOverrides} 迁出）。
 *
 * <p>★ <b>D1 删除的连带</b>：{@code technologies} / {@code masteries} 两个组件与 {@code TechnologyId} / {@code
 * MasteryScope} / {@code TechnologyDefinition} / {@code TechnologyMastery} 四个类型已按用户 2026-10-17「D1
 * 直接删」整体退役 ⇒ 本类不再枚举它们（组件数从 4 变成 8，不是"少了两项"）。
 */
class ProductionRoundTripTest {

  private static final SimosTimestamp AT = new SimosTimestamp(120L, Optional.empty());

  private static final IndustryId I1 = new IndustryId("farm@0_0");
  private static final IndustryId I2 = new IndustryId("weave@0_0");
  private static final IndustryId I3 = new IndustryId("craft@0_0");

  private static final ProductionModeId M1 = new ProductionModeId("peasant@0_0");
  private static final ProductionModeId M2 = new ProductionModeId("capitalist@0_0");
  private static final ProductionModeId M3 = new ProductionModeId("subsistence@0_0");

  private static final ProductionUnitId U1 = new ProductionUnitId("unit-farm-1");
  private static final ProductionUnitId U2 = new ProductionUnitId("unit-weave-1");
  private static final ProductionUnitId U3 = new ProductionUnitId("unit-craft-1");

  private static final ExperienceTrackId TRACK_A = new ExperienceTrackId("farming");
  private static final ExperienceTrackId TRACK_B = new ExperienceTrackId("weaving");
  private static final ExperienceTrackId TRACK_C = new ExperienceTrackId("crafting");

  private static final HouseholdId H1 = new HouseholdId("h-1");
  private static final HouseholdId H2 = new HouseholdId("h-2");
  private static final HouseholdId H3 = new HouseholdId("h-3");

  private static final RegimeId PEASANT = new RegimeId("peasant");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CommodityId TOOL = new CommodityId("tool");

  private static final ClassStructureId CS1 = new ClassStructureId("cs-peasant");
  private static final ClassStructureId CS2 = new ClassStructureId("cs-capitalist");
  private static final ClassStructureId CS3 = new ClassStructureId("cs-subsistence");

  private static final ActorRef OPERATOR_1 = new ActorRef(ActorKind.HOUSEHOLD, "h-1");
  private static final ActorRef OPERATOR_2 = new ActorRef(ActorKind.HOUSEHOLD, "h-2");

  private static final ProductionLocation LOC_0_0 = new ProductionLocation("0_0");

  /** ★ 唯一豁免集合（本轮无任何豁免项）。加一项就是一次有意决定，会在 diff 里现形。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── ① 反射枚举：逐组件三条断言 ────────────────────────────────────────────────────────

  @Test
  void everyProductionDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : ProductionData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      ProductionData base = full();
      ProductionData target = mutate(base, name);
      ProductionChangeSet cs = ProductionChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被报成非 Unchanged", name).isTrue();
      assertThat(ProductionChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void stateHasExactlyEightComponents() {
    assertThat(ProductionData.class.getRecordComponents())
        .as(
            "ProductionData 恰 8 个组件（industries / modes / instances / experienceTracks / householdExperience"
                + " / unlocks / efficiency / outputQuantityOverrides）—— 多加一个而不改变更集，本断言与往返用例同时红")
        .hasSize(8);
    assertThat(componentNames(ProductionData.class))
        .as("名字逐字相等（顺序即声明序）")
        .containsExactly(
            "industries",
            "modes",
            "instances",
            "experienceTracks",
            "householdExperience",
            "unlocks",
            "efficiency",
            "outputQuantityOverrides");
  }

  @Test
  void changeSetHasExactlyEightComponentsMatchingTheState() {
    assertThat(ProductionChangeSet.class.getRecordComponents()).hasSize(8);
    assertThat(componentNames(ProductionChangeSet.class))
        .as("变更集的每个组件都必须在 ProductionData 里有同名 record 组件")
        .isSubsetOf(componentNames(ProductionData.class));
    assertThat(componentNames(ProductionData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(ProductionChangeSet.class));
  }

  // ── ② 豁免集为空 ──────────────────────────────────────────────────────────────────

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("ProductionData 没有豁免项：八表都必须进变更集").isEmpty();
  }

  @Test
  void changeSetCoversEveryStateComponentWithNoExemptions() {
    Set<String> covered = new LinkedHashSet<>(componentNames(ProductionChangeSet.class));
    covered.addAll(EXCLUDED_FROM_CHANGE_SET);
    assertThat(covered)
        .as("变更集组件 ∪ 豁免集 必须恰好覆盖 ProductionData 的全部组件")
        .containsExactlyInAnyOrderElementsOf(componentNames(ProductionData.class));
  }

  // ── ③ 八张表都非空的逐字段往返（含"又删又改"与嵌套内层）──────────────────────────────────

  @Test
  void allEightNonEmptyTablesRoundTripFieldByFieldWithRemovalsAndOverwrites() {
    ProductionData base = full();

    // industries：删 I1、覆盖 I2 的产出、加 I3。
    Map<IndustryId, IndustryDefinitionView> targetIndustries = new LinkedHashMap<>();
    targetIndustries.put(I2, industry(I2, "织布（改）", CLOTH, 9L));
    targetIndustries.put(I3, industry(I3, "作坊", GRAIN, 3L));

    // modes：删 M1、覆盖 M2 的版本与结构、加 M3。
    Map<ProductionModeId, ProductionModeDefinitionView> targetModes = new LinkedHashMap<>();
    targetModes.put(M2, mode(M2, "资本主义（v2）", 2, CS2));
    targetModes.put(M3, mode(M3, "自给", 1, CS3));

    // instances：删 U1、覆盖 U2 的进度、加 U3。
    Map<ProductionUnitId, ProductionProcess> targetInstances = new LinkedHashMap<>();
    targetInstances.put(U2, instance(U2, I2, OPERATOR_1, 4L));
    targetInstances.put(U3, instance(U3, I3, OPERATOR_2, 1L));

    // experienceTracks：删 TRACK_A、覆盖 TRACK_B 的换算、加 TRACK_C。
    Map<ExperienceTrackId, ExperienceTrack> targetTracks = new LinkedHashMap<>();
    targetTracks.put(TRACK_B, track(TRACK_B, "织布（改）", 200L, 7L));
    targetTracks.put(TRACK_C, track(TRACK_C, "作坊", 300L, 2L));

    // householdExperience：删 (H1,A)、覆盖 (H1,B) 的整条特征、加 (H2,C)（复合键两侧都换）。
    Map<HouseholdExperienceKey, ProductionExperienceFeature> targetExperience =
        new LinkedHashMap<>();
    targetExperience.put(
        new HouseholdExperienceKey(H1, TRACK_B), new ProductionExperienceFeature(41L, 250L));
    targetExperience.put(
        new HouseholdExperienceKey(H2, TRACK_C), new ProductionExperienceFeature(7L, 125L));

    // unlocks：删 H1、覆盖 H2 的集合、加 H3。
    Map<HouseholdId, Set<IndustryId>> targetUnlocks = new LinkedHashMap<>();
    targetUnlocks.put(H2, new LinkedHashSet<>(Set.of(I1, I3)));
    targetUnlocks.put(H3, new LinkedHashSet<>(Set.of(I2)));

    // efficiency：删 U1、覆盖 U2 的三段余数、加 U3。
    Map<ProductionUnitId, ProductionEfficiencyState> targetEfficiency = new LinkedHashMap<>();
    targetEfficiency.put(U2, new ProductionEfficiencyState(new PerCycleCarry(1L, 2L, 3L)));
    targetEfficiency.put(U3, new ProductionEfficiencyState(new PerCycleCarry(4L, 5L, 6L)));

    // outputQuantityOverrides：删 I1、覆盖 I2 的内层表、加 I3。
    Map<IndustryId, Map<CommodityId, Long>> targetOverrides = new LinkedHashMap<>();
    targetOverrides.put(I2, new LinkedHashMap<>(Map.of(CLOTH, 12L)));
    targetOverrides.put(I3, new LinkedHashMap<>(Map.of(TOOL, 3L)));

    ProductionData target =
        new ProductionData(
            targetIndustries,
            targetModes,
            targetInstances,
            targetTracks,
            targetExperience,
            targetUnlocks,
            targetEfficiency,
            targetOverrides);
    ProductionChangeSet cs = ProductionChangeSet.between(base, target);

    assertThat(cs.isEmpty()).isFalse();
    assertThat(cs.industries()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.modes()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.instances()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.experienceTracks()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.householdExperience())
        .as("复合键又删又改又加 ⇒ Patch")
        .isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.unlocks()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.efficiency()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.outputQuantityOverrides())
        .as("外层又删又改又加 ⇒ Patch")
        .isInstanceOf(FieldDelta.Patch.class);

    ProductionData rebuilt = ProductionChangeSet.apply(cs, base);

    assertThat(rebuilt.industries()).containsOnlyKeys(I2, I3);
    assertThat(rebuilt.industries().get(I2).name()).as("I2 被覆盖").isEqualTo("织布（改）");
    assertThat(rebuilt.industries().get(I2).outputPerUnit()).containsEntry(CLOTH, 9L);
    assertThat(rebuilt.industries().get(I3).name()).as("新增的 IndustryId 键被解析回来").isEqualTo("作坊");
    assertThat(rebuilt.industries().get(I3).outputPerUnit()).containsEntry(GRAIN, 3L);
    assertThat(rebuilt.industries()).as("被删的 I1 不许留在重建结果里").doesNotContainKey(I1);

    assertThat(rebuilt.modes()).containsOnlyKeys(M2, M3);
    assertThat(rebuilt.modes().get(M2).version()).as("version 被覆盖").isEqualTo(2);
    assertThat(rebuilt.modes().get(M2).classStructureId()).isEqualTo(CS2);
    assertThat(rebuilt.modes().get(M3).name()).as("新增的 ProductionModeId 键被解析回来").isEqualTo("自给");
    assertThat(rebuilt.modes()).as("被删的 M1 不许留下").doesNotContainKey(M1);

    assertThat(rebuilt.instances()).containsOnlyKeys(U2, U3);
    assertThat(rebuilt.instances().get(U2).progressDays()).as("进度被覆盖").isEqualTo(4L);
    assertThat(rebuilt.instances().get(U2).operator()).isEqualTo(OPERATOR_1);
    assertThat(rebuilt.instances().get(U2).location()).as("显式地点往返").isEqualTo(LOC_0_0);
    assertThat(rebuilt.instances().get(U3).plan()).as("新增的 ProductionUnitId 键被解析回来").isEqualTo(I3);
    assertThat(rebuilt.instances()).as("被删的 U1 不许留下").doesNotContainKey(U1);

    assertThat(rebuilt.experienceTracks()).containsOnlyKeys(TRACK_B, TRACK_C);
    assertThat(rebuilt.experienceTracks().get(TRACK_B).growthPerLaborMilli())
        .as("增长换算被覆盖")
        .isEqualTo(7L);
    assertThat(rebuilt.experienceTracks().get(TRACK_B).masteryFormula())
        .as("公式（sealed 多态）往返")
        .isEqualTo(ExpressionDefinition.NEUTRAL_PER_MILLE);
    assertThat(rebuilt.experienceTracks().get(TRACK_C).name())
        .as("新增的 ExperienceTrackId 键被解析回来")
        .isEqualTo("作坊");
    assertThat(rebuilt.experienceTracks()).as("被删的 TRACK_A 不许留下").doesNotContainKey(TRACK_A);

    assertThat(rebuilt.householdExperience())
        .containsOnlyKeys(
            new HouseholdExperienceKey(H1, TRACK_B), new HouseholdExperienceKey(H2, TRACK_C));
    assertThat(rebuilt.householdExperience().get(new HouseholdExperienceKey(H1, TRACK_B)))
        .as("★ 整条特征（主值 + 余数）被覆盖，不是只搬主值")
        .isEqualTo(new ProductionExperienceFeature(41L, 250L));
    assertThat(
            rebuilt.householdExperience().get(new HouseholdExperienceKey(H1, TRACK_B)).remainder())
        .as("余数必须活着过 JSON/变更集（复核裁决 §4 第 3 条）")
        .isEqualTo(250L);
    assertThat(rebuilt.experienceOf(H2, TRACK_C)).as("新增复合键读回来").isEqualTo(7L);
    assertThat(rebuilt.householdExperience())
        .as("被删的 (H1, farming) 不许留下")
        .doesNotContainKey(new HouseholdExperienceKey(H1, TRACK_A));

    assertThat(rebuilt.unlocks()).containsOnlyKeys(H2, H3);
    assertThat(rebuilt.unlocks().get(H2)).as("集合被覆盖").containsExactlyInAnyOrder(I1, I3);
    assertThat(rebuilt.unlocksOf(H3)).as("新增的 HouseholdId 键被解析回来").containsExactly(I2);
    assertThat(rebuilt.unlocks()).as("被删的 H1 不许留下").doesNotContainKey(H1);

    assertThat(rebuilt.efficiency()).containsOnlyKeys(U2, U3);
    assertThat(rebuilt.efficiency().get(U2).carry())
        .as("效率余数三段被覆盖")
        .isEqualTo(new PerCycleCarry(1L, 2L, 3L));
    assertThat(rebuilt.efficiency().get(U3).carry())
        .as("新增的 ProductionUnitId 键被解析回来")
        .isEqualTo(new PerCycleCarry(4L, 5L, 6L));
    assertThat(rebuilt.efficiency()).as("被删的 U1 不许留下").doesNotContainKey(U1);

    assertThat(rebuilt.outputQuantityOverrides()).containsOnlyKeys(I2, I3);
    assertThat(rebuilt.outputQuantityOverrideOf(I2)).as("内层商品表被覆盖").containsEntry(CLOTH, 12L);
    assertThat(rebuilt.outputQuantityOverrideOf(I3))
        .as("新增的 IndustryId 键被解析回来")
        .containsEntry(TOOL, 3L);
    assertThat(rebuilt.outputQuantityOverrides()).as("被删的 I1 不许留下").doesNotContainKey(I1);

    assertThat(rebuilt)
        .as("铁律 5 的原文：apply(between(base, target), base) 逐字段重建出 target")
        .isEqualTo(target);
  }

  @Test
  void emptyFixtureRoundTripsToTheFullFixture() {
    RoundTripAssertions.assertRoundTrip(
        ProductionData.empty(), full(), ProductionChangeSet::between, ProductionChangeSet::apply);
  }

  // ── ④ 变更集自身语义 ────────────────────────────────────────────────────────────────

  @Test
  void identicalStatesProduceAnEmptyChangeSetOfUnchangedComponents() {
    ProductionData state = full();
    ProductionChangeSet cs = ProductionChangeSet.between(state, state);

    assertThat(cs.isEmpty()).isTrue();
    assertThat(cs.industries()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.modes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.instances()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.experienceTracks()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.householdExperience()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.unlocks()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.efficiency()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.outputQuantityOverrides()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(ProductionChangeSet.apply(cs, state)).as("一字未动的往返必须恒等").isEqualTo(state);
  }

  @Test
  void aChangeSetWithMissingComponentKeysIsNormalizedToUnchanged() {
    ProductionChangeSet fromMissingKeys =
        new ProductionChangeSet(null, null, null, null, null, null, null, null);

    assertThat(fromMissingKeys.isEmpty()).as("缺键 ⇒ 八个组件都是 Unchanged").isTrue();
    assertThat(fromMissingKeys.industries()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.modes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.instances()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.experienceTracks()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.householdExperience()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.unlocks()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.efficiency()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.outputQuantityOverrides()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(ProductionChangeSet.apply(fromMissingKeys, full())).as("不抛且恒等").isEqualTo(full());
  }

  // ── ⑤ 状态集合隔离（"元数据更新与集合隔离"判据）────────────────────────────────────────

  @Test
  void sourceMapMutationDoesNotLeakIntoStateAndReturnedMapsAreUnmodifiable() {
    Map<IndustryId, IndustryDefinitionView> sourceIndustries = new LinkedHashMap<>();
    sourceIndustries.put(I1, industry(I1, "农业", GRAIN, 60L));
    Map<ProductionModeId, ProductionModeDefinitionView> sourceModes = new LinkedHashMap<>();
    sourceModes.put(M1, mode(M1, "小农", 1, CS1));
    Map<ProductionUnitId, ProductionProcess> sourceInstances = new LinkedHashMap<>();
    sourceInstances.put(U1, instance(U1, I1, OPERATOR_1, 0L));
    Map<ExperienceTrackId, ExperienceTrack> sourceTracks = new LinkedHashMap<>();
    sourceTracks.put(TRACK_A, track(TRACK_A, "农业", 100L, 5L));
    Map<HouseholdExperienceKey, ProductionExperienceFeature> sourceExperience =
        new LinkedHashMap<>();
    sourceExperience.put(
        new HouseholdExperienceKey(H1, TRACK_A), new ProductionExperienceFeature(3L, 0L));
    Map<HouseholdId, Set<IndustryId>> sourceUnlocks = new LinkedHashMap<>();
    sourceUnlocks.put(H1, new LinkedHashSet<>(Set.of(I1)));
    Map<ProductionUnitId, ProductionEfficiencyState> sourceEfficiency = new LinkedHashMap<>();
    sourceEfficiency.put(U1, new ProductionEfficiencyState(new PerCycleCarry(1L, 0L, 0L)));
    Map<IndustryId, Map<CommodityId, Long>> sourceOverrides = new LinkedHashMap<>();
    sourceOverrides.put(I1, new LinkedHashMap<>(Map.of(GRAIN, 5L)));

    ProductionData data =
        new ProductionData(
            sourceIndustries,
            sourceModes,
            sourceInstances,
            sourceTracks,
            sourceExperience,
            sourceUnlocks,
            sourceEfficiency,
            sourceOverrides);

    sourceIndustries.put(I2, industry(I2, "织布", CLOTH, 5L));
    sourceModes.put(M2, mode(M2, "资本", 1, CS2));
    sourceInstances.put(U2, instance(U2, I2, OPERATOR_2, 0L));
    sourceTracks.put(TRACK_B, track(TRACK_B, "织布", 100L, 5L));
    sourceExperience.put(
        new HouseholdExperienceKey(H1, TRACK_B), new ProductionExperienceFeature(9L, 0L));
    sourceUnlocks.put(H2, new LinkedHashSet<>(Set.of(I2)));
    sourceEfficiency.put(U2, new ProductionEfficiencyState(new PerCycleCarry(2L, 0L, 0L)));
    sourceOverrides.put(I2, new LinkedHashMap<>(Map.of(CLOTH, 6L)));

    assertThat(data.industries()).as("源产业表后改不得渗进状态").containsOnlyKeys(I1);
    assertThat(data.modes()).as("源模式表后改不得渗进状态").containsOnlyKeys(M1);
    assertThat(data.instances()).as("源实例表后改不得渗进状态").containsOnlyKeys(U1);
    assertThat(data.experienceTracks()).as("源经验条表后改不得渗进状态").containsOnlyKeys(TRACK_A);
    assertThat(data.householdExperience()).as("源经验账后改不得渗进状态").hasSize(1);
    assertThat(data.unlocks()).as("源解锁表后改不得渗进状态").containsOnlyKeys(H1);
    assertThat(data.efficiency()).as("源效率余数表后改不得渗进状态").containsOnlyKeys(U1);
    assertThat(data.outputQuantityOverrides()).as("源覆盖表后改不得渗进状态").containsOnlyKeys(I1);

    assertThatThrownBy(() -> data.industries().put(I3, industry(I3, "作坊", GRAIN, 1L)))
        .as("对外交出的方案表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> data.modes().put(M3, mode(M3, "自给", 1, CS3)))
        .as("对外交出的模式表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> data.instances().put(U3, instance(U3, I3, OPERATOR_2, 0L)))
        .as("对外交出的实例表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> data.experienceTracks().put(TRACK_C, track(TRACK_C, "作坊", 1L, 1L)))
        .as("对外交出的经验条表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                data.householdExperience()
                    .put(
                        new HouseholdExperienceKey(H3, TRACK_A),
                        new ProductionExperienceFeature(1L, 0L)))
        .as("对外交出的家户经验账必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> data.unlocks().put(H3, new LinkedHashSet<>(Set.of(I3))))
        .as("对外交出的解锁表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                data.efficiency()
                    .put(U3, new ProductionEfficiencyState(new PerCycleCarry(0L, 0L, 1L))))
        .as("对外交出的效率余数表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> data.outputQuantityOverrideOf(I1).put(GRAIN, 1L))
        .as("对外交出的内层覆盖表也必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void applyToTakesTheNewMetaCoordinates() {
    ProductionSnapshot base =
        new ProductionSnapshot(new StateRef(new BranchId("main"), new RevisionId(7L)), AT, full());
    ProductionData target = mutate(full(), "instances");
    ProductionChangeSet cs = ProductionChangeSet.between(base.state(), target);
    StateMeta newMeta =
        new StateMeta(
            new StateRef(new BranchId("main"), new RevisionId(9L)),
            new SimosTimestamp(200L, Optional.empty()));

    ProductionSnapshot applied = cs.applyTo(base, newMeta);

    assertThat(applied.ref()).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(9L)));
    assertThat(applied.timestamp()).isEqualTo(new SimosTimestamp(200L, Optional.empty()));
    assertThat(applied.state()).isEqualTo(target);
  }

  // ── ⑥ codec 的 JSON 往返：八把键读侧 + diff/apply 接缝 ────────────────────────────────

  @Test
  void productionCodecJsonRoundTripsAllTables() {
    ProductionCodec codec = new ProductionCodec();
    ProductionData state = full();
    ProductionSnapshot snapshot =
        new ProductionSnapshot(new StateRef(new BranchId("main"), new RevisionId(7L)), AT, state);

    String json = codec.encodeSnapshot(snapshot);
    ProductionSnapshot decoded = (ProductionSnapshot) codec.decodeSnapshot(json);

    assertThat(decoded.state()).as("八张非空表过 JSON 后逐字段相同").isEqualTo(state);
    assertThat(decoded.state().industries().keySet())
        .as("IndustryId 键读回来了")
        .containsExactly(I1, I2);
    assertThat(decoded.state().industries().get(I1).allocation())
        .as("分配函数 sealed 多态（@class）读回来了")
        .isEqualTo(new AllocationRule.Split(500, 500));
    assertThat(decoded.state().industries().get(I1).moneyOutputPerUnit().isEmpty())
        .as("MoneyOutput 的 isEmpty 派生判断在裸 mapper/Timeline 往返后仍成立（NONE = 无铸币）")
        .isTrue();
    assertThat(decoded.state().modes().keySet())
        .as("ProductionModeId 键读回来了")
        .containsExactly(M1, M2);
    assertThat(decoded.state().instances().keySet())
        .as("ProductionUnitId 键读回来了")
        .containsExactly(U1, U2);
    assertThat(decoded.state().instances().get(U1).location())
        .as("ProductionLocation 组件往返")
        .isEqualTo(LOC_0_0);
    assertThat(decoded.state().instances().get(U1).cycleContributions().keySet())
        .as("实例内层逐 (家户×角色) 贡献台账也过 JSON")
        .containsExactly(
            new ProductionParticipantKey(H1, ProductionRoleDefinition.defaultOperator().id()));
    assertThat(decoded.state().experienceTracks().keySet())
        .as("ExperienceTrackId 键读回来了")
        .containsExactly(TRACK_A, TRACK_B);
    assertThat(decoded.state().householdExperience().keySet())
        .as("HouseholdExperienceKey 的 <household>@<track> 复合键读回来了")
        .containsExactly(
            new HouseholdExperienceKey(H1, TRACK_A), new HouseholdExperienceKey(H1, TRACK_B));
    assertThat(decoded.state().unlocks().keySet())
        .as("HouseholdId 键读回来了（集合值也读回来）")
        .containsExactly(H1, H2);
    assertThat(decoded.state().unlocksOf(H2)).containsExactly(I2);
    assertThat(decoded.state().efficiency().keySet())
        .as("效率余数表的 ProductionUnitId 键读回来了")
        .containsExactly(U1, U2);
    assertThat(decoded.state().efficiency().get(U1).carry())
        .as("三段余数逐值读回来")
        .isEqualTo(new PerCycleCarry(3L, 4L, 5L));
    assertThat(decoded.state().outputQuantityOverrides().keySet())
        .as("GM 产出覆盖的方案键读回来了")
        .containsExactly(I1, I2);

    ProductionData target =
        mutate(
            mutate(
                mutate(
                    mutate(
                        mutate(
                            mutate(mutate(mutate(state, "industries"), "modes"), "instances"),
                            "experienceTracks"),
                        "householdExperience"),
                    "unlocks"),
                "efficiency"),
            "outputQuantityOverrides");
    ProductionChangeSet cs = ProductionChangeSet.between(state, target);
    ProductionChangeSet decodedCs =
        (ProductionChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(cs));
    assertThat(ProductionChangeSet.apply(decodedCs, decoded.state())).isEqualTo(target);

    ProductionSnapshot targetSnapshot =
        new ProductionSnapshot(new StateRef(new BranchId("main"), new RevisionId(8L)), AT, target);
    assertThat(codec.diff(decoded, targetSnapshot)).isEqualTo(cs);

    String emptyJson =
        codec.encodeSnapshot(
            new ProductionSnapshot(
                new StateRef(new BranchId("main"), new RevisionId(7L)),
                AT,
                ProductionData.empty()));
    assertThat(((ProductionSnapshot) codec.decodeSnapshot(emptyJson)).state())
        .isEqualTo(ProductionData.empty());

    StateMeta meta = new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9L)), AT);
    ProductionSnapshot applied = (ProductionSnapshot) codec.apply(decodedCs, decoded, meta);
    assertThat(applied.ref()).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(9L)));
    assertThat(applied.state()).isEqualTo(target);
  }

  @Test
  void namespaceLiteralsAgreeBetweenSnapshotAndCodec() {
    assertThat(new ProductionCodec().namespace())
        .as("codec 与 snapshot 的 namespace 同字面（装配期有牙）")
        .isEqualTo(
            new ProductionSnapshot(
                    new StateRef(new BranchId("main"), new RevisionId(1L)),
                    AT,
                    ProductionData.empty())
                .namespace())
        .isEqualTo("production");
  }

  // ── 夹具与反射小工具 ────────────────────────────────────────────────────────────────

  private static ProductionData full() {
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();
    industries.put(I1, industry(I1, "农业", GRAIN, 60L));
    industries.put(I2, industry(I2, "织布", CLOTH, 4L));
    Map<ProductionModeId, ProductionModeDefinitionView> modes = new LinkedHashMap<>();
    modes.put(M1, mode(M1, "小农", 1, CS1));
    modes.put(M2, mode(M2, "资本主义", 1, CS2));
    Map<ProductionUnitId, ProductionProcess> instances = new LinkedHashMap<>();
    instances.put(U1, instance(U1, I1, OPERATOR_1, 2L));
    instances.put(U2, instance(U2, I2, OPERATOR_2, 3L));
    Map<ExperienceTrackId, ExperienceTrack> tracks = new LinkedHashMap<>();
    tracks.put(TRACK_A, track(TRACK_A, "农业", 100L, 5L));
    tracks.put(TRACK_B, track(TRACK_B, "织布", 150L, 6L));
    Map<HouseholdExperienceKey, ProductionExperienceFeature> experience = new LinkedHashMap<>();
    experience.put(
        new HouseholdExperienceKey(H1, TRACK_A), new ProductionExperienceFeature(12L, 500L));
    experience.put(
        new HouseholdExperienceKey(H1, TRACK_B), new ProductionExperienceFeature(3L, 25L));
    Map<HouseholdId, Set<IndustryId>> unlocks = new LinkedHashMap<>();
    unlocks.put(H1, new LinkedHashSet<>(Set.of(I1)));
    unlocks.put(H2, new LinkedHashSet<>(Set.of(I2)));
    Map<ProductionUnitId, ProductionEfficiencyState> efficiency = new LinkedHashMap<>();
    efficiency.put(U1, new ProductionEfficiencyState(new PerCycleCarry(3L, 4L, 5L)));
    efficiency.put(U2, new ProductionEfficiencyState(new PerCycleCarry(6L, 7L, 8L)));
    Map<IndustryId, Map<CommodityId, Long>> overrides = new LinkedHashMap<>();
    overrides.put(I1, new LinkedHashMap<>(Map.of(GRAIN, 11L)));
    overrides.put(I2, new LinkedHashMap<>(Map.of(CLOTH, 22L)));
    return new ProductionData(
        industries, modes, instances, tracks, experience, unlocks, efficiency, overrides);
  }

  private static IndustryDefinitionView industry(
      IndustryId id, String name, CommodityId output, long outputPerUnit) {
    Map<AssetKind, Long> capacityPerUnit = new LinkedHashMap<>();
    capacityPerUnit.put(AssetKind.LAND, 1000L);
    Map<CommodityId, Long> outputTable = new LinkedHashMap<>();
    outputTable.put(output, outputPerUnit);
    List<ClassSlot> slots = List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 950));
    return new IndustryDefinitionView(
        id,
        name,
        PEASANT,
        Optional.empty(),
        10L,
        0L,
        capacityPerUnit,
        Map.of(),
        0L,
        120L,
        outputTable,
        Map.of(),
        slots,
        new AllocationRule.Split(500, 500),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of());
  }

  private static ProductionModeDefinitionView mode(
      ProductionModeId id, String name, int version, ClassStructureId structure) {
    return new ProductionModeDefinitionView(id, name, version, structure);
  }

  private static ProductionProcess instance(
      ProductionUnitId id, IndustryId plan, ActorRef operator, long progressDays) {
    Map<ProductionParticipantKey, CycleContribution> contributions = new LinkedHashMap<>();
    ProductionParticipantKey participantKey =
        new ProductionParticipantKey(H1, ProductionRoleDefinition.defaultOperator().id());
    contributions.put(
        participantKey,
        new CycleContribution(
            H1, ProductionRoleDefinition.defaultOperator().id(), 100L, 100L * 1_000L));
    return new ProductionProcess(
        id,
        plan,
        LOC_0_0,
        operator,
        plan.value(),
        progressDays,
        contributions,
        new LinkedHashMap<>(Map.of(GRAIN, 7L)));
  }

  private static ExperienceTrack track(
      ExperienceTrackId id, String name, long maxExperience, long growth) {
    return new ExperienceTrack(
        id, name, maxExperience, growth, 1L, ExpressionDefinition.NEUTRAL_PER_MILLE);
  }

  private static ProductionData mutate(ProductionData base, String name) {
    return switch (name) {
      case "industries" -> base.withIndustry(industry(I2, "织布（改）", CLOTH, 9L));
      case "modes" -> base.withMode(mode(M2, "资本主义（v2）", 2, CS2));
      case "instances" -> base.withInstance(instance(U1, I1, OPERATOR_1, 9L));
      case "experienceTracks" -> base.withExperienceTrack(track(TRACK_A, "农业（改）", 100L, 9L));
      case "householdExperience" ->
          base.withHouseholdExperience(H1, TRACK_A, new ProductionExperienceFeature(99L, 999L));
      case "unlocks" -> base.withUnlock(H1, I3);
      case "efficiency" ->
          base.withEfficiencyState(
              U1, new ProductionEfficiencyState(new PerCycleCarry(9L, 9L, 9L)));
      case "outputQuantityOverrides" -> base.withOutputQuantityOverride(I1, GRAIN, 77L);
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(ProductionChangeSet cs, String name) {
    return switch (name) {
      case "industries" -> cs.industries().changed();
      case "modes" -> cs.modes().changed();
      case "instances" -> cs.instances().changed();
      case "experienceTracks" -> cs.experienceTracks().changed();
      case "householdExperience" -> cs.householdExperience().changed();
      case "unlocks" -> cs.unlocks().changed();
      case "efficiency" -> cs.efficiency().changed();
      case "outputQuantityOverrides" -> cs.outputQuantityOverrides().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }
}
