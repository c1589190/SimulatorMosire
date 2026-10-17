package io.mosire.simos.production.spi;

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
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：G2「显式 operator 逐值落盘、且 ≠ 制度推导值」的载体</b>（台账 §9.2 的建议落点：{@code production.Seed} 的
 * {@code instances[]}）。
 *
 * <p>★★ <b>背景</b>：B5 之后 economy 载荷<b>不再解析</b> operator（旧字段退役）；显式绑定 operator 的唯一入口是 {@code
 * production.Seed} 的 {@code instances[]}。本用例判的是：那份载荷里的显式 operator <b>逐值</b>落进 production 权威实例表，
 * 且<b>不是</b>按 {@code regime} 推导出来的值。
 *
 * <p>★★ <b>制度推导值（独立规格）</b>：{@code RegimeOperators.defaultOperator(TENANT, farm@0_0)} = {@code
 * ActorRef(HOUSEHOLD, "farm@0_0")}（tenant 档 = 佃农家户，id = 产业 id）。载荷显式给的是 {@code
 * ActorRef(ORGANIZATION, "org-explicit-owner")} ⇒ 两者<b>种类与 id 都不同</b>，断言"≠推导值"不可能是 碰巧相等。
 *
 * <p>★ <b>逐值判据</b>：{@code id / plan / operator / modeKey / location / progressDays} 六个字段逐项与载荷相同（不是
 * "只看 operator 一项"）；同时变更集必须携带该实例（G1 的入口面）。
 */
class ProductionSeedInstancesTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1L));
  private static final SimosTimestamp AT = SimosTimestamp.of(0L);

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-g2-explicit");

  /** 制度推导值（tenant 档：HOUSEHOLD + 产业 id）。 */
  private static final ActorRef DERIVED = new ActorRef(ActorKind.HOUSEHOLD, FARM.value());

  /** 载荷里的显式 operator（与推导值不同）。 */
  private static final ActorRef EXPLICIT =
      new ActorRef(ActorKind.ORGANIZATION, "org-explicit-owner");

  @Test
  void anExplicitOperatorInTheSeedPayloadLandsVerbatimAndDiffersFromTheRegimeDerivedValue() {
    IndustryDefinitionView plan =
        new IndustryDefinitionView(
            FARM,
            "G2 方案（tenant 档）",
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
    ProductionData base = ProductionData.empty().withIndustry(plan);
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, AT),
            Map.of("production", new ProductionSnapshot(REF, AT, base)),
            InMemoryInfoSystem.empty());

    // 载荷只带 instances[]（方案已在权威定义表里）——显式 operator + 显式 location + 显式进度。
    String payload =
        "{\"instances\":[{"
            + "\"id\":\""
            + UNIT.value()
            + "\",\"plan\":\""
            + FARM.value()
            + "\",\"operator\":{\"kind\":\"ORGANIZATION\",\"id\":\""
            + EXPLICIT.id()
            + "\"},\"modeKey\":\"m-g2\",\"location\":\"0_0\",\"progressDays\":1}]}";

    HandlerOutcome outcome = new ProductionSeedHandler().handle(state, payload);
    assertThat(outcome)
        .as("载荷必须被真 handler 接受: %s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    ProductionChangeSet changeSet =
        (ProductionChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    ProductionData seeded = ProductionChangeSet.apply(changeSet, base);

    ProductionProcess instance = seeded.instances().get(UNIT);
    assertThat(instance).as("实例真的进了权威实例表").isNotNull();
    assertThat(changeSet.instances().changed()).as("G1 的入口面：显式实例必须进变更集差异（不是 Unchanged）").isTrue();
    assertThat(instance.id()).as("id 逐值").isEqualTo(UNIT);
    assertThat(instance.industry()).as("plan 逐值").isEqualTo(FARM);
    assertThat(instance.modeKey()).as("modeKey 逐值（含拼写）").isEqualTo("m-g2");
    assertThat(instance.location().value()).as("location 逐值").isEqualTo("0_0");
    assertThat(instance.progressDays()).as("progressDays 逐值").isEqualTo(1L);
    assertThat(instance.operator())
        .as("★★ 显式 operator 逐值落盘 = 载荷里的那个（不是 regime 推导值）")
        .isEqualTo(EXPLICIT)
        .isNotEqualTo(DERIVED);
    assertThat(instance.operator().kind())
        .as("种类也逐值（ORGANIZATION，不是 tenant 档推导的 HOUSEHOLD）")
        .isEqualTo(ActorKind.ORGANIZATION);
  }
}
