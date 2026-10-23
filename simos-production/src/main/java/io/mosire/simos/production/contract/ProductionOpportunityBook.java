package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ <b>D-032 L1b：本 tick 的生产机会簿</b>（架构 v1 §1.2 / §3；plan §3）。
 *
 * <p>★★ <b>只读纯派生</b>：从 {@link InventoryLotView} 的 LAND lot + 注入的产业定义 + 注入价格函数派生本 tick 的 {@link
 * ProductionOpportunity}。它只活本 tick，不落 ChangeSet / Codec，不读时钟、不掷随机数、不写任何状态。
 *
 * <p>★★ <b>两条分支</b>：
 *
 * <ul>
 *   <li><b>InProduction lot</b>：按 lot state 的 {@code planId} 查定义，继续当前阶段；缺定义 ⇒ {@code
 *       production-opportunity-plan-missing}；阶段不在方案中 ⇒ {@code
 *       production-opportunity-stage-unknown}；阶段进度超过 {@code durationDays} ⇒ 具名拒绝。
 *   <li><b>IDLE lot</b>：对每个传入方案各生成一个“从第一段新开”的机会；方案须有非空 stages 且 LAND 容量 {@code > 0}；方案 id 带 hex
 *       段时必须与 lot 所在地逐字相等，否则跳过。
 * </ul>
 *
 * <p>★ <b>legacy 边界</b>：本批只服务多阶段方案；{@code stages} 为空的 legacy 单周期方案<b>暂不生成</b>
 * opportunity（不是错误、也不伪造单阶段），后续批次再定义 legacy 机会形状。
 *
 * <p>★★ <b>expectedInputs / expectedOutputs 公式</b>（plan §3）：
 *
 * <pre>
 * 当前 stage：
 *   若 !startInputPaid && stageProgressDays == 0：+ startInputPerUnit × scale
 *   + tickInputPerUnit × max(0, durationDays - stageProgressDays) × scale
 * 后续每个 stage：
 *   + startInputPerUnit × scale + tickInputPerUnit × durationDays × scale
 * 预计产出：最后一个 HARVEST stage 的 outputPerUnit × scale × 1000（毫商品）
 * 劳动：plan.laborPerUnit × scale
 * </pre>
 *
 * <p>同商品用 {@code Math.addExact} 合并、保持首次出现序并冻结；乘法/加法一律 {@code Math.multiplyExact / addExact} 包成
 * {@link ProductionContractFault}，溢出 fail-closed。
 *
 * <p>★★ <b>估值</b>：{@code pricePerUnit} 返回每 1 商品单位的价格；每项估值 = {@code 毫商品量 × price / 1000}（BigInteger
 * 精确乘除）。任一正投入/正产出缺价 ⇒ {@code marketValue} / {@code expectedNet} 为空；{@code score = expectedNet /
 * max(1, inputCost)}（整数除法、允许负值）。全部缺价都不伪造。
 *
 * <p>★★ <b>排序</b>：{@code location.q/r → owner.value → lotId.value → planId.value}；列表保序不可变，重复 {@code
 * opportunityId} 具名拒绝。
 *
 * @param opportunities 已排序且不可变的 opportunities
 */
public record ProductionOpportunityBook(List<ProductionOpportunity> opportunities) {

  private static final long MILLI_PER_COMMODITY_UNIT = 1000L;

  private static final BigInteger MILLI_PER_COMMODITY_UNIT_BIG = BigInteger.valueOf(1000L);

  private static final Comparator<ProductionOpportunity> OPPORTUNITY_ORDER =
      Comparator.comparingInt((ProductionOpportunity opportunity) -> opportunity.location().q())
          .thenComparingInt(opportunity -> opportunity.location().r())
          .thenComparing(opportunity -> opportunity.ownerHousehold().value())
          .thenComparing(opportunity -> opportunity.carrierLotIds().get(0).value())
          .thenComparing(opportunity -> opportunity.planId().value());

