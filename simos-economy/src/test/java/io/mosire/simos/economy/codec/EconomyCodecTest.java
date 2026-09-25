package io.mosire.simos.economy.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * economy 模块的 JSON 往返守卫（照 {@code LedgerCodecTest} 同制，夹具是 economy 自己的）。
 *
 * <p>★ 覆盖：{@code Optional<EconomyMeta>} 两侧向（未激活 / 已激活）、{@code OptionalLong}（{@code lastClosedCycle}
 * 两侧向）、{@code Optional<String>}/{@code Optional<CommodityId>}、四个自定义键（{@code IndustryId}/{@code
 * ClassKey}/{@code DebtId}/{@code CommodityId}）、{@code AssetKind} 的**枚举键**、{@code AllocationRule} 的
 * **sealed 多态**（{@code Split}/{@code WageFirst} 各一）、{@code FieldDelta} 四变体、
 * 单值组件的投影往返、**字节级**往返（含"派生判断 {@code empty} 不进线格式"的观察点），以及旧档缺键的兼容。
 */
class EconomyCodecTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final IndustryId WORKSHOP = new IndustryId("workshop");
  private static final IndustryId MILL = new IndustryId("mill");
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);
  private static final DebtId D1 = new DebtId("debt-1");
  private static final DebtId D2 = new DebtId("debt-2");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");

  private static final EconomyCodec CODEC = new EconomyCodec();

  @Test
  void namespaceIsEconomy() {
    assertThat(CODEC.namespace()).isEqualTo("economy");
  }

  /** 非平凡快照往返：带历注 + 四张表都非空 + 两层自定义键 + 各 Optional 的有值侧 + 两种 AllocationRule。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    EconomySnapshot snapshot = snapshotOf(fullData(), SimosTimestamp.of(10, "弘光元年"));

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
  }

  /** Optional 的另一个方向：无历注（empty）也要活着。 */
  @Test
  void snapshotRoundTripsWithUnlabeledTimestamp() {
    EconomySnapshot snapshot = snapshotOf(fullData(), SimosTimestamp.of(11));

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
  }

  /** ★ **未激活**：{@code meta} 为空 {@code Optional}，且四张实体表为空——"空切片 ≠ 已激活"（§6.6）。 */
  @Test
  void snapshotRoundTripsWithUnactivatedEconomyMeta() {
    EconomySnapshot snapshot = snapshotOf(EconomyData.empty(), SimosTimestamp.of(12, "弘光元年"));

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.data().meta()).as("未激活必须原样回来").isEmpty();
  }

  /** 变更集往返：四条变体各造一条（都落在 {@code industries} 组件上），逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    EconomyData full = fullData();
    EconomyData base = dataWithIndustries(industriesFrom(industry(FARM, 0L), industry(MILL, 0L)));
    EconomyData moved =
        dataWithIndustries(industriesFrom(industry(FARM, 5L), industry(WORKSHOP, 0L)));

    EconomyChangeSet unchanged = EconomyChangeSet.between(full, full);
    EconomyChangeSet upsert = EconomyChangeSet.between(EconomyData.empty(), full);
    EconomyChangeSet remove = EconomyChangeSet.between(full, EconomyData.empty());
    EconomyChangeSet patch = EconomyChangeSet.between(base, moved);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.industries()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.industries()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.industries()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.industries()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /**
   * ★ 值类型的绑定不能在读入侧丢成 Map：{@code Industry} 得还是 {@code Industry}，且它内部的商品键得还是 {@code CommodityId}、
   * 资产键得还是 {@code AssetKind}、分配函数得还是 {@code AllocationRule}（sealed 多态 + 嵌套 map 键在这里受检）。
   */
  @Test
  void deltaValuesSurviveAsIndustryWithCommodityKeys() {
    EconomyChangeSet back =
        (EconomyChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(
                    EconomyChangeSet.between(
                        EconomyData.empty(),
                        dataWithIndustries(
                            industriesFrom(industry(FARM, 0L), workshopIndustry())))));

    FieldDelta.Upsert<Industry> upsert = (FieldDelta.Upsert<Industry>) back.industries();
    Industry farm = upsert.entries().get("farm");
    Industry workshop = upsert.entries().get("workshop");

    assertThat(farm).isInstanceOf(Industry.class);
    assertThat(farm.outputPerUnit()).containsOnlyKeys(GRAIN);
    assertThat(farm.dailyInputPerUnit()).containsOnlyKeys(AssetKind.CATTLE);
    assertThat(farm.allocation()).isInstanceOf(AllocationRule.Split.class);
    assertThat(workshop.allocation()).isInstanceOf(AllocationRule.WageFirst.class);
    AllocationRule.WageFirst wageFirst = (AllocationRule.WageFirst) workshop.allocation();
    assertThat(wageFirst.ownerResidual()).containsOnlyKeys(GRAIN, CLOTH);
  }

  /**
   * ★ **单值组件的投影往返**（{@code meta} 是 {@code Optional<EconomyMeta>}，走"单键表"投影进 {@code
   * FieldDelta}）：未激活→已激活 = {@code Upsert}、已激活→未激活 = {@code Remove}、值→另一个值 = {@code Upsert}，三条都要过线。
   */
  @Test
  void metaDeltaRoundTripsInAllThreeShapes() {
    EconomyData unactivated = EconomyData.empty();
    EconomyData activated = unactivated.withMeta(Optional.of(meta()));

    EconomyChangeSet act = EconomyChangeSet.between(unactivated, activated);
    EconomyChangeSet deact = EconomyChangeSet.between(activated, unactivated);
    EconomyChangeSet rev =
        EconomyChangeSet.between(activated, activated.withMeta(Optional.of(metaClosed())));

    assertThat(act.meta()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(deact.meta()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(rev.meta()).isInstanceOf(FieldDelta.Upsert.class);

    assertThat(EconomyChangeSet.apply(act, unactivated)).isEqualTo(activated);
    assertThat(EconomyChangeSet.apply(deact, activated)).isEqualTo(unactivated);
    assertThat(deact.isEmpty()).isFalse();

    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(act))).isEqualTo(act);
    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(deact)))
        .isEqualTo(deact);
    assertThat((EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(rev))).isEqualTo(rev);
  }

  /**
   * ★ **字节级往返**：编码 → 解码 → **再编码**，两次编码**逐字节相等**——它能抓住"解码时把有序容器换成 {@code
   * Map.copyOf}"/"键序漂移"这类内容相等而迭代序漂移的退化。
   *
   * <p>★ 同时钉住"派生判断 {@code empty} **不进线格式**"（{@code isEmpty()} 不是状态；写进去会让严格读入当场炸）。
   */
  @Test
  void encodingIsByteLevelStableForEconomyData() {
    EconomyData data = fullData();

    String snapshotOnce = CODEC.encodeSnapshot(snapshotOf(data, SimosTimestamp.of(10, "弘光元年")));
    String snapshotTwice = CODEC.encodeSnapshot(CODEC.decodeSnapshot(snapshotOnce));
    assertThat(snapshotTwice).as("快照的字节级往返").isEqualTo(snapshotOnce);

    EconomyChangeSet changeSet = EconomyChangeSet.between(EconomyData.empty(), data);
    String changeOnce = CODEC.encodeChangeSet(changeSet);
    String changeTwice = CODEC.encodeChangeSet(CODEC.decodeChangeSet(changeOnce));
    assertThat(changeTwice).as("变更集的字节级往返").isEqualTo(changeOnce);

    assertThat(changeOnce).as("派生判断 empty 不得进线格式").doesNotContain("\"empty\"");
    assertThat(snapshotOnce).as("快照里也没有派生判断").doesNotContain("\"empty\"");
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    EconomySnapshot base = snapshotOf(EconomyData.empty(), SimosTimestamp.of(10));
    EconomyChangeSet changeSet =
        EconomyChangeSet.between(base.data(), EconomyData.empty().withIndustries(oneIndustry()));
    StateMeta newMeta =
        new StateMeta(
            new StateRef(new BranchId("main"), new RevisionId(42)), SimosTimestamp.of(11));

    EconomySnapshot applied = (EconomySnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.data().industries()).containsOnlyKeys(FARM);
    assertThat(base.data().industries()).as("base 不得被就地改").isEmpty();
  }

  /** 施加到别的模块的切片上 ⇒ 当场炸，不给出一份错快照。 */
  @Test
  void applyRejectsForeignSlice() {
    EconomySnapshot base = snapshotOf(EconomyData.empty(), SimosTimestamp.of(10));
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(
            () ->
                CODEC.apply(
                    EconomyChangeSet.between(EconomyData.empty(), EconomyData.empty()),
                    foreign,
                    new StateMeta(
                        new StateRef(new BranchId("main"), new RevisionId(1)),
                        SimosTimestamp.of(1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 EconomySnapshot");

    // base 本身合法，只为让"foreign"这一侧成为唯一失败点
    assertThat(base.namespace()).isEqualTo("economy");
  }

  /**
   * {@code EconomyCodec} 同时实现 {@link io.mosire.simos.util.spi.ModuleDiffer}：语义委托 {@code between}。
   */
  @Test
  void diffDerivesTheChangeSetFromTwoFullSlices() {
    EconomySnapshot base = snapshotOf(EconomyData.empty(), SimosTimestamp.of(10));
    EconomySnapshot target = snapshotOf(fullData(), SimosTimestamp.of(10));

    assertThat(CODEC.diff(base, target))
        .isEqualTo(EconomyChangeSet.between(base.data(), target.data()));
    assertThat(CODEC.diff(base, base))
        .as("全相等 ⇒ 各组件 Unchanged（仍是一份合法变更集）")
        .isEqualTo(EconomyChangeSet.between(base.data(), base.data()));
  }

  /** diff 的两侧切片都必须属于本模块：转错切片 ⇒ 当场炸，不给出一份错变更集。 */
  @Test
  void diffRejectsForeignSlice() {
    EconomySnapshot economy = snapshotOf(fullData(), SimosTimestamp.of(10));
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.diff(foreign, economy))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 EconomySnapshot");
  }

  /**
   * ★★ **旧档兼容：缺键的快照必须读得回来**（§11 的口径，照 {@code LedgerCodecTest}）。
   *
   * <p>字节刻意只留 {@code industries}：{@code classes}/{@code debts}/{@code flows}/{@code meta}
   * 四个键**缺席**。 若让构造器对 null 抛，等于"这个世界打不开"。缺省方向是 fail-closed：缺 ⇒ 空表 / 未激活。
   */
  @Test
  void legacySnapshotWithoutEconomyKeysDecodesToUnactivatedEmptyTables() {
    String legacy =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},"
            + "\"timestamp\":{\"tick\":0,\"calendarLabel\":null},"
            + "\"data\":{\"industries\":{}}}";

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(legacy);

    assertThat(back.data().industries()).isEmpty();
    assertThat(back.data().classes()).as("旧档没提阶层 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().debts()).as("旧档没提债务 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().flows()).as("旧档没提流水 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().meta()).as("旧档没提元信息 ⇒ 未激活，不抛").isEmpty();
  }

  /**
   * ★★ **旧档兼容：缺键的变更集必须读得回来**。缺省 = {@link FieldDelta.Unchanged}（"一字未动"）——读成 null 的话 {@code
   * isEmpty()} 与 {@code apply} 都会 NPE。
   */
  @Test
  void legacyChangeSetWithoutEconomyKeysDecodesToUnchangedComponents() {
    String legacy = "{\"industries\":{\"@class\":\"unchanged\"}}";

    EconomyChangeSet back = (EconomyChangeSet) CODEC.decodeChangeSet(legacy);

    assertThat(back.meta()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.classes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.debts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.flows()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.isEmpty()).as("五个组件都未变 ⇒ 这份旧变更集是空的").isTrue();
    assertThat(EconomyChangeSet.apply(back, EconomyData.empty())).isEqualTo(EconomyData.empty());
  }

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "unit";
    }
  }

  // ── 夹具 ──

  /** 非平凡数据：四张表都非空、两层自定义键、各 Optional 的有值侧至少出现一次、两种 AllocationRule 都在。 */
  private static EconomyData fullData() {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, 0L));
    industries.put(WORKSHOP, workshopIndustry());
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, classRow(PEASANT_KEY, 120L));
    classes.put(LANDLORD_KEY, classRow(LANDLORD_KEY, 8L));
    Map<DebtId, Debt> debts = new LinkedHashMap<>();
    debts.put(D1, grainDebt());
    debts.put(D2, moneyDebt());
    Map<ClassKey, FlowRow> flows = new LinkedHashMap<>();
    flows.put(PEASANT_KEY, flowRow(PEASANT_KEY));
    flows.put(LANDLORD_KEY, flowRow(LANDLORD_KEY));
    return new EconomyData(Optional.of(meta()), industries, classes, debts, flows);
  }

  private static EconomyData dataWithIndustries(Map<IndustryId, Industry> industries) {
    return new EconomyData(Optional.of(meta()), industries, Map.of(), Map.of(), Map.of());
  }

  private static Map<IndustryId, Industry> oneIndustry() {
    return Map.of(FARM, industry(FARM, 0L));
  }

  private static Map<IndustryId, Industry> industriesFrom(Industry... industries) {
    Map<IndustryId, Industry> out = new LinkedHashMap<>();
    for (Industry industry : industries) {
      out.put(industry.id(), industry);
    }
    return out;
  }

  private static EconomySnapshot snapshotOf(EconomyData data, SimosTimestamp timestamp) {
    return new EconomySnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, data);
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "m1", 7L, OptionalLong.of(2L), "rules-2026-09", Optional.of("worlds/v17levant.json"));
  }

  private static EconomyMeta metaClosed() {
    return new EconomyMeta("m1", 7L, OptionalLong.of(3L), "rules-2026-10", Optional.empty());
  }

  /** 一个合规矩的产业（两槽位占比合计 1000‰），{@code Split(700,300)}。 */
  private static Industry industry(IndustryId id, long progress) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 700), new ClassSlot(LANDLORD, "地主", 300));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        progress,
        Map.of(AssetKind.CATTLE, 1L),
        500L,
        Map.of(GRAIN, 7L),
        slots,
        new AllocationRule.Split(700, 300));
  }

  /** 资本主义工业：{@code WageFirst} + 嵌套商品键（企业主剩余）。 */
  private static Industry workshopIndustry() {
    List<ClassSlot> slots = List.of(new ClassSlot(PEASANT, "工人", 1000));
    Map<CommodityId, Long> residual = new LinkedHashMap<>();
    residual.put(GRAIN, 30L);
    residual.put(CLOTH, 20L);
    return new Industry(
        WORKSHOP,
        "工坊",
        new RegimeId("capitalist"),
        30L,
        0L,
        Map.of(AssetKind.TOOL, 2L),
        300L,
        Map.of(CLOTH, 5L),
        slots,
        new AllocationRule.WageFirst(4L, residual));
  }

  private static ClassRow classRow(ClassKey key, long population) {
    return new ClassRow(
        key,
        population,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L, CLOTH, 10L),
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static Debt grainDebt() {
    return new Debt(D1, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN), 100L, 20, 3L, false);
  }

  private static Debt moneyDebt() {
    return new Debt(D2, LANDLORD_KEY, PEASANT_KEY, Optional.empty(), 700L, 5, 5L, true);
  }

  private static FlowRow flowRow(ClassKey key) {
    return new FlowRow(key, 200L, Map.of(GRAIN, 120L), 10L, 5L, 0L, 0L, 65L);
  }
}
