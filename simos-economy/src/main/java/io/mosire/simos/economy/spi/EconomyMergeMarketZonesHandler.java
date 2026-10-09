package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.fx.OfficialRate;
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
 * ★★ {@code economy.MergeMarketZones}（阶段 2-B2，2026-10-08；约束设计书 §4.2 / 用户 §1.2「合并」）：<b>把源区并入目标区</b>
 * —— 源区的成员格与区级官方汇率覆盖一并并进目标区，<b>源区随之撤销</b>；一条命令 = 一条 revision（铁律 2）。
 *
 * <pre>{@code
 * {"sourceZoneId":"c-tp-gold",     // 必填：被并入并撤销的区
 *  "targetZoneId":"c-tp-copper",   // 必填：保留的区（≠ source）
 *  "reason":"…"}                   // 可选：审计文本（只进日志，不进状态）
 * }</pre>
 *
 * <p>★★ <b>"撤区"只有这一条路</b>：{@code ReassignZoneHexes} 明令拒"把源区的格全部划走"（那会留下一个没有成员格的空壳区），
 * 于是"这个区不要了"必须表达成"并进哪个区" —— 合并是一条<b>有明确归属去向</b>的动作，"删掉"不是（用户 §1.2 的原话是"合并"， 本批照它做，不另造一条 delete 命令）。
 *
 * <p>★★ <b>五条具名拒（fail-closed）</b>：
 *
 * <ol>
 *   <li>{@code bad-payload}：形状（缺字段/空白 id）；
 *   <li>{@code economy-not-activated}；
 *   <li>{@code zone-not-found}：源区/目标区不存在（点名是哪一个）；
 *   <li>{@code same-zone}：源区 == 目标区；
 *   <li>{@code numeraire-mismatch}：被并入的某个格（凡有市场行者）的计价币 ≠ 目标区法定币（先 {@code
 *       economy.SetMarketNumeraire} 显式改成目标币；合并**不做**静默换汇 —— I17/I24。跨币合并在三步之内可达，
 *       而"两区法定币必须相同"那种更强的守卫会把合并在这个批次的 three-powers 世界里彻底堵死）；
 *   <li>{@code official-rate-conflict}：两区对<b>同一币对</b>都有区级覆盖且数值不同（两个政策价不能同时成立；先统一其中一个， 相同值则自然合并）。
 * </ol>
 *
 * <p>★ <b>合并后的形状</b>：目标区 = 目标区原有格 ∪ 源区格（<b>规范序</b>由 {@code MarketZone} 构造期保证），锚格与法定币取
 * 目标区（**目标区的钱成为合并区的法定币**；★ 2026-10-09 C 批起区里不再有"发行者"一栏 —— 谁管这种钱由 {@code Government.issuable}
 * 反查回答），声明半径 = 两区半径的较大者（不因合并而缩小可达判据），区级汇率覆盖 = 两区的并集 （冲突已在上面拒掉）。
 *
 * <p>★ <b>GM-only</b>：同 {@code economy.DefineMarketZone}（世界级区表没有单格资源目标 ⇒ {@link
 * CommandTargets#targetPaths} 恒空）。
 */
public final class EconomyMergeMarketZonesHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.MergeMarketZones";

  private static final LogChannel LOG = EventLog.channel(EconomyLog.command());

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson); // 形状校验（缺字段/空白 id ⇒ 抛具名载荷错）；区表没有单格资源目标。
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
          "bad-payload", null, null, null, EconomyCommandPayloads.logReason(e.getMessage()), base);
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected(
            "economy-not-activated",
            definition,
            null,
            null,
            "economy 切片尚未激活（先 economy.Seed 播种）",
            base);
      }
      MarketZoneId sourceId = new MarketZoneId(definition.sourceZoneId());
      MarketZoneId targetId = new MarketZoneId(definition.targetZoneId());
      if (sourceId.equals(targetId)) {
        return rejected("same-zone", definition, null, null, "源区与目标区相同: " + sourceId.value(), base);
      }
      MarketZone source = base.marketZones().get(sourceId);
      if (source == null) {
        return rejected(
            "zone-not-found", definition, null, null, "源区不存在: " + sourceId.value(), base);
      }
      MarketZone target = base.marketZones().get(targetId);
      if (target == null) {
        return rejected(
            "zone-not-found", definition, null, null, "目标区不存在: " + targetId.value(), base);
      }
      // ★★ 合并的守卫是**逐格**的（不是"两区法定币必须相同"）：合并后的区只认目标区的法定币 ⇒ 被并入的每一格
      //   （凡有市场行者）的计价币必须已经是目标区法定币。这样"跨币合并"仍然可达（先 economy.SetMarketNumeraire
      //   把那些格改成目标币，再合并），但**没有任何一次静默换汇**（I17/I24）—— 而"两区法定币必须相同"会把合并
      //   在 three-powers 这种"三币三区、每格都被覆盖"的世界里彻底堵死（那正是本批要服务的世界）。
      for (HexCoord hex : source.hexes()) {
        Market market = base.markets().get(hex);
        if (market != null && !market.numeraire().equals(target.legalTender())) {
          return rejected(
              "numeraire-mismatch",
              definition,
              source,
              target,
              "被并入的格 "
                  + hex
                  + " 的计价币是 "
                  + market.numeraire().value()
                  + "，与目标区 "
                  + targetId.value()
                  + " 的法定币 "
                  + target.legalTender().value()
                  + " 不一致（先 economy.SetMarketNumeraire 改那些格；合并不做静默换汇）",
              base);
        }
      }
      Map<String, OfficialRate> mergedRates = new LinkedHashMap<>(target.officialRates());
      for (Map.Entry<String, OfficialRate> entry : source.officialRates().entrySet()) {
        OfficialRate existing = mergedRates.get(entry.getKey());
        if (existing != null && !existing.equals(entry.getValue())) {
          return rejected(
              "official-rate-conflict",
              definition,
              source,
              target,
              "两区对币对 "
                  + entry.getKey()
                  + " 都有区级官方汇率且数值不同（源="
                  + entry.getValue().buyPerMille()
                  + "/"
                  + entry.getValue().sellPerMille()
                  + " 目标="
                  + existing.buyPerMille()
                  + "/"
                  + existing.sellPerMille()
                  + "）；两个政策价不能同时成立，先统一其中一个",
              base);
        }
        mergedRates.put(entry.getKey(), entry.getValue());
      }
      Set<HexCoord> mergedHexes = new LinkedHashSet<>(target.hexes());
      mergedHexes.addAll(source.hexes());
      int mergedRadius = Math.max(target.radiusHex(), source.radiusHex());
      MarketZone merged =
          new MarketZone(
                  targetId,
                  target.anchor(),
                  mergedRadius,
                  mergedHexes,
                  target.legalTender(),
                  Map.of())
              .withOfficialRates(mergedRates);
      Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
      zones.remove(sourceId);
      zones.put(targetId, merged);
      EconomyData projected = base.withMarketZones(zones);
      logMerged(source, target, merged);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation",
          definition,
          null,
          null,
          EconomyCommandPayloads.logReason(e.getMessage()),
          base);
    }
  }

  /** 合并 INFO 一条"发生了什么 + 具名计数"（§一.9）：源/目标区、并入格数、撤掉的区、区表规模前后。 */
  private static void logMerged(MarketZone source, MarketZone target, MarketZone merged) {
    LOG.info(
        LogEvent.of(
            "MARKET_ZONE_MERGED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "sourceZone",
            source.zoneId().value(),
            "targetZone",
            target.zoneId().value(),
            "sourceHexCount",
            source.hexCount(),
            "targetHexCountBefore",
            target.hexCount(),
            "mergedHexCount",
            merged.hexCount(),
            "targetZoneRemoved",
            source.zoneId().value(),
            "legalTender",
            merged.legalTender().value(),
            "radiusHex",
            merged.radiusHex(),
            "officialRates",
            merged.officialRates().size()));
    LOG.debug(
        LogEvent.of(
            "MARKET_ZONE_MERGED_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "sourceZone",
            source.zoneId().value(),
            "targetZone",
            target.zoneId().value(),
            "sourceHexes",
            EconomyDefineMarketZoneHandler.describeHexes(source.hexes()),
            "targetAnchor",
            target.anchor().toString()));
  }

  /** 具名拒（业务拒绝 = INFO 一条 + DEBUG 一条"为什么"）。 */
  private static HandlerOutcome rejected(
      String reason,
      Definition definition,
      MarketZone source,
      MarketZone target,
      String message,
      EconomyData base) {
    LOG.info(
        LogEvent.of(
            "MARKET_ZONE_MERGE_REJECTED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "command",
            TYPE,
            "reason",
            reason,
            "sourceZone",
            definition == null ? "" : definition.sourceZoneId(),
            "targetZone",
            definition == null ? "" : definition.targetZoneId()));
    LOG.debug(
        LogEvent.of(
            "MARKET_ZONE_MERGE_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "command",
            TYPE,
            "reason",
            reason,
            "sourceHexCount",
            source == null ? -1 : source.hexCount(),
            "targetHexCount",
            target == null ? -1 : target.hexCount(),
            "sourceLegalTender",
            source == null ? "" : source.legalTender().value(),
            "targetLegalTender",
            target == null ? "" : target.legalTender().value(),
            "zonesInWorld",
            base == null ? -1 : base.marketZones().size(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Definition parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String sourceZoneId = EconomyCommandPayloads.requireText(TYPE, payload, "sourceZoneId");
    String targetZoneId = EconomyCommandPayloads.requireText(TYPE, payload, "targetZoneId");
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(sourceZoneId, targetZoneId, reason);
  }

  /** 一次合并（两个区 id + 可选审计文本）。 */
  private record Definition(String sourceZoneId, String targetZoneId, String reason) {

    Definition {
      Objects.requireNonNull(sourceZoneId, "sourceZoneId");
      Objects.requireNonNull(targetZoneId, "targetZoneId");
      if (sourceZoneId.isBlank()) {
        throw new IllegalArgumentException("sourceZoneId 不得为空白");
      }
      if (targetZoneId.isBlank()) {
        throw new IllegalArgumentException("targetZoneId 不得为空白");
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
