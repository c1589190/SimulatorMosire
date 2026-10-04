package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * {@code sd.SetDiplomaticRelation} 命令的处理器（D-003 / D-005 / R6）：**upsert 一条有向外交关系边**。
 *
 * <pre>{@code
 * {"from":"大蜀","to":"西陵","kind":"称臣纳贡","text":"……自然语言……","tick":120}
 * }</pre>
 *
 * <ul>
 *   <li>{@code from} / {@code to} 必填、非空白，且必须是**已存在的 sd.Nation**（查 {@code SdState.nations()}，查无 ⇒
 *       具名拒）；{@code from == to} ⇒ 拒（D-003 的边不建自环）；
 *   <li>{@code kind} 可选（自由文本，可空；"称臣纳贡"只是它的一个取值——本命令**不**解释它、也**不**写死贡额/周期/违约）；
 *   <li>{@code text} 必填非空白：自然语言描述；**谈判/联动裁决的状态就记在这里**（D-005），对同一 (from,to) 再调一次就是
 *       **更新**（upsert，不新增第二条边）；
 *   <li>{@code tick} 可选，缺省 = 世界当前 tick；**不得记在未来**（{@code > 世界 tick} ⇒ 拒，与 {@code sd.PutInfo}/{@code
 *       sd.IssueDirective} 同口径）；过去合法（补记/滞后），存进 {@code updatedTick}。
 * </ul>
 *
 * <p>★ **非 GmOnly**：命令本身不做调用者身份判定——"不许以别国名义写"由**决策人窄工具**的 signature 层拒绝（命令结构性看不见调用者）； GM 走 {@code
 * simos.command.submit} / GM 窄工具。{@code sd.*} 本就不进指令白名单（禁自指），不担心嵌令递归。
 */
public final class SetDiplomaticRelationHandler implements CommandHandler {

  private static final Logger LOG = SdLog.diplomacy();

  @Override
  public String type() {
    return "sd.SetDiplomaticRelation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      NationId from = NationId.parse(SdPayloads.requireText(payload, "from"));
      NationId to = NationId.parse(SdPayloads.requireText(payload, "to"));
      String text = SdPayloads.requireText(payload, "text");
      // kind 可空：缺省 / null / 空白 ⇒ 无 kind（"称臣纳贡"只是它的一个取值，本层不解释）。
      Optional<String> kind =
          SdPayloads.optionalText(payload, "kind").filter(value -> !value.isBlank());
      long worldTick = state.meta().timestamp().tick();
      long tick = SdPayloads.optionalLong(payload, "tick", worldTick);
      if (tick < 0L) {
        return new HandlerOutcome.Rejected("外交关系 tick 不得为负: " + tick);
      }
      if (tick > worldTick) {
        return new HandlerOutcome.Rejected(
            "外交关系不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }
      if (from.equals(to)) {
        return new HandlerOutcome.Rejected("外交关系两端不得相同（from=to=" + from.value() + "）");
      }
      if (!base.nations().containsKey(from)) {
        return new HandlerOutcome.Rejected("外交关系 from Nation 不存在: " + from.value());
      }
      if (!base.nations().containsKey(to)) {
        return new HandlerOutcome.Rejected("外交关系 to Nation 不存在: " + to.value());
      }
      DiplomaticRelationKey key = new DiplomaticRelationKey(from, to);
      Map<DiplomaticRelationKey, DiplomaticRelation> next =
          new LinkedHashMap<>(base.diplomaticRelations());
      next.put(key, new DiplomaticRelation(kind, text, tick));
      LOG.info(
          "event=SD_DIPLOMATIC_RELATION_SET from={} to={} kindPresent={} tick={}",
          from.value(),
          to.value(),
          kind.isPresent(),
          tick);
      return new HandlerOutcome.Applied(
          SdChangeSet.between(base, base.withDiplomaticRelations(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
