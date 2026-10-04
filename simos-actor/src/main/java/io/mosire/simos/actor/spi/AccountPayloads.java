package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * P1.2 actor 行政命令的载荷解析助手：{@code actor.TransferAccounts} / {@code actor.MoveAccount} 共用的 账户引用、正数量表与
 * owner 解析。形状是 actor 模块私有的事（Core 只转交 JSON 文本）。
 *
 * <p>★ <b>只判形状/类型/非零：</b>数值语义（源是否有账、可支配是否够、相加是否溢出）由 {@link
 * io.mosire.simos.actor.ops.AccountOperations} 判 —— 本层不重复实现，避免同一规则两处拼写。
 */
final class AccountPayloads {

  private AccountPayloads() {}

  /** 一个账户引用：{@code {"owner":{"kind","id"},"q":..,"r":..}}。 */
  record AccountRef(ActorRef owner, HexCoord at) {

    AccountRef {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(at, "at");
    }
  }

  /**
   * 解析一条账户引用（形状见 {@link AccountRef}）。
   *
   * @param node 承载该引用的 JSON 对象
   * @param field 字段名（错误消息点名用）
   */
  static AccountRef accountRef(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {owner{kind,id},q,r} 对象: " + node);
    }
    return new AccountRef(ownerObject(node.get("owner"), field + ".owner"), hex(node, field));
  }

  /** 解析独立 {@code owner} 对象：{@code {"kind":…,"id":…}}。 */
  static ActorRef owner(JsonNode payload, String field) {
    return ownerObject(payload.get("owner"), field);
  }

  /** 解析 {@code actor.TransferAccounts} 的正转移量表（缺省/JSON null ⇒ 空表；值为 0/负 ⇒ 抛）。 */
  static Map<CommodityId, Long> positiveCommodities(JsonNode payload, String field) {
    return positiveAmounts(payload, field, CommodityId::parse, "商品");
  }

  /** 解析 {@code actor.TransferAccounts} 的正货币转移量表（口径同 {@link #positiveCommodities}）。 */
  static Map<CurrencyId, Long> positiveMoney(JsonNode payload, String field) {
    return positiveAmounts(payload, field, CurrencyId::parse, "货币");
  }

  /** owner 对象的唯一解析点（词表在 {@link ActorKind#parse}）。 */
  private static ActorRef ownerObject(JsonNode owner, String field) {
    if (owner == null || !owner.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {kind,id} 对象");
    }
    JsonNode kind = owner.get("kind");
    JsonNode id = owner.get("id");
    if (kind == null || !kind.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + ".kind 必须是字符串: " + owner);
    }
    if (id == null || !id.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + ".id 必须是字符串: " + owner);
    }
    return ActorRef.parse(kind.asText(), id.asText());
  }

  /** 解析 {@code {q,r}}（不允许小数/字符串；缺字段或类型不符 ⇒ 抛）。 */
  private static HexCoord hex(JsonNode node, String field) {
    JsonNode q = node.get("q");
    JsonNode r = node.get("r");
    if (q == null
        || !q.isIntegralNumber()
        || !q.canConvertToInt()
        || r == null
        || !r.isIntegralNumber()
        || !r.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须有整数 q 与 r: " + node);
    }
    return new HexCoord(q.asInt(), r.asInt());
  }

  /**
   * 正数量表：缺键 / JSON {@code null} ⇒ 空表；出现但非对象、键不在词表、值非整数或 ≤ 0 ⇒ 抛。
   *
   * <p>★ 0 也拒绝（与 {@code actor.AdjustAccounts} 的"0 增量请删条目"同口径）：一条转移命令里写 0
   * 是"看起来在做一件事、实际什么都没做"，放入载荷只会让调用方对意图产生误判。
   */
  private static <A> Map<A, Long> positiveAmounts(
      JsonNode payload, String field, Function<String, A> idParser, String dimension) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Map.of();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {" + dimension + ":正整数} 对象: " + payload);
    }
    Map<A, Long> parsed = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      A id;
      long amount;
      try {
        id = idParser.apply(entry.getKey());
        amount = integral(entry.getValue(), field + "." + entry.getKey());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("字段 " + field + " 的键/值不合法: " + e.getMessage(), e);
      }
      if (amount <= 0L) {
        throw new IllegalArgumentException(
            dimension + " 转移量必须 > 0（0 无操作，请删该键）: " + field + "." + entry.getKey() + "=" + amount);
      }
      parsed.put(id, amount);
    }
    return Collections.unmodifiableMap(parsed);
  }

  /** 整数（含 long 范围）；非整数 / 可转换但超 long ⇒ 抛。 */
  private static long integral(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + value);
    }
    return value.asLong();
  }
}
