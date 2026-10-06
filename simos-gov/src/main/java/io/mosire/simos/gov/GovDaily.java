package io.mosire.simos.gov;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * 每日行政结算纯函数（阶段 11a，计划 §2.2 / §3）：对一个 {@link GovState} 里的每个 GOV 编制算需求/效率、评估并支付当日行政定额、发出三类缺口信号， 返回新
 * {@link GovState} 与当日账（{@link UpkeepDue} / {@link SignalDraft}）。
 *
 * <p>★★ <b>边界与裁定</b>：
 *
 * <ul>
 *   <li><b>行政力与战力分开算</b>（用户裁定 1/2）：本类只读 {@code Unit.module} 里的 {@link
 *       GovernmentFormation}（编制/政策），不产出任何战力； 军队 {@code ArmyFormation} 不参与本结算；
 *   <li><b>只发信号，不扣市场</b>（用户裁定 5/6）：缺口只写 {@link SignalDraft} 与 {@code lastShortfall*}
 *       读数，<b>不</b>自动去市场拿粮、 <b>不</b>自动裁人、<b>不</b>自动加税；决策人自己决定下一步；
 *   <li><b>付款回调</b>：{@link PaymentOracle} 由调用方（11b 的 participant）装配，本模块不依赖 economy/actor
 *       主模块，也不认识任何国库/账本 API；
 *   <li><b>确定性</b>：offices 按 {@link UnitId#value()} 升序遍历；每 office 的资源顺序固定为 grain → cloth →
 *       money；信号顺序固定为 supply → security → paperwork；oracle 调用次序就是资源的评估顺序（0 需求不发、不跳号）。同一输入（oracle
 *       纯函数）⇒ 输出逐字段相等。
 * </ul>
 *
 * <p>★★ <b>状态损坏不静默</b>：office 的 {@code unitId} 在 {@code units} 里查无、或该单位没有 {@link
 * GovernmentFormation}，一律当场抛 {@link IllegalStateException}——这是装配/数据故障，不能"跳过这个 office"把损坏藏起来。
 *
 * <p>★★ <b>无有效位置</b>（{@code units.effectivePosition(...)} 为空）：该 office 本日<b>跳过 upkeep 与全部信号</b>，只把
 * efficiency 四个 per-mille 读数与 {@code tick} 更新到新读数上；{@code assessed/paid/shortfall} 六表清空（<b>空表不是"评估为
 * 0"</b>， 而是"本日没有结算事实"），{@code dues} 也为空。<b>本批不发明 {@code ADMIN_NO_SEAT} 之类的
 * kind</b>——"没有座位"由调用方读日志/证据裁定如何处理。
 *
 * <p>★★ <b>布料折算口径</b>：{@code clothNeed = totalStaff × floor(policy.clothPerStaffPerCycle /
 * daysInYearAtSettlement)}。<b>不足一昼夜的余量不跨日累计</b>（本批口径；逐日 floor 的残差丢掉，不建"周期余额"账本）。年长由调用方按 {@code
 * CalendarClock.daysInYearAtTick(该日 tick)} 传入（365 或 366）；gov 侧不依赖 calendar 模块、不自造第二个"一年多少天"。
 *
 * <p>★★ <b>{@link SignalDraft} 的口径</b>：
 *
 * <ul>
 *   <li>三类 kind 用字符串：{@link #KIND_ADMIN_SUPPLY} / {@link #KIND_ADMIN_SECURITY} / {@link
 *       #KIND_ADMIN_PAPERWORK}（11b 折成 {@code HexCrisisSignal.Kind} 时按名映射）；
 *   <li>本批 severity <b>一律 1</b>：只表示"出现了这类缺口"这一事实，程度由 {@code evidence} 的原始量承载（三类资源的量纲不同， 本批不造
 *       "多少毫算多严重"的伪统一分级）；
 *   <li>{@code ADMIN_SUPPLY} <b>每 office 每天至多一条</b>，evidence = {@code {assessed, paid, shortfall}}
 *       三资源合计， reason 里列逐资源明细。为什么不按资源各发一条：11b 的六角危机信号身份是 {@code (hex, kind)}，同格同 kind 只保留一条， 三条
 *       {@code ADMIN_SUPPLY} 叠在同格会互相覆盖、丢掉两类缺口——聚合一条是唯一不丢信息的形状；逐资源事实仍完整保留在 {@link UpkeepDue} 与
 *       {@code GovOfficeState} 六表里；
 *   <li>{@code ADMIN_SECURITY}/{@code ADMIN_PAPERWORK}：coverage &lt; 1000‰ 时各发一条，evidence 带 {@code
 *       coveragePerMille/supply/demand}；
 *   <li>所有信号 hex = 该 GOV 单位的有效位置。
 * </ul>
 *
 * <p>★ <b>六表读数的键</b>：商品侧 grain / cloth，货币侧 silver（id 分别取 {@code EconomyVocabulary} 与 {@code
 * MoneyVocabulary} 的 唯一拼写点）；即使某资源当日评估为 0，也保留 {@code {资源: 0}} 的评估事实（"评估过且为 0"与"没评估"不同）。
 *
 * <p>★ <b>纯函数</b>：不写任何输入状态；所有输出都是新建的保序不可变值（{@link Outcome} / {@link UpkeepDue} / {@link
 * SignalDraft}）。
 */
public final class GovDaily {

  private static final Logger LOG = GovLog.daily();

  private static final Logger TRACE = GovLog.trace();

  /** 行政物资/俸禄缺口的信号 kind（字符串；11b 按名折成 {@code HexCrisisSignal.Kind.ADMIN_SUPPLY}）。 */
  public static final String KIND_ADMIN_SUPPLY = "ADMIN_SUPPLY";

  /** 治安覆盖不足的信号 kind（字符串；11b 按名折成 {@code HexCrisisSignal.Kind.ADMIN_SECURITY}）。 */
  public static final String KIND_ADMIN_SECURITY = "ADMIN_SECURITY";

  /** 文书覆盖不足的信号 kind（字符串；11b 按名折成 {@code HexCrisisSignal.Kind.ADMIN_PAPERWORK}）。 */
  public static final String KIND_ADMIN_PAPERWORK = "ADMIN_PAPERWORK";

  /** 商品侧两个稳定 id（唯一拼写点在 util 词表）。 */
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);

  private static final CommodityId CLOTH = CommodityId.parse(EconomyVocabulary.CLOTH_COMMODITY_ID);

  /** 货币侧唯一稳定 id（唯一拼写点在 economy-api 词表）。 */
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  /** 三类资源的资源值（记录不可变；复用同一实例，避免逐次 new）。 */
  private static final GovResource GRAIN_RESOURCE = new Commodity(GRAIN);

  private static final GovResource CLOTH_RESOURCE = new Commodity(CLOTH);

  private static final GovResource MONEY_RESOURCE = new Money(SILVER);

  private GovDaily() {}

  /**
   * 结算一个 tick（日）。
   *
   * @param govState 行政读数状态（键 = 单位 id）；不得为 null
   * @param units 单位状态（读编制与有效位置）；不得为 null
   * @param map 地图（{@link GovDemand} 查 Region）；不得为 null
   * @param social 社会数据（人口/城市）；不得为 null
   * @param tick 本日 tick（≥ 0）；写进新读数
   * @param daysInYearAtSettlement 本日所在历法年的天数（只接受 365 或 366；调用方按 {@code
   *     CalendarClock.daysInYearAtTick(该日 tick)} 传入）
   * @param oracle 付款回调（{@code requested > 0} 才会被调）；不得为 null
   * @return 新状态 + 当日 dues/signals + {@code changed}（新状态是否与旧状态不等）
   */
  public static Outcome settle(
      GovState govState,
      UnitState units,
      GameMap map,
      SocialData social,
      long tick,
      long daysInYearAtSettlement,
      PaymentOracle oracle) {
    requireNonNull(govState, "govState");
    requireNonNull(units, "units");
    requireNonNull(map, "map");
    requireNonNull(social, "social");
    requireNonNull(oracle, "oracle");
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
    }
    if (daysInYearAtSettlement != 365L && daysInYearAtSettlement != 366L) {
      throw new IllegalArgumentException(
          "daysInYearAtSettlement 只接受 365 或 366（拒绝臆造年长）: " + daysInYearAtSettlement);
    }

    LOG.debug(
        "event=GOV_DAILY_START tick={} offices={} daysInYear={}",
        tick,
        govState.offices().size(),
        daysInYearAtSettlement);
    // ★ 返回的 offices 保持输入键序（未被覆盖的部分"原样"），但**处理顺序**按下而排序 ⇒ dues/signals/oracle 调用次序确定。
    Map<UnitId, GovOfficeState> nextOffices = new LinkedHashMap<>(govState.offices());
    List<UpkeepDue> dues = new ArrayList<>();
    List<SignalDraft> signals = new ArrayList<>();
    List<Map.Entry<UnitId, GovOfficeState>> ordered =
        new ArrayList<>(govState.offices().entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));

    for (Map.Entry<UnitId, GovOfficeState> entry : ordered) {
      UnitId unitId = entry.getKey();
      Unit unit = units.units().get(unitId);
      if (unit == null) {
        throw new IllegalStateException("gov offices 引用了不存在的单位（状态损坏）: " + unitId);
      }
      UnitModule module = unit.module().orElse(null);
      if (!(module instanceof GovernmentFormation governmentFormation)) {
        throw new IllegalStateException("gov offices 的单位缺少 GovernmentFormation（状态损坏）: " + unitId);
      }

      Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);
      GovEfficiency.Efficiency efficiency = GovEfficiency.of(governmentFormation, demand);

      Optional<HexCoord> seat = units.effectivePosition(unitId, SimosTimestamp.of(tick));
      if (seat.isEmpty()) {
        // ★ 无有效位置：只更新 efficiency 读数；不评估、不支付、不发信号（六表清空 = 本日无结算事实）。
        nextOffices.put(unitId, noSeat(unitId, tick, efficiency));
        continue;
      }
      HexCoord at = seat.get();

      // ---- 评估与支付（顺序固定 grain → cloth → money；0 需求不发）----
      OfficePolicy policy = governmentFormation.policy();
      long totalStaff = totalStaff(governmentFormation);
      long grainNeed = totalStaff * policy.grainPerStaffPerTick();
      long clothNeed =
          totalStaff * Math.floorDiv(policy.clothPerStaffPerCycle(), daysInYearAtSettlement);
      long moneyNeed = totalStaff * policy.moneyPerStaffPerTick();

      long grainPaid = pay(oracle, unitId, at, GRAIN_RESOURCE, grainNeed);
      long clothPaid = pay(oracle, unitId, at, CLOTH_RESOURCE, clothNeed);
      long moneyPaid = pay(oracle, unitId, at, MONEY_RESOURCE, moneyNeed);
      long grainShortfall = grainNeed - grainPaid;
      long clothShortfall = clothNeed - clothPaid;
      long moneyShortfall = moneyNeed - moneyPaid;

      dues.add(new UpkeepDue(unitId, at, GRAIN_RESOURCE, grainNeed, grainPaid, grainShortfall));
      dues.add(new UpkeepDue(unitId, at, CLOTH_RESOURCE, clothNeed, clothPaid, clothShortfall));
      dues.add(new UpkeepDue(unitId, at, MONEY_RESOURCE, moneyNeed, moneyPaid, moneyShortfall));

      // ---- 信号（顺序固定 supply → security → paperwork）----
      long assessedTotal = grainNeed + clothNeed + moneyNeed;
      long paidTotal = grainPaid + clothPaid + moneyPaid;
      long shortfallTotal = grainShortfall + clothShortfall + moneyShortfall;
      if (shortfallTotal > 0L) {
        signals.add(
            new SignalDraft(
                at,
                KIND_ADMIN_SUPPLY,
                1L,
                supplyEvidence(assessedTotal, paidTotal, shortfallTotal),
                supplyReason(
                    grainNeed,
                    grainPaid,
                    grainShortfall,
                    clothNeed,
                    clothPaid,
                    clothShortfall,
                    moneyNeed,
                    moneyPaid,
                    moneyShortfall)));
      }

      long securitySupply = GovEfficiency.securitySupply(governmentFormation);
      long paperworkSupply = GovEfficiency.paperworkSupply(governmentFormation);
      long securityDemand = GovEfficiency.securityDemand(demand);
      long paperworkDemand = GovEfficiency.paperworkDemand(demand);
      if (efficiency.securityCoveragePerMille() < GovRules.COVERAGE_FULL_PER_MILLE) {
        signals.add(
            new SignalDraft(
                at,
                KIND_ADMIN_SECURITY,
                1L,
                coverageEvidence(
                    efficiency.securityCoveragePerMille(), securitySupply, securityDemand),
                coverageReason(
                    "治安", efficiency.securityCoveragePerMille(), securitySupply, securityDemand)));
      }
      if (efficiency.paperworkCoveragePerMille() < GovRules.COVERAGE_FULL_PER_MILLE) {
        signals.add(
            new SignalDraft(
                at,
                KIND_ADMIN_PAPERWORK,
                1L,
                coverageEvidence(
                    efficiency.paperworkCoveragePerMille(), paperworkSupply, paperworkDemand),
                coverageReason(
                    "文书",
                    efficiency.paperworkCoveragePerMille(),
                    paperworkSupply,
                    paperworkDemand)));
      }

      // ---- 新读数：六表用当日评估/实付/缺口逐值填；四个 per-mille 填 eff；tick 填入参 ----
      nextOffices.put(
          unitId,
          officeState(
              unitId,
              tick,
              grainNeed,
              grainPaid,
              grainShortfall,
              clothNeed,
              clothPaid,
              clothShortfall,
              moneyNeed,
              moneyPaid,
              moneyShortfall,
              efficiency));
    }

    GovState nextState = new GovState(nextOffices);
    LOG.info(
        "event=GOV_DAILY_END tick={} offices={} dues={} signals={} changed={}",
        tick,
        nextOffices.size(),
        dues.size(),
        signals.size(),
        !nextState.equals(govState));
    if (TRACE.isTraceEnabled()) {
      for (UpkeepDue due : dues) {
        if (due.shortfall() > 0L) {
          TRACE.trace(
              "event=GOV_UPKEEP_SHORTFALL unit={} hex={} resource={} assessed={} paid={} shortfall={}",
              due.unitId().value(),
              due.at(),
              due.resource().name(),
              due.assessed(),
              due.paid(),
              due.shortfall());
        }
      }
      for (SignalDraft signal : signals) {
        TRACE.trace(
            "event=GOV_SIGNAL_DRAFT hex={} kind={} severity={}",
            signal.hex(),
            signal.kind(),
            signal.severity());
      }
    }
    return new Outcome(nextState, dues, signals, !nextState.equals(govState));
  }

  /** 无有效位置：六表清空 + efficiency 四读数 + tick（类注具名说明；不产信号、不产 due）。 */
  private static GovOfficeState noSeat(
      UnitId unitId, long tick, GovEfficiency.Efficiency efficiency) {
    return new GovOfficeState(
        unitId,
        tick,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        efficiency.securityCoveragePerMille(),
        efficiency.paperworkCoveragePerMille(),
        efficiency.efficiencyPerMille(),
        efficiency.bonusPerMille());
  }

  /** 有位置：六表逐值填（商品 grain→cloth，货币 silver；0 也保留为"评估事实"）。 */
  private static GovOfficeState officeState(
      UnitId unitId,
      long tick,
      long grainNeed,
      long grainPaid,
      long grainShortfall,
      long clothNeed,
      long clothPaid,
      long clothShortfall,
      long moneyNeed,
      long moneyPaid,
      long moneyShortfall,
      GovEfficiency.Efficiency efficiency) {
    Map<CommodityId, Long> assessedGoods = new LinkedHashMap<>();
    assessedGoods.put(GRAIN, grainNeed);
    assessedGoods.put(CLOTH, clothNeed);
    Map<CommodityId, Long> paidGoods = new LinkedHashMap<>();
    paidGoods.put(GRAIN, grainPaid);
    paidGoods.put(CLOTH, clothPaid);
    Map<CommodityId, Long> shortfallGoods = new LinkedHashMap<>();
    shortfallGoods.put(GRAIN, grainShortfall);
    shortfallGoods.put(CLOTH, clothShortfall);
    Map<CurrencyId, Long> assessedMoney = new LinkedHashMap<>();
    assessedMoney.put(SILVER, moneyNeed);
    Map<CurrencyId, Long> paidMoney = new LinkedHashMap<>();
    paidMoney.put(SILVER, moneyPaid);
    Map<CurrencyId, Long> shortfallMoney = new LinkedHashMap<>();
    shortfallMoney.put(SILVER, moneyShortfall);

    return new GovOfficeState(
        unitId,
        tick,
        assessedGoods,
        paidGoods,
        shortfallGoods,
        assessedMoney,
        paidMoney,
        shortfallMoney,
        efficiency.securityCoveragePerMille(),
        efficiency.paperworkCoveragePerMille(),
        efficiency.efficiencyPerMille(),
        efficiency.bonusPerMille());
  }

  /** 编制总人数（各角色求和；超编时这个数就是"超"的分子来源）。 */
  private static long totalStaff(GovernmentFormation governmentFormation) {
    long total = 0L;
    for (long count : governmentFormation.staff().values()) {
      total += count;
    }
    return total;
  }

  /**
   * 走一次付款回调：{@code requested == 0} ⇒ 不发、返回 0；否则返回额必须 ∈ {@code [0, requested]}，越界当场抛 {@link
   * IllegalArgumentException}（回调实现/装配错了，不静默钳制、不当作 0）。
   */
  private static long pay(
      PaymentOracle oracle, UnitId unitId, HexCoord at, GovResource resource, long requested) {
    if (requested == 0L) {
      return 0L;
    }
    long paid = oracle.pay(unitId, at, resource, requested);
    if (paid < 0L || paid > requested) {
      throw new IllegalArgumentException(
          "PaymentOracle 违反契约：resource="
              + resource.name()
              + " requested="
              + requested
              + " 实付="
              + paid
              + "（必须 ∈ [0, requested]）");
    }
    return paid;
  }

  private static Map<String, Long> supplyEvidence(long assessed, long paid, long shortfall) {
    Map<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("assessed", assessed);
    evidence.put("paid", paid);
    evidence.put("shortfall", shortfall);
    return evidence;
  }

  private static Map<String, Long> coverageEvidence(
      long coveragePerMille, long supply, long demand) {
    Map<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("coveragePerMille", coveragePerMille);
    evidence.put("supply", supply);
    evidence.put("demand", demand);
    return evidence;
  }

  /** 人可读 reason：三资源逐项列明（evidence 只给合计，明细在这里）。 */
  private static String supplyReason(
      long grainNeed,
      long grainPaid,
      long grainShortfall,
      long clothNeed,
      long clothPaid,
      long clothShortfall,
      long moneyNeed,
      long moneyPaid,
      long moneyShortfall) {
    return "行政物资/俸禄缺口（只发信号，不自动扣市场）："
        + GRAIN_RESOURCE.name()
        + " 评估 "
        + grainNeed
        + " 实付 "
        + grainPaid
        + " 缺 "
        + grainShortfall
        + "；"
        + CLOTH_RESOURCE.name()
        + " 评估 "
        + clothNeed
        + " 实付 "
        + clothPaid
        + " 缺 "
        + clothShortfall
        + "；"
        + MONEY_RESOURCE.name()
        + " 评估 "
        + moneyNeed
        + " 实付 "
        + moneyPaid
        + " 缺 "
        + moneyShortfall
        + "（单位：毫）";
  }

  private static String coverageReason(String label, long coverage, long supply, long demand) {
    return label
        + "覆盖率 "
        + coverage
        + "‰ < "
        + GovRules.COVERAGE_FULL_PER_MILLE
        + "‰（供给 "
        + supply
        + "、需求 "
        + demand
        + "；只发信号，不自动扣市场）";
  }

  private static void requireNonNull(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " 不得为 null");
    }
  }

  /**
   * 行政/货币资源：商品（{@link CommodityId}）或货币（{@link CurrencyId}）二选一。sealed 保证 11b 的付款实现 switch 穷尽；两个
   * record 各自持有稳定身份，不用字符串冒充。
   */
  public sealed interface GovResource permits Commodity, Money {

    /** 资源短名（grain/cloth/silver），供信号 reason 与审计读；它不是新身份，就是 id 的裸值。 */
    String name();
  }

  /** 商品资源（粮/布等；数量单位 = 毫）。 */
  public record Commodity(CommodityId commodity) implements GovResource {

    public Commodity {
      if (commodity == null) {
        throw new IllegalArgumentException("commodity 不得为 null");
      }
    }

    @Override
    public String name() {
      return commodity.value();
    }
  }

  /** 货币资源（本批只有银；数量单位 = 毫）。 */
  public record Money(CurrencyId currency) implements GovResource {

    public Money {
      if (currency == null) {
        throw new IllegalArgumentException("currency 不得为 null");
      }
    }

    @Override
    public String name() {
      return currency.value();
    }
  }

  /**
   * 当日一条应付款（评估/实付/缺口三件套；每资源一条，即使缺口为 0 也保留评估事实）。
   *
   * <p>★ <b>构造期校验</b>：引用不得为 null；{@code assessed/paid/shortfall ≥ 0}；{@code paid ≤ assessed} 且
   * {@code shortfall == assessed − paid}（这是账，不是三笔可以各自漂移的读数）。越界当场抛。
   */
  public record UpkeepDue(
      UnitId unitId, HexCoord at, GovResource resource, long assessed, long paid, long shortfall) {

    public UpkeepDue {
      if (unitId == null || at == null || resource == null) {
        throw new IllegalArgumentException("UpkeepDue 的 unitId/at/resource 都不得为 null");
      }
      if (assessed < 0L || paid < 0L || shortfall < 0L) {
        throw new IllegalArgumentException(
            "UpkeepDue 的 assessed/paid/shortfall 都必须 ≥ 0: "
                + assessed
                + "/"
                + paid
                + "/"
                + shortfall);
      }
      if (paid > assessed) {
        throw new IllegalArgumentException(
            "UpkeepDue.paid 不得超过 assessed: paid=" + paid + " assessed=" + assessed);
      }
      if (shortfall != assessed - paid) {
        throw new IllegalArgumentException(
            "UpkeepDue.shortfall 必须等于 assessed − paid: shortfall="
                + shortfall
                + " assessed="
                + assessed
                + " paid="
                + paid);
      }
    }
  }

  /**
   * 当日一条待折入危机信号的草稿（阶段 11a 的 gov 侧形状；11b 按 {@link #kind()} 折成 {@code HexCrisisSignal}）。
   *
   * <p>★ <b>构造期校验</b>：{@code hex}/{@code kind}/{@code reason} 非空且 kind/reason 非空白；{@code severity ≥
   * 1}；{@code evidence} 非 null、键非空白、值非 null，且按 {@code LinkedHashMap} + 赋值处 {@code unmodifiableMap}
   * 保序冻结。 evidence 的值保留原始量语义（本批实际产出的都是非负评估/覆盖量，但本类型不替调用方判正负）。
   */
  public record SignalDraft(
      HexCoord hex, String kind, long severity, Map<String, Long> evidence, String reason) {

    public SignalDraft {
      if (hex == null) {
        throw new IllegalArgumentException("SignalDraft.hex 不得为 null");
      }
      if (kind == null || kind.isBlank()) {
        throw new IllegalArgumentException("SignalDraft.kind 不得为空白");
      }
      if (severity < 1L) {
        throw new IllegalArgumentException("SignalDraft.severity 必须 ≥ 1: " + severity);
      }
      if (evidence == null) {
        throw new IllegalArgumentException("SignalDraft.evidence 不得为 null（没有用空表）");
      }
      Map<String, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<String, Long> entry : evidence.entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank()) {
          throw new IllegalArgumentException("SignalDraft.evidence 的键不得空白");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException("SignalDraft.evidence 的值不得为 null: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      evidence = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（保序）
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("SignalDraft.reason 不得为空白");
      }
    }
  }

  /**
   * 一次日结算的完整输出：新状态 + 按处理序的 dues/signals + {@code changed}（新状态是否与旧状态不等）。
   *
   * <p>★ <b>构造期校验与保序不可变</b>：三个引用都不得为 null；两个 list 逐元素不得为 null，且在赋值处 {@code
   * Collections.unmodifiableList} 冻结。
   */
  public record Outcome(
      GovState next, List<UpkeepDue> dues, List<SignalDraft> signals, boolean changed) {

    public Outcome {
      if (next == null) {
        throw new IllegalArgumentException("Outcome.next 不得为 null");
      }
      if (dues == null) {
        throw new IllegalArgumentException("Outcome.dues 不得为 null（没有用空表）");
      }
      if (signals == null) {
        throw new IllegalArgumentException("Outcome.signals 不得为 null（没有用空表）");
      }
      List<UpkeepDue> duesCopy = new ArrayList<>(dues.size());
      for (UpkeepDue due : dues) {
        if (due == null) {
          throw new IllegalArgumentException("Outcome.dues 不得含 null");
        }
        duesCopy.add(due);
      }
      List<SignalDraft> signalsCopy = new ArrayList<>(signals.size());
      for (SignalDraft signal : signals) {
        if (signal == null) {
          throw new IllegalArgumentException("Outcome.signals 不得含 null");
        }
        signalsCopy.add(signal);
      }
      dues = Collections.unmodifiableList(duesCopy); // ★ 冻在赋值处（保序）
      signals = Collections.unmodifiableList(signalsCopy); // ★ 冻在赋值处（保序）
    }
  }

  /**
   * 付款回调（阶段 11a 的边界对象）：由调用方（11b 的 app participant）执行真正的国库扣款；gov 模块<b>不</b>依赖 economy/actor 主模块。
   *
   * <p>★ <b>契约</b>：返回本次实际支付额，必须 ∈ {@code [0, requested]}（{@link GovDaily#settle} 会校验，越界/负数抛 {@link
   * IllegalArgumentException}；回调自己做不到也可以抛，settle 原样上抛）。★ 本接口不承诺事务性：结算方在一次 {@code settle}
   * 里按固定次序调用，扣款/回滚是调用方（participant）的原子性职责。
   */
  @FunctionalInterface
  public interface PaymentOracle {

    /**
     * 为某单位在某格的某资源付款。
     *
     * @param unitId 付款主体（GOV 单位）
     * @param at 该单位本 tick 的有效位置
     * @param resource 资源（商品或货币）
     * @param requested 本次请求额（&gt; 0；单位 = 毫）
     * @return 实际支付额，必须 ∈ {@code [0, requested]}
     */
    long pay(UnitId unitId, HexCoord at, GovResource resource, long requested);
  }
}
