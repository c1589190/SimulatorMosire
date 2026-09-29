package io.mosire.simos.economy.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 精简试点验收：初始 4 户（地主/中农/佃农/雇农）+ 地主催收政策，跑 360 tick，打印每 60 tick 读数并检查守恒与关键机制。
 *
 * <p>夹具刻意让中农"人口多、自有地/工具少"：种子先自给（不够则向地主借），产出先交租/工资/投入， 残值归己；布匹（非必要品）耗尽后只扣效率、不阻生产。债务跨 tick
 * 累积，只有到期/阈值/压力才催收。
 */
class ClassFirstPilotEngineTest {

  private static final long RUN_TICKS = 360L;

  @Test
  void fourHouseholdTenancyPilotSurvives360TicksWithClassFirstMechanisms() {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            900L, // 债务当量阈值（粮）：先跨 tick 累积到 900
            6000L, // 压力：债务 ≥ 可收价值 6 倍才触发，别让首次催收太早
            250L, // 每次只收 25%，跨 tick 累积
            20L, // 地主定价：1 单位地 = 20 粮
            PilotModel.SeizurePriority.LIQUID_THEN_LAND,
            0L, // 人口流动基础速率
            2L, // 债务压力斜率
            20L); // 人口流动上限 20‰/tick
    PilotModel.Lender lender =
        new PilotModel.Lender(
            "PILOT-LENDER",
            1_000_000L, // 大量资金
            Map.of(), // 0 流动性
            20L, // 利率 2%/tick
            60L, // 首期到期 tick
            1000L); // collectionPower（本试点只用于记录）
    PilotConfig config = PilotConfig.tenancyAgriculture(lender, policy, 90L);

    List<PilotModel.Household> initial =
        List.of(
            household("H1", "地主", PilotModel.LANDLORD_ID, 2L, 0L, 2000L, 200L, 500L, 24L, 0L),
            household("H2", "中农", PilotModel.MIDDLE_PEASANT_ID, 16L, 500L, 0L, 40L, 20L, 6L, 3L),
            household("H3", "佃农", PilotModel.TENANT_ID, 10L, 500L, 40L, 40L, 10L, 0L, 4L),
            household("H4", "雇农", PilotModel.LABORER_ID, 8L, 500L, 0L, 10L, 0L, 0L, 0L));

    ClassFirstPilotEngine engine = new ClassFirstPilotEngine(config, initial);
    List<PilotModel.TickReport> reports = engine.runTicks((int) RUN_TICKS);
    assertThat(reports).hasSize((int) RUN_TICKS);

    // 每 60 tick 打印关键读数
    for (long tick = 60L; tick <= RUN_TICKS; tick += 60L) {
      printReport("[PILOT-" + tick + "]", reports.get((int) tick - 1));
    }
    printSummary(engine, initial, reports.get(reports.size() - 1));

    // ── 守恒 ───────────────────────────────────────────────────────────────────
    long initialPopulation = initial.stream().mapToLong(PilotModel.Household::population).sum();
    long initialLand = initial.stream().mapToLong(PilotModel.Household::land).sum();
    long initialTools = initial.stream().mapToLong(PilotModel.Household::tools).sum();
    long initialHouseholdMoney = initial.stream().mapToLong(PilotModel.Household::money).sum();
    assertThat(engine.totalPopulation()).as("人口守恒").isEqualTo(initialPopulation);
    assertThat(engine.totalLand()).as("土地守恒").isEqualTo(initialLand);
    assertThat(engine.totalTools()).as("工具守恒").isEqualTo(initialTools);

    assertThat(engine.initialGrainTotal() + engine.producedGrainTotal())
        .as("粮守恒：期初 + 产出 = 期末 + 种子 + 口粮")
        .isEqualTo(engine.totalGrain() + engine.seedUsedTotal() + engine.rationConsumedTotal());
    assertThat(engine.initialClothTotal())
        .as("布守恒：期初 = 期末 + 消费")
        .isEqualTo(engine.totalCloth() + engine.clothConsumedTotal());
    assertThat(engine.initialHouseholdMoneyTotal() + engine.initialLenderMoneyTotal())
        .as("货币守恒：家户 + 放贷方")
        .isEqualTo(engine.totalHouseholdMoney() + engine.totalLenderMoney());

