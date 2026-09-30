package io.mosire.simos.economy.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 阶层池 + 聚合资产状态试点的 360 tick 验收。
 *
 * <p>夹具是四池（地主/中农/佃农/雇农）各一个家户账户。每 60 tick 打印 POOL 读数；迁移逐条打印 UP/DOWN 与 bundle；
 * 结束时打印阶层分布、双向流动、稳定区净流动、守恒对账、GM 调参前后对比。若某条不触发，打印逐 tick 的 A/x/r/O/cap/bundle 诊断，不改数据绕过。
 */
class ClassFirstPilotEngineTest {

  private static final long RUN_TICKS = 360L;

  @Test
  void classPoolTenancyPilotRuns360TicksWithBidirectionalFlowAndConservation() {
    List<PilotModel.Household> initial = fixture();
    PilotConfig baselineConfig = config(MobilityPolicy.tenancyDefaults());
    ClassFirstPilotEngine engine = new ClassFirstPilotEngine(baselineConfig, initial);
    List<PilotModel.TickReport> reports = engine.runTicks((int) RUN_TICKS);
    assertThat(reports).hasSize((int) RUN_TICKS);

    for (long tick = 60L; tick <= RUN_TICKS; tick += 60L) {
      PilotModel.TickReport report = reports.get((int) tick - 1);
      printPoolTick(report);
      assertThat(report.totalPopulation())
          .as("tick " + tick + " 人口守恒")
          .isEqualTo(engine.initialPopulationTotal());
      assertThat(report.totalOwnedLand() + report.landForSale())
          .as("tick " + tick + " Σ土地所有权+LandForSale 守恒")
          .isEqualTo(engine.initialOwnedLandTotal());
      assertThat(report.totalTools())
          .as("tick " + tick + " Σ工具守恒")
          .isEqualTo(engine.initialToolsTotal());
      assertThat(report.totalDebtGrainMilli())
          .as("tick " + tick + " Σ债务 = Σ债权")
          .isEqualTo(report.totalClaimGrainMilli());
      assertThat(engine.initialGrainTotal() + report.producedGrainTotal())
          .as("tick " + tick + " 粮守恒")
          .isEqualTo(report.totalGrain() + report.seedUsedTotal() + report.rationConsumedTotal());
      assertThat(engine.initialClothTotal())
          .as("tick " + tick + " 布守恒")
          .isEqualTo(report.totalCloth() + report.clothConsumedTotal());
      assertThat(engine.initialHouseholdMoneyTotal() + engine.initialLenderMoneyTotal())
          .as("tick " + tick + " 货币守恒")
          .isEqualTo(report.totalMoney());
    }
    printMobilityEvents(engine.mobilityEvents());
    printFinal(engine, reports.get(reports.size() - 1), initial);
    printSchema(engine.mobilityPolicy());

    long upCount = countDirection(engine, PilotModel.Direction.UP);
    long downCount = countDirection(engine, PilotModel.Direction.DOWN);
    boolean bidirectional = upCount > 0L && downCount > 0L;
    if (!bidirectional) {
      printMobilityDiagnostics(engine, "双向流动未同时触发");
    }
    assertThat(upCount).as("360 tick 内应有向上迁移").isPositive();
    assertThat(downCount).as("360 tick 内应有向下迁移").isPositive();

    // ── 中农→佃农下放的土地先进 LandForSale，再被佃农→中农购买：同一批地驱动双向 ──
    long landToMarketTotal = 0L;
    long landPurchasedTotal = 0L;
    for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
      landToMarketTotal += event.bundle().landOwnershipToMarket();
      landPurchasedTotal += event.bundle().landPurchasedFromMarket();
    }
    System.out.println(
        "[POOL-360] landMarket shared batch: landToMarketTotal="
            + landToMarketTotal
            + " landPurchasedTotal="
            + landPurchasedTotal
            + " landForSaleEnd="
            + engine.landForSale());
    if (landToMarketTotal <= 0L || landPurchasedTotal <= 0L) {
      printMobilityDiagnostics(engine, "LandForSale 未同时承接中农下行地与佃农上行购地");
    }
    assertThat(landToMarketTotal).as("中农→佃农应向 LandForSale 下放土地").isPositive();
    assertThat(landPurchasedTotal)
        .as("佃农→中农应从 LandForSale 购地（初始 LandForSale=0，故必然来自下行放地）")
        .isPositive();
    assertThat(landPurchasedTotal)
        .as("购买量不得超过 LandForSale 收到的地（不复制资产）")
        .isLessThanOrEqualTo(
            landToMarketTotal + engine.config().mobilityPolicy().initialLandForSale());

