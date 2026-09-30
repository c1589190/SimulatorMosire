package io.mosire.simos.economy.classfirst;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶层池经济（单 mode：佃农制农业）的数据形状与阶层语义（R1 从 pilot 试点迁入正式包 classfirst）。
 *
 * <p>★ 边界：模型/引擎不读{@code 旧结算引擎（R3a 已删除）} 路径；跨 tick 持久化统一走 {@link ClassFirstState} → {@code
 * EconomyData.classFirst}（见 {@link ClassFirstSettlement}）。不碰三国 compact world，也不依赖旧 E1–E6 结算。
 * 全部字段都是整数（{@code long}）与 {@link LinkedHashMap}，没有任何随机数/UUID/时钟；同样的输入必然得到同样的 360 tick 轨迹。
 *
 * <p>阶层语义写死如下（见 {@link #tenancyMode()}）：
 *
 * <pre>
 * 位置             relationToMeans laborRole surplusRole        分配
 * LANDLORD         OWNER           NONE      SURPLUS_RECEIVER   固定实物粮租/单位土地
 * MIDDLE_PEASANT   MIXED           BOTH      SELF_SUBSISTENCE   自有地经营，残值归己
 * TENANT           DIRECT_LABORER  PROVIDER  SHARE              交租后余粮归己；欠租资本化
 * LABORER          DIRECT_LABORER  PROVIDER  WAGE_EARNER        工资/给养；无地
 * </pre>
 */
public final class PilotModel {

  public static final String GRAIN = "grain";
  public static final String CLOTH = "cloth";
  public static final String MONEY = "money";

  public static final String LANDLORD_ID = "LANDLORD";
  public static final String MIDDLE_PEASANT_ID = "MIDDLE_PEASANT";
  public static final String TENANT_ID = "TENANT";
  public static final String LABORER_ID = "LABORER";

  private PilotModel() {}

  /** 与生产资料的关系。 */
  public enum RelationToMeans {
    OWNER,
    MIXED,
    DIRECT_LABORER
  }

  /** 在本生产方式里的劳动角色。 */
  public enum LaborRole {
    NONE,
    BOTH,
    PROVIDER
  }

  /** 在本生产方式里的剩余角色（分成是佃农的 SHARE）。 */
  public enum SurplusRole {
    SURPLUS_RECEIVER,
    SELF_SUBSISTENCE,
    SHARE,
    WAGE_EARNER
  }

  /** 投入责任：谁负责备齐本期投入。 */
  public enum InputResponsibility {
    OWNER,
    SELF,
    MIXED,
    NONE
  }

  /** 损失责任：产出不够时先由谁承担。 */
  public enum LossResponsibility {
    OWNER,
    OPERATOR,
    HOUSEHOLD,
    LABORER
  }

  /** 催收优先序：本试点只实现"先流动商品，再按定价收地"。 */
  public enum SeizurePriority {
    LIQUID_THEN_LAND
  }

  /** 滚动账户状态。 */
  public enum AccountStatus {
    ACTIVE,
    DUE,
    COLLECTED,
    SETTLED
  }

  /** 报告里的非流动性/催收/红灯事件种类。人口流动另有 {@link MobilityEvent}。 */
  public enum TransitionKind {
    POPULATION_FLOW,
    LAND_SEIZED,
    LIQUID_SEIZED,
    DEBT_CAPITALIZED,
    BORROW_GOODS,
    BORROW_MONEY,
    BUY_GRAIN,
    RED_LIGHT
  }

  /** 迁移方向：UP = tier+1（雇农→佃农→中农→地主），DOWN = tier−1。 */
  public enum Direction {
    UP,
    DOWN
  }

  /** 阶层位置（id + 政治经济学语义 + 序：rank 越小越高）。 */
  public record ClassPosition(
      String id,
      String name,
      RelationToMeans relationToMeans,
      LaborRole laborRole,
      SurplusRole surplusRole,
      int rank) {

    public boolean isHigherThan(ClassPosition other) {
      return rank < other.rank;
    }
  }

  /** mode 内部对某个阶层位置的分配规则。 */
  public record ClassRule(
      long rentSharePerMille,
      long wageSharePerMille,
      String residualToClassPositionId,
      InputResponsibility inputResponsibility,
      LossResponsibility lossResponsibility) {}

  /** 生产方式：单 mode = tenancy_agriculture，classRules 必须覆盖全部四个位置。 */
  public record Mode(String id, Map<String, ClassRule> classRules) {
    public Mode {
      classRules = immutableCopy(classRules);
    }

    public ClassRule ruleFor(String classPositionId) {
      return classRules.get(classPositionId);
    }
  }

  /**
   * 初始家户夹具。池化后家户只是池内 {@link HouseholdAccount} 的生产/消费子账户；{@code land} 是**自有地** （佃农夹具为 0，租入地由池的租约在首
   * tick 配置）。
   */
  public record Household(
      String id,
      String name,
      String classPositionId,
      long population,
      long laborPerCapita,
      Map<String, Long> goods,
      long money,
      long land,
      long tools,
      long participationSharePerMille) {

    public Household {
      if (population < 1) {
        throw new IllegalArgumentException("household population must be >= 1: " + id);
      }
      goods = immutableCopy(goods);
    }

    public long grain() {
      return goods.getOrDefault(GRAIN, 0L);
    }

    public long cloth() {
      return goods.getOrDefault(CLOTH, 0L);
    }

    /** 该户本 tick 可提供的劳动（千分劳动单位）。 */
    public long laborUnits() {
      return population * laborPerCapita / 1000L;
    }
  }

  /** 独立放贷方（模拟 GOV/特殊单位）：不属于农业阶层结构，利率/条款显式配置。 */
  public record Lender(
      String id,
      long money,
      Map<String, Long> goods,
      long interestRatePerMille,
      long nextDueTick,
      long collectionPower) {

    public Lender {
      goods = immutableCopy(goods);
    }
  }

  /** 地主催收政策（试点显式配置，不是从别处推导）。 */
  public record CollectionPolicy(
      String collectorClassPositionId,
      long collectionThreshold,
      long collectionTriggerRatioPerMille,
      long collectionRatioPerMille,
      long landPricePerUnit,
      SeizurePriority seizurePriority) {}

  /** 单池本期生产计划：土地/劳动/工具/种子/效率都显式记录，收支双方可对账。 */
  public record ProductionPlan(
      long tick,
      String poolId,
      String classPositionId,
      long ownedLand,
      long leasedLand,
      long plannedLand,
      long ownLaborUsed,
      long externalLaborUsed,
      long seedRequired,
      long seedSelf,
      long seedExternal,
      long toolsUsed,
      long toolsShortageLand,
      long laborShortageLand,
      long seedShortage,
      long efficiencyPerMille,
      Map<String, Long> externalLaborByProviderPool,
      Map<String, Long> seedExternalByProviderPool) {

    public ProductionPlan {
      externalLaborByProviderPool = immutableCopy(externalLaborByProviderPool);
      seedExternalByProviderPool = immutableCopy(seedExternalByProviderPool);
    }
  }

  /** 本期生产账户：投入、产出、按阶层位置的实际分配与家户份额。 */
  public record ProductionAccount(
      long tick,
      String poolId,
      String classPositionId,
      long plannedLand,
      long actualLand,
      long outputGrain,
      long seedSelfUsed,
      long seedExternalUsed,
      long seedPaid,
      long rentPaid,
      long wagePaid,
      long residualPaid,
      Map<String, Long> distributedByClass,
      Map<String, Long> householdGrainShares,
      List<String> shortages) {

    public ProductionAccount {
      distributedByClass = immutableCopy(distributedByClass);
      householdGrainShares = immutableCopy(householdGrainShares);
      shortages = List.copyOf(shortages);
    }
  }

  /** 池内家户子账户：只承载人口/劳动/份额与生产子账户，不承载库存资产。 */
  public record HouseholdAccount(
      String poolId,
      String householdId,
      String name,
      long population,
      long laborPerCapita,
      long sharePerMille,
      long laborUnits) {}

  /**
   * 池级滚动账户快照。{@code cumulativeNet > 0} ⇒ 对手方欠 owner（claim）；{@code < 0} ⇒ owner 欠对手方（debt）。
   * 正净额永远不会被写成 debt，反之亦然。
   */
  public record RollingAccount(
      String ownerId,
      String counterpartyId,
      String unit,
      String terms,
      long cumulativeNet,
      long debt,
      long claim,
      long interestAccrued,
      long nextDueTick,
      AccountStatus status) {}

  /** 一次非流动性/催收事件。 */
  public record Transition(
      long tick,
      TransitionKind kind,
      String poolId,
      String counterpartyId,
      long populationMoved,
      long landSeized,
      long debtReduced,
      String reason) {}

  /** 迁移 bundle 的守恒明细（库存 + 权利 + 债权/债务份额）。 */
  public record TransitionBundle(
      long population,
      long labor,
      Map<AssetKind, Long> movedAssets,
      long landOwnershipToDestination,
      long landOwnershipToMarket,
      long landPurchasedFromMarket,
      long leaseRightsGranted,
      long leaseRightsReturned,
      long claimsMovedMilli,
      long debtMovedMilli,
      String debtRule,
      String landRule) {

    public TransitionBundle {
      movedAssets = immutableCopy(movedAssets);
    }
  }

  /** 池级迁移事件：tick、方向、from→to、人数、bundle、A/x/r/O/cap 原因。 */
  public record MobilityEvent(
      long tick,
      Direction direction,
      String fromClassPositionId,
      String toClassPositionId,
      long movedPopulation,
      TransitionBundle bundle,
      long aMilli,
      long afterAMilli,
      boolean originStockPerCapitaNotIncreased,
      long xMilli,
      long ratePerMillePerYear,
      long opportunityPerMille,
      long absorptionCapPerMille,
      long capMilliPeople,
      boolean skipLevel,
      String reason) {}

  /** 单池读数（含 A_C/x_C/r_up/r_down）。 */
  public record PoolReading(
      String classPositionId,
      String name,
      long population,
      long labor,
      long aMilli,
      long xMilli,
      long rateUpPerMillePerYear,
      long rateDownPerMillePerYear,
      long rateUpPerMillePerTick,
      long rateDownPerMillePerTick,
      Map<AssetKind, Long> assetVector,
      Map<String, Long> debtByUnit,
      long leaseHolding,
      long laborEfficiencyPerMille,
      long upCapPerMillePerTick,
      long downCapPerMillePerTick) {

    public PoolReading {
      assetVector = immutableCopy(assetVector);
      debtByUnit = immutableCopy(debtByUnit);
    }
  }

  /** 单 tick 读数（全部是深拷贝，构造后不可变）。 */
  public record TickReport(
      long tick,
      Map<String, PoolReading> pools,
      long landForSale,
      long leaseSupply,
      List<MobilityEvent> mobility,
      List<Transition> transitions,
      List<ProductionAccount> productionAccounts,
      long redLights,
      long baseRationGap,
      List<String> diagnostics,
      long totalPopulation,
      long totalOwnedLand,
      long totalTools,
      long totalGrain,
      long totalCloth,
      long totalMoney,
      long totalDebtGrainMilli,
      long totalClaimGrainMilli,
      long producedGrainTotal,
      long seedUsedTotal,
      long rationConsumedTotal,
      long clothConsumedTotal) {

    public TickReport {
      pools = immutableCopy(pools);
      mobility = List.copyOf(mobility);
      transitions = List.copyOf(transitions);
      productionAccounts = List.copyOf(productionAccounts);
      diagnostics = List.copyOf(diagnostics);
    }
  }

  public static final ClassPosition LANDLORD =
      new ClassPosition(
          LANDLORD_ID,
          "地主",
          RelationToMeans.OWNER,
          LaborRole.NONE,
          SurplusRole.SURPLUS_RECEIVER,
          0);
  public static final ClassPosition MIDDLE_PEASANT =
      new ClassPosition(
          MIDDLE_PEASANT_ID,
          "中农/自耕农",
          RelationToMeans.MIXED,
          LaborRole.BOTH,
          SurplusRole.SELF_SUBSISTENCE,
          1);
  public static final ClassPosition TENANT =
      new ClassPosition(
          TENANT_ID,
          "佃农/贫农",
          RelationToMeans.DIRECT_LABORER,
          LaborRole.PROVIDER,
          SurplusRole.SHARE,
          2);
  public static final ClassPosition LABORER =
      new ClassPosition(
          LABORER_ID,
          "雇农",
          RelationToMeans.DIRECT_LABORER,
          LaborRole.PROVIDER,
          SurplusRole.WAGE_EARNER,
          3);

  private static final List<ClassPosition> CLASS_POSITIONS =
      List.of(LANDLORD, MIDDLE_PEASANT, TENANT, LABORER);

  public static List<ClassPosition> classPositions() {
    return CLASS_POSITIONS;
  }

  public static ClassPosition classPosition(String id) {
    for (ClassPosition position : CLASS_POSITIONS) {
      if (position.id().equals(id)) {
        return position;
      }
    }
    throw new IllegalArgumentException("unknown class position: " + id);
  }

  /** 单 mode：佃农制农业。规则写死在这里，不从 EconomyData 读。 */
  public static Mode tenancyMode() {
    LinkedHashMap<String, ClassRule> rules = new LinkedHashMap<>();
    rules.put(
        LANDLORD_ID,
        new ClassRule(1000L, 0L, LANDLORD_ID, InputResponsibility.OWNER, LossResponsibility.OWNER));
    rules.put(
        MIDDLE_PEASANT_ID,
        new ClassRule(
            0L, 0L, MIDDLE_PEASANT_ID, InputResponsibility.SELF, LossResponsibility.OPERATOR));
    rules.put(
        TENANT_ID,
        new ClassRule(
            1000L, 0L, TENANT_ID, InputResponsibility.SELF, LossResponsibility.HOUSEHOLD));
    rules.put(
        LABORER_ID,
        new ClassRule(0L, 1000L, LABORER_ID, InputResponsibility.NONE, LossResponsibility.LABORER));
    return new Mode("tenancy_agriculture", rules);
  }

  static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
