package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovRules;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GOV 日志验收（计划 L4，判据取 {@code docs/superpowers/plans/2026-10-23-all-module-logging-rollout.md} §3 第 6
 * 条固定字段 / §4.2 级别语义 / §4.4 来源可筛 / §9 第 2/3/5 条）：
 *
 * <ul>
 *   <li>真实 INFO 生命周期：{@code GOV_DAILY_START}/{@code GOV_DAILY_END} 带 {@code origin=gov-daily} +
 *       {@code originKind=tick} + {@code day=<settle 的 tick>} 与关键计数；
 *   <li>开关：gov 根 logger 调到 WARN ⇒ INFO 消失，调回 INFO 恢复；
 *   <li>三档明细：DEBUG（{@code GOV_OFFICE_NO_SEAT}/{@code GOV_OFFICE_UPKEEP_EVALUATED}）与 TRACE（{@code
 *       GOV_UPKEEP_SHORTFALL}/{@code GOV_SIGNAL_DRAFT}）只在对应级别打开且逐笔缺口的路径真被执行时出现。
 * </ul>
 *
 * <p>被验对象是 {@code io.mosire.simos.gov} 门面（{@link GovLog}）下的 {@code .daily}/{@code .trace} logger；
 * 装置住 app 模块，因为只有 app 的测试类路径带 log4j-core + log4j-slf4j2-impl（照 {@code SocialLoggingTest} 的形制）。
 * 触发夹具内联自 {@code simos-gov} 的 {@code GovDailyTest}（app 测试类路径拿不到它的测试作用域私有夹具）。
 *
 * <p>★ <b>具名拒绝缺口（如实登记，不写恒真断言）</b>：gov 模块 {@code src/main} 当前没有 {@code *_REJECTED} 日志事件（{@code git
 * grep -nE 'LogEvent\.of\("[A-Z_]*REJECTED' simos-gov/src/main} = 0 命中）。 GovDaily 的失败语义是裸抛异常
 * （{@code GovDaily.java:139} tick 负数、{@code :142} 年长非 365/366、{@code :169} office 引用缺单位、{@code
 * :173} 缺 GovernmentFormation），没有「具名拒绝 + reason」可断。 因此本测试不为「拒绝」编「空集不含
 * REJECTED」这类恒真断言；缺口写进测试报告上报控制方。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured()} 是前提断言：不挂 appender / 不抬级别时 log4j2 的 root 是 ERROR，空集上
 * 「不含某串」会假绿（core 的 {@code CommandBusLoggingTest} 已记过这个坑）。
 */
class GovLoggingTest {

