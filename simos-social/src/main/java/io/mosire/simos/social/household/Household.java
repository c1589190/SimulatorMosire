package io.mosire.simos.social.household;

import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ★★ <b>家户状态本体</b>（2026-10-09 家户/人口架构 §4.1）：Social 的<b>基本单元</b>——家户在哪、由谁组成、按什么生死/生育率结算。
 *
 * <pre>
 * Household(id, location, profile, memberLots, vitalRates)
 * </pre>
 *
 * <p>★★ <b>{@code memberLots} 是唯一成员关系</b>（架构 §4.1 原文）：不建 {@code HouseholdMember} 表，不存第二份
 * "谁属于谁"。批次（{@link io.mosire.simos.social.population.PopulationGroup}）本身不再带位置，
 * "人在哪"只能从所属家户的 {@link #location()} 得到（架构 §4.2：删除 {@code PopulationGroup.residence}）。
 *
 * <p>★★ <b>不变量（构造期判、坏数据当场抛）</b>：
 *
 * <ul>
 *   <li>{@code id} / {@code location} / {@code profile} / {@code vitalRates} 都不得为 null；
 *   <li>{@code memberLots} 冻结不可变（保序 {@link List#copyOf}）、不得含 null、<b>不得重复</b>
 *       —— 同一个批次只能属于一个家户（"每个批次必须且只能被一个家户引用"由 {@code SocialData} 的跨组件校验收口）；
 *   <li>不校验批次是否存在（批次表在家户之外）：那是 {@code SocialData} 构造期的跨组件职责。
 * </ul>
 *
 * <p>★ <b>copy-with 语义</b>：{@link #withLocation} / {@link #withProfile} / {@link #withMemberLots} /
 * {@link #withVitalRates} 都是"换一件事、其余原样带过"，不做就地修改（record 不可变）。
 *
 * <p>★ 本类型<b>零 Jackson 注解</b>：线格式由 {@code SocialCodec} 负责（架构 §3.1 的"契约层不放实现"同款纪律）。
 *
 * @param id 家户稳定身份；不得为 null
 * @param location 家户位置（{@code HEX} / {@code UNIT}）；不得为 null
 * @param profile 家户画像；不得为 null
 * @param memberLots 成员批次身份；冻结、不得含 null/重复
 * @param vitalRates 逐 {@code (年龄档, 性别)} 的出生/死亡率；不得为 null
 */
public record Household(
    HouseholdId id,
    HouseholdLocation location,
    HouseholdProfile profile,
    List<PeopleLotId> memberLots,
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
    memberLots = freezeMemberLots(memberLots);
  }

  /** 防御性拷贝 + null 元素具名拒绝 + 重复成员具名拒绝 + 保序冻结。 */
  private static List<PeopleLotId> freezeMemberLots(List<PeopleLotId> source) {
    if (source == null) {
      throw new IllegalArgumentException("Household.memberLots 不得为 null（空家户用空表）");
    }
    List<PeopleLotId> copy = new ArrayList<>(source.size());
    Set<PeopleLotId> seen = new LinkedHashSet<>();
    for (PeopleLotId lot : source) {
      if (lot == null) {
        throw new IllegalArgumentException("Household.memberLots 不得含 null 元素");
      }
      if (!seen.add(lot)) {
        throw new IllegalArgumentException("Household.memberLots 不得含重复批次: " + lot);
      }
      copy.add(lot);
    }
    return List.copyOf(copy);
  }

  /** 换位置（HEX ↔ UNIT 都合法；其余字段原样带过）。 */
  public Household withLocation(HouseholdLocation newLocation) {
    return new Household(id, newLocation, profile, memberLots, vitalRates);
  }

  /** 换画像（其余字段原样带过）。 */
  public Household withProfile(HouseholdProfile newProfile) {
    return new Household(id, location, newProfile, memberLots, vitalRates);
  }

  /** 换成员批次表（其余字段原样带过）。 */
  public Household withMemberLots(List<PeopleLotId> newMemberLots) {
    return new Household(id, location, profile, newMemberLots, vitalRates);
  }

  /** 换率表（其余字段原样带过）。 */
  public Household withVitalRates(HouseholdVitalRates newVitalRates) {
    return new Household(id, location, profile, memberLots, newVitalRates);
  }

  /** 是否含有该成员批次。 */
  public boolean hasMember(PeopleLotId lot) {
    return memberLots.contains(lot);
  }
}
