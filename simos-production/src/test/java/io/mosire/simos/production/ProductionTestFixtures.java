package io.mosire.simos.production;

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
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.ExperienceGrowth;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.GrowthBasis;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>T2a 判据用例的公共世界夹具</b>（D-032 测试阶段；`control-v1v12.md` §9.1 的施工图）。
 *
 * <p>★★ <b>裁定二十七（占位断言）在这里的落形</b>：所有工厂方法都产<b>真实数据</b>（真实方案 + 真实实例 + 真实经验条 +
 * 真实经验账）；唯一允许的空表是"这个方案/这个实例<b>本来就没有</b>那一路"（例：不设原料通道 ⇒ {@code inputPerUnit} 空；刚建的实例 ⇒
 * 周期台账空），且每一处在调用点的名字/注释里写明语义。 <b>不</b>提供"随便给张空表让断言恒真"的口子。
 *
 * <p>★ <b>数值口径</b>：效率公式一律用 {@code 1 + a}（{@code a} = 专门熟练度，千分制）⇒ 熟练度 500 ⇒ {@code E = 1500}、熟练度 505
 * ⇒ {@code E = 1505}；熟练度解释用恒等公式（熟练度 = 经验值）， 让手算样例里的每个数字都能一眼对上（A3-§5 的样例一~五）。
 */
final class ProductionTestFixtures {

  private ProductionTestFixtures() {}

  static final BranchId MAIN = new BranchId("main");
  static final StateRef REF = new StateRef(MAIN, new RevisionId(7L));
  static final SimosTimestamp AT = new SimosTimestamp(0L, Optional.empty());
  static final SimosTimestamp AT_LATER = new SimosTimestamp(86_400L, Optional.empty());

  static final HouseholdId H1 = new HouseholdId("h-1");
  static final HouseholdId H2 = new HouseholdId("h-2");
  static final HouseholdId H3 = new HouseholdId("h-3");

  static final ExperienceTrackId FARMING = new ExperienceTrackId("farming");
  static final ExperienceTrackId MILLING = new ExperienceTrackId("milling");

  static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  static final IndustryId MILL = IndustryHexKeys.id("mill", 0, 0);
  static final IndustryId CRAFT = IndustryHexKeys.id("craft", 0, 0);

  static final ProductionUnitId U1 = new ProductionUnitId("unit-farm-1");
  static final ProductionUnitId U2 = new ProductionUnitId("unit-mill-1");
  static final ProductionUnitId U3 = new ProductionUnitId("unit-craft-1");

  static final CommodityId GRAIN = new CommodityId("grain");
  static final CommodityId CLOTH = new CommodityId("cloth");

