package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ **人口—经济端到端夹具**（2026-09-26 从 {@code PopulationR4Test} 提取，**两处共用**）。
 *
 * <p>★ **为什么提取**（与 B1 的兜底同一条理由）：**同一个问题只能有一个答案**。 危机监控的相位回归（{@code
 * io.mosire.simos.app.crisis.CrisisMonitorPhaseTest}）需要与 R4 **完全相同**的"真播种器 + 真协调器"世界 ——
 * 复制一份必然漂，而这份夹具本身就有 60 余行。
 *
 * <p>★★ **它是真路径，不是替身**：批次由 {@link PopulationSeeder} 造、经济由 {@link EconomySeeder} 的真载荷经 {@link
 * EconomySeedHandler} 落盘、推进由真协调器 {@link PopulationEconomyTimeParticipant} 提案再经真变更集 apply —— 正是 Core
 * 做的事。
 *
 * <p>★ 夹具的"刻意混合"（承自 R4 brief 的硬要求）：(0,0) 平原格上有"供方富余 + 受方不足"的一对； (1,0) 沙漠格可耕地为 0 ⇒ **从第 1
 * 天起就吃不饱**，是"长期缺粮"的那一极。
 */
public final class PopulationEconomyFixture {

  /** 本夹具的世界 id（与其它夹具的世界不复用）。 */
  public static final String MAP_ID = "r4";

  /** 有农田的格（平原）：供方（农业行的纤维）与受方（同格织机的原料）都在这里。 */
  public static final HexCoord PLAINS = new HexCoord(0, 0);

  /** 沙漠格：可耕地系数 0 ⇒ 从第 1 天起就吃不饱 —— "长期缺粮"的那一极。 */
  public static final HexCoord DESERT = new HexCoord(1, 0);

  /** 每个格的创世人口。 */
  public static final long POPULATION = 1_000L;

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));

  private PopulationEconomyFixture() {}

  /**
   * 一份"世界"：两个格的批次 + 真播种器产出的经济状态 + **产权账本** + 当前世界日（{@code SocialData} 本身不带时刻）。
   *
   * <p>★ T5：第三片 {@code actor} —— 协调器（{@link PopulationEconomyTimeParticipant}）同时写三片，产出落 operator
   * 的账。
   */
  public record Fixture(SocialData social, EconomyData economy, ActorData actor, long tick) {}

  /** 创世（day 0）：平原格与沙漠格各有 1,000 人；经济侧由**真播种器**产出。 */
  public static Fixture seeded() {
    List<PopulationGroup> groups = new java.util.ArrayList<>();
    groups.addAll(PopulationSeeder.groups(plan(PLAINS), 0L));
    groups.addAll(PopulationSeeder.groups(plan(DESERT), 0L));
    SocialData social =
        new SocialData(
            Map.of(PLAINS, series(), DESERT, series()),
            Map.<CityId, io.mosire.simos.social.city.SocialCity>of(),
            index(groups));
    // ★★ H1：**一次算两条命令的载荷**（真路径 = 同批的 economy.Seed + actor.Seed）——
    //   economy 取 entries、actor 取同一份 householdStocks（家户的开缸库存只有一个拼写点）。
    EconomySeeder.Seed seeded =
        EconomySeeder.plan(MAP_ID, groups, at -> at.equals(DESERT) ? "desert" : "plains");
    String payload = seeded.economyPayload();
    SimulationState empty =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(0)),
            Map.of(
                "economy", snap(EconomyData.empty(), 0L),
                // ★ T5：actor 片必须在场（产权落账口要求它 —— 缺席 ⇒ 协调器当场抛）。
                // ★★ H1：家户 actor 与账本也要在创世就位（商品库存的唯一真源是 actor 侧的 GoodsAccount）
                //   —— 由**同一个 plan** 的开缸库存建（与真播种路径的 actor.Seed 同源）。
                "actor",
                    actorSnap(
                        // ★★ H5（⑤）：经营主体的开缸账（与家户同一次 plan）—— 少了它作坊开不起来。
                        HouseholdSeeder.books(
                            seeded.householdStocks(), seeded.householdMoney(), seeded.operators()),
                        0L)),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(empty, payload);
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyData economy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
    return new Fixture(
        social,
        economy,
        HouseholdSeeder.books(
            seeded.householdStocks(), seeded.householdMoney(), seeded.operators()),
        0L);
  }

  /** 从 {@code fixture} 推进 {@code days} 天：真协调器的提案 + 真变更集 apply（这正是 Core ④ 做的事）。 */
  public static Fixture advanced(Fixture fixture, long days) {
    long now = fixture.tick();
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(now)),
            Map.of(
                "economy", snap(fixture.economy(), now),
                "social", socialSnap(fixture.social(), now),
                "actor", actorSnap(fixture.actor(), now)),
            InMemoryInfoSystem.empty());
    WorldTimeProposal proposal =
        new PopulationEconomyTimeParticipant(MAP_ID)
            .simulateWorld(
                state,
                new TimeRange(SimosTimestamp.of(now), Optional.of(SimosTimestamp.of(now + days))));
    EconomyData nextEconomy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) proposal.moduleChanges().get("economy"), fixture.economy());
    SocialData nextSocial =
        SocialChangeSet.apply(
            (SocialChangeSet) proposal.moduleChanges().get("social"), fixture.social());
    ActorData nextActor =
        ActorChangeSet.apply(
            (ActorChangeSet) proposal.moduleChanges().get("actor"), fixture.actor());
    return new Fixture(nextSocial, nextEconomy, nextActor, now + days);
  }

  private static io.mosire.simos.social.gen.SettlementPlan plan(HexCoord hex) {
    return new io.mosire.simos.social.gen.SettlementPlan(
        Map.of(hex, POPULATION), List.of(), Map.of(), 250L, 0L);
  }

  private static Map<PeopleLotId, PopulationGroup> index(List<PopulationGroup> groups) {
    Map<PeopleLotId, PopulationGroup> byId = new LinkedHashMap<>();
    for (PopulationGroup group : groups) {
      byId.put(group.id(), group);
    }
    return byId;
  }

  private static PopulationSeries series() {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), 0L),
        new SegmentedSeries<Double>(
            List.of(new Segment<>(SimosTimestamp.of(0), 0.0)), List.of(), null),
        List.of());
  }

  private static Snapshot snap(EconomyData data, long tick) {
    return new EconomySnapshot(REF, SimosTimestamp.of(tick), data);
  }

  private static Snapshot socialSnap(SocialData data, long tick) {
    return new SocialSnapshot(REF, SimosTimestamp.of(tick), data);
  }

  private static Snapshot actorSnap(ActorData data, long tick) {
    return new ActorSnapshot(REF, SimosTimestamp.of(tick), data);
  }
}
