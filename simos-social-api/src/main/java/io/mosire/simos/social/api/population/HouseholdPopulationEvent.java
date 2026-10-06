package io.mosire.simos.social.api.population;

import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;

/**
 * 一条家户人口事件（2026-10-09 家户/人口架构 §4.3）：进持久事件表、可回放。
 *
 * <p>不变量（构造期判、坏数据当场抛，不静默落半截账）：
 *
 * <ul>
 *   <li>{@code id}、{@code ageBracketId} 非空白；
 *   <li>{@code householdId}、{@code type}、{@code sex} 非 null；
 *   <li>{@code count}、{@code day} 非负；<b>唯一例外</b>见下；
 *   <li>{@code reason}、{@code source} 非空白（回放时能回答"谁为什么动的人"）。
 * </ul>
 *
 * <p>★★ <b>S2 增补：{@code lotId}（可空）</b>——架构 §4.3 的事件形状里没有批次身份，而 TRANSFER_IN / TRANSFER_OUT
 * 要"同一批次整体移动、id 不变"（架构 §4.2），并且要能被回放：少了它，回放只能按 {@code (sex, 年龄档)} 聚合重建， 批次身份会漂成事件派生的新 id。故 S2
 * 给事件补一个<b>可空</b>批次身份组件：
 *
 * <ul>
 *   <li>非 TRANSFER 事件可以为 null（BIRTH 按"最小年龄档"落、DEATH 按批次瀑布扣）；给了就精确落到该批次；
 *   <li>TRANSFER 事件应带上批次的稳定身份（{@code lotId}）；为 null 时按聚合语义回放（见 {@code HouseholdBook.applyEvent}）。
 * </ul>
 *
 * <p>★ <b>S2 增补：{@code count} 对 {@link PopulationEventType#GM_ADJUST} 允许为负</b>——GM 直调"可正可负"（S2
 * 任务书）， 而其余事件类型的人数/变动量恒非负。负的 GM_ADJUST 表示"GM 抽走/下调 |count| 个人"，是回放必须区分的方向。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param id 事件稳定身份；非空白
 * @param householdId 事件所属家户；不得为 null
 * @param type 事件类型；不得为 null
 * @param sex 涉及性别；不得为 null
 * @param ageBracketId 涉及年龄档短名；非空白
 * @param count 人数；非负；{@link PopulationEventType#GM_ADJUST} 可为负（见类注）
 * @param day 发生日（tick）；不得为负
 * @param reason 原因档；非空白
 * @param source 来源（GM / 结算 / 命令等）；非空白
 * @param lotId 涉及的批次稳定身份；可空（见类注）
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
    String source,
    PeopleLotId lotId) {

  /**
   * ★ <b>不影响既有 9 参构造的便捷形态</b>（{@code lotId = null}）：S1 的事件形状与调用点原样可用； 需要批次精确回放的调用方走 10 参 canonical
   * 构造。
   */
  public HouseholdPopulationEvent(
      String id,
      HouseholdId householdId,
      PopulationEventType type,
      Sex sex,
      String ageBracketId,
      long count,
      long day,
      String reason,
      String source) {
    this(id, householdId, type, sex, ageBracketId, count, day, reason, source, null);
  }

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
    if (count < 0L && type != PopulationEventType.GM_ADJUST) {
      throw new IllegalArgumentException(
          "HouseholdPopulationEvent.count 不得为负（GM_ADJUST 除外）: " + count + " / " + type);
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
