package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.household.GovernmentHouseholdWiring;
import io.mosire.simos.app.household.GovernmentPostTierConsistency;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.household.GovernmentServiceUnitConsistency;
import io.mosire.simos.app.household.HouseholdEconomyProjection;
import io.mosire.simos.app.household.HouseholdPositionResolver;
import io.mosire.simos.app.household.HouseholdSatietyBridge;
import io.mosire.simos.app.household.HouseholdUnitConsistency;
import io.mosire.simos.app.household.MigrationSocialBridge;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomyParallelism;
import io.mosire.simos.economy.time.EconomyPopulationTransfer;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionLedger.ActorEntry;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovEfficiencyModifier;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovServiceFlow;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.household.VitalSettlementResult;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ **人口—经济协调器**（R4）：**唯一同时看得见 {@code social} 与 {@code economy} 的推进参与者** —— 于是"人"第一次真的随时间变： **日初每
 * tick 生死结算（Social 唯一权威）+ 经济结算一天 + 逐户净人口变化同步到经济行**。
 *
 * <p>★★ **为什么必须有它**（不是"图省事"，是结构上只能如此）：
 *
 * <ol>
 *   <li>**出生/死亡只算在 Social 那一侧**（年龄/性别/率表都是 {@code Household} + {@code PopulationGroup} 的属性，见 {@code
 *       HouseholdBook.settleOneTick}）；**而"经济行人口"只住在 economy 那一侧** ⇒ 两边必须在一个参与者里按 "先 Social
 *       结算、再刷新经济投影、最后把 delta 加到经济行"的次序接线；
 *   <li>**§十一 等价性**（一次推 N 天 == N 次单日）要求"逐日"这条语义落在**同一个参与者内部** —— 若让两个参与者各自读对方的**基态**，一次推 365
 *       天时社会侧只能看到第 0 天的经济状态， 而 365 次单日推进每天都能看到前一天的 ⇒ **两条路径必然不等价**；
 *   <li>跨切片写要求"同一模块只能有一个写者"（{@code TimeProposalResolver} 的写-写检查）⇒ 同时写这两片的参与者**只能有一份**。
 * </ol>
 *
 * <p>★ 它住在 {@code simos-app}：设计稿 §8.2 的原文——"跨 {@code social}+{@code economy} 的协调器**必须住 {@code
 * simos-app}** （唯一认识所有模块的地方）"。★ 它**内联调用** {@link EconomySettlement} 的包内可见日结算入口， 从而与 {@code
 * EconomyTimeParticipant} 共用同一个结算实现（**只有一条真相**，不是两份公式）。
 *
 * <p>★★ **一天的次序**（2026-10-09 每 tick 生死 Batch B 起；缺一不可）：
 *
 * <pre>
 * ① 日初：Social 每 tick 生死结算（HouseholdBook.settleOneTick）
 *     → 用**新** Social 刷新经济侧 composition / laborBudgets / naturalNeeds
 *     → 把逐户净人口变化（出生 − 死亡）加到经济行 population
 * ② 经济结算一天（消费/借粮/进度/劳动/周期末收获/迁移执行）——
 *     行人口、劳动预算与当日需求都已是**新 Social** 的投影；迁移只推进投影账并落 outbox
 * ③ ★★ P0：经济腿迁移 outbox → Social 工单落人 → 经济行人口按净 delta 回写 → 再刷新
 *     composition / laborBudgets / naturalNeeds（与②同属本次 advance 的同一条 revision）
 * </pre>
 *
 * <p>★ 旧口径的"日末生理压力 + 每 30 天月度出生/死亡"已在 Batch B 整体删除；出生/死亡不再进 {@code FlowRow.births/deaths}
 * 的逐户流水（本批只同步行人口，见报告"未完成/风险"）。
 *
 * <p>★★ <b>H4：两份副本（商品 + 货币）按同一顺序收尾</b>：<b>载入</b>（{@link OwnershipBooks#loadHouseholdGoods} / {@link
 * OwnershipBooks#loadHouseholdMoney}）→ step（两者都由 {@code EconomyDayStepper} 就地更新）→ 条目落账 （{@link
 * OwnershipBooks#apply}）→ **两份副本按绝对值落回**（先商品、后货币；顺序不能反，因为它们写的是同一本 {@code HouseholdInventory}
 * 的两个余额表）。
 *
 * <p>★★ **它是"人口守恒"的落点**：出生与死亡在 Social 侧算出（唯一权威），本参与者把逐户净变化同步到经济行 ⇒ {@code Σ经济行新人口 == Σ经济行旧人口 + 出生 −
 * 死亡} 逐值可核。
 *
 * <p>★ **未激活/无上界**：经济未激活（{@code meta} 空）⇒ **两侧都交不变变更集**（没有生活资料信号 ⇒ 人口不动， 这正是"世界还没播种"该有的样子）；{@code
 * range.to} 缺省 ⇒ 同样交不变变更集、不抛（该推进随后必被 Core 拒）。
 */
public final class PopulationEconomyTimeParticipant implements TimeParticipant {

  /** 日循环事件的发射通道（分类 = {@link AppLog#time()}；来源按调用点取 DAILY_LOOP / HOUSEHOLD_SYNC）。 */
  private static final LogChannel TIME = EventLog.channel(AppLog.time());

  /** 参与者身份（**不是模块名**：它同时写 {@code social} 与 {@code economy} 两个模块）。 */
  public static final String NAMESPACE = "population";

  private static final String ECONOMY = "economy";
  private static final String SOCIAL = "social";

  /** ★★ P2-D：gov 切片（GOV 单位 → 每 tick 行政读数）。 */
  private static final String GOV = "gov";

  /** map 切片（税/行政需求要读 Region 拓扑；只在 gov 激活时强制在场）。 */
  private static final String MAP = "map";

  /** ★ T5 的第三片（产权落账）：actor 切片必须在场（缺席 ⇒ 抛 —— 产出没有地方落）。 */
  private static final String ACTOR = "actor";

  private final String mapId;

  /**
   * ★★ <b>经济日结算的 worker 数</b>（R2）：由组合根从 {@code ShellConfig.economyWorkerCount()} 传入；缺省 1 = 单线程退化路径。
   *
   * <p>★ 本参与者在每次 {@code simulateWorld} 里构造一次 {@link EconomyParallelism#of(int)}（自建池），并由 {@link
   * EconomyDayStepper#finish()}/{@link EconomyDayStepper#close()} 关池；异常路径由 {@code try/finally} 收口
   * ——推进失败不留下活着的结算线程池。
   */
  private final int economyWorkerCount;

  /**
   * ★★ <b>Z3b：本 tick 的行政效率动态修正注入集</b>（瞬态；键 = GOV unit id，保序 = 注入列表序）。
   *
   * <p>与经济的 {@code ProductionEfficiencyModifier} 同形：{@code updateGovEfficiencyModifiers}
   * <b>替换</b>本集合；日循环 消费一次后清空——机制要连续影响就必须逐 tick 再次注入。它不进 {@code GovState}/变更集/Codec（GM 不可达；无命令、无工具、
   * 无审批链）。★ 单写者假设：与 {@code EconomySession} 同制，调用与推进由同一线程串行发生。
   */
  private final LinkedHashMap<UnitId, GovEfficiencyModifier> govEfficiencyModifiers =
      new LinkedHashMap<>();

  /**
   * ★ 最近一次推进看到的 GOV 集合（{@code govState.offices()} 的键；只读快照）——注入时用它判"未知 GOV"；参与者从未推进过 GOV
   * 世界时为空集（此时注入只做形状/重复校验，GOV 存在性由消费时按当 tick 的 office 集合再判一次，见 {@code
   * reportUnknownGovEfficiencyModifiers}）。
   */
  private Set<UnitId> knownGovUnits = Collections.emptySet();

  /**
   * ★★ <b>Z7d-2：本 tick 产生的 unit 侧逃亡摘除请求</b>（瞬态；由 app 组合根的新组合参与者 {@code
   * PopulationUnitTimeParticipant} 在同一 revision 的 unit 变更集里消费）。
   *
   * <p>与 {@code govEfficiencyModifiers} 同制：不进状态/变更集/Codec；只在"population 参与者先跑、unit 参与者后跑" 的既有
   * namespace 字典序（population &lt; unit）下被消费一次。
   */
  private final List<GovernmentServiceDesertionBridge.Eviction> pendingFlightEvictions =
      new ArrayList<>();

  /** 旧调用点（测试/夹具）兼容：并行度取缺省 {@link ShellConfig#DEFAULT_ECONOMY_WORKER_COUNT}（单线程退化路径）。 */
  public PopulationEconomyTimeParticipant(String mapId) {
    this(mapId, 1);
  }

  /** ★ R2 组合根入口：{@code economyWorkerCount} 必须 ≥ 1（1 = 单线程退化路径，≥ 2 才真的并行）。 */
  public PopulationEconomyTimeParticipant(String mapId, int economyWorkerCount) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    if (economyWorkerCount < 1) {
      throw new IllegalArgumentException(
          "economyWorkerCount 必须 ≥ 1（1 = 单线程退化路径，≥ 2 才真的并行）: " + economyWorkerCount);
    }
    this.economyWorkerCount = economyWorkerCount;
  }

  /** ★ Z3b：把一份 gov 状态的 office 集合记为"已知 GOV"（排序只为本字段稳定，不影响调用次序）。 */
  private void rememberKnownGovUnits(GovState govState) {
    List<UnitId> ordered = new ArrayList<>(govState.offices().keySet());
    ordered.sort(Comparator.comparing(UnitId::value));
    this.knownGovUnits =
        Collections.unmodifiableSet(new LinkedHashSet<>(ordered)); // ★ 冻在赋值处（保序不可变）
  }

  /** ★ R2：本参与者配置的经济结算 worker 数（只读；服务装配日志/诊断）。 */
  public int economyWorkerCount() {
    return economyWorkerCount;
  }

  /**
   * ★★ <b>Z3b：替换当日的行政效率动态修正注入集</b>（GM 不可达；无命令/无工具/审批链）。
   *
   * <p>具名拒绝（INFO + {@link IllegalArgumentException}，照经济 {@code updateProductionModifiers} 同形）：
   *
   * <ul>
   *   <li>{@code modifiers == null} / 含 null 项 ⇒ 拒；
   *   <li>同一 GOV 重复出现 ⇒ 拒 {@code duplicate-gov}；
   *   <li>未知 GOV（上次推进的 office 集合不含它）⇒ 拒 {@code unknown-gov}；★ 参与者尚未推进过 GOV 世界（已知集合为空）时，
   *       本方法只做形状/重复校验，GOV 存在性推迟到当日消费时按当 tick 的 office 集合判（同样 INFO 具名拒绝、不静默采用）。
   * </ul>
   *
   * <p>四个 ‰ 值只判 {@code ≥ 0}（不封顶）由 {@link GovEfficiencyModifier} 构造期守卫；未注入的 GOV = 四项 1000‰ 中性。
   * 消费（读走当 tick 用）后集合清空。
   */
  public void updateGovEfficiencyModifiers(List<GovEfficiencyModifier> modifiers) {
    if (modifiers == null) {
      logGovEfficiencyModifierInjectionRejected("null-modifier-list", null);
      throw new IllegalArgumentException(
          "updateGovEfficiencyModifiers 的 modifiers 不得为 null（没有注入用空列表）");
    }
    LinkedHashMap<UnitId, GovEfficiencyModifier> replacements = new LinkedHashMap<>();
    for (GovEfficiencyModifier modifier : modifiers) {
      if (modifier == null) {
        logGovEfficiencyModifierInjectionRejected("null-modifier", null);
        throw new IllegalArgumentException("updateGovEfficiencyModifiers 不得含 null 修正项");
      }
      UnitId gov = modifier.gov();
      if (!knownGovUnits.isEmpty() && !knownGovUnits.contains(gov)) {
        logGovEfficiencyModifierInjectionRejected("unknown-gov", gov);
        throw new IllegalArgumentException(
            "updateGovEfficiencyModifiers 指向未知 GOV（上次推进的 office 集合不含）: " + gov.value());
      }
      if (replacements.putIfAbsent(gov, modifier) != null) {
        logGovEfficiencyModifierInjectionRejected("duplicate-gov", gov);
        throw new IllegalArgumentException(
            "updateGovEfficiencyModifiers 同一 GOV 重复注入: " + gov.value());
      }
    }
    govEfficiencyModifiers.clear();
    govEfficiencyModifiers.putAll(replacements);
    if (TIME.isDebugEnabled()) {
      TIME.debug(
          LogEvent.of(
              "GOV_EFFICIENCY_MODIFIERS_REPLACED",
              AppLogSource.GOV_EFFICIENCY_INJECT,
              "count",
              replacements.size()));
    }
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    // ★ Z7d-2：新一次提案从零开始记逃亡摘除（旧提案被拒/重放都不会把请求带进下一次）。
    pendingFlightEvictions.clear();
    EconomyData economyBase = economyOf(state);
    SocialData socialBase = socialOf(state);
    // ★★ S1 阶段 4+5 Task 5：**第三片 actor** —— 产权落账只可能发生在同时看得见 economy 与 actor 的地方。
    ActorData actor = actorOf(state);
    // ★★ S3b（2026-10-09）：Unit/Gov 的 households ↔ Social 家户位置一致性 + HouseholdEconomy 人口向 Social 家户投影。
    //   两件事都必须在日循环之前做：它们改的是本轮推进的**基态**，终端变更集相对 economyBase/socialBase 取差分。
    UnitState units = unitStateOf(state);
    SocialData social = socialBase;
    if (units != null && !social.households().isEmpty()) {
      HouseholdUnitConsistency.Reconciliation reconciliation =
          HouseholdUnitConsistency.reconcileSocialToUnits(social, units);
      social = reconciliation.data();
      // ★ 2026-10-23：自动同步逐条 INFO 从 HouseholdUnitConsistency 迁到这里（原 helper 无 day）。
      for (String repaired : reconciliation.repaired()) {
        TIME.info(
            LogEvent.of(
                "HOUSEHOLD_LOCATION_AUTOSYNC",
                AppLogSource.HOUSEHOLD_SYNC,
                "day",
                range.from().tick(),
                "entry",
                repaired));
      }
      if (!reconciliation.unresolved().isEmpty()) {
        throw new IllegalStateException(
            "Unit.households 与 Social 家户位置存在单侧修不了的不一致（S3b）："
                + reconciliation.unresolved().size()
                + " 处，首条："
                + reconciliation.unresolved().get(0));
      }
      HouseholdUnitConsistency.requireConsistent(social, units);
      Map<String, Long> staffProjection =
          HouseholdUnitConsistency.staffHouseholdProjection(
              economyBase, social, units, range.from().tick());
      if (!staffProjection.isEmpty()) {
        TIME.info(
            LogEvent.of(
                "GOV_STAFF_HOUSEHOLD_PROJECTION",
                AppLogSource.DAILY_LOOP,
                "day",
                range.from().tick(),
                "mapId",
                mapId,
                "entries",
                staffProjection.size(),
                "projection",
                staffProjection));
      }
      // ★★ Z4/C4：posts 非空的 GOV，stored staff 是 legacy 缓存；与岗位家户投影不一致是**预期过渡态**
      //   （承诺权威/供给桥属 Z3），不是告警。按 AGENTS §一.9「既有 WARN 不降级、新增日志不滥发 WARN」：
      //   这里每日聚合一条 INFO（保留事件名与 count/first），不用 WARN/DEBUG 淹没日志。
      List<String> staffMismatches =
          HouseholdUnitConsistency.staffProjectionMismatches(
              economyBase, social, units, range.from().tick());
      if (!staffMismatches.isEmpty()) {
        TIME.info(
            LogEvent.of(
                "GOV_STAFF_PROJECTION_MISMATCH",
                AppLogSource.DAILY_LOOP,
                "day",
                range.from().tick(),
                "mapId",
                mapId,
                "count",
                staffMismatches.size(),
                "first",
                staffMismatches.get(0)));
      }
      // ★★ Z4/C1 旧档只读识别：官吏仍住 hh-gov 财政户的世界记录下来（不做破坏性迁移；迁移策略见 Z4 台账）。
      Map<String, Long> legacyTreasury =
          HouseholdUnitConsistency.legacyTreasuryHouseholdPopulation(social, units);
      if (!legacyTreasury.isEmpty()) {
        TIME.info(
            LogEvent.of(
                "GOV_LEGACY_OFFICIALS_IN_TREASURY_HOUSEHOLD",
                AppLogSource.DAILY_LOOP,
                "day",
                range.from().tick(),
                "mapId",
                mapId,
                "count",
                legacyTreasury.size(),
                "entries",
                legacyTreasury));
      }
    }
    EconomyData economyAligned = economyBase;
    if (units != null && !social.households().isEmpty()) {
      // ★★ 2026-10-09：UNIT 家户（政府/军队小家户）的 economy 行视图对齐到 unit 当刻 effectivePosition 的 hex——
      //   市场参与 / 生产组织 / 贷款等 economy 内部一律读 HouseholdEconomy.view().hex()，本对齐让它们无需 new dependency
      //   就跟随 unit.PlaceAt / 行军 / 迁都。HEX 家户原样不动。
      HouseholdPositionResolver.Alignment alignment =
          HouseholdPositionResolver.alignHouseholdEconomyViews(
              economyBase, social, units, range.from());
      economyAligned = alignment.data();
      if (alignment.moved() > 0) {
        TIME.info(
            LogEvent.of(
                "UNIT_HOUSEHOLD_ECONOMY_VIEW_ALIGNED",
                AppLogSource.DAILY_LOOP,
                "day",
                range.from().tick(),
                "mapId",
                mapId,
                "moved",
                alignment.moved()));
      }
    }
    HouseholdEconomyProjection.Result householdEconomyProjection =
        HouseholdEconomyProjection.project(economyAligned, social);
    if (!householdEconomyProjection.unresolved().isEmpty()) {
      // ★ 保留 WARN（用户 2026-10-23：不接受降级；原 helper 的 SKIPPED 副本已删）。
      TIME.warn(
          LogEvent.of(
              "CLASSROW_POPULATION_PROJECTION_UNRESOLVED",
              AppLogSource.HOUSEHOLD_SYNC,
              "day",
              range.from().tick(),
              "mapId",
              mapId,
              "count",
              householdEconomyProjection.unresolved().size(),
              "first",
              householdEconomyProjection.unresolved().get(0)));
    } else if (householdEconomyProjection.projected()) {
      TIME.info(
          LogEvent.of(
              "CLASSROW_POPULATION_PROJECTED",
              AppLogSource.HOUSEHOLD_SYNC,
              "day",
              range.from().tick(),
              "mapId",
              mapId,
              "households",
              social.households().size(),
              "changedRows",
              householdEconomyProjection.changedRows(),
              "absPopulationDelta",
              householdEconomyProjection.populationDelta()));
    }
    EconomyData economy = householdEconomyProjection.data();
    // ★★ Z4/spec §18.3：gov service unit（operator = HOUSEHOLD:hh-gov-<govUnitId>）的 operator GOV 必须在
    // unit
    //   切片存在且带 GovernmentFormation——Z1c handler 编译期看不见 unit，app GM 窄工具做 preview/apply 预检，
    //   但 GM 裸 simos.command.submit 可绕过；本组合根检查是最终具名拒（不静默继续）。unit 切片缺席 + 存在 service
    //   unit 也是不一致。
    GovernmentServiceUnitConsistency.requireConsistent(economy, units);
    // ★★ P2-C §13.7：经济已激活 + 存在 GOV 单位时，推进入口把"GovernmentFormation 政府家户 ↔ HouseholdEconomy ↔ 政府记录 ↔
    // 国库账户"
    //   这条闭环判死 —— 缺任何一边都具名失败，不把"没有政府记录"读成"没有政府"。
    if (economy.meta().isPresent() && units != null) {
      GovernmentHouseholdWiring.requireConsistent(economy, social, units);
    }

    // ★★ P2-D：gov 切片（GOV 单位 → 每 tick 行政读数）与地图（Region 拓扑）在日循环之前读入。
    //   引导（沿用阶段 11b 的口径）：只要单位带 GovernmentFormation 而 gov 片还没有它的读数，就为本推进补一条
    //   GovOfficeState.empty(...) 作为基线 —— 否则"建了 GOV 编制"永远不会激活行政结算（没有人负责首建读数）。
    GovState govState = govOf(state);
    GameMap map = mapOfOrNull(state);
    GovState bootstrappedGov =
        units == null ? govState : withBootstrapOffices(govState, units, range.from().tick());
    // ★★ Z4/C4：岗位 tierId 必须指向该 GOV 的 GovAdministrationPlan.postTiers 目录（unit 看不见 gov 计划，
    //   只有 app 同时看得见；裸命令提交的悬空 tierId 在这里具名 fail-closed，不静默带进日结算）。
    if (units != null) {
      GovernmentPostTierConsistency.requireConsistent(bootstrappedGov, units);
    }
    boolean govActive = !bootstrappedGov.offices().isEmpty();
    // ★ Z3b：把本轮基态的 office 集合记为"已知 GOV"（注入接口用它判未知 GOV；推进结束后会再刷成本轮结果）。
    rememberKnownGovUnits(bootstrappedGov);
    if (govActive) {
      if (units == null) {
        throw new IllegalStateException("gov 片有行政读数但 state 里没有 unit 切片（装配故障：行政结算要求 GOV 编制在场）");
      }
      if (map == null) {
        throw new IllegalStateException("存在 GOV 编制但 state 里没有 map 切片（日税/行政需求要求 Region 拓扑在场；装配故障）");
      }
      if (state.module(GOV).isEmpty()) {
        throw new IllegalStateException("存在 GOV 编制但 state 里没有 gov 切片（本轮要写行政读数，装配故障；世界创世应补空 gov 片）");
      }
    }

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    for (IndustryId id : economy.industries().keySet()) {
      reads.add(economyAddress("industry", id.value()));
      writes.add(economyAddress("industry", id.value()));
    }
    // ★★ H0.2：class/flow 的地址局部名 = {@link CohortKey#toString()} 的**规范串**（{@code
    // 0_0|rural|poor_peasant}）。
    //   键里已经没有产业 ⇒ 旧版内联拼的 {@code <industryId>.<slotId>} 既拼不出来、也不该再拼（那是**第二处拼写点**）。
    //   ★ 与 {@code EconomyResolver} 的 class/flow 地址**必须逐字同串**：读写集的冲突检测全靠它。
    for (HouseholdId key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.toString()));
      writes.add(economyAddress("class", key.toString()));
    }
    for (HouseholdId key : economy.flows().keySet()) {
      writes.add(economyAddress("flow", key.toString()));
    }
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    reads.add(socialAddressRoot());
    writes.add(socialAddressRoot());
    for (PeopleLotId lot : social.groups().keySet()) {
      reads.add(socialAddress("group", lot.value()));
      writes.add(socialAddress("group", lot.value()));
    }
    // ★ T5：第三片 actor —— 读写集是 {@code actor:<mapId>:goods.<key>}（形制照 ActorResolver）。
    reads.add(actorAddressRoot());
    if (state.module("map").isPresent()) {
      // ★ M2.3：区域拓扑读地图（城市/地形）—— 只读声明，避免与地图写者同轮冲突时静默。
      reads.add(mapAddressRoot());
    }
    if (units != null) {
      // ★ S3b：本轮读 unit 切片做家户一致性校核/自动同步（只读，不写 unit——写侧仍归 UnitTimeParticipant）。
      reads.add(unitAddressRoot());
    }
    writes.add(actorAddressRoot());
    for (HouseholdAccountKey key : actor.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }

    Optional<io.mosire.simos.util.time.SimosTimestamp> to = range.to();
    if (to.isEmpty() || economy.meta().isEmpty()) {
      // 无上界推进 / 经济未激活 ⇒ 三片都不动（但**交的是不变变更集，不是空提案**：契约原文）。
      // ★ S3b：家户一致性同步 / HouseholdEconomy 投影即使在经济未激活时也照常提交（它们各自与 base 差分）。
      return new WorldTimeProposal(
          NAMESPACE,
          Map.of(
              ECONOMY, EconomyChangeSet.between(economyBase, economy),
              SOCIAL, SocialChangeSet.between(socialBase, social),
              ACTOR, ActorChangeSet.between(actor, actor)),
          reads,
          writes);
    }
    // ★★ P2-D：gov 片只在真的存在 GOV 读数时声明读写（无 GOV 世界零变化、零声明，沿用阶段 11b 口径）。
    if (govActive) {
      reads.add(govAddressRoot());
      writes.add(govAddressRoot());
    }
    TIME.info(
        LogEvent.of(
            "ECONOMY_ADVANCE_START",
            AppLogSource.DAILY_LOOP,
            "day",
            range.from().tick(),
            "mapId",
            mapId,
            "fromTick",
            range.from().tick(),
            "toTick",
            to.get().tick(),
            "days",
            to.get().tick() - range.from().tick(),
            "workerCount",
            economyWorkerCount,
            "households",
            economy.classes().size(),
            "markets",
            economy.markets().size(),
            "debtContracts",
            economy.debtContracts().size()));

    // ★★ S1：唯一账户会话 —— 家户（商品/货币/冻结）+ 经营者（商品/货币/冻结）一次装载；
    //   键 = (ActorRef, HexCoord)，家户 actor id 由 HouseholdId 唯一派生（不再从 CohortKey 拼）。
    //   ★ S1.5 旧档：先把旧三段 actor id 上的账搬到新身份键（移动，不是复制 —— 否则一笔粮变两本账）。
    ActorData migratedBooks = actor; // ★ P2-A：旧账户随旧世界报废，不再做 legacy actor 账户搬家
    // ★ S1.5：搬迁后的新账户键也要进读写集（否则第二次推进的冲突检测看不见它们）。
    for (HouseholdAccountKey key : migratedBooks.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }
    AccountSession session = OwnershipBooks.loadAccountSession(economy, migratedBooks);
    // ★★ S3 缺陷修复：会话登记过的账户当天终值由 landAccountSession 的**绝对值**落回覆盖；
    //   这些账户上的 ledger 条目不能再对 actor 基准叠一遍（否则跨区到货等"只写会话"的当天流入会让付方被误判透支）。
    //   非会话账户（承运人、未播种经营者等）仍按 ledger 条目逐笔折入 actor 账。
    // ★★ D3（2026-10-09）：这张"会话登记集"**必须当天现读**（日循环里现取
    //   {@code stepper.accounts().accounts().keySet()}），不能在 advance 起点拍快照 —— 迁移会在本次 advance 中途
    //   新建家户并 `AccountSession.registerHousehold`（{@code
    // ModeMigrationSettlement.createNewHousehold}），
    //   快照漏掉它 ⇒ 它当天的条目被折到 actor 基准上，而它的终值随后又由 landAccountSession 的绝对值覆盖 ⇒
    //   前缀校验拿一个"当天并非权威"的基准判负（三区世界 day=330 实测：会话里的迁移户
    //   `hh-mig-3_0-wage_farm-hh-3_0-urban-middle_peasant-0` 基准 7218 + 当日 -7952 = -734 ⇒ 整次 advance
    // 500）。
    // ★★ R2：并行度进构造器；workerCount == 1 时 EconomyParallelism.of 走单线程退化路径（不建池）。
    //   池的生命周期：finish()/close() 关闭；下面的 try/finally 保证日循环抛异常也不泄漏结算线程池。
    EconomyParallelism parallelism = EconomyParallelism.of(economyWorkerCount);
    try {
      // ★ M2.3：区域拓扑由组合根从地图/城市现算（Map + SocialCity/City）；不得让 economy 反查 social。
      //   ★ Z7d-2：同一份拓扑也喂逃亡去向排序（ExpectedProfitBook 的入参）。
      MarketTopology flightTopology = MarketTopologyBook.from(state);
      EconomyDayStepper stepper =
          new EconomyDayStepper(
              economy,
              session,
              flightTopology,
              EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
              EconomySettlement.FAMINE_MORTALITY_PER_MILLE,
              parallelism);
      try {
        // ★ 装配行可读：实际 workerCount 与"是否真的并行"都在这里（1 = 单线程退化路径）。
        TIME.info(
            LogEvent.of(
                "POPULATION_ECONOMY_PARALLEL_ENTRY",
                AppLogSource.DAILY_LOOP,
                "day",
                range.from().tick(),
                "mapId",
                mapId,
                "workerCount",
                economyWorkerCount,
                "parallelism",
                stepper.parallelism()));
        // ★★ P2-A A3：家户人口组成的唯一权威是 Social 的 {@code Household.members} —— 这里先把**基态**投影成
        //   「household → (lot → count)」只读表注入经济会话（组织/进入阶段挑批次用；不进 Economy 状态、
        //   不进变更集）。日循环里每 tick 在生死结算后再用新 Social 刷新一次。
        stepper.updateComposition(compositionOf(social));
        stepper.recomputeLaborBudgets(laborBudgetsOf(social, range.from().tick()));
        // ★★ Z7b：国库/单位户退出商品市场 —— economy 编译期看不见 unit 切片，组合根在这里把
        //   Unit.households() 的唯一投影注入（政府国库户由 economy 自己从 governments 并入；两来源在
        //   MarketRound 合成有效排除集）。单位列表在 revision 内不变，推进前注入一次即可。
        stepper.updateMarketExcludedHouseholds(unitHouseholdExclusions(units));
        ActorData currentBooks = migratedBooks;
        SocialData currentSocial = social;
        // ★★ Z7d-1：基态逐户粮缺口累计基线 —— {@link FlowRow#unmetNeed()} 是"本周期累计"，当日断顿要用
        //   与前一日（或 revision 基态）的差分；周期第一天清零 ⇒ 差分 < 0 时读作"当日值"。
        Map<HouseholdId, Long> previousCumulativeGrainUnmet =
            cumulativeGrainUnmetOf(stepper.flows());
        // ★★ P2-D：gov 状态（每 tick 行政读数）+ 跨日累计读数（只进日志，不进状态）。
        GovState currentGov = bootstrappedGov;
        AdminTotals adminTotals = new AdminTotals();
        Set<String> missingAdminRegions = new LinkedHashSet<>();
        for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
          // ★★ 2026-10-09 每 tick 生死 Batch B：**日初先算生死**（Social 唯一权威），再用新 Social 刷新经济侧
          //   composition / laborBudgets / naturalNeeds，最后把逐户净人口变化加到经济行人口上 —— 全部在
          //   step(day) 之前完成。经济结算当天读到的都是新 Social 的投影。
          VitalSettlementResult vital =
              HouseholdBook.settleOneTick(currentSocial, day, CalendarClock.julianDefault());
          currentSocial = vital.data();
          stepper.updateComposition(compositionOf(currentSocial));
          stepper.recomputeLaborBudgets(laborBudgetsOf(currentSocial, day));
          Map<HouseholdId, Map<CommodityId, Long>> dayNaturalNeeds =
              naturalNeedsOf(currentSocial, day);
          stepper.updateNaturalNeeds(dayNaturalNeeds);
          stepper.applyHouseholdPopulationDeltas(vital.populationDeltas());
          if (TIME.isDebugEnabled()) {
            long deltaNet = 0L;
            for (long delta : vital.populationDeltas().values()) {
              deltaNet = Math.addExact(deltaNet, delta);
            }
            TIME.debug(
                LogEvent.of(
                    "POPULATION_SETTLE_APP",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "births",
                    vital.births(),
                    "deaths",
                    vital.deaths(),
                    "events",
                    vital.events().size(),
                    "deltaHouseholds",
                    vital.populationDeltas().size(),
                    "deltaNet",
                    deltaNet));
          }
          // ★★ T5：日循环里同一处落账 —— step 交回**当天**的账，条目逐日落到 actor 账本上（不重不漏）。
          //   ★★ M2 守恒收口：**市场成交（MARKET_TRADE）不折**（理由见 {@link OwnershipBooks#REASONS_NOT_FOLDED}）——
          //   市场双方都必须是本轮参与者：落在账户上的那一份已由下面的会话副本绝对值落回覆盖，在途那一份由
          //   ShipmentBatch 承载；再折一遍会在异地键上造幽灵账。
          ProductionLedger ledger = stepper.step(day);
          // ★★ Z7d-1：经济→Social 断顿回写（同一 revision；当日结算后立刻生效于下一 tick 的劳动预算）。
          //   数据源 = 当日 FlowRow.unmetNeed[grain]（本周期累计，基线差分取当日量）与当日注入的粮自然需求；
          //   规则（用户冻结）：有粮 unmet ⇒ 快降 −150‰/日 × min(1000, unmet×1000/need)；无粮 unmet ⇒ 慢升 +20‰；
          //   布不足不降劳动。Social 不反向依赖 economy —— 本回写由 app 组合根编排、经 HouseholdSatietyBridge 纯函数。
          currentSocial =
              applyHouseholdSatietyWriteback(
                  currentSocial,
                  previousCumulativeGrainUnmet,
                  stepper.flows(),
                  dayNaturalNeeds,
                  day);
          // ★★ P0（2026-10-10）：经济腿迁移 outbox → Social 工单 → 经济行人口回写。次序（缺一不可）：
          //   ① drain：ModeMigrationSettlement 已在 step 内按投影账把资产/钱/债/组织/劳动配额落好，
          //      只把"搬了多少人、从谁到谁"留在瞬态 outbox；
          //   ② Social 工单落人（失败 ⇒ 整次 advance 具名拒、不落 revision，经济腿一并作废）；
          //   ③ 逐户净 delta 写回经济行 population（源 − / 目标 +；壳户行已在经济腿保留）；
          //   ④ 用**新** Social 重刷 composition / laborBudgets / naturalNeeds，供同日 gov 阶段与下一天使用。
          List<EconomyPopulationTransfer> populationTransfers =
              stepper.drainPendingPopulationTransfers();
          if (!populationTransfers.isEmpty()) {
            long socialPopulationBefore = totalSocialPopulation(currentSocial);
            long migratedPopulation = totalTransferredPopulation(populationTransfers);
            int createdTargets = 0;
            for (EconomyPopulationTransfer transfer : populationTransfers) {
              if (transfer.newTarget()) {
                createdTargets++;
              }
            }
            currentSocial = MigrationSocialBridge.apply(currentSocial, populationTransfers, day);
            stepper.applyHouseholdPopulationDeltas(netPopulationDeltas(populationTransfers));
            stepper.updateComposition(compositionOf(currentSocial));
            stepper.recomputeLaborBudgets(laborBudgetsOf(currentSocial, day));
            stepper.updateNaturalNeeds(naturalNeedsOf(currentSocial, day));
            TIME.info(
                LogEvent.of(
                    "MODE_MIGRATION_BRIDGED",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "mapId",
                    mapId,
                    "transfers",
                    populationTransfers.size(),
                    "migratedPopulation",
                    migratedPopulation,
                    "createdTargets",
                    createdTargets,
                    "socialPopulationBefore",
                    socialPopulationBefore,
                    "socialPopulationAfter",
                    totalSocialPopulation(currentSocial),
                    "economyPopulationAfter",
                    totalEconomyPopulation(stepper.householdEconomies())));
          }
          // ★★ Z7d-1：当日结算后的缺口累计基线（迁移可能新增目标行；基线随最新工作副本刷新，日序稳定）。
          previousCumulativeGrainUnmet = cumulativeGrainUnmetOf(stepper.flows());
          // ★★ P2-D/Z3c：日结算之后的税 / 预算 / 行政俸禄 / 军俸 / 工资 —— **同一账户会话、同一个日循环**
          //   （不另起 participant，避免 gov/actor 同名模块冲突）。顺序：先税（收入侧）→ Z3c 预算规划
          //   （GovBudgetPolicy 类别顺序/min/cap）→ GovDaily 行政俸禄（预算 oracle）→ 军俸/工资（预算裁剪后执行）
          //   → 持久周期规则；全部写账户会话，由本日末尾的 landAccountSession 绝对值一次落回 actor。
          //   ★ Z7c：remittance 插在"税之后、预算规划之前"（见下面 settle 调用）——于是上级国库的到账在同一 tick 的预算
          //     oracle 里立即可见；rate/缺口读数进 gov 源状态（GovRemittanceState），读口见 simos.gov.info。
          //   信号折进 economy.crisisSignals（同 (hex,kind) 覆盖）。
          if (govActive) {
            // ★★ Z3b 单次计算：本 tick 的注入集先取走并清空，随后算**唯一一份**效率/流量结果；税与 GovDaily 都消费它。
            Map<UnitId, GovEfficiencyModifier> dayModifiers = drainGovEfficiencyModifiers();
            GovEfficiencyDay computed =
                computeGovEfficiency(
                    currentGov,
                    units,
                    map,
                    currentSocial,
                    economy,
                    stepper.householdEconomies(),
                    dayModifiers,
                    day,
                    missingAdminRegions);
            reportUnknownGovEfficiencyModifiers(dayModifiers, computed.byUnit().keySet(), day);
            // ★★ R2（2026-10-09 口岸设计书 §4.2/§4.3/§4.4；G9"口岸/禁运 = 法律规定层"）：
            //   把"逐政府的口岸政策 s × 该政府的口岸效率 e（第三维）"按**暴露边**折算成市场区逐类的实际执行规律，
            //   再注入经济会话 —— 它被 CurrencyValuation 当作"家户对外币估值的减项"（用户 2026-10-08 原话
            //   「如果这个效率高，那么单个家户就更不倾向于用这种货币付款，因为如果付了要被抓」）。
            //   ★ 时序：本折算读的是**当日结算之后**算出的口岸效率，而市场轮在 step(day) 之内已经跑完 ⇒
            //     注入值作用于**下一次**市场轮（一 tick 滞后；与 GovEfficiencyModifier 的"逐 tick 注入、当场消费"同族）。
            //   ★ I-P8：一条限制都没有 ⇒ portRegime.active() == false ⇒ **不注入**（会话保持
            // PortEnforcementInput.none()
            //     ⇒ 家户估值不减项 ⇒ 旧世界逐值不变）。
            //   ★ N1：政策里的未知商品/未知币种 ⇒ 本调用内具名 ERROR + fail-closed 抛出（不静默忽略）。
            PortRegimeBridge.PortRegimeDay portRegime =
                PortRegimeBridge.compute(currentGov, units, map, economy, computed.byUnit(), day);
            if (portRegime.active()) {
              stepper.updatePortEnforcement(portRegime.input());
              TIME.info(
                  LogEvent.of(
                      "PORT_REGIME_INJECTED",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "contacts",
                      portRegime.contacts(),
                      "exposedEdges",
                      portRegime.exposedEdges(),
                      "restrictedClasses",
                      portRegime.restrictedClasses(),
                      "zones",
                      portRegime.input().zoneCount(),
                      "reason",
                      "port-policy-times-port-efficiency-folded-by-exposed-edges"));
            }
            // ★★ 服务流量：进程内投递（不落库、不进库存/市场/ledger）；读不到由读口具名 unavailable。
            GovServiceFlowFeed.publish(mapId, computed.flows(), day);
            JurisdictionDailyTax.Report tax =
                JurisdictionDailyTax.collect(
                    stepper.accounts(),
                    stepper.householdEconomies(),
                    units,
                    map,
                    day,
                    computed.efficiencyPerMilleByUnit());
            for (Map.Entry<HouseholdId, Long> entry : tax.grainByHousehold().entrySet()) {
              if (!stepper.recordTaxPaid(entry.getKey(), entry.getValue())) {
                // ★ 税流账行缺失是账户面异常（非业务拒绝）：保留 WARN 档，只换新形态与来源字段。
                TIME.warn(
                    LogEvent.of(
                        "TAX_FLOW_ROW_MISSING",
                        AppLogSource.DAILY_LOOP,
                        "day",
                        day,
                        "household",
                        entry.getKey().value(),
                        "grain",
                        entry.getValue()));
              }
            }
            adminTotals.recordTax(tax);
            // ★★ Z7c remittance：把本日逐 unit 实收加进周期累计；若本日有产业周期关账（同一事实见
            //   EconomyDayStepper.lastCycleClosed），在这一刻（税后、预算前、同一 AccountSession）执行上缴：
            //   due = 周期实收 × rate/1000，逐腿 min(due, 国库可用)，省→superiorGov 原子转移。
            //   不足只发 ADMIN_REMITTANCE_SHORTFALL 信号 + INFO（不自动注资/调率）；rate=0 不转移（抗税）。
            GovRemittanceBridge.Outcome remittance =
                GovRemittanceBridge.settle(
                    currentGov,
                    units,
                    tax.grainCollectedByUnit(),
                    tax.moneyCollectedByUnit(),
                    stepper.accounts(),
                    day,
                    stepper.lastCycleClosed());
            currentGov = remittance.nextGov();
            for (GovDaily.SignalDraft draft : remittance.alerts()) {
              stepper.putCrisisSignal(toCrisisSignal(draft, day));
            }
            // ★★ Z3c：税收入侧（税 + remittance）进来之后、任何支出之前，先按该 GOV 的 GovBudgetPolicy 做当日限额规划。
            //   行政俸禄（GovDaily）/军俸桥/官吏工资桥此后都只被授权到各自类别限额；本桥不注资、不改计划。
            long daysInYearAtSettlement = CalendarClock.julianDefault().daysInYearAtTick(day);
            GovBudgetExecutionBridge.DayBudget budget =
                GovBudgetExecutionBridge.plan(
                    currentGov,
                    units,
                    currentSocial,
                    economy,
                    stepper.accounts(),
                    computed.byUnit(),
                    computed.supplyByUnit(),
                    day,
                    daysInYearAtSettlement);
            MilitaryPayRuleBridge.Report militaryPayReport = budget.militaryReport();
            if (TIME.isDebugEnabled()) {
              TIME.debug(
                  LogEvent.of(
                      "MILITARY_PAY_BRIDGE",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "units",
                      militaryPayReport.units(),
                      "policies",
                      militaryPayReport.policies(),
                      "rules",
                      militaryPayReport.rules().size(),
                      "gaps",
                      militaryPayReport.gaps().size()));
            }
            // ★★ Z7b：俸禄（ADMIN_STIPEND）的粮/布腿由 oracle 转给官吏户（按 GOV_SERVICE 承诺份额分摊）；
            //   传入 economy 是为了读承诺份额的唯一权威（GovernmentServiceLaborBridge），不写任何状态。
            GovernmentUpkeepOracle oracle =
                new GovernmentUpkeepOracle(stepper.accounts(), units, economy);
            GovDaily.Outcome settled =
                GovDaily.settle(
                    currentGov,
                    units,
                    day,
                    daysInYearAtSettlement,
                    computed.byUnit(),
                    budget.upkeepOracle(oracle));
            currentGov = settled.next();
            adminTotals.recordGovDaily(settled);
            for (GovDaily.SignalDraft draft : settled.signals()) {
              stepper.putCrisisSignal(toCrisisSignal(draft, day));
            }
            for (GovDaily.SignalDraft draft : budget.alerts()) {
              stepper.putCrisisSignal(toCrisisSignal(draft, day));
            }
            adminTotals.recordExtraSignals(budget.alerts().size());
            // ★★ Z3c/Z7b：军俸/工资规则带着**原始请求 + 逐腿授权**整条交给执行器（applyBudgeted 不与持久规则合并），
            //   零授权腿不再被提前丢掉，由执行器记 PARTIAL/具名缺口；持久规则紧接着在下面单独执行，不能先抢走预算类别预留的国库。
            PeriodicHouseholdAdjustmentExecutor.Report budgetedReport =
                PeriodicHouseholdAdjustmentExecutor.applyBudgeted(
                    budget.budgetedLedgerRules(), stepper.accounts(), day);
            adminTotals.recordSalary(budget.logSalaryExecution(budgetedReport, day));
            List<GovDaily.SignalDraft> executionAlerts =
                budget.executionContractAlerts(budgetedReport, day);
            for (GovDaily.SignalDraft draft : executionAlerts) {
              stepper.putCrisisSignal(toCrisisSignal(draft, day));
            }
            adminTotals.recordExtraSignals(executionAlerts.size());
            // ★★ Z7d-2 逃亡：日结 + 预算执行之后，用**当日真实逐户缺口**（ADMIN_SALARY 逐户 requested−paid；
            //   ADMIN_STIPEND 粮/布腿逐 GOV shortfall）驱动 fleeRate，再按确定性去向执行成员转移。
            //   同一 revision：Social 成员/位置 + Economy 人口/劳动/承诺 + Unit 摘除请求（由组合参与者落 unit 变更集）。
            Map<HouseholdId, Long> salaryShortfallsByHousehold = new LinkedHashMap<>();
            for (Map.Entry<HouseholdId, GovBudgetExecutionBridge.ResourceVector> entry :
                budget.salaryShortfallByHousehold(budgetedReport).entrySet()) {
              long value = entry.getValue().valueInBookCurrency();
              if (value > 0L) {
                salaryShortfallsByHousehold.put(entry.getKey(), value);
              }
            }
            Map<UnitId, Long> stipendShortfallsByUnit = new LinkedHashMap<>();
            for (GovDaily.UpkeepDue due : settled.dues()) {
              if (due.resource() instanceof GovDaily.Commodity && due.shortfall() > 0L) {
                stipendShortfallsByUnit.merge(due.unitId(), due.shortfall(), Math::addExact);
              }
            }
            GovernmentServiceDesertionBridge.Outcome flight =
                GovernmentServiceDesertionBridge.execute(
                    currentSocial,
                    economy,
                    stepper::data,
                    units,
                    salaryShortfallsByHousehold,
                    stipendShortfallsByUnit,
                    stepper.accounts(),
                    flightTopology,
                    stepper.lastMarketReport().map(List::of).orElse(List.of()),
                    day);
            currentSocial = flight.social();
            stepper.applyGovServiceCommitmentReductions(flight.commitmentReductions());
            if (!flight.populationDeltas().isEmpty()) {
              stepper.applyHouseholdPopulationDeltas(flight.populationDeltas());
            }
            // ★ 户空被删的源户也要显式把劳动预算归 0（recompute 只写传入键，不清理缺键行）。
            Map<HouseholdId, Long> flightLaborBudgets =
                new LinkedHashMap<>(laborBudgetsOf(currentSocial, day));
            for (HouseholdId emptied : flight.emptiedHouseholds()) {
              flightLaborBudgets.put(emptied, 0L);
            }
            stepper.updateComposition(compositionOf(currentSocial));
            stepper.recomputeLaborBudgets(flightLaborBudgets);
            stepper.updateNaturalNeeds(naturalNeedsOf(currentSocial, day));
            pendingFlightEvictions.addAll(flight.evictions());
            for (HexCrisisSignal signal : flight.signals()) {
              stepper.putCrisisSignal(signal);
            }
            if (TIME.isDebugEnabled()) {
              GovernmentServiceDesertionBridge.Report flightReport = flight.report();
              TIME.debug(
                  LogEvent.of(
                      "GOV_SERVICE_DESERTION_DAY",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "mapId",
                      mapId,
                      "govUnits",
                      flightReport.govUnits(),
                      "householdsEvaluated",
                      flightReport.householdsEvaluated(),
                      "rises",
                      flightReport.rises(),
                      "falls",
                      flightReport.falls(),
                      "tierCrossings",
                      flightReport.tierCrossings(),
                      "flights",
                      flightReport.flights(),
                      "fledPopulation",
                      flightReport.fledPopulation(),
                      "emptiedHouseholds",
                      flightReport.emptiedHouseholds(),
                      "noDestinationSkips",
                      flightReport.noDestinationSkips()));
            }
          } else {
            // 本日没有 GOV 读数：注入集既无法消费也无法验证，直接作废（机制要影响就必须逐 tick 重新注入）。
            govEfficiencyModifiers.clear();
          }
          // ★★ P4a/P4b：通用周期家户库存扣增 —— 预算类别（GovDaily/军俸/工资）之后、市场报告/日末之前执行。
          //   ★ 有 GOV 时：预算桥已用 applyBudgeted 执行完类别内规则，这里只执行 EconomyData 的持久规则（extraRules 空表），
          //     保证持久规则不会先到先得地抢走预算类别预留的国库；
          //   ★ 无 GOV 时：沿用旧口径（军俸派生 + 持久/瞬态合并执行），但不进入任何预算类别。
          if (govActive) {
            PeriodicHouseholdAdjustmentExecutor.applyDue(
                economy, List.of(), stepper.accounts(), day);
          } else {
            MilitaryPayRuleBridge.Report militaryPayReport =
                units == null
                    ? MilitaryPayRuleBridge.Report.empty()
                    : MilitaryPayRuleBridge.deriveReport(units, currentSocial, economy, day);
            if (TIME.isDebugEnabled()) {
              TIME.debug(
                  LogEvent.of(
                      "MILITARY_PAY_BRIDGE",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "units",
                      militaryPayReport.units(),
                      "policies",
                      militaryPayReport.policies(),
                      "rules",
                      militaryPayReport.rules().size(),
                      "gaps",
                      militaryPayReport.gaps().size()));
            }
            PeriodicHouseholdAdjustmentExecutor.applyDue(
                economy, militaryPayReport.rules(), stepper.accounts(), day);
          }

          // ★★ M2.7：把"最近一轮市场报告"投递给读口（进程内、不落盘、只在同一 tick 内可信；见 MarketReportFeed 的类注）。
          MarketReportFeed.publish(mapId, stepper.lastMarketReport(), day);
          // ★★ S3：把"当日结账账本"投递给读口（租/工资欠款与逐规则欠额的唯一进程内来源；同款边界）。
          EconomyDayFeed.publish(mapId, Optional.of(ledger), day);
          List<ActorEntry> entries = OwnershipBooks.fold(ledger, OwnershipBooks.REASONS_NOT_FOLDED);
          if (!entries.isEmpty()) {
            // ★★ D3：过滤集取**活会话**的键集（不是 advance 起点的快照）—— 与下面 landAccountSession 的落回集合逐字同源。
            currentBooks =
                OwnershipBooks.apply(currentBooks, entries, stepper.accounts().accounts().keySet());
            for (HouseholdAccountKey key : currentBooks.accounts().keySet()) {
              writes.add(accountAddress(key));
            }
          }
          // ★★ H1：家户账**按绝对值**落回 actor 切片（不是"再叠加一遍条目"，见 OwnershipBooks 的类注）——
          //   日耗 / 投入 / 同格取材只写副本（它们不是产权条目），而关系实付既进条目、也已计进副本
          //   ⇒ 这一步是它们唯一共同的落点。★ 副本是**活的**（step 就地更新）⇒ 每天重新读访问器，不缓存引用。
          // ★★ S1：全部账户（家户 + 经营者；商品 + 货币 + 冻结）按会话绝对值一次落回。
          currentBooks = OwnershipBooks.landAccountSession(currentBooks, stepper.accounts());
          // ★★ Batch B 起：日末不再有生理压力/月度出生死亡回写 —— 生死已移到**日初**（见循环最前面），
          //   经济行人口由 HouseholdBook 的逐户 delta 同步；P8 迁移规划接线位（原默认 no-op，挂在旧月结块里）
          //   随月结块一并删除，P9 重新接线前必须先定周期边界（见报告）。
        }
        EconomyData currentEconomy = stepper.finish();
        long finalPopulation = 0L;
        for (HouseholdEconomy householdEconomy : currentEconomy.classes().values()) {
          finalPopulation += householdEconomy.population();
        }
        TIME.info(
            LogEvent.of(
                "ECONOMY_ADVANCE_END",
                AppLogSource.DAILY_LOOP,
                "day",
                to.get().tick(),
                "mapId",
                mapId,
                "toTick",
                to.get().tick(),
                "days",
                to.get().tick() - range.from().tick(),
                "finalPopulation",
                finalPopulation,
                "finalHouseholds",
                currentEconomy.classes().size(),
                "finalDebtContracts",
                currentEconomy.debtContracts().size(),
                "finalMarkets",
                currentEconomy.markets().size()));
        if (govActive) {
          if (!missingAdminRegions.isEmpty()) {
            // ★ 行政区域缺失是数据面异常：保留 WARN 档，只换新形态与来源字段。
            TIME.warn(
                LogEvent.of(
                    "GOV_ADMIN_REGION_MISSING",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    to.get().tick(),
                    "mapId",
                    mapId,
                    "count",
                    missingAdminRegions.size(),
                    "first",
                    missingAdminRegions.iterator().next()));
          }
          adminTotals.logSummary(
              mapId, range.from().tick(), to.get().tick(), governmentCount(currentGov));
        }
        LinkedHashMap<String, ChangeSet> moduleChanges = new LinkedHashMap<>();
        moduleChanges.put(ECONOMY, EconomyChangeSet.between(economyBase, currentEconomy));
        moduleChanges.put(SOCIAL, SocialChangeSet.between(socialBase, currentSocial));
        moduleChanges.put(ACTOR, ActorChangeSet.between(actor, currentBooks));
        if (govActive) {
          // ★ 基线取**未引导**的 govState：本推进新补的空读数（GovOfficeState.empty）也是本轮的合法结果，
          //   不能因为"它本来就是空的"被差分成 Unchanged 而丢掉（否则首建 GOV 永远不会激活结算）。
          moduleChanges.put(GOV, GovChangeSet.between(govState, currentGov));
        }
        // ★ Z3b：推进结束后把"已知 GOV"刷成本轮结果——下一 tick 的注入与调用方看到的状态一致。
        rememberKnownGovUnits(currentGov);
        return new WorldTimeProposal(NAMESPACE, moduleChanges, reads, writes);
      } finally {
        stepper.close();
      }
    } catch (RuntimeException | Error failure) {
      // stepper 构造失败 ⇒ 池从未交给 finish()/close()；这里补关一次（已关时幂等）。
      parallelism.close();
      throw failure;
    }
  }

  /**
   * ★★ <b>Z7d-2：取走本 tick 的 unit 摘除请求</b>（由组合参与者 {@code PopulationUnitTimeParticipant} 在 unit
   * 变更集里执行）。 取走后本参与者记录清零；空表共享单例。
   */
  public List<GovernmentServiceDesertionBridge.Eviction> drainFlightEvictions() {
    if (pendingFlightEvictions.isEmpty()) {
      return List.of();
    }
    List<GovernmentServiceDesertionBridge.Eviction> drained = List.copyOf(pendingFlightEvictions);
    pendingFlightEvictions.clear();
    return drained;
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

  /** ★ 是否旧档迁移态（只有旧档需要按 social 重建份额；新档逐 lot 严格校验）。 */

  /** S3b：unit 切片（缺省 ⇒ null；只读，用于家户一致性校核/自动同步）。 */
  private static UnitState unitStateOf(SimulationState state) {
    Snapshot snapshot = state.module("unit").orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /**
   * ★★ <b>退出商品市场的单位户集合</b>（组合根唯一同时看得见 unit 与经济的地方）——{@code Σ Unit.households()}。★ 政府国库户
   * <b>不</b>在这里（R1 起它回到商品市场，只按 {@code EconomyData.govMarketMandates()} 的明确授权下单；名单由 economy 自己的
   * {@code governments()} 派生，见 {@code GovernmentMarketMandatePlan}）。★ 缺 unit 切片（旧档/纯经济夹具）⇒ 空集。
   */
  private static Set<HouseholdId> unitHouseholdExclusions(UnitState units) {
    if (units == null) {
      return Set.of();
    }
    LinkedHashSet<HouseholdId> excluded = new LinkedHashSet<>();
    for (Unit unit : units.units().values()) {
      for (HouseholdId household : unit.households()) {
        excluded.add(household);
      }
    }
    return Collections.unmodifiableSet(excluded);
  }

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ECONOMY)
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  private static SocialData socialOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(SOCIAL)
            .orElseThrow(() -> new IllegalStateException("state 里没有 social 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /** ★★ P2-D：gov 切片（缺切片 ⇒ {@link GovState#empty()}；旧档/无 GOV 世界），类型不符当场抛。 */
  private static GovState govOf(SimulationState state) {
    Optional<Snapshot> snapshot = state.module(GOV);
    if (snapshot.isEmpty()) {
      return GovState.empty();
    }
    if (!(snapshot.get() instanceof GovSnapshot govSnapshot)) {
      throw new IllegalStateException(
          "state 的 gov 切片不是 GovSnapshot: " + snapshot.get().getClass().getName());
    }
    return govSnapshot.state();
  }

  /** ★★ P2-D：map 切片（缺省 ⇒ null；gov 未激活时不强制在场）。 */
  private static GameMap mapOfOrNull(SimulationState state) {
    Snapshot snapshot = state.module(MAP).orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  private String economyAddressRoot() {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId))).canonical();
  }

  private String socialAddressRoot() {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId))).canonical();
  }

  private String economyAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String socialAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String unitAddressRoot() {
    return new Address(List.of(new Namespace("unit"), Entity.of(mapId))).canonical();
  }

  private String actorAddressRoot() {
    return new Address(List.of(new Namespace(ACTOR), Entity.of(mapId))).canonical();
  }

  private String mapAddressRoot() {
    return new Address(List.of(new Namespace("map"), Entity.of(mapId))).canonical();
  }

  /** ★★ P2-D：gov 片根地址（形制照其余切片：{@code gov:<mapId>}）。 */
  private String govAddressRoot() {
    return new Address(List.of(new Namespace(GOV), Entity.of(mapId))).canonical();
  }

  /**
   * 一本产权账的地址：{@code actor:<mapId>:goods.<key>} —— ★ 形制照 {@code ActorResolver}（它的第三段 kind 就是 {@code
   * goods}）。{@code <key>} 是 {@link HouseholdAccountKey#toString()} 的产物，<b>本类不复述那个格式</b>。
   */
  private String accountAddress(HouseholdAccountKey key) {
    return new Address(
            List.of(new Namespace(ACTOR), Entity.of(mapId), Entity.of("goods", key.toString())))
        .canonical();
  }

  /** actor 切片只能从 actor 模块拿（铁律 3/4）；缺席或类型不对都是装配故障（同 {@link #economyOf} 的口径）。 */
  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ACTOR)
            .orElseThrow(() -> new IllegalStateException("state 里没有 actor 切片（装配故障：产权落账口要求切片在场）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
  }

  // ── P2-D：行政引导 / 效率表 / 信号折叠 / 日累计读数 ────────────────────────────────────

  /**
   * ★★ <b>行政读数引导</b>（阶段 11b 口径不变）：为"带 {@link GovernmentFormation} 但 gov 片还没有读数"的单位补一条 {@link
   * GovOfficeState#empty(UnitId, long)} 作为本推进基线。
   *
   * <p>确定性：缺读数单位按 {@link UnitId#value()} 升序追加；已有读数原样保留（键序不变）。没有任何缺项 ⇒ 返回入参同一实例 （无 GOV/已引导世界零变化）。
   */
  private static GovState withBootstrapOffices(GovState base, UnitState units, long tick) {
    LinkedHashMap<UnitId, GovOfficeState> offices = new LinkedHashMap<>(base.offices());
    List<UnitId> missing = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation
          && !offices.containsKey(unit.id())) {
        missing.add(unit.id());
      }
    }
    if (missing.isEmpty()) {
      return base;
    }
    missing.sort(Comparator.comparing(UnitId::value));
    for (UnitId id : missing) {
      offices.put(id, GovOfficeState.empty(id, tick));
    }
    // ★ Z2 拷贝纪律：只补 offices，两条源状态（administrationPlans/budgetPolicies）必须原样带过。
    return base.withOffices(offices);
  }

  /**
   * ★★ Z3b 每 tick 唯一一份计算的完整产物：效率表（GovDaily 消费）+ ‰ 投影（税侧消费）+ 流量（进程内投递）+ 两维承诺供给 （Z3c
   * 的空缺/服务零告警判据；与效率同源，防止第二处重算）。
   */
  private record GovEfficiencyDay(
      Map<UnitId, GovEfficiency.Efficiency> byUnit,
      Map<UnitId, Long> efficiencyPerMilleByUnit,
      Map<UnitId, GovServiceFlow> flows,
      Map<UnitId, GovernmentServiceLaborBridge.Supply> supplyByUnit) {

    private GovEfficiencyDay {
      byUnit = Collections.unmodifiableMap(new LinkedHashMap<>(byUnit)); // ★ 冻在构造处（保序）
      efficiencyPerMilleByUnit =
          Collections.unmodifiableMap(new LinkedHashMap<>(efficiencyPerMilleByUnit));
      flows = Collections.unmodifiableMap(new LinkedHashMap<>(flows));
      supplyByUnit = Collections.unmodifiableMap(new LinkedHashMap<>(supplyByUnit));
    }
  }

  /**
   * ★★ <b>算当日 GOV 效率/流量（唯一供给权威，设计书 §3/§10 C2）</b>：{@code govState.offices()} 里每个 office 取单位上的
   * {@link GovernmentFormation}（没有 ⇒ 不进表，由 {@link GovDaily#settle} 当场 ERROR），用 {@link GovDemand#of}
   * 得建议需求（只进 日志/建议），按承诺→两维供给桥（{@link GovernmentServiceLaborBridge}）算两维供给，再按当 tick 注入的四项动态修正调 {@link
   * GovEfficiency#of} 得唯一一份结果；{@link GovServiceFlow} 从同一份结果派生。
   *
   * <p>★★ <b>Z7d-1</b>：供给桥按**当 tick 家户行工作副本**（{@code currentHouseholdRows.laborMilli}，已含 satiety
   * 折算）逐户 cap 到有效供给；cap 掉的部分只发一条具名 INFO {@code GOV_SERVICE_UNDERFED}（不 WARN、不自动缩承诺）。
   *
   * <p>★ 检查该单位管辖的每个 Region 是否都在 map 里，缺的累积进 {@code missingRegions}（只累积、不抛；调用方整轮汇总成一条具名
   * WARN——这是预存量口径，不在 Z3b 改动）。
   *
   * @param currentHouseholdRows 本 tick 经济结算工作副本的家户行（有效供给 cap 的实际劳动来源；不得为 null）
   * @param modifiers 本 tick 的动态修正注入集（已按当 tick 取走；缺项 = 四项 1000‰ 中性）
   * @param missingRegions 跨日累积的 {@code unit=…,region=…} 明细（调用方只在整轮结束时汇总 WARN 一次）
   */
  private static GovEfficiencyDay computeGovEfficiency(
      GovState govState,
      UnitState units,
      GameMap map,
      SocialData social,
      EconomyData economy,
      Map<HouseholdId, HouseholdEconomy> currentHouseholdRows,
      Map<UnitId, GovEfficiencyModifier> modifiers,
      long day,
      Set<String> missingRegions) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(currentHouseholdRows, "currentHouseholdRows");
    Objects.requireNonNull(modifiers, "modifiers");
    Map<UnitId, GovEfficiency.Efficiency> byUnit = new LinkedHashMap<>();
    Map<UnitId, Long> efficiencyPerMilleByUnit = new LinkedHashMap<>();
    Map<UnitId, GovServiceFlow> flows = new LinkedHashMap<>();
    Map<UnitId, GovernmentServiceLaborBridge.Supply> supplyByUnit = new LinkedHashMap<>();
    long standardLaborMilliHoursPerTick = social.provisioning().standardLaborMilliHoursPerTick();
    List<UnitId> ordered = new ArrayList<>(govState.offices().keySet());
    ordered.sort(Comparator.comparing(UnitId::value));
    for (UnitId unitId : ordered) {
      Unit unit = units.units().get(unitId);
      if (unit == null) {
        continue; // 状态损坏由 GovDaily.settle 当场抛；税侧只保证"查不到效率就不征"。
      }
      UnitModule module = unit.module().orElse(null);
      if (!(module instanceof GovernmentFormation formation)) {
        continue; // 没有 GovernmentFormation ⇒ 不进效率表，由 GovDaily.settle 当场 ERROR（无 GOV 不征）。
      }
      unit.jurisdiction()
          .ifPresent(
              jurisdiction -> {
                for (var regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
                  if (!map.regions().containsKey(regionId)) {
                    missingRegions.add("unit=" + unitId.value() + ",region=" + regionId.value());
                  }
                }
              });
      Map<HexCoord, GovDemand.HexDemand> suggestedDemand = GovDemand.of(map, social, unit);
      GovAdministrationPlan plan = govState.administrationPlanOrDefault(unitId);
      GovernmentServiceLaborBridge.Supply supply =
          GovernmentServiceLaborBridge.supply(
              economy, currentHouseholdRows, unitId, formation, plan, day);
      GovEfficiencyModifier modifier = modifiers.get(unitId);
      long securitySupplyModifier =
          modifier == null
              ? GovEfficiencyModifier.NEUTRAL_PER_MILLE
              : modifier.securitySupplyPerMille();
      long paperworkSupplyModifier =
          modifier == null
              ? GovEfficiencyModifier.NEUTRAL_PER_MILLE
              : modifier.paperworkSupplyPerMille();
      long securityDemandModifier =
          modifier == null
              ? GovEfficiencyModifier.NEUTRAL_PER_MILLE
              : modifier.securityDemandPerMille();
      long paperworkDemandModifier =
          modifier == null
              ? GovEfficiencyModifier.NEUTRAL_PER_MILLE
              : modifier.paperworkDemandPerMille();
      // ★ R2：口岸维（第三维）的供给与两个动态修正 —— 与另两维同源、同一次计算。
      long portSupplyModifier =
          modifier == null
              ? GovEfficiencyModifier.NEUTRAL_PER_MILLE
              : modifier.portSupplyPerMille();
      long portDemandModifier =
          modifier == null
              ? GovEfficiencyModifier.NEUTRAL_PER_MILLE
              : modifier.portDemandPerMille();
      GovEfficiency.Efficiency efficiency =
          GovEfficiency.of(
              formation,
              suggestedDemand,
              plan,
              supply.securityLaborMilli(),
              supply.paperworkLaborMilli(),
              supply.portLaborMilli(),
              securitySupplyModifier,
              paperworkSupplyModifier,
              portSupplyModifier,
              securityDemandModifier,
              paperworkDemandModifier,
              portDemandModifier,
              standardLaborMilliHoursPerTick);
      if (supply.underfedHouseholds() > 0L) {
        // ★★ Z7d-1：在编但供给不足 —— 承诺是职位（C7 不缩/删），实际劳动被饥饿折算 cap；
        //   只给一条具名 INFO（不 WARN、不自动缩承诺/招募/注资），效率按同一份有效供给如实下降。
        long committedLabor =
            Math.addExact(
                supply.committedSecurityLaborMilli(), supply.committedPaperworkLaborMilli());
        long effectiveLabor =
            Math.addExact(supply.securityLaborMilli(), supply.paperworkLaborMilli());
        TIME.info(
            LogEvent.of(
                "GOV_SERVICE_UNDERFED",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "underfedHouseholds",
                supply.underfedHouseholds(),
                "committedLaborMilli",
                committedLabor,
                "effectiveLaborMilli",
                effectiveLabor,
                "reason",
                "starvation-reduced-household-labor-capped-effective-supply"));
      }
      if (GovEfficiency.anySupplyZero(
          supply.securityLaborMilli(), supply.paperworkLaborMilli(), supply.portLaborMilli())) {
        // ★ §3：无挂岗位家户（无承诺）⇒ 该维供给 0、效率 0，具名 INFO（不是静默 0）。
        // ★ Z7d-1：若承诺不为 0 而是被饥饿 cap 到 0，reason 具名为 committed-but-underfed（不冒充"无岗位"）。
        String zeroSupplyReason =
            supply.committedSecurityLaborMilli() == 0L
                    && supply.committedPaperworkLaborMilli() == 0L
                ? "no-posted-household-commitment"
                : "committed-supply-underfed-to-zero";
        TIME.info(
            LogEvent.of(
                "GOV_EFFICIENCY_ZERO_SUPPLY",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "securitySupplyLaborMilli",
                supply.securityLaborMilli(),
                "paperworkSupplyLaborMilli",
                supply.paperworkLaborMilli(),
                "portSupplyLaborMilli",
                supply.portLaborMilli(),
                "reason",
                zeroSupplyReason));
      }
      if (TIME.isDebugEnabled()) {
        TIME.debug(
            LogEvent.of(
                "GOV_SERVICE_SUPPLY_COMPUTED",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "securityPlannedLaborMilli",
                plan.securityPlannedLaborMilli(),
                "paperworkPlannedLaborMilli",
                plan.paperworkPlannedLaborMilli(),
                "portPlannedLaborMilli",
                plan.portPlannedLaborMilli(),
                "securitySupplyLaborMilli",
                supply.securityLaborMilli(),
                "paperworkSupplyLaborMilli",
                supply.paperworkLaborMilli(),
                "portSupplyLaborMilli",
                supply.portLaborMilli(),
                "securityCommittedLaborMilli",
                supply.committedSecurityLaborMilli(),
                "paperworkCommittedLaborMilli",
                supply.committedPaperworkLaborMilli(),
                "portCommittedLaborMilli",
                supply.committedPortLaborMilli(),
                "underfedHouseholds",
                supply.underfedHouseholds(),
                "securityDemandLaborMilli",
                efficiency.securityDemandLaborMilli(),
                "paperworkDemandLaborMilli",
                efficiency.paperworkDemandLaborMilli(),
                "modifierSource",
                modifier == null ? "-" : modifier.source(),
                "securityCoveragePerMille",
                efficiency.securityCoveragePerMille(),
                "paperworkCoveragePerMille",
                efficiency.paperworkCoveragePerMille(),
                "efficiencyPerMille",
                efficiency.efficiencyPerMille()));
      }
      byUnit.put(unitId, efficiency);
      efficiencyPerMilleByUnit.put(unitId, efficiency.efficiencyPerMille());
      supplyByUnit.put(unitId, supply);
      flows.put(
          unitId,
          GovServiceFlow.of(
              unitId,
              day,
              supply.committedSecurityLaborMilli(),
              supply.committedPaperworkLaborMilli(),
              efficiency));
    }
    return new GovEfficiencyDay(byUnit, efficiencyPerMilleByUnit, flows, supplyByUnit);
  }

  /** ★ 取走本 tick 的注入集并清空（"消费后清空"；未再注入的下一日回到 1000‰ 中性）。 */
  private Map<UnitId, GovEfficiencyModifier> drainGovEfficiencyModifiers() {
    if (govEfficiencyModifiers.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<UnitId, GovEfficiencyModifier> drained =
        new LinkedHashMap<>(govEfficiencyModifiers);
    govEfficiencyModifiers.clear();
    return Collections.unmodifiableMap(drained); // ★ 冻在返回处（保序）
  }

  /** ★ 注入集里指向"本 tick 无效率结果（非 office/无编制/单位不存在）"的 GOV ⇒ 具名 INFO 拒绝，不静默采用。 */
  private static void reportUnknownGovEfficiencyModifiers(
      Map<UnitId, GovEfficiencyModifier> modifiers, Set<UnitId> activeGovs, long day) {
    for (UnitId gov : modifiers.keySet()) {
      if (!activeGovs.contains(gov)) {
        TIME.info(
            LogEvent.of(
                "GOV_EFFICIENCY_MODIFIER_REJECTED",
                AppLogSource.DAILY_LOOP,
                "reason",
                "unknown-gov",
                "day",
                day,
                "gov",
                gov.value()));
      }
    }
  }

  /** ★ 注入接口的具名拒绝（无 day 上下文 ⇒ SYSTEM 来源 + INFO，不编造 tick）。 */
  private static void logGovEfficiencyModifierInjectionRejected(String reason, UnitId gov) {
    TIME.info(
        LogEvent.of(
            "GOV_EFFICIENCY_MODIFIER_REJECTED",
            AppLogSource.GOV_EFFICIENCY_INJECT,
            "reason",
            reason,
            "gov",
            gov == null ? "-" : gov.value()));
  }

  /** ★ 把 {@link GovDaily.SignalDraft} 折成 {@link HexCrisisSignal}；kind 字符串 → 枚举的映射只此一处。 */
  private static HexCrisisSignal toCrisisSignal(GovDaily.SignalDraft draft, long day) {
    HexCrisisSignal.Kind kind =
        switch (draft.kind()) {
          case GovDaily.KIND_ADMIN_SUPPLY -> HexCrisisSignal.Kind.ADMIN_SUPPLY;
          case GovDaily.KIND_ADMIN_SECURITY -> HexCrisisSignal.Kind.ADMIN_SECURITY;
          case GovDaily.KIND_ADMIN_PAPERWORK -> HexCrisisSignal.Kind.ADMIN_PAPERWORK;
          case GovDaily.KIND_ADMIN_BUDGET_SHORTFALL -> HexCrisisSignal.Kind.ADMIN_BUDGET_SHORTFALL;
          case GovDaily.KIND_ADMIN_REMITTANCE_SHORTFALL ->
              HexCrisisSignal.Kind.ADMIN_REMITTANCE_SHORTFALL;
          case GovDaily.KIND_ADMIN_PLAN_MISSING -> HexCrisisSignal.Kind.ADMIN_PLAN_MISSING;
          case GovDaily.KIND_ADMIN_SERVICE_FLOW_ZERO ->
              HexCrisisSignal.Kind.ADMIN_SERVICE_FLOW_ZERO;
          case GovDaily.KIND_ADMIN_VACANCY -> HexCrisisSignal.Kind.ADMIN_VACANCY;
          case GovDaily.KIND_ADMIN_CONTRACT -> HexCrisisSignal.Kind.ADMIN_CONTRACT;
          default ->
              throw new IllegalStateException(
                  "未知的 GovDaily SignalDraft.kind（映射表只此一处）: " + draft.kind());
        };
    return new HexCrisisSignal(
        CrisisSignalId.idOf(draft.hex(), kind.name()),
        draft.hex(),
        kind,
        Math.toIntExact(draft.severity()),
        day,
        draft.evidence(),
        List.of(),
        List.of(),
        draft.reason());
  }

  /** 本推进覆盖的 GOV 单位数（= gov 片读数条数；只进日志）。 */
  private static long governmentCount(GovState govState) {
    return govState.offices().size();
  }

  /** ★★ P2-D：跨日累计的税/俸禄读数（只进日志/汇总，不进状态、不进变更集）。 */
  private static final class AdminTotals {

    private long taxGrainAssessed;
    private long taxGrainCollected;
    private long taxSilverAssessed;
    private long taxSilverCollected;
    private long upkeepPaidGrain;
    private long upkeepPaidCloth;
    private long upkeepPaidMoney;
    private long upkeepShortfallTotal;
    private long adminSalaryPaidGrain;
    private long adminSalaryPaidSilver;
    private long adminSalaryShortfallGrain;
    private long adminSalaryShortfallSilver;
    private long adminSalaryHouseholds;
    private long adminSalaryNoPayHouseholds;
    private long signals;

    void recordTax(JurisdictionDailyTax.Report report) {
      taxGrainAssessed = Math.addExact(taxGrainAssessed, report.grain().assessed());
      taxGrainCollected = Math.addExact(taxGrainCollected, report.grain().collected());
      taxSilverAssessed = Math.addExact(taxSilverAssessed, report.money().assessed());
      taxSilverCollected = Math.addExact(taxSilverCollected, report.money().collected());
    }

    void recordSalary(GovBudgetExecutionBridge.SalaryTotals totals) {
      adminSalaryPaidGrain = Math.addExact(adminSalaryPaidGrain, totals.paidGrainMilli());
      adminSalaryPaidSilver = Math.addExact(adminSalaryPaidSilver, totals.paidSilverMilli());
      adminSalaryShortfallGrain =
          Math.addExact(adminSalaryShortfallGrain, totals.shortfallGrainMilli());
      adminSalaryShortfallSilver =
          Math.addExact(adminSalaryShortfallSilver, totals.shortfallSilverMilli());
      adminSalaryHouseholds = Math.addExact(adminSalaryHouseholds, totals.households());
      adminSalaryNoPayHouseholds =
          Math.addExact(adminSalaryNoPayHouseholds, totals.noPayHouseholds());
    }

    void recordExtraSignals(int count) {
      signals = Math.addExact(signals, count);
    }

    void recordGovDaily(GovDaily.Outcome outcome) {
      for (GovDaily.UpkeepDue due : outcome.dues()) {
        switch (due.resource().name()) {
          case "grain" -> upkeepPaidGrain = Math.addExact(upkeepPaidGrain, due.paid());
          case "cloth" -> upkeepPaidCloth = Math.addExact(upkeepPaidCloth, due.paid());
          case "silver" -> upkeepPaidMoney = Math.addExact(upkeepPaidMoney, due.paid());
          default -> throw new IllegalStateException("未知的 GovDaily 资源名: " + due.resource().name());
        }
        upkeepShortfallTotal = Math.addExact(upkeepShortfallTotal, due.shortfall());
      }
      signals = Math.addExact(signals, outcome.signals().size());
    }

    void logSummary(String mapId, long fromTick, long toTick, long governments) {
      TIME.info(
          LogEvent.of(
              "GOV_ADMIN_ADVANCE_END",
              AppLogSource.DAILY_LOOP,
              "day",
              toTick,
              "mapId",
              mapId,
              "fromTick",
              fromTick,
              "toTick",
              toTick,
              "governments",
              governments,
              "taxGrainAssessed",
              taxGrainAssessed,
              "taxGrainCollected",
              taxGrainCollected,
              "taxSilverAssessed",
              taxSilverAssessed,
              "taxSilverCollected",
              taxSilverCollected,
              "upkeepPaidGrain",
              upkeepPaidGrain,
              "upkeepPaidCloth",
              upkeepPaidCloth,
              "upkeepPaidMoney",
              upkeepPaidMoney,
              "upkeepShortfallTotal",
              upkeepShortfallTotal,
              "adminSalaryHouseholds",
              adminSalaryHouseholds,
              "adminSalaryPaidGrain",
              adminSalaryPaidGrain,
              "adminSalaryPaidSilver",
              adminSalaryPaidSilver,
              "adminSalaryShortfallGrain",
              adminSalaryShortfallGrain,
              "adminSalaryShortfallSilver",
              adminSalaryShortfallSilver,
              "adminSalaryNoPayHouseholds",
              adminSalaryNoPayHouseholds,
              "signals",
              signals));
    }
  }

  // ── P0 迁移桥的净 delta / 对账读数（纯函数，不写状态）─────────────────────────────────────

  /**
   * ★★ <b>P0：迁移 outbox → 逐户净人口 delta</b>（源 {@code -population}、目标 {@code +population}，同户出现多次 则 Σ
   * 合并；结果为 0 的家户不入表——{@code applyHouseholdPopulationDeltasInto} 拒绝 0 delta）。
   */
  private static Map<HouseholdId, Long> netPopulationDeltas(
      List<EconomyPopulationTransfer> transfers) {
    LinkedHashMap<HouseholdId, Long> deltas = new LinkedHashMap<>();
    for (EconomyPopulationTransfer transfer : transfers) {
      deltas.merge(transfer.source(), -transfer.population(), Math::addExact);
      deltas.merge(transfer.target(), transfer.population(), Math::addExact);
    }
    deltas.entrySet().removeIf(entry -> entry.getValue() == 0L);
    return deltas;
  }

  /** ★★ P0：outbox 迁移人数合计（只进日志，不写状态）。 */
  private static long totalTransferredPopulation(List<EconomyPopulationTransfer> transfers) {
    long total = 0L;
    for (EconomyPopulationTransfer transfer : transfers) {
      total = Math.addExact(total, transfer.population());
    }
    return total;
  }

  /** ★★ P0：Social 总人口（逐家户 {@link SocialData#householdPopulation(HouseholdId)} 求和；只进日志/对账）。 */
  private static long totalSocialPopulation(SocialData social) {
    long total = 0L;
    for (HouseholdId household : social.households().keySet()) {
      total = Math.addExact(total, social.householdPopulation(household));
    }
    return total;
  }

  /** ★★ P0：Economy 行人口合计（只进日志/对账）。 */
  private static long totalEconomyPopulation(Map<HouseholdId, HouseholdEconomy> rows) {
    long total = 0L;
    for (HouseholdEconomy row : rows.values()) {
      total = Math.addExact(total, row.population());
    }
    return total;
  }

  /** ★★ P2-A A3：Social 的家户成员表 → 经济结算的只读组成投影（不落任何 Economy 状态）。 */
  private static Map<HouseholdId, Map<PeopleLotId, Long>> compositionOf(SocialData social) {
    Map<HouseholdId, Map<PeopleLotId, Long>> composition = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Household> entry : social.households().entrySet()) {
      composition.put(entry.getKey(), entry.getValue().members());
    }
    return composition;
  }

  /** ★★ Z7d-1：经济侧逐户粮缺口累计（{@link FlowRow#unmetNeed()} 的粮维，本周期累计）→ household 表（保序只读）。 */
  private static Map<HouseholdId, Long> cumulativeGrainUnmetOf(Map<HouseholdId, FlowRow> flows) {
    Map<HouseholdId, Long> cumulative = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      cumulative.put(
          entry.getKey(), entry.getValue().unmetNeed().getOrDefault(EconomySettlement.GRAIN, 0L));
    }
    return cumulative;
  }

  /**
   * ★★ <b>Z7d-1 经济→Social 断顿回写（app 组合根编排的唯一落点）</b>：把"当日粮缺口 + 当日粮需求"经 {@link HouseholdSatietyBridge}
   * 纯函数折成逐户 satietyPerMille，返回**新的** SocialData（同一 revision 内生效）。
   *
   * <pre>
   * 当日缺口 = FlowRow.unmetNeed[grain]（本周期累计） − 前一日/基态累计
   *            （累计 < 上一基线 ⇒ 周期第一天清零，当日缺口 = 当前累计）
   * 有缺口   ⇒ satiety ← max(0, satiety − ⌊150 × min(1000, ⌊缺口×1000÷当日需求⌋) ÷ 1000⌋)
   * 无缺口   ⇒ satiety ← min(1000, satiety + 20)
   * 缺口为 0 且 satiety=1000 的户不写回（保持表稀疏；缺键语义 = 1000）
   * </pre>
   *
   * <p>★ <b>布不足不在这里发生</b>：{@code unmetNeed[cloth]} 照旧只进经济读口/告警，不降劳动。
   *
   * @param social 当前 SocialData（含本日生死结算后的状态）
   * @param previousCumulativeGrainUnmet 上一日（或 revision 基态）逐户粮缺口累计基线；不得为 null
   * @param flows 当日结算后的经济流水（{@code stepper.flows()}）；不得为 null
   * @param dayNaturalNeeds 当日注入的逐户自然需求（{@code naturalNeedsOf(currentSocial, day)}）；不得为 null
   * @param day 世界日（只进日志）
   */
  private SocialData applyHouseholdSatietyWriteback(
      SocialData social,
      Map<HouseholdId, Long> previousCumulativeGrainUnmet,
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> dayNaturalNeeds,
      long day) {
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(previousCumulativeGrainUnmet, "previousCumulativeGrainUnmet");
    Objects.requireNonNull(flows, "flows");
    Objects.requireNonNull(dayNaturalNeeds, "dayNaturalNeeds");
    List<HouseholdId> ordered = new ArrayList<>(social.households().keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));
    List<HouseholdSatietyBridge.DailyFeed> feeds = new ArrayList<>();
    for (HouseholdId household : ordered) {
      FlowRow flow = flows.get(household);
      if (flow == null) {
        continue; // 该 Social 户没有经济行（旧档/装配边界）：没有断顿证据，不回写、不造行
      }
      long cumulative = flow.unmetNeed().getOrDefault(EconomySettlement.GRAIN, 0L);
      Long previous = previousCumulativeGrainUnmet.get(household);
      long dailyGrainUnmetMilli;
      if (previous == null || cumulative < previous.longValue()) {
        // 周期第一天（FlowRow 清零）或首次观察：当前累计就是当日量。
        dailyGrainUnmetMilli = cumulative;
      } else {
        dailyGrainUnmetMilli = Math.subtractExact(cumulative, previous.longValue());
      }
      long satiety = social.satietyPerMille(household);
      if (dailyGrainUnmetMilli <= 0L && satiety >= SocialData.SATIETY_FULL_PER_MILLE) {
        continue; // 吃饱且无新缺口：不写回（缺键 = 1000，保持表稀疏与逐字节稳定）
      }
      long grainNeedMilli =
          dayNaturalNeeds
              .getOrDefault(household, Map.of())
              .getOrDefault(EconomySettlement.GRAIN, 0L);
      feeds.add(
          new HouseholdSatietyBridge.DailyFeed(household, dailyGrainUnmetMilli, grainNeedMilli));
    }
    HouseholdSatietyBridge.Report report = HouseholdSatietyBridge.apply(social, feeds);
    if (TIME.isDebugEnabled()) {
      long fastDrops = 0L;
      long recoveries = 0L;
      for (HouseholdSatietyBridge.Outcome outcome : report.outcomes()) {
        if (outcome.grainUnmetMilli() > 0L) {
          fastDrops++;
        } else {
          recoveries++;
        }
      }
      TIME.debug(
          LogEvent.of(
              "POPULATION_SATIETY_WRITEBACK",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "mapId",
              mapId,
              "feeds",
              report.outcomes().size(),
              "fastDrops",
              fastDrops,
              "recoveries",
              recoveries,
              "skippedUnknownHouseholds",
              report.skippedUnknownHouseholds(),
              "householdsWithSatietyEntry",
              report.data().satietyPerMille().size()));
    }
    return report.data();
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：Social 逐户展开当日劳动预算</b>（毫小时/tick；只读投影）—— 唯一实现 = {@link
   * SocialData#householdLaborMilli(HouseholdId, long, CalendarClock)}（逐成员份额 × provisioning
   * 劳动系数，家户覆盖优先、全局默认兜底）。经济侧只收结果，不再自己查表。
   */
  private Map<HouseholdId, Long> laborBudgetsOf(SocialData social, long day) {
    CalendarClock clock = CalendarClock.julianDefault();
    Map<HouseholdId, Long> budgets = new LinkedHashMap<>();
    long totalLaborMilli = 0L;
    for (HouseholdId household : social.households().keySet()) {
      long budget = social.householdLaborMilli(household, day, clock);
      budgets.put(household, budget);
      totalLaborMilli = Math.addExact(totalLaborMilli, budget);
    }
    if (TIME.isDebugEnabled()) {
      TIME.debug(
          LogEvent.of(
              "POPULATION_LABOR_BUDGETS_EXPANDED",
              AppLogSource.HOUSEHOLD_SYNC,
              "day",
              day,
              "mapId",
              mapId,
              "households",
              budgets.size(),
              "totalLaborMilli",
              totalLaborMilli));
    }
    return budgets;
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：Social 逐户展开当日逐商品自然需求</b>（毫单位/日；只读投影）—— 唯一实现 = {@link
   * SocialData#householdNaturalNeeds(HouseholdId, long, CalendarClock)}（逐成员求和、粮 120 天家户层一次取整、布历年
   * {@code YearFraction}）；经济侧收到后由 {@link EconomyDayStepper#updateNaturalNeeds(Map)} 原样注入。
   *
   * <p>★ 空家户返回空需求（合法的"没有人"）；坏数据（家户/批次缺失、口径冲突、系数查不到）由 Social 侧具名拒并带 ERROR 日志。
   */
  private Map<HouseholdId, Map<CommodityId, Long>> naturalNeedsOf(SocialData social, long day) {
    CalendarClock clock = CalendarClock.julianDefault();
    Map<HouseholdId, Map<CommodityId, Long>> needsByHousehold = new LinkedHashMap<>();
    long totalNeedsMilli = 0L;
    long totalGrainMilli = 0L;
    long totalClothMilli = 0L;
    for (HouseholdId household : social.households().keySet()) {
      Map<CommodityId, Long> needs = social.householdNaturalNeeds(household, day, clock);
      needsByHousehold.put(household, needs);
      for (Map.Entry<CommodityId, Long> need : needs.entrySet()) {
        totalNeedsMilli = Math.addExact(totalNeedsMilli, need.getValue());
        if (EconomySettlement.GRAIN.equals(need.getKey())) {
          totalGrainMilli = Math.addExact(totalGrainMilli, need.getValue());
        }
        if (EconomySettlement.CLOTH.equals(need.getKey())) {
          totalClothMilli = Math.addExact(totalClothMilli, need.getValue());
        }
      }
    }
    if (TIME.isDebugEnabled()) {
      TIME.debug(
          LogEvent.of(
              "POPULATION_NATURAL_NEEDS_EXPANDED",
              AppLogSource.HOUSEHOLD_SYNC,
              "day",
              day,
              "mapId",
              mapId,
              "households",
              needsByHousehold.size(),
              "totalNeedsMilli",
              totalNeedsMilli,
              "grainMilli",
              totalGrainMilli,
              "clothMilli",
              totalClothMilli));
    }
    return needsByHousehold;
  }
}
