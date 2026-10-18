package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ProductionProspectBook.industryHex;
import static io.mosire.simos.economy.time.ProspectArithmetic.infeasible;
import static io.mosire.simos.economy.time.ProspectArithmetic.joinReasons;
import static io.mosire.simos.economy.time.ProspectArithmetic.relationFor;
import static io.mosire.simos.economy.time.ProspectArithmetic.scaledNetPerLabor;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.time.ExpectedProfitBook.Prospect;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class WageProspectBook {
  private WageProspectBook() {}

  // ── 工资位置（WAGE_EARNER / DIRECT_LABORER / PROVIDER）──────────────────────────────────────

  static Prospect wageProspect(
      EconomyDayView base,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      ProductionModeId modeId,
      ProductionRole position,
      HexCoord hex,
      IndustryDefinitionView industry,
      MarketTable market,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      AssetOccupancyBudget assetBudget,
      WageTermsSource wageTermsSource) {
    long horizonDays = industry.cycleDays();
    long laborPerUnit = industry.recipe().laborPerUnit();
    Employer employer = employerOf(modeId, industry, hex, household, units, assetBudget);
    if (employer == null || employer.scale() < 1L) {
      return infeasible(
          household,
          modeId,
          position.id(),
          hex,
          industry.id(),
          "NO_EMPLOYER:" + industry.id().value());
    }
    long laborScale =
        laborPerUnit <= 0L
            ? Long.MAX_VALUE
            : householdEconomy.participationAdjustedLaborMilli() / laborPerUnit;
    if (laborScale < 1L) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), "NO_LABOR:" + household.value());
    }
    long scale = Math.min(employer.scale(), laborScale);
    if (scale < 1L) {
      return infeasible(household, modeId, position.id(), hex, industry.id(), "SCALE_ZERO");
    }
    long laborNeed =
        laborPerUnit <= 0L
            ? 0L
            : Math.multiplyExact(Math.multiplyExact(laborPerUnit, scale), horizonDays);
    ProductionProcess employerUnit = units.get(employer.unitId());
    List<ProductionRoleDefinition> moneyRoles = moneyWageRoles(industry);
    long income = 0L;
    boolean priceMissing = false;
    List<String> notes = new ArrayList<>();
    if (!moneyRoles.isEmpty()) {
      // ★★★ D-032 D3 §5.2（W1）：MONEY_WAGE worker 的预计应收 = quote × 预计可工作劳动 × cycleDays / 1000。
      //   先做 payer（雇主）级地点预检；>1 地点 ⇒ 候选 skip/具名拒绝，不进入收入计算。
      WageEstimate wageEstimate =
          estimateWorkerWageIncome(
              base,
              industry,
              employer,
              employerUnit,
              hex,
              householdEconomy,
              laborPerUnit,
              horizonDays,
              moneyRoles,
              units,
              wageTermsSource);
      if (wageEstimate.rejection().isPresent()) {
        return infeasible(
            household, modeId, position.id(), hex, industry.id(), wageEstimate.rejection().get());
      }
      income = wageEstimate.amountMilli();
      notes.addAll(wageEstimate.notes());
    } else {
      // ★ 旧非名册路径仍保留可计价非货币收入（in-kind/share）；但 FIXED_MONEY_WAGE 绝不回读（D3 §2.2.1）。
      ProductionRules relation =
          relationFor(
              relations,
              employer.unitId(),
              modeId,
              industry,
              householdEconomy,
              employer.operator());
      notes.add("wage-quote-missing");
      if (relation == null) {
        notes.add("NO_RELATION");
      } else {
        for (CompensationRule rule : relation.rules()) {
          if (!isWorkerPayee(rule.recipient(), household, householdEconomy, hex)) {
            continue;
          }
          switch (rule.type()) {
            case FIXED_MONEY_WAGE -> notes.add("LEGACY_MONEY_WAGE_IGNORED");
            case FIXED_IN_KIND_PER_LABOR -> {
              CommodityId commodity = rule.commodity().orElse(null);
              long price = market == null || commodity == null ? 0L : market.bidPriceOf(commodity);
              if (price <= 0L) {
                priceMissing = true;
                continue;
              }
              long due = SubsistenceObligation.perLaborDue(laborNeed, rule.fixedAmount());
              income =
                  Math.addExact(
                      income,
                      Math.multiplyExact(due, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
            }
            case OUTPUT_SHARE -> {
              // ★ 具名近似：分账池要"本受方劳动 ÷ Σ劳动"，而本计算器拿不到该 unit 的全部劳动
              //   ⇒ 按"这份产出全归本受方"的上界计入，并标 WAGE_SHARE_ESTIMATE（不静默）。
              long employerOutputValue = grossOutputValue(industry, employer.scale(), market);
              long share = Math.multiplyExact(employerOutputValue, rule.ratePerMille()) / 1000L;
              income = Math.addExact(income, share);
              notes.add("WAGE_SHARE_ESTIMATE");
            }
            default -> {
              // 其余规则（租金/自留）不是劳动者的收入来源，跳过。
            }
          }
        }
      }
    }
    if (priceMissing) {
      notes.add("NO_PRICE");
    }
    return new Prospect(
        household,
        modeId,
        position.id(),
        hex,
        Optional.of(industry.id()),
        scale,
        Map.of(),
        Map.of(),
        Map.of(),
        income,
        0L,
        0L,
        0L,
        0L,
        income,
        laborNeed,
        scaledNetPerLabor(income, laborNeed),
        false,
        true,
        joinReasons(notes, List.of("WAGE_POSITION")));
  }

  /**
   * ★★★ <b>W7-fix（2026-10-11，用户裁定 v3.28 §7）：跑商可行性的**新判据 = 劳动 + 工具**</b> ——
   * 「**能不能先把牲口要求取消掉啊？目前是在跑测试，算作工具需求得了**」。
   *
   * <pre>
   * 判据 = 该户**本轮能投的劳动投入 &gt; 0** ∧ 该户**手上的工具 &gt; 0**（会话商品账）
   * 口径 = J-B 的既有算式「运力 = f(劳动投入, 工具消耗)」的两个操作数（{@link MerchantCapacity#capacityMilliOf}）
   * 缺项 = 具名不可行：{@code NO_MERCHANT_CAPACITY:no-labor} / {@code NO_MERCHANT_CAPACITY:no-tool-input}
   * </pre>
   *
   * <p>★★ <b>它取代了什么（W7 的 `no-carrier-asset` 已删，§一.11）</b>：W7 在这一处要求"该户**自备 CATTLE &gt; 0**"。
   * 实测真世界（W7d 测试批）：全世界只有 {@code 0_0}/{@code 0_2} 两户持 CATTLE，非城市格既买不到也养不出 ⇒ {@code
   * MERCHANT_TRADE_INDUSTRY_CREATED} = **0**、非城市格一户新跑商都没有 —— 那道闸把本批要开的路整个堵死。 用户据此裁定：**CATTLE
   * 不再是跑商的可行性门槛，也不再是运力的来源**；跑商的家户**凭"劳动 + 工具"就能跑商**。
   *
   * <p>★ <b>真正决定成败的量仍是执行期与结算期的那一份</b>：本判据只回答"这一户**有没有**跑商的投入"（可行性）， 规模/收益/能不能接到活由 {@link
   * MerchantCapacityPool#providerCapacityMilli} 与市场轮决定（fail-closed： 这里说不可行 ⇒ 一定不可行）。
   *
   * @param accounts 会话账户（读该户的 {@code tool} 存量；读不到 ⇒ 0 ⇒ 具名 {@code no-tool-input}） // ── D-032 D3
   *     §5.1（W1）：工资前置估值 / payer 地点预检 ───────────────────────────────────────
   *     <p>/** ★★★ <b>一个 MONEY_WAGE 角色的报价与目标劳动</b>（D-032 D3 §2.1/§5.1）。
   *     <p>量纲：{@code wagePerLaborMilli} = 毫货币/小时；{@code targetLaborMilliPerDay} = 毫小时/日。 ★ 0
   *     报价合法（U18）， 缺报价/缺目标用 {@link WageTermsResult#rejection()} 具名，绝不用 0 冒充。
   */
  private record WageTerms(long wagePerLaborMilli, long targetLaborMilliPerDay) {
    private WageTerms {
      if (wagePerLaborMilli < 0L) {
        throw new IllegalArgumentException(
            "WageTerms.wagePerLaborMilli 不得为负（0 是合法报价）: " + wagePerLaborMilli);
      }
      if (targetLaborMilliPerDay < 0L) {
        throw new IllegalArgumentException(
            "WageTerms.targetLaborMilliPerDay 不得为负: " + targetLaborMilliPerDay);
      }
    }

    private boolean zeroQuote() {
      return wagePerLaborMilli == 0L;
    }
  }

  /**
   * 报价读取结论：成功给 {@link WageTerms}，失败给稳定拒绝词（{@code wage-quote-missing:*}/{@code
   * wage-target-missing:*}）。
   */
  private record WageTermsResult(Optional<WageTerms> terms, Optional<String> rejection) {
    private WageTermsResult {
      Objects.requireNonNull(terms, "terms");
      Objects.requireNonNull(rejection, "rejection");
    }

    static WageTermsResult ok(WageTerms terms) {
      return new WageTermsResult(Optional.of(terms), Optional.empty());
    }

    static WageTermsResult rejected(String reason) {
      return new WageTermsResult(Optional.empty(), Optional.of(reason));
    }
  }

  /**
   * ★★★ <b>W1 工资估值的只读锚快照</b>（D-032 P4c3）。
   *
   * <p>报价唯一来自 {@code productionWageAnchors}；目标唯一走 role 的 {@code standardLaborMilliPerCycle /
   * cycleDays} 工程默认。缺锚/缺目标都具名拒绝，不回读 posting/default、不按 0 兜底。
   */
  static final class WageTermsSource {

    private final Map<WageAnchorKey, ProductionWageAnchor> anchors;

    private WageTermsSource(Map<WageAnchorKey, ProductionWageAnchor> anchors) {
      this.anchors = Objects.requireNonNull(anchors, "anchors（无锚请用空表）");
    }

    static WageTermsSource of(EconomyDayView view) {
      Objects.requireNonNull(view, "view");
      return new WageTermsSource(new WageAnchorBook(view).snapshot());
    }

    private WageTermsResult lookup(
        IndustryDefinitionView plan, ProductionRoleId role, MarketZoneId zone) {
      if (zone == null) {
        return WageTermsResult.rejected("wage-quote-missing:" + role.value());
      }
      ProductionWageAnchor anchor = anchors.get(new WageAnchorKey(zone, plan.id(), role));
      if (anchor == null) {
        return WageTermsResult.rejected("wage-quote-missing:" + role.value());
      }
      long target = 0L;
      for (ProductionRoleDefinition candidate : plan.roles()) {
        if (candidate.id().equals(role)) {
          target =
              WageQuoteBridge.wageTargetLaborMilliPerDay(
                  0L, candidate, plan.cycleDays());
          break;
        }
      }
      if (target <= 0L) {
        return WageTermsResult.rejected("wage-target-missing:" + role.value());
      }
      return WageTermsResult.ok(new WageTerms(anchor.wagePerLaborMilli(), target));
    }
  }

  /** 工资估值结论：金额（毫货币/周期）+ 具名拒绝 + 读数标记（如 {@code WAGE_QUOTE_ZERO}）。 */
  record WageEstimate(long amountMilli, Optional<String> rejection, List<String> notes) {
    WageEstimate {
      if (amountMilli < 0L) {
        throw new IllegalArgumentException("WageEstimate.amountMilli 不得为负: " + amountMilli);
      }
      Objects.requireNonNull(rejection, "rejection");
      Objects.requireNonNull(notes, "notes");
      notes = List.copyOf(notes);
    }

    static WageEstimate none() {
      return new WageEstimate(0L, Optional.empty(), List.of());
    }

    static WageEstimate rejected(String reason) {
      return new WageEstimate(0L, Optional.of(reason), List.of());
    }
  }

  /** 方案内的 MONEY_WAGE 角色，按稳定角色 id 升序（D3 E10；不靠定义表插入序）。 */
  static List<ProductionRoleDefinition> moneyWageRoles(IndustryDefinitionView plan) {
    List<ProductionRoleDefinition> roles = new ArrayList<>();
    for (ProductionRoleDefinition role : plan.roles()) {
      if (role.payment() instanceof RolePayment.MoneyWage) {
        roles.add(role);
      }
    }
    roles.sort(Comparator.comparing(role -> role.id().value()));
    return roles;
  }

  /** 候选格的显式地点（与 {@code ProductionProcess.location} 同形：{@code <q>_<r>}）。 */
  static ProductionLocation locationOfHex(HexCoord hex) {
    return new ProductionLocation(IndustryHexKeys.hexKey(hex.q(), hex.r()));
  }

  /**
   * ★ 把实例的显式地点读回 {@link HexCoord}（只服务"该实例所在市场区"的只读派生）。
   *
   * <p>★ fail-closed：不是规范 {@code <q>_<r>} 形状 ⇒ {@code null}（调用方按"缺市场区"具名，不猜一个格子）。
   */
  static HexCoord hexOfLocation(ProductionLocation location) {
    if (location == null || !location.isSpecified()) {
      return null;
    }
    String value = location.value();
    int separator = value.indexOf('_');
    if (separator <= 0
        || separator == value.length() - 1
        || value.indexOf('_', separator + 1) >= 0) {
      return null;
    }
    try {
      return new HexCoord(
          Integer.parseInt(value.substring(0, separator)),
          Integer.parseInt(value.substring(separator + 1)));
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  /**
   * ★★★ <b>当前 revision 中该 payer 的全部已有 settlement location</b>（D-032 D3 §6.9.1/F-01）。
   *
   * <p>汇总口径（只读、<b>不扫账户</b>）：
   *
   * <ol>
   *   <li>{@code operator == payer} 的全部 unit 的 {@link ProductionProcess#location()}；
   *   <li>{@code productionRosters} 的 organizer == payer 的 unit 地点（unit 不在工作副本时退回方案地点）。
   * </ol>
   *
   * <p>★ 本方法只返回"已有"；候选地点由调用方另加，再交 {@link WagePayerLocationPrecheck#standard()}。禁止调用方只传候选单点。
   */
  static Set<ProductionLocation> existingSettlementLocations(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units, ActorRef payer) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(payer, "payer");
    LinkedHashSet<ProductionLocation> locations = new LinkedHashSet<>();
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit != null && unit.operator().equals(payer)) {
        locations.add(unit.location());
      }
    }
    List<ProductionUnitId> rosterIds = new ArrayList<>(base.productionRosters().keySet());
    rosterIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : rosterIds) {
      ProductionRoster roster = base.productionRosters().get(unitId);
      if (roster == null || !HouseholdActors.of(roster.organizerHousehold()).equals(payer)) {
        continue;
      }
      ProductionProcess unit = units.get(unitId);
      if (unit != null) {
        locations.add(unit.location());
      } else {
        ProductionLocation.ofPlan(roster.plan()).ifPresent(locations::add);
      }
    }
    return Collections.unmodifiableSet(locations);
  }

  /**
   * ★★ <b>W1/W2b/W3 共享的"已有 settlement location"只读构造入口</b>（D-032 D3 §6.9.1/F-01）。
   *
   * <p>{@code rosterIncomes} 的完整重载与后续 W2b/W3 装配点都应经本方法构造 {@code existingSettlementLocations}，
   * 而不是自己只传候选 unit 的地点。口径见 {@link #existingSettlementLocations(EconomyDayView, Map, ActorRef)}。
   */
  static Set<ProductionLocation> existingSettlementLocationsOf(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units, ActorRef payer) {
    return existingSettlementLocations(base, units, payer);
  }

  /**
   * ★★★ <b>W1 雇主候选：全部 MONEY_WAGE 角色的周期预计工资成本</b>（D-032 D3 §5.1/A1）。
   *
   * <pre>
   * expectedWageCostMilli = Σ_role floor(quote.wagePerLaborMilli × targetLaborMilliPerDay × cycleDays / 1000)
   * </pre>
   *
   * <p>★ 先做 payer 级地点预检（existing ∪ candidate）；&gt;1 settlement location ⇒ {@code
   * wage-payer-location-unsupported}，零成本/零净额、不调用任何库存 lookup。★ 缺 quote/target ⇒ 具名拒绝；显式 0 则成本 0 但保留
   * {@code WAGE_QUOTE_ZERO} 读数。★ 整数精确、向下取整；溢出 ⇒ {@code wage-quote-overflow} fail-closed。
   */
  static WageEstimate estimateEmployerWageCost(
      EconomyDayView base,
      IndustryDefinitionView plan,
      ProductionProcess existingUnit,
      HexCoord candidateHex,
      ActorRef payer,
      Map<ProductionUnitId, ProductionProcess> units,
      long cycleDays,
      WageTermsSource source) {
    List<ProductionRoleDefinition> moneyRoles = moneyWageRoles(plan);
    if (moneyRoles.isEmpty()) {
      return WageEstimate.none();
    }
    ProductionLocation candidateLocation =
        existingUnit == null ? locationOfHex(candidateHex) : existingUnit.location();
    WagePayerLocationSnapshot snapshot =
        new WagePayerLocationSnapshot(
            payer, existingSettlementLocations(base, units, payer), Set.of(candidateLocation));
    if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
        == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
      return WageEstimate.rejected("wage-payer-location-unsupported");
    }
    HexCoord zoneHex = existingUnit == null ? candidateHex : hexOfLocation(existingUnit.location());
    MarketZoneId zone =
        zoneHex == null ? null : MarketTables.marketIdAt(base, zoneHex).orElse(null);
    long total = 0L;
    List<String> notes = new ArrayList<>();
    for (ProductionRoleDefinition role : moneyRoles) {
      WageTermsResult terms = source.lookup(plan, role.id(), zone);
      if (terms.rejection().isPresent()) {
        return WageEstimate.rejected(terms.rejection().get());
      }
      WageTerms value = terms.terms().orElseThrow();
      long amount;
      try {
        amount =
            wageAmountMilli(value.wagePerLaborMilli(), value.targetLaborMilliPerDay(), cycleDays);
        total = Math.addExact(total, amount);
      } catch (ArithmeticException overflow) {
        return WageEstimate.rejected("wage-quote-overflow");
      }
      if (value.zeroQuote()) {
        notes.add("WAGE_QUOTE_ZERO");
      }
    }
    notes.add("WAGE_COST_INCLUDED");
    return new WageEstimate(total, Optional.empty(), notes);
  }

  /**
   * ★★★ <b>W1 雇工候选：MONEY_WAGE worker 的周期预计应收</b>（D-032 D3 §5.2/A1）。
   *
   * <pre>
   * expectedWorkableLaborMilliPerCycle
   *   = min(本户每日可分配劳动, targetLaborMilliPerDay, 该角色在共享 unit cap 下的日容量) × cycleDays
   * wageIncomeMilli = floor(quote.wagePerLaborMilli × expectedWorkableLaborMilliPerCycle / 1000)
   * </pre>
   *
   * <p>★ 先做 payer（雇主）级地点预检；&gt;1 地点 ⇒ 候选 skip/具名拒绝。★ 本批不做匹配算法：多 MONEY_WAGE 角色时取稳定 id 升序第一个并具名
   * {@code WAGE_ROLE_FIRST_ONLY}，不虚构多角色同时受雇。
   */
  static WageEstimate estimateWorkerWageIncome(
      EconomyDayView base,
      IndustryDefinitionView plan,
      Employer employer,
      ProductionProcess employerUnit,
      HexCoord candidateHex,
      HouseholdEconomy householdEconomy,
      long laborPerUnit,
      long cycleDays,
      List<ProductionRoleDefinition> moneyRoles,
      Map<ProductionUnitId, ProductionProcess> units,
      WageTermsSource source) {
    ProductionLocation candidateLocation =
        employerUnit == null ? locationOfHex(candidateHex) : employerUnit.location();
    WagePayerLocationSnapshot snapshot =
        new WagePayerLocationSnapshot(
            employer.operator(),
            existingSettlementLocations(base, units, employer.operator()),
            Set.of(candidateLocation));
    if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
        == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
      return WageEstimate.rejected("wage-payer-location-unsupported");
    }
    HexCoord zoneHex = employerUnit == null ? candidateHex : hexOfLocation(employerUnit.location());
    MarketZoneId zone =
        zoneHex == null ? null : MarketTables.marketIdAt(base, zoneHex).orElse(null);
    ProductionRoleDefinition role = moneyRoles.get(0);
    WageTermsResult terms = source.lookup(plan, role.id(), zone);
    if (terms.rejection().isPresent()) {
      return WageEstimate.rejected(terms.rejection().get());
    }
    WageTerms value = terms.terms().orElseThrow();
    long employerCapacityPerDay =
        laborPerUnit <= 0L ? Long.MAX_VALUE : Math.multiplyExact(employer.scale(), laborPerUnit);
    long workablePerDay =
        Math.min(
            householdEconomy.participationAdjustedLaborMilli(),
            Math.min(value.targetLaborMilliPerDay(), employerCapacityPerDay));
    long amount;
    try {
      amount = wageAmountMilli(value.wagePerLaborMilli(), workablePerDay, cycleDays);
    } catch (ArithmeticException overflow) {
      return WageEstimate.rejected("wage-quote-overflow");
    }
    List<String> notes = new ArrayList<>();
    if (value.zeroQuote()) {
      notes.add("WAGE_QUOTE_ZERO");
    }
    notes.add("WAGE_INCOME_ESTIMATE");
    if (moneyRoles.size() > 1) {
      notes.add("WAGE_ROLE_FIRST_ONLY");
    }
    return new WageEstimate(amount, Optional.empty(), notes);
  }

  /**
   * ★ 精确周期工资：{@code floor(quote × targetLaborMilliPerDay × cycleDays / 1000)}。
   *
   * <p>★ 用 {@link BigInteger} 做乘加（D3 §2.4 的精确乘加口径），避免中间溢出；结果超出 {@code long} 抛 {@link
   * ArithmeticException}（调用方折 {@code wage-quote-overflow}）。
   */
  static long wageAmountMilli(long wagePerLaborMilli, long laborMilliPerDay, long cycleDays) {
    BigInteger numerator =
        BigInteger.valueOf(wagePerLaborMilli)
            .multiply(BigInteger.valueOf(laborMilliPerDay))
            .multiply(BigInteger.valueOf(cycleDays));
    return numerator.divide(BigInteger.valueOf(1000L)).longValueExact();
  }

  /** 雇主读数：unit 身份 + 经营者 + 产能规模（规模 = 该 unit 的 {@code capacityScaleOf}）。 */
  private record Employer(ProductionUnitId unitId, ActorRef operator, long scale) {
    private Employer {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(operator, "operator");
    }
  }

  /**
   * 本 mode 在本格现有的最大雇主：<b>只读当天工作副本 {@code units}</b>，不再读 {@code
   * base.productionOrganizations()}（周期关账时 base 是本周期开始时的 revision，enterprise.modeId 是过期读数）。
   *
   * <p>筛选判据（唯一拼写点 = 本方法 + {@link #employerModeRank}）：
   *
   * <ol>
   *   <li>{@code unit.industry().equals(industry.id())}（同产业；产业 id 自带格键，再用 {@link #industryHex} 判
   *       {@code industryHex(unit.industry()).equals(hex)}，不内联拼串）；
   *   <li>{@code unit.operator() != HouseholdActors.of(household)}（自己的 unit 不是自己的雇主）；
   *   <li>mode/产业兼容性按 {@link #employerModeRank} 分档（2 &gt; 1 &gt; 0）：有高秩候选时只在最高秩里选。
   * </ol>
   *
   * <p>规模 = {@link ProductionProcessBook#capacityScaleOf(ProductionProcess, IndustryDefinitionView,
   * Map)}；规模 &lt; 1 的候选先跳过 （没有真实产能可雇人，且 wageProspect 也会以 scale&lt;1 判 NO_EMPLOYER）；同秩同规模按 {@link
   * ProductionUnitId#value()} 升序取第一个（确定性）。
   *
   * <p>★ 已知近似（如实记）：同一 farm 产业下 wage/family 两种 unit 的产业 regime 都是 feudal ⇒ target=wage_farm 时
   * family_farm 的 unit 也落第 1 档（"同产业即同一劳动市场"）；mode 精确到人的劳动配额/关系结算留给后续阶段。
   */
  static Employer employerOf(
      ProductionModeId modeId,
      IndustryDefinitionView industry,
      HexCoord hex,
      HouseholdId household,
      Map<ProductionUnitId, ProductionProcess> units,
      AssetOccupancyBudget assetBudget) {
    ActorRef self = HouseholdActors.of(household);
    Employer best = null;
    int bestRank = -1;
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit == null || !unit.industry().equals(industry.id()) || unit.operator().equals(self)) {
        continue;
      }
      HexCoord unitHex = industryHex(unit.industry());
      if (unitHex == null || !unitHex.equals(hex)) {
        continue;
      }
      int rank = employerModeRank(unit, modeId, industry);
      if (assetBudget.rejectionOf(unitId).isPresent()) {
        continue; // ★★ R2：共享 scope 的 unit 不是可用雇主。
      }
      long scale =
          ProductionProcessBook.capacityScaleOf(assetBudget.allocationOf(unit.id()), industry);
      if (scale < 1L) {
        continue; // 规模 0 的 unit 不是真实雇主（wageProspect 同样以 scale<1 判 NO_EMPLOYER），不让它挡住低秩的可用雇主
      }
      // unitIds 已按 id 升序：同秩同规模时先到者即"id 升序第一个"，不再改 best。
      if (best == null || rank > bestRank || (rank == bestRank && scale > best.scale())) {
        best = new Employer(unitId, unit.operator(), scale);
        bestRank = rank;
      }
    }
    return best;
  }

  /**
   * 雇主候选的 mode 兼容档（越大越优先）：
   *
   * <ol>
   *   <li>2 = 直接命中：{@code unit.modeKey()} 等于"本 mode 组织阶段/迁移新建"的 key （{@link
   *       EconomyEnterpriseSettlement#MODE_KEY_PREFIX} + modeId.value()）；
   *   <li>1 = 产业 regime 相符：目标 mode 有默认 regime（{@link RegimeOperators#defaultRegimeForMode}）且等于该
   *       unit 所在产业的 {@code regime().value()}；
   *   <li>0 = 同产业回退：mode 未登记默认制度或制度不符 ⇒ 仍可在同产业 unit 里找工作（保持"同产业即可"语义）。
   * </ol>
   *
   * <p>★ 旧档/主 unit 的 {@code modeKey} 是产业 id（{@code farm@hex}）⇒ 落第 1 档；候选预设的 {@code
   * candidateId@version} 无法反解 modeId ⇒ 按产业 regime 落第 1/0 档（具名近似，不猜版本内容）。
   */
  static int employerModeRank(
      ProductionProcess unit, ProductionModeId modeId, IndustryDefinitionView industry) {
    if (unit.modeKey().equals(EconomyEnterpriseSettlement.MODE_KEY_PREFIX + modeId.value())) {
      return 2;
    }
    Optional<String> wanted = RegimeOperators.defaultRegimeForMode(modeId);
    if (wanted.isPresent() && wanted.get().equals(industry.regime().value())) {
      return 1;
    }
    return 0;
  }

  // ── 价 / 量 / 收益小工具 ───────────────────────────────────────────────────────────────────

  /** 产出的毛值（毫钱）：{@code Σ outputPerUnit × scale × bidPrice}（商品单位 × 规模 × 毫钱/单位）。 */
  static long grossOutputValue(IndustryDefinitionView industry, long scale, MarketTable market) {
    long total = 0L;
    for (Map.Entry<CommodityId, Long> output : industry.recipe().outputPerUnit().entrySet()) {
      if (output.getValue() <= 0L || market == null) {
        continue;
      }
      long price = market.bidPriceOf(output.getKey());
      if (price <= 0L) {
        continue;
      }
      total =
          Math.addExact(
              total, Math.multiplyExact(Math.multiplyExact(output.getValue(), scale), price));
    }
    return total;
  }

  static boolean isWorkerPayee(
      Payee recipient, HouseholdId household, HouseholdEconomy householdEconomy, HexCoord hex) {
    if (recipient instanceof Payee.ToActor toActor) {
      return toActor.actor().equals(HouseholdActors.of(household));
    }
    if (recipient instanceof Payee.ToHousehold toHousehold) {
      return toHousehold.household().equals(household);
    }
    if (recipient instanceof Payee.ToCohort toCohort) {
      return toCohort.cohort().hex().equals(hex)
          && toCohort.cohort().residence() == householdEconomy.view().residence()
          && toCohort.cohort().stratum().equals(householdEconomy.view().stratum());
    }
    return false;
  }
}
