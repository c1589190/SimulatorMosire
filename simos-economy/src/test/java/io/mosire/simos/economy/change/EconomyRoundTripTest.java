package io.mosire.simos.economy.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
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
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 {@code LedgerRoundTripTest}）：反射枚举 {@link EconomyData} 的 record 组件，逐组件造 差异，三条断言
 * ——新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★ **它也是本轮"变异自证"的落点**：把 {@code EconomyChangeSet.between} 里任一分量改成恒 {@code Unchanged}，
 * "该组件参与"那条断言当场红。
 */
class EconomyRoundTripTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final ClassKey KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey OTHER_KEY = new ClassKey(FARM, LANDLORD);
  private static final DebtId D1 = new DebtId("debt-1");
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** ★ R3：第二种商品（"所得逐商品"的那一维在往返里要真的被带上，只有一个商品的夹具挡不住"退回标量"）。 */
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ R2：劳动供给与配额的夹具身份（一格一批人 ⇒ 供给一条、配额一条）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION =
      new LaborAllocationId("alloc-farm-rural:0_0:MALE:1");

  /** ★ 唯一的豁免集合：v1 的 EconomyData 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everyEconomyDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : EconomyData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      EconomyData base = EconomyData.empty();
      EconomyData target = mutate(base, name);
      EconomyChangeSet cs = EconomyChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(EconomyChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  /**
   * ★★ **I3.1「不丢失」的第二个面**（裁定 D9-1）：**显式绑定的、与制度默认值不同的** operator 参与 {@code FieldDelta<Industry>}
   * 的差异与重建 —— 变更集既不能"看不见"它（差异退化成 {@code Unchanged}）， 也不能在重建时把它换成别的值。
   *
   * <p>★★ <b>为什么夹具必须是非默认值</b>：本文件其余夹具的 operator 都是**派生值** （`tenant ⇒ HOUSEHOLD:farm`）——
   * 用派生值的话，差异与重建两边都会得到**同一个**推导值 ⇒ 断言恒真，"operator 是标签"以最隐蔽的形式复活也没人发现。本用例的 target 取
   * `HOUSEHOLD:house-7` （同一个 kind、**不同的 id**），base 取本文件夹具的派生值 `HOUSEHOLD:farm` ⇒ 判别力落在 id 那一维上。
   *
   * <p>★ 判别力（两条变异体各自实测）：把 {@code between} 的差异改成"先把两边的 operator 都归一到 regime 推导值再 diff"（"operator
   * 是标签"在**变更集层**复活）⇒ 本用例的 {@code isInstanceOf(Upsert)} 那句红 （差异退化成 {@code Unchanged}）；把 {@code
   * Industry} 的构造期改成"operator 一律按 regime 重新推导"⇒ 本用例**前置**那句红（target 在构造期就被改写成与 base 相同）。两条都记在 T3
   * 报告的变异自证里。
   */
  @Test
  void anOperatorThatIsNotTheRegimeDefaultSurvivesTheChangeSet() {
    ActorRef household = new ActorRef(ActorKind.HOUSEHOLD, "house-7");
    EconomyData base = EconomyData.empty().withIndustries(Map.of(FARM, industry(FARM, 0L)));
    EconomyData target =
        EconomyData.empty().withIndustries(Map.of(FARM, industry(FARM, 0L, household)));

    // ★ 前置：夹具真的是"非默认"（`tenant` 的推导值是 HOUSEHOLD:farm，本用例给的是 HOUSEHOLD:house-7）——
    //   若两者相同，本用例的每条断言都能被"重新推导"这条规则满足 ⇒ 白写。
    assertThat(industry(FARM, 0L, household).operator())
        .as("夹具必须是**非默认**值（`tenant` 的推导值是 HOUSEHOLD:farm）")
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId("tenant"), FARM));

    EconomyChangeSet cs = EconomyChangeSet.between(base, target);

    // ★ 形状按 `FieldDelta.diff` 的**规则**推：同一个 key、值不同 ⇒ 进 upserts；base 里没有多出来的 key
    //   ⇒ removals 为空 ⇒ 变体是 **Upsert**（不是 Unchanged、也不是 Upsert + Remove 的 Patch）。
    assertThat(cs.industries())
        .as("operator 变了 ⇒ 差异不许退化成 Unchanged")
        .isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.industries().changed()).as("差异必须看得见 operator").isTrue();
    @SuppressWarnings("unchecked")
    FieldDelta.Upsert<Industry> upserts = (FieldDelta.Upsert<Industry>) cs.industries();
    assertThat(upserts.entries().get(FARM.value()).operator())
        .as("★ 差异里带的就是 operator 那一维的**新值**（不是旧值、也不是推导值）")
        .isEqualTo(household);
    assertThat(EconomyChangeSet.apply(cs, base)).as("重建后的整份状态 == target").isEqualTo(target);
    assertThat(EconomyChangeSet.apply(cs, base).industries().get(FARM).operator())
        .as("★ 重建后读到的就是那一个显式主体")
        .isEqualTo(household)
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId("tenant"), FARM));
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("EconomyData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyEightComponents() {
    assertThat(EconomyChangeSet.class.getRecordComponents()).hasSize(8);
    assertThat(componentNames(EconomyChangeSet.class))
        .as("变更集的每个组件都必须在 EconomyData 里有同名的 record 组件")
        .isSubsetOf(componentNames(EconomyData.class));
    assertThat(componentNames(EconomyData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(EconomyChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsEconomy() {
    EconomySnapshot snapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            EconomyData.empty());
    assertThat(snapshot.namespace()).isEqualTo("economy");
  }

  private static EconomyData mutate(EconomyData base, String name) {
    return switch (name) {
      case "meta" -> base.withMeta(Optional.of(meta()));
      case "industries" -> base.withIndustries(Map.of(FARM, industry(FARM, 0L)));
      case "classes" ->
          base.withIndustries(Map.of(FARM, industry(FARM, 0L)))
              .withClasses(Map.of(KEY, classRow(KEY)));
      case "debts" ->
          // ★ 债务的两端必须在 classes 里（v2 spec §八.2）⇒ 这个变异体必须**自带支撑的 classes**：
          //   从 EconomyData.empty() 只改 debts 的旧形态在新不变量下无法自洽（本用例只断言
          //   "目标组件进了变更集 + 往返相等"，多带支撑组件不破坏任何断言）。
          base.withIndustries(Map.of(FARM, industry(FARM, 0L)))
              .withClasses(Map.of(KEY, classRow(KEY), OTHER_KEY, classRow(OTHER_KEY)))
              .withDebts(Map.of(D1, debt()));
      case "flows" ->
          base.withIndustries(Map.of(FARM, industry(FARM, 0L))).withFlows(Map.of(KEY, flowRow()));
      // ★ R2 的两个新组件：都自带"支撑记录"（供给挂批次、配额挂产业 —— 两条都是构造期守卫判死的对应关系）。
      case "laborSupply" -> base.withLaborSupply(Map.of(LOT, laborSupply()));
      case "allocations" ->
          base.withIndustries(Map.of(FARM, industry(FARM, 0L)))
              .withLaborSupply(Map.of(LOT, laborSupply()))
              .withAllocations(Map.of(ALLOCATION, laborAllocation()));
      // ★ T2 的第 8 个组件：**自带支撑的产业**（跨表守卫要求 relations[key].operator() 等于
      //   industries[key].operator()，且键 == 值内 activity）。
      //   ★ 夹具是**非派生**值：operator 取 `HOUSEHOLD:house-7`（本文件夹具的 regime 是 `tenant`，
      //   推导值是 `HOUSEHOLD:farm`）⇒ "把 relations 整个按 regime 重新推导"这种坏实现会读到别的值 ⇒ 红。
      case "relations" ->
          base.withIndustries(Map.of(FARM, industry(FARM, 0L, NON_DEFAULT_OPERATOR)))
              .withRelations(Map.of(FARM, relation(FARM, NON_DEFAULT_OPERATOR)));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(EconomyChangeSet cs, String name) {
    return switch (name) {
      case "meta" -> cs.meta().changed();
      case "industries" -> cs.industries().changed();
      case "classes" -> cs.classes().changed();
      case "debts" -> cs.debts().changed();
      case "flows" -> cs.flows().changed();
      case "laborSupply" -> cs.laborSupply().changed();
      case "allocations" -> cs.allocations().changed();
      case "relations" -> cs.relations().changed();
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

  // ── 夹具（本类自足；codec 测试自带一套，照 ledger 两测试各自展开的先例） ──

  static EconomyMeta meta() {
    return new EconomyMeta("m1", 0L, OptionalLong.empty(), "rules-r1", Optional.empty());
  }

  /**
   * 一个合规矩的产业：两个槽位各持**劳动投入率上限**（R1.1 起不再是"人口占比"，故**不必合计 1000‰**）。 上限须 ≥ 行里的 {@code
   * participationPerMille}（此处 800）= v2 spec §八.1 的不变量。
   */
  static Industry industry(IndustryId id, long progress) {
    // ★ 通用夹具的 operator = **派生**（`tenant` ⇒ `HOUSEHOLD:<本夹具的 id 参数>`）：默认值只有一处拼写点。
    return industry(id, progress, RegimeOperators.defaultOperator(new RegimeId("tenant"), id));
  }

  /**
   * 同 {@link #industry(IndustryId, long)}，但**显式给定经营主体**。
   *
   * <p>★ 加这个重载是 D9 的纪律所要求的：守门用例必须能塞进**非默认**值 —— 用派生值的话， "重建点漏传 ⇒ 被重新推导"这种变异体会被推导出的**同一个值**掩盖（T2 报告
   * §5.3）。
   */
  static Industry industry(IndustryId id, long progress, ActorRef operator) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 900));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        progress,
        // ★ R3（V7）：产能那一路非空且为正（"单位规模"的锚）；这一行的规模单位 = "1 头耕牛"。
        Map.of(AssetKind.CATTLE, 1L),
        // ★ 必须非空：空 map 与"字段没进变更集"在值层面不可区分（R3 起值侧再带一层商品维度）。
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        500L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        slots,
        new AllocationRule.Split(700, 300),
        0L,
        Map.of(GRAIN, 3L),
        operator);
  }

  static ClassKey otherKey() {
    return OTHER_KEY;
  }

  /**
   * ★ 无债务的阶层行：**债务引用完整性**（v2 spec §八.2）要求行内 {@code debts} 的每个 id 都在债务表里， 故"只变异 classes
   * 一个组件"的用例只能用不引用债务的行。
   */
  static ClassRow classRow(ClassKey key) {
    return new ClassRow(
        key,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  static Debt debt() {
    return new Debt(D1, KEY, OTHER_KEY, Optional.of(GRAIN), 100L, 20, 3L, false);
  }

  static FlowRow flowRow() {
    // ★ R3：income 由标量改成**逐商品**的表（与 consumed 对称）——这里刻意给两种商品，往返要真的带上它。
    // ★ R4：unmetNeed 也变成**逐商品**的表、并多一个与 deaths 对称的 births ⇒ 三种商品维度的账都要带上。
    return new FlowRow(
        KEY,
        Map.of(GRAIN, 200L, CLOTH, 11L),
        Map.of(GRAIN, 120L),
        10L,
        5L,
        0L,
        0L,
        65L,
        Map.of(GRAIN, 7L, CLOTH, 2L),
        3L,
        4L);
  }

  /** ★ R2 的配额夹具：批次 {@link #LOT} 把 60,000 千分劳动供给产业 {@code FARM}（actor id = 产业 id）。 */
  static LaborAllocation laborAllocation() {
    return new LaborAllocation(
        ALLOCATION, LOT, new ActorRef(ActorKind.ESTATE, FARM.value()), "farm", 60_000L, 1L);
  }

  /** ★ R2 的供给夹具：毛额 60,000 ⇒ 配额恰好用满（{@code Σ allocated ≤ available} 取等号）。 */
  static LaborSupply laborSupply() {
    return new LaborSupply(LOT, 1L, 60_000L, 0L, 0L);
  }

  /**
   * ★★ T2 的 operator 夹具：**非派生值**（`HOUSEHOLD:house-7`；本文件 `industry` 的 regime 是 `tenant`， 推导值 =
   * `HOUSEHOLD:farm`）—— 见 {@link #mutate} 的 `relations` 分支。
   */
  static final ActorRef NON_DEFAULT_OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "house-7");

  /**
   * ★★ T2 的关系夹具：**逐值非派生**（两条规则把 E4 的两个要点各钉一条：地租**显式**给 {@code (hex, landlord)} cohort、自留由 {@code
   * residualOwner} 表达而不是写一条 {@code SELF_RETENTION}）。
   */
  static ProductionRelation relation(IndustryId id, ActorRef operator) {
    return new ProductionRelation(
        id,
        operator,
        List.of(
            new CompensationRule(
                RuleType.OUTPUT_SHARE,
                new Recipient.ToCohort(new CohortKey(new HexCoord(0, 0), LANDLORD)),
                Basis.GROSS_OUTPUT,
                300,
                0L,
                Optional.of(GRAIN),
                10),
            new CompensationRule(
                RuleType.FIXED_IN_KIND_PER_LABOR,
                new Recipient.ToCohort(new CohortKey(new HexCoord(0, 0), PEASANT)),
                Basis.LABOR_AMOUNT,
                0,
                144L,
                Optional.of(GRAIN),
                20)),
        operator);
  }
}
