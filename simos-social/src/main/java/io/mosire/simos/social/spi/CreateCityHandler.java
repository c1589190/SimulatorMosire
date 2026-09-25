package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code social.CreateCity} 命令的处理器：建一个城市节点。
 *
 * <pre>{@code
 * {"id":"c1","name":"城甲","at":{"q":0,"r":0},"region":"r1"?,"props":{…}?}
 * }</pre>
 *
 * <p>★ {@code region} 缺省 {@code Optional.empty()}（无归属）；{@code props} 缺省空表。id 已存在 ⇒ {@code
 * Rejected}（与 {@code map.CreateRegion}/{@code sd.CreateNation} 同口径）。空 id/name、空白 region 由 {@link
 * CityId#parse} / {@link SocialCity} 的构造期守卫拒（折算成拒因）。
 *
 * <p>★★ **R1（T5）：人口不在本命令的载荷里**（旧载荷的 {@code population} 字段**明令拒收**，不是静默忽略）。城的城镇人口是**派生量** = 该城名下各
 * {@link io.mosire.simos.social.population.PopulationGroup} 之和（{@link
 * io.mosire.simos.social.SocialData#urbanPopulationAt}），批次由 {@code social.SeedGroups} 落 （命名见 {@link
 * io.mosire.simos.social.population.PopulationLots}：{@code urban:<cityId>:<SEX>:<细分>}）。
 * 若留着那个字段又不读它，就会得到本仓最忌的那种字段："看起来在记、其实不起作用"。
 *
 * <p>★ **目标资源**（{@link CommandTargets}）：这座城**将要落在的那一格**，路径取 social 命名空间的既有形态 {@link
 * ResourcePaths#social(int, int)}（{@code <q>_<r>}）——social 资源语法里**没有** city 专属路径，故按城市所在的格判（与读侧把
 * {@code map.city} 映射到它那一格同口径）。
 */
public final class CreateCityHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = SocialPayloads.parse(payloadJson);
    HexCoord at = SocialPayloads.requireHex(payload, "at");
    return List.of(ResourcePaths.social(at.q(), at.r()));
  }

  @Override
  public String type() {
    return "social.CreateCity";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data();
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      CityId id = CityId.parse(SocialPayloads.requireText(payload, "id"));
      String name = SocialPayloads.requireText(payload, "name");
      HexCoord at = SocialPayloads.requireHex(payload, "at");
      Optional<RegionId> region =
          Optional.ofNullable(SocialPayloads.optionalText(payload, "region"))
              .map(RegionId::parse); // 空白 region 由 RegionId.parse 拒
      SocialPayloads.rejectRetiredPopulation(payload, "social.CreateCity");
      Map<String, Object> props = SocialPayloads.optionalProps(payload, "props");
      if (base.cities().containsKey(id)) {
        return new HandlerOutcome.Rejected("城市已存在: " + id);
      }
      SocialCity city = new SocialCity(id, name, at, region, props == null ? Map.of() : props);
      Map<CityId, SocialCity> next = new LinkedHashMap<>(base.cities());
      next.put(id, city);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, base.withCities(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
