package io.mosire.simos.app.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.gov.spi.SetPortPolicyHandler;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>R2 口岸政策词表守卫（N1 负向判据：未知商品/未知币种 ⇒ fail-closed 具名拒）</b>。
 *
 * <p>★★ <b>为什么需要它、以及为什么住在组合根</b>：{@code gov.SetPortPolicy} 的 handler 在 {@code simos-gov} 里，而 {@code
 * simos-gov} <b>编译期看不见经济词表</b>（模块边界：gov 只许依赖 {@code economy-api} 的 ID 契约，不见 {@code
 * EconomyData}）。于是"这个商品/币种在世界里存在吗"这条判据在 handler 里<b>判不了</b>；把它留到日循环折算处（{@code PortRegimeBridge} 的具名
 * ERROR）虽然也 fail-closed，但那时非法政策<b>已经落进状态</b>了 —— 那不是"具名拒绝"，那是 "先存下坏数据、下次结算再炸"。
 *
 * <p>⇒ 判据落在<b>写前跨模块守卫</b>（{@link MutationGuard}，与 {@link GovJurisdictionGuard} / {@code
 * RegionDeleteGuard} 同一形制、同一注册点）：命令在 {@code handler.handle} <b>之前</b>被拦，返回具名理由 ⇒ Core 照 {@code
 * HandlerOutcome.Rejected} 落账，<b>不留 revision</b>。它只读状态、不写状态。
 *
 * <p>★ <b>它拦什么</b>：只拦 {@code gov.SetPortPolicy}；两条表的<b>键</b>分别对 {@link
 * EconomyVocabulary#allCommodityIds()}（商品词表）与 {@link EconomyData#currencies()}（币种词表，世界状态权威）。
 * 键的<b>词法</b>与值的<b>值域</b>（≥ 0）仍由 {@code GovPayloads}/{@code GovPortPolicy} 在命令边界判 —— 两处不重复实现。
 *
 * <p>★ <b>fail-closed 方向</b>：economy 切片缺失/类型不符 ⇒ <b>拒</b>（装配故障不能放坏政策进状态），理由具名。
 */
public final class GovPortPolicyGuard implements MutationGuard {

  /** 唯一的一台 mapper：共享基座出厂配置（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public String name() {
    return "gov-port-policy-vocabulary";
  }

  @Override
  public Optional<String> rejection(SimulationState state, String commandType, String payloadJson) {
    if (!SetPortPolicyHandler.TYPE.equals(commandType)) {
      return Optional.empty();
    }
    JsonNode payload;
    try {
      payload = MAPPER.readTree(payloadJson == null ? "{}" : payloadJson);
    } catch (JsonProcessingException e) {
      // 坏 JSON 由 handler 的载荷解析给出具名拒（这里不抢它的活）。
      // ★ 只捕 Jackson 的解析异常（不是宽口 `Exception`）：`REC_CATCH_EXCEPTION` 要求 catch 的类型确实是
      //   try 块里会抛的 —— `readTree(String)` 只声明 `JsonProcessingException`。
      return Optional.empty();
    }
    if (payload == null || !payload.isObject()) {
      return Optional.empty();
    }
    EconomyData economy = economyOf(state);
    if (economy == null) {
      return Optional.of("口岸政策守卫：state 里没有 economy 切片（装配故障）⇒ 无法判定类词表，fail-closed 拒");
    }
    Set<String> knownCurrencies = new LinkedHashSet<>();
    for (CurrencyId currency : economy.currencies().keySet()) {
      knownCurrencies.add(currency.value());
    }
    Optional<String> unknownCommodity =
        unknownKey(
            payload.get("commodityRestrictionPerMille"), EconomyVocabulary.allCommodityIds(), "商品");
    if (unknownCommodity.isPresent()) {
      return unknownCommodity;
    }
    return unknownKey(payload.get("currencyRestrictionPerMille"), knownCurrencies, "币种");
  }

  /** 一张表里有没有词表外的键；有 ⇒ 具名理由（点名第一个未知键，便于排查）。 */
  private static Optional<String> unknownKey(JsonNode table, Iterable<String> known, String kind) {
    if (table == null || table.isNull() || !table.isObject()) {
      return Optional.empty(); // 缺省/形状坏 ⇒ 交给 handler 的解析器（具名拒）
    }
    java.util.List<String> knownList = new java.util.ArrayList<>();
    for (String value : known) {
      knownList.add(value);
    }
    var fields = table.fieldNames();
    while (fields.hasNext()) {
      String key = fields.next();
      if (!knownList.contains(key)) {
        return Optional.of("口岸政策非法：未知" + kind + " " + key + "（不在世界词表内；fail-closed 拒，不静默忽略）");
      }
    }
    return Optional.empty();
  }

  private static EconomyData economyOf(SimulationState state) {
    if (state == null) {
      return null;
    }
    return state
        .module("economy")
        .filter(EconomySnapshot.class::isInstance)
        .map(EconomySnapshot.class::cast)
        .map(EconomySnapshot::data)
        .orElse(null);
  }
}
