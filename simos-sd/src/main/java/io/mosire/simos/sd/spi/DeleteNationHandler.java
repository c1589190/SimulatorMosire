package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code sd.DeleteNation}（P1.2 后端行政）：删除国家实体，<b>严格引用检查、默认不静默级联</b>。
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
 *   <li>{@link DecisionMaker} 的 {@link Affiliation.Nation} 仍指向该国家 ⇒ 先 {@code
 *       sd.DeleteDecisionMaker}（仅当其名下没有历史 Directive 时可用）或另行处理；★ 若该决策人已被 Directive 引用，当前命令面没有"删历史
 *       Directive"的入口，完整清理路径缺失，见 P1.2 报告；
 *   <li>外交关系边 {@link DiplomaticRelation} 的 from/to 仍指向该国家 ⇒ 可显式给 {@code
 *       clearDiplomaticReferences=true} 随国删除，或先另行清理；
 *   <li>地图上仍有 {@code nation:&lt;id&gt;} tag 的 Region ⇒ 先 {@code map.UpdateRegion} 把 tag 改掉/清掉
 *       （清理路径在 map 自己的命令面；sd 只读 map 做检查，不反向写 map）。
 * </ol>
 *
 * <p>★★ <b>不作为硬引用的两类（如实记录，不假装已清）</b>：{@code SdInfoEntry.affiliations/tags} 是感知层归属标签、 {@link
 * DiplomaticEvent#participants()} 是历史事件记录；它们都不是 {@code SdState} 构造期强制的引用。删国后这些历史条目
 * <b>保留原文</b>（不篡改留痕），读侧按既有 fail-closed 口径处理"指向不存在国家"的标签。若要连历史事件一起清，显式给 {@code
 * clearDiplomaticReferences=true}（连关系边与外交事件一并删）。
 *
 * <p>★ <b>GM-only</b>：删国是行政原语，不开放给决策令直调。
 */
public final class DeleteNationHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  private static final Logger LOG = SdLog.nation();

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "sd.DeleteNation";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SdPayloads.parse(payloadJson);
    NationId id = NationId.parse(SdPayloads.requireText(payload, "nationId"));
    return List.of(ResourcePaths.sd("nation", id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      NationId id = NationId.parse(SdPayloads.requireText(payload, "nationId"));
      boolean clearDiplomatic = optionalBoolean(payload, "clearDiplomaticReferences", false);
      if (!base.nations().containsKey(id)) {
        return new HandlerOutcome.Rejected("国家不存在: " + id);
      }

      List<String> problems = new ArrayList<>();
      List<DecisionMakerId> decisionMakers = decisionMakersOf(base, id);
      if (!decisionMakers.isEmpty()) {
        problems.add(
            "决策人绑定 "
                + decisionMakers.size()
                + " 个（"
                + summarize(decisionMakers)
                + "）：先 sd.DeleteDecisionMaker（仅当其无历史 Directive 引用时可用）；若已被 Directive 引用，"
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
        return new HandlerOutcome.Rejected(
            "国家 " + id + " 引用未清，拒绝删除（不静默级联）：\n- " + String.join("\n- ", problems));
      }

      Map<NationId, Nation> nations = new LinkedHashMap<>(base.nations());
      nations.remove(id);
      SdState next = base.withNations(nations);
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
      LOG.info("event=SD_NATION_DELETED id={} clearDiplomatic={}", id.value(), clearDiplomatic);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static List<DecisionMakerId> decisionMakersOf(SdState base, NationId id) {
    List<DecisionMakerId> ids = new ArrayList<>();
    for (DecisionMaker maker : base.decisionMakers().values()) {
      if (maker.affiliation() instanceof Affiliation.Nation nation
          && nation.nationId().equals(id)) {
        ids.add(maker.id());
      }
    }
    ids.sort((a, b) -> a.value().compareTo(b.value()));
    return List.copyOf(ids);
  }

  private static List<String> diplomaticRelationKeys(SdState base, NationId id) {
    List<String> ids = new ArrayList<>();
    for (DiplomaticRelationKey key : base.diplomaticRelations().keySet()) {
      if (key.from().equals(id) || key.to().equals(id)) {
        ids.add(key.toString());
      }
    }
    ids.sort(String::compareTo);
    return List.copyOf(ids);
  }

  private static List<DiplomaticEventId> diplomaticEvents(SdState base, NationId id) {
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
    GameMap map = SdSnapshots.map(state);
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
