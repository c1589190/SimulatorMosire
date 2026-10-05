package io.mosire.simos.social.change;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.provisioning.SocialProvisioning;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 社会状态的变更集。**组件与 {@link SocialData} 的 record 组件一一对应**（当前 6 个：populations / cities / groups /
 * households / populationEvents / provisioning）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 `SocialRoundTripTest` 的**反射枚举**把守——新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code UnitChangeSet} 共用同一份）。
 *
 * <p>★ **S2 起新增两个键解析器**：{@code households} 的键是 {@link HouseholdId#parse}（裸值 + parse 三件套）， {@code
 * populationEvents} 的键是事件 id 的裸字符串（恒等还原）。
 *
 * <p>★★ <b>第 6 个组件 {@code provisioning} 照 {@code EconomyChangeSet.meta} 的"单键表"投影法</b>：它是
 * 一个<b>单值</b>组件（{@link SocialProvisioning} 不是表），若为它另写一份"单值差异"机制，就有了与 {@link FieldDelta}
 * 分叉的第二份实现。故把它投影成"恰一行的表"（键固定为 {@link #PROVISIONING_KEY}）， diff/rebuild 全走既有机制，再投影回单值。语义是纯的：{@code
 * 旧值 → 新值} = {@code Upsert}、 {@code 不变} = {@code Unchanged}；本项目不存在"把 provisioning
 * 删掉"的合法状态（组件恒在），故不产生 {@code Remove}。
 *
 * <p>★★ <b>旧档不兼容</b>（用户 2026-10-09 裁定"一切从新、旧档作废、不做迁移/双读"）：其余五个组件的旧档 缺键仍按既有口径读成 {@code
 * Unchanged}（那是更早变更集的既有语义，本批不动），但第 6 个组件缺键 ⇒ 构造期具名拒——旧变更集读不回是可接受结果，不给它补默认值。
 */
public record SocialChangeSet(
    FieldDelta<PopulationSeries> populations,
    FieldDelta<SocialCity> cities,
    FieldDelta<PopulationGroup> groups,
    FieldDelta<Household> households,
    FieldDelta<HouseholdPopulationEvent> populationEvents,
    FieldDelta<SocialProvisioning> provisioning)
    implements ChangeSet {

  /** {@code provisioning} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String PROVISIONING_KEY = "provisioning";

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
    // ★★ 第 6 组件（Batch 1）**不做旧档兜底**：缺键 = 旧变更集缺 provisioning，具名拒（见类注"旧档不兼容"）。
    if (provisioning == null) {
      SocialLog.provisioning()
          .error(
              "event=SOCIAL_PROVISIONING_REJECTED reason={}",
              "SocialChangeSet.provisioning 不得为 null（旧档缺此组件已作废，不做缺省兜底）");
      throw new IllegalArgumentException("SocialChangeSet.provisioning 不得为 null（旧档缺此组件已作废，不做缺省兜底）");
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
        FieldDelta.diff(base.populationEvents(), target.populationEvents()),
        FieldDelta.diff(
            provisioningTable(base.provisioning()), provisioningTable(target.provisioning())));
  }

  /**
   * 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。
   *
   * <p>★ key 解析器一律是各自的 {@code parse}（铁律 1 的"裸值 + parse"三件套）：{@code HexCoord::parse} / {@code
   * CityId::parse} / {@code PeopleLotId::parse} / {@link HouseholdId#parse}；事件表的键是裸字符串 ⇒
   * 恒等还原；{@code provisioning} 的单键表键是固定串 ⇒ 恒等还原。
   */
  public static SocialData apply(SocialChangeSet cs, SocialData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new SocialData(
        FieldDelta.rebuild(base.populations(), cs.populations(), HexCoord::parse),
        FieldDelta.rebuild(base.cities(), cs.cities(), CityId::parse),
        FieldDelta.rebuild(base.groups(), cs.groups(), PeopleLotId::parse),
        FieldDelta.rebuild(base.households(), cs.households(), HouseholdId::parse),
        FieldDelta.rebuild(base.populationEvents(), cs.populationEvents(), text -> text),
        provisioningOf(
            FieldDelta.rebuild(
                provisioningTable(base.provisioning()), cs.provisioning(), Function.identity())));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(populations.changed()
        || cities.changed()
        || groups.changed()
        || households.changed()
        || populationEvents.changed()
        || provisioning.changed());
  }

  /** {@code SocialProvisioning} → 恰一行的表（键固定为 {@link #PROVISIONING_KEY}）。 */
  private static Map<String, SocialProvisioning> provisioningTable(
      SocialProvisioning provisioning) {
    if (provisioning == null) {
      throw new IllegalArgumentException(
          "SocialChangeSet.provisioningTable 收到 null（坏数据；provisioning 组件恒在）");
    }
    return Map.of(PROVISIONING_KEY, provisioning);
  }

  /** 上一条的逆：单键表 → {@code SocialProvisioning}；键缺席 ⇒ 具名拒（不静默补默认值）。 */
  private static SocialProvisioning provisioningOf(Map<String, SocialProvisioning> table) {
    SocialProvisioning provisioning = table.get(PROVISIONING_KEY);
    if (provisioning == null) {
      SocialLog.provisioning()
          .error(
              "event=SOCIAL_PROVISIONING_REJECTED reason={}",
              "SocialChangeSet.apply 后 provisioning 键缺席（坏数据；旧档已作废，不做缺省兜底）");
      throw new IllegalArgumentException(
          "SocialChangeSet.apply 后 provisioning 键缺席（坏数据；旧档已作废，不做缺省兜底）");
    }
    return provisioning;
  }
}
