package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 两条辖区命令 handler（辖区阶段 5 / 计划 §2.2）：{@code unit.SetJurisdiction}（区域集合整体替换 + 可选政策字段 upsert）与 {@code
 * unit.SetTaxRate}（已管辖区域的税率 upsert）。
 *
 * <p>★ 判据两块：① happy path 的 {@code Applied} + 应用后状态逐值 + 变更集<b>过线</b>往返（{@link UnitCodec} 编解码后 {@code
 * apply} 仍重建 target）；② 每条具名拒都走 {@link HandlerOutcome.Rejected} 路径（不是抛异常）， 理由只断言关键片段、名字点到具体
 * id/字段，不整句复刻。
 *
 * <p>★ 世界夹具 = {@link SpiFixture} 的走廊 + u-1，再挂三个区域（{@code r-north}/{@code r-south}/{@code r-east}）；
 * {@code r-ghost} 刻意不在 {@code GameMap.regions()} 里。
 */
class JurisdictionCommandHandlersTest {

  private static final SetJurisdictionHandler SET_JURISDICTION = new SetJurisdictionHandler();
  private static final SetTaxRateHandler SET_TAX_RATE = new SetTaxRateHandler();
  private static final UnitCodec CODEC = new UnitCodec();

  private static final RegionId NORTH = new RegionId("r-north");
  private static final RegionId SOUTH = new RegionId("r-south");
  private static final RegionId EAST = new RegionId("r-east");

  /** 不在 {@code GameMap.regions()} 里的区域。 */
  private static final RegionId GHOST = new RegionId("r-ghost");

  // ── type() ─────────────────────────────────────────────────────

  @Test
  void typeNamesMatchTheSpecTable() {
    assertThat(SET_JURISDICTION.type()).isEqualTo("unit.SetJurisdiction");
    assertThat(SET_TAX_RATE.type()).isEqualTo("unit.SetTaxRate");
  }

  // ── unit.SetJurisdiction：正常设 / 改 / 撤 ──────────────────────

