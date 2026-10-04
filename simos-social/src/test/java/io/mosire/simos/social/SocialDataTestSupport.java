package io.mosire.simos.social;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S2 家户模型下的测试夹具：把旧夹具的"每个批次自带落点"翻译成"批次挂在家户上，家户给出位置"。
 *
 * <p>这是 2026-10-09 架构 §4.2 的直接产物：{@code PopulationGroup.residence} 已删，{@code SocialData}
 * 构造期要求"每个批次恰被一个家户引用"。本类按调用方给的 {@code lot → hex} 表，为每个出现过的 hex 造一个家户
 * （{@code hh:<q>_<r>}），成员表按 {@code groups} 的插入序，从而让旧读口用例（{@code populationAt} /
 * {@code groupsAt} / {@code ageStructureAt} 等）继续有判别力。
 *
 * <p>★ 只在测试里用；生产装配走 {@code PopulationSeeder} / {@code social.SeedGroups}。
 */
public final class SocialDataTestSupport {

  private SocialDataTestSupport() {}

  /** 按 {@code locations} 把批次挂到逐 hex 家户上。 */
  public static SocialData withHouseholdsAt(
      Map<HexCoord, PopulationSeries> populations,
      Map<CityId, SocialCity> cities,
      Map<PeopleLotId, PopulationGroup> groups,
      Map<PeopleLotId, HexCoord> locations) {
    Map<HexCoord, List<PeopleLotId>> lotsByHex = new LinkedHashMap<>();
    for (PeopleLotId lot : groups.keySet()) {
      HexCoord at = locations.get(lot);
      if (at == null) {
        throw new IllegalArgumentException("测试夹具缺批次落点: " + lot);
      }
      lotsByHex.computeIfAbsent(at, ignored -> new ArrayList<>()).add(lot);
    }
    Map<HouseholdId, Household> households = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, List<PeopleLotId>> entry : lotsByHex.entrySet()) {
      HouseholdId householdId = HouseholdId.parse("hh:" + entry.getKey());
      households.put(
          householdId,
          new Household(
              householdId,
              new HouseholdLocation.Hex(entry.getKey()),
              new HouseholdProfile(householdId.value(), null, Map.of()),
              entry.getValue(),
              new HouseholdVitalRates(List.of())));
    }
    return new SocialData(populations, cities, groups, households, Map.of());
  }

  /** 单 hex 家户的简化入口：全部批次都落 {@code at}。 */
  public static SocialData withHouseholdsAt(
      Map<HexCoord, PopulationSeries> populations,
      Map<CityId, SocialCity> cities,
      Map<PeopleLotId, PopulationGroup> groups,
      HexCoord at) {
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    for (PeopleLotId lot : groups.keySet()) {
      locations.put(lot, at);
    }
    return withHouseholdsAt(populations, cities, groups, locations);
  }
}
