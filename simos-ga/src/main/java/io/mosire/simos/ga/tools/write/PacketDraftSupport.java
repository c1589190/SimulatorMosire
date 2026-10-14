package io.mosire.simos.ga.tools.write;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Optional;

/**
 * ★★ <b>决策包七件套的共用小件</b>（2026-10-14 本批新增）：包 id 派生、{@code callIndex} 追加位、 {@code 已裁决} / {@code 非当前
 * tick} 两条具名拒的**统一文案**、以及 {@code argsJson} 回解析。
 *
 * <p>★★ <b>为什么要有这一个类</b>（AGENTS 铁律 4 同族：第二份业务真相 = 漂移且无症状）：
 *
 * <ul>
 *   <li>包 id 恒为 {@code pkt-<决策人>-<tick>}，七个工具**必须**派生出同一个 id——各写一份的话，改口径时不会有任何症状，
 *       只会让某一族工具读/写到"另一个包"；
 *   <li>该用哪条提示是**契约**，不是文案风格：各写一份就会漂。
 * </ul>
 *
 * <p>★★ <b>2026-10-15 语义变更（设计书 §3.9："PENDING 不是锁"）之后，本类收着三条判据、各只有一处实现</b>：
 *
 * <ol>
 *   <li>{@link #rejectIfDecided}（不变量 <b>I14</b>）——"已裁决 ⇒ 改不了"。★ 它取代了旧的 {@code rejectIfNotDraft}
 *       （那条口径是"只有 DRAFT 放行"；PENDING 一放开，若不再显式补上"已裁决 ⇒ 拒"，DM 就能把 GM 已批的包改了）。 ★ 判据是**合取**：{@code
 *       decidedBy} 非空 **或** 状态属四种终态（两者不一致 = 契约故障，也拒，fail-closed）；
 *   <li>{@link #rejectIfNotCurrentTick}（不变量 <b>I15</b>）——"只许动当前 tick 的包"：历史 tick 的包**只读**；
 *   <li>{@link #autoWithdrawNote}——PENDING 上被修改 ⇒ 包**以 DRAFT 写回**（隐式 reopen）并在视图里报一句注记。
 * </ol>
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
   * ★★ <b>不变量 I14 的唯一实现：已裁决的包 ⇒ 四条修改口（+ confirm/reopen）一律具名拒</b> （{@code BAD_REQUEST}）；未裁决 ⇒ 空。
   *
   * <p>★★ <b>为什么必须是它、且只写这一处</b>（设计书 §3.9.3 第二条红线）：旧实现挡住"已裁决"靠的是"**只有 DRAFT 才放行**" ——PENDING 一旦放开（用户
   * 2026-10-15：「提交多少轮都没问题」），那条顺带防线就没了。若四个工具各写一份形态不同的判据， 漏掉一个的症状是"DM 能把 GM
   * 已批的包改了"，而**没有任何一层会报错**（{@code ga.UpsertDecisionPacket} 本身没有状态机，整包覆盖）。
   *
   * <p>★ <b>判据是合取（{@code ||}），不是二选一</b>：{@code decidedBy} 非空 **或** 状态属四种终态。两者之一成立即拒 ⇒ 契约故障（例如
   * decidedBy 非空却写回 DRAFT）也**拒**，fail-closed。
   *
   * <p>★ 包不存在（{@code null}）⇒ 空：那种情况由各工具自己的"本 tick 还没有决策包 ⇒ 先 propose"拒因处理。
   */
  static Optional<ToolResult> rejectIfDecided(DecisionPacket packet) {
    if (packet == null) {
      return Optional.empty();
    }
    Optional<String> decidedBy = packet.decidedBy();
    boolean terminal = isDecidedStatus(packet.status());
    if (decidedBy.isEmpty() && !terminal) {
      return Optional.empty();
    }
    String verdict =
        decidedBy
            .map(value -> "decidedBy=" + value + " status=" + packet.status())
            .orElseGet(() -> "status=" + packet.status());
    return Optional.of(
        ToolResult.error(
            "BAD_REQUEST",
            "决策包已由 GM 裁决，不能再改（"
                + verdict
                + "）："
                + packet.id().value()
                + "；本 tick 的决策到此定稿，改动请留到下一个 tick 的包"));
  }

  /**
   * ★★ <b>不变量 I15 的唯一实现：只许动当前 tick 的包</b>（设计书 §3.9.3 第一条红线）。
   *
   * <p>★ 理由（设计书原文）：包 id 是 {@code pkt-<决策人>-<tick>}，按 tick 分的容器；若允许 {@code revise{tick: 359}} 在 tick
   * 360 执行，DM 就能**改写历史 tick 的决策** —— 那正是用户「做完决策这个 tick 就不能再提交命令了」要挡住的事。 <b>历史 tick 的包只读</b>（{@code
   * packet.my} / {@code gm.packet} / {@code gm.packets} 照常可读，本方法只管写口）。
   *
   * @param packetIdValue 目标包 id（只进文案，便于模型看清自己点的是哪个 tick 的包）
   * @param tick 载荷给出的 tick（缺省时由调用方填成"当前世界 tick"⇒ 必然相等）
   * @param currentTick 当前世界 tick（该分支 head 的 tick）
   */
  static Optional<ToolResult> rejectIfNotCurrentTick(
      String packetIdValue, long tick, long currentTick) {
    if (tick == currentTick) {
      return Optional.empty();
    }
    return Optional.of(
        ToolResult.error(
            "BAD_REQUEST",
            "只许动当前 tick 的决策包：请求 tick="
                + tick
                + " 而当前世界 tick="
                + currentTick
                + "，历史 tick 的包只读（"
                + packetIdValue
                + "）"));
  }

  /**
   * ★ <b>隐式 reopen 的注记</b>（设计书 §3.9.2）：PENDING 上被修改 ⇒ 包以 DRAFT 写回，视图里必须告诉 DM "发生了什么 + 下一步做什么"。
   *
   * <p>★ 为什么报出来：自动撤回**改变了包的状态**（GM 的 {@code pendingConfirmations} 里这个 DM 会重新出现）， 一句注记是 DM
   * 唯一能看见这件事的地方。★ 文案里的 {@code confirm} 工具名要求 DM"改完记得再提交"，否则它会以为 这一改已经算提交过了。
   *
   * @return 包原本是 PENDING（= 本次是隐式 reopen）⇒ 注记；本来就是 DRAFT ⇒ 空
   */
  static Optional<String> autoWithdrawNote(DecisionPacket packet) {
    if (packet == null || packet.status() != PacketStatus.PENDING) {
      return Optional.empty();
    }
    return Optional.of(
        "已自动撤回（PENDING → DRAFT）：本次改动已把包退回草稿，改完记得再 " + ConfirmPacketTool.NAME + " 提交");
  }

  /** 四种"已裁决"终态 = {@link PacketStatus} 里除 DRAFT/PENDING 之外的全部取值（{@link #rejectIfDecided} 的第二条判据）。 */
  private static boolean isDecidedStatus(PacketStatus status) {
    return status == PacketStatus.APPROVED
        || status == PacketStatus.REJECTED
        || status == PacketStatus.MERGED
        || status == PacketStatus.PARTIALLY_APPROVED;
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
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("决策包内的 argsJson 不是 JSON 对象: " + e.getMessage(), e);
    }
  }
}
