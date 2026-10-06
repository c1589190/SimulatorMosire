package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.CityOperations;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code social.MoveCity} 命令的处理器（P1.2 后端行政）：把城市节点搬到新落点，并可同时改/清区域归属。
 *
 * <pre>{@code
 * {"id":"c-33_-55","at":{"q":35,"r":-60},"region":"西陵__CAP","reason":"迁都"}
 * }</pre>
 *
 * <p>★ <b>{@code region} 三种键态</b>（与 {@code social.UpdateCity} 同款）：<b>键缺席</b> = 保持原归属； <b>JSON
 * {@code null}</b> = 清空归属；<b>字符串</b> = 设为该 {@link RegionId}。
 *
 * <p>★★ <b>人口语义</b>：城市身份 {@link CityId} 不变 ⇒ {@code urban:&lt;cityId&gt;:} 前缀批次仍属于本城， 城镇人口<b>不因
 * MoveCity 减少</b>。物理人口是否随城搬由各自家户决定；要搬人请另发 {@code social.MovePopulationLots}（两条命令可在 app 组合根同批落一条
 * revision）。
 *
 * <p>★ <b>GM-only</b>：城市落点是行政区划/首都布局的一部分，不作为决策令直改入口。
 */
public final class MoveCityHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "social.MoveCity";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    HexCoord at = SocialPayloads.requireHex(payload, "at");
    // social 资源语法没有 city 专属路径；城的目标按它所在的格判（与 CreateCity 同口径）。
    return List.of(ResourcePaths.social(at.q(), at.r()));
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
      HexCoord at = SocialPayloads.requireHex(payload, "at");
      Optional<Optional<RegionId>> region = optionalRegion(payload);
      SocialCity before = base.cities().get(id);
      SocialData next =
          CityOperations.move(base, id, at, region.orElse(Optional.empty()), region.isPresent());
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_MOVE_CITY_APPLIED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "city",
                  id.value(),
                  "from",
                  before == null ? "-" : before.at(),
                  "to",
                  at,
                  "region",
                  region.isPresent()
                      ? region.get().map(RegionId::value).orElse("(cleared)")
                      : "(unchanged)"));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_MOVE_CITY_REJECTED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "reason",
                  SocialPayloads.logReason(e.getMessage()),
                  "city",
                  cityForLog == null ? "-" : cityForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 同 {@code social.UpdateCity}：外层表达"键在不在"，内层表达 JSON null（清空）与字符串（设值）。 */
  private static Optional<Optional<RegionId>> optionalRegion(JsonNode payload) {
    JsonNode value = payload.get("region");
    if (value == null) {
      return Optional.empty();
    }
    if (value.isNull()) {
      return Optional.of(Optional.empty());
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 region 必须是字符串或 null: " + payload);
    }
    return Optional.of(Optional.of(RegionId.parse(value.asText())));
  }
}
