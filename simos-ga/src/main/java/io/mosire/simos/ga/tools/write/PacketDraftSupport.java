package io.mosire.simos.ga.tools.write;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.FormattedCall;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>决策包七件套的共用小件</b>（2026-10-14 本批新增）：包 id 派生、{@code callIndex} 追加位、{@code DRAFT} 守卫的**统一文案**、以及
 * {@code argsJson} 回解析。
 *
 * <p>★★ <b>为什么要有这一个类</b>（AGENTS 铁律 4 同族：第二份业务真相 = 漂移且无症状）：
 *
 * <ul>
 *   <li>包 id 恒为 {@code pkt-<决策人>-<tick>}，七个工具**必须**派生出同一个 id——各写一份的话，改口径时不会有任何症状，
 *       只会让某一族工具读/写到"另一个包"；
 *   <li>"非 DRAFT ⇒ 先 reopen 撤回"是本批的统一具名拒文案（该用哪条提示是**契约**，不是文案风格）：各写一份就会漂。
 * </ul>
 *
 * <p>★ <b>它不是第二份状态机</b>：状态判据仍然只有 {@link PacketStatus} 与 {@code ga.SubmitDecisionPacket} 的
 * DRAFT→PENDING 转换；本类只把"读出来之后怎么判"收在一处供各工具调用。★ 真正**不可回退**的那条硬防线（{@code decidedBy} 非空 ⇒ 不许回到 DRAFT）在
 * {@link ReopenPacketTool}，因为那一条只在撤回时才有意义。
 */
final class PacketDraftSupport {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private PacketDraftSupport() {}

  /** 本 tick 该决策人的包 id（{@code pkt-<proposerId>-<tick>}）——七件套的唯一拼写点。 */
  static String packetIdOf(DecisionMakerId proposerId, long tick) {
    return "pkt-" + proposerId.value() + "-" + tick;
  }

  /** 同 {@link #packetIdOf} 的已解析形态。 */
  static DecisionPacketId packetIdValueOf(DecisionMakerId proposerId, long tick) {
    return DecisionPacketId.parse(packetIdOf(proposerId, tick));
  }

  /** 下一条追加 call 的 {@code callIndex}（现有最大 + 1；空包 ⇒ 0）。 */
  static int nextCallIndex(DecisionPacket existing) {
    if (existing == null || existing.calls().isEmpty()) {
      return 0;
    }
    int max = -1;
    for (FormattedCall call : existing.calls()) {
      max = Math.max(max, call.callIndex());
    }
    return max + 1;
  }

  /**
   * 非 DRAFT 包的统一具名拒（{@code BAD_REQUEST}）；是 DRAFT（或包不存在）⇒ 空。
   *
   * <p>★ 文案统一写"先 {@link ReopenPacketTool#NAME} 撤回"：DM 看到的是**下一步该做什么**，不是一句"状态不对"。
   */
  static java.util.Optional<ToolResult> rejectIfNotDraft(DecisionPacket packet) {
    if (packet == null || packet.status() == PacketStatus.DRAFT) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        ToolResult.error(
            "BAD_REQUEST",
            "本 tick 决策包已提交/已裁决 ⇒ 先 "
                + ReopenPacketTool.NAME
                + " 撤回: "
                + packet.id().value()
                + " status="
                + packet.status()));
  }

  /** 包级视图的公共头（{@code packetId}/{@code status}/{@code callCount}）；各工具在自己的字段之前先放它。 */
  static Map<String, Object> packetViewOf(DecisionPacket packet) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", packet.id().value());
    view.put("status", packet.status().name());
    view.put("callCount", packet.calls().size());
    return view;
  }

  /**
   * {@code FormattedCall.argsJson} 回解析成 Map（{@code revise} 复用原 args 时要用它重跑校验）。
   *
   * <p>★ 形状解析失败 = 包里那条 call 不可用 ⇒ {@code IllegalArgumentException}（调用方折成 {@code
   * BAD_REQUEST}）；**不**静默退回空 Map——那会把"改一条"悄悄变成"改成一条无参调用"。
   */
  static Map<String, Object> parseArgsObject(String argsJson) {
    try {
      Map<String, Object> parsed =
          MAPPER.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    } catch (Exception e) {
      throw new IllegalArgumentException("决策包内的 argsJson 不是 JSON 对象: " + e.getMessage(), e);
    }
  }
}
