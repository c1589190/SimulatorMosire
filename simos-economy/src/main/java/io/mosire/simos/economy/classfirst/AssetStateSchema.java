package io.mosire.simos.economy.classfirst;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 聚合资产状态 schema（佃农制默认）：每个阶层一组"按人/劳动归一"的再生产需求向量与权重。
 *
 * <pre>
 *   r_k = asset_k / max(1, requirement_k)                 // 正向维度
 *   r_debt = clamp(1 - debt_k / max(1, requirement_debt), 0, 1)  // 负向维度
 *   A_C = weighted_geometric_mean(r) * min(r) ^ bottleneckWeight
 * </pre>
 *
 * <p>所有比率与 A_C 用千分整数（1000 = 1.0）表示，几何平均/开方用 {@link BigInteger} 精确整数根， 无浮点、无随机。requirement 为 0
 * 的维度不进入状态（该阶层不要求）。无价格的维度（如租约权利）按 {@link #unpricedWeightPerMille} 降权，并在 printout 里标 {@code
 * unpriced}。
 */
public record AssetStateSchema(long unpricedWeightPerMille, Map<String, ClassRequirement> byClass) {

  private static final int MAX_RATIO_MILLI = 10_000;

  public AssetStateSchema {
    if (unpricedWeightPerMille <= 0 || unpricedWeightPerMille > 1000) {
      throw new IllegalArgumentException("unpricedWeightPerMille must be in (0,1000]");
    }
    LinkedHashMap<String, ClassRequirement> copy = new LinkedHashMap<>();
    if (byClass != null) {
      for (Map.Entry<String, ClassRequirement> entry : byClass.entrySet()) {
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    byClass = Collections.unmodifiableMap(copy);
  }

  /** 单阶层的需求/权重向量；requirement 按"每 1000 人"给，权重非零才参与 A_C。 */
  public record ClassRequirement(
      Map<AssetKind, Long> requirementPerCapitaMilli,
      Map<AssetKind, Long> weights,
      long bottleneckWeightPerMille,
      Set<AssetKind> unpriced) {

    public ClassRequirement {
      LinkedHashMap<AssetKind, Long> reqCopy = new LinkedHashMap<>();
      if (requirementPerCapitaMilli != null) {
        for (Map.Entry<AssetKind, Long> entry : requirementPerCapitaMilli.entrySet()) {
          reqCopy.put(entry.getKey(), Math.max(0L, entry.getValue()));
        }
      }
      LinkedHashMap<AssetKind, Long> weightCopy = new LinkedHashMap<>();
      if (weights != null) {
        for (Map.Entry<AssetKind, Long> entry : weights.entrySet()) {
          weightCopy.put(entry.getKey(), Math.max(0L, entry.getValue()));
        }
      }
      requirementPerCapitaMilli = Collections.unmodifiableMap(reqCopy);
      weights = Collections.unmodifiableMap(weightCopy);
      LinkedHashSet<AssetKind> unpricedCopy = new LinkedHashSet<>();
      if (unpriced != null) {
        unpricedCopy.addAll(unpriced);
      }
      unpriced = Collections.unmodifiableSet(unpricedCopy);
      if (bottleneckWeightPerMille < 0 || bottleneckWeightPerMille > 1000) {
        throw new IllegalArgumentException("bottleneckWeightPerMille must be in [0,1000]");
      }
    }
  }

  /** 佃农制默认 schema：地主/中农/佃农/雇农四套需求向量。 */
  public static AssetStateSchema tenancyDefaults() {
    LinkedHashMap<String, ClassRequirement> byClass = new LinkedHashMap<>();
    byClass.put(
        PilotModel.LANDLORD_ID,
        requirement(
            Map.of(
                AssetKind.GRAIN, 12_000L,
                AssetKind.MONEY, 10_000L,
                AssetKind.OWNED_LAND, 300_000L),
            Map.of(AssetKind.GRAIN, 2L, AssetKind.MONEY, 2L, AssetKind.OWNED_LAND, 4L),
            500L,
            Set.of()));
    byClass.put(
        PilotModel.MIDDLE_PEASANT_ID,
        requirement(
            Map.of(
                AssetKind.GRAIN, 9_000L,
                AssetKind.MONEY, 3_000L,
                AssetKind.TOOLS, 1_000L,
                AssetKind.OWNED_LAND, 6_000L,
                AssetKind.OPERATED_LAND, 6_000L,
                AssetKind.DEBT, 9_000L),
            Map.of(
                AssetKind.GRAIN, 2L,
                AssetKind.MONEY, 2L,
                AssetKind.TOOLS, 2L,
                AssetKind.OWNED_LAND, 3L,
                AssetKind.OPERATED_LAND, 2L,
                AssetKind.DEBT, 2L),
            500L,
            Set.of()));
    byClass.put(
        PilotModel.TENANT_ID,
        requirement(
            Map.of(
                AssetKind.GRAIN, 9_000L,
                AssetKind.MONEY, 2_000L,
                AssetKind.TOOLS, 1_000L,
                AssetKind.OPERATED_LAND, 3_000L,
                AssetKind.LEASE_SECURITY, 1_500L,
                AssetKind.DEBT, 6_000L),
            Map.of(
                AssetKind.GRAIN, 2L,
                AssetKind.MONEY, 1L,
                AssetKind.TOOLS, 2L,
                AssetKind.OPERATED_LAND, 3L,
                AssetKind.LEASE_SECURITY, 2L,
                AssetKind.DEBT, 2L),
            500L,
            Set.of(AssetKind.LEASE_SECURITY)));
    byClass.put(
        PilotModel.LABORER_ID,
        requirement(
            Map.of(
                AssetKind.GRAIN, 3_000L,
                AssetKind.MONEY, 2_000L,
                AssetKind.TOOLS, 500L),
            Map.of(AssetKind.GRAIN, 3L, AssetKind.MONEY, 2L, AssetKind.TOOLS, 2L),
            500L,
            Set.of()));
    return new AssetStateSchema(500L, byClass);
  }

  private static ClassRequirement requirement(
      Map<AssetKind, Long> requirement,
      Map<AssetKind, Long> weights,
      long bottleneckWeightPerMille,
      Set<AssetKind> unpriced) {
    return new ClassRequirement(requirement, weights, bottleneckWeightPerMille, unpriced);
  }

  public ClassRequirement requirementFor(String classPositionId) {
    ClassRequirement requirement = byClass.get(classPositionId);
    if (requirement == null) {
      throw new IllegalArgumentException("no asset requirement for class: " + classPositionId);
    }
    return requirement;
  }

  /** A_C（千分，1000 = 1.0）。空池 A=0；任一有效维度 r=0 时 A=0。 */
  public long aMilli(ClassPool pool, long moneyPerGrain) {
    ClassRequirement requirement = requirementFor(pool.classPositionId());
    BigInteger product = BigInteger.ONE;
    int totalWeight = 0;
    long minRatio = Long.MAX_VALUE;
    long population = Math.max(1L, pool.population());
    for (AssetKind kind : AssetKind.ordered()) {
      long perCapita = requirement.requirementPerCapitaMilli().getOrDefault(kind, 0L);
      long rawWeight = requirement.weights().getOrDefault(kind, 0L);
      if (perCapita <= 0L || rawWeight <= 0L) {
        continue;
      }
      long weight = rawWeight;
      if (requirement.unpriced().contains(kind)) {
        weight = Math.max(1L, rawWeight * unpricedWeightPerMille / 1000L);
      }
      long requirementMilli = perCapita * population;
      long ratio = ratioMilli(pool, kind, moneyPerGrain, requirementMilli);
      if (ratio == 0L) {
        return 0L;
      }
      minRatio = Math.min(minRatio, ratio);
      product = product.multiply(BigInteger.valueOf(ratio).pow((int) weight));
      totalWeight += (int) weight;
    }
    if (totalWeight == 0 || minRatio == Long.MAX_VALUE) {
      return 1_000L;
    }
    long geometricMean = floorRoot(product, totalWeight).longValue();
    long bottleneck = bottleneckFactor(minRatio, requirement.bottleneckWeightPerMille());
    return geometricMean * bottleneck / 1000L;
  }

  private static long ratioMilli(
      ClassPool pool, AssetKind kind, long moneyPerGrain, long requirementMilli) {
    long requirement = Math.max(1L, requirementMilli);
    if (kind == AssetKind.DEBT) {
      long debtMilli = pool.debtGrainMilli();
      long scaled = debtMilli * 1000L / requirement;
      return Math.max(0L, Math.min(1000L, 1000L - scaled));
    }
    long assetMilli = pool.stateMilli(kind);
    return Math.min(MAX_RATIO_MILLI, assetMilli * 1000L / requirement);
  }

  /** minRatio 的 bottleneckWeight 次幂（千分定点）。 */
  private static long bottleneckFactor(long minRatioMilli, long bottleneckWeightPerMille) {
    if (bottleneckWeightPerMille <= 0L) {
      return 1000L;
    }
    long numerator = bottleneckWeightPerMille;
    long denominator = 1000L;
    long gcd = gcd(numerator, denominator);
    numerator /= gcd;
    denominator /= gcd;
    if (numerator >= denominator) {
      return minRatioMilli;
    }
    BigInteger value =
        BigInteger.valueOf(minRatioMilli)
            .pow((int) numerator)
            .multiply(BigInteger.valueOf(1000L).pow((int) (denominator - numerator)));
    return floorRoot(value, (int) denominator).longValue();
  }

  private static long gcd(long a, long b) {
    long x = Math.abs(a);
    long y = Math.abs(b);
    while (y != 0L) {
      long remainder = x % y;
      x = y;
      y = remainder;
    }
    return Math.max(1L, x);
  }

  static BigInteger floorRoot(BigInteger value, int degree) {
    if (degree <= 0) {
      throw new IllegalArgumentException("degree must be > 0: " + degree);
    }
    if (value.signum() <= 0) {
      return BigInteger.ZERO;
    }
    int bits = value.bitLength();
    BigInteger high = BigInteger.ONE.shiftLeft((bits + degree - 1) / degree);
    BigInteger low = BigInteger.ZERO;
    while (low.compareTo(high) < 0) {
      BigInteger middle = low.add(high).add(BigInteger.ONE).shiftRight(1);
      if (middle.pow(degree).compareTo(value) <= 0) {
        low = middle;
      } else {
        high = middle.subtract(BigInteger.ONE);
      }
    }
    return low;
  }
}
