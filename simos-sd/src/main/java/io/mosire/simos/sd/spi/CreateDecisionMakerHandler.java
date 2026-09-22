package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code sd.CreateDecisionMaker} 命令的处理器（spec §四）。
 *
 * <pre>{@code
 * {"id":"dm1","affiliation":{"kind":"nation","id":"n1"},
 *  "allowedTools":["sd.SubmitVerdict"],"cadence":1}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；{@code affiliation} 目标不存在；{@code allowedTools} 含**通用写**（{@link
 * SdCommandNames#SIMOS_COMMAND_SUBMIT}）⇒ 拒绝（N9）。
 *
 * <p>★ **创建期 {@code accessLimit} 恒为"无额外限制"**（{@link AccessLimit#empty()}）：可见范围由 app 层的范围函数 现算，GM
 * 配的是**额外收紧**——创建期不解析它，配权由 GM 专用的 {@code sd.SetDecisionMakerAccess} 写入（spec §4.2）。 这**不是**旧 {@code
 * viewScope.empty()} 那种 deny-all：新语义下空限制 = 不收紧，否则新建的决策人当场变瞎。
 */
public final class CreateDecisionMakerHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.CreateDecisionMaker";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId id = DecisionMakerId.parse(SdPayloads.requireText(payload, "id"));
      Affiliation affiliation = SdPayloads.requireAffiliation(payload, "affiliation");
      Set<String> allowedTools = SdPayloads.requireTextSet(payload, "allowedTools");
      long cadence = SdPayloads.requireLong(payload, "cadence");
      if (base.decisionMakers().containsKey(id)) {
        return new HandlerOutcome.Rejected("决策人已存在: " + id);
      }
      if (!affiliationExists(base, affiliation)) {
        return new HandlerOutcome.Rejected("affiliation 目标不存在: " + affiliation);
      }
      if (allowedTools.contains(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {
        return new HandlerOutcome.Rejected(
            "allowedTools 不得含通用写 " + SdCommandNames.SIMOS_COMMAND_SUBMIT + "（N9：决策 Agent 只用窄工具）");
      }
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.put(id, new DecisionMaker(id, affiliation, allowedTools, AccessLimit.empty(), cadence));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static boolean affiliationExists(SdState base, Affiliation affiliation) {
    return switch (affiliation) {
      case Affiliation.Nation nation -> base.nations().containsKey(nation.nationId());
      case Affiliation.Army army -> base.armies().containsKey(army.armyId());
    };
  }
}
