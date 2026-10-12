package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovPortPolicy;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★★ <b>{@code economy.SetGovernmentPolicy}（追加裁定 12，2026-10-12 §21.9 D-1~D-4）：GOV 侧三类政策（口岸 / 预算 /
 * 编制）的<b>唯一通用写口</b></b>。
 *
 * <pre>{@code
 * { "governmentId": "gov-unit-gov-central",
 *   "policies": {
 *     "port":           { commodityRules?, currencyRules?, marketControl? },
 *     "budget":         { mode?, orderedCategories?, officialSalaryRule?, remittancePerMilleToSuperior? },
 *     "administration": { securityPlannedLaborMilli?, …, postTiers?, supernumerarySqrtCoefficient? } } }
 * }</pre>
 *
 * <p>★★ <b>它为什么存在（用户 2026-10-12 原话）</b>：「口岸本身肯定是交给经济模块负责维护的，包括规则之类的，但是写入规则需要交给 GOV，也就是 GOV
 * 只调用相关修改，不负责管理机制」＋「B，GOV 不应该负责存」＋「gov 模块要改，那先让 eco 搞个通用 handler， 声明从哪来干啥就能改，然后让 gov 越过 unit
 * 单独调用不就得了，这样权限系统最干净」。⇒
 *
 * <ul>
 *   <li><b>机制 + 规则 + 存储 = economy</b>：三类政策挂在 {@code EconomyData.governments} 的 {@link Government}
 *       记录上（<b>不新增独立组件</b> ⇒ 组件数不变）；算式与缺省语义都在 economy（{@code economy.govpolicy} 包 + {@code
 *       PortRegimeAggregation}/{@code PortThrottle}/税/预算执行/编制效率）；
 *   <li><b>决定权 = GOV</b>：设什么值由本条命令决定 —— GOV <b>调用修改</b>，不实现机制、不负责存；
 *   <li><b>来源 = {@code GovernmentId}</b>（用户选 D-2）：economy <b>完全不碰 {@code unit} 命名空间</b>（零 {@code
 *       simos-unit} 依赖）。"这个 id 存不存在"的判据就是"它在不在 {@code governments} 里"——不是 GOV 单位的 id 或未登记的单位
 *       派生不出这条记录，于是<b>天然具名拒</b>；
 *   <li>★★ <b>结构红利</b>：政府记录没了 ⇒ 它的政策跟着没了 ⇒ "GOV 删除后政策悬空"在结构上不可能出现。
 * </ul>
 *
 * <p>★★ <b>一条命令改任意子集</b>（D-1 冻结契约）：{@code policies.port} / {@code .budget} / {@code
 * .administration} <b>各自可缺席</b>（缺席 = 该类一个数都不动）；{@code policies} 缺失/非对象/空对象 ⇒ <b>具名拒</b>（不做空写）。
 * 三类同批写入 ⇒ <b>恰一条 revision</b>（{@link EconomyChangeSet#between} 逐值比较，只有一个 {@code governments}
 * 组件会变）。
 *
 * <p>★★ <b>fail-closed（判据 3）</b>：
 *
 * <ul>
 *   <li>{@code governmentId} 缺失/词法非法 ⇒ 具名拒；
 *   <li><b>{@code governmentId} 不存在</b>（{@code governments} 里没有这把键）⇒ 具名拒 {@code government-missing}
 *       + <b>零 revision</b>（本命令<b>不</b>凭空建政府：建政府要同时给国库家户/单位/发钞权，那是 {@code
 *       economy.RegisterGovernment} 的判据）；
 *   <li>非法值（负限制 / 负税 / 未知商品或币种 / 坏引用 / 未知预算类别 / 非 3 档目录 / 未知挂单类型 / 未知字段名 …）⇒ 全部由 {@link
 *       EconomyGovPolicyPayloads} 与三个领域 record 的构造期给出<b>具名拒</b>（不静默忽略、不静默取缺省）；
 *   <li>★ <b>未知商品/未知币种在写口判</b>（2026-10-12 起）：本 handler 住 economy ⇒ 编译期看得见经济词表 （{@link
 *       io.mosire.simos.util.economy.EconomyVocabulary#allCommodityIds()} + {@code
 *       EconomyData.currencies()}）。旧形状里这条判据只能由 app 的写前守卫 {@code GovPortPolicyGuard} 承担 （gov
 *       看不见词表），政策搬进 economy 后那道守卫<b>整个消失</b>——判据没丢，只是回到了能看见词表的那一层。
 * </ul>
 *
 * <p>★★ <b>权限单调性（I-B4 / D-4）</b>：本命令不打 {@code DmUsableCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} /
 * 决策人目录（旧三条 {@code gov.Set*} 命令也是 GM-only，权限面<b>不放大也不缩小</b>）；GM 的 {@code simos.command.submit}
 * 与窄工具照常。 ★ <b>不造第二套声明机制</b>（D-4）：复用既有 {@link CommandTargets}（目标 = 本命令声明的政府身份）与工具面的 {@code
 * ResourceManifest}。
 *
 * <p>★ <b>幂等</b>：与现值逐值相同 ⇒ 具名拒 {@code policies-unchanged}（照 {@code economy.SetGovernmentMintScale}
 * / {@code economy.SetMarketZonePriceAlpha} 的既有口径：没变化就不落 revision）。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code GOVERNMENT_POLICY_SET}，带"改了哪几类"与逐类具名计数）、业务/载荷拒绝
 * INFO（{@code GOVERNMENT_POLICY_REJECTED}，经 {@code EconomyCommandPayloads.logReason} 脱敏）、DEBUG
 * 一条"为什么"。
 */
