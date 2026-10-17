package io.mosire.simos.production.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.state.FieldDelta;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：G1「显式 operator 必须进变更集差异、重建时不得按制度重推顶替」的载体</b>（台账 §9.2 的建议落点）。
 *
 * <p>★★ <b>判据</b>：实例的 {@code operator} 是<b>状态</b>（来自 {@code production.Seed} 的显式载荷），不是"每次读的时候
 * 按制度重推"的派生值。⇒ ① 两个 operator 不同的实例必须产出 {@code instances} 组件的变更集差异（不是 {@code Unchanged} 的静默丢弃）；②
 * 把变更集 apply 回基态后，operator <b>逐值 = target 里那个显式值</b>，且 <b>≠ 制度推导值</b> （{@code tenant} 档的推导值 =
 * {@code ActorRef(HOUSEHOLD, <industryId>)}，见 economy 的 {@code RegimeOperators}； production 模块不能依赖
 * economy 主代码，故该推导值按表写成本文件的独立规格）。
 *
 * <p>★ <b>判别力</b>：若实现把 operator 当派生值（读时重推）、或变更集不携带它，则 ① {@code instances} 会是 {@code Unchanged} 或 ②
 * apply 后 operator 会变回推导值 —— 两条断言各自当场红。
 */
class ProductionChangeSetOperatorTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-g1-operator");
  private static final CommodityId GRAIN = new CommodityId("grain");

  /**
   * 制度推导值（独立规格，不是从 economy 复制常量）：{@code RegimeOperators.defaultOperator(TENANT, farm@0_0)} = {@code
   * ActorRef(HOUSEHOLD, "farm@0_0")}（tenant 档的默认主体种类 = HOUSEHOLD，id = 产业 id）。
   */
  private static final ActorRef DERIVED = new ActorRef(ActorKind.HOUSEHOLD, FARM.value());

  /** 显式绑定的 operator：与制度推导值不同（种类与 id 都不同 ⇒ 不可能"碰巧相等"）。 */
  private static final ActorRef EXPLICIT =
      new ActorRef(ActorKind.ORGANIZATION, "org-explicit-owner");

  @Test
  void anExplicitOperatorEntersTheChangeSetAndSurvivesRebuildInsteadOfBeingReDerived() {
    IndustryDefinitionView plan =
        new IndustryDefinitionView(
            FARM,
            "G1 方案（tenant 档）",
            new RegimeId("tenant"),
            Optional.empty(),
            1L,
            0L,
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            1_000L,
            Map.of(GRAIN, 5L),
            Map.of(),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            ProductionExperienceUse.NONE,
            List.of(),
            Set.of(AssetRightKind.OWNED));
    ProductionProcess before = instance(DERIVED, 0L);
    ProductionData base = ProductionData.empty().withIndustry(plan).withInstance(before);

    ProductionProcess after = instance(EXPLICIT, 1L);
    ProductionData target = base.withInstance(after);

    ProductionChangeSet changeSet = ProductionChangeSet.between(base, target);

    // ── ① 显式 operator 必须进变更集差异（不是 Unchanged）────────────────────────────
    assertThat(changeSet.instances())
        .as("★★ instances 组件必须 Changed（operator 是状态，不是派生值）")
        .isInstanceOf(FieldDelta.Upsert.class);
    @SuppressWarnings("unchecked")
    FieldDelta.Upsert<ProductionProcess> upsert =
        (FieldDelta.Upsert<ProductionProcess>) changeSet.instances();
    assertThat(upsert.entries())
        .as("变更集携带的实例 operator 逐值 = target 的显式值")
        .containsEntry(UNIT.toString(), after);
    assertThat(upsert.entries().get(UNIT.toString()).operator())
        .as("显式 operator 逐值进差异")
        .isEqualTo(EXPLICIT)
        .isNotEqualTo(DERIVED);

    // ── ② 重建：apply(between(base,target), base) 逐值重建 target；operator 不得被重推顶替 ──
    ProductionData rebuilt = ProductionChangeSet.apply(changeSet, base);
    assertThat(rebuilt).as("铁律 5：apply(between(base,target), base) == target").isEqualTo(target);
    assertThat(rebuilt.instances().get(UNIT).operator())
        .as("★★ 重建后的 operator = 显式值，不是制度推导值")
        .isEqualTo(EXPLICIT)
        .isNotEqualTo(DERIVED);
    assertThat(rebuilt.instances().get(UNIT).progressDays())
        .as("同一条差异里的其它字段也逐值落盘（不是只带 operator）")
        .isEqualTo(1L);
  }

  private static ProductionProcess instance(ActorRef operator, long progressDays) {
    return new ProductionProcess(
        UNIT,
        FARM,
        ProductionLocation.parse("0_0"),
        operator,
        FARM.value(),
        progressDays,
        Map.of(),
        Map.of());
  }
}
