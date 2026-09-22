package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sd.SetDecisionMakerAccess} 命令的处理器（spec §4.2；用户 2026-09-22 裁定③）：GM 为某个决策人**配权**。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm1","allowedTools":["sd.IssueDirective"],
 *  "accessLimit":{"map":["Map1/region/701"]},"redactedFields":["position"],
 *  "adjudicationDisclosure":"PERCEPTION_ONLY"}
 * }</pre>
 *
 * <p>★★ **本命令取代 {@code sd.SetViewScope}**（旧那套已被删除）：旧命令配的是 GM **绝对指定**的可见集合， 本命令配的是**额外限制**——与 app
 * 层范围函数现算的结果做**交集**（{@code ResourceScopeMap#narrowTo}）⇒ **GM 只能额外收紧、不能放大**（spec §4.2）。配进来的是前缀而不是实体：
 * 国家决策人的范围按**区域**组织（真档 59223 hex，逐格前缀不可行）。
 *
 * <p>★ **配权本身是数据**（N11）：{@link AccessLimit} 写进 {@link DecisionMaker}、随 revision 落盘 ⇒ 可回放、可回退分岔（铁律
 * 2）。
 *
 * <p>★ **GM 专用**（N11）：工具侧标 {@code sensitive=true} 走审批（D6）；本处理器只做领域校验（dm 存在、载荷合法）。
 *
 * <p>★★ **四个字段各自"缺省 = 不改动、显式给 = 整份替换"**（含空对象/空数组）：配权命令一次只改一件事是常态， 若"缺省"被读成"清空"，一条只改 {@code
 * allowedTools} 的命令会把 GM 刚配好的资源限制**静默抹掉**。反过来，"缺省 = 保持"若无"清空"的写法，GM 就永远退不回"无额外限制"——故 {@code
 * "accessLimit":{}} 是**有效载荷**（= 清空）， 与"键缺席"（= 不动）**必须**分得开。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；载荷不是合法 {@link AccessLimit}；{@code allowedTools} 含**通用写**（N9）。
 */
public final class SetDecisionMakerAccessHandler implements CommandHandler {

  /**
   * ★ **必须是字面量**（不许提成常量）：app 侧两条**派生式同源判据**（catalog ↔ 全部 {@code *Handler.java} 的 {@code
   * type()}；窄写工具 ↔ GM 桶）都按**源码里的字符串字面量**扫描——写成常量引用，扫描器当场判"抽不到"并**红**（本任务实测踩过）。 那两条判据正是把 handler /
   * 工具 / catalog 三处名字钉在一起的东西，比"提到一个常量"更靠得住（常量管不到漏接）。
   */
  @Override
  public String type() {
    return "sd.SetDecisionMakerAccess";
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
        return new HandlerOutcome.Rejected("决策人不存在: " + id.value());
      }

      Optional<Set<String>> allowedTools =
          SdPayloads.optionalTextSetIfPresent(payload, "allowedTools");
      if (allowedTools.isPresent()
          && allowedTools.get().contains(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {
        return new HandlerOutcome.Rejected(
            "allowedTools 不得含通用写 " + SdCommandNames.SIMOS_COMMAND_SUBMIT + "（N9：决策 Agent 只用窄工具）");
      }

      AccessLimit updated = SdPayloads.accessLimitOrKeep(existing.accessLimit(), payload);
      DecisionMaker next =
          new DecisionMaker(
              existing.id(),
              existing.affiliation(),
              allowedTools.orElse(existing.allowedTools()),
              updated,
              existing.decisionCadenceTicks(),
              // ★ 配权只换白名单与 accessLimit：必须带回既有 providerId，否则静默丢绑定（M11 变异靶子 m2）。
              existing.providerId());
      Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>(base.decisionMakers());
      makers.put(id, next);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(makers)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
