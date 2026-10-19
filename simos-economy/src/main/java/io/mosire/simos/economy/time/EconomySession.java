package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>一次推进会话（revision 级）</b>：持有 {@link EconomyStateBuilder}（可变工作表）与本期流水累加器， 只在 {@link #build()}
 * 构造一次完整 {@link EconomyData} —— 这就是 P1.5 的"日结算持有可变工作表、 revision 边界构造一次"。
 *
 * <p>★ <b>与 {@code AccountSession} 的分工</b>：{@code AccountSession} 是<b>账户</b>会话（家户/经营者的 商品/货币/冻结，键 =
 * {@code (ActorRef, HexCoord)}，不进状态树）；本类是<b>领域状态</b>会话（产业/行/债务/… 的工作副本，最终进变更集）。两者同生命周期但职责不同。
 *
 * <p>★★ <b>归属（R1 的硬口径）</b>：本对象与其 {@link EconomyStateBuilder} 工作表都是<b>协调器单线程</b>的可变状态 ——
 * 只能由创建它的那一次推进任务访问；并行 worker 只允许读 {@link AccountSnapshot} 与写自己的 {@link
 * AccountIntentBuffer}，<b>不得</b>持有本对象。
 *
 * <p>★ <b>单线程、用完即弃</b>；{@link #base()} 永不被修改。
 */
public final class EconomySession {

  private final EconomyData base;
  private final EconomyStateBuilder sheet;
  private final LinkedHashMap<HouseholdId, FlowRow> flows;

  /**
   * ★★ <b>D-032 U2：本会话的生产运行时只读投影</b>（可能为 null；见双参构造器）。
   *
   * <p>★ 生命周期 = 本会话：由组合根在**每次 Advance** 起点新建一个引擎实例并交进来（不得跨 Advance 复用； 那正是 I13"不得跨 revision
   * 陈旧"的机读形态）。{@code sheet} 与它共用同一个引用。
   */
  private final io.mosire.simos.production.contract.ProductionRuntime runtime;

  /**
   * ★★★ <b>D-032 D4-C / D2 §8.4：本会话的资产占用唯一只读读口</b>。组合根未装配 D4-I 时必须注入 {@link
   * UnavailableAssetOccupancyLookup}；名册执行路径读到 {@code Optional.empty()} ⇒ 具名 {@code
   * asset-occupancy-unavailable}，<b>不得</b>回退整池份额扫描。
   */
  private final AssetOccupancyLookup assetOccupancyLookup;

  /** ★★★ D-032 D2 §6.2/§7.3：参与户外部属性事实的唯一读口。 */
  private final ParticipantAttributeLookup participantAttributeLookup;

  /**
   * ★★★ D-032 D4-C：本次 Advance 的权威 revision（资产占用快照必须与它同版本）。 组合根未提供 ⇒ {@link
   * java.util.Optional#empty()} ⇒ 名册执行路径具名 {@code asset-occupancy-unavailable}。
   */
  private final java.util.Optional<io.mosire.simos.util.state.RevisionId> expectedRevision;

  /**
   * ★★ <b>P5：本会话的死亡按人口比例删债累加器</b>（瞬态；键 = 债务人，值 = Σ 逐笔 {@code forgive} 的本金差）。
   *
   * <p>★★ <b>为什么不进 {@link EconomyData}/变更集/{@code Codec}</b>：删债不是新的持久组件 —— 合同表里该条的 {@code
   * principal} 已经减少、本金为 0 的条处于 {@code FORGIVEN/SETTLED}，这就是权威事实；本表只是<b>本会话发生额</b> 的进程内累加器，供
   * P9/协调器核对守恒式 {@code 债务 = 发行 − 还款 − 删债（利息另列）}。
   */
  private final LinkedHashMap<HouseholdId, Long> debtWriteOffs = new LinkedHashMap<>();

  /**
   * ★★ <b>P0（2026-10-10）：本会话的迁移人口 outbox</b>（瞬态；保序 = move 执行序）。
   *
   * <p>★★ <b>为什么不进 {@link EconomyData}/变更集/{@code Codec}</b>：人口权威在 Social； {@code
   * ModeMigrationSettlement} 只把"这一天经济腿搬了多少人、从谁到谁"记在这里，由 {@code simos-app} 的 人口—经济协调器在 {@code
   * stepper.step(day)} 之后取走、翻译成 Social 工单，再把 Social 真值 delta 写回经济行。 与 {@link #debtWriteOffs()}
   * 同制：进程内本会话发生额，用完即弃；重启后由同一 plan 重放产生同一批条目。
   */
  private final List<EconomyPopulationTransfer> populationTransfers = new ArrayList<>();

  /**
   * ★★★ <b>W3（2026-10-11）：本会话的逐户缺口 outbox</b>（瞬态；保序 = 家户处理序）。
   *
   * <p>★★ <b>为什么不进 {@link EconomyData}/变更集/{@code Codec}</b>：人口与死亡率的权威在 Social（AGENTS §〇 模块表）；
   * {@code EconomySettlement} 只把"这一天经济腿看见的逐户缺口（生产花费未满足量 + 口粮缺口那一支）"记在这里，由 {@code simos-app} 的组合根在
   * {@code stepper.step(day)} 之后取走、翻成 Social 的饥荒输入（S-11 第②半）。 与 {@link #populationTransfers}
   * 同制：进程内本会话发生额，用完即弃；逐日重建 ⇒ 不引入跨日/跨 advance 记忆（B2 分段等价的硬前提）。
   */
  private final List<EconomyHouseholdGap> householdGaps = new ArrayList<>();

  /**
   * ★★★ <b>B1（D-032 §4.3）：本 tick"现扣周期投入"的实扣量（键 = 生产实例）</b>。
   *
   * <p>★★ <b>为什么它是瞬态而不是状态</b>：它是"这一次日结算里已经扣到多少料"的<b>过程读数</b>，一个生产日 过完就没有意义 —— 周期投入的<b>权威</b>在
   * production 的 {@code ProductionProcess.cycleInputUsedMilli()} （由 {@code
   * ProductionRuntime#executeDay} 的 {@code finalInputsMilli} 写）。⇒ 它<b>不进</b> {@link
   * io.mosire.simos.economy.EconomyData}、不进变更集、不进 codec，且每 tick 开头清零 （见 {@link
   * #clearDrawnProductionInputs()}）。
   *
   * <p>★★ <b>为什么必须由会话持有它</b>：现扣发生在<b>并行分区</b>里（每分区一份实例副本），而把结果交给 production
   * 必须在<b>协调器线程</b>上做（引擎单线程独占）；会话正是那个"协调器持有、worker 不碰"的落点 （与 {@link #populationTransfers} / {@link
   * #householdGaps} 同制）。
   */
  private final Map<
          io.mosire.simos.economy.api.id.ProductionUnitId,
          Map<io.mosire.simos.economy.api.id.CommodityId, Long>>
      drawnProductionInputs = new LinkedHashMap<>();

  /** ★ B1：清空本 tick 的现扣读数（每个生产日开头调一次 —— 不跨日累计，权威在 production）。 */
  public void clearDrawnProductionInputs() {
    drawnProductionInputs.clear();
  }

  /** ★ B1：记一条"这个实例本 tick 实扣到了这些料"（协调器线程；覆盖写 = 同一实例只留最后一份累计量）。 */
  public void recordDrawnProductionInput(
      io.mosire.simos.economy.api.id.ProductionUnitId unit,
      Map<io.mosire.simos.economy.api.id.CommodityId, Long> drawn) {
    java.util.Objects.requireNonNull(unit, "unit");
    java.util.Objects.requireNonNull(drawn, "drawn");
    if (drawn.isEmpty()) {
      drawnProductionInputs.remove(unit);
      return;
    }
    drawnProductionInputs.put(unit, new LinkedHashMap<>(drawn));
  }

  /** ★ B1：本 tick 某实例实扣到的料（缺 ⇒ 空表 = 这个实例本 tick 一根料都没扣到）。 */
  public Map<io.mosire.simos.economy.api.id.CommodityId, Long> drawnProductionInputsOf(
      io.mosire.simos.economy.api.id.ProductionUnitId unit) {
    return drawnProductionInputs.getOrDefault(unit, Map.of());
  }

  // ★★ D-032（D2 直接删）：本会话原有的 `productionModifiers`（逐 tick 生产效率修正注入集）与
  //   `productionModifiersView()/replaceProductionModifiers()/clearProductionModifiers()` 已随
  //   "文化 → 生产效率倍率"整条通路退役（用户 U5「文化直接决定生产效率太不唯物主义了」）。
  //   ★ 新口径：效率 = 家户熟练度经方案公式解释出的倍率，归 production（`ProductionExperienceUse` +
  //   `ProductionEfficiency`），由 `production.contract.ProductionRuntime` 的试算/执行面提供；
  //   economy 不再持有任何可注入的效率修正集。

  // ★★★ D-032 B3（§13.13.7 第 8 条 / §13.3 第 2 条）：`EconomySession(EconomyData)` 与
  //   `EconomySession(EconomyData, ProductionDefinitionLookup)` 两个 **runtime = null** 的旧构造器已**删除**
  //   —— 它们只服务 `EconomySettlement.settle` / `applyPopulationChange` 两条"没有组合根、没有 production 切片"
  //   的静态单模块入口，而那两条入口本身也随本批删除（main 里 0 个调用点）。"没有运行时"不再是合法装配形态。
  //   ★ 定义来源也不再从 `EconomyBaseDefinitions`（读 `EconomyData.industries`）现取：唯一装配口是
  //     下面这个三参构造器（显式 definitions + 显式 runtime）。

  /**
   * ★★★ <b>D-032 U2 的装配口：把生产运行时的只读投影线程进本会话</b>。
   *
   * <p>★★ <b>为什么必须有它</b>：生产实例（进度 / 周期累计劳动 / 周期累计投入）的唯一权威在 production（{@code ProductionProcess} 的类注
   * / I1），而日结算的多个阶段（组织、分配、市场、债务容量、 迁移、收获）都要读"这一格有谁在产、经营者是谁、进度到哪"。那份读数只能来自<b>本会话的</b> {@link
   * io.mosire.simos.production.contract.ProductionRuntime}（I13：同次 Advance 的后续阶段看本次最新候选态， 不得跨
   * revision 陈旧）。
   *
   * @param runtime 本会话的生产运行时只读投影；可为 null（= 本会话不读/不写生产实例，任何实例读 fail-closed 抛）
   */
  public EconomySession(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime) {
    this(
        base,
        definitions,
        runtime,
        new UnavailableAssetOccupancyLookup(),
        new UnavailableParticipantAttributeLookup());
  }

  /**
   * ★★★ <b>D-032 C2 的装配口</b>：除定义来源与运行时外，再注入 D4-C 资产占用读口与属性桥。 两个 lookup 都必须显式给出（缺真实实现时用对应的 {@code
   * Unavailable*} fail-closed 适配器，不得传 null）。
   */
  public EconomySession(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      AssetOccupancyLookup assetOccupancyLookup,
      ParticipantAttributeLookup participantAttributeLookup) {
    this(base, definitions, runtime, assetOccupancyLookup, participantAttributeLookup, null);
  }

  /**
   * ★★★ <b>D-032 C2 全参装配口（含权威 revision）</b>：资产占用快照要求调用方给出 expectedRevision；未提供 ⇒ 名册执行路径 fail-closed
   * `asset-occupancy-unavailable`（不猜版本、不跨 revision 读）。
   */
  public EconomySession(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      AssetOccupancyLookup assetOccupancyLookup,
      ParticipantAttributeLookup participantAttributeLookup,
      io.mosire.simos.util.state.RevisionId expectedRevision) {
    this.base = Objects.requireNonNull(base, "base");
    // ★★ B3：runtime 必须非 null（§13.3 第 2 条 —— 所有支持生产的有效入口显式获得合法 runtime）。
    this.runtime =
        Objects.requireNonNull(runtime, "runtime（D-032：生产实例的唯一权威在 production，必须显式注入合法运行时）");
    this.assetOccupancyLookup =
        Objects.requireNonNull(
            assetOccupancyLookup, "assetOccupancyLookup（D4-C 读口，缺实现用 Unavailable*）");
    this.participantAttributeLookup =
        Objects.requireNonNull(
            participantAttributeLookup, "participantAttributeLookup（属性桥，缺实现用 Unavailable*）");
    this.expectedRevision = java.util.Optional.ofNullable(expectedRevision);
    this.sheet = new EconomyStateBuilder(base, definitions, runtime);
    this.flows = new LinkedHashMap<>(base.flows());
  }

  /**
   * ★★ <b>本会话的生产运行时只读投影</b>（D-032 U2；B3 起<b>恒非 null</b> —— 见构造器）。
   *
   * <p>★ 它是 economy 侧读生产实例的<b>唯一</b>入口（{@code EconomyData.units()} 是阶段 2 的临时兼容组件）。
   *
   * <p>★★ <b>B12 登记：EI_EXPOSE_REP 是孤立例外，不是缺陷</b>（§14.25.3 裁定四十五第 3 条）。它不是"漏了防御性拷贝"：
   *
   * <ul>
   *   <li><b>为什么不是缺陷</b>：{@code ProductionRuntime} 是组合根注入的<b>契约端口接口</b>（economy 调 production 的
   *       唯一通道，见 I13）—— 它没有拷贝契约；拷贝/包装出一个"防御副本"就不再是同一份权威候选态，{@code beginTick} / {@code changeSet()}
   *       的 tick 身份与本次推进的候选态会当场对不上。
   *   <li><b>调用面</b>：economy 域内（{@code EconomyDayStepper} / {@code EconomyStateBuilder} / 各结算阶段）只读；
   *       组合根在推进收尾调 {@code changeSet()} 汇入同一 revision。没有第三方可写入口（接口本身只读优先、变更经引擎）。
   *   <li><b>依据</b>：§14.25.3 裁定四十五第 3 条；同文件 {@code flows()} 之上的既有同类登记（会话内就地更新的视图）。
   * </ul>
   *
   * ⇒ 用下面的**具名** {@code @SuppressFBWarnings} 登记（不是静默排除、不是排除清单）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "孤立例外（§14.25.3 裁定四十五第 3 条）：返回的是组合根注入的契约端口接口 ProductionRuntime —— economy 读/写生产候选态的唯一通道；接口没有拷贝契约，拷贝掉就不再是同一份权威候选态（beginTick()/changeSet() 的 tick 身份与候选态会失配），调用面限于 economy 域内与组合根收尾。登记见 impl-ledger B12")
  public io.mosire.simos.production.contract.ProductionRuntime productionRuntime() {
    return runtime;
  }

  /** ★★★ D-032 D4-C：本会话的资产占用只读读口（empty = 不可用，调用方必须具名拒绝）。 */
  public AssetOccupancyLookup assetOccupancyLookup() {
    return assetOccupancyLookup;
  }

  /** ★★★ D-032 D2 §6.2/§7.3：参与户外部属性事实读口。 */
  public ParticipantAttributeLookup participantAttributeLookup() {
    return participantAttributeLookup;
  }

  /** ★★★ D-032 D4-C：本次 Advance 的权威 revision（资产占用快照必须与它同版本）。 */
  public java.util.Optional<io.mosire.simos.util.state.RevisionId> expectedRevision() {
    return expectedRevision;
  }

  public EconomyData base() {
    return base;
  }

  /** ★★ 本会话按需创建、尚未登记的产业定义（瞬态候选态；见 {@link EconomyStateBuilder#createdIndustryDefinitions()}）。 */
  public Map<
          io.mosire.simos.economy.api.id.IndustryId,
          io.mosire.simos.production.contract.IndustryDefinitionView>
      createdIndustryDefinitions() {
    return sheet.createdIndustryDefinitions();
  }

  public EconomyStateBuilder sheet() {
    return sheet;
  }

  /** 本期流水累加器（**就地更新**；一次推进会话内跨日累计，{@link #build()} 时挂上）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "flows() 是会话内就地更新的流水累加器（类注：协调器单线程、用完即弃），返回拷贝会切断\"跨日累计\"语义；只读出口是 flowsView()")
  public LinkedHashMap<HouseholdId, FlowRow> flows() {
    return flows;
  }

  /** ★★ <b>唯一一次构造</b>（全量守卫在此照跑一次，见 {@link EconomyStateBuilder#build}）。 */
  public EconomyData build() {
    return sheet.build(flows);
  }

  /**
   * ★ <b>当刻预览</b>（会跑全量守卫、构造一个新 {@code EconomyData}）—— 只服务"读口/测试要看当前值"； 日循环内部**不要**每天调它（那会把 P1.5
   * 的收益抵消）。
   */
  public EconomyData preview() {
    return build();
  }

  /** 只读视图（防调用方替换流水表）。 */
  public Map<HouseholdId, FlowRow> flowsView() {
    return java.util.Collections.unmodifiableMap(flows);
  }

  /**
   * ★ P5：删债累加器的**可变视图**（包内写口：只服务 {@code EconomySettlement.applyPopulationChangeInto}）。
   *
   * <p>★ 不对外公开：公开的是 {@link #debtWriteOffsView()}（只读，{@link EconomyDayStepper} 转发给协调器/P9）。
   */
  LinkedHashMap<HouseholdId, Long> debtWriteOffs() {
    return debtWriteOffs;
  }

  /** ★ P5：删债累加器的**只读视图**（键序 = 首次发生序；值 = 该家户累计删债本金）。 */
  public Map<HouseholdId, Long> debtWriteOffsView() {
    return java.util.Collections.unmodifiableMap(debtWriteOffs);
  }

  /** ★★ P0：记一条迁移人口事实（非 null；保序追加）。 */
  public void recordPopulationTransfer(EconomyPopulationTransfer transfer) {
    populationTransfers.add(Objects.requireNonNull(transfer, "transfer"));
  }

  /** ★★ P0：本次推进已记但尚未被 App 取走的迁移人口 outbox（只读视图，保序 = 执行序）。 */
  public List<EconomyPopulationTransfer> pendingPopulationTransfers() {
    return java.util.Collections.unmodifiableList(populationTransfers);
  }

  /**
   * ★★ P0：App 每日取走并清空 outbox（返回保序快照）。
   *
   * <p>★ 空 ⇒ {@link List#of()}（共享空单例）；非空 ⇒ {@link List#copyOf} 的不可变快照，之后本会话记录清零， 保证"同一条事实只被翻译一次"。
   */
  public List<EconomyPopulationTransfer> drainPendingPopulationTransfers() {
    if (populationTransfers.isEmpty()) {
      return List.of();
    }
    List<EconomyPopulationTransfer> drained = List.copyOf(populationTransfers);
    populationTransfers.clear();
    return drained;
  }

  /** ★★ W3：记一条逐户缺口事实（非 null；保序追加）。 */
  public void recordHouseholdGap(EconomyHouseholdGap gap) {
    householdGaps.add(Objects.requireNonNull(gap, "gap"));
  }

  /** ★★ W3：本次推进已记但尚未被 App 取走的逐户缺口 outbox（只读视图，保序 = 家户处理序）。 */
  public List<EconomyHouseholdGap> pendingHouseholdGaps() {
    return java.util.Collections.unmodifiableList(householdGaps);
  }

  /**
   * ★★ W3：App 每日取走并清空缺口 outbox（返回保序快照）。
   *
   * <p>★ 空 ⇒ {@link List#of()}（共享空单例）；非空 ⇒ {@link List#copyOf} 的不可变快照，之后本会话记录清零， 保证"同一条事实只被翻译一次"。
   */
  public List<EconomyHouseholdGap> drainPendingHouseholdGaps() {
    if (householdGaps.isEmpty()) {
      return List.of();
    }
    List<EconomyHouseholdGap> drained = List.copyOf(householdGaps);
    householdGaps.clear();
    return drained;
  }
}
