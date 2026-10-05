package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>P10.2/P11.3 商人承运与周期结算（架构 §5.4）</b>：
 *
 * <pre>
 * 市场阶段：按 lane 服务半径 + 剩余运力，把一条 lane 的需求按"有效到货费率升序 → organizationId 升序"依次分给多家商号，
 *           每家吃满 capacityPerRound 余量（P11.3 多承运商分摊）；买方 CARRIER_FEE 按承运量比例分别付给 merchant principal
 *           家户；总运力仍不足的部分才 ⇒ freightUncollectedMilli（钱不凭空消失）
 * 周期末  ：运费实收 − porter 工资实付 − upkeep = lastProfit；盈利 capacityPerRound+5（上限 100000）、
 *           亏损 −5（下限 5）；农村商号每活跃轮 +2‰（封顶 100）；结果经 withTradeResult 写回 merchantFirms
 * </pre>
 *
 * <p>★★ <b>与既有承运路径的关系</b>：{@code merchantFirms} 为空时 {@code MarketSettlement} 保持旧行为（第一个有货币账的
 * ORGANIZATION）；非空时改走本类。★ 本类不搬货、不卖买，只做承运与商号财务。
 *
 * <p>★★ <b>upkeep 的口径</b>：城区当量 upkeep（{@code tier.districtUse × MerchantPolicy.UPKEEP_PER_DISTRICT_USE}）
 * 与船畜维护在本批是<b>成本计提</b>（没有可收方主体；若真的扣钱就会让货币凭空消失）。它进 {@code lastProfitMilli} 与
 * {@link OrganizationProfitBook} 的成本，<b>不</b>移动任何余额。这是本批具名收窄（见收口报告）。
 */
public final class MerchantSettlement {

  /** 商人周期日志（settlement 分类：每轮关账的逐商号明细）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  private MerchantSettlement() {}

  /** 盈利/亏损时每周期运力增减（架构 §10：+5/−5）。 */
  public static final long CAPACITY_STEP_PER_ROUND = 5L;

  /** 运力上限（架构 §10：100000）。 */
  public static final long CAPACITY_CEILING = MerchantPolicy.CITY_CAPACITY_CEILING;

  /** 运力下限（架构 §10：5）。 */
  public static final long CAPACITY_FLOOR = MerchantPolicy.CITY_CAPACITY_SHRINK_FLOOR;

  /** 农村累积惩罚步长（‰/活跃轮；架构 §10：+2）。 */
  public static final long RURAL_PENALTY_STEP_PER_ROUND = MerchantPolicy.RURAL_PENALTY_STEP_PER_ROUND;

  /** 农村累积惩罚上限（‰；架构 §10：100）。 */
  public static final long RURAL_PENALTY_CAP_PER_MILLE = MerchantPolicy.RURAL_PENALTY_CAP_PER_MILLE;

  /**
   * ★ <b>船畜维护单价（毫/单位；本批具名缺省值）</b>：海运/畜力的每单位每周期维护。架构 §10 没有给数值，本批取 10 毫/单位
   * （合理量级、可 GM 改；见收口报告的"受影响硬编码字面量"）。
   */
  public static final long SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI = 10L;

