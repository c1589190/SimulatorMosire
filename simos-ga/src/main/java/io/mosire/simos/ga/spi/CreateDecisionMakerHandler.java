package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.spi.SdCommandNames;
import io.mosire.simos.sd.spi.SdPayloads;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code ga.CreateDecisionMaker} 命令的处理器（spec §四；<b>★ 2026-10-12 批次 3b-2 自 {@code simos-sd/sd.spi}
 * 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）。
 *
 * <pre>{@code
 * {"id":"dm1","allowedTools":["ga.SubmitVerdict"],"cadence":1}
 * }</pre>
 *
 * <p>★★★ <b>2026-10-12 乙收尾：{@code affiliation}
 * 载荷<b>整条删除</b></b>（用户原话「归属肯定归模块啊」＋「不但允许创建<b>没有更多附加状态的单个决策人</b>，
 * 也可以创建/为已有决策人<b>赋予、修改状态</b>」）：本命令从此只造<b>裸决策人身份</b>；归属由各持有模块的赋权工单后赋 —— {@code
 * gov.AssignDecisionMakerNation}（决策人 → 国家）· {@code gov.AssignDecisionMakerGov}（决策人 → GOV 单位）·
 * {@code army.AssignDecisionMakerArmy}（决策人 → 军队）。
 *
 * <p>★ <b>旧 {@code affiliation} 键<b>具名拒并指路</b></b>（与 {@code army.CreateArmy} 对旧 {@code nationId}
 * 的处置同款： 拒绝，不静默忽略）：字段已从 {@link DecisionMaker} 删除，继续兼容读会让人以为"决策人自带归属"。拒因直接给出三条新命令。
 *
 * <p>★ 拒绝：id 已存在；带了 {@code affiliation}；{@code allowedTools} 含**通用写**（{@link
 * SdCommandNames#SIMOS_COMMAND_SUBMIT}）⇒ 拒绝（N9）。
 *
 * <p>★ **创建期 {@code accessLimit} 恒为"无额外限制"**（{@link AccessLimit#empty()}）：可见范围由 app 层的范围函数 现算，GM
 * 配的是**额外收紧**——创建期不解析它，配权由 GM 专用的 {@code ga.SetDecisionMakerAccess} 写入（spec §4.2）。 这**不是**旧 {@code
 * viewScope.empty()} 那种 deny-all：新语义下空限制 = 不收紧，否则新建的决策人当场变瞎。
 *
 * <p>★★ <b>归属校验去哪了（如实记）</b>：3c/3d-1 期间它住 sd 的 {@code AffiliationTargets}（读 unit 切片的 {@code
 * GovernmentFormation}），本批随"归属归模块"<b>整条搬进各持有模块的赋权命令</b>——Nation 的存在性判据在 {@code
 * gov.AssignDecisionMakerNation}、Gov 单位的"存在 + 带 GovernmentFormation"在 {@code
 * gov.AssignDecisionMakerGov}、 军队存在性在 {@code army.AssignDecisionMakerArmy}。★
 * 那三条命令<b>都</b>比本命令多一条判据：{@code decisionMakerId} 必须是已存在的决策人（拆分前"创建"与"绑归属"是同一条命令，不存在这个问题）⇒ 走 {@code
 * ga.spi.DecisionMakerExistencePort}。
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
      if (payload.has("affiliation") && !payload.get("affiliation").isNull()) {
        return rejected(
            "ga.CreateDecisionMaker 不再接受 affiliation：决策人身份与归属已分开（用户裁定「归属肯定归模块」）。"
                + "先建裸决策人，再用工单赋归属：gov.AssignDecisionMakerNation（决策人 → 国家）、"
                + "gov.AssignDecisionMakerGov（决策人 → GOV 单位）、army.AssignDecisionMakerArmy（决策人 → 军队）；"
                + "没有归属的决策人读不到任何可见范围（deny-all）。",
            "dm",
            dmForLog);
      }
      Set<String> allowedTools = SdPayloads.requireTextSet(payload, "allowedTools");
      long cadence = SdPayloads.requireLong(payload, "cadence");
      if (base.decisionMakers().containsKey(id)) {
        return rejected("决策人已存在: " + id, "dm", dmForLog);
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
      next.put(id, new DecisionMaker(id, allowedTools, AccessLimit.empty(), cadence));
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_DECISION_MAKER_CREATED",
                  GaLogSource.GA_DECISION,
                  "id",
                  id.value(),
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
