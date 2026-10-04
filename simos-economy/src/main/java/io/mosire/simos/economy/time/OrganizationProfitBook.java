package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>P10.2 周期真实利润汇总簿（架构 §4.2 / §5.2 的 ⑦）</b>：只从本周期真实发生额汇总，<b>不引入任何外生 π</b>。
 *
 * <pre>
 * 收入 revenue = 产出货款实收（MARKET_TRADE 货币腿、按卖方=组织/家户主体归集）
 *             + 运费实收（CARRIER_FEE 货币腿，付给 merchant principal）
 *             + 服务收入（本批 0；没有第三种服务收费路径）
 * 成本 costPaid = 投入实扣（ledger.inputs 按 industry → 经营组织，用本地 ref 价折毫）
 *             + 生产损耗/维护（ledger.losses 同路，折旧与饲料的实物损耗按本地 ref 价折毫）
 *             + 关系实付租/工资（RELATION_PAYMENT 转移腿；货币按计价币、实物按本地 ref 价折毫）
 *             + 商人 upkeep（MerchantSettlement 显式记录）
 *             + 利息实付（本批恒 0：利息并入本金，没有"付息"这条腿；见收口报告）
 * 欠款 arrears = 本周期 RuleSettlement.owed() 中归属于本组织 unit 的部分（单列，不混进成本）
 * 劳动 labor   = 本周期实际投入劳动（关账 unit 的 cycleLaborMilli，关账日结算前抓取）
 * </pre>
 *
 * <p>★★ <b>真实来源</b>：{@link ProductionLedger#transfers()} / {@link ProductionLedger#inputs()} / {@link
 * ProductionLedger#losses()} / {@link ProductionLedger#ruleSettlements()}（逐日累加）+ 关账 unit 的周期劳动/投入快照。
 * MarketReport 只作旁证，不作为金额来源（逐 unit 成交归属不足时宁缺勿造，见收口报告）。
 *
 * <p>★★ <b>货币口径</b>：每个组织按它所在格市场的 {@link Market#numeraire()} 计价；该格没有市场时只累计运费/货款腿里
 * <b>出现的币种</b>（混合币种不求和，取份额最大的？不 —— 本实现取 <b>规范串最小的币种</b>，确定性；缺口写入报告）。
 */
public final class OrganizationProfitBook {

  private OrganizationProfitBook() {}

  /** 一个生产组织（= 一个 mode/hex/家户的生产身份）的本周期真实利润读数。 */
  public record OrganizationProfit(
      ProductionOrganizationId organizationId,
      ProductionModeId modeId,
      Optional<ProductionUnitId> unitId,
      HouseholdId householdId,
      HexCoord hex,
      long revenueMilli,
      long costPaidMilli,
      long arrearsMilli,
      long netMilli,
      long laborMilli,
      long netPerLaborMilli) {

    public OrganizationProfit {
      Objects.requireNonNull(organizationId, "organizationId");
      Objects.requireNonNull(modeId, "modeId");
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(householdId, "householdId");
      Objects.requireNonNull(hex, "hex");
      if (revenueMilli < 0L || costPaidMilli < 0L || arrearsMilli < 0L) {
        throw new IllegalArgumentException(
            "OrganizationProfit 的 revenue/costPaid/arrears 不得为负: "
                + revenueMilli
                + "/"
                + costPaidMilli
                + "/"
                + arrearsMilli);
      }
      if (laborMilli < 0L) {
        throw new IllegalArgumentException("OrganizationProfit.laborMilli 不得为负: " + laborMilli);
      }
      long expectedNet = revenueMilli - costPaidMilli;
      if (netMilli != expectedNet) {
        throw new IllegalArgumentException(
            "OrganizationProfit.netMilli 必须逐值等于 revenue − costPaid: "
                + netMilli
                + " != "
                + expectedNet);
      }
      long expectedPerLabor = netMilli / Math.max(1L, laborMilli);
      if (netPerLaborMilli != expectedPerLabor) {
        throw new IllegalArgumentException(
            "OrganizationProfit.netPerLaborMilli 必须逐值等于 net ÷ max(1, labor): "
                + netPerLaborMilli
                + " != "
                + expectedPerLabor);
      }
    }
  }

  /** 一个 (mode, hex) 的汇总键（目标排序的 (hex, mode) 规范串由 {@link #canonical()} 给出）。 */
  public record ModeHex(ProductionModeId modeId, HexCoord hex) implements Comparable<ModeHex> {

    public ModeHex {
      Objects.requireNonNull(modeId, "modeId");
      Objects.requireNonNull(hex, "hex");
    }

    /** 规范串：{@code mode@q_r}（mode 与 hex 的确定性组合，用于 tie-break）。 */
    public String canonical() {
      return modeId.value() + "@" + hex.q() + "_" + hex.r();
    }

    @Override
    public int compareTo(ModeHex other) {
      return canonical().compareTo(other.canonical());
    }
  }

  /** 汇总结果（不可变；同输入同态恒逐值相同）。 */
  public record Book(
      Map<ProductionOrganizationId, OrganizationProfit> byOrganization,
      Map<ModeHex, Long> netPerLaborByModeHex,
      Map<ModeHex, Long> laborByModeHex) {

    public Book {
      Objects.requireNonNull(byOrganization, "byOrganization");
      Objects.requireNonNull(netPerLaborByModeHex, "netPerLaborByModeHex");
      Objects.requireNonNull(laborByModeHex, "laborByModeHex");
      byOrganization = Collections.unmodifiableMap(new LinkedHashMap<>(byOrganization));
      netPerLaborByModeHex =
          Collections.unmodifiableMap(new LinkedHashMap<>(netPerLaborByModeHex));
      laborByModeHex = Collections.unmodifiableMap(new LinkedHashMap<>(laborByModeHex));
    }

    /** 某个 (mode, hex) 是否真的有本期读数（没有 ⇒ false，调用方不得把它当成 0 收益目标）。 */
    public boolean hasReading(ProductionModeId modeId, HexCoord hex) {
      return laborByModeHex.containsKey(new ModeHex(modeId, hex));
    }

    /** 某个 (mode, hex) 的单位劳动净收益；无读数 ⇒ 0（调用方先用 {@link #hasReading} 判）。 */
    public long netPerLabor(ProductionModeId modeId, HexCoord hex) {
      return netPerLaborByModeHex.getOrDefault(new ModeHex(modeId, hex), 0L);
    }

    /** 某个 (mode, hex) 的本期总劳动。 */
    public long labor(ProductionModeId modeId, HexCoord hex) {
      return laborByModeHex.getOrDefault(new ModeHex(modeId, hex), 0L);
    }
  }

  /**
   * ★★ <b>关账 unit 的周期事实</b>（在 {@code EconomySettlement} 把 unit 的周期状态清零<b>之前</b>抓取）。
   *
   * @param laborMilli 本周期实际投入劳动（{@code unit.cycleLaborMilli + 当日劳动}）
   * @param inputUsedMilli 本周期实际扣到的投入（毫单位、按商品；关账清零前抓取）
   */
  public record CloseFact(
      ProductionUnitId unitId,
      IndustryId industry,
      ActorRef operator,
      HexCoord hex,
      String modeKey,
      long laborMilli,
      Map<CommodityId, Long> inputUsedMilli) {

    public CloseFact {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(industry, "industry");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(modeKey, "modeKey");
      Objects.requireNonNull(inputUsedMilli, "inputUsedMilli");
      if (laborMilli < 0L) {
        throw new IllegalArgumentException("CloseFact.laborMilli 不得为负: " + laborMilli);
      }
      inputUsedMilli = Collections.unmodifiableMap(new LinkedHashMap<>(inputUsedMilli));
    }
  }

  /**
   * ★★ <b>周期累加器</b>（{@link EconomyDayStepper} 持有，逐日喂、关账日汇总后清）。
   *
   * <p>★ 它是瞬态、进程内、协调器单线程；不进 {@code EconomyData}/变更集/Codec。
   */
  public static final class CycleAccumulator {

    private final List<ProductionLedger> ledgers = new ArrayList<>();
    private final List<MarketReport> marketReports = new ArrayList<>();
    private final List<CloseFact> closeFacts = new ArrayList<>();
    private final LinkedHashMap<ProductionOrganizationId, Long> merchantWagesPaid = new LinkedHashMap<>();
    private final LinkedHashMap<ProductionOrganizationId, Long> merchantUpkeep = new LinkedHashMap<>();
    private final LinkedHashMap<ProductionOrganizationId, Long> merchantArrears = new LinkedHashMap<>();
    private final LinkedHashMap<ProductionOrganizationId, Long> merchantLabor = new LinkedHashMap<>();
    private boolean cycleClosed;

    /** 逐日喂入当天账本（含市场报告；可空）。 */
    public void recordDay(long day, ProductionLedger ledger, MarketReport report) {
      Objects.requireNonNull(ledger, "ledger");
      if (day < 1L) {
        throw new IllegalArgumentException("recordDay 的 day 必须 ≥ 1: " + day);
      }
      ledgers.add(ledger);
      if (report != null) {
        marketReports.add(report);
      }
    }

    /** 关账日喂入本周期关账 unit 的快照（在 unit 周期状态清零前抓）。 */
    public void recordCloseFacts(long day, List<CloseFact> facts) {
      Objects.requireNonNull(facts, "facts");
      if (day < 1L) {
        throw new IllegalArgumentException("recordCloseFacts 的 day 必须 ≥ 1: " + day);
      }
      closeFacts.clear();
      for (CloseFact fact : facts) {
        closeFacts.add(Objects.requireNonNull(fact, "CloseFact"));
      }
      cycleClosed = true;
    }

    /** 本周期是否真的关过账。 */
    public boolean cycleClosed() {
      return cycleClosed;
    }

    /** 只读账本列表（保序）。 */
    public List<ProductionLedger> ledgers() {
      return Collections.unmodifiableList(ledgers);
    }

    /** 本周期逐轮市场报告（有开市的日子才有一条；保序）。 */
    public List<MarketReport> marketReports() {
      return Collections.unmodifiableList(marketReports);
    }

    /** 关账 unit 快照（只读）。 */
    public List<CloseFact> closeFacts() {
      return Collections.unmodifiableList(closeFacts);
    }

    /** 商人结算后写入：本周期实际付出的 porter 工资（毫计价货币）。 */
    public void recordMerchantWages(ProductionOrganizationId organizationId, long wagesPaidMilli) {
      if (wagesPaidMilli != 0L) {
        merchantWagesPaid.merge(requireOrg(organizationId), wagesPaidMilli, Math::addExact);
      }
    }

    /** 商人结算后写入：本周期 upkeep（毫计价货币；本批是非现金成本计提，见 MerchantSettlement）。 */
    public void recordMerchantUpkeep(ProductionOrganizationId organizationId, long upkeepMilli) {
      if (upkeepMilli != 0L) {
        merchantUpkeep.merge(requireOrg(organizationId), upkeepMilli, Math::addExact);
      }
    }

    /** 商人结算后写入：付不出而资本化的欠薪本金（毫计价货币）。 */
    public void recordMerchantArrears(ProductionOrganizationId organizationId, long arrearsMilli) {
      if (arrearsMilli != 0L) {
        merchantArrears.merge(requireOrg(organizationId), arrearsMilli, Math::addExact);
      }
    }

    /** 商人结算后写入：porter 实际投入劳动。 */
    public void recordMerchantLabor(ProductionOrganizationId organizationId, long laborMilli) {
      if (laborMilli != 0L) {
        merchantLabor.merge(requireOrg(organizationId), laborMilli, Math::addExact);
      }
    }

    public long merchantWagesOf(ProductionOrganizationId organizationId) {
      return merchantWagesPaid.getOrDefault(organizationId, 0L);
    }

    public long merchantUpkeepOf(ProductionOrganizationId organizationId) {
      return merchantUpkeep.getOrDefault(organizationId, 0L);
    }

    public long merchantArrearsOf(ProductionOrganizationId organizationId) {
      return merchantArrears.getOrDefault(organizationId, 0L);
    }

    public long merchantLaborOf(ProductionOrganizationId organizationId) {
      return merchantLabor.getOrDefault(organizationId, 0L);
    }

    /** 进入下一周期：清空全部逐周期读数（ledger / close facts / 商人读数）。 */
    public void resetForNextCycle() {
      ledgers.clear();
      marketReports.clear();
      closeFacts.clear();
      merchantWagesPaid.clear();
      merchantUpkeep.clear();
      merchantArrears.clear();
      merchantLabor.clear();
      cycleClosed = false;
    }

    private static ProductionOrganizationId requireOrg(ProductionOrganizationId organizationId) {
      return Objects.requireNonNull(organizationId, "organizationId");
    }
  }

  /**
   * ★★ <b>汇总一本 {@link Book}</b>：只读入参，不改任何状态；同输入恒同输出。
   */
  public static Book collect(
      CycleAccumulator cycle,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<HexCoord, Market> markets,
      AccountSession accounts) {
    Objects.requireNonNull(cycle, "cycle");
    Objects.requireNonNull(organizations, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(accounts, "accounts");

    Map<ActorRef, HouseholdId> householdByActor = new LinkedHashMap<>();
    for (HouseholdId household : sortedHouseholds(rows)) {
      householdByActor.put(io.mosire.simos.economy.api.cohort.HouseholdActors.of(household), household);
    }
    // 组织按 household 归集：生产运行时 organizer = 该家户 actor（E2 的唯一拼写点）。
    Map<HouseholdId, ProductionOrganization> orgByHousehold = new LinkedHashMap<>();
    Map<ProductionUnitId, ProductionOrganization> orgByUnit = new LinkedHashMap<>();
    List<ProductionOrganizationId> orderedOrgIds = new ArrayList<>(organizations.keySet());
    orderedOrgIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId orgId : orderedOrgIds) {
      ProductionOrganization organization = organizations.get(orgId);
      if (organization == null) {
        continue;
      }
      HouseholdId household = householdOfActor(organization.organizer(), rows, householdByActor);
      if (household != null) {
        orgByHousehold.putIfAbsent(household, organization);
      }
      organization
          .unitId()
          .ifPresent(unitId -> orgByUnit.putIfAbsent(unitId, organization));
    }

    Map<ProductionOrganizationId, long[]> acc = new LinkedHashMap<>(); // [revenue, cost, arrears, labor]
    for (ProductionOrganizationId orgId : orderedOrgIds) {
      acc.put(orgId, new long[4]);
    }
    Map<ProductionOrganizationId, HouseholdId> householdOfOrg = new LinkedHashMap<>();
    Map<ProductionOrganizationId, HexCoord> hexOfOrg = new LinkedHashMap<>();
    Map<ProductionOrganizationId, ProductionUnitId> unitOfOrg = new LinkedHashMap<>();
    for (ProductionOrganizationId orgId : orderedOrgIds) {
      ProductionOrganization organization = organizations.get(orgId);
      if (organization == null) {
        continue;
      }
      HouseholdId household = householdOfActor(organization.organizer(), rows, householdByActor);
      if (household == null) {
        continue;
      }
      HexCoord hex = hexOfOrganization(organization, units, rows, household);
      if (hex == null) {
        continue;
      }
      householdOfOrg.put(orgId, household);
      hexOfOrg.put(orgId, hex);
      organization.unitId().ifPresent(unitId -> unitOfOrg.put(orgId, unitId));
    }

    // ── 收入/成本：逐日转移腿 + 实物投入/损耗 + 欠款读数 ─────────────────────────────
    for (ProductionLedger ledger : cycle.ledgers()) {
      for (Transfer transfer : ledger.transfers()) {
        HouseholdId fromHousehold = householdByActor.get(transfer.from());
        HouseholdId toHousehold = householdByActor.get(transfer.to());
        ProductionOrganization fromOrg = fromHousehold == null ? null : orgByHousehold.get(fromHousehold);
        ProductionOrganization toOrg = toHousehold == null ? null : orgByHousehold.get(toHousehold);
        if (transfer.reason() == TransferReason.MARKET_TRADE
            || transfer.reason() == TransferReason.CARRIER_FEE) {
          if (toOrg != null) {
            addRevenue(acc, toOrg.id(), transfer.money(), currencyOf(hexOfOrg.get(toOrg.id()), markets));
          }
        } else if (transfer.reason() == TransferReason.RELATION_PAYMENT) {
          if (fromOrg != null) {
            addCost(acc, fromOrg.id(), transfer.money(), currencyOf(hexOfOrg.get(fromOrg.id()), markets));
            addCostGoods(acc, fromOrg.id(), transfer.goods(), hexOfOrg.get(fromOrg.id()), markets);
          }
        }
      }
      // 生产损耗：账本只有"逐 industry"的实物量（没有逐 unit 拆分）⇒ 按同期该 industry 各 unit 的真实劳动
      // 份额分摊（floor + 最大余数，确定性；见收口报告的"逐 unit 归属不足"具名缺口）。
      for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.losses().entrySet()) {
        attributeLosses(acc, entry.getKey(), entry.getValue(), units, orgByUnit, hexOfOrg, markets, cycle);
      }
      // 欠款：payer 组织 × 它的 unit 的活动；单列，不混进成本。
      for (ProductionLedger.RuleSettlement settlement : ledger.ruleSettlements()) {
        if (settlement.owed() <= 0L) {
          continue;
        }
        HouseholdId payerHousehold = householdByActor.get(settlement.payer());
        ProductionOrganization payerOrg =
            payerHousehold == null ? null : orgByHousehold.get(payerHousehold);
        if (payerOrg == null) {
          continue;
        }
        long value =
            settlement.currency().isPresent()
                ? settlement.owed()
                : goodsValue(settlement.owed(), settlement.commodity().orElse(null), hexOfOrg.get(payerOrg.id()), markets);
        addArrears(acc, payerOrg.id(), value);
      }
    }
    // ── 关账 unit 的劳动与投入（unit 周期状态被清零前抓取；投入按 ref 价与 ledger.inputs 同口径）────
    for (CloseFact fact : cycle.closeFacts()) {
      ProductionOrganization organization = orgByUnit.get(fact.unitId());
      if (organization == null) {
        continue;
      }
      long[] row = acc.get(organization.id());
      if (row == null) {
        continue;
      }
      row[3] = Math.addExact(row[3], fact.laborMilli());
      // 投入实扣：unit 的周期累计投入（关账清零前抓的真实量）× 该组织所在格 ref 价。
      for (Map.Entry<CommodityId, Long> input : fact.inputUsedMilli().entrySet()) {
        if (input.getValue() > 0L) {
          row[1] =
              Math.addExact(
                  row[1], goodsValue(input.getValue(), input.getKey(), fact.hex(), markets));
        }
      }
    }
    // ── 商人结算读数（工资实付 / upkeep / 欠薪资本化 / porter 劳动）──────────────────────
    for (ProductionOrganizationId orgId : orderedOrgIds) {
      long[] row = acc.get(orgId);
      if (row == null) {
        continue;
      }
      row[1] = Math.addExact(row[1], cycle.merchantWagesOf(orgId));
      row[1] = Math.addExact(row[1], cycle.merchantUpkeepOf(orgId));
      row[2] = Math.addExact(row[2], cycle.merchantArrearsOf(orgId));
      row[3] = Math.addExact(row[3], cycle.merchantLaborOf(orgId));
    }

    // ── 组装逐组织读数 + (mode, hex) 汇总 ──────────────────────────────────────────
    LinkedHashMap<ProductionOrganizationId, OrganizationProfit> byOrganization =
        new LinkedHashMap<>();
    LinkedHashMap<ModeHex, long[]> byModeHex = new LinkedHashMap<>(); // [net, labor]
    for (ProductionOrganizationId orgId : orderedOrgIds) {
      ProductionOrganization organization = organizations.get(orgId);
      if (organization == null || !householdOfOrg.containsKey(orgId)) {
        continue;
      }
      long[] row = acc.get(orgId);
      long revenue = row[0];
      long cost = row[1];
      long arrears = row[2];
      long labor = row[3];
      long net = revenue - cost;
      long perLabor = net / Math.max(1L, labor);
      OrganizationProfit profit =
          new OrganizationProfit(
              orgId,
              organization.modeId(),
              organization.unitId(),
              householdOfOrg.get(orgId),
              hexOfOrg.get(orgId),
              revenue,
              cost,
              arrears,
              net,
              labor,
              perLabor);
      byOrganization.put(orgId, profit);
      ModeHex key = new ModeHex(organization.modeId(), hexOfOrg.get(orgId));
      long[] aggregate = byModeHex.computeIfAbsent(key, ignored -> new long[2]);
      aggregate[0] = Math.addExact(aggregate[0], net);
      aggregate[1] = Math.addExact(aggregate[1], labor);
    }
    LinkedHashMap<ModeHex, Long> perLaborByModeHex = new LinkedHashMap<>();
    LinkedHashMap<ModeHex, Long> laborByModeHex = new LinkedHashMap<>();
    for (Map.Entry<ModeHex, long[]> entry : byModeHex.entrySet()) {
      long net = entry.getValue()[0];
      long labor = entry.getValue()[1];
      perLaborByModeHex.put(entry.getKey(), net / Math.max(1L, labor));
      laborByModeHex.put(entry.getKey(), labor);
    }
    return new Book(byOrganization, perLaborByModeHex, laborByModeHex);
  }

  /** 组织缺 unit/行时回退到它经营主体的居住格（hex 是收益读数的维度，不能为空）。 */
  private static HexCoord hexOfOrganization(
      ProductionOrganization organization,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, ClassRow> rows,
      HouseholdId household) {
    if (organization.unitId().isPresent()) {
      ProductionUnit unit = units.get(organization.unitId().get());
      if (unit != null) {
        Optional<String> hexKey = IndustryHexKeys.hexKeyOf(unit.industry());
        if (hexKey.isPresent()) {
          int separator = hexKey.get().lastIndexOf('_');
          try {
            return new HexCoord(
                Integer.parseInt(hexKey.get().substring(0, separator)),
                Integer.parseInt(hexKey.get().substring(separator + 1)));
          } catch (RuntimeException ignored) {
            // 落回家户居住格（不猜坐标）
          }
        }
      }
    }
    ClassRow row = rows.get(household);
    return row == null ? null : row.view().hex();
  }

  private static HouseholdId householdOfActor(
      ActorRef actor, Map<HouseholdId, ClassRow> rows, Map<ActorRef, HouseholdId> householdByActor) {
    HouseholdId direct = householdByActor.get(actor);
    if (direct != null) {
      return direct;
    }
    // 旧路径兜底：actor 是 HOUSEHOLD 但不是本表键（不应发生；不猜则返回 null）
    if (actor.kind() == io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
      try {
        HouseholdId parsed =
            io.mosire.simos.economy.api.cohort.HouseholdActors.householdOf(actor);
        return rows.containsKey(parsed) ? parsed : null;
      } catch (RuntimeException ignored) {
        return null;
      }
    }
    return null;
  }

  private static List<HouseholdId> sortedHouseholds(Map<HouseholdId, ClassRow> rows) {
    List<HouseholdId> keys = new ArrayList<>(rows.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  private static void addRevenue(
      Map<ProductionOrganizationId, long[]> acc,
      ProductionOrganizationId orgId,
      Map<CurrencyId, Long> money,
      CurrencyId numeraire) {
    long[] row = acc.get(orgId);
    if (row == null || money.isEmpty()) {
      return;
    }
    // 逐币种读取、全部计入（跨区成交的计价币可能不是卖方本格 numeraire；跨币种求和的量纲缺口见收口报告）。
    for (long amount : money.values()) {
      row[0] = Math.addExact(row[0], amount);
    }
  }

  private static void addCost(
      Map<ProductionOrganizationId, long[]> acc,
      ProductionOrganizationId orgId,
      Map<CurrencyId, Long> money,
      CurrencyId numeraire) {
    long[] row = acc.get(orgId);
    if (row == null || money.isEmpty()) {
      return;
    }
    for (long amount : money.values()) {
      row[1] = Math.addExact(row[1], amount);
    }
  }

  private static void addCostGoods(
      Map<ProductionOrganizationId, long[]> acc,
      ProductionOrganizationId orgId,
      Map<CommodityId, Long> goods,
      HexCoord hex,
      Map<HexCoord, Market> markets) {
    long[] row = acc.get(orgId);
    if (row == null || goods.isEmpty()) {
      return;
    }
    for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
      row[1] = Math.addExact(row[1], goodsValue(leg.getValue(), leg.getKey(), hex, markets));
    }
  }

  private static void addArrears(
      Map<ProductionOrganizationId, long[]> acc, ProductionOrganizationId orgId, long value) {
    long[] row = acc.get(orgId);
    if (row != null && value != 0L) {
      row[2] = Math.addExact(row[2], value);
    }
  }

  /**
   * 把逐 industry 的实物损耗按该 industry 各 unit 的周期劳动份额分摊给组织（单位数/劳动都取不到时按 unit id 均分）。
   * 这是"账本没有逐 unit 损耗归属"时唯一可核的分摊维；不按产出价值反推（那会再引一次价格）。
   */
  private static void attributeLosses(
      Map<ProductionOrganizationId, long[]> acc,
      IndustryId industry,
      Map<CommodityId, Long> goods,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionOrganization> orgByUnit,
      Map<ProductionOrganizationId, HexCoord> hexOfOrg,
      Map<HexCoord, Market> markets,
      CycleAccumulator cycle) {
    if (goods == null || goods.isEmpty()) {
      return;
    }
    List<ProductionUnitId> industryUnits = new ArrayList<>();
    for (Map.Entry<ProductionUnitId, ProductionUnit> entry : units.entrySet()) {
      if (entry.getValue().industry().equals(industry) && orgByUnit.containsKey(entry.getKey())) {
        industryUnits.add(entry.getKey());
      }
    }
    industryUnits.sort(Comparator.comparing(ProductionUnitId::value));
    if (industryUnits.isEmpty()) {
      return;
    }
    long[] weights = new long[industryUnits.size()];
    long totalWeight = 0L;
    for (int i = 0; i < industryUnits.size(); i++) {
      long labor = 0L;
      for (CloseFact fact : cycle.closeFacts()) {
        if (fact.unitId().equals(industryUnits.get(i))) {
          labor = fact.laborMilli();
          break;
        }
      }
      weights[i] = Math.max(1L, labor);
      totalWeight = Math.addExact(totalWeight, weights[i]);
    }
    for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
      long[] shares = ProportionalSplit.byDenominator(leg.getValue(), weights, totalWeight);
      for (int i = 0; i < industryUnits.size(); i++) {
        if (shares[i] <= 0L) {
          continue;
        }
        ProductionOrganization org = orgByUnit.get(industryUnits.get(i));
        long[] row = acc.get(org.id());
        if (row == null || !hexOfOrg.containsKey(org.id())) {
          continue;
        }
        row[1] =
            Math.addExact(
                row[1], goodsValue(shares[i], leg.getKey(), hexOfOrg.get(org.id()), markets));
      }
    }
  }

  private static long goodsValue(
      long quantityMilli, CommodityId commodity, HexCoord hex, Map<HexCoord, Market> markets) {
    if (commodity == null || hex == null || quantityMilli <= 0L) {
      return 0L;
    }
    Market market = markets.get(hex);
    if (market == null) {
      return 0L; // 没有本地 ref 价 ⇒ 不折毫（宁缺勿造；缺口写收口报告）
    }
    long price = market.priceOf(commodity);
    if (price <= 0L) {
      return 0L;
    }
    return quantityMilli * price / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
  }

  private static CurrencyId currencyOf(HexCoord hex, Map<HexCoord, Market> markets) {
    Market market = hex == null ? null : markets.get(hex);
    return market == null ? null : market.numeraire();
  }

}
