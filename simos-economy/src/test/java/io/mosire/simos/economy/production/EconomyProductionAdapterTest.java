package io.mosire.simos.economy.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionRecipe;
import io.mosire.simos.production.contract.ProductionSkillKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>production 定义权威与 economy 侧劳动只读适配器的忠实映射（D-031 P1 spec §5 V1/V3/N9）</b>。
 *
 * <p>P1 把定义权威整体迁入 production：旧 {@code EconomyProductionDefinitionLookup}（economy 定义适配器）已删除， 本类改为直接钉
 * {@link ProductionData} 的定义读口；同时保留 P0 的 {@link EconomyProductionLaborLookup} 劳动事实能力（P1
 * 明确只删定义适配器、不删劳动能力）。钉住：
 *
 * <ul>
 *   <li>① 全定义字段忠实（尤其 {@code dailyLaborPerUnit} 与 {@code moneyOutputPerUnit}）；
 *   <li>② 无运行实例字段；③ mode 用自身稳定 ID；④ 配方按产业派生（没有独立 {@code RecipeId} 权威表）；
 *   <li>⑤ 劳动按持久 {@code allocations} 的 ID 相等匹配 unit、同户合并、0 劳动排除、不过滤承诺种类、不反查经营者。
 * </ul>
 */
class EconomyProductionAdapterTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final RegimeId TENANT = new RegimeId("tenant");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, FARM.value());
  private static final ActorRef OTHER_OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "other-org");
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(FARM, OPERATOR);
  private static final ProductionUnitId OTHER_UNIT = ProductionUnitId.idOf(FARM, OTHER_OPERATOR);
  private static final CohortKey VIEW =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final HouseholdId HOUSEHOLD = HouseholdIds.ofLegacy(VIEW);
  private static final HouseholdId HOUSEHOLD_2 = new HouseholdId("hh-2");
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final PeopleLotId LOT_2 = new PeopleLotId("rural:0_0:FEMALE:1");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final ProductionSkillKey FARMING = new ProductionSkillKey("arable-farming");

  // ── ① production 定义权威的忠实字段 ────────────────────────────────────────────────

  @Test
  void industryViewCarriesEveryDefinitionFieldFaithfully() {
    IndustryDefinitionView view = definitions(FARMING).industry(FARM).orElseThrow();

    assertThat(view.id()).isEqualTo(FARM);
    assertThat(view.name()).isEqualTo("农业");
    assertThat(view.regime()).isEqualTo(TENANT);
    assertThat(view.skill()).contains(FARMING);
    assertThat(view.cycleDays()).isEqualTo(3L);
    assertThat(view.capacityPerUnit()).containsExactly(Map.entry(AssetKind.CATTLE, 1L));
    assertThat(view.dailyInputPerUnit())
        .as("每日原料按生产资料种类归类")
        .containsExactly(Map.entry(AssetKind.LAND, Map.of(GRAIN, 2L)));
    assertThat(view.dailyLaborPerUnit()).as("★ dailyLaborPerUnit 不得丢").isEqualTo(40L);
    assertThat(view.laborPerUnit()).isEqualTo(120L);
    assertThat(view.outputPerUnit()).containsExactly(Map.entry(GRAIN, 7L));
    assertThat(view.cycleInputPerUnit())
        .containsExactly(Map.entry(AssetKind.TOOL, Map.of(GRAIN, 1L)));
    assertThat(view.slots()).hasSize(1);
    assertThat(view.slots().get(0).id()).isEqualTo(SocialClassId.POOR_PEASANT);
    assertThat(view.slots().get(0).name()).isEqualTo("贫农");
    assertThat(view.allocation())
        .as("分配函数是 production.contract 的权威值类型（AllocationRule.Split）")
        .isEqualTo(new AllocationRule.Split(600, 400));
    assertThat(view.moneyOutputPerUnit().perUnit())
        .as("★ moneyOutputPerUnit 不得丢（修正①）")
        .containsExactly(Map.entry(SILVER, 5L));
  }

  @Test
  void industryViewHasNoRuntimeInstanceFields() {
    assertThat(
            java.util.Arrays.stream(IndustryDefinitionView.class.getRecordComponents())
                .map(c -> c.getName()))
        .as("运行实例字段不得进视图")
        .doesNotContain(
            "operator", "progressDays", "capacity", "cycleLaborMilli", "cycleInputUsedMilli");
  }

  @Test
  void missingSkillStillReturnsTheCompleteIndustryView() {
    IndustryDefinitionView view = definitions(null).industry(FARM).orElseThrow();

    assertThat(view.skill()).as("N9：缺技能 ⇒ skill=empty，但视图照常返回").isEmpty();
    assertThat(view.dailyLaborPerUnit()).as("缺技能不影响定义字段").isEqualTo(40L);
    assertThat(view.outputPerUnit()).containsExactly(Map.entry(GRAIN, 7L));
    assertThat(view.moneyOutputPerUnit().perUnit()).containsExactly(Map.entry(SILVER, 5L));
  }

  @Test
  void modeViewUsesItsOwnStableId() {
    ProductionModeDefinitionView view =
        definitions(FARMING).mode(new ProductionModeId("tenant-mode")).orElseThrow();

    assertThat(view.id()).isEqualTo(new ProductionModeId("tenant-mode"));
    assertThat(view.name()).isEqualTo("租佃制");
    assertThat(view.version()).isEqualTo(3);
    assertThat(view.classStructureId()).isEqualTo(new ClassStructureId("cs-1"));
  }

  @Test
  void unknownIndustryAndModeReturnEmpty() {
    ProductionDefinitionLookup lookup = definitions(FARMING);
    assertThat(lookup.industry(new IndustryId("ghost@9_9"))).isEmpty();
    assertThat(lookup.mode(new ProductionModeId("ghost"))).isEmpty();
  }

  @Test
  void recipeIsDerivedFromTheIndustryAndThereIsNoRecipeIdAuthority() {
    ProductionDefinitionLookup lookup = definitions(FARMING);
    Optional<ProductionRecipe> recipe = lookup.recipeOf(FARM);
    assertThat(recipe)
        .as("配方按产业派生（P1 无独立 RecipeId 权威表）")
        .isPresent()
        .get()
        .isEqualTo(lookup.industry(FARM).orElseThrow().recipe());
    assertThat(lookup.recipeOf(new IndustryId("ghost@9_9")))
        .as("不存在的产业 ⇒ empty（不制造 RecipeId↔IndustryId 一对一映射）")
        .isEmpty();
  }

  // ★★ D-032 S2（D3）删除面（本文件原「② 劳动事实的现算语义」两条用例）：
  //   `EconomyProductionLaborLookup` / `api.production.UnitProductionFacts` / `HouseholdLaborShare`
  // /
  //   `ProductionLaborLookup` 整条 P0 劳动只读口已按裁定退役（唯一消费者随 D2 一并删除 ⇒ 零消费者）。
  //   劳动事实改由**调用入参**提供：`production.contract.HouseholdProductionConditions.laborMilli` /
  //   `ProductionExecutionRequest.laborContributionsMilli`。⇒ 测试面随之删除（不是放宽断言）。

  // ── 夹具 ───────────────────────────────────────────────────────────────────────

  /**
   * ★★ D-032 T1：定义权威的直接构造（旧 helper 走的是已随 D1 退役的 {@code
   * ProductionDefinitionLookup.definitions(ProductionSkillKey)} 技能键读口）。
   *
   * <p>与旧 helper 逐值同形：{@code skill == null} ⇒ {@code Optional.empty()}（"缺技能照常返回完整视图"的入口）。 新增的 4
   * 个组件取中性值：{@code buildDays=1}、{@code experience=}{@link ProductionExperienceUse#NONE}（效率恒
   * 1000‰、无需解锁）、{@code availability=}{@code List.of()}（无额外可行性条件）、{@code acceptedRightKinds=}{@code
   * Set.of()}（不额外限制权利种类）。
   */
  private static ProductionData definitions(ProductionSkillKey skill) {
    IndustryDefinitionView farm =
        new IndustryDefinitionView(
            FARM,
            "农业",
            TENANT,
            Optional.ofNullable(skill),
            3L,
            1L,
            Map.of(AssetKind.CATTLE, 1L),
            Map.of(AssetKind.LAND, Map.of(GRAIN, 2L)),
            40L,
            120L,
            Map.of(GRAIN, 7L),
            Map.of(AssetKind.TOOL, Map.of(GRAIN, 1L)),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(600, 400),
            new MoneyOutput(Map.of(SILVER, 5L)),
            ProductionExperienceUse.NONE,
            List.of(),
            Set.of());
    ProductionModeDefinitionView tenantMode =
        new ProductionModeDefinitionView(
            new ProductionModeId("tenant-mode"), "租佃制", 3, new ClassStructureId("cs-1"));
    return ProductionData.empty().withIndustry(farm).withMode(tenantMode);
  }
}
