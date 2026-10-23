package io.mosire.simos.production;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionStageDefinition;
import io.mosire.simos.production.contract.ProductionStageEndAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>D-032 L2b1：LAND carrier lot 的纯日执行内核</b>。
 *
 * <p>本类只回答一个问题：<b>给定 lot 当前生产状态与本日可用投入，这一天应如何推进</b>。它是纯函数：
 *
 * <ul>
 *   <li>不读写库存、不写 Map、不落 ChangeSet/Codec；
 *   <li>不读时钟、随机数、文件或环境；
 *   <li>不构造任何运行实例、不依赖 S3/S4；
 *   <li>不修改 {@link DayRequest#availableInputs()} 或任何入参。
 * </ul>
 *
 * <p>★ <b>目标运行位置</b>：S4「按 carrier lot 扣料/推进」的纯计算核。真实库存扣减、缩规模/拆 lot 与土地 state 写回留在 L2b2；本类只返回 {@link
 * Result}。legacy（{@code plan.stages()} 为空）在 L2b1 明确不支持，具名拒绝 {@code
 * land-plan-legacy-unsupported}，绝不伪造单周期行为。
 *
 * <p>★ <b>数量口径</b>：{@code lotQuantityMilli} 与 {@code availableInputs} 都是毫商品/毫亩；投入按 {@code perUnit ×
 * scale} 计算；HARVEST 产出按 {@code outputPerUnit × scale × 1000} 换算成毫商品。所有乘加走 {@code
 * Math.multiplyExact}/{@code Math.addExact}，溢出统一抛 {@link ProductionContractFault} {@code
 * production-lot-day-arithmetic-overflow}（fail-closed，不静默截断）。
 *
 * <p>★ <b>失败语义</b>：业务拒绝以 {@link Status#REJECTED} + 稳定 {@code reason} 返回，{@code nextState} 原样等于
 * {@code currentState}、{@code consumedInputs}/{@code outputs} 为空；契约/算术故障以 {@link
 * ProductionContractFault} 抛出。
 *
 * <p>★ <b>保序不可变</b>：所有对外 map 一律「拷贝进 {@link LinkedHashMap} 再 {@link
 * Collections#unmodifiableMap}」，<b>绝不使用</b> {@code Map.copyOf}。
 */
public final class ProductionLotDayExecutor {

  private static final long MILLI_PER_COMMODITY_UNIT = 1000L;

  private ProductionLotDayExecutor() {}

  /** 一天推进的业务结果分类。 */
  public enum Status {
    /** 阶段尚未结束，lot 继续留在当前阶段。 */
    CONTINUE,
    /** 本阶段结束并进入下一阶段。 */
    STAGE_ADVANCED,
    /** 最终阶段结束并收获；lot 回到 IDLE。 */
    HARVESTED,
    /** 本日不推进；{@link Result#reason()} 给出稳定理由。 */
    REJECTED
  }

  /**
   * 一个 LAND lot 的日执行请求。
   *
   * @param lotId carrier lot 身份
   * @param plan 该 lot 正在执行的多阶段生产方案
   * @param currentState lot 当前状态（IDLE / InProduction；Complete 及未知态会被业务拒绝）
   * @param lotQuantityMilli lot 的物理数量（毫单位）；必须 &gt; 0
   * @param availableInputs 本日可用的逐商品投入（毫单位）；键值非 null、值 ≥ 0
   */
  public record DayRequest(
      InventoryLotId lotId,
      IndustryDefinitionView plan,
      LotState currentState,
      long lotQuantityMilli,
      Map<CommodityId, Long> availableInputs) {

    public DayRequest {
      Objects.requireNonNull(lotId, "ProductionLotDayExecutor.DayRequest.lotId 不得为 null");
      Objects.requireNonNull(plan, "ProductionLotDayExecutor.DayRequest.plan 不得为 null");
      Objects.requireNonNull(
          currentState, "ProductionLotDayExecutor.DayRequest.currentState 不得为 null");
      if (lotQuantityMilli <= 0L) {
        throw new IllegalArgumentException(
            "ProductionLotDayExecutor.DayRequest.lotQuantityMilli 必须 > 0: " + lotQuantityMilli);
      }
      if (availableInputs == null) {
        throw new IllegalArgumentException(
            "ProductionLotDayExecutor.DayRequest.availableInputs 不得为 null（无投入用空 map）");
      }
      Map<CommodityId, Long> availableCopy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : availableInputs.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionLotDayExecutor.DayRequest.availableInputs 的键与值都不得为 null");
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "ProductionLotDayExecutor.DayRequest.availableInputs 的值不得为负: "
                  + entry.getKey()
                  + " = "
                  + entry.getValue());
        }
        availableCopy.put(entry.getKey(), entry.getValue());
      }
      availableInputs = Collections.unmodifiableMap(availableCopy); // ★ 保序不可变；不用 Map.copyOf
    }
  }

  /**
   * 一天推进的结果。
   *
   * @param lotId 对应的 carrier lot（回显请求）
   * @param status 推进分类
   * @param reason 稳定理由；成功为空串，{@link Status#REJECTED} 时非空白
   * @param nextState 推进后的 lot 状态；拒绝时原样等于请求的 currentState
   * @param consumedInputs 本 tick 的投入 delta（保序不可变）；拒绝时为空
   * @param outputs 本 tick 的产出（仅 HARVEST；保序不可变）；其它情况为空
   * @param productionScale 由 {@code lotQuantityMilli / LAND capacityPerUnit} 向下取整得到的规模
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "consumedInputs/outputs 在 compact 构造器中经 freezeCommodityMap 拷贝并冻结为 unmodifiable；项目同类"
              + " record 既有豁免口径")
  public record Result(
      InventoryLotId lotId,
      Status status,
      String reason,
      LotState nextState,
      Map<CommodityId, Long> consumedInputs,
      Map<CommodityId, Long> outputs,
      long productionScale) {

    public Result {
      Objects.requireNonNull(lotId, "ProductionLotDayExecutor.Result.lotId 不得为 null");
      Objects.requireNonNull(status, "ProductionLotDayExecutor.Result.status 不得为 null");
      Objects.requireNonNull(reason, "ProductionLotDayExecutor.Result.reason 不得为 null");
      Objects.requireNonNull(nextState, "ProductionLotDayExecutor.Result.nextState 不得为 null");
      if (consumedInputs == null) {
        throw new IllegalArgumentException(
            "ProductionLotDayExecutor.Result.consumedInputs 不得为 null（无投入用空 map）");
      }
      if (outputs == null) {
        throw new IllegalArgumentException(
            "ProductionLotDayExecutor.Result.outputs 不得为 null（无产出用空 map）");
      }
      consumedInputs = freezeCommodityMap(consumedInputs, "consumedInputs");
      outputs = freezeCommodityMap(outputs, "outputs");
    }
  }

  /**
   * 执行一个 LAND lot 的生产日。
   *
   * <p>规则顺序：先拒绝 legacy 方案，再定规模，再解析 currentState，最后才计算本 tick 投入与推进；任何拒绝都在 任何可观察状态改变之前返回。
   */
  public static Result executeDay(DayRequest request) {
    Objects.requireNonNull(request, "ProductionLotDayExecutor.executeDay 的 request 不得为 null");
    try {
      return executeDayChecked(request);
    } catch (ArithmeticException overflow) {
      throw new ProductionContractFault(
          "production-lot-day-arithmetic-overflow",
          "lot=" + request.lotId().value() + " 的日执行数量运算溢出: " + overflow.getMessage());
    }
  }

  private static Result executeDayChecked(DayRequest request) {
    IndustryDefinitionView plan = request.plan();

    // 1) L2b1 只做 staged；legacy 不伪造。
    if (plan.stages().isEmpty()) {
      return rejected(request, "land-plan-legacy-unsupported", 0L);
    }

    // 2) LAND 规模锚：缺失/非正 ⇒ 无法生产。
    Long landCapacityPerUnit = plan.capacityPerUnit().get(AssetKind.LAND);
    if (landCapacityPerUnit == null || landCapacityPerUnit <= 0L) {
      return rejected(request, "land-production-scale-zero", 0L);
    }
    long productionScale = request.lotQuantityMilli() / landCapacityPerUnit;
    if (productionScale <= 0L) {
      return rejected(request, "land-production-scale-zero", 0L);
    }

    // 3) 解析 currentState 到本日执行上下文；不修改 state。
    String planId = plan.id().value();
    int stageIndex;
    long stageProgressDays;
    long cycleProgressDays;
    boolean startInputPaid;
    Map<CommodityId, Long> cycleInputUsed;
    Map<String, Long> facts;
    if (request.currentState() instanceof LotState.Idle) {
      stageIndex = 0;
      stageProgressDays = 0L;
      cycleProgressDays = 0L;
      startInputPaid = false;
      cycleInputUsed = emptyCommodityMap();
      facts = emptyStringMap();
    } else if (request.currentState() instanceof LotState.InProduction inProduction) {
      // ★ fail-closed：lot state 自带的 planId 是 carrier lot 的一部分；错配方案不得静默按请求 plan 推进。
      if (!inProduction.planId().equals(planId)) {
        return rejected(request, "production-lot-plan-mismatch", productionScale);
      }
      stageIndex = indexOfStage(plan, inProduction.stageId());
      if (stageIndex < 0) {
        return rejected(request, "production-lot-stage-unknown", productionScale);
      }
      ProductionStageDefinition stage = plan.stages().get(stageIndex);
      if (inProduction.stageProgressDays() > stage.durationDays()) {
        return rejected(request, "production-lot-stage-progress-out-of-range", productionScale);
      }
      stageProgressDays = inProduction.stageProgressDays();
      cycleProgressDays = inProduction.cycleProgressDays();
      startInputPaid = inProduction.startInputPaid();
      cycleInputUsed = inProduction.cycleInputUsedMilli();
      facts = inProduction.facts();
    } else {
      return rejected(request, "production-lot-state-invalid", productionScale);
    }

    ProductionStageDefinition stage = plan.stages().get(stageIndex);

    // 4) 本 tick 必需投入 = 当前 stage 的 tickInput；仅第一天且未付 start 时再叠加 startInput。
    //    ★ 不得把 future stages 的投入算进本 tick。
    boolean startDue = stageProgressDays == 0L && !startInputPaid;
    Map<CommodityId, Long> consumedInputs = new LinkedHashMap<>();
    if (startDue) {
      addScaled(consumedInputs, stage.startInputPerUnit(), productionScale);
    }
    addScaled(consumedInputs, stage.tickInputPerUnit(), productionScale);

    // 5) 可用量不足：业务拒绝，nextState 原样，consumed/outputs 为空。
    for (Map.Entry<CommodityId, Long> entry : consumedInputs.entrySet()) {
      long available = request.availableInputs().getOrDefault(entry.getKey(), 0L);
      if (available < entry.getValue()) {
        return rejected(request, "stage-input-shortfall", productionScale);
      }
    }

    Map<CommodityId, Long> frozenConsumedInputs =
        Collections.unmodifiableMap(new LinkedHashMap<>(consumedInputs));

    // 6) 累加本周期投入与进度。
    Map<CommodityId, Long> nextCycleInput = new LinkedHashMap<>(cycleInputUsed);
    for (Map.Entry<CommodityId, Long> entry : frozenConsumedInputs.entrySet()) {
      nextCycleInput.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
    Map<CommodityId, Long> frozenNextCycleInput = Collections.unmodifiableMap(nextCycleInput);
    long nextCycleProgress = Math.addExact(cycleProgressDays, 1L);
    long nextStageProgress = Math.addExact(stageProgressDays, 1L);

    // 7) 阶段未结束 ⇒ 留在同一 stage。
    if (nextStageProgress < stage.durationDays()) {
      LotState nextState =
          new LotState.InProduction(
              planId,
              stage.stageId(),
              nextStageProgress,
              nextCycleProgress,
              facts,
              true,
              frozenNextCycleInput);
      return new Result(
          request.lotId(),
          Status.CONTINUE,
          "",
          nextState,
          frozenConsumedInputs,
          emptyCommodityMap(),
          productionScale);
    }

    // 8) 阶段结束：NEXT_STAGE 需要下一段；缺失时不得半推进。
    if (stage.endAction() == ProductionStageEndAction.NEXT_STAGE) {
      int nextStageIndex = stageIndex + 1;
      if (nextStageIndex >= plan.stages().size()) {
        return rejected(request, "production-lot-next-stage-missing", productionScale);
      }
      ProductionStageDefinition nextStage = plan.stages().get(nextStageIndex);
      LotState nextState =
          new LotState.InProduction(
              planId,
              nextStage.stageId(),
              0L,
              nextCycleProgress,
              facts,
              false,
              frozenNextCycleInput);
      return new Result(
          request.lotId(),
          Status.STAGE_ADVANCED,
          "",
          nextState,
          frozenConsumedInputs,
          emptyCommodityMap(),
          productionScale);
    }

    // 9) 阶段结束：HARVEST ⇒ 产出本阶段 output × scale × 1000，lot 回到 IDLE。
    Map<CommodityId, Long> outputs = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : stage.outputPerUnit().entrySet()) {
      long amount =
          Math.multiplyExact(
              Math.multiplyExact(entry.getValue(), productionScale), MILLI_PER_COMMODITY_UNIT);
      if (amount > 0L) {
        outputs.put(entry.getKey(), amount);
      }
    }
    Map<CommodityId, Long> frozenOutputs = Collections.unmodifiableMap(outputs);
    return new Result(
        request.lotId(),
        Status.HARVESTED,
        "",
        LotState.idle(),
        frozenConsumedInputs,
        frozenOutputs,
        productionScale);
  }

  /** 具名业务拒绝：nextState 原样、consumed/outputs 为空。 */
  private static Result rejected(DayRequest request, String reason, long productionScale) {
    return new Result(
        request.lotId(),
        Status.REJECTED,
        reason,
        request.currentState(),
        emptyCommodityMap(),
        emptyCommodityMap(),
        productionScale);
  }

  /** 当前 stage 在方案中的下标；找不到返回 -1（调用方具名拒绝）。 */
  private static int indexOfStage(IndustryDefinitionView plan, String stageId) {
    for (int index = 0; index < plan.stages().size(); index++) {
      if (plan.stages().get(index).stageId().equals(stageId)) {
        return index;
      }
    }
    return -1;
  }

  /** 把逐单位投入按规模放大并合并；0 不进表，同商品相加，溢出由 {@code Math} exact 方法抛出。 */
  private static void addScaled(
      Map<CommodityId, Long> target, Map<CommodityId, Long> perUnit, long scale) {
    for (Map.Entry<CommodityId, Long> entry : perUnit.entrySet()) {
      long amount = Math.multiplyExact(entry.getValue(), scale);
      if (amount != 0L) {
        target.merge(entry.getKey(), amount, Math::addExact);
      }
    }
  }

  private static Map<CommodityId, Long> freezeCommodityMap(
      Map<CommodityId, Long> source, String field) {
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionLotDayExecutor.Result." + field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionLotDayExecutor.Result." + field + " 的值不得为负: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 保序不可变；不用 Map.copyOf
  }

  private static Map<CommodityId, Long> emptyCommodityMap() {
    return Collections.unmodifiableMap(new LinkedHashMap<>());
  }

  private static Map<String, Long> emptyStringMap() {
    return Collections.unmodifiableMap(new LinkedHashMap<>());
  }
}
