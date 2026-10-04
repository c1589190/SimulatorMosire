package io.mosire.simos.social.api.lookup;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.AgeBracketView;
import java.util.List;

/**
 * 人口只读查询 SPI（2026-10-09 家户/人口架构 §3.1 / §5）：hex/unit 人数由家户实时汇总现算， 不在经济侧复制第二份家户人口。
 *
 * <p>★ 四个查询都是只读、无副作用；查不到 ⇒ {@code 0} / 空表（不返回 null）。
 */
public interface PopulationLookup {

  /** 某一格上的总人口。 */
  long populationAt(HexCoord hex);

  /** 某个 unit 上的总人口（Unit/Gov 人数从家户汇总）。 */
  long unitPopulation(String unitId);

  /** 某个家户的人口。 */
  long householdPopulation(HouseholdId householdId);

  /** 某个家户的年龄档视图（空 ⇒ 空表）。 */
  List<AgeBracketView> ageBrackets(HouseholdId householdId);
}
