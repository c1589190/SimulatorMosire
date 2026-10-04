package io.mosire.simos.economy.change;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.LossBearer;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.market.TradeRoute;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.classfirst.ClassFirstMeta;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
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
 *
 * <p>★★ <b>S1/R3B.2/R4/E1–E6 的 API 漂移已在这里就位</b>：{@code classes}/{@code flows} 的键 = {@link
 * HouseholdId}（旧视图用 {@code HouseholdIds.ofLegacy}）；{@code relations} 挂 {@link ProductionUnitId}；
 * operator / 周期进度住在 {@link ProductionUnit} 上（不是 {@code Industry} 的旧档兼容位）；{@code EconomyData} 是 29
 * 组件记录（E1–E6 追加组件 + R1 的 classFirst 全在 {@link EconomyChangeSet} 里逐一对齐）。
 */
class EconomyRoundTripTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CohortKey KEY =
      new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT);
  private static final CohortKey OTHER_KEY =
      new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD);

  /** ★ S1：键 = 稳定家户身份；{@code KEY} 只是它的旧视图。 */
  private static final HouseholdId KEY_HH = HouseholdIds.ofLegacy(KEY);

  private static final HouseholdId OTHER_HH = HouseholdIds.ofLegacy(OTHER_KEY);
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** ★ R3：第二种商品（"所得逐商品"的那一维在往返里要真的被带上，只有一个商品的夹具挡不住"退回标量"）。 */
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ R2：劳动供给与配额的夹具身份（一格一批人 ⇒ 供给一条、配额一条）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  /** ★ H4：市场夹具的计价货币（每格恰一种 —— 裁定 M1-A）。 */
  private static final CurrencyId SILVER = new CurrencyId("silver");

  private static final LaborAllocationId ALLOCATION =
      new LaborAllocationId("alloc-farm-rural:0_0:MALE:1");

  /** ★ M2.4：在途批次的夹具身份（键 = 批次 id；值里带路线/商品/发运到达日/逐票分配）。 */
  private static final ShipmentId SHIPMENT = new ShipmentId("shipment-1");

  // ── E1–E6 追加组件的夹具身份（RoundTrip 必须为每个组件都能造出 target） ───────────────────

  /** ★ E4a：连续粮债的稳定身份（key == 值内 id，且 id 由四元组派生）。 */
  private static final DebtContractId D1 =
      DebtContractId.idOf(KEY_HH, OTHER_HH, DebtUnit.commodity(GRAIN), DebtTerms.legacyDefault());

  private static final ProductionModeId MODE = new ProductionModeId("mode-1");
  private static final ProductionModeId MODE_TARGET = new ProductionModeId("mode-2");
  private static final ClassStructureId STRUCTURE = new ClassStructureId("structure-1");
  private static final ClassPositionId POSITION = new ClassPositionId("position-1");
  private static final ProductionOrganizationId ORGANIZATION =
      new ProductionOrganizationId("organization-1");
  private static final ProductionOrganizationId MERCHANT_ORGANIZATION =
      new ProductionOrganizationId("organization-merchant-1");
  private static final AssetRuleId ASSET_RULE = AssetRuleId.idOf(MODE, AssetKind.CATTLE);
  private static final GovernmentId GOVERNMENT = new GovernmentId("government-1");
  private static final MoneyIssuanceId ISSUANCE = new MoneyIssuanceId("issuance-1");
  private static final PledgeId PLEDGE = new PledgeId("pledge-1");
  private static final CrisisSignalId CRISIS = CrisisSignalId.idOf(KEY.hex(), "FOOD");
  private static final ModeTransitionId TRANSITION =
      ModeTransitionId.idOf(ORGANIZATION, MODE_TARGET, 10L);
  private static final ClassShareId CLASS_SHARE = ClassShareId.idOf(TRANSITION, KEY_HH, POSITION);

  /**
   * ★★ T2/D9 的 operator 夹具：**非派生值**（`HOUSEHOLD:house-7`；本文件 `industry` 的 regime 是 `tenant`， 推导值 =
   * `HOUSEHOLD:farm`）。R3B.2 起它是 {@link ProductionUnit} 的字段，不再挂在 {@code Industry} 上。
   */
  static final ActorRef NON_DEFAULT_OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "house-7");

  /** ★ R3B.2：unit id 的唯一拼写点；本文件三个组件（units / relations / allocations）共用同一条。 */
  private static final ProductionUnitId FARM_UNIT =
      ProductionUnitId.idOf(FARM, NON_DEFAULT_OPERATOR);

  /** ★ S1：memberships 夹具的键（键 == 值内 id；count 必须对上行人口）。 */
  private static final MembershipId KEY_MEMBERSHIP = Membership.idOf(LOT, KEY_HH);

  /** ★ R3B.1：assetShares 夹具的键（键 == 值内 id；industry 必须存在）。 */
  private static final AssetShareId FARM_SHARE =
      AssetShare.idOf(
          FARM,
          AssetKind.CATTLE,
          NON_DEFAULT_OPERATOR,
          NON_DEFAULT_OPERATOR,
          AssetShare.RightKind.OWNED,
          0L);

  /** ★ R4-E2：后两个组件的夹具键。 */
  private static final DemandId DEMAND = new DemandId("demand-1");

  private static final CandidateId CANDIDATE = new CandidateId("candidate-1");

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
   * ★★ **I3.1「不丢失」的第二个面**（裁定 D9-1）：**显式绑定的、与制度默认值不同的** operator 参与 {@code
   * FieldDelta<ProductionUnit>} 的差异与重建 —— 变更集既不能"看不见"它（差异退化成 {@code Unchanged}）， 也不能在重建时把它换成别的值。
   *
   * <p>★★ <b>为什么夹具必须是非默认值</b>：{@code tenant} 的推导值是 {@code HOUSEHOLD:farm}；本用例在同一 unit id 上把
   * operator 换成 {@code HOUSEHOLD:house-7}（同一个 kind、**不同的 id**）⇒ 判别力落在 id
   * 那一维上。用派生值的话，差异与重建两边都会得到**同一个** 推导值 ⇒ 断言恒真，"operator 是标签"以最隐蔽的形式复活也没人发现。
   *
   * <p>★ R3B.2 漂移：operator 的真值已从 {@code Industry} 移进 {@link ProductionUnit} ⇒ 本用例把同一条判别力钉在 {@code
   * units} 组件上（旧用例的 {@code Industry.operator} 现在只是旧档兼容位，新代码不读）。
   *
   * <p>★ 判别力（两条变异体各自实测）：把 {@code EconomyChangeSet.between} 的差异改成"先把两边的 operator 都归一到 regime 推导值再
   * diff"（"operator 是标签"在**变更集层**复活）⇒ 本用例的 {@code isInstanceOf(Upsert)} 那句红 （差异退化成 {@code
   * Unchanged}）；把重建点改成"operator 一律按 regime 重新推导"⇒ 本用例的期望值那句红。
   */
  @Test
  void anOperatorThatIsNotTheRegimeDefaultSurvivesTheChangeSet() {
    ActorRef derived = RegimeOperators.defaultOperator(new RegimeId("tenant"), FARM);
    ActorRef explicit = NON_DEFAULT_OPERATOR;
    // ★ 身份与 operator 分离：同一个 unit id 上改 operator（ProductionUnitId 是 opaque 值，工厂只是新 id 的拼写点）。
    ProductionUnitId unitId = ProductionUnitId.idOf(FARM, derived);
    EconomyData base =
        EconomyData.empty()
            .withIndustries(Map.of(FARM, industry(FARM)))
            .withUnits(Map.of(unitId, unit(unitId, derived)));
    EconomyData target =
        EconomyData.empty()
            .withIndustries(Map.of(FARM, industry(FARM)))
            .withUnits(Map.of(unitId, unit(unitId, explicit)));

    // ★ 前置：夹具真的是"非默认"（`tenant` 的推导值是 HOUSEHOLD:farm，本用例给的是 HOUSEHOLD:house-7）——
    //   若两者相同，本用例的每条断言都能被"重新推导"这条规则满足 ⇒ 白写。
    assertThat(explicit).as("夹具必须是**非默认**值（`tenant` 的推导值是 HOUSEHOLD:farm）").isNotEqualTo(derived);

    EconomyChangeSet cs = EconomyChangeSet.between(base, target);

    // ★ 形状按 `FieldDelta.diff` 的**规则**推：同一个 key、值不同 ⇒ 进 upserts；base 里没有多出来的 key
    //   ⇒ removals 为空 ⇒ 变体是 **Upsert**（不是 Unchanged、也不是 Upsert + Remove 的 Patch）。
    assertThat(cs.units())
        .as("operator 变了 ⇒ 差异不许退化成 Unchanged")
        .isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.units().changed()).as("差异必须看得见 operator").isTrue();
    @SuppressWarnings("unchecked")
    FieldDelta.Upsert<ProductionUnit> upserts = (FieldDelta.Upsert<ProductionUnit>) cs.units();
    assertThat(upserts.entries().get(unitId.value()).operator())
        .as("★ 差异里带的就是 operator 那一维的**新值**（不是旧值、也不是推导值）")
        .isEqualTo(explicit);
    assertThat(EconomyChangeSet.apply(cs, base)).as("重建后的整份状态 == target").isEqualTo(target);
    assertThat(EconomyChangeSet.apply(cs, base).units().get(unitId).operator())
        .as("★ 重建后读到的就是那一个显式主体")
        .isEqualTo(explicit)
        .isNotEqualTo(derived);
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("EconomyData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  /**
   * ★★ <b>组件计数（E6 = 29 + R1 classFirst = 30）</b>：{@code meta} / {@code industries} / {@code
   * classes} / {@code debtContracts} / {@code flows} / {@code laborSupply} / {@code allocations} /
   * {@code relations} / {@code markets} / {@code shipments} / {@code memberships} / {@code
   * assetShares} / {@code operatorConditions} / {@code units} / {@code demands} / {@code
   * candidates} / E1 的四个 / E2 的两个 / E3 的两个 / E4 的 {@code pledges} / E5 的两个 / E6 的两个 / R1 的 {@code
   * classFirst}。
   *
   * <p>★ 这个名字里的数字**故意写死**（R4 16 → E3 24 → E4 25 → E5 27 → E6 29 → R1 30）：它就是"又加了一个状态组件"这件事
   * 在编译/测试面上的**唯一提醒**——新增组件却只改了 {@code EconomyData} 而没进变更集时，本用例当场红。P10.1 追加 {@code merchantFirms} 后为 31 组件。
   */
  @Test
  void changeSetHasExactlyThirtyOneComponents() {
    assertThat(EconomyChangeSet.class.getRecordComponents()).hasSize(31);
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

  /** ★ P10.1：非空的最小 merchantFirms 夹具（键 == 值内 organizationId；组织表为空时不需要组织支撑）。 */
  private static MerchantFirm merchantFirm() {
    return new MerchantFirm(
        MERCHANT_ORGANIZATION,
        MerchantPolicy.MerchantTier.PORTER,
        new HexCoord(0, 0),
        true,
        100L,
        0L,
        2L,
        0L,
        0L,
        0L,
        0L);
  }

  /** ★ R1：非空的最小 classFirst 夹具（一个池 + 一个 tick 的 meta，足以让"组件参与差异"有判别力）。 */
  private static ClassFirstState classFirst() {
    ClassPoolId poolId = ClassPoolId.idOf("tenancy_agriculture", "LABORER");
    ClassPool pool = new ClassPool("tenancy_agriculture", "LABORER");
    ClassFirstMeta meta = new ClassFirstMeta(1L, 0L, 0L, 0L, 0L, null, null, null, 0L);
    return new ClassFirstState(
        Map.of(),
        Map.of(poolId, pool),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        meta);
  }

  private static EconomyData mutate(EconomyData base, String name) {
    return switch (name) {
      case "meta" -> base.withMeta(Optional.of(meta()));
      case "industries" -> base.withIndustries(Map.of(FARM, industry(FARM)));
      case "classes" ->
          // ★ S1：键 = HouseholdId（视图住在行内）。先落 meta 标记（见 meta()），避免构造期自动跑旧档迁移。
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withClasses(Map.of(KEY_HH, classRow(KEY_HH, KEY)));
      case "debtContracts" ->
          // ★ E4a：债务的两端必须在 classes 里（v2 spec §八.2）⇒ 这个变异体必须**自带支撑的 classes**：
          //   从 EconomyData.empty() 只改债务表的旧形态在新不变量下无法自洽（本用例只断言
          //   "目标组件进了变更集 + 往返相等"，多带支撑组件不破坏任何断言）。
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withClasses(
                  Map.of(KEY_HH, classRow(KEY_HH, KEY), OTHER_HH, classRow(OTHER_HH, OTHER_KEY)))
              .withDebtContracts(Map.of(D1, debt()));
      case "flows" ->
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withClasses(Map.of(KEY_HH, classRow(KEY_HH, KEY)))
              .withFlows(Map.of(KEY_HH, flowRow(KEY_HH)));
      // ★ R2 的两个新组件：都自带"支撑记录"（供给挂批次、配额挂产业 —— 两条都是构造期守卫判死的对应关系）。
      case "laborSupply" -> base.withLaborSupply(Map.of(LOT, laborSupply()));
      case "allocations" ->
          // ★ R3B.2：配额自带 unit 支撑 —— activity 指到 unit、actor == unit.operator（同一件事不许两处拼写），
          //   家户必须在 classes 里，供给必须同期（这里 60_000 恰好用满）。
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withUnits(Map.of(FARM_UNIT, unit(FARM_UNIT, NON_DEFAULT_OPERATOR)))
              .withClasses(Map.of(KEY_HH, classRow(KEY_HH, KEY)))
              .withLaborSupply(Map.of(LOT, laborSupply()))
              .withAllocations(Map.of(ALLOCATION, laborAllocation()));
      // ★ T2 的第 8 个组件：**自带同 operator 的 unit**（跨表守卫要求 relations 键 == 值内 activity == 已存在的
      //   unit id，且 relation.operator() == unit.operator()）。
      //   ★ 夹具是**非派生**值：operator 取 `HOUSEHOLD:house-7`（本文件夹具的 regime 是 `tenant`，
      //   推导值是 `HOUSEHOLD:farm`）⇒ "把 relations 整个按 regime 重新推导"这种坏实现会读到别的值 ⇒ 红。
      case "relations" ->
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withUnits(Map.of(FARM_UNIT, unit(FARM_UNIT, NON_DEFAULT_OPERATOR)))
              .withRelations(Map.of(FARM_UNIT, relation(FARM_UNIT, NON_DEFAULT_OPERATOR)));
      // ★ H4 的第 9 个组件：**自带支撑的格**（市场的键 = 格；本夹具的格就是 {@link #KEY} 所在那一格）。
      //   ★ 价表**非空**：空价表与"字段没进变更集"在值层面不可区分（同上面 outputPerUnit 那条理由），
      //     而"这一格什么价都没挂"恰恰是 H4 最想让人看得见的一种状态。
      case "markets" -> base.withMarkets(Map.of(KEY.hex(), market()));
      // ★ M2.4 的第 10 个组件：**自带支撑的票**（在途批次没有跨表守卫，但仍要有真实的路线与至少一票，
      //   否则构造期就会拦下"在途必须能追到票"）。
      case "shipments" -> base.withShipments(Map.of(SHIPMENT, shipment()));
      // ★ S1 的第 11 个组件：**自带支撑的家户行**（membership 的家户必须存在，且 Σcount == Σ行人口）。
      case "memberships" ->
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withClasses(Map.of(KEY_HH, classRow(KEY_HH, KEY)))
              .withMemberships(Map.of(KEY_MEMBERSHIP, membership()));
      // ★ R3B.1 的第 12 个组件：**自带支撑的产业**（份额指名的 industry 必须存在；键 == 值内 id）。
      case "assetShares" ->
          base.withIndustries(Map.of(FARM, industry(FARM)))
              .withAssetShares(Map.of(FARM_SHARE, share()));
      // ★ S3.2 的第 13 个组件：**自带支撑的 unit**（键 = unit id；值内 industry 必须等于 unit.industry）。
      case "operatorConditions" ->
          base.withMeta(Optional.of(meta()))
              .withIndustries(Map.of(FARM, industry(FARM)))
              .withUnits(Map.of(FARM_UNIT, unit(FARM_UNIT, NON_DEFAULT_OPERATOR)))
              .withOperatorConditions(Map.of(FARM_UNIT, operatorCondition()));
      // ★ R3B.2 的第 14 个组件：**自带支撑的产业**（unit 指名的技术模板必须存在；progressDays ≤ cycleDays）。
      case "units" ->
          base.withIndustries(Map.of(FARM, industry(FARM)))
              .withUnits(Map.of(FARM_UNIT, unit(FARM_UNIT, NON_DEFAULT_OPERATOR)));
      // ★ R4-E2 的第 15 个组件：HEX 范围的需求自带一张**登记了该格的市场**（hexRegistered 的口径）。
      case "demands" ->
          base.withMarkets(Map.of(KEY.hex(), market())).withDemands(Map.of(DEMAND, demand()));
      // ★ R4-E2 的第 16 个组件：候选预设自带已登记的 regime 与自洽的 output/outputPerUnit。
      case "candidates" -> base.withCandidates(Map.of(CANDIDATE, candidate()));
      // ★★ E1–E6 的追加组件：每个都自带一个最小自洽实例（引用完整性按"对侧是否提供"分段，
      //   故单组件 target 合法；moneyIssuances 是唯一需要连带 government 支撑的一组）。
      case "modes" -> base.withModes(Map.of(MODE, productionMode()));
      case "classStructures" -> base.withClassStructures(Map.of(STRUCTURE, classStructure()));
      case "classPositions" -> base.withClassPositions(Map.of(POSITION, classPosition()));
      case "classStandings" -> base.withClassStandings(Map.of(KEY_HH, classStanding()));
      case "productionOrganizations" ->
          base.withProductionOrganizations(Map.of(ORGANIZATION, productionOrganization()));
      case "assetRules" -> base.withAssetRules(Map.of(ASSET_RULE, assetRule()));
      case "governments" -> base.withGovernments(Map.of(GOVERNMENT, government()));
      case "moneyIssuances" ->
          base.withGovernments(Map.of(GOVERNMENT, government()))
              .withMoneyIssuances(Map.of(ISSUANCE, moneyIssuance()));
      case "pledges" -> base.withPledges(Map.of(PLEDGE, pledge()));
      case "liquidationPolicies" ->
          base.withLiquidationPolicies(Map.of(ASSET_RULE, liquidationPolicy()));
      case "crisisSignals" -> base.withCrisisSignals(Map.of(CRISIS, crisisSignal()));
      case "modeTransitions" -> base.withModeTransitions(Map.of(TRANSITION, modeTransition()));
      case "classShares" -> base.withClassShares(Map.of(CLASS_SHARE, classShare()));
      case "classFirst" -> base.withClassFirst(classFirst());
      case "merchantFirms" ->
          base.withMerchantFirms(Map.of(MERCHANT_ORGANIZATION, merchantFirm()));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(EconomyChangeSet cs, String name) {
    return switch (name) {
      case "meta" -> cs.meta().changed();
      case "industries" -> cs.industries().changed();
      case "classes" -> cs.classes().changed();
      case "debtContracts" -> cs.debtContracts().changed();
      case "flows" -> cs.flows().changed();
      case "laborSupply" -> cs.laborSupply().changed();
      case "allocations" -> cs.allocations().changed();
      case "relations" -> cs.relations().changed();
      case "markets" -> cs.markets().changed();
      case "shipments" -> cs.shipments().changed();
      case "memberships" -> cs.memberships().changed();
      case "assetShares" -> cs.assetShares().changed();
      case "operatorConditions" -> cs.operatorConditions().changed();
      case "units" -> cs.units().changed();
      case "demands" -> cs.demands().changed();
      case "candidates" -> cs.candidates().changed();
      case "modes" -> cs.modes().changed();
      case "classStructures" -> cs.classStructures().changed();
      case "classPositions" -> cs.classPositions().changed();
      case "classStandings" -> cs.classStandings().changed();
      case "productionOrganizations" -> cs.productionOrganizations().changed();
      case "assetRules" -> cs.assetRules().changed();
      case "governments" -> cs.governments().changed();
      case "moneyIssuances" -> cs.moneyIssuances().changed();
      case "pledges" -> cs.pledges().changed();
      case "liquidationPolicies" -> cs.liquidationPolicies().changed();
      case "crisisSignals" -> cs.crisisSignals().changed();
      case "modeTransitions" -> cs.modeTransitions().changed();
      case "classShares" -> cs.classShares().changed();
      case "classFirst" -> cs.classFirst().changed();
      case "merchantFirms" -> cs.merchantFirms().changed();
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

  /**
   * ★ <b>把 rulesVersion 标成"已迁移"</b>（{@code pre-modern-v1}）：本文件从 {@code EconomyData.empty()}
   * 逐组件搭新形状， 若不标，构造期会对"classes 非空但 memberships 为空"的中间态自动跑 {@code
   * LegacyHouseholdMigration}（那是给旧档的兜底， 不是本用例要量的东西）。标了之后迁移的第一条触发判据为假，逐组件的目标就是"只改了那一个组件"。
   */
  static EconomyMeta meta() {
    return new EconomyMeta(
        "m1", 0L, OptionalLong.empty(), EconomyMeta.RULES_VERSION_PRE_MODERN_V1, Optional.empty());
  }

  /**
   * 一个合规矩的产业（R3B.2 起是**纯技术模板**的 12 参构造；operator / 周期进度住在 {@link ProductionUnit}
   * 上）：两个槽位各持**劳动投入率上限** （R1.1 起不再是"人口占比"，故**不必合计 1000‰**）。{@code capacityPerUnit}
   * 非空且为正（"单位规模"的锚），各表的值侧都带商品维度。
   */
  static Industry industry(IndustryId id) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 900));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        // ★ R3（V7）：产能那一路非空且为正（"单位规模"的锚）；这一行的规模单位 = "1 头耕牛"。
        Map.of(AssetKind.CATTLE, 1L),
        // ★ 必须非空：空 map 与"字段没进变更集"在值层面不可区分（R3 起值侧再带一层商品维度）。
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        500L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        slots,
        new AllocationRule.Split(700, 300));
  }

  /** 一个合规矩的生产单元：身份 / operator 由调用方给，modeKey = 产业 id，周期状态取中性值。 */
  static ProductionUnit unit(ProductionUnitId id, ActorRef operator) {
    return new ProductionUnit(id, FARM, operator, FARM.value(), 0L, 0L, Map.of());
  }

  static CohortKey otherKey() {
    return OTHER_KEY;
  }

  /**
   * ★ 无债务的阶层行：**债务引用完整性**（v2 spec §八.2）要求行内 {@code debts} 的每个 id 都在债务表里， 故"只变异 classes
   * 一个组件"的用例只能用不引用债务的行。
   *
   * <p>★ S1：行 = {@code (id, view, …)} 两件事（键 = 稳定家户身份，view = 当前格/居住/阶层）。
   */
  static ClassRow classRow(HouseholdId id, CohortKey view) {
    // ★★ H1（K1）：行里没有 goods 了（家户的商品库存住在 actor 切片的 GoodsAccount / 经济侧的会话工作副本里）。
    return new ClassRow(
        id, view, 120L, 60000L, 800, 50L, List.of(), Map.of(GRAIN, 40L), Map.of(GRAIN, 30L), 0L);
  }

  static DebtContract debt() {
    return new DebtContract(
        D1,
        KEY_HH,
        OTHER_HH,
        DebtUnit.commodity(GRAIN),
        DebtTerms.legacyDefault(),
        100L,
        0L,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  static FlowRow flowRow(HouseholdId id) {
    // ★ R3：income 由标量改成**逐商品**的表（与 consumed 对称）——这里刻意给两种商品，往返要真的带上它。
    // ★ R4：unmetNeed 也变成**逐商品**的表、并多一个与 deaths 对称的 births ⇒ 三种商品维度的账都要带上。
    return new FlowRow(
        id,
        Map.of(GRAIN, 200L, CLOTH, 11L),
        Map.of(GRAIN, 120L),
        10L,
        5L,
        0L,
        0L,
        65L,
        Map.of(GRAIN, 7L, CLOTH, 2L),
        3L,
        4L,
        Map.of(SILVER, 9L),
        Map.of(DebtUnit.commodity(GRAIN).key(), 5L));
  }

  /**
   * ★ R2 的配额夹具：批次 {@link #LOT} 把 60,000 千分劳动供给 {@link #FARM_UNIT}（activity = unit id、actor =
   * unit.operator，R3B.2 的一致性两处逐字相同）。
   */
  static LaborAllocation laborAllocation() {
    return new LaborAllocation(
        ALLOCATION, LOT, KEY_HH, NON_DEFAULT_OPERATOR, FARM_UNIT.value(), 60_000L, 1L);
  }

  /** ★ R2 的供给夹具：毛额 60,000 ⇒ 配额恰好用满（{@code Σ allocated ≤ available} 取等号）。 */
  static LaborSupply laborSupply() {
    return new LaborSupply(LOT, 1L, 60_000L, 0L, 0L);
  }

  /** ★ S1 的成员份额夹具：count 120 == 行人口 120（Σ 守恒）。 */
  static Membership membership() {
    return new Membership(KEY_MEMBERSHIP, LOT, KEY_HH, 120L);
  }

  /** ★ R3B.1 的资产份额夹具：键 == 值内 id、industry 存在；键与值都由 {@link #FARM_SHARE} 一处拼写。 */
  static AssetShare share() {
    return new AssetShare(
        FARM_SHARE,
        FARM,
        AssetKind.CATTLE,
        NON_DEFAULT_OPERATOR,
        NON_DEFAULT_OPERATOR,
        1L,
        AssetShare.RightKind.OWNED);
  }

  /** ★ S3.2 的经营者状态夹具：键 = {@link #FARM_UNIT}，值内 industry = {@link #FARM}（跨表守卫要求两者一致）。 */
  static OperatorCondition operatorCondition() {
    return new OperatorCondition(
        FARM,
        OperatorCondition.IndustryStatus.ACTIVE,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        "",
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L);
  }

  /**
   * ★★ <b>H4 的市场夹具</b>（{@code EconomyData} 的第 9 个组件）：一格、单一计价货币 {@link #SILVER}、一张**非空**价表。
   *
   * <p>★ <b>为什么价表非空</b>：{@code Map.of()} 与"这个字段压根没进变更集"在值层面不可区分（同 {@code outputPerUnit} 那条理由）——
   * 空价表会让"把 markets 分量写成恒 Unchanged"这种坏实现**假绿**。
   *
   * <p>★ <b>为什么不逐格建不同的价</b>：本用例量的是"变更集看得见市场表"，不是价格业务；逐格差异留给价格那一轮。
   */
  static Market market() {
    return new Market(SILVER, Map.of(GRAIN, 3L, CLOTH, 5L));
  }

  /**
   * ★★ <b>M2.4 的在途夹具</b>（{@code EconomyData} 的第 10 个组件 {@code shipments}）：一条 1 天的路线、一票买方承担损耗的 grain
   * 在途。★ 数量与票面之和取非 0 且相等 —— 构造期就判"批次量 == 各票之和（同一件事不许两处拼写）"。
   */
  static ShipmentBatch shipment() {
    TradeRoute route = new TradeRoute(new HexCoord(0, 0), new HexCoord(1, 0), 1_000L, 1L, 1L, 5);
    return new ShipmentBatch(
        route,
        GRAIN,
        10L,
        11L,
        500L,
        List.of(
            new ShipmentAllocation(
                new ActorRef(ActorKind.ESTATE, FARM.value()),
                new ActorRef(ActorKind.HOUSEHOLD, "house-7"),
                new HexCoord(1, 0),
                500L,
                LossBearer.BUYER)));
  }

  /** ★★ <b>R4-E2 的需求夹具</b>（第 15 个组件）：HEX 范围 ⇒ 自带一张登记了该格的市场（{@code hexRegistered} 接受市场键）。 */
  static DemandEntry demand() {
    return new DemandEntry(
        DEMAND,
        DemandEntry.DemandScope.HEX,
        Optional.empty(),
        Optional.of(KEY.hex()),
        GRAIN,
        DemandEntry.DemandKind.RECURRING,
        DemandEntry.DemandUnit.TOTAL,
        100L,
        0L,
        -1L,
        0,
        "fixture");
  }

  /** ★★ <b>R4-E2 的候选预设夹具</b>（第 16 个组件）：regime 已登记（{@code tenant}）且 output ∈ outputPerUnit。 */
  static ProductionCandidate candidate() {
    return new ProductionCandidate(
        CANDIDATE,
        1,
        GRAIN,
        Map.of(GRAIN, 7L),
        Map.of(GRAIN, 100L),
        Map.of(AssetKind.CATTLE, 1L),
        7L,
        0L,
        120L,
        new RegimeId("tenant"),
        LaborSource.SELF,
        Set.of(AssetShare.RightKind.OWNED),
        Optional.empty(),
        "候选-粮");
  }

  // ── E1–E6 追加组件的最小自洽夹具（单组件 target 合法；跨表引用按“对侧已提供”分段生效） ──

  static ProductionMode productionMode() {
    return new ProductionMode(MODE, "生产方式", 1, STRUCTURE);
  }

  static ClassStructure classStructure() {
    return new ClassStructure(STRUCTURE, MODE, Map.of(), Map.of());
  }

  static ClassPosition classPosition() {
    return new ClassPosition(
        POSITION,
        MODE,
        "阶层位置",
        ClassPosition.RelationToMeans.DIRECT_LABORER,
        ClassPosition.LaborRole.PROVIDER,
        ClassPosition.SurplusRole.WAGE_EARNER);
  }

  static ClassStanding classStanding() {
    return new ClassStanding(KEY_HH, POSITION, POSITION, Map.of(), 0L, 0L, "");
  }

  static ProductionOrganization productionOrganization() {
    return new ProductionOrganization(
        ORGANIZATION,
        MODE,
        POSITION,
        Optional.empty(),
        NON_DEFAULT_OPERATOR,
        List.of(),
        List.of(),
        List.of(),
        new Recipient.ToActor(NON_DEFAULT_OPERATOR),
        Optional.empty(),
        ProductionOrganization.Status.SHORTAGE,
        "缺资产");
  }

  static AssetRule assetRule() {
    return new AssetRule(
        ASSET_RULE,
        MODE,
        AssetKind.CATTLE,
        true,
        true,
        10,
        Optional.empty(),
        new TransferRule(true, true, false));
  }

  static Government government() {
    return new Government(
        GOVERNMENT, "world", new ActorRef(ActorKind.GOVERNMENT, "treasury"), Set.of());
  }

  static MoneyIssuanceRecord moneyIssuance() {
    return new MoneyIssuanceRecord(
        ISSUANCE, GOVERNMENT, 0L, 1L, SILVER, 100L, MoneyIssuanceKind.INITIAL_ENDOWMENT, "fixture");
  }

  static Pledge pledge() {
    return new Pledge(PLEDGE, D1, FARM_SHARE, 1L, MODE, 10, Pledge.Status.ACTIVE);
  }

  static LiquidationPolicy liquidationPolicy() {
    return new LiquidationPolicy(
        ASSET_RULE,
        1_000,
        0L,
        LiquidationPolicy.PriceSource.POLICY,
        3L,
        LiquidationPolicy.RecipientRule.CREDITOR_FIRST);
  }

  static HexCrisisSignal crisisSignal() {
    return new HexCrisisSignal(
        CRISIS,
        KEY.hex(),
        HexCrisisSignal.Kind.FOOD,
        1,
        0L,
        Map.of("grain", 1L),
        List.of(),
        List.of(),
        "fixture");
  }

  static ModeTransition modeTransition() {
    return new ModeTransition(
        TRANSITION,
        ORGANIZATION,
        MODE,
        MODE_TARGET,
        1_000,
        0L,
        10L,
        ModeTransition.Status.PENDING,
        "fixture");
  }

  static ClassShare classShare() {
    return new ClassShare(CLASS_SHARE, TRANSITION, KEY_HH, POSITION, 1_000L);
  }

  /**
   * ★★ T2 的关系夹具（R3B.2：键 / activity = unit id；**逐值非派生**）：两条规则把 E4 的两个要点各钉一条 —— 地租**显式**给 {@code
   * (hex, landlord)} cohort、自留由 {@code residualOwner} 表达而不是写一条 {@code SELF_RETENTION}。
   */
  static ProductionRelation relation(ProductionUnitId activity, ActorRef operator) {
    return new ProductionRelation(
        activity,
        operator,
        null,
        List.of(
            new CompensationRule(
                RuleType.OUTPUT_SHARE,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD)),
                Pool.GROSS_OUTPUT,
                Weight.NONE,
                300,
                0L,
                Optional.of(GRAIN),
                Optional.empty(),
                10),
            new CompensationRule(
                RuleType.FIXED_IN_KIND_PER_LABOR,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT)),
                Pool.NET_AFTER_INPUTS,
                Weight.LABOR_AMOUNT,
                0,
                144L,
                Optional.of(GRAIN),
                Optional.empty(),
                20)),
        operator);
  }
}
