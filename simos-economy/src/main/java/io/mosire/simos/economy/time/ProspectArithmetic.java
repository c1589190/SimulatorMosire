package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ExpectedProfitBook.PER_LABOR_SCALE;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.time.ExpectedProfitBook.Prospect;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ProspectArithmetic {
  private ProspectArithmetic() {}

  /** 显式关系优先；缺则按产业 regime 推制度默认；制度未登记/拿不到 ⇒ null（调用方标 NO_RELATION，rent=0）。 */
  static ProductionRules relationFor(
      Map<ProductionUnitId, ProductionRules> relations,
      ProductionUnitId unitId,
      ProductionModeId modeId,
      IndustryDefinitionView industry,
      HouseholdEconomy householdEconomy,
      ActorRef operator) {
    ProductionRules explicit = relations.get(unitId);
    if (explicit != null) {
      return explicit;
    }
    // ★★ P5a：农村只有一个 grain_farm。它没有租佃/雇农/家庭农场制度模板；默认关系 = 空规则 + SELF
    //   （产出全归 operator），雇工只走 MONEY_WAGE 工资锚。不得回读产业 regime=feudal 的旧庄园模板，
    //   也不得因 mode 未登记而落到 TENANT/SERF。
    if (DefaultProductionModes.GRAIN_FARM.equals(modeId)) {
      return new ProductionRules(
          unitId, operator, new Payee.ToActor(operator), List.of(), operator, LaborSource.SELF);
    }
    // ★ 制度默认的 regime 先取 mode 的默认制度；mode 未登记（如 merchant）或该 regime 无默认表 ⇒ 退回产业 regime。
    Optional<String> modeRegime = RegimeOperators.defaultRegimeForMode(modeId);
    boolean useModeRegime =
        modeRegime.isPresent() && RegimeRelations.registered().containsKey(modeRegime.get());
    RegimeId regime = useModeRegime ? new RegimeId(modeRegime.get()) : industry.regime();
    try {
      return RegimeRelations.defaultRelation(
          regime, unitId, industry.id(), operator, Set.of(householdEconomy.view().residence()));
    } catch (IllegalArgumentException ignored) {
      return null; // 未登记的制度（如 merchant）⇒ 不猜一套规则
    }
  }

  /**
   * 一条关系里"从本经营者净收益里扣出去"的地租/分成/工资义务（毫钱）。
   *
   * <p>★ 规则口径与 {@code ProductionSettlement} 对齐：固定租是每周期一笔；分成按池 × 率；按劳动给养按 {@link
   * SubsistenceObligation#perLaborDue}。★ 受方 == 本家户自己（同 actor / 同 household / 同 cohort）的规则是自付，
   * 直接跳过（与 E2 的 {@code normalizePayees} 同一口径）。
   */
  static Obligations obligationsOf(
      ProductionRules relation,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      HexCoord hex,
      MarketTable market,
      Map<CommodityId, Long> revenueByCommodity,
      Map<CommodityId, Long> inputCostByCommodity,
      long totalRevenue,
      long totalInputCost,
      long laborNeed,
      boolean includeWages) {
    if (relation == null) {
      return new Obligations(0L, 0L, List.of());
    }
    long rent = 0L;
    long wages = 0L;
    boolean priceMissing = false;
    List<String> notes = new ArrayList<>();
    // ★ OUTPUT_SHARE 的池是**逐商品**的（结算公式 {@code pool_c × rate × weight}）：这里按 (pool 层, 商品)
    //   维护 [池额, 已分出去, 劳动加权的最大率]。
    //   · Weight.NONE 的规则在结算里按 priority/表序读"已付"、付款上限咬合 ⇒ 本估算也逐条封顶，绝不把
    //     四条 1000‰ 的规则加成 4000‰（那会让真档农田纤维把经营者的净收益扣成负数）。
    //   · Weight.LABOR_AMOUNT 拿不到 Σ劳动 ⇒ 对同一池取最大率、只计一次（标 SHARE_WEIGHT_ESTIMATE），是上界。
    Map<String, long[]> sharePools = new LinkedHashMap<>();
    for (CompensationRule rule : relation.rules()) {
      if (isSelfPayee(rule.recipient(), relation.operator(), household, householdEconomy, hex)) {
        continue;
      }
      switch (rule.type()) {
        case FIXED_IN_KIND_RENT -> {
          CommodityId commodity = rule.commodity().orElse(null);
          long price = market == null || commodity == null ? 0L : market.askPriceOf(commodity);
          if (price <= 0L) {
            priceMissing = true;
          } else {
            rent =
                Math.addExact(
                    rent,
                    Math.multiplyExact(rule.fixedAmount(), price)
                        / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          }
        }
        case FIXED_MONEY_RENT -> rent = Math.addExact(rent, rule.fixedAmount());
        case FIXED_IN_KIND_PER_LABOR -> {
          if (!includeWages) {
            continue;
          }
          CommodityId commodity = rule.commodity().orElse(null);
          long price = market == null || commodity == null ? 0L : market.askPriceOf(commodity);
          if (price <= 0L) {
            priceMissing = true;
          } else {
            long due = SubsistenceObligation.perLaborDue(laborNeed, rule.fixedAmount());
            wages =
                Math.addExact(
                    wages,
                    Math.multiplyExact(due, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          }
        }
        case FIXED_MONEY_WAGE -> {
          // ★★★ D-032 D3 §2.2.1/§5.1（W1）：运行时不得回读旧 FIXED_MONEY_WAGE.fixedAmount。
          //   工资义务改由 MONEY_WAGE 角色的 U20 锚报价逐角色进入 expectedWageCostMilli。
          notes.add("LEGACY_MONEY_WAGE_IGNORED");
        }
        case OUTPUT_SHARE -> {
          CommodityId commodity = rule.commodity().orElse(null);
          boolean perCommodity =
              commodity != null
                  && (!revenueByCommodity.isEmpty() || !inputCostByCommodity.isEmpty());
          long gross = perCommodity ? revenueByCommodity.getOrDefault(commodity, 0L) : totalRevenue;
          long input =
              perCommodity ? inputCostByCommodity.getOrDefault(commodity, 0L) : totalInputCost;
          long pool = rule.pool() == Pool.GROSS_OUTPUT ? gross : Math.max(0L, gross - input);
          if (pool <= 0L) {
            continue;
          }
          String key = rule.pool().name() + "|" + (commodity == null ? "*" : commodity.value());
          long[] state = sharePools.computeIfAbsent(key, ignored -> new long[] {pool, 0L, 0L});
          state[0] = Math.max(state[0], pool);
          if (rule.weight() == Weight.LABOR_AMOUNT) {
            notes.add("SHARE_WEIGHT_ESTIMATE");
            state[2] = Math.max(state[2], rule.ratePerMille());
          } else {
            long remaining = Math.max(0L, state[0] - state[1]);
            long share =
                Math.min(
                    remaining, Math.multiplyExact(state[0], (long) rule.ratePerMille()) / 1000L);
            if (share > 0L) {
              rent = Math.addExact(rent, share);
              state[1] = Math.addExact(state[1], share);
            }
          }
        }
        default -> {
          // SELF_RETENTION 数量恒 0（余额归 residualOwner），不构成义务。
        }
      }
    }
    for (long[] state : sharePools.values()) {
      if (state[2] <= 0L) {
        continue;
      }
      long remaining = Math.max(0L, state[0] - state[1]);
      long share = Math.min(remaining, Math.multiplyExact(state[0], state[2]) / 1000L);
      rent = Math.addExact(rent, share);
    }
    if (priceMissing) {
      notes.add("NO_PRICE");
    }
    return new Obligations(rent, wages, notes);
  }

  record Obligations(long rentMilli, long laborWageMilli, List<String> notes) {
    Obligations {
      Objects.requireNonNull(notes, "notes");
      notes = List.copyOf(notes);
    }
  }

  /** 劳动成本（毫钱）= laborNeed × subsistence × grainPrice ÷ 1_000_000（BigInteger 防溢出/截断）。 */
  static long laborCostMilli(long laborNeed, long subsistence, long grainPrice) {
    if (laborNeed <= 0L || subsistence <= 0L || grainPrice <= 0L) {
      return 0L;
    }
    BigInteger numerator =
        BigInteger.valueOf(laborNeed)
            .multiply(BigInteger.valueOf(subsistence))
            .multiply(BigInteger.valueOf(grainPrice));
    return numerator.divide(BigInteger.valueOf(1_000_000L)).longValueExact();
  }

  /**
   * ★★★ <b>W9-fix（2026-10-11）：把任意"毫钱"读数折成<b>每单位劳动的缩放值</b> —— 与 {@link Prospect#netPerLaborScaled()}
   * <b>同一把尺</b>（{@link #PER_LABOR_SCALE} 百万分之一刻度）。</b>
   *
   * <pre>
   * perLaborScaled(amountMilli, laborNeedMilli) = ⌊amountMilli × PER_LABOR_SCALE ÷ laborNeedMilli⌋
   * amountMilli ≤ 0 ∨ laborNeedMilli ≤ 0 ⇒ 0（"没有这笔钱" / "没有规模" ⇒ 没有基准）
   * </pre>
   *
   * <p>★ <b>它为什么存在（唯一拼写点）</b>：转业速度算式（{@code ModeMigrationPolicy.profitGapSpeedPerMille}）需要一个
   * <b>正参考基准</b>（"这个生产方式的收益规模有多大"），而基准必须与收益差<b>同一把尺</b>才不会漂开 ⇒ 复用 {@link #scaledNetPerLabor}
   * 的那一次乘法（{@code × PER_LABOR_SCALE}），不新造第二份刻度。
   *
   * <p>★ 溢出语义：与 {@code netPerLaborScaled} 同制 —— {@code BigInteger} 中间量 + {@code longValueExact}
   * 溢出即抛（fail-closed，拒绝静默回绕）。
   */
  static long perLaborScaled(long amountMilli, long laborNeedMilli) {
    if (amountMilli <= 0L || laborNeedMilli <= 0L) {
      return 0L;
    }
    return scaledNetPerLabor(amountMilli, laborNeedMilli);
  }

  /** {@code floor(net × PER_LABOR_SCALE / max(1, laborNeed))}；负数也向下取整（同 EnterpriseProfitBook）。 */
  static long scaledNetPerLabor(long net, long laborNeed) {
    BigInteger numerator = BigInteger.valueOf(net).multiply(BigInteger.valueOf(PER_LABOR_SCALE));
    BigInteger[] quotientAndRemainder =
        numerator.divideAndRemainder(BigInteger.valueOf(Math.max(1L, laborNeed)));
    BigInteger scaled = quotientAndRemainder[0];
    if (numerator.signum() < 0 && quotientAndRemainder[1].signum() != 0) {
      scaled = scaled.subtract(BigInteger.ONE);
    }
    try {
      return scaled.longValueExact();
    } catch (ArithmeticException overflow) {
      throw new ArithmeticException(
          "ExpectedProfitBook.netPerLaborScaled 溢出 long（fail-closed）: net="
              + net
              + ", labor="
              + laborNeed);
    }
  }

  static boolean demandCapped(long remainingTotal, long sellableTotal) {
    return sellableTotal < remainingTotal;
  }

  static boolean isSelfPayee(
      Payee recipient,
      ActorRef operator,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      HexCoord hex) {
    if (recipient instanceof Payee.ToActor toActor) {
      return toActor.actor().equals(operator)
          || toActor.actor().equals(HouseholdActors.of(household));
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

  // ── 规范序 / 冻结 / 理由串 ────────────────────────────────────────────────────────────────

  static Prospect infeasible(
      HouseholdId household,
      ProductionModeId modeId,
      ClassPositionId positionId,
      HexCoord hex,
      IndustryId industryId,
      String reason) {
    return new Prospect(
        household,
        modeId,
        positionId,
        hex,
        Optional.ofNullable(industryId),
        0L,
        Map.of(),
        Map.of(),
        Map.of(),
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        false,
        false,
        reason);
  }

  /**
   * 键值非空、逐值 ≥ 0 的保序副本（返回<b>未包装</b>的 LinkedHashMap；调用方在赋值处 {@code Collections.unmodifiableMap(...)}
   * 包装 —— SpotBugs 只认赋值处看得见的包装，故这里刻意不代包）。
   */
  static Map<CommodityId, Long> copyNonNegative(Map<CommodityId, Long> values, String field) {
    if (values == null) {
      throw new IllegalArgumentException("Prospect." + field + " 不得为 null");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Prospect." + field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Prospect." + field + " 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  @SafeVarargs
  static String joinReasons(List<String>... groups) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    for (List<String> group : groups) {
      for (String token : group) {
        if (token != null && !token.isBlank()) {
          tokens.add(token);
        }
      }
    }
    return String.join(",", tokens);
  }

  /** 逐商品 bid 估值（唯一口径）：缺价记入 {@code unpriced}、0 价按 0；只返回 >0 行。 */
  static Map<CommodityId, Long> bidValuesByCommodity(
      MarketTable market, Map<CommodityId, Long> quantities, Set<CommodityId> unpriced) {
    Map<CommodityId, Long> values = new LinkedHashMap<>();
    if (market == null) {
      unpriced.addAll(quantities.keySet());
      return values;
    }
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      if (!market.hasPrice(entry.getKey())) {
        unpriced.add(entry.getKey());
        continue;
      }
      long price = market.bidPriceOf(entry.getKey());
      if (price <= 0L) {
        continue;
      }
      values.put(
          entry.getKey(),
          Math.multiplyExact(entry.getValue(), price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return values;
  }

  /** 逐商品 ask 估值（唯一口径）：缺价记入 {@code unpriced}、0 价按 0；只返回 >0 行。 */
  static Map<CommodityId, Long> askValuesByCommodity(
      MarketTable market, Map<CommodityId, Long> quantities, Set<CommodityId> unpriced) {
    Map<CommodityId, Long> values = new LinkedHashMap<>();
    if (market == null) {
      unpriced.addAll(quantities.keySet());
      return values;
    }
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      if (!market.hasPrice(entry.getKey())) {
        unpriced.add(entry.getKey());
        continue;
      }
      long price = market.askPriceOf(entry.getKey());
      if (price <= 0L) {
        continue;
      }
      values.put(
          entry.getKey(),
          Math.multiplyExact(entry.getValue(), price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return values;
  }

  static long sumValues(Map<CommodityId, Long> values) {
    long total = 0L;
    for (long value : values.values()) {
      total = Math.addExact(total, value);
    }
    return total;
  }
}
