package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.ClassFirstPopulationEconomyTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.SetJurisdictionHandler;
import io.mosire.simos.unit.spi.SetTaxRateHandler;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>阶段 6.3 长期税的真三国一日集成（对照分支法）</b>：
 *
 * <p>同一创世（{@link CompactThreeNationsWorld} 真 worldgen 三国 class-first 小世界，两条互不影响的 store）→ A 在
 * 既有根单位上设 {@code unit.SetJurisdiction}（region = 粮仓平原国、admin=1000、caps 齐备）+ {@code unit.SetTaxRate}
 * （50‰），B 不设 → 两侧各经真 {@code AdvanceTime} 推进同一日（0 → 1）。断言：
 *
 * <ol>
 *   <li><b>逐户税值</b>：从对照 B（无税）的<b>税日末家户账</b>（= A 的税前账）逐户算 {@code
 *       assessed=floor(balance×50/1000)}、{@code collected=min(assessed,
 *       AvailableStock.available)}，A 的每一本家户账都逐值等于 {@code B 余额 − 手算税}；未被征税的账逐字段原样；
 *   <li><b>累计守恒</b>：{@code ΣA家户粮 == ΣB家户粮 − 税粮}、{@code ΣA家户银 == ΣB家户银 − 税银}，且 A 新建的 国库账恰好 {@code ==
 *       税粮/税银}（逐值），两张冻结表空；B 无该国库账；
 *   <li><b>Settlement 不受税影响</b>：税在 actor 写回之后 ⇒ 两侧 {@code classFirst} 结算轨迹逐字段一致、social 一致、tick 都是
 *       1；推进本身 {@code Committed}、actor 无负余额（{@code ClassFirstActorWriteback} 的约束未破）。
 * </ol>
 *
 * <p>★ <b>未构造（留给控制方裁）</b>：把税率压到"次日 ClassFirstActorWriteback fail-closed 断粮"的那一幕 —— 本文件只推一日、取低税
 * 50‰，不构造次日失败。
 */