    // ── 账户：双边对冲、正净额只记 claim、负净额只记 debt ────────────────────────
    assertThat(engine.accountNetSum()).as("全体账户净额双边对冲为 0").isZero();
    boolean sawPositive = false;
    boolean sawNegative = false;
    for (PilotModel.HouseholdAccount account : engine.accounts()) {
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
    assertThat(sawPositive).as("应存在正的索取权账户").isTrue();
    assertThat(sawNegative).as("应存在负的债务账户").isTrue();

    // 家户之间的粮债权/债务必须双边对冲（放贷方是外部账户，不计入）
    List<String> householdIds = initial.stream().map(PilotModel.Household::id).toList();
    long internalGrainClaims = 0L;
    long internalGrainDebts = 0L;
    for (PilotModel.HouseholdAccount account : engine.accounts()) {
      if (!PilotModel.GRAIN.equals(account.unit())) {
        continue;
      }
      if (!householdIds.contains(account.householdId())
          || !householdIds.contains(account.counterpartyId())) {
        continue;
      }
      if (account.cumulativeNet() > 0L) {
        internalGrainClaims += account.cumulativeNet();
      } else if (account.cumulativeNet() < 0L) {
        internalGrainDebts += -account.cumulativeNet();
      }
    }
    assertThat(internalGrainDebts).as("家户间粮债务与粮索取权对冲").isEqualTo(internalGrainClaims);

    // ── 非必要品缺口只扣效率、不阻生产 ─────────────────────────────────────────
    long ticksWithEfficiencyPenalty =
        reports.stream()
            .filter(
                report ->
                    report.efficiencyByHousehold().values().stream()
                        .anyMatch(efficiency -> efficiency < 1000L))
            .count();
    assertThat(ticksWithEfficiencyPenalty).as("布匹（非必要品）缺口应触发效率扣减").isPositive();
    assertThat(engine.clothConsumedTotal()).as("非必要品确有消费").isPositive();
    assertThat(engine.producedGrainTotal()).as("效率扣减不阻生产：粮仍有大量产出").isGreaterThan(40_000L);

    // ── 阶层分配确有发生：地租/工资/残值都支付过 ───────────────────────────────
    assertThat(engine.rentPaidTotal()).as("地租已支付").isPositive();
    assertThat(engine.wagePaidTotal()).as("工资/给养已支付").isPositive();
    assertThat(engine.residualPaidTotal()).as("经营者残值已支付").isPositive();
    assertThat(engine.seedUsedTotal()).as("种子投入已发生").isPositive();

    // ── 债务跨 tick 累积，不每 tick 全额清收 ────────────────────────────────────
    assertThat(engine.interestChargedTotal()).as("债务利息应至少计过一次").isPositive();
    assertThat(engine.collectionEventCount()).as("催收是离散事件，不是每 tick 全额清收").isLessThan(RUN_TICKS);
    long debtTicks = reports.stream().filter(report -> totalDebtValueMilli(report) > 0L).count();
    assertThat(debtTicks).as("债务应跨大多数 tick 存续，而不是每 tick 清零").isGreaterThan(300L);
    assertThat(engine.accountsOf("H2"))
        .as("中农账户应收不抵债、保持负净额")
        .anyMatch(account -> account.cumulativeNet() < 0L);
    long debtTick60 = totalDebtValueMilli(reports.get(59));
    long debtTick300 = totalDebtValueMilli(reports.get(299));
    assertThat(debtTick300).as("债务跨 tick 累积（tick300 债务 > tick60）").isGreaterThan(debtTick60);
    assertThat(totalDebtValueMilli(reports.get(reports.size() - 1)))
        .as("期末仍有未清债务（没有每 tick 全额清收）")
        .isPositive();

    // ── 催收：先流动商品、再按地主定价收地 ─────────────────────────────────────
    assertThat(engine.transitions())
        .as("应发生按地主定价收地")
        .anyMatch(t -> t.kind() == PilotModel.TransitionKind.LAND_SEIZED);
    assertThat(engine.transitions())
        .as("应发生地主催收（至少扣到流动商品或收地或资本化）")
        .anyMatch(
            t ->
                t.kind() == PilotModel.TransitionKind.LIQUID_SEIZED
                    || t.kind() == PilotModel.TransitionKind.LAND_SEIZED
                    || t.kind() == PilotModel.TransitionKind.DEBT_CAPITALIZED);

    // ── 阶层下滑：人口流动 + 家户/人口计数变化 ─────────────────────────────────
    assertThat(engine.transitions())
        .as("应至少发生一次人口向更低阶层家户流动")
        .anyMatch(t -> t.kind() == PilotModel.TransitionKind.POPULATION_FLOW);
    assertThat(engine.transitions())
        .as("中农本人应至少发生一次向更低阶层的人口流动")
        .anyMatch(
            t ->
                t.kind() == PilotModel.TransitionKind.POPULATION_FLOW
                    && "H2".equals(t.householdId()));
    assertThat(engine.transitions())
        .as("应至少发生一次中农自有地/人低于下限的阶层下调")
        .anyMatch(t -> t.kind() == PilotModel.TransitionKind.CLASS_DOWNGRADE);
    PilotModel.TickReport last = reports.get(reports.size() - 1);
    long middleStart = findHousehold(initial, "H2").population();
    assertThat(last.populationByClass().get(PilotModel.MIDDLE_PEASANT_ID))
        .as("中农人口应下降")
        .isLessThan(middleStart);
    assertThat(
            last.populationByClass().get(PilotModel.TENANT_ID)
                + last.populationByClass().get(PilotModel.LABORER_ID))
        .as("佃农/雇农人口应增加")
        .isGreaterThan(
            findHousehold(initial, "H3").population() + findHousehold(initial, "H4").population());
    assertThat(last.landlordLandSharePerMille())
        .as("收地后土地向地主集中（期末份额 > 期初 800‰）")
        .isGreaterThan(reports.get(0).landlordLandSharePerMille());
  }

