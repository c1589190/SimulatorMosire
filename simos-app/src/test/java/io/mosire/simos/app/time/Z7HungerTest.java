package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.household.HouseholdSatietyBridge;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.StateRunner;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z7d-1 饥饿反馈端到端</b>（设计书 §5/§6、§8-要点5；z7d1 台账 §5/§7）。
 *
 * <p>装置 = 真 {@code GovZ6WorldFixture.smallWorldState} + 真组合参与者 {@link StateRunner}（含 unit 片）：
 *
 * <ul>
 *   <li>断粮：清空全部家户粮账户 ⇒ 当日粮缺口 ⇒ 回写 satiety 快降 −150‰；次日 Social 折算劳动下降， 经济行 laborMilli 随之下降，GOV 有效供给 =
 *       min(承诺, 实际) 下降、office 效率下降（underfed 如实记录）；
 *   <li>喂饱：+20‰/日，劳动逐步回升；
 *   <li>吃饱户：satiety 表不写（稀疏）、经济行 laborMilli 前后逐值不变（正常世界无副作用）；
 *   <li>回写幂等：同 base+feeds 两次结果逐字段相等；1×2 天与 2×1 天 satiety/经济劳动逐值一致。
 * </ul>
 */
class Z7HungerTest {

  private static final HouseholdId OFFICIAL = HouseholdId.parse("hh-unit:gov-central");
  private static final UnitId CENTRAL = UnitId.parse("gov-central");
  private static final CommodityId GRAIN = EconomyCommodities.GRAIN;
  private static final CalendarClock CLOCK = CalendarClock.julianDefault();

  @Test
  void starvedWorldDropsSatietyLaborSupplyAndEfficiencyThenRecoversByTwentyPerDay() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    SocialData social0 = GovZ6WorldFixture.socialSlice(base);
    long baseLabor = social0.householdLaborMilli(OFFICIAL, 1L, CLOCK);
    assertThat(baseLabor).as("创世官吏户（2 人）基础劳动").isPositive();

    StateRunner starved = new StateRunner("Map1", withoutAnyGrain(base));
    starved.advance(0L, 1L);

    SocialData afterStarve = GovZ6WorldFixture.socialSlice(starved.state());
    assertThat(afterStarve.satietyPerMille(OFFICIAL))
        .as("整户断顿 ⇒ −150‰（比例封顶 1000‰）")
        .isEqualTo(850L);
    long starvedLabor = afterStarve.householdLaborMilli(OFFICIAL, 2L, CLOCK);
    assertThat(starvedLabor)
        .as("劳动 = ⌊基础劳动 × 850/1000⌋")
        .isEqualTo(Math.floorDiv(baseLabor * 850L, 1000L));

    EconomyData economyAfterStarve = GovZ6WorldFixture.economySlice(starved.state());
    HouseholdEconomy row = economyAfterStarve.classes().get(OFFICIAL);
    assertThat(row.laborMilli()).as("经济行 laborMilli = 当日 Social 折算后的预算").isEqualTo(starvedLabor);

