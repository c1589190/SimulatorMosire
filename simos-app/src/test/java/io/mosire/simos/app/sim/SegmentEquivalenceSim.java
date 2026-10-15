package io.mosire.simos.app.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>批 M（2026-10-15）：30 天"分段等价"对拍从 JUnit 测试类搬成独立跑法</b>。
 *
 * <p>★★ <b>用户原话（逐字，2026-10-15）</b>：「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑， 别塞进测试里！」
 *
 * <p>★ <b>为什么它算违规</b>：两条用例各自在一个 {@code @Test} 里推 60 / 90 tick（30 天一段 + 30 次逐日 ⇒ 判据 M1 的"≥60
 * tick"与"≥2 个多日段"两条都踩）。断言**逐字搬来**（模块切片逐字段相等 + day30 中央工资足额）。 门禁里留的 smoke（{@code
 * SegmentEquivalenceAcrossEconomyGovSocialTest}）把窗口缩到 5 天 / 10 tick， 只保"多日一刀 == 逐日多刀"这条契约本身。
 *
 * <p>★ 跑法：{@code tools/multitick-sim.sh segment-equivalence}（独立进程 + 独立 store，不进 {@code clean
 * verify}）。
 */
/**
 * ★★ <b>跨模块（经济 + GOV + Social）分段等价断言 —— 本仓原先缺的那条护栏</b>。
 *
 * <p>仓里既有的等价性断言只有 {@code UnitTimeParticipantTest} 的 {@code
 * multiDayAdvanceEqualsChainedSingleDayAdvancesIncludingTheWholeFormation}（对 Unit 那半边成立）； Z7
 * 是第一个撞上"经济/GOV 半边不等价"的用例，而且撞上纯属<b>偶然</b>（它只看 day30 那一条工资行）。本类把它补上。
 *
 * <p>★★ <b>判据（逐字取 {@code
 * docs/superpowers/specs/2026-09-25-aggregate-economy-redesign.md:236-247}，用户原话
 * 「我点推进100天，它不直接结算到100，而是一天一天地推进！改！」）</b>：
 *
 * <blockquote>
 *
 * {@code advance(from, from+N)} 的<b>终态</b> == N 次单日 advance 的终态
 *
 * </blockquote>
 *
 * <p>★ <b>为什么比较"模块状态"而不是"框架 record"</b>：{@code StateRef}/{@code timestamp} 是本轮 revision 的记账对象，
 * 两侧当然不同；{@code UnitSnapshot} 的<b>位置历史分段数</b>也允许不同（§十一 明文：事件链可以不同）。故断言取<b>各领域模块自己的 状态类型</b>：{@code
 * economy} / {@code social} / {@code gov} 逐字段相等（{@code UnitState} 的终态视图由既有的 Unit 侧用例覆盖）。
 *
 * <p>★ <b>为什么这条能挡住"分段不等价"</b>：它比较的是<b>同一次 advance 内部的跨日瞬态</b>是否泄漏 —— 只要某个桥/累加器在 一次 {@code
 * advance(N)} 内跨日累积、却在下一次 {@code advance} 重建，两侧终态就会分叉。Z7 在未修基线 {@code 9c841e06} 上撞到的正是这个形态（一次 30
 * 天：day30 中央工资 {@code paidGrain=320}；逐日：day30 无工资行）。
 */
public final class SegmentEquivalenceSim {

  private static final UnitId PROVINCE = UnitId.parse("gov-province");
  private static final UnitId CENTRAL = UnitId.parse("gov-central");
  private static final HouseholdId CENTRAL_TREASURY = GovernmentHouseholds.of("gov-central");
  private static final HouseholdId CENTRAL_OFFICIALS = new HouseholdId("hh-unit:gov-central");
  private static final io.mosire.simos.util.state.BranchId MAIN =
      new io.mosire.simos.util.state.BranchId("main");

  /** 一次 30 天 / 逐日 30 次 —— 同一座真世界夹具、同一份创世。 */
  private static final long DAYS = 30L;

  /** ★ 曾是 JUnit 的 {@code @TempDir}；现在是本跑法的独立 store 根（由 {@link #run} 赋值）。 */
  private static Path tempDir;