  /**
   * ★★ <b>一条承运选择结果（P11.3 起：多承运商按容量分摊）</b>。
   *
   * <p>★★ <b>与 P10.2 旧形状的差异（具名）</b>：{@code select} 不再拿 lane 单价，因此本记录<b>不存</b>单条
   * {@code freightMilli}；运费由 {@code MarketSettlement} 按 lane 名义费率现算各条有效费率下的金额，再按承运量比例分摊并封顶。
   *
   * @param organizationId 商号对应的生产组织
   * @param principalActor 商号 principal 家户 actor（CARRIER_FEE 收款人）
   * @param firm 选中时的商号读数（容量扣减前的快照）
   * @param quantityMilli 本条分到的承运量（毫商品单位）
   * @param cityDiscountPerMille 本条 lane 的城市折扣（‰）
   * @param ruralPenaltyPerMille 本条商号的农村累积惩罚（‰）
   */
  public record CarrierChoice(
      ProductionOrganizationId organizationId,
      ActorRef principalActor,
      MerchantFirm firm,
      long quantityMilli,
      long cityDiscountPerMille,
      long ruralPenaltyPerMille) {

    public CarrierChoice {
      Objects.requireNonNull(organizationId, "organizationId");
      Objects.requireNonNull(principalActor, "principalActor");
      Objects.requireNonNull(firm, "firm");
      if (quantityMilli <= 0L || cityDiscountPerMille < 0L || ruralPenaltyPerMille < 0L) {
        throw new IllegalArgumentException("CarrierChoice 的 quantity/discount/penalty 非法");
      }
    }

    /** 本条在给定 lane 名义费率下的有效到货费率（‰）：{@code max(0, 名义 − 城市折扣 + 农村惩罚)}（P10.2 同一算式）。 */
    public long effectiveRatePerMille(long nominalRatePerMille) {
      if (nominalRatePerMille < 0L) {
        throw new IllegalArgumentException("nominalRatePerMille 不得为负: " + nominalRatePerMille);
      }
      return Math.max(
          0L,
          Math.addExact(
              Math.subtractExact(nominalRatePerMille, cityDiscountPerMille),
              ruralPenaltyPerMille));
    }

    /** P10.2 旧访问器名（旧形状字段叫 {@code quantity}）；语义 = {@link #quantityMilli()}。 */
    public long quantity() {
      return quantityMilli;
    }
  }

  /**
   * ★★ <b>一次 select 的完整结果（P11.3）</b>：分给了哪些商号、各多少、以及没分出去的剩余需求。
   *
   * <p>★★ <b>不变量</b>：{@code Σ choices.quantityMilli + unallocatedMilli == requestedMilli}；{@code unallocatedMilli}
   * 必须由调用方显式处理（{@code MarketSettlement} 记 {@code freightUncollectedMilli}），本类不静默丢。
   *
   * @param choices 已分配条目（按有效到货费率升序 → organizationId 升序）
   * @param requestedMilli 本次请求分配的承运量
   * @param unallocatedMilli 总剩余运力仍不足而未分配的数量
   */
  public record CarrierAllocation(
      List<CarrierChoice> choices, long requestedMilli, long unallocatedMilli) {

    public CarrierAllocation {
      Objects.requireNonNull(choices, "choices");
      choices = List.copyOf(choices);
      if (requestedMilli < 0L || unallocatedMilli < 0L || unallocatedMilli > requestedMilli) {
        throw new IllegalArgumentException(
            "CarrierAllocation 的 requested/unallocated 非法: requested="
                + requestedMilli
                + " unallocated="
                + unallocatedMilli);
      }
      long allocated = 0L;
      for (CarrierChoice choice : choices) {
        allocated = Math.addExact(allocated, choice.quantityMilli());
      }
      if (allocated != requestedMilli - unallocatedMilli) {
        throw new IllegalArgumentException(
            "CarrierAllocation 不守恒: allocated="
                + allocated
                + " requested="
                + requestedMilli
                + " unallocated="
                + unallocatedMilli);
      }
    }

    /** 已分配总量（毫商品单位）= {@code requestedMilli − unallocatedMilli}。 */
    public long allocatedMilli() {
      return requestedMilli - unallocatedMilli;
    }

    /** 需求是否全部有商号承运（自承运也算已分配；自承运是否收钱由调用方按 P10.9 口径处理）。 */
    public boolean fullyAllocated() {
      return unallocatedMilli == 0L;
    }

    /** 一条都没分出去（没有可服务商号 / 全部运力耗尽）。 */
    public boolean isEmpty() {
      return choices.isEmpty();
    }
  }

  /**
   * ★★ <b>一轮市场/一整周期的承运池</b>：持有商号工作副本（容量扣减就地写回），只允许协调器单线程使用。
   */
  public static final class CarrierPool {

    private final Map<ProductionOrganizationId, MerchantFirm> firms;
    private final Map<ProductionOrganizationId, ActorRef> principalByOrganization;