  public ProductionOpportunityBook {
    if (opportunities == null) {
      throw new IllegalArgumentException(
          "ProductionOpportunityBook.opportunities 不得为 null（空簿用空列表）");
    }
    List<ProductionOpportunity> copy = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ProductionOpportunity opportunity : opportunities) {
      if (opportunity == null) {
        throw new IllegalArgumentException("ProductionOpportunityBook.opportunities 不得含 null");
      }
      if (!seen.add(opportunity.opportunityId())) {
        throw new IllegalArgumentException(
            "production-opportunity-duplicate-id: " + opportunity.opportunityId());
      }
      copy.add(opportunity);
    }
    opportunities = Collections.unmodifiableList(copy); // ★ 保序不可变
  }

  /**
   * 从当前 tick 的库存视图派生机会簿。
   *
   * @param view 当前 tick 的库存 lot 视图
   * @param industries 方案定义；键 = 方案 id、值 = 定义（IDLE 分支遍历全部值）
   * @param pricePerUnit 每 1 商品单位的价格；返回 empty 表示缺价
   */
  public static ProductionOpportunityBook from(
      InventoryLotView view,
      Map<IndustryId, IndustryDefinitionView> industries,
      Function<CommodityId, OptionalLong> pricePerUnit) {
    Objects.requireNonNull(view, "ProductionOpportunityBook.from 的 view 不得为 null");
    Objects.requireNonNull(industries, "ProductionOpportunityBook.from 的 industries 不得为 null");
    Objects.requireNonNull(pricePerUnit, "ProductionOpportunityBook.from 的 pricePerUnit 不得为 null");
    for (Map.Entry<IndustryId, IndustryDefinitionView> entry : industries.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionOpportunityBook.from 的 industries 不得含 null 键/值");
      }
    }
    List<ProductionOpportunity> derived = new ArrayList<>();
    for (InventoryLot lot : view.lots()) {
      if (lot.itemKind() != InventoryLotKind.LAND) {
        continue; // 普通商品/资产不生成生产机会
      }
      LotState state = lot.state();
      if (state instanceof LotState.InProduction inProduction) {
        ProductionOpportunity opportunity =
            inProductionOpportunity(lot, inProduction, industries, pricePerUnit);
        if (opportunity != null) {
          derived.add(opportunity);
        }
      } else if (state instanceof LotState.Idle) {
        for (IndustryDefinitionView plan : industries.values()) {
          ProductionOpportunity opportunity = idleOpportunity(lot, plan, pricePerUnit);
          if (opportunity != null) {
            derived.add(opportunity);
          }
        }
      } else {
        throw new ProductionContractFault(
            "production-opportunity-lot-state-unsupported",
            "LAND lot " + lot.lotId().value() + " 的状态不是 IDLE/InProduction: " + state);
      }
    }
    derived.sort(OPPORTUNITY_ORDER);
    return new ProductionOpportunityBook(derived);
  }

  /** 本簿中该 opportunityId 的唯一机会；缺失具名抛。 */
  public ProductionOpportunity require(String opportunityId) {
    Objects.requireNonNull(opportunityId, "opportunityId");
    for (ProductionOpportunity opportunity : opportunities) {
      if (opportunity.opportunityId().equals(opportunityId)) {
        return opportunity;
      }
    }
    throw new IllegalArgumentException("production-opportunity-not-found: " + opportunityId);
  }

  /** 该 owner 的全部机会，保持簿顺序且不可变。 */
  public List<ProductionOpportunity> byOwner(HouseholdId owner) {
    Objects.requireNonNull(owner, "owner");
    List<ProductionOpportunity> matches = new ArrayList<>();
    for (ProductionOpportunity opportunity : opportunities) {
      if (opportunity.ownerHousehold().equals(owner)) {
        matches.add(opportunity);
      }
    }
    return Collections.unmodifiableList(matches);
  }

  private static ProductionOpportunity inProductionOpportunity(
      InventoryLot lot,
      LotState.InProduction state,
      Map<IndustryId, IndustryDefinitionView> industries,
      Function<CommodityId, OptionalLong> pricePerUnit) {
    IndustryId planId = IndustryId.parse(state.planId());
    IndustryDefinitionView plan = industries.get(planId);
    if (plan == null) {
      throw new ProductionContractFault(
          "production-opportunity-plan-missing",
          "LAND lot " + lot.lotId().value() + " 引用的方案不在 industries 中: " + planId.value());
    }
    if (plan.stages().isEmpty()) {
      return null; // legacy 单周期：L1b 暂不生成机会（见类注）
    }
    int stageIndex = stageIndex(plan.stages(), state.stageId());
    if (stageIndex < 0) {
      throw new ProductionContractFault(
          "production-opportunity-stage-unknown",
          "LAND lot "
              + lot.lotId().value()
              + " 的阶段 "
              + state.stageId()
              + " 不在方案 "
              + plan.id().value()
              + " 的 stages 中");
    }
    ProductionStageDefinition stage = plan.stages().get(stageIndex);
    if (state.stageProgressDays() > stage.durationDays()) {
      throw new ProductionContractFault(
          "production-opportunity-stage-progress-out-of-range",
          "LAND lot "
              + lot.lotId().value()
              + " 的阶段进度 "
              + state.stageProgressDays()
              + " 超过阶段 "
              + stage.stageId()
              + " 的 durationDays="
              + stage.durationDays());
    }
    return buildOpportunity(
        lot,
        plan,
        stageIndex,
        state.stageProgressDays(),
        state.cycleProgressDays(),
        state.startInputPaid(),
        pricePerUnit);
  }

  private static ProductionOpportunity idleOpportunity(
      InventoryLot lot,
      IndustryDefinitionView plan,
      Function<CommodityId, OptionalLong> pricePerUnit) {
    if (plan.stages().isEmpty()) {
      return null; // legacy 单周期：L1b 暂不生成机会（见类注）
    }
    Long capacity = plan.capacityPerUnit().get(AssetKind.LAND);
    if (capacity == null || capacity <= 0L) {
      return null; // 方案不消耗 LAND ⇒ 不适用于土地 lot
    }
    Optional<String> planHex = IndustryHexKeys.hexKeyOf(plan.id());
    if (planHex.isPresent()) {
      HexCoord location = lot.location().orElseThrow();
      String lotHex = IndustryHexKeys.hexKey(location.q(), location.r());
      if (!planHex.get().equals(lotHex)) {
        return null; // 方案绑定到别的格
      }
    }
    return buildOpportunity(lot, plan, 0, 0L, 0L, false, pricePerUnit);
  }

  private static ProductionOpportunity buildOpportunity(
      InventoryLot lot,
      IndustryDefinitionView plan,
      int stageIndex,
      long stageProgressDays,
      long cycleProgressDays,
      boolean startInputPaid,
      Function<CommodityId, OptionalLong> pricePerUnit) {
    Long capacity = plan.capacityPerUnit().get(AssetKind.LAND);
    if (capacity == null || capacity <= 0L) {
      throw new ProductionContractFault(
          "production-opportunity-land-capacity-missing",
          "LAND lot "
              + lot.lotId().value()
              + " 的方案 "
              + plan.id().value()
              + " 没有正的 LAND capacityPerUnit");
    }
    long productionScale = lot.quantityMilli() / capacity;
    if (productionScale <= 0L) {
      return null; // 不足一个单位规模：不伪造半单位
    }
    ProductionStageDefinition stage = plan.stages().get(stageIndex);
    Map<CommodityId, Long> expectedInputs =
        expectedInputs(
            plan.stages(), stageIndex, stageProgressDays, startInputPaid, productionScale);
    Map<CommodityId, Long> expectedOutputs = expectedOutputs(plan.stages(), productionScale);
    OptionalLong marketValue = valuation(expectedOutputs, pricePerUnit, "marketValue");
    OptionalLong inputCost = valuation(expectedInputs, pricePerUnit, "inputCost");
    OptionalLong expectedNet = OptionalLong.empty();
    OptionalLong score = OptionalLong.empty();
    if (marketValue.isPresent() && inputCost.isPresent()) {
      long net =
          exactLong(
              BigInteger.valueOf(marketValue.getAsLong())
                  .subtract(BigInteger.valueOf(inputCost.getAsLong())),
              "expectedNet");
      expectedNet = OptionalLong.of(net);
      long denominator = Math.max(1L, inputCost.getAsLong());
      score =
          OptionalLong.of(
              exactLong(BigInteger.valueOf(net).divide(BigInteger.valueOf(denominator)), "score"));
    }
    long laborRequirementMilli = multiplyExact(plan.laborPerUnit(), productionScale);
    String opportunityId = lot.lotId().value() + "|" + plan.id().value();
    return new ProductionOpportunity(
        opportunityId,
        plan.id(),
        lot.location().orElseThrow(),
        lot.owner(),
        List.of(lot.lotId()),
        lot.quantityMilli(),
        productionScale,
        stage.stageId(),
        stageProgressDays,
        cycleProgressDays,
        expectedInputs,
        expectedOutputs,
        marketValue,
        expectedNet,
        score,
        laborRequirementMilli);
  }

  private static int stageIndex(List<ProductionStageDefinition> stages, String stageId) {
    for (int index = 0; index < stages.size(); index++) {
      if (stages.get(index).stageId().equals(stageId)) {
        return index;
      }
    }
    return -1;
  }

  private static Map<CommodityId, Long> expectedInputs(
      List<ProductionStageDefinition> stages,
      int currentStageIndex,
      long stageProgressDays,
      boolean startInputPaid,
      long productionScale) {
    Map<CommodityId, Long> merged = new LinkedHashMap<>();
    ProductionStageDefinition current = stages.get(currentStageIndex);
    if (!startInputPaid && stageProgressDays == 0L) {
      mergeScaled(merged, current.startInputPerUnit(), productionScale);
    }
    long remainingDays = Math.max(0L, current.durationDays() - stageProgressDays);
    if (remainingDays > 0L) {
      mergeScaledTimes(merged, current.tickInputPerUnit(), remainingDays, productionScale);
    }
    for (int index = currentStageIndex + 1; index < stages.size(); index++) {
      ProductionStageDefinition stage = stages.get(index);
      mergeScaled(merged, stage.startInputPerUnit(), productionScale);
      mergeScaledTimes(merged, stage.tickInputPerUnit(), stage.durationDays(), productionScale);
    }
    return Collections.unmodifiableMap(merged); // ★ 保序不可变
  }

  private static Map<CommodityId, Long> expectedOutputs(
      List<ProductionStageDefinition> stages, long productionScale) {
    for (int index = stages.size() - 1; index >= 0; index--) {
      ProductionStageDefinition stage = stages.get(index);
      if (stage.endAction() == ProductionStageEndAction.HARVEST) {
        Map<CommodityId, Long> outputs = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Long> entry : stage.outputPerUnit().entrySet()) {
          outputs.put(
              entry.getKey(),
              multiplyExact(
                  multiplyExact(entry.getValue(), productionScale), MILLI_PER_COMMODITY_UNIT));
        }
        return Collections.unmodifiableMap(outputs); // ★ 保序不可变
      }
    }
    throw new ProductionContractFault(
        "production-opportunity-harvest-stage-missing", "stages 中没有 HARVEST 阶段，无法派生最终产出");
  }

  private static void mergeScaled(
      Map<CommodityId, Long> target, Map<CommodityId, Long> line, long productionScale) {
    for (Map.Entry<CommodityId, Long> entry : line.entrySet()) {
      merge(target, entry.getKey(), multiplyExact(entry.getValue(), productionScale));
    }
  }

  private static void mergeScaledTimes(
      Map<CommodityId, Long> target,
      Map<CommodityId, Long> line,
      long times,
      long productionScale) {
    for (Map.Entry<CommodityId, Long> entry : line.entrySet()) {
      merge(
          target,
          entry.getKey(),
          multiplyExact(multiplyExact(entry.getValue(), times), productionScale));
    }
  }

  private static void merge(Map<CommodityId, Long> target, CommodityId commodity, long amount) {
    Long existing = target.get(commodity);
    if (existing == null) {
      target.put(commodity, amount);
    } else {
      target.put(commodity, addExact(existing, amount));
    }
  }

  private static OptionalLong valuation(
      Map<CommodityId, Long> amounts,
      Function<CommodityId, OptionalLong> pricePerUnit,
      String label) {
    BigInteger total = BigInteger.ZERO;
    for (Map.Entry<CommodityId, Long> entry : amounts.entrySet()) {
      long amountMilli = entry.getValue();
      if (amountMilli <= 0L) {
        continue; // 零/负（不应存在）不要求价格
      }
      OptionalLong price = pricePerUnit.apply(entry.getKey());
      if (price == null) {
        throw new ProductionContractFault(
            "production-opportunity-price-null",
            label + " 的价格函数对 " + entry.getKey().value() + " 返回 null（缺价请返回 empty）");
      }
      if (price.isEmpty()) {
        return OptionalLong.empty(); // 任一正投入/正产出缺价 ⇒ 整体不估值
      }
      BigInteger value =
          BigInteger.valueOf(amountMilli)
              .multiply(BigInteger.valueOf(price.getAsLong()))
              .divide(MILLI_PER_COMMODITY_UNIT_BIG);
      total = total.add(value);
    }
    return OptionalLong.of(exactLong(total, label));
  }

  private static long multiplyExact(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new ProductionContractFault(
          "production-opportunity-arithmetic-overflow", "机会派生乘法溢出 long: " + left + " × " + right);
    }
  }

  private static long addExact(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new ProductionContractFault(
          "production-opportunity-arithmetic-overflow", "机会派生加法溢出 long: " + left + " + " + right);
    }
  }

  private static long exactLong(BigInteger value, String label) {
    try {
      return value.longValueExact();
    } catch (ArithmeticException overflow) {
      throw new ProductionContractFault(
          "production-opportunity-valuation-overflow", label + " 超出 long: " + value);
    }
  }
}