  private static long totalDebtValueMilli(PilotModel.TickReport report) {
    long total = 0L;
    for (long value : report.debtByClassGrainMilli().values()) {
      total += value;
    }
    return total;
  }

  private static PilotModel.Household findHousehold(
      List<PilotModel.Household> households, String id) {
    for (PilotModel.Household household : households) {
      if (household.id().equals(id)) {
        return household;
      }
    }
    throw new IllegalArgumentException("unknown household " + id);
  }

  private static PilotModel.Household household(
      String id,
      String name,
      String classPositionId,
      long population,
      long laborPerCapita,
      long grain,
      long cloth,
      long money,
      long land,
      long tools) {
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>();
    goods.put(PilotModel.GRAIN, grain);
    goods.put(PilotModel.CLOTH, cloth);
    return new PilotModel.Household(
        id, name, classPositionId, population, laborPerCapita, goods, money, land, tools, 1000L);
  }

  // ── 打印辅助 ─────────────────────────────────────────────────────────────────

  private static void printReport(String tag, PilotModel.TickReport report) {
    System.out.println(
        tag
            + " popByClass="
            + report.populationByClass()
            + " householdsByClass="
            + report.householdsByClass());
    System.out.println(
        "         debtByClass(grainMilli)="
            + report.debtByClassGrainMilli()
            + " claimByClass(grainMilli)="
            + report.claimByClassGrainMilli());
    System.out.println(
        "         goodsByHousehold="
            + report.goodsByHousehold()
            + " money="
            + report.moneyByHousehold());
    System.out.println(
        "         land="
            + report.landByHousehold()
            + " tools="
            + report.toolsByHousehold()
            + " efficiency="
            + report.efficiencyByHousehold());
    System.out.println(
        "         redLights="
            + report.redLights()
            + " baseRationGap="
            + report.baseRationGap()
            + " landlordLandSharePerMille="
            + report.landlordLandSharePerMille()
            + " transitions="
            + report.transitions().size());
    for (PilotModel.Transition transition : report.transitions()) {
      System.out.println("         event " + transition);
    }
  }

