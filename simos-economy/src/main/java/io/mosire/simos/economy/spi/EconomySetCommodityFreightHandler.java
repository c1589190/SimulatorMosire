package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.CommodityFreightBase;
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
 * ★★ <b>{@code economy.SetCommodityFreight}</b>（2026-10-09 用户裁定「甲」后的<b>纠正版</b>；约束设计书 §4.1 修正版）： GM
 * 设置**某个商品**的<b>基础运费</b>（毫计价货币 / 商品单位 / 程 —— 与商品价格无关）。
 *
 * <pre>{@code
 * {"commodityId":"grain","baseMilli":2,"reason":"…"}
 * }</pre>
 *
 * <p>★★ <b>用户原话（设计书 §1.3，逐字）</b>：「可以这么算，因为运力本质上也是劳动力；不同的商品拥有不同的运力需求，需要维护一个额外的全局
 * 商品对应运费表，<b>GM可改</b>；」。★ 裁定「甲」的含义经控制方 2026-10-09 修正：商品维落成<b>基础费表</b>（面值维）， <b>不是</b>费率乘数 —— F
 * 批把系数乘在整条费率上是方向错的产物（与早已存在的硬编码分档构成两个商品维相乘），已撤销。
 *
 * <p>★★ <b>写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code commodityFreightBaseMilli} 这**一个组件的一个商品**（upsert 一行）；不动价格、库存、账户、债务、需求、商号、
 *       区表：一个数都不动；
 *   <li><b>语义 = 基础运费（毫 / 商品单位 / 程）</b>：唯一算式在 {@code MarketSettlement.freightUnitMilli} —— {@code
 *       unit = max(1, ⌈ 基础费 × (1000 + 路线费率) × (1000 + 承运成本) ÷ 1,000,000 ⌉)} ⇒
 *       商品维管<b>整条单位运费</b>（短途长途都管），不再只是长途的乘数；
 *   <li>★ <b>值域 ≥ 0</b>：{@code 0} 是<b>明确的</b>"该商品免基础费"（单位运费仍被 {@code max(1, …)} 抬到 1 毫）；
 *   <li>★★ <b>缺键 ⇒ 现行硬编码分档</b>（{@link CommodityFreightBase#legacyMilli(CommodityId)}：粮 1 / 纤维 1 / 布
 *       2 / 工具 3；未登记商品 ⇒ 1）—— 因此<b>旧档 / 未设表的世界逐值等于改动前</b>（I-F1）。 ★ 要改回"分档里那个值"没必要 （本来就是这个值 ⇒ 会被
 *       {@code freight-unchanged} 拒）；要把某商品变成 1 或 0 ⇒ <b>显式设</b>它；
 *   <li>具名拒（三条，均**零 revision**、head 不动）：未知商品 ⇒ {@code unknown-commodity}（键域只认词表 {@link
 *       EconomyVocabulary#allCommodityIds()}）；{@code baseMilli < 0} ⇒ {@code
 *       negative-freight}（负运费不是"说不出价"；★ {@code 0} **是合法的** = 免基础费）；与现值 **逐字相同** ⇒ {@code
 *       freight-unchanged}（不做静默幂等 —— 照 {@code economy.SetMarketNumeraire}/{@code
 *       economy.SetOfficialRate} 的幂等口径：没变化就不落 revision）。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令标 {@link GmOnlyCommand}（照 {@code economy.SetMarketPrice}/{@code
 * economy.SetMarketNumeraire}）—— 它改的是"整个世界的运费口径"，属经济制度面，不进决策人令（{@code sd.IssueDirective} / {@code
 * AdjudicateTick} 的 directive 载荷）、不进 {@code sd.RegisterEffect} 可入队白名单、 不进决策人工具目录；GM 的 {@code
 * simos.command.submit} 与 GM 窄工具 {@code simos.economy.setCommodityFreight} 照常可用。
 *
 * <p>★ <b>{@code reason} 是可选审计文本</b>（只进日志、不进状态；与 {@code economy.DefineCurrency} 同款）。
 *
 * <p>★ <b>日志（§一.9）</b>：设置成功 INFO 一条（商品 / 新基础费 / 旧值 / reason）；三条具名拒各 INFO 一条（业务拒绝 = INFO， 用户
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
      long baseMilli = EconomyCommandPayloads.requireLong(TYPE, payload, "baseMilli");
      String reason =
          payload.hasNonNull("reason")
              ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
              : "";
      CommodityId commodity = CommodityId.parse(commodityText);
      // ── 三条具名拒（各自 INFO + DEBUG，零 revision：Rejected 不带变更集，Core 不落 revision）──────────
      if (!EconomyVocabulary.allCommodityIds().contains(commodity.value())) {
        return reject(
            commodity.value(),
            baseMilli,
            "unknown-commodity",
            "商品不在词表里（本仓商品恰 "
                + EconomyVocabulary.allCommodityIds().size()
                + " 种："
                + EconomyVocabulary.allCommodityIds()
                + "）");
      }
      if (baseMilli < 0L) {
        return reject(
            commodity.value(),
            baseMilli,
            "negative-freight",
            "基础运费必须 ≥ 0（毫/单位/程）：负值不是'说不出价'；★ 0 是合法的 = 该商品免基础费");
      }
      long current = base.commodityFreightBaseMilliOf(commodity);
      if (current == baseMilli) {
        return reject(
            commodity.value(),
            baseMilli,
            "freight-unchanged",
            "与现值逐字相同（现值 = "
                + current
                + " 毫/单位/程），不做静默幂等（★ 缺键时的现'值'就是现行硬编码分档 "
                + CommodityFreightBase.legacyMilli(commodity)
                + "）");
      }
      Map<CommodityId, Long> table = new LinkedHashMap<>(base.commodityFreightBaseMilli());
      table.put(commodity, baseMilli);
      EconomyData target = base.withCommodityFreightBaseMilli(table);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_SET_COMMODITY_FREIGHT_APPLIED",
                  EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                  "commodity",
                  commodity.value(),
                  "baseMilli",
                  baseMilli,
                  "previousBaseMilli",
                  current,
                  "reason",
                  reason,
                  "configuredCommodities",
                  table.size(),
                  "note",
                  "基础运费（毫/单位/程），商品维管整条单位运费（§4.1 修正版）；缺键 ⇒ 现行硬编码分档"
                      + "（未登记商品 ⇒ "
                      + CommodityFreightBase.DEFAULT_MILLI
                      + "）；0 = 免基础费"));
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
      String commodity, long baseMilli, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_SET_COMMODITY_FREIGHT_REJECTED",
                EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                "commodity",
                commodity,
                "baseMilli",
                baseMilli,
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
                  "baseMilli",
                  baseMilli,
                  "reason",
                  reason,
                  "detail",
                  detail,
                  "rejections",
                  "unknown-commodity / negative-freight / freight-unchanged"));
    }
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
