package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.time.OwnershipStakeBook;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.TransferAssetShare}（R4-B.3b）：GM/事件用的<b>实物资产份额拆分/转移</b>命令。
 *
 * <pre>{@code
 * {"share":"share-…","quantity":123,
 *  "toOwner":{"kind":"HOUSEHOLD","id":"hh-…"},"toOperator":{"kind":"ESTATE","id":"farm@-20_-81"},
 *  "kind":"TENANCY","reason":"…"}
 * }</pre>
 *
 * <p>★★ <b>语义（与 B.3 计划逐条对应）</b>：
 *
 * <ul>
 *   <li>{@code share} 必填（{@link AssetShareId#parse} 是 opaque 的，旧 {@code use-…} id 也按现值取行）；
 *   <li>{@code quantity} 缺省 = 原份额全部；{@code toOwner}/{@code toOperator}/{@code kind} 缺省 = 原值；
 *       <b>三者至少一项与现值不同</b>，否则拒绝（不允许造一条与旧行同形的新行）；
 *   <li>{@code quantity == 原 quantity} ⇒ <b>整条转移</b>：旧 id 移除，按新 {@code (industry, asset, owner,
 *       operator, kind)} 用 {@link OwnershipStake#idOf} 生成新 id；
 *   <li>{@code 0 < quantity < 原 quantity} ⇒ <b>拆分</b>：旧行减 {@code quantity}，新行 {@code quantity} 由
 *       {@link OwnershipStake#idOf} 生成新 id；
 *   <li>{@code quantity <= 0} 或 {@code > 原 quantity} ⇒ {@link HandlerOutcome.Rejected}（不产生半截
 *       revision）；
 *   <li>份额总量逐 {@code (industry, asset)} 不变；<b>不动</b>商品/货币/债务/劳动；新 owner/operator 不要求已有账户 （账户是 actor
 *       侧的事，本命令不越界代造）；
 *   <li>结果一律走 {@link EconomyChangeSet#between(EconomyData, EconomyData)}，由 {@link
 *       EconomyData#withOwnershipStakes} 的整体构造期守卫兜底（份额守恒是"删一行 + 加一行"的结构性事实）。
 * </ul>
 *
 * <p>★★ <b>新 id 的 sequence（确定性、防撞）</b>：由{@link OwnershipStakeBook#transfer} 统一生成 —— 从 0 起找第一个
 * 既不在同表、也不在本批已生成集合里的空闲序号；没有同形份额 ⇒ 0。同一基准状态的重放/分支因此得到同一 id。 ★ <b>旧档 opaque id 不再让命令失败</b>：写口不解析 id
 * 尾段，只按“同 key 撞车”检测（这一条与 R4-B.3b 的 “尾段解析失败 fail-closed”不同：旧实现是尾段最大 +
 * 1，新实现是首个空闲序号，仅在序列有空洞/旧档非数字尾段时会得到 不同的新 id —— 量的守恒与旧行内容不变）。
 *
 * <p>★ <b>不做的事</b>：不创建/关闭 unit，不改 relation/condition，不重分类家户，不碰当前未拥有的其他份额。资产被全部转走的 unit 保留在状态里、规模派生为
 * 0（{@code EconomyData} 不再要求非 EXITED unit 必须有份额）。
 *
 * <p>★★ <b>E5a：份额表写入委托 {@link OwnershipStakeBook#transfer}</b>（计划 §4 硬约束 2 的唯一写口）。本类只做
 * 命令语义的准入判断（份额存在/数量范围/至少一栏变化），真正的校验/新 id/守恒/活跃质押上界全在 Book 里一次完成： 任一失败 ⇒ 调用方的表一字不动（不再有"先改源行、后生成 id
 * 抛错"的半笔风险）。
 */
public final class EconomyTransferOwnershipStakeHandler implements CommandHandler {

  private static final String COMMAND = "economy.TransferAssetShare";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public String type() {
    return COMMAND;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = MAPPER.readTree(payloadJson);
      if (payload == null || !payload.isObject()) {
        throw new IllegalArgumentException(
            "economy.TransferAssetShare 载荷必须是 JSON 对象: " + payloadJson);
      }
      AssetShareId shareId = AssetShareId.parse(requireText(payload, "share"));
      OwnershipStake share = base.assetShares().get(shareId);
      if (share == null) {
        return new HandlerOutcome.Rejected("资产份额不存在: " + shareId.value());
      }
      long originalQuantity = share.quantity();
      long quantity =
          payload.hasNonNull("quantity")
              ? requireLong(payload.get("quantity"), "quantity")
              : originalQuantity;
      if (quantity <= 0L) {
        return new HandlerOutcome.Rejected(
            "quantity 必须 > 0（不接受零/负拆分；要清空请把剩余量整条转给新主体）: " + quantity);
      }
      if (quantity > originalQuantity) {
        return new HandlerOutcome.Rejected(
            "quantity 不得超过原份额 " + originalQuantity + ": " + quantity);
      }
      ActorRef toOwner = optionalActor(payload, "toOwner", share.owner());
      ActorRef toOperator = optionalActor(payload, "toOperator", share.operator());
      OwnershipStake.RightKind kind = optionalKind(payload, "kind", share.kind());
      if (toOwner.equals(share.owner())
          && toOperator.equals(share.operator())
          && kind == share.kind()) {
        return new HandlerOutcome.Rejected(
            "toOwner/toOperator/kind 至少一项必须与现值不同（否则不产生任何转移）: share=" + shareId.value());
      }
      Map<AssetShareId, OwnershipStake> updated = new LinkedHashMap<>(base.assetShares());
      // ★ 2026-10-09：质押表也要可写 —— ACTIVE 质押按比例跟到新份额（OwnershipStakeBook 就地写）。
      Map<PledgeId, Pledge> updatedPledges = new LinkedHashMap<>(base.pledges());
      try {
        OwnershipStakeBook.transfer(
            updated,
            base.industries(),
            updatedPledges,
            shareId,
            quantity,
            toOwner,
            toOperator,
            kind);
      } catch (IllegalArgumentException e) {
        // ★ 写口的全量校验失败 ⇒ 输入表未被改动，折成命令拒绝（计划 §4：不产生半截 revision）。
        return new HandlerOutcome.Rejected(e.getMessage());
      }
      return new HandlerOutcome.Applied(
          EconomyChangeSet.between(
              base, base.withOwnershipStakes(updated).withPledges(updatedPledges)));
    } catch (IllegalArgumentException | JsonProcessingException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 可选的 {@code {kind,id}} 主体：缺键 / JSON null ⇒ 原值；给了但形状不对 ⇒ 抛。 */
  private static ActorRef optionalActor(JsonNode payload, String field, ActorRef fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {kind,id} 对象: " + node);
    }
    return new ActorRef(ActorKind.parse(requireText(node, "kind")), requireText(node, "id"));
  }

  /** 可选的 {@code kind}：缺键 / JSON null ⇒ 原值；给了但不在词表 ⇒ 抛（列出合法值）。 */
  private static OwnershipStake.RightKind optionalKind(
      JsonNode payload, String field, OwnershipStake.RightKind fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    try {
      return OwnershipStake.RightKind.valueOf(node.asText());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 RightKind: "
              + node.asText()
              + "；合法值: "
              + java.util.Arrays.toString(OwnershipStake.RightKind.values()));
    }
  }

  /** 载荷里的必填文本（缺/空白/类型不对 ⇒ 抛，由 handle 折成 Rejected）。 */
  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 缺少非空文本字段: " + field);
    }
    return value.asText();
  }

  /** 载荷里的整数（非整型/超 long ⇒ 抛，由 handle 折成 Rejected）。 */
  private static long requireLong(JsonNode node, String field) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return node.asLong();
  }
}