    public CarrierPool(
        Map<ProductionOrganizationId, MerchantFirm> firms,
        Map<ProductionOrganizationId, ProductionOrganization> organizations) {
      this.firms = Objects.requireNonNull(firms, "firms");
      this.principalByOrganization = new LinkedHashMap<>();
      List<ProductionOrganizationId> ids = new ArrayList<>(firms.keySet());
      ids.sort(Comparator.comparing(ProductionOrganizationId::value));
      for (ProductionOrganizationId id : ids) {
        ProductionOrganization organization = organizations.get(id);
        if (organization == null) {
          continue; // 没有组织的商号是坏档：不猜承运人，选商时跳过（结算时具名抛）
        }
        principalByOrganization.put(id, organization.organizer());
      }
    }

    /** 空池（旧路径/worker 本地副本用；不做任何选择）。 */
    public static CarrierPool empty() {
      return new CarrierPool(Map.of(), Map.of());
    }

    public boolean isEmpty() {
      return firms.isEmpty();
    }

    /**
     * ★★ <b>P11.3 多承运商按容量分摊</b>：按"有效到货费率升序 → organizationId 升序"依次取服务商号，每家取
     * {@code min(剩余需求, capacityPerRound − capacityUsedThisRound)}，扣减该商号本轮已用运力，直到需求放完或没有可服务商号。
     * 总可分配量仍不足的部分原样放进 {@code unallocatedMilli}，不静默丢。
     *
     * <p>★ 排序口径保留 P10.2：有效费率 = {@code max(0, nominal − 城市折扣 + 农村惩罚)}；同一 lane 的 nominal 对所有候选相同，
     * 但它参与 {@code max(0,·)} 截断 ⇒ 必须传入才能与旧序逐值一致。
     *
     * @param nominalRatePerMille lane 名义到货费率（‰，{@code MarketTopology.freightPerMilleBetween} 给出的唯一来源）
     */
    public CarrierAllocation select(
        HexCoord from, HexCoord to, long quantityMilli, long nominalRatePerMille) {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      if (nominalRatePerMille < 0L) {
        throw new IllegalArgumentException("nominalRatePerMille 不得为负: " + nominalRatePerMille);
      }
      List<Candidate> candidates = candidatesFor(from, to);
      candidates.sort(
          Comparator.comparingLong(
                  (Candidate candidate) -> candidate.effectiveRatePerMille(nominalRatePerMille))
              .thenComparing(candidate -> candidate.organizationId.value()));
      return allocate(quantityMilli, candidates);
    }

    /**
     * ★ <b>3 参便捷入口（任务书签名）</b>：调用方不知道 lane 名义费率时，按"费率调整量（农村惩罚 − 城市折扣）"升序 →
     * organizationId 升序。nominal 对同一 lane 的所有候选取同一值，因此该序在常规区间（不被 {@code max(0,·)} 截平）与 4 参口径
     * 逐值一致；{@code MarketSettlement} 的实际收费走 4 参版本，以保留 P10.2 的逐值排序。
     */
    public CarrierAllocation select(HexCoord from, HexCoord to, long quantityMilli) {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      List<Candidate> candidates = candidatesFor(from, to);
      candidates.sort(
          Comparator.comparingLong((Candidate candidate) -> candidate.rateAdjustmentPerMille())
              .thenComparing(candidate -> candidate.organizationId.value()));
      return allocate(quantityMilli, candidates);
    }

    /** 池里所有"服务该 lane 且本轮仍有剩余运力"的商号候选（未排序）。 */
    private List<Candidate> candidatesFor(HexCoord from, HexCoord to) {
      List<Candidate> candidates = new ArrayList<>();
      for (Map.Entry<ProductionOrganizationId, MerchantFirm> entry : firms.entrySet()) {
        MerchantFirm firm = entry.getValue();
        ActorRef principal = principalByOrganization.get(entry.getKey());
        if (firm == null || principal == null || !servesLane(firm, from, to)) {
          continue;
        }
        if (remainingCapacityOf(firm) <= 0L) {
          continue;
        }
        candidates.add(
            new Candidate(
                entry.getKey(),
                principal,
                firm,
                cityDiscountPerMille(firm, from, to),
                firm.ruralTradeCostPenaltyPerMille()));
      }
      return candidates;
    }

