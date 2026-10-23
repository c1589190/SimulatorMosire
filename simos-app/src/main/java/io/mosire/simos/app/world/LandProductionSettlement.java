package io.mosire.simos.app.world;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.production.ProductionLotDayExecutor;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionClaim;
import io.mosire.simos.production.contract.ProductionOpportunity;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>D-032 L2b2a：ProductionClaim 的土地生产纯结算服务</b>（架构 v1 §1.4 / §1.5；plan §3.1）。
 *
 * <p>★★ <b>目标运行位置：S4 之前/之中</b>。S3 生成 {@link ProductionClaim}；本服务按 claim 输入顺序逐条读取 carrier lot /
 * opportunity / plan，调用 {@link ProductionLotDayExecutor} 做当日纯计算，成功后把投入从 operator 的 {@link
 * HouseholdInventory} 扣除、把普通产出入到 operator 库存，并把 lot 的新生产状态用 {@link LandProductionStateWriter} 一次性写回
 * Map。S5 按 lot 产出与交易保留状态不在本批；S3/S4 日循环接线也在 L2b2b，尚未接入。
 *
 * <p>★★ <b>输入/输出库存语义</b>：
 *
 * <ul>
 *   <li>{@code availableInputs} = operator 当前工作副本的<b>余额 − 冻结</b>（唯一算法 {@link AvailableStock}）；同一
 *       operator 的多个 claim 按输入顺序看到前序扣/加后的余额；
 *   <li>成功 claim 的 {@code consumedInputs} 逐商品从 operator 库存扣除；余额不足（理论上执行器已挡住，出现即契约故障）抛具名 {@link
 *       IllegalStateException} {@code production-claim-input-mutation-shortfall}，<b>绝不静默截断</b>；
 *   <li>普通 {@code outputs} 加到 operator 库存（v1 口径）；owner / operator 的分账留给后续经济层，本类不臆造；
 *   <li>{@code transport_capacity_service} 是内部服务键，<b>不是普通商品</b>：它不进任何 inventory，只聚合进 {@link
 *       Result#serviceOutputs()}。
 * </ul>
 *
 * <p>★★ <b>失败语义</b>：
 *
 * <ul>
 *   <li>同一 {@code lotId} 出现两次 ⇒ 整次调用 fail-closed，抛 {@link IllegalArgumentException} {@code
 *       production-claim-duplicate-lot}，不返回任何半成品；
 *   <li>owner 不匹配 / plan 缺失 / opportunity 缺失 / operator 库存缺失 / 执行器业务拒绝 ⇒ 记入 {@link
 *       Result#rejections()}，该 claim 不扣库存、不写 Map；
 *   <li>写入前任何契约/状态一致性故障 ⇒ 抛具名异常；此时不返回部分 actor / map。
 * </ul>
 *
 * <p>★★ <b>纯服务保证</b>：本类不构造 ChangeSet / Codec，不读写真实 EconomyData，不读时钟 / 随机数 / 日志 / 文件； 所有返回 map /
 * list 均为保序不可变（{@link LinkedHashMap} + {@link Collections#unmodifiableMap}、 {@link
 * Collections#unmodifiableList}），<b>绝不使用</b> {@code Map.copyOf} / {@code List.copyOf}。
 */
public final class LandProductionSettlement {

  private static final String OWNER_MISMATCH_REASON = "production-claim-owner-mismatch";
  private static final String LOT_MISSING_REASON = "production-claim-lot-missing";
  private static final String PLAN_MISSING_REASON = "production-claim-plan-missing";
  private static final String OPPORTUNITY_MISSING_REASON = "production-claim-opportunity-missing";
  private static final String OPERATOR_INVENTORY_MISSING_REASON =
      "production-claim-operator-inventory-missing";

  private LandProductionSettlement() {}

  /**
   * 一个成功 claim 的回执。
   *
   * @param claim 原始 claim
   * @param status 执行器返回的推进分类（非 REJECTED）
   * @param consumedInputs 本 tick 实际消耗（保序不可变）
   * @param outputs 本 tick 执行器产出的完整结果（含 service 键；保序不可变）
   * @param nextState 写回 carrier lot 的新生产状态
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "两张商品表在 compact 构造器中拷贝后冻结为 unmodifiable；项目同类 record 既有豁免口径")
  public record Receipt(
      ProductionClaim claim,
      ProductionLotDayExecutor.Status status,
      Map<CommodityId, Long> consumedInputs,
      Map<CommodityId, Long> outputs,
      LotState nextState) {

    public Receipt {
      if (claim == null) {
        throw new IllegalArgumentException("LandProductionSettlement.Receipt.claim 不得为 null");
      }
      if (status == null) {
        throw new IllegalArgumentException("LandProductionSettlement.Receipt.status 不得为 null");
      }
      if (nextState == null) {
        throw new IllegalArgumentException("LandProductionSettlement.Receipt.nextState 不得为 null");
      }
      consumedInputs = freezeCommodityMap(consumedInputs, "Receipt.consumedInputs");
      outputs = freezeCommodityMap(outputs, "Receipt.outputs");
    }
  }

  /** 一个被业务拒绝的 claim；理由为稳定短 id。 */
  public record Rejection(ProductionClaim claim, String reason) {

    public Rejection {
      if (claim == null) {
        throw new IllegalArgumentException("LandProductionSettlement.Rejection.claim 不得为 null");
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("LandProductionSettlement.Rejection.reason 不得为空白");
      }
    }
  }

  /**
   * 一次结算的完整结果。
   *
   * @param map 写回后的地图工作副本；无成功 claim 时原样返回入参
   * @param actor 应用库存扣/加后的 actor 工作副本；无成功 claim 时原样返回入参
   * @param receipts 成功 claim 回执（保持 claim 输入顺序）
   * @param rejections 业务拒绝（保持 claim 输入顺序）
   * @param serviceOutputs 非库存服务产出聚合（保序不可变）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "列表与商品表在 compact 构造器中拷贝后冻结为 unmodifiable；项目同类 record 既有豁免口径")
  public record Result(
      GameMap map,
      ActorData actor,
      List<Receipt> receipts,
      List<Rejection> rejections,
      Map<CommodityId, Long> serviceOutputs) {

    public Result {
      if (map == null) {
        throw new IllegalArgumentException("LandProductionSettlement.Result.map 不得为 null");
      }
      if (actor == null) {
        throw new IllegalArgumentException("LandProductionSettlement.Result.actor 不得为 null");
      }
      receipts = freezeList(receipts, "Result.receipts");
      rejections = freezeList(rejections, "Result.rejections");
      serviceOutputs = freezeCommodityMap(serviceOutputs, "Result.serviceOutputs");
    }
  }

  /**
   * 按 claim 输入顺序执行一次土地生产结算。
   *
   * @param map 当前地图（不会被修改）
   * @param actor 当前 actor 状态（不会被修改）
   * @param view 当前 tick 的库存 lot 视图
   * @param opportunities 当前 tick 的生产机会簿
   * @param plans 方案定义；键 = 方案 id
   * @param claims 本轮 claim；同一 lot 最多一条
   */
  public static Result settle(
      GameMap map,
      ActorData actor,
      InventoryLotView view,
      ProductionOpportunityBook opportunities,
      Map<IndustryId, IndustryDefinitionView> plans,
      List<ProductionClaim> claims) {
    requireSettleInput(map, actor, view, opportunities, plans, claims);
    requireUniqueLotIds(claims);

    ActorData workingActor = actor;
    List<Receipt> receipts = new ArrayList<>();
    List<Rejection> rejections = new ArrayList<>();
    List<LandProductionStateWriter.Write> writes = new ArrayList<>();
    Map<CommodityId, Long> serviceOutputs = new LinkedHashMap<>();

    for (ProductionClaim claim : claims) {
      InventoryLot lot = findLot(view, claim.lotId());
      if (lot == null) {
        rejections.add(new Rejection(claim, LOT_MISSING_REASON));
        continue;
      }
      if (!lot.owner().equals(claim.ownerHousehold())) {
        rejections.add(new Rejection(claim, OWNER_MISMATCH_REASON));
        continue;
      }
      IndustryDefinitionView plan = plans.get(claim.planId());
      if (plan == null || !plan.id().equals(claim.planId())) {
        rejections.add(new Rejection(claim, PLAN_MISSING_REASON));
        continue;
      }
      ProductionOpportunity opportunity =
          findOpportunity(opportunities, claim.planId(), claim.lotId());
      if (opportunity == null) {
        rejections.add(new Rejection(claim, OPPORTUNITY_MISSING_REASON));
        continue;
      }

      HouseholdAccountKey accountKey = new HouseholdAccountKey(claim.operatorHousehold());
      HouseholdInventory inventory = workingActor.accounts().get(accountKey);
      if (inventory == null) {
        rejections.add(new Rejection(claim, OPERATOR_INVENTORY_MISSING_REASON));
        continue;
      }

      ProductionLotDayExecutor.DayRequest request =
          new ProductionLotDayExecutor.DayRequest(
              lot.lotId(), plan, lot.state(), lot.quantityMilli(), availableInputs(inventory));
      ProductionLotDayExecutor.Result execution = ProductionLotDayExecutor.executeDay(request);
      if (execution.status() == ProductionLotDayExecutor.Status.REJECTED) {
        rejections.add(new Rejection(claim, execution.reason()));
        continue;
      }

      workingActor =
          workingActor.withInventory(
              mutateInventory(
                  claim,
                  inventory,
                  execution.consumedInputs(),
                  execution.outputs(),
                  serviceOutputs));
      receipts.add(
          new Receipt(
              claim,
              execution.status(),
              execution.consumedInputs(),
              execution.outputs(),
              execution.nextState()));
      writes.add(
          new LandProductionStateWriter.Write(
              execution.lotId().landComponentId(), execution.nextState()));
    }

    GameMap settledMap = LandProductionStateWriter.apply(map, writes);
    return new Result(settledMap, workingActor, receipts, rejections, serviceOutputs);
  }

  private static void requireSettleInput(
      GameMap map,
      ActorData actor,
      InventoryLotView view,
      ProductionOpportunityBook opportunities,
      Map<IndustryId, IndustryDefinitionView> plans,
      List<ProductionClaim> claims) {
    if (map == null) {
      throw new IllegalArgumentException("LandProductionSettlement.settle.map 不得为 null");
    }
    if (actor == null) {
      throw new IllegalArgumentException("LandProductionSettlement.settle.actor 不得为 null");
    }
    if (view == null) {
      throw new IllegalArgumentException("LandProductionSettlement.settle.view 不得为 null");
    }
    if (opportunities == null) {
      throw new IllegalArgumentException("LandProductionSettlement.settle.opportunities 不得为 null");
    }
    if (plans == null) {
      throw new IllegalArgumentException("LandProductionSettlement.settle.plans 不得为 null");
    }
    if (claims == null) {
      throw new IllegalArgumentException(
          "LandProductionSettlement.settle.claims 不得为 null（空批次用空 list）");
    }
  }

  /** 同一 lot 重复 claim 是集合级契约故障：整次 fail-closed，先于任何逐 claim 处理。 */
  private static void requireUniqueLotIds(List<ProductionClaim> claims) {
    Set<InventoryLotId> seenLotIds = new LinkedHashSet<>();
    for (int index = 0; index < claims.size(); index++) {
      ProductionClaim claim = claims.get(index);
      if (claim == null) {
        throw new IllegalArgumentException("production-claim-null: index=" + index);
      }
      if (!seenLotIds.add(claim.lotId())) {
        throw new IllegalArgumentException(
            "production-claim-duplicate-lot: lot=" + claim.lotId().value() + ", index=" + index);
      }
    }
  }

  private static InventoryLot findLot(InventoryLotView view, InventoryLotId lotId) {
    for (InventoryLot lot : view.lots()) {
      if (lot.lotId().equals(lotId)) {
        return lot;
      }
    }
    return null;
  }

  private static ProductionOpportunity findOpportunity(
      ProductionOpportunityBook opportunities, IndustryId planId, InventoryLotId lotId) {
    for (ProductionOpportunity opportunity : opportunities.opportunities()) {
      if (opportunity.planId().equals(planId) && opportunity.carrierLotIds().contains(lotId)) {
        return opportunity;
      }
    }
    return null;
  }

  /** 可用投入 = 余额 − 冻结（唯一算法 {@link AvailableStock}），保持余额表迭代序。 */
  private static Map<CommodityId, Long> availableInputs(HouseholdInventory inventory) {
    Map<CommodityId, Long> available = new LinkedHashMap<>();
    for (CommodityId commodity : inventory.balances().keySet()) {
      available.put(commodity, AvailableStock.available(inventory, commodity));
    }
    return available;
  }

  /**
   * 在 operator 库存工作副本上先扣投入、再加非 service 产出。
   *
   * <p>service 键只聚合到 {@code serviceOutputs}，<b>不进入</b> balances。
   */
  private static HouseholdInventory mutateInventory(
      ProductionClaim claim,
      HouseholdInventory inventory,
      Map<CommodityId, Long> consumedInputs,
      Map<CommodityId, Long> outputs,
      Map<CommodityId, Long> serviceOutputs) {
    Map<CommodityId, Long> balances = new LinkedHashMap<>(inventory.balances());
    for (Map.Entry<CommodityId, Long> entry : consumedInputs.entrySet()) {
      CommodityId commodity = entry.getKey();
      long required = entry.getValue();
      long current = balances.getOrDefault(commodity, 0L);
      long next;
      try {
        next = Math.subtractExact(current, required);
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException(
            "production-claim-input-mutation-shortfall: claim="
                + claim.claimId()
                + ", commodity="
                + commodity.value()
                + ", balance="
                + current
                + ", required="
                + required
                + ", reason=overflow",
            overflow);
      }
      if (next < 0L) {
        throw new IllegalStateException(
            "production-claim-input-mutation-shortfall: claim="
                + claim.claimId()
                + ", commodity="
                + commodity.value()
                + ", balance="
                + current
                + ", required="
                + required);
      }
      long frozen = inventory.frozenBalances().getOrDefault(commodity, 0L);
      if (next < frozen) {
        throw new IllegalStateException(
            "production-claim-input-mutation-shortfall: claim="
                + claim.claimId()
                + ", commodity="
                + commodity.value()
                + ", balance="
                + current
                + ", required="
                + required
                + ", frozen="
                + frozen
                + ", reason=frozen-shortfall");
      }
      balances.put(commodity, next);
    }
    for (Map.Entry<CommodityId, Long> entry : outputs.entrySet()) {
      CommodityId commodity = entry.getKey();
      long amount = entry.getValue();
      if (CaravanTransportTemplate.SERVICE_OUTPUT.equals(commodity)) {
        accumulateServiceOutput(serviceOutputs, commodity, amount, claim);
        continue;
      }
      long current = balances.getOrDefault(commodity, 0L);
      long next;
      try {
        next = Math.addExact(current, amount);
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException(
            "production-claim-output-mutation-overflow: claim="
                + claim.claimId()
                + ", commodity="
                + commodity.value()
                + ", balance="
                + current
                + ", output="
                + amount,
            overflow);
      }
      balances.put(commodity, next);
    }
    try {
      return new HouseholdInventory(
          inventory.key(),
          balances,
          inventory.money(),
          inventory.frozenBalances(),
          inventory.frozenMoney());
    } catch (IllegalArgumentException inconsistent) {
      throw new IllegalStateException(
          "production-claim-inventory-mutation-invalid: claim="
              + claim.claimId()
              + ", household="
              + claim.operatorHousehold().value()
              + ", detail="
              + inconsistent.getMessage(),
          inconsistent);
    }
  }

  private static void accumulateServiceOutput(
      Map<CommodityId, Long> serviceOutputs,
      CommodityId commodity,
      long amount,
      ProductionClaim claim) {
    long current = serviceOutputs.getOrDefault(commodity, 0L);
    long next;
    try {
      next = Math.addExact(current, amount);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "production-claim-service-output-overflow: claim="
              + claim.claimId()
              + ", commodity="
              + commodity.value()
              + ", current="
              + current
              + ", amount="
              + amount,
          overflow);
    }
    serviceOutputs.put(commodity, next);
  }

  /** 逐商品表的冻结：非 null、键值非 null、逐值 ≥ 0；保序不可变（不用 {@code Map.copyOf}）。 */
  private static Map<CommodityId, Long> freezeCommodityMap(
      Map<CommodityId, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(
          "LandProductionSettlement." + field + " 不得为 null（无该项用空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("LandProductionSettlement." + field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "LandProductionSettlement." + field + " 的值不得为负: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 列表的冻结：非 null、不得含 null；保序不可变（不用 {@code List.copyOf}）。 */
  private static <T> List<T> freezeList(List<T> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(
          "LandProductionSettlement." + field + " 不得为 null（空列表用空 list）");
    }
    List<T> copy = new ArrayList<>();
    for (T item : source) {
      if (item == null) {
        throw new IllegalArgumentException("LandProductionSettlement." + field + " 不得含 null");
      }
      copy.add(item);
    }
    return Collections.unmodifiableList(copy);
  }
}
