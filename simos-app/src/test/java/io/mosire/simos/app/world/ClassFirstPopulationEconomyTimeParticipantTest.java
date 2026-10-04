package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.time.ClassFirstPopulationEconomyTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MobilityPolicyId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstPilotEngine;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassFlowEvent;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.MobilityPolicy;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>R2c 的入口判据（三国世界）</b>：{@code economyProfile=class-first} 的真 worldgen 三国小世界，经<b>新参与者</b>
 * {@link ClassFirstPopulationEconomyTimeParticipant} + 真 {@code AdvanceTime} 推进：
 *
 * <ol>
 *   <li>{@link ClassFirstState} 真的前进（tick 增加、池库存/累计读数变化，不是空转）；
 *   <li><b>AccountDelta 守恒</b>：每个池每维度的 actor 家户账合计 == 池库存（池级增量逐值落进家户账）；放贷账户（三国多本 GOV）合计同理；
 *   <li><b>人口守恒/同步</b>：Σ池 == Σsocial 批次，且逐 {@code (格, 居住类型)} 对上；前 24 tick（无月度结算）人口恒定； 120 tick
 *       后人口因出生/死亡真的变化，social 与 classfirst 逐值一致；
 *   <li><b>classes 只读投影</b>：逐家户 population/laborMilli == classfirst 家户账户；
 *   <li><b>没有调用旧结算</b>：{@code economy.flows} / {@code industries} / {@code laborSupply} / {@code
 *       allocations} 在推进后仍为空（旧 {@code EconomyDayStepper} 会写这些表）。
 * </ol>
 *
 * <p>★ 数值直接打印，不建 Golden。
 */
