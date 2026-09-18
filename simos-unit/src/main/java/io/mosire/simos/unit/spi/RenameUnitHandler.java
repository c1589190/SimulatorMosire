package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;

/**
 * {@code unit.RenameUnit} 命令的处理器（spec §9.2，U15 乙）。选改名是因为它**最简单**（一个 {@code UnitId} + 一个 {@code
 * String}），从而把"信封全链"这件事本身暴露成唯一的被测对象：
 *
 * <pre>{@code
 * CommandEnvelope{type:"unit.RenameUnit", payloadJson:{"id":"u-1","name":"新名"}}
 *   → 本类自己反序列化 payload、调 UnitOperations.rename（M3 已有）
 *   → 返回 HandlerOutcome.Applied(UnitChangeSet)
 * }</pre>
 *
 * <p>★ **payload 形态是本模块的私事**（C26）：Core 只转交 {@code payloadJson} 字节串（R11）， 从不理解它的结构。本类认 {@code
 * {"id":字符串,"name":字符串}}，字段缺失或非字符串 ⇒ {@code Rejected}——载荷坏是命令的错，不是装配的错，走拒绝路径、 理由进 {@code
 * simos.command.rejected} 事件。字符串**原样使用、不做任何规范化**（空格、标点都在）。
 *
 * <p>★ **域规则违反以 {@code Rejected} 面世**：{@code UnitOperations} 的 {@code
 * IllegalArgumentException}（查无此人、名字空白） 在本边界折成拒绝理由——M3 的口径是操作面只抛、不兜底，折算发生在命令边界这一层。
 *
 * <p>★ **不是时间命令** ⇒ 没有 {@code TimeProposal}、没有读写集，该路径的读写集为空集（spec §9.2 末段）。
 */
public final class RenameUnitHandler implements CommandHandler {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平的 id/name 字符串对）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public String type() {
    return "unit.RenameUnit";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    JsonNode payload;
    try {
      payload = MAPPER.readTree(payloadJson);
    } catch (JsonProcessingException e) {
      return new HandlerOutcome.Rejected("payload 不是合法 JSON: " + e.getOriginalMessage());
    }
    JsonNode id = payload.get("id");
    JsonNode name = payload.get("name");
    if (id == null || !id.isTextual() || name == null || !name.isTextual()) {
      return new HandlerOutcome.Rejected("payload 必须是 {\"id\":字符串,\"name\":字符串}: " + payloadJson);
    }
    try {
      UnitState next =
          UnitOperations.rename(snapshot.state(), UnitId.parse(id.asText()), name.asText());
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
