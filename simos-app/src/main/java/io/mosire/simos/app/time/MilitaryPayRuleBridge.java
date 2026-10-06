package io.mosire.simos.app.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>军俸政策 → P4a 周期规则桥接</b>（P4b，2026-10-15；纯函数，不写任何状态）：把每个 Army 单位的
 * {@link MilitaryPayPolicy} 现算成若干条 {@link HouseholdPeriodicAdjustment}，交给 P4a 的无状态到期执行器。
 *
 * <pre>
 * 逐 Army 单位（unitId 升序）：
 *   0. policy 空/disabled ⇒ 跳过（不产生规则、不是 gap）
 *   1. masterGov 空 ⇒ gap（具名读数，跳过该单位，不阻断其它单位）
 *   2. payer = GovernmentHouseholds.of(masterGov.value())
 *      payer 必须已在 Social 家户表、已在 economy.classes（账户登记的前提）、
 *      且 economy.governments[gov-unit-&lt;masterGov&gt;].treasury == HouseholdActors.of(payer)
 *      —— 任一不成立 ⇒ gap 并跳过该单位（不自动造户/造账；交给下一轮或具名修复）
 *   3. 逐政策家户（householdId 升序）：
 *      - 家户不在 unit.households()（坏数据） ⇒ gap 并跳过该户；
 *      - payer == payee（自转不是一条发生额） ⇒ gap 并跳过该户；
 *      - 三张表里该户没有一条腿 ⇒ 不生成规则（空腿家户不落规则）；
 *      - 生成 id=army-pay:&lt;unitId&gt;:&lt;householdId&gt;、reason=MILITARY_SALARY、
 *        policySource=army:&lt;unitId&gt;、period/phase/starts/expires 逐值取 policy；
 *        grain→GRAIN、cloth→CLOTH、money→SILVER_CURRENCY。
 * </pre>
 *
 * <p>★★ <b>为什么规则是“现算派生件”、不写 EconomyData</b>：政策的唯一权威是 unit 的
 * {@code ArmyFormation.militaryPayPolicy}；把它复制进 {@code EconomyData.periodicAdjustments} 会造出第二份真相，
 * 政策变更/删除后还要维护两侧同步。本类每天在日循环里从当前 unit 状态重算，执行器只读这些瞬态规则。
 *
 * <p>★★ <b>失败语义（不抛、不阻断）</b>：单位级“国库户/账户不成立”、家户级“坏数据/自转”、以及单条规则构造被
 * {@link HouseholdPeriodicAdjustment} 拒，都记一条具名 gap 后继续下一个单位/家户；只有入参 null 才 {@link
 * NullPointerException}。规则表保序不可变（单位 id 升序 → 家户 id 升序）。
 *
 * <p>★ {@code day} 不在本类做到期筛选（到期判据唯一在执行器：无状态 due）；保留该参数是为了与日循环的调用坐标一致，
 * 也便于将来在不改签名的前提下加“当日可见性”读数。
 */
public final class MilitaryPayRuleBridge {

  /** P4a 规则 id 的固定前缀（本类拼写规则名的唯一一处）。 */
  public static final String RULE_ID_PREFIX = "army-pay:";

  /** P4a 规则审计串的固定前缀（{@code army:<unitId>}）。 */
  public static final String POLICY_SOURCE_PREFIX = "army:";

  private MilitaryPayRuleBridge() {}

  /**
   * 纯函数主入口：返回当天应从军俸政策派生的规则（<b>不</b>筛到期——由 {@link
   * PeriodicHouseholdAdjustmentExecutor#isDue} 按绝对世界日判）。需要 gap 读数时走 {@link #deriveReport}。
   */
  public static List<HouseholdPeriodicAdjustment> derive(
      UnitState units, SocialData social, EconomyData economy, long day) {
    return deriveReport(units, social, economy, day).rules();
  }

