package io.mosire.simos.gov;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * 辖区逐格行政需求（阶段 11a，计划 §2.2 / §3）：给定地图、社会数据与一个 GOV 单位，算出它**管辖区域内每一格**的治安/文书需求。
 *
 * <p>★★ <b>纯函数</b>：不写任何状态、不调命令/存储、不发明缺口信号；输入相同 ⇒ 输出逐字段相同（{@code Region.hexes()} 是集合语义，
 * 同一实例的迭代序在单次运行内稳定，故本函数的键序在同一输入下稳定；跨实例比较请用 {@link Map#equals}，它不依赖迭代序）。
 *
 * <p>★★ <b>只算管辖 Region</b>：遍历 {@link Jurisdiction#taxRatePerMilleByRegion()} 的 <b>key 集</b>（value
 * 是税率，与本函数无关）。 key 对应的 {@link Region} 在 {@code map.regions()} 里<b>查无 ⇒ 跳过该 Region</b>——这是具名缺口，由调用方读
 * evidence/日志补， 纯函数里不抛、不造信号。
 *
 * <p>★★ <b>逐格公式</b>（常量全部引用 {@link GovRules}，不手抄数字）：
 *
 * <ul>
 *   <li>{@code security = ceil(populationAt(hex) / SECURITY_PER_OFFICER) + (cityOnHex ?
 *       CITY_SECURITY_WEIGHT : 0)}；
 *   <li>{@code paperwork = ceil(populationAt(hex) / PAPERWORK_PER_SCRIBE) + (cityOnHex ?
 *       CITY_PAPERWORK_WEIGHT : 0)}；
 *   <li>{@code populationAt} 走 {@link SocialData#populationAt(HexCoord)}（该格农村 + 城镇的唯一算法）；
 *   <li>{@code cityOnHex} 从 {@link SocialData#cities()} 的 {@link SocialCity#at()}
 *       <b>现算</b>：本方法内临时收一份"有哪些格 有城"的集合用于 O(1) 查询，<b>不落任何持久索引</b>（城市落点仍是 social 侧的唯一真相）。
 * </ul>
 *
 * <p>★ <b>0 也是事实</b>：需求为 0 的格（如无人无城）也保留在返回表里——缺失与"需求为零"不是一回事（照 {@code GovOfficeState} 对空读数的口径）。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} + 在返回处 {@code Collections.unmodifiableMap}；<b>不用</b>
 * {@code Map.copyOf}（它不承诺迭代序）。
 */
public final class GovDemand {

  private static final Logger LOG = GovLog.demand();

  private GovDemand() {}

  /**
   * 逐格行政需求。
   *
   * @param map 地图（{@code regions()} 提供 Region → hex 集）；不得为 null
   * @param social 社会数据（人口与城市落点的唯一真相）；不得为 null
   * @param unit 其 {@code jurisdiction().taxRatePerMilleByRegion()} 的 key 集就是管辖 Region 集；不得为 null
   * @return {@code hex → 需求}，保序不可变；无管辖/Region 全查无 ⇒ 空表（空是事实，不是错误）
   */
  public static Map<HexCoord, HexDemand> of(GameMap map, SocialData social, Unit unit) {
    if (map == null) {
      throw new IllegalArgumentException("map 不得为 null");
    }
    if (social == null) {
      throw new IllegalArgumentException("social 不得为 null");
    }
    if (unit == null) {
      throw new IllegalArgumentException("unit 不得为 null");
    }

    Map<HexCoord, HexDemand> demand = new LinkedHashMap<>();
    Optional<Jurisdiction> jurisdiction = unit.jurisdiction();
    if (jurisdiction.isPresent()) {
      // ★ 城市落点现算一次，供逐格 O(1) 判"这格有城"；不落任何持久索引（social 仍是唯一真相）。
      Set<HexCoord> cityHexes = cityHexes(social);
      for (RegionId regionId : jurisdiction.get().taxRatePerMilleByRegion().keySet()) {
        Region region = map.regions().get(regionId);
        if (region == null) {
          continue; // ★ 查无 ⇒ 跳过该 Region（具名缺口由调用方读 evidence/日志，不在这里抛）
        }
        for (HexCoord hex : region.hexes()) {
          long population = social.populationAt(hex);
          boolean cityOnHex = cityHexes.contains(hex);
          long security =
              ceilDiv(population, GovRules.SECURITY_PER_OFFICER)
                  + (cityOnHex ? GovRules.CITY_SECURITY_WEIGHT : 0L);
          long paperwork =
              ceilDiv(population, GovRules.PAPERWORK_PER_SCRIBE)
                  + (cityOnHex ? GovRules.CITY_PAPERWORK_WEIGHT : 0L);
          // ★ 重叠 Region 的同一格需求相同（公式只看该格人口与城市），putIfAbsent 只保证"首次出现的键序"。
          demand.putIfAbsent(hex, new HexDemand(security, paperwork));
        }
      }
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "GOV_DEMAND_COMPUTED",
                GovLogSource.GOV_DEMAND,
                "unit",
                unit.id().value(),
                "hexes",
                demand.size(),
                "jurisdiction",
                jurisdiction.isPresent()));
    return Collections.unmodifiableMap(demand); // ★ 冻在返回处（保序）
  }

  /** 从城市表现算"有城的格"（临时查询集；城市落点这类空间事实归 social，不在这里存第二份）。 */
  private static Set<HexCoord> cityHexes(SocialData social) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (SocialCity city : social.cities().values()) {
      hexes.add(city.at());
    }
    return hexes;
  }

  /**
   * 向上取整的整数除法：{@code ceil(value / divisor)}（{@code divisor > 0}）。
   *
   * <p>★ 不用 {@code (value + divisor - 1) / divisor}：那在 {@code value} 接近 {@code Long.MAX_VALUE}
   * 时会先溢出； 这里先 {@code floorDiv} 再按余数补一，等价且不提前相加。人口是非负量，负值只会出现在损坏数据里（此时结果仍是数学 ceil）。
   */
  private static long ceilDiv(long value, long divisor) {
    long quotient = Math.floorDiv(value, divisor);
    return Math.floorMod(value, divisor) == 0L ? quotient : quotient + 1L;
  }

  /**
   * 单格行政需求（阶段 11a，计划 §3）：治安与文书两个**编制人数**需求。
   *
   * <p>★ <b>量纲</b>：两个都是"名"（治安名 / 书吏+驿传名），不是人口、不是毫。★ <b>构造期校验</b>：两者都 ≥ 0，负值当场抛 {@link
   * IllegalArgumentException}（需求为负不是一种事实，是算式/数据损坏）。
   *
   * @param security 治安需求（名；≥ 0）
   * @param paperwork 文书需求（书吏+驿传；名；≥ 0）
   */
  public record HexDemand(long security, long paperwork) {

    public HexDemand {
      requireNonNegative(security, "security");
      requireNonNegative(paperwork, "paperwork");
    }

    private static void requireNonNegative(long value, String field) {
      if (value < 0L) {
        throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
      }
    }
  }
}
