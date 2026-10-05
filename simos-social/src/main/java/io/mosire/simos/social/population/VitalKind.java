package io.mosire.simos.social.population;

/**
 * 每 tick 生死余数累加器的<b>方向键</b>（2026-10-09 Social 每 tick 计划 §3.4）：{@code BIRTH} / {@code DEATH}。
 *
 * <p>★ 它只用于 {@link SocialVitalRemainder} 的键与日志/审计，不改变 {@code HouseholdPopulationEvent}
 * 的事件类型词表（那仍是 {@code PopulationEventType}）。</p>
 *
 * <p>★ {@code name()} 就是它的稳定拼写：余数表进 JSON 时按它读写，改名视同改线格式。</p>
 */
public enum VitalKind {
  BIRTH,
  DEATH
}
