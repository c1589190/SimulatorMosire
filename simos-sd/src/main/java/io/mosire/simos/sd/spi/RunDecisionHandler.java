package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
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
import java.util.Set;

/**
 * {@code sd.RunDecision} 命令的处理器（T11C）：**「让某个决策人的 agent 跑一轮」= 一条真命令**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1"}
 * }</pre>
 *
 * <p>★ **它记的是"触发"这一事实，不是这一轮的产出**：命令处理是**纯函数**（{@code SimulationState → ChangeSet}），而"跑一轮 agent"要调真
 * LLM、还要经 {@code sd.IssueDirective} 落更多 revision——那是**命令之外**的动作（由 app 层的窄工具在**本命令落盘之后** 执行）。故本
 * handler 只写一条**感知层事实**：谁在哪个 tick 让哪个决策人跑了一轮。决策人的产出仍走 {@code sd.IssueDirective}/{@code
 * sd.SubmitVerdict}（铁律 2 无例外）。
 *
 * <p>★ **与 {@code sd.StartDecision} 的分工**（两者语义不同，不是同一件事的两种写法）：
 *
 * <ul>
 *   <li>{@link StartDecisionHandler}（{@code sd.StartDecision}）= **"开始一次判决"**：结果由 app 侧的 {@code
 *       DecisionAdjudicationService} 折成 {@code sd.SubmitVerdict}（裁决者冻结判决）；
 *   <li>本命令（{@code sd.RunDecision}）= **"让某决策人的 agent 跑一轮"**：结果由 app 侧的 {@code DecisionAgentRunner}
 *       折成决策人**自己**调工具（读世界 + {@code sd.IssueDirective}）。
 * </ul>
 *
 * <p>两者写在**同一个地址空间** {@code sd:decision.<dmId>}（前缀直接取自 {@link
 * StartDecisionHandler#ADDRESS_PREFIX}——同一处拼写点，避免两条事实在 AAR 里跑到两个地址去），只以 key 区分：{@code start} /
 * {@link #RUN_INFO_KEY}。
 *
 * <p>★ **不写 {@code Directive}**（与 {@code sd.StartDecision} 同一条理由，见 {@link StartDecisionHandler}
 * 的类注）：触发命令若在决策人**将要出令的那个 (决策人, tick)** 上留一条 {@code Directive}，它就会成为"第 1 版"、被决策人的真出令顶成 {@code
 * SUPERSEDED}——AAR 上凭空多一条决策人从没写过的版本。
 *
 * <p>★ **不校验 provider 绑定**：{@code DecisionMaker.providerId} 是**不透明的基础设施引用**，sd 不解释、不校验其存在性 （铁律 3
 * 的结构化，见 {@code DecisionMaker} 的类注）。绑没绑、解析不解析得到，是**使用时刻**（app 层）的事，且必须 fail-closed。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；载荷不是合法 JSON / 缺字段。
 */
public final class RunDecisionHandler implements CommandHandler {

  /** 触发记录在 sd INFO 覆盖层里的 key（与 {@link StartDecisionHandler#START_INFO_KEY} 同一地址、不同 key）。 */
  public static final String RUN_INFO_KEY = "run";

  @Override
  public String type() {
    return "sd.RunDecision";
  }

  /**
   * 载荷里那**唯一**一个字段（{@code decisionMakerId}）。
   *
   * <p>★ **公开的理由**：触发这条命令的 app 侧窄工具（{@code sd.RunDecision}）在**命令落盘之后**还要拿同一个 id 去跑那一轮
   * ——两处若各写一遍字段名，改名时一处漏改就会"命令认得、运行流认不得"（或反过来），而**没有任何症状**。故字段名的拼写点只有这里。
   *
   * @throws IllegalArgumentException 载荷不是合法 JSON / 缺 {@code decisionMakerId}（与 {@link #handle}
   *     同一条判定）
   */
  public static DecisionMakerId decisionMakerIdOf(String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    return DecisionMakerId.parse(
        SdPayloads.requireText(SdPayloads.parse(payloadJson), "decisionMakerId"));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      DecisionMakerId decisionMakerId = decisionMakerIdOf(payloadJson);

      if (!base.decisionMakers().containsKey(decisionMakerId)) {
        return new HandlerOutcome.Rejected("决策人不存在: " + decisionMakerId.value());
      }

      long tick = state.meta().timestamp().tick();
      RevisionId at = state.meta().ref().revision();
      String address = StartDecisionHandler.ADDRESS_PREFIX + decisionMakerId.value();
      Map<String, List<SdInfoEntry>> nextInfo = new LinkedHashMap<>(base.info());
      List<SdInfoEntry> entries = new ArrayList<>(nextInfo.getOrDefault(address, List.of()));
      // ★ 决策结果三件套（第 3 波第 1 步）：与 StartDecision 同形制（同址异 key），tags 挂**被触发的决策人**。
      SdInfoEntry entry =
          new SdInfoEntry(
              SdInfoIds.synthesize(address, entries.size()),
              tick,
              Set.of(decisionMakerId),
              Set.of(),
              RUN_INFO_KEY,
              String.valueOf(tick),
              Optional.empty(),
              at,
              Optional.empty(),
              Optional.empty());
      entries.add(entry);
      nextInfo.put(address, List.copyOf(entries));

      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withInfo(nextInfo)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
