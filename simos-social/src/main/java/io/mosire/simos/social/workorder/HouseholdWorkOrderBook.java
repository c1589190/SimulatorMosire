package io.mosire.simos.social.workorder;

import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Objects;

/**
 * ★★ <b>Social 工单受理</b>（2026-10-09 用户裁定：Unit/Eco 等要改家户人口属性时，向 Social 提交 "更改理由 + 更改方案 + 更改对象"，由
 * Social 校验与落账）：把 {@link HouseholdWorkOrder} 顺序应用到 base 的 <b>工作副本</b>上，成功才返回新的 {@link
 * SocialData}（调用方包成一条 {@code SocialChangeSet}）。
 *
 * <p>★★ <b>整单语义</b>：
 *
 * <ul>
 *   <li><b>顺序</b>：逐条调 {@link HouseholdBook} 的对应写口，后一条看到前一条已生效的工作副本；
 *   <li><b>不部分生效</b>：任一条抛 {@link IllegalArgumentException} ⇒ 把"第几步 + 操作名 + 原拒因"重新具名抛出，
 *       调用方（handler）折成 {@code HandlerOutcome.Rejected} ⇒ <b>不留 revision</b>；base 从不被改（social
 *       状态不可变）；
 *   <li><b>目标校验</b>：plan 必须点名 {@code target}（由 {@link HouseholdWorkOrder} 构造期判），且计划执行后 target 必须存在；
 *       否则整单拒；
 *   <li><b>幂等</b>：{@code orderId} 非空且已有一条 {@code work-order:<orderId>} 标记事件 ⇒ 具名拒（不重复改人口）。 首次成功时在
 *       {@code populationEvents} 追加一条 {@link PopulationEventType#WORK_ORDER} 标记（只进事件表、不改人口）， 让幂等键跨
 *       revision/重启生效；未给 orderId ⇒ 不做幂等，也不落标记；
 *   <li><b>日志</b>：INFO 汇总（{@code HOUSEHOLD_WORK_ORDER} / {@code HOUSEHOLD_WORK_ORDER_APPLIED} /
 *       {@code HOUSEHOLD_WORK_ORDER_REJECTED} / {@code HOUSEHOLD_WORK_ORDER_DUPLICATE}，都带 reason 与
 *       source）； TRACE 逐操作（{@code HOUSEHOLD_WORK_ORDER_STEP}，同样带 reason/source）——逐条人口事件仍由 {@code
 *       HouseholdBook} 自己记 INFO/TRACE，不在这里复制。
 * </ul>
 *
 * <p>★ <b>纯函数</b>：不写任何外部状态、不读 store；{@code worldTick} 是世界当前 tick（调用方传入），只用于 ADD_MEMBERS 缺省 {@code
 * anchorTick} 与标记事件的 {@code day}，保证同一输入同一输出。
 *
 * <p>★ <b>本类只管家户人口属性</b>：UNIT 位置对应的 unit 侧容纳列表一致性由 app 组合工具同批保证（social 域不认识 unit，铁律 3）。
 */
public final class HouseholdWorkOrderBook {

  private HouseholdWorkOrderBook() {}

  /**
   * 受理一张工单：从 {@code base} 起顺序应用计划，成功返回<b>新的</b> {@link SocialData}（含幂等标记）。
   *
   * @param base 受理基准（命令拿到的那条 revision 的 social 切片）
   * @param order 待受理工单（已做形状校验）
   * @param worldTick 世界当前 tick（ADD_MEMBERS 缺省锚点 / 标记事件 day）
   * @throws IllegalArgumentException 任一操作失败、目标计划后不存在、orderId 重复（整单拒，调用方折 Rejected）
   */
  public static SocialData apply(SocialData base, HouseholdWorkOrder order, long worldTick) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(order, "order");
    if (worldTick < 0L) {
      throw new IllegalArgumentException("工单 worldTick 不得为负: " + worldTick);
    }
    String orderKey = order.orderId() == null ? "(none)" : order.orderId();
    String markerId = order.markerEventId();
    if (markerId != null && base.populationEvents().containsKey(markerId)) {
      EventLog.channel(SocialLog.workOrder())
          .info(
              LogEvent.of(
                  "HOUSEHOLD_WORK_ORDER_DUPLICATE",
                  SocialLogSource.SOCIAL_WORK_ORDER,
                  "order",
                  orderKey,
                  "target",
                  order.target(),
                  "reason",
                  order.reason(),
                  "source",
                  order.source()));
      throw new IllegalArgumentException(
          "工单 orderId 已受理过，拒绝重复提交（幂等键命中，不重复改人口）: " + order.orderId());
    }

