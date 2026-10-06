package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.workorder.HouseholdWorkOrder;
import io.mosire.simos.social.workorder.HouseholdWorkOrderBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code social.SubmitHouseholdWorkOrder} 命令的处理器（2026-10-09 用户裁定：Social 是<b>唯一家户人口变更受理口</b>）：
 * 一张工单 = "更改对象 {@code target} + 有序方案 {@code plan} + 理由 {@code reason} + 来源 {@code source}"。
 *
 * <pre>{@code
 * {"orderId"?:"wo-1",
 *  "target":"hh-1",
 *  "reason":"征兵",
 *  "source":{"module":"unit","commandId"?:"...","actorId"?:"..."},
 *  "dryRun"?:false,
 *  "plan":[
 *    {"op":"CREATE_HOUSEHOLD","household":"hh-new","location":{...},"profile":{"name":"..."},"vitalRates"?},
 *    {"op":"SET_LOCATION","household":"hh-1","location":{"type":"HEX","hex":{"q":1,"r":0}}},
 *    {"op":"ADD_MEMBERS","household":"hh-1","lotId"?,"sex":"MALE","count":12,"ageAtAnchorDays"?,"anchorTick"?},
 *    {"op":"REMOVE_MEMBERS","household":"hh-1","lotId":"lot-1","count":3},
 *    {"op":"TRANSFER_MEMBERS","from":"hh-1","to":"hh-2","lotId":"lot-1","count":4},
 *    {"op":"ADJUST_POPULATION","household":"hh-1","sex":"FEMALE","ageBracketId":"15-59","delta":-2},
 *    {"op":"SET_VITAL_RATES","household":"hh-1","vitalRates":[...]}
 *  ]}
 * }</pre>
 *
 * <p>★★ <b>语义</b>：从 base {@link SocialData} 起把 plan 顺序应用为一个<b>工作副本</b>（见
 * {@link HouseholdWorkOrderBook}）——任一步失败 ⇒ 整单具名拒、不部分生效；成功 ⇒
 * {@link SocialChangeSet#between(SocialData, SocialData)} 一条 revision。逐操作写口仍全部收口在 {@code HouseholdBook}
 * （本 handler 不直接改任何状态字段；铁律 2）。
 *
 * <p>★ <b>幂等</b>：{@code orderId} 给定时是幂等键；同 orderId 重复提交 ⇒ 具名拒（见
 * {@link HouseholdWorkOrder#markerEventId()} 与 {@code WORK_ORDER} 标记事件）。未给 ⇒ 不做幂等。
 * {@code expectedRevision} 不在载荷里：它由命令信封承载，Core 在任何 handler 之前做乐观并发检查。
 *
 * <p>★ <b>dryRun</b>：命令 handler 只有 {@code Applied}/{@code Rejected} 两种结局，没有"只算不写"；{@code dryRun=true}
 * 在 {@code HouseholdWorkOrderPayloads} 里具名拒（不假装成功、不落空 revision）。预览由 app 工具预览路径承担。
 *
 * <p>★ <b>旧命令保留</b>：{@code social.CreateHousehold} / {@code SetHouseholdLocation} / {@code Add/Remove/
 * TransferHouseholdMembers} / {@code AdjustHouseholdPopulation} / {@code SetHouseholdVitalRates} 逐操作入口继续可用；
 * 本命令是新增的统一受理口，不替换它们。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：2026-10-20 起走跨命名空间 {@link #targetResources}——解析整张 plan 的
 * CREATE_HOUSEHOLD.location / SET_LOCATION（旧位置 + 新位置）/ 全部 household/from/to 引用；创建型用载荷 location， 现有家户用
 * SocialData 现值（plan 内先建的户按工作副本位置解析）。旧 {@link #targetPaths} 保留为空列表（它看不到 state， 升级前逐字一致）。
 */
public final class SubmitHouseholdWorkOrderHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.SubmitHouseholdWorkOrder";

  /**
   * 目标 = plan 的逐步骤解析结果（保序去重）。★ 用与 {@code handle} 同一台载荷解析器 + 同一 worldTick 口径，不造第二份 plan 语义。
   */
  @Override
  public List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    long worldTick = state.meta().timestamp().tick();
    HouseholdWorkOrder order = HouseholdWorkOrderPayloads.parse(payload, worldTick);
    return HouseholdCommandTargets.workOrderTargets(state, order);
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有目标可给
    return List.of();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      long worldTick = state.meta().timestamp().tick();
      HouseholdWorkOrder order = HouseholdWorkOrderPayloads.parse(payload, worldTick);
      SocialData next = HouseholdWorkOrderBook.apply(base, order, worldTick);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
