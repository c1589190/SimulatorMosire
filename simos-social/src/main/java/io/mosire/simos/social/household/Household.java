package io.mosire.simos.social.household;

import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>家户状态本体</b>（2026-10-09 家户/人口架构 §4.1 + P2-A §13.2）：Social 的<b>基本单元</b>——家户在哪、由谁组成（逐批次的人数份额）、按什么生死/生育率结算。
 *
 * <pre>
 * Household(id, location, profile, members, vitalRates)
 * members = { PeopleLotId → count }   // ★ P2-A：同一批次可按 count 拆给多个家户（Membership 归 Social）
 * </pre>
 *
 * <p>★★ <b>{@code members} 是唯一成员关系</b>（架构 §4.1 原文）：不建 {@code HouseholdMember} 表，不存第二份
 * "谁属于谁"。批次（{@link io.mosire.simos.social.population.PopulationGroup}）本身不再带位置，
 * "人在哪"只能从所属家户的 {@link #location()} 得到（架构 §4.2）。
 *
 * <p>★★ <b>P2-A 的口径变化（如实记）</b>：改前成员表是 {@code List<PeopleLotId>}（一个批次只能整批属于一个家户）；
 * 现在换成 {@code Map<PeopleLotId, Long>} 的<b>份额表</b>（{@code (PeopleLotId, HouseholdId, count)} 的 Social 侧形态）。
 * 份额的<b>跨家户守恒</b>（逐 lot {@code Σ count == PopulationGroup.count}）由 {@code SocialData} 的跨组件校验收口——
 * 本类型只判自身合法性。
 *
 * <p>★★ <b>不变量（构造期判、坏数据当场抛）</b>：
 *
 * <ul>
 *   <li>{@code id} / {@code location} / {@code profile} / {@code vitalRates} 都不得为 null；
 *   <li>{@code members} 冻结不可变（保序 {@link LinkedHashMap}）、不得含 null 键、<b>不得含负 count</b>；
 *   <li>不校验批次是否存在、份额是否守恒（那要跨家户看）：那是 {@code SocialData} 构造期的跨组件职责。
 * </ul>
 *
 * <p>★ <b>copy-with 语义</b>：{@link #withLocation} / {@link #withProfile} / {@link #withMembers} /
 * {@link #withMember} / {@link #withoutMember} / {@link #withVitalRates} 都是"换一件事、其余原样带过"，不做就地修改（record 不可变）。
 *
 * <p>★ 本类型<b>零 Jackson 注解</b>：线格式由 {@code SocialCodec} 负责（架构 §3.1 的"契约层不放实现"同款纪律）。
 *
 * @param id 家户稳定身份；不得为 null
 * @param location 家户位置（{@code HEX} / {@code UNIT}）；不得为 null
 * @param profile 家户画像；不得为 null
 * @param members 成员份额（批次 → 人数）；冻结、不得含 null 键或负值
 * @param vitalRates 逐 {@code (年龄档, 性别)} 的出生/死亡率；不得为 null
 */
public record Household(
    HouseholdId id,
    HouseholdLocation location,
    HouseholdProfile profile,
    Map<PeopleLotId, Long> members,
    HouseholdVitalRates vitalRates) {

  public Household {
    if (id == null) {
      throw new IllegalArgumentException("Household.id 不得为 null");
    }
    if (location == null) {
      throw new IllegalArgumentException("Household.location 不得为 null");
    }
    if (profile == null) {
      throw new IllegalArgumentException("Household.profile 不得为 null");
    }
    if (vitalRates == null) {
      throw new IllegalArgumentException("Household.vitalRates 不得为 null");
    }
    members = freezeMembers(members);
  }

  /** 防御性拷贝 + null 键具名拒绝 + 负份额具名拒绝 + 保序冻结。 */
  private static Map<PeopleLotId, Long> freezeMembers(Map<PeopleLotId, Long> source) {
    if (source == null) {
      throw new IllegalArgumentException("Household.members 不得为 null（空家户用空表）");
    }
    Map<PeopleLotId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null) {
        throw new IllegalArgumentException("Household.members 不得含 null 键");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("Household.members 的份额不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Household.members 的份额不得为负: " + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 成员批次身份表（保序；份额表键集的只读快照）—— 旧 {@code memberLots} 形状的兼容读法。 */
  public List<PeopleLotId> memberLots() {
    return List.copyOf(members.keySet());
  }

  /** 某批次的份额；不含 ⇒ 0。 */
  public long memberCount(PeopleLotId lot) {
    Objects.requireNonNull(lot, "lot");
    return members.getOrDefault(lot, 0L);
  }

  /** 换位置（HEX ↔ UNIT 都合法；其余字段原样带过）。 */
  public Household withLocation(HouseholdLocation newLocation) {
    return new Household(id, newLocation, profile, members, vitalRates);
  }

  /** 换画像（其余字段原样带过）。 */
  public Household withProfile(HouseholdProfile newProfile) {
    return new Household(id, location, newProfile, members, vitalRates);
  }

  /** 换成员份额表（其余字段原样带过）。 */
  public Household withMembers(Map<PeopleLotId, Long> newMembers) {
    return new Household(id, location, profile, newMembers, vitalRates);
  }

  /**
   * 写入/覆盖某批次的份额（{@code count < 0} 由规范构造器拒；{@code count == 0} 合法但等价于"这个家户此刻没有这一份"，
   * 调用方若想删条目请用 {@link #withoutMember}）。
   *
   * <p>★ 键的插入序：新键追加到末尾；已有键保留原位置（{@link LinkedHashMap} 的既有语义）。
   */
  public Household withMember(PeopleLotId lot, long count) {
    Objects.requireNonNull(lot, "lot");
    Map<PeopleLotId, Long> next = new LinkedHashMap<>(members);
    next.put(lot, count);
    return new Household(id, location, profile, next, vitalRates);
  }

  /** 删除某批次的份额条目（不存在 ⇒ 原样返回）。 */
  public Household withoutMember(PeopleLotId lot) {
    Objects.requireNonNull(lot, "lot");
    if (!members.containsKey(lot)) {
      return this;
    }
    Map<PeopleLotId, Long> next = new LinkedHashMap<>(members);
    next.remove(lot);
    return new Household(id, location, profile, next, vitalRates);
  }

  /** 换率表（其余字段原样带过）。 */
  public Household withVitalRates(HouseholdVitalRates newVitalRates) {
    return new Household(id, location, profile, members, newVitalRates);
  }

  /** 是否含有该成员批次（含份额为 0 的显式条目）。 */
  public boolean hasMember(PeopleLotId lot) {
    return members.containsKey(lot);
  }
}
