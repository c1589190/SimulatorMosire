package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
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
 *  "legalTender":"copper",          // 必填：本区法定币（必须在世界词表里）—— ★ 区的唯一货币事实
 *  "radiusHex":8,                   // 可选（缺省 0）：声明半径（只用于邻接判据与读数，**不**参与成员格派生）
 *  "reason":"…"}                    // 可选：审计文本（只进日志，不进状态）
 * }</pre>
 *
 * <p>★★ <b>2026-10-09 C 批：不再点名"发行 GOV"</b>（用户原话「法定货币发行者也丢掉」；设计书 §4.1/G1）：载荷没有 {@code govUnitId}，
 * 区里也不存发行者 —— "谁管这种钱"由 {@code Government.issuable} <b>反查</b>回答（{@code
 * MarketZoneBook.possibleIssuersOf}）。 ★ 载荷若仍带 {@code govUnitId} ⇒ <b>具名拒 {@code
 * bad-payload}</b>（fail-closed 的退役键处理：绝不"算了却被忽略"）。
 *
 * <p>★★ <b>它是"市场区成为持久状态"的入口</b>：B2 之前市场区是纯派生件（城市 + tier 半径每轮现算），"这个 hex 属于哪个区"每次现算 ⇒
 * 划界/退让/覆盖/合并都没有可写的对象。本命令把"成员格"落成状态；非空区表之后，{@code MarketTopologyBook} 的成员格<b>由它给定</b>
 * （单一权威，I22），派生路径退化为空表时的默认值。
 *
 * <p>★★ <b>六条具名拒（全部 fail-closed，一条都不许静默）</b>：
 *
 * <ol>
 *   <li>{@code bad-payload}：载荷形状/边界（空 hexes、缺字段、非法格；★ 含"仍带退役的 {@code govUnitId}"）；
 *   <li>{@code economy-not-activated}：经济切片未激活；
 *   <li>{@code zone-already-defined}：区 id 已存在（改成员走 {@code ReassignZoneHexes} / {@code
 *       MergeMarketZones}）；
 *   <li>{@code anchor-not-in-hexes}：锚格不在成员格里（{@code MarketZone} 的构造期守卫同款，此处提前成具名拒）；
 *   <li>{@code anchor-missing-market}：锚格没有市场行（"这一格按什么钱报价"说不出来；先 {@code economy.SetMarketPrice}）；
 *   <li>{@code currency-not-defined}：法定币不在世界词表里；
 *   <li>{@code hex-in-other-zone}：某个成员格已经在别的区里（I22：一个 hex 至多属于一个区；先把它从那个区划出去）—— ★
 *       排在"这个区本身长什么样"的四条判据之后：先问"你立的是什么区"，再问"这些格腾出来了吗"；
 *   <li>{@code numeraire-mismatch}：某个**已有市场行**的成员格的计价币 ≠ 本区法定币（同一格上的两种"这格用什么钱"不能并存；
 *       先把该格改成法定币：{@code economy.SetMarketNumeraire}）。
 * </ol>
 *
 * <p>★★ <b>2026-10-09 C 批删掉的两条</b>（用户 §1.6「肯定不管」；设计书 §4.3）：{@code gov-not-registered} 与 {@code
 * currency-not-issuable} —— 立区<b>不管"谁发得出"</b>：区只是法律事实（G4），发不出就没人能换、后果自负。 ★
 * <b>不新增</b>"至少有一个政府能发行该法定币"之类的校验（§4.3 明文）。
 *
 * <p>★★ <b>GM-only</b>：本命令不打 {@code DmUsableCommand}（照 {@code economy.SetOfficialRate} / {@code
 * economy.DefineCurrency}） —— 划界是货币制度面的事实，不进决策人令 / {@code RegisterEffect} / 决策人命令目录。★
 * 决策人侧的"管理本国市场区"（用户 §1.2 的 口岸政策语境）需要身份派生的作用域，与口岸/管制一起做（阶段 3），本批**不**给决策人桶开这条口子（权限不得因为新增命令而放大）。
 *
 * <p>★ <b>不实现 {@link CommandTargets}</b>：区表是<b>世界级</b>表（一份区表横跨多个格，但"区"本身不是格资源），且本命令 GM-only ⇒
 * 不进决策令桶（与 {@code SetOfficialRate} / {@code DefineCurrency} 同款，{@code targetPaths} 恒空）。
 */
