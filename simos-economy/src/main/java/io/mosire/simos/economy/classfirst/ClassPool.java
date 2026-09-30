package io.mosire.simos.economy.classfirst;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶层池：阶层本身固定，池持有 {@code population / labor / assetVector / debt}，以及派生的 A_C / x_C。
 *
 * <p>★ <b>线格式（R2a）</b>：本类是可变工作表、不是 record，所以裸 Jackson 不认识它的 no-arg 访问器；
 * {@code @JsonSerialize}/{@code @JsonDeserialize} 把 {@link ClassPoolJson} 的字段集直接钉在类型上 ⇒ <b>任何</b>
 * mapper （EconomyCodec 私有 mapper、Timeline 的 changeset mapper、诊断裸 mapper）写出的 {@code ClassPool}
 * 都是同一份字段集， 读回都走 {@link ClassPool#restored}。
 *
 * <p>家户只在池内以 {@code HouseholdAccount(pool, household, sharePerMille)} 存在，负责生产/分配/消费/劳动；
 * 库存资产的唯一权威写口是本类的 {@link #addStock}/{@link #takeStock}，迁移 bundle 也只能经这两个方法转移库存，
 * 因此不存在"家户搬走资产、池里还留一份"的复制路径。
 *
 * <p>资产向量里的 {@code OPERATED_LAND}、{@code LEASE_SECURITY}、{@code DEBT} 是派生维度：
 * 前两者分别由本期经营规划和租约权利折算得到，{@code DEBT} 由双边账户的负净额逐 tick 重算，都不参与库存守恒。
 */
@JsonSerialize(using = ClassPoolJson.Serializer.class)
@JsonDeserialize(using = ClassPoolJson.Deserializer.class)
public final class ClassPool {

  private final String modeId;
  private final String classPositionId;
  private final int tier;
  private long population;
  private long labor;
  private final LinkedHashMap<AssetKind, Long> assets = new LinkedHashMap<>();
  private final LinkedHashMap<String, Long> debtByUnit = new LinkedHashMap<>();
  private long debtGrainMilli;
  private long leaseHolding;
  private long laborEfficiencyPerMille = 1000L;
  private long flowUpRemainderMilli;
  private long flowDownRemainderMilli;
  private long collectionCooldownUntilTick;

  public ClassPool(String modeId, String classPositionId) {
    this.modeId = modeId;
    this.classPositionId = classPositionId;
    this.tier = tierOf(classPositionId);
    for (AssetKind kind : AssetKind.ordered()) {
      assets.put(kind, 0L);
    }
  }

  /** LABORER=0 → TENANT=1 → MIDDLE=2 → LANDLORD=3；向上 = tier+1。 */
  public static int tierOf(String classPositionId) {
    if (PilotModel.LABORER_ID.equals(classPositionId)) {
      return 0;
    }
    if (PilotModel.TENANT_ID.equals(classPositionId)) {
      return 1;
    }
    if (PilotModel.MIDDLE_PEASANT_ID.equals(classPositionId)) {
      return 2;
    }
    if (PilotModel.LANDLORD_ID.equals(classPositionId)) {
      return 3;
    }
    throw new IllegalArgumentException("unknown class position: " + classPositionId);
  }

  public String modeId() {
    return modeId;
  }

  public String classPositionId() {
    return classPositionId;
  }

  public int tier() {
    return tier;
  }

  public long population() {
    return population;
  }

  public long labor() {
    return labor;
  }

  public long leaseHolding() {
    return leaseHolding;
  }

  public long laborEfficiencyPerMille() {
    return laborEfficiencyPerMille;
  }

  public long flowUpRemainderMilli() {
    return flowUpRemainderMilli;
  }

  public long flowDownRemainderMilli() {
    return flowDownRemainderMilli;
  }

  public long collectionCooldownUntilTick() {
    return collectionCooldownUntilTick;
  }

  public long debtGrainMilli() {
    return debtGrainMilli;
  }

  public long stock(AssetKind kind) {
    return assets.getOrDefault(kind, 0L);
  }

  /** 报告用资产向量：库存量原值；OPERATED_LAND/LEASE_SECURITY 是折算量；DEBT 是 milli-grain 欠额。 */
  public LinkedHashMap<AssetKind, Long> assetVector() {
    LinkedHashMap<AssetKind, Long> vector = new LinkedHashMap<>();
    for (AssetKind kind : AssetKind.ordered()) {
      if (kind == AssetKind.DEBT) {
        vector.put(kind, debtGrainMilli);
      } else if (kind == AssetKind.LEASE_SECURITY) {
        vector.put(kind, leaseHolding);
      } else {
        vector.put(kind, assets.getOrDefault(kind, 0L));
      }
    }
    return vector;
  }

  public LinkedHashMap<String, Long> debtByUnit() {
    return new LinkedHashMap<>(debtByUnit);
  }

  /** A_C schema 用：统一返回千分单位。 */
  long stateMilli(AssetKind kind) {
    if (kind == AssetKind.DEBT) {
      return debtGrainMilli;
    }
    if (kind == AssetKind.LEASE_SECURITY) {
      return leaseHolding * 1000L;
    }
    return assets.getOrDefault(kind, 0L) * 1000L;
  }

  long takeStock(AssetKind kind, long amount) {
    if (amount <= 0L) {
      return 0L;
    }
    long available = Math.max(0L, assets.getOrDefault(kind, 0L));
    long taken = Math.min(available, amount);
    if (taken > 0L) {
      assets.put(kind, available - taken);
    }
    return taken;
  }

  void addStock(AssetKind kind, long amount) {
    if (amount > 0L) {
      assets.merge(kind, amount, Long::sum);
    }
  }

  void setPopulation(long value) {
    population = Math.max(0L, value);
  }

  void setLabor(long value) {
    labor = Math.max(0L, value);
  }

  void setLaborEfficiencyPerMille(long value) {
    laborEfficiencyPerMille = Math.max(0L, Math.min(1000L, value));
  }

  void setFlowUpRemainderMilli(long value) {
    flowUpRemainderMilli = Math.max(0L, value);
  }

  void setFlowDownRemainderMilli(long value) {
    flowDownRemainderMilli = Math.max(0L, value);
  }

  void setCollectionCooldownUntilTick(long value) {
    collectionCooldownUntilTick = value;
  }

  void setLeaseHolding(long value) {
    leaseHolding = Math.max(0L, value);
    assets.put(AssetKind.LEASE_SECURITY, leaseHolding);
  }

  void setOperatedLand(long value) {
    assets.put(AssetKind.OPERATED_LAND, Math.max(0L, value));
  }

  void setDebtState(LinkedHashMap<String, Long> debtByUnit, long debtGrainMilli) {
    this.debtByUnit.clear();
    this.debtByUnit.putAll(debtByUnit);
    this.debtGrainMilli = Math.max(0L, debtGrainMilli);
  }

  public Map<AssetKind, Long> readOnlyAssets() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(assetVector()));
  }

  /** ★ R1：深拷贝 —— {@link ClassFirstState} 用它在不可变边界复制池；引擎用它在 restore 时把持久池拷回工作表。 拷贝后两份池互不影响。 */
  public ClassPool copy() {
    ClassPool copy = new ClassPool(modeId, classPositionId);
    copy.population = population;
    copy.labor = labor;
    copy.assets.clear();
    copy.assets.putAll(assets);
    copy.debtByUnit.clear();
    copy.debtByUnit.putAll(debtByUnit);
    copy.debtGrainMilli = debtGrainMilli;
    copy.leaseHolding = leaseHolding;
    copy.laborEfficiencyPerMille = laborEfficiencyPerMille;
    copy.flowUpRemainderMilli = flowUpRemainderMilli;
    copy.flowDownRemainderMilli = flowDownRemainderMilli;
    copy.collectionCooldownUntilTick = collectionCooldownUntilTick;
    return copy;
  }

  /**
   * ★ R1：从持久形态重建池（唯一入口，供 {@code EconomyCodec} 的读侧调用）。传入的库存只允许是真实库存维度； OPERATED_LAND /
   * LEASE_SECURITY / DEBT 由专用参数重建，避免同一维度两处拼写。
   */
  public static ClassPool restored(
      String modeId,
      String classPositionId,
      long population,
      long labor,
      Map<AssetKind, Long> stocks,
      long operatedLand,
      long leaseHolding,
      long laborEfficiencyPerMille,
      long flowUpRemainderMilli,
      long flowDownRemainderMilli,
      long collectionCooldownUntilTick,
      Map<String, Long> debtByUnit,
      long debtGrainMilli) {
    ClassPool pool = new ClassPool(modeId, classPositionId);
    if (stocks != null) {
      for (Map.Entry<AssetKind, Long> entry : stocks.entrySet()) {
        AssetKind kind = entry.getKey();
        if (kind == null || !kind.stock()) {
          throw new IllegalArgumentException(
              "ClassPool.restored 的库存维度必须是真实库存（stock=true），收到: " + kind);
        }
        pool.assets.put(kind, Math.max(0L, entry.getValue() == null ? 0L : entry.getValue()));
      }
    }
    pool.setPopulation(population);
    pool.setLabor(labor);
    pool.setLaborEfficiencyPerMille(laborEfficiencyPerMille);
    pool.setFlowUpRemainderMilli(flowUpRemainderMilli);
    pool.setFlowDownRemainderMilli(flowDownRemainderMilli);
    pool.setCollectionCooldownUntilTick(collectionCooldownUntilTick);
    pool.setLeaseHolding(leaseHolding);
    pool.setOperatedLand(operatedLand);
    LinkedHashMap<String, Long> debts = new LinkedHashMap<>();
    if (debtByUnit != null) {
      debts.putAll(debtByUnit);
    }
    pool.setDebtState(debts, debtGrainMilli);
    return pool;
  }

  /** ★ R1：值相等 —— 变更集差异/重建与状态往返都按值判等（同 record 状态的口径）。 */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClassPool that)) {
      return false;
    }
    return population == that.population
        && labor == that.labor
        && debtGrainMilli == that.debtGrainMilli
        && leaseHolding == that.leaseHolding
        && laborEfficiencyPerMille == that.laborEfficiencyPerMille
        && flowUpRemainderMilli == that.flowUpRemainderMilli
        && flowDownRemainderMilli == that.flowDownRemainderMilli
        && collectionCooldownUntilTick == that.collectionCooldownUntilTick
        && modeId.equals(that.modeId)
        && classPositionId.equals(that.classPositionId)
        && assets.equals(that.assets)
        && debtByUnit.equals(that.debtByUnit);
  }

  /** ★ R1：与 {@link #equals(Object)} 同源的值哈希。 */
  @Override
  public int hashCode() {
    return java.util.Objects.hash(
        modeId,
        classPositionId,
        population,
        labor,
        assets,
        debtByUnit,
        debtGrainMilli,
        leaseHolding,
        laborEfficiencyPerMille,
        flowUpRemainderMilli,
        flowDownRemainderMilli,
        collectionCooldownUntilTick);
  }

  @Override
  public String toString() {
    return "ClassPool["
        + modeId
        + ":"
        + classPositionId
        + " population="
        + population
        + " labor="
        + labor
        + " assets="
        + assets
        + " debtGrainMilli="
        + debtGrainMilli
        + "]";
  }
}
