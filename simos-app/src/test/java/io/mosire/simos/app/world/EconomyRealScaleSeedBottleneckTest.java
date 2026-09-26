package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **Task 7：真档规模下"第三路瓶颈"真的在起作用**（v2 spec §九 V3 判据 3：缺种子 ⇒ 投入面积缩 ⇒ 减产）。
 *
 * <p>★★ **为什么需要这个文件**：{@code EconomyTestWorld} 那 5 格夹具是 **1,000 人/格**的（真档是 **14,806 人/格**， 差 15
 * 倍——spec §九 点名的"夹具规模决定判别力"），而**每亩需种是按亩计的**：同样的 3,100 亩，1,000 人的格只有 65 天口粮 （5,395,000 毫粮）⇒
 * 付不起满种（24,800,000 毫粮）⇒ 那个夹具上跑"配了种子"的世界只会得到"全员把口粮当种子播下去"的
 * 病态结果，**它证明不了真档的任何事**。故真档可见性必须**在真档量级上**验收：本文件用**真播种器的载荷** （{@link EconomySeeder#payload} → 真
 * {@link EconomySeedHandler}）造一格 14,806 人的平原格，再跑真结算。
 *
 * <p>★ 三条读数的算式（全部整数、毫粮；亩 = 千分亩 {@code / 1000}）：
 *
 * <pre>
 * 人口 14,806 按 450/350/150/50 切 ⇒ 6,663 / 5,183 / 2,220 / 740（残差 2 按槽位 id 序补前两槽）
 * 产能 3,100,000 千分亩（= 3,100 亩 ÷ 每单位 1,000 千分亩 ⇒ 规模 = 3,100 亩）
 * 本行"想扣多少"的份额 = ⌊3,100 亩 × 本行人口 ÷ 14,806⌋ ⇒ 1,395 / 1,084 / 465 / 154 亩
 * 满种种子 = Σ(份额 × 8,000 毫粮/亩) = 3,098 亩 × 8,000 = 24,784,000 毫粮
 * 各行储备（贫 30 / 中 60 / 富 120 / 地 250 天）都付得起自己那一份 ⇒ 满种（第三路**存在但不缩地**）
 * 收获：可支撑亩 = 24,784,000 ÷ 8,000 = 3,098 亩（&lt; 产能 3,100 亩、&lt; 劳动可经营 51,698 亩）⇒ **种子是那一年的瓶颈**
 * 毛产 = 3,098 × 67 × 1000 = 207,566,000 ⇒ 净（扣饲料 0‰ + 折旧 30‰）201,339,020
 * </pre>
 *
 * <p>★ 缸全空时：播种日扣 0 ⇒ 可支撑 0 亩 ⇒ **颗粒无收**；而**未配种子的对照格**照常按产能满产 3,100 × 67 × 1000 × 0.97 = 201,469,000
 * —— 这两条并排就是"种子是第三路瓶颈"的判别力。
 */
class EconomyRealScaleSeedBottleneckTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final String MAP_ID = "econ-real-scale";

  /** 真档每格人口（11,830,000 ÷ 799，与 {@code EconomySeederTest} 同口径）。 */
  private static final long POPULATION_PER_HEX = 11_830_000L / 799L; // = 14,806

  private static final HexCoord HEX = new HexCoord(0, 0);

  /** 一格平原、真档人口（**真播种器载荷**：{@code economy.Seed} 经真 handler 落成状态）。 */
  private static EconomyData realScaleHex() {
    SettlementPlan plan =
        new SettlementPlan(Map.of(HEX, POPULATION_PER_HEX), List.of(), Map.of(), 250L, 0L);
    String payload =
        EconomySeeder.payload(MAP_ID, PopulationSeeder.groups(plan, 0L), at -> "plains");
    SimulationState emptyState =
        new SimulationState(
            stateMeta(), snapshots(EconomyData.empty()), InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(emptyState, payload);
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet change = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return EconomyChangeSet.apply(change, EconomyData.empty());
  }

  /**
   * 该格**农村家户行**（H0.2 起行 = {@code (格, 居住类型, 阶层)}，**行里没有产业了**）。
   *
   * <p>★ 本夹具一格、无城 ⇒ {@code (0,0)} 上的农村四行**就是**供给农业与家庭纺织的那两批人（旧版 {@code farm@0_0} 那四行逐值对应）；
   * 该格没有城镇批次 ⇒ 没有 {@code URBAN} 行。★ 按键的 {@code hex} / {@code residence} 过滤是 H0 之后**唯一**的筛法。
   */
  private static List<ClassRow> farmRows(EconomyData data) {
    List<ClassRow> rows = new ArrayList<>();
    for (Map.Entry<CohortKey, ClassRow> entry : data.classes().entrySet()) {
      CohortKey key = entry.getKey();
      if (key.hex().equals(HEX) && key.residence() == ResidenceKind.RURAL) {
        rows.add(entry.getValue());
      }
    }
    return rows;
  }

  /**
   * ★★ <b>该格农业"本行那一份规模"的亩数</b>（H0.3/K3 之后行上没有土地了 ⇒ 份额只能现算）。
   *
   * <pre>
   * 规模 scale   = ⌊农业产业 capacity[LAND] ÷ capacityPerUnit[LAND]⌋   = 3,100 亩（唯一真相在 {@code Industry.capacity}）
   * 本行份额      = ⌊scale × 本行人口 ÷ 该产业家户行的人口和⌋
   * </pre>
   *
   * <p>★ 与 {@code EconomySettlement.rowSharesOf} 是**同一个算式**（那里的"该产业家户行"= 由劳动配额表推出的本格农村四行）——
   * 于是"每行想扣多少种子"与这里读到的份额同源，而不是把结算的算式在本文件里再抄一遍另一套。
   */
  private static long rowShareMu(long scaleMu, long population, long householdPopulation) {
    return scaleMu * population / householdPopulation;
  }

  /** 该格农业的规模（亩）= {@code capacity[LAND] ÷ capacityPerUnit[LAND]}（两位都是千分亩 ⇒ 结果是亩）。 */
  private static long farmScaleMu(EconomyData data) {
    Industry farm = data.industries().get(IndustryHexKeys.id(EconomySeeder.FARM, 0, 0));
    long capacity = farm.capacity().getOrDefault(AssetKind.LAND, 0L);
    long perUnit = farm.capacityPerUnit().getOrDefault(AssetKind.LAND, 1L);
    return capacity / perUnit;
  }

  // ── ①′ R2：真档规模上"行"与"配额"逐值对拍（改口径不改数）────────────────────────────

  /**
   * ★★ **R2（T3）+ R3（T4）：真档规模上，各产业的当日劳动 = 该产业的配额之和，且**两个产业的配额之和** == 各行折算出的日劳动**。
   *
   * <pre>
   * Σ 行折算 = Σ_i 行 laborMilli_i × 槽位投入率_i ÷ 1000        （**改口径前的当日劳动**，只有农业行）
   * Σ 配额   = Σ 农业配额 + Σ 家庭纺织配额  ==  Σ 行折算          （R2 判据在 R3 之后的形式）
   * 农业配额 = Σ 行折算 × (1000 − WEAVE_SHARE) ÷ 1000             （R3：同一批人的劳动拆成两条配额）
   * 结算后    = Industry.cycleLaborMilli（第 1 天，**逐产业**）
   * </pre>
   *
   * <p>★★ **它仍然是"真档数字一个都不变"最直接的一条判据**：真档种子的瓶颈（3,098 亩）与土地（3,100 亩）都**不是劳动** ⇒ 农业让出的那 100‰
   * 不动收获一分一厘（本文件其余关于种子瓶颈的字面量因此原样站得住）。 ★ 判别力：三者中任何一处改口径而另两处没跟上，本条当场红。
   */
  @Test
  void theRealScaleQuotasEqualTheRowsAndTheSettledDailyLabor() {
    EconomyData seeded = realScaleHex();
    IndustryId farm = IndustryHexKeys.id(EconomySeeder.FARM, 0, 0);
    IndustryId weave = IndustryHexKeys.id(EconomySeeder.WEAVE, 0, 0);

    long farmQuota = 0L;
    long weaveQuota = 0L;
    for (LaborAllocation allocation : seeded.allocations().values()) {
      if (allocation.actor().id().equals(farm.value())) {
        farmQuota += allocation.laborMilli();
      } else {
        assertThat(allocation.actor().id()).as("农村那一池的配额只归农业与家庭纺织").isEqualTo(weave.value());
        weaveQuota += allocation.laborMilli();
      }
    }
    long rowSum = 0L;
    for (ClassRow row : farmRows(seeded)) {
      rowSum += row.laborMilli() * row.participationPerMille() / 1000L;
    }
    assertThat(farmQuota + weaveQuota)
        .as("① 两条配额之和 == ② 各行折算的日劳动（改口径不改数：R2 的判据在 R3 之后的形式）")
        .isEqualTo(rowSum);
    assertThat(weaveQuota)
        .as("★★ R3 的判据：农村批次**真的**把一成劳动给了纺织（非零 ⇒ 织机有活干 ⇒ 报表里看得见）")
        .isEqualTo(rowSum * EconomySeeder.WEAVE_SHARE_PER_MILLE / 1000L)
        .isPositive();

    EconomyData afterOneDay = EconomyOwnershipFixture.advanceEconomy(seeded, MAP_ID, 1L);
    assertThat(afterOneDay.industries().get(farm).cycleLaborMilli())
        .as("③ 农业第 1 天累加的就是它那一条配额（劳动投入取自配额表）")
        .isEqualTo(farmQuota);
    assertThat(afterOneDay.industries().get(weave).cycleLaborMilli())
        .as("③ 纺织第 1 天累加的是它那一条配额")
        .isEqualTo(weaveQuota);
  }

  /**
   * ★ **每条配额都在它的批次可支配劳动的范围内**（真档规模上的守恒；{@code Σ ≤ available} 逐组成立）。
   *
   * <p>★ 判别力：播种器若把同一批人的劳动同时算给两个产业（或忘了按毛额折算），本条的 {@code ≤} 会红。
   */
  @Test
  void everyRealScaleQuotaStaysWithinItsBatch() {
    EconomyData seeded = realScaleHex();

    Map<PeopleLotId, Long> allocated = new HashMap<>();
    for (LaborAllocation allocation : seeded.allocations().values()) {
      allocated.merge(allocation.group(), allocation.laborMilli(), Long::sum);
    }
    assertThat(allocated).as("有配额 ⇒ 必有供给").isNotEmpty();
    for (Map.Entry<PeopleLotId, Long> entry : allocated.entrySet()) {
      LaborSupply supply = seeded.laborSupply().get(entry.getKey());
      assertThat(supply).as("批次 %s 必须有供给记录", entry.getKey()).isNotNull();
      assertThat(entry.getValue())
          .as("批次 %s：Σ 配额 ≤ 可用劳动", entry.getKey())
          .isLessThanOrEqualTo(supply.availableLabor());
      assertThat(allocationPeriod(seeded, entry.getKey())).as("配额与供给同期").isEqualTo(supply.period());
    }
  }

  private static long allocationPeriod(EconomyData data, PeopleLotId group) {
    for (LaborAllocation allocation : data.allocations().values()) {
      if (allocation.group().equals(group)) {
        return allocation.period();
      }
    }
    throw new AssertionError("没有配额: " + group);
  }

  // ── ① 真档真的配了种子、且真的扣了（满种）────────────────────────────────────────────

  /**
   * ★★ **真档载荷里带着定案数**（8 粮/亩），且**每一行都付得起自己那份** ⇒ 播种日扣满 {@code 3,098 亩 × 8,000 =
   * 24,784,000 毫粮}（真档的"标定实质不变"就建立在"种子买得起"这一点上）。
   *
   * <p>★ **3,098 而非 3,100**：H0.3/K3 把产能从"行"搬到产业之后，"本行想扣多少"改成 {@code
   * ⌊产业规模 × 本行人口 ÷ 本格农村人口⌋}（{@code EconomySettlement.rowSharesOf} 是唯一算式）—— 四行**各自**向下取整
   * （1,395 / 1,084 / 465 / 154），故 Σ份额 ≤ 产业规模。★ 实测：H0 前那条"按行土地的千分亩折亩"也恰得 3,098（3,098 不是巧合，
   * 是同一批人口的同一组份额），本用例因此**逐值不变**。★ 方向是安全的：第三路只**缩**面积、永不放大（{@code seedCapMu ≤ availableMu}）。
   */
  @Test
  void theRealScaleHexSowsEveryMuItHasMoneyForOnTheSowingDay() {
    EconomyData seeded = realScaleHex();
    assertThat(seeded.industries()).as("一格、无城 ⇒ 农业 + 家庭纺织两个产业（R3 起有农村人口的格都有织机）").hasSize(2);

    // ★ R3：投入表的值侧带商品维度（{"LAND":{"grain":8000}}）⇒ 断言落在**内层**那张商品表上；
    //   且只对**农业**断言（家庭纺织的投入挂在 TOOL 上、耗的是纤维，不是每亩需种）。
    Industry farm = seeded.industries().get(IndustryHexKeys.id(EconomySeeder.FARM, 0, 0));
    assertThat(farm.cycleInputPerUnit().get(AssetKind.LAND))
        .as("播种器给真档的农业配了每亩需种")
        .containsEntry(EconomySettlement.GRAIN, EconomySeeder.SEED_MILLI_PER_MU);
    // ★★ H0.3（K3）：规模的唯一真相在**产业的产能**上（行里已经没有土地了）。
    long scaleMu = farmScaleMu(seeded);
    List<ClassRow> rows = farmRows(seeded);
    long householdPopulation = rows.stream().mapToLong(ClassRow::population).sum();
    long needMilli = 0L;
    for (ClassRow row : rows) {
      // 本行那一份种子 = 本行份额（亩）× 每亩需种（毫粮/亩）
      long need = rowShareMu(scaleMu, row.population(), householdPopulation) * EconomySeeder.SEED_MILLI_PER_MU;
      needMilli += need;
      assertThat(row.goods().getOrDefault(EconomySettlement.GRAIN, 0L))
          .as("行 %s 的储备必须付得起它那一份种子（%d 毫粮）", row.key(), need)
          .isGreaterThanOrEqualTo(need);
    }
    assertThat(needMilli).as("满种量 = 3,098 亩 × 8,000 毫粮/亩").isEqualTo(24_784_000L);

    EconomyData sowingDay = EconomyOwnershipFixture.advanceEconomy(seeded, MAP_ID, 1L);

    assertThat(totalSown(sowingDay)).as("播种日扣满（第三路瓶颈**存在**：它决定投入面积）").isEqualTo(needMilli);
    assertThat(totalSown(sowingDay)).as("★ 判别力：与「没配种子」（恒 0）必须不同").isNotZero();
  }

  /**
   * ★★ **收获面积由"实际扣到的种子"决定**（第三路瓶颈逐值）：可支撑 {@code 24,784,000 ÷ 8,000 = 3,098 亩}， 小于产业产能 3,100
   * 亩、远小于劳动可经营的 51,698 亩 ⇒ **它是那一年最短的那块**。
   *
   * <p>★ 3,098 而非 3,100：{@code ⌊产业规模 × 本行人口 ÷ 家户人口和⌋} 是**逐行向下取整**的，四行合计少了 2 亩。第三路只**缩**
   * 面积、永不放大（{@code seedCapMu ≤ availableMu}），这个方向是安全的。
   */
  @Test
  void theSownSeedIsTheBottleneckThatDecidesTheHarvestArea() {
    EconomyData seeded = realScaleHex();
    long scaleMu = farmScaleMu(seeded);
    List<ClassRow> rows = farmRows(seeded);
    long householdPopulation = rows.stream().mapToLong(ClassRow::population).sum();
    long seedCapMu = 0L;
    for (ClassRow row : rows) {
      seedCapMu += rowShareMu(scaleMu, row.population(), householdPopulation);
    }
    assertThat(seedCapMu).as("可支撑亩 = Σ 本行份额（满种时它恰等于产业规模的向下取整损失后的那份）").isEqualTo(3_098L);

    EconomyOwnershipFixture.Result afterHarvest =
        EconomyOwnershipFixture.advance(
            seeded, EconomyOwnershipFixture.NO_BOOKS, MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);

    // ★★ T4 起**净产**要在两处合读：行里收到的**关系入账** + {@code operator} 账上留下的那一份
    //   （账户 = {@code (ESTATE:farm@0_0, 0_0)}）。毛产 = 净产 ÷ 0.97（损耗 30‰ 只进 `ProductionLedger.losses`）。
    assertThat(harvestGrainNet(afterHarvest))
        .as("净产 = 3,098 亩 × 67 粮/亩 × 1000 × 0.97（**按种子可支撑的亩数**，不是按 3,100 亩）")
        .isEqualTo(3_098L * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L * 970L / 1000L);
    assertThat(harvestGrainNet(afterHarvest))
        .as("★ 判别力：若第三路没进 min（退回两路），这里会是 3,100 亩的 201,469,000")
        .isNotEqualTo(
            EconomySeeder.MU_PER_HEX * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L * 970L / 1000L);
  }

  // ── ② 缸空 ⇒ 颗粒无收；未配种子的对照格照常收获 ───────────────────────────────────────

  /**
   * ★★ **"冬春把缸吃空 ⇒ 播种日扣不到 ⇒ 减产"在真档量级上的极端形态**：把这一格各行的粮清空 ⇒ 播种日扣 0 ⇒ {@code seedCapMu = 0} ⇒
   * **颗粒无收**；而**同样清空、但没配种子**的对照格照常按土地满产 （3,100 亩 × 67 × 1000 × 0.97 = 201,469,000 毫粮）——
   * 两条并排即"种子确实是第三路瓶颈"的证据。
   */
  @Test
  void anEmptyJarYieldsNothingInTheRealWorldWhileTheUnseededControlStillHarvests() {
    EconomyData seeded = realScaleHex();
    EconomyData emptied = withEmptyJars(seeded);
    EconomyData emptiedUnseeded = withEmptyJars(withoutSeedRate(seeded));

    // ★ 前提：确实清空了（否则下一条断言测的是别的东西）
    for (ClassRow row : emptied.classes().values()) {
      assertThat(row.goods()).as("行 %s 的缸已清空", row.key()).isEmpty();
    }

    EconomyOwnershipFixture.Result starved =
        EconomyOwnershipFixture.advance(
            emptied, EconomyOwnershipFixture.NO_BOOKS, MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);
    EconomyOwnershipFixture.Result control =
        EconomyOwnershipFixture.advance(
            emptiedUnseeded,
            EconomyOwnershipFixture.NO_BOOKS,
            MAP_ID,
            0L,
            EconomySeeder.CYCLE_DAYS);

    assertThat(harvestGrainNet(starved)).as("扣不到种 ⇒ 0 亩 ⇒ 不产粮").isZero();
    assertThat(hexGrain(starved.economy())).as("缸本来空、又不产粮 ⇒ 终态为 0").isZero();
    assertThat(starved.economy().industries().values())
        .allSatisfy(
            industry -> assertThat(industry.cycleSeedUsedMilli()).as("周期已关账 ⇒ 累加器清零").isZero());

    assertThat(harvestGrainNet(control))
        .as("★ 未配种子的对照格：第三路不施加约束 ⇒ 按产能 3,100 亩满产的**净额**（扣饲料 0‰ + 折旧 30‰）")
        .isEqualTo(201_469_000L);
    assertThat(hexGrain(control.economy()))
        .as("行侧的入账非零（T4 起产出两处落：行里那份 + operator 账上那份）")
        .isPositive();
  }

  /**
   * ★★ <b>本格这一周期农业的粮**净产**</b>（T4 起两处合读）：行里收到的**关系入账** + {@code operator} 账上留下的那一份。 ★ 少了任何一半都会读小 ——
   * 这正是"产出离开 {@code ClassRow}"的后果；毛产 = 它 ÷ 0.97（生产损耗 30‰）。
   */
  private static long harvestGrainNet(EconomyOwnershipFixture.Result result) {
    long rows = 0L;
    for (ClassRow row : result.economy().classes().values()) {
      io.mosire.simos.economy.model.FlowRow flow = result.economy().flows().get(row.key());
      if (flow != null) {
        rows += flow.income().getOrDefault(EconomySettlement.GRAIN, 0L);
      }
    }
    io.mosire.simos.economy.model.Industry farm =
        result.economy().industries().get(IndustryHexKeys.id(EconomySeeder.FARM, 0, 0));
    io.mosire.simos.actor.model.GoodsAccount account =
        result
            .actor()
            .accounts()
            .get(
                new io.mosire.simos.actor.model.GoodsAccountKey(
                    farm.operator(), new HexCoord(0, 0)));
    return rows
        + (account == null ? 0L : account.balances().getOrDefault(EconomySettlement.GRAIN, 0L));
  }

  // ── 夹具与读数 ───────────────────────────────────────────────────────────────────────

  private static StateMeta stateMeta() {
    return new StateMeta(REF, SimosTimestamp.of(0));
  }

  private static Map<String, Snapshot> snapshots(EconomyData data) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(REF, SimosTimestamp.of(0), data));
    return modules;
  }

  /** 把每一行的粮清空（"冬春把缸吃空"）；其余字段原样带过（★ H0.3：行里**没有** {@code meansOfProduction} 了）。 */
  private static EconomyData withEmptyJars(EconomyData data) {
    LinkedHashMap<CohortKey, ClassRow> rows = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, ClassRow> entry : data.classes().entrySet()) {
      ClassRow row = entry.getValue();
      rows.put(
          entry.getKey(),
          new ClassRow(
              row.key(),
              row.population(),
              row.laborMilli(),
              row.participationPerMille(),
              Map.of(),
              row.money(),
              row.debts(),
              row.naturalNeeds(),
              row.effectiveDemand()));
    }
    return data.withClasses(rows);
  }

  /** 把每个产业的 {@code cycleInputPerUnit} 清空（= 未配投入的对照格）；其余字段原样带过。 */
  private static EconomyData withoutSeedRate(EconomyData data) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : data.industries().entrySet()) {
      Industry industry = entry.getValue();
      industries.put(
          entry.getKey(),
          new Industry(
              industry.id(),
              industry.name(),
              industry.regime(),
              industry.cycleDays(),
              industry.progressDays(),
              industry.capacityPerUnit(),
              // ★★ H0.3（K3）：产能是**存量、是事实**，清投入这一张表不动它 ⇒ 原样透传。
              industry.capacity(),
              industry.dailyInputPerUnit(),
              industry.dailyLaborPerUnit(),
              industry.laborPerUnit(),
              industry.outputPerUnit(),
              Map.of(),
              industry.slots(),
              industry.allocation(),
              industry.cycleLaborMilli(),
              industry.cycleInputUsedMilli(),
              // ★★ **重建点 ⇒ 透传**（不是重新推导）：本方法只清投入那一张表，其余字段全部原样带过。
              industry.operator()));
    }
    return data.withIndustries(industries);
  }

  /** 该格 Σ 行库存（毫粮）。 */
  private static long hexGrain(EconomyData data) {
    return data.classes().values().stream()
        .mapToLong(row -> row.goods().getOrDefault(EconomySettlement.GRAIN, 0L))
        .sum();
  }

  /** Σ 产业的"本周期实际扣到的种子"（毫粮；周期关账后清零）。 */
  private static long totalSown(EconomyData data) {
    return data.industries().values().stream().mapToLong(Industry::cycleSeedUsedMilli).sum();
  }

  /**
   * 该格的**粮的收获毛产**（毫粮）：流水所得里**粮那一维**的合计（= 净得 + 其份额的生产损耗 = 毛产）——用一个**独立可算**的量反推投入面积， 而不是把 {@code 亩 ×
   * 亩产 × 1000} 在本文件里再抄一遍。
   *
   * <p>★ R3：{@code income} 是**逐商品**的表（田里同时出粮与纤维）⇒ 必须指名粮那一维；把两种商品加在一起会得到 "粮 + 纤维"的和，本文件的每条字面量都会错。
   */
  private static long harvestGrainGross(EconomyData data) {
    long income = 0L;
    for (FlowRow flow : data.flows().values()) {
      income += flow.income().getOrDefault(EconomySettlement.GRAIN, 0L);
    }
    return income;
  }
}
