package io.mosire.simos.economy.pilot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立最小试点（单 mode：佃农制农业）的数据形状与阶层语义。
 *
 * <p>★ 边界：本包不读写 {@code EconomyData}、不碰三国 compact world、不依赖 E1–E6。全部字段都是整数（{@code long}）与 {@link
 * LinkedHashMap}，没有任何随机数/UUID/时钟；同样的输入必然得到同样的 360 tick 轨迹。
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

  /** 报告里的迁移/催收/红灯事件种类。 */
  public enum TransitionKind {
    POPULATION_FLOW,
    CLASS_DOWNGRADE,
    LAND_SEIZED,
    LIQUID_SEIZED,
    DEBT_CAPITALIZED,
    BORROW_GOODS,
    BORROW_MONEY,
    BUY_GRAIN,
    RED_LIGHT
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
   * 家户（初始夹具与只读快照）。goods/money/land/tools 都是家户私有库存；{@code laborPerCapita} 的单位是"千分劳动者" （500 = 0.5
   * 个全劳力/人/ tick）。
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

  /**
   * 独立放贷方（模拟 GOV/特殊单位）：不属于农业阶层结构，默认资金很多、流动性为 0，利率/条款显式配置。
   *
   * <p>它只通过 {@link ClassFirstPilotEngine} 的借贷账户和家户发生关系，不出现在四个阶层位置里。
   */
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

  /** 地主催收/人口流动政策（试点显式配置，不是从别处推导）。 */
  public record CollectionPolicy(
      String collectorClassPositionId,
      long collectionThreshold,
      long collectionTriggerRatioPerMille,
      long collectionRatioPerMille,
      long landPricePerUnit,
      SeizurePriority seizurePriority,
      long baseFlowPerMille,
      long flowSlopePerMille,
      long maxFlowPerMille) {}

  /** 单个生产组织（家户）的本期计划，收支双方都显式记录。 */
  public record ProductionPlan(
      long tick,
      String householdId,
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
      Map<String, Long> externalLaborByProvider,
      Map<String, Long> seedExternalByProvider) {

    public ProductionPlan {
      externalLaborByProvider = immutableCopy(externalLaborByProvider);
      seedExternalByProvider = immutableCopy(seedExternalByProvider);
    }
  }

  /** 本期生产账户：投入、产出、按阶层位置的实际分配。 */
  public record ProductionAccount(
      long tick,
      String householdId,
      String classPositionId,
      long plannedLand,
      long actualLand,
      long outputGrain,
      long seedSelfUsed,
      long seedExternalUsed,
      long externalSeedPaid,
      long rentPaid,
      long wagePaid,
      long residualPaid,
      Map<String, Long> distributedByClass,
      List<String> shortages) {

    public ProductionAccount {
      distributedByClass = immutableCopy(distributedByClass);
      shortages = List.copyOf(shortages);
    }
  }

  /**
   * 滚动账户快照（家户或放贷方视角）。
   *
   * <p>{@code cumulativeNet} 有符号：{@code >0} ⇒ 对手方欠 {@code household}（claim）；{@code <0} ⇒ {@code
   * household} 欠对手方（debt）。正净额永远不会被写成 debt，反之亦然。
   */
  public record HouseholdAccount(
      String householdId,
      String modeId,
      String counterpartyId,
      String unit,
      String terms,
      long cumulativeNet,
      long debt,
      long claim,
      long interestAccrued,
      long nextDueTick,
      AccountStatus status) {}

  /** 一次可报告事件（人口流动 / 阶层下调 / 收地 / 扣流动商品 / 资本化 / 借贷 / 红灯）。 */
  public record Transition(
      long tick,
      TransitionKind kind,
      String householdId,
      String counterpartyId,
      long populationMoved,
      long landSeized,
      long debtReduced,
      String reason) {}

  /** 单 tick 读数（全部是深拷贝，构造后不可变）。 */
  public record TickReport(
      long tick,
      Map<String, Long> populationByClass,
      Map<String, Long> householdsByClass,
      Map<String, Long> debtByClassGrainMilli,
      Map<String, Long> claimByClassGrainMilli,
      Map<String, Map<String, Long>> goodsByHousehold,
      Map<String, Long> moneyByHousehold,
      Map<String, Long> landByHousehold,
      Map<String, Long> toolsByHousehold,
      Map<String, Long> efficiencyByHousehold,
      long redLights,
      long baseRationGap,
      List<Transition> transitions,
      long landlordLandSharePerMille,
      long topHouseholdLandSharePerMille,
      List<String> diagnostics) {

    public TickReport {
      populationByClass = immutableCopy(populationByClass);
      householdsByClass = immutableCopy(householdsByClass);
      debtByClassGrainMilli = immutableCopy(debtByClassGrainMilli);
      claimByClassGrainMilli = immutableCopy(claimByClassGrainMilli);
      goodsByHousehold = deepImmutableCopy(goodsByHousehold);
      moneyByHousehold = immutableCopy(moneyByHousehold);
      landByHousehold = immutableCopy(landByHousehold);
      toolsByHousehold = immutableCopy(toolsByHousehold);
      efficiencyByHousehold = immutableCopy(efficiencyByHousehold);
      transitions = List.copyOf(transitions);
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

  private static Map<String, Map<String, Long>> deepImmutableCopy(
      Map<String, Map<String, Long>> source) {
    LinkedHashMap<String, Map<String, Long>> copy = new LinkedHashMap<>();
    if (source != null) {
      for (Map.Entry<String, Map<String, Long>> entry : source.entrySet()) {
        copy.put(entry.getKey(), immutableCopy(entry.getValue()));
      }
    }
    return Collections.unmodifiableMap(copy);
  }
}
