package io.mosire.simos.social.api.population;

import io.mosire.simos.social.api.id.HouseholdId;

/**
 * 一条家户人口事件（2026-10-09 家户/人口架构 §4.3）：进持久事件表、可回放。
 *
 * <p>不变量（构造期判、坏数据当场抛，不静默落半截账）：
 *
 * <ul>
 *   <li>{@code id}、{@code ageBracketId} 非空白；
 *   <li>{@code householdId}、{@code type}、{@code sex} 非 null；
 *   <li>{@code count}、{@code day} 非负；
 *   <li>{@code reason}、{@code source} 非空白（回放时能回答"谁为什么动的人"）。
 * </ul>
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param id 事件稳定身份；非空白
 * @param householdId 事件所属家户；不得为 null
 * @param type 事件类型；不得为 null
 * @param sex 涉及性别；不得为 null
 * @param ageBracketId 涉及年龄档短名；非空白
 * @param count 人数；不得为负
 * @param day 发生日（tick）；不得为负
 * @param reason 原因档；非空白
 * @param source 来源（GM / 结算 / 命令等）；非空白
 */
public record HouseholdPopulationEvent(
    String id,
    HouseholdId householdId,
    PopulationEventType type,
    Sex sex,
    String ageBracketId,
    long count,
    long day,
    String reason,
    String source) {

  public HouseholdPopulationEvent {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.id 不得为空白");
    }
    if (householdId == null) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.householdId 不得为 null");
    }
    if (type == null) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.type 不得为 null");
    }
    if (sex == null) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.sex 不得为 null");
    }
    if (ageBracketId == null || ageBracketId.isBlank()) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.ageBracketId 不得为空白");
    }
    if (count < 0L) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.count 不得为负: " + count);
    }
    if (day < 0L) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.day 不得为负: " + day);
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.reason 不得为空白");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("HouseholdPopulationEvent.source 不得为空白");
    }
  }
}
