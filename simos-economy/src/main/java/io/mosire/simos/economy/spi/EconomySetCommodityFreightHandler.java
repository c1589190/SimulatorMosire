package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.SetCommodityFreight}（F 批 2026-10-09；约束设计书 §4.1「甲方案」）</b>：GM 设置**某个商品**的
 * 全局运费系数（‰）。
 *
 * <pre>{@code
 * {"commodityId":"grain","perMille":1500,"reason":"…"}
 * }</pre>
 *
 * <p>★★ <b>用户原话（设计书 §1.3/§1.4，逐字）</b>：「可以这么算，因为运力本质上也是劳动力；不同的商品拥有不同的运力需求，需要维护一个额外的全局
 * 商品对应运费表，<b>GM可改</b>；」「<b>肯定甲啊</b>；那行，算布匹+工具；」（甲 = 系数<b>乘在整条运费上</b>）。
 *
 * <p>★★ <b>写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code commodityFreightPerMille} 这**一个组件的一个商品**（upsert 一行）；不动价格、库存、账户、债务、需求、商号、
 *       区表：一个数都不动；
 *   <li><b>语义 = 千分系数（‰），乘在整条运费上</b>（唯一算式 {@link TransportTariff#perMille(long, long, long, long,
 *       long, long)} 的第 6 个入参）—— 1500 ⇒ 该商品在同一 lane 上的运费费率是未设时的 1.5 倍；
 *   <li>★ <b>缺键 ⇒ 1000</b>（= 现状、逐值不变）：因此"设回 1000"与"删掉这一行"在数值上等价；本命令不做删除（本批不需要）， 要回退就显式设 1000；
 *   <li>具名拒（三条，均**零 revision**、head 不动）：未知商品 ⇒ {@code unknown-commodity}（键域只认词表 {@link
 *       EconomyVocabulary#allCommodityIds()}）；{@code perMille ≤ 0} ⇒ {@code non-positive-freight}
 *       （"免费运输"不是"说不出价"）；与现值**逐字相同** ⇒ {@code freight-unchanged}（不做静默幂等 —— 照 {@code
 *       economy.SetMarketNumeraire}/{@code economy.SetOfficialRate} 的幂等口径：没变化就不落 revision）。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令标 {@link GmOnlyCommand}（照 {@code economy.SetMarketPrice}/{@code
 * economy.SetMarketNumeraire}）—— 它改的是"整个世界的运费口径"，属经济制度面，不进决策人令（{@code sd.IssueDirective} / {@code
 * AdjudicateTick} 的 directive 载荷）、不进 {@code sd.RegisterEffect} 可入队白名单、 不进决策人工具目录；GM 的 {@code
 * simos.command.submit} 与 GM 窄工具 {@code simos.economy.setCommodityFreight} 照常可用。
 *
 * <p>★ <b>{@code reason} 是可选审计文本</b>（只进日志、不进状态；与 {@code economy.DefineCurrency} 同款）。
 *
 * <p>★ <b>日志（§一.9）</b>：设置成功 INFO 一条（商品 / 新系数 / 旧值 / reason）；三条具名拒各 INFO 一条（业务拒绝 = INFO， 用户
 * 2026-10-23 裁定）+ DEBUG 一条"为什么"。
 */
public final class EconomySetCommodityFreightHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册、catalog 载荷提示与 GM 窄工具都取它）。 */
  public static final String TYPE = "economy.SetCommodityFreight";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      String commodityText = EconomyCommandPayloads.requireText(TYPE, payload, "commodityId");
      long perMille = EconomyCommandPayloads.requireLong(TYPE, payload, "perMille");
      String reason =
          payload.hasNonNull("reason")
              ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
              : "";
      CommodityId commodity = CommodityId.parse(commodityText);
      // ── 三条具名拒（各自 INFO + DEBUG，零 revision：Rejected 不带变更集，Core 不落 revision）──────────
      if (!EconomyVocabulary.allCommodityIds().contains(commodity.value())) {
        return reject(
            commodity.value(),
            perMille,
            "unknown-commodity",
            "商品不在词表里（本仓商品恰 "
                + EconomyVocabulary.allCommodityIds().size()
                + " 种："
                + EconomyVocabulary.allCommodityIds()
                + "）");
      }
      if (perMille <= 0L) {
        return reject(
            commodity.value(),
            perMille,
            "non-positive-freight",
            "运费系数必须 > 0（‰）：0/负不是'说不出价'，而是'免费运输'——不是合法系数");
      }
      long current = base.commodityFreightPerMilleOf(commodity);
      if (current == perMille) {
        return reject(
            commodity.value(),
            perMille,
            "freight-unchanged",
            "与现值逐字相同（现值 = "
                + current
                + "‰），不做静默幂等（要回退到现状请显式设 "
                + TransportTariff.DEFAULT_COMMODITY_FREIGHT_PER_MILLE
                + "）");
      }
      Map<CommodityId, Long> table = new LinkedHashMap<>(base.commodityFreightPerMille());
      table.put(commodity, perMille);
      EconomyData target = base.withCommodityFreightPerMille(table);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_SET_COMMODITY_FREIGHT_APPLIED",
                  EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                  "commodity",
                  commodity.value(),
                  "perMille",
                  perMille,
                  "previousPerMille",
                  current,
                  "reason",
                  reason,
                  "configuredCommodities",
                  table.size(),
                  "note",
                  "运费系数乘在整条运费上（§4.1 甲方案）；缺键 ⇒ "
                      + TransportTariff.DEFAULT_COMMODITY_FREIGHT_PER_MILLE
                      + "‰（= 现状）"));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, target));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_SET_COMMODITY_FREIGHT_REJECTED",
                  EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 一条具名拒：INFO（业务拒绝）+ DEBUG（为什么）+ 零变更集的 {@link HandlerOutcome.Rejected}。 */
  private static HandlerOutcome reject(
      String commodity, long perMille, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_SET_COMMODITY_FREIGHT_REJECTED",
                EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                "commodity",
                commodity,
                "perMille",
                perMille,
                "reason",
                reason,
                "detail",
                detail));
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "ECONOMY_SET_COMMODITY_FREIGHT_REJECT_CRITERIA",
                  EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                  "commodity",
                  commodity,
                  "perMille",
                  perMille,
                  "reason",
                  reason,
                  "detail",
                  detail,
                  "rejections",
                  "unknown-commodity / non-positive-freight / freight-unchanged"));
    }
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
