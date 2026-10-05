package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.migrate.ClassPositionResolver;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassPosition.LaborRole;
import io.mosire.simos.economy.model.ClassPosition.RelationToMeans;
import io.mosire.simos.economy.model.ClassPosition.SurplusRole;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionOrganization.Status;
import io.mosire.simos.economy.model.ProductionRecipe;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>E2：自动生产组织阶段</b>（理想架构 §2.4/§4.2 ①；计划 E2）。
 *
 * <p>★★ <b>它回答的唯一问题</b>："按当前生产方式 + 阶层结构 + 各阶层可支配劳动 + 可用 {@code AssetShare}，本期应当存在 哪些生产组织与生产单元？" ——
 * 为每个"应当生产的阶层位置 × 家户"建立/激活一条 {@link ProductionOrganization} 与对应 {@link ProductionUnit}；组织不起来就落具名
 * {@link Status#SHORTAGE}，<b>不静默跳过、不凭空造资产</b>。
 *
 * <p>★★ <b>闸门（兼容性第一判据）</b>：{@link #organize} 的第一行就是 {@code base.modes().isEmpty()} ⇒ 直接返回空结果，
 * <b>不读/不建任何工作副本</b>。旧档（四张 E1 表全空）的 {@code HouseholdClassRule} 与全部 {@code settle*} 路径因此逐值不变；
 * 新阶段只在显式写入 mode 的世界里生效（计划 §0.2 / E2 兼容约束）。
 *
 * <p>★★ <b>行为规则（逐条，确定性）</b>：
 *
 * <ol>
 *   <li><b>遍历序</b>：mode 按 id 升序 → 阶层位置按 id 升序 → 家户按 id 升序。家户的当前阶层由 {@link
 *       ClassPositionResolver#resolveCurrent} 给（新状态优先、旧 {@code view.stratum} 次之）；无位置/无行/0
 *       人口的家户不建组织；
 *   <li><b>应当生产的位置</b>：{@code laborRole != NONE} 且 {@code surplusRole !=
 *       DEPENDENT}（地主与无劳动角色的官署位置不组织； 三个农民档、佃农/雇工档、匠户都组织）；
 *   <li><b>产业模板</b>：该格已有 {@code industries} 按 id 升序；优先取 regime 与位置角色匹配的模板（自给 → {@code household}、
 *       雇工/佃农 → {@code tenant}、匠户 → {@code handicraft}、其余 → {@code feudal}），没有匹配则退回该格其余模板 （E2 不做完整
 *       mode 产业模板体系，复用现有 industry 作模板源）；全部候选的 unit id 都被别的组织占用 ⇒ 具名 SHORTAGE；
 *   <li><b>优先采用已有生产</b>：该 {@code (industry, organizer)} 的 unit 已存在（本 mode 之前建的、或旧档 preset unit） ⇒
 *       组织直接指向它（关系优先用已有 relation，缺 relation 走"全归 operator"的等价路径），不重建、不覆盖；
 *   <li><b>最紧约束缩产</b>：新 unit 的规模 = {@code min(可用资产那一路, 本户可支配劳动那一路, 本周期投入那一路)}； 可支配劳动 = {@code
 *       ClassRow.participationAdjustedLaborMilli() − 该户已承诺劳动}（并受所选批次的 {@code
 *       LaborSupply.availableLabor() − 已分配}封顶）；投入按 {@code Industry.recipe().inputPerUnit()}
 *       与家户会话库存判。任一路 {@code < 1} ⇒ SHORTAGE（原因里带该路的名字/数字）；
 *   <li><b>租佃（TENANCY）</b>：自有资产不够目标规模时，按 {@code AssetRule(mode, assetKind)} 的 {@code RentRule}
 *       从同产业、同格、<b>自有自营（operator == owner）</b>且不属于本户的闲置份额里拆分出 {@code AssetShare{owner 不变, operator
 *       = 组织者, kind = TENANCY}} —— 只转移使用权，<b>总量不变、不造资产</b>； 缺 AssetRule/缺 RentRule/无闲置份额 ⇒ 具名
 *       SHORTAGE；
 *   <li><b>关系生成</b>：新 unit 优先用已有 relation；没有时用该产业 regime 的 {@link RegimeRelations#defaultRelation}
 *       （唯一默认模板拼写点）；regime 未登记或模板里的 cohort 受方在本格解析不出唯一行 ⇒ 退回<b>最小自留关系</b> （空 rules ⇒ 产出全归
 *       operator，与"缺 relation"是既有的等价路径）。租佃产生的租金腿按 {@link RentRule#legs()} 的顺序逐条转成结算侧已认识的 {@code
 *       *_RENT}/{@code OUTPUT_SHARE} 规则；
 *   <li><b>货币租</b>：只生成 {@code FIXED_MONEY_RENT} 规则，钱的实付/欠额由既有 {@code ProductionSettlement}
 *       货币路径按"付方本期可用货币"结（没有可靠钱源 ⇒ 实付 0 + 欠租读数；<b>不铸钱、不落债务</b>）；
 *   <li><b>劳动配额</b>：为新 unit 发一条 {@code LaborAllocation{activity = unitId, laborMilli = scale ×
 *       laborPerUnit}} （批次取该户已有配额/成员份额里余量最大的；不多发、不超过可支配与批次余量）。
 * </ol>
 *
 * <p>★★ <b>确定性</b>：全程无随机、无时钟、无 UUID；所有遍历序都按稳定 id 排序或来自不可变表的保序迭代；同一入参在同一 状态上重放恒得同一批组织/unit/份额 id（份额
 * id 的 sequence 由 {@link AssetShareBook} 按"同 tuple 已有 id"确定性推出）。
 * 本类是纯协调器单线程阶段：只写调用方交给它的工作副本，不发布、不并行、不跨日持有状态。
 *
 * <p>★ <b>本阶段不做</b>（如实边界）：不做完整 mode 产业模板体系（复用现有 industry）；不发明 Industry 模板；不做独立的市场
 * 准入判定（"无市场"只作为投入缺失的具名原因出现）；不落租金债务（E4）；不改已有 relation 的规则；已 ACTIVE 的组织逐日幂等跳过。
 */
final class EconomyOrganizationSettlement {

  /** 新 unit 的 {@code modeKey} 前缀（唯一拼写点）：识别"这条生产由 mode 组织阶段建立"。 */
  static final String MODE_KEY_PREFIX = "mode:";

  /** 缺口原因前缀（测试/读口按前缀判，完整串见各拼接处）。 */
  static final String REASON_NO_INDUSTRY_TEMPLATE = "NO_INDUSTRY_TEMPLATE";

  static final String REASON_UNIT_ID_TAKEN = "UNIT_ID_TAKEN";
  static final String REASON_NO_LABOR = "NO_LABOR";
  static final String REASON_NO_LABOR_LOT = "NO_LABOR_LOT";
  static final String REASON_NO_INPUT = "NO_INPUT";
  static final String REASON_NO_INPUT_NO_MARKET = "NO_INPUT_NO_MARKET";
  static final String REASON_NO_ASSET_RULE = "NO_ASSET_RULE";
  static final String REASON_NO_RENT_RULE = "NO_RENT_RULE";
  static final String REASON_NO_IDLE_ASSET = "NO_IDLE_ASSET";
  static final String REASON_NO_ASSET = "NO_ASSET";
  static final String REASON_SCALE_OVERFLOW = "SCALE_OVERFLOW";
  static final String REASON_REFUSED = "REFUSED";

  /** 一个"无约束"的规模（劳动/投入那一路不施加约束时用它；任何真实规模都远小于它）。 */
  private static final long UNCONSTRAINED = Long.MAX_VALUE;

  private EconomyOrganizationSettlement() {}

  /** 本阶段的结果：是否写了状态 + 今天新建的 unit id（供日结算把新 unit 加进"不触发本格重排"的豁免集）。 */
  record Outcome(boolean changed, Set<ProductionUnitId> createdUnitIds) {

    Outcome {
      Objects.requireNonNull(createdUnitIds, "createdUnitIds");
      createdUnitIds = Collections.unmodifiableSet(new LinkedHashSet<>(createdUnitIds));
    }

    static Outcome empty() {
      return new Outcome(false, Set.of());
    }
  }

  /** 一条 (mode, position, household) 的决策结果：组织 + 这条决策是否当场新建了 unit。 */
  private record OrganizeResult(ProductionOrganization organization, boolean unitCreated) {

    OrganizeResult {
      Objects.requireNonNull(organization, "organization");
    }
  }

  /** 计划里的一条租佃拆分腿（只记"从哪条份额拆多少"，执行期不重算）。 */
  private record AssetGrant(AssetShareId source, AssetKind asset, long quantity, ActorRef owner) {

    AssetGrant {
      Objects.requireNonNull(source, "AssetGrant.source");
      Objects.requireNonNull(asset, "AssetGrant.asset");
      Objects.requireNonNull(owner, "AssetGrant.owner");
      if (quantity <= 0L) {
        throw new IllegalArgumentException("AssetGrant.quantity 必须 > 0: " + quantity);
      }
    }
  }

  /** 一条新 unit 的劳动配额计划。 */
  private record AllocationPlan(PeopleLotId lot, long laborMilli, long period) {

    AllocationPlan {
      Objects.requireNonNull(lot, "AllocationPlan.lot");
      if (laborMilli <= 0L) {
        throw new IllegalArgumentException("AllocationPlan.laborMilli 必须 > 0: " + laborMilli);
      }
      if (period < 0L) {
        throw new IllegalArgumentException("AllocationPlan.period 不得为负: " + period);
      }
    }
  }

  /** 所选批次的余量。 */
  private record LotChoice(PeopleLotId lot, long room) {

    LotChoice {
      Objects.requireNonNull(lot, "LotChoice.lot");
      if (room <= 0L) {
        throw new IllegalArgumentException("LotChoice.room 必须 > 0: " + room);
      }
    }
  }

  /** 生成的关系 + 它的模板来源串（进 ProductionOrganization.relationTemplateRef）。 */
  private record GeneratedRelation(ProductionRelation relation, String templateRef) {

    GeneratedRelation {
      Objects.requireNonNull(relation, "GeneratedRelation.relation");
      Objects.requireNonNull(templateRef, "GeneratedRelation.templateRef");
    }
  }

  /** 租佃计划：能拆到的腿 + 拆不到的具名原因（部分可拆 ⇒ 按最紧约束缩到能拆到的规模，不整体失败）。 */
  private record TenancyPlan(List<AssetGrant> grants, List<String> reasons) {

    TenancyPlan {
      Objects.requireNonNull(grants, "TenancyPlan.grants");
      Objects.requireNonNull(reasons, "TenancyPlan.reasons");
      grants = List.copyOf(grants);
      reasons = List.copyOf(reasons);
    }
  }

  /**
   * ★ 旧签名（E6a 之前）：等价于传 {@code base.classStandings()} —— 保留给既有调用方/用例；无模式变迁时逐值等于 新签名。生产路径（{@code
   * EconomySettlement}）走带 standing 覆盖的新签名，以便组织阶段看见刚应用的变迁。
   */
  static Outcome organize(
      EconomyData base,
      Map<HouseholdId, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HexCoord, Market> markets,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations) {
    return organize(
        base,
        base.classStandings(),
        rows,
        industries,
        pledges,
        units,
        relations,
        assetShares,
        allocations,
        composition,
        householdGoods,
        markets,
        organizations);
  }

  /**
   * ★★ <b>本阶段入口</b>：{@code modes} 为空时<b>第一行就返回</b>（旧路径逐值不变的全部保证在这里）。
   *
   * @param base 结算前的不可变状态（读
   *     modes/classStructures/classPositions/classStandings/assetRules/industries/pledges）
   * @param classStandings 家户阶层归属视图（E6a：模式变迁在同一日刚写过的工作副本优先；无变迁时 = {@code base.classStandings()}）
   * @param rows 家户工作副本（只读本阶段；键 = 稳定身份）
   * @param industries 产业模板（只读；本阶段不新建模板）
   * @param pledges 质押表（只读；E5a 起作为 {@code AssetShareBook} 的活跃质押上界来源；空表 = 不判）
   * @param units 生产单元工作副本（可能被 upsert）
   * @param relations 生产关系工作副本（可能被 upsert）
   * @param assetShares 实物资产份额工作副本（可能被租佃拆分：只改 operator/kind，总量不变）
   * @param allocations 劳动配额工作副本（可能被 upsert）
   * @param composition 家户人口组成的只读投影（{@code household → (lot → count)}；来自 Social，不是经济状态）
   * @param householdGoods 家户商品账会话副本（只读；判投入约束）
   * @param markets 市场表（只读；只用于"缺投入时有没有市场"的具名区分）
   * @param organizations 生产组织工作副本（upsert）
   * @return 是否写了状态 + 今天新建的 unit id
   */
  static Outcome organize(
      EconomyData base,
      Map<HouseholdId, ClassStanding> classStandings,
      Map<HouseholdId, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HexCoord, Market> markets,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations) {
    Objects.requireNonNull(base, "base");
    if (base.modes().isEmpty()) {
      return Outcome.empty(); // ★★ 闸门：旧档/未接线世界完全不执行本阶段
    }
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(pledges, "pledges");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(allocations, "allocations");
    Objects.requireNonNull(composition, "composition");
    Objects.requireNonNull(householdGoods, "householdGoods");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(organizations, "organizations");

    boolean changed = false;
    Set<ProductionUnitId> created = new LinkedHashSet<>();

    // 家户 → 当前阶层位置（新状态优先、旧 stratum 次之；唯一解析口径在 ClassPositionResolver）。
    Map<HouseholdId, ClassPositionId> positionByHousehold = new LinkedHashMap<>();
    List<HouseholdId> orderedHouseholds = new ArrayList<>(rows.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : orderedHouseholds) {
      resolveCurrent(base, classStandings, household)
          .ifPresent(position -> positionByHousehold.put(household, position));
    }

    // (mode, assetKind) → AssetRule（EconomyData 已判同一组合唯一）；按 id 升序取，重复不可能。
    Map<String, AssetRule> assetRuleByModeKind = new LinkedHashMap<>();
    List<AssetRule> orderedRules = new ArrayList<>(base.assetRules().values());
    orderedRules.sort(Comparator.comparing(rule -> rule.id().value()));
    for (AssetRule rule : orderedRules) {
      assetRuleByModeKind.put(assetKindKey(rule), rule);
    }

    List<ProductionMode> orderedModes = new ArrayList<>(base.modes().values());
    orderedModes.sort(Comparator.comparing(mode -> mode.id().value()));
    for (ProductionMode mode : orderedModes) {
      if (DefaultProductionModes.DISPLACED.equals(mode.id())) {
        // ★★ P11.1 / D-023 #6：流民没有工作、不得从 DISPLACED 池主动招募 —— 自动组织不得为
        //    displaced 位置建 unit / 关系 / 劳动配额，也不得让已有的 displaced 位置被"重新雇佣"。
        continue;
      }
      var structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        continue; // 不完整状态：没有阶层结构就没有可组织的"位置"（不猜默认结构，报告里如实记）
      }
      List<ClassPositionId> orderedPositions = new ArrayList<>(structure.positions().keySet());
      orderedPositions.sort(Comparator.comparing(ClassPositionId::value));
      // ★★ P1：本 mode 下"自己会生产"的家户集合。它们的 OWNED 份额是自家组织的生产资料，
      //   不能被别家的租佃计划当闲置份额拆走 —— 否则先建的组织会在同一天被后建的租佃拆空，
      //   到 revision 边界留下指向已删除份额的 assetSources（实测的构造期守卫失败）。
      Set<ActorRef> reservedHouseholdOwners = new LinkedHashSet<>();
      for (ClassPositionId positionId : orderedPositions) {
        ClassPosition position = structure.positions().get(positionId);
        if (position == null || !shouldProduce(position)) {
          continue;
        }
        for (HouseholdId household : orderedHouseholds) {
          if (!positionId.equals(positionByHousehold.get(household))) {
            continue;
          }
          ClassRow row = rows.get(household);
          if (row != null && row.population() > 0L) {
            reservedHouseholdOwners.add(HouseholdActors.of(household));
          }
        }
      }
      for (ClassPositionId positionId : orderedPositions) {
        ClassPosition position = structure.positions().get(positionId);
        if (position == null || !shouldProduce(position)) {
          continue;
        }
        for (HouseholdId household : orderedHouseholds) {
          if (!positionId.equals(positionByHousehold.get(household))) {
            continue;
          }
          ClassRow row = rows.get(household);
          if (row == null || row.population() <= 0L) {
            continue; // 空行/无人口：不是"应当生产的阶层"，也没有可组织的人
          }
          HexCoord hex = row.view().hex();
          String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
          ProductionOrganizationId orgId = null;
          try {
            orgId = ProductionOrganizationId.idOf(mode.id(), positionId, household, hexKey);
            ProductionOrganization existing = organizations.get(orgId);
            if (existing != null && existing.status() == Status.EXITING) {
              // ★★ E6a：模式变迁把旧 mode 的组织钉在 EXITING —— 该位置即使仍有家户停留（retain=1000），
              //   也不得被自动组织阶段重建/覆盖成 SHORTAGE/ACTIVE（旧 unit 已改挂新 mode，重组织必然失败）。
              continue;
            }
            if (existing != null
                && existing.status() == Status.ACTIVE
                && existing.unitId().isPresent()
                && units.containsKey(existing.unitId().get())) {
              continue; // 已激活：逐日幂等（组织指向的 unit 仍在）
            }
            OrganizeResult result =
                organizeOne(
                    assetRuleByModeKind,
                    mode,
                    position,
                    positionId,
                    household,
                    row,
                    hex,
                    hexKey,
                    orgId,
                    reservedHouseholdOwners,
                    rows,
                    industries,
                    pledges,
                    units,
                    relations,
                    assetShares,
                    allocations,
                    composition,
                                householdGoods,
                    markets);
            ProductionOrganization previous = organizations.put(orgId, result.organization());
            if (!result.organization().equals(previous)) {
              changed = true;
            }
            if (result.unitCreated()) {
              created.add(result.organization().unitId().orElseThrow());
            }
          } catch (IllegalArgumentException e) {
            // 坏 id/坏模板：不半建、不静默 —— 落一条具名 SHORTAGE（状态里能看到原因，读口/报告可追溯）。
            ProductionOrganization refused =
                shortage(
                    orgId != null ? orgId : orgIdFallback(mode, positionId, household, hexKey),
                    mode,
                    positionId,
                    household,
                    List.of(),
                    REASON_REFUSED + ":" + e.getMessage());
            if (!refused.equals(organizations.put(refused.id(), refused))) {
              changed = true;
            }
          }
        }
      }
    }
    return new Outcome(changed, created);
  }

  /**
   * ★★ <b>一个 (mode, position, household) 的组织决策</b>：先"采用已有 unit / 建新 unit"两路，再逐个把约束判死； 全部通过才返回
   * ACTIVE，否则返回具名 SHORTAGE。plan 阶段只读，apply 阶段才写工作副本。
   */
  private static OrganizeResult organizeOne(
      Map<String, AssetRule> assetRuleByModeKind,
      ProductionMode mode,
      ClassPosition position,
      ClassPositionId positionId,
      HouseholdId household,
      ClassRow row,
      HexCoord hex,
      String hexKey,
      ProductionOrganizationId orgId,
      Set<ActorRef> reservedHouseholdOwners,
      Map<HouseholdId, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HexCoord, Market> markets) {
    ActorRef organizer = HouseholdActors.of(household);
    String modeKey = MODE_KEY_PREFIX + mode.id().value();
    List<IndustryId> candidates = IndustryHexKeys.at(industries, hex.q(), hex.r());
    if (candidates.isEmpty()) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          List.of(),
          REASON_NO_INDUSTRY_TEMPLATE + ":" + hexKey);
    }
    Optional<IndustryId> selected =
        selectIndustry(industries, candidates, position, modeKey, organizer, units);
    if (selected.isEmpty()) {
      return shortageResult(
          orgId, mode, positionId, household, List.of(), REASON_UNIT_ID_TAKEN + ":" + hexKey);
    }
    IndustryId industryId = selected.get();
    Industry industry = industries.get(industryId);
    ProductionUnitId unitId = ProductionUnitId.idOf(industryId, organizer);
    ProductionUnit existingUnit = units.get(unitId);

    // ── 路由 ①：采用已有 unit（本 mode 之前建的，或旧档/播种的 preset unit）────────────────
    if (existingUnit != null) {
      boolean ours = existingUnit.modeKey().equals(modeKey);
      boolean preset = existingUnit.modeKey().equals(existingUnit.industry().value());
      if (ours || preset) {
        ProductionRelation relation = relations.get(unitId);
        List<Recipient> inputSources =
            relation == null ? List.of() : List.of(relation.inputSupplier());
        Recipient outputOwnership =
            relation == null
                ? new Recipient.ToActor(organizer)
                : new Recipient.ToActor(relation.residualOwner());
        return new OrganizeResult(
            new ProductionOrganization(
                orgId,
                mode.id(),
                positionId,
                Optional.of(unitId),
                organizer,
                List.of(household),
                shareIdsOf(industryId, organizer, assetShares),
                inputSources,
                outputOwnership,
                Optional.of("existing:" + unitId.value()),
                Status.ACTIVE,
                ""),
            false);
      }
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          REASON_UNIT_ID_TAKEN + ":" + unitId.value());
    }

    // ── 路由 ②：计划一条新 unit（只读；任一路不满 ⇒ 具名 SHORTAGE）──────────────────────────
    ProductionRecipe recipe = industry.recipe();
    long disposableLabor = disposableLabor(household, row, allocations);
    LotChoice lotChoice = null;
    if (recipe.laborPerUnit() > 0L) {
      lotChoice = chooseLot(household, allocations, composition, rows);
      if (lotChoice == null) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_LABOR_LOT + ":" + household.value());
      }
    }
    long laborBudget = lotChoice == null ? 0L : Math.min(disposableLabor, lotChoice.room());
    long laborScale;
    if (recipe.laborPerUnit() <= 0L) {
      laborScale = UNCONSTRAINED;
    } else {
      laborScale = laborBudget / recipe.laborPerUnit();
      if (laborScale < 1L) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_LABOR
                + ":household="
                + household.value()
                + ",budget="
                + laborBudget
                + ",need="
                + recipe.laborPerUnit());
      }
    }
    Map<CommodityId, Long> stock = householdGoods.getOrDefault(household, Map.of());
    long inputScale = UNCONSTRAINED;
    String inputReason = null;
    for (Map.Entry<CommodityId, Long> entry : recipe.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long available = stock.getOrDefault(entry.getKey(), 0L);
      long scale = available / entry.getValue();
      if (scale < 1L && inputReason == null) {
        inputReason =
            (markets.containsKey(hex) ? REASON_NO_INPUT : REASON_NO_INPUT_NO_MARKET)
                + ":commodity="
                + entry.getKey().value()
                + ",available="
                + available
                + ",need="
                + entry.getValue();
      }
      inputScale = Math.min(inputScale, scale);
    }
    if (inputScale < 1L) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          inputReason == null ? REASON_NO_INPUT + ":unknown" : inputReason);
    }
    long target = Math.min(laborScale, inputScale);
    Map<AssetKind, Long> usable =
        ProductionUnitBook.usableAssets(industryId, organizer, assetShares);
    long ownCapacity =
        ProductionUnitBook.capacityScaleOf(industryId, organizer, industry, assetShares);
    // 资产天花板 = 自有 + 同产业全部可租闲置份额（"自家会生产"的家户份额除外）；目标规模不超过它。
    long assetCeiling =
        assetCeiling(industry, organizer, usable, assetShares, reservedHouseholdOwners);
    long desired;
    if (target == UNCONSTRAINED) {
      desired = Math.max(1L, Math.min(Math.max(ownCapacity, 0L), assetCeiling));
    } else {
      desired = Math.max(1L, Math.min(target, assetCeiling));
    }

    TenancyPlan tenancy =
        ownCapacity < desired
            ? planTenancy(
                assetRuleByModeKind,
                mode,
                industry,
                organizer,
                usable,
                desired,
                assetShares,
                reservedHouseholdOwners)
            : new TenancyPlan(List.of(), List.of());
    long expectedCapacity = expectedCapacityAfterGrants(industry, usable, tenancy.grants());
    if (expectedCapacity < 1L) {
      String reason =
          tenancy.reasons().isEmpty()
              ? REASON_NO_ASSET + ":" + industryId.value()
              : String.join(",", tenancy.reasons());
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          reason);
    }
    long scale = Math.min(expectedCapacity, target == UNCONSTRAINED ? expectedCapacity : target);
    if (scale < 1L) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          REASON_NO_ASSET + ":" + industryId.value());
    }
    List<AssetGrant> grants = new ArrayList<>(tenancy.grants());

    GeneratedRelation generated =
        generateRelation(mode, position, industry, industryId, unitId, organizer, row, rows);
    List<CompensationRule> rules = new ArrayList<>(generated.relation().rules());
    for (AssetGrant grant : rentGroups(grants)) {
      AssetRule rule = assetRuleByModeKind.get(assetKindKey(mode.id().value(), grant.asset()));
      RentRule rentRule = rule == null ? null : rule.rentRule().orElse(null);
      if (rentRule == null) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_RENT_RULE + ":" + grant.asset().name());
      }
      rules.addAll(rentRules(rentRule, new Recipient.ToActor(grant.owner())));
    }
    ProductionRelation relation =
        new ProductionRelation(
            unitId,
            organizer,
            generated.relation().inputSupplier(),
            rules,
            generated.relation().residualOwner(),
            generated.relation().laborSource());

    Optional<AllocationPlan> allocation = Optional.empty();
    if (recipe.laborPerUnit() > 0L) {
      long amount = Math.min(scale * recipe.laborPerUnit(), laborBudget);
      if (amount < recipe.laborPerUnit()) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_LABOR + ":" + household.value());
      }
      // ★★ P2-A A4：period 只是审计标签（供给表已删除）；批次有效性已由 chooseLot 的组成投影保证。
      allocation = Optional.of(new AllocationPlan(lotChoice.lot(), amount, 1L));
    }

    // ── apply：全部可失败判断已在上面做完，这里只落工作副本（不重置/不覆盖既有 unit）──────────
    applyGrants(grants, organizer, industries, pledges, assetShares);
    units.put(unitId, new ProductionUnit(unitId, industryId, organizer, modeKey, 0L, 0L, Map.of()));
    relations.put(unitId, relation);
    allocation.ifPresent(
        plan -> {
          LaborAllocationId allocationId = LaborAllocation.idOf(unitId, plan.lot(), household);
          allocations.put(
              allocationId,
              new LaborAllocation(
                  allocationId,
                  plan.lot(),
                  household,
                  organizer,
                  unitId.value(),
                  plan.laborMilli(),
                  plan.period()));
        });
    return new OrganizeResult(
        new ProductionOrganization(
            orgId,
            mode.id(),
            positionId,
            Optional.of(unitId),
            organizer,
            List.of(household),
            shareIdsOf(industryId, organizer, assetShares),
            List.of(relation.inputSupplier()),
            new Recipient.ToActor(relation.residualOwner()),
            Optional.of(generated.templateRef()),
            Status.ACTIVE,
            ""),
        true);
  }

  // ── 位置/产业/批次选择 ─────────────────────────────────────────────────────────────────

  /** 应当生产的位置：有劳动角色且不是纯被供养者；地主（NONE）与官署（NONE）不组织。 */
  private static boolean shouldProduce(ClassPosition position) {
    return position.laborRole() != LaborRole.NONE
        && position.surplusRole() != SurplusRole.DEPENDENT;
  }

  /**
   * ★★ <b>E6a：当前阶层位置的解析（结算工作副本优先）</b>：日结算可能在自动组织之前刚应用了模式变迁， {@code classStandings} 工作副本里的
   * currentPositionId 已是新位置；若仍读 {@code base} 的旧归属，本日组织会按旧位置 重来一遍。传进来的表在无变迁时逐字等于 {@code
   * base.classStandings()}（{@code classStandingsOrBase()}），旧路径不变。
   */
  private static Optional<ClassPositionId> resolveCurrent(
      EconomyData base, Map<HouseholdId, ClassStanding> classStandings, HouseholdId household) {
    ClassStanding standing = classStandings.get(household);
    if (standing != null) {
      return Optional.of(standing.currentPositionId());
    }
    return ClassPositionResolver.resolveCurrent(base, household);
  }

  /** 位置角色 → 优先 regime（E2 的产业模板选择启发式；只是"先试哪一个"，不是规则权威）。 */
  private static String preferredRegime(ClassPosition position) {
    // ★★ P11.7 / D-024：merchant 位置优先 regime==merchant 的产业模板（trade）；没有才回退下面的现有顺序。
    if (DefaultProductionModes.MERCHANT.equals(position.modeId())) {
      return RegimeOperators.MERCHANT;
    }
    if (position.surplusRole() == SurplusRole.SELF_SUBSISTENCE) {
      return RegimeOperators.HOUSEHOLD;
    }
    if (position.surplusRole() == SurplusRole.WAGE_EARNER) {
      return position.relationToMeans() == RelationToMeans.DIRECT_LABORER
          ? RegimeOperators.TENANT
          : RegimeOperators.HANDICRAFT;
    }
    return RegimeOperators.FEUDAL;
  }

  /**
   * 从该格候选产业里选一个"这个位置可以用"的：regime 匹配的排在前面（同组按 id 升序），其余按 id 升序； 取第一个 unit id 空闲/可采用的。全被别的组织占用 ⇒
   * 空（调用方落具名 SHORTAGE）。
   */
  private static Optional<IndustryId> selectIndustry(
      Map<IndustryId, Industry> industries,
      List<IndustryId> candidates,
      ClassPosition position,
      String modeKey,
      ActorRef organizer,
      Map<ProductionUnitId, ProductionUnit> units) {
    String wanted = preferredRegime(position);
    List<IndustryId> preferred = new ArrayList<>();
    List<IndustryId> rest = new ArrayList<>();
    for (IndustryId id : candidates) {
      Industry industry = industries.get(id);
      if (industry != null && industry.regime().value().equals(wanted)) {
        preferred.add(id);
      } else {
        rest.add(id);
      }
    }
    preferred.sort(Comparator.comparing(IndustryId::value));
    rest.sort(Comparator.comparing(IndustryId::value));
    List<IndustryId> ordered = new ArrayList<>(preferred);
    ordered.addAll(rest);
    for (IndustryId industryId : ordered) {
      ProductionUnitId unitId = ProductionUnitId.idOf(industryId, organizer);
      ProductionUnit existing = units.get(unitId);
      if (existing == null
          || existing.modeKey().equals(modeKey)
          || existing.modeKey().equals(existing.industry().value())) {
        return Optional.of(industryId);
      }
    }
    return Optional.empty();
  }

  /** 可支配时间（毫小时/tick）= 行时间预算（{@code ClassRow.laborMilli}）− 该户已承诺的全部配额；不除零、不为负。 */
  private static long disposableLabor(
      HouseholdId household, ClassRow row, Map<LaborAllocationId, LaborAllocation> allocations) {
    long allocated = 0L;
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.household().equals(household)) {
        allocated += allocation.laborMilli();
      }
    }
    return Math.max(0L, row.laborMilli() - allocated);
  }

  /**
   * 选批次：该户已有配额用过的批次 + 该户成员份额指向的批次，按批次 id 升序逐个算余量 （{@code LaborSupply.availableLabor() −
   * 该批次全部已分配}），取余量最大者；并列取 id 最小者。没有 ⇒ 空。
   */
  private static LotChoice chooseLot(
      HouseholdId household,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, ClassRow> rows) {
    Set<PeopleLotId> lots = new LinkedHashSet<>();
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.household().equals(household)) {
        lots.add(allocation.group());
      }
    }
    // ★ P2-A A3：家户人口组成来自 Social 的只读投影（不是 Economy 状态里的成员份额表）。
    for (Map.Entry<PeopleLotId, Long> member :
        composition.getOrDefault(household, Map.of()).entrySet()) {
      if (member.getValue() != null && member.getValue() > 0L) {
        lots.add(member.getKey());
      }
    }
    List<PeopleLotId> ordered = new ArrayList<>(lots);
    ordered.sort(Comparator.comparing(PeopleLotId::value));
    // ★★ P2-A A4：余量 = **家户时间预算** − 该户已承诺的全部配额（不再有批次级供给这第二权威）；
    //   批次只决定这笔时间记在哪个 lot 名下 ⇒ 取 id 最小的可用批次。
    long room = disposableLabor(household, rows.get(household), allocations);
    return ordered.isEmpty() || room <= 0L ? null : new LotChoice(ordered.get(0), room);
  }

  // ── 租佃计划 ─────────────────────────────────────────────────────────────────────────────

  /**
   * 自有资产不够目标规模时，用 {@link AssetRule#rentRule()} 从闲置份额里拆 TENANCY。返回 null = 计划可行 （grants 已填）；非 null =
   * 具名缺口原因。只读：不改任何份额。
   *
   * <p>★ <b>部分可拆不是失败</b>：能拆多少拆多少，调用方按"拆分后的产能规模"缩产；只有缩到 {@code < 1} 时， 返回的 {@link
   * TenancyPlan#reasons()} 才被拼进 SHORTAGE 原因（缺规则/无闲置份额各是一条具名原因）。
   */
  private static TenancyPlan planTenancy(
      Map<String, AssetRule> assetRuleByModeKind,
      ProductionMode mode,
      Industry industry,
      ActorRef organizer,
      Map<AssetKind, Long> usable,
      long desiredScale,
      Map<AssetShareId, AssetShare> assetShares,
      Set<ActorRef> reservedHouseholdOwners) {
    List<AssetGrant> grants = new ArrayList<>();
    // 缺资产那几路按 AssetKind.name() 稳定序处理（同一产业内的资产种类集合来自模板）。
    List<AssetKind> required = new ArrayList<>(industry.capacityPerUnit().keySet());
    required.sort(Comparator.comparing(AssetKind::name));
    List<String> reasons = new ArrayList<>();
    for (AssetKind assetKind : required) {
      long perUnit = industry.capacityPerUnit().get(assetKind);
      if (perUnit <= 0L) {
        continue;
      }
      if (desiredScale > Long.MAX_VALUE / perUnit) {
        reasons.add(REASON_SCALE_OVERFLOW + ":" + assetKind.name());
        continue;
      }
      long need = desiredScale * perUnit;
      long have = usable.getOrDefault(assetKind, 0L);
      long deficit = need - have;
      if (deficit <= 0L) {
        continue;
      }
      AssetRule rule = assetRuleByModeKind.get(assetKindKey(mode.id().value(), assetKind));
      if (rule == null) {
        reasons.add(REASON_NO_ASSET_RULE + ":" + assetKind.name());
        continue;
      }
      if (rule.rentRule().isEmpty()) {
        reasons.add(REASON_NO_RENT_RULE + ":" + assetKind.name());
        continue;
      }
      long taken = 0L;
      for (AssetShare source :
          idleSources(industry.id(), assetKind, organizer, assetShares, reservedHouseholdOwners)) {
        long give = Math.min(deficit - taken, source.quantity());
        if (give <= 0L) {
          continue;
        }
        grants.add(new AssetGrant(source.id(), assetKind, give, source.owner()));
        taken += give;
        if (taken >= deficit) {
          break;
        }
      }
      if (taken < deficit) {
        reasons.add(
            REASON_NO_IDLE_ASSET
                + ":"
                + assetKind.name()
                + "(need="
                + deficit
                + ",got="
                + taken
                + ")");
      }
    }
    return new TenancyPlan(grants, reasons);
  }

  /** 自有 + 同产业闲置份额能支撑的规模天花板（只读；用于把 desired 限在真实资产上界内、避免溢出）。 */
  private static long assetCeiling(
      Industry industry,
      ActorRef organizer,
      Map<AssetKind, Long> usable,
      Map<AssetShareId, AssetShare> assetShares,
      Set<ActorRef> reservedHouseholdOwners) {
    long ceiling = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      AssetKind assetKind = entry.getKey();
      long available = usable.getOrDefault(assetKind, 0L);
      for (AssetShare source :
          idleSources(industry.id(), assetKind, organizer, assetShares, reservedHouseholdOwners)) {
        available = saturatingAdd(available, source.quantity());
      }
      ceiling = Math.min(ceiling, available / entry.getValue());
    }
    return ceiling == Long.MAX_VALUE ? 0L : ceiling;
  }

  /** 饱和加法（{@code Long.MAX_VALUE} 为上限；只用于"天花板"这类只关心是否足够大的量）。 */
  private static long saturatingAdd(long left, long right) {
    long sum = left + right;
    return sum < 0L ? Long.MAX_VALUE : sum;
  }

  /**
   * 同产业、同资产种类、自有自营（operator == owner）、不属于组织者、TENANCY 之外的闲置份额（按 id 升序）。
   *
   * <p>★ {@code reservedHouseholdOwners} = 本 mode 下自己会生产的家户 actor 集合：这些家户的 OWNED 份额要留给
   * 他们自己的组织，不能被别家的租佃拆走（见 {@code organize} 里的注释）。非生产位置（地主/官署）的家户份额不在此列。
   */
  private static List<AssetShare> idleSources(
      IndustryId industryId,
      AssetKind assetKind,
      ActorRef organizer,
      Map<AssetShareId, AssetShare> assetShares,
      Set<ActorRef> reservedHouseholdOwners) {
    List<AssetShare> sources = new ArrayList<>();
    for (AssetShare share : assetShares.values()) {
      if (!share.industry().equals(industryId) || share.asset() != assetKind) {
        continue;
      }
      if (share.kind() == AssetShare.RightKind.TENANCY) {
        continue; // 已租出的份额不再转租（E2 不做转租；TransferRule.allowSublease 由后续阶段读）
      }
      if (!share.operator().equals(share.owner())) {
        continue; // 已有人在用（operator != owner）⇒ 不是闲置
      }
      if (share.owner().equals(organizer)) {
        continue; // 本户自己的份额已经在 usable 里
      }
      if (reservedHouseholdOwners.contains(share.owner())) {
        continue; // 该份额的主人自己会生产：留给它，不当别家的闲置
      }
      if (share.quantity() <= 0L) {
        continue;
      }
      sources.add(share);
    }
    sources.sort(Comparator.comparing(share -> share.id().value()));
    return sources;
  }

  /**
   * 把 grants 落到份额工作副本：<b>委托 {@link AssetShareBook#apply}</b> —— 源份额减量/删行，新建 owner 不变、
   * operator=组织者、kind=TENANCY 的份额（Σ 逐 {@code (industry, asset)} 守恒）。★ E5a 起不再在本类直接 {@code
   * put}/{@code remove} 份额：新 id 的确定性序号、源数量上界、industry/质押守卫全部收在唯一写口，且失败时工作副本一字不动。
   */
  private static void applyGrants(
      List<AssetGrant> grants,
      ActorRef organizer,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      LinkedHashMap<AssetShareId, AssetShare> assetShares) {
    if (grants.isEmpty()) {
      return;
    }
    List<AssetShareBook.Move> moves = new ArrayList<>(grants.size());
    for (AssetGrant grant : grants) {
      moves.add(
          new AssetShareBook.Move(
              grant.source(),
              grant.quantity(),
              grant.owner(),
              organizer,
              AssetShare.RightKind.TENANCY));
    }
    AssetShareBook.apply(assetShares, industries, pledges, moves);
  }

  /** 租佃拆分后的产能规模（与 {@code ProductionUnitBook} 同式的只读预估；grants 尚未落盘）。 */
  private static long expectedCapacityAfterGrants(
      Industry industry, Map<AssetKind, Long> usable, List<AssetGrant> grants) {
    Map<AssetKind, Long> granted = new LinkedHashMap<>();
    for (AssetGrant grant : grants) {
      granted.merge(grant.asset(), grant.quantity(), Long::sum);
    }
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      long available =
          usable.getOrDefault(entry.getKey(), 0L) + granted.getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, available / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /** 租佃腿的分组：同一 (assetKind, owner) 只生成一条租金规则（多条份额不重复计租）；保序确定。 */
  private static List<AssetGrant> rentGroups(List<AssetGrant> grants) {
    Map<String, AssetGrant> groups = new LinkedHashMap<>();
    List<AssetGrant> ordered = new ArrayList<>(grants);
    ordered.sort(
        Comparator.comparing((AssetGrant grant) -> grant.asset().name())
            .thenComparing(grant -> grant.owner().kind().name())
            .thenComparing(grant -> grant.owner().id())
            .thenComparing(grant -> grant.source().value()));
    for (AssetGrant grant : ordered) {
      groups.putIfAbsent(grant.asset().name() + "|" + grant.owner(), grant);
    }
    return new ArrayList<>(groups.values());
  }

  /** {@link RentRule} → 结算侧规则（逐腿一条；货币腿走既有 FIXED_MONEY_RENT 路径）。 */
  private static List<CompensationRule> rentRules(RentRule rentRule, Recipient recipient) {
    List<CompensationRule> rules = new ArrayList<>();
    for (RentRule.RentLeg leg : rentRule.legs()) {
      CompensationRule rule =
          switch (leg.kind()) {
            case FIXED_IN_KIND ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT,
                    recipient,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    leg.fixedAmount(),
                    leg.commodity(),
                    Optional.empty(),
                    rentRule.priority());
            case FIXED_MONEY ->
                new CompensationRule(
                    RuleType.FIXED_MONEY_RENT,
                    recipient,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    leg.fixedAmount(),
                    Optional.empty(),
                    leg.currency(),
                    rentRule.priority());
            case SHARE ->
                new CompensationRule(
                    RuleType.OUTPUT_SHARE,
                    recipient,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    leg.ratePerMille(),
                    0L,
                    leg.commodity(),
                    Optional.empty(),
                    rentRule.priority());
            case MIXED -> throw new IllegalArgumentException("RentLeg.kind 不得为 MIXED（构造期已判死）");
          };
      rules.add(rule);
    }
    return rules;
  }

  // ── 关系生成 ─────────────────────────────────────────────────────────────────────────────

  /**
   * 新 unit 的关系：优先 regime 默认模板（唯一拼写点 {@link RegimeRelations}），模板里的 cohort 受方在本格解析不出 唯一行/或 regime 未登记
   * ⇒ 退回最小自留关系（空 rules = 产出全归 operator，既有等价路径）。
   */
  private static GeneratedRelation generateRelation(
      ProductionMode mode,
      ClassPosition position,
      Industry industry,
      IndustryId industryId,
      ProductionUnitId unitId,
      ActorRef organizer,
      ClassRow row,
      Map<HouseholdId, ClassRow> rows) {
    try {
      ProductionRelation byRegime =
          RegimeRelations.defaultRelation(
              industry.regime(), unitId, industryId, organizer, Set.of(row.view().residence()));
      ProductionRelation normalized = normalizeRecipients(byRegime, rows, row.view().hex());
      if (normalized != null) {
        return new GeneratedRelation(
            normalized, "mode:" + mode.id().value() + ":regime:" + industry.regime().value());
      }
    } catch (IllegalArgumentException ignored) {
      // regime 未登记（没有默认关系模板）⇒ 退回最小自留关系；不猜制度。
    }
    ProductionRelation minimal =
        new ProductionRelation(
            unitId,
            organizer,
            new Recipient.ToActor(organizer),
            List.of(),
            organizer,
            laborSourceOf(position));
    return new GeneratedRelation(
        minimal, "mode:" + mode.id().value() + ":position:" + position.id().value());
  }

  /**
   * ★★ <b>把 regime 默认模板里的 cohort 受方一对一归一成 {@link Recipient.ToHousehold}，并剔除 受方 == organizer
   * 的自付规则</b>。
   *
   * <p>★★ <b>为什么必须在 E2 就地归一</b>：新建 unit 的关系当天就会被 harvest/settle 使用，而 {@code EconomyData} 的构造期归一到
   * revision 边界才发生；若把 {@code ToCohort} 留到那时， 按劳动加权的分账会在 {@code SubsistenceObligation.laborOf}
   * 处因"没有家户身份"fail-closed（实测）。 返回 {@code null} = 视图不唯一 / 受方不在本格 / 家户不存在 ⇒ 调用方退回最小自留关系，不猜。
   *
   * <p>★★ <b>自付规则为什么直接剔除而不是留到转移端</b>：{@code Transfer} 的两端不得相等（自转移是坏数据）。 一条"付给 organizer
   * 自己"的规则对余额是恒等变换：产出已经归 residualOwner = organizer，工资/租金也只是 从自己的一个口袋到另一个口袋。这四档默认模板的 cohort 受方在 E2
   * 里就是 operator 本人时（家户自营）， 对应的份额 <b>本来就该留在 operator 手里</b>，故剔除；其余受方照原率保留。
   *
   * <p>★★ <b>GAP-3 共用</b>：{@code ModeMigrationSettlement.buildMigrationRelation} 走同一条模板也必须就地归一
   * —— 否则迁移新建的关系会在**当天** harvest 时被 {@code requireCohortRows} 的"视图不再是唯一身份"守卫拒绝。归一只有这一份拼写点。
   */
  static ProductionRelation normalizeRecipients(
      ProductionRelation relation, Map<HouseholdId, ClassRow> rows, HexCoord hex) {
    Map<CohortKey, HouseholdId> householdByView = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    for (ClassRow row : rows.values()) {
      if (householdByView.putIfAbsent(row.view(), row.id()) != null) {
        ambiguous.add(row.view());
      }
    }
    for (CohortKey view : ambiguous) {
      householdByView.remove(view);
    }
    List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
    boolean changed = false;
    for (CompensationRule rule : relation.rules()) {
      Recipient recipient = rule.recipient();
      if (recipient instanceof Recipient.ToHousehold toHousehold) {
        if (HouseholdActors.of(toHousehold.household()).equals(relation.operator())) {
          changed = true; // 自付：净额恒等，不落转移（见类注）
          continue;
        }
        ClassRow target = rows.get(toHousehold.household());
        if (target == null || !target.view().hex().equals(hex)) {
          return null;
        }
        rules.add(rule);
      } else if (recipient instanceof Recipient.ToCohort toCohort) {
        HouseholdId household = householdByView.get(toCohort.cohort());
        if (household == null) {
          return null;
        }
        if (HouseholdActors.of(household).equals(relation.operator())) {
          changed = true; // 自付：与上面同一条口径
          continue;
        }
        ClassRow target = rows.get(household);
        if (target == null || !target.view().hex().equals(hex)) {
          return null;
        }
        rules.add(
            new CompensationRule(
                rule.type(),
                new Recipient.ToHousehold(household),
                rule.pool(),
                rule.weight(),
                rule.ratePerMille(),
                rule.fixedAmount(),
                rule.commodity(),
                rule.currency(),
                rule.priority()));
        changed = true;
      } else if (recipient instanceof Recipient.ToActor toActor
          && toActor.actor().equals(relation.operator())) {
        changed = true; // 自付：同一条口径（默认模板不走这档，留给将来）
        continue;
      } else {
        rules.add(rule);
      }
    }
    return changed
        ? new ProductionRelation(
            relation.activity(),
            relation.operator(),
            relation.inputSupplier(),
            rules,
            relation.residualOwner(),
            relation.laborSource())
        : relation;
  }

  /** 位置 → 劳动来源档（最小自留关系用；E2 只做形状，逐档结算差异属后续阶段）。 */
  private static LaborSource laborSourceOf(ClassPosition position) {
    return switch (position.surplusRole()) {
      case WAGE_EARNER ->
          position.relationToMeans() == RelationToMeans.DIRECT_LABORER
              ? LaborSource.TENANT
              : LaborSource.WAGE;
      case SELF_SUBSISTENCE -> LaborSource.SELF;
      default -> position.laborRole() == LaborRole.PROVIDER ? LaborSource.WAGE : LaborSource.SELF;
    };
  }

  // ── 小工具 ───────────────────────────────────────────────────────────────────────────────

  /** 一个组织指名的资产份额列表（同产业、同 operator；按 id 升序 ⇒ 字节可复现）。 */
  private static List<AssetShareId> shareIdsOf(
      IndustryId industryId, ActorRef operator, Map<AssetShareId, AssetShare> assetShares) {
    List<AssetShareId> ids = new ArrayList<>();
    for (AssetShare share : assetShares.values()) {
      if (share.industry().equals(industryId) && share.operator().equals(operator)) {
        ids.add(share.id());
      }
    }
    ids.sort(Comparator.comparing(AssetShareId::value));
    return ids;
  }

  /** 具名缺口结果（没有 unit；资产来源照实带过，便于读口看"当时有什么/缺什么"）。 */
  private static OrganizeResult shortageResult(
      ProductionOrganizationId orgId,
      ProductionMode mode,
      ClassPositionId positionId,
      HouseholdId household,
      List<AssetShareId> assetSources,
      String reason) {
    return new OrganizeResult(
        shortage(orgId, mode, positionId, household, assetSources, reason), false);
  }

  /** 具名缺口组织本体（没有 unit）。 */
  private static ProductionOrganization shortage(
      ProductionOrganizationId orgId,
      ProductionMode mode,
      ClassPositionId positionId,
      HouseholdId household,
      List<AssetShareId> assetSources,
      String reason) {
    return new ProductionOrganization(
        orgId,
        mode.id(),
        positionId,
        Optional.empty(),
        HouseholdActors.of(household),
        List.of(household),
        assetSources,
        List.of(),
        new Recipient.ToActor(HouseholdActors.of(household)),
        Optional.empty(),
        Status.SHORTAGE,
        reason);
  }

  /** 组织 id 的兜底构造（id 工厂本身抛异常时用；把会引发地址截断的 {@code "."} 换成 {@code "_"}，保持确定性）。 */
  private static ProductionOrganizationId orgIdFallback(
      ProductionMode mode, ClassPositionId positionId, HouseholdId household, String hexKey) {
    String sanitized =
        ("org-"
                + mode.id().value()
                + "-"
                + positionId.value()
                + "-"
                + household.value()
                + "-"
                + hexKey)
            .replace('.', '_');
    return new ProductionOrganizationId(sanitized);
  }

  /** {@code (mode, assetKind)} 的稳定索引键（只在内存里用，不进状态/线格式）。 */
  private static String assetKindKey(AssetRule rule) {
    return assetKindKey(rule.modeId().value(), rule.assetKind());
  }

  /** {@code (mode, assetKind)} 的稳定索引键（字符串形态；上面是同义重载）。 */
  private static String assetKindKey(String modeId, AssetKind assetKind) {
    return modeId + "|" + assetKind.name();
  }
}
