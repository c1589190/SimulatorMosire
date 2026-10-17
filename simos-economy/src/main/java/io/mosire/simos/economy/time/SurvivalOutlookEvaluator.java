package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>D-032 D3 §2.5.1/§2.5.1.1：SurvivalOutlook 纯只读求值器</b>。
 *
 * <p>本类型无状态、无账户/市场写、无 ChangeSet；同输入同输出。它只返回资格 {@code idleEligible} 与生存原因， <b>不填 {@code
 * idleChosen}</b> —— 选择由 {@code LaborQueueBook}/{@code LaborQueueSettlement} 按 {@code
 * SURVIVAL_IDLE_POLICY} 显式完成。
 *
 * <p>逐需求行覆盖顺序固定为：当前库存 → 预计实物收入 → 预计产品份额 → 货币折算；时间过滤、 去重、缺价/不可达/0 价/0 供给规则全部按 D3 §2.5.1.1 的唯一拼写点实现。
 */
public interface SurvivalOutlookEvaluator {

  /** D3 §2.5.1 工程默认：组合上限 4096（调用方仍须显式传入；超过即 fail-closed）。 */
  int DEFAULT_MAX_COMBINATIONS = 4096;

  SurvivalOutlook evaluate(SurvivalOutlookRequest request);

  /** 唯一默认实现（纯只读、无状态）。 */
  static SurvivalOutlookEvaluator standard() {
    return StandardSurvivalOutlookEvaluator.INSTANCE;
  }
}

/** {@link SurvivalOutlookEvaluator} 的唯一实现：确定性 DFS 组合 + 逐需求行来源覆盖。 */
final class StandardSurvivalOutlookEvaluator implements SurvivalOutlookEvaluator {

  static final StandardSurvivalOutlookEvaluator INSTANCE = new StandardSurvivalOutlookEvaluator();

  private StandardSurvivalOutlookEvaluator() {}

  @Override
  public SurvivalOutlook evaluate(SurvivalOutlookRequest request) {
    Objects.requireNonNull(request, "SurvivalOutlookRequest 不得为 null");
    validate(request);

    List<LivelihoodAlternative> alternatives = request.feasibleAlternatives();
    int maxCombinations = request.maxCombinations();
    Search search = new Search(request, alternatives);
    search.enumerate(0, 0L, new LinkedHashSet<>(), new ArrayList<>());
    if (search.evaluatedNonEmpty > maxCombinations) {
      throw new SurvivalOutlookException(
          "survival-combination-limit",
          "非空组合评估数 " + search.evaluatedNonEmpty + " > maxCombinations=" + maxCombinations);
    }
    if (search.bestCoverage == null) {
      // 空子集也必须被评估；走到这里只可能是实现错误，按契约故障 fail-closed。
      throw new SurvivalOutlookException("survival-input-invalid", "空组合没有被评估（内部错误，不返回截断结果）");
    }

    Map<SubsistenceNeedKey, SurvivalCoverageLine> provenance = search.bestCoverage.provenance();
    Map<SubsistenceNeedKey, Long> shortfall = new LinkedHashMap<>();
    for (Map.Entry<SubsistenceNeedKey, SurvivalCoverageLine> entry : provenance.entrySet()) {
      long missing =
          Math.max(
              0L,
              entry.getValue().requiredQuantityMilli() - entry.getValue().coveredQuantityMilli());
      shortfall.put(entry.getKey(), missing);
    }
    long bottleneck = search.bestCoverage.bottleneckCoveragePerMille();
    boolean idleEligible = bottleneck < 1000L;
    SurvivalOutlookReason reason;
    if (!idleEligible) {
      reason = SurvivalOutlookReason.SURVIVAL_REACHABLE;
    } else if (search.evaluatedNonEmpty == 0) {
      reason = SurvivalOutlookReason.NO_FEASIBLE_LIVELIHOOD;
    } else {
      reason = SurvivalOutlookReason.SUBSISTENCE_SHORTFALL;
    }
    return new SurvivalOutlook(
        request.household(),
        request.nextTickNeed(),
        search.bestCoverage,
        new SurvivalShortfall(shortfall, 1000L - bottleneck),
        idleEligible,
        reason);
  }

