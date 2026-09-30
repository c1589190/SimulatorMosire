package io.mosire.simos.economy.classfirst;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人口流动/GM 政策。GM 只改这里的源参数（schema、边界、速率、caps、机会、absorption、bundle 模板、提取率）， 不直接改人口/资产/债务读数；引擎每 tick
 * 从池状态重新派生 A_C/x_C/r/O。
 *
 * <pre>
 *   r_up   = upMin   + (upMax   - upMin)   * x^gamma       // 年化千分
 *   r_down = downMin + (downMax - downMin) * (1 - x)^gamma
 * </pre>
 */
public record MobilityPolicy(
    AssetStateSchema schema,
    ClassBounds bounds,
    long gamma,
    long upMinPerMillePerYear,
    long upMaxPerMillePerYear,
    long downMinPerMillePerYear,
    long downMaxPerMillePerYear,
    long upCapPerMillePerTick,
    long downCapPerMillePerTick,
    long leaseAvailabilityPerMille,
    long initialLandForSale,
    long ticksPerYear,
    long leasePerCapitaMilli,
    long landPurchasePerCapitaMilli,
    long absorptionCapTenantPerMille,
    long absorptionCapMiddlePerMille,
    long absorptionCapLandlordPerMille,
    long absorptionCapLaborerPerMille,
    Map<String, Long> absorptionCapByEdgePerMille,
    AbsorptionPolicy absorptionPolicy,
    Map<String, BundleTemplate> bundleTemplates,
    long extractionTaxPerMille) {

  public enum AbsorptionPolicy {
    /** O = min(cap, 机会供给 / 需求)。 */
    PROPORTIONAL,
    /** 机会供给 < 需求时 O=0（整批吸收），否则 O=cap。 */
    ALL_OR_NOTHING
  }

  /**
   * 单条边的人口/劳动/资产 bundle 模板（千分比）。
   *
   * <p>库存资产份额按"迁出人口 / 迁出前人口"的人均比例切分（1000 = 按人头带走全部人均份额），因此迁出不会 让原池人均状态凭空提高；{@code
   * landToMarket=true} 时土地所有权进 LandForSale，否则进目的池。
   */
  public record BundleTemplate(
      long grainSharePerMille,
      long moneySharePerMille,
      long toolsSharePerMille,
      long clothSharePerMille,
      long landSharePerMille,
      boolean landToMarket,
      boolean takesLease,
      boolean returnsLease,
      boolean buysLand,
      boolean movesDebt,
      boolean movesClaims) {}

  public MobilityPolicy {
    if (schema == null || bounds == null || absorptionPolicy == null) {
      throw new IllegalArgumentException("schema/bounds/absorptionPolicy must be present");
    }
    if (gamma < 1L) {
      throw new IllegalArgumentException("gamma must be >= 1");
    }
    if (upMinPerMillePerYear < 0L
        || upMaxPerMillePerYear < upMinPerMillePerYear
        || downMinPerMillePerYear < 0L
        || downMaxPerMillePerYear < downMinPerMillePerYear) {
      throw new IllegalArgumentException("invalid annual mobility rates");
    }
    if (ticksPerYear <= 0L) {
      throw new IllegalArgumentException("ticksPerYear must be > 0");
    }
    if (upCapPerMillePerTick < 0L || downCapPerMillePerTick < 0L) {
      throw new IllegalArgumentException("caps must be >= 0");
    }
    if (leaseAvailabilityPerMille < 0L
        || leaseAvailabilityPerMille > 1000L
        || initialLandForSale < 0L) {
      throw new IllegalArgumentException("invalid opportunity policy");
    }
    LinkedHashMap<String, BundleTemplate> templates = new LinkedHashMap<>();
    if (bundleTemplates != null) {
      templates.putAll(bundleTemplates);
    }
    bundleTemplates = Collections.unmodifiableMap(templates);
    LinkedHashMap<String, Long> edgeCaps = new LinkedHashMap<>();
    if (absorptionCapByEdgePerMille != null) {
      for (Map.Entry<String, Long> entry : absorptionCapByEdgePerMille.entrySet()) {
        edgeCaps.put(entry.getKey(), Math.max(0L, entry.getValue()));
      }
    }
    absorptionCapByEdgePerMille = Collections.unmodifiableMap(edgeCaps);
  }

  public static MobilityPolicy tenancyDefaults() {
    LinkedHashMap<String, BundleTemplate> templates = new LinkedHashMap<>();
    templates.put(
        edge(PilotModel.LABORER_ID, PilotModel.TENANT_ID),
        bundle(1000, 1000, 1000, 1000, 0, false, true, false, false, true, true));
    templates.put(
        edge(PilotModel.TENANT_ID, PilotModel.MIDDLE_PEASANT_ID),
        bundle(1000, 1000, 1000, 1000, 0, false, false, true, true, true, true));
    templates.put(
        edge(PilotModel.MIDDLE_PEASANT_ID, PilotModel.LANDLORD_ID),
        bundle(1000, 1000, 1000, 1000, 1000, false, false, false, false, false, true));
    templates.put(
        edge(PilotModel.MIDDLE_PEASANT_ID, PilotModel.TENANT_ID),
        bundle(1000, 1000, 1000, 1000, 1000, true, true, false, false, true, true));
    templates.put(
        edge(PilotModel.TENANT_ID, PilotModel.LABORER_ID),
        bundle(1000, 1000, 1000, 1000, 1000, false, false, true, false, true, true));
    LinkedHashMap<String, Long> edgeCaps = new LinkedHashMap<>();
    edgeCaps.put(edge(PilotModel.MIDDLE_PEASANT_ID, PilotModel.TENANT_ID), 182L);
    return new MobilityPolicy(
        AssetStateSchema.tenancyDefaults(),
        ClassBounds.tenancyDefaults(),
        2L,
        10L,
        80L,
        20L,
        150L,
        1L, // upCap ‰/tick：默认卡住向上流，GM 调大即可见迁移增加
        3L, // downCap ‰/tick
        300L, // leaseAvailability：地主土地的 30% 可出租
        0L, // initialLandForSale：市场从 0 开始，中农下行放地后佃农才能上行
        12L, // ticksPerYear：年化速率按 12 tick/年换算
        3_000L, // leasePerCapitaMilli：新佃农每人 3 单位租地权
        4_000L, // landPurchasePerCapitaMilli：升中农每人从 LandForSale 购 4 单位地
        318L, // absorptionCapTenant：佃农池可吸收（同时是上行目的地与中农下行目的地）
        500L, // absorptionCapMiddle：中农池可吸收（佃农上行）
        450L, // absorptionCapLandlord：地主池可吸收（"可组织他人劳动/出租需求"容量）
        318L, // absorptionCapLaborer：雇农池可吸收（经营池未满足的劳动需求）
        edgeCaps, // 单边 absorption 覆盖：中农→佃农只在有租地机会时按更低速率吸收
        AbsorptionPolicy.PROPORTIONAL,
        templates,
        0L);
  }

  /** 与 {@link #tenancyDefaults()} 同一套；保留别名便于测试显式表达"pilot 默认政策"。 */
  public static MobilityPolicy pilotDefaults() {
    return tenancyDefaults();
  }

  /** GM 旋钮：升中农时从 LandForSale 购地的人均量（千分土地/人）。 */
  public MobilityPolicy withLandPurchasePerCapitaMilli(long newValue) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        newValue,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public MobilityPolicy withDownCapPerMillePerTick(long newCap) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        newCap,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public MobilityPolicy withAbsorptionPolicy(AbsorptionPolicy newPolicy) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        newPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public MobilityPolicy withInitialLandForSale(long newInitialLandForSale) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        newInitialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public MobilityPolicy withExtractionTaxPerMille(long newTax) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        newTax);
  }

  public MobilityPolicy withBundleTemplates(Map<String, BundleTemplate> newTemplates) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        newTemplates,
        extractionTaxPerMille);
  }

  /** GM 旋钮：只替换 AssetStateSchema（需求/权重/unpriced），不碰人口/资产读数。 */
  public MobilityPolicy withSchema(AssetStateSchema newSchema) {
    return new MobilityPolicy(
        newSchema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  /** GM 旋钮：只替换 ClassBounds。 */
  public MobilityPolicy withBounds(ClassBounds newBounds) {
    return new MobilityPolicy(
        schema,
        newBounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public MobilityPolicy withUpCapPerMillePerTick(long newCap) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        newCap,
        downCapPerMillePerTick,
        leaseAvailabilityPerMille,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public MobilityPolicy withLeaseAvailabilityPerMille(long newAvailability) {
    return new MobilityPolicy(
        schema,
        bounds,
        gamma,
        upMinPerMillePerYear,
        upMaxPerMillePerYear,
        downMinPerMillePerYear,
        downMaxPerMillePerYear,
        upCapPerMillePerTick,
        downCapPerMillePerTick,
        newAvailability,
        initialLandForSale,
        ticksPerYear,
        leasePerCapitaMilli,
        landPurchasePerCapitaMilli,
        absorptionCapTenantPerMille,
        absorptionCapMiddlePerMille,
        absorptionCapLandlordPerMille,
        absorptionCapLaborerPerMille,
        absorptionCapByEdgePerMille,
        absorptionPolicy,
        bundleTemplates,
        extractionTaxPerMille);
  }

  public long rateUpPerMillePerYear(long xMilli) {
    long x = clamp1000(xMilli);
    long xPow = pow(x, gamma);
    long span = upMaxPerMillePerYear - upMinPerMillePerYear;
    return upMinPerMillePerYear + span * xPow / pow(1000L, gamma);
  }

  public long rateDownPerMillePerYear(long xMilli) {
    long x = clamp1000(xMilli);
    long complement = 1000L - x;
    long complementPow = pow(complement, gamma);
    long span = downMaxPerMillePerYear - downMinPerMillePerYear;
    return downMinPerMillePerYear + span * complementPow / pow(1000L, gamma);
  }

  /** 单 tick 的迁移人数（milli-people）。 */
  public long flowPerTickMilli(long population, long annualRatePerMille) {
    return population * annualRatePerMille / ticksPerYear;
  }

  public BundleTemplate templateFor(String fromClass, String toClass) {
    return bundleTemplates.get(edge(fromClass, toClass));
  }

  /** 单边覆盖优先；没有覆盖时回落到目的阶层的统一 cap。 */
  public long absorptionCapPerMille(String fromClassPositionId, String toClassPositionId) {
    Long edgeCap = absorptionCapByEdgePerMille.get(edge(fromClassPositionId, toClassPositionId));
    return edgeCap != null ? edgeCap : absorptionCapPerMille(toClassPositionId);
  }

  public long absorptionCapPerMille(String destinationClassPositionId) {
    if (PilotModel.TENANT_ID.equals(destinationClassPositionId)) {
      return absorptionCapTenantPerMille;
    }
    if (PilotModel.MIDDLE_PEASANT_ID.equals(destinationClassPositionId)) {
      return absorptionCapMiddlePerMille;
    }
    if (PilotModel.LANDLORD_ID.equals(destinationClassPositionId)) {
      return absorptionCapLandlordPerMille;
    }
    if (PilotModel.LABORER_ID.equals(destinationClassPositionId)) {
      return absorptionCapLaborerPerMille;
    }
    throw new IllegalArgumentException("unknown destination class: " + destinationClassPositionId);
  }

  public static String edge(String fromClass, String toClass) {
    return fromClass + "->" + toClass;
  }

  private static BundleTemplate bundle(
      long grain,
      long money,
      long tools,
      long cloth,
      long land,
      boolean landToMarket,
      boolean takesLease,
      boolean returnsLease,
      boolean buysLand,
      boolean movesDebt,
      boolean movesClaims) {
    return new BundleTemplate(
        grain,
        money,
        tools,
        cloth,
        land,
        landToMarket,
        takesLease,
        returnsLease,
        buysLand,
        movesDebt,
        movesClaims);
  }

  private static long pow(long base, long exponent) {
    long result = 1L;
    long factor = base;
    long remaining = exponent;
    while (remaining > 0L) {
      if ((remaining & 1L) == 1L) {
        result *= factor;
      }
      remaining >>= 1L;
      if (remaining > 0L) {
        factor *= factor;
      }
    }
    return result;
  }

  private static long clamp1000(long value) {
    return Math.max(0L, Math.min(1000L, value));
  }
}
