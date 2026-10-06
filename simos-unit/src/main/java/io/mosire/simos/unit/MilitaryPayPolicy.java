package io.mosire.simos.unit;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>军队军俸政策</b>（P4b，2026-10-15）：{@code ArmyFormation} 的第四组件，声明“每隔多少天、在周期哪一天、从哪天起、到哪天止，
 * 由国库给本单位每个军户各发多少粮/布/钱”。
 *
 * <p>★★ <b>它只声明内部分摊，不做人口平均</b>：每个家户拿多少由三张表<b>逐家户显式给出</b>；同一个军户不在表里就没它的腿，
 * 绝不按人口/户数折算、不做跨户比例。周期/相位语义与 P4a 的经济侧规则
 * {@link io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment} 完全一致（无状态 due：{@code day >=
 * startsOnDay && (expires 空 || day <= expiresOnDay) && (day - startsOnDay) % periodDays == phaseDay}）。
 *
 * <p>★★ <b>为什么它住在 {@code simos-unit} 且不引 economy-api</b>：政策是 unit 侧对“该给军户多少粮/布/钱”的声明，
 * 只用 unit 自己的资源词表（{@code grain}/{@code cloth}/{@code money} 三张以 {@link HouseholdId} 为键的表）；
 * 到 P4a 规则的商品/币种 id 映射由 {@code simos-app} 的桥接完成（见 P4b 计划 §3）。若在这里 import economy-api，unit 与
 * 经济切片就会被一纸政策绑成双向依赖。
 *
 * <p>★★ <b>构造期不变量（坏数据 fail-closed）</b>：
 *
 * <ol>
 *   <li>{@code periodDays > 0}；{@code phaseDay ∈ [0, periodDays)}；{@code startsOnDay ≥ 0}；{@code expiresOnDay}
 *       空 = 永久，非空必须 {@code ≥ startsOnDay}；{@code expiresOnDay == null}（旧档/手写 JSON 缺键）统一收成
 *       {@link OptionalLong#empty()}；
 *   <li>三张表的键与值都非 null、逐值 {@code > 0}；
 *   <li><b>三张表至少一腿非空</b>——唯一的例外是 {@link #disabled()} 的全空形状（见下）；
 *   <li>三张表保序不可变（{@code LinkedHashMap} + 赋值处 {@code Collections.unmodifiableMap}；<b>绝不用</b>
 *       {@code Map.copyOf}——它的迭代序不是内容的纯函数）；
 *   <li>表中键必须出现在所属 {@code Unit.households()} 里（由 {@link UnitState} 构造期统一强判，拒绝“给不存在于本单位的人发钱”）。
 * </ol>
 *
 * <p>★★ <b>{@link #disabled()} 是唯一的全空态</b>：三张空表 + {@code periodDays=1 / phaseDay=0 / startsOnDay=0 /
 * expires 空}，且 {@link #enabled()} 为 {@code false}。canonical 构造器只在这个形状下接受全空表；其它排期的全空政策一律具名拒
 * （“声明了周期却又没有一条腿”没有意义）。命令层把“三张表全空”的载荷归一成 {@code disabled()}（P4b §2.3：允许，表示停发）。
 *
 * @param periodDays 周期天数，{@code > 0}
 * @param phaseDay 周期内相位日，{@code ∈ [0, periodDays)}
 * @param startsOnDay 首次可能的起始绝对世界日，{@code ≥ 0}（day 0 是创世）
 * @param expiresOnDay 到期绝对世界日；空 = 永久；非空必须 {@code ≥ startsOnDay}
 * @param grainPerHouseholdPerCycle 逐军户每周期粮额（键 ⊆ Unit.households；值 {@code > 0}；保序不可变）
 * @param clothPerHouseholdPerCycle 逐军户每周期布额（键 ⊆ Unit.households；值 {@code > 0}；保序不可变）
 * @param moneyPerHouseholdPerCycle 逐军户每周期钱额（键 ⊆ Unit.households；值 {@code > 0}；保序不可变）
 */
public record MilitaryPayPolicy(
    long periodDays,
    long phaseDay,
    long startsOnDay,
    OptionalLong expiresOnDay,
    Map<HouseholdId, Long> grainPerHouseholdPerCycle,
    Map<HouseholdId, Long> clothPerHouseholdPerCycle,
    Map<HouseholdId, Long> moneyPerHouseholdPerCycle) {

  /** 全空停发态的唯一实例（record 不可变 ⇒ 共享安全；三张表都是 {@code Map.of()} 的不可变空表）。 */
  private static final MilitaryPayPolicy DISABLED =
      new MilitaryPayPolicy(
          1L, 0L, 0L, OptionalLong.empty(), Map.of(), Map.of(), Map.of());

  public MilitaryPayPolicy {
    // ★ 旧档/手写 JSON 缺 expiresOnDay 键时 Jackson 可能绑成 null ⇒ 统一收成 empty（与 P4a 规则同款口径）。
    if (expiresOnDay == null) {
      expiresOnDay = OptionalLong.empty();
    }
    requireValidSchedule(periodDays, phaseDay, startsOnDay, expiresOnDay);
    grainPerHouseholdPerCycle = positiveAmounts(grainPerHouseholdPerCycle, "grainPerHouseholdPerCycle");
    clothPerHouseholdPerCycle = positiveAmounts(clothPerHouseholdPerCycle, "clothPerHouseholdPerCycle");
    moneyPerHouseholdPerCycle = positiveAmounts(moneyPerHouseholdPerCycle, "moneyPerHouseholdPerCycle");
    if (grainPerHouseholdPerCycle.isEmpty()
        && clothPerHouseholdPerCycle.isEmpty()
        && moneyPerHouseholdPerCycle.isEmpty()) {
      // ★ 全空只允许 disabled() 的形状；其它排期的全空是“声明了政策却没有一条腿”的坏数据。
      boolean disabledShape =
          periodDays == 1L && phaseDay == 0L && startsOnDay == 0L && expiresOnDay.isEmpty();
      if (!disabledShape) {
        throw new IllegalArgumentException(
            "MilitaryPayPolicy 三张表至少一腿非空；全空只能是 disabled() 形状"
                + "（periodDays=1/phaseDay=0/startsOnDay=0/expires 空）: periodDays="
                + periodDays
                + "，phaseDay="
                + phaseDay
                + "，startsOnDay="
                + startsOnDay
                + "，expiresOnDay="
                + expiresOnDay);
      }
    }
  }

  /**
   * ★ 排期字段的独立校验入口（P4b 载荷层用）：命令把“三张表全空”归一成 {@link #disabled()} 之前，仍要先把
   * {@code periodDays/phaseDay/startsOnDay/expiresOnDay} 按同一条判据验一遍—— 否则 {@code periodDays=0} 的全空载荷会被一句
   * “全空 = 停发”静默放过。
   *
   * @throws IllegalArgumentException 任一排期不变量被违反（理由具名）
   */
  public static void requireValidSchedule(
      long periodDays, long phaseDay, long startsOnDay, OptionalLong expiresOnDay) {
    Objects.requireNonNull(expiresOnDay, "expiresOnDay 不得为 null（永久请给 OptionalLong.empty()）");
    if (periodDays <= 0L) {
      throw new IllegalArgumentException("MilitaryPayPolicy.periodDays 必须 > 0: " + periodDays);
    }
    if (phaseDay < 0L || phaseDay >= periodDays) {
      throw new IllegalArgumentException(
          "MilitaryPayPolicy.phaseDay 必须 ∈ [0, periodDays): phaseDay="
              + phaseDay
              + "，periodDays="
              + periodDays);
    }
    if (startsOnDay < 0L) {
      throw new IllegalArgumentException("MilitaryPayPolicy.startsOnDay 不得为负: " + startsOnDay);
    }
    if (expiresOnDay.isPresent() && expiresOnDay.getAsLong() < startsOnDay) {
      throw new IllegalArgumentException(
          "MilitaryPayPolicy.expiresOnDay 必须 ≥ startsOnDay: expiresOnDay="
              + expiresOnDay.getAsLong()
              + "，startsOnDay="
              + startsOnDay);
    }
  }

  /**
   * ★ 停发态：三张空表 + {@code periodDays=1 / phaseDay=0 / startsOnDay=0 / expires 空}。{@link #enabled()} 为
   * {@code false}。
   *
   * <p>语义：军俸政策“没有声明任何腿”= 不生成任何 P4a 规则；它是 {@code ArmyFormation} 缺 policy 键（旧档）与命令层
   * “三表全空”载荷的唯一归一落点。
   */
  public static MilitaryPayPolicy disabled() {
    return DISABLED;
  }

  /** {@code true} = 至少一腿非空（会由 app 桥接派生成 P4a 规则）；{@code false} 仅 {@link #disabled()} 为真。 */
  public boolean enabled() {
    return !(grainPerHouseholdPerCycle.isEmpty()
        && clothPerHouseholdPerCycle.isEmpty()
        && moneyPerHouseholdPerCycle.isEmpty());
  }

  /** 逐表拷贝、逐值判正、键值非 null，并冻在赋值处（三张表共用同一形制）。 */
  private static Map<HouseholdId, Long> positiveAmounts(
      Map<HouseholdId, Long> amounts, String field) {
    if (amounts == null) {
      throw new IllegalArgumentException("MilitaryPayPolicy." + field + " 不得为 null（没有腿请给空 map）");
    }
    Map<HouseholdId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : amounts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "MilitaryPayPolicy." + field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "MilitaryPayPolicy." + field + " 的每周期额必须 > 0（0 不是一条腿，请删键）: "
                + entry.getKey()
                + "="
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
  }
}
