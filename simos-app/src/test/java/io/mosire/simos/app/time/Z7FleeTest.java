package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.household.HouseholdUnitConsistency;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.StateRunner;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.HouseholdFleeState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z7d-2 官吏户逃亡</b>（设计书 §7、§8-要点6、§9；z7d2 台账 §5/§6/§10）。
 *
 * <ul>
 *   <li>驱动公式：欠俸 +150‰/日；饥饿追加 ⌊150×饥饿比例⌋；付足吃饱 −20‰/日（下限 0）；上限 1000；
 *   <li>只转成员、不带走公/私财产：桥执行前后账户逐值不变、economy 资产份额逐值不变；
 *   <li>真走人 + 户空协同（★★ 新口径，用户 2026-10-13 裁定）：<b>职位与发薪依据一律保留</b> —— 岗位留在编制、{@code Unit.households}
 *       仍含该 0 人壳户、位置仍是 {@code UNIT:<gov>}、 {@code GOV_SERVICE} 承诺行不缩不删、{@code projectedStaff}
 *       照旧；只让<b>有效劳动</b>归 0 （{@code GovernmentServiceLaborBridge.supply} 的 {@code min(承诺,
 *       该户实际劳动)}）⇒ 行政效率如实降到 0； 发 {@code GOV_SERVICE_DESERTION} 信号；
 *   <li>不自动补俸、不自动招人（国库仍 0、无新户、post 不补）。
 * </ul>
 *
 * <p>★★ <b>为什么"户空不摘岗位/不释放承诺"（2026-10-14 改向，用户原话，最高权威）</b>：
 *
 * <blockquote>
 *
 * 「我的设计要求是，<b>就算是政府家户，0 人也发钱</b> —— 我甚至<b>从来没设计过没人就不发钱了</b>」
 *
 * </blockquote>
 *
 * <p>旧口径（"户空 ⇒ 摘岗位 + 摘 {@code Unit.households} + 释放 {@code GOV_SERVICE} 承诺 + 迁壳户"）被用户裁定为
 * <b>缺陷</b>：{@code GOV_SERVICE} 承诺行是<b>职位（post）</b>、也是 {@code GovSalaryRuleBridge} 逐户发薪与 {@code
 * GovernmentUpkeepOracle} 俸禄分摊的<b>唯一遍历源</b> —— 缩到 0/删行就等于把"没人"读成"没职位 ⇒ 不发钱"。 本用例的断言据此改向新口径（机制与定位结论见
 * {@code docs/superpowers/plans/2026-10-13-emergency-stop-protocol.md} §11.7）。
 */
class Z7FleeTest {

  private static final HouseholdId OFFICIAL = HouseholdId.parse("hh-unit:gov-central");
  private static final HouseholdId CENTRAL_TREASURY = GovernmentHouseholds.of("gov-central");
  private static final UnitId CENTRAL = UnitId.parse("gov-central");

  @Test
  void driverRisesFastOnUnderpaidAddsHungerAndFallsSlowlyWhenSatisfied() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    SocialData social = GovZ6WorldFixture.socialSlice(base);
    EconomyData economy = GovZ6WorldFixture.economySlice(base);
    UnitState units = GovZ6WorldFixture.unitSlice(base);
    AccountSession accounts =
        OwnershipBooks.loadAccountSession(economy, GovZ6WorldFixture.actorSlice(base));

    GovernmentServiceDesertionBridge.Outcome underpaid =
        GovernmentServiceDesertionBridge.execute(
            social,
            economy,
            () -> economy,
            units,
            Map.of(OFFICIAL, 160L),
            Map.of(),
            accounts,
            MarketTopologyBook.from(base),
            List.of(),
            1L);
    assertThat(rate(underpaid, OFFICIAL)).as("欠俸 ⇒ +150‰/日").isEqualTo(150L);
    assertThat(driverReason(underpaid, OFFICIAL)).isEqualTo("underpaid");
    assertThat(underpaid.report().flights()).as("2 人 × 150‰ < 1 人 ⇒ 不真走").isZero();

