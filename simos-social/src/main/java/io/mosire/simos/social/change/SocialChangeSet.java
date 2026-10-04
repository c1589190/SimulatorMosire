package io.mosire.simos.social.change;

import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;

/**
 * 社会状态的变更集。**组件与 {@link SocialData} 的 record 组件一一对应**（当前 3 个：populations / cities / groups）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code SocialRoundTripTest} 的**反射枚举**把守——新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code UnitChangeSet} 共用同一份，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★ **实现 util 的 {@code ChangeSet} 标记接口**（M4 / spec §十，取代本段原引的 C8 结论）：该接口已收窄为**标记接口**——原 {@code
 * baseRevision()} 版本戳已删，C8 当年"版本戳属 Revision 层"的顾虑随之消失（那是 C27 的裁定）。 实现它不带来任何新义务，**字段与既有测试零变化**（U
 * 裁定原话）。
 */
public record SocialChangeSet(
    FieldDelta<PopulationSeries> populations,
    FieldDelta<SocialCity> cities,
    FieldDelta<PopulationGroup> groups)
    implements ChangeSet {

  public SocialChangeSet {
    // ★ **老档兼容**（升级前落盘的每条 social revision 都没有这个键，`simos.db` 里就有）：缺省 = {@link
    //   FieldDelta.Unchanged}（"一字未动"），**此处不抛** —— 抛了等于"旧 revision 全部读不回来"。
    //   方向是 fail-closed：旧档没提城市，就是没动城市。先例见 SdInfoEntry 的 affiliations / SocialData 的 cities。
    if (cities == null) {
      cities = new FieldDelta.Unchanged<>();
    }
    // ★ **R1 唯一保留的那一行兼容**（用户 2026-09-26）：升级前的每条 social revision 都**没有** groups 这个键 ⇒
    //   缺省 = Unchanged，不抛。同上一行，方向 fail-closed：旧档没提批次，就是没动批次。
    if (groups == null) {
      groups = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static SocialChangeSet between(SocialData base, SocialData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new SocialChangeSet(
        FieldDelta.diff(base.populations(), target.populations()),
        FieldDelta.diff(base.cities(), target.cities()),
        FieldDelta.diff(base.groups(), target.groups()));
  }

  /**
   * 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。
   *
   * <p>★ 第三个 key 解析器是 {@code PeopleLotId::parse}（{@link PopulationGroup} 的 map 键）——与 {@code
   * HexCoord::parse} / {@code CityId::parse} 同制：{@code FieldDelta} 的 key 一律是 {@code toString()}
   * 的裸值， 重建时用各自的 {@code parse} 还原（铁律 1 的"裸值 + parse"三件套正是为这一步存在的）。
   */
  public static SocialData apply(SocialChangeSet cs, SocialData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new SocialData(
        FieldDelta.rebuild(base.populations(), cs.populations(), HexCoord::parse),
        FieldDelta.rebuild(base.cities(), cs.cities(), CityId::parse),
        FieldDelta.rebuild(base.groups(), cs.groups(), PeopleLotId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(populations.changed() || cities.changed() || groups.changed());
  }
}