  private static final String APPENDER_NAME = "gov-logging-capture";
  private static final UnitId U1 = new UnitId("gov-1");
  private static final UnitId U2 = new UnitId("gov-2");
  private static final HexCoord H1 = new HexCoord(1, 1);
  private static final HexCoord H2 = new HexCoord(2, 2);
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig govConfig;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    govConfig = configuration.getLoggerConfig(GovLog.ROOT_LOGGER_NAME);
    originalRootLevel = govConfig.getLevel();
    originalTraceLevel = configuration.getLoggerConfig(GovLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(GovLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(GovLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(GovLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setGovLevel(Level.INFO);
    settleEmptyGov(7L);

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void dailyStartAndEndAreRealInfoEventsWithOriginKindAndTheSettlementDay() {
    setGovLevel(Level.INFO);

    GovDaily.Outcome first = settleEmptyGov(7L);

    assertThat(first.next()).as("空 offices 夹具 ⇒ next 仍是空状态").isEqualTo(GovState.empty());
    assertThat(first.dues()).isEmpty();
    assertThat(first.signals()).isEmpty();
    assertThat(first.changed()).as("GovState.empty() ⇒ 无变化（证明结算路径真被执行）").isFalse();
    assertEvent(
        appender.messages(),
        "INFO",
        "GOV_DAILY_START",
        "origin=gov-daily",
        "originKind=tick",
        "day=7",
        "offices=0",
        "daysInYear=365");
    assertEvent(
        appender.messages(),
        "INFO",
        "GOV_DAILY_END",
        "origin=gov-daily",
        "originKind=tick",
        "day=7",
        "offices=0",
        "dues=0",
        "signals=0",
        "changed=false");

    // ★ §9 第 5 条：TICK 事件的 day 必须来自 settle 入参 —— 换一个 tick 再跑，绝不允许还是 7。
    appender.clear();
    settleEmptyGov(8L);

    assertEvent(
        appender.messages(), "INFO", "GOV_DAILY_START", "originKind=tick", "day=8", "offices=0");
    assertEvent(
        appender.messages(), "INFO", "GOV_DAILY_END", "originKind=tick", "day=8", "offices=0");
    assertThat(appender.messages())
        .as("day 必须跟随 settle 的 tick（硬编码 7 ⇒ 本断言红）；实得 %s", appender.messages())
        .allSatisfy(line -> assertThat(line).doesNotContain("day=7"));
  }

  @Test
  void warnLevelSuppressesInfoAndInfoLevelRestoresIt() {
    setGovLevel(Level.INFO);
    settleEmptyGov(7L);
    assertEvent(appender.messages(), "INFO", "GOV_DAILY_START", "day=7");

    appender.clear();
    setGovLevel(Level.WARN);
    settleEmptyGov(8L);
    assertThat(appender.messages())
        .as("gov 根 logger 调到 WARN 后 INFO 事件必须整体消失；实得 %s", appender.messages())
        .isEmpty();

    appender.clear();
    setGovLevel(Level.INFO);
    settleEmptyGov(9L);
    assertEvent(
        appender.messages(),
        "INFO",
        "GOV_DAILY_START",
        "origin=gov-daily",
        "originKind=tick",
        "day=9");
  }

  @Test
  void debugAndTraceDetailsAppearOnlyWhenTheirLevelsAreOpen() {
    setGovLevel(Level.DEBUG);
    appender.clear();

    GovDaily.Outcome outcome = settleShortfallAndNoSeatFixture(7L);

    assertThat(outcome.dues()).as("夹具必须真产出三资源 due，否则 DEBUG 断言可能只是空跑").hasSize(3);
    assertThat(outcome.signals()).as("夹具必须真产出缺口信号，否则 TRACE 断言可能只是空跑").hasSize(1);
    assertThat(outcome.signals().get(0).kind()).isEqualTo(GovDaily.KIND_ADMIN_SUPPLY);
    assertEvent(
        appender.messages(),
        "DEBUG",
        "GOV_OFFICE_NO_SEAT",
        "origin=gov-daily",
        "originKind=tick",
        "day=7",
        "unit=gov-2",
        "reason=no-effective-position");
    assertEvent(
        appender.messages(),
        "DEBUG",
        "GOV_OFFICE_UPKEEP_EVALUATED",
        "origin=gov-daily",
        "originKind=tick",
        "day=7",
        "unit=gov-1",
        "grainNeed=10",
        "grainPaid=0");
    assertThat(appender.messages())
        .as("TRACE 未开（.trace = INFO）时逐笔明细不得出现；实得 %s", appender.messages())
        .noneSatisfy(line -> assertThat(line).contains("event=GOV_UPKEEP_SHORTFALL"))
        .noneSatisfy(line -> assertThat(line).contains("event=GOV_SIGNAL_DRAFT"));

    appender.clear();
    setGovLevel(Level.TRACE);
    settleShortfallAndNoSeatFixture(7L);

    assertEvent(
        appender.messages(),
        "TRACE",
        "GOV_UPKEEP_SHORTFALL",
        "origin=gov-daily",
        "originKind=tick",
        "day=7",
        "unit=gov-1",
        "resource=grain",
        "assessed=10",
        "paid=0",
        "shortfall=10");
    assertEvent(
        appender.messages(),
        "TRACE",
        "GOV_SIGNAL_DRAFT",
        "origin=gov-daily",
        "originKind=tick",
        "day=7",
        "kind=ADMIN_SUPPLY",
        "severity=1");

    appender.clear();
    setGovLevel(Level.INFO);
    settleShortfallAndNoSeatFixture(8L);
    assertThat(appender.messages())
        .as("调回 INFO 后 DEBUG/TRACE 都必须消失；实得 %s", appender.messages())
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).startsWith("INFO|"));
  }

  /** 空 offices：只发 START/END（INFO），oracle 一次都不会被调。 */
  private static GovDaily.Outcome settleEmptyGov(long tick) {
    return settleLegacy(
        GovState.empty(),
        UnitState.empty(),
        map(),
        SocialData.empty(),
        tick,
        365L,
        (unitId, at, resource, requested, day) -> 0L);
  }

  /**
   * 两个 office：U1 有座位、政策每人日粮 10 且 oracle 实付 0 ⇒ due 缺口 10 ⇒ {@code GOV_UPKEEP_SHORTFALL} + {@code
   * ADMIN_SUPPLY} ⇒ {@code GOV_SIGNAL_DRAFT}；U2 无有效位置 ⇒ {@code GOV_OFFICE_NO_SEAT}。
   */
  private static GovDaily.Outcome settleShortfallAndNoSeatFixture(long tick) {
    GovState base = state(GovOfficeState.empty(U1, 0L), GovOfficeState.empty(U2, 0L));
    UnitState units =
        new UnitState(
            ordered(
                govUnit(U1, gov(staff(1L, 0L, 0L), policy(10L, 0L, 0L, 0L)), Optional.of(H1)),
                govUnit(U2, gov(staff(1L, 0L, 0L), policy(10L, 0L, 0L, 0L)), Optional.empty())));
    return settleLegacy(
        base,
        units,
        map(),
        SocialData.empty(),
        tick,
        365L,
        (unitId, at, resource, requested, day) -> 0L);
  }

  private void setGovLevel(Level level) {
    Configurator.setLevel(GovLog.ROOT_LOGGER_NAME, level);
    Configurator.setLevel(
        GovLog.TRACE_LOGGER_NAME, level == Level.TRACE ? Level.TRACE : Level.INFO);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(GovLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  private static void assertEvent(
      List<String> lines, String level, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 %s|event=%s（实得 %s）", level, event, lines)
        .anySatisfy(
            line -> {
              assertThat(line).startsWith(level + "|");
              assertThat(line).contains("event=" + event);
              for (String fragment : fragments) {
                assertThat(line).contains(fragment);
              }
            });
  }

  // ── 夹具（内联自 simos-gov 的 GovDailyTest，避开跨模块 test 作用域）────────────────────────

  private static GovState state(GovOfficeState... offices) {
    Map<UnitId, GovOfficeState> byId = new LinkedHashMap<>();
    for (GovOfficeState office : offices) {
      byId.put(office.unitId(), office);
    }
    return new GovState(byId);
  }

  private static Map<UnitId, Unit> ordered(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return byId;
  }

  private static Unit govUnit(UnitId id, GovernmentFormation gov, Optional<HexCoord> position) {
    // ★ S3b/P2-C：带 GovernmentFormation 的单位必须恰含派生的政府家户 hh-gov-<unitId>。
    return new Unit(
        id,
        "gov-unit-" + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        List.of(),
        1,
        1000,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        Optional.of(gov),
        Map.of(),
        List.of(GovernmentHouseholds.of(id.value())));
  }

  private static GovernmentFormation gov(Map<StaffRole, Long> staff, OfficePolicy policy) {
    return new GovernmentFormation(
        staff, Map.of(), policy, Optional.empty(), GovernmentLevel.CENTRAL, Map.of());
  }

  private static Map<StaffRole, Long> staff(long yamen, long scribe, long post) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    staff.put(StaffRole.YAMEN, yamen);
    staff.put(StaffRole.SCRIBE, scribe);
    staff.put(StaffRole.POST, post);
    return staff;
  }

  private static OfficePolicy policy(long grain, long clothCycle, long money, long retirement) {
    return new OfficePolicy(grain, clothCycle, money, retirement, Map.of());
  }

  private static GameMap map() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H1, new HexCell(0.5));
    hexes.put(H2, new HexCell(0.5));
    TerrainType desert = TerrainCatalog.of("desert");
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 采集 appender：记 {@code level|message}，不碰状态。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> messages = new ArrayList<>();

    private CollectingAppender() {
      super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      messages.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      return List.copyOf(messages);
    }

    private void clear() {
      messages.clear();
    }
  }

  /**
   * ★ Z3b 编译最小占位（Z6 统一重写测试）：新 {@code GovDaily.settle} 只消费 app 算好的效率表；这里临时复刻旧桥口径构造效率表，
   * 让本类既有断言代码仍能编译（本类只验 gov 日志门面，不验效率数值）。
   */
  private static GovDaily.Outcome settleLegacy(
      GovState govState,
      UnitState units,
      GameMap map,
      SocialData social,
      long tick,
      long daysInYearAtSettlement,
      GovDaily.PaymentOracle oracle) {
    long quota =
        io.mosire.simos.social.provisioning.SocialProvisioning.defaults()
            .standardLaborMilliHoursPerTick();
    Map<UnitId, GovEfficiency.Efficiency> byUnit = new LinkedHashMap<>();
    List<UnitId> ordered = new ArrayList<>(govState.offices().keySet());
    ordered.sort(Comparator.comparing(UnitId::value));
    for (UnitId unitId : ordered) {
      Unit unit = units.units().get(unitId);
      if (unit == null) {
        continue;
      }
      UnitModule module = unit.module().orElse(null);
      if (!(module instanceof GovernmentFormation formation)) {
        continue;
      }
      Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);
      long securityDemand = 0L;
      long paperworkDemand = 0L;
      for (GovDemand.HexDemand hexDemand : demand.values()) {
        securityDemand += hexDemand.security();
        paperworkDemand += hexDemand.paperwork();
      }
      long securitySupply = formation.staff().getOrDefault(StaffRole.YAMEN, 0L) * quota;
      long paperworkSupply =
          (formation.staff().getOrDefault(StaffRole.SCRIBE, 0L)
                  + formation.staff().getOrDefault(StaffRole.POST, 0L))
              * quota;
      GovAdministrationPlan plan =
          new GovAdministrationPlan(
              Math.multiplyExact(securityDemand, quota),
              Math.multiplyExact(paperworkDemand, quota),
              GovAdministrationPlan.DEFAULT_POST_TIERS,
              GovRules.PER_MILLE,
              GovRules.PER_MILLE,
              GovRules.PER_MILLE,
              GovRules.PER_MILLE,
              GovAdministrationPlan.DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT);
      byUnit.put(
          unitId,
          GovEfficiency.of(
              formation,
              demand,
              plan,
              securitySupply,
              paperworkSupply,
              GovRules.PER_MILLE,
              GovRules.PER_MILLE,
              GovRules.PER_MILLE,
              GovRules.PER_MILLE,
              quota));
    }
    return GovDaily.settle(govState, units, tick, daysInYearAtSettlement, byUnit, oracle);
  }
}
