package io.mosire.simos.gov.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.verify.RoundTripAssertions;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ <b>铁律 5 在 gov 切片的机械化落地</b>（本仓原先唯一没有往返护栏的切片：{@code find simos-gov/src/test -name
 * '*RoundTrip*'} = 0）。形制照 {@code SdRoundTripTest} / {@code MapRoundTripComponentsTest}
 * （反射枚举状态组件 → 逐组件造差异 → 三条断言），不另创一套。
 *
 * <p>★★ <b>判别力来自"反射枚举 + 两个 switch 的 default 抛"</b>：往 {@link GovState} 加一个组件而<b>不</b>同步
 * {@link GovChangeSet} 时，{@link #mutate} 与 {@link #changedOf} 的 {@code default -> throw} 当场响，且
 * {@link #stateHasExactlyTwoComponentsOfficesAndRemittanceStates} 的
 * {@code containsExactly} 与 {@link #changeSetCoversEveryStateComponentWithNoExemptions} 的"并集相等"同时红 —— 三处
 * 各自独立，不靠"人记得改"。
 *
 * <p>★★ <b>为什么夹具两张表都必须非空</b>：{@link FieldDelta#diff} 把表键经 {@code toString()} 写成规范串、
 * {@link FieldDelta#rebuild} 再用 {@code UnitId::parse} 读回 —— 空表<b>根本不经过键的字面量往返</b>， 于是"键读侧缺失"这一类缺陷在空夹具下永远看不见（{@code
 * apply(between(empty, empty), empty)} 恒真）。本类因此一律用<b>非空</b> {@code offices} / {@code
 * remittanceStates} 造差异。
 *
 * <p>★ <b>豁免集为空且被单独钉死</b>：{@code GovState} 的两个组件都必须进变更集，本类<b>不设</b>任何豁免项；写成一个被
 * {@link #theExclusionListIsEmpty} 单独断言的集合，加名字是一次显式动作、在 diff 里现形（照 {@code RoundTripComponentsTest} 的
 * "豁免口必须自己现形"口径）。
 */
class GovRoundTripTest {

  private static final UnitId U1 = new UnitId("gov-1");
  private static final UnitId U2 = new UnitId("gov-2");
  private static final UnitId U3 = new UnitId("gov-3");

  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  /** ★ 唯一的豁免集合（本轮**没有**任何豁免项）。**加一项就是一次有意的决定，会在 diff 里现形。** */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── ① 反射枚举：逐组件三条断言 ────────────────────────────────────────────────────────

  /**
   * ★ <b>本任务的核心用例</b>：逐组件 ①{@code between} 看见它（变更集非空）②该组件被报成非 {@code Unchanged} ③逐字段重建出
   * target。少任何一条都会留下一类看不见的漂移（三者的分工见 {@code RoundTripComponentsTest} 的类注释）。
   */
  @Test
  void everyGovStateComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : GovState.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue; // ★ 本轮恒不进入：EXCLUDED 为空，且被 theExclusionListIsEmpty 钉死
      }
      String name = rc.getName();
      GovState base = full();
      GovState target = mutate(base, name);
      GovChangeSet cs = GovChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 该组件的变化看不见）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(GovChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  /** ★ 反射断言：{@code GovState} 的 record 组件**恰两个**、名字逐字相等（声明序）。 */
  @Test
  void stateHasExactlyTwoComponentsOfficesAndRemittanceStates() {
    assertThat(GovState.class.getRecordComponents())
        .as("GovState 恰 2 个组件 —— 多加一个而不改变更集，本断言与往返用例同时红")
        .hasSize(2);
    assertThat(componentNames(GovState.class))
        .as("名字逐字相等（顺序即声明序）")
        .containsExactly("offices", "remittanceStates");
  }

  /** ★ 变更集这边同样恰 2 个组件，且与状态组件集**互相包含**（两个方向都钉，不是只钉一边）。 */
  @Test
  void changeSetHasExactlyTwoComponentsMatchingTheState() {
    assertThat(GovChangeSet.class.getRecordComponents()).hasSize(2);
    assertThat(componentNames(GovChangeSet.class))
        .as("变更集的每个组件都必须在 GovState 里有同名的 record 组件")
        .isSubsetOf(componentNames(GovState.class));
    assertThat(componentNames(GovState.class))
        .as("反向也成立 ⇒ 两边组件集相同（挡不住'两边同时多出一个同名组件'，故上面另有 hasSize(2)）")
        .isSubsetOf(componentNames(GovChangeSet.class));
  }

  // ── ② 豁免集为空 ──────────────────────────────────────────────────────────────────

  /** ★ 钉死豁免集本身 —— 否则"加字段忘了改"的补救方式会变成"往豁免集里塞一项"，护栏出现一个正好等于新字段大小的洞。 */
  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET)
        .as("GovState 没有豁免项：offices 与 remittanceStates 都必须进变更集")
        .isEmpty();
  }

  /** ★ 最后一条兜底：变更集组件 ∪ 豁免集必须**恰好**覆盖 {@code GovState} 的全部组件（多一项 = 有组件被豁免掉，少一项 = 有组件谁都没管）。 */
  @Test
  void changeSetCoversEveryStateComponentWithNoExemptions() {
    Set<String> covered = new LinkedHashSet<>(componentNames(GovChangeSet.class));
    covered.addAll(EXCLUDED_FROM_CHANGE_SET);
    assertThat(covered)
        .as("变更集组件 ∪ 豁免集 必须恰好覆盖 GovState 的全部组件")
        .containsExactlyInAnyOrderElementsOf(componentNames(GovState.class));
  }

  // ── ③ 两张表都非空的逐字段往返（含"又删又改"）──────────────────────────────────────────

  /**
   * ★★ <b>非空 → 非空</b>的逐字段往返：目标态对两张表**各删一个键、各改一个键、各加一个键**（{@link FieldDelta.Patch}
   * 的两侧），于是"键读侧"（{@code toString → parse}）、"值覆盖"、"键删除"三条路径全部走到，而不是只走"从空表往里加"。
   *
   * <p>★ 判别力：若 {@code GovChangeSet.apply} 漏重建任一张表、或把 {@code Patch} 的某一侧丢掉，{@code rebuilt.equals(target)} 与
   * 下面逐字段的断言都会红（整态 equals 只是最后一条，**不能**是唯一一条 —— 两个空对象也相等）。
   */
  @Test
  void bothNonEmptyTablesRoundTripFieldByFieldWithRemovalsAndOverwrites() {
    Map<UnitId, GovOfficeState> baseOffices = new LinkedHashMap<>();
    baseOffices.put(U1, office(U1, 7L, 100L, 40L));
    baseOffices.put(U2, office(U2, 7L, 200L, 50L));
    Map<UnitId, GovRemittanceState> baseRemittances = new LinkedHashMap<>();
    baseRemittances.put(U1, remittance(10L, 20L, 30L, 30L, 0L, 40L, 40L, 0L, 5L));
    baseRemittances.put(U2, remittance(1L, 2L, 3L, 3L, 0L, 4L, 4L, 0L, 6L));
    GovState base = new GovState(baseOffices, baseRemittances);

    // target：U2 从两张表**删掉**、U1 的值**被覆盖**、U3 是**新增**键。
    Map<UnitId, GovOfficeState> targetOffices = new LinkedHashMap<>();
    targetOffices.put(U1, office(U1, 9L, 700L, 300L));
    targetOffices.put(U3, office(U3, 9L, 55L, 5L));
    Map<UnitId, GovRemittanceState> targetRemittances = new LinkedHashMap<>();
    targetRemittances.put(U1, remittance(0L, 0L, 30L, 25L, 5L, 40L, 35L, 5L, 9L));
    targetRemittances.put(U3, remittance(7L, 8L, 9L, 9L, 0L, 10L, 10L, 0L, 11L));
    GovState target = new GovState(targetOffices, targetRemittances);

    GovChangeSet cs = GovChangeSet.between(base, target);

    assertThat(cs.isEmpty()).isFalse();
    assertThat(cs.offices())
        .as("两张表各自又删又改 ⇒ Patch（两侧都不许丢）")
        .isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.remittanceStates()).isInstanceOf(FieldDelta.Patch.class);

    GovState rebuilt = GovChangeSet.apply(cs, base);

    // ⑤ 先逐字段（整态相等放最后：它自己挡不住"空对空"）
    assertThat(rebuilt.offices()).containsOnlyKeys(U1, U3);
    assertThat(rebuilt.offices().get(U1).tick()).as("U1 读数被覆盖（不是留在 7）").isEqualTo(9L);
    assertThat(rebuilt.offices().get(U1).lastAssessedGoods()).containsEntry(GRAIN, 700L);
    assertThat(rebuilt.offices().get(U1).lastPaidGoods()).containsEntry(GRAIN, 300L);
    assertThat(rebuilt.offices().get(U1).lastShortfallGoods()).containsEntry(GRAIN, 400L);
    assertThat(rebuilt.offices().get(U1).securityEfficiencyPerMille()).isEqualTo(250L);
    assertThat(rebuilt.offices().get(U3)).as("新增键（键读侧真的被解析回来）").isNotNull();
    assertThat(rebuilt.offices().get(U3).unitId()).isEqualTo(U3);
    assertThat(rebuilt.offices()).as("被删掉的 U2 不许留在重建结果里").doesNotContainKey(U2);

    assertThat(rebuilt.remittanceStates()).containsOnlyKeys(U1, U3);
    assertThat(rebuilt.remittanceStates().get(U1).lastPaidGrainMilli())
        .as("U1 的上缴读数被覆盖（25，不是 30 也不是相加）")
        .isEqualTo(25L);
    assertThat(rebuilt.remittanceStates().get(U1).lastShortfallGrainMilli()).isEqualTo(5L);
    assertThat(rebuilt.remittanceStates().get(U1).lastCycleCloseDay()).isEqualTo(9L);
    assertThat(rebuilt.remittanceStates().get(U3).cycleSilverCollectedMilli()).isEqualTo(8L);
    assertThat(rebuilt.remittanceStates()).doesNotContainKey(U2);

    assertThat(rebuilt).as("铁律 5 的原文：apply(between(base, target), base) 逐字段重建出 target").isEqualTo(target);
  }

  /** ★ 通用往返断言（util 的 {@code RoundTripAssertions}）：空夹具 → 满夹具（照 {@code SdRoundTripTest} 同款）。 */
  @Test
  void emptyFixtureRoundTripsToTheFullFixture() {
    RoundTripAssertions.assertRoundTrip(
        GovState.empty(), full(), GovChangeSet::between, GovChangeSet::apply);
  }

  // ── ④ 变更集自身语义（照 {@code ActorRoundTripTest} 的同款；{@code SdRoundTripTest} 未单列这两条）─────

  /** ★ 两侧相同 ⇒ 各组件 {@link FieldDelta.Unchanged}（不是 null、也不是"空对象"），且往返恒等。 */
  @Test
  void identicalStatesProduceAnEmptyChangeSetOfUnchangedComponents() {
    GovState state = full();
    GovChangeSet cs = GovChangeSet.between(state, state);

    assertThat(cs.isEmpty()).as("全相等 ⇒ isEmpty()（读法不是 null）").isTrue();
    assertThat(cs.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.remittanceStates()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GovChangeSet.apply(cs, state)).as("一字未动的往返必须恒等").isEqualTo(state);
  }

  /**
   * ★ 变更集里<b>缺组件键</b>（Jackson 绑成 null）⇒ 归一成 {@link FieldDelta.Unchanged}，**此处不抛**。
   *
   * <p>钉的是 {@link GovChangeSet} 紧凑构造器里那条 fail-closed 归一（"没提该组件 = 没动它"）；重建时 {@code Unchanged}
   * 直接返回 base、连键解析器都不会被调用。
   */
  @Test
  void aChangeSetWithMissingComponentKeysIsNormalizedToUnchanged() {
    GovChangeSet fromMissingKeys = new GovChangeSet(null, null);

    assertThat(fromMissingKeys.isEmpty()).as("缺键 ⇒ 两个组件都是 Unchanged").isTrue();
    assertThat(fromMissingKeys.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.remittanceStates()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GovChangeSet.apply(fromMissingKeys, full()))
        .as("一字未动的往返必须恒等（不抛）")
        .isEqualTo(full());
  }

  // ── 夹具与反射小工具 ────────────────────────────────────────────────────────────────

  /** 两张表都**非空**的起点：offices 两条读数、remittanceStates 两条周期账。 */
  private static GovState full() {
    Map<UnitId, GovOfficeState> offices = new LinkedHashMap<>();
    offices.put(U1, office(U1, 7L, 100L, 40L));
    offices.put(U2, office(U2, 7L, 200L, 50L));
    Map<UnitId, GovRemittanceState> remittances = new LinkedHashMap<>();
    remittances.put(U1, remittance(10L, 20L, 30L, 30L, 0L, 40L, 40L, 0L, 5L));
    remittances.put(U2, remittance(1L, 2L, 3L, 3L, 0L, 4L, 4L, 0L, 6L));
    return new GovState(offices, remittances);
  }

  /**
   * 造一个"只在 {@code name} 这个组件上与 {@code base} 不同"的状态。
   *
   * <p>★ <b>{@code default} 必须抛</b>：往 {@link GovState} 加组件时这个 {@code switch} 当场响，**这正是想要的**
   * —— 它迫使加字段的人来读这个测试（照 {@code RoundTripComponentsTest} 的同一条纪律）。
   */
  private static GovState mutate(GovState base, String name) {
    return switch (name) {
      case "offices" -> base.withOffice(office(U2, 9L, 999L, 888L));
      case "remittanceStates" ->
          base.withRemittanceState(U2, remittance(0L, 0L, 3L, 2L, 1L, 4L, 4L, 0L, 8L));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /** 按组件名取出变更集里对应的那一份差异，问它"变了没有"。★ {@code default} 必须抛（同 {@link #mutate}）。 */
  private static boolean changedOf(GovChangeSet cs, String name) {
    return switch (name) {
      case "offices" -> cs.offices().changed();
      case "remittanceStates" -> cs.remittanceStates().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /** 某个 record 类型的组件名，**按声明序**。 */
  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── 夹具：一张读数 + 一条周期账（键 == 值内身份；数值全 ≥ 0 且 paid ≤ due + shortfall == due − paid）──

  /** 一条行政读数：商品/货币各一张评估表 + 实付表 + 缺口表（缺口 = 评估 − 实付，非负）。 */
  private static GovOfficeState office(UnitId id, long tick, long assessed, long paid) {
    return new GovOfficeState(
        id,
        tick,
        Map.of(GRAIN, assessed),
        Map.of(GRAIN, paid),
        Map.of(GRAIN, assessed - paid),
        Map.of(SILVER, assessed),
        Map.of(SILVER, paid),
        Map.of(SILVER, assessed - paid),
        500L,
        600L,
        300L,
        0L,
        250L,
        275L);
  }

  /** 一条 remittance 周期账（9 个字段全给，便于逐字段判往返丢了哪一个）。 */
  private static GovRemittanceState remittance(
      long cycleGrain, long cycleSilver, long dueGrain, long paidGrain, long shortGrain,
      long dueSilver, long paidSilver, long shortSilver, long closeDay) {
    return new GovRemittanceState(
        cycleGrain,
        cycleSilver,
        dueGrain,
        paidGrain,
        shortGrain,
        dueSilver,
        paidSilver,
        shortSilver,
        closeDay);
  }
}