    SocialData hungry = social.withSatietyPerMille(Map.of(OFFICIAL, 500L));
    GovernmentServiceDesertionBridge.Outcome hungerOnly =
        GovernmentServiceDesertionBridge.execute(
            hungry,
            economy,
            () -> economy,
            units,
            Map.of(),
            Map.of(),
            accounts,
            MarketTopologyBook.from(base),
            List.of(),
            1L);
    assertThat(rate(hungerOnly, OFFICIAL)).as("饥饿追加 ⌊150×500/1000⌋ = 75").isEqualTo(75L);
    assertThat(driverReason(hungerOnly, OFFICIAL)).isEqualTo("hunger");

    GovernmentServiceDesertionBridge.Outcome both =
        GovernmentServiceDesertionBridge.execute(
            hungry,
            economy,
            () -> economy,
            units,
            Map.of(OFFICIAL, 160L),
            Map.of(),
            accounts,
            MarketTopologyBook.from(base),
            List.of(),
            1L);
    assertThat(rate(both, OFFICIAL)).as("欠俸 + 饥饿 = 150 + 75").isEqualTo(225L);
    assertThat(driverReason(both, OFFICIAL)).isEqualTo("underpaid+hunger");

    SocialData falling =
        social.withFleeStates(
            Map.of(OFFICIAL, new HouseholdFleeState(200L, 0L, 0L, 0L, "", 0L, "")));
    GovernmentServiceDesertionBridge.Outcome satisfied =
        GovernmentServiceDesertionBridge.execute(
            falling,
            economy,
            () -> economy,
            units,
            Map.of(),
            Map.of(),
            accounts,
            MarketTopologyBook.from(base),
            List.of(),
            1L);
    assertThat(rate(satisfied, OFFICIAL)).as("付足且吃饱 ⇒ −20‰/日").isEqualTo(180L);
    assertThat(driverReason(satisfied, OFFICIAL)).isEqualTo("satisfied");

    SocialData nearlyZero =
        social.withFleeStates(
            Map.of(OFFICIAL, new HouseholdFleeState(10L, 0L, 0L, 0L, "", 0L, "")));
    GovernmentServiceDesertionBridge.Outcome floored =
        GovernmentServiceDesertionBridge.execute(
            nearlyZero,
            economy,
            () -> economy,
            units,
            Map.of(),
            Map.of(),
            accounts,
            MarketTopologyBook.from(base),
            List.of(),
            1L);
    assertThat(rate(floored, OFFICIAL)).as("慢降下限 0").isZero();

