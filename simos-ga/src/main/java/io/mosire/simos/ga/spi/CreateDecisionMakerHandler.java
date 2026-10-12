package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.spi.AffiliationTargets;
import io.mosire.simos.sd.spi.SdCommandNames;
import io.mosire.simos.sd.spi.SdPayloads;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ga.CreateDecisionMaker} 命令的处理器（spec §四；<b>★ 2026-10-12 批次 3b-2 自 {@code simos-sd/sd.spi}
 * 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）。
 *
 * <pre>{@code
 * {"id":"dm1","affiliation":{"kind":"nation","id":"n1"},
 *  "allowedTools":["ga.SubmitVerdict"],"cadence":1}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；{@code affiliation} 目标不存在；{@code allowedTools} 含**通用写**（{@link
 * SdCommandNames#SIMOS_COMMAND_SUBMIT}）⇒ 拒绝（N9）。
 *
 * <p>★ **创建期 {@code accessLimit} 恒为"无额外限制"**（{@link AccessLimit#empty()}）：可见范围由 app 层的范围函数 现算，GM
 * 配的是**额外收紧**——创建期不解析它，配权由 GM 专用的 {@code ga.SetDecisionMakerAccess} 写入（spec §4.2）。 这**不是**旧 {@code
 * viewScope.empty()} 那种 deny-all：新语义下空限制 = 不收紧，否则新建的决策人当场变瞎。
 *
 * <p>★★ <b>归属校验仍在 sd 侧</b>（{@link AffiliationTargets}）：它要读 {@code sd} 的 {@code nations}/{@code
 * armies} 与 <b>unit 切片</b>的 {@code GovernmentFormation}，而本模块<b>没有声明</b> {@code simos-unit} 依赖 ⇒
 * 那段判据留在 sd （它服务的正是 sd 的 {@link Affiliation} 值类型），本类经既有的 {@code ga → sd} 方向调用；<b>不是复制第二份</b>。
 *
 * <p>★ <b>GM-only</b>（反默认口径）：不打 {@code DmUsableCommand} ⇒ handler 照常注册、GM 的 {@code
 * simos.command.submit} 照常可用，但排除出决策令白名单 / {@code sd.RegisterEffect} / 决策人目录 —— 与搬迁前逐字同档。
 */
public final class CreateDecisionMakerHandler implements CommandHandler {

  @Override
  public String type() {
    return "ga.CreateDecisionMaker";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String dmForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId id = DecisionMakerId.parse(SdPayloads.requireText(payload, "id"));
      dmForLog = id.value();
      Affiliation affiliation = SdPayloads.requireAffiliation(payload, "affiliation");
      Set<String> allowedTools = SdPayloads.requireTextSet(payload, "allowedTools");
      long cadence = SdPayloads.requireLong(payload, "cadence");
      if (base.decisionMakers().containsKey(id)) {
        return rejected("决策人已存在: " + id, "dm", dmForLog);
      }
      Optional<String> affiliationProblem = AffiliationTargets.problem(state, affiliation);
      if (affiliationProblem.isPresent()) {
        return rejected(affiliationProblem.get(), "dm", dmForLog);
      }
      if (allowedTools.contains(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {
        return rejected(
            "allowedTools 不得含通用写 " + SdCommandNames.SIMOS_COMMAND_SUBMIT + "（N9：决策 Agent 只用窄工具）",
            "dm",
            dmForLog,
            "allowedTools",
            allowedTools.size());
      }
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.put(id, new DecisionMaker(id, affiliation, allowedTools, AccessLimit.empty(), cadence));
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_DECISION_MAKER_CREATED",
                  GaLogSource.GA_DECISION,
                  "id",
                  id.value(),
                  "affiliation",
                  affiliation,
                  "cadence",
                  cadence,
                  "allowedTools",
                  allowedTools.size(),
                  "decisionMakers",
                  next.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withDecisionMakers(next)));
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
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "SD_CREATE_DECISION_MAKER_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