  private static void validate(SurvivalOutlookRequest request) {
    if (request.nextTickNeed() == null) {
      throw new SurvivalOutlookException("survival-need-missing", "nextTickNeed 不得为 null");
    }
    List<SubsistenceNeedLine> lines = request.nextTickNeed().lines();
    if (lines == null) {
      throw new SurvivalOutlookException("survival-need-missing", "需求行表不得为 null");
    }
    Set<SubsistenceNeedKey> needKeys = new LinkedHashSet<>();
    for (SubsistenceNeedLine line : lines) {
      if (line == null) {
        throw new SurvivalOutlookException("survival-input-invalid", "需求行不得为 null");
      }
      if (!needKeys.add(line.key())) {
        throw new SurvivalOutlookException(
            "survival-input-invalid", "需求键重复（逐需求行覆盖无法去重）: " + line.key().value());
      }
    }
    if (request.currentResources() == null) {
      throw new SurvivalOutlookException("survival-input-invalid", "currentResources 不得为 null");
    }
    if (request.localFacts() == null) {
      throw new SurvivalOutlookException("survival-market-facts-missing", "localFacts 不得为 null");
    }
    if (request.existingClaims() == null) {
      throw new SurvivalOutlookException("survival-input-invalid", "existingClaims 不得为 null");
    }
    if (request.maxCombinations() <= 0) {
      throw new SurvivalOutlookException(
          "survival-input-invalid", "maxCombinations 必须 > 0: " + request.maxCombinations());
    }
    Set<LivelihoodAlternativeId> ids = new LinkedHashSet<>();
    for (LivelihoodAlternative alternative : request.feasibleAlternatives()) {
      if (!ids.add(alternative.id())) {
        throw new SurvivalOutlookException(
            "survival-input-invalid", "同一请求内替代 id 必须唯一: " + alternative.id().value());
      }
    }
  }

  /** 组合搜索的瞬态状态；只被本方法单线程使用。 */
  private static final class Search {
    private final SurvivalOutlookRequest request;
    private final List<LivelihoodAlternative> alternatives;
    private final Set<ResourceClaimKey> unavailableClaims;
    private long evaluatedNonEmpty;
    private SurvivalCoverage bestCoverage;
    private List<String> bestIds = List.of();
    private long bestLabor = Long.MAX_VALUE;

    private Search(SurvivalOutlookRequest request, List<LivelihoodAlternative> alternatives) {
      this.request = request;
      this.alternatives = alternatives;
      this.unavailableClaims = request.existingClaims().unavailableResourceClaims();
    }

    private void enumerate(
        int index,
        long laborUsed,
        Set<ResourceClaimKey> claims,
        List<LivelihoodAlternative> selected) {
      if (index >= alternatives.size()) {
        if (!selected.isEmpty()) {
          evaluatedNonEmpty++;
          if (evaluatedNonEmpty > request.maxCombinations()) {
            return;
          }
        }
        SurvivalCoverage coverage = coverageOf(selected, laborUsed);
        chooseBest(coverage, selected, laborUsed);
        return;
      }
      // 不选当前替代：空组合也在 index==size 时被评估一次。
      enumerate(index + 1, laborUsed, claims, selected);

      LivelihoodAlternative alternative = alternatives.get(index);
      if (laborUsed > request.laborBudgetMilli() - alternative.laborMilli()) {
        return;
      }
      for (ResourceClaimKey claim : alternative.resourceClaims()) {
        if (claims.contains(claim) || unavailableClaims.contains(claim)) {
          return;
        }
      }
      long nextLabor;
      try {
        nextLabor = Math.addExact(laborUsed, alternative.laborMilli());
      } catch (ArithmeticException overflow) {
        throw new SurvivalOutlookException("survival-input-invalid", "劳动预算相加溢出");
      }
      if (nextLabor > request.laborBudgetMilli()) {
        return;
      }
      Set<ResourceClaimKey> nextClaims = new LinkedHashSet<>(claims);
      nextClaims.addAll(alternative.resourceClaims());
      selected.add(alternative);
      enumerate(index + 1, nextLabor, nextClaims, selected);
      selected.remove(selected.size() - 1);
    }