    SocialData capped =
        social.withFleeStates(
            Map.of(OFFICIAL, new HouseholdFleeState(999L, 0L, 0L, 0L, "", 0L, "")));
    try (io.mosire.simos.app.testing.AppLogCapture log =
        io.mosire.simos.app.testing.AppLogCapture.appTime()) {
      GovernmentServiceDesertionBridge.Outcome cappedOutcome =
          GovernmentServiceDesertionBridge.execute(
              capped,
              economy,
              () -> economy,
              units,
              Map.of(OFFICIAL, 1L),
              Map.of(),
              accounts,
              MarketTopologyBook.from(base),
              List.of(),
              1L);
      assertThat(members(cappedOutcome.social(), OFFICIAL))
          .as("2 人 × 1000‰ ⇒ 两人都走（上限封顶后仍满人逃亡）")
          .isZero();
      assertThat(log.messages())
          .as("真走人日志必须记录封顶后的 1000‰（不是 1149‰）；实得 %s", log.messages())
          .anyMatch(
              line ->
                  line.contains("event=GOV_SERVICE_DESERTION")
                      && line.contains("trigger=members-fled")
                      && line.contains("ratePerMille=1000"));
    }
  }

  @Test
  void fleeingMembersTakeNoPropertyAndActorBalancesStayByteIdentical() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    SocialData social = GovZ6WorldFixture.socialSlice(base);
    EconomyData economy = GovZ6WorldFixture.economySlice(base);
    UnitState units = GovZ6WorldFixture.unitSlice(base);
    AccountSession accounts =
        OwnershipBooks.loadAccountSession(economy, GovZ6WorldFixture.actorSlice(base));
    long treasuryGrain = grainOf(accounts, CENTRAL_TREASURY);
    long treasurySilver = silverOf(accounts, CENTRAL_TREASURY);
    long officialGrain = grainOf(accounts, OFFICIAL);
    long officialSilver = silverOf(accounts, OFFICIAL);

    SocialData ready =
        social.withFleeStates(
            Map.of(OFFICIAL, new HouseholdFleeState(1_000L, 0L, 0L, 0L, "", 0L, "")));
    GovernmentServiceDesertionBridge.Outcome outcome =
        GovernmentServiceDesertionBridge.execute(
            ready,
            economy,
            () -> economy,
            units,
            Map.of(OFFICIAL, 160L),
            Map.of(),
            accounts,
            MarketTopologyBook.from(base),
            List.of(),
            1L);

    assertThat(outcome.report().flights()).isEqualTo(1);
    assertThat(outcome.report().fledPopulation()).as("2 人全走").isEqualTo(2L);
    assertThat(members(outcome.social(), OFFICIAL)).isZero();
    assertThat(outcome.populationDeltas().get(OFFICIAL)).isEqualTo(-2L);
    assertThat(outcome.populationDeltas()).as("源 −2、目标 +2（净和 0）").hasSize(2);
    assertThat(
            outcome.populationDeltas().values().stream()
                .filter(delta -> delta > 0L)
                .mapToLong(Long::longValue)
                .sum())
        .as("走的人全部落到某个经济家户（不蒸发）")
        .isEqualTo(2L);

    assertThat(grainOf(accounts, CENTRAL_TREASURY)).as("国库粮不带进目的地").isEqualTo(treasuryGrain);
    assertThat(silverOf(accounts, CENTRAL_TREASURY)).isEqualTo(treasurySilver);
    assertThat(grainOf(accounts, OFFICIAL)).as("官吏户私产也不被搬走").isEqualTo(officialGrain);
    assertThat(silverOf(accounts, OFFICIAL)).isEqualTo(officialSilver);

    assertThat(economy.classes())
        .as("economy 基态行未被写")
        .isEqualTo(GovZ6WorldFixture.economySlice(base).classes());
    assertThat(economy.assetShares()).isEqualTo(GovZ6WorldFixture.economySlice(base).assetShares());
    assertThat(economy.governments()).isEqualTo(GovZ6WorldFixture.economySlice(base).governments());
  }

  @Test
  void emptyingHouseholdKeepsPostsAndCommitmentsAndSignalsWithoutAutoRefill() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");

    SimulationState underpaid =
        withFleeState(
            withTreasuryEmptied(withHouseholdGrain(base, OFFICIAL, 1_000_000L)),
            OFFICIAL,
            new HouseholdFleeState(1_000L, 0L, 0L, 0L, "", 0L, ""));

    StateRunner runner = new StateRunner("Map1", underpaid);
    runner.advance(0L, 1L);

    // 差分对照：同一日、同一状态、只把 fleeRate 置 0 —— 人口/户数/国库除"走人"外必须逐值一致。
    SimulationState controlState =
        withTreasuryEmptied(withHouseholdGrain(base, OFFICIAL, 1_000_000L));
    StateRunner control = new StateRunner("Map1", controlState);
    control.advance(0L, 1L);
    SocialData controlSocial = GovZ6WorldFixture.socialSlice(control.state());

    SocialData social = GovZ6WorldFixture.socialSlice(runner.state());
    UnitState units = GovZ6WorldFixture.unitSlice(runner.state());
    EconomyData economy = GovZ6WorldFixture.economySlice(runner.state());
    ActorData actorAfter = GovZ6WorldFixture.actorSlice(runner.state());

    HouseholdFleeState flee = social.fleeState(OFFICIAL);
    assertThat(flee.lastFleeDay()).isEqualTo(1L);
    assertThat(flee.lastFleeCount()).isEqualTo(2L);
    assertThat(flee.lastFleeReason()).isEqualTo("underpaid");
    assertThat(members(social, OFFICIAL)).as("户空").isZero();
    assertThat(social.households().get(OFFICIAL).location())
        .as("★ 新口径：户空后壳户留在原 unit —— 位置仍是 UNIT:gov-central（不迁座位格、不造 economy 孤儿行）")
        .isEqualTo(new HouseholdLocation.Unit(CENTRAL.value()));

    Unit central = units.units().get(CENTRAL);
    assertThat(central.households())
        .as("★ 新口径：Unit.households 仍含该 0 人壳户（不摘 households）")
        .contains(OFFICIAL)
        .contains(CENTRAL_TREASURY);
    GovernmentFormation formation = (GovernmentFormation) central.module().orElseThrow();
    assertThat(formation.governmentPostsOfHousehold())
        .as("★ 新口径：该户的岗位留在编制里（职位不随人口消失）")
        .containsKey(OFFICIAL);
    assertThat(formation.governmentPostsOfHousehold().get(OFFICIAL).role())
        .as("岗位内容逐值不变（创世的 POST/CENTRAL/tier-3 原样保留）")
        .isEqualTo(StaffRole.POST);
    List<HouseholdLaborCommitment> officialCommitments =
        economy.allocations().values().stream()
            .filter(row -> row.kind() == LaborCommitmentKind.GOV_SERVICE)
            .filter(row -> row.household().equals(OFFICIAL))
            .toList();
    assertThat(officialCommitments).as("★ 新口径：GOV_SERVICE 承诺行不释放（仍是 1 行 = 该职位的发薪依据）").hasSize(1);
    assertThat(officialCommitments.get(0).laborMilli())
        .as("★ 新口径：承诺劳动不被人口缩减（满员承诺原值 32000 毫小时/tick）")
        .isEqualTo(32_000L);
    assertThat(
            HouseholdUnitConsistency.staffHouseholdProjection(economy, social, units, 1L)
                .getOrDefault("gov-central:POST", 0L))
        .as("★ 新口径：projectedStaff 不随人口降为 0（32000 ÷ 标准定额 16000 = 2 人当量）")
        .isEqualTo(2L);
    assertThat(HouseholdUnitConsistency.staffProjectionMismatches(economy, social, units, 1L))
        .as("跨切片一致性守卫不报不一致")
        .isEmpty();
    // ★ 新口径：职位与承诺都还在，但**有效供给** = min(承诺, 该户实际劳动) = min(32000, 0) = 0 ⇒ 效率如实降到 0。
    //   注意"为什么是 0"变了：不再是"无在编供给/无岗位"（no-posted-household-commitment），而是"在编但无人到岗"
    //   （GOV_EFFICIENCY_ZERO_SUPPLY reason=committed-supply-underfed-to-zero）。
    StateRunner afterFlight = new StateRunner("Map1", runner.state());
    afterFlight.advance(1L, 2L);
    assertThat(
            GovZ6WorldFixture.govSlice(afterFlight.state())
                .offices()
                .get(CENTRAL)
                .efficiencyPerMille())
        .as("户空后的次日 GOV 无人到岗 ⇒ 有效供给 0 ⇒ 效率降到 0（不自动招人顶岗）")
        .isZero();

    assertThat(economy.crisisSignals().values())
        .as("真走人必须发 GOV_SERVICE_DESERTION 信号")
        .anyMatch(signal -> signal.kind() == HexCrisisSignal.Kind.GOV_SERVICE_DESERTION);

    assertThat(grainOf(actorAfter, CENTRAL_TREASURY)).as("不自动补俸：国库粮仍 0").isZero();
    assertThat(silverOf(actorAfter, CENTRAL_TREASURY)).as("不自动注资：国库银仍 0").isZero();
    assertThat(social.households())
        .as("不自动招人：家户数与只差 fleeRate 的对照世界逐值相同")
        .hasSize(controlSocial.households().size());
    assertThat(members(controlSocial, OFFICIAL)).as("对照世界官吏户仍在（走人不是对照世界的效果）").isEqualTo(2L);
    assertThat(totalSocialPopulation(social))
        .as("人口只在户间转移、不蒸发（与对照世界同值；差异由出生/死亡之外的走人造成）")
        .isEqualTo(totalSocialPopulation(controlSocial));
  }

  // ── 夹具 ──

  private static long rate(
      GovernmentServiceDesertionBridge.Outcome outcome, HouseholdId household) {
    return outcome.social().fleeState(household).fleeRatePerMille();
  }

  private static String driverReason(
      GovernmentServiceDesertionBridge.Outcome outcome, HouseholdId household) {
    return outcome.social().fleeState(household).lastDriverReason();
  }

  private static long members(SocialData social, HouseholdId household) {
    return social.households().get(household).members().values().stream()
        .mapToLong(Long::longValue)
        .sum();
  }

  private static long totalSocialPopulation(SocialData social) {
    long total = 0L;
    for (var household : social.households().values()) {
      total += household.members().values().stream().mapToLong(Long::longValue).sum();
    }
    return total;
  }

  private static SimulationState withFleeState(
      SimulationState state, HouseholdId household, HouseholdFleeState fleeState) {
    SocialData social = GovZ6WorldFixture.socialSlice(state);
    Map<HouseholdId, HouseholdFleeState> table = new LinkedHashMap<>(social.fleeStates());
    table.put(household, fleeState);
    return GovZ6WorldFixture.withModule(
        state,
        "social",
        new io.mosire.simos.social.SocialSnapshot(
            state.meta().ref(), state.meta().timestamp(), social.withFleeStates(table)));
  }

  private static SimulationState withHouseholdGrain(
      SimulationState state, HouseholdId household, long grain) {
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    Map<HouseholdAccountKey, HouseholdInventory> inventories =
        new LinkedHashMap<>(actor.accounts());
    HouseholdAccountKey key = new HouseholdAccountKey(household);
    HouseholdInventory row = inventories.get(key);
    assertThat(row).as("目标家户账户必须存在: %s", household.value()).isNotNull();
    inventories.put(
        key,
        new HouseholdInventory(
            key,
            Map.of(EconomyCommodities.GRAIN, grain),
            row.money(),
            row.frozenBalances(),
            row.frozenMoney()));
    return withInventories(state, actor, inventories);
  }

  /** 清空中央国库的粮与银（制造欠俸；官吏户私粮另行补足 ⇒ 隔离"饥饿驱动"）。 */
  private static SimulationState withTreasuryEmptied(SimulationState state) {
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    Map<HouseholdAccountKey, HouseholdInventory> inventories =
        new LinkedHashMap<>(actor.accounts());
    HouseholdAccountKey key = new HouseholdAccountKey(CENTRAL_TREASURY);
    HouseholdInventory row = inventories.get(key);
    assertThat(row).as("中央国库账户必须存在").isNotNull();
    inventories.put(
        key,
        new HouseholdInventory(key, Map.of(), Map.of(), row.frozenBalances(), row.frozenMoney()));
    return withInventories(state, actor, inventories);
  }

  private static SimulationState withInventories(
      SimulationState state,
      ActorData actor,
      Map<HouseholdAccountKey, HouseholdInventory> inventories) {
    return GovZ6WorldFixture.withModule(
        state,
        "actor",
        new ActorSnapshot(
            state.meta().ref(), state.meta().timestamp(), actor.withInventories(inventories)));
  }

  private static long grainOf(AccountSession accounts, HouseholdId household) {
    return accounts.householdAccount(household).goods().getOrDefault(EconomyCommodities.GRAIN, 0L);
  }

  private static long silverOf(AccountSession accounts, HouseholdId household) {
    return accounts
        .householdAccount(household)
        .money()
        .getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L);
  }

  private static long grainOf(ActorData actor, HouseholdId household) {
    HouseholdInventory row = actor.accounts().get(new HouseholdAccountKey(household));
    assertThat(row).as("账户必须存在: %s", household.value()).isNotNull();
    return row.balances().getOrDefault(EconomyCommodities.GRAIN, 0L);
  }

  private static long silverOf(ActorData actor, HouseholdId household) {
    HouseholdInventory row = actor.accounts().get(new HouseholdAccountKey(household));
    assertThat(row).as("账户必须存在: %s", household.value()).isNotNull();
    return row.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L);
  }
}
