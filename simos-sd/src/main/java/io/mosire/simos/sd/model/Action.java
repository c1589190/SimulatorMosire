package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.util.address.Address;

/**
 * 效果动作（spec §三.6，R6）：trigger 达成时发生的事。v1 封闭集。
 *
 * <p>★ **跨模块效果**（如改 unit）落成 {@link EnqueueUnitCommand}——sd participant 只写 sd，跨模块由 app 层 {@code
 * SdCommandDrain} 经 {@code CoreSimos.submit} 落成真 revision（spec §五.3，铁律 3）。
 *
 * <p>★ **sealed 多态**：裸往返不可能（同 {@code FieldDelta}）⇒ 类型信息以注解钉在类型上。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：两档战斗耦合的动作随旧战斗族退役（如实记）</b>：
 *
 * <ul>
 *   <li>{@code SetStage(CombatStateId, CombatStageId)} —— 它引的 <b>两个 id 类型都是 sd 自己的战斗 id</b>，作用是推进
 *       {@code SdState.combatStates} 里的当前阶段（{@code SdTimeParticipant} 的 {@code Action.SetStage}
 *       支）；该表本批退役 ⇒ 编译期已不可能保留；
 *   <li>{@code RecordCasualties(LossRecordId)} —— 引 sd 自己的 {@code LossRecord}。★ 它<b>早已是死路</b>：全仓
 *       {@code src/main} 无生产者（没有任何命令/工具造出这个动作），而 {@code SdTimeParticipant.applyAction} 的 {@code
 *       default -> {}} <b>静默忽略</b>它 ⇒ 就算写进 {@code sd.RegisterEffect} 也永不生效。本批与族一起删除，不再留这条静默失效的路。
 * </ul>
 *
 * <p>★ <b>存活档（本批一个字未改语义）</b>：{@code PutInfo}（写 ga 的 INFO 覆盖层，经注入的 {@code DecisionTablesLookup}）与
 * {@code EnqueueUnitCommand}（跨模块效果：交 app 层 drain 成真 revision）。
 *
 * <p>★ <b>线格式的后果（如实记）</b>：{@code @class} 里的 {@code set_stage} / {@code record_casualties} 两个取值不再被认领
 * （Jackson 对未知子类型抛 ⇒ 该载荷在 {@code sd.RegisterEffect} 的命令期被<b>具名拒</b>）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Action.PutInfo.class, name = "put_info"),
  @JsonSubTypes.Type(value = Action.EnqueueUnitCommand.class, name = "enqueue_unit_command"),
})
public sealed interface Action {

  /** 给某地址改 sd 侧 INFO（感知层，spec §六）。 */
  record PutInfo(Address address, String key, Object value) implements Action {

    public PutInfo {
      if (address == null) {
        throw new IllegalArgumentException("address 不得为 null");
      }
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("key 不得为空白");
      }
      if (value == null) {
        throw new IllegalArgumentException("value 不得为 null");
      }
    }
  }

  /** 跨模块效果：把一条命令交 app 层 drain 提交（spec §五.3）。 */
  record EnqueueUnitCommand(String type, String payloadJson) implements Action {

    public EnqueueUnitCommand {
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("type 不得为空白");
      }
      if (payloadJson == null) {
        throw new IllegalArgumentException("payloadJson 不得为 null（无载荷用 \"{}\"）");
      }
    }
  }
}