    GovernmentFormation formation =
        (GovernmentFormation)
            GovZ6WorldFixture.unitSlice(starved.state())
                .units()
                .get(CENTRAL)
                .module()
                .orElseThrow();
    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            economyAfterStarve,
            CENTRAL,
            formation,
            GovZ6WorldFixture.govSlice(starved.state()).administrationPlans().get(CENTRAL),
            1L);
    assertThat(supply.securityLaborMilli())
        .as("有效供给 = min(承诺, 实际) 必须低于承诺")
        .isLessThan(supply.committedSecurityLaborMilli());
    assertThat(supply.paperworkLaborMilli()).isLessThan(supply.committedPaperworkLaborMilli());
    assertThat(supply.underfedHouseholds()).as("在编但供给不足的户数").isEqualTo(1L);

    // ★ 时点口径（与读口 timing 同源）：day1 的 GovOfficeState 用日初（未饿）劳动预算算 ⇒ efficiency 仍 1000；
    //   饿少后的劳动在 day2 的 GovOfficeState 生效（day2 日初 recompute 读到 satiety=850）。
    SimulationState afterDay1 = starved.state();
    StateRunner secondDay = new StateRunner("Map1", afterDay1);
    secondDay.advance(1L, 2L);
    GovOfficeState office = GovZ6WorldFixture.govSlice(secondDay.state()).offices().get(CENTRAL);
    assertThat(office.efficiencyPerMille()).as("次日效率如实下降").isLessThan(1000L);
    assertThat(GovZ6WorldFixture.socialSlice(secondDay.state()).satietyPerMille(OFFICIAL))
        .as("继续断粮 ⇒ 再 −150‰")
        .isEqualTo(700L);

    // 喂饱：同一户私粮补足 ⇒ 下一日 +20‰，劳动回升（从 day1 的 850 起算）。
    StateRunner fed = new StateRunner("Map1", withHouseholdGrain(afterDay1, OFFICIAL, 1_000_000L));
    fed.advance(1L, 2L);
    SocialData afterRecover = GovZ6WorldFixture.socialSlice(fed.state());
    assertThat(afterRecover.satietyPerMille(OFFICIAL)).as("无缺口 ⇒ 慢升 +20‰/日").isEqualTo(870L);
    // 用"同状态、全饱食"的折算作基准（第二日断粮可能触发饥荒死亡，户内人数不再固定为 2）。
    long fullSatietyLabor =
        afterRecover.withSatietyPerMille(Map.of()).householdLaborMilli(OFFICIAL, 3L, CLOCK);
    assertThat(afterRecover.householdLaborMilli(OFFICIAL, 3L, CLOCK))
        .isEqualTo(Math.floorDiv(fullSatietyLabor * 870L, 1000L));
  }

  @Test
  void fullyFedHouseholdIsValueUnchangedAndSatietyTableStaysSparse() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    long baseLabor = GovZ6WorldFixture.socialSlice(base).householdLaborMilli(OFFICIAL, 1L, CLOCK);

    StateRunner runner = new StateRunner("Map1", base);
    runner.advance(0L, 1L);

    SocialData social = GovZ6WorldFixture.socialSlice(runner.state());
    assertThat(social.satietyPerMille(OFFICIAL)).as("吃饱户读回 1000（缺键语义）").isEqualTo(1000L);
    assertThat(social.satietyPerMille()).as("吃饱户不写冗余记录（稀疏表）").doesNotContainKey(OFFICIAL);
    assertThat(social.householdLaborMilli(OFFICIAL, 2L, CLOCK))
        .as("吃饱世界劳动折算逐值恒等")
        .isEqualTo(baseLabor);
    assertThat(GovZ6WorldFixture.economySlice(runner.state()).classes().get(OFFICIAL).laborMilli())
        .isEqualTo(baseLabor);
  }

  @Test
  void satietyWritebackIsIdempotentAndOneByTwoDaysEqualsTwoByOneDay() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    SocialData social = GovZ6WorldFixture.socialSlice(base);
    List<HouseholdSatietyBridge.DailyFeed> feeds =
        List.of(
            new HouseholdSatietyBridge.DailyFeed(OFFICIAL, 1_000L, 1_000L),
            new HouseholdSatietyBridge.DailyFeed(
                HouseholdId.parse("hh-0_0-urban-landlord"), 0L, 1_000L));

    HouseholdSatietyBridge.Report first = HouseholdSatietyBridge.apply(social, feeds);
    HouseholdSatietyBridge.Report second = HouseholdSatietyBridge.apply(social, feeds);
    assertThat(second.data()).as("同一 base+feeds 两次回写逐字段相等（幂等）").isEqualTo(first.data());
    assertThat(second.outcomes()).isEqualTo(first.outcomes());
    assertThat(first.data().satietyPerMille(OFFICIAL)).isEqualTo(850L);
    assertThat(first.data().satietyPerMille(HouseholdId.parse("hh-0_0-urban-landlord")))
        .isEqualTo(1000L);

    // 1×2 天 == 2×1 天：断粮世界分别"一次推进两天"与"两次推进一天"，satiety 与经济劳动逐值一致。
    SimulationState starved = withoutAnyGrain(base);
    StateRunner once = new StateRunner("Map1", starved);
    once.advance(0L, 2L);
    StateRunner twice = new StateRunner("Map1", starved);
    twice.advance(0L, 1L);
    twice.advance(1L, 2L);

    SocialData onceSocial = GovZ6WorldFixture.socialSlice(once.state());
    SocialData twiceSocial = GovZ6WorldFixture.socialSlice(twice.state());
    assertThat(onceSocial.satietyPerMille(OFFICIAL))
        .as("1×2 天 satiety")
        .isEqualTo(twiceSocial.satietyPerMille(OFFICIAL));
    assertThat(GovZ6WorldFixture.economySlice(once.state()).classes().get(OFFICIAL).laborMilli())
        .as("1×2 天经济劳动")
        .isEqualTo(
            GovZ6WorldFixture.economySlice(twice.state()).classes().get(OFFICIAL).laborMilli());
  }

  // ── 夹具 ──

  /** 清空全部家户的全部商品账户（断粮：无可贷余粮；布也清空但不参与 satiety 折算）。 */
  private static SimulationState withoutAnyGrain(SimulationState state) {
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    Map<HouseholdAccountKey, HouseholdInventory> inventories = new LinkedHashMap<>();
    for (Map.Entry<HouseholdAccountKey, HouseholdInventory> entry : actor.accounts().entrySet()) {
      HouseholdInventory row = entry.getValue();
      inventories.put(
          entry.getKey(),
          new HouseholdInventory(
              row.key(), Map.of(), row.money(), row.frozenBalances(), row.frozenMoney()));
    }
    return withInventories(state, actor, inventories);
  }

  /** 给某家户补粮（保留钱与冻结）。 */
  private static SimulationState withHouseholdGrain(
      SimulationState state, HouseholdId household, long grain) {
    ActorData actor = GovZ6WorldFixture.actorSlice(state);
    Map<HouseholdAccountKey, HouseholdInventory> inventories =
        new LinkedHashMap<>(actor.accounts());
    HouseholdAccountKey key = new HouseholdAccountKey(household);
    HouseholdInventory row = inventories.get(key);
    assertThat(row).as("补粮目标家户必须有账户: %s", household.value()).isNotNull();
    inventories.put(
        key,
        new HouseholdInventory(
            key, Map.of(GRAIN, grain), row.money(), row.frozenBalances(), row.frozenMoney()));
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
}
