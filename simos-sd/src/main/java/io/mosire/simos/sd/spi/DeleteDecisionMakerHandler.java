package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
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
 * <p>★★ <b>只删决策人身份，不做任何级联</b>：本命令只从 {@link SdState#decisionMakers()} 删掉这一条；<b>不删</b>该决策人名下的 {@link
 * Directive}、{@code sd.info()} 里的文档条目（它们只是 tag 引用，不是外键），也<b>不碰</b> app 层的 LLM 会话历史—— 那些是历史/其它切片的
 * 事实，删除它们不是本命令的语义。
 *
 * <p>★★ <b>引用完整性的唯一例外必须显式拒绝，不静默级联</b>：{@link SdState} 的构造期不变量要求每条 {@code Directive.decisionMakerId}
 * 都存在于 {@code decisionMakers}。若目标决策人仍被任何 Directive 引用，本命令<b>不</b>顺手删 Directive（那会违背上面的"不级联"），而是给出具名
 * {@code Rejected}，让调用方先处置历史指令。这样既守住状态不变量，也不把"删身份"悄悄升级成"删历史"。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒ handler 照常注册、GM 的 {@code simos.command.submit}
 * 照常可用，但排除出决策令白名单 / {@code sd.RegisterEffect} / 决策人目录（与 {@code sd.SetArmyMasterGov} 等同制）。
 *
 * <p>★ <b>失败语义</b>：{@code decisionMakerId} 缺失 / 空白 / 非法 ⇒ {@code Rejected}（载荷解析抛，命令边界折）；目标不存在 ⇒
 * {@code Rejected}（不做静默幂等）；仍被 Directive 引用 ⇒ {@code Rejected} 并点名引用数。
 */
public final class DeleteDecisionMakerHandler implements CommandHandler, GmOnlyCommand {

  @Override
  public String type() {
    return "sd.DeleteDecisionMaker";
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
      DecisionMaker existing = base.decisionMakers().get(id);
      if (existing == null) {
        return new HandlerOutcome.Rejected("决策人不存在: " + id);
      }
      List<String> directiveIds = referencingDirectiveIds(base, id);
      if (!directiveIds.isEmpty()) {
        return new HandlerOutcome.Rejected(
            "决策人 "
                + id
                + " 仍被 "
                + directiveIds.size()
                + " 条 Directive 引用（"
                + summarize(directiveIds)
                + "）：本命令只删决策人身份，不级联删历史 Directive / 文档 / 会话；删除会破坏 sd 引用完整性，"
                + "请先处置这些指令");
      }
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.remove(id);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 仍引用该决策人的 Directive id（按 id 自然序，确定性；命令期只读 base 快照）。 */
  private static List<String> referencingDirectiveIds(SdState base, DecisionMakerId id) {
    List<String> ids = new ArrayList<>();
    for (Directive directive : base.directives().values()) {
      if (directive.decisionMakerId().equals(id)) {
        ids.add(directive.id().value());
      }
    }
    ids.sort(String::compareTo);
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
