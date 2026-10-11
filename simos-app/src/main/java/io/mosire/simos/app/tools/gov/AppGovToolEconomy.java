package io.mosire.simos.app.tools.gov;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetLine;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicyEditMode;
import io.mosire.simos.economy.govpolicy.GovOfficialSalaryRule;
import io.mosire.simos.economy.govpolicy.GovPostTier;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.spi.EconomyGovPolicyPayloads;
import io.mosire.simos.gov.tools.GovToolEconomy;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * {@link GovToolEconomy} 的宿主实现（组合根）：**逐条转调既有唯一实现**，并把 economy 的政策值类型（record）**逐字段**翻成 gov
 * 侧的镜像记录（{@code simos-economy} 不在 gov 允许面 ⇒ 见端口类注）。
 *
 * <p>★ <b>政策解析不重写</b>：{@code policies.administration} / {@code policies.budget} 的解析与 PATCH 合并仍由
 * {@code EconomyGovPolicyPayloads} 一处做（工具面与命令 handler 同源），本类只把其输入（子集 JSON）与输出（record）搬运。
 *
 * <p>★ <b>economy 切片提取的拒因/文案</b>逐字照抄迁入前的 {@code GovToolSupport.economyData}（{@code
 * IllegalStateException} + 两句原文）——装配故障不许降级成"没有币种/没有政策"。
 */
public final class AppGovToolEconomy implements GovToolEconomy {

