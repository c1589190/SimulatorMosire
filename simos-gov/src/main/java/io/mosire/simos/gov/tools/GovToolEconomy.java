package io.mosire.simos.gov.tools;

import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 政府工具面的**经济口**：economy 切片上政府工具需要的那点事实 + {@code economy.SetGovernmentPolicy} 的解析/合并。
 *
 * <p>★★ <b>为什么这一整块必须注入</b>（派单书 §三.1）：2026-10-12 追加裁定 12 §21.9 D-1 把政府的**三类政策**（口岸/预算/编制） 搬进了 {@code
 * EconomyData.governments} ⇒ 相关事实与命令类型的唯一拼写点都在 {@code simos-economy}（**实现模块**），而 gov 的允许面是 {@code
 * util/map/unit/social/economy-api/ga-spi} —— 看不见 economy 实现，也看不见它的 {@code govpolicy} 值类型与 {@code
 * EconomyGovPolicyPayloads} 解析器。⇒ 由 app（唯一同时认识两边的模块）转调。
 *
 * <p>★★ <b>政策值类型是**镜像记录**，不是"另造一套口径"</b>：{@code simos-economy} 的 {@code GovAdministrationPlan} /
 * {@code GovBudgetPolicy} / {@code GovPostTier} / {@code GovBudgetLine} / {@code
 * GovOfficialSalaryRule} 都是 record，工具面既要用它们**渲染视图**、又要用它们判**幂等重放** （{@code previous.equals(next)} ⇒
 * noop）。本接口因此声明**逐字段同形**的镜像记录，由 app 双向转换：
 *
 * <ul>
 *   <li>字段**一个不少**（含 gov 侧渲染用不到的口岸三维）——否则"只差一个没镜像的字段"会静默变成 noop，那不是零行为变化；
 *   <li>转换只有一处（app 适配器），gov 侧只读镜像 ⇒ 不存在"两处拼政策形状"。
 * </ul>
 *
 * <p>★ {@link OfficialRate}、{@code CurrencyId}、{@code GovernmentId/GovernmentIds} 等**契约层**类型（{@code
 * economy-api}）不在镜像之列：gov 直接使用它们（允许面内）。
 */
public interface GovToolEconomy {

  // ── 币种 / 政府记录 / 经济行 / 告警 ────────────────────────────────────────────────

  /** 币种在不在世界词表里（{@code EconomyData.currencies().containsKey}）。 */
  boolean currencyDefined(SimulationState state, String currencyId);

  /** 币种显示名；{@code null} = 该币种未定义（不编一个默认名）。 */
  String currencyDisplayName(SimulationState state, String currencyId);

  /** 币种最小单位精度；{@code null} = 该币种未定义。 */
  Long currencyScale(SimulationState state, String currencyId);

  /** 已有发行记录条数（{@code EconomyData.moneyIssuances().size()}；读口只报事实）。 */
  int moneyIssuanceCount(SimulationState state);

  /** 该 GOV 单位有没有政府记录（{@code governmentOrDefault} 的"explicit / neutral-default"唯一判据）。 */
  boolean governmentRegistered(SimulationState state, String govUnitId);

  /** 该 GOV 的 {@code govUnitId（issuable）} 原样文本（{@code Set.toString()}）；{@code null} = 未登记。 */
  String governmentIssuableText(SimulationState state, String govUnitId);

  /** 该 GOV 的政府记录发不发行这个币种（{@code issuable.contains}）；未登记 ⇒ false。 */
  boolean governmentIssuesCurrency(SimulationState state, String govUnitId, String currencyId);

  /** 该 GOV 的官方汇率表里 {@code (base, quote)} 那一条；没有 ⇒ {@code null}。 */
  OfficialRate officialRate(
      SimulationState state, String govUnitId, String currencyBase, String currencyQuote);

  /** 经济切片里该家户行的当刻劳动量；{@code null} = 没有经济行（不填 0）。 */
  Long classLaborMilli(SimulationState state, HouseholdId household);

  /**
   * 经济切片里的危机信号（**迭代序 = {@code EconomyData.crisisSignals()} 自身的序**；调用方自己筛 {@code ADMIN_*} 并按 (day,
   * kind) 排序 —— 并列时保留本序 ⇒ 不许重排）。
   */
  List<CrisisSignal> crisisSignals(SimulationState state);

  // ── 政策：子集名 / 现值 / 解析 / 合并 ────────────────────────────────────────────────

  /** {@code EconomyGovPolicyPayloads.ADMINISTRATION}（{@code "administration"}）的唯一拼写点。 */
  String administrationSubsetName();

