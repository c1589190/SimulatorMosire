package io.mosire.simos.app.tools.write;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * 决策人**外交写入**的署名校验（与 {@code IssueDirectiveTool} 的 {@code signatureViolation} 同口径：身份只从 {@link
 * ToolContext#identity()} 取，绝不采信载荷自报的"我是谁"）。
 *
 * <p>规则（D5）：调用者必须是本世界已知的 {@link DecisionMaker}，且归属是 {@link Affiliation.Nation}——
 *
 * <ul>
 *   <li>写关系边（{@code sd.SetDiplomaticRelation}）：载荷 {@code from} 必须等于调用者 Nation（不许以别国名义立边）；
 *   <li>记事件（{@code sd.RecordDiplomaticEvent}）：{@code participants} 必须包含调用者 Nation（不许替别国记名）。
 * </ul>
 *
 * <p>★ <b>为什么住在 app 工具层、不搬进命令 handler</b>：命令 handler 结构性看不见调用者（{@code CommandHandler} 只有 {@code
 * state + payloadJson}），身份只在 {@link ToolContext} 上——与 {@code DecisionSignature} 是同一分工。
 *
 * <p>★ <b>状态在哪个 revision 上核对</b>：优先用工具载荷里的 {@code expectedRevision}（= 这次写入所基于的基态，与基类 {@code submit}
 * 的信封一致）；不给则取分支 head。读状态失败（分支不存在等）<b>不返回空</b>——那是"核不了"，按 fail-closed 拒绝，绝不放行一条无法核对署名的写入。
 *
 * <p>★ <b>载荷 JSON 解析失败/形状不对</b>返回空（不在这里重复报形状错误）：那种载荷到不了落盘——命令 handler 会拒；此处只回答 "署名是否冒名"这一件事。
 */
final class DiplomaticWriteSignature {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private DiplomaticWriteSignature() {}

  /**
   * @param context 本次调用上下文（身份 + 参数）
   * @param query 只读入口（按 {@code branch}/{@code expectedRevision} 取世界状态核对归属）
   * @param participantsMode {@code true} = 事件记录（要求 participants 含调用者 Nation）；{@code false} = 关系边（要求
   *     from = 调用者 Nation）
   * @return 拒绝理由；不构成冒名 ⇒ 空
   */
  static Optional<String> violation(
      ToolContext context, QueryService query, boolean participantsMode) {
    Optional<String> decisionMakerId = DecisionCallerFactory.decisionMakerIdOf(context.identity());
    if (decisionMakerId.isEmpty()) {
      return Optional.of("调用者不是决策人（身份 " + context.identity().instanceId() + "），外交写入只暴露给决策人");
    }
    SimulationState state;
    try {
      BranchId branch =
          new BranchId(
              ToolSupport.optionalText(context.arguments(), "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevision = ToolSupport.optionalLong(context.arguments(), "expectedRevision");
      state =
          query.stateAt(
              expectedRevision == null
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
    } catch (RuntimeException e) {
      return Optional.of(
          "无法读取世界状态以核对调用者 Nation 归属（branch="
              + context.arguments().get("branch")
              + "，expectedRevision="
              + context.arguments().get("expectedRevision")
              + "）："
              + e.getMessage());
    }
    DecisionMakerId id;
    try {
      id = new DecisionMakerId(decisionMakerId.get());
    } catch (IllegalArgumentException e) {
      return Optional.of("调用者身份里的决策人 id 非法: " + decisionMakerId.get());
    }
    DecisionMaker maker = ToolSupport.sdState(state).decisionMakers().get(id);
    if (maker == null) {
      return Optional.of("调用者身份不是本世界已知的决策人: " + decisionMakerId.get());
    }
    if (!(maker.affiliation() instanceof Affiliation.Nation nation)) {
      return Optional.of(
          "外交写入只对 Nation 归属的决策人开放（调用者 "
              + decisionMakerId.get()
              + " 归属: "
              + maker.affiliation()
              + "）");
    }
    String callerNation = nation.nationId().value();
    JsonNode payload;
    try {
      payload = MAPPER.readTree(ToolSupport.optionalText(context.arguments(), "payloadJson", "{}"));
    } catch (JsonProcessingException e) {
      return Optional.empty(); // 形状坏 ⇒ 交给命令 handler 的既有坏输入路径
    }
    if (payload == null || !payload.isObject()) {
      return Optional.empty();
    }
    if (participantsMode) {
      JsonNode participants = payload.get("participants");
      if (participants == null || !participants.isArray()) {
        return Optional.empty();
      }
      for (JsonNode element : participants) {
        if (!element.isTextual() || element.asText().isBlank()) {
          // 形状坏（非文本/空白元素）⇒ 交给命令 handler 的既有坏输入路径；本层只回答冒名与否。
          return Optional.empty();
        }
        if (callerNation.equals(element.asText())) {
          return Optional.empty();
        }
      }
      return Optional.of("外交事件的 participants 必须包含调用者 Nation（调用者=" + callerNation + "），不许替别国记名");
    }
    JsonNode from = payload.get("from");
    if (from == null || !from.isTextual() || from.asText().isBlank()) {
      return Optional.empty();
    }
    if (callerNation.equals(from.asText())) {
      return Optional.empty();
    }
    return Optional.of(
        "外交关系的 from 必须是调用者 Nation（from=" + from.asText() + "，调用者=" + callerNation + "），不许以别国名义写入");
  }
}
