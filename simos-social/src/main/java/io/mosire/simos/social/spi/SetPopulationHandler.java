package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code social.SetPopulation} 命令的处理器：**只对点名的格设人口，未点名的格不动**。
 *
 * <pre>{@code
 * {"entries":[{"q":0,"r":0,"population":18036},…],"anchorTick":5?}
 * }</pre>
 *
 * <p>★ **载荷形态是本模块的私事**（C26）：字段缺失或形态不符 ⇒ {@code Rejected}。校验：{@code entries} 非空、每项 {@code population
 * ≥ 0}。**坐标重复：后出现者覆盖先出现者，不报错**（见 {@link SocialPayloads#requireEntries}）。
 *
 * <p>★ **人口先静止**：本命令建出的 {@link PopulationSeries} 增长率 0、无事件，{@code anchor} 落在 {@code anchorTick} （缺省
 * = {@code state.meta().timestamp()}；单位：日，2026-09-24 日制裁定）。★ 该增长率是**每日**增长率（量纲见 {@link
 * PopulationSeries}）。增长率模型留给后续 Social 工作。
 *
 * <p>★ **目标资源**（{@link CommandTargets}）：{@code entries[]} 里**每一个**格，路径取 social 命名空间的既有形态 {@link
 * ResourcePaths#social(int, int)}（{@code <q>_<r>}，**不带 mapId**）——与读侧 {@code populationVisible}
 * 判的是同一个资源。
 */
public final class SetPopulationHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = SocialPayloads.parse(payloadJson);
    return SocialPayloads.requireEntries(payload).keySet().stream()
        .map(coord -> ResourcePaths.social(coord.q(), coord.r()))
        .toList();
  }

  @Override
  public String type() {
    return "social.SetPopulation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      Map<HexCoord, Long> entries = SocialPayloads.requireEntries(payload);
      Long anchorTick = SocialPayloads.optionalLong(payload, "anchorTick");
      SimosTimestamp at =
          anchorTick == null ? state.meta().timestamp() : SimosTimestamp.of(anchorTick);
      Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>(base.populations());
      for (Map.Entry<HexCoord, Long> entry : entries.entrySet()) {
        next.put(entry.getKey(), stillPopulation(at, entry.getValue()));
      }
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, base.withPopulations(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 静止人口序列：增长率为 0、无事件，anchor 落在 {@code at}（见类注）。 */
  private static PopulationSeries stillPopulation(SimosTimestamp at, long population) {
    return new PopulationSeries(
        new Segment<>(at, population),
        SegmentedSeries.of(List.of(new Segment<>(at, 0.0)), List.of(), null),
        List.of());
  }
}