class ClassFirstPopulationEconomyTimeParticipantTaxTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:t3-jurisdiction-tax-test";

  /** 本波选的低压税率：50‰（5%），只推一日 ⇒ 不触发次日写回 fail-closed。 */
  private static final long TAX_RATE_PER_MILLE = 50L;

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final RegionId TAXED_REGION = CompactThreeNationsWorld.GRANARY;

  @TempDir Path tempDir;

  @Test
  void realWorldOneDayTaxMatchesPerHouseholdFormulaAndLandsInTreasury() throws IOException {
    UnitId unitId = new UnitId(TAXED_REGION.value() + "-army");

    // ── A：有管辖 + 50‰ 税率 ───────────────────────────────────────────────
    SimulationState seededA;
    SimulationState configuredA;
    SimulationState taxedA;
    long revisionA;
    Path storeA = Files.createDirectories(tempDir.resolve("t3-tax-store-a"));
    try (CoreSimos core = classFirstCore(storeA)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      CompactThreeNationsWorld.initializeNations(core, EconomySeeder.FoundationProfile.CLASS_FIRST);
      seededA = core.replay(new StateRef(MAIN, new RevisionId(4L)));
      assertChargeableUnit(seededA, unitId);

      submit(core, "cmd-t3-set-jurisdiction", "unit.SetJurisdiction", jurisdictionPayload(unitId));
      submit(core, "cmd-t3-set-tax-rate", "unit.SetTaxRate", taxRatePayload(unitId));
      configuredA = core.replay(new StateRef(MAIN, new RevisionId(6L)));
      assertThat(CompactThreeNationsWorld.actorOf(configuredA))
          .as("设管辖/税率只写 unit 切片：actor 逐值不动")
          .isEqualTo(CompactThreeNationsWorld.actorOf(seededA));

      revisionA = advance(core, 0L, 1L);
      taxedA = core.replay(new StateRef(MAIN, new RevisionId(revisionA)));
    }

    // ── B：同一创世、同一日、无管辖（对照）────────────────────────────────
    SimulationState seededB;
    SimulationState taxedB;
    long revisionB;
    Path storeB = Files.createDirectories(tempDir.resolve("t3-tax-store-b"));
    try (CoreSimos core = classFirstCore(storeB)) {
      core.bootstrapGenesis(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID));
      CompactThreeNationsWorld.initializeNations(core, EconomySeeder.FoundationProfile.CLASS_FIRST);
      seededB = core.replay(new StateRef(MAIN, new RevisionId(4L)));
      revisionB = advance(core, 0L, 1L);
      taxedB = core.replay(new StateRef(MAIN, new RevisionId(revisionB)));
    }

    assertThat(revisionA - revisionB).as("A 只比 B 多两条辖区命令（推进次数一致）").isEqualTo(2L);
    assertThat(CompactThreeNationsWorld.actorOf(seededA))
        .as("同一创世：两侧 seed actor 逐值相同")
        .isEqualTo(CompactThreeNationsWorld.actorOf(seededB));
    assertThat(CompactThreeNationsWorld.economyOf(seededA).classFirst())
        .as("同一创世：两侧 seed classFirst 逐值相同")
        .isEqualTo(CompactThreeNationsWorld.economyOf(seededB).classFirst());

    EconomyData economyA = CompactThreeNationsWorld.economyOf(taxedA);
    EconomyData economyB = CompactThreeNationsWorld.economyOf(taxedB);
    assertThat(economyA.classFirst().meta().tick()).as("A 推一日").isEqualTo(1L);
    assertThat(economyB.classFirst().meta().tick()).as("B 推一日（同推进次数）").isEqualTo(1L);
    assertThat(economyA.classFirst())
        .as("税在结算+写回之后 ⇒ classFirst 结算轨迹与对照逐字段一致")
        .isEqualTo(economyB.classFirst());
    assertThat(CompactThreeNationsWorld.socialOf(taxedA))
        .as("social 写回不读 actor ⇒ 与对照逐字段一致")
        .isEqualTo(CompactThreeNationsWorld.socialOf(taxedB));

    // ── 逐户税值：B 的税日末账 = A 的税前账 ────────────────────────────────
    GameMap map = CompactThreeNationsWorld.mapOf(taxedA);
    Region taxedArea = map.regions().get(TAXED_REGION);
    assertThat(taxedArea).as("税区必须是真 region").isNotNull();
    ActorRef unitOwner = new ActorRef(ActorKind.UNIT, unitId.value());
    HexCoord treasuryAt =
        CompactThreeNationsWorld.unitOf(taxedA)
            .effectivePosition(unitId, SimosTimestamp.of(1L))
            .orElseThrow(() -> new AssertionError("税日末单位 " + unitId + " 应有有效位置"));
    GoodsAccountKey treasuryKey = new GoodsAccountKey(unitOwner, treasuryAt);

    ActorData control = CompactThreeNationsWorld.actorOf(taxedB);
    ActorData taxed = CompactThreeNationsWorld.actorOf(taxedA);
    Map<GoodsAccountKey, Expected> grain = expectedTax(control, taxedArea, GRAIN);
    Map<GoodsAccountKey, Expected> silver = expectedTax(control, taxedArea, SILVER);

    long assessedGrain = sumAssessed(grain);
    long assessedSilver = sumAssessed(silver);
    long taxGrain = sumCollected(grain);
    long taxSilver = sumCollected(silver);
    assertThat(grain).as("粮税基非空（否则用例什么都没验）").isNotEmpty();
    assertThat(silver).as("钱税基非空").isNotEmpty();
    assertThat(assessedGrain).as("粮 assessed 为正").isPositive();
    assertThat(assessedSilver).as("钱 assessed 为正").isPositive();
    assertThat(taxGrain).as("粮 collected 为正").isPositive();
    assertThat(taxSilver).as("钱 collected 为正").isPositive();

    assertThat(control.accounts()).as("B（对照）没有该国库账").doesNotContainKey(treasuryKey);
    List<GoodsAccountKey> expectedKeys = new ArrayList<>(control.accounts().keySet());
    expectedKeys.add(treasuryKey);
    assertThat(taxed.accounts().keySet())
        .as("A = B 的全部账键 + 新建国库账（不增不减别的账）")
        .containsExactlyInAnyOrderElementsOf(expectedKeys);

    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : control.accounts().entrySet()) {
      GoodsAccountKey key = entry.getKey();
      GoodsAccount before = entry.getValue();
      GoodsAccount after = taxed.accounts().get(key);
      assertThat(after).as("税不删账键：%s", key).isNotNull();
      long grainTax = grain.getOrDefault(key, Expected.ZERO).collected();
      long silverTax = silver.getOrDefault(key, Expected.ZERO).collected();
      if (grainTax == 0L && silverTax == 0L) {
        assertThat(after).as("未征税的账逐字段原样（含其它区域/余额≤0）：%s", key).isEqualTo(before);
        continue;
      }
      assertThat(after.balances().getOrDefault(GRAIN, 0L))
          .as("逐户粮：A == B − 手算税（key=%s）", key)
          .isEqualTo(before.balances().getOrDefault(GRAIN, 0L) - grainTax);
      assertThat(after.money().getOrDefault(SILVER, 0L))
          .as("逐户银：A == B − 手算税（key=%s）", key)
          .isEqualTo(before.money().getOrDefault(SILVER, 0L) - silverTax);
      assertThat(after.frozenBalances())
          .as("税只从可支配扣、冻结表不动（key=%s）", key)
          .isEqualTo(before.frozenBalances());
      assertThat(after.frozenMoney())
          .as("税只从可支配扣、货币冻结表不动（key=%s）", key)
          .isEqualTo(before.frozenMoney());
    }

    // ── 国库账逐值 == 逐户税合计；两张冻结表空（五参新建）────────────────
    GoodsAccount treasury = taxed.accounts().get(treasuryKey);
    assertThat(treasury).as("国库账由本日税新建").isNotNull();
    assertThat(treasury.balances().getOrDefault(GRAIN, 0L)).as("国库粮 == Σ逐户粮税").isEqualTo(taxGrain);
    assertThat(treasury.money().getOrDefault(SILVER, 0L)).as("国库银 == Σ逐户银税").isEqualTo(taxSilver);
    assertThat(treasury.frozenBalances()).as("新建国库账的粮冻结表空").isEmpty();
    assertThat(treasury.frozenMoney()).as("新建国库账的银冻结表空").isEmpty();

    // ── 世界口径守恒式（逐维）────────────────────────────────────────────
    long controlHouseholdGrain = householdTotal(control, GRAIN);
    long taxedHouseholdGrain = householdTotal(taxed, GRAIN);
    long controlHouseholdSilver = householdTotal(control, SILVER);
    long taxedHouseholdSilver = householdTotal(taxed, SILVER);
    assertThat(taxedHouseholdGrain)
        .as("ΣA家户粮 == ΣB家户粮 − 税粮")
        .isEqualTo(controlHouseholdGrain - taxGrain);
    assertThat(taxedHouseholdSilver)
        .as("ΣA家户银 == ΣB家户银 − 税银")
        .isEqualTo(controlHouseholdSilver - taxSilver);
    assertThat(taxedHouseholdGrain + taxGrain)
        .as("ΣB家户粮 == ΣA家户粮 + 税粮（同一式的另一侧）")
        .isEqualTo(controlHouseholdGrain);
    assertThat(taxedHouseholdSilver + taxSilver)
        .as("ΣB家户银 == ΣA家户银 + 税银")
        .isEqualTo(controlHouseholdSilver);
    assertThat(treasury.balances().getOrDefault(GRAIN, 0L))
        .as("国库增加 == 家户减少（粮）")
        .isEqualTo(controlHouseholdGrain - taxedHouseholdGrain);
    assertThat(treasury.money().getOrDefault(SILVER, 0L))
        .as("国库增加 == 家户减少（银）")
        .isEqualTo(controlHouseholdSilver - taxedHouseholdSilver);

    // ── ClassFirstActorWriteback 的约束未破：推进成功、全账无负余额 ─────────
    for (GoodsAccount account : taxed.accounts().values()) {
      for (long value : account.balances().values()) {
        assertThat(value).as("余额不得为负：%s", account.key()).isNotNegative();
      }
      for (long value : account.money().values()) {
        assertThat(value).as("货币余额不得为负：%s", account.key()).isNotNegative();
      }
    }

    System.out.println(
        "[T3-TAX] unit="
            + unitId.value()
            + " region="
            + TAXED_REGION.value()
            + " rate‰="
            + TAX_RATE_PER_MILLE
            + " householdsTaxed="
            + grain.size()
            + " grain{assessed="
            + assessedGrain
            + ", collected="
            + taxGrain
            + ", stockShortfall="
            + (assessedGrain - taxGrain)
            + "} silver{assessed="
            + assessedSilver
            + ", collected="
            + taxSilver
            + ", stockShortfall="
            + (assessedSilver - taxSilver)
            + "} controlHousehold{grain="
            + controlHouseholdGrain
            + ", silver="
            + controlHouseholdSilver
            + "} taxedHousehold{grain="
            + taxedHouseholdGrain
            + ", silver="
            + taxedHouseholdSilver
            + "} treasury="
            + treasuryKey);
  }

  // ── 判据小件 ───────────────────────────────────────────────────────────────

  /** 一个维度的逐户期望：{@code assessed=floor(balance×rate/1000)}、{@code collected=min(assessed, 可支配)}。 */
  private record Expected(long assessed, long collected) {

    static final Expected ZERO = new Expected(0L, 0L);
  }

  /** 商品维度：税区里每本余额 > 0 的 HOUSEHOLD 账的期望税（口径与 {@code collect} 同款；admin=1000 ⇒ 不折行政）。 */
  private static Map<GoodsAccountKey, Expected> expectedTax(
      ActorData control, Region area, CommodityId commodity) {
    Map<GoodsAccountKey, Expected> expected = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : control.accounts().entrySet()) {
      if (!isTaxableHousehold(entry.getKey(), area)) {
        continue;
      }
      GoodsAccount account = entry.getValue();
      long balance = account.balances().getOrDefault(commodity, 0L);
      if (balance <= 0L) {
        continue;
      }
      long assessed = Math.multiplyExact(balance, TAX_RATE_PER_MILLE) / 1000L;
      long collected = Math.min(assessed, AvailableStock.available(account, commodity));
      expected.put(entry.getKey(), new Expected(assessed, collected));
    }
    return expected;
  }

  /** 货币维度：与商品同款（admin=1000 ⇒ 不折行政）。 */
  private static Map<GoodsAccountKey, Expected> expectedTax(
      ActorData control, Region area, CurrencyId currency) {
    Map<GoodsAccountKey, Expected> expected = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : control.accounts().entrySet()) {
      if (!isTaxableHousehold(entry.getKey(), area)) {
        continue;
      }
      GoodsAccount account = entry.getValue();
      long balance = account.money().getOrDefault(currency, 0L);
      if (balance <= 0L) {
        continue;
      }
      long assessed = Math.multiplyExact(balance, TAX_RATE_PER_MILLE) / 1000L;
      long collected = Math.min(assessed, AvailableStock.available(account, currency));
      expected.put(entry.getKey(), new Expected(assessed, collected));
    }
    return expected;
  }

  private static boolean isTaxableHousehold(GoodsAccountKey key, Region area) {
    return key.owner().kind() == ActorKind.HOUSEHOLD && area.hexes().contains(key.location());
  }

  private static long sumAssessed(Map<GoodsAccountKey, Expected> expected) {
    long total = 0L;
    for (Expected value : expected.values()) {
      total = Math.addExact(total, value.assessed());
    }
    return total;
  }

  private static long sumCollected(Map<GoodsAccountKey, Expected> expected) {
    long total = 0L;
    for (Expected value : expected.values()) {
      total = Math.addExact(total, value.collected());
    }
    return total;
  }

  private static long householdTotal(ActorData actor, CommodityId commodity) {
    long total = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : actor.accounts().entrySet()) {
      if (entry.getKey().owner().kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      total = Math.addExact(total, entry.getValue().balances().getOrDefault(commodity, 0L));
    }
    return total;
  }

  private static long householdTotal(ActorData actor, CurrencyId currency) {
    long total = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : actor.accounts().entrySet()) {
      if (entry.getKey().owner().kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      total = Math.addExact(total, entry.getValue().money().getOrDefault(currency, 0L));
    }
    return total;
  }

  /** 既有根单位必须真有有效位置、且位置落在税区；税区里至少有一本家户账（否则税基为空 ⇒ 假绿）。 */
  private static void assertChargeableUnit(SimulationState seeded, UnitId unitId) {
    UnitState units = CompactThreeNationsWorld.unitOf(seeded);
    GameMap map = CompactThreeNationsWorld.mapOf(seeded);
    Unit unit = units.units().get(unitId);
    assertThat(unit)
        .as("三国 worldgen 的根单位 %s 存在（后缀 -army 与 WorldgenInitializeTool 一致）", unitId)
        .isNotNull();
    HexCoord at =
        units
            .effectivePosition(unitId, SimosTimestamp.of(0L))
            .orElseThrow(() -> new AssertionError("根单位 " + unitId + " 应有有效位置"));
    Region area = map.regions().get(TAXED_REGION);
    assertThat(area).as("地图里有税区 %s", TAXED_REGION).isNotNull();
    assertThat(area.hexes()).as("根单位驻地 %s 落在税区内", at).contains(at);
    long householdAccounts =
        CompactThreeNationsWorld.actorOf(seeded).accounts().keySet().stream()
            .filter(key -> isTaxableHousehold(key, area))
            .count();
    assertThat(householdAccounts).as("税区里至少一本 HOUSEHOLD 账").isPositive();
  }

  // ── 装配与命令 ─────────────────────────────────────────────────────────────

  /**
   * 与 {@code ClassFirstPopulationEconomyTimeParticipantTest.classFirstCore} 同源，另注册两条辖区命令（不改生产注册）。
   */
  private static CoreSimos classFirstCore(Path storeDir) {
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
            new CreateArmyHandler(),
            // ★ 本测试自己的 core 构建器里注册（生产注册不动）。
            new SetJurisdictionHandler(),
            new SetTaxRateHandler())) {
      core.register(handler);
    }
    core.register(new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID));
    return core;
  }

  private static String jurisdictionPayload(UnitId unitId) {
    return "{\"unitId\":\""
        + unitId.value()
        + "\",\"regions\":[\""
        + TAXED_REGION.value()
        + "\"],\"levyGrainCapPerCommand\":1000,\"levyMoneyCapPerCommand\":1000,"
        + "\"levyManpowerCapPerCommand\":1000,\"administrationPerMille\":1000}";
  }

  private static String taxRatePayload(UnitId unitId) {
    return "{\"unitId\":\""
        + unitId.value()
        + "\",\"regionId\":\""
        + TAXED_REGION.value()
        + "\",\"ratePerMille\":"
        + TAX_RATE_PER_MILLE
        + "}";
  }

  private static long submit(CoreSimos core, String commandId, String type, String payloadJson) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new CommandEnvelope(
                commandId, commandId, INITIATOR, MAIN, new RevisionId(head), type, payloadJson));
    assertThat(result)
        .as("%s 提交成功", type)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1L))));
    return head + 1L;
  }

  /** 一次真 {@code AdvanceTime}（一条 revision，内部由参与者逐日跑）；返回新 revision。 */
  private static long advance(CoreSimos core, long from, long to) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-t3-advance-" + from + "-" + to,
                "corr-t3-advance-" + from + "-" + to,
                INITIATOR,
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进 %d → %d 天成功（fail-closed 未触发）", from, to)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1L))));
    return head + 1L;
  }
}
