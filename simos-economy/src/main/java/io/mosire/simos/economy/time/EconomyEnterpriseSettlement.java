package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>E2：自动生产组织阶段</b>（理想架构 §2.4/§4.2 ①；计划 E2）。
 *
 * <p>★★ <b>它回答的唯一问题</b>："按当前生产方式 + 阶层结构 + 各阶层可支配劳动 + 可用 {@code OwnershipStake}，本期应当存在 哪些生产组织与生产单元？"
 * —— 为每个"应当生产的阶层位置 × 家户"建立/激活一条 {@link ProductionEnterprise} 与对应 {@link
 * ProductionProcess}；组织不起来就落具名 {@link Status#SHORTAGE}，<b>不静默跳过、不凭空造资产</b>。
 *
 * <p>★★ <b>闸门（兼容性第一判据）</b>：{@link #organize} 的第一行就是 {@code base.modes().isEmpty()} ⇒ 直接返回空结果，
 * <b>不读/不建任何工作副本</b>。旧档（四张 E1 表全空）的 {@code HouseholdClassRule} 与全部 {@code settle*} 路径因此逐值不变；
 * 新阶段只在显式写入 mode 的世界里生效（计划 §0.2 / E2 兼容约束）。
 *
 * <p>★★ <b>行为规则（逐条，确定性）</b>：
 *
 * <ol>
 *   <li><b>遍历序</b>：mode 按 id 升序 → 阶层位置按 id 升序 → 家户按 id 升序。家户的参与位置由 {@link
 *       HouseholdClassMembership#effectivePositionIds()} 给（当前位置 ∪ 追加参与集合；无 standing 的旧档退回 {@link
 *       ProductionRoleResolver#resolveCurrent} 的单值旧口径）⇒ <b>同一家户可同时参与多个 mode/position，各建一条组织与
 *       unit</b>；无位置/无行/0 人口的家户不建组织；
 *   <li><b>应当生产的位置</b>：{@code laborRole != NONE} 且 {@code surplusRole !=
 *       DEPENDENT}（地主与无劳动角色的官署位置不组织； 三个农民档、佃农/雇工档、匠户都组织）；
 *   <li><b>产业模板</b>：该格已有 {@code industries} 按 id 升序；优先取 regime 与位置角色匹配的模板（自给 → {@code household}、
 *       雇工/佃农 → {@code tenant}、匠户 → {@code handicraft}、其余 → {@code feudal}），没有匹配则退回该格其余模板 （E2 不做完整
 *       mode 产业模板体系，复用现有 industry 作模板源）；全部候选的 unit id 都被别的组织占用 ⇒ 具名 SHORTAGE；
 *   <li><b>优先采用已有生产</b>：该 {@code (industry, organizer)} 的 unit 已存在（本 mode 之前建的、或旧档 preset unit） ⇒
 *       组织直接指向它（关系优先用已有 relation，缺 relation 走"全归 operator"的等价路径），不重建、不覆盖；
 *   <li><b>最紧约束缩产</b>：新 unit 的规模 = {@code min(可用资产那一路, 本户可支配劳动那一路, 本周期投入那一路)}； 可支配劳动 = {@code
 *       HouseholdEconomy.participationAdjustedLaborMilli() − 该户已承诺劳动}（并受所选批次的 {@code
 *       LaborSupply.availableLabor() − 已分配}封顶）；投入按 {@code
 *       IndustryDefinitionView.recipe().inputPerUnit()} 与家户会话库存判。任一路 {@code < 1} ⇒
 *       SHORTAGE（原因里带该路的名字/数字）；
 *   <li><b>租佃（TENANCY）</b>：自有资产不够目标规模时，按 {@code AssetRule(mode, assetKind)} 的 {@code RentRule}
 *       从同产业、同格、<b>自有自营（operator == owner）</b>且不属于本户的闲置份额里拆分出 {@code OwnershipStake{owner 不变,
 *       operator = 组织者, kind = TENANCY}} —— 只转移使用权，<b>总量不变、不造资产</b>； 缺 AssetRule/缺 RentRule/无闲置份额 ⇒
 *       具名 SHORTAGE；
 *   <li><b>关系生成</b>：新 unit 优先用已有 relation；没有时用该产业 regime 的 {@link RegimeRelations#defaultRelation}
 *       （唯一默认模板拼写点）；regime 未登记或模板里的 cohort 受方在本格解析不出唯一行 ⇒ 退回<b>最小自留关系</b> （空 rules ⇒ 产出全归
 *       operator，与"缺 relation"是既有的等价路径）。租佃产生的租金腿按 {@link RentRule#legs()} 的顺序逐条转成结算侧已认识的 {@code
 *       *_RENT}/{@code OUTPUT_SHARE} 规则；
 *   <li><b>货币租</b>：只生成 {@code FIXED_MONEY_RENT} 规则，钱的实付/欠额由既有 {@code ProductionSettlement}
 *       货币路径按"付方本期可用货币"结（没有可靠钱源 ⇒ 实付 0 + 欠租读数；<b>不铸钱、不落债务</b>）；
 *   <li><b>劳动配额</b>：为新 unit 发一条 {@code HouseholdLaborCommitment{activity = unitId, laborMilli =
 *       scale × laborPerUnit}} （批次取该户已有配额/成员份额里余量最大的；不多发、不超过可支配与批次余量）。
 * </ol>
 *
 * <p>★★ <b>确定性</b>：全程无随机、无时钟、无 UUID；所有遍历序都按稳定 id 排序或来自不可变表的保序迭代；同一入参在同一 状态上重放恒得同一批组织/unit/份额 id（份额
 * id 的 sequence 由 {@link OwnershipStakeBook} 按"同 tuple 已有 id"确定性推出）。
 * 本类是纯协调器单线程阶段：只写调用方交给它的工作副本，不发布、不并行、不跨日持有状态。
 *
 * <p>★ <b>本阶段不做</b>（如实边界）：不做完整 mode 产业模板体系（复用现有 industry）；不发明 IndustryDefinitionView 模板；不做独立的市场
 * 准入判定（"无市场"只作为投入缺失的具名原因出现）；不落租金债务（E4）；不改已有 relation 的规则；已 ACTIVE 的组织逐日幂等跳过。
 */
final class EconomyEnterpriseSettlement {

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

  private EconomyEnterpriseSettlement() {}

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
  record OrganizeResult(ProductionEnterprise enterprise, boolean unitCreated) {

    OrganizeResult {
      Objects.requireNonNull(enterprise, "organization");
    }
  }

  /** 计划里的一条租佃拆分腿（只记"从哪条份额拆多少"，执行期不重算）。 */
  record AssetGrant(AssetShareId source, AssetKind asset, long quantity, ActorRef owner) {

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
  record AllocationPlan(PeopleLotId lot, long laborMilli, long period) {

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
  record LotChoice(PeopleLotId lot, long room) {

    LotChoice {
      Objects.requireNonNull(lot, "LotChoice.lot");
      if (room <= 0L) {
        throw new IllegalArgumentException("LotChoice.room 必须 > 0: " + room);
      }
    }
  }

  /** 生成的关系 + 它的模板来源串（进 ProductionEnterprise.relationTemplateRef）。 */
  record GeneratedRelation(ProductionRules relation, String templateRef) {

    GeneratedRelation {
      Objects.requireNonNull(relation, "GeneratedRelation.relation");
      Objects.requireNonNull(templateRef, "GeneratedRelation.templateRef");
    }
  }

  /** 租佃计划：能拆到的腿 + 拆不到的具名原因（部分可拆 ⇒ 按最紧约束缩到能拆到的规模，不整体失败）。 */
  record TenancyPlan(List<AssetGrant> grants, List<String> reasons) {

    TenancyPlan {
      Objects.requireNonNull(grants, "TenancyPlan.grants");
      Objects.requireNonNull(reasons, "TenancyPlan.reasons");
      grants = List.copyOf(grants);
      reasons = List.copyOf(reasons);
    }
  }

  static Outcome organize(
      ProductionRuntime runtime,
      EconomyDayView base,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      MarketIndex markets,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    return organize(
        runtime,
        base,
        base.classStandings(),
        householdEconomies,
        industries,
        units,
        relations,
        assetShares,
        laborCommitments,
        composition,
        householdGoods,
        markets,
        enterprises,
        new LinkedHashMap<>());
  }

  static Outcome organize(
      ProductionRuntime runtime,
      EconomyDayView base,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      MarketIndex markets,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      LinkedHashMap<ProductionUnitId, ProductionRoster> productionRosters) {
    return EnterpriseOrganizationBook.organize(
        runtime,
        base,
        classMemberships,
        householdEconomies,
        industries,
        units,
        relations,
        assetShares,
        laborCommitments,
        composition,
        householdGoods,
        markets,
        enterprises,
        productionRosters);
  }

  static ProductionRules normalizePayees(
      ProductionRules relation,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HexCoord hex) {
    return EnterpriseStatusBook.normalizePayees(relation, householdEconomies, hex);
  }
}
