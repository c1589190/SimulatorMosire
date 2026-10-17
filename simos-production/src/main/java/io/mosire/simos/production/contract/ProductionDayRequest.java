package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>批量驱动口：一个生产日的全部实例请求</b>（D-032 约束设计书 §13.2 第 1 条；复核裁决 §2.2）。
 *
 * <p>★★ <b>为什么需要它（而不是让调用方自己循环 {@link ProductionRuntime#execute}）</b>：`execute` 是 <b>"一次 =
 * 该实例一个生产日"</b>（§13.2 冻结），而 economy 的一天要推进<b>全部</b>实例。让 economy 自己 循环会带来两个它管不了的东西：
 *
 * <ol>
 *   <li><b>同 tick 生效条件只能由 production 给</b>（裁决 §2.1）：economy 无从冻结"本 tick 的熟练度"；
 *   <li><b>失败语义的收敛点</b>（复核裁决 §2.3）：契约故障必须让<b>整次候选计算</b>停下并撤销， economy 逐条调 `execute`
 *       时没有"这一批"这个概念，也就没有可撤销的边界。
 * </ol>
 *
 * <p>★★ <b>它不是一条新的业务规则</b>：本请求只是"按<b>确定序</b>排列的一串 {@link Step}"， {@link
 * ProductionRuntime#executeDay} 对每一步做的仍然是同一个 {@link ProductionRuntime#execute}
 * ——<b>同语义、同公式、同回执</b>。批次级的东西只有三件：序、失败语义、末尾那一份 {@code changeSet()}。
 *
 * <p>★★ <b>顺序是内容的纯函数</b>：{@code steps} 一律保序（{@link List#copyOf} 保持迭代序）， 调用方按稳定键（实例
 * id）排好；引擎<b>不</b>再重排（重排会让"谁先关账"变成实现的私有决定）。
 *
 * @param steps 本生产日的实例请求，<b>按确定序</b>；不得为 null（什么都不做给空表）
 */
public record ProductionDayRequest(List<Step> steps) {

  public ProductionDayRequest {
    if (steps == null) {
      throw new IllegalArgumentException("ProductionDayRequest.steps 不得为 null（什么都不做给空表）");
    }
    for (Step step : steps) {
      Objects.requireNonNull(step, "ProductionDayRequest.steps 的元素不得为 null");
    }
    steps = List.copyOf(steps);
  }

  /** ★ 空日（合法：这个世界今天没有任何生产实例要推进）。 */
  public static ProductionDayRequest empty() {
    return new ProductionDayRequest(List.of());
  }

  /**
   * ★★ <b>一个生产日里的一步</b>——"推进这个实例"或"按需新建这个实例并推进它"（恰有其一）。
   *
   * <p>★ 两个分支与 {@link ProductionExecutionRequest} 的 {@code instance}/{@code creation} 逐字同构；
   * 本类型只是把"哪一步"从请求内部提到请求外面，好让<b>回执与拒绝都能按步对上</b>。
   *
   * @param instance 既有实例 ID；新建步为 empty
   * @param creation 新建实例请求；既有实例步为 empty
   * @param grantedScale 本次实际授予的规模（基准规模上限；见 {@link ProductionExecutionRequest}）
   * @param laborContributionsMilli 逐参与户×角色的实际劳动贡献（保序、{@code (household,role)} 唯一、逐项 &gt;0）
   * @param assetOccupancy 资产占用（按种类）；不得为 null、值必须为正（0 不进表）
   * @param finalInputsMilli 本次最终投入的原料（毫单位）；不得为 null、值必须为正（0 不进表）
   * @param attributes 方案级/非家户主体外部属性事实（原样传进 {@link ProductionExecutionRequest#attributes()}）
   * @param participantAttributes 逐参与户外部属性事实（原样传进 {@link
   *     ProductionExecutionRequest#participantAttributes()}）
   * @param householdLaborBudgetMilli 逐户当日可用劳动预算（原样传进 {@link
   *     ProductionExecutionRequest#householdLaborBudgetMilli()}）
   * @param managementLaborMilli 逐户管理劳动（原样传进 {@link
   *     ProductionExecutionRequest#managementLaborMilli()}）
   */
  public record Step(
      Optional<ProductionUnitId> instance,
      Optional<ProductionCreationRequest> creation,
      long grantedScale,
      List<ParticipantContribution> laborContributionsMilli,
      Map<AssetKind, Long> assetOccupancy,
      Map<CommodityId, Long> finalInputsMilli,
      ExternalAttributeFacts attributes,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
      Map<HouseholdId, Long> householdLaborBudgetMilli,
      Map<HouseholdId, Long> managementLaborMilli) {

    public Step {
      if (instance == null) {
        throw new IllegalArgumentException("ProductionDayRequest.Step.instance 不得为 null");
      }
      if (creation == null) {
        throw new IllegalArgumentException("ProductionDayRequest.Step.creation 不得为 null");
      }
      if (instance.isPresent() == creation.isPresent()) {
        throw new IllegalArgumentException(
            "ProductionDayRequest.Step 必须恰给一个：instance 或 creation（当前 instance="
                + instance.isPresent()
                + ", creation="
                + creation.isPresent()
                + "）");
      }
      // ★★ B11：三张表**就地**逐字展开冻结（照 MarketTable.hexes / EconomyMeta 政策表 / ProductionData 的形制）。
      //   ★ 为什么必须写在本构造器里、不能抽私有 helper：SpotBugs 的 EI_EXPOSE_REP/EI_EXPOSE_REP2 **不做跨过程
      //     分析**，只认它在这一个方法里看得见的 Collections.unmodifiableMap（本类 6 条：3 条"存进来" + 3 条
      //     "发出去"）。★ 本记录的字段语义**就是** {@link ProductionExecutionRequest} 的对应字段（类注："本类型
      //     只是把'哪一步'从请求内部提到请求外面"），故这里的守卫与它**逐字同款**——不另立第二套口径。
      //   ★ 可达路径逐值不变：{@code executeDay} 对每个未被跳过的步都会立刻走 {@code toExecutionRequest()}，
      //     同一组守卫原样会在那里执行；本批只是把"同一个判断"提前到值被造出来的地方。
      if (laborContributionsMilli == null) {
        throw new IllegalArgumentException(
            "ProductionDayRequest.Step.laborContributionsMilli 不得为 null（无出工用空表）");
      }
      List<ParticipantContribution> contributionCopy = new java.util.ArrayList<>();
      Set<ProductionParticipantKey> participantKeys = new LinkedHashSet<>();
      for (ParticipantContribution contribution : laborContributionsMilli) {
        if (contribution == null) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.laborContributionsMilli 不得含 null");
        }
        if (!participantKeys.add(contribution.participantKey())) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.laborContributionsMilli 的 (household,role) 不得重复: "
                  + contribution.participantKey());
        }
        contributionCopy.add(contribution);
      }
      laborContributionsMilli = Collections.unmodifiableList(contributionCopy); // ★ 冻在赋值处

      if (assetOccupancy == null) {
        throw new IllegalArgumentException(
            "ProductionDayRequest.Step.assetOccupancy 不得为 null（无占用用空表）");
      }
      Map<AssetKind, Long> assetCopy = new LinkedHashMap<>();
      for (Map.Entry<AssetKind, Long> entry : assetOccupancy.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.assetOccupancy 的键与值都不得为 null");
        }
        if (entry.getValue() <= 0L) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.assetOccupancy 的占用必须为正（0 不进表）: "
                  + entry.getKey()
                  + " = "
                  + entry.getValue());
        }
        assetCopy.put(entry.getKey(), entry.getValue());
      }
      assetOccupancy = Collections.unmodifiableMap(assetCopy); // ★ 冻在赋值处

      if (finalInputsMilli == null) {
        throw new IllegalArgumentException(
            "ProductionDayRequest.Step.finalInputsMilli 不得为 null（无投入用空表）");
      }
      Map<CommodityId, Long> inputCopy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : finalInputsMilli.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.finalInputsMilli 的键与值都不得为 null");
        }
        if (entry.getValue() <= 0L) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.finalInputsMilli 的投入必须为正（0 不进表）: "
                  + entry.getKey()
                  + " = "
                  + entry.getValue());
        }
        inputCopy.put(entry.getKey(), entry.getValue());
      }
      finalInputsMilli = Collections.unmodifiableMap(inputCopy); // ★ 冻在赋值处

      if (participantAttributes == null) {
        throw new IllegalArgumentException(
            "ProductionDayRequest.Step.participantAttributes 不得为 null（无属性用空表）");
      }
      Map<HouseholdId, ExternalAttributeFacts> participantAttributesCopy = new LinkedHashMap<>();
      for (Map.Entry<HouseholdId, ExternalAttributeFacts> entry :
          participantAttributes.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step.participantAttributes 的键与值都不得为 null");
        }
        participantAttributesCopy.put(entry.getKey(), entry.getValue());
      }
      participantAttributes = Collections.unmodifiableMap(participantAttributesCopy); // ★ 冻在赋值处
      householdLaborBudgetMilli =
          freezeNonNegativeByHousehold(householdLaborBudgetMilli, "householdLaborBudgetMilli");
      managementLaborMilli =
          freezeNonNegativeByHousehold(managementLaborMilli, "managementLaborMilli");
    }

    private static Map<HouseholdId, Long> freezeNonNegativeByHousehold(
        Map<HouseholdId, Long> source, String field) {
      if (source == null) {
        throw new IllegalArgumentException(
            "ProductionDayRequest.Step." + field + " 不得为 null（无值用空表）");
      }
      Map<HouseholdId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<HouseholdId, Long> entry : source.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step." + field + " 的键与值都不得为 null");
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "ProductionDayRequest.Step." + field + " 不得为负: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      return Collections.unmodifiableMap(copy);
    }

    /** ★ 把本步还原成引擎真正消费的那一个请求（两个分支共用 {@link ProductionExecutionRequest}）。 */
    public ProductionExecutionRequest toExecutionRequest() {
      return new ProductionExecutionRequest(
          instance,
          creation,
          grantedScale,
          laborContributionsMilli,
          assetOccupancy,
          finalInputsMilli,
          attributes,
          participantAttributes,
          householdLaborBudgetMilli,
          managementLaborMilli);
    }

    /** ★ 本步指向的实例 ID（新建步取创建请求里的那一个）——拒绝记录与日志用它。 */
    public ProductionUnitId unitId() {
      return instance.orElseGet(() -> creation.orElseThrow().unit());
    }
  }
}