class ClassFirstPopulationEconomyTimeParticipantTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:r2b-class-first-test";
  private static final long DAY_24 = 24L;
  private static final long DAY_120 = 120L;
  private static final long DAY_360 = 360L;
  private static final long DAY_30 = 30L;
  private static final long GM_UP_CAP_PER_MILLE_PER_TICK = 10L;
  private static final long GM_LEASE_AVAILABILITY_PER_MILLE = 800L;
  private static final String GM_INITIATOR = "agent:r3b-gm-classfirst";
  private static final BranchId CONTROL_BRANCH = new BranchId("r3b-baseline-control");

  @TempDir Path tempDir;

  @Test
  void classFirstWorldAdvancesThroughNewParticipantAndConserves() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("r2b-class-first-store"));
    ClassFirstPopulationEconomyTimeParticipant participant =
        new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID);
    try (CoreSimos core = classFirstCore(store, participant)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      CompactThreeNationsWorld.initializeNations(core, EconomySeeder.FoundationProfile.CLASS_FIRST);

      SimulationState seeded = core.replay(new StateRef(MAIN, new RevisionId(4L)));
      EconomyData economy0 = CompactThreeNationsWorld.economyOf(seeded);
      ActorData actor0 = CompactThreeNationsWorld.actorOf(seeded);
      SocialData social0 = CompactThreeNationsWorld.socialOf(seeded);
      assertThat(economy0.classFirst().isEmpty()).as("class-first 世界必须种出池").isFalse();
      long seedStockSignature = poolStockSignature(economy0.classFirst());
      assertAccountConservation(economy0, actor0, economy0, actor0, "seed-baseline");
      assertClassFirstConservation(economy0.classFirst(), "seed-baseline");
      assertNoNegativeActorBalances(actor0, "seed-baseline");

      // ★ range.to 缺省 ⇒ 三片都交**不变**变更集（不是空提案、不抛、不回退旧结算）。
      WorldTimeProposal noUpper =
          participant.simulateWorld(seeded, new TimeRange(SimosTimestamp.of(0L), Optional.empty()));
      assertThat(((EconomyChangeSet) noUpper.moduleChanges().get("economy")).isEmpty())
          .as("range.to 缺省：economy 不变")
          .isTrue();
      assertThat(((SocialChangeSet) noUpper.moduleChanges().get("social")).isEmpty())
          .as("range.to 缺省：social 不变")
          .isTrue();
      assertThat(((ActorChangeSet) noUpper.moduleChanges().get("actor")).isEmpty())
          .as("range.to 缺省：actor 不变")
          .isTrue();

      // ── ① 直接调参与者一天：提案形状 + 三片落账守恒（不经 Core，先逐值看账）────────────
      WorldTimeProposal day1 = participant.simulateWorld(seeded, range(0L, 1L));
      assertThat(day1.participantId()).isEqualTo("population");
      assertThat(day1.moduleChanges()).containsOnlyKeys("economy", "social", "actor");
      EconomyData economy1 =
          EconomyChangeSet.apply((EconomyChangeSet) day1.moduleChanges().get("economy"), economy0);
      ActorData actor1 =
          ActorChangeSet.apply((ActorChangeSet) day1.moduleChanges().get("actor"), actor0);
      SocialData social1 =
          SocialChangeSet.apply((SocialChangeSet) day1.moduleChanges().get("social"), social0);
      assertThat(economy1.classFirst().meta().tick()).as("单日提案推进 1 tick").isEqualTo(1L);
      assertNoOldSettlement(economy1, "day1");
      assertAccountConservation(economy0, actor0, economy1, actor1, "day1");
      assertPopulationConservation(economy0, social0, economy1, social1, 0L, "day1");
      assertSocialMatchesClassfirstByLocation(economy1, social1, "day1");
      assertClassesProjectionMatchesClassfirst(economy1, "day1");
      printReadings("TICK-1", economy1, actor1, social1);

      // ── ② 经 Core 真 AdvanceTime 0 → 24（一条 revision，内部逐日）────────────────────
      long revisionAt24 = advance(core, 0L, DAY_24);
      SimulationState at24 = core.replay(new StateRef(MAIN, new RevisionId(revisionAt24)));
      EconomyData economy24 = CompactThreeNationsWorld.economyOf(at24);
      ActorData actor24 = CompactThreeNationsWorld.actorOf(at24);
      SocialData social24 = CompactThreeNationsWorld.socialOf(at24);
      assertThat(economy24.classFirst().meta().tick())
          .as("24 tick 后 classFirst tick")
          .isEqualTo(DAY_24);
      assertNoOldSettlement(economy24, "tick24");
      assertAccountConservation(economy0, actor0, economy24, actor24, "tick24");
      assertClassFirstConservation(economy24.classFirst(), "tick24");
      assertNoNegativeActorBalances(actor24, "tick24");
      assertPopulationConservation(economy0, social0, economy24, social24, 0L, "tick24");
      assertSocialMatchesClassfirstByLocation(economy24, social24, "tick24");
      assertClassesProjectionMatchesClassfirst(economy24, "tick24");
      assertThat(poolStockSignature(economy24.classFirst()))
          .as("24 tick 内真的发生生产/消费/移动（池库存签名变化）")
          .isNotEqualTo(seedStockSignature);
      assertThat(economy24.classFirst().meta().totals().producedGrainTotal())
          .as("24 tick 内真的发生生产")
          .isPositive();
      assertThat(economy24.classFirst().meta().totals().rationConsumedTotal())
          .as("24 tick 内真的发生口粮消费")
          .isPositive();
      printReadings("TICK-24", economy24, actor24, social24);

      // ── ③ 继续经 Core 24 → 120（第二条 revision，累计 120 tick）──────────────────────
      long revisionAt120 = advance(core, DAY_24, DAY_120);
      SimulationState at120 = core.replay(new StateRef(MAIN, new RevisionId(revisionAt120)));
      EconomyData economy120 = CompactThreeNationsWorld.economyOf(at120);
      ActorData actor120 = CompactThreeNationsWorld.actorOf(at120);
      SocialData social120 = CompactThreeNationsWorld.socialOf(at120);
      assertThat(economy120.classFirst().meta().tick())
          .as("120 tick 后 classFirst tick")
          .isEqualTo(DAY_120);
      assertNoOldSettlement(economy120, "tick120");
      assertAccountConservation(economy0, actor0, economy120, actor120, "tick120");
      assertClassFirstConservation(economy120.classFirst(), "tick120");
      assertNoNegativeActorBalances(actor120, "tick120");
      long initialPopulation = poolPopulation(economy0.classFirst());
      long finalPopulation = poolPopulation(economy120.classFirst());
      assertThat(finalPopulation)
          .as("120 tick 后人口学真的改变了人口（出生 − 死亡；不是只换池）")
          .isGreaterThan(initialPopulation);
      assertThat(socialPopulation(social120))
          .as("Σsocial == Σclassfirst（出生/死亡两侧逐值同步）")
          .isEqualTo(finalPopulation);
      assertSocialMatchesClassfirstByLocation(economy120, social120, "tick120");
      assertClassesProjectionMatchesClassfirst(economy120, "tick120");
      assertThat(newbornLotCount(social120))
          .as("120 tick 内真的产生了出生批次（social 侧的新生 lot）")
          .isPositive();
      long changedLots = changedLotCount(social0, social120);
      System.out.println(
          "[R2C-SOCIAL-LOTS] changedCountLots="
              + changedLots
              + "/"
              + social120.groups().size()
              + " newbornLots="
              + newbornLotCount(social120)
              + " populationDelta="
              + (finalPopulation - initialPopulation));
      assertThat(changedLots).as("120 tick 的阶层移动/生死真的同步到了 social 批次（逐 lot 人数有变化）").isPositive();
      printReadings("TICK-120", economy120, actor120, social120);
    }
  }

  /**
   * ★★ <b>R3b：classfirst 三国 360 tick 验收（CompactThreeNationsWorld + 真 {@code AdvanceTime}）</b>。
   *
   * <p>0→360 分 12 段、每段 30 tick（每段落一条 revision，参与者内部仍逐日结算，语义与 0→120→360 两段相同）；每段末读 social 的 {@code
   * b<月>} 新生批次 ⇒ 出生数逐月精确；死亡数 = 上月末人口 + 本月出生 − 本月末人口（迁移不改总人口 ⇒ 差额只能是死亡）。tick 120（第 4 段末）用 {@code
   * submitRestore} 把 GM 政策源写进 {@code economy.classFirst.mobilityPolicies}（upCap
   * 1→10‰/tick、leaseAvailability 300→800‰）—— 那是 {@link
   * ClassFirstPilotEngine#restore(ClassFirstState)} 的权威面；随后 8 段继续推进。
   *
   * <p>判据与打印（读数直接打印 {@code [CLASSFIRST-360]}，不建 Golden）：4 池人口/A_C/x_C/r_up/r_down、UP/DOWN
   * 事件数、LandForSale 闭环（{@code landForSale == 初始 + Σ放地 − Σ购地}）、GM 调参前后迁移数、出生/死亡、粮/布/货币/土地/债务/债权守恒、
   * 旧投影 flows/industries/units 恒空。
   */
  @Test
  void classFirstThreeNationsRun360TicksAndGmKnobsBite() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("r3b-classfirst-360-store"));
    ClassFirstPopulationEconomyTimeParticipant participant =
        new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID);
    try (CoreSimos core = classFirstCore(store, participant)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      CompactThreeNationsWorld.initializeNations(core, EconomySeeder.FoundationProfile.CLASS_FIRST);
      SimulationState seeded = core.replay(new StateRef(MAIN, new RevisionId(4L)));
      EconomyData economy0 = CompactThreeNationsWorld.economyOf(seeded);
      ActorData actor0 = CompactThreeNationsWorld.actorOf(seeded);
      SocialData social0 = CompactThreeNationsWorld.socialOf(seeded);
      assertThat(economy0.classFirst().isEmpty()).as("class-first 世界必须种出池").isFalse();
      assertThat(economy0.classFirst().classPools()).as("三国合并后恰 4 池").hasSize(4);
      assertNoOldSettlement(economy0, "360-seed");
      assertClassFirstConservation(economy0.classFirst(), "360-seed");
      assertGrainClothConservation(economy0.classFirst(), "360-seed");
      assertAccountConservation(economy0, actor0, economy0, actor0, "360-seed");
      MobilityPolicy before = policyOf(economy0);
      System.out.println(
          "[CLASSFIRST-360] seed tick=0 pools="
              + economy0.classFirst().classPools().size()
              + " population="
              + poolPopulation(economy0.classFirst())
              + " upCap="
              + before.upCapPerMillePerTick()
              + "‰/tick leaseAvailability="
              + before.leaseAvailabilityPerMille()
              + "‰");

      // ── 0→120（4 段），第 4 段末做 GM 调参 ─────────────────────────────────────────────
      long population = poolPopulation(economy0.classFirst());
      long births = 0L;
      long deaths = 0L;
      SimulationState at120 = null;
      SimulationState at360 = null;
      EconomyData economy120 = null;
      for (int month = 1; month <= (int) (DAY_360 / DAY_30); month++) {
        long to = DAY_30 * month;
        long revision = advance(core, to - DAY_30, to);
        SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
        EconomyData economy = CompactThreeNationsWorld.economyOf(state);
        ActorData actor = CompactThreeNationsWorld.actorOf(state);
        SocialData social = CompactThreeNationsWorld.socialOf(state);
        assertNoOldSettlement(economy, "360-month" + month);
        assertClassFirstConservation(economy.classFirst(), "360-month" + month);
        assertGrainClothConservation(economy.classFirst(), "360-month" + month);
        assertAccountConservation(economy0, actor0, economy, actor, "360-month" + month);
        long now = poolPopulation(economy.classFirst());
        long monthBirths = bornPopulation(social, month);
        long monthDeaths = population + monthBirths - now;
        assertThat(monthDeaths).as("month %d：死亡数不得为负（出生批次在结算月之后才参与死亡）", month).isNotNegative();
        assertThat(socialPopulation(social))
            .as("month %d：Σsocial == Σclassfirst（逐日移动 + 月度生死都不丢人）", month)
            .isEqualTo(now);
        births += monthBirths;
        deaths += monthDeaths;
        population = now;
        if (month == 4) {
          at120 = state;
          economy120 = economy;
          ClassFirstPilotEngine engine120 = ClassFirstPilotEngine.restore(economy120.classFirst());
          long upPre = flowCount(economy120.classFirst(), PilotModel.Direction.UP, 0L, DAY_120);
          long downPre = flowCount(economy120.classFirst(), PilotModel.Direction.DOWN, 0L, DAY_120);
          System.out.println(
              "[CLASSFIRST-360] phase=pre-gm tick=120 pools="
                  + economy120.classFirst().classPools().size()
                  + " population="
                  + now
                  + " births="
                  + births
                  + " deaths="
                  + deaths
                  + " ownedLand="
                  + engine120.totalOwnedLand()
                  + " landForSale="
                  + engine120.landForSale()
                  + " leaseSupply="
                  + engine120.leaseSupply()
                  + " money="
                  + engine120.totalMoney()
                  + " debtMilli="
                  + engine120.totalDebtGrainMilli()
                  + " claimMilli="
                  + engine120.totalClaimGrainMilli()
                  + " accountNetSum="
                  + engine120.accountNetSum()
                  + " upEvents="
                  + upPre
                  + " downEvents="
                  + downPre
                  + " flowEventsTotal="
                  + economy120.classFirst().classFlowEvents().size());
          printPoolReadings("pre-gm", economy120.classFirst());
          // ★ 同态对照：从 tick 120 分岔一条**不改政策**的基线分支，稍后跑同样的 120→240 ——
          //   两次推进的起点逐字段相同，唯一差别就是 GM 政策，迁移数差异才有因果判别力。
          long head120 = core.head(MAIN).orElseThrow().value();
          CommandResult forked =
              core.submit(
                  new ForkBranch(
                      "cmd-r3b-fork-control",
                      "corr-r3b-fork-control",
                      GM_INITIATOR,
                      MAIN,
                      new RevisionId(head120),
                      CONTROL_BRANCH));
          assertThat(forked).as("基线对照分支必须分岔成功").isInstanceOf(CommandResult.Committed.class);
          // ★ GM 调参：只改源政策（upCap/leaseAvailability），不直接改池读数。
          MobilityPolicy raised =
              before
                  .withUpCapPerMillePerTick(GM_UP_CAP_PER_MILLE_PER_TICK)
                  .withLeaseAvailabilityPerMille(GM_LEASE_AVAILABILITY_PER_MILLE);
          long gmRevision = applyGmMobilityPolicy(core, economy120, raised);
          SimulationState adjusted = core.replay(new StateRef(MAIN, new RevisionId(gmRevision)));
          MobilityPolicy persisted = policyOf(CompactThreeNationsWorld.economyOf(adjusted));
          assertThat(persisted.upCapPerMillePerTick())
              .as("GM 写入后 upCap 立即生效（引擎 restore 的权威面）")
              .isEqualTo(GM_UP_CAP_PER_MILLE_PER_TICK);
          assertThat(persisted.leaseAvailabilityPerMille())
              .as("GM 写入后 leaseAvailability 立即生效")
              .isEqualTo(GM_LEASE_AVAILABILITY_PER_MILLE);
          System.out.println(
              "[CLASSFIRST-360] GM adjusted at tick=120: upCap "
                  + before.upCapPerMillePerTick()
                  + "‰ → "
                  + raised.upCapPerMillePerTick()
                  + "‰/tick, leaseAvailability "
                  + before.leaseAvailabilityPerMille()
                  + "‰ → "
                  + raised.leaseAvailabilityPerMille()
                  + "‰ (revision="
                  + gmRevision
                  + ")");
        }
        if (month == (int) (DAY_360 / DAY_30)) {
          at360 = state;
        }
      }
      assertThat(at120).as("tick 120 状态必须可得").isNotNull();
      assertThat(at360).as("tick 360 状态必须可得").isNotNull();

      // ── 同态对照：基线分支 120→240（与主分支同起点、同段数；唯一差别 = GM 政策）──────────────
      for (int month = 5; month <= 8; month++) {
        long to = DAY_30 * month;
        advance(core, CONTROL_BRANCH, to - DAY_30, to);
      }
      long controlHead = core.head(CONTROL_BRANCH).orElseThrow().value();
      SimulationState control240 =
          core.replay(new StateRef(CONTROL_BRANCH, new RevisionId(controlHead)));
      EconomyData controlEconomy = CompactThreeNationsWorld.economyOf(control240);
      assertThat(controlEconomy.classFirst().meta().tick()).as("对照分支也在 tick 240").isEqualTo(240L);
      assertClassFirstConservation(controlEconomy.classFirst(), "control-240");
      assertGrainClothConservation(controlEconomy.classFirst(), "control-240");
      assertThat(socialPopulation(CompactThreeNationsWorld.socialOf(control240)))
          .as("对照分支也保持 Σsocial == Σclassfirst")
          .isEqualTo(poolPopulation(controlEconomy.classFirst()));
      long upControl =
          flowCount(controlEconomy.classFirst(), PilotModel.Direction.UP, DAY_120, 240L);
      long downControl =
          flowCount(controlEconomy.classFirst(), PilotModel.Direction.DOWN, DAY_120, 240L);

      // ── 终局判据 ───────────────────────────────────────────────────────────────────
      EconomyData economy360 = CompactThreeNationsWorld.economyOf(at360);
      ActorData actor360 = CompactThreeNationsWorld.actorOf(at360);
      SocialData social360 = CompactThreeNationsWorld.socialOf(at360);
      assertThat(economy360.classFirst().meta().tick())
          .as("classfirst tick == 360")
          .isEqualTo(DAY_360);
      assertNoOldSettlement(economy360, "360-final");
      assertClassFirstConservation(economy360.classFirst(), "360-final");
      assertGrainClothConservation(economy360.classFirst(), "360-final");
      assertAccountConservation(economy0, actor0, economy360, actor360, "360-final");
      ClassFirstPilotEngine engine360 = ClassFirstPilotEngine.restore(economy360.classFirst());
      MobilityPolicy after = engine360.mobilityPolicy();
      assertThat(after.upCapPerMillePerTick()).isEqualTo(GM_UP_CAP_PER_MILLE_PER_TICK);
      assertThat(after.leaseAvailabilityPerMille()).isEqualTo(GM_LEASE_AVAILABILITY_PER_MILLE);

      long population360 = poolPopulation(economy360.classFirst());
      assertThat(socialPopulation(social360))
          .as("终局 Σsocial == Σclassfirst")
          .isEqualTo(population360);
      assertThat(population360)
          .as("终局人口 == 初始 + 出生 − 死亡（逐月差额的累计）")
          .isEqualTo(poolPopulation(economy0.classFirst()) + births - deaths);
      assertThat(births).as("360 tick 内真的发生出生").isPositive();
      assertThat(deaths).as("360 tick 内真的发生死亡（基础死亡率不等于 0）").isPositive();

      // UP/DOWN：总事件数 + GM 前后窗口（pre = ticks 1..120，post = ticks 121..360）
      long upTotal = flowCount(economy360.classFirst(), PilotModel.Direction.UP, 0L, DAY_360);
      long downTotal = flowCount(economy360.classFirst(), PilotModel.Direction.DOWN, 0L, DAY_360);
      long upPre = flowCount(economy360.classFirst(), PilotModel.Direction.UP, 0L, DAY_120);
      long downPre = flowCount(economy360.classFirst(), PilotModel.Direction.DOWN, 0L, DAY_120);
      long upPost = flowCount(economy360.classFirst(), PilotModel.Direction.UP, DAY_120, DAY_360);
      long downPost =
          flowCount(economy360.classFirst(), PilotModel.Direction.DOWN, DAY_120, DAY_360);
      assertThat(upTotal).as("360 tick 内应有向上迁移").isPositive();
      assertThat(downTotal).as("360 tick 内应有向下迁移").isPositive();
      assertThat(upPost * DAY_120)
          .as(
              "GM 调高 upCap/leaseAvailability 后，上行迁移的每 tick 速率应高于调参前（pre=%d/%d→post=%d/%d）",
              upPre, DAY_120, upPost, DAY_360 - DAY_120)
          .isGreaterThan(upPre * (DAY_360 - DAY_120));
      long upGmWindow = flowCount(economy360.classFirst(), PilotModel.Direction.UP, DAY_120, 240L);
      long downGmWindow =
          flowCount(economy360.classFirst(), PilotModel.Direction.DOWN, DAY_120, 240L);
      System.out.println(
          "[CLASSFIRST-360] GM controlled A/B (tick 120→240, same start state): GM up="
              + upGmWindow
              + " down="
              + downGmWindow
              + " vs baseline up="
              + upControl
              + " down="
              + downControl);
      assertThat(upGmWindow)
          .as(
              "同态对照：GM 调高 upCap/leaseAvailability 后 120→240 上行迁移数应严格高于基线（GM=%d，baseline=%d）",
              upGmWindow, upControl)
          .isGreaterThan(upControl);

      // LandForSale 闭环：landForSale == 初始 + Σ放地 − Σ购地（放地不消失，购地不凭空）
      long landToMarket = 0L;
      long landPurchased = 0L;
      for (ClassFlowEvent event : economy360.classFirst().classFlowEvents().values()) {
        landToMarket += event.bundle().landOwnershipToMarket();
        landPurchased += event.bundle().landPurchasedFromMarket();
      }
      assertThat(engine360.landForSale())
          .as(
              "LandForSale 闭环（初始 %d + 放地 %d − 购地 %d）",
              after.initialLandForSale(), landToMarket, landPurchased)
          .isEqualTo(after.initialLandForSale() + landToMarket - landPurchased);
      assertThat(landPurchased)
          .as("购地量不得超过市场上出现过的地（初始 + 放地）")
          .isLessThanOrEqualTo(after.initialLandForSale() + landToMarket);
      System.out.println(
          "[CLASSFIRST-360] landMarket closure: initialForSale="
              + after.initialLandForSale()
              + " + landToMarket="
              + landToMarket
              + " - landPurchased="
              + landPurchased
              + " == landForSaleEnd="
              + engine360.landForSale()
              + " (leaseSupplyEnd="
              + engine360.leaseSupply()
              + ")");

      printClassFirst360Final(
          economy360, social360, births, deaths, upPre, downPre, upPost, downPost);
      printPoolReadings("final", economy360.classFirst());

      // ── 读口收口：GUI/MCP 共用的 ApiViews.economyHex 从同一状态给出 class-first 权威读数，旧栏只留具名 unavailable ──
      Map<String, Object> hexView = ApiViews.economyHex(new HexCoord(0, 0), at360);
      Map<?, ?> classFirstReadout = (Map<?, ?>) hexView.get("classFirst");
      assertThat(classFirstReadout.get("available"))
          .as("class-first 已播种 ⇒ 读口 available=true")
          .isEqualTo(true);
      assertThat(classFirstReadout.get("scope").toString()).as("读数必须自述世界级").contains("世界级");
      List<?> poolReadouts = (List<?>) classFirstReadout.get("pools");
      assertThat(poolReadouts).as("4 池逐池可读").hasSize(4);
      for (Object poolReadoutObject : poolReadouts) {
        Map<?, ?> poolReadout = (Map<?, ?>) poolReadoutObject;
        assertThat(poolReadout.get("population")).as("池人口可读").isNotNull();
        assertThat(poolReadout.get("aMilli")).as("A_C 可读").isNotNull();
        assertThat(poolReadout.get("xMilli")).as("x_C 可读").isNotNull();
        assertThat(poolReadout.get("rateUpPerMillePerYear")).as("r_up 可读").isNotNull();
        assertThat(poolReadout.get("rateDownPerMillePerYear")).as("r_down 可读").isNotNull();
      }
      Map<?, ?> landMarketReadout = (Map<?, ?>) classFirstReadout.get("landMarket");
      assertThat(landMarketReadout.get("landBalanced"))
          .as("读口的 LandForSale 守恒读数与测试断言同源")
          .isEqualTo(true);
      Map<?, ?> accountsReadout = (Map<?, ?>) classFirstReadout.get("accounts");
      assertThat(accountsReadout.get("debtGrainMilli"))
          .as("读口：债务 == 债权")
          .isEqualTo(accountsReadout.get("claimGrainMilli"));
      Map<?, ?> flowReadout = (Map<?, ?>) classFirstReadout.get("classFlowEvents");
      assertThat(flowReadout.get("upCount")).as("读口 UP 计数 == 状态里的 UP 事件数").isEqualTo(upTotal);
      assertThat(flowReadout.get("downCount"))
          .as("读口 DOWN 计数 == 状态里的 DOWN 事件数")
          .isEqualTo(downTotal);
      List<?> policyReadouts = (List<?>) classFirstReadout.get("mobilityPolicies");
      assertThat(policyReadouts).as("GM 政策逐 mode 可读").hasSize(1);
      Map<?, ?> policyReadout = (Map<?, ?>) policyReadouts.get(0);
      assertThat(policyReadout.get("upCapPerMillePerTick"))
          .as("读口的 upCap 是 GM 写入后的值")
          .isEqualTo(GM_UP_CAP_PER_MILLE_PER_TICK);
      assertThat(policyReadout.get("leaseAvailabilityPerMille"))
          .as("读口的 leaseAvailability 是 GM 写入后的值")
          .isEqualTo(GM_LEASE_AVAILABILITY_PER_MILLE);
      Map<?, ?> conservationReadout = (Map<?, ?>) classFirstReadout.get("conservation");
      for (String key :
          List.of(
              "grainBalanced",
              "clothBalanced",
              "moneyBalanced",
              "landBalanced",
              "debtEqualsClaim",
              "accountNetZero")) {
        assertThat(conservationReadout.get(key)).as("读口守恒读数 %s", key).isEqualTo(true);
      }
      // 旧结算删除后恒 null 的栏位：保留具名 unavailable，且指向 class-first 的权威替代读数。
      assertThat(hexView.get("marketReadout")).isNull();
      assertThat(hexView.get("marketReadoutUnavailable").toString()).contains("classFirst");
      assertThat(hexView.get("classTransitions")).isNull();
      assertThat(hexView.get("classTransitionsUnavailable").toString()).contains("classFlowEvents");
      assertThat(hexView.get("entryOutcomes")).isNull();
      assertThat(hexView.get("arrears")).isNull();
      assertThat(hexView.get("arrearsUnavailable").toString()).contains("classFirst.accounts");
      assertThat(hexView.get("liquidationAudits")).isNull();
      assertThat(hexView.get("liquidationAuditsUnavailable").toString())
          .contains("classFirst.totals");
      System.out.println(
          "[CLASSFIRST-360] readout ApiViews.economyHex: classFirst.pools="
              + poolReadouts.size()
              + " flowEvents="
              + flowReadout.get("count")
              + " up="
              + flowReadout.get("upCount")
              + " down="
              + flowReadout.get("downCount")
              + " accounts="
              + accountsReadout.get("count")
              + " landBalanced="
              + landMarketReadout.get("landBalanced")
              + " mobilityPolicies="
              + policyReadouts.size()
              + " (marketReadout/classTransitions/entryOutcomes/arrears/liquidationAudits 具名 unavailable)");
    }
  }

  // ── 判据 ────────────────────────────────────────────────────────────────────────────

  /**
   * 旧结算若被调用，会写 {@code flows}/{@code industries}/{@code laborSupply}/{@code allocations}；新路径四表恒空。
   */
  private static void assertNoOldSettlement(EconomyData economy, String tag) {
    assertThat(economy.flows()).as("%s: class-first 路径不得写旧 FlowRow（旧结算的痕迹）", tag).isEmpty();
    assertThat(economy.industries()).as("%s: class-first 世界没有旧 industries", tag).isEmpty();
    assertThat(economy.relations()).as("%s: class-first 路径不得写旧 relations", tag).isEmpty();
    assertThat(economy.units()).as("%s: class-first 路径不得写旧 units", tag).isEmpty();
    assertThat(economy.laborSupply()).as("%s: class-first 路径不得写旧 laborSupply", tag).isEmpty();
    assertThat(economy.allocations()).as("%s: class-first 路径不得写旧 allocations", tag).isEmpty();
  }

  /** ★ R2c：土地/货币/债务守恒 + 无负库存/负人口（classfirst 权威侧 + actor 账本侧）。 */
  private static void assertClassFirstConservation(ClassFirstState state, String tag) {
    ClassFirstPilotEngine engine = ClassFirstPilotEngine.restore(state);
    System.out.println(
        "[R2C-CONSERVATION] "
            + tag
            + " ownedLand="
            + engine.totalOwnedLand()
            + " landForSale="
            + engine.landForSale()
            + " totalMoney="
            + engine.totalMoney()
            + " householdMoney="
            + engine.totalHouseholdMoney()
            + " lenderMoney="
            + engine.totalLenderMoney()
            + " debtMilli="
            + engine.totalDebtGrainMilli()
            + " claimMilli="
            + engine.totalClaimGrainMilli()
            + " accountNetSum="
            + engine.accountNetSum());
    assertThat(engine.totalOwnedLand() + engine.landForSale())
        .as("%s: 土地守恒（Σ池 OWNED_LAND + LandForSale == 创世初始）", tag)
        .isEqualTo(engine.initialOwnedLandTotal());
    assertThat(engine.totalMoney())
        .as("%s: 货币守恒（Σ池 + 放贷窗口 + 托管 == 创世初始家户+放贷）", tag)
        .isEqualTo(engine.initialHouseholdMoneyTotal() + engine.initialLenderMoneyTotal());
    assertThat(engine.accountNetSum()).as("%s: 双边账户净额之和恒为 0", tag).isZero();
    assertThat(engine.totalDebtGrainMilli())
        .as("%s: 债务 == 债权（双边记账）", tag)
        .isEqualTo(engine.totalClaimGrainMilli());
    for (ClassPool pool : state.classPools().values()) {
      assertThat(pool.population())
          .as("%s: 池人口不得为负: %s", tag, pool.classPositionId())
          .isNotNegative();
      assertThat(pool.debtGrainMilli()).as("%s: 池欠额不得为负", tag).isNotNegative();
      for (AssetKind kind : AssetKind.ordered()) {
        assertThat(pool.stock(kind))
            .as("%s: %s.%s 不得为负", tag, pool.classPositionId(), kind)
            .isNotNegative();
      }
    }
    for (var lender : state.lenders().values()) {
      assertThat(lender.money()).as("%s: 放贷窗口资金不得为负", tag).isNotNegative();
    }
  }

  /** ★ R2c：actor 全部余额（含冻结）不得为负 —— 读侧复核落账没有偷偷透支。 */
  private static void assertNoNegativeActorBalances(ActorData actor, String tag) {
    for (GoodsAccount book : actor.accounts().values()) {
      for (long value : book.balances().values()) {
        assertThat(value).as("%s: actor 商品余额不得为负: %s", tag, book.key()).isNotNegative();
      }
      for (long value : book.frozenBalances().values()) {
        assertThat(value).as("%s: actor 冻结商品不得为负: %s", tag, book.key()).isNotNegative();
      }
      for (long value : book.money().values()) {
        assertThat(value).as("%s: actor 货币余额不得为负: %s", tag, book.key()).isNotNegative();
      }
      for (long value : book.frozenMoney().values()) {
        assertThat(value).as("%s: actor 冻结货币不得为负: %s", tag, book.key()).isNotNegative();
      }
    }
  }

  /**
   * ★★ AccountDelta 守恒（逐池绝对对账 + 全局增量对账）：
   *
   * <ol>
   *   <li>每个池每维度：Σ该池家户 actor 账 == 池库存（粮/布/货币逐池）；
   *   <li>全局：Σ池 + 放贷账户的每个维度增量 == Σactor 账的同一维度增量（池/放贷的 AccountDelta 全部落进 actor 账）。
   * </ol>
   */
  private static void assertAccountConservation(
      EconomyData beforeEconomy,
      ActorData beforeActor,
      EconomyData afterEconomy,
      ActorData afterActor,
      String tag) {
    ClassFirstState before = beforeEconomy.classFirst();
    ClassFirstState after = afterEconomy.classFirst();
    Map<ClassPoolId, long[]> booksByPool = actorHouseholdTotals(afterEconomy, after, afterActor);
    for (Map.Entry<ClassPoolId, ClassPool> entry : after.classPools().entrySet()) {
      long[] books = booksByPool.get(entry.getKey());
      assertThat(books).as("%s: 池 %s 的家户账合计必须可得", tag, entry.getKey()).isNotNull();
      assertThat(books[0])
          .as("%s: %s 粮 actor 合计 == 池库存", tag, entry.getKey())
          .isEqualTo(entry.getValue().stock(AssetKind.GRAIN));
      assertThat(books[1])
          .as("%s: %s 布 actor 合计 == 池库存", tag, entry.getKey())
          .isEqualTo(entry.getValue().stock(AssetKind.CLOTH));
      assertThat(books[2])
          .as("%s: %s 货币 actor 合计 == 池库存", tag, entry.getKey())
          .isEqualTo(entry.getValue().stock(AssetKind.MONEY));
    }

    for (String commodity : List.of(grainName(), clothName())) {
      long actorDelta =
          actorCommodityTotal(afterActor, commodity) - actorCommodityTotal(beforeActor, commodity);
      long poolDelta = poolCommodityTotal(after, commodity) - poolCommodityTotal(before, commodity);
      assertThat(actorDelta)
          .as("%s: %s 的 Σactor 增量 == Σ池增量（AccountDelta 全落账）", tag, commodity)
          .isEqualTo(poolDelta);
    }
    long actorMoneyDelta = actorMoneyTotal(afterActor) - actorMoneyTotal(beforeActor);
    long stateMoneyDelta =
        (poolMoneyTotal(after) - poolMoneyTotal(before))
            + (lenderMoneyTotal(after) - lenderMoneyTotal(before));
    assertThat(actorMoneyDelta)
        .as("%s: 货币 Σactor 增量 == Σ池 + 放贷窗口增量", tag)
        .isEqualTo(stateMoneyDelta);
  }

  /**
   * 人口守恒：{@code Σclassfirst == Σsocial} 逐值成立；若给定期望变化量，还断言两侧都恰好变化该值。
   *
   * <p>★ R2c 起总人口不再恒定：每 30 天出生/死亡会改两侧人数；移动只换池/换地点。故"移动窗口"传 {@code 0L}， 人口学窗口传 {@code null}
   * 并由调用方另判读。
   */
  private static void assertPopulationConservation(
      EconomyData beforeEconomy,
      SocialData beforeSocial,
      EconomyData afterEconomy,
      SocialData afterSocial,
      Long expectedDelta,
      String tag) {
    long beforePoolPopulation = poolPopulation(beforeEconomy.classFirst());
    long afterPoolPopulation = poolPopulation(afterEconomy.classFirst());
    long beforeSocialPopulation = socialPopulation(beforeSocial);
    long afterSocialPopulation = socialPopulation(afterSocial);
    if (expectedDelta != null) {
      assertThat(afterPoolPopulation - beforePoolPopulation)
          .as("%s: classfirst 人口变化量 == 期望（0 = 纯移动窗口）", tag)
          .isEqualTo(expectedDelta);
      assertThat(afterSocialPopulation - beforeSocialPopulation)
          .as("%s: social 人口变化量 == 期望", tag)
          .isEqualTo(expectedDelta);
    }
    assertThat(afterSocialPopulation)
        .as("%s: Σsocial == Σclassfirst（逐日移动与出生/死亡都不丢人）", tag)
        .isEqualTo(afterPoolPopulation);
  }

  /**
   * ★ R2c：classes 只是 classfirst 家户账户的只读投影 —— 逐 household 断言 {@code population/laborMilli} 与账户一致。
   */
  private static void assertClassesProjectionMatchesClassfirst(EconomyData economy, String tag) {
    for (HouseholdProductionAccount account : economy.classFirst().householdAccounts().values()) {
      ClassRow row = economy.classes().get(HouseholdId.parse(account.householdId()));
      assertThat(row).as("%s: 家户生产账户必须在 classes 投影里: %s", tag, account.householdId()).isNotNull();
      assertThat(row.population())
          .as("%s: classes.population == 家户账户人口: %s", tag, account.householdId())
          .isEqualTo(account.population());
      assertThat(row.laborMilli())
          .as("%s: classes.laborMilli == 家户账户 laborUnits: %s", tag, account.householdId())
          .isEqualTo(account.laborUnits());
    }
  }

  /** 新生批次数（social 侧的 lot 细分以 {@code b<月>} 结尾 ⇒ 只可能是出生批次）。 */
  private static long newbornLotCount(SocialData social) {
    long births = 0L;
    for (PopulationGroup group : social.groups().values()) {
      String value = group.id().value();
      int lastColon = value.lastIndexOf(':');
      if (lastColon >= 0 && value.substring(lastColon + 1).startsWith("b")) {
        births++;
      }
    }
    return births;
  }

  /**
   * ★ 更强的一条：逐个 {@code (格, 居住类型)} 对账 —— Σ该处 social 批次人数 == Σ该处 classfirst 家户生产账户人口（经 {@code
   * economy.classes} 的 view 定位）。它把"同步只保了总数"这种假绿挡在外面。
   */
  private static void assertSocialMatchesClassfirstByLocation(
      EconomyData economy, SocialData social, String tag) {
    Map<String, Long> classfirstByLocation = new LinkedHashMap<>();
    for (HouseholdProductionAccount account : economy.classFirst().householdAccounts().values()) {
      ClassRow row = economy.classes().get(HouseholdId.parse(account.householdId()));
      assertThat(row).as("%s: 家户生产账户必须在 classes 视图里: %s", tag, account.householdId()).isNotNull();
      classfirstByLocation.merge(
          locationKey(row.view().hex().toString(), row.view().residence().value()),
          account.population(),
          Long::sum);
    }
    Map<String, Long> socialByLocation = new LinkedHashMap<>();
    for (PopulationGroup group : social.groups().values()) {
      socialByLocation.merge(
          locationKey(
              social
                  .hexOfLot(group.id())
                  .orElseThrow(() -> new IllegalStateException("批次没有位置: " + group.id()))
                  .toString(),
              residenceOf(group).value()),
          group.count(),
          Long::sum);
    }
    assertThat(socialByLocation)
        .as("%s: social 的 (格, 居住类型) 键集必须与 classfirst 家户一致", tag)
        .containsExactlyInAnyOrderEntriesOf(classfirstByLocation);
  }

  private static String locationKey(String hex, String residence) {
    return hex + "|" + residence;
  }

  private static ResidenceKind residenceOf(PopulationGroup group) {
    return ResidenceKind.ofLot(group.id());
  }

  // ── 账本读数 ────────────────────────────────────────────────────────────────────────

  /** 逐池 actor 家户账合计：[粮, 布, 货币]（operator 壳/GOV 账户不属任何池，不计）。 */
  private static Map<ClassPoolId, long[]> actorHouseholdTotals(
      EconomyData economy, ClassFirstState state, ActorData actor) {
    Map<ClassPoolId, long[]> totals = new LinkedHashMap<>();
    for (HouseholdProductionAccount account : state.householdAccounts().values()) {
      HouseholdId householdId = HouseholdId.parse(account.householdId());
      ClassRow row = economy.classes().get(householdId);
      assertThat(row).as("池成员必须在 classes 视图里: %s", householdId).isNotNull();
      GoodsAccount book =
          actor
              .accounts()
              .get(new GoodsAccountKey(HouseholdActors.of(householdId), row.view().hex()));
      assertThat(book).as("池成员必须有 actor 家户账: %s", householdId).isNotNull();
      long[] sums = totals.computeIfAbsent(account.poolId(), ignored -> new long[3]);
      sums[0] += book.balances().getOrDefault(new CommodityId(grainName()), 0L);
      sums[1] += book.balances().getOrDefault(new CommodityId(clothName()), 0L);
      for (long value : book.money().values()) {
        sums[2] += value;
      }
    }
    return totals;
  }

  private static long actorCommodityTotal(ActorData actor, String commodity) {
    CommodityId id = new CommodityId(commodity);
    long total = 0L;
    for (GoodsAccount book : actor.accounts().values()) {
      total += book.balances().getOrDefault(id, 0L);
    }
    return total;
  }

  private static long actorMoneyTotal(ActorData actor) {
    long total = 0L;
    for (GoodsAccount book : actor.accounts().values()) {
      for (long value : book.money().values()) {
        total += value;
      }
    }
    return total;
  }

  /** 世界级 GOV 汇总：三国 seed 各一本（同一 owner、不同格）⇒ 逐本求和，不要求唯一。 */
  private static String governmentSummary(ActorData actor) {
    long money = 0L;
    long accounts = 0L;
    StringBuilder goods = new StringBuilder();
    for (GoodsAccount book : actor.accounts().values()) {
      if (book.key().owner().kind() != ActorKind.GOVERNMENT) {
        continue;
      }
      accounts++;
      money += book.money().values().stream().mapToLong(Long::longValue).sum();
      if (!book.balances().isEmpty()) {
        goods.append(book.balances());
      }
    }
    return "accounts=" + accounts + " money=" + money + " goods=" + goods;
  }

  private static long poolCommodityTotal(ClassFirstState state, String commodity) {
    AssetKind kind = grainName().equals(commodity) ? AssetKind.GRAIN : AssetKind.CLOTH;
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.stock(kind);
    }
    return total;
  }

  private static long poolMoneyTotal(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.stock(AssetKind.MONEY);
    }
    return total;
  }

  private static long lenderMoneyTotal(ClassFirstState state) {
    long total = 0L;
    for (PilotModel.Lender lender : state.lenders().values()) {
      total += lender.money();
    }
    return total;
  }

  private static long poolPopulation(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.population();
    }
    return total;
  }

  private static long poolStockSignature(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      for (AssetKind kind : AssetKind.ordered()) {
        if (kind.stock()) {
          total += pool.stock(kind);
        }
      }
    }
    return total;
  }

  private static long socialPopulation(SocialData social) {
    long total = 0L;
    for (PopulationGroup group : social.groups().values()) {
      total += group.count();
    }
    return total;
  }

  /** 人数有变化的 social 批次数（证明同步真的落到了逐 lot 上，而不只是总数守恒）。 */
  private static long changedLotCount(SocialData before, SocialData after) {
    long changed = 0L;
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : after.groups().entrySet()) {
      PopulationGroup seed = before.groups().get(entry.getKey());
      if (seed == null || seed.count() != entry.getValue().count()) {
        changed++;
      }
    }
    return changed;
  }

  /** classfirst 商品维度名 —— 与 {@code PilotModel.GRAIN}/{@code CLOTH} 同值（测试侧只写一次）。 */
  private static String grainName() {
    return PilotModel.GRAIN;
  }

  private static String clothName() {
    return PilotModel.CLOTH;
  }

  private static void printReadings(
      String tag, EconomyData economy, ActorData actor, SocialData social) {
    ClassFirstState state = economy.classFirst();
    StringBuilder pools = new StringBuilder();
    for (ClassPool pool : state.classPools().values()) {
      pools
          .append('\n')
          .append("  [R2B-POOL] ")
          .append(tag)
          .append(' ')
          .append(pool.classPositionId())
          .append(" population=")
          .append(pool.population())
          .append(" labor=")
          .append(pool.labor())
          .append(" grain=")
          .append(pool.stock(AssetKind.GRAIN))
          .append(" cloth=")
          .append(pool.stock(AssetKind.CLOTH))
          .append(" money=")
          .append(pool.stock(AssetKind.MONEY))
          .append(" land=")
          .append(pool.stock(AssetKind.OWNED_LAND))
          .append(" tools=")
          .append(pool.stock(AssetKind.TOOLS));
    }
    String gov = governmentSummary(actor);
    System.out.println(
        "[R2B-"
            + tag
            + "] tick="
            + state.meta().tick()
            + " pools="
            + state.classPools().size()
            + " households="
            + state.householdAccounts().size()
            + " poolPopulation="
            + poolPopulation(state)
            + " socialPopulation="
            + socialPopulation(social)
            + " actorGrain="
            + actorCommodityTotal(actor, grainName())
            + " actorMoney="
            + actorMoneyTotal(actor)
            + " lenderMoney="
            + lenderMoneyTotal(state)
            + " producedGrain="
            + state.meta().totals().producedGrainTotal()
            + " rationConsumed="
            + state.meta().totals().rationConsumedTotal()
            + " clothConsumed="
            + state.meta().totals().clothConsumedTotal()
            + " classFlowEvents="
            + state.classFlowEvents().size()
            + " GOV {"
            + gov
            + "}"
            + pools);
  }

  /** 当前 mode 生效的 GM 政策（engine restore 的权威面 = state.mobilityPolicies）。 */
  private static MobilityPolicy policyOf(EconomyData economy) {
    ClassFirstState state = economy.classFirst();
    assertThat(state.meta().config()).as("class-first 非空态必须带 meta.config").isNotNull();
    MobilityPolicy policy =
        state.mobilityPolicies().get(MobilityPolicyId.of(state.meta().config().mode().id()));
    assertThat(policy).as("class-first 状态必须带当前 mode 的 mobility policy").isNotNull();
    return policy;
  }

  /**
   * GM 政策源写入：重建 {@code mobilityPolicies} 后经 Core 的 {@code submitRestore} 落一条 revision；返回新 revision。
   */
  private static long applyGmMobilityPolicy(
      CoreSimos core, EconomyData economy, MobilityPolicy policy) {
    ClassFirstState state = economy.classFirst();
    Map<MobilityPolicyId, MobilityPolicy> policies = new LinkedHashMap<>(state.mobilityPolicies());
    policies.put(MobilityPolicyId.of(state.meta().config().mode().id()), policy);
    ClassFirstState adjusted =
        new ClassFirstState(
            state.modeParticipations(),
            state.classPools(),
            state.householdAccounts(),
            state.assetStateSchemas(),
            state.classBounds(),
            policies,
            state.classFlowEvents(),
            state.accounts(),
            state.lenders(),
            state.meta());
    EconomyChangeSet changeSet =
        EconomyChangeSet.between(economy, economy.withClassFirst(adjusted));
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submitRestore(
            MAIN,
            new RevisionId(head),
            GM_INITIATOR,
            new WorldChangeSet(Map.of("economy", changeSet)));
    assertThat(result)
        .as("GM 政策源写入必须落一条 revision")
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1L))));
    return head + 1L;
  }

  /** 窗口内的 UP/DOWN 迁移事件数（from 开区间、to 闭区间；事件 tick 落在窗口内的才计数）。 */
  private static long flowCount(
      ClassFirstState state, PilotModel.Direction direction, long fromExclusive, long toInclusive) {
    long count = 0L;
    for (ClassFlowEvent event : state.classFlowEvents().values()) {
      if (event.direction() == direction
          && event.tick() > fromExclusive
          && event.tick() <= toInclusive) {
        count++;
      }
    }
    return count;
  }

  /** 第 {@code month} 月结算产生的出生人数：social 里 id 末段 = {@code b<month>} 的批次总人数（结算当月即精确读取）。 */
  private static long bornPopulation(SocialData social, long month) {
    String cohort = "b" + month;
    long total = 0L;
    for (PopulationGroup group : social.groups().values()) {
      String value = group.id().value();
      int lastColon = value.lastIndexOf(':');
      if (lastColon >= 0 && cohort.equals(value.substring(lastColon + 1))) {
        total += group.count();
      }
    }
    return total;
  }

  /** ★ R3b：粮/布守恒（与 {@code ClassFirstPilotEngineTest} 的 360 tick 判据同式）。 */
  private static void assertGrainClothConservation(ClassFirstState state, String tag) {
    ClassFirstPilotEngine engine = ClassFirstPilotEngine.restore(state);
    assertThat(engine.initialGrainTotal() + engine.producedGrainTotal())
        .as("%s: 粮守恒（初始 + 生产 == Σ池+放贷+托管 库存 − 留种 − 口粮）", tag)
        .isEqualTo(engine.totalGrain() + engine.seedUsedTotal() + engine.rationConsumedTotal());
    assertThat(engine.initialClothTotal())
        .as("%s: 布守恒（初始 == Σ池+放贷 库存 − 布消费）", tag)
        .isEqualTo(engine.totalCloth() + engine.clothConsumedTotal());
  }

  /** 逐池打印人口/库存/A_C/x_C/r_up/r_down（A/x/r 与结算引擎同源：schema/bounds/policy 现算）。 */
  private static void printPoolReadings(String tag, ClassFirstState state) {
    MobilityPolicy policy = state.mobilityPolicies().values().iterator().next();
    long moneyPerGrain = state.meta().config().moneyPerGrain();
    for (ClassPool pool : state.classPools().values()) {
      long aMilli = policy.schema().aMilli(pool, moneyPerGrain);
      long xMilli = policy.bounds().xMilli(pool.classPositionId(), aMilli);
      System.out.println(
          "[CLASSFIRST-360]   "
              + tag
              + " pool="
              + pool.classPositionId()
              + " population="
              + pool.population()
              + " labor="
              + pool.labor()
              + " grain="
              + pool.stock(AssetKind.GRAIN)
              + " cloth="
              + pool.stock(AssetKind.CLOTH)
              + " money="
              + pool.stock(AssetKind.MONEY)
              + " ownedLand="
              + pool.stock(AssetKind.OWNED_LAND)
              + " tools="
              + pool.stock(AssetKind.TOOLS)
              + " debtMilli="
              + pool.debtGrainMilli()
              + " leaseHolding="
              + pool.leaseHolding()
              + " A_C="
              + aMilli
              + " x_C="
              + xMilli
              + " r_up="
              + policy.rateUpPerMillePerYear(xMilli)
              + " r_down="
              + policy.rateDownPerMillePerYear(xMilli));
    }
  }

  /** 360 tick 终局一行汇总（守恒读数 + GM 前后迁移 + 旧投影条数）。 */
  private static void printClassFirst360Final(
      EconomyData economy,
      SocialData social,
      long births,
      long deaths,
      long upPre,
      long downPre,
      long upPost,
      long downPost) {
    ClassFirstState state = economy.classFirst();
    ClassFirstPilotEngine engine = ClassFirstPilotEngine.restore(state);
    MobilityPolicy policy = engine.mobilityPolicy();
    System.out.println(
        "[CLASSFIRST-360] final tick="
            + state.meta().tick()
            + " pools="
            + state.classPools().size()
            + " households="
            + state.householdAccounts().size()
            + " population="
            + engine.totalPopulation()
            + " socialPopulation="
            + socialPopulation(social)
            + " births="
            + births
            + " deaths="
            + deaths
            + " upEvents="
            + flowCount(state, PilotModel.Direction.UP, 0L, DAY_360)
            + " downEvents="
            + flowCount(state, PilotModel.Direction.DOWN, 0L, DAY_360)
            + " upPre120="
            + upPre
            + " downPre120="
            + downPre
            + " upPost240="
            + upPost
            + " downPost240="
            + downPost
            + " ownedLand="
            + engine.totalOwnedLand()
            + " landForSale="
            + engine.landForSale()
            + " leaseSupply="
            + engine.leaseSupply()
            + " grainStock="
            + engine.totalGrain()
            + " clothStock="
            + engine.totalCloth()
            + " householdMoney="
            + engine.totalHouseholdMoney()
            + " lenderMoney="
            + engine.totalLenderMoney()
            + " escrowMoney="
            + engine.landMarketEscrowMoney()
            + " totalMoney="
            + engine.totalMoney()
            + " debtMilli="
            + engine.totalDebtGrainMilli()
            + " claimMilli="
            + engine.totalClaimGrainMilli()
            + " accountNetSum="
            + engine.accountNetSum()
            + " producedGrain="
            + engine.producedGrainTotal()
            + " rationConsumed="
            + engine.rationConsumedTotal()
            + " clothConsumed="
            + engine.clothConsumedTotal()
            + " collectionEvents="
            + engine.collectionEventCount()
            + " capitalized="
            + engine.capitalizedTotal()
            + " landSeized="
            + engine.landSeizedTotal()
            + " upCap="
            + policy.upCapPerMillePerTick()
            + " leaseAvailability="
            + policy.leaseAvailabilityPerMille()
            + " oldProjections{flows="
            + economy.flows().size()
            + ", industries="
            + economy.industries().size()
            + ", units="
            + economy.units().size()
            + ", relations="
            + economy.relations().size()
            + ", laborSupply="
            + economy.laborSupply().size()
            + ", allocations="
            + economy.allocations().size()
            + "}");
  }

  // ── 装配 ────────────────────────────────────────────────────────────────────────────

  /** 真 worldgen 的 core（与 {@code ClassFirstEconomySeedTest.worldgenCore} 同源）+ R2b 新参与者（封存前注册）。 */
  private static CoreSimos classFirstCore(
      Path storeDir, ClassFirstPopulationEconomyTimeParticipant participant) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (ModuleCodec codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec(),
            new ActorCodec())) {
      core.register(codec);
    }
    for (CommandHandler handler :
        List.of(
            new SetPopulationHandler(),
            new CreateCityHandler(),
            new SeedGroupsHandler(),
            new EconomySeedHandler(),
            new ActorSeedHandler(),
            new UpdateRegionHandler(),
            new CreateNationHandler(),
            new CreateUnitHandler(),
            new CreateCommandChainHandler(),
            new CreateArmyHandler())) {
      core.register(handler);
    }
    core.register(participant);
    return core;
  }

  /** 一次 {@code AdvanceTime}（一条 revision，内部由参与者逐日跑）；返回新 revision。 */
  private static long advance(CoreSimos core, long from, long to) {
    return advance(core, MAIN, from, to);
  }

  /** 指定分支的一次 {@code AdvanceTime}（对照分支与主分支走同一条语义）。 */
  private static long advance(CoreSimos core, BranchId branch, long from, long to) {
    long head = core.head(branch).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-advance-" + branch.value() + "-" + from + "-" + to,
                "corr-advance-" + branch.value() + "-" + from + "-" + to,
                INITIATOR,
                branch,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进 %d → %d 天只落一条 revision（branch=%s）", from, to, branch.value())
        .isEqualTo(new CommandResult.Committed(new StateRef(branch, new RevisionId(head + 1L))));
    return head + 1L;
  }

  private static TimeRange range(long from, long to) {
    return new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)));
  }
}
