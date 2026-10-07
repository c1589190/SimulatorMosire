package io.mosire.simos.app.time;

import io.mosire.simos.gov.GovServiceFlow;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★ <b>进程内的"本 tick 行政服务流量"投递点（Z3b；同形先例 {@link MarketReportFeed}）</b>——把日循环里那份 {@link
 * GovServiceFlow} 交给读口（GUI / MCP / Z3c 的 {@code simos.gov.info}），<b>不进 {@code GovState}、不落库、不进
 * 库存/市场/ledger 存量</b>。
 *
 * <p>★★ <b>如实边界（读侧必须照写）</b>：
 *
 * <ol>
 *   <li><b>重启即失</b>：它是 JVM 进程内的 {@link ConcurrentHashMap}，不落盘、不进变更集；重启 / 换进程后读口只能报具名 {@link
 *       #UNAVAILABLE_REASON}（绝不把"读不到"填成 0）；
 *   <li><b>只在同一 tick 内可信</b>：{@link #last(String, long)} 要求读数 tick 与投递 tick 逐值相同——从旧 revision
 *       分叉/回读旧档时， 进程里那份流量属于另一条时间线，宁可不给；{@code GovOfficeState} 只存当日读数，公式/次日结算<b>不得</b>把它当输入；
 *   <li><b>一份流量覆盖一个 map</b>：键是 {@code mapId}（一个 JVM 里可能有多个世界/夹具）。
 * </ol>
 *
 * <p>★ 投递时机：app 推进参与者在每个有 GOV 读数的世界日、算完当日唯一一份效率结果之后投递（流量与效率同源、同 tick）。
 */
public final class GovServiceFlowFeed {

  /** 读不到时的具名原因（读口把它写进 {@code unavailable}，不填 0）。 */
  public static final String UNAVAILABLE_REASON = "gov-service-flow-unavailable-for-tick";

  /** 键 = mapId；值 = 最近一次投递（含投递时的世界日）。 */
  private static final Map<String, Entry> BY_MAP = new ConcurrentHashMap<>();

  private GovServiceFlowFeed() {}

  /**
   * 投递一份流量（可为空表 = 这个世界本 tick 没有 GOV 流量）。
   *
   * @param mapId 世界 id；不得为空白
   * @param flows 本 tick 逐 GOV 流量（保序快照，投递处再拷贝冻结）；不得为 null、键值不得为 null
   * @param tick 投递时的世界日（读侧必须逐值相同才认）
   */
  public static void publish(String mapId, Map<UnitId, GovServiceFlow> flows, long tick) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("GovServiceFlowFeed.mapId 不得为空白");
    }
    Objects.requireNonNull(flows, "flows");
    Map<UnitId, GovServiceFlow> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, GovServiceFlow> entry : flows.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("GovServiceFlowFeed.flows 的键与值都不得为 null");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    BY_MAP.put(mapId, new Entry(Collections.unmodifiableMap(copy), tick)); // ★ 冻在投递处（保序）
  }

  /**
   * 读本 tick 的逐 GOV 流量：<b>只在读数 tick 与投递 tick 逐值相同时返回</b>（见类注"同一 tick 内可信"）。
   *
   * @param mapId 世界 id
   * @param tick 当前 state 的世界日
   * @return 本 tick 流量；没有 / tick 不匹配 ⇒ {@link Optional#empty()}（读口按 {@link #UNAVAILABLE_REASON}
   *     具名，不填 0）
   */
  public static Optional<Map<UnitId, GovServiceFlow>> last(String mapId, long tick) {
    if (mapId == null || mapId.isBlank()) {
      return Optional.empty();
    }
    Entry entry = BY_MAP.get(mapId);
    if (entry == null || entry.tick() != tick) {
      return Optional.empty();
    }
    return Optional.of(entry.flows());
  }

  /** 清掉一个 map 的投递（服务收工 / 测试夹具用；不是业务路径）。 */
  public static void clear(String mapId) {
    if (mapId != null) {
      BY_MAP.remove(mapId);
    }
  }

  private record Entry(Map<UnitId, GovServiceFlow> flows, long tick) {}
}