    long populationBefore = base.householdPopulation(order.target());
    EventLog.channel(SocialLog.workOrder())
        .info(
            LogEvent.of(
                "HOUSEHOLD_WORK_ORDER",
                SocialLogSource.SOCIAL_WORK_ORDER,
                "order",
                orderKey,
                "target",
                order.target(),
                "steps",
                order.plan().steps().size(),
                "populationBefore",
                populationBefore,
                "worldTick",
                worldTick,
                "reason",
                order.reason(),
                "source",
                order.source()));

    SocialData current = base;
    int index = 0;
    for (HouseholdWorkOrderPlan.Step step : order.plan().steps()) {
      index++;
      if (SocialLog.trace().isTraceEnabled()) {
        EventLog.channel(SocialLog.trace())
            .trace(
                LogEvent.of(
                    "HOUSEHOLD_WORK_ORDER_STEP",
                    SocialLogSource.SOCIAL_WORK_ORDER,
                    "order",
                    orderKey,
                    "target",
                    order.target(),
                    "step",
                    index,
                    "op",
                    step.op(),
                    "detail",
                    describe(step),
                    "reason",
                    order.reason(),
                    "source",
                    order.source()));
      }
      try {
        current = applyStep(current, step, order.reason());
      } catch (IllegalArgumentException failure) {
        String message = "工单第 " + index + " 步 [" + step.op() + "] 失败: " + failure.getMessage();
        EventLog.channel(SocialLog.workOrder())
            .info(
                LogEvent.of(
                    "HOUSEHOLD_WORK_ORDER_REJECTED",
                    SocialLogSource.SOCIAL_WORK_ORDER,
                    "order",
                    orderKey,
                    "target",
                    order.target(),
                    "step",
                    index,
                    "op",
                    step.op(),
                    "reason",
                    order.reason(),
                    "source",
                    order.source(),
                    "error",
                    failure.getMessage()));
        throw new IllegalArgumentException(message, failure);
      }
    }

    if (!current.households().containsKey(order.target())) {
      String message = "工单执行后目标家户不存在（plan 未创建该家户，或把它从最终状态里移走了）: " + order.target();
      EventLog.channel(SocialLog.workOrder())
          .info(
              LogEvent.of(
                  "HOUSEHOLD_WORK_ORDER_REJECTED",
                  SocialLogSource.SOCIAL_WORK_ORDER,
                  "order",
                  orderKey,
                  "target",
                  order.target(),
                  "reason",
                  order.reason(),
                  "source",
                  order.source(),
                  "error",
                  message));
      throw new IllegalArgumentException(message);
    }

    if (markerId != null) {
      // ★ 幂等/审计标记：WORK_ORDER 事件只进事件表（HouseholdBook.applyEvents 对该类型不碰人口状态），
      //   count/sex/ageBracket 不承载语义；orderId 在事件 id 里，reason/source 原样入账 ⇒ 可查"谁为什么提了什么"。
      HouseholdPopulationEvent marker =
          new HouseholdPopulationEvent(
              markerId,
              order.target(),
              PopulationEventType.WORK_ORDER,
              Sex.MALE,
              AgeBracket.CHILD.key(),
              0L,
              worldTick,
              order.reason(),
              order.source(),
              null);
      current = HouseholdBook.applyEvent(current, marker);
    }

