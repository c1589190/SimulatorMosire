package io.mosire.simos.app.time;

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
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>Z7c 端到端：关账日上缴 + 同一 tick 上级可见</b>（设计书 §4、§8-闭环；z7c 台账 §8-6）。
 *
 * <p>装置 = 真 {@link Shell} + 真 {@code SmallWorld.state} + 真 {@code bootstrapGenesis}（空库首启）+ 真 {@code
 * AdvanceTime} 0→30（官署 30 天周期 ⇒ day30 是第一个关账日）。
 *
 * <ul>
 *   <li>rate=500：day30 出现 {@code
 *       GOV_REMITTANCE_DAY}（payer=gov-province/payee=gov-central，paid&gt;0）；周期累计清零；
 *   <li>中央国库被故意清空到 0（day1..29 工资/俸禄付不出）⇒ day30 上缴到账后<b>同一 tick</b> 工资足额 （{@code
 *       GOV_ADMIN_SALARY_DAY unit=gov-central paidGrain=320 shortfall=0}）——证明 remittance 在预算 oracle
 *       之前；
 *   <li>rate=0（抗税）：无 {@code GOV_REMITTANCE_DAY}、具名 {@code GOV_REMITTANCE_SKIPPED
 *       reason=rate-zero}、账户零动。
 * </ul>
 */
class Z7RemittanceE2ETest {

  private static final UnitId PROVINCE = UnitId.parse("gov-province");
  private static final HouseholdId CENTRAL_TREASURY = GovernmentHouseholds.of("gov-central");
  private static final io.mosire.simos.util.state.BranchId MAIN =
      new io.mosire.simos.util.state.BranchId("main");

  @TempDir Path tempDir;

  @Test
  void rateFiveHundredRemitsOnCloseAndCentralPaysSameTickAfterTreasuryDrained() {
    try (Shell shell = shellWithProvinceRate(tempDir.resolve("remit-e2e"), 500L);
        AppLogCapture log = AppLogCapture.appTime()) {
      advance(shell, 0L, 30L, "rate500");

      assertThat(log.messages())
          .as("day30 必须有真上缴事件（payer/payee/paid 逐值）；实得 %s", log.messages())
          .anyMatch(
              line ->
                  line.contains("event=GOV_REMITTANCE_DAY")
                      && line.contains("day=30")
                      && line.contains("payer=gov-province")
                      && line.contains("payee=gov-central")
                      && paidGrainOf(line) > 0L);

      assertThat(log.messages())
          .as("中央国库被清空时 day1..29 工资付不出（负向前提）")
          .anyMatch(
              line ->
                  line.contains("event=GOV_ADMIN_SALARY_DAY")
                      && line.contains("unit=gov-central")
                      && shortfallGrainOf(line) > 0L);

      assertThat(log.messages())
          .as("day30 上缴到账后同一 tick 中央工资足额（remittance 在预算规划之前）")
          .anyMatch(
              line ->
                  line.contains("event=GOV_ADMIN_SALARY_DAY")
                      && line.contains("day=30")
                      && line.contains("unit=gov-central")
                      && line.contains("paidGrain=320")
                      && line.contains("paidSilver=32")
                      && line.contains("shortfallGrain=0")
                      && line.contains("shortfallSilver=0"));

      SimulationState state = state(shell);
      GovRemittanceState remittance =
          GovZ6WorldFixture.govSlice(state).remittanceStateOrDefault(PROVINCE);
      assertThat(remittance.lastCycleCloseDay()).isEqualTo(30L);
      assertThat(remittance.lastPaidGrainMilli()).as("足额：实缴 == 应缴").isPositive();
      assertThat(remittance.lastShortfallGrainMilli()).isZero();
      assertThat(remittance.cycleGrainCollectedMilli()).as("关账后累计清零").isZero();
      assertThat(remittance.cycleSilverCollectedMilli()).isZero();
    }
  }

  @Test
  void rateZeroIsTaxResistanceWithoutAnyTransferOrDayEvent() {
    try (Shell shell = shellWithProvinceRate(tempDir.resolve("remit-zero"), 0L);
        AppLogCapture log = AppLogCapture.appTime()) {
      advance(shell, 0L, 30L, "rate0");

      assertThat(log.messages())
          .as("抗税 ⇒ 不得有真上缴事件")
          .noneMatch(line -> line.contains("event=GOV_REMITTANCE_DAY"));
      assertThat(log.messages())
          .as("rate=0 必须具名 skip")
          .anyMatch(
              line ->
                  line.contains("event=GOV_REMITTANCE_SKIPPED")
                      && line.contains("unit=gov-province")
                      && line.contains("reason=rate-zero"));

      SimulationState state = state(shell);
      GovRemittanceState remittance =
          GovZ6WorldFixture.govSlice(state).remittanceStateOrDefault(PROVINCE);
      assertThat(remittance.lastPaidGrainMilli()).isZero();
      assertThat(remittance.lastPaidSilverMilli()).isZero();
      assertThat(remittance.lastCycleCloseDay()).isEqualTo(30L);
      assertThat(remittance.cycleGrainCollectedMilli()).as("抗税也必须清零累计").isZero();
    }
  }

  // ── 夹具 ──

  /** 真空库壳：零中央国库 + 省 rate 指定；bootstrap 后 head=1。 */
  private static Shell shellWithProvinceRate(Path storeDir, long rate) {
    ShellConfig config =
        ShellConfig.defaults(storeDir).withPorts(0, 0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
    Shell shell = Shell.start(config);
    try {
      SimulationState state = SmallWorld.state(config.mapId());
      state = withTreasuryEmptied(state, CENTRAL_TREASURY);
      GovState gov = GovZ6WorldFixture.govSlice(state);
      GovBudgetPolicy province = gov.budgetPolicyOrDefault(PROVINCE);
      state =
          GovZ6WorldFixture.withModule(
              state,
              "gov",
              new GovSnapshot(
                  state.meta().ref(),
                  state.meta().timestamp(),
                  gov.withBudgetPolicy(
                      PROVINCE,
                      new GovBudgetPolicy(
                          province.orderedCategories(), province.officialSalaryRule(), rate))));
      shell.coreSimos().bootstrapGenesis(state);
      return shell;
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
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

  private static long paidGrainOf(String line) {
    return longField(line, "paidGrain");
  }

  private static long shortfallGrainOf(String line) {
    return longField(line, "shortfallGrain");
  }

  private static long longField(String line, String field) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\b" + field + "=(\\d+)").matcher(line);
    if (!matcher.find()) {
      throw new AssertionError("日志缺字段 " + field + ": " + line);
    }
    return Long.parseLong(matcher.group(1));
  }
}
