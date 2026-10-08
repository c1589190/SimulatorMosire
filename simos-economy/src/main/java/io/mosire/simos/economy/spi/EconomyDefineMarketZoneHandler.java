package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code economy.DefineMarketZone}（阶段 2-B2，2026-10-08；约束设计书 §4.2 / 用户 §1.2 的"退让市场区、覆盖范围、合并"
 * 的第一条，即"划界"）：<b>定义一个市场区</b> —— 一次写 {@code EconomyData.marketZones} 一条，一条命令 = 一条 revision（铁律 2）。
 *
 * <pre>{@code
 * {"zoneId":"c-tp-copper",          // 必填：区的稳定身份（建立之后只能用 Reassign/Merge 改成员，不改身份 —— 铁律 1）
 *  "anchor":{"q":-2,"r":0},         // 必填：集散节点格；必须 ∈ hexes 且**该格已有市场行**（区按它的币报价）
 *  "hexes":[{"q":-2,"r":0},…],      // 必填非空：成员格（唯一权威 —— 一个 hex 属于哪个区由它给定，I22）
 *  "legalTender":"copper",          // 必填：本区法定币（必须在世界词表里）
 *  "govUnitId":"gov-tp-copper",     // 必填：发行它的 GOV 单位（身份 = gov-unit-<govUnitId>；必须已登记为政府且发行该币）
 *  "radiusHex":8,                   // 可选（缺省 0）：声明半径（只用于邻接判据与读数，**不**参与成员格派生）
 *  "reason":"…"}                    // 可选：审计文本（只进日志，不进状态）
 * }</pre>
 *
 * <p>★★ <b>它是"市场区成为持久状态"的入口</b>：B2 之前市场区是纯派生件（城市 + tier 半径每轮现算），"这个 hex 属于哪个区"每次现算 ⇒
 * 划界/退让/覆盖/合并都没有可写的对象。本命令把"成员格"落成状态；非空区表之后，{@code MarketTopologyBook} 的成员格<b>由它给定</b>
 * （单一权威，I22），派生路径退化为空表时的默认值。
 *
 * <p>★★ <b>八条具名拒（全部 fail-closed，一条都不许静默）</b>：
 *
 * <ol>
 *   <li>{@code bad-payload}：载荷形状/边界（空 hexes、缺字段、非法格）；
 *   <li>{@code economy-not-activated}：经济切片未激活；
 *   <li>{@code zone-already-defined}：区 id 已存在（改成员走 {@code ReassignZoneHexes} / {@code
 *       MergeMarketZones}）；
 *   <li>{@code anchor-not-in-hexes}：锚格不在成员格里（{@code MarketZone} 的构造期守卫同款，此处提前成具名拒）；
 *   <li>{@code anchor-missing-market}：锚格没有市场行（"这一格按什么钱报价"说不出来；先 {@code economy.SetMarketPrice}）；
 *   <li>{@code currency-not-defined}：法定币不在世界词表里；
 *   <li>{@code gov-not-registered} / {@code currency-not-issuable}：发行 GOV 未登记，或它的 {@code issuable}
 *       不含该法定币 （"谁发行的"不许在两处漂开）；
 *   <li>{@code hex-in-other-zone}：某个成员格已经在别的区里（I22：一个 hex 至多属于一个区；先把它从那个区划出去）—— ★
 *       排在"这个区本身长什么样"的四条判据之后：先问"你立的是什么区"，再问"这些格腾出来了吗"；
 *   <li>{@code numeraire-mismatch}：某个**已有市场行**的成员格的计价币 ≠ 本区法定币（同一格上的两种"这格用什么钱"不能并存；
 *       先把该格改成法定币：{@code economy.SetMarketNumeraire}）。
 * </ol>
 *
 * <p>★★ <b>GM-only</b>：本命令标 {@link GmOnlyCommand}（照 {@code economy.SetOfficialRate} / {@code
 * economy.DefineCurrency}） —— 划界是货币制度面的事实，不进决策人令 / {@code RegisterEffect} / 决策人命令目录。★
 * 决策人侧的"管理本国市场区"（用户 §1.2 的 口岸政策语境）需要身份派生的作用域，与口岸/管制一起做（阶段 3），本批**不**给决策人桶开这条口子（权限不得因为新增命令而放大）。
 *
 * <p>★ <b>不实现 {@link CommandTargets}</b>：区表是<b>世界级</b>表（一份区表横跨多个格，但"区"本身不是格资源），且本命令 GM-only ⇒
 * 不进决策令桶（与 {@code SetOfficialRate} / {@code DefineCurrency} 同款，{@code targetPaths} 恒空）。
 */
public final class EconomyDefineMarketZoneHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.DefineMarketZone";

  private static final LogChannel LOG = EventLog.channel(EconomyLog.command());

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson); // 形状校验（缺字段/空 hexes ⇒ 抛具名载荷错）；区表没有单格资源目标。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Definition definition;
    try {
      definition = parse(payloadJson);
    } catch (IllegalArgumentException e) {
      return rejected(
          "bad-payload", null, null, EconomyCommandPayloads.logReason(e.getMessage()), null);
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected(
            "economy-not-activated", definition, null, "economy 切片尚未激活（先 economy.Seed 播种）", base);
      }
      MarketZoneId zoneId = new MarketZoneId(definition.zoneId());
      if (base.marketZones().containsKey(zoneId)) {
        return rejected(
            "zone-already-defined",
            definition,
            null,
            "区 id 已存在（改成员走 economy.ReassignZoneHexes / economy.MergeMarketZones，身份不变）: "
                + zoneId.value(),
            base);
      }
      if (!definition.hexes().contains(definition.anchor())) {
        return rejected(
            "anchor-not-in-hexes",
            definition,
            definition.anchor(),
            "锚格必须在成员格里（它是本区的取价点）: anchor=" + definition.anchor(),
            base);
      }
      if (!base.markets().containsKey(definition.anchor())) {
        return rejected(
            "anchor-missing-market",
            definition,
            definition.anchor(),
            "锚格没有市场行（说不出本区按什么钱报价；先 economy.SetMarketPrice 在该格建市场）: " + definition.anchor(),
            base);
      }
      CurrencyId legalTender = new CurrencyId(definition.legalTender());
      if (!base.currencies().containsKey(legalTender)) {
        return rejected(
            "currency-not-defined",
            definition,
            null,
            "法定币不在世界词表里: " + definition.legalTender() + "（词表=" + base.currencies().keySet() + "）",
            base);
      }
      GovernmentId governmentId = GovernmentIds.ofUnit(definition.govUnitId());
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return rejected(
            "gov-not-registered",
            definition,
            null,
            "发行 GOV 未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value(),
            base);
      }
      if (!government.issuable().contains(legalTender)) {
        return rejected(
            "currency-not-issuable",
            definition,
            null,
            "该 GOV 不发行这种钱（谁发行的不许在两处漂开）: gov="
                + governmentId.value()
                + " 法定币="
                + legalTender.value()
                + " issuable="
                + government.issuable(),
            base);
      }
      for (HexCoord hex : definition.hexes()) {
        Optional<MarketZone> owner = MarketZoneBook.zoneOfHex(base, hex);
        if (owner.isPresent()) {
          return rejected(
              "hex-in-other-zone",
              definition,
              hex,
              "格 "
                  + hex
                  + " 已属于市场区 "
                  + owner.get().zoneId().value()
                  + "（I22：一个 hex 至多属于一个区；先 economy.ReassignZoneHexes 把它划出去）",
              base);
        }
      }
      for (HexCoord hex : definition.hexes()) {
        Market market = base.markets().get(hex);
        if (market != null && !market.numeraire().equals(legalTender)) {
          return rejected(
              "numeraire-mismatch",
              definition,
              hex,
              "格 "
                  + hex
                  + " 的计价币是 "
                  + market.numeraire().value()
                  + "，与本区法定币 "
                  + legalTender.value()
                  + " 不一致（同一格上不许有两种\"这格用什么钱\"；先 economy.SetMarketNumeraire）",
              base);
        }
      }
      MarketZone zone =
          new MarketZone(
              zoneId,
              definition.anchor(),
              definition.radiusHex(),
              new LinkedHashSet<>(definition.hexes()),
              legalTender,
              governmentId,
              Map.of());
      Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
      zones.put(zoneId, zone);
      EconomyData projected = base.withMarketZones(zones);
      logDefined(zone, base.marketZones().size(), projected.marketZones().size());
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation",
          definition,
          null,
          EconomyCommandPayloads.logReason(e.getMessage()),
          base);
    }
  }

  /** 区定义 INFO 一条"发生了什么 + 具名计数"（§一.9）：区 id/锚格/格数/法定币/发行 GOV/半径/区表规模。 */
  private static void logDefined(MarketZone zone, int zonesBefore, int zonesAfter) {
    LOG.info(
        LogEvent.of(
            "MARKET_ZONE_DEFINED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "zone",
            zone.zoneId().value(),
            "anchor",
            zone.anchor().toString(),
            "hexCount",
            zone.hexCount(),
            "legalTender",
            zone.legalTender().value(),
            "issuingGov",
            zone.issuingGov().value(),
            "issuingGovUnit",
            MarketZoneBook.issuingGovUnitOf(zone).orElse("(world-level)"),
            "radiusHex",
            zone.radiusHex(),
            "zonesBefore",
            zonesBefore,
            "zonesAfter",
            zonesAfter));
    LOG.debug(
        LogEvent.of(
            "MARKET_ZONE_DEFINED_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "zone",
            zone.zoneId().value(),
            "hexes",
            describeHexes(zone.hexes())));
  }

  /** 具名拒（业务拒绝 = INFO 一条 + DEBUG 一条"为什么"；AGENTS §一.9 与 2026-10-23 裁定）。 */
  private static HandlerOutcome rejected(
      String reason, Definition definition, HexCoord hex, String message, EconomyData base) {
    LOG.info(
        LogEvent.of(
            "MARKET_ZONE_REJECTED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "command",
            TYPE,
            "reason",
            reason,
            "zone",
            definition == null ? "" : definition.zoneId(),
            "hex",
            hex == null ? "" : hex.toString()));
    LOG.debug(
        LogEvent.of(
            "MARKET_ZONE_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "command",
            TYPE,
            "reason",
            reason,
            "zone",
            definition == null ? "" : definition.zoneId(),
            "hexCount",
            definition == null ? -1 : definition.hexes().size(),
            "legalTender",
            definition == null ? "" : definition.legalTender(),
            "govUnit",
            definition == null ? "" : definition.govUnitId(),
            "zonesInWorld",
            base == null ? -1 : base.marketZones().size(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 格集的稳定可读串（保序；只输出坐标，不输出任何载荷 —— §一.9 的日志纪律）。 */
  static String describeHexes(Set<HexCoord> hexes) {
    StringBuilder text = new StringBuilder();
    for (HexCoord hex : hexes) {
      if (text.length() > 0) {
        text.append(',');
      }
      text.append(hex);
    }
    return text.toString();
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Definition parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String zoneId = EconomyCommandPayloads.requireText(TYPE, payload, "zoneId");
    HexCoord anchor = EconomyCommandPayloads.requireHex(TYPE, payload, "anchor");
    List<HexCoord> hexes = EconomyCommandPayloads.requireHexArray(TYPE, payload, "hexes");
    String legalTender = EconomyCommandPayloads.requireText(TYPE, payload, "legalTender");
    String govUnitId = EconomyCommandPayloads.requireText(TYPE, payload, "govUnitId");
    int radiusHex = EconomyCommandPayloads.optionalInt(TYPE, payload, "radiusHex", 0);
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(zoneId, anchor, hexes, legalTender, govUnitId, radiusHex, reason);
  }

  /** 一条区定义（三个业务字段 + 格集 + 可选半径/审计文本；边界在构造期判完，与 {@code MarketZone} 的守卫同一口径）。 */
  private record Definition(
      String zoneId,
      HexCoord anchor,
      List<HexCoord> hexes,
      String legalTender,
      String govUnitId,
      int radiusHex,
      String reason) {

    Definition {
      Objects.requireNonNull(zoneId, "zoneId");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(hexes, "hexes");
      Objects.requireNonNull(legalTender, "legalTender");
      Objects.requireNonNull(govUnitId, "govUnitId");
      if (zoneId.isBlank()) {
        throw new IllegalArgumentException("zoneId 不得为空白");
      }
      if (hexes.isEmpty()) {
        throw new IllegalArgumentException("hexes 不得为空（一个区至少含锚格）");
      }
      if (legalTender.isBlank()) {
        throw new IllegalArgumentException("legalTender 不得为空白");
      }
      if (govUnitId.isBlank()) {
        throw new IllegalArgumentException("govUnitId 不得为空白");
      }
      if (radiusHex < 0) {
        throw new IllegalArgumentException("radiusHex 不得为负: " + radiusHex);
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
