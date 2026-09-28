package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetShare;
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
 *       operator, kind)} 用 {@link AssetShare#idOf} 生成新 id；
 *   <li>{@code 0 < quantity < 原 quantity} ⇒ <b>拆分</b>：旧行减 {@code quantity}，新行 {@code quantity} 由
 *       {@link AssetShare#idOf} 生成新 id；
 *   <li>{@code quantity <= 0} 或 {@code > 原 quantity} ⇒ {@link HandlerOutcome.Rejected}（不产生半截
 *       revision）；
 *   <li>份额总量逐 {@code (industry, asset)} 不变；<b>不动</b>商品/货币/债务/劳动；新 owner/operator 不要求已有账户 （账户是 actor
 *       侧的事，本命令不越界代造）；
 *   <li>结果一律走 {@link EconomyChangeSet#between(EconomyData, EconomyData)}，由 {@link
 *       EconomyData#withAssetShares} 的整体构造期守卫兜底（份额守恒是"删一行 + 加一行"的结构性事实）。
 * </ul>
 *
 * <p>★★ <b>新 id 的 sequence（确定性、防撞）</b>：在<b>基准状态</b>里，取同 {@code (industry, asset, owner, operator,
 * kind)} 的全部现有份额，解析它们 id 的<b>尾段</b>序列号，取最大值 + 1；没有任何同形份额 ⇒ sequence = 0。 同一基准状态的重放/分支因此得到同一 id。★
 * <b>尾段解析失败 fail-closed</b>：{@code AssetShareId.parse} 是 opaque 的（旧档 id 不是本工厂所造），若同形份额里出现非 {@code
 * <数字>} 的尾段，命令拒绝而不是猜一个序号（猜错会撞 id 或覆盖既有份额）。
 *
 * <p>★ <b>不做的事</b>：不创建/关闭 unit，不改 relation/condition，不重分类家户，不碰当前未拥有的其他份额。资产被全部转走的 unit 保留在状态里、规模派生为
 * 0（{@code EconomyData} 不再要求非 EXITED unit 必须有份额）。
 */
public final class EconomyTransferAssetShareHandler implements CommandHandler {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public String type() {
    return "economy.TransferAssetShare";
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
      AssetShare share = base.assetShares().get(shareId);
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
      AssetShare.RightKind kind = optionalKind(payload, "kind", share.kind());
      if (toOwner.equals(share.owner())
          && toOperator.equals(share.operator())
          && kind == share.kind()) {
        return new HandlerOutcome.Rejected(
            "toOwner/toOperator/kind 至少一项必须与现值不同（否则不产生任何转移）: share=" + shareId.value());
      }
      AssetShareId newId =
          nextShareId(
              base.assetShares(), share.industry(), share.asset(), toOwner, toOperator, kind);
      Map<AssetShareId, AssetShare> updated = new LinkedHashMap<>(base.assetShares());
      if (quantity == originalQuantity) {
        updated.remove(shareId); // 整条转移：旧 id 不再存在
      } else {
        updated.put(
            shareId,
            new AssetShare(
                shareId,
                share.industry(),
                share.asset(),
                share.owner(),
                share.operator(),
                originalQuantity - quantity,
                share.kind()));
      }
      updated.put(
          newId,
          new AssetShare(
              newId, share.industry(), share.asset(), toOwner, toOperator, quantity, kind));
      return new HandlerOutcome.Applied(
          EconomyChangeSet.between(base, base.withAssetShares(updated)));
    } catch (IllegalArgumentException | JsonProcessingException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * 新份额 id：同形现有份额的最大 sequence + 1；没有同形份额 ⇒ 0。 ★ 尾段解析失败/溢出/撞 id 一律抛（由 {@code handle} 折成 {@link
   * HandlerOutcome.Rejected}），不猜序号。
   */
  private static AssetShareId nextShareId(
      Map<AssetShareId, AssetShare> shares,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetShare.RightKind kind) {
    long max = -1L;
    for (AssetShare share : shares.values()) {
      if (!share.industry().equals(industry)
          || share.asset() != asset
          || !share.owner().equals(owner)
          || !share.operator().equals(operator)
          || share.kind() != kind) {
        continue;
      }
      max = Math.max(max, sequenceOf(share.id()));
    }
    if (max == Long.MAX_VALUE) {
      throw new IllegalArgumentException(
          "无法为资产份额分配新 sequence：现有最大序号已达 Long.MAX_VALUE（fail-closed）");
    }
    AssetShareId candidate = AssetShare.idOf(industry, asset, owner, operator, kind, max + 1L);
    if (shares.containsKey(candidate)) {
      throw new IllegalArgumentException("资产份额 id 冲突（新 id 已存在，拒绝覆盖）: " + candidate.value());
    }
    return candidate;
  }

  /** 解析 {@link AssetShare#idOf} 约定 id 的尾段 sequence；非纯数字/超长 ⇒ 抛（fail-closed，不猜）。 */
  private static long sequenceOf(AssetShareId id) {
    String value = id.value();
    int dash = value.lastIndexOf('-');
    if (dash < 0 || dash == value.length() - 1) {
      throw new IllegalArgumentException("资产份额 id 没有可解析的 sequence 尾段（fail-closed，不猜序号）: " + value);
    }
    String tail = value.substring(dash + 1);
    for (int i = 0; i < tail.length(); i++) {
      char c = tail.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException("资产份额 id 的尾段不是数字（fail-closed，不猜序号）: " + value);
      }
    }
    try {
      return Long.parseLong(tail);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "资产份额 id 的 sequence 尾段超出 long 范围（fail-closed，不猜序号）: " + value);
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
  private static AssetShare.RightKind optionalKind(
      JsonNode payload, String field, AssetShare.RightKind fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    try {
      return AssetShare.RightKind.valueOf(node.asText());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 RightKind: "
              + node.asText()
              + "；合法值: "
              + java.util.Arrays.toString(AssetShare.RightKind.values()));
    }
  }

  /** 载荷里的必填文本（缺/空白/类型不对 ⇒ 抛，由 handle 折成 Rejected）。 */
  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("economy.TransferAssetShare 缺少非空文本字段: " + field);
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
