package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyPayloadSupport.commodityMap;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalText;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireText;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** 市场价表与 U20 工资锚的机械解析 helper（P0.5-C2 / P4c3）。 */
final class EconomyMarketPayloads {

  /**
   * ★★★ <b>D-032 P4b1：顶层 {@code wageAnchors} 的形状解析</b>（P4 §6.1/§6.4）。
   *
   * <pre>
   * "wageAnchors": [
   *   {"marketZone":"zone-0_0","industry":"farm","role":"hired-hand",
   *    "currency":"silver","wagePerLaborMilli":1000,
   *    "shortageStreak":0,"satisfiedStreak":0,"lastAdjustedTick":-1,
   *    "lastObservedMatchedLaborMilli":0,"lastObservedTargetLaborMilli":0,
   *    "lastAdjustmentDelta":0}
   * ]
   * </pre>
   *
   * <p>★ 空数组 / 缺键 = 空表。重复三元组、未知 zone/industry/role、非 MONEY_WAGE、 负工资、负 streak/观测值、{@code
   * lastAdjustedTick < -1} 都 fail-closed；currency 省略时取该区法定币， 给了就必须一致。历史字段缺省 = 0（{@code
   * lastAdjustedTick} 缺省 = -1 = 从未改价）。
   *
   * <p>★★ <b>P5b2：派生市场世界的 zone/法定币来源</b>：若 {@code marketZone} 不在持久区表 {@code
   * knownMarketZones}，但命中同一份 seed 载荷已解析的派生市场表 {@code seededMarkets}（{@code MarketTable.id ==
   * marketZone}），则取该表的 {@code numeraire} 作为法定币。两条来源都不命中 ⇒ 仍然 {@code
   * wage-anchor-market-zone-missing}（不猜 single-region、不兜底任意默认）。
   */
  static Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors(
      JsonNode payload,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      Map<MarketZoneId, MarketZone> knownMarketZones,
      Map<MarketZoneId, MarketTable> seededMarkets) {
    Map<WageAnchorKey, ProductionWageAnchor> anchors = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "wageAnchors")) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("wageAnchors 的元素必须是对象: " + node);
      }
      MarketZoneId marketZone = MarketZoneId.parse(requireText(node, "marketZone"));
      // ★★ P5b2：zone/法定币的合法来源 = 已有持久区表 **或** 同一份 seed 载荷里已解析的派生市场表。
      //   派生世界（SmallWorld：marketZones=[]、一张 single-region 价表）没有持久区记录，但派生表自带
      //   id/numeraire，就是该格的"市场区 + 法定币"。显式未知 zone 仍然 fail-closed（不兜底 single-region）。
      MarketZone zone = knownMarketZones.get(marketZone);
      CurrencyId legalTender;
      if (zone != null) {
        legalTender = zone.legalTender();
      } else {
        MarketTable seeded = seededMarkets == null ? null : seededMarkets.get(marketZone);
        if (seeded == null) {
          throw new IllegalArgumentException(
              "wage-anchor-market-zone-missing:" + marketZone.value());
        }
        legalTender = seeded.numeraire();
      }
      IndustryId industry = IndustryId.parse(requireText(node, "industry"));
      IndustryDefinitionView definition =
          definitions
              .industry(industry)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "wage-anchor-industry-unknown:" + industry.value()));
      ProductionRoleId role = ProductionRoleId.parse(requireText(node, "role"));
      ProductionRoleDefinition roleDefinition = null;
      for (ProductionRoleDefinition candidate : definition.roles()) {
        if (candidate.id().equals(role)) {
          roleDefinition = candidate;
          break;
        }
      }
      if (roleDefinition == null) {
        throw new IllegalArgumentException("wage-anchor-role-unknown:" + role.value());
      }
      if (!(roleDefinition.payment() instanceof RolePayment.MoneyWage)) {
        throw new IllegalArgumentException("wage-role-not-money:" + role.value());
      }
      CurrencyId currency =
          optionalText(node, "currency").map(CurrencyId::parse).orElse(legalTender);
      if (!currency.equals(legalTender)) {
        throw new IllegalArgumentException(
            "wage-anchor-currency-mismatch:"
                + marketZone.value()
                + ":"
                + industry.value()
                + ":"
                + role.value());
      }
      long wage = requireLong(node, "wagePerLaborMilli");
      if (wage < 0L) {
        throw new IllegalArgumentException("wage-anchor-quote-negative:" + role.value());
      }
      long shortageStreak = optionalLong(node, "shortageStreak", 0L);
      long satisfiedStreak = optionalLong(node, "satisfiedStreak", 0L);
      long lastAdjustedTick = optionalLong(node, "lastAdjustedTick", -1L);
      long observedMatched = optionalLong(node, "lastObservedMatchedLaborMilli", 0L);
      long observedTarget = optionalLong(node, "lastObservedTargetLaborMilli", 0L);
      long adjustmentDelta = optionalLong(node, "lastAdjustmentDelta", 0L);
      WageAnchorKey key = new WageAnchorKey(marketZone, industry, role);
      if (anchors.putIfAbsent(
              key,
              new ProductionWageAnchor(
                  currency,
                  wage,
                  shortageStreak,
                  satisfiedStreak,
                  lastAdjustedTick,
                  observedMatched,
                  observedTarget,
                  adjustmentDelta))
          != null) {
        throw new IllegalArgumentException(
            "wage-anchor-duplicate:"
                + marketZone.value()
                + ":"
                + industry.value()
                + ":"
                + role.value());
      }
    }
    return anchors;
  }

  /**
   * ★★ <b>顶层 {@code markets}：市场 id → 价表</b>（★★ W1：**每区一张**；可选键，见类注的第五处形状变化）。
   *
   * <pre>
   * "markets":{"single-region":{"numeraire":"silver","hexes":["0_0","0_1"],"prices":{"grain":1,"cloth":5}}}
   * </pre>
   *
   * <p>★ <b>四条 fail-closed</b>：键不是合法市场 id ⇒ 抛（{@link MarketZoneId#parse}）；值不是对象 ⇒ 抛； ★★
   * <b>价表指名的格必须在 {@code entries} 里</b> ⇒ 否则抛（命令作用域是 entry 的格集，见类注）；同一 id 重复 ⇒ 抛。
   *
   * <p>★★ <b>{@code hexes} 可缺</b>（⇒ 空表）：持久的市场区（{@code marketZones}）由区记录给成员格，价表只带价与币；
   * 派生市场（键不在区表里）必须自己给出成员格 —— 那条守卫在 {@code EconomyData} 的构造期（"有市场的格"只许有一个来源）。
   *
   * @param payload 整份载荷（本方法自己读它的 {@code markets} 键）
   * @param entries 已经校验过的 {@code entries} 数组（用来判"市场落在 entry 之外"）
   */
  static Map<MarketZoneId, MarketTable> markets(JsonNode payload, JsonNode entries) {
    Map<MarketZoneId, MarketTable> markets = new LinkedHashMap<>();
    JsonNode node = optionalObject(payload, "markets");
    if (node == null) {
      return markets; // 缺键 ⇒ 世界上一个市场都没有（合法状态）
    }
    Set<HexCoord> entryHexes = new LinkedHashSet<>();
    for (JsonNode entry : entries) {
      entryHexes.add(new HexCoord(requireInt(entry, "q"), requireInt(entry, "r")));
    }
    node.fields()
        .forEachRemaining(
            field -> {
              MarketZoneId marketId;
              try {
                marketId = MarketZoneId.parse(field.getKey());
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("markets 的键必须是合法的市场 id: " + field.getKey(), e);
              }
              if (!field.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "markets."
                        + field.getKey()
                        + " 必须是对象 {numeraire, hexes, prices}: "
                        + field.getValue());
              }
              if (markets.putIfAbsent(
                      marketId, market(field.getValue(), entryHexes, field.getKey()))
                  != null) {
                throw new IllegalArgumentException("markets 里市场 id " + field.getKey() + " 重复");
              }
            });
    return markets;
  }

  /**
   * ★★ <b>一个市场的价表</b>（{@code {"numeraire":"<币种>","hexes":["<q>_<r>",…],"prices":{"<商品>":<单价>}}}）。
   *
   * <p>★ <b>键的口径</b>：{@code numeraire} 必填（每区恰一种计价货币）；{@code hexes} 可缺（持久的市场区由区记录给成员格）； {@code
   * prices} 可缺（⇒ 空表）；单价**逐值 ≥ 0** 由 {@link MarketTable} 的构造期守卫判死（本层不重复实现那条规则，只做形状）。
   *
   * <p>★★ <b>P5b2：{@code landPrice} 三态</b>：缺键 / {@code null} ⇒ {@link
   * OptionalLong#empty()}（<b>未定价</b>）； 给 {@code 0} ⇒ 明确免费；给正数 ⇒ 明确有价（毫计价货币 / 整亩）。负值由 {@link
   * MarketTable} 构造期 fail-closed 拒。
   */
  static MarketTable market(JsonNode node, Set<HexCoord> entryHexes, String marketId) {
    CurrencyId numeraire = CurrencyId.parse(requireText(node, "numeraire"));
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (JsonNode hexNode : optionalArray(node, "hexes")) {
      if (!hexNode.isTextual()) {
        throw new IllegalArgumentException(
            "markets." + marketId + ".hexes 的每一项必须是格串（<q>_<r>）: " + hexNode);
      }
      HexCoord hex = HexCoord.parse(hexNode.asText());
      if (!entryHexes.contains(hex)) {
        throw new IllegalArgumentException(
            "markets."
                + marketId
                + " 指名的格 "
                + hexNode.asText()
                + " 不在 entries 里（命令的作用域是 entry 的格集，"
                + "落在它之外的市场没有人申报 ⇒ 拒绝，不静默种下一个谁也管不到的格）");
      }
      hexes.add(hex);
    }
    Map<CommodityId, Long> prices = commodityMap(optionalObject(node, "prices"), "prices");
    // ★★ P5b2：landPrice 三态 —— 缺键/null = empty（未定价）；0 = 明确免费；正数 = 明确有价。负值由构造期拒。
    OptionalLong landPrice = OptionalLong.empty();
    if (node.has("landPrice") && !node.get("landPrice").isNull()) {
      landPrice = OptionalLong.of(requireLong(node, "landPrice"));
    }
    return new MarketTable(
        numeraire,
        hexes,
        prices,
        landPrice,
        MarketTable.DEFAULT_LOAN_RATE_PER_MILLE,
        MarketTable.DEFAULT_LOAN_RATE_ALPHA_PER_MILLE);
  }
}
