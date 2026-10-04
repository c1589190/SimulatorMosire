package io.mosire.simos.economy.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
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
 * 两侧向）、{@code Optional<String>}/{@code Optional<CommodityId>}、自定义键（{@code IndustryId} / {@code
 * HouseholdId}（S1 起 classes/flows 的键；旧档 {@code CohortKey} 串由 codec 映射成 {@code ofLegacy}）/ {@code
 * DebtContractId} / {@code CommodityId} + R2 的 {@code PeopleLotId} / {@code LaborAllocationId} + S1
 * 的 {@code MembershipId} / {@code AssetShareId} + R3B.2 的 {@code ProductionUnitId}）、{@code
 * AssetKind} 的**枚举键**、 {@code AllocationRule} 的 **sealed 多态**（{@code Split}/{@code WageFirst}
 * 各一）、{@code FieldDelta} 四变体、 单值组件的投影往返、**字节级**往返（含"派生判断 {@code empty} 不进线格式"的观察点），以及旧档缺键的兼容。
 *
 * <p>★ T2 补第 8 个组件（{@code relations}）：它的值里嵌着**第二个 sealed 多态**（{@code Recipient}）与 {@code
 * CompensationRule} 的 {@code Optional<CommodityId>} —— 见 {@code
 * relationCarriesTheSealedRecipientAndTheMoneyRuleOverTheWire}。★ R3B.2 起它的键 / {@code activity} 都是
 * {@link ProductionUnitId}（关系挂在 unit 上，不再是 {@code IndustryId}），故夹具必须先有同 operator 的 unit。
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

  /**
   * ★ S1 起 {@code classes}/{@code flows} 的键 = **稳定家户身份** {@link HouseholdId}（视图住在 {@code
   * ClassRow.view}）；旧档的 {@link CohortKey} 由 {@code EconomyCodec} 读入时映射成 {@code ofLegacy}。本测试的夹具
   * 直接按旧视图造 id，等价于"旧档读入后的新形状"。
   */
  private static final HouseholdId FARM_HH = HouseholdIds.ofLegacy(PEASANT_KEY);

  private static final HouseholdId LANDLORD_HH = HouseholdIds.ofLegacy(LANDLORD_KEY);
  private static final DebtTerms GRAIN_TERMS = DebtTerms.legacyDefault();
  private static final CommodityId GRAIN = new CommodityId("grain");

  /**
   * ★★ H2 的币种位夹具：**独立字面量**（不引用 {@code RegimeRelations.DEFAULT_CURRENCY}）—— 往返夹具的纪律是
   * "期望值不许从被测物派生"，引用生产的出厂值会让"币种真的过了线格式"这条断言变成自证。
   */
  private static final CurrencyId CURRENCY = new CurrencyId("silver");

  /** ★ E4a：连续合同身份（key == 值内 id，且 id 由四元组派生；故必须在 FARM_HH/LANDLORD_HH/GRAIN/CURRENCY 之后）。 */
  private static final DebtContractId D1 =
      DebtContractId.idOf(FARM_HH, LANDLORD_HH, DebtUnit.commodity(GRAIN), GRAIN_TERMS);

  private static final DebtContractId D2 =
      DebtContractId.idOf(
          LANDLORD_HH, FARM_HH, DebtUnit.money(CURRENCY), DebtTerms.legacyDefault());

  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ R2 的夹具：本文件共用同一个批次与同一条配额（码的是"表"的往返，不是配额的经济含义）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION =
      new LaborAllocationId("alloc-farm-rural:0_0:MALE:1");

  /**
   * ★★ **非派生**经营主体（S1 阶段 3 的 D9 纪律）：{@code tenant} 的推导值是 {@code HOUSEHOLD:farm}，这里显式给 {@code
   * ESTATE:farm}（kind 与推导值不同）⇒ "operator 真的过了线"这条断言不可能被任何推导满足。
   *
   * <p>★ 为什么 id 取 {@code farm} 而不是 {@code farm@0_0}：{@code EconomyData} 的守卫要求产业型
   * actor（ESTATE/WORKSHOP） 的 id **必须命中一个已存在的产业 id**（劳动结算按 actor id 归属）；本夹具的产业 id 是 {@code
   * farm}，故这里是唯一自洽的字面量。
   */
  private static final ActorRef FARM_OPERATOR = new ActorRef(ActorKind.ESTATE, FARM.value());

  /** ★ R3B.2：劳动与关系都挂在生产单元上；unit id 的唯一拼写点 = {@link ProductionUnitId#idOf}。 */
  private static final ProductionUnitId FARM_UNIT = ProductionUnitId.idOf(FARM, FARM_OPERATOR);

  /** ★ S1：成员份额（键 == 值内 id；Σcount 必须等于 Σ行人口）。 */
  private static final MembershipId PEASANT_MEMBERSHIP = Membership.idOf(LOT, FARM_HH);

  private static final MembershipId LANDLORD_MEMBERSHIP = Membership.idOf(LOT, LANDLORD_HH);

  /** ★ R3B.1：实物资产份额（键 == 值内 id；industry 必须存在）。 */
  private static final AssetShareId FARM_LAND_SHARE =
      AssetShare.idOf(
          FARM, AssetKind.LAND, FARM_OPERATOR, FARM_OPERATOR, AssetShare.RightKind.OWNED, 0L);

  private static final EconomyCodec CODEC = new EconomyCodec();

  @Test
  void namespaceIsEconomy() {
    assertThat(CODEC.namespace()).isEqualTo("economy");
  }

  /** 非平凡快照往返：带历注 + 多张表都非空 + 两层自定义键 + 各 Optional 的有值侧 + 两种 AllocationRule。 */
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

  /** ★ **未激活**：{@code meta} 为空 {@code Optional}，且实体表为空——"空切片 ≠ 已激活"（§6.6）。 */
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
    // ★ 第二个实参是模板里的 laborPerUnit（旧 API 用进度位制造差异；R3B.2 起进度已移出 Industry）。
    EconomyData base = dataWithIndustries(industriesFrom(industry(FARM, 143L), industry(MILL, 0L)));
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
                            industriesFrom(industry(FARM, 143L), workshopIndustry())))));

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
    assertThat(workshop.allocation()).isInstanceOf(AllocationRule.WageFirst.class);
    AllocationRule.WageFirst wageFirst = (AllocationRule.WageFirst) workshop.allocation();
    assertThat(wageFirst.ownerResidual()).containsOnlyKeys(GRAIN, CLOTH);
  }

  /**
   * ★★ <b>R3B.2：operator / 周期状态的真值搬进了 {@link ProductionUnit}</b> —— 旧用例在 {@code Industry} 上断言
   * {@code operator()} / {@code cycleInputUsedMilli()}（那两个字段现在只是旧档兼容位，新代码一律走 12 参模板、恒中性），
   * 故这里把**同一条判别力** 迁到 unit 上：
   *
   * <ul>
   *   <li>{@code operator} 取**非派生值**（{@code ESTATE:farm}；{@code tenant} 的推导值是 {@code
   *       HOUSEHOLD:farm}） ⇒ 把 operator 从线格式里丢掉、或解码时按 regime 重新推导，读到的都会是推导值 ⇒ 当场红；
   *   <li>{@code progressDays} / {@code cycleLaborMilli} / {@code cycleInputUsedMilli} 取**非零 /
   *       非空**值 ⇒ "字段没进线格式"在值层面不可区分，只有真过线才能满足。
   * </ul>
   */
  @Test
  void unitOperatorAndCycleStateSurviveAsProductionUnit() {
    ProductionUnit unit =
        new ProductionUnit(
            FARM_UNIT, FARM, FARM_OPERATOR, FARM.value(), 7L, 1234L, Map.of(GRAIN, 400L));
    EconomyData target =
        EconomyData.empty()
            .withIndustries(Map.of(FARM, industry(FARM, 143L)))
            .withUnits(Map.of(FARM_UNIT, unit));

    EconomyChangeSet back =
        (EconomyChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(EconomyChangeSet.between(EconomyData.empty(), target)));

    assertThat(back.units()).isInstanceOf(FieldDelta.Upsert.class);
    FieldDelta.Upsert<ProductionUnit> upsert = (FieldDelta.Upsert<ProductionUnit>) back.units();
    ProductionUnit read = upsert.entries().get(FARM_UNIT.value());

    assertThat(read).isNotNull();
    assertThat(read.operator())
        .as("★ S1/R3B.2：经营主体必须真的过线（缺它 ⇒ 往返后 operator 没了）")
        .isEqualTo(FARM_OPERATOR);
    assertThat(read.modeKey()).as("生产方式键必须过线").isEqualTo(FARM.value());
    assertThat(read.progressDays()).as("本周期进度必须过线").isEqualTo(7L);
    assertThat(read.cycleLaborMilli()).as("本周期累计劳动必须过线").isEqualTo(1234L);
    assertThat(read.cycleInputUsedMilli())
        .as("★ R3：本周期实际扣到的投入（按商品）必须过线")
        .containsEntry(GRAIN, 400L);
    assertThat(EconomyChangeSet.apply(back, EconomyData.empty())).isEqualTo(target);
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
   *
   * <p>★ R3B.2：关系挂在 unit 上 ⇒ 夹具必须先建同 operator 的 unit（键 = unit id，值内 activity 逐字相等）。
   */
  @Test
  void relationCarriesTheSealedRecipientAndTheMoneyRuleOverTheWire() {
    ActorRef operator = FARM_OPERATOR;
    EconomyData target =
        EconomyData.empty()
            .withIndustries(Map.of(FARM, industry(FARM, 143L)))
            .withUnits(Map.of(FARM_UNIT, farmUnit()))
            .withRelations(Map.of(FARM_UNIT, relation(FARM_UNIT, operator)));

    EconomyChangeSet back =
        (EconomyChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(EconomyChangeSet.between(EconomyData.empty(), target)));

    assertThat(back.relations()).isInstanceOf(FieldDelta.Upsert.class);
    FieldDelta.Upsert<ProductionRelation> upsert =
        (FieldDelta.Upsert<ProductionRelation>) back.relations();
    ProductionRelation relation = upsert.entries().get(FARM_UNIT.value());
    assertThat(relation.activity()).as("activity 过线（身份 = 它结算的那个生产单元）").isEqualTo(FARM_UNIT);
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
   * <p>字节刻意只留 {@code industries}：其余组件键**缺席**。 若让构造器对 null 抛，等于"这个世界打不开"。缺省方向是 fail-closed：缺 ⇒ 空表 /
   * 未激活。
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
    assertThat(back.data().debtContracts()).as("旧档没提债务 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().flows()).as("旧档没提流水 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().meta()).as("旧档没提元信息 ⇒ 未激活，不抛").isEmpty();
    // ★ R2 起的新组件同款：缺键 ⇒ 空表（fail-closed 方向）
    assertThat(back.data().laborSupply()).isEmpty();
    assertThat(back.data().allocations()).isEmpty();
    assertThat(back.data().relations()).isEmpty();
    assertThat(back.data().memberships()).isEmpty();
    assertThat(back.data().units()).isEmpty();
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
    assertThat(back.industries()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.classes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.debtContracts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.flows()).isInstanceOf(FieldDelta.Unchanged.class);
    // ★ R2：两张劳动表也是同款（旧档里没有这两个键 ⇒ Unchanged，不是 null）
    assertThat(back.laborSupply()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.allocations()).isInstanceOf(FieldDelta.Unchanged.class);
    // ★ T2/H4/M2.4/S1/S3/R3B/R4-E2 依次补齐的组件同款。
    assertThat(back.relations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.markets()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.shipments()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.memberships()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.assetShares()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.operatorConditions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.units()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.demands()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.candidates()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.isEmpty()).as("二十九个组件都未变 ⇒ 这份旧变更集是空的").isTrue();
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

  /**
   * 非平凡数据：产业 / 阶层 / 债务 / 流水 / 劳动供给 / 劳动分配 / 生产关系 / 成员份额 / 资产份额 / 生产单元**都非空**，两层自定义键、 各 Optional
   * 的有值侧至少出现一次、两种 AllocationRule 都在；市场 / 在途 / 经营者状态 / 需求 / 候选留空（追加在尾部的中性值）。
   *
   * <p>★ 夹具必须是**当前形状且自洽**：{@code classes} 带 memberships（否则构造期会自动跑 {@code LegacyHouseholdMigration}
   * 反推成员份额）、{@code allocations} 的 activity 指到 unit 且 actor == unit.operator、 {@code relations} 键 ==
   * unit id —— 于是构造期迁移是 no-op，往返量的就是本夹具本身。
   */
  private static EconomyData fullData() {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, 143L));
    industries.put(WORKSHOP, workshopIndustry());
    Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    classes.put(FARM_HH, classRow(FARM_HH, PEASANT_KEY, 120L));
    classes.put(LANDLORD_HH, classRow(LANDLORD_HH, LANDLORD_KEY, 8L));
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    debts.put(D1, grainDebt());
    debts.put(D2, moneyDebt());
    Map<HouseholdId, FlowRow> flows = new LinkedHashMap<>();
    flows.put(FARM_HH, flowRow(FARM_HH));
    flows.put(LANDLORD_HH, flowRow(LANDLORD_HH));
    // ★★ R2：两张劳动表也**非空** —— 它们各有**一个自定义键类型**（PeopleLotId / LaborAllocationId）要过
    //   JSON 的键反序列化器；空表会让那两个注册项**永远不被走到**（"注册了却测不到"= 假覆盖）。
    Map<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>();
    laborSupply.put(LOT, new LaborSupply(LOT, 1L, 60_000L, 1_000L, 500L)); // ★ 两项扣除取非 0
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    allocations.put(
        ALLOCATION,
        new LaborAllocation(
            ALLOCATION, LOT, FARM_HH, FARM_OPERATOR, FARM_UNIT.value(), 58_000L, 1L));
    // ★★ T2/R3B.2：第 8 个组件也**非空** —— 它的值里嵌着**一个 sealed 多态类型**（{@code Recipient}）与**一条货币规则**
    //   （{@code commodity} 空 = `Optional` 的空侧）；空表会让那两处**永远不被走到**（"注册了却测不到"= 假覆盖）。
    //   ★ 键 / activity = unit id，operator 与 unit.operator 逐字相同（跨表守卫要求两处拼写一致）。
    Map<ProductionUnitId, ProductionRelation> relations = new LinkedHashMap<>();
    relations.put(FARM_UNIT, relation(FARM_UNIT, FARM_OPERATOR));
    Map<ProductionUnitId, ProductionUnit> units = new LinkedHashMap<>();
    units.put(FARM_UNIT, farmUnit());
    // ★ S1：成员份额非空 + Σcount == Σ行人口（120 + 8）⇒ 构造期不再自动迁移、也不再是"只有行没有成员"的半态。
    Map<MembershipId, Membership> memberships = new LinkedHashMap<>();
    memberships.put(PEASANT_MEMBERSHIP, new Membership(PEASANT_MEMBERSHIP, LOT, FARM_HH, 120L));
    memberships.put(LANDLORD_MEMBERSHIP, new Membership(LANDLORD_MEMBERSHIP, LOT, LANDLORD_HH, 8L));
    // ★ R3B.1：实物资产份额非空 —— AssetShareId 是另一处自定义键反序列化注册项。
    Map<AssetShareId, AssetShare> assetShares = new LinkedHashMap<>();
    assetShares.put(
        FARM_LAND_SHARE,
        new AssetShare(
            FARM_LAND_SHARE,
            FARM,
            AssetKind.LAND,
            FARM_OPERATOR,
            FARM_OPERATOR,
            1000L,
            AssetShare.RightKind.OWNED));
    // ★ E1–E6：用 withX 逐组件搭（避免 29 参 record arity 漂移）。先挂 pre-modern-v1 迁移标记，
    //   防止“classes 非空但 memberships 尚未挂上”的中间态触发自动旧档迁移；尾步再换回本用例的 meta。
    return EconomyData.empty()
        .withMeta(Optional.of(metaPreModern()))
        .withIndustries(industries)
        .withClasses(classes)
        .withMemberships(memberships)
        .withAssetShares(assetShares)
        .withUnits(units)
        .withDebtContracts(debts)
        .withFlows(flows)
        .withLaborSupply(laborSupply)
        .withAllocations(allocations)
        .withRelations(relations)
        .withMeta(Optional.of(meta()));
  }

  private static EconomyData dataWithIndustries(Map<IndustryId, Industry> industries) {
    return EconomyData.empty().withMeta(Optional.of(meta())).withIndustries(industries);
  }

  private static Map<IndustryId, Industry> oneIndustry() {
    return Map.of(FARM, industry(FARM, 143L));
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

  /** 构造 fullData 期间的迁移抑制标记；尾步换回 {@link #meta()}。 */
  private static EconomyMeta metaPreModern() {
    return new EconomyMeta(
        "m1",
        7L,
        OptionalLong.of(2L),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.of("worlds/v17levant.json"));
  }

  private static EconomyMeta metaClosed() {
    return new EconomyMeta("m1", 7L, OptionalLong.of(3L), "rules-2026-10", Optional.empty());
  }

  /** 本夹具共用的生产单元：{@link #FARM_UNIT} + 显式 operator + 中性周期状态。 */
  private static ProductionUnit farmUnit() {
    return new ProductionUnit(FARM_UNIT, FARM, FARM_OPERATOR, FARM.value(), 0L, 0L, Map.of());
  }

  /**
   * 一个合规矩的产业（R3B.2 起是**纯技术模板**的 12 参构造；operator / 周期状态在 {@link ProductionUnit} 上）：两个槽位各持**劳动投入率上限**
   * （R1.1 起不再是"人口占比"，故**不必合计 1000‰**），{@code Split(700,300)}。{@code capacityPerUnit}
   * 非空且为正（"单位规模"的锚）， 各表的值侧都带商品维度。
   *
   * @param laborPerUnit 每 1 单位规模需要的劳动（千分劳动）；顺带当 {@code changeSetRoundTripsWithAllFourDeltaVariants}
   *     制造"同键不同值"的差异维
   */
  private static Industry industry(IndustryId id, long laborPerUnit) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 900));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        // ★ R3（V7）：产能那一路（"单位规模"的锚）—— non-null、非空、逐值为正。
        Map.of(AssetKind.LAND, 1000L),
        // ★ 非空：空 map 与"字段没进线格式"在值层面不可区分（R3 起值侧再带一层商品维度）。
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        500L,
        laborPerUnit,
        Map.of(GRAIN, 7L),
        // ★ 非空：空 map 与"字段没进线格式"在值层面不可区分（R3 换型后是"生产资料 → 商品表"）。
        Map.of(AssetKind.LAND, Map.of(GRAIN, 1200L)),
        slots,
        new AllocationRule.Split(700, 300));
  }

  /**
   * ★★ T2 的一条生产关系（**非派生夹具**：规则内容与 {@code RegimeRelations} 的四档都不同）。
   *
   * <p>★ 三条规则刻意把三个"线格式上的难点"各占一条：{@code ToActor}（变体一）、{@code ToCohort}（变体二 + {@code CohortKey}
   * 的规范串）、货币档（{@code commodity} 的**空侧**）。
   *
   * <p>★ R3B.2：键 / {@code activity} = 生产单元 id（不再是 {@code IndustryId}）。
   */
  private static ProductionRelation relation(ProductionUnitId activity, ActorRef operator) {
    return new ProductionRelation(
        activity,
        operator,
        null,
        List.of(
            new CompensationRule(
                RuleType.OUTPUT_SHARE,
                new Recipient.ToActor(operator),
                Pool.GROSS_OUTPUT,
                Weight.NONE,
                300,
                0L,
                Optional.of(GRAIN),
                Optional.empty(),
                10),
            new CompensationRule(
                RuleType.FIXED_IN_KIND_RENT,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD)),
                Pool.FIXED_AMOUNT,
                Weight.NONE,
                0,
                5_000L,
                Optional.of(CLOTH),
                Optional.empty(),
                20),
            new CompensationRule(
                RuleType.FIXED_MONEY_WAGE,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT)),
                Pool.FIXED_AMOUNT,
                Weight.NONE,
                0,
                7L,
                Optional.empty(),
                Optional.of(CURRENCY),
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
        Map.of(AssetKind.WORKSHOP, 1L), // ★ 产能锚：规模单位 = 1 座工坊
        Map.of(AssetKind.TOOL, Map.of(GRAIN, 2L)),
        300L,
        1000L,
        Map.of(CLOTH, 5L),
        Map.of(), // ★ WageFirst 多态夹具：未配一次性投入（空 map 是合法形状）
        slots,
        new AllocationRule.WageFirst(4L, residual));
  }

  private static ClassRow classRow(HouseholdId id, CohortKey view, long population) {
    // ★★ H1（K1）：行里没有 goods 了（家户的商品库存住在 actor 切片的 GoodsAccount / 经济侧的会话工作副本里）。
    // ★ S1：键 = 家户稳定身份，视图住在 view；id 与 view 是两件事（本夹具按旧视图造 id）。
    return new ClassRow(
        id,
        view,
        population,
        60000L,
        800,
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  private static DebtContract grainDebt() {
    return new DebtContract(
        D1,
        FARM_HH,
        LANDLORD_HH,
        DebtUnit.commodity(GRAIN),
        GRAIN_TERMS,
        100L,
        0L,
        OptionalLong.empty(),
        OptionalLong.of(3L),
        DebtStatus.NORMAL);
  }

  private static DebtContract moneyDebt() {
    return new DebtContract(
        D2,
        LANDLORD_HH,
        FARM_HH,
        DebtUnit.money(CURRENCY),
        DebtTerms.legacyDefault(),
        700L,
        0L,
        OptionalLong.empty(),
        OptionalLong.of(5L),
        DebtStatus.DEFAULTED);
  }

  private static FlowRow flowRow(HouseholdId id) {
    // ★ R3：income 是**逐商品**的表（两种商品，故"退回标量"的实现过不了这一条）。
    // ★ R4：unmetNeed 也逐商品、并多一个 births（与 deaths 对称）。
    return new FlowRow(
        id,
        Map.of(GRAIN, 200L, CLOTH, 15L),
        Map.of(GRAIN, 120L),
        10L,
        5L,
        0L,
        0L,
        65L,
        Map.of(GRAIN, 7L, CLOTH, 3L),
        3L,
        4L,
        Map.of(CURRENCY, 5L),
        Map.of(DebtUnit.commodity(GRAIN).key(), 7L));
  }
}
