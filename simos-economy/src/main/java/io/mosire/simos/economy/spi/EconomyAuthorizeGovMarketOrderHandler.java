package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code economy.AuthorizeGovernmentMarketOrder}（R1，GM-only）：<b>给某政府的国库家户一条「明确授权下单」</b>。
 *
 * <pre>{@code
 * {"id":"gm:central-buy-grain-1",
 *  "government":"gov-unit-gov-central",     // 必须是已登记的政府（国库 = hh-gov-<govUnitId>，账户主体只有家户）
 *  "commodity":"grain",
 *  "side":"BUY",                            // BUY = 收购（价格上限）/ SELL = 抛售（价格下限）
 *  "quantityMilli":100000,                  // 授权总量（毫商品单位），必须 > 0
 *  "limitPriceMilli":2,                     // 限价（毫计价货币/单位），必须 ≥ 1
 *  "expiresOnDay":60,                       // 最后一个生效日（含）；必须 ≥ 当天（不许"出生即过期"）
 *  "source":"gm:simos.gm.govMarketMandate"  // 谁授权的（审计串）；不得空白
 * }</pre>
 *
 * <p>★★ <b>为什么需要它（用户 2026-10-09 §1.3 的红线）</b>：政府的经济行为只能落成<b>行政家户挂单</b>， 不许新增"政策层/补贴层/官营层"。本命令就是那条"挂单"的<b>唯一授权入口</b>：
 * 它只写一张授权表（谁/商品/方向/量/限价/到期），<b>不改价格、不改成本、不豁免任何市场规则</b>； 订单仍由既有的 {@code
 * 家户→订单→撮合→结算} 全链路在日结算里生成与成交。
 *
 * <p>★★ <b>五项 fail-closed 校验（全部具名拒、零 revision、head 不动）</b>：
 *
 * <ol>
 *   <li><b>未知政府</b>：{@code base.governments()} 里查不到 ⇒ 拒（授权没有可执行的主体）；
 *   <li><b>国库不是家户</b>：账户主体只有家户 ⇒ 拒；<b>国库家户没有经济行</b>（{@code classes} 缺行）⇒ 拒 （它进不了市场参与者表，
 *       授权永远不会成交）；
 *   <li><b>未知商品 / 未定价</b>：该商品在<b>国库户所在格</b>的市场价表里从未定价 ⇒ 拒 —— 市场对"从未定价"的商品整行不交易 （见
 *       {@code MarketSettlement.ordersFor}），授权一条永不可能成交的挂单就是留一条静默死行；
 *   <li><b>量 / 限价 / 到期</b>：量 ≤ 0、限价 ≤ 0、到期日早于当天 ⇒ 拒（{@link GovernmentMarketMandate} 的构造期守卫 + 这里的
 *       到期判定）；
 *   <li><b>幂等</b>：同 id + 授权形状逐值相同 ⇒ 拒（"已存在同形状授权，不重复下单"）—— 见类注末条。
 * </ol>
 *
 * <p>★★ <b>幂等语义与"不许留永久挂单"</b>：
 *
 * <ul>
 *   <li>同 id + 形状逐值相同 ⇒ <b>拒</b>（{@code reason=idempotent-duplicate-authorization}）：<b>不双倍下单</b>、 不留
 *       revision。★ 为什么是"拒"而不是"静默 no-op"：本仓的 {@code HandlerOutcome} 只有 Applied/Rejected 两种， 而
 *       Applied（哪怕是全 Unchanged 的变更集）也会落一条 revision ⇒ 静默 no-op 会给出"看起来成功了"的假象；
 *       拒会让调用方看见"这条授权已经在生效"，同时保证 head 不动。
 *   <li>同 id + 形状不同 ⇒ <b>拒</b>（{@code reason=authorization-shape-change}）：要改形状必须先 {@code
 *       economy.CancelGovernmentMarketOrder} 再以新 id/新载荷授权 —— 否则"改一次量"就能把已成交量悄悄重置（双倍下单的另一种形态）。
 *   <li>到期日与总量是<b>必填</b>（本命令不接受"永久授权"）：{@code expiresOnDay} 必须给出，且到期后由日结算清除该行。
 * </ul>
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 的 {@code
 * simos.command.submit} 与窄工具 {@code simos.gm.govMarketMandate} 照常可用。
 */
public final class EconomyAuthorizeGovMarketOrderHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.AuthorizeGovernmentMarketOrder";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(TYPE, payloadJson); // 形状校验；本命令没有可声明的格资源目标
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      long day = state.meta().timestamp().tick();
      Request request = parse(TYPE, payloadJson);
      GovernmentMarketMandate mandate =
          new GovernmentMarketMandate(
              request.id(),
              request.government(),
              request.commodity(),
              request.side(),
              request.quantityMilli(),
              request.limitPriceMilli(),
              day,
              request.expiresOnDay(),
              // ★ 新授权恒从 0 成交量起；"改形状"已在下面被拒 ⇒ 不存在"继承旧成交量"的歧义。
              0L,
              request.source());
      // ① 未知政府 / ② 国库必须是家户 / ③ 国库家户必须有经济行。
      Government government = base.governments().get(mandate.government());
      if (government == null) {
        return reject(
            "unknown-government",
            "未知政府（授权没有可执行的主体）: " + mandate.government().value(),
            "government",
            mandate.government().value());
      }
      if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
        return reject(
            "treasury-not-household",
            "该政府的国库不是家户（账户主体只有家户，挂单主体必须是国库家户）: "
                + mandate.government().value()
                + " treasury="
                + government.treasury(),
            "government",
            mandate.government().value());
      }
      HouseholdId treasury =
          io.mosire.simos.economy.api.cohort.HouseholdActors.householdOf(government.treasury());
      if (!base.classes().containsKey(treasury)) {
        return reject(
            "treasury-household-has-no-economy-row",
            "国库家户没有经济行（它进不了市场参与者表，授权永远不会成交）: " + treasury.value(),
            "government",
            mandate.government().value(),
            "treasury",
            treasury.value());
      }
      // ④ 到期日已过：本命令的 authorizedDay = 当天 ⇒ 早于当天就是"出生即过期"，当场拒（不静默改期）。
      if (mandate.expiresOnDay() < day) {
        return reject(
            "expires-on-day-already-past",
            "到期日已过（expiresOnDay="
                + mandate.expiresOnDay()
                + " < 当天="
                + day
                + "）: "
                + mandate.id().value(),
            "id",
            mandate.id().value(),
            "expiresOnDay",
            mandate.expiresOnDay(),
            "day",
            day);
      }
      // ⑤ 未知商品 / 未定价：国库户所在格的市场价表里没有该商品 ⇒ 市场对它整行不交易，授权是死行。
      Market market = base.markets().get(treasuryHex(base, treasury));
      if (market == null) {
        return reject(
            "treasury-hex-has-no-market",
            "国库户所在格没有市场（没有价表 ⇒ 挂单无处可挂）: " + treasury.value(),
            "government",
            mandate.government().value(),
            "treasury",
            treasury.value());
      }
      if (!market.hasPrice(mandate.commodity())) {
        return reject(
            "unknown-or-unpriced-commodity",
            "商品在国库户所在格从未定价（市场对未定价商品整行不交易）: "
                + mandate.commodity().value()
                + " treasury="
                + treasury.value(),
            "commodity",
            mandate.commodity().value(),
            "treasury",
            treasury.value());
      }
      // ⑥ 幂等 / 形状变更：两者都拒（理由不同、可分辨）。
      GovernmentMarketMandate previous = base.govMarketMandates().get(mandate.id());
      if (previous != null) {
        if (previous.sameAuthorizationAs(mandate)) {
          return reject(
              "idempotent-duplicate-authorization",
              "已存在形状逐值相同的授权（幂等：不重复下单、不改状态）: " + mandate.id().value(),
              "id",
              mandate.id().value());
        }
        return reject(
            "authorization-shape-change",
            "同 id 的授权形状不同（要改形状请先 Cancel 再以新载荷授权，避免已成交量被悄悄重置）: " + mandate.id().value(),
            "id",
            mandate.id().value());
      }
      // ⑦ 同一政府 + 同一商品 + 同一方向**至多一条生效授权**：否则两条授权会生成两条同类订单，成交量无法归属
      //   （{@code GovernmentMarketMandatePlan} 与 {@code collectGovMandateFills} 也各有一道 fail-closed
      // 守卫）。
      //   ★ 判据只看"当天生效且未耗尽"的行：已过期但尚未被日结算清除的行不挡新授权。
      for (GovernmentMarketMandate other : base.govMarketMandates().values()) {
        if (other.id().equals(mandate.id())
            || !other.government().equals(mandate.government())
            || !other.commodity().equals(mandate.commodity())
            || other.side() != mandate.side()) {
          continue;
        }
        if (other.effectiveOn(day) && !other.exhausted()) {
          return reject(
              "duplicate-open-authorization",
              "该政府在该商品该方向上已有一条生效授权（先 Cancel 旧的再授权）: "
                  + other.id().value()
                  + " side="
                  + other.side()
                  + " commodity="
                  + other.commodity().value(),
              "existing",
              other.id().value(),
              "government",
              other.government().value());
        }
      }
      Map<MarketMandateId, GovernmentMarketMandate> mandates =
          new LinkedHashMap<>(base.govMarketMandates());
      mandates.put(mandate.id(), mandate);
      EconomyData projected = base.withGovMarketMandates(mandates);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "GOV_MARKET_ORDER_AUTHORIZED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "id",
                  mandate.id().value(),
                  "government",
                  mandate.government().value(),
                  "treasury",
                  treasury.value(),
                  "side",
                  mandate.side().name(),
                  "commodity",
                  mandate.commodity().value(),
                  "quantityMilli",
                  mandate.quantityMilli(),
                  "limitPriceMilli",
                  mandate.limitPriceMilli(),
                  "authorizedDay",
                  mandate.authorizedDay(),
                  "expiresOnDay",
                  mandate.expiresOnDay(),
                  "source",
                  mandate.source(),
                  "authorizations",
                  mandates.size()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return reject("payload-invalid", e.getMessage());
    }
  }

  /** 国库户所在格：市场只在它的落点格上为它生成订单。 */
  private static io.mosire.simos.map.hex.HexCoord treasuryHex(
      EconomyData base, HouseholdId treasury) {
    return base.classes().get(treasury).view().hex();
  }

  /** 具名拒 + INFO 日志（§一.9：业务拒绝 = INFO，理由具名可查；extra 为偶数个 key/value 对）。 */
  private static HandlerOutcome reject(String reason, String message, Object... extra) {
    if (extra.length % 2 != 0) {
      throw new IllegalArgumentException("reject 的 extra 必须是偶数个 key/value: " + extra.length);
    }
    Object[] keyValues = new Object[4 + extra.length];
    keyValues[0] = "reason";
    keyValues[1] = reason;
    keyValues[2] = "detail";
    keyValues[3] = EconomyCommandPayloads.logReason(message);
    System.arraycopy(extra, 0, keyValues, 4, extra.length);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_AUTHORIZE_GOV_MARKET_ORDER_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                keyValues));
    return new HandlerOutcome.Rejected(message);
  }

  /** 载荷形状（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  static Request parse(String command, String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(command, payloadJson);
    MarketMandateId id =
        MarketMandateId.parse(EconomyCommandPayloads.requireText(command, payload, "id"));
    GovernmentId government =
        GovernmentId.parse(EconomyCommandPayloads.requireText(command, payload, "government"));
    CommodityId commodity =
        CommodityId.parse(EconomyCommandPayloads.requireText(command, payload, "commodity"));
    String sideText = EconomyCommandPayloads.requireText(command, payload, "side");
    GovernmentMarketMandate.Side side;
    try {
      side =
          GovernmentMarketMandate.Side.valueOf(sideText.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(command + " 的 side 词表外（只认 BUY/SELL）: " + sideText);
    }
    long quantityMilli = EconomyCommandPayloads.requireLong(command, payload, "quantityMilli");
    long limitPriceMilli = EconomyCommandPayloads.requireLong(command, payload, "limitPriceMilli");
    long expiresOnDay = EconomyCommandPayloads.requireLong(command, payload, "expiresOnDay");
    String source = EconomyCommandPayloads.requireText(command, payload, "source");
    return new Request(
        id, government, commodity, side, quantityMilli, limitPriceMilli, expiresOnDay, source);
  }

  /** 解析后的载荷（构造 {@link GovernmentMarketMandate} 之前的中间形状）。 */
  record Request(
      MarketMandateId id,
      GovernmentId government,
      CommodityId commodity,
      GovernmentMarketMandate.Side side,
      long quantityMilli,
      long limitPriceMilli,
      long expiresOnDay,
      String source) {}
}
