package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.SetDecisionMakerProvider} 命令的处理器（M11）：给某个决策人**绑定 LLM provider 引用**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1","providerId":"p-openai"}
 * }</pre>
 *
 * <p>★ **绑定本身是数据**（铁律 2）：{@code providerId} 写进 {@link DecisionMaker}、随 revision 落盘 ⇒ 可回放、可回退分岔。
 *
 * <p>★★ **本处理器只校验"非空白"，不校验 provider 是否存在**：provider 配置在 app / AgentLib 层，sd 看不见（铁律 3）。存在性由
 * **使用时刻**的解析强制——解析不到就 fail-closed（明确报错或既定降级），**绝不静默兜底**（见 {@link DecisionMaker} 的类注「回放语义」）。 在 sd
 * 里校验存在性既越界，也会把"配置缺失"与"世界事实变化"混为一谈。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；{@code providerId} 缺失 / 非字符串 / 空白。
 */
public final class SetDecisionMakerProviderHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.SetDecisionMakerProvider";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId id =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "decisionMakerId"));
      String providerId = SdPayloads.requireText(payload, "providerId");
      if (providerId.isBlank()) {
        return new HandlerOutcome.Rejected("providerId 不得为空白");
      }
      DecisionMaker existing = base.decisionMakers().get(id);
      if (existing == null) {
        return new HandlerOutcome.Rejected("决策人不存在: " + id.value());
      }
      DecisionMaker updated =
          new DecisionMaker(
              existing.id(),
              existing.affiliation(),
              existing.allowedTools(),
              existing.accessLimit(),
              existing.decisionCadenceTicks(),
              Optional.of(providerId));
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.put(id, updated);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
