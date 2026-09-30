package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.ClassFirstPopulationEconomyTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.social.SocialData;
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
 * ★★ <b>R2b 的入口判据</b>：{@code economyProfile=class-first} 的真 worldgen 小世界，经<b>新参与者</b> {@link
 * ClassFirstPopulationEconomyTimeParticipant} + 真 {@code AdvanceTime} 推进：
 *
 * <ol>
 *   <li>{@link ClassFirstState} 真的前进（tick 增加、池库存/累计读数变化，不是空转）；
 *   <li><b>AccountDelta 守恒</b>：每个池每维度的 actor 家户账合计 == 池库存（池级增量逐值落进家户账）；放贷账户同理；
 *   <li><b>人口守恒</b>：Σ池 == classfirst 初始总人口 == Σsocial 批次（逐日移动同步，不丢人）；
 *   <li><b>没有调用旧结算</b>：{@code economy.flows} / {@code industries} / {@code laborSupply} / {@code
 *       allocations} 在推进后仍为空（旧 {@code EconomyDayStepper} 会写这些表）。
 * </ol>
 *
 * <p>★ 它<b>不</b>测出生/死亡（R2b 明说"人口学暂未接"，见参与者类注）；数值直接打印，不建 Golden。
 */
class ClassFirstPopulationEconomyTimeParticipantTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:r2b-class-first-test";
  private static final long DAY_24 = 24L;
  private static final long DAY_120 = 120L;

  @TempDir Path tempDir;

  @Test
  void classFirstWorldAdvancesThroughNewParticipantAndConserves() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("r2b-class-first-store"));
    ClassFirstPopulationEconomyTimeParticipant participant =
        new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID);
    try (CoreSimos core = classFirstCore(store, participant)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      CompactThreeNationsWorld.initializeNation(
          core, CompactThreeNationsWorld.GRANARY, EconomySeeder.FoundationProfile.CLASS_FIRST);

      SimulationState seeded = core.replay(new StateRef(MAIN, new RevisionId(2L)));
      EconomyData economy0 = CompactThreeNationsWorld.economyOf(seeded);
      ActorData actor0 = CompactThreeNationsWorld.actorOf(seeded);
      SocialData social0 = CompactThreeNationsWorld.socialOf(seeded);
      assertThat(economy0.classFirst().isEmpty()).as("class-first 世界必须种出池").isFalse();
      long seedStockSignature = poolStockSignature(economy0.classFirst());
      assertAccountConservation(economy0, actor0, economy0, actor0, "seed-baseline");

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
      assertPopulationConservation(economy0, social0, economy1, social1, "day1");
      assertSocialMatchesClassfirstByLocation(economy1, social1, "day1");
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
      assertPopulationConservation(economy0, social0, economy24, social24, "tick24");
      assertSocialMatchesClassfirstByLocation(economy24, social24, "tick24");
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
      assertPopulationConservation(economy0, social0, economy120, social120, "tick120");
      assertSocialMatchesClassfirstByLocation(economy120, social120, "tick120");
      long changedLots = changedLotCount(social0, social120);
      System.out.println(
          "[R2B-SOCIAL-LOTS] changedCountLots=" + changedLots + "/" + social120.groups().size());
      assertThat(changedLots).as("120 tick 的阶层移动真的同步到了 social 批次（逐 lot 人数有变化）").isPositive();
      printReadings("TICK-120", economy120, actor120, social120);
    }
  }

  // ── 判据 ────────────────────────────────────────────────────────────────────────────

  /**
   * 旧结算若被调用，会写 {@code flows}/{@code industries}/{@code laborSupply}/{@code allocations}；新路径四表恒空。
   */
  private static void assertNoOldSettlement(EconomyData economy, String tag) {
    assertThat(economy.flows()).as("%s: class-first 路径不得写旧 FlowRow（旧结算的痕迹）", tag).isEmpty();
    assertThat(economy.industries()).as("%s: class-first 世界没有旧 industries", tag).isEmpty();
    assertThat(economy.laborSupply()).as("%s: class-first 路径不得写旧 laborSupply", tag).isEmpty();
    assertThat(economy.allocations()).as("%s: class-first 路径不得写旧 allocations", tag).isEmpty();
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

  /** 人口守恒：Σ池 == classfirst 初始总人口 == Σsocial 批次（social 侧同步的是逐日移动，不是月度生死）。 */
  private static void assertPopulationConservation(
      EconomyData beforeEconomy,
      SocialData beforeSocial,
      EconomyData afterEconomy,
      SocialData afterSocial,
      String tag) {
    long beforePoolPopulation = poolPopulation(beforeEconomy.classFirst());
    long afterPoolPopulation = poolPopulation(afterEconomy.classFirst());
    long beforeSocialPopulation = socialPopulation(beforeSocial);
    long afterSocialPopulation = socialPopulation(afterSocial);
    assertThat(afterPoolPopulation)
        .as("%s: classfirst 总人口守恒（移动只换池）", tag)
        .isEqualTo(beforePoolPopulation);
    assertThat(afterSocialPopulation)
        .as("%s: social 总人口恒定（本轮无出生/死亡）", tag)
        .isEqualTo(beforeSocialPopulation);
    assertThat(afterSocialPopulation)
        .as("%s: Σsocial == Σclassfirst（逐日移动映射不丢人）", tag)
        .isEqualTo(afterPoolPopulation);
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
          locationKey(group.residence().toString(), residenceOf(group).value()),
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

  private static Optional<GoodsAccount> governmentAccount(ActorData actor) {
    GoodsAccount found = null;
    for (GoodsAccount book : actor.accounts().values()) {
      if (book.key().owner().kind() == ActorKind.GOVERNMENT) {
        if (found != null) {
          throw new IllegalStateException(
              "actor 里有多个 GOVERNMENT 账户: " + found.key() + " / " + book.key());
        }
        found = book;
      }
    }
    return Optional.ofNullable(found);
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
    String gov =
        governmentAccount(actor)
            .map(
                book ->
                    "money="
                        + book.money().values().stream().mapToLong(Long::longValue).sum()
                        + " goods="
                        + book.balances())
            .orElse("缺失");
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
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-advance-" + from + "-" + to,
                "corr-advance-" + from + "-" + to,
                INITIATOR,
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进 %d → %d 天只落一条 revision", from, to)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1L))));
    return head + 1L;
  }

  private static TimeRange range(long from, long to) {
    return new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)));
  }
}
