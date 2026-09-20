package io.mosire.simos.sd.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Optional;

/**
 * "带国家 tag 的区域不可删"（spec §九，A6）：sd 侧唯一能**同时**读 map 的 {@code Region.meta.tag} 与 sd 的 {@code
 * Nation.homeRegion} 的地方——这正是守卫必须住在 simos-sd 的原因。
 *
 * <p>★ **只读、只回答可不可以**（{@link MutationGuard}）：它不写任何状态，删除本身仍由 {@code map.DeleteRegion} 的 handler 执行。
 *
 * <p>★ **非 map.DeleteRegion 的命令一律放行**（先按 {@code commandType} 短路）——守卫不该影响其它命令。
 *
 * <p>★ 载荷坏 / 切片缺 ⇒ 放行（判不了；坏载荷由 handler 自己拒，缺切片是装配故障、会由 handler 的切片读取炸出）。
 */
public final class RegionDeleteGuard implements MutationGuard {

  private static final String COMMAND_TYPE = "map.DeleteRegion";

  private static final String REGION_ID_FIELD = "regionId";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public String name() {
    return "sd.region-delete";
  }

  @Override
  public Optional<String> rejection(SimulationState state, String commandType, String payloadJson) {
    if (!COMMAND_TYPE.equals(commandType)) {
      return Optional.empty();
    }
    Optional<RegionId> regionId = regionIdOf(payloadJson);
    if (regionId.isEmpty()) {
      return Optional.empty();
    }
    RegionId id = regionId.get();
    Optional<GameMap> map = mapOf(state);
    if (map.isPresent()) {
      Region region = map.get().regions().get(id);
      if (region != null && NationTag.isNationTag(region.meta().tag())) {
        return Optional.of("Region " + id + " 带国家 tag（" + region.meta().tag() + "），不可删（spec §九）");
      }
    }
    Optional<SdState> sd = sdOf(state);
    if (sd.isPresent()) {
      for (Nation nation : sd.get().nations().values()) {
        if (nation.homeRegion().equals(id)) {
          return Optional.of(
              "Region " + id + " 是 Nation " + nation.id() + " 的 homeRegion，不可删（spec §九）");
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<RegionId> regionIdOf(String payloadJson) {
    try {
      JsonNode payload = MAPPER.readTree(payloadJson);
      JsonNode value = payload == null ? null : payload.get(REGION_ID_FIELD);
      if (value == null || !value.isTextual()) {
        return Optional.empty();
      }
      return Optional.of(RegionId.parse(value.asText()));
    } catch (JsonProcessingException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Optional<GameMap> mapOf(SimulationState state) {
    Snapshot snapshot = state.module("map").orElse(null);
    if (snapshot instanceof MapSnapshot mapSnapshot) {
      return Optional.of(mapSnapshot.map());
    }
    return Optional.empty();
  }

  private static Optional<SdState> sdOf(SimulationState state) {
    Snapshot snapshot = state.module("sd").orElse(null);
    if (snapshot instanceof SdSnapshot sdSnapshot) {
      return Optional.of(sdSnapshot.state());
    }
    return Optional.empty();
  }
}
