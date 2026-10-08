package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Market;
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
import java.util.Set;

/**
 * ★★ {@code economy.ReassignZoneHexes}（阶段 2-B2，2026-10-08；约束设计书 §4.2 / 用户 §1.2「退让市场区、覆盖范围」）：
 * <b>把一个格的归属从一个区划到另一个区</b> —— 一次写 {@code marketZones} 两条（源区少一格、目标区多一格），一条命令 = 一条 revision（铁律 2）。
 *
 * <pre>{@code
 * {"fromZoneId":"c-tp-silver",     // 必填：退让方（该格当前必须属于它）
 *  "toZoneId":"c-tp-copper",       // 必填：接收方（≠ from）
 *  "hexes":[{"q":0,"r":0},…],      // 必填非空：要改划的格
 *  "reason":"…"}                   // 可选：审计文本（只进日志，不进状态）
 * }</pre>
 *
 * <p>★★ <b>"退让"与"覆盖"是同一条命令的两面</b>（用户原话"退让市场区、覆盖范围"）：源区少掉这些格 = 退让；目标区收进这些格 =
 * 覆盖。本批不做"只退让不覆盖"的形态（那会留下不属于任何区的格 —— 未覆盖的市场格仍会退回单格区，等于把一个治理动作变成一次 静默降级）；要删区走 {@code
 * economy.MergeMarketZones}（合并即撤源区）。
 *
 * <p>★★ <b>六条具名拒（fail-closed）</b>：
 *
 * <ol>
 *   <li>{@code bad-payload}：形状/边界（空 hexes、缺字段、非法格）；
 *   <li>{@code economy-not-activated}；
 *   <li>{@code zone-not-found}：源区/目标区不存在（点名是哪一个）；
 *   <li>{@code same-zone}：源区 == 目标区（不是划界，是 no-op）；
 *   <li>{@code hex-not-in-from-zone}：某个格并不属于源区（说不出"退让的是哪一格"）；
 *   <li>{@code from-zone-would-be-empty}：源区的格被<b>全部</b>划走（区必须至少含锚格；要撤区请走 MergeMarketZones，
 *       那是一条有明确归属去向的动作，不是把区留成空壳）；
 *   <li>{@code from-zone-anchor-would-move}：源区的<b>锚格</b>在被划走的格里（锚格是取价点，划走它等于让本区说不出"按哪一格的价"；
 *       先把锚格留下，或用 MergeMarketZones 撤掉这个区）；
 *   <li>{@code numeraire-mismatch}：被划的格里有市场行、而它的计价币 ≠ 目标区法定币（同一格上不许有两种"这格用什么钱"； 先 {@code
 *       economy.SetMarketNumeraire} 把它改成目标区法定币）。
 * </ol>
 *
 * <p>★ <b>半径不参与归属</b>（{@code MarketZone.radiusHex} 只是声明值，供邻接判据与读数）：本命令<b>不</b>改半径 ——
 * 成员格变了而半径照旧，是刻意留白（"改半径顺带改归属"这条路被堵死，两套逻辑不同时说了算）。
 *
 * <p>★ <b>GM-only</b>：同 {@code economy.DefineMarketZone}（世界级区表没有单格资源目标 ⇒ {@link
 * CommandTargets#targetPaths} 恒空）。
 */
public final class EconomyReassignZoneHexesHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.ReassignZoneHexes";

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
      return rejected("bad-payload", null, null, EconomyCommandPayloads.logReason(e.getMessage()));
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected(
            "economy-not-activated", definition, null, "economy 切片尚未激活（先 economy.Seed 播种）");
      }
      MarketZoneId fromId = new MarketZoneId(definition.fromZoneId());
      MarketZoneId toId = new MarketZoneId(definition.toZoneId());
      if (fromId.equals(toId)) {
        return rejected("same-zone", definition, null, "源区与目标区相同（不是划界，是 no-op）: " + fromId.value());
      }
      MarketZone from = base.marketZones().get(fromId);
      if (from == null) {
        return rejected(
            "zone-not-found", definition, null, "源区不存在: " + fromId.value() + "（现有一区表见日志）");
      }
      MarketZone to = base.marketZones().get(toId);
      if (to == null) {
        return rejected("zone-not-found", definition, null, "目标区不存在: " + toId.value());
      }
      Set<HexCoord> moving = new LinkedHashSet<>(definition.hexes());
      for (HexCoord hex : moving) {
        if (!from.hexes().contains(hex)) {
          return rejected(
              "hex-not-in-from-zone",
              definition,
              hex,
              "格 " + hex + " 不属于源区 " + fromId.value() + "（说不出退让的是哪一格）");
        }
      }
      if (moving.containsAll(from.hexes())) {
        return rejected(
            "from-zone-would-be-empty",
            definition,
            from.anchor(),
            "源区 " + fromId.value() + " 的格会被全部划走（区必须至少含锚格；要撤区请走 economy.MergeMarketZones）");
      }
      if (moving.contains(from.anchor())) {
        return rejected(
            "from-zone-anchor-would-move",
            definition,
            from.anchor(),
            "源区 "
                + fromId.value()
                + " 的锚格 "
                + from.anchor()
                + " 在被划走的格里（锚格是取价点；把它留下，或用 MergeMarketZones 撤掉这个区）");
      }
      for (HexCoord hex : moving) {
        Market market = base.markets().get(hex);
        if (market != null && !market.numeraire().equals(to.legalTender())) {
          return rejected(
              "numeraire-mismatch",
              definition,
              hex,
              "格 "
                  + hex
                  + " 的计价币是 "
                  + market.numeraire().value()
                  + "，与目标区 "
                  + toId.value()
                  + " 的法定币 "
                  + to.legalTender().value()
                  + " 不一致（先 economy.SetMarketNumeraire）");
        }
      }
      Set<HexCoord> fromHexes = new LinkedHashSet<>(from.hexes());
      fromHexes.removeAll(moving);
      Set<HexCoord> toHexes = new LinkedHashSet<>(to.hexes());
      toHexes.addAll(moving);
      Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
      zones.put(fromId, from.withHexes(fromHexes, from.anchor(), from.radiusHex()));
      zones.put(toId, to.withHexes(toHexes, to.anchor(), to.radiusHex()));
      EconomyData projected = base.withMarketZones(zones);
      logReassigned(
          from, to, moving, projected.marketZones().get(fromId), projected.marketZones().get(toId));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation", definition, null, EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /** 改划 INFO 一条"发生了什么 + 具名计数"（§一.9）：源/目标区、格数、逐区前后规模。 */
  private static void logReassigned(
      MarketZone from,
      MarketZone to,
      Set<HexCoord> moving,
      MarketZone fromAfter,
      MarketZone toAfter) {
    LOG.info(
        LogEvent.of(
            "MARKET_ZONE_HEXES_REASSIGNED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "fromZone",
            from.zoneId().value(),
            "toZone",
            to.zoneId().value(),
            "movedHexes",
            moving.size(),
            "fromHexCountBefore",
            from.hexCount(),
            "fromHexCountAfter",
            fromAfter.hexCount(),
            "toHexCountBefore",
            to.hexCount(),
            "toHexCountAfter",
            toAfter.hexCount(),
            "legalTender",
            to.legalTender().value()));
    LOG.debug(
        LogEvent.of(
            "MARKET_ZONE_HEXES_REASSIGNED_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "fromZone",
            from.zoneId().value(),
            "toZone",
            to.zoneId().value(),
            "hexes",
            EconomyDefineMarketZoneHandler.describeHexes(moving)));
  }

  /** 具名拒（业务拒绝 = INFO 一条 + DEBUG 一条"为什么"）。 */
  private static HandlerOutcome rejected(
      String reason, Definition definition, HexCoord hex, String message) {
    LOG.info(
        LogEvent.of(
            "MARKET_ZONE_REASSIGN_REJECTED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "command",
            TYPE,
            "reason",
            reason,
            "fromZone",
            definition == null ? "" : definition.fromZoneId(),
            "toZone",
            definition == null ? "" : definition.toZoneId(),
            "hex",
            hex == null ? "" : hex.toString()));
    LOG.debug(
        LogEvent.of(
            "MARKET_ZONE_REASSIGN_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "command",
            TYPE,
            "reason",
            reason,
            "hexCount",
            definition == null ? -1 : definition.hexes().size(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Definition parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String fromZoneId = EconomyCommandPayloads.requireText(TYPE, payload, "fromZoneId");
    String toZoneId = EconomyCommandPayloads.requireText(TYPE, payload, "toZoneId");
    List<HexCoord> hexes = EconomyCommandPayloads.requireHexArray(TYPE, payload, "hexes");
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(fromZoneId, toZoneId, hexes, reason);
  }

  /** 一次改划（两个区 id + 格集 + 可选审计文本）。 */
  private record Definition(
      String fromZoneId, String toZoneId, List<HexCoord> hexes, String reason) {

    Definition {
      Objects.requireNonNull(fromZoneId, "fromZoneId");
      Objects.requireNonNull(toZoneId, "toZoneId");
      Objects.requireNonNull(hexes, "hexes");
      if (fromZoneId.isBlank()) {
        throw new IllegalArgumentException("fromZoneId 不得为空白");
      }
      if (toZoneId.isBlank()) {
        throw new IllegalArgumentException("toZoneId 不得为空白");
      }
      if (hexes.isEmpty()) {
        throw new IllegalArgumentException("hexes 不得为空");
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
