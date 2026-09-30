package io.mosire.simos.economy.classfirst;

import io.mosire.simos.economy.api.id.ClassFlowEventId;
import io.mosire.simos.economy.api.id.ClassPoolId;
import java.util.Objects;

/**
 * 上下行人口流动的审计事件（持久状态的一等公民，不是会话内读数）。
 *
 * <p>身份 = {@code (tick, sequence)} 的纯函数（{@link ClassFlowEventId#of}）。{@code bundle} 是守恒明细（库存 + 权利 +
 * 债权/债务份额），随事件一起持久化，供回放与对账。
 *
 * @param id 稳定身份；必须等于 {@code ClassFlowEventId.of(tick, sequence)}
 * @param tick 发生 tick
 * @param direction UP = tier+1、DOWN = tier-1
 * @param fromPoolId 迁出池
 * @param toPoolId 迁入池
 * @param movedPopulation 迁移人数
 * @param bundle 守恒明细
 * @param aMilli 迁出前 A（千分）
 * @param afterAMilli 迁出后 A（千分）
 * @param originStockPerCapitaNotIncreased 原池库存人均份额未提高（应为 true）
 * @param xMilli 迁出前 x（千分）
 * @param ratePerMillePerYear 年化迁移率（千分）
 * @param opportunityPerMille 机会率（千分）
 * @param absorptionCapPerMille 吸收 cap（千分）
 * @param capMilliPeople cap 对应人数（milli-people）
 * @param skipLevel 是否跳级迁移
 * @param reason 审计原因
 */
public record ClassFlowEvent(
    ClassFlowEventId id,
    long tick,
    PilotModel.Direction direction,
    ClassPoolId fromPoolId,
    ClassPoolId toPoolId,
    long movedPopulation,
    PilotModel.TransitionBundle bundle,
    long aMilli,
    long afterAMilli,
    boolean originStockPerCapitaNotIncreased,
    long xMilli,
    long ratePerMillePerYear,
    long opportunityPerMille,
    long absorptionCapPerMille,
    long capMilliPeople,
    boolean skipLevel,
    String reason) {

  public ClassFlowEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(fromPoolId, "fromPoolId");
    Objects.requireNonNull(toPoolId, "toPoolId");
    Objects.requireNonNull(bundle, "bundle");
    if (tick < 0L || movedPopulation < 0L) {
      throw new IllegalArgumentException("ClassFlowEvent 的 tick/movedPopulation 都不得为负: " + tick);
    }
    if (reason == null) {
      throw new IllegalArgumentException("ClassFlowEvent.reason 不得为 null");
    }
  }

  /** 从引擎的迁移事件派生审计事件；{@code modeId} 用来拼出池 id。 */
  public static ClassFlowEvent from(String modeId, PilotModel.MobilityEvent event, long sequence) {
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(event, "event");
    return new ClassFlowEvent(
        ClassFlowEventId.of(event.tick(), sequence),
        event.tick(),
        event.direction(),
        ClassPoolId.idOf(modeId, event.fromClassPositionId()),
        ClassPoolId.idOf(modeId, event.toClassPositionId()),
        event.movedPopulation(),
        event.bundle(),
        event.aMilli(),
        event.afterAMilli(),
        event.originStockPerCapitaNotIncreased(),
        event.xMilli(),
        event.ratePerMillePerYear(),
        event.opportunityPerMille(),
        event.absorptionCapPerMille(),
        event.capMilliPeople(),
        event.skipLevel(),
        event.reason());
  }
}