    private SurvivalCoverage coverageOf(List<LivelihoodAlternative> selected, long laborUsed) {
      NextTickSubsistenceNeed need = request.nextTickNeed();
      long nextTick = need.forTick();
      Map<CommodityId, Long> inventory =
          new LinkedHashMap<>(request.currentResources().usableInventoryMilli());
      Map<CommodityId, Long> inKind = new LinkedHashMap<>();
      Map<CommodityId, Long> productShare = new LinkedHashMap<>();
      Map<CurrencyId, Long> money =
          new LinkedHashMap<>(request.currentResources().spendableMoneyMilli());
      // ★ 同一商品的可达供给也是全局一份池：逐需求行（同一商品可出现多行）必须逐次扣减，不能每行重置。
      Map<CommodityId, Long> supplyLeft = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, LocalCommodityReachability> entry :
          request.localFacts().byCommodity().entrySet()) {
        LocalCommodityReachability reachable = entry.getValue();
        if (reachable.reachable() && reachable.purchasePriceMilli() > 0L) {
          supplyLeft.put(entry.getKey(), reachable.reachableSupplyMilli());
        }
      }
      for (LivelihoodAlternative alternative : selected) {
        if (alternative.incomeAvailableAtTick() > nextTick) {
          continue;
        }
        addCommodityMap(inKind, alternative.expectedInKindMilli());
        addCommodityMap(productShare, alternative.expectedProductShareMilli());
        addMoneyMap(money, alternative.expectedMoneyIncomeMilli());
      }

      Map<SubsistenceNeedKey, Long> covered = new LinkedHashMap<>();
      Map<SubsistenceNeedKey, Long> perMille = new LinkedHashMap<>();
      Map<SubsistenceNeedKey, SurvivalCoverageLine> provenance = new LinkedHashMap<>();
      long bottleneck = 1000L;
      List<SubsistenceNeedLine> lines = new ArrayList<>(need.lines());
      lines.sort(Comparator.comparing(line -> line.key().value()));
      for (SubsistenceNeedLine line : lines) {
        long required = line.requiredQuantityMilli();
        if (required == 0L) {
          covered.put(line.key(), 0L);
          perMille.put(line.key(), 1000L);
          provenance.put(line.key(), new SurvivalCoverageLine(0L, 0L, 0L, 0L, 0L, 0L));
          continue;
        }
        CommodityId commodity = line.commodity();
        long remaining = required;
        long fromInventory = take(inventory, commodity, remaining);
        remaining -= fromInventory;
        long fromInKind = take(inKind, commodity, remaining);
        remaining -= fromInKind;
        long fromProductShare = take(productShare, commodity, remaining);
        remaining -= fromProductShare;
        long fromMoney = 0L;
        if (remaining > 0L) {
          LocalCommodityReachability reachable = request.localFacts().byCommodity().get(commodity);
          long supply = supplyLeft.getOrDefault(commodity, 0L);
          if (reachable != null
              && reachable.reachable()
              && reachable.purchasePriceMilli() > 0L
              && supply > 0L) {
            long budget = money.getOrDefault(reachable.currency(), 0L);
            if (budget > 0L) {
              BigInteger affordable =
                  BigInteger.valueOf(budget)
                      .multiply(BigInteger.valueOf(1000L))
                      .divide(BigInteger.valueOf(reachable.purchasePriceMilli()));
              long affordableQ = affordable.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
              long quantity = Math.min(remaining, Math.min(affordableQ, supply));
              if (quantity > 0L) {
                long cost = ceilCost(quantity, reachable.purchasePriceMilli());
                if (cost <= budget) {
                  fromMoney = quantity;
                  remaining -= quantity;
                  money.put(reachable.currency(), budget - cost);
                  long supplyAfter = supply - quantity;
                  if (supplyAfter == 0L) {
                    supplyLeft.remove(commodity);
                  } else {
                    supplyLeft.put(commodity, supplyAfter);
                  }
                }
              }
            }
          }
        }
        long lineCovered =
            Math.addExact(
                Math.addExact(fromInventory, fromInKind),
                Math.addExact(fromProductShare, fromMoney));
        long coverPerMille =
            BigInteger.valueOf(lineCovered)
                .multiply(BigInteger.valueOf(1000L))
                .divide(BigInteger.valueOf(required))
                .min(BigInteger.valueOf(1000L))
                .longValue();
        covered.put(line.key(), lineCovered);
        perMille.put(line.key(), coverPerMille);
        provenance.put(
            line.key(),
            new SurvivalCoverageLine(
                required, fromInventory, fromInKind, fromProductShare, fromMoney, lineCovered));
        bottleneck = Math.min(bottleneck, coverPerMille);
      }
      return new SurvivalCoverage(covered, perMille, provenance, bottleneck);
    }