    EventLog.channel(SocialLog.workOrder())
        .info(
            LogEvent.of(
                "HOUSEHOLD_WORK_ORDER_APPLIED",
                SocialLogSource.SOCIAL_WORK_ORDER,
                "order",
                orderKey,
                "target",
                order.target(),
                "steps",
                order.plan().steps().size(),
                "populationBefore",
                populationBefore,
                "populationAfter",
                current.householdPopulation(order.target()),
                "marker",
                markerId == null ? "(none)" : markerId,
                "reason",
                order.reason(),
                "source",
                order.source()));
    return current;
  }

  /** 逐条操作 → {@link HouseholdBook} 的对应纯函数写口；{@code reason} 贯穿为每一步的人口事件原因。 */
  private static SocialData applyStep(
      SocialData current, HouseholdWorkOrderPlan.Step step, String reason) {
    return switch (step) {
      case HouseholdWorkOrderPlan.CreateHousehold s ->
          HouseholdBook.create(current, s.householdId(), s.location(), s.profile(), s.vitalRates());
      case HouseholdWorkOrderPlan.SetLocation s ->
          HouseholdBook.setLocation(current, s.householdId(), s.location(), reason);
      case HouseholdWorkOrderPlan.AddMembers s ->
          HouseholdBook.addMembers(
              current,
              s.householdId(),
              s.lotId(),
              s.sex(),
              s.count(),
              s.ageAtAnchorDays(),
              s.anchorTick(),
              reason);
      case HouseholdWorkOrderPlan.RemoveMembers s ->
          HouseholdBook.removeMembers(current, s.householdId(), s.lotId(), s.count(), reason);
      case HouseholdWorkOrderPlan.TransferMembers s ->
          HouseholdBook.transferMembers(current, s.from(), s.to(), s.lotId(), s.count(), reason);
      case HouseholdWorkOrderPlan.AdjustPopulation s ->
          HouseholdBook.adjustPopulation(
              current, s.householdId(), s.sex(), s.ageBracketId(), s.delta(), reason);
      case HouseholdWorkOrderPlan.SetVitalRates s ->
          HouseholdBook.setVitalRates(current, s.householdId(), s.vitalRates(), reason);
    };
  }

  /** TRACE 行的操作明细（只记 id/数量/位置，不记 profile metadata 等自由文本）。 */
  private static String describe(HouseholdWorkOrderPlan.Step step) {
    return switch (step) {
      case HouseholdWorkOrderPlan.CreateHousehold s ->
          "household="
              + s.householdId()
              + " location="
              + s.location()
              + " name="
              + s.profile().name();
      case HouseholdWorkOrderPlan.SetLocation s ->
          "household=" + s.householdId() + " location=" + s.location();
      case HouseholdWorkOrderPlan.AddMembers s ->
          "household="
              + s.householdId()
              + " lot="
              + s.lotId()
              + " sex="
              + s.sex()
              + " count="
              + s.count()
              + " ageAtAnchorDays="
              + s.ageAtAnchorDays()
              + " anchorTick="
              + s.anchorTick();
      case HouseholdWorkOrderPlan.RemoveMembers s ->
          "household=" + s.householdId() + " lot=" + s.lotId() + " count=" + s.count();
      case HouseholdWorkOrderPlan.TransferMembers s ->
          "from=" + s.from() + " to=" + s.to() + " lot=" + s.lotId() + " count=" + s.count();
      case HouseholdWorkOrderPlan.AdjustPopulation s ->
          "household="
              + s.householdId()
              + " sex="
              + s.sex()
              + " ageBracket="
              + s.ageBracketId()
              + " delta="
              + s.delta();
      case HouseholdWorkOrderPlan.SetVitalRates s ->
          "household=" + s.householdId() + " rates=" + s.vitalRates().rates().size();
    };
  }
}
