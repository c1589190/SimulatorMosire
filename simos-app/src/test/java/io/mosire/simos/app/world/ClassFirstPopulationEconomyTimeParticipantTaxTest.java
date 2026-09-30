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
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
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
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.SetGovFormationHandler;
import io.mosire.simos.unit.spi.SetJurisdictionHandler;
import io.mosire.simos.unit.spi.SetTaxRateHandler;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
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
 * ★★ <b>阶段 6.3 / 11b 长期税的真三国一日集成（对照分支法）</b>：
 *
 * <p>同一创世（{@link CompactThreeNationsWorld} 真 worldgen 三国 class-first 小世界，三条互不影响的 store）各经真 {@code
 * AdvanceTime} 推进同一日（0 → 1）：
 *
 * <ol>
 *   <li><b>纯对照</b>：不设管辖、不设 GOV、不设税 ⇒ “什么都没发生”的基线；
 *   <li><b>无 GOV 的原夹具</b>：根单位设 {@code unit.SetJurisdiction}（region = 粮仓平原国、旧 {@code
 *       administrationPerMille=1000}、caps 齐备）+ {@code unit.SetTaxRate}（50‰），但<b>不设</b> {@code
 *       GovFormation} ⇒ 税路径整单位跳过：actor 与纯对照逐字段相同、无国库账（不得回退读旧 {@code administrationPerMille}）；
 *   <li><b>有 GOV</b>：同一配置再加 {@code unit.SetGovFormation}（CENTRAL；staff 按 {@code GovDemand}
 *       现算后给足并超编；policy 的 upkeep 置 0——见下）⇒ 逐户税 = {@code floor(floor(balance×50‰/1000) ×
 *       efficiency‰/1000)} 且受可支配库存限制；家户减少 == 国库增加；守恒。
 * </ol>
 *
 * <p>★★ <b>效率不硬编码</b>：期望值由夹具状态现算 {@code GovDemand.of} + {@code GovEfficiency.of} 给出（与生产参与者同一对纯函数）。
 * ★ policy 的 upkeep 置 0 是<b>本用例的隔离选择</b>：{@code GovDaily.settle} 的俸禄付款 oracle 会从同一本国库账扣
 * grain/cloth/silver；默认 {@code OfficePolicy}（粮 10/tick/人）会把当日税款当场扣走，令“家户减少 ==
 * 国库增加”这条税语义断言变成“税减俸禄”的复合题。本用例只验长期税，故把 upkeep 显式置 0；俸禄结算另有 gov 侧判据。
 *
 * <p>★ <b>gov 切片</b>：本测试 core 同时注册 {@link GovCodec} 与 {@link SetGovFormationHandler}；创世夹具显式补 {@code
 * gov=GovState.empty()} 空片，使首建 GOV 读数引导能在真 {@code AdvanceTime} 的模块校验里落地（生产 {@code
 * RichWorld.state()} 当前缺该片，已另具名报告）。
 *
 * <p>★ <b>未构造（留给控制方裁）</b>：把税率压到“次日 ClassFirstActorWriteback fail-closed 断粮”的那一幕 —— 本文件只推一日、取低税
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
  void realWorldOneDayTaxOnlyWithGovAndMatchesEfficiencyFormula() throws IOException {
    UnitId unitId = new UnitId(TAXED_REGION.value() + "-army");

    // ── 纯对照：不设管辖/GOV/税 ─────────────────────────────────────────────
    SimulationState seededControl;
    SimulationState taxedControl;
    long revisionControl;
    Path storeControl = Files.createDirectories(tempDir.resolve("t3-tax-store-control"));
    try (CoreSimos core = classFirstCore(storeControl)) {
      seedClassFirstWorld(core);
      seededControl = core.replay(ref(4L));
      assertChargeableUnit(seededControl, unitId);
      revisionControl = advance(core, 0L, 1L);
      taxedControl = core.replay(ref(revisionControl));
    }

    // ── A：有管辖 + 50‰ 税率，但**无 GovFormation**（旧 administrationPerMille=1000 必须不被回退） ──
    SimulationState seededNoGov;
    SimulationState configuredNoGov;
    SimulationState taxedNoGov;
    long revisionNoGov;
    Path storeNoGov = Files.createDirectories(tempDir.resolve("t3-tax-store-no-gov"));
    try (CoreSimos core = classFirstCore(storeNoGov)) {
      seedClassFirstWorld(core);
      seededNoGov = core.replay(ref(4L));
      assertChargeableUnit(seededNoGov, unitId);

      configuredNoGov = configureJurisdictionAndTax(core, unitId);
      assertThat(CompactThreeNationsWorld.actorOf(configuredNoGov))
          .as("设管辖/税率只写 unit 切片：actor 逐值不动")
          .isEqualTo(CompactThreeNationsWorld.actorOf(seededNoGov));

      revisionNoGov = advance(core, 0L, 1L);
      taxedNoGov = core.replay(ref(revisionNoGov));
    }

    // ── B：同 A + GovFormation（staff 按 demand 现算、覆盖并超编；upkeep 置 0 以隔离税语义） ──
    SimulationState seededGov;
    SimulationState configuredNoGovForGov;
    SimulationState configuredGov;
    SimulationState taxedGov;
    long revisionGov;
    GovEfficiency.Efficiency efficiency;
    Path storeGov = Files.createDirectories(tempDir.resolve("t3-tax-store-gov"));
    try (CoreSimos core = classFirstCore(storeGov)) {
      seedClassFirstWorld(core);
      seededGov = core.replay(ref(4L));
      assertChargeableUnit(seededGov, unitId);

      configuredNoGovForGov = configureJurisdictionAndTax(core, unitId);

      Map<HexCoord, GovDemand.HexDemand> demand = demandOf(configuredNoGovForGov, unitId);
      long securityDemand = demand.values().stream().mapToLong(GovDemand.HexDemand::security).sum();
      long paperworkDemand =
          demand.values().stream().mapToLong(GovDemand.HexDemand::paperwork).sum();
      assertThat(demand).as("辖区里有逐步行政需求（否则 GOV 效率是空洞的满覆盖）").isNotEmpty();
      assertThat(securityDemand).as("治安需求为正（YAMEN 供给才有意义）").isPositive();
      assertThat(paperworkDemand).as("文书需求为正（SCRIBE+POST 供给才有意义）").isPositive();

      submit(
          core,
          "cmd-t3-set-gov-formation",
          "unit.SetGovFormation",
          govFormationPayload(
              unitId,
              Math.addExact(Math.multiplyExact(securityDemand, 2L), 1L),
              Math.addExact(Math.multiplyExact(paperworkDemand, 2L), 1L)));
      configuredGov = core.replay(ref(7L));

      efficiency = efficiencyOf(configuredGov, unitId);
      assertThat(efficiency.securityCoveragePerMille())
          .as("治安覆盖率必须满（staff 按 demand×2+1 给）")
          .isEqualTo(1000L);
      assertThat(efficiency.paperworkCoveragePerMille()).as("文书覆盖率必须满").isEqualTo(1000L);
      assertThat(efficiency.efficiencyPerMille())
          .as("超编要给 >1000‰ 的加成；本用例把它现算出来当期望值，不硬编码")
          .isGreaterThan(1000L);
      assertThat(efficiency.efficiencyPerMille())
          .as("若生产回退读旧 administrationPerMille=1000，这条对照就失去判别力")
          .isNotEqualTo(1000L);

      revisionGov = advance(core, 0L, 1L);
      taxedGov = core.replay(ref(revisionGov));
    }

    // ── 命令计数：A 比纯对照多 2 条；B 比纯对照多 3 条（仅多一条 GovFormation）────────
    assertThat(revisionNoGov - revisionControl).as("A 只比纯对照多两条辖区命令（推进次数一致）").isEqualTo(2L);
    assertThat(revisionGov - revisionControl)
        .as("B 只比纯对照多三条命令（辖区两条 + GovFormation 一条）")
        .isEqualTo(3L);
    assertThat(CompactThreeNationsWorld.actorOf(seededNoGov))
        .as("同一创世：A seed actor 与纯对照逐值相同")
        .isEqualTo(CompactThreeNationsWorld.actorOf(seededControl));
    assertThat(CompactThreeNationsWorld.actorOf(seededGov))
        .as("同一创世：B seed actor 与纯对照逐值相同")
        .isEqualTo(CompactThreeNationsWorld.actorOf(seededControl));

    // ── 无 GOV ⇒ 整单位不征：与纯对照逐字段一致（旧 admin 字段不得回退）──────────────
    assertThat(CompactThreeNationsWorld.economyOf(taxedNoGov).classFirst())
        .as("无 GOV 时税路径整单位跳过 ⇒ classFirst 结算轨迹与纯对照逐字段一致")
        .isEqualTo(CompactThreeNationsWorld.economyOf(taxedControl).classFirst());
    assertThat(CompactThreeNationsWorld.socialOf(taxedNoGov))
        .as("无 GOV 时社会写回与纯对照逐字段一致")
        .isEqualTo(CompactThreeNationsWorld.socialOf(taxedControl));
    assertThat(CompactThreeNationsWorld.actorOf(taxedNoGov))
        .as("无 GovFormation / 无 GOV 读数 ⇒ 不征：actor 与纯对照逐字段一致（旧 administrationPerMille=1000 不得回退）")
        .isEqualTo(CompactThreeNationsWorld.actorOf(taxedControl));

    // ── 有 GOV：结算轨迹不受长期税影响 ─────────────────────────────────────
    EconomyData economyGov = CompactThreeNationsWorld.economyOf(taxedGov);
    EconomyData economyNoGov = CompactThreeNationsWorld.economyOf(taxedNoGov);
    assertThat(economyGov.classFirst().meta().tick()).as("B 推一日").isEqualTo(1L);
    assertThat(economyGov.classFirst())
        .as("税在结算+写回之后、且 GOV upkeep=0 ⇒ classFirst 结算轨迹与无 GOV 逐字段一致")
        .isEqualTo(economyNoGov.classFirst());
    assertThat(CompactThreeNationsWorld.socialOf(taxedGov))
        .as("GOV upkeep=0 不发信号、不回写 social ⇒ 与无 GOV 逐字段一致")
        .isEqualTo(CompactThreeNationsWorld.socialOf(taxedNoGov));

    // ── 逐户税值：无 GOV 的 A 就是 B 的税前账（同 seed、同结算）──────────────────
    GameMap map = CompactThreeNationsWorld.mapOf(taxedNoGov);
    Region taxedArea = map.regions().get(TAXED_REGION);
    assertThat(taxedArea).as("税区必须是真 region").isNotNull();
    ActorRef unitOwner = new ActorRef(ActorKind.UNIT, unitId.value());
    HexCoord treasuryAt =
        CompactThreeNationsWorld.unitOf(taxedGov)
            .effectivePosition(unitId, SimosTimestamp.of(1L))
            .orElseThrow(() -> new AssertionError("税日末单位 " + unitId + " 应有有效位置"));
    GoodsAccountKey treasuryKey = new GoodsAccountKey(unitOwner, treasuryAt);

    ActorData baseline = CompactThreeNationsWorld.actorOf(taxedNoGov);
    ActorData taxed = CompactThreeNationsWorld.actorOf(taxedGov);
    Map<GoodsAccountKey, Expected> grain =
        expectedTax(baseline, taxedArea, GRAIN, efficiency.efficiencyPerMille());
    Map<GoodsAccountKey, Expected> silver =
        expectedTax(baseline, taxedArea, SILVER, efficiency.efficiencyPerMille());

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

    assertThat(baseline.accounts()).as("无 GOV 的基线没有该国库账").doesNotContainKey(treasuryKey);
    List<GoodsAccountKey> expectedKeys = new ArrayList<>(baseline.accounts().keySet());
    expectedKeys.add(treasuryKey);
    assertThat(taxed.accounts().keySet())
        .as("B = 基线全部账键 + 新建国库账（不增不减别的账）")
        .containsExactlyInAnyOrderElementsOf(expectedKeys);

    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : baseline.accounts().entrySet()) {
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
          .as("逐户粮：B == 税前 − 手算税（key=%s）", key)
          .isEqualTo(before.balances().getOrDefault(GRAIN, 0L) - grainTax);
      assertThat(after.money().getOrDefault(SILVER, 0L))
          .as("逐户银：B == 税前 − 手算税（key=%s）", key)
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
    long baselineHouseholdGrain = householdTotal(baseline, GRAIN);
    long taxedHouseholdGrain = householdTotal(taxed, GRAIN);
    long baselineHouseholdSilver = householdTotal(baseline, SILVER);
    long taxedHouseholdSilver = householdTotal(taxed, SILVER);
    assertThat(taxedHouseholdGrain)
        .as("ΣB家户粮 == Σ基线家户粮 − 税粮（逐户 assessed×效率 的结果）")
        .isEqualTo(baselineHouseholdGrain - taxGrain);
    assertThat(taxedHouseholdSilver)
        .as("ΣB家户银 == Σ基线家户银 − 税银")
        .isEqualTo(baselineHouseholdSilver - taxSilver);
    assertThat(taxedHouseholdGrain + taxGrain)
        .as("Σ基线家户粮 == ΣB家户粮 + 税粮（同一式的另一侧）")
        .isEqualTo(baselineHouseholdGrain);
    assertThat(taxedHouseholdSilver + taxSilver)
        .as("Σ基线家户银 == ΣB家户银 + 税银")
        .isEqualTo(baselineHouseholdSilver);
    assertThat(treasury.balances().getOrDefault(GRAIN, 0L))
        .as("国库增加 == 家户减少（粮）")
        .isEqualTo(baselineHouseholdGrain - taxedHouseholdGrain);
    assertThat(treasury.money().getOrDefault(SILVER, 0L))
        .as("国库增加 == 家户减少（银）")
        .isEqualTo(baselineHouseholdSilver - taxedHouseholdSilver);

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
            + " efficiency‰="
            + efficiency.efficiencyPerMille()
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
            + "} baselineHousehold{grain="
            + baselineHouseholdGrain
            + ", silver="
            + baselineHouseholdSilver
            + "} taxedHousehold{grain="
            + taxedHouseholdGrain
            + ", silver="
            + taxedHouseholdSilver
            + "} treasury="
            + treasuryKey);
  }

  // ── 判据小件 ───────────────────────────────────────────────────────────────

  /** 一个维度的逐户期望：{@code assessed=floor(balance×rate/1000)}、{@code collected=min(attainable, 可支配)}。 */
  private record Expected(long assessed, long collected) {

    static final Expected ZERO = new Expected(0L, 0L);
  }

  /** 商品维度：税区里每本余额 > 0 的 HOUSEHOLD 账的期望税（含 GOV 效率‰；与 {@code collect} 同算式）。 */
  private static Map<GoodsAccountKey, Expected> expectedTax(
      ActorData baseline, Region area, CommodityId commodity, long efficiencyPerMille) {
    Map<GoodsAccountKey, Expected> expected = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : baseline.accounts().entrySet()) {
      if (!isTaxableHousehold(entry.getKey(), area)) {
        continue;
      }
      GoodsAccount account = entry.getValue();
      long balance = account.balances().getOrDefault(commodity, 0L);
      if (balance <= 0L) {
        continue;
      }
      long assessed = Math.multiplyExact(balance, TAX_RATE_PER_MILLE) / 1000L;
      long attainable = Math.multiplyExact(assessed, efficiencyPerMille) / 1000L;
      long collected = Math.min(attainable, AvailableStock.available(account, commodity));
      expected.put(entry.getKey(), new Expected(assessed, collected));
    }
    return expected;
  }

  /** 货币维度：与商品同款（含 GOV 效率‰）。 */
  private static Map<GoodsAccountKey, Expected> expectedTax(
      ActorData baseline, Region area, CurrencyId currency, long efficiencyPerMille) {
    Map<GoodsAccountKey, Expected> expected = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : baseline.accounts().entrySet()) {
      if (!isTaxableHousehold(entry.getKey(), area)) {
        continue;
      }
      GoodsAccount account = entry.getValue();
      long balance = account.money().getOrDefault(currency, 0L);
      if (balance <= 0L) {
        continue;
      }
      long assessed = Math.multiplyExact(balance, TAX_RATE_PER_MILLE) / 1000L;
      long attainable = Math.multiplyExact(assessed, efficiencyPerMille) / 1000L;
      long collected = Math.min(attainable, AvailableStock.available(account, currency));
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
   * 与 {@code ClassFirstPopulationEconomyTimeParticipantTest.classFirstCore} 同源，另注册辖区/编制命令与 gov
   * codec（不改生产注册）。
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
            new ActorCodec(),
            // ★ 阶段 10a：GOV 切片；本用例经真 AdvanceTime 走 GovFormation 首建读数引导。
            new GovCodec())) {
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
            new SetTaxRateHandler(),
            new SetGovFormationHandler())) {
      core.register(handler);
    }
    core.register(new ClassFirstPopulationEconomyTimeParticipant(CompactThreeNationsWorld.MAP_ID));
    return core;
  }

  /**
   * 生产 {@code RichWorld.state()} 当前没有 gov 切片；本用例显式补空片，使 {@code unit.SetGovFormation} 之后的真 {@code
   * AdvanceTime} 的“base 必须有该 namespace”模块校验能落地。缺口本身已具名报告。
   */
  private static SimulationState withEmptyGov(SimulationState base) {
    Map<String, Snapshot> modules = new LinkedHashMap<>(base.modules());
    modules.put(
        "gov", new GovSnapshot(base.meta().ref(), base.meta().timestamp(), GovState.empty()));
    return new SimulationState(base.meta(), modules, base.info());
  }

  private static void seedClassFirstWorld(CoreSimos core) throws IOException {
    core.bootstrapGenesis(
        withEmptyGov(CompactThreeNationsWorld.state(CompactThreeNationsWorld.MAP_ID)));
    CompactThreeNationsWorld.initializeNations(core, EconomySeeder.FoundationProfile.CLASS_FIRST);
  }

  /** 发辖区域 + 税率两条命令（head 从 4 到 6），返回配置后的状态。 */
  private static SimulationState configureJurisdictionAndTax(CoreSimos core, UnitId unitId) {
    submit(core, "cmd-t3-set-jurisdiction", "unit.SetJurisdiction", jurisdictionPayload(unitId));
    submit(core, "cmd-t3-set-tax-rate", "unit.SetTaxRate", taxRatePayload(unitId));
    return core.replay(ref(6L));
  }

  /** 该单位当日生效的 GOV 效率（与生产 participant 同一对纯函数：以单位当前 jurisdiction 算 demand）。 */
  private static GovEfficiency.Efficiency efficiencyOf(SimulationState state, UnitId unitId) {
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(unitId);
    assertThat(unit).as("GOV 效率要对齐的单位 %s 必须存在", unitId).isNotNull();
    Object module =
        unit.module()
            .orElseThrow(() -> new AssertionError("单位 " + unitId + " 没有 GovFormation，无法算效率"));
    assertThat(module)
        .as("单位 %s 的 module 必须是 GovFormation", unitId)
        .isInstanceOf(GovFormation.class);
    return GovEfficiency.of((GovFormation) module, demandOf(state, unitId));
  }

  /** 单位辖区（jurisdiction 的 key 集）的逐格行政需求（与生产 GovDemand 同一纯函数）。 */
  private static Map<HexCoord, GovDemand.HexDemand> demandOf(SimulationState state, UnitId unitId) {
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(unitId);
    assertThat(unit).as("算行政需求的单位 %s 必须存在", unitId).isNotNull();
    return GovDemand.of(
        CompactThreeNationsWorld.mapOf(state), CompactThreeNationsWorld.socialOf(state), unit);
  }

  private static String jurisdictionPayload(UnitId unitId) {
    return "{\"unitId\":\""
        + unitId.value()
        + "\",\"regions\":[\""
        + TAXED_REGION.value()
        + "\"],\"levyGrainCapPerCommand\":1000,\"levyMoneyCapPerCommand\":1000,"
        + "\"levyManpowerCapPerCommand\":1000,\"administrationPerMille\":1000}";
  }

  /** 立 CENTRAL GOV：staff 由调用方按 demand 算好；policy 四项 upkeep 全 0（隔离俸禄结算，只用效率）。 */
  private static String govFormationPayload(UnitId unitId, long yamen, long scribe) {
    return "{\"unitId\":\""
        + unitId.value()
        + "\",\"level\":\"CENTRAL\",\"staff\":{\"YAMEN\":"
        + yamen
        + ",\"SCRIBE\":"
        + scribe
        + ",\"POST\":0},\"policy\":{\"grainPerStaffPerTick\":0,"
        + "\"clothPerStaffPerCycle\":0,\"moneyPerStaffPerTick\":0,\"retirementPerStaff\":0}}";
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
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
