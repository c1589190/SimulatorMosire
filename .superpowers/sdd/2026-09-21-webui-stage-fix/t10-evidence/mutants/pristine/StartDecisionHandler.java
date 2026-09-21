package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.StartDecision} 命令的处理器（T10，spec §四.5，D5 已裁）：**「开始决策」= 一条真命令**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1","note":"可选说明"}
 * }</pre>
 *
 * <p>★ **铁律 2 无例外**：用户经 GUI 点、GM Agent 经 GM 口发，最终都落成 {@code Command → ChangeSet → Revision} 路径上的 一条
 * revision（可回放、可回退分岔）。**不存在**"直接改世界但不落 revision"的入口（D5 否掉了"GUI 专用端点在事务外触发"）。
 *
 * <p>★ **它与 R4 的关系（本任务的关键设计决定）**：{@link IssueDirectiveHandler} 的 R4 硬不变量是「同一 {@code
 * (decisionMakerId, tick)} 至多一条 {@code Directive}」（命令期 + {@link SdState} 状态期两层）。若本命令也写一条 {@code
 * Directive}（哪怕是 {@code PLANNED}），就会**占掉该 tick 的 R4 名额** ⇒ 其后的 {@code sd.IssueDirective}（决策人出令） 必被
 * R4 拒。**故本命令不写 {@code Directive}**：它写的是"发起/授权"这一事实（INFO 覆盖层），不碰决策人的出令名额。
 *
 * <p>★ **落点（INFO 覆盖层）**：地址 {@code sd:decision.<dmId>}（同 {@code sd.IssueDirective} 的 {@code
 * sd:directive.<id>} 形制），key 固定 {@value #START_INFO_KEY}，{@code value} 记本命令所在 tick（**标量串**—— {@link
 * SdInfoEntry#value()} 是裸 {@code Object}，数值类型跨 JSON 往返会 Long↔Integer 漂移，故取串），{@code note}
 * 记可选说明。这是**感知层**（供 UI / AAR 展示）记录，不影响 `due` 计算（`due` 只扫 {@code Directive}）。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；载荷不是合法 JSON / 缺字段。
 */
public final class StartDecisionHandler implements CommandHandler {

  /** 发起记录在 sd INFO 覆盖层里的 key。 */
  public static final String START_INFO_KEY = "start";

  /** 发起记录地址前缀（{@code sd:decision.<dmId>}，同 {@code sd:directive.<id>} 形制）。 */
  public static final String ADDRESS_PREFIX = "sd:decision.";

  @Override
  public String type() {
    return "sd.StartDecision";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId decisionMakerId =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "decisionMakerId"));
      Optional<String> note = SdPayloads.optionalText(payload, "note");

      if (!base.decisionMakers().containsKey(decisionMakerId)) {
        return new HandlerOutcome.Rejected("决策人不存在: " + decisionMakerId.value());
      }

      long tick = state.meta().timestamp().tick();
      RevisionId at = state.meta().ref().revision();
      String address = ADDRESS_PREFIX + decisionMakerId.value();
      SdInfoEntry entry =
          new SdInfoEntry(START_INFO_KEY, String.valueOf(tick), note, at, Optional.empty());
      Map<String, List<SdInfoEntry>> nextInfo = new LinkedHashMap<>(base.info());
      List<SdInfoEntry> entries = new ArrayList<>(nextInfo.getOrDefault(address, List.of()));
      entries.add(entry);
      nextInfo.put(address, List.copyOf(entries));

      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withInfo(nextInfo)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
