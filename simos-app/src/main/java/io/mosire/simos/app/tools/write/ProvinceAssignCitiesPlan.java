package io.mosire.simos.app.tools.write;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.province.assignCities} 的<b>纯推导</b>（R2a，行政区划修复计划 §1.2）：从一份 {@link GameMap} /
 * {@link SocialData} 与已解析参数算出"哪些城市要改到哪个省/首都区"——<b>不碰 {@code ToolContext}、不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把 {@link Derivation} 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>候选与选择（确定性）</b>：对每座城，在包含其 {@link SocialCity#at()} 的 Region 中挑"省/首都区"候选——id <b>含</b>
 * {@code "__P"} 或 <b>以</b> {@code "__CAP"} <b>结尾</b>；带 {@code nation:} tag 的国家 Region 自身一律排除。多个候选 ⇒
 * 取 {@code hexCount} 最小；并列 ⇒ 按 regionId 字典序取小。无候选 ⇒ 该城保持原 {@code region}（不猜、不编）；目标 region 与当前
 * {@code city.region} 相同 ⇒ 不进改动列表。改动列表最后按 cityId 字典序输出（同状态两次调用逐字节相同）。
 *
 * <p>★★ <b>{@code regionId?}（父 Region 过滤）</b>：给了就只处理"目标省/首都区属于该父 Region"的候选——候选 id 以 {@code
 * <regionId>__} 开头；<b>或</b>该城当前 {@code region == regionId} 且候选包含其 {@code at}（这样既有世界里城市 仍挂在国家 Region
 * 上时，即使省 id 前缀写法与父 id 不完全一致，也不会漏掉）。父 Region <b>自身</b>即使 id 恰好命中 {@code __P}/{@code __CAP} 也排除。
 *
 * <p>★ <b>为什么单独一个文件</b>：与 {@code ProvinceApplyPlan} 同制——preview=false 时工具把每条 {@link Assignment}
 * 折成一条 {@code social.UpdateCity}，一批共享 batchId / branch / expectedRevision ⇒ 恰一条
 * revision；本类只产出纯数据，不生成命令 id / 不组批。
 */
final class ProvinceAssignCitiesPlan {

  /** {@code social.UpdateCity} 的命令类型（与窄工具同一个拼写点）。 */
  static final String UPDATE_CITY_TYPE = SocialUpdateCityTool.NAME;

  /** 省 id 标记（{@code ProvinceDivider} 建议形制的中段）。 */
  private static final String PROVINCE_MARKER = "__P";

  /** 首都区 id 后缀。 */
  private static final String CAPITAL_SUFFIX = "__CAP";

  /** 父 Region 过滤时用的前缀分隔符。 */
  private static final String REGION_PREFIX_SEPARATOR = "__";

  private ProvinceAssignCitiesPlan() {}

  /**
   * 一次调用的已解析参数。
   *
   * @param regionId 父 Region 过滤（可选；工具层已校验它存在于当前地图）
   */
  record Params(Optional<RegionId> regionId) {

    Params {
      Objects.requireNonNull(regionId, "regionId");
    }
  }

  /**
   * 一条实际要改的归属。
   *
   * @param fromRegion 原 region（{@code null} = 原来无归属）
   * @param toRegion 目标省/首都区 id（必填非空白）
   */
  record Assignment(
      CityId cityId, String cityName, HexCoord at, String fromRegion, String toRegion) {

    Assignment {
      Objects.requireNonNull(cityId, "cityId");
      if (cityName == null || cityName.isBlank()) {
        throw new IllegalArgumentException("cityName 不得为空白");
      }
      Objects.requireNonNull(at, "at");
      if (toRegion == null || toRegion.isBlank()) {
        throw new IllegalArgumentException("toRegion 不得为空白");
      }
    }

    /** {@code social.UpdateCity} 的载荷视图：只给 id 与目标 region，name/props 保持原值。 */
    Map<String, Object> payloadView() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", cityId.value());
      payload.put("region", toRegion);
      return payload;
    }
  }

  /** 一次推导：扫描到的城市总数 + 按 cityId 字典序冻结的改动列表。 */
  record Derivation(int totalCities, List<Assignment> assignments) {

    Derivation {
      if (totalCities < 0) {
        throw new IllegalArgumentException("totalCities 不得为负: " + totalCities);
      }
      Objects.requireNonNull(assignments, "assignments");
      assignments = List.copyOf(assignments);
      if (assignments.size() > totalCities) {
        throw new IllegalArgumentException(
            "改动城市数不得超过扫描总数: " + assignments.size() + " > " + totalCities);
      }
    }
  }

  /** 唯一推导入口：不读墙钟、不用随机量；候选集与选择规则都是 {@code map}/{@code social}/参数的字面函数。 */
  static Derivation derive(GameMap map, SocialData social, Params params) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(params, "params");
    List<Region> candidates = new ArrayList<>();
    for (Region region : map.regions().values()) {
      if (!isProvinceOrCapital(region) || isNationRegion(region)) {
        continue;
      }
      if (params.regionId().filter(region.id()::equals).isPresent()) {
        continue; // 父 Region 自身永远不是候选
      }
      candidates.add(region);
    }
    // 先按 id 排序；"最小 hexCount、再字典序"由 choose 的比较器保证，两层顺序都不依赖 regions() 的插入序。
    candidates.sort(Comparator.comparing(region -> region.id().value()));
    List<Assignment> assignments = new ArrayList<>();
    for (SocialCity city : social.cities().values()) {
      Region target = choose(candidates, city, params.regionId());
      if (target == null) {
        continue;
      }
      String from = city.region().map(RegionId::value).orElse(null);
      if (target.id().value().equals(from)) {
        continue;
      }
      assignments.add(new Assignment(city.id(), city.name(), city.at(), from, target.id().value()));
    }
    assignments.sort(Comparator.comparing(assignment -> assignment.cityId().value()));
    return new Derivation(social.cities().size(), assignments);
  }

  /** 候选省/首都区：id 含 {@code "__P"} 或 id 以 {@code "__CAP"} 结尾。 */
  private static boolean isProvinceOrCapital(Region region) {
    String id = region.id().value();
    return id.contains(PROVINCE_MARKER) || id.endsWith(CAPITAL_SUFFIX);
  }

  /** 国家 Region 自身（{@code nation:<id>} tag）不作为候选。 */
  private static boolean isNationRegion(Region region) {
    return NationTag.isNationTag(region.meta().tag());
  }

  /** 在包含城 {@code at} 的候选中按"hexCount 最小、regionId 字典序"取一；无候选 ⇒ null（保持原 region）。 */
  private static Region choose(
      List<Region> candidates, SocialCity city, Optional<RegionId> parentRegion) {
    Region best = null;
    for (Region candidate : candidates) {
      if (!candidate.hexes().contains(city.at())) {
        continue;
      }
      if (!eligible(candidate, city, parentRegion)) {
        continue;
      }
      if (best == null || better(candidate, best)) {
        best = candidate;
      }
    }
    return best;
  }

  /**
   * 父 Region 过滤：候选 id 以 {@code <regionId>__} 开头，<b>或</b>该城当前 region 正是父 Region（此时候选包含 at
   * 已在上游判过）。后一支是"既有世界城市仍挂国家、省 id 前缀可能被 sanitize 改写"的兜底口径。
   */
  private static boolean eligible(
      Region candidate, SocialCity city, Optional<RegionId> parentRegion) {
    if (parentRegion.isEmpty()) {
      return true;
    }
    RegionId parent = parentRegion.get();
    if (candidate.id().value().startsWith(parent.value() + REGION_PREFIX_SEPARATOR)) {
      return true;
    }
    return city.region().filter(parent::equals).isPresent();
  }

  /** 严格优于：hexCount 更小；相等时 regionId 字典序更小。 */
  private static boolean better(Region candidate, Region current) {
    int byCount = Integer.compare(candidate.hexes().size(), current.hexes().size());
    if (byCount != 0) {
      return byCount < 0;
    }
    return candidate.id().value().compareTo(current.id().value()) < 0;
  }
}
