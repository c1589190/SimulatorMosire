package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.model.AccessLimit;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code ga.SetDecisionMakerPeer} 命令的处理器（2026-10-14 决策人互致消息，设计书 §4.1）：GM 一次授权 /
 * 一次关闸**两个**决策人的互通。
 *
 * <pre>{@code
 * {"dmA":"dm-fra","dmB":"dm-ger","allowed":true}
 * }</pre>
 *
 * <p>★★ <b>为什么必须是一条命令写两条记录</b>（本命令存在的唯一理由）：用户裁定**双向**。若走既有的 {@code ga.SetDecisionMakerAccess}
 * 逐条设，GM 要点两次，**两次之间是"半开"态**（A 能写 B、B 回不了）—— 那是**可达且无告警**的错误状态。 本命令把两条记录放进**同一个 {@link
 * GaChangeSet}** ⇒ **一条 revision**，半开**不可表达**（I3）。
 *
 * <p>★ <b>底层机制仍是 {@code accessLimit}</b>（零新存储 / 零新可见性 / 零新切片 / 零新限流）：授权 = 在两人的 {@code
 * accessLimit.prefixesByNamespace.peer} 里各写入**对方**的 id；关闸 = 各删去对方。 工具侧按**参数**断言 {@code
 * peer:<收件人>}（{@link ToolResources#resourcePeer}）⇒ 未授权 = 具名拒。
 *
 * <p>★ <b>关闸到空 ⇒ 整个 {@code peer} 键删掉</b>（而不是留一个空集）：空集在 {@code ResourceScope} 里是"显式够不着" （{@code
 * none()}），与"不表态"方向相反；这里两者对 {@code peer} 域**语义相同**（自指域由基础可达面另给）， 所以留空集只会让数据多一种形态。删键 = 回到"无额外限制"。
 *
 * <p>★★ <b>自指域不在本命令的射程内</b>：{@code peer:<自己>} 是决策人的**结构性**可达面（连 {@code inbox} 都要它， 见 {@code
 * DecisionCallerFactory}），**不参与配权**——授权表里只有"对方"。
 *
 * <p>★★ <b>授权同时还把两条消息工具并进双方的 {@code allowedTools}</b>（★ 这是本命令的第二件事，实测逼出来的）： {@code
 * DecisionCallerFactory.whitelistFor(dm)} 的语义是「<b>非空</b> allowedTools ⇒ 全局白名单 ∩ 它」， 而 {@code
 * simos.ga.message.send/inbox} 是**新工具** ⇒ 一个 {@code allowedTools} 非空、但没列到这两条的决策人， 即使被授权也只会拿到 {@code
 * PERMISSION_DENIED 工具 … 不在 allowed-tools 白名单内}——那是"授权了却用不了"的 假象。用户原话是「**只有 GM
 * 授权…这个决策人才能使用新的通讯工具**」⇒ 授权这一下就必须让它**能用**。
 *
 * <p>★ <b>但空 {@code allowedTools} 一个字都不碰</b>：空集在 {@code whitelistFor} 里的语义是「**沿用全局白名单**」
 * （对该决策人**更宽**）⇒ 往里填名字会把它**收窄**成"只有这两条"。★ 关闸（{@code allowed=false}）**也不动** {@code
 * allowedTools}：那会连带废掉它与**别的**对端的授权（前缀表才是真正的闸门）。
 *
 * <p>★ 拒绝：{@code dmA}/{@code dmB} 缺失或同一人；任一决策人不存在；{@code allowed} 不是布尔。
 */
public final class SetDecisionMakerPeerHandler implements CommandHandler {

  /**
   * 授权时要一并放进{@code allowedTools} 的**消息工具名**（宿主注入 —— 唯一拼写点是两个工具类自己的 {@code NAME} 常量；机制层不抄字面量，见
   * {@code DecisionToolNames} 的同款理由）。
   */
  private final Set<String> messageToolNames;

  /**
   * @param messageToolNames 两条消息工具的名字（非空；由组合根从工具常量取）
   */
  public SetDecisionMakerPeerHandler(Set<String> messageToolNames) {
    Set<String> names = Objects.requireNonNull(messageToolNames, "messageToolNames");
    if (names.isEmpty()) {
      throw new IllegalArgumentException("messageToolNames 不得为空");
    }
    this.messageToolNames = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(names));
  }

  /**
   * ★ **必须是字面量**（不许提成常量，理由与 {@code SetDecisionMakerAccessHandler#type} 逐字相同）：app 侧两条
   * **派生式同源判据**（catalog ↔ 全部 {@code *Handler.java} 的 {@code type()}；窄写工具 ↔ GM 桶）都按**源码里的
   * 字符串字面量**扫描——写成常量引用，扫描器当场判"抽不到"并红（那是把 handler / 工具 / catalog 三处名字钉在一起的护栏， 比"提到一个常量"更靠得住）。
   */
  @Override
  public String type() {
    return "ga.SetDecisionMakerPeer";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String aForLog = null;
    try {
      JsonNode payload = GaPayloads.parse(payloadJson);
      DecisionMakerId dmA = DecisionMakerId.parse(GaPayloads.requireText(payload, "dmA"));
      aForLog = dmA.value();
      DecisionMakerId dmB = DecisionMakerId.parse(GaPayloads.requireText(payload, "dmB"));
      if (dmA.equals(dmB)) {
        return rejected("dmA 与 dmB 不得是同一个决策人: " + dmA.value(), "dmA", dmA.value());
      }
      boolean allowed = requireBoolean(payload, "allowed");

      DecisionMaker left = base.decisionMakers().get(dmA);
      if (left == null) {
        return rejected("决策人不存在: " + dmA.value(), "dmA", dmA.value());
      }
      DecisionMaker right = base.decisionMakers().get(dmB);
      if (right == null) {
        return rejected("决策人不存在: " + dmB.value(), "dmB", dmB.value());
      }

      // ★★ 两条记录进**同一份** map ⇒ 同一份 GaChangeSet ⇒ 一条 revision（I3：半开不可表达）。
      Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>(base.decisionMakers());
      DecisionMaker nextLeft = withPeer(left, dmB.value(), allowed);
      DecisionMaker nextRight = withPeer(right, dmA.value(), allowed);
      makers.put(dmA, nextLeft);
      makers.put(dmB, nextRight);

      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_SET_DECISION_MAKER_PEER_APPLIED",
                  GaLogSource.GA_DECISION,
                  "dmA",
                  dmA.value(),
                  "dmB",
                  dmB.value(),
                  "allowed",
                  allowed,
                  "peerCountA",
                  peerIdsOf(nextLeft),
                  "peerCountB",
                  peerIdsOf(nextRight),
                  "decisionMakers",
                  makers.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withDecisionMakers(makers)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "dmA", aForLog);
    }
  }

  /** 授权 = 加上对方（并把两条消息工具并进 allowedTools）；关闸 = 删去对方（删到空 ⇒ 整个 {@code peer} 键消失）。 */
  private DecisionMaker withPeer(DecisionMaker dm, String peerId, boolean allowed) {
    Map<String, Set<String>> prefixes = new LinkedHashMap<>(dm.accessLimit().prefixesByNamespace());
    Set<String> peers =
        new LinkedHashSet<>(prefixes.getOrDefault(ToolResources.PEER_NAMESPACE, Set.of()));
    if (allowed) {
      peers.add(peerId);
    } else {
      peers.remove(peerId);
    }
    if (peers.isEmpty()) {
      prefixes.remove(ToolResources.PEER_NAMESPACE);
    } else {
      prefixes.put(ToolResources.PEER_NAMESPACE, peers);
    }
    // ★★ 授权同时让这两条工具**可用**（见类注）：只在 allowedTools **非空**时并进去——空集 = "沿用全局白名单"，
    //   填名字反而是收窄。关闸不动它（前缀表才是闸门；动它会连带废掉与别的对端的授权）。
    Set<String> tools = new LinkedHashSet<>(dm.allowedTools());
    if (allowed && !tools.isEmpty()) {
      tools.addAll(messageToolNames);
    }
    AccessLimit updated =
        new AccessLimit(
            prefixes, dm.accessLimit().redactedFields(), dm.accessLimit().adjudicationDisclosure());
    // ★ 必须原样带回 providerId 与 conversationGeneration：漏一个 = 配一次权就静默丢 provider 绑定 /
    //   把会话退回第 0 代（DecisionMaker 的类注把这两条写成"重建路径必须带过来"的硬要求）。
    return new DecisionMaker(
        dm.id(),
        tools,
        updated,
        dm.decisionCadenceTicks(),
        dm.providerId(),
        dm.conversationGeneration());
  }

  private static int peerIdsOf(DecisionMaker dm) {
    return dm.accessLimit()
        .prefixesByNamespace()
        .getOrDefault(ToolResources.PEER_NAMESPACE, Set.of())
        .size();
  }

  /**
   * {@code allowed} 必填布尔。★ {@code GaPayloads} 没有 {@code requireBoolean} ⇒ 在这里判：**非布尔一律拒** （不把
   * "true"/1 这类宽松形态读成真——授权命令的方向错了是权限事故，不做隐式转换）。
   */
  private static boolean requireBoolean(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(field + " 必填且必须是布尔: " + value);
    }
    return value.asBoolean();
  }

  /**
   * 具名拒绝的唯一发射点（被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id。理由先过 {@link
   * GaPayloads#logReason}，载荷原文不进日志。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GaPayloads.logRejected(
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "SD_SET_DECISION_MAKER_PEER_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