public final class EconomyDefineMarketZoneHandler implements CommandHandler, CommandTargets {

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
      // ★★ W1：锚格必须有市场（"说不出本区按什么钱报价"这条拒绝语义逐字保留）—— 新形状下"有市场的格"
      //   由 ①持久区成员格 ②派生价表成员格 给出；本命令只建**新区**，故只看派生价表。
      MarketTable anchorSource = null;
      for (MarketTable table : base.marketTables().values()) {
        if (table.hexes().contains(definition.anchor())) {
          anchorSource = table;
          break;
        }
      }
      if (anchorSource == null) {
        return rejected(
            "anchor-missing-market",
            definition,
            definition.anchor(),
            "锚格没有市场（说不出本区按什么钱报价；先 economy.Seed 播种该格，或把它并入一个已有的区）: " + definition.anchor(),
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
      // ★★ W1（逐格 → 逐区）：新区的价表由"这些格原来所在的那张派生价表"改建而来 ⇒ 三件事必须成立：
      //   ① 每张被改到的派生价表的计价币 = 本区法定币（否则是静默换汇，I17）；② 被改到的派生价表的价目
      //   必须彼此一致（新区只有一张表，冲突无法同时成立）；③ 改完之后该派生的剩余成员格（若有）另成一张表。
      Set<MarketZoneId> sourceIds = new LinkedHashSet<>();
      for (HexCoord hex : definition.hexes()) {
        for (Map.Entry<MarketZoneId, MarketTable> entry : base.marketTables().entrySet()) {
          if (entry.getValue().hexes().contains(hex)) {
            sourceIds.add(entry.getKey());
          }
        }
      }
      Map<CommodityId, Long> zonePrices = null;
      for (MarketZoneId sourceId : sourceIds) {
        MarketTable source = base.marketTables().get(sourceId);
        if (!source.numeraire().equals(legalTender)) {
          return rejected(
              "numeraire-mismatch",
              definition,
              definition.anchor(),
              "格所属的市场 "
                  + sourceId.value()
                  + " 的计价币是 "
                  + source.numeraire().value()
                  + "，与本区法定币 "
                  + legalTender.value()
                  + " 不一致（同一片地方不许有两种\"这里用什么钱\"；先 economy.SetMarketNumeraire）",
              base);
        }
        if (zonePrices == null) {
          zonePrices = source.prices();
        } else if (!zonePrices.equals(source.prices())) {
          return rejected(
              "prices-differ",
              definition,
              definition.anchor(),
              "本区要覆盖的格来自多张价表且价目不同（每区只能有一张价表，冲突无法同时成立）：市场 "
                  + sourceId.value()
                  + " 的价目与另一张不一致；先 economy.SetMarketPrice 把它们调成同一张",
              base);
        }
      }
      // ★ 新区自己一张表（成员格 = 空：持久形态的成员格权威是 MarketZone.hexes）；
      //   来源派生表把本区的格摘走，摘空即删（不留"零成员格的派生市场"——那是坏形状）。
      //   ★★ W1d：价目可能是**空表**（要覆盖的格在改动前就不在任何价表里）⇒ 那个区**不交易任何商品**
      //      （设计书 §4.2 N-4 的口径），这是合法目标态，不是"缺表"。表仍要建：本命令的契约是"区 + 它的表"
      //      一次落地（表在、价目空 = 明确不交易；两件事在读数与结算上等价，但形状上"表在"让 V 形态可分辨）。
      // ★★ P4fix2：来源派生市场表的格被摘走/整表删掉时，挂在该 sourceId 下的工资锚必须同步重键到新持久区
      //   zoneId —— 否则锚键指向的派生表已不存在，跨片校验报 `wage-anchor-market-zone-missing`。
      //   逐锚判据：
      //     ① key.marketZone() ∈ sourceIds；
      //     ② 该来源表被整张摘空 ⇒ 该表下全部锚重键（含 IndustryHexKeys.hexKeyOf 为空的"不绑地点"方案）；
      //        否则仅当 key.industry() 的 hexKey 落在 definition.hexes() 内才重键（未迁走的格仍留在原表，
      //        锚继续随原表合法存在）。
      //   新键 = (zoneId, industry, role)，值逐字段不变；目标键已有同值 ⇒ 合并去重，已有异值 ⇒ fail-closed。
      Map<MarketZoneId, MarketTable> marketTables = new LinkedHashMap<>(base.marketTables());
      Set<MarketZoneId> emptiedSourceIds = new LinkedHashSet<>();
      for (MarketZoneId sourceId : sourceIds) {
        MarketTable source = marketTables.get(sourceId);
        Set<HexCoord> remaining = new LinkedHashSet<>(source.hexes());
        remaining.removeAll(definition.hexes());
        if (remaining.isEmpty()) {
          emptiedSourceIds.add(sourceId);
          marketTables.remove(sourceId);
        } else {
          marketTables.put(sourceId, source.withHexes(remaining));
        }
      }
      Set<String> definitionHexKeys = new LinkedHashSet<>();
      for (HexCoord hex : definition.hexes()) {
        definitionHexKeys.add(hex.toString());
      }
      Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors =
          new LinkedHashMap<>(base.productionWageAnchors());
      int rekeyedAnchors = 0;
      for (Map.Entry<WageAnchorKey, ProductionWageAnchor> entry :
          new ArrayList<>(productionWageAnchors.entrySet())) {
        WageAnchorKey key = entry.getKey();
        MarketZoneId sourceId = key.marketZone();
        if (!sourceIds.contains(sourceId)) {
          continue;
        }
        boolean wholeTableEmptied = emptiedSourceIds.contains(sourceId);
        boolean industryHexMigrated =
            IndustryHexKeys.hexKeyOf(key.industry())
                .filter(definitionHexKeys::contains)
                .isPresent();
        if (!wholeTableEmptied && !industryHexMigrated) {
          continue;
        }
        WageAnchorKey rekeyedKey = new WageAnchorKey(zoneId, key.industry(), key.role());
        ProductionWageAnchor existing = productionWageAnchors.get(rekeyedKey);
        if (existing != null && !existing.equals(entry.getValue())) {
          return rejected(
              "wage-anchor-rekey-conflict",
              definition,
              definition.anchor(),
              "工资锚重键冲突：来源市场 "
                  + sourceId.value()
                  + " 的锚 "
                  + key
                  + " 要重键到 "
                  + rekeyedKey
                  + "，但目标键已有不同值的锚（不得静默覆盖）",
              base);
        }
        productionWageAnchors.remove(key);
        if (existing == null) {
          productionWageAnchors.put(rekeyedKey, entry.getValue());
        }
        rekeyedAnchors++;
      }
      marketTables.put(
          zoneId,
          new MarketTable(legalTender, Set.of(), zonePrices == null ? Map.of() : zonePrices));
      MarketZone zone =
          new MarketZone(
              zoneId,
              definition.anchor(),
              definition.radiusHex(),
              new LinkedHashSet<>(definition.hexes()),
              legalTender,
              Map.of());
      Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
      zones.put(zoneId, zone);
      // ★★ W1d（2026-10-11）：**区表与价表同一次落地**（原子写口）—— 拆成两个单组件 wither 时中间态会被
      //   构造期跨表守卫拒掉（`withMarketZones` 先 ⇒ 新区的表还没进；`withMarketTables` 先 ⇒ 该键还不是区、
      //   被读成"派生市场必须有成员格"）。W1 正是踩在这条上：`economy.DefineMarketZone` 恒被自己的守卫拒成
      //   `contract-violation`（W1c 账本 §四-2 的调用序证据），三国世界的市场区播种因此整条走不通。
      //   ★ 目标态不变：新区 + 它的表（计价币 = 该区法定币、成员格 = 空）与来源派生表的摘格在同一次构造里成立。
      //   ★★ P4fix2：**工资锚表必须与区表/价表同一次构造** —— 拆开写会让中间态的锚指向已删的派生表
      //   （`wage-anchor-market-zone-missing`）；三组件一次交给同一个 Builder，中间态不经过构造守卫。
      EconomyData projected =
          base.withMarketZonesTablesAndWageAnchors(zones, marketTables, productionWageAnchors);
      logDefined(zone, base.marketZones().size(), projected.marketZones().size());
      logWageAnchorsRekeyed(
          zoneId, sourceIds, definition.hexes(), rekeyedAnchors, definition.reason());
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

  /** 区定义 INFO 一条"发生了什么 + 具名计数"（§一.9）：区 id/锚格/格数/法定币/半径/区表规模。 */
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

  /**
   * ★★★ D-032 P4fix2：工资锚重键 INFO（唯一 logger 门面）：新持久区 / 来源派生表 / 迁入格 / 实际重键锚数 / 审计原因。
   *
   * <p>{@code rekeyedAnchors} 是真正换了键的锚条数（含"目标键已有同值 ⇒ 合并去重"的条目）；0 也照记，便于 验证"本命令确实扫描过来源表、只是没有锚需要搬"。
   */
  private static void logWageAnchorsRekeyed(
      MarketZoneId zoneId,
      Set<MarketZoneId> sourceIds,
      List<HexCoord> hexes,
      int rekeyedAnchors,
      String reason) {
    LOG.info(
        LogEvent.of(
            "ECONOMY_DEFINE_MARKET_ZONE_WAGE_ANCHORS_REKEYED",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "zoneId",
            zoneId.value(),
            "sourceIds",
            describeZoneIds(sourceIds),
            "hexes",
            describeHexes(new LinkedHashSet<>(hexes)),
            "rekeyedAnchors",
            rekeyedAnchors,
            "reason",
            reason == null ? "define-market-zone" : reason));
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

  /** 来源市场 id 集的稳定可读串（保序；只输出 id，不输出任何载荷）。 */
  private static String describeZoneIds(Set<MarketZoneId> zoneIds) {
    StringBuilder text = new StringBuilder();
    for (MarketZoneId zoneId : zoneIds) {
      if (text.length() > 0) {
        text.append(',');
      }
      text.append(zoneId.value());
    }
    return text.toString();
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Definition parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    // ★★ C 批退役键的 fail-closed 处理：区不再记发行政府（用户「法定货币发行者也丢掉」；设计书 §4.1/G1）。
    //   旧载荷若仍带 govUnitId ⇒ 具名拒（绝不静默忽略一个"以为还算数"的字段）。谁管这种钱由 issuable 反查回答。
    if (payload.hasNonNull("govUnitId")) {
      throw new IllegalArgumentException(
          TYPE + " 的 govUnitId 已退役（区不再记发行政府：谁管这种钱由 Government.issuable 反查）");
    }
    String zoneId = EconomyCommandPayloads.requireText(TYPE, payload, "zoneId");
    HexCoord anchor = EconomyCommandPayloads.requireHex(TYPE, payload, "anchor");
    List<HexCoord> hexes = EconomyCommandPayloads.requireHexArray(TYPE, payload, "hexes");
    String legalTender = EconomyCommandPayloads.requireText(TYPE, payload, "legalTender");
    int radiusHex = EconomyCommandPayloads.optionalInt(TYPE, payload, "radiusHex", 0);
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(zoneId, anchor, hexes, legalTender, radiusHex, reason);
  }

  /** 一条区定义（三个业务字段 + 格集 + 可选半径/审计文本；边界在构造期判完，与 {@code MarketZone} 的守卫同一口径）。 */
  private record Definition(
      String zoneId,
      HexCoord anchor,
      List<HexCoord> hexes,
      String legalTender,
      int radiusHex,
      String reason) {

    Definition {
      Objects.requireNonNull(zoneId, "zoneId");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(hexes, "hexes");
      Objects.requireNonNull(legalTender, "legalTender");
      if (zoneId.isBlank()) {
        throw new IllegalArgumentException("zoneId 不得为空白");
      }
      if (hexes.isEmpty()) {
        throw new IllegalArgumentException("hexes 不得为空（一个区至少含锚格）");
      }
      if (legalTender.isBlank()) {
        throw new IllegalArgumentException("legalTender 不得为空白");
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