    /** 按既定顺序贪心分配：每家吃满 min(剩余需求, 本轮剩余运力)；逐条扣减就地写回商号工作副本。 */
    private CarrierAllocation allocate(long quantityMilli, List<Candidate> ordered) {
      if (quantityMilli < 0L) {
        throw new IllegalArgumentException("承运需求不得为负: " + quantityMilli);
      }
      List<CarrierChoice> choices = new ArrayList<>();
      long demandLeft = quantityMilli;
      for (Candidate candidate : ordered) {
        if (demandLeft <= 0L) {
          break;
        }
        long remainingCapacity = remainingCapacityOf(candidate.firm());
        if (remainingCapacity <= 0L) {
          continue;
        }
        long take = Math.min(demandLeft, remainingCapacity);
        if (take <= 0L) {
          continue;
        }
        long used = Math.addExact(candidate.firm().capacityUsedThisRound(), take);
        firms.put(candidate.organizationId(), candidate.firm().withCapacityUsedThisRound(used));
        choices.add(
            new CarrierChoice(
                candidate.organizationId(),
                candidate.principal(),
                candidate.firm(),
                take,
                candidate.cityDiscountPerMille(),
                candidate.ruralPenaltyPerMille()));
        demandLeft -= take;
      }
      return new CarrierAllocation(List.copyOf(choices), quantityMilli, demandLeft);
    }

    private static long remainingCapacityOf(MerchantFirm firm) {
      return Math.max(0L, firm.capacityPerRound() - firm.capacityUsedThisRound());
    }

    private record Candidate(
        ProductionOrganizationId organizationId,
        ActorRef principal,
        MerchantFirm firm,
        long cityDiscountPerMille,
        long ruralPenaltyPerMille) {

      long rateAdjustmentPerMille() {
        return Math.subtractExact(ruralPenaltyPerMille, cityDiscountPerMille);
      }

      long effectiveRatePerMille(long nominalRatePerMille) {
        return Math.max(0L, Math.addExact(nominalRatePerMille, rateAdjustmentPerMille()));
      }
    }
  }

  /** 本周期某商号的运费实收（只从本周期真实 CARRIER_FEE 转移读数取；`to` = principal actor）。 */
  public static long feeRevenueOf(
      OrganizationProfitBook.CycleAccumulator cycle, ActorRef principalActor) {
    long revenue = 0L;
    for (ProductionLedger ledger : cycle.ledgers()) {
      for (io.mosire.simos.economy.api.transfer.Transfer transfer : ledger.transfers()) {
        if (transfer.reason() != io.mosire.simos.economy.api.transfer.TransferReason.CARRIER_FEE) {
          continue;
        }
        if (!transfer.to().equals(principalActor)) {
          continue;
        }
        for (long amount : transfer.money().values()) {
          revenue = Math.addExact(revenue, amount);
        }
      }
    }
    return revenue;
  }