  @Test
  void setJurisdictionAppliesRegionsCapsAndAdministration() {
    UnitState base = SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));
    SimulationState world = world(base);

    UnitChangeSet changeSet =
        changeSetOf(
            SET_JURISDICTION,
            world,
            "{\"unitId\":\"u-1\",\"regions\":[\"r-north\",\"r-south\"],"
                + "\"levyGrainCapPerCommand\":5,\"levyMoneyCapPerCommand\":7,"
                + "\"levyManpowerCapPerCommand\":9,\"administrationPerMille\":250}");
    UnitState target = UnitChangeSet.apply(changeSet, base);

    Unit unit = target.units().get(SpiFixture.U1);
    Jurisdiction jurisdiction = unit.jurisdiction().orElseThrow();
    assertThat(keysOf(jurisdiction)).as("新区域按数组顺序落进 key 集").containsExactly(NORTH, SOUTH);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .as("原本无管辖 ⇒ 新区域税率从 0 起")
        .containsEntry(NORTH, 0L)
        .containsEntry(SOUTH, 0L);
    assertThat(jurisdiction.levyGrainCapPerCommand()).isEqualTo(5L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).isEqualTo(250);
    assertThat(unit.name()).isEqualTo("第一连");
    assertThat(unit.position().valueAt(SpiFixture.T0)).contains(SpiFixture.H11);

    assertChangeSetRebuildsTarget(base, changeSet, target);
    assertChangeSetSurvivesJsonRoundTrip(base, changeSet, target);
  }

  @Test
  void setJurisdictionReplacesTheSetKeepingOldRatesAndUpdatingOnlyProvidedFields() {
    UnitState base =
        stateWith(
            jurisdiction(
                orderedRates(Map.entry(NORTH, 120L), Map.entry(SOUTH, 340L)), 5L, 7L, 9L, 250));
    SimulationState world = world(base);

    UnitChangeSet changeSet =
        changeSetOf(
            SET_JURISDICTION,
            world,
            "{\"unitId\":\"u-1\",\"regions\":[\"r-south\",\"r-east\"],"
                + "\"levyGrainCapPerCommand\":50,\"administrationPerMille\":800}");
    UnitState target = UnitChangeSet.apply(changeSet, base);

    Jurisdiction jurisdiction = target.units().get(SpiFixture.U1).jurisdiction().orElseThrow();
    assertThat(keysOf(jurisdiction))
        .as("整体替换：r-north 移除、r-east 新入，r-south 保留键位")
        .containsExactly(SOUTH, EAST);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsEntry(SOUTH, 340L)
        .containsEntry(EAST, 0L)
        .doesNotContainKey(NORTH);
    assertThat(jurisdiction.levyGrainCapPerCommand()).as("给了 ⇒ 覆盖").isEqualTo(50L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).as("没给 ⇒ 保持").isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).as("没给 ⇒ 保持").isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).as("给了 ⇒ 覆盖").isEqualTo(800);

    assertChangeSetRebuildsTarget(base, changeSet, target);
    assertChangeSetSurvivesJsonRoundTrip(base, changeSet, target);
  }

  @Test
  void setJurisdictionWithEmptyRegionsRevokesAllButKeepsJurisdictionPresent() {
    UnitState base =
        stateWith(
            jurisdiction(
                orderedRates(Map.entry(NORTH, 120L), Map.entry(SOUTH, 340L)), 5L, 7L, 9L, 250));
    SimulationState world = world(base);

    UnitChangeSet changeSet =
        changeSetOf(SET_JURISDICTION, world, "{\"unitId\":\"u-1\",\"regions\":[]}");
    UnitState target = UnitChangeSet.apply(changeSet, base);

    Unit unit = target.units().get(SpiFixture.U1);
    assertThat(unit.jurisdiction()).as("撤销全部管辖 ≠ 丢掉 Optional").isPresent();
    Jurisdiction jurisdiction = unit.jurisdiction().orElseThrow();
    assertThat(jurisdiction.taxRatePerMilleByRegion()).isEmpty();
    assertThat(jurisdiction.levyGrainCapPerCommand()).as("未给的政策字段保持原值").isEqualTo(5L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).isEqualTo(250);

    assertChangeSetRebuildsTarget(base, changeSet, target);
    assertChangeSetSurvivesJsonRoundTrip(base, changeSet, target);
  }

  // ── unit.SetJurisdiction：具名拒 ───────────────────────────────

  @Test
  void setJurisdictionRejectsARegionNotInTheGameMap() {
    UnitState base = stateWith(jurisdiction(orderedRates(Map.entry(NORTH, 120L)), 0L, 0L, 0L, 0));
    SimulationState world = world(base);

    String reason =
        reason(
            SET_JURISDICTION, world, "{\"unitId\":\"u-1\",\"regions\":[\"r-north\",\"r-ghost\"]}");

    assertThat(reason).as("坏 regionId 必须点名且不静默丢").contains("区域不存在").contains("r-ghost");
    assertThat(
            base.units().get(SpiFixture.U1).jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("拒绝 ⇒ 原状态一字不动")
        .containsEntry(NORTH, 120L);
  }

  @Test
  void setJurisdictionRejectsAnUnknownUnit() {
    UnitState base = SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));

    String reason =
        reason(SET_JURISDICTION, world(base), "{\"unitId\":\"u-ghost\",\"regions\":[\"r-north\"]}");

    assertThat(reason).contains("单位不存在").contains("u-ghost");
  }

  @Test
  void setJurisdictionRejectsMalformedPayloads() {
    UnitState base = SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));
    SimulationState world = world(base);

    assertThat(reason(SET_JURISDICTION, world, "{\"regions\":[\"r-north\"]}"))
        .as("缺 unitId")
        .contains("unitId");
    assertThat(reason(SET_JURISDICTION, world, "{\"unitId\":\"u-1\",\"regions\":\"r-north\"}"))
        .as("regions 不是数组")
        .contains("regions")
        .contains("数组");
    assertThat(reason(SET_JURISDICTION, world, "{\"unitId\":\"u-1\"}"))
        .as("缺 regions")
        .contains("regions");
    assertThat(reason(SET_JURISDICTION, world, "{\"unitId\":\"u-1\",\"regions\":[\"\"]}"))
        .as("regions 元素不得为空白")
        .contains("regions")
        .contains("非空字符串");
  }

  // ── unit.SetTaxRate：upsert / 边界 ─────────────────────────────

  @Test
  void setTaxRateUpsertsOneRegionKeepingTheOrderAndOtherPolicyFields() {
    UnitState base =
        stateWith(
            jurisdiction(
                orderedRates(Map.entry(NORTH, 120L), Map.entry(SOUTH, 340L)), 5L, 7L, 9L, 250));
    SimulationState world = world(base);

    UnitChangeSet changeSet =
        changeSetOf(
            SET_TAX_RATE,
            world,
            "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":777}");
    UnitState target = UnitChangeSet.apply(changeSet, base);

    Unit unit = target.units().get(SpiFixture.U1);
    Jurisdiction jurisdiction = unit.jurisdiction().orElseThrow();
    assertThat(keysOf(jurisdiction)).as("既有键只换值 ⇒ 键位不动").containsExactly(NORTH, SOUTH);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsExactly(Map.entry(NORTH, 777L), Map.entry(SOUTH, 340L));
    assertThat(jurisdiction.levyGrainCapPerCommand()).isEqualTo(5L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).isEqualTo(250);
    assertThat(unit.movement()).isEmpty();
    assertThat(unit.visionRadius()).isEqualTo(Unit.DEFAULT_VISION_RADIUS);

    assertChangeSetRebuildsTarget(base, changeSet, target);
    assertChangeSetSurvivesJsonRoundTrip(base, changeSet, target);
  }

  @Test
  void setTaxRateAcceptsBoundaryRatesZeroAndThousand() {
    UnitState base = stateWith(jurisdiction(orderedRates(Map.entry(NORTH, 120L)), 0L, 0L, 0L, 0));
    SimulationState world = world(base);

    UnitState atZero =
        applied(
            SET_TAX_RATE,
            world,
            "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":0}");
    assertThat(rateOf(atZero, NORTH)).as("0‰ 是下界、合法").isZero();

    UnitState atThousand =
        applied(
            SET_TAX_RATE,
            world,
            "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":1000}");
    assertThat(rateOf(atThousand, NORTH)).as("1000‰ 是上界、合法").isEqualTo(1000L);
  }

  // ── unit.SetTaxRate：具名拒 ────────────────────────────────────

  @Test
  void setTaxRateRejectsAUnitWithoutJurisdiction() {
    UnitState base = SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));

    String reason =
        reason(
            SET_TAX_RATE,
            world(base),
            "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":100}");

    assertThat(reason).contains("没有 jurisdiction").contains("unit.SetJurisdiction");
  }

  @Test
  void setTaxRateRejectsARegionOutsideTheJurisdiction() {
    UnitState base = stateWith(jurisdiction(orderedRates(Map.entry(NORTH, 120L)), 0L, 0L, 0L, 0));
    SimulationState world = world(base);

    String reason =
        reason(
            SET_TAX_RATE,
            world,
            "{\"unitId\":\"u-1\",\"regionId\":\"r-south\",\"ratePerMille\":100}");

    assertThat(reason).contains("r-south").contains("不在单位").contains("unit.SetJurisdiction");
    assertThat(
            base.units().get(SpiFixture.U1).jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("拒绝 ⇒ 原状态不动")
        .containsEntry(NORTH, 120L);
  }

  @Test
  void setTaxRateRejectsRatesOutsideRangeWithoutClamping() {
    UnitState base = stateWith(jurisdiction(orderedRates(Map.entry(NORTH, 120L)), 0L, 0L, 0L, 0));
    SimulationState world = world(base);

    assertThat(
            reason(
                SET_TAX_RATE,
                world,
                "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":-1}"))
        .contains("ratePerMille")
        .contains("[0,1000]");
    assertThat(
            reason(
                SET_TAX_RATE,
                world,
                "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":1001}"))
        .contains("ratePerMille")
        .contains("[0,1000]");
    assertThat(
            unitSlice(world)
                .units()
                .get(SpiFixture.U1)
                .jurisdiction()
                .orElseThrow()
                .taxRatePerMilleByRegion())
        .as("越界被拒 ⇒ 不钳制、原值不动")
        .containsEntry(NORTH, 120L);
  }

  @Test
  void setTaxRateRejectsAnUnknownUnit() {
    UnitState base = stateWith(jurisdiction(orderedRates(Map.entry(NORTH, 120L)), 0L, 0L, 0L, 0));

    String reason =
        reason(
            SET_TAX_RATE,
            world(base),
            "{\"unitId\":\"u-ghost\",\"regionId\":\"r-north\",\"ratePerMille\":100}");

    assertThat(reason).contains("单位不存在").contains("u-ghost");
  }

  @Test
  void setTaxRateRejectsMalformedPayloads() {
    UnitState base = stateWith(jurisdiction(orderedRates(Map.entry(NORTH, 120L)), 0L, 0L, 0L, 0));
    SimulationState world = world(base);

    assertThat(reason(SET_TAX_RATE, world, "{\"unitId\":\"u-1\",\"ratePerMille\":100}"))
        .as("缺 regionId")
        .contains("regionId");
    assertThat(reason(SET_TAX_RATE, world, "{\"unitId\":\"u-1\",\"regionId\":\"r-north\"}"))
        .as("缺 ratePerMille")
        .contains("ratePerMille");
    assertThat(
            reason(
                SET_TAX_RATE,
                world,
                "{\"unitId\":\"u-1\",\"regionId\":\"r-north\",\"ratePerMille\":\"高\"}"))
        .as("ratePerMille 不是整数")
        .contains("ratePerMille")
        .contains("整数");
  }

  // ── 装置 ───────────────────────────────────────────────────────

  private static SimulationState world(UnitState base) {
    return SpiFixture.state(regionMap(), base);
  }

  private static UnitState unitSlice(SimulationState world) {
    return ((UnitSnapshot) world.module("unit").orElseThrow()).state();
  }

  private static UnitChangeSet changeSetOf(
      CommandHandler handler, SimulationState world, String payload) {
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome).as("期望 Applied 而不是 %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
  }

  private static UnitState applied(CommandHandler handler, SimulationState world, String payload) {
    return UnitChangeSet.apply(changeSetOf(handler, world, payload), unitSlice(world));
  }

  private static String reason(CommandHandler handler, SimulationState world, String payload) {
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome)
        .as("期望 Rejected 而不是 %s", outcome)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  /**
   * 铁律 5 的往返：{@code apply(changeSet, base)} 与 {@code apply(between(base, target), base)} 都重建
   * target。
   */
  private static void assertChangeSetRebuildsTarget(
      UnitState base, UnitChangeSet changeSet, UnitState target) {
    assertThat(UnitChangeSet.apply(changeSet, base)).as("apply 重建 = target").isEqualTo(target);
    assertThat(UnitChangeSet.apply(UnitChangeSet.between(base, target), base))
        .as("between 往返：apply 重建 = target（铁律 5）")
        .isEqualTo(target);
  }

  /** 命令边界给出的那份变更集必须能过 JSON 线（RegionId 作 Map 键的绑定不能在读入侧退化）。 */
  private static void assertChangeSetSurvivesJsonRoundTrip(
      UnitState base, UnitChangeSet changeSet, UnitState target) {
    UnitChangeSet wire = (UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet));
    assertThat(wire).as("过线后变更集逐值相等").isEqualTo(changeSet);
    assertThat(UnitChangeSet.apply(wire, base)).as("过线后 apply 仍重建 = target").isEqualTo(target);
  }

  private static List<RegionId> keysOf(Jurisdiction jurisdiction) {
    return new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().keySet());
  }

  /** 某区域在 u-1 管辖里的税率（逐值判据的读数口）。 */
  private static Long rateOf(UnitState state, RegionId region) {
    return state
        .units()
        .get(SpiFixture.U1)
        .jurisdiction()
        .orElseThrow()
        .taxRatePerMilleByRegion()
        .get(region);
  }

  // ── 夹具 ───────────────────────────────────────────────────────

  private static UnitState stateWith(Jurisdiction jurisdiction) {
    return SpiFixture.unitState(unitWith(jurisdiction));
  }

  /** u-1（SpiFixture 形制）+ 非空管辖；其余字段原样。 */
  private static Unit unitWith(Jurisdiction jurisdiction) {
    Unit unit = SpiFixture.unitWithMovement(Optional.empty());
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        Optional.of(jurisdiction));
  }

  private static Jurisdiction jurisdiction(
      Map<RegionId, Long> rates, long grain, long money, long manpower, int administration) {
    return new Jurisdiction(rates, grain, money, manpower, administration);
  }

  @SafeVarargs
  private static Map<RegionId, Long> orderedRates(Map.Entry<RegionId, Long>... entries) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    for (Map.Entry<RegionId, Long> entry : entries) {
      rates.put(entry.getKey(), entry.getValue());
    }
    return rates;
  }

  private static GameMap regionMap() {
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(NORTH, Region.of(NORTH, "北境", Set.of(SpiFixture.H11), RegionMeta.empty()));
    regions.put(SOUTH, Region.of(SOUTH, "南境", Set.of(SpiFixture.H12), RegionMeta.empty()));
    regions.put(EAST, Region.of(EAST, "东境", Set.of(SpiFixture.H13), RegionMeta.empty()));
    return SpiFixture.map().withRegions(regions);
  }
}
