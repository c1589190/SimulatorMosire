package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.SetDirectiveStatus} 命令的处理器（第 3 波最后一块）：把一条令的 {@code Directive.status} 从 {@code ISSUED}
 * 翻到两个**终态**之一。
 *
 * <pre>{@code
 * {"directiveId":"d1","status":"EXECUTED"}
 * {"directiveId":"d2","status":"CANCELLED"}
 * }</pre>
 *
 * <p>★ **它不做对外的事**（有意划界，别在别处把它接成窄工具 / 端点）：它是**裁决的内部编排**——{@code sd.AdjudicateTick} 在裁决一个 tick
 * 时，按每条令的命令结局生成对应的翻转命令，**与命令效果、决策结果条目同批**落盘（见 {@code AdjudicateTickTool}）。域里其余写路径都不会产生这种命令。
 *
 * <p>★★ **转移守卫**（本处理器唯一有语义的部分，逐条可读）：
 *
 * <ol>
 *   <li><b>令必须存在</b> ⇒ 否则拒（{@code 决策不存在: <id>}）；
 *   <li><b>目标值只允许两个终态</b> {@code EXECUTED} / {@code CANCELLED} ⇒ {@code PLANNED}/{@code ISSUED}
 *       以及一切非法串一律拒（目标值非法：…）；
 *   <li><b>只允许 {@code ISSUED → 终态}</b>：当前不是 {@code ISSUED} ⇒ 拒——这条同时堵住"重复翻转"（已 {@code EXECUTED} /
 *       {@code CANCELLED} 的令**不得改回**，两个终态之间也不得互改）与"翻一条还没发出的 {@code PLANNED} 令"。★ 它**也**堵住 {@code
 *       SUPERSEDED} 那一档：它既是终态（当前不是 {@code ISSUED} ⇒ 拒），也不在目标集里（见第 2 条）—— 该档只由 {@code
 *       sd.IssueDirective} 内部"顶掉旧令"产生，**不是外部可设的目标**。
 * </ol>
 *
 * <p>★ 拒因写明**当前态与目标态**（可读、可核对），不写一句笼统的"状态不对"。
 */
public final class SetDirectiveStatusHandler implements CommandHandler {

  /**
   * 命令类型。
   *
   * <p>★ {@link #type()} 里仍写**字面量**而不是引用本常量：{@code SimosToolsTest} 从源码抽 {@code type()} 的返回串做"注册面 ==
   * 实现面"的强判据，扫描器只认 {@code return "…"} 形态（引用常量会被漏掉）。两处同值由下方注释钉住。
   */
  public static final String TYPE = "sd.SetDirectiveStatus";

  @Override
  public String type() {
    return "sd.SetDirectiveStatus"; // ★ 必须与 TYPE 同值：扫描器要求字面量（见 TYPE 的注）
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DirectiveId id = DirectiveId.parse(SdPayloads.requireText(payload, "directiveId"));
      DirectiveStatus target = parseTarget(SdPayloads.requireText(payload, "status"));
      Directive directive = base.directives().get(id);
      if (directive == null) {
        return new HandlerOutcome.Rejected("决策不存在: " + id.value());
      }
      Optional<String> violation = transitionViolation(directive.status(), target);
      if (violation.isPresent()) {
        return new HandlerOutcome.Rejected(violation.get());
      }
      Map<DirectiveId, Directive> next = new LinkedHashMap<>(base.directives());
      next.put(id, directive.withStatus(target));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDirectives(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 目标值只允许两个终态；其余（含 {@code PLANNED}/{@code ISSUED}/非法串）一律以可读原因抛出。 */
  private static DirectiveStatus parseTarget(String text) {
    DirectiveStatus target;
    try {
      target = DirectiveStatus.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("目标状态非法（只允许 EXECUTED|CANCELLED）: " + text);
    }
    if (target != DirectiveStatus.EXECUTED && target != DirectiveStatus.CANCELLED) {
      throw new IllegalArgumentException("目标状态非法（只允许 EXECUTED|CANCELLED）: " + text);
    }
    return target;
  }

  /** 转移守卫：只放行 {@code ISSUED → EXECUTED/CANCELLED}，其余（含重复翻转）以可读原因拒绝。 */
  private static Optional<String> transitionViolation(
      DirectiveStatus current, DirectiveStatus target) {
    if (current != DirectiveStatus.ISSUED) {
      return Optional.of(
          "决策状态不可转移：当前 "
              + current
              + " ⇒ 目标 "
              + target
              + "（只允许 ISSUED → EXECUTED/CANCELLED；已终态不得改回，重复翻转即拒）");
    }
    return Optional.empty();
  }
}
