package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.CityId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.CityOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code social.DeleteCity} 命令的处理器（P1.2 后端行政）：删除城市节点，并对"城的城镇人口"给出显式两条路。
 *
 * <pre>{@code
 * {"id":"c-old","deletePopulation":false}   // 缺省 false：有城镇批次 ⇒ 严格拒绝
 * {"id":"c-old","deletePopulation":true}    // 显式破坏性清理：连同 urban:<id>: 批次与家户成员关系一起删
 * }</pre>
 *
 * <p>★★ <b>人口派生的处理语义</b>：城的城镇人口是 {@code urban:&lt;cityId&gt;:} 前缀批次的现算量。删掉城市却留下批次，
 * 会让那些批次的前缀指向不存在的城（读口看不见、无法再按城汇总）⇒ 默认<b>严格拒绝</b>，不静默级联。 只有在载荷显式 {@code deletePopulation=true}
 * 时才做连带删除——这是行政区划重建用的破坏性清理，不是默认行为。
 *
 * <p>★ <b>为什么不做"把批次改挂到另一座城"</b>：批次 id 是稳定身份（铁律 1），而且经济侧劳动分配等固定引用该 id；
 * 改名会让身份与引用一起漂。要保留人请保留城市身份，或先评估引用面后另行处理（本命令不假装能安全搬城籍）。
 *
 * <p>★ <b>GM-only</b>。
 */
public final class DeleteCityHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "social.DeleteCity";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷在判目标时照样抛（fail-closed）
    // social 资源语法没有 city 专属路径；删除是身份操作、载荷又不含坐标 ⇒ 没有可寻址目标。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data();
    String cityForLog = null;
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      CityId id = CityId.parse(SocialPayloads.requireText(payload, "id"));
      cityForLog = id.value();
      boolean deletePopulation = SocialPayloads.optionalBoolean(payload, "deletePopulation", false);
      SocialData next = CityOperations.delete(base, id, deletePopulation);
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_DELETE_CITY_APPLIED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "city",
                  id.value(),
                  "deletePopulation",
                  deletePopulation,
                  "citiesRemoved",
                  base.cities().size() - next.cities().size()));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_DELETE_CITY_REJECTED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "reason",
                  SocialPayloads.logReason(e.getMessage()),
                  "city",
                  cityForLog == null ? "-" : cityForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
