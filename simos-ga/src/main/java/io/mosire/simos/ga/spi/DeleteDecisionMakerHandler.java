package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.spi.SdPayloads;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code ga.DeleteDecisionMaker} 命令的处理器（P1b2，2026-10-01；<b>★ 2026-10-12 批次 3b-2 自 {@code
 * simos-sd/sd.spi} 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）：删除一个决策人的**身份本身**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1"}
 * }</pre>
 *
 * <p>★★ <b>只删决策人身份，不做任何级联</b>：本命令只从 {@link GaState#decisionMakers()} 删掉这一条；<b>不删</b>该决策人名下的
 * Directive（{@code directives} 表自 2026-10-12 批次 3b-1 起住 {@code
 * simos-ga}，本批起与决策人<b>同在一棵树</b>）、{@code info} 里的文档条目（它们只是 tag 引用，不是外键），也<b>不碰</b> app 层的 LLM 会话历史——
 * 那些是历史/其它切片的事实，删除它们不是本命令的语义。
 *
 * <p>★★ <b>"仍被引用 ⇒ 显式拒绝，不静默级联"这条守卫一个字不变，而且恢复了它的第二层</b>：批次 3b-1 曾因两张表跨切片而让<b>本守卫成为唯一 拦口</b>；本批
 * {@code decisionMakers} 也搬进 ga ⇒ {@link GaState} 构造期那条"每条 Directive 的 decisionMakerId 必须存在"
 * 的<b>状态期后备整条恢复</b>，本守卫回到它原本的位置（命令期的**具名前置**，让拒因可读、可点名引用数）。
 *
 * <p>★ <b>表都在本模块 ⇒ 直接读状态，不再需要注入的只读口</b>：批次 3b-1 引入的 {@code DirectiveLookup} 在本次搬迁后对本类不再需要 （它的另一个使用者
 * {@code SdResolver} 仍在 sd 侧，改由 {@code DecisionTablesLookup} 兑现）。
 *
 * <p>★ <b>GM-only</b>：不打 {@code DmUsableCommand} ⇒ handler 照常注册、GM 的 {@code simos.command.submit}
 * 照常可用，但排除出决策令白名单 / {@code sd.RegisterEffect} / 决策人目录（与 {@code sd.SetArmyMasterGov} 等同制）。
 *
 * <p>★ <b>失败语义</b>：{@code decisionMakerId} 缺失 / 空白 / 非法 ⇒ {@code Rejected}（载荷解析抛，命令边界折）；目标不存在 ⇒
 * {@code Rejected}（不做静默幂等）；仍被 Directive 引用 ⇒ {@code Rejected} 并点名引用数。
 */
public final class DeleteDecisionMakerHandler implements CommandHandler {

  @Override
  public String type() {
    return "ga.DeleteDecisionMaker";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String dmForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId id =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "decisionMakerId"));
      dmForLog = id.value();
      DecisionMaker existing = base.decisionMakers().get(id);
      if (existing == null) {
        return rejected("决策人不存在: " + id, "dm", dmForLog);
      }
      List<String> directiveIds = referencingDirectiveIds(base, id);
      if (!directiveIds.isEmpty()) {
        return rejected(
            "决策人 "
                + id
                + " 仍被 "
                + directiveIds.size()
                + " 条 Directive 引用（"
                + summarize(directiveIds)
                + "）：本命令只删决策人身份，不级联删历史 Directive / 文档 / 会话；删除会破坏 ga 引用完整性，"
                + "请先处置这些指令",
            "dm",
            dmForLog,
            "directives",
            directiveIds.size());
      }
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.remove(id);
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_DELETE_DECISION_MAKER_APPLIED",
                  GaLogSource.GA_DECISION,
                  "dm",
                  id.value(),
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
        "SD_DELETE_DECISION_MAKER_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /**
   * 仍引用该决策人的 Directive id 串（按 id 自然序，确定性；命令期只读快照，不落任何东西）。
   *
   * <p>★ 表都在本模块 ⇒ 直接扫 {@code GaState.directives()}；排序口径与搬迁前逐字相同（{@code DirectiveId.value()} 的字典序）。
   */
  private static List<String> referencingDirectiveIds(GaState base, DecisionMakerId id) {
    List<DirectiveId> ids = new ArrayList<>();
    for (Directive directive : base.directives().values()) {
      if (directive.decisionMakerId().equals(id)) {
        ids.add(directive.id());
      }
    }
    ids.sort((left, right) -> left.value().compareTo(right.value()));
    List<String> out = new ArrayList<>(ids.size());
    for (DirectiveId directiveId : ids) {
      out.add(directiveId.value());
    }
    return List.copyOf(out);
  }

  /** 拒因里最多点名前 5 条，避免长历史把拒因文本撑爆；数量始终是真实总数。 */
  private static String summarize(List<String> ids) {
    if (ids.size() <= 5) {
      return ids.toString();
    }
    return ids.subList(0, 5) + " 等";
  }
}