    private void chooseBest(
        SurvivalCoverage candidate, List<LivelihoodAlternative> selected, long laborUsed) {
      if (bestCoverage == null) {
        bestCoverage = candidate;
        bestIds = idsOf(selected);
        bestLabor = laborUsed;
        return;
      }
      int byBottleneck =
          Long.compare(
              candidate.bottleneckCoveragePerMille(), bestCoverage.bottleneckCoveragePerMille());
      if (byBottleneck > 0) {
        bestCoverage = candidate;
        bestIds = idsOf(selected);
        bestLabor = laborUsed;
        return;
      }
      if (byBottleneck < 0) {
        return;
      }
      long candidateCoverage = sumCoverage(candidate);
      long bestTotal = sumCoverage(bestCoverage);
      int byCoverage = Long.compare(candidateCoverage, bestTotal);
      if (byCoverage > 0) {
        bestCoverage = candidate;
        bestIds = idsOf(selected);
        bestLabor = laborUsed;
        return;
      }
      if (byCoverage < 0) {
        return;
      }
      int byLabor = Long.compare(laborUsed, bestLabor);
      if (byLabor < 0) {
        bestCoverage = candidate;
        bestIds = idsOf(selected);
        bestLabor = laborUsed;
        return;
      }
      if (byLabor == 0 && lexicographicallyLess(idsOf(selected), bestIds)) {
        bestCoverage = candidate;
        bestIds = idsOf(selected);
        bestLabor = laborUsed;
      }
    }

    private static long sumCoverage(SurvivalCoverage coverage) {
      long sum = 0L;
      for (long value : coverage.coveredQuantityMilli().values()) {
        sum = Math.addExact(sum, value);
      }
      return sum;
    }

    private static List<String> idsOf(List<LivelihoodAlternative> selected) {
      List<String> ids = new ArrayList<>(selected.size());
      for (LivelihoodAlternative alternative : selected) {
        ids.add(alternative.id().value());
      }
      ids.sort(String::compareTo);
      return List.copyOf(ids);
    }

    private static boolean lexicographicallyLess(List<String> left, List<String> right) {
      int length = Math.min(left.size(), right.size());
      for (int i = 0; i < length; i++) {
        int compare = left.get(i).compareTo(right.get(i));
        if (compare != 0) {
          return compare < 0;
        }
      }
      return left.size() < right.size();
    }

    private static long take(Map<CommodityId, Long> pool, CommodityId commodity, long wanted) {
      long available = pool.getOrDefault(commodity, 0L);
      long used = Math.min(Math.max(0L, available), Math.max(0L, wanted));
      if (used <= 0L) {
        return 0L;
      }
      long left = available - used;
      if (left == 0L) {
        pool.remove(commodity);
      } else {
        pool.put(commodity, left);
      }
      return used;
    }

    private static void addCommodityMap(
        Map<CommodityId, Long> target, Map<CommodityId, Long> source) {
      for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
        target.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }

    private static void addMoneyMap(Map<CurrencyId, Long> target, Map<CurrencyId, Long> source) {
      for (Map.Entry<CurrencyId, Long> entry : source.entrySet()) {
        target.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }

    private static long ceilCost(long quantity, long price) {
      BigInteger product = BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(price));
      BigInteger numerator = product.add(BigInteger.valueOf(999L));
      return numerator.divide(BigInteger.valueOf(1000L)).longValueExact();
    }
  }
}
