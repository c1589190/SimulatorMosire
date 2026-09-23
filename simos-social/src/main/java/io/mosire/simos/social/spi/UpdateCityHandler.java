package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.CityId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code social.UpdateCity} 命令的处理器：改城市名 / 城市人口 / props。
 *
 * <pre>{@code
 * {"id":"c1","name":"新名"?,"population":13000?,"props":{…}?}
 * }</pre>
 *
 * <p>★ **三个字段都缺省 = 不动**；id 不存在 ⇒ {@code Rejected}。{@code props} 是**合并**语义（在已有 props
 * 上叠加、同键覆盖），**不是**整份替换 ——这样"只加一个审计量"不必先把整份 props 抄回来。空名、负人口由 {@link SocialCity} 的构造期守卫拒。
 *
 * <p>★★ **目标资源**（{@link CommandTargets}）：返回**空列表**。本命令的载荷**不含坐标**（改名/改人口/props 都不需要它），而 social
 * 资源语法里 **没有 city 专属路径**（只有 {@code <q>_<r>} 的逐格路径）⇒ 从载荷判不出"要动哪一格"。按 {@link CommandTargets} 的口径，空列表
 * = **没有可寻址 目标**，是 **fail-closed**：受限决策人经 {@code sd.AdjudicateTick} 编排的 {@code social.UpdateCity}
 * 会被拒（"载荷未给出任何可寻址目标"）， 由 GM 直接 {@code simos.command.submit}
 * 不受影响（那条走粗写声明，不查目标集）。这不假装能判——要放开得先给城市一个资源路径，那是另一个决定。
 */
public final class UpdateCityHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有目标可给
    return List.of();
  }

  @Override
  public String type() {
    return "social.UpdateCity";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data();
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      CityId id = CityId.parse(SocialPayloads.requireText(payload, "id"));
      SocialCity existing = base.cities().get(id);
      if (existing == null) {
        return new HandlerOutcome.Rejected("城市不存在: " + id);
      }
      String name = SocialPayloads.optionalText(payload, "name");
      Long population = SocialPayloads.optionalLong(payload, "population");
      Map<String, Object> props = SocialPayloads.optionalProps(payload, "props");
      SocialCity updated = existing;
      if (name != null) {
        updated = updated.withName(name); // 空白名由 SocialCity 构造期拒
      }
      if (population != null) {
        updated = updated.withPopulation(population); // 负值由 SocialCity 构造期拒
      }
      if (props != null) {
        Map<String, Object> merged = new LinkedHashMap<>(updated.props());
        merged.putAll(props); // ★ 合并：已有键保留，同键覆盖
        updated = updated.withProps(merged);
      }
      Map<CityId, SocialCity> next = new LinkedHashMap<>(base.cities());
      next.put(id, updated);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, base.withCities(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