  /**
   * {@code advance(0→30)} 的终态 == 30 次单日 advance 的终态（经济 + GOV + Social 三片逐字段）。
   *
   * <p>装置 = 真 {@link Shell} + 真 {@code SmallWorld.state} + 真 {@code bootstrapGenesis}（与 Z7
   * 同款，含"中央国库被清空" 这一负向前提 ⇒ 官吏户在 day1..29 欠俸、按 Z7d-2 在 day3/day4 走人直到户空）。<b>户空之后</b>才是本用例真正压的地方：
   * 职位与发薪依据必须继续存在（用户 2026-10-13 设计要求「就算是政府家户，0 人也发钱」）。
   */
  static void oneThirtyDayAdvanceEqualsThirtyChainedSingleDayAdvances() {
    // ① 一次 30 天
    SimulationState once = runOnce();
    // ② 30 次单日（同一夹具的新世界；逐日 advance 各落一条 revision）
    SimulationState chained = runChained();

    EconomyData economyOnce = GovZ6WorldFixture.economySlice(once);
    EconomyData economyChained = GovZ6WorldFixture.economySlice(chained);
    assertThat(economyOnce.classes())
        .as("economy.classes（户内人口/劳动预算）逐值相同")
        .isEqualTo(economyChained.classes());
    assertThat(economyOnce.allocations())
        .as("economy.allocations（GOV_SERVICE 承诺行 = 职位，不随人口缩/删）逐值相同")
        .isEqualTo(economyChained.allocations());
    assertThat(economyOnce.governments())
        .as("economy.governments（预算政策/工资规则）逐值相同")
        .isEqualTo(economyChained.governments());
    assertThat(economyOnce.crisisSignals())
        .as("economy.crisisSignals（危机信号）逐值相同")
        .isEqualTo(economyChained.crisisSignals());
    assertThat(economyOnce.flows())
        .as("economy.flows（逐户流水读数）逐值相同")
        .isEqualTo(economyChained.flows());

    assertThat(GovZ6WorldFixture.socialSlice(once).households())
        .as("social.households（成员/位置/生死）逐值相同")
        .isEqualTo(GovZ6WorldFixture.socialSlice(chained).households());
    assertThat(GovZ6WorldFixture.socialSlice(once).fleeStates())
        .as("social.fleeStates（逃亡率/余数）逐值相同")
        .isEqualTo(GovZ6WorldFixture.socialSlice(chained).fleeStates());

    assertThat(GovZ6WorldFixture.govSlice(once).offices())
        .as("gov.offices（每 tick 行政读数：效率/流量/信号）逐值相同")
        .isEqualTo(GovZ6WorldFixture.govSlice(chained).offices());
    assertThat(GovZ6WorldFixture.govSlice(once).remittanceStates())
        .as("gov.remittanceStates（上缴周期读数）逐值相同")
        .isEqualTo(GovZ6WorldFixture.govSlice(chained).remittanceStates());

    assertThat(GovZ6WorldFixture.unitSlice(once).units().keySet())
        .as("unit 单位集合逐值相同")
        .isEqualTo(GovZ6WorldFixture.unitSlice(chained).units().keySet());
  }

