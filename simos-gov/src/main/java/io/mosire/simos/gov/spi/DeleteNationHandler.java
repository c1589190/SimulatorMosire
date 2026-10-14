package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.gov.nation.DiplomaticEvent;
import io.mosire.simos.gov.nation.DiplomaticEventId;
import io.mosire.simos.gov.nation.DiplomaticRelation;
import io.mosire.simos.gov.nation.DiplomaticRelationKey;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code gov.DeleteNation}（P1.2 后端行政）：删除国家实体，<b>严格引用检查、默认不静默级联</b>。
 *
 * <pre>{@code
 * {"nationId":"西陵"}
 * // 确有外交关系要一起清（显式、非默认）：
 * {"nationId":"西陵","clearDiplomaticReferences":true}
 * }</pre>
 *
 * <p>★★ <b>默认拒绝的硬引用（逐类具名报数）</b>：
 *
 * <ol>
 *   <li><b>决策人的国家归属</b>仍指向该国家 ⇒ 先 {@code ga.DeleteDecisionMaker}（仅当其名下没有历史 Directive 时可用）或另行处理；★
 *       若该决策人已被 Directive 引用，当前命令面没有"删历史 Directive"的入口，完整清理路径缺失，见 P1.2 报告。 ★ <b>乙收尾（本批）起这条判据读 gov
 *       自己的归属表</b>：表本体就在本切片（{@code GovState.nationAffiliations()}，写口 = {@code
 *       gov.AssignDecisionMakerNation}）⇒ 经 {@link NationAffiliations#resolve} 裁一遍（"这个国家还在吗"用本站的
 *       {@code nations}），<b>不再需要任何端口</b>（3c 的那个 {@code NationAffiliationLookup} 随本批删除 —— 它存在的唯一理由
 *       "决策人记录住 ga、gov 看不见"已随归属归模块消失）；
 *   <li>外交关系边 {@link DiplomaticRelation} 的 from/to 仍指向该国家 ⇒ 可显式给 {@code
 *       clearDiplomaticReferences=true} 随国删除，或先另行清理；
 *   <li>地图上仍有 {@code nation:&lt;id&gt;} tag 的 Region ⇒ 先 {@code map.UpdateRegion} 把 tag 改掉/清掉
 *       （清理路径在 map 自己的命令面；gov 只读 map 做检查，不反向写 map）。
 * </ol>
 *
 * <p>★★ <b>不作为硬引用的两类（如实记录，不假装已清）</b>：{@code SdInfoEntry.affiliations/tags} 是感知层归属标签（{@code info}
 * 覆盖层住 {@code GaState}，本命令只读归属裁定、不碰它）、 {@link DiplomaticEvent#participants()} 是历史事件记录；它们都不是 {@code
 * GovState} 构造期强制的引用。删国后这些历史条目 <b>保留原文</b>（不篡改留痕），读侧按既有 fail-closed 口径处理"指向不存在国家"的标签。若要连历史事件一起清，显式给
 * {@code clearDiplomaticReferences=true}（连关系边与外交事件一并删）。
 *
 * <p>★ <b>GM-only</b>：删国是行政原语，不开放给决策令直调。
 *
 * <p>★★ <b>2026-10-12 批次 3c：本类自 {@code simos-sd/sd.spi} 迁入 {@code simos-gov/spi}，命令名 {@code
 * sd.DeleteNation} → {@code gov.DeleteNation}</b>（外交是 gov 的事）。语义/载荷/拒绝文案一字未改，变的只有：①落点（{@code
 * GovChangeSet} 的外交三表，含 {@code nations}）；②载荷与切片助手改本模块；③日志门面。
 *
 * <p>★★ <b>2026-10-12 乙收尾：构造器回到无参</b>——第 1 条硬引用的读法从"经端口问 app"改成"直接读本站的归属表"（见上）。
 */
public final class DeleteNationHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "gov.DeleteNation";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = GovPayloads.parse(payloadJson);
    NationId id = NationId.parse(GovPayloads.requireText(payload, "nationId"));
    // ★ 路径不含命名空间：{@code CommandTargets} 的默认实现按**命令类型第一段**取命名空间 ⇒ 本命令是
    //   `gov:nation/<id>`（改名前是 `sd:nation/<id>`）。{@link ResourcePaths#sd} 只是"<kind>/<id>"这条路径语法的
    //   助手（util 侧不含任何领域命名空间，也不许我在这里加一个 —— 见批次 3c 的文件所有权）。
    return List.of(ResourcePaths.ga("nation", id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GovState base = GovSnapshots.of(state);
    String nationForLog = null;
    try {
      JsonNode payload = GovPayloads.parse(payloadJson);
      NationId id = NationId.parse(GovPayloads.requireText(payload, "nationId"));
      nationForLog = id.value();
      boolean clearDiplomatic = optionalBoolean(payload, "clearDiplomaticReferences", false);
      if (!base.nations().containsKey(id)) {
        return rejected("国家不存在: " + id, "nation", nationForLog);
      }

      List<String> problems = new ArrayList<>();
      // ★ 乙收尾：归属表就在本切片 ⇒ 不再有"跨切片读决策人记录"那一步，也没有端口。
      NationAffiliations affiliations =
          NationAffiliations.resolve(base.nationAffiliations(), base.nations());
      List<DecisionMakerId> decisionMakers = decisionMakersOf(affiliations, id);
      if (!decisionMakers.isEmpty()) {
        problems.add(
            "决策人绑定 "
                + decisionMakers.size()
                + " 个（"
                + summarize(decisionMakers)
                + "）：先 ga.DeleteDecisionMaker（仅当其无历史 Directive 引用时可用）；若已被 Directive 引用，"
                + "当前命令面没有删历史 Directive 的入口");
      }
      List<String> relations = diplomaticRelationKeys(base, id);
      List<DiplomaticEventId> events = diplomaticEvents(base, id);
      if (!clearDiplomatic && !relations.isEmpty()) {
        problems.add(
            "外交关系边 "
                + relations.size()
                + " 条（"
                + summarize(relations)
                + "）：显式给 clearDiplomaticReferences=true 连关系一起删，或先另行清理");
      }
      if (!clearDiplomatic && !events.isEmpty()) {
        problems.add(
            "外交事件记录 "
                + events.size()
                + " 条（"
                + summarize(events)
                + "）：显式给 clearDiplomaticReferences=true 连事件一起删");
      }
      List<RegionId> taggedRegions = nationTaggedRegions(state, id);
      if (!taggedRegions.isEmpty()) {
        problems.add(
            "map 上仍有 nation tag 的 Region "
                + taggedRegions.size()
                + " 个（"
                + summarize(taggedRegions)
                + "）：先 map.UpdateRegion 改掉这些 tag/归属");
      }
      if (!problems.isEmpty()) {
        return rejected(
            "国家 " + id + " 引用未清，拒绝删除（不静默级联）：\n- " + String.join("\n- ", problems),
            "nation",
            nationForLog);
      }

      Map<NationId, Nation> nations = new LinkedHashMap<>(base.nations());
      nations.remove(id);
      GovState next = base.withNations(nations);
      if (clearDiplomatic) {
        Map<DiplomaticRelationKey, DiplomaticRelation> remainingRelations =
            new LinkedHashMap<>(base.diplomaticRelations());
        for (DiplomaticRelationKey key : base.diplomaticRelations().keySet()) {
          if (key.from().equals(id) || key.to().equals(id)) {
            remainingRelations.remove(key);
          }
        }
        next = next.withDiplomaticRelations(remainingRelations);

        Map<DiplomaticEventId, DiplomaticEvent> remainingEvents =
            new LinkedHashMap<>(base.diplomaticEvents());
        for (Map.Entry<DiplomaticEventId, DiplomaticEvent> entry :
            base.diplomaticEvents().entrySet()) {
          if (entry.getValue().participants().contains(id)) {
            remainingEvents.remove(entry.getKey());
          }
        }
        next = next.withDiplomaticEvents(remainingEvents);
      }
      EventLog.channel(GovLog.nation())
          .info(
              LogEvent.of(
                  "SD_NATION_DELETED",
                  GovLogSource.GOV_NATION,
                  "id",
                  id.value(),
                  "clearDiplomatic",
                  clearDiplomatic,
                  "relationsCleared",
                  clearDiplomatic ? relations.size() : 0,
                  "eventsCleared",
                  clearDiplomatic ? events.size() : 0,
                  "nations",
                  nations.size()));
      return new HandlerOutcome.Applied(GovChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "nation", nationForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GovPayloads} 的日志安全截断，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GovPayloads.logRejected(
        GovLog.nation(), GovLogSource.GOV_NATION, "SD_DELETE_NATION_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /** 归属该国家的决策人 id（升序）——判据来自 {@link NationAffiliations}（gov 自己的归属表 + 本切片国家表的裁定）。 */
  private static List<DecisionMakerId> decisionMakersOf(
      NationAffiliations affiliations, NationId id) {
    List<DecisionMakerId> ids = new ArrayList<>();
    for (Map.Entry<DecisionMakerId, NationId> entry : affiliations.byDecisionMaker().entrySet()) {
      if (id.equals(entry.getValue())) {
        ids.add(entry.getKey());
      }
    }
    ids.sort((a, b) -> a.value().compareTo(b.value()));
    return List.copyOf(ids);
  }

  private static List<String> diplomaticRelationKeys(GovState base, NationId id) {
    List<String> ids = new ArrayList<>();
    for (DiplomaticRelationKey key : base.diplomaticRelations().keySet()) {
      if (key.from().equals(id) || key.to().equals(id)) {
        ids.add(key.toString());
      }
    }
    ids.sort(String::compareTo);
    return List.copyOf(ids);
  }

  private static List<DiplomaticEventId> diplomaticEvents(GovState base, NationId id) {
    List<DiplomaticEventId> ids = new ArrayList<>();
    for (Map.Entry<DiplomaticEventId, DiplomaticEvent> entry : base.diplomaticEvents().entrySet()) {
      if (entry.getValue().participants().contains(id)) {
        ids.add(entry.getKey());
      }
    }
    ids.sort((a, b) -> a.value().compareTo(b.value()));
    return List.copyOf(ids);
  }

  /** map 里 tag 逐字等于 {@code nation:<id>} 的区域（只读 map；写入由 map 自己的命令负责）。 */
  private static List<RegionId> nationTaggedRegions(SimulationState state, NationId id) {
    GameMap map = GovSnapshots.map(state);
    String expected = NationTag.tagFor(id);
    List<RegionId> ids = new ArrayList<>();
    for (Region region : map.regions().values()) {
      if (expected.equals(region.meta().tag())) {
        ids.add(region.id());
      }
    }
    ids.sort((a, b) -> a.value().compareTo(b.value()));
    return List.copyOf(ids);
  }

  private static boolean optionalBoolean(JsonNode payload, String field, boolean defaultValue) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔: " + payload);
    }
    return value.asBoolean();
  }

  private static String summarize(List<?> ids) {
    if (ids.size() <= 5) {
      return ids.toString();
    }
    return ids.subList(0, 5) + " 等";
  }
}