  static final RegimeId TENANT = new RegimeId("tenant");
  static final ClassStructureId CS = new ClassStructureId("cs-peasant");
  static final ProductionModeId MODE = new ProductionModeId("peasant@0_0");
  static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "h-1");

  /** 效率倍率公式 {@code 1 + a}（{@code a} = 专门熟练度，千分制）：熟练度 500 ⇒ 1500‰。 */
  static ExpressionDefinition onePlusPrimaryMastery() {
    return new ExpressionDefinition(
        ExpressionDefinition.CURRENT_VERSION,
        List.of(ProductionExperienceUse.PRIMARY_MASTERY_INPUT),
        new RestrictedExpression.Add(
            new RestrictedExpression.Const(1_000L),
            new RestrictedExpression.Input(ProductionExperienceUse.PRIMARY_MASTERY_INPUT)));
  }

  /** 熟练度解释：熟练度（千分制）= 经验值（恒等）——让手算样例的"经验 500 ⇒ 熟练度 500"成立。 */
  static ExpressionDefinition identityMastery() {
    return ExpressionDefinition.ofSingleInput(
        ExperienceTrack.EXPERIENCE_INPUT,
        new RestrictedExpression.Input(ExperienceTrack.EXPERIENCE_INPUT));
  }

  /** 恒等倍率（1000‰，不做加成）——用来把"经验账"与"规模公式"两件事隔离观察。 */
  static ProductionExperienceUse neutralUse(ExperienceTrackId primary) {
    return new ProductionExperienceUse(
        Optional.of(primary),
        Optional.empty(),
        ProductionEffectScope.TOTAL,
        ExpressionDefinition.NEUTRAL_PER_MILLE,
        0L);
  }

  /** 效率面：专门经验条 + {@code 1 + a} + 给定解锁门槛；共用条缺（{@code Optional.empty()}）。 */
  static ProductionExperienceUse totalUse(
      ExperienceTrackId primary, ProductionEffectScope scope, long unlockAtPrimaryExperience) {
    return new ProductionExperienceUse(
        Optional.of(primary),
        Optional.empty(),
        scope,
        onePlusPrimaryMastery(),
        unlockAtPrimaryExperience);
  }

  /** 经验条定义（熟练度解释 = 恒等；上限/增长/衰减逐项给定）。 */
  static ExperienceTrack track(
      ExperienceTrackId id, long maxExperience, long growthPerLaborMilli, long decayPerTick) {
    return new ExperienceTrack(
        id, id.value(), maxExperience, growthPerLaborMilli, decayPerTick, identityMastery());
  }

  /**
   * 方案定义（18 组件里与本批判据无关的几路取"本来就没有"的显式值：无技能、无日投入、无铸币、无可用性条件）。
   *
   * <p>★ 原料那一路的口径唯一落点是 {@code cycleInputPerUnit}（按生产资料分路），{@code inputPerUnit()} 由它合计而来 ——
   * 本夹具把给定的商品表挂在 {@code LAND} 这一路上（"每单位规模每周期从土地分路吃掉这些原料"）， 因为本批判据只关心合计值，不关心分路。
   */
  static IndustryDefinitionView plan(
      IndustryId id,
      long cycleDays,
      long laborPerUnit,
      Map<AssetKind, Long> capacityPerUnit,
      Map<CommodityId, Long> inputPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      ProductionExperienceUse use) {
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit =
        inputPerUnit.isEmpty()
            ? Map.of()
            : Map.of(AssetKind.LAND, new LinkedHashMap<>(inputPerUnit));
    return new IndustryDefinitionView(
        id,
        "方案 " + id.value(),
        TENANT,
        Optional.empty(),
        cycleDays,
        0L,
        capacityPerUnit,
        Map.of(),
        0L,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        use,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  /**
   * ★★ D1 迁移夹具：方案级经验面之外，**角色**也必须声明 {@link ExperienceGrowth}（D1 §2.2）。
   *
   * <p>本助手把测试世界的历史口径（增长系数在 {@link ExperienceTrack#growthPerLaborMilli}）逐值保留：角色系数取 {@code
   * 1000}（不额外缩放），增长按实际劳动计。旧测试只改夹具，不改任何期望值。
   */
  static IndustryDefinitionView planWithRoleGrowth(
      IndustryId id,
      long cycleDays,
      long laborPerUnit,
      Map<AssetKind, Long> capacityPerUnit,
      Map<CommodityId, Long> inputPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      ProductionExperienceUse use,
      ExperienceTrackId growthTrack) {
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit =
        inputPerUnit.isEmpty()
            ? Map.of()
            : Map.of(AssetKind.LAND, new LinkedHashMap<>(inputPerUnit));
    ProductionRoleDefinition operator =
        new ProductionRoleDefinition(
            ProductionRoleDefinition.defaultOperator().id(),
            "经营者",
            Optional.empty(),
            1_000L,
            IntraRoleSplitRule.EQUAL,
            0L,
            Optional.of(
                new ExperienceGrowth(growthTrack, GrowthBasis.ACTUAL_LABOR, 1_000L, 0L, false)),
            List.of(),
            new RolePayment.ProductShare());
    return new IndustryDefinitionView(
        id,
        "方案 " + id.value(),
        TENANT,
        Optional.empty(),
        cycleDays,
        0L,
        capacityPerUnit,
        Map.of(),
        0L,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        use,
        List.of(),
        Set.of(AssetRightKind.OWNED),
        List.of(operator),
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.empty());
  }

  /** 生产方式定义（mode 面不属于本批判据，只在需要"世界里有 mode"时用）。 */
  static ProductionModeDefinitionView mode(ProductionModeId id, int version) {
    return new ProductionModeDefinitionView(id, "生产方式 " + id.value(), version, CS);
  }

  /** 新实例：进度 0、周期台账空（"刚立起来、还没出过工"的合法起点）。 */
  static ProductionProcess instance(ProductionUnitId unit, IndustryId plan, String locationHex) {
    return new ProductionProcess(
        unit,
        plan,
        new ProductionLocation(locationHex),
        OPERATOR,
        plan.value(),
        0L,
        Map.of(),
        Map.of());
  }

  /**
   * 家户条件：劳动 + 资产 + 原料 + 熟练度 + 经验五路都给真实值。
   *
   * <p>★ 缺键语义是"这家户没有这种资源/还没这门经验"（{@link HouseholdProductionConditions} 类注）， 故这里全部显式给出，避免"缺键 =
   * 0"把某个通道悄悄关掉。
   */
  static HouseholdProductionConditions conditions(
      HouseholdId household,
      long laborMilli,
      Map<AssetKind, Long> assets,
      Map<CommodityId, Long> inputs,
      Map<ExperienceTrackId, Long> masteryPerMille,
      Map<ExperienceTrackId, Long> experience) {
    return new HouseholdProductionConditions(
        household, laborMilli, assets, inputs, masteryPerMille, experience);
  }

  /** 一条家户经验账的键。 */
  static HouseholdExperienceKey experienceKey(HouseholdId household, ExperienceTrackId track) {
    return new HouseholdExperienceKey(household, track);
  }

  /** 一条家户经验特征（主值 + 余数）。 */
  static ProductionExperienceFeature feature(long value, long remainder) {
    return new ProductionExperienceFeature(value, remainder);
  }
}