  /**
   * ★★ 反向用例（负向前提的钉子）：户空的官吏户<b>照样</b>产出当天 {@code ADMIN_SALARY} 规则，且上缴到账的同一 tick 足额付清 —— 这正是用户裁定「0
   * 人也发钱」的可观测形态，也是 Z7 day30 那条断言的机制。
   *
   * <p>两个切法都必须看到同一条：一次 30 天与逐日 30 次，day30 都必须是 {@code paidGrain=320 shortfallGrain=0}。
   */
  static void emptyOfficialHouseholdStillDrawsItsFullSalaryOnBothSegmentations() {
    assertThat(dayThirtySalaryLines(runOnceLog()))
        .as("一次 30 天：户空后 day30 中央工资足额（0 人也发钱）")
        .anyMatch(SegmentEquivalenceSim::isFullCentralSalary);
    assertThat(dayThirtySalaryLines(runChainedLog()))
        .as("逐日 30 次：同一条工资行必须逐字相同（分段等价）")
        .anyMatch(SegmentEquivalenceSim::isFullCentralSalary);
    // 官吏户 = 0 人（户空是前提，不是"没走人"）
    assertThat(GovZ6WorldFixture.socialSlice(runOnce()).householdPopulation(CENTRAL_OFFICIALS))
        .as("前提：中央官吏户已走光（0 人）—— 本用例断言的正是'0 人仍发钱'")
        .isZero();
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────────────

  /** 一次 {@code advance(0→30)}；返回 head 的完整状态。 */
  private static SimulationState runOnce() {
    try (Shell shell = shellWithProvinceRate(tempDir.resolve("once"), 500L)) {
      advance(shell, 0L, DAYS, "once");
      return state(shell);
    }
  }

  /** 30 次单日 advance；返回 head 的完整状态。 */
  private static SimulationState runChained() {
    try (Shell shell = shellWithProvinceRate(tempDir.resolve("chained"), 500L)) {
      for (long day = 0L; day < DAYS; day++) {
        advance(shell, day, day + 1L, "chained-" + day);
      }
      return state(shell);
    }
  }

  /** 一次 30 天那次推进的 time-logger INFO 行（只取工资行）。 */
  private static List<String> runOnceLog() {
    try (Shell shell = shellWithProvinceRate(tempDir.resolve("once-log"), 500L);
        AppLogCapture log = AppLogCapture.appTime()) {
      advance(shell, 0L, DAYS, "once-log");
      return List.copyOf(log.messages());
    }
  }

  /** 逐日 30 次推进的 time-logger INFO 行（只取工资行）。 */
  private static List<String> runChainedLog() {
    try (Shell shell = shellWithProvinceRate(tempDir.resolve("chained-log"), 500L);
        AppLogCapture log = AppLogCapture.appTime()) {
      for (long day = 0L; day < DAYS; day++) {
        advance(shell, day, day + 1L, "chained-log-" + day);
      }
      return List.copyOf(log.messages());
    }
  }

  private static List<String> dayThirtySalaryLines(List<String> messages) {
    return messages.stream()
        .filter(line -> line.contains("event=GOV_ADMIN_SALARY_DAY"))
        .filter(line -> line.contains("day=30"))
        .filter(line -> line.contains("unit=gov-central"))
        .toList();
  }

  private static boolean isFullCentralSalary(String line) {
    return line.contains("paidGrain=320")
        && line.contains("shortfallGrain=0")
        && line.contains("paidSilver=32")
        && line.contains("shortfallSilver=0");
  }

  // ── 夹具（与 Z7RemittanceE2ETest 同款：真空库 + 零中央国库 + 省 rate=500）────────────────────

  private static Shell shellWithProvinceRate(Path storeDir, long rate) {
    ShellConfig config =
        ShellConfig.defaults(storeDir).withPorts(0, 0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
    Shell shell = Shell.start(config);
    try {
      SimulationState state = SmallWorld.state(config.mapId());
      state = withTreasuryEmptied(state, CENTRAL_TREASURY);
      state = withProvinceRemittanceRate(state, rate);
      shell.coreSimos().bootstrapGenesis(state);
      return shell;
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
  }

  private static SimulationState withProvinceRemittanceRate(SimulationState state, long rate) {
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    GovernmentId id = GovernmentIds.ofUnit(PROVINCE.value());
    Government province = economy.governments().get(id);
    assertThat(province).as("创世必须已登记省 GOV（政策宿主 = 政府记录）").isNotNull();
    GovBudgetPolicy current = province.budgetPolicy();
    Map<GovernmentId, Government> governments = new LinkedHashMap<>(economy.governments());
    governments.put(
        id,
        province.withBudgetPolicy(
            new GovBudgetPolicy(current.orderedCategories(), current.officialSalaryRule(), rate)));
    return GovZ6WorldFixture.withModule(
        state,
        "economy",
        new EconomySnapshot(
            state.meta().ref(), state.meta().timestamp(), economy.withGovernments(governments)));
  }

  private static void advance(Shell shell, long from, long to, String label) {
    long head = shell.coreSimos().head(MAIN).orElseThrow().value();
    shell.advanceAndDrain(
        new AdvanceTime(
            "cmd-" + label,
            "corr-" + label,
            label,
            MAIN,
            new RevisionId(head),
            new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
  }

  private static SimulationState state(Shell shell) {
    long revision = shell.coreSimos().head(MAIN).orElseThrow().value();
    return shell
        .coreSimos()
        .replay(new io.mosire.simos.util.state.StateRef(MAIN, new RevisionId(revision)));
  }

  private static SimulationState withTreasuryEmptied(SimulationState state, HouseholdId household) {
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    Map<HouseholdAccountKey, HouseholdInventory> inventories =
        new LinkedHashMap<>(actor.accounts());
    HouseholdAccountKey key = new HouseholdAccountKey(household);
    HouseholdInventory row = inventories.get(key);
    assertThat(row).as("中央国库账户必须存在").isNotNull();
    inventories.put(
        key,
        new HouseholdInventory(key, Map.of(), Map.of(), row.frozenBalances(), row.frozenMoney()));
    return GovZ6WorldFixture.withModule(
        state,
        "actor",
        new ActorSnapshot(
            state.meta().ref(), state.meta().timestamp(), actor.withInventories(inventories)));
  }

  /**
   * 独立跑法入口（**不是 JUnit 用例** —— surefire 的缺省 include 只收「Test 前缀 / Test 后缀 / Tests /
   * TestCase」四种类名，本类名不匹配 ⇒ 门禁永远不会跑到它）。
   *
   * @param caseDir 本跑法的独立 store 根（{@code test-world/multitick/segment-equivalence}）
   */
  public static void run(Path caseDir) {
    // ★ 原来是 JUnit 的 @TempDir：**每条用例一个新临时目录**。搬到这里必须显式复刻这个隔离 ——
    //   两条用例都各自 resolve("once")/resolve("chained")，共用同一个根会第二次创世撞"库非空"
    //   （实测踩到：IllegalStateException「库非空…第二次创世被拒绝」）。
    System.out.println("段数/天数：一次性 0→30（1 段 30 天）× 2 座世界 + 逐日 30 tick × 2 座世界 = 90 tick / 4 段");
    tempDir = caseDir.resolve("case1");
    oneThirtyDayAdvanceEqualsThirtyChainedSingleDayAdvances();
    System.out.println("✓ 一次 30 天 == 30 次单日（economy / social / gov / unit 四片逐字段相等）");
    tempDir = caseDir.resolve("case2");
    emptyOfficialHouseholdStillDrawsItsFullSalaryOnBothSegmentations();
    System.out.println("✓ 户空官吏户在两种切法下 day30 都足额发薪（paidGrain=320 shortfallGrain=0）");
  }
}
