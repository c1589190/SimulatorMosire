package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** economy 引用校验拆分 helper（由 EconomyReferenceValidation 抽取；行为不变）。 */
final class ProductionReferenceValidation {

  private ProductionReferenceValidation() {}

  /**
   * ★★★ <b>D-032 D4-Ib1：资产占用授予的跨表校验</b>（D4 §4.3 / §8.2）。
   *
   * <ul>
   *   <li>键 == 行内 id；
   *   <li>share 存在且 not dangling；unit 在 production 当刻实例投影存在；
   *   <li>share.industry / operator / location 与 unit 逐值一致；
   *   <li>grant.quantity ≤ share.quantity；
   *   <li>同一 share 同 interval 不得有两条 ACTIVE grant（具名 ERROR）。
   * </ul>
   *
   * <p>失败以 {@link IllegalArgumentException} 出面（候选态拒绝，不留 revision）；日志由错误事件在命令层统一记录。
   */
  public static void validateAssetOccupancies(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    Objects.requireNonNull(production, "production");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(label, "label");
    Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
        production.instances();
    List<AssetOccupancyGrant> active = new java.util.ArrayList<>();
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry :
        candidate.assetOccupancies().entrySet()) {
      AssetOccupancyGrantId key = entry.getKey();
      AssetOccupancyGrant grant = entry.getValue();
      if (!key.equals(grant.id())) {
        throw new IllegalArgumentException(
            label
                + " 的 assetOccupancies 键必须等于行内 id（asset-occupancy-key-mismatch）: 键="
                + key.value()
                + " id="
                + grant.id().value());
      }
      OwnershipStake share = candidate.assetShares().get(grant.shareId());
      if (share == null) {
        throw new IllegalArgumentException(
            label
                + " 的 grant 指名悬空 share（asset-occupancy-share-missing）: "
                + grant.id().value()
                + " → "
                + grant.shareId().value());
      }
      ProductionProcess unit = instances.get(grant.unitId());
      if (unit == null) {
        throw new IllegalArgumentException(
            label
                + " 的 grant 指名悬空 unit（asset-occupancy-unit-missing）: "
                + grant.id().value()
                + " → "
                + grant.unitId().value());
      }
      if (!share.industry().equals(unit.industry())
          || !share.operator().equals(unit.operator())
          || !share.location().equals(unit.location())) {
        throw new IllegalArgumentException(
            label
                + " 的 grant share/unit 的 industry/operator/location 不一致（asset-occupancy-scope-mismatch）: grant="
                + grant.id().value()
                + " share="
                + share.id().value()
                + " unit="
                + unit.id().value());
      }
      if (grant.quantity() > share.quantity()) {
        throw new IllegalArgumentException(
            label
                + " 的 grant 数量超过 share.quantity（asset-occupancy-quantity-exceeds-share）: grant="
                + grant.id().value()
                + " quantity="
                + grant.quantity()
                + " shareQuantity="
                + share.quantity());
      }
      if (grant.status() == AssetOccupancyStatus.ACTIVE) {
        active.add(grant);
      }
    }
    active.sort(
        java.util.Comparator.comparing((AssetOccupancyGrant grant) -> grant.shareId().value())
            .thenComparingLong(AssetOccupancyGrant::fromTick)
            .thenComparingLong(AssetOccupancyGrant::toTickExclusive)
            .thenComparing(grant -> grant.id().value()));
    for (int i = 0; i < active.size(); i++) {
      AssetOccupancyGrant left = active.get(i);
      for (int j = i + 1; j < active.size(); j++) {
        AssetOccupancyGrant right = active.get(j);
        if (left.shareId().equals(right.shareId())
            && left.unitId().equals(right.unitId())
            && left.overlaps(right.fromTick(), right.toTickExclusive())) {
          throw new IllegalArgumentException(
              label
                  + " 的同一 share 同一 unit 同时段存在重叠 ACTIVE grant（asset-occupancy-overlap:"
                  + left.shareId().value()
                  + ":"
                  + left.unitId().value()
                  + "）: grants="
                  + left.id().value()
                  + "/"
                  + right.id().value());
        }
      }
    }
    validateActiveOccupancyQuantities(active, candidate, label);
  }

  /**
   * 同一 share 上不同 unit 的 ACTIVE grant 可以重叠，但任意时刻的合计数量不得超过 share.quantity。
   *
   * <p>只在 grant 起点处取并集：ACTIVE 合计只在某个 grant 起点单调增加，随后只会在终点减少，因此逐个起点检查即可覆盖所有 时刻的最大并发占用。
   */
  static void validateActiveOccupancyQuantities(
      List<AssetOccupancyGrant> active, EconomyData candidate, String label) {
    Map<io.mosire.simos.economy.api.id.AssetShareId, java.util.List<AssetOccupancyGrant>> byShare =
        new java.util.LinkedHashMap<>();
    for (AssetOccupancyGrant grant : active) {
      byShare.computeIfAbsent(grant.shareId(), ignored -> new java.util.ArrayList<>()).add(grant);
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.AssetShareId, java.util.List<AssetOccupancyGrant>>
        entry : byShare.entrySet()) {
      OwnershipStake share = candidate.assetShares().get(entry.getKey());
      java.util.List<AssetOccupancyGrant> grants = entry.getValue();
      java.util.List<Long> moments = new java.util.ArrayList<>();
      for (AssetOccupancyGrant grant : grants) {
        moments.add(grant.fromTick());
      }
      moments.sort(Long::compare);
      for (long moment : moments) {
        long sum = 0L;
        for (AssetOccupancyGrant grant : grants) {
          if (grant.fromTick() <= moment && moment < grant.toTickExclusive()) {
            try {
              sum = Math.addExact(sum, grant.quantity());
            } catch (ArithmeticException overflow) {
              throw new IllegalArgumentException(
                  label
                      + " 的 ACTIVE quantity 合计溢出（asset-occupancy-quantity-overflow）: share="
                      + share.id().value()
                      + " tick="
                      + moment);
            }
          }
        }
        if (sum > share.quantity()) {
          throw new IllegalArgumentException(
              label
                  + " 的同一 share 在任何时刻的 ACTIVE 合计不得超过 share.quantity"
                  + "（asset-occupancy-overcommit）: share="
                  + share.id().value()
                  + " tick="
                  + moment
                  + " activeQuantity="
                  + sum
                  + " shareQuantity="
                  + share.quantity());
        }
      }
    }
  }

  /**
   * ★★★ <b>生产实例引用/构造守卫（B2 批：改经 {@code production.contract} 的只读投影）</b>。
   *
   * <p>★★ <b>为什么它必须存在</b>：{@code EconomyData} 曾用"第 14 个组件 {@code units}"的构造期守卫判死
   * "实例指名的方案不存在"一类悬空引用。B2 把那份组件（连同它的守卫）从 economy 删掉后，<b>守卫本身不许跟着消失</b> —— 本方法就是它的新落点：判据逐条保留，读的却是
   * {@link ProductionRuntime#units()} 这份 <b>同一候选态的只读投影</b>（I13：不得跨 revision 陈旧）。
   *
   * <p>★★ <b>判据逐条（与旧 {@code EconomyData} 的同名守卫逐字同义）</b>：
   *
   * <ul>
   *   <li>实例的键必须与行内 id 一致（键≠值 = 状态自相矛盾）；
   *   <li>实例指名的方案必须在 production 的当刻权威里存在；
   *   <li>{@code progressDays} 必须落在 {@code [0, cycleDays]} 闭区间；
   *   <li>实例的经营者（operator）不得为空、地点不得是 {@code UNSPECIFIED} 占位 （§13.8 裁定二：地点有语义之处读到占位 ⇒
   *       <b>具名拒绝</b>，不静默当合法地址用）。
   * </ul>
   *
   * <p>★ <b>只读</b>：只回答投影是否自洽，不改任何状态；失败以 {@link IllegalArgumentException} 出面。
   *
   * @param runtime 生产运行时（其 {@link ProductionRuntime#units()} 是唯一权威的只读投影）；不得为 null
   * @param label 进拒绝理由（命令/播种点）
   */
  public static void validateProductionInstances(ProductionRuntime runtime, String label) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(label, "label");
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> entry :
        runtime.units().entrySet()) {
      ProductionProcess unit = entry.getValue();
      if (!entry.getKey().equals(unit.id())) {
        throw new IllegalArgumentException(
            label
                + " 的生产实例键与行内 id 不一致（状态自相矛盾）: 键="
                + entry.getKey().value()
                + " 行内 id="
                + unit.id().value());
      }
      IndustryDefinitionView industry =
          runtime
              .definitions()
              .industry(unit.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的生产实例指名的方案定义不存在（production 当刻权威）: unit="
                              + entry.getKey().value()
                              + " plan="
                              + unit.industry().value()));
      if (unit.progressDays() < 0L || unit.progressDays() > industry.cycleDays()) {
        throw new IllegalArgumentException(
            label
                + " 的生产进度越界（闭区间 [0, cycleDays]）: unit="
                + entry.getKey().value()
                + " progressDays="
                + unit.progressDays()
                + " cycleDays="
                + industry.cycleDays());
      }
      if (unit.operator() == null) {
        throw new IllegalArgumentException(
            label + " 的生产实例没有经营者（状态自相矛盾）: unit=" + entry.getKey().value());
      }
      if (!unit.location().isSpecified()) {
        // ★ §13.8 裁定二第 1 条：地点有语义之处读到 UNSPECIFIED ⇒ 具名拒绝（不是静默跳过）。
        throw new IllegalArgumentException(
            label
                + " 的生产实例地点是 UNSPECIFIED 占位（不是合法格）: unit="
                + entry.getKey().value()
                + " plan="
                + unit.industry().value());
      }
    }
  }

  /**
   * unit→industry 存在 + {@code progressDays ≤ cycleDays}；assetShare→industry 存在。
   *
   * <p>★ B2b：实例侧改读 {@code production.instances()}（权威投影），份额侧仍读 economy 的 {@code assetShares}。
   */
  public static void validateUnitsAndShares(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> entry :
        production.instances().entrySet()) {
      ProductionProcess unit = entry.getValue();
      IndustryDefinitionView industry =
          definitions
              .industry(unit.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的生产单元指名的产业定义不存在（production 当刻权威）: unit="
                              + entry.getKey().value()
                              + " industry="
                              + unit.industry().value()));
      if (unit.progressDays() > industry.cycleDays()) {
        throw new IllegalArgumentException(
            label
                + " 的生产进度超过产业周期：unit="
                + entry.getKey().value()
                + " progressDays="
                + unit.progressDays()
                + " cycleDays="
                + industry.cycleDays());
      }
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> entry :
        candidate.assetShares().entrySet()) {
      OwnershipStake share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            label
                + " 的资产份额键必须与行内 id 一致: 键="
                + entry.getKey().value()
                + " 行内 id="
                + share.id().value());
      }
      OwnershipStake.requireSpecifiedLocation(share.location());
      if (!OwnershipStake.idEmbedsLocation(share.id(), share.location())) {
        throw new IllegalArgumentException(
            label
                + " 的资产份额 id 与其 location 不一致（asset-location-id-mismatch）: share="
                + share.id().value()
                + " location="
                + share.location().value());
      }
      if (definitions.industry(share.industry()).isEmpty()) {
        throw new IllegalArgumentException(
            label
                + " 的资产份额指名的产业定义不存在（production 当刻权威）: share="
                + entry.getKey().value()
                + " industry="
                + share.industry().value());
      }
    }
  }

  /**
   * 输出覆盖：产业存在，且每个商品都在该产业 {@code recipe().outputPerUnit()} 键里。
   *
   * <p>★ B2b：改读 {@code production.outputQuantityOverrides()}（GM 覆盖的唯一权威）。
   */
  public static void validateOutputQuantityOverrides(
      ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionStateLookup production,
      String label) {
    for (Map.Entry<IndustryId, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> entry :
        production.outputQuantityOverrides().entrySet()) {
      IndustryDefinitionView industry =
          definitions
              .industry(entry.getKey())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的输出覆盖指名产业不存在（production 当刻权威）: industry="
                              + entry.getKey().value()));
      for (io.mosire.simos.economy.api.id.CommodityId commodity : entry.getValue().keySet()) {
        if (!industry.recipe().outputPerUnit().containsKey(commodity)) {
          throw new IllegalArgumentException(
              label
                  + " 的输出覆盖商品不在该产业配方产出键里: industry="
                  + entry.getKey().value()
                  + " commodity="
                  + commodity.value());
        }
      }
    }
  }

  /** mode/结构关联：classStructure/classPosition/组织/资产规则/模式变迁指名的 mode 必须存在。 */
  public static void validateModeAssociations(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (ClassStructure structure : candidate.classStructures().values()) {
      requireMode(definitions, label, "classStructure", structure.id().value(), structure.modeId());
    }
    for (ProductionRole position : candidate.classPositions().values()) {
      requireMode(definitions, label, "classPosition", position.id().value(), position.modeId());
    }
    for (ProductionEnterprise enterprise : candidate.productionOrganizations().values()) {
      requireMode(
          definitions,
          label,
          "productionOrganization",
          enterprise.id().value(),
          enterprise.modeId());
    }
    for (AssetRule rule : candidate.assetRules().values()) {
      requireMode(definitions, label, "assetRule", rule.id().value(), rule.modeId());
    }
    for (ModeTransition transition : candidate.modeTransitions().values()) {
      requireMode(
          definitions,
          label,
          "modeTransition.from",
          transition.id().value(),
          transition.fromModeId());
      requireMode(
          definitions, label, "modeTransition.to", transition.id().value(), transition.toModeId());
    }
  }

  /** mode→classStructure 反向：production 的每个 mode 指名的结构必须在 economy 候选态里存在（与上面的结构→mode 合成双向闭环）。 */
  public static void validateModeStructures(
      ProductionDefinitionLookup definitions, EconomyData candidate, String label) {
    for (io.mosire.simos.production.contract.ProductionModeDefinitionView mode :
        definitions.modes().values()) {
      if (!candidate.classStructures().containsKey(mode.classStructureId())) {
        throw new IllegalArgumentException(
            label
                + " 的生产方式指名的阶层结构不存在: mode="
                + mode.id().value()
                + " classStructureId="
                + mode.classStructureId().value()
                + "（同一 submitBatch 原子批里与 economy.GmAdjust.upsertClassStructure 一起创建即可）");
      }
    }
  }

  /**
   * ★★★ <b>B2b：实例 ↔ economy 表的交叉引用守卫</b>（原 {@code EconomyData} 构造期守卫的<b>新落点</b>）。
   *
   * <p>★★ <b>为什么必须重落而不是删</b>：{@code units} 从 economy 删除后，"economy 的表指着一条不存在的实例" 这类悬空引用就再也没有本域可查的参照物
   * —— 判据本身仍是 <b>fail-closed</b> 的一致性要求，故改读 production 的 当刻实例投影（{@link
   * io.mosire.simos.production.contract.ProductionStateLookup#instances()}）， 由候选提交前的跨片校验面执行。
   *
   * <p>★★ <b>判据逐条（与旧构造期守卫逐字同义）</b>：
   *
   * <ul>
   *   <li>{@code relations}：关系指名的实例必须存在，且 {@code relation.operator() == unit.operator()}
   *       （"谁经营"不许两处拼写）；
   *   <li>{@code operatorConditions}：键的实例必须存在，且值内 {@code industry == unit.industry()}；
   *   <li>{@code allocations}：{@code activity} 若形如 unit id（{@code unit-} 前缀）必须指向存在的实例 （悬空 ⇒
   *       具名拒；自由家户劳动用非 unit 活动词，合法）；命中实例时 {@code actor == unit.operator()}；
   *   <li>{@code productionOrganizations}：{@code unitId} 存在时必须指向存在的实例，且 {@code organizer ==
   *       unit.operator()}。
   * </ul>
   *
   * <p>★ <b>只读</b>：失败以 {@link IllegalArgumentException} 出面（命令层折 {@code Rejected}，不留 revision）。
   */
  public static void validateUnitCrossReferences(
      io.mosire.simos.production.contract.ProductionStateLookup production,
      EconomyData candidate,
      String label) {
    Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
        production.instances();
    Map<String, ProductionProcess> byValue = new java.util.LinkedHashMap<>();
    for (ProductionProcess unit : instances.values()) {
      byValue.put(unit.id().value(), unit);
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionRules> entry :
        candidate.relations().entrySet()) {
      ProductionRules relation = entry.getValue();
      ProductionProcess unit = byValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的关系指名的生产实例不存在（production 当刻权威）: " + entry.getKey().value());
      }
      if (!relation.operator().equals(unit.operator())) {
        throw new IllegalArgumentException(
            label
                + " 的关系 operator 必须与实例 operator 一致（同一件事不许两处拼写）："
                + entry.getKey().value()
                + " 关系="
                + relation.operator()
                + "，实例="
                + unit.operator());
      }
    }
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, OperatorCondition> entry :
        candidate.operatorConditions().entrySet()) {
      ProductionProcess unit = byValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的经营者条件指名的生产实例不存在（production 当刻权威）: " + entry.getKey().value());
      }
      if (!unit.industry().equals(entry.getValue().industry())) {
        throw new IllegalArgumentException(
            label
                + " 的经营者条件值内 industry 必须与实例 industry 一致：键="
                + entry.getKey().value()
                + "，实例 industry="
                + unit.industry().value()
                + "，值内="
                + entry.getValue().industry().value());
      }
    }
    for (HouseholdLaborCommitment commitment : candidate.allocations().values()) {
      ProductionProcess unit = byValue.get(commitment.activity());
      if (unit == null) {
        if (commitment.activity().startsWith("unit-")) {
          throw new IllegalArgumentException(
              label
                  + " 的劳动配额 activity 看起来是 unit id 但该实例不存在（悬空引用；自由家户劳动请用非 unit 的活动词）: "
                  + commitment.id().value()
                  + " activity="
                  + commitment.activity());
        }
        continue;
      }
      if (!unit.operator().equals(commitment.actor())) {
        throw new IllegalArgumentException(
            label
                + " 的劳动配额 activity 指名的实例与 actor 不一致（同一件事不许两处拼写）：activity="
                + commitment.activity()
                + " 实例 operator="
                + unit.operator()
                + "，actor="
                + commitment.actor());
      }
    }
    for (ProductionEnterprise enterprise : candidate.productionOrganizations().values()) {
      if (enterprise.unitId().isEmpty()) {
        continue;
      }
      String unitId = enterprise.unitId().get().value();
      ProductionProcess unit = byValue.get(unitId);
      if (unit == null) {
        throw new IllegalArgumentException(
            label + " 的生产组织指名的实例必须已存在：组织=" + enterprise.id().value() + "，unitId=" + unitId);
      }
      if (!unit.operator().equals(enterprise.organizer())) {
        throw new IllegalArgumentException(
            label
                + " 的生产组织 organizer 必须与它指名实例的 operator 一致（同一件事不许两处拼写）：组织="
                + enterprise.id().value()
                + " organizer="
                + enterprise.organizer()
                + "，实例 operator="
                + unit.operator());
      }
    }
  }

  static void requireMode(
      ProductionDefinitionLookup definitions,
      String label,
      String where,
      String owner,
      io.mosire.simos.economy.api.id.ProductionModeId modeId) {
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label
              + " 的 "
              + where
              + " 指名生产方式不存在（production 当刻权威）: "
              + owner
              + " mode="
              + modeId.value());
    }
  }
}
