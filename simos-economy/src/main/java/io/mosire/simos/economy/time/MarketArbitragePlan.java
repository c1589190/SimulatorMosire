package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>§4.3 的活动排序结果 → 市场订单的唯一传递通道</b>（逐日瞬态；不进 {@code EconomyData}、不进变更集、不落盘）。
 *
 * <p>★★ <b>它为什么存在</b>：排序在<b>劳动阶段</b>（{@code LaborQueueSettlement}，与生产争同一份时间预算）完成，
 * 而订单在<b>市场阶段</b>（{@code MarketSettlement.ordersFor}）生成 —— 两者之间必须有一个只读的、具名的传导物，
 * 否则"套利分到了多少劳动/多少库存"这件事会在日序里丢失。本类就是那个传导物：<b>唯一的产出方</b>是 {@code
 * LaborQueueSettlement}（排序的落点），<b>唯一的消费方</b>是 {@code MarketSettlement} 的订单生成。
 *
 * <p>★★ <b>它不改任何账户</b>：订单仍由 {@code ordersFor} 生成、仍由 {@code clearOncePerCycle} 撮合、仍由唯一写口 {@code
 * EconomySettlement.applyTransfer} 落账 ⇒ 铁律 2 不被绕过（本类只是"想要多少"的声明）。
 *
 * <p>★ <b>每户每轮至多一条</b>（§4.1 第 ④ 步"每户每轮最多执行一次，吃一次深度"）：{@link #of} 对同一家户只保留第一条，
 * 后面的<b>不静默覆盖</b>（调用方保证一户只发一次；重复 ⇒ 抛，避免"谁覆盖谁"这种没有答案的问题）。
 *
 * <p>★ <b>确定性与保序</b>：键按传入序保序冻结（不用 {@code Map.copyOf}：迭代序不是内容的纯函数）；查找是 O(1) 的 {@code (household,
 * commodity)} 复合键。
 */
final class MarketArbitragePlan {

  /**
   * 一次套利决定（排序的产出；只读证据 + 要下的量）。
   *
   * <p>★★ <b>量纲（2026-10-08 诊断缺陷修复后）</b>：{@code quantityMilli}/{@code laborMilli} 是**毫** （毫商品 /
   * 毫小时）；{@code reservationMicro}/{@code marketMicro}/{@code edgeMicro} 一律是**微 numeraire / 商品单位**（1
   * 毫 = 1000 微）—— 三者同刻度、且字段名自带刻度，日志据此取名，不再出现"两个装微、一个装毫、 名字都写 Milli"（旧 {@code
   * reservationPrice}/{@code marketPrice}/{@code edgePerUnit}）。 ★ 三个价格字段**只进日志与读数**：订单侧只取 {@code
   * quantityMilli}，本记录不参与任何决策。
   */
  record Instruction(
      HouseholdId household,
      HouseholdActivity.Direction direction,
      CommodityId commodity,
      long quantityMilli,
      long reservationMicro,
      long marketMicro,
      long edgeMicro,
      long laborMilli) {

    Instruction {
      Objects.requireNonNull(household, "MarketArbitragePlan.Instruction.household 不得为 null");
      Objects.requireNonNull(direction, "MarketArbitragePlan.Instruction.direction 不得为 null");
      Objects.requireNonNull(commodity, "MarketArbitragePlan.Instruction.commodity 不得为 null");
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException(
            "MarketArbitragePlan.Instruction.quantityMilli 必须 > 0: " + quantityMilli);
      }
      if (laborMilli < 0L) {
        throw new IllegalArgumentException(
            "MarketArbitragePlan.Instruction.laborMilli 不得为负: " + laborMilli);
      }
    }
  }

  private static final MarketArbitragePlan EMPTY = new MarketArbitragePlan(List.of(), Map.of());

  private final List<Instruction> instructions;
  private final Map<String, Instruction> byHouseholdCommodity;

  private MarketArbitragePlan(
      List<Instruction> instructions, Map<String, Instruction> byHouseholdCommodity) {
    this.instructions = instructions;
    this.byHouseholdCommodity = byHouseholdCommodity;
  }

  /** 空计划（旧路径/只读计划轮/读口：**逐值退回改前行为**）。 */
  static MarketArbitragePlan empty() {
    return EMPTY;
  }

  /**
   * ★★ <b>由逐户决定构建一份计划</b>（唯一入口；保序、去重 fail-closed）。
   *
   * @param instructions 决定清单（保序；同一 {@code (家户, 商品)} 出现两次 ⇒ 抛 —— "谁覆盖谁"没有答案，不许静默取后者）
   */
  static MarketArbitragePlan of(List<Instruction> instructions) {
    Objects.requireNonNull(instructions, "MarketArbitragePlan.of 的决定清单不得为 null");
    LinkedHashMap<String, Instruction> byKey = new LinkedHashMap<>();
    java.util.ArrayList<Instruction> copy = new java.util.ArrayList<>(instructions.size());
    for (Instruction instruction : instructions) {
      Instruction checked = Objects.requireNonNull(instruction, "决定清单不得含 null");
      String key = keyOf(checked.household(), checked.commodity());
      if (byKey.putIfAbsent(key, checked) != null) {
        throw new IllegalArgumentException(
            "同一家户在同一轮对同一商品至多一条套利决定（§4.1 第 ④ 步）: "
                + checked.household().value()
                + "/"
                + checked.commodity().value());
      }
      copy.add(checked);
    }
    return new MarketArbitragePlan(
        Collections.unmodifiableList(copy), Collections.unmodifiableMap(byKey));
  }

  /** 全部决定（保序只读）。 */
  List<Instruction> instructions() {
    return instructions;
  }

  /** 本户在某商品上的套利决定；没有 ⇒ 空（订单生成据此逐值退回改前口径）。 */
  Optional<Instruction> instructionFor(HouseholdId household, CommodityId commodity) {
    if (household == null || commodity == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byHouseholdCommodity.get(keyOf(household, commodity)));
  }

  int size() {
    return instructions.size();
  }

  boolean isEmpty() {
    return instructions.isEmpty();
  }

  /** 复合键的唯一拼写点（{@code \u0000} 不会被任何稳定 id 使用）。 */
  private static String keyOf(HouseholdId household, CommodityId commodity) {
    return household.value() + '\u0000' + commodity.value();
  }

  /**
   * ★★ <b>逐日收集器</b>：排序阶段（{@code LaborQueueSettlement}）往它里放决定，市场阶段用 {@link #toPlan()} 取走。
   *
   * <p>★ 它是<b>逐日瞬态</b>（每次结算 new 一个，不进状态、不跨 tick 复用）；它不排序、不去重 —— 排序由 {@link ActivitySelector}
   * 做，去重/冲突由 {@link #of} 判死。
   */
  static final class Collector {

    private final List<Instruction> instructions = new ArrayList<>();

    /** 记一条决定（保序追加；同一 (家户, 商品) 重复 ⇒ 由 {@link #toPlan()} 具名抛）。 */
    void add(Instruction instruction) {
      instructions.add(Objects.requireNonNull(instruction, "套利决定不得为 null"));
    }

    /** 冻结成计划（空 ⇒ {@link #empty()}，调用方因此不必自己判空）。 */
    MarketArbitragePlan toPlan() {
      return instructions.isEmpty() ? empty() : of(instructions);
    }
  }
}