  /**
   * ★★ <b>周期末商号结算</b>：收入 = 本周期 CARRIER_FEE 实收；成本 = porter 工资实付 + upkeep 计提；付不出的工资走
   * {@link DebtContractBook#upsert} 资本化；盈利/亏损与农村惩罚写回 {@code merchantFirms}。
   */
  public static void settleCycle(
      OrganizationProfitBook.CycleAccumulator cycle,
      LinkedHashMap<ProductionOrganizationId, MerchantFirm> firms,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<AssetShareId, AssetShare> assetShares,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      AccountSession accounts,
      Map<HexCoord, Market> markets,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      long day) {
    Objects.requireNonNull(cycle, "cycle");
    Objects.requireNonNull(firms, "firms");
    if (firms.isEmpty() || !cycle.cycleClosed()) {
      return;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("event=MERCHANT_CYCLE_START day={} firms={}", day, firms.size());
    }
    Map<ActorRef, HouseholdId> householdByActor = new LinkedHashMap<>();
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
      householdByActor.put(HouseholdActors.of(household), household);
    }
    List<ProductionOrganizationId> ids = new ArrayList<>(firms.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : ids) {
      MerchantFirm firm = firms.get(organizationId);
      ProductionOrganization organization = organizations.get(organizationId);
      if (firm == null || organization == null) {
        throw new IllegalStateException(
            "merchantFirms 的商号没有对应的 ProductionOrganization（拒绝静默跳过）: " + organizationId);
      }
      ActorRef principalActor = organization.organizer();
      HouseholdId principalHousehold = householdByActor.get(principalActor);
      if (principalHousehold == null) {
        throw new IllegalStateException(
            "商号 principal 不是已登记家户（说不出收款人，拒绝静默丢钱）: " + organizationId + " actor=" + principalActor);
      }
      long revenue = feeRevenueOf(cycle, principalActor);
      List<Porter> porters = portersOf(organization, principalHousehold, laborCommitments, householdEconomies);
      List<Long> porterWeights = new ArrayList<>(porters.size());
      long totalPorterLabor = 0L;
      for (Porter porter : porters) {
        porterWeights.add(porter.laborMilli);
        totalPorterLabor = Math.addExact(totalPorterLabor, porter.laborMilli);
      }
      if (totalPorterLabor <= 0L && !porterWeights.isEmpty()) {
        porterWeights.replaceAll(ignored -> 1L);
        totalPorterLabor = porterWeights.size();
      }
      ProductionRelation relation =
          organization.unitId().isPresent() ? relations.get(organization.unitId().get()) : null;
      Market market = markets.get(firm.homeHex());
      CurrencyId numeraire =
          market != null ? market.numeraire() : firstCurrency(accounts, principalHousehold);
      long wagesDueMoney = dueMoneyWages(relation);
      long wagesPaidMoney = 0L;
      long arrearsMoney = 0L;
      long wagesPaidInKindValue = 0L;
      long arrearsInKindValue = 0L;
      if (wagesDueMoney > 0L && !porters.isEmpty() && numeraire == null) {
        throw new IllegalStateException(
            "商人有应付货币工资但找不到计价币（说不出欠薪币种，拒绝静默丢债）: " + organizationId);
      }
      if (wagesDueMoney > 0L && !porters.isEmpty() && numeraire != null) {
        long[] dueShares = split(porters.size(), wagesDueMoney, porterWeights, totalPorterLabor);
        long available = accountMoney(accounts, principalHousehold, numeraire);
        long paid = Math.min(wagesDueMoney, available);
        long[] paidShares = split(porters.size(), paid, porterWeights, totalPorterLabor);
        for (int i = 0; i < porters.size(); i++) {
          if (paidShares[i] > 0L) {
            moveMoney(accounts, principalHousehold, porters.get(i).household, numeraire, paidShares[i]);
            wagesPaidMoney = Math.addExact(wagesPaidMoney, paidShares[i]);
          }
          long unpaid = dueShares[i] - paidShares[i];
          if (unpaid > 0L) {
            DebtContractBook.upsert(
                debts,
                principalHousehold,
                porters.get(i).household,
                DebtUnit.money(numeraire),
                DebtTerms.legacyDefault(0),
                unpaid,
                day,
                OptionalLong.empty());
            arrearsMoney = Math.addExact(arrearsMoney, unpaid);
          }
        }
      }
      // 实物工资（FIXED_IN_KIND_PER_LABOR）：按现扣口径，付不出也资本化成实物债。
      for (CompensationRule rule : rulesOfType(relation, RuleType.FIXED_IN_KIND_PER_LABOR)) {
        CommodityId commodity = rule.commodity().orElse(null);
        if (commodity == null || porters.isEmpty() || totalPorterLabor <= 0L) {
          continue;
        }
        long due = Math.multiplyExact(rule.fixedAmount(), totalPorterLabor) / 1000L;
        long[] dueShares = split(porters.size(), due, porterWeights, totalPorterLabor);
        long available = accountGoods(accounts, principalHousehold, commodity);
        long paid = Math.min(due, available);
        long[] paidShares = split(porters.size(), paid, porterWeights, totalPorterLabor);
        for (int i = 0; i < porters.size(); i++) {
          if (paidShares[i] > 0L) {
            moveGoods(accounts, principalHousehold, porters.get(i).household, commodity, paidShares[i]);
            wagesPaidInKindValue =
                Math.addExact(
                    wagesPaidInKindValue,
                    goodsValue(paidShares[i], commodity, market));
          }
          long unpaid = dueShares[i] - paidShares[i];
          if (unpaid > 0L) {
            DebtContractBook.upsert(
                debts,
                principalHousehold,
                porters.get(i).household,
                DebtUnit.commodity(commodity),
                DebtTerms.legacyDefault(0),
                unpaid,
                day,
                OptionalLong.empty());
            arrearsInKindValue =
                Math.addExact(arrearsInKindValue, goodsValue(unpaid, commodity, market));
          }
        }
      }
      long upkeep = upkeepOf(firm, organization, assetShares);
      long costPaid =
          Math.addExact(
              Math.addExact(wagesPaidMoney, wagesPaidInKindValue),
              upkeep);
      long arrears = Math.addExact(arrearsMoney, arrearsInKindValue);
      long profit = revenue - costPaid;

      // 运力：盈利 +5（上限 100000）、亏损 −5（下限 5）。
      long capacity = firm.capacityPerRound();
      if (profit > 0L) {
        capacity = Math.min(CAPACITY_CEILING, capacity + CAPACITY_STEP_PER_ROUND);
      } else if (profit < 0L) {
        capacity = Math.max(CAPACITY_FLOOR, capacity - CAPACITY_STEP_PER_ROUND);
      }
      long ruralPenalty = firm.ruralTradeCostPenaltyPerMille();
      boolean active = revenue > 0L || firm.capacityUsedThisRound() > 0L;
      if (!firm.homeIsCity() && active) {
        ruralPenalty = Math.min(RURAL_PENALTY_CAP_PER_MILLE, ruralPenalty + RURAL_PENALTY_STEP_PER_ROUND);
      }
      MerchantFirm updated =
          firm.withTradeResult(revenue, upkeep, profit)
              .withCapacityDelta(capacity - firm.capacityPerRound())
              .withRuralTradeCostPenaltyPerMille(ruralPenalty)
              .withRoundReset();
      firms.put(organizationId, updated);

      cycle.recordMerchantWages(organizationId, Math.addExact(wagesPaidMoney, wagesPaidInKindValue));
      cycle.recordMerchantUpkeep(organizationId, upkeep);
      cycle.recordMerchantArrears(organizationId, arrears);
      cycle.recordMerchantLabor(organizationId, totalPorterLabor);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "event=MERCHANT_FIRM_CYCLE day={} firm={} principal={} revenue={} wagesPaidMoney={} wagesPaidInKindValue={} upkeep={} arrears={} profit={} capacityBefore={} capacityAfter={} porters={}",
            day,
            organizationId.value(),
            principalHousehold.value(),
            revenue,
            wagesPaidMoney,
            wagesPaidInKindValue,
            upkeep,
            arrears,
            profit,
            firm.capacityPerRound(),
            capacity,
            porters.size());
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("event=MERCHANT_CYCLE_END day={} firms={}", day, firms.size());
    }
  }

  // ── 商人细节 ──────────────────────────────────────────────────────────────────────────────

  private static boolean servesLane(MerchantFirm firm, HexCoord from, HexCoord to) {
    if (firm.serviceRadiusHex() <= 0L) {
      return false; // 0 = 不按半径服务；MerchantFirm 没有显式 lane 字段（收口报告具名缺口）
    }
    return firm.homeHex().distanceTo(from) <= firm.serviceRadiusHex()
        && firm.homeHex().distanceTo(to) <= firm.serviceRadiusHex();
  }

  private static long cityDiscountPerMille(MerchantFirm firm, HexCoord from, HexCoord to) {
    MerchantPolicy policy =
        new MerchantPolicy(
            firm.tier(),
            firm.homeHex(),
            firm.homeIsCity(),
            firm.capacityPerRound(),
            Math.max(0L, firm.capacityPerRound() - firm.capacityUsedThisRound()),
            null,
            null,
            firm.serviceRadiusHex(),
            firm.ruralTradeCostPenaltyPerMille(),
            firm.capacityUsedThisRound());
    return policy.cityDiscountForLane(from, to);
  }

  private static long upkeepOf(
      MerchantFirm firm,
      ProductionOrganization organization,
      Map<AssetShareId, AssetShare> assetShares) {
    long district = Math.multiplyExact((long) firm.tier().districtUse(), MerchantPolicy.UPKEEP_PER_DISTRICT_USE);
    long assets = 0L;
    for (AssetShareId shareId : organization.assetSources()) {
      AssetShare share = assetShares.get(shareId);
      if (share == null || share.quantity() <= 0L) {
        continue;
      }
      if (share.asset() == AssetKind.SHIP || share.asset() == AssetKind.CATTLE) {
        assets =
            Math.addExact(
                assets, Math.multiplyExact(share.quantity(), SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI));
      }
    }
    return Math.addExact(district, assets);
  }

  /** 一个 porter 家户与本周期实际劳动（laborSources 只提供身份；实际劳动只从 HouseholdLaborCommitment 取）。 */
  private record Porter(HouseholdId household, long laborMilli) {}

  private static List<Porter> portersOf(
      ProductionOrganization organization,
      HouseholdId principal,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    LinkedHashMap<HouseholdId, Long> laborByHousehold = new LinkedHashMap<>();
    if (organization.unitId().isPresent()) {
      String activity = organization.unitId().get().value();
      List<HouseholdLaborCommitment> matchingLaborCommitments = new ArrayList<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        if (laborCommitment.activity().equals(activity) && laborCommitment.laborMilli() > 0L) {
          matchingLaborCommitments.add(laborCommitment);
        }
      }
      matchingLaborCommitments.sort(Comparator.comparing(allocation -> allocation.id().value()));
      for (HouseholdLaborCommitment laborCommitment : matchingLaborCommitments) {
        if (laborCommitment.household().equals(principal) || !householdEconomies.containsKey(laborCommitment.household())) {
          continue;
        }
        laborByHousehold.merge(laborCommitment.household(), laborCommitment.laborMilli(), Math::addExact);
      }
    }
    if (laborByHousehold.isEmpty()) {
      for (HouseholdId source : organization.laborSources()) {
        if (!source.equals(principal) && householdEconomies.containsKey(source)) {
          laborByHousehold.putIfAbsent(source, 0L);
        }
      }
    }
    List<HouseholdId> households = new ArrayList<>(laborByHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    List<Porter> porters = new ArrayList<>();
    for (HouseholdId household : households) {
      porters.add(new Porter(household, laborByHousehold.get(household)));
    }
    return porters;
  }

  private static long dueMoneyWages(ProductionRelation relation) {
    long due = 0L;
    for (CompensationRule rule : rulesOfType(relation, RuleType.FIXED_MONEY_WAGE)) {
      due = Math.addExact(due, rule.fixedAmount());
    }
    return due;
  }

  private static List<CompensationRule> rulesOfType(ProductionRelation relation, RuleType type) {
    if (relation == null) {
      return List.of();
    }
    List<CompensationRule> rules = new ArrayList<>();
    for (CompensationRule rule : relation.rules()) {
      if (rule.type() == type) {
        rules.add(rule);
      }
    }
    return rules;
  }

  // ── 比例切分 / 账户移动 ───────────────────────────────────────────────────────────────────

  private static long[] split(int size, long total, List<Long> weights, long weightSum) {
    long[] shares = new long[size];
    if (size == 0 || total <= 0L) {
      return shares;
    }
    if (weightSum <= 0L) {
      shares[0] = total;
      return shares;
    }
    return io.mosire.simos.util.economy.ProportionalSplit.byDenominator(
        total, toArray(weights), weightSum);
  }

  private static long[] toArray(List<Long> values) {
    long[] array = new long[values.size()];
    for (int i = 0; i < values.size(); i++) {
      array[i] = values.get(i);
    }
    return array;
  }

  private static long accountMoney(
      AccountSession accounts, HouseholdId household, CurrencyId currency) {
    return accounts.householdMoney().getOrDefault(household, Map.of()).getOrDefault(currency, 0L);
  }

  private static long accountGoods(
      AccountSession accounts, HouseholdId household, CommodityId commodity) {
    return accounts.householdGoods().getOrDefault(household, Map.of()).getOrDefault(commodity, 0L);
  }

  private static void moveMoney(
      AccountSession accounts, HouseholdId source, HouseholdId target, CurrencyId currency, long amount) {
    if (amount <= 0L) {
      return;
    }
    Map<CurrencyId, Long> sourceMoney = accounts.householdMoney().get(source);
    Map<CurrencyId, Long> targetMoney = accounts.householdMoney().get(target);
    if (sourceMoney == null || targetMoney == null) {
      throw new IllegalStateException("商人工资付款账户不存在（拒绝静默丢钱）: " + source + " → " + target);
    }
    long balance = sourceMoney.getOrDefault(currency, 0L);
    if (amount > balance) {
      throw new IllegalStateException("商人工资超过 principal 余额（拒绝透支）: need=" + amount + " balance=" + balance);
    }
    LinkedHashMap<CurrencyId, Long> nextSource = new LinkedHashMap<>(sourceMoney);
    if (balance - amount <= 0L) {
      nextSource.remove(currency);
    } else {
      nextSource.put(currency, balance - amount);
    }
    accounts.householdMoney().put(source, nextSource);
    LinkedHashMap<CurrencyId, Long> nextTarget = new LinkedHashMap<>(targetMoney);
    nextTarget.merge(currency, amount, Math::addExact);
    accounts.householdMoney().put(target, nextTarget);
  }

  private static void moveGoods(
      AccountSession accounts, HouseholdId source, HouseholdId target, CommodityId commodity, long amount) {
    if (amount <= 0L) {
      return;
    }
    Map<CommodityId, Long> sourceGoods = accounts.householdGoods().get(source);
    Map<CommodityId, Long> targetGoods = accounts.householdGoods().get(target);
    if (sourceGoods == null || targetGoods == null) {
      throw new IllegalStateException("商人实物工资账户不存在（拒绝静默丢货）: " + source + " → " + target);
    }
    long balance = sourceGoods.getOrDefault(commodity, 0L);
    if (amount > balance) {
      throw new IllegalStateException("商人实物工资超过 principal 库存（拒绝透支）: need=" + amount + " balance=" + balance);
    }
    LinkedHashMap<CommodityId, Long> nextSource = new LinkedHashMap<>(sourceGoods);
    if (balance - amount <= 0L) {
      nextSource.remove(commodity);
    } else {
      nextSource.put(commodity, balance - amount);
    }
    accounts.householdGoods().put(source, nextSource);
    LinkedHashMap<CommodityId, Long> nextTarget = new LinkedHashMap<>(targetGoods);
    nextTarget.merge(commodity, amount, Math::addExact);
    accounts.householdGoods().put(target, nextTarget);
  }

  private static long goodsValue(long quantityMilli, CommodityId commodity, Market market) {
    if (market == null || commodity == null || quantityMilli <= 0L) {
      return 0L;
    }
    long price = market.priceOf(commodity);
    if (price <= 0L) {
      return 0L; // 明确 0 价（免费）⇒ 实物工资折 0；不参与货币价值守恒
    }
    // ★★ 2026-10-09：原为裸 `quantity * price`，量级一大就静默回绕成负数 ⇒ 精确乘法 + 除法。
    return Math.multiplyExact(quantityMilli, price)
        / io.mosire.simos.util.economy.EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
  }

  private static CurrencyId firstCurrency(AccountSession accounts, HouseholdId household) {
    CurrencyId smallest = null;
    for (CurrencyId currency : accounts.householdMoney().getOrDefault(household, Map.of()).keySet()) {
      if (smallest == null || currency.value().compareTo(smallest.value()) < 0) {
        smallest = currency;
      }
    }
    return smallest;
  }

  private static List<HouseholdId> sortedHouseholds(Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    List<HouseholdId> keys = new ArrayList<>(householdEconomies.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }
}