  /**
   * 与 {@link #derive} 同一份推导，附带单位/政策/gap 读数（P4b 的 DEBUG 汇总用）。返回的规则与 gap 都保序不可变。
   */
  public static Report deriveReport(
      UnitState units, SocialData social, EconomyData economy, long day) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(economy, "economy");
    List<Unit> orderedUnits = new ArrayList<>(units.units().values());
    orderedUnits.sort(Comparator.comparing(unit -> unit.id().value()));
    List<HouseholdPeriodicAdjustment> rules = new ArrayList<>();
    List<String> gaps = new ArrayList<>();
    int armyUnits = 0;
    int enabledPolicies = 0;
    for (Unit unit : orderedUnits) {
      if (!(unit.module().orElse(null) instanceof ArmyFormation armyFormation)) {
        continue;
      }
      armyUnits++;
      MilitaryPayPolicy policy = armyFormation.militaryPayPolicy();
      if (policy == null || !policy.enabled()) {
        continue; // 停发/旧档：不是 gap，也不产生任何规则。
      }
      enabledPolicies++;
      if (armyFormation.masterGov().isEmpty()) {
        gaps.add("unit=" + unit.id().value() + " gap=masterGov-missing");
        continue;
      }
      UnitId masterGov = armyFormation.masterGov().get();
      HouseholdId payer;
      try {
        payer = GovernmentHouseholds.of(masterGov.value());
      } catch (IllegalArgumentException invalidReference) {
        gaps.add(
            "unit="
                + unit.id().value()
                + " gap=payer-id-invalid payer="
                + masterGov.value()
                + " detail="
                + invalidReference.getMessage());
        continue;
      }
      if (!social.households().containsKey(payer)) {
        gaps.add(
            "unit=" + unit.id().value() + " gap=payer-not-in-social payer=" + payer.value());
        continue;
      }
      GovernmentId governmentId;
      try {
        governmentId = GovernmentIds.ofUnit(masterGov.value());
      } catch (IllegalArgumentException invalidReference) {
        gaps.add(
            "unit="
                + unit.id().value()
                + " gap=government-id-invalid gov="
                + masterGov.value()
                + " detail="
                + invalidReference.getMessage());
        continue;
      }
      Government government = economy.governments().get(governmentId);
      if (government == null) {
        gaps.add(
            "unit="
                + unit.id().value()
                + " gap=government-record-missing gov="
                + governmentId.value());
        continue;
      }
      HouseholdId treasuryHousehold;
      try {
        ActorRef treasury = government.treasury();
        treasuryHousehold = HouseholdActors.householdOf(treasury);
      } catch (IllegalArgumentException invalidTreasury) {
        gaps.add(
            "unit="
                + unit.id().value()
                + " gap=treasury-actor-invalid gov="
                + governmentId.value()
                + " detail="
                + invalidTreasury.getMessage());
        continue;
      }
      if (!treasuryHousehold.equals(payer)) {
        gaps.add(
            "unit="
                + unit.id().value()
                + " gap=treasury-mismatch expected="
                + payer.value()
                + " actual="
                + treasuryHousehold.value());
        continue;
      }
      if (!economy.classes().containsKey(payer)) {
        // 账户登记/有账的前提是经济家户行存在；真正的 actor 账户存在性由 P4a 执行器 skip（同一 gap 读数口径）。
        gaps.add(
            "unit=" + unit.id().value() + " gap=payer-classrow-missing payer=" + payer.value());
        continue;
      }
      Set<HouseholdId> policyHouseholds = new LinkedHashSet<>();
      policyHouseholds.addAll(policy.grainPerHouseholdPerCycle().keySet());
      policyHouseholds.addAll(policy.clothPerHouseholdPerCycle().keySet());
      policyHouseholds.addAll(policy.moneyPerHouseholdPerCycle().keySet());
      List<HouseholdId> orderedHouseholds = new ArrayList<>(policyHouseholds);
      orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
      for (HouseholdId household : orderedHouseholds) {
        if (!unit.households().contains(household)) {
          // 正常路径 UnitState 构造期已拒；这里是纯函数对“手工拼出的坏状态”的防御：跳过该户，不阻断其它户/单位。
          gaps.add(
              "unit="
                  + unit.id().value()
                  + " household="
                  + household.value()
                  + " gap=payee-not-in-unit");
          continue;
        }
        if (household.equals(payer)) {
          // 自转不是一条发生额（HouseholdPeriodicAdjustment 也会拒）；桥接层先具名跳过，避免一条坏数据打红整天。
          gaps.add(
              "unit="
                  + unit.id().value()
                  + " household="
                  + household.value()
                  + " gap=payer-equals-payee");
          continue;
        }
        Map<CommodityId, Long> goods = new LinkedHashMap<>();
        Long grain = policy.grainPerHouseholdPerCycle().get(household);
        if (grain != null) {
          goods.put(EconomyCommodities.GRAIN, grain);
        }
        Long cloth = policy.clothPerHouseholdPerCycle().get(household);
        if (cloth != null) {
          goods.put(EconomyCommodities.CLOTH, cloth);
        }
        Map<CurrencyId, Long> money = new LinkedHashMap<>();
        Long silver = policy.moneyPerHouseholdPerCycle().get(household);
        if (silver != null) {
          money.put(MoneyVocabulary.SILVER_CURRENCY, silver);
        }
        if (goods.isEmpty() && money.isEmpty()) {
          continue; // 空腿家户不生成规则（政策在别户有腿，本户没有 == 本户不发）。
        }
        try {
          rules.add(
              new HouseholdPeriodicAdjustment(
                  PeriodicHouseholdAdjustmentId.parse(
                      RULE_ID_PREFIX + unit.id().value() + ":" + household.value()),
                  payer,
                  Optional.of(household),
                  goods,
                  money,
                  DeductionReason.MILITARY_SALARY,
                  policy.periodDays(),
                  policy.phaseDay(),
                  policy.startsOnDay(),
                  policy.expiresOnDay(),
                  POLICY_SOURCE_PREFIX + unit.id().value()));
        } catch (IllegalArgumentException rejectedRule) {
          gaps.add(
              "unit="
                  + unit.id().value()
                  + " household="
                  + household.value()
                  + " gap=rule-rejected detail="
                  + rejectedRule.getMessage());
        }
      }
    }
    return new Report(armyUnits, enabledPolicies, rules, gaps);
  }

  /**
   * 推导读数：{@code units} = 扫到的 Army 单位数；{@code policies} = 其中 enabled 政策数；{@code rules} = 派生规则（保序
   * = 单位 id 升序 → 家户 id 升序）；{@code gaps} = 具名跳过读数（每条形如 {@code unit=... household=... gap=...}）。
   */
  public record Report(
      int units, int policies, List<HouseholdPeriodicAdjustment> rules, List<String> gaps) {

    public Report {
      if (units < 0 || policies < 0) {
        throw new IllegalArgumentException(
            "MilitaryPayRuleBridge.Report 计数不得为负: units=" + units + " policies=" + policies);
      }
      rules = List.copyOf(rules);
      gaps = List.copyOf(gaps);
    }

    /** 当天没有任何 Army 单位/政策/规则时的空读数。 */
    public static Report empty() {
      return new Report(0, 0, List.of(), List.of());
    }
  }
}