  /** {@code EconomyGovPolicyPayloads.BUDGET}（{@code "budget"}）的唯一拼写点。 */
  String budgetSubsetName();

  /** 该 GOV 的现行编制计划（未登记 ⇒ 中性默认，与 {@code governmentOrDefault} 同口径）。 */
  AdministrationPlan administrationPlan(SimulationState state, String govUnitId);

  /** 该 GOV 的现行预算政策（未登记 ⇒ 中性默认，同口径）。 */
  BudgetPolicy budgetPolicy(SimulationState state, String govUnitId);

  /**
   * 解析 {@code policies.administration} 子集（{@code EconomyGovPolicyPayloads.administrationPlan}）。
   *
   * @throws IllegalArgumentException 子集形状非法（解析器的既有拒因与文案逐字不变）
   */
  AdministrationPlan parseAdministrationPlan(String subsetJson);

  /** 子集里声明的编辑模式名（{@code EconomyGovPolicyPayloads.editMode(subset).name()}；缺省 = {@code PATCH}）。 */
  String budgetEditMode(String subsetJson);

  /**
   * 工具参数 {@code mode} 的规范化（{@code GovBudgetPolicyEditMode.parse(raw).name()}）。
   *
   * @throws IllegalArgumentException 只支持 PATCH|REPLACE（解析器的既有拒因与文案逐字不变）
   */
  String parseBudgetEditMode(String raw);

  /**
   * {@code PATCH} 语义的合并：以**现值**（{@code portPolicy + budgetPolicy + administrationPlan}）为基准算出 next
   * 预算政策（{@code EconomyGovPolicyPayloads.budgetPolicy(subset, current)}）。
   */
  BudgetPolicy mergeBudgetPolicy(SimulationState state, String govUnitId, String subsetJson);

  // ── 镜像记录（逐字段同形；仅 gov 工具面使用） ───────────────────────────────────────

  /** {@code economy.govpolicy.GovPostTier} 的镜像（含口岸权重：gov 不渲染它，但它参与 policy 的逐值相等）。 */
  record PostTier(
      String tierId,
      long securityWeightPerMille,
      long paperworkWeightPerMille,
      long portWeightPerMille) {}

  /** {@code economy.govpolicy.GovAdministrationPlan} 的镜像（11 个字段一个不少）。 */
  record AdministrationPlan(
      long securityPlannedLaborMilli,
      long paperworkPlannedLaborMilli,
      long portPlannedLaborMilli,
      List<PostTier> postTiers,
      long securitySupplyStaticModifierPerMille,
      long paperworkSupplyStaticModifierPerMille,
      long portSupplyStaticModifierPerMille,
      long securityDemandStaticModifierPerMille,
      long paperworkDemandStaticModifierPerMille,
      long portDemandStaticModifierPerMille,
      long supernumerarySqrtCoefficient) {

    public AdministrationPlan {
      // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf —— 迭代序不是内容的纯函数，SpotBugs 也认不出它的不可变性）。
      postTiers =
          Collections.unmodifiableList(
              new ArrayList<>(Objects.requireNonNull(postTiers, "postTiers")));
    }
  }

  /** {@code economy.govpolicy.GovBudgetLine} 的镜像；{@code category} 取枚举 {@code name()}。 */
  record BudgetLine(String category, long minPerCycle, long capPerCycle) {}

  /** {@code economy.govpolicy.GovOfficialSalaryRule} 的镜像。 */
  record OfficialSalaryRule(long grainMilliPerCommittedHour, long silverMilliPerCommittedHour) {}

  /** {@code economy.govpolicy.GovBudgetPolicy} 的镜像（保序类别表 + 工资规则 + 上缴比例）。 */
  record BudgetPolicy(
      List<BudgetLine> orderedCategories,
      OfficialSalaryRule officialSalaryRule,
      long remittancePerMilleToSuperior) {

    public BudgetPolicy {
      orderedCategories =
          Collections.unmodifiableList(
              new ArrayList<>(Objects.requireNonNull(orderedCategories, "orderedCategories")));
      Objects.requireNonNull(officialSalaryRule, "officialSalaryRule");
    }
  }

  /** {@code economy.model.HexCrisisSignal} 里 gov 读口用到的那几项（保序 evidence）。 */
  record CrisisSignal(
      String id,
      HexCoord hex,
      String kind,
      int severity,
      long day,
      Map<String, Long> evidence,
      String reason) {

    public CrisisSignal {
      // ★ 保序冻结在赋值处（evidence 的迭代序是既有读口的一部分）。
      evidence =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(evidence, "evidence")));
    }
  }
}
