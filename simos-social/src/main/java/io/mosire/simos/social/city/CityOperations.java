package io.mosire.simos.social.city;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>城市节点的迁移/删除原语</b>（P1.2 后端行政）：只动 social 自己的 {@code cities} / {@code groups} / {@code
 * households}，不碰 map / unit / economy（铁律 3）。
 *
 * <p>★★ <b>人口派生的处理语义（本类存在的主要理由）</b>：{@code SocialCity} 现在没有人口字段，城的城镇人口由 {@code
 * urban:&lt;cityId&gt;:} 前缀的批次现算（{@code SocialData.urbanPopulationAt}）。因此：
 *
 * <ul>
 *   <li><b>MoveCity</b>：城市身份 {@link CityId} 不变 ⇒ 人口归属不变；落点改了，物理人口的位置由各自家户决定， 要随城搬人须另发 {@code
 *       social.MovePopulationLots}。两条命令合起来才是"城与人一起迁"。
 *   <li><b>DeleteCity</b>：默认<b>严格拒绝</b>仍挂着该城批次的城——删掉城市会让那些批次的前缀指向不存在的城 （读口看不见、也无法再按城汇总）。载荷显式给
 *       {@code deletePopulation=true} 时才连带删除这些批次及其家户成员关系； 这是破坏性清理，调用方必须先理解影响面。
 * </ul>
 */
public final class CityOperations {

  private CityOperations() {}

  /**
   * 改城市落点，并可同时改/清区域归属。
   *
   * @param newRegion 仅当 {@code regionGiven == true} 时被读取；{@code Optional.empty()} = 清空归属
   * @param regionGiven 载荷里 {@code region} 键是否出现（缺席 = 保持原归属，JSON null = 清空）
   * @throws IllegalArgumentException 城市不存在 / 没有任何字段变化（拒绝空 revision）
   */
  public static SocialData move(
      SocialData base, CityId id, HexCoord at, Optional<RegionId> newRegion, boolean regionGiven) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(newRegion, "newRegion");
    SocialCity existing = requireCity(base, id);
    SocialCity moved = existing.withAt(at);
    if (regionGiven) {
      moved = moved.withRegion(newRegion);
    }
    if (moved.equals(existing)) {
      throw new IllegalArgumentException("social.MoveCity 没有造成任何变化（落点与归属都与现值相同）: " + id);
    }
    Map<CityId, SocialCity> next = new LinkedHashMap<>(base.cities());
    next.put(id, moved);
    return base.withCities(next);
  }

  /**
   * 删除城市节点。{@code deletePopulation=false} 时，只要还有 {@code urban:&lt;cityId&gt;:} 批次就<b>严格拒绝</b>；
   * {@code true} 时连带删除这些批次及其在所有家户 {@code memberLots} 里的成员关系。
   *
   * @throws IllegalArgumentException 城市不存在 / 有城镇批次但未显式允许删除人口
   */
  public static SocialData delete(SocialData base, CityId id, boolean deletePopulation) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    requireCity(base, id);
    List<PeopleLotId> lots = attributedLots(base, id);
    if (!lots.isEmpty() && !deletePopulation) {
      long people = 0L;
      for (PeopleLotId lot : lots) {
        PopulationGroup group = base.groups().get(lot);
        if (group != null) {
          people += group.count();
        }
      }
      throw new IllegalArgumentException(
          "城市 "
              + id
              + " 仍挂着 "
              + lots.size()
              + " 个城镇批次（合计 "
              + people
              + " 人）：删除会留下无主前缀。请先在载荷显式给 deletePopulation=true 做破坏性清理，"
              + "或保留该城；若要保留人只换城籍，当前模型不支持改批次 id（id 是身份）");
    }

    Map<CityId, SocialCity> cities = new LinkedHashMap<>(base.cities());
    cities.remove(id);
    if (lots.isEmpty()) {
      return base.withCities(cities);
    }

    Set<PeopleLotId> removed = new LinkedHashSet<>(lots);
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(base.groups());
    for (PeopleLotId lot : removed) {
      groups.remove(lot);
    }
    Map<HouseholdId, Household> households = new LinkedHashMap<>(base.households());
    for (Map.Entry<HouseholdId, Household> entry : base.households().entrySet()) {
      Household household = entry.getValue();
      Map<PeopleLotId, Long> remaining = new LinkedHashMap<>();
      for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
        if (!removed.contains(member.getKey())) {
          remaining.put(member.getKey(), member.getValue());
        }
      }
      if (remaining.size() != household.members().size()) {
        households.put(entry.getKey(), household.withMembers(remaining));
      }
    }
    // 事件表是历史留痕，不删（被删批次的 id 只作历史引用）；其余组件原样带过（含 provisioning / vitalRates / 余数表 /
    // Z7d-1 satietyPerMille）。
    return new SocialData(
        base.populations(),
        cities,
        groups,
        households,
        base.populationEvents(),
        base.provisioning(),
        base.vitalRates(),
        base.vitalRemainders(),
        base.satietyPerMille(),
        base.fleeStates());
  }

  /** 该城名下的全部批次（按 {@code groups} 的插入序；前缀判法走 {@link PopulationLots#urbanPrefix} 的唯一拼写点）。 */
  public static List<PeopleLotId> attributedLots(SocialData base, CityId id) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    String prefix = PopulationLots.urbanPrefix(id);
    List<PeopleLotId> lots = new ArrayList<>();
    for (PeopleLotId lot : base.groups().keySet()) {
      if (lot.value().startsWith(prefix)) {
        lots.add(lot);
      }
    }
    return List.copyOf(lots);
  }

  /** 城必须存在（不存在 ⇒ 抛，不静默造空壳）。 */
  private static SocialCity requireCity(SocialData base, CityId id) {
    SocialCity city = base.cities().get(id);
    if (city == null) {
      throw new IllegalArgumentException("城市不存在: " + id);
    }
    return city;
  }
}
