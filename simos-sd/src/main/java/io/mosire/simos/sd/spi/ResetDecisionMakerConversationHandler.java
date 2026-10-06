package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.ResetDecisionMakerConversation} 命令的处理器：GM 把某个决策人的 LLM 会话**重置为新会话**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1"}
 * }</pre>
 *
 * <p>★★ **现实动因是现场实测**：改版前落盘的老会话在回放时会 400（历史里没有 {@code reasoning_content}，补不上） ⇒
 * 唯一的处置是"换个新会话重开"。本命令把那件事**工具化**——它是**一条真命令**（铁律 2：走 {@code Command → ChangeSet →
 * Revision}），不是"去把文件删了"。
 *
 * <p>★★ **它只把 {@link DecisionMaker#conversationGeneration()} 加一**，因此有三个性质是**结构上**成立的，不靠约定：
 *
 * <ol>
 *   <li>**不删任何历史字节**：会话库（{@code ConversationStore}）的契约是 append-only，本命令根本够不着它——它只改世界事实；
 *       旧会话原样留在库里可审计（"谁在什么时候说了什么"是 AAR 的凭据，不是缓存）；
 *   <li>**换不了别人的会话**：载荷只有 id、动作只有 +1，**没有任何字段能让某个决策人指向另一个人的会话**；
 *   <li>**可回放、可回退分岔**：世代是普通字段（与 {@code providerId} 同制），回退到旧 revision 就回到旧会话上。
 * </ol>
 *
 * <p>★ **会话 id 不由本层拼**：sd 不认识会话库、也不知道 id 的写法（铁律 3）。派生在 app 层（{@code
 * DecisionAgentRunner.conversationIdOf}），**世代 0 时必须与旧格式逐字相同**——现场已落盘的老会话因此接得上。
 *
 * <p>★ **重建必须逐字段带过来**：白名单 / accessLimit / 决策周期 / providerId 一个都不能丢。只写"id + 世代"的实现会 **静默把 provider
 * 解绑、把配权抹掉**——与 M11 那条（{@code SetDecisionMakerAccess} 丢 {@code providerId}）同形， 故这里逐字段显式传。
 *
 * <p>拒绝：{@code decisionMakerId} 缺失/空白；该决策人不存在。
 */
public final class ResetDecisionMakerConversationHandler implements CommandHandler {

  /**
   * ★ **必须是字面量**（不许提成常量）：app 侧两条**派生式同源判据**（catalog ↔ 全部 {@code *Handler.java} 的 {@code
   * type()}；窄写工具 ↔ GM 桶）都按**源码里的字符串字面量**扫描——写成常量引用，扫描器当场判"抽不到"并**红**。
   */
  @Override
  public String type() {
    return "sd.ResetDecisionMakerConversation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String dmForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId id =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "decisionMakerId"));
      dmForLog = id.value();

      DecisionMaker existing = base.decisionMakers().get(id);
      if (existing == null) {
        return rejected("决策人不存在: " + id.value(), "dm", dmForLog);
      }

      DecisionMaker next =
          new DecisionMaker(
              existing.id(),
              existing.affiliation(),
              existing.allowedTools(),
              existing.accessLimit(),
              existing.decisionCadenceTicks(),
              // ★ 与 M11 同一条纪律：重建路径必须带回 providerId，否则"换个会话"顺手把 provider 解绑了。
              existing.providerId(),
              existing.conversationGeneration() + 1);
      // ★ **用 withDecisionMakers 派生新状态**（不是 new SdState(...)）：后者会静默清空别的九个组件（本仓那条通则的由来）。
      Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>(base.decisionMakers());
      makers.put(id, next);
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_RESET_DECISION_MAKER_CONVERSATION_APPLIED",
                  SdLogSource.SD_NATION,
                  "dm",
                  id.value(),
                  "fromGeneration",
                  existing.conversationGeneration(),
                  "toGeneration",
                  next.conversationGeneration()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(makers)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "dm", dmForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.decision(),
        SdLogSource.SD_NATION,
        "SD_RESET_DECISION_MAKER_CONVERSATION_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