    // ── 迁移 bundle 按人头（ceil）带走库存：原池库存人均份额不得凭空提高 ──────────
    for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
      assertThat(event.originStockPerCapitaNotIncreased())
          .as(
              "迁移后原池库存人均份额不得提高（event tick="
                  + event.tick()
                  + " "
                  + event.fromClassPositionId()
                  + "->"
                  + event.toClassPositionId()
                  + "）")
          .isTrue();
    }
    assertThat(engine.stockEnrichmentViolations()).as("迁移不得让原池人均库存凭空提高").isZero();
    for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
      assertThat(event.afterAMilli())
          .as(
              "迁移后原池 A 不得高于迁移前（event tick="
                  + event.tick()
                  + " "
                  + event.fromClassPositionId()
                  + "->"
                  + event.toClassPositionId()
                  + "）")
          .isLessThanOrEqualTo(event.aMilli());
    }

    // ── 稳定区：x∈[0.4,0.6] 的池，净流动接近 0（允许合理阈值） ──────────────────
    StableZone stable = stableZoneStatistics(engine, reports);
    if (stable == null) {
      printMobilityDiagnostics(engine, "没有池在稳定区留下可核对的净流动");
    }
    assertThat(stable).as("应存在 x∈[0.4,0.6] 且发生双向流动的池用于核对稳定区净流动").isNotNull();
    long stableNet = stable.upMoved - stable.downMoved;
    long stableGross = stable.upMoved + stable.downMoved;
    long stableThreshold = Math.max(1L, stableGross / 5L);
    System.out.println(
        "[POOL-360] stableZone pool="
            + stable.classPositionId
            + " ticksInZone="
            + stable.ticksInZone
            + " xLast="
            + stable.xLast
            + " upMoved="
            + stable.upMoved
            + " downMoved="
            + stable.downMoved
            + " net="
            + stableNet
            + " gross="
            + stableGross
            + " threshold="
            + stableThreshold
            + " |net|/grossMilli="
            + (stableGross == 0L ? 0L : Math.abs(stableNet) * 1000L / stableGross));
    boolean stableOk = Math.abs(stableNet) <= stableThreshold;
    if (!stableOk) {
      printMobilityDiagnostics(engine, "稳定区净流动未接近 0");
    }
    assertThat(Math.abs(stableNet))
        .as("稳定区池 |净流动| 应接近 0（阈值 = max(1, gross/5)）")
        .isLessThanOrEqualTo(stableThreshold);

    // ── 守恒：人口 / 土地+LandForSale / 工具 / 粮 / 布 / 货币 / 债务债权 ─────────
    assertThat(engine.totalPopulation()).as("Σ人口守恒").isEqualTo(engine.initialPopulationTotal());
    assertThat(engine.totalOwnedLand() + engine.landForSale())
        .as("Σ土地所有权 + LandForSale 守恒")
        .isEqualTo(engine.initialOwnedLandTotal());
    assertThat(engine.totalTools()).as("Σ工具守恒").isEqualTo(engine.initialToolsTotal());
    assertThat(engine.initialGrainTotal() + engine.producedGrainTotal())
        .as("粮守恒：期初 + 产出 = 期末 + 种子 + 口粮")
        .isEqualTo(engine.totalGrain() + engine.seedUsedTotal() + engine.rationConsumedTotal());
    assertThat(engine.initialClothTotal())
        .as("布守恒：期初 = 期末 + 消费")
        .isEqualTo(engine.totalCloth() + engine.clothConsumedTotal());
    assertThat(engine.initialHouseholdMoneyTotal() + engine.initialLenderMoneyTotal())
        .as("货币守恒：期初家户 + 放贷方 = 期末池 + 放贷方 + 市场托管")
        .isEqualTo(engine.totalMoney());
    assertThat(engine.accountNetSum()).as("全体账户净额双边对冲为 0").isZero();
    assertThat(engine.totalDebtGrainMilli())
        .as("Σ债务（负净额）与 Σ债权（正净额）守恒")
        .isEqualTo(engine.totalClaimGrainMilli());
    // ── 原生产/消费/效率语义仍通过 ──────────────────────────────────────────────
    assertThat(engine.producedGrainTotal()).as("产出 > 0").isPositive();
    assertThat(engine.seedUsedTotal()).as("种子投入已发生").isPositive();
    assertThat(engine.rentPaidTotal()).as("地租已支付").isPositive();
    assertThat(engine.wagePaidTotal()).as("工资/给养已支付").isPositive();
    assertThat(engine.residualPaidTotal()).as("经营者残值已支付").isPositive();
    assertThat(engine.clothConsumedTotal()).as("非必要品确有消费").isPositive();
    long ticksWithEfficiencyPenalty =
        reports.stream()
            .filter(
                report ->
                    report.pools().values().stream()
                        .anyMatch(reading -> reading.laborEfficiencyPerMille() < 1000L))
            .count();
    assertThat(ticksWithEfficiencyPenalty).as("布匹缺口应触发效率扣减").isPositive();
    long minEfficiency =
        reports.stream()
            .flatMap(report -> report.pools().values().stream())
            .mapToLong(PilotModel.PoolReading::laborEfficiencyPerMille)
            .min()
            .orElse(1000L);
    assertThat(minEfficiency).as("效率下限为 300‰，缺口只扣效率").isBetween(300L, 1000L);
    assertThat(engine.producedGrainTotal()).as("效率扣减不阻生产：粮仍有大量产出").isGreaterThan(20_000L);

    // 家户只是池内账户：份额合计对得上池残值/Population，池仍持有库存。
    for (PilotModel.ProductionAccount account : engine.lastProductionAccounts()) {
      long shareSum =
          account.householdGrainShares().values().stream().mapToLong(Long::longValue).sum();
      assertThat(shareSum)
          .as("家户残值份额合计 = 池 residual（pool=" + account.poolId() + "）")
          .isEqualTo(account.residualPaid());
    }
    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      long accountPopulation =
          engine.householdAccounts().stream()
              .filter(account -> account.poolId().equals(position.id()))
              .mapToLong(PilotModel.HouseholdAccount::population)
              .sum();
      assertThat(accountPopulation)
          .as("池内家户账户人口合计 = 池人口（" + position.id() + "）")
          .isEqualTo(engine.poolOf(position.id()).population());
    }
    System.out.println("[POOL-360] householdAccounts=" + engine.householdAccounts());

    // ── GM 旋钮：只改规则参数；提高 UpCap / leaseAvailability 应有可复现的迁移增加 ──
    long upWithRaisedCap =
        runUpCount(baselineConfig.mobilityPolicy().withUpCapPerMillePerTick(10L), initial);
    long upWithRaisedCapAndLowLease =
        runUpCount(
            baselineConfig
                .mobilityPolicy()
                .withUpCapPerMillePerTick(10L)
                .withLeaseAvailabilityPerMille(30L),
            initial);
    long upWithRaisedCapAndHighLease =
        runUpCount(
            baselineConfig
                .mobilityPolicy()
                .withUpCapPerMillePerTick(10L)
                .withLeaseAvailabilityPerMille(80L),
            initial);
    System.out.println(
        "[POOL-360] GM comparison: baseline(upCap="
            + baselineConfig.mobilityPolicy().upCapPerMillePerTick()
            + "‰, leaseAvail="
            + baselineConfig.mobilityPolicy().leaseAvailabilityPerMille()
            + "‰) upEvents="
            + upCount
            + " | upCap=10‰ leaseAvail=300‰ upEvents="
            + upWithRaisedCap
            + " | upCap=10‰ leaseAvail=30‰ upEvents="
            + upWithRaisedCapAndLowLease
            + " | upCap=10‰ leaseAvail=80‰(重复) upEvents="
            + upWithRaisedCapAndHighLease);
    assertThat(upWithRaisedCap).as("GM 提高 UpCap 后向上迁移数应严格增加").isGreaterThan(upCount);
    assertThat(upWithRaisedCapAndHighLease)
        .as("GM 提高 leaseAvailability 后向上迁移数应严格增加")
        .isGreaterThan(upWithRaisedCapAndLowLease);
    assertThat(runUpCount(baselineConfig.mobilityPolicy().withUpCapPerMillePerTick(10L), initial))
        .as("GM 调参应可复现")
        .isEqualTo(upWithRaisedCap);
  }

  /**
   * 滚动账户/借款顺序/催收/红灯仍是活路径：无粮夹具下先借商品（无余粮）→ 借货币 → 买粮（无卖家）→ 红灯； 债务跨 tick
   * 累积计息，到期催收先扣流动商品，账户双边对冲且正/负净额不混。人口不消失。
   */
  @Test
  void debtCollectionBorrowOrderAndRedLightRemainReachableInGrainlessPilot() {
    ClassFirstPilotEngine engine =
        new ClassFirstPilotEngine(config(MobilityPolicy.tenancyDefaults()), grainlessFixture());
    engine.runTicks(200);
    System.out.println(
        "[RED-LIGHT] borrowedGrain="
            + engine.borrowedGrainTotal()
            + " borrowedMoney="
            + engine.borrowedMoneyTotal()
            + " boughtGrain="
            + engine.boughtGrainTotal()
            + " redLights="
            + engine.redLightTotal()
            + " interest="
            + engine.interestChargedTotal()
            + " collectEvents="
            + engine.collectionEventCount()
            + " liquidSeized="
            + engine.liquidSeizedTotal()
            + " landSeized="
            + engine.landSeizedTotal()
            + " capitalized="
            + engine.capitalizedTotal()
            + " debtMilli="
            + engine.totalDebtGrainMilli()
            + " claimMilli="
            + engine.totalClaimGrainMilli()
            + " population="
            + engine.totalPopulation());
    assertThat(engine.totalPopulation())
        .as("红灯/催收也不能让人口消失")
        .isEqualTo(engine.initialPopulationTotal());
    assertThat(engine.borrowedMoneyTotal()).as("先借货币的借款顺序仍可用").isPositive();
    assertThat(engine.redLightTotal()).as("红灯路径仍可用").isPositive();
    assertThat(engine.transitions())
        .as("红灯事件在报告里可追溯")
        .anyMatch(t -> t.kind() == PilotModel.TransitionKind.RED_LIGHT);
    assertThat(engine.interestChargedTotal()).as("债务跨 tick 计息仍可用").isPositive();
    assertThat(engine.collectionEventCount()).as("催收仍可用").isPositive();
    assertThat(engine.transitions())
        .as("催收先扣流动商品/收地/资本化至少一种仍可追溯")
        .anyMatch(
            t ->
                t.kind() == PilotModel.TransitionKind.LIQUID_SEIZED
                    || t.kind() == PilotModel.TransitionKind.LAND_SEIZED
                    || t.kind() == PilotModel.TransitionKind.DEBT_CAPITALIZED);
    assertThat(engine.totalDebtGrainMilli())
        .as("压力场景下 Σ债务 = Σ债权 且都为正")
        .isEqualTo(engine.totalClaimGrainMilli())
        .isPositive();
    assertThat(engine.accountNetSum()).as("全体账户净额双边对冲为 0").isZero();

    boolean sawPositive = false;
    boolean sawNegative = false;
    for (PilotModel.RollingAccount account : engine.accounts()) {
      long net = account.cumulativeNet();
      if (net > 0L) {
        sawPositive = true;
        assertThat(account.claim()).as("正净额只记 claim").isEqualTo(net);
        assertThat(account.debt()).as("正净额不得记 debt").isZero();
      } else if (net < 0L) {
        sawNegative = true;
        assertThat(account.debt()).as("负净额只记 debt").isEqualTo(-net);
        assertThat(account.claim()).as("负净额不得记 claim").isZero();
      } else {
        assertThat(account.debt()).isZero();
        assertThat(account.claim()).isZero();
      }
    }
    assertThat(sawPositive).as("压力场景应存在正的索取权账户").isTrue();
    assertThat(sawNegative).as("压力场景应存在负的债务账户").isTrue();
  }

  /** 催收的"先流动商品、再按地主定价收地"路径仍可达：低产出 + 地主余粮 ⇒ 中农欠租/欠种子， 到期后地主作为 collector 收地减债；土地仍在总账内。 */
  @Test
  void landSeizurePathRemainsReachableUnderArrears() {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            100L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotModel.Lender lender =
        new PilotModel.Lender("PILOT-LENDER", 1_000_000L, Map.of(), 20L, 60L, 1000L);
    PilotConfig config =
        new PilotConfig(
            PilotModel.tenancyMode(),
            lender,
            policy,
            MobilityPolicy.tenancyDefaults(),
            1L, // yieldPerLand 压低 ⇒ 中农产出不足以清掉工资/种子
            1L,
            1L,
            2L,
            2L,
            6L,
            3L,
            1L,
            250L,
            200L,
            20L,
            10L,
            5L,
            3L,
            90L);
    ClassFirstPilotEngine engine = new ClassFirstPilotEngine(config, landArrearsFixture());
    engine.runTicks(200);
    System.out.println(
        "[LAND-SEIZURE] landSeized="
            + engine.landSeizedTotal()
            + " liquidSeized="
            + engine.liquidSeizedTotal()
            + " collectEvents="
            + engine.collectionEventCount()
            + " interest="
            + engine.interestChargedTotal()
            + " debtMilli="
            + engine.totalDebtGrainMilli()
            + " claimMilli="
            + engine.totalClaimGrainMilli()
            + " population="
            + engine.totalPopulation());
    assertThat(engine.landSeizedTotal()).as("收地减债路径仍可达").isPositive();
    assertThat(engine.transitions())
        .as("LAND_SEIZED 事件可追溯")
        .anyMatch(t -> t.kind() == PilotModel.TransitionKind.LAND_SEIZED);
    assertThat(engine.totalPopulation())
        .as("收地也不能让人口消失")
        .isEqualTo(engine.initialPopulationTotal());
    assertThat(engine.totalOwnedLand() + engine.landForSale())
        .as("收地只是转移所有权，Σ土地+LandForSale 守恒")
        .isEqualTo(engine.initialOwnedLandTotal());
    assertThat(engine.totalDebtGrainMilli())
        .as("欠租/欠种子下 Σ债务 = Σ债权")
        .isEqualTo(engine.totalClaimGrainMilli())
        .isPositive();
  }

  // ── 夹具与配置 ───────────────────────────────────────────────────────────────

  private static List<PilotModel.Household> fixture() {
    LinkedHashMap<String, Long> landlordGoods = goods(500L, 100L);
    LinkedHashMap<String, Long> middleGoods = goods(460L, 200L);
    LinkedHashMap<String, Long> tenantGoods = goods(300L, 150L);
    LinkedHashMap<String, Long> laborerGoods = goods(600L, 80L);
    List<PilotModel.Household> initial = new ArrayList<>();
    initial.add(
        new PilotModel.Household(
            "H1", "地主", PilotModel.LANDLORD_ID, 5L, 0L, landlordGoods, 1000L, 2000L, 0L, 1000L));
    initial.add(
        new PilotModel.Household(
            "H2",
            "中农",
            PilotModel.MIDDLE_PEASANT_ID,
            40L,
            500L,
            middleGoods,
            140L,
            175L,
            34L,
            1000L));
    initial.add(
        new PilotModel.Household(
            "H3", "佃农", PilotModel.TENANT_ID, 60L, 500L, tenantGoods, 150L, 0L, 50L, 1000L));
    initial.add(
        new PilotModel.Household(
            "H4", "雇农", PilotModel.LABORER_ID, 100L, 500L, laborerGoods, 150L, 0L, 40L, 1000L));
    return List.copyOf(initial);
  }

  private static PilotConfig config(MobilityPolicy mobilityPolicy) {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            900L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotModel.Lender lender =
        new PilotModel.Lender("PILOT-LENDER", 1_000_000L, Map.of(), 20L, 60L, 1000L);
    return PilotConfig.tenancyAgriculture(lender, policy, 90L, mobilityPolicy);
  }

  private static List<PilotModel.Household> landArrearsFixture() {
    List<PilotModel.Household> initial = new ArrayList<>();
    initial.add(
        new PilotModel.Household(
            "H1",
            "地主",
            PilotModel.LANDLORD_ID,
            5L,
            0L,
            goods(5000L, 100L),
            1000L,
            2000L,
            0L,
            1000L));
    initial.add(
        new PilotModel.Household(
            "H2",
            "中农",
            PilotModel.MIDDLE_PEASANT_ID,
            40L,
            500L,
            goods(0L, 200L),
            140L,
            175L,
            34L,
            1000L));
    initial.add(
        new PilotModel.Household(
            "H3", "佃农", PilotModel.TENANT_ID, 60L, 500L, goods(0L, 150L), 150L, 0L, 50L, 1000L));
    initial.add(
        new PilotModel.Household(
            "H4", "雇农", PilotModel.LABORER_ID, 100L, 500L, goods(0L, 80L), 150L, 0L, 40L, 1000L));
    return List.copyOf(initial);
  }

  private static List<PilotModel.Household> grainlessFixture() {
    List<PilotModel.Household> initial = new ArrayList<>();
    initial.add(
        new PilotModel.Household(
            "H1", "地主", PilotModel.LANDLORD_ID, 5L, 0L, goods(0L, 100L), 1000L, 2000L, 0L, 1000L));
    initial.add(
        new PilotModel.Household(
            "H2",
            "中农",
            PilotModel.MIDDLE_PEASANT_ID,
            40L,
            500L,
            goods(0L, 200L),
            140L,
            175L,
            34L,
            1000L));
    initial.add(
        new PilotModel.Household(
            "H3", "佃农", PilotModel.TENANT_ID, 60L, 500L, goods(0L, 150L), 150L, 0L, 50L, 1000L));
    initial.add(
        new PilotModel.Household(
            "H4", "雇农", PilotModel.LABORER_ID, 100L, 500L, goods(0L, 80L), 150L, 0L, 40L, 1000L));
    return List.copyOf(initial);
  }

  private static LinkedHashMap<String, Long> goods(long grain, long cloth) {
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>();
    goods.put(PilotModel.GRAIN, grain);
    goods.put(PilotModel.CLOTH, cloth);
    return goods;
  }

  private static long runUpCount(MobilityPolicy policy, List<PilotModel.Household> initial) {
    ClassFirstPilotEngine engine = new ClassFirstPilotEngine(config(policy), initial);
    engine.runTicks((int) RUN_TICKS);
    return countDirection(engine, PilotModel.Direction.UP);
  }

  private static long countDirection(ClassFirstPilotEngine engine, PilotModel.Direction direction) {
    long count = 0L;
    for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
      if (event.direction() == direction) {
        count++;
      }
    }
    return count;
  }

  // ── 稳定区统计 ───────────────────────────────────────────────────────────────

  private static final class StableZone {
    String classPositionId;
    long ticksInZone;
    long xLast;
    long upMoved;
    long downMoved;
  }

  private static StableZone stableZoneStatistics(
      ClassFirstPilotEngine engine, List<PilotModel.TickReport> reports) {
    StableZone best = null;
    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      long ticksInZone = 0L;
      long xLast = 0L;
      for (PilotModel.TickReport report : reports) {
        PilotModel.PoolReading reading = report.pools().get(position.id());
        long x = reading.xMilli();
        if (x >= 400L && x <= 600L) {
          ticksInZone++;
        }
        xLast = x;
      }
      long upMoved = 0L;
      long downMoved = 0L;
      for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
        if (!event.fromClassPositionId().equals(position.id())) {
          continue;
        }
        // 只统计"迁移发生时 x 也在稳定区"的流量，净流动才真正对应稳定区。
        if (event.xMilli() < 400L || event.xMilli() > 600L) {
          continue;
        }
        if (event.direction() == PilotModel.Direction.UP) {
          upMoved += event.movedPopulation();
        } else {
          downMoved += event.movedPopulation();
        }
      }
      if (upMoved + downMoved <= 0L) {
        continue;
      }
      if (best == null || ticksInZone > best.ticksInZone) {
        StableZone candidate = new StableZone();
        candidate.classPositionId = position.id();
        candidate.ticksInZone = ticksInZone;
        candidate.xLast = xLast;
        candidate.upMoved = upMoved;
        candidate.downMoved = downMoved;
        best = candidate;
      }
    }
    return best;
  }

  // ── 打印辅助 ─────────────────────────────────────────────────────────────────

  private static void printSchema(MobilityPolicy policy) {
    System.out.println(
        "[POOL-360] schema unpricedWeightPerMille="
            + policy.schema().unpricedWeightPerMille()
            + " (unpriced dims 降权，debt 为负维)");
    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      AssetStateSchema.ClassRequirement requirement = policy.schema().requirementFor(position.id());
      ClassBounds.Bound bound = policy.bounds().boundFor(position.id());
      System.out.println(
          "[POOL-360]   schema "
              + position.id()
              + " L="
              + bound.lowerMilli()
              + " U="
              + bound.upperMilli()
              + " bottleneck="
              + requirement.bottleneckWeightPerMille()
              + "‰");
      for (AssetKind kind : AssetKind.ordered()) {
        long perCapita = requirement.requirementPerCapitaMilli().getOrDefault(kind, 0L);
        long weight = requirement.weights().getOrDefault(kind, 0L);
        if (perCapita <= 0L && weight <= 0L) {
          continue;
        }
        System.out.println(
            "[POOL-360]     "
                + kind
                + " reqPerCapitaMilli="
                + perCapita
                + " weight="
                + weight
                + " unpriced="
                + requirement.unpriced().contains(kind));
      }
    }
  }

  private static void printPoolTick(PilotModel.TickReport report) {
    String tag = "[POOL-" + report.tick() + "]";
    System.out.println(
        tag
            + " landForSale="
            + report.landForSale()
            + " leaseSupply="
            + report.leaseSupply()
            + " redLights="
            + report.redLights()
            + " baseGap="
            + report.baseRationGap());
    for (PilotModel.PoolReading reading : report.pools().values()) {
      System.out.println(
          "  "
              + reading.classPositionId()
              + " pop="
              + reading.population()
              + " labor="
              + reading.labor()
              + " A="
              + reading.aMilli()
              + " x="
              + reading.xMilli()
              + " rUp="
              + reading.rateUpPerMillePerYear()
              + "/yr="
              + reading.rateUpPerMillePerTick()
              + "/tick"
              + " rDown="
              + reading.rateDownPerMillePerYear()
              + "/yr="
              + reading.rateDownPerMillePerTick()
              + "/tick"
              + " debtByUnit="
              + reading.debtByUnit()
              + " leaseHolding="
              + reading.leaseHolding()
              + " efficiency="
              + reading.laborEfficiencyPerMille());
      System.out.println("    assets=" + reading.assetVector());
    }
    for (String diagnostic : report.diagnostics()) {
      if (diagnostic.startsWith("mobility-blocked") || diagnostic.startsWith("mobility-skip")) {
        System.out.println("  [DIAG-" + report.tick() + "] " + diagnostic);
      }
    }
  }

  private static void printMobilityEvents(List<PilotModel.MobilityEvent> events) {
    for (PilotModel.MobilityEvent event : events) {
      printMobilityEvent(event);
    }
  }

  private static void printMobilityEvent(PilotModel.MobilityEvent event) {
    String tag = event.direction() == PilotModel.Direction.UP ? "[UP]" : "[DOWN]";
    PilotModel.TransitionBundle bundle = event.bundle();
    System.out.println(
        tag
            + " tick="
            + event.tick()
            + " "
            + event.fromClassPositionId()
            + "->"
            + event.toClassPositionId()
            + " moved="
            + event.movedPopulation()
            + " skipLevel="
            + event.skipLevel()
            + " A="
            + event.aMilli()
            + " afterA="
            + event.afterAMilli()
            + " x="
            + event.xMilli()
            + " r="
            + event.ratePerMillePerYear()
            + "/yr O="
            + event.opportunityPerMille()
            + "‰ cap="
            + event.absorptionCapPerMille()
            + "‰ capMilli="
            + event.capMilliPeople()
            + " bundle{pop="
            + bundle.population()
            + " labor="
            + bundle.labor()
            + " assets="
            + bundle.movedAssets()
            + " landToDest="
            + bundle.landOwnershipToDestination()
            + " landToMarket="
            + bundle.landOwnershipToMarket()
            + " landBoughtFromMarket="
            + bundle.landPurchasedFromMarket()
            + " leaseGranted="
            + bundle.leaseRightsGranted()
            + " leaseReturned="
            + bundle.leaseRightsReturned()
            + " claimsMovedMilli="
            + bundle.claimsMovedMilli()
            + " debtMovedMilli="
            + bundle.debtMovedMilli()
            + " debtRule="
            + bundle.debtRule()
            + " landRule="
            + bundle.landRule()
            + "} reason="
            + event.reason());
  }

  private static void printFinal(
      ClassFirstPilotEngine engine,
      PilotModel.TickReport last,
      List<PilotModel.Household> initial) {
    System.out.println("[POOL-360] tick=" + last.tick());
    System.out.println("[POOL-360] pool population/A/x/r:");
    for (PilotModel.PoolReading reading : last.pools().values()) {
      System.out.println(
          "[POOL-360]   "
              + reading.classPositionId()
              + " pop="
              + reading.population()
              + " labor="
              + reading.labor()
              + " A="
              + reading.aMilli()
              + " x="
              + reading.xMilli()
              + " rUp="
              + reading.rateUpPerMillePerYear()
              + "/yr rDown="
              + reading.rateDownPerMillePerYear()
              + "/yr assets="
              + reading.assetVector()
              + " debt="
              + reading.debtByUnit());
    }
    long upEvents = countDirection(engine, PilotModel.Direction.UP);
    long downEvents = countDirection(engine, PilotModel.Direction.DOWN);
    long upMoved = 0L;
    long downMoved = 0L;
    for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
      if (event.direction() == PilotModel.Direction.UP) {
        upMoved += event.movedPopulation();
      } else {
        downMoved += event.movedPopulation();
      }
    }
    System.out.println(
        "[POOL-360] mobility upEvents="
            + upEvents
            + " upMoved="
            + upMoved
            + " downEvents="
            + downEvents
            + " downMoved="
            + downMoved
            + " landForSale="
            + engine.landForSale()
            + " leaseSupply="
            + engine.leaseSupply());
    System.out.println(
        "[POOL-360] conservation populations="
            + engine.totalPopulation()
            + "/"
            + engine.initialPopulationTotal()
            + " land+market="
            + (engine.totalOwnedLand() + engine.landForSale())
            + "/"
            + engine.initialOwnedLandTotal()
            + " tools="
            + engine.totalTools()
            + "/"
            + engine.initialToolsTotal()
            + " grain="
            + engine.totalGrain()
            + " produced="
            + engine.producedGrainTotal()
            + " seed="
            + engine.seedUsedTotal()
            + " ration="
            + engine.rationConsumedTotal()
            + " (initial+produced="
            + (engine.initialGrainTotal() + engine.producedGrainTotal())
            + ")"
            + " cloth="
            + engine.totalCloth()
            + "/"
            + engine.initialClothTotal()
            + " money="
            + engine.totalMoney()
            + "/"
            + (engine.initialHouseholdMoneyTotal() + engine.initialLenderMoneyTotal())
            + " debtMilli="
            + engine.totalDebtGrainMilli()
            + " claimMilli="
            + engine.totalClaimGrainMilli()
            + " netSum="
            + engine.accountNetSum());
    System.out.println(
        "[POOL-360] production: rent="
            + engine.rentPaidTotal()
            + " wage="
            + engine.wagePaidTotal()
            + " externalSeed="
            + engine.externalSeedPaidTotal()
            + " residual="
            + engine.residualPaidTotal()
            + " tax="
            + engine.taxPaidTotal()
            + " redLights="
            + engine.redLightTotal()
            + " collectEvents="
            + engine.collectionEventCount()
            + " interest="
            + engine.interestChargedTotal()
            + " liquidSeized="
            + engine.liquidSeizedTotal()
            + " landSeized="
            + engine.landSeizedTotal()
            + " capitalized="
            + engine.capitalizedTotal()
            + " borrowedGrain="
            + engine.borrowedGrainTotal()
            + " borrowedMoney="
            + engine.borrowedMoneyTotal()
            + " boughtGrain="
            + engine.boughtGrainTotal());
    long initialPopulation = 0L;
    for (PilotModel.Household household : initial) {
      initialPopulation += household.population();
    }
    System.out.println("[POOL-360] initialPopulation=" + initialPopulation);
  }

  private static void printMobilityDiagnostics(ClassFirstPilotEngine engine, String reason) {
    System.out.println("[POOL-DIAG] " + reason + " → 逐 tick A/x/r/O/cap/bundle 原因：");
    for (PilotModel.TickReport report : engine.reports()) {
      for (String diagnostic : report.diagnostics()) {
        if (diagnostic.startsWith("mobility-blocked") || diagnostic.startsWith("mobility-skip")) {
          System.out.println("  tick=" + report.tick() + " " + diagnostic);
        }
      }
    }
    System.out.println("[POOL-DIAG] mobility events by direction:");
    Map<String, Long> byEdge = new LinkedHashMap<>();
    for (PilotModel.MobilityEvent event : engine.mobilityEvents()) {
      byEdge.merge(
          event.direction() + " " + event.fromClassPositionId() + "->" + event.toClassPositionId(),
          1L,
          Long::sum);
    }
    byEdge.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
        .forEach(entry -> System.out.println("  " + entry.getKey() + "=" + entry.getValue()));
  }
}