  private static void printSummary(
      ClassFirstPilotEngine engine,
      List<PilotModel.Household> initial,
      PilotModel.TickReport last) {
    System.out.println("[PILOT-360-SUMMARY] tick=" + last.tick());
    System.out.println("  class population=" + last.populationByClass());
    System.out.println("  class households=" + last.householdsByClass());
    System.out.println("  class debt(grainMilli)=" + last.debtByClassGrainMilli());
    System.out.println("  class claim(grainMilli)=" + last.claimByClassGrainMilli());
    System.out.println("  household grain=" + mapValue(last.goodsByHousehold(), PilotModel.GRAIN));
    System.out.println("  household cloth=" + mapValue(last.goodsByHousehold(), PilotModel.CLOTH));
    System.out.println("  household money=" + last.moneyByHousehold());
    System.out.println("  household land=" + last.landByHousehold());
    System.out.println("  household tools=" + last.toolsByHousehold());
    System.out.println("  household efficiency=" + last.efficiencyByHousehold());
    System.out.println(
        "  redLightsTotal="
            + engine.redLightTotal()
            + " lastTickRedLights="
            + last.redLights()
            + " lastTickBaseGap="
            + last.baseRationGap());
    System.out.println(
        "  landConcentration landlord="
            + last.landlordLandSharePerMille()
            + "‰ topHousehold="
            + last.topHouseholdLandSharePerMille()
            + "‰");
    System.out.println(
        "  borrow: grain="
            + engine.borrowedGrainTotal()
            + " money="
            + engine.borrowedMoneyTotal()
            + " boughtGrain="
            + engine.boughtGrainTotal());
    System.out.println(
        "  distributionPaid: rent="
            + engine.rentPaidTotal()
            + " wage="
            + engine.wagePaidTotal()
            + " externalSeed="
            + engine.externalSeedPaidTotal()
            + " residual="
            + engine.residualPaidTotal()
            + " seedUsed="
            + engine.seedUsedTotal()
            + " rationConsumed="
            + engine.rationConsumedTotal());
    System.out.println("  lastTickProductionAccounts=" + engine.lastProductionAccounts());
    System.out.println(
        "  collect: events="
            + engine.collectionEventCount()
            + " liquidSeized="
            + engine.liquidSeizedTotal()
            + " landSeized="
            + engine.landSeizedTotal()
            + " capitalized="
            + engine.capitalizedTotal()
            + " interestCharged="
            + engine.interestChargedTotal());
    System.out.println("  transitions by kind:");
    LinkedHashMap<String, Long> byKind = new LinkedHashMap<>();
    for (PilotModel.Transition transition : engine.transitions()) {
      byKind.merge(transition.kind().name(), 1L, Long::sum);
    }
    for (Map.Entry<String, Long> entry : byKind.entrySet()) {
      System.out.println("    " + entry.getKey() + "=" + entry.getValue());
    }
    System.out.println("  class/flow/land events（谁/何时/迁出多少/收走多少地）:");
    for (PilotModel.Transition transition : engine.transitions()) {
      if (transition.kind() == PilotModel.TransitionKind.CLASS_DOWNGRADE
          || transition.kind() == PilotModel.TransitionKind.POPULATION_FLOW
          || transition.kind() == PilotModel.TransitionKind.LAND_SEIZED) {
        System.out.println("    " + transition);
      }
    }
    System.out.println(
        "  conservation: population="
            + engine.totalPopulation()
            + " land="
            + engine.totalLand()
            + " tools="
            + engine.totalTools()
            + " grain="
            + engine.totalGrain()
            + " cloth="
            + engine.totalCloth()
            + " householdMoney="
            + engine.totalHouseholdMoney()
            + " lenderMoney="
            + engine.totalLenderMoney());

    // 机制未触发时，逐 tick 打印关键原因（不调数据绕过）
    boolean sawFlow =
        engine.transitions().stream()
            .anyMatch(t -> t.kind() == PilotModel.TransitionKind.POPULATION_FLOW);
    boolean sawLandSeizure =
        engine.transitions().stream()
            .anyMatch(t -> t.kind() == PilotModel.TransitionKind.LAND_SEIZED);
    boolean sawRedLight =
        engine.transitions().stream()
            .anyMatch(t -> t.kind() == PilotModel.TransitionKind.RED_LIGHT);
    if (!sawFlow || !sawLandSeizure) {
      System.out.println("[PILOT-DIAG] 未触发的机制 → 逐 tick 原因：");
      for (PilotModel.TickReport report : engine.reports()) {
        for (String diagnostic : report.diagnostics()) {
          if ((!sawFlow && diagnostic.contains("flow"))
              || (!sawLandSeizure
                  && (diagnostic.contains("collect") || diagnostic.contains("收地")))) {
            System.out.println("[PILOT-DIAG] tick=" + report.tick() + " " + diagnostic);
          }
        }
        if (!sawLandSeizure) {
          for (PilotModel.Transition transition : report.transitions()) {
            if (transition.kind() == PilotModel.TransitionKind.DEBT_CAPITALIZED) {
              System.out.println("[PILOT-DIAG] tick=" + report.tick() + " " + transition);
            }
          }
        }
      }
    }
    if (!sawRedLight) {
      System.out.println("[PILOT-DIAG] 红灯未触发：逐 tick 原因（债务、阈值、利率、商品/货币库存）");
      for (PilotModel.TickReport report : engine.reports()) {
        String reason = null;
        for (String diagnostic : report.diagnostics()) {
          if (diagnostic.contains("borrow-cover")) {
            reason = diagnostic;
            break;
          }
        }
        if (reason == null) {
          reason = "无 base 口粮缺口：本 tick 全部家户自有粮覆盖；红灯分支未到";
        }
        System.out.println("[PILOT-DIAG] tick=" + report.tick() + " " + reason);
      }
    }
    System.out.println("[PILOT-360-SUMMARY-END]");
  }

  private static Map<String, Long> mapValue(
      Map<String, Map<String, Long>> goodsByHousehold, String unit) {
    LinkedHashMap<String, Long> result = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Long>> entry : goodsByHousehold.entrySet()) {
      result.put(entry.getKey(), entry.getValue().getOrDefault(unit, 0L));
    }
    return result;
  }
}
