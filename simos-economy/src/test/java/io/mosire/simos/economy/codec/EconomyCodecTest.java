package io.mosire.simos.economy.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.map.hex.HexCoord;
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
 * 两侧向）、{@code Optional<String>}/{@code Optional<CommodityId>}、**六个**自定义键（{@code IndustryId}/{@code
 * CohortKey}/{@code DebtId}/{@code CommodityId} + R2 的 {@code PeopleLotId}/{@code
 * LaborAllocationId}）、{@code AssetKind} 的**枚举键**、{@code AllocationRule} 的 **sealed 多态**（{@code
 * Split}/{@code WageFirst} 各一）、{@code FieldDelta} 四变体、 单值组件的投影往返、**字节级**往返（含"派生判断 {@code empty}
 * 不进线格式"的观察点），以及旧档缺键的兼容。
 *
 * <p>★ T2 补第 8 个组件（{@code relations}）：它的值里嵌着**第二个 sealed 多态**（{@code Recipient}）与 {@code
 * CompensationRule} 的 {@code Optional<CommodityId>} —— 见 {@code
 * relationCarriesTheSealedRecipientAndTheMoneyRuleOverTheWire}。
 */
class EconomyCodecTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final IndustryId WORKSHOP = new IndustryId("workshop");
  private static final IndustryId MILL = new IndustryId("mill");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CohortKey PEASANT_KEY =
      new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT);
  private static final CohortKey LANDLORD_KEY =
      new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD);
  private static final DebtId D1 = new DebtId("debt-1");
  private static final DebtId D2 = new DebtId("debt-2");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ R2 的夹具：本文件共用同一个批次与同一条配额（码的是"表"的往返，不是配额的经济含义）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION =
      new LaborAllocationId("alloc-farm-rural:0_0:MALE:1");

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
    // ★ R3：值侧的商品维度也必须真的过线（{"LAND":{"grain":1200}}）—— 退化成标量就在这里红。
    assertThat(farm.dailyInputPerUnit().get(AssetKind.CATTLE)).containsEntry(GRAIN, 1L);
    assertThat(farm.allocation()).isInstanceOf(AllocationRule.Split.class);
    assertThat(farm.capacityPerUnit()).as("★ R3 的产能锚必须过线").containsEntry(AssetKind.LAND, 1000L);
    assertThat(farm.laborPerUnit()).as("★ R3 的劳动那一路必须过线").isEqualTo(143L);
    assertThat(farm.cycleInputPerUnit())
        .as("★ 新字段必须真的过线：空 map 与「字段没进线格式」在值层面不可区分")
        .containsOnlyKeys(AssetKind.LAND);
    assertThat(farm.cycleInputPerUnit().get(AssetKind.LAND)).containsEntry(GRAIN, 1200L);
    assertThat(farm.cycleInputUsedMilli())
        .as("★ R3：本周期实际扣到的投入（按商品）必须过线")
        .containsEntry(GRAIN, 400L);
    // ★★ 夹具是**非默认**值（`tenant` 的推导值是 HOUSEHOLD:farm，见 {@link #industry} 的第 16 个实参）
    //   ⇒ 这一句只有"值真的过了线"才能满足：把 operator 从线格式里丢掉、或解码时按 regime 重新推导，
    //   读到的都会是 `HOUSEHOLD:farm` ⇒ 当场红。
    assertThat(farm.operator())
        .as("★ S1 阶段 3：经营主体必须真的过线（缺它 ⇒ 往返后 operator 没了）")
        .isEqualTo(new ActorRef(ActorKind.ESTATE, "farm@0_0"));
    // ★★ 夹具是**非默认**值（`tenant` 的推导值是 HOUSEHOLD:farm，见 {@link #industry} 的第 16 个实参）
    //   ⇒ 这一句只有"值真的过了线"才能满足：把 operator 从线格式里丢掉、或解码时按 regime 重新推导，
    //   读到的都会是 `HOUSEHOLD:farm` ⇒ 当场红。
    assertThat(workshop.allocation()).isInstanceOf(AllocationRule.WageFirst.class);
    AllocationRule.WageFirst wageFirst = (AllocationRule.WageFirst) workshop.allocation();
    assertThat(wageFirst.ownerResidual()).containsOnlyKeys(GRAIN, CLOTH);
  }

  /**
   * ★★ **T2：第 8 个组件的关系表必须真的过线**，且它值里的**两个"不可能裸往返"的形状**都要被走到：
   *
   * <ul>
   *   <li>{@code Recipient}（**sealed 多态**）：两个变体各一条规则（{@code ToActor} / {@code ToCohort}），
   *       读回时"造哪个变体"只可能来自线格式（类型上的 Jackson 注解）；
   *   <li>{@code CompensationRule.commodity} 的**空侧**（{@code Optional.empty()} = 货币档）与**有值侧**（粮） 各一条
   *       —— 空侧写成 {@code null} 会让货币档与"字段没进线格式"无法区分。
   * </ul>
   *
   * <p>★ 判别力（两条）：把 {@code Recipient} 的注解去掉 ⇒ 解码当场抛（{@code no Creators / abstract types}）⇒ 红； 把
   * {@code compensations} 的 {@code Optional} 换成裸引用 ⇒ 空侧那条红（读回是 null 或抛）。
   */
  @Test
  void relationCarriesTheSealedRecipientAndTheMoneyRuleOverTheWire() {
    ActorRef operator = new ActorRef(ActorKind.ESTATE, FARM.value() + "@0_0");
    EconomyData target =
        EconomyData.empty()
            .withIndustries(Map.of(FARM, industry(FARM, 0L)))
            .withRelations(Map.of(FARM, relation(FARM, operator)));

    EconomyChangeSet back =
        (EconomyChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(EconomyChangeSet.between(EconomyData.empty(), target)));

    assertThat(back.relations()).isInstanceOf(FieldDelta.Upsert.class);
    FieldDelta.Upsert<ProductionRelation> upsert =
        (FieldDelta.Upsert<ProductionRelation>) back.relations();
    ProductionRelation relation = upsert.entries().get("farm");
    assertThat(relation.activity()).as("activity 过线（身份 = 它结算的那个产业）").isEqualTo(FARM);
    assertThat(relation.operator()).isEqualTo(operator);
    assertThat(relation.residualOwner())
        .as("residualOwner 与 operator 是**两件事**，各自过线")
        .isEqualTo(operator);
    assertThat(relation.rules()).as("三条规则逐字过线").hasSize(3);
    assertThat(relation.rules().get(0).recipient())
        .as("★ sealed 多态变体一：读回的是 ToActor（不是 Map、不是别的变体）")
        .isEqualTo(new Recipient.ToActor(operator));
    assertThat(relation.rules().get(0).commodity()).contains(GRAIN);
    assertThat(relation.rules().get(1).recipient())
        .as("★ sealed 多态变体二：读回的是 ToCohort（`CohortKey` 的规范串过线）")
        .isEqualTo(
            new Recipient.ToCohort(
                new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.LANDLORD)));
    assertThat(relation.rules().get(2).commodity()).as("★ 货币档的**空侧**必须过线（空 = 货币是类型事实）").isEmpty();
    assertThat(EconomyChangeSet.apply(back, EconomyData.empty())).isEqualTo(target);
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
    // ★ R2：两张劳动表也是同款（旧档里没有这两个键 ⇒ Unchanged，不是 null）
    assertThat(back.laborSupply()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.allocations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.isEmpty()).as("七个组件都未变 ⇒ 这份旧变更集是空的").isTrue();
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

  /** 非平凡数据：**八张表都非空**、两层自定义键、各 Optional 的有值侧至少出现一次、两种 AllocationRule 都在。 */
  private static EconomyData fullData() {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, 0L));
    industries.put(WORKSHOP, workshopIndustry());
    Map<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, classRow(PEASANT_KEY, 120L));
    classes.put(LANDLORD_KEY, classRow(LANDLORD_KEY, 8L));
    Map<DebtId, Debt> debts = new LinkedHashMap<>();
    debts.put(D1, grainDebt());
    debts.put(D2, moneyDebt());
    Map<CohortKey, FlowRow> flows = new LinkedHashMap<>();
    flows.put(PEASANT_KEY, flowRow(PEASANT_KEY));
    flows.put(LANDLORD_KEY, flowRow(LANDLORD_KEY));
    // ★★ R2：两张劳动表也**非空** —— 它们各有**一个自定义键类型**（PeopleLotId / LaborAllocationId）要过
    //   JSON 的键反序列化器；空表会让那两个注册项**永远不被走到**（"注册了却测不到"= 假覆盖）。
    Map<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>();
    laborSupply.put(LOT, new LaborSupply(LOT, 1L, 60_000L, 1_000L, 500L)); // ★ 两项扣除取非 0
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    allocations.put(
        ALLOCATION,
        new LaborAllocation(
            ALLOCATION, LOT, new ActorRef(ActorKind.ESTATE, FARM.value()), "farm", 58_000L, 1L));
    // ★★ T2：第 8 个组件也**非空** —— 它的值里嵌着**一个 sealed 多态类型**（{@code Recipient}）与**一条货币规则**
    //   （{@code commodity} 空 = `Optional` 的空侧）；空表会让那两处**永远不被走到**（"注册了却测不到"= 假覆盖）。
    //   ★ operator 与 {@link #industry(IndustryId, long)} 的显式值**逐字相同**（跨表守卫要求两处拼写一致）。
    Map<IndustryId, ProductionRelation> relations = new LinkedHashMap<>();
    relations.put(FARM, relation(FARM, new ActorRef(ActorKind.ESTATE, FARM.value() + "@0_0")));
    return new EconomyData(
        Optional.of(meta()),
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations);
  }

  private static EconomyData dataWithIndustries(Map<IndustryId, Industry> industries) {
    return new EconomyData(
        Optional.of(meta()),
        industries,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
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

  /**
   * 一个合规矩的产业：两个槽位各持**劳动投入率上限**（R1.1 起不再是"人口占比"，故**不必合计 1000‰**）， {@code Split(700,300)}。上限须 ≥ 行里的
   * {@code participationPerMille}（此处 800）= v2 spec §八.1 的不变量。
   */
  private static Industry industry(IndustryId id, long progress) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 900));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        progress,
        // ★ R3（V7）：产能那一路（"单位规模"的锚）—— non-null、非空、逐值为正。
        Map.of(AssetKind.LAND, 1000L),
        // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
        Map.of(AssetKind.LAND, 2700L),
        // ★ 非空：空 map 与"字段没进线格式"在值层面不可区分（R3 起值侧再带一层商品维度）。
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        500L,
        143L,
        Map.of(GRAIN, 7L),
        // ★ 非空：空 map 与"字段没进线格式"在值层面不可区分（R3 换型后是"生产资料 → 商品表"）。
        Map.of(AssetKind.LAND, Map.of(GRAIN, 1200L)),
        slots,
        new AllocationRule.Split(700, 300),
        0L,
        Map.of(GRAIN, 400L),
        // ★★ **非默认值**（S1 阶段 3 的 D9 纪律：往返夹具**不许**用派生值）——
        //   本夹具的 regime 是 `tenant` ⇒ 推导值 = defaultOperator(tenant, id) = `HOUSEHOLD:<产业 id>`；
        //   这里显式给的是 `ESTATE:<产业 id>@0_0`：**kind 与推导值不同**（ESTATE ≠ HOUSEHOLD）
        //   ⇒ 往返后读到它**只可能来自线格式**，不可能来自任何推导（`id = FARM` ⇒ `ESTATE:farm@0_0`，
        //   即 `deltaValuesSurviveAsIndustryWithCommodityKeys` 里那条过线断言期望的字面量）。
        new ActorRef(ActorKind.ESTATE, id.value() + "@0_0"));
  }

  /**
   * ★★ T2 的一条生产关系（**非派生夹具**：规则内容与 {@code RegimeRelations} 的四档都不同）。
   *
   * <p>★ 三条规则刻意把三个"线格式上的难点"各占一条：{@code ToActor}（变体一）、{@code ToCohort}（变体二 + {@code CohortKey}
   * 的规范串）、货币档（{@code commodity} 的**空侧**）。
   */
  private static ProductionRelation relation(IndustryId id, ActorRef operator) {
    return new ProductionRelation(
        id,
        operator,
        List.of(
            new CompensationRule(
                RuleType.OUTPUT_SHARE,
                new Recipient.ToActor(operator),
                Basis.GROSS_OUTPUT,
                300,
                0L,
                Optional.of(GRAIN),
                10),
            new CompensationRule(
                RuleType.FIXED_IN_KIND_RENT,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD)),
                Basis.FIXED_AMOUNT,
                0,
                5_000L,
                Optional.of(CLOTH),
                20),
            new CompensationRule(
                RuleType.FIXED_MONEY_WAGE,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT)),
                Basis.FIXED_AMOUNT,
                0,
                7L,
                Optional.empty(),
                30)),
        operator);
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
        Map.of(AssetKind.WORKSHOP, 1L),
        // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
        Map.of(AssetKind.WORKSHOP, 0L), // ★ 产能锚：规模单位 = 1 座工坊
        Map.of(AssetKind.TOOL, Map.of(GRAIN, 2L)),
        300L,
        1000L,
        Map.of(CLOTH, 5L),
        Map.of(), // ★ WageFirst 多态夹具：未配一次性投入（空 map 是合法形状）
        slots,
        new AllocationRule.WageFirst(4L, residual),
        0L,
        Map.of(),
        // ★★ **必须显式给**：`capitalist` **未登记**在推导表里（裁定 R1 的 fail-closed）⇒ 不许猜。
        //   值取"经营这座工坊的那个作坊"（`WORKSHOP:<产业 id>`），与劳动侧的 actor 同字面。
        new ActorRef(ActorKind.WORKSHOP, WORKSHOP.value()));
  }

  private static ClassRow classRow(CohortKey key, long population) {
    // ★★ H1（K1）：行里没有 goods 了（家户的商品库存住在 actor 切片的 GoodsAccount / 经济侧的会话工作副本里）。
    return new ClassRow(
        key, population, 60000L, 800, 50L, List.of(D1), Map.of(GRAIN, 40L), Map.of(GRAIN, 30L));
  }

  private static Debt grainDebt() {
    return new Debt(D1, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN), 100L, 20, 3L, false);
  }

  private static Debt moneyDebt() {
    return new Debt(D2, LANDLORD_KEY, PEASANT_KEY, Optional.empty(), 700L, 5, 5L, true);
  }

  private static FlowRow flowRow(CohortKey key) {
    // ★ R3：income 是**逐商品**的表（两种商品，故"退回标量"的实现过不了这一条）。
    // ★ R4：unmetNeed 也逐商品、并多一个 births（与 deaths 对称）。
    return new FlowRow(
        key,
        Map.of(GRAIN, 200L, CLOTH, 15L),
        Map.of(GRAIN, 120L),
        10L,
        5L,
        0L,
        0L,
        65L,
        Map.of(GRAIN, 7L, CLOTH, 3L),
        3L,
        4L);
  }
}