public final class EconomySetGovernmentPolicyHandler
    implements CommandHandler, CommandTargets {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册、catalog 提示与窄工具都取它）。 */
  public static final String TYPE = "economy.SetGovernmentPolicy";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * ★★ <b>目标声明（D-4：复用既有声明式机制）</b>：本命令改的是<b>一个政府</b> —— 载荷用 {@code governmentId} 精确点名时， 目标路径就是那个政府
   * id（{@code economy} 命名空间内）。
   *
   * <p>★ <b>只用 {@code govUnitId} 时返回空表</b>：单位 id 属于 {@code unit} 命名空间，economy <b>不碰它</b>（D-2）； 按
   * {@link CommandTargets} 的契约，"无可寻址目标"与"未实现"走同一条 fail-closed 路径。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    if (payload.hasNonNull("governmentId")) {
      return List.of(
          GovernmentId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "governmentId"))
              .value());
    }
    // ★ 走到这里若两个地址都没有/形状坏，requireGovernmentAddress 会给具名拒（判不出目标就不得放行）。
    EconomyCommandPayloads.requireGovernmentAddress(TYPE, payload);
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    String governmentText = "-";
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      GovernmentId governmentId = EconomyCommandPayloads.requireGovernmentAddress(TYPE, payload);
      governmentText = governmentId.value();
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return reject(
            governmentText,
            "-",
            "government-missing",
            "该政府未登记（本命令只改已登记的政府；要建政府请走 economy.RegisterGovernment）: " + governmentId.value());
      }
      EconomyGovPolicyPayloads.GovPolicyCurrent current =
          new EconomyGovPolicyPayloads.GovPolicyCurrent(
              government.portPolicy(), government.budgetPolicy(), government.administrationPlan());
      EconomyGovPolicyPayloads.Parsed parsed = EconomyGovPolicyPayloads.parse(payload, current);
      if (parsed.portSet()) {
        // ★ N1 负向判据：非法政策不许先落进状态（写口这一层就能看见词表，故在这里拒）。
        EconomyGovPolicyPayloads.requireKnownPortClasses(parsed.port(), base);
      }
      Government projected = government;
      if (parsed.portSet()) {
        projected = projected.withPortPolicy(parsed.port());
      }
      if (parsed.budgetSet()) {
        projected = projected.withBudgetPolicy(parsed.budget());
      }
      if (parsed.administrationSet()) {
        projected = projected.withAdministrationPlan(parsed.administration());
      }
      if (projected.equals(government)) {
        return reject(
            governmentText,
            changedKinds(parsed),
            "policies-unchanged",
            "载荷给的三类政策与该政府现值逐值相同，没变化（不落空 revision）");
      }
      Map<GovernmentId, Government> governments = new LinkedHashMap<>(base.governments());
      governments.put(governmentId, projected);
      // ★ 只碰政府表这一个组件：账户/债务/发行审计/产业/市场表一个数都不动（canonical 构造器与全部守卫照跑）。
      EconomyData next = base.withGovernments(governments);
      logApplied(governmentId, parsed, government, projected);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return reject(
          governmentText, "-", "bad-payload", EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /** 载荷里"给了哪几类"的具名文本（只进日志/拒绝理由；唯一的拼接点）。 */
  private static String changedKinds(EconomyGovPolicyPayloads.Parsed parsed) {
    StringBuilder kinds = new StringBuilder();
    if (parsed.portSet()) {
      kinds.append(kinds.isEmpty() ? "" : "+").append(EconomyGovPolicyPayloads.PORT);
    }
    if (parsed.budgetSet()) {
      kinds.append(kinds.isEmpty() ? "" : "+").append(EconomyGovPolicyPayloads.BUDGET);
    }
    if (parsed.administrationSet()) {
      kinds.append(kinds.isEmpty() ? "" : "+").append(EconomyGovPolicyPayloads.ADMINISTRATION);
    }
    return kinds.isEmpty() ? "-" : kinds.toString();
  }

  /** 成功 INFO（改了哪几类 + 逐类具名计数；缺省中性由"没给这一类"表达，不写"看起来设了 0"）。 */
  private static void logApplied(
      GovernmentId governmentId,
      EconomyGovPolicyPayloads.Parsed parsed,
      Government previous,
      Government next) {
    GovPortPolicy port = next.portPolicy();
    GovBudgetPolicy budget = next.budgetPolicy();
    GovAdministrationPlan plan = next.administrationPlan();
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOVERNMENT_POLICY_SET",
                EconomyLogSource.ECONOMY_GOV_POLICY,
                "government",
                governmentId.value(),
                "kinds",
                changedKinds(parsed),
                // ★ 口岸：条数分开报（设过几条 / 真会拦几条 / 真会收几条 / 管控开关）。
                "portCommodityRules",
                port.commodityRules().size(),
                "portCurrencyKindRules",
                port.currencyKindCount(),
                "portRestrictedClasses",
                port.restrictedClassCount(),
                "portTaxedClasses",
                port.taxedClassCount(),
                "portMarketControl",
                port.controlsMarket(),
                // ★ 预算：类别条数 + 上缴比例 + 是否发薪（0/0 = 明确不发薪）。
                "budgetCategories",
                budget.orderedCategories().size(),
                "budgetRemittancePerMille",
                budget.remittancePerMilleToSuperior(),
                "budgetSalaryGrainMilliPerHour",
                budget.officialSalaryRule().grainMilliPerCommittedHour(),
                "budgetSalarySilverMilliPerHour",
                budget.officialSalaryRule().silverMilliPerCommittedHour(),
                // ★ 编制：三维计划量 + 档数 + k。
                "planSecurityLaborMilli",
                plan.securityPlannedLaborMilli(),
                "planPaperworkLaborMilli",
                plan.paperworkPlannedLaborMilli(),
                "planPortLaborMilli",
                plan.portPlannedLaborMilli(),
                "planPostTiers",
                plan.postTiers().size(),
                "planSupernumerarySqrtCoefficient",
                plan.supernumerarySqrtCoefficient(),
                "note",
                "三类政策住在 Government 记录上（EconomyData.governments）；GOV 只调写口、不负责存"));
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GOVERNMENT_POLICY_CRITERIA",
                  EconomyLogSource.ECONOMY_GOV_POLICY,
                  "government",
                  governmentId.value(),
                  "kinds",
                  changedKinds(parsed),
                  "beforePortRules",
                  previous.portPolicy().definedClassCount(),
                  "afterPortRules",
                  port.definedClassCount(),
                  "beforeBudgetCategories",
                  previous.budgetPolicy().orderedCategories().size(),
                  "afterBudgetCategories",
                  budget.orderedCategories().size(),
                  "beforePlanSecurityLaborMilli",
                  previous.administrationPlan().securityPlannedLaborMilli(),
                  "afterPlanSecurityLaborMilli",
                  plan.securityPlannedLaborMilli(),
                  "why",
                  "subset-absent-means-untouched-defaults-are-neutral-i-b1"));
    }
  }

  /** 具名拒（业务拒绝 = INFO；字段级细节 = DEBUG）。 */
  private static HandlerOutcome reject(
      String government, String kinds, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOVERNMENT_POLICY_REJECTED",
                EconomyLogSource.ECONOMY_GOV_POLICY,
                "government",
                government,
                "kinds",
                kinds,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