  /** 与工具面同一台 mapper（共享基座出厂配置）；本类只做 JSON ↔ 子集的搬运。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public boolean currencyDefined(SimulationState state, String currencyId) {
    return economyOf(state).currencies().containsKey(new CurrencyId(currencyId));
  }

  @Override
  public String currencyDisplayName(SimulationState state, String currencyId) {
    var def = economyOf(state).currencies().get(new CurrencyId(currencyId));
    return def == null ? null : def.displayName();
  }

  @Override
  public Long currencyScale(SimulationState state, String currencyId) {
    var def = economyOf(state).currencies().get(new CurrencyId(currencyId));
    return def == null ? null : (long) def.scale();
  }

  @Override
  public int moneyIssuanceCount(SimulationState state) {
    return economyOf(state).moneyIssuances().size();
  }

  @Override
  public boolean governmentRegistered(SimulationState state, String govUnitId) {
    return economyOf(state).governments().containsKey(governmentIdOf(govUnitId));
  }

  @Override
  public String governmentIssuableText(SimulationState state, String govUnitId) {
    Government government = governmentOf(state, govUnitId);
    return government == null ? null : government.issuable().toString();
  }

  @Override
  public boolean governmentIssuesCurrency(
      SimulationState state, String govUnitId, String currencyId) {
    Government government = governmentOf(state, govUnitId);
    return government != null && government.issuable().contains(new CurrencyId(currencyId));
  }

  @Override
  public OfficialRate officialRate(
      SimulationState state, String govUnitId, String currencyBase, String currencyQuote) {
    Government government = governmentOf(state, govUnitId);
    if (government == null) {
      return null;
    }
    return OfficialRate.find(
            government.officialRates(), new CurrencyId(currencyBase), new CurrencyId(currencyQuote))
        .orElse(null);
  }

  @Override
  public Long classLaborMilli(SimulationState state, HouseholdId household) {
    var row = economyOf(state).classes().get(household);
    return row == null ? null : row.laborMilli();
  }

  @Override
  public List<CrisisSignal> crisisSignals(SimulationState state) {
    List<CrisisSignal> signals = new ArrayList<>();
    for (HexCrisisSignal signal : economyOf(state).crisisSignals().values()) {
      signals.add(
          new CrisisSignal(
              signal.id().value(),
              signal.hex(),
              signal.kind().name(),
              signal.severity(),
              signal.day(),
              // ★ 保序冻结（evidence 的迭代序照旧；不用 Map.copyOf）。
              Collections.unmodifiableMap(new LinkedHashMap<>(signal.evidence())),
              signal.reason()));
    }
    return Collections.unmodifiableList(signals);
  }

  @Override
  public String administrationSubsetName() {
    return EconomyGovPolicyPayloads.ADMINISTRATION;
  }

  @Override
  public String budgetSubsetName() {
    return EconomyGovPolicyPayloads.BUDGET;
  }

  @Override
  public AdministrationPlan administrationPlan(SimulationState state, String govUnitId) {
    return mirror(economyOf(state).governmentOrDefault(govUnitId).administrationPlan());
  }

  @Override
  public BudgetPolicy budgetPolicy(SimulationState state, String govUnitId) {
    return mirror(economyOf(state).governmentOrDefault(govUnitId).budgetPolicy());
  }

  @Override
  public AdministrationPlan parseAdministrationPlan(String subsetJson) {
    return mirror(EconomyGovPolicyPayloads.administrationPlan(parse(subsetJson)));
  }

  @Override
  public String budgetEditMode(String subsetJson) {
    return EconomyGovPolicyPayloads.editMode(parse(subsetJson)).name();
  }

  @Override
  public String parseBudgetEditMode(String raw) {
    return GovBudgetPolicyEditMode.parse(raw).name();
  }

  @Override
  public BudgetPolicy mergeBudgetPolicy(
      SimulationState state, String govUnitId, String subsetJson) {
    Government government = economyOf(state).governmentOrDefault(govUnitId);
    GovBudgetPolicy next =
        EconomyGovPolicyPayloads.budgetPolicy(
            parse(subsetJson),
            new EconomyGovPolicyPayloads.GovPolicyCurrent(
                government.portPolicy(),
                government.budgetPolicy(),
                government.administrationPlan()));
    return mirror(next);
  }

  // ── 内部：切片取用 / 身份换算 / 镜像转换 ──────────────────────────────────────────────

  private static GovernmentId governmentIdOf(String govUnitId) {
    return GovernmentIds.ofUnit(govUnitId);
  }

  private static Government governmentOf(SimulationState state, String govUnitId) {
    return economyOf(state).governments().get(governmentIdOf(govUnitId));
  }

  private static JsonNode parse(String subsetJson) {
    Objects.requireNonNull(subsetJson, "subsetJson");
    try {
      JsonNode node = MAPPER.readTree(subsetJson);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("政策子集必须是 JSON 对象");
      }
      return node;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("政策子集不是合法 JSON: " + e.getOriginalMessage(), e);
    }
  }

  /** {@code GovAdministrationPlan} → gov 镜像（11 个字段一个不少；档位表保序冻结）。 */
  private static AdministrationPlan mirror(GovAdministrationPlan plan) {
    List<PostTier> tiers = new ArrayList<>(plan.postTiers().size());
    for (GovPostTier tier : plan.postTiers()) {
      tiers.add(
          new PostTier(
              tier.tierId(),
              tier.securityWeightPerMille(),
              tier.paperworkWeightPerMille(),
              tier.portWeightPerMille()));
    }
    return new AdministrationPlan(
        plan.securityPlannedLaborMilli(),
        plan.paperworkPlannedLaborMilli(),
        plan.portPlannedLaborMilli(),
        Collections.unmodifiableList(tiers),
        plan.securitySupplyStaticModifierPerMille(),
        plan.paperworkSupplyStaticModifierPerMille(),
        plan.portSupplyStaticModifierPerMille(),
        plan.securityDemandStaticModifierPerMille(),
        plan.paperworkDemandStaticModifierPerMille(),
        plan.portDemandStaticModifierPerMille(),
        plan.supernumerarySqrtCoefficient());
  }

  /** {@code GovBudgetPolicy} → gov 镜像（类别表保序 + 工资规则 + 上缴比例）。 */
  private static BudgetPolicy mirror(GovBudgetPolicy policy) {
    List<BudgetLine> lines = new ArrayList<>(policy.orderedCategories().size());
    for (GovBudgetLine line : policy.orderedCategories()) {
      lines.add(new BudgetLine(line.category().name(), line.minPerCycle(), line.capPerCycle()));
    }
    GovOfficialSalaryRule salary = policy.officialSalaryRule();
    return new BudgetPolicy(
        Collections.unmodifiableList(lines),
        new OfficialSalaryRule(
            salary.grainMilliPerCommittedHour(), salary.silverMilliPerCommittedHour()),
        policy.remittancePerMilleToSuperior());
  }

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障）"));
    if (snapshot instanceof EconomySnapshot economySnapshot) {
      return economySnapshot.data();
    }
    throw new IllegalStateException(
        "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
  }
}
