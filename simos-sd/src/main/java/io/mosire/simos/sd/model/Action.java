package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.util.address.Address;

/**
 * 效果动作（spec §三.6，R6）：trigger 达成时发生的事。v1 封闭集。
 *
 * <p>★ **跨模块效果**（如改 unit）落成 {@link EnqueueUnitCommand}——sd participant 只写 sd，跨模块由 app 层 {@code
 * SdCommandDrain} 经 {@code CoreSimos.submit} 落成真 revision（spec §五.3，铁律 3）。
 *
 * <p>★ **sealed 多态**：裸往返不可能（同 {@code FieldDelta}）⇒ 类型信息以注解钉在类型上。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Action.PutInfo.class, name = "put_info"),
  @JsonSubTypes.Type(value = Action.SetStage.class, name = "set_stage"),
  @JsonSubTypes.Type(value = Action.RecordCasualties.class, name = "record_casualties"),
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

  /** 推进某交战状态的当前阶段（N1）。 */
  record SetStage(CombatStateId combatState, CombatStageId stage) implements Action {

    public SetStage {
      if (combatState == null) {
        throw new IllegalArgumentException("combatState 不得为 null");
      }
      if (stage == null) {
        throw new IllegalArgumentException("stage 不得为 null");
      }
    }
  }

  /** 记录一次战损（引 sd 自己的 {@code LossRecord}，N3）。 */
  record RecordCasualties(LossRecordId lossRecord) implements Action {

    public RecordCasualties {
      if (lossRecord == null) {
        throw new IllegalArgumentException("lossRecord 不得为 null");
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
