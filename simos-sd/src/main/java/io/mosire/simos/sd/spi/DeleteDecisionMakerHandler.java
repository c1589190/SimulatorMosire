package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.DeleteDecisionMaker} 命令的处理器（P1b2，2026-10-01）：删除一个决策人的**身份本身**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1"}
 * }</pre>
 *
 * <p>★★ <b>只删决策人身份，不做任何级联</b>：本命令只从 {@link SdState#decisionMakers()} 删掉这一条；<b>不删</b>该决策人名下的
 * Directive（{@code directives} 表自 2026-10-12 批次 3b′ 起住 {@code simos-ga}）、{@code sd.info()}
 * 里的文档条目（它们只是 tag 引用，不是外键），也<b>不碰</b> app 层的 LLM 会话历史—— 那些是历史/其它切片的事实，删除它们不是本命令的语义。
 *
 * <p>★★ <b>"仍被引用 ⇒ 显式拒绝，不静默级联"这条守卫一个字不变</b>（★ 理由换了，判据没换）：批次 3b′ 之前它是 {@link SdState} 构造期"每条
 * Directive 的 decisionMakerId 必须存在"那条不变量的<b>命令期前置</b>；表搬进 ga 后那条外键成了<b>跨切片</b> 的（铁律 3：ga 不拥有 {@code
 * decisionMakers}）⇒ 状态期不再判，本命令这条守卫反而成了<b>唯一的</b>拦口。若目标决策人仍被任何 Directive 引用，本命令<b>不</b>顺手删
 * Directive（那会违背上面的"不级联"），而是给出具名 {@code Rejected}，让调用方先处置历史指令—— 既守住"删身份 ≠ 删历史"，也不留下悬空引用。
 *
 * <p>★ <b>表在 ga ⇒ 读口注入</b>：{@code directives} 表住 {@code simos-ga}，而 {@code simos-sd} <b>不得</b>依赖
 * {@code simos-ga}（成环）⇒ 本类经构造期注入的 {@link DirectiveLookup} 读它（实现 {@code GaDirectiveLookup} 在 ga、 组合根
 * {@code Shell} 接线；形制见该接口的类注）。
 *
 * <p>★ <b>GM-only</b>：不打 {@code DmUsableCommand} ⇒ handler 照常注册、GM 的 {@code simos.command.submit}
 * 照常可用，但排除出决策令白名单 / {@code sd.RegisterEffect} / 决策人目录（与 {@code sd.SetArmyMasterGov} 等同制）。
 *
 * <p>★ <b>失败语义</b>：{@code decisionMakerId} 缺失 / 空白 / 非法 ⇒ {@code Rejected}（载荷解析抛，命令边界折）；目标不存在 ⇒
 * {@code Rejected}（不做静默幂等）；仍被 Directive 引用 ⇒ {@code Rejected} 并点名引用数。
 */
public final class DeleteDecisionMakerHandler implements CommandHandler {

  private final DirectiveLookup directives;

  /**
   * @param directives ga 侧 {@code directives} 表的只读口（装配期注入；非 null——缺了它"不级联"守卫无从判定，故当场拒）
   */
  public DeleteDecisionMakerHandler(DirectiveLookup directives) {
    this.directives = Objects.requireNonNull(directives, "directives");
  }

  @Override
  public String type() {
    return "sd.DeleteDecisionMaker";
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
        return rejected("决策人不存在: " + id, "dm", dmForLog);
      }
      List<String> directiveIds = referencingDirectiveIds(state, id);
      if (!directiveIds.isEmpty()) {
        return rejected(
            "决策人 "
                + id
                + " 仍被 "
                + directiveIds.size()
                + " 条 Directive 引用（"
                + summarize(directiveIds)
                + "）：本命令只删决策人身份，不级联删历史 Directive / 文档 / 会话；删除会破坏 sd 引用完整性，"
                + "请先处置这些指令",
            "dm",
            dmForLog,
            "directives",
            directiveIds.size());
      }
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.remove(id);
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_DELETE_DECISION_MAKER_APPLIED",
                  SdLogSource.SD_NATION,
                  "dm",
                  id.value(),
                  "decisionMakers",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(next)));
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
        "SD_DELETE_DECISION_MAKER_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /**
   * 仍引用该决策人的 Directive id 串（按 id 自然序，确定性；命令期只读快照，不落任何东西）。
   *
   * <p>★ 表在 ga ⇒ 走注入的只读口（{@link DirectiveLookup#referencing}，其契约已钉"按 id 自然序"），本类只把它摊成字符串。
   */
  private List<String> referencingDirectiveIds(SimulationState state, DecisionMakerId id) {
    List<String> ids = new ArrayList<>();
    for (DirectiveId directiveId : directives.referencing(state, id)) {
      ids.add(directiveId.value());
    }
    return List.copyOf(ids);
  }

  /** 拒因里最多点名前 5 条，避免长历史把拒因文本撑爆；数量始终是真实总数。 */
  private static String summarize(List<String> ids) {
    if (ids.size() <= 5) {
      return ids.toString();
    }
    return ids.subList(0, 5) + " 等";
  }
}
