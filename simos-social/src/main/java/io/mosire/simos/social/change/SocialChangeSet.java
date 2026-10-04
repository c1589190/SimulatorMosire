package io.mosire.simos.social.change;

import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;

/**
 * 社会状态的变更集。**组件与 {@link SocialData} 的 record 组件一一对应**（当前 5 个：populations / cities / groups /
 * households / populationEvents）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 `SocialRoundTripTest` 的**反射枚举**把守——新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code UnitChangeSet} 共用同一份）。
 *
 * <p>★ **S2 起新增两个键解析器**：{@code households} 的键是 {@link HouseholdId#parse}（裸值 + parse 三件套），
 * {@code populationEvents} 的键是事件 id 的裸字符串（恒等还原）。
 */
public record SocialChangeSet(
    FieldDelta<PopulationSeries> populations,
    FieldDelta<SocialCity> cities,
    FieldDelta<PopulationGroup> groups,
    FieldDelta<Household> households,
    FieldDelta<HouseholdPopulationEvent> populationEvents)
    implements ChangeSet {

  public SocialChangeSet {
    // ★ **老档兼容**（升级前落盘的每条 social revision 都没有这些键）：缺省 = {@link FieldDelta.Unchanged}
    //   （"一字未动"），**此处不抛** —— 抛了等于"旧 revision 全部读不回来"。
    if (cities == null) {
      cities = new FieldDelta.Unchanged<>();
    }
    if (groups == null) {
      groups = new FieldDelta.Unchanged<>();
    }
    if (households == null) {
      households = new FieldDelta.Unchanged<>();
    }
    if (populationEvents == null) {
      populationEvents = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static SocialChangeSet between(SocialData base, SocialData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new SocialChangeSet(
        FieldDelta.diff(base.populations(), target.populations()),
        FieldDelta.diff(base.cities(), target.cities()),
        FieldDelta.diff(base.groups(), target.groups()),
        FieldDelta.diff(base.households(), target.households()),
        FieldDelta.diff(base.populationEvents(), target.populationEvents()));
  }

  /**
   * 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。
   *
   * <p>★ key 解析器一律是各自的 {@code parse}（铁律 1 的"裸值 + parse"三件套）：{@code HexCoord::parse} /
   * {@code CityId::parse} / {@code PeopleLotId::parse} / {@link HouseholdId#parse}；事件表的键是裸字符串 ⇒
   * 恒等还原。
   */
  public static SocialData apply(SocialChangeSet cs, SocialData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new SocialData(
        FieldDelta.rebuild(base.populations(), cs.populations(), HexCoord::parse),
        FieldDelta.rebuild(base.cities(), cs.cities(), CityId::parse),
        FieldDelta.rebuild(base.groups(), cs.groups(), PeopleLotId::parse),
        FieldDelta.rebuild(base.households(), cs.households(), HouseholdId::parse),
        FieldDelta.rebuild(base.populationEvents(), cs.populationEvents(), text -> text));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(populations.changed()
        || cities.changed()
        || groups.changed()
        || households.changed()
        || populationEvents.changed());
  }
}
