package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * actor 账户行政命令的载荷解析助手（P2-A §13.3 起账户主体只有家户）：{@code actor.TransferAccounts} / {@code
 * actor.AdjustAccounts} 共用的家户引用与正数量表。形状是 actor 模块私有的事（Core 只转交 JSON 文本）。
 *
 * <p>★★ <b>P2-A 的形状变化（如实记）</b>：改前账户引用是 {@code {"owner":{"kind","id"},"q":..,"r":..}} （{@code
 * ActorRef} + 格）；现在只有家户，格从 {@code Household.location} 派生 ⇒ 引用是 {@code {"household":"hh-…"}}。
 * 旧账户直接报废（§13.3）：本层**不**保留旧形状的兼容解析。
 *
 * <p>★ <b>只判形状/类型/非零：</b>数值语义（源是否有账、可支配是否够、相加是否溢出）由 {@link
 * io.mosire.simos.actor.ops.AccountOperations} 判 —— 本层不重复实现，避免同一规则两处拼写。
 */
final class AccountPayloads {

  private AccountPayloads() {}

  /** 一个家户账户引用：{@code {"household":"hh-…"}}。 */
  record AccountRef(HouseholdId household) {

    AccountRef {
      Objects.requireNonNull(household, "household");
    }
  }

  /**
   * 解析一条家户账户引用（形状见 {@link AccountRef}）。
   *
   * @param node 承载该引用的 JSON 对象
   * @param field 字段名（错误消息点名用）
   */
  static AccountRef accountRef(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {household:<id>} 对象: " + node);
    }
    JsonNode value = node.get("household");
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + ".household 必须是家户 id 字符串: " + node);
    }
    try {
      return new AccountRef(HouseholdId.parse(value.asText()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "字段 " + field + ".household 的家户 id 不合法: " + e.getMessage(), e);
    }
  }

  /** 解析独立 {@code household} 字段（字符串 id）。 */
  static HouseholdId household(JsonNode node, String field) {
    // ★ D5 修：必须按调用方给的字段名读；旧实现恒读 "household"，使 fromHousehold/toHousehold 永远解析成同一个键。
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是家户 id 字符串: " + node);
    }
    try {
      return HouseholdId.parse(value.asText());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("字段 " + field + " 的家户 id 不合法: " + e.getMessage(), e);
    }
  }

  /** 解析 {@code actor.TransferAccounts} 的正转移量表（缺省/JSON null ⇒ 空表；值为 0/负 ⇒ 抛）。 */
  static Map<CommodityId, Long> positiveCommodities(JsonNode payload, String field) {
    return positiveAmounts(payload, field, CommodityId::parse, "商品");
  }

  /** 解析 {@code actor.TransferAccounts} 的正货币转移量表（口径同 {@link #positiveCommodities}）。 */
  static Map<CurrencyId, Long> positiveMoney(JsonNode payload, String field) {
    return positiveAmounts(payload, field, CurrencyId::parse, "货币");
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
